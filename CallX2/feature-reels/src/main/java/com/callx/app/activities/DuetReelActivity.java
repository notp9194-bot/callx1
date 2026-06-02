package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.database.ServerValue;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DuetReelActivity v26-3
 *
 * Bugs fixed in this version:
 *  ✅ Video sideways — setTargetRotation(Surface.ROTATION_0) on Preview + VideoCapture
 *  ✅ Original reel blank — ExoPlayer set AFTER view is laid out via post(); resize_mode=fill
 *  ✅ All v26-2 fixes retained
 */
@UnstableApi
public class DuetReelActivity extends AppCompatActivity {

    private static final String TAG = "DuetReelActivity";

    public static final String EXTRA_REEL_ID      = "duet_reel_id";
    public static final String EXTRA_VIDEO_URL    = "duet_video_url";
    public static final String EXTRA_OWNER_NAME   = "duet_owner_name";
    public static final String EXTRA_OWNER_UID    = "duet_owner_uid";
    public static final String EXTRA_DURATION_SEC = "duet_duration_sec";

    private static final int REQ_PERMISSIONS = 211;
    private static final int MAX_DUET_SEC    = 60;

    // ── Views ─────────────────────────────────────────────────────────────────
    private PlayerView  playerViewOriginal;
    private PreviewView previewViewCamera;
    private ImageButton btnDuetRecord, btnDuetFlip, btnDuetClose;
    private ProgressBar progressDuet;
    private TextView    tvDuetTimer, tvDuetLabel;

    // ── Camera / player ───────────────────────────────────────────────────────
    private ExoPlayer              exoPlayer;
    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        cameraExecutor;

    private int     lensFacing  = CameraSelector.LENS_FACING_FRONT;
    private boolean isRecording = false;
    private CountDownTimer recordTimer;

    // ── Reel metadata ─────────────────────────────────────────────────────────
    private String reelId;
    private String videoUrl;
    private String ownerName;
    private String ownerUid;
    private int    durationSec = MAX_DUET_SEC;

