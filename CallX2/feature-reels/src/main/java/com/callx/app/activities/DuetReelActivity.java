package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
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
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DuetReelActivity — Record a duet alongside an existing reel.
 *
 * Features:
 *  ✅ Top half: original reel plays via ExoPlayer (looped, synced)
 *  ✅ Bottom half: live CameraX camera preview
 *  ✅ Record button: starts both reel playback + camera recording simultaneously
 *  ✅ Countdown timer (max 60s, synced to original reel duration)
 *  ✅ Flip camera (front/back)
 *  ✅ On finish → opens ReelEditorActivity with recorded duet video
 */
@UnstableApi
public class DuetReelActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID    = "duet_reel_id";
    public static final String EXTRA_VIDEO_URL  = "duet_video_url";
    public static final String EXTRA_OWNER_NAME = "duet_owner_name";

    private static final int REQ_PERMISSIONS = 211;
    private static final int MAX_DUET_SEC    = 60;

    private PlayerView  playerViewOriginal;
    private PreviewView previewViewCamera;
    private ImageButton btnDuetRecord, btnDuetFlip, btnDuetClose;
    private ProgressBar progressDuet;
    private TextView    tvDuetTimer, tvDuetLabel;

    private ExoPlayer              exoPlayer;
    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        cameraExecutor;

    private int     lensFacing  = CameraSelector.LENS_FACING_FRONT;
    private boolean isRecording = false;
    private CountDownTimer recordTimer;

    private String reelId;
    private String videoUrl;
    private String ownerName;
    private int    durationSec  = MAX_DUET_SEC;

    private static final String[] PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_reel);

        reelId    = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl  = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);

        if (videoUrl == null || videoUrl.isEmpty()) {
            Toast.makeText(this, "Reel not available for duet", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();
        setupOriginalPlayer();

        if (ownerName != null && tvDuetLabel != null) {
            tvDuetLabel.setText("Duet with @" + ownerName);
        }

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, PERMISSIONS, REQ_PERMISSIONS);
        }

        btnDuetRecord.setOnClickListener(v -> toggleRecording());
        btnDuetFlip.setOnClickListener(v -> flipCamera());
        btnDuetClose.setOnClickListener(v -> {
            if (isRecording) stopRecording(false);
            finish();
        });
    }

    private void bindViews() {
        playerViewOriginal = findViewById(R.id.player_view_original);
        previewViewCamera  = findViewById(R.id.preview_view_camera);
        btnDuetRecord      = findViewById(R.id.btn_duet_record);
        btnDuetFlip        = findViewById(R.id.btn_duet_flip);
        btnDuetClose       = findViewById(R.id.btn_duet_close);
        progressDuet       = findViewById(R.id.progress_duet);
        tvDuetTimer        = findViewById(R.id.tv_duet_timer);
        tvDuetLabel        = findViewById(R.id.tv_duet_label);
    }

    private void setupOriginalPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerViewOriginal.setPlayer(exoPlayer);
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.prepare();

        exoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && exoPlayer.getDuration() > 0) {
                    durationSec = (int) Math.min(
                        exoPlayer.getDuration() / 1000, MAX_DUET_SEC);
                    progressDuet.setMax(durationSec);
                }
            }
        });
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;
        CameraSelector selector = new CameraSelector.Builder()
            .requireLensFacing(lensFacing).build();
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewViewCamera.getSurfaceProvider());
        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD)).build();
        videoCapture = VideoCapture.withOutput(recorder);
        cameraProvider.unbindAll();
        try {
            cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
        } catch (Exception e) {
            Toast.makeText(this, "Cannot bind camera", Toast.LENGTH_SHORT).show();
        }
    }

    private void flipCamera() {
        lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
            ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
        bindCameraUseCases();
    }

    private void toggleRecording() {
        if (isRecording) {
            stopRecording(true);
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (videoCapture == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        File out = new File(getCacheDir(), "duet_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions opts = new FileOutputOptions.Builder(out).build();

        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    isRecording = true;
                    runOnUiThread(() -> {
                        exoPlayer.seekTo(0);
                        exoPlayer.play();
                        btnDuetRecord.setImageResource(R.drawable.ic_pause);
                        startCountdown();
                    });
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    isRecording = false;
                    if (!fin.hasError()) {
                        runOnUiThread(() -> openEditor(out.getAbsolutePath()));
                    } else {
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show());
                    }
                }
            });
    }

    private void stopRecording(boolean openEditorAfter) {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (recordTimer != null) { recordTimer.cancel(); recordTimer = null; }
        exoPlayer.pause();
        progressDuet.setProgress(0);
        tvDuetTimer.setText("0:00");
        btnDuetRecord.setImageResource(R.drawable.ic_play);
    }

    private void startCountdown() {
        final int[] elapsed = {0};
        recordTimer = new CountDownTimer(durationSec * 1000L, 1000) {
            @Override public void onTick(long ms) {
                elapsed[0]++;
                progressDuet.setProgress(elapsed[0]);
                int rem = durationSec - elapsed[0];
                tvDuetTimer.setText(String.format("%d:%02d", rem / 60, rem % 60));
            }
            @Override public void onFinish() {
                stopRecording(true);
            }
        }.start();
    }

    private void openEditor(String filePath) {
        exoPlayer.pause();
        Intent i = new Intent(this, ReelEditorActivity.class);
        i.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,    filePath);
        i.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH, true);
        startActivity(i);
    }

    private boolean allPermissionsGranted() {
        for (String p : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERMISSIONS && allPermissionsGranted()) startCamera();
        else { Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show(); finish(); }
    }

    @Override
    protected void onDestroy() {
        if (recordTimer != null) recordTimer.cancel();
        if (activeRecording != null) activeRecording.stop();
        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.release(); }
        cameraExecutor.shutdown();
        super.onDestroy();
    }
}
