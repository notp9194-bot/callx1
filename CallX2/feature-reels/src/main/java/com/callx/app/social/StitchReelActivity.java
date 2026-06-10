package com.callx.app.social;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelEditorActivity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.*;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * StitchReelActivity — Production-level Stitch Feature.
 *
 * Flow:
 *  1. Top half shows first N seconds of the original reel (1-5s, user selects)
 *  2. Bottom half is live camera for user's response
 *  3. On record complete → merge original clip + user clip → ReelEditorActivity
 *
 * Features:
 *  ✅ Stitch duration selector (1s / 2s / 3s / 5s of original)
 *  ✅ Original reel preview (top half, looped during selection)
 *  ✅ CameraX recording (bottom half)
 *  ✅ Countdown 3-2-1 before recording starts
 *  ✅ Max 60s total recording
 *  ✅ Sends both clips to ReelEditorActivity for merge
 *
 * ✅ FIX (GAP #2): Now passes originalReelId + originalOwnerUid through launchEditor()
 *   so ReelUploadActivity can save stitchOf, increment stitchCount, and fire
 *   StitchNotificationWorker to notify the original creator.
 */
public class StitchReelActivity extends AppCompatActivity {

    public static final String EXTRA_ORIGINAL_REEL_URL   = "stitch_original_url";
    public static final String EXTRA_ORIGINAL_REEL_ID    = "stitch_original_id";
    public static final String EXTRA_ORIGINAL_OWNER_UID  = "stitch_original_owner_uid";  // ✅ NEW

    private static final int REQ_PERMISSIONS = 310;
    private static final int MAX_RECORD_SEC  = 60;

    private PlayerView    playerOriginal;
    private PreviewView   previewCamera;
    private ImageButton   btnBack, btnRecord, btnFlip;
    private TextView      tvCountdown, tvTimer, tvStitchInfo;
    private ProgressBar   progressRecord;
    private LinearLayout  layoutDurationPicker;
    private View          layoutRecording;

    private ExoPlayer      exoPlayer;
    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording      activeRecording;
    private ExecutorService cameraExecutor;

    private int    stitchDurationSec = 3;
    private int    selectedLenSec    = 30;
    private boolean isRecording      = false;
    private boolean isFront          = true;
    private File    outputFile;

    // ✅ FIX: Store all three original-reel identifiers
    private String  originalUrl;
    private String  originalReelId;
    private String  originalOwnerUid;

    private CountDownTimer recordTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stitch_reel);

        originalUrl      = getIntent().getStringExtra(EXTRA_ORIGINAL_REEL_URL);
        originalReelId   = getIntent().getStringExtra(EXTRA_ORIGINAL_REEL_ID);
        originalOwnerUid = getIntent().getStringExtra(EXTRA_ORIGINAL_OWNER_UID);
        if (originalOwnerUid == null) originalOwnerUid = "";

        cameraExecutor = Executors.newSingleThreadExecutor();

        bindViews();
        setupDurationPicker();
        setupOriginalPlayer();

        if (hasPermissions()) startCamera();
        else requestPermissions();
    }

    private void bindViews() {
        playerOriginal   = findViewById(R.id.player_stitch_original);
        previewCamera    = findViewById(R.id.preview_stitch_camera);
        btnBack          = findViewById(R.id.btn_stitch_back);
        btnRecord        = findViewById(R.id.btn_stitch_record);
        btnFlip          = findViewById(R.id.btn_stitch_flip);
        tvCountdown      = findViewById(R.id.tv_stitch_countdown);
        tvTimer          = findViewById(R.id.tv_stitch_timer);
        tvStitchInfo     = findViewById(R.id.tv_stitch_info);
        progressRecord   = findViewById(R.id.progress_stitch);
        layoutDurationPicker = findViewById(R.id.layout_stitch_duration);
        layoutRecording  = findViewById(R.id.layout_stitch_recording);

        btnBack.setOnClickListener(v -> finish());
        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording();
            else startCountdownThenRecord();
        });
        btnFlip.setOnClickListener(v -> {
            isFront = !isFront;
            startCamera();
        });
    }

    private void setupDurationPicker() {
        int[] durations = {1, 2, 3, 5};
        int[] chipIds   = {R.id.chip_stitch_1s, R.id.chip_stitch_2s,
                           R.id.chip_stitch_3s, R.id.chip_stitch_5s};
        for (int i = 0; i < chipIds.length; i++) {
            TextView chip = findViewById(chipIds[i]);
            if (chip == null) continue;
            final int d = durations[i];
            chip.setOnClickListener(v -> {
                stitchDurationSec = d;
                tvStitchInfo.setText("First " + d + "s of original will be stitched");
                resetOriginalPlayer();
            });
        }
        tvStitchInfo.setText("First " + stitchDurationSec + "s of original will be stitched");
    }

    private void setupOriginalPlayer() {
        if (originalUrl == null) return;
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerOriginal.setPlayer(exoPlayer);
        exoPlayer.setMediaItem(MediaItem.fromUri(originalUrl));
        exoPlayer.setRepeatMode(ExoPlayer.REPEAT_MODE_ONE);
        exoPlayer.prepare();
        exoPlayer.play();
    }

    private void resetOriginalPlayer() {
        if (exoPlayer != null) {
            exoPlayer.seekTo(0);
            exoPlayer.play();
        }
    }

    private void startCountdownThenRecord() {
        btnRecord.setEnabled(false);
        tvCountdown.setVisibility(View.VISIBLE);
        new CountDownTimer(3500, 1000) {
            @Override public void onTick(long ms) {
                tvCountdown.setText(String.valueOf((int) Math.ceil(ms / 1000.0)));
            }
            @Override public void onFinish() {
                tvCountdown.setVisibility(View.GONE);
                btnRecord.setEnabled(true);
                startRecording();
            }
        }.start();
    }

    private void startRecording() {
        if (videoCapture == null) return;
        outputFile = new File(getCacheDir(), "stitch_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions opts = new FileOutputOptions.Builder(outputFile).build();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;
        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    if (!fin.hasError() && outputFile.exists()) {
                        launchEditor();
                    } else {
                        Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        isRecording = true;
        btnRecord.setImageResource(R.drawable.ic_pause);
        layoutRecording.setVisibility(View.VISIBLE);
        progressRecord.setMax(MAX_RECORD_SEC);
        recordTimer = new CountDownTimer(MAX_RECORD_SEC * 1000L, 1000) {
            int elapsed = 0;
            @Override public void onTick(long ms) {
                elapsed++;
                progressRecord.setProgress(elapsed);
                tvTimer.setText(String.format("%d:%02d", elapsed / 60, elapsed % 60));
            }
            @Override public void onFinish() { stopRecording(); }
        };
        recordTimer.start();
    }

    private void stopRecording() {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (recordTimer != null) { recordTimer.cancel(); recordTimer = null; }
        isRecording = false;
        btnRecord.setImageResource(R.drawable.ic_camera);
    }

    private void launchEditor() {
        Intent i = new Intent(this, ReelEditorActivity.class);
        i.putExtra(ReelCameraActivity.EXTRA_VIDEO_URI,   outputFile.getAbsolutePath());
        i.putExtra("stitch_original_url",                originalUrl);
        i.putExtra("stitch_duration_sec",                stitchDurationSec);
        i.putExtra("is_stitch",                          true);
        // ✅ FIX: Pass original reel ID and owner UID so ReelUploadActivity can
        // save stitchOf, increment stitchCount, and notify the original creator.
        if (originalReelId   != null) i.putExtra("stitch_original_id",        originalReelId);
        if (originalOwnerUid != null) i.putExtra("stitch_original_owner_uid", originalOwnerUid);
        startActivity(i);
        finish();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        cameraProvider.unbindAll();
        CameraSelector selector = isFront
            ? CameraSelector.DEFAULT_FRONT_CAMERA
            : CameraSelector.DEFAULT_BACK_CAMERA;
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewCamera.getSurfaceProvider());
        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD)).build();
        videoCapture = VideoCapture.withOutput(recorder);
        cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
            REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_PERMISSIONS && hasPermissions()) startCamera();
        else Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exoPlayer != null) { exoPlayer.release(); exoPlayer = null; }
        cameraExecutor.shutdown();
    }
}
