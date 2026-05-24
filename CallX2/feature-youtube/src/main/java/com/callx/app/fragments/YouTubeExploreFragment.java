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
import java.util.List;

/** Explore tab — sorted by viewCount with category filter chips. */
public class YouTubeExploreFragment extends Fragment {

    private RecyclerView       rvTrending;
    private YouTubeVideoAdapter adapter;
    private LinearLayout       layoutChips;
    private String             activeCategory = "all";
    private ValueEventListener trendingListener;

    private static final String[] CATEGORIES = {
        "all","Music","Gaming","News","Sports","Education","Comedy","Tech","Entertainment"
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p,
                             @Nullable Bundle s) {
        return inf.inflate(R.layout.fragment_youtube_explore, p, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);

        rvTrending  = view.findViewById(R.id.rv_yt_trending);
        layoutChips = view.findViewById(R.id.layout_yt_explore_chips);

        adapter = new YouTubeVideoAdapter(requireContext(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvTrending.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvTrending.setAdapter(adapter);

        buildChips();
        loadTrending();
    }

    private void buildChips() {
        if (layoutChips == null) return;
        for (String cat : CATEGORIES) {
            TextView chip = new TextView(requireContext());
            chip.setText("all".equals(cat) ? "All" : cat);
            chip.setTag(cat);
            chip.setPadding(32, 16, 32, 16);
            chip.setBackgroundResource(R.drawable.bg_yt_chip);
            chip.setOnClickListener(v -> {
                activeCategory = (String) v.getTag();
                highlightChip(v);
                detachListener();
                loadTrending();
            });
            layoutChips.addView(chip);
        }
        if (layoutChips.getChildCount() > 0) layoutChips.getChildAt(0).setSelected(true);
    }

    private void highlightChip(View sel) {
        for (int i = 0; i < layoutChips.getChildCount(); i++) {
            View c = layoutChips.getChildAt(i);
            c.setSelected(c == sel);
        }
    }

    private void loadTrending() {
        DatabaseReference ref = "all".equals(activeCategory)
            ? YouTubeFirebaseUtils.globalFeedRef()
            : YouTubeFirebaseUtils.categoryFeedRef(activeCategory);

        trendingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v != null && "public".equals(v.visibility) && !v.isShort) list.add(v);
                }
                list.sort((a, b) -> Long.compare(b.viewCount, a.viewCount));
                adapter.setData(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        ref.orderByChild("viewCount").limitToLast(50).addValueEventListener(trendingListener);
    }

    private void detachListener() {
        if (trendingListener == null) return;
        DatabaseReference ref = "all".equals(activeCategory)
            ? YouTubeFirebaseUtils.globalFeedRef()
            : YouTubeFirebaseUtils.categoryFeedRef(activeCategory);
        ref.removeEventListener(trendingListener);
        trendingListener = null;
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        detachListener();
    }
}
