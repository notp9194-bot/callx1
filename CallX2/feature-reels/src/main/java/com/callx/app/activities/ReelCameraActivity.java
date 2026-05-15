package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.callx.app.reels.R;
import com.callx.app.activities.ReelEffectsActivity;
import com.callx.app.activities.ReelFiltersActivity;
import com.callx.app.activities.ReelSpeedControlActivity;
import com.callx.app.activities.MusicPickerActivity;
import com.callx.app.activities.MultiClipCameraActivity;
import com.callx.app.activities.ReelDraftsActivity;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelCameraActivity — Production-level in-app reel camera.
 *
 * Features:
 *  ✅ CameraX VideoCapture with quality selector (HD)
 *  ✅ Timer selector: 15s / 30s / 60s
 *  ✅ Flip camera (front ↔ back)
 *  ✅ Flash toggle (back camera only)
 *  ✅ Circular ProgressBar countdown ring
 *  ✅ Tap record to start/stop (also auto-stops at timer limit)
 *  ✅ On finish → launches ReelEditorActivity with recorded file Uri
 */
public class ReelCameraActivity extends AppCompatActivity {

    private static final String TAG = "ReelCameraActivity";
    public  static final String EXTRA_VIDEO_URI = "video_uri";
    private static final int    REQ_PERMISSIONS = 210;

    private PreviewView   previewView;
    private ImageButton   btnRecord, btnFlipCamera, btnFlash, btnClose;
    private ProgressBar   progressRecord;
    private TextView      tvTimer, tvSelectedDuration;
    private View          chip15s, chip30s, chip60s;
    private ImageButton   btnEffects, btnCameraFilters, btnCameraSpeed, btnCameraMusic, btnMultiClip, btnDrafts;

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording             activeRecording;
    private Camera                camera;
    private ExecutorService       cameraExecutor;

    private int     lensFacing          = CameraSelector.LENS_FACING_BACK;
    private boolean isFlashOn           = false;
    private boolean isRecording         = false;
    private int     selectedDurationSec = 30;
    private CountDownTimer recordTimer;

    // Pre-selected sound from SoundDetailActivity
    private String preSelectedSoundId    = "";
    private String preSelectedSoundTitle = "";
    private String preSelectedSoundUrl   = "";

