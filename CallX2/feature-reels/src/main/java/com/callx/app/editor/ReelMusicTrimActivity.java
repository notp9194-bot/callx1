package com.callx.app.editor;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.reels.R;
import com.callx.app.views.AudioTrimWaveformView;

import java.util.Locale;

/**
 * ReelMusicTrimActivity — "Trim Audio": lets the user pick the start point
 * (and range) of an original-audio track from SoundDetailFragment's trim chip.
 *
 * Modern redesign: reuses the exact same layout, drawables and
 * AudioTrimWaveformView-driven trimmer as ReelPhotoMusicTrimActivity's
 * "Adjust Music" screen, so both trim flows look and behave identically —
 * cover-art preview, draggable waveform with tooltips, Cancel / total
 * duration / Done action row.
 *
 * Features:
 *  ✅ Loads full audio track via MediaPlayer
 *  ✅ Draggable dual-handle waveform (AudioTrimWaveformView): choose start + end point
 *  ✅ Displays total duration and live playhead while previewing
 *  ✅ Snaps to 15 / 30 / 60 second preset clips
 *  ✅ Returns EXTRA_START_MS + EXTRA_END_MS to caller on "Done"
 */
public class ReelMusicTrimActivity extends AppCompatActivity {

    public static final String EXTRA_SOUND_ID     = "trim_sound_id";
    public static final String EXTRA_SOUND_TITLE  = "trim_sound_title";
    public static final String EXTRA_SOUND_ARTIST = "trim_sound_artist";
    public static final String EXTRA_SOUND_URL    = "trim_sound_url";
    public static final String EXTRA_SOUND_COVER  = "trim_sound_cover";
    public static final String EXTRA_DURATION_MS  = "trim_duration_ms";

    public static final String RESULT_START_MS    = "result_start_ms";
    public static final String RESULT_END_MS      = "result_end_ms";
    public static final String RESULT_SOUND_ID    = "result_sound_id";
    public static final String RESULT_SOUND_URL   = "result_sound_url";
    public static final String RESULT_SOUND_TITLE = "result_sound_title";

    private ImageButton  btnBack, btnPreview;
    private TextView     btnUse, btnCancel;
    private TextView     tvTitle, tvDuration;
    private TextView     tvTrackTitle, tvTrackArtist;
    private ImageView    ivCover, ivPhotoPreview, ivPlayHint;
    private AudioTrimWaveformView waveformView;
    private RadioGroup   rgPresets;
    private ProgressBar  progressLoad;
    private View         layoutControls;

    private String soundId, soundUrl, soundTitle, soundArtist, soundCover;
    private int    totalDurationMs;
    private int    startMs = 0;
    private int    endMs   = 30_000;

