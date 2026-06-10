package com.callx.app.social;

import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.workers.DuetNotificationWorker;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.Surface;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DuetReelActivity v27 — Full Duet System
 *
 * Fixes & additions over v26-3:
 *  ✅ FIX: 3-2-1 countdown before recording starts
 *  ✅ FIX: Actual side-by-side video compositing via DuetVideoCompositor
 *  ✅ FIX: Original reel audio mixed into final output (volume-controlled)
 *  ✅ FIX: Granular allowDuet enforcement ("followers" vs "everyone" vs "off")
 *  ✅ NEW: Layout selector — Side-by-side / Top-Bottom / React (PiP)
 *  ✅ NEW: Original audio volume slider
 *  ✅ NEW: Pause / Resume recording
 *  ✅ NEW: duetCount incremented on original reel in Firebase
 *  ✅ NEW: DuetNotificationWorker — notifies original creator
 *  ✅ NEW: "View Duets" button — opens DuetsByReelActivity
 *  ✅ All v26-3 bug fixes retained (rotation, ExoPlayer post(), TextureView)
 */
@UnstableApi
public class DuetReelActivity extends AppCompatActivity {

    private static final String TAG = "DuetReelActivity";

    public static final String EXTRA_REEL_ID          = "duet_reel_id";
    public static final String EXTRA_VIDEO_URL         = "duet_video_url";
    public static final String EXTRA_OWNER_NAME        = "duet_owner_name";
    public static final String EXTRA_OWNER_UID         = "duet_owner_uid";
    public static final String EXTRA_DURATION_SEC      = "duet_duration_sec";
    /** Local cached file path — if provided, compositor uses this instead of network URL */
    public static final String EXTRA_CACHED_VIDEO_PATH = "duet_cached_video_path";
    /** "everyone" | "followers" | "off" — passed from ReelPlayerFragment */
    public static final String EXTRA_ALLOW_DUET_LEVEL  = "duet_allow_level";
    /** Whether current user follows the original creator — needed for "followers" check */
    public static final String EXTRA_VIEWER_FOLLOWS    = "duet_viewer_follows";

    private static final int REQ_PERMISSIONS = 211;
    private static final int MAX_DUET_SEC    = 60;

    // Layout mode constants
    public static final int LAYOUT_SIDE_BY_SIDE    = 0;
    public static final int LAYOUT_TOP_BOTTOM      = 1;
    public static final int LAYOUT_REACT_PIP       = 2;
    public static final int LAYOUT_REACTION_BUBBLE = 3;

    // ── Views ─────────────────────────────────────────────────────────────────
    private PlayerView   playerViewOriginal;
    private PreviewView  previewViewCamera;
    private ImageButton  btnDuetRecord, btnDuetFlip, btnDuetClose;
    private ImageButton  btnViewDuets;
    private ProgressBar  progressDuet;
    private TextView     tvDuetTimer, tvDuetLabel, tvCountdown;
    private SeekBar      seekOriginalVolume;
    private TextView     tvVolumeLabel;
    private SeekBar      seekMicGain;
    private TextView     tvMicGainLabel;

    /** Layout selector buttons */
    private View       btnLayoutSideBySide, btnLayoutTopBottom, btnLayoutPip;
    private View       btnLayoutReactionBubble;
    private View       layoutSelector;

    /** Reaction bubble draggable overlay (shown only in LAYOUT_REACTION_BUBBLE mode) */
    private View       bubbleOverlay;
    // Bubble position in screen coords → converted to NDC when compositing
    private float      bubbleScreenX = 0f;
    private float      bubbleScreenY = 0f;
    private boolean    bubblePosSet  = false;

    // ── Camera / player ───────────────────────────────────────────────────────
    private ExoPlayer              exoPlayer;
    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        cameraExecutor;

    private int     lensFacing      = CameraSelector.LENS_FACING_FRONT;
    private boolean isRecording     = false;
    private boolean isPaused        = false;
    private boolean discardOnStop   = false; // true = close-button stop, skip editor
    private int     layoutMode      = LAYOUT_SIDE_BY_SIDE;
    private float   originalVol  = 0.5f; // original reel audio volume (default 50%)
    private float   micGain      = 1.0f; // mic gain multiplier (default 100% = no boost)
    private CountDownTimer recordTimer;

