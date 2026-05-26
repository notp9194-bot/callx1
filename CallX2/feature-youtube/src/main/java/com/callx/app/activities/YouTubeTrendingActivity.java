package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Trending — sorted by computed trending score.
 * Category filter chips: All / Music / Gaming / News / Sports / Movies / Tech
 */
public class YouTubeTrendingActivity extends AppCompatActivity {

    private static final String[] CATEGORIES = {
        "All", "Music", "Gaming", "News", "Sports", "Movies", "Tech", "Education"
    };

    private RecyclerView        rvTrending;
    private LinearLayout        llChips;
    private ProgressBar         pbLoading;
    private YouTubeVideoAdapter adapter;
    private ValueEventListener  feedListener;
    private String              selectedCat = "All";
    private List<YouTubeVideo>  allVideos   = new ArrayList<>();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_youtube_trending);

        View btnBack = findViewById(R.id.btn_yt_trending_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        rvTrending = findViewById(R.id.rv_yt_trending);
        llChips    = findViewById(R.id.ll_yt_trending_chips);
        pbLoading  = findViewById(R.id.pb_yt_trending);

        adapter = new YouTubeVideoAdapter(this, new ArrayList<>(), video ->
            startActivity(new Intent(this, YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvTrending.setLayoutManager(new LinearLayoutManager(this));
        rvTrending.setAdapter(adapter);

        buildChips();
        loadFeed();
    }

    private void buildChips() {
        if (llChips == null) return;
        llChips.removeAllViews();
        for (String cat : CATEGORIES) {
            TextView chip = new TextView(this);
            chip.setText(cat);
            chip.setTextSize(13f);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            boolean sel = cat.equals(selectedCat);
            chip.setTextColor(ContextCompat.getColor(this,
                sel ? android.R.color.white : android.R.color.black));
            chip.setBackground(ContextCompat.getDrawable(this,
                sel ? R.drawable.bg_yt_chip_selected : R.drawable.bg_yt_chip));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> { selectedCat = cat; buildChips(); filterAndSort(); });
            llChips.addView(chip);
        }
    }

    private int dp(int d) {
        return (int) (d * getResources().getDisplayMetrics().density);
    }

    private void loadFeed() {
        if (pbLoading != null) pbLoading.setVisibility(View.VISIBLE);
        feedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                allVideos.clear();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v != null && "public".equals(v.visibility) && !v.isShort)
                        allVideos.add(v);
                }
                filterAndSort();
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (pbLoading != null) pbLoading.setVisibility(View.GONE);
            }
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("viewCount").limitToLast(80)
            .addValueEventListener(feedListener);
    }

    private void filterAndSort() {
        List<YouTubeVideo> filtered = new ArrayList<>();
        for (YouTubeVideo v : allVideos) {
            if ("All".equals(selectedCat)
                    || (v.category != null && v.category.equalsIgnoreCase(selectedCat)))
                filtered.add(v);
        }
        long now = System.currentTimeMillis();
        Collections.sort(filtered, (a, b) -> Double.compare(score(b, now), score(a, now)));
        adapter.setData(filtered);
    }

    private double score(YouTubeVideo v, long now) {
        long hoursOld = Math.max(1, (now - v.uploadedAt) / 3_600_000L);
        return (v.viewCount * 2.0 + v.likeCount * 5.0 + v.commentCount * 3.0
                + v.shareCount * 4.0) / hoursOld;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (feedListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(feedListener);
    }
}
