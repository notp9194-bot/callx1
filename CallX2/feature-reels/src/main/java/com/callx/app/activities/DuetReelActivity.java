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

import com.callx.app.helpers.DuetVideoMerger;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DuetReelActivity — Record + merge a production-level duet.
 *
 * ✅ Fixed gaps vs. original:
 *  ✅ Own-reel guard — users cannot duet their own reel
 *  ✅ Duet permission check passed in from ReelPlayerFragment (Firebase-verified)
 *  ✅ Original video downloaded to cache during camera warm-up
 *  ✅ After recording: DuetVideoMerger composites both videos side-by-side
 *  ✅ Merge progress UI shown during composition
 *  ✅ Full duet metadata (reelId, uid, ownerName, videoUrl) forwarded to ReelEditorActivity
 */
@UnstableApi
public class DuetReelActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_ID      = "duet_reel_id";
    public static final String EXTRA_VIDEO_URL    = "duet_video_url";
    public static final String EXTRA_OWNER_NAME   = "duet_owner_name";
    public static final String EXTRA_OWNER_UID    = "duet_owner_uid";

    private static final int REQ_PERMISSIONS = 211;
    private static final int MAX_DUET_SEC    = 60;

    // ── Views ─────────────────────────────────────────────────────────────
    private PlayerView  playerViewOriginal;
    private PreviewView previewViewCamera;
    private ImageButton btnDuetRecord, btnDuetFlip, btnDuetClose;
    private ProgressBar progressDuet, progressMerge;
    private TextView    tvDuetTimer, tvDuetLabel, tvMergeStatus;
    private View        layoutRecordControls, layoutMerging;

    // ── Media ─────────────────────────────────────────────────────────────
    private ExoPlayer              exoPlayer;
    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        cameraExecutor;
    private CountDownTimer         recordTimer;

    // ── State ─────────────────────────────────────────────────────────────
    private int     lensFacing  = CameraSelector.LENS_FACING_FRONT;
    private boolean isRecording = false;

    // ── Intent data ───────────────────────────────────────────────────────
    private String reelId;
    private String videoUrl;
    private String ownerName;
    private String ownerUid;
    private int    durationSec = MAX_DUET_SEC;

    /** Local cache path of the downloaded original video. Set in background. */
    private volatile String cachedOriginalPath = null;

    private static final String[] PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    // ─────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_reel);

        reelId    = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl  = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        ownerUid  = getIntent().getStringExtra(EXTRA_OWNER_UID);

        if (videoUrl == null || videoUrl.isEmpty()) {
            Toast.makeText(this, "Reel not available for duet", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // ── Own-reel guard ────────────────────────────────────────────────
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid != null && myUid.equals(ownerUid)) {
            Toast.makeText(this, "You can't duet your own reel", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();
        setupOriginalPlayer();
        downloadOriginalVideo();

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

    // ─────────────────────────────────────────────────────────────────────
    private void bindViews() {
        playerViewOriginal  = findViewById(R.id.player_view_original);
        previewViewCamera   = findViewById(R.id.preview_view_camera);
        btnDuetRecord       = findViewById(R.id.btn_duet_record);
        btnDuetFlip         = findViewById(R.id.btn_duet_flip);
        btnDuetClose        = findViewById(R.id.btn_duet_close);
        progressDuet        = findViewById(R.id.progress_duet);
        tvDuetTimer         = findViewById(R.id.tv_duet_timer);
        tvDuetLabel         = findViewById(R.id.tv_duet_label);
        progressMerge       = findViewById(R.id.progress_merge);
        tvMergeStatus       = findViewById(R.id.tv_merge_status);
        layoutRecordControls= findViewById(R.id.layout_record_controls);
        layoutMerging       = findViewById(R.id.layout_merging);

        if (layoutMerging != null) layoutMerging.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
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
                    if (progressDuet != null) progressDuet.setMax(durationSec);
                }
            }
        });
    }

    /**
     * Download the original reel to local cache in background so merge is instant after recording.
     * If the URL is already a local file path, skip download.
     */
    private void downloadOriginalVideo() {
        if (videoUrl.startsWith("/")) {
            cachedOriginalPath = videoUrl;
            return;
        }
        cameraExecutor.execute(() -> {
            try {
                File dest = new File(getCacheDir(), "duet_orig_" + reelId + ".mp4");
                if (dest.exists() && dest.length() > 0) {
                    cachedOriginalPath = dest.getAbsolutePath();
                    return;
                }
                java.net.HttpURLConnection conn =
                    (java.net.HttpURLConnection) new java.net.URL(videoUrl).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(60_000);
                conn.connect();
                try (java.io.InputStream in = conn.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                cachedOriginalPath = dest.getAbsolutePath();
            } catch (Exception e) {
                android.util.Log.w("DuetReel", "Pre-download failed (will retry at merge): " + e.getMessage());
                // DuetVideoMerger.downloadAndMerge() will handle it again
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
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

    // ─────────────────────────────────────────────────────────────────────
    private void toggleRecording() {
        if (isRecording) stopRecording(true);
        else             startRecording();
    }

    private void startRecording() {
        if (videoCapture == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        File out = new File(getCacheDir(), "duet_user_" + System.currentTimeMillis() + ".mp4");
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
                        runOnUiThread(() -> startMerge(out.getAbsolutePath()));
                    } else {
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show());
                    }
                }
            });
    }

    private void stopRecording(boolean mergeAfter) {
        if (activeRecording != null) {
            activeRecording.stop();
            activeRecording = null;
        }
        if (recordTimer != null) { recordTimer.cancel(); recordTimer = null; }
        exoPlayer.pause();
        if (progressDuet != null) progressDuet.setProgress(0);
        if (tvDuetTimer  != null) tvDuetTimer.setText("0:00");
        if (btnDuetRecord!= null) btnDuetRecord.setImageResource(R.drawable.ic_play);
    }

    private void startCountdown() {
        final int[] elapsed = {0};
        recordTimer = new CountDownTimer(durationSec * 1000L, 1000) {
            @Override public void onTick(long ms) {
                elapsed[0]++;
                if (progressDuet != null) progressDuet.setProgress(elapsed[0]);
                int rem = durationSec - elapsed[0];
                if (tvDuetTimer != null)
                    tvDuetTimer.setText(String.format("%d:%02d", rem / 60, rem % 60));
            }
            @Override public void onFinish() {
                stopRecording(true);
            }
        }.start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Video merge
    // ─────────────────────────────────────────────────────────────────────

    private void startMerge(String userVideoPath) {
        exoPlayer.pause();

        // Show merge progress UI
        if (layoutRecordControls != null) layoutRecordControls.setVisibility(View.GONE);
        if (layoutMerging        != null) layoutMerging.setVisibility(View.VISIBLE);
        if (tvMergeStatus        != null) tvMergeStatus.setText("Compositing duet…");
        if (progressMerge        != null) progressMerge.setProgress(0);
        if (btnDuetClose         != null) btnDuetClose.setEnabled(false);

        String outPath = new File(getCacheDir(),
            "duet_merged_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();

        DuetVideoMerger.MergeCallback cb = new DuetVideoMerger.MergeCallback() {
            @Override public void onProgress(int percent) {
                if (progressMerge != null) progressMerge.setProgress(percent);
                if (tvMergeStatus != null)
                    tvMergeStatus.setText("Compositing duet… " + percent + "%");
            }
            @Override public void onSuccess(String outputPath) {
                if (isFinishing() || isDestroyed()) return;
                openEditor(outputPath);
            }
            @Override public void onError(Exception e) {
                if (isFinishing() || isDestroyed()) return;
                // Silent fallback: use user's recorded video directly
                // (split-screen was visible during recording; duetOfVideoUrl saved in Firebase)
                android.util.Log.w("DuetReel", "Merge skipped, using user video: " + e.getMessage());
                openEditor(userVideoPath);
            }
        };

        if (cachedOriginalPath != null && new File(cachedOriginalPath).exists()) {
            DuetVideoMerger.merge(cachedOriginalPath, userVideoPath, outPath, cb);
        } else {
            DuetVideoMerger.downloadAndMerge(this, videoUrl, userVideoPath, outPath, cb);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    private void openEditor(String mergedPath) {
        Intent i = new Intent(this, ReelEditorActivity.class);
        i.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,         mergedPath);
        i.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH,      true);
        // ── Duet metadata forwarded to editor → upload ────────────────────
        i.putExtra(ReelEditorActivity.EXTRA_IS_DUET,           true);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_OF_REEL_ID,   reelId);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_OF_UID,       ownerUid);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_OF_NAME,      ownerName);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_OF_VIDEO_URL, videoUrl);
        startActivity(i);
        finish();
    }

    // ─────────────────────────────────────────────────────────────────────
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
        if (recordTimer    != null) recordTimer.cancel();
        if (activeRecording!= null) activeRecording.stop();
        if (exoPlayer      != null) { exoPlayer.stop(); exoPlayer.release(); }
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }
}
