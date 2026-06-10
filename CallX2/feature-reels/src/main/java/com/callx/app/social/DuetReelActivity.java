package com.callx.app.social;

import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.workers.DuetNotificationWorker;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.MotionEvent;
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
 * DuetReelActivity v28 — Full Duet System (All Fixes Applied)
 *
 * ── What changed from v27 ────────────────────────────────────────────────────
 *  ✅ FIX 2: Reaction Bubble DRAG — setOnTouchListener added to bubbleOverlay
 *  ✅ FIX 3: bubbleToNdc() method fully implemented
 *  ✅ FIX 5: DuetPreviewOverlayView shows live layout indicator during recording
 *  ✅ FIX 10: Chain Duet — detects when dueting a duet; passes chainDuetRootId
 *              & chainDuetDepth to editor/upload; banner shows "Duet of Duet"
 *  ✅ FIX 11: "Invite to Duet" button → DuetInviteActivity (creator-only)
 *  ✅ All v27 fixes retained
 */
@UnstableApi
public class DuetReelActivity extends AppCompatActivity {

    private static final String TAG = "DuetReelActivity";

    public static final String EXTRA_REEL_ID           = "duet_reel_id";
    public static final String EXTRA_VIDEO_URL          = "duet_video_url";
    public static final String EXTRA_OWNER_NAME         = "duet_owner_name";
    public static final String EXTRA_OWNER_UID          = "duet_owner_uid";
    public static final String EXTRA_DURATION_SEC       = "duet_duration_sec";
    public static final String EXTRA_CACHED_VIDEO_PATH  = "duet_cached_video_path";
    /** "everyone" | "followers" | "off" */
    public static final String EXTRA_ALLOW_DUET_LEVEL   = "duet_allow_level";
    public static final String EXTRA_VIEWER_FOLLOWS     = "duet_viewer_follows";
    /** Fix 4: original reel's separate music/sound URL (may differ from video audio) */
    public static final String EXTRA_ORIGINAL_SOUND_URL = "duet_original_sound_url";
    /** Fix 10: if original reel is itself a duet */
    public static final String EXTRA_ORIGINAL_DUET_OF   = "duet_original_duet_of";
    public static final String EXTRA_ORIGINAL_CHAIN_DEPTH = "duet_chain_depth";

    private static final int REQ_PERMISSIONS = 211;
    private static final int MAX_DUET_SEC    = 60;
    private static final int MIN_BUBBLE_SIZE_DP = 80;

    public static final int LAYOUT_SIDE_BY_SIDE    = 0;
    public static final int LAYOUT_TOP_BOTTOM      = 1;
    public static final int LAYOUT_REACT_PIP       = 2;
    public static final int LAYOUT_REACTION_BUBBLE = 3;

    // ── Views ─────────────────────────────────────────────────────────────────
    private PlayerView   playerViewOriginal;
    private PreviewView  previewViewCamera;
    private ImageButton  btnDuetRecord, btnDuetFlip, btnDuetClose;
    private ImageButton  btnViewDuets;
    private ImageButton  btnInviteDuet;
    private ProgressBar  progressDuet;
    private TextView     tvDuetTimer, tvDuetLabel, tvCountdown;
    private SeekBar      seekOriginalVolume;
    private TextView     tvVolumeLabel;
    private SeekBar      seekMicGain;
    private TextView     tvMicGainLabel;
    private TextView     tvChainDuetBanner;

    private View       btnLayoutSideBySide, btnLayoutTopBottom, btnLayoutPip;
    private View       btnLayoutReactionBubble;
    private View       layoutSelector;

    /** Fix 2: draggable bubble overlay */
    private View             bubbleOverlay;
    /** Fix 5: live layout preview overlay */
    private DuetPreviewOverlayView previewOverlay;

    // Fix 2 & 3: bubble position in VIEW coordinates
    private float bubbleViewX  = -1f;
    private float bubbleViewY  = -1f;
    // Touch drag offsets
    private float touchDX = 0f;
    private float touchDY = 0f;

    // ── Camera / player ───────────────────────────────────────────────────────
    private ExoPlayer              exoPlayer;
    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        cameraExecutor;

    private int     lensFacing    = CameraSelector.LENS_FACING_FRONT;
    private boolean isRecording   = false;
    private boolean isPaused      = false;
    private boolean discardOnStop = false;
    private int     layoutMode    = LAYOUT_SIDE_BY_SIDE;
    private float   originalVol   = 0.5f;
    private float   micGain       = 1.0f;
    private CountDownTimer recordTimer;

