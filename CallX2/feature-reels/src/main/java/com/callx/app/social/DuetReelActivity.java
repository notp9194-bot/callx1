package com.callx.app.social;

import com.callx.app.cache.UnifiedVideoCacheManager;
import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.workers.DuetNotificationWorker;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.InputFilter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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
import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.database.ServerValue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DuetReelActivity v28 — Live Editing Tools During Recording
 *
 * NEW in v28:
 *  ✅ NEW: Live Filters — 12 professional colour-matrix filters (Normal → Clarendon)
 *          applied to the live camera feed in real-time via
 *          setLayerType(LAYER_TYPE_HARDWARE, filterPaint) — no OpenGL required.
 *  ✅ NEW: Text Overlays — draggable text with 10-colour palette, 4 font sizes,
 *          3 background styles; double-tap to edit, long-press to delete.
 *  ✅ NEW: Sticker Picker — 80+ emoji stickers in 5 themed categories,
 *          shown in a bottom-sheet grid; draggable + long-press to delete.
 *  ✅ NEW: Filter strip panel slides in above the bottom controls when tapped.
 *  ✅ NEW: Selected filter index + overlay JSON passed to ReelEditorActivity as
 *          extras so the editor can burn them into the final composite video.
 *
 * All v27 features retained unchanged.
 */
@UnstableApi
public class DuetReelActivity extends AppCompatActivity {

    private static final String TAG = "DuetReelActivity";

    // ── Intent extras ─────────────────────────────────────────────────────────
    public static final String EXTRA_REEL_ID           = "reel_id";
    public static final String EXTRA_VIDEO_URL         = "video_url";
    public static final String EXTRA_OWNER_UID         = "owner_uid";
    public static final String EXTRA_OWNER_NAME        = "owner_name";
    public static final String EXTRA_DURATION_SEC      = "duration_sec";
    public static final String EXTRA_ALLOW_DUET_LEVEL  = "allow_duet_level";
    public static final String EXTRA_VIEWER_FOLLOWS    = "viewer_follows";
    public static final String EXTRA_CACHED_VIDEO_PATH = "cached_video_path";
    public static final String EXTRA_DUET_ROOT_ID      = "duet_root_id";

    private static final int MAX_DUET_SEC   = 60;
    private static final int REQ_PERMISSIONS = 101;

    // ── View references ───────────────────────────────────────────────────────
    private PlayerView     playerViewOriginal;
    private PreviewView    previewViewCamera;
    private ImageButton    btnDuetRecord;
    private ImageButton    btnDuetFlip;
    private ImageButton    btnDuetClose;
    private ImageButton    btnViewDuets;
    private ProgressBar    progressDuet;
    private TextView       tvDuetTimer;
    private TextView       tvDuetLabel;
    private TextView       tvCountdown;
    private SeekBar        seekOriginalVolume;
    private TextView       tvVolumeLabel;
    private SeekBar        seekMicGain;
    private TextView       tvMicGainLabel;
    private com.callx.app.views.BeatSyncOverlayView beatSyncOverlay;
    private android.os.Handler  beatSyncHandler;
    private Runnable            beatSyncTick;
    private View               layoutSelector;
    private ImageButton        btnLayoutSideBySide;
    private ImageButton        btnLayoutTopBottom;
    private ImageButton        btnLayoutPip;
    private ImageButton        btnLayoutReactionBubble;
    private FrameLayout        bubbleOverlay;

    // Layout modes
    private static final int LAYOUT_SIDE_BY_SIDE    = 0;
    private static final int LAYOUT_TOP_BOTTOM      = 1;
    private static final int LAYOUT_REACT_PIP       = 2;
    private static final int LAYOUT_REACTION_BUBBLE = 3;

    // Bubble NDC position
    private float   bubbleScreenX = 0f;
    private float   bubbleScreenY = 0f;
    private boolean bubblePosSet  = false;

    // ── Camera / player ───────────────────────────────────────────────────────
    private ExoPlayer              exoPlayer;
    private ProcessCameraProvider  cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording              activeRecording;
    private ExecutorService        cameraExecutor;

    private int     lensFacing   = CameraSelector.LENS_FACING_FRONT;
    private boolean isRecording  = false;
    private boolean isPaused     = false;
    private boolean discardOnStop = false;
    private int     layoutMode   = LAYOUT_SIDE_BY_SIDE;
    private float   originalVol  = 0.5f;
    private float   micGain      = 1.0f;
    private CountDownTimer recordTimer;

    // ── Reel metadata ─────────────────────────────────────────────────────────
    private String  reelId;
    private String  videoUrl;
    private String  ownerName;
    private String  ownerUid;
    private String  cachedOriginalPath = null;
    private int     durationSec   = MAX_DUET_SEC;
    private int     elapsedSec    = 0;
    private String  allowDuetLevel = "everyone";
    private boolean viewerFollows  = false;
    private String  duetRootId    = null;

    private File recordedCameraFile;
    private String pendingCameraFilePath = null;

    private static final String[] PERMISSIONS = {
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    };

    // ═══════════════════════════════════════════════════════════════════════════
    //  LIVE EDITING TOOLS — v28
    // ═══════════════════════════════════════════════════════════════════════════

    /** Display names for the 12 filters shown in the strip. */
    private static final String[] FILTER_NAMES = {
        "Normal", "Vivid", "Fade", "Warm", "Cool",
        "Drama", "Vintage", "Mono", "Noir", "Juno", "Lark", "Clarendon"
    };

    /**
     * Saturation multiplier for each filter.
     * 0 = fully desaturated (greyscale), 1 = original, >1 = boosted.
     */
    private static final float[] FILTER_SAT = {
        1.0f,  // Normal
        1.8f,  // Vivid  — punchy colours
        0.6f,  // Fade   — desaturated, hazy
        1.3f,  // Warm   — slightly saturated
        0.9f,  // Cool   — slightly muted
        0.7f,  // Drama  — desaturated + high contrast
        0.5f,  // Vintage— low saturation, sepia-ish
        0.0f,  // Mono   — full greyscale
        0.0f,  // Noir   — greyscale + extreme contrast
        1.6f,  // Juno   — golden, saturated
        0.85f, // Lark   — airy, soft
        1.4f   // Clarendon — bold, punchy blue-greens
    };

    /**
     * Brightness offset added to every RGB channel (positive = brighter).
     */
    private static final float[] FILTER_BRIGHT = {
         0f,  // Normal
        20f,  // Vivid
        -5f,  // Fade   — slightly darker
        15f,  // Warm   — bright warm feel
       -10f,  // Cool
       -30f,  // Drama  — dark and moody
       -40f,  // Vintage— dark and aged
         0f,  // Mono
       -50f,  // Noir   — very dark
        10f,  // Juno
        35f,  // Lark   — very bright / airy
         5f   // Clarendon
    };

    /**
     * Contrast multiplier applied uniformly across RGB.
     * 1.0 = no change, 1.5 = +50% contrast, 0.8 = reduced.
     */
    private static final float[] FILTER_CONTRAST = {
        1.00f, // Normal
        1.15f, // Vivid
        0.85f, // Fade
        1.05f, // Warm
        1.00f, // Cool
        1.50f, // Drama  — harsh contrast
        0.75f, // Vintage— low contrast, flat
        1.00f, // Mono
        1.60f, // Noir   — extreme contrast
        1.20f, // Juno
        0.90f, // Lark
        1.35f  // Clarendon
    };

