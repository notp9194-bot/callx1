package com.callx.app.feed;
import com.callx.app.utils.AlertDialogStyler;

import com.callx.app.workers.ReelRepostWorker;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import android.widget.SeekBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.reels.R;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.comments.ReelCommentActivity;
import com.callx.app.explore.ReelExploreActivity;
import com.callx.app.social.ReelShareSheetFragment;
import com.callx.app.upload.ReelUploadActivity;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.profile.ReelPeekPreviewController;
import com.callx.app.explore.HashtagReelsActivity;
import com.callx.app.notifications.ReelNotificationsActivity;
import com.callx.app.explore.ReelSearchActivity;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.cache.UnifiedVideoCacheManager;
import com.callx.app.models.ReelModel;
import com.callx.app.ranking.FeedRankingEngine;
import com.callx.app.ranking.RankingProfile;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * HomeFragment — Production-grade Instagram-like social hub shown in the Reels "Home" tab.
 *
 * Sections (top → bottom):
 *  ✅ Stories bar      — 24-hr status items from contacts, unseen first with colored ring
 *  ✅ Feed toggle      — Following / For You toggle with active indicator underline
 *  ✅ Mixed feed       — Reel video posts shown as cards with full action row
 *  ✅ Trending Reels   — Horizontal scroll strip of top trending reels → opens player
 *  ✅ Friends Activity — Recent likes/reposts/comments with type icon + time ago
 *  ✅ Continue Watching— Reels user started but didn't finish → opens player at position
 *  ✅ Suggested Creators — Horizontal row of top reel creators to follow
 *
 * Advanced fixes:
 *  ✅ Story click → StatusViewerActivity (Class.forName cross-module)
 *  ✅ Unseen story ring (brand color) vs seen ring (gray) via statusSeen Firebase
 *  ✅ Stories sorted: unseen first
 *  ✅ Trending card click → SingleReelPlayerActivity
 *  ✅ Continue Watching card → SingleReelPlayerActivity
 *  ✅ Like state persistence (filled heart if already liked)
 *  ✅ Save reel from feed card (reelSaves Firebase write)
 *  ✅ Comment button opens ReelCommentActivity
 *  ✅ Avatar tap → UserReelsActivity
 *  ✅ Suggested Creators section with follow button
 */
public class HomeFragment extends Fragment {

    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout       containerStories;
    private LinearLayout       containerTrending;
    private LinearLayout       containerFriendsActivity;
    private LinearLayout       containerContinueWatching;
    private LinearLayout       containerSuggestedCreators;
    private ProgressBar        pbTrending;
    private ProgressBar        pbActivity;
    private ProgressBar        pbContinue;
    private ProgressBar        pbSuggested;
    private TextView           btnHomeFollowing;
    private TextView           btnHomeForYou;
    private View               vFeedIndicator;
    private TextView           btnSeeAllTrending;
    private TextView           btnClearHistory;
    private LinearLayout       btnAddStory;
    private ImageButton        btnHomeUpload;
    private CircleImageView    ivMyStoryAvatar;
    // ── New Instagram-style header ──
    private TextView           tvFeedTitle;
    private ImageButton        btnNewPost;
    private ImageButton        btnNotifications;
    // ── Inline auto-play (single ExoPlayer shared across feed cards) ──
    private ExoPlayer          feedPlayer;
    /** ★ Instant-play standby pool: two extra muted ExoPlayer instances that
     *  pre-buffer the card AHEAD of and BEHIND whichever card is currently
     *  active, so swapping either direction is just re-pointing a
     *  PlayerView — no cold prepare/buffer wait either way (Instagram-style
     *  scroll-up-to-rewatch is just as instant as scrolling forward). A
     *  fixed 3-instance pool total (active + standbyNext + standbyPrev) —
     *  players are demoted/promoted between roles rather than rebuilt, same
     *  reuse principle as ExoPlayerPool for the Reels swipe feed. See
     *  prepareStandbyNext()/prepareStandbyPrev()/attachPlayerToCard(). */
    private ExoPlayer          standbyNextPlayer;
    private int                standbyNextIndex = -1;
    private String             standbyNextUrl   = null;
    private ExoPlayer          standbyPrevPlayer;
    private int                standbyPrevIndex = -1;
    private String             standbyPrevUrl   = null;
    /** Timestamp attachPlayerToCard() started at — consumed by
     *  onRenderedFirstFrame() to measure real time-to-first-frame and feed
     *  it into AdaptiveStreamingManager's existing QoE stats, same metric
     *  the Reels swipe feed tracks. 0 = no measurement pending. */
    private long                attachStartTimeMs = 0L;
    /** v243: single top-level RecyclerView that replaced the old
     *  NestedScrollView+LinearLayout — see fragment_home.xml / FeedAdapter. */
    private RecyclerView       recyclerHome;
    private FeedAdapter        feedAdapter;
    /** Parallel to currentFeedPosts: feedCards.get(i) is the live HomeFeedCard
     *  for post i ONLY while that post's card is actually bound to an
     *  on-screen (or just-off-screen) ViewHolder; null while it's scrolled
     *  far enough away to have been recycled. Every place that reads this
     *  list must treat a null (or out-of-range) entry as "not currently
     *  visible" — same as the old height==0/parent==null checks did for a
     *  virtualized card. */
    private final List<HomeFeedCard> feedCards = new ArrayList<>();
    private int                currentPlayingIndex = -1;
    private boolean            isMuted = false;
    private final Handler      scrollHandler = new Handler(Looper.getMainLooper());

    // ── v281: Ultra-advanced performance coordinator ──────────────────────
    // Coordinates metadata caching, scroll state machine, network batching,
    // view recycling, and smart prefetch for Instagram-level feed speed.
    private HomeFeedUltraOptimizer ultraOptimizer;

    // ── v177: preload feature (same as Reels tab) ──────────────────────────
    // Preloads upcoming videos/thumbnails a couple cards ahead of whichever
    // card is currently playing, so by the time the user scrolls to them
    // they're already sitting in the shared cache (same one buildCachedMediaSource
    // reads from) — no spinner, no wait, no fresh download.
    private com.callx.app.cache.ReelVideoPreloader       videoPreloader;
    private com.callx.app.cache.ReelThumbnailPreloader   thumbPreloader;
    private com.callx.app.cache.ReelPredictivePreloader  predictivePreloader;
    /** Same singleton thermal/battery monitor the Reels swipe feed uses —
     *  gates all three preloaders above so Home's infinite-scroll prefetch
     *  doesn't keep feeding CPU/network work to a device that's overheating
     *  or nearly out of battery. See ReelThermalManager for the level table. */
    private com.callx.app.player.ReelThermalManager thermalManager;
    private final Runnable thermalChangeListener = this::onThermalChanged;
    /** Same singleton "precompute counts/caption ahead of the swipe" cache the
     *  Reels tab uses — see ReelUiStatePrecomputer/ReelUiStateCache docs. */
    private com.callx.app.cache.ReelUiStatePrecomputer uiStatePrecomputer;
    /** Same singleton offline-download/cache-fallback manager the Reels tab's
     *  "more" sheet uses (ReelPlayerController.saveReelOffline) — backs
     *  Home's own "Save for offline" menu item below. */
    private com.callx.app.player.ReelOfflineManager offlineManager;
    /** Manual view-virtualization for the feed's NestedScrollView — see class doc.
     *  Unloads off-screen card bitmaps and pauses Glide during fling so image
     *  decode work never competes with the scroll frame budget. */
    private HomeFeedWindowManager feedWindowManager;
    /** The exact list of posts currently backing feedCards, index-aligned with it. */
    private List<ReelModel> currentFeedPosts = new ArrayList<>();

    /** Lightweight holder for each inline feed card.
     *  Package-private (not private) so HomeFeedViewRecyclingOptimizer and
     *  HomeFeedUltraOptimizer, which live in the same package, can reference
     *  it as HomeFragment.HomeFeedCard for their onCardDetaching/onCardAttaching
     *  hooks. */
    static class HomeFeedCard {
        View      rootView;
        PlayerView playerView;
        ImageView  thumbView;
        View       endOverlay;
        String     videoUrl;
        String     reelId;
        /** Guards revealThumbnailAfterFirstFrame() against double-firing —
         *  reset to false every time this card becomes the active one. */
        boolean    firstFrameRevealed;
        // ── Advanced playback controls (see the scrub/hold/resume block) ──
        SeekBar    seekBar;
        TextView   tvPosition;
        TextView   speedChip;
        View       playOverlay;
        /** True while the user's finger is on this card's scrub bar — the
         *  progress ticker must not fight the drag by writing its own value. */
        boolean    isScrubbing;
        /** Set when the card becomes active; cleared once the saved watch
         *  position has actually been applied (needs a known duration). */
        boolean    resumePending;
        /** True while a press-and-hold 2x fast-forward is in effect. */
        boolean    speedBoosted;
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── v243: RecyclerView feed row model ───────────────────────────────────
    // The scrolling middle section (posts + interleaved suggested rows +
    // banner/loading/empty states) is now backed by this ordered list instead
    // of directly-added LinearLayout children. Adapter position 0 is always
    // the header item, and the last position is always the footer sections
    // item — feedItems occupies everything in between (see FeedAdapter).
    // ══════════════════════════════════════════════════════════════════════
    private static final int ROW_POST                = 1;
    private static final int ROW_SUGGESTED_CREATORS  = 2;
    private static final int ROW_SUGGESTED_REELS     = 3;
    private static final int ROW_NEW_POSTS_BANNER    = 4;
    private static final int ROW_LOADING             = 5;
    private static final int ROW_EMPTY               = 6;
    private static final int ROW_LOAD_MORE_FOOTER    = 7;
    private static final int VT_HEADER               = 0;
    private static final int VT_FOOTER               = 8;

    private static class FeedRow {
        final int type;
        /** For ROW_POST: index into currentFeedPosts/feedCards. */
        int postIndex = -1;
        /** For ROW_SUGGESTED_CREATORS: candidate pool (uid,name,photo). */
        List<String[]> creatorPool;
        /** For ROW_SUGGESTED_REELS: candidate reel pool. */
        List<ReelModel> reelPool;
        FeedRow(int type) { this.type = type; }
    }

    /** The middle, recycled section of the feed — see FeedRow doc above. */
    private final List<FeedRow> feedItems = new ArrayList<>();

    /** Growable in lockstep with currentFeedPosts so feedCards.set(i, ...)
     *  never throws — new slots default to null ("not currently bound"). */
    private void ensureFeedCardsCapacity(int size) {
        while (feedCards.size() < size) feedCards.add(null);
    }

    /** Adapter position → header offset. Header is always position 0. */
    private static final int FEED_HEADER_OFFSET = 1;

    /** v243: replaces the old "yank the View straight out of its
     *  LinearLayout parent" approach for optimistic "Not interested"/"Block"
     *  removal — finds this reel's ROW_POST entry in feedItems and removes
     *  it from the adapter properly instead. */
    private void removeFeedRowByReelId(String reelId) {
        if (reelId == null || feedAdapter == null) return;
        for (int i = 0; i < feedItems.size(); i++) {
            FeedRow row = feedItems.get(i);
            if (row.type == ROW_POST && row.postIndex >= 0 && row.postIndex < currentFeedPosts.size()
                    && reelId.equals(currentFeedPosts.get(row.postIndex).reelId)) {
                feedItems.remove(i);
                feedAdapter.notifyItemRemoved(FEED_HEADER_OFFSET + i);
                return;
            }
        }
    }

    /** Same value ReelPlayerController (Reels tab) uses — short enough that
     *  the surface hand-off reads as one continuous frame instead of a
     *  visible cut, matching Instagram's near-zero thumbnail→video swap. */
    private static final long HOME_THUMB_CROSSFADE_MS = 80L;

    private boolean isFollowingMode = true;

    // ══════════════════════════════════════════════════════════════════════
    // ── Advanced inline playback: scrub bar, hold-to-2x, resume, tracking ──
    // ══════════════════════════════════════════════════════════════════════
    /** How often the active card's scrub bar / watch progress is refreshed. */
    private static final long PROGRESS_TICK_MS = 250L;
    /** Playback rate applied while the user presses and holds a feed video. */
    private static final float HOLD_SPEED = 2f;
    /** Views / watch history / watch progress writer — see HomeFeedWatchTracker. */
    private HomeFeedWatchTracker watchTracker;
    /** users/{uid}/feedSettings/autoplay (Always / Wi-Fi Only / Off). */
    private final HomeFeedAutoplayPolicy autoplayPolicy = new HomeFeedAutoplayPolicy();
    private Runnable progressTicker  = null;
    private boolean  isTickerRunning = false;
    /** True when the active card is paused because the user tapped it (or
     *  autoplay is disabled) — keeps onResume/tab-switch from overriding it. */
    private boolean  userPausedActiveCard = false;

    // Tracks which ownerUids have at least one unseen status item for this viewer
    private final Set<String> unseenOwnerUids = new HashSet<>();

    // ══════════════════════════════════════════════════════════════════════
    // ── Instagram-style unified ranked feed: pagination + real-time ────────
    // ══════════════════════════════════════════════════════════════════════
    /** How many posts we render per "page" appended to the feed. */
    private static final int FEED_PAGE_SIZE   = 8;
    /** How many raw rows we ask Firebase for per fetch (server-side window). */
    private static final int FEED_FETCH_BATCH = 25;
    /** How often (in rendered posts) an inline "Suggested for you" creator
     *  row is mixed into the feed — Instagram doesn't show trending/suggested
     *  as separate boxes, it blends them straight into the scroll. */
    private static final int SUGGESTED_EVERY_N_POSTS = 6;
    /** How often an inline "Suggested reels" thumbnail row is mixed into the
     *  feed — same interleaving idea as the creators row above, offset so
     *  the two don't always land on the same post (feels organic, like IG). */
    private static final int SUGGESTED_REELS_EVERY_N_POSTS = 4;

    // ══════════════════════════════════════════════════════════════════════
    // ── v280: list cap / windowing ──────────────────────────────────────────
    // Infinite scroll only ever appends, so a long Home session accumulates
    // every post ever fetched in currentFeedPosts/feedCards/feedItems forever
    // — none of it is recycled RecyclerView-style the way onBindViewHolder's
    // views are (see FeedAdapter.onViewRecycled: that only nulls a feedCards
    // *slot*, it never shrinks the backing lists). maybeTrimFeedWindow()
    // periodically drops posts the user has scrolled well past off the FRONT
    // of these lists so their size stays bounded by how far the user has
    // scrolled forward, not by the session's total lifetime. The AHEAD side
    // (posts fetched but not yet scrolled to) is intentionally left alone —
    // it's already bounded by FEED_FETCH_BATCH per page and trimming it would
    // create a permanent gap once oldestFeedTimestamp has already moved past
    // it (loadMoreFeedPosts would never re-fetch it). Front-trim is safe by
    // comparison because reloadTrimmedFrontPosts() below re-fetches exactly
    // that range if the user scrolls back up into it.
    // ══════════════════════════════════════════════════════════════════════
    /** How many posts behind the current visible position stay loaded. */
    private static final int FEED_WINDOW_BEHIND = 15;
    /** How many posts ahead of the current visible position stay loaded —
     *  documented for symmetry with FEED_WINDOW_BEHIND; the ahead side is
     *  never actively trimmed (see class-doc block above), so this only
     *  documents the intended shape of the window, not a trim boundary. */
    private static final int FEED_WINDOW_AHEAD = 30;
    /** Trim only once the feed is a full page past the window, so a single
     *  page append doesn't trigger a DiffUtil pass every time. */
    private static final int FEED_TRIM_SLACK = FEED_PAGE_SIZE;
    /** Newest timestamp among posts ever trimmed off the front — null until
     *  the first trim. reloadTrimmedFrontPosts() uses this to know when it
     *  has re-fetched all the way back to where trimming started. */
    private Long frontTrimHighWaterTimestamp = null;
    /** Guards reloadTrimmedFrontPosts() against duplicate in-flight fetches
     *  from a fast scroll-up. */
    private boolean isLoadingNewerFeed = false;

    private boolean          isLoadingMoreFeed   = false;
    private boolean          feedHasMore         = true;
    private Long             oldestFeedTimestamp = null;
    private Long             newestFeedTimestamp = null;
    private Set<String>      cachedFollowedUids  = new HashSet<>();
    // ── Instagram-level ranking: personalization snapshot for the current
    // feed render (relationship, creator watch-affinity, topic prefs, etc.)
    // — see FeedRankingEngine / RankingProfile. Reloaded on each fresh feed
    // load (mode switch, pull-to-refresh); reused as-is across pagination
    // pages within that same load so "page 2" ranks against the same
    // snapshot as "page 1" instead of drifting mid-scroll.
    private RankingProfile   cachedRankingProfile = new RankingProfile();
    private Set<String>      cachedLikedIds      = new HashSet<>();
    private Set<String>      cachedSavedIds      = new HashSet<>();
    private String           cachedMyUidForFeed  = null;
    private final Set<String> renderedReelIds    = new HashSet<>();
    private View             feedLoadMoreFooter  = null;
    private View             newPostsBanner      = null;
    private int               newPostsPending     = 0;
    private int               postsSincePeopleYouMayLike = 0;
    private List<String[]>   suggestedCreatorPool = null; // uid,name,photo,sub — fetched once/session
    private int               postsSinceSuggestedReels   = 0;
    private List<ReelModel>   suggestedReelsPool   = null; // fetched once/session, reused for every insertion
    // Same shared mini-video-player long-press "peek" that UserReelsActivity's
    // grid and SoundDetailFragment already use — reused as-is here, just with
    // a bigger card size (see buildInlineSuggestedReelsRow's long-press wiring).
    private ReelPeekPreviewController suggestedReelsPeekController = null;
    private Query             newPostsQuery       = null;
    private ChildEventListener newPostsListener   = null;

    // ── Buttery-smooth scroll: perf toggles ────────────────────────────────
    /** Cheap-decode Glide options for feed thumbnails/avatars — RGB_565
     *  halves per-pixel memory vs the default ARGB_8888 and skips Glide's
     *  cross-fade transition, which is the single biggest avoidable cause
     *  of dropped frames when many images decode while the user is mid-fling. */
    private static final com.bumptech.glide.request.RequestOptions FEED_IMAGE_OPTS =
            new com.bumptech.glide.request.RequestOptions()
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .dontAnimate();
    /** True while the NestedScrollView is actively moving. Used to switch the
     *  feed content to a hardware layer only while scrolling — GPU-composites
     *  the whole scrolling subtree as one texture instead of re-drawing every
     *  child view per frame, which is what makes long feeds feel "buttery"
     *  instead of stuttery. Reverted to LAYER_TYPE_NONE once idle, since a
     *  hardware layer left on permanently costs GPU memory for no benefit. */
    private boolean   isFeedScrolling          = false;
    /** Whether the hardware-layer promotion actually got applied this scroll. */
    private boolean   isHwLayerOn              = false;
    private View      feedScrollContentRoot     = null;
    /** Idle-time pre-inflater for feed cards — see HomeFeedCardPool. */
    private HomeFeedCardPool cardPool = null;

    // ── Scroll-listener hot path ────────────────────────────────────────
    // onScrollChange fires on EVERY scrolled pixel — several hundred times a
    // fling — so nothing in it may allocate. These runnables and the
    // dp thresholds below used to be rebuilt per event; they are now created
    // once and merely re-posted.
    private final Runnable scrollSettleRunnable = new Runnable() {
        @Override public void run() {
            endFeedScrollLayer();
            if (feedWindowManager != null) feedWindowManager.onScrollSettled();
        }
    };
    /** ★ Instagram-level instant autoplay: coalesces to at most one
     *  playMostVisibleCard() call per Looper pass (~once per frame) instead
     *  of the old "wait 120ms after scroll goes quiet" debounce. The old
     *  debounce reset on every single onScrolled call, so during any
     *  continuous drag/fling — where onScrolled fires continuously — it
     *  never actually fired until the whole scroll had been motionless for
     *  120ms straight, meaning a reel didn't start playing until well after
     *  it had already settled into view. playMostVisibleCard() itself is
     *  cheap (a bounded visibility scan with an early break, no allocation)
     *  and only does real work when the dominant card actually changed, so
     *  running it every frame during scroll is safe — a card now starts
     *  playing the moment it becomes the most-visible one, not 120ms after
     *  the finger stops. playVisibleCheckScheduled guards against queuing
     *  more than one pending check per frame.
     *
     *  ★★ Ultra-advanced: the hot-path scheduling itself runs on
     *  Choreographer rather than the plain main-thread Handler used
     *  everywhere else. Handler.post() only queues behind whatever else is
     *  already sitting in the main Looper's message queue (input events,
     *  other posted work), so under load its "next frame" isn't reliably
     *  synced to vsync. Choreographer.postFrameCallback() is the same
     *  primitive RecyclerView's own scroll/layout pipeline uses internally
     *  — it runs exactly once per display refresh, right alongside the
     *  layout pass that just moved these cards, so the visibility check
     *  always reads this frame's final positions instead of racing an
     *  arbitrary queued message. */
    private boolean playVisibleCheckScheduled = false;
    private final android.view.Choreographer.FrameCallback playVisibleFrameCallback = frameTimeNanos -> {
        playVisibleCheckScheduled = false;
        playMostVisibleCard();
    };
    /** Handler-based twin of playVisibleFrameCallback — kept for the few
     *  call sites elsewhere that want a deliberate fixed delay (e.g. "wait
     *  300ms for a transition to finish, then re-check") rather than a
     *  frame-synced check; the hot scroll path below uses the Choreographer
     *  callback exclusively. */
    private final Runnable playVisibleRunnable = () -> {
        playVisibleCheckScheduled = false;
        playMostVisibleCard();
    };
    private int paginateThresholdPx = 0;
    private int prefetchThresholdPx = 0;
    /** Reused by playMostVisibleCard() so a scroll never allocates an int[]. */
    private final int[] visibilityLoc = new int[2];
    /** Max side a hardware layer can be rasterized into on virtually all
     *  GPUs; a taller layer is silently refused, so promoting a very long
     *  feed's content root costs a re-render for nothing. */
    private static final int MAX_HW_LAYER_PX = 4096;
    /** Decode size for the 36dp card avatar (36dp ≈ 144px at xxhdpi). */
    private static final int AVATAR_DECODE_PX = 144;
    /** ★ Ultra-advanced optimization: Pattern.compile() is far from free —
     *  buildCaptionSpannable() used to recompile this same regex on EVERY
     *  card bind (every scroll rebind, not just once per unique caption).
     *  Compiled once and reused; matching against it per-bind is cheap,
     *  compiling it per-bind was not. */
    private static final java.util.regex.Pattern HASHTAG_PATTERN =
        java.util.regex.Pattern.compile("#(\\w+)");
    /** Card thumbnail decode size — 9:16, matching the card frame. Shared by
     *  the card load and the prefetch so both hit the same Glide cache key. */
    private static final int THUMB_DECODE_W = 540;
    private static final int THUMB_DECODE_H = 960;
    /** Trending strip / Continue Watching tiles render at ~120–140dp — the
     *  fixed 720x720 decode those two spots used was sized for a full-width
     *  card, roughly 3-5x more pixels than any device density actually needs
     *  for a ~140dp tile, wasting decode time and heap on every card. 260px
     *  covers up to xxhdpi (140dp × ~1.9) with headroom. */
    private static final int STRIP_THUMB_DECODE_PX = 260;
    /** De-dupes prefetchUpcomingFeedMedia() across a fling's scroll events. */
    private int lastPrefetchFromIndex = -1;

    // Story data model for proper sorting
    private static class StoryEntry {
        String uid, name, photo;
        boolean hasUnseen;
        /** True when contact has at least one reel_story type — shows gradient ring */
        boolean hasReelStory;
        StoryEntry(String u, String n, String p, boolean unseen, boolean reelStory) {
            uid = u; name = n; photo = p; hasUnseen = unseen; hasReelStory = reelStory;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        bindViews(v);
        // ★ Ultra-advanced: GpuDecodeWarmup was previously only fired from
        // ReelsFragment, so a user who opens the Home tab FIRST (very common
        // — it's the app's default landing tab) paid the full one-time
        // MediaCodec HAL + EGL/GL driver cold-start cost (50-150ms+) inline
        // on their very first Home feed reel instead of it being paid
        // speculatively in the background beforehand. warmUpOnce() is
        // guarded to run at most once per process, so calling it from both
        // tabs is free — whichever tab the user opens first now wins the
        // warm-up instead of only Reels.
        com.callx.app.player.GpuDecodeWarmup.warmUpOnce(requireContext());
        // ── v243: LayoutManager must be attached to recyclerHome BEFORE
        // anything is inflated with it as the (false-attach) parent —
        // RecyclerView.generateLayoutParams() requires one to already be set
        // even just to build a root view's LayoutParams, or inflate() throws
        // "RecyclerView has no LayoutManager". Adapter is set once built below.
        LinearLayoutManager homeLayoutManager = new LinearLayoutManager(requireContext());
        // ★ Ultra-advanced optimization: fragment_home.xml pins recycler_home
        // to match_parent/match_parent inside a CoordinatorLayout, so the
        // RecyclerView's own on-screen size never changes as items are
        // added/removed/resized (posts loading, rows swapping in) — exactly
        // the contract setHasFixedSize() requires. Skips a measure/layout
        // pass of the RecyclerView's PARENT on every adapter change (it only
        // re-measures itself), same class of win as the item-cache/pool
        // tuning below, just one level up the view tree.
        recyclerHome.setHasFixedSize(true);
        // Instagram-level look-ahead: LinearLayoutManager's default gap-worker
        // prefetch only warms 1 extra row per fling frame. item_home_feed_post
        // is a heavy inflate (PlayerView + ~28 children, see ROW_POST pool
        // note below) so widening this to 2 gives the prefetch thread more of
        // a head start on the next row while the current one is still
        // settling, at the cost of a little extra idle prefetch work.
        homeLayoutManager.setInitialPrefetchItemCount(2);
        recyclerHome.setLayoutManager(homeLayoutManager);
        recyclerHome.setItemViewCacheSize(6);
        // ★ Ultra-advanced optimization: item_home_feed_post.xml is a heavy
        // inflate (PlayerView, seek bar, ~28 child views). The default
        // recycled-pool cap is 5 per view type — on a fast fling that's not
        // always enough to keep ahead of the scroll, so the pool empties and
        // RecyclerView falls back to a fresh LayoutInflater.inflate() (the
        // second most expensive thing in this bind path after the
        // findViewById cost PostRowHolder.cacheViews() already removed).
        // Raising the pool for just the post row type keeps more already-
        // inflated instances on hand for a big fling, at the cost of a
        // little idle memory — the standard fix for this exact stutter.
        recyclerHome.getRecycledViewPool().setMaxRecycledViews(ROW_POST, 10);
        // Keep RecyclerView's native fling implementation. Do not call
        // recyclerHome.fling() from an OnFlingListener: RecyclerView invokes
        // that listener from fling(), so re-dispatching here recursively
        // re-enters the callback until the app crashes with StackOverflowError
        // as soon as the feed is flung.
        // Instagram/TikTok-style feeds run without the default add/remove/
        // change fade-and-shift animation: every notifyItemChanged (like
        // count ticking up, a save toggling, DiffUtil remapping positions
        // after loadMoreReels()/trimFeedFront()) would otherwise trigger a
        // ~120ms cross-fade + shift on that row, which reads as stutter on
        // a feed that scrolls continuously. Content still updates instantly.
        recyclerHome.setItemAnimator(null);
        feedScrollContentRoot = recyclerHome;
        // Header/footer are inflated ONCE here (not lazily by the adapter) so
        // every existing method that reaches for containerStories,
        // btnHomeFollowing, containerTrending, etc. keeps working exactly as
        // before — those fields are populated immediately, same timing as
        // when fragment_home.xml held them directly. FeedAdapter just wraps
        // these same two View instances as VT_HEADER (position 0) / VT_FOOTER
        // (last position) so they scroll as part of the one RecyclerView.
        View headerView = inflater.inflate(R.layout.item_home_header, recyclerHome, false);
        View footerView = inflater.inflate(R.layout.item_home_footer, recyclerHome, false);
        bindHeaderViews(headerView);
        bindFooterViews(footerView);
        feedAdapter = new FeedAdapter(headerView, footerView);
        recyclerHome.setAdapter(feedAdapter);

        // ── v281: Initialize ultra-advanced performance coordinator ────────
        // Coordinates metadata caching, scroll state, network batching, view
        // recycling, and smart prefetch for Instagram-level feed speed.
        if (this.ultraOptimizer == null) {
            this.ultraOptimizer = new HomeFeedUltraOptimizer();
            this.ultraOptimizer.initialize(requireContext(), 
                FirebaseUtils.getReelsRef(), scrollHandler);
        }

        // Hook RecyclerView scroll events → ultraOptimizer scroll state machine
        recyclerHome.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (ultraOptimizer != null) {
                    ultraOptimizer.onRecyclerScrollStateChanged(newState);
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (ultraOptimizer != null) {
                    ultraOptimizer.onRecyclerScrolled(dx, dy);
                }
            }
        });

