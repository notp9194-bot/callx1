package com.callx.app.activities;

import android.media.MediaPlayer;
import android.media.audiofx.BassBoost;
import android.media.audiofx.Equalizer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.audiofx.PresetReverb;
import android.media.audiofx.Virtualizer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.util.*;

/**
 * ReelEqualizerActivity — 5-band + extras audio equalizer for reel audio.
 *
 * Features:
 *  ✅ 5-band parametric equalizer (Sub Bass / Bass / Mid / Presence / Treble)
 *      using Android's native AudioEffect Equalizer API
 *  ✅ Bass Boost slider (0–1000 strength)
 *  ✅ Virtualizer (stereo widening) slider
 *  ✅ Loudness Enhancer (0–1000 mB)
 *  ✅ 8 preset buttons: Flat | Pop | Rock | Jazz | Classical | Hip-Hop | Dance | Podcast
 *  ✅ Live preview of any audio path with effects applied in real-time
 *  ✅ Returns band levels + bass boost + virtualizer strength to caller
 *  ✅ Reset to flat
 *
 * Input extras:
 *   EXTRA_AUDIO_PATH — local audio file to preview (optional)
 *
 * Output extras (RESULT_OK):
 *   RESULT_BAND_LEVELS  — int[] (5 values, millibels from –1500 to +1500)
 *   RESULT_BASS_BOOST   — int (0–1000)
 *   RESULT_VIRTUALIZER  — int (0–1000)
 *   RESULT_LOUDNESS     — int (0–1000)
 *   RESULT_PRESET_NAME  — String
 */
public class ReelEqualizerActivity extends AppCompatActivity {

    public static final String EXTRA_AUDIO_PATH  = "eq_audio_path";
    public static final String RESULT_BAND_LEVELS  = "result_eq_bands";
    public static final String RESULT_BASS_BOOST   = "result_eq_bass";
    public static final String RESULT_VIRTUALIZER  = "result_eq_virtual";
    public static final String RESULT_LOUDNESS     = "result_eq_loudness";
    public static final String RESULT_PRESET_NAME  = "result_eq_preset";

    private static final String[] BAND_NAMES = {"Sub Bass", "Bass", "Mid", "Presence", "Treble"};

    /** EQ presets: 5 band levels in millibels (–1500 to +1500) */
    private static final int[][] EQ_PRESETS = {
        {     0,    0,    0,    0,    0 },   // Flat
        {   300,  200,    0,  300,  400 },   // Pop
        {   500,  300,  -200, 400,  500 },   // Rock
        {   400,  100,  300,  200,  -100},   // Jazz
        {   500, -200,  100, -300,  500 },   // Classical
        {   600,  400,  100, -200,  200 },   // Hip-Hop
        {   200,  500,  -100, 400,  300 },   // Dance
        {  -100, -200,  500,  300, -200 },   // Podcast
    };
    private static final String[] PRESET_NAMES = {
        "Flat", "Pop", "Rock", "Jazz", "Classical", "Hip-Hop", "Dance", "Podcast"
    };

    private int[]  currentBands  = {0, 0, 0, 0, 0};
    private int    bassBoost     = 0;
    private int    virtualizer   = 0;
    private int    loudness      = 0;
    private String currentPreset = "Flat";

    private SeekBar[] sbBands = new SeekBar[5];
    private TextView[] tvBandValues = new TextView[5];
    private SeekBar sbBassBoost, sbVirtualizer, sbLoudness;
    private TextView tvBassVal, tvVirtualVal, tvLoudnessVal;
    private RecyclerView rvPresets;
    private TextView btnApply, btnReset, btnPreview;
    private ImageButton btnBack;
    private ProgressBar progressEq;