    private static final String[] REQUIRED_PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_camera);
        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();
        setupTimerChips();
        setupClickListeners();
        readSoundExtras();
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQ_PERMISSIONS);
        }
    }

    private void bindViews() {
        previewView       = findViewById(R.id.preview_view);
        btnRecord         = findViewById(R.id.btn_record);
        btnFlipCamera     = findViewById(R.id.btn_flip_camera);
        btnFlash          = findViewById(R.id.btn_flash);
        btnClose          = findViewById(R.id.btn_close_camera);
        progressRecord    = findViewById(R.id.progress_record);
        tvTimer           = findViewById(R.id.tv_record_timer);
        tvSelectedDuration= findViewById(R.id.tv_selected_duration);
        chip15s           = findViewById(R.id.chip_15s);
        chip30s           = findViewById(R.id.chip_30s);
        chip60s           = findViewById(R.id.chip_60s);
        btnEffects        = findViewById(R.id.btn_camera_effects);
        btnCameraFilters  = findViewById(R.id.btn_camera_filters);
        btnCameraSpeed    = findViewById(R.id.btn_camera_speed);
        btnCameraMusic    = findViewById(R.id.btn_camera_music);
        btnMultiClip      = findViewById(R.id.btn_camera_multiclip);
        btnDrafts         = findViewById(R.id.btn_camera_drafts);
    }

    private void setupTimerChips() {
        selectDurationChip(30);
    }

    private void selectDurationChip(int sec) {
        selectedDurationSec = sec;
        chip15s.setSelected(sec == 15);
        chip30s.setSelected(sec == 30);
        chip60s.setSelected(sec == 60);
        tvSelectedDuration.setText(sec + "s");
        progressRecord.setMax(sec);
        progressRecord.setProgress(0);
    }

    private void setupClickListeners() {
        btnClose.setOnClickListener(v -> finish());
        btnRecord.setOnClickListener(v -> toggleRecording());
        btnFlipCamera.setOnClickListener(v -> flipCamera());
        btnFlash.setOnClickListener(v -> toggleFlash());
        if (btnEffects != null)       btnEffects.setOnClickListener(v -> startActivityForResult(new Intent(this, ReelEffectsActivity.class), 301));
        if (btnCameraFilters != null) btnCameraFilters.setOnClickListener(v -> { Intent i = new Intent(this, ReelFiltersActivity.class); startActivityForResult(i, 302); });
        if (btnCameraSpeed != null)   btnCameraSpeed.setOnClickListener(v -> startActivityForResult(new Intent(this, ReelSpeedControlActivity.class), 303));
        if (btnCameraMusic != null)   btnCameraMusic.setOnClickListener(v -> startActivityForResult(new Intent(this, MusicPickerActivity.class), 304));
        if (btnMultiClip != null)     btnMultiClip.setOnClickListener(v -> startActivity(new Intent(this, MultiClipCameraActivity.class)));
        if (btnDrafts != null)        btnDrafts.setOnClickListener(v -> startActivity(new Intent(this, ReelDraftsActivity.class)));
        chip15s.setOnClickListener(v -> { if (!isRecording) selectDurationChip(15); });
        chip30s.setOnClickListener(v -> { if (!isRecording) selectDurationChip(30); });
        chip60s.setOnClickListener(v -> { if (!isRecording) selectDurationChip(60); });
    }

    private void readSoundExtras() {
        Intent i = getIntent();
        if (i == null) return;
        String id    = i.getStringExtra("selected_sound_id");
        String title = i.getStringExtra("selected_sound_title");
        String url   = i.getStringExtra("selected_sound_url");
        if (id    != null && !id.isEmpty())    preSelectedSoundId    = id;
        if (title != null && !title.isEmpty()) preSelectedSoundTitle = title;
        if (url   != null && !url.isEmpty())   preSelectedSoundUrl   = url;

        // Show pre-selected music label on the music button if a sound was passed
        if (!preSelectedSoundTitle.isEmpty() && btnCameraMusic != null) {
            btnCameraMusic.setContentDescription(preSelectedSoundTitle);
            Toast.makeText(this,
                "Sound ready: " + preSelectedSoundTitle, Toast.LENGTH_SHORT).show();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture =
            ProcessCameraProvider.getInstance(this);

        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        CameraSelector cameraSelector = new CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build();

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build();
        videoCapture = VideoCapture.withOutput(recorder);

        cameraProvider.unbindAll();
        try {
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, videoCapture);
            updateFlashIcon();
        } catch (Exception e) {
            Log.e(TAG, "Use case binding failed", e);
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleRecording() {
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (videoCapture == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show();
            return;
        }

        File outputFile = new File(getCacheDir(),
            "reel_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions options = new FileOutputOptions.Builder(outputFile).build();

        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, options)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    isRecording = true;
                    runOnUiThread(this::onRecordingStarted);
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
                    isRecording = false;
                    if (!finalize.hasError()) {
                        String path = outputFile.getAbsolutePath();
                        runOnUiThread(() -> openEditor(path));
                    } else {
                        Log.e(TAG, "Recording error: " + finalize.getError());
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show());
                    }
                }
            });
    }

    private void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (recordTimer != null) {
            recordTimer.cancel();
            recordTimer = null;
        }
        progressRecord.setProgress(0);
        tvTimer.setText("00:00");
    }

    private void onRecordingStarted() {
        btnRecord.setImageResource(R.drawable.ic_pause);
        setTimerChipsEnabled(false);

        final int[] elapsed = {0};
        recordTimer = new CountDownTimer(selectedDurationSec * 1000L, 1000) {
            @Override public void onTick(long msRemaining) {
                elapsed[0]++;
                progressRecord.setProgress(elapsed[0]);
                int remaining = selectedDurationSec - elapsed[0];
                tvTimer.setText(String.format("%02d:%02d", remaining / 60, remaining % 60));
            }
            @Override public void onFinish() {
                stopRecording();
            }
        }.start();
    }

    private void openEditor(String filePath) {
        btnRecord.setImageResource(R.drawable.ic_camera);
        setTimerChipsEnabled(true);
        tvTimer.setText("00:00");
        progressRecord.setProgress(0);

        Intent intent = new Intent(this, ReelEditorActivity.class);
        intent.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI, filePath);
        // Pass pre-selected sound through to editor → upload
        if (!preSelectedSoundId.isEmpty())    intent.putExtra("selected_sound_id",    preSelectedSoundId);
        if (!preSelectedSoundTitle.isEmpty()) intent.putExtra("selected_sound_title", preSelectedSoundTitle);
        if (!preSelectedSoundUrl.isEmpty())   intent.putExtra("selected_sound_url",   preSelectedSoundUrl);
        startActivity(intent);
    }

    private void flipCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_BACK)
            ? CameraSelector.LENS_FACING_FRONT
            : CameraSelector.LENS_FACING_BACK;
        isFlashOn = false;
        bindCameraUseCases();
        updateFlashIcon();
    }

    private void toggleFlash() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, "Flash not available", Toast.LENGTH_SHORT).show();
            return;
        }
        isFlashOn = !isFlashOn;
        camera.getCameraControl().enableTorch(isFlashOn);
        updateFlashIcon();
    }

    private void updateFlashIcon() {
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            btnFlash.setVisibility(View.VISIBLE);
            btnFlash.setImageResource(isFlashOn
                ? R.drawable.ic_volume_on : R.drawable.ic_volume_off);
        } else {
            btnFlash.setVisibility(View.GONE);
        }
    }

    private void setTimerChipsEnabled(boolean enabled) {
        chip15s.setEnabled(enabled);
        chip30s.setEnabled(enabled);
        chip60s.setEnabled(enabled);
    }

    private boolean allPermissionsGranted() {
        for (String p : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera and microphone permissions are required",
                    Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (recordTimer != null) recordTimer.cancel();
        if (activeRecording != null) activeRecording.stop();
        cameraExecutor.shutdown();
    }
}