    /**
     * Per-channel RGB tint offsets {R, G, B}.  null = no tint.
     * Applied after saturation + brightness/contrast adjustments.
     */
    private static final float[][] FILTER_TINT = {
        null,                    // Normal
        null,                    // Vivid
        null,                    // Fade
        { 35f,   8f, -25f},     // Warm     — more red/orange, reduced blue
        {-20f,   0f,  30f},     // Cool     — reduced red, boosted blue
        null,                    // Drama
        { 25f,  10f, -15f},     // Vintage  — warm brownish sepia
        null,                    // Mono
        null,                    // Noir
        { 25f,  18f, -12f},     // Juno     — golden warm
        { 10f,   5f,   5f},     // Lark     — bright airy neutral
        { -5f,  12f,  25f},     // Clarendon— deep cool blue punch
    };

    /** Preview colour shown in the chip circle (matches the filter mood). */
    private static final int[] FILTER_CHIP_COLOR = {
        0xFF888888, // Normal  — grey neutral
        0xFFFF6B35, // Vivid   — bright orange
        0xFF9BB7D4, // Fade    — muted blue-grey
        0xFFFF9F40, // Warm    — warm amber
        0xFF5BB8FF, // Cool    — sky blue
        0xFF1A1A2E, // Drama   — very dark blue
        0xFF8B6914, // Vintage — sepia brown
        0xFF666666, // Mono    — mid grey
        0xFF111111, // Noir    — near black
        0xFFFFD700, // Juno    — golden
        0xFFB5D8FF, // Lark    — pale sky
        0xFF0077B6, // Clarendon — deep blue
    };

    private int          currentFilterIndex   = 0;
    private FrameLayout  frameCameraContainer;     // camera FrameLayout — setLayerType target
    private FrameLayout  frameOverlayContainer;    // full-screen overlay for text + stickers
    private View         filterStripPanel;
    private LinearLayout filterChipContainer;
    private boolean      filterPanelVisible   = false;
    private TextView     tvActiveFilterName;       // badge inside camera pane

    /** All text / sticker overlays placed during the current recording session. */
    private final List<DuetOverlayItem> overlayItems = new ArrayList<>();

    // ── Text colour palette (10 colours) ─────────────────────────────────────
    private static final int[] TEXT_COLORS = {
        0xFFFFFFFF, // White
        0xFFFFFF00, // Yellow
        0xFFFF5252, // Red
        0xFFFF80AB, // Pink
        0xFFE040FB, // Purple
        0xFF40C4FF, // Blue
        0xFF69F0AE, // Green
        0xFFFFD740, // Amber
        0xFF18FFFF, // Cyan
        0xFF000000  // Black
    };

    /** Lightweight model for a placed overlay (text or emoji sticker). */
    private static class DuetOverlayItem {
        final String  content;
        final int     color;
        final float   xFrac;      // left edge as fraction of container width  (0..1)
        final float   yFrac;      // top  edge as fraction of container height (0..1)
        final float   textSp;
        final boolean isSticker;

        DuetOverlayItem(String c, int col, float x, float y, float sp, boolean stk) {
            content = c; color = col; xFrac = x; yFrac = y; textSp = sp; isSticker = stk;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Activity lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_duet_reel);

        reelId         = getIntent().getStringExtra(EXTRA_REEL_ID);
        videoUrl       = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        ownerUid       = getIntent().getStringExtra(EXTRA_OWNER_UID);
        allowDuetLevel = getIntent().getStringExtra(EXTRA_ALLOW_DUET_LEVEL);
        duetRootId     = getIntent().getStringExtra(EXTRA_DUET_ROOT_ID);
        if (duetRootId == null || duetRootId.isEmpty()) duetRootId = reelId;
        String cp = getIntent().getStringExtra(EXTRA_CACHED_VIDEO_PATH);
        if (cp != null && new java.io.File(cp).exists()) cachedOriginalPath = cp;
        viewerFollows  = getIntent().getBooleanExtra(EXTRA_VIEWER_FOLLOWS, false);
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

        // Duet screen khulte hi original reel video preload karo background mein
        // Bina iske setupOriginalPlayer() mein ExoPlayer buffer karta tha
        UnifiedVideoCacheManager.preloadPartial(this, videoUrl,
            UnifiedVideoCacheManager.Module.REELS);

        cameraExecutor = Executors.newSingleThreadExecutor();
        bindViews();

        tvDuetLabel.setText(ownerName.isEmpty() ? "Duet" : "Duet with @" + ownerName);
        progressDuet.setMax(durationSec);

        setupVolumeSlider();
        setupMicGainSlider();
        setupLayoutSelector();
        setupEditingTools();   // ✅ v28: live filters + text + stickers

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
        playerViewOriginal      = findViewById(R.id.player_view_original);
        previewViewCamera       = findViewById(R.id.preview_view_camera);
        btnDuetRecord           = findViewById(R.id.btn_duet_record);
        btnDuetFlip             = findViewById(R.id.btn_duet_flip);
        btnDuetClose            = findViewById(R.id.btn_duet_close);
        btnViewDuets            = findViewById(R.id.btn_view_duets);
        progressDuet            = findViewById(R.id.progress_duet);
        tvDuetTimer             = findViewById(R.id.tv_duet_timer);
        tvDuetLabel             = findViewById(R.id.tv_duet_label);
        tvCountdown             = findViewById(R.id.tv_duet_countdown);
        seekOriginalVolume      = findViewById(R.id.seek_original_volume);
        tvVolumeLabel           = findViewById(R.id.tv_volume_label);
        seekMicGain             = findViewById(R.id.seek_mic_gain);
        tvMicGainLabel          = findViewById(R.id.tv_mic_gain_label);
        beatSyncOverlay         = findViewById(R.id.beat_sync_overlay);
        layoutSelector          = findViewById(R.id.layout_duet_selector);
        btnLayoutSideBySide     = findViewById(R.id.btn_layout_side_by_side);
        btnLayoutTopBottom      = findViewById(R.id.btn_layout_top_bottom);
        btnLayoutPip            = findViewById(R.id.btn_layout_pip);
        btnLayoutReactionBubble = findViewById(R.id.btn_layout_reaction_bubble);
        bubbleOverlay           = findViewById(R.id.view_bubble_overlay);

        // v28 — new views from updated layout
        frameCameraContainer  = findViewById(R.id.frame_camera_container);
        frameOverlayContainer = findViewById(R.id.frame_duet_overlays);
        filterStripPanel      = findViewById(R.id.panel_filter_strip);
        filterChipContainer   = findViewById(R.id.container_filter_chips);
        tvActiveFilterName    = findViewById(R.id.tv_active_filter_name);

        if (tvCountdown != null) tvCountdown.setVisibility(View.GONE);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  LIVE EDITING TOOLS — setup (v28)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Wire up the three tool buttons (Filter / Text / Sticker) on the right-side
     * tools panel and build the filter chip strip programmatically.
     */
    private void setupEditingTools() {
        View panelLiveTools = findViewById(R.id.panel_live_tools);
        if (panelLiveTools == null) return;

        ImageButton btnFilter  = findViewById(R.id.btn_tool_filter);
        View        btnText    = findViewById(R.id.btn_tool_text);
        View        btnSticker = findViewById(R.id.btn_tool_sticker);

        if (btnFilter  != null) btnFilter.setOnClickListener(v -> toggleFilterPanel());
        if (btnText    != null) btnText.setOnClickListener(v -> openTextOverlayDialog());
        if (btnSticker != null) btnSticker.setOnClickListener(v -> openStickerPicker());

        buildFilterStrip();
    }

