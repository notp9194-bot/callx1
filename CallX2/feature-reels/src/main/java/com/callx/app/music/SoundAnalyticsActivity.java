package com.callx.app.music;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * SoundAnalyticsActivity — Feature 6: Advanced Sound Analytics Dashboard.
 *
 * Loads data from TWO Firebase sources for complete picture:
 *   sounds/{soundId}:          reel_count, total_saves, is_trending, creatorUid
 *   musicLibrary/{soundId}:    usageCount, trendingRank, savesCount, topCreator, analytics/
 *   sounds/{soundId}/daily_plays/{yyyy-MM-dd}: play counts for 7-day bar chart
 *   sounds/{soundId}/reels/:   top creators who used this sound
 *
 * Displays:
 *   ✅ Total Reels  ✅ Saves  ✅ Trending Rank  ✅ Trending badge
 *   ✅ 7-day daily plays bar chart (custom Canvas view — no external chart lib)
 *   ✅ Viral coefficient: reels per save (higher = more organic spread)
 *   ✅ Top 3 creators who used this sound (deduplicated by UID)
 *   ✅ Share rate (saves / reels × 100%)
 */
public class SoundAnalyticsActivity extends AppCompatActivity {

    public static final String EXTRA_SOUND_ID    = "analytics_sound_id";
    public static final String EXTRA_SOUND_TITLE = "analytics_sound_title";

    private String soundId;

