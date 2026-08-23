package com.callx.app.editor;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.upload.ReelUploadActivity;
import com.callx.app.music.MusicPickerActivity;
import com.callx.app.music.SoundDetailActivity;
import com.callx.app.music.AudioMixHelper;
import com.callx.app.social.DuetReelActivity;
// ✅ NEW: reused from Chat's Media Editing screen (MediaEditActivity's btnEditCrop)
// — same crop screen (aspect chips, drag handles, rotate) lives in :core so any
// feature module can launch it, same pattern already used for ReelCameraActivity.
import com.callx.app.media.crop.MediaCropActivity;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;
import com.callx.app.editor.ReelFiltersActivity;
import com.callx.app.editor.ReelStickerPickerActivity;
import com.callx.app.editor.ReelSubtitlesActivity;
import com.callx.app.editor.ReelTransitionsActivity;
import com.callx.app.editor.ReelVoiceEffectsActivity;
import com.callx.app.editor.ReelAudioMixerActivity;
import com.callx.app.editor.ReelThumbnailPickerActivity;
import com.callx.app.stickers.StatusStickerPickerSheet;
import com.callx.app.stickers.StatusStickerOverlayView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelEditorActivity v13 — Full visual apply of all editing tools.
 *
 * Fixes over v12:
 *  ✅ FIX: Filter ColorMatrix now VISUALLY applied as overlay on video preview
 *  ✅ FIX: Sticker/emoji added as draggable overlay TextView on video frame
 *  ✅ FIX: Subtitle preview bar shown at bottom of video with first caption
 *  ✅ FIX: Active tool badges shown at top-left of video (filter name, voice, transition)
 *  ✅ FIX: Thumbnail preview shown in small corner badge after selection
 *  ✅ All v12 onActivityResult handling retained (result storage + duet pass-through)
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ReelEditorActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI           = "editor_video_uri";
    public static final String EXTRA_IS_FILE_PATH        = "is_file_path";
    /** Pass true from SoundDetailActivity "Use in Video" to auto-open the audio mixer. */
    public static final String EXTRA_OPEN_AUDIO_MIXER    = "open_audio_mixer";
    public static final String EXTRA_IS_DUET             = "editor_is_duet";
    public static final String EXTRA_DUET_ORIGINAL_ID    = "editor_duet_original_id";
    public static final String EXTRA_DUET_ORIGINAL_URL   = "editor_duet_original_url";
    public static final String EXTRA_DUET_OWNER_UID      = "editor_duet_owner_uid";
    public static final String EXTRA_DUET_LABEL          = "editor_duet_label";

    // ✅ NEW: Live filter/text/sticker presets carried over from ReelCameraActivity
    public static final String EXTRA_PRESET_FILTER_NAME       = "preset_filter_name";
    public static final String EXTRA_PRESET_FILTER_BRIGHTNESS = "preset_filter_brightness";
    public static final String EXTRA_PRESET_FILTER_CONTRAST   = "preset_filter_contrast";
    public static final String EXTRA_PRESET_FILTER_SATURATION = "preset_filter_saturation";
    public static final String EXTRA_PRESET_FILTER_BEAUTY     = "preset_filter_beauty";
    public static final String EXTRA_PRESET_STICKERS_JSON     = "preset_stickers_json";
    /** Effect preset carried from ReelCameraActivity → ReelEffectsActivity result */
    public static final String EXTRA_PRESET_EFFECT_NAME       = "preset_effect_name";
    public static final String EXTRA_PRESET_EFFECT_BRIGHTNESS = "preset_effect_brightness";
    public static final String EXTRA_PRESET_EFFECT_CONTRAST   = "preset_effect_contrast";
    public static final String EXTRA_PRESET_EFFECT_SATURATION = "preset_effect_saturation";
    public static final String EXTRA_PRESET_EFFECT_BEAUTY     = "preset_effect_beauty";
    /** Recording speed carried from ReelCameraActivity → ReelSpeedControlActivity result */
    public static final String EXTRA_PRESET_SPEED             = "preset_speed";
    /** NEW: forwarded from ReelCameraActivity when opened from Status's "Add Status"
     *  camera flow — routes the final "Next" tap back to Status (setResult) instead
     *  of on into ReelUploadActivity. Must match ReelCameraActivity.EXTRA_TARGET_STATUS. */
    public static final String EXTRA_TARGET_STATUS            = "target_status";
    /** NEW: set only when this screen was opened directly on an already-picked
     *  video via Status's attach-sheet pencil/Edit action (NOT the record-a-new-
     *  video camera flow, which has no "back to a simpler editor" screen to fall
     *  back to). When true, backing out of this screen (the X button or physical
     *  back at wizard step 0) — as opposed to completing the edit with Done —
     *  opens feature-chat's MediaEditActivity ("media editing screen") on the
     *  same video instead of just cancelling straight back to Status, and
     *  transparently forwards whatever MediaEditActivity itself returns. See
     *  goBackOrToMediaEdit()/handleMediaEditFallbackResult(). */
    public static final String EXTRA_ALLOW_MEDIA_EDIT_FALLBACK = "allow_media_edit_fallback";
    /** NEW: mirrors EXTRA_TARGET_STATUS but for feature-chat's single-video
     *  "Advance Editing" prompt (ChatMediaController#showSingleVideoAdvanceEditPrompt)
     *  — only changes the title text ("Edit video"). allowMediaEditFallback
     *  already handles the Back/Done → MediaEditActivity round trip; this flag
     *  is purely cosmetic so the chat entry point doesn't read "Edit Reel". */
    public static final String EXTRA_TARGET_CHAT               = "target_chat";

    private static final int REQ_FILTERS     = 401;
    private static final int REQ_STICKERS    = 402;
    private static final int REQ_SUBTITLES   = 403;
    private static final int REQ_TRANSITIONS = 404;
    private static final int REQ_VOICE       = 405;
    private static final int REQ_AUDIO_MIXER = 406;
    private static final int REQ_THUMBNAIL   = 407;
    /** ✅ NEW: open MusicPickerActivity to select a sound from scratch */
    private static final int REQ_MUSIC_PICKER = 408;
    /** ✅ NEW: open SoundDetailActivity for the already-selected sound */
    private static final int REQ_SOUND_DETAIL = 409;
    private static final int REQ_BEAT_SYNC    = 410;
    /** ✅ NEW: Step 1 · Trim and Crop → crop button → core's MediaCropActivity
     *  (reused from Chat's Media Editing screen). */
    private static final int REQ_CROP         = 411;

    // ── XML views ─────────────────────────────────────────────────────────
    private PlayerView    playerView;
    // ✅ FIX: rounded-corner box now sized to the real video aspect ratio.
    // videoPreviewOuter just centers content in the available weighted space;
    // videoPreviewContainer is the rounded/clipped box, resized in Java once
    // the video's actual width/height is known (see onVideoSizeChanged below).
    private View videoPreviewOuter, videoPreviewContainer;
    private ImageButton   btnPlayPause, btnBack;
    private com.callx.app.views.VideoTrimFilmstripView trimFilmstripView;
    private TextView      tvTrimStart, tvTrimEnd, tvDuration;
    private EditText      etTextOverlay;
    private View          btnNext, btnAddText;
    // ── Step 2 · Advanced text overlay wizard ───────────────────────────────
    private LinearLayout  llTextFontRow, llTextStyleRow, llTextBgRow, llTextColorRow;
    private SeekBar        seekTextSize;
    private TextView       btnDeleteTextOverlay;
    private TextView       tvTextTrashZone;
    // ✅ NEW: font/style/bg/colour/size controls now live in a bottom sheet
    // (see bottom_sheet_text_overlay_style.xml + openTextOverlayStyleSheet())
    // instead of always-visible inline in Step 2's card, so that card stays
    // the same compact height as every other step and the video preview no
    // longer shrinks to make room for it. Same BottomSheetDialog class the
    // sticker picker sheet is built on — cached and re-shown, not rebuilt.
    private View            btnTextOverlaySettings;
    private com.google.android.material.bottomsheet.BottomSheetDialog textOverlayStyleSheet;
    /** All advanced text overlays currently live on the video preview (see TextOverlayStyle tag on each). */
    private final List<TextView> textOverlayViews = new ArrayList<>();
    /** Currently selected overlay (style chips edit this one live) — null = next "Add" creates a new one. */
    private TextView        selectedTextOverlay = null;
    // Style state carried forward for the NEXT overlay you add (and live-edits the selection, if any).
    private String  currentFontKey   = "classic";   // classic | serif | mono | condensed
    private boolean currentBold      = true;
    private boolean currentItalic    = false;
    private String  currentBgStyle   = "pill";      // none | pill | solid | highlight
    private String  currentAlign     = "center";    // left | center | right
    private int     currentTextColor = Color.WHITE;
    private float   currentTextSizeSp = 24f;
    // ── Perf: Step 2 text overlay optimization ─────────────────────────────
    /** True once the chip/swatch rows have been built — after that, reselecting
     *  an overlay only updates selection state on the existing views instead of
     *  tearing down and reallocating every chip + drawable from scratch. */
    private boolean textOverlayPanelBuilt = false;
    /** Coalesces bursty stickerJson rebuilds (seekbar drag ticks, rapid chip taps)
     *  into a single rebuild ~100ms after the last change. proceedToUpload() still
     *  does a synchronous flush right before the value is actually read, so this
     *  never affects correctness — it only skips the wasted intermediate rebuilds. */
    private static final long STICKER_JSON_MERGE_DEBOUNCE_MS = 100L;
    private final Runnable stickerJsonMergeRunnable = this::mergeTextOverlaysIntoStickerJson;
    /** Background thread for extracting the Step-3 Filters preview frame off a video —
     *  frame decoding via MediaMetadataRetriever must never run on the UI thread. */
    private final ExecutorService filterPreviewExecutor = Executors.newSingleThreadExecutor();
    private ProgressBar   progressBuffering;
    private ImageButton   btnToolFilters, btnToolStickers, btnToolSubtitles,
                          btnToolTransitions, btnToolVoice, btnToolAudioMixer, btnToolThumbnail;
    /** ✅ NEW: Step 1 · Trim and Crop → Crop button (reused Chat's crop feature) */
    private View                        btnEditorCrop;
    /** ✅ NEW: music chip / tool button — opens SoundDetail (if sound selected) or MusicPicker */
    private ImageButton   btnToolMusic;

    // ── Editing-tools step wizard: Trim → Text → Tools(Look) → Tools(Motion &
    //    Sound) → Tools(Finishing). Separate from btnNext, which still moves
    //    on to the Upload screen after all editing is done. ────────────────
    private android.widget.ViewFlipper editorStepFlipper;
    private TextView                   tvEditorStepTitle;
    private TextView                   tvEditorStepName;
    // Dot-stepper (reused from Add Status / Reel Upload's stepper UI — see
    // updateEditorStepDots() below) — replaces the old thin horizontal
    // pb_editor_step ProgressBar.
    private TextView[]                 editorStepDots;
    private View[]                     editorStepLines;
    private ImageView[]                editorStepRings;
    private ObjectAnimator             editorActiveStepRingSpin;
    private View                       btnEditorStepBack, btnEditorStepNext;
    private int                        editorCurrentStep = 0;
    private static final String[] EDITOR_STEP_TITLES = {
            "Step 1 of 5 · Trim and Crop",
            "Step 2 of 5 · Text Overlay",
            "Step 3 of 5 · Tools · Look",
            "Step 4 of 5 · Tools · Motion & Sound",
            "Step 5 of 5 · Tools · Finishing"
    };
    /** Short step name shown in tv_editor_step_name, next to the "Step X of Y" pill
     *  (all-caps via android:textAllCaps, so plain-case names are given here). */
    private static final String[] EDITOR_STEP_NAMES = {
            "Trim and Crop",
            "Text Overlay",
            "Look",
            "Motion & Sound",
            "Finishing"
    };

    // ── Dynamic overlay views (added programmatically to the video FrameLayout) ──
    /** Semi-transparent colour overlay that simulates the selected filter */
    private View          filterOverlayView;
    /** Chip strip at top of video showing active tools */
    private LinearLayout  badgeStrip;
    /** Subtitle preview bar pinned to bottom of video frame */
    private TextView      tvSubtitlePreview;
    /** Small thumbnail preview badge (bottom-right corner) */
    private ImageView     ivThumbBadge;

    // ── Player ───────────────────────────────────────────────────────────
    private ExoPlayer player;
    private String    videoUriStr;
    private boolean   isFilePath = false;
    private long      totalDurationMs = 0;
    private long      trimStartMs     = 0;
    private long      trimEndMs       = 0;
    /** ✅ FIX: set true once runHardBakeExport() has burned a real trim range into
     *  videoUriStr, so ReelUploadActivity knows not to re-bake the (now reset) 0..duration
     *  range it receives — see ReelUploadActivity.EXTRA_TRIM_ALREADY_BAKED. */
    private boolean   trimBakedIntoFile = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ── Duet metadata ────────────────────────────────────────────────────
    private boolean isDuet          = false;
    private String  duetOriginalId  = "";
    private String  duetOwnerUid    = "";
    private String  duetLabel       = "";
    private String  duetOriginalUrl = "";
    private String  multiDuetSessionId = "";
    private int     multiDuetSlot      = -1;
    private int     multiDuetTotal     = 0;

    // ── Pre-selected sound ───────────────────────────────────────────────
    private String  preSelectedSoundId    = "";
    private String  preSelectedSoundTitle = "";
    private String  preSelectedSoundUrl   = "";
    // True when ReelCameraActivity already replaced mic audio with the selected
    // sound. Must be forwarded to ReelUploadActivity so it skips a second mix.
    private boolean audioAlreadyReplaced  = false;
    // True when coming from "Use in Video" (SoundDetailActivity gallery flow):
    // the mixer is auto-opened once the player is ready so user can balance volumes.
    private boolean openAudioMixerOnLoad  = false;
    private boolean mixerAutoOpened       = false; // guard: open only once

    // ── Tool result storage ───────────────────────────────────────────────
    // Filters
    private String filterName       = "";
    private float  filterBrightness = 0f;
    private float  filterContrast   = 1f;
    private float  filterSaturation = 1f;
    private float  filterBeauty     = 0f;
    // Stickers (list of all added stickers, each is a draggable TextView in the FrameLayout)
    private String stickerJson = "[]";
    /**
     * Full interactive stickers (music/quiz/poll/slider/countdown/mention/hashtag/
     * link/addyours) added via the shared StatusStickerPickerSheet — same widget +
     * sheet the Status and photo-slideshow reel editors use. Unlike the old
     * ReelStickerPickerActivity card system, these are kept LIVE (never baked into
     * the video pixels — ReelVideoExportEngine's baking only ever consumed the
     * "value" field plain emoji/text stickers have, so these were already implicitly
     * excluded from hard-baking) and forwarded as ReelModel#stickerJson so
     * ReelPlayerFragment can render them as tappable overlays at playback time,
     * wired through ReelStickerReplyHelper exactly like the photo-slideshow feed.
     */
    private final List<StatusStickerOverlayView> fullStickerViews = new ArrayList<>();
    // Subtitles
    private String  subtitlesJson     = "";
    private boolean subtitlesEnabled  = false;
    private int     subtitlesFontSize = 16;
    private int     subtitlesStyle    = 0;
    // Transitions
    private String  transitionName     = "";
    private int     transitionDuration = 0;
    private boolean transitionApplyAll = true;
    // Voice
    private String voiceEffectName = "";
    private float  voicePitch      = 1.0f;
    private float  voiceSpeed      = 1.0f;
    private float  voiceReverb     = 0.0f;
    /** Recording speed set before recording in ReelCameraActivity (0.3x – 3x). */
    private float  cameraSpeed     = 1.0f;
    // Audio mixer
    private float  mixOrigVol        = 1.0f;
    private float  mixMusicVol       = 0.8f;
    private String mixVoiceoverPath  = "";
    private float  mixVoiceoverVol   = 1.0f;
    private int    mixFadeInMs       = 0;
    private int    mixFadeOutMs      = 0;
    private float  mixPitchSemitones = 0f;
    /** ✅ NEW: peak-normalize flag from ReelAudioMixerActivity */
    private boolean mixNormalize     = false;
    private int    musicStartMs      = 0;
    private int    musicEndMs        = 0;
    private long[] beatTimesMs       = null;
    // Thumbnail
    private String thumbnailPath    = "";
    private long   thumbnailFrameMs = 0;
    // NEW: true when this editor session was opened from Status's camera flow —
    // see EXTRA_TARGET_STATUS / proceedToUploadInternal().
    private boolean targetStatus = false;
    // NEW: true when this editor session was opened from feature-chat's
    // single-video "Advance Editing" prompt — see EXTRA_TARGET_CHAT. Only
    // affects the title text; allowMediaEditFallback below still drives the
    // Back/Done → MediaEditActivity round trip either way.
    private boolean targetChat = false;
    // NEW: true when opened directly on an already-picked video from Status's
    // attach-sheet pencil/Edit action — see EXTRA_ALLOW_MEDIA_EDIT_FALLBACK.
    private boolean allowMediaEditFallback = false;
    private androidx.activity.result.ActivityResultLauncher<Intent> mediaEditFallbackLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_editor);

        videoUriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        isFilePath  = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);
        targetStatus = getIntent().getBooleanExtra(EXTRA_TARGET_STATUS, false);
        targetChat   = getIntent().getBooleanExtra(EXTRA_TARGET_CHAT, false);
        allowMediaEditFallback = getIntent().getBooleanExtra(EXTRA_ALLOW_MEDIA_EDIT_FALLBACK, false);

        // Screen title reads "Edit Status" instead of "Edit Reel" when this
        // editor is being reused for Status (targetStatus) instead of Reels.
        TextView tvEditorTitle = findViewById(R.id.tv_editor_title);
        if (tvEditorTitle != null) {
            tvEditorTitle.setText(targetStatus ? "Edit Status" : (targetChat ? "Edit video" : "Edit Reel"));
        }

        // Forwards whatever MediaEditActivity itself returns straight back to
        // whoever launched THIS screen — see goBackOrToMediaEdit().
        mediaEditFallbackLauncher = registerForActivityResult(
                new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                this::handleMediaEditFallbackResult);

        isDuet         = getIntent().getBooleanExtra(EXTRA_IS_DUET, false);
        duetOriginalId = nvl(getIntent().getStringExtra(EXTRA_DUET_ORIGINAL_ID));
        duetOwnerUid   = nvl(getIntent().getStringExtra(EXTRA_DUET_OWNER_UID));
        duetLabel      = nvl(getIntent().getStringExtra(EXTRA_DUET_LABEL));
        String dUrl    = getIntent().getStringExtra(EXTRA_DUET_ORIGINAL_URL);
        if (dUrl != null) duetOriginalUrl = dUrl;

        // Multi-duet session passthrough
        String mdsId = getIntent().getStringExtra("multi_duet_session_id");
        if (mdsId != null && !mdsId.isEmpty()) {
            multiDuetSessionId = mdsId;
            multiDuetSlot      = getIntent().getIntExtra("multi_duet_slot", -1);
            multiDuetTotal     = getIntent().getIntExtra("multi_duet_total", 0);
        }

        String si = getIntent().getStringExtra("selected_sound_id");
        String st = getIntent().getStringExtra("selected_sound_title");
        String su = getIntent().getStringExtra("selected_sound_url");
        if (si != null && !si.isEmpty()) preSelectedSoundId    = si;
        if (st != null && !st.isEmpty()) preSelectedSoundTitle = st;
        if (su != null && !su.isEmpty()) preSelectedSoundUrl   = su;
        // FIX: carry the "already replaced" flag from camera so upload skips re-mixing
        audioAlreadyReplaced = getIntent().getBooleanExtra("audio_already_replaced", false);
        musicStartMs = getIntent().getIntExtra("music_start_ms", 0);
        musicEndMs   = getIntent().getIntExtra("music_end_ms",   0);
        // "Use in Video" gallery flow: auto-open mixer once player is ready
        openAudioMixerOnLoad = getIntent().getBooleanExtra(EXTRA_OPEN_AUDIO_MIXER, false);

        if (videoUriStr == null || videoUriStr.isEmpty()) {
            Toast.makeText(this, "No video to edit", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        loadMetadata();
        setupPlayer();
        injectOverlayViews();   // ← NEW: add dynamic overlay views to video FrameLayout
        applyPresetsFromCamera(); // ✅ NEW: re-apply live filter/text/sticker chosen during recording
        setupListeners();
        setupEditorStepWizard();
    }

    /**
     * ✅ NEW: If the user already picked a filter / added text / stickers LIVE on the
     * camera recording screen (ReelCameraActivity), re-apply them here so nothing is lost
     * between recording → editing.
     */
    private void applyPresetsFromCamera() {
        String presetFilter = getIntent().getStringExtra(EXTRA_PRESET_FILTER_NAME);
        if (presetFilter != null && !presetFilter.isEmpty()) {
            filterName       = presetFilter;
            filterBrightness = getIntent().getFloatExtra(EXTRA_PRESET_FILTER_BRIGHTNESS, 0f);
            filterContrast   = getIntent().getFloatExtra(EXTRA_PRESET_FILTER_CONTRAST,   1f);
            filterSaturation = getIntent().getFloatExtra(EXTRA_PRESET_FILTER_SATURATION, 1f);
            filterBeauty     = getIntent().getFloatExtra(EXTRA_PRESET_FILTER_BEAUTY,     0f);
            applyFilterVisual(filterName, filterBrightness, filterContrast, filterSaturation);
            if (btnToolFilters != null) btnToolFilters.setColorFilter(
                android.graphics.Color.argb(200, 168, 85, 247));
        }

        // ── Effect preset from ReelEffectsActivity (via camera) ───────────
        String presetEffect = getIntent().getStringExtra(EXTRA_PRESET_EFFECT_NAME);
        if (presetEffect != null && !presetEffect.isEmpty()) {
            float eBright = getIntent().getFloatExtra(EXTRA_PRESET_EFFECT_BRIGHTNESS, 0f);
            float eCont   = getIntent().getFloatExtra(EXTRA_PRESET_EFFECT_CONTRAST,   1f);
            float eSat    = getIntent().getFloatExtra(EXTRA_PRESET_EFFECT_SATURATION, 1f);
            float eBeauty = getIntent().getFloatExtra(EXTRA_PRESET_EFFECT_BEAUTY,     0f);
            // Apply visually (reuses same tint-overlay system as filters)
            applyFilterVisual(presetEffect, eBright, eCont, eSat);
            // Tint the Filters toolbar button (there is no separate Effects button in the
            // toolbar — effects reuse the same filter-overlay system) so user sees it's active
            if (btnToolFilters != null)
                btnToolFilters.setColorFilter(android.graphics.Color.argb(200, 91, 91, 246));
        }

        // ── Recording speed preset from ReelSpeedControlActivity (via camera) ──
        float presetSpeed = getIntent().getFloatExtra(EXTRA_PRESET_SPEED, 1.0f);
        if (presetSpeed != 1.0f) {
            cameraSpeed = presetSpeed;
            // Apply to player once it's ready (listener in setupPlayer handles STATE_READY)
        }

        String presetStickers = getIntent().getStringExtra(EXTRA_PRESET_STICKERS_JSON);
        if (presetStickers != null && presetStickers.length() > 2) {
            // Simple split of top-level JSON objects in the array: "[{...},{...}]"
            String inner = presetStickers.substring(1, presetStickers.length() - 1).trim();
            if (!inner.isEmpty()) {
                int depth = 0, start = 0;
                for (int i = 0; i < inner.length(); i++) {
                    char c = inner.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            String obj = inner.substring(start, i + 1);
                            stickerJson = obj;
                            addStickerOverlay(obj);
                            start = i + 1;
                            while (start < inner.length() && (inner.charAt(start) == ',' )) start++;
                        }
                    }
                }
                if (btnToolStickers != null) btnToolStickers.setColorFilter(
                    android.graphics.Color.argb(200, 255, 215, 0));
            }
        }
    }

    // ── View binding ──────────────────────────────────────────────────────

    private void bindViews() {
        playerView         = findViewById(R.id.editor_player_view);
        videoPreviewOuter     = findViewById(R.id.video_preview_outer);
        videoPreviewContainer = findViewById(R.id.video_preview_container);
        btnPlayPause       = findViewById(R.id.btn_editor_play_pause);
        btnBack            = findViewById(R.id.btn_editor_back);
        trimFilmstripView  = findViewById(R.id.trim_filmstrip_view);
        tvTrimStart        = findViewById(R.id.tv_editor_trim_start);
        tvTrimEnd          = findViewById(R.id.tv_editor_trim_end);
        tvDuration         = findViewById(R.id.tv_editor_duration);
        etTextOverlay      = findViewById(R.id.et_text_overlay);
        btnNext            = findViewById(R.id.btn_editor_next);
        btnAddText         = findViewById(R.id.btn_add_text);
        btnTextOverlaySettings = findViewById(R.id.btn_text_overlay_settings);
        // ✅ NOTE: llTextFontRow / llTextStyleRow / llTextBgRow / llTextColorRow /
        // seekTextSize are NO LONGER in this activity's layout — they now live in
        // bottom_sheet_text_overlay_style.xml and are bound lazily the first time
        // openTextOverlayStyleSheet() inflates it (see below), not here.
        btnDeleteTextOverlay = findViewById(R.id.btn_delete_text_overlay);
        tvTextTrashZone    = findViewById(R.id.tv_text_trash_zone);
        progressBuffering  = findViewById(R.id.editor_progress_buffering);
        btnToolFilters     = findViewById(R.id.btn_tool_filters);
        btnToolStickers    = findViewById(R.id.btn_tool_stickers);
        btnToolSubtitles   = findViewById(R.id.btn_tool_subtitles);
        btnToolTransitions = findViewById(R.id.btn_tool_transitions);
        btnToolVoice       = findViewById(R.id.btn_tool_voice);
        btnToolAudioMixer  = findViewById(R.id.btn_tool_audio_mixer);
        btnToolThumbnail   = findViewById(R.id.btn_tool_thumbnail);
        btnEditorCrop      = findViewById(R.id.btn_editor_crop);
        // ✅ NEW: music chip button (add btn_tool_music ImageButton to the toolbar XML)
        btnToolMusic       = findViewById(R.id.btn_tool_music);

        // ✅ FIX: Transitions are a between-clips effect — they have no target
        // to apply to on Status's single-video flow (nothing downstream ever
        // reads transition_name for a lone clip either), so the tool silently
        // did nothing while still looking tappable/"applied". Hide it here
        // instead of leaving a dead control for targetStatus sessions.
        if (targetStatus && btnToolTransitions != null) {
            View transitionsColumn = (View) btnToolTransitions.getParent();
            if (transitionsColumn != null) transitionsColumn.setVisibility(View.GONE);
        }
    }

    // ── Inject dynamic overlay views into the video FrameLayout ──────────

    /**
     * Programmatically adds overlay views into the FrameLayout that wraps the PlayerView.
     * Called once in onCreate after bindViews().
     * Layers (bottom→top): PlayerView | filterOverlayView | advanced text overlays
     *                       (added per-overlay, see createAdvancedTextOverlay) |
     *                       sticker TextViews (added per sticker) | tvSubtitlePreview |
     *                       ivThumbBadge | badgeStrip
     */
    private void injectOverlayViews() {
        if (playerView == null) return;
        ViewGroup parent = (ViewGroup) playerView.getParent();
        if (!(parent instanceof FrameLayout)) return;
        FrameLayout fl = (FrameLayout) parent;
        int dp = (int) getResources().getDisplayMetrics().density;

        // 1. Filter colour overlay — full-size, initially transparent
        filterOverlayView = new View(this);
        filterOverlayView.setBackgroundColor(0x00000000);
        filterOverlayView.setVisibility(View.GONE);
        filterOverlayView.setClickable(false);
        fl.addView(filterOverlayView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        // 2. Subtitle preview bar — pinned to bottom, initially hidden
        tvSubtitlePreview = new TextView(this);
        tvSubtitlePreview.setTextColor(Color.WHITE);
        tvSubtitlePreview.setTextSize(16);
        tvSubtitlePreview.setGravity(Gravity.CENTER);
        tvSubtitlePreview.setPadding(16 * dp, 8 * dp, 16 * dp, 8 * dp);
        tvSubtitlePreview.setBackgroundColor(0xCC000000);
        tvSubtitlePreview.setVisibility(View.GONE);
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        subLp.bottomMargin = 16 * dp;
        fl.addView(tvSubtitlePreview, subLp);

        // 3. Thumbnail badge — small preview in bottom-right corner, initially hidden
        ivThumbBadge = new ImageView(this);
        ivThumbBadge.setScaleType(ImageView.ScaleType.CENTER_CROP);
        ivThumbBadge.setVisibility(View.GONE);
        int badgeSize = 56 * dp;
        FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(badgeSize, badgeSize,
            Gravity.BOTTOM | Gravity.END);
        thumbLp.bottomMargin = 80 * dp;
        thumbLp.rightMargin  = 12 * dp;
        fl.addView(ivThumbBadge, thumbLp);

        // 4. Badge strip — active tool chips at top-left, initially hidden
        badgeStrip = new LinearLayout(this);
        badgeStrip.setOrientation(LinearLayout.HORIZONTAL);
        badgeStrip.setPadding(8 * dp, 8 * dp, 8 * dp, 8 * dp);
        badgeStrip.setVisibility(View.GONE);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.START);
        badgeLp.topMargin   = 8 * dp;
        badgeLp.leftMargin  = 8 * dp;
        fl.addView(badgeStrip, badgeLp);
    }

    // ── Visual apply helpers ──────────────────────────────────────────────

    /**
     * Visually apply a filter by:
     *  a) Setting a semi-transparent colour overlay to simulate the tint
     *  b) Updating a badge chip in the badge strip
     */
    private void applyFilterVisual(String name, float brightness, float contrast, float saturation) {
        if (filterOverlayView == null) return;

        // Determine overlay colour based on filter preset
        int overlayColor;
        switch (name) {
            case "Warm":      overlayColor = 0x22FF8800; break; // orange tint
            case "Cool":      overlayColor = 0x220044FF; break; // blue tint
            case "Vivid":     overlayColor = 0x1AFF00AA; break; // slight magenta
            case "Fade":      overlayColor = 0x33FFFFFF; break; // white wash
            case "Drama":     overlayColor = 0x33000000; break; // darken
            case "Vintage":   overlayColor = 0x22884400; break; // sepia-ish
            case "Mono":      overlayColor = 0x44888888; break; // grey tint (simulates desaturate)
            case "Noir":      overlayColor = 0x55000000; break; // strong dark
            case "Juno":      overlayColor = 0x22FFAA00; break; // warm yellow
            case "Lark":      overlayColor = 0x1500DDFF; break; // light blue
            case "Clarendon": overlayColor = 0x220055CC; break; // rich blue
            case "Normal":    overlayColor = 0x00000000; break; // clear
            default:          overlayColor = 0x11FFFFFF; break;
        }

        if (name.equals("Normal") || name.isEmpty()) {
            filterOverlayView.setVisibility(View.GONE);
        } else {
            filterOverlayView.setBackgroundColor(overlayColor);
            filterOverlayView.setVisibility(View.VISIBLE);
        }

        updateBadge("filter", name.equals("Normal") ? null : "🎨 " + name);
    }

    /**
     * Add a sticker as a draggable overlay on the video FrameLayout.
     * Supports: emoji, text, gif (plain TextView) AND interactive stickers
     * (poll, quiz, slider, question — rendered as styled card views).
     *
     * Plain sticker JSON:       {"type":"emoji","value":"😀","x":0.5,"y":0.5}
     * Interactive sticker JSON: {"type":"interactive","stickerType":"poll",
     *                            "question":"...","options":[...],"extra":"...","x":0.5,"y":0.4}
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addStickerOverlay(String stickerJson) {
        if (playerView == null || stickerJson.isEmpty()) return;
        ViewGroup parent = (ViewGroup) playerView.getParent();
        if (!(parent instanceof FrameLayout)) return;
        FrameLayout fl = (FrameLayout) parent;
        int dp = (int) getResources().getDisplayMetrics().density;

        // Detect interactive sticker
        boolean isInteractive = stickerJson.contains("\"type\":\"interactive\"");

        if (isInteractive) {
            addInteractiveStickerOverlay(fl, stickerJson, dp);
            return;
        }

        // ── Plain sticker (emoji / text / gif) ────────────────────────────
        String value = "";
        try {
            int vStart = stickerJson.indexOf("\"value\":\"") + 9;
            int vEnd   = stickerJson.indexOf("\"", vStart);
            if (vStart > 8 && vEnd > vStart) value = stickerJson.substring(vStart, vEnd);
        } catch (Exception ignored) {}
        if (value.isEmpty()) value = "✨";

        // Handle text stickers that store "text|#RRGGBB"
        int textColor = Color.WHITE;
        if (value.contains("|#")) {
            int sep = value.lastIndexOf("|#");
            String colorHex = value.substring(sep + 1);
            value = value.substring(0, sep);
            try { textColor = Color.parseColor(colorHex); } catch (Exception ignored) {}
        }

        TextView stickerView = new TextView(this);
        stickerView.setText(value);
        stickerView.setTextSize(32);
        stickerView.setTextColor(textColor);
        stickerView.setPadding(8 * dp, 4 * dp, 8 * dp, 4 * dp);
        stickerView.setBackgroundColor(0x55000000);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER);
        fl.addView(stickerView, lp);

        makeDraggableAndRemovable(stickerView, fl);

        // Bounce animation
        stickerView.setScaleX(0.3f);
        stickerView.setScaleY(0.3f);
        stickerView.animate().scaleX(1f).scaleY(1f).setDuration(250).start();

        updateBadge("sticker", "✨ Sticker");
    }

    /**
     * Opens the full sticker sheet — same Music/Poll/Quiz/Countdown/Mention/
     * Hashtag/Link flow Status and the photo-slideshow reel editor use. Unlike
     * the retired card-based interactive stickers, these render with
     * StatusStickerOverlayView so ReelPlayerFragment can wire real tap-to-vote/
     * answer/subscribe behaviour at playback — the same widget, the same JSON,
     * the same ReelStickerReplyHelper flow the photo feed already has.
     */
    private void openFullStickerPicker() {
        StatusStickerPickerSheet.show(this, result -> {
            ViewGroup parent = (ViewGroup) playerView.getParent();
            if (!(parent instanceof FrameLayout)) return;
            FrameLayout fl = (FrameLayout) parent;

            StatusStickerOverlayView sv = StatusStickerOverlayView.fromJson(this, result.json);
            int dp = (int) getResources().getDisplayMetrics().density;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(fl.getWidth() > 0 ? fl.getWidth() - dp * 32 : dp * 260, dp * 260),
                FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp * (30 + fullStickerViews.size() * 20);
            sv.setLayoutParams(lp);
            fl.addView(sv);
            fullStickerViews.add(sv);
            // Long-press OR drag-to-trash removes it (both gestures live inside
            // attachDragToParent itself — no need to reimplement removal here).
            sv.setOnStickerRemovedListener(removed -> {
                fullStickerViews.remove(removed);
                rebuildFullStickerJson(fl);
            });
            sv.attachDragToParent(fl);
            rebuildFullStickerJson(fl);

            if (btnToolStickers != null) btnToolStickers.setColorFilter(
                android.graphics.Color.argb(200, 255, 215, 0)); // gold tint = active
            updateBadge("sticker", getFullStickerLabel(result.type));
            Toast.makeText(this, getFullStickerLabel(result.type) + " added! Drag to reposition.", Toast.LENGTH_SHORT).show();
        });
    }

    private String getFullStickerLabel(String type) {
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

    /**
     * Rebuilds {@link #stickerJson} as a JSON array of all StatusStickerOverlayView
     * stickers currently on the video frame, baking in each one's normalised
     * posXRatio/posYRatio so ReelPlayerFragment can restore exact placement —
     * mirrors ReelPhotoEditorActivity#rebuildStickerJson.
     */
    private void rebuildFullStickerJson(FrameLayout fl) {
        StringBuilder sb = new StringBuilder("[");
        int added = 0;
        for (StatusStickerOverlayView sv : fullStickerViews) {
            if (sv.getParent() == null) continue;
            String json = sv.toJsonWithScale();
            if (json == null || json.isEmpty()) continue;
            if (fl.getWidth() > 0 && json.startsWith("{") && json.endsWith("}")) {
                float xFrac = (sv.getX() + sv.getWidth() / 2f) / fl.getWidth();
                float yFrac = (sv.getY() + sv.getHeight() / 2f) / fl.getHeight();
                json = json.substring(0, json.length() - 1)
                    + String.format(java.util.Locale.US, ",\"posXRatio\":%.3f,\"posYRatio\":%.3f}", xFrac, yFrac);
            }
            if (added > 0) sb.append(',');
            sb.append(json);
            added++;
        }
        sb.append(']');
        stickerJson = sb.toString();
    }

    /**
     * Render an interactive sticker (Poll / Quiz / Slider / Question) as a styled
     * card overlay on the video FrameLayout. The card is draggable and long-press removes it.
     * @deprecated retained only for the legacy pre-record camera preset sticker
     * (see EXTRA_PRESET_STICKERS_JSON in onCreate) — the main sticker button now
     * opens {@link #openFullStickerPicker()} instead, which produces real
     * interactive StatusStickerOverlayView stickers.
     */
    @Deprecated
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addInteractiveStickerOverlay(FrameLayout fl, String json, int dp) {
        // Parse fields from JSON
        String stickerType = jsonStr(json, "stickerType", "poll");
        String question    = jsonStr(json, "question",    "");
        String extra       = jsonStr(json, "extra",       "");

        // Parse options array
        java.util.List<String> options = new java.util.ArrayList<>();
        try {
            int arrStart = json.indexOf("\"options\":[") + 10;
            int arrEnd   = json.indexOf("]", arrStart);
            if (arrStart > 9 && arrEnd > arrStart) {
                String arrContent = json.substring(arrStart + 1, arrEnd);
                for (String part : arrContent.split(",")) {
                    String opt = part.trim().replace("\"","");
                    if (!opt.isEmpty()) options.add(opt);
                }
            }
        } catch (Exception ignored) {}

        // Build the card
        android.widget.LinearLayout card = buildInteractiveCardView(stickerType, question, options, extra, dp);

        int cardWidth  = (int)(Math.min(fl.getWidth() > 0 ? fl.getWidth() : 360 * dp, 300 * dp));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(cardWidth,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        lp.topMargin = dp * 40;
        fl.addView(card, lp);

        makeDraggableAndRemovable(card, fl);

        // Pop-in animation
        card.setScaleX(0.3f);
        card.setScaleY(0.3f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(300)
            .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f))
            .start();

        String badge;
        switch (stickerType) {
            case "poll":     badge = "📊 Poll";     break;
            case "quiz":     badge = "🧠 Quiz";     break;
            case "slider":   badge = "😍 Slider";   break;
            case "question": badge = "💬 Question"; break;
            default:         badge = "✨ Sticker";
        }
        updateBadge("sticker", badge);
    }

    /**
     * Build the visual card view for an interactive sticker.
     */
    private android.widget.LinearLayout buildInteractiveCardView(
            String stickerType, String question,
            java.util.List<String> options, String extra, int dp) {

        android.widget.LinearLayout card = new android.widget.LinearLayout(this);
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(dp * 14, dp * 12, dp * 14, dp * 14);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);

        // Card style per sticker type
        int accentColor;
        String headerEmoji;
        String headerLabel;
        switch (stickerType) {
            case "poll":
                bg.setColor(0xEE1B3D6F);
                accentColor = 0xFF4A90E2;
                headerEmoji = "📊"; headerLabel = "POLL"; break;
            case "quiz":
                bg.setColor(0xEE2D1B6F);
                accentColor = 0xFFAA55FF;
                headerEmoji = "🧠"; headerLabel = "QUIZ"; break;
            case "slider":
                bg.setColor(0xEE6F1B1B);
                accentColor = 0xFFFF5555;
                headerEmoji = ""; headerLabel = "RATE THIS"; break;
            case "question":
                bg.setColor(0xEE1B5040);
                accentColor = 0xFF00C897;
                headerEmoji = "💬"; headerLabel = "ASK ME"; break;
            default:
                bg.setColor(0xEE222222);
                accentColor = 0xFFFF3B5C;
                headerEmoji = "✨"; headerLabel = "STICKER";
        }
        bg.setStroke(1, accentColor);
        card.setBackground(bg);

        // Header row: emoji label + type chip
        android.widget.LinearLayout headerRow = new android.widget.LinearLayout(this);
        headerRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams hrLp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp * 8;

        if (!headerEmoji.isEmpty()) {
            TextView tvEmoji = new TextView(this);
            tvEmoji.setText(headerEmoji);
            tvEmoji.setTextSize(16);
            android.widget.LinearLayout.LayoutParams eLP = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            eLP.rightMargin = dp * 4;
            headerRow.addView(tvEmoji, eLP);
        }

        TextView tvType = new TextView(this);
        tvType.setText(headerLabel);
        tvType.setTextColor(accentColor);
        tvType.setTextSize(11);
        tvType.setTypeface(null, android.graphics.Typeface.BOLD);
        tvType.setLetterSpacing(0.1f);
        headerRow.addView(tvType, new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(headerRow, hrLp);

        // Question text
        TextView tvQuestion = new TextView(this);
        tvQuestion.setText(question);
        tvQuestion.setTextColor(Color.WHITE);
        tvQuestion.setTextSize(15);
        tvQuestion.setTypeface(null, android.graphics.Typeface.BOLD);
        tvQuestion.setMaxLines(3);
        android.widget.LinearLayout.LayoutParams qLp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        qLp.bottomMargin = dp * 10;
        card.addView(tvQuestion, qLp);

        // Body per sticker type
        switch (stickerType) {
            case "poll":
            case "quiz": {
                // Show options as rounded buttons
                for (int i = 0; i < options.size() && i < 4; i++) {
                    TextView opt = new TextView(this);
                    boolean isCorrect = stickerType.equals("quiz") && extra.contains("correctIndex:" + i);
                    opt.setText(options.get(i));
                    opt.setTextColor(isCorrect ? Color.WHITE : 0xFFCCCCCC);
                    opt.setTextSize(13);
                    opt.setGravity(android.view.Gravity.CENTER);
                    opt.setPadding(dp * 12, dp * 8, dp * 12, dp * 8);

                    android.graphics.drawable.GradientDrawable optBg = new android.graphics.drawable.GradientDrawable();
                    optBg.setCornerRadius(dp * 10);
                    if (isCorrect) {
                        optBg.setColor(accentColor);
                    } else {
                        optBg.setColor(0x33FFFFFF);
                        optBg.setStroke(1, 0x55FFFFFF);
                    }
                    opt.setBackground(optBg);

                    android.widget.LinearLayout.LayoutParams optLp = new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    optLp.bottomMargin = dp * 6;
                    card.addView(opt, optLp);
                }
                break;
            }
            case "slider": {
                // Parse emoji from extra
                String sliderEmoji = "😍";
                if (extra.startsWith("emoji:")) sliderEmoji = extra.substring(6);

                android.widget.LinearLayout sliderRow = new android.widget.LinearLayout(this);
                sliderRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                sliderRow.setGravity(android.view.Gravity.CENTER);

                // Left emoji (dim)
                TextView tvLeft = new TextView(this);
                tvLeft.setText(sliderEmoji);
                tvLeft.setTextSize(20);
                tvLeft.setAlpha(0.35f);
                sliderRow.addView(tvLeft);

                // Thumb at 50%
                android.widget.LinearLayout trackWrap = new android.widget.LinearLayout(this);
                trackWrap.setGravity(android.view.Gravity.CENTER_VERTICAL);
                android.widget.LinearLayout.LayoutParams twLp = new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                twLp.leftMargin  = dp * 8;
                twLp.rightMargin = dp * 8;

                android.widget.ProgressBar pb = new android.widget.ProgressBar(this,
                    null, android.R.attr.progressBarStyleHorizontal);
                pb.setProgress(50);
                pb.setMax(100);
                trackWrap.addView(pb, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp * 12));
                sliderRow.addView(trackWrap, twLp);

                // Right emoji (full)
                TextView tvRight = new TextView(this);
                tvRight.setText(sliderEmoji);
                tvRight.setTextSize(26);
                sliderRow.addView(tvRight);

                android.widget.LinearLayout.LayoutParams srLp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                srLp.bottomMargin = dp * 4;
                card.addView(sliderRow, srLp);

                TextView tvHint = new TextView(this);
                tvHint.setText("Slide to react →");
                tvHint.setTextColor(0x99FFFFFF);
                tvHint.setTextSize(11);
                tvHint.setGravity(android.view.Gravity.CENTER);
                card.addView(tvHint);
                break;
            }
            case "question": {
                // Reply box
                android.widget.LinearLayout replyBox = new android.widget.LinearLayout(this);
                replyBox.setGravity(android.view.Gravity.CENTER_VERTICAL);
                replyBox.setPadding(dp * 10, dp * 8, dp * 10, dp * 8);
                android.graphics.drawable.GradientDrawable rBg = new android.graphics.drawable.GradientDrawable();
                rBg.setCornerRadius(dp * 20);
                rBg.setColor(0x33FFFFFF);
                rBg.setStroke(1, 0x55FFFFFF);
                replyBox.setBackground(rBg);

                TextView tvReply = new TextView(this);
                tvReply.setText("Send a reply…");
                tvReply.setTextColor(0x88FFFFFF);
                tvReply.setTextSize(13);
                replyBox.addView(tvReply, new android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                TextView tvSend = new TextView(this);
                tvSend.setText("→");
                tvSend.setTextColor(accentColor);
                tvSend.setTextSize(16);
                replyBox.addView(tvSend);

                card.addView(replyBox, new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
                break;
            }
        }

        return card;
    }

    /**
     * ✅ FIX: Subtitles picked in this editor were captured into subtitlesJson/
     * subtitlesEnabled and shown as a live preview bar, but neither this
     * editor's status exit (finishForStatusResult) nor its normal Reel exit
     * (ReelUploadActivity) ever actually burns caption text into the video —
     * so the "Subtitles" tool button did nothing to the final posted media in
     * either flow. There's no per-frame/time-ranged text renderer in
     * ReelVideoExportEngine to burn a fully time-synced track, but it DOES
     * already support baking a single static text overlay (same mechanism
     * used for stickers/text). So for Status specifically — which shows one
     * continuous clip rather than a scrubbable timeline — burn the first
     * caption line in as a bottom-anchored overlay, appended into the same
     * stickerJson array that's about to be hard-baked below. Matches the
     * "first caption" precedent already used for the live preview bar.
     */
    private void mergeSubtitleCaptionIntoOverlay() {
        if (!subtitlesEnabled || subtitlesJson == null || subtitlesJson.length() < 5) return;
        String firstText = jsonStr(subtitlesJson, "text", "");
        if (firstText.isEmpty()) return;

        String escaped = firstText.replace("\\", "\\\\").replace("\"", "\\\"");
        String captionOverlay = "{\"type\":\"text\",\"value\":\"" + escaped
            + "|#FFFFFF\",\"x\":0.5,\"y\":0.85}";

        if (stickerJson == null || stickerJson.isEmpty() || stickerJson.equals("[]")) {
            stickerJson = "[" + captionOverlay + "]";
        } else if (stickerJson.trim().startsWith("[")) {
            String trimmed = stickerJson.trim();
            stickerJson = trimmed.substring(0, trimmed.length() - 1)
                + (trimmed.length() > 2 ? "," : "") + captionOverlay + "]";
        }
    }

    /** Simple JSON string field extractor (no library needed). */
    private String jsonStr(String json, String key, String defaultVal) {
        try {
            String search = "\"" + key + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) return defaultVal;
            start += search.length();
            int end = json.indexOf("\"", start);
            if (end < 0) return defaultVal;
            return json.substring(start, end).replace("\\\"","\"");
        } catch (Exception e) {
            return defaultVal;
        }
    }

    /** Makes a view draggable within its parent FrameLayout; long-press removes it. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void makeDraggableAndRemovable(View view, FrameLayout fl) {
        final float[] startTouch = new float[2];
        final float[] startPos   = new float[2];
        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    startPos[0]   = v.getX();
                    startPos[1]   = v.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v.setX(startPos[0] + (event.getRawX() - startTouch[0]));
                    v.setY(startPos[1] + (event.getRawY() - startTouch[1]));
                    return true;
                case MotionEvent.ACTION_UP:
                    return true;
            }
            return false;
        });
        view.setOnLongClickListener(v -> {
            v.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(180)
                .withEndAction(() -> fl.removeView(v)).start();
            return true;
        });
    }

    // ════════════════════════════════════════════════════════════════════
    // ── Step 2 · ULTRA ADVANCED text overlay system ───────────────────────
    // Multiple simultaneous overlays, each independently draggable, pinch-
    // to-scale + rotate, with per-overlay font family / bold / italic /
    // background style (none·pill·solid·highlight) / alignment / colour /
    // size — all of which also bake into the exported video pixels via
    // ReelVideoExportEngine (see drawStyledOverlay there).
    // ════════════════════════════════════════════════════════════════════

    /** Small holder tagged onto each overlay TextView so its style survives drag/scale/rotate. */
    private static class TextOverlayStyle {
        String text;
        String fontKey  = "classic";
        boolean bold    = true;
        boolean italic  = false;
        String bgStyle  = "pill";
        String align    = "center";
        int colorInt    = Color.WHITE;
        float sizeSp    = 24f;
    }

    private FrameLayout getVideoOverlayLayer() {
        if (playerView == null) return null;
        ViewGroup parent = (ViewGroup) playerView.getParent();
        return (parent instanceof FrameLayout) ? (FrameLayout) parent : null;
    }

    /**
     * Builds all the chip rows (font / bold·italic·align / background style /
     * colour) once, in code.
     * Perf: this used to run in full — removeAllViews() + brand-new TextViews +
     * brand-new GradientDrawable/StateListDrawable per chip — on every single
     * overlay tap/reselect. On a screen where tapping between overlays is the
     * core interaction, that meant dozens of view + drawable allocations per
     * tap for zero visual difference beyond which chip is highlighted. Now the
     * rows are physically built exactly once per screen session; every
     * subsequent selection change just flips .setSelected() on the existing
     * chips via syncTextOverlayPanelSelectionUI().
     */
    /**
     * ✅ NEW: Step 2 · Text Overlay — Settings button (between the text input
     * and Add button). Opens the font / bold·italic·align / background /
     * colour / size controls in a bottom sheet instead of inline in the card.
     *
     * Why: those controls used to sit directly inside Step 2's card, always
     * visible, which made that step's card panel noticeably taller than every
     * other step (Trim, Look, Motion & Sound, Finishing) — and since the video
     * preview above shrinks to make room for the card, Step 2 had a visibly
     * smaller preview than the rest of the wizard. Moving them into a sheet
     * that only appears on demand keeps Step 2's card the same compact height
     * as the others, so the preview area stays consistent across all 5 steps.
     *
     * Reuses the same com.google.android.material.bottomsheet.BottomSheetDialog
     * class the sticker picker sheet (StatusStickerPickerSheet) and other
     * sheets in this app (see DuetReelActivity) are already built on — no new
     * popup/dialog mechanism, just this screen's existing chip-building logic
     * (setupAdvancedTextOverlayPanel() etc.) pointed at a sheet's views instead
     * of the activity's own layout.
     */
    private void openTextOverlayStyleSheet() {
        if (textOverlayStyleSheet == null) {
            View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_text_overlay_style, null);
            llTextFontRow  = sheetView.findViewById(R.id.ll_text_font_row);
            llTextStyleRow = sheetView.findViewById(R.id.ll_text_style_row);
            llTextBgRow    = sheetView.findViewById(R.id.ll_text_bg_row);
            llTextColorRow = sheetView.findViewById(R.id.ll_text_color_row);
            seekTextSize   = sheetView.findViewById(R.id.seek_text_size);

            textOverlayStyleSheet = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
            textOverlayStyleSheet.setContentView(sheetView);

            View btnDone = sheetView.findViewById(R.id.btn_text_style_done);
            if (btnDone != null) btnDone.setOnClickListener(v -> textOverlayStyleSheet.dismiss());

            // First real build now that the sheet's rows/seekbar actually exist.
            setupAdvancedTextOverlayPanel();
        } else {
            // Sheet already built — just reflect whichever overlay is
            // currently selected (in case selection changed while it was closed).
            syncTextOverlayPanelSelectionUI();
        }
        textOverlayStyleSheet.show();
    }

    private void setupAdvancedTextOverlayPanel() {
        if (textOverlayPanelBuilt) {
            syncTextOverlayPanelSelectionUI();
            return;
        }
        // ✅ Views now live in bottom_sheet_text_overlay_style.xml, bound lazily
        // by openTextOverlayStyleSheet() the first time the settings sheet is
        // opened. Until then there's nothing to build — this may be reached
        // earlier (e.g. selectTextOverlay() tapping an existing overlay before
        // the sheet has ever been opened), so just no-op and let the sheet's
        // own open path do the real one-time build once the views exist.
        if (llTextFontRow == null) return;
        textOverlayPanelBuilt = true;
        int dp = (int) getResources().getDisplayMetrics().density;

        // ── Font family chips ──────────────────────────────────────────
        String[] fontKeys   = {"classic", "serif", "mono", "condensed"};
        String[] fontLabels = {"Classic", "Serif", "Mono", "Condensed"};
        if (llTextFontRow != null) {
            llTextFontRow.removeAllViews();
            for (int i = 0; i < fontKeys.length; i++) {
                final int idx = i;
                TextView chip = buildChip(fontLabels[i], dp);
                chip.setTypeface(resolvePreviewTypeface(fontKeys[i], false, false));
                chip.setSelected(fontKeys[i].equals(currentFontKey));
                chip.setTag(fontKeys[i]);
                chip.setOnClickListener(v -> {
                    currentFontKey = fontKeys[idx];
                    refreshChipSelection(llTextFontRow, v);
                    applyLiveStyleToSelection();
                });
                llTextFontRow.addView(chip);
            }
        }

        // ── Bold / Italic / Align chips ────────────────────────────────
        if (llTextStyleRow != null) {
            llTextStyleRow.removeAllViews();
            TextView chipBold = buildChip("B", dp);
            chipBold.setTypeface(null, android.graphics.Typeface.BOLD);
            chipBold.setSelected(currentBold);
            chipBold.setTag("bold");
            chipBold.setOnClickListener(v -> {
                currentBold = !currentBold;
                v.setSelected(currentBold);
                applyLiveStyleToSelection();
            });
            llTextStyleRow.addView(chipBold);

            TextView chipItalic = buildChip("I", dp);
            chipItalic.setTypeface(null, android.graphics.Typeface.ITALIC);
            chipItalic.setSelected(currentItalic);
            chipItalic.setTag("italic");
            chipItalic.setOnClickListener(v -> {
                currentItalic = !currentItalic;
                v.setSelected(currentItalic);
                applyLiveStyleToSelection();
            });
            llTextStyleRow.addView(chipItalic);

            String[] aligns = {"left", "center", "right"};
            String[] alignLabels = {"⇤", "≡", "⇥"};
            for (int i = 0; i < aligns.length; i++) {
                final int idx = i;
                TextView chip = buildChip(alignLabels[i], dp);
                chip.setSelected(aligns[i].equals(currentAlign));
                chip.setTag(aligns[i]);
                chip.setOnClickListener(v -> {
                    currentAlign = aligns[idx];
                    refreshChipSelection(llTextStyleRow, v, /*skipFirstN=*/2);
                    applyLiveStyleToSelection();
                });
                llTextStyleRow.addView(chip);
            }
        }

        // ── Background style chips ─────────────────────────────────────
        String[] bgKeys    = {"none", "pill", "solid", "highlight"};
        String[] bgLabels  = {"No BG", "Pill", "Solid", "Highlight"};
        if (llTextBgRow != null) {
            llTextBgRow.removeAllViews();
            for (int i = 0; i < bgKeys.length; i++) {
                final int idx = i;
                TextView chip = buildChip(bgLabels[i], dp);
                chip.setSelected(bgKeys[i].equals(currentBgStyle));
                chip.setTag(bgKeys[i]);
                chip.setOnClickListener(v -> {
                    currentBgStyle = bgKeys[idx];
                    refreshChipSelection(llTextBgRow, v);
                    applyLiveStyleToSelection();
                });
                llTextBgRow.addView(chip);
            }
        }

        // ── Colour swatches ─────────────────────────────────────────────
        int[] colors = {
            Color.WHITE, Color.BLACK, 0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00,
            0xFF34C759, 0xFF00C7BE, 0xFF007AFF, 0xFFAF52DE, 0xFFFF2D78
        };
        if (llTextColorRow != null) {
            llTextColorRow.removeAllViews();
            for (int color : colors) {
                View swatch = buildColorSwatch(color, dp);
                swatch.setSelected(color == currentTextColor);
                swatch.setTag(color);
                swatch.setOnClickListener(v -> {
                    currentTextColor = color;
                    refreshChipSelection(llTextColorRow, v);
                    applyLiveStyleToSelection();
                });
                llTextColorRow.addView(swatch);
            }
            // Custom colour swatch — opens a simple RGB picker dialog.
            TextView customSwatch = new TextView(this);
            customSwatch.setText("+");
            customSwatch.setGravity(Gravity.CENTER);
            customSwatch.setTextColor(Color.WHITE);
            customSwatch.setTextSize(16);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(32 * dp, 32 * dp);
            clp.setMargins(4 * dp, 4 * dp, 4 * dp, 4 * dp);
            customSwatch.setLayoutParams(clp);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            gd.setColor(0xFF3A3A3C);
            gd.setStroke((int) (1.5f * dp), 0x66FFFFFF);
            customSwatch.setBackground(gd);
            customSwatch.setOnClickListener(v -> showCustomColorPicker());
            llTextColorRow.addView(customSwatch);
        }

        if (seekTextSize != null) {
            seekTextSize.setProgress((int) currentTextSizeSp - 12);
            seekTextSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    currentTextSizeSp = 12 + progress;
                    if (fromUser) applyLiveStyleToSelection();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
    }

    /**
     * Lightweight reselection path: flips .setSelected() on the already-built
     * chips/swatches to match the current style state, and updates the size
     * seekbar's progress — no view or drawable allocation at all. This is what
     * actually runs every time the user taps between overlays, replacing the
     * old full teardown-and-rebuild.
     */
    private void syncTextOverlayPanelSelectionUI() {
        syncRowSelectionByTag(llTextFontRow, currentFontKey);
        if (llTextStyleRow != null) {
            for (int i = 0; i < llTextStyleRow.getChildCount(); i++) {
                View child = llTextStyleRow.getChildAt(i);
                Object tag = child.getTag();
                if ("bold".equals(tag)) child.setSelected(currentBold);
                else if ("italic".equals(tag)) child.setSelected(currentItalic);
                else if (tag instanceof String) child.setSelected(tag.equals(currentAlign));
            }
        }
        syncRowSelectionByTag(llTextBgRow, currentBgStyle);
        if (llTextColorRow != null) {
            for (int i = 0; i < llTextColorRow.getChildCount(); i++) {
                View child = llTextColorRow.getChildAt(i);
                if (child.getTag() instanceof Integer) {
                    child.setSelected((Integer) child.getTag() == currentTextColor);
                }
            }
        }
        if (seekTextSize != null) {
            seekTextSize.setProgress((int) currentTextSizeSp - 12);
        }
    }

    /** Sets .setSelected(true) on the one child of `row` whose String tag equals `key`. */
    private void syncRowSelectionByTag(LinearLayout row, String key) {
        if (row == null) return;
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            child.setSelected(key.equals(child.getTag()));
        }
    }

    private TextView buildChip(String label, int dp) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setTextColor(Color.WHITE);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(14 * dp, 8 * dp, 14 * dp, 8 * dp);
        chip.setBackground(buildChipBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 8 * dp, 0);
        chip.setLayoutParams(lp);
        return chip;
    }

    private android.graphics.drawable.Drawable buildChipBackground() {
        android.graphics.drawable.GradientDrawable selected = new android.graphics.drawable.GradientDrawable();
        selected.setColor(0xFF5B5BF6);
        selected.setCornerRadius(20f);
        android.graphics.drawable.GradientDrawable unselected = new android.graphics.drawable.GradientDrawable();
        unselected.setColor(0xFF2A2A30);
        unselected.setStroke(1, 0x33FFFFFF);
        unselected.setCornerRadius(20f);

        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_selected}, selected);
        sld.addState(new int[]{}, unselected);
        return sld;
    }

    private View buildColorSwatch(int color, int dp) {
        View swatch = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(28 * dp, 28 * dp);
        lp.setMargins(0, 4 * dp, 8 * dp, 4 * dp);
        swatch.setLayoutParams(lp);

        android.graphics.drawable.GradientDrawable unselected = new android.graphics.drawable.GradientDrawable();
        unselected.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        unselected.setColor(color);
        android.graphics.drawable.GradientDrawable selected = new android.graphics.drawable.GradientDrawable();
        selected.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        selected.setColor(color);
        selected.setStroke((int) (2.5f * dp), 0xFF5B5BF6);

        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_selected}, selected);
        sld.addState(new int[]{}, unselected);
        swatch.setBackground(sld);
        return swatch;
    }

    /** Deselects every sibling chip in a row (except the given N leading ones) so only `keep` stays selected. */
    private void refreshChipSelection(LinearLayout row, View keep) {
        refreshChipSelection(row, keep, 0);
    }

    private void refreshChipSelection(LinearLayout row, View keep, int skipFirstN) {
        if (row == null) return;
        for (int i = skipFirstN; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            child.setSelected(child == keep);
        }
    }

    /**
     * ✅ CHANGED: was a bare AlertDialog with RGB seekbars — now reuses the
     * shared :core rainbow bottom sheet (same GRID / SPECTRUM / SLIDERS
     * picker as Status's highlight ring color and Chat's MediaEditActivity),
     * so Step 2's "+" custom-colour swatch matches the rest of the app.
     */
    private void showCustomColorPicker() {
        String currentHex = String.format("#%06X", (0xFFFFFF & currentTextColor));
        com.callx.app.utils.RainbowStripColorPickerBottomSheet.show(
                this, "Custom colour", currentHex, false,
                hex -> {
                    if (hex == null) return;
                    try {
                        currentTextColor = Color.parseColor(hex);
                        setupAdvancedTextOverlayPanel();
                        applyLiveStyleToSelection();
                    } catch (Exception ignored) { /* keep previous color on parse failure */ }
                });
    }

    private Typeface resolvePreviewTypeface(String fontKey, boolean bold, boolean italic) {
        Typeface base;
        if ("serif".equals(fontKey)) base = Typeface.SERIF;
        else if ("mono".equals(fontKey)) base = Typeface.MONOSPACE;
        else if ("condensed".equals(fontKey)) base = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
        else base = Typeface.SANS_SERIF;

        int style = Typeface.NORMAL;
        if (bold && italic) style = Typeface.BOLD_ITALIC;
        else if (bold) style = Typeface.BOLD;
        else if (italic) style = Typeface.ITALIC;
        return Typeface.create(base, style);
    }

    /** Creates a brand-new draggable/pinch-scale/rotate text overlay using the current style-panel settings. */
    private void createAdvancedTextOverlay(String text) {
        FrameLayout fl = getVideoOverlayLayer();
        if (fl == null) return;
        int dp = (int) getResources().getDisplayMetrics().density;

        TextOverlayStyle style = new TextOverlayStyle();
        style.text = text;
        style.fontKey = currentFontKey;
        style.bold = currentBold;
        style.italic = currentItalic;
        style.bgStyle = currentBgStyle;
        style.align = currentAlign;
        style.colorInt = currentTextColor;
        style.sizeSp = currentTextSizeSp;

        TextView tv = new TextView(this);
        tv.setTag(style);
        applyStyleToView(tv, style, dp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        tv.setLayoutParams(lp);
        fl.addView(tv);
        textOverlayViews.add(tv);
        makeTextOverlayInteractive(tv, fl);

        tv.setScaleX(0.3f); tv.setScaleY(0.3f); tv.setAlpha(0f);
        tv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).start();

        selectTextOverlay(tv);
        scheduleStickerJsonMerge();
        updateBadge("text_overlay", "🔤 Text (" + textOverlayViews.size() + ")");
    }

    private void applyStyleToView(TextView tv, TextOverlayStyle style, int dp) {
        tv.setText(style.text);
        tv.setTextSize(style.sizeSp);
        tv.setTypeface(resolvePreviewTypeface(style.fontKey, style.bold, style.italic));
        tv.setGravity("left".equals(style.align) ? Gravity.START
            : "right".equals(style.align) ? Gravity.END : Gravity.CENTER);

        boolean highlight = "highlight".equals(style.bgStyle);
        int textColor = style.colorInt;
        if (highlight) {
            double luminance = 0.299 * Color.red(style.colorInt) + 0.587 * Color.green(style.colorInt) + 0.114 * Color.blue(style.colorInt);
            textColor = luminance > 150 ? Color.BLACK : Color.WHITE;
        }
        tv.setTextColor(textColor);
        tv.setPadding(10 * dp, 6 * dp, 10 * dp, 6 * dp);

        // Perf: only allocate a GradientDrawable when a background is actually
        // needed — "none" is a common style pick and previously paid for a
        // throwaway GradientDrawable on every single style change regardless.
        if ("none".equals(style.bgStyle)) {
            tv.setShadowLayer(4f, 0f, 2f, 0x99000000);
            tv.setBackground(null);
        } else {
            tv.setShadowLayer(0f, 0f, 0f, 0);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            if (highlight) {
                bg.setColor(style.colorInt);
                bg.setCornerRadius(6f * dp);
            } else if ("solid".equals(style.bgStyle)) {
                bg.setColor(0xEE000000);
                bg.setCornerRadius(6f * dp);
            } else { // pill
                bg.setColor(0x66000000);
                bg.setCornerRadius(999f);
            }
            tv.setBackground(bg);
        }
    }

    /** Live-restyles the currently selected overlay whenever a chip/slider changes, matching the panel exactly. */
    private void applyLiveStyleToSelection() {
        if (selectedTextOverlay == null) return;
        Object tag = selectedTextOverlay.getTag();
        if (!(tag instanceof TextOverlayStyle)) return;
        TextOverlayStyle style = (TextOverlayStyle) tag;
        style.fontKey = currentFontKey;
        style.bold = currentBold;
        style.italic = currentItalic;
        style.bgStyle = currentBgStyle;
        style.align = currentAlign;
        style.colorInt = currentTextColor;
        style.sizeSp = currentTextSizeSp;
        int dp = (int) getResources().getDisplayMetrics().density;
        applyStyleToView(selectedTextOverlay, style, dp);
        // Interactive path (chip taps, seekbar drag ticks) — coalesced, not synchronous.
        scheduleStickerJsonMerge();
    }

    private void selectTextOverlay(TextView tv) {
        selectedTextOverlay = tv;
        Object tag = tv.getTag();
        if (tag instanceof TextOverlayStyle) {
            TextOverlayStyle style = (TextOverlayStyle) tag;
            currentFontKey = style.fontKey;
            currentBold = style.bold;
            currentItalic = style.italic;
            currentBgStyle = style.bgStyle;
            currentAlign = style.align;
            currentTextColor = style.colorInt;
            currentTextSizeSp = style.sizeSp;
            setupAdvancedTextOverlayPanel();
        }
        for (TextView other : textOverlayViews) {
            other.setAlpha(other == tv ? 1f : 0.85f);
        }
        if (btnDeleteTextOverlay != null) btnDeleteTextOverlay.setVisibility(View.VISIBLE);
    }

    private void deselectTextOverlay() {
        selectedTextOverlay = null;
        for (TextView other : textOverlayViews) other.setAlpha(1f);
        if (btnDeleteTextOverlay != null) btnDeleteTextOverlay.setVisibility(View.GONE);
    }

    private void deleteSelectedTextOverlay() {
        if (selectedTextOverlay == null) return;
        removeTextOverlay(selectedTextOverlay);
    }

    private void removeTextOverlay(TextView tv) {
        FrameLayout fl = getVideoOverlayLayer();
        tv.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(160)
            .withEndAction(() -> { if (fl != null) fl.removeView(tv); }).start();
        textOverlayViews.remove(tv);
        if (selectedTextOverlay == tv) deselectTextOverlay();
        scheduleStickerJsonMerge();
        updateBadge("text_overlay", textOverlayViews.isEmpty() ? null : "🔤 Text (" + textOverlayViews.size() + ")");
    }

    /**
     * Single-finger drag to move, two-finger pinch to scale + twist to rotate,
     * tap to select (re-opens the style panel pre-filled with this overlay's
     * current style), drag onto the bottom trash icon to delete.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void makeTextOverlayInteractive(TextView tv, FrameLayout fl) {
        final float[] startTouch = new float[2];
        final float[] startPos   = new float[2];
        final float[] startSpan  = new float[1];
        final float[] startAngle = new float[1];
        final float[] startScale = new float[1];
        final float[] startRotation = new float[1];
        final boolean[] moved = {false};
        final boolean[] twoFinger = {false};
        // Perf: trash-zone hit testing used to call View.getLocationOnScreen()
        // (a full view-hierarchy transform walk) for BOTH the trash icon and the
        // dragged overlay on every single ACTION_MOVE pixel — at drag speed that's
        // 100+ hierarchy walks/sec. The trash icon and the overlay's parent frame
        // don't move mid-gesture, so their screen offsets only need computing once,
        // at ACTION_DOWN; every ACTION_MOVE after that is pure arithmetic.
        final int[]   reusableLoc   = new int[2];
        final float[] trashCenter   = new float[2]; // screen-space, cached per gesture
        final float[] flOffset      = new float[2]; // screen-space top-left of the overlay's parent
        final boolean[] trashCached = {false};

        tv.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    moved[0] = false;
                    twoFinger[0] = false;
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    startPos[0] = v.getX();
                    startPos[1] = v.getY();
                    trashCached[0] = false;
                    if (tvTextTrashZone != null && fl != null) {
                        tvTextTrashZone.getLocationOnScreen(reusableLoc);
                        trashCenter[0] = reusableLoc[0] + tvTextTrashZone.getWidth() / 2f;
                        trashCenter[1] = reusableLoc[1] + tvTextTrashZone.getHeight() / 2f;
                        fl.getLocationOnScreen(reusableLoc);
                        flOffset[0] = reusableLoc[0];
                        flOffset[1] = reusableLoc[1];
                        trashCached[0] = true;
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() == 2) {
                        twoFinger[0] = true;
                        startSpan[0] = pointerSpacing(event);
                        startAngle[0] = pointerAngle(event);
                        startScale[0] = v.getScaleX();
                        startRotation[0] = v.getRotation();
                        if (tvTextTrashZone != null) tvTextTrashZone.setVisibility(View.GONE);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (twoFinger[0] && event.getPointerCount() >= 2) {
                        float span = pointerSpacing(event);
                        float angle = pointerAngle(event);
                        float scale = startScale[0] * (span / Math.max(startSpan[0], 1f));
                        scale = Math.max(0.4f, Math.min(4f, scale));
                        v.setScaleX(scale);
                        v.setScaleY(scale);
                        v.setRotation(startRotation[0] + (angle - startAngle[0]));
                        moved[0] = true;
                    } else if (event.getPointerCount() == 1) {
                        float dx = event.getRawX() - startTouch[0];
                        float dy = event.getRawY() - startTouch[1];
                        if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved[0] = true;
                        v.setX(startPos[0] + dx);
                        v.setY(startPos[1] + dy);
                        if (moved[0] && tvTextTrashZone != null && trashCached[0]) {
                            tvTextTrashZone.setVisibility(View.VISIBLE);
                            boolean over = isOverTrashZoneFast(v, flOffset, trashCenter);
                            tvTextTrashZone.setAlpha(over ? 1f : 0.5f);
                        }
                    }
                    return true;

                case MotionEvent.ACTION_POINTER_UP:
                    // Recapture single-pointer baseline so the remaining finger doesn't jump.
                    startTouch[0] = event.getRawX();
                    startTouch[1] = event.getRawY();
                    startPos[0] = v.getX();
                    startPos[1] = v.getY();
                    return true;

                case MotionEvent.ACTION_UP:
                    if (tvTextTrashZone != null) tvTextTrashZone.setVisibility(View.GONE);
                    if (!moved[0]) {
                        selectTextOverlay(tv);
                    } else if (!twoFinger[0] && tvTextTrashZone != null && trashCached[0]
                            && isOverTrashZoneFast(v, flOffset, trashCenter)) {
                        removeTextOverlay(tv);
                    } else {
                        selectTextOverlay(tv);
                        syncOverlayPositionIntoStyle(tv, fl);
                        scheduleStickerJsonMerge();
                    }
                    return true;
            }
            return false;
        });
    }

    private float pointerSpacing(MotionEvent event) {
        float dx = event.getX(0) - event.getX(1);
        float dy = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float pointerAngle(MotionEvent event) {
        float dx = event.getX(1) - event.getX(0);
        float dy = event.getY(1) - event.getY(0);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /**
     * Zero-allocation, zero-syscall trash-zone hit test used during active drag.
     * `flOffset`/`trashCenter` are pre-cached once per gesture (see
     * makeTextOverlayInteractive's ACTION_DOWN) — this just does arithmetic on
     * the overlay's already-known local X/Y, so it's safe to call on every
     * ACTION_MOVE without any per-frame cost.
     */
    private boolean isOverTrashZoneFast(View overlay, float[] flOffset, float[] trashCenter) {
        float overlayCx = flOffset[0] + overlay.getX() + overlay.getWidth() / 2f;
        float overlayCy = flOffset[1] + overlay.getY() + overlay.getHeight() / 2f;
        float dist = (float) Math.hypot(overlayCx - trashCenter[0], overlayCy - trashCenter[1]);
        return dist < tvTextTrashZone.getWidth();
    }

    /** Stores the overlay's current normalised (0..1) position + rotation/scale into its style tag. */
    private void syncOverlayPositionIntoStyle(TextView tv, FrameLayout fl) {
        if (fl == null || fl.getWidth() == 0 || fl.getHeight() == 0) return;
        Object tag = tv.getTag();
        if (!(tag instanceof TextOverlayStyle)) return;
        // Position/rotation/scale are read directly off the view at export time
        // (see mergeTextOverlaysIntoStickerJson), so nothing else to do here —
        // this hook exists for symmetry / future per-overlay persistence needs.
    }

    /** Joins every active overlay's text (used only as a caption-prefill fallback). */
    private String getAllTextOverlaysJoined() {
        StringBuilder sb = new StringBuilder();
        for (TextView tv : textOverlayViews) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(tv.getText());
        }
        return sb.toString();
    }

    /**
     * Fast-path entry point for interactive edits (drag/pinch/chip taps/seekbar
     * ticks). Coalesces bursts of these into one rebuild instead of doing the
     * full O(n) rebuild (string building + JSON re-parse) on every single tick —
     * a seekbar drag alone can fire this dozens of times a second. proceedToUpload()
     * bypasses this and calls mergeTextOverlaysIntoStickerJson() directly for a
     * guaranteed-fresh synchronous flush before the value is ever read.
     */
    private void scheduleStickerJsonMerge() {
        handler.removeCallbacks(stickerJsonMergeRunnable);
        handler.postDelayed(stickerJsonMergeRunnable, STICKER_JSON_MERGE_DEBOUNCE_MS);
    }

    /**
     * Rebuilds the "type":"text" entries inside stickerJson from every live
     * overlay's current position/rotation/scale/style — preserving any
     * non-text entries already in stickerJson (emoji stickers, interactive
     * cards) untouched. This is what makes Step 2 overlays both hard-bake
     * into the exported video (ReelVideoExportEngine) and ride along in the
     * sticker_json extra for playback rendering.
     */
    private void mergeTextOverlaysIntoStickerJson() {
        handler.removeCallbacks(stickerJsonMergeRunnable);
        FrameLayout fl = getVideoOverlayLayer();
        int flWidth = (fl != null) ? fl.getWidth() : 0;
        int flHeight = (fl != null) ? fl.getHeight() : 0;

        // Preserve any non-text entries already present (emoji/interactive stickers)
        // before we start overwriting the buffer with the rebuilt text entries.
        List<String> preservedNonText = null;
        for (String obj : splitJsonObjects(stickerJson)) {
            if (!obj.contains("\"type\":\"text\"")) {
                if (preservedNonText == null) preservedNonText = new ArrayList<>();
                preservedNonText.add(obj);
            }
        }

        StringBuilder sb = new StringBuilder(64 + textOverlayViews.size() * 160);
        sb.append('[');
        boolean first = true;
        for (TextView tv : textOverlayViews) {
            if (tv.getParent() == null) continue;
            Object tag = tv.getTag();
            if (!(tag instanceof TextOverlayStyle)) continue;
            TextOverlayStyle style = (TextOverlayStyle) tag;

            float xFrac = 0.5f, yFrac = 0.5f;
            if (flWidth > 0 && flHeight > 0) {
                xFrac = (tv.getX() + tv.getWidth() / 2f) / flWidth;
                yFrac = (tv.getY() + tv.getHeight() / 2f) / flHeight;
            }

            if (!first) sb.append(',');
            first = false;
            sb.append("{\"type\":\"text\",\"value\":\"");
            appendJsonEscaped(sb, tv.getText());
            sb.append("\",\"x\":").append(xFrac)
              .append(",\"y\":").append(yFrac)
              .append(",\"color\":\"");
            appendColorHex(sb, style.colorInt);
            sb.append("\",\"font\":\"").append(style.fontKey)
              .append("\",\"bold\":").append(style.bold)
              .append(",\"italic\":").append(style.italic)
              .append(",\"bg\":\"").append(style.bgStyle)
              .append("\",\"align\":\"").append(style.align)
              .append("\",\"size\":").append(style.sizeSp)
              .append(",\"rotation\":").append(tv.getRotation())
              .append(",\"scale\":").append(tv.getScaleX())
              .append('}');
        }
        if (preservedNonText != null) {
            for (String obj : preservedNonText) {
                if (!first) sb.append(',');
                first = false;
                sb.append(obj);
            }
        }
        sb.append(']');
        stickerJson = sb.toString();
    }

    /** Appends `text` JSON-escaped straight into `sb` — avoids the 3 intermediate
     *  String allocations that chained .replace() calls would otherwise produce. */
    private static void appendJsonEscaped(StringBuilder sb, CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                default:   sb.append(c);
            }
        }
    }

    private static final char[] HEX_DIGITS = "0123456789ABCDEF".toCharArray();

    /** Manual #RRGGBB hex formatting — avoids String.format's Locale/Formatter
     *  allocation overhead, which otherwise runs once per overlay per merge. */
    private static void appendColorHex(StringBuilder sb, int colorInt) {
        sb.append('#');
        for (int shift = 20; shift >= 0; shift -= 4) {
            sb.append(HEX_DIGITS[(colorInt >> shift) & 0xF]);
        }
    }

    /** Splits a top-level JSON array string into its individual {...} object substrings. */
    private List<String> splitJsonObjects(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.length() < 2) return result;
        String inner = json.trim();
        if (inner.startsWith("[")) inner = inner.substring(1, inner.length() - 1);
        int depth = 0, start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    result.add(inner.substring(start, i + 1));
                    start = i + 1;
                    while (start < inner.length() && inner.charAt(start) == ',') start++;
                }
            }
        }
        return result;
    }


    /**
     * Show subtitle preview at the bottom of the video.
     * Displays the first subtitle line as a representative preview.
     */
    private void applySubtitlePreview(String json, boolean enabled, int fontSize) {
        if (tvSubtitlePreview == null) return;
        if (!enabled || json.isEmpty()) {
            tvSubtitlePreview.setVisibility(View.GONE);
            updateBadge("subtitle", null);
            return;
        }
        // Extract first caption text from JSON array
        // Format: [{"text":"Caption text","start":0,"end":3000},...]
        String firstCaption = "";
        try {
            int tStart = json.indexOf("\"text\":\"") + 8;
            int tEnd   = json.indexOf("\"", tStart);
            if (tStart > 7 && tEnd > tStart) firstCaption = json.substring(tStart, tEnd);
        } catch (Exception ignored) {}

        if (firstCaption.isEmpty()) firstCaption = "Subtitles active";

        tvSubtitlePreview.setText(firstCaption);
        tvSubtitlePreview.setTextSize(fontSize);
        tvSubtitlePreview.setVisibility(View.VISIBLE);
        updateBadge("subtitle", "💬 Subtitles");
    }

    /**
     * Show/update a badge chip in the badge strip.
     * tag: unique key (e.g. "filter", "voice", "transition")
     * label: chip text, or null to remove the badge
     */
    private void updateBadge(String tag, String label) {
        if (badgeStrip == null) return;
        // Remove existing badge with this tag
        for (int i = badgeStrip.getChildCount() - 1; i >= 0; i--) {
            View child = badgeStrip.getChildAt(i);
            if (tag.equals(child.getTag())) {
                badgeStrip.removeViewAt(i);
            }
        }
        if (label == null || label.isEmpty()) {
            if (badgeStrip.getChildCount() == 0) badgeStrip.setVisibility(View.GONE);
            return;
        }

        int dp = (int) getResources().getDisplayMetrics().density;
        TextView chip = new TextView(this);
        chip.setTag(tag);
        chip.setText(label);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(11);
        chip.setPadding(8 * dp, 4 * dp, 8 * dp, 4 * dp);
        chip.setBackgroundColor(0xCC9B59B6); // purple chip background
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(4 * dp);

        // Slightly rounded — use outline drawable fallback to a simple color
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0xCC9B59B6);
        gd.setCornerRadius(12 * dp);
        chip.setBackground(gd);

        badgeStrip.addView(chip, lp);
        badgeStrip.setVisibility(View.VISIBLE);
    }

    /**
     * Show the selected thumbnail as a small corner badge and highlight the tool button.
     */
    private void applyThumbnailBadge(String path) {
        if (ivThumbBadge == null || path.isEmpty()) return;
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path);
            if (bmp != null) {
                ivThumbBadge.setImageBitmap(bmp);
                ivThumbBadge.setVisibility(View.VISIBLE);
                // Rounded border
                android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
                border.setStroke(3, Color.WHITE);
                border.setCornerRadius(8 * (int) getResources().getDisplayMetrics().density);
                ivThumbBadge.setForeground(border);
            }
        } catch (Exception ignored) {}
    }

    // ── Metadata & player ─────────────────────────────────────────────────

    private void loadMetadata() {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if (isFilePath) mmr.setDataSource(videoUriStr);
            else            mmr.setDataSource(this, Uri.parse(videoUriStr));
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) totalDurationMs = Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        trimStartMs = 0;
        trimEndMs   = totalDurationMs;
        tvDuration.setText(formatMs(totalDurationMs));
        tvTrimStart.setText("0:00");
        tvTrimEnd.setText(formatMs(totalDurationMs));
        if (trimFilmstripView != null) {
            trimFilmstripView.setDuration(totalDurationMs);
            trimFilmstripView.setTrimRange(0, totalDurationMs);
            trimFilmstripView.loadThumbnails(this, videoUriStr, isFilePath, totalDurationMs);
        }
    }

    /**
     * Bug fix: Step 3 → Filters used to hand ReelFiltersActivity the raw video
     * URI as if it were a still image (`EXTRA_THUMBNAIL_URI` → `ImageView.
     * setImageURI()`), which silently fails since an ImageView can't decode
     * video — so the Filters & Adjust screen opened with no preview at all.
     * This grabs an actual frame off the video (at the current playhead, same
     * MediaMetadataRetriever pattern used by ReelThumbnailPickerActivity),
     * saves it as a temp JPEG, and hands that over via a FileProvider URI —
     * plus the currently-applied filter/slider values so re-opening Filters
     * continues from where the user left off instead of resetting to Normal.
     */
    private void openFiltersScreen() {
        if (videoUriStr == null || videoUriStr.isEmpty()) return;
        final long frameAtMs = (player != null) ? Math.max(0, player.getCurrentPosition()) : trimStartMs;

        filterPreviewExecutor.execute(() -> {
            Uri previewUri = extractFrameAsProviderUri(frameAtMs);
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Intent i = new Intent(ReelEditorActivity.this, ReelFiltersActivity.class);
                if (previewUri != null) {
                    i.putExtra(ReelFiltersActivity.EXTRA_THUMBNAIL_URI, previewUri.toString());
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } else {
                    // Extraction failed (corrupt frame, odd codec, etc.) — still open
                    // the screen so filter/slider values can be picked; just no preview.
                    Toast.makeText(ReelEditorActivity.this,
                        "Couldn't load a preview frame — filters will still apply to the video",
                        Toast.LENGTH_SHORT).show();
                }
                if (!filterName.isEmpty()) {
                    i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_FILTER,     filterName);
                    i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_BRIGHTNESS, filterBrightness);
                    i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_CONTRAST,   filterContrast);
                    i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_SATURATION, filterSaturation);
                    i.putExtra(ReelFiltersActivity.EXTRA_CURRENT_BEAUTY,     filterBeauty);
                }
                startActivityForResult(i, REQ_FILTERS);
            });
        });
    }

    /** Runs off the UI thread. Extracts one frame near `atMs`, writes it to a
     *  cache file, and returns a content:// URI for it via the app's existing
     *  FileProvider — or null if extraction fails for any reason. */
    private Uri extractFrameAsProviderUri(long atMs) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        Bitmap frame = null;
        try {
            if (isFilePath) mmr.setDataSource(videoUriStr);
            else             mmr.setDataSource(this, Uri.parse(videoUriStr));
            frame = mmr.getFrameAtTime(atMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                // Fall back to the very first frame if the playhead position fails to decode.
                frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) return null;

            File dir = new File(getCacheDir(), "filter_preview");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, "frame_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                frame.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            }
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", out);
        } catch (Exception e) {
            return null;
        } finally {
            if (frame != null) frame.recycle();
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    /**
     * ✅ CHANGED: Step 1 · Trim and Crop → Crop button.
     * Used to only grab the current playhead frame and crop *that still
     * image* for use as a custom thumbnail. Now reuses :core's
     * {@link MediaCropActivity} in its new video mode (EXTRA_VIDEO_URI)
     * instead, which crops the *entire video* via Media3 Transformer — a
     * real reframe of the reel, not just its thumbnail. It's the exact same
     * shared crop screen Chat's MediaEditActivity uses (same aspect chips,
     * same drag handles), just launched in video mode here.
     */
    private void openCropScreen() {
        if (videoUriStr == null || videoUriStr.isEmpty()) return;
        Uri uri = isFilePath ? Uri.fromFile(new File(videoUriStr)) : Uri.parse(videoUriStr);

        Intent i = new Intent(this, MediaCropActivity.class);
        i.putExtra(MediaCropActivity.EXTRA_VIDEO_URI, uri.toString());
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i, REQ_CROP);
    }

    /**
     * ✅ CHANGED: the cropped result from MediaCropActivity is now a
     * permanently-cropped .mp4 for the whole reel (not a cropped still).
     * Swaps it in as the working video, reloads the player on the new
     * source, and refreshes the trim filmstrip + thumbnail so both reflect
     * the newly-cropped frame.
     */
    private void handleCropResult(String croppedVideoUriStr) {
        if (croppedVideoUriStr == null || croppedVideoUriStr.isEmpty()) return;
        try {
            videoUriStr = Uri.parse(croppedVideoUriStr).getPath();
            isFilePath  = true;

            if (player != null) {
                try { player.release(); } catch (Exception ignored) {}
                player = null;
            }
            setupPlayer();
            loadMetadata();          // refresh duration + trim filmstrip for the cropped video
            regenerateThumbnailFromCurrentVideo();

            Toast.makeText(this, "Crop applied ✓", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Crop failed to apply", Toast.LENGTH_SHORT).show();
        }
    }

    /** Re-extracts a thumbnail/cover frame from whatever videoUriStr currently points
     *  to — used after a crop so the thumbnail badge reflects the new framing instead
     *  of a stale frame captured from the pre-crop video. */
    private void regenerateThumbnailFromCurrentVideo() {
        final long frameAtMs = (player != null) ? Math.max(0, player.getCurrentPosition()) : trimStartMs;
        filterPreviewExecutor.execute(() -> {
            Uri previewUri = extractFrameAsProviderUri(frameAtMs);
            handler.post(() -> {
                if (isFinishing() || isDestroyed() || previewUri == null) return;
                try {
                    File dir = new File(getCacheDir(), "reel_crop");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, "crop_" + System.currentTimeMillis() + ".jpg");
                    try (InputStream in = getContentResolver().openInputStream(previewUri);
                         FileOutputStream fos = new FileOutputStream(out)) {
                        if (in == null) throw new Exception("Could not open frame");
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    thumbnailPath    = out.getAbsolutePath();
                    thumbnailFrameMs = frameAtMs;
                    applyThumbnailBadge(thumbnailPath);
                } catch (Exception ignored) { /* thumbnail refresh is best-effort */ }
            });
        });
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        Uri uri = isFilePath ? Uri.fromFile(new File(videoUriStr)) : Uri.parse(videoUriStr);
        player.setMediaItem(MediaItem.fromUri(uri));
        // ✅ FIX: repeat mode OFF — looping is handled manually in playheadUpdater
        // so playback loops only within [trimStartMs, trimEndMs], not the whole clip.
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.prepare();
        player.setPlayWhenReady(true);
        // Apply recording speed (from camera) as soon as player is created
        if (cameraSpeed != 1.0f) {
            player.setPlaybackParameters(
                new androidx.media3.common.PlaybackParameters(cameraSpeed));
        }

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (progressBuffering != null)
                    progressBuffering.setVisibility(
                        state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                updatePlayPauseIcon();

                // "Use in Video" gallery flow: auto-open the audio mixer once the
                // player has buffered enough to start, so the user can balance
                // "Original video audio" vs "Reused sound" volumes immediately.
                if (state == Player.STATE_READY
                        && openAudioMixerOnLoad
                        && !mixerAutoOpened
                        && !preSelectedSoundUrl.isEmpty()) {
                    mixerAutoOpened = true;
                    // Small delay so the UI is fully settled before opening mixer
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        () -> {
                            if (!isFinishing() && !isDestroyed()) {
                                Intent mi = new Intent(ReelEditorActivity.this,
                                        ReelAudioMixerActivity.class);
                                mi.putExtra(ReelAudioMixerActivity.EXTRA_VIDEO_URI,    videoUriStr);
                                mi.putExtra(ReelAudioMixerActivity.EXTRA_IS_FILE_PATH, isFilePath);
                                mi.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_URL,    preSelectedSoundUrl);
                                mi.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_TITLE,  preSelectedSoundTitle);
                                mi.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_ARTIST, "");
                                // ✅ FIX: pass soundId here too so the auto-opened mixer
                                // returns a valid RESULT_MUSIC_ID when the user applies.
                                mi.putExtra(ReelAudioMixerActivity.EXTRA_SOUND_ID,     preSelectedSoundId);
                                startActivityForResult(mi, REQ_AUDIO_MIXER);
                            }
                        }, 600);
                }
            }
            @Override public void onIsPlayingChanged(boolean p) { updatePlayPauseIcon(); }

            // ✅ FIX: fires as soon as the real video track dimensions are known
            // (works for any source aspect ratio, not just 9:16). Resizes the
            // rounded container to exactly match the video's own aspect ratio
            // instead of leaving it filling the whole placeholder box.
            @Override public void onVideoSizeChanged(androidx.media3.common.VideoSize videoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return;
                float pxRatio = videoSize.pixelWidthHeightRatio > 0
                        ? videoSize.pixelWidthHeightRatio : 1f;
                applyVideoAspectToPreviewContainer(
                        videoSize.width * pxRatio, videoSize.height);
            }
        });

        startPlayheadUpdater();
    }

    /**
     * Resizes {@link #videoPreviewContainer} (the rounded/clipped box) so its
     * bounds exactly match the video's aspect ratio — width x height in the
     * same units, e.g. pixels reported by ExoPlayer's VideoSize — while fitting
     * within the space available inside {@link #videoPreviewOuter}. This makes
     * the rounded corners sit directly on the video's own edges, in whatever
     * ratio the video actually is, instead of rounding a fixed placeholder box
     * that may be larger than the video (leaving square corners visible where
     * the video used to letterbox inside it).
     */
    private void applyVideoAspectToPreviewContainer(float videoW, float videoH) {
        if (videoPreviewOuter == null || videoPreviewContainer == null) return;
        if (videoW <= 0 || videoH <= 0) return;
        final float videoAspect = videoW / videoH; // width / height

        videoPreviewOuter.post(() -> {
            int outerW = videoPreviewOuter.getWidth();
            int outerH = videoPreviewOuter.getHeight();
            if (outerW <= 0 || outerH <= 0) return;

            int marginPx = (int) (10 * getResources().getDisplayMetrics().density);
            int availW = Math.max(1, outerW - marginPx * 2);
            int availH = Math.max(1, outerH - marginPx * 2);
            float availAspect = (float) availW / availH;

            int targetW, targetH;
            if (videoAspect > availAspect) {
                // Video is relatively wider than the available box → width-constrained.
                targetW = availW;
                targetH = Math.round(availW / videoAspect);
            } else {
                // Video is relatively taller → height-constrained.
                targetH = availH;
                targetW = Math.round(availH * videoAspect);
            }

            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) videoPreviewContainer.getLayoutParams();
            if (lp.width == targetW && lp.height == targetH) return; // no-op, avoid relayout churn
            lp.width = targetW;
            lp.height = targetH;
            lp.gravity = Gravity.CENTER;
            videoPreviewContainer.setLayoutParams(lp);
        });
    }

    /**
     * Polls player position every ~150ms so the filmstrip's blue playhead line tracks
     * playback, and — ✅ FIX — loops preview playback within [trimStartMs, trimEndMs]
     * only, instead of the whole source video, so the preview always matches exactly
     * what the trim handles show and what gets uploaded.
     */
    /**
     * Polls player position every ~33ms (~30fps) so the filmstrip's blue playhead line
     * tracks playback smoothly — ✅ FIX: was 150ms (≈6-7fps), which made the line visibly
     * jump/stutter instead of gliding — and loops preview playback within
     * [trimStartMs, trimEndMs] only, instead of the whole source video, so the preview
     * always matches exactly what the trim handles show and what gets uploaded.
     */
    private final Runnable playheadUpdater = new Runnable() {
        @Override public void run() {
            if (player != null) {
                long pos = player.getCurrentPosition();
                if (trimFilmstripView != null) {
                    trimFilmstripView.setPlayheadPosition(pos);
                }
                if (trimEndMs > trimStartMs && pos >= trimEndMs) {
                    player.seekTo(trimStartMs);
                }
            }
            handler.postDelayed(this, 33);
        }
    };

    private void startPlayheadUpdater() {
        handler.removeCallbacks(playheadUpdater);
        handler.post(playheadUpdater);
    }

    // ── Listener setup ────────────────────────────────────────────────────

    /**
     * ✅ NEW: Splits the "Editing tools" panel into a step-by-step wizard
     * (Trim → Text Overlay → Tools in groups of 3) instead of everything on
     * one screen — same pattern as the Reel Upload screen. This is
     * independent of {@code btnNext}, which still moves on to the Upload
     * screen once editing is done.
     */
    private void setupEditorStepWizard() {
        editorStepFlipper = findViewById(R.id.editor_step_flipper);
        tvEditorStepTitle = findViewById(R.id.tv_editor_step_title);
        tvEditorStepName = findViewById(R.id.tv_editor_step_name);
        editorStepDots  = new TextView[] {
                findViewById(R.id.step_dot_1), findViewById(R.id.step_dot_2),
                findViewById(R.id.step_dot_3), findViewById(R.id.step_dot_4),
                findViewById(R.id.step_dot_5)
        };
        editorStepLines = new View[] {
                findViewById(R.id.step_line_1), findViewById(R.id.step_line_2),
                findViewById(R.id.step_line_3), findViewById(R.id.step_line_4)
        };
        // ✅ NEW: vibrant-green spinning ring behind whichever step dot is
        // currently in use — shared :core drawable/color, see updateActiveEditorStepRing().
        editorStepRings = new ImageView[] {
                findViewById(R.id.step_ring_1), findViewById(R.id.step_ring_2),
                findViewById(R.id.step_ring_3), findViewById(R.id.step_ring_4),
                findViewById(R.id.step_ring_5)
        };
        btnEditorStepBack = findViewById(R.id.btn_editor_step_back);
        btnEditorStepNext = findViewById(R.id.btn_editor_step_next);

        if (editorStepFlipper == null) return; // layout not the wizard version — no-op safety

        if (btnEditorStepBack != null) {
            btnEditorStepBack.setOnClickListener(v -> goToEditorStep(editorCurrentStep - 1));
        }
        if (btnEditorStepNext != null) {
            btnEditorStepNext.setOnClickListener(v -> goToEditorStep(editorCurrentStep + 1));
        }
        // ✅ NEW: tapping a step dot jumps back to an already-visited step;
        // tapping ahead does nothing — only btnEditorStepNext may move forward.
        // Shared across every stepper screen — see StepDotsNavigationHelper.
        com.callx.app.utils.StepDotsNavigationHelper.bindStepDots(editorStepDots,
            new com.callx.app.utils.StepDotsNavigationHelper.StepNavigator() {
                @Override public int getCurrentStep() { return editorCurrentStep; }
                @Override public void goToStep(int step) { goToEditorStep(step); }
            });
        updateEditorStepUi();
    }

    private void goToEditorStep(int step) {
        if (editorStepFlipper == null) return;
        if (step < 0 || step >= EDITOR_STEP_TITLES.length) return;
        editorCurrentStep = step;
        editorStepFlipper.setDisplayedChild(editorCurrentStep);
        updateEditorStepUi();
    }

    private void updateEditorStepUi() {
        // Pill (tv_editor_step_title) shows just "Step X of Y"; the step name
        // (Trim / Text Overlay / etc.) is shown separately in tv_editor_step_name,
        // uppercase, next to the pill — EDITOR_STEP_TITLES[...] itself is left
        // untouched since its .length is still used for step-count bounds checks
        // above; EDITOR_STEP_NAMES holds the plain-case names for the label.
        if (tvEditorStepTitle != null) {
            tvEditorStepTitle.setText(getString(R.string.editor_step_pill_format,
                    editorCurrentStep + 1, EDITOR_STEP_TITLES.length));
        }
        if (tvEditorStepName != null && editorCurrentStep < EDITOR_STEP_NAMES.length) {
            tvEditorStepName.setText(EDITOR_STEP_NAMES[editorCurrentStep]);
        }
        updateEditorStepDots();
        if (btnEditorStepBack != null) {
            btnEditorStepBack.setVisibility(editorCurrentStep == 0 ? View.INVISIBLE : View.VISIBLE);
        }
        boolean isLastStep = editorCurrentStep == EDITOR_STEP_TITLES.length - 1;
        if (btnEditorStepNext != null) {
            btnEditorStepNext.setVisibility(isLastStep ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /**
     * Reused from NewStatusActivity's updateWizardProgress() / ReelUploadActivity's
     * updateStepDots() — same dot stepper visuals (numbered circle turns
     * brand-gradient once its step is reached/passed, connecting line to its
     * right fills solid, everything ahead stays the neutral "not yet" grey) —
     * just sized for this wizard's 5 steps.
     */
    private void updateEditorStepDots() {
        if (editorStepDots == null) return;
        for (int i = 0; i < editorStepDots.length; i++) {
            if (editorStepDots[i] == null) continue;
            boolean active = i <= editorCurrentStep;
            editorStepDots[i].setBackgroundResource(active
                    ? com.callx.app.core.R.drawable.bg_trim_gradient_button
                    : com.callx.app.core.R.drawable.bg_trim_circle_btn);
            editorStepDots[i].setTextColor(androidx.core.content.ContextCompat.getColor(this,
                    active ? com.callx.app.core.R.color.white
                           : com.callx.app.core.R.color.trim_text_secondary));
        }
        if (editorStepLines == null) return;
        // ✅ FIX: completed segments now use bg_step_line_gradient (the same
        // brand gradient as the active step dot) instead of a flat solid
        // color, so consecutive completed lines read as one continuous
        // gradient strip joining the step dots, instead of separate flat bars.
        for (int i = 0; i < editorStepLines.length; i++) {
            if (editorStepLines[i] == null) continue;
            if (editorCurrentStep >= i + 1) {
                editorStepLines[i].setBackgroundResource(R.drawable.bg_step_line_gradient);
            } else {
                editorStepLines[i].setBackgroundColor(androidx.core.content.ContextCompat.getColor(this,
                        com.callx.app.core.R.color.trim_divider));
            }
        }
        updateActiveEditorStepRing();
    }

    /**
     * ✅ NEW: spins a vibrant-green ring behind the step dot currently in
     * use (editorCurrentStep) — mirrors ReelUploadActivity's updateActiveStepRing().
     */
    private void updateActiveEditorStepRing() {
        if (editorStepRings == null) return;
        if (editorActiveStepRingSpin != null) {
            editorActiveStepRingSpin.cancel();
            editorActiveStepRingSpin = null;
        }
        for (int i = 0; i < editorStepRings.length; i++) {
            if (editorStepRings[i] == null) continue;
            if (i == editorCurrentStep) {
                editorStepRings[i].setVisibility(View.VISIBLE);
                editorStepRings[i].setRotation(0f);
                editorActiveStepRingSpin = ObjectAnimator.ofFloat(
                        editorStepRings[i], View.ROTATION, 0f, 360f);
                editorActiveStepRingSpin.setDuration(1400);
                editorActiveStepRingSpin.setRepeatCount(ObjectAnimator.INFINITE);
                editorActiveStepRingSpin.setInterpolator(new android.view.animation.LinearInterpolator());
                editorActiveStepRingSpin.start();
            } else {
                editorStepRings[i].setVisibility(View.GONE);
            }
        }
    }

    /** Physical/gesture back steps backward through the editing-tools wizard first. */
    @Override
    public void onBackPressed() {
        if (editorStepFlipper != null && editorCurrentStep > 0) {
            goToEditorStep(editorCurrentStep - 1);
            return;
        }
        goBackOrToMediaEdit();
    }

    /**
     * Backing out of this screen without completing the edit (X button, or
     * physical back once already at wizard step 0). Normally this just
     * cancels back to whoever opened this screen. But when
     * allowMediaEditFallback is set — i.e. this screen was opened directly
     * on an already-picked video via Status's attach-sheet pencil/Edit
     * action — it instead opens MediaEditActivity ("media editing screen")
     * on that same video, and this screen forwards MediaEditActivity's own
     * result straight through as its own (handleMediaEditFallbackResult()),
     * so Status sees exactly the same result shape it would have if the
     * pencil had opened MediaEditActivity in the first place.
     */
    private void goBackOrToMediaEdit() {
        if (allowMediaEditFallback && videoUriStr != null && !videoUriStr.isEmpty()) {
            Intent intent = new Intent();
            intent.setClassName(getPackageName(), "com.callx.app.conversation.controllers.MediaEditActivity");
            java.util.ArrayList<String> uriStrings = new java.util.ArrayList<>();
            uriStrings.add(videoUriStr);
            java.util.ArrayList<Integer> videoFlags = new java.util.ArrayList<>();
            videoFlags.add(1); // always a video on this fallback path
            intent.putStringArrayListExtra("media_edit_uris", uriStrings);
            intent.putIntegerArrayListExtra("media_edit_is_video", videoFlags);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) != null) {
                mediaEditFallbackLauncher.launch(intent);
                return;
            }
            // feature-chat not present on this build — fall through to plain cancel.
        }
        finish();
    }

    /** Transparently forwards MediaEditActivity's result (whatever it is —
     *  edited-and-saved or cancelled) back to whoever launched THIS screen. */
    private void handleMediaEditFallbackResult(androidx.activity.result.ActivityResult result) {
        setResult(result.getResultCode(), result.getData());
        finish();
    }

    /**
     * Mirrors ReelUploadActivity.runAudioMixThenUpload()'s AudioMixHelper call
     * (same MixConfig fields, same mix step) for the chat "Advance Editing"
     * exit path, which never passes through ReelUploadActivity itself. Mixes
     * the picked sound/voiceover into the video's actual audio track, then
     * continues on to MediaEditActivity with the mixed file.
     */
    private void runAudioMixThenGoToMediaEdit() {
        android.app.ProgressDialog dialog = new android.app.ProgressDialog(this);
        dialog.setMessage("Mixing audio…");
        dialog.setCancelable(false);
        dialog.setIndeterminate(false);
        dialog.setMax(100);
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        dialog.show();

        // AudioMixHelper's PCM extractor needs a real file path — MediaExtractor
        // can't open a content:// URI directly. When no filter/overlay/trim was
        // applied above, videoUriStr is still the original picked content://
        // URI (isFilePath=false), so copy it to a local cache file first — same
        // fix VideoTrimActivity already applies for the same reason.
        new Thread(() -> {
            String localPath;
            try {
                localPath = isFilePath ? videoUriStr : copyUriToCacheFile(Uri.parse(videoUriStr));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    dialog.dismiss();
                    Toast.makeText(ReelEditorActivity.this,
                        "Couldn't prepare video for audio mix, sending without sound.", Toast.LENGTH_SHORT).show();
                    goBackOrToMediaEdit();
                });
                return;
            }
            String finalLocalPath = localPath;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                AudioMixHelper.MixConfig cfg = new AudioMixHelper.MixConfig();
                cfg.musicUrl       = preSelectedSoundUrl;
                cfg.voiceoverPath  = mixVoiceoverPath != null ? mixVoiceoverPath : "";
                cfg.micVol         = mixOrigVol;
                cfg.musicVol       = mixMusicVol;
                cfg.voiceoverVol   = mixVoiceoverVol;
                cfg.musicStartMs   = musicStartMs;
                cfg.musicEndMs     = musicEndMs;
                cfg.fadeInMs       = mixFadeInMs;
                cfg.fadeOutMs      = mixFadeOutMs;
                cfg.pitchSemitones = mixPitchSemitones;
                cfg.normalize      = mixNormalize;

                AudioMixHelper.mixAndExportWithConfig(ReelEditorActivity.this, finalLocalPath, cfg,
                    new AudioMixHelper.MixCallback() {
                        @Override public void onProgress(int percent) {
                            if (isFinishing() || isDestroyed()) return;
                            dialog.setProgress(percent);
                        }
                        @Override public void onSuccess(String mixedPath) {
                            if (isFinishing() || isDestroyed()) return;
                            dialog.dismiss();
                            videoUriStr = mixedPath;
                            isFilePath  = true;
                            goBackOrToMediaEdit();
                        }
                        @Override public void onError(Exception e) {
                            if (isFinishing() || isDestroyed()) return;
                            dialog.dismiss();
                            Toast.makeText(ReelEditorActivity.this,
                                "Audio mix failed, continuing with original audio.", Toast.LENGTH_SHORT).show();
                            goBackOrToMediaEdit();
                        }
                    });
            });
        }).start();
    }

    /**
     * ✅ FIX: Status counterpart of runAudioMixThenGoToMediaEdit() — same
     * AudioMixHelper mix step, but finishes back to NewStatusActivity via
     * finishForStatusResult() instead of continuing to MediaEditActivity.
     * Also folds the Voice Effects tool's pitch slider (a 0.5x–2.0x ratio,
     * separate from the Audio Mixer's own semitone slider) into the same
     * pitch-shift pass so both tools' pitch choices are honoured together.
     */
    private void runAudioMixThenFinishForStatus(String textOverlay) {
        android.app.ProgressDialog dialog = new android.app.ProgressDialog(this);
        dialog.setMessage("Mixing audio…");
        dialog.setCancelable(false);
        dialog.setIndeterminate(false);
        dialog.setMax(100);
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        dialog.show();

        new Thread(() -> {
            String localPath;
            try {
                localPath = isFilePath ? videoUriStr : copyUriToCacheFile(Uri.parse(videoUriStr));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    dialog.dismiss();
                    Toast.makeText(ReelEditorActivity.this,
                        "Couldn't prepare video for audio mix, posting without sound changes.",
                        Toast.LENGTH_SHORT).show();
                    finishForStatusResult(textOverlay);
                });
                return;
            }
            String finalLocalPath = localPath;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;

                // Voice Effects' pitch is a 0.5–2.0x ratio; convert to semitones
                // and combine with the Audio Mixer's own semitone slider.
                float voicePitchSemitones = (!voiceEffectName.isEmpty() && voicePitch > 0f)
                    ? (float) (12.0 * (Math.log(voicePitch) / Math.log(2.0))) : 0f;

                AudioMixHelper.MixConfig cfg = new AudioMixHelper.MixConfig();
                cfg.musicUrl       = preSelectedSoundUrl;
                cfg.voiceoverPath  = mixVoiceoverPath != null ? mixVoiceoverPath : "";
                cfg.micVol         = mixOrigVol;
                cfg.musicVol       = mixMusicVol;
                cfg.voiceoverVol   = mixVoiceoverVol;
                cfg.musicStartMs   = musicStartMs;
                cfg.musicEndMs     = musicEndMs;
                cfg.fadeInMs       = mixFadeInMs;
                cfg.fadeOutMs      = mixFadeOutMs;
                cfg.pitchSemitones = mixPitchSemitones + voicePitchSemitones;
                cfg.normalize      = mixNormalize;

                AudioMixHelper.mixAndExportWithConfig(ReelEditorActivity.this, finalLocalPath, cfg,
                    new AudioMixHelper.MixCallback() {
                        @Override public void onProgress(int percent) {
                            if (isFinishing() || isDestroyed()) return;
                            dialog.setProgress(percent);
                        }
                        @Override public void onSuccess(String mixedPath) {
                            if (isFinishing() || isDestroyed()) return;
                            dialog.dismiss();
                            videoUriStr = mixedPath;
                            isFilePath  = true;
                            audioAlreadyReplaced = true;
                            finishForStatusResult(textOverlay);
                        }
                        @Override public void onError(Exception e) {
                            if (isFinishing() || isDestroyed()) return;
                            dialog.dismiss();
                            Toast.makeText(ReelEditorActivity.this,
                                "Audio mix failed, posting with original audio.", Toast.LENGTH_SHORT).show();
                            finishForStatusResult(textOverlay);
                        }
                    });
            });
        }).start();
    }

    /** Copies a content:// (or any resolvable) URI to a local cache file — see runAudioMixThenGoToMediaEdit(). */
    private String copyUriToCacheFile(Uri src) throws java.io.IOException {
        File out = new File(getCacheDir(), "chat_mix_src_" + System.currentTimeMillis() + ".mp4");
        try (java.io.InputStream is = getContentResolver().openInputStream(src);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
            if (is == null) throw new java.io.IOException("Cannot open source URI");
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out.getAbsolutePath();
    }

    private void setupListeners() {
        btnBack.setOnClickListener(v -> goBackOrToMediaEdit());

        btnPlayPause.setOnClickListener(v -> {
            if (player != null) {
                if (player.isPlaying()) player.pause(); else player.play();
            }
        });

        // Advanced text overlay Add: creates a brand-new styled overlay from the
        // current text field + whatever style chips are active (see
        // setupAdvancedTextOverlayPanel / createAdvancedTextOverlay below).
        btnAddText.setOnClickListener(v -> {
            String text = etTextOverlay.getText() != null
                ? etTextOverlay.getText().toString().trim() : "";
            if (!text.isEmpty()) {
                createAdvancedTextOverlay(text);
                etTextOverlay.setText("");
            }
        });

        if (btnDeleteTextOverlay != null) {
            btnDeleteTextOverlay.setOnClickListener(v -> deleteSelectedTextOverlay());
        }

        // Tapping empty video area (not on any overlay) deselects the current one.
        if (videoPreviewContainer instanceof FrameLayout) {
            videoPreviewContainer.setOnClickListener(v -> deselectTextOverlay());
        }

        if (trimFilmstripView != null) {
            trimFilmstripView.setOnTrimChangeListener(new com.callx.app.views.VideoTrimFilmstripView.OnTrimChangeListener() {
                @Override public void onTrimChanged(long startMs, long endMs, boolean fromUser) {
                    trimStartMs = startMs;
                    trimEndMs   = endMs;
                    tvTrimStart.setText(formatMs(trimStartMs));
                    tvTrimEnd.setText(formatMs(trimEndMs));
                    if (player != null) player.seekTo(trimStartMs);
                }
                @Override public void onTrimTouchEnd(long startMs, long endMs) {
                    if (player != null) player.seekTo(trimStartMs);
                }
            });
        }

        // ── Tool buttons ─────────────────────────────────────────────────

        if (btnToolFilters != null) btnToolFilters.setOnClickListener(v -> openFiltersScreen());

        if (btnToolStickers != null) btnToolStickers.setOnClickListener(v -> openFullStickerPicker());

        if (btnToolSubtitles != null) btnToolSubtitles.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelSubtitlesActivity.class);
            i.putExtra(ReelSubtitlesActivity.EXTRA_VIDEO_URI,    videoUriStr);
            i.putExtra(ReelSubtitlesActivity.EXTRA_IS_FILE_PATH, isFilePath);
            startActivityForResult(i, REQ_SUBTITLES);
        });

        if (btnToolTransitions != null) btnToolTransitions.setOnClickListener(v -> {
            // ✅ FIX: pass the reel's own media + current trim range so the
            // Transitions screen can show a LIVE preview (same video, looped
            // within [trimStartMs, trimEndMs] just like this screen) instead
            // of a generic icon card, and preselect whatever was chosen before.
            Intent i = new Intent(this, ReelTransitionsActivity.class);
            i.putExtra(ReelTransitionsActivity.EXTRA_MEDIA_URI,    videoUriStr);
            i.putExtra(ReelTransitionsActivity.EXTRA_IS_FILE_PATH, isFilePath);
            i.putExtra(ReelTransitionsActivity.EXTRA_IS_IMAGE,     false);
            i.putExtra(ReelTransitionsActivity.EXTRA_TRIM_START_MS, trimStartMs);
            i.putExtra(ReelTransitionsActivity.EXTRA_TRIM_END_MS,   trimEndMs);
            if (!transitionName.isEmpty()) {
                i.putExtra(ReelTransitionsActivity.EXTRA_SELECTED_NAME,     transitionName);
                i.putExtra(ReelTransitionsActivity.EXTRA_SELECTED_DURATION, transitionDuration);
                i.putExtra(ReelTransitionsActivity.EXTRA_SELECTED_APPLY_ALL, transitionApplyAll);
            }
            startActivityForResult(i, REQ_TRANSITIONS);
        });

        if (btnToolVoice != null) btnToolVoice.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelVoiceEffectsActivity.class);
            i.putExtra(ReelVoiceEffectsActivity.EXTRA_AUDIO_PATH, videoUriStr);
            i.putExtra(ReelVoiceEffectsActivity.EXTRA_IS_FILE_PATH, isFilePath);
            startActivityForResult(i, REQ_VOICE);
        });

        if (btnToolAudioMixer != null) btnToolAudioMixer.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelAudioMixerActivity.class);
            i.putExtra(ReelAudioMixerActivity.EXTRA_VIDEO_URI,    videoUriStr);
            i.putExtra(ReelAudioMixerActivity.EXTRA_IS_FILE_PATH, isFilePath);
            i.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_URL,    preSelectedSoundUrl);
            i.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_TITLE,  preSelectedSoundTitle);
            i.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_ARTIST, "");
            // ✅ FIX: pass soundId so the mixer can open SoundDetailActivity for the
            // currently-selected track and so it returns a valid RESULT_MUSIC_ID on apply.
            i.putExtra(ReelAudioMixerActivity.EXTRA_SOUND_ID,     preSelectedSoundId);
            startActivityForResult(i, REQ_AUDIO_MIXER);
        });

        if (btnToolThumbnail != null) btnToolThumbnail.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelThumbnailPickerActivity.class);
            i.putExtra(ReelThumbnailPickerActivity.EXTRA_VIDEO_URI,    videoUriStr);
            i.putExtra(ReelThumbnailPickerActivity.EXTRA_IS_FILE_PATH, isFilePath);
            startActivityForResult(i, REQ_THUMBNAIL);
        });

        // ✅ NEW: Step 1 · Trim and Crop → Crop button — reuses Chat's Media
        // Editing screen crop feature (core's MediaCropActivity) the exact
        // same way MediaEditActivity's btnEditCrop does.
        if (btnEditorCrop != null) btnEditorCrop.setOnClickListener(v -> openCropScreen());

        // ✅ NEW: Step 2 · Text Overlay — Settings button opens the font/style/
        // bg/colour/size bottom sheet (see openTextOverlayStyleSheet()).
        if (btnTextOverlaySettings != null) btnTextOverlaySettings.setOnClickListener(v -> openTextOverlayStyleSheet());

        // ✅ NEW: Music chip — if a sound is already selected tap → SoundDetail,
        //                       otherwise → MusicPickerActivity to pick one.
        if (btnToolMusic != null) btnToolMusic.setOnClickListener(v -> openMusicChip());

        // Auto-show music badge if a sound was pre-selected from camera screen
        if (!preSelectedSoundTitle.isEmpty()) updateBadge("music", "🎵 " + preSelectedSoundTitle);

        btnNext.setOnClickListener(v -> proceedToUpload());
    }

    // ── onActivityResult — store results AND visually apply ───────────────

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    @androidx.annotation.Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        switch (requestCode) {

            case REQ_FILTERS: {
                filterName       = nvl(data.getStringExtra(ReelFiltersActivity.RESULT_FILTER_NAME));
                filterBrightness = data.getFloatExtra(ReelFiltersActivity.RESULT_BRIGHTNESS,   0f);
                filterContrast   = data.getFloatExtra(ReelFiltersActivity.RESULT_CONTRAST,     1f);
                filterSaturation = data.getFloatExtra(ReelFiltersActivity.RESULT_SATURATION,   1f);
                filterBeauty     = data.getFloatExtra(ReelFiltersActivity.RESULT_BEAUTY_LEVEL, 0f);
                // ✅ VISUALLY APPLY filter overlay on video preview
                applyFilterVisual(filterName, filterBrightness, filterContrast, filterSaturation);
                if (btnToolFilters != null) btnToolFilters.setColorFilter(
                    android.graphics.Color.argb(200, 168, 85, 247)); // purple tint = active
                break;
            }


            case REQ_SUBTITLES: {
                String subs = data.getStringExtra(ReelSubtitlesActivity.RESULT_SUBTITLES_JSON);
                if (subs != null && !subs.isEmpty()) {
                    subtitlesJson     = subs;
                    subtitlesEnabled  = data.getBooleanExtra(ReelSubtitlesActivity.RESULT_ENABLED,   true);
                    subtitlesFontSize = data.getIntExtra(ReelSubtitlesActivity.RESULT_FONT_SIZE,     16);
                    subtitlesStyle    = data.getIntExtra(ReelSubtitlesActivity.RESULT_STYLE,         0);
                    // ✅ VISUALLY APPLY — show subtitle bar at bottom of video
                    applySubtitlePreview(subtitlesJson, subtitlesEnabled, subtitlesFontSize);
                    if (btnToolSubtitles != null) btnToolSubtitles.setColorFilter(
                        android.graphics.Color.WHITE);
                }
                break;
            }

            case REQ_TRANSITIONS: {
                String tName = data.getStringExtra(ReelTransitionsActivity.RESULT_TRANSITION_NAME);
                if (tName != null && !tName.isEmpty()) {
                    transitionName     = tName;
                    transitionDuration = data.getIntExtra(
                        ReelTransitionsActivity.RESULT_TRANSITION_DURATION, 300);
                    transitionApplyAll = data.getBooleanExtra(
                        ReelTransitionsActivity.RESULT_APPLY_ALL, true);
                    // ✅ VISUALLY APPLY — badge chip
                    updateBadge("transition", "⚡ " + transitionName);
                    Toast.makeText(this, "Transition: " + transitionName + " applied ✓",
                        Toast.LENGTH_SHORT).show();
                    if (btnToolTransitions != null) btnToolTransitions.setColorFilter(
                        android.graphics.Color.argb(200, 168, 85, 247));
                }
                break;
            }

            case REQ_VOICE: {
                String vName = data.getStringExtra(ReelVoiceEffectsActivity.RESULT_EFFECT_NAME);
                if (vName != null && !vName.isEmpty()) {
                    voiceEffectName = vName;
                    voicePitch      = data.getFloatExtra(ReelVoiceEffectsActivity.RESULT_PITCH,  1.0f);
                    voiceSpeed      = data.getFloatExtra(ReelVoiceEffectsActivity.RESULT_SPEED,  1.0f);
                    voiceReverb     = data.getFloatExtra(ReelVoiceEffectsActivity.RESULT_REVERB, 0.0f);
                    // ✅ VISUALLY APPLY — badge chip + playback speed hint
                    updateBadge("voice", "🎙 " + voiceEffectName);
                    // Apply speed to player preview (pitch not adjustable via ExoPlayer directly)
                    if (player != null && voiceSpeed != 1.0f) {
                        try {
                            androidx.media3.common.PlaybackParameters pp =
                                new androidx.media3.common.PlaybackParameters(voiceSpeed);
                            player.setPlaybackParameters(pp);
                        } catch (Exception ignored) {}
                    }
                    if (btnToolVoice != null) btnToolVoice.setColorFilter(
                        android.graphics.Color.WHITE);
                }
                break;
            }

            // ✅ NEW: user picked a fresh sound from MusicPickerActivity
            case REQ_MUSIC_PICKER: {
                String pid = nvl(data.getStringExtra("selected_sound_id"));
                String pt  = nvl(data.getStringExtra("selected_sound_title"));
                String pu  = nvl(data.getStringExtra("selected_sound_url"));
                if (!pid.isEmpty()) {
                    preSelectedSoundId    = pid;
                    preSelectedSoundTitle = pt;
                    preSelectedSoundUrl   = pu;
                    updateBadge("music", "🎵 " + pt);
                    if (btnToolMusic != null) btnToolMusic.setColorFilter(
                        android.graphics.Color.argb(200, 255, 100, 180)); // pink tint = active
                }
                break;
            }

            // ✅ NEW: returned from SoundDetailActivity (user may have chosen a different sound)
            case REQ_SOUND_DETAIL: {
                String sid = nvl(data.getStringExtra(SoundDetailActivity.EXTRA_SOUND_ID));
                String st  = nvl(data.getStringExtra(SoundDetailActivity.EXTRA_SOUND_TITLE));
                String su  = nvl(data.getStringExtra(SoundDetailActivity.EXTRA_SOUND_URL));
                if (!sid.isEmpty()) {
                    preSelectedSoundId    = sid;
                    preSelectedSoundTitle = st.isEmpty() ? preSelectedSoundTitle : st;
                    preSelectedSoundUrl   = su.isEmpty() ? preSelectedSoundUrl   : su;
                    updateBadge("music", "🎵 " + preSelectedSoundTitle);
                    if (btnToolMusic != null) btnToolMusic.setColorFilter(
                        android.graphics.Color.argb(200, 255, 100, 180));
                }
                break;
            }

            case REQ_AUDIO_MIXER: {
                mixOrigVol        = data.getFloatExtra(ReelAudioMixerActivity.RESULT_ORIG_VOL,        1.0f);
                mixMusicVol       = data.getFloatExtra(ReelAudioMixerActivity.RESULT_MUSIC_VOL,       0.8f);
                String mvp        = data.getStringExtra(ReelAudioMixerActivity.RESULT_VOICEOVER_PATH);
                mixVoiceoverPath  = mvp != null ? mvp : "";
                mixVoiceoverVol   = data.getFloatExtra(ReelAudioMixerActivity.RESULT_VOICEOVER_VOL,   1.0f);
                mixFadeInMs       = data.getIntExtra(ReelAudioMixerActivity.RESULT_FADE_IN_MS,        0);
                mixFadeOutMs      = data.getIntExtra(ReelAudioMixerActivity.RESULT_FADE_OUT_MS,       0);
                mixPitchSemitones = data.getFloatExtra(ReelAudioMixerActivity.RESULT_PITCH_SEMITONES, 0f);
                mixNormalize      = data.getBooleanExtra(ReelAudioMixerActivity.RESULT_NORMALIZE,     false);
                if (player != null) player.setVolume(mixOrigVol);

                // ✅ FIX: If the user changed the background track inside the mixer
                // (Edit Reel → Audio Mix → Change → Trending Audio → select new sound),
                // the mixer now returns RESULT_MUSIC_URL / _ID / _TITLE.
                // We MUST update preSelectedSoundUrl/Id/Title here; otherwise
                // proceedToUploadInternal() forwards the OLD sound to ReelUploadActivity
                // and the new track is silently ignored — the reel plays the original audio.
                String newMusicUrl    = data.getStringExtra(ReelAudioMixerActivity.RESULT_MUSIC_URL);
                String newMusicId     = data.getStringExtra(ReelAudioMixerActivity.RESULT_MUSIC_ID);
                String newMusicTitle  = data.getStringExtra(ReelAudioMixerActivity.RESULT_MUSIC_TITLE);
                String newMusicArtist = data.getStringExtra(ReelAudioMixerActivity.RESULT_MUSIC_ARTIST);
                if (newMusicUrl != null && !newMusicUrl.isEmpty()) {
                    preSelectedSoundUrl   = newMusicUrl;
                    if (newMusicId    != null && !newMusicId.isEmpty())    preSelectedSoundId    = newMusicId;
                    if (newMusicTitle != null && !newMusicTitle.isEmpty()) preSelectedSoundTitle = newMusicTitle;
                    // Show the updated track name on the music badge
                    String badgeLabel = (newMusicTitle != null && !newMusicTitle.isEmpty())
                        ? newMusicTitle : preSelectedSoundTitle;
                    updateBadge("music", "🎵 " + badgeLabel);
                }

                updateBadge("audio", "🎛 Audio Mix");
                Toast.makeText(this, "Audio mix applied ✓", Toast.LENGTH_SHORT).show();
                break;
            }

            case REQ_THUMBNAIL: {
                String tPath = data.getStringExtra(ReelThumbnailPickerActivity.RESULT_THUMB_PATH);
                if (tPath != null && !tPath.isEmpty()) {
                    thumbnailPath    = tPath;
                    thumbnailFrameMs = data.getLongExtra(
                        ReelThumbnailPickerActivity.RESULT_THUMB_FRAME_MS, 0);
                    // ✅ VISUALLY APPLY — show thumb as corner badge
                    applyThumbnailBadge(thumbnailPath);
                    Toast.makeText(this, "Thumbnail set ✓", Toast.LENGTH_SHORT).show();
                    if (btnToolThumbnail != null) btnToolThumbnail.setColorFilter(
                        android.graphics.Color.WHITE);
                }
                break;
            }

            case REQ_CROP: {
                String croppedUriStr = data.getStringExtra(MediaCropActivity.RESULT_CROPPED_URI);
                handleCropResult(croppedUriStr);
                break;
            }
        }
    }

    // ── Music chip ────────────────────────────────────────────────────────

    /**
     * ✅ NEW: Called when the user taps the music chip / tool button.
     * - If a sound is already pre-selected → open SoundDetailActivity so they can
     *   view stats, see reels using this sound, or change it from there.
     * - If no sound is selected → open MusicPickerActivity to choose one.
     */
    private void triggerBeatSyncAnalysis() {
        if (preSelectedSoundUrl.isEmpty() || !isFilePath
                || videoUriStr == null || videoUriStr.isEmpty()) return;
        com.callx.app.views.BeatSyncAnalyzer.analyze(
            this, videoUriStr, totalDurationMs > 0 ? totalDurationMs : 60_000L,
            new com.callx.app.views.BeatSyncAnalyzer.Callback() {
                @Override public void onBeatsReady(long[] beats) {
                    beatTimesMs = beats;
                    if (!isFinishing() && beats != null && beats.length > 0) {
                        updateBadge("beat", beats.length + " beats");
                        Toast.makeText(ReelEditorActivity.this,
                            "Beat sync: " + beats.length + " beats detected",
                            Toast.LENGTH_SHORT).show();
                    }
                }
                @Override public void onError(Exception e) {
                    android.util.Log.w("ReelEditor", "Beat sync failed", e);
                }
            });
    }

    private void openMusicChip() {
        if (!preSelectedSoundId.isEmpty()) {
            // Sound already chosen → show its detail page
            Intent i = new Intent(this, SoundDetailActivity.class);
            i.putExtra(SoundDetailActivity.EXTRA_SOUND_ID,    preSelectedSoundId);
            i.putExtra(SoundDetailActivity.EXTRA_SOUND_TITLE, preSelectedSoundTitle);
            i.putExtra(SoundDetailActivity.EXTRA_SOUND_URL,   preSelectedSoundUrl);
            startActivityForResult(i, REQ_SOUND_DETAIL);
        } else {
            // No sound yet → open picker
            startActivityForResult(
                new Intent(this, MusicPickerActivity.class), REQ_MUSIC_PICKER);
        }
    }

    // ── Proceed to upload ─────────────────────────────────────────────────

    private void proceedToUpload() {
        // ✅ NEW: fold all advanced Step-2 text overlays (with their font/color/
        // bg/align/rotation/scale styling) into stickerJson BEFORE anything below
        // reads it — this is what makes them both hard-bake into the exported
        // video pixels (runHardBakeExport) AND ride along in the sticker_json
        // extra for playback rendering.
        mergeTextOverlaysIntoStickerJson();

        // ✅ FIX: Status has no separate subtitle-burn step (unlike this same
        // editor's other exit paths, which never had one either — see
        // mergeSubtitleCaptionIntoOverlay()'s javadoc). Merge the first caption
        // line into stickerJson BEFORE the hasOverlays check below so it rides
        // along on the existing text-overlay hard-bake instead of being
        // silently dropped when this screen was opened from Status.
        if (targetStatus) mergeSubtitleCaptionIntoOverlay();

        boolean hasFilter   = !filterName.isEmpty() && !filterName.equals("Normal");
        boolean hasOverlays = !stickerJson.isEmpty();
        // ✅ FIX: previously only filter/overlays triggered a re-encode, so a user
        // who only adjusted the trim handles still had the FULL original video
        // uploaded (trimStartMs/trimEndMs were sent to ReelUploadActivity but never
        // read there). Now a real trim range also triggers the bake step.
        boolean hasTrim = totalDurationMs > 0 && trimEndMs > trimStartMs
            && (trimStartMs > 0 || trimEndMs < totalDurationMs);

        // ✅ NEW: If a filter, text/sticker overlay, or a trim range is active and we
        // have a local file, burn them into the actual video pixels (Media3 Transformer)
        // before uploading — this is also what makes the uploaded video length match
        // exactly what the trim preview showed.
        if (isFilePath && (hasFilter || hasOverlays || hasTrim) && videoUriStr != null && !videoUriStr.isEmpty()) {
            runHardBakeExport(hasTrim);
            return;
        }
        proceedToUploadInternal();
    }

    /** Re-encodes the video with the selected filter + text/stickers (+ trim range) baked in, then continues to upload. */
    private void runHardBakeExport(boolean hasTrim) {
        android.app.ProgressDialog dialog = new android.app.ProgressDialog(this);
        dialog.setMessage(hasTrim ? "Trimming & applying edits…" : "Applying filter & overlays…");
        dialog.setCancelable(false);
        dialog.setIndeterminate(false);
        dialog.setMax(100);
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        dialog.show();

        java.util.List<ReelVideoExportEngine.OverlayItem> overlays =
            ReelVideoExportEngine.parseOverlayJsonArray(stickerJson);

        long exportTrimStart = hasTrim ? trimStartMs : 0L;
        long exportTrimEnd   = hasTrim ? trimEndMs   : 0L;

        ReelVideoExportEngine.export(this, videoUriStr, filterName,
            filterBrightness, filterContrast, filterSaturation, overlays,
            exportTrimStart, exportTrimEnd,
            new ReelVideoExportEngine.ExportCallback() {
                @Override public void onProgress(int percent) {
                    if (percent >= 0) dialog.setProgress(percent);
                }
                @Override public void onSuccess(String outputPath) {
                    if (isFinishing() || isDestroyed()) return;
                    dialog.dismiss();
                    videoUriStr = outputPath;
                    isFilePath  = true;
                    if (hasTrim) {
                        // The exported file IS the trimmed range now, starting at 0 —
                        // reset so downstream extras/UI don't re-apply the old offsets.
                        totalDurationMs = trimEndMs - trimStartMs;
                        trimStartMs = 0;
                        trimEndMs   = totalDurationMs;
                        trimBakedIntoFile = true;
                    }
                    Toast.makeText(ReelEditorActivity.this,
                        hasTrim ? "Trim & edits applied ✓" : "Filter & overlays applied ✓",
                        Toast.LENGTH_SHORT).show();
                    proceedToUploadInternal();
                }
                @Override public void onError(Exception e) {
                    if (isFinishing() || isDestroyed()) return;
                    dialog.dismiss();
                    Toast.makeText(ReelEditorActivity.this,
                        "Couldn't apply edits, uploading original video.", Toast.LENGTH_SHORT).show();
                    // Fall back to original file — upload proceeds without hard-baked effects.
                    proceedToUploadInternal();
                }
            });
    }

    private void proceedToUploadInternal() {
        // Caption fallback: join all active text overlays' text (used to
        // prefill ReelUploadActivity's caption field when relevant).
        String textOverlay = getAllTextOverlaysJoined();

        // NEW: when this screen was opened directly on an already-picked video
        // via Status's pencil/Edit action (allowMediaEditFallback), Done takes
        // the SAME route as backing out does — on to MediaEditActivity ("media
        // editing screen") on this video — instead of finishing straight back
        // to Status itself. Both exits from this screen now land in the same
        // place; only MediaEditActivity's own Save/Post ever returns to Status.
        if (allowMediaEditFallback) {
            boolean hasMusicTrack = !preSelectedSoundUrl.isEmpty();
            boolean hasVoiceover  = mixVoiceoverPath != null && !mixVoiceoverPath.isEmpty();
            // Chat's Advance Editing flow has no ReelUploadActivity step after
            // this screen (unlike Status/Reels, which run this exact mix via
            // ReelUploadActivity.runAudioMixThenUpload right before posting) —
            // so a picked sound/voiceover would otherwise silently never reach
            // the video that actually gets sent in chat. Run the same
            // AudioMixHelper mix ourselves before handing off to
            // MediaEditActivity. See runAudioMixThenGoToMediaEdit().
            if (!audioAlreadyReplaced && (hasMusicTrack || hasVoiceover)
                    && videoUriStr != null && !videoUriStr.isEmpty()) {
                runAudioMixThenGoToMediaEdit();
                return;
            }
            goBackOrToMediaEdit();
            return;
        }

        // Status flow (record-new-video camera chain) — hand the finished
        // (already filter/overlay-baked, if applicable) video straight back
        // to NewStatusActivity via setResult instead of opening
        // ReelUploadActivity. All the camera/editor features the person used
        // (speed, filters, effects, stickers, text, sound pick) are carried
        // across in the result extras below.
        if (targetStatus) {
            // ✅ FIX: Audio Mixer (music/voiceover/fade/normalize/pitch) and
            // Voice Effects (pitch) previously only got baked into the real
            // audio track on the Reels/chat exit paths (ReelUploadActivity's
            // runAudioMixThenUpload / this screen's own
            // runAudioMixThenGoToMediaEdit for chat) — the Status exit never
            // ran the mix at all, so picking a track/voiceover/pitch here did
            // nothing to the video actually posted as a Status. Run the same
            // AudioMixHelper step used for chat before finishing.
            boolean hasMusicTrack   = !preSelectedSoundUrl.isEmpty();
            boolean hasVoiceover    = mixVoiceoverPath != null && !mixVoiceoverPath.isEmpty();
            boolean hasMixTweaks    = Math.abs(mixPitchSemitones) > 0.01f || mixNormalize
                || mixFadeInMs > 0 || mixFadeOutMs > 0;
            boolean hasVoiceEffect  = !voiceEffectName.isEmpty() && Math.abs(voicePitch - 1.0f) > 0.01f;
            if (!audioAlreadyReplaced && (hasMusicTrack || hasVoiceover || hasMixTweaks || hasVoiceEffect)
                    && videoUriStr != null && !videoUriStr.isEmpty()) {
                runAudioMixThenFinishForStatus(textOverlay);
                return;
            }
            finishForStatusResult(textOverlay);
            return;
        }

        Intent intent = new Intent(this, ReelUploadActivity.class);
        intent.putExtra(ReelUploadActivity.EXTRA_VIDEO_URI,    videoUriStr);
        intent.putExtra(ReelUploadActivity.EXTRA_IS_FILE_PATH, isFilePath);
        intent.putExtra(ReelUploadActivity.EXTRA_TRIM_START,   trimStartMs);
        intent.putExtra(ReelUploadActivity.EXTRA_TRIM_END,     trimEndMs);
        intent.putExtra(ReelUploadActivity.EXTRA_TRIM_ALREADY_BAKED, trimBakedIntoFile);
        intent.putExtra(ReelUploadActivity.EXTRA_TEXT_OVERLAY, textOverlay);

        if (!preSelectedSoundId.isEmpty())
            intent.putExtra(ReelUploadActivity.EXTRA_SOUND_ID,    preSelectedSoundId);
        if (!preSelectedSoundTitle.isEmpty())
            intent.putExtra(ReelUploadActivity.EXTRA_SOUND_TITLE, preSelectedSoundTitle);
        if (!preSelectedSoundUrl.isEmpty())
            intent.putExtra(ReelUploadActivity.EXTRA_SOUND_URL,   preSelectedSoundUrl);

        // FIX: forward camera-stage replacement flag → upload will skip a second mix
        if (audioAlreadyReplaced)
            intent.putExtra("audio_already_replaced", true);

        // Audio mix
        intent.putExtra("mix_orig_vol",        mixOrigVol);
        intent.putExtra("mix_music_vol",       mixMusicVol);
        intent.putExtra("mix_voiceover_path",  mixVoiceoverPath);
        intent.putExtra("mix_voiceover_vol",   mixVoiceoverVol);
        intent.putExtra("mix_fade_in_ms",      mixFadeInMs);
        intent.putExtra("mix_fade_out_ms",     mixFadeOutMs);
        intent.putExtra("mix_pitch_semitones", mixPitchSemitones);
        intent.putExtra("mix_normalize",       mixNormalize);
        if (musicStartMs > 0) intent.putExtra("music_start_ms", musicStartMs);
        if (musicEndMs   > 0) intent.putExtra("music_end_ms",   musicEndMs);

        // Filter
        if (!filterName.isEmpty()) {
            intent.putExtra("filter_name",       filterName);
            intent.putExtra("filter_brightness", filterBrightness);
            intent.putExtra("filter_contrast",   filterContrast);
            intent.putExtra("filter_saturation", filterSaturation);
            intent.putExtra("filter_beauty",     filterBeauty);
        }

        // Sticker
        if (!stickerJson.isEmpty())
            intent.putExtra("sticker_json", stickerJson);

        // Subtitles
        if (!subtitlesJson.isEmpty()) {
            intent.putExtra("subtitles_json",      subtitlesJson);
            intent.putExtra("subtitles_enabled",   subtitlesEnabled);
            intent.putExtra("subtitles_font_size", subtitlesFontSize);
            intent.putExtra("subtitles_style",     subtitlesStyle);
        }

        // Transitions
        if (!transitionName.isEmpty()) {
            intent.putExtra("transition_name",      transitionName);
            intent.putExtra("transition_duration",  transitionDuration);
            intent.putExtra("transition_apply_all", transitionApplyAll);
        }

        // Voice
        if (!voiceEffectName.isEmpty()) {
            intent.putExtra("voice_effect_name", voiceEffectName);
            intent.putExtra("voice_pitch",       voicePitch);
            intent.putExtra("voice_speed",       voiceSpeed);
            intent.putExtra("voice_reverb",      voiceReverb);
        }

        // Camera recording speed (0.3x – 3x, normal = 1.0)
        if (cameraSpeed != 1.0f) {
            intent.putExtra("camera_speed", cameraSpeed);
        }

        // Thumbnail
        if (!thumbnailPath.isEmpty()) {
            intent.putExtra("thumbnail_path",     thumbnailPath);
            intent.putExtra("thumbnail_frame_ms", thumbnailFrameMs);
        }

        // Duet
        if (isDuet) {
            intent.putExtra(ReelUploadActivity.EXTRA_IS_DUET,           true);
            intent.putExtra(ReelUploadActivity.EXTRA_DUET_ORIGINAL_ID,  duetOriginalId);
            intent.putExtra(ReelUploadActivity.EXTRA_DUET_ORIGINAL_URL, duetOriginalUrl);
            intent.putExtra(ReelUploadActivity.EXTRA_DUET_OWNER_UID,    duetOwnerUid);
            intent.putExtra(ReelUploadActivity.EXTRA_DUET_LABEL,        duetLabel);
            // Multi-duet session
            if (!multiDuetSessionId.isEmpty()) {
                intent.putExtra("multi_duet_session_id", multiDuetSessionId);
                intent.putExtra("multi_duet_slot",       multiDuetSlot);
                intent.putExtra("multi_duet_total",      multiDuetTotal);
            }
        }

        startActivity(intent);
    }

    /**
     * NEW: Status flow terminus — packages the finished video (already
     * hard-baked with filter/sticker/text pixels if runHardBakeExport ran)
     * plus every other camera/editor feature the person used, and hands it
     * back to NewStatusActivity as an activity result. Mirrors
     * proceedToUploadInternal()'s extras so nothing the person picked in the
     * camera/editor gets silently dropped — Status decides what to do with
     * each piece (video preview, caption prefill, music sticker, etc).
     */
    private void finishForStatusResult(String textOverlay) {
        Intent result = new Intent();
        result.putExtra("video_uri",     videoUriStr);
        result.putExtra("is_file_path",  isFilePath);
        result.putExtra("text_overlay",  textOverlay);

        if (!preSelectedSoundId.isEmpty())    result.putExtra("selected_sound_id",    preSelectedSoundId);
        if (!preSelectedSoundTitle.isEmpty()) result.putExtra("selected_sound_title", preSelectedSoundTitle);
        if (!preSelectedSoundUrl.isEmpty())   result.putExtra("selected_sound_url",   preSelectedSoundUrl);

        if (!filterName.isEmpty())   result.putExtra("filter_name",   filterName);
        if (!stickerJson.isEmpty())  result.putExtra("sticker_json",  stickerJson);
        if (cameraSpeed != 1.0f)     result.putExtra("camera_speed",  cameraSpeed);
        // ✅ FIX: thumbnail_frame_ms was never forwarded, so NewStatusActivity
        // had no way to tell a real picked frame apart from a leftover 0
        // default — see NewStatusActivity#handleReelCameraResult.
        if (!thumbnailPath.isEmpty()) {
            result.putExtra("thumbnail_path",     thumbnailPath);
            result.putExtra("thumbnail_frame_ms", thumbnailFrameMs);
        }

        setResult(RESULT_OK, result);
        finish();
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private void updatePlayPauseIcon() {
        if (btnPlayPause == null || player == null) return;
        btnPlayPause.setImageResource(
            player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private String formatMs(long ms) {
        long sec = ms / 1000;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    private static String nvl(String s) { return s != null ? s : ""; }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            try { player.stop(); }    catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        handler.removeCallbacksAndMessages(null);
        filterPreviewExecutor.shutdownNow();
        if (editorActiveStepRingSpin != null) editorActiveStepRingSpin.cancel();
        super.onDestroy();
    }
}
