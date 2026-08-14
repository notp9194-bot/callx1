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

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.callx.app.reels.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ReelPhotoMusicTrimActivity — shown right after the user picks a track
 * (from ReelTrendingAudioActivity) while creating a photo-slideshow reel.
 *
 * Combines:
 *  • A live reel-style photo preview (cycles through the selected photos)
 *  • The same dual-handle trim controls used elsewhere in the app
 *
 * The user trims the track to exactly the portion they want; only that
 * trimmed range (RESULT_START_MS…RESULT_END_MS) is stored on the reel and
 * played back at view time — the rest of the track is never used.
 *
 * Playback-side support for a trimmed range already exists
 * (ReelPlayerController / ReelPlayerFragment read reel.musicStartMs /
 * reel.musicEndMs) — this screen is what lets the uploader actually set
 * those values for photo reels instead of always defaulting to 0.
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

    private ImageButton  btnBack, btnPreview, btnUse;
    private TextView     tvTitle, tvStartTime, tvEndTime, tvDuration, tvSelectedRange;
    private TextView     tvTrackTitle, tvTrackArtist;
    private ImageView    ivCover, ivPhotoPreview, ivPlayHint;
    private SeekBar      sbStart, sbEnd;
    private LinearLayout layoutWaveform;
    private RadioGroup   rgPresets;
    private ProgressBar  progressLoad;
    private View         layoutControls;

    private String soundId, soundUrl, soundTitle, soundArtist, soundCover;
    private int    totalDurationMs;
    private int    startMs = 0;
    private int    endMs   = 30_000;

    private List<String> photoUris = new ArrayList<>();
    private int slideMs = 3000;
    private int previewPhotoIndex = 0;

    private MediaPlayer mediaPlayer;
    private boolean     isPreviewing = false;

    private final Handler handler       = new Handler(Looper.getMainLooper());
    private final Handler waveHandler   = new Handler(Looper.getMainLooper());
    private final Handler slideHandler  = new Handler(Looper.getMainLooper());

    private final Runnable previewStopCheck = new Runnable() {
        @Override public void run() {
            if (mediaPlayer != null && isPreviewing) {
                if (mediaPlayer.getCurrentPosition() >= endMs) {
                    stopPreview();
                } else {
                    handler.postDelayed(this, 100);
                }
            }
        }
    };

    private final Runnable waveRunnable = new Runnable() {
        @Override public void run() {
            if (!isPreviewing || layoutWaveform == null) return;
            java.util.Random rng = new java.util.Random();
            for (int i = 0; i < layoutWaveform.getChildCount(); i++) {
                View bar = layoutWaveform.getChildAt(i);
                if ("wBar".equals(bar.getTag())) {
                    int newH = (int)((8 + rng.nextInt(26))
                        * getResources().getDisplayMetrics().density);
                    LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams) bar.getLayoutParams();
                    lp.height = newH;
                    bar.setLayoutParams(lp);
                }
            }
            waveHandler.postDelayed(this, 100);
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
        populateInfo();
        showPhoto(0);
        buildWaveform();
        loadAudio();
        setupSeekbars();
        setupPresets();
        setupButtons();
    }

    private void bindViews() {
        btnBack         = findViewById(R.id.btn_trim_back);
        btnPreview      = findViewById(R.id.btn_trim_preview);
        btnUse          = findViewById(R.id.btn_trim_use);
        tvTitle         = findViewById(R.id.tv_trim_title);
        tvStartTime     = findViewById(R.id.tv_trim_start_time);
        tvEndTime       = findViewById(R.id.tv_trim_end_time);
        tvDuration      = findViewById(R.id.tv_trim_total_duration);
        tvSelectedRange = findViewById(R.id.tv_trim_selected_range);
        tvTrackTitle    = findViewById(R.id.tv_trim_title_track);
        tvTrackArtist   = findViewById(R.id.tv_trim_artist);
        ivCover         = findViewById(R.id.iv_trim_cover);
        ivPhotoPreview  = findViewById(R.id.iv_photo_preview);
        ivPlayHint      = findViewById(R.id.iv_preview_play_hint);
        sbStart         = findViewById(R.id.sb_trim_start);
        sbEnd           = findViewById(R.id.sb_trim_end);
        layoutWaveform  = findViewById(R.id.layout_trim_waveform);
        rgPresets       = findViewById(R.id.rg_trim_presets);
        progressLoad    = findViewById(R.id.progress_trim_load);
        layoutControls  = findViewById(R.id.layout_trim_controls);

        View.OnClickListener togglePreviewClick = v -> togglePreview();
        if (ivPhotoPreview != null) ivPhotoPreview.setOnClickListener(togglePreviewClick);
        if (ivPlayHint      != null) ivPlayHint.setOnClickListener(togglePreviewClick);
    }

    private void populateInfo() {
        if (tvTitle != null) tvTitle.setText("Adjust Music");
        if (tvTrackTitle != null)
            tvTrackTitle.setText(soundTitle != null && !soundTitle.isEmpty() ? soundTitle : "Track");
        if (tvTrackArtist != null)
            tvTrackArtist.setText(soundArtist != null && !soundArtist.isEmpty() ? soundArtist : "—");
        if (ivCover != null && soundCover != null && !soundCover.isEmpty()) {
            Glide.with(this).load(soundCover)
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(ivCover);
        }
        updateTimeLabels();
        if (totalDurationMs > 0 && tvDuration != null)
            tvDuration.setText("Total: " + msToTime(totalDurationMs));
    }

    private void showPhoto(int index) {
        if (ivPhotoPreview == null || photoUris.isEmpty()) return;
        if (index < 0 || index >= photoUris.size()) index = 0;
        String uriStr = photoUris.get(index);
        Glide.with(this).load(Uri.parse(uriStr))
            .transition(DrawableTransitionOptions.withCrossFade())
            .into(ivPhotoPreview);
    }

    private void buildWaveform() {
        if (layoutWaveform == null) return;
        layoutWaveform.removeAllViews();
        float dpL = getResources().getDisplayMetrics().density;
        int barW = (int)(4 * dpL), gap = (int)(2 * dpL);
        java.util.Random rng = new java.util.Random();
        for (int b = 0; b < 48; b++) {
            View bar = new View(this);
            int h = (int)((8 + rng.nextInt(26)) * dpL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(barW, h);
            lp.setMargins(gap, 0, gap, 0);
            lp.gravity = android.view.Gravity.BOTTOM;
            bar.setLayoutParams(lp);
            bar.setBackgroundColor(0x55FFFFFF);
            bar.setTag("wBar");
            layoutWaveform.addView(bar);
        }
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
                            tvDuration.setText("Total: " + msToTime(totalDurationMs));
                    }
                    endMs = Math.min(endMs, totalDurationMs);
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
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                startMs = p;
                if (startMs >= endMs - 1000) {
                    startMs = Math.max(0, endMs - 1000);
                    sbStart.setProgress(startMs);
                }
                updateTimeLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { stopPreview(); }
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        sbEnd.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                endMs = p;
                if (endMs <= startMs + 1000) {
                    endMs = Math.min(totalDurationMs, startMs + 1000);
                    sbEnd.setProgress(endMs);
                }
                updateTimeLabels();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { stopPreview(); }
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
    }

    private void setupSeekbarsRange() {
        if (sbStart == null || sbEnd == null || totalDurationMs <= 0) return;
        sbStart.setMax(totalDurationMs);
        sbStart.setProgress(startMs);
        sbEnd.setMax(totalDurationMs);
        sbEnd.setProgress(Math.min(endMs, totalDurationMs));
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
        if (btnBack    != null) btnBack.setOnClickListener(v -> finish());
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
            handler.postDelayed(previewStopCheck, 100);
            waveHandler.post(waveRunnable);
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
        handler.removeCallbacks(previewStopCheck);
        waveHandler.removeCallbacks(waveRunnable);
        slideHandler.removeCallbacks(slideRunnable);
        buildWaveform();
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
            tvSelectedRange.setText("Selected: " + msToTime(Math.max(0, endMs - startMs)));
    }

    private static String msToTime(int ms) {
        int totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
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
        waveHandler.removeCallbacksAndMessages(null);
        slideHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }
}
