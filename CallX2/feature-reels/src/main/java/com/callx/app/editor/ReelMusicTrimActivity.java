package com.callx.app.editor;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.callx.app.reels.R;

import java.util.Locale;

/**
 * ReelMusicTrimActivity — Select which portion of a track to use in a Reel.
 *
 * Features:
 *  ✅ Loads full audio track via MediaPlayer
 *  ✅ Dual-handle range seekbar: choose start + end point within track
 *  ✅ Displays total duration, selected range, and remaining available length
 *  ✅ Live preview playback of selected range
 *  ✅ Snaps to 15 / 30 / 60 second preset clips
 *  ✅ Returns EXTRA_START_MS + EXTRA_END_MS to caller on "Use"
 *  ✅ Waveform bar animated while previewing
 */
public class ReelMusicTrimActivity extends AppCompatActivity {

    public static final String EXTRA_SOUND_ID    = "trim_sound_id";
    public static final String EXTRA_SOUND_TITLE = "trim_sound_title";
    public static final String EXTRA_SOUND_URL   = "trim_sound_url";
    public static final String EXTRA_DURATION_MS = "trim_duration_ms";

    public static final String RESULT_START_MS   = "result_start_ms";
    public static final String RESULT_END_MS     = "result_end_ms";
    public static final String RESULT_SOUND_ID   = "result_sound_id";
    public static final String RESULT_SOUND_URL  = "result_sound_url";
    public static final String RESULT_SOUND_TITLE= "result_sound_title";

    private ImageButton  btnBack, btnPreview, btnUse;
    private TextView     tvTitle, tvStartTime, tvEndTime, tvDuration, tvSelectedRange;
    private SeekBar      sbStart, sbEnd;
    private LinearLayout layoutWaveform;
    private RadioGroup   rgPresets;
    private ProgressBar  progressLoad;
    private View         layoutControls;

    private String soundId, soundUrl, soundTitle;
    private int    totalDurationMs;
    private int    startMs = 0;
    private int    endMs   = 30_000;

    private MediaPlayer mediaPlayer;
    private boolean     isPreviewing = false;

    private final Handler handler     = new Handler(Looper.getMainLooper());
    private final Handler waveHandler = new Handler(Looper.getMainLooper());

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
                    int newH = (int)((10 + rng.nextInt(32))
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_music_trim);

        soundId       = getIntent().getStringExtra(EXTRA_SOUND_ID);
        soundUrl      = getIntent().getStringExtra(EXTRA_SOUND_URL);
        soundTitle    = getIntent().getStringExtra(EXTRA_SOUND_TITLE);
        totalDurationMs = getIntent().getIntExtra(EXTRA_DURATION_MS, 0);