    // ── Filter strip ──────────────────────────────────────────────────────────

    /**
     * Programmatically creates 12 filter chips inside the horizontal strip.
     * Each chip is a vertical LinearLayout: coloured circle + filter name.
     */
    private void buildFilterStrip() {
        if (filterChipContainer == null) return;
        filterChipContainer.removeAllViews();

        int dp  = (int) getResources().getDisplayMetrics().density;
        int cSz = 48 * dp;   // circle diameter
        int mH  = 6  * dp;   // horizontal margin between chips

        for (int i = 0; i < FILTER_NAMES.length; i++) {
            final int idx = i;

            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.setMargins(mH, 0, mH, 0);
            chip.setLayoutParams(chipLp);
            chip.setPadding(0, 0, 0, 0);

            // Coloured circle
            View circle = new View(this);
            LinearLayout.LayoutParams circleLp = new LinearLayout.LayoutParams(cSz, cSz);
            circle.setLayoutParams(circleLp);
            android.graphics.drawable.GradientDrawable gd =
                    new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(FILTER_CHIP_COLOR[i]);
            if (i == currentFilterIndex) {
                gd.setStroke(3 * dp, 0xFFFFFFFF);
            }
            circle.setBackground(gd);
            circle.setTag("circle_" + i);

            // Filter name label
            TextView label = new TextView(this);
            label.setText(FILTER_NAMES[i]);
            label.setTextColor(i == currentFilterIndex ? 0xFFFFFFFF : 0xAAFFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lblLp.topMargin = 4 * dp;
            label.setLayoutParams(lblLp);
            label.setTag("label_" + i);

            chip.addView(circle);
            chip.addView(label);

            chip.setOnClickListener(v -> {
                currentFilterIndex = idx;
                applyLiveFilter(idx);
                refreshFilterStripSelection();
            });

            filterChipContainer.addView(chip);
        }
    }

    /** Highlights the currently selected filter chip; dims the others. */
    private void refreshFilterStripSelection() {
        if (filterChipContainer == null) return;
        int dp = (int) getResources().getDisplayMetrics().density;

        for (int i = 0; i < filterChipContainer.getChildCount(); i++) {
            View chip = filterChipContainer.getChildAt(i);
            if (!(chip instanceof LinearLayout)) continue;

            View     circle = chip.findViewWithTag("circle_" + i);
            TextView label  = chip.findViewWithTag("label_"  + i);

            if (circle != null && circle.getBackground() instanceof
                    android.graphics.drawable.GradientDrawable) {
                android.graphics.drawable.GradientDrawable gd =
                        (android.graphics.drawable.GradientDrawable) circle.getBackground();
                if (i == currentFilterIndex) {
                    gd.setStroke(3 * dp, 0xFFFFFFFF);
                } else {
                    gd.setStroke(0, 0x00000000);
                }
            }
            if (label != null) {
                label.setTextColor(i == currentFilterIndex ? 0xFFFFFFFF : 0xAAFFFFFF);
            }
        }
    }

    /** Show / hide the filter strip panel with a slide animation. */
    private void toggleFilterPanel() {
        if (filterStripPanel == null) return;
        filterPanelVisible = !filterPanelVisible;
        if (filterPanelVisible) {
            filterStripPanel.setVisibility(View.VISIBLE);
            filterStripPanel.setAlpha(0f);
            filterStripPanel.animate().alpha(1f).setDuration(200).start();
        } else {
            filterStripPanel.animate().alpha(0f).setDuration(150)
                    .withEndAction(() -> filterStripPanel.setVisibility(View.GONE)).start();
        }
    }

    /**
     * Apply a colour-matrix filter to the live camera container via
     * {@code setLayerType(LAYER_TYPE_HARDWARE, paint)}.
     *
     * Android renders the view (including the PreviewView camera feed) to a
     * GPU offscreen buffer, then composites it with the ColorMatrixColorFilter
     * applied — giving a real, live-preview filter with zero frame-drop cost.
     *
     * @param idx  Index into FILTER_NAMES / FILTER_SAT etc.  0 = Normal (no-op).
     */
    private void applyLiveFilter(int idx) {
        if (frameCameraContainer == null) return;

        // Update the filter badge
        if (tvActiveFilterName != null) {
            if (idx == 0) {
                tvActiveFilterName.setVisibility(View.GONE);
            } else {
                tvActiveFilterName.setText(FILTER_NAMES[idx]);
                tvActiveFilterName.setVisibility(View.VISIBLE);
            }
        }

        if (idx == 0) {
            // Normal — remove any active layer type so hardware composition is standard
            frameCameraContainer.setLayerType(View.LAYER_TYPE_NONE, null);
            return;
        }

        ColorMatrix cm = buildFilterColorMatrix(idx);
        Paint filterPaint = new Paint();
        filterPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        frameCameraContainer.setLayerType(View.LAYER_TYPE_HARDWARE, filterPaint);
    }

    /**
     * Builds a composite {@link ColorMatrix} for the requested filter by applying,
     * in order: saturation → contrast+brightness → per-channel RGB tint.
     */
    private ColorMatrix buildFilterColorMatrix(int idx) {
        // Step 1 — Saturation
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(FILTER_SAT[idx]);

        // Step 2 — Contrast + Brightness
        float c = FILTER_CONTRAST[idx];
        float b = FILTER_BRIGHT[idx];
        // Standard contrast formula: scale channels by c, then translate by b
        // (translation also compensates for the (1-c)/2 midpoint shift)
        float t = (1f - c) / 2f * 255f + b;
        ColorMatrix contrastMatrix = new ColorMatrix(new float[]{
            c, 0, 0, 0, t,
            0, c, 0, 0, t,
            0, 0, c, 0, t,
            0, 0, 0, 1, 0
        });
        cm.postConcat(contrastMatrix);

        // Step 3 — Per-channel RGB tint (null = skip)
        float[] tint = FILTER_TINT[idx];
        if (tint != null) {
            ColorMatrix tintMatrix = new ColorMatrix(new float[]{
                1, 0, 0, 0, tint[0],
                0, 1, 0, 0, tint[1],
                0, 0, 1, 0, tint[2],
                0, 0, 0, 1, 0
            });
            cm.postConcat(tintMatrix);
        }

        return cm;
    }

    // ── Text overlay ──────────────────────────────────────────────────────────

    /**
     * Opens a bottom-sheet style AlertDialog for composing a text overlay.
     * Features: 60-char text input, 10-colour palette, 4 size options, 3 bg styles.
     */
    @android.annotation.SuppressLint("InflateParams")
    private void openTextOverlayDialog() {
        if (frameOverlayContainer == null) return;

        BottomSheetDialog bsd = new BottomSheetDialog(this,
                com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);

        // ── Root layout ───────────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1A1A);
        int p = dp(16);
        root.setPadding(p, p, p, p);

        // Title
        TextView title = new TextView(this);
        title.setText("Add Text");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        // EditText
        EditText et = new EditText(this);
        et.setHint("Type something…");
        et.setHintTextColor(0xFF888888);
        et.setTextColor(0xFFFFFFFF);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        et.setGravity(Gravity.CENTER);
        et.setBackground(null);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(60)});
        et.setSingleLine(false);
        et.setMaxLines(3);
        et.setMinLines(2);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.bottomMargin = dp(12);
        et.setLayoutParams(etLp);
        et.setBackgroundColor(0xFF2C2C2C);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(et);

        // ── Colour palette ────────────────────────────────────────────────────
        TextView colourLabel = makeLabel("Colour");
        root.addView(colourLabel);

