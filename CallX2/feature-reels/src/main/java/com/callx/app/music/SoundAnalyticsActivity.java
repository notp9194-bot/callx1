package com.callx.app.music;

import android.graphics.Color;
import android.os.Bundle;
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
 * SoundAnalyticsActivity — Creator-facing analytics for a specific sound.
 *
 * Features:
 *  ✅ Total reel count using this sound
 *  ✅ Trending rank (e.g. #12 Trending this week)
 *  ✅ Weekly usage chart (7-day bar chart drawn programmatically on a LinearLayout)
 *  ✅ Top creator using this sound
 *  ✅ Total saves count
 *  ✅ Avg. view-through rate (read from Firebase if stored)
 *  ✅ Firebase path: musicLibrary/{soundId}/analytics/
 *
 * Required extra:
 *   EXTRA_SOUND_ID    — Realtime DB key of the sound
 *   EXTRA_SOUND_TITLE — display name (optional, shown in toolbar)
 */
public class SoundAnalyticsActivity extends AppCompatActivity {

    public static final String EXTRA_SOUND_ID    = "analytics_sound_id";
    public static final String EXTRA_SOUND_TITLE = "analytics_sound_title";

    private ImageButton  btnBack;
    private TextView     tvSoundName, tvTotalReels, tvTrendingRank, tvSaves, tvTopCreator, tvVTR;
    private LinearLayout layoutWeeklyChart;
    private ProgressBar  progress;
    private TextView     tvNoData;
    private LinearLayout layoutStats;

    private String soundId;
    private String soundTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sound_analytics);

        soundId    = nvl(getIntent().getStringExtra(EXTRA_SOUND_ID));
        soundTitle = nvl(getIntent().getStringExtra(EXTRA_SOUND_TITLE));

        bindViews();
        loadAnalytics();
    }

    private void bindViews() {
        btnBack          = findViewById(R.id.btn_analytics_back);
        tvSoundName      = findViewById(R.id.tv_analytics_sound_name);
        tvTotalReels     = findViewById(R.id.tv_analytics_total_reels);
        tvTrendingRank   = findViewById(R.id.tv_analytics_trending_rank);
        tvSaves          = findViewById(R.id.tv_analytics_saves);
        tvTopCreator     = findViewById(R.id.tv_analytics_top_creator);
        tvVTR            = findViewById(R.id.tv_analytics_vtr);
        layoutWeeklyChart= findViewById(R.id.layout_weekly_chart);
        progress         = findViewById(R.id.progress_analytics);
        tvNoData         = findViewById(R.id.tv_analytics_no_data);
        layoutStats      = findViewById(R.id.layout_analytics_stats);

        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        if (tvSoundName != null) tvSoundName.setText(soundTitle.isEmpty() ? "Sound Analytics" : soundTitle);
    }

    private void loadAnalytics() {
        if (soundId.isEmpty()) { showNoData(); return; }
        if (progress != null) progress.setVisibility(View.VISIBLE);
        if (layoutStats != null) layoutStats.setVisibility(View.GONE);

        FirebaseUtils.getMusicLibraryRef().child(soundId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    if (progress != null) progress.setVisibility(View.GONE);

                    if (!snapshot.exists()) { showNoData(); return; }

                    Long usageCount   = snapshot.child("usageCount").getValue(Long.class);
                    Long trendingRank = snapshot.child("trendingRank").getValue(Long.class);
                    Long savesCount   = snapshot.child("savesCount").getValue(Long.class);
                    String topCreator = nvl(snapshot.child("topCreator").getValue(String.class));
                    Double vtr        = snapshot.child("analytics").child("avgVTR").getValue(Double.class);

                    // Populate stats
                    if (tvTotalReels != null)
                        tvTotalReels.setText(fmtCount(usageCount != null ? usageCount : 0L) + " Reels");
                    if (tvTrendingRank != null) {
                        if (trendingRank != null && trendingRank > 0)
                            tvTrendingRank.setText("#" + trendingRank + " Trending");
                        else
                            tvTrendingRank.setText("Not in top trending");
                    }
                    if (tvSaves != null)
                        tvSaves.setText(fmtCount(savesCount != null ? savesCount : 0L) + " Saves");
                    if (tvTopCreator != null)
                        tvTopCreator.setText(topCreator.isEmpty() ? "—" : "@" + topCreator);
                    if (tvVTR != null)
                        tvVTR.setText(vtr != null ? String.format(Locale.US, "%.1f%%", vtr * 100) : "—");

                    // Load weekly usage chart
                    loadWeeklyChart();

                    if (layoutStats != null) layoutStats.setVisibility(View.VISIBLE);
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    if (progress != null) progress.setVisibility(View.GONE);
                    showNoData();
                }
            });
    }

    private void loadWeeklyChart() {
        FirebaseUtils.getMusicLibraryRef().child(soundId).child("analytics").child("weeklyUsage")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    Map<String, Long> weekData = new LinkedHashMap<>();
                    // Build last 7 days keys
                    Calendar cal = Calendar.getInstance();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    String[] dayLabels = new String[7];
                    String[] dayKeys   = new String[7];
                    for (int i = 6; i >= 0; i--) {
                        cal = Calendar.getInstance();
                        cal.add(Calendar.DAY_OF_YEAR, -i);
                        String key = sdf.format(cal.getTime());
                        dayKeys[6 - i]   = key;
                        dayLabels[6 - i] = (6 - i == 6) ? "Today"
                            : new SimpleDateFormat("EEE", Locale.US).format(cal.getTime());
                    }
                    long maxVal = 1L;
                    long[] values = new long[7];
                    for (int j = 0; j < 7; j++) {
                        Long v = snapshot.child(dayKeys[j]).getValue(Long.class);
                        values[j] = v != null ? v : 0L;
                        if (values[j] > maxVal) maxVal = values[j];
                    }
                    drawWeeklyChart(values, maxVal, dayLabels);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void drawWeeklyChart(long[] values, long maxVal, String[] labels) {
        if (layoutWeeklyChart == null) return;
        layoutWeeklyChart.removeAllViews();
        layoutWeeklyChart.setOrientation(LinearLayout.HORIZONTAL);

        float density = getResources().getDisplayMetrics().density;
        int   maxBarHeightDp = 100;
        int   barWidthDp     = 36;
        int   gapDp          = 6;

        for (int i = 0; i < 7; i++) {
            // Column = bar + label
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                (int)((barWidthDp + gapDp) * density),
                LinearLayout.LayoutParams.WRAP_CONTENT);
            col.setLayoutParams(colLp);

            // Bar
            int barHeightPx = maxVal > 0
                ? (int)(maxBarHeightDp * density * values[i] / maxVal)
                : 4;
            View bar = new View(this);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                (int)(barWidthDp * density), Math.max(barHeightPx, (int)(4 * density)));
            barLp.setMargins(0, 0, (int)(gapDp * density), (int)(4 * density));

            int barColor = (i == 6) ? Color.parseColor("#FF3B5C") : Color.parseColor("#6B7280");
            bar.setBackgroundColor(barColor);
            bar.setLayoutParams(barLp);
            col.addView(bar);

            // Value label
            TextView tvVal = new TextView(this);
            tvVal.setText(values[i] > 0 ? fmtCount(values[i]) : "");
            tvVal.setTextSize(9f);
            tvVal.setTextColor(Color.WHITE);
            tvVal.setGravity(android.view.Gravity.CENTER);
            col.addView(tvVal);

            // Day label
            TextView tvDay = new TextView(this);
            tvDay.setText(labels[i]);
            tvDay.setTextSize(9f);
            tvDay.setTextColor(Color.GRAY);
            tvDay.setGravity(android.view.Gravity.CENTER);
            col.addView(tvDay);

            layoutWeeklyChart.addView(col);
        }
    }

    private void showNoData() {
        if (progress  != null) progress.setVisibility(View.GONE);
        if (tvNoData  != null) tvNoData.setVisibility(View.VISIBLE);
        if (layoutStats != null) layoutStats.setVisibility(View.GONE);
    }

    private static String fmtCount(long n) {
        if (n >= 1_000_000) return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000)     return String.format(Locale.US, "%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String nvl(String s) { return s != null ? s : ""; }
}
