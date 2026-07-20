package com.callx.reels.analytics;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

public class ReelCreatorDashboardActivity extends AppCompatActivity {
    private TextView tvTotalViews, tvTotalReach, tvNewFollowers, tvTotalRevenue, tvBestTimeRec;
    private ReelTimeSeriesChartView reachChart, followerGrowthChart;
    private BestTimeToPostView bestTimeGrid;
    private RecyclerView rvTopReels;
    private LinearLayout layoutTopGifters;
    private String myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_creator_dashboard);
        try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { finish(); return; }
        bindViews();
        loadData();
    }

    private void bindViews() {
        tvTotalViews = findViewById(R.id.tv_total_views_7d);
        tvTotalReach = findViewById(R.id.tv_total_reach_7d);
        tvNewFollowers = findViewById(R.id.tv_new_followers_7d);
        tvTotalRevenue = findViewById(R.id.tv_total_revenue);
        tvBestTimeRec = findViewById(R.id.tv_best_time_rec);
        reachChart = findViewById(R.id.reach_chart);
        followerGrowthChart = findViewById(R.id.follower_growth_chart);
        bestTimeGrid = findViewById(R.id.best_time_grid);
        rvTopReels = findViewById(R.id.rv_top_reels);
        layoutTopGifters = findViewById(R.id.layout_top_gifters);
    }

    private void loadData() {
        DatabaseReference root = FirebaseUtils.db().getReference();
        
        root.child("creatorInsights").child(myUid).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                if (!snap.exists()) return;
                tvTotalViews.setText(String.valueOf(snap.child("views_7d").getValue(Long.class)));
                tvTotalReach.setText(String.valueOf(snap.child("reach_7d").getValue(Long.class)));
                tvNewFollowers.setText("+" + snap.child("followers_7d").getValue(Long.class));
                
                List<Float> reachData = new ArrayList<>();
                for (DataSnapshot d : snap.child("reach_series").getChildren()) {
                    reachData.add(d.getValue(Float.class));
                }
                if (!reachData.isEmpty()) reachChart.setData(reachData, null);

                List<Float> followerData = new ArrayList<>();
                for (DataSnapshot d : snap.child("follower_series").getChildren()) {
                    followerData.add(d.getValue(Float.class));
                }
                if (!followerData.isEmpty()) followerGrowthChart.setData(followerData, null);

                float[][] grid = new float[7][24];
                DataSnapshot heat = snap.child("engagement_heatmap");
                for (int d = 0; d < 7; d++) {
                    for (int h = 0; h < 24; h++) {
                        Float val = heat.child(d + "_" + h).getValue(Float.class);
                        grid[d][h] = val != null ? val : 0f;
                    }
                }
                bestTimeGrid.setData(grid);
                tvBestTimeRec.setText(bestTimeGrid.getRecommendationText());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });

        root.child("reelMetrics").child(myUid).orderByChild("engagementRate").limitToLast(10)
            .addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    List<ReelTopPerformingAdapter.ReelMetric> list = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        ReelTopPerformingAdapter.ReelMetric m = new ReelTopPerformingAdapter.ReelMetric();
                        m.id = s.getKey();
                        m.views = s.child("views").getValue(Integer.class) != null ? s.child("views").getValue(Integer.class) : 0;
                        m.engagementRate = s.child("engagementRate").getValue(Float.class) != null ? s.child("engagementRate").getValue(Float.class) : 0f;
                        m.thumbUrl = s.child("thumbUrl").getValue(String.class);
                        m.durationMs = s.child("duration").getValue(Long.class) != null ? s.child("duration").getValue(Long.class) : 0L;
                        list.add(m);
                    }
                    Collections.reverse(list);
                    rvTopReels.setAdapter(new ReelTopPerformingAdapter(list));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        root.child("userGifts").child(myUid).child("received").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                double total = 0;
                layoutTopGifters.removeAllViews();
                for (DataSnapshot s : snap.getChildren()) {
                    Double amount = s.child("amount").getValue(Double.class);
                    if (amount != null) total += amount;
                    
                    TextView tv = new TextView(ReelCreatorDashboardActivity.this);
                    tv.setText(s.child("senderName").getValue(String.class) + ": $" + amount);
                    tv.setTextColor(0xFFDDDDDD);
                    tv.setPadding(0, 8, 0, 8);
                    layoutTopGifters.addView(tv);
                }
                tvTotalRevenue.setText(String.format(Locale.US, "$%.2f", total));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }
}
