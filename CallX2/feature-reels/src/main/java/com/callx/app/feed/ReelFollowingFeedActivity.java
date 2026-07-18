package com.callx.app.feed;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

import java.util.*;

/**
 * ReelFollowingFeedActivity — "Following" tab reel feed.
 *
 * Features:
 *  ✅ Shows reels ONLY from users the logged-in user follows
 *  ✅ Two tabs: "Following" (this screen) and "For You" (main ReelsFragment)
 *  ✅ Full-screen vertical ViewPager-style RecyclerView (snap paging)
 *  ✅ Loads follows list from Firebase: follows/{myUid}/{followingUid}
 *  ✅ Fetches recent reels from each followed user
 *  ✅ Shows creator name, avatar, caption, like/comment/share row
 *  ✅ Pull-to-refresh
 *  ✅ Empty state when not following anyone
 *  ✅ "Find people to follow" CTA button on empty state
 */
public class ReelFollowingFeedActivity extends AppCompatActivity {

    private RecyclerView      rvFeed;
    private SwipeRefreshLayout swipeRefresh;
    private View              layoutEmpty;
    private ProgressBar       progressLoad;
    private TextView          btnFindPeople;
    private ImageButton       btnBack;

    private FollowingFeedAdapter  adapter;
    private final List<ReelItem>  reels  = new ArrayList<>();
    private String                myUid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_following_feed);

        try { myUid = FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { finish(); return; }

        bindViews();
        loadFollowingReels();
    }

    private void bindViews() {
        btnBack       = findViewById(R.id.btn_following_back);
        rvFeed        = findViewById(R.id.rv_following_feed);
        swipeRefresh  = findViewById(R.id.swipe_following_refresh);
        layoutEmpty   = findViewById(R.id.layout_following_empty);
        progressLoad  = findViewById(R.id.progress_following_load);
        btnFindPeople = findViewById(R.id.btn_following_find_people);

        btnBack.setOnClickListener(v -> finish());

        adapter = new FollowingFeedAdapter(reels);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        rvFeed.setLayoutManager(llm);

        // Snap-to-full-screen paging
        PagerSnapHelper snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(rvFeed);
        rvFeed.setAdapter(adapter);

        swipeRefresh.setColorSchemeColors(0xFFFF3B5C);
        swipeRefresh.setOnRefreshListener(() -> {
            reels.clear();
            adapter.notifyDataSetChanged();
            loadFollowingReels();
        });

        btnFindPeople.setOnClickListener(v -> finish());
    }

    private void loadFollowingReels() {
        progressLoad.setVisibility(View.VISIBLE);
        layoutEmpty.setVisibility(View.GONE);
        rvFeed.setVisibility(View.GONE);

        FirebaseUtils.db().getReference("follows").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    List<String> followingUids = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        Boolean val = s.getValue(Boolean.class);
                        if (Boolean.TRUE.equals(val)) followingUids.add(s.getKey());
                    }
                    if (followingUids.isEmpty()) {
                        showEmptyState();
                        return;
                    }
                    fetchReelsFromUsers(followingUids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { showEmptyState(); }
            });
    }

    private void fetchReelsFromUsers(List<String> uids) {
        final int[] remaining = {uids.size()};
        for (String uid : uids) {
            FirebaseUtils.getReelsByUserRef(uid)
                .limitToLast(5)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (isFinishing() || isDestroyed()) return;
                        for (DataSnapshot s : snap.getChildren()) {
                            String reelId = s.getKey();
                            if (reelId != null) fetchReelDetails(reelId, uid);
                        }
                        remaining[0]--;
                        if (remaining[0] <= 0) finishLoading();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        remaining[0]--;
                        if (remaining[0] <= 0) finishLoading();
                    }
                });
        }
    }

    private void fetchReelDetails(String reelId, String ownerUid) {
        FirebaseUtils.getReelsRef().child(reelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    if (isFinishing() || isDestroyed()) return;
                    ReelItem item = new ReelItem();
                    item.reelId    = reelId;
                    item.ownerUid  = ownerUid;
                    item.videoUrl  = str(s, "videoUrl");
                    item.thumbUrl  = str(s, "thumbnailUrl");
                    item.caption   = str(s, "caption");
                    item.userName  = str(s, "userName");
                    item.userPhoto = str(s, "userPhoto");
                    Long likes     = s.child("likes").getValue(Long.class);
                    item.likeCount = likes != null ? likes : 0L;
                    if (!item.videoUrl.isEmpty()) {
                        reels.add(item);
                        adapter.notifyItemInserted(reels.size() - 1);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void finishLoading() {
        progressLoad.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
        if (reels.isEmpty()) {
            showEmptyState();
        } else {
            rvFeed.setVisibility(View.VISIBLE);
        }
    }

    private void showEmptyState() {
        progressLoad.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
        layoutEmpty.setVisibility(View.VISIBLE);
        rvFeed.setVisibility(View.GONE);
    }

    private String str(DataSnapshot s, String key) {
        String v = s.child(key).getValue(String.class);
        return v != null ? v : "";
    }

    // ── Data model ────────────────────────────────────────────────────────
    static class ReelItem {
        String reelId, ownerUid, videoUrl, thumbUrl, caption, userName, userPhoto;
        long likeCount;
    }

    // ── Feed adapter ──────────────────────────────────────────────────────
    static class FollowingFeedAdapter extends RecyclerView.Adapter<FollowingFeedAdapter.VH> {
        private final List<ReelItem> items;
        FollowingFeedAdapter(List<ReelItem> items) { this.items = items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_following_reel, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ReelItem item = items.get(pos);

            h.tvUserName.setText(item.userName.isEmpty() ? "Creator" : item.userName);
            h.tvCaption.setText(item.caption);
            h.tvLikes.setText(formatCount(item.likeCount));

            if (!item.thumbUrl.isEmpty()) {
                Glide.with(h.ivThumb).load(item.thumbUrl)
                    .override(480, 853)
                    .centerCrop().into(h.ivThumb);
            }
            if (!item.userPhoto.isEmpty()) {
                Glide.with(h.ivAvatar).load(item.userPhoto)
                    .override(96, 96)
                    .placeholder(R.drawable.ic_person).circleCrop().into(h.ivAvatar);
            }
        }

        @Override public int getItemCount() { return items.size(); }

        private static String formatCount(long n) {
            if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
            if (n >= 1_000)     return String.format("%.1fK", n / 1_000.0);
            return String.valueOf(n);
        }

        static class VH extends RecyclerView.ViewHolder {
            ImageView       ivThumb;
            CircleImageView ivAvatar;
            TextView        tvUserName, tvCaption, tvLikes;

            VH(View v) {
                super(v);
                ivThumb    = v.findViewById(R.id.iv_following_reel_thumb);
                ivAvatar   = v.findViewById(R.id.iv_following_creator_avatar);
                tvUserName = v.findViewById(R.id.tv_following_creator_name);
                tvCaption  = v.findViewById(R.id.tv_following_reel_caption);
                tvLikes    = v.findViewById(R.id.tv_following_likes);
            }
        }
    }
}