        bindViews();
        populateInfo();
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
        sbStart         = findViewById(R.id.sb_trim_start);
        sbEnd           = findViewById(R.id.sb_trim_end);
        layoutWaveform  = findViewById(R.id.layout_trim_waveform);
        rgPresets       = findViewById(R.id.rg_trim_presets);
        progressLoad    = findViewById(R.id.progress_trim_load);
        layoutControls  = findViewById(R.id.layout_trim_controls);
    }

    private void populateInfo() {
        if (tvTitle != null)
            tvTitle.setText(soundTitle != null ? soundTitle : "Trim Audio");
        updateTimeLabels();
        if (totalDurationMs > 0 && tvDuration != null)
            tvDuration.setText("Total: " + msToTime(totalDurationMs));
    }

    private void buildWaveform() {
        if (layoutWaveform == null) return;
        layoutWaveform.removeAllViews();
        float dpL = getResources().getDisplayMetrics().density;
        int bW=(int)(4*dpL), g=(int)(2*dpL);
        for (int b=0; b<60; b++) {
            View bar = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(bW,(int)(18*dpL));
            lp.setMargins(g,0,g,0); lp.gravity = android.view.Gravity.BOTTOM;
            bar.setLayoutParams(lp); bar.setBackgroundColor(0x55FFFFFF); bar.setTag("wBar");
            layoutWaveform.addView(bar);
        }
        if (soundUrl != null && !soundUrl.isEmpty()) buildRealWaveformAsync();
    }

    private void buildRealWaveformAsync() {
        new Thread(() -> {
            try {
                String key = "wf_" + Math.abs(soundUrl.hashCode()) + ".aac";
                java.io.File tmp = new java.io.File(getCacheDir(), key);
                if (!tmp.exists() || tmp.length() == 0) {
                    java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(soundUrl).openConnection();
                    conn.setConnectTimeout(10_000); conn.setReadTimeout(20_000);
                    try (java.io.InputStream in = conn.getInputStream();
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) >= 0) fos.write(buf, 0, n);
                    }
                    conn.disconnect();
                }
                android.media.MediaExtractor ext = new android.media.MediaExtractor();
                ext.setDataSource(tmp.getAbsolutePath());
                int track = -1;
                for (int i = 0; i < ext.getTrackCount(); i++) {
                    String m = ext.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("audio/")) { track = i; break; }
                }
                if (track < 0) { ext.release(); return; }
                ext.selectTrack(track);
                android.media.MediaFormat fmt = ext.getTrackFormat(track);
                android.media.MediaCodec codec = android.media.MediaCodec.createDecoderByType(
                    fmt.getString(android.media.MediaFormat.KEY_MIME));
                codec.configure(fmt, null, null, 0); codec.start();
                java.util.ArrayList<Short> samples = new java.util.ArrayList<>(44100 * 60);
                android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                boolean inDone = false;
                while (true) {
                    if (!inDone) {
                        int ii = codec.dequeueInputBuffer(8_000);
                        if (ii >= 0) {
                            java.nio.ByteBuffer ib = codec.getInputBuffer(ii);
                            int sz = ext.readSampleData(ib, 0);
                            if (sz < 0) {
                                codec.queueInputBuffer(ii,0,0,0,android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inDone = true;
                            } else { codec.queueInputBuffer(ii,0,sz,ext.getSampleTime(),0); ext.advance(); }
                        }
                    }
                    int oi = codec.dequeueOutputBuffer(info, 8_000);
                    if (oi >= 0) {
                        java.nio.ByteBuffer ob = codec.getOutputBuffer(oi);
                        if (ob != null && info.size > 0) {
                            java.nio.ShortBuffer sb = ob.asShortBuffer();
                            while (sb.hasRemaining()) samples.add(sb.get());
                        }
                        codec.releaseOutputBuffer(oi, false);
                        if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    }
                }
                codec.stop(); codec.release(); ext.release();
                final int BARS = 60;
                float[] rms = new float[BARS];
                int perBar = Math.max(1, samples.size() / BARS);
                for (int b = 0; b < BARS; b++) {
                    long sq = 0; int s0=b*perBar, s1=Math.min(s0+perBar, samples.size());
                    for (int j=s0; j<s1; j++) { long v=samples.get(j); sq+=v*v; }
                    rms[b] = (float)Math.sqrt((double)sq / Math.max(1, s1-s0));
                }
                float maxR = 1f; for (float r : rms) if (r > maxR) maxR = r;
                final float[] norm = new float[BARS];
                for (int b=0; b<BARS; b++) norm[b] = rms[b] / maxR;
                float dp2 = getResources().getDisplayMetrics().density;
                int minH=(int)(4*dp2), maxH=(int)(44*dp2);
                runOnUiThread(() -> {
                    if (layoutWaveform == null || isFinishing()) return;
                    for (int b=0; b<Math.min(BARS, layoutWaveform.getChildCount()); b++) {
                        View bar = layoutWaveform.getChildAt(b); if (bar == null) continue;
                        int h = minH + (int)((maxH-minH)*norm[b]);
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bar.getLayoutParams();
                        lp.height = h; bar.setLayoutParams(lp);
                        float pct = (float)b/BARS, totS = totalDurationMs>0 ? totalDurationMs/1000f : 1f;
                        boolean inR = pct >= startMs/1000f/totS && pct <= endMs/1000f/totS;
                        bar.setBackgroundColor(inR ? 0xFFFFFFFF : 0x44FFFFFF);
                    }
                });
            } catch (Exception ignored) {}
        }, "WaveformBuild").start();
    }

    @SuppressWarnings("unused")
    private void buildWaveform_LEGACY() {
        if (layoutWaveform == null) return;
        layoutWaveform.removeAllViews();
        java.util.Random rng = new java.util.Random(soundUrl != null ? soundUrl.hashCode() : 0);
        int bars = 40;
        int barW = (int)(4 * getResources().getDisplayMetrics().density);
        int gap  = (int)(2 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < bars; i++) {
            View bar = new View(this);
            int h = (int)((10 + rng.nextInt(32)) * getResources().getDisplayMetrics().density);
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
        RadioButton rb30 = rgPresets.findViewById(R.id.rb_preset_30);
        if (rb30 != null) rb30.setChecked(true);
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
            handler.postDelayed(previewStopCheck, 100);
            waveHandler.post(waveRunnable);
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
        handler.removeCallbacks(previewStopCheck);
        waveHandler.removeCallbacks(waveRunnable);
        buildWaveform();
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
        if (tvStartTime     != null) tvStartTime.setText(msToTime(startMs));
        if (tvEndTime       != null) tvEndTime.setText(msToTime(endMs));
        if (tvSelectedRange != null)
            tvSelectedRange.setText("Selected: " + msToTime(endMs - startMs));
    }

    private static String msToTime(int ms) {
        int totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        waveHandler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            mediaPlayer.release();
            mediaPlayer = null;
        }
        super.onDestroy();
    }
}
