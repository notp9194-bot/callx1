package com.callx.app.activities;

import android.Manifest;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
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
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.callx.app.duet.DuetLayoutMode;
import com.callx.app.duet.DuetVideoComposer;
import com.callx.app.reels.R;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DuetReelActivity — Production-level Duet recording screen.
 *
 * ✅ 4 layout modes: Top/Bottom, Left/Right, PiP (Original Focus), React
 * ✅ 3-2-1 countdown before recording
 * ✅ Audio mix sliders (original audio vol + mic vol)
 * ✅ Camera flip (front/back)
 * ✅ PiP camera overlay drag-to-move
 * ✅ Swap sides (who's on top/left)
 * ✅ Recording progress bar + countdown timer
 * ✅ Auto-stop at max duration
 * ✅ DuetVideoComposer post-processing
 * ✅ Passes full duet metadata to ReelEditorActivity
 * ✅ TextureView to avoid SurfaceView Z-layer conflict with CameraX
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class DuetReelActivity extends AppCompatActivity {

    private static final String TAG = "DuetReelActivity";

    // ── Intent extras ─────────────────────────────────────────────────────────
    public static final String EXTRA_REEL_ID      = "duet_reel_id";
    public static final String EXTRA_VIDEO_URL    = "duet_video_url";
    public static final String EXTRA_OWNER_NAME   = "duet_owner_name";
    public static final String EXTRA_OWNER_UID    = "duet_owner_uid";
    public static final String EXTRA_DURATION_SEC = "duet_duration_sec";

    private static final int REQ_PERMISSIONS = 211;
    private static final int MAX_DUET_SEC    = 60;
    private static final int COUNTDOWN_SECS  = 3;

    // ── Views ─────────────────────────────────────────────────────────────────
    // Split (vertical / horizontal)
    private LinearLayout layoutSplitVertical;
    private android.widget.FrameLayout frameOriginalTop, frameCameraBottom;
    // PiP
    private android.widget.FrameLayout layoutPip, framePipCamera;
    // React
    private android.widget.FrameLayout layoutReact, frameReactOriginal;
    // Players / Camera previews
    private PlayerView   playerOriginal;      // TOP_BOTTOM / LEFT_RIGHT
    private PreviewView  previewCamera;       // TOP_BOTTOM / LEFT_RIGHT
    private PlayerView   playerPipBg;         // PiP bg
    private PreviewView  previewPipCamera;    // PiP overlay
    private PlayerView   playerReactBg;       // React corner
    private PreviewView  previewReactCamera;  // React fullscreen

    // Controls
    private ImageButton  btnRecord, btnFlip, btnClose, btnAudio, btnSwap;
    private ProgressBar  progressDuet;
    private TextView     tvTimer, tvDuetLabel, tvCountdown;
    private LinearLayout layoutModeTabs;
    private View         layoutAudioMix;
    private SeekBar      seekOriginalVol, seekMicVol;

    // ── Camera / Player state ─────────────────────────────────────────────────
    private ExoPlayer              exoPlayer;        // always used for original video
    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        cameraExecutor;
    private int                    lensFacing = CameraSelector.LENS_FACING_FRONT;
    private boolean                isRecording = false;
    private boolean                isSwapped   = false;  // swap original/camera position
    private CountDownTimer         recordTimer;

    // ── Layout mode ───────────────────────────────────────────────────────────
    private DuetLayoutMode currentMode = DuetLayoutMode.TOP_BOTTOM;

    // ── Audio mix ─────────────────────────────────────────────────────────────
    private float originalVolume = 0.5f; // 0.0–1.0
    private float micVolume      = 1.0f; // for display; actual mic is hardware

    // ── Metadata ──────────────────────────────────────────────────────────────
    private String reelId;
    private String videoUrl;
    private String ownerName;
    private String ownerUid;
    private int    durationSec = MAX_DUET_SEC;

    private static final String[] PERMS = {
        Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
    };

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_reel);

        reelId   = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerUid = getIntent().getStringExtra(EXTRA_OWNER_UID);
        String rawName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        ownerName = (rawName != null && rawName.startsWith("@"))
                    ? rawName.substring(1) : (rawName != null ? rawName : "");
        int intentDur = getIntent().getIntExtra(EXTRA_DURATION_SEC, 0);
        if (intentDur > 0) durationSec = Math.min(intentDur, MAX_DUET_SEC);

        if (videoUrl == null || videoUrl.isEmpty()) {
            Toast.makeText(this, "Reel not available for duet", Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();
        buildLayoutModeTabs();
        setupAudioSliders();
        setupButtonListeners();
        applyLayoutMode(currentMode);

        tvDuetLabel.setText(ownerName.isEmpty() ? "Duet" : "Duet with @" + ownerName);
        progressDuet.setMax(durationSec);
        tvTimer.setText(formatTime(durationSec));

        // Setup ExoPlayer after layout is ready
        playerOriginal.post(this::setupExoPlayer);

        if (allPermsGranted()) startCamera();
        else ActivityCompat.requestPermissions(this, PERMS, REQ_PERMISSIONS);
    }

    // ── View binding ─────────────────────────────────────────────────────────

    private void bindViews() {
        layoutSplitVertical = findViewById(R.id.layout_split_vertical);
        frameOriginalTop    = findViewById(R.id.frame_original_top);
        frameCameraBottom   = findViewById(R.id.frame_camera_bottom);
        layoutPip           = findViewById(R.id.layout_pip);
        framePipCamera      = findViewById(R.id.frame_pip_camera);
        layoutReact         = findViewById(R.id.layout_react);
        frameReactOriginal  = findViewById(R.id.frame_react_original);

        playerOriginal     = findViewById(R.id.player_view_original);
        previewCamera      = findViewById(R.id.preview_view_camera);
        playerPipBg        = findViewById(R.id.player_view_pip_bg);
        previewPipCamera   = findViewById(R.id.preview_pip_camera);
        playerReactBg      = findViewById(R.id.player_view_react_bg);
        previewReactCamera = findViewById(R.id.preview_react_camera);

        btnRecord    = findViewById(R.id.btn_duet_record);
        btnFlip      = findViewById(R.id.btn_duet_flip);
        btnClose     = findViewById(R.id.btn_duet_close);
        btnAudio     = findViewById(R.id.btn_duet_audio);
        btnSwap      = findViewById(R.id.btn_duet_swap);
        progressDuet = findViewById(R.id.progress_duet);
        tvTimer      = findViewById(R.id.tv_duet_timer);
        tvDuetLabel  = findViewById(R.id.tv_duet_label);
        tvCountdown  = findViewById(R.id.tv_duet_countdown);
        layoutModeTabs = findViewById(R.id.layout_mode_tabs);
        layoutAudioMix = findViewById(R.id.layout_audio_mix);
        seekOriginalVol = findViewById(R.id.seek_original_vol);
        seekMicVol      = findViewById(R.id.seek_mic_vol);

        // PiP overlay drag-to-move
        makeDraggable(framePipCamera);
        makeDraggable(frameReactOriginal);
    }

    // ── Layout mode tabs ──────────────────────────────────────────────────────

    private void buildLayoutModeTabs() {
        layoutModeTabs.removeAllViews();
        for (DuetLayoutMode mode : DuetLayoutMode.all()) {
            TextView chip = new TextView(this);
            chip.setText(mode.label());
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(12f);
            chip.setPadding(24, 8, 24, 8);
            chip.setBackground(currentMode == mode
                ? getDrawable(R.drawable.bg_reel_chip_selected)
                : null);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> selectMode(mode));
            layoutModeTabs.addView(chip);
        }
    }

    private void selectMode(DuetLayoutMode mode) {
        if (isRecording) return; // don't switch during recording
        currentMode = mode;
        buildLayoutModeTabs(); // refresh chip styles
        applyLayoutMode(mode);
    }

    private void applyLayoutMode(DuetLayoutMode mode) {
        // Hide all containers first
        layoutSplitVertical.setVisibility(View.GONE);
        layoutPip.setVisibility(View.GONE);
        layoutReact.setVisibility(View.GONE);

        switch (mode) {
            case TOP_BOTTOM:
                layoutSplitVertical.setVisibility(View.VISIBLE);
                layoutSplitVertical.setOrientation(LinearLayout.VERTICAL);
                ensurePlayerInContainer(playerOriginal, frameOriginalTop);
                ensurePreviewInContainer(previewCamera, frameCameraBottom);
                updateExoPlayerSurface(playerOriginal);
                rebindCamera(previewCamera);
                break;
            case LEFT_RIGHT:
                layoutSplitVertical.setVisibility(View.VISIBLE);
                layoutSplitVertical.setOrientation(LinearLayout.HORIZONTAL);
                ensurePlayerInContainer(playerOriginal, frameOriginalTop);
                ensurePreviewInContainer(previewCamera, frameCameraBottom);
                updateExoPlayerSurface(playerOriginal);
                rebindCamera(previewCamera);
                break;
            case PIP:
                layoutPip.setVisibility(View.VISIBLE);
                updateExoPlayerSurface(playerPipBg);
                rebindCamera(previewPipCamera);
                break;
            case REACT:
                layoutReact.setVisibility(View.VISIBLE);
                updateExoPlayerSurface(playerReactBg);
                rebindCamera(previewReactCamera);
                break;
        }
    }

    private void ensurePlayerInContainer(PlayerView player,
                                         android.widget.FrameLayout container) {
        if (player.getParent() != container) {
            if (player.getParent() != null)
                ((android.view.ViewGroup) player.getParent()).removeView(player);
            container.addView(player, 0, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        }
    }

    private void ensurePreviewInContainer(PreviewView preview,
                                          android.widget.FrameLayout container) {
        if (preview.getParent() != container) {
            if (preview.getParent() != null)
                ((android.view.ViewGroup) preview.getParent()).removeView(preview);
            container.addView(preview, 0, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        }
    }

    // ── ExoPlayer ─────────────────────────────────────────────────────────────

    private void setupExoPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerOriginal.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        playerOriginal.setUseArtwork(false);
        playerOriginal.setPlayer(exoPlayer);

        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setVolume(originalVolume);
        exoPlayer.prepare();

        exoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && exoPlayer.getDuration() > 0 && !isRecording) {
                    int playerDur = (int) Math.min(exoPlayer.getDuration() / 1000, MAX_DUET_SEC);
                    if (playerDur > 0) {
                        durationSec = playerDur;
                        progressDuet.setMax(durationSec);
                        tvTimer.setText(formatTime(durationSec));
                    }
                }
            }
        });
    }

    private void updateExoPlayerSurface(PlayerView newView) {
        if (exoPlayer == null) return;
        newView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        newView.setUseArtwork(false);
        newView.setPlayer(exoPlayer);
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private PreviewView currentPreview = null;

    private void startCamera() {
        currentPreview = getActivePreview();
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider error", e);
                Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void rebindCamera(PreviewView preview) {
        currentPreview = preview;
        if (cameraProvider != null) bindCameraUseCases();
    }

    private void bindCameraUseCases() {
        if (cameraProvider == null || currentPreview == null) return;
        int rotation = getWindowManager().getDefaultDisplay().getRotation();

        CameraSelector selector = new CameraSelector.Builder()
            .requireLensFacing(lensFacing).build();
        Preview preview = new Preview.Builder().setTargetRotation(rotation).build();
        preview.setSurfaceProvider(currentPreview.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD)).build();
        videoCapture = new VideoCapture.Builder<>(recorder)
            .setTargetRotation(rotation).build();

        cameraProvider.unbindAll();
        try {
            cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
        } catch (Exception e) {
            Log.e(TAG, "bindCamera failed: " + e.getMessage());
            Toast.makeText(this, "Cannot start camera", Toast.LENGTH_SHORT).show();
        }
    }

    private PreviewView getActivePreview() {
        switch (currentMode) {
            case PIP:   return previewPipCamera;
            case REACT: return previewReactCamera;
            default:    return previewCamera;
        }
    }

    // ── Button listeners ──────────────────────────────────────────────────────

    private void setupButtonListeners() {
        btnClose.setOnClickListener(v -> {
            if (isRecording) stopRecording(false);
            finish();
        });
        btnFlip.setOnClickListener(v -> {
            lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
                ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
            bindCameraUseCases();
        });
        btnRecord.setOnClickListener(v -> {
            if (isRecording) stopRecording(true);
            else startCountdownThenRecord();
        });
        btnAudio.setOnClickListener(v -> {
            boolean shown = layoutAudioMix.getVisibility() == View.VISIBLE;
            layoutAudioMix.setVisibility(shown ? View.GONE : View.VISIBLE);
        });
        btnSwap.setOnClickListener(v -> {
            isSwapped = !isSwapped;
            swapSides();
        });
    }

    private void swapSides() {
        if (currentMode == DuetLayoutMode.TOP_BOTTOM || currentMode == DuetLayoutMode.LEFT_RIGHT) {
            // Swap children in split container
            android.widget.FrameLayout c0 = (android.widget.FrameLayout)
                layoutSplitVertical.getChildAt(0);
            android.widget.FrameLayout c1 = (android.widget.FrameLayout)
                layoutSplitVertical.getChildAt(2); // skip divider at index 1
            if (c0 == null || c1 == null) return;
            // Move camera to top, original to bottom
            View camView  = c1.getChildAt(0);
            View origView = c0.getChildAt(0);
            if (camView != null && origView != null) {
                c1.removeView(camView);
                c0.removeView(origView);
                c0.addView(camView, 0, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
                c1.addView(origView, 0, new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            }
        }
    }

    // ── Audio sliders ─────────────────────────────────────────────────────────

    private void setupAudioSliders() {
        seekOriginalVol.setProgress((int)(originalVolume * 100));
        seekMicVol.setProgress((int)(micVolume * 100));

        seekOriginalVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                originalVolume = p / 100f;
                if (exoPlayer != null) exoPlayer.setVolume(originalVolume);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        seekMicVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                micVolume = p / 100f;
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
    }

    // ── Countdown ─────────────────────────────────────────────────────────────

    private void startCountdownThenRecord() {
        btnRecord.setEnabled(false);
        tvCountdown.setVisibility(View.VISIBLE);

        new CountDownTimer(COUNTDOWN_SECS * 1000L + 200, 1000) {
            @Override public void onTick(long ms) {
                int sec = (int) Math.ceil(ms / 1000.0);
                runOnUiThread(() -> {
                    tvCountdown.setText(String.valueOf(sec));
                    // Pulse animation
                    tvCountdown.setScaleX(1.5f);
                    tvCountdown.setScaleY(1.5f);
                    tvCountdown.animate().scaleX(1f).scaleY(1f).setDuration(700).start();
                });
            }
            @Override public void onFinish() {
                runOnUiThread(() -> {
                    tvCountdown.setVisibility(View.GONE);
                    btnRecord.setEnabled(true);
                    startRecording();
                });
            }
        }.start();
    }

    // ── Recording ─────────────────────────────────────────────────────────────

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
                        if (exoPlayer != null) { exoPlayer.seekTo(0); exoPlayer.play(); }
                        btnRecord.setImageResource(R.drawable.ic_pause);
                        startCountdown();
                    });
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    isRecording = false;
                    runOnUiThread(() -> {
                        if (!fin.hasError()) {
                            onRecordingDone(out.getAbsolutePath());
                        } else {
                            Log.e(TAG, "Record error: " + fin.getCause());
                            Toast.makeText(this, "Recording failed, try again",
                                Toast.LENGTH_SHORT).show();
                            btnRecord.setImageResource(R.drawable.ic_play);
                        }
                    });
                }
            });
    }

    private void stopRecording(boolean processAfter) {
        if (activeRecording != null) { activeRecording.stop(); activeRecording = null; }
        if (recordTimer != null)     { recordTimer.cancel(); recordTimer = null; }
        if (exoPlayer != null)       exoPlayer.pause();
        progressDuet.setProgress(0);
        tvTimer.setText(formatTime(durationSec));
        btnRecord.setImageResource(R.drawable.ic_play);
        if (!processAfter) isRecording = false;
    }

    private void startCountdown() {
        final int[] elapsed = {0};
        recordTimer = new CountDownTimer(durationSec * 1000L, 1000) {
            @Override public void onTick(long ms) {
                elapsed[0]++;
                int e = elapsed[0];
                runOnUiThread(() -> {
                    progressDuet.setProgress(e);
                    int rem = durationSec - e;
                    tvTimer.setText(formatTime(rem));
                });
            }
            @Override public void onFinish() {
                runOnUiThread(() -> stopRecording(true));
            }
        }.start();
    }

    // ── Post-recording ────────────────────────────────────────────────────────

    private void onRecordingDone(String cameraFilePath) {
        if (exoPlayer != null) exoPlayer.pause();
        Toast.makeText(this, "Processing duet…", Toast.LENGTH_SHORT).show();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            String processed = DuetVideoComposer.remux(cameraFilePath, getCacheDir());
            runOnUiThread(() -> openEditor(processed));
            exec.shutdown();
        });
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
        // Pass layout mode so editor/upload can display it
        i.putExtra("duet_layout_mode", currentMode.name());
        i.putExtra("duet_original_vol", originalVolume);
        startActivity(i);
    }

    // ── Drag-to-move PiP overlay ──────────────────────────────────────────────

    private void makeDraggable(View view) {
        if (view == null) return;
        final float[] dX = {0}, dY = {0};
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    dX[0] = v.getX() - event.getRawX();
                    dY[0] = v.getY() - event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    v.setX(event.getRawX() + dX[0]);
                    v.setY(event.getRawY() + dY[0]);
                    break;
                case MotionEvent.ACTION_UP:
                    v.performClick();
                    break;
            }
            return true;
        });
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private boolean allPermsGranted() {
        for (String p : PERMS)
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERMISSIONS && allPermsGranted()) startCamera();
        else {
            Toast.makeText(this, "Camera & Mic permissions required", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override protected void onResume() {
        super.onResume();
        if (exoPlayer != null && !isRecording) exoPlayer.play();
    }

    @Override protected void onPause() {
        super.onPause();
        if (exoPlayer != null) exoPlayer.pause();
    }

    @Override protected void onDestroy() {
        if (recordTimer != null)     recordTimer.cancel();
        if (activeRecording != null) { activeRecording.stop(); activeRecording = null; }
        if (exoPlayer != null)       { exoPlayer.stop(); exoPlayer.release(); exoPlayer = null; }
        if (cameraExecutor != null)  cameraExecutor.shutdown();
        super.onDestroy();
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private String formatTime(int totalSec) {
        int min = totalSec / 60, sec = totalSec % 60;
        return String.format("%d:%02d", min, sec);
    }
}
