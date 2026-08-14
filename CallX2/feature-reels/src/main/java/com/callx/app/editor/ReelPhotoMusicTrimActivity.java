package com.callx.app.editor;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.music.ReelTrendingAudioActivity;
import com.callx.app.music.SoundDetailSheetFragment;
import com.callx.app.reels.R;
import com.callx.app.views.AudioTrimWaveformView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ReelPhotoMusicTrimActivity — shown right after the user picks a track
 * (from ReelTrendingAudioActivity) while creating a photo-slideshow reel.
 *
 * Combines:
 *  • A live reel-style photo preview (cycles through the selected photos)
 *  • A modern draggable dual-handle waveform trimmer (AudioTrimWaveformView)
 *    plus the classic Start/End seekbars, kept in sync with each other
 *
 * The user trims the track to exactly the portion they want; only that
 * trimmed range (RESULT_START_MS…RESULT_END_MS) is stored on the reel and
 * played back at view time — the rest of the track is never used.
 *
 * Playback-side support for a trimmed range already exists
 * (ReelPlayerController / ReelPlayerFragment read reel.musicStartMs /
 * reel.musicEndMs) — this screen is what lets the uploader actually set
 * those values for photo reels instead of always defaulting to 0.
 *
 * UI note: colors used throughout this screen's layout come from the
 * @color/trim_* tokens (values/colors.xml + values-night/colors.xml), so
 * the screen automatically follows the device's light/dark mode — no
 * theme-detection code needed here beyond resolving those same tokens for
 * the custom waveform view's Canvas-drawn palette (setupWaveformPalette()).
 */
public class ReelPhotoMusicTrimActivity extends AppCompatActivity {

    public static final String EXTRA_PHOTO_URIS   = "trim_photo_uris";
    /** Per-photo slide duration in ms, used to pace the preview. Default 3000. */
    public static final String EXTRA_SLIDE_MS     = "trim_slide_ms";

    public static final String EXTRA_SOUND_ID     = "trim_sound_id";
    public static final String EXTRA_SOUND_TITLE  = "trim_sound_title";
    public static final String EXTRA_SOUND_ARTIST = "trim_sound_artist";
    public static final String EXTRA_SOUND_URL    = "trim_sound_url";
    public static final String EXTRA_SOUND_COVER  = "trim_sound_cover";
    public static final String EXTRA_DURATION_MS  = "trim_duration_ms";
    /** Optional — resume trimming an already-chosen range (e.g. re-opened via "Change"). */
    public static final String EXTRA_INITIAL_START_MS = "trim_initial_start_ms";
    public static final String EXTRA_INITIAL_END_MS   = "trim_initial_end_ms";

    public static final String RESULT_START_MS    = "result_start_ms";
    public static final String RESULT_END_MS      = "result_end_ms";
    public static final String RESULT_SOUND_ID    = "result_sound_id";
    public static final String RESULT_SOUND_TITLE = "result_sound_title";
    public static final String RESULT_SOUND_ARTIST= "result_sound_artist";
    public static final String RESULT_SOUND_URL   = "result_sound_url";
    public static final String RESULT_SOUND_COVER = "result_sound_cover";

    /** Convenience launcher. */
    public static void start(AppCompatActivity from, ArrayList<String> photoUris, int slideMs,
                              String soundId, String soundTitle, String soundArtist,
                              String soundUrl, String soundCover, int durationMs,
                              int initialStartMs, int initialEndMs, int requestCode) {
        Intent i = new Intent(from, ReelPhotoMusicTrimActivity.class);
        i.putStringArrayListExtra(EXTRA_PHOTO_URIS, photoUris);
        i.putExtra(EXTRA_SLIDE_MS,     slideMs);
        i.putExtra(EXTRA_SOUND_ID,     soundId);
        i.putExtra(EXTRA_SOUND_TITLE,  soundTitle);
        i.putExtra(EXTRA_SOUND_ARTIST, soundArtist);
        i.putExtra(EXTRA_SOUND_URL,    soundUrl);
        i.putExtra(EXTRA_SOUND_COVER,  soundCover);
        i.putExtra(EXTRA_DURATION_MS,  durationMs);
        i.putExtra(EXTRA_INITIAL_START_MS, initialStartMs);
        i.putExtra(EXTRA_INITIAL_END_MS,   initialEndMs);
        from.startActivityForResult(i, requestCode);
    }

    private ImageButton  btnBack, btnPreview;
    private TextView      btnUse, btnChange;
    private TextView     tvTitle, tvStartTime, tvEndTime, tvDuration, tvSelectedRange;
    private TextView     tvStartValue, tvEndValue;
    private TextView     tvTrackTitle, tvTrackArtist;
    private ImageView    ivCover, ivPhotoPreview, ivPlayHint;
    private View          rowTrackInfo;
    private SeekBar      sbStart, sbEnd;
    private AudioTrimWaveformView waveformView;
    private RadioGroup   rgPresets;
    private ProgressBar  progressLoad;
    private View         layoutControls;

    private static final int REQ_CHANGE_MUSIC = 771;