    private MediaPlayer  player;
    private Equalizer    equalizer;
    private BassBoost    bassEffect;
    private Virtualizer  virtualEffect;
    private LoudnessEnhancer loudnessEffect;
    private boolean      isPreviewing = false;
    private int          selectedPresetIdx = 0;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_equalizer);
        bindViews();
        setupBandSliders();
        setupExtraSliders();
        buildPresetStrip();
        applyPreset(0);
    }

    private void bindViews() {
        btnBack      = findViewById(R.id.btn_eq_back);
        btnApply     = findViewById(R.id.btn_eq_apply);
        btnReset     = findViewById(R.id.btn_eq_reset);
        btnPreview   = findViewById(R.id.btn_eq_preview);
        rvPresets    = findViewById(R.id.rv_eq_presets);
        progressEq   = findViewById(R.id.progress_eq);
        sbBassBoost  = findViewById(R.id.sb_eq_bass_boost);
        sbVirtualizer= findViewById(R.id.sb_eq_virtualizer);
        sbLoudness   = findViewById(R.id.sb_eq_loudness);
        tvBassVal    = findViewById(R.id.tv_eq_bass_val);
        tvVirtualVal = findViewById(R.id.tv_eq_virtual_val);
        tvLoudnessVal= findViewById(R.id.tv_eq_loudness_val);

        int[] bandSeekIds = {R.id.sb_eq_band0, R.id.sb_eq_band1, R.id.sb_eq_band2, R.id.sb_eq_band3, R.id.sb_eq_band4};
        int[] bandValIds  = {R.id.tv_eq_band0_val, R.id.tv_eq_band1_val, R.id.tv_eq_band2_val, R.id.tv_eq_band3_val, R.id.tv_eq_band4_val};
        for (int i = 0; i < 5; i++) {
            sbBands[i]     = findViewById(bandSeekIds[i]);
            tvBandValues[i]= findViewById(bandValIds[i]);
        }

        if (btnBack  != null) btnBack.setOnClickListener(v -> finish());
        if (btnReset != null) btnReset.setOnClickListener(v -> applyPreset(0));
        if (btnPreview != null) btnPreview.setOnClickListener(v -> togglePreview());
        if (btnApply != null) btnApply.setOnClickListener(v -> returnResult());
    }

    private void setupBandSliders() {
        for (int i = 0; i < 5; i++) {
            if (sbBands[i] == null) continue;
            sbBands[i].setMax(3000);     // 0 = –1500 mB, 1500 = 0 mB, 3000 = +1500 mB
            sbBands[i].setProgress(1500);// centre = 0 mB
            final int band = i;
            final TextView label = tvBandValues[i];
            sbBands[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    currentBands[band] = p - 1500; // millibels
                    if (label != null) label.setText(formatMb(currentBands[band]));
                    if (isPreviewing && equalizer != null) {
                        try { equalizer.setBandLevel((short) band, (short) currentBands[band]); }
                        catch (Exception ignored) {}
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
    }

    private void setupExtraSliders() {
        if (sbBassBoost != null) {
            sbBassBoost.setMax(1000);
            sbBassBoost.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    bassBoost = p;
                    if (tvBassVal != null) tvBassVal.setText(p + "");
                    if (isPreviewing && bassEffect != null) {
                        try { bassEffect.setStrength((short) p); } catch (Exception ignored) {}
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
        if (sbVirtualizer != null) {
            sbVirtualizer.setMax(1000);
            sbVirtualizer.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    virtualizer = p;
                    if (tvVirtualVal != null) tvVirtualVal.setText(p + "");
                    if (isPreviewing && virtualEffect != null) {
                        try { virtualEffect.setStrength((short) p); } catch (Exception ignored) {}
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
        if (sbLoudness != null) {
            sbLoudness.setMax(1000);
            sbLoudness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    loudness = p;
                    if (tvLoudnessVal != null) tvLoudnessVal.setText(p + " mB");
                    if (isPreviewing && loudnessEffect != null) {
                        try { loudnessEffect.setTargetGain(p); } catch (Exception ignored) {}
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
    }

    private void buildPresetStrip() {
        if (rvPresets == null) return;
        rvPresets.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvPresets.setAdapter(new PresetAdapter());
    }

    private void applyPreset(int idx) {
        if (idx < 0 || idx >= EQ_PRESETS.length) return;
        selectedPresetIdx = idx;
        currentPreset = PRESET_NAMES[idx];
        int[] levels = EQ_PRESETS[idx];
        for (int i = 0; i < 5; i++) {
            currentBands[i] = levels[i];
            if (sbBands[i]     != null) sbBands[i].setProgress(levels[i] + 1500);
            if (tvBandValues[i]!= null) tvBandValues[i].setText(formatMb(levels[i]));
            if (isPreviewing && equalizer != null) {
                try { equalizer.setBandLevel((short) i, (short) levels[i]); } catch (Exception ignored) {}
            }
        }
        if (rvPresets != null && rvPresets.getAdapter() != null)
            rvPresets.getAdapter().notifyDataSetChanged();
    }

    // ── Preview ───────────────────────────────────────────────────────────

    private void togglePreview() { if (isPreviewing) stopPreview(); else startPreview(); }

    private void startPreview() {
        String path = getIntent().getStringExtra(EXTRA_AUDIO_PATH);
        if (path == null || path.isEmpty()) { Toast.makeText(this, "No audio to preview", Toast.LENGTH_SHORT).show(); return; }
        try {
            player = new MediaPlayer();
            player.setDataSource(path);
            player.setLooping(true);
            player.prepare();

            // Attach effects
            int session = player.getAudioSessionId();
            try {
                equalizer = new Equalizer(0, session);
                for (int i = 0; i < 5; i++) {
                    equalizer.setBandLevel((short) i, (short) currentBands[i]);
                }
                equalizer.setEnabled(true);
            } catch (Exception ignored) {}

            try {
                bassEffect = new BassBoost(0, session);
                bassEffect.setStrength((short) bassBoost);
                bassEffect.setEnabled(bassBoost > 0);
            } catch (Exception ignored) {}

            try {
                virtualEffect = new Virtualizer(0, session);
                virtualEffect.setStrength((short) virtualizer);
                virtualEffect.setEnabled(virtualizer > 0);
            } catch (Exception ignored) {}

            try {
                loudnessEffect = new LoudnessEnhancer(session);
                loudnessEffect.setTargetGain(loudness);
                loudnessEffect.setEnabled(loudness > 0);
            } catch (Exception ignored) {}

            player.start();
            isPreviewing = true;
            if (btnPreview != null) btnPreview.setText("Stop");
            player.setOnCompletionListener(mp -> handler.post(this::stopPreview));
        } catch (Exception e) {
            Toast.makeText(this, "Preview failed", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopPreview() {
        isPreviewing = false;
        if (btnPreview != null) btnPreview.setText("Preview");
        releaseEffects();
        if (player != null) {
            try { player.stop(); player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void releaseEffects() {
        if (equalizer     != null) { try { equalizer.release();      } catch (Exception ignored) {} equalizer      = null; }
        if (bassEffect    != null) { try { bassEffect.release();     } catch (Exception ignored) {} bassEffect     = null; }
        if (virtualEffect != null) { try { virtualEffect.release();  } catch (Exception ignored) {} virtualEffect  = null; }
        if (loudnessEffect!= null) { try { loudnessEffect.release(); } catch (Exception ignored) {} loudnessEffect = null; }
    }

    // ── Return ────────────────────────────────────────────────────────────

    private void returnResult() {
        stopPreview();
        android.content.Intent result = new android.content.Intent();
        result.putExtra(RESULT_BAND_LEVELS, currentBands);
        result.putExtra(RESULT_BASS_BOOST,  bassBoost);
        result.putExtra(RESULT_VIRTUALIZER, virtualizer);
        result.putExtra(RESULT_LOUDNESS,    loudness);
        result.putExtra(RESULT_PRESET_NAME, currentPreset);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override protected void onDestroy() {
        stopPreview();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String formatMb(int mb) {
        if (mb == 0) return "0 dB";
        return String.format(java.util.Locale.US, "%+.1f dB", mb / 100f);
    }

    // ── Preset adapter ────────────────────────────────────────────────────

    private class PresetAdapter extends RecyclerView.Adapter<PresetAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int vt) {
            android.view.View v = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_eq_preset_chip, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            h.tvName.setText(PRESET_NAMES[pos]);
            boolean sel = pos == selectedPresetIdx;
            h.itemView.setBackgroundResource(sel
                    ? R.drawable.bg_reel_chip_selected : R.drawable.bg_sort_chip);
            h.tvName.setTextColor(sel ? 0xFFFFFFFF : 0xCCFFFFFF);
            h.itemView.setOnClickListener(v -> applyPreset(pos));
        }
        @Override public int getItemCount() { return PRESET_NAMES.length; }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName;
            VH(android.view.View v) { super(v); tvName = v.findViewById(R.id.tv_eq_chip_name); }
        }
    }
}
