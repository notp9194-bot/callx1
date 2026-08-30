package com.callx.app.profile;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.media.MediaPlayer;
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
import android.widget.PopupMenu;
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
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.comments.ReelCommentActivity;
import com.callx.app.cache.PostFeedAvatarBinder;
import com.callx.app.feed.PostFeedUltraOptimizer;
import com.callx.app.models.ReelModel;
import com.callx.app.player.ReelOfflineManager;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.social.ReelShareSheetFragment;
import com.callx.app.social.ReelSharesBottomSheet;
import com.callx.app.reels.R;
import com.callx.app.utils.AlertDialogStyler;
import com.callx.app.utils.FirebaseUtils;
import de.hdodenhof.circleimageview.CircleImageView;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * for actual video reels). A photo post CAN still carry an attached music
 * track (ReelModel.musicUrl) though — that's played back through a
 * lightweight shared MediaPlayer (see the "Instagram-style background audio
 * engine" section below), same approach ReelPlayerController uses for photo
 * slideshow reels in the immersive player, just driven by scroll position
 * here instead of ViewPager2 page selection.
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
 *  ✅ PostFeedUltraOptimizer (v287) — HomeFeedUltraOptimizer's coordinator
 *     pattern, scoped for this photo-only feed: gates window-reload work
 *     behind scroll-settled (was firing mid-fling before), coalesces
 *     per-post reads through PostFeedNetworkBatcher (was one uncoalesced
 *     read per id), and skips the v284 standing live-count listener in
 *     favor of a one-time read when ReelThermalManager reports HOT.
 */
public class PostsFeedActivity extends AppCompatActivity {

    public static final String EXTRA_REEL_IDS       = "reel_ids";
    public static final String EXTRA_START_POSITION = "start_position";
    public static final String EXTRA_TITLE          = "title";

    /** How many cards ahead (in scroll direction) Glide should pre-decode. */
    private static final int PRELOAD_AHEAD = 5;

    /** Caption truncation — read by both onBindViewHolder (initial state)
     *  and the once-registered "more"/"less" click listener in
     *  setupClickListenersOnce(), which needs the same value the bind used. */
    private static final int CAPTION_MAX_LINES = 2;

    private RecyclerView   recyclerView;
    private ProgressBar    progressBar;
    private PostsAdapter   adapter;
    private int            startPosition;
    private int            screenWidthPx;

    // ── Instagram-style per-post background audio ───────────────────────
    // Photo posts can carry an attached music track (ReelModel.musicUrl —
    // same field video reels + photo-slideshow reels use). This screen
    // hides the video surface (photo-only), but audio still needs to play
    // for whichever card is currently most visible on screen, exactly like
    // Instagram's feed — one shared MediaPlayer, swapped as the user
    // scrolls between posts, with a mute toggle on each card.
    // Mirrors ReelPlayerController's photoAudioPlayer pattern (trim window
    // + loop support) used by the immersive Reels player, just scoped to
    // a scrollable list instead of one-post-at-a-time.
    private MediaPlayer  activeAudioPlayer;
    private String       activeAudioReelId;
    private int           activeAudioPosition = -1;
    private boolean       audioStarted = false;
    private boolean       isMuted = false;
    private Runnable      audioLoopRunnable;

    private final List<ReelModel> posts    = new ArrayList<>();
    // ── v285: list cap / windowing (same idea as HomeFragment's v280) ───
    // originalReelIds is the FULL id list this screen was launched with
    // (order preserved); posts is only ever a WINDOW of it. windowStartOffset
    // is the index into originalReelIds that posts.get(0) currently
    // corresponds to, so trimmed rows can be re-fetched by id if the user
    // scrolls back to them.
    private List<String>          originalReelIds = new ArrayList<>();
    private int                   windowStartOffset = 0;
    private static final int      POST_WINDOW_BEHIND = 15;
    private static final int      POST_WINDOW_AHEAD  = 30;
    private static final int      POST_TRIM_SLACK    = 10;
    // v286: cap on how many Firebase reads the initial open fires in
    // parallel — same size as the steady-state window, so first paint
    // fetches "enough to fill the window" instead of "every post from the
    // tap onward" (which on a 100+ photo profile meant 100 parallel reads).
    private static final int      POST_INITIAL_BATCH = POST_WINDOW_BEHIND + POST_WINDOW_AHEAD;
    private boolean                trimInFlight = false;
    private final Set<String>     likedIds  = new HashSet<>();
    /** reelIds with a like/unlike write currently in flight — blocks a
     *  second tap on the same row until the first Firebase write settles. */
    private final Set<String>     likeInFlight = new HashSet<>();
    /** reelIds this user has saved/bookmarked — same source pattern as
     *  likedIds (see loadSavedState()), used to render btn_post_save's
     *  filled/outline state correctly on first bind. */
    private final Set<String>     savedIds  = new HashSet<>();

    /** Lazily created, same singleton HomeFragment's "Save for offline"
     *  action uses — a reel saved from either screen is available offline
     *  in both. */
    private ReelOfflineManager offlineManager;

    /** v287: scroll-state gating + network batching + thermal awareness
     *  for this screen — see PostFeedUltraOptimizer's class doc. */
    private final PostFeedUltraOptimizer feedOptimizer = new PostFeedUltraOptimizer();

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

        // Track scroll position → whichever card is most visible gets the
        // active background-audio track (same "most visible card wins" idea
        // HomeFragment uses for its shared ExoPlayer, just driving a
        // MediaPlayer here since this screen is photo-only).
        // v287: initialize the scroll-gating/batching/thermal coordinator
        // before the scroll listener that drives it. onScrollSettled is the
        // re-trigger for any window reload that got deferred mid-fling below.
        feedOptimizer.initialize(this, FirebaseUtils.getReelsRef(), mainHandler,
            this::maybeTrimOrReloadPostWindow);

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            private long lastTimeMs = 0L;

            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                feedOptimizer.onRecyclerScrolled(dx, dy);
                updateActiveAudioForVisibleItem();
                maybeTrimOrReloadPostWindow();

                // FIX (velocity-based prefetch): route through
                // PostFeedAvatarBinder.prefetch() — same velocity
                // measurement + depth thresholds as every other avatar
                // list in the app (fast fling skips prefetch entirely,
                // slow scroll warms several rows ahead). Mostly a no-op
                // hit for the repeated single-owner avatar, but pays off
                // for the varying collab-post avatars scattered through
                // this feed.
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null) {
                    int lastVisible = lm.findLastVisibleItemPosition();
                    if (lastVisible >= 0) {
                        long now = android.os.SystemClock.elapsedRealtime();
                        long dt = lastTimeMs == 0L ? 0L : (now - lastTimeMs);
                        float velocity = (dt > 0) ? Math.abs(dy) / (float) dt : 0f;
                        lastTimeMs = now;
                        PostFeedAvatarBinder.prefetch(PostsFeedActivity.this, postFeedAvatarSource(), lastVisible + 1, velocity);
                    }
                }
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                feedOptimizer.onRecyclerScrollStateChanged(newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) updateActiveAudioForVisibleItem();
            }
        });

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenWidthPx = dm.widthPixels;

        startPosition = getIntent().getIntExtra(EXTRA_START_POSITION, 0);
        ArrayList<String> reelIds = getIntent().getStringArrayListExtra(EXTRA_REEL_IDS);
        if (reelIds == null || reelIds.isEmpty()) {
            Toast.makeText(this, "No posts to show", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        originalReelIds = new ArrayList<>(reelIds); // v285: full-list reference for windowing/reload
        loadLikedState();
        loadSavedState();

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
        //
        // v286: that still fired every id in "after" (and, once backgrounded,
        // every id in "before") as parallel reads in one shot — bounded to
        // POST_INITIAL_BATCH each on both sides now. Whatever's past that
        // isn't fetched at all up front; it streams in lazily via v285's
        // reloadPostsAfter()/reloadPostsBefore() only once the user actually
        // scrolls that far, so parallel-read count no longer scales with
        // profile size at all.
        int safeStart = Math.max(0, Math.min(startPosition, reelIds.size() - 1));
        List<String> fullAfterIds  = reelIds.subList(safeStart, reelIds.size());
        List<String> fullBeforeIds = reelIds.subList(0, safeStart);
        startPosition = 0; // tapped post is now index 0 of the first-loaded batch

        int afterBatch = Math.min(fullAfterIds.size(), POST_INITIAL_BATCH);
        List<String> afterIds = fullAfterIds.subList(0, afterBatch);
        // Closest-to-tap slice of "before" — contiguous with the window so
        // windowStartOffset still lines up exactly after prependBeforeBatch.
        int beforeBatch = Math.min(fullBeforeIds.size(), POST_INITIAL_BATCH);
        List<String> beforeIds = fullBeforeIds.subList(fullBeforeIds.size() - beforeBatch, fullBeforeIds.size());

        loadPosts(afterIds, slots -> {
            windowStartOffset = safeStart; // v285: posts[0] currently maps to originalReelIds[safeStart]
            finishInitialLoad(slots);
            if (!beforeIds.isEmpty()) loadPosts(beforeIds, this::prependBeforeBatch);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Don't tear the player down — just pause, so a quick backgrounding
        // (e.g. notification shade, app switcher) resumes exactly where it
        // left off instead of restarting the track from zero.
        cancelAudioLoop();
        if (activeAudioPlayer != null) {
            try { if (activeAudioPlayer.isPlaying()) activeAudioPlayer.pause(); }
            catch (Exception ignored) {}
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activeAudioPlayer != null && audioStarted) {
            try {
                if (!activeAudioPlayer.isPlaying()) activeAudioPlayer.start();
                if (activeAudioPosition >= 0 && activeAudioPosition < posts.size()) {
                    ReelModel r = posts.get(activeAudioPosition);
                    int[] trim = trimWindowFor(r);
                    if (trim != null) scheduleAudioLoop(trim[0], trim[1]);
                }
            } catch (Exception ignored) {}
        } else {
            // Nothing playing yet (e.g. activity just resumed before the
            // first scroll settled) — figure out what should be playing.
            updateActiveAudioForVisibleItem();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseActiveAudio();
        detachAllCountListeners();
        feedOptimizer.shutdown();
    }

    // ── Live likes/comments/reposts (v284) ──────────────────────────────
    // loadPosts() above is still a one-time snapshot (correct for the
    // initial batch fetch), but until now nothing kept those three counts
    // fresh afterwards — if someone else liked/commented/reposted while
    // this screen was open, the numbers only updated on next reopen.
    // Fix: attach a real addValueEventListener per currently-bound row
    // (same live-count pattern already used for other counters elsewhere
    // in the app), scoped to exactly what's on screen — attach in
    // onBindViewHolder, detach in onViewRecycled so scrolled-off rows
    // don't leave listeners running, plus a full sweep in onDestroy as a
    // safety net for any rows that never got an onViewRecycled call.
    private final Map<String, ValueEventListener> activeCountListeners = new HashMap<>();

    private void attachCountListener(ReelModel r, PostsAdapter.Holder h) {
        if (r == null || r.reelId == null) return;
        detachCountListener(h); // in case this Holder is being rebound to a new row

        // v287: under thermal HOT, skip the standing listener and settle
        // for a one-time read — a persistent addValueEventListener per
        // visible row is exactly the kind of background work to shed under
        // thermal pressure (same principle Home's prefetch manager applies).
        if (!feedOptimizer.canAttachLiveCountListener()) {
            FirebaseUtils.getReelsRef().child(r.reelId).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) return;
                    Long likes = snap.child("likesCount").getValue(Long.class);
                    Long comments = snap.child("commentsCount").getValue(Long.class);
                    Long reposts = snap.child("repostCount").getValue(Long.class);
                    if (likes != null) r.likesCount = likes.intValue();
                    if (comments != null) r.commentsCount = comments.intValue();
                    if (reposts != null) r.repostCount = reposts.intValue();
                    if (h.tvLikes != null && r.reelId.equals(h.boundReelId)) {
                        h.tvLikes.setText(cachedLikesStr(h, r.likesCount));
                        h.tvComments.setText(cachedCommentsStr(h, r.commentsCount));
                        if (h.tvReposts != null) h.tvReposts.setText(cachedRepostStr(h, r.repostCount));
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError error) { }
            });
            return;
        }

        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                Long likes = snap.child("likesCount").getValue(Long.class);
                Long comments = snap.child("commentsCount").getValue(Long.class);
                Long reposts = snap.child("repostCount").getValue(Long.class);
                if (likes != null) r.likesCount = likes.intValue();
                if (comments != null) r.commentsCount = comments.intValue();
                if (reposts != null) r.repostCount = reposts.intValue();
                // Holder may have been recycled onto a different row by the
                // time this async callback lands — only touch the views if
                // it's still bound to this reel.
                if (h.tvLikes != null && r.reelId.equals(h.boundReelId)) {
                    h.tvLikes.setText(cachedLikesStr(h, r.likesCount));
                    h.tvComments.setText(cachedCommentsStr(h, r.commentsCount));
                    if (h.tvReposts != null) h.tvReposts.setText(cachedRepostStr(h, r.repostCount));
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        FirebaseUtils.getReelsRef().child(r.reelId).addValueEventListener(l);
        h.countListener = l;
        h.countListenerReelId = r.reelId;
        activeCountListeners.put(r.reelId + "#" + System.identityHashCode(h), l);
    }

    private void detachCountListener(PostsAdapter.Holder h) {
        if (h.countListener != null && h.countListenerReelId != null) {
            FirebaseUtils.getReelsRef().child(h.countListenerReelId).removeEventListener(h.countListener);
            activeCountListeners.remove(h.countListenerReelId + "#" + System.identityHashCode(h));
            h.countListener = null;
            h.countListenerReelId = null;
        }
    }

    private void detachAllCountListeners() {
        for (Map.Entry<String, ValueEventListener> e : activeCountListeners.entrySet()) {
            String reelId = e.getKey().substring(0, e.getKey().indexOf('#'));
            FirebaseUtils.getReelsRef().child(reelId).removeEventListener(e.getValue());
        }
        activeCountListeners.clear();
    }

    /** Callback for a fetched, order-preserved batch. */
    private interface BatchCallback { void onLoaded(ReelModel[] slots); }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /**
     * AvatarSource view over `posts` for PostFeedAvatarBinder.prefetch() —
     * resolves each index to whichever avatar that row would ACTUALLY
     * render: the collab initiator's photo for a collab repost, plain
     * ownerPhoto otherwise (mirrors PostsAdapter#onBindViewHolder's own
     * collab-vs-plain avatar branch). Collab photos have no dedicated
     * avatarVersion field on ReelModel, so they fall back to 0 (still a
     * valid, just non-cache-busted, buildResponsive() URL).
     */
    private PostFeedAvatarBinder.AvatarSource postFeedAvatarSource() {
        return new PostFeedAvatarBinder.AvatarSource() {
            @Override public String photo(int index) {
                ReelModel r = posts.get(index);
                boolean isCollab = r.collabInitiatorUid != null && !r.collabInitiatorUid.isEmpty()
                                 && r.collabColaboratorUid != null && !r.collabColaboratorUid.isEmpty();
                return isCollab ? r.collabInitiatorPhoto : r.ownerPhoto;
            }
            @Override public long avatarVersion(int index) {
                ReelModel r = posts.get(index);
                boolean isCollab = r.collabInitiatorUid != null && !r.collabInitiatorUid.isEmpty()
                                 && r.collabColaboratorUid != null && !r.collabColaboratorUid.isEmpty();
                return isCollab ? 0L : r.avatarVersion;
            }
            @Override public int size() { return posts.size(); }
        };
    }

    /** Solid white when active, translucent white otherwise — same dot
     *  styling as HomeFragment's photo-slideshow indicator, reused here for
     *  PostsFeedActivity's multi-photo carousel. */
    private android.graphics.drawable.GradientDrawable makeCarouselDot(boolean active) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        d.setColor(active ? 0xFFFFFFFF : 0x66FFFFFF);
        return d;
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

    /** Pre-fetch which of these posts the current user already saved, so
     *  btn_post_save renders filled/outline correctly on first bind (same
     *  pattern and reelSaves source as HomeFragment's savedIds). */
    private void loadSavedState() {
        String myUid = FirebaseUtils.getCurrentUid();
        if (myUid == null) return;
        FirebaseUtils.getReelSavesRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot c : snap.getChildren()) savedIds.add(c.getKey());
                if (adapter != null) adapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    /** Same fetch-by-id-list pattern as SingleReelPlayerActivity.loadByReelIds —
     *  order preserved, all reads fired in parallel, results collected before
     *  the batch's callback fires. Used for both the initial (after) window
     *  and the background (before) batch. */
    /** v287: routed through PostFeedUltraOptimizer's batcher instead of
     *  firing one raw, uncoalesced addListenerForSingleValueEvent per id —
     *  see PostFeedNetworkBatcher's class doc for why that mattered. */
    private void loadPosts(List<String> reelIds, BatchCallback callback) {
        feedOptimizer.batchFetchReels(reelIds, callback::onLoaded);
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

        // First-paint audio kick — same idea as HomeFragment's
        // scheduleImmediatePlayCheck(): wait for the scroll above to
        // actually lay out, then start audio for whatever landed on
        // screen, instead of waiting for the user's first manual scroll.
        recyclerView.post(this::updateActiveAudioForVisibleItem);
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
        windowStartOffset = Math.max(0, windowStartOffset - toPrepend.size()); // v285

        // Keep the same row at the same on-screen position — without this,
        // inserting rows above the viewport would push the user's current
        // scroll position down by toPrepend.size() rows on screen.
        if (lm != null) lm.scrollToPositionWithOffset(anchorPos + toPrepend.size(), anchorOffset);
    }

    // ── v285: window trim / reload (mirrors HomeFragment's v280 fix) ────
    // posts only ever grew before this (prependBeforeBatch adds, nothing
    // ever removed) — on a 100+ photo profile every ReelModel + its live
    // Firebase count-listener (v284) stayed resident for the whole scroll
    // session. Now the list is kept to a window around the visible
    // position; rows scrolled well past get trimmed (and their v284 count
    // listeners detached), and re-fetched by id from originalReelIds if
    // the user scrolls back to them.
    private void maybeTrimOrReloadPostWindow() {
        if (trimInFlight || adapter == null || recyclerView == null) return;
        if (posts.size() < POST_WINDOW_BEHIND + POST_WINDOW_AHEAD + POST_TRIM_SLACK) return;
        LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
        if (lm == null) return;
        int first = lm.findFirstVisibleItemPosition();
        int last  = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;

        // Trim front — rows scrolled well past (already viewed, above screen).
        if (first > POST_WINDOW_BEHIND + POST_TRIM_SLACK) {
            trimPostFront(first - POST_WINDOW_BEHIND);
            return; // re-measure next scroll tick rather than trim both ends at once
        }
        // Trim back — rows not yet reached, far below the visible window.
        int aheadCount = posts.size() - 1 - last;
        if (aheadCount > POST_WINDOW_AHEAD + POST_TRIM_SLACK) {
            trimPostBack(aheadCount - POST_WINDOW_AHEAD);
            return;
        }

        // Reload — user scrolled back up near the trimmed front edge.
        // v287: deferred while flinging — HomeFeedScrollStateManager's
        // settle callback (wired to this same method) re-checks once the
        // scroll stops, so a fast fling right past the trimmed edge no
        // longer fires a Firebase read mid-fling for nothing.
        if (windowStartOffset > 0 && first < POST_TRIM_SLACK) {
            if (feedOptimizer.isFlinging()) return;
            reloadPostsBefore();
            return;
        }
        // Reload — user scrolled back down toward the trimmed tail edge.
        int windowEndOffset = windowStartOffset + posts.size(); // exclusive
        if (windowEndOffset < originalReelIds.size() && (posts.size() - 1 - last) < POST_TRIM_SLACK) {
            if (feedOptimizer.isFlinging()) return;
            reloadPostsAfter();
        }
    }

    private void trimPostFront(int count) {
        count = Math.min(count, posts.size() - POST_WINDOW_BEHIND);
        if (count <= 0) return;
        for (int i = 0; i < count; i++) {
            PostsAdapter.Holder h = holderBoundTo(posts.get(i));
            if (h != null) detachCountListener(h);
        }
        posts.subList(0, count).clear();
        windowStartOffset += count;
        adapter.notifyItemRangeRemoved(0, count);
    }

    private void trimPostBack(int count) {
        int start = posts.size() - count;
        if (start < 0 || count <= 0) return;
        for (int i = start; i < posts.size(); i++) {
            PostsAdapter.Holder h = holderBoundTo(posts.get(i));
            if (h != null) detachCountListener(h);
        }
        posts.subList(start, posts.size()).clear();
        adapter.notifyItemRangeRemoved(start, count);
    }

    /** Finds the currently-bound ViewHolder for a given post, if any is
     *  attached right now — used so a row being trimmed the instant it's
     *  still attached gets its v284 live listener detached cleanly rather
     *  than relying solely on the eventual onViewRecycled call. */
    @Nullable
    private PostsAdapter.Holder holderBoundTo(ReelModel r) {
        if (r == null || r.reelId == null || recyclerView == null) return null;
        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(posts.indexOf(r));
        if (vh instanceof PostsAdapter.Holder) {
            PostsAdapter.Holder h = (PostsAdapter.Holder) vh;
            if (r.reelId.equals(h.boundReelId)) return h;
        }
        return null;
    }

    private void reloadPostsBefore() {
        int batch = Math.min(POST_WINDOW_BEHIND, windowStartOffset);
        if (batch <= 0) return;
        int from = windowStartOffset - batch;
        List<String> ids = originalReelIds.subList(from, windowStartOffset);
        trimInFlight = true;
        loadPosts(ids, slots -> {
            trimInFlight = false;
            prependBeforeBatch(slots); // already decrements windowStartOffset by however many actually loaded
        });
    }

    private void reloadPostsAfter() {
        int windowEndOffset = windowStartOffset + posts.size();
        int batch = Math.min(POST_WINDOW_AHEAD, originalReelIds.size() - windowEndOffset);
        if (batch <= 0) return;
        List<String> ids = originalReelIds.subList(windowEndOffset, windowEndOffset + batch);
        trimInFlight = true;
        loadPosts(ids, slots -> {
            trimInFlight = false;
            if (isFinishing() || isDestroyed()) return;
            int insertAt = posts.size();
            int added = 0;
            for (ReelModel r : slots) {
                if (r != null) { posts.add(r); added++; }
            }
            if (added > 0) adapter.notifyItemRangeInserted(insertAt, added);
        });
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

    // ── Instagram-style background audio engine ─────────────────────────
    //
    // One shared MediaPlayer, attached to whichever post is most visible on
    // screen — swapped as the user scrolls, same "most visible card" idea
    // HomeFragment uses for its shared ExoPlayer, and the same trim-window
    // loop behaviour ReelPlayerController.startPhotoAudio() uses for photo
    // slideshow reels in the immersive player.

    /** Walks the currently laid-out rows, finds whichever has the most
     *  on-screen pixel area, and switches the active audio track to it if
     *  it changed since the last check. */
    private void updateActiveAudioForVisibleItem() {
        if (isFinishing() || isDestroyed() || recyclerView == null || posts.isEmpty()) return;
        RecyclerView.LayoutManager rawLm = recyclerView.getLayoutManager();
        if (!(rawLm instanceof LinearLayoutManager)) return;
        LinearLayoutManager lm = (LinearLayoutManager) rawLm;

        int first = lm.findFirstVisibleItemPosition();
        int last  = lm.findLastVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int[] loc = new int[2];
        int bestPos = -1, bestVisiblePx = 0;
        for (int p = first; p <= last; p++) {
            View child = lm.findViewByPosition(p);
            if (child == null || child.getHeight() == 0) continue;
            child.getLocationOnScreen(loc);
            int top = loc[1], bottom = top + child.getHeight();
            int visible = Math.max(0, Math.min(bottom, screenH) - Math.max(top, 0));
            if (visible > bestVisiblePx) { bestVisiblePx = visible; bestPos = p; }
        }
        if (bestPos >= 0 && bestPos != activeAudioPosition) switchActiveAudio(bestPos);
    }

    /** Stops whatever was playing, then starts the track for the post at
     *  {@code pos} (silently no-ops if that post has no attached music). */
    private void switchActiveAudio(int pos) {
        releaseActiveAudio();
        activeAudioPosition = pos;
        if (pos < 0 || pos >= posts.size()) return;
        ReelModel r = posts.get(pos);
        if (r == null || r.musicUrl == null || r.musicUrl.isEmpty()) return;
        startAudioFor(r);
    }

    /** startMs/endMs trim window for a post's music, or null if untrimmed. */
    @Nullable
    private int[] trimWindowFor(ReelModel r) {
        if (r == null) return null;
        int startMs = r.musicStartMs > 0 ? r.musicStartMs : (r.musicStartSec > 0 ? r.musicStartSec * 1000 : 0);
        int endMs   = r.musicEndMs > 0 ? r.musicEndMs : 0;
        return (endMs > startMs && endMs > 0) ? new int[]{ startMs, endMs } : null;
    }

    private void startAudioFor(ReelModel r) {
        final String reelId = r.reelId;
        activeAudioReelId = reelId;
        final int[] trim = trimWindowFor(r);
        final int startMs = trim != null ? trim[0]
            : (r.musicStartMs > 0 ? r.musicStartMs : (r.musicStartSec > 0 ? r.musicStartSec * 1000 : 0));

        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(r.musicUrl);
            mp.setOnPreparedListener(prepared -> {
                // If the user scrolled again before this finished preparing,
                // this instance has already been superseded/released —
                // don't let a late callback start audio for the wrong post.
                if (prepared != activeAudioPlayer || reelId == null || !reelId.equals(activeAudioReelId)) {
                    try { prepared.release(); } catch (Exception ignored) {}
                    return;
                }
                try {
                    prepared.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f);
                    if (startMs > 0) prepared.seekTo(startMs);
                    prepared.setLooping(trim == null); // loop whole track when no trim window
                    prepared.start();
                    audioStarted = true;
                    if (trim != null) scheduleAudioLoop(trim[0], trim[1]);
                } catch (Exception ignored) {}
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (m == activeAudioPlayer) releaseActiveAudio();
                return true;
            });
            mp.prepareAsync();
            activeAudioPlayer = mp;
        } catch (Exception e) {
            activeAudioPlayer = null;
        }
    }

    private void scheduleAudioLoop(int startMs, int endMs) {
        cancelAudioLoop();
        int clipDurationMs = endMs - startMs;
        if (clipDurationMs <= 0) return;
        audioLoopRunnable = () -> {
            if (activeAudioPlayer == null) return;
            try {
                activeAudioPlayer.seekTo(startMs);
                if (!activeAudioPlayer.isPlaying()) activeAudioPlayer.start();
                scheduleAudioLoop(startMs, endMs); // re-arm for the next loop
            } catch (Exception ignored) {
                releaseActiveAudio();
            }
        };
        mainHandler.postDelayed(audioLoopRunnable, clipDurationMs);
    }

    private void cancelAudioLoop() {
        if (audioLoopRunnable != null) {
            mainHandler.removeCallbacks(audioLoopRunnable);
            audioLoopRunnable = null;
        }
    }

    private void releaseActiveAudio() {
        cancelAudioLoop();
        if (activeAudioPlayer != null) {
            try { if (activeAudioPlayer.isPlaying()) activeAudioPlayer.stop(); } catch (Exception ignored) {}
            try { activeAudioPlayer.release(); } catch (Exception ignored) {}
            activeAudioPlayer = null;
        }
        activeAudioReelId = null;
        audioStarted = false;
    }

    /** Mute toggle — one shared switch for the whole feed (matches
     *  HomeFragment's single-mute-state-across-cards behaviour), so tapping
     *  the icon on any visible card mutes/unmutes the currently playing
     *  track and updates every bound card's icon without a full rebind
     *  (a rebind would re-trigger Glide loads on every visible row). */
    private void toggleMute() {
        isMuted = !isMuted;
        if (activeAudioPlayer != null) {
            try { activeAudioPlayer.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f); }
            catch (Exception ignored) {}
        }
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            RecyclerView.ViewHolder vh = recyclerView.getChildViewHolder(child);
            if (vh instanceof PostsAdapter.Holder) {
                ImageButton btn = ((PostsAdapter.Holder) vh).btnMute;
                if (btn != null) btn.setImageResource(isMuted
                    ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
            }
        }
    }

    /**
     * Opens SoundDetailActivity for a post's attached music — same
     * destination/extras Home feed's audio label and the immersive Reels
     * player's audio pill use (ReelDuetController.openSoundDetail()).
     */
    private void openSoundDetail(ReelModel r) {
        if (isFinishing() || isDestroyed() || r == null) return;

        // ⚠️ r.musicId is written by a BACKGROUND job (audio extraction +
        // sound registration) that runs AFTER the reel is already live, so
        // it can still be empty here even though the sound entity already
        // exists (or is about to) on Firebase. Fall back to the same
        // deterministic "orig_{reelId}" ID ReelUploadActivity registers
        // original audio under.
        String soundId = r.musicId;
        if ((soundId == null || soundId.isEmpty()) && r.reelId != null && !r.reelId.isEmpty()) {
            soundId = "orig_" + r.reelId;
        }

        Intent i = new Intent(this, com.callx.app.music.SoundDetailActivity.class);
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_ID,
            soundId != null ? soundId : "");
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_TITLE,
            r.musicName != null && !r.musicName.isEmpty() ? r.musicName : "Original Audio");
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_URL,
            r.musicUrl != null ? r.musicUrl : "");
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_COVER_URL,
            r.musicCoverUrl != null ? r.musicCoverUrl : "");
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_ARTIST,
            r.musicArtist != null && !r.musicArtist.isEmpty()
                ? r.musicArtist
                : (r.ownerName != null ? r.ownerName : ""));
        // Only pass creator uid when this post IS the sound's own source —
        // otherwise SoundDetailActivity resolves creatorUid from the sound
        // node itself.
        boolean isOwnSoundSource = r.reelId != null
            && soundId != null && soundId.equals("orig_" + r.reelId);
        if (isOwnSoundSource && r.uid != null && !r.uid.isEmpty()) {
            i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_CREATOR_UID, r.uid);
        }
        if (r.originalAudioUrl != null && !r.originalAudioUrl.isEmpty()) {
            i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_ORIGINAL_AUDIO_URL, r.originalAudioUrl);
        } else {
            i.putExtra("reel_video_url", r.videoUrl != null ? r.videoUrl : "");
        }
        startActivity(i);
    }

    /** Removes a post row from the list (used by "Not interested" / "Block"). */
    private void removePost(ReelModel r) {
        int idx = posts.indexOf(r);
        if (idx < 0) return;
        posts.remove(idx);
        adapter.notifyItemRemoved(idx);
    }

    private void saveReelOffline(ReelModel r) {
        if (isFinishing() || isDestroyed() || r == null || r.reelId == null) return;
        if (offlineManager == null) offlineManager = ReelOfflineManager.get(this);
        if (offlineManager.isAvailableOffline(r.reelId)) {
            Toast.makeText(this, "Already saved for offline viewing", Toast.LENGTH_SHORT).show();
            return;
        }
        offlineManager.downloadForOffline(r);
        Toast.makeText(this, "Saving reel for offline viewing…", Toast.LENGTH_SHORT).show();
    }

    /** Opens the immersive single-reel player for this one post — same
     *  destination HomeFragment's "Open original" jumps to. */
    private void openOriginal(ReelModel r) {
        if (r == null || r.reelId == null) return;
        Intent i = new Intent(this, SingleReelPlayerActivity.class);
        ArrayList<String> ids = new ArrayList<>();
        ids.add(r.reelId);
        i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
        i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, 0);
        i.putExtra(SingleReelPlayerActivity.EXTRA_TITLE,
            r.ownerName != null ? r.ownerName + "'s Reel" : "Reel");
        startActivity(i);
    }

    /** ⋮ "More options" popup — same menu/actions as HomeFragment's per-card
     *  more button (Not interested, Report, Copy link, Mute/Block owner,
     *  Open original, Save for offline), adapted to this single-user list
     *  (removeFeedRowByReelId's FeedRow bookkeeping isn't needed here —
     *  posts is a flat list, so removePost() is a direct index remove). */
    private void showMoreMenu(ReelModel r, View anchor) {
        if (isFinishing() || isDestroyed() || r == null || r.reelId == null || anchor == null) return;
        final String reelId   = r.reelId;
        final String ownerUid = r.uid;
        final String myUid    = FirebaseUtils.getCurrentUid();

        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Not interested");
        popup.getMenu().add(0, 2, 0, "Report");
        popup.getMenu().add(0, 3, 0, "Copy link");
        if (myUid != null && ownerUid != null && !myUid.equals(ownerUid)) {
            popup.getMenu().add(0, 4, 0, "Mute @" + (r.ownerName != null ? r.ownerName : "user"));
            popup.getMenu().add(0, 5, 0, "Block");
        }
        popup.getMenu().add(0, 6, 0, "Open original");
        popup.getMenu().add(0, 7, 0, "Save for offline");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: // Not interested
                    removePost(r);
                    if (myUid != null) {
                        FirebaseUtils.db().getReference("userNotInterested")
                            .child(myUid).child(reelId).setValue(true);
                    }
                    return true;
                case 2: // Report
                    if (myUid == null) return true;
                    String[] reportReasons = {"Spam", "Inappropriate content",
                        "Harassment", "Misinformation", "Kuch aur"};
                    AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(this)
                        .setTitle("Report this reel")
                        .setItems(reportReasons, (d, which) -> {
                            String reportKey = FirebaseUtils.db()
                                .getReference("reelReports").child(reelId).push().getKey();
                            if (reportKey != null) {
                                Map<String, Object> report = new HashMap<>();
                                report.put("reporterUid", myUid);
                                report.put("reelId",      reelId);
                                report.put("ownerUid",    ownerUid != null ? ownerUid : "");
                                report.put("reason",      reportReasons[which]);
                                report.put("timestamp",   System.currentTimeMillis());
                                FirebaseUtils.db().getReference("reelReports")
                                    .child(reelId).child(reportKey).setValue(report);
                            }
                            Toast.makeText(this, "Report submitted — thanks!", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null).create());
                    return true;
                case 3: // Copy link
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        String link = "https://callx.app/reel/" + reelId;
                        clipboard.setPrimaryClip(ClipData.newPlainText("Reel link", link));
                        Toast.makeText(this, "Link copied!", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case 4: // Mute owner
                    if (myUid != null && ownerUid != null) {
                        FirebaseUtils.db().getReference("muted")
                            .child(myUid).child(ownerUid).setValue(true);
                        Toast.makeText(this, "Muted @" + r.ownerName, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                case 5: // Block
                    if (myUid != null && ownerUid != null) {
                        AlertDialogStyler.showRounded(new android.app.AlertDialog.Builder(this)
                            .setTitle("Block @" + r.ownerName + "?")
                            .setMessage("They won't be able to find your profile or reels.")
                            .setPositiveButton("Block", (d, w) -> {
                                FirebaseUtils.getBlocksRef(myUid).child(ownerUid).setValue(true);
                                removePost(r);
                                Toast.makeText(this, "Blocked", Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Cancel", null).create());
                    }
                    return true;
                case 6: // Open original
                    openOriginal(r);
                    return true;
                case 7: // Save for offline
                    saveReelOffline(r);
                    return true;
            }
            return false;
        });
        popup.show();
    }

    // ── Adapter — reuses item_home_feed_post.xml (image-only bind) ─────────

    private class PostsAdapter extends RecyclerView.Adapter<PostsAdapter.Holder>
            implements ListPreloader.PreloadModelProvider<String> {

        // ── Carousel perf: every row's inner ViewPager2 hosts its OWN
        // RecyclerView internally. Left default, each one builds/tears down
        // its own recycled-view pool as rows scroll on/off screen — on a
        // feed with many multi-photo posts that means repeated ImageView
        // inflation instead of reuse. Sharing ONE pool across every pager
        // (they're all the same trivial ImageView viewType) lets a page
        // view inflated for post A's carousel be recycled straight into
        // post B's, the same trick Instagram's own feed relies on.
        private final RecyclerView.RecycledViewPool photoPagerSharedPool = new RecyclerView.RecycledViewPool();

        // Capped decode size for carousel pages, reused across every
        // PhotoPagerAdapter instance instead of allocating a fresh
        // RequestOptions per page bind. RGB_565 halves per-pixel memory
        // vs the default ARGB_8888 — imperceptible for photo feed
        // thumbnails, and it matters here because a multi-photo post can
        // have several of these decoded at once (current + offscreen
        // preload neighbor).
        private final RequestOptions pagerPhotoOpts = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .override(screenWidthPxOrFallback(), screenWidthPxOrFallback())
            .format(DecodeFormat.PREFER_RGB_565)
            .centerCrop();

        // PERF: same reasoning as pagerPhotoOpts above — the single-photo
        // thumb (h.ivThumb) was building a brand-new RequestOptions object
        // on EVERY onBindViewHolder call. Screen width doesn't change
        // mid-session, so this is built once per adapter instance and
        // reused for every row/rebind, same as HomeFragment's static
        // FEED_IMAGE_OPTS.
        private final RequestOptions thumbOpts = new RequestOptions()
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .override(screenWidthPxOrFallback(), screenWidthPxOrFallback())
            .centerCrop();

        // PERF: owner-avatar circleCrop() — shorthand for
        // .transform(new CircleCrop()), which was allocating a fresh
        // CircleCrop instance on every single bind (every row has an
        // avatar). Same fix as thumbOpts/pagerPhotoOpts: build the
        // RequestOptions once and .apply() the cached instance instead.
        // PERF: avatarOpts / collabAvatarOpts (cached per-adapter
        // RequestOptions instances) were replaced by PostFeedAvatarBinder,
        // which builds its own tier-sized, L2/L3-aware options per bind —
        // removed here to avoid two competing avatar pipelines existing
        // side by side in this class.

        // PERF: audio-cover tile (btnAudioCover) — was building a brand
        // new RequestOptions + MultiTransformation(CenterCrop,
        // RoundedCorners) object graph on EVERY bind of a row that has
        // attached music (i.e. most rows in an audio-heavy feed), even
        // though the target size and corner radius are fixed 28dp
        // constants that never vary per-post. Built ONCE per adapter
        // instance instead, same as pagerPhotoOpts/thumbOpts above — the
        // single biggest remaining per-bind allocation on this screen.
        private final int audioCoverSizePx =
            com.callx.app.utils.AvatarUrlBuilder.tierPx(
                PostsFeedActivity.this, com.callx.app.utils.AvatarSizeTier.TINY); // 28dp view → shared TINY tier
        private final RequestOptions audioCoverOpts = new RequestOptions()
            .transform(new com.bumptech.glide.load.MultiTransformation<>(
                new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                new com.bumptech.glide.load.resource.bitmap.RoundedCorners(
                    com.callx.app.utils.AvatarUrlBuilder.dpToPx(PostsFeedActivity.this, 4))))
            .override(audioCoverSizePx, audioCoverSizePx)
            .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .placeholder(R.drawable.ic_audio);

        PostsAdapter() {
            // Stable ids (reelId hash) → RecyclerView can tell "same row,
            // different position" apart from "new row" during the DiffUtil
            // dispatch above, instead of tearing down/recreating views.
            setHasStableIds(true);
            photoPagerSharedPool.setMaxRecycledViews(0, 8);
        }

        /** Persistent per-row carousel adapter — replaces the old approach
         *  of building a brand-new anonymous RecyclerView.Adapter on every
         *  single onBindViewHolder call. That forced ViewPager2's internal
         *  RecyclerView to swap adapters on every rebind (scroll past a
         *  row and back), which drops its whole view-recycling state and
         *  re-inflates every page from scratch — real jank on a feed you
         *  swipe AND scroll. This one lives for the Holder's lifetime and
         *  just gets handed a new photo list per bind. */
        private class PhotoPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
            private List<String> urls = Collections.emptyList();

            void setUrls(@NonNull List<String> newUrls) {
                // Same row rebound with the same post (e.g. a live-count
                // update elsewhere triggered a rebind) — nothing to redo.
                if (urls == newUrls) return;
                urls = newUrls;
                notifyDataSetChanged();
            }

            @NonNull @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
                ImageView iv = new ImageView(parent.getContext());
                iv.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                return new RecyclerView.ViewHolder(iv) {};
            }

            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int pos) {
                Glide.with(vh.itemView.getContext())
                    .load(urls.get(pos))
                    .apply(pagerPhotoOpts)
                    .into((ImageView) vh.itemView);
            }

            @Override public void onViewRecycled(@NonNull RecyclerView.ViewHolder vh) {
                // Same reasoning as the outer adapter's onViewRecycled —
                // release the decoded bitmap the instant a page scrolls
                // off, don't wait for the next bind to clear it.
                Glide.with(vh.itemView.getContext()).clear((ImageView) vh.itemView);
            }

            @Override public int getItemCount() { return urls.size(); }
        }

        @Override public long getItemId(int position) {
            String id = posts.get(position).reelId;
            return id != null ? id.hashCode() : RecyclerView.NO_ID;
        }

        @NonNull @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Instagram classic-feed layout: header row sits ABOVE the image
            // (not overlaid on it) — see item_post_feed_photo.xml for why
            // this is a dedicated layout rather than reusing
            // item_home_feed_post.xml (which HomeFragment's video Reels
            // feed still needs the overlaid-header version of).
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_post_feed_photo, parent, false);
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
            // thumbOpts is now the adapter-level cached instance above
            // instead of a fresh RequestOptions allocated on every bind.
            //
            // PERF (Instagram-level pass, URL-skip): a rebind of this holder
            // onto the SAME reel (DiffUtil landing on this row because
            // likesCount/commentsCount/caption changed — see
            // PostDiffCallback.areContentsTheSame, which never looks at the
            // image URL at all) used to still run the full Glide
            // load()/apply()/into() chain even though the exact same URL was
            // already showing in ivThumb. Glide's own memory cache absorbs
            // the decode cost, but building/dispatching the request
            // (Engine key computation, Target attach/detach, the placeholder
            // flash while it resolves) is real per-bind work that a same-URL
            // rebind never needed to redo. Skip it when the URL hasn't
            // actually changed since the last load onto this holder.
            String thumbUrl = r.effectiveThumbUrl();
            if (!java.util.Objects.equals(thumbUrl, h.lastThumbUrl)) {
                h.lastThumbUrl = thumbUrl;
                Glide.with(h.ivThumb.getContext())
                    .load(thumbUrl)
                    .apply(thumbOpts)
                    .into(h.ivThumb);
            }

            // ── Multi-photo carousel — Instagram-level approach: a real
            // swipeable ViewPager2 (was a static first-photo-only image +
            // static "1/N" badge before), a bottom dot-indicator strip
            // (screenshot-2 reference), and a "position/total" chip
            // top-right (screenshot-3 reference) that tracks the live
            // swipe position instead of always reading "1/N".
            final int photoCount = r.photoUrls != null ? r.photoUrls.size() : 0;
            if (photoCount > 1 && h.photoPager != null) {
                h.ivThumb.setVisibility(View.GONE);
                h.photoPager.setVisibility(View.VISIBLE);
                final List<String> photoList = r.photoUrls;
                h.photoPagerBoundReel = r;

                // Hand the persistent adapter this row's photo list instead
                // of swapping the whole adapter object out — see
                // PhotoPagerAdapter's doc for why that matters.
                h.photoPagerAdapter.setUrls(photoList);
                // Recycled Holder may carry a stale scroll position from
                // whatever row it previously rendered — always reset.
                h.photoPager.setCurrentItem(0, false);

                // Dot indicator — ★ Instagram-level PERF: this used to
                // removeAllViews() + allocate a brand-new View +
                // LinearLayout.LayoutParams + GradientDrawable per dot on
                // EVERY bind, even when the exact same reel rebinds onto
                // this same holder. Now the View[]/GradientDrawable[]
                // arrays are built ONCE per holder and reused; a rebind
                // only rebuilds them if the photo COUNT actually changed
                // (a different post landed on this row), and updating the
                // active dot is just mutating each drawable's color
                // in-place instead of swapping in a new Drawable object.
                h.dotsContainer.setVisibility(View.VISIBLE);
                if (h.dotCount != photoCount) {
                    h.dotsContainer.removeAllViews();
                    h.dotViews = new View[photoCount];
                    h.dotDrawables = new android.graphics.drawable.GradientDrawable[photoCount];
                    int dotSz = dp(6), dotMargin = dp(3);
                    for (int di = 0; di < photoCount; di++) {
                        View dot = new View(h.dotsContainer.getContext());
                        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dotSz, dotSz);
                        dlp.setMargins(dotMargin, 0, dotMargin, 0);
                        dot.setLayoutParams(dlp);
                        android.graphics.drawable.GradientDrawable gd = makeCarouselDot(false);
                        dot.setBackground(gd);
                        h.dotsContainer.addView(dot);
                        h.dotViews[di]      = dot;
                        h.dotDrawables[di]  = gd;
                    }
                    h.dotCount = photoCount;
                }
                // Always reset dot colors on bind (pager below is reset to
                // page 0), even when the array itself was reused as-is.
                for (int di = 0; di < photoCount; di++) {
                    h.dotDrawables[di].setColor(di == 0 ? 0xFFFFFFFF : 0x66FFFFFF);
                }

                // PERF: "1/" + photoCount used to re-concatenate a fresh
                // String on EVERY bind, even a same-post rebind onto this
                // holder (dot rebuild above already skips that case via
                // h.dotCount) — cache it the same way, keyed off the count.
                if (h.lastCarouselCount != photoCount) {
                    h.lastCarouselCount = photoCount;
                    h.lastCarouselStr = "1/" + photoCount;
                }
                h.tvCarouselIndex.setText(h.lastCarouselStr);
                h.tvCarouselIndex.setVisibility(View.VISIBLE);
                // PERF: the OnPageChangeCallback used to be a brand-new
                // anonymous class allocated on EVERY bind, plus an
                // unregister-old/register-new pair of calls each time —
                // pure churn even when the same reel rebinds onto this
                // holder. It's now built ONCE per holder in
                // setupPhotoPagerOnce() and stays registered permanently;
                // it reads h.boundPhotoCount / h.dotDrawables live instead
                // of capturing photoCount/dotDrawablesRef via closure, so
                // all a bind needs to do is update that field.
                h.boundPhotoCount = photoCount;
            } else {
                if (h.photoPager != null) {
                    h.photoPager.setVisibility(View.GONE);
                }
                h.photoPagerBoundReel = null;
                h.boundPhotoCount = 0;
                // Just hide — don't tear the cached dot views down. If a
                // later post on this same holder has the same photo count,
                // the cache above is reused as-is instead of rebuilding.
                if (h.dotsContainer != null) {
                    h.dotsContainer.setVisibility(View.GONE);
                }
                h.ivThumb.setVisibility(View.VISIBLE);
                if (h.tvCarouselIndex != null) h.tvCarouselIndex.setVisibility(View.GONE);
            }

            h.tvOwner.setText(r.ownerName != null ? r.ownerName : "");
            // PERF (URL-skip): skip the Glide chain entirely when this
            // holder is already showing the same avatar URL (e.g. a
            // likesCount-only rebind of the same post). The collab branch
            // below may still overwrite ivAvatar with the initiator's
            // photo for a collab post; it keeps h.lastAvatarUrl in sync so
            // this field always reflects whatever is ACTUALLY loaded into
            // ivAvatar right now, not just the non-collab owner photo.
            // FIX (deep avatar pipeline): flat Glide.load(r.ownerPhoto) +
            // fixed circleCrop-only RequestOptions replaced with
            // PostFeedAvatarBinder.bind() — the SAME shared pipeline the
            // reel owner avatar, comment sheet, Home Stories tray, and
            // Follow lists already use: density-aware tier sizing +
            // WebP/AVIF CDN param + ?v=avatarVersion cache-bust, and L2/L3
            // bitmap reuse (survives TRIM_MEMORY_MODERATE) instead of only
            // Glide's own disk cache. The URL-skip dedupe above (lastAvatarUrl)
            // is unchanged — it still avoids rebuilding the request at all
            // on a same-post rebind.
            if (!java.util.Objects.equals(r.ownerPhoto, h.lastAvatarUrl)) {
                h.lastAvatarUrl = r.ownerPhoto;
                PostFeedAvatarBinder.bind(h.ivAvatar.getContext(), h.ivAvatar, r.ownerPhoto, r.avatarVersion,
                        com.callx.app.core.R.drawable.ic_person, /* circleCrop= */ true);
            }

            // Avatar tap / owner-name tap → open the post owner's profile
            // (or the collab initiator's, for a collab repost). The click
            // listener itself is registered ONCE per holder in
            // setupClickListenersOnce() (constructor) and reads whichever
            // reel is currently bound off h.boundReel — this block just
            // needs to keep that field pointed at the right reel, which
            // happens once below (h.boundReel = r).

            // ── Story ring around the avatar — same StatusCacheManager-driven
            // behavior as HomeFragment's home feed (see addFeedPostCard()):
            // gradient ring while the owner has an unseen status, flat gray
            // ring once seen, hidden entirely with no active status.
            if (h.ivStoryRing != null && r.uid != null) {
                com.callx.app.cache.StatusCacheManager scm =
                        com.callx.app.cache.StatusCacheManager.getInstance(h.ivStoryRing.getContext());
                boolean hasUnseen = scm.hasUnseen(r.uid);
                boolean hasAny    = scm.hasStatus(r.uid);
                if (hasUnseen) {
                    h.ivStoryRing.setImageDrawable(null);
                    h.ivStoryRing.setBackground(
                            com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(2f,
                                    h.ivStoryRing.getResources().getDisplayMetrics().density));
                    h.ivStoryRing.setVisibility(View.VISIBLE);
                } else if (hasAny) {
                    h.ivStoryRing.setBackground(null);
                    h.ivStoryRing.setImageResource(com.callx.app.core.R.drawable.circle_status_seen);
                    h.ivStoryRing.setVisibility(View.VISIBLE);
                } else {
                    h.ivStoryRing.setVisibility(View.GONE);
                }
            }

            // ── Collab / dual-author header — same pattern as HomeFragment's
            // home feed: when this post is a collab repost (both an
            // initiator and an accepted collaborator on record), show
            // "Initiator ∧ Collaborator" as the owner label and overlap a
            // second circular avatar for the collaborator, instead of the
            // normal single-owner row.
            boolean isCollab = r.collabInitiatorUid != null && !r.collabInitiatorUid.isEmpty()
                             && r.collabColaboratorUid != null && !r.collabColaboratorUid.isEmpty();
            if (isCollab && h.collabAvatarContainer instanceof LinearLayout) {
                // PERF: this concat used to run fresh on EVERY bind, even a
                // same-post rebind (live-count tick, like-tap
                // notifyItemChanged) — cache per-holder, same
                // lastOwnerNameSrc/lastOwnerLabel pattern as HomeFragment,
                // and only rebuild when either name actually changed.
                String initName = r.collabInitiatorName;
                String collabName = r.collabCollaboratorName;
                if (!java.util.Objects.equals(initName, h.lastCollabInitiatorName)
                        || !java.util.Objects.equals(collabName, h.lastCollabCollaboratorName)) {
                    h.lastCollabInitiatorName = initName;
                    h.lastCollabCollaboratorName = collabName;
                    h.lastCollabLabel = (initName != null ? initName : "User")
                        + " \u2227 " + (collabName != null ? collabName : "User");
                }
                h.tvOwner.setText(h.lastCollabLabel);

                LinearLayout collabRow = (LinearLayout) h.collabAvatarContainer;
                collabRow.setVisibility(View.VISIBLE);
                CircleImageView av2 = collabRow.findViewWithTag("collab_av2");
                if (av2 == null) {
                    av2 = new CircleImageView(collabRow.getContext());
                    av2.setTag("collab_av2");
                    float density = collabRow.getResources().getDisplayMetrics().density;
                    int avSize = (int) (32 * density);
                    LinearLayout.LayoutParams av2Lp = new LinearLayout.LayoutParams(avSize, avSize);
                    av2Lp.setMarginStart((int) (-10 * density));
                    av2.setLayoutParams(av2Lp);
                    av2.setBorderColor(0xFF111111);
                    av2.setBorderWidth(2);
                    collabRow.addView(av2);
                }
                // PERF (URL-skip): same as the owner-avatar/thumb loads above
                // — a same-post rebind (live-count tick etc.) shouldn't
                // re-run Glide for either collab avatar when the URL it
                // already has loaded hasn't changed.
                // FIX (deep avatar pipeline): same PostFeedAvatarBinder
                // upgrade as the plain owner avatar above — collab photos
                // have no dedicated avatarVersion field on ReelModel, so
                // this passes 0 (still a valid, just non-cache-busted, URL).
                // av2 is a real CircleImageView (already clips to a circle
                // at draw time), so circleCrop=false here.
                if (r.collabCollaboratorPhoto != null && !r.collabCollaboratorPhoto.isEmpty()) {
                    if (!java.util.Objects.equals(r.collabCollaboratorPhoto, h.lastCollabAv2Url)) {
                        h.lastCollabAv2Url = r.collabCollaboratorPhoto;
                        PostFeedAvatarBinder.bind(av2.getContext(), av2, r.collabCollaboratorPhoto, 0L,
                                com.callx.app.core.R.drawable.ic_person, /* circleCrop= */ false);
                    }
                }
                // Main avatar shows the initiator's photo for a collab post.
                if (r.collabInitiatorPhoto != null && !r.collabInitiatorPhoto.isEmpty()) {
                    if (!java.util.Objects.equals(r.collabInitiatorPhoto, h.lastAvatarUrl)) {
                        h.lastAvatarUrl = r.collabInitiatorPhoto;
                        PostFeedAvatarBinder.bind(h.ivAvatar.getContext(), h.ivAvatar, r.collabInitiatorPhoto, 0L,
                                com.callx.app.core.R.drawable.ic_person, /* circleCrop= */ true);
                    }
                }
                // Collab click → open initiator's profile: handled by the
                // once-registered ivAvatar/tvOwner listeners in
                // setupClickListenersOnce(), which already branch on
                // whether the currently-bound reel is a collab post.
            } else {
                if (h.collabAvatarContainer != null) h.collabAvatarContainer.setVisibility(View.GONE);
                // Name tap / avatar tap: also handled by the once-registered
                // listeners in setupClickListenersOnce().
            }

            h.tvCaption.setText(r.caption != null ? r.caption : "");
            h.tvCaption.setVisibility(r.caption != null && !r.caption.isEmpty() ? View.VISIBLE : View.GONE);

            if (h.tvTime != null) {
                // PERF: formatAgo() used to recompute + re-allocate its
                // result String on EVERY bind, including a same-second
                // rebind of the exact same reel onto this holder (like-tap
                // notifyItemChanged / live-count tick). Genuinely
                // time-varying so it can't be cached forever, but a rebind
                // within the same ~1s window would've produced the same
                // output anyway — reuse it instead. Same pattern as
                // HomeFragment.formatAgo() caching.
                long now = System.currentTimeMillis();
                if (r.timestamp != h.lastAgoTs
                        || h.lastAgoComputedAtMs < 0
                        || (now - h.lastAgoComputedAtMs) >= 1000) {
                    h.lastAgoTs = r.timestamp;
                    h.lastAgoStr = formatAgo(r.timestamp);
                    h.lastAgoComputedAtMs = now;
                }
                h.tvTime.setText(h.lastAgoStr);
            }

            // ── "...more" / "less" caption expand toggle — same
            // 2-line-truncate-past-120-chars pattern as HomeFragment's
            // btnReadMore. Every new bind starts collapsed; the toggle
            // click listener itself is registered ONCE in
            // setupClickListenersOnce() and reads/writes h.captionExpanded
            // instead of a fresh lambda capturing a local boolean[] on
            // every single bind.
            h.captionExpanded = false;
            if (h.btnReadMore != null) {
                String captionText = r.caption != null ? r.caption : "";
                if (captionText.length() > 120) {
                    h.tvCaption.setMaxLines(CAPTION_MAX_LINES);
                    h.tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    h.btnReadMore.setVisibility(View.VISIBLE);
                    h.btnReadMore.setText("more");
                } else {
                    h.tvCaption.setMaxLines(Integer.MAX_VALUE);
                    h.tvCaption.setEllipsize(null);
                    h.btnReadMore.setVisibility(View.GONE);
                }
            }
            h.tvLikes.setText(cachedLikesStr(h, r.likesCount));
            h.tvComments.setText(cachedCommentsStr(h, r.commentsCount));
            h.boundReelId = r.reelId;
            attachCountListener(r, h); // v284: keep likes/comments/reposts live while this row is on screen

            // Tap the like count → likes bottom sheet. Listener registered
            // once in setupClickListenersOnce(), reads h.boundReel.

            // ── Audio track label (avatar/name ke niche) — same as HomeFragment's
            // header row: song name if set, else "artist · Original audio",
            // else hidden entirely (no music attached to this post). Tap
            // listener registered once in setupClickListenersOnce().
            if (h.tvAudio != null) {
                String audioLabel = r.musicName != null && !r.musicName.isEmpty()
                    ? r.musicName
                    : (r.musicArtist != null && !r.musicArtist.isEmpty()
                       ? r.musicArtist + " · Original audio"
                       : null);
                if (audioLabel != null) {
                    h.tvAudio.setText(audioLabel);
                    h.tvAudio.setVisibility(View.VISIBLE);
                } else {
                    h.tvAudio.setVisibility(View.GONE);
                }
            }

            boolean liked = likedIds.contains(r.reelId);
            h.btnLike.setImageResource(liked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);

            // ── Double-tap on the media to like — same Instagram-style
            // gesture as the immersive Reels player. The GestureDetector
            // + OnTouchListener itself is built ONCE per holder in
            // setupFrameMediaGestureOnce() (constructor); every bind just
            // points it at the currently-bound reel via this field.
            // btnLike/btnComment/tvComments' click listeners are likewise
            // registered once in setupClickListenersOnce() and read this
            // same field.
            h.boundReel = r;

            // ── Repost button — show options (Repost / Quote Repost).
            // Click listener registered once in setupClickListenersOnce(),
            // reads h.boundReel. ──
            if (h.tvReposts != null) h.tvReposts.setText(cachedRepostStr(h, r.repostCount));

            // ── Save button — same reelSaves Firebase write pattern as
            // HomeFragment's btnSave. Click listener registered once in
            // setupClickListenersOnce(); it derives saved/not-saved live
            // from `savedIds` at click time instead of a per-bind
            // boolean[] captured via closure. ──
            if (h.btnSave != null) {
                boolean isSaved = r.reelId != null && savedIds.contains(r.reelId);
                h.btnSave.setImageResource(isSaved
                    ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
            }

            // ── Send / Share button — open ReelShareSheetFragment, same
            // destination as HomeFragment's btnSend. Click listeners
            // registered once in setupClickListenersOnce(), read
            // h.boundReel. ──
            if (h.tvSends != null) {
                h.tvSends.setText(cachedSharesStr(h, r.sharesCount));
            }

            // Suggested/follow-button rows aren't relevant on a
            // single-user filtered screen — keep them hidden.
            if (h.tvSuggested != null) h.tvSuggested.setVisibility(View.GONE);
            if (h.btnFollow   != null) h.btnFollow.setVisibility(View.GONE);

            // ── Background audio mute toggle — click listener registered
            // once in setupClickListenersOnce() (it doesn't even depend on
            // which reel is bound). ────────────────────────────────────
            // Only shown when this post actually has an attached music
            // track — nothing to mute/unmute otherwise.
            boolean hasAudio = r.musicUrl != null && !r.musicUrl.isEmpty();
            if (h.btnMute != null) {
                h.btnMute.setVisibility(hasAudio ? View.VISIBLE : View.GONE);
                h.btnMute.setImageResource(isMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
            }

            // ── Audio-cover tile — reused from the immersive Reels player's
            // right action rail (see fragment_reel_player.xml's
            // btn_create_audio / ReelUiController), same 28dp size as the
            // player and pinned to the image's bottom-right corner instead
            // of being the last item in a vertical rail. Same cover-
            // resolution as the tv_post_audio label above; click listener
            // (→ openSoundDetail) registered once in
            // setupClickListenersOnce(), reads h.boundReel.
            if (h.btnAudioCover != null) {
                if (hasAudio) {
                    h.btnAudioCover.setVisibility(View.VISIBLE);
                    String coverUrl = !android.text.TextUtils.isEmpty(r.musicCoverUrl)
                        ? r.musicCoverUrl : r.ownerPhoto;
                    if (!android.text.TextUtils.isEmpty(coverUrl)) {
                        android.content.Context ctx = h.btnAudioCover.getContext();
                        // PERF: same 28dp pattern as the player's btn_create_audio —
                        // server-resize via AvatarUrlBuilder(..,28) AND pin Glide's
                        // decode with .override() to 28dp*2 (retina), so this never
                        // decodes more pixels than the 28dp tile actually shows.
                        // The MultiTransformation + RequestOptions chain itself is
                        // now the adapter-level cached audioCoverOpts (see field
                        // above) instead of a fresh CenterCrop/RoundedCorners/
                        // RequestOptions object graph built on every single bind —
                        // this was the biggest allocation source on the scroll hot
                        // path for any feed with music attached to most posts.
                        // PERF (URL-skip): built resize URL is deterministic for a
                        // given coverUrl, so compare against the raw coverUrl (not
                        // the built one) to also skip the AvatarUrlBuilder.build()
                        // call itself on a same-post rebind.
                        if (!java.util.Objects.equals(coverUrl, h.lastAudioCoverSrcUrl)) {
                            h.lastAudioCoverSrcUrl = coverUrl;
                            Glide.with(ctx)
                                .load(com.callx.app.utils.AvatarUrlBuilder.build(
                                    ctx, coverUrl, com.callx.app.utils.AvatarSizeTier.TINY))
                                .apply(audioCoverOpts)
                                .into(h.btnAudioCover);
                        }
                    } else {
                        h.lastAudioCoverSrcUrl = null;
                        h.btnAudioCover.setImageResource(R.drawable.ic_audio);
                    }
                } else {
                    h.btnAudioCover.setVisibility(View.GONE);
                    h.lastAudioCoverSrcUrl = null;
                }
            }

            // ── ⋮ More menu — click listener registered once in
            // setupClickListenersOnce(), reads h.boundReel. ──
        }

        @Override
        public void onViewRecycled(@NonNull Holder h) {
            // Free the Glide target's decoded bitmap the moment a row scrolls
            // off-screen instead of leaving it referenced until the next bind
            // — keeps peak memory down on long scroll sessions.
            Glide.with(h.ivThumb.getContext()).clear(h.ivThumb);
            PostFeedAvatarBinder.cancel(h.ivAvatar.getContext(), h.ivAvatar);
            if (h.btnAudioCover != null) Glide.with(h.btnAudioCover.getContext()).clear(h.btnAudioCover);
            if (h.collabAvatarContainer instanceof LinearLayout) {
                CircleImageView av2 = ((LinearLayout) h.collabAvatarContainer).findViewWithTag("collab_av2");
                if (av2 != null) PostFeedAvatarBinder.cancel(av2.getContext(), av2);
            }
            // Glide.clear() above drops whatever bitmap was showing — the
            // URL-skip caches (see field doc above the caches) must forget
            // it too, or a future rebind to a post with the SAME image URL
            // would wrongly skip reloading into a now-empty target.
            h.lastThumbUrl = null;
            h.lastAvatarUrl = null;
            h.lastCollabAv2Url = null;
            h.lastAudioCoverSrcUrl = null;
            detachCountListener(h); // v284: row is off-screen, stop listening for its live counts
            h.boundReelId = null;
            // Carousel pages recycle themselves via PhotoPagerAdapter's own
            // onViewRecycled (clears each page's Glide target); dropping
            // the reel reference here just avoids a stale double-tap-like
            // target while this row sits recycled off-screen.
            h.photoPagerBoundReel = null;
            h.boundReel = null;
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
            // PERF: ListPreloader calls this repeatedly while scrolling
            // (once per upcoming off-screen item), so it's squarely on the
            // scroll hot path. Was building a brand-new RequestOptions
            // identical to thumbOpts on every single call — reuse the
            // adapter-level cached instance instead, same options as the
            // actual bind above so the preloaded decode is cache-hit-able.
            return Glide.with(PostsFeedActivity.this)
                .load(url)
                .apply(thumbOpts);
        }

        class Holder extends RecyclerView.ViewHolder {
            View      pvVideo; // PlayerView — only ever hidden on this screen
            ImageView ivThumb, ivAvatar, ivStoryRing, ivLikeAnim;
            TextView  tvOwner, tvCaption, tvLikes, tvComments, tvSuggested, tvAudio, btnFollow;
            TextView  tvReposts, tvSends;
            TextView  btnReadMore;
            TextView  tvCarouselIndex;
            androidx.viewpager2.widget.ViewPager2 photoPager;
            LinearLayout dotsContainer;
            // ── Carousel dot cache (see the dot-rebuild comment above in
            // onBindViewHolder) — built once per holder, rebuilt only when
            // the photo count for this row actually changes. ──
            View[] dotViews;
            android.graphics.drawable.GradientDrawable[] dotDrawables;
            int dotCount = -1;
            // Mutable — read live by the long-lived OnPageChangeCallback
            // installed once in setupPhotoPagerOnce() below, so a bind only
            // needs to update this field instead of rebuilding + re-
            // registering a fresh callback every time.
            int boundPhotoCount = 0;
            PhotoPagerAdapter photoPagerAdapter;
            // Mutable — read by the long-lived gesture detector below so
            // double-tap-to-like always resolves against whichever post
            // this row currently holds, without rebuilding the detector
            // (and its GestureDetector/OnTouchListener object graph) on
            // every single bind, which was the previous approach and the
            // biggest single allocation source on this carousel.
            ReelModel photoPagerBoundReel;
            // Mutable — read by frameMedia's long-lived double-tap gesture
            // detector (see setupFrameMediaGestureOnce()) so it always
            // resolves against whichever post currently occupies this row,
            // instead of rebuilding a whole GestureDetector object graph
            // on every single bind (was the single biggest allocation on
            // this screen — ViewConfiguration lookups + object graph, per
            // row, per scroll).
            ReelModel boundReel;
            TextView  tvTime;
            ImageButton btnLike, btnComment, btnMute, btnAudioCover;
            ImageButton btnRepost, btnSave;
            View        btnSend;
            View        btnMore;
            View        collabAvatarContainer;
            View        frameMedia;
            // v284 — live likes/comments/reposts bookkeeping for this row
            String              boundReelId;
            String              countListenerReelId;
            ValueEventListener  countListener;
            // Mutable — read/written by the once-registered btnReadMore
            // click listener (see setupClickListenersOnce()) instead of a
            // fresh boolean[] captured via closure on every bind. Reset to
            // false at the top of every bind (a newly-bound post always
            // starts collapsed).
            boolean captionExpanded = false;

            // ── PERF (Instagram-level pass): per-bind string-allocation
            // caches, same pattern as HomeFragment's PostRowHolder
            // (lastOwnerNameSrc/lastOwnerLabel/lastAgoTs/lastAgoStr) — a
            // rebind that lands on this holder with the same source value
            // (a live-count tick, a like tap's notifyItemChanged, or the
            // same reel simply scrolling back into view) reuses the
            // already-formatted String instead of re-running
            // formatAgo()/formatCount()/String.valueOf()/concat and
            // allocating a fresh one every single bind.
            long   lastAgoTs = Long.MIN_VALUE;
            long   lastAgoComputedAtMs = -1;
            String lastAgoStr;
            int    lastCarouselCount = -1;
            String lastCarouselStr;
            String lastCollabInitiatorName;
            String lastCollabCollaboratorName;
            String lastCollabLabel;
            int    lastLikesCount = Integer.MIN_VALUE;
            String lastLikesStr;
            int    lastCommentsCount = Integer.MIN_VALUE;
            String lastCommentsStr;
            int    lastRepostCount = Integer.MIN_VALUE;
            String lastRepostStr;
            int    lastSharesCount = Integer.MIN_VALUE;
            String lastSharesStr;

            // ── PERF (Instagram-level pass): Glide URL-skip caches — a
            // same-post rebind (DiffUtil landing here for a likesCount/
            // commentsCount/caption-only change, see PostDiffCallback)
            // used to re-run every image field's full Glide load()/
            // apply()/into() chain even though the URL itself never
            // changed. Track what's actually loaded into each target and
            // skip the Glide call entirely when the incoming URL matches —
            // same principle as the lastAgoStr/lastLikesStr text caches
            // above, applied to the image loads instead of string builds. ──
            String lastThumbUrl;
            String lastAvatarUrl;
            String lastCollabAv2Url;
            String lastAudioCoverSrcUrl;

            Holder(@NonNull View itemView) {
                super(itemView);
                pvVideo     = itemView.findViewById(R.id.pv_feed_post);
                ivThumb     = itemView.findViewById(R.id.iv_post_thumb);
                ivAvatar    = itemView.findViewById(R.id.iv_post_avatar);
                ivStoryRing = itemView.findViewById(R.id.iv_post_story_ring);
                ivLikeAnim  = itemView.findViewById(R.id.iv_post_like_anim);
                frameMedia  = itemView.findViewById(R.id.frame_video);
                tvCarouselIndex = itemView.findViewById(R.id.tv_post_carousel_index);
                photoPager  = itemView.findViewById(R.id.post_photo_pager);
                dotsContainer = itemView.findViewById(R.id.post_photo_dots);
                setupPhotoPagerOnce();
                setupFrameMediaGestureOnce();
                tvTime      = itemView.findViewById(R.id.tv_post_time);
                tvOwner     = itemView.findViewById(R.id.tv_post_owner);
                tvSuggested = itemView.findViewById(R.id.tv_post_suggested);
                tvAudio     = itemView.findViewById(R.id.tv_post_audio);
                tvCaption   = itemView.findViewById(R.id.tv_post_caption);
                tvLikes     = itemView.findViewById(R.id.tv_post_likes);
                tvComments  = itemView.findViewById(R.id.tv_post_comments);
                btnLike     = itemView.findViewById(R.id.btn_post_like);
                btnComment  = itemView.findViewById(R.id.btn_post_comment);
                btnFollow   = itemView.findViewById(R.id.btn_post_follow);
                btnMute     = itemView.findViewById(R.id.btn_post_mute);
                btnAudioCover = itemView.findViewById(R.id.btn_post_audio_cover);
                btnMore     = itemView.findViewById(R.id.btn_post_more);
                collabAvatarContainer = itemView.findViewById(R.id.layout_collab_avatar);
                btnRepost   = itemView.findViewById(R.id.btn_post_repost);
                tvReposts   = itemView.findViewById(R.id.tv_post_reposts);
                btnSave     = itemView.findViewById(R.id.btn_post_save);
                btnSend     = itemView.findViewById(R.id.btn_post_send);
                tvSends     = itemView.findViewById(R.id.tv_post_sends);
                btnReadMore = itemView.findViewById(R.id.tv_post_read_more);
                setupClickListenersOnce();
            }

            /** Runs once per Holder (constructor time), never per bind:
             *  wires the persistent PhotoPagerAdapter, joins the shared
             *  recycled-view pool, sets offscreenPageLimit so the next
             *  photo is already decoded before a swipe reveals it (no
             *  blank-frame flash mid-gesture — Instagram does the same),
             *  and installs the double-tap-to-like gesture detector. None
             *  of this needs to be rebuilt just because the row got
             *  recycled into a different post underneath it. */
            private void setupPhotoPagerOnce() {
                if (photoPager == null) return;

                photoPagerAdapter = new PhotoPagerAdapter();
                photoPager.setAdapter(photoPagerAdapter);
                photoPager.setOffscreenPageLimit(1);

                View innerRv = photoPager.getChildAt(0);
                if (innerRv instanceof RecyclerView) {
                    ((RecyclerView) innerRv).setRecycledViewPool(photoPagerSharedPool);
                    // Uniform ImageView pages don't need item-change
                    // animations (fade/flash) — same reasoning as the
                    // outer feed list disabling them, one less thing
                    // fighting the swipe gesture visually.
                    ((RecyclerView) innerRv).setItemAnimator(null);
                }

                // PERF: built + registered exactly ONCE here instead of a
                // fresh OnPageChangeCallback object allocated (plus an
                // unregister/register pair) on every single bind. Reads
                // `boundPhotoCount` / `dotDrawables` live off the Holder at
                // call-time, so it always reflects whichever post is
                // currently bound without needing to be rebuilt for it.
                photoPager.registerOnPageChangeCallback(
                    new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                        @Override public void onPageSelected(int position) {
                            if (boundPhotoCount <= 1 || dotDrawables == null) return;
                            tvCarouselIndex.setText((position + 1) + "/" + boundPhotoCount);
                            for (int di = 0; di < dotDrawables.length; di++) {
                                dotDrawables[di].setColor(di == position ? 0xFFFFFFFF : 0x66FFFFFF);
                            }
                        }
                    });

                final android.view.GestureDetector gestureDetector = new android.view.GestureDetector(
                    photoPager.getContext(),
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                        @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                            ReelModel bound = photoPagerBoundReel;
                            if (bound == null) return true;
                            if (!likedIds.contains(bound.reelId)) toggleLike(bound, Holder.this);
                            showLikeAnimation(ivLikeAnim);
                            return true;
                        }
                    });
                // Double-tap-to-like has to live on the pager itself here —
                // a ViewPager2 consumes touches before frameMedia's own
                // OnTouchListener would ever see them. Also claims
                // horizontal drags immediately so this screen's vertical
                // RecyclerView doesn't steal a left/right swipe mid-gesture
                // (same fix as HomeFragment's photo slideshow). Built once
                // and left attached permanently — harmless while the pager
                // is GONE for single-photo posts, since a gone view never
                // dispatches touches.
                photoPager.setOnTouchListener(new View.OnTouchListener() {
                    private float downX, downY;
                    @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                        switch (event.getActionMasked()) {
                            case android.view.MotionEvent.ACTION_DOWN:
                                downX = event.getRawX(); downY = event.getRawY();
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                                break;
                            case android.view.MotionEvent.ACTION_MOVE:
                                float dx = Math.abs(event.getRawX() - downX);
                                float dy = Math.abs(event.getRawY() - downY);
                                v.getParent().requestDisallowInterceptTouchEvent(dx >= dy);
                                break;
                            case android.view.MotionEvent.ACTION_UP:
                            case android.view.MotionEvent.ACTION_CANCEL:
                                v.getParent().requestDisallowInterceptTouchEvent(false);
                                break;
                        }
                        return gestureDetector.onTouchEvent(event);
                    }
                });
            }

            /** Runs once per physical inflated Holder — NOT once per bind.
             *  Mirrors setupPhotoPagerOnce()'s pattern above: build the
             *  GestureDetector + OnTouchListener object graph exactly once
             *  and have it read whichever reel is CURRENTLY bound (see
             *  `boundReel`) instead of a fresh detector capturing that
             *  bind's `r` via closure every single time this row rebinds. */
            private void setupFrameMediaGestureOnce() {
                if (frameMedia == null) return;
                final android.view.GestureDetector likeGesture = new android.view.GestureDetector(
                    frameMedia.getContext(),
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                        @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                            ReelModel bound = boundReel;
                            if (bound == null) return true;
                            if (!likedIds.contains(bound.reelId)) toggleLike(bound, Holder.this);
                            showLikeAnimation(ivLikeAnim);
                            return true;
                        }
                    });
                frameMedia.setOnTouchListener((v, event) -> {
                    boolean handled = likeGesture.onTouchEvent(event);
                    int action = event.getActionMasked();
                    return action == android.view.MotionEvent.ACTION_DOWN || handled;
                });
            }

            /** Runs once per physical Holder (constructor time), never per
             *  bind. ★ Instagram-level PERF: avatar/tvOwner/tvLikes/
             *  btnComment/tvComments/btnRepost/btnSave/tvSends/btnSend/
             *  btnMute/btnAudioCover/btnMore/tvAudio/btnReadMore's
             *  OnClickListeners used to be a fresh lambda object allocated
             *  on EVERY single bind, each one capturing that bind's `r`
             *  (and sometimes a local boolean[]) via closure — exactly the
             *  pattern already fixed for HomeFragment's home feed. Every
             *  listener here is now registered exactly once and reads
             *  whichever reel is CURRENTLY bound off `boundReel` (or, for
             *  the couple of listeners with no reel dependency at all —
             *  btnMute, btnReadMore — off other Holder-level mutable
             *  fields), so a bind is just a handful of field writes. */
            private void setupClickListenersOnce() {
                // Avatar tap: own profile, or the collab initiator's for a
                // collab repost.
                ivAvatar.setOnClickListener(v -> {
                    ReelModel br = boundReel;
                    if (br == null) return;
                    boolean collab = br.collabInitiatorUid != null && !br.collabInitiatorUid.isEmpty()
                                   && br.collabColaboratorUid != null && !br.collabColaboratorUid.isEmpty();
                    Intent i = new Intent(v.getContext(), UserReelsActivity.class);
                    if (collab) {
                        i.putExtra(UserReelsActivity.EXTRA_UID,   br.collabInitiatorUid);
                        i.putExtra(UserReelsActivity.EXTRA_NAME,  br.collabInitiatorName);
                        i.putExtra(UserReelsActivity.EXTRA_PHOTO, br.collabInitiatorPhoto);
                    } else {
                        i.putExtra(UserReelsActivity.EXTRA_UID,   br.uid);
                        i.putExtra(UserReelsActivity.EXTRA_NAME,  br.ownerName);
                        i.putExtra(UserReelsActivity.EXTRA_PHOTO, br.ownerPhoto);
                    }
                    v.getContext().startActivity(i);
                });
                // Name tap → same target as avatar tap (also correctly
                // covers the collab case, since avatar's own listener
                // above already branches on it).
                tvOwner.setOnClickListener(v -> ivAvatar.performClick());

                // Like count tap → likes bottom sheet.
                tvLikes.setOnClickListener(v -> {
                    ReelModel br = boundReel;
                    if (br == null || br.reelId == null || isFinishing() || isDestroyed()) return;
                    com.callx.app.comments.ReelLikesBottomSheet sheet =
                        com.callx.app.comments.ReelLikesBottomSheet.newInstance(
                            br.reelId, br.likesCount, br.viewsCount);
                    sheet.show(getSupportFragmentManager(), com.callx.app.comments.ReelLikesBottomSheet.TAG);
                });

                // Heart tap → like/unlike.
                btnLike.setOnClickListener(v -> {
                    ReelModel br = boundReel;
                    if (br != null) toggleLike(br, Holder.this);
                });

                // Comment icon / comment count tap → ReelCommentActivity.
                btnComment.setOnClickListener(v -> {
                    ReelModel br = boundReel;
                    if (br == null) return;
                    Intent ci = new Intent(v.getContext(), ReelCommentActivity.class);
                    ci.putExtra(ReelCommentActivity.EXTRA_REEL_ID, br.reelId);
                    ci.putExtra(ReelCommentActivity.EXTRA_REEL_UID, br.uid != null ? br.uid : "");
                    startActivity(ci);
                });
                if (tvComments != null) {
                    tvComments.setOnClickListener(v -> {
                        ReelModel br = boundReel;
                        if (br == null) return;
                        Intent ci = new Intent(v.getContext(), ReelCommentActivity.class);
                        ci.putExtra(ReelCommentActivity.EXTRA_REEL_ID, br.reelId);
                        ci.putExtra(ReelCommentActivity.EXTRA_REEL_UID, br.uid != null ? br.uid : "");
                        startActivity(ci);
                    });
                }

                // Repost button — show options (Repost / Quote Repost).
                if (btnRepost != null) {
                    btnRepost.setOnClickListener(v -> {
                        ReelModel br = boundReel;
                        if (br == null) return;
                        String myUid = FirebaseUtils.getCurrentUid();
                        if (myUid == null || br.reelId == null) return;
                        if (myUid.equals(br.uid)) {
                            Toast.makeText(v.getContext(),
                                "You can't repost your own reel", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String[] options = {"Repost", "Quote Repost"};
                        new androidx.appcompat.app.AlertDialog.Builder(v.getContext())
                            .setTitle("Repost options")
                            .setItems(options, (d, which) -> {
                                if (which == 0) {
                                    performRepost(br, myUid, tvReposts);
                                } else {
                                    try {
                                        ReelShareSheetFragment sheet = ReelShareSheetFragment.newInstance(
                                            br.reelId,
                                            br.videoUrl   != null ? br.videoUrl   : (br.video480 != null ? br.video480 : ""),
                                            br.thumbUrl   != null ? br.thumbUrl   : "",
                                            br.caption    != null ? br.caption    : "",
                                            br.uid        != null ? br.uid        : "",
                                            br.ownerName  != null ? br.ownerName  : "",
                                            br.ownerPhoto != null ? br.ownerPhoto : "",
                                            true
                                        );
                                        sheet.show(getSupportFragmentManager(), "quote_sheet");
                                    } catch (Exception e) {
                                        Intent share = new Intent(Intent.ACTION_SEND);
                                        share.setType("text/plain");
                                        String quote = "\"" + (br.caption != null ? br.caption : "Check this out")
                                            + "\" — @" + br.ownerName + " https://callx.app/reel/" + br.reelId;
                                        share.putExtra(Intent.EXTRA_TEXT, quote);
                                        startActivity(Intent.createChooser(share, "Quote Repost"));
                                    }
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    });
                }

                // Save button — toggles live off `savedIds` at click time
                // instead of a per-bind boolean[] snapshot.
                if (btnSave != null) {
                    btnSave.setOnClickListener(v -> {
                        ReelModel br = boundReel;
                        if (br == null) return;
                        String myUid = FirebaseUtils.getCurrentUid();
                        if (myUid == null || br.reelId == null) return;
                        boolean nowSaved = !savedIds.contains(br.reelId);
                        if (nowSaved) {
                            btnSave.setImageResource(R.drawable.ic_bookmark_filled);
                            savedIds.add(br.reelId);
                            FirebaseUtils.getReelSavesRef(myUid).child(br.reelId).setValue(true);
                            FirebaseUtils.getReelSavesIndexRef(br.reelId).child(myUid).setValue(true);
                            Toast.makeText(v.getContext(), "Saved!", Toast.LENGTH_SHORT).show();
                        } else {
                            btnSave.setImageResource(R.drawable.ic_bookmark);
                            savedIds.remove(br.reelId);
                            FirebaseUtils.getReelSavesRef(myUid).child(br.reelId).removeValue();
                            FirebaseUtils.getReelSavesIndexRef(br.reelId).child(myUid).removeValue();
                        }
                    });
                }

                // Shares count tap → shares bottom sheet.
                if (tvSends != null) {
                    tvSends.setOnClickListener(v -> {
                        ReelModel br = boundReel;
                        if (br == null || br.reelId == null) return;
                        ReelSharesBottomSheet sheet = ReelSharesBottomSheet.newInstance(
                            br.reelId, br.sharesCount, br.repostCount);
                        sheet.show(getSupportFragmentManager(), ReelSharesBottomSheet.TAG);
                    });
                }
                // Send/share icon tap → share sheet.
                if (btnSend != null) {
                    btnSend.setOnClickListener(v -> {
                        ReelModel br = boundReel;
                        if (br == null || br.reelId == null) return;
                        try {
                            ReelShareSheetFragment sheet = ReelShareSheetFragment.newInstance(
                                br.reelId,
                                br.videoUrl   != null ? br.videoUrl   : (br.video480 != null ? br.video480 : ""),
                                br.thumbUrl   != null ? br.thumbUrl   : "",
                                br.caption    != null ? br.caption    : "",
                                br.uid        != null ? br.uid        : "",
                                br.ownerName  != null ? br.ownerName  : "",
                                br.ownerPhoto != null ? br.ownerPhoto : "",
                                true
                            );
                            sheet.show(getSupportFragmentManager(), "share_sheet");
                        } catch (Exception e) {
                            Intent share = new Intent(Intent.ACTION_SEND);
                            share.setType("text/plain");
                            share.putExtra(Intent.EXTRA_TEXT,
                                "Check out this reel on CallX! @" + br.ownerName);
                            startActivity(Intent.createChooser(share, "Share reel"));
                        }
                    });
                }

                // Mute toggle — doesn't even depend on which reel is bound.
                if (btnMute != null) {
                    btnMute.setOnClickListener(v -> toggleMute());
                }

                // Audio label / audio-cover tap → sound detail.
                if (tvAudio != null) {
                    tvAudio.setOnClickListener(v -> {
                        ReelModel br = boundReel;
                        if (br != null) openSoundDetail(br);
                    });
                }
                if (btnAudioCover != null) {
                    btnAudioCover.setOnClickListener(v -> {
                        ReelModel br = boundReel;
                        if (br != null) openSoundDetail(br);
                    });
                }

                // ⋮ More menu.
                if (btnMore != null) {
                    btnMore.setOnClickListener(v -> {
                        ReelModel br = boundReel;
                        if (br != null) showMoreMenu(br, btnMore);
                    });
                }

                // "...more" / "less" caption expand toggle — reads/writes
                // captionExpanded instead of a per-bind boolean[].
                if (btnReadMore != null) {
                    btnReadMore.setOnClickListener(v -> {
                        captionExpanded = !captionExpanded;
                        if (captionExpanded) {
                            tvCaption.setMaxLines(Integer.MAX_VALUE);
                            tvCaption.setEllipsize(null);
                            btnReadMore.setText("less");
                        } else {
                            tvCaption.setMaxLines(CAPTION_MAX_LINES);
                            tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            btnReadMore.setText("more");
                        }
                    });
                }
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
        h.tvLikes.setText(cachedLikesStr(h, r.likesCount));
    }

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(java.util.Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    // ── PERF (Instagram-level pass): cached String.valueOf()/formatCount()
    // wrappers — the count itself only actually changes on a real Firebase
    // update or a like tap, but onBindViewHolder/attachCountListener were
    // reallocating a fresh String on every call regardless, including
    // same-value rebinds. These check the holder's last-seen int first and
    // only format again when it changed. ──
    private String cachedLikesStr(PostsAdapter.Holder h, int likesCount) {
        if (h.lastLikesCount != likesCount) {
            h.lastLikesCount = likesCount;
            h.lastLikesStr = String.valueOf(likesCount);
        }
        return h.lastLikesStr;
    }

    private String cachedCommentsStr(PostsAdapter.Holder h, int commentsCount) {
        if (h.lastCommentsCount != commentsCount) {
            h.lastCommentsCount = commentsCount;
            h.lastCommentsStr = String.valueOf(commentsCount);
        }
        return h.lastCommentsStr;
    }

    private String cachedRepostStr(PostsAdapter.Holder h, int repostCount) {
        if (h.lastRepostCount != repostCount) {
            h.lastRepostCount = repostCount;
            h.lastRepostStr = formatCount(repostCount);
        }
        return h.lastRepostStr;
    }

    private String cachedSharesStr(PostsAdapter.Holder h, int sharesCount) {
        if (h.lastSharesCount != sharesCount) {
            h.lastSharesCount = sharesCount;
            h.lastSharesStr = formatCount(sharesCount);
        }
        return h.lastSharesStr;
    }

    /** Same relative-time format as HomeFragment.formatAgo(). */
    private String formatAgo(long ts) {
        long diff = System.currentTimeMillis() - ts;
        long secs = diff / 1000;
        if (secs < 60)  return secs + "s";
        long mins = secs / 60;
        if (mins < 60)  return mins + "m";
        long hours = mins / 60;
        if (hours < 24) return hours + "h";
        return (hours / 24) + "d";
    }

    /** Double-tap heart-burst — same scale/fade AnimatorSet as the immersive
     *  player's ReelSocialController.showLikeAnimation(). */
    private void showLikeAnimation(ImageView ivLikeAnim) {
        if (ivLikeAnim == null) return;
        ivLikeAnim.setVisibility(View.VISIBLE);
        ivLikeAnim.setAlpha(1f);
        ivLikeAnim.setScaleX(0.3f);
        ivLikeAnim.setScaleY(0.3f);

        android.animation.AnimatorSet set = new android.animation.AnimatorSet();
        android.animation.ObjectAnimator scaleX =
            android.animation.ObjectAnimator.ofFloat(ivLikeAnim, "scaleX", 0.3f, 1.2f, 1.0f);
        android.animation.ObjectAnimator scaleY =
            android.animation.ObjectAnimator.ofFloat(ivLikeAnim, "scaleY", 0.3f, 1.2f, 1.0f);
        android.animation.ObjectAnimator alpha =
            android.animation.ObjectAnimator.ofFloat(ivLikeAnim, "alpha", 1f, 1f, 0f);
        alpha.setStartDelay(400);
        set.playTogether(scaleX, scaleY, alpha);
        set.setDuration(600);
        set.start();
    }

    /** Same reelReposts/userReposts write + repostCount transaction pattern
     *  as HomeFragment.performRepost(). */
    private void performRepost(ReelModel r, String myUid, TextView tvReposts) {
        long now = System.currentTimeMillis();
        com.google.firebase.database.FirebaseDatabase db =
            com.google.firebase.database.FirebaseDatabase.getInstance(
                com.callx.app.utils.Constants.DB_URL);
        db.getReference("reelReposts").child(r.reelId).child(myUid).setValue(now);
        db.getReference("userReposts").child(myUid).child(r.reelId).setValue(now);
        db.getReference("reels").child(r.reelId).child("repostCount")
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Integer c = d.getValue(Integer.class);
                    d.setValue(c != null ? c + 1 : 1);
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {}
            });
        com.callx.app.workers.ReelRepostWorker.enqueue(this, r.reelId, myUid,
            FirebaseUtils.getCurrentName(), r.uid, r.ownerName, r.thumbUrl);
        Toast.makeText(this, "Reposted!", Toast.LENGTH_SHORT).show();
        r.repostCount = r.repostCount + 1;
        if (tvReposts != null) tvReposts.setText(formatCount(r.repostCount));
    }
}
