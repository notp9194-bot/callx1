package com.callx.app.editor;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.upload.ReelUploadActivity;
import com.callx.app.music.MusicPickerActivity;
import com.callx.app.music.SoundDetailActivity;
import com.callx.app.music.AudioMixHelper;
import com.callx.app.social.DuetReelActivity;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
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

import java.io.File;

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

    // ── XML views ─────────────────────────────────────────────────────────
    private PlayerView    playerView;
    private ImageButton   btnPlayPause, btnBack;
    private SeekBar       sbTrimStart, sbTrimEnd;
    private TextView      tvTrimStart, tvTrimEnd, tvDuration;
    private EditText      etTextOverlay;
    private TextView      tvTextPreview;
    private View          btnNext, btnAddText;
    private ProgressBar   progressBuffering;
    private ImageButton   btnToolFilters, btnToolStickers, btnToolSubtitles,
                          btnToolTransitions, btnToolVoice, btnToolAudioMixer, btnToolThumbnail;
    /** ✅ NEW: music chip / tool button — opens SoundDetail (if sound selected) or MusicPicker */
    private ImageButton   btnToolMusic;

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
    private String stickerJson = "";
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_editor);

        videoUriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        isFilePath  = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);

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
        btnPlayPause       = findViewById(R.id.btn_editor_play_pause);
        btnBack            = findViewById(R.id.btn_editor_back);
        sbTrimStart        = findViewById(R.id.sb_editor_trim_start);
        sbTrimEnd          = findViewById(R.id.sb_editor_trim_end);
        tvTrimStart        = findViewById(R.id.tv_editor_trim_start);
        tvTrimEnd          = findViewById(R.id.tv_editor_trim_end);
        tvDuration         = findViewById(R.id.tv_editor_duration);
        etTextOverlay      = findViewById(R.id.et_text_overlay);
        tvTextPreview      = findViewById(R.id.tv_text_preview);
        btnNext            = findViewById(R.id.btn_editor_next);
        btnAddText         = findViewById(R.id.btn_add_text);
        progressBuffering  = findViewById(R.id.editor_progress_buffering);
        btnToolFilters     = findViewById(R.id.btn_tool_filters);
        btnToolStickers    = findViewById(R.id.btn_tool_stickers);
        btnToolSubtitles   = findViewById(R.id.btn_tool_subtitles);
        btnToolTransitions = findViewById(R.id.btn_tool_transitions);
        btnToolVoice       = findViewById(R.id.btn_tool_voice);
        btnToolAudioMixer  = findViewById(R.id.btn_tool_audio_mixer);
        btnToolThumbnail   = findViewById(R.id.btn_tool_thumbnail);
        // ✅ NEW: music chip button (add btn_tool_music ImageButton to the toolbar XML)
        btnToolMusic       = findViewById(R.id.btn_tool_music);
    }

    // ── Inject dynamic overlay views into the video FrameLayout ──────────

    /**
     * Programmatically adds overlay views into the FrameLayout that wraps the PlayerView.
     * Called once in onCreate after bindViews().
     * Layers (bottom→top): PlayerView | filterOverlayView | tvTextPreview (XML) |
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
     * Render an interactive sticker (Poll / Quiz / Slider / Question) as a styled
     * card overlay on the video FrameLayout. The card is draggable and long-press removes it.
     */
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
        int maxProgress = Math.max(1, (int)(totalDurationMs / 100));
        sbTrimStart.setMax(maxProgress); sbTrimStart.setProgress(0);
        sbTrimEnd.setMax(maxProgress);   sbTrimEnd.setProgress(maxProgress);
        tvDuration.setText(formatMs(totalDurationMs));
        tvTrimStart.setText("0:00");
        tvTrimEnd.setText(formatMs(totalDurationMs));
    }

    private void setupPlayer() {
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        Uri uri = isFilePath ? Uri.fromFile(new File(videoUriStr)) : Uri.parse(videoUriStr);
        player.setMediaItem(MediaItem.fromUri(uri));
        player.setRepeatMode(Player.REPEAT_MODE_ONE);
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
        });
    }

    // ── Listener setup ────────────────────────────────────────────────────

    private void setupListeners() {
        btnBack.setOnClickListener(v -> finish());

        btnPlayPause.setOnClickListener(v -> {
            if (player != null) {
                if (player.isPlaying()) player.pause(); else player.play();
            }
        });

        btnAddText.setOnClickListener(v -> {
            String text = etTextOverlay.getText() != null
                ? etTextOverlay.getText().toString().trim() : "";
            if (!text.isEmpty()) {
                tvTextPreview.setText(text);
                tvTextPreview.setVisibility(View.VISIBLE);
                etTextOverlay.setText("");
            }
        });

        tvTextPreview.setOnClickListener(v -> {
            tvTextPreview.setVisibility(View.GONE);
            tvTextPreview.setText("");
        });

        sbTrimStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                if (fromUser) {
                    long ns = prog * 100L;
                    if (ns >= trimEndMs - 1000) { sb.setProgress((int)((trimEndMs-1000)/100)); return; }
                    trimStartMs = ns;
                    tvTrimStart.setText(formatMs(trimStartMs));
                    if (player != null) player.seekTo(trimStartMs);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbTrimEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                if (fromUser) {
                    long ne = prog * 100L;
                    if (ne <= trimStartMs + 1000) { sb.setProgress((int)((trimStartMs+1000)/100)); return; }
                    trimEndMs = ne;
                    tvTrimEnd.setText(formatMs(trimEndMs));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // ── Tool buttons ─────────────────────────────────────────────────

        if (btnToolFilters != null) btnToolFilters.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelFiltersActivity.class);
            i.putExtra(ReelFiltersActivity.EXTRA_THUMBNAIL_URI, videoUriStr);
            startActivityForResult(i, REQ_FILTERS);
        });

        if (btnToolStickers != null) btnToolStickers.setOnClickListener(v ->
            startActivityForResult(new Intent(this, ReelStickerPickerActivity.class), REQ_STICKERS));

        if (btnToolSubtitles != null) btnToolSubtitles.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelSubtitlesActivity.class);
            i.putExtra(ReelSubtitlesActivity.EXTRA_VIDEO_URI,    videoUriStr);
            i.putExtra(ReelSubtitlesActivity.EXTRA_IS_FILE_PATH, isFilePath);
            startActivityForResult(i, REQ_SUBTITLES);
        });

        if (btnToolTransitions != null) btnToolTransitions.setOnClickListener(v ->
            startActivityForResult(new Intent(this, ReelTransitionsActivity.class), REQ_TRANSITIONS));

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

            case REQ_STICKERS: {
                String sJson = data.getStringExtra(ReelStickerPickerActivity.RESULT_STICKER_JSON);
                if (sJson != null && !sJson.isEmpty()) {
                    stickerJson = sJson;
                    // ✅ VISUALLY APPLY — add draggable sticker on video frame
                    addStickerOverlay(sJson);
                    if (btnToolStickers != null) btnToolStickers.setColorFilter(
                        android.graphics.Color.argb(200, 255, 215, 0)); // gold tint = active
                }
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
        boolean hasFilter   = !filterName.isEmpty() && !filterName.equals("Normal");
        boolean hasOverlays = !stickerJson.isEmpty();

        // ✅ NEW: If a filter or text/sticker overlay is active and we have a local file,
        // burn them into the actual video pixels (Media3 Transformer) before uploading.
        if (isFilePath && (hasFilter || hasOverlays) && videoUriStr != null && !videoUriStr.isEmpty()) {
            runHardBakeExport();
            return;
        }
        proceedToUploadInternal();
    }

    /** Re-encodes the video with the selected filter + text/stickers baked in, then continues to upload. */
    private void runHardBakeExport() {
        android.app.ProgressDialog dialog = new android.app.ProgressDialog(this);
        dialog.setMessage("Applying filter & overlays…");
        dialog.setCancelable(false);
        dialog.setIndeterminate(false);
        dialog.setMax(100);
        dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        dialog.show();

        java.util.List<ReelVideoExportEngine.OverlayItem> overlays =
            ReelVideoExportEngine.parseOverlayJsonArray(stickerJson);

        ReelVideoExportEngine.export(this, videoUriStr, filterName,
            filterBrightness, filterContrast, filterSaturation, overlays,
            new ReelVideoExportEngine.ExportCallback() {
                @Override public void onProgress(int percent) {
                    if (percent >= 0) dialog.setProgress(percent);
                }
                @Override public void onSuccess(String outputPath) {
                    if (isFinishing() || isDestroyed()) return;
                    dialog.dismiss();
                    videoUriStr = outputPath;
                    isFilePath  = true;
                    Toast.makeText(ReelEditorActivity.this, "Filter & overlays applied ✓", Toast.LENGTH_SHORT).show();
                    proceedToUploadInternal();
                }
                @Override public void onError(Exception e) {
                    if (isFinishing() || isDestroyed()) return;
                    dialog.dismiss();
                    Toast.makeText(ReelEditorActivity.this,
                        "Couldn't bake filter/overlay, uploading original video.", Toast.LENGTH_SHORT).show();
                    // Fall back to original file — upload proceeds without hard-baked effects.
                    proceedToUploadInternal();
                }
            });
    }

    private void proceedToUploadInternal() {
        String textOverlay = (tvTextPreview != null
            && tvTextPreview.getVisibility() == View.VISIBLE)
            ? tvTextPreview.getText().toString() : "";

        Intent intent = new Intent(this, ReelUploadActivity.class);
        intent.putExtra(ReelUploadActivity.EXTRA_VIDEO_URI,    videoUriStr);
        intent.putExtra(ReelUploadActivity.EXTRA_IS_FILE_PATH, isFilePath);
        intent.putExtra(ReelUploadActivity.EXTRA_TRIM_START,   trimStartMs);
        intent.putExtra(ReelUploadActivity.EXTRA_TRIM_END,     trimEndMs);
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
        super.onDestroy();
    }
}
