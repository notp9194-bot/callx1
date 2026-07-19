package com.callx.app.search;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.player.YouTubePlayerActivity;
import com.callx.app.home.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;

/**
 * YouTubeExploreFragment — Explore/Trending tab with full category filter chips.
 * Categories: All, Music, Gaming, News, Sports, Technology, Education,
 *             Entertainment, Travel, Food, Beauty, Science, Comedy.
 * Each chip click filters the Firebase globalFeed by category.
 */
public class YouTubeExploreFragment extends Fragment {

    private static final String[] CATEGORIES = {
        "All", "Music", "Gaming", "News", "Sports", "Technology",
        "Education", "Entertainment", "Travel", "Food", "Beauty", "Science", "Comedy"
    };

    private RecyclerView        rvTrending;
    private YouTubeVideoAdapter adapter;
    private ValueEventListener  trendingListener;
    private LinearLayout        llCategoryChips;
    private String              selectedCategory = "All";
    private List<TextView>      chipViews = new ArrayList<>();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup parent,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_explore, parent, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        llCategoryChips = view.findViewById(R.id.ll_yt_category_chips);
        rvTrending      = view.findViewById(R.id.rv_yt_trending);

        adapter = new YouTubeVideoAdapter(requireActivity(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvTrending.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvTrending.setAdapter(adapter);

        setupCategoryChips();
        loadVideos("All");
    }

    private void setupCategoryChips() {
        if (llCategoryChips == null) return;
        llCategoryChips.removeAllViews();
        chipViews.clear();

        for (String cat : CATEGORIES) {
            TextView chip = buildChip(cat);
            chipViews.add(chip);
            llCategoryChips.addView(chip);
        }
        updateChipSelection();
    }

    private TextView buildChip(String label) {
        TextView tv = new TextView(requireContext());
        tv.setText(label);
        tv.setTextSize(13f);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        tv.setClickable(true);
        tv.setFocusable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), dp(8), dp(4), dp(8));
        tv.setLayoutParams(lp);

        // Rounded background
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(label.equals(selectedCategory) ? 0xFFFFFFFF : 0xFF303030);
        tv.setBackground(bg);
        tv.setTextColor(label.equals(selectedCategory) ? 0xFF000000 : 0xFFFFFFFF);

        tv.setOnClickListener(v -> {
            selectedCategory = label;
            updateChipSelection();
            loadVideos(label);
        });
        return tv;
    }

    private void updateChipSelection() {
        for (int i = 0; i < chipViews.size(); i++) {
            TextView chip = chipViews.get(i);
            boolean selected = CATEGORIES[i].equals(selectedCategory);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(selected ? 0xFFFFFFFF : 0xFF303030);
            chip.setBackground(bg);
            chip.setTextColor(selected ? 0xFF000000 : 0xFFFFFFFF);
            chip.setTypeface(null, selected
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    private void loadVideos(String category) {
        // Remove previous listener
        if (trendingListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(trendingListener);

        trendingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v == null || !"public".equals(v.visibility)) continue;
                    if (!"All".equals(category)) {
                        if (v.category == null || !v.category.equalsIgnoreCase(category)) continue;
                    }
                    list.add(v);
                }
                // Sort by viewCount descending
                list.sort((a, b) -> Long.compare(b.viewCount, a.viewCount));
                if (getActivity() != null) {
                    requireActivity().runOnUiThread(() -> {
                        if (adapter != null) adapter.setData(list);
                        // Show empty state if needed
                        View tvEmpty = getView() != null
                            ? getView().findViewById(R.id.tv_yt_explore_empty) : null;
                        if (tvEmpty != null)
                            tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                    });
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };

        DatabaseReference ref = YouTubeFirebaseUtils.globalFeedRef();
        if (!"All".equals(category)) {
            ref.orderByChild("category").equalTo(category)
                .addValueEventListener(trendingListener);
        } else {
            ref.orderByChild("viewCount").limitToLast(50)
                .addValueEventListener(trendingListener);
        }
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (trendingListener != null)
            YouTubeFirebaseUtils.globalFeedRef().removeEventListener(trendingListener);
    }

    private int dp(int val) {
        return Math.round(val * requireContext().getResources().getDisplayMetrics().density);
    }
}