    private String soundId, soundUrl, soundTitle, soundArtist, soundCover;
    private int    totalDurationMs;
    private int    startMs = 0;
    private int    endMs   = 30_000;

    /** Guards against feedback loops while seekbars/waveform sync each other. */
    private boolean syncingRange = false;

    private List<String> photoUris = new ArrayList<>();
    private int slideMs = 3000;
    private int previewPhotoIndex = 0;

    private MediaPlayer mediaPlayer;
    private boolean     isPreviewing = false;

    private final Handler handler       = new Handler(Looper.getMainLooper());
    private final Handler slideHandler  = new Handler(Looper.getMainLooper());

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

    /** Advances the photo preview to the next slide while previewing. */
    private final Runnable slideRunnable = new Runnable() {
        @Override public void run() {
            if (!isPreviewing || photoUris.isEmpty()) return;
            previewPhotoIndex = (previewPhotoIndex + 1) % photoUris.size();
            showPhoto(previewPhotoIndex);
            slideHandler.postDelayed(this, slideMs);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_photo_music_trim);

        soundId       = getIntent().getStringExtra(EXTRA_SOUND_ID);
        soundUrl      = getIntent().getStringExtra(EXTRA_SOUND_URL);
        soundTitle    = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
        soundArtist   = getIntent().getStringExtra(EXTRA_SOUND_ARTIST);
        soundCover    = getIntent().getStringExtra(EXTRA_SOUND_COVER);
        totalDurationMs = getIntent().getIntExtra(EXTRA_DURATION_MS, 0);
        slideMs       = getIntent().getIntExtra(EXTRA_SLIDE_MS, 3000);
        if (slideMs <= 0) slideMs = 3000;

        ArrayList<String> uris = getIntent().getStringArrayListExtra(EXTRA_PHOTO_URIS);
        if (uris != null) photoUris = uris;

        int initStart = getIntent().getIntExtra(EXTRA_INITIAL_START_MS, 0);
        int initEnd   = getIntent().getIntExtra(EXTRA_INITIAL_END_MS, 0);
        if (initEnd > initStart) { startMs = initStart; endMs = initEnd; }

        bindViews();
        setupWaveformPalette();
        populateInfo();
        showPhoto(0);
        loadAudio();
        setupSeekbars();
        setupWaveformDrag();
        setupPresets();
        setupButtons();
    }

    private void bindViews() {
        btnBack         = findViewById(R.id.btn_trim_back);
        btnPreview      = findViewById(R.id.btn_trim_preview);
        btnUse          = findViewById(R.id.btn_trim_use);
        btnChange       = findViewById(R.id.btn_trim_change);
        tvTitle         = findViewById(R.id.tv_trim_title);
        tvDuration      = findViewById(R.id.tv_trim_total_duration);
        tvTrackTitle    = findViewById(R.id.tv_trim_title_track);
        tvTrackArtist   = findViewById(R.id.tv_trim_artist);
        ivCover         = findViewById(R.id.iv_trim_cover);
        rowTrackInfo    = findViewById(R.id.row_trim_track_info);
        ivPhotoPreview  = findViewById(R.id.iv_photo_preview);
        ivPlayHint      = findViewById(R.id.iv_preview_play_hint);
        sbStart         = findViewById(R.id.sb_trim_start);
        sbEnd           = findViewById(R.id.sb_trim_end);
        waveformView    = findViewById(R.id.waveform_trim_view);
        rgPresets       = findViewById(R.id.rg_trim_presets);
        progressLoad    = findViewById(R.id.progress_trim_load);
        layoutControls  = findViewById(R.id.layout_trim_controls);

        View.OnClickListener togglePreviewClick = v -> togglePreview();
        if (ivPhotoPreview != null) ivPhotoPreview.setOnClickListener(togglePreviewClick);
        if (ivPlayHint      != null) ivPlayHint.setOnClickListener(togglePreviewClick);
    }

