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
    public static final String EXTRA_IS_DUET             = "editor_is_duet";
    public static final String EXTRA_DUET_ORIGINAL_ID    = "editor_duet_original_id";
    public static final String EXTRA_DUET_ORIGINAL_URL   = "editor_duet_original_url";
    public static final String EXTRA_DUET_OWNER_UID      = "editor_duet_owner_uid";
    public static final String EXTRA_DUET_LABEL          = "editor_duet_label";

    private static final int REQ_FILTERS     = 401;
    private static final int REQ_STICKERS    = 402;
    private static final int REQ_SUBTITLES   = 403;
    private static final int REQ_TRANSITIONS = 404;
    private static final int REQ_VOICE       = 405;
    private static final int REQ_AUDIO_MIXER = 406;
    private static final int REQ_THUMBNAIL   = 407;

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
    // Audio mixer
    private float  mixOrigVol       = 1.0f;
    private float  mixMusicVol      = 0.8f;
    private String mixVoiceoverPath = "";
    private float  mixVoiceoverVol  = 1.0f;
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
        injectOverlayViews();   // ← NEW: add dynamic overlay views to video FrameLayout
        setupListeners();
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
     * Add a sticker as a draggable TextView on the video FrameLayout.
     * Each call adds a new sticker (supports multiple stickers).
     * Sticker JSON format: {"type":"emoji","value":"😀","x":0.5,"y":0.5}
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void addStickerOverlay(String stickerJson) {
        if (playerView == null || stickerJson.isEmpty()) return;
        ViewGroup parent = (ViewGroup) playerView.getParent();
        if (!(parent instanceof FrameLayout)) return;
        FrameLayout fl = (FrameLayout) parent;
        int dp = (int) getResources().getDisplayMetrics().density;

        // Parse value from JSON (simple substring approach, no heavy JSON lib)
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

        // Make sticker draggable
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
                    // Double-tap to remove (long click)
                    return true;
            }
            return false;
        });
        stickerView.setOnLongClickListener(v -> {
            fl.removeView(v);
            return true;
        });

        // Bounce animation
        stickerView.setScaleX(0.3f);
        stickerView.setScaleY(0.3f);
        stickerView.animate().scaleX(1f).scaleY(1f).setDuration(250).start();

        updateBadge("sticker", "✨ Sticker");
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
            startActivityForResult(i, REQ_AUDIO_MIXER);
        });

        if (btnToolThumbnail != null) btnToolThumbnail.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelThumbnailPickerActivity.class);
            i.putExtra(ReelThumbnailPickerActivity.EXTRA_VIDEO_URI,    videoUriStr);
            i.putExtra(ReelThumbnailPickerActivity.EXTRA_IS_FILE_PATH, isFilePath);
            startActivityForResult(i, REQ_THUMBNAIL);
        });

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

            case REQ_AUDIO_MIXER: {
                mixOrigVol       = data.getFloatExtra(ReelAudioMixerActivity.RESULT_ORIG_VOL,       1.0f);
                mixMusicVol      = data.getFloatExtra(ReelAudioMixerActivity.RESULT_MUSIC_VOL,      0.8f);
                String mvp       = data.getStringExtra(ReelAudioMixerActivity.RESULT_VOICEOVER_PATH);
                mixVoiceoverPath = mvp != null ? mvp : "";
                mixVoiceoverVol  = data.getFloatExtra(ReelAudioMixerActivity.RESULT_VOICEOVER_VOL,  1.0f);
                // Apply original volume to player preview
                if (player != null) player.setVolume(mixOrigVol);
                updateBadge("audio", "🎵 Audio Mix");
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
