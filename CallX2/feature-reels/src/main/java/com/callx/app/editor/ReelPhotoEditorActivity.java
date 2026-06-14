package com.callx.app.editor;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.bumptech.glide.Glide;
import com.callx.app.feed.ReelPhotoSlideshowAdapter;
import com.callx.app.reels.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ReelPhotoEditorActivity ── Per-Photo Editor v5
 * ══════════════════════════════════════════════════════════════════
 *
 * Full-screen editor for a single photo in a photo_slideshow reel.
 * Launched from ReelUploadActivity when user taps a thumbnail to edit it.
 *
 * ✅ Features:
 *   • Live colour filter preview (16 filters via ReelPhotoSlideshowAdapter)
 *   • Visual effect overlay (vignette / grain / glitch / neon_glow / chrome / etc.)
 *   • Per-photo caption: multi-line text, font style (bold/italic), colour, size
 *   • Sticker/emoji picker row: adds emoji as draggable overlays
 *   • Rotation in 90° steps (CW)
 *   • Brightness / contrast / saturation sliders (baked into saved result)
 *   • Ken Burns direction selector (tl_br / tr_bl / center_out / bottom_up / random)
 *   • Duration slider (1s – 15s per photo)
 *   • "Apply to all" checkbox: pushes filter/effect choice to all photos
 *   • Returns updated per-photo metadata as Intent extras on RESULT_OK
 *
 * Extras IN  (required):
 *   EXTRA_PHOTO_URI    String  – local file URI for the photo to edit
 *   EXTRA_PHOTO_INDEX  int     – index of this photo in the slideshow (for display)
 *   EXTRA_PHOTO_COUNT  int     – total number of photos (for display)
 *
 * Extras IN  (optional, pre-fill previous settings):
 *   EXTRA_FILTER       String  – e.g. "warm"
 *   EXTRA_EFFECT       String  – e.g. "vignette"
 *   EXTRA_CAPTION      String  – per-photo caption text
 *   EXTRA_CAPTION_STYLE String – caption style JSON
 *   EXTRA_STICKERS     String  – sticker JSON array
 *   EXTRA_KB_DIRECTION String  – Ken Burns direction
 *   EXTRA_DURATION_MS  int     – per-photo duration override
 *   EXTRA_ROTATION     float   – current rotation (0 / 90 / 180 / 270)
 *
 * Extras OUT (on RESULT_OK):
 *   EXTRA_FILTER, EXTRA_EFFECT, EXTRA_CAPTION, EXTRA_CAPTION_STYLE,
 *   EXTRA_STICKERS, EXTRA_KB_DIRECTION, EXTRA_DURATION_MS,
 *   EXTRA_ROTATION, EXTRA_APPLY_ALL
 */
public class ReelPhotoEditorActivity extends AppCompatActivity {

    // ── Intent extras ─────────────────────────────────────────────────────────

    public static final String EXTRA_PHOTO_URI      = "photo_editor_uri";
    public static final String EXTRA_PHOTO_INDEX    = "photo_editor_index";
    public static final String EXTRA_PHOTO_COUNT    = "photo_editor_count";
    public static final String EXTRA_FILTER         = "photo_editor_filter";
    public static final String EXTRA_EFFECT         = "photo_editor_effect";
    public static final String EXTRA_CAPTION        = "photo_editor_caption";
    public static final String EXTRA_CAPTION_STYLE  = "photo_editor_caption_style";
    public static final String EXTRA_STICKERS       = "photo_editor_stickers";
    public static final String EXTRA_KB_DIRECTION   = "photo_editor_kb_dir";
    public static final String EXTRA_DURATION_MS    = "photo_editor_duration_ms";
    public static final String EXTRA_ROTATION       = "photo_editor_rotation";
    public static final String EXTRA_APPLY_ALL      = "photo_editor_apply_all";

