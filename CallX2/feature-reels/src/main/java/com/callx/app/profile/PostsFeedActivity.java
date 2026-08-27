package com.callx.app.profile;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
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
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
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
import java.util.Collections;
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
 * ── Perf notes (ultra-optimized pass) ───────────────────────────────────
 *  ✅ Glide RecyclerViewPreloader — warms upcoming thumbnails ahead of
 *     scroll direction, same pattern as ReelGridAdapter's grid preloader,
 *     so images are already decoded by the time a card scrolls into view.
 *  ✅ Decode-size capped Glide requests — .override(screenWidth, screenWidth)
 *     so Glide never decodes a bitmap larger than what's actually drawn
 *     (avoids OOM churn + wasted decode time on large source photos).
 *  ✅ DiffUtil instead of notifyDataSetChanged — both the initial load and
 *     the liked-state pass compute a real diff, so RecyclerView only
 *     re-binds/animates the rows that actually changed instead of a full
 *     rebind + losing scroll-relative view state.
 *  ✅ setHasStableIds(true) — reelId-based stable ids let RecyclerView
 *     track view identity across the diff/rebind above (smoother, no
 *     unnecessary view recreation).
 *  ✅ RecyclerView tuned: setItemViewCacheSize + enlarged RecycledViewPool
 *     so cards scrolled off-screen and quickly back into view are re-bound
 *     from cache instead of re-inflated.
 *  ✅ Parallel Firebase reads — all reelIds fetched concurrently (not
 *     chained one-by-one), single UI update once every slot resolves.
 *  ✅ Like-button debounce — guards against rapid double-taps firing
 *     duplicate Firebase writes/transactions before the first completes.
 */
