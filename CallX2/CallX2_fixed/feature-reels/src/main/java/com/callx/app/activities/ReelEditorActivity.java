package com.callx.app.activities;

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
import com.callx.app.activities.ReelFiltersActivity;
import com.callx.app.activities.ReelStickerPickerActivity;
import com.callx.app.activities.ReelSubtitlesActivity;
import com.callx.app.activities.ReelTransitionsActivity;
import com.callx.app.activities.ReelVoiceEffectsActivity;
import com.callx.app.activities.ReelAudioMixerActivity;
import com.callx.app.activities.ReelThumbnailPickerActivity;


import java.io.File;

/**
 * ReelEditorActivity — Edit a reel before posting.
 *
 * Features:
 *  ✅ ExoPlayer preview (loop)
 *  ✅ Trim range seekbars (start + end)
 *  ✅ Text overlay input (shown over video)
 *  ✅ Play/pause toggle
 *  ✅ "Next" → passes trimmed/original Uri + text overlay to ReelUploadActivity
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ReelEditorActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI      = "editor_video_uri";
    public static final String EXTRA_IS_FILE_PATH   = "is_file_path";

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_editor);

        videoUriStr = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        isFilePath  = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);

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

        if (btnToolFilters    != null) btnToolFilters.setOnClickListener(v    -> { Intent i = new Intent(this, ReelFiltersActivity.class); i.putExtra(ReelFiltersActivity.EXTRA_THUMBNAIL_URI, videoUriStr); startActivityForResult(i, 401); });
        if (btnToolStickers   != null) btnToolStickers.setOnClickListener(v   -> startActivityForResult(new Intent(this, ReelStickerPickerActivity.class), 402));
        if (btnToolSubtitles  != null) btnToolSubtitles.setOnClickListener(v  -> startActivityForResult(new Intent(this, ReelSubtitlesActivity.class), 403));
        if (btnToolTransitions!= null) btnToolTransitions.setOnClickListener(v-> startActivityForResult(new Intent(this, ReelTransitionsActivity.class), 404));
        if (btnToolVoice      != null) btnToolVoice.setOnClickListener(v      -> startActivityForResult(new Intent(this, ReelVoiceEffectsActivity.class), 405));
        if (btnToolAudioMixer != null) btnToolAudioMixer.setOnClickListener(v -> startActivityForResult(new Intent(this, ReelAudioMixerActivity.class), 406));
        if (btnToolThumbnail  != null) btnToolThumbnail.setOnClickListener(v  -> startActivityForResult(new Intent(this, ReelThumbnailPickerActivity.class), 407));
        btnNext.setOnClickListener(v -> proceedToUpload());
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
