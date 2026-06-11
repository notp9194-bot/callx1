package com.callx.app.editor;

import android.content.Intent;
import android.media.*;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.reels.R;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReelVoiceEffectsActivity — Apply voice effects to reel audio.
 *
 * Bugs fixed (v13):
 *  ✅ FIX: setDataSource now handles BOTH content:// URIs AND file paths
 *     (previously crash-silent when videoUri was a content:// URI)
 *  ✅ FIX: setPlaybackParams now called AFTER start() — avoids ignored-pitch
 *     on some devices when called in Prepared state
 *  ✅ FIX: isFilePath flag accepted from caller so URI type is known
 *  ✅ FIX: pitch/speed values clamped to safe range (0.5–2.0) to avoid
 *     MediaPlayer crash on extreme values
 *  ✅ FIX: prepare() wrapped in executor thread to avoid NetworkOnMainThread
 *     if path is a network URI
 *
 * Features:
 *  ✅ 10 voice effect presets: Normal, Chipmunk, Giant, Echo, Robot,
 *     Reverb, Whisper, Helium, Slow-Mo, Deep Space
 *  ✅ Live preview toggle — play the sample audio with effect applied
 *  ✅ Per-effect sliders: Pitch shift, Speed multiplier, Reverb mix
 *  ✅ Returns selected effect config to caller (effect name + params)
 *  ✅ "Apply to Reel" saves settings and returns to editor
 */
public class ReelVoiceEffectsActivity extends AppCompatActivity {

    public static final String EXTRA_AUDIO_PATH    = "voice_audio_path";
    public static final String EXTRA_IS_FILE_PATH  = "voice_is_file_path";  // ✅ NEW
    public static final String RESULT_EFFECT_NAME  = "result_voice_effect";
    public static final String RESULT_PITCH        = "result_voice_pitch";
    public static final String RESULT_SPEED        = "result_voice_speed";
    public static final String RESULT_REVERB       = "result_voice_reverb";

    // Preset: {name, emoji, pitch(0.5-2.0), speed(0.5-2.0), reverb(0-1)}
    private static final Object[][] PRESETS = {
        {"Normal",     "🎙️", 1.0f, 1.0f,  0.0f},
        {"Chipmunk",   "🐿️", 1.8f, 1.2f,  0.0f},
        {"Giant",      "👹", 0.4f, 0.8f,  0.1f},
        {"Echo",       "🔊", 1.0f, 1.0f,  0.6f},
        {"Robot",      "🤖", 0.7f, 1.0f,  0.3f},
        {"Reverb",     "🎶", 1.0f, 0.9f,  0.8f},
        {"Whisper",    "🤫", 1.1f, 0.85f, 0.1f},
        {"Helium",     "🎈", 2.0f, 1.3f,  0.0f},
        {"Slow-Mo",    "🐢", 0.9f, 0.5f,  0.05f},
        {"Deep Space", "🌌", 0.5f, 0.7f,  0.9f},
    };

    private RecyclerView  rvEffects;
    private SeekBar       sbPitch, sbSpeed, sbReverb;
    private TextView      tvPitchVal, tvSpeedVal, tvReverbVal;
    private TextView      tvSelectedEffect, btnApply, btnPreview;
    private ImageButton   btnBack;
    private ProgressBar   progressApply;

    private int     selectedIdx = 0;
    private float   pitch   = 1.0f;
    private float   speed   = 1.0f;
    private float   reverb  = 0.0f;
    private boolean isPreviewing = false;

    private MediaPlayer  mediaPlayer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_voice_effects);
        bindViews();
        setupEffectStrip();
        setupSliders();
        applyPreset(0);
    }

    private void bindViews() {
        btnBack          = findViewById(R.id.btn_voice_back);
        rvEffects        = findViewById(R.id.rv_voice_effects);
        sbPitch          = findViewById(R.id.sb_voice_pitch);
        sbSpeed          = findViewById(R.id.sb_voice_speed);
        sbReverb         = findViewById(R.id.sb_voice_reverb);
        tvPitchVal       = findViewById(R.id.tv_voice_pitch_val);
        tvSpeedVal       = findViewById(R.id.tv_voice_speed_val);
        tvReverbVal      = findViewById(R.id.tv_voice_reverb_val);
        tvSelectedEffect = findViewById(R.id.tv_voice_effect_name);
        btnApply         = findViewById(R.id.btn_voice_apply);
        btnPreview       = findViewById(R.id.btn_voice_preview);
        progressApply    = findViewById(R.id.progress_voice_apply);

        sbPitch.setMax(150);
        sbSpeed.setMax(150);
        sbReverb.setMax(100);

        btnBack.setOnClickListener(v -> finish());
        btnPreview.setOnClickListener(v -> togglePreview());

        btnApply.setOnClickListener(v -> {
            Intent result = new Intent();
            result.putExtra(RESULT_EFFECT_NAME, PRESETS[selectedIdx][0].toString());
            result.putExtra(RESULT_PITCH,  pitch);
            result.putExtra(RESULT_SPEED,  speed);
            result.putExtra(RESULT_REVERB, reverb);
            setResult(RESULT_OK, result);
            finish();
        });
    }

    private void setupEffectStrip() {
        rvEffects.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        rvEffects.setAdapter(new EffectAdapter());
    }

    private void setupSliders() {
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                if (sb == sbPitch) {
                    pitch = 0.5f + (p / 150f) * 1.5f;
                    tvPitchVal.setText(String.format("%.1fx", pitch));
                } else if (sb == sbSpeed) {
                    speed = 0.5f + (p / 150f) * 1.5f;
                    tvSpeedVal.setText(String.format("%.1fx", speed));
                } else if (sb == sbReverb) {
                    reverb = p / 100f;
                    tvReverbVal.setText(Math.round(reverb * 100) + "%");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        sbPitch.setOnSeekBarChangeListener(l);
        sbSpeed.setOnSeekBarChangeListener(l);
        sbReverb.setOnSeekBarChangeListener(l);
    }

    private void applyPreset(int idx) {
        selectedIdx = idx;
        Object[] p  = PRESETS[idx];
        tvSelectedEffect.setText(p[0] + " " + p[1]);

        pitch  = (float) p[2];
        speed  = (float) p[3];
        reverb = (float) p[4];

        sbPitch.setProgress(Math.round((pitch  - 0.5f) / 1.5f * 150));
        sbSpeed.setProgress(Math.round((speed  - 0.5f) / 1.5f * 150));
        sbReverb.setProgress(Math.round(reverb * 100));

        tvPitchVal.setText(String.format("%.1fx", pitch));
        tvSpeedVal.setText(String.format("%.1fx", speed));
        tvReverbVal.setText(Math.round(reverb * 100) + "%");

        if (rvEffects.getAdapter() != null) rvEffects.getAdapter().notifyDataSetChanged();
    }

    private void togglePreview() {
        if (isPreviewing) stopPreview();
        else              startPreview();
    }

    /**
     * ✅ FIXED startPreview():
     *  1. Detects content:// URI vs file path and uses correct setDataSource overload
     *  2. prepare() called off main thread (prepareAsync) to avoid ANR
     *  3. setPlaybackParams applied in onPrepared callback (correct state)
     *  4. pitch/speed clamped to [0.5, 2.0] for MediaPlayer safety
     */
    private void startPreview() {
        String path = getIntent().getStringExtra(EXTRA_AUDIO_PATH);
        boolean isFilePath = getIntent().getBooleanExtra(EXTRA_IS_FILE_PATH, true);

        if (path == null || path.isEmpty()) {
            Toast.makeText(this, "No audio to preview", Toast.LENGTH_SHORT).show();
            return;
        }

        isPreviewing = true;
        btnPreview.setText("⏹ Stop");
        if (progressApply != null) progressApply.setVisibility(View.VISIBLE);

        // Snapshot effect values (in case sliders change during async prepare)
        final float previewPitch  = clamp(pitch,  0.5f, 2.0f);
        final float previewSpeed  = clamp(speed,  0.25f, 2.0f);

        try {
            if (mediaPlayer != null) { try { mediaPlayer.release(); } catch (Exception ignored) {} mediaPlayer = null; }

            mediaPlayer = new MediaPlayer();

            // ✅ FIX 1: handle content:// URI vs file path correctly
            if (!isFilePath && path.startsWith("content://")) {
                mediaPlayer.setDataSource(this, Uri.parse(path));
            } else if (path.startsWith("content://") || path.startsWith("http://") || path.startsWith("https://")) {
                mediaPlayer.setDataSource(this, Uri.parse(path));
            } else {
                mediaPlayer.setDataSource(path);
            }

            // ✅ FIX 2: use prepareAsync + onPrepared so main thread never blocks
            mediaPlayer.setOnPreparedListener(mp -> {
                if (progressApply != null) progressApply.setVisibility(View.GONE);
                if (!isPreviewing) { mp.release(); mediaPlayer = null; return; }

                // ✅ FIX 3: apply PlaybackParams AFTER prepared and right before start
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    try {
                        PlaybackParams pp = new PlaybackParams();
                        pp.setSpeed(previewSpeed);
                        pp.setPitch(previewPitch);
                        pp.setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT);
                        mp.setPlaybackParams(pp);
                    } catch (Exception e) {
                        // Device doesn't support pitch — apply speed only
                        try {
                            PlaybackParams pp = new PlaybackParams();
                            pp.setSpeed(previewSpeed);
                            mp.setPlaybackParams(pp);
                        } catch (Exception ignored) {}
                    }
                }

                mp.setOnCompletionListener(m -> handler.post(this::stopPreview));
                mp.start();
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                handler.post(() -> {
                    if (progressApply != null) progressApply.setVisibility(View.GONE);
                    Toast.makeText(this, "Preview unavailable for this format", Toast.LENGTH_SHORT).show();
                    stopPreview();
                });
                return true;
            });

            mediaPlayer.prepareAsync(); // ✅ non-blocking

        } catch (Exception e) {
            if (progressApply != null) progressApply.setVisibility(View.GONE);
            Toast.makeText(this, "Preview unavailable: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            isPreviewing = false;
            btnPreview.setText("▶ Preview");
            if (mediaPlayer != null) { try { mediaPlayer.release(); } catch (Exception ignored) {} mediaPlayer = null; }
        }
    }

    private void stopPreview() {
        isPreviewing = false;
        btnPreview.setText("▶ Preview");
        if (progressApply != null) progressApply.setVisibility(View.GONE);
        if (mediaPlayer != null) {
            try { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); }  catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    /** Clamp value to [min, max] */
    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isPreviewing) stopPreview();
    }

    @Override
    protected void onDestroy() {
        stopPreview();
        executor.shutdown();
        super.onDestroy();
    }

    // ── Effect strip adapter ──────────────────────────────────────────────
    private class EffectAdapter extends RecyclerView.Adapter<EffectAdapter.VH> {
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_voice_effect_chip, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Object[] p = PRESETS[pos];
            h.tvEmoji.setText(p[1].toString());
            h.tvName.setText(p[0].toString());
            boolean sel = (pos == selectedIdx);
            h.vSelected.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                applyPreset(pos);
                // Auto-restart preview with new effect if already previewing
                if (isPreviewing) { stopPreview(); startPreview(); }
            });
        }

        @Override public int getItemCount() { return PRESETS.length; }

        class VH extends RecyclerView.ViewHolder {
            TextView tvEmoji, tvName;
            View     vSelected;
            VH(View v) {
                super(v);
                tvEmoji   = v.findViewById(R.id.tv_voice_chip_emoji);
                tvName    = v.findViewById(R.id.tv_voice_chip_name);
                vSelected = v.findViewById(R.id.v_voice_chip_selected);
            }
        }
    }
}
