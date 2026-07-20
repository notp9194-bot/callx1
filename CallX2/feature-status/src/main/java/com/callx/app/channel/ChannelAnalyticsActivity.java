package com.callx.app.channel;

import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.callx.app.status.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * ChannelAnalyticsActivity — WhatsApp-level comprehensive channel analytics (v5).
 *
 * v5 additions:
 *   ✓ Summary cards: Total posts, Total views, Followers, Engagement rate
 *   ✓ Reach per day (last 14 days) — line chart with date labels
 *   ✓ Impressions per day (last 14 days) — bar chart
 *   ✓ Shares/forwards timeline (last 14 days) — bar chart
 *   ✓ Subscriber growth chart (last 30 days) — line chart
 *   ✓ Post-type distribution (8 types) — bar chart
 *   ✓ Peak posting hours (0–23h) — bar chart
 *   ✓ Top 5 posts by view count — tappable rows
 *   ✓ Best performing post type (highest avg engagement)
 *   ✓ Optimal posting time recommendation
 *   ✓ Export analytics as text via share sheet
 *   ✓ All data fetched live from Firebase analytics node
 */
public class ChannelAnalyticsActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";

    private String channelId, channelName;

    // Summary stat views
    private TextView tvTotalPosts, tvTotalViews, tvTotalFollowers, tvEngagement;
    private TextView tvReach7d, tvImpressions7d, tvForwards7d, tvAvgViews;

    // Chart views
    private BarChartView   chartPostTypes, chartPeakHours, chartImpressionsDaily, chartForwardsDaily;
    private LineChartView  chartWeeklyGrowth, chartReachDaily, chartSubGrowth30d;

    // Top posts list
    private LinearLayout   layoutTopPosts;

    // Best type / optimal time
    private TextView tvBestType, tvOptimalTime;

    // Analytics data
    private int    totalPosts     = 0;
    private long   totalViews     = 0;
    private long   totalReactions = 0;
    private long   totalReplies   = 0;
    private long   totalForwards  = 0;
    private long   totalFollowers = 0;
    private final Map<String, Integer> typeCounts       = new LinkedHashMap<>();
    private final int[]                hourCounts       = new int[24];
    private final long[]               weeklyGrowth     = new long[7];
    private final long[]               reach14d         = new long[14];
    private final long[]               impressions14d   = new long[14];
    private final long[]               forwards14d      = new long[14];
    private final long[]               subGrowth30d     = new long[30];
    private final Map<String, long[]>  typeEngagement   = new LinkedHashMap<>(); // type→[views,reactions]
    private final List<TopPost>        topPosts         = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_analytics);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        if (channelId == null) { finish(); return; }

        Toolbar toolbar = findViewById(R.id.toolbar_analytics);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Analytics");
            getSupportActionBar().setSubtitle(channelName);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        // Summary
        tvTotalPosts     = findViewById(R.id.tv_analytics_total_posts);
        tvTotalViews     = findViewById(R.id.tv_analytics_total_views);
        tvTotalFollowers = findViewById(R.id.tv_analytics_total_followers);
        tvEngagement     = findViewById(R.id.tv_analytics_engagement);
        tvReach7d        = findViewById(R.id.tv_analytics_reach_7d);
        tvImpressions7d  = findViewById(R.id.tv_analytics_impressions_7d);
        tvForwards7d     = findViewById(R.id.tv_analytics_forwards_7d);
        tvAvgViews       = findViewById(R.id.tv_analytics_avg_views);
        tvBestType       = findViewById(R.id.tv_analytics_best_type);
        tvOptimalTime    = findViewById(R.id.tv_analytics_optimal_time);

        // Charts
        chartPostTypes        = findViewById(R.id.chart_post_types);
        chartPeakHours        = findViewById(R.id.chart_peak_hours);
        chartWeeklyGrowth     = findViewById(R.id.chart_weekly_growth);
        chartReachDaily       = findViewById(R.id.chart_reach_daily);
        chartImpressionsDaily = findViewById(R.id.chart_impressions_daily);
        chartForwardsDaily    = findViewById(R.id.chart_forwards_daily);
        chartSubGrowth30d     = findViewById(R.id.chart_sub_growth_30d);

        layoutTopPosts = findViewById(R.id.layout_top_posts);

        // Export button
        View btnExport = findViewById(R.id.btn_analytics_export);
        if (btnExport != null) btnExport.setOnClickListener(v -> exportAnalytics());

        // Load all analytics data in parallel
        loadChannelData();
        loadFollowerGrowth();
        loadAnalyticsEvents();
    }

    // ── Firebase data loading ──────────────────────────────────────────────

    private void loadChannelData() {
        FirebaseUtils.db().getReference("channels").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Object f = snap.child("followers").getValue();
                    totalFollowers = f instanceof Number ? ((Number) f).longValue() : 0;
                    if (tvTotalFollowers != null)
                        tvTotalFollowers.setText(formatCompact(totalFollowers));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        FirebaseUtils.db().getReference("channelPosts").child(channelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    totalPosts = 0; totalViews = 0; totalReactions = 0;
                    totalReplies = 0; totalForwards = 0;
                    typeCounts.clear(); topPosts.clear(); Arrays.fill(hourCounts, 0);
                    typeEngagement.clear();

                    for (DataSnapshot child : snap.getChildren()) {
                        if (Boolean.TRUE.equals(child.child("isDraft").getValue(Boolean.class))) continue;
                        if (Boolean.TRUE.equals(child.child("isDeleted").getValue(Boolean.class))) continue;
                        if (child.child("scheduledAt").getValue(Long.class) != null
                            && child.child("scheduledAt").getValue(Long.class) > 0) continue;

                        totalPosts++;
                        long views     = getLong(child, "viewCount");
                        long reactions = child.child("reactions").getChildrenCount();
                        long replies   = getLong(child, "replyCount");
                        long forwards  = getLong(child, "forwardCount");
                        totalViews     += views;
                        totalReactions += reactions;
                        totalReplies   += replies;
                        totalForwards  += forwards;

                        String type = getStr(child, "type");
                        if (type == null) type = "text";
                        typeCounts.merge(type, 1, Integer::sum);

                        // Per-type engagement tracking
                        long[] eng = typeEngagement.computeIfAbsent(type, k -> new long[2]);
                        eng[0] += views; eng[1] += reactions + replies;

                        // Peak hours
                        long ts = getLong(child, "timestamp");
                        if (ts > 0) {
                            Calendar cal = Calendar.getInstance();
                            cal.setTimeInMillis(ts);
                            hourCounts[cal.get(Calendar.HOUR_OF_DAY)]++;
                        }

                        // Top posts
                        if (child.getKey() != null) {
                            topPosts.add(new TopPost(
                                child.getKey(), type, getStr(child, "text"),
                                views, reactions, replies));
                        }
                    }

                    // Sort top posts by views
                    topPosts.sort((a, b) -> Long.compare(b.views, a.views));

                    // Update summary UI
                    updateSummaryCards();
                    updateCharts();
                    populateTopPosts();
                    computeInsights();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /** Load per-day analytics events (reach, impressions, forwards) from Firebase analytics node. */
    private void loadAnalyticsEvents() {
        DatabaseReference analyticsRef = FirebaseUtils.db().getReference("channelAnalytics").child(channelId);

        // Load reach (14 days)
        analyticsRef.child("reach").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Arrays.fill(reach14d, 0);
                String[] dates = last14Dates();
                for (int i = 0; i < dates.length; i++) {
                    Long v = snap.child(dates[i]).getValue(Long.class);
                    reach14d[i] = v != null ? v : 0;
                }
                updateReachChart();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });

        // Load impressions (14 days)
        analyticsRef.child("impressions").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Arrays.fill(impressions14d, 0);
                String[] dates = last14Dates();
                for (int i = 0; i < dates.length; i++) {
                    Long v = snap.child(dates[i]).getValue(Long.class);
                    impressions14d[i] = v != null ? v : 0;
                }
                updateImpressionsChart();
                update7dStats();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });

        // Load forwards (14 days)
        analyticsRef.child("forwards").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Arrays.fill(forwards14d, 0);
                String[] dates = last14Dates();
                for (int i = 0; i < dates.length; i++) {
                    Long v = snap.child(dates[i]).getValue(Long.class);
                    forwards14d[i] = v != null ? v : 0;
                }
                updateForwardsChart();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    /** Load follower growth timeline (last 30 + 7 days). */
    private void loadFollowerGrowth() {
        FirebaseUtils.db().getReference("channelAnalytics").child(channelId)
            .child("followerGrowth")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Arrays.fill(weeklyGrowth, 0); Arrays.fill(subGrowth30d, 0);
                    String[] last30 = lastNDates(30);
                    String[] last7  = lastNDates(7);

                    for (int i = 0; i < 30; i++) {
                        Long v = snap.child(last30[i]).getValue(Long.class);
                        subGrowth30d[i] = v != null ? v : 0;
                    }
                    for (int i = 0; i < 7; i++) {
                        Long v = snap.child(last7[i]).getValue(Long.class);
                        weeklyGrowth[i] = v != null ? v : 0;
                    }
                    updateWeeklyGrowthChart();
                    updateSubGrowth30dChart();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── UI update ─────────────────────────────────────────────────────────

    private void updateSummaryCards() {
        if (tvTotalPosts != null) tvTotalPosts.setText(formatCompact(totalPosts));
        if (tvTotalViews != null) tvTotalViews.setText(formatCompact(totalViews));
        double engRate = (totalViews > 0)
            ? (totalReactions + totalReplies + totalForwards) * 100.0 / totalViews : 0;
        if (tvEngagement != null) tvEngagement.setText(String.format("%.1f%%", engRate));
        long avgV = totalPosts > 0 ? totalViews / totalPosts : 0;
        if (tvAvgViews != null) tvAvgViews.setText(formatCompact(avgV) + " avg views");
    }

    private void update7dStats() {
        long reach7 = 0, imp7 = 0, fwd7 = 0;
        int start = Math.max(0, reach14d.length - 7);
        for (int i = start; i < reach14d.length;      i++) reach7 += reach14d[i];
        start = Math.max(0, impressions14d.length - 7);
        for (int i = start; i < impressions14d.length; i++) imp7 += impressions14d[i];
        start = Math.max(0, forwards14d.length - 7);
        for (int i = start; i < forwards14d.length;    i++) fwd7 += forwards14d[i];
        if (tvReach7d       != null) tvReach7d.setText(formatCompact(reach7) + " reach");
        if (tvImpressions7d != null) tvImpressions7d.setText(formatCompact(imp7) + " impressions");
        if (tvForwards7d    != null) tvForwards7d.setText(formatCompact(fwd7) + " shares");
    }

    private void updateCharts() {
        // Post type bar chart
        if (chartPostTypes != null && !typeCounts.isEmpty()) {
            float[] vals = new float[typeCounts.size()];
            String[] labels = new String[typeCounts.size()];
            int i = 0;
            for (Map.Entry<String, Integer> e : typeCounts.entrySet()) {
                vals[i] = e.getValue(); labels[i++] = typeLabel(e.getKey());
            }
            chartPostTypes.setData(vals, labels);
        }

        // Peak hours bar chart
        if (chartPeakHours != null) {
            float[] vals = new float[24];
            String[] labels = new String[24];
            for (int h = 0; h < 24; h++) { vals[h] = hourCounts[h]; labels[h] = h + "h"; }
            chartPeakHours.setData(vals, labels);
        }
    }

    private void updateReachChart() {
        if (chartReachDaily == null) return;
        String[] labels = shortDateLabels(14);
        float[] vals = new float[14];
        for (int i = 0; i < 14; i++) vals[i] = reach14d[i];
        chartReachDaily.setData(vals, labels);
    }

    private void updateImpressionsChart() {
        if (chartImpressionsDaily == null) return;
        String[] labels = shortDateLabels(14);
        float[] vals = new float[14];
        for (int i = 0; i < 14; i++) vals[i] = impressions14d[i];
        chartImpressionsDaily.setData(vals, labels);
    }

    private void updateForwardsChart() {
        if (chartForwardsDaily == null) return;
        String[] labels = shortDateLabels(14);
        float[] vals = new float[14];
        for (int i = 0; i < 14; i++) vals[i] = forwards14d[i];
        chartForwardsDaily.setData(vals, labels);
    }

    private void updateWeeklyGrowthChart() {
        if (chartWeeklyGrowth == null) return;
        String[] days = {"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};
        float[] vals = new float[7];
        for (int i = 0; i < 7; i++) vals[i] = weeklyGrowth[i];
        chartWeeklyGrowth.setData(vals, days);
    }

    private void updateSubGrowth30dChart() {
        if (chartSubGrowth30d == null) return;
        float[] vals = new float[30];
        for (int i = 0; i < 30; i++) vals[i] = subGrowth30d[i];
        chartSubGrowth30d.setData(vals, shortDateLabels(30));
    }

    private void populateTopPosts() {
        if (layoutTopPosts == null) return;
        layoutTopPosts.removeAllViews();
        int limit = Math.min(5, topPosts.size());
        for (int i = 0; i < limit; i++) {
            TopPost p = topPosts.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 12, 0, 12);

            TextView tvRank = new TextView(this);
            tvRank.setText("#" + (i + 1) + "  ");
            tvRank.setTypeface(null, Typeface.BOLD);
            tvRank.setTextColor(0xFF25D366);

            TextView tvInfo = new TextView(this);
            String preview = p.text != null && !p.text.isEmpty()
                ? (p.text.length() > 50 ? p.text.substring(0, 50) + "…" : p.text)
                : typeLabel(p.type);
            tvInfo.setText(typeLabel(p.type) + "  " + preview
                + "\n👁 " + formatCompact(p.views)
                + "  ❤️ " + formatCompact(p.reactions)
                + "  💬 " + formatCompact(p.replies));
            tvInfo.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            row.addView(tvRank);
            row.addView(tvInfo);

            final String pid = p.postId;
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, ChannelViewerActivity.class);
                intent.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_ID,   channelId);
                intent.putExtra(ChannelViewerActivity.EXTRA_CHANNEL_NAME, channelName);
                startActivity(intent);
            });

            // Divider
            View divider = new View(this);
            divider.setBackgroundColor(0xFFEEEEEE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
            divider.setLayoutParams(lp);

            layoutTopPosts.addView(row);
            layoutTopPosts.addView(divider);
        }
    }

    private void computeInsights() {
        // Best performing post type
        String bestType = null; double bestEngRate = -1;
        for (Map.Entry<String, long[]> e : typeEngagement.entrySet()) {
            long[] eng = e.getValue();
            double rate = eng[0] > 0 ? (double) eng[1] / eng[0] : 0;
            if (rate > bestEngRate) { bestEngRate = rate; bestType = e.getKey(); }
        }
        if (tvBestType != null && bestType != null)
            tvBestType.setText("Best type: " + typeLabel(bestType)
                + " (" + String.format("%.1f%%", bestEngRate * 100) + " engagement)");

        // Optimal posting time
        int peakHour = 0;
        for (int h = 1; h < 24; h++) if (hourCounts[h] > hourCounts[peakHour]) peakHour = h;
        String ampm = peakHour < 12 ? "AM" : "PM";
        int h12 = peakHour == 0 ? 12 : (peakHour > 12 ? peakHour - 12 : peakHour);
        if (tvOptimalTime != null)
            tvOptimalTime.setText("Best posting time: " + h12 + ":00 " + ampm);
    }

    // ── Export ────────────────────────────────────────────────────────────

    private void exportAnalytics() {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Analytics for ").append(channelName).append("\n");
        sb.append("Generated: ").append(new SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(new Date())).append("\n\n");
        sb.append("Posts:       ").append(totalPosts).append("\n");
        sb.append("Views:       ").append(formatCompact(totalViews)).append("\n");
        sb.append("Followers:   ").append(formatCompact(totalFollowers)).append("\n");
        sb.append("Reactions:   ").append(formatCompact(totalReactions)).append("\n");
        sb.append("Replies:     ").append(formatCompact(totalReplies)).append("\n");
        sb.append("Forwards:    ").append(formatCompact(totalForwards)).append("\n\n");
        sb.append("Post types:\n");
        for (Map.Entry<String, Integer> e : typeCounts.entrySet())
            sb.append("  ").append(typeLabel(e.getKey())).append(": ").append(e.getValue()).append("\n");
        sb.append("\nTop 5 posts:\n");
        for (int i = 0; i < Math.min(5, topPosts.size()); i++) {
            TopPost p = topPosts.get(i);
            sb.append(i + 1).append(". ").append(typeLabel(p.type))
              .append(" — ").append(formatCompact(p.views)).append(" views\n");
        }

        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, sb.toString());
        startActivity(Intent.createChooser(share, "Export analytics"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String[] last14Dates() { return lastNDates(14); }

    private String[] lastNDates(int n) {
        String[] dates = new String[n];
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();
        for (int i = n - 1; i >= 0; i--) {
            dates[n - 1 - i] = sdf.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, -1);
        }
        return dates;
    }

    private String[] shortDateLabels(int n) {
        String[] labels = new String[n];
        SimpleDateFormat sdf = new SimpleDateFormat("M/d", Locale.US);
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -(n - 1));
        for (int i = 0; i < n; i++) {
            labels[i] = sdf.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        return labels;
    }

    private String getStr(DataSnapshot ds, String key) {
        Object v = ds.child(key).getValue();
        return v != null ? v.toString() : null;
    }

    private long getLong(DataSnapshot ds, String key) {
        Object v = ds.child(key).getValue();
        return v instanceof Number ? ((Number) v).longValue() : 0;
    }

    private String formatCompact(long n) {
        if (n >= 1_000_000L) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000L)     return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private String typeLabel(String type) {
        if (type == null) return "Post";
        switch (type) {
            case "image":     return "📷 Image";
            case "video":     return "🎬 Video";
            case "audio":     return "🎵 Audio";
            case "document":  return "📄 Document";
            case "poll":      return "📊 Poll";
            case "link":      return "🔗 Link";
            case "broadcast": return "📢 Broadcast";
            case "event":     return "📅 Event";
            default:          return "💬 Text";
        }
    }

    // ── Inner classes ─────────────────────────────────────────────────────

    static class TopPost {
        String postId, type, text;
        long   views, reactions, replies;
        TopPost(String id, String type, String text, long views, long reactions, long replies) {
            this.postId = id; this.type = type; this.text = text;
            this.views = views; this.reactions = reactions; this.replies = replies;
        }
    }

    // ── Canvas chart views ────────────────────────────────────────────────

    public static class BarChartView extends android.view.View {
        private float[] values; private String[] labels;
        private final Paint barPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public BarChartView(android.content.Context ctx) { super(ctx); init(); }
        public BarChartView(android.content.Context ctx, android.util.AttributeSet attrs) { super(ctx, attrs); init(); }
        private void init() {
            barPaint.setColor(0xFF25D366);
            textPaint.setColor(0xFF666666); textPaint.setTextSize(24f); textPaint.setTextAlign(Paint.Align.CENTER);
        }
        public void setData(float[] v, String[] l) { values = v; labels = l; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            if (values == null || values.length == 0) return;
            float w = getWidth(), h = getHeight(), pad = 40f;
            float max = 1f; for (float v : values) if (v > max) max = v;
            float bw = (w - pad * 2) / values.length;
            for (int i = 0; i < values.length; i++) {
                float bh = (values[i] / max) * (h - pad - 60f);
                float left = pad + i * bw + bw * 0.1f;
                float right = left + bw * 0.8f;
                canvas.drawRoundRect(left, h - pad - bh, right, h - pad, 6f, 6f, barPaint);
                if (labels != null && i < labels.length)
                    canvas.drawText(labels[i], left + bw * 0.4f, h - 4f, textPaint);
            }
        }
    }

    public static class LineChartView extends android.view.View {
        private float[] values; private String[] labels;
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public LineChartView(android.content.Context ctx) { super(ctx); init(); }
        public LineChartView(android.content.Context ctx, android.util.AttributeSet attrs) { super(ctx, attrs); init(); }
        private void init() {
            linePaint.setColor(0xFF25D366); linePaint.setStrokeWidth(4f); linePaint.setStyle(Paint.Style.STROKE);
            dotPaint.setColor(0xFF25D366);
            textPaint.setColor(0xFF666666); textPaint.setTextSize(22f); textPaint.setTextAlign(Paint.Align.CENTER);
            fillPaint.setColor(0x2025D366); fillPaint.setStyle(Paint.Style.FILL);
        }
        public void setData(float[] v, String[] l) { values = v; labels = l; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            if (values == null || values.length < 2) return;
            float w = getWidth(), h = getHeight(), pad = 40f;
            float max = 1f; for (float v : values) if (v > max) max = v;
            float xStep = (w - pad * 2) / (values.length - 1);
            float[] xs = new float[values.length]; float[] ys = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                xs[i] = pad + i * xStep;
                ys[i] = h - pad - (values[i] / max) * (h - pad - 60f);
            }
            // Fill
            android.graphics.Path fill = new android.graphics.Path();
            fill.moveTo(xs[0], h - pad);
            for (int i = 0; i < xs.length; i++) fill.lineTo(xs[i], ys[i]);
            fill.lineTo(xs[xs.length - 1], h - pad); fill.close();
            canvas.drawPath(fill, fillPaint);
            // Line
            android.graphics.Path path = new android.graphics.Path();
            for (int i = 0; i < values.length; i++) {
                if (i == 0) path.moveTo(xs[i], ys[i]); else path.lineTo(xs[i], ys[i]);
                canvas.drawCircle(xs[i], ys[i], 6f, dotPaint);
                if (labels != null && i < labels.length)
                    canvas.drawText(labels[i], xs[i], h - 4f, textPaint);
            }
            canvas.drawPath(path, linePaint);
        }
    }
}