    /**
     * Convenience launcher — builds the Intent and calls startActivityForResult.
     * Pass "" or null for any optional metadata field to use defaults.
     */
    public static void start(android.app.Activity caller,
                             String photoUriStr, int index, int total,
                             String filter, String effect,
                             String caption, String captionStyle,
                             String stickers, String kbDir,
                             int durationMs, float rotation,
                             int requestCode) {
        android.content.Intent i = new android.content.Intent(caller, ReelPhotoEditorActivity.class);
        i.putExtra(EXTRA_PHOTO_URI,      photoUriStr);
        i.putExtra(EXTRA_PHOTO_INDEX,    index);
        i.putExtra(EXTRA_PHOTO_COUNT,    total);
        if (filter      != null && !filter.isEmpty())      i.putExtra(EXTRA_FILTER,        filter);
        if (effect      != null && !effect.isEmpty())      i.putExtra(EXTRA_EFFECT,        effect);
        if (caption     != null && !caption.isEmpty())     i.putExtra(EXTRA_CAPTION,       caption);
        if (captionStyle!= null && !captionStyle.isEmpty())i.putExtra(EXTRA_CAPTION_STYLE, captionStyle);
        if (stickers    != null && !stickers.isEmpty())    i.putExtra(EXTRA_STICKERS,      stickers);
        if (kbDir       != null && !kbDir.isEmpty())       i.putExtra(EXTRA_KB_DIRECTION,  kbDir);
        if (durationMs  > 0)                               i.putExtra(EXTRA_DURATION_MS,   durationMs);
        if (rotation    != 0f)                             i.putExtra(EXTRA_ROTATION,      rotation);
        caller.startActivityForResult(i, requestCode);
    }

    // ── Filter / effect options ────────────────────────────────────────────────

    private static final String[] FILTERS = {
        "normal","warm","cool","vivid","bw","golden_hour","rose","sunset",
        "neon_pop","matrix","dream","chrome","matte","vintage","fade_film","noir"
    };

    private static final String[] FILTER_LABELS = {
        "Normal","Warm","Cool","Vivid","B&W","Golden","Rose","Sunset",
        "Neon","Matrix","Dream","Chrome","Matte","Vintage","Fade","Noir"
    };

    private static final String[] EFFECTS = {
        "none","vignette","grain","glitch_overlay","neon_glow","matte_overlay",
        "chrome_leak","bokeh","scanlines","dust","double_exposure"
    };

    private static final String[] EFFECT_LABELS = {
        "None","Vignette","Grain","Glitch","Neon","Matte",
        "Lens","Bokeh","Scan","Dust","Double"
    };

    private static final String[] KB_DIRS   = {"random","tl_br","tr_bl","center_out","bottom_up","br_tl"};
    private static final String[] KB_LABELS = {"Auto","↗","↙","⊙","↑","↖"};

    private static final String[] EMOJIS = {
        "🔥","❤️","😍","✨","😂","🎉","💯","🙌","😊","💜","🎵","🌙",
        "☀️","🌊","🌸","🦋","⚡","🍀","💎","🌈","🤩","😎","💫","🎸"
    };

    // ── Views ─────────────────────────────────────────────────────────────────

    private ImageView   ivPreview;
    private View        vEffectOverlay;
    private View        vColorFilterOverlay;
    private FrameLayout flStickerLayer;
    private TextView    tvPhotoIndexLabel;
    private TextView    tvCaption;

    // Tool tabs
    private View tabFilters, tabEffects, tabCaption, tabStickers, tabAdjust;
    private View panelFilters, panelEffects, panelCaption, panelStickers, panelAdjust;

    // Filter row
    private HorizontalScrollView scrollFilters;
    private LinearLayout llFilterChips;

    // Effect row
    private HorizontalScrollView scrollEffects;
    private LinearLayout llEffectChips;

    // Caption panel
    private EditText etCaption;
    private ToggleButton toggleBold, toggleItalic;
    private SeekBar sbCaptionSize;
    private LinearLayout llCaptionColorPicker;

    // Sticker panel
    private HorizontalScrollView scrollStickers;
    private LinearLayout llEmojiRow;

