package com.callx.app.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.utils.YouTubePrefs;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

public class YouTubeHomeFragment extends Fragment {

    private static final String[] CATEGORIES = {
        "All", "Music", "Gaming", "News", "Sports", "Movies",
        "Tech", "Education", "Comedy", "Travel", "Food", "Fashion"
    };

    private RecyclerView       rvFeed;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar        pbLoading;
    private View               llEmpty;
    private LinearLayout       llChips;
    private YouTubeVideoAdapter adapter;
    private ValueEventListener feedListener;
    private YouTubePrefs       ytPrefs;
    private String             selectedCategory = "All";
    private List<YouTubeVideo> allVideos = new ArrayList<>();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_home, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        ytPrefs      = new YouTubePrefs(requireContext());
        rvFeed       = view.findViewById(R.id.rv_yt_home_feed);
        swipeRefresh = view.findViewById(R.id.srl_yt_home);
        pbLoading    = view.findViewById(R.id.pb_yt_home);
        llEmpty      = view.findViewById(R.id.ll_yt_empty);
        llChips      = view.findViewById(R.id.ll_yt_category_chips);

        adapter = new YouTubeVideoAdapter(requireActivity(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvFeed.setAdapter(adapter);
        applyPlaybackInFeeds();

        buildCategoryChips();

        swipeRefresh.setOnRefreshListener(() -> {
            detachFeedListener();
            loadFeed();
        });

        loadFeed();
    }

    // ── Category chips ─────────────────────────────────────────────────────────

    private void buildCategoryChips() {
        if (llChips == null || !isAdded()) return;
        llChips.removeAllViews();
        for (String cat : CATEGORIES) {
            TextView chip = new TextView(requireContext());
            chip.setText(cat);
            chip.setTextSize(13f);
            chip.setPadding(dpToPx(14), dpToPx(7), dpToPx(14), dpToPx(7));
            chip.setTextColor(ContextCompat.getColor(requireContext(),
                cat.equals(selectedCategory) ? android.R.color.white : android.R.color.black));
            chip.setBackground(ContextCompat.getDrawable(requireContext(),
                cat.equals(selectedCategory) ? R.drawable.bg_yt_chip_selected : R.drawable.bg_yt_chip));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dpToPx(8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                selectedCategory = cat;
                buildCategoryChips();
                filterAndDisplay();
            });
            llChips.addView(chip);
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * requireContext().getResources().getDisplayMetrics().density);
    }

    // ── Feed loading ──────────────────────────────────────────────────────────

    private void loadFeed() {
        showLoading(true);
        detachFeedListener();
        feedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                allVideos.clear();
                boolean restricted = ytPrefs.isRestrictedMode();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v == null) continue;
                    if (v.isShort) continue;
                    if (!"public".equals(v.visibility)) continue;
                    if (v.videoUrl == null || v.videoUrl.trim().isEmpty()) continue;
                    if (restricted && v.isAgeRestricted) continue;
                    allVideos.add(0, v);
                }
                filterAndDisplay();
                showLoading(false);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                showLoading(false);
                showEmpty(true);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        };
        YouTubeFirebaseUtils.globalFeedRef()
            .orderByChild("uploadedAt").limitToLast(60)
            .addValueEventListener(feedListener);
    }

    private void filterAndDisplay() {
        List<YouTubeVideo> filtered = new ArrayList<>();
        for (YouTubeVideo v : allVideos) {
            if ("All".equals(selectedCategory)
                    || (v.category != null && v.category.equalsIgnoreCase(selectedCategory))) {
                filtered.add(v);
            }
        }
        adapter.setData(filtered);
        showEmpty(filtered.isEmpty());
    }

    private void detachFeedListener() {
        if (feedListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(feedListener);
    }

    private void applyPlaybackInFeeds() {
        int setting = ytPrefs.getPlaybackInFeeds();
        boolean autoplay = (setting == 0); // 0=On, 1=Wi-Fi only, 2=Off
        adapter.setFeedAutoplay(autoplay);
    }

    private void showLoading(boolean show) {
        if (pbLoading != null) pbLoading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showEmpty(boolean show) {
        if (llEmpty != null) llEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        detachFeedListener();
    }
}