    // ── Reel metadata ─────────────────────────────────────────────────────────
    private String  reelId;
    private String  videoUrl;
    private String  ownerName;
    private String  ownerUid;
    private String  cachedOriginalPath = null; // local cache of original reel video
    private int     durationSec   = MAX_DUET_SEC;
    private int     elapsedSec    = 0;
    private String  allowDuetLevel = "everyone";
    private boolean viewerFollows  = false;

    /** File written by CameraX recording */
    private File recordedCameraFile;

    private static final String[] PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_reel);

        reelId         = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl       = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerUid       = getIntent().getStringExtra(EXTRA_OWNER_UID);
        allowDuetLevel = getIntent().getStringExtra(EXTRA_ALLOW_DUET_LEVEL);
        String cp = getIntent().getStringExtra(EXTRA_CACHED_VIDEO_PATH);
        if (cp != null && new java.io.File(cp).exists()) cachedOriginalPath = cp;
        viewerFollows  = getIntent().getBooleanExtra(EXTRA_VIEWER_FOLLOWS, false);
        if (allowDuetLevel == null) allowDuetLevel = "everyone";

        // ── Granular permission check ─────────────────────────────────────────
        if ("off".equals(allowDuetLevel)) {
            Toast.makeText(this, "This creator has disabled duets", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if ("followers".equals(allowDuetLevel) && !viewerFollows) {
            Toast.makeText(this, "Only followers can duet this reel", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        String rawName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        ownerName = (rawName != null && rawName.startsWith("@"))
                    ? rawName.substring(1) : (rawName != null ? rawName : "");

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

        setupVolumeSlider();
        setupMicGainSlider();
        setupLayoutSelector();

        playerViewOriginal.post(this::setupOriginalPlayer);

        if (allPermissionsGranted()) startCamera();
        else ActivityCompat.requestPermissions(this, PERMISSIONS, REQ_PERMISSIONS);

        btnDuetRecord.setOnClickListener(v -> onRecordButtonClick());
        btnDuetFlip.setOnClickListener(v -> flipCamera());
        btnDuetClose.setOnClickListener(v -> {
            if (isRecording) stopRecording(false);
            finish();
        });
        if (btnViewDuets != null) {
            btnViewDuets.setOnClickListener(v -> openDuetsOfReel());
        }
    }

    private void bindViews() {
        playerViewOriginal  = findViewById(R.id.player_view_original);
        previewViewCamera   = findViewById(R.id.preview_view_camera);
        btnDuetRecord       = findViewById(R.id.btn_duet_record);
        btnDuetFlip         = findViewById(R.id.btn_duet_flip);
        btnDuetClose        = findViewById(R.id.btn_duet_close);
        btnViewDuets        = findViewById(R.id.btn_view_duets);
        progressDuet        = findViewById(R.id.progress_duet);
        tvDuetTimer         = findViewById(R.id.tv_duet_timer);
        tvDuetLabel         = findViewById(R.id.tv_duet_label);
        tvCountdown         = findViewById(R.id.tv_duet_countdown);
        seekOriginalVolume      = findViewById(R.id.seek_original_volume);
        tvVolumeLabel           = findViewById(R.id.tv_volume_label);
        seekMicGain             = findViewById(R.id.seek_mic_gain);
        tvMicGainLabel          = findViewById(R.id.tv_mic_gain_label);
        layoutSelector          = findViewById(R.id.layout_duet_selector);
        btnLayoutSideBySide     = findViewById(R.id.btn_layout_side_by_side);
        btnLayoutTopBottom      = findViewById(R.id.btn_layout_top_bottom);
        btnLayoutPip            = findViewById(R.id.btn_layout_pip);
        btnLayoutReactionBubble = findViewById(R.id.btn_layout_reaction_bubble);
        bubbleOverlay           = findViewById(R.id.view_bubble_overlay);

        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
    }

    // ── Volume slider ─────────────────────────────────────────────────────────

    private void setupVolumeSlider() {
        if (seekOriginalVolume == null) return;
        seekOriginalVolume.setMax(100);
        seekOriginalVolume.setProgress(50); // 50% default
        if (tvVolumeLabel != null) tvVolumeLabel.setText("Original: 50%");

        seekOriginalVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                originalVol = progress / 100f;
                // ✅ Only update ExoPlayer volume when NOT recording.
                // During recording ExoPlayer stays muted (0f) to prevent mic bleed.
                // The compositor uses originalVol in post-processing instead.
                if (exoPlayer != null && !isRecording) {
                    exoPlayer.setVolume(originalVol);
                }
                if (tvVolumeLabel != null) tvVolumeLabel.setText("Original: " + progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    // ── Mic gain slider ───────────────────────────────────────────────────────

    private void setupMicGainSlider() {
        if (seekMicGain == null) return;
        seekMicGain.setMax(200);       // 0–200 → 0.0–2.0x gain
        seekMicGain.setProgress(100);  // default 100 = 1.0x (no change)
        if (tvMicGainLabel != null) tvMicGainLabel.setText("Mic: 100%");

        seekMicGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                micGain = progress / 100f; // 0.0–2.0
                if (tvMicGainLabel != null) tvMicGainLabel.setText("Mic: " + progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    // ── Layout selector ───────────────────────────────────────────────────────

    private void setupLayoutSelector() {
        if (btnLayoutSideBySide == null) return;
        btnLayoutSideBySide.setOnClickListener(v -> setLayoutMode(LAYOUT_SIDE_BY_SIDE));
        btnLayoutTopBottom.setOnClickListener(v -> setLayoutMode(LAYOUT_TOP_BOTTOM));
        btnLayoutPip.setOnClickListener(v -> setLayoutMode(LAYOUT_REACT_PIP));
        if (btnLayoutReactionBubble != null)
            btnLayoutReactionBubble.setOnClickListener(v -> setLayoutMode(LAYOUT_REACTION_BUBBLE));
        setLayoutMode(LAYOUT_SIDE_BY_SIDE);
    }

    private void setLayoutMode(int mode) {
        layoutMode = mode;
        if (btnLayoutSideBySide     != null) btnLayoutSideBySide.setAlpha(mode == LAYOUT_SIDE_BY_SIDE    ? 1f : 0.4f);
        if (btnLayoutTopBottom      != null) btnLayoutTopBottom.setAlpha(mode == LAYOUT_TOP_BOTTOM       ? 1f : 0.4f);
        if (btnLayoutPip            != null) btnLayoutPip.setAlpha(mode == LAYOUT_REACT_PIP              ? 1f : 0.4f);
        if (btnLayoutReactionBubble != null) btnLayoutReactionBubble.setAlpha(mode == LAYOUT_REACTION_BUBBLE ? 1f : 0.4f);

        // Show/hide draggable bubble overlay
        if (bubbleOverlay != null)
            bubbleOverlay.setVisibility(mode == LAYOUT_REACTION_BUBBLE ? View.VISIBLE : View.GONE);

        applyLayoutToViews(mode);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void applyLayoutToViews(int mode) {
        if (playerViewOriginal == null || previewViewCamera == null) return;
        android.view.ViewGroup.LayoutParams lp1 = playerViewOriginal.getLayoutParams();
        android.view.ViewGroup.LayoutParams lp2 = previewViewCamera.getLayoutParams();
        if (lp1 == null || lp2 == null) return;

        switch (mode) {
            case LAYOUT_SIDE_BY_SIDE:
                if (lp1 instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp1).weight = 1;
                    ((android.widget.LinearLayout.LayoutParams) lp2).weight = 1;
                }
                playerViewOriginal.setVisibility(View.VISIBLE);
                previewViewCamera.setVisibility(View.VISIBLE);
                break;

            case LAYOUT_TOP_BOTTOM:
                if (lp1 instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp1).weight = 1;
                    ((android.widget.LinearLayout.LayoutParams) lp2).weight = 1;
                }
                playerViewOriginal.setVisibility(View.VISIBLE);
                previewViewCamera.setVisibility(View.VISIBLE);
                break;

            case LAYOUT_REACT_PIP:
                if (lp1 instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp1).weight = 0.3f;
                    ((android.widget.LinearLayout.LayoutParams) lp2).weight = 0.7f;
                }
                playerViewOriginal.setVisibility(View.VISIBLE);
                previewViewCamera.setVisibility(View.VISIBLE);
                break;

            case LAYOUT_REACTION_BUBBLE:
                // Original reel fills full screen; camera is shown as a live draggable bubble.
                // ✅ FIX (GAP #3): previewViewCamera is now VISIBLE and sized as the bubble.
                // Previously it was GONE — user could not see their own camera feed during recording.
                if (lp1 instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp1).weight = 1;
                    ((android.widget.LinearLayout.LayoutParams) lp2).weight = 0;
                }
                // Size the camera preview to match the bubble overlay (110 dp)
                int bubbleDp = (int) (110 * getResources().getDisplayMetrics().density);
                lp2.width  = bubbleDp;
                lp2.height = bubbleDp;
                playerViewOriginal.setVisibility(View.VISIBLE);
                previewViewCamera.setVisibility(View.VISIBLE);  // ✅ LIVE camera feed visible
                // bubbleOverlay stays as transparent drag handle; camera syncs position in setupBubbleDrag()
                setupBubbleDrag();
                break;
        }
        playerViewOriginal.setLayoutParams(lp1);
        previewViewCamera.setLayoutParams(lp2);
        playerViewOriginal.requestLayout();
        previewViewCamera.requestLayout();
    }

    /**
     * Make the bubble overlay draggable so the user can reposition it before recording.
     * Position is stored in bubbleScreenX/Y and converted to NDC when compositing.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupBubbleDrag() {
        if (bubbleOverlay == null) return;

        // Set default position to bottom-left (if not already set by user drag)
        if (!bubblePosSet) {
            bubbleOverlay.post(() -> {
                android.view.ViewGroup parent = (android.view.ViewGroup) bubbleOverlay.getParent();
                if (parent == null) return;
                float defaultX = parent.getWidth()  * 0.08f;
                float defaultY = parent.getHeight() * 0.72f;
                bubbleOverlay.setX(defaultX);
                bubbleOverlay.setY(defaultY);
                bubbleScreenX = defaultX + bubbleOverlay.getWidth()  / 2f;
                bubbleScreenY = defaultY + bubbleOverlay.getHeight() / 2f;
                // ✅ FIX (GAP #3): sync live camera preview to default bubble position
                if (previewViewCamera != null
                        && previewViewCamera.getVisibility() == View.VISIBLE) {
                    previewViewCamera.setX(defaultX);
                    previewViewCamera.setY(defaultY);
                }
            });
        }

        bubbleOverlay.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY;
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        dX = v.getX() - e.getRawX();
                        dY = v.getY() - e.getRawY();
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        float nx = e.getRawX() + dX;
                        float ny = e.getRawY() + dY;
                        v.setX(nx);
                        v.setY(ny);
                        // ✅ FIX (GAP #3): move live camera preview with the bubble
                        if (previewViewCamera != null
                                && previewViewCamera.getVisibility() == View.VISIBLE) {
                            previewViewCamera.setX(nx);
                            previewViewCamera.setY(ny);
                        }
                        bubbleScreenX = nx + v.getWidth()  / 2f;
                        bubbleScreenY = ny + v.getHeight() / 2f;
                        bubblePosSet  = true;
                        break;
                }
                return true;
            }
        });
    }

    /** Convert bubble screen coords to NDC (-1..1) for the compositor. */
    private float[] bubbleToNdc() {
        android.view.ViewGroup parent = (android.view.ViewGroup) bubbleOverlay.getParent();
        if (parent == null || parent.getWidth() == 0) return new float[]{-0.55f, -0.72f};
        float ndcX =  (bubbleScreenX / parent.getWidth())  * 2f - 1f;
        float ndcY = -((bubbleScreenY / parent.getHeight()) * 2f - 1f); // Y flipped in NDC
        return new float[]{ndcX, ndcY};
    }

    // ── ExoPlayer ─────────────────────────────────────────────────────────────

    private void setupOriginalPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerViewOriginal.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        playerViewOriginal.setUseArtwork(false);
        playerViewOriginal.setPlayer(exoPlayer);

        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setVolume(originalVol); // default 0.5 (50%) matches slider default
        exoPlayer.prepare();

        exoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
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
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
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
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        CameraSelector selector = new CameraSelector.Builder()
            .requireLensFacing(lensFacing).build();

        Preview preview = new Preview.Builder().setTargetRotation(rotation).build();
        preview.setSurfaceProvider(previewViewCamera.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD)).build();
        videoCapture = new VideoCapture.Builder<>(recorder)
            .setTargetRotation(rotation).build();

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

    // ── Record button logic ───────────────────────────────────────────────────

    private void onRecordButtonClick() {
        if (!isRecording) {
            // Not recording yet — show countdown then start
            startCountdownThenRecord();
        } else if (isPaused) {
            // Paused — resume
            resumeRecording();
        } else {
            // Recording — pause
            pauseRecording();
        }
    }

    // ── 3-2-1 Countdown ───────────────────────────────────────────────────────

    private void startCountdownThenRecord() {
        if (tvCountdown == null) { startRecording(); return; }

        // Disable buttons during countdown
        btnDuetRecord.setEnabled(false);
        btnDuetFlip.setEnabled(false);
        if (layoutSelector != null) layoutSelector.setVisibility(View.GONE);

        tvCountdown.setVisibility(View.VISIBLE);
        tvCountdown.setText("3");

        new CountDownTimer(3200, 1000) {
            int count = 3;
            @Override public void onTick(long ms) {
                tvCountdown.setText(String.valueOf(count--));
                tvCountdown.animate().scaleX(1.4f).scaleY(1.4f).setDuration(200)
                    .withEndAction(() -> tvCountdown.animate().scaleX(1f).scaleY(1f)
                        .setDuration(200).start())
                    .start();
            }
            @Override public void onFinish() {
                tvCountdown.setVisibility(View.GONE);
                btnDuetRecord.setEnabled(true);
                btnDuetFlip.setEnabled(true);
                startRecording();
            }
        }.start();
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    private void startRecording() {
        if (videoCapture == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

        recordedCameraFile = new File(getCacheDir(), "duet_cam_" + System.currentTimeMillis() + ".mp4");
        FileOutputOptions opts = new FileOutputOptions.Builder(recordedCameraFile).build();

        activeRecording = videoCapture.getOutput()
            .prepareRecording(this, opts)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    isRecording = true;
                    isPaused    = false;
                    runOnUiThread(() -> {
                        exoPlayer.seekTo(0);
                        // ✅ FIX: Mute ExoPlayer during recording so original audio
                        // does NOT bleed into the mic. DuetVideoCompositor will mix
                        // the original audio at origVolume in post-processing.
                        // Without this, original audio plays twice (mic bleed + mix).
                        exoPlayer.setVolume(0f);
                        exoPlayer.play();
                        btnDuetRecord.setImageResource(R.drawable.ic_pause);
                        startCountdownTimer();
                    });
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    isRecording = false;
                    isPaused    = false;
                    runOnUiThread(() -> {
                        // ✅ Restore ExoPlayer volume to slider value after recording ends
                        if (exoPlayer != null) exoPlayer.setVolume(originalVol);
                    });
                    if (!fin.hasError() && !discardOnStop) {
                        runOnUiThread(() -> onRecordingDone(recordedCameraFile.getAbsolutePath()));
                    } else if (fin.hasError()) {
                        Log.e(TAG, "Recording error: " + fin.getCause());
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show());
                    }
                    discardOnStop = false; // reset
                }
            });
    }

    private void pauseRecording() {
        if (activeRecording == null) return;
        activeRecording.pause();
        isPaused = true;
        exoPlayer.pause();
        if (recordTimer != null) recordTimer.cancel();
        btnDuetRecord.setImageResource(R.drawable.ic_play);
    }

    private void resumeRecording() {
        if (activeRecording == null) return;
        activeRecording.resume();
        isPaused = false;
        exoPlayer.play();
        btnDuetRecord.setImageResource(R.drawable.ic_pause);
        // Continue countdown from where we left off
        continueCountdownTimer();
    }

    private void stopRecording(boolean openEditorAfter) {
        if (recordTimer != null) { recordTimer.cancel(); recordTimer = null; }
        if (exoPlayer != null)   exoPlayer.pause();
        progressDuet.setProgress(0);
        tvDuetTimer.setText("0:00");
        btnDuetRecord.setImageResource(R.drawable.ic_play);
        isRecording = false;
        isPaused    = false;

        if (activeRecording != null) {
            // Set flag BEFORE stop() — Finalize fires immediately after stop()
            discardOnStop = !openEditorAfter;
            activeRecording.stop();
            activeRecording = null;
            // Editor is opened inside VideoRecordEvent.Finalize callback (if !discardOnStop)
        }
    }

    private void startCountdownTimer() {
        elapsedSec = 0;
        runCountdownFrom(elapsedSec);
    }

    private void continueCountdownTimer() {
        runCountdownFrom(elapsedSec);
    }

    private void runCountdownFrom(int startElapsed) {
        if (recordTimer != null) recordTimer.cancel();
        int remaining = (durationSec - startElapsed) * 1000;
        if (remaining <= 0) { stopRecording(true); return; }

        recordTimer = new CountDownTimer(remaining, 1000) {
            @Override public void onTick(long ms) {
                elapsedSec++;
                progressDuet.setProgress(elapsedSec);
                int rem = durationSec - elapsedSec;
                tvDuetTimer.setText(String.format("%d:%02d", rem / 60, rem % 60));
            }
            @Override public void onFinish() { stopRecording(true); }
        }.start();
    }

    // ── Post-recording: composite then open editor ────────────────────────────

    private void onRecordingDone(String cameraFilePath) {
        if (exoPlayer != null) exoPlayer.pause();

        // Show compositing progress
        if (tvDuetLabel != null) tvDuetLabel.setText("Processing duet…");
        if (progressDuet != null) {
            progressDuet.setIndeterminate(true);
            progressDuet.setVisibility(android.view.View.VISIBLE);
        }
        btnDuetRecord.setEnabled(false);
        btnDuetFlip.setEnabled(false);

        final String outputPath = new java.io.File(getCacheDir(),
            "duet_composite_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();

        final int   capturedLayout   = layoutMode;
        // Prefer local cached file — no network dependency
        final String capturedOriginal = (cachedOriginalPath != null)
            ? cachedOriginalPath : videoUrl;
        final float  capturedVol     = originalVol;
        final float  capturedMicGain = micGain;

        // Use a SEPARATE thread — cameraExecutor is single-threaded and still
        // used by CameraX internally; running compositing on it causes a deadlock.
        new Thread(() -> {
            DuetVideoCompositor compositor = new DuetVideoCompositor();
            // Calculate bubble NDC position (only used in LAYOUT_REACTION_BUBBLE mode)
            float[] bubbleNdc = (bubbleOverlay != null && capturedLayout == LAYOUT_REACTION_BUBBLE)
                    ? bubbleToNdc()
                    : new float[]{-0.55f, -0.72f};

            boolean ok = compositor.composite(
                cameraFilePath, capturedOriginal, outputPath,
                capturedLayout, capturedVol, capturedMicGain,
                bubbleNdc[0], bubbleNdc[1]);

            final String finalPath = ok ? outputPath : cameraFilePath;

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (progressDuet != null) {
                    progressDuet.setIndeterminate(false);
                    progressDuet.setVisibility(android.view.View.GONE);
                }
                // ✅ duetCount and notification are fired from ReelUploadActivity
                // AFTER the reel is actually published — not here at compositor done.
                openEditor(finalPath);
            });
        }, "duet-compositor").start();
    }

    // duetCount increment + notification are now handled in ReelUploadActivity.saveReelToFirebase()
    // AFTER the reel is confirmed published to Firebase — not at compositor completion.
    // This prevents double-counting and sending a notification for duets the user never posts.

    private void openEditor(String filePath) {
        Intent i = new Intent(this, ReelEditorActivity.class);
        i.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,          filePath);
        i.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH,       true);
        i.putExtra(ReelEditorActivity.EXTRA_IS_DUET,            true);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_ORIGINAL_ID,   reelId);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_ORIGINAL_URL,  videoUrl);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_OWNER_UID,     ownerUid);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_LABEL,
                   ownerName.isEmpty() ? "Duet" : "Duet with @" + ownerName);
        i.putExtra("duet_layout_mode", layoutMode);
        startActivity(i);
    }

    /** Open DuetsByReelActivity to browse all duets of this reel. */
    private void openDuetsOfReel() {
        if (reelId == null) return;
        Intent i = new Intent(this, DuetsByReelActivity.class);
        i.putExtra(DuetsByReelActivity.EXTRA_REEL_ID,    reelId);
        i.putExtra(DuetsByReelActivity.EXTRA_OWNER_NAME, ownerName);
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
        if (cameraExecutor != null)  cameraExecutor.shutdown();
        super.onDestroy();
    }
}