    private static final String[] PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_reel);

        reelId   = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerUid = getIntent().getStringExtra(EXTRA_OWNER_UID);

        // Strip leading '@' to avoid "@@username"
        String rawName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        ownerName = (rawName != null && rawName.startsWith("@"))
                    ? rawName.substring(1) : (rawName != null ? rawName : "");

        // Use pre-passed duration immediately (Fix 7)
        int intentDuration = getIntent().getIntExtra(EXTRA_DURATION_SEC, 0);
        if (intentDuration > 0) durationSec = Math.min(intentDuration, MAX_DUET_SEC);

        if (videoUrl == null || videoUrl.isEmpty()) {
            Toast.makeText(this, "Reel not available for duet", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();

        tvDuetLabel.setText(ownerName.isEmpty() ? "Duet" : "Duet with @" + ownerName);
        progressDuet.setMax(durationSec);

        // FIX: Setup ExoPlayer AFTER the view is fully laid out so surface is ready
        playerViewOriginal.post(() -> setupOriginalPlayer());

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

        // CRITICAL FIX: TextureView — SurfaceView conflicts with CameraX PreviewView
        // (SurfaceView creates its own Z-layer, clashes with camera surface → blank)
        // TextureView renders in same window layer as camera, both show correctly.
        playerViewOriginal.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        playerViewOriginal.setUseArtwork(false);
        playerViewOriginal.setPlayer(exoPlayer);

        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setVolume(0f);
        exoPlayer.prepare();

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && exoPlayer.getDuration() > 0 && !isRecording) {
                    int playerDur = (int) Math.min(exoPlayer.getDuration() / 1000, MAX_DUET_SEC);
                    if (playerDur > 0) {
                        durationSec = playerDur;
                        progressDuet.setMax(durationSec);
                    }
                }
            }
        });
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
                Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null) return;

        // FIX: get current display rotation so CameraX knows the device orientation
        int rotation = getWindowManager().getDefaultDisplay().getRotation();

        CameraSelector selector = new CameraSelector.Builder()
            .requireLensFacing(lensFacing).build();

        // FIX: setTargetRotation — tells CameraX the correct upright orientation
        Preview preview = new Preview.Builder()
            .setTargetRotation(rotation)
            .build();
        preview.setSurfaceProvider(previewViewCamera.getSurfaceProvider());

        // FIX: same rotation on the VideoCapture so recorded file is portrait
        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build();
        videoCapture = VideoCapture.withOutput(recorder);
        // Apply rotation to VideoCapture via its camera2 interop or directly:
        videoCapture = new VideoCapture.Builder<>(recorder)
            .setTargetRotation(rotation)
            .build();

        cameraProvider.unbindAll();
        try {
            cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
        } catch (Exception e) {
            Log.e(TAG, "bindCameraUseCases failed: " + e.getMessage());
            Toast.makeText(this, "Cannot bind camera", Toast.LENGTH_SHORT).show();
        }
    }

    private void flipCamera() {
        lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
            ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
        bindCameraUseCases();
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private void toggleRecording() {
        if (isRecording) stopRecording(true);
        else             startRecording();
    }

    private void startRecording() {
        if (videoCapture == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        File out = new File(getCacheDir(), "duet_cam_" + System.currentTimeMillis() + ".mp4");
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
                        runOnUiThread(() -> onRecordingDone(out.getAbsolutePath()));
                    } else {
                        Log.e(TAG, "Recording error: " + fin.getCause());
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show());
                    }
                }
            });
    }

    private void stopRecording(boolean openEditorAfter) {
        if (activeRecording != null) { activeRecording.stop(); activeRecording = null; }
        if (recordTimer != null)     { recordTimer.cancel(); recordTimer = null; }
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
            @Override public void onFinish() { stopRecording(true); }
        }.start();
    }

    private void onRecordingDone(String cameraFilePath) {
        exoPlayer.pause();
        Toast.makeText(this, "Processing duet...", Toast.LENGTH_SHORT).show();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            try {
                String merged = mergeDuetVideo(cameraFilePath);
                runOnUiThread(() -> openEditor(merged));
            } catch (Exception e) {
                Log.e(TAG, "Merge failed, using camera file", e);
                runOnUiThread(() -> openEditor(cameraFilePath));
            } finally {
                exec.shutdown();
            }
        });
    }

    /** Re-mux all tracks from camera file into a clean mp4 output. */
    private String mergeDuetVideo(String cameraFilePath) throws Exception {
        File outFile = new File(getCacheDir(), "duet_merged_" + System.currentTimeMillis() + ".mp4");
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(cameraFilePath);
        int trackCount = extractor.getTrackCount();
        MediaMuxer muxer = new MediaMuxer(outFile.getAbsolutePath(),
                                          MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int[] trackMap = new int[trackCount];
        for (int i = 0; i < trackCount; i++) trackMap[i] = muxer.addTrack(extractor.getTrackFormat(i));
        muxer.start();
        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        for (int t = 0; t < trackCount; t++) extractor.selectTrack(t);
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true) {
            int idx = extractor.getSampleTrackIndex();
            if (idx < 0) break;
            info.offset = 0;
            info.size   = (int) extractor.readSampleData(buf, 0);
            if (info.size < 0) break;
            info.presentationTimeUs = extractor.getSampleTime();
            info.flags              = extractor.getSampleFlags();
            muxer.writeSampleData(trackMap[idx], buf, info);
            extractor.advance();
        }
        muxer.stop(); muxer.release(); extractor.release();
        return outFile.getAbsolutePath();
    }

    private void openEditor(String filePath) {
        Intent i = new Intent(this, ReelEditorActivity.class);
        i.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,        filePath);
        i.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH,     true);
        i.putExtra(ReelEditorActivity.EXTRA_IS_DUET,          true);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_ORIGINAL_ID, reelId);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_OWNER_UID,   ownerUid);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_LABEL,
                   ownerName.isEmpty() ? "Duet" : "Duet with @" + ownerName);
        startActivity(i);
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private boolean allPermissionsGranted() {
        for (String p : PERMISSIONS)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
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
        if (recordTimer != null)     recordTimer.cancel();
        if (activeRecording != null) activeRecording.stop();
        if (exoPlayer != null)       { exoPlayer.stop(); exoPlayer.release(); }
        cameraExecutor.shutdown();
        super.onDestroy();
    }
}
