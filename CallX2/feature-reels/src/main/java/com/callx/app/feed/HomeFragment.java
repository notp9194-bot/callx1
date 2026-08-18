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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
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
    private LinearLayout       containerFeed;
    private LinearLayout       containerTrending;
    private LinearLayout       containerFriendsActivity;
    private LinearLayout       containerContinueWatching;
    private LinearLayout       containerSuggestedCreators;
    private ProgressBar        pbFeedLoading;
    private ProgressBar        pbTrending;
    private ProgressBar        pbActivity;
    private ProgressBar        pbContinue;
    private ProgressBar        pbSuggested;
    private TextView           tvFeedEmpty;
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
    private NestedScrollView   scrollView;
    private final List<HomeFeedCard> feedCards = new ArrayList<>();
    private int                currentPlayingIndex = -1;
    private boolean            isMuted = false;
    private final Handler      scrollHandler = new Handler(Looper.getMainLooper());
    private Runnable           scrollRunnable;

    // ── v177: preload feature (same as Reels tab) ──────────────────────────
    // Preloads upcoming videos/thumbnails a couple cards ahead of whichever
    // card is currently playing, so by the time the user scrolls to them
    // they're already sitting in the shared cache (same one buildCachedMediaSource
    // reads from) — no spinner, no wait, no fresh download.
    private com.callx.app.cache.ReelVideoPreloader       videoPreloader;
    private com.callx.app.cache.ReelThumbnailPreloader   thumbPreloader;
    private com.callx.app.cache.ReelPredictivePreloader  predictivePreloader;
    /** Manual view-virtualization for the feed's NestedScrollView — see class doc.
     *  Unloads off-screen card bitmaps and pauses Glide during fling so image
     *  decode work never competes with the scroll frame budget. */
    private HomeFeedWindowManager feedWindowManager;
    /** The exact list of posts currently backing feedCards, index-aligned with it. */
    private List<ReelModel> currentFeedPosts = new ArrayList<>();

    /** Lightweight holder for each inline feed card. */
    private static class HomeFeedCard {
        View      rootView;
        PlayerView playerView;
        ImageView  thumbView;
        View       endOverlay;
        String     videoUrl;
        String     reelId;
        /** Guards revealThumbnailAfterFirstFrame() against double-firing —
         *  reset to false every time this card becomes the active one. */
        boolean    firstFrameRevealed;
    }

    /** Same value ReelPlayerController (Reels tab) uses — short enough that
     *  the surface hand-off reads as one continuous frame instead of a
     *  visible cut, matching Instagram's near-zero thumbnail→video swap. */
    private static final long HOME_THUMB_CROSSFADE_MS = 80L;

    private boolean isFollowingMode = true;

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
    private Runnable  scrollSettleRunnable      = null;
    private View      feedScrollContentRoot     = null;

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
        // ── Initialise the single shared ExoPlayer for inline feed playback ──
        // ★ Built via AdaptiveStreamingManager.buildBarePlayer() instead of a
        // plain ExoPlayer.Builder() — gets the SAME network-tier-tuned
        // LoadControl (short start buffer, e.g. ~800ms bufferForPlaybackMs on
        // WiFi vs ExoPlayer's slower ~2500ms default) and shared
        // THREAD_PRIORITY_DISPLAY playback thread the Reels swipe feed
        // already uses for fast, low-latency start — Home's feed was
        // previously left on generic defaults.
        feedPlayer = com.callx.app.player.AdaptiveStreamingManager.get(requireContext()).buildBarePlayer();
        configureFeedPlayerInstance(feedPlayer);
        // ── v177: same preload feature Reels tab has ──────────────────────
        videoPreloader      = new com.callx.app.cache.ReelVideoPreloader(requireContext());
        thumbPreloader      = new com.callx.app.cache.ReelThumbnailPreloader(requireContext());
        predictivePreloader = new com.callx.app.cache.ReelPredictivePreloader(requireContext());
        feedWindowManager   = new HomeFeedWindowManager(scrollView, Glide.with(this));
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
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && currentPlayingIndex >= 0
                        && currentPlayingIndex < feedCards.size()) {
                    HomeFeedCard card = feedCards.get(currentPlayingIndex);
                    if (card.endOverlay != null && isAdded()) {
                        requireActivity().runOnUiThread(
                            () -> card.endOverlay.setVisibility(View.VISIBLE));
                    }
                }
            }
            @Override
            public void onRenderedFirstFrame() {
                // Instagram-style handoff: reveal the card's thumbnail crossfade
                // HERE — the first actually-decoded frame on screen — not at
                // player-attach time (STATE_READY can land a frame or two
                // before anything is visibly drawn, which is what caused the
                // old attach-time fade to expose a visible "jump" between the
                // static thumb and a still-blank/half-buffered surface).
                if (currentPlayingIndex >= 0 && currentPlayingIndex < feedCards.size()) {
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
        });
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
        containerStories          = v.findViewById(R.id.container_stories);
        containerFeed             = v.findViewById(R.id.container_feed);
        containerTrending         = v.findViewById(R.id.container_trending);
        containerFriendsActivity  = v.findViewById(R.id.container_friends_activity);
        containerContinueWatching = v.findViewById(R.id.container_continue_watching);
        containerSuggestedCreators= v.findViewById(R.id.container_suggested_creators);
        pbFeedLoading             = v.findViewById(R.id.pb_feed_loading);
        pbTrending                = v.findViewById(R.id.pb_trending);
        pbActivity                = v.findViewById(R.id.pb_activity);
        pbContinue                = v.findViewById(R.id.pb_continue);
        pbSuggested               = v.findViewById(R.id.pb_suggested);
        tvFeedEmpty               = v.findViewById(R.id.tv_feed_empty);
        btnHomeFollowing          = v.findViewById(R.id.btn_home_following);
        btnHomeForYou             = v.findViewById(R.id.btn_home_for_you);
        vFeedIndicator            = v.findViewById(R.id.v_feed_indicator);
        btnSeeAllTrending         = v.findViewById(R.id.btn_see_all_trending);
        btnClearHistory           = v.findViewById(R.id.btn_clear_history);
        btnAddStory               = v.findViewById(R.id.btn_add_story);
        btnHomeUpload             = v.findViewById(R.id.btn_home_upload);
        ivMyStoryAvatar           = v.findViewById(R.id.iv_my_story_avatar);
        // Instagram-style header
        tvFeedTitle               = v.findViewById(R.id.tv_feed_title);
        btnNewPost                = v.findViewById(R.id.btn_new_post);
        btnNotifications          = v.findViewById(R.id.btn_notifications);
        // NestedScrollView for scroll-triggered auto-play
        scrollView                = v.findViewById(R.id.nested_scroll_home);
        // The single LinearLayout NestedScrollView wraps — this is what gets
        // promoted to a hardware layer while actively scrolling.
        if (scrollView != null && scrollView.getChildCount() > 0) {
            feedScrollContentRoot = scrollView.getChildAt(0);
        }
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
        if (scrollView != null) {
            scrollView.setOnScrollChangeListener(
                (NestedScrollView.OnScrollChangeListener) (sv, sx, sy, osx, osy) -> {
                    if (scrollRunnable != null) scrollHandler.removeCallbacks(scrollRunnable);
                    scrollRunnable = this::playMostVisibleCard;
                    scrollHandler.postDelayed(scrollRunnable, 120);

                    // ★ Buttery scroll: promote the whole scrolling content
                    // to a hardware layer the instant motion starts, so the
                    // GPU composites it as one cached texture per frame
                    // instead of Android re-drawing every avatar/thumbnail/
                    // text view individually on every pixel of scroll. Drop
                    // back to a normal layer ~180ms after motion stops (fling
                    // settle) since an always-on hardware layer just wastes
                    // GPU memory once the user is reading, not scrolling.
                    beginFeedScrollLayer();
                    // ★ Pause all Glide decode/dispatch work for the duration
                    // of the scroll — see HomeFeedWindowManager. Resumed, and
                    // off-screen card bitmaps re-windowed, on the same settle
                    // timer as the hardware-layer drop below.
                    if (feedWindowManager != null) feedWindowManager.onScrollStarted();
                    if (scrollSettleRunnable != null) scrollHandler.removeCallbacks(scrollSettleRunnable);
                    scrollSettleRunnable = () -> {
                        endFeedScrollLayer();
                        if (feedWindowManager != null) feedWindowManager.onScrollSettled();
                    };
                    scrollHandler.postDelayed(scrollSettleRunnable, 180);

                    // ★ Instagram-style infinite scroll: once the user is
                    // within ~600dp of the bottom of the scrollable content,
                    // silently fetch the next page from Firebase and append
                    // it — the feed never hits a hard "end", same as IG.
                    View content = sv.getChildAt(0);
                    if (content != null) {
                        int remaining = content.getBottom() - (sv.getHeight() + sv.getScrollY());
                        if (remaining < dpToPx(600) && !isLoadingMoreFeed && feedHasMore) {
                            loadMoreFeedPosts();
                        }
                        // Prefetch thumbnails/video for the *next* page a bit
                        // earlier than the fetch trigger itself, so by the
                        // time those cards actually scroll into view their
                        // media is already warm in cache — removes the
                        // "pop-in" jank new content otherwise causes.
                        if (remaining < dpToPx(1400) && remaining >= dpToPx(600)) {
                            prefetchUpcomingFeedMedia();
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
        feedScrollContentRoot.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    private void endFeedScrollLayer() {
        if (!isFeedScrolling || feedScrollContentRoot == null) return;
        isFeedScrolling = false;
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
        // Warms Glide's cache for thumbnails of the next few not-yet-rendered
        // posts so they're already decoded by the time their cards scroll in.
        int upTo = Math.min(currentFeedPosts.size(), fromIndex + 4);
        for (int i = fromIndex; i < upTo; i++) {
            ReelModel r = currentFeedPosts.get(i);
            if (r == null) continue;
            String thumb = r.effectiveThumbUrl();
            if (!thumb.isEmpty()) {
                Glide.with(requireContext()).load(thumb).apply(FEED_IMAGE_OPTS)
                        .override(720, 720).preload();
            }
        }
        // Reuses the same byte-range video preloader the Reels swipe feed
        // uses, pointed at the upcoming slice of the ranked feed list.
        if (videoPreloader != null) {
            videoPreloader.preloadFrom(currentFeedPosts, fromIndex);
        }
    }

    // ── Auto-play helpers ─────────────────────────────────────────────────

    /**
     * Walk all tracked feed cards; find the one with the largest visible area
     * on screen, then attach the shared ExoPlayer to it.
     */
    private void playMostVisibleCard() {
        if (!isAdded() || feedPlayer == null || feedCards.isEmpty()) return;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int bestIdx = -1;
        int bestPx  = 0;
        for (int i = 0; i < feedCards.size(); i++) {
            View root = feedCards.get(i).rootView;
            if (root == null || root.getHeight() == 0) continue;
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            int cardTop = loc[1];
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
        if (currentPlayingIndex >= 0 && currentPlayingIndex < feedCards.size()) {
            HomeFeedCard old = feedCards.get(currentPlayingIndex);
            if (old.playerView != null) old.playerView.setPlayer(null);
            if (old.endOverlay  != null) old.endOverlay.setVisibility(View.GONE);
        }
        currentPlayingIndex = index;
        HomeFeedCard card = feedCards.get(index);
        attachStartTimeMs = System.currentTimeMillis(); // TTFF measurement start

        // ── v177: preload the next few cards' video + thumbnails ahead of
        // time, same as Reels tab does on every onPageSelected. Uses the
        // SAME UnifiedVideoCacheManager.Module.REELS cache buildCachedMediaSource
        // reads from, so by the time the user scrolls here it's cache-hot.
        if (!currentFeedPosts.isEmpty() && index < currentFeedPosts.size()) {
            if (videoPreloader      != null) videoPreloader.preloadFrom(currentFeedPosts, index);
            if (thumbPreloader      != null) thumbPreloader.preloadFrom(currentFeedPosts, index);
            if (predictivePreloader != null) predictivePreloader.preloadSmartFrom(currentFeedPosts, index);
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
        feedPlayer.setVolume(isMuted ? 0f : 1f);
        feedPlayer.setPlayWhenReady(true);
        feedPlayer.play();

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
        if (next.videoUrl == null || next.videoUrl.isEmpty()) return;
        if (standbyNextIndex == nextIndex && next.videoUrl.equals(standbyNextUrl)) return; // already prepared

        if (standbyNextPlayer == null) {
            standbyNextPlayer = com.callx.app.player.AdaptiveStreamingManager.get(requireContext()).buildBarePlayer();
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
        if (prev.videoUrl == null || prev.videoUrl.isEmpty()) return;
        if (standbyPrevIndex == prevIndex && prev.videoUrl.equals(standbyPrevUrl)) return; // already prepared

        if (standbyPrevPlayer == null) {
            standbyPrevPlayer = com.callx.app.player.AdaptiveStreamingManager.get(requireContext()).buildBarePlayer();
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
            if (currentPlayingIndex >= 0) feedPlayer.play();
            else if (!feedCards.isEmpty()) scrollHandler.postDelayed(this::playMostVisibleCard, 300);
        }
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
            if (currentPlayingIndex >= 0) feedPlayer.play();
            else if (!feedCards.isEmpty()) scrollHandler.postDelayed(this::playMostVisibleCard, 300);
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
        if (feedPlayer != null) feedPlayer.pause();
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        stopRealtimeNewPostsListener();
        if (scrollRunnable != null) scrollHandler.removeCallbacks(scrollRunnable);
        if (scrollSettleRunnable != null) scrollHandler.removeCallbacks(scrollSettleRunnable);
        feedScrollContentRoot = null;
        if (feedPlayer != null) { feedPlayer.release(); feedPlayer = null; }
        if (standbyNextPlayer != null) { standbyNextPlayer.release(); standbyNextPlayer = null; }
        if (standbyPrevPlayer != null) { standbyPrevPlayer.release(); standbyPrevPlayer = null; }
        standbyNextIndex = -1; standbyNextUrl = null;
        standbyPrevIndex = -1; standbyPrevUrl = null;
        attachStartTimeMs = 0L;
        if (videoPreloader != null) { videoPreloader.shutdown(); videoPreloader = null; }
        thumbPreloader      = null;
        predictivePreloader = null;
        feedCards.clear();
        currentFeedPosts = new ArrayList<>();
        currentPlayingIndex = -1;
        if (suggestedReelsPeekController != null) {
            suggestedReelsPeekController.dismiss();
            suggestedReelsPeekController = null;
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
        if (containerFeed != null) containerFeed.removeAllViews();
        if (feedWindowManager != null) feedWindowManager.reset();
        showFeedLoading(true);
        showFeedEmpty(false);
        loadFeed();
    }

    /** Clears pagination/ranking/real-time state before a fresh feed load
     *  (mode switch, pull-to-refresh, or "N new posts" tap). */
    private void resetFeedPaginationState() {
        stopRealtimeNewPostsListener();
        isLoadingMoreFeed   = false;
        feedHasMore         = true;
        oldestFeedTimestamp = null;
        newestFeedTimestamp = null;
        renderedReelIds.clear();
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
        if (containerFeed != null)             containerFeed.removeAllViews();
        if (feedWindowManager != null)         feedWindowManager.reset();
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
        if (!isAdded() || getContext() == null) return;
        if (index >= uids.size() || index >= 15) {
            // Sort: unseen first, then seen
            collected.sort((a, b) -> Boolean.compare(!a.hasUnseen, !b.hasUnseen));
            for (StoryEntry entry : collected) addStoryView(entry);
            return;
        }
        String uid = uids.get(index);

        FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded() || getContext() == null) return;
                String name  = snap.child("name").getValue(String.class);
                String _photo = snap.child("photoUrl").getValue(String.class);
                String _thumb = snap.child("thumbUrl").getValue(String.class);
                String photo = (_thumb != null && !_thumb.isEmpty()) ? _thumb : _photo;

                FirebaseUtils.getUserStatusRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot statusSnap) {
                        if (!isAdded() || getContext() == null) return;
                        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
                        boolean hasActive = false;
                        boolean allSeen   = true;
                        Set<String> mySeenForOwner = seenMap.containsKey(uid) ? seenMap.get(uid) : new HashSet<>();

                        for (DataSnapshot s : statusSnap.getChildren()) {
                            Long ts = s.child("timestamp").getValue(Long.class);
                            if (ts == null || ts <= cutoff) continue;
                            hasActive = true;
                            if (mySeenForOwner == null || !mySeenForOwner.contains(s.getKey())) {
                                allSeen = false; // at least one unseen
                            }
                        }

                        if (hasActive) {
                            // ★ INSTAGRAM FIX: gradient ring is driven purely by
                            // "has this been seen" — a story used to keep its
                            // gradient forever once it was tagged "reel_story",
                            // even after the viewer had already watched it. Now
                            // it's just unseen-state, same as every other story
                            // type — matches Instagram's actual behavior.
                            collected.add(new StoryEntry(uid, name, photo, !allSeen, false));
                        }
                        collectStoryEntries(uids, index + 1, seenMap, collected);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        collectStoryEntries(uids, index + 1, seenMap, collected);
                    }
                });
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                collectStoryEntries(uids, index + 1, seenMap, collected);
            }
        });
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
            if (containerFeed == null || !isAdded()) return;
            containerFeed.removeAllViews();
            feedCards.clear();
            if (feedWindowManager != null) feedWindowManager.reset();
            renderedReelIds.clear();
            postsSincePeopleYouMayLike = 0;
            postsSinceSuggestedReels = 0;
            currentFeedPosts = posts;
            currentPlayingIndex = -1;
            // No more artificial 10-item cap — this page is already bounded
            // by FEED_FETCH_BATCH, and infinite scroll appends further pages
            // as the user nears the bottom instead of ever showing an "end".
            int count = posts.size();
            // PERF: stagger card creation across frames. Inflating a card +
            // dispatching its avatar/thumb Glide requests is real work; doing
            // them all in one runOnUiThread block competes for the same 16ms
            // frame budget and is the main cause of a visible stutter when
            // opening Home or switching Following/For You. The first 3 cards
            // (~one screen) still render immediately so content is visible
            // instantly; the rest are added one-per-frame via postDelayed.
            int immediate = Math.min(count, 3);
            for (int i = 0; i < immediate; i++) {
                renderOneFeedItem(posts.get(i), likedIds, savedIds, myUid, followedUids);
            }
            for (int i = immediate; i < count; i++) {
                final int idx = i;
                containerFeed.postDelayed(() -> {
                    if (!isAdded() || containerFeed == null) return;
                    renderOneFeedItem(posts.get(idx), likedIds, savedIds, myUid, followedUids);
                }, (long) (idx - immediate + 1) * 16L);
            }
            // Auto-play first visible card after layout
            containerFeed.post(() -> {
                if (scrollRunnable != null) scrollHandler.removeCallbacks(scrollRunnable);
                scrollHandler.postDelayed(this::playMostVisibleCard, 400);
            });
            // Start listening for brand-new posts published while the user
            // is sitting on Home — real background updates, not just
            // pull-to-refresh, same as Instagram's live feed.
            startRealtimeNewPostsListener();
        });
    }

    /**
     * Renders a single feed slot: the post card itself, plus — every
     * SUGGESTED_EVERY_N_POSTS posts, in For-You mode only — an inline
     * "Suggested for you" creators row mixed directly into the scroll
     * (Instagram doesn't box these off separately; they're interleaved).
     */
    private void renderOneFeedItem(ReelModel reel, Set<String> likedIds, Set<String> savedIds,
                                    String myUid, Set<String> followedUids) {
        if (reel == null || reel.reelId == null || renderedReelIds.contains(reel.reelId)) return;
        renderedReelIds.add(reel.reelId);
        addFeedPostCard(reel, likedIds, savedIds, myUid, followedUids);

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
     * re-fetching it, and simply extends currentFeedPosts + the container.
     */
    /**
     * Appends the next page of the feed (called by infinite scroll). Reuses
     * the cached liked/saved/follow state from the initial render instead of
     * re-fetching it, and simply extends currentFeedPosts + the container.
     * Rendered one-per-frame (like the very first page) instead of all at
     * once — inflating + dispatching Glide/ExoPlayer work for a whole batch
     * synchronously is exactly the kind of frame-budget spike that causes a
     * visible stutter right as new content lands mid-scroll.
     */
    private void appendFeedPage(List<ReelModel> newPosts) {
        if (!isAdded() || containerFeed == null) return;
        List<ReelModel> merged = new ArrayList<>(currentFeedPosts);
        merged.addAll(newPosts);
        currentFeedPosts = merged;
        for (int i = 0; i < newPosts.size(); i++) {
            final ReelModel r = newPosts.get(i);
            containerFeed.postDelayed(() -> {
                if (!isAdded() || containerFeed == null) return;
                renderOneFeedItem(r, cachedLikedIds, cachedSavedIds, cachedMyUidForFeed, cachedFollowedUids);
            }, (long) i * 16L);
        }
        isLoadingMoreFeed = false;
        showFeedFooterLoading(false);
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
    private void showFeedFooterLoading(boolean show) {
        if (containerFeed == null || !isAdded() || getContext() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (containerFeed == null || !isAdded()) return;
            if (show) {
                if (feedLoadMoreFooter == null) {
                    ProgressBar pb = new ProgressBar(requireContext());
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48));
                    lp.gravity = android.view.Gravity.CENTER;
                    lp.topMargin = dpToPx(8);
                    lp.bottomMargin = dpToPx(8);
                    pb.setLayoutParams(lp);
                    feedLoadMoreFooter = pb;
                }
                if (feedLoadMoreFooter.getParent() == null) containerFeed.addView(feedLoadMoreFooter);
            } else if (feedLoadMoreFooter != null && feedLoadMoreFooter.getParent() != null) {
                containerFeed.removeView(feedLoadMoreFooter);
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
        if (!isAdded() || getContext() == null || containerFeed == null) return;
        if (suggestedCreatorPool != null) {
            buildInlineSuggestedRow(suggestedCreatorPool);
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
                    requireActivity().runOnUiThread(() -> buildInlineSuggestedRow(creators));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { /* skip this insertion */ }
            });
    }

    private void buildInlineSuggestedRow(List<String[]> pool) {
        if (!isAdded() || getContext() == null || containerFeed == null || pool.isEmpty()) return;
        Set<String> followed = cachedFollowedUids != null ? cachedFollowedUids : new HashSet<>();
        List<String[]> candidates = new ArrayList<>();
        for (String[] c : pool) {
            if (!followed.contains(c[0])) candidates.add(c);
            if (candidates.size() >= 6) break;
        }
        if (candidates.isEmpty()) return;

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

        HorizontalScrollView scroller = new HorizontalScrollView(requireContext());
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollerLp.topMargin = dpToPx(8);
        scroller.setLayoutParams(scrollerLp);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(row);

        for (String[] c : candidates) {
            String uid = c[0], name = c[1], photo = c[2];
            LinearLayout chip = new LinearLayout(requireContext());
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    dpToPx(136), LinearLayout.LayoutParams.WRAP_CONTENT);
            chipLp.setMarginEnd(dpToPx(12));
            chip.setLayoutParams(chipLp);
            chip.setPadding(dpToPx(6), dpToPx(10), dpToPx(6), dpToPx(10));
            chip.setBackgroundResource(R.drawable.bg_speed_chip);

            CircleImageView av = new CircleImageView(requireContext());
            av.setLayoutParams(new LinearLayout.LayoutParams(dpToPx(90), dpToPx(90)));
            av.setImageResource(R.drawable.ic_person);
            if (photo != null && !photo.isEmpty()) {
                Glide.with(requireContext()).load(photo)
                    .apply(RequestOptions.circleCropTransform())
                    .placeholder(R.drawable.ic_person).into(av);
            }
            chip.addView(av);

            TextView tvName = new TextView(requireContext());
            tvName.setText(name);
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

            chip.setOnClickListener(v -> {
                if (!isAdded() || getContext() == null) return;
                Intent i = new Intent(getContext(), UserReelsActivity.class);
                i.putExtra(UserReelsActivity.EXTRA_UID, uid);
                i.putExtra(UserReelsActivity.EXTRA_NAME, name);
                startActivity(i);
            });
            row.addView(chip);
        }

        section.addView(scroller);
        containerFeed.addView(section);
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
        if (!isAdded() || getContext() == null || containerFeed == null) return;
        if (suggestedReelsPool != null) {
            buildInlineSuggestedReelsRow(suggestedReelsPool);
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
                    requireActivity().runOnUiThread(() -> buildInlineSuggestedReelsRow(reels));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { /* skip this insertion */ }
            });
    }

    private void buildInlineSuggestedReelsRow(List<ReelModel> pool) {
        if (!isAdded() || getContext() == null || containerFeed == null || pool.isEmpty()) return;
        String myUid = safeMyUid();
        List<ReelModel> candidates = new ArrayList<>();
        for (ReelModel r : pool) {
            if (r.reelId == null || renderedReelIds.contains(r.reelId)) continue; // don't dupe posts already shown
            if (myUid != null && myUid.equals(r.uid)) continue; // Instagram doesn't suggest your own reels
            candidates.add(r);
            if (candidates.size() >= 8) break;
        }
        if (candidates.isEmpty()) return;

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
                if (containerFeed != null && section.getParent() == containerFeed) {
                    containerFeed.removeView(section);
                }
                return true;
            });
            menu.show();
        });
        headerRow.addView(btnMore);
        section.addView(headerRow);

        HorizontalScrollView scroller = new HorizontalScrollView(requireContext());
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        scrollerLp.topMargin = dpToPx(8);
        scroller.setLayoutParams(scrollerLp);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(row);

        int cardW = dpToPx(160);
        int cardH = dpToPx(284); // ~9:16 thumbnail card, bigger tile so only ~2 tiles + a peek of the 3rd fit per screen (matches reference screenshot)

        for (int idx = 0; idx < candidates.size(); idx++) {
            ReelModel r = candidates.get(idx);
            final int startPos = idx;

            FrameLayout tile = new FrameLayout(requireContext());
            LinearLayout.LayoutParams tileLp = new LinearLayout.LayoutParams(cardW, cardH);
            tileLp.setMarginEnd(dpToPx(8));
            tile.setLayoutParams(tileLp);
            tile.setBackgroundResource(R.drawable.bg_speed_chip);
            tile.setClipToOutline(true);

            ImageView thumb = new ImageView(requireContext());
            thumb.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            String thumbUrl = r.effectiveThumbUrl();
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                Glide.with(requireContext()).load(thumbUrl)
                    .apply(new RequestOptions().format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565))
                    .into(thumb);
            }
            tile.addView(thumb);

            // Views count pill, bottom-left, with a small play glyph — same
            // read-at-a-glance treatment Instagram uses on its reel tiles.
            TextView tvViews = new TextView(requireContext());
            tvViews.setText("▶ " + formatCount(r.viewsCount));
            tvViews.setTextColor(0xFFFFFFFF);
            tvViews.setTextSize(12f);
            tvViews.setShadowLayer(4f, 0f, 0f, 0xCC000000);
            FrameLayout.LayoutParams viewsLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            viewsLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
            viewsLp.setMargins(dpToPx(8), 0, 0, dpToPx(8));
            tvViews.setLayoutParams(viewsLp);
            tile.addView(tvViews);

            tile.setOnClickListener(v -> {
                if (!isAdded() || getContext() == null) return;
                Intent i = new Intent(getContext(), SingleReelPlayerActivity.class);
                i.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, reelIds);
                i.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, startPos);
                startActivity(i);
            });
            tile.setOnLongClickListener(v -> {
                showSuggestedReelPeek(r, tile);
                return true;
            });

            row.addView(tile);
        }

        section.addView(scroller);
        containerFeed.addView(section);
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
     *  pinned above the feed content. */
    private void showNewPostsBanner() {
        if (!isAdded() || getContext() == null || containerFeed == null) return;
        if (newPostsBanner == null) {
            TextView pill = new TextView(requireContext());
            pill.setTextColor(0xFFFFFFFF);
            pill.setTextSize(13f);
            pill.setTypeface(null, android.graphics.Typeface.BOLD);
            pill.setGravity(android.view.Gravity.CENTER);
            pill.setBackgroundResource(R.drawable.bg_speed_chip);
            int padV = dpToPx(10), padH = dpToPx(16);
            pill.setPadding(padH, padV, padH, padV);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dpToPx(8);
            lp.bottomMargin = dpToPx(8);
            pill.setLayoutParams(lp);
            pill.setOnClickListener(v -> {
                if (scrollView != null) scrollView.smoothScrollTo(0, 0);
                resetFeedPaginationState();
                if (containerFeed != null) containerFeed.removeAllViews();
                showFeedLoading(true);
                loadFeed();
            });
            newPostsBanner = pill;
        }
        ((TextView) newPostsBanner).setText(newPostsPending == 1
                ? "1 new post · Tap to refresh" : (newPostsPending + " new posts · Tap to refresh"));
        if (newPostsBanner.getParent() == null) {
            containerFeed.addView(newPostsBanner, 0);
        }
    }

    private void hideNewPostsBanner() {
        if (newPostsBanner != null && newPostsBanner.getParent() != null && containerFeed != null) {
            containerFeed.removeView(newPostsBanner);
        }
        newPostsBanner = null;
        newPostsPending = 0;
    }

    private void addFeedPostCard(ReelModel reel, Set<String> likedIds,
                                  Set<String> savedIds, String myUid, Set<String> followedUids) {
        if (!isAdded() || getContext() == null || containerFeed == null) return;
        View card = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_home_feed_post, containerFeed, false);

        CircleImageView avatar    = card.findViewById(R.id.iv_post_avatar);
        // FIX v39: seamless gradient ring (see StoryRingGradientDrawable doc for why
        // the old story_ring_insta_gradient.xml background had a visible seam).
        ImageView ivPostStoryRing = card.findViewById(R.id.iv_post_story_ring);
        if (ivPostStoryRing != null) {
            ivPostStoryRing.setBackground(
                    com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(2.5f,
                            getResources().getDisplayMetrics().density));
        }
        TextView tvOwner          = card.findViewById(R.id.tv_post_owner);
        TextView tvTime           = card.findViewById(R.id.tv_post_time);
        TextView tvAudio          = card.findViewById(R.id.tv_post_audio);
        TextView tvSuggested      = card.findViewById(R.id.tv_post_suggested);
        TextView btnPostFollow    = card.findViewById(R.id.btn_post_follow);
        ImageView ivThumb         = card.findViewById(R.id.iv_post_thumb);
        TextView tvCaption        = card.findViewById(R.id.tv_post_caption);
        TextView tvLikes          = card.findViewById(R.id.tv_post_likes);
        TextView tvComments       = card.findViewById(R.id.tv_post_comments);
        TextView tvReposts        = card.findViewById(R.id.tv_post_reposts);
        ImageButton btnLike       = card.findViewById(R.id.btn_post_like);
        ImageButton btnComment    = card.findViewById(R.id.btn_post_comment);
        ImageButton btnRepost     = card.findViewById(R.id.btn_post_repost);
        ImageButton btnSave       = card.findViewById(R.id.btn_post_save);
        PlayerView  pvFeed        = card.findViewById(R.id.pv_feed_post);
        FrameLayout frameVideo    = card.findViewById(R.id.frame_video);
        View        endOverlay    = card.findViewById(R.id.layout_end_of_reel_card);
        View        watchMore     = card.findViewById(R.id.btn_watch_more_card);
        TextView    watchAgain    = card.findViewById(R.id.btn_watch_again_card);
        ImageButton btnMute       = card.findViewById(R.id.btn_post_mute);

        // ── 9:16 aspect ratio for video frame ──────────────────────────────
        if (frameVideo != null) {
            int screenW = getResources().getDisplayMetrics().widthPixels;
            int videoH  = (int)(screenW * 16f / 9f);
            android.view.ViewGroup.LayoutParams lp = frameVideo.getLayoutParams();
            lp.height = videoH;
            frameVideo.setLayoutParams(lp);
        }

        // ── Register HomeFeedCard for auto-play ─────────────────────────────
        final int cardIndex = feedCards.size();
        HomeFeedCard feedCard = new HomeFeedCard();
        feedCard.rootView   = card;
        feedCard.playerView = pvFeed;
        feedCard.thumbView  = ivThumb;
        feedCard.endOverlay = endOverlay;
        feedCard.videoUrl   = (reel.videoUrl != null && !reel.videoUrl.isEmpty())
                              ? reel.videoUrl
                              : (reel.video480 != null ? reel.video480 : "");
        feedCard.reelId     = reel.reelId;
        feedCards.add(feedCard);

        // ── End-of-reel overlay buttons ──────────────────────────────────────
        if (watchMore != null) {
            watchMore.setOnClickListener(x -> {
                if (!isAdded() || getContext() == null) return;
                // Open the fullscreen reels explore feed
                startActivity(new Intent(getContext(), ReelExploreActivity.class));
            });
        }
        if (watchAgain != null) {
            watchAgain.setOnClickListener(x -> {
                if (feedPlayer == null) return;
                // Hide overlay, reset thumb visibility, seek to 0, replay
                if (endOverlay != null) endOverlay.setVisibility(View.GONE);
                if (ivThumb != null) { ivThumb.setAlpha(0f); ivThumb.setVisibility(View.INVISIBLE); }
                feedPlayer.seekTo(0);
                feedPlayer.play();
                currentPlayingIndex = cardIndex;
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
            View collabAvatarContainer = card.findViewById(R.id.layout_collab_avatar);
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

        // ── Expandable caption with "...more" support ───────────────────────
        String captionText = reel.caption != null ? reel.caption : "";
        final int CAPTION_MAX_LINES = 2;
        boolean[] captionExpanded = {false};
        View btnReadMore = card.findViewById(R.id.tv_post_read_more);

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

        tvLikes.setText(formatCount(reel.likesCount));
        tvComments.setText(formatCount(reel.commentsCount));
        tvReposts.setText(formatCount(reel.repostCount));

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
                frameVideo.addView(photoPager);
                frameVideo.addView(dots);
            }
        } else {
            // ── Double-tap to like on video frame ──
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
                    });
                frameVideo.setOnTouchListener((v, ev) -> dtGesture.onTouchEvent(ev));
            }
        }

        // ── Saved state ──
        final boolean[] isSaved = {reel.reelId != null && savedIds.contains(reel.reelId)};
        if (btnSave != null) {
            btnSave.setImageResource(isSaved[0]
                ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        }

        if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
            Glide.with(requireContext()).load(reel.thumbUrl)
                .apply(FEED_IMAGE_OPTS)
                .override(720, 720)
                .centerCrop().placeholder(R.drawable.ic_reels).into(ivThumb);
        }
        if (reel.ownerPhoto != null && !reel.ownerPhoto.isEmpty()) {
            Glide.with(requireContext()).load(reel.ownerPhoto)
                .apply(RequestOptions.circleCropTransform())
                .apply(FEED_IMAGE_OPTS)
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
        View btnSend = card.findViewById(R.id.btn_post_send);
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
        View btnMore = card.findViewById(R.id.btn_post_more);
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
                popup.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 1: // Not interested — remove from feed optimistically
                            if (card.getParent() instanceof ViewGroup) {
                                ((ViewGroup) card.getParent()).removeView(card);
                            }
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
                                        if (card.getParent() instanceof ViewGroup)
                                            ((ViewGroup) card.getParent()).removeView(card);
                                        Toast.makeText(requireContext(),
                                            "Blocked", Toast.LENGTH_SHORT).show();
                                    })
                                    .setNegativeButton("Cancel", null).create());
                            }
                            return true;
                        case 6: // Open original
                            openReelById(reelId, reel.ownerName);
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

        containerFeed.addView(card);
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
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("#(\\w+)").matcher(text);
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
                    Glide.with(requireContext()).load(reel.thumbUrl).centerCrop().override(720, 720).into(thumb);
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

    private void loadReelsByIds(List<String> ids, int index) {
        if (!isAdded() || getContext() == null) return;
        if (index == 0 && pbContinue != null)
            requireActivity().runOnUiThread(() -> pbContinue.setVisibility(View.GONE));
        if (index >= ids.size()) return;

        String reelId = ids.get(index);
        FirebaseUtils.getReelsRef().child(reelId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || getContext() == null) return;
                    ReelModel r = snap.getValue(ReelModel.class);
                    if (r != null) {
                        if (r.reelId == null) r.reelId = reelId;
                        addContinueWatchingCard(r, ids, index);
                    } else {
                        loadReelsByIds(ids, index + 1);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    loadReelsByIds(ids, index + 1);
                }
            });
    }

    private void addContinueWatchingCard(ReelModel reel, List<String> allIds, int position) {
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
                Glide.with(requireContext()).load(reel.thumbUrl).centerCrop().override(720, 720).into(ivThumb);
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
        loadReelsByIds(allIds, position + 1);
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

    private void showFeedLoading(boolean show) {
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (pbFeedLoading != null) pbFeedLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    private void showFeedEmpty(boolean show) {
        if (!isAdded() || getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (tvFeedEmpty != null) tvFeedEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        });
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

    /** Same red used by the full-screen reel player's like button (#FF416C) —
     *  keeps the liked-state color consistent between the home feed's inline
     *  reel card and ReelPlayerFragment/ReelSocialController. */
    private void setLikeButtonTint(ImageButton btnLike, boolean liked) {
        if (btnLike == null) return;
        btnLike.setImageTintList(ColorStateList.valueOf(
            liked ? Color.parseColor("#FF416C") : Color.WHITE));
    }
}
