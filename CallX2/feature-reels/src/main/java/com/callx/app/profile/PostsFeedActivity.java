package com.callx.app.profile;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.comments.ReelCommentActivity;
import com.callx.app.models.ReelModel;
import com.callx.app.reels.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * PostsFeedActivity — Instagram-style "tap a grid photo → scroll like Home
 * feed" screen.
 *
 * Launched ONLY from {@link UserReelsActivity}'s Posts tab (photos). Unlike
 * {@link com.callx.app.player.SingleReelPlayerActivity} (fullscreen vertical
 * ViewPager2, TikTok-style — used for the Reels / Repost / Duet / Series
 * tabs), this screen is a plain scrollable RecyclerView, same card layout
 * (`item_home_feed_post`) as HomeFragment's mixed feed, so Posts open the
 * same visual way Instagram's own profile-grid → post tap does.
 *
 * Scope note: Posts tab is photo-only (UserReelsActivity.filterPhotoPostsOnly),
 * so this screen only renders static images — no ExoPlayer/autoplay/ABR
 * plumbing is needed here (that stays in HomeFragment / SingleReelPlayerActivity
 * for actual video reels).
 *
 * Usage:
 *   Intent i = new Intent(context, PostsFeedActivity.class);
 *   i.putStringArrayListExtra(EXTRA_REEL_IDS, photoPostIds);
 *   i.putExtra(EXTRA_START_POSITION, tappedIndex);
 *   i.putExtra(EXTRA_TITLE, "John's Posts");
 *   startActivity(i);
 */