    // ── Reel metadata ─────────────────────────────────────────────────────────
    private String  reelId;
    private String  videoUrl;
    private String  ownerName;
    private String  ownerUid;
    private String  originalSoundUrl  = null; // Fix 4
    private String  cachedOriginalPath = null;
    private int     durationSec   = MAX_DUET_SEC;
    private int     elapsedSec    = 0;
    private String  allowDuetLevel = "everyone";
    private boolean viewerFollows  = false;
    // Fix 10: chain duet
    private String  originalDuetOf   = null;
    private int     chainDepth        = 0;
    private boolean isChainDuet       = false;

    private File recordedCameraFile;

    private static final String[] PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_reel);

        reelId           = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl         = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerUid         = getIntent().getStringExtra(EXTRA_OWNER_UID);
        allowDuetLevel   = getIntent().getStringExtra(EXTRA_ALLOW_DUET_LEVEL);
        originalSoundUrl = getIntent().getStringExtra(EXTRA_ORIGINAL_SOUND_URL); // Fix 4
        originalDuetOf   = getIntent().getStringExtra(EXTRA_ORIGINAL_DUET_OF);   // Fix 10
        chainDepth       = getIntent().getIntExtra(EXTRA_ORIGINAL_CHAIN_DEPTH, 0);
        isChainDuet      = (originalDuetOf != null && !originalDuetOf.isEmpty());

        String cp = getIntent().getStringExtra(EXTRA_CACHED_VIDEO_PATH);
        if (cp != null && new File(cp).exists()) cachedOriginalPath = cp;
        viewerFollows = getIntent().getBooleanExtra(EXTRA_VIEWER_FOLLOWS, false);
        if (allowDuetLevel == null) allowDuetLevel = "everyone";

        if ("off".equals(allowDuetLevel)) {
            Toast.makeText(this, "This creator has disabled duets", Toast.LENGTH_SHORT).show();
            finish(); return;
        }
        if ("followers".equals(allowDuetLevel) && !viewerFollows) {
            Toast.makeText(this, "Only followers can duet this reel", Toast.LENGTH_SHORT).show();
            finish(); return;
        }

        String rawName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
        ownerName = (rawName != null && rawName.startsWith("@"))
                    ? rawName.substring(1) : (rawName != null ? rawName : "");

        int intentDuration = getIntent().getIntExtra(EXTRA_DURATION_SEC, 0);
        if (intentDuration > 0) durationSec = Math.min(intentDuration, MAX_DUET_SEC);

        if (videoUrl == null || videoUrl.isEmpty()) {
            Toast.makeText(this, "Reel not available for duet", Toast.LENGTH_SHORT).show();
            finish(); return;
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();

        tvDuetLabel.setText(ownerName.isEmpty() ? "Duet" : "Duet with @" + ownerName);
        progressDuet.setMax(durationSec);

        // Fix 10: Show chain-duet banner
        if (tvChainDuetBanner != null) {
            if (isChainDuet) {
                tvChainDuetBanner.setVisibility(View.VISIBLE);
                tvChainDuetBanner.setText("Chain Duet (depth " + (chainDepth + 1) + ")");
            } else {
                tvChainDuetBanner.setVisibility(View.GONE);
            }
        }

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
        if (btnViewDuets != null)
            btnViewDuets.setOnClickListener(v -> openDuetsOfReel());

        // Fix 11: Invite button — show only to reel owner
        if (btnInviteDuet != null) {
            com.google.firebase.auth.FirebaseUser me =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            boolean isMine = me != null && me.getUid().equals(ownerUid);
            btnInviteDuet.setVisibility(isMine ? View.VISIBLE : View.GONE);
            btnInviteDuet.setOnClickListener(v -> openInvite());
        }
    }

    private void bindViews() {
        playerViewOriginal      = findViewById(R.id.player_view_original);
        previewViewCamera       = findViewById(R.id.preview_view_camera);
        btnDuetRecord           = findViewById(R.id.btn_duet_record);
        btnDuetFlip             = findViewById(R.id.btn_duet_flip);
        btnDuetClose            = findViewById(R.id.btn_duet_close);
        btnViewDuets            = findViewById(R.id.btn_view_duets);
        btnInviteDuet           = findViewById(R.id.btn_invite_duet);  // Fix 11
        progressDuet            = findViewById(R.id.progress_duet);
        tvDuetTimer             = findViewById(R.id.tv_duet_timer);
        tvDuetLabel             = findViewById(R.id.tv_duet_label);
        tvCountdown             = findViewById(R.id.tv_duet_countdown);
        tvChainDuetBanner       = findViewById(R.id.tv_chain_duet_banner); // Fix 10
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
        previewOverlay          = findViewById(R.id.view_duet_preview_overlay); // Fix 5

        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);

        // Fix 2: Setup bubble drag listener immediately after binding
        setupBubbleDragListener();
    }

    // ── Fix 2: Bubble Drag Touch Listener ─────────────────────────────────────

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupBubbleDragListener() {
        if (bubbleOverlay == null) return;

        bubbleOverlay.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // Capture offset from top-left of bubble to touch point
                    touchDX = v.getX() - event.getRawX();
                    touchDY = v.getY() - event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float newX = event.getRawX() + touchDX;
                    float newY = event.getRawY() + touchDY;

                    // Clamp to parent bounds so bubble doesn't go off-screen
                    View parent = (View) v.getParent();
                    if (parent != null) {
                        float maxX = parent.getWidth()  - v.getWidth();
                        float maxY = parent.getHeight() - v.getHeight();
                        newX = Math.max(0, Math.min(newX, maxX));
                        newY = Math.max(0, Math.min(newY, maxY));
                    }
                    v.setX(newX);
                    v.setY(newY);

                    // Fix 3: record view position for bubbleToNdc()
                    bubbleViewX = newX + v.getWidth()  / 2f;
                    bubbleViewY = newY + v.getHeight() / 2f;

                    // Fix 5: update preview overlay
                    if (previewOverlay != null && parent != null) {
                        float nx = parent.getWidth()  > 0 ? bubbleViewX / parent.getWidth()  : 0.2f;
                        float ny = parent.getHeight() > 0 ? bubbleViewY / parent.getHeight() : 0.75f;
                        previewOverlay.setBubblePosition(nx, ny);
                    }
                    return true;

                default:
                    return false;
            }
        });
    }

    // ── Fix 3: bubbleToNdc() ──────────────────────────────────────────────────

    /**
     * Converts the bubble overlay's current VIEW position into OpenGL NDC coords.
     * NDC: centre = (0,0), range = -1..1 for both axes.
     * Y is flipped because GL Y-up vs Android Y-down.
     *
     * @return float[2] = { ndcX, ndcY }
     */
    private float[] bubbleToNdc() {
        View parent = bubbleOverlay != null ? (View) bubbleOverlay.getParent() : null;
        if (parent == null || parent.getWidth() == 0 || parent.getHeight() == 0) {
            return new float[]{-0.55f, -0.72f}; // safe default: bottom-left corner
        }

        float cx, cy;
        if (bubbleViewX >= 0 && bubbleViewY >= 0) {
            // User dragged the bubble — use tracked position
            cx = bubbleViewX;
            cy = bubbleViewY;
        } else {
            // Not dragged yet — default to bottom-left area
            cx = parent.getWidth()  * 0.22f;
            cy = parent.getHeight() * 0.76f;
        }

        // Normalise to 0..1 in view space
        float nx =  cx / parent.getWidth();
        float ny =  cy / parent.getHeight();

        // Convert to NDC (-1..1); flip Y (GL is bottom-up)
        float ndcX = nx * 2f - 1f;
        float ndcY = -(ny * 2f - 1f); // negate for GL Y-up

        return new float[]{ndcX, ndcY};
    }

    // ── Volume slider ─────────────────────────────────────────────────────────

    private void setupVolumeSlider() {
        if (seekOriginalVolume == null) return;
        seekOriginalVolume.setMax(100);
        seekOriginalVolume.setProgress(50);
        if (tvVolumeLabel != null) tvVolumeLabel.setText("Original: 50%");
        seekOriginalVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                originalVol = p / 100f;
                if (exoPlayer != null && !isRecording) exoPlayer.setVolume(originalVol);
                if (tvVolumeLabel != null) tvVolumeLabel.setText("Original: " + p + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupMicGainSlider() {
        if (seekMicGain == null) return;
        seekMicGain.setMax(200);
        seekMicGain.setProgress(100);
        if (tvMicGainLabel != null) tvMicGainLabel.setText("Mic: 100%");
        seekMicGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                micGain = p / 100f;
                if (tvMicGainLabel != null) tvMicGainLabel.setText("Mic: " + p + "%");
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

        // Show/hide draggable bubble overlay (Fix 2)
        if (bubbleOverlay != null)
            bubbleOverlay.setVisibility(mode == LAYOUT_REACTION_BUBBLE ? View.VISIBLE : View.GONE);

        // Fix 5: update live preview overlay
        if (previewOverlay != null) {
            previewOverlay.setLayoutMode(mode);
            previewOverlay.setVisibility(View.VISIBLE);
        }

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
                if (lp1 instanceof android.widget.LinearLayout.LayoutParams) {
                    ((android.widget.LinearLayout.LayoutParams) lp1).weight = 1;
                    ((android.widget.LinearLayout.LayoutParams) lp2).weight = 0;
                }
                playerViewOriginal.setVisibility(View.VISIBLE);
                previewViewCamera.setVisibility(View.GONE);
                break;
        }

        playerViewOriginal.requestLayout();
        previewViewCamera.requestLayout();
    }

    // ── Original player setup ─────────────────────────────────────────────────

    private void setupOriginalPlayer() {
        if (videoUrl == null) return;
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerViewOriginal.setPlayer(exoPlayer);
        playerViewOriginal.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setVolume(originalVol);
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.prepare();
    }

    // ── Camera setup ──────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindCamera();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider error: " + e.getMessage());
                runOnUiThread(() ->
                    Toast.makeText(this, "Camera unavailable", Toast.LENGTH_SHORT).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera() {
        if (cameraProvider == null) return;
        cameraProvider.unbindAll();
        CameraSelector selector = lensFacing == CameraSelector.LENS_FACING_FRONT
            ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewViewCamera.getSurfaceProvider());
        Recorder recorder = new Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD)).build();
        videoCapture = VideoCapture.withOutput(recorder);
        try {
            cameraProvider.bindToLifecycle(this, selector, preview, videoCapture);
        } catch (Exception e) {
            Log.e(TAG, "bindCamera failed: " + e.getMessage());
        }
    }

    private void flipCamera() {
        lensFacing = (lensFacing == CameraSelector.LENS_FACING_FRONT)
            ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
        bindCamera();
    }

    // ── Record button ─────────────────────────────────────────────────────────

    private void onRecordButtonClick() {
        if (!isRecording) {
            showCountdown(() -> startRecording());
        } else if (isPaused) {
            resumeRecording();
        } else {
            pauseRecording();
        }
    }

    private void showCountdown(Runnable afterCountdown) {
        if (tvCountdown == null) { afterCountdown.run(); return; }
        tvCountdown.setVisibility(View.VISIBLE);
        new CountDownTimer(3500, 1000) {
            @Override public void onTick(long ms) {
                int sec = (int) Math.ceil(ms / 1000.0);
                runOnUiThread(() -> tvCountdown.setText(String.valueOf(sec)));
            }
            @Override public void onFinish() {
                runOnUiThread(() -> {
                    tvCountdown.setVisibility(View.GONE);
                    afterCountdown.run();
                });
            }
        }.start();
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void startRecording() {
        if (videoCapture == null || isRecording) return;

        recordedCameraFile = new File(getCacheDir(),
            "duet_cam_" + System.currentTimeMillis() + ".mp4");
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
                        exoPlayer.setVolume(0f); // mute during recording to prevent bleed
                        exoPlayer.play();
                        btnDuetRecord.setImageResource(R.drawable.ic_pause);
                        startCountdownTimer();
                    });
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    isRecording = false;
                    isPaused    = false;
                    runOnUiThread(() -> {
                        if (exoPlayer != null) exoPlayer.setVolume(originalVol);
                    });
                    if (!fin.hasError() && !discardOnStop) {
                        runOnUiThread(() -> onRecordingDone(recordedCameraFile.getAbsolutePath()));
                    } else if (fin.hasError()) {
                        Log.e(TAG, "Recording error: " + fin.getCause());
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed", Toast.LENGTH_SHORT).show());
                    }
                    discardOnStop = false;
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
            discardOnStop = !openEditorAfter;
            activeRecording.stop();
            activeRecording = null;
        }
    }

    private void startCountdownTimer() {
        elapsedSec = 0;
        runCountdownFrom(0);
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
        if (tvDuetLabel != null) tvDuetLabel.setText("Processing duet…");
        if (progressDuet != null) { progressDuet.setIndeterminate(true); progressDuet.setVisibility(View.VISIBLE); }
        btnDuetRecord.setEnabled(false);
        btnDuetFlip.setEnabled(false);

        final String outputPath = new File(getCacheDir(),
            "duet_composite_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();

        final int    capturedLayout   = layoutMode;
        final String capturedOriginal = (cachedOriginalPath != null) ? cachedOriginalPath : videoUrl;
        final float  capturedVol      = originalVol;
        final float  capturedMicGain  = micGain;
        // Fix 4: pass separate sound URL so compositor can mix the correct music track
        final String capturedSoundUrl = originalSoundUrl;
        // Fix 10: chain duet metadata
        final String capturedChainRoot = isChainDuet ? originalDuetOf : null;
        final int    capturedChainDepth = chainDepth;

        new Thread(() -> {
            DuetVideoCompositor compositor = new DuetVideoCompositor();
            float[] bubbleNdc = (bubbleOverlay != null && capturedLayout == LAYOUT_REACTION_BUBBLE)
                    ? bubbleToNdc() : new float[]{-0.55f, -0.72f};

            // Fix 4: pass ownerName for watermark; soundUrl for music mixing
            boolean ok = compositor.composite(
                cameraFilePath, capturedOriginal, outputPath,
                capturedLayout, capturedVol, capturedMicGain,
                bubbleNdc[0], bubbleNdc[1],
                capturedSoundUrl,           // Fix 4: separate music track
                ownerName);                 // Fix 9: watermark label

            final String finalPath = ok ? outputPath : cameraFilePath;

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (progressDuet != null) { progressDuet.setIndeterminate(false); progressDuet.setVisibility(View.GONE); }
                openEditor(finalPath, capturedChainRoot, capturedChainDepth);
            });
        }, "duet-compositor").start();
    }

    private void openEditor(String filePath, String chainRoot, int chainDepth) {
        Intent i = new Intent(this, ReelEditorActivity.class);
        i.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,         filePath);
        i.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH,      true);
        i.putExtra(ReelEditorActivity.EXTRA_IS_DUET,           true);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_ORIGINAL_ID,  reelId);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_ORIGINAL_URL, videoUrl);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_OWNER_UID,    ownerUid);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_LABEL,
                   ownerName.isEmpty() ? "Duet" : "Duet with @" + ownerName);
        i.putExtra("duet_layout_mode", layoutMode);
        // Fix 10: chain duet fields
        if (chainRoot != null) {
            i.putExtra("chain_duet_root_id", chainRoot);
            i.putExtra("chain_duet_depth",   chainDepth + 1);
        } else {
            i.putExtra("chain_duet_root_id", reelId);
            i.putExtra("chain_duet_depth",   1);
        }
        startActivity(i);
    }

    private void openDuetsOfReel() {
        if (reelId == null) return;
        Intent i = new Intent(this, DuetsByReelActivity.class);
        i.putExtra(DuetsByReelActivity.EXTRA_REEL_ID,    reelId);
        i.putExtra(DuetsByReelActivity.EXTRA_OWNER_NAME, ownerName);
        startActivity(i);
    }

    private void openInvite() {
        Intent i = new Intent(this, DuetInviteActivity.class);
        i.putExtra(DuetInviteActivity.EXTRA_REEL_ID,    reelId);
        i.putExtra(DuetInviteActivity.EXTRA_VIDEO_URL,  videoUrl);
        i.putExtra(DuetInviteActivity.EXTRA_OWNER_NAME, ownerName);
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
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERMISSIONS && allPermissionsGranted()) startCamera();
        else { Toast.makeText(this, "Camera & microphone permissions required", Toast.LENGTH_SHORT).show(); finish(); }
    }

    @Override
    protected void onDestroy() {
        if (recordTimer     != null) recordTimer.cancel();
        if (activeRecording != null) activeRecording.stop();
        if (exoPlayer       != null) { exoPlayer.stop(); exoPlayer.release(); }
        if (cameraExecutor  != null) cameraExecutor.shutdown();
        super.onDestroy();
    }
}