    // Adjust panel
    private SeekBar sbBrightness, sbContrast, sbSaturation;
    private TextView tvBrightnessVal, tvContrastVal, tvSaturationVal;

    // Ken Burns & Duration
    private RadioGroup rgKenBurns;
    private SeekBar  sbDuration;
    private TextView tvDurationLabel;

    // Bottom bar
    private TextView btnRotate, btnBack, btnDone;
    private CheckBox cbApplyAll;

    // ── State ─────────────────────────────────────────────────────────────────

    private String  photoUri;
    private int     photoIndex, photoCount;
    private String  selectedFilter    = "normal";
    private String  selectedEffect    = "none";
    private String  captionText       = "";
    private String  captionStyleJson  = "";
    private String  stickerJson       = "[]";
    private String  kbDirection       = "random";
    private int     durationMs        = 3000;
    private float   rotation          = 0f;
    private boolean applyAll          = false;

    // Caption style
    private boolean captionBold       = false;
    private boolean captionItalic     = false;
    private float   captionSizeSp     = 13f;
    private int     captionColor      = Color.WHITE;
    private int     captionBgColor    = 0xBB000000;

    // Adjustments
    private float   brightness        = 0f;   // -1f to +1f
    private float   contrast          = 1f;   // 0.5f to 2f
    private float   saturation        = 1f;   // 0f to 3f