    private MediaPlayer mediaPlayer;
    private boolean     isPreviewing = false;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Ticks while previewing: stops playback at the trim end and drives the waveform playhead. */
    private final Runnable previewStopCheck = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && isPreviewing) {
                int pos = mediaPlayer.getCurrentPosition();
                if (waveformView != null) waveformView.setPlayheadMs(pos);
                if (pos >= endMs) {
                    stopPreview();
                } else {
                    handler.postDelayed(this, 60);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_music_trim);

        soundId         = getIntent().getStringExtra(EXTRA_SOUND_ID);
        soundUrl        = getIntent().getStringExtra(EXTRA_SOUND_URL);
        soundTitle      = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
        soundArtist     = getIntent().getStringExtra(EXTRA_SOUND_ARTIST);
        soundCover      = getIntent().getStringExtra(EXTRA_SOUND_COVER);
        totalDurationMs = getIntent().getIntExtra(EXTRA_DURATION_MS, 0);

        bindViews();
        setupWaveformPalette();
        populateInfo();
        showCoverPreview();
        loadAudio();
        setupWaveformDrag();
        setupPresets();
        setupButtons();
    }

    private void bindViews() {
        btnBack        = findViewById(R.id.btn_trim_back);
        btnPreview     = findViewById(R.id.btn_trim_preview);
        btnUse         = findViewById(R.id.btn_trim_use);
        btnCancel      = findViewById(R.id.btn_trim_cancel);
        tvTitle        = findViewById(R.id.tv_trim_title);
        tvDuration     = findViewById(R.id.tv_trim_total_duration);
        tvTrackTitle   = findViewById(R.id.tv_trim_title_track);
        tvTrackArtist  = findViewById(R.id.tv_trim_artist);
        ivCover        = findViewById(R.id.iv_trim_cover);
        ivPhotoPreview = findViewById(R.id.iv_photo_preview);
        ivPlayHint     = findViewById(R.id.iv_preview_play_hint);
        waveformView   = findViewById(R.id.waveform_trim_view);
        rgPresets      = findViewById(R.id.rg_trim_presets);
        progressLoad   = findViewById(R.id.progress_trim_load);
        layoutControls = findViewById(R.id.layout_trim_controls);

        View.OnClickListener togglePreviewClick = v -> togglePreview();
        if (ivPhotoPreview != null) ivPhotoPreview.setOnClickListener(togglePreviewClick);
        if (ivPlayHint     != null) ivPlayHint.setOnClickListener(togglePreviewClick);
    }

    /**
     * Resolves the waveform's Canvas-drawn colors from the day/night-aware
     * @color/trim_* resources — same palette as the Adjust Music screen.
     */
    private void setupWaveformPalette() {
        if (waveformView == null) return;
        int dim       = ContextCompat.getColor(this, R.color.trim_waveform_dim);
        int start     = ContextCompat.getColor(this, R.color.trim_gradient_start);
        int end       = ContextCompat.getColor(this, R.color.trim_gradient_end);
        int playhead  = ContextCompat.getColor(this, R.color.trim_text_primary);
        int tooltipBg = ContextCompat.getColor(this, R.color.trim_tooltip_bg);
        int tooltipTx = ContextCompat.getColor(this, R.color.trim_tooltip_text);
        waveformView.setPalette(dim, start, end, playhead, tooltipBg, tooltipTx);
    }

    private void populateInfo() {
        if (tvTitle != null) tvTitle.setText("Trim Audio");
        if (tvTrackTitle != null)
            tvTrackTitle.setText(soundTitle != null && !soundTitle.isEmpty() ? soundTitle : "Original audio");
        if (tvTrackArtist != null)
            tvTrackArtist.setText(soundArtist != null && !soundArtist.isEmpty() ? soundArtist : "—");
        if (ivCover != null && soundCover != null && !soundCover.isEmpty()) {
            Glide.with(this).load(soundCover)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(ivCover);
        }
        updateTimeLabels();
        if (totalDurationMs > 0 && tvDuration != null)
            tvDuration.setText(msToTime(totalDurationMs));
    }

    /** Original-audio flow: preview always shows the track's own cover art. */
    private void showCoverPreview() {
        if (ivPhotoPreview == null || soundCover == null || soundCover.isEmpty()) return;
        Glide.with(this).load(soundCover)
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(ivPhotoPreview);
    }

    private void loadAudio() {
        if (soundUrl == null || soundUrl.isEmpty()) {
            if (layoutControls != null) layoutControls.setVisibility(View.VISIBLE);
            if (progressLoad   != null) progressLoad.setVisibility(View.GONE);
            return;
        }
        if (progressLoad != null) progressLoad.setVisibility(View.VISIBLE);
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(soundUrl);
            mediaPlayer.prepareAsync();
            mediaPlayer.setOnPreparedListener(mp -> {
                runOnUiThread(() -> {
                    if (totalDurationMs <= 0) {
                        totalDurationMs = mp.getDuration();
                        if (tvDuration != null)
                            tvDuration.setText(msToTime(totalDurationMs));
                    }
                    endMs = Math.min(endMs, totalDurationMs);
                    if (waveformView != null) {
                        waveformView.setDurationMs(totalDurationMs);
                        waveformView.generateAmplitudes(soundId != null ? soundId : soundUrl);
                    }
                    updateTimeLabels();
                    if (progressLoad   != null) progressLoad.setVisibility(View.GONE);
                    if (layoutControls != null) layoutControls.setVisibility(View.VISIBLE);
                });
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                runOnUiThread(() -> {
                    if (progressLoad != null) progressLoad.setVisibility(View.GONE);
                    Toast.makeText(this, "Cannot load audio", Toast.LENGTH_SHORT).show();
                });
                return true;
            });
        } catch (Exception e) {
            if (progressLoad != null) progressLoad.setVisibility(View.GONE);
            Toast.makeText(this, "Failed to load audio", Toast.LENGTH_SHORT).show();
        }
    }

    /** Wires the draggable waveform's handles to startMs/endMs. */
    private void setupWaveformDrag() {
        if (waveformView == null) return;
        waveformView.setOnRangeChangeListener(new AudioTrimWaveformView.OnRangeChangeListener() {
            @Override public void onRangeChanging(int newStart, int newEnd, boolean isStartHandle) {
                if (isPreviewing) stopPreview();
                startMs = newStart;
                endMs   = newEnd;
                updateTimeLabels();
            }
            @Override public void onRangeChangeFinished(int newStart, int newEnd) {
                startMs = newStart;
                endMs   = newEnd;
                updateTimeLabels();
            }
        });
    }

    private void setupPresets() {
        if (rgPresets == null) return;
        rgPresets.setOnCheckedChangeListener((group, checkedId) -> {
            stopPreview();
            if      (checkedId == R.id.rb_preset_15) snapTo(15_000);
            else if (checkedId == R.id.rb_preset_30) snapTo(30_000);
            else if (checkedId == R.id.rb_preset_60) snapTo(60_000);
        });
        // Pre-select the preset closest to the current range; default 30s.
        int span = endMs - startMs;
        int presetId = R.id.rb_preset_30;
        if (span > 0) {
            if (Math.abs(span - 15_000) <= Math.abs(span - 30_000) && Math.abs(span - 15_000) <= Math.abs(span - 60_000))
                presetId = R.id.rb_preset_15;
            else if (Math.abs(span - 60_000) <= Math.abs(span - 30_000))
                presetId = R.id.rb_preset_60;
        }
        RadioButton rb = rgPresets.findViewById(presetId);
        if (rb != null) rb.setChecked(true);
    }

    private void snapTo(int clipMs) {
        if (totalDurationMs <= 0) return;
        int maxStart = Math.max(0, totalDurationMs - clipMs);
        startMs = Math.min(startMs, maxStart);
        endMs   = Math.min(startMs + clipMs, totalDurationMs);
        updateTimeLabels();
    }

    private void setupButtons() {
        if (btnBack    != null) btnBack.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        if (btnCancel  != null) btnCancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        if (btnPreview != null) btnPreview.setOnClickListener(v -> togglePreview());
        if (btnUse     != null) btnUse.setOnClickListener(v -> useSelection());
    }

    private void togglePreview() {
        if (isPreviewing) stopPreview();
        else startPreview();
    }

    private void startPreview() {
        if (mediaPlayer == null || soundUrl == null) {
            Toast.makeText(this, "Audio not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            mediaPlayer.seekTo(startMs);
            mediaPlayer.start();
            isPreviewing = true;
            if (btnPreview != null) btnPreview.setImageResource(R.drawable.ic_pause);
            if (ivPlayHint != null) ivPlayHint.setVisibility(View.GONE);
            handler.postDelayed(previewStopCheck, 60);
        } catch (Exception e) {
            Toast.makeText(this, "Playback failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPreview() {
        if (mediaPlayer != null && isPreviewing) {
            try { mediaPlayer.pause(); } catch (Exception ignored) {}
        }
        isPreviewing = false;
        if (btnPreview != null) btnPreview.setImageResource(R.drawable.ic_play);
        if (ivPlayHint != null) ivPlayHint.setVisibility(View.VISIBLE);
        if (waveformView != null) waveformView.setPlayheadMs(-1);
        handler.removeCallbacks(previewStopCheck);
    }

    private void useSelection() {
        stopPreview();
        Intent result = new Intent();
        result.putExtra(RESULT_START_MS,    startMs);
        result.putExtra(RESULT_END_MS,      endMs);
        result.putExtra(RESULT_SOUND_ID,    soundId   != null ? soundId    : "");
        result.putExtra(RESULT_SOUND_URL,   soundUrl  != null ? soundUrl   : "");
        result.putExtra(RESULT_SOUND_TITLE, soundTitle!= null ? soundTitle : "");
        setResult(RESULT_OK, result);
        finish();
    }

    private void updateTimeLabels() {
        if (waveformView != null) waveformView.setRangeMs(startMs, endMs);
    }

    private static String msToTime(int ms) {
        int totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }
}
