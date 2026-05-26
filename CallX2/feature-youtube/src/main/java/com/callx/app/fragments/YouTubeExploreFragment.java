package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Explore tab — Trending + Category tabs.
 * Trending score = (views*2 + likes*5 + comments*3 + shares*4) / hoursOld
 */
public class YouTubeExploreFragment extends Fragment {

    private static final String[] TABS = {
        "Trending", "Music", "Gaming", "News", "Sports", "Movies", "Tech"
    };

    private RecyclerView       rvTrending;
    private LinearLayout       llTabs;
    private YouTubeVideoAdapter adapter;
    private ValueEventListener  trendingListener;
    private String              selectedTab = "Trending";
    private List<YouTubeVideo>  allVideos   = new ArrayList<>();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_explore, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        rvTrending = view.findViewById(R.id.rv_yt_trending);
        llTabs     = view.findViewById(R.id.ll_yt_explore_tabs);

        adapter = new YouTubeVideoAdapter(requireActivity(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvTrending.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvTrending.setAdapter(adapter);

        buildTabs();
        loadVideos();
    }

    private void buildTabs() {
        if (llTabs == null || !isAdded()) return;
        llTabs.removeAllViews();
        for (String tab : TABS) {
            TextView tv = new TextView(requireContext());
            tv.setText(tab);
            tv.setTextSize(13f);
            tv.setPadding(dp(16), dp(8), dp(16), dp(8));
            boolean sel = tab.equals(selectedTab);
            tv.setTextColor(ContextCompat.getColor(requireContext(),
                sel ? android.R.color.white : android.R.color.black));
            tv.setBackground(ContextCompat.getDrawable(requireContext(),
                sel ? R.drawable.bg_yt_chip_selected : R.drawable.bg_yt_chip));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> {
                selectedTab = tab;
                buildTabs();
                filterAndSort();
            });
            llTabs.addView(tv);
        }
    }

    private int dp(int d) {
        return (int) (d * requireContext().getResources().getDisplayMetrics().density);
    }

    private void loadVideos() {
        trendingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                allVideos.clear();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v != null && "public".equals(v.visibility) && !v.isShort)
                        allVideos.add(v);
                }
                filterAndSort();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("viewCount").limitToLast(60)
            .addValueEventListener(trendingListener);
    }

    private void filterAndSort() {
        List<YouTubeVideo> list = new ArrayList<>();
        for (YouTubeVideo v : allVideos) {
            if ("Trending".equals(selectedTab)
                    || (v.category != null && v.category.equalsIgnoreCase(selectedTab)))
                list.add(v);
        }
        // Sort by computed trending score
        long now = System.currentTimeMillis();
        Collections.sort(list, (a, b) -> {
            double scoreA = computeScore(a, now);
            double scoreB = computeScore(b, now);
            return Double.compare(scoreB, scoreA);
        });
        adapter.setData(list);
    }

    private double computeScore(YouTubeVideo v, long now) {
        long hoursOld = Math.max(1, (now - v.uploadedAt) / 3_600_000L);
        return (v.viewCount * 2.0 + v.likeCount * 5.0 + v.commentCount * 3.0
                + v.shareCount * 4.0) / hoursOld;
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (trendingListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(trendingListener);
    }
}
