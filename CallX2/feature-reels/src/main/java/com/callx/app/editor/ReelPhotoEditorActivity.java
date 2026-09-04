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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.callx.app.media.crop.MediaCropActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.feed.ReelBlurTransformation;
import com.callx.app.feed.ReelPhotoSlideshowAdapter;
import com.callx.app.reels.R;
import com.callx.app.stickers.StatusStickerPickerSheet;
import com.callx.app.stickers.StatusStickerOverlayView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ReelPhotoEditorActivity ── Per-Photo Editor v6
 * ══════════════════════════════════════════════════════════════════
 *
 * Full-screen editor for a single photo in a photo_slideshow reel.
 * Launched from ReelUploadActivity when user taps a thumbnail to edit it.
 *
 * ✅ Features:
 *   • Live colour filter preview (16 filters via ReelPhotoSlideshowAdapter)
 *   • Visual effect overlay (vignette / grain / glitch / neon_glow / chrome / etc.)
 *   • Per-photo caption: multi-line text, font style (bold/italic), colour, size
 *   • Full sticker sheet (music, poll, quiz, countdown, mention, hashtag, link) via StatusStickerPickerSheet
 *   • Quick emoji overlay row for fast emoji additions
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
 *   EXTRA_ROTATION, EXTRA_APPLY_ALL, EXTRA_PHOTO_URI (updated when cropped)
 *
 * Crop tool (✂️ / ⛶ button, top bar):
 *   Reuses :core's shared MediaCropActivity (same one feature-chat uses) via
 *   a simple className-based Intent — no feature-reels ↔ feature-chat coupling.
 *   On crop RESULT_OK the working photoUri is replaced with the cropped file
 *   and the preview reloads; the new uri is sent back in EXTRA_PHOTO_URI so
 *   ReelUploadActivity can swap it into selectedPhotoUris.
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

    /** Set true when this screen is reused for Add Status's "Advance Editing"
     *  entry point (Create step, photo selected) instead of the Reel Upload
     *  slideshow editor — swaps the "Photo X of Y" header label for
     *  "Edit Status", same convention ReelEditorActivity uses for video. */
    public static final String EXTRA_TARGET_STATUS = "target_status";
    /** Set true alongside EXTRA_TARGET_STATUS when opened directly on an
     *  already-picked status photo (Add Status's Advance Editing button).
     *  Both Back and Done then hand the photo off to MediaEditActivity
     *  ("media editing screen") instead of finishing this screen directly —
     *  see goBackOrToMediaEdit() — so Add Status always sees the same
     *  MediaEditActivity result shape the video (ReelEditorActivity) path
     *  already returns, and lands on its Edit step either way. */
    public static final String EXTRA_ALLOW_MEDIA_EDIT_FALLBACK = "allow_media_edit_fallback";

    // ── Pre-selected sound from the Reels camera screen ─────────────────────
    // When the user picks a track on the camera screen and then takes a photo,
    // ReelUploadActivity forwards these so the editing screen can auto-attach
    // the chosen track as a Music sticker — same experience as the video flow.
    public static final String EXTRA_PRESET_SOUND_ID     = "photo_preset_sound_id";
    public static final String EXTRA_PRESET_SOUND_TITLE  = "photo_preset_sound_title";
    public static final String EXTRA_PRESET_SOUND_ARTIST = "photo_preset_sound_artist";
    public static final String EXTRA_PRESET_SOUND_URL    = "photo_preset_sound_url";
    public static final String EXTRA_PRESET_SOUND_COVER  = "photo_preset_sound_cover";

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
        // Grant read permission for content:// URIs coming from the photo picker
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
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

    /**
     * Extended launcher — same as {@link #start} but also bundles a pre-selected
     * sound so the editor can auto-attach a Music sticker on open. Called by
     * ReelUploadActivity when the user arrives via the camera photo flow and had
     * already picked a track on the camera screen.
     */
    public static void startWithSound(android.app.Activity caller,
                                      String photoUriStr, int index, int total,
                                      String filter, String effect,
                                      String caption, String captionStyle,
                                      String stickers, String kbDir,
                                      int durationMs, float rotation,
                                      String soundId, String soundTitle,
                                      String soundArtist, String soundUrl,
                                      String soundCover,
                                      int requestCode) {
        android.content.Intent i = new android.content.Intent(caller, ReelPhotoEditorActivity.class);
        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra(EXTRA_PHOTO_URI,   photoUriStr);
        i.putExtra(EXTRA_PHOTO_INDEX, index);
        i.putExtra(EXTRA_PHOTO_COUNT, total);
        if (filter       != null && !filter.isEmpty())       i.putExtra(EXTRA_FILTER,        filter);
        if (effect       != null && !effect.isEmpty())       i.putExtra(EXTRA_EFFECT,        effect);
        if (caption      != null && !caption.isEmpty())      i.putExtra(EXTRA_CAPTION,       caption);
        if (captionStyle != null && !captionStyle.isEmpty()) i.putExtra(EXTRA_CAPTION_STYLE, captionStyle);
        if (stickers     != null && !stickers.isEmpty())     i.putExtra(EXTRA_STICKERS,      stickers);
        if (kbDir        != null && !kbDir.isEmpty())        i.putExtra(EXTRA_KB_DIRECTION,  kbDir);
        if (durationMs   > 0)                                i.putExtra(EXTRA_DURATION_MS,   durationMs);
        if (rotation     != 0f)                              i.putExtra(EXTRA_ROTATION,      rotation);
        if (soundId      != null && !soundId.isEmpty())      i.putExtra(EXTRA_PRESET_SOUND_ID,     soundId);
        if (soundTitle   != null && !soundTitle.isEmpty())   i.putExtra(EXTRA_PRESET_SOUND_TITLE,  soundTitle);
        if (soundArtist  != null && !soundArtist.isEmpty())  i.putExtra(EXTRA_PRESET_SOUND_ARTIST, soundArtist);
        if (soundUrl     != null && !soundUrl.isEmpty())     i.putExtra(EXTRA_PRESET_SOUND_URL,    soundUrl);
        if (soundCover   != null && !soundCover.isEmpty())   i.putExtra(EXTRA_PRESET_SOUND_COVER,  soundCover);
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

    /** The whole preview stage (bg blur + photo + overlays + stickers + caption) —
     *  used only as the coordinate frame for baking sticker/caption positions
     *  into the final exported photo, see {@link #captureOverlaySnapshots()}. */
    private ViewGroup    flPreviewStage;
    private ImageView   ivPreview;
    /** Blurred background fill behind the preview — Instagram-style letterbox. */
    private ImageView   ivBgBlur;
    private View        vEffectOverlay;
    private View        vColorFilterOverlay;
    private FrameLayout flStickerLayer;
    private TextView    tvPhotoIndexLabel;
    private TextView    tvCaption;

    // Tool tabs
    // ✅ Old horizontal tab bar (tab_filters/tab_effects/tab_caption/
    // tab_stickers/tab_adjust) is gone from the Instagram-style layout —
    // these fields stay declared (findViewById below now just yields null)
    // purely so the still-present null-guarded references elsewhere in this
    // file remain harmless no-ops; see setupPhotoEditorRail() for the
    // replacement navigation.
    private View tabFilters, tabEffects, tabCaption, tabStickers, tabAdjust;

    // ── Old 5-step wizard chrome (Filter → Effect → Caption → Sticker →
    //    Adjust). The "Step X of Y" pill, dot-stepper and fixed Back/Next
    //    bar are gone from the Instagram-style layout (see
    //    activity_reel_photo_editor.xml) — the right-edge rail
    //    (setupPhotoEditorRail()) is the primary navigation now. These
    //    fields/methods stay in place, fully null-guarded, exactly like
    //    ReelEditorActivity's equivalent wizard fields, so nothing else in
    //    this file needs to change. tv_photo_editor_step_name is the one
    //    view from this group still present — it now shows the current
    //    tool's name inside the bottom panel. ────────────────────────────
    private TextView            tvPhotoEditorStepTitle;
    private TextView            tvPhotoEditorStepName;
    // Dot-stepper (reused from Add Status / Reel Upload / Reel Editor's
    // stepper UI — see updatePhotoEditorStepDots() below) — no longer in
    // the layout; kept null-safe for compatibility.
    private TextView[]          photoEditorStepDots;
    private View[]              photoEditorStepLines;
    private ImageView[]         photoEditorStepRings;
    private ObjectAnimator      photoEditorActiveStepRingSpin;
    private View                btnPhotoEditorStepBack, btnPhotoEditorStepNext;
    private int                 photoEditorCurrentStep = 0;

    /** ✅ NEW (Instagram-style editor): right-edge vertical tool rail —
     *  Filter / Effect / Caption / Sticker / Adjust, same pattern as
     *  ReelEditorActivity#editorRailIcons. See setupPhotoEditorRail() /
     *  updatePhotoEditorRailUi(). */
    private ImageButton[]       photoEditorRailIcons;
    private static final String[] PHOTO_EDITOR_STEP_TITLES = {
            "Step 1 of 5 · Filter",
            "Step 2 of 5 · Effect",
            "Step 3 of 5 · Caption",
            "Step 4 of 5 · Sticker",
            "Step 5 of 5 · Adjust"
    };
    /** Short step name shown in tv_photo_editor_step_name, next to the "Step X of Y"
     *  pill (all-caps via android:textAllCaps, so plain-case names are given here). */
    private static final String[] PHOTO_EDITOR_STEP_NAMES = {
            "Filter",
            "Effect",
            "Caption",
            "Sticker",
            "Adjust"
    };
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
    private TextView btnRotate, btnCrop, btnBack, btnDone;
    private ActivityResultLauncher<Intent> cropLauncher;
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
    /** Tracks full interactive sticker views (StatusStickerOverlayView) added via the sticker sheet. */
    private final List<StatusStickerOverlayView> fullStickerViews = new ArrayList<>();
    private View btnAddFullSticker;

    // ── Pre-selected sound forwarded from the Reels camera ───────────────────
    private String presetSoundId     = "";
    private String presetSoundTitle  = "";
    private String presetSoundArtist = "";
    private String presetSoundUrl    = "";
    private String presetSoundCover  = "";

    // ── Add Status "Advance Editing" support (photo path) ────────────────────
    private boolean targetStatus = false;
    private boolean allowMediaEditFallback = false;
    private ActivityResultLauncher<Intent> mediaEditFallbackLauncher;

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

        // Pre-selected sound from camera screen
        presetSoundId     = nvl(getIntent().getStringExtra(EXTRA_PRESET_SOUND_ID),     "");
        presetSoundTitle  = nvl(getIntent().getStringExtra(EXTRA_PRESET_SOUND_TITLE),  "");
        presetSoundArtist = nvl(getIntent().getStringExtra(EXTRA_PRESET_SOUND_ARTIST), "");
        presetSoundUrl    = nvl(getIntent().getStringExtra(EXTRA_PRESET_SOUND_URL),    "");
        presetSoundCover  = nvl(getIntent().getStringExtra(EXTRA_PRESET_SOUND_COVER),  "");

        targetStatus = getIntent().getBooleanExtra(EXTRA_TARGET_STATUS, false);
        allowMediaEditFallback = getIntent().getBooleanExtra(EXTRA_ALLOW_MEDIA_EDIT_FALLBACK, false);
        registerMediaEditFallbackLauncher();

        registerCropLauncher();
        bindViews();
        setupPhotoEditorStepWizard();
        setupPhotoEditorRail();
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
        goToPhotoEditorStep(0);

        // Auto-attach a Music sticker if the camera had a track pre-selected.
        // Delayed by one frame so flStickerLayer has measured its dimensions.
        if (!presetSoundTitle.isEmpty()) {
            if (flStickerLayer != null) {
                flStickerLayer.post(() -> attachPresetMusicSticker());
            }
        }
        updatePhotoLabel();
    }

    // ── Crop launcher ────────────────────────────────────────────────────────
    // Reuses core's shared MediaCropActivity — same WhatsApp-grade crop screen
    // feature-chat uses — via className Intent so :feature-reels never needs a
    // compile dependency on :feature-chat.

    private void registerCropLauncher() {
        cropLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                String uriStr = result.getData().getStringExtra(MediaCropActivity.RESULT_CROPPED_URI);
                if (uriStr != null) {
                    photoUri = uriStr;
                    loadPreviewImage();
                }
            }
        });
    }

    private void openCropScreen() {
        if (photoUri == null || photoUri.isEmpty()) return;
        Intent i = new Intent();
        i.setClassName(getPackageName(), "com.callx.app.media.crop.MediaCropActivity");
        i.putExtra(MediaCropActivity.EXTRA_IMAGE_URI, photoUri);
        cropLauncher.launch(i);
    }

    // ── Add Status "Advance Editing" media-edit fallback ────────────────────
    // Mirrors ReelEditorActivity's goBackOrToMediaEdit()/handleMediaEditFallbackResult()
    // for the video path, so a picked photo gets the exact same "Reel [Photo]
    // Edit screen → Media Editing screen → back to Add Status" round trip.

    private void registerMediaEditFallbackLauncher() {
        mediaEditFallbackLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                this::handleMediaEditFallbackResult);
    }

    /**
     * Backing out of this screen (X / Done) when allowMediaEditFallback is
     * set — i.e. opened directly on an already-picked status photo via Add
     * Status's Advance Editing button — hands the photo off to
     * MediaEditActivity ("media editing screen") instead of finishing this
     * screen directly, and this screen forwards MediaEditActivity's own
     * result straight through as its own (handleMediaEditFallbackResult()),
     * so Add Status sees exactly the same result shape the video path
     * (ReelEditorActivity) already returns.
     */
    private void goBackOrToMediaEdit() {
        // ✅ FIX: this used to hand the ORIGINAL (or only-cropped) photoUri
        // straight to MediaEditActivity, silently dropping every edit made on
        // this screen — filter, effect, caption, stickers, brightness/
        // contrast/saturation, rotation. That's why the chat "photo send"
        // flow (which reuses this exact screen via allow_media_edit_fallback)
        // looked like none of the Reel Photo Editor's features worked: they
        // were applied to the on-screen preview only and thrown away on
        // Back/Done. Now, when there's something to bake, we render those
        // edits into a real new photo file first (renderBakedPhoto()) and
        // forward THAT file's uri instead — same "only the uri travels
        // onward" contract MediaEditActivity already expects.
        if (allowMediaEditFallback && photoUri != null && !photoUri.isEmpty() && hasBakableEdits()) {
            bakeEditsThenGoToMediaEdit();
            return;
        }
        goBackOrToMediaEditRaw();
    }

    /** Original hand-off, now used once photoUri already reflects any baked edits
     *  (or there was nothing to bake in the first place). */
    private void goBackOrToMediaEditRaw() {
        if (allowMediaEditFallback && photoUri != null && !photoUri.isEmpty()) {
            Intent intent = new Intent();
            intent.setClassName(getPackageName(), "com.callx.app.conversation.controllers.MediaEditActivity");
            java.util.ArrayList<String> uriStrings = new java.util.ArrayList<>();
            uriStrings.add(photoUri);
            java.util.ArrayList<Integer> videoFlags = new java.util.ArrayList<>();
            videoFlags.add(0); // always a photo on this fallback path
            intent.putStringArrayListExtra("media_edit_uris", uriStrings);
            intent.putIntegerArrayListExtra("media_edit_is_video", videoFlags);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) != null) {
                mediaEditFallbackLauncher.launch(intent);
                return;
            }
            // feature-chat not present on this build — fall through to plain cancel.
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    /** True if anything was actually changed on this screen — skips a needless
     *  re-encode (and its small quality/EXIF cost) when the user just cropped
     *  or backed straight out without touching filters/effects/caption/stickers/adjust. */
    private boolean hasBakableEdits() {
        if (!"normal".equals(selectedFilter))                     return true;
        if (!"none".equals(selectedEffect))                       return true;
        if (rotation != 0f)                                       return true;
        if (brightness != 0f || contrast != 1f || saturation != 1f) return true;
        if (captionText != null && !captionText.trim().isEmpty()) return true;
        if (flStickerLayer != null && flStickerLayer.getChildCount() > 0) return true;
        return false;
    }

    /**
     * Renders the current filter/effect/adjust/rotation/caption/stickers onto
     * the full-resolution photo and swaps photoUri to the result before
     * continuing to MediaEditActivity — see goBackOrToMediaEdit().
     *
     * View snapshots (caption + stickers) are captured on the UI thread first
     * (View#draw requires it); the actual decode/compose/encode of the
     * full-size photo runs on a background thread so Back/Done never jank or
     * ANR on a large image.
     */
    private void bakeEditsThenGoToMediaEdit() {
        final List<OverlaySnap> overlays = captureOverlaySnapshots();
        final float stageW = flPreviewStage != null ? flPreviewStage.getWidth()  : 0;
        final float stageH = flPreviewStage != null ? flPreviewStage.getHeight() : 0;
        final String srcUri = photoUri;
        final String filter = selectedFilter;
        final String effect = selectedEffect;
        final float rotDeg  = rotation;
        final float br = brightness, ct = contrast, sat = saturation;

        new Thread(() -> {
            File out = null;
            try {
                out = renderBakedPhoto(srcUri, filter, effect, rotDeg, br, ct, sat, overlays, stageW, stageH);
            } catch (Exception e) {
                out = null; // fall back to the un-baked photo below rather than losing the send entirely
            }
            final Uri resultUri = out != null
                    ? FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out)
                    : null;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (resultUri != null) photoUri = resultUri.toString();
                goBackOrToMediaEditRaw();
            });
        }).start();
    }

    /** One captured overlay (a sticker or the caption), ready to be redrawn
     *  onto the full-resolution canvas at its proportional position. */
    private static class OverlaySnap {
        Bitmap bitmap;
        float centerXFrac, centerYFrac;
        float scale;
        float rotationDeg;
    }

    /** Rasterizes the caption view (if visible/non-empty) plus every sticker
     *  currently on flStickerLayer, recording each one's centre position (as
     *  a fraction of the preview stage) and its scale/rotation, so they can
     *  be redrawn in the correct spot on the full-resolution export. Must run
     *  on the UI thread — View#draw isn't safe off it. */
    private List<OverlaySnap> captureOverlaySnapshots() {
        List<OverlaySnap> list = new ArrayList<>();
        float stageW = flPreviewStage != null ? flPreviewStage.getWidth()  : 0;
        float stageH = flPreviewStage != null ? flPreviewStage.getHeight() : 0;
        if (tvCaption != null && tvCaption.getVisibility() == View.VISIBLE
                && captionText != null && !captionText.trim().isEmpty()) {
            OverlaySnap cap = snapshotView(tvCaption, stageW, stageH);
            if (cap != null) list.add(cap);
        }
        if (flStickerLayer != null) {
            for (int i = 0; i < flStickerLayer.getChildCount(); i++) {
                OverlaySnap s = snapshotView(flStickerLayer.getChildAt(i), stageW, stageH);
                if (s != null) list.add(s);
            }
        }
        return list;
    }

    @Nullable
    private OverlaySnap snapshotView(View v, float stageW, float stageH) {
        if (v == null || v.getVisibility() != View.VISIBLE
                || v.getWidth() <= 0 || v.getHeight() <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(v.getWidth(), v.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        v.draw(c);
        OverlaySnap s = new OverlaySnap();
        s.bitmap = bmp;
        float cx = v.getX() + v.getWidth()  / 2f;
        float cy = v.getY() + v.getHeight() / 2f;
        s.centerXFrac = stageW > 0 ? cx / stageW : 0.5f;
        s.centerYFrac = stageH > 0 ? cy / stageH : 0.5f;
        s.scale = v.getScaleX();
        s.rotationDeg = v.getRotation();
        return s;
    }

    /**
     * Background-thread render: decodes the full-resolution photo (Glide —
     * same EXIF-safe decode path used for the live preview), applies rotation
     * + the same combined colour matrix as applyAdjustPreview() (filter →
     * saturation → brightness → contrast), the colour-filter tint and effect
     * overlays (same colours as applyAdjustPreview()/applyEffectPreview()),
     * then draws every captured sticker/caption snapshot at its recorded
     * proportional position, and saves the result as a new JPEG.
     */
    private File renderBakedPhoto(String srcUri, String filter, String effect,
                                  float rotDeg, float br, float ct, float sat,
                                  List<OverlaySnap> overlays, float stageW, float stageH) throws Exception {
        Bitmap base = Glide.with(getApplicationContext())
                .asBitmap()
                .load(Uri.parse(srcUri))
                .skipMemoryCache(true)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .submit()
                .get();

        int baseW = base.getWidth(), baseH = base.getHeight();
        boolean swapDims = (Math.round(rotDeg) % 180) != 0;
        int outW = swapDims ? baseH : baseW;
        int outH = swapDims ? baseW : baseH;

        Bitmap output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);

        // 1. Photo, rotated in place, with the same filter+saturation+brightness+contrast
        //    matrix chain as applyAdjustPreview()'s live preview.
        Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        ColorMatrix cm = new ColorMatrix();
        ColorMatrix filterCm = extractColorMatrix(filter);
        if (filterCm != null) cm.postConcat(filterCm);
        ColorMatrix satCm = new ColorMatrix();
        satCm.setSaturation(sat);
        cm.postConcat(satCm);
        float b = br * 128f;
        cm.postConcat(new ColorMatrix(new float[]{
            1f,0f,0f,0f,b, 0f,1f,0f,0f,b, 0f,0f,1f,0f,b, 0f,0f,0f,1f,0f}));
        float t = 128f * (1f - ct);
        cm.postConcat(new ColorMatrix(new float[]{
            ct,0f,0f,0f,t, 0f,ct,0f,0f,t, 0f,0f,ct,0f,t, 0f,0f,0f,1f,0f}));
        photoPaint.setColorFilter(new ColorMatrixColorFilter(cm));

        canvas.save();
        canvas.translate(outW / 2f, outH / 2f);
        canvas.rotate(rotDeg);
        canvas.translate(-baseW / 2f, -baseH / 2f);
        canvas.drawBitmap(base, 0, 0, photoPaint);
        canvas.restore();
        base.recycle();

        // 2. Colour-filter tint overlay (matches vColorFilterOverlay).
        Integer tint = buildTint(filter);
        if (tint != null) canvas.drawColor(tint);

        // 3. Visual effect overlay (matches applyEffectPreview()'s switch).
        Integer effectColor = effectOverlayColor(effect);
        if (effectColor != null) canvas.drawColor(effectColor);

        // 4. Stickers + caption, redrawn at their recorded proportional position.
        if (stageW > 0 && stageH > 0) {
            float scaleX = outW / stageW;
            float scaleY = outH / stageH;
            float uniformScale = (scaleX + scaleY) / 2f;
            for (OverlaySnap s : overlays) {
                if (s == null || s.bitmap == null) continue;
                float cx = s.centerXFrac * outW;
                float cy = s.centerYFrac * outH;
                float drawScale = s.scale * uniformScale;
                canvas.save();
                canvas.translate(cx, cy);
                canvas.rotate(s.rotationDeg);
                canvas.scale(drawScale, drawScale);
                canvas.drawBitmap(s.bitmap, -s.bitmap.getWidth() / 2f, -s.bitmap.getHeight() / 2f, null);
                canvas.restore();
                s.bitmap.recycle();
            }
        }

        File dir = new File(getCacheDir(), "photo_edit_bake");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "bake_" + java.util.UUID.randomUUID() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            output.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        }
        output.recycle();
        return out;
    }

    /** Same colours as applyEffectPreview()'s switch, factored out so the
     *  background-thread renderer doesn't have to touch vEffectOverlay. */
    @Nullable
    private static Integer effectOverlayColor(String effect) {
        if (effect == null || "none".equals(effect)) return null;
        switch (effect) {
            case "vignette":        return 0x55000000;
            case "grain":           return 0x1AFFFFFF;
            case "glitch_overlay":  return 0x22FF0044;
            case "neon_glow":       return 0x22FF00FF;
            case "matte_overlay":   return 0x33FFFFFF;
            case "chrome_leak":     return 0x22FFFACD;
            case "bokeh":           return 0x15000000;
            case "scanlines":       return 0x18000000;
            case "dust":            return 0x14FFFFCC;
            case "double_exposure": return 0x30FFFFFF;
            default:                return null;
        }
    }

    /** Transparently forwards MediaEditActivity's result (whatever it is —
     *  edited-and-saved or cancelled) back to whoever launched THIS screen. */
    private void handleMediaEditFallbackResult(androidx.activity.result.ActivityResult result) {
        setResult(result.getResultCode(), result.getData());
        finish();
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private void bindViews() {
        flPreviewStage      = findViewById(R.id.fl_editor_preview_stage);
        ivPreview           = findViewById(R.id.iv_photo_editor_preview);
        ivBgBlur            = findViewById(R.id.iv_photo_editor_bg_blur);
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
        btnAddFullSticker = findViewById(R.id.btn_add_full_sticker);

        sbBrightness     = findViewById(R.id.sb_brightness);
        sbContrast       = findViewById(R.id.sb_contrast);
        sbSaturation     = findViewById(R.id.sb_saturation);
        tvBrightnessVal  = findViewById(R.id.tv_brightness_val);
        tvContrastVal    = findViewById(R.id.tv_contrast_val);
        tvSaturationVal  = findViewById(R.id.tv_saturation_val);

        sbDuration       = findViewById(R.id.sb_duration);
        tvDurationLabel  = findViewById(R.id.tv_duration_label);

        btnRotate = findViewById(R.id.btn_editor_rotate);
        btnCrop   = findViewById(R.id.btn_editor_crop);
        btnBack   = findViewById(R.id.btn_editor_back);
        btnDone   = findViewById(R.id.btn_editor_done);
        cbApplyAll = findViewById(R.id.cb_apply_all);
    }

    // ── Load preview ──────────────────────────────────────────────────────────

    private void loadPreviewImage() {
        if (photoUri == null || photoUri.isEmpty() || ivPreview == null) return;
        Uri uri = Uri.parse(photoUri);

        // Instagram-style blurred background: same photo, blurred + darkened, centerCrop.
        // Fills the entire preview area so there are no black bars for landscape/square photos.
        if (ivBgBlur != null) {
            Glide.with(this)
                    .load(uri)
                    .apply(new RequestOptions()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .transform(new CenterCrop(), new ReelBlurTransformation(20))
                            .override(120, 213))
                    .into(ivBgBlur);
        }

        // Foreground: fitCenter so the photo is NEVER cropped regardless of aspect ratio.
        Glide.with(this)
                .load(uri)
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .fitCenter()
                        .override(480, 853))
                .into(ivPreview);

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
                chip.setBackgroundColor(selected ? 0xFFA855F7
                        : androidx.core.content.ContextCompat.getColor(this,
                                com.callx.app.core.R.color.trim_chip_bg));
            }
        }
    }

    private void applyFilterPreview() {
        if (ivPreview == null) return;
        // Re-apply combined filter + current adjust values
        applyAdjustPreview();
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
                chip.setBackgroundColor(selected ? 0xFFFF416C
                        : androidx.core.content.ContextCompat.getColor(this,
                                com.callx.app.core.R.color.trim_chip_bg));
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

    // ── Full sticker picker (opens StatusStickerPickerSheet from core) ─────────

    /**
     * Auto-attaches a Music sticker using the sound that was already selected on
     * the Reels camera screen. Called once from onCreate when presetSoundTitle is
     * non-empty — gives the user an instant, visible music card on the photo so
     * they can reposition it before posting, exactly like the Status flow.
     */
    private void attachPresetMusicSticker() {
        if (flStickerLayer == null || presetSoundTitle.isEmpty()) return;
        String json = "{\"type\":\"music\""
            + ",\"song\":\"" + presetSoundTitle.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            + ",\"artist\":\"" + presetSoundArtist.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            + ",\"albumArt\":\"" + presetSoundCover + "\""
            + ",\"soundId\":\"" + presetSoundId + "\""
            + ",\"soundUrl\":\"" + presetSoundUrl + "\""
            + "}";
        StatusStickerOverlayView sv = StatusStickerOverlayView.fromJson(this, json);
        int dp = (int) getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            Math.min(flStickerLayer.getWidth() > 0 ? flStickerLayer.getWidth() - dp * 32 : dp * 260, dp * 260),
            FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp * 30;
        sv.setLayoutParams(lp);
        flStickerLayer.addView(sv);
        fullStickerViews.add(sv);
        // Long-press OR drag-to-trash removes it — both gestures live inside
        // attachDragToParent itself, so no need to reimplement removal here.
        sv.setOnStickerRemovedListener(removed -> {
            fullStickerViews.remove(removed);
            rebuildStickerJson();
        });
        sv.attachDragToParent(flStickerLayer);
        rebuildStickerJson();
        Toast.makeText(this, "🎵 Music sticker attached! Drag to reposition.", Toast.LENGTH_SHORT).show();
    }

    /** Opens the full sticker sheet — same Music/Poll/Quiz/Countdown flow as Status. */
    private void openFullStickerPicker() {
        StatusStickerPickerSheet.show(this, result -> {
            if (flStickerLayer == null) return;
            StatusStickerOverlayView sv = StatusStickerOverlayView.fromJson(this, result.json);
            int dp = (int) getResources().getDisplayMetrics().density;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(flStickerLayer.getWidth() > 0 ? flStickerLayer.getWidth() - dp * 32 : dp * 260, dp * 260),
                FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp * (30 + fullStickerViews.size() * 20);
            sv.setLayoutParams(lp);
            flStickerLayer.addView(sv);
            fullStickerViews.add(sv);
            // Long-press OR drag-to-trash removes it — both gestures live
            // inside attachDragToParent itself, no need to reimplement here.
            sv.setOnStickerRemovedListener(removed -> {
                fullStickerViews.remove(removed);
                rebuildStickerJson();
            });
            sv.attachDragToParent(flStickerLayer);
            rebuildStickerJson();
            Toast.makeText(this, getStickerLabel(result.type) + " added! Drag to reposition.", Toast.LENGTH_SHORT).show();
        });
    }

    private String getStickerLabel(String type) {
        if (type == null) return "✨ Sticker";
        switch (type) {
            case "music":     return "🎵 Music sticker";
            case "countdown": return "⏳ Countdown";
            case "quiz":      return "🧠 Quiz sticker";
            case "question":  return "💬 Question box";
            case "poll":      return "🗳️ Poll sticker";
            case "slider":    return "🎚️ Slider sticker";
            case "mention":   return "👤 Mention sticker";
            case "hashtag":   return "#️⃣ Hashtag sticker";
            case "link":      return "🔗 Link sticker";
            case "addyours":  return "➕ Add Yours sticker";
            default:          return "✨ Sticker";
        }
    }

    // ── Emoji sticker row (quick emoji overlays) ──────────────────────────────

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
        // 1. Emoji stickers (plain TextView draggable overlays)
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
        // 2. Full interactive stickers added via StatusStickerPickerSheet (music, poll, quiz…)
        for (StatusStickerOverlayView sv : fullStickerViews) {
            if (sv.getParent() == null) continue;
            String json = sv.toJsonWithScale();
            if (json == null || json.isEmpty()) continue;
            // Inject normalised position ratios so the viewer can restore exact placement
            if (flStickerLayer.getWidth() > 0 && json.startsWith("{") && json.endsWith("}")) {
                float xFrac = (sv.getX() + sv.getWidth() / 2f) / flStickerLayer.getWidth();
                float yFrac = (sv.getY() + sv.getHeight() / 2f) / flStickerLayer.getHeight();
                json = json.substring(0, json.length() - 1)
                    + String.format(",\"posXRatio\":%.3f,\"posYRatio\":%.3f}", xFrac, yFrac);
            }
            if (added > 0) sb.append(',');
            sb.append(json);
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
            btn.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    com.callx.app.core.R.color.trim_text_primary));
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(16, 8, 16, 8);
            btn.setBackgroundColor(dir.equals(kbDirection) ? 0xFF00E5FF
                    : androidx.core.content.ContextCompat.getColor(this,
                            com.callx.app.core.R.color.trim_chip_bg));
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
            v.setBackgroundColor(KB_DIRS[i].equals(kbDirection) ? 0xFF00E5FF
                    : androidx.core.content.ContextCompat.getColor(this,
                            com.callx.app.core.R.color.trim_chip_bg));
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
        // Build combined matrix: filter base + saturation + brightness + contrast
        ColorMatrix cm = new ColorMatrix();

        // 1. Apply the selected colour filter as base
        ColorMatrixColorFilter filterCmcf = ReelPhotoSlideshowAdapter.buildColorFilter(selectedFilter);
        if (filterCmcf != null) {
            // Extract the filter matrix by applying it to a known matrix
            ColorMatrix filterCm = extractColorMatrix(selectedFilter);
            if (filterCm != null) cm.postConcat(filterCm);
        }

        // 2. Saturation on top
        ColorMatrix satCm = new ColorMatrix();
        satCm.setSaturation(saturation);
        cm.postConcat(satCm);

        // 3. Brightness (translate channels)
        float b = brightness * 128f;
        ColorMatrix bright = new ColorMatrix(new float[]{
            1f,0f,0f,0f,b, 0f,1f,0f,0f,b, 0f,0f,1f,0f,b, 0f,0f,0f,1f,0f});
        cm.postConcat(bright);

        // 4. Contrast (scale around 128)
        float c = contrast;
        float t = 128f * (1f - c);
        ColorMatrix cont = new ColorMatrix(new float[]{
            c,0f,0f,0f,t, 0f,c,0f,0f,t, 0f,0f,c,0f,t, 0f,0f,0f,1f,0f});
        cm.postConcat(cont);

        ivPreview.setColorFilter(new ColorMatrixColorFilter(cm));

        // Keep tint overlay in sync with the filter selection
        Integer tint = buildTint(selectedFilter);
        if (tint != null) {
            vColorFilterOverlay.setBackgroundColor(tint);
            vColorFilterOverlay.setVisibility(View.VISIBLE);
        } else {
            vColorFilterOverlay.setVisibility(View.GONE);
        }
    }

    /** Returns a ColorMatrix for the named filter, or null for "normal". */
    private ColorMatrix extractColorMatrix(String filter) {
        if (filter == null || "normal".equals(filter)) return null;
        // Rebuild the same matrices as ReelPhotoSlideshowAdapter.buildColorFilter
        ColorMatrix cm = new ColorMatrix();
        switch (filter) {
            case "warm":
                cm.set(new float[]{
                    1.20f,0f,0f,0f,18f, 0f,1.05f,0f,0f,8f, 0f,0f,0.85f,0f,-20f, 0f,0f,0f,1f,0f});
                break;
            case "cool":
                cm.set(new float[]{
                    0.82f,0f,0f,0f,-18f, 0f,1f,0f,0f,0f, 0f,0f,1.22f,0f,22f, 0f,0f,0f,1f,0f});
                break;
            case "vivid":
                cm.setSaturation(1.9f); break;
            case "bw":
                cm.setSaturation(0f); break;
            case "golden_hour":
                cm.set(new float[]{
                    1.25f,0f,0f,0f,30f, 0f,1.10f,0f,0f,10f, 0f,0f,0.70f,0f,-40f, 0f,0f,0f,1f,0f});
                break;
            case "rose":
                cm.set(new float[]{
                    1.15f,0f,0.15f,0f,10f, 0f,0.90f,0f,0f,-5f, 0f,0f,0.80f,0f,0f, 0f,0f,0f,1f,0f});
                break;
            case "sunset":
                cm.set(new float[]{
                    1.30f,0f,0f,0f,40f, 0f,0.95f,0f,0f,5f, 0f,0f,0.60f,0f,-50f, 0f,0f,0f,1f,0f});
                break;
            case "neon_pop":
                cm.setSaturation(2.2f);
                cm.postConcat(new ColorMatrix(new float[]{
                    0.9f,0f,0.1f,0f,-5f, 0f,1.0f,0f,0f,0f, 0.1f,0f,1.15f,0f,15f, 0f,0f,0f,1f,0f}));
                break;
            case "matrix":
                cm.set(new float[]{
                    0f,0.20f,0f,0f,0f, 0f,1.10f,0f,0f,20f, 0f,0.10f,0.15f,0f,0f, 0f,0f,0f,1f,0f});
                break;
            case "dream":
                cm.setSaturation(0.7f);
                cm.postConcat(new ColorMatrix(new float[]{
                    1f,0f,0f,0f,30f, 0f,1f,0f,0f,30f, 0f,0f,1f,0f,30f, 0f,0f,0f,1f,-15f}));
                break;
            case "chrome":
                cm.set(new float[]{
                    1.30f,0f,0f,0f,-20f, 0f,1.30f,0f,0f,-20f, 0f,0f,1.30f,0f,-20f, 0f,0f,0f,1f,0f});
                break;
            case "matte":
                cm.set(new float[]{
                    0.90f,0f,0f,0f,15f, 0f,0.90f,0f,0f,15f, 0f,0f,0.90f,0f,15f, 0f,0f,0f,1f,0f});
                break;
            case "vintage":
                cm.set(new float[]{
                    1.10f,0f,0f,0f,10f, 0f,0.95f,0f,0f,5f, 0f,0f,0.75f,0f,-10f, 0f,0f,0f,1f,0f});
                break;
            case "fade_film":
                cm.set(new float[]{
                    0.85f,0f,0f,0f,30f, 0f,0.85f,0f,0f,25f, 0f,0f,0.85f,0f,20f, 0f,0f,0f,1f,0f});
                break;
            case "noir":
                cm.setSaturation(0f);
                cm.postConcat(new ColorMatrix(new float[]{
                    1.2f,0f,0f,0f,-30f, 0f,1.2f,0f,0f,-30f, 0f,0f,1.2f,0f,-30f, 0f,0f,0f,1f,0f}));
                break;
            default:
                return null;
        }
        return cm;
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

    /**
     * ✅ NEW (Instagram-style editor): jumps straight to a tool's panel by
     * index — the same underlying panel-visibility switch showPanel() used
     * to do, just driven by the right-edge rail instead of the old
     * horizontal tab bar. Mirrors ReelEditorActivity#goToEditorStep().
     */
    private void goToPhotoEditorStep(int step) {
        View[] panels = {panelFilters, panelEffects, panelCaption, panelStickers, panelAdjust};
        if (step < 0 || step >= panels.length) return;
        photoEditorCurrentStep = step;
        for (int i = 0; i < panels.length; i++) {
            if (panels[i] != null) panels[i].setVisibility(i == step ? View.VISIBLE : View.GONE);
        }
        updatePhotoEditorStepUi();
        updatePhotoEditorRailUi();
    }

    /**
     * ✅ NEW (Instagram-style editor): wires the right-edge vertical tool
     * rail — tapping any icon jumps straight to that tool's panel, same
     * pattern as ReelEditorActivity#setupEditorRail(). Every tool is
     * reachable at any time, not a linear wizard.
     */
    private void setupPhotoEditorRail() {
        photoEditorRailIcons = new ImageButton[] {
                findViewById(R.id.rail_icon_filter),
                findViewById(R.id.rail_icon_effect),
                findViewById(R.id.rail_icon_caption),
                findViewById(R.id.rail_icon_sticker),
                findViewById(R.id.rail_icon_adjust)
        };
        for (int i = 0; i < photoEditorRailIcons.length; i++) {
            if (photoEditorRailIcons[i] == null) continue;
            final int step = i;
            photoEditorRailIcons[i].setOnClickListener(v -> goToPhotoEditorStep(step));
        }
        updatePhotoEditorRailUi();
    }

    /** Fills whichever rail icon matches photoEditorCurrentStep with the
     *  brand gradient (bg_editor_rail_icon_active); every other icon stays
     *  the plain translucent disc (bg_editor_rail_icon). Mirrors
     *  ReelEditorActivity#updateEditorRailUi(). */
    private void updatePhotoEditorRailUi() {
        if (photoEditorRailIcons == null) return;
        for (int i = 0; i < photoEditorRailIcons.length; i++) {
            if (photoEditorRailIcons[i] == null) continue;
            boolean active = i == photoEditorCurrentStep;
            photoEditorRailIcons[i].setBackgroundResource(active
                    ? R.drawable.bg_editor_rail_icon_active
                    : R.drawable.bg_editor_rail_icon);
        }
    }

    private void showPanel(View panel, View tab) {
        View[] panels = {panelFilters, panelEffects, panelCaption, panelStickers, panelAdjust};
        View[] tabs   = {tabFilters,   tabEffects,   tabCaption,   tabStickers,   tabAdjust};
        for (int i = 0; i < panels.length; i++) {
            if (panels[i] != null) panels[i].setVisibility(panels[i] == panel ? View.VISIBLE : View.GONE);
            if (tabs[i]   != null) tabs[i].setAlpha(tabs[i] == tab ? 1f : 0.5f);
            if (tabs[i] == tab) photoEditorCurrentStep = i;
        }
        updatePhotoEditorStepUi();
    }

    /**
     * ✅ NEW: Binds the step indicator + Back/Next bar and syncs them with the
     * existing tab bar, so the 5 tool panels (Filter/Effect/Caption/Sticker/
     * Adjust) can be stepped through in order — same wizard pattern used on
     * the Reel Upload and Reel Editor screens.
     */
    private void setupPhotoEditorStepWizard() {
        tvPhotoEditorStepTitle = findViewById(R.id.tv_photo_editor_step_title);
        tvPhotoEditorStepName = findViewById(R.id.tv_photo_editor_step_name);
        photoEditorStepDots  = new TextView[] {
                findViewById(R.id.step_dot_1), findViewById(R.id.step_dot_2),
                findViewById(R.id.step_dot_3), findViewById(R.id.step_dot_4),
                findViewById(R.id.step_dot_5)
        };
        photoEditorStepLines = new View[] {
                findViewById(R.id.step_line_1), findViewById(R.id.step_line_2),
                findViewById(R.id.step_line_3), findViewById(R.id.step_line_4)
        };
        // ✅ NEW: vibrant-green spinning ring behind whichever step dot is
        // currently in use — shared pattern from :core, see updateActiveStepRing().
        photoEditorStepRings = new ImageView[] {
                findViewById(R.id.step_ring_1), findViewById(R.id.step_ring_2),
                findViewById(R.id.step_ring_3), findViewById(R.id.step_ring_4),
                findViewById(R.id.step_ring_5)
        };
        btnPhotoEditorStepBack = findViewById(R.id.btn_photo_editor_step_back);
        btnPhotoEditorStepNext = findViewById(R.id.btn_photo_editor_step_next);

        if (tvPhotoEditorStepTitle == null) return; // layout not the wizard version — no-op safety

        View[] panels = {panelFilters, panelEffects, panelCaption, panelStickers, panelAdjust};
        View[] tabs   = {tabFilters,   tabEffects,   tabCaption,   tabStickers,   tabAdjust};

        if (btnPhotoEditorStepBack != null) {
            btnPhotoEditorStepBack.setOnClickListener(v -> {
                int prev = photoEditorCurrentStep - 1;
                if (prev >= 0) showPanel(panels[prev], tabs[prev]);
            });
        }
        if (btnPhotoEditorStepNext != null) {
            btnPhotoEditorStepNext.setOnClickListener(v -> {
                int next = photoEditorCurrentStep + 1;
                if (next < panels.length) showPanel(panels[next], tabs[next]);
            });
        }
        // ✅ NEW: tapping a step dot jumps back to an already-visited step;
        // tapping ahead does nothing — only btnPhotoEditorStepNext may move
        // forward. Shared across every stepper screen — see StepDotsNavigationHelper.
        com.callx.app.utils.StepDotsNavigationHelper.bindStepDots(photoEditorStepDots,
            new com.callx.app.utils.StepDotsNavigationHelper.StepNavigator() {
                @Override public int getCurrentStep() { return photoEditorCurrentStep; }
                @Override public void goToStep(int step) {
                    if (step >= 0 && step < panels.length) showPanel(panels[step], tabs[step]);
                }
            });
        updatePhotoEditorStepUi();
    }

    private void updatePhotoEditorStepUi() {
        // Pill (tv_photo_editor_step_title) shows just "Step X of Y"; the step name
        // (Filter / Effect / etc.) is shown separately in tv_photo_editor_step_name,
        // uppercase, next to the pill — same pattern as Reel Editor.
        // PHOTO_EDITOR_STEP_TITLES[] left as-is since .length is still used for
        // step-count bounds checks; PHOTO_EDITOR_STEP_NAMES holds the plain-case
        // names for the label.
        if (tvPhotoEditorStepTitle != null) {
            tvPhotoEditorStepTitle.setText(getString(R.string.editor_step_pill_format,
                    photoEditorCurrentStep + 1, PHOTO_EDITOR_STEP_TITLES.length));
        }
        if (tvPhotoEditorStepName != null && photoEditorCurrentStep < PHOTO_EDITOR_STEP_NAMES.length) {
            tvPhotoEditorStepName.setText(PHOTO_EDITOR_STEP_NAMES[photoEditorCurrentStep]);
        }
        updatePhotoEditorStepDots();
        if (btnPhotoEditorStepBack != null) {
            btnPhotoEditorStepBack.setVisibility(photoEditorCurrentStep == 0 ? View.INVISIBLE : View.VISIBLE);
        }
        if (btnPhotoEditorStepNext != null) {
            boolean isLastStep = photoEditorCurrentStep == PHOTO_EDITOR_STEP_TITLES.length - 1;
            btnPhotoEditorStepNext.setVisibility(isLastStep ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /**
     * Reused from NewStatusActivity's updateWizardProgress() / ReelUploadActivity's
     * updateStepDots() / ReelEditorActivity's updateEditorStepDots() — same dot
     * stepper visuals (numbered circle turns brand-gradient once its step is
     * reached/passed, connecting line to its right fills solid, everything
     * ahead stays the neutral "not yet" grey) — just sized for this wizard's
     * 5 steps.
     */
    private void updatePhotoEditorStepDots() {
        if (photoEditorStepDots == null) return;
        for (int i = 0; i < photoEditorStepDots.length; i++) {
            if (photoEditorStepDots[i] == null) continue;
            boolean active = i <= photoEditorCurrentStep;
            photoEditorStepDots[i].setBackgroundResource(active
                    ? com.callx.app.core.R.drawable.bg_trim_gradient_button
                    : com.callx.app.core.R.drawable.bg_trim_circle_btn);
            photoEditorStepDots[i].setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    active ? com.callx.app.core.R.color.white
                           : com.callx.app.core.R.color.trim_text_secondary));
        }
        if (photoEditorStepLines == null) return;
        for (int i = 0; i < photoEditorStepLines.length; i++) {
            if (photoEditorStepLines[i] == null) continue;
            photoEditorStepLines[i].setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,
                    photoEditorCurrentStep >= i + 1
                            ? com.callx.app.core.R.color.trim_gradient_start
                            : com.callx.app.core.R.color.trim_divider));
        }
        updatePhotoEditorActiveStepRing();
    }

    /**
     * ✅ NEW: spins a vibrant-green ring behind the step dot currently in
     * use (photoEditorCurrentStep) — mirrors ReelUploadActivity's
     * updateActiveStepRing().
     */
    private void updatePhotoEditorActiveStepRing() {
        if (photoEditorStepRings == null) return;
        if (photoEditorActiveStepRingSpin != null) {
            photoEditorActiveStepRingSpin.cancel();
            photoEditorActiveStepRingSpin = null;
        }
        for (int i = 0; i < photoEditorStepRings.length; i++) {
            if (photoEditorStepRings[i] == null) continue;
            if (i == photoEditorCurrentStep) {
                photoEditorStepRings[i].setVisibility(View.VISIBLE);
                photoEditorStepRings[i].setRotation(0f);
                photoEditorActiveStepRingSpin = ObjectAnimator.ofFloat(
                        photoEditorStepRings[i], View.ROTATION, 0f, 360f);
                photoEditorActiveStepRingSpin.setDuration(1400);
                photoEditorActiveStepRingSpin.setRepeatCount(ObjectAnimator.INFINITE);
                photoEditorActiveStepRingSpin.setInterpolator(new android.view.animation.LinearInterpolator());
                photoEditorActiveStepRingSpin.start();
            } else {
                photoEditorStepRings[i].setVisibility(View.GONE);
            }
        }
    }

    /** Physical/gesture back steps backward through the rail tools before closing the editor. */
    @Override
    public void onBackPressed() {
        if (photoEditorCurrentStep > 0) {
            goToPhotoEditorStep(photoEditorCurrentStep - 1);
            return;
        }
        if (allowMediaEditFallback) {
            goBackOrToMediaEdit();
            return;
        }
        super.onBackPressed();
    }


    // ── Activity result forwarding ──────────────────────────────────────────

    /**
     * Explicit override so super.onActivityResult() is always called, which
     * causes AppCompatActivity's FragmentManager to forward the result to any
     * hosted fragment (e.g. StatusStickerPickerSheet) that called
     * Fragment.startActivityForResult() — most importantly for the Music
     * sticker's ReelTrendingAudioActivity picker.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @Nullable android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private void setupListeners() {
        if (tabFilters  != null) tabFilters.setOnClickListener(v -> showPanel(panelFilters,  tabFilters));
        if (tabEffects  != null) tabEffects.setOnClickListener(v -> showPanel(panelEffects,  tabEffects));
        if (tabCaption  != null) tabCaption.setOnClickListener(v -> showPanel(panelCaption,  tabCaption));
        if (tabStickers != null) tabStickers.setOnClickListener(v -> showPanel(panelStickers, tabStickers));
        if (btnAddFullSticker != null) btnAddFullSticker.setOnClickListener(v -> openFullStickerPicker());
        if (tabAdjust   != null) tabAdjust.setOnClickListener(v -> showPanel(panelAdjust,    tabAdjust));

        if (btnRotate != null) btnRotate.setOnClickListener(v -> {
            rotation = (rotation + 90f) % 360f;
            if (ivPreview != null) ivPreview.animate().rotation(rotation).setDuration(200).start();
        });

        if (btnCrop != null) btnCrop.setOnClickListener(v -> openCropScreen());

        if (btnBack != null) btnBack.setOnClickListener(v -> {
            if (allowMediaEditFallback) {
                goBackOrToMediaEdit();
                return;
            }
            setResult(RESULT_CANCELED);
            finish();
        });

        if (btnDone != null) btnDone.setOnClickListener(v -> {
            rebuildStickerJson();
            buildCaptionStyleJson();
            applyAll = cbApplyAll != null && cbApplyAll.isChecked();
            // Advance Editing round trip: Done also goes on to MediaEditActivity
            // (not straight back to Add Status) — same convention
            // ReelEditorActivity's Done uses when allowMediaEditFallback is set,
            // since only MediaEditActivity's own Save/Post should return to
            // Add Status on this path.
            if (allowMediaEditFallback) {
                goBackOrToMediaEdit();
                return;
            }
            Intent result = new Intent();
            result.putExtra(EXTRA_PHOTO_URI,     photoUri);
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
            // Screen title reads "Edit Status" instead of "Photo X of Y" when
            // this editor is being reused for Add Status's Advance Editing
            // entry point — same convention ReelEditorActivity uses for video.
            tvPhotoIndexLabel.setText(targetStatus
                    ? "Edit Status"
                    : "Photo " + (photoIndex + 1) + " of " + photoCount);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // ✅ FIX (light/dark mode): Filter/Effect chips were hardcoded
    // Color.WHITE text + 0x44FFFFFF unselected background — tuned only for
    // this screen's old forced-black look, invisible on a light card.
    // Tokenized to the same trim_* day/night colors used by the rest of the
    // card panel (see activity_reel_photo_editor.xml for the XML-side pass).
    private TextView makeChip(String label, boolean selected) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(androidx.core.content.ContextCompat.getColor(this,
                com.callx.app.core.R.color.trim_text_primary));
        tv.setTextSize(12f);
        tv.setPadding(24, 10, 24, 10);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(selected ? 0xFFA855F7
                : androidx.core.content.ContextCompat.getColor(this,
                        com.callx.app.core.R.color.trim_chip_bg));
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

    @Override
    protected void onDestroy() {
        if (photoEditorActiveStepRingSpin != null) photoEditorActiveStepRingSpin.cancel();
        super.onDestroy();
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