    // Views
    private ProgressBar  pbLoad;
    private View         layoutStats;
    private TextView     tvNoData;
    private TextView     tvTitle, tvReels, tvSaves, tvRank, tvViral,
                         tvShareRate, tvTrendingBadge, tvTopCreators;
    private BarChartView barChart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_analytics_v2);

        soundId = getIntent().getStringExtra(EXTRA_SOUND_ID);
        String title = getIntent().getStringExtra(EXTRA_SOUND_TITLE);

        ImageButton btnBack = findViewById(R.id.btn_back);
        tvTitle             = findViewById(R.id.tv_sound_name);
        pbLoad              = findViewById(R.id.progress);
        layoutStats         = findViewById(R.id.layout_stats);
        tvNoData            = findViewById(R.id.tv_no_data);
        tvReels             = findViewById(R.id.tv_reels);
        tvSaves             = findViewById(R.id.tv_saves);
        tvRank              = findViewById(R.id.tv_rank);
        tvViral             = findViewById(R.id.tv_viral);
        tvShareRate         = findViewById(R.id.tv_share_rate);
        tvTrendingBadge     = findViewById(R.id.tv_trending_badge);
        tvTopCreators       = findViewById(R.id.tv_top_creators);
        barChart            = findViewById(R.id.bar_chart_plays);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (tvTitle != null) tvTitle.setText(title != null && !title.isEmpty() ? title : "Analytics");

        loadStats();
    }

    private void loadStats() {
        if (soundId == null || soundId.isEmpty()) { showState(false); return; }
        if (pbLoad != null) pbLoad.setVisibility(View.VISIBLE);

        final long[] reelCount  = {0};
        final long[] saves      = {0};
        final boolean[] trending= {false};
        final long[] rank       = {0};
        final boolean[] done    = {false, false, false}; // sounds, musicLibrary, daily

        // ── Load from sounds/{soundId} ────────────────────────────────────
        FirebaseUtils.db().getReference("sounds").child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Long rc = snap.child("reel_count").getValue(Long.class);
                    Long sv = snap.child("total_saves").getValue(Long.class);
                    Boolean tr = snap.child("is_trending").getValue(Boolean.class);
                    reelCount[0] = rc != null ? rc : 0;
                    saves[0]     = sv != null ? sv : 0;
                    trending[0]  = Boolean.TRUE.equals(tr);
                    done[0] = true;

                    // Load top creators from sounds/{soundId}/reels/
                    loadTopCreators(snap.child("reels"));
                    checkAllDone(done, reelCount, saves, trending, rank);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    done[0] = true;
                    checkAllDone(done, reelCount, saves, trending, rank);
                }
            });

        // ── Load from musicLibrary/{soundId} ──────────────────────────────
        FirebaseUtils.getMusicLibraryRef().child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Long r = snap.child("trendingRank").getValue(Long.class);
                    Long s = snap.child("savesCount").getValue(Long.class);
                    Long u = snap.child("usageCount").getValue(Long.class);
                    if (r != null && r > 0) rank[0] = r;
                    // Use whichever saves count is higher (both sources valid)
                    if (s != null && s > saves[0]) saves[0] = s;
                    if (u != null && u > reelCount[0]) reelCount[0] = u;
                    done[1] = true;
                    checkAllDone(done, reelCount, saves, trending, rank);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    done[1] = true;
                    checkAllDone(done, reelCount, saves, trending, rank);
                }
            });

        // ── Load 7-day daily plays ─────────────────────────────────────────
        loadDailyPlays(done);
    }

    private void loadDailyPlays(boolean[] done) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();
        List<String> days = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        SimpleDateFormat labelFmt = new SimpleDateFormat("EEE", Locale.US);
        for (int i = 6; i >= 0; i--) {
            Calendar d = (Calendar) cal.clone();
            d.add(Calendar.DAY_OF_YEAR, -i);
            days.add(sdf.format(d.getTime()));
            labels.add(labelFmt.format(d.getTime()));
        }

        final long[] values  = new long[7];
        final int[]  fetched = {0};

        for (int i = 0; i < 7; i++) {
            final int idx = i;
            FirebaseUtils.db().getReference("sounds").child(soundId)
                .child("daily_plays").child(days.get(i))
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Long v = snap.getValue(Long.class);
                        values[idx] = v != null ? v : 0;
                        fetched[0]++;
                        if (fetched[0] == 7) {
                            done[2] = true;
                            runOnUiThread(() -> {
                                if (barChart != null) barChart.setData(values, labels);
                            });
                            // done[2] already set — main check runs separately below
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        fetched[0]++;
                        if (fetched[0] == 7) { done[2] = true; }
                    }
                });
        }
    }

    private void loadTopCreators(DataSnapshot reelsSnap) {
        Map<String, Integer> uidCount = new LinkedHashMap<>();
        for (DataSnapshot r : reelsSnap.getChildren()) {
            String uid = r.child("ownerUid").getValue(String.class);
            if (uid != null && !uid.isEmpty())
                uidCount.put(uid, uidCount.getOrDefault(uid, 0) + 1);
        }
        // Sort by count desc, take top 3
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(uidCount.entrySet());
        entries.sort((a, b) -> b.getValue() - a.getValue());

        if (entries.isEmpty()) {
            runOnUiThread(() -> { if (tvTopCreators != null) tvTopCreators.setText("—"); });
            return;
        }

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(3, entries.size());
        final int[] done2 = {0};
        final String[] names = new String[limit];

        for (int i = 0; i < limit; i++) {
            final int idx = i;
            String uid = entries.get(idx).getKey();
            int cnt    = entries.get(idx).getValue();
            FirebaseUtils.getUserRef(uid).child("name")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String name = snap.getValue(String.class);
                        names[idx] = (name != null ? "@" + name : "User") + " (" + cnt + " reels)";
                        done2[0]++;
                        if (done2[0] == limit) {
                            StringBuilder b = new StringBuilder();
                            for (String n : names) if (n != null) b.append(n).append("\n");
                            runOnUiThread(() -> {
                                if (tvTopCreators != null) tvTopCreators.setText(b.toString().trim());
                            });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        done2[0]++;
                    }
                });
        }
    }

    private void checkAllDone(boolean[] done, long[] reelCount, long[] saves,
                               boolean[] trending, long[] rank) {
        if (!done[0] || !done[1]) return; // daily plays chart is independent
        runOnUiThread(() -> {
            if (pbLoad != null) pbLoad.setVisibility(View.GONE);
            boolean hasData = reelCount[0] > 0 || saves[0] > 0;
            showState(hasData);
            if (!hasData) return;

            if (tvReels != null)   tvReels.setText(fmt(reelCount[0]) + " Reels");
            if (tvSaves != null)   tvSaves.setText(fmt(saves[0]) + " Saves");
            if (tvRank  != null)   tvRank.setText(rank[0] > 0 ? "#" + rank[0] + " Trending" : "Not ranked");
            if (tvTrendingBadge != null) {
                tvTrendingBadge.setVisibility(trending[0] ? View.VISIBLE : View.GONE);
            }
            // Viral coefficient: reels per save (e.g. "2.5 reels/save")
            if (tvViral != null) {
                if (saves[0] > 0) {
                    float coeff = reelCount[0] / (float) saves[0];
                    tvViral.setText(String.format(Locale.US, "%.1f reels/save", coeff));
                } else {
                    tvViral.setText("—");
                }
            }
            // Share rate: saves / reels × 100%
            if (tvShareRate != null) {
                if (reelCount[0] > 0) {
                    float rate = saves[0] * 100f / reelCount[0];
                    tvShareRate.setText(String.format(Locale.US, "%.1f%%", rate));
                } else {
                    tvShareRate.setText("—");
                }
            }
        });
    }

    private void showState(boolean hasData) {
        if (layoutStats != null) layoutStats.setVisibility(hasData ? View.VISIBLE : View.GONE);
        if (tvNoData    != null) tvNoData.setVisibility(hasData ? View.GONE : View.VISIBLE);
    }

    private static String fmt(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Custom bar chart — no external library needed
    // ─────────────────────────────────────────────────────────────────────────

    public static class BarChartView extends View {

        private long[]   values;
        private List<String> labels;
        private final Paint barPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

        public BarChartView(Context ctx) { super(ctx); init(); }
        public BarChartView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
        public BarChartView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

        private void init() {
            barPaint.setColor(0xFFFF3B5C);
            labelPaint.setColor(0xFF888888);
            labelPaint.setTextSize(28f);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            valuePaint.setColor(0xFFFFFFFF);
            valuePaint.setTextSize(24f);
            valuePaint.setTextAlign(Paint.Align.CENTER);
            gridPaint.setColor(0xFF333333);
            gridPaint.setStrokeWidth(1f);
        }

        public void setData(long[] values, List<String> labels) {
            this.values = values;
            this.labels = labels;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (values == null || values.length == 0) return;

            int w = getWidth(), h = getHeight();
            int bottomPad = 48, topPad = 24, sidePad = 8;
            int chartH = h - bottomPad - topPad;
            int n = values.length;
            float barW = (w - sidePad * 2f) / n;

            long maxVal = 1;
            for (long v : values) if (v > maxVal) maxVal = v;

            // Draw grid line
            canvas.drawLine(sidePad, topPad + chartH, w - sidePad, topPad + chartH, gridPaint);

            for (int i = 0; i < n; i++) {
                float ratio   = values[i] / (float) maxVal;
                float barH    = ratio * chartH;
                float left    = sidePad + i * barW + barW * 0.15f;
                float right   = left + barW * 0.7f;
                float top2    = topPad + chartH - barH;
                float bottom2 = topPad + chartH;

                // Bar with rounded top
                RectF rect = new RectF(left, top2, right, bottom2);
                barPaint.setAlpha(values[i] == maxVal ? 255 : 180);
                canvas.drawRoundRect(rect, 6f, 6f, barPaint);

                // Value label above bar
                if (values[i] > 0) {
                    canvas.drawText(fmt(values[i]),
                        left + (right - left) / 2f,
                        Math.max(topPad + 24, top2 - 6),
                        valuePaint);
                }

                // Day label below
                if (labels != null && i < labels.size()) {
                    canvas.drawText(labels.get(i),
                        left + (right - left) / 2f,
                        h - 8,
                        labelPaint);
                }
            }
        }

        private static String fmt(long n) {
            if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format(Locale.US, "%.0fK", n / 1_000.0);
            return String.valueOf(n);
        }
    }
}