        // ★ Built via AdaptiveStreamingManager.buildBarePlayer() instead of a
        // plain ExoPlayer.Builder() — gets the SAME network-tier-tuned
        // LoadControl (short start buffer, e.g. ~800ms bufferForPlaybackMs on
        // WiFi vs ExoPlayer's slower ~2500ms default) and shared
        // THREAD_PRIORITY_DISPLAY playback thread the Reels swipe feed
        // already uses for fast, low-latency start — Home's feed was
        // previously left on generic defaults.
        // ── Shared ExoPlayerPool instead of a raw buildBarePlayer() ────────
        // Same centralized, tested pool the Reels swipe feed uses — POOL_SIZE=3
        // was sized for exactly "prev paused / current playing / next
        // prewarmed", which is precisely Home's feedPlayer + standbyPrev +
        // standbyNext trio, so no capacity change needed on the pool itself.
        feedPlayer = com.callx.app.player.ExoPlayerPool.get(requireContext()).acquire();
        configureFeedPlayerInstance(feedPlayer);
        // ── Instagram-level thermal manager (real-time monitoring) ────────
        // Same singleton the Reels swipe feed uses — process-lifetime, so
        // this just attaches Home's own change-listener to it. Initialised
        // BEFORE the preloaders below so they can be gated from the first
        // preload call, same ordering as ReelsFragment.
        thermalManager = com.callx.app.player.ReelThermalManager.get(requireContext());
        thermalManager.addChangeListener(thermalChangeListener);

        // ── v177: same preload feature Reels tab has ──────────────────────
        videoPreloader      = new com.callx.app.cache.ReelVideoPreloader(requireContext());
        thumbPreloader      = new com.callx.app.cache.ReelThumbnailPreloader(requireContext());
        predictivePreloader = new com.callx.app.cache.ReelPredictivePreloader(requireContext());
        // Same UI-state precompute + offline manager the Reels tab wires up
        // alongside its preloaders.
        uiStatePrecomputer  = new com.callx.app.cache.ReelUiStatePrecomputer();
        offlineManager      = com.callx.app.player.ReelOfflineManager.get(requireContext());
        // v243: feedWindowManager/cardPool retired — real RecyclerView
        // ViewHolder recycling (FeedAdapter) now does what their manual
        // bitmap-unload / idle-pre-inflate hacks existed to approximate.
        // Left as unused (always-null) fields for now rather than deleting
        // HomeFeedWindowManager/HomeFeedCardPool outright, since their
        // remaining logic (thermal/battery-aware preloading) is still on the
        // TODO list for a later pass — see chat notes on priority #3.
        // ── Watch tracking + autoplay preference ───────────────────────────
        // Both are read once here: the watch-progress map powers resume
        // positions for every card without a per-card Firebase read, and the
        // autoplay mode decides whether a card that scrolls into view starts
        // by itself or waits behind a tap-to-play overlay.
        watchTracker = new HomeFeedWatchTracker(safeMyUid());
        watchTracker.preloadWatchProgress(null);
        autoplayPolicy.load(safeMyUid(), null);
        setupListeners();
        loadAllSections();
        return v;
    }

