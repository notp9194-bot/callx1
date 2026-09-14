package com.callx.app.conversation.controllers;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.callx.app.cache.CameraProviderCache;
import com.callx.app.chat.R;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * Instagram/Telegram-style in-app camera for the chat attach flow (see
 * screenshots that drove this: tap the shutter for a photo, hold it for a
 * video — no leaving the app to the system Camera). Replaces the old
 * ChatMediaController#launchCamera() path, which shelled out to the system
 * Camera app via ActivityResultContracts.TakePicture() — that hop was the
 * root cause of a separate bug where a low-RAM device would kill CallX's
 * process while the system Camera had focus, silently dropping the photo on
 * return (see ChatMediaController's cameraOutputUri history). Staying in-app
 * for the whole capture avoids that failure mode at the source, since the
 * app is never backgrounded during capture.
 *
 * Output is always written straight to MediaStore (like the old flow did)
 * rather than kept in a private file, so the result is a stable content://
 * Uri that survives regardless of what happens to this Activity afterwards —
 * same reasoning as the cameraOutputUri persistence fix.
 *
 * Result contract: RESULT_OK with RESULT_URI (String) + RESULT_IS_VIDEO
 * (boolean) extras. ChatMediaController forwards these straight into
 * MediaEditActivity for caption/crop/send, same screen the gallery-attach
 * flow already uses.
 */
public class ChatCameraActivity extends AppCompatActivity {

    private static final String TAG = "ChatCameraActivity";

    public static final String RESULT_URI      = "chat_camera_result_uri";
    public static final String RESULT_IS_VIDEO = "chat_camera_result_is_video";

    private static final int REQ_AUDIO_FOR_VIDEO = 771;

    // Holding the shutter longer than this starts video instead of a photo.
    private static final long LONG_PRESS_THRESHOLD_MS = 350;

    private PreviewView previewView;
    private ImageButton btnClose, btnFlash, btnFlip;
    private FrameLayout btnShutter;
    private View shutterRing, shutterInner;
    private TextView tvHint, tvRecordTimer;

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private Camera camera;

    // PERF (advanced optimization #6): built at ACTION_DOWN, before we even
    // know yet whether this touch will turn into a tap (photo) or a hold
    // (video) — see setupShutterTouch(). If RECORD_AUDIO is already granted
    // (true for anyone who's recorded a video before), pendingRecording is a
    // fully-built PendingRecording sitting ready the instant the 350ms hold
    // threshold fires, so startRecording() just calls .start() — none of
    // the ContentValues/MediaStoreOutputOptions/prepareRecording() work that
    // otherwise sits directly in the hold-to-record-starts critical path.
    // Never reused across gestures — always rebuilt fresh next ACTION_DOWN
    // and discarded (see discardPendingRecording()) if that touch didn't
    // turn into a recording, since it's tied to a specific MediaStore
    // filename generated at build time.
    private PendingRecording pendingRecording;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private boolean flashOn = false;
    private boolean isRecording = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    // PERF: builds Preview/ImageCapture/Recorder/VideoCapture/CameraSelector
    // off the main thread before handing off to bindToLifecycle() — see
    // bindCameraUseCases(). Single-thread is enough; this only ever runs one
    // bind at a time (camera open, and camera-flip).
    private final java.util.concurrent.ExecutorService bgExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private Runnable longPressRunnable;
    private boolean longPressFired = false;
    private long recordStartMs = 0;
    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (!isRecording) return;
            long secs = (System.currentTimeMillis() - recordStartMs) / 1000;
            tvRecordTimer.setText(String.format(Locale.US, "%d:%02d", secs / 60, secs % 60));
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_camera);

        previewView   = findViewById(R.id.camera_preview);
        // BUG FIX: PERFORMANCE mode backs the preview with a SurfaceView.
        // A SurfaceView punches its own hole in the window and composites
        // as a separate hardware layer OUTSIDE the normal View drawing
        // pass — it does NOT reliably co-exist with sibling views on top of
        // it that keep invalidating (this screen overlays the shutter
        // button/ring, flash/flip buttons, hint text, and a record timer
        // that ticks every 500ms — all in the same FrameLayout, directly
        // above camera_preview in activity_chat_camera.xml). On plenty of
        // GPU/OEM skins that repeated overlay invalidation desyncs the
        // SurfaceView's own buffer swap from the rest of the window: the
        // feed shows correctly for the first second or two, then visibly
        // freezes on whatever frame was last composited, exactly the
        // "dikhta hai, phir ruk jata hai" symptom, even though CameraX is
        // still happily producing frames underneath.
        // FIX: COMPATIBLE mode renders through a TextureView instead, which
        // is a normal View — it draws in-order with its siblings on every
        // invalidation instead of as an independent hardware layer, so the
        // overlaid buttons/timer can update freely without ever knocking
        // the preview out of sync. This is also what CameraX's own docs
        // recommend once anything is drawn on top of/around the preview.
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        btnClose      = findViewById(R.id.btn_camera_close);
        btnFlash      = findViewById(R.id.btn_camera_flash);
        btnFlip       = findViewById(R.id.btn_camera_flip);
        btnShutter    = findViewById(R.id.btn_camera_shutter);
        shutterRing   = findViewById(R.id.shutter_ring);
        shutterInner  = findViewById(R.id.shutter_inner);
        tvHint        = findViewById(R.id.tv_camera_hint);
        tvRecordTimer = findViewById(R.id.tv_record_timer);

        btnClose.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        btnFlash.setOnClickListener(v -> toggleFlash());
        btnFlip.setOnClickListener(v -> { lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
            // A pendingRecording (if any) was built against the videoCapture
            // use case instance that's about to be torn down/rebuilt below —
            // discard it so startRecording() falls back to building fresh
            // against whichever camera is now bound.
            discardPendingRecording();
            bindCameraUseCases(); });

        setupShutterTouch();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            // ChatActivity already handles the CAMERA request/grant flow one
            // level up (ChatMediaController#launchCamera() only gets here
            // once permission is granted), but guard anyway in case this
            // Activity is ever launched some other way.
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 770);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == 770) {
            if (granted) startCamera();
            else { Toast.makeText(this, "Camera permission zaroori hai", Toast.LENGTH_SHORT).show(); finish(); }
        } else if (requestCode == REQ_AUDIO_FOR_VIDEO) {
            if (granted) startRecording();
            else Toast.makeText(this, "Video ke liye mic permission chahiye", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Shutter gesture: tap = photo, hold = video ──────────────────────────

    private void setupShutterTouch() {
        btnShutter.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    longPressFired = false;
                    // PERF (advanced optimization #6): see pendingRecording's
                    // field doc — only actually builds anything if
                    // RECORD_AUDIO is already granted; otherwise this is a
                    // cheap no-op and permission is still only requested
                    // below once a hold is confirmed, same as before (a
                    // plain tap must never trigger a mic-permission prompt).
                    preparePendingRecordingIfPossible();
                    longPressRunnable = () -> {
                        longPressFired = true;
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                            startRecording();
                        } else {
                            ActivityCompat.requestPermissions(this,
                                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO_FOR_VIDEO);
                        }
                    };
                    handler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    if (isRecording) {
                        stopRecording();
                    } else if (!longPressFired) {
                        capturePhoto();
                        // This touch resolved to a tap (photo), not a hold —
                        // any pendingRecording built for it above is now
                        // stale (its MediaStore filename/timestamp belongs
                        // to a recording that's never going to start).
                        discardPendingRecording();
                    }
                    return true;
            }
            return false;
        });
    }

    // ── CameraX setup ────────────────────────────────────────────────────

    private void startCamera() {
        // PERF: was ProcessCameraProvider.getInstance(this) directly — that
        // resolves cold (~100-300ms) the first time it's called in the
        // process. CameraProviderCache warms this up at app start
        // (CallxApp#onCreate) so by the time the user opens this screen the
        // future is already resolved or resolving, and this listener fires
        // near-instantly.
        ListenableFuture<ProcessCameraProvider> future = CameraProviderCache.get(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider init failed", e);
                Toast.makeText(this, "Camera start nahi ho paya", Toast.LENGTH_SHORT).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

        // PERF (advanced optimization: flip = selector switch, not a full
        // rebuild): Preview/ImageCapture/Recorder/VideoCapture only ever get
        // built once — the very first bindCameraUseCases() call after
        // startCamera(). A later camera flip reuses those exact same
        // UseCase instances; CameraX only needs a new CameraSelector +
        // rebind, not a second trip through bgExecutor re-resolving
        // ResolutionSelector/QualitySelector against the other lens. That
        // background-thread build is real, non-trivial work (see below) —
        // this skips it entirely on every flip after the first bind.
        if (preview != null) {
            rebindToLifecycle(selector);
            return;
        }

        // PERF: build the use-case objects (Preview/ImageCapture/Recorder/
        // VideoCapture/CameraSelector) on a background thread — constructing
        // these does non-trivial work (quality/capability resolution against
        // the camera's characteristics) that doesn't need the main thread.
        // NOTE: the actual cameraProvider.unbindAll()/bindToLifecycle(...)
        // call below CANNOT be moved off main thread — that's a hard CameraX/
        // AndroidX Lifecycle requirement (bindToLifecycle registers a
        // Lifecycle observer, and Lifecycle only permits that from the main
        // thread; calling it off-thread throws). So this only moves what's
        // legally movable — building the use-cases — off the launch path;
        // the unavoidable main-thread part is now just the actual bind call.
        bgExecutor.execute(() -> {
            // PERF (advanced optimization #2): cap both the live preview and
            // the still-capture at 1080p instead of leaving CameraX on its
            // default resolution pick, which on plenty of devices happily
            // selects the sensor's max (12MP+ stills, sometimes 4K-class
            // preview streams). Chat photos are shown as small compressed
            // bubbles and get re-compressed again before upload anyway (see
            // ImageCompressor/CloudinaryUploader) — capturing bigger than
            // 1080p buys nothing downstream and only costs extra capture +
            // JPEG-encode time (slower shutter-to-saved latency) and a
            // heavier buffer to push through the preview pipeline every
            // frame. FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER: prefer the
            // closest resolution at-or-below 1080p, only reach above it on a
            // device that genuinely can't do 1080p or lower.
            Size targetSize = new Size(1920, 1080);
            ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                    .setResolutionStrategy(new ResolutionStrategy(
                            targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build();

            Preview localPreview = new Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build();

            // PERF (advanced optimization: zero-shutter-lag): ZSL keeps a
            // short rolling buffer of frames from the sensor so takePicture()
            // can hand back a frame that was already captured close to the
            // moment the shutter was tapped, instead of triggering a brand
            // new capture at tap-time — noticeably faster tap-to-saved
            // latency than even MINIMIZE_LATENCY on devices that support it.
            // Deliberately NOT gated behind our own capability check here:
            // CameraX documents CAPTURE_MODE_ZERO_SHUTTER_LAG as
            // automatically/silently falling back to
            // CAPTURE_MODE_MINIMIZE_LATENCY on any camera/combination that
            // doesn't support it (including when bound alongside VideoCapture,
            // as this screen always does) — so requesting it is always safe,
            // and this screen already gets MINIMIZE_LATENCY as its floor
            // either way.
            ImageCapture localImageCapture = new ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG)
                    .setFlashMode(flashOn ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF)
                    .setResolutionSelector(resolutionSelector)
                    .build();

            // PERF: cap recording quality instead of leaving it on
            // QualitySelector auto-pick, which on a lot of devices happily
            // grabs the highest resolution/bitrate the sensor supports (up to
            // 4K) — that means heavier encoding work per frame, which is
            // exactly what causes recording to visibly lag/stutter. HD (720p) is
            // both a WhatsApp/Instagram-style chat-video-appropriate size AND
            // light enough to encode smoothly on mid/low-end devices; falls
            // back to the next-lower supported quality if a device can't do HD.
            QualitySelector qualitySelector = QualitySelector.from(
                    Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD));
            Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build();
            VideoCapture<Recorder> localVideoCapture = VideoCapture.withOutput(recorder);

            // Hop back to main for the part CameraX/Lifecycle actually
            // requires there, plus any UI touch.
            ContextCompat.getMainExecutor(this).execute(() -> {
                if (isFinishing() || isDestroyed() || cameraProvider == null) return; // activity gone while we were building off-thread
                preview = localPreview;
                imageCapture = localImageCapture;
                videoCapture = localVideoCapture;
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                rebindToLifecycle(selector);
            });
        });
    }

    /** Actual unbindAll()+bindToLifecycle() call, shared by both the
     *  first-ever bind (called from bindCameraUseCases() once the use-cases
     *  finish building) and every camera-flip rebind after that (called
     *  directly, reusing the already-built preview/imageCapture/
     *  videoCapture — see bindCameraUseCases()'s early-return above). Must
     *  run on the main thread — hard CameraX/Lifecycle requirement. */
    private void rebindToLifecycle(CameraSelector selector) {
        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, selector, preview, imageCapture, videoCapture);
            // Front camera has no torch/flash on almost every device — hide the toggle for it.
            boolean hasFlashUnit = camera.getCameraInfo().hasFlashUnit();
            btnFlash.setVisibility(hasFlashUnit ? View.VISIBLE : View.GONE);
        } catch (Exception e) {
            Log.e(TAG, "bindCameraUseCases failed", e);
            Toast.makeText(this, "Camera bind nahi ho paya", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleFlash() {
        flashOn = !flashOn;
        btnFlash.setImageResource(flashOn ? R.drawable.ic_camera_flash_on : R.drawable.ic_camera_flash_off);
        if (imageCapture != null) {
            imageCapture.setFlashMode(flashOn ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
        }
        if (camera != null && camera.getCameraInfo().hasFlashUnit() && isRecording) {
            camera.getCameraControl().enableTorch(flashOn);
        }
    }

    // ── Photo capture ────────────────────────────────────────────────────

    private void capturePhoto() {
        if (imageCapture == null) return;

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, "callx_" + System.currentTimeMillis() + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build();

        // Freeze the shutter briefly so a double-tap can't fire twice.
        btnShutter.setEnabled(false);

        imageCapture.takePicture(options, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        Uri uri = results.getSavedUri();
                        btnShutter.setEnabled(true);
                        if (uri != null) finishWithResult(uri, false);
                        else Toast.makeText(ChatCameraActivity.this,
                                "Photo save nahi hui, dubara try karein", Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "capturePhoto failed", exception);
                        btnShutter.setEnabled(true);
                        Toast.makeText(ChatCameraActivity.this,
                                "Photo capture fail ho gaya", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── Video capture ────────────────────────────────────────────────────

    /** Builds (but does not start) a PendingRecording ahead of the actual
     *  hold-confirmed start — see pendingRecording's field doc. Only does
     *  real work when RECORD_AUDIO is already granted; building a
     *  PendingRecording never touches the microphone or filesystem by
     *  itself (that only happens on .start()), so this is safe to call
     *  speculatively on every shutter touch-down, including ones that turn
     *  out to be a plain tap. */
    private void preparePendingRecordingIfPossible() {
        if (videoCapture == null || isRecording || pendingRecording != null) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return; // permission still requested only once a hold is confirmed
        }
        try {
            pendingRecording = videoCapture.getOutput()
                    .prepareRecording(this, buildVideoOutputOptions())
                    .withAudioEnabled();
        } catch (Exception e) {
            // Never let a speculative pre-build affect the real gesture —
            // startRecording() falls back to building this itself if
            // pendingRecording is still null when the hold fires.
            Log.w(TAG, "speculative prepareRecording failed, will retry on hold", e);
            pendingRecording = null;
        }
    }

    /** Discards a PendingRecording that was speculatively built for a touch
     *  which turned out to be a tap (photo), not a hold — it's tied to a
     *  MediaStore filename/timestamp for a recording that's never starting,
     *  so it must never be reused for a later gesture. */
    private void discardPendingRecording() {
        pendingRecording = null;
    }

    private MediaStoreOutputOptions buildVideoOutputOptions() {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, "callx_" + System.currentTimeMillis() + ".mp4");
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        return new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(cv)
                .build();
    }

    @androidx.annotation.RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private void startRecording() {
        if (videoCapture == null || isRecording) return;

        // PERF (advanced optimization #6): common case — pendingRecording
        // was already built back at ACTION_DOWN (mic permission was already
        // granted from a prior use), so there's nothing left to build here;
        // just start it. Falls back to building fresh only the first time
        // ever (permission just granted via the request above) or if the
        // speculative build failed for some reason.
        PendingRecording recording = pendingRecording;
        pendingRecording = null;
        if (recording == null) {
            recording = videoCapture.getOutput()
                    .prepareRecording(this, buildVideoOutputOptions())
                    .withAudioEnabled();
        }

        activeRecording = recording.start(ContextCompat.getMainExecutor(this), event -> {
            if (event instanceof VideoRecordEvent.Start) {
                onRecordingStarted();
            } else if (event instanceof VideoRecordEvent.Finalize) {
                VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                onRecordingFinished(fin);
            }
        });
    }

    private void onRecordingStarted() {
        isRecording = true;
        recordStartMs = System.currentTimeMillis();
        shutterRing.setBackgroundResource(R.drawable.bg_camera_shutter_recording);
        shutterInner.setVisibility(View.GONE);
        tvHint.setVisibility(View.GONE);
        tvRecordTimer.setVisibility(View.VISIBLE);
        tvRecordTimer.setText("0:00");
        btnFlip.setVisibility(View.GONE);
        handler.post(timerTick);
        if (camera != null && camera.getCameraInfo().hasFlashUnit() && flashOn) {
            camera.getCameraControl().enableTorch(true);
        }
    }

    private void stopRecording() {
        if (activeRecording != null) activeRecording.stop();
    }

    private void onRecordingFinished(VideoRecordEvent.Finalize fin) {
        isRecording = false;
        handler.removeCallbacks(timerTick);
        shutterRing.setBackgroundResource(R.drawable.bg_camera_shutter_ring);
        shutterInner.setVisibility(View.VISIBLE);
        tvRecordTimer.setVisibility(View.GONE);
        btnFlip.setVisibility(View.VISIBLE);
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            camera.getCameraControl().enableTorch(false);
        }

        if (fin.hasError()) {
            Log.e(TAG, "Video recording error: " + fin.getError());
            Toast.makeText(this, "Video record nahi ho paya", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri uri = fin.getOutputResults().getOutputUri();
        if (uri != null) finishWithResult(uri, true);
    }

    private void finishWithResult(Uri uri, boolean isVideo) {
        // PERF: kick off MediaEditActivity's layout pre-inflate the instant
        // a capture succeeds — see MediaEditPreloadCache — so by the time
        // ChatMediaController's chatCameraLauncher callback actually starts
        // MediaEditActivity (after this Activity finishes and control
        // returns up to ChatActivity), the pre-inflate has had this whole
        // finish()+transition window as a head start.
        MediaEditPreloadCache.warmUp(this);
        // PERF: same head start for Glide's memory cache — pre-decode the
        // just-captured media at the exact sizes the filter-strip and
        // bottom thumb-strip will request it at, so those Glide loads are
        // cache hits instead of fresh decodes the first time each shows up.
        MediaEditPreloadCache.preloadThumbnails(this, uri);

        Intent result = new Intent();
        result.putExtra(RESULT_URI, uri.toString());
        result.putExtra(RESULT_IS_VIDEO, isVideo);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        bgExecutor.shutdownNow();
        discardPendingRecording();
        if (activeRecording != null) activeRecording.close();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}