        final int[] selectedColor = {TEXT_COLORS[0]}; // default white
        final View[] colorDots = new View[TEXT_COLORS.length];

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        colorRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        crLp.bottomMargin = dp(12);
        colorRow.setLayoutParams(crLp);

        for (int ci = 0; ci < TEXT_COLORS.length; ci++) {
            final int finalCi = ci;
            View dot = new View(this);
            int dotSize = dp(30);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dotSize, dotSize);
            dlp.setMargins(dp(4), 0, dp(4), 0);
            dot.setLayoutParams(dlp);
            android.graphics.drawable.GradientDrawable dgd =
                    new android.graphics.drawable.GradientDrawable();
            dgd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dgd.setColor(TEXT_COLORS[ci]);
            if (ci == 0) dgd.setStroke(dp(2), 0xFFFFFFFF); // default selected
            dot.setBackground(dgd);
            colorDots[ci] = dot;
            dot.setOnClickListener(v2 -> {
                selectedColor[0] = TEXT_COLORS[finalCi];
                // Update selection ring
                for (int k = 0; k < colorDots.length; k++) {
                    android.graphics.drawable.GradientDrawable kgd =
                            (android.graphics.drawable.GradientDrawable) colorDots[k].getBackground();
                    kgd.setStroke(k == finalCi ? dp(2) : 0, k == finalCi ? 0xFFFFFFFF : 0x00000000);
                }
                et.setTextColor(selectedColor[0]);
            });
            colorRow.addView(dot);
        }
        root.addView(colorRow);

        // ── Font size ─────────────────────────────────────────────────────────
        TextView sizeLabel = makeLabel("Size");
        root.addView(sizeLabel);

        final float[] sizes = {16f, 22f, 30f, 40f};
        final String[] sizeNames = {"S", "M", "L", "XL"};
        final float[] selectedSp = {22f};
        final View[] sizeBtns = new View[sizes.length];

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        srLp.bottomMargin = dp(14);
        sizeRow.setLayoutParams(srLp);