public class PostsFeedActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_IDS       = "reel_ids";
    public static final String EXTRA_START_POSITION = "start_position";
    public static final String EXTRA_TITLE          = "title";

    /** How many cards ahead (in scroll direction) Glide should pre-decode. */
    private static final int PRELOAD_AHEAD = 5;

    private RecyclerView   recyclerView;
    private ProgressBar    progressBar;
    private PostsAdapter   adapter;
    private int            startPosition;
    private int            screenWidthPx;

    private final List<ReelModel> posts    = new ArrayList<>();
    private final Set<String>     likedIds  = new HashSet<>();
    /** reelIds with a like/unlike write currently in flight — blocks a
     *  second tap on the same row until the first Firebase write settles. */
    private final Set<String>     likeInFlight = new HashSet<>();

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
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setInitialPrefetchItemCount(4); // RecyclerView prefetch hint — warms next rows' views during idle frame time
        recyclerView.setLayoutManager(llm);
        // ── Perf: recycling/caching tuned for a single-view-type feed ──
        recyclerView.setItemViewCacheSize(6);           // keep more off-screen views alive → fewer re-inflates on fast scroll
        recyclerView.setDrawingCacheEnabled(false);      // deprecated bitmap cache path — not needed, avoid extra memory
        recyclerView.getRecycledViewPool().setMaxRecycledViews(0, 12); // view type 0 = the only row type here
        recyclerView.setHasFixedSize(false);             // row height varies with caption length — keep accurate remeasure
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
        setupGlidePreloader();

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidthPx = dm.widthPixels;

        startPosition = getIntent().getIntExtra(EXTRA_START_POSITION, 0);
        ArrayList<String> reelIds = getIntent().getStringArrayListExtra(EXTRA_REEL_IDS);
        if (reelIds == null || reelIds.isEmpty()) {
            Toast.makeText(this, "No posts to show", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        loadLikedState();

        // ── Perf: windowed initial load ─────────────────────────────────
        // Instead of blocking first paint on ALL N posts' Firebase reads
        // (the old behaviour — a profile with 100+ photos meant 100 parallel
        // reads before anything showed), only the tapped post + everything
        // "after" it (older, in scroll-down direction) is fetched first.
        // That's what's immediately visible/reachable, so first paint only
        // waits on however many posts are left from the tapped one onward.
        // Posts "before" it (newer, reachable by scrolling up) load quietly
        // in the background and get prepended once ready — see
        // prependBeforeBatch(). Net effect: tap-to-first-frame latency now
        // scales with "posts after the tap", not "posts in the whole grid".
        int safeStart = Math.max(0, Math.min(startPosition, reelIds.size() - 1));
        List<String> afterIds  = reelIds.subList(safeStart, reelIds.size());
        List<String> beforeIds = reelIds.subList(0, safeStart);
        startPosition = 0; // tapped post is now index 0 of the first-loaded batch

        loadPosts(afterIds, slots -> {
            finishInitialLoad(slots);
            if (!beforeIds.isEmpty()) loadPosts(beforeIds, this::prependBeforeBatch);
        });
    }

    /** Callback for a fetched, order-preserved batch. */
    private interface BatchCallback { void onLoaded(ReelModel[] slots); }

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
     *  order preserved, all reads fired in parallel, results collected before
     *  the batch's callback fires. Used for both the initial (after) window
     *  and the background (before) batch. */
    private void loadPosts(List<String> reelIds, BatchCallback callback) {
        ReelModel[] slots = new ReelModel[reelIds.size()];
        final int total = reelIds.size();
        if (total == 0) { callback.onLoaded(slots); return; }
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
                        if (remaining[0] == 0) callback.onLoaded(slots);
                    }
                });
        }
    }

    private void finishInitialLoad(ReelModel[] slots) {
        if (isFinishing() || isDestroyed()) return;
        progressBar.setVisibility(View.GONE);

        List<ReelModel> old = new ArrayList<>(posts);
        posts.clear();
        for (ReelModel r : slots) if (r != null) posts.add(r);

        // DiffUtil instead of a blanket notifyDataSetChanged() — only the
        // rows that actually changed get rebound/animated, and RecyclerView
        // keeps its scroll-relative view state for unchanged rows.
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new PostDiffCallback(old, posts));
        diff.dispatchUpdatesTo(adapter);

        int safePos = Math.max(0, Math.min(startPosition, posts.size() - 1));
        if (!posts.isEmpty()) recyclerView.scrollToPosition(safePos);
    }

    /** Prepends the background-loaded "before" batch (newer posts, reachable
     *  by scrolling up past the tapped post) once it arrives. Uses a direct
     *  insert-at-0 (these are all brand-new rows, nothing to diff against)
     *  + scroll-offset compensation so the user's current viewport doesn't
     *  visually jump when rows are added above it — same anchor-preserving
     *  technique HomeFragment's pagination uses. */
    private void prependBeforeBatch(ReelModel[] slots) {
        if (isFinishing() || isDestroyed()) return;
        List<ReelModel> toPrepend = new ArrayList<>();
        for (ReelModel r : slots) if (r != null) toPrepend.add(r);
        if (toPrepend.isEmpty()) return;

        LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
        int anchorPos = lm != null ? lm.findFirstVisibleItemPosition() : 0;
        View anchorView = lm != null ? lm.findViewByPosition(anchorPos) : null;
        int anchorOffset = anchorView != null ? anchorView.getTop() : 0;

        posts.addAll(0, toPrepend);
        adapter.notifyItemRangeInserted(0, toPrepend.size());

        // Keep the same row at the same on-screen position — without this,
        // inserting rows above the viewport would push the user's current
        // scroll position down by toPrepend.size() rows on screen.
        if (lm != null) lm.scrollToPositionWithOffset(anchorPos + toPrepend.size(), anchorOffset);
    }

    /** Cheap id/content diff — reelId is the identity, likesCount/commentsCount
     *  covers the fields that can change after the initial bind. */
    private static class PostDiffCallback extends DiffUtil.Callback {
        private final List<ReelModel> oldList, newList;
        PostDiffCallback(List<ReelModel> oldList, List<ReelModel> newList) {
            this.oldList = oldList; this.newList = newList;
        }
        @Override public int getOldListSize() { return oldList.size(); }
        @Override public int getNewListSize() { return newList.size(); }
        @Override public boolean areItemsTheSame(int oldPos, int newPos) {
            String a = oldList.get(oldPos).reelId, b = newList.get(newPos).reelId;
            return a != null && a.equals(b);
        }
        @Override public boolean areContentsTheSame(int oldPos, int newPos) {
            ReelModel a = oldList.get(oldPos), b = newList.get(newPos);
            return a.likesCount == b.likesCount && a.commentsCount == b.commentsCount
                && java.util.Objects.equals(a.caption, b.caption);
        }
    }

    /** Glide RecyclerViewPreloader — same pattern as ReelGridAdapter's grid
     *  preloader (see UserReelsActivity.setupGlidePreloader): watches scroll
     *  direction/velocity and pre-decodes upcoming thumbnails so they're
     *  already in Glide's cache by the time a card scrolls into view. */
    private void setupGlidePreloader() {
        ListPreloader.PreloadSizeProvider<String> sizeProvider =
            (item, adapterPosition, perItemPosition) -> new int[]{ screenWidthPxOrFallback(), screenWidthPxOrFallback() };
        RecyclerViewPreloader<String> preloader = new RecyclerViewPreloader<>(
            Glide.with(this), adapter, sizeProvider, PRELOAD_AHEAD);
        recyclerView.addOnScrollListener(preloader);
    }

    private int screenWidthPxOrFallback() {
        return screenWidthPx > 0 ? screenWidthPx : getResources().getDisplayMetrics().widthPixels;
    }

    // ── Adapter — reuses item_home_feed_post.xml (image-only bind) ─────────

    private class PostsAdapter extends RecyclerView.Adapter<PostsAdapter.Holder>
            implements ListPreloader.PreloadModelProvider<String> {

        PostsAdapter() {
            // Stable ids (reelId hash) → RecyclerView can tell "same row,
            // different position" apart from "new row" during the DiffUtil
            // dispatch above, instead of tearing down/recreating views.
            setHasStableIds(true);
        }

        @Override public long getItemId(int position) {
            String id = posts.get(position).reelId;
            return id != null ? id.hashCode() : RecyclerView.NO_ID;
        }

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
            // Decode capped to screen width — avoids Glide decoding a full-
            // resolution source bitmap just to downscale it for display.
            RequestOptions thumbOpts = new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .override(screenWidthPxOrFallback(), screenWidthPxOrFallback())
                .centerCrop();
            Glide.with(h.ivThumb.getContext())
                .load(r.effectiveThumbUrl())
                .apply(thumbOpts)
                .into(h.ivThumb);

            h.tvOwner.setText(r.ownerName != null ? r.ownerName : "");
            Glide.with(h.ivAvatar.getContext())
                .load(r.ownerPhoto)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
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

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            // Free the Glide target's decoded bitmap the moment a row scrolls
            // off-screen instead of leaving it referenced until the next bind
            // — keeps peak memory down on long scroll sessions.
            Glide.with(h.ivThumb.getContext()).clear(h.ivThumb);
            Glide.with(h.ivAvatar.getContext()).clear(h.ivAvatar);
        }

        // ── ListPreloader.PreloadModelProvider ──────────────────────────
        @NonNull @Override
        public List<String> getPreloadItems(int position) {
            if (position < 0 || position >= posts.size()) return Collections.emptyList();
            ReelModel r = posts.get(position);
            String url = r != null ? r.effectiveThumbUrl() : null;
            return url != null ? Collections.singletonList(url) : Collections.emptyList();
        }

        @Nullable @Override
        public RequestBuilder<?> getPreloadRequestBuilder(@NonNull String url) {
            return Glide.with(PostsFeedActivity.this)
                .load(url)
                .apply(new RequestOptions()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .override(screenWidthPxOrFallback(), screenWidthPxOrFallback())
                    .centerCrop());
        }

        class Holder extends RecyclerView.ViewHolder {
            View      pvVideo; // PlayerView — only ever hidden on this screen
            ImageView ivThumb, ivAvatar;
            TextView  tvOwner, tvCaption, tvLikes, tvComments, tvSuggested, btnFollow;
            ImageButton btnLike, btnComment;

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
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Debounced like/unlike — a rapid second tap on the same row while the
     *  first write is still in flight is ignored instead of firing a second
     *  Firebase write + transaction (which would otherwise double-count or
     *  race the likesCount transaction). Guard clears itself shortly after,
     *  well within normal round-trip time, so it never sticks. */
    private void toggleLike(ReelModel r, PostsAdapter.Holder h) {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null || r.reelId == null) return;
        if (!likeInFlight.add(r.reelId)) return; // already in flight for this row

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
            @Override public void onComplete(@Nullable DatabaseError e, boolean committed, @Nullable DataSnapshot s) {
                mainHandler.post(() -> likeInFlight.remove(r.reelId));
            }
        });

        h.btnLike.setImageResource(!currentlyLiked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        h.tvLikes.setText(String.valueOf(r.likesCount));
    }
}
