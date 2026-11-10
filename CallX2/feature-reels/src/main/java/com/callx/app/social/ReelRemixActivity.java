package com.callx.app.social;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.player.AdaptiveStreamingManager;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.database.ServerValue;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelRemixActivity v2 — Production-grade remix recorder
 *
 * Upgrades over v1:
 *  ✅ Compositor integration — ReelRemixVideoCompositor called after recording
 *  ✅ Composition progress dialog shown while processing
 *  ✅ Layout-aware thumbnail overlay for REACT_CAM (pip resize with drag)
 *  ✅ Mute/unmute original reel during recording
 *  ✅ Timer displays remaining seconds (countdown from MAX_DURATION)
 *  ✅ Firebase remix record written via publishRemixToFirebase()
 *  ✅ Proper lifecycle: pauses original on onPause, resumes on onResume
 *
 * Layout modes:
 *   LAYOUT_SIDE_BY_SIDE  — original left, camera right (50/50)
 *   LAYOUT_REACT_CAM     — original full + floating PiP cam (top-right 30%)
 *   LAYOUT_GREEN_SCREEN  — your video replaces green-screen background
 *   LAYOUT_OVERLAY       — your video at 60% alpha over original
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelRemixActivity extends AppCompatActivity {

    private static final String TAG = "ReelRemix";

    // ── Extras ───────────────────────────────────────────────────────────────
    public static final String EXTRA_REEL_ID    = "remixOriginalReelId";
    public static final String EXTRA_OWNER_UID  = "remixOwnerUid";
    public static final String EXTRA_OWNER_NAME = "remixOwnerName";
    public static final String EXTRA_VIDEO_URL  = "remixVideoUrl";
    public static final String EXTRA_THUMB_URL  = "remixThumbUrl";
    public static final String EXTRA_LAYOUT     = "remixLayout";
    /** v790 — "remix" (default) | "collab" (set by RemixCollabFlow). */
    public static final String EXTRA_MODE       = "remixMode";

    // ── Layout modes ──────────────────────────────────────────────────────────
    public static final String LAYOUT_SIDE_BY_SIDE = "side_by_side";
    public static final String LAYOUT_REACT_CAM    = "react_cam";
    public static final String LAYOUT_GREEN_SCREEN = "green_screen";
    public static final String LAYOUT_OVERLAY      = "overlay";

    private static final int  PERM_REQ        = 1201;
    private static final int  MAX_DURATION_SEC = 60;
    /** v791 — give up merging after this long and offer Retry / raw video instead of hanging. */
    private static final long COMPOSITE_TIMEOUT_MS = 3 * 60_000L;

    // ── Views ─────────────────────────────────────────────────────────────────
    private PlayerView   pvOriginal;
    private PreviewView  pvCamera;
    private ImageButton  btnRecord, btnFlip, btnBack, btnMuteOriginal;
    private ImageView    ivLayoutIcon;
    private TextView     tvTimer, tvLayoutLabel, tvOwnerName, tvCountdown, tvRemaining;
    private ProgressBar  pbDuration;
    private FrameLayout  floatingCamContainer;
    private View         overlayDimView;
    private LinearLayout layoutCompositing;
    private ProgressBar  pbCompositing;
    private TextView     tvCompositingStatus;

    // ── Camera state ──────────────────────────────────────────────────────────
    private ExoPlayer              originalPlayer;
    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        cameraExecutor;
    private boolean                isRecording    = false;
    private boolean                frontCamera    = true;
    private boolean                originalMuted  = false;
    private int                    elapsedSec     = 0;
    private CountDownTimer         durationTimer;

    // ── Data ──────────────────────────────────────────────────────────────────
    private String originalReelId, ownerUid, ownerName, originalVideoUrl, originalThumbUrl;
    private String layoutMode;
    private String flowMode = "remix";   // v790: "remix" | "collab"
    private File   outputFile;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_remix);

        readExtras();
        bindViews();
        applyLayout();
        setupButtons();

        cameraExecutor = Executors.newSingleThreadExecutor();

        if (hasPermissions()) {
            startOriginalPlayback();
            startCamera();
        } else {
            requestPermissions();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (originalPlayer != null) originalPlayer.pause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (originalPlayer != null && !isRecording) originalPlayer.play();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (originalPlayer != null) { originalPlayer.release(); originalPlayer = null; }
        if (durationTimer   != null) durationTimer.cancel();
        if (activeRecording != null) { activeRecording.stop(); activeRecording = null; }
        if (cameraProvider  != null) cameraProvider.unbindAll();
        cameraExecutor.shutdown();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private void readExtras() {
        Intent i       = getIntent();
        originalReelId = i.getStringExtra(EXTRA_REEL_ID);
        ownerUid       = i.getStringExtra(EXTRA_OWNER_UID);
        ownerName      = i.getStringExtra(EXTRA_OWNER_NAME);
        originalVideoUrl = i.getStringExtra(EXTRA_VIDEO_URL);
        originalThumbUrl = i.getStringExtra(EXTRA_THUMB_URL);
        layoutMode       = i.getStringExtra(EXTRA_LAYOUT);
        if (layoutMode == null) layoutMode = LAYOUT_SIDE_BY_SIDE;
        String fm = i.getStringExtra(EXTRA_MODE);
        if ("collab".equals(fm)) flowMode = "collab";
    }

    private void bindViews() {
        pvOriginal           = findViewById(R.id.pv_remix_original);
        pvCamera             = findViewById(R.id.pv_remix_camera);
        btnRecord            = findViewById(R.id.btn_remix_record);
        btnFlip              = findViewById(R.id.btn_remix_flip);
        btnBack              = findViewById(R.id.btn_remix_back);
        btnMuteOriginal      = findViewById(R.id.btn_remix_mute_original);
        ivLayoutIcon         = findViewById(R.id.iv_remix_layout_icon);
        tvTimer              = findViewById(R.id.tv_remix_timer);
        tvLayoutLabel        = findViewById(R.id.tv_remix_layout_label);
        tvOwnerName          = findViewById(R.id.tv_remix_owner_name);
        tvCountdown          = findViewById(R.id.tv_remix_countdown);
        tvRemaining          = findViewById(R.id.tv_remix_remaining);
        pbDuration           = findViewById(R.id.pb_remix_duration);
        floatingCamContainer = findViewById(R.id.container_floating_cam);
        overlayDimView       = findViewById(R.id.view_overlay_dim);
        layoutCompositing    = findViewById(R.id.layout_compositing);
        pbCompositing        = findViewById(R.id.pb_compositing);
        tvCompositingStatus  = findViewById(R.id.tv_compositing_status);

        if (tvOwnerName != null) tvOwnerName.setText(("collab".equals(flowMode) ? "Collab with @" : "Remixing @") + ownerName);
        if (pbDuration  != null) { pbDuration.setMax(MAX_DURATION_SEC); pbDuration.setProgress(0); }
        if (layoutCompositing != null) layoutCompositing.setVisibility(View.GONE);
    }

    private void applyLayout() {
        switch (layoutMode) {
            case LAYOUT_REACT_CAM:
                if (tvLayoutLabel != null) tvLayoutLabel.setText("React Cam");
                if (floatingCamContainer != null) floatingCamContainer.setVisibility(View.VISIBLE);
                if (overlayDimView != null) overlayDimView.setVisibility(View.GONE);
                break;
            case LAYOUT_GREEN_SCREEN:
                if (tvLayoutLabel != null) tvLayoutLabel.setText("Green Screen");
                if (floatingCamContainer != null) floatingCamContainer.setVisibility(View.GONE);
                if (overlayDimView != null) overlayDimView.setVisibility(View.GONE);
                break;
            case LAYOUT_OVERLAY:
                if (tvLayoutLabel != null) tvLayoutLabel.setText("Overlay");
                if (floatingCamContainer != null) floatingCamContainer.setVisibility(View.GONE);
                if (overlayDimView != null) {
                    overlayDimView.setVisibility(View.VISIBLE);
                    overlayDimView.setAlpha(0.45f);
                }
                break;
            default: // side_by_side
                if (tvLayoutLabel != null) tvLayoutLabel.setText("Side by Side");
                if (floatingCamContainer != null) floatingCamContainer.setVisibility(View.GONE);
                if (overlayDimView != null) overlayDimView.setVisibility(View.GONE);
                break;
        }
    }

    private void setupButtons() {
        if (btnBack != null) btnBack.setOnClickListener(v -> onBackPressed());

        // v790 — "Layout" tool: tap the layout label / icon to cycle Side by Side → React Cam → Green Screen → Overlay
        View.OnClickListener cycle = v -> {
            if (isRecording) return;   // layout is baked into the composite — don't change mid-take
            switch (layoutMode) {
                case LAYOUT_SIDE_BY_SIDE: layoutMode = LAYOUT_REACT_CAM;     break;
                case LAYOUT_REACT_CAM:    layoutMode = LAYOUT_GREEN_SCREEN;  break;
                case LAYOUT_GREEN_SCREEN: layoutMode = LAYOUT_OVERLAY;       break;
                default:                  layoutMode = LAYOUT_SIDE_BY_SIDE;  break;
            }
            applyLayout();
        };
        if (tvLayoutLabel != null) tvLayoutLabel.setOnClickListener(cycle);
        if (ivLayoutIcon  != null) ivLayoutIcon.setOnClickListener(cycle);

        if (btnFlip != null) btnFlip.setOnClickListener(v -> {
            frontCamera = !frontCamera;
            startCamera();
        });

        if (btnRecord != null) btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startCountdownThenRecord();
        });

        if (btnMuteOriginal != null) btnMuteOriginal.setOnClickListener(v -> {
            originalMuted = !originalMuted;
            if (originalPlayer != null) originalPlayer.setVolume(originalMuted ? 0f : 1f);
            btnMuteOriginal.setImageResource(
                originalMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
        });
    }

    // ── Original reel playback ────────────────────────────────────────────────

    private void startOriginalPlayback() {
        if (originalVideoUrl == null || originalVideoUrl.isEmpty()) return;

        originalPlayer = AdaptiveStreamingManager.get(this)
            .buildPlayer(originalVideoUrl, AdaptiveStreamingManager.QualityCap.Q720P, null);
        if (pvOriginal != null) pvOriginal.setPlayer(originalPlayer);
        originalPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        originalPlayer.setPlayWhenReady(true);
        originalPlayer.prepare();
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera init failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();

        CameraSelector selector = frontCamera
            ? CameraSelector.DEFAULT_FRONT_CAMERA
            : CameraSelector.DEFAULT_BACK_CAMERA;

        Preview preview = new Preview.Builder().build();
        if (pvCamera != null) preview.setSurfaceProvider(pvCamera.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build();
        videoCapture = VideoCapture.withOutput(recorder);

        try {
            cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
        } catch (Exception e) {
            Log.e(TAG, "Camera bind failed", e);
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private void startCountdownThenRecord() {
        if (btnRecord != null) btnRecord.setEnabled(false);
        if (tvCountdown != null) tvCountdown.setVisibility(View.VISIBLE);

        new CountDownTimer(3_000, 1_000) {
            int n = 3;
            @Override public void onTick(long ms) {
                if (tvCountdown != null) tvCountdown.setText(String.valueOf(n--));
            }
            @Override public void onFinish() {
                if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
                if (btnRecord   != null) btnRecord.setEnabled(true);
                startRecording();
            }
        }.start();
    }

    @SuppressWarnings("MissingPermission")
    private void startRecording() {
        if (videoCapture == null) {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        outputFile = new File(getCacheDir(),
            "remix_cam_" + System.currentTimeMillis() + ".mp4");

        FileOutputOptions opts = new FileOutputOptions.Builder(outputFile).build();
        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    if (!fin.hasError()) {
                        startCompositing(fin.getOutputResults().getOutputUri());
                    } else {
                        Log.e(TAG, "Recording error: " + fin.getError());
                        Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show();
                        resetRecordButton();
                    }
                }
            });

        isRecording = true;
        if (btnRecord != null) btnRecord.setImageResource(R.drawable.ic_pause);
        startDurationTimer();
        Log.d(TAG, "Recording started → " + outputFile.getPath());
    }

    private void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        isRecording = false;
        if (durationTimer != null) durationTimer.cancel();
        if (btnRecord != null) btnRecord.setImageResource(R.drawable.ic_play);
        Log.d(TAG, "Recording stopped");
    }

    private void resetRecordButton() {
        isRecording = false;
        if (btnRecord != null) {
            btnRecord.setImageResource(R.drawable.ic_play);
            btnRecord.setEnabled(true);
        }
    }

    private void startDurationTimer() {
        elapsedSec = 0;
        durationTimer = new CountDownTimer((long) MAX_DURATION_SEC * 1_000, 1_000) {
            @Override public void onTick(long ms) {
                elapsedSec++;
                if (tvTimer != null) tvTimer.setText(formatTime(elapsedSec));
                if (pbDuration != null) pbDuration.setProgress(elapsedSec);
                // Show remaining seconds
                int remaining = MAX_DURATION_SEC - elapsedSec;
                if (tvRemaining != null) {
                    tvRemaining.setVisibility(View.VISIBLE);
                    tvRemaining.setText(remaining + "s left");
                }
            }
            @Override public void onFinish() {
                if (tvRemaining != null) tvRemaining.setVisibility(View.GONE);
                stopRecording();
            }
        }.start();
    }

    private String formatTime(int sec) {
        return String.format("%02d:%02d", sec / 60, sec % 60);
    }

    // ── Compositing ───────────────────────────────────────────────────────────

    /**
     * After recording stops, use ReelRemixVideoCompositor to composite
     * original reel + user cam recording into one output file.
     */
    private void startCompositing(Uri userCamUri) {
        // Show compositing UI
        if (layoutCompositing != null) layoutCompositing.setVisibility(View.VISIBLE);
        if (tvCompositingStatus != null) tvCompositingStatus.setText("Compositing videos…");
        if (pbCompositing != null) pbCompositing.setProgress(0);

        // Disable record button during compositing
        if (btnRecord != null) btnRecord.setEnabled(false);

        // v791 — the recorded file we wrote ourselves (the Uri path is the same file; prefer the File we know exists)
        final String camPath = (outputFile != null && outputFile.exists())
                ? outputFile.getAbsolutePath()
                : (userCamUri != null ? userCamUri.getPath() : null);
        final Uri rawUri = camPath != null ? Uri.fromFile(new File(camPath)) : userCamUri;

        final File compositeFile = new File(getCacheDir(),
            "remix_composite_" + System.currentTimeMillis() + ".mp4");
        final Handler main = new Handler(Looper.getMainLooper());

        // v791 — exactly ONE outcome (done / failed / timed out). A hung codec can no longer leave the screen
        // on "Compositing…" forever.
        final java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean(false);
        final Runnable timeout = () -> {
            if (finished.compareAndSet(false, true)) {
                Log.e(TAG, "Compositing timed out");
                onCompositeFailed(camPath, rawUri, "Merging took too long");
            }
        };
        main.postDelayed(timeout, COMPOSITE_TIMEOUT_MS);

        ReelRemixVideoCompositor compositor = new ReelRemixVideoCompositor(
            this,
            originalVideoUrl,           // original reel URL (network) — compositor handles download
            camPath,                    // user cam recording (local file)
            compositeFile.getAbsolutePath(),
            layoutMode,
            originalMuted ? 0f : 1f,    // "mute original" button also mutes it in the final mix
            new ReelRemixVideoCompositor.CompositionListener() {
                @Override
                public void onProgress(int percent) {
                    main.post(() -> {
                        if (finished.get() || isFinishing() || isDestroyed()) return;
                        if (pbCompositing != null) pbCompositing.setProgress(percent);
                        if (tvCompositingStatus != null)
                            tvCompositingStatus.setText("Compositing… " + percent + "%");
                    });
                }

                @Override
                public void onComplete(File out) {
                    main.post(() -> {
                        if (!finished.compareAndSet(false, true)) return;
                        main.removeCallbacks(timeout);
                        if (isFinishing() || isDestroyed()) return;
                        if (layoutCompositing != null) layoutCompositing.setVisibility(View.GONE);
                        goToEditor(Uri.fromFile(out));
                    });
                }

                @Override
                public void onError(String message, Exception cause) {
                    Log.e(TAG, "Compositor error: " + message, cause);
                    main.post(() -> {
                        if (!finished.compareAndSet(false, true)) return;
                        main.removeCallbacks(timeout);
                        if (isFinishing() || isDestroyed()) return;
                        onCompositeFailed(camPath, rawUri, message);
                    });
                }
            }
        );

        // Run compositor on background thread
        Thread t = new Thread(compositor::composite, "remix-compositor");
        t.setDaemon(true);
        t.start();
    }

    /** v791 — merge failed / timed out: let the user retry, continue with their own video, or re-record. */
    private void onCompositeFailed(final String camPath, final Uri rawUri, String reason) {
        if (isFinishing() || isDestroyed()) return;
        if (layoutCompositing != null) layoutCompositing.setVisibility(View.GONE);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Could not merge the videos")
            .setMessage((reason != null ? reason + ".\n\n" : "") + "What would you like to do?")
            .setCancelable(false)
            .setPositiveButton("Retry", (d, w) -> startCompositing(rawUri))
            .setNeutralButton("Use my video only", (d, w) -> {
                Toast.makeText(this, "Opening without the original video", Toast.LENGTH_SHORT).show();
                goToEditor(rawUri);
            })
            .setNegativeButton("Record again", (d, w) -> resetRecordButton())
            .show();
    }

    // ── Navigate to editor ────────────────────────────────────────────────────

    private void goToEditor(Uri videoUri) {
        Intent intent = new Intent(this, ReelEditorActivity.class);
        // ── Correct extras for ReelEditorActivity ──────────────────────────
        intent.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,    videoUri.toString());
        intent.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH, true);
        // ── Remix metadata — passed through to ReelUploadActivity ──────────
        intent.putExtra("is_remix",               true);
        intent.putExtra("remix_mode",             flowMode);   // v790: "remix" | "collab"
        intent.putExtra("remix_original_reel_id", originalReelId   != null ? originalReelId   : "");
        intent.putExtra("remix_original_uid",     ownerUid          != null ? ownerUid          : "");
        intent.putExtra("remix_original_name",    ownerName         != null ? ownerName         : "");
        intent.putExtra("remix_original_url",     originalVideoUrl  != null ? originalVideoUrl  : "");
        intent.putExtra("remix_original_thumb",   originalThumbUrl  != null ? originalThumbUrl  : "");
        intent.putExtra("remix_layout_mode",      layoutMode        != null ? layoutMode        : LAYOUT_SIDE_BY_SIDE);
        startActivity(intent);
        finish();
    }

    // ── Firebase: publish remix record ────────────────────────────────────────

    /**
     * Call this from the upload pipeline after the remix video is uploaded.
     * Writes remix metadata to Firebase and sends notification to original creator.
     */
    public static void publishRemixToFirebase(
            String originalReelId, String newReelId,
            String originalOwnerUid, String originalOwnerName,
            String originalThumbUrl, String originalVideoUrl,
            String remixerUid, String remixerName, String remixerPhoto,
            String remixVideoUrl, String remixThumbUrl,
            String remixCaption, String layoutMode) {

        if (originalReelId == null || newReelId == null || remixerUid == null) return;

        ReelRemixModel model = new ReelRemixModel(
            newReelId, originalReelId,
            originalOwnerUid, originalOwnerName, originalThumbUrl, originalVideoUrl,
            remixerUid, remixerName, remixerPhoto,
            remixVideoUrl, remixThumbUrl, remixCaption, layoutMode);

        // 1. Remix record node
        FirebaseUtils.db()
            .getReference("reelRemixes")
            .child(originalReelId)
            .child(newReelId)
            .setValue(model);

        // 2. Atomic increment of remixCount on original reel
        FirebaseUtils.db()
            .getReference("reels")
            .child(originalReelId)
            .child("remixCount")
            .setValue(ServerValue.increment(1));

        // 3. Remixer's own index
        FirebaseUtils.db()
            .getReference("userRemixes")
            .child(remixerUid)
            .child(newReelId)
            .setValue(System.currentTimeMillis());

        // 4. Notification to original creator
        if (originalOwnerUid != null && !originalOwnerUid.equals(remixerUid)) {
            Map<String, Object> notif = new HashMap<>();
            notif.put("type",         "remix");
            notif.put("fromUid",      remixerUid);
            notif.put("fromName",     remixerName);
            notif.put("fromPhoto",    remixerPhoto);
            notif.put("reelId",       originalReelId);
            notif.put("remixReelId",  newReelId);
            notif.put("layoutMode",   layoutMode);
            notif.put("timestamp",    System.currentTimeMillis());
            FirebaseUtils.db()
                .getReference("reelNotifications")
                .child(originalOwnerUid)
                .push()
                .setValue(notif);
        }

        Log.d(TAG, "publishRemixToFirebase done: " + newReelId + " remixes " + originalReelId);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
            PERM_REQ);
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERM_REQ
            && results.length >= 2
            && results[0] == PackageManager.PERMISSION_GRANTED
            && results[1] == PackageManager.PERMISSION_GRANTED) {
            startOriginalPlayback();
            startCamera();
        } else {
            Toast.makeText(this, "Camera & Mic permission required", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
