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
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording activeRecording;
    private Camera camera;

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private boolean flashOn = false;
    private boolean isRecording = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
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
                    }
                    return true;
            }
            return false;
        });
    }

    // ── CameraX setup ────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
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

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        imageCapture = new ImageCapture.Builder()
                .setFlashMode(flashOn ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF)
                .build();

        Recorder recorder = new Recorder.Builder().build();
        videoCapture = VideoCapture.withOutput(recorder);

        CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

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

    @androidx.annotation.RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private void startRecording() {
        if (videoCapture == null || isRecording) return;

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, "callx_" + System.currentTimeMillis() + ".mp4");
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        MediaStoreOutputOptions options = new MediaStoreOutputOptions.Builder(
                getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(cv)
                .build();

        activeRecording = videoCapture.getOutput()
                .prepareRecording(this, options)
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this), event -> {
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
        if (activeRecording != null) activeRecording.close();
        if (cameraProvider != null) cameraProvider.unbindAll();
    }
}
