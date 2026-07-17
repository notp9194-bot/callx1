package com.callx.app.music;

import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.reels.R;
import java.util.*;
import java.util.concurrent.*;

/**
 * BeatSyncEditorActivity — Feature 4: Beat Sync Auto-Cut.
 *
 * Takes a recorded video + a sound with BPM, then:
 *  ✅ Shows editable BPM value (pre-filled from sound's bpm field)
 *  ✅ Calculates beat interval = 60_000 / bpm ms
 *  ✅ Extracts thumbnails at each beat position (MediaMetadataRetriever)
 *  ✅ Shows horizontal timeline strip of beat-aligned frames
 *  ✅ "Auto Cut" → generates cut timestamps list and passes back to editor
 *  ✅ Supports 1/2-beat and 2-beat multiplier for faster/slower edits
 *  ✅ Preview: shows beat count, interval, total cuts
 *
 * Caller receives RESULT_OK with EXTRA_CUT_TIMESTAMPS (long[]) and
 * EXTRA_BEAT_INTERVAL_MS (long).
 *
 * Firebase path: sounds/{soundId}/bpm (read-only in this screen)
 */
public class BeatSyncEditorActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_PATH      = "beat_video_path";
    public static final String EXTRA_SOUND_TITLE     = "beat_sound_title";
    public static final String EXTRA_BPM             = "beat_bpm";
    public static final String EXTRA_VIDEO_DURATION  = "beat_video_duration_ms";

    // Results
    public static final String RESULT_CUT_TIMESTAMPS  = "beat_cut_timestamps";
    public static final String RESULT_BEAT_INTERVAL_MS = "beat_interval_ms";

    private static final int THUMB_COUNT_MAX = 20;

    private ImageButton  btnBack;
    private TextView     tvSoundTitle, tvBeatInfo, tvCutCount;
    private EditText     etBpm;
    private Button       btnApplyCuts;
    private SeekBar      sbBpmMultiplier;
    private TextView     tvMultiplierLabel;
    private RecyclerView rvThumbs;
    private ProgressBar  pbThumbs;

    private String  videoPath;
    private int     bpm;
    private long    videoDurationMs;
    private float   multiplier = 1.0f;  // 0.5, 1.0, 2.0

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final List<android.graphics.Bitmap> thumbs = new ArrayList<>();
    private ThumbAdapter thumbAdapter;

    private static final float[] MULTIPLIERS   = {0.5f, 1.0f, 2.0f};
    private static final String[] MULT_LABELS  = {"½ Beat (Fast)", "1× Beat", "2× Beat (Slow)"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_beat_sync_editor);

        videoPath      = getIntent().getStringExtra(EXTRA_VIDEO_PATH);
        bpm            = getIntent().getIntExtra(EXTRA_BPM, 120);
        videoDurationMs= getIntent().getLongExtra(EXTRA_VIDEO_DURATION, 15_000L);
        String soundTitle = getIntent().getStringExtra(EXTRA_SOUND_TITLE);

        bindViews(soundTitle);
        updateBeatInfo();
        loadThumbnails();
    }

    private void bindViews(String soundTitle) {
        btnBack         = findViewById(R.id.btn_beat_back);
        tvSoundTitle    = findViewById(R.id.tv_beat_sound_title);
        tvBeatInfo      = findViewById(R.id.tv_beat_info);
        tvCutCount      = findViewById(R.id.tv_beat_cut_count);
        etBpm           = findViewById(R.id.et_beat_bpm);
        btnApplyCuts    = findViewById(R.id.btn_beat_apply);
        sbBpmMultiplier = findViewById(R.id.sb_beat_multiplier);
        tvMultiplierLabel = findViewById(R.id.tv_beat_multiplier_label);
        rvThumbs        = findViewById(R.id.rv_beat_thumbs);
        pbThumbs        = findViewById(R.id.pb_beat_thumbs);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (tvSoundTitle != null)
            tvSoundTitle.setText(soundTitle != null ? soundTitle : "Beat Sync");
        if (etBpm != null) {
            etBpm.setText(String.valueOf(bpm));
            etBpm.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b2, int c) {
                    try {
                        int v = Integer.parseInt(s.toString().trim());
                        if (v > 0 && v <= 300) { bpm = v; updateBeatInfo(); }
                    } catch (NumberFormatException ignored) {}
                }
            });
        }

        // Multiplier seekbar: 0=0.5x, 1=1x, 2=2x
        if (sbBpmMultiplier != null) {
            sbBpmMultiplier.setMax(2);
            sbBpmMultiplier.setProgress(1);
            sbBpmMultiplier.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                    multiplier = MULTIPLIERS[p];
                    if (tvMultiplierLabel != null) tvMultiplierLabel.setText(MULT_LABELS[p]);
                    updateBeatInfo();
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
        }
        if (tvMultiplierLabel != null) tvMultiplierLabel.setText(MULT_LABELS[1]);

        thumbAdapter = new ThumbAdapter(thumbs);
        if (rvThumbs != null) {
            rvThumbs.setLayoutManager(new LinearLayoutManager(
                this, LinearLayoutManager.HORIZONTAL, false));
            rvThumbs.setAdapter(thumbAdapter);
        }

        if (btnApplyCuts != null) btnApplyCuts.setOnClickListener(v -> applyCuts());
    }

    private long beatIntervalMs() {
        return Math.max(100, (long) (60_000L / Math.max(1, bpm) / multiplier));
    }

    private void updateBeatInfo() {
        long intervalMs = beatIntervalMs();
        long cuts = videoDurationMs / intervalMs;
        if (tvBeatInfo != null)
            tvBeatInfo.setText("Beat interval: " + intervalMs + " ms  ("
                + String.format(Locale.US, "%.1f", intervalMs / 1000f) + " sec)");
        if (tvCutCount != null)
            tvCutCount.setText(cuts + " auto-cuts in " +
                String.format(Locale.US, "%.1f", videoDurationMs / 1000f) + "s video");
    }

    private void loadThumbnails() {
        if (videoPath == null) return;
        if (pbThumbs != null) pbThumbs.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            List<android.graphics.Bitmap> loaded = new ArrayList<>();
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            try {
                mmr.setDataSource(videoPath);
                long interval = beatIntervalMs();
                long pos = 0;
                int count = 0;
                while (pos < videoDurationMs && count < THUMB_COUNT_MAX) {
                    android.graphics.Bitmap bmp = mmr.getFrameAtTime(
                        pos * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (bmp != null) {
                        // Scale down to 120×200 for memory efficiency
                        android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(
                            bmp, 120, 200, true);
                        if (scaled != bmp) bmp.recycle();
                        loaded.add(scaled);
                    }
                    pos += interval;
                    count++;
                }
            } catch (Exception e) {
                android.util.Log.e("BeatSyncEditor", "Thumbnail extraction failed", e);
            } finally {
                try { mmr.release(); } catch (Exception ignored) {}
            }

            mainHandler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (pbThumbs != null) pbThumbs.setVisibility(View.GONE);
                thumbs.clear();
                thumbs.addAll(loaded);
                thumbAdapter.notifyDataSetChanged();
            });
        });
    }

    private void applyCuts() {
        long interval = beatIntervalMs();
        List<Long> cuts = new ArrayList<>();
        for (long pos = 0; pos < videoDurationMs; pos += interval) cuts.add(pos);

        long[] arr = new long[cuts.size()];
        for (int i = 0; i < cuts.size(); i++) arr[i] = cuts.get(i);

        Intent result = new Intent();
        result.putExtra(RESULT_CUT_TIMESTAMPS,   arr);
        result.putExtra(RESULT_BEAT_INTERVAL_MS, interval);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        for (android.graphics.Bitmap b : thumbs) { if (b != null && !b.isRecycled()) b.recycle(); }
        super.onDestroy();
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    static class ThumbAdapter extends RecyclerView.Adapter<ThumbAdapter.VH> {
        private final List<android.graphics.Bitmap> bitmaps;
        ThumbAdapter(List<android.graphics.Bitmap> bitmaps) { this.bitmaps = bitmaps; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int vt) {
            android.widget.ImageView iv = new android.widget.ImageView(p.getContext());
            iv.setLayoutParams(new ViewGroup.LayoutParams(120, 200));
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setPadding(2, 0, 2, 0);
            return new VH(iv);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            ((android.widget.ImageView) h.itemView).setImageBitmap(bitmaps.get(pos));
        }

        @Override public int getItemCount() { return bitmaps.size(); }

        static class VH extends RecyclerView.ViewHolder {
            VH(View v) { super(v); }
        }
    }
}