    /**
     * Applies the repeat-mode + end-of-reel listener every active feed
     * player needs. feedPlayer and the two standby players swap roles
     * repeatedly (see attachPlayerToCard's instant-play swap) — this must
     * run once on EVERY ExoPlayer instance we build (here and in
     * prepareStandbyNext/prepareStandbyPrev), not just the one that starts
     * out as feedPlayer, or a promoted former-standby would silently play
     * with no end-of-reel overlay and REPEAT_MODE_OFF unset.
     *
     * Also wires time-to-first-frame measurement: onRenderedFirstFrame only
     * fires for an instance that's actually rendering to a Surface, which
     * only ever happens for whichever instance is currently feedPlayer (the
     * two standby instances are never PlayerView-attached), so this is safe
     * to attach uniformly to all three pool instances.
     */
    private void configureFeedPlayerInstance(ExoPlayer player) {
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        Player.Listener listener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && currentPlayingIndex >= 0
                        && currentPlayingIndex < feedCards.size()
                        && feedCards.get(currentPlayingIndex) != null) {
                    HomeFeedCard card = feedCards.get(currentPlayingIndex);
                    if (card.endOverlay != null && isAdded()) {
                        requireActivity().runOnUiThread(
                            () -> card.endOverlay.setVisibility(View.VISIBLE));
                    }
                    // Finished inline → same bookkeeping the full-screen player
                    // does: history keeps the timestamp, progress resets to 0.
                    if (watchTracker != null) watchTracker.onPlaybackCompleted(card.reelId);
                    if (card.seekBar != null && isAdded()) {
                        requireActivity().runOnUiThread(
                            () -> card.seekBar.setProgress(card.seekBar.getMax()));
                    }
                }
                // A promoted standby player only learns its duration here, so
                // this is the first safe point to apply a saved resume seek.
                if (state == Player.STATE_READY) applyPendingResumeSeek();
            }
            @Override
            public void onRenderedFirstFrame() {
                // Instagram-style handoff: reveal the card's thumbnail crossfade
                // HERE — the first actually-decoded frame on screen — not at
                // player-attach time (STATE_READY can land a frame or two
                // before anything is visibly drawn, which is what caused the
                // old attach-time fade to expose a visible "jump" between the
                // static thumb and a still-blank/half-buffered surface).
                if (currentPlayingIndex >= 0 && currentPlayingIndex < feedCards.size()
                        && feedCards.get(currentPlayingIndex) != null) {
                    revealCardThumbnailAfterFirstFrame(feedCards.get(currentPlayingIndex));
                }

                // Consume once — attachStartTimeMs is reset to 0 by whichever
                // call fires first, so a stray late callback on an old
                // instance can't double-report.
                if (attachStartTimeMs <= 0 || !isAdded()) return;
                long ttffMs = System.currentTimeMillis() - attachStartTimeMs;
                attachStartTimeMs = 0L;
                try {
                    com.callx.app.player.AdaptiveStreamingManager.get(requireContext())
                        .persistQoeSession(0, 0, 0, 0, ttffMs);
                } catch (Exception ignored) {}
            }
        };
        player.addListener(listener);
        // Track this listener against the SHARED ExoPlayerPool so that when
        // this instance is handed back via ExoPlayerPool.release(), the pool
        // strips it before the next acquirer (Home or Reels tab) gets this
        // player — otherwise a reused pooled instance would keep firing this
        // stale card's onRenderedFirstFrame/onPlaybackStateChanged forever.
        com.callx.app.player.ExoPlayerPool.get(requireContext()).trackListener(player, listener);
    }

    /**
     * Clears just the top story row and rebuilds it. loadStories() only
     * APPENDS story avatars — calling it directly (without clearing first)
     * would duplicate every avatar on each resume/observer refresh, so this
     * wrapper is what onResume() and storyRingObserver actually call.
     */
    private void refreshStoryRow() {
        clearStoriesKeepAddButton();
        loadStories();
    }

    private void bindViews(View v) {
        swipeRefresh              = v.findViewById(R.id.swipe_refresh_home);
        recyclerHome              = v.findViewById(R.id.recycler_home);
    }

    /** v243: populates the SAME fragment-level fields bindViews() used to,
     *  just sourced from the separately-inflated header item view instead of
     *  the fragment's own root — see onCreateView. Every other method that
     *  touches these fields (loadStories, updateFeedToggleUI, switchFeedMode,
     *  showFeedFilterDropdown, loadMyAvatar, ...) is unchanged. */
    private void bindHeaderViews(View v) {
        containerStories   = v.findViewById(R.id.container_stories);
        btnHomeFollowing   = v.findViewById(R.id.btn_home_following);
        btnHomeForYou      = v.findViewById(R.id.btn_home_for_you);
        vFeedIndicator     = v.findViewById(R.id.v_feed_indicator);
        btnAddStory        = v.findViewById(R.id.btn_add_story);
        btnHomeUpload      = v.findViewById(R.id.btn_home_upload);
        ivMyStoryAvatar    = v.findViewById(R.id.iv_my_story_avatar);
        tvFeedTitle        = v.findViewById(R.id.tv_feed_title);
        btnNewPost         = v.findViewById(R.id.btn_new_post);
        btnNotifications   = v.findViewById(R.id.btn_notifications);
    }

    /** v243: same idea as bindHeaderViews(), for the trailing sections. */
    private void bindFooterViews(View v) {
        containerTrending          = v.findViewById(R.id.container_trending);
        containerFriendsActivity   = v.findViewById(R.id.container_friends_activity);
        containerContinueWatching  = v.findViewById(R.id.container_continue_watching);
        containerSuggestedCreators = v.findViewById(R.id.container_suggested_creators);
        pbTrending                 = v.findViewById(R.id.pb_trending);
        pbActivity                 = v.findViewById(R.id.pb_activity);
        pbContinue                 = v.findViewById(R.id.pb_continue);
        pbSuggested                = v.findViewById(R.id.pb_suggested);
        btnSeeAllTrending          = v.findViewById(R.id.btn_see_all_trending);
        btnClearHistory            = v.findViewById(R.id.btn_clear_history);
    }

    private void setupListeners() {
        swipeRefresh.setColorSchemeResources(R.color.brand_primary);
        swipeRefresh.setOnRefreshListener(() -> {
            unseenOwnerUids.clear();
            resetFeedPaginationState();
            clearAllSections();
            loadAllSections();
        });

        btnHomeFollowing.setOnClickListener(v -> switchFeedMode(true));
        btnHomeForYou.setOnClickListener(v -> switchFeedMode(false));

        // Apply initial active state
        updateFeedToggleUI();

        btnSeeAllTrending.setOnClickListener(v -> {
            if (isAdded() && getContext() != null)
                startActivity(new Intent(getContext(), ReelExploreActivity.class));
        });

        btnClearHistory.setOnClickListener(v -> clearWatchHistory());

        if (btnAddStory != null) {
            btnAddStory.setOnClickListener(v -> {
                if (!isAdded() || getContext() == null) return;
                try {
                    Class<?> cls = Class.forName("com.callx.app.compose.NewStatusActivity");
                    startActivity(new Intent(getContext(), cls));
                } catch (ClassNotFoundException e) {
                    startActivity(new Intent(getContext(), ReelCameraActivity.class));
                }
            });
        }

        if (btnHomeUpload != null) {
            btnHomeUpload.setOnClickListener(v -> {
                if (isAdded() && getContext() != null)
                    startActivity(new Intent(getContext(), ReelUploadActivity.class));
            });
        }

        // ── Instagram-style header buttons ──────────────────────────────────
        // "+" / New Post button — opens camera or upload
        if (btnNewPost != null) {
            btnNewPost.setOnClickListener(v -> {
                if (!isAdded() || getContext() == null) return;
                startActivity(new Intent(getContext(), ReelUploadActivity.class));
            });
        }

        // Notifications / Heart button — opens notifications feed
        if (btnNotifications != null) {
            btnNotifications.setOnClickListener(v -> {
                if (!isAdded() || getContext() == null) return;
                startActivity(new Intent(getContext(), ReelNotificationsActivity.class));
            });
        }

        // Feed title dropdown — "CallX ▾" tap shows Following / Favorites popup
        if (tvFeedTitle != null) {
            tvFeedTitle.setOnClickListener(v -> showFeedFilterDropdown(tvFeedTitle));
        }

        View btnSearch = getView() != null ? getView().findViewById(R.id.btn_home_search) : null;
        if (btnSearch != null) {
            btnSearch.setOnClickListener(v -> {
                if (isAdded() && getContext() != null)
                    startActivity(new Intent(getContext(), ReelSearchActivity.class));
            });
        }

        View btnSeeAllAct = getView() != null ? getView().findViewById(R.id.btn_see_all_activity) : null;
        if (btnSeeAllAct != null) {
            btnSeeAllAct.setOnClickListener(v -> {
                if (isAdded() && getContext() != null)
                    startActivity(new Intent(getContext(), ReelNotificationsActivity.class));
            });
        }

        View btnSeeAllSug = getView() != null ? getView().findViewById(R.id.btn_see_all_suggested) : null;
        if (btnSeeAllSug != null) {
            btnSeeAllSug.setOnClickListener(v -> {
                if (isAdded() && getContext() != null)
                    startActivity(new Intent(getContext(), ReelExploreActivity.class));
            });
        }

        loadMyAvatar();

        // ── Scroll-triggered auto-play ──────────────────────────────────────
        // v243: RecyclerView doesn't have NestedScrollView's onScrollChange,
        // but exposes the same pixel-accurate scroll-range/offset/extent
        // triplet used below to reconstruct the identical "remaining px to
        // bottom" math the old listener used.
        if (recyclerHome != null) {
            paginateThresholdPx = dpToPx(600);
            prefetchThresholdPx = dpToPx(1400);
            recyclerHome.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    // Instant-play trigger, frame-synced — see
                    // playVisibleFrameCallback doc.
                    if (!playVisibleCheckScheduled) {
                        playVisibleCheckScheduled = true;
                        android.view.Choreographer.getInstance().postFrameCallback(playVisibleFrameCallback);
                    }

                    // ★ Buttery scroll: promote the RecyclerView itself to a
                    // hardware layer for the duration of active scrolling —
                    // same idea the old NestedScrollView content root used,
                    // just retargeted at the new scroll container.
                    beginFeedScrollLayer();
                    scrollHandler.removeCallbacks(scrollSettleRunnable);
                    scrollHandler.postDelayed(scrollSettleRunnable, 180);

                    // ★ Instagram-style infinite scroll: once the user is
                    // within ~600dp of the bottom of the scrollable content,
                    // silently fetch the next page from Firebase and append
                    // it — the feed never hits a hard "end", same as IG.
                    // Scrolling up can never bring the bottom closer, so the
                    // pagination/prefetch math is skipped entirely for half
                    // of all scroll events.
                    if (dy <= 0) {
                        // v280: mirror image of the pagination check below,
                        // but toward the top — once the user scrolls back
                        // within paginateThresholdPx of the very top of a
                        // feed whose front has been trimmed, silently
                        // re-fetch the trimmed range instead of letting the
                        // feed appear to have hit a hard top it never had.
                        if (dy < 0 && frontTrimHighWaterTimestamp != null && !isLoadingNewerFeed) {
                            int offsetUp = rv.computeVerticalScrollOffset();
                            if (offsetUp < paginateThresholdPx) {
                                reloadTrimmedFrontPosts();
                            }
                        }
                        return;
                    }
                    int range  = rv.computeVerticalScrollRange();
                    int offset = rv.computeVerticalScrollOffset();
                    int extent = rv.computeVerticalScrollExtent();
                    int remaining = range - offset - extent;
                    if (remaining < paginateThresholdPx && !isLoadingMoreFeed && feedHasMore) {
                        loadMoreFeedPosts();
                    }
                    // Prefetch thumbnails/video for the *next* page a bit
                    // earlier than the fetch trigger itself, so by the time
                    // those cards actually scroll into view their media is
                    // already warm in cache — removes the "pop-in" jank new
                    // content otherwise causes.
                    if (remaining < prefetchThresholdPx && remaining >= paginateThresholdPx) {
                        prefetchUpcomingFeedMedia();
                    }
                }

                @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                    // Definitive settle check: guarantees the truly-final
                    // resting position is evaluated with zero delay the
                    // instant RecyclerView reports IDLE, on top of the
                    // per-frame checks already firing during the scroll —
                    // matches Instagram's "already playing by the time your
                    // thumb lifts" feel instead of waiting on a fixed timer.
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        android.view.Choreographer.getInstance().removeFrameCallback(playVisibleFrameCallback);
                        scrollHandler.removeCallbacks(playVisibleRunnable);
                        playVisibleCheckScheduled = false;
                        playMostVisibleCard();
                    }
                }
            });
        }
    }

    // ── Buttery scroll helpers ──────────────────────────────────────────

    /** Promotes the feed's scrolling content to a hardware layer for the
     *  duration of active scrolling/fling. No-op if already on, or if the
     *  view isn't bound yet. See beginFeedScrollLayer/endFeedScrollLayer
     *  pair used from the scroll listener above. */
    private void beginFeedScrollLayer() {
        if (isFeedScrolling || feedScrollContentRoot == null) return;
        isFeedScrolling = true;
        // A hardware layer is only a win while the content still fits in one
        // GPU texture. Once the feed has grown past MAX_HW_LAYER_PX — which
        // happens after just a few cards — the promotion is refused and all
        // that is left is the cost of re-rendering the subtree on every
        // layer-type flip, i.e. exactly the stutter it was meant to remove.
        // (isFeedScrolling itself is still tracked either way: the staged
        // card renderer and the settle timer both key off it.)
        if (feedScrollContentRoot.getHeight() > MAX_HW_LAYER_PX) return;
        isHwLayerOn = true;
        feedScrollContentRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    private void endFeedScrollLayer() {
        isFeedScrolling = false;
        if (!isHwLayerOn || feedScrollContentRoot == null) return;
        isHwLayerOn = false;
        feedScrollContentRoot.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    /** Warms Glide's cache for the next couple of not-yet-rendered posts
     *  (and, where available, primes video/thumbnail caches via the
     *  existing preloader infra) shortly before they're appended by
     *  infinite scroll — so they're already decoded/cached by the time the
     *  new page's cards actually enter view, instead of popping in raw. */
    private void prefetchUpcomingFeedMedia() {
        if (!isAdded() || getContext() == null || currentFeedPosts == null) return;
        int fromIndex = renderedReelIds.size();
        if (fromIndex >= currentFeedPosts.size()) return;
        // Called straight from onScrollChange, i.e. once per scrolled pixel
        // while the viewport sits in the prefetch band — without this guard a
        // single fling re-dispatched the same Glide preloads and the same
        // byte-range video preload hundreds of times, and all that redundant
        // work lands on the frames the fling needs.
        if (fromIndex == lastPrefetchFromIndex) return;
        lastPrefetchFromIndex = fromIndex;
        // Warms Glide's cache for thumbnails of the next few not-yet-rendered
        // posts so they're already decoded by the time their cards scroll in.
        int upTo = Math.min(currentFeedPosts.size(), fromIndex + 4);
        for (int i = fromIndex; i < upTo; i++) {
            ReelModel r = currentFeedPosts.get(i);
            if (r == null) continue;
            String thumb = r.effectiveThumbUrl();
            if (!thumb.isEmpty()) {
                // MUST match the card's own override() — Glide keys its cache
                // on the requested size, so a differently-sized preload warms
                // an entry the card never reads and decodes the bitmap twice.
                Glide.with(requireContext()).load(thumb).apply(FEED_IMAGE_OPTS)
                        .override(THUMB_DECODE_W, THUMB_DECODE_H).preload();
            }
        }
        // Reuses the same byte-range video preloader the Reels swipe feed
        // uses, pointed at the upcoming slice of the ranked feed list.
        // Thermal-gated: skip on HOT so we don't keep opening byte-range
        // downloads while the device is being throttled to cool down.
        if (videoPreloader != null && currentThermalLevel() != com.callx.app.player.ReelThermalManager.Level.HOT) {
            videoPreloader.preloadFrom(currentFeedPosts, fromIndex);
        }
        // Same precompute as attachPlayerToCard(), pointed at the freshly
        // appended page so its cards' counts/caption are already formatted
        // by the time they scroll into view.
        if (uiStatePrecomputer != null) uiStatePrecomputer.precomputeFrom(currentFeedPosts, fromIndex);
    }

    /** Current thermal level, defaulting to SAFE if the manager isn't up yet. */
    private com.callx.app.player.ReelThermalManager.Level currentThermalLevel() {
        return thermalManager != null
            ? thermalManager.getLevel()
            : com.callx.app.player.ReelThermalManager.Level.SAFE;
    }

    /**
     * Called by ReelThermalManager when device thermal / battery state changes.
     * Same handler as ReelsFragment: on HOT, cancel in-flight byte downloads
     * immediately instead of waiting for the next scroll/attach to notice —
     * videoPreloader/predictivePreloader already self-gate future calls via
     * ReelThermalManager internally, but a download already in flight when
     * the device crosses into HOT needs an explicit cancel.
     */
    private void onThermalChanged() {
        if (thermalManager == null) return;
        com.callx.app.player.ReelThermalManager.Level level = thermalManager.getLevel();
        android.util.Log.d("HomeFragment", "Thermal changed → " + level);
        if (level == com.callx.app.player.ReelThermalManager.Level.HOT) {
            if (videoPreloader != null) videoPreloader.cancelAll();
            if (predictivePreloader != null) predictivePreloader.cancelAll();
            android.util.Log.d("HomeFragment", "Thermal HOT: all preloads cancelled");
        }
    }

    // ── Auto-play helpers ─────────────────────────────────────────────────

    /**
     * ★★★ Ultra-advanced: the "screen just appeared" autoplay trigger.
     *
     * Three call sites — the very first cold-load render, onResume(), and
     * onTabBecameVisible() (switching into the Home tab) — used to just wait
     * a flat 300-400ms guess before calling playMostVisibleCard(), on the
     * theory that the freshly-(re)shown view hierarchy needed a moment to
     * finish laying out before getLocationOnScreen() would read correct
     * numbers. That flat guess is exactly the same anti-pattern the old
     * 120ms scroll debounce was: a fixed wait bigger than the work it's
     * covering for. A ViewTreeObserver.OnPreDrawListener fires at the
     * precise next predraw — i.e. the instant this frame's layout pass has
     * actually finished — which is normally one display frame (~16ms) away,
     * not 300-400ms, and is exactly the "is layout done yet" signal the old
     * delays were guessing at instead of listening for. This is the
     * difference between "reel is already playing before you've finished
     * looking at the screen" (real Instagram) and a visible quarter-second
     * of a frozen thumbnail.
     */
    private void scheduleImmediatePlayCheck() {
        if (recyclerHome == null || !isAdded()) return;
        final android.view.ViewTreeObserver vto = recyclerHome.getViewTreeObserver();
        if (!vto.isAlive()) { playMostVisibleCard(); return; }
        vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.ViewTreeObserver current = recyclerHome != null
                        ? recyclerHome.getViewTreeObserver() : vto;
                if (current.isAlive()) current.removeOnPreDrawListener(this);
                playMostVisibleCard();
                return true;
            }
        });
    }

    /**
     * Walk the tracked feed cards currently on/near screen; find the one with
     * the largest visible area, then attach the shared ExoPlayer to it.
     *
     * ★★ Ultra-advanced: bounded to RecyclerView's own known visible range
     * instead of walking every card from index 0 every single frame.
     * LinearLayoutManager already tracks its first/last visible adapter
     * position as a side effect of laying itself out — reading that back is
     * free — so reusing it here turns this from an O(entire loaded feed)
     * scan into an O(rows actually on/near screen) one. On a feed that's
     * grown to hundreds/thousands of loaded cards after a long scrolling
     * session, that is the difference between a few pointer comparisons and
     * genuinely walking the whole list every frame during scroll.
     */
    private void playMostVisibleCard() {
        if (!isAdded() || feedPlayer == null || feedCards.isEmpty()) return;
        int screenH = getResources().getDisplayMetrics().heightPixels;

        int scanFrom = 0, scanTo = feedCards.size() - 1;
        if (recyclerHome != null && recyclerHome.getLayoutManager() instanceof LinearLayoutManager) {
            LinearLayoutManager lm = (LinearLayoutManager) recyclerHome.getLayoutManager();
            int firstAdapterPos = lm.findFirstVisibleItemPosition();
            int lastAdapterPos  = lm.findLastVisibleItemPosition();
            if (firstAdapterPos != RecyclerView.NO_POSITION && lastAdapterPos != RecyclerView.NO_POSITION) {
                int minPost = Integer.MAX_VALUE, maxPost = -1;
                // A couple of rows of padding on each side: a fast fling can
                // cover more than one row between two consecutive checks, and
                // non-post rows (header/suggested/banner) sit between
                // ROW_POST entries, so this keeps the bound safe without
                // ever missing the real winner.
                int padStart = Math.max(0, firstAdapterPos - FEED_HEADER_OFFSET - 3);
                int padEnd   = Math.min(feedItems.size() - 1, lastAdapterPos - FEED_HEADER_OFFSET + 3);
                for (int p = padStart; p <= padEnd; p++) {
                    FeedRow row = feedItems.get(p);
                    if (row.type == ROW_POST && row.postIndex >= 0) {
                        if (row.postIndex < minPost) minPost = row.postIndex;
                        if (row.postIndex > maxPost) maxPost = row.postIndex;
                    }
                }
                if (maxPost >= 0) {
                    scanFrom = minPost;
                    scanTo   = Math.min(feedCards.size() - 1, maxPost);
                }
            }
        }

        int bestIdx = -1;
        int bestPx  = 0;
        for (int i = scanFrom; i <= scanTo; i++) {
            HomeFeedCard fc = feedCards.get(i);
            // v243: null now means "recycled / not currently bound to a live
            // ViewHolder" — same as the old detached/unmeasured checks below.
            if (fc == null) continue;
            View root = fc.rootView;
            if (root == null || root.getHeight() == 0 || root.getParent() == null) continue;
            root.getLocationOnScreen(visibilityLoc);
            int cardTop = visibilityLoc[1];
            int cardBot = cardTop + root.getHeight();
            int vis     = Math.max(0, Math.min(cardBot, screenH) - Math.max(cardTop, 0));
            if (vis > bestPx) { bestPx = vis; bestIdx = i; }
        }
        if (bestIdx >= 0 && bestIdx != currentPlayingIndex) attachPlayerToCard(bestIdx);
    }

    /**
     * Builds a MediaSource backed by the SAME on-disk cache
     * (UnifiedVideoCacheManager.Module.REELS) that the Reels tab's
     * AdaptiveStreamingManager uses. Cache key = video URL, so a reel
     * already downloaded/cached from either tab is served from disk in
     * the other — no duplicate downloads.
     */
    private MediaSource buildCachedMediaSource(String url) {
        if (!UnifiedVideoCacheManager.isInitialized()) {
            UnifiedVideoCacheManager.init(requireContext().getApplicationContext());
        }
        CacheDataSource.Factory cacheFactory =
            UnifiedVideoCacheManager.getFactory(UnifiedVideoCacheManager.Module.REELS);
        return new ProgressiveMediaSource.Factory(cacheFactory)
            .createMediaSource(MediaItem.fromUri(android.net.Uri.parse(url)));
    }

    /**
     * Detach the shared ExoPlayer from any previous card, then attach+play on the new one.
     *
     * ★ Instant-play swap: if prepareStandbyNext()/prepareStandbyPrev() already pre-buffered
     * THIS exact card on the standby player (the common case when scrolling
     * forward through the feed normally), we swap player instances instead
     * of cold-starting — no setMediaSource+prepare+buffer wait, so playback
     * starts as close to instantly as the decoder allows, matching the
     * "already loading before you get there" feel of Instagram/Reels tabs.
     * Falls back to the original cold-start path on a cache miss (fast
     * scroll past the predicted next card, or scrolling backward).
     */
    private void attachPlayerToCard(int index) {
        if (!isAdded() || feedPlayer == null || index >= feedCards.size()) return;
        // Detach old
        if (currentPlayingIndex >= 0 && currentPlayingIndex < feedCards.size()
                && feedCards.get(currentPlayingIndex) != null) {
            HomeFeedCard old = feedCards.get(currentPlayingIndex);
            // Persist how far the outgoing reel got BEFORE its player is torn
            // away, otherwise a scroll-away loses the resume position.
            flushActiveWatchProgress();
            endSpeedBoost(currentPlayingIndex);
            resetCardPlaybackChrome(old);
            if (old.playerView != null) old.playerView.setPlayer(null);
            if (old.endOverlay  != null) old.endOverlay.setVisibility(View.GONE);
        }
        if (watchTracker != null) watchTracker.onCardInactive();
        currentPlayingIndex = index;
        HomeFeedCard card = feedCards.get(index);
        // v243: the target card isn't currently bound to a live view (fully
        // recycled) — nothing to attach a player to. Shouldn't normally
        // happen since callers only pass indices found visible on screen,
        // but a fast fling could in principle race past this.
        if (card == null) { currentPlayingIndex = -1; return; }
        attachStartTimeMs = System.currentTimeMillis(); // TTFF measurement start

        // ── v177: preload the next few cards' video + thumbnails ahead of
        // time, same as Reels tab does on every onPageSelected. Uses the
        // SAME UnifiedVideoCacheManager.Module.REELS cache buildCachedMediaSource
        // reads from, so by the time the user scrolls here it's cache-hot.
        // ── Thermal-gated, same thresholds as ReelsFragment.onPageSelected ──
        // Byte preloading (video) is allowed up to LIGHT thermal; predictive
        // preload — the most aggressive of the three — only on SAFE. Thumb
        // preload is cheap local decode work so it isn't gated, matching
        // Reels tab's own gating.
        if (!currentFeedPosts.isEmpty() && index < currentFeedPosts.size()) {
            com.callx.app.player.ReelThermalManager.Level thermalLevel = currentThermalLevel();
            boolean canPreload = thermalLevel != com.callx.app.player.ReelThermalManager.Level.HOT;
            boolean canPredictivePreload = (thermalLevel == com.callx.app.player.ReelThermalManager.Level.SAFE
                || thermalLevel == com.callx.app.player.ReelThermalManager.Level.LIGHT);
            if (canPreload && videoPreloader != null) videoPreloader.preloadFrom(currentFeedPosts, index);
            if (thumbPreloader != null) thumbPreloader.preloadFrom(currentFeedPosts, index);
            if (canPredictivePreload && predictivePreloader != null) predictivePreloader.preloadSmartFrom(currentFeedPosts, index);
            // Precompute the next few cards' formatted counts/caption off the
            // main thread — same call ReelsFragment makes on every page
            // select, so those cards' text is a plain field read by the time
            // they're actually bound instead of a format() call on the frame
            // the scroll needs.
            if (uiStatePrecomputer != null) uiStatePrecomputer.precomputeFrom(currentFeedPosts, index);
        }

        if (card.videoUrl == null || card.videoUrl.isEmpty()) return;
        if (card.endOverlay != null) card.endOverlay.setVisibility(View.GONE);

        com.callx.app.player.AdaptiveStreamingManager mgr =
            com.callx.app.player.AdaptiveStreamingManager.get(requireContext());

        ExoPlayer oldActive = feedPlayer;
        if (standbyNextPlayer != null && standbyNextIndex == index && card.videoUrl.equals(standbyNextUrl)) {
            // Pre-buffered ahead of time (forward scroll, the common case) — promote.
            feedPlayer        = standbyNextPlayer;
            standbyNextPlayer = oldActive;
            standbyNextIndex  = -1;
            standbyNextUrl    = null;
        } else if (standbyPrevPlayer != null && standbyPrevIndex == index && card.videoUrl.equals(standbyPrevUrl)) {
            // Pre-buffered ahead of time (scrolled back up to rewatch) — promote.
            feedPlayer        = standbyPrevPlayer;
            standbyPrevPlayer = oldActive;
            standbyPrevIndex  = -1;
            standbyPrevUrl    = null;
        } else {
            // Cache miss — cold start on the current active player, same as before.
            mgr.applyQualityCap(feedPlayer, mgr.recommendedCap(requireContext()));
            feedPlayer.setMediaSource(buildCachedMediaSource(card.videoUrl));
            feedPlayer.prepare();
        }

        if (card.playerView != null) card.playerView.setPlayer(feedPlayer);
        // Never let the virtualizer pull the playing card out of the tree.
        if (feedWindowManager != null) feedWindowManager.setProtectedView(card.rootView);
        feedPlayer.setVolume(isMuted ? 0f : 1f);
        feedPlayer.setPlaybackSpeed(1f);

        // ── Autoplay gate ────────────────────────────────────────────────
        // Preparation/pre-buffering above still happens regardless of the
        // setting — only the decision to actually start is gated, so a
        // tap-to-play under "Off" is still instant.
        card.resumePending = true;
        boolean autoplay = autoplayPolicy.shouldAutoplay(getContext());
        if (autoplay) {
            userPausedActiveCard = false;
            feedPlayer.setPlayWhenReady(true);
            feedPlayer.play();
            if (watchTracker != null) watchTracker.onCardActive(card.reelId);
            showCardPlayOverlay(card, false);
            startProgressTicker();
        } else {
            userPausedActiveCard = true;
            feedPlayer.setPlayWhenReady(false);
            feedPlayer.pause();
            showCardPlayOverlay(card, true);
        }
        applyPendingResumeSeek();

        // Reset the reveal guard — the actual fade now happens in
        // configureFeedPlayerInstance()'s onRenderedFirstFrame, once a real
        // decoded frame is on screen (see revealCardThumbnailAfterFirstFrame).
        card.firstFrameRevealed = false;
        if (card.thumbView != null) {
            card.thumbView.animate().cancel();
            card.thumbView.setAlpha(1f);
            card.thumbView.setVisibility(View.VISIBLE);
        }

        // Speculative first-frame pre-render (same mechanism the Reels tab
        // uses): if this video is already substantially cached, decode its
        // actual frame-0 on a background thread and swap it in over the
        // server-generated thumbnail. When it lands before onRenderedFirstFrame
        // fires, the thumbnail IS the video's first frame, so the crossfade
        // below has nothing to visibly change — a true no-jump handoff.
        if (card.videoUrl != null && !card.videoUrl.isEmpty() && card.reelId != null) {
            // Synchronous check first — if thumbPreloader already decoded this
            // reel's frame ahead of time (see ReelThumbnailPreloader), use it
            // immediately instead of showing the server thumbnail at all.
            android.graphics.Bitmap prewarmed = com.callx.app.cache.ReelFirstFrameCache
                .get(requireContext()).getCached(card.reelId);
            if (prewarmed != null && card.thumbView != null) {
                card.thumbView.setImageBitmap(prewarmed);
            } else {
                com.callx.app.cache.ReelFirstFrameCache.get(requireContext())
                    .decodeFirstFrameAsync(card.reelId, card.videoUrl, bitmap -> {
                        if (card.thumbView != null && card.thumbView.getVisibility() == View.VISIBLE) {
                            card.thumbView.setImageBitmap(bitmap);
                        }
                    });
            }
        }

        // Pre-buffer both neighbours so either scroll direction gets an instant swap.
        prepareStandbyNext(index);
        prepareStandbyPrev(index);
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── Inline playback controls: scrub bar, tap play/pause, hold-2x ──────
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Wires a card's scrub bar to the shared feed player.
     *
     * The bar is expressed in permille (max 1000) rather than millis because
     * the same bar outlives several media items as the shared player hops
     * between cards — a fixed scale avoids having to re-max it every attach.
     * The thumb is invisible at rest and fades in only while dragging, which
     * is exactly what the full-screen player does (see thumb_reel_seek.xml).
     */
    private void setupCardScrubBar(HomeFeedCard card, int index) {
        final SeekBar bar = card.seekBar;
        if (bar == null) return;
        setSeekThumbVisible(bar, false);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || index != currentPlayingIndex || feedPlayer == null) return;
                long dur = feedPlayer.getDuration();
                if (dur <= 0) return;
                showScrubTime(card, (long) (dur * (progress / (float) sb.getMax())), dur);
            }
            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                // Scrubbing a card that isn't the playing one makes it the
                // playing one first, so the drag lands on the right media.
                if (index != currentPlayingIndex) attachPlayerToCard(index);
                card.isScrubbing = true;
                setSeekThumbVisible(sb, true);
                if (card.tvPosition != null) card.tvPosition.setVisibility(View.VISIBLE);
            }
            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                card.isScrubbing = false;
                setSeekThumbVisible(sb, false);
                if (card.tvPosition != null) card.tvPosition.setVisibility(View.GONE);
                if (index != currentPlayingIndex || feedPlayer == null) return;
                long dur = feedPlayer.getDuration();
                if (dur <= 0) return;
                // A manual seek supersedes any not-yet-applied resume point.
                card.resumePending = false;
                if (card.endOverlay != null) card.endOverlay.setVisibility(View.GONE);
                feedPlayer.seekTo((long) (dur * (sb.getProgress() / (float) sb.getMax())));
                if (!userPausedActiveCard) feedPlayer.play();
            }
        });
    }

    /** Tap-to-play / tap-to-pause for a feed card. */
    private void toggleCardPlayback(int index) {
        if (feedPlayer == null || index < 0 || index >= feedCards.size()) return;
        if (index != currentPlayingIndex) {
            // Tapping a non-active card promotes it; attach honours the
            // autoplay setting, so under "Off" force it to start anyway —
            // an explicit tap IS the user asking for playback.
            attachPlayerToCard(index);
            if (currentPlayingIndex == index && userPausedActiveCard) resumeActiveCard(index);
            return;
        }
        HomeFeedCard card = feedCards.get(index);
        if (card == null) return;
        if (feedPlayer.isPlaying()) {
            userPausedActiveCard = true;
            feedPlayer.pause();
            flushActiveWatchProgress();
            stopProgressTicker();
            if (watchTracker != null) watchTracker.onCardInactive();
            showCardPlayOverlay(card, true);
        } else {
            resumeActiveCard(index);
        }
    }

    /** Starts (or restarts) the active card, hiding the tap-to-play overlay. */
    private void resumeActiveCard(int index) {
        if (feedPlayer == null || index < 0 || index >= feedCards.size()) return;
        HomeFeedCard card = feedCards.get(index);
        if (card == null) return;
        userPausedActiveCard = false;
        if (card.endOverlay != null) card.endOverlay.setVisibility(View.GONE);
        feedPlayer.setPlayWhenReady(true);
        feedPlayer.play();
        if (watchTracker != null) watchTracker.onCardActive(card.reelId);
        showCardPlayOverlay(card, false);
        startProgressTicker();
    }

    /** Press-and-hold → 2x playback with a "2x ▶▶" chip, like the player. */
    private void beginSpeedBoost(int index) {
        if (feedPlayer == null || index != currentPlayingIndex) return;
        if (index < 0 || index >= feedCards.size()) return;
        HomeFeedCard card = feedCards.get(index);
        if (card == null || card.speedBoosted || !feedPlayer.isPlaying()) return;
        card.speedBoosted = true;
        feedPlayer.setPlaybackSpeed(HOLD_SPEED);
        if (card.speedChip != null) card.speedChip.setVisibility(View.VISIBLE);
        if (card.rootView != null) card.rootView.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS);
    }

    /** Finger lifted → back to 1x. Safe to call when no boost is active. */
    private void endSpeedBoost(int index) {
        if (index < 0 || index >= feedCards.size()) return;
        HomeFeedCard card = feedCards.get(index);
        if (card == null || !card.speedBoosted) return;
        card.speedBoosted = false;
        if (feedPlayer != null && index == currentPlayingIndex) feedPlayer.setPlaybackSpeed(1f);
        if (card.speedChip != null) card.speedChip.setVisibility(View.GONE);
    }

    /**
     * Applies the saved watch position for the active card once its duration
     * is actually known. Called both right after attach (warm/promoted player
     * — duration already available) and from STATE_READY (cold start).
     */
    private void applyPendingResumeSeek() {
        if (feedPlayer == null || watchTracker == null) return;
        if (currentPlayingIndex < 0 || currentPlayingIndex >= feedCards.size()) return;
        HomeFeedCard card = feedCards.get(currentPlayingIndex);
        if (card == null || !card.resumePending) return;
        long dur = feedPlayer.getDuration();
        if (dur <= 0) return;                 // retry on the next STATE_READY
        card.resumePending = false;
        long resumeMs = watchTracker.consumeResumePositionMs(card.reelId, dur);
        if (resumeMs > 0) feedPlayer.seekTo(resumeMs);
    }

    /** Drives the active card's scrub bar and the throttled progress writes. */
    private void startProgressTicker() {
        if (isTickerRunning) return;
        isTickerRunning = true;
        if (progressTicker == null) {
            progressTicker = new Runnable() {
                @Override public void run() {
                    if (!isTickerRunning) return;
                    updateActiveCardProgress();
                    scrollHandler.postDelayed(this, PROGRESS_TICK_MS);
                }
            };
        }
        scrollHandler.post(progressTicker);
    }

    private void stopProgressTicker() {
        isTickerRunning = false;
        if (progressTicker != null) scrollHandler.removeCallbacks(progressTicker);
    }

    private void updateActiveCardProgress() {
        if (!isAdded() || feedPlayer == null) return;
        if (currentPlayingIndex < 0 || currentPlayingIndex >= feedCards.size()) return;
        HomeFeedCard card = feedCards.get(currentPlayingIndex);
        if (card == null) return;
        long dur = feedPlayer.getDuration();
        long pos = feedPlayer.getCurrentPosition();
        if (dur <= 0) return;
        applyPendingResumeSeek();
        if (card.seekBar != null && !card.isScrubbing) {
            card.seekBar.setProgress(
                (int) Math.max(0, Math.min(card.seekBar.getMax(),
                    pos * card.seekBar.getMax() / dur)));
        }
        if (card.tvPosition != null && card.tvPosition.getVisibility() == View.VISIBLE
                && !card.isScrubbing) {
            card.tvPosition.setText(formatClock(pos) + " / " + formatClock(dur));
        }
        if (watchTracker != null && feedPlayer.isPlaying()) {
            watchTracker.onPlaybackProgress(card.reelId, pos, dur);
        }
    }

    /** Writes the active reel's current position immediately (no throttle). */
    private void flushActiveWatchProgress() {
        if (watchTracker == null || feedPlayer == null) return;
        if (currentPlayingIndex < 0 || currentPlayingIndex >= feedCards.size()) return;
        HomeFeedCard card = feedCards.get(currentPlayingIndex);
        if (card == null) return;
        long dur = feedPlayer.getDuration();
        if (dur <= 0) return;
        watchTracker.flushProgress(card.reelId, feedPlayer.getCurrentPosition(), dur);
    }

    private void showCardPlayOverlay(HomeFeedCard card, boolean show) {
        if (card == null || card.playOverlay == null) return;
        card.playOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Returns a card's chrome to its idle look when it stops being active. */
    private void resetCardPlaybackChrome(HomeFeedCard card) {
        if (card == null) return;
        card.isScrubbing   = false;
        card.resumePending = false;
        if (card.seekBar != null) {
            card.seekBar.setProgress(0);
            setSeekThumbVisible(card.seekBar, false);
        }
        if (card.tvPosition != null) card.tvPosition.setVisibility(View.GONE);
        if (card.speedChip  != null) card.speedChip.setVisibility(View.GONE);
        showCardPlayOverlay(card, false);
    }

    private void showScrubTime(HomeFeedCard card, long posMs, long durMs) {
        if (card.tvPosition == null) return;
        card.tvPosition.setVisibility(View.VISIBLE);
        card.tvPosition.setText(formatClock(posMs) + " / " + formatClock(durMs));
    }

    /**
     * The thumb drawable is shared across every inflated card, so it must be
     * mutated before its alpha is touched — otherwise showing one card's
     * handle would show all of them.
     */
    private void setSeekThumbVisible(SeekBar bar, boolean visible) {
        android.graphics.drawable.Drawable thumb = bar.getThumb();
        if (thumb == null) return;
        thumb.mutate().setAlpha(visible ? 255 : 0);
    }

    private static String formatClock(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        return (totalSec / 60) + ":" + String.format(Locale.US, "%02d", totalSec % 60);
    }

    /**
     * Crossfades a feed card's thumbnail over the now-rendering player
     * surface. Mirrors ReelPlayerController.revealThumbnailAfterFirstFrame()
     * in the Reels tab: driven by onRenderedFirstFrame (never a buffering/
     * ready state, which can arrive a frame or two before anything is
     * actually drawn), with a short crossfade tight enough to read as a
     * single continuous frame instead of a visible cut.
     */
    private void revealCardThumbnailAfterFirstFrame(HomeFeedCard card) {
        if (card == null || card.firstFrameRevealed || card.thumbView == null) return;
        card.firstFrameRevealed = true;
        ImageView thumb = card.thumbView;
        if (thumb.getVisibility() != View.VISIBLE) return;
        thumb.animate().cancel();
        thumb.setAlpha(1f);
        thumb.animate()
            .alpha(0f)
            .setDuration(HOME_THUMB_CROSSFADE_MS)
            .withEndAction(() -> {
                thumb.setVisibility(View.INVISIBLE);
                thumb.setAlpha(1f);
            })
            .start();
    }

    /**
     * Pre-buffers the next card's video on a standby ExoPlayer while the
     * current card plays — the same "warm the next player ahead of time"
     * idea ExoPlayerPool uses for the Reels swipe feed. Reuses whatever
     * instance is already sitting in the standbyNext slot (very often the
     * just-demoted former active/prev player, from attachPlayerToCard's
     * role rotation) instead of constructing a new ExoPlayer, and applies
     * the current network-recommended quality cap so the pre-buffered
     * rendition actually matches what the connection can sustain — buffering
     * a 1080p track ahead of time on a slow connection would defeat the
     * whole point by taking too long to reach playable state.
     */
    private void prepareStandbyNext(int fromIndex) {
        if (!isAdded() || getContext() == null || feedCards.isEmpty()) return;
        int nextIndex = fromIndex + 1;
        if (nextIndex >= feedCards.size()) return;
        HomeFeedCard next = feedCards.get(nextIndex);
        // v243: null means that post's card isn't currently bound to a live
        // view (recycled/off-screen) — nothing to pre-buffer into yet; the
        // next attach cycle will retry once it scrolls close enough to bind.
        if (next == null || next.videoUrl == null || next.videoUrl.isEmpty()) return;
        if (standbyNextIndex == nextIndex && next.videoUrl.equals(standbyNextUrl)) return; // already prepared

        if (standbyNextPlayer == null) {
            standbyNextPlayer = com.callx.app.player.ExoPlayerPool.get(requireContext()).acquire();
            configureFeedPlayerInstance(standbyNextPlayer);
        }
        com.callx.app.player.AdaptiveStreamingManager mgr =
            com.callx.app.player.AdaptiveStreamingManager.get(requireContext());
        standbyNextPlayer.stop();
        standbyNextPlayer.clearMediaItems();
        standbyNextPlayer.setVolume(0f);
        standbyNextPlayer.setPlayWhenReady(false);
        mgr.applyQualityCap(standbyNextPlayer, mgr.recommendedCap(requireContext()));
        standbyNextPlayer.setMediaSource(buildCachedMediaSource(next.videoUrl));
        standbyNextPlayer.prepare();
        standbyNextIndex = nextIndex;
        standbyNextUrl   = next.videoUrl;
    }

    /**
     * Same idea as prepareStandbyNext(), but for the card ONE ABOVE the
     * currently active one — covers the equally common "scroll up to
     * rewatch" pattern instantly instead of only optimizing forward scroll.
     */
    private void prepareStandbyPrev(int fromIndex) {
        if (!isAdded() || getContext() == null || feedCards.isEmpty()) return;
        int prevIndex = fromIndex - 1;
        if (prevIndex < 0) return;
        HomeFeedCard prev = feedCards.get(prevIndex);
        if (prev == null || prev.videoUrl == null || prev.videoUrl.isEmpty()) return;
        if (standbyPrevIndex == prevIndex && prev.videoUrl.equals(standbyPrevUrl)) return; // already prepared

        if (standbyPrevPlayer == null) {
            standbyPrevPlayer = com.callx.app.player.ExoPlayerPool.get(requireContext()).acquire();
            configureFeedPlayerInstance(standbyPrevPlayer);
        }
        com.callx.app.player.AdaptiveStreamingManager mgr =
            com.callx.app.player.AdaptiveStreamingManager.get(requireContext());
        standbyPrevPlayer.stop();
        standbyPrevPlayer.clearMediaItems();
        standbyPrevPlayer.setVolume(0f);
        standbyPrevPlayer.setPlayWhenReady(false);
        mgr.applyQualityCap(standbyPrevPlayer, mgr.recommendedCap(requireContext()));
        standbyPrevPlayer.setMediaSource(buildCachedMediaSource(prev.videoUrl));
        standbyPrevPlayer.prepare();
        standbyPrevIndex = prevIndex;
        standbyPrevUrl   = prev.videoUrl;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    private boolean isFirstResume = true;

    @Override public void onResume() {
        super.onResume();
        // NOTE: prewarmHomeTab() in ReelsFragment now attaches this fragment
        // (and calls onResume() on it) immediately in the background, while
        // home_container is still View.GONE — long before the user actually
        // taps the Home nav tab. getView().isShown() (unlike the fragment's
        // own onResume/added state) correctly returns false while any
        // ancestor is GONE, so this guards against silently autoplaying a
        // feed video's audio behind the still-visible Reels player.
        boolean actuallyVisible = getView() != null && getView().isShown();
        if (feedPlayer != null && actuallyVisible) {
            // A card the user deliberately paused (or that never started
            // because autoplay is Off / off-Wi-Fi) must stay paused across a
            // resume — resuming it here would defeat the setting.
            if (currentPlayingIndex >= 0) {
                if (!userPausedActiveCard) { feedPlayer.play(); startProgressTicker(); }
            } else if (!currentFeedPosts.isEmpty()) {
                scheduleImmediatePlayCheck();
            }
        }
        // The autoplay preference can be changed in Reel Feed Settings while
        // this fragment is only stopped, so re-read it on every resume.
        autoplayPolicy.load(safeMyUid(), null);
        // Also listen live while Home is on-screen: the moment ANY screen
        // marks a story seen, statusSeen/{myUid} changes in Firebase and
        // StatusCacheManager's real-time listener fires this observer —
        // so the ring updates immediately, without even needing to leave
        // and come back to Home. Same shared cache/pattern already used
        // by StatusFragment, ChatListAdapter, CallHistoryAdapter, etc.
        if (getContext() != null) {
            com.callx.app.cache.StatusCacheManager.getInstance(requireContext())
                .addObserver(storyRingObserver);
        }
    }

    // ★ Fires whenever StatusCacheManager's status/seen data changes anywhere
    // in the app (e.g. a story was just viewed) — refreshes just the top
    // story row so its ring (gradient vs. gray) is always current.
    private final com.callx.app.cache.StatusCacheManager.StatusDataObserver storyRingObserver = () -> {
        if (isAdded() && getActivity() != null) getActivity().runOnUiThread(this::refreshStoryRow);
    };

    @Override public void onPause() {
        super.onPause();
        endSpeedBoost(currentPlayingIndex);
        flushActiveWatchProgress();
        stopProgressTicker();
        if (watchTracker != null) watchTracker.onCardInactive();
        if (feedPlayer != null) feedPlayer.pause();
        // stop() drops a standby player back to STATE_IDLE (needs a fresh
        // prepare() before it's playable again), so the tracked index/url
        // must be cleared too or the next attachPlayerToCard() would try to
        // promote an un-prepared player. prepareStandbyNext()/Prev() re-warm
        // both normally once the fragment resumes.
        if (standbyNextPlayer != null) { standbyNextPlayer.stop(); standbyNextIndex = -1; standbyNextUrl = null; }
        if (standbyPrevPlayer != null) { standbyPrevPlayer.stop(); standbyPrevIndex = -1; standbyPrevUrl = null; }
        // Don't waste bandwidth preloading cards the user can't see right now.
        if (videoPreloader != null) videoPreloader.cancelAll();
        if (getContext() != null) {
            com.callx.app.cache.StatusCacheManager.getInstance(requireContext())
                .removeObserver(storyRingObserver);
        }
    }

    /**
     * Called by ReelsFragment.showHomeTab() right after it flips
     * home_container to VISIBLE. Because prewarmHomeTab() now attaches this
     * fragment (and fires its real onResume()) well before that — while
     * still hidden, guarded off from autoplaying by the isShown() check
     * above — this is what actually starts feed-card video playback the
     * moment the user genuinely switches to the Home tab, exactly like
     * onResume() used to when this fragment was only ever created already-visible.
     */
    public void onTabBecameVisible() {
        if (feedPlayer != null) {
            if (currentPlayingIndex >= 0) {
                if (!userPausedActiveCard) { feedPlayer.play(); startProgressTicker(); }
            } else if (!currentFeedPosts.isEmpty()) {
                scheduleImmediatePlayCheck();
            }
        }
        // ★ INSTAGRAM-LEVEL FIX: previously the top story row was only ever
        // built once (onCreateView) or on pull-to-refresh — so viewing a
        // story elsewhere in the app (StatusViewerActivity, a reel_story via
        // the reels player, etc.) and coming back to Home never updated the
        // ring here; it silently stayed on the old gradient until the user
        // manually pulled to refresh or left/reopened the app. Re-running
        // loadStories() each time the user genuinely switches back to this
        // tab keeps it in sync. (Skip the very first time it becomes
        // visible — onCreateView's loadAllSections() already just built it
        // during the background warm-up; refreshing again here would just
        // be a redundant clear+reload flash the first time Home is opened.)
        if (isFirstResume) isFirstResume = false;
        else refreshStoryRow();
    }

    /**
     * Called by ReelsFragment.hideHomeTab() right after it flips
     * home_container back to GONE (user switched back to the Reels feed).
     * Mirrors onPause() so a Home feed video never keeps playing audio
     * behind the Reels player once the user has swiped away from it.
     */
    public void onTabBecameHidden() {
        endSpeedBoost(currentPlayingIndex);
        flushActiveWatchProgress();
        stopProgressTicker();
        if (watchTracker != null) watchTracker.onCardInactive();
        if (feedPlayer != null) feedPlayer.pause();
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        stopRealtimeNewPostsListener();
        scrollHandler.removeCallbacks(playVisibleRunnable);
        android.view.Choreographer.getInstance().removeFrameCallback(playVisibleFrameCallback);
        scrollHandler.removeCallbacks(scrollSettleRunnable);
        cancelStagedFeedRender();
        if (cardPool != null) { cardPool.release(); cardPool = null; }
        flushActiveWatchProgress();
        stopProgressTicker();
        progressTicker = null;
        if (watchTracker != null) { watchTracker.release(); watchTracker = null; }
        userPausedActiveCard = false;
        feedScrollContentRoot = null;
        // Hand back to the SHARED pool (strips our tracked listener, resets
        // playback state) instead of a hard player.release() — keeps the
        // instance warm for whichever tab (Home or Reels) next needs one,
        // same as ReelsFragment does on ordinary tab-away teardown. Falls
        // back to a raw release() in the unlikely case context is already
        // gone here, so a player is never silently leaked.
        Context poolCtx = getContext();
        com.callx.app.player.ExoPlayerPool pool = poolCtx != null
            ? com.callx.app.player.ExoPlayerPool.get(poolCtx) : null;
        if (feedPlayer != null) {
            if (pool != null) pool.release(feedPlayer); else feedPlayer.release();
            feedPlayer = null;
        }
        if (standbyNextPlayer != null) {
            if (pool != null) pool.release(standbyNextPlayer); else standbyNextPlayer.release();
            standbyNextPlayer = null;
        }
        if (standbyPrevPlayer != null) {
            if (pool != null) pool.release(standbyPrevPlayer); else standbyPrevPlayer.release();
            standbyPrevPlayer = null;
        }
        standbyNextIndex = -1; standbyNextUrl = null;
        standbyPrevIndex = -1; standbyPrevUrl = null;
        attachStartTimeMs = 0L;
        // Release our listener only — ReelThermalManager is a process-lifetime
        // singleton shared with the Reels tab, so it must NOT be torn down here.
        if (thermalManager != null) {
            thermalManager.removeChangeListener(thermalChangeListener);
            thermalManager = null;
        }
        if (videoPreloader != null) { videoPreloader.shutdown(); videoPreloader = null; }
        thumbPreloader      = null;
        predictivePreloader = null;
        if (uiStatePrecomputer != null) { uiStatePrecomputer.shutdown(); uiStatePrecomputer = null; }
        // ReelOfflineManager is a process-lifetime singleton shared with the
        // Reels tab (holds the retry scheduler + offline catalog) — just
        // drop our reference, don't tear the manager itself down.
        offlineManager = null;
        feedCards.clear();
        currentFeedPosts = new ArrayList<>();
        currentPlayingIndex = -1;
        if (suggestedReelsPeekController != null) {
            suggestedReelsPeekController.dismiss();
            suggestedReelsPeekController = null;
        }

        // ── v281: Shutdown ultra-advanced performance coordinator ──────────
        // Cancels pending batches, clears caches, tears down background threads.
        if (ultraOptimizer != null) {
            ultraOptimizer.shutdown();
            ultraOptimizer = null;
        }
    }

    /**
     * Instagram-style dropdown on the header title — shows Following / Favorites options.
     */
    private void showFeedFilterDropdown(android.view.View anchor) {
        if (!isAdded() || getContext() == null) return;
        android.widget.PopupMenu popup = new android.widget.PopupMenu(requireContext(), anchor);

        // Following option
        popup.getMenu().add(0, 1, 0, "Following")
            .setIcon(android.R.drawable.ic_menu_my_calendar);
        // For You option — was missing entirely; this is the only path that
        // ever sets isFollowingMode = false, so without it the ranked
        // For-You feed (and the inline "Suggested for you"/"Suggested reels"
        // rows, which only insert in For-You mode) was unreachable from the UI.
        popup.getMenu().add(0, 3, 0, "For You")
            .setIcon(android.R.drawable.ic_menu_recent_history);
        // Favorites option
        popup.getMenu().add(0, 2, 0, "Favorites")
            .setIcon(android.R.drawable.btn_star_big_off);

        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                // Following feed
                if (tvFeedTitle != null) tvFeedTitle.setText("Following  ▾");
                switchFeedMode(true);
            } else if (item.getItemId() == 3) {
                // For You feed — ranked, with inline suggested rows mixed in
                if (tvFeedTitle != null) tvFeedTitle.setText("For You  ▾");
                switchFeedMode(false);
            } else if (item.getItemId() == 2) {
                // Favorites feed (same as Following for now, filtered differently later)
                if (tvFeedTitle != null) tvFeedTitle.setText("Favorites  ▾");
                switchFeedMode(true); // reuse following mode with favorites filter
            }
            return true;
        });
        popup.show();
    }

    private void switchFeedMode(boolean following) {
        isFollowingMode = following;
        updateFeedToggleUI();
        resetFeedPaginationState();
        cancelStagedFeedRender();
        clearFeedRows();
        showFeedLoading(true);
        showFeedEmpty(false);
        loadFeed();
    }

    /** v243: wipes the recycled middle section of the feed (posts +
     *  interleaved rows + banner/loading/empty/footer) — used by a mode
     *  switch or pull-to-refresh, both of which are about to fully rebuild
     *  it from a fresh Firebase query. Header/footer sections are untouched. */
    private void clearFeedRows() {
        int oldSize = feedItems.size();
        feedItems.clear();
        feedCards.clear();
        currentFeedPosts = new ArrayList<>();
        currentPlayingIndex = -1;
        if (feedAdapter != null && oldSize > 0) {
            feedAdapter.notifyItemRangeRemoved(FEED_HEADER_OFFSET, oldSize);
        }
    }

    /** Clears pagination/ranking/real-time state before a fresh feed load
     *  (mode switch, pull-to-refresh, or "N new posts" tap). */
    private void resetFeedPaginationState() {
        stopRealtimeNewPostsListener();
        isLoadingMoreFeed   = false;
        feedHasMore         = true;
        oldestFeedTimestamp = null;
        newestFeedTimestamp = null;
        frontTrimHighWaterTimestamp = null;
        isLoadingNewerFeed = false;
        renderedReelIds.clear();
        lastPrefetchFromIndex = -1;
        postsSincePeopleYouMayLike = 0;
        postsSinceSuggestedReels = 0;
        newPostsPending = 0;
        feedLoadMoreFooter = null;
        hideNewPostsBanner();
    }

    private void updateFeedToggleUI() {
        if (btnHomeFollowing != null) {
            btnHomeFollowing.setAlpha(isFollowingMode ? 1f : 0.55f);
            btnHomeFollowing.setTypeface(null,
                isFollowingMode ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        if (btnHomeForYou != null) {
            btnHomeForYou.setAlpha(isFollowingMode ? 0.55f : 1f);
            btnHomeForYou.setTypeface(null,
                isFollowingMode ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
        }
        // Slide underline indicator
        if (vFeedIndicator != null && btnHomeFollowing != null && btnHomeForYou != null) {
            View target = isFollowingMode ? btnHomeFollowing : btnHomeForYou;
            target.post(() -> {
                if (vFeedIndicator == null) return;
                vFeedIndicator.animate()
                    .translationX(target.getLeft())
                    .setDuration(180)
                    .start();
                android.view.ViewGroup.LayoutParams lp = vFeedIndicator.getLayoutParams();
                lp.width = target.getWidth();
                vFeedIndicator.setLayoutParams(lp);
            });
        }
    }

    private void clearAllSections() {
        cancelStagedFeedRender();
        clearFeedRows();
        if (containerTrending != null)         clearContainerKeepLoader(containerTrending);
        if (containerFriendsActivity != null)  clearContainerKeepLoader(containerFriendsActivity);
        if (containerContinueWatching != null) clearContainerKeepLoader(containerContinueWatching);
        if (containerSuggestedCreators != null)clearContainerKeepLoader(containerSuggestedCreators);
        if (containerStories != null)          clearStoriesKeepAddButton();
        if (pbTrending != null)   pbTrending.setVisibility(View.VISIBLE);
        if (pbActivity != null)   pbActivity.setVisibility(View.VISIBLE);
        if (pbContinue != null)   pbContinue.setVisibility(View.VISIBLE);
        if (pbSuggested != null)  pbSuggested.setVisibility(View.VISIBLE);
    }

    private void clearContainerKeepLoader(LinearLayout container) {
        if (container == null) return;
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (!(child instanceof ProgressBar)) container.removeViewAt(i);
        }
    }

    /** Remove story avatars but keep the "Add Story" button at index 0 */
    private void clearStoriesKeepAddButton() {
        if (containerStories == null) return;
        for (int i = containerStories.getChildCount() - 1; i >= 1; i--) {
            containerStories.removeViewAt(i);
        }
    }

    private void loadAllSections() {
        loadStories();
        loadFeed();
        loadTrending();
        loadFriendsActivity();
        loadContinueWatching();
        loadSuggestedCreators();
    }

    // ── Stories ───────────────────────────────────────────────────────────
    /**
     * Loads stories from contacts:
     *  1. Fetch contacts list
     *  2. For each contact, check if they have an active (< 24h) status
     *  3. Check statusSeen/{myUid}/{ownerUid}/{statusId} to determine unseen state
     *  4. Collect StoryEntry objects, sort unseen first, then render
     */
    private void loadStories() {
        String myUid = safeMyUid();
        if (myUid == null) return;

        // First, load my own seen map for all contacts in one pass
        FirebaseUtils.getStatusSeenRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot seenSnap) {
                    if (!isAdded() || getContext() == null) return;
                    // Build seen set: ownerUid → set<statusId> already seen
                    Map<String, Set<String>> seenMap = new HashMap<>();
                    for (DataSnapshot ownerNode : seenSnap.getChildren()) {
                        Set<String> ids = new HashSet<>();
                        for (DataSnapshot idNode : ownerNode.getChildren()) ids.add(idNode.getKey());
                        seenMap.put(ownerNode.getKey(), ids);
                    }
                    loadContactStoriesWithSeenMap(seenMap, myUid);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    loadContactStoriesWithSeenMap(new HashMap<>(), myUid);
                }
            });
    }

    private void loadContactStoriesWithSeenMap(Map<String, Set<String>> seenMap, String myUid) {
        FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded() || getContext() == null) return;
                List<String> contactUids = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) contactUids.add(c.getKey());
                if (contactUids.isEmpty()) return;
                collectStoryEntries(contactUids, 0, seenMap, new ArrayList<>());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void collectStoryEntries(List<String> uids, int index,
                                     Map<String, Set<String>> seenMap,
                                     List<StoryEntry> collected) {
        // ★ Ultra-advanced optimization: the old implementation walked
        // contacts ONE AT A TIME and, for each contact, chained
        // getUserRef() → (only then) getUserStatusRef() → (only then) the
        // NEXT contact's pair of reads. For 15 contacts that's up to 30
        // fully sequential Firebase round-trips standing between opening
        // the Home tab and the very first row (the stories bar) rendering
        // anything at all — by far the single biggest source of Home's
        // cold-start latency. Delegates to the parallel implementation
        // below; kept as a thin wrapper so any other call site still
        // compiles unchanged.
        collectStoryEntriesParallel(uids, seenMap);
    }

    /** Fires every contact's profile read AND status read for ALL contacts
     *  (capped at 15, same limit as before) CONCURRENTLY instead of one
     *  contact — and one read — at a time. Neither a contact's own profile
     *  read depends on its status read (or vice versa), and no contact's
     *  reads depend on any other contact's, so nothing here actually needed
     *  to be sequential in the first place.
     *
     *  Firebase's Android SDK always delivers ValueEventListener callbacks
     *  on the main thread, one at a time, even though the underlying network
     *  requests race in parallel — so the plain int counters below (no
     *  AtomicInteger, no synchronization) are safe: every mutation happens
     *  from the same thread, just not necessarily in request order.
     *
     *  Results are written into an index-addressed `slots` array (not
     *  appended as each contact resolves) specifically so the final sort
     *  ties break in the SAME original-contact-list order the old
     *  sequential version produced — Collections.sort is stable, so the
     *  only thing that has to be preserved is insertion order into the list
     *  being sorted, which index-addressing guarantees regardless of which
     *  contact's network reads happen to land first. */
    private void collectStoryEntriesParallel(List<String> uids, Map<String, Set<String>> seenMap) {
        if (!isAdded() || getContext() == null) return;
        final int limit = Math.min(uids.size(), 15);
        if (limit == 0) return;

        final StoryEntry[] slots = new StoryEntry[limit];
        final List<StoryEntry> collected = new ArrayList<>();
        final int[] contactsRemaining = { limit };
        final long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);

        for (int idx = 0; idx < limit; idx++) {
            final int slot = idx;
            final String uid = uids.get(idx);
            final String[] nameHolder  = new String[1];
            final String[] photoHolder = new String[1];
            final DataSnapshot[] statusHolder = new DataSnapshot[1];
            final boolean[] userDone   = { false };
            final boolean[] statusDone = { false };

            Runnable maybeFinishContact = () -> {
                if (!userDone[0] || !statusDone[0]) return; // wait for both this contact's reads
                boolean hasActive = false;
                boolean allSeen   = true;
                Set<String> mySeenForOwner = seenMap.containsKey(uid) ? seenMap.get(uid) : new HashSet<>();
                if (statusHolder[0] != null) {
                    for (DataSnapshot s : statusHolder[0].getChildren()) {
                        Long ts = s.child("timestamp").getValue(Long.class);
                        if (ts == null || ts <= cutoff) continue;
                        hasActive = true;
                        if (mySeenForOwner == null || !mySeenForOwner.contains(s.getKey())) {
                            allSeen = false; // at least one unseen
                        }
                    }
                }
                if (hasActive) {
                    // ★ INSTAGRAM FIX: gradient ring is driven purely by
                    // "has this been seen" (see original note this replaces).
                    slots[slot] = new StoryEntry(uid, nameHolder[0], photoHolder[0], !allSeen, false);
                }
                contactsRemaining[0]--;
                if (contactsRemaining[0] == 0) {
                    if (!isAdded() || getContext() == null) return;
                    for (StoryEntry e : slots) if (e != null) collected.add(e);
                    collected.sort((a, b) -> Boolean.compare(!a.hasUnseen, !b.hasUnseen));
                    for (StoryEntry entry : collected) addStoryView(entry);
                }
            };

            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    nameHolder[0] = snap.child("name").getValue(String.class);
                    String _photo = snap.child("photoUrl").getValue(String.class);
                    String _thumb = snap.child("thumbUrl").getValue(String.class);
                    photoHolder[0] = (_thumb != null && !_thumb.isEmpty()) ? _thumb : _photo;
                    userDone[0] = true;
                    maybeFinishContact.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    userDone[0] = true;
                    maybeFinishContact.run();
                }
            });

            FirebaseUtils.getUserStatusRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot statusSnap) {
                    if (!isAdded() || getContext() == null) return;
                    statusHolder[0] = statusSnap;
                    statusDone[0] = true;
                    maybeFinishContact.run();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    statusDone[0] = true;
                    maybeFinishContact.run();
                }
            });
        }
    }

    private void addStoryView(StoryEntry entry) {
        if (!isAdded() || getContext() == null || containerStories == null) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded() || getContext() == null) return;
            View storyView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_home_story, containerStories, false);

            CircleImageView avatar  = storyView.findViewById(R.id.iv_story_avatar);
            TextView tvName         = storyView.findViewById(R.id.tv_story_name);
            ImageView ivSeenRing    = storyView.findViewById(R.id.iv_story_seen_ring);

            tvName.setText(entry.name != null ? entry.name : "User");

            // ★ Instagram-style: gradient ring for ALL stories that have unseen content
            ImageView ivGradientRing = storyView.findViewById(R.id.iv_reel_story_gradient_ring);
            // FIX v39: story_ring_insta_gradient.xml had a visible seam (XML sweep
            // gradient only supports 3 stops, doesn't loop back cleanly) — swapped
            // for the seamless StoryRingGradientDrawable used across the app.
            if (ivGradientRing != null) {
                ivGradientRing.setImageDrawable(
                        com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(3f,
                                getResources().getDisplayMetrics().density));
            }

            if (entry.hasUnseen) {
                // Gradient pink/orange ring — same as Instagram, for any unseen story
                if (ivGradientRing != null) ivGradientRing.setVisibility(View.VISIBLE);
                avatar.setBorderColor(0xFFFFFFFF);
                avatar.setBorderWidth(dpToPx(3));
                if (ivSeenRing != null) ivSeenRing.setVisibility(View.GONE);
            } else {
                // Gray ring for all-seen stories
                if (ivGradientRing != null) ivGradientRing.setVisibility(View.GONE);
                avatar.setBorderColor(0xFF888888);
                avatar.setBorderWidth(dpToPx(2));
                if (ivSeenRing != null) {
                    ivSeenRing.setVisibility(View.VISIBLE);
                    ivSeenRing.setColorFilter(0xFF888888, android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }

            if (entry.photo != null && !entry.photo.isEmpty()) {
                Glide.with(requireContext()).load(entry.photo)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person)
                    .override(96, 96)
                    .into(avatar);
            }

            // ✅ Open StatusViewerActivity (cross-module via Class.forName)
            storyView.setOnClickListener(v -> openStatusViewer(entry.uid, entry.name));

            containerStories.addView(storyView);
        });
    }

    /**
     * Opens StatusViewerActivity via Class.forName so feature-reels doesn't need a
     * compile dependency on feature-status. Falls back to UserReelsActivity if the
     * status module isn't present in the APK (shouldn't happen in production).
     */
    private void openStatusViewer(String ownerUid, String ownerName) {
        if (!isAdded() || getContext() == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.viewer.StatusViewerActivity");
            Intent i = new Intent(getContext(), cls);
            i.putExtra("ownerUid",  ownerUid);
            i.putExtra("ownerName", ownerName != null ? ownerName : "");
            startActivity(i);
        } catch (ClassNotFoundException e) {
            // Fallback: open the user's reel profile
            Intent i = new Intent(getContext(), UserReelsActivity.class);
            i.putExtra(UserReelsActivity.EXTRA_UID,  ownerUid);
            i.putExtra(UserReelsActivity.EXTRA_NAME, ownerName);
            startActivity(i);
        }
    }

    // ── Feed ──────────────────────────────────────────────────────────────

    private void loadFeed() {
        showFeedLoading(true);
        String myUid = safeMyUid();

        if (isFollowingMode && myUid != null) {
            FirebaseUtils.getReelFollowsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    Set<String> followedUids = new HashSet<>();
                    for (DataSnapshot s : snap.getChildren()) followedUids.add(s.getKey());

                    if (followedUids.isEmpty()) {
                        showFeedLoading(false);
                        showFeedEmpty(true);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                        return;
                    }
                    loadReelsForFeed(followedUids, myUid);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    showFeedLoading(false);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                }
            });
        } else {
            final String uid = myUid;
            FirebaseUtils.getReelsRef()
                .orderByChild("timestamp")
                .limitToLast(FEED_FETCH_BATCH)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!isAdded() || getContext() == null) return;
                        List<ReelModel> posts = new ArrayList<>();
                        for (DataSnapshot s : snap.getChildren()) {
                            ReelModel r = s.getValue(ReelModel.class);
                            if (r != null) {
                                if (r.reelId == null) r.reelId = s.getKey();
                                posts.add(r);
                            }
                        }
                        updateFeedTimestampBounds(posts);
                        feedHasMore = posts.size() >= FEED_FETCH_BATCH;
                        // PERF: fetch the current user's full follow-set ONCE here
                        // instead of each card independently querying Firebase for
                        // its own follow status (was: up to 10 extra network round
                        // trips per feed render in For-You mode). The same set also
                        // feeds the ranking score below (relationship signal).
                        if (uid != null) {
                            FirebaseUtils.getReelFollowsRef(uid)
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(@NonNull DataSnapshot fSnap) {
                                        Set<String> followedUids = new HashSet<>();
                                        for (DataSnapshot s : fSnap.getChildren()) followedUids.add(s.getKey());
                                        cachedFollowedUids = followedUids;
                                        // ★ Instagram-level ranking: engagement × recency,
                                        // relationship + real creator watch-affinity,
                                        // watch-time/completion signal, topic
                                        // personalization (Not Interested / Preferred
                                        // topics), discovery boost for new creators, and a
                                        // diversity re-rank pass so one creator can't
                                        // cluster several cards in a row. See
                                        // FeedRankingEngine for the full signal breakdown.
                                        RankingProfile.load(uid, followedUids, profile -> {
                                            if (!isAdded() || getContext() == null) return;
                                            cachedRankingProfile = profile;
                                            List<ReelModel> ranked =
                                                    FeedRankingEngine.buildRankedFeed(posts, profile);
                                            renderFeedPosts(ranked, uid, followedUids);
                                        });
                                    }
                                    @Override public void onCancelled(@NonNull DatabaseError e) {
                                        posts.sort((a, b) -> Float.compare(b.trendingScore(), a.trendingScore()));
                                        renderFeedPosts(posts, uid, new HashSet<>());
                                    }
                                });
                        } else {
                            posts.sort((a, b) -> Float.compare(b.trendingScore(), a.trendingScore()));
                            renderFeedPosts(posts, uid, new HashSet<>());
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        showFeedLoading(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    }
                });
        }
    }

    /**
     * @deprecated Ranking now lives in {@link FeedRankingEngine#score} so the
     * Home tab and Reels tab share one implementation instead of two
     * independently-drifting copies. Kept as a thin forwarding wrapper only
     * in case any older call site still references it directly.
     */
    @Deprecated
    private float rankScore(ReelModel r, Set<String> followedUids) {
        RankingProfile p = cachedRankingProfile != null ? cachedRankingProfile : new RankingProfile();
        if (followedUids != null) p.followedUids = followedUids;
        return FeedRankingEngine.score(r, p);
    }

    /** Tracks the newest/oldest timestamp seen so far so pagination
     *  (older posts) and the real-time listener (newer posts) both know
     *  where the currently-rendered window starts and ends. */
    private void updateFeedTimestampBounds(List<ReelModel> posts) {
        for (ReelModel r : posts) {
            if (oldestFeedTimestamp == null || r.timestamp < oldestFeedTimestamp) oldestFeedTimestamp = r.timestamp;
            if (newestFeedTimestamp == null || r.timestamp > newestFeedTimestamp) newestFeedTimestamp = r.timestamp;
        }
    }

    private void loadReelsForFeed(Set<String> followedUids, String myUid) {
        cachedFollowedUids = followedUids;
        FirebaseUtils.getReelsRef()
            .orderByChild("timestamp")
            .limitToLast(FEED_FETCH_BATCH)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    List<ReelModel> posts = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        ReelModel r = s.getValue(ReelModel.class);
                        if (r != null && followedUids.contains(r.uid)) {
                            if (r.reelId == null) r.reelId = s.getKey();
                            posts.add(r);
                        }
                    }
                    // Following mode stays reverse-chronological (matches real
                    // Instagram: the explicit "Following" filter is chrono-only —
                    // ranking/mixing only happens in the default/For-You feed).
                    posts.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                    updateFeedTimestampBounds(posts);
                    // A full window may still come back < FEED_FETCH_BATCH once
                    // filtered down to followed authors — that alone doesn't mean
                    // there's nothing older, so only stop paginating once the raw
                    // (unfiltered) fetch itself came back short.
                    feedHasMore = snap.getChildrenCount() >= FEED_FETCH_BATCH;
                    renderFeedPosts(posts, myUid, followedUids);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    showFeedLoading(false);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                }
            });
    }

    private void renderFeedPosts(List<ReelModel> posts, String myUid, Set<String> followedUids) {
        if (!isAdded() || getContext() == null) return;
        showFeedLoading(false);
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);

        if (posts.isEmpty()) {
            showFeedEmpty(true);
            return;
        }
        showFeedEmpty(false);

        // Load liked reels for current user to show correct heart state
        if (myUid != null) {
            FirebaseUtils.getReelSavesRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot savedSnap) {
                    Set<String> savedIds = new HashSet<>();
                    for (DataSnapshot s : savedSnap.getChildren()) savedIds.add(s.getKey());

                    FirebaseUtils.getReelLikedByUserRef(myUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot likedSnap) {
                                Set<String> likedIds = new HashSet<>();
                                for (DataSnapshot s : likedSnap.getChildren()) likedIds.add(s.getKey());
                                renderFeedPostsWithState(posts, likedIds, savedIds, myUid, followedUids);
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {
                                renderFeedPostsWithState(posts, new HashSet<>(), savedIds, myUid, followedUids);
                            }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    renderFeedPostsWithState(posts, new HashSet<>(), new HashSet<>(), myUid, followedUids);
                }
            });
        } else {
            renderFeedPostsWithState(posts, new HashSet<>(), new HashSet<>(), null, followedUids);
        }
    }

    private void renderFeedPostsWithState(List<ReelModel> posts, Set<String> likedIds,
                                           Set<String> savedIds, String myUid, Set<String> followedUids) {
        if (!isAdded() || getContext() == null) return;
        // Cache render state so infinite-scroll pages and the real-time
        // "new posts" refresh can reuse it without re-fetching per card.
        cachedLikedIds     = likedIds;
        cachedSavedIds     = savedIds;
        cachedMyUidForFeed = myUid;
        cachedFollowedUids = followedUids;
        requireActivity().runOnUiThread(() -> {
            if (feedAdapter == null || !isAdded()) return;
            // The cards backing the ticker/tracker are about to be destroyed:
            // save where the current reel got to, then stand both down before
            // currentPlayingIndex is invalidated.
            flushActiveWatchProgress();
            stopProgressTicker();
            if (watchTracker != null) watchTracker.onCardInactive();
            userPausedActiveCard = false;
            clearFeedRows();
            renderedReelIds.clear();
            postsSincePeopleYouMayLike = 0;
            postsSinceSuggestedReels = 0;
            currentFeedPosts = posts;
            currentPlayingIndex = -1;
            // v243: with real RecyclerView recycling, building a page's worth
            // of FeedRow descriptors is cheap bookkeeping (no inflate, no
            // Glide, no ExoPlayer touch — that all happens lazily in
            // FeedAdapter.onBindViewHolder only once a row actually scrolls
            // into view), so the old "3 immediate + 1-per-frame staged" dance
            // that existed purely to pace eager view creation is no longer
            // needed — the whole page's rows go in synchronously.
            ensureFeedCardsCapacity(posts.size());
            for (int i = 0; i < posts.size(); i++) {
                renderOneFeedItem(i, posts.get(i));
            }
            // Auto-play first visible card the instant layout finishes —
            // see scheduleImmediatePlayCheck() doc (was a flat 400ms guess).
            scrollHandler.removeCallbacks(playVisibleRunnable);
            scheduleImmediatePlayCheck();
            // Start listening for brand-new posts published while the user
            // is sitting on Home — real background updates, not just
            // pull-to-refresh, same as Instagram's live feed.
            startRealtimeNewPostsListener();
        });
    }

    // ── Staged (scroll-yielding) card rendering ───────────────────────────
    // v243: retired. Real RecyclerView ViewHolder recycling means building a
    // page's worth of FeedRow descriptors (renderOneFeedItem) is cheap
    // bookkeeping done synchronously — the actual expensive work (inflate,
    // Glide, ExoPlayer wiring) now only ever happens lazily in
    // FeedAdapter.onBindViewHolder for whichever row is actually on screen,
    // so there's nothing left to frame-pace here. cancelStagedFeedRender()
    // is kept as a safe no-op since a few call sites still invoke it
    // defensively (mode switch, pull-to-refresh, teardown).
    private Runnable stagedRunnable = null;

    private void cancelStagedFeedRender() {
        if (stagedRunnable != null) scrollHandler.removeCallbacks(stagedRunnable);
        stagedRunnable = null;
    }

    /**
     * Renders a single feed slot: the post row itself, plus — every
     * SUGGESTED_EVERY_N_POSTS posts, in For-You mode only — an inline
     * "Suggested for you" creators row mixed directly into the scroll
     * (Instagram doesn't box these off separately; they're interleaved).
     *
     * v243: this only ever touches the lightweight feedItems/feedCards data
     * model now — no view is inflated, no Glide/ExoPlayer work is dispatched.
     * FeedAdapter.onBindViewHolder does that lazily, only for whichever row
     * actually scrolls into view.
     */
    private void renderOneFeedItem(int postIndex, ReelModel reel) {
        if (reel == null || reel.reelId == null || renderedReelIds.contains(reel.reelId)) return;
        renderedReelIds.add(reel.reelId);
        ensureFeedCardsCapacity(postIndex + 1);
        FeedRow row = new FeedRow(ROW_POST);
        row.postIndex = postIndex;
        feedItems.add(row);
        if (feedAdapter != null) feedAdapter.notifyItemInserted(FEED_HEADER_OFFSET + feedItems.size() - 1);

        if (isFollowingMode) return; // Following stays a pure chronological feed
        postsSincePeopleYouMayLike++;
        if (postsSincePeopleYouMayLike >= SUGGESTED_EVERY_N_POSTS) {
            postsSincePeopleYouMayLike = 0;
            insertInlineSuggestedCreatorsRow();
        }
        postsSinceSuggestedReels++;
        if (postsSinceSuggestedReels >= SUGGESTED_REELS_EVERY_N_POSTS) {
            postsSinceSuggestedReels = 0;
            insertInlineSuggestedReelsRow();
        }
    }

    /**
     * Appends the next page of the feed (called by infinite scroll). Reuses
     * the cached liked/saved/follow state from the initial render instead of
     * re-fetching it, and simply extends currentFeedPosts + feedItems.
     * v243: no staged/frame-paced rendering needed any more — see
     * renderFeedPostsWithState's comment; appending row descriptors for a
     * page (~8-25 items) is cheap bookkeeping, not real view work.
     */
    private void appendFeedPage(List<ReelModel> newPosts) {
        if (!isAdded() || feedAdapter == null) return;
        int baseIndex = currentFeedPosts.size();
        List<ReelModel> merged = new ArrayList<>(currentFeedPosts);
        merged.addAll(newPosts);
        currentFeedPosts = merged;
        ensureFeedCardsCapacity(currentFeedPosts.size());
        for (int i = 0; i < newPosts.size(); i++) {
            renderOneFeedItem(baseIndex + i, newPosts.get(i));
        }
        isLoadingMoreFeed = false;
        showFeedFooterLoading(false);
        // v280: check once per page append, not per scroll delta — see the
        // list-cap/windowing block above for why this only trims the front.
        maybeTrimFeedWindow();

        // ── v281: Prefetch metadata for all newly-loaded posts ──────────────
        // Batched via networkBatcher (50ms coalesce window); results cached
        // so immediate display of post counts without spinners.
        if (ultraOptimizer != null && !newPosts.isEmpty()) {
            ultraOptimizer.prefetchPostMetadata(newPosts, FirebaseUtils.getReelsRef());
        }
    }

    // ── v280: list cap / windowing (front-trim + DiffUtil remap) ───────────

    /** Resolves a FeedRow's current reelId identity via currentFeedPosts —
     *  ONLY valid to call after currentFeedPosts has already been mutated to
     *  its final post-operation state (see call sites below). */
    private String postReelId(FeedRow row) {
        if (row.type != ROW_POST || row.postIndex < 0 || row.postIndex >= currentFeedPosts.size()) return null;
        ReelModel r = currentFeedPosts.get(row.postIndex);
        return r != null ? r.reelId : null;
    }

    /** Diffs feedItems' old vs new shape by post identity (reelId) rather
     *  than raw position, so a front-trim's index remap turns into the
     *  correct sequence of RecyclerView remove/insert/move calls instead of
     *  a manual, error-prone position recomputation. reelIdOf carries each
     *  ROW_POST row's identity as it stood BEFORE this operation's mutation
     *  (postIndex may already have been rewritten in place by the caller). */
    private void applyFeedItemsDiff(List<FeedRow> oldItems, List<FeedRow> newItems, Map<FeedRow, String> reelIdOf) {
        if (feedAdapter == null) return;
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldItems.size(); }
            @Override public int getNewListSize() { return newItems.size(); }
            @Override public boolean areItemsTheSame(int oldPos, int newPos) {
                FeedRow o = oldItems.get(oldPos), n = newItems.get(newPos);
                if (o.type != n.type) return false;
                if (o.type == ROW_POST) {
                    String a = reelIdOf.get(o);
                    String b = reelIdOf.containsKey(n) ? reelIdOf.get(n) : postReelId(n);
                    return a != null && a.equals(b);
                }
                return o == n; // non-post rows carry no reelId — same instance = same row
            }
            @Override public boolean areContentsTheSame(int oldPos, int newPos) {
                // A trim/prepend only ever moves rows around or drops/adds
                // whole posts — it never changes what an existing row looks
                // like, and onBindViewHolder re-reads postIndex fresh on
                // every bind regardless of position, so "same item" already
                // implies "same content" here.
                return true;
            }
        });
        feedItems.clear();
        feedItems.addAll(newItems);
        result.dispatchUpdatesTo(new ListUpdateCallback() {
            @Override public void onInserted(int position, int count) {
                feedAdapter.notifyItemRangeInserted(FEED_HEADER_OFFSET + position, count);
            }
            @Override public void onRemoved(int position, int count) {
                feedAdapter.notifyItemRangeRemoved(FEED_HEADER_OFFSET + position, count);
            }
            @Override public void onMoved(int fromPosition, int toPosition) {
                feedAdapter.notifyItemMoved(FEED_HEADER_OFFSET + fromPosition, FEED_HEADER_OFFSET + toPosition);
            }
            @Override public void onChanged(int position, int count, Object payload) {
                feedAdapter.notifyItemRangeChanged(FEED_HEADER_OFFSET + position, count, payload);
            }
        });
    }

    /** Called once per page append (see appendFeedPage) — cheap bookkeeping
     *  unless the feed has actually grown past the window, so a normal
     *  scroll session pays for nothing here until it's grown far enough to
     *  matter. */
    private void maybeTrimFeedWindow() {
        if (!isAdded() || feedAdapter == null) return;
        int total = currentFeedPosts.size();
        if (total <= FEED_WINDOW_BEHIND + FEED_WINDOW_AHEAD + FEED_TRIM_SLACK) return;

        int refIndex = currentPlayingIndex >= 0 ? currentPlayingIndex : 0;
        int lowerBound = Math.max(0, refIndex - FEED_WINDOW_BEHIND);
        if (lowerBound <= 0) return; // nothing far enough behind yet

        trimFeedFront(lowerBound);
    }

    /** Drops the first cutCount posts from currentFeedPosts/feedCards
     *  (already scrolled well past — see the class-doc block above) and
     *  remaps every surviving ROW_POST's postIndex, applying the change via
     *  DiffUtil instead of a blanket notifyDataSetChanged(). */
    private void trimFeedFront(int cutCount) {
        List<FeedRow> oldItems = new ArrayList<>(feedItems);
        List<ReelModel> oldPosts = currentFeedPosts;
        if (oldPosts.isEmpty() || cutCount <= 0) return;

        // Capture each surviving/removed ROW_POST row's identity BEFORE any
        // postIndex remap below, since several rows are mutated in place.
        Map<FeedRow, String> reelIdOf = new HashMap<>();
        for (FeedRow row : oldItems) {
            if (row.type == ROW_POST && row.postIndex >= 0 && row.postIndex < oldPosts.size()) {
                reelIdOf.put(row, oldPosts.get(row.postIndex).reelId);
            }
        }

        // Track the newest timestamp we're about to drop, so a scroll back
        // up knows exactly how far to re-fetch (see reloadTrimmedFrontPosts).
        long newHigh = oldPosts.get(0).timestamp;
        frontTrimHighWaterTimestamp = (frontTrimHighWaterTimestamp == null)
                ? newHigh : Math.max(frontTrimHighWaterTimestamp, newHigh);
        // These posts may come back via reloadTrimmedFrontPosts() — let
        // renderOneFeedItem() re-add them instead of skipping them as
        // "already rendered".
        for (int i = 0; i < cutCount; i++) {
            ReelModel removed = oldPosts.get(i);
            if (removed != null && removed.reelId != null) renderedReelIds.remove(removed.reelId);
        }

        currentFeedPosts = new ArrayList<>(oldPosts.subList(cutCount, oldPosts.size()));
        int cardCut = Math.min(cutCount, feedCards.size());
        for (int i = 0; i < cardCut; i++) {
            // Should already be null (well past the RecyclerView's bound
            // range) — defensively detach the shared player anyway in case a
            // very fast fling raced past onViewRecycled.
            HomeFeedCard c = feedCards.get(i);
            if (c != null && c.playerView != null) c.playerView.setPlayer(null);
        }
        if (cardCut > 0) feedCards.subList(0, cardCut).clear();

        List<FeedRow> newItems = new ArrayList<>();
        boolean pastCut = false;
        for (FeedRow row : oldItems) {
            if (!pastCut) {
                if (row.type == ROW_POST && row.postIndex >= cutCount) {
                    pastCut = true;
                } else {
                    continue; // drop trimmed posts and any row interleaved before them
                }
            }
            if (row.type == ROW_POST) row.postIndex -= cutCount;
            newItems.add(row);
        }

        if (currentPlayingIndex >= 0) currentPlayingIndex -= cutCount;

        applyFeedItemsDiff(oldItems, newItems, reelIdOf);
    }

    /**
     * Scroll-up counterpart to loadMoreFeedPosts(): once the user scrolls
     * back within paginateThresholdPx of the top of a feed whose front has
     * been trimmed, re-fetch exactly the trimmed range from Firebase (posts
     * newer than the current head, up to frontTrimHighWaterTimestamp) and
     * prepend them — same DiffUtil path as trimFeedFront, just growing the
     * front instead of shrinking it.
     */
    private void reloadTrimmedFrontPosts() {
        if (isLoadingNewerFeed || frontTrimHighWaterTimestamp == null) return;
        if (!isAdded() || getContext() == null || currentFeedPosts.isEmpty()) return;
        isLoadingNewerFeed = true;
        long lowTimestamp = currentFeedPosts.get(0).timestamp;

        FirebaseUtils.getReelsRef()
                .orderByChild("timestamp")
                .startAt(lowTimestamp + 1)
                .limitToFirst(FEED_FETCH_BATCH)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        isLoadingNewerFeed = false;
                        if (!isAdded() || getContext() == null) return;
                        List<ReelModel> newer = new ArrayList<>();
                        for (DataSnapshot s : snap.getChildren()) {
                            ReelModel r = s.getValue(ReelModel.class);
                            if (r == null) continue;
                            if (r.reelId == null) r.reelId = s.getKey();
                            if (renderedReelIds.contains(r.reelId)) continue;
                            if (isFollowingMode && !cachedFollowedUids.contains(r.uid)) continue;
                            // Anything newer than the high-water mark belongs
                            // to the real-time "new posts" listener, not the
                            // trimmed gap this method is refilling.
                            if (r.timestamp > frontTrimHighWaterTimestamp) continue;
                            newer.add(r);
                        }
                        if (newer.isEmpty()) {
                            // Caught all the way back up to where trimming started.
                            frontTrimHighWaterTimestamp = null;
                            return;
                        }
                        newer.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                        List<ReelModel> pageToPrepend = isFollowingMode
                                ? newer
                                : FeedRankingEngine.buildRankedFeed(newer, cachedRankingProfile);
                        requireActivity().runOnUiThread(() -> prependFeedPosts(pageToPrepend));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { isLoadingNewerFeed = false; }
                });
    }

    /** Prepends previously-trimmed posts back onto the front of
     *  currentFeedPosts/feedCards, shifts every existing postIndex forward,
     *  and applies the change through the same DiffUtil path trimFeedFront
     *  uses (see applyFeedItemsDiff). */
    private void prependFeedPosts(List<ReelModel> newerPosts) {
        if (!isAdded() || feedAdapter == null || newerPosts.isEmpty()) return;
        List<FeedRow> oldItems = new ArrayList<>(feedItems);
        List<ReelModel> oldPosts = currentFeedPosts;

        Map<FeedRow, String> reelIdOf = new HashMap<>();
        for (FeedRow row : oldItems) {
            if (row.type == ROW_POST && row.postIndex >= 0 && row.postIndex < oldPosts.size()) {
                reelIdOf.put(row, oldPosts.get(row.postIndex).reelId);
            }
        }

        int shift = newerPosts.size();
        List<ReelModel> merged = new ArrayList<>(shift + oldPosts.size());
        merged.addAll(newerPosts);
        merged.addAll(oldPosts);
        currentFeedPosts = merged;

        List<HomeFeedCard> shiftedCards = new ArrayList<>(currentFeedPosts.size());
        for (int i = 0; i < shift; i++) shiftedCards.add(null);
        shiftedCards.addAll(feedCards);
        feedCards.clear();
        feedCards.addAll(shiftedCards);

        for (FeedRow row : oldItems) {
            if (row.type == ROW_POST) row.postIndex += shift;
        }
        List<FeedRow> newItems = new ArrayList<>(oldItems.size() + shift);
        for (int i = 0; i < shift; i++) {
            ReelModel r = newerPosts.get(i);
            if (r.reelId != null) renderedReelIds.add(r.reelId);
            FeedRow row = new FeedRow(ROW_POST);
            row.postIndex = i;
            newItems.add(row);
        }
        newItems.addAll(oldItems);

        if (currentPlayingIndex >= 0) currentPlayingIndex += shift;

        applyFeedItemsDiff(oldItems, newItems, reelIdOf);
    }

    // ── Infinite scroll (pagination) ─────────────────────────────────────

    /**
     * Fetches the next older window of posts once the user scrolls near the
     * bottom, instead of the feed simply stopping after one fixed load.
     * Following mode paginates by timestamp and filters to followed
     * authors; For-You mode paginates the same way and re-ranks each new
     * page with {@link FeedRankingEngine}.
     */
    private void loadMoreFeedPosts() {
        if (isLoadingMoreFeed || !feedHasMore || oldestFeedTimestamp == null) return;
        if (!isAdded() || getContext() == null) return;
        isLoadingMoreFeed = true;
        showFeedFooterLoading(true);

        Query q = FirebaseUtils.getReelsRef()
                .orderByChild("timestamp")
                .endAt(oldestFeedTimestamp - 1)
                .limitToLast(FEED_FETCH_BATCH);

        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded() || getContext() == null) return;
                List<ReelModel> newPosts = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) {
                    ReelModel r = s.getValue(ReelModel.class);
                    if (r == null) continue;
                    if (r.reelId == null) r.reelId = s.getKey();
                    if (renderedReelIds.contains(r.reelId)) continue;
                    if (isFollowingMode && !cachedFollowedUids.contains(r.uid)) continue;
                    newPosts.add(r);
                }
                feedHasMore = snap.getChildrenCount() >= FEED_FETCH_BATCH;
                for (ReelModel r : newPosts) {
                    if (oldestFeedTimestamp == null || r.timestamp < oldestFeedTimestamp)
                        oldestFeedTimestamp = r.timestamp;
                }
                if (newPosts.isEmpty()) {
                    isLoadingMoreFeed = false;
                    showFeedFooterLoading(false);
                    return;
                }
                List<ReelModel> pageToAppend;
                if (isFollowingMode) {
                    newPosts.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                    pageToAppend = newPosts;
                } else {
                    // Re-rank this page against the SAME profile snapshot the
                    // first page used, so scores/diversity stay consistent
                    // across pagination instead of drifting mid-scroll.
                    pageToAppend = FeedRankingEngine.buildRankedFeed(newPosts, cachedRankingProfile);
                }
                requireActivity().runOnUiThread(() -> appendFeedPage(pageToAppend));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                isLoadingMoreFeed = false;
                showFeedFooterLoading(false);
            }
        });
    }

    /** Small spinner appended/removed at the bottom of the feed while a
     *  pagination fetch (loadMoreFeedPosts) is in flight. */
    /** v243: the pagination footer spinner is now a ROW_LOAD_MORE_FOOTER
     *  entry appended after the posts (queued into feedItems / removed from
     *  it), instead of a standalone view toggled on containerFeed. */
    private void showFeedFooterLoading(boolean show) {
        if (feedAdapter == null || !isAdded() || getContext() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (feedAdapter == null || !isAdded()) return;
            int existing = findFeedRow(ROW_LOAD_MORE_FOOTER);
            if (show) {
                if (existing < 0) {
                    feedItems.add(new FeedRow(ROW_LOAD_MORE_FOOTER));
                    feedAdapter.notifyItemInserted(FEED_HEADER_OFFSET + feedItems.size() - 1);
                }
            } else if (existing >= 0) {
                feedItems.remove(existing);
                feedAdapter.notifyItemRemoved(FEED_HEADER_OFFSET + existing);
            }
        });
    }

    // ── Inline "Suggested for you" — mixed into the feed, not boxed off ────

    /**
     * Inserts a horizontal row of suggested (not-yet-followed) creators
     * directly into the feed scroll, the way Instagram interleaves
     * suggested accounts/posts between followed content instead of
     * confining them to a separate section. Fetches the candidate pool
     * once per session and reuses it for every insertion.
     */
    private void insertInlineSuggestedCreatorsRow() {
        if (!isAdded() || getContext() == null || feedAdapter == null) return;
        if (suggestedCreatorPool != null) {
            addSuggestedCreatorsRowIfAny(suggestedCreatorPool);
            return;
        }
        String myUid = safeMyUid();
        FirebaseUtils.db().getReference("users")
            .orderByChild("reelCount")
            .limitToLast(15)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    List<String[]> creators = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String uid = s.getKey();
                        if (uid == null || uid.equals(myUid)) continue;
                        String name = s.child("name").getValue(String.class);
                        String photo = s.child("photoUrl").getValue(String.class);
                        String thumb = s.child("thumbUrl").getValue(String.class);
                        String finalPhoto = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        if (name != null) {
                            creators.add(new String[]{uid, name, finalPhoto != null ? finalPhoto : ""});
                        }
                    }
                    Collections.reverse(creators);
                    suggestedCreatorPool = creators;
                    requireActivity().runOnUiThread(() -> addSuggestedCreatorsRowIfAny(creators));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { /* skip this insertion */ }
            });
    }

    /** v243: filters the pool down to candidates (same rule as before —
     *  unfollowed, capped at 6) and, if any remain, queues a ROW_SUGGESTED_CREATORS
     *  entry. The actual chip row view is now only built lazily, at bind
     *  time, by bindSuggestedCreatorsRowContent(). */
    private void addSuggestedCreatorsRowIfAny(List<String[]> pool) {
        if (!isAdded() || getContext() == null || feedAdapter == null || pool.isEmpty()) return;
        Set<String> followed = cachedFollowedUids != null ? cachedFollowedUids : new HashSet<>();
        List<String[]> candidates = new ArrayList<>();
        for (String[] c : pool) {
            if (!followed.contains(c[0])) candidates.add(c);
            if (candidates.size() >= 6) break;
        }
        if (candidates.isEmpty()) return;
        FeedRow row = new FeedRow(ROW_SUGGESTED_CREATORS);
        row.creatorPool = candidates;
        feedItems.add(row);
        feedAdapter.notifyItemInserted(FEED_HEADER_OFFSET + feedItems.size() - 1);
    }

    /** v243: builds the "Suggested for you" chip row into `container` — called
     *  from FeedAdapter.onBindViewHolder(VT_SUGGESTED_CREATORS_ROW). Content
     *  logic is unchanged from the old buildInlineSuggestedRow(); it just
     *  targets a recycled ViewHolder container instead of containerFeed. */
    private void bindSuggestedCreatorsRowContent(ViewGroup container, List<String[]> candidates) {
        container.removeAllViews();
        if (candidates == null || candidates.isEmpty()) return;

        LinearLayout section = new LinearLayout(requireContext());
        section.setOrientation(LinearLayout.VERTICAL);
        int dp16 = dpToPx(16);
        section.setPadding(dp16, dpToPx(12), dp16, dpToPx(4));

        TextView header = new TextView(requireContext());
        header.setText("Suggested for you");
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(13f);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        section.addView(header);

        // ── Instagram-level: real RecyclerView instead of an eagerly-inflated
        // HorizontalScrollView+LinearLayout row ──────────────────────────────
        // Mirrors bindSuggestedReelsRowContent()'s nested-RecyclerView upgrade
        // below: the old code built + Glide-loaded every candidate chip up
        // front (including chips off-screen to the right) as fresh View
        // objects, and — since this whole row is itself just one FrameLayout
        // FeedAdapter rebinds from scratch every time it scrolls back into
        // view — repeated that full build+decode cost on every rebind, with
        // zero reuse either across rebinds of the same strip or across the
        // multiple "Suggested for you" strips mixed periodically into a long
        // infinite-scroll session. A nested RecyclerView backed by the SAME
        // shared pool as the reels tiles below only builds/binds chips
        // actually on/near screen, and hands a chip ViewHolder scrolled out
        // of one strip straight to the next strip (or this strip's next
        // rebind) with no re-inflation and no re-decoding.
        RecyclerView chipsRecycler = new RecyclerView(requireContext());
        chipsRecycler.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        chipsRecycler.setRecycledViewPool(SUGGESTED_CREATORS_TILE_POOL);
        chipsRecycler.setItemViewCacheSize(4);
        chipsRecycler.setHasFixedSize(true);
        chipsRecycler.setItemAnimator(null);
        LinearLayout.LayoutParams recyclerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        recyclerLp.topMargin = dpToPx(8);
        chipsRecycler.setLayoutParams(recyclerLp);
        chipsRecycler.setAdapter(new SuggestedCreatorsTileAdapter(candidates));

        section.addView(chipsRecycler);
        container.addView(section);
    }

    /** Shared across every "Suggested for you" creators strip mixed into the
     *  Home feed — see bindSuggestedCreatorsRowContent() note above for why
     *  this must be a single shared instance rather than one pool per strip. */
    private static final RecyclerView.RecycledViewPool SUGGESTED_CREATORS_TILE_POOL =
            new RecyclerView.RecycledViewPool();

    /** Backs one "Suggested for you" strip's avatar chips with real
     *  ViewHolder recycling (see bindSuggestedCreatorsRowContent()). Chip
     *  dimensions (136dp wide, 90dp avatar) unchanged from the old manual
     *  row, so this is a drop-in visual match. */
    private class SuggestedCreatorsTileAdapter extends RecyclerView.Adapter<SuggestedCreatorsTileAdapter.TileHolder> {
        private static final int CHIP_W_DP = 136;
        private static final int AVATAR_DP = 90;

        private final List<String[]> items;

        SuggestedCreatorsTileAdapter(List<String[]> items) {
            this.items = items;
        }

        class TileHolder extends RecyclerView.ViewHolder {
            final LinearLayout chip;
            final CircleImageView av;
            final TextView tvName;
            TileHolder(LinearLayout chip, CircleImageView av, TextView tvName) {
                super(chip);
                this.chip = chip; this.av = av; this.tvName = tvName;
            }
        }

        @NonNull @Override
        public TileHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout chip = new LinearLayout(requireContext());
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            chip.setLayoutParams(new ViewGroup.MarginLayoutParams(dpToPx(CHIP_W_DP), ViewGroup.LayoutParams.WRAP_CONTENT));
            chip.setPadding(dpToPx(6), dpToPx(10), dpToPx(6), dpToPx(10));
            chip.setBackgroundResource(R.drawable.bg_speed_chip);

            CircleImageView av = new CircleImageView(requireContext());
            av.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(AVATAR_DP), dpToPx(AVATAR_DP)));
            chip.addView(av);

            TextView tvName = new TextView(requireContext());
            tvName.setTextSize(13f);
            tvName.setTextColor(0xFFFFFFFF);
            tvName.setMaxLines(1);
            tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvName.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nameLp.topMargin = dpToPx(6);
            tvName.setLayoutParams(nameLp);
            chip.addView(tvName);

            return new TileHolder(chip, av, tvName);
        }

        @Override
        public void onBindViewHolder(@NonNull TileHolder holder, int position) {
            String[] c = items.get(position);
            String uid = c[0], name = c[1], photo = c[2];

            ViewGroup.LayoutParams rawLp = holder.chip.getLayoutParams();
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) rawLp).setMarginEnd(
                        position == items.size() - 1 ? 0 : dpToPx(12));
            }

            holder.tvName.setText(name);
            holder.av.setImageResource(R.drawable.ic_person);
            if (photo != null && !photo.isEmpty()) {
                Glide.with(requireContext()).load(photo)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(holder.av);
            } else {
                Glide.with(requireContext()).clear(holder.av);
            }

            holder.chip.setOnClickListener(v -> {
                if (!isAdded() || getContext() == null) return;
                Intent i = new Intent(getContext(), UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID, uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME, name);
                startActivity(i);
            });
        }

        @Override
        public void onViewRecycled(@NonNull TileHolder holder) {
            // Cancel/clear this Glide load before the ViewHolder goes back to
            // the SHARED pool — otherwise a slow in-flight load from strip A
            // could land its bitmap into an avatar strip B has since reused
            // for a different creator.
            Glide.with(requireContext()).clear(holder.av);
        }

        @Override public int getItemCount() { return items.size(); }
    }

    // ── Inline "Suggested reels" — Instagram-style thumbnail row ──────────

    /**
     * Inserts a horizontal row of suggested-reel thumbnail cards directly
     * into the feed scroll (see screenshot ref: a "Suggested reels" header
     * + a row of tall video-thumbnail cards, mixed between regular posts).
     * Fetches the candidate pool once per session (same pattern as
     * {@link #insertInlineSuggestedCreatorsRow()}) and reuses it for every
     * insertion so we don't re-hit Firebase on every 4th post.
     */
    private void insertInlineSuggestedReelsRow() {
        if (!isAdded() || getContext() == null || feedAdapter == null) return;
        if (suggestedReelsPool != null) {
            addSuggestedReelsRowIfAny(suggestedReelsPool);
            return;
        }
        FirebaseUtils.getReelsRef()
            .orderByChild("viewsCount")
            .limitToLast(20)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    List<ReelModel> reels = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        ReelModel r = s.getValue(ReelModel.class);
                        if (r == null) continue;
                        if (r.reelId == null) r.reelId = s.getKey();
                        reels.add(r);
                    }
                    Collections.reverse(reels); // most-viewed first
                    suggestedReelsPool = reels;
                    requireActivity().runOnUiThread(() -> addSuggestedReelsRowIfAny(reels));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { /* skip this insertion */ }
            });
    }

    /** v243: filters (same rule as before) and, if any remain, queues a
     *  ROW_SUGGESTED_REELS entry — the tile row itself is only built lazily
     *  by bindSuggestedReelsRowContent() at bind time. */
    private void addSuggestedReelsRowIfAny(List<ReelModel> pool) {
        if (!isAdded() || getContext() == null || feedAdapter == null || pool.isEmpty()) return;
        String myUid = safeMyUid();
        List<ReelModel> candidates = new ArrayList<>();
        for (ReelModel r : pool) {
            if (r.reelId == null || renderedReelIds.contains(r.reelId)) continue; // don't dupe posts already shown
            if (myUid != null && myUid.equals(r.uid)) continue; // Instagram doesn't suggest your own reels
            candidates.add(r);
            if (candidates.size() >= 8) break;
        }
        if (candidates.isEmpty()) return;
        FeedRow row = new FeedRow(ROW_SUGGESTED_REELS);
        row.reelPool = candidates;
        feedItems.add(row);
        feedAdapter.notifyItemInserted(FEED_HEADER_OFFSET + feedItems.size() - 1);
    }

    /** v243: builds the "Suggested reels" tile row into `container` — called
     *  from FeedAdapter.onBindViewHolder(VT_SUGGESTED_REELS_ROW). Content
     *  logic unchanged from buildInlineSuggestedReelsRow(); onNotInterested
     *  lets the adapter remove this exact row from feedItems instead of the
     *  old direct containerFeed.removeView(section).
     */
    private void bindSuggestedReelsRowContent(ViewGroup container, List<ReelModel> candidates,
                                               Runnable onNotInterested) {
        container.removeAllViews();
        if (candidates == null || candidates.isEmpty()) return;

        final ArrayList<String> reelIds = new ArrayList<>();
        for (ReelModel r : candidates) reelIds.add(r.reelId);

        LinearLayout section = new LinearLayout(requireContext());
        section.setOrientation(LinearLayout.VERTICAL);
        int dp16 = dpToPx(16);
        section.setPadding(dp16, dpToPx(12), dp16, dpToPx(4));

        LinearLayout headerRow = new LinearLayout(requireContext());
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView header = new TextView(requireContext());
        header.setText("Suggested reels");
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(13f);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.setLayoutParams(headerLp);
        headerRow.addView(header);

        ImageView btnMore = new ImageView(requireContext());
        btnMore.setImageResource(R.drawable.ic_more_vert);
        btnMore.setColorFilter(0xFFFFFFFF);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(dpToPx(20), dpToPx(20));
        btnMore.setLayoutParams(moreLp);
        btnMore.setOnClickListener(v -> {
            if (!isAdded() || getContext() == null) return;
            android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), btnMore);
            menu.getMenu().add("Not interested");
            menu.setOnMenuItemClickListener(item -> {
                if (onNotInterested != null) onNotInterested.run();
                return true;
            });
            menu.show();
        });
        headerRow.addView(btnMore);
        section.addView(headerRow);

        // ── Instagram-level: real RecyclerView instead of an eagerly-inflated
        // HorizontalScrollView+LinearLayout row ──────────────────────────────
        // The old code inflated + Glide-loaded EVERY candidate tile up front,
        // even the ones off-screen to the right, and — since this whole row is
        // itself just one FrameLayout bound fresh by FeedAdapter every time it
        // scrolls back into view (ROW_SUGGESTED_REELS has no inner recycling)
        // — repeated that full inflate+decode cost on every rebind. A nested
        // RecyclerView only builds/binds the tiles actually on/near screen,
        // and — backed by SUGGESTED_REELS_TILE_POOL, shared across every
        // "Suggested reels" strip inserted into the feed — a tile ViewHolder
        // scrolled out of one strip can be handed straight to the next strip
        // (or to this same strip on its next rebind) with no re-inflation and
        // no re-decoding, exactly like Instagram's nested-recycling rows.
        RecyclerView tilesRecycler = new RecyclerView(requireContext());
        tilesRecycler.setLayoutManager(
                new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
        tilesRecycler.setRecycledViewPool(SUGGESTED_REELS_TILE_POOL);
        tilesRecycler.setItemViewCacheSize(4);
        tilesRecycler.setHasFixedSize(true);
        tilesRecycler.setItemAnimator(null);
        LinearLayout.LayoutParams recyclerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(284));
        recyclerLp.topMargin = dpToPx(8);
        tilesRecycler.setLayoutParams(recyclerLp);
        tilesRecycler.setAdapter(new SuggestedReelsTileAdapter(candidates, reelIds));

        section.addView(tilesRecycler);
        container.addView(section);
    }

    /** Shared across every "Suggested reels" strip mixed into the Home feed —
     *  see bindSuggestedReelsRowContent() note above for why this must be a
     *  single shared instance rather than one pool per strip. */
    private static final RecyclerView.RecycledViewPool SUGGESTED_REELS_TILE_POOL =
            new RecyclerView.RecycledViewPool();

    /** Backs one "Suggested reels" strip's tiles with real ViewHolder
     *  recycling (see bindSuggestedReelsRowContent()). ~9:16 tile, bigger
     *  than a grid cell so only ~2 tiles + a peek of the 3rd fit per screen
     *  (matches reference screenshot) — same sizing as the old manual row. */
    private class SuggestedReelsTileAdapter extends RecyclerView.Adapter<SuggestedReelsTileAdapter.TileHolder> {
        private static final int TILE_W_DP = 160;
        private static final int TILE_H_DP = 284;

        private final List<ReelModel> items;
        private final ArrayList<String> reelIds;

        SuggestedReelsTileAdapter(List<ReelModel> items, ArrayList<String> reelIds) {
            this.items = items;
            this.reelIds = reelIds;
        }

        class TileHolder extends RecyclerView.ViewHolder {
            final FrameLayout tile;
            final ImageView thumb;
            final TextView tvViews;
            TileHolder(FrameLayout tile, ImageView thumb, TextView tvViews) {
                super(tile);
                this.tile = tile; this.thumb = thumb; this.tvViews = tvViews;
            }
        }

        @NonNull @Override
        public TileHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout tile = new FrameLayout(requireContext());
            tile.setLayoutParams(new ViewGroup.MarginLayoutParams(dpToPx(TILE_W_DP), dpToPx(TILE_H_DP)));
            tile.setBackgroundResource(R.drawable.bg_speed_chip);
            tile.setClipToOutline(true);

            ImageView thumb = new ImageView(requireContext());
            thumb.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            tile.addView(thumb);

            // Views count pill, bottom-left, with a small play glyph — same
            // read-at-a-glance treatment Instagram uses on its reel tiles.
            TextView tvViews = new TextView(requireContext());
            tvViews.setTextColor(0xFFFFFFFF);
            tvViews.setTextSize(12f);
            tvViews.setShadowLayer(4f, 0f, 0f, 0xCC000000);
            FrameLayout.LayoutParams viewsLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            viewsLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
            viewsLp.setMargins(dpToPx(8), 0, 0, dpToPx(8));
            tvViews.setLayoutParams(viewsLp);
            tile.addView(tvViews);

            return new TileHolder(tile, thumb, tvViews);
        }

        @Override
        public void onBindViewHolder(@NonNull TileHolder holder, int position) {
            ReelModel r = items.get(position);
            holder.tvViews.setText("▶ " + formatCount(r.viewsCount));

            ViewGroup.LayoutParams rawLp = holder.tile.getLayoutParams();
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) rawLp).setMarginEnd(
                        position == items.size() - 1 ? 0 : dpToPx(8));
            }

            String thumbUrl = r.effectiveThumbUrl();
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                // Same RGB_565 + no-crossfade treatment as the rest of the
                // feed's cheap-decode thumbnails (FEED_IMAGE_OPTS).
                Glide.with(requireContext()).load(thumbUrl).apply(FEED_IMAGE_OPTS).into(holder.thumb);
            } else {
                Glide.with(requireContext()).clear(holder.thumb);
            }

            final int startPos = position;
            holder.tile.setOnClickListener(v -> {
                if (!isAdded() || getContext() == null) return;
                Intent i = new Intent(getContext(), SingleReelPlayerActivity.class);
                i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, reelIds);
                i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, startPos);
                startActivity(i);
            });
            holder.tile.setOnLongClickListener(v -> {
                showSuggestedReelPeek(r, holder.tile);
                return true;
            });
        }

        @Override
        public void onViewRecycled(@NonNull TileHolder holder) {
            // Cancel/clear this Glide load before the ViewHolder goes back to
            // the SHARED pool — otherwise a slow in-flight load from strip A
            // could land its bitmap into an ImageView strip B has since reused
            // for a different reel.
            Glide.with(requireContext()).clear(holder.thumb);
        }

        @Override public int getItemCount() { return items.size(); }
    }

    /**
     * Long-press on a "Suggested reels" tile — reuses the exact same
     * ReelPeekPreviewController mini video player UserReelsActivity's grid
     * and SoundDetailFragment already use (see class doc there), just sized
     * bigger: a near-full-width card instead of the shared 331x475dp
     * default, matching the reference screenshot's larger preview.
     */
    private void showSuggestedReelPeek(ReelModel reel, View sourceView) {
        if (!isAdded() || getContext() == null) return;
        if (suggestedReelsPeekController == null) {
            suggestedReelsPeekController = new ReelPeekPreviewController(requireActivity());
        }
        int screenW   = getResources().getDisplayMetrics().widthPixels;
        int cardWidth = screenW - dpToPx(24); // near-full-width, small side margins
        int videoH    = (int) (cardWidth * 16f / 9f); // same 9:16 ratio as the reel feed card
        suggestedReelsPeekController.show(reel, null,
                () -> {
                    Intent i = new Intent(getContext(), SingleReelPlayerActivity.class);
                    ArrayList<String> singleId = new ArrayList<>();
                    singleId.add(reel.reelId);
                    i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, singleId);
                    i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, 0);
                    startActivity(i);
                },
                sourceView, cardWidth, videoH);
    }

    // ── Real-time background updates ────────────────────────────────────

    /**
     * Listens for reels published after the newest one currently rendered
     * and surfaces a small "N new posts" pill instead of silently jumping
     * the scroll position — same pattern Instagram/Twitter use for live
     * feed updates. Tapping the pill reloads from the top; pull-to-refresh
     * still works independently at any time.
     */
    private void startRealtimeNewPostsListener() {
        stopRealtimeNewPostsListener();
        if (newestFeedTimestamp == null || !isAdded() || getContext() == null) return;

        Query q = FirebaseUtils.getReelsRef()
                .orderByChild("timestamp")
                .startAt(newestFeedTimestamp + 1);
        ChildEventListener listener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot snap, String prevKey) {
                if (!isAdded() || getContext() == null) return;
                ReelModel r = snap.getValue(ReelModel.class);
                if (r == null) return;
                String reelId = r.reelId != null ? r.reelId : snap.getKey();
                if (reelId == null || renderedReelIds.contains(reelId)) return;
                if (isFollowingMode && !cachedFollowedUids.contains(r.uid)) return;
                requireActivity().runOnUiThread(() -> {
                    newPostsPending++;
                    showNewPostsBanner();
                });
            }
            @Override public void onChildChanged(@NonNull DataSnapshot snap, String prevKey) { }
            @Override public void onChildRemoved(@NonNull DataSnapshot snap) { }
            @Override public void onChildMoved(@NonNull DataSnapshot snap, String prevKey) { }
            @Override public void onCancelled(@NonNull DatabaseError error) { }
        };
        q.addChildEventListener(listener);
        newPostsQuery = q;
        newPostsListener = listener;
    }

    private void stopRealtimeNewPostsListener() {
        if (newPostsQuery != null && newPostsListener != null) {
            newPostsQuery.removeEventListener(newPostsListener);
        }
        newPostsQuery = null;
        newPostsListener = null;
    }

    /** Shows (or updates the count on) the floating "N new posts" pill
     *  pinned above the feed content.
     *  v243: this is now a singleton ROW_NEW_POSTS_BANNER entry at the very
     *  front of feedItems instead of a raw View kept in containerFeed —
     *  bindNewPostsBannerContent() builds/updates its actual pill view. */
    private void showNewPostsBanner() {
        if (!isAdded() || getContext() == null || feedAdapter == null) return;
        int existing = findFeedRow(ROW_NEW_POSTS_BANNER);
        if (existing < 0) {
            feedItems.add(0, new FeedRow(ROW_NEW_POSTS_BANNER));
            feedAdapter.notifyItemInserted(FEED_HEADER_OFFSET);
        } else {
            feedAdapter.notifyItemChanged(FEED_HEADER_OFFSET + existing);
        }
    }

    private void hideNewPostsBanner() {
        int existing = findFeedRow(ROW_NEW_POSTS_BANNER);
        if (existing >= 0 && feedAdapter != null) {
            feedItems.remove(existing);
            feedAdapter.notifyItemRemoved(FEED_HEADER_OFFSET + existing);
        }
        newPostsBanner = null;
        newPostsPending = 0;
    }

    /** v243: builds/refreshes the "N new posts" pill into `container` —
     *  called from FeedAdapter.onBindViewHolder(VT_NEW_POSTS_BANNER). Same
     *  content/behavior as the old showNewPostsBanner()'s view-building half. */
    private void bindNewPostsBannerContent(ViewGroup container) {
        container.removeAllViews();
        TextView pill = new TextView(requireContext());
        pill.setTextColor(0xFFFFFFFF);
        pill.setTextSize(13f);
        pill.setTypeface(null, android.graphics.Typeface.BOLD);
        pill.setGravity(android.view.Gravity.CENTER);
        pill.setBackgroundResource(R.drawable.bg_speed_chip);
        int padV = dpToPx(10), padH = dpToPx(16);
        pill.setPadding(padH, padV, padH, padV);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dpToPx(8);
        lp.bottomMargin = dpToPx(8);
        pill.setLayoutParams(lp);
        pill.setText(newPostsPending == 1
                ? "1 new post · Tap to refresh" : (newPostsPending + " new posts · Tap to refresh"));
        pill.setOnClickListener(v -> {
            if (recyclerHome != null) recyclerHome.smoothScrollToPosition(0);
            resetFeedPaginationState();
            cancelStagedFeedRender();
            clearFeedRows();
            showFeedLoading(true);
            loadFeed();
        });
        newPostsBanner = pill;
        container.addView(pill);
    }

    /**
     * v243: `card` is now a ViewHolder's already-inflated item_home_feed_post
     * view (owned/attached by FeedAdapter), and `postIndex` is this post's
     * position in currentFeedPosts/feedCards — both supplied by
     * FeedAdapter.onBindViewHolder instead of being computed here. This
     * function purely POPULATES `card`'s children + (re)registers listeners;
     * it no longer inflates a view, pulls from cardPool, or appends anything
     * to a container — RecyclerView owns attach/detach/recycling now.
     */
    private void addFeedPostCard(PostRowHolder holder, int postIndex, ReelModel reel, Set<String> likedIds,
                                  Set<String> savedIds, String myUid, Set<String> followedUids) {
        if (!isAdded() || getContext() == null) return;
        // ★ Ultra-advanced optimization: every one of these used to be a
        // fresh card.findViewById(...) tree-walk on EVERY bind (~28 calls,
        // repeated for every card that scrolls into view during a fling).
        // holder.cacheViews() already did that lookup once at inflate time
        // (onCreateViewHolder) — this is now just a set of field reads, and
        // every line below is completely unchanged. See PostRowHolder doc.
        View card                 = holder.itemView;
        CircleImageView avatar    = holder.avatar;
        // FIX v39: seamless gradient ring (see StoryRingGradientDrawable doc for why
        // the old story_ring_insta_gradient.xml background had a visible seam).
        ImageView ivPostStoryRing = holder.ivPostStoryRing;
        // Instagram-style: gradient only while unseen, flat gray once seen,
        // hidden with no active status — same StatusCacheManager everything
        // else in the app reads from. Mirrors ReelUiController.
        if (ivPostStoryRing != null && reel.uid != null) {
            com.callx.app.cache.StatusCacheManager scm =
                    com.callx.app.cache.StatusCacheManager.getInstance(requireContext());
            boolean hasUnseen = scm.hasUnseen(reel.uid);
            boolean hasAny    = scm.hasStatus(reel.uid);
            if (hasUnseen) {
                ivPostStoryRing.setImageDrawable(null);
                ivPostStoryRing.setBackground(
                        com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(2f,
                                getResources().getDisplayMetrics().density));
                ivPostStoryRing.setVisibility(View.VISIBLE);
            } else if (hasAny) {
                ivPostStoryRing.setBackground(null);
                ivPostStoryRing.setImageResource(com.callx.app.core.R.drawable.circle_status_seen);
                ivPostStoryRing.setVisibility(View.VISIBLE);
            } else {
                ivPostStoryRing.setVisibility(View.GONE);
            }
        }
        TextView tvOwner          = holder.tvOwner;
        TextView tvTime           = holder.tvTime;
        TextView tvAudio          = holder.tvAudio;
        TextView tvSuggested      = holder.tvSuggested;
        TextView btnPostFollow    = holder.btnPostFollow;

        // FIX: cardPool.obtain() can hand back a RECYCLED view that was
        // previously bound to a different post — one where tv_post_suggested
        // / tv_post_audio / btn_post_follow ended up VISIBLE, or tv_post_time
        // ended up GONE. Below, each of these is only ever set to its "on"
        // state when its condition is true — none of them get reset back to
        // their XML-default "off" state when the condition is false on THIS
        // bind. That stale leftover state is exactly why some cards' headers
        // showed less info than others (e.g. a photo post reusing a view
        // that was last a "Suggested for you" post could end up with BOTH
        // tv_post_suggested and tv_post_time hidden — no label at all).
        // Reset every one of them to the XML-default state first so each
        // card's header is fully deterministic regardless of prior use.
        if (tvSuggested   != null) tvSuggested.setVisibility(View.GONE);
        if (tvAudio       != null) tvAudio.setVisibility(View.GONE);
        if (btnPostFollow != null) btnPostFollow.setVisibility(View.GONE);
        if (tvTime        != null) tvTime.setVisibility(View.VISIBLE);
        ImageView ivThumb         = holder.ivThumb;
        TextView tvCaption        = holder.tvCaption;
        TextView tvLikes          = holder.tvLikes;
        TextView tvComments       = holder.tvComments;
        TextView tvReposts        = holder.tvReposts;
        ImageButton btnLike       = holder.btnLike;
        ImageButton btnComment    = holder.btnComment;
        ImageButton btnRepost     = holder.btnRepost;
        ImageButton btnSave       = holder.btnSave;
        PlayerView  pvFeed        = holder.pvFeed;
        FrameLayout frameVideo    = holder.frameVideo;
        View        endOverlay    = holder.endOverlay;
        View        watchMore     = holder.watchMore;
        TextView    watchAgain    = holder.watchAgain;
        ImageButton btnMute       = holder.btnMute;
        SeekBar     sbProgress    = holder.sbProgress;
        TextView    tvPosition    = holder.tvPosition;
        TextView    tvSpeedChip   = holder.tvSpeedChip;
        View        playOverlay   = holder.playOverlay;

        // ── Instagram-level approach: Home Feed vs Reels tab ─────────────────
        // Reels tab (fragment_reel_player.xml) is a dedicated fullscreen
        // experience — full device height, full 9:16 video visible.
        // Home Feed is a scrolling list where the reel shares screen space
        // with the header, action bar, caption, and the next card peeking
        // in below — so Instagram deliberately caps the video frame well
        // below full device height (~75% of screen height) rather than
        // giving it the whole 16:9. Width still fills the screen, so with
        // resize_mode="zoom" (center-crop-and-fill) the video is cropped
        // top/bottom to fit that shorter frame — same visual effect as the
        // real Instagram app. This does NOT touch the Reels tab; that stays
        // full 9:16 via fragment_reel_player.xml, untouched by this cap.
        // ★ Ultra-advanced optimization: this height is identical for every
        // card (same screen, same 0.75 cap) — it was being recomputed AND
        // re-applied via setLayoutParams() on every single bind, which
        // forces a full measure/layout pass on frameVideo every time a card
        // scrolls into view, even though the number never actually changes
        // for the life of the fragment. Computed once and cached below;
        // setLayoutParams() is now only called when the height genuinely
        // differs (first bind of a given recycled view, or a config change
        // that invalidated the cache).
        if (frameVideo != null) {
            int videoH = feedCardVideoHeightPx();
            android.view.ViewGroup.LayoutParams lp = frameVideo.getLayoutParams();
            if (lp.height != videoH) {
                lp.height = videoH;
                frameVideo.setLayoutParams(lp);
            }
        }

        // ── Register HomeFeedCard for auto-play ─────────────────────────────
        // v243: cardIndex used to be feedCards.size() (an always-growing
        // append counter); it's now simply the postIndex passed in by
        // FeedAdapter, which is this post's stable position in
        // currentFeedPosts — kept as a local so every closure below (scrub
        // bar, tap/hold listeners, menu actions) needs no further changes.
        final int cardIndex = postIndex;
        HomeFeedCard feedCard = new HomeFeedCard();
        feedCard.rootView   = card;
        feedCard.playerView = pvFeed;
        feedCard.thumbView  = ivThumb;
        feedCard.endOverlay = endOverlay;
        feedCard.videoUrl   = (reel.videoUrl != null && !reel.videoUrl.isEmpty())
                              ? reel.videoUrl
                              : (reel.video480 != null ? reel.video480 : "");
        feedCard.reelId     = reel.reelId;
        feedCard.seekBar     = sbProgress;
        feedCard.tvPosition  = tvPosition;
        feedCard.speedChip   = tvSpeedChip;
        feedCard.playOverlay = playOverlay;
        ensureFeedCardsCapacity(cardIndex + 1);
        feedCards.set(cardIndex, feedCard);

        // Photo-only posts have no timeline to scrub and never autoplay, so
        // the video-only chrome stays hidden for them.
        boolean hasVideo = !feedCard.videoUrl.isEmpty();
        if (!hasVideo) {
            if (sbProgress  != null) sbProgress.setVisibility(View.GONE);
            if (tvPosition  != null) tvPosition.setVisibility(View.GONE);
            if (playOverlay != null) playOverlay.setVisibility(View.GONE);
        } else {
            setupCardScrubBar(feedCard, cardIndex);
        }

        // ── End-of-reel overlay buttons ──────────────────────────────────────
        if (watchMore != null) {
            watchMore.setOnClickListener(x -> {
                if (!isAdded() || getContext() == null) return;
                // Instagram-level: "Watch more reels" after a Home Feed reel
                // finishes drops the user into the actual Reels tab feed,
                // landing on this exact reel — not a generic explore screen.
                Fragment parent = getParentFragment();
                if (parent instanceof ReelsFragment) {
                    ((ReelsFragment) parent).openReelInFeed(reel);
                } else {
                    // Defensive fallback if HomeFragment is ever hosted
                    // somewhere other than inside ReelsFragment.
                    startActivity(new Intent(getContext(), ReelExploreActivity.class));
                }
            });
        }
        if (watchAgain != null) {
            watchAgain.setOnClickListener(x -> {
                if (feedPlayer == null) return;
                // Hide overlay, reset thumb visibility, seek to 0, replay
                if (endOverlay != null) endOverlay.setVisibility(View.GONE);
                if (ivThumb != null) { ivThumb.setAlpha(0f); ivThumb.setVisibility(View.INVISIBLE); }
                if (sbProgress != null) sbProgress.setProgress(0);
                feedPlayer.seekTo(0);
                currentPlayingIndex = cardIndex;
                resumeActiveCard(cardIndex);
            });
        }

        // ── Mute toggle ──────────────────────────────────────────────────────
        if (btnMute != null) {
            btnMute.setOnClickListener(x -> {
                isMuted = !isMuted;
                if (feedPlayer != null) feedPlayer.setVolume(isMuted ? 0f : 1f);
                btnMute.setImageResource(isMuted
                    ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
            });
        }

        // ── Audio track label ────────────────────────────────────────────────
        if (tvAudio != null) {
            String audioLabel = reel.musicName != null && !reel.musicName.isEmpty()
                ? reel.musicName
                : (reel.musicArtist != null && !reel.musicArtist.isEmpty()
                   ? reel.musicArtist + " · Original audio"
                   : null);
            if (audioLabel != null) {
                tvAudio.setText(audioLabel);
                tvAudio.setVisibility(View.VISIBLE);
            }
        }

        // ── "Suggested for you" — shown for non-following posts (For You mode) ──
        final String ownerUidRef = reel.uid != null ? reel.uid : "";
        if (!isFollowingMode && tvSuggested != null && !ownerUidRef.isEmpty()) {
            tvSuggested.setVisibility(android.view.View.VISIBLE);
            if (tvTime   != null) tvTime.setVisibility(android.view.View.GONE);
            if (btnPostFollow != null) {
                // PERF: was an individual Firebase read per card
                // (getReelFollowsRef(myUid).child(ownerUidRef)) — now a single
                // pre-fetched Set lookup, since followedUids is fetched ONCE
                // per feed render in loadFeed()/loadReelsForFeed().
                final boolean[] isFollowed = {followedUids != null && followedUids.contains(ownerUidRef)};
                btnPostFollow.setVisibility(isFollowed[0] ? android.view.View.GONE : android.view.View.VISIBLE);
                btnPostFollow.setOnClickListener(x -> {
                    if (myUid == null || ownerUidRef.isEmpty()) return;
                    isFollowed[0] = true;
                    btnPostFollow.setVisibility(android.view.View.GONE);
                    FirebaseUtils.getReelFollowsRef(myUid).child(ownerUidRef).setValue(true);
                    FirebaseUtils.getReelFollowersRef(ownerUidRef).child(myUid).setValue(true);
                });
            }
        }

        // ── Collab / dual-author header ─────────────────────────────────────
        boolean isCollab = reel.collabInitiatorUid != null && !reel.collabInitiatorUid.isEmpty()
                        && reel.collabColaboratorUid != null && !reel.collabColaboratorUid.isEmpty();
        if (isCollab) {
            // Show collab header: "InitiatorName & CollaboratorName"
            String collabLabel = (reel.collabInitiatorName != null ? reel.collabInitiatorName : "User")
                + " \u2227 " + (reel.collabCollaboratorName != null ? reel.collabCollaboratorName : "User");
            tvOwner.setText(collabLabel);
            // Load collaborator's avatar into a second circle view if one exists in layout
            View collabAvatarContainer = holder.collabAvatarContainer;
            if (collabAvatarContainer instanceof LinearLayout) {
                // Dual-avatar rendering: two overlapping circle images
                LinearLayout collabRow = (LinearLayout) collabAvatarContainer;
                collabRow.setVisibility(View.VISIBLE);
                CircleImageView av2 = collabRow.findViewWithTag("collab_av2");
                if (av2 == null) {
                    av2 = new CircleImageView(requireContext());
                    av2.setTag("collab_av2");
                    int avSize = dpToPx(32);
                    LinearLayout.LayoutParams av2Lp = new LinearLayout.LayoutParams(avSize, avSize);
                    av2Lp.setMarginStart(-dpToPx(10));
                    av2.setLayoutParams(av2Lp);
                    av2.setBorderColor(0xFF111111);
                    av2.setBorderWidth(2);
                    collabRow.addView(av2);
                }
                if (reel.collabCollaboratorPhoto != null && !reel.collabCollaboratorPhoto.isEmpty()) {
                    Glide.with(requireContext()).load(reel.collabCollaboratorPhoto)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person).into(av2);
                }
                // Also load initiator photo into the main avatar
                if (reel.collabInitiatorPhoto != null && !reel.collabInitiatorPhoto.isEmpty()) {
                    Glide.with(requireContext()).load(reel.collabInitiatorPhoto)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person).into(avatar);
                }
            }
            // Collab click → open initiator profile
            tvOwner.setOnClickListener(x -> {
                if (!isAdded() || getContext() == null) return;
                Intent i = new Intent(getContext(), UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID,   reel.collabInitiatorUid);
                i.putExtra(UserReelsActivity.EXTRA_NAME,  reel.collabInitiatorName);
                i.putExtra(UserReelsActivity.EXTRA_PHOTO, reel.collabInitiatorPhoto);
                startActivity(i);
            });
        } else {
            tvOwner.setText(reel.ownerName != null ? "@" + reel.ownerName : "@user");
            tvOwner.setOnClickListener(x -> avatar.performClick());
        }

        if (tvTime != null) tvTime.setText(formatAgo(reel.timestamp));

        // PERF advance — "precompute next reel's UI state": if this card's
        // formatted counts/caption were already computed ahead of time (see
        // ReelUiStatePrecomputer, driven above from attachPlayerToCard() /
        // prefetchUpcomingFeedMedia()), reuse them directly instead of
        // re-running formatCount()/safeCaption() on the bind frame — same
        // cache ReelSocialController/ReelUiController read from on the
        // Reels tab.
        com.callx.app.cache.ReelUiStateCache.State precomputedUi =
            com.callx.app.cache.ReelUiStateCache.get(reel.reelId);

        // ── Expandable caption with "...more" support ───────────────────────
        String captionText = precomputedUi != null
            ? precomputedUi.captionText
            : (reel.caption != null ? reel.caption : "");
        final int CAPTION_MAX_LINES = 2;
        boolean[] captionExpanded = {false};
        View btnReadMore = holder.btnReadMore;

        // Apply hashtag spans
        android.text.SpannableString captionSpannable = buildCaptionSpannable(captionText);
        tvCaption.setText(captionSpannable);
        if (captionSpannable.length() > 0) {
            tvCaption.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
        // Truncate long captions
        if (captionText.length() > 120) {
            tvCaption.setMaxLines(CAPTION_MAX_LINES);
            tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if (btnReadMore != null) {
                btnReadMore.setVisibility(View.VISIBLE);
                btnReadMore.setOnClickListener(rx -> {
                    captionExpanded[0] = !captionExpanded[0];
                    if (captionExpanded[0]) {
                        tvCaption.setMaxLines(Integer.MAX_VALUE);
                        tvCaption.setEllipsize(null);
                        ((TextView) btnReadMore).setText("less");
                    } else {
                        tvCaption.setMaxLines(CAPTION_MAX_LINES);
                        tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        ((TextView) btnReadMore).setText("more");
                    }
                });
            }
        } else {
            if (btnReadMore != null) btnReadMore.setVisibility(View.GONE);
        }

        if (precomputedUi != null) {
            tvLikes.setText(precomputedUi.likesText);
            tvComments.setText(precomputedUi.commentsText);
            tvReposts.setText(precomputedUi.repostText);
        } else {
            tvLikes.setText(formatCount(reel.likesCount));
            tvComments.setText(formatCount(reel.commentsCount));
            tvReposts.setText(formatCount(reel.repostCount));
        }

        // ── Liked state (declared early: needed by slideshow double-tap-to-like below) ──
        final String reelId   = reel.reelId;
        final boolean[] isLiked = {reel.reelId != null && likedIds.contains(reel.reelId)};
        if (btnLike != null) {
            btnLike.setImageResource(isLiked[0]
                ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            setLikeButtonTint(btnLike, isLiked[0]);
        }

        // ── Photo slideshow support ─────────────────────────────────────────
        if (reel.isPhotoSlideshow() && reel.photoUrls != null && !reel.photoUrls.isEmpty()) {
            // Hide the video player frame; show a photo-slideshow ViewPager2 instead
            if (pvFeed != null)     pvFeed.setVisibility(View.GONE);
            if (ivThumb != null)    ivThumb.setVisibility(View.GONE);

            // Build a simple inline photo pager
            ViewPager2 photoPager = new ViewPager2(requireContext());
            photoPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
            int screenW  = getResources().getDisplayMetrics().widthPixels;
            int photoH   = (int)(screenW * 16f / 9f);
            FrameLayout.LayoutParams pagerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, photoH);
            photoPager.setLayoutParams(pagerLp);

            final List<String> photoList = reel.photoUrls;
            photoPager.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                @NonNull @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
                    ImageView iv = new ImageView(parent.getContext());
                    iv.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    return new RecyclerView.ViewHolder(iv) {};
                }
                @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                    Glide.with(requireContext())
                        .load(photoList.get(pos))
                        .centerCrop()
                        .placeholder(R.drawable.ic_reels)
                        .into((ImageView) h.itemView);
                }
                @Override public int getItemCount() { return photoList.size(); }
            });

            // Dot indicator below the pager
            LinearLayout dots = new LinearLayout(requireContext());
            dots.setOrientation(LinearLayout.HORIZONTAL);
            dots.setGravity(android.view.Gravity.CENTER);
            FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
            dotsLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            dotsLp.bottomMargin = dpToPx(8);
            dots.setLayoutParams(dotsLp);

            final View[] dotViews = new View[photoList.size()];
            for (int di = 0; di < photoList.size(); di++) {
                View dot = new View(requireContext());
                int dotSz = dpToPx(6);
                LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSz, dotSz);
                dotLp.setMargins(dpToPx(3), 0, dpToPx(3), 0);
                dot.setLayoutParams(dotLp);
                android.graphics.drawable.GradientDrawable dotBg =
                    new android.graphics.drawable.GradientDrawable();
                dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dotBg.setColor(di == 0 ? 0xFFFFFFFF : 0x66FFFFFF);
                dot.setBackground(dotBg);
                dots.addView(dot);
                dotViews[di] = dot;
            }

            photoPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override public void onPageSelected(int position) {
                    for (int di = 0; di < dotViews.length; di++) {
                        android.graphics.drawable.GradientDrawable d =
                            new android.graphics.drawable.GradientDrawable();
                        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                        d.setColor(di == position ? 0xFFFFFFFF : 0x66FFFFFF);
                        dotViews[di].setBackground(d);
                    }
                }
            });

            // Double-tap to like on slideshow
            photoPager.setOnTouchListener(new View.OnTouchListener() {
                private float downX = 0f, downY = 0f;
                private final GestureDetector gd = new GestureDetector(requireContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDoubleTap(MotionEvent e) {
                            if (myUid == null || reelId == null) return true;
                            if (!isLiked[0]) {
                                isLiked[0] = true;
                                btnLike.setImageResource(R.drawable.ic_heart_filled);
                                setLikeButtonTint(btnLike, true);
                                FirebaseUtils.getReelLikesRef(reelId).child(myUid).setValue(System.currentTimeMillis()); // FIX: timestamp value enables orderByValue().limitToLast(3) recency query for the liker-avatar row
                                FirebaseUtils.getReelLikedByUserRef(myUid).child(reelId)
                                    .setValue(System.currentTimeMillis());
                                try {
                                    int cur = Integer.parseInt(tvLikes.getText().toString());
                                    tvLikes.setText(formatCount(cur + 1));
                                } catch (Exception ignored) {}
                            }
                            if (frameVideo != null) showHeartAnimation(frameVideo);
                            return true;
                        }
                    });
                @Override public boolean onTouch(View v, MotionEvent event) {
                    // FIX: same nested-ViewPager2 tab-switch bug as the Reels
                    // player's photo slideshow — a multi-photo post here sits
                    // inside this same horizontal tab-switch pager, so claim
                    // horizontal drags immediately instead of letting the tab
                    // pager steal them mid-swipe.
                    switch (event.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = event.getRawX();
                            downY = event.getRawY();
                            if (photoList.size() > 1) v.getParent().requestDisallowInterceptTouchEvent(true);
                            break;
                        case MotionEvent.ACTION_MOVE:
                            float dx = Math.abs(event.getRawX() - downX);
                            float dy = Math.abs(event.getRawY() - downY);
                            if (photoList.size() > 1 && dx >= dy) {
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                            break;
                        case MotionEvent.ACTION_UP:
                        case MotionEvent.ACTION_CANCEL:
                            v.getParent().requestDisallowInterceptTouchEvent(false);
                            break;
                    }
                    return gd.onTouchEvent(event);
                }
            });

            if (frameVideo != null) {
                // FIX: addView() with no index appends to the END of
                // frame_video's children — i.e. the TOP of the z-order in a
                // FrameLayout. overlay_post_header (avatar, username, bio,
                // follow button) is already a child of frame_video defined
                // in the XML, so appending the photo pager + dots on top of
                // it completely covered the header — that's why owner
                // bio/details showed on video posts (where pv_feed_post /
                // iv_post_thumb sit at the BOTTOM of frame_video, below the
                // header) but not on photo posts. Insert at index 0/1
                // instead so the photo pager + dots sit at the same
                // bottom layer the video surface normally occupies, and
                // every overlay defined after it in the XML (header, mute
                // button, end-of-reel card, etc.) stays visible on top.
                frameVideo.addView(photoPager, 0);
                frameVideo.addView(dots, 1);
            }
        } else {
            // ── Video frame gestures: double-tap like, tap play/pause, hold 2x ──
            // All three live in ONE touch listener because a View has only one
            // OnTouchListener — a separate listener for the new gestures would
            // silently replace double-tap-to-like.
            if (frameVideo != null) {
                GestureDetector dtGesture = new GestureDetector(requireContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDoubleTap(MotionEvent e) {
                            if (myUid == null || reelId == null) return true;
                            if (!isLiked[0]) {
                                isLiked[0] = true;
                                if (btnLike != null) {
                                    btnLike.setImageResource(R.drawable.ic_heart_filled);
                                    setLikeButtonTint(btnLike, true);
                                }
                                FirebaseUtils.getReelLikesRef(reelId).child(myUid).setValue(System.currentTimeMillis()); // FIX: timestamp value enables orderByValue().limitToLast(3) recency query for the liker-avatar row
                                FirebaseUtils.getReelLikedByUserRef(myUid).child(reelId)
                                    .setValue(System.currentTimeMillis());
                                try {
                                    int cur = Integer.parseInt(tvLikes.getText().toString());
                                    tvLikes.setText(formatCount(cur + 1));
                                } catch (Exception ignored) {}
                            }
                            showHeartAnimation(frameVideo);
                            return true;
                        }
                        /** Single tap (not part of a double-tap) toggles play/pause
                         *  — and is the only way to start a card when the user's
                         *  autoplay setting is "Off" / off-Wi-Fi. */
                        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                            toggleCardPlayback(cardIndex);
                            return true;
                        }
                        /** Press-and-hold = temporary 2x fast-forward. */
                        @Override public void onLongPress(MotionEvent e) {
                            beginSpeedBoost(cardIndex);
                        }
                    });
                dtGesture.setIsLongpressEnabled(true);
                frameVideo.setOnTouchListener((v, ev) -> {
                    boolean handled = dtGesture.onTouchEvent(ev);
                    int action = ev.getActionMasked();
                    if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        // Releasing the finger always ends a boost, even when the
                        // detector itself consumed neither the up nor the cancel.
                        endSpeedBoost(cardIndex);
                    }
                    return handled || action == MotionEvent.ACTION_DOWN;
                });
            }
            if (playOverlay != null) {
                playOverlay.setOnClickListener(x -> toggleCardPlayback(cardIndex));
            }
        }

        // ── Saved state ──
        final boolean[] isSaved = {reel.reelId != null && savedIds.contains(reel.reelId)};
        if (btnSave != null) {
            btnSave.setImageResource(isSaved[0]
                ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        }

        if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
            // THUMB_DECODE_* matches the card's 9:16 frame instead of decoding a
            // square 720x720 and throwing away a third of it — ~44% less
            // bitmap memory per card, which is what bounds GC pressure while
            // flinging through a long feed.
            Glide.with(requireContext()).load(reel.thumbUrl)
                .apply(FEED_IMAGE_OPTS)
                .override(THUMB_DECODE_W, THUMB_DECODE_H)
                .centerCrop().placeholder(R.drawable.ic_reels).into(ivThumb);
        }
        if (reel.ownerPhoto != null && !reel.ownerPhoto.isEmpty()) {
            // The avatar is 36dp; without an override Glide decoded the
            // full-resolution profile photo for it.
            Glide.with(requireContext()).load(reel.ownerPhoto)
                .apply(RequestOptions.circleCropTransform())
                .apply(FEED_IMAGE_OPTS)
                .override(AVATAR_DECODE_PX, AVATAR_DECODE_PX)
                .placeholder(R.drawable.ic_person).into(avatar);
        }

        final String ownerUid = reel.uid;

        // Tap thumbnail → open this specific reel in the player
        ivThumb.setOnClickListener(x -> openReelById(reelId, reel.ownerName));

        // Avatar tap → open user's reel profile
        avatar.setOnClickListener(x -> {
            if (!isAdded() || getContext() == null) return;
            Intent i = new Intent(getContext(), UserReelsActivity.class);
            i.putExtra(UserReelsActivity.EXTRA_UID,   ownerUid);
            i.putExtra(UserReelsActivity.EXTRA_NAME,  reel.ownerName);
            i.putExtra(UserReelsActivity.EXTRA_PHOTO, reel.ownerPhoto);
            startActivity(i);
        });

        // ── Like button ──
        if (btnLike != null) {
            btnLike.setOnClickListener(x -> {
                if (myUid == null || reelId == null) return;
                isLiked[0] = !isLiked[0];
                if (isLiked[0]) {
                    btnLike.setImageResource(R.drawable.ic_heart_filled);
                    setLikeButtonTint(btnLike, true);
                    FirebaseUtils.getReelLikesRef(reelId).child(myUid).setValue(System.currentTimeMillis()); // FIX: timestamp value enables orderByValue().limitToLast(3) recency query for the liker-avatar row
                    FirebaseUtils.getReelLikedByUserRef(myUid).child(reelId)
                        .setValue(System.currentTimeMillis());
                    // Optimistic UI count update
                    try {
                        int cur = Integer.parseInt(tvLikes.getText().toString()
                            .replace("K", "000").replace("M", "000000"));
                        tvLikes.setText(formatCount(cur + 1));
                    } catch (Exception ignored) {}
                } else {
                    btnLike.setImageResource(R.drawable.ic_heart);
                    setLikeButtonTint(btnLike, false);
                    FirebaseUtils.getReelLikesRef(reelId).child(myUid).removeValue();
                    FirebaseUtils.getReelLikedByUserRef(myUid).child(reelId).removeValue();
                }
            });
        }

        // ── Comment button → open ReelCommentActivity ──
        if (btnComment != null) {
            btnComment.setOnClickListener(x -> {
                if (!isAdded() || getContext() == null || reelId == null) return;
                Intent ci = new Intent(getContext(), ReelCommentActivity.class);
                ci.putExtra(ReelCommentActivity.EXTRA_REEL_ID,  reelId);
                ci.putExtra(ReelCommentActivity.EXTRA_REEL_UID, ownerUid != null ? ownerUid : "");
                startActivity(ci);
            });
        }

        // ── Repost button — show options (Repost / Quote Repost / Undo) ──
        if (btnRepost != null) {
            btnRepost.setOnClickListener(x -> {
                if (myUid == null || reelId == null || !isAdded() || getContext() == null) return;
                if (myUid.equals(ownerUid)) {
                    Toast.makeText(requireContext(),
                        "You can't repost your own reel", Toast.LENGTH_SHORT).show();
                    return;
                }
                // Build options dialog
                String[] options = {"Repost", "Quote Repost"};
                AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("Repost options")
                    .setItems(options, (d, which) -> {
                        if (which == 0) {
                            // Instant repost
                            performRepost(reelId, ownerUid, reel, myUid, tvReposts);
                        } else {
                            // Quote Repost — open share sheet pre-filled as quote
                            try {
                                ReelShareSheetFragment sheet = ReelShareSheetFragment.newInstance(
                                    reelId,
                                    reel.videoUrl    != null ? reel.videoUrl    : (reel.video480 != null ? reel.video480 : ""),
                                    reel.thumbUrl    != null ? reel.thumbUrl    : "",
                                    reel.caption     != null ? reel.caption     : "",
                                    ownerUid         != null ? ownerUid         : "",
                                    reel.ownerName   != null ? reel.ownerName   : "",
                                    reel.ownerPhoto  != null ? reel.ownerPhoto  : "",
                                    true
                                );
                                sheet.show(getChildFragmentManager(), "quote_sheet");
                            } catch (Exception e) {
                                // Fallback: system share
                                Intent share = new Intent(Intent.ACTION_SEND);
                                share.setType("text/plain");
                                String quote = "\"" + (reel.caption != null ? reel.caption : "Check this out") + "\" — @" + reel.ownerName + " https://callx.app/reel/" + reelId;
                                share.putExtra(Intent.EXTRA_TEXT, quote);
                                startActivity(Intent.createChooser(share, "Quote Repost"));
                            }
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .create());
            });
        }

        // ── Save button ──
        if (btnSave != null) {
            btnSave.setOnClickListener(x -> {
                if (myUid == null || reelId == null) return;
                isSaved[0] = !isSaved[0];
                if (isSaved[0]) {
                    btnSave.setImageResource(R.drawable.ic_bookmark_filled);
                    FirebaseUtils.getReelSavesRef(myUid).child(reelId).setValue(true);
                    FirebaseUtils.getReelSavesIndexRef(reelId).child(myUid).setValue(true);
                    Toast.makeText(requireContext(), "Saved!", Toast.LENGTH_SHORT).show();
                } else {
                    btnSave.setImageResource(R.drawable.ic_bookmark);
                    FirebaseUtils.getReelSavesRef(myUid).child(reelId).removeValue();
                    FirebaseUtils.getReelSavesIndexRef(reelId).child(myUid).removeValue();
                }
            });
        }

        // ── Send / Share button — open ReelShareSheetFragment ──
        View btnSend = holder.btnSend;
        if (btnSend != null) {
            btnSend.setOnClickListener(x -> {
                if (!isAdded() || getContext() == null || reelId == null) return;
                try {
                    ReelShareSheetFragment sheet = ReelShareSheetFragment.newInstance(
                        reelId,
                        reel.videoUrl  != null ? reel.videoUrl  : (reel.video480 != null ? reel.video480 : ""),
                        reel.thumbUrl  != null ? reel.thumbUrl  : "",
                        reel.caption   != null ? reel.caption   : "",
                        ownerUid       != null ? ownerUid       : "",
                        reel.ownerName != null ? reel.ownerName : "",
                        reel.ownerPhoto != null ? reel.ownerPhoto : "",
                        true
                    );
                    sheet.show(getChildFragmentManager(), "share_sheet");
                } catch (Exception e) {
                    // Fallback to system share if bottom sheet fails
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT,
                        "Check out this reel on CallX! @" + reel.ownerName);
                    startActivity(Intent.createChooser(share, "Share reel"));
                }
            });
        }

        // ── More options (⋮) button ──
        View btnMore = holder.btnMore;
        if (btnMore != null) {
            btnMore.setOnClickListener(x -> {
                if (!isAdded() || getContext() == null) return;
                PopupMenu popup = new PopupMenu(requireContext(), btnMore);
                popup.getMenu().add(0, 1, 0, "Not interested");
                popup.getMenu().add(0, 2, 0, "Report");
                popup.getMenu().add(0, 3, 0, "Copy link");
                if (myUid != null && !myUid.equals(ownerUid)) {
                    popup.getMenu().add(0, 4, 0, "Mute @" + (reel.ownerName != null ? reel.ownerName : "user"));
                    popup.getMenu().add(0, 5, 0, "Block");
                }
                popup.getMenu().add(0, 6, 0, "Open original");
                // Reuses the same ReelOfflineManager the Reels swipe feed's
                // "more" sheet calls (ReelPlayerController.saveReelOffline) —
                // same singleton cache, so a reel saved from either tab is
                // available offline in both.
                popup.getMenu().add(0, 7, 0, "Save for offline");
                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 1: // Not interested — remove from feed optimistically
                            removeFeedRowByReelId(reelId);
                            if (myUid != null && reelId != null) {
                                FirebaseUtils.db().getReference("userNotInterested")
                                    .child(myUid).child(reelId).setValue(true);
                            }
                            return true;
                        case 2: // Report
                            if (myUid == null || reelId == null) return true;
                            String[] reportReasons = {"Spam", "Inappropriate content",
                                "Harassment", "Misinformation", "Kuch aur"};
                            AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(requireContext())
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
                                    Toast.makeText(requireContext(),
                                        "Report submitted — thanks!", Toast.LENGTH_SHORT).show();
                                })
                                .setNegativeButton("Cancel", null).create());
                            return true;
                        case 3: // Copy link
                            ClipboardManager clipboard = (ClipboardManager)
                                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                            if (clipboard != null) {
                                String link = "https://callx.app/reel/" + reelId;
                                clipboard.setPrimaryClip(ClipData.newPlainText("Reel link", link));
                                Toast.makeText(requireContext(),
                                    "Link copied!", Toast.LENGTH_SHORT).show();
                            }
                            return true;
                        case 4: // Mute
                            if (myUid != null && ownerUid != null) {
                                FirebaseUtils.db().getReference("muted")
                                    .child(myUid).child(ownerUid).setValue(true);
                                Toast.makeText(requireContext(),
                                    "Muted @" + reel.ownerName, Toast.LENGTH_SHORT).show();
                            }
                            return true;
                        case 5: // Block
                            if (myUid != null && ownerUid != null) {
                                AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Block @" + reel.ownerName + "?")
                                    .setMessage("They won't be able to find your profile or reels.")
                                    .setPositiveButton("Block", (d, w) -> {
                                        FirebaseUtils.getBlocksRef(myUid).child(ownerUid).setValue(true);
                                        removeFeedRowByReelId(reelId);
                                        Toast.makeText(requireContext(),
                                            "Blocked", Toast.LENGTH_SHORT).show();
                                    })
                                    .setNegativeButton("Cancel", null).create());
                            }
                            return true;
                        case 6: // Open original
                            openReelById(reelId, reel.ownerName);
                            return true;
                        case 7: // Save for offline
                            saveHomeReelOffline(reel);
                            return true;
                    }
                    return false;
                });
                popup.show();
            });
        }

        // ★ Register with the off-screen media windower BEFORE the card is
        // attached — its own avatar/thumbnail Glide loads above already
        // dispatched, so it starts out in the "loaded" state and only gets
        // unloaded once it actually scrolls far enough away.
        if (feedWindowManager != null) {
            feedWindowManager.registerCard(card, avatar, reel.ownerPhoto, ivThumb, reel.thumbUrl);
        }
        // v243: no containerFeed.addView(card) — `card` IS the RecyclerView
        // ViewHolder's itemView already; FeedAdapter/RecyclerView own attach.
    }

    /**
     * Save a Home feed reel for offline playback — same ReelOfflineManager
     * singleton and download flow as ReelPlayerController.saveReelOffline()
     * on the Reels tab, so a reel saved from either tab is available
     * offline in both (shared cache, shared catalog).
     */
    private void saveHomeReelOffline(ReelModel reel) {
        if (!isAdded() || getContext() == null || reel == null || reel.reelId == null) return;
        if (offlineManager == null) offlineManager = com.callx.app.player.ReelOfflineManager.get(requireContext());
        if (offlineManager.isAvailableOffline(reel.reelId)) {
            Toast.makeText(requireContext(), "Already saved for offline viewing", Toast.LENGTH_SHORT).show();
            return;
        }
        offlineManager.downloadForOffline(reel);
        Toast.makeText(requireContext(), "Saving reel for offline viewing…", Toast.LENGTH_SHORT).show();
    }

    /** Animate a floating heart on double-tap (Instagram-style) */
    private void showHeartAnimation(FrameLayout container) {
        if (container == null || !isAdded() || getContext() == null) return;
        ImageView heart = new ImageView(requireContext());
        heart.setImageResource(R.drawable.ic_heart_filled);
        int size = dpToPx(72);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = android.view.Gravity.CENTER;
        heart.setLayoutParams(lp);
        heart.setAlpha(0f);
        heart.setScaleX(0f);
        heart.setScaleY(0f);
        container.addView(heart);

        AnimatorSet anim = new AnimatorSet();
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(heart, "scaleX", 0f, 1.3f, 1.0f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(heart, "scaleY", 0f, 1.3f, 1.0f);
        ObjectAnimator alpha  = ObjectAnimator.ofFloat(heart, "alpha",  0f, 1f,   1f, 0f);
        scaleX.setDuration(500);
        scaleY.setDuration(500);
        alpha.setDuration(700);
        anim.playTogether(scaleX, scaleY, alpha);
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                container.removeView(heart);
            }
        });
        anim.start();
    }

    /**
     * Build a SpannableString that highlights #hashtags and @mentions in the caption.
     * Hashtag taps open HashtagReelsActivity.
     */
    private android.text.SpannableString buildCaptionSpannable(String text) {
        if (text == null || text.isEmpty())
            return new android.text.SpannableString("");
        android.text.SpannableString span = new android.text.SpannableString(text);
        java.util.regex.Matcher m = HASHTAG_PATTERN.matcher(text);
        while (m.find()) {
            final String tag = m.group(1);
            final int s = m.start(), e = m.end();
            span.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(@NonNull android.view.View w) {
                    if (!isAdded() || getContext() == null || tag == null) return;
                    Intent hi = new Intent(getContext(), HashtagReelsActivity.class);
                    hi.putExtra("hashtag", tag);
                    startActivity(hi);
                }
                @Override public void updateDrawState(@NonNull android.text.TextPaint ds) {
                    ds.setColor(0xFF00C6FF); ds.setUnderlineText(false);
                }
            }, s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    /**
     * Execute an instant repost: Firebase writes + WorkManager notification + optimistic UI.
     */
    private void performRepost(String reelId, String ownerUid, ReelModel reel,
                               String myUid, TextView tvReposts) {
        long now = System.currentTimeMillis();
        com.google.firebase.database.FirebaseDatabase db =
            com.google.firebase.database.FirebaseDatabase.getInstance(
                com.callx.app.utils.Constants.DB_URL);
        db.getReference("reelReposts").child(reelId).child(myUid).setValue(now);
        db.getReference("userReposts").child(myUid).child(reelId).setValue(now);
        db.getReference("reels").child(reelId).child("repostCount")
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @NonNull @Override
                public com.google.firebase.database.Transaction.Result doTransaction(
                        @NonNull com.google.firebase.database.MutableData d) {
                    Integer c = d.getValue(Integer.class);
                    d.setValue(c != null ? c + 1 : 1);
                    return com.google.firebase.database.Transaction.success(d);
                }
                @Override public void onComplete(
                        com.google.firebase.database.DatabaseError e,
                        boolean committed,
                        com.google.firebase.database.DataSnapshot s) {}
            });
        ReelRepostWorker.enqueue(requireContext(), reelId, myUid,
            FirebaseUtils.getCurrentName(), ownerUid, reel.ownerName, reel.thumbUrl);
        Toast.makeText(requireContext(), "Reposted!", Toast.LENGTH_SHORT).show();
        try {
            int cur = Integer.parseInt(tvReposts.getText().toString());
            tvReposts.setText(formatCount(cur + 1));
        } catch (Exception ignored) {
            tvReposts.setText(formatCount(reel.repostCount + 1));
        }
    }

    /** Opens SingleReelPlayerActivity by reel ID directly */
    private void openReelById(String reelId, String ownerName) {
        if (!isAdded() || getContext() == null || reelId == null) return;
        Intent i = new Intent(getContext(), SingleReelPlayerActivity.class);
        ArrayList<String> ids = new ArrayList<>();
        ids.add(reelId);
        i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
        i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, 0);
        i.putExtra(SingleReelPlayerActivity.EXTRA_TITLE,
            ownerName != null ? ownerName + "'s Reel" : "Reel");
        startActivity(i);
    }

    // ── Trending ─────────────────────────────────────────────────────────

    private void loadTrending() {
        FirebaseUtils.getReelsRef()
            .orderByChild("likesCount")
            .limitToLast(8)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    List<ReelModel> reels = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        ReelModel r = s.getValue(ReelModel.class);
                        if (r != null) {
                            if (r.reelId == null) r.reelId = s.getKey();
                            reels.add(r);
                        }
                    }
                    Collections.reverse(reels); // highest first
                    renderTrending(reels);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (pbTrending != null) pbTrending.setVisibility(View.GONE);
                    });
                }
            });
    }

    private void renderTrending(List<ReelModel> reels) {
        if (!isAdded() || getContext() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (containerTrending == null || !isAdded()) return;
            if (pbTrending != null) pbTrending.setVisibility(View.GONE);

            for (ReelModel reel : reels) {
                View card = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_home_trending, containerTrending, false);

                ImageView thumb  = card.findViewById(R.id.iv_trending_thumb);
                TextView tvLikes = card.findViewById(R.id.tv_trending_likes);
                TextView tvOwner = card.findViewById(R.id.tv_trending_owner);

                tvLikes.setText("❤ " + formatCount(reel.likesCount));
                tvOwner.setText(reel.ownerName != null ? "@" + reel.ownerName : "@user");

                if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
                    Glide.with(requireContext()).load(reel.thumbUrl).apply(FEED_IMAGE_OPTS)
                        .centerCrop().override(STRIP_THUMB_DECODE_PX, STRIP_THUMB_DECODE_PX).into(thumb);
                }

                // ✅ Open specific reel in the player (not just showReelFeed)
                final String reelId = reel.reelId;
                final String name   = reel.ownerName;
                card.setOnClickListener(v -> openReelById(reelId, name));

                containerTrending.addView(card);
            }

            if (reels.isEmpty()) {
                TextView empty = new TextView(requireContext());
                empty.setText("No trending reels yet");
                empty.setTextColor(0xFF888888);
                empty.setPadding(0, 8, 0, 8);
                containerTrending.addView(empty);
            }
        });
    }

    // ── Friends Activity ─────────────────────────────────────────────────

    private void loadFriendsActivity() {
        String myUid = safeMyUid();
        if (myUid == null) {
            if (pbActivity != null) pbActivity.setVisibility(View.GONE);
            return;
        }

        FirebaseUtils.db().getReference("reel_notifications").child(myUid)
            .orderByChild("timestamp")
            .limitToLast(10)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    List<Map<String, Object>> activities = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        Map<String, Object> item = new HashMap<>();
                        String type     = s.child("type").getValue(String.class);
                        String message  = s.child("message").getValue(String.class);
                        String fromUid  = s.child("from_uid").getValue(String.class);
                        String fromPhoto= s.child("from_photo").getValue(String.class);
                        String fromThumb= s.child("from_thumb").getValue(String.class);
                        Long   ts       = s.child("timestamp").getValue(Long.class);
                        String resolvedPhoto = (fromThumb != null && !fromThumb.isEmpty()) ? fromThumb : (fromPhoto != null ? fromPhoto : "");
                        if (message != null) {
                            item.put("message",    message);
                            item.put("timestamp",  ts != null ? ts : 0L);
                            item.put("type",       type != null ? type : "like");
                            item.put("from_uid",   fromUid != null ? fromUid : "");
                            item.put("from_photo", resolvedPhoto);
                            activities.add(item);
                        }
                    }
                    activities.sort((a, b) ->
                        Long.compare((Long) b.get("timestamp"), (Long) a.get("timestamp")));
                    renderFriendsActivity(activities);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (pbActivity != null) pbActivity.setVisibility(View.GONE);
                    });
                }
            });
    }

    @SuppressWarnings("unchecked")
    private void renderFriendsActivity(List<Map<String, Object>> activities) {
        if (!isAdded() || getContext() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (containerFriendsActivity == null || !isAdded()) return;
            if (pbActivity != null) pbActivity.setVisibility(View.GONE);

            if (activities.isEmpty()) {
                TextView empty = new TextView(requireContext());
                empty.setText("No recent activity from friends");
                empty.setTextColor(0xFF888888);
                empty.setTextSize(12f);
                empty.setPadding(0, 8, 0, 8);
                containerFriendsActivity.addView(empty);
                return;
            }

            for (Map<String, Object> act : activities) {
                String message  = (String) act.get("message");
                Long   ts       = (Long)   act.get("timestamp");
                String type     = (String) act.get("type");
                String fromPhoto= (String) act.get("from_photo");

                LinearLayout row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                int dp12 = dpToPx(12);
                int dp8  = dpToPx(8);
                row.setPadding(0, dp8, 0, dp8);

                // Mini avatar
                CircleImageView miniAvatar = new CircleImageView(requireContext());
                int sz = dpToPx(32);
                LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(sz, sz);
                avLp.setMarginEnd(dp12);
                miniAvatar.setLayoutParams(avLp);
                miniAvatar.setImageResource(R.drawable.ic_person);
                if (fromPhoto != null && !fromPhoto.isEmpty()) {
                    Glide.with(requireContext()).load(fromPhoto)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person).into(miniAvatar);
                }
                row.addView(miniAvatar);

                // Type icon
                ImageView icon = new ImageView(requireContext());
                int iconRes = "repost".equals(type) ? R.drawable.ic_repost
                    : "comment".equals(type) ? R.drawable.ic_comment_reel
                    : "follow".equals(type) ? R.drawable.ic_person
                    : R.drawable.ic_heart_filled;
                icon.setImageResource(iconRes);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(16), dpToPx(16));
                iconLp.setMarginEnd(dpToPx(6));
                icon.setLayoutParams(iconLp);
                row.addView(icon);

                // Message
                TextView tvMsg = new TextView(requireContext());
                tvMsg.setText(message);
                tvMsg.setTextColor(0xFFDDDDDD);
                tvMsg.setTextSize(12.5f);
                LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvMsg.setLayoutParams(msgLp);
                row.addView(tvMsg);

                // Time
                TextView tvTime = new TextView(requireContext());
                tvTime.setText(ts != null ? formatAgo(ts) : "");
                tvTime.setTextColor(0xFF888888);
                tvTime.setTextSize(11f);
                row.addView(tvTime);

                containerFriendsActivity.addView(row);

                View divider = new View(requireContext());
                divider.setBackgroundColor(0x1AFFFFFF);
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
                containerFriendsActivity.addView(divider);
            }
        });
    }

    // ── Continue Watching ─────────────────────────────────────────────────

    private void loadContinueWatching() {
        String myUid = safeMyUid();
        if (myUid == null) {
            if (pbContinue != null) pbContinue.setVisibility(View.GONE);
            return;
        }

        FirebaseUtils.getReelWatchHistoryRef(myUid)
            .orderByValue()
            .limitToLast(8)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    List<String> reelIds = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) reelIds.add(s.getKey());
                    Collections.reverse(reelIds);
                    if (reelIds.isEmpty()) {
                        requireActivity().runOnUiThread(() -> {
                            if (pbContinue != null) pbContinue.setVisibility(View.GONE);
                            if (!isAdded() || getContext() == null) return;
                            TextView empty = new TextView(requireContext());
                            empty.setText("No watch history yet");
                            empty.setTextColor(0xFF888888);
                            empty.setTextSize(12f);
                            empty.setPadding(0, 8, 0, 8);
                            if (containerContinueWatching != null)
                                containerContinueWatching.addView(empty);
                        });
                        return;
                    }
                    loadReelsByIds(reelIds, 0);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (pbContinue != null) pbContinue.setVisibility(View.GONE);
                    });
                }
            });
    }

    /** ★ Ultra-advanced optimization: the old implementation fetched each of
     *  the (up to 8) Continue Watching reels ONE AT A TIME — reelId[i+1]'s
     *  fetch only started after reelId[i]'s fetch AND its card render had
     *  both finished. None of these per-reel reads depend on each other (a
     *  watch-history reelId list is already fully known up front), so this
     *  is the same class of unnecessary serialization as the old stories
     *  loader (see collectStoryEntriesParallel) — just fixed here for the
     *  Continue Watching strip. Fires all reads concurrently and renders
     *  cards in the ORIGINAL (most-recently-watched-first) order via an
     *  index-addressed slot array + a join counter, exactly mirroring the
     *  stories fix; same reasoning applies for why plain int counters are
     *  safe (Firebase Android callbacks land on the main thread one at a
     *  time even though the underlying reads race in parallel). */
    private void loadReelsByIds(List<String> ids, int index) {
        if (!isAdded() || getContext() == null) return;
        if (pbContinue != null) requireActivity().runOnUiThread(() -> pbContinue.setVisibility(View.GONE));
        if (ids.isEmpty()) return;

        final int total = ids.size();
        final ReelModel[] slots = new ReelModel[total];
        final int[] remaining = { total };

        for (int i = 0; i < total; i++) {
            final int slot = i;
            String reelId = ids.get(i);
            FirebaseUtils.getReelsRef().child(reelId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!isAdded() || getContext() == null) return;
                        ReelModel r = snap.getValue(ReelModel.class);
                        if (r != null && r.reelId == null) r.reelId = reelId;
                        slots[slot] = r; // null (deleted reel) is a valid, skippable slot
                        finishOneContinueWatchingSlot(slots, remaining);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        finishOneContinueWatchingSlot(slots, remaining);
                    }
                });
        }
    }

    private void finishOneContinueWatchingSlot(ReelModel[] slots, int[] remaining) {
        remaining[0]--;
        if (remaining[0] != 0) return;
        if (!isAdded() || getContext() == null) return;
        for (ReelModel r : slots) {
            if (r != null) addContinueWatchingCard(r);
        }
    }

    private void addContinueWatchingCard(ReelModel reel) {
        if (!isAdded() || getContext() == null || containerContinueWatching == null) return;
        requireActivity().runOnUiThread(() -> {
            if (!isAdded() || getContext() == null) return;
            View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_home_continue_watching, containerContinueWatching, false);

            ImageView ivThumb   = card.findViewById(R.id.iv_cw_thumb);
            TextView  tvOwner   = card.findViewById(R.id.tv_cw_owner);
            ProgressBar pbWatch = card.findViewById(R.id.pb_cw_progress);

            tvOwner.setText(reel.ownerName != null ? "@" + reel.ownerName : "@user");

            if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
                Glide.with(requireContext()).load(reel.thumbUrl).apply(FEED_IMAGE_OPTS)
                    .centerCrop().override(STRIP_THUMB_DECODE_PX, STRIP_THUMB_DECODE_PX).into(ivThumb);
            }

            final String reelId = reel.reelId;
            final String name   = reel.ownerName;
            String myUid = safeMyUid();
            if (pbWatch != null && myUid != null && reelId != null) {
                FirebaseUtils.getReelWatchProgressRef(myUid).child(reelId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            if (!isAdded() || getContext() == null) return;
                            Integer pct = snap.getValue(Integer.class);
                            if (pct != null && pct > 0) {
                                requireActivity().runOnUiThread(() -> {
                                    if (pbWatch != null) pbWatch.setProgress(pct);
                                });
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
            }

            card.setOnClickListener(v -> openReelById(reelId, name));
            containerContinueWatching.addView(card);
        });
    }

    private void clearWatchHistory() {
        String myUid = safeMyUid();
        if (myUid == null || !isAdded() || getContext() == null) return;
        FirebaseUtils.getReelWatchHistoryRef(myUid).removeValue();
        if (containerContinueWatching != null) {
            clearContainerKeepLoader(containerContinueWatching);
            TextView empty = new TextView(requireContext());
            empty.setText("Watch history cleared");
            empty.setTextColor(0xFF888888);
            empty.setTextSize(12f);
            empty.setPadding(0, 8, 0, 8);
            containerContinueWatching.addView(empty);
        }
        Toast.makeText(requireContext(), "Watch history cleared", Toast.LENGTH_SHORT).show();
    }

    // ── Suggested Creators ────────────────────────────────────────────────

    private void loadSuggestedCreators() {
        if (containerSuggestedCreators == null) return;
        String myUid = safeMyUid();

        // Load top creators by reelCount, exclude self
        FirebaseUtils.db().getReference("users")
            .orderByChild("reelCount")
            .limitToLast(12)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    List<String[]> creators = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String uid   = s.getKey();
                        if (uid == null || uid.equals(myUid)) continue;
                        String name  = s.child("name").getValue(String.class);
                        String _cPhoto = s.child("photoUrl").getValue(String.class);
                        String _cThumb = s.child("thumbUrl").getValue(String.class);
                        String photo = (_cThumb != null && !_cThumb.isEmpty()) ? _cThumb : _cPhoto;
                        Long rc      = s.child("reelCount").getValue(Long.class);
                        if (name != null)
                            creators.add(new String[]{
                                uid,
                                name,
                                photo != null ? photo : "",
                                rc != null ? formatCount(rc.intValue()) + " reels" : "Creator"
                            });
                    }
                    Collections.reverse(creators);
                    renderSuggestedCreators(creators, myUid);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(() -> {
                        if (pbSuggested != null) pbSuggested.setVisibility(View.GONE);
                    });
                }
            });
    }

    private void renderSuggestedCreators(List<String[]> creators, String myUid) {
        if (!isAdded() || getContext() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (containerSuggestedCreators == null || !isAdded()) return;
            if (pbSuggested != null) pbSuggested.setVisibility(View.GONE);

            // First fetch the followed set for correct button state
            if (myUid == null) {
                addCreatorCards(creators, new HashSet<>());
                return;
            }
            FirebaseUtils.getReelFollowsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    Set<String> followed = new HashSet<>();
                    for (DataSnapshot s : snap.getChildren()) followed.add(s.getKey());
                    if (isAdded() && getContext() != null)
                        requireActivity().runOnUiThread(() -> addCreatorCards(creators, followed));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (isAdded() && getContext() != null)
                        requireActivity().runOnUiThread(() -> addCreatorCards(creators, new HashSet<>()));
                }
            });
        });
    }

    private void addCreatorCards(List<String[]> creators, Set<String> followedUids) {
        if (containerSuggestedCreators == null || !isAdded() || getContext() == null) return;
        String myUid = safeMyUid();

        for (String[] c : creators) {
            String uid   = c[0];
            String name  = c[1];
            String photo = c[2];
            String sub   = c[3];

            LinearLayout card = new LinearLayout(requireContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            int w = dpToPx(90);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
            cardLp.setMarginEnd(dpToPx(10));
            card.setLayoutParams(cardLp);
            card.setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8));
            card.setBackgroundResource(R.drawable.bg_speed_chip);

            // Avatar
            CircleImageView av = new CircleImageView(requireContext());
            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dpToPx(56), dpToPx(56));
            av.setLayoutParams(avLp);
            av.setImageResource(R.drawable.ic_person);
            if (!photo.isEmpty()) {
                Glide.with(requireContext()).load(photo)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(av);
            }
            card.addView(av);

            // Name
            TextView tvName = new TextView(requireContext());
            tvName.setText(name);
            tvName.setTextSize(11f);
            tvName.setTextColor(0xFFFFFFFF);
            tvName.setMaxLines(1);
            tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tvName.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nameLp.topMargin = dpToPx(4);
            tvName.setLayoutParams(nameLp);
            card.addView(tvName);

            // Subtitle (reel count)
            TextView tvSub = new TextView(requireContext());
            tvSub.setText(sub);
            tvSub.setTextSize(10f);
            tvSub.setTextColor(0xFF888888);
            tvSub.setGravity(android.view.Gravity.CENTER);
            card.addView(tvSub);

            // Follow / Following button
            final boolean[] isFollowed = {followedUids.contains(uid)};
            Button btnFollow = new Button(requireContext());
            btnFollow.setText(isFollowed[0] ? "Following" : "Follow");
            btnFollow.setTextSize(10f);
            btnFollow.setAllCaps(false);
            btnFollow.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(28));
            btnLp.topMargin = dpToPx(4);
            btnFollow.setLayoutParams(btnLp);
            if (isFollowed[0]) {
                btnFollow.setBackgroundColor(0xFF333333);
                btnFollow.setTextColor(0xFFCCCCCC);
            } else {
                btnFollow.setBackgroundColor(getResources().getColor(R.color.brand_primary, null));
                btnFollow.setTextColor(0xFFFFFFFF);
            }

            final String creatorUid = uid;
            btnFollow.setOnClickListener(vv -> {
                if (myUid == null) return;
                isFollowed[0] = !isFollowed[0];
                if (isFollowed[0]) {
                    FirebaseUtils.getReelFollowsRef(myUid).child(creatorUid).setValue(true);
                    FirebaseUtils.getReelFollowersRef(creatorUid).child(myUid).setValue(true);
                    btnFollow.setText("Following");
                    btnFollow.setBackgroundColor(0xFF333333);
                    btnFollow.setTextColor(0xFFCCCCCC);
                } else {
                    FirebaseUtils.getReelFollowsRef(myUid).child(creatorUid).removeValue();
                    FirebaseUtils.getReelFollowersRef(creatorUid).child(myUid).removeValue();
                    btnFollow.setText("Follow");
                    btnFollow.setBackgroundColor(getResources().getColor(R.color.brand_primary, null));
                    btnFollow.setTextColor(0xFFFFFFFF);
                }
            });
            card.addView(btnFollow);

            // Card click → open user's reels
            card.setOnClickListener(vv -> {
                if (!isAdded() || getContext() == null) return;
                Intent i = new Intent(getContext(), UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID,   uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME,  name);
                i.putExtra(UserReelsActivity.EXTRA_PHOTO, photo);
                startActivity(i);
            });

            containerSuggestedCreators.addView(card);
        }

        if (creators.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("No suggestions yet");
            empty.setTextColor(0xFF888888);
            empty.setTextSize(12f);
            empty.setPadding(0, 8, 0, 8);
            containerSuggestedCreators.addView(empty);
        }
    }

    // ── My avatar ─────────────────────────────────────────────────────────

    private void loadMyAvatar() {
        String myUid = safeMyUid();
        if (myUid == null || ivMyStoryAvatar == null) return;
        // Reels profile avatar load karo (reels/users/{uid})
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded() || getContext() == null) return;
                String thumb = snap.child("thumbUrl").getValue(String.class);
                String photo = snap.child("photoUrl").getValue(String.class);
                String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                if (url != null && !url.isEmpty()) {
                    Glide.with(requireContext()).load(url)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person)
                        .override(96, 96)
                        .into(ivMyStoryAvatar);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    /** v243: pb_feed_loading used to be a permanent view sitting right below
     *  container_feed; it's now a ROW_LOADING entry in feedItems so it
     *  scrolls as part of the same RecyclerView instead of living outside it. */
    private void showFeedLoading(boolean show) {
        if (!isAdded() || getActivity() == null || feedAdapter == null) return;
        getActivity().runOnUiThread(() -> {
            if (feedAdapter == null) return;
            int existing = findFeedRow(ROW_LOADING);
            if (show) {
                if (existing < 0) {
                    feedItems.add(new FeedRow(ROW_LOADING));
                    feedAdapter.notifyItemInserted(FEED_HEADER_OFFSET + feedItems.size() - 1);
                }
            } else if (existing >= 0) {
                feedItems.remove(existing);
                feedAdapter.notifyItemRemoved(FEED_HEADER_OFFSET + existing);
            }
        });
    }

    private void showFeedEmpty(boolean show) {
        if (!isAdded() || getActivity() == null || feedAdapter == null) return;
        getActivity().runOnUiThread(() -> {
            if (feedAdapter == null) return;
            int existing = findFeedRow(ROW_EMPTY);
            if (show) {
                if (existing < 0) {
                    feedItems.add(new FeedRow(ROW_EMPTY));
                    feedAdapter.notifyItemInserted(FEED_HEADER_OFFSET + feedItems.size() - 1);
                }
            } else if (existing >= 0) {
                feedItems.remove(existing);
                feedAdapter.notifyItemRemoved(FEED_HEADER_OFFSET + existing);
            }
        });
    }

    /** First feedItems index whose type matches, or -1. Only ever a handful
     *  of rows of a given singleton type (loading/empty) so a linear scan
     *  is fine. */
    private int findFeedRow(int type) {
        for (int i = 0; i < feedItems.size(); i++) {
            if (feedItems.get(i).type == type) return i;
        }
        return -1;
    }

    @Nullable
    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { return null; }
    }

    private String formatCount(int n) {
        if (n >= 1_000_000) return String.format(java.util.Locale.US, "%.1fM", n / 1_000_000f);
        if (n >= 1_000)     return String.format(java.util.Locale.US, "%.1fK", n / 1_000f);
        return String.valueOf(n);
    }

    private String formatCount(long n) {
        return formatCount((int) n);
    }

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

    private int dpToPx(int dp) {
        if (getContext() == null) return dp * 3;
        return (int)(dp * getContext().getResources().getDisplayMetrics().density);
    }

    /** Cached feed-card video-frame height (see addFeedPostCard's comment) —
     *  same value for every card, so it's computed once instead of on every
     *  bind. -1 means "not computed yet / invalidated". */
    private int cachedFeedVideoH = -1;

    private int feedCardVideoHeightPx() {
        if (cachedFeedVideoH > 0 || getContext() == null) {
            return cachedFeedVideoH > 0 ? cachedFeedVideoH : 0;
        }
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int full916H = (int) (dm.widthPixels * 16f / 9f);
        int feedCapH = (int) (dm.heightPixels * 0.75f);
        cachedFeedVideoH = Math.min(full916H, feedCapH);
        return cachedFeedVideoH;
    }

    /** Same red used by the full-screen reel player's like button (#FF416C) —
     *  keeps the liked-state color consistent between the home feed's inline
     *  reel card and ReelPlayerFragment/ReelSocialController. */
    private void setLikeButtonTint(ImageButton btnLike, boolean liked) {
        if (btnLike == null) return;
        // FIX (light mode): the "not liked" state used to be hardcoded
        // Color.WHITE, so the heart icon stayed white (invisible-ish) even
        // when the action bar switched to a light background. Now it uses
        // the theme-aware text_primary color, same as the other action-bar
        // icons, so it flips to dark in light mode and stays white in dark
        // mode automatically.
        int tint = liked
            ? Color.parseColor("#FF416C")
            : btnLike.getContext().getResources().getColor(R.color.text_primary, null);
        btnLike.setImageTintList(ColorStateList.valueOf(tint));
    }

    // ══════════════════════════════════════════════════════════════════════
    // ── v243: FeedAdapter — the single top-level RecyclerView adapter ──────
    // Position 0 = header (stories/toggle), last position = footer sections
    // (trending/suggested/activity/continue-watching), everything between is
    // feedItems (posts + interleaved suggested rows + banner/loading/empty/
    // load-more states). Post/suggested-row content is rebuilt fresh on
    // every bind (same view-construction code that used to run once per
    // append), which is what actually bounds memory/view count now — a
    // handful of live ViewHolders instead of one permanent View per post.
    // ══════════════════════════════════════════════════════════════════════
    private class FeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final View headerView;
        private final View footerView;

        FeedAdapter(View headerView, View footerView) {
            this.headerView = headerView;
            this.footerView = footerView;
            setHasStableIds(false);
        }

        @Override public int getItemCount() { return feedItems.size() + 2; }

        @Override public int getItemViewType(int position) {
            if (position == 0) return VT_HEADER;
            if (position == getItemCount() - 1) return VT_FOOTER;
            return feedItems.get(position - FEED_HEADER_OFFSET).type;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
            switch (vt) {
                case VT_HEADER: {
                    // Singleton — headerView only ever backs position 0, so
                    // reparent-if-needed instead of inflating a duplicate.
                    detachFromCurrentParent(headerView);
                    return new SimpleRowHolder(headerView);
                }
                case VT_FOOTER: {
                    detachFromCurrentParent(footerView);
                    return new SimpleRowHolder(footerView);
                }
                case ROW_POST: {
                    View v = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_home_feed_post, parent, false);
                    PostRowHolder prh = new PostRowHolder(v);
                    // ★ Cache all 28 child-view references ONCE here, at
                    // inflate time — not on every bind. See PostRowHolder doc.
                    prh.cacheViews();
                    return prh;
                }
                default: {
                    // ROW_SUGGESTED_CREATORS / ROW_SUGGESTED_REELS / ROW_NEW_POSTS_BANNER /
                    // ROW_LOADING / ROW_EMPTY / ROW_LOAD_MORE_FOOTER — all a
                    // bare FrameLayout whose real content gets built fresh
                    // into it at bind time by the matching bindXContent()/
                    // showX-style helper.
                    FrameLayout container = new FrameLayout(parent.getContext());
                    container.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new SimpleRowHolder(container);
                }
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int vt = getItemViewType(position);
            switch (vt) {
                case VT_HEADER:
                case VT_FOOTER:
                    // Static content; already populated by bindHeaderViews()/
                    // bindFooterViews() and kept live by the rest of the
                    // fragment's existing code (loadStories, loadTrending, ...).
                    return;
                case ROW_POST: {
                    FeedRow row = feedItems.get(position - FEED_HEADER_OFFSET);
                    PostRowHolder h = (PostRowHolder) holder;
                    h.boundPostIndex = row.postIndex;
                    if (row.postIndex >= 0 && row.postIndex < currentFeedPosts.size()) {
                        ReelModel reel = currentFeedPosts.get(row.postIndex);
                        addFeedPostCard(h, row.postIndex, reel,
                            cachedLikedIds != null ? cachedLikedIds : new HashSet<>(),
                            cachedSavedIds != null ? cachedSavedIds : new HashSet<>(),
                            cachedMyUidForFeed, cachedFollowedUids != null ? cachedFollowedUids : new HashSet<>());
                    }
                    return;
                }
                case ROW_SUGGESTED_CREATORS: {
                    FeedRow row = feedItems.get(position - FEED_HEADER_OFFSET);
                    bindSuggestedCreatorsRowContent((ViewGroup) holder.itemView, row.creatorPool);
                    return;
                }
                case ROW_SUGGESTED_REELS: {
                    int feedIdx = position - FEED_HEADER_OFFSET;
                    FeedRow row = feedItems.get(feedIdx);
                    bindSuggestedReelsRowContent((ViewGroup) holder.itemView, row.reelPool, () -> {
                        int i = feedItems.indexOf(row);
                        if (i >= 0) {
                            feedItems.remove(i);
                            notifyItemRemoved(FEED_HEADER_OFFSET + i);
                        }
                    });
                    return;
                }
                case ROW_NEW_POSTS_BANNER:
                    bindNewPostsBannerContent((ViewGroup) holder.itemView);
                    return;
                case ROW_LOADING: {
                    ViewGroup c = (ViewGroup) holder.itemView;
                    c.removeAllViews();
                    ProgressBar pb = new ProgressBar(c.getContext());
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        dpToPx(32), dpToPx(32));
                    lp.gravity = android.view.Gravity.CENTER;
                    lp.topMargin = dpToPx(24);
                    lp.bottomMargin = dpToPx(24);
                    pb.setLayoutParams(lp);
                    c.addView(pb);
                    return;
                }
                case ROW_EMPTY: {
                    ViewGroup c = (ViewGroup) holder.itemView;
                    c.removeAllViews();
                    TextView tv = new TextView(c.getContext());
                    tv.setText("No posts yet");
                    tv.setTextColor(0xFF888888);
                    tv.setTextSize(14f);
                    tv.setGravity(android.view.Gravity.CENTER);
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = dpToPx(48);
                    lp.bottomMargin = dpToPx(48);
                    tv.setLayoutParams(lp);
                    c.addView(tv);
                    return;
                }
                case ROW_LOAD_MORE_FOOTER: {
                    ViewGroup c = (ViewGroup) holder.itemView;
                    c.removeAllViews();
                    ProgressBar pb = new ProgressBar(c.getContext());
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(48));
                    lp.gravity = android.view.Gravity.CENTER;
                    lp.topMargin = dpToPx(8);
                    lp.bottomMargin = dpToPx(8);
                    pb.setLayoutParams(lp);
                    c.addView(pb);
                    return;
                }
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            // Header/footer are singletons meant to survive recycling with
            // their content intact (see onBindViewHolder note above) — never
            // tear them down.
            if (holder.itemView == headerView || holder.itemView == footerView) return;
            if (holder instanceof PostRowHolder) {
                PostRowHolder h = (PostRowHolder) holder;
                if (h.boundPostIndex >= 0 && h.boundPostIndex < feedCards.size()) {
                    HomeFeedCard card = feedCards.get(h.boundPostIndex);
                    if (card != null && card.rootView == holder.itemView) {
                        // This card is about to lose its live view — if it
                        // happened to be the one currently playing, detach
                        // the shared player defensively (playMostVisibleCard
                        // normally already moves it away before a card is
                        // recycled, but a very fast fling could in principle
                        // race past that).
                        if (currentPlayingIndex == h.boundPostIndex) {
                            if (card.playerView != null) card.playerView.setPlayer(null);
                        }
                        feedCards.set(h.boundPostIndex, null);
                    }
                }
                h.boundPostIndex = -1;
            }
        }

        /** Header/footer views are singletons reused across binds; if this
         *  ViewHolder's pooled instance still has a stale parent from a
         *  previous attach, RecyclerView's addView would throw — detach
         *  first, same idea as any other manual view-reparenting. */
        private void detachFromCurrentParent(View v) {
            if (v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
        }
    }

    /** Wraps a header/footer/suggested-row/banner/loading/empty/load-more
     *  item — content lives directly in itemView, built by whichever
     *  bind*Content() helper matches its row type. */
    private static class SimpleRowHolder extends RecyclerView.ViewHolder {
        SimpleRowHolder(@NonNull View itemView) { super(itemView); }
    }

    /** Wraps one item_home_feed_post.xml inflation — reused across many
     *  different posts as the user scrolls, unlike the old one-View-per-post
     *  model. boundPostIndex tracks which currentFeedPosts entry it's
     *  currently showing, so onViewRecycled can null out the right feedCards
     *  slot.
     *
     *  ★ Ultra-advanced optimization: view-holder caching. addFeedPostCard()
     *  used to run ~28 findViewById() tree-walks on EVERY bind — meaning
     *  every single card that scrolled into view during a fling paid that
     *  cost again, even though the same 28 children exist in every recycled
     *  instance of item_home_feed_post.xml. cacheViews() now does that
     *  lookup exactly once, right after inflation (onCreateViewHolder) —
     *  every rebind afterwards is a plain field read. This is the same
     *  reason plain RecyclerView.ViewHolder subclasses exist in the first
     *  place; addFeedPostCard was just never wired to take advantage of it.
     *  addFeedPostCard()'s ~800 lines of bind logic are otherwise completely
     *  unchanged — it now reads `holder.avatar` etc. instead of calling
     *  `card.findViewById(...)`, nothing else about it moved. */
    private static class PostRowHolder extends RecyclerView.ViewHolder {
        int boundPostIndex = -1;
        boolean viewsCached = false;

        CircleImageView avatar;
        ImageView       ivPostStoryRing;
        TextView        tvOwner;
        TextView        tvTime;
        TextView        tvAudio;
        TextView        tvSuggested;
        TextView        btnPostFollow;
        ImageView       ivThumb;
        TextView        tvCaption;
        TextView        tvLikes;
        TextView        tvComments;
        TextView        tvReposts;
        ImageButton     btnLike;
        ImageButton     btnComment;
        ImageButton     btnRepost;
        ImageButton     btnSave;
        PlayerView      pvFeed;
        FrameLayout     frameVideo;
        View            endOverlay;
        View            watchMore;
        TextView        watchAgain;
        ImageButton     btnMute;
        SeekBar         sbProgress;
        TextView        tvPosition;
        TextView        tvSpeedChip;
        View            playOverlay;
        View            collabAvatarContainer;
        View            btnReadMore;
        View            btnSend;
        View            btnMore;

        PostRowHolder(@NonNull View itemView) { super(itemView); }

        /** Runs once per physical inflated instance — NOT once per bind. */
        void cacheViews() {
            if (viewsCached) return;
            avatar                = itemView.findViewById(R.id.iv_post_avatar);
            ivPostStoryRing       = itemView.findViewById(R.id.iv_post_story_ring);
            tvOwner               = itemView.findViewById(R.id.tv_post_owner);
            tvTime                = itemView.findViewById(R.id.tv_post_time);
            tvAudio               = itemView.findViewById(R.id.tv_post_audio);
            tvSuggested           = itemView.findViewById(R.id.tv_post_suggested);
            btnPostFollow         = itemView.findViewById(R.id.btn_post_follow);
            ivThumb               = itemView.findViewById(R.id.iv_post_thumb);
            tvCaption             = itemView.findViewById(R.id.tv_post_caption);
            tvLikes               = itemView.findViewById(R.id.tv_post_likes);
            tvComments            = itemView.findViewById(R.id.tv_post_comments);
            tvReposts             = itemView.findViewById(R.id.tv_post_reposts);
            btnLike               = itemView.findViewById(R.id.btn_post_like);
            btnComment            = itemView.findViewById(R.id.btn_post_comment);
            btnRepost             = itemView.findViewById(R.id.btn_post_repost);
            btnSave               = itemView.findViewById(R.id.btn_post_save);
            pvFeed                = itemView.findViewById(R.id.pv_feed_post);
            frameVideo            = itemView.findViewById(R.id.frame_video);
            endOverlay            = itemView.findViewById(R.id.layout_end_of_reel_card);
            watchMore             = itemView.findViewById(R.id.btn_watch_more_card);
            watchAgain            = itemView.findViewById(R.id.btn_watch_again_card);
            btnMute               = itemView.findViewById(R.id.btn_post_mute);
            sbProgress            = itemView.findViewById(R.id.sb_post_progress);
            tvPosition            = itemView.findViewById(R.id.tv_post_position);
            tvSpeedChip           = itemView.findViewById(R.id.tv_post_speed_chip);
            playOverlay           = itemView.findViewById(R.id.btn_post_play_overlay);
            collabAvatarContainer = itemView.findViewById(R.id.layout_collab_avatar);
            btnReadMore           = itemView.findViewById(R.id.tv_post_read_more);
            btnSend               = itemView.findViewById(R.id.btn_post_send);
            btnMore               = itemView.findViewById(R.id.btn_post_more);
            viewsCached = true;
        }
    }
}
