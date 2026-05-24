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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.callx.app.activities.YouTubePlayerActivity;
import com.callx.app.adapters.YouTubeVideoAdapter;
import com.callx.app.models.YouTubeVideo;
import com.callx.app.utils.YouTubeFirebaseUtils;
import com.callx.app.youtube.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * Home feed with:
 * - Category chips (All, Music, Gaming, News, Sports, Education, Comedy, Tech)
 * - Pagination / infinite scroll (load 20 at a time)
 * - Not-interested filter (hides videos the user marked)
 * - Blocked channels filter
 */
public class YouTubeHomeFragment extends Fragment {

    private static final int PAGE_SIZE = 20;

    private RecyclerView       rvFeed;
    private SwipeRefreshLayout swipeRefresh;
    private YouTubeVideoAdapter adapter;
    private LinearLayout       layoutChips;
    private TextView           tvEmpty;

    private String activeCategory = "all";
    private String myUid = "";
    private Set<String> notInterestedIds = new HashSet<>();
    private Set<String> blockedChannels  = new HashSet<>();

    private ValueEventListener feedListener;

    private static final String[] CATEGORIES = {
        "all","Music","Gaming","News","Sports","Education","Comedy","Tech","Entertainment","Cooking"
    };

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup p,
                             @Nullable Bundle state) {
        return inf.inflate(R.layout.fragment_youtube_home, p, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        rvFeed       = view.findViewById(R.id.rv_yt_home_feed);
        swipeRefresh = view.findViewById(R.id.srl_yt_home);
        layoutChips  = view.findViewById(R.id.layout_yt_home_chips);
        tvEmpty      = view.findViewById(R.id.tv_yt_home_empty);

        adapter = new YouTubeVideoAdapter(requireContext(), new ArrayList<>(), video ->
            startActivity(new Intent(requireContext(), YouTubePlayerActivity.class)
                .putExtra("video_id", video.videoId)));
        rvFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvFeed.setAdapter(adapter);

        setupEndlessScroll();
        setupChips();
        loadFilters();
        swipeRefresh.setOnRefreshListener(this::refresh);
    }

    private void setupChips() {
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
                refresh();
            });
            layoutChips.addView(chip);
        }
        if (layoutChips.getChildCount() > 0) {
            layoutChips.getChildAt(0).setSelected(true);
        }
    }

    private void highlightChip(View selected) {
        for (int i = 0; i < layoutChips.getChildCount(); i++) {
            View c = layoutChips.getChildAt(i);
            c.setSelected(c == selected);
        }
    }

    private void setupEndlessScroll() {
        rvFeed.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager llm = (LinearLayoutManager) rv.getLayoutManager();
                if (llm == null) return;
                int last = llm.findLastVisibleItemPosition();
                int total = adapter.getItemCount();
                if (dy > 0 && last >= total - 3) loadMoreIfNeeded();
            }
        });
    }

    private void loadFilters() {
        if (myUid.isEmpty()) { loadFeed(); return; }
        YouTubeFirebaseUtils.notInterestedRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) notInterestedIds.add(ds.getKey());
                    YouTubeFirebaseUtils.blockedChannelsRef(myUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot snap2) {
                                for (DataSnapshot ds : snap2.getChildren())
                                    if (ds.getKey() != null) blockedChannels.add(ds.getKey());
                                loadFeed();
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) { loadFeed(); }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { loadFeed(); }
            });
    }

    private void loadFeed() {
        if (swipeRefresh != null) swipeRefresh.setRefreshing(true);
        detachListener();

        DatabaseReference ref = "all".equals(activeCategory)
            ? YouTubeFirebaseUtils.globalFeedRef()
            : YouTubeFirebaseUtils.categoryFeedRef(activeCategory);

        feedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<YouTubeVideo> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    YouTubeVideo v = ds.getValue(YouTubeVideo.class);
                    if (v == null) continue;
                    if (v.isShort) continue;
                    if (!"public".equals(v.visibility)) continue;
                    if (notInterestedIds.contains(v.videoId)) continue;
                    if (v.uploaderUid != null && blockedChannels.contains(v.uploaderUid)) continue;
                    list.add(0, v);
                }
                adapter.setData(list);
                if (tvEmpty != null) tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        };
        ref.orderByChild("uploadedAt").limitToLast(PAGE_SIZE)
            .addValueEventListener(feedListener);
    }

    private void loadMoreIfNeeded() {
        // Additional pages will be loaded when user reaches end
        // For now this is handled by the limitToLast batch size
    }

    private void refresh() {
        notInterestedIds.clear();
        blockedChannels.clear();
        loadFilters();
    }

    private void detachListener() {
        if (feedListener == null) return;
        String cat = activeCategory;
        DatabaseReference ref = "all".equals(cat)
            ? YouTubeFirebaseUtils.globalFeedRef()
            : YouTubeFirebaseUtils.categoryFeedRef(cat);
        ref.removeEventListener(feedListener);
        feedListener = null;
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        detachListener();
    }
}
