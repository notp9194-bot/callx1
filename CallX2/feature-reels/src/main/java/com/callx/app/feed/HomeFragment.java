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
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.callx.app.utils.AvatarUrlBuilder;
import com.callx.app.utils.AvatarSizeTier;
import com.callx.app.cache.HomeStoryAvatarBinder;
import com.callx.app.reels.R;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.comments.ReelCommentActivity;
import com.callx.app.explore.ReelExploreActivity;
import com.callx.app.social.ReelShareSheetFragment;
import com.callx.app.social.ReelSharesBottomSheet;
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
public class HomeFragment extends Fragment
        implements HomeFeedScrollDiagnostics.SnapshotProvider {

    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout       containerNotes;
    private HorizontalScrollView scrollNotes;
    /** PERF (RecyclerView conversion): the Stories tray's real
     *  RecyclerView — replaces the old HorizontalScrollView+LinearLayout
     *  (scroll_stories/container_stories) that kept every row's View
     *  permanently inflated/attached with a hand-rolled index-based pool.
     *  See StoriesAdapter, setupStoriesRecyclerView(), STORIES_TILE_POOL. */
    private RecyclerView       rvStories;
    private StoriesAdapter     storiesAdapter;
    /** Backing data for {@link #storiesAdapter} — position i+1 in the
     *  adapter is storyEntries.get(i) (position 0 is the "Add Story" item).
     *  Rebuilt every loadStories() cycle; diffed via notifyDataSetChanged()
     *  since the whole tray always reloads together (contacts + seen-state
     *  are fetched as one batch, never incrementally). */
    private final List<StoryEntry> storyEntries = new ArrayList<>();
    /** My-story avatar data resolved by loadMyAvatar() — kept here (not just
     *  painted directly into a View) because the "Add Story" row is now a
     *  normal RecyclerView item: its ViewHolder can be recycled/recreated,
     *  so onBindViewHolder needs somewhere durable to re-read this from
     *  rather than relying on a single long-lived ImageView field. */
    private String  myAvatarPhotoUrl;
    private long    myAvatarVersion;
    private boolean myAvatarLoaded = false;
    /** Shared across every Stories-tray RecyclerView instance (this
     *  fragment's view can be recreated e.g. on tab re-select) — same
     *  "one process-wide pool per row shape" rule as SUGGESTED_CREATORS_TILE_POOL
     *  / SUGGESTED_REELS_TILE_POOL elsewhere in this header. */
    private static final RecyclerView.RecycledViewPool STORIES_TILE_POOL =
            new RecyclerView.RecycledViewPool();
    private static final int STORIES_VIEW_TYPE_ADD_STORY = 0;
    private static final int STORIES_VIEW_TYPE_STORY     = 1;
    /** Guards attachStoriesScrollListener() against double-registration —
     *  setupStoriesRecyclerView() can run again if the header is re-inflated. */
    private boolean storiesScrollListenerAttached = false;
    private LinearLayout       containerTrending;
    private LinearLayout       containerFriendsActivity;
    private LinearLayout       containerContinueWatching;
    private LinearLayout       containerSuggestedCreators;
    /** ★ Instagram-level PERF: pooled card Views for the header's static
     *  "Suggested Creators" rail (containerSuggestedCreators) — see
     *  addCreatorCards() below. Not the same UI as the inline "Suggested
     *  for you" row mixed into the scrolling feed every N posts (that one —
     *  ROW_SUGGESTED_CREATORS / SuggestedCreatorsTileAdapter — already uses
     *  a real nested RecyclerView with a shared RecycledViewPool). This is
     *  the separate, older Explore-adjacent rail pinned in the header
     *  (position 0, never rebinds on scroll), which was still doing a full
     *  removeAllViews()-then-rebuild-from-scratch every pull-to-refresh /
     *  feed reload — one avatar/name/sub/Follow-button View tree plus a
     *  fresh click listener PER CARD, discarded and reallocated on every
     *  single refresh even though the same ~12 candidate slots keep coming
     *  back. Pooled the same way v323 pooled the tagged-people/product-tag
     *  pills: reuse card N's Views across refreshes, only update
     *  text/image/tag; listeners registered once per pooled card and read
     *  the CURRENT candidate off the card's tag at click time. */
    private final List<LinearLayout> suggestedCreatorCardPool = new ArrayList<>();
    /** ★ Instagram-level PERF: same pooling fix as suggestedCreatorCardPool,
     *  applied to the header's other three static rails — Trending, Friends
     *  Activity, and Continue Watching — which were still doing a full
     *  clear-then-rebuild (fresh inflate/View tree + fresh click listener
     *  PER CARD) on every pull-to-refresh / feed reload. See
     *  obtainTrendingCard() / obtainFriendsActivityCard() /
     *  obtainContinueWatchingCard(). */
    private final List<View> trendingCardPool = new ArrayList<>();
    private final List<FriendsActivityCard> friendsActivityCardPool = new ArrayList<>();
    private final List<View> continueWatchingCardPool = new ArrayList<>();
    private ProgressBar        pbTrending;
    private ProgressBar        pbActivity;
    private ProgressBar        pbContinue;
    private ProgressBar        pbSuggested;
    private TextView           btnHomeFollowing;
    private TextView           btnHomeForYou;
    private View               vFeedIndicator;
    private TextView           btnSeeAllTrending;
    private TextView           btnClearHistory;
    private ImageButton        btnHomeUpload;
    // ── New Instagram-style header ──
    private TextView           tvFeedTitle;
    private ImageButton        btnNewPost;
    private ImageButton        btnNotifications;
    // ── Inline auto-play (single ExoPlayer shared across feed cards) ──
    private ExoPlayer          feedPlayer;
    /** Adjacent media is warmed through the shared cache/preloader, not by
     *  running extra decoders. Home deliberately keeps one attached player:
     *  multiple READY/BUFFERING players competing for decoder, Surface and
     *  network resources were the source of the measured scroll stalls. These
     *  fields remain for lifecycle cleanup/compatibility with older state. */
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
    /** Real frame-level scroll diagnostics. Records only during Home-feed
     * movement and remains available after this view is torn down so
     * UserReelsActivity can show it from the 3-dot menu. */
    private final HomeFeedScrollDiagnostics scrollDiagnostics =
            HomeFeedScrollDiagnostics.get();
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

    // ── Photo-slideshow background audio (Home / For-You feed) ─────────────
    // 🐛 FIX: photo posts have no videoUrl, so attachPlayerToCard() used to
    // bail out before ever starting anything — video posts got sound via
    // feedPlayer, photo posts got none at all. Mirrors ReelPlayerController's
    // photoAudioPlayer (immersive Reels tab), just scoped to this fragment's
    // single active card instead of a per-page controller.
    private android.media.MediaPlayer feedPhotoAudioPlayer;
    private final android.os.Handler feedPhotoAudioHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable feedPhotoAudioLoopRunnable;
    private final Handler      scrollHandler = new Handler(Looper.getMainLooper());
    /** Retry ticks for the PTS-gated reveal below — separate from
     *  scrollHandler so a pending retry can be reasoned about (and, if
     *  ever needed, bulk-cancelled) independently of scroll bookkeeping. */
    private final Handler      ptsGateHandler = new Handler(Looper.getMainLooper());
    /** How close (ms) a rendered frame's playback position must be to the
     *  card's real expected start (0, or the resume-seek target once
     *  applyPendingResumeSeek() resolves one) before revealCardThumbnailAfterFirstFrame()
     *  is trusted to fire — Instagram-style PTS gating. onRenderedFirstFrame
     *  alone can fire for a transient frame rendered right before a pending
     *  resume seek lands (e.g. the pre-seek position-0 frame); revealing that
     *  immediately shows a visible jump/black flash the instant the seek
     *  actually completes a moment later. ~2 frames at 30fps of slack absorbs
     *  normal PTS rounding without letting a genuinely mismatched frame through. */
    private static final long FIRST_FRAME_PTS_TOLERANCE_MS = 80;
    /** Hard cap on how long the reveal is withheld waiting for the PTS gate
     *  to settle. Protects against a permanently frozen thumbnail if a resume
     *  seek never resolves — past this, reveal unconditionally rather than
     *  leave the card looking stuck. */
    private static final long FIRST_FRAME_PTS_MAX_WAIT_MS = 400;

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
        ImageView  backdropView;
        View       mediaFrame;
        View       endOverlay;
        String     videoUrl;
        String     reelId;
        /** Guards revealThumbnailAfterFirstFrame() against double-firing —
         *  reset to false every time this card becomes the active one. */
        boolean    firstFrameRevealed;
        /** True once onRenderedFirstFrame has fired but the PTS gate couldn't
         *  yet confirm the on-screen frame matches this card's real start
         *  position — cleared once attemptPtsGatedReveal() actually reveals
         *  (or force-reveals on timeout). See FIRST_FRAME_PTS_TOLERANCE_MS. */
        boolean    firstFramePtsGatePending;
        /** The position (ms) applyPendingResumeSeek() last sought this card
         *  to, or 0 when no resume seek was needed — the PTS gate's target
         *  for "is the frame actually on screen the right one". */
        long       resumeSeekTargetMs;
        /** Wall-clock time the PTS gate started waiting for this card, used
         *  to enforce FIRST_FRAME_PTS_MAX_WAIT_MS. 0 = not yet gating. */
        long       firstFrameGateStartMs;
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
    /** ★ Sponsored/ad slot — see insertSponsoredRowIfDue(). Not wired to any
     *  real ad SDK/network; this is a real, additive feed-mixing mechanism
     *  (same interleaving pattern as ROW_SUGGESTED_CREATORS) reading from a
     *  Firebase "sponsoredReels" node, since Instagram-level feeds always mix
     *  paid placements into the organic ranking and this app previously had
     *  no such slot at all. */
    private static final int ROW_SPONSORED           = 9;
    /** Home-feed caption collapsed line count before "…more" kicks in. */
    private static final int CAPTION_MAX_LINES        = 2;

    // ── Shared, allocation-free pill click listeners ────────────────────
    // ★ Instagram-level PERF: one listener instance per pill TYPE for the
    // life of the fragment (not per pill, not per bind). Both read whatever
    // is CURRENTLY on the clicked View's tag at click time (setTag() is
    // refreshed on every bind in bindTaggedPeople()/bindProductTags()), so
    // reusing the exact same listener object across every pool pill and
    // every rebind is safe — nothing about "which uid/product" is captured
    // via closure, it's read fresh off the view itself.
    private final View.OnClickListener taggedPersonPillClickListener = v -> {
        Object tag = v.getTag();
        if (tag instanceof String) openUserProfile((String) tag);
    };
    private final View.OnClickListener productTagPillClickListener = v -> {
        Object tag = v.getTag();
        if (!(tag instanceof ReelModel.ProductTag)) return;
        ReelModel.ProductTag product = (ReelModel.ProductTag) tag;
        if (product.productUrl != null && !product.productUrl.trim().isEmpty()) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(product.productUrl.trim())));
            } catch (Exception e) {
                Toast.makeText(requireContext(), "Couldn't open product link",
                    Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(requireContext(), product.name, Toast.LENGTH_SHORT).show();
        }
    };

    private static class FeedRow {
        final int type;
        /** For ROW_POST: index into currentFeedPosts/feedCards. */
        int postIndex = -1;
        /** For ROW_SUGGESTED_CREATORS: candidate pool (uid,name,photo). */
        List<String[]> creatorPool;
        /** For ROW_SUGGESTED_REELS: candidate reel pool. */
        List<ReelModel> reelPool;
        /** For ROW_SPONSORED: the single ad being shown in this slot. */
        SponsoredAd sponsoredAd;
        FeedRow(int type) { this.type = type; }
    }

    /** Minimal sponsored/ad payload for an inline feed ad slot. Deliberately
     *  separate from ReelModel — an ad never enters currentFeedPosts,
     *  FeedRankingEngine, watch-history tracking, or the like/comment/repost
     *  pipeline; it is purely a rendered slot at a fixed cadence, same as
     *  real feed ad systems keep ad delivery decoupled from organic ranking. */
    private static class SponsoredAd {
        String id;
        String imageUrl;
        String headline;
        String sponsorName;
        String sponsorPhotoUrl;
        String ctaText;
        String ctaUrl;
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

    /** Row cap for the Room cold-start instant-paint cache — see
     *  persistFeedPageToLocalCache()/paintFeedFromLocalCacheInstant(). Kept
     *  equal to FEED_FETCH_BATCH: this is a "last-seen top of feed" cache
     *  for instant paint, not a full offline store. */
    private static final int HOME_FEED_CACHE_LIMIT = FEED_FETCH_BATCH;
    /** v299 ultra: true from the moment paintFeedFromLocalCacheInstant()
     *  paints the disk cache, back to false the instant a real Firebase
     *  page lands (whether that page short-circuits via
     *  isSameTopOfFeedOrder() or goes through the full rebuild). Lets
     *  renderFeedPostsWithState() tell "confirming what's already on
     *  screen" apart from "replacing it with something different". */
    private boolean feedPaintedFromCache = false;
    /** v299 ultra: cheap fingerprint (ordered reelId sequence) of the last
     *  page actually written to the Room instant-paint cache. Firebase
     *  reconciles the Home feed on every app-foreground and every
     *  real-time listener tick, so without this every one of those
     *  no-op-content ticks would still hit disk with a full delete+insert.
     *  The cache only needs to represent "roughly what the user last saw",
     *  so skipping a byte-identical rewrite is free — see
     *  persistFeedPageToLocalCache(). */
    private String lastPersistedCacheSignature = null;
    /** v300 ultra: process-lifetime in-memory mirror of the last persisted
     *  cache page. Room's disk read in paintFeedFromLocalCacheInstant() is
     *  already fast (single indexed SELECT, ~25 rows), but it's still a
     *  disk I/O + thread-hop the OS can stall on. The overwhelmingly common
     *  case for repainting Home isn't a true cold process start though —
     *  it's the user switching tabs away and back, or the fragment view
     *  being recreated (config change, back-stack) while the app process
     *  stays alive. For that case there's no reason to touch disk at all:
     *  Instagram doesn't re-hit storage when you tap back to the Home tab
     *  mid-session. Populated by persistFeedPageToLocalCache(); cleared on
     *  logout/account-switch by clearInMemoryFeedCache() (see
     *  AccountMenuActivity#confirmLogout / AuthActivity's EXTRA_FORCE_LOGIN
     *  branch) so a new account on the same process never flashes the
     *  previous account's feed. */
    private static volatile List<ReelModel> sMemoryFeedCache = null;

    /** Wipes the in-memory Home feed mirror. Must be called on logout /
     *  forced account switch alongside ChatSnapshotCache.clearSnapshotAsync()
     *  and AppDatabase.wipeForAccountSwitch() — those clear the disk-backed
     *  caches, this clears the equivalent in-RAM one so a same-process
     *  account switch can't flash the outgoing account's Home feed. */
    public static void clearInMemoryFeedCache() {
        sMemoryFeedCache = null;
    }
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

    /**
     * PERF cache — built caption SpannableStrings (hashtag ClickableSpans
     * already parsed/attached), keyed by reelId. See addFeedPostCard()'s
     * caption block for why this exists: a plain field-read/reuse instead
     * of re-running the hashtag regex + re-allocating a ClickableSpan per
     * hashtag on every single bind. Bounded LRU (access-order) so a long
     * scroll session across a big feed can't grow this unboundedly — same
     * sizing/eviction pattern as ReelUiStateCache's own MAX_ENTRIES cap.
     */
    private static final int CAPTION_SPANNABLE_CACHE_MAX = 64;
    private final LinkedHashMap<String, android.text.SpannableString> captionSpannableCache =
        new LinkedHashMap<String, android.text.SpannableString>(CAPTION_SPANNABLE_CACHE_MAX, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, android.text.SpannableString> eldest) {
                return size() > CAPTION_SPANNABLE_CACHE_MAX;
            }
        };
    /**
     * PERF cache — resolved "@username" labels for tagged-people pills,
     * keyed by uid. See bindTaggedPeople(): the same tagged user routinely
     * shows up on many different reels (and the same reel rebinds on the
     * same holder), so this is a FRAGMENT-level cache (not per-holder) —
     * one Firebase getUserRef() read ever, per user, per session, instead
     * of one read per tagged-pill per bind. Same bounded-LRU eviction
     * pattern as captionSpannableCache above.
     */
    private static final int TAGGED_USER_NAME_CACHE_MAX = 256;
    private final LinkedHashMap<String, String> taggedUserNameCache =
        new LinkedHashMap<String, String>(TAGGED_USER_NAME_CACHE_MAX, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > TAGGED_USER_NAME_CACHE_MAX;
            }
        };
    private View             feedLoadMoreFooter  = null;
    private View             newPostsBanner      = null;
    private int               newPostsPending     = 0;
    private int               postsSincePeopleYouMayLike = 0;
    private List<String[]>   suggestedCreatorPool = null; // uid,name,photo,sub — fetched once/session
    private int               postsSinceSuggestedReels   = 0;
    private List<ReelModel>   suggestedReelsPool   = null; // fetched once/session, reused for every insertion
    /** Every SPONSORED_EVERY_N_POSTS organic posts (For-You mode only), one
     *  inline ad slot is mixed in — see insertSponsoredRowIfDue(). */
    private static final int SPONSORED_EVERY_N_POSTS = 9;
    private int               postsSinceSponsored  = 0;
    private List<SponsoredAd> sponsoredAdPool      = null; // fetched once/session
    private int               sponsoredAdCursor    = 0;    // round-robins through the pool
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
    /** Exact foreground-media request options shared by visible cards and
     *  thumbnail prefetch, so prefetches warm the same transformed cache key
     *  the card consumes instead of decoding a second copy. */
    private static final com.bumptech.glide.request.RequestOptions FEED_MEDIA_OPTS =
            new com.bumptech.glide.request.RequestOptions()
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .dontAnimate()
                    .fitCenter();
    /** The backdrop is deliberately much smaller than the foreground media:
     *  it is blurred and only fills letterbox pixels, so decoding it at card
     *  resolution wastes CPU, heap, and upload bandwidth. */
    private static final int BACKDROP_DECODE_W = 180;
    private static final int BACKDROP_DECODE_H = 225;
    private static final CenterCrop FEED_BACKDROP_CROP = new CenterCrop();
    private static final ReelBlurTransformation FEED_BACKDROP_BLUR =
            new ReelBlurTransformation(20);
    private static final com.bumptech.glide.request.RequestOptions FEED_BACKDROP_OPTS =
            new com.bumptech.glide.request.RequestOptions()
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .dontAnimate()
                    .transform(FEED_BACKDROP_CROP, FEED_BACKDROP_BLUR);
    /** True while the RecyclerView is actively moving. This is deliberately
     *  a state flag only: RecyclerView and PlayerView already own
     *  hardware-accelerated rendering, and a parent layer would force
     *  expensive Surface texture rebuilds. */
    private boolean   isFeedScrolling          = false;
    /** Kept for diagnostics compatibility; Home no longer promotes the
     *  scrolling subtree to a parent hardware layer. */
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
    /** Stable autoplay scheduling: visibility is scanned once on the first
     *  vsync after RecyclerView becomes IDLE. Keeping player handoffs out of
     *  onScrolled is intentional — a fling can pass several cards and every
     *  intermediate Surface/decoder swap costs the same frames needed to draw
     *  the scroll. */
    private boolean playVisibleCheckScheduled = false;
    private final android.view.Choreographer.FrameCallback playVisibleFrameCallback = frameTimeNanos -> {
        playVisibleCheckScheduled = false;
        playMostVisibleCard();
    };
    /** Handler-based fallback used by lifecycle/transition call sites that
     *  need a deliberate delayed visibility check. */
    private final Runnable playVisibleRunnable = () -> {
        playVisibleCheckScheduled = false;
        playMostVisibleCard();
    };
    private int paginateThresholdPx = 0;
    private int prefetchThresholdPx = 0;
    /** Reused by playMostVisibleCard() so a scroll never allocates an int[]. */
    private final int[] visibilityLoc = new int[2];
    /** Reused by playMostVisibleCard() for the RecyclerView's own on-screen
     *  location, so the visibility % is computed against the actual visible
     *  viewport (status bar / bottom nav / tab bar excluded) instead of the
     *  raw display height. */
    private final int[] rvLocationOnScreen = new int[2];
    /** True when the current card was paused purely because it scrolled
     *  below the 50% visibility floor (not a user tap-to-pause). Lets
     *  playMostVisibleCard() auto-resume it if it scrolls back above the
     *  floor before anything else takes over — mirrors Instagram's
     *  symmetric enter/exit behaviour instead of only gating entry. */
    private boolean pausedForVisibility = false;
    /** Decode size for the 36dp card avatar (36dp ≈ 144px at xxhdpi). */
    private static final int AVATAR_DECODE_PX = 144;
    /** ★ Ultra-advanced optimization: Pattern.compile() is far from free —
     *  buildCaptionSpannable() used to recompile this same regex on EVERY
     *  card bind (every scroll rebind, not just once per unique caption).
     *  Compiled once and reused; matching against it per-bind is cheap,
     *  compiling it per-bind was not. */
    private static final java.util.regex.Pattern HASHTAG_PATTERN =
        java.util.regex.Pattern.compile("([#@])(\\w+)");
    /** Card thumbnail decode size — 4:5, matching the tallest Home-feed
     *  container. Shared by the card load and the prefetch so both hit the same
     *  Glide cache key. FIT keeps wider/taller source media uncropped inside it.
     */
    private static final int THUMB_DECODE_W = 540;
    private static final int THUMB_DECODE_H = 675;
    /** Trending strip / Continue Watching tiles render at ~120–140dp — the
     *  fixed 720x720 decode those two spots used was sized for a full-width
     *  card, roughly 3-5x more pixels than any device density actually needs
     *  for a ~140dp tile, wasting decode time and heap on every card. 260px
     *  covers up to xxhdpi (140dp × ~1.9) with headroom. */
    private static final int STRIP_THUMB_DECODE_PX = 260;
    /** "Suggested reels" tile decode size — matches SuggestedReelsTileAdapter's
     *  fixed TILE_W_DP×TILE_H_DP (160×284dp) tile exactly. Without an
     *  .override() here Glide fell back to resolving the ImageView's actual
     *  measured size via a pre-draw listener on every single bind (extra
     *  work stacking up as this strip's own horizontal RecyclerView recycles
     *  tiles during a fling) and, on any bind that raced ahead of layout,
     *  could decode at the source's full resolution instead of this tile's
     *  ~160dp width — the same oversized-bitmap heat/GC cost the card thumb
     *  and avatar overrides above were already added to avoid. xxhdpi
     *  (~3x) puts 160dp at 480px and 284dp at 852px; rounded up with a little
     *  headroom the same way STRIP_THUMB_DECODE_PX was.  */
    private static final int SUGGESTED_TILE_DECODE_W = 500;
    private static final int SUGGESTED_TILE_DECODE_H = 880;
    /** setItemViewCacheSize() at idle — keeps enough recently-scrolled-off
     *  cards fully bound (thumb/avatar already decoded, scrub-bar listeners
     *  attached) to feel instant if the user scrolls back a little. See
     *  FLING_ITEM_VIEW_CACHE_SIZE doc for why this shrinks during a fling. */
    private static final int DEFAULT_ITEM_VIEW_CACHE_SIZE = 6;
    /** ★ Instagram-level fast-scroll fix: item-view-cache entries are full
     *  ViewHolders with their bind work already done — RecyclerView keeps up
     *  to DEFAULT_ITEM_VIEW_CACHE_SIZE of them once they scroll off screen so
     *  scrolling back doesn't repeat that work. During a fast fling that's
     *  the wrong trade: cards are racing past and being scrolled off again
     *  within a frame or two, so a bigger cache just means more of them get
     *  fully bound (and stay resident) before being discarded anyway — extra
     *  CPU work for views the user never actually paused on, stacking on top
     *  of the decode work the Glide pause (SCROLL_STATE_SETTLING handling
     *  below) already targets. Shrunk to 2 for the duration of the fling —
     *  still keeps the couple of cards right around the viewport instant-
     *  cached — and restored to DEFAULT_ITEM_VIEW_CACHE_SIZE the moment the
     *  fling ends, so ordinary back-and-forth scrolling is unaffected. */
    private static final int FLING_ITEM_VIEW_CACHE_SIZE = 2;
    /** De-dupes prefetchUpcomingFeedMedia() across a fling's scroll events. */
    private int lastPrefetchFromIndex = -1;

    // Story data model for proper sorting
    private static class StoryEntry {
        String uid, name, photo;
        boolean hasUnseen;
        /** True when contact has at least one reel_story type — shows gradient ring */
        boolean hasReelStory;
        /** Mirrors users/{uid}/avatarVersion — see HomeStoryAvatarBinder#url. 0 = unversioned. */
        long avatarVersion;
        StoryEntry(String u, String n, String p, boolean unseen, boolean reelStory, long avatarVersion) {
            uid = u; name = n; photo = p; hasUnseen = unseen; hasReelStory = reelStory;
            this.avatarVersion = avatarVersion;
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
        // ★ Surface pre-warm — see SurfacePrewarmer class doc. Companion to
        // GpuDecodeWarmup above: that one deliberately never touches a real
        // Surface, so the first PlayerView any feed row attaches in this
        // process still paid for SurfaceView/SurfaceFlinger's one-time
        // per-process setup. This pays that cost now instead, off the first
        // real card's critical path, so its own surfaceCreated() — and
        // therefore revealCardThumbnailAfterFirstFrame()'s first-frame reveal
        // — lands sooner and more predictably on a cold start.
        com.callx.app.player.SurfacePrewarmer.warmUpOnce(requireActivity());
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
        recyclerHome.setItemViewCacheSize(DEFAULT_ITEM_VIEW_CACHE_SIZE);
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
        // every existing method that reaches for rvStories,
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
        // FrameMetrics is attached to the host Activity, while this provider
        // maps a flagged frame back to the exact visible reel/player state.
        scrollDiagnostics.attach(requireActivity(), this);

        // ── v281: Initialize ultra-advanced performance coordinator ────────
        // Coordinates metadata caching, scroll state, network batching, view
        // recycling, and smart prefetch for Instagram-level feed speed.
        if (this.ultraOptimizer == null) {
            this.ultraOptimizer = new HomeFeedUltraOptimizer();
            this.ultraOptimizer.initialize(requireContext(), 
                FirebaseUtils.getReelsRef(), scrollHandler);
        }

        // NOTE: ultraOptimizer's scroll hook used to be its own separate
        // recyclerHome.addOnScrollListener(...) registered right here. That
        // meant recyclerHome had TWO independent OnScrollListeners firing on
        // every single scroll event/frame (this one + the autoplay/pagination
        // one further down in setupHomeFeed()) — double dispatch overhead on
        // every pixel of scroll, and worse, the two ran with zero awareness
        // of each other's state. Consolidated into ONE listener (see the
        // single recyclerHome.addOnScrollListener(...) below) so there's a
        // single per-event dispatch and a single source of truth for scroll
        // state — ultraOptimizer's FLING/SETTLE state still coordinates the
        // final winner, while the scroll listener can now start a reel as
        // soon as it crosses the visibility floor during a fast fling.

        // ★ Built via AdaptiveStreamingManager.buildBarePlayer() instead of a
        // plain ExoPlayer.Builder() — gets the SAME network-tier-tuned
        // LoadControl (short start buffer, e.g. ~800ms bufferForPlaybackMs on
        // WiFi vs ExoPlayer's slower ~2500ms default) and shared
        // THREAD_PRIORITY_DISPLAY playback thread the Reels swipe feed
        // already uses for fast, low-latency start — Home's feed was
        // previously left on generic defaults.
        // ── Shared ExoPlayerPool instead of a raw buildBarePlayer() ────────
        // Same centralized pool the Reels swipe feed uses. Home intentionally
        // acquires only one player so decoder/Surface ownership is singular.
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
        // v298: paint the last-seen feed page from disk BEFORE the real
        // Firebase fetch (kicked off by loadAllSections() right below) has
        // any chance to respond — see paintFeedFromLocalCacheInstant()'s
        // doc. Firebase is still always queried; this only removes the
        // cold-start loading-spinner gap on repeat opens.
        paintFeedFromLocalCacheInstant();
        loadAllSections();
        return v;
    }

    /**
     * Applies the repeat-mode + end-of-reel listener to Home's active feed
     * player. Legacy standby instances, if encountered during a hot code
     * transition, are guarded and returned to the pool before handoff.
     *
     * Also wires time-to-first-frame measurement and protects the thumbnail
     * reveal until the active player has actually rendered the correct frame.
     */
    private void configureFeedPlayerInstance(ExoPlayer player) {
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        Player.Listener listener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                // Standby players are prepared off-screen. Their READY/ENDED
                // callbacks must never mutate the visible card or active
                // player's playback chrome.
                if (player != feedPlayer) return;
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
                // A prebuffered neighbour can render internally before it is
                // promoted. Only the player currently attached to a visible
                // PlayerView may reveal the active card.
                if (player != feedPlayer) return;
                // Instagram-style handoff: remove the opaque thumbnail cover
                // once the first actually-decoded frame is on screen — but not
                // blindly on this callback alone. onRenderedFirstFrame can fire
                // for a transient frame (often the pre-seek position-0 frame)
                // rendered just before a still-pending resume seek lands; PTS-gate
                // it through attemptPtsGatedReveal() so we only reveal once the
                // on-screen frame's position actually matches where this card is
                // meant to start, avoiding a visible seek/black-frame flash.
                HomeFeedCard activeCard = currentPlayingIndex >= 0
                        && currentPlayingIndex < feedCards.size()
                        ? feedCards.get(currentPlayingIndex) : null;
                if (activeCard != null) {
                    long nowMs = System.currentTimeMillis();
                    if (isHomeFeedScrollActive()) {
                        // The first frame can arrive while a drag/fling is
                        // still moving the Surface. The reveal itself is a
                        // single visibility update (not an animation), so
                        // check it now; this lets fast-scrolling reels become
                        // visible as soon as their first frame is ready.
                        activeCard.firstFramePtsGatePending = true;
                        if (activeCard.firstFrameGateStartMs <= 0L) {
                            activeCard.firstFrameGateStartMs = nowMs;
                        }
                        attemptPtsGatedReveal(activeCard, player, nowMs);
                        if (!activeCard.firstFrameRevealed) {
                            scheduleFirstFrameRevealRetry(activeCard);
                        }
                    } else {
                        attemptPtsGatedReveal(activeCard, player, nowMs);
                    }
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
            @Override
            public void onVideoSizeChanged(@NonNull VideoSize videoSize) {
                if (player != feedPlayer || videoSize.width <= 0 || videoSize.height <= 0) return;
                if (currentPlayingIndex < 0 || currentPlayingIndex >= feedCards.size()) return;
                HomeFeedCard activeCard = feedCards.get(currentPlayingIndex);
                if (activeCard != null) {
                    applyFeedMediaAspect(activeCard.mediaFrame,
                        videoSize.width / (float) videoSize.height);
                }
            }
            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition, int reason) {
                if (player != feedPlayer) return;
                // A resume seek landing is exactly the moment the PTS gate above
                // was waiting on — re-check immediately instead of only relying
                // on a second onRenderedFirstFrame (which media3 doesn't reliably
                // re-fire for a seek within the same enabled renderer).
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return;
                if (currentPlayingIndex < 0 || currentPlayingIndex >= feedCards.size()
                        || feedCards.get(currentPlayingIndex) == null) return;
                HomeFeedCard active = feedCards.get(currentPlayingIndex);
                if (active.firstFrameRevealed || !active.firstFramePtsGatePending) return;
                // Small settle delay — checking on the discontinuity callback
                // itself can still read the pre-seek position for a frame or two.
                ptsGateHandler.postDelayed(() -> {
                    if (!isAdded() || active.firstFrameRevealed
                            || isHomeFeedScrollActive() || currentPlayingIndex < 0
                            || currentPlayingIndex >= feedCards.size()
                            || feedCards.get(currentPlayingIndex) != active) return;
                    attemptPtsGatedReveal(active, player, active.firstFrameGateStartMs);
                }, 32);
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
     * Refreshes the Stories tray. loadStories() swaps storyEntries and calls
     * StoriesAdapter#notifyDataSetChanged once the new fetch resolves (see
     * collectStoryEntriesParallel), so no clear-first step is needed here —
     * onResume() and storyRingObserver just call this directly and the old
     * rows keep showing (real RecyclerView-recycled Views, not a manual
     * pool) until the new data lands, same no-blank-flash rule as
     * trendingCardPool/etc.
     */
    private void refreshStoryRow() {
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
        containerNotes     = v.findViewById(R.id.container_notes);
        scrollNotes        = v.findViewById(R.id.scroll_notes);
        rvStories          = v.findViewById(R.id.rv_stories);
        btnHomeFollowing   = v.findViewById(R.id.btn_home_following);
        btnHomeForYou      = v.findViewById(R.id.btn_home_for_you);
        vFeedIndicator     = v.findViewById(R.id.v_feed_indicator);
        btnHomeUpload      = v.findViewById(R.id.btn_home_upload);
        tvFeedTitle        = v.findViewById(R.id.tv_feed_title);
        btnNewPost         = v.findViewById(R.id.btn_new_post);
        btnNotifications   = v.findViewById(R.id.btn_notifications);
        setupStoriesRecyclerView();
    }

    /**
     * PERF (RecyclerView conversion): wires rv_stories up exactly once per
     * inflated header — LinearLayoutManager(HORIZONTAL), a shared
     * RecycledViewPool (STORIES_TILE_POOL) so row Views survive a header
     * re-inflate instead of being thrown away, a small item view cache so a
     * quick back-and-forth scroll doesn't immediately re-hit the pool, and
     * no item animator (same reasoning as recyclerHome: this tray reloads
     * wholesale on every refresh, a fade/shift per row would just read as
     * flicker). Mirrors SuggestedCreatorsRowHolder's chipsRecycler setup.
     */
    private void setupStoriesRecyclerView() {
        if (rvStories == null || !isAdded() || getContext() == null) return;
        LinearLayoutManager lm = new LinearLayoutManager(getContext(), LinearLayoutManager.HORIZONTAL, false);
        rvStories.setLayoutManager(lm);
        rvStories.setRecycledViewPool(STORIES_TILE_POOL);
        rvStories.setItemViewCacheSize(6);
        rvStories.setHasFixedSize(true);
        rvStories.setItemAnimator(null);
        storiesAdapter = new StoriesAdapter();
        rvStories.setAdapter(storiesAdapter);
        attachStoriesScrollListener();
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
        swipeRefresh.setOnRefreshListener(this::performFeedRefresh);

        btnHomeFollowing.setOnClickListener(v -> switchFeedMode(true));
        btnHomeForYou.setOnClickListener(v -> switchFeedMode(false));

        // Apply initial active state
        updateFeedToggleUI();

        btnSeeAllTrending.setOnClickListener(v -> {
            if (isAdded() && getContext() != null)
                startActivity(new Intent(getContext(), ReelExploreActivity.class));
        });

        btnClearHistory.setOnClickListener(v -> clearWatchHistory());

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
        loadNotes();

        // ── Scroll-triggered auto-play ──────────────────────────────────────
        // v243: RecyclerView doesn't have NestedScrollView's onScrollChange,
        // but exposes the same pixel-accurate scroll-range/offset/extent
        // triplet used below to reconstruct the identical "remaining px to
        // bottom" math the old listener used.
        if (recyclerHome != null) {
            paginateThresholdPx = dpToPx(600);
            prefetchThresholdPx = dpToPx(1400);
            // ★ CONSOLIDATED (was 2 separate addOnScrollListener calls on this
            // same RecyclerView — one purely for ultraOptimizer's scroll state
            // machine, one for velocity/autoplay/pagination). Two listeners
            // meant RecyclerView's dispatchOnScrolled/dispatchOnScrollStateChanged
            // iterated and invoked BOTH on every single scroll callback, and
            // neither knew what the other was doing. Now it's one listener,
            // one dispatch, and ultraOptimizer's state feeds directly into the
            // autoplay/pagination decisions below instead of running blind.
            recyclerHome.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                    // Drive ultraOptimizer's scroll state machine first (was
                    // its own separate listener) so its FLING/SETTLE state is
                    // already up to date for the checks below in this same
                    // callback — single source of truth, no cross-listener
                    // ordering ambiguity.
                    if (ultraOptimizer != null) {
                        ultraOptimizer.onRecyclerScrolled(dx, dy);
                    }

                    // Keep the incumbent playing while it remains visible,
                    // but allow a card that becomes dominant during either a
                    // slow drag or a fast fling to take over immediately.
                    // The 50% visibility floor below prevents handoffs for
                    // cards that only flash past the edge of the viewport.
                    beginFeedScrollLayer();
                    detachActiveCardIfOutsideViewport();
                    if (dy != 0) {
                        playMostVisibleCard(true);
                    }
                    scrollDiagnostics.onScrolled(dx, dy, rv.computeVerticalScrollOffset());
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
                    // Drive ultraOptimizer's scroll state machine first (was
                    // its own separate listener — see consolidation note
                    // above onScrolled()) so its pause/resume of metadata
                    // fetch, network batching, and aggressive view-recycling
                    // cleanup has already reacted to IDLE/DRAGGING/FLINGING
                    // BEFORE playMostVisibleCard() below tries to (re)acquire
                    // a player for the settled card — avoids a race where
                    // recyclingOptimizer's fling-triggered cleanup and this
                    // listener's settle-triggered playback could interleave
                    // out of order across two separate listener objects and
                    // cause a visible black-frame/flicker on landing.
                    if (ultraOptimizer != null) {
                        ultraOptimizer.onRecyclerScrollStateChanged(newState);
                    }
                    // ★ Instagram-level fast-scroll fix: SCROLL_STATE_SETTLING is a
                    // fling still coasting under its own momentum — exactly the
                    // window where onBindViewHolder/addFeedPostCard fires for many
                    // cards back-to-back as they race past, each one kicking off
                    // fresh Glide decode chains (thumb, avatar, story ring, audio
                    // cover) for frames the user never actually stops on. That
                    // decode work is what was competing with the scroll
                    // choreographer for CPU/GPU time and running the device hot —
                    // this is the same "pause image loading during a fling, resume
                    // on settle" pattern Glide's own RecyclerView guidance
                    // recommends and Instagram uses. Pausing only queues the
                    // request; nothing is cancelled, so every card still gets its
                    // image the moment the fling ends. This never touches
                    // attachPlayerToCard/playMostVisibleCard/currentPlayingIndex —
                    // which card is chosen to play is entirely unchanged, only the
                    // still-image decode work around it is deferred.
                    if (isAdded() && getContext() != null) {
                        if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                            Glide.with(requireContext()).pauseRequests();
                        } else {
                            // DRAGGING (finger still down, not a fast fling) and
                            // IDLE both resume immediately — only the coasting
                            // fling itself is gated.
                            Glide.with(requireContext()).resumeRequests();
                        }
                    }
                    // ★ Instagram-level fast-scroll fix, part 2: shrink the
                    // item-view cache for the same SCROLL_STATE_SETTLING
                    // window as the Glide pause above — see
                    // FLING_ITEM_VIEW_CACHE_SIZE doc. Only touches how many
                    // scrolled-off ViewHolders RecyclerView keeps fully
                    // bound; does not evict or rebind anything already on
                    // screen, and does not touch the RecycledViewPool sizing
                    // above (that stays fixed — it's the unbound-instance
                    // pool a fling still needs to avoid re-inflating from
                    // scratch).
                    if (recyclerHome != null) {
                        recyclerHome.setItemViewCacheSize(
                            newState == RecyclerView.SCROLL_STATE_SETTLING
                                ? FLING_ITEM_VIEW_CACHE_SIZE
                                : DEFAULT_ITEM_VIEW_CACHE_SIZE);
                    }
                    scrollDiagnostics.onScrollStateChanged(newState);
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
                        endFeedScrollLayer();
                        playVisibleCheckScheduled = true;
                        android.view.Choreographer.getInstance()
                                .postFrameCallback(playVisibleFrameCallback);
                    }
                }
            });
        }
    }

    // ── Buttery scroll helpers ──────────────────────────────────────────

    /** Tracks active scrolling for virtualization/diagnostics. The feed is
     *  already hardware accelerated; promoting the full RecyclerView would
     *  fight its recycled PlayerView/Surface children. */
    private void beginFeedScrollLayer() {
        if (isFeedScrolling || feedScrollContentRoot == null) return;
        isFeedScrolling = true;
        // RecyclerView is already hardware accelerated. Do not promote the
        // entire scrolling subtree: Home rows contain PlayerView/Surface
        // content, and repeatedly toggling a parent hardware layer forces
        // expensive GPU texture rebuilds and can expose a black frame during
        // player handoff. Keep the scroll-state flag for virtualization and
        // diagnostics, but leave layer ownership to RecyclerView/PlayerView.
        isHwLayerOn = false;
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
                Glide.with(requireContext()).load(thumb).apply(FEED_MEDIA_OPTS)
                        .override(THUMB_DECODE_W, THUMB_DECODE_H)
                        .preload();
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
     * A RecyclerView may keep a just-off-screen holder cached instead of
     * recycling it immediately. Do not leave the active Surface/player tied to
     * that hidden card for the whole fling: cover it, detach once, and let the
     * IDLE pass choose the new winner. This is different from per-pixel player
     * switching — it runs only when the incumbent actually exits the viewport.
     */
    private void detachActiveCardIfOutsideViewport() {
        if (recyclerHome == null || currentPlayingIndex < 0
                || currentPlayingIndex >= feedCards.size()) return;
        HomeFeedCard active = feedCards.get(currentPlayingIndex);
        if (active == null || active.rootView == null
                || active.rootView.getParent() == null
                || recyclerHome.getHeight() <= 0) return;
        int top = active.rootView.getTop();
        int bottom = active.rootView.getBottom();
        if (bottom > 0 && top < recyclerHome.getHeight()) return;

        flushActiveWatchProgress();
        endSpeedBoost(currentPlayingIndex);
        resetCardPlaybackChrome(active);
        if (active.thumbView != null) {
            active.thumbView.animate().cancel();
            active.thumbView.setAlpha(1f);
            active.thumbView.setVisibility(View.VISIBLE);
        }
        if (feedPlayer != null) {
            feedPlayer.setPlayWhenReady(false);
            feedPlayer.pause();
        }
        stopProgressTicker();
        if (active.playerView != null) active.playerView.setPlayer(null);
        if (feedWindowManager != null) feedWindowManager.setProtectedView(null);
        if (watchTracker != null) watchTracker.onCardInactive();
        releaseFeedPhotoAudio();
        active.firstFrameRevealed = false;
        active.firstFramePtsGatePending = false;
        active.resumeSeekTargetMs = 0L;
        active.firstFrameGateStartMs = 0L;
        active.resumePending = false;
        attachStartTimeMs = 0L;
        currentPlayingIndex = -1;
        pausedForVisibility = false;
        userPausedActiveCard = false;
    }

    private boolean isHomeFeedScrollActive() {
        return isFeedScrolling || (recyclerHome != null
                && recyclerHome.getScrollState() != RecyclerView.SCROLL_STATE_IDLE);
    }

    private void scheduleFirstFrameRevealRetry(HomeFeedCard card) {
        ptsGateHandler.postDelayed(() -> {
            if (!isAdded() || card.firstFrameRevealed
                    || feedPlayer == null || currentPlayingIndex < 0
                    || currentPlayingIndex >= feedCards.size()
                    || feedCards.get(currentPlayingIndex) != card) {
                return;
            }
            attemptPtsGatedReveal(card, feedPlayer, System.currentTimeMillis());
            if (!card.firstFrameRevealed && card.firstFramePtsGatePending) {
                scheduleFirstFrameRevealRetry(card);
            }
        }, 32L);
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
     *
     * ★ Instagram-level 50% rule: a card only takes over playback once it
     * genuinely covers at least half the visible screen — not merely
     * "currently has more visible pixels than any other candidate". Without
     * this floor, a fast fling or a run of short in-between rows (header /
     * suggested-accounts / banner cards) can hand the "most visible" title
     * back and forth between two barely-visible cards well before either is
     * actually what the user is looking at — each handoff tears down and
     * re-attaches the shared ExoPlayer, which is exactly the stutter/flicker
     * this is meant to prevent. Gating the switch on a real 50%-of-screen
     * crossing means the current card keeps playing smoothly until a
     * challenger has decisively taken over, matching the clean single
     * handoff point Instagram/Reels use — not a running tug-of-war.
     */
    private static final float AUTOPLAY_VISIBILITY_THRESHOLD = 0.5f;
    private void playMostVisibleCard() {
        playMostVisibleCard(false);
    }

    private void playMostVisibleCard(boolean allowScrollHandoff) {
        if (!isAdded() || feedPlayer == null || feedCards.isEmpty()) return;
        // A stale vsync callback can run after a new gesture has already
        // started. Scroll callbacks explicitly allow the newly dominant card
        // to take over; the 50% floor prevents edge-of-viewport churn.
        if (recyclerHome != null
                && recyclerHome.getScrollState() != RecyclerView.SCROLL_STATE_IDLE
                && !allowScrollHandoff) {
            return;
        }
        // ★ Instagram-level viewport: measure against the RecyclerView's own
        // on-screen bounds, not the raw display height. Raw heightPixels
        // includes area the feed never actually occupies (status bar, the
        // bottom nav/tab bar), so a card sitting partly behind the nav bar
        // was being counted as "more visible" than it really is on screen.
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int viewportTop = 0, viewportBottom = screenH, viewportH = screenH;
        if (recyclerHome != null && recyclerHome.getHeight() > 0) {
            recyclerHome.getLocationOnScreen(rvLocationOnScreen);
            viewportTop    = rvLocationOnScreen[1];
            viewportBottom = viewportTop + recyclerHome.getHeight();
            viewportH      = recyclerHome.getHeight();
        }

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
            int vis     = Math.max(0, Math.min(cardBot, viewportBottom) - Math.max(cardTop, viewportTop));
            if (vis > bestPx) { bestPx = vis; bestIdx = i; }
        }
        boolean hasIncumbent = currentPlayingIndex >= 0 && currentPlayingIndex < feedCards.size()
                && feedCards.get(currentPlayingIndex) != null;
        if (bestIdx < 0) {
            // Nothing at all is visible in the scanned range (e.g. a fast
            // fling jumped clean past every loaded post row). Same as
            // dropping below the floor with no challenger: pause, don't
            // leave the incumbent playing off-screen.
            if (!allowScrollHandoff) {
                pauseIncumbentForVisibility(hasIncumbent);
            }
            return;
        }

        // Instagram-level 50% rule, applied symmetrically:
        //  • a challenger must cross half the viewport before it's allowed
        //    to steal playback from whatever is already playing (entry).
        //  • the incumbent itself is paused the moment it (and everything
        //    else on screen) drops back below that same floor (exit) —
        //    bestPx is the MAX visible-pixel count across every scanned
        //    card, so "!crossedHalfScreen" already implies the incumbent's
        //    own visibility is under the floor too, not just that it won.
        boolean crossedHalfScreen = bestPx >= viewportH * AUTOPLAY_VISIBILITY_THRESHOLD;
        if (!crossedHalfScreen) {
            // During a gentle drag the incumbent is still on-screen. Keep it
            // running until a challenger decisively occupies the viewport;
            // pausing at the 50% crossing is the small-scroll freeze users
            // notice in the feed.
            if (!allowScrollHandoff) {
                pauseIncumbentForVisibility(hasIncumbent);
            }
            return;
        }

        if (bestIdx != currentPlayingIndex) {
            pausedForVisibility = false;
            attachPlayerToCard(bestIdx, allowScrollHandoff);
        } else if (pausedForVisibility && hasIncumbent) {
            // The same card climbed back above the 50% floor. Resume it in
            // place instead of
            // detaching/re-preparing the same player. Explicit user pause
            // always wins.
            pausedForVisibility = false;
            if (!userPausedActiveCard && autoplayPolicy.shouldAutoplay(getContext())) {
                HomeFeedCard active = feedCards.get(currentPlayingIndex);
                if (active.videoUrl == null || active.videoUrl.isEmpty()) {
                    ReelModel photoReel = currentPlayingIndex < currentFeedPosts.size()
                            ? currentFeedPosts.get(currentPlayingIndex) : null;
                    resumeFeedPhotoAudio(photoReel);
                } else if (feedPlayer != null) {
                    feedPlayer.play();
                    startProgressTicker();
                    if (!active.firstFrameRevealed && active.firstFramePtsGatePending) {
                        if (!active.resumePending && active.resumeSeekTargetMs <= 0L) {
                            // The frame was already rendered before the
                            // scroll kept the opaque cover in place; there is
                            // no pending seek to protect, so reveal immediately
                            // at
                            // the safe post-scroll boundary.
                            active.firstFramePtsGatePending = false;
                            revealCardThumbnailAfterFirstFrame(active);
                        } else {
                            attemptPtsGatedReveal(active, feedPlayer,
                                    System.currentTimeMillis());
                            scheduleFirstFrameRevealRetry(active);
                        }
                    }
                }
            }
        }
    }

    /** Pauses the currently-playing card because it (and every other loaded
     *  card) has dropped below the 50% visibility floor. No-ops if there's
     *  no incumbent, playback is already stopped, or the user already
     *  paused it manually (userPausedActiveCard already covers that case
     *  and shouldn't be clobbered by a visibility-driven pause/resume). */
    private void pauseIncumbentForVisibility(boolean hasIncumbent) {
        if (!hasIncumbent || feedPlayer == null || userPausedActiveCard) return;
        if (pausedForVisibility) return; // already paused for this reason
        pausedForVisibility = true;
        feedPlayer.pause();
        stopProgressTicker();
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

    /** Shared by pull-to-refresh and reselect-Home-tab-icon: clears + reloads the feed. */
    private void performFeedRefresh() {
        unseenOwnerUids.clear();
        resetFeedPaginationState();
        clearAllSections();
        loadAllSections();
    }

    /**
     * Instagram-style "tap Home icon while already on Home" behaviour:
     * scroll to top (if not already there), then refresh the feed. Called
     * by ReelsFragment's reel_nav_home OnItemReselectedListener.
     */
    public void scrollToTopAndRefresh() {
        if (!isAdded() || recyclerHome == null || recyclerHome.getLayoutManager() == null) return;
        boolean atTop = ((LinearLayoutManager) recyclerHome.getLayoutManager())
                .findFirstCompletelyVisibleItemPosition() == 0;
        if (atTop) {
            performFeedRefresh();
        } else {
            recyclerHome.smoothScrollToPosition(0);
        }
    }

    /** Starts (or restarts) background music for a photo-slideshow feed card. */
    private void startFeedPhotoAudio(ReelModel reel) {
        releaseFeedPhotoAudio();
        if (reel == null || reel.musicUrl == null || reel.musicUrl.isEmpty()) return;

        final int startMs = reel.musicStartMs > 0 ? reel.musicStartMs
                          : (reel.musicStartSec > 0 ? reel.musicStartSec * 1000 : 0);
        final int endMs   = reel.musicEndMs > 0 ? reel.musicEndMs : 0;
        final boolean hasTrim = (endMs > startMs && endMs > 0);

        try {
            feedPhotoAudioPlayer = new android.media.MediaPlayer();
            feedPhotoAudioPlayer.setDataSource(reel.musicUrl);
            final android.media.MediaPlayer built = feedPhotoAudioPlayer;
            feedPhotoAudioPlayer.setOnPreparedListener(mp -> {
                if (feedPhotoAudioPlayer != built || !isAdded()) return;
                try {
                    mp.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f);
                    if (startMs > 0) mp.seekTo(startMs);
                    mp.setLooping(!hasTrim);
                    mp.start();
                    if (hasTrim) scheduleFeedPhotoAudioLoop(startMs, endMs);
                } catch (Exception ignored) {}
            });
            feedPhotoAudioPlayer.setOnErrorListener((mp, what, extra) -> {
                releaseFeedPhotoAudio();
                return true;
            });
            feedPhotoAudioPlayer.prepareAsync();
        } catch (Exception e) {
            releaseFeedPhotoAudio();
        }
    }

    /** Resumes the current photo card's audio (tap-to-play, tab-back-visible, etc). */
    private void resumeFeedPhotoAudio(ReelModel reel) {
        if (feedPhotoAudioPlayer == null) { startFeedPhotoAudio(reel); return; }
        try {
            feedPhotoAudioPlayer.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f);
            if (!feedPhotoAudioPlayer.isPlaying()) feedPhotoAudioPlayer.start();
        } catch (Exception e) {
            startFeedPhotoAudio(reel);
        }
    }

    private void pauseFeedPhotoAudio() {
        cancelFeedPhotoAudioLoop();
        if (feedPhotoAudioPlayer != null) {
            try { if (feedPhotoAudioPlayer.isPlaying()) feedPhotoAudioPlayer.pause(); } catch (Exception ignored) {}
        }
    }

    private void releaseFeedPhotoAudio() {
        cancelFeedPhotoAudioLoop();
        if (feedPhotoAudioPlayer != null) {
            try { if (feedPhotoAudioPlayer.isPlaying()) feedPhotoAudioPlayer.stop(); } catch (Exception ignored) {}
            try { feedPhotoAudioPlayer.release(); } catch (Exception ignored) {}
            feedPhotoAudioPlayer = null;
        }
    }

    private void scheduleFeedPhotoAudioLoop(int startMs, int endMs) {
        cancelFeedPhotoAudioLoop();
        feedPhotoAudioLoopRunnable = () -> {
            if (feedPhotoAudioPlayer == null) return;
            try {
                feedPhotoAudioPlayer.seekTo(startMs);
                if (!feedPhotoAudioPlayer.isPlaying()) feedPhotoAudioPlayer.start();
                scheduleFeedPhotoAudioLoop(startMs, endMs);
            } catch (Exception e) {
                releaseFeedPhotoAudio();
            }
        };
        feedPhotoAudioHandler.postDelayed(feedPhotoAudioLoopRunnable, Math.max(200, endMs - startMs));
    }

    private void cancelFeedPhotoAudioLoop() {
        if (feedPhotoAudioLoopRunnable != null) {
            feedPhotoAudioHandler.removeCallbacks(feedPhotoAudioLoopRunnable);
            feedPhotoAudioLoopRunnable = null;
        }
    }

    /**
     * Reuses one active ExoPlayer for the settled card. The RecyclerView
     * scroll path never calls this until IDLE; explicit user controls may
     * request an immediate handoff.
     */
    private void attachPlayerToCard(int index) {
        attachPlayerToCard(index, false);
    }

    private void attachPlayerToCard(int index, boolean userInitiated) {
        if (!isAdded() || feedPlayer == null || index < 0 || index >= feedCards.size()) return;
        // Scroll callbacks may opt in so the newly dominant card starts
        // immediately, even during a fast fling. The caller has already
        // applied the visibility floor, so edge-only cards cannot churn the
        // shared player; taps/scrubs use the same safe override.
        if (!userInitiated && recyclerHome != null
                && recyclerHome.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            return;
        }
        // A real handoff to a (possibly different) card always supersedes
        // any pending visibility-driven pause state from the outgoing card.
        pausedForVisibility = false;
        // Detach old
        if (currentPlayingIndex >= 0 && currentPlayingIndex < feedCards.size()
                && feedCards.get(currentPlayingIndex) != null) {
            HomeFeedCard old = feedCards.get(currentPlayingIndex);
            // Persist how far the outgoing reel got BEFORE its player is torn
            // away, otherwise a scroll-away loses the resume position.
            flushActiveWatchProgress();
            endSpeedBoost(currentPlayingIndex);
            resetCardPlaybackChrome(old);
            // 🐛 FIX: restore the thumbnail BEFORE detaching the player, not
            // after. revealCardThumbnailAfterFirstFrame() hides this card's
            // thumbView to INVISIBLE once its video starts drawing, so by the
            // time the user scrolls away it's sitting fully transparent with
            // nothing behind it — playerView.setPlayer(null) then rips out
            // the decoded surface too, leaving a plain black rectangle where
            // the card used to be until it's scrolled back into view (or
            // forever, since a fully-scrolled-past card is never re-bound).
            // Snapping the thumbnail back to visible/opaque first means the
            // player's surface is detached underneath a frame that's already
            // covering it, so the swap is invisible instead of a flash of
            // black. firstFrameRevealed resets too so the very next time
            // this card becomes active, revealCardThumbnailAfterFirstFrame()
            // is armed for a fresh first-frame reveal instead of silently
            // remaining hidden.
            if (old.thumbView != null) {
                old.thumbView.animate().cancel();
                old.thumbView.setAlpha(1f);
                old.thumbView.setVisibility(View.VISIBLE);
            }
            old.firstFrameRevealed = false;
            old.firstFramePtsGatePending = false;
            old.resumeSeekTargetMs = 0;
            old.firstFrameGateStartMs = 0;
            if (old.playerView != null) old.playerView.setPlayer(null);
            if (old.endOverlay  != null) old.endOverlay.setVisibility(View.GONE);
        }
        // Outgoing card may have been a photo-slideshow post with its own
        // background track — stop it before promoting the next card so two
        // cards never play music at once (releaseFeedPhotoAudio is a no-op
        // when nothing is active).
        releaseFeedPhotoAudio();
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

        if (card.videoUrl == null || card.videoUrl.isEmpty()) {
            // 🐛 FIX: photo-slideshow posts have no videoUrl and never used
            // to reach any playback code below — silent sound, unlike the
            // Reels tab's own photo mode. Start/resume this card's music
            // the same way the video branch below starts feedPlayer.
            ReelModel photoReel = index < currentFeedPosts.size() ? currentFeedPosts.get(index) : null;
            if (autoplayPolicy.shouldAutoplay(getContext())) {
                userPausedActiveCard = false;
                startFeedPhotoAudio(photoReel);
            } else {
                userPausedActiveCard = true;
            }
            return;
        }
        if (card.endOverlay != null) card.endOverlay.setVisibility(View.GONE);

        // Arm the reveal gate BEFORE attaching the PlayerView. A promoted
        // standby player may already have a decoded frame and can dispatch
        // onRenderedFirstFrame immediately when it is attached; resetting
        // these fields after setPlayer/applyPendingResumeSeek would race that
        // callback and could also erase the resolved resume target.
        card.firstFrameRevealed = false;
        card.firstFramePtsGatePending = false;
        card.resumeSeekTargetMs = 0;
        card.firstFrameGateStartMs = 0;
        card.resumePending = true;
        if (card.thumbView != null) {
            card.thumbView.animate().cancel();
            card.thumbView.setAlpha(1f);
            card.thumbView.setVisibility(View.VISIBLE);
        }

        com.callx.app.player.AdaptiveStreamingManager mgr =
            com.callx.app.player.AdaptiveStreamingManager.get(requireContext());

        // Keep a single decoder/Surface owner. Cache warming still happens
        // below through ReelVideoPreloader/UnifiedVideoCacheManager, but
        // promoting a second prepared ExoPlayer causes decoder/network
        // contention and a visible Surface handoff at the exact landing frame.
        releaseStandbyPlayersForStableHandoff();
        mgr.applyQualityCap(feedPlayer, mgr.recommendedCap(requireContext()));
        feedPlayer.setMediaSource(buildCachedMediaSource(card.videoUrl));
        feedPlayer.prepare();

        if (card.playerView != null) card.playerView.setPlayer(feedPlayer);
        // Never let the virtualizer pull the playing card out of the tree.
        if (feedWindowManager != null) feedWindowManager.setProtectedView(card.rootView);
        feedPlayer.setVolume(isMuted ? 0f : 1f);
        feedPlayer.setPlaybackSpeed(1f);

        // ── Autoplay gate ────────────────────────────────────────────────
        // Preparation/pre-buffering above still happens regardless of the
        // setting — only the decision to actually start is gated, so a
        // tap-to-play under "Off" is still instant.
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

        // Speculative first-frame pre-render (same mechanism the Reels tab
        // uses): if this video is already substantially cached, decode its
        // actual frame-0 on a background thread and swap it in over the
        // server-generated thumbnail. When it lands before onRenderedFirstFrame
        // fires, the thumbnail IS the video's first frame, so the direct
        // reveal below has nothing to visibly change — a true no-jump handoff.
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

        // Warm adjacent bytes/thumbnails through the cache only. Do not
        // prepare extra ExoPlayers here; one active decoder is the stable
        // Instagram-style feed invariant.
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
                if (index != currentPlayingIndex) attachPlayerToCard(index, true);
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
            attachPlayerToCard(index, true);
            if (currentPlayingIndex == index && userPausedActiveCard) resumeActiveCard(index);
            return;
        }
        HomeFeedCard card = feedCards.get(index);
        if (card == null) return;
        // Photo-slideshow cards have no video, so tap-to-pause/-play toggles
        // the background track instead of the (unattached) feedPlayer.
        if (card.videoUrl == null || card.videoUrl.isEmpty()) {
            if (feedPhotoAudioPlayer != null && feedPhotoAudioPlayer.isPlaying()) {
                userPausedActiveCard = true;
                pauseFeedPhotoAudio();
                showCardPlayOverlay(card, true);
            } else {
                resumeActiveCard(index);
            }
            return;
        }
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
        if (index < 0 || index >= feedCards.size()) return;
        HomeFeedCard card = feedCards.get(index);
        if (card == null) return;
        userPausedActiveCard = false;
        if (card.endOverlay != null) card.endOverlay.setVisibility(View.GONE);
        if (card.videoUrl == null || card.videoUrl.isEmpty()) {
            ReelModel photoReel = index < currentFeedPosts.size() ? currentFeedPosts.get(index) : null;
            resumeFeedPhotoAudio(photoReel);
            showCardPlayOverlay(card, false);
            return;
        }
        if (feedPlayer == null) return;
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
        card.resumeSeekTargetMs = resumeMs > 0 ? resumeMs : 0;
        if (resumeMs > 0) feedPlayer.seekTo(resumeMs);

        // The PTS gate (attemptPtsGatedReveal) may already have seen a
        // rendered frame and be waiting purely on resumePending flipping to
        // know the real target — re-check now instead of only via
        // onPositionDiscontinuity, since the no-seek-needed (resumeMs<=0)
        // path never fires a discontinuity at all.
        if (card.firstFramePtsGatePending && !card.firstFrameRevealed) {
            attemptPtsGatedReveal(card, feedPlayer, card.firstFrameGateStartMs);
        }
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
     * PTS gate in front of revealCardThumbnailAfterFirstFrame(). Only lets the
     * reveal through once the frame actually on screen (player.getCurrentPosition())
     * is close to this card's real expected start — 0 for a plain autoplay, or
     * the resume-seek target once applyPendingResumeSeek() has resolved one.
     *
     * Two cases withhold the reveal instead of firing it immediately:
     *  - card.resumePending is still true → we don't yet know whether a resume
     *    seek is coming, so the frame onRenderedFirstFrame just reported could
     *    be the pre-seek transient. Wait for applyPendingResumeSeek() (which
     *    re-invokes this once it resolves) or the seek's onPositionDiscontinuity.
     *  - resumePending is false but the frame position doesn't match the
     *    resolved target yet → the seek is still in flight; the discontinuity
     *    retry above will catch it once it lands.
     * Either way, FIRST_FRAME_PTS_MAX_WAIT_MS is a hard ceiling so a card can
     * never get stuck showing its static thumbnail forever.
     */
    private void attemptPtsGatedReveal(HomeFeedCard card, ExoPlayer player, long nowMs) {
        if (card == null || card.firstFrameRevealed || player == null || !isAdded()) return;
        if (card.firstFrameGateStartMs <= 0) card.firstFrameGateStartMs = nowMs;

        boolean timedOut = (System.currentTimeMillis() - card.firstFrameGateStartMs)
            >= FIRST_FRAME_PTS_MAX_WAIT_MS;
        boolean resumeTargetUnresolved = card.resumePending;
        long framePosMs = player.getCurrentPosition();
        boolean atExpectedPosition =
            Math.abs(framePosMs - card.resumeSeekTargetMs) <= FIRST_FRAME_PTS_TOLERANCE_MS;

        if (timedOut || (!resumeTargetUnresolved && atExpectedPosition)) {
            card.firstFramePtsGatePending = false;
            revealCardThumbnailAfterFirstFrame(card);
            return;
        }
        card.firstFramePtsGatePending = true;
    }

    private void revealCardThumbnailAfterFirstFrame(HomeFeedCard card) {
        if (card == null || card.firstFrameRevealed || card.thumbView == null) return;
        card.firstFrameRevealed = true;
        ImageView thumb = card.thumbView;
        if (thumb.getVisibility() != View.VISIBLE) return;
        // The opaque cover already hides preparation/Surface setup. Remove it
        // in one property update after a real frame is rendered; a multi-frame
        // alpha animation adds AnimationDuration work exactly where the report
        // shows 47–73 ms stalls.
        thumb.animate().cancel();
        thumb.setAlpha(0f);
        thumb.setVisibility(View.INVISIBLE);
    }

    /**
     * Enforces Home's one-decoder invariant. The old two-player standby path
     * could leave a neighbour buffering while the active player was being
     * attached, competing for decoder/network time and producing READY/Surface
     * handoff stalls. Adjacent content is now warmed by the cache preloader;
     * any legacy standby instance is stopped and returned to the shared pool.
     */
    private void releaseStandbyPlayersForStableHandoff() {
        com.callx.app.player.ExoPlayerPool pool = getContext() != null
                ? com.callx.app.player.ExoPlayerPool.get(requireContext()) : null;
        if (standbyNextPlayer != null && standbyNextPlayer != feedPlayer) {
            try {
                if (pool != null) pool.release(standbyNextPlayer);
                else standbyNextPlayer.release();
            } catch (Exception ignored) {}
        }
        if (standbyPrevPlayer != null && standbyPrevPlayer != feedPlayer
                && standbyPrevPlayer != standbyNextPlayer) {
            try {
                if (pool != null) pool.release(standbyPrevPlayer);
                else standbyPrevPlayer.release();
            } catch (Exception ignored) {}
        }
        standbyNextPlayer = null;
        standbyPrevPlayer = null;
        standbyNextIndex = -1;
        standbyNextUrl = null;
        standbyPrevIndex = -1;
        standbyPrevUrl = null;
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
        pauseFeedPhotoAudio();
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
        HomeFeedCard activeCard = (currentPlayingIndex >= 0 && currentPlayingIndex < feedCards.size())
            ? feedCards.get(currentPlayingIndex) : null;
        boolean activeCardIsPhoto = activeCard != null
            && (activeCard.videoUrl == null || activeCard.videoUrl.isEmpty());
        if (activeCardIsPhoto) {
            if (!userPausedActiveCard) {
                ReelModel photoReel = currentPlayingIndex < currentFeedPosts.size()
                    ? currentFeedPosts.get(currentPlayingIndex) : null;
                resumeFeedPhotoAudio(photoReel);
            }
        } else if (feedPlayer != null) {
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
        pauseFeedPhotoAudio();
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        // ★ Safety net for the fast-scroll Glide pause/resume gate above: if
        // the view is torn down while a fling happens to be mid-SETTLING,
        // don't leave the Activity-wide RequestManager paused for whatever
        // screen comes next.
        if (getContext() != null) {
            try { Glide.with(getContext()).resumeRequests(); } catch (Exception ignored) {}
        }
        scrollDiagnostics.detach();
        stopRealtimeNewPostsListener();
        scrollHandler.removeCallbacks(playVisibleRunnable);
        android.view.Choreographer.getInstance().removeFrameCallback(playVisibleFrameCallback);
        playVisibleCheckScheduled = false;
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
        releaseFeedPhotoAudio();
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
     * Snapshot consumed only when FrameMetrics flags an over-budget frame.
     * Keeping this out of the ordinary scroll path preserves the existing
     * allocation-free hot path.
     */
    @Override
    public HomeFeedScrollDiagnostics.Snapshot captureScrollSnapshot() {
        int firstVisible = RecyclerView.NO_POSITION;
        int lastVisible = RecyclerView.NO_POSITION;
        if (recyclerHome != null
                && recyclerHome.getLayoutManager() instanceof LinearLayoutManager) {
            LinearLayoutManager lm = (LinearLayoutManager) recyclerHome.getLayoutManager();
            firstVisible = lm.findFirstVisibleItemPosition();
            lastVisible = lm.findLastVisibleItemPosition();
        }

        HomeFeedCard card = currentPlayingIndex >= 0
                && currentPlayingIndex < feedCards.size()
                ? feedCards.get(currentPlayingIndex) : null;
        int playerState = feedPlayer != null ? feedPlayer.getPlaybackState() : -1;
        boolean playerAttached = card != null && card.playerView != null
                && card.playerView.getPlayer() != null;
        float thumbnailAlpha = card != null && card.thumbView != null
                ? card.thumbView.getAlpha() : -1f;
        String reelId = card != null && card.reelId != null ? card.reelId : "";
        long positionMs = feedPlayer != null ? feedPlayer.getCurrentPosition() : -1L;
        return new HomeFeedScrollDiagnostics.Snapshot(
                recyclerHome != null ? recyclerHome.getScrollState()
                        : RecyclerView.SCROLL_STATE_IDLE,
                currentPlayingIndex, firstVisible, lastVisible,
                feedItems.size(), playerState, playerAttached,
                card != null && card.firstFramePtsGatePending,
                card != null && card.firstFrameRevealed,
                isHwLayerOn, thumbnailAlpha, positionMs, reelId);
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
        postsSinceSponsored = 0;
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
        // containerTrending / containerFriendsActivity /
        // containerContinueWatching / containerSuggestedCreators: deliberately
        // NOT cleared here — each render*()/addCreatorCards()
        // now reuses/hides its own pooled cards in place instead of being
        // handed an emptied container to rebuild from scratch every refresh
        // (see trendingCardPool / friendsActivityCardPool /
        // continueWatchingCardPool / suggestedCreatorCardPool docs). Stale
        // cards just sit there, still showing last session's data, until
        // the new fetch resolves and overwrites them in place — no blank
        // flash. The Stories tray (rv_stories/StoriesAdapter) is a real
        // RecyclerView now, so its equivalent "don't blank on refresh"
        // behavior is just: don't clear storyEntries until loadStories()'s
        // new data is ready to swap in (see collectStoryEntriesParallel).
        if (pbTrending != null)   pbTrending.setVisibility(View.VISIBLE);
        if (pbActivity != null)   pbActivity.setVisibility(View.VISIBLE);
        if (pbContinue != null)   pbContinue.setVisibility(View.VISIBLE);
        if (pbSuggested != null)  pbSuggested.setVisibility(View.VISIBLE);
    }

    private void loadAllSections() {
        loadStories();
        loadFeed();
        loadTrending();
        loadFriendsActivity();
        loadContinueWatching();
        loadSuggestedCreators();
    }

    /**
     * Loads the lightweight Notes tray independently from Stories. Notes are
     * intentionally read from reelNotes/{uid}, not status/{uid}: a note is
     * text-only, expires after 24 hours, and must not create a story ring.
     * Older accounts simply have no node and keep the tray hidden.
     *
     * ★ Instagram-level PERF: no removeAllViews()/GONE reset up front any
     * more — same "deliberately not cleared, overwritten in place once the
     * fetch resolves" rule as containerTrending/etc (see clearAllSections
     * doc). A stale bubble from last session just keeps showing until
     * finishNotesRead() rebinds or hides it, instead of the tray blanking
     * out on every refresh.
     */
    private void loadNotes() {
        if (!isAdded() || containerNotes == null) return;
        String myUid = safeMyUid();
        if (myUid == null || myUid.isEmpty()) {
            containerNotes.setVisibility(View.GONE);
            return;
        }

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(myUid);
        if (cachedFollowedUids != null) ids.addAll(cachedFollowedUids);
        ArrayList<String> limited = new ArrayList<>(ids);
        if (limited.size() > 20) limited.subList(20, limited.size()).clear();

        final LinkedHashMap<String, NoteEntry> notes = new LinkedHashMap<>();
        final int[] remaining = {limited.size()};
        for (String uid : limited) {
            FirebaseUtils.db().getReference("reelNotes").child(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        String text = s.child("text").getValue(String.class);
                        Long expires = s.child("expiresAt").getValue(Long.class);
                        if (text != null && !text.trim().isEmpty()
                                && (expires == null || expires > System.currentTimeMillis())) {
                            String name = s.child("ownerName").getValue(String.class);
                            if (name == null || name.isEmpty()) name = s.child("name").getValue(String.class);
                            String photo = s.child("photoUrl").getValue(String.class);
                            if (photo == null || photo.isEmpty()) photo = s.child("ownerPhoto").getValue(String.class);
                            notes.put(uid, new NoteEntry(uid, name, photo, text.trim()));
                        }
                        finishNotesRead(notes, remaining);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        finishNotesRead(notes, remaining);
                    }
                });
        }
    }

    private void finishNotesRead(LinkedHashMap<String, NoteEntry> notes, int[] remaining) {
        if (--remaining[0] != 0 || !isAdded() || containerNotes == null) return;
        if (notes.isEmpty()) {
            // Nothing to show this refresh — hide any pooled bubbles left
            // from last session rather than tearing them down; the tray
            // itself just goes GONE the same as before.
            for (TextView bubble : noteBubblePool) bubble.setVisibility(View.GONE);
            containerNotes.setVisibility(View.GONE);
            return;
        }
        int i = 0;
        for (NoteEntry note : notes.values()) {
            bindNoteBubble(obtainNoteBubble(i), note);
            i++;
        }
        // Pooled bubbles left over from a previous, longer notes list —
        // hidden, not destroyed, same tail-hide rule as trendingCardPool.
        for (; i < noteBubblePool.size(); i++) noteBubblePool.get(i).setVisibility(View.GONE);
        containerNotes.setVisibility(View.VISIBLE);
    }

    /** ★ Instagram-level PERF: pooled note bubbles — same fresh-View-every-
     *  refresh problem trendingCardPool/etc fixed elsewhere in this header.
     *  Index i backs the i-th bubble in tray order; see obtainNoteBubble()/
     *  bindNoteBubble(). */
    private final List<TextView> noteBubblePool = new ArrayList<>();

    /** Returns the pooled note bubble at index i, creating (and adding to
     *  containerNotes) a new one only the first time this index is needed.
     *  Static styling/background/layout params are set exactly once per
     *  index; every later refresh only updates text/tag via bindNoteBubble(). */
    private TextView obtainNoteBubble(int index) {
        if (index < noteBubblePool.size()) return noteBubblePool.get(index);

        TextView bubble = new TextView(requireContext());
        bubble.setTextColor(0xFF202124);
        bubble.setTextSize(11f);
        bubble.setGravity(Gravity.CENTER);
        bubble.setMaxLines(2);
        bubble.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bubble.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFF0F1F5);
        bg.setCornerRadius(dpToPx(18));
        bubble.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            dpToPx(132), dpToPx(48));
        lp.setMargins(dpToPx(4), dpToPx(2), dpToPx(4), dpToPx(2));
        bubble.setLayoutParams(lp);

        // Allocation-free: reads whatever NoteEntry is CURRENTLY tagged on
        // this bubble at click time, so this single listener instance
        // stays correct across every future refresh of this slot.
        bubble.setOnClickListener(v -> {
            NoteEntry note = (NoteEntry) v.getTag();
            if (note == null) return;
            if (note.uid.equals(safeMyUid())) {
                Toast.makeText(requireContext(), "Add or edit your note from your profile",
                    Toast.LENGTH_SHORT).show();
            } else {
                Intent i = new Intent(requireContext(), UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID, note.uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME, note.name);
                startActivity(i);
            }
        });

        noteBubblePool.add(bubble);
        containerNotes.addView(bubble);
        return bubble;
    }

    /** Updates a pooled note bubble's text + click tag for `note` — no view
     *  creation, no removeAllViews(). See obtainNoteBubble() doc. */
    private void bindNoteBubble(TextView bubble, NoteEntry note) {
        String label = note.uid.equals(safeMyUid()) ? "Your note" :
            (note.name == null || note.name.isEmpty() ? "Note" : note.name);
        bubble.setText(label + "\n" + note.text);
        bubble.setTag(note);
        bubble.setVisibility(View.VISIBLE);
    }

    private static class NoteEntry {
        final String uid, name, photo, text;
        NoteEntry(String uid, String name, String photo, String text) {
            this.uid = uid; this.name = name; this.photo = photo; this.text = text;
        }
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
            final long[] avatarVersionHolder = { 0L };
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
                    slots[slot] = new StoryEntry(uid, nameHolder[0], photoHolder[0], !allSeen, false, avatarVersionHolder[0]);
                }
                contactsRemaining[0]--;
                if (contactsRemaining[0] == 0) {
                    if (!isAdded() || getContext() == null) return;
                    for (StoryEntry e : slots) if (e != null) collected.add(e);
                    collected.sort((a, b) -> Boolean.compare(!a.hasUnseen, !b.hasUnseen));
                    // PERF (RecyclerView conversion): swap the whole list in
                    // and let the adapter's real diffing^H^H^H^Hnotify do the
                    // rest — no manual per-row bind/hide dance any more.
                    // The tray always reloads wholesale (contacts + seen
                    // state are fetched as one batch above), so a single
                    // notifyDataSetChanged() here is the right tool: this
                    // path runs once per pull-to-refresh, not per frame.
                    requireActivity().runOnUiThread(() -> {
                        if (!isAdded() || getContext() == null || storiesAdapter == null) return;
                        storyEntries.clear();
                        storyEntries.addAll(collected);
                        storiesAdapter.notifyDataSetChanged();
                    });
                }
            };

            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    nameHolder[0] = snap.child("name").getValue(String.class);
                    String _photo = snap.child("photoUrl").getValue(String.class);
                    String _thumb = snap.child("thumbUrl").getValue(String.class);
                    photoHolder[0] = (_thumb != null && !_thumb.isEmpty()) ? _thumb : _photo;
                    Long _ver = snap.child("avatarVersion").getValue(Long.class);
                    avatarVersionHolder[0] = _ver != null ? _ver : 0L;
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

    /**
     * PERF (RecyclerView conversion): backs rv_stories with real
     * ViewHolder recycling — position 0 is the "Add Story" item
     * (item_home_story_add.xml), positions 1..storyEntries.size() are
     * contact rows (item_home_story.xml). Unlike the old
     * obtainStoryRowView()/bindStoryRow() pair, onBindViewHolder is only
     * ever called by RecyclerView for a row that's actually about to be
     * laid out (on-screen, or within its prefetch/cache window) — every
     * row past that simply has no View at all, instead of the old
     * approach's "every row inflated and disk-cache-bound up front,
     * upgraded on scroll" gate. That removes the need for the old
     * STORIES_EAGER_COUNT / HomeStoryAvatarBinder#bindGated /
     * HomeStoryAvatarBinder#promote split entirely — a plain
     * HomeStoryAvatarBinder#bind() here already only runs for rows
     * RecyclerView decided are worth having a View for.
     */
    private final class StoriesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? STORIES_VIEW_TYPE_ADD_STORY : STORIES_VIEW_TYPE_STORY;
        }

        @Override
        public int getItemCount() {
            return 1 + storyEntries.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == STORIES_VIEW_TYPE_ADD_STORY) {
                View v = inflater.inflate(R.layout.item_home_story_add, parent, false);
                AddStoryViewHolder holder = new AddStoryViewHolder(v);
                // Click listener registered once per ViewHolder — same
                // allocation-free "one listener for the life of the View"
                // rule as every other pooled row in this header.
                v.setOnClickListener(vw -> openAddStory());
                return holder;
            }
            View v = inflater.inflate(R.layout.item_home_story, parent, false);
            StoryViewHolder holder = new StoryViewHolder(v);
            v.setOnClickListener(vw -> {
                StoryEntry entry = holder.entry;
                if (entry == null) return;
                openStatusViewer(entry.uid, entry.name);
            });
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            if (vh instanceof AddStoryViewHolder) {
                bindAddStoryHolder((AddStoryViewHolder) vh);
            } else {
                bindStoryHolder((StoryViewHolder) vh, storyEntries.get(position - 1));
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder vh) {
            // FIX (lifecycle-aware cancel — same reasoning as the old
            // HomeStoryAvatarBinder#cancel call in hideExtraStoryRows): a
            // row that's being recycled might have an in-flight Glide
            // request; stop it and clear the tag so a stale async result
            // can never paint into whatever this recycled View gets
            // rebound to next.
            if (vh instanceof StoryViewHolder && isAdded() && getContext() != null) {
                StoryViewHolder svh = (StoryViewHolder) vh;
                HomeStoryAvatarBinder.cancel(requireContext(), svh.avatar);
                svh.entry = null;
            }
        }
    }

    /** ViewHolder for the leading "Add Story" row (position 0). */
    private static class AddStoryViewHolder extends RecyclerView.ViewHolder {
        final CircleImageView avatar;
        AddStoryViewHolder(View v) {
            super(v);
            avatar = v.findViewById(R.id.iv_my_story_avatar);
        }
    }

    /** ViewHolder for a contact's story row (position 1..N). */
    private static class StoryViewHolder extends RecyclerView.ViewHolder {
        final CircleImageView avatar;
        final TextView tvName;
        final ImageView ivSeenRing;
        final ImageView ivGradientRing;
        StoryEntry entry;
        StoryViewHolder(View v) {
            super(v);
            avatar         = v.findViewById(R.id.iv_story_avatar);
            tvName         = v.findViewById(R.id.tv_story_name);
            ivSeenRing     = v.findViewById(R.id.iv_story_seen_ring);
            ivGradientRing = v.findViewById(R.id.iv_reel_story_gradient_ring);
        }
    }

    /** Binds the "Add Story" row from the data loadMyAvatar() resolved —
     *  see {@link #myAvatarPhotoUrl} doc for why this reads from fields
     *  instead of a single long-lived ImageView. */
    private void bindAddStoryHolder(AddStoryViewHolder holder) {
        if (!isAdded() || getContext() == null) return;
        if (myAvatarLoaded) {
            // FIX (deep avatar pipeline): always inside the tray's initial
            // viewport (it's the very first row) — real IMMEDIATE bind via
            // HomeStoryAvatarBinder, same tier/L2/L3/blur-up as every other
            // Stories-tray row instead of the old flat override(96,96).
            HomeStoryAvatarBinder.bind(requireContext(), holder.avatar, myAvatarPhotoUrl,
                    myAvatarVersion, R.drawable.ic_person);
        } else {
            holder.avatar.setImageResource(R.drawable.ic_person);
        }
    }

    private void bindStoryHolder(StoryViewHolder holder, StoryEntry entry) {
        if (!isAdded() || getContext() == null) return;
        holder.entry = entry;
        holder.tvName.setText(entry.name != null ? entry.name : "User");

        // ★ Instagram-style: gradient ring for ALL stories that have unseen content
        // FIX v39: story_ring_insta_gradient.xml had a visible seam (XML sweep
        // gradient only supports 3 stops, doesn't loop back cleanly) — swapped
        // for the seamless StoryRingGradientDrawable used across the app.
        // v42 PERF: withStrokeDp() returns a SHARED cached Drawable instance
        // for this stroke width (see StoryRingGradientDrawable), so this is
        // a plain map lookup on every bind, not a per-row allocation.
        if (holder.ivGradientRing != null) {
            holder.ivGradientRing.setImageDrawable(
                    com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(3f,
                            getResources().getDisplayMetrics().density));
        }

        CircleImageView avatar = holder.avatar;
        if (entry.hasUnseen) {
            // Gradient pink/orange ring — same as Instagram, for any unseen story
            if (holder.ivGradientRing != null) holder.ivGradientRing.setVisibility(View.VISIBLE);
            avatar.setBorderColor(0xFFFFFFFF);
            avatar.setBorderWidth(dpToPx(3));
            if (holder.ivSeenRing != null) holder.ivSeenRing.setVisibility(View.GONE);
        } else {
            // Gray ring for all-seen stories
            if (holder.ivGradientRing != null) holder.ivGradientRing.setVisibility(View.GONE);
            avatar.setBorderColor(0xFF888888);
            avatar.setBorderWidth(dpToPx(2));
            if (holder.ivSeenRing != null) {
                holder.ivSeenRing.setVisibility(View.VISIBLE);
                holder.ivSeenRing.setColorFilter(0xFF888888, android.graphics.PorterDuff.Mode.SRC_IN);
            }
        }

        // FIX (deep avatar pipeline): shared AvatarSizeTier + density-aware
        // WebP/AVIF URL, L2/L3 bitmap reuse, blur-up thumbnail — see
        // HomeStoryAvatarBinder's class doc. No eager/gated split needed
        // here any more (see StoriesAdapter doc): RecyclerView itself only
        // calls onBindViewHolder for a row worth having a View for.
        HomeStoryAvatarBinder.bind(requireContext(), avatar, entry.photo, entry.avatarVersion, R.drawable.ic_person);
    }

    /**
     * PERF (RecyclerView conversion): same velocity-based "fast fling
     * skips, slow scroll warms several rows ahead" prefetch every other
     * avatar list in the app uses (see AvatarScrollPrefetchHelper, the
     * canonical version of this for a plain vertical avatar RecyclerView),
     * adapted to LinearLayoutManager(HORIZONTAL) here: velocity is derived
     * from dx/dt exactly like AvatarScrollPrefetchHelper does from dy/dt,
     * and the warm target is whatever sits just past
     * findLastVisibleItemPosition() — the rows RecyclerView hasn't already
     * decided to lay out for real.
     */
    private void attachStoriesScrollListener() {
        if (rvStories == null || storiesScrollListenerAttached) return;
        storiesScrollListenerAttached = true;
        rvStories.addOnScrollListener(new RecyclerView.OnScrollListener() {
            long lastTimeMs = 0L;
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (!isAdded() || getContext() == null) return;
                RecyclerView.LayoutManager raw = recyclerView.getLayoutManager();
                if (!(raw instanceof LinearLayoutManager)) return;
                LinearLayoutManager lm = (LinearLayoutManager) raw;
                int lastVisible = lm.findLastVisibleItemPosition();
                if (lastVisible < 0) return;

                long now = System.currentTimeMillis();
                float velocity = 0f; // treated as DEPTH_DEFAULT on the very first callback
                if (lastTimeMs != 0L) {
                    long dt = Math.max(1L, now - lastTimeMs);
                    velocity = Math.abs(dx) / (float) dt; // px/ms
                }
                lastTimeMs = now;

                // lastVisible is an adapter position (0 = Add Story); map to
                // a storyEntries index for the AvatarSource below.
                int fromIndex = lastVisible; // lastVisible+1 in adapter terms == lastVisible in storyEntries terms (offset by the Add Story slot)
                HomeStoryAvatarBinder.prefetch(requireContext(), storyEntriesAvatarSource(), fromIndex, velocity);
            }
        });
    }

    /** Read-only view over {@link #storyEntries} for {@link HomeStoryAvatarBinder#prefetch}. */
    private HomeStoryAvatarBinder.AvatarSource storyEntriesAvatarSource() {
        return new HomeStoryAvatarBinder.AvatarSource() {
            @Override public String photo(int index) { return storyEntries.get(index).photo; }
            @Override public long avatarVersion(int index) { return storyEntries.get(index).avatarVersion; }
            @Override public int size() { return storyEntries.size(); }
        };
    }

    /** Opens NewStatusActivity (cross-module via Class.forName), falling
     *  back to the reel camera — same behavior as the old btnAddStory
     *  click listener, now shared by every AddStoryViewHolder instance. */
    private void openAddStory() {
        if (!isAdded() || getContext() == null) return;
        try {
            Class<?> cls = Class.forName("com.callx.app.compose.NewStatusActivity");
            startActivity(new Intent(getContext(), cls));
        } catch (ClassNotFoundException e) {
            startActivity(new Intent(getContext(), ReelCameraActivity.class));
        }
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
                                         loadNotes();
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

                                            // Server-side pass (index.js: POST /reels/rank) —
                                            // reads the PERMANENT seen record
                                            // (reelWatchHistory/{uid}) plus live engagement
                                            // counts and returns a seen-aware reorder. This
                                            // runs AFTER FeedRankingEngine, as a second opinion
                                            // using server-durable data, not a replacement for
                                            // it. Any network failure/timeout falls back to
                                            // FeedRankingEngine's order unchanged — ranking
                                            // never blocks or breaks on this call.
                                            List<String> candidateIds = new ArrayList<>();
                                            for (ReelModel r : ranked) candidateIds.add(r.reelId);
                                            ReelFeedRankingClient.rank(uid, candidateIds,
                                                new ReelFeedRankingClient.RankCallback() {
                                                    @Override public void onRanked(List<String> serverOrder) {
                                                        if (!isAdded() || getContext() == null) return;
                                                        renderFeedPosts(
                                                            applyServerOrder(ranked, serverOrder),
                                                            uid, followedUids);
                                                    }
                                                    @Override public void onFailed() {
                                                        if (!isAdded() || getContext() == null) return;
                                                        renderFeedPosts(ranked, uid, followedUids);
                                                    }
                                                });
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
    /**
     * Reorders {@code clientRanked} (FeedRankingEngine's output) to follow
     * {@code serverOrder} (reelIds from POST /reels/rank), preserving every
     * ReelModel object — only the ORDER changes, no data is refetched.
     * Any reelId the server didn't return (e.g. it errored on one lookup)
     * keeps its original relative position by being appended at the end.
     */
    private List<ReelModel> applyServerOrder(List<ReelModel> clientRanked, List<String> serverOrder) {
        Map<String, ReelModel> byId = new HashMap<>();
        for (ReelModel r : clientRanked) if (r.reelId != null) byId.put(r.reelId, r);

        List<ReelModel> result = new ArrayList<>(clientRanked.size());
        Set<String> used = new HashSet<>();
        for (String id : serverOrder) {
            ReelModel r = byId.get(id);
            if (r != null && used.add(id)) result.add(r);
        }
        for (ReelModel r : clientRanked) {
            if (r.reelId == null || !used.contains(r.reelId)) result.add(r);
        }
        return result;
    }

    private void updateFeedTimestampBounds(List<ReelModel> posts) {
        for (ReelModel r : posts) {
            if (oldestFeedTimestamp == null || r.timestamp < oldestFeedTimestamp) oldestFeedTimestamp = r.timestamp;
            if (newestFeedTimestamp == null || r.timestamp > newestFeedTimestamp) newestFeedTimestamp = r.timestamp;
        }
    }

    private void loadReelsForFeed(Set<String> followedUids, String myUid) {
        cachedFollowedUids = followedUids;
        loadNotes();
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
        renderFeedPostsWithState(posts, likedIds, savedIds, myUid, followedUids, true);
    }

    /**
     * @param persistToLocalCache Whether to write this page to the Room
     *      instant-paint cache (see HomeFeedCacheEntity). True for every
     *      real Firebase-backed render; false only for the one-time
     *      cache→UI paint in paintFeedFromLocalCacheInstant(), so a cold
     *      start doesn't just write the disk cache straight back to itself.
     */
    private void renderFeedPostsWithState(List<ReelModel> posts, Set<String> likedIds,
                                           Set<String> savedIds, String myUid, Set<String> followedUids,
                                           boolean persistToLocalCache) {
        if (!isAdded() || getContext() == null) return;
        // Cache render state so infinite-scroll pages and the real-time
        // "new posts" refresh can reuse it without re-fetching per card.
        cachedLikedIds     = likedIds;
        cachedSavedIds     = savedIds;
        cachedMyUidForFeed = myUid;
        cachedFollowedUids = followedUids;
        requireActivity().runOnUiThread(() -> {
            if (feedAdapter == null || !isAdded()) return;
            // v299 ultra: zero-flicker handoff. If the screen is currently
            // showing the disk-cache paint and this real Firebase page has
            // the exact same top-of-feed reelId order, the user is looking
            // at correct data already — a full clearFeedRows()+rebuild here
            // would tear down and restart the ExoPlayer surface + Glide
            // targets for every visible card for literally no visual
            // change. Instagram/TikTok never re-flash the feed on a
            // reconcile that agrees with what's on screen; only replace
            // when the content actually differs.
            if (persistToLocalCache && feedPaintedFromCache
                    && isSameTopOfFeedOrder(currentFeedPosts, posts)) {
                feedPaintedFromCache = false; // now confirmed live, not just disk
                currentFeedPosts = posts;     // adopt live objects (fresh counts etc.)
                startRealtimeNewPostsListener();
                persistFeedPageToLocalCache(posts);
                return;
            }
            feedPaintedFromCache = false;
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
            postsSinceSponsored = 0;
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
            if (persistToLocalCache) persistFeedPageToLocalCache(posts);
        });
    }

    // ── Cold-start instant-paint cache (Room) ───────────────────────────────

    /**
     * Reads the last successfully-rendered feed page from Room off the main
     * thread and paints it the moment it's ready — see the call site in
     * onCreateView for the full rationale (Instagram/TikTok-style instant
     * cold start). Guarded so it's a no-op if the real Firebase response (or
     * a pull-to-refresh) already populated currentFeedPosts first; disk is
     * never allowed to stomp live data.
     */
    private void paintFeedFromLocalCacheInstant() {
        if (!isAdded() || getContext() == null) return;
        // v300 ultra: in-memory fast path first — zero disk I/O, zero
        // thread-hop, paints on this very call. Covers tab-away-and-back
        // and fragment-view recreation within the same app process, which
        // is far more common in real usage than an actual cold start. Falls
        // through to the Room read below only when this is empty (true
        // process cold start, or right after logout cleared it).
        List<ReelModel> memoryHit = sMemoryFeedCache;
        if (memoryHit != null && !memoryHit.isEmpty() && currentFeedPosts.isEmpty()) {
            thumbPreloader.preloadFrom(memoryHit, -1);
            videoPreloader.preloadFrom(memoryHit, -1);
            feedPaintedFromCache = true;
            renderFeedPostsWithState(memoryHit, new HashSet<>(), new HashSet<>(), null,
                    cachedFollowedUids, false);
            return;
        }
        final android.content.Context appCtx = requireContext().getApplicationContext();
        com.callx.app.utils.AppBgExecutor.execute(() -> {
            List<com.callx.app.db.entity.HomeFeedCacheEntity> cached;
            try {
                cached = com.callx.app.db.AppDatabase.getInstance(appCtx)
                        .homeFeedCacheDao().getCached();
            } catch (Exception e) {
                return; // Best-effort — a disk hiccup here just means no instant paint this launch.
            }
            if (cached == null || cached.isEmpty()) return;
            List<ReelModel> posts = new ArrayList<>(cached.size());
            for (com.callx.app.db.entity.HomeFeedCacheEntity e : cached) posts.add(toReelModelFromCache(e));
            // v299 ultra: start warming the first screenful's media RIGHT NOW,
            // still off the main thread — before the UI-thread hop below,
            // before a single row is even inflated. Both preloaders enqueue
            // to their own background executors (Glide / ExoPlayer cache
            // writer), so this costs nothing on this thread; it just means
            // the thumbnail bitmap and the first video's initial bytes are
            // already landing in RAM/disk cache by the time FeedAdapter
            // actually binds card 0 — the gap Instagram closes by never
            // showing a decode/buffer spinner on a warm reopen.
            thumbPreloader.preloadFrom(posts, -1);
            videoPreloader.preloadFrom(posts, -1);
            if (!isAdded() || getContext() == null) return;
            requireActivity().runOnUiThread(() -> {
                if (!isAdded() || getContext() == null) return;
                // Never stomp a live render that already landed first.
                if (!currentFeedPosts.isEmpty()) return;
                feedPaintedFromCache = true;
                renderFeedPostsWithState(posts, new HashSet<>(), new HashSet<>(), null,
                        cachedFollowedUids, false);
            });
        });
    }

    /** v299 ultra: true iff both lists carry the same reelIds in the same
     *  order over their shared length — the cheap signal that a fresh
     *  Firebase page is "the same top-of-feed" the disk cache already
     *  painted, so the live render can be treated as a confirmation
     *  instead of a replacement. Deliberately ignores everything except
     *  identity + order (not counts/captions/etc.) — a metadata-only
     *  change (a like count ticking) is exactly the case we want to keep
     *  cheap and flicker-free; a real content change (new/reordered
     *  posts) will differ in reelId order and fall through to the normal
     *  full rebuild. */
    private boolean isSameTopOfFeedOrder(List<ReelModel> a, List<ReelModel> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            ReelModel ra = a.get(i), rb = b.get(i);
            String idA = ra != null ? ra.reelId : null;
            String idB = rb != null ? rb.reelId : null;
            if (idA == null || !idA.equals(idB)) return false;
        }
        return true;
    }

    /**
     * Writes the just-rendered feed page to Room so the NEXT cold start can
     * paint instantly instead of showing the loading spinner. Capped to
     * HOME_FEED_CACHE_LIMIT rows — a "last-seen top of feed" cache, not a
     * full offline store. Best-effort: any disk failure here never affects
     * the live feed that's already on screen.
     */
    private void persistFeedPageToLocalCache(List<ReelModel> posts) {
        if (getContext() == null || posts == null || posts.isEmpty()) return;
        final android.content.Context appCtx = requireContext().getApplicationContext();
        final List<ReelModel> snapshot =
                new ArrayList<>(posts.subList(0, Math.min(posts.size(), HOME_FEED_CACHE_LIMIT)));
        // v299 ultra: skip the disk round-trip entirely when this exact page
        // (by reelId order) is already what's persisted — Home reconciles
        // against Firebase on every foreground + real-time tick, and most of
        // those ticks change nothing about the top of the feed. Computed on
        // the calling (main) thread since it's a handful of string compares,
        // not worth a background hop by itself.
        StringBuilder sig = new StringBuilder(snapshot.size() * 12);
        for (ReelModel r : snapshot) sig.append(r != null && r.reelId != null ? r.reelId : "?").append('|');
        final String signature = sig.toString();
        // v300 ultra: always refresh the in-memory mirror (cheap reference
        // assign), independent of the disk-write skip below — the memory
        // fast path should reflect the newest page even on ticks that don't
        // change the persisted disk copy.
        sMemoryFeedCache = snapshot;
        if (signature.equals(lastPersistedCacheSignature)) return;
        com.callx.app.utils.AppBgExecutor.execute(() -> {
            try {
                List<com.callx.app.db.entity.HomeFeedCacheEntity> rows = new ArrayList<>(snapshot.size());
                for (int i = 0; i < snapshot.size(); i++) rows.add(toCacheEntityFromReel(snapshot.get(i), i));
                com.callx.app.db.AppDatabase.getInstance(appCtx).homeFeedCacheDao().replaceAll(rows);
                lastPersistedCacheSignature = signature;
            } catch (Exception ignored) {
                // Best-effort disk cache write — never let a failure here affect the live feed.
            }
        });
    }

    private com.callx.app.db.entity.HomeFeedCacheEntity toCacheEntityFromReel(ReelModel r, int sortOrder) {
        com.callx.app.db.entity.HomeFeedCacheEntity e = new com.callx.app.db.entity.HomeFeedCacheEntity();
        e.reelId = r.reelId != null ? r.reelId : "";
        e.uid = r.uid;
        e.ownerName = r.ownerName;
        e.ownerPhoto = r.ownerPhoto;
        e.ownerAvatarBlurHash = r.ownerAvatarBlurHash;
        e.avatarVersion = r.avatarVersion;
        e.videoUrl = r.videoUrl;
        e.video480 = r.video480;
        e.video720 = r.video720;
        e.video1080 = r.video1080;
        e.hlsManifestUrl = r.hlsManifestUrl;
        e.thumbUrl = r.thumbUrl;
        e.thumbnailUrl = r.thumbnailUrl;
        e.blurHash = r.blurHash;
        e.caption = r.caption;
        e.musicName = r.musicName;
        e.musicId = r.musicId;
        e.musicUrl = r.musicUrl;
        e.musicCoverUrl = r.musicCoverUrl;
        e.musicArtist = r.musicArtist;
        e.musicStartSec = r.musicStartSec;
        e.timestamp = r.timestamp;
        e.duration = r.duration;
        e.width = r.width;
        e.height = r.height;
        e.likesCount = r.likesCount;
        e.commentsCount = r.commentsCount;
        e.sharesCount = r.sharesCount;
        e.viewsCount = r.viewsCount;
        e.repostCount = r.repostCount;
        e.isVerified = r.isVerified;
        e.sortOrder = sortOrder;
        return e;
    }

    private ReelModel toReelModelFromCache(com.callx.app.db.entity.HomeFeedCacheEntity e) {
        ReelModel r = new ReelModel();
        r.reelId = e.reelId;
        r.uid = e.uid;
        r.ownerName = e.ownerName;
        r.ownerPhoto = e.ownerPhoto;
        r.ownerAvatarBlurHash = e.ownerAvatarBlurHash;
        r.avatarVersion = e.avatarVersion;
        r.videoUrl = e.videoUrl;
        r.video480 = e.video480;
        r.video720 = e.video720;
        r.video1080 = e.video1080;
        r.hlsManifestUrl = e.hlsManifestUrl;
        r.thumbUrl = e.thumbUrl;
        r.thumbnailUrl = e.thumbnailUrl;
        r.blurHash = e.blurHash;
        r.caption = e.caption;
        r.musicName = e.musicName;
        r.musicId = e.musicId;
        r.musicUrl = e.musicUrl;
        r.musicCoverUrl = e.musicCoverUrl;
        r.musicArtist = e.musicArtist;
        r.musicStartSec = e.musicStartSec;
        r.timestamp = e.timestamp;
        r.duration = e.duration;
        r.width = e.width;
        r.height = e.height;
        r.likesCount = e.likesCount;
        r.commentsCount = e.commentsCount;
        r.sharesCount = e.sharesCount;
        r.viewsCount = e.viewsCount;
        r.repostCount = e.repostCount;
        r.isVerified = e.isVerified;
        return r;
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
        postsSinceSponsored++;
        if (postsSinceSponsored >= SPONSORED_EVERY_N_POSTS) {
            postsSinceSponsored = 0;
            insertSponsoredRowIfDue();
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

        // FIX: was `.endAt(oldestFeedTimestamp - 1)` (exclusive-by-subtraction).
        // Two reels can legitimately share the exact same millisecond
        // timestamp; the old cursor permanently skipped any such reel that
        // hadn't already been rendered in the previous page, since every
        // future page's query excluded that timestamp entirely. endAt() here
        // is inclusive by design — duplicates that WERE already rendered are
        // filtered out below via renderedReelIds, same as always, so nothing
        // renders twice; reels that were missed before now get a chance.
        final long queryBoundary = oldestFeedTimestamp;
        Query q = FirebaseUtils.getReelsRef()
                .orderByChild("timestamp")
                .endAt(queryBoundary)
                .limitToLast(FEED_FETCH_BATCH);

        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded() || getContext() == null) return;
                List<ReelModel> newPosts = new ArrayList<>();
                long rawOldestSeen = queryBoundary;
                for (DataSnapshot s : snap.getChildren()) {
                    ReelModel r = s.getValue(ReelModel.class);
                    if (r == null) continue;
                    if (r.reelId == null) r.reelId = s.getKey();
                    if (r.timestamp < rawOldestSeen) rawOldestSeen = r.timestamp;
                    if (renderedReelIds.contains(r.reelId)) continue;
                    if (isFollowingMode && !cachedFollowedUids.contains(r.uid)) continue;
                    newPosts.add(r);
                }
                feedHasMore = snap.getChildrenCount() >= FEED_FETCH_BATCH;
                // FIX: advance the cursor from the RAW page (rawOldestSeen),
                // not just the filtered newPosts. Previously, if an entire
                // page came back as already-rendered duplicates (possible
                // right at the boundary timestamp), oldestFeedTimestamp never
                // moved and the very next scroll-triggered call re-issued the
                // identical query forever — a stuck-pagination bug.
                if (rawOldestSeen < oldestFeedTimestamp) {
                    oldestFeedTimestamp = rawOldestSeen;
                } else if (newPosts.isEmpty() && feedHasMore) {
                    // Whole page was duplicates at the same boundary ms —
                    // step back one ms so the next call can make progress.
                    oldestFeedTimestamp = oldestFeedTimestamp - 1;
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
    /**
     * ★ Inline sponsored/ad slot — mixed into the organic feed every
     * SPONSORED_EVERY_N_POSTS posts, For-You mode only (Following stays pure
     * chronological, same rule as the other inline rows). Reads a flat
     * "sponsoredReels" Firebase node (one-time fetch, cached for the
     * session, same caching shape as suggestedCreatorPool) and round-robins
     * through it via sponsoredAdCursor so a short pool still fills every
     * slot on a long scroll instead of only ever showing ad #1.
     *
     * Honest scope note: this is real, additive feed-mixing plumbing — not
     * a real ad SDK/auction integration. There is no impression/click
     * billing pipeline here; wiring that up is a backend/ads-network
     * integration task, not something fake-able client-side. What this DOES
     * give you: the actual on-screen ad slot, cadence, and rendering that
     * an ad SDK's response would drop into.
     */
    private void insertSponsoredRowIfDue() {
        if (!isAdded() || getContext() == null || feedAdapter == null) return;
        if (sponsoredAdPool != null) {
            addSponsoredRowIfAny(sponsoredAdPool);
            return;
        }
        FirebaseUtils.db().getReference("sponsoredReels")
            .limitToLast(20)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    List<SponsoredAd> ads = new ArrayList<>();
                    for (DataSnapshot s : snap.getChildren()) {
                        String imageUrl = s.child("imageUrl").getValue(String.class);
                        String headline = s.child("headline").getValue(String.class);
                        if (imageUrl == null || imageUrl.isEmpty() || headline == null) continue;
                        SponsoredAd ad = new SponsoredAd();
                        ad.id = s.getKey();
                        ad.imageUrl = imageUrl;
                        ad.headline = headline;
                        ad.sponsorName = s.child("sponsorName").getValue(String.class);
                        ad.sponsorPhotoUrl = s.child("sponsorPhotoUrl").getValue(String.class);
                        ad.ctaText = s.child("ctaText").getValue(String.class);
                        ad.ctaUrl = s.child("ctaUrl").getValue(String.class);
                        ads.add(ad);
                    }
                    sponsoredAdPool = ads;
                    requireActivity().runOnUiThread(() -> addSponsoredRowIfAny(ads));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { /* skip this slot, try again next cadence */ }
            });
    }

    private void addSponsoredRowIfAny(List<SponsoredAd> pool) {
        if (!isAdded() || getContext() == null || feedAdapter == null || pool == null || pool.isEmpty()) return;
        SponsoredAd ad = pool.get(sponsoredAdCursor % pool.size());
        sponsoredAdCursor++;
        FeedRow row = new FeedRow(ROW_SPONSORED);
        row.sponsoredAd = ad;
        feedItems.add(row);
        feedAdapter.notifyItemInserted(FEED_HEADER_OFFSET + feedItems.size() - 1);
    }

    /** ★ Ultra-advanced optimization: view-holder caching for the sponsored
     *  card — same idea as PostRowHolder.cacheViews(). The old
     *  bindSponsoredRowContent() did container.removeAllViews() and rebuilt
     *  ~10 fresh View objects (LinearLayouts, avatar/name/label/image/
     *  headline/cta) EVERY time this row scrolled back into view, even
     *  though every sponsored slot shares the exact same shape — only the
     *  text/images/click-target differ per ad. Now the chrome is built once
     *  in onCreateViewHolder and every rebind just updates field values on
     *  the already-attached views. currentAd is read by the click listener
     *  at click time (not captured at bind time) so the listener object
     *  itself never needs to be recreated either. */
    private class SponsoredRowHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView  nameView;
        final ImageView image;
        final TextView  headline;
        final TextView  cta;
        SponsoredAd currentAd;

        SponsoredRowHolder(FrameLayout container) {
            super(container);
            Context ctx = container.getContext();

            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            cardLp.topMargin = dpToPx(6);
            cardLp.bottomMargin = dpToPx(6);
            card.setLayoutParams(cardLp);

            LinearLayout sponsorRow = new LinearLayout(ctx);
            sponsorRow.setOrientation(LinearLayout.HORIZONTAL);
            sponsorRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = dpToPx(12);
            sponsorRow.setPadding(pad, pad, pad, dpToPx(6));

            avatar = new ImageView(ctx);
            LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dpToPx(32), dpToPx(32));
            avatarLp.rightMargin = dpToPx(10);
            avatar.setLayoutParams(avatarLp);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);

            LinearLayout sponsorText = new LinearLayout(ctx);
            sponsorText.setOrientation(LinearLayout.VERTICAL);
            nameView = new TextView(ctx);
            nameView.setTextColor(0xFF222222);
            nameView.setTextSize(14f);
            nameView.setTypeface(null, android.graphics.Typeface.BOLD);
            TextView sponsoredLabel = new TextView(ctx);
            sponsoredLabel.setText("Sponsored");
            sponsoredLabel.setTextColor(0xFF8A8A8A);
            sponsoredLabel.setTextSize(11f);
            sponsorText.addView(nameView);
            sponsorText.addView(sponsoredLabel);
            sponsorRow.addView(avatar);
            sponsorRow.addView(sponsorText);

            image = new ImageView(ctx);
            LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(220));
            image.setLayoutParams(imgLp);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);

            headline = new TextView(ctx);
            headline.setTextColor(0xFF222222);
            headline.setTextSize(14f);
            headline.setPadding(pad, dpToPx(8), pad, dpToPx(4));

            cta = new TextView(ctx);
            cta.setTextColor(0xFF1877F2);
            cta.setTextSize(14f);
            cta.setTypeface(null, android.graphics.Typeface.BOLD);
            cta.setPadding(pad, dpToPx(4), pad, dpToPx(12));

            // Registered once — reads currentAd off the holder at click time
            // instead of capturing a specific `ad` in a per-bind closure, so
            // rebinding this holder to a different ad never needs a new
            // listener object.
            View.OnClickListener openAd = v -> {
                SponsoredAd ad = currentAd;
                if (ad == null || ad.ctaUrl == null || ad.ctaUrl.trim().isEmpty()) return;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ad.ctaUrl.trim())));
                } catch (Exception e) {
                    Toast.makeText(ctx, "Couldn't open link", Toast.LENGTH_SHORT).show();
                }
            };
            image.setOnClickListener(openAd);
            cta.setOnClickListener(openAd);

            card.addView(sponsorRow);
            card.addView(image);
            card.addView(headline);
            card.addView(cta);
            container.addView(card);
        }
    }

    /** Updates a pooled SponsoredRowHolder's field values for `ad` — no
     *  view creation, no removeAllViews(). See SponsoredRowHolder doc. */
    private void bindSponsoredRowHolder(SponsoredRowHolder holder, SponsoredAd ad) {
        holder.currentAd = ad;
        if (ad == null) return;

        holder.nameView.setText(ad.sponsorName != null ? ad.sponsorName : "Sponsored");
        if (ad.sponsorPhotoUrl != null && !ad.sponsorPhotoUrl.isEmpty()) {
            // 32dp avatar — same decode-size discipline as every other feed
            // avatar (AVATAR_DECODE_PX); this row scrolls through the main
            // feed RecyclerView during a fling like any other row, so an
            // uncapped remote sponsor photo was exactly the same heat/GC
            // source as the card avatars above.
            Glide.with(this).load(ad.sponsorPhotoUrl)
                .apply(new RequestOptions().transform(new CenterCrop(), new RoundedCorners(dpToPx(16))))
                .apply(FEED_IMAGE_OPTS)
                .override(AVATAR_DECODE_PX, AVATAR_DECODE_PX)
                .into(holder.avatar);
        } else {
            Glide.with(this).clear(holder.avatar);
        }

        // Ad image is match_parent width × fixed 220dp height — cap the
        // decode to actual device width instead of whatever resolution the
        // ad creative was uploaded at (ad images are frequently far higher
        // res than a 220dp-tall strip needs).
        int adImgDecodeW = getResources().getDisplayMetrics().widthPixels;
        Glide.with(this).load(ad.imageUrl)
            .apply(new RequestOptions().transform(new CenterCrop()).format(DecodeFormat.PREFER_RGB_565))
            .override(adImgDecodeW, dpToPx(220))
            .into(holder.image);

        holder.headline.setText(ad.headline);
        holder.cta.setText(ad.ctaText != null && !ad.ctaText.isEmpty() ? ad.ctaText : "Learn more");
    }

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
     *  time, by SuggestedCreatorsRowHolder/bindSuggestedCreatorsRowHolder(). */
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

    /** ★ View-holder caching for the "Suggested for you" row — same pattern
     *  as PostRowHolder/SponsoredRowHolder. The old
     *  bindSuggestedCreatorsRowContent() did container.removeAllViews() and
     *  rebuilt the section LinearLayout + header TextView + a brand-new
     *  RecyclerView (with a brand-new SuggestedCreatorsTileAdapter) EVERY
     *  time this row scrolled back into view — even though the nested
     *  RecyclerView already shares SUGGESTED_CREATORS_TILE_POOL, so tearing
     *  it down and rebuilding it on every rebind threw away that reuse
     *  benefit at the outer-row level. The chrome (section/header/
     *  RecyclerView) and its adapter are now built exactly once in
     *  onCreateViewHolder; every rebind just calls updateItems() on the
     *  existing adapter. */
    private class SuggestedCreatorsRowHolder extends RecyclerView.ViewHolder {
        final RecyclerView chipsRecycler;
        final SuggestedCreatorsTileAdapter adapter;

        SuggestedCreatorsRowHolder(FrameLayout container) {
            super(container);
            Context ctx = container.getContext();

            LinearLayout section = new LinearLayout(ctx);
            section.setOrientation(LinearLayout.VERTICAL);
            int dp16 = dpToPx(16);
            section.setPadding(dp16, dpToPx(12), dp16, dpToPx(4));

            TextView header = new TextView(ctx);
            header.setText("Suggested for you");
            header.setTextColor(0xFFFFFFFF);
            header.setTextSize(13f);
            header.setTypeface(null, android.graphics.Typeface.BOLD);
            section.addView(header);

            chipsRecycler = new RecyclerView(ctx);
            chipsRecycler.setLayoutManager(
                    new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
            chipsRecycler.setRecycledViewPool(SUGGESTED_CREATORS_TILE_POOL);
            chipsRecycler.setItemViewCacheSize(4);
            chipsRecycler.setHasFixedSize(true);
            chipsRecycler.setItemAnimator(null);
            LinearLayout.LayoutParams recyclerLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            recyclerLp.topMargin = dpToPx(8);
            chipsRecycler.setLayoutParams(recyclerLp);

            adapter = new SuggestedCreatorsTileAdapter(new ArrayList<>());
            chipsRecycler.setAdapter(adapter);

            section.addView(chipsRecycler);
            container.addView(section);
        }
    }

    /** Swaps candidates into a pooled SuggestedCreatorsRowHolder's existing
     *  adapter — no view creation, no removeAllViews(). See
     *  SuggestedCreatorsRowHolder doc. */
    private void bindSuggestedCreatorsRowHolder(SuggestedCreatorsRowHolder holder, List<String[]> candidates) {
        holder.itemView.setVisibility(candidates == null || candidates.isEmpty() ? View.GONE : View.VISIBLE);
        holder.adapter.updateItems(candidates != null ? candidates : java.util.Collections.emptyList());
    }

    /** Shared across every "Suggested for you" creators strip mixed into the
     *  Home feed — see SuggestedCreatorsRowHolder note above for why
     *  this must be a single shared instance rather than one pool per strip. */
    private static final RecyclerView.RecycledViewPool SUGGESTED_CREATORS_TILE_POOL =
            new RecyclerView.RecycledViewPool();

    /** Backs one "Suggested for you" strip's avatar chips with real
     *  ViewHolder recycling (see SuggestedCreatorsRowHolder). Chip
     *  dimensions (136dp wide, 90dp avatar) unchanged from the old manual
     *  row, so this is a drop-in visual match. */
    private class SuggestedCreatorsTileAdapter extends RecyclerView.Adapter<SuggestedCreatorsTileAdapter.TileHolder> {
        private static final int CHIP_W_DP = 136;
        private static final int AVATAR_DP = 90;

        private List<String[]> items;

        SuggestedCreatorsTileAdapter(List<String[]> items) {
            this.items = items;
        }

        /** ★ Lets the SAME adapter instance — and therefore the same
         *  RecyclerView + its shared-pool ViewHolders — be reused across
         *  binds of the strip it backs, instead of a brand-new adapter (and
         *  brand-new RecyclerView) being built every time the row scrolls
         *  back into view. See SuggestedCreatorsRowHolder. */
        void updateItems(List<String[]> newItems) {
            this.items = newItems;
            notifyDataSetChanged();
        }

        class TileHolder extends RecyclerView.ViewHolder {
            final LinearLayout chip;
            final CircleImageView av;
            final TextView tvName;
            final LinearLayout llMutual;
            final CircleImageView ivMutualAvatar;
            final TextView tvMutual;
            /** Bumped on every bind; guards the async mutual-followers callback
             *  below from landing on a ViewHolder the shared pool has since
             *  handed to a different candidate (RecyclerView reuse race). */
            int bindToken = 0;
            TileHolder(LinearLayout chip, CircleImageView av, TextView tvName,
                       LinearLayout llMutual, CircleImageView ivMutualAvatar, TextView tvMutual) {
                super(chip);
                this.chip = chip; this.av = av; this.tvName = tvName;
                this.llMutual = llMutual; this.ivMutualAvatar = ivMutualAvatar; this.tvMutual = tvMutual;
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

            // NEW: Instagram-style "N mutual" row (mini avatar + label) below the
            // name — hidden until onBindViewHolder resolves an actual mutual
            // count for this candidate. Same mutual-followers concept already
            // shown on a reel's bio (ll_reel_mutual_followers / ReelSocialController),
            // reused here via the shared MutualFollowersCache — no new
            // mutual-followers computation logic written for this row.
            LinearLayout llMutual = new LinearLayout(requireContext());
            llMutual.setOrientation(LinearLayout.HORIZONTAL);
            llMutual.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams mutualRowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mutualRowLp.topMargin = dpToPx(3);
            llMutual.setLayoutParams(mutualRowLp);
            llMutual.setVisibility(View.GONE);

            CircleImageView ivMutualAvatar = new CircleImageView(requireContext());
            ivMutualAvatar.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(12), dpToPx(12)));
            llMutual.addView(ivMutualAvatar);

            TextView tvMutual = new TextView(requireContext());
            tvMutual.setTextSize(10.5f);
            tvMutual.setTextColor(0xFFAAAAAA);
            tvMutual.setMaxLines(1);
            tvMutual.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams mutualTextLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            mutualTextLp.setMarginStart(dpToPx(3));
            tvMutual.setLayoutParams(mutualTextLp);
            llMutual.addView(tvMutual);

            chip.addView(llMutual);

            return new TileHolder(chip, av, tvName, llMutual, ivMutualAvatar, tvMutual);
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
                // Tile avatar is AVATAR_DP (90dp) — cap decode to it instead
                // of the source profile photo's full resolution; this strip
                // scrolls (horizontally, and vertically as part of the main
                // feed) during a fling the same as every card thumbnail.
                int avPx = dpToPx(AVATAR_DP);
                Glide.with(requireContext()).load(photo)
                    .apply(RequestOptions.circleCropTransform())
                    .apply(FEED_IMAGE_OPTS)
                    .override(avPx, avPx)
                    .placeholder(R.drawable.ic_person).into(holder.av);
            } else {
                Glide.with(requireContext()).clear(holder.av);
            }

            // NEW: "N mutual" row — resolved via the shared MutualFollowersCache
            // (core/cache), the exact same cache reels already use for their
            // "Followed by X, Y and N others" bio row, so this costs nothing
            // extra beyond what's already warm from browsing reels this session.
            holder.llMutual.setVisibility(View.GONE);
            String myUid = safeMyUid();
            final int token = ++holder.bindToken;
            if (myUid != null && !myUid.isEmpty() && !myUid.equals(uid)) {
                com.callx.app.cache.MutualFollowersCache.getInstance()
                    .getMutualFollowers(myUid, uid, (uids, names, photos) -> {
                        if (token != holder.bindToken || !isAdded() || getContext() == null) return; // stale — recycled
                        int count = uids.size();
                        if (count <= 0) {
                            holder.llMutual.setVisibility(View.GONE);
                            return;
                        }
                        if (!photos.isEmpty() && !photos.get(0).isEmpty()) {
                            // 12dp mini-avatar — the smallest avatar in the
                            // whole feed and, before this, the only one with
                            // zero decode cap.
                            int mutualPx = dpToPx(12);
                            Glide.with(requireContext()).load(photos.get(0))
                                .placeholder(R.drawable.ic_person)
                                .apply(RequestOptions.circleCropTransform())
                                .apply(FEED_IMAGE_OPTS)
                                .override(mutualPx, mutualPx)
                                .into(holder.ivMutualAvatar);
                        } else {
                            holder.ivMutualAvatar.setImageResource(R.drawable.ic_person);
                        }
                        holder.tvMutual.setText(count == 1 ? "1 mutual" : count + " mutual");
                        holder.llMutual.setVisibility(View.VISIBLE);
                    });
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
            Glide.with(requireContext()).clear(holder.ivMutualAvatar);
            holder.bindToken++; // invalidate any in-flight mutual-followers lookup for this holder
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
     *  by SuggestedReelsRowHolder/bindSuggestedReelsRowHolder() at bind time. */
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

    /** ★ View-holder caching for the "Suggested reels" row — same pattern as
     *  SuggestedCreatorsRowHolder above. The old bindSuggestedReelsRowContent()
     *  rebuilt the section/header row/header text/"⋮" button (with a brand
     *  new click listener capturing that bind's onNotInterested) AND a fresh
     *  RecyclerView+adapter every single time this row scrolled back into
     *  view. Chrome + adapter now built once in onCreateViewHolder; the "⋮"
     *  listener is registered once and reads a mutable `onNotInterested`
     *  field off the holder at click time, and every rebind just updates
     *  that field plus calls updateItems() on the existing adapter. */
    private class SuggestedReelsRowHolder extends RecyclerView.ViewHolder {
        final RecyclerView tilesRecycler;
        final SuggestedReelsTileAdapter adapter;
        Runnable onNotInterested;

        SuggestedReelsRowHolder(FrameLayout container) {
            super(container);
            Context ctx = container.getContext();

            LinearLayout section = new LinearLayout(ctx);
            section.setOrientation(LinearLayout.VERTICAL);
            int dp16 = dpToPx(16);
            section.setPadding(dp16, dpToPx(12), dp16, dpToPx(4));

            LinearLayout headerRow = new LinearLayout(ctx);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
            headerRow.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView header = new TextView(ctx);
            header.setText("Suggested reels");
            header.setTextColor(0xFFFFFFFF);
            header.setTextSize(13f);
            header.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams headerLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            header.setLayoutParams(headerLp);
            headerRow.addView(header);

            ImageView btnMore = new ImageView(ctx);
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

            tilesRecycler = new RecyclerView(ctx);
            tilesRecycler.setLayoutManager(
                    new LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false));
            tilesRecycler.setRecycledViewPool(SUGGESTED_REELS_TILE_POOL);
            tilesRecycler.setItemViewCacheSize(4);
            tilesRecycler.setHasFixedSize(true);
            tilesRecycler.setItemAnimator(null);
            LinearLayout.LayoutParams recyclerLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(284));
            recyclerLp.topMargin = dpToPx(8);
            tilesRecycler.setLayoutParams(recyclerLp);

            adapter = new SuggestedReelsTileAdapter(new ArrayList<>(), new ArrayList<>());
            tilesRecycler.setAdapter(adapter);

            section.addView(tilesRecycler);
            container.addView(section);
        }
    }

    /** Swaps candidates + the "Not interested" callback into a pooled
     *  SuggestedReelsRowHolder's existing adapter/listener — no view
     *  creation, no removeAllViews(). See SuggestedReelsRowHolder doc. */
    private void bindSuggestedReelsRowHolder(SuggestedReelsRowHolder holder, List<ReelModel> candidates,
                                              Runnable onNotInterested) {
        holder.onNotInterested = onNotInterested;
        holder.itemView.setVisibility(candidates == null || candidates.isEmpty() ? View.GONE : View.VISIBLE);
        if (candidates == null || candidates.isEmpty()) {
            holder.adapter.updateItems(java.util.Collections.emptyList(), new ArrayList<>());
            return;
        }
        final ArrayList<String> reelIds = new ArrayList<>();
        for (ReelModel r : candidates) reelIds.add(r.reelId);
        holder.adapter.updateItems(candidates, reelIds);
    }

    /** Shared across every "Suggested reels" strip mixed into the Home feed —
     *  see SuggestedReelsRowHolder note above for why this must be a
     *  single shared instance rather than one pool per strip. */
    private static final RecyclerView.RecycledViewPool SUGGESTED_REELS_TILE_POOL =
            new RecyclerView.RecycledViewPool();

    /** Backs one "Suggested reels" strip's tiles with real ViewHolder
     *  recycling (see SuggestedReelsRowHolder). ~9:16 tile, bigger
     *  than a grid cell so only ~2 tiles + a peek of the 3rd fit per screen
     *  (matches reference screenshot) — same sizing as the old manual row. */
    private class SuggestedReelsTileAdapter extends RecyclerView.Adapter<SuggestedReelsTileAdapter.TileHolder> {
        private static final int TILE_W_DP = 160;
        private static final int TILE_H_DP = 284;

        private List<ReelModel> items;
        private ArrayList<String> reelIds;

        SuggestedReelsTileAdapter(List<ReelModel> items, ArrayList<String> reelIds) {
            this.items = items;
            this.reelIds = reelIds;
        }

        /** ★ Same reuse trick as SuggestedCreatorsTileAdapter.updateItems() —
         *  see SuggestedReelsRowHolder for why this must swap data on the
         *  existing adapter/RecyclerView rather than build fresh ones. */
        void updateItems(List<ReelModel> newItems, ArrayList<String> newReelIds) {
            this.items = newItems;
            this.reelIds = newReelIds;
            notifyDataSetChanged();
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
                // feed's cheap-decode thumbnails (FEED_IMAGE_OPTS), plus an
                // explicit .override() to this tile's fixed decode size —
                // see SUGGESTED_TILE_DECODE_W/H doc for why this was the one
                // remaining feed thumbnail load with no decode-size cap.
                Glide.with(requireContext()).load(thumbUrl).apply(FEED_IMAGE_OPTS)
                    .override(SUGGESTED_TILE_DECODE_W, SUGGESTED_TILE_DECODE_H)
                    .centerCrop().into(holder.thumb);
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

    /** ★ Instagram-level PERF: view-holder caching for the "N new posts"
     *  pill — same idea as SponsoredRowHolder. The old
     *  bindNewPostsBannerContent() did container.removeAllViews() and
     *  rebuilt a fresh TextView + a fresh click listener EVERY time this
     *  row (re)bound, even though the pill's only per-bind change is its
     *  text. Chrome is built once in onCreateViewHolder; every rebind just
     *  updates the text on the already-attached TextView. The click
     *  listener never referenced bind-time data in the first place, so
     *  registering it once at creation time changes nothing about its
     *  behavior. */
    /** ★ Instagram-level PERF: view-holder caching for the Loading spinner
     *  row — same idea as NewPostsBannerHolder. The old inline
     *  ROW_LOADING branch in onBindViewHolder did container.removeAllViews()
     *  and built a fresh ProgressBar EVERY time this row (re)bound, even
     *  though it has no per-bind data at all (nothing to update — a
     *  spinner is just a spinner). Chrome is built once in
     *  onCreateViewHolder; onBindViewHolder for ROW_LOADING is now a no-op,
     *  same as VT_HEADER/VT_FOOTER. */
    private class LoadingRowHolder extends RecyclerView.ViewHolder {
        LoadingRowHolder(FrameLayout container) {
            super(container);
            ProgressBar pb = new ProgressBar(container.getContext());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dpToPx(32), dpToPx(32));
            lp.gravity = android.view.Gravity.CENTER;
            lp.topMargin = dpToPx(24);
            lp.bottomMargin = dpToPx(24);
            pb.setLayoutParams(lp);
            container.addView(pb);
        }
    }

    /** ★ Instagram-level PERF: view-holder caching for the "No posts yet"
     *  empty state — same idea as LoadingRowHolder. The old inline
     *  ROW_EMPTY branch in onBindViewHolder did container.removeAllViews()
     *  and built a fresh TextView EVERY time this row (re)bound, even
     *  though its text is a fixed string with no per-bind data. Chrome is
     *  built once in onCreateViewHolder; onBindViewHolder for ROW_EMPTY is
     *  now a no-op. */
    private class EmptyRowHolder extends RecyclerView.ViewHolder {
        EmptyRowHolder(FrameLayout container) {
            super(container);
            TextView tv = new TextView(container.getContext());
            tv.setText("No posts yet");
            tv.setTextColor(0xFF888888);
            tv.setTextSize(14f);
            tv.setGravity(android.view.Gravity.CENTER);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dpToPx(48);
            lp.bottomMargin = dpToPx(48);
            tv.setLayoutParams(lp);
            container.addView(tv);
        }
    }

    /** ★ Instagram-level PERF: view-holder caching for the pagination
     *  footer spinner — same idea as LoadingRowHolder. The old inline
     *  ROW_LOAD_MORE_FOOTER branch in onBindViewHolder did
     *  container.removeAllViews() and built a fresh ProgressBar EVERY time
     *  this row (re)bound, even though it has no per-bind data at all.
     *  Chrome is built once in onCreateViewHolder; onBindViewHolder for
     *  ROW_LOAD_MORE_FOOTER is now a no-op. */
    private class LoadMoreFooterRowHolder extends RecyclerView.ViewHolder {
        LoadMoreFooterRowHolder(FrameLayout container) {
            super(container);
            ProgressBar pb = new ProgressBar(container.getContext());
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dpToPx(48));
            lp.gravity = android.view.Gravity.CENTER;
            lp.topMargin = dpToPx(8);
            lp.bottomMargin = dpToPx(8);
            pb.setLayoutParams(lp);
            container.addView(pb);
        }
    }

    private class NewPostsBannerHolder extends RecyclerView.ViewHolder {
        final TextView pill;

        NewPostsBannerHolder(FrameLayout container) {
            super(container);
            Context ctx = container.getContext();
            pill = new TextView(ctx);
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
            pill.setOnClickListener(v -> {
                if (recyclerHome != null) recyclerHome.smoothScrollToPosition(0);
                resetFeedPaginationState();
                cancelStagedFeedRender();
                clearFeedRows();
                showFeedLoading(true);
                loadFeed();
            });
            container.addView(pill);
        }
    }

    /** Updates a pooled NewPostsBannerHolder's text for the current pending
     *  count — no view creation, no removeAllViews(). See
     *  NewPostsBannerHolder doc. */
    private void bindNewPostsBannerHolder(NewPostsBannerHolder holder) {
        holder.pill.setText(newPostsPending == 1
                ? "1 new post · Tap to refresh" : (newPostsPending + " new posts · Tap to refresh"));
        newPostsBanner = holder.pill;
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
            int state = hasUnseen ? 2 : (hasAny ? 1 : 0);
            // ★ Instagram-level PERF: skip setBackground/setImageDrawable/
            // setVisibility entirely when this exact uid+state was already
            // applied on this holder's last bind — see field doc above.
            boolean unchanged = reel.uid.equals(holder.lastStoryRingUid)
                    && state == holder.lastStoryRingState;
            if (!unchanged) {
                holder.lastStoryRingUid = reel.uid;
                holder.lastStoryRingState = state;
                if (state == 2) {
                    ivPostStoryRing.setImageDrawable(null);
                    ivPostStoryRing.setBackground(
                            com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(2f,
                                    getResources().getDisplayMetrics().density));
                    ivPostStoryRing.setVisibility(View.VISIBLE);
                } else if (state == 1) {
                    ivPostStoryRing.setBackground(null);
                    ivPostStoryRing.setImageResource(com.callx.app.core.R.drawable.circle_status_seen);
                    ivPostStoryRing.setVisibility(View.VISIBLE);
                } else {
                    ivPostStoryRing.setVisibility(View.GONE);
                }
            }
        } else if (ivPostStoryRing != null) {
            holder.lastStoryRingUid = null;
            holder.lastStoryRingState = -1;
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
        ImageButton btnAudioCover = holder.btnAudioCover;
        SeekBar     sbProgress    = holder.sbProgress;
        TextView    tvPosition    = holder.tvPosition;
        TextView    tvSpeedChip   = holder.tvSpeedChip;
        View        playOverlay   = holder.playOverlay;

        // ── Instagram-level approach: Home Feed vs Reels tab ─────────────────
        // Reels tab (fragment_reel_player.xml) is a dedicated fullscreen
        // experience. Home Feed is a scrolling post list, so its media frame
        // stays within Instagram's feed bounds instead of forcing every post
        // into a 9:16 rectangle. FIT then keeps the complete source visible;
        // the blurred backdrop fills any intentional letterbox area.
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
            int videoH = feedCardMediaHeightPx(reel);
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
        ensureFeedCardsCapacity(cardIndex + 1);
        // ★ PERF: this used to be `new HomeFeedCard()` on every single bind —
        // reuse the existing card object at this index when one's already
        // there (the overwhelmingly common case: a rebind of the same slot,
        // e.g. notifyItemChanged() after a like/follow tap, or the same row
        // scrolling back into view) instead of throwing the old one away.
        // Every field below is still explicitly reassigned exactly as
        // before, so this is a pure allocation-avoidance change — nothing
        // about what the card ends up holding is different. The playback
        // -state fields (firstFrameRevealed, resumeSeekTargetMs, etc.) are
        // reset to their fresh-card defaults explicitly, since a reused
        // object could otherwise carry stale state left over from whichever
        // reel previously occupied this slot.
        HomeFeedCard feedCard = feedCards.get(cardIndex);
        if (feedCard == null) {
            feedCard = new HomeFeedCard();
        } else if (!java.util.Objects.equals(feedCard.reelId, reel.reelId)) {
            // Reused object is being repurposed for a DIFFERENT reel than
            // whatever last occupied this slot — clear playback state so
            // nothing stale (a PTS gate, a scrub-drag flag, a resume seek
            // target) leaks from the old reel into the new one. If it's the
            // SAME reel rebinding (e.g. notifyItemChanged() from a like tap
            // while this card is actively playing), leave this state alone —
            // resetting it here would reset an in-progress PTS gate or
            // interrupt an active scrub for no reason.
            feedCard.firstFrameRevealed = false;
            feedCard.firstFramePtsGatePending = false;
            feedCard.resumeSeekTargetMs = 0L;
            feedCard.firstFrameGateStartMs = 0L;
            feedCard.isScrubbing = false;
            feedCard.resumePending = false;
            feedCard.speedBoosted = false;
        }
        feedCard.rootView   = card;
        feedCard.playerView = pvFeed;
        feedCard.thumbView  = ivThumb;
        feedCard.backdropView = holder.ivBackdrop;
        feedCard.mediaFrame = frameVideo;
        feedCard.endOverlay = endOverlay;
        feedCard.videoUrl   = (reel.videoUrl != null && !reel.videoUrl.isEmpty())
                              ? reel.videoUrl
                              : (reel.video480 != null ? reel.video480 : "");
        feedCard.reelId     = reel.reelId;
        feedCard.seekBar     = sbProgress;
        feedCard.tvPosition  = tvPosition;
        feedCard.speedChip   = tvSpeedChip;
        feedCard.playOverlay = playOverlay;
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

        // ── Update this holder's bound state ─────────────────────────────────
        // Read by the (registered-once, see below) click listeners on
        // watchMore/watchAgain/btnMute/btnAudioCover/tvAudio/btnPostFollow —
        // a cheap field write here instead of a fresh listener allocation
        // per bind. See PostRowHolder's boundXxx fields doc.
        holder.boundReel = reel;
        holder.boundCardIndex = cardIndex;
        holder.boundMyUid = myUid;
        bindInlineSocialMetadata(holder, reel);

        // ── End-of-reel overlay buttons ──────────────────────────────────────
        if (watchMore != null && !holder.clickListenersBound) {
            watchMore.setOnClickListener(x -> {
                if (!isAdded() || getContext() == null) return;
                ReelModel currentReel = holder.boundReel;
                if (currentReel == null) return;
                // Instagram-level: "Watch more reels" after a Home Feed reel
                // finishes drops the user into the actual Reels tab feed,
                // landing on this exact reel — not a generic explore screen.
                Fragment parent = getParentFragment();
                if (parent instanceof ReelsFragment) {
                    ((ReelsFragment) parent).openReelInFeed(currentReel);
                } else {
                    // Defensive fallback if HomeFragment is ever hosted
                    // somewhere other than inside ReelsFragment.
                    startActivity(new Intent(getContext(), ReelExploreActivity.class));
                }
            });
        }
        if (watchAgain != null && !holder.clickListenersBound) {
            // endOverlay/ivThumb/sbProgress are holder-cached views (stable
            // for this physical holder's whole lifetime — see cacheViews()),
            // safe to capture directly; only cardIndex changes bind-to-bind,
            // so that one reads holder.boundCardIndex instead.
            watchAgain.setOnClickListener(x -> {
                if (feedPlayer == null) return;
                // Hide overlay, reset thumb visibility, seek to 0, replay
                if (endOverlay != null) endOverlay.setVisibility(View.GONE);
                if (ivThumb != null) { ivThumb.setAlpha(0f); ivThumb.setVisibility(View.INVISIBLE); }
                if (sbProgress != null) sbProgress.setProgress(0);
                feedPlayer.seekTo(0);
                currentPlayingIndex = holder.boundCardIndex;
                resumeActiveCard(holder.boundCardIndex);
            });
        }

        // ── Mute toggle ──────────────────────────────────────────────────────
        // Doesn't capture any per-reel state at all (isMuted/feedPlayer are
        // fragment-level, btnMute is the holder's own stable view field) —
        // safe to register exactly once, same as the others below.
        if (btnMute != null && !holder.clickListenersBound) {
            btnMute.setOnClickListener(x -> {
                isMuted = !isMuted;
                if (feedPlayer != null) feedPlayer.setVolume(isMuted ? 0f : 1f);
                if (feedPhotoAudioPlayer != null) {
                    try { feedPhotoAudioPlayer.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f); } catch (Exception ignored) {}
                }
                btnMute.setImageResource(isMuted
                    ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
            });
        }

        // ── Audio-cover tile — reused from the immersive Reels player's
        // right action rail (see fragment_reel_player.xml's
        // btn_create_audio / ReelUiController), same 28dp size as the
        // player and pinned to the video's bottom-right corner instead of
        // being the last item in a vertical rail. Same cover-resolution +
        // click destination as the tv_post_audio label below
        // (openHomeCardSoundDetail()). GONE when the reel has no music.
        if (btnAudioCover != null) {
            boolean hasMusic = (reel.musicName != null && !reel.musicName.isEmpty())
                             || (reel.musicArtist != null && !reel.musicArtist.isEmpty());
            if (hasMusic) {
                btnAudioCover.setVisibility(View.VISIBLE);
                if (!holder.clickListenersBound) {
                    btnAudioCover.setOnClickListener(x -> {
                        if (holder.boundReel != null) openHomeCardSoundDetail(holder.boundReel);
                    });
                }
                String coverUrl = !android.text.TextUtils.isEmpty(reel.musicCoverUrl)
                    ? reel.musicCoverUrl : reel.ownerPhoto;
                if (isAdded() && getContext() != null && !android.text.TextUtils.isEmpty(coverUrl)) {
                    // ★ Instagram-level PERF: skip the whole Glide chain
                    // when this holder is already showing this exact cover
                    // URL — same rebind-skip already applied to the thumb/
                    // avatar loads above (lastThumbUrl/lastAvatarUrl).
                    if (!coverUrl.equals(holder.lastAudioCoverUrl)) {
                        holder.lastAudioCoverUrl = coverUrl;
                        android.content.Context ctx = requireContext();
                        // PERF: same 28dp pattern as the player's btn_create_audio —
                        // server-resize via AvatarUrlBuilder(..,28) AND pin Glide's
                        // decode with .override() to 28dp*2 (retina), so this never
                        // decodes more pixels than the 28dp tile actually shows.
                        int sizePx = AvatarUrlBuilder.tierPx(ctx, com.callx.app.utils.AvatarSizeTier.TINY);
                        int cornerRadiusPx = AvatarUrlBuilder.dpToPx(ctx, 4);
                        Glide.with(ctx)
                            .load(AvatarUrlBuilder.build(ctx, coverUrl, com.callx.app.utils.AvatarSizeTier.TINY))
                            .apply(new RequestOptions()
                                .transform(new MultiTransformation<>(
                                    new CenterCrop(), new RoundedCorners(cornerRadiusPx)))
                                .override(sizePx, sizePx)
                                .format(DecodeFormat.PREFER_RGB_565)
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .placeholder(R.drawable.ic_audio))
                            .into(btnAudioCover);
                    }
                } else {
                    holder.lastAudioCoverUrl = null;
                    btnAudioCover.setImageResource(R.drawable.ic_audio);
                }
            } else {
                btnAudioCover.setVisibility(View.GONE);
                holder.lastAudioCoverUrl = null;
                // No need to null the listener anymore — it reads
                // holder.boundReel dynamically (never stale) and the view
                // is GONE so it can't receive clicks either way.
            }
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
                // Tap the song label → SoundDetailActivity, same "Use this
                // sound" screen the immersive Reels player's audio-pill tap
                // opens (ReelDuetController.openSoundDetail()).
                if (!holder.clickListenersBound) {
                    tvAudio.setOnClickListener(x -> {
                        if (holder.boundReel != null) openHomeCardSoundDetail(holder.boundReel);
                    });
                }
            }
        }

        // ── "Suggested for you" — shown for non-following posts (For You mode) ──
        final String ownerUidRef = reel.uid != null ? reel.uid : "";
        holder.boundOwnerUidRef = ownerUidRef;
        if (!isFollowingMode && tvSuggested != null && !ownerUidRef.isEmpty()) {
            tvSuggested.setVisibility(android.view.View.VISIBLE);
            if (tvTime   != null) tvTime.setVisibility(android.view.View.GONE);
            if (btnPostFollow != null) {
                // PERF: was an individual Firebase read per card
                // (getReelFollowsRef(myUid).child(ownerUidRef)) — now a single
                // pre-fetched Set lookup, since followedUids is fetched ONCE
                // per feed render in loadFeed()/loadReelsForFeed().
                boolean isFollowedNow = followedUids != null && followedUids.contains(ownerUidRef);
                holder.boundIsFollowed[0] = isFollowedNow;
                btnPostFollow.setVisibility(isFollowedNow ? android.view.View.GONE : android.view.View.VISIBLE);
                if (!holder.clickListenersBound) {
                    btnPostFollow.setOnClickListener(x -> {
                        String uid = holder.boundMyUid;
                        String ownerUid = holder.boundOwnerUidRef;
                        if (uid == null || ownerUid == null || ownerUid.isEmpty()) return;
                        holder.boundIsFollowed[0] = true;
                        btnPostFollow.setVisibility(android.view.View.GONE);
                        FirebaseUtils.getReelFollowsRef(uid).child(ownerUid).setValue(true);
                        FirebaseUtils.getReelFollowersRef(ownerUid).child(uid).setValue(true);
                    });
                }
            }
        }

        // All the listeners above that check `!holder.clickListenersBound`
        // have now been registered exactly once for this physical holder —
        // every future bind (a different reel scrolling into this same
        // recycled row) skips straight past them and just relies on the
        // boundXxx field updates above to keep them pointed at the right
        // reel/cardIndex/uid.
        // ── Bottom-left collab icon — registered ONCE per physical holder
        // (fixed view in item_home_feed_post.xml, never lazily inflated),
        // reads holder.boundReel at click time — same pattern as
        // watchMore/watchAgain/btnMute above, avoids allocating a fresh
        // lambda on every single bind/scroll.
        if (holder.btnCollabIcon != null && !holder.clickListenersBound) {
            holder.btnCollabIcon.setOnClickListener(v -> {
                ReelModel currentReel = holder.boundReel;
                if (!isAdded() || currentReel == null) return;
                com.callx.app.social.CollaboratorsBottomSheet.newInstance(
                        currentReel.reelId, currentReel.uid, currentReel.ownerName, currentReel.ownerPhoto)
                    .show(getChildFragmentManager(), com.callx.app.social.CollaboratorsBottomSheet.TAG);
            });
        }
        holder.clickListenersBound = true;

        // ── Collab / multi-collaborator header — REUSED from the Reels play
        // screen (ReelUiController.populateStaticData()). Same priority:
        // new stack-based system first (reel.acceptedCollaborators() /
        // legacy single collabUid), old two-field dual-author system as a
        // fallback only when the new system has nothing, else solo owner.
        java.util.List<com.callx.app.models.ReelModel.CollabCollaborator> acceptedHome = reel.acceptedCollaborators();
        boolean legacySingleOnlyHome = acceptedHome.isEmpty() && reel.isCollabPost
            && reel.collabUid != null && !reel.collabUid.isEmpty();
        boolean isCollabStackDisplay = !acceptedHome.isEmpty() || legacySingleOnlyHome;

        View llCollabAuthorsHome = holder.llCollabAuthorsHome;
        if (llCollabAuthorsHome == null) {
            llCollabAuthorsHome = holder.itemView.findViewById(R.id.ll_collab_second_author);
            if (llCollabAuthorsHome == null && isCollabStackDisplay) {
                View stubHome = holder.itemView.findViewById(R.id.stub_home_collab_row);
                if (stubHome instanceof android.view.ViewStub) {
                    llCollabAuthorsHome = ((android.view.ViewStub) stubHome).inflate();
                }
            }
            if (llCollabAuthorsHome != null) {
                // ── PERF: cache the collab row + its children on the holder
                // the FIRST time this physical row ever inflates/finds them —
                // every rebind afterwards (this row scrolling back into view,
                // or a different collab reel landing on it) reads these
                // cached fields instead of re-walking the view tree, same
                // "cacheViews()-once" principle as every other field on this
                // holder. The avatar-stack click listener is ALSO registered
                // here, exactly once — it reads holder.boundReel at click
                // time (set fresh on every bind, above) instead of
                // capturing `reel` in a brand-new lambda on every bind.
                holder.llCollabAuthorsHome = llCollabAuthorsHome;
                holder.collabStackHome = llCollabAuthorsHome.findViewById(R.id.collab_avatar_stack);
                holder.tvCollabNameHome = llCollabAuthorsHome.findViewById(R.id.tv_collab_author_name);
                holder.tvCollabFollowBtnHome = llCollabAuthorsHome.findViewById(R.id.tv_collab_follow_btn);
                holder.llCollabSongRowHome = llCollabAuthorsHome.findViewById(R.id.ll_collab_song_row);
                 // stub_reel_collab_row is shared with the dark Reels player,
                 // where its default white name is correct. Home places this
                 // row above the media, so use the feed theme color here.
                 if (holder.tvCollabNameHome != null) {
                     holder.tvCollabNameHome.setTextColor(
                         androidx.core.content.ContextCompat.getColor(
                             requireContext(), R.color.text_primary));
                 }
                // No bio/song ticker in the compact feed header — audio
                // already shows via tv_post_audio elsewhere on the card.
                if (holder.llCollabSongRowHome != null) holder.llCollabSongRowHome.setVisibility(View.GONE);
                llCollabAuthorsHome.setOnClickListener(null);

                if (holder.collabStackHome != null) {
                    holder.collabStackHome.setOnClickListener(v -> {
                        ReelModel currentReel = holder.boundReel;
                        if (!isAdded() || currentReel == null) return;
                        com.callx.app.social.CollaboratorsBottomSheet.newInstance(
                                currentReel.reelId, currentReel.uid, currentReel.ownerName, currentReel.ownerPhoto)
                            .show(getChildFragmentManager(), com.callx.app.social.CollaboratorsBottomSheet.TAG);
                    });
                }
                if (holder.tvCollabFollowBtnHome != null) {
                    final TextView tvCollabFollowBtnCached = holder.tvCollabFollowBtnHome;
                    tvCollabFollowBtnCached.setOnClickListener(x -> {
                        String uid = holder.boundMyUid;
                        ReelModel currentReel = holder.boundReel;
                        if (uid == null || currentReel == null || currentReel.uid == null || currentReel.uid.isEmpty()) return;
                        tvCollabFollowBtnCached.setVisibility(View.GONE);
                        FirebaseUtils.getReelFollowsRef(uid).child(currentReel.uid).setValue(true);
                        FirebaseUtils.getReelFollowersRef(currentReel.uid).child(uid).setValue(true);
                    });
                }
            }
        }

        if (isCollabStackDisplay && llCollabAuthorsHome != null) {
            // ── Multi-collaborator merged row: overlapping avatar stack +
            // "@owner and N others", opens CollaboratorsBottomSheet — exact
            // same views/ids/behavior as the Reels play screen.
            llCollabAuthorsHome.setVisibility(View.VISIBLE);
            if (holder.llPostOwnerRow != null) holder.llPostOwnerRow.setVisibility(View.GONE);
            if (holder.collabAvatarContainer != null) holder.collabAvatarContainer.setVisibility(View.GONE);

            com.callx.app.views.CollabAvatarStackView collabStackHome = holder.collabStackHome;
            TextView tvCollabNameHome = holder.tvCollabNameHome;
            TextView tvCollabFollowBtnHome = holder.tvCollabFollowBtnHome;

            java.util.List<String> avatarUrlsHome = new java.util.ArrayList<>();
            avatarUrlsHome.add(reel.ownerPhoto);
            int totalCountHome;
            if (!acceptedHome.isEmpty()) {
                for (com.callx.app.models.ReelModel.CollabCollaborator c : acceptedHome) avatarUrlsHome.add(c.avatarUrl);
                totalCountHome = acceptedHome.size();
            } else {
                avatarUrlsHome.add(reel.collabAvatarUrl);
                totalCountHome = 1;
            }

            if (collabStackHome != null) {
                final com.callx.app.views.CollabAvatarStackView collabStackFinal = collabStackHome;
                int stackCountHome = Math.min(avatarUrlsHome.size(), 3);
                // PERF (URL-skip): a rebind landing on this row with the
                // SAME reel/avatars (a live-count tick, a like-tap's
                // notifyItemChanged, or the row simply scrolling back into
                // view) used to re-run up to 3 fresh Glide asBitmap() loads
                // EVERY time even though none of the avatar URLs actually
                // changed — same lastAvatarUrl/lastThumbUrl URL-skip
                // principle this holder already uses elsewhere.
                boolean unchangedHome = holder.lastCollabStackCount == stackCountHome;
                if (unchangedHome) {
                    for (int i = 0; i < stackCountHome; i++) {
                        if (!java.util.Objects.equals(avatarUrlsHome.get(i), holder.lastCollabAvatarUrls[i])) {
                            unchangedHome = false;
                            break;
                        }
                    }
                }
                if (!unchangedHome) {
                    collabStackFinal.clearAvatars();
                    collabStackFinal.setAvatarCount(stackCountHome);
                    for (int i = 0; i < stackCountHome; i++) {
                        String url = avatarUrlsHome.get(i);
                        holder.lastCollabAvatarUrls[i] = url;
                        final int index = i;
                        if (url != null && !url.isEmpty() && isAdded()) {
                            int stackSizePx = AvatarUrlBuilder.tierPx(requireContext(), AvatarSizeTier.TINY);
                            Glide.with(requireContext())
                                .asBitmap()
                                .load(AvatarUrlBuilder.build(requireContext(), url, AvatarSizeTier.TINY))
                                .apply(new RequestOptions()
                                    .override(stackSizePx, stackSizePx)
                                    .format(DecodeFormat.PREFER_ARGB_8888)
                                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE))
                                .into(new CustomTarget<android.graphics.Bitmap>() {
                                    @Override
                                    public void onResourceReady(@androidx.annotation.NonNull android.graphics.Bitmap resource, Transition<? super android.graphics.Bitmap> transition) {
                                        collabStackFinal.setAvatarBitmap(index, resource);
                                    }
                                    @Override
                                    public void onLoadCleared(android.graphics.drawable.Drawable placeholder) { }
                                });
                        }
                    }
                    for (int i = stackCountHome; i < holder.lastCollabAvatarUrls.length; i++) holder.lastCollabAvatarUrls[i] = null;
                    holder.lastCollabStackCount = stackCountHome;
                }
            }

            if (tvCollabNameHome != null) {
                String ownerNameHome = reel.ownerName != null && !reel.ownerName.isEmpty() ? reel.ownerName : "user";
                String namePartHome = "@" + ownerNameHome;
                String othersPartHome = " and " + totalCountHome + (totalCountHome == 1 ? " other" : " others");
                String fullHome = namePartHome + othersPartHome;

                // PERF: a same-post rebind (live-count tick etc.) repeats the
                // exact same "@owner and N others" text — skip rebuilding
                // the SpannableString + both ClickableSpans (2 extra
                // allocations per bind) when the text hasn't actually
                // changed since this holder's last bind.
                if (!fullHome.equals(holder.lastCollabNameTextHome)) {
                    holder.lastCollabNameTextHome = fullHome;
                    android.text.SpannableString spannableHome = new android.text.SpannableString(fullHome);
                    spannableHome.setSpan(new android.text.style.ClickableSpan() {
                        @Override public void onClick(@androidx.annotation.NonNull View widget) {
                            ReelModel currentReel = holder.boundReel;
                            if (!isAdded() || getContext() == null || currentReel == null) return;
                            Intent i = new Intent(getContext(), UserReelsActivity.class);
                            i.putExtra(UserReelsActivity.EXTRA_UID,   currentReel.uid);
                            i.putExtra(UserReelsActivity.EXTRA_NAME,  currentReel.ownerName);
                            i.putExtra(UserReelsActivity.EXTRA_PHOTO, currentReel.ownerPhoto);
                            startActivity(i);
                        }
                        @Override public void updateDrawState(@androidx.annotation.NonNull android.text.TextPaint ds) {
                            ds.setUnderlineText(false);
                        }
                    }, 0, namePartHome.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spannableHome.setSpan(new android.text.style.ClickableSpan() {
                        @Override public void onClick(@androidx.annotation.NonNull View widget) {
                            ReelModel currentReel = holder.boundReel;
                            if (!isAdded() || currentReel == null) return;
                            com.callx.app.social.CollaboratorsBottomSheet.newInstance(
                                    currentReel.reelId, currentReel.uid, currentReel.ownerName, currentReel.ownerPhoto)
                                .show(getChildFragmentManager(), com.callx.app.social.CollaboratorsBottomSheet.TAG);
                        }
                        @Override public void updateDrawState(@androidx.annotation.NonNull android.text.TextPaint ds) {
                            ds.setUnderlineText(false);
                        }
                    }, namePartHome.length(), fullHome.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

                    tvCollabNameHome.setText(spannableHome);
                    tvCollabNameHome.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                    tvCollabNameHome.setHighlightColor(android.graphics.Color.TRANSPARENT);
                }
            }

            if (tvCollabFollowBtnHome != null) {
                boolean isFollowedHome = followedUids != null && reel.uid != null && followedUids.contains(reel.uid);
                tvCollabFollowBtnHome.setVisibility(isFollowedHome ? View.GONE : View.VISIBLE);
            }

            // ── Bottom-left collab icon on the video itself — second entry
            // point into the exact same CollaboratorsBottomSheet as the
            // "@owner and N others" row / avatar stack above. Only shown for
            // collab posts (same isCollabStackDisplay condition). Its click
            // listener is registered once, up top with the other one-time
            // listeners — nothing to wire here, just visibility.
            if (holder.btnCollabIcon != null) holder.btnCollabIcon.setVisibility(View.VISIBLE);

            // Legacy two-field dual-author branch never applies once the
            // new stack system is showing — nothing further to bind.
        } else {
        if (llCollabAuthorsHome != null) llCollabAuthorsHome.setVisibility(View.GONE);
        if (holder.btnCollabIcon != null) holder.btnCollabIcon.setVisibility(View.GONE);
        if (holder.llPostOwnerRow != null) holder.llPostOwnerRow.setVisibility(View.VISIBLE);

        // ── Legacy dual-author header (older collabInitiatorUid/
        // collabColaboratorUid data) — unchanged fallback behavior. ──────
        boolean isCollab = reel.collabInitiatorUid != null && !reel.collabInitiatorUid.isEmpty()
                        && reel.collabColaboratorUid != null && !reel.collabColaboratorUid.isEmpty();
        if (isCollab) {
            // Show collab header: "InitiatorName & CollaboratorName"
            // ★ Instagram-level PERF: skip the concat when this holder
            // already built this exact label from these exact two source
            // names on a previous bind — same rebind-skip principle as the
            // solo-author lastOwnerNameSrc/lastOwnerLabel cache below.
            String initName = reel.collabInitiatorName != null ? reel.collabInitiatorName : "User";
            String collabName = reel.collabCollaboratorName != null ? reel.collabCollaboratorName : "User";
            if (!initName.equals(holder.lastCollabLabelInitiator)
                    || !collabName.equals(holder.lastCollabLabelCollaborator)) {
                holder.lastCollabLabelInitiator = initName;
                holder.lastCollabLabelCollaborator = collabName;
                holder.lastCollabLabelText = initName + " \u2227 " + collabName;
            }
            tvOwner.setText(holder.lastCollabLabelText);
            // Collab header shows two people — badge next to the name refers
            // to the initiator (the account tvOwner's click target opens).
            com.callx.app.utils.VerifiedBadgeUtils.bindForUid(holder.ivPostVerified, reel.collabInitiatorUid);
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
                // ★ Instagram-level PERF: skip the Glide chain when this
                // holder is already showing this exact photo — same
                // rebind-skip already applied to thumb/avatar/audio-cover
                // above (lastThumbUrl/lastAvatarUrl/lastAudioCoverUrl).
                if (reel.collabCollaboratorPhoto != null && !reel.collabCollaboratorPhoto.isEmpty()) {
                    if (!reel.collabCollaboratorPhoto.equals(holder.lastCollabCollaboratorPhoto)) {
                        holder.lastCollabCollaboratorPhoto = reel.collabCollaboratorPhoto;
                        // ★ av2 is a 32dp circle — same AVATAR_DECODE_PX cap
                        // as the main 36dp card avatar below; this spot had
                        // no cap at all before (full-res profile photo
                        // decoded for a 32dp dot), the same oversized-bitmap
                        // heat/GC cost the other avatar overrides exist to
                        // avoid, and one more source of it during a fling
                        // through collab posts specifically.
                        Glide.with(requireContext()).load(reel.collabCollaboratorPhoto)
                            .apply(RequestOptions.circleCropTransform())
                            .apply(FEED_IMAGE_OPTS)
                            .override(AVATAR_DECODE_PX, AVATAR_DECODE_PX)
                            .placeholder(R.drawable.ic_person).into(av2);
                    }
                } else {
                    holder.lastCollabCollaboratorPhoto = null;
                }
                // Also load initiator photo into the main avatar
                if (reel.collabInitiatorPhoto != null && !reel.collabInitiatorPhoto.isEmpty()) {
                    if (!reel.collabInitiatorPhoto.equals(holder.lastCollabInitiatorPhoto)) {
                        holder.lastCollabInitiatorPhoto = reel.collabInitiatorPhoto;
                        // Same AVATAR_DECODE_PX cap the solo-owner avatar
                        // load below applies to this exact `avatar` view —
                        // the collab path was loading into the same 36dp
                        // circle with no size cap.
                        Glide.with(requireContext()).load(reel.collabInitiatorPhoto)
                            .apply(RequestOptions.circleCropTransform())
                            .apply(FEED_IMAGE_OPTS)
                            .override(AVATAR_DECODE_PX, AVATAR_DECODE_PX)
                            .placeholder(R.drawable.ic_person).into(avatar);
                    }
                } else {
                    holder.lastCollabInitiatorPhoto = null;
                }
            }
            // Collab click → open initiator profile. Listener itself is
            // registered once, outside this branch (see the unified
            // tvOwner click handler below) — nothing to wire here anymore.
        } else {
            // Reset collab-avatar URL-skip cache — this holder is now
            // showing a non-collab reel, so a LATER collab post landing
            // back on this same physical holder must treat both photos as
            // "new" again instead of wrongly comparing against whatever
            // collab post it last showed.
            holder.lastCollabCollaboratorPhoto = null;
            holder.lastCollabInitiatorPhoto = null;
            // Same reset for the legacy dual-author label cache above —
            // a later legacy-collab post on this holder must rebuild the
            // label instead of comparing against a stale pair.
            holder.lastCollabLabelInitiator = null;
            holder.lastCollabLabelCollaborator = null;
            holder.lastCollabLabelText = null;
            // PERF: "@" + name used to be re-concatenated (new String) on
            // EVERY bind, even when the exact same reel rebinds onto this
            // same holder (e.g. a like-tap's notifyItemChanged). Now cached
            // per-holder and only recomputed when the owner name actually
            // changes.
            String ownerNameSrc = reel.ownerName;
            if (!java.util.Objects.equals(ownerNameSrc, holder.lastOwnerNameSrc)) {
                holder.lastOwnerNameSrc = ownerNameSrc;
                holder.lastOwnerLabel = ownerNameSrc != null ? "@" + ownerNameSrc : "@user";
            }
            tvOwner.setText(holder.lastOwnerLabel);
            // Listener registered once, outside this branch — see the
            // unified tvOwner click handler below.
            com.callx.app.utils.VerifiedBadgeUtils.bindForUid(holder.ivPostVerified, reel.uid);
        }
        } // end legacy/solo fallback (else branch of isCollabStackDisplay)

        if (tvTime != null) {
            // PERF: formatAgo() used to recompute + re-allocate its result
            // String on EVERY bind, including a same-second rebind of the
            // exact same reel onto this holder (tap → notifyItemChanged).
            // It's genuinely time-varying so it can't be cached forever,
            // but a rebind of the same timestamp within the same ~1s
            // window it would've produced the same output anyway — reuse
            // that instead of recomputing.
            long now = System.currentTimeMillis();
            if (reel.timestamp != holder.lastAgoTs
                    || holder.lastAgoComputedAtMs < 0
                    || (now - holder.lastAgoComputedAtMs) >= 1000) {
                holder.lastAgoTs = reel.timestamp;
                holder.lastAgoStr = formatAgo(reel.timestamp);
                holder.lastAgoComputedAtMs = now;
            }
            tvTime.setText(holder.lastAgoStr);
        }

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
        View btnReadMore = holder.btnReadMore;

        // Apply hashtag spans
        // ★ Instagram-level PERF: buildCaptionSpannable() re-runs the hashtag
        // regex AND allocates a fresh ClickableSpan object per hashtag on
        // EVERY bind — including the common "recompute for nothing" cases
        // where the same reel rebinds to the same (or a different) holder
        // with an unchanged caption: a scroll-back-and-forth over an already
        // rendered card, or a plain notifyItemChanged(position) firing from
        // a like/follow tap. likes/comments/repost text already skip this
        // via ReelUiStateCache's precompute; caption spans didn't. Cache the
        // built SpannableString per reelId (captions are immutable once a
        // reel is published) so a rebind is a map lookup + field read
        // instead of a regex pass + N span allocations.
        android.text.SpannableString captionSpannable = reel.reelId != null
            ? captionSpannableCache.get(reel.reelId) : null;
        if (captionSpannable == null) {
            captionSpannable = buildCaptionSpannable(captionText);
            if (reel.reelId != null) captionSpannableCache.put(reel.reelId, captionSpannable);
        }
        tvCaption.setText(captionSpannable);
        if (captionSpannable.length() > 0) {
            tvCaption.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
        // Truncate long captions
        if (captionText.length() > 120) {
            tvCaption.setMaxLines(CAPTION_MAX_LINES);
            tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // Reset to collapsed for whichever reel is landing on this
            // holder now — a fresh bind should always start collapsed,
            // even though the listener below is only registered once.
            holder.captionExpanded = false;
            if (btnReadMore != null) {
                btnReadMore.setVisibility(View.VISIBLE);
                ((TextView) btnReadMore).setText("more");
                // ★ Instagram-level PERF: this used to be a fresh
                // OnClickListener lambda allocated on EVERY bind. Registered
                // ONCE per physical holder now (see readMoreListenerBound) —
                // reads/toggles holder.captionExpanded and re-reads
                // holder.tvCaption/holder.btnReadMore at click time, so a
                // rebind is just the state reset above instead of a new
                // listener allocation.
                if (!holder.readMoreListenerBound) {
                    btnReadMore.setOnClickListener(rx -> {
                        TextView capView  = holder.tvCaption;
                        TextView moreView = (TextView) holder.btnReadMore;
                        if (capView == null || moreView == null) return;
                        holder.captionExpanded = !holder.captionExpanded;
                        if (holder.captionExpanded) {
                            capView.setMaxLines(Integer.MAX_VALUE);
                            capView.setEllipsize(null);
                            moreView.setText("less");
                        } else {
                            capView.setMaxLines(CAPTION_MAX_LINES);
                            capView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            moreView.setText("more");
                        }
                    });
                    holder.readMoreListenerBound = true;
                }
            }
        } else {
            if (btnReadMore != null) btnReadMore.setVisibility(View.GONE);
        }

        if (precomputedUi != null) {
            tvLikes.setText(precomputedUi.likesText);
            tvComments.setText(precomputedUi.commentsText);
            tvReposts.setText(precomputedUi.repostText);
        } else {
            // ★ Instagram-level PERF: skip formatCount()+setText() when this
            // holder's last fallback bind already showed these exact counts
            // for this same reel — see field doc above.
            if (reel.likesCount != holder.lastFallbackLikesCount) {
                holder.lastFallbackLikesCount = reel.likesCount;
                tvLikes.setText(formatCount(reel.likesCount));
            }
            if (reel.commentsCount != holder.lastFallbackCommentsCount) {
                holder.lastFallbackCommentsCount = reel.commentsCount;
                tvComments.setText(formatCount(reel.commentsCount));
            }
            if (reel.repostCount != holder.lastFallbackRepostCount) {
                holder.lastFallbackRepostCount = reel.repostCount;
                tvReposts.setText(formatCount(reel.repostCount));
            }
        }

        // ── Liked state (declared early: needed by slideshow double-tap-to-like
        // below). Now lives on the holder (holder.boundIsLiked) instead of a
        // per-bind boolean[] closure, since the like BUTTON's own listener is
        // registered once per holder and needs a persistent place to read/flip
        // this from — see actionBarListenersBound above. ──
        final String reelId   = reel.reelId;
        holder.boundIsLiked = reel.reelId != null && likedIds.contains(reel.reelId);
        if (btnLike != null) {
            btnLike.setImageResource(holder.boundIsLiked
                ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
            setLikeButtonTint(btnLike, holder.boundIsLiked);
        }

        // ── Photo slideshow support ─────────────────────────────────────────
        if (reel.isPhotoSlideshow() && reel.photoUrls != null && !reel.photoUrls.isEmpty()) {
            // Hide the video player frame; show the (reused) photo-slideshow
            // ViewPager2 instead.
            if (pvFeed != null)     pvFeed.setVisibility(View.GONE);
            if (ivThumb != null)    ivThumb.setVisibility(View.GONE);

            // ★ Instagram-level PERF: photoPager / its adapter / the dots
            // row are now created ONCE per physical holder (fields live on
            // PostRowHolder, see its doc) and reused for every photo post
            // that ever lands on this recycled row — no more
            // new ViewPager2(...) / new RecyclerView.Adapter() /
            // new LinearLayout() (each dragging its own measure+layout pass)
            // on every single bind. Same "cache the view, refresh the data"
            // rule cacheViews() already applies to every other view here.
            final List<String> photoList = reel.photoUrls;
            boolean firstTimeOnThisHolder = holder.photoPager == null;
            if (firstTimeOnThisHolder) {
                holder.photoPager = new ViewPager2(requireContext());
                holder.photoPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
                FrameLayout.LayoutParams pagerLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT);
                holder.photoPager.setLayoutParams(pagerLp);

                // Adapter reads holder.photoPagerData directly (not a
                // per-bind captured list), so this ONE adapter instance
                // stays correct for every future reel this holder shows —
                // a rebind just mutates that list + notifyDataSetChanged().
                holder.photoPagerAdapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    @NonNull @Override
                    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int vt) {
                        ImageView iv = new ImageView(parent.getContext());
                        iv.setLayoutParams(new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                         iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        return new RecyclerView.ViewHolder(iv) {};
                    }
                    @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                        Glide.with(requireContext())
                            .load(holder.photoPagerData.get(pos))
                             .fitCenter()
                            .placeholder(R.drawable.ic_reels)
                            .into((ImageView) h.itemView);
                    }
                    @Override public int getItemCount() { return holder.photoPagerData.size(); }
                };
                holder.photoPager.setAdapter(holder.photoPagerAdapter);

                holder.photoDots = new LinearLayout(requireContext());
                holder.photoDots.setOrientation(LinearLayout.HORIZONTAL);
                holder.photoDots.setGravity(android.view.Gravity.CENTER);
                // ✅ Now lives in frame_photo_dots_row BELOW the media (Instagram-
                // style) instead of overlaid on the bottom of the video/photo
                // itself — plain centered wrap_content, no bottom-edge gravity
                // or margin needed anymore.
                FrameLayout.LayoutParams dotsLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
                dotsLp.gravity = android.view.Gravity.CENTER;
                holder.photoDots.setLayoutParams(dotsLp);
                if (holder.framePhotoDotsRow != null) {
                    holder.framePhotoDotsRow.addView(holder.photoDots);
                }

                // Registered ONCE — reads holder.photoDotDrawables at
                // fire-time (whatever the CURRENT reel's dots are), so it
                // stays correct across every future rebind without ever
                // needing to be re-registered.
                holder.photoPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                    @Override public void onPageSelected(int position) {
                        if (holder.photoDotDrawables == null) return;
                        for (int di = 0; di < holder.photoDotDrawables.length; di++) {
                            // Mutate the existing Drawable instead of
                            // allocating a fresh GradientDrawable per dot on
                            // every page change.
                            holder.photoDotDrawables[di].setColor(
                                di == position ? photoDotActiveColor() : photoDotInactiveColor());
                        }
                    }
                });

                // Double-tap to like on slideshow — ★ Instagram-level PERF:
                // this used to allocate a brand-new GestureDetector + a
                // brand-new OnTouchListener object on EVERY single bind
                // (comment said "kept per-bind" but nothing was actually
                // cached). Registered ONCE per physical holder now, same
                // "cache the object, refresh the data" rule as everything
                // else in this block — the listener reads
                // holder.boundReel/boundMyUid/boundIsLiked/photoPagerData
                // at touch time, so it stays correct for whichever reel is
                // CURRENTLY bound to this holder without ever needing to be
                // re-created.
                holder.photoPager.setOnTouchListener(new View.OnTouchListener() {
                    private float downX = 0f, downY = 0f;
                    private final GestureDetector gd = new GestureDetector(requireContext(),
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override public boolean onDoubleTap(MotionEvent e) {
                                String uid = holder.boundMyUid;
                                String rid = holder.boundReel != null ? holder.boundReel.reelId : null;
                                if (uid == null || rid == null) return true;
                                if (!holder.boundIsLiked) {
                                    holder.boundIsLiked = true;
                                    holder.btnLike.setImageResource(R.drawable.ic_heart_filled);
                                    setLikeButtonTint(holder.btnLike, true);
                                    FirebaseUtils.writeReelLike(rid, uid); // writes reelLikes timestamp + reelLikeMeta denormalized snapshot together
                                    FirebaseUtils.getReelLikedByUserRef(uid).child(rid)
                                        .setValue(System.currentTimeMillis());
                                    try {
                                        int cur = Integer.parseInt(holder.tvLikes.getText().toString());
                                        holder.tvLikes.setText(formatCount(cur + 1));
                                    } catch (Exception ignored) {}
                                }
                                if (holder.frameVideo != null) showHeartAnimation(holder.frameVideo);
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
                                if (holder.photoPagerData.size() > 1) v.getParent().requestDisallowInterceptTouchEvent(true);
                                break;
                            case MotionEvent.ACTION_MOVE:
                                float dx = Math.abs(event.getRawX() - downX);
                                float dy = Math.abs(event.getRawY() - downY);
                                if (holder.photoPagerData.size() > 1 && dx >= dy) {
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
            }

            // Swap the DATA only — same reused ViewPager2 + adapter instance.
            holder.photoPagerData.clear();
            holder.photoPagerData.addAll(photoList);
            holder.photoPagerAdapter.notifyDataSetChanged();
            holder.photoPager.setCurrentItem(0, false);
            holder.photoPager.setVisibility(View.VISIBLE);

            // Dot Views themselves are only rebuilt when the photo COUNT
            // differs from whatever this holder last showed (rare — most
            // scrolling re-lands on the same 2-3 photo counts). Otherwise
            // every dot's already-inflated GradientDrawable is reused,
            // just reset to "page 0 active".
            if (holder.photoDotCount != photoList.size()) {
                holder.photoDots.removeAllViews();
                holder.photoDotDrawables = new android.graphics.drawable.GradientDrawable[photoList.size()];
                for (int di = 0; di < photoList.size(); di++) {
                    View dot = new View(requireContext());
                    int dotSz = dpToPx(6);
                    LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSz, dotSz);
                    dotLp.setMargins(dpToPx(3), 0, dpToPx(3), 0);
                    dot.setLayoutParams(dotLp);
                    android.graphics.drawable.GradientDrawable dotBg =
                        new android.graphics.drawable.GradientDrawable();
                    dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    dotBg.setColor(di == 0 ? photoDotActiveColor() : photoDotInactiveColor());
                    dot.setBackground(dotBg);
                    holder.photoDots.addView(dot);
                    holder.photoDotDrawables[di] = dotBg;
                }
                holder.photoDotCount = photoList.size();
            } else {
                for (int di = 0; di < holder.photoDotDrawables.length; di++) {
                    holder.photoDotDrawables[di].setColor(di == 0 ? photoDotActiveColor() : photoDotInactiveColor());
                }
            }
            holder.photoDots.setVisibility(View.VISIBLE);
            if (holder.framePhotoDotsRow != null) holder.framePhotoDotsRow.setVisibility(View.VISIBLE);

            if (firstTimeOnThisHolder && frameVideo != null) {
                 // Same z-order fix as before — insert immediately above the
                 // blurred backdrop and below the PlayerView/thumbnail so the
                 // header overlay (defined after them in XML) stays on top.
                 // Only ever added ONCE per
                // holder now; later binds just toggle visibility (see
                // above, and the video-branch below).
                // ✅ photoDots is no longer added here — it now lives in
                // frame_photo_dots_row BELOW the video block (added at
                // creation time above), not overlaid on top of the media.
                 frameVideo.addView(holder.photoPager, 1);
            }
        } else {
            // This (recycled) holder previously showed a photo slideshow —
            // hide its cached pager/dots rather than tearing them down, so
            // they're ready to reuse instantly if this row later recycles
            // back into a photo post.
            if (holder.photoPager != null) holder.photoPager.setVisibility(View.GONE);
            if (holder.photoDots  != null) holder.photoDots.setVisibility(View.GONE);
            if (holder.framePhotoDotsRow != null) holder.framePhotoDotsRow.setVisibility(View.GONE);

            // ── Video frame gestures: double-tap like, tap play/pause, hold 2x ──
            // All three live in ONE touch listener because a View has only one
            // OnTouchListener — a separate listener for the new gestures would
            // silently replace double-tap-to-like.
            //
            // ★ Instagram-level PERF: this used to allocate a brand-new
            // GestureDetector + a brand-new OnTouchListener lambda on EVERY
            // single bind (comment said "kept per-bind" but nothing was
            // actually cached — same issue as the photo-slideshow listener
            // above). Registered ONCE per physical holder now, gated by
            // holder.frameVideoGestureBound; the listener reads
            // holder.boundReel/boundMyUid/boundCardIndex/boundIsLiked at
            // touch time so it stays correct for whichever reel is
            // CURRENTLY bound to this holder.
            if (frameVideo != null && !holder.frameVideoGestureBound) {
                GestureDetector dtGesture = new GestureDetector(requireContext(),
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDoubleTap(MotionEvent e) {
                            String uid = holder.boundMyUid;
                            String rid = holder.boundReel != null ? holder.boundReel.reelId : null;
                            if (uid == null || rid == null) return true;
                            if (!holder.boundIsLiked) {
                                holder.boundIsLiked = true;
                                if (holder.btnLike != null) {
                                    holder.btnLike.setImageResource(R.drawable.ic_heart_filled);
                                    setLikeButtonTint(holder.btnLike, true);
                                }
                                FirebaseUtils.writeReelLike(rid, uid); // writes reelLikes timestamp + reelLikeMeta denormalized snapshot together
                                FirebaseUtils.getReelLikedByUserRef(uid).child(rid)
                                    .setValue(System.currentTimeMillis());
                                try {
                                    int cur = Integer.parseInt(holder.tvLikes.getText().toString());
                                    holder.tvLikes.setText(formatCount(cur + 1));
                                } catch (Exception ignored) {}
                            }
                            if (holder.frameVideo != null) showHeartAnimation(holder.frameVideo);
                            return true;
                        }
                        /** Single tap (not part of a double-tap) opens this reel in the
                         *  fullscreen immersive player — same behaviour as tapping the
                         *  thumbnail (ivThumb) before playback starts. Inline play/pause
                         *  is no longer reachable via a plain tap; the card still
                         *  autoplays on its own per the feed's autoplay setting. */
                        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                            // ✅ Instagram-style resume: carry over exactly how far this
                            // card's inline preview had already played, so the fullscreen
                            // player continues from there instead of restarting at 0.
                            ReelModel curReel = holder.boundReel;
                            if (curReel == null) return true;
                            openReelWithContext(currentFeedPosts, curReel.reelId, curReel.ownerName,
                                capturePreviewPositionMs(holder.boundCardIndex));
                            return true;
                        }
                        /** Press-and-hold = temporary 2x fast-forward. */
                        @Override public void onLongPress(MotionEvent e) {
                            beginSpeedBoost(holder.boundCardIndex);
                        }
                    });
                dtGesture.setIsLongpressEnabled(true);
                holder.frameVideo.setOnTouchListener((v, ev) -> {
                    boolean handled = dtGesture.onTouchEvent(ev);
                    int action = ev.getActionMasked();
                    if (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL) {
                        // Releasing the finger always ends a boost, even when the
                        // detector itself consumed neither the up nor the cancel.
                        endSpeedBoost(holder.boundCardIndex);
                    }
                    return handled || action == MotionEvent.ACTION_DOWN;
                });
                holder.frameVideoGestureBound = true;
            }
            // Play overlay only shows when autoplay is off — tapping it now also
            // jumps straight to the fullscreen player (consistent with the video
            // area itself) instead of starting inline playback.
            if (playOverlay != null) {
                playOverlay.setOnClickListener(x -> openReelWithContext(currentFeedPosts, reelId, reel.ownerName,
                    capturePreviewPositionMs(cardIndex)));
            }
        }

        // ── Saved state — lives on the holder now (holder.boundIsSaved),
        // same reasoning as boundIsLiked above. ──
        holder.boundIsSaved = reel.reelId != null && savedIds.contains(reel.reelId);
        if (btnSave != null) {
            btnSave.setImageResource(holder.boundIsSaved
                ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        }

        String feedThumbUrl = reel.effectiveThumbUrl();
        boolean hasFeedThumb = feedThumbUrl != null && !feedThumbUrl.isEmpty();
        boolean needsBackdrop = feedNeedsBackdrop(reel);
        if (hasFeedThumb) {
            // Keep the backdrop GONE for square/ordinary landscape media:
            // their source aspect already matches the bounded frame, so a
            // blurred second image would only add decode and draw work.
            if (holder.ivBackdrop != null) {
                if (!needsBackdrop) {
                    if (holder.lastBackdropNeeded) {
                        Glide.with(requireContext()).clear(holder.ivBackdrop);
                    }
                    holder.lastBackdropNeeded = false;
                    holder.lastBackdropUrl = feedThumbUrl;
                    holder.ivBackdrop.setVisibility(View.GONE);
                } else {
                    holder.ivBackdrop.setVisibility(View.VISIBLE);
                    if (!holder.lastBackdropNeeded
                            || !feedThumbUrl.equals(holder.lastBackdropUrl)) {
                        holder.lastBackdropNeeded = true;
                        holder.lastBackdropUrl = feedThumbUrl;
                        Glide.with(requireContext()).load(feedThumbUrl)
                            .apply(FEED_BACKDROP_OPTS)
                            .override(BACKDROP_DECODE_W, BACKDROP_DECODE_H)
                            .priority(Priority.LOW)
                            .into(holder.ivBackdrop);
                    }
                }
            }
            // Decode into the tallest supported Home-feed canvas. FIT keeps the
            // source's complete aspect ratio; unlike the old centerCrop chain,
            // no photo or video pixels are thrown away.
            //
            // PERF: Glide already dedupes the actual decode/network work
            // when the same URL is requested into a target it's already
            // showing, but the RequestBuilder chain itself (load/apply/
            // override/centerCrop/placeholder) was still a fresh object
            // allocation on EVERY bind — including a same-reel rebind onto
            // this same holder. Skip the whole chain when this holder is
            // already showing this exact thumb URL.
            if (!feedThumbUrl.equals(holder.lastThumbUrl)) {
                holder.lastThumbUrl = feedThumbUrl;

                Glide.with(requireContext()).load(feedThumbUrl)
                    .apply(FEED_MEDIA_OPTS)
                    .override(THUMB_DECODE_W, THUMB_DECODE_H)
                    .placeholder(R.drawable.ic_reels)
                    .priority(Priority.HIGH)
                    .listener(holder.thumbLoadListener)
                    .into(ivThumb);
            }
        } else {
            holder.lastThumbUrl = null;
            if (holder.ivBackdrop != null) {
                if (holder.lastBackdropNeeded) {
                    Glide.with(requireContext()).clear(holder.ivBackdrop);
                }
                holder.lastBackdropNeeded = false;
                holder.lastBackdropUrl = null;
                holder.ivBackdrop.setVisibility(View.GONE);
                holder.ivBackdrop.setImageDrawable(null);
            }
            if (ivThumb != null) ivThumb.setImageResource(R.drawable.ic_reels);
        }
        if (reel.ownerPhoto != null && !reel.ownerPhoto.isEmpty()) {
            // The avatar is 36dp; without an override Glide decoded the
            // full-resolution profile photo for it. Same rebind-skip as
            // the thumb above.
            if (!reel.ownerPhoto.equals(holder.lastAvatarUrl)) {
                holder.lastAvatarUrl = reel.ownerPhoto;
                Glide.with(requireContext()).load(reel.ownerPhoto)
                    .apply(RequestOptions.circleCropTransform())
                    .apply(FEED_IMAGE_OPTS)
                    .override(AVATAR_DECODE_PX, AVATAR_DECODE_PX)
                    .placeholder(R.drawable.ic_person).into(avatar);
            }
        }

        final String ownerUid = reel.uid;

        // ★ Instagram-level PERF (action-bar pass): every listener below —
        // thumbnail tap, avatar tap, owner-name tap, like, comment (icon +
        // count), repost, save, shares-count, send/share, and the "⋮" more
        // menu — used to be a fresh lambda allocated on EVERY single bind,
        // each one capturing that bind's reel/reelId/ownerUid/myUid/tvSends
        // etc. via closure. Registered ONCE per physical holder now (see
        // actionBarListenersBound on PostRowHolder); every listener below
        // reads holder.boundReel / holder.boundMyUid — kept fresh by the
        // cheap field writes in addFeedPostCard() — instead of a captured
        // local, so a rebind is just those field writes, not N new
        // allocations. Anything that's genuinely per-bind data (setText,
        // visibility, image resource) still runs on every bind, unguarded,
        // below this block exactly as before.
        if (!holder.actionBarListenersBound) {
            final TextView    tvLikesRef    = holder.tvLikes;
            final TextView    tvCommentsRef = holder.tvComments;
            final TextView    tvRepostsRef  = holder.tvReposts;
            final ImageButton btnLikeRef    = holder.btnLike;
            final ImageButton btnSaveRef    = holder.btnSave;
            final View        btnSendRef    = holder.btnSend;
            final View        btnMoreRef    = holder.btnMore;
            final TextView    tvSendsRef    = holder.tvSends;
            final CircleImageView avatarRef = holder.avatar;

            // Tap thumbnail → open this specific reel in the player
            holder.ivThumb.setOnClickListener(x -> {
                ReelModel r = holder.boundReel;
                if (r == null) return;
                openReelWithContext(currentFeedPosts, r.reelId, r.ownerName);
            });

            // Avatar tap → open user's reel profile
            avatarRef.setOnClickListener(x -> {
                ReelModel r = holder.boundReel;
                if (!isAdded() || getContext() == null || r == null) return;
                Intent i = new Intent(getContext(), UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID,   r.uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME,  r.ownerName);
                i.putExtra(UserReelsActivity.EXTRA_PHOTO, r.ownerPhoto);
                startActivity(i);
            });

            // Owner-name tap: opens the collab initiator's profile for a
            // legacy dual-author post, otherwise defers to the avatar tap —
            // same two behaviors the per-bind version had, just resolved
            // fresh off holder.boundReel at click time instead of being
            // picked once, up front, at bind time.
            if (holder.tvOwner != null) {
                holder.tvOwner.setOnClickListener(x -> {
                    ReelModel r = holder.boundReel;
                    if (!isAdded() || getContext() == null || r == null) return;
                    boolean isLegacyCollab = r.collabInitiatorUid != null && !r.collabInitiatorUid.isEmpty()
                        && r.collabColaboratorUid != null && !r.collabColaboratorUid.isEmpty();
                    if (isLegacyCollab) {
                        Intent i = new Intent(getContext(), UserReelsActivity.class);
                        i.putExtra(UserReelsActivity.EXTRA_UID,   r.collabInitiatorUid);
                        i.putExtra(UserReelsActivity.EXTRA_NAME,  r.collabInitiatorName);
                        i.putExtra(UserReelsActivity.EXTRA_PHOTO, r.collabInitiatorPhoto);
                        startActivity(i);
                    } else {
                        avatarRef.performClick();
                    }
                });
            }

            // ── Like button ──
            if (btnLikeRef != null) {
                btnLikeRef.setOnClickListener(x -> {
                    ReelModel r = holder.boundReel;
                    String uid = holder.boundMyUid;
                    if (uid == null || r == null || r.reelId == null) return;
                    String rid = r.reelId;
                    holder.boundIsLiked = !holder.boundIsLiked;
                    if (holder.boundIsLiked) {
                        btnLikeRef.setImageResource(R.drawable.ic_heart_filled);
                        setLikeButtonTint(btnLikeRef, true);
                        FirebaseUtils.writeReelLike(rid, uid); // writes reelLikes timestamp + reelLikeMeta denormalized snapshot together
                        FirebaseUtils.getReelLikedByUserRef(uid).child(rid)
                            .setValue(System.currentTimeMillis());
                        // Optimistic UI count update
                        try {
                            int cur = Integer.parseInt(tvLikesRef.getText().toString()
                                .replace("K", "000").replace("M", "000000"));
                            tvLikesRef.setText(formatCount(cur + 1));
                        } catch (Exception ignored) {}
                    } else {
                        btnLikeRef.setImageResource(R.drawable.ic_heart);
                        setLikeButtonTint(btnLikeRef, false);
                        FirebaseUtils.removeReelLike(rid, uid);
                        FirebaseUtils.getReelLikedByUserRef(uid).child(rid).removeValue();
                    }
                });
            }

            // ── Comment button → open ReelCommentActivity ──
            if (holder.btnComment != null) {
                holder.btnComment.setOnClickListener(x -> openReelComments(holder));
            }
            // ── Comment COUNT tap → same destination as btnComment. Reuses
            // the immersive player's pattern (ReelSocialController's
            // tvCommentsCount.setOnClickListener → openCommentsSheet()) so
            // tapping the number, not just the icon, opens comments here too.
            if (tvCommentsRef != null) {
                tvCommentsRef.setOnClickListener(x -> openReelComments(holder));
            }

            // ── Repost button — show options (Repost / Quote Repost / Undo) ──
            if (holder.btnRepost != null) {
                holder.btnRepost.setOnClickListener(x -> {
                    ReelModel r = holder.boundReel;
                    String uid = holder.boundMyUid;
                    if (uid == null || r == null || r.reelId == null || !isAdded() || getContext() == null) return;
                    final String rid = r.reelId;
                    final String ownerUidNow = r.uid;
                    if (uid.equals(ownerUidNow)) {
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
                                performRepost(rid, ownerUidNow, r, uid, tvRepostsRef);
                            } else {
                                // Quote Repost — open share sheet pre-filled as quote
                                try {
                                    ReelShareSheetFragment sheet = ReelShareSheetFragment.newInstance(
                                        rid,
                                        r.videoUrl    != null ? r.videoUrl    : (r.video480 != null ? r.video480 : ""),
                                        r.thumbUrl    != null ? r.thumbUrl    : "",
                                        r.caption     != null ? r.caption     : "",
                                        ownerUidNow   != null ? ownerUidNow   : "",
                                        r.ownerName   != null ? r.ownerName   : "",
                                        r.ownerPhoto  != null ? r.ownerPhoto  : "",
                                        true
                                    );
                                    sheet.show(getChildFragmentManager(), "quote_sheet");
                                } catch (Exception e) {
                                    // Fallback: system share
                                    Intent share = new Intent(Intent.ACTION_SEND);
                                    share.setType("text/plain");
                                    String quote = "\"" + (r.caption != null ? r.caption : "Check this out") + "\" — @" + r.ownerName + " https://callx.app/reel/" + rid;
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
            if (btnSaveRef != null) {
                btnSaveRef.setOnClickListener(x -> {
                    ReelModel r = holder.boundReel;
                    String uid = holder.boundMyUid;
                    if (uid == null || r == null || r.reelId == null) return;
                    String rid = r.reelId;
                    holder.boundIsSaved = !holder.boundIsSaved;
                    if (holder.boundIsSaved) {
                        btnSaveRef.setImageResource(R.drawable.ic_bookmark_filled);
                        FirebaseUtils.getReelSavesRef(uid).child(rid).setValue(true);
                        FirebaseUtils.getReelSavesIndexRef(rid).child(uid).setValue(true);
                        Toast.makeText(requireContext(), "Saved!", Toast.LENGTH_SHORT).show();
                    } else {
                        btnSaveRef.setImageResource(R.drawable.ic_bookmark);
                        FirebaseUtils.getReelSavesRef(uid).child(rid).removeValue();
                        FirebaseUtils.getReelSavesIndexRef(rid).child(uid).removeValue();
                    }
                });
            }

            // ── Shares count tap → shares bottom sheet, same
            // ReelSharesBottomSheet the immersive player opens via
            // ReelShareController.openSharesSheet() / same pattern as the
            // tvComments count tap above. ──
            if (tvSendsRef != null) {
                tvSendsRef.setOnClickListener(x -> {
                    ReelModel r = holder.boundReel;
                    if (r == null || r.reelId == null) return;
                    ReelSharesBottomSheet sheet = ReelSharesBottomSheet.newInstance(
                        r.reelId, r.sharesCount, r.repostCount);
                    sheet.show(getChildFragmentManager(), ReelSharesBottomSheet.TAG);
                });
            }

            // ── Send / Share button — open ReelShareSheetFragment ──
            if (btnSendRef != null) {
                btnSendRef.setOnClickListener(x -> {
                    ReelModel r = holder.boundReel;
                    if (!isAdded() || getContext() == null || r == null || r.reelId == null) return;
                    try {
                        ReelShareSheetFragment sheet = ReelShareSheetFragment.newInstance(
                            r.reelId,
                            r.videoUrl  != null ? r.videoUrl  : (r.video480 != null ? r.video480 : ""),
                            r.thumbUrl  != null ? r.thumbUrl  : "",
                            r.caption   != null ? r.caption   : "",
                            r.uid       != null ? r.uid       : "",
                            r.ownerName != null ? r.ownerName : "",
                            r.ownerPhoto != null ? r.ownerPhoto : "",
                            true
                        );
                        sheet.show(getChildFragmentManager(), "share_sheet");
                    } catch (Exception e) {
                        // Fallback to system share if bottom sheet fails
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_TEXT,
                            "Check out this reel on CallX! @" + r.ownerName);
                        startActivity(Intent.createChooser(share, "Share reel"));
                    }
                });
            }

            // ── More options (⋮) button ──
            if (btnMoreRef != null) {
                btnMoreRef.setOnClickListener(x -> {
                    ReelModel r = holder.boundReel;
                    String uid = holder.boundMyUid;
                    if (!isAdded() || getContext() == null || r == null) return;
                    final String rid = r.reelId;
                    final String ownerUidNow = r.uid;
                    PopupMenu popup = new PopupMenu(requireContext(), btnMoreRef);
                    popup.getMenu().add(0, 1, 0, "Not interested");
                    popup.getMenu().add(0, 2, 0, "Report");
                    popup.getMenu().add(0, 3, 0, "Copy link");
                    if (uid != null && !uid.equals(ownerUidNow)) {
                        popup.getMenu().add(0, 4, 0, "Mute @" + (r.ownerName != null ? r.ownerName : "user"));
                        popup.getMenu().add(0, 5, 0, "Block");
                    }
                    popup.getMenu().add(0, 6, 0, "Open original");
                    // Reuses the same ReelOfflineManager the Reels swipe feed's
                    // "more" sheet calls (ReelPlayerController.saveReelOffline) —
                    // same singleton cache, so a reel saved from either tab is
                    // available offline in both.
                    popup.getMenu().add(0, 7, 0, "Save for offline");
                    popup.getMenu().add(0, 8, 0, "Share to Story");
                    popup.getMenu().add(0, 9, 0, "Share to Close Friends Story");
                    if ((r.videoUrl != null && !r.videoUrl.isEmpty())
                            || (r.video480 != null && !r.video480.isEmpty())) {
                        popup.getMenu().add(0, 10, 0, "Remix this reel");
                    }
                    popup.setOnMenuItemClickListener(item -> {
                        switch (item.getItemId()) {
                            case 1: // Not interested — remove from feed optimistically
                                removeFeedRowByReelId(rid);
                                if (uid != null && rid != null) {
                                    FirebaseUtils.db().getReference("userNotInterested")
                                        .child(uid).child(rid).setValue(true);
                                }
                                return true;
                            case 2: // Report
                                if (uid == null || rid == null) return true;
                                String[] reportReasons = {"Spam", "Inappropriate content",
                                    "Harassment", "Misinformation", "Kuch aur"};
                                AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                    .setTitle("Report this reel")
                                    .setItems(reportReasons, (d, which) -> {
                                        String reportKey = FirebaseUtils.db()
                                            .getReference("reelReports").child(rid).push().getKey();
                                        if (reportKey != null) {
                                            Map<String, Object> report = new HashMap<>();
                                            report.put("reporterUid", uid);
                                            report.put("reelId",      rid);
                                            report.put("ownerUid",    ownerUidNow != null ? ownerUidNow : "");
                                            report.put("reason",      reportReasons[which]);
                                            report.put("timestamp",   System.currentTimeMillis());
                                            FirebaseUtils.db().getReference("reelReports")
                                                .child(rid).child(reportKey).setValue(report);
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
                                    String link = "https://callx.app/reel/" + rid;
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Reel link", link));
                                    Toast.makeText(requireContext(),
                                        "Link copied!", Toast.LENGTH_SHORT).show();
                                }
                                return true;
                            case 4: // Mute
                                if (uid != null && ownerUidNow != null) {
                                    FirebaseUtils.db().getReference("muted")
                                        .child(uid).child(ownerUidNow).setValue(true);
                                    Toast.makeText(requireContext(),
                                        "Muted @" + r.ownerName, Toast.LENGTH_SHORT).show();
                                }
                                return true;
                            case 5: // Block
                                if (uid != null && ownerUidNow != null) {
                                    AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                                        .setTitle("Block @" + r.ownerName + "?")
                                        .setMessage("They won't be able to find your profile or reels.")
                                        .setPositiveButton("Block", (d, w) -> {
                                            FirebaseUtils.getBlocksRef(uid).child(ownerUidNow).setValue(true);
                                            removeFeedRowByReelId(rid);
                                            Toast.makeText(requireContext(),
                                                "Blocked", Toast.LENGTH_SHORT).show();
                                        })
                                        .setNegativeButton("Cancel", null).create());
                                }
                                return true;
                            case 6: // Open original
                                openReelWithContext(currentFeedPosts, rid, r.ownerName);
                                return true;
                            case 7: // Save for offline
                                saveHomeReelOffline(r);
                                return true;
                            case 8:
                                launchHomeStoryShare(r, false);
                                return true;
                            case 9:
                                launchHomeStoryShare(r, true);
                                return true;
                            case 10:
                                launchHomeRemix(r);
                                return true;
                        }
                        return false;
                    });
                    popup.show();
                });
            }

            holder.actionBarListenersBound = true;
        }

        // tvSends' COUNT text is genuinely per-bind data (changes with the
        // reel), so it's still refreshed on every bind, unguarded — only
        // the listener above was the one-time part.
        if (holder.tvSends != null) {
            holder.tvSends.setText(formatCount(reel.sharesCount));
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

    /** Binds all metadata that sits below a Home-feed caption. Every view is
     * reset first because this physical ViewHolder is recycled between posts. */
    private void bindInlineSocialMetadata(PostRowHolder holder, ReelModel reel) {
        if (holder == null || reel == null) return;
        TextView likedBy = holder.tvLikedBy;
        TextView commentPreview = holder.tvCommentPreview;
        // PERF: likedBy/commentPreview/tvTranslate listeners used to be a
        // fresh lambda (capturing that bind's `reel`) allocated on EVERY
        // bind. Registered ONCE per physical holder now — reads
        // holder.boundReel at click time instead, same pattern as the
        // action-bar buttons in addFeedPostCard(). clickListenersBound is
        // already true by the time this method is called on the 2nd+ bind
        // of a given holder (it's flipped true earlier in the very first
        // bind's addFeedPostCard() call), so this correctly registers once.
        boolean registerOnce = !holder.clickListenersBound;
        if (likedBy != null) {
            if (registerOnce) {
                likedBy.setOnClickListener(v -> {
                    ReelModel currentReel = holder.boundReel;
                    if (currentReel != null) openLikes(currentReel);
                });
            }
            // ★ Instagram-level PERF: this used to setVisibility(GONE) +
            // setText("") unconditionally on EVERY bind, then always fire a
            // fresh reelLikeMeta network read a few lines below — even when
            // the exact same reel was just rebinding onto this same holder
            // (a like-tap's notifyItemChanged, or scrolling a half-screen
            // and back). Now: same reel as last time this holder fetched →
            // reapply the cached label instead of re-clearing + re-fetching;
            // different reel → clear now, fetch runs further down.
            boolean sameLikedByReel = reel.reelId != null && reel.reelId.equals(holder.lastLikedByReelId);
            if (sameLikedByReel) {
                if (holder.lastLikedByLabel != null) {
                    likedBy.setText(holder.lastLikedByLabel);
                    likedBy.setVisibility(View.VISIBLE);
                } else {
                    likedBy.setVisibility(View.GONE);
                }
            } else {
                likedBy.setVisibility(View.GONE);
                likedBy.setText("");
            }
        }
        if (commentPreview != null) {
            commentPreview.setVisibility(View.GONE);
            commentPreview.setText("");
            if (registerOnce) {
                commentPreview.setOnClickListener(v -> {
                    ReelModel currentReel = holder.boundReel;
                    if (currentReel != null) openCommentsForCard(currentReel);
                });
            }
        }

        // PERF: no more removeAllViews() here — bindTaggedPeople()/
        // bindProductTags() now reuse a per-holder pill POOL (create once,
        // refresh text/tag/visibility on every bind) instead of tearing the
        // whole row down and reinflating it from scratch every single bind.
        // Each of those two methods hides any pool pills it doesn't need
        // for THIS reel, so nothing stale from a previous, longer row stays
        // visible; the rows still start hidden here and only reappear once
        // bindTaggedPeople()/bindProductTags() actually populate a pill.
        if (holder.scrollTaggedPeople != null) holder.scrollTaggedPeople.setVisibility(View.GONE);
        if (holder.scrollProductTags != null) holder.scrollProductTags.setVisibility(View.GONE);

        if (holder.tvTranslate != null) {
            boolean hasCaption = reel.caption != null && !reel.caption.trim().isEmpty();
            holder.tvTranslate.setVisibility(hasCaption ? View.VISIBLE : View.GONE);
            holder.tvTranslate.setText("See translation");
            if (registerOnce) {
                holder.tvTranslate.setOnClickListener(v -> {
                    ReelModel currentReel = holder.boundReel;
                    if (currentReel != null) showCaptionLanguageChooser(holder, currentReel);
                });
            }
        }
        if (holder.tvTranslation != null) {
            holder.tvTranslation.setVisibility(View.GONE);
            holder.tvTranslation.setText("");
        }

        // Social proof: reelLikeMeta contains the denormalized display name
        // written by FirebaseUtils.writeReelLike(). It avoids one user read
        // per card and naturally handles legacy likes with no metadata.
        //
        // ★ Instagram-level PERF: skip the read entirely when this holder
        // already fetched it for THIS exact reel (holder.lastLikedByReelId
        // guard above already reapplied the cached label in that case) —
        // same "same-URL skip" principle as the thumb/avatar Glide loads.
        if (reel.reelId != null && !reel.reelId.isEmpty() && likedBy != null
                && !reel.reelId.equals(holder.lastLikedByReelId)) {
            FirebaseUtils.getReelLikeMetaRef(reel.reelId).limitToFirst(2)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        if (holder.boundReel != reel || !isAdded()) return;
                        ArrayList<String> names = new ArrayList<>();
                        for (DataSnapshot child : s.getChildren()) {
                            String n = child.child("name").getValue(String.class);
                            if (n == null || n.trim().isEmpty()) n = child.child("username").getValue(String.class);
                            if (n != null && !n.trim().isEmpty()) names.add(n.trim());
                        }
                        holder.lastLikedByReelId = reel.reelId;
                        if (names.isEmpty()) {
                            holder.lastLikedByLabel = null;
                            return;
                        }
                        String label = names.size() == 1
                            ? "Liked by " + names.get(0) + " and others"
                            : "Liked by " + names.get(0) + ", " + names.get(1) + " and others";
                        holder.lastLikedByLabel = label;
                        likedBy.setText(label);
                        likedBy.setVisibility(View.VISIBLE);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }

        // Prefer a pinned comment (already denormalized on ReelModel), then
        // fall back to the newest comment for older/unpinned reels.
        if (commentPreview != null) {
            if (reel.pinnedCommentText != null && !reel.pinnedCommentText.trim().isEmpty()) {
                String author = reel.pinnedCommentAuthorName == null ? "" : reel.pinnedCommentAuthorName;
                commentPreview.setText("💬 " + (author.isEmpty() ? "" : author + ": ") +
                    reel.pinnedCommentText.trim());
                commentPreview.setVisibility(View.VISIBLE);
                // Pinned path is synchronous (no network read), so it never
                // needs the fetch-cache below — invalidate it so a LATER
                // reel that lacks a pinned comment doesn't wrongly reuse a
                // stale fetched value from a much earlier bind.
                holder.lastCommentPreviewReelId = null;
            } else if (reel.reelId != null && !reel.reelId.isEmpty()) {
                // ★ Instagram-level PERF: same same-reel skip as likedBy
                // above — reuse the cached comment text/visibility instead
                // of re-hitting Firebase on a rebind of the same reel.
                if (reel.reelId.equals(holder.lastCommentPreviewReelId)) {
                    if (holder.lastCommentPreviewText != null) {
                        commentPreview.setText(holder.lastCommentPreviewText);
                        commentPreview.setVisibility(View.VISIBLE);
                    }
                } else {
                    FirebaseUtils.getReelCommentsRef(reel.reelId).orderByKey().limitToLast(1)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot s) {
                                if (holder.boundReel != reel || !isAdded()) return;
                                holder.lastCommentPreviewReelId = reel.reelId;
                                holder.lastCommentPreviewText = null;
                                for (DataSnapshot child : s.getChildren()) {
                                    String text = child.child("text").getValue(String.class);
                                    if (text == null || text.trim().isEmpty()) continue;
                                    String author = child.child("ownerName").getValue(String.class);
                                    String label = "💬 " + (author == null || author.isEmpty() ? "" : author + ": ") + text.trim();
                                    holder.lastCommentPreviewText = label;
                                    commentPreview.setText(label);
                                    commentPreview.setVisibility(View.VISIBLE);
                                }
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
            }
        }

        bindTaggedPeople(holder, reel);
        bindProductTags(holder, reel);
    }

    /** Shared by btnComment and the comment-COUNT tap (tvComments) — both
     *  open the same ReelCommentActivity for whatever reel is CURRENTLY
     *  bound to this holder. Pulled out so the two once-registered
     *  listeners don't each need their own copy of this logic. */
    private void openReelComments(PostRowHolder holder) {
        ReelModel r = holder.boundReel;
        if (!isAdded() || getContext() == null || r == null || r.reelId == null) return;
        Intent ci = new Intent(getContext(), ReelCommentActivity.class);
        ci.putExtra(ReelCommentActivity.EXTRA_REEL_ID,  r.reelId);
        ci.putExtra(ReelCommentActivity.EXTRA_REEL_UID, r.uid != null ? r.uid : "");
        startActivity(ci);
    }

    /** Returns the pool pill at `index`, creating (and adding to `container`
     *  + registering `sharedClickListener` on) a new one only the FIRST time
     *  that index is needed for this physical holder — every later bind that
     *  needs `index` again just gets the same View back, still attached,
     *  still listening. This is the same "cache-the-view, refresh-the-data"
     *  principle as PostRowHolder.cacheViews(), applied to a variable-length
     *  row of chips instead of a fixed set of fields. */
    private TextView obtainPoolPill(java.util.List<TextView> pool, LinearLayout container,
                                     int index, View.OnClickListener sharedClickListener) {
        if (index < pool.size()) return pool.get(index);
        TextView pill = metadataPill("");
        pill.setOnClickListener(sharedClickListener);
        pool.add(pill);
        container.addView(pill);
        return pill;
    }

    private void bindTaggedPeople(PostRowHolder holder, ReelModel reel) {
        if (holder.layoutTaggedPeople == null) return;
        java.util.List<String> uids = reel.taggedPeopleUids;
        int max = uids == null ? 0 : Math.min(5, uids.size());
        int used = 0;
        for (int i = 0; i < max; i++) {
            String uid = uids.get(i);
            if (uid == null || uid.trim().isEmpty()) continue;
            final TextView pill = obtainPoolPill(holder.taggedPeoplePillPool,
                holder.layoutTaggedPeople, used, taggedPersonPillClickListener);
            pill.setTag(uid);
            pill.setVisibility(View.VISIBLE);
            if (holder.scrollTaggedPeople != null) holder.scrollTaggedPeople.setVisibility(View.VISIBLE);
            // ★ Instagram-level PERF: this used to fire a fresh
            // getUserRef(uid) network read for EVERY tagged pill on EVERY
            // single bind — even though the same person is tagged across
            // many different reels, and the same reel rebinds on the same
            // holder repeatedly while scrolling. Resolved names basically
            // never change mid-session, so check the fragment-level
            // taggedUserNameCache first; only hit Firebase on a genuine
            // cache miss (first time this user is ever seen this session).
            String cached = taggedUserNameCache.get(uid);
            if (cached != null) {
                pill.setText(cached);
            } else {
                pill.setText("…");
                FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        String name = s.child("username").getValue(String.class);
                        if (name == null || name.isEmpty()) name = s.child("handle").getValue(String.class);
                        if (name == null || name.isEmpty()) name = s.child("name").getValue(String.class);
                        String label = "@" + (name == null || name.isEmpty() ? "user" : name);
                        taggedUserNameCache.put(uid, label);
                        // Bail on applying to the VIEW if this holder moved on to a
                        // different reel, OR this exact pool slot got reused for a
                        // different tag, while the Firebase read was in flight — the
                        // cache write above still happens either way.
                        if (holder.boundReel != reel || !isAdded() || !uid.equals(pill.getTag())) return;
                        pill.setText(label);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
            }
            used++;
        }
        // Any pool pills left over from a previous, longer-tagged reel that
        // landed on this same physical holder — hide, don't destroy, so
        // they're ready to reuse the next time a reel needs that many.
        for (int i = used; i < holder.taggedPeoplePillPool.size(); i++) {
            holder.taggedPeoplePillPool.get(i).setVisibility(View.GONE);
        }
    }

    private void bindProductTags(PostRowHolder holder, ReelModel reel) {
        if (holder.layoutProductTags == null) return;
        java.util.List<ReelModel.ProductTag> products = reel.productTags;
        int used = 0;
        if (products != null) {
            for (ReelModel.ProductTag product : products) {
                if (product == null || product.name == null || product.name.trim().isEmpty()) continue;
                TextView pill = obtainPoolPill(holder.productTagPillPool,
                    holder.layoutProductTags, used, productTagPillClickListener);
                String label = "🛍 " + product.name.trim();
                if (product.price != null && !product.price.trim().isEmpty()) label += " · " + product.price.trim();
                pill.setText(label);
                pill.setTag(product);
                pill.setVisibility(View.VISIBLE);
                if (holder.scrollProductTags != null) holder.scrollProductTags.setVisibility(View.VISIBLE);
                used++;
            }
        }
        for (int i = used; i < holder.productTagPillPool.size(); i++) {
            holder.productTagPillPool.get(i).setVisibility(View.GONE);
        }
    }

    private TextView metadataPill(String text) {
        TextView pill = new TextView(requireContext());
        pill.setText(text);
        pill.setTextColor(0xFF374151);
        pill.setTextSize(11f);
        pill.setGravity(Gravity.CENTER);
        pill.setSingleLine(true);
        pill.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFFF0F1F5);
        bg.setCornerRadius(dpToPx(16));
        pill.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dpToPx(32));
        lp.setMargins(0, dpToPx(3), dpToPx(6), dpToPx(3));
        pill.setLayoutParams(lp);
        return pill;
    }

    private void openLikes(ReelModel reel) {
        if (!isAdded() || reel == null || reel.reelId == null) return;
        com.callx.app.comments.ReelLikesBottomSheet sheet =
            com.callx.app.comments.ReelLikesBottomSheet.newInstance(
                reel.reelId, reel.likesCount, 0);
        sheet.show(getChildFragmentManager(), "home_likes");
    }

    private void openCommentsForCard(ReelModel reel) {
        if (!isAdded() || getContext() == null || reel == null || reel.reelId == null) return;
        Intent i = new Intent(getContext(), ReelCommentActivity.class);
        i.putExtra(ReelCommentActivity.EXTRA_REEL_ID, reel.reelId);
        i.putExtra(ReelCommentActivity.EXTRA_REEL_UID, reel.uid == null ? "" : reel.uid);
        startActivity(i);
    }

    private void openUserProfile(String uid) {
        if (!isAdded() || getContext() == null || uid == null || uid.isEmpty()) return;
        Intent i = new Intent(getContext(), UserReelsActivity.class);
        i.putExtra(UserReelsActivity.EXTRA_UID, uid);
        startActivity(i);
    }

    private void showCaptionLanguageChooser(PostRowHolder holder, ReelModel reel) {
        if (!isAdded() || reel == null || reel.caption == null) return;
        String[] languages = {"English", "Hindi", "Spanish", "French"};
        String[] codes = {"en", "hi", "es", "fr"};
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Translate caption")
            .setItems(languages, (d, which) -> translateCaption(holder, reel, codes[which]))
            .show();
    }

    /** Uses Google's no-key translation endpoint for this user-requested,
     * one-caption action. Nothing is sent until the user taps a language. */
    private void translateCaption(PostRowHolder holder, ReelModel reel, String language) {
        if (holder.tvTranslation == null || reel.caption == null) return;
        holder.tvTranslation.setText("Translating…");
        holder.tvTranslation.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String result = null;
            try {
                String q = java.net.URLEncoder.encode(reel.caption, "UTF-8");
                java.net.URL url = new java.net.URL(
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                    + language + "&dt=t&q=" + q);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(7000);
                c.setReadTimeout(7000);
                java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()));
                StringBuilder json = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) json.append(line);
                br.close();
                org.json.JSONArray rows = new org.json.JSONArray(json.toString()).getJSONArray(0);
                StringBuilder translated = new StringBuilder();
                for (int i = 0; i < rows.length(); i++) translated.append(rows.getJSONArray(i).optString(0));
                result = translated.toString();
            } catch (Exception ignored) {}
            final String translated = result;
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (holder.boundReel != reel || holder.tvTranslation == null) return;
                holder.tvTranslation.setText(translated == null || translated.isEmpty()
                    ? "Translation unavailable right now." : translated);
            });
        }).start();
    }

    private void launchHomeStoryShare(ReelModel reel, boolean closeFriends) {
        if (!isAdded() || getContext() == null || reel == null || reel.reelId == null) return;
        Intent i = new Intent(requireContext(),
            com.callx.app.social.ReelShareToStoryActivity.class);
        i.putExtra(com.callx.app.social.ReelShareToStoryActivity.EXTRA_REEL_ID, reel.reelId);
        i.putExtra(com.callx.app.social.ReelShareToStoryActivity.EXTRA_REEL_URL,
            reel.videoUrl != null && !reel.videoUrl.isEmpty() ? reel.videoUrl : reel.video480);
        i.putExtra(com.callx.app.social.ReelShareToStoryActivity.EXTRA_REEL_OWNER_NAME,
            reel.ownerName == null ? "" : reel.ownerName);
        if (closeFriends) {
            i.putExtra(com.callx.app.social.ReelShareToStoryActivity.EXTRA_PRIVACY_PRESET,
                "close_friends");
        }
        startActivity(i);
    }

    private void launchHomeRemix(ReelModel reel) {
        if (!isAdded() || getContext() == null || reel == null || reel.reelId == null) return;
        Intent i = new Intent(requireContext(),
            com.callx.app.social.ReelRemixActivity.class);
        i.putExtra(com.callx.app.social.ReelRemixActivity.EXTRA_REEL_ID, reel.reelId);
        i.putExtra(com.callx.app.social.ReelRemixActivity.EXTRA_OWNER_UID,
            reel.uid == null ? "" : reel.uid);
        i.putExtra(com.callx.app.social.ReelRemixActivity.EXTRA_OWNER_NAME,
            reel.ownerName == null ? "" : reel.ownerName);
        i.putExtra(com.callx.app.social.ReelRemixActivity.EXTRA_VIDEO_URL,
            reel.videoUrl == null ? "" : reel.videoUrl);
        i.putExtra(com.callx.app.social.ReelRemixActivity.EXTRA_THUMB_URL,
            reel.thumbUrl == null ? "" : reel.thumbUrl);
        startActivity(i);
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
     * Hashtag taps open discovery; mention taps resolve the handle to a profile.
     */
    private android.text.SpannableString buildCaptionSpannable(String text) {
        if (text == null || text.isEmpty())
            return new android.text.SpannableString("");
        android.text.SpannableString span = new android.text.SpannableString(text);
        java.util.regex.Matcher m = HASHTAG_PATTERN.matcher(text);
        while (m.find()) {
            final String prefix = m.group(1);
            final String token = m.group(2);
            final int s = m.start(), e = m.end();
            span.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(@NonNull android.view.View w) {
                    if (!isAdded() || getContext() == null || token == null) return;
                    if ("#".equals(prefix)) {
                        Intent hi = new Intent(getContext(), HashtagReelsActivity.class);
                        hi.putExtra("hashtag", token);
                        startActivity(hi);
                    } else {
                        resolveMentionAndOpenProfile(token);
                    }
                }
                @Override public void updateDrawState(@NonNull android.text.TextPaint ds) {
                    ds.setColor(0xFF00C6FF); ds.setUnderlineText(false);
                }
            }, s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return span;
    }

    private void resolveMentionAndOpenProfile(String handle) {
        if (!isAdded() || handle == null || handle.isEmpty()) return;
        Query q = FirebaseUtils.db().getReference("users")
            .orderByChild("username").equalTo(handle).limitToFirst(1);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String uid = null;
                String name = handle;
                for (DataSnapshot child : s.getChildren()) {
                    uid = child.getKey();
                    name = child.child("name").getValue(String.class);
                    break;
                }
                if (uid == null) {
                    FirebaseUtils.db().getReference("users")
                        .orderByChild("handle").equalTo(handle).limitToFirst(1)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot fallback) {
                                for (DataSnapshot child : fallback.getChildren()) {
                                    openUserProfile(child.getKey());
                                    return;
                                }
                                Toast.makeText(requireContext(), "Profile not found",
                                    Toast.LENGTH_SHORT).show();
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                } else {
                    openUserProfile(uid);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Toast.makeText(requireContext(), "Profile not found", Toast.LENGTH_SHORT).show();
            }
        });
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

    /**
     * Opens SoundDetailActivity for a Home-feed card's attached music —
     * same destination/extras as the immersive Reels player's audio-pill
     * tap (ReelDuetController.openSoundDetail()), just triggered from the
     * inline feed card's tv_post_audio label instead.
     */
    private void openHomeCardSoundDetail(ReelModel reel) {
        if (!isAdded() || getContext() == null || reel == null) return;

        // ⚠️ reel.musicId is written by a BACKGROUND job (audio extraction +
        // sound registration) that runs AFTER the reel is already live, so
        // it can still be empty here even though the sound entity already
        // exists (or is about to) on Firebase. Fall back to the same
        // deterministic "orig_{reelId}" ID ReelUploadActivity registers
        // original audio under — mirrors ReelDuetController.openSoundDetail().
        String soundId = reel.musicId;
        if ((soundId == null || soundId.isEmpty()) && reel.reelId != null && !reel.reelId.isEmpty()) {
            soundId = "orig_" + reel.reelId;
        }

        Intent i = new Intent(getContext(), com.callx.app.music.SoundDetailActivity.class);
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_ID,
            soundId != null ? soundId : "");
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_TITLE,
            reel.musicName != null && !reel.musicName.isEmpty() ? reel.musicName : "Original Audio");
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_SOUND_URL,
            reel.musicUrl != null ? reel.musicUrl : "");
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_COVER_URL,
            reel.musicCoverUrl != null ? reel.musicCoverUrl : "");
        i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_ARTIST,
            reel.musicArtist != null && !reel.musicArtist.isEmpty()
                ? reel.musicArtist
                : (reel.ownerName != null ? reel.ownerName : ""));
        // Only pass creator uid when this reel IS the sound's own source —
        // otherwise SoundDetailActivity resolves creatorUid from the sound
        // node itself (see ReelDuetController.openSoundDetail() for why).
        boolean isOwnSoundSource = reel.reelId != null
            && soundId != null && soundId.equals("orig_" + reel.reelId);
        if (isOwnSoundSource && reel.uid != null && !reel.uid.isEmpty()) {
            i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_CREATOR_UID, reel.uid);
        }
        if (reel.originalAudioUrl != null && !reel.originalAudioUrl.isEmpty()) {
            i.putExtra(com.callx.app.music.SoundDetailActivity.EXTRA_ORIGINAL_AUDIO_URL, reel.originalAudioUrl);
        } else {
            i.putExtra("reel_video_url", reel.videoUrl != null ? reel.videoUrl : "");
        }
        startActivity(i);
    }

    /** Opens SingleReelPlayerActivity by reel ID directly — no scroll context,
     *  falls back to a single-item list. Kept only for call sites that truly
     *  have no surrounding row/feed (e.g. a deep link to one reel). Prefer
     *  {@link #openReelWithContext(List, String, String)} everywhere the reel
     *  came from a visible list, so scrolling past it in the fullscreen
     *  player continues into the next reels — Instagram/TikTok style —
     *  instead of dead-ending on that one reel. */
    private void openReelById(String reelId, String ownerName) {
        openReelWithContext(null, reelId, ownerName);
    }

    /** Returns how far (ms) the given card's inline preview has actually
     *  played right now — only meaningful when the SHARED feedPlayer is
     *  currently attached to that exact card (feedPlayer plays at most one
     *  card at a time; every other card is just a static thumbnail). Returns
     *  0 for any other card, or if nothing is attached/playing yet — which
     *  correctly falls back to a normal from-the-start open. */
    private long capturePreviewPositionMs(int cardIndex) {
        if (feedPlayer == null || cardIndex != currentPlayingIndex) return 0;
        try {
            long pos = feedPlayer.getCurrentPosition();
            return pos > 0 ? pos : 0;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /** Opens SingleReelPlayerActivity with the full ordered list of reel IDs
     *  the tapped reel was part of (contextList), starting at that reel's
     *  position — so scrolling down in the fullscreen player keeps advancing
     *  through the SAME list (home feed, trending row, continue-watching
     *  row, etc.) instead of dead-ending after one reel.
     *  @param contextList the list currently backing the row/feed the user
     *                     tapped from; pass null (or empty) to fall back to
     *                     a single-item list when no such list exists. */
    private void openReelWithContext(List<ReelModel> contextList, String reelId, String ownerName) {
        openReelWithContext(contextList, reelId, ownerName, 0);
    }

    /** Same as {@link #openReelWithContext(List, String, String)}, plus an
     *  Instagram-style resume position: when the tapped card's inline
     *  preview had already played {@code resumeAtMs} into the video,
     *  the fullscreen player picks up from exactly there instead of
     *  restarting at 0. Pass 0 for a normal open (thumbnail tap before
     *  playback started, etc.). */
    private void openReelWithContext(List<ReelModel> contextList, String reelId, String ownerName, long resumeAtMs) {
        if (!isAdded() || getContext() == null || reelId == null) return;

        // Stamp the resume position onto the exact ReelModel instance that's
        // about to be primed into ReelModelCache below — SingleReelPlayerActivity
        // reads this same object back out of that cache, so the value survives
        // the activity hop without needing its own Intent extra.
        if (resumeAtMs > 0 && contextList != null) {
            for (ReelModel r : contextList) {
                if (r != null && reelId.equals(r.reelId)) {
                    r.pendingStartPositionMs = resumeAtMs;
                    break;
                }
            }
        }

        // PERF: prime the shared in-memory ReelModelCache with every
        // ReelModel we already have in hand right now — HomeFragment fetched
        // all of these once already (feed page load / trending / continue-
        // watching), so SingleReelPlayerActivity shouldn't have to hit
        // Firebase again for a single one of them. Only the intent-safe
        // reelId strings cross the activity boundary (see ids below); the
        // actual objects travel via this process-wide cache instead, so
        // there's no Binder transaction size concern even for a long feed.
        com.callx.app.cache.ReelModelCache.getInstance().putAll(contextList);

        ArrayList<String> ids = new ArrayList<>();
        int startPos = 0;
        if (contextList != null && !contextList.isEmpty()) {
            for (ReelModel r : contextList) {
                if (r != null && r.reelId != null && !r.reelId.isEmpty()) {
                    if (r.reelId.equals(reelId)) startPos = ids.size();
                    ids.add(r.reelId);
                }
            }
        }
        if (ids.isEmpty()) {
            // No usable context (or the tapped reel wasn't in it) — same
            // single-item behaviour as before.
            ids.add(reelId);
            startPos = 0;
        }

        Intent i = new Intent(getContext(), SingleReelPlayerActivity.class);
        i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
        i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, startPos);
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

    /** Cached child-view refs + click-target state for one pooled Trending
     *  card — see obtainTrendingCard(). The click listener is registered
     *  once per pooled card and reads reelId/ownerName/rowContext off THIS
     *  tag at click time, same allocation-free rule as
     *  SuggestedCreatorCardTag. */
    private static class TrendingCardTag {
        ImageView thumb;
        TextView tvLikes, tvOwner;
        String reelId, ownerName;
        List<ReelModel> rowContext;
    }

    /** Returns the pooled Trending card at index i, inflating (and adding to
     *  containerTrending) a new one only the first time this index is
     *  needed. Child-view lookups + the click listener are done exactly
     *  once per index for the life of the fragment; every later refresh
     *  only updates text/image/tag on the SAME View. */
    private View obtainTrendingCard(int index) {
        if (index < trendingCardPool.size()) return trendingCardPool.get(index);

        View card = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_home_trending, containerTrending, false);
        TrendingCardTag tag = new TrendingCardTag();
        tag.thumb   = card.findViewById(R.id.iv_trending_thumb);
        tag.tvLikes = card.findViewById(R.id.tv_trending_likes);
        tag.tvOwner = card.findViewById(R.id.tv_trending_owner);
        card.setTag(tag);

        // ✅ Open specific reel in the player — allocation-free: reads
        // whatever TrendingCardTag is CURRENTLY on `card`'s tag at click
        // time, so this single listener instance stays correct across
        // every future refresh of this slot.
        card.setOnClickListener(v -> {
            if (!isAdded() || getContext() == null) return;
            TrendingCardTag t = (TrendingCardTag) v.getTag();
            if (t == null || t.reelId == null) return;
            openReelWithContext(t.rowContext, t.reelId, t.ownerName);
        });

        trendingCardPool.add(card);
        containerTrending.addView(card);
        return card;
    }

    private void renderTrending(List<ReelModel> reels) {
        if (!isAdded() || getContext() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (containerTrending == null || !isAdded()) return;
            if (pbTrending != null) pbTrending.setVisibility(View.GONE);

            int i = 0;
            for (ReelModel reel : reels) {
                View card = obtainTrendingCard(i);
                TrendingCardTag tag = (TrendingCardTag) card.getTag();
                tag.reelId     = reel.reelId;
                tag.ownerName  = reel.ownerName;
                tag.rowContext = reels;

                tag.tvLikes.setText("❤ " + formatCount(reel.likesCount));
                tag.tvOwner.setText(reel.ownerName != null ? "@" + reel.ownerName : "@user");
                if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
                    Glide.with(requireContext()).load(reel.thumbUrl).apply(FEED_IMAGE_OPTS)
                        .centerCrop().override(STRIP_THUMB_DECODE_PX, STRIP_THUMB_DECODE_PX).into(tag.thumb);
                } else {
                    Glide.with(requireContext()).clear(tag.thumb);
                }
                card.setVisibility(View.VISIBLE);
                i++;
            }

            // Any pool slots left over from a previous, longer trending
            // list — hidden, not destroyed, so they're ready to reuse next
            // refresh (same rule as suggestedCreatorCardPool).
            for (; i < trendingCardPool.size(); i++) {
                trendingCardPool.get(i).setVisibility(View.GONE);
            }

            View existingEmpty = containerTrending.findViewWithTag("trending_empty");
            if (reels.isEmpty()) {
                if (existingEmpty == null) {
                    TextView empty = new TextView(requireContext());
                    empty.setTag("trending_empty");
                    empty.setText("No trending reels yet");
                    empty.setTextColor(0xFF888888);
                    empty.setPadding(0, 8, 0, 8);
                    containerTrending.addView(empty);
                } else {
                    existingEmpty.setVisibility(View.VISIBLE);
                }
            } else if (existingEmpty != null) {
                existingEmpty.setVisibility(View.GONE);
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

    /** One pooled Friends Activity slot — the content row (avatar/icon/
     *  message/time) plus its trailing divider, built once and reused
     *  across refreshes. See obtainFriendsActivityCard(). */
    private static class FriendsActivityCard {
        final LinearLayout    row;
        final View            divider;
        final CircleImageView avatar;
        final ImageView       icon;
        final TextView        tvMsg;
        final TextView        tvTime;
        FriendsActivityCard(LinearLayout row, View divider, CircleImageView avatar,
                             ImageView icon, TextView tvMsg, TextView tvTime) {
            this.row = row; this.divider = divider; this.avatar = avatar;
            this.icon = icon; this.tvMsg = tvMsg; this.tvTime = tvTime;
        }
    }

    /** Returns the pooled Friends Activity card at index i, building (and
     *  adding row+divider to containerFriendsActivity) a new one only the
     *  first time this index is needed. Every later refresh only updates
     *  text/image on the SAME row — no fresh Views, no new listeners. */
    private FriendsActivityCard obtainFriendsActivityCard(int index) {
        if (index < friendsActivityCardPool.size()) return friendsActivityCardPool.get(index);

        Context ctx = requireContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int dp12 = dpToPx(12);
        int dp8  = dpToPx(8);
        row.setPadding(0, dp8, 0, dp8);

        CircleImageView miniAvatar = new CircleImageView(ctx);
        int sz = dpToPx(32);
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(sz, sz);
        avLp.setMarginEnd(dp12);
        miniAvatar.setLayoutParams(avLp);
        row.addView(miniAvatar);

        ImageView icon = new ImageView(ctx);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(16), dpToPx(16));
        iconLp.setMarginEnd(dpToPx(6));
        icon.setLayoutParams(iconLp);
        row.addView(icon);

        TextView tvMsg = new TextView(ctx);
        tvMsg.setTextColor(0xFFDDDDDD);
        tvMsg.setTextSize(12.5f);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvMsg.setLayoutParams(msgLp);
        row.addView(tvMsg);

        TextView tvTime = new TextView(ctx);
        tvTime.setTextColor(0xFF888888);
        tvTime.setTextSize(11f);
        row.addView(tvTime);

        View divider = new View(ctx);
        divider.setBackgroundColor(0x1AFFFFFF);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));

        containerFriendsActivity.addView(row);
        containerFriendsActivity.addView(divider);

        FriendsActivityCard card = new FriendsActivityCard(row, divider, miniAvatar, icon, tvMsg, tvTime);
        friendsActivityCardPool.add(card);
        return card;
    }

    @SuppressWarnings("unchecked")
    private void renderFriendsActivity(List<Map<String, Object>> activities) {
        if (!isAdded() || getContext() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (containerFriendsActivity == null || !isAdded()) return;
            if (pbActivity != null) pbActivity.setVisibility(View.GONE);

            int i = 0;
            for (Map<String, Object> act : activities) {
                String message  = (String) act.get("message");
                Long   ts       = (Long)   act.get("timestamp");
                String type     = (String) act.get("type");
                String fromPhoto= (String) act.get("from_photo");

                FriendsActivityCard card = obtainFriendsActivityCard(i);

                card.avatar.setImageResource(R.drawable.ic_person);
                if (fromPhoto != null && !fromPhoto.isEmpty()) {
                    Glide.with(requireContext()).load(fromPhoto)
                        .apply(RequestOptions.circleCropTransform())
                        .placeholder(R.drawable.ic_person).into(card.avatar);
                } else {
                    Glide.with(requireContext()).clear(card.avatar);
                }

                int iconRes = "repost".equals(type) ? R.drawable.ic_repost
                    : "comment".equals(type) ? R.drawable.ic_comment_reel
                    : "follow".equals(type) ? R.drawable.ic_person
                    : R.drawable.ic_heart_filled;
                card.icon.setImageResource(iconRes);

                card.tvMsg.setText(message);
                card.tvTime.setText(ts != null ? formatAgo(ts) : "");

                card.row.setVisibility(View.VISIBLE);
                card.divider.setVisibility(View.VISIBLE);
                i++;
            }

            // Leftover pool slots from a previous, longer activity list —
            // hidden, not destroyed (same rule as trendingCardPool).
            for (; i < friendsActivityCardPool.size(); i++) {
                FriendsActivityCard c = friendsActivityCardPool.get(i);
                c.row.setVisibility(View.GONE);
                c.divider.setVisibility(View.GONE);
            }

            View existingEmpty = containerFriendsActivity.findViewWithTag("friends_activity_empty");
            if (activities.isEmpty()) {
                if (existingEmpty == null) {
                    TextView empty = new TextView(requireContext());
                    empty.setTag("friends_activity_empty");
                    empty.setText("No recent activity from friends");
                    empty.setTextColor(0xFF888888);
                    empty.setTextSize(12f);
                    empty.setPadding(0, 8, 0, 8);
                    containerFriendsActivity.addView(empty);
                } else {
                    existingEmpty.setVisibility(View.VISIBLE);
                }
            } else if (existingEmpty != null) {
                existingEmpty.setVisibility(View.GONE);
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
                        requireActivity().runOnUiThread(() ->
                            renderContinueWatching(new ArrayList<>(), "No watch history yet"));
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
        // Build the surviving (non-deleted) list once so every card in this
        // row can be opened with the WHOLE row as scroll context, not just
        // itself — same fix as the main feed / trending rows.
        List<ReelModel> watched = new ArrayList<>();
        for (ReelModel r : slots) {
            if (r != null) watched.add(r);
        }
        requireActivity().runOnUiThread(() -> renderContinueWatching(watched, "No watch history yet"));
    }

    /** Cached child-view refs + click-target state for one pooled Continue
     *  Watching card — see obtainContinueWatchingCard(). bindToken is
     *  bumped every time this slot is reused for a different reel, and the
     *  in-flight watch-progress read below checks it before writing to
     *  pbWatch — exactly the same stale-callback guard
     *  SuggestedCreatorsTileAdapter uses for its mutual-followers lookup —
     *  so a slow progress read for slot i's PREVIOUS reel can never land on
     *  the WRONG reel's progress bar after a refresh reuses that slot. */
    private static class ContinueWatchingCardTag {
        ImageView thumb;
        TextView tvOwner;
        ProgressBar pbWatch;
        String reelId, ownerName;
        List<ReelModel> rowContext;
        int bindToken = 0;
    }

    /** Returns the pooled Continue Watching card at index i, inflating (and
     *  adding to containerContinueWatching) a new one only the first time
     *  this index is needed. Child-view lookups + the click listener are
     *  done exactly once per index; every later refresh only updates
     *  text/image/progress/tag on the SAME View. */
    private View obtainContinueWatchingCard(int index) {
        if (index < continueWatchingCardPool.size()) return continueWatchingCardPool.get(index);

        View card = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_home_continue_watching, containerContinueWatching, false);
        ContinueWatchingCardTag tag = new ContinueWatchingCardTag();
        tag.thumb   = card.findViewById(R.id.iv_cw_thumb);
        tag.tvOwner = card.findViewById(R.id.tv_cw_owner);
        tag.pbWatch = card.findViewById(R.id.pb_cw_progress);
        card.setTag(tag);

        card.setOnClickListener(v -> {
            if (!isAdded() || getContext() == null) return;
            ContinueWatchingCardTag t = (ContinueWatchingCardTag) v.getTag();
            if (t == null || t.reelId == null) return;
            openReelWithContext(t.rowContext, t.reelId, t.ownerName);
        });

        continueWatchingCardPool.add(card);
        containerContinueWatching.addView(card);
        return card;
    }

    /** ★ Instagram-level PERF: pooled cards for Continue Watching — same
     *  obtain-by-index pattern as Trending/Suggested Creators. Replaces the
     *  old one-fresh-inflate-per-card, one-runOnUiThread-per-card approach
     *  with a single batch render, reusing pooled Views and guarding each
     *  card's watch-progress listener with a bindToken (see
     *  ContinueWatchingCardTag doc). emptyMessage lets clearWatchHistory()
     *  reuse this same pooled empty-state text with its own wording instead
     *  of allocating a brand-new TextView on every "Clear" tap. */
    private void renderContinueWatching(List<ReelModel> watched, String emptyMessage) {
        if (!isAdded() || getContext() == null || containerContinueWatching == null) return;
        if (pbContinue != null) pbContinue.setVisibility(View.GONE);

        int i = 0;
        String myUid = safeMyUid();
        for (ReelModel reel : watched) {
            View card = obtainContinueWatchingCard(i);
            ContinueWatchingCardTag tag = (ContinueWatchingCardTag) card.getTag();
            tag.reelId     = reel.reelId;
            tag.ownerName  = reel.ownerName;
            tag.rowContext = watched;
            final int token = ++tag.bindToken;

            tag.tvOwner.setText(reel.ownerName != null ? "@" + reel.ownerName : "@user");
            if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
                Glide.with(requireContext()).load(reel.thumbUrl).apply(FEED_IMAGE_OPTS)
                    .centerCrop().override(STRIP_THUMB_DECODE_PX, STRIP_THUMB_DECODE_PX).into(tag.thumb);
            } else {
                Glide.with(requireContext()).clear(tag.thumb);
            }
            if (tag.pbWatch != null) tag.pbWatch.setProgress(0);

            String reelId = reel.reelId;
            if (tag.pbWatch != null && myUid != null && reelId != null) {
                FirebaseUtils.getReelWatchProgressRef(myUid).child(reelId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            if (!isAdded() || getContext() == null || token != tag.bindToken) return; // stale — slot reused
                            Integer pct = snap.getValue(Integer.class);
                            if (pct != null && pct > 0) tag.pbWatch.setProgress(pct);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
            }

            card.setVisibility(View.VISIBLE);
            i++;
        }

        // Leftover pool slots from a previous, longer watch-history list —
        // hidden, not destroyed (same rule as trendingCardPool).
        for (; i < continueWatchingCardPool.size(); i++) {
            continueWatchingCardPool.get(i).setVisibility(View.GONE);
        }

        View existingEmpty = containerContinueWatching.findViewWithTag("continue_watching_empty");
        if (watched.isEmpty()) {
            if (existingEmpty == null) {
                TextView empty = new TextView(requireContext());
                empty.setTag("continue_watching_empty");
                empty.setText(emptyMessage);
                empty.setTextColor(0xFF888888);
                empty.setTextSize(12f);
                empty.setPadding(0, 8, 0, 8);
                containerContinueWatching.addView(empty);
            } else {
                ((TextView) existingEmpty).setText(emptyMessage);
                existingEmpty.setVisibility(View.VISIBLE);
            }
        } else if (existingEmpty != null) {
            existingEmpty.setVisibility(View.GONE);
        }
    }

    private void clearWatchHistory() {
        String myUid = safeMyUid();
        if (myUid == null || !isAdded() || getContext() == null) return;
        FirebaseUtils.getReelWatchHistoryRef(myUid).removeValue();
        renderContinueWatching(new ArrayList<>(), "Watch history cleared");
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

    /** Per-card mutable state, stored via card.setTag() — read fresh by the
     *  card's ONE allocation-free click listener and the Follow button's ONE
     *  allocation-free click listener at click time, same pattern the
     *  action-bar pill pool (v323) already established for taggedPeople/
     *  productTags. Avoids capturing uid/name/photo/isFollowed in a
     *  per-bind closure, which is what forced a brand-new listener object on
     *  every single card rebuild before. */
    private static class SuggestedCreatorCardTag {
        String uid, name, photo;
        boolean isFollowed;
    }

    private void addCreatorCards(List<String[]> creators, Set<String> followedUids) {
        if (containerSuggestedCreators == null || !isAdded() || getContext() == null) return;

        int i = 0;
        for (String[] c : creators) {
            String uid   = c[0];
            String name  = c[1];
            String photo = c[2];
            String sub   = c[3];
            boolean isFollowed = followedUids.contains(uid);

            LinearLayout card = obtainSuggestedCreatorCard(i);
            SuggestedCreatorCardTag tag = (SuggestedCreatorCardTag) card.getTag();
            tag.uid = uid; tag.name = name; tag.photo = photo; tag.isFollowed = isFollowed;

            CircleImageView av   = (CircleImageView) card.getChildAt(0);
            TextView tvName      = (TextView) card.getChildAt(1);
            TextView tvSub       = (TextView) card.getChildAt(2);
            Button   btnFollow   = (Button)   card.getChildAt(3);

            tvName.setText(name);
            tvSub.setText(sub);
            av.setImageResource(R.drawable.ic_person);
            if (photo != null && !photo.isEmpty()) {
                Glide.with(requireContext()).load(photo)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(av);
            } else {
                Glide.with(requireContext()).clear(av);
            }
            applyFollowButtonState(btnFollow, isFollowed);

            ViewGroup.LayoutParams rawLp = card.getLayoutParams();
            if (rawLp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) rawLp).setMarginEnd(
                        i == creators.size() - 1 ? 0 : dpToPx(10));
            }
            card.setVisibility(View.VISIBLE);
            i++;
        }

        // Any pool slots left over from a previous, longer suggestion list —
        // hidden, not destroyed, so they're ready to reuse next refresh.
        for (; i < suggestedCreatorCardPool.size(); i++) {
            suggestedCreatorCardPool.get(i).setVisibility(View.GONE);
        }

        // Empty-state text: low-frequency enough (only when there are
        // literally zero suggestions) that it's left as a plain child
        // rather than pooled — added after the (possibly all-hidden) cards.
        View existingEmpty = containerSuggestedCreators.findViewWithTag("suggested_creators_empty");
        if (creators.isEmpty()) {
            if (existingEmpty == null) {
                TextView empty = new TextView(requireContext());
                empty.setTag("suggested_creators_empty");
                empty.setText("No suggestions yet");
                empty.setTextColor(0xFF888888);
                empty.setTextSize(12f);
                empty.setPadding(0, 8, 0, 8);
                containerSuggestedCreators.addView(empty);
            } else {
                existingEmpty.setVisibility(View.VISIBLE);
            }
        } else if (existingEmpty != null) {
            existingEmpty.setVisibility(View.GONE);
        }
    }

    /** Returns the pooled card at index i, creating (and adding to
     *  containerSuggestedCreators) a new one only the first time this index
     *  is needed. Every card's View tree — avatar, name, subtitle, Follow
     *  button — plus BOTH click listeners (card-tap-to-profile, Follow
     *  toggle) are built exactly once per index, for the life of the
     *  fragment; every later refresh only updates text/image/tag on the
     *  SAME objects. */
    private LinearLayout obtainSuggestedCreatorCard(int index) {
        if (index < suggestedCreatorCardPool.size()) return suggestedCreatorCardPool.get(index);

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int w = dpToPx(90);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.setMarginEnd(dpToPx(10));
        card.setLayoutParams(cardLp);
        card.setPadding(dpToPx(4), dpToPx(8), dpToPx(4), dpToPx(8));
        card.setBackgroundResource(R.drawable.bg_speed_chip);

        SuggestedCreatorCardTag tag = new SuggestedCreatorCardTag();
        card.setTag(tag);

        CircleImageView av = new CircleImageView(requireContext());
        av.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(56), dpToPx(56)));
        card.addView(av); // child 0

        TextView tvName = new TextView(requireContext());
        tvName.setTextSize(11f);
        tvName.setTextColor(0xFFFFFFFF);
        tvName.setMaxLines(1);
        tvName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tvName.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = dpToPx(4);
        tvName.setLayoutParams(nameLp);
        card.addView(tvName); // child 1

        TextView tvSub = new TextView(requireContext());
        tvSub.setTextSize(10f);
        tvSub.setTextColor(0xFF888888);
        tvSub.setGravity(android.view.Gravity.CENTER);
        card.addView(tvSub); // child 2

        Button btnFollow = new Button(requireContext());
        btnFollow.setTextSize(10f);
        btnFollow.setAllCaps(false);
        btnFollow.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(28));
        btnLp.topMargin = dpToPx(4);
        btnFollow.setLayoutParams(btnLp);
        // Allocation-free: reads whatever SuggestedCreatorCardTag is
        // CURRENTLY on `card`'s tag at click time, so this single listener
        // instance stays correct across every future refresh of this slot.
        btnFollow.setOnClickListener(vv -> {
            String myUid = safeMyUid();
            SuggestedCreatorCardTag t = (SuggestedCreatorCardTag) card.getTag();
            if (myUid == null || t == null || t.uid == null) return;
            t.isFollowed = !t.isFollowed;
            if (t.isFollowed) {
                FirebaseUtils.getReelFollowsRef(myUid).child(t.uid).setValue(true);
                FirebaseUtils.getReelFollowersRef(t.uid).child(myUid).setValue(true);
            } else {
                FirebaseUtils.getReelFollowsRef(myUid).child(t.uid).removeValue();
                FirebaseUtils.getReelFollowersRef(t.uid).child(myUid).removeValue();
            }
            applyFollowButtonState(btnFollow, t.isFollowed);
        });
        card.addView(btnFollow); // child 3

        // Card tap → open user's reels. Same allocation-free rule: reads
        // the tag at click time instead of capturing uid/name/photo.
        card.setOnClickListener(vv -> {
            if (!isAdded() || getContext() == null) return;
            SuggestedCreatorCardTag t = (SuggestedCreatorCardTag) card.getTag();
            if (t == null || t.uid == null) return;
            Intent i = new Intent(getContext(), UserReelsActivity.class);
            i.putExtra(UserReelsActivity.EXTRA_UID,   t.uid);
            i.putExtra(UserReelsActivity.EXTRA_NAME,  t.name);
            i.putExtra(UserReelsActivity.EXTRA_PHOTO, t.photo);
            startActivity(i);
        });

        suggestedCreatorCardPool.add(card);
        containerSuggestedCreators.addView(card);
        return card;
    }

    private void applyFollowButtonState(Button btnFollow, boolean isFollowed) {
        if (isFollowed) {
            btnFollow.setText("Following");
            btnFollow.setBackgroundColor(0xFF333333);
            btnFollow.setTextColor(0xFFCCCCCC);
        } else {
            btnFollow.setText("Follow");
            btnFollow.setBackgroundColor(getResources().getColor(R.color.brand_primary, null));
            btnFollow.setTextColor(0xFFFFFFFF);
        }
    }

    // ── My avatar ─────────────────────────────────────────────────────────

    private void loadMyAvatar() {
        String myUid = safeMyUid();
        if (myUid == null) return;
        // Reels profile avatar load karo (reels/users/{uid})
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded() || getContext() == null) return;
                String thumb = snap.child("thumbUrl").getValue(String.class);
                String photo = snap.child("photoUrl").getValue(String.class);
                String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                Long ver = snap.child("avatarVersion").getValue(Long.class);
                // PERF (RecyclerView conversion): the "Add Story" row is now
                // a normal position-0 adapter item — its ViewHolder can be
                // recycled/recreated, so the resolved data is stored here
                // and re-read by StoriesAdapter#bindAddStoryHolder on every
                // (re)bind, rather than painted once into a single
                // long-lived ImageView field.
                myAvatarPhotoUrl = url;
                myAvatarVersion = ver != null ? ver : 0L;
                myAvatarLoaded = true;
                if (storiesAdapter != null) storiesAdapter.notifyItemChanged(0);
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

    /** Active/inactive dot colors for the below-media photo indicator row —
     *  now that the dots sit on the app's themed background instead of
     *  overlaid on the video/photo itself, they need theme-aware colors
     *  (not the old hardcoded translucent-white, which vanishes in light
     *  mode). PERF: resolved from resources ONCE per fragment instance
     *  (same "compute once, reuse" pattern as cachedFeedVideoH below)
     *  instead of a fresh ContextCompat.getColor() + bit-math call on
     *  every single dot, on every bind AND every page-change — a
     *  multi-photo feed can easily rebuild/recolor dozens of dots per
     *  scroll pass. */
    private int cachedPhotoDotActiveColor   = 0;
    private int cachedPhotoDotInactiveColor = 0;
    private boolean photoDotColorsCached = false;

    private void ensurePhotoDotColorsCached() {
        if (photoDotColorsCached || getContext() == null) return;
        int primary = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_primary);
        int secondary = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.text_secondary);
        cachedPhotoDotActiveColor   = primary;
        // Keep the RGB from text_secondary but dial alpha down to ~30% so the
        // inactive dots stay visibly secondary to the active one.
        cachedPhotoDotInactiveColor = (secondary & 0x00FFFFFF) | 0x4D000000;
        photoDotColorsCached = true;
    }

    private int photoDotActiveColor() {
        ensurePhotoDotColorsCached();
        return photoDotColorsCached ? cachedPhotoDotActiveColor : 0xFF0F172A;
    }

    private int photoDotInactiveColor() {
        ensurePhotoDotColorsCached();
        return photoDotColorsCached ? cachedPhotoDotInactiveColor : 0x4D64748B;
    }

    /**
     * Returns the Home-feed media height for a source aspect ratio.
     *
     * Instagram's feed has a bounded post canvas: portrait media is not
     * allowed to grow past 4:5, and very wide media is not allowed to become
     * a paper-thin strip. The source itself is still rendered with FIT, so
     * clamping the container never crops the actual photo/video. Any remaining
     * space is filled by the blurred media backdrop.
     */
    private int feedCardMediaHeightPx(ReelModel reel) {
        if (getContext() == null) return 0;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int mediaWidth = recyclerHome != null && recyclerHome.getWidth() > 0
                ? recyclerHome.getWidth() : dm.widthPixels;
        if (mediaWidth <= 0) mediaWidth = dm.widthPixels;

        float sourceAspect = 0f; // width / height
        if (reel != null && reel.width > 0 && reel.height > 0) {
            sourceAspect = reel.width / (float) reel.height;
        }

        // Instagram feed bounds: portrait 4:5 through landscape 1.91:1.
        // Unknown legacy photo dimensions use the tallest supported frame;
        // the Glide listener below tightens it once the thumbnail is decoded.
        float displayAspect = sourceAspect > 0f ? sourceAspect : (4f / 5f);
        displayAspect = Math.max(4f / 5f, Math.min(1.91f, displayAspect));

        int ratioHeight = Math.round(mediaWidth / displayAspect);
        int feedCapH = (int) (dm.heightPixels * 0.75f);
        return Math.max(1, Math.min(ratioHeight, feedCapH));
    }

    /** A backdrop is only visible when the source aspect is outside the
     *  bounded feed range. Square, normal landscape, and normal 4:5-or-taller
     *  feed media already fills the frame exactly, so skipping their blur load
     *  removes a second decode/transform/draw from the hot path. */
    private static boolean feedNeedsBackdrop(ReelModel reel) {
        if (reel == null || reel.width <= 0 || reel.height <= 0) return true;
        float sourceAspect = reel.width / (float) reel.height;
        return sourceAspect < (4f / 5f) || sourceAspect > 1.91f;
    }

    /** Re-applies the frame height after Glide reveals dimensions for a legacy
     *  photo/video whose ReelModel did not carry width/height metadata. */
    private void applyFeedMediaAspect(View frameVideo, float sourceAspect) {
        if (frameVideo == null || sourceAspect <= 0f || getContext() == null) return;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        int mediaWidth = frameVideo.getWidth() > 0 ? frameVideo.getWidth()
                : (recyclerHome != null && recyclerHome.getWidth() > 0
                    ? recyclerHome.getWidth() : dm.widthPixels);
        applyFeedMediaAspectStatic(frameVideo, sourceAspect, mediaWidth, dm.heightPixels);
    }

    // FIX: static-context-safe twin of applyFeedMediaAspect(). PostRowHolder
    // is a static nested class (by design, so it never implicitly holds a
    // Fragment reference), so its Glide RequestListener — itself an inner
    // class of that static holder — cannot call the non-static
    // applyFeedMediaAspect() (it has no enclosing HomeFragment instance to
    // call it on: "non-static method ... cannot be referenced from a static
    // context"). This variant takes the width/height it needs as plain
    // params (sourced from the View itself) instead of reading them off the
    // Fragment, so it works from anywhere without needing a Fragment
    // instance. Same math as applyFeedMediaAspect().
    private static void applyFeedMediaAspectStatic(View frameVideo, float sourceAspect,
            int mediaWidth, int screenHeightPx) {
        if (frameVideo == null || sourceAspect <= 0f || mediaWidth <= 0) return;
        float displayAspect = Math.max(4f / 5f, Math.min(1.91f, sourceAspect));
        int targetHeight = Math.round(mediaWidth / displayAspect);
        targetHeight = Math.max(1, Math.min(targetHeight, (int) (screenHeightPx * 0.75f)));
        ViewGroup.LayoutParams lp = frameVideo.getLayoutParams();
        if (lp != null && lp.height != targetHeight) {
            lp.height = targetHeight;
            frameVideo.setLayoutParams(lp);
        }
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
                case ROW_SUGGESTED_CREATORS: {
                    // ★ Chrome + nested adapter built once here — see
                    // SuggestedCreatorsRowHolder doc.
                    FrameLayout container = new FrameLayout(parent.getContext());
                    container.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new SuggestedCreatorsRowHolder(container);
                }
                case ROW_SUGGESTED_REELS: {
                    // ★ Chrome + nested adapter built once here — see
                    // SuggestedReelsRowHolder doc.
                    FrameLayout container = new FrameLayout(parent.getContext());
                    container.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new SuggestedReelsRowHolder(container);
                }
                case ROW_SPONSORED: {
                    // ★ Card chrome built once here — see SponsoredRowHolder doc.
                    FrameLayout container = new FrameLayout(parent.getContext());
                    container.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new SponsoredRowHolder(container);
                }
                case ROW_NEW_POSTS_BANNER: {
                    // ★ Pill built once here — see NewPostsBannerHolder doc.
                    FrameLayout container = new FrameLayout(parent.getContext());
                    container.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new NewPostsBannerHolder(container);
                }
                case ROW_LOADING: {
                    // ★ Spinner built once here — see LoadingRowHolder doc.
                    FrameLayout container = new FrameLayout(parent.getContext());
                    container.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new LoadingRowHolder(container);
                }
                case ROW_EMPTY: {
                    // ★ Empty-state built once here — see EmptyRowHolder doc.
                    FrameLayout container = new FrameLayout(parent.getContext());
                    container.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new EmptyRowHolder(container);
                }
                case ROW_LOAD_MORE_FOOTER: {
                    // ★ Footer spinner built once here — see LoadMoreFooterRowHolder doc.
                    FrameLayout container = new FrameLayout(parent.getContext());
                    container.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
                    return new LoadMoreFooterRowHolder(container);
                }
                default: {
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
                        // ★ PERF: was `cachedX != null ? cachedX : new HashSet<>()` —
                        // allocating a fresh empty HashSet on every single bind
                        // for as long as the real cached set stayed null (e.g.
                        // before the first Firebase liked/saved/followed read
                        // lands). Collections.emptySet() is a shared singleton —
                        // zero allocation, and every caller downstream only
                        // ever calls .contains()/.isEmpty() on it (read-only),
                        // so the shared instance is safe to hand out repeatedly.
                        addFeedPostCard(h, row.postIndex, reel,
                            cachedLikedIds != null ? cachedLikedIds : java.util.Collections.emptySet(),
                            cachedSavedIds != null ? cachedSavedIds : java.util.Collections.emptySet(),
                            cachedMyUidForFeed, cachedFollowedUids != null ? cachedFollowedUids : java.util.Collections.emptySet());
                    }
                    return;
                }
                case ROW_SUGGESTED_CREATORS: {
                    FeedRow row = feedItems.get(position - FEED_HEADER_OFFSET);
                    bindSuggestedCreatorsRowHolder((SuggestedCreatorsRowHolder) holder, row.creatorPool);
                    return;
                }
                case ROW_SUGGESTED_REELS: {
                    int feedIdx = position - FEED_HEADER_OFFSET;
                    FeedRow row = feedItems.get(feedIdx);
                    bindSuggestedReelsRowHolder((SuggestedReelsRowHolder) holder, row.reelPool, () -> {
                        int i = feedItems.indexOf(row);
                        if (i >= 0) {
                            feedItems.remove(i);
                            notifyItemRemoved(FEED_HEADER_OFFSET + i);
                        }
                    });
                    return;
                }
                case ROW_NEW_POSTS_BANNER:
                    bindNewPostsBannerHolder((NewPostsBannerHolder) holder);
                    return;
                case ROW_SPONSORED: {
                    FeedRow row = feedItems.get(position - FEED_HEADER_OFFSET);
                    bindSponsoredRowHolder((SponsoredRowHolder) holder, row.sponsoredAd);
                    return;
                }
                case ROW_LOADING:
                    // Static content; already built once in
                    // LoadingRowHolder's constructor (see onCreateViewHolder).
                    return;
                case ROW_EMPTY:
                case ROW_LOAD_MORE_FOOTER:
                    // Static content; already built once in
                    // EmptyRowHolder's/LoadMoreFooterRowHolder's constructor
                    // (see onCreateViewHolder).
                    return;
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
                            // Cover the Surface before detaching it. Also stop
                            // the player now: waiting for the later IDLE
                            // callback would leave an off-screen decoder
                            // running throughout the fling.
                            if (card.thumbView != null) {
                                card.thumbView.animate().cancel();
                                card.thumbView.setAlpha(1f);
                                card.thumbView.setVisibility(View.VISIBLE);
                            }
                            if (feedPlayer != null) {
                                feedPlayer.setPlayWhenReady(false);
                                feedPlayer.pause();
                            }
                            pauseFeedPhotoAudio();
                            stopProgressTicker();
                            if (card.playerView != null) card.playerView.setPlayer(null);
                            currentPlayingIndex = -1;
                            pausedForVisibility = false;
                        }
                        // ★ Bitmap downsample + reuse: this ViewHolder's
                        // ImageView is about to be rebound to a totally
                        // different post, so whatever first-frame bitmap it
                        // was showing is done being displayed — hand it back
                        // to ReelFirstFrameCache's reuse pool (if it's safe;
                        // see releaseIfEvicted's identity check) instead of
                        // just letting the GC collect it, and clear the
                        // ImageView so the recycled view doesn't keep a
                        // dangling reference to a bitmap that might get
                        // reused/overwritten for a future decode.
                        if (card.thumbView != null && isAdded()) {
                            android.graphics.drawable.Drawable d = card.thumbView.getDrawable();
                            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                                android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                                card.thumbView.setImageDrawable(null);
                                if (card.reelId != null && bmp != null) {
                                    com.callx.app.cache.ReelFirstFrameCache.get(requireContext())
                                        .releaseIfEvicted(card.reelId, bmp);
                                }
                            }
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
        ImageView       ivPostVerified;
        TextView        tvOwner;
        TextView        tvTime;
        TextView        tvAudio;
        TextView        tvSuggested;
        TextView        btnPostFollow;
        ImageView       ivThumb;
        TextView        tvCaption;
        TextView        tvLikedBy;
        TextView        tvCommentPreview;
        TextView        tvTranslate;
        TextView        tvTranslation;
        LinearLayout    layoutTaggedPeople;
        LinearLayout    layoutProductTags;
        View            scrollTaggedPeople;
        View            scrollProductTags;
        TextView        tvLikes;
        TextView        tvComments;
        TextView        tvReposts;
        ImageButton     btnLike;
        ImageButton     btnComment;
        ImageButton     btnRepost;
        ImageButton     btnSave;
        PlayerView      pvFeed;
        ImageView       ivBackdrop;
        FrameLayout     frameVideo;
        View            endOverlay;
        View            watchMore;
        TextView        watchAgain;
        ImageButton     btnMute;
        ImageButton     btnAudioCover;
        ImageButton     btnCollabIcon;
        // ── Collab-row cache (see the collab block in addFeedPostCard()) —
        // populated ONCE, the first time this physical holder ever inflates
        // a collab post's merged row, and reused on every subsequent bind
        // instead of re-walking the view tree / re-registering listeners.
        View            llCollabAuthorsHome;
        com.callx.app.views.CollabAvatarStackView collabStackHome;
        TextView        tvCollabNameHome;
        TextView        tvCollabFollowBtnHome;
        View            llCollabSongRowHome;
        // PERF (URL/text-skip) caches for the collab row — same rebind-skip
        // principle as lastAvatarUrl/lastThumbUrl below: a same-post rebind
        // (live-count tick, like-tap notifyItemChanged) skips re-loading
        // avatars into the stack / rebuilding the "@owner and N others"
        // SpannableString when nothing actually changed since last bind.
        final String[]  lastCollabAvatarUrls = new String[3];
        int             lastCollabStackCount = -1;
        String          lastCollabNameTextHome;
        SeekBar         sbProgress;
        TextView        tvPosition;
        TextView        tvSpeedChip;
        View            playOverlay;
        View            collabAvatarContainer;
        View            llPostOwnerRow;
        View            btnReadMore;
        View            btnSend;
        TextView        tvSends;
        View            btnMore;

        // ── Photo-slideshow chrome (lazily created, reused across binds) ──
        // ★ Instagram-level PERF: these used to be a brand-new ViewPager2 +
        // brand-new anonymous RecyclerView.Adapter + brand-new dots
        // LinearLayout allocated (and measured/laid-out) on EVERY single
        // bind of a photo-slideshow card — paid again every time the same
        // physical row view scrolled back into view. Now built ONCE per
        // physical holder (first photo post it ever binds) and reused for
        // every subsequent photo post that lands on this holder: rebinding
        // just swaps photoPagerData's contents + notifyDataSetChanged(),
        // exactly the same "cache the view, refresh the data" pattern
        // cacheViews()/PostRowHolder already uses for every other view.
        ViewPager2 photoPager;
        LinearLayout photoDots;
        /** New container BELOW frame_video (Instagram-style) that now hosts
         *  photoDots — replaces the old bottom-overlaid-on-video placement. */
        FrameLayout framePhotoDotsRow;
        final List<String> photoPagerData = new ArrayList<>();
        RecyclerView.Adapter<RecyclerView.ViewHolder> photoPagerAdapter;
        /** One reusable GradientDrawable per currently-inflated dot — mutated
         *  (setColor) on page-select instead of allocating a new Drawable
         *  per dot on every single page change. Rebuilt only when the dot
         *  COUNT changes (i.e. a different photo post's photo count). */
        android.graphics.drawable.GradientDrawable[] photoDotDrawables;
        int photoDotCount = -1;

        // ── Listener-reuse bound state ───────────────────────────────────
        // ★ Instagram-level PERF: watchMore/watchAgain/btnMute/
        // btnAudioCover/tvAudio/btnPostFollow's OnClickListeners used to be
        // a fresh lambda object allocated on EVERY single bind, each one
        // capturing that bind's `reel`/`cardIndex`/`myUid`/`ownerUidRef` via
        // closure. Now registered ONCE per physical holder (see
        // clickListenersBound below) — the shared listener reads whichever
        // reel/cardIndex/uid is CURRENTLY bound off these fields, which are
        // just a handful of cheap field writes on every bind instead of a
        // fresh listener allocation.
        ReelModel boundReel;
        int boundCardIndex = -1;
        String boundMyUid;
        String boundOwnerUidRef;
        final boolean[] boundIsFollowed = {false};
        boolean clickListenersBound = false;

        // ── Action-bar / header listener-reuse state ─────────────────────
        // ★ Instagram-level PERF (action-bar pass): avatar/tvOwner/ivThumb
        // and the like/comment/repost/save/send/more buttons used to get a
        // brand-new OnClickListener lambda allocated on EVERY single bind
        // (each one capturing that bind's reel/reelId/ownerUid/myUid via
        // closure) — exactly the per-bind allocation cost already fixed for
        // watchMore/watchAgain/btnMute/etc. above. Same fix, same pattern:
        // registered ONCE per physical holder (see actionBarListenersBound),
        // the shared listener reads holder.boundReel/boundMyUid at click
        // time instead of a captured local. This flag is separate from
        // clickListenersBound because it's set true further down the bind
        // method (clickListenersBound already flips true earlier, before
        // this section runs, so it can't be reused as the gate here).
        boolean actionBarListenersBound = false;
        // Caption "…more/less" toggle — see the btnReadMore block above.
        // captionExpanded tracks the CURRENT bind's expand/collapse state
        // (reset to false at the top of every bind, same as the old local
        // boolean[]); readMoreListenerBound gates the one-time listener
        // registration, same pattern as actionBarListenersBound.
        boolean captionExpanded = false;
        boolean readMoreListenerBound = false;
        // Like/save toggle state now lives on the holder (was a per-bind
        // boolean[] closure) so the once-registered button listeners can
        // read + flip it directly instead of needing a fresh capture.
        boolean boundIsLiked = false;
        boolean boundIsSaved = false;
        // ★ Instagram-level PERF: gates the video-frame double-tap-like /
        // tap-to-open / hold-to-2x GestureDetector so it's registered ONCE
        // per physical holder instead of a fresh GestureDetector + touch
        // listener on every bind (see the frameVideo.setOnTouchListener
        // block in bindPostRow()). Same pattern as clickListenersBound
        // above; the photo-slideshow double-tap listener reuses
        // firstTimeOnThisHolder as its own equivalent one-time gate.
        boolean frameVideoGestureBound = false;
        // Reusable pill pools for tagged-people / product-tag rows — see
        // bindTaggedPeople()/bindProductTags(): pills are created (and their
        // click listener registered) ONCE per pool slot, then just have
        // their text/tag/visibility refreshed on every subsequent bind
        // instead of removeAllViews()+addView(new TextView) every time.
        final java.util.List<TextView> taggedPeoplePillPool = new java.util.ArrayList<>();
        final java.util.List<TextView> productTagPillPool = new java.util.ArrayList<>();

        // ── Small per-bind allocation caches ──────────────────────────────
        // ★ Instagram-level PERF (final pass): the three remaining
        // per-bind allocations flagged in the review — owner-name concat,
        // formatAgo() string churn, and thumb/avatar Glide RequestBuilder
        // chains — all get skipped when a rebind lands on this holder with
        // the same source values (e.g. a like-tap's notifyItemChanged
        // rebinding the SAME reel to the SAME row, not a new reel scrolling
        // in). Different reel / different URL / enough time elapsed still
        // recompute exactly as before.
        String lastOwnerNameSrc;
        String lastOwnerLabel;
        long   lastAgoTs = Long.MIN_VALUE;
        long   lastAgoComputedAtMs = -1;
        String lastAgoStr;
        String lastThumbUrl;
        String lastBackdropUrl;
        boolean lastBackdropNeeded;
        String lastAvatarUrl;
        RequestListener<Drawable> thumbLoadListener;
        // ★ Instagram-level PERF: same same-URL-skip principle as
        // lastThumbUrl/lastAvatarUrl above, for the music/sound cover tile
        // (btnAudioCover) — see the "Audio-cover tile" block in
        // addFeedPostCard(). Was allocating a fresh Glide RequestBuilder
        // chain (load/apply/transform/override/into) on EVERY bind even
        // when the exact same cover URL was already showing.
        String lastAudioCoverUrl;
        // ★ Instagram-level PERF: same same-URL-skip principle, for the
        // collab-post dual-avatar loads (av2 collaborator photo + the main
        // avatar's initiator photo) — see the collab branch in
        // addFeedPostCard(). Was allocating a fresh Glide chain for BOTH
        // avatars on EVERY bind even when the same reel rebinds unchanged.
        String lastCollabCollaboratorPhoto;
        String lastCollabInitiatorPhoto;
        // ★ Instagram-level PERF: story-ring rebind-skip. The gradient ring
        // Drawable itself was already a SHARED instance (see
        // StoryRingGradientDrawable's v42 PERF PASS — computeIfAbsent keyed
        // by stroke width, never re-allocated), but setBackground()/
        // setImageDrawable()/setVisibility() were still being called on
        // EVERY single bind even when the same uid rebinds with the exact
        // same seen/unseen story state (e.g. a like-tap's
        // notifyItemChanged) — each call still forces an invalidate +
        // padding recompute. Skip those calls entirely when nothing
        // actually changed since this holder's last bind.
        String lastStoryRingUid;
        int lastStoryRingState = -1; // -1 = never bound, 0 = none, 1 = seen, 2 = unseen
        // ★ Instagram-level PERF: guards the RARE fallback branch of the
        // likes/comments/reposts count text (see the precomputedUi==null
        // branch below) — the common path already skips recompute via
        // ReelUiStateCache's precompute, but the fallback used to call
        // formatCount() + setText() unconditionally on every fallback
        // bind even when the same reel's counts hadn't changed since this
        // holder's last fallback bind.
        int lastFallbackLikesCount = Integer.MIN_VALUE;
        int lastFallbackCommentsCount = Integer.MIN_VALUE;
        int lastFallbackRepostCount = Integer.MIN_VALUE;
        // ★ Instagram-level PERF: caches the legacy dual-author collab
        // header's "InitiatorName ∧ CollaboratorName" label — see the
        // isCollab branch in addFeedPostCard(). Same skip principle as
        // lastOwnerNameSrc/lastOwnerLabel below (that pair only covers the
        // solo-author path); this pair was still re-concatenating on
        // every single bind.
        String lastCollabLabelInitiator;
        String lastCollabLabelCollaborator;
        String lastCollabLabelText;
        // ★ Instagram-level PERF: caches the last reel this holder actually
        // fetched social-proof / comment-preview data FOR, so a same-reel
        // rebind (notifyItemChanged from a like tap, or a half-screen
        // scroll-and-back) reapplies the cached text instead of firing a
        // fresh Firebase read every single bind. null label/text = "fetched,
        // nothing to show" (still skips re-fetching). Reset to null only
        // when a genuinely different reel lands on this holder.
        String lastLikedByReelId;
        String lastLikedByLabel;
        String lastCommentPreviewReelId;
        String lastCommentPreviewText;

        PostRowHolder(@NonNull View itemView) { super(itemView); }

        /** Runs once per physical inflated instance — NOT once per bind. */
        void cacheViews() {
            if (viewsCached) return;
            avatar                = itemView.findViewById(R.id.iv_post_avatar);
            ivPostStoryRing       = itemView.findViewById(R.id.iv_post_story_ring);
            ivPostVerified        = itemView.findViewById(R.id.iv_post_verified);
            tvOwner               = itemView.findViewById(R.id.tv_post_owner);
            tvTime                = itemView.findViewById(R.id.tv_post_time);
            tvAudio               = itemView.findViewById(R.id.tv_post_audio);
            tvSuggested           = itemView.findViewById(R.id.tv_post_suggested);
            btnPostFollow         = itemView.findViewById(R.id.btn_post_follow);
            ivThumb               = itemView.findViewById(R.id.iv_post_thumb);
            tvCaption             = itemView.findViewById(R.id.tv_post_caption);
            tvLikedBy             = itemView.findViewById(R.id.tv_post_liked_by);
            tvCommentPreview      = itemView.findViewById(R.id.tv_post_comment_preview);
            tvTranslate            = itemView.findViewById(R.id.tv_post_translate);
            tvTranslation         = itemView.findViewById(R.id.tv_post_translation);
            layoutTaggedPeople     = itemView.findViewById(R.id.layout_post_tagged_people);
            layoutProductTags      = itemView.findViewById(R.id.layout_post_product_tags);
            scrollTaggedPeople     = itemView.findViewById(R.id.scroll_post_tagged_people);
            scrollProductTags      = itemView.findViewById(R.id.scroll_post_product_tags);
            tvLikes               = itemView.findViewById(R.id.tv_post_likes);
            tvComments            = itemView.findViewById(R.id.tv_post_comments);
            tvReposts             = itemView.findViewById(R.id.tv_post_reposts);
            btnLike               = itemView.findViewById(R.id.btn_post_like);
            btnComment            = itemView.findViewById(R.id.btn_post_comment);
            btnRepost             = itemView.findViewById(R.id.btn_post_repost);
            btnSave               = itemView.findViewById(R.id.btn_post_save);
            pvFeed                = itemView.findViewById(R.id.pv_feed_post);
            // ★ Instagram-level fix: PlayerView's default shutter is opaque
            // BLACK. It paints over the surface the instant the player is
            // attached/prepared and stays until a real frame is decoded —
            // sitting UNDER ivThumb (z-order: shutter, then thumb on top),
            // so normally it's invisible. But the thumb's direct reveal
            // (revealCardThumbnailAfterFirstFrame) happens exactly on
            // onRenderedFirstFrame, which is the same instant the shutter
            // itself is cleared — any tiny scheduling gap between "shutter
            // cleared" and "thumb is hidden" exposes a flash of
            // solid black for a frame, which reads exactly as "thumbnail
            // hata video baad me play hoti hai" instead of one continuous
            // image. ReelPlayerController (Reels tab) already sets this to
            // TRANSPARENT; Home Feed's pvFeed never did, so this row-level
            // player showed that flash on every card while the Reels tab —
            // which also uses a transparent shutter — never did. Same
            // instance, set once at ViewHolder construction (matches Reels
            // tab, which sets it once at player-view bind time too).
            pvFeed.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT);
            pvFeed.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            ivBackdrop            = itemView.findViewById(R.id.iv_feed_media_backdrop);
            if (ivBackdrop != null) ivBackdrop.setVisibility(View.GONE);
            frameVideo            = itemView.findViewById(R.id.frame_video);
            // Instagram-style Home cards keep the author header ABOVE the
            // media. The XML keeps this row inside frame_video so all existing
            // cached IDs/ViewStub bindings remain intact; move it once when
            // the physical holder is created, before the first bind.
            View homeHeader = itemView.findViewById(R.id.overlay_post_header);
            if (homeHeader != null && homeHeader.getParent() == frameVideo
                    && itemView instanceof ViewGroup) {
                ViewGroup cardRoot = (ViewGroup) itemView;
                int mediaIndex = cardRoot.indexOfChild(frameVideo);
                frameVideo.removeView(homeHeader);
                homeHeader.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                cardRoot.addView(homeHeader, Math.max(0, mediaIndex));
            }
            // Reuse one listener for this physical holder. Creating a new
            // anonymous RequestListener for every new thumbnail URL causes
            // avoidable churn during a long feed fling.
            thumbLoadListener = new RequestListener<Drawable>() {
                @Override
                public boolean onLoadFailed(@Nullable GlideException e, Object model,
                        Target<Drawable> target, boolean isFirstResource) {
                    return false;
                }

                @Override
                public boolean onResourceReady(Drawable resource, Object model,
                        Target<Drawable> target, DataSource dataSource,
                        boolean isFirstResource) {
                    // Ignore a late decode from a previous bind after this
                    // recycled holder has already requested another URL.
                    if (resource != null
                            && java.util.Objects.equals(String.valueOf(model), lastThumbUrl)
                            && resource.getIntrinsicWidth() > 0
                            && resource.getIntrinsicHeight() > 0
                            && frameVideo != null
                            && frameVideo.getContext() != null) {
                        int fvWidth = frameVideo.getWidth() > 0 ? frameVideo.getWidth()
                                : frameVideo.getContext().getResources()
                                    .getDisplayMetrics().widthPixels;
                        int screenHeightPx = frameVideo.getContext().getResources()
                                .getDisplayMetrics().heightPixels;
                        applyFeedMediaAspectStatic(frameVideo,
                                resource.getIntrinsicWidth()
                                    / (float) resource.getIntrinsicHeight(),
                                fvWidth, screenHeightPx);
                    }
                    return false;
                }
            };
            framePhotoDotsRow     = itemView.findViewById(R.id.frame_photo_dots_row);
            endOverlay            = itemView.findViewById(R.id.layout_end_of_reel_card);
            watchMore             = itemView.findViewById(R.id.btn_watch_more_card);
            watchAgain            = itemView.findViewById(R.id.btn_watch_again_card);
            btnMute               = itemView.findViewById(R.id.btn_post_mute);
            btnAudioCover         = itemView.findViewById(R.id.btn_post_audio_cover);
            btnCollabIcon         = itemView.findViewById(R.id.btn_post_collab_icon);
            sbProgress            = itemView.findViewById(R.id.sb_post_progress);
            tvPosition            = itemView.findViewById(R.id.tv_post_position);
            tvSpeedChip           = itemView.findViewById(R.id.tv_post_speed_chip);
            playOverlay           = itemView.findViewById(R.id.btn_post_play_overlay);
            collabAvatarContainer = itemView.findViewById(R.id.layout_collab_avatar);
            llPostOwnerRow        = itemView.findViewById(R.id.ll_post_owner_row);
            btnReadMore           = itemView.findViewById(R.id.tv_post_read_more);
            btnSend               = itemView.findViewById(R.id.btn_post_send);
            tvSends               = itemView.findViewById(R.id.tv_post_sends);
            btnMore               = itemView.findViewById(R.id.btn_post_more);
            viewsCached = true;
        }
    }
}
