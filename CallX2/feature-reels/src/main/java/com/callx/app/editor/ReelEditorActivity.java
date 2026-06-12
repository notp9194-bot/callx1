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

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
 * ReelEditorActivity v14 — Editing tools fixed for duet + normal reels.
 *
 * Fixes over v13:
 *  ✅ FIX: Migrated ALL tool launches from deprecated startActivityForResult to
 *     ActivityResultLauncher (registerForActivityResult). Fixes result-drop on
 *     Android 13/14 when called from duet flow.
 *  ✅ FIX: duet_root_id now forwarded to ReelUploadActivity in proceedToUpload().
 *  ✅ FIX: Editor bottom panel wrapped in ScrollView — tools accessible on all
 *     screen sizes (previously cut off on small phones).
 *  ✅ FIX: Toast confirmation added for every tool result so user knows it applied.
 *  ✅ All v13 visual apply logic retained (filter overlay, sticker drag, subtitle bar,
 *     badge strip, thumbnail corner badge).
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ReelEditorActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI           = "editor_video_uri";
    public static final String EXTRA_IS_FILE_PATH        = "is_file_path";
    public static final String EXTRA_IS_DUET             = "editor_is_duet";
    public static final String EXTRA_DUET_ORIGINAL_ID    = "editor_duet_original_id";
    public static final String EXTRA_DUET_ORIGINAL_URL   = "editor_duet_original_url";
    public static final String EXTRA_DUET_OWNER_UID      = "editor_duet_owner_uid";
    public static final String EXTRA_DUET_LABEL          = "editor_duet_label";

    // ── ActivityResultLaunchers (replaces deprecated startActivityForResult) ──
    private ActivityResultLauncher<Intent> launcherFilters;
    private ActivityResultLauncher<Intent> launcherStickers;
    private ActivityResultLauncher<Intent> launcherSubtitles;
    private ActivityResultLauncher<Intent> launcherTransitions;
    private ActivityResultLauncher<Intent> launcherVoice;
    private ActivityResultLauncher<Intent> launcherAudioMixer;
    private ActivityResultLauncher<Intent> launcherThumbnail;

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

    // ── Dynamic overlay views (added programmatically to the video FrameLayout) ──
    private View          filterOverlayView;
    private LinearLayout  badgeStrip;
    private TextView      tvSubtitlePreview;
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
    private String  duetRootId      = "";  // ✅ FIX: track root ID for chain duets

    // ── Pre-selected sound ───────────────────────────────────────────────
    private String preSelectedSoundId    = "";
    private String preSelectedSoundTitle = "";
    private String preSelectedSoundUrl   = "";

    // ── Tool result storage ───────────────────────────────────────────────
    // Filters
    private String filterName       = "";
    private float  filterBrightness = 0f;
    private float  filterContrast   = 1f;
    private float  filterSaturation = 1f;
    private float  filterBeauty     = 0f;
    // Stickers
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
    // Audio mixer
    private float  mixOrigVol       = 1.0f;
    private float  mixMusicVol      = 0.8f;
    private String mixVoiceoverPath = "";
    private float  mixVoiceoverVol  = 1.0f;
    // Thumbnail
    private String thumbnailPath    = "";
    private long   thumbnailFrameMs = 0;

    // ── Register launchers BEFORE onCreate (must be in field init or constructor) ──

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ✅ FIX: Register all ActivityResultLaunchers here (must be before setContentView)
        registerLaunchers();

        setContentView(R.layout.activity_reel_editor);

        videoUriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        isFilePath  = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);

        isDuet         = getIntent().getBooleanExtra(EXTRA_IS_DUET, false);
        duetOriginalId = nvl(getIntent().getStringExtra(EXTRA_DUET_ORIGINAL_ID));
        duetOwnerUid   = nvl(getIntent().getStringExtra(EXTRA_DUET_OWNER_UID));
        duetLabel      = nvl(getIntent().getStringExtra(EXTRA_DUET_LABEL));
        String dUrl    = getIntent().getStringExtra(EXTRA_DUET_ORIGINAL_URL);
        if (dUrl != null) duetOriginalUrl = dUrl;
        // ✅ FIX: Read duet_root_id so it can be forwarded to upload
        String dRootId = getIntent().getStringExtra(DuetReelActivity.EXTRA_DUET_ROOT_ID);
        if (dRootId != null && !dRootId.isEmpty()) duetRootId = dRootId;
        else duetRootId = duetOriginalId;

        String si = getIntent().getStringExtra("selected_sound_id");
        String st = getIntent().getStringExtra("selected_sound_title");
        String su = getIntent().getStringExtra("selected_sound_url");
        if (si != null && !si.isEmpty()) preSelectedSoundId    = si;
        if (st != null && !st.isEmpty()) preSelectedSoundTitle = st;
        if (su != null && !su.isEmpty()) preSelectedSoundUrl   = su;

        if (videoUriStr == null || videoUriStr.isEmpty()) {
            Toast.makeText(this, "No video to edit", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        loadMetadata();
        setupPlayer();
        injectOverlayViews();
        setupListeners();

        // ✅ Show duet badge in title if this is a duet edit
        if (isDuet && !duetLabel.isEmpty()) {
            updateBadge("duet", "🎭 " + duetLabel);
        }
    }

    // ── Register ActivityResultLaunchers ──────────────────────────────────

    /**
     * ✅ FIX: All tool activities now use ActivityResultLauncher instead of
     * deprecated startActivityForResult. This fixes result-drop on Android 13/14
     * especially when ReelEditorActivity is started from DuetReelActivity.
     */
    private void registerLaunchers() {
        launcherFilters = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                filterName       = nvl(data.getStringExtra(ReelFiltersActivity.RESULT_FILTER_NAME));
                filterBrightness = data.getFloatExtra(ReelFiltersActivity.RESULT_BRIGHTNESS,   0f);
                filterContrast   = data.getFloatExtra(ReelFiltersActivity.RESULT_CONTRAST,     1f);
                filterSaturation = data.getFloatExtra(ReelFiltersActivity.RESULT_SATURATION,   1f);
                filterBeauty     = data.getFloatExtra(ReelFiltersActivity.RESULT_BEAUTY_LEVEL, 0f);
                applyFilterVisual(filterName, filterBrightness, filterContrast, filterSaturation);
                if (btnToolFilters != null) btnToolFilters.setColorFilter(
                    Color.argb(200, 168, 85, 247));
                if (!filterName.isEmpty() && !filterName.equals("Normal"))
                    Toast.makeText(this, "Filter \"" + filterName + "\" applied ✓", Toast.LENGTH_SHORT).show();
            });

        launcherStickers = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                String sJson = result.getData().getStringExtra(ReelStickerPickerActivity.RESULT_STICKER_JSON);
                if (sJson != null && !sJson.isEmpty()) {
                    stickerJson = sJson;
                    addStickerOverlay(sJson);
                    if (btnToolStickers != null) btnToolStickers.setColorFilter(
                        Color.argb(200, 255, 215, 0));
                    Toast.makeText(this, "Sticker added ✓", Toast.LENGTH_SHORT).show();
                }
            });

        launcherSubtitles = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                String subs = data.getStringExtra(ReelSubtitlesActivity.RESULT_SUBTITLES_JSON);
                if (subs != null && !subs.isEmpty()) {
                    subtitlesJson     = subs;
                    subtitlesEnabled  = data.getBooleanExtra(ReelSubtitlesActivity.RESULT_ENABLED,   true);
                    subtitlesFontSize = data.getIntExtra(ReelSubtitlesActivity.RESULT_FONT_SIZE,     16);
                    subtitlesStyle    = data.getIntExtra(ReelSubtitlesActivity.RESULT_STYLE,         0);
                    applySubtitlePreview(subtitlesJson, subtitlesEnabled, subtitlesFontSize);
                    if (btnToolSubtitles != null) btnToolSubtitles.setColorFilter(Color.WHITE);
                    Toast.makeText(this, "Subtitles applied ✓", Toast.LENGTH_SHORT).show();
                }
            });

        launcherTransitions = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                String tName = data.getStringExtra(ReelTransitionsActivity.RESULT_TRANSITION_NAME);
                if (tName != null && !tName.isEmpty()) {
                    transitionName     = tName;
                    transitionDuration = data.getIntExtra(
                        ReelTransitionsActivity.RESULT_TRANSITION_DURATION, 300);
                    transitionApplyAll = data.getBooleanExtra(
                        ReelTransitionsActivity.RESULT_APPLY_ALL, true);
                    updateBadge("transition", "⚡ " + transitionName);
                    if (btnToolTransitions != null) btnToolTransitions.setColorFilter(
                        Color.argb(200, 168, 85, 247));
                    Toast.makeText(this, "Transition: " + transitionName + " applied ✓",
                        Toast.LENGTH_SHORT).show();
                }
            });

        launcherVoice = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                String vName = data.getStringExtra(ReelVoiceEffectsActivity.RESULT_EFFECT_NAME);
                if (vName != null && !vName.isEmpty()) {
                    voiceEffectName = vName;
                    voicePitch      = data.getFloatExtra(ReelVoiceEffectsActivity.RESULT_PITCH,  1.0f);
                    voiceSpeed      = data.getFloatExtra(ReelVoiceEffectsActivity.RESULT_SPEED,  1.0f);
                    voiceReverb     = data.getFloatExtra(ReelVoiceEffectsActivity.RESULT_REVERB, 0.0f);
                    updateBadge("voice", "🎙 " + voiceEffectName);
                    if (player != null && voiceSpeed != 1.0f) {
                        try {
                            player.setPlaybackParameters(
                                new androidx.media3.common.PlaybackParameters(voiceSpeed));
                        } catch (Exception ignored) {}
                    }
                    if (btnToolVoice != null) btnToolVoice.setColorFilter(Color.WHITE);
                    if (!vName.equals("Normal"))
                        Toast.makeText(this, "Voice FX \"" + vName + "\" applied ✓",
                            Toast.LENGTH_SHORT).show();
                }
            });

        launcherAudioMixer = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                mixOrigVol       = data.getFloatExtra(ReelAudioMixerActivity.RESULT_ORIG_VOL,       1.0f);
                mixMusicVol      = data.getFloatExtra(ReelAudioMixerActivity.RESULT_MUSIC_VOL,      0.8f);
                String mvp       = data.getStringExtra(ReelAudioMixerActivity.RESULT_VOICEOVER_PATH);
                mixVoiceoverPath = mvp != null ? mvp : "";
                mixVoiceoverVol  = data.getFloatExtra(ReelAudioMixerActivity.RESULT_VOICEOVER_VOL,  1.0f);
                if (player != null) player.setVolume(mixOrigVol);
                updateBadge("audio", "🎵 Audio Mix");
                Toast.makeText(this, "Audio mix applied ✓", Toast.LENGTH_SHORT).show();
            });

        launcherThumbnail = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Intent data = result.getData();
                String tPath = data.getStringExtra(ReelThumbnailPickerActivity.RESULT_THUMB_PATH);
                if (tPath != null && !tPath.isEmpty()) {
                    thumbnailPath    = tPath;
                    thumbnailFrameMs = data.getLongExtra(
                        ReelThumbnailPickerActivity.RESULT_THUMB_FRAME_MS, 0);
                    applyThumbnailBadge(thumbnailPath);
                    if (btnToolThumbnail != null) btnToolThumbnail.setColorFilter(Color.WHITE);
                    Toast.makeText(this, "Thumbnail set ✓", Toast.LENGTH_SHORT).show();
                }
            });
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
    }

    // ── Inject dynamic overlay views into the video FrameLayout ──────────

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

    private void applyFilterVisual(String name, float brightness, float contrast, float saturation) {
        if (filterOverlayView == null) return;
        int overlayColor;
        switch (name) {
            case "Warm":      overlayColor = 0x22FF8800; break;
            case "Cool":      overlayColor = 0x220044FF; break;
            case "Vivid":     overlayColor = 0x1AFF00AA; break;
            case "Fade":      overlayColor = 0x33FFFFFF; break;
            case "Drama":     overlayColor = 0x33000000; break;
            case "Vintage":   overlayColor = 0x22884400; break;
            case "Mono":      overlayColor = 0x44888888; break;
            case "Noir":      overlayColor = 0x55000000; break;
            case "Juno":      overlayColor = 0x22FFAA00; break;
            case "Lark":      overlayColor = 0x1500DDFF; break;
            case "Clarendon": overlayColor = 0x220055CC; break;
            case "Normal":    overlayColor = 0x00000000; break;
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

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addStickerOverlay(String stickerJson) {
        if (playerView == null || stickerJson.isEmpty()) return;
        ViewGroup parent = (ViewGroup) playerView.getParent();
        if (!(parent instanceof FrameLayout)) return;
        FrameLayout fl = (FrameLayout) parent;
        int dp = (int) getResources().getDisplayMetrics().density;

        String value = "";
        try {
            int vStart = stickerJson.indexOf("\"value\":\"") + 9;
            int vEnd   = stickerJson.indexOf("\"", vStart);
            if (vStart > 8 && vEnd > vStart) value = stickerJson.substring(vStart, vEnd);
        } catch (Exception ignored) {}
        if (value.isEmpty()) value = "✨";

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

        final float[] startTouch = new float[2];
        final float[] startPos   = new float[2];
        stickerView.setOnTouchListener((v, event) -> {
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
        stickerView.setOnLongClickListener(v -> {
            fl.removeView(v);
            return true;
        });

        stickerView.setScaleX(0.3f);
        stickerView.setScaleY(0.3f);
        stickerView.animate().scaleX(1f).scaleY(1f).setDuration(250).start();
        updateBadge("sticker", "✨ Sticker");
    }

    private void applySubtitlePreview(String json, boolean enabled, int fontSize) {
        if (tvSubtitlePreview == null) return;
        if (!enabled || json.isEmpty()) {
            tvSubtitlePreview.setVisibility(View.GONE);
            updateBadge("subtitle", null);
            return;
        }
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

    private void updateBadge(String tag, String label) {
        if (badgeStrip == null) return;
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(4 * dp);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(0xCC9B59B6);
        gd.setCornerRadius(12 * dp);
        chip.setBackground(gd);
        badgeStrip.addView(chip, lp);
        badgeStrip.setVisibility(View.VISIBLE);
    }

    private void applyThumbnailBadge(String path) {
        if (ivThumbBadge == null || path.isEmpty()) return;
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(path);
            if (bmp != null) {
                ivThumbBadge.setImageBitmap(bmp);
                ivThumbBadge.setVisibility(View.VISIBLE);
                android.graphics.drawable.GradientDrawable border =
                    new android.graphics.drawable.GradientDrawable();
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
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (progressBuffering != null)
                    progressBuffering.setVisibility(
                        state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                updatePlayPauseIcon();
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
                Toast.makeText(this, "Text overlay added ✓", Toast.LENGTH_SHORT).show();
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

        // ── Tool buttons — use launchers ─────────────────────────────────

        if (btnToolFilters != null) btnToolFilters.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelFiltersActivity.class);
            i.putExtra(ReelFiltersActivity.EXTRA_THUMBNAIL_URI, videoUriStr);
            launcherFilters.launch(i);
        });

        if (btnToolStickers != null) btnToolStickers.setOnClickListener(v ->
            launcherStickers.launch(new Intent(this, ReelStickerPickerActivity.class)));

        if (btnToolSubtitles != null) btnToolSubtitles.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelSubtitlesActivity.class);
            i.putExtra(ReelSubtitlesActivity.EXTRA_VIDEO_URI,    videoUriStr);
            i.putExtra(ReelSubtitlesActivity.EXTRA_IS_FILE_PATH, isFilePath);
            launcherSubtitles.launch(i);
        });

        if (btnToolTransitions != null) btnToolTransitions.setOnClickListener(v ->
            launcherTransitions.launch(new Intent(this, ReelTransitionsActivity.class)));

        if (btnToolVoice != null) btnToolVoice.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelVoiceEffectsActivity.class);
            i.putExtra(ReelVoiceEffectsActivity.EXTRA_AUDIO_PATH,   videoUriStr);
            i.putExtra(ReelVoiceEffectsActivity.EXTRA_IS_FILE_PATH, isFilePath);
            launcherVoice.launch(i);
        });

        if (btnToolAudioMixer != null) btnToolAudioMixer.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelAudioMixerActivity.class);
            i.putExtra(ReelAudioMixerActivity.EXTRA_VIDEO_URI,    videoUriStr);
            i.putExtra(ReelAudioMixerActivity.EXTRA_IS_FILE_PATH, isFilePath);
            i.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_URL,    preSelectedSoundUrl);
            i.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_TITLE,  preSelectedSoundTitle);
            i.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_ARTIST, "");
            launcherAudioMixer.launch(i);
        });

        if (btnToolThumbnail != null) btnToolThumbnail.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelThumbnailPickerActivity.class);
            i.putExtra(ReelThumbnailPickerActivity.EXTRA_VIDEO_URI,    videoUriStr);
            i.putExtra(ReelThumbnailPickerActivity.EXTRA_IS_FILE_PATH, isFilePath);
            launcherThumbnail.launch(i);
        });

        btnNext.setOnClickListener(v -> proceedToUpload());
    }

    // ── Proceed to upload ─────────────────────────────────────────────────

    private void proceedToUpload() {
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

        // Audio mix
        intent.putExtra("mix_orig_vol",       mixOrigVol);
        intent.putExtra("mix_music_vol",      mixMusicVol);
        intent.putExtra("mix_voiceover_path", mixVoiceoverPath);
        intent.putExtra("mix_voiceover_vol",  mixVoiceoverVol);

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
            // ✅ FIX: Forward duet_root_id so upload can persist chain info
            if (!duetRootId.isEmpty())
                intent.putExtra(DuetReelActivity.EXTRA_DUET_ROOT_ID, duetRootId);
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