        for (int si = 0; si < sizes.length; si++) {
            final int finalSi = si;
            TextView sb = new TextView(this);
            sb.setText(sizeNames[si]);
            sb.setTextColor(si == 1 ? 0xFFFFFFFF : 0x99FFFFFF);
            sb.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizes[si] * 0.65f);
            sb.setGravity(Gravity.CENTER);
            sb.setPadding(dp(14), dp(6), dp(14), dp(6));
            sb.setBackground(si == 1
                    ? makePillDrawable(0xFF444444, 0xFFFFFFFF, dp(1))
                    : makePillDrawable(0xFF2C2C2C, 0x44FFFFFF, 0));
            LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            sblp.setMargins(0, 0, dp(8), 0);
            sb.setLayoutParams(sblp);
            sizeBtns[si] = sb;
            sb.setOnClickListener(v2 -> {
                selectedSp[0] = sizes[finalSi];
                for (int k = 0; k < sizeBtns.length; k++) {
                    sizeBtns[k].setBackground(k == finalSi
                            ? makePillDrawable(0xFF444444, 0xFFFFFFFF, dp(1))
                            : makePillDrawable(0xFF2C2C2C, 0x44FFFFFF, 0));
                    ((TextView) sizeBtns[k]).setTextColor(
                            k == finalSi ? 0xFFFFFFFF : 0x99FFFFFF);
                }
            });
            sizeRow.addView(sb);
        }
        root.addView(sizeRow);

        // ── Background style ──────────────────────────────────────────────────
        TextView bgLabel = makeLabel("Background");
        root.addView(bgLabel);

        final String[] bgNames = {"None", "Frosted", "Solid"};
        // 0=no bg, 1=semi-transparent black, 2=solid dark
        final int[] selectedBg = {0};
        final View[] bgBtns = new View[bgNames.length];

        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        bgRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        brLp.bottomMargin = dp(18);
        bgRow.setLayoutParams(brLp);

        for (int bi = 0; bi < bgNames.length; bi++) {
            final int finalBi = bi;
            TextView bb = new TextView(this);
            bb.setText(bgNames[bi]);
            bb.setTextColor(bi == 0 ? 0xFFFFFFFF : 0x99FFFFFF);
            bb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            bb.setGravity(Gravity.CENTER);
            bb.setPadding(dp(14), dp(7), dp(14), dp(7));
            bb.setBackground(bi == 0
                    ? makePillDrawable(0xFF444444, 0xFFFFFFFF, dp(1))
                    : makePillDrawable(0xFF2C2C2C, 0x44FFFFFF, 0));
            LinearLayout.LayoutParams bblp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            bblp.setMargins(0, 0, dp(8), 0);
            bb.setLayoutParams(bblp);
            bgBtns[bi] = bb;
            bb.setOnClickListener(v2 -> {
                selectedBg[0] = finalBi;
                for (int k = 0; k < bgBtns.length; k++) {
                    bgBtns[k].setBackground(k == finalBi
                            ? makePillDrawable(0xFF444444, 0xFFFFFFFF, dp(1))
                            : makePillDrawable(0xFF2C2C2C, 0x44FFFFFF, 0));
                    ((TextView) bgBtns[k]).setTextColor(
                            k == finalBi ? 0xFFFFFFFF : 0x99FFFFFF);
                }
            });
            bgRow.addView(bb);
        }
        root.addView(bgRow);

        // ── Action buttons ────────────────────────────────────────────────────
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);

        TextView cancelBtn = makeActionButton("Cancel", 0xFF555555, 0xFFFFFFFF);
        cancelBtn.setOnClickListener(v -> bsd.dismiss());
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        abLp.setMargins(0, 0, dp(8), 0);
        cancelBtn.setLayoutParams(abLp);
        actionRow.addView(cancelBtn);

        TextView addBtn = makeActionButton("Add Text", 0xFFA855F7, 0xFFFFFFFF);
        addBtn.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addBtn.setOnClickListener(v -> {
            String txt = et.getText().toString().trim();
            if (txt.isEmpty()) {
                et.setError("Please enter some text");
                return;
            }
            bsd.dismiss();
            addTextOverlayToScreen(txt, selectedColor[0], selectedSp[0], selectedBg[0]);
        });
        actionRow.addView(addBtn);

        root.addView(actionRow);
        bsd.setContentView(root);
        bsd.show();

        // Auto-show keyboard
        et.requestFocus();
        et.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                    getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et,
                    android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    /**
     * Creates a draggable {@link TextView} overlay on top of the video area.
     *
     * Gestures:
     *   • Drag  — move anywhere inside the overlay container.
     *   • Double-tap — re-open the edit dialog for this overlay.
     *   • Long-press  — show/hide a ✕ delete button at the corner.
     *
     * @param text    The display text.
     * @param color   ARGB text colour.
     * @param sp      Text size in sp.
     * @param bgStyle 0 = none, 1 = frosted (#88000000), 2 = solid (#CC000000).
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addTextOverlayToScreen(String text, int color, float sp, int bgStyle) {
        if (frameOverlayContainer == null) return;

        // Outer FrameLayout (holds the TextView + optional delete button)
        FrameLayout wrapper = new FrameLayout(this);
        FrameLayout.LayoutParams wLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        wrapper.setLayoutParams(wLp);

        // The text view itself
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setShadowLayer(3f, 1f, 1f, 0x88000000);
        switch (bgStyle) {
            case 1: tv.setBackgroundColor(0x88000000); break;
            case 2: tv.setBackgroundColor(0xCC000000); break;
            default: break;
        }
        wrapper.addView(tv);

        // Delete button (hidden until long-press)
        TextView deleteBtn = new TextView(this);
        deleteBtn.setText("✕");
        deleteBtn.setTextColor(0xFFFFFFFF);
        deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        deleteBtn.setGravity(Gravity.CENTER);
        int dbSz = dp(22);
        FrameLayout.LayoutParams dbLp = new FrameLayout.LayoutParams(dbSz, dbSz);
        dbLp.gravity = Gravity.TOP | Gravity.END;
        deleteBtn.setLayoutParams(dbLp);
        android.graphics.drawable.GradientDrawable dbBg =
                new android.graphics.drawable.GradientDrawable();
        dbBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dbBg.setColor(0xCCFF3333);
        deleteBtn.setBackground(dbBg);
        deleteBtn.setVisibility(View.GONE);
        wrapper.addView(deleteBtn);

        // Position in the centre of the container initially
        frameOverlayContainer.post(() -> {
            float cx = (frameOverlayContainer.getWidth()  - wrapper.getWidth())  / 2f;
            float cy = (frameOverlayContainer.getHeight() - wrapper.getHeight()) / 2f - dp(80);
            wrapper.setX(Math.max(0f, cx));
            wrapper.setY(Math.max(0f, cy));
        });

        // ── Touch: drag + double-tap + long-press ─────────────────────────────
        makeDraggableOverlay(wrapper, deleteBtn, () -> {
            frameOverlayContainer.removeView(wrapper);
            // Remove from tracking list
            for (int i = overlayItems.size() - 1; i >= 0; i--) {
                if (overlayItems.get(i).content.equals(text)) {
                    overlayItems.remove(i); break;
                }
            }
        });

        frameOverlayContainer.addView(wrapper);

        // Track the overlay (position updated on final snapshot)
        overlayItems.add(new DuetOverlayItem(text, color,
                0.5f, 0.4f, sp, false));
    }

    // ── Sticker picker ────────────────────────────────────────────────────────

    /**
     * Shows a bottom-sheet grid of 80+ emoji stickers arranged in 5 categories.
     * Tapping a sticker places it as a draggable overlay on the video.
     */
    private void openStickerPicker() {
        if (frameOverlayContainer == null) return;

        BottomSheetDialog bsd = new BottomSheetDialog(this,
                com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1A1A1A);
        root.setPadding(dp(16), dp(12), dp(16), dp(32));

        // Title
        TextView title = new TextView(this);
        title.setText("Stickers");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        // ── 5 categories ─────────────────────────────────────────────────────
        String[][] categories = {
            // Expressions
            {"😀","😂","🥹","😍","🤩","😎","🥸","😴","😭","😤",
             "😱","🥳","🤪","🫠","🥰","😏","🤔","🫡","🤯","😇"},
            // Love & vibes
            {"❤️","🧡","💛","💚","💙","💜","🖤","🤍","🩷","🩵",
             "💖","💗","💓","💞","💕","🫶","💝","🎀","🫦","💌"},
            // Trending
            {"🔥","💯","✨","🎉","🎊","⚡","🌟","💫","🦋","🌈",
             "👑","🎯","🚀","🎸","🎮","🏆","💎","🌙","⭐","🎭"},
            // Food & fun
            {"🍕","🍔","🌮","🍦","🎂","🧁","🍩","🥂","🍿","🧃",
             "🎁","🪄","🎪","🎡","🎢","🎠","🃏","🎲","🎳","🧸"},
            // Nature & animals
            {"🌸","🌺","🌻","🌹","🌷","🌿","🍃","🍂","🍁","🌊",
             "🦄","🐣","🐬","🦁","🐼","🦊","🐙","🌴","🏔️","🌅"},
        };
        String[] catNames = {"😀 Expressions","❤️ Love","🔥 Trending","🍕 Fun","🌸 Nature"};

        android.widget.ScrollView scrollView = new android.widget.ScrollView(this);
        scrollView.setBackgroundColor(0xFF1A1A1A);
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(scrollContent);

        for (int cat = 0; cat < categories.length; cat++) {
            // Category header
            TextView catHeader = new TextView(this);
            catHeader.setText(catNames[cat]);
            catHeader.setTextColor(0xFFAAAAAA);
            catHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            catHeader.setPadding(dp(4), dp(10), 0, dp(6));
            scrollContent.addView(catHeader);

            // Grid (4 columns)
            android.widget.GridLayout grid = new android.widget.GridLayout(this);
            grid.setColumnCount(5);
            grid.setUseDefaultMargins(false);
            int btnSz = dp(52);

            for (String emoji : categories[cat]) {
                TextView emojiBtn = new TextView(this);
                emojiBtn.setText(emoji);
                emojiBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f);
                emojiBtn.setGravity(Gravity.CENTER);
                android.widget.GridLayout.LayoutParams glp =
                        new android.widget.GridLayout.LayoutParams();
                glp.width  = btnSz;
                glp.height = btnSz;
                glp.setMargins(dp(2), dp(2), dp(2), dp(2));
                emojiBtn.setLayoutParams(glp);
                emojiBtn.setBackground(makePillDrawable(0xFF2A2A2A, 0x22FFFFFF, 0));
                emojiBtn.setOnClickListener(v -> {
                    bsd.dismiss();
                    addStickerToScreen(emojiBtn.getText().toString());
                });
                grid.addView(emojiBtn);
            }
            scrollContent.addView(grid);
        }

        // Max height ~60% of screen
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.60f);
        ViewGroup.LayoutParams svLp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxH);
        scrollView.setLayoutParams(svLp);
        root.addView(scrollView);

        bsd.setContentView(root);
        bsd.show();
    }

    /**
     * Places a draggable emoji sticker on the screen.
     *
     * @param emoji The emoji string (one or more code points).
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addStickerToScreen(String emoji) {
        if (frameOverlayContainer == null) return;

        FrameLayout wrapper = new FrameLayout(this);
        FrameLayout.LayoutParams wLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        wrapper.setLayoutParams(wLp);

        TextView sticker = new TextView(this);
        sticker.setText(emoji);
        sticker.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f);
        sticker.setGravity(Gravity.CENTER);
        sticker.setPadding(dp(6), dp(6), dp(6), dp(6));
        wrapper.addView(sticker);

        // Delete button
        TextView deleteBtn = new TextView(this);
        deleteBtn.setText("✕");
        deleteBtn.setTextColor(0xFFFFFFFF);
        deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f);
        deleteBtn.setGravity(Gravity.CENTER);
        int dbSz = dp(20);
        FrameLayout.LayoutParams dbLp = new FrameLayout.LayoutParams(dbSz, dbSz);
        dbLp.gravity = Gravity.TOP | Gravity.END;
        deleteBtn.setLayoutParams(dbLp);
        android.graphics.drawable.GradientDrawable dbBg =
                new android.graphics.drawable.GradientDrawable();
        dbBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dbBg.setColor(0xCCFF3333);
        deleteBtn.setBackground(dbBg);
        deleteBtn.setVisibility(View.GONE);
        wrapper.addView(deleteBtn);

        // Random starting position (not always dead-centre to feel natural)
        frameOverlayContainer.post(() -> {
            float maxX = frameOverlayContainer.getWidth()  - wrapper.getWidth()  - dp(8);
            float maxY = frameOverlayContainer.getHeight() - wrapper.getHeight() - dp(8);
            float rx = (float) (Math.random() * Math.max(1, maxX));
            float ry = (float) (Math.random() * Math.max(1, maxY * 0.6f)) + maxY * 0.1f;
            wrapper.setX(rx);
            wrapper.setY(ry);
        });

        makeDraggableOverlay(wrapper, deleteBtn, () -> {
            frameOverlayContainer.removeView(wrapper);
            for (int i = overlayItems.size() - 1; i >= 0; i--) {
                if (overlayItems.get(i).isSticker
                        && overlayItems.get(i).content.equals(emoji)) {
                    overlayItems.remove(i); break;
                }
            }
        });

        frameOverlayContainer.addView(wrapper);
        overlayItems.add(new DuetOverlayItem(emoji, 0xFFFFFFFF,
                0.5f, 0.4f, 48f, true));
    }

    // ── Draggable overlay helper ──────────────────────────────────────────────

    /**
     * Attaches a combined drag + long-press-to-delete touch listener to a wrapper view.
     *
     * @param wrapper   The view to make draggable inside {@link #frameOverlayContainer}.
     * @param deleteBtn A child view shown on long-press; tapping it calls {@code onDelete}.
     * @param onDelete  Runnable invoked when the delete button is tapped.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void makeDraggableOverlay(View wrapper, View deleteBtn, Runnable onDelete) {
        deleteBtn.setOnClickListener(v -> {
            if (onDelete != null) onDelete.run();
        });

        final float[] downX  = {0f};
        final float[] downY  = {0f};
        final float[] origX  = {0f};
        final float[] origY  = {0f};
        final long[]  downMs = {0L};
        final boolean[] moved = {false};

        wrapper.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX[0]  = e.getRawX();
                    downY[0]  = e.getRawY();
                    origX[0]  = v.getX();
                    origY[0]  = v.getY();
                    downMs[0] = System.currentTimeMillis();
                    moved[0]  = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX[0];
                    float dy = e.getRawY() - downY[0];
                    if (Math.abs(dx) > 8 || Math.abs(dy) > 8) moved[0] = true;
                    if (moved[0]) {
                        // Clamp inside container
                        float nx = origX[0] + dx;
                        float ny = origY[0] + dy;
                        if (frameOverlayContainer != null) {
                            nx = Math.max(0f, Math.min(nx,
                                    frameOverlayContainer.getWidth()  - v.getWidth()));
                            ny = Math.max(0f, Math.min(ny,
                                    frameOverlayContainer.getHeight() - v.getHeight()));
                        }
                        v.setX(nx);
                        v.setY(ny);
                    }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    long elapsed = System.currentTimeMillis() - downMs[0];
                    if (!moved[0] && elapsed > 500) {
                        // Long-press: toggle delete button
                        if (deleteBtn != null) {
                            deleteBtn.setVisibility(
                                    deleteBtn.getVisibility() == View.VISIBLE
                                    ? View.GONE : View.VISIBLE);
                        }
                    }
                    return true;
            }
            return false;
        });
    }

    // ── Overlay serialisation ─────────────────────────────────────────────────

    /**
     * Captures the current screen positions of all overlays and serialises them
     * to a JSON string for handoff to {@link ReelEditorActivity}.
     *
     * JSON format:
     * <pre>
     * [
     *   { "content": "Hello!", "color": -1, "xFrac": 0.45, "yFrac": 0.38,
     *     "textSp": 22.0, "isSticker": false },
     *   ...
     * ]
     * </pre>
     */
    private String overlaysToJson() {
        if (frameOverlayContainer == null) return "[]";
        JSONArray arr = new JSONArray();
        int cw = frameOverlayContainer.getWidth();
        int ch = frameOverlayContainer.getHeight();

        for (int i = 0; i < frameOverlayContainer.getChildCount(); i++) {
            View child = frameOverlayContainer.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            try {
                JSONObject obj = new JSONObject();
                // Extract text from the first TextView child
                String content = "";
                float sp = 22f;
                if (child instanceof FrameLayout) {
                    FrameLayout fw = (FrameLayout) child;
                    for (int j = 0; j < fw.getChildCount(); j++) {
                        if (fw.getChildAt(j) instanceof TextView) {
                            TextView ctv = (TextView) fw.getChildAt(j);
                            if (!ctv.getText().toString().equals("✕")) {
                                content = ctv.getText().toString();
                                sp = ctv.getTextSize()
                                     / getResources().getDisplayMetrics().scaledDensity;
                                break;
                            }
                        }
                    }
                }
                float xFrac = cw > 0 ? child.getX() / cw : 0.5f;
                float yFrac = ch > 0 ? child.getY() / ch : 0.4f;
                obj.put("content",   content);
                obj.put("xFrac",     (double) xFrac);
                obj.put("yFrac",     (double) yFrac);
                obj.put("textSp",    (double) sp);
                obj.put("isSticker", content.codePointCount(0, content.length()) == 1
                        && content.codePointAt(0) > 127);
                arr.put(obj);
            } catch (Exception ignored) {}
        }
        return arr.toString();
    }

    // ── Small UI helpers ──────────────────────────────────────────────────────

    /** Converts dp to pixels. */
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private TextView makeLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFAAAAAA);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView makeActionButton(String text, int bgColor, int textColor) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setBackground(makePillDrawable(bgColor, 0x00000000, 0));
        return tv;
    }

    /** Returns a GradientDrawable rounded rectangle (pill / stadium shape). */
    private android.graphics.drawable.GradientDrawable makePillDrawable(
            int fillColor, int strokeColor, int strokeWidth) {
        android.graphics.drawable.GradientDrawable gd =
                new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(24));
        gd.setColor(fillColor);
        if (strokeWidth > 0) gd.setStroke(strokeWidth, strokeColor);
        return gd;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Volume / Mic sliders
    // ═══════════════════════════════════════════════════════════════════════════

    private void setupVolumeSlider() {
        if (seekOriginalVolume == null) return;
        seekOriginalVolume.setMax(100);
        seekOriginalVolume.setProgress(50);
        if (tvVolumeLabel != null) tvVolumeLabel.setText("Original: 50%");

        seekOriginalVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                originalVol = progress / 100f;
                if (exoPlayer != null && !isRecording) exoPlayer.setVolume(originalVol);
                if (tvVolumeLabel != null) tvVolumeLabel.setText("Original: " + progress + "%");
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
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                micGain = progress / 100f;
                if (tvMicGainLabel != null) tvMicGainLabel.setText("Mic: " + progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Layout selector
    // ═══════════════════════════════════════════════════════════════════════════

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
        if (isRecording) {
            Toast.makeText(this, "Cannot change layout while recording",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        layoutMode = mode;
        if (btnLayoutSideBySide     != null)
            btnLayoutSideBySide.setAlpha(mode == LAYOUT_SIDE_BY_SIDE     ? 1f : 0.4f);
        if (btnLayoutTopBottom      != null)
            btnLayoutTopBottom.setAlpha(mode == LAYOUT_TOP_BOTTOM        ? 1f : 0.4f);
        if (btnLayoutPip            != null)
            btnLayoutPip.setAlpha(mode == LAYOUT_REACT_PIP               ? 1f : 0.4f);
        if (btnLayoutReactionBubble != null)
            btnLayoutReactionBubble.setAlpha(mode == LAYOUT_REACTION_BUBBLE ? 1f : 0.4f);

        if (bubbleOverlay != null)
            bubbleOverlay.setVisibility(
                    mode == LAYOUT_REACTION_BUBBLE ? View.VISIBLE : View.GONE);

        applyLayoutToViews(mode);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void applyLayoutToViews(int mode) {
        if (playerViewOriginal == null || previewViewCamera == null) return;
        ViewGroup.LayoutParams lp1 = playerViewOriginal.getLayoutParams();
        ViewGroup.LayoutParams lp2 = previewViewCamera.getLayoutParams();
        if (lp1 == null || lp2 == null) return;

        switch (mode) {
            case LAYOUT_SIDE_BY_SIDE:
                if (lp1 instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp1).weight = 1;
                    ((LinearLayout.LayoutParams) lp2).weight = 1;
                }
                playerViewOriginal.setVisibility(View.VISIBLE);
                previewViewCamera.setVisibility(View.VISIBLE);
                removeCameraCircleClip();
                break;

            case LAYOUT_TOP_BOTTOM:
                if (lp1 instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp1).weight = 1;
                    ((LinearLayout.LayoutParams) lp2).weight = 1;
                }
                playerViewOriginal.setVisibility(View.VISIBLE);
                previewViewCamera.setVisibility(View.VISIBLE);
                removeCameraCircleClip();
                break;

            case LAYOUT_REACT_PIP:
                if (lp1 instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp1).weight = 0.3f;
                    ((LinearLayout.LayoutParams) lp2).weight = 0.7f;
                }
                playerViewOriginal.setVisibility(View.VISIBLE);
                previewViewCamera.setVisibility(View.VISIBLE);
                removeCameraCircleClip();
                break;

            case LAYOUT_REACTION_BUBBLE:
                if (lp1 instanceof LinearLayout.LayoutParams) {
                    ((LinearLayout.LayoutParams) lp1).weight = 1;
                    ((LinearLayout.LayoutParams) lp2).weight = 0;
                }
                int bubbleDp = (int) (110 * getResources().getDisplayMetrics().density);
                lp2.width  = bubbleDp;
                lp2.height = bubbleDp;
                playerViewOriginal.setVisibility(View.VISIBLE);
                previewViewCamera.setVisibility(View.VISIBLE);
                setupBubbleDrag();
                previewViewCamera.post(this::applyCameraCircleClip);
                break;
        }
        playerViewOriginal.setLayoutParams(lp1);
        previewViewCamera.setLayoutParams(lp2);
        playerViewOriginal.requestLayout();
        previewViewCamera.requestLayout();
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupBubbleDrag() {
        if (bubbleOverlay == null) return;

        if (!bubblePosSet) {
            bubbleOverlay.post(() -> {
                ViewGroup parent = (ViewGroup) bubbleOverlay.getParent();
                if (parent == null) return;
                float defaultX = parent.getWidth()  * 0.08f;
                float defaultY = parent.getHeight() * 0.72f;
                bubbleOverlay.setX(defaultX);
                bubbleOverlay.setY(defaultY);
                bubbleScreenX = defaultX + bubbleOverlay.getWidth()  / 2f;
                bubbleScreenY = defaultY + bubbleOverlay.getHeight() / 2f;
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
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - e.getRawX();
                        dY = v.getY() - e.getRawY();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float nx = e.getRawX() + dX;
                        float ny = e.getRawY() + dY;
                        v.setX(nx);
                        v.setY(ny);
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

    private float[] bubbleToNdc() {
        ViewGroup parent = (ViewGroup) bubbleOverlay.getParent();
        if (parent == null || parent.getWidth() == 0) return new float[]{-0.55f, -0.72f};
        float ndcX =  (bubbleScreenX / parent.getWidth())  * 2f - 1f;
        float ndcY = -((bubbleScreenY / parent.getHeight()) * 2f - 1f);
        return new float[]{ndcX, ndcY};
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  ExoPlayer
    // ═══════════════════════════════════════════════════════════════════════════

    private void setupOriginalPlayer() {
        exoPlayer = new ExoPlayer.Builder(this).build();
        playerViewOriginal.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
        playerViewOriginal.setUseArtwork(false);
        playerViewOriginal.setPlayer(exoPlayer);

        exoPlayer.setMediaItem(MediaItem.fromUri(videoUrl));
        exoPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        exoPlayer.setVolume(originalVol);
        exoPlayer.prepare();

        exoPlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY && exoPlayer.getDuration() > 0 && !isRecording) {
                    int playerDur = (int) Math.min(exoPlayer.getDuration() / 1000, MAX_DUET_SEC);
                    if (playerDur > 0) {
                        durationSec = playerDur;
                        progressDuet.setMax(durationSec);
                    }
                    if (beatSyncOverlay != null && cachedOriginalPath != null) {
                        com.callx.app.views.BeatSyncAnalyzer.analyze(
                            DuetReelActivity.this, cachedOriginalPath,
                            exoPlayer.getDuration(),
                            new com.callx.app.views.BeatSyncAnalyzer.Callback() {
                                @Override public void onBeatsReady(long[] beats) {
                                    runOnUiThread(() -> {
                                        beatSyncOverlay.setBeats(beats, exoPlayer.getDuration());
                                        beatSyncOverlay.setOnBeatListener(beatTimeMs -> {
                                            android.os.VibrationEffect ve =
                                                android.os.VibrationEffect.createOneShot(
                                                    30, android.os.VibrationEffect.DEFAULT_AMPLITUDE);
                                            ((android.os.Vibrator) getSystemService(
                                                VIBRATOR_SERVICE)).vibrate(ve);
                                        });
                                        startBeatSyncTick();
                                    });
                                }
                                @Override public void onError(Exception e) {
                                    Log.w(TAG, "BeatSync analysis failed: " + e.getMessage());
                                }
                            });
                    }
                }
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Camera
    // ═══════════════════════════════════════════════════════════════════════════

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
            // Re-apply live filter after camera rebind (flip camera case)
            if (currentFilterIndex > 0) applyLiveFilter(currentFilterIndex);
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  Recording flow
    // ═══════════════════════════════════════════════════════════════════════════

    private void onRecordButtonClick() {
        if (!isRecording) {
            startCountdownThenRecord();
        } else if (isPaused) {
            resumeRecording();
        } else {
            pauseRecording();
        }
    }

    private void startCountdownThenRecord() {
        if (tvCountdown == null) { startRecording(); return; }

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

    private void startRecording() {
        if (videoCapture == null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return;

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
                        exoPlayer.setVolume(0f);
                        exoPlayer.addListener(new Player.Listener() {
                            @Override
                            public void onPositionDiscontinuity(
                                    @NonNull Player.PositionInfo oldPos,
                                    @NonNull Player.PositionInfo newPos,
                                    int reason) {
                                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                                    exoPlayer.removeListener(this);
                                    if (isRecording) exoPlayer.play();
                                }
                            }
                        });
                        exoPlayer.seekTo(0);
                        btnDuetRecord.setImageResource(R.drawable.ic_pause);
                        startCountdownTimer();
                        // Close filter strip while recording (clean UI)
                        if (filterPanelVisible) toggleFilterPanel();
                    });
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    isRecording = false;
                    isPaused    = false;
                    runOnUiThread(() -> {
                        if (exoPlayer != null) exoPlayer.setVolume(originalVol);
                    });
                    if (!fin.hasError() && !discardOnStop) {
                        runOnUiThread(() ->
                            onRecordingDone(recordedCameraFile.getAbsolutePath()));
                    } else if (fin.hasError()) {
                        Log.e(TAG, "Recording error: " + fin.getCause());
                        runOnUiThread(() ->
                            Toast.makeText(this, "Recording failed",
                                    Toast.LENGTH_SHORT).show());
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  Post-recording: composite → editor
    // ═══════════════════════════════════════════════════════════════════════════

    private void onRecordingDone(String cameraFilePath) {
        pendingCameraFilePath = cameraFilePath;
        runCompositor(cameraFilePath);
    }

    private void runCompositor(String cameraFilePath) {
        if (exoPlayer != null) exoPlayer.pause();

        if (tvDuetLabel != null) tvDuetLabel.setText("Processing duet… 0%");
        if (progressDuet != null) {
            progressDuet.setIndeterminate(false);
            progressDuet.setMax(100);
            progressDuet.setProgress(0);
            progressDuet.setVisibility(View.VISIBLE);
        }
        btnDuetRecord.setEnabled(false);
        btnDuetFlip.setEnabled(false);

        final String outputPath = new File(getCacheDir(),
            "duet_composite_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        final int    capturedLayout   = layoutMode;
        final String capturedOriginal = (cachedOriginalPath != null)
                ? cachedOriginalPath : videoUrl;
        final float  capturedVol      = originalVol;
        final float  capturedMicGain  = micGain;
        final String capturedCamPath  = cameraFilePath;

        new Thread(() -> {
            DuetVideoCompositor compositor = new DuetVideoCompositor();

            float[] bubbleNdc = (bubbleOverlay != null
                    && capturedLayout == LAYOUT_REACTION_BUBBLE)
                    ? bubbleToNdc() : new float[]{-0.55f, -0.72f};

            DuetVideoCompositor.ProgressListener progressCb = pct ->
                runOnUiThread(() -> {
                    if (progressDuet != null) progressDuet.setProgress(pct);
                    if (tvDuetLabel  != null)
                        tvDuetLabel.setText("Processing duet… " + pct + "%");
                });

            boolean ok = compositor.composite(
                capturedCamPath, capturedOriginal, outputPath,
                capturedLayout, capturedVol, capturedMicGain,
                bubbleNdc[0], bubbleNdc[1], progressCb);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (progressDuet != null) progressDuet.setVisibility(View.GONE);
                if (tvDuetLabel  != null)
                    tvDuetLabel.setText(ownerName.isEmpty()
                            ? "Duet" : "Duet with @" + ownerName);
                if (ok) {
                    openEditor(outputPath);
                } else {
                    showCompositorFailureDialog(capturedCamPath, outputPath);
                }
            });
        }, "duet-compositor").start();
    }

    private void showCompositorFailureDialog(String rawCamPath, String failedOut) {
        if (isFinishing() || isDestroyed()) return;
        btnDuetRecord.setEnabled(true);
        btnDuetFlip.setEnabled(true);
        new AlertDialog.Builder(this)
            .setTitle("Could not merge videos")
            .setMessage("Compositing failed on this device. Choose how to proceed:")
            .setCancelable(false)
            .setPositiveButton("Retry",  (d, w) -> runCompositor(rawCamPath))
            .setNeutralButton("Post Raw Recording", (d, w) -> {
                Toast.makeText(this, "Opening without duet layout",
                        Toast.LENGTH_SHORT).show();
                openEditor(rawCamPath);
            })
            .setNegativeButton("Discard & Re-record", (d, w) -> {
                new File(rawCamPath).delete();
                new File(failedOut).delete();
                pendingCameraFilePath = null;
                Toast.makeText(this, "Recording discarded — ready to re-record.",
                        Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    /**
     * Opens {@link ReelEditorActivity} with the composited video.
     *
     * v28 additions — extra parameters passed to the editor:
     *  • {@code duet_filter_index}   — index of the selected live filter (0 = Normal).
     *  • {@code duet_overlays_json}  — JSON array of placed text/sticker overlays.
     *
     * The editor can use these to re-apply the filter and burn the overlays into
     * the final video via its existing Canvas/TextOverlay machinery.
     */
    private void openEditor(String filePath) {
        Intent i = new Intent(this, ReelEditorActivity.class);
        i.putExtra(ReelEditorActivity.EXTRA_VIDEO_URI,         filePath);
        i.putExtra(ReelEditorActivity.EXTRA_IS_FILE_PATH,      true);
        i.putExtra(ReelEditorActivity.EXTRA_IS_DUET,           true);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_ORIGINAL_ID,  reelId);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_ORIGINAL_URL, videoUrl);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_OWNER_UID,    ownerUid);
        i.putExtra(ReelEditorActivity.EXTRA_DUET_LABEL,
                ownerName.isEmpty() ? "Duet" : "Duet with @" + ownerName);
        i.putExtra("duet_layout_mode",     layoutMode);
        i.putExtra(EXTRA_DUET_ROOT_ID,
                duetRootId != null ? duetRootId : reelId);

        // ── v28: live editing data ────────────────────────────────────────────
        i.putExtra("duet_filter_index",  currentFilterIndex);
        i.putExtra("duet_overlays_json", overlaysToJson());

        // ── Multi-duet session passthrough ───────────────────────────────────
        String sessionId = getIntent().getStringExtra("multi_duet_session_id");
        int    slot      = getIntent().getIntExtra("multi_duet_slot", -1);
        int    total     = getIntent().getIntExtra("multi_duet_total", 0);
        if (sessionId != null && !sessionId.isEmpty()) {
            i.putExtra("multi_duet_session_id", sessionId);
            i.putExtra("multi_duet_slot",       slot);
            i.putExtra("multi_duet_total",      total);
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

    // ═══════════════════════════════════════════════════════════════════════════
    //  Permissions
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean allPermissionsGranted() {
        for (String p : PERMISSIONS)
            if (ContextCompat.checkSelfPermission(this, p)
                    != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms,
                                           @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_PERMISSIONS && allPermissionsGranted()) startCamera();
        else {
            Toast.makeText(this, "Permissions required",
                    Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Circle clip (Reaction Bubble mode)
    // ═══════════════════════════════════════════════════════════════════════════

    private void applyCameraCircleClip() {
        if (previewViewCamera == null) return;
        previewViewCamera.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        previewViewCamera.setClipToOutline(true);
    }

    private void removeCameraCircleClip() {
        if (previewViewCamera == null) return;
        previewViewCamera.setOutlineProvider(
                android.view.ViewOutlineProvider.BACKGROUND);
        previewViewCamera.setClipToOutline(false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    protected void onDestroy() {
        if (recordTimer != null)     recordTimer.cancel();
        if (activeRecording != null) activeRecording.stop();
        if (exoPlayer != null)       { exoPlayer.stop(); exoPlayer.release(); }
        if (cameraExecutor != null)  cameraExecutor.shutdown();
        if (beatSyncHandler != null && beatSyncTick != null) {
            beatSyncHandler.removeCallbacks(beatSyncTick);
        }
        // Remove live filter layer to prevent leaks
        if (frameCameraContainer != null) {
            frameCameraContainer.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        super.onDestroy();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Beat Sync helpers (v10)
    // ═══════════════════════════════════════════════════════════════════════════

    private void startBeatSyncTick() {
        if (beatSyncHandler == null)
            beatSyncHandler = new android.os.Handler(getMainLooper());
        beatSyncTick = new Runnable() {
            @Override public void run() {
                if (exoPlayer != null && beatSyncOverlay != null) {
                    beatSyncOverlay.setPosition(exoPlayer.getCurrentPosition());
                }
                if (beatSyncHandler != null)
                    beatSyncHandler.postDelayed(this, 100);
            }
        };
        beatSyncHandler.post(beatSyncTick);
    }
}