public class PostsFeedActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_IDS       = "reel_ids";
    public static final String EXTRA_START_POSITION = "start_position";
    public static final String EXTRA_TITLE          = "title";

    private RecyclerView   recyclerView;
    private ProgressBar    progressBar;
    private PostsAdapter   adapter;
    private int            startPosition;

    private final List<ReelModel> posts    = new ArrayList<>();
    private final Set<String>     likedIds  = new HashSet<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Root layout built in code — mirrors fragment_home's RecyclerView
        // shell, but standalone (no stories/trending/suggested sections;
        // this screen is only ever a single user's photo posts).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(getResources().getColor(R.color.background_light));

        // Simple top bar: back button + title, same as other reel screens.
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = dp(8);
        topBar.setPadding(pad, pad, pad, pad);
        ImageButton btnBack = new ImageButton(this);
        btnBack.setImageResource(R.drawable.ic_arrow_back);
        btnBack.setBackground(null);
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView tvTitle = new TextView(this);
        tvTitle.setText(getIntent().getStringExtra(EXTRA_TITLE) != null
            ? getIntent().getStringExtra(EXTRA_TITLE) : "Posts");
        tvTitle.setTextSize(16f);
        tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(8);
        topBar.addView(tvTitle, titleLp);
        root.addView(topBar, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(this);
        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        body.addView(recyclerView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
            dp(36), dp(36));
        pbLp.gravity = android.view.Gravity.CENTER;
        body.addView(progressBar, pbLp);

        root.addView(body, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        adapter = new PostsAdapter();
        recyclerView.setAdapter(adapter);

        startPosition = getIntent().getIntExtra(EXTRA_START_POSITION, 0);
        ArrayList<String> reelIds = getIntent().getStringArrayListExtra(EXTRA_REEL_IDS);
        if (reelIds == null || reelIds.isEmpty()) {
            Toast.makeText(this, "No posts to show", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        loadLikedState();
        loadPosts(reelIds);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Pre-fetch which of these posts the current user already liked, so the
     *  heart renders correctly on first bind (same pattern as HomeFragment). */
    private void loadLikedState() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) return;
        FirebaseUtils.getReelLikedByUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot c : snap.getChildren()) likedIds.add(c.getKey());
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    /** Same fetch-by-id-list pattern as SingleReelPlayerActivity.loadByReelIds —
     *  order preserved, results collected before first bind. */
    private void loadPosts(List<String> reelIds) {
        posts.clear();
        ReelModel[] slots = new ReelModel[reelIds.size()];
        final int total = reelIds.size();
        final int[] remaining = { total };

        for (int i = 0; i < total; i++) {
            final int idx = i;
            FirebaseUtils.getReelsRef().child(reelIds.get(i))
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        ReelModel r = snap.getValue(ReelModel.class);
                        if (r != null) { r.reelId = snap.getKey(); slots[idx] = r; }
                        onSlotDone();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) { onSlotDone(); }

                    private void onSlotDone() {
                        remaining[0]--;
                        if (remaining[0] == 0) finishLoading(slots);
                    }
                });
        }
    }

    private void finishLoading(ReelModel[] slots) {
        if (isFinishing() || isDestroyed()) return;
        progressBar.setVisibility(View.GONE);
        for (ReelModel r : slots) if (r != null) posts.add(r);
        adapter.notifyDataSetChanged();

        int safePos = Math.max(0, Math.min(startPosition, posts.size() - 1));
        if (!posts.isEmpty()) recyclerView.scrollToPosition(safePos);
    }

    // ── Adapter — reuses item_home_feed_post.xml (image-only bind) ─────────

    private class PostsAdapter extends RecyclerView.Adapter<PostsAdapter.Holder> {

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_home_feed_post, parent, false);
            return new Holder(v);
        }

        @Override public int getItemCount() { return posts.size(); }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            ReelModel r = posts.get(position);
            if (r == null) return;

            // Photo-only screen: hide the video surface, show static image.
            h.pvVideo.setVisibility(View.GONE);
            Glide.with(h.ivThumb.getContext())
                .load(r.effectiveThumbUrl())
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .into(h.ivThumb);

            h.tvOwner.setText(r.ownerName != null ? r.ownerName : "");
            Glide.with(h.ivAvatar.getContext())
                .load(r.ownerPhoto)
                .circleCrop()
                .into(h.ivAvatar);

            h.tvCaption.setText(r.caption != null ? r.caption : "");
            h.tvCaption.setVisibility(r.caption != null && !r.caption.isEmpty() ? View.VISIBLE : View.GONE);
            h.tvLikes.setText(String.valueOf(r.likesCount));
            h.tvComments.setText(String.valueOf(r.commentsCount));

            boolean liked = likedIds.contains(r.reelId);
            h.btnLike.setImageResource(liked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            h.btnLike.setOnClickListener(v -> toggleLike(r, h));

            h.btnComment.setOnClickListener(v -> {
                Intent ci = new Intent(v.getContext(), ReelCommentActivity.class);
                ci.putExtra(ReelCommentActivity.EXTRA_REEL_ID, r.reelId);
                ci.putExtra(ReelCommentActivity.EXTRA_REEL_UID, r.uid != null ? r.uid : "");
                startActivity(ci);
            });

            // Suggested/audio/follow-button rows aren't relevant on a
            // single-user filtered screen — keep them hidden.
            if (h.tvSuggested != null) h.tvSuggested.setVisibility(View.GONE);
            if (h.btnFollow   != null) h.btnFollow.setVisibility(View.GONE);
        }

        class Holder extends RecyclerView.ViewHolder {
            View      pvVideo; // PlayerView — only ever hidden on this screen
            ImageView ivThumb, ivAvatar;
            TextView  tvOwner, tvCaption, tvLikes, tvComments, tvSuggested;
            ImageButton btnLike, btnComment, btnFollow;

            Holder(@NonNull View itemView) {
                super(itemView);
                pvVideo     = itemView.findViewById(R.id.pv_feed_post);
                ivThumb     = itemView.findViewById(R.id.iv_post_thumb);
                ivAvatar    = itemView.findViewById(R.id.iv_post_avatar);
                tvOwner     = itemView.findViewById(R.id.tv_post_owner);
                tvSuggested = itemView.findViewById(R.id.tv_post_suggested);
                tvCaption   = itemView.findViewById(R.id.tv_post_caption);
                tvLikes     = itemView.findViewById(R.id.tv_post_likes);
                tvComments  = itemView.findViewById(R.id.tv_post_comments);
                btnLike     = itemView.findViewById(R.id.btn_post_like);
                btnComment  = itemView.findViewById(R.id.btn_post_comment);
                btnFollow   = itemView.findViewById(R.id.btn_post_follow);
            }
        }
    }

    /** Same like/unlike Firebase write pattern used elsewhere (UserReelsActivity,
     *  HomeFragment) — reelLikes/{reelId}/{uid} + reelLikedByUser/{uid}/{reelId}
     *  + transactional likesCount bump. */
    private void toggleLike(ReelModel r, PostsAdapter.Holder h) {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || r.reelId == null) return;

        boolean currentlyLiked = likedIds.contains(r.reelId);
        DatabaseReference likeRef       = FirebaseUtils.getReelsRef().child(r.reelId).child("likes").child(myUid);
        DatabaseReference likedByRef    = FirebaseUtils.getReelLikedByUserRef(myUid).child(r.reelId);
        DatabaseReference countRef      = FirebaseUtils.getReelsRef().child(r.reelId).child("likesCount");

        if (currentlyLiked) {
            likedIds.remove(r.reelId);
            likeRef.removeValue();
            likedByRef.removeValue();
            r.likesCount = Math.max(0, r.likesCount - 1);
        } else {
            likedIds.add(r.reelId);
            likeRef.setValue(System.currentTimeMillis());
            likedByRef.setValue(System.currentTimeMillis());
            r.likesCount = r.likesCount + 1;
        }
        countRef.runTransaction(new Transaction.Handler() {
            @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                Integer c = d.getValue(Integer.class);
                d.setValue(Math.max(0, (c != null ? c : 0) + (currentlyLiked ? -1 : 1)));
                return Transaction.success(d);
            }
            @Override public void onComplete(@Nullable DatabaseError e, boolean committed, @Nullable DataSnapshot s) {}
        });

        h.btnLike.setImageResource(!currentlyLiked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        h.tvLikes.setText(String.valueOf(r.likesCount));
    }
}
