package com.callx.app.editor;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.upload.ReelUploadActivity;
import com.callx.app.music.MusicPickerActivity;
import com.callx.app.music.SoundDetailActivity;
import com.callx.app.music.AudioMixHelper;
import com.callx.app.social.DuetReelActivity;

import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
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
 * ReelEditorActivity — Edit a reel before posting.
 *
 * Features:
 *  ✅ ExoPlayer preview (loop)
 *  ✅ Trim range seekbars (start + end)
 *  ✅ Text overlay input (shown over video)
 *  ✅ Play/pause toggle
 *  ✅ Filters — filter name + brightness/contrast/saturation/beauty returned and stored
 *  ✅ Stickers — sticker JSON returned and stored
 *  ✅ Subtitles — subtitle JSON + style options returned and stored
 *  ✅ Transitions — transition name + duration returned and stored
 *  ✅ Voice Effects — effect name + pitch/speed/reverb returned and stored
 *  ✅ Audio Mixer — mix settings returned and stored (existing)
 *  ✅ Thumbnail — selected thumb path + frame time returned and stored
 *  ✅ "Next" → passes all editor data to ReelUploadActivity
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ReelEditorActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI      = "editor_video_uri";
    public static final String EXTRA_IS_FILE_PATH   = "is_file_path";
    // Fix 4: duet metadata passed from DuetReelActivity
    public static final String EXTRA_IS_DUET             = "editor_is_duet";
    public static final String EXTRA_DUET_ORIGINAL_ID    = "editor_duet_original_id";
    public static final String EXTRA_DUET_ORIGINAL_URL   = "editor_duet_original_url";
    public static final String EXTRA_DUET_OWNER_UID      = "editor_duet_owner_uid";
    // Fix 8: watermark label e.g. "Duet with @username"
    public static final String EXTRA_DUET_LABEL          = "editor_duet_label";

    // Request codes for tool activities
    private static final int REQ_FILTERS     = 401;
    private static final int REQ_STICKERS    = 402;
    private static final int REQ_SUBTITLES   = 403;
    private static final int REQ_TRANSITIONS = 404;
    private static final int REQ_VOICE       = 405;
    private static final int REQ_AUDIO_MIXER = 406;
    private static final int REQ_THUMBNAIL   = 407;

    private PlayerView    playerView;
    private ImageButton   btnPlayPause, btnBack;
    private SeekBar       sbTrimStart, sbTrimEnd;
    private TextView      tvTrimStart, tvTrimEnd, tvDuration;
    private EditText      etTextOverlay;
    private TextView      tvTextPreview;
    private View          btnNext, btnAddText;
    private ProgressBar   progressBuffering;
    private ImageButton   btnToolFilters, btnToolStickers, btnToolSubtitles, btnToolTransitions, btnToolVoice, btnToolAudioMixer, btnToolThumbnail;

    private ExoPlayer player;
    private String    videoUriStr;
    private boolean   isFilePath = false;
    private long      totalDurationMs = 0;
    private long      trimStartMs     = 0;
    private long      trimEndMs       = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Duet metadata (Fix 4 & 8)
    private boolean isDuet           = false;
    private String  duetOriginalId   = "";
    private String  duetOwnerUid     = "";
    private String  duetLabel        = "";
    private String  duetOriginalUrl  = "";

    // Sound pre-selected from SoundDetailActivity or MusicPickerActivity
    private String preSelectedSoundId    = "";
    private String preSelectedSoundTitle = "";
    private String preSelectedSoundUrl   = "";

    // ── Tool result storage ───────────────────────────────────────────────

    // Filters (REQ_FILTERS = 401)
    private String filterName    = "";
    private float  filterBrightness  = 0f;
    private float  filterContrast    = 1f;
    private float  filterSaturation  = 1f;
    private float  filterBeauty      = 0f;

    // Stickers (REQ_STICKERS = 402)
    private String stickerJson = "";

    // Subtitles (REQ_SUBTITLES = 403)
    private String  subtitlesJson    = "";
    private boolean subtitlesEnabled = false;
    private int     subtitlesFontSize= 16;
    private int     subtitlesStyle   = 0;

    // Transitions (REQ_TRANSITIONS = 404)
    private String  transitionName     = "";
    private int     transitionDuration = 0;
    private boolean transitionApplyAll = true;

    // Voice Effects (REQ_VOICE = 405)
    private String voiceEffectName  = "";
    private float  voicePitch       = 1.0f;
    private float  voiceSpeed       = 1.0f;
    private float  voiceReverb      = 0.0f;

    // Audio Mixer (REQ_AUDIO_MIXER = 406)
    private float  mixOrigVol      = 1.0f;
    private float  mixMusicVol     = 0.8f;
    private String mixVoiceoverPath = "";
    private float  mixVoiceoverVol  = 1.0f;

    // Thumbnail (REQ_THUMBNAIL = 407)
    private String thumbnailPath    = "";
    private long   thumbnailFrameMs = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_editor);

        videoUriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        isFilePath  = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);

        // Fix 4 & 8: read duet metadata
        isDuet         = getIntent().getBooleanExtra(EXTRA_IS_DUET, false);
        duetOriginalId = getIntent().getStringExtra(EXTRA_DUET_ORIGINAL_ID) != null
                         ? getIntent().getStringExtra(EXTRA_DUET_ORIGINAL_ID) : "";
        duetOwnerUid   = getIntent().getStringExtra(EXTRA_DUET_OWNER_UID) != null
                         ? getIntent().getStringExtra(EXTRA_DUET_OWNER_UID) : "";
        duetLabel      = getIntent().getStringExtra(EXTRA_DUET_LABEL) != null
                         ? getIntent().getStringExtra(EXTRA_DUET_LABEL) : "";
        String dUrl    = getIntent().getStringExtra(EXTRA_DUET_ORIGINAL_URL);
        if (dUrl != null) duetOriginalUrl = dUrl;

        // Read pre-selected sound passed from ReelCameraActivity / SoundDetailActivity
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
        setupListeners();
    }

    private void bindViews() {
        playerView        = findViewById(R.id.editor_player_view);
        btnPlayPause      = findViewById(R.id.btn_editor_play_pause);
        btnBack           = findViewById(R.id.btn_editor_back);
        sbTrimStart       = findViewById(R.id.sb_editor_trim_start);
        sbTrimEnd         = findViewById(R.id.sb_editor_trim_end);
        tvTrimStart       = findViewById(R.id.tv_editor_trim_start);
        tvTrimEnd         = findViewById(R.id.tv_editor_trim_end);
        tvDuration        = findViewById(R.id.tv_editor_duration);
        etTextOverlay     = findViewById(R.id.et_text_overlay);
        tvTextPreview     = findViewById(R.id.tv_text_preview);
        btnNext           = findViewById(R.id.btn_editor_next);
        btnAddText        = findViewById(R.id.btn_add_text);
        progressBuffering = findViewById(R.id.editor_progress_buffering);
        btnToolFilters    = findViewById(R.id.btn_tool_filters);
        btnToolStickers   = findViewById(R.id.btn_tool_stickers);
        btnToolSubtitles  = findViewById(R.id.btn_tool_subtitles);
        btnToolTransitions= findViewById(R.id.btn_tool_transitions);
        btnToolVoice      = findViewById(R.id.btn_tool_voice);
        btnToolAudioMixer = findViewById(R.id.btn_tool_audio_mixer);
        btnToolThumbnail  = findViewById(R.id.btn_tool_thumbnail);
    }

    private void loadMetadata() {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            if (isFilePath) {
                mmr.setDataSource(videoUriStr);
            } else {
                mmr.setDataSource(this, Uri.parse(videoUriStr));
            }
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) totalDurationMs = Long.parseLong(d);
        } catch (Exception ignored) {
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        trimStartMs = 0;
        trimEndMs   = totalDurationMs;

        int maxProgress = Math.max(1, (int)(totalDurationMs / 100));
        sbTrimStart.setMax(maxProgress);
        sbTrimStart.setProgress(0);
        sbTrimEnd.setMax(maxProgress);
        sbTrimEnd.setProgress(maxProgress);
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
                if (progressBuffering != null) {
                    progressBuffering.setVisibility(
                        state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                }
                updatePlayPauseIcon();
            }
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseIcon();
            }
        });
    }

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
                    long newStart = prog * 100L;
                    if (newStart >= trimEndMs - 1000) {
                        sb.setProgress((int)((trimEndMs - 1000) / 100));
                        return;
                    }
                    trimStartMs = newStart;
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
                    long newEnd = prog * 100L;
                    if (newEnd <= trimStartMs + 1000) {
                        sb.setProgress((int)((trimStartMs + 1000) / 100));
                        return;
                    }
                    trimEndMs = newEnd;
                    tvTrimEnd.setText(formatMs(trimEndMs));
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // ── Tool buttons ─────────────────────────────────────────────────────

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
            startActivityForResult(i, REQ_VOICE);
        });

        if (btnToolAudioMixer != null) btnToolAudioMixer.setOnClickListener(v -> {
            Intent mixIntent = new Intent(this, ReelAudioMixerActivity.class);
            mixIntent.putExtra(ReelAudioMixerActivity.EXTRA_VIDEO_URI,    videoUriStr);
            mixIntent.putExtra(ReelAudioMixerActivity.EXTRA_IS_FILE_PATH, isFilePath);
            mixIntent.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_URL,    preSelectedSoundUrl);
            mixIntent.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_TITLE,  preSelectedSoundTitle);
            mixIntent.putExtra(ReelAudioMixerActivity.EXTRA_MUSIC_ARTIST, "");
            startActivityForResult(mixIntent, REQ_AUDIO_MIXER);
        });

        if (btnToolThumbnail != null) btnToolThumbnail.setOnClickListener(v -> {
            Intent i = new Intent(this, ReelThumbnailPickerActivity.class);
            i.putExtra(ReelThumbnailPickerActivity.EXTRA_VIDEO_URI,    videoUriStr);
            i.putExtra(ReelThumbnailPickerActivity.EXTRA_IS_FILE_PATH, isFilePath);
            startActivityForResult(i, REQ_THUMBNAIL);
        });

        btnNext.setOnClickListener(v -> proceedToUpload());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @androidx.annotation.Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        switch (requestCode) {

            case REQ_FILTERS:
                filterName       = data.getStringExtra(ReelFiltersActivity.RESULT_FILTER_NAME);
                filterBrightness = data.getFloatExtra(ReelFiltersActivity.RESULT_BRIGHTNESS,   0f);
                filterContrast   = data.getFloatExtra(ReelFiltersActivity.RESULT_CONTRAST,     1f);
                filterSaturation = data.getFloatExtra(ReelFiltersActivity.RESULT_SATURATION,   1f);
                filterBeauty     = data.getFloatExtra(ReelFiltersActivity.RESULT_BEAUTY_LEVEL, 0f);
                if (filterName == null) filterName = "";
                if (!filterName.isEmpty() && !filterName.equals("Normal")) {
                    Toast.makeText(this, "Filter applied: " + filterName + " ✓", Toast.LENGTH_SHORT).show();
                    if (btnToolFilters != null) btnToolFilters.setAlpha(1f);
                }
                break;

            case REQ_STICKERS:
                String sJson = data.getStringExtra(ReelStickerPickerActivity.RESULT_STICKER_JSON);
                if (sJson != null && !sJson.isEmpty()) {
                    stickerJson = sJson;
                    Toast.makeText(this, "Sticker added ✓", Toast.LENGTH_SHORT).show();
                    if (btnToolStickers != null) btnToolStickers.setAlpha(1f);
                }
                break;

            case REQ_SUBTITLES:
                String subJson = data.getStringExtra(ReelSubtitlesActivity.RESULT_SUBTITLES_JSON);
                if (subJson != null && !subJson.isEmpty()) {
                    subtitlesJson     = subJson;
                    subtitlesEnabled  = data.getBooleanExtra(ReelSubtitlesActivity.RESULT_ENABLED,   true);
                    subtitlesFontSize = data.getIntExtra(ReelSubtitlesActivity.RESULT_FONT_SIZE,     16);
                    subtitlesStyle    = data.getIntExtra(ReelSubtitlesActivity.RESULT_STYLE,         0);
                    Toast.makeText(this, "Subtitles saved ✓", Toast.LENGTH_SHORT).show();
                    if (btnToolSubtitles != null) btnToolSubtitles.setAlpha(1f);
                }
                break;

            case REQ_TRANSITIONS:
                String tName = data.getStringExtra(ReelTransitionsActivity.RESULT_TRANSITION_NAME);
                if (tName != null && !tName.isEmpty()) {
                    transitionName     = tName;
                    transitionDuration = data.getIntExtra(ReelTransitionsActivity.RESULT_TRANSITION_DURATION, 300);
                    transitionApplyAll = data.getBooleanExtra(ReelTransitionsActivity.RESULT_APPLY_ALL, true);
                    Toast.makeText(this, "Transition: " + transitionName + " ✓", Toast.LENGTH_SHORT).show();
                    if (btnToolTransitions != null) btnToolTransitions.setAlpha(1f);
                }
                break;

            case REQ_VOICE:
                String vName = data.getStringExtra(ReelVoiceEffectsActivity.RESULT_EFFECT_NAME);
                if (vName != null && !vName.isEmpty()) {
                    voiceEffectName = vName;
                    voicePitch      = data.getFloatExtra(ReelVoiceEffectsActivity.RESULT_PITCH,  1.0f);
                    voiceSpeed      = data.getFloatExtra(ReelVoiceEffectsActivity.RESULT_SPEED,  1.0f);
                    voiceReverb     = data.getFloatExtra(ReelVoiceEffectsActivity.RESULT_REVERB, 0.0f);
                    Toast.makeText(this, "Voice effect: " + voiceEffectName + " ✓", Toast.LENGTH_SHORT).show();
                    if (btnToolVoice != null) btnToolVoice.setAlpha(1f);
                }
                break;

            case REQ_AUDIO_MIXER:
                mixOrigVol       = data.getFloatExtra(ReelAudioMixerActivity.RESULT_ORIG_VOL,       1.0f);
                mixMusicVol      = data.getFloatExtra(ReelAudioMixerActivity.RESULT_MUSIC_VOL,      0.8f);
                mixVoiceoverPath = data.getStringExtra(ReelAudioMixerActivity.RESULT_VOICEOVER_PATH);
                mixVoiceoverVol  = data.getFloatExtra(ReelAudioMixerActivity.RESULT_VOICEOVER_VOL,  1.0f);
                if (mixVoiceoverPath == null) mixVoiceoverPath = "";
                Toast.makeText(this, "Audio mix saved ✓", Toast.LENGTH_SHORT).show();
                break;

            case REQ_THUMBNAIL:
                String tPath = data.getStringExtra(ReelThumbnailPickerActivity.RESULT_THUMB_PATH);
                if (tPath != null && !tPath.isEmpty()) {
                    thumbnailPath    = tPath;
                    thumbnailFrameMs = data.getLongExtra(ReelThumbnailPickerActivity.RESULT_THUMB_FRAME_MS, 0);
                    Toast.makeText(this, "Thumbnail set ✓", Toast.LENGTH_SHORT).show();
                    if (btnToolThumbnail != null) btnToolThumbnail.setAlpha(1f);
                }
                break;
        }
    }

    private void proceedToUpload() {
        String textOverlay = tvTextPreview.getVisibility() == View.VISIBLE
            ? tvTextPreview.getText().toString() : "";

        Intent intent = new Intent(this, ReelUploadActivity.class);
        intent.putExtra(ReelUploadActivity.EXTRA_VIDEO_URI,    videoUriStr);
        intent.putExtra(ReelUploadActivity.EXTRA_IS_FILE_PATH, isFilePath);
        intent.putExtra(ReelUploadActivity.EXTRA_TRIM_START,   trimStartMs);
        intent.putExtra(ReelUploadActivity.EXTRA_TRIM_END,     trimEndMs);
        intent.putExtra(ReelUploadActivity.EXTRA_TEXT_OVERLAY, textOverlay);

        // Pass pre-selected sound
        if (!preSelectedSoundId.isEmpty())    intent.putExtra(ReelUploadActivity.EXTRA_SOUND_ID,    preSelectedSoundId);
        if (!preSelectedSoundTitle.isEmpty()) intent.putExtra(ReelUploadActivity.EXTRA_SOUND_TITLE, preSelectedSoundTitle);
        if (!preSelectedSoundUrl.isEmpty())   intent.putExtra(ReelUploadActivity.EXTRA_SOUND_URL,   preSelectedSoundUrl);

        // Pass audio mix settings (for AudioMixHelper in ReelUploadActivity)
        intent.putExtra("mix_orig_vol",        mixOrigVol);
        intent.putExtra("mix_music_vol",       mixMusicVol);
        intent.putExtra("mix_voiceover_path",  mixVoiceoverPath);
        intent.putExtra("mix_voiceover_vol",   mixVoiceoverVol);

        // Pass filter settings
        if (!filterName.isEmpty()) {
            intent.putExtra("filter_name",       filterName);
            intent.putExtra("filter_brightness", filterBrightness);
            intent.putExtra("filter_contrast",   filterContrast);
            intent.putExtra("filter_saturation", filterSaturation);
            intent.putExtra("filter_beauty",     filterBeauty);
        }

        // Pass sticker
        if (!stickerJson.isEmpty()) {
            intent.putExtra("sticker_json", stickerJson);
        }

        // Pass subtitles
        if (!subtitlesJson.isEmpty()) {
            intent.putExtra("subtitles_json",      subtitlesJson);
            intent.putExtra("subtitles_enabled",   subtitlesEnabled);
            intent.putExtra("subtitles_font_size", subtitlesFontSize);
            intent.putExtra("subtitles_style",     subtitlesStyle);
        }

        // Pass transition
        if (!transitionName.isEmpty()) {
            intent.putExtra("transition_name",     transitionName);
            intent.putExtra("transition_duration", transitionDuration);
            intent.putExtra("transition_apply_all",transitionApplyAll);
        }

        // Pass voice effect
        if (!voiceEffectName.isEmpty()) {
            intent.putExtra("voice_effect_name", voiceEffectName);
            intent.putExtra("voice_pitch",       voicePitch);
            intent.putExtra("voice_speed",       voiceSpeed);
            intent.putExtra("voice_reverb",      voiceReverb);
        }

        // Pass thumbnail
        if (!thumbnailPath.isEmpty()) {
            intent.putExtra("thumbnail_path",     thumbnailPath);
            intent.putExtra("thumbnail_frame_ms", thumbnailFrameMs);
        }

        // Fix 4 & 6 & 8: pass duet metadata so ReelUploadActivity can save duetOf + increment duetCount
        if (isDuet) {
            intent.putExtra(ReelUploadActivity.EXTRA_IS_DUET,          true);
            intent.putExtra(ReelUploadActivity.EXTRA_DUET_ORIGINAL_ID, duetOriginalId);
            intent.putExtra(ReelUploadActivity.EXTRA_DUET_ORIGINAL_URL,duetOriginalUrl);
            intent.putExtra(ReelUploadActivity.EXTRA_DUET_OWNER_UID,   duetOwnerUid);
            intent.putExtra(ReelUploadActivity.EXTRA_DUET_LABEL,       duetLabel);
        }

        startActivity(intent);
    }

    private void updatePlayPauseIcon() {
        if (btnPlayPause == null || player == null) return;
        btnPlayPause.setImageResource(
            player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private String formatMs(long ms) {
        long sec = ms / 1000;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