    /**
     * Resolves the waveform's Canvas-drawn colors from the day/night-aware
     * @color/trim_* resources so it matches the rest of the (XML-themed)
     * screen in both light and dark mode.
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
        if (tvTitle != null) tvTitle.setText("Adjust Music");
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

    private void showPhoto(int index) {
        if (ivPhotoPreview == null || photoUris.isEmpty()) return;
        if (index < 0 || index >= photoUris.size()) index = 0;
        String uriStr = photoUris.get(index);
        Glide.with(this).load(Uri.parse(uriStr))
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
                    setupSeekbarsRange();
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

    private void setupSeekbars() {
        if (sbStart == null || sbEnd == null) return;
        if (totalDurationMs > 0) setupSeekbarsRange();

        sbStart.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (syncingRange) return;
                startMs = p;
                if (startMs >= endMs - 1000) {
                    startMs = Math.max(0, endMs - 1000);
                    sb.setProgress(startMs);
                }
                updateTimeLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { stopPreview(); }
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (syncingRange) return;
                endMs = p;
                if (endMs <= startMs + 1000) {
                    endMs = Math.min(totalDurationMs, startMs + 1000);
                    sb.setProgress(endMs);
                }
                updateTimeLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { stopPreview(); }
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupSeekbarsRange() {
        if (sbStart == null || sbEnd == null || totalDurationMs <= 0) return;
        syncingRange = true;
        sbStart.setMax(totalDurationMs);
        sbStart.setProgress(startMs);
        sbEnd.setMax(totalDurationMs);
        sbEnd.setProgress(Math.min(endMs, totalDurationMs));
        syncingRange = false;
    }

    /** Wires the draggable waveform's handles to the same startMs/endMs state as the seekbars. */
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
        // Pre-select the preset closest to any resumed range; default 30s.
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
        setupSeekbarsRange();
        updateTimeLabels();
    }

    private void setupButtons() {
        if (btnBack      != null) btnBack.setOnClickListener(v -> finish());
        if (btnPreview    != null) btnPreview.setOnClickListener(v -> togglePreview());
        if (btnUse        != null) btnUse.setOnClickListener(v -> useSelection());
        if (btnChange     != null) btnChange.setOnClickListener(v -> openChangeMusicPicker());
        if (rowTrackInfo  != null) rowTrackInfo.setOnClickListener(v -> openSoundDetailSheet());
    }

    /** "Change" button — reopens the trending audio picker to swap the track. */
    private void openChangeMusicPicker() {
        stopPreview();
        startActivityForResult(
            new Intent(this, ReelTrendingAudioActivity.class), REQ_CHANGE_MUSIC);
    }

    /** Tapping the original-audio row opens the sound details bottom sheet for it. */
    private void openSoundDetailSheet() {
        if (soundUrl == null || soundUrl.isEmpty()) return;
        stopPreview();
        SoundDetailSheetFragment sheet = SoundDetailSheetFragment.newInstance(
            soundId, soundTitle, soundArtist, soundCover, soundUrl, totalDurationMs);
        sheet.show(getSupportFragmentManager(), "sound_detail");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CHANGE_MUSIC || resultCode != RESULT_OK || data == null) return;

        String newUrl = data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_URL);
        if (newUrl == null || newUrl.isEmpty()) return;

        soundId     = data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_ID);
        soundTitle  = data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_TITLE);
        soundArtist = data.getStringExtra(ReelTrendingAudioActivity.RESULT_AUDIO_ARTIST);
        soundUrl    = newUrl;
        soundCover  = data.getStringExtra(ReelTrendingAudioActivity.RESULT_COVER_URL);

        // Reset the trim range/duration for the newly picked track.
        totalDurationMs = 0;
        startMs = 0;
        endMs   = 30_000;

        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }

        populateInfo();
        if (layoutControls != null) layoutControls.setVisibility(View.GONE);
        loadAudio();
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
            previewPhotoIndex = 0;
            showPhoto(previewPhotoIndex);
            if (photoUris.size() > 1) slideHandler.postDelayed(slideRunnable, slideMs);
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
        slideHandler.removeCallbacks(slideRunnable);
    }

    private void useSelection() {
        stopPreview();
        Intent result = new Intent();
        result.putExtra(RESULT_START_MS,     startMs);
        result.putExtra(RESULT_END_MS,       endMs);
        result.putExtra(RESULT_SOUND_ID,     soundId     != null ? soundId     : "");
        result.putExtra(RESULT_SOUND_TITLE,  soundTitle  != null ? soundTitle  : "");
        result.putExtra(RESULT_SOUND_ARTIST, soundArtist != null ? soundArtist : "");
        result.putExtra(RESULT_SOUND_URL,    soundUrl    != null ? soundUrl    : "");
        result.putExtra(RESULT_SOUND_COVER,  soundCover  != null ? soundCover  : "");
        setResult(RESULT_OK, result);
        finish();
    }

    private void updateTimeLabels() {
        if (tvStartTime     != null) tvStartTime.setText(msToTime(startMs));
        if (tvEndTime       != null) tvEndTime.setText(msToTime(endMs));
        if (tvSelectedRange != null)
            tvSelectedRange.setText("Selected: " + msToTime(startMs) + " – " + msToTime(endMs));
        if (tvStartValue    != null) tvStartValue.setText(msToTimeTenths(startMs));
        if (tvEndValue       != null) tvEndValue.setText(msToTimeTenths(endMs));

        // Keep the seekbars and the draggable waveform in lockstep with whichever
        // control the user just moved, without re-triggering each other's listeners.
        syncingRange = true;
        if (sbStart != null && sbStart.getProgress() != startMs) sbStart.setProgress(startMs);
        if (sbEnd   != null && sbEnd.getProgress()   != endMs)   sbEnd.setProgress(endMs);
        syncingRange = false;
        if (waveformView != null) waveformView.setRangeMs(startMs, endMs);
    }

    private static String msToTime(int ms) {
        int totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    /** Same as msToTime but with a tenths-of-a-second digit, e.g. "0:01.2". */
    private static String msToTimeTenths(int ms) {
        int totalTenths = ms / 100;
        int sec = totalTenths / 10;
        int tenth = totalTenths % 10;
        return String.format(Locale.US, "%d:%02d.%d", sec / 60, sec % 60, tenth);
    }

    @Override
    public void onBackPressed() {
        // Back without an explicit "Use" tap = cancel (caller keeps prior state).
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        slideHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }
}