    private final List<View> stickerViews = new ArrayList<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_photo_editor);

        photoUri    = getIntent().getStringExtra(EXTRA_PHOTO_URI);
        photoIndex  = getIntent().getIntExtra(EXTRA_PHOTO_INDEX, 0);
        photoCount  = getIntent().getIntExtra(EXTRA_PHOTO_COUNT, 1);

        // Pre-fill
        selectedFilter   = nvl(getIntent().getStringExtra(EXTRA_FILTER),   "normal");
        selectedEffect   = nvl(getIntent().getStringExtra(EXTRA_EFFECT),   "none");
        captionText      = nvl(getIntent().getStringExtra(EXTRA_CAPTION),  "");
        captionStyleJson = nvl(getIntent().getStringExtra(EXTRA_CAPTION_STYLE), "");
        stickerJson      = nvl(getIntent().getStringExtra(EXTRA_STICKERS), "[]");
        kbDirection      = nvl(getIntent().getStringExtra(EXTRA_KB_DIRECTION), "random");
        durationMs       = getIntent().getIntExtra(EXTRA_DURATION_MS, 3000);
        rotation         = getIntent().getFloatExtra(EXTRA_ROTATION, 0f);

        bindViews();
        loadPreviewImage();
        applyCurrentState();
        populateFilterChips();
        populateEffectChips();
        populateEmojiRow();
        populateKbButtons();
        setupCaptionPanel();
        setupAdjustPanel();
        setupDurationSlider();
        setupListeners();
        showPanel(panelFilters, tabFilters);
        updatePhotoLabel();
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews() {
        ivPreview           = findViewById(R.id.iv_photo_editor_preview);
        vEffectOverlay      = findViewById(R.id.v_editor_effect_overlay);
        vColorFilterOverlay = findViewById(R.id.v_editor_color_overlay);
        flStickerLayer      = findViewById(R.id.fl_editor_sticker_layer);
        tvPhotoIndexLabel   = findViewById(R.id.tv_photo_editor_index);
        tvCaption           = findViewById(R.id.tv_photo_editor_caption);

        tabFilters  = findViewById(R.id.tab_filters);
        tabEffects  = findViewById(R.id.tab_effects);
        tabCaption  = findViewById(R.id.tab_caption);
        tabStickers = findViewById(R.id.tab_stickers);
        tabAdjust   = findViewById(R.id.tab_adjust);

        panelFilters  = findViewById(R.id.panel_filters);
        panelEffects  = findViewById(R.id.panel_effects);
        panelCaption  = findViewById(R.id.panel_caption);
        panelStickers = findViewById(R.id.panel_stickers);
        panelAdjust   = findViewById(R.id.panel_adjust);

        llFilterChips = findViewById(R.id.ll_filter_chips);
        llEffectChips = findViewById(R.id.ll_effect_chips);

        etCaption        = findViewById(R.id.et_caption);
        toggleBold       = findViewById(R.id.toggle_bold);
        toggleItalic     = findViewById(R.id.toggle_italic);
        sbCaptionSize    = findViewById(R.id.sb_caption_size);
        llCaptionColorPicker = findViewById(R.id.ll_caption_color_picker);

        llEmojiRow       = findViewById(R.id.ll_emoji_row);

        sbBrightness     = findViewById(R.id.sb_brightness);
        sbContrast       = findViewById(R.id.sb_contrast);
        sbSaturation     = findViewById(R.id.sb_saturation);
        tvBrightnessVal  = findViewById(R.id.tv_brightness_val);
        tvContrastVal    = findViewById(R.id.tv_contrast_val);
        tvSaturationVal  = findViewById(R.id.tv_saturation_val);

        sbDuration       = findViewById(R.id.sb_duration);
        tvDurationLabel  = findViewById(R.id.tv_duration_label);

        btnRotate = findViewById(R.id.btn_editor_rotate);
        btnBack   = findViewById(R.id.btn_editor_back);
        btnDone   = findViewById(R.id.btn_editor_done);
        cbApplyAll = findViewById(R.id.cb_apply_all);
    }

    // ── Load preview ──────────────────────────────────────────────────────────

    private void loadPreviewImage() {
        if (photoUri == null || photoUri.isEmpty() || ivPreview == null) return;
        Glide.with(this).load(photoUri).centerCrop().into(ivPreview);
        if (rotation != 0f) ivPreview.setRotation(rotation);
    }

    // ── Filter chips ─────────────────────────────────────────────────────────

    private void populateFilterChips() {
        if (llFilterChips == null) return;
        llFilterChips.removeAllViews();
        for (int i = 0; i < FILTERS.length; i++) {
            final String filter = FILTERS[i];
            TextView chip = makeChip(FILTER_LABELS[i], filter.equals(selectedFilter));
            chip.setOnClickListener(v -> {
                selectedFilter = filter;
                applyFilterPreview();
                refreshFilterChipSelection();
            });
            llFilterChips.addView(chip);
        }
    }

    private void refreshFilterChipSelection() {
        if (llFilterChips == null) return;
        for (int i = 0; i < llFilterChips.getChildCount(); i++) {
            View chip = llFilterChips.getChildAt(i);
            if (chip instanceof TextView) {
                boolean selected = FILTERS[i].equals(selectedFilter);
                chip.setBackgroundColor(selected ? 0xFFA855F7 : 0x44FFFFFF);
            }
        }
    }

    private void applyFilterPreview() {
        if (ivPreview == null) return;
        ColorMatrixColorFilter cmcf = ReelPhotoSlideshowAdapter.buildColorFilter(selectedFilter);
        ivPreview.setColorFilter(cmcf);

        Integer tint = buildTint(selectedFilter);
        if (tint != null) {
            vColorFilterOverlay.setBackgroundColor(tint);
            vColorFilterOverlay.setVisibility(View.VISIBLE);
        } else {
            vColorFilterOverlay.setVisibility(View.GONE);
        }
    }

    // ── Effect chips ──────────────────────────────────────────────────────────

    private void populateEffectChips() {
        if (llEffectChips == null) return;
        llEffectChips.removeAllViews();
        for (int i = 0; i < EFFECTS.length; i++) {
            final String effect = EFFECTS[i];
            TextView chip = makeChip(EFFECT_LABELS[i], effect.equals(selectedEffect));
            chip.setOnClickListener(v -> {
                selectedEffect = effect;
                applyEffectPreview();
                refreshEffectChipSelection();
            });
            llEffectChips.addView(chip);
        }
    }

    private void refreshEffectChipSelection() {
        if (llEffectChips == null) return;
        for (int i = 0; i < llEffectChips.getChildCount(); i++) {
            View chip = llEffectChips.getChildAt(i);
            if (chip instanceof TextView) {
                boolean selected = EFFECTS[i].equals(selectedEffect);
                chip.setBackgroundColor(selected ? 0xFFFF416C : 0x44FFFFFF);
            }
        }
    }

    private void applyEffectPreview() {
        if (vEffectOverlay == null) return;
        if ("none".equals(selectedEffect) || selectedEffect == null) {
            vEffectOverlay.setVisibility(View.GONE);
            return;
        }
        vEffectOverlay.setVisibility(View.VISIBLE);
        switch (selectedEffect) {
            case "vignette":        vEffectOverlay.setBackgroundColor(0x55000000); break;
            case "grain":           vEffectOverlay.setBackgroundColor(0x1AFFFFFF); break;
            case "glitch_overlay":  vEffectOverlay.setBackgroundColor(0x22FF0044); break;
            case "neon_glow":       vEffectOverlay.setBackgroundColor(0x22FF00FF); break;
            case "matte_overlay":   vEffectOverlay.setBackgroundColor(0x33FFFFFF); break;
            case "chrome_leak":     vEffectOverlay.setBackgroundColor(0x22FFFACD); break;
            case "bokeh":           vEffectOverlay.setBackgroundColor(0x15000000); break;
            case "scanlines":       vEffectOverlay.setBackgroundColor(0x18000000); break;
            case "dust":            vEffectOverlay.setBackgroundColor(0x14FFFFCC); break;
            case "double_exposure": vEffectOverlay.setBackgroundColor(0x30FFFFFF); break;
        }
    }

    // ── Caption panel ─────────────────────────────────────────────────────────

    private void setupCaptionPanel() {
        if (etCaption == null) return;
        etCaption.setText(captionText);
        updateCaptionPreview();

        etCaption.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int co, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int co) {}
            @Override public void afterTextChanged(Editable s) {
                captionText = s.toString();
                updateCaptionPreview();
            }
        });

        if (toggleBold != null) toggleBold.setOnCheckedChangeListener((v, checked) -> {
            captionBold = checked;
            updateCaptionPreview();
        });
        if (toggleItalic != null) toggleItalic.setOnCheckedChangeListener((v, checked) -> {
            captionItalic = checked;
            updateCaptionPreview();
        });
        if (sbCaptionSize != null) sbCaptionSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                captionSizeSp = 10f + p * 0.3f;
                updateCaptionPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        buildCaptionColorSwatches();
    }

    private void buildCaptionColorSwatches() {
        if (llCaptionColorPicker == null) return;
        int[] colors = {Color.WHITE, Color.BLACK, Color.YELLOW, 0xFFFF416C,
                        0xFFA855F7, 0xFF00E5FF, 0xFF00FF88, 0xFFFF8800};
        for (int c : colors) {
            View swatch = new View(this);
            int size = (int)(36 * getResources().getDisplayMetrics().density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd((int)(4 * getResources().getDisplayMetrics().density));
            swatch.setLayoutParams(lp);
            swatch.setBackgroundColor(c);
            final int finalC = c;
            swatch.setOnClickListener(v -> {
                captionColor = finalC;
                updateCaptionPreview();
            });
            llCaptionColorPicker.addView(swatch);
        }
    }

    private void updateCaptionPreview() {
        if (tvCaption == null) return;
        if (captionText.isEmpty()) {
            tvCaption.setVisibility(View.GONE);
            return;
        }
        tvCaption.setText(captionText);
        tvCaption.setTextColor(captionColor);
        tvCaption.setTextSize(captionSizeSp);
        tvCaption.setBackgroundColor(captionBgColor);
        tvCaption.setVisibility(View.VISIBLE);
        int style = captionBold && captionItalic ? android.graphics.Typeface.BOLD_ITALIC
                  : captionBold  ? android.graphics.Typeface.BOLD
                  : captionItalic ? android.graphics.Typeface.ITALIC
                  : android.graphics.Typeface.NORMAL;
        tvCaption.setTypeface(android.graphics.Typeface.SANS_SERIF, style);
        buildCaptionStyleJson();
    }

    private void buildCaptionStyleJson() {
        captionStyleJson = String.format(
            "{\"color\":\"%s\",\"bg\":\"%s\",\"size\":%.1f,\"bold\":%s,\"italic\":%s}",
            colorToHex(captionColor), colorToHex(captionBgColor),
            captionSizeSp, captionBold, captionItalic);
    }

    // ── Emoji sticker row ─────────────────────────────────────────────────────

    private void populateEmojiRow() {
        if (llEmojiRow == null) return;
        for (String emoji : EMOJIS) {
            TextView tv = new TextView(this);
            tv.setText(emoji);
            tv.setTextSize(28f);
            tv.setPadding(8, 4, 8, 4);
            tv.setGravity(Gravity.CENTER);
            tv.setClickable(true);
            tv.setFocusable(true);
            tv.setOnClickListener(v -> addEmojiSticker(emoji));
            llEmojiRow.addView(tv);
        }
    }

    private void addEmojiSticker(String emoji) {
        if (flStickerLayer == null) return;
        TextView tv = new TextView(this);
        tv.setText(emoji);
        tv.setTextSize(32f);
        tv.setPadding(4, 2, 4, 2);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tv.setLayoutParams(lp);
        flStickerLayer.post(() -> {
            tv.setX(flStickerLayer.getWidth() / 2f - tv.getWidth() / 2f);
            tv.setY(flStickerLayer.getHeight() / 2f - tv.getHeight() / 2f);
        });
        makeDraggable(tv);
        makePinchRotate(tv);
        flStickerLayer.addView(tv);
        stickerViews.add(tv);
        // Double-tap to remove
        tv.setOnLongClickListener(v -> {
            flStickerLayer.removeView(tv);
            stickerViews.remove(tv);
            rebuildStickerJson();
            return true;
        });
    }

    private void makeDraggable(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY;
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = view.getX() - e.getRawX();
                        dY = view.getY() - e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float nx = e.getRawX() + dX;
                        float ny = e.getRawY() + dY;
                        if (flStickerLayer != null) {
                            nx = Math.max(0, Math.min(nx, flStickerLayer.getWidth() - view.getWidth()));
                            ny = Math.max(0, Math.min(ny, flStickerLayer.getHeight() - view.getHeight()));
                        }
                        view.setX(nx); view.setY(ny);
                        rebuildStickerJson();
                        return true;
                }
                return false;
            }
        });
    }

    private void makePinchRotate(View v) {
        android.view.ScaleGestureDetector scaleGD = new android.view.ScaleGestureDetector(
            this, new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(@NonNull android.view.ScaleGestureDetector d) {
                float newScale = Math.max(0.5f, Math.min(v.getScaleX() * d.getScaleFactor(), 3f));
                v.setScaleX(newScale); v.setScaleY(newScale);
                rebuildStickerJson();
                return true;
            }
        });
        View.OnTouchListener orig = v.getTag() instanceof View.OnTouchListener
            ? (View.OnTouchListener) v.getTag() : null;
        v.setOnTouchListener((view, e) -> {
            scaleGD.onTouchEvent(e);
            if (orig != null) orig.onTouch(view, e);
            return false;
        });
    }

    private void rebuildStickerJson() {
        if (flStickerLayer == null) { stickerJson = "[]"; return; }
        StringBuilder sb = new StringBuilder("[");
        int added = 0;
        for (int i = 0; i < flStickerLayer.getChildCount(); i++) {
            View child = flStickerLayer.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            String val = ((TextView) child).getText().toString();
            if (val.isEmpty()) continue;
            if (flStickerLayer.getWidth() == 0) continue;
            float xFrac = (child.getX() + child.getWidth() / 2f) / flStickerLayer.getWidth();
            float yFrac = (child.getY() + child.getHeight() / 2f) / flStickerLayer.getHeight();
            if (added > 0) sb.append(',');
            sb.append(String.format("{\"type\":\"emoji\",\"value\":\"%s\",\"x\":%.3f,\"y\":%.3f,\"scale\":%.2f,\"rotation\":%.1f}",
                val, xFrac, yFrac, child.getScaleX(), child.getRotation()));
            added++;
        }
        sb.append(']');
        stickerJson = sb.toString();
    }

    // ── Ken Burns direction ───────────────────────────────────────────────────

    private void populateKbButtons() {
        ViewGroup container = findViewById(R.id.ll_kb_direction);
        if (container == null) return;
        container.removeAllViews();
        for (int i = 0; i < KB_DIRS.length; i++) {
            final String dir = KB_DIRS[i];
            TextView btn = new TextView(this);
            btn.setText(KB_LABELS[i]);
            btn.setTextSize(14f);
            btn.setTextColor(Color.WHITE);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(16, 8, 16, 8);
            btn.setBackgroundColor(dir.equals(kbDirection) ? 0xFF00E5FF : 0x44FFFFFF);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginEnd(4);
            btn.setLayoutParams(lp);
            btn.setOnClickListener(v -> {
                kbDirection = dir;
                refreshKbButtonSelection(container);
            });
            container.addView(btn);
        }
    }

    private void refreshKbButtonSelection(ViewGroup container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View v = container.getChildAt(i);
            v.setBackgroundColor(KB_DIRS[i].equals(kbDirection) ? 0xFF00E5FF : 0x44FFFFFF);
        }
    }

    // ── Adjust panel (brightness / contrast / saturation) ─────────────────────

    private void setupAdjustPanel() {
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                int id = sb.getId();
                if (id == R.id.sb_brightness) {
                    brightness = (p - 50) / 50f;  // -1 to +1
                    if (tvBrightnessVal != null) tvBrightnessVal.setText(String.format("%+.1f", brightness));
                } else if (id == R.id.sb_contrast) {
                    contrast = 0.5f + p / 50f;    // 0.5 to 2.5
                    if (tvContrastVal != null) tvContrastVal.setText(String.format("%.2fx", contrast));
                } else if (id == R.id.sb_saturation) {
                    saturation = p / 33.3f;        // 0 to 3
                    if (tvSaturationVal != null) tvSaturationVal.setText(String.format("%.1fx", saturation));
                }
                applyAdjustPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        if (sbBrightness  != null) { sbBrightness.setMax(100); sbBrightness.setProgress(50); sbBrightness.setOnSeekBarChangeListener(listener); }
        if (sbContrast    != null) { sbContrast.setMax(100);   sbContrast.setProgress(25);   sbContrast.setOnSeekBarChangeListener(listener); }
        if (sbSaturation  != null) { sbSaturation.setMax(99);  sbSaturation.setProgress(33); sbSaturation.setOnSeekBarChangeListener(listener); }
    }

    private void applyAdjustPreview() {
        if (ivPreview == null) return;
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(saturation);
        // Brightness (translate)
        float b = brightness * 128f;
        ColorMatrix bright = new ColorMatrix(new float[]{
            1f,0f,0f,0f,b, 0f,1f,0f,0f,b, 0f,0f,1f,0f,b, 0f,0f,0f,1f,0f});
        cm.postConcat(bright);
        // Contrast (scale around 128)
        float c = contrast;
        float t = 128f * (1f - c);
        ColorMatrix cont = new ColorMatrix(new float[]{
            c,0f,0f,0f,t, 0f,c,0f,0f,t, 0f,0f,c,0f,t, 0f,0f,0f,1f,0f});
        cm.postConcat(cont);
        ivPreview.setColorFilter(new ColorMatrixColorFilter(cm));
    }

    // ── Duration slider ───────────────────────────────────────────────────────

    private void setupDurationSlider() {
        if (sbDuration == null) return;
        sbDuration.setMax(140); // 0→140 maps to 1s→15s
        int progress = Math.max(0, Math.min(140, (durationMs / 1000 - 1) * 10));
        sbDuration.setProgress(progress);
        updateDurationLabel();
        sbDuration.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                durationMs = (1 + p / 10) * 1000;
                updateDurationLabel();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void updateDurationLabel() {
        if (tvDurationLabel != null) {
            tvDurationLabel.setText(String.format("%.1fs per photo", durationMs / 1000f));
        }
    }

    // ── Tab navigation ────────────────────────────────────────────────────────

    private void showPanel(View panel, View tab) {
        View[] panels = {panelFilters, panelEffects, panelCaption, panelStickers, panelAdjust};
        View[] tabs   = {tabFilters,   tabEffects,   tabCaption,   tabStickers,   tabAdjust};
        for (int i = 0; i < panels.length; i++) {
            if (panels[i] != null) panels[i].setVisibility(panels[i] == panel ? View.VISIBLE : View.GONE);
            if (tabs[i]   != null) tabs[i].setAlpha(tabs[i] == tab ? 1f : 0.5f);
        }
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private void setupListeners() {
        if (tabFilters  != null) tabFilters.setOnClickListener(v -> showPanel(panelFilters,  tabFilters));
        if (tabEffects  != null) tabEffects.setOnClickListener(v -> showPanel(panelEffects,  tabEffects));
        if (tabCaption  != null) tabCaption.setOnClickListener(v -> showPanel(panelCaption,  tabCaption));
        if (tabStickers != null) tabStickers.setOnClickListener(v -> showPanel(panelStickers, tabStickers));
        if (tabAdjust   != null) tabAdjust.setOnClickListener(v -> showPanel(panelAdjust,    tabAdjust));

        if (btnRotate != null) btnRotate.setOnClickListener(v -> {
            rotation = (rotation + 90f) % 360f;
            if (ivPreview != null) ivPreview.animate().rotation(rotation).setDuration(200).start();
        });

        if (btnBack != null) btnBack.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        if (btnDone != null) btnDone.setOnClickListener(v -> {
            rebuildStickerJson();
            buildCaptionStyleJson();
            applyAll = cbApplyAll != null && cbApplyAll.isChecked();
            Intent result = new Intent();
            result.putExtra(EXTRA_FILTER,        selectedFilter);
            result.putExtra(EXTRA_EFFECT,        selectedEffect);
            result.putExtra(EXTRA_CAPTION,       captionText);
            result.putExtra(EXTRA_CAPTION_STYLE, captionStyleJson);
            result.putExtra(EXTRA_STICKERS,      stickerJson);
            result.putExtra(EXTRA_KB_DIRECTION,  kbDirection);
            result.putExtra(EXTRA_DURATION_MS,   durationMs);
            result.putExtra(EXTRA_ROTATION,      rotation);
            result.putExtra(EXTRA_APPLY_ALL,     applyAll);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    // ── Initial state apply ───────────────────────────────────────────────────

    private void applyCurrentState() {
        applyFilterPreview();
        applyEffectPreview();
        updateCaptionPreview();
    }

    private void updatePhotoLabel() {
        if (tvPhotoIndexLabel != null) {
            tvPhotoIndexLabel.setText("Photo " + (photoIndex + 1) + " of " + photoCount);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TextView makeChip(String label, boolean selected) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setPadding(24, 10, 24, 10);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(selected ? 0xFFA855F7 : 0x44FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd((int)(4 * getResources().getDisplayMetrics().density));
        tv.setLayoutParams(lp);
        return tv;
    }

    private static String nvl(String s, String def) {
        return (s != null && !s.isEmpty()) ? s : def;
    }

    private static String colorToHex(int color) {
        return String.format("#%08X", color);
    }

    @Nullable
    private static Integer buildTint(String filter) {
        if (filter == null) return null;
        switch (filter) {
            case "golden_hour": return 0x18FF9900;
            case "rose":        return 0x12FF6688;
            case "sunset":      return 0x1AFF4400;
            case "neon_pop":    return 0x10FF00FF;
            case "matrix":      return 0x1500FF44;
            case "dream":       return 0x14AAAAFF;
            default:            return null;
        }
    }
}
