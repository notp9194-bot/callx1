package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * YouTubeCreatorStudioActivity — Production Creator Dashboard.
 *
 * Sections:
 *  1. Overview cards — Total Views, Subscribers, Total Likes, Videos
 *  2. Last 7 days views chart (simple bar using LinearLayout)
 *  3. Your videos list (edit/delete access)
 *  4. Upload new video shortcut
 */
public class YouTubeCreatorStudioActivity extends AppCompatActivity {

    private TextView tvTotalViews, tvTotalSubs, tvTotalLikes, tvTotalVideos;
    private LinearLayout llWeekChart;
    private RecyclerView rvMyVideos;
    private ProgressBar pbLoading;
    private String myUid;

    private final long[] weekViews = new long[7]; // last 7 days view counts

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_creator_studio);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        if (myUid.isEmpty()) { finish(); return; }

        View btnBack = findViewById(R.id.btn_yt_studio_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        View btnUpload = findViewById(R.id.btn_yt_studio_upload);
        if (btnUpload != null)
            btnUpload.setOnClickListener(v ->
                startActivity(new Intent(this, YouTubeUploadActivity.class)));

        tvTotalViews  = findViewById(R.id.tv_yt_studio_views);
        tvTotalSubs   = findViewById(R.id.tv_yt_studio_subs);
        tvTotalLikes  = findViewById(R.id.tv_yt_studio_likes);
        tvTotalVideos = findViewById(R.id.tv_yt_studio_video_count);
        llWeekChart   = findViewById(R.id.ll_yt_studio_week_chart);
        rvMyVideos    = findViewById(R.id.rv_yt_studio_videos);
        pbLoading     = findViewById(R.id.pb_yt_studio);

        YouTubeVideoAdapter adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvMyVideos.setLayoutManager(new LinearLayoutManager(this));
        rvMyVideos.setAdapter(adapter);

        loadChannelStats();
        loadWeeklyAnalytics();
        loadMyVideos(adapter);
    }

    // ── Channel stats ─────────────────────────────────────────────────────────

    private void loadChannelStats() {
        YouTubeFirebaseUtils.channelRef(myUid).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    long views  = coerceLong(snap, "totalViews");
                    long subs   = coerceLong(snap, "subscriberCount");
                    long likes  = coerceLong(snap, "totalLikes");
                    long vids   = coerceLong(snap, "videoCount");
                    if (tvTotalViews  != null) tvTotalViews.setText(fmt(views));
                    if (tvTotalSubs   != null) tvTotalSubs.setText(fmt(subs));
                    if (tvTotalLikes  != null) tvTotalLikes.setText(fmt(likes));
                    if (tvTotalVideos != null) tvTotalVideos.setText(String.valueOf(vids));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Weekly analytics chart ────────────────────────────────────────────────

    private void loadWeeklyAnalytics() {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
        String[] dateKeys = new String[7];
        for (int i = 6; i >= 0; i--) {
            cal.setTimeInMillis(System.currentTimeMillis() - (long)i * 86_400_000L);
            dateKeys[6 - i] = sdf.format(cal.getTime());
        }

        final int[] done = {0};
        for (int d = 0; d < 7; d++) {
            final int idx = d;
            YouTubeFirebaseUtils.dailyAnalyticsRef(myUid, dateKeys[d])
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Long val = snap.getValue(Long.class);
                        weekViews[idx] = val != null ? val : 0;
                        done[0]++;
                        if (done[0] == 7) buildWeekChart(dateKeys);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        done[0]++;
                        if (done[0] == 7) buildWeekChart(dateKeys);
                    }
                });
        }
    }

    private void buildWeekChart(String[] dateKeys) {
        if (llWeekChart == null) return;
        runOnUiThread(() -> {
            llWeekChart.removeAllViews();
            long maxVal = 1;
            for (long v : weekViews) if (v > maxVal) maxVal = v;

            String[] dayLabels = {"Mon","Tue","Wed","Thu","Fri","Sat","Sun"};
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(System.currentTimeMillis() - 6 * 86_400_000L);

            for (int i = 0; i < 7; i++) {
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.BOTTOM);
                LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                colLp.setMarginEnd(dp(3));
                col.setLayoutParams(colLp);

                // Bar
                View bar = new View(this);
                int maxBarHeight = dp(120);
                int barH = (int) (maxBarHeight * weekViews[i] / maxVal);
                barH = Math.max(barH, dp(2));
                LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(20), barH);
                barLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
                bar.setLayoutParams(barLp);
                bar.setBackgroundColor(getResources().getColor(R.color.yt_red, getTheme()));
                col.addView(bar);

                // Count label
                TextView tvCount = new TextView(this);
                tvCount.setText(fmt(weekViews[i]));
                tvCount.setTextSize(9f);
                tvCount.setGravity(android.view.Gravity.CENTER);
                col.addView(tvCount);

                // Day label
                TextView tvDay = new TextView(this);
                tvDay.setText(dayLabels[cal.get(Calendar.DAY_OF_WEEK) % 7]);
                tvDay.setTextSize(9f);
                tvDay.setGravity(android.view.Gravity.CENTER);
                col.addView(tvDay);
                cal.add(Calendar.DAY_OF_YEAR, 1);

                llWeekChart.addView(col);
            }
        });
    }

    // ── My videos ─────────────────────────────────────────────────────────────

    private void loadMyVideos(YouTubeVideoAdapter adapter) {
        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        YouTubeFirebaseUtils.userVideosRef(myUid)
            .orderByValue().limitToLast(30)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<String> ids = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) ids.add(0, ds.getKey());
                    if (ids.isEmpty()) {
                        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                        return;
                    }
                    List<YouTubeVideo> videos = new ArrayList<>();
                    final int[] count = {0};
                    for (String id : ids) {
                        YouTubeFirebaseUtils.videoRef(id).addListenerForSingleValueEvent(
                            new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot vs) {
                                    YouTubeVideo v = vs.getValue(YouTubeVideo.class);
                                    if (v != null) videos.add(v);
                                    count[0]++;
                                    if (count[0] == ids.size()) {
                                        adapter.setData(videos);
                                        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                                    }
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    count[0]++;
                                    if (count[0] == ids.size()) {
                                        adapter.setData(videos);
                                        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                                    }
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (pbLoading != null) pbLoading.setVisibility(View.GONE);
                }
            });
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private long coerceLong(DataSnapshot snap, String key) {
        Long v = snap.child(key).getValue(Long.class);
        return v != null ? v : 0L;
    }

    private String fmt(long n) {
        if (n >= 1_000_000_000) return String.format("%.1fB", n / 1_000_000_000.0);
        if (n >= 1_000_000)     return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000)         return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density);
    }
}
