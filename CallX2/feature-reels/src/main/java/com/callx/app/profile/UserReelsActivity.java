package com.callx.app.profile;
import com.callx.app.utils.AlertDialogStyler;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.followers.FollowersListActivity;
import com.callx.app.followers.FollowingListActivity;
import com.callx.app.followers.MutualFollowersActivity;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.AppBarLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.view.ViewCompat;
import com.google.android.material.tabs.TabLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.ListPreloader;
import com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.callx.app.reels.R;
import com.callx.app.profile.ReelGridAdapter;
import com.callx.app.profile.AllReelsFullActivity;
import com.callx.app.analytics.ReelCreatorDashboardActivity;
import com.callx.app.profile.ReelEditProfileActivity;
import com.callx.app.creator.ReelCreatorHubActivity;
import com.callx.app.analytics.ReelAnalyticsBottomSheet;
import com.callx.app.models.ReelModel;
  import com.callx.app.models.DuetSeriesModel;
  import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.UserEntity;
import com.callx.app.db.entity.ReelWatchHistoryCacheEntity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

// Advance features: grid-open zoom transition, skeleton crossfade, header parallax
import android.app.ActivityOptions;
import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * UserReelsActivity — Full production reel profile screen.
 *
 * SCROLLING FIX v4:
 *  - Removed NestedScrollView entirely.
 *  - Removed SwipeRefreshLayout (pull-to-refresh) entirely.
 *  - Profile header, tabs now live ABOVE a plain FrameLayout + RecyclerView.
 *  - RecyclerView gets match_parent height and owns all scrolling.
 *  - Header collapse/expand is driven explicitly via AppBarLayout.setExpanded()
 *    from the RecyclerView's scroll listener (see setupScrollPagination) —
 *    this does NOT depend on implicit nested-scroll propagation working,
 *    so it's guaranteed to move the header regardless of view-hierarchy quirks.
 *  - Pagination uses the same RecyclerView.OnScrollListener.
 */
public class UserReelsActivity extends AppCompatActivity
        implements ReelGridAdapter.LongPressListener,
                   ReelGridAdapter.MultiSelectChangeListener {

    public static final String EXTRA_UID   = "uid";
    public static final String EXTRA_NAME  = "name";
    public static final String EXTRA_PHOTO = "photo";

    // Instagram-level page size: bigger pages mean the "end of loaded data"
    // wall is hit far less often per scroll session, and the earlier
    // prefetch trigger below (maybeLoadNextPage) fires well before the user
    // ever reaches it.
    private static final int PAGE_SIZE  = 18;
    private static final int TAB_REELS  = 0;
    private static final int TAB_LIKED  = 1;
    private static final int TAB_SAVED  = 2;
    // Liked/Saved are no longer shown as tabs in the strip — moved into the
    // 3-dot menu (see setupMoreMenu()), which opens them directly in
    // AllReelsFullActivity instead of switching this screen's grid. These
    // constants are kept only for AllReelsFullActivity's own EXTRA_TAB values.
    private static final int TAB_REPOST = 3;
    private static final int TAB_SERIES = 4;
    // New grid tabs: reels a user has duetted (own duet reels), and reels
    // published via an accepted Collab Repost invite (see TAB_REPOST for the
    // repost tab this pairs with).
    private static final int TAB_DUET          = 5;
    private static final int TAB_COLLAB_REPOST = 6;

    // Visible tab STRIP position (0..5 — what tab.getPosition() returns for
    // the 6 tabs: Posts, Reels, Repost, Duet, Collab Repost, Series) →
    // internal data constant above. Needed because the strip no longer has
    // tabs in constant order, so position and data-constant are no longer
    // the same number.
    // Instagram-style "Posts" tab (leftmost) shares TAB_REELS' data/network
    // path — it's not a separate loaded list, just a photo-only FILTERED
    // view over reelsTabData (see isPostsTabActive()/filterPhotoPostsOnly()).
    private static final int[] VISIBLE_TAB_DATA =
            { TAB_REELS, TAB_REELS, TAB_REPOST, TAB_DUET, TAB_COLLAB_REPOST, TAB_SERIES };
    private static final int POSTS_STRIP_POSITION = 0;
    private static final int REELS_STRIP_POSITION = 1;
    // Current tab strip position (0..2) — separate from `activeTab`, which
    // holds the DATA constant (0/3/4). Used to compute swipe-left/right's
    // next/previous tab.
    // Instagram-style default: this screen opens on the Reels tab, not
    // Posts — see setupTabs(), which also moves the visible tab-strip
    // selection to match on cold start.
    private int activeTabPosition = REELS_STRIP_POSITION;
    // Previous tab STRIP position, used only to compute the slide direction
    // for slideSwapGridContent() — -1 means "no tab switch has happened
    // yet" (skip the slide on the very first onTabSelected at cold start).
    private int lastTabStripPosition = -1;

    // Views
    private CircleImageView ivAvatar;
    private ImageView       ivVerified;
    private View            viewStoryRing;
    // Story-ring reveal animation state (fixes v42-era infinite blink; see
    // handleStoryRingVisibility()/playStoryRingReveal()).
    private final Handler   storyRingHandler = new Handler(Looper.getMainLooper());
    private Runnable        storyRingRevealRunnable;
    private android.animation.ValueAnimator storyRingRevealAnimator;
    private TextView        tvName, tvDisplayName, tvReelCount, tvFollowers, tvFollowing, tvBio;
    private TextView        tvMutualFollowers;
    private LinearLayout    layoutMutualFollowers;
    private CircleImageView ivMutual1, ivMutual2, ivMutual3;
    private List<String>    mutualUidsList = new ArrayList<>();
    private TextView        tvPhone, tvWhatsapp, tvInstagram, tvYoutube, tvOtherLink;
    private View            layoutPhone, layoutWhatsapp, layoutInstagram, layoutYoutube, layoutOtherLink;
    private android.widget.HorizontalScrollView hsvBioLinks;
    private LinearLayout    llBioChips;
    // ── Profile Song pill (Instagram-style) ───────────────────────────────────
    private View            layoutProfileSong;
    private TextView        tvProfileSongName;
    private View            layoutAddSongStub;   // isSelf + no song → "Add a song" stub
    // Custom accent color for the profile-song strip (picked via the shared
    // rainbow color picker, long-press on either pill state). Null = default
    // theme-aware bg_song_pill.xml / drawable-night styling.
    private String          profileSongStripColorHex = null;
    // Bio-links chip strip (hsv_bio_links) custom accent colors — each chip
    // (website / instagram / youtube / twitter) now keeps its OWN color,
    // long-press picked via the same shared rainbow picker as the
    // profile-song strip. Keyed by a stable "chip type" string and persisted
    // to reels/users/{targetUid}/profileBioChipColors/{type}.
    // (Old field kept only to read a legacy single-color value written by
    // earlier app versions, so upgrading users don't lose their pick — it
    // is used purely as a one-time fallback default and is never written
    // to again.)
    private String          profileBioStripColorHex = null; // legacy fallback only
    private java.util.Map<String, String> profileBioChipColorsMap = new java.util.HashMap<>();
    // Last links list passed to buildBioChips() — cached so "Default Colour"
    // (More menu) can rebuild the chip row after clearing colors without
    // needing a fresh Firebase read.
    private java.util.List<String[]> lastBioLinks = new java.util.ArrayList<>();
    // Grid tabs (Reels/Liked/Saved/Repost/Series) + thumbnail grid-line
    // accent color — same long-press rainbow picker pattern as the bio
    // chips: EACH tab now keeps its OWN color (not one shared color for
    // all tabs). Keyed by a stable "tab key" string (see GRID_TAB_KEYS)
    // and persisted to reels/users/{targetUid}/gridAccentColors/{tabKey}.
    // When a tab is active, its own saved color (or default if none) is
    // applied to: that tab's indicator + icon tint, AND the grid's
    // thumbnail separator lines for whichever RecyclerView is showing
    // (rvReels for Reels/Liked/Saved/Repost, rvSeries for Series) — those
    // "lines" are really just the RecyclerView's background showing
    // through the symmetric item gaps (see WhiteGridDecoration).
    // (Legacy field kept only to read a one-time fallback default written
    // by earlier app versions under the old single "gridAccentColor" key
    // — used only for the Reels tab if it has no per-tab color yet.)
    private String          legacyGridAccentColorHex = null;
    private static final String[] GRID_TAB_KEYS =
            { "reels", "liked", "saved", "repost", "series", "duet", "collab_repost" };
    // PERF: plain fixed-size array instead of a HashMap<String,String> —
    // only 5 tabs ever exist, so an array index is a direct memory read
    // with zero hashing/boxing, vs a HashMap lookup (hash the key, walk a
    // bucket, unbox). Indexed 1:1 with GRID_TAB_KEYS / the tab position
    // constants (TAB_REELS=0 … TAB_SERIES=4).
    private final String[] gridAccentColorsByTab = new String[GRID_TAB_KEYS.length];
    // One-time reverse lookup (Firebase child keys → array index) built
    // once as a static map — used only while parsing the profile snapshot,
    // never on the tab-switch hot path.
    private static final java.util.Map<String, Integer> GRID_TAB_KEY_TO_INDEX;
    static {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < GRID_TAB_KEYS.length; i++) m.put(GRID_TAB_KEYS[i], i);
        GRID_TAB_KEY_TO_INDEX = java.util.Collections.unmodifiableMap(m);
    }
    // Cached tab-strip child views (position → anchor View) — resolved
    // ONCE lazily instead of walking tabLayout.getChildAt(0) + casting on
    // every long-press-listener setup AND every filter-popup anchor lookup.
    private android.view.View[] tabAnchorViewsCache = null;
    // ── Perf: grid accent color is applied on EVERY tab switch (including
    // fast swipes/reselects), so the hot path below is optimized to do the
    // minimum possible work per switch:
    //  - hex→int parsing is memoized (Color.parseColor is not free — no
    //    reason to re-parse the same hex string every time a tab is
    //    revisited).
    //  - the per-color ColorStateList used for tab icon tint is memoized
    //    too, so switching back to a previously-seen color reuses the same
    //    object instead of allocating a new int[][]/ColorStateList.
    //  - the default (no-custom-color) indicator color + tint list are
    //    resolved ONCE and cached, instead of re-querying the theme/
    //    resources on every single tab switch.
    //  - the actual native calls (setSelectedTabIndicatorColor,
    //    setTabIconTint, setBackgroundColor) are skipped entirely when the
    //    value to apply is identical to what's already applied — this is
    //    what actually keeps tab-switch/swipe scrolling smooth, since each
    //    of those calls forces an invalidate + redraw.
    private final java.util.Map<String, Integer> gridColorParseCache = new java.util.HashMap<>();
    private final android.util.SparseArray<android.content.res.ColorStateList> gridTabIconTintCache = new android.util.SparseArray<>();
    private Integer gridDefaultIndicatorColorCached = null;
    private android.content.res.ColorStateList gridDefaultTabIconTintCached = null;
    private int gridDefaultGutterColorCached = 0;
    private boolean gridDefaultGutterColorResolved = false;
    // Last-applied-state trackers (Integer so "not yet applied" == null is
    // distinguishable from any real color, incl. black/0).
    private Integer lastAppliedIndicatorColor = null;
    private android.content.res.ColorStateList lastAppliedTabIconTint = null;
    private Integer lastAppliedRvReelsBgColor = null;
    private Integer lastAppliedRvSeriesBgColor = null;
    private TextView        tvEmptyTitle, tvEmptySubtitle;
    // Illustrated empty-state animation + its static fallback (see
    // setupEmptyStateLottie() and refreshEmptyState()).
    private com.airbnb.lottie.LottieAnimationView lottieEmpty;
    private ImageView       ivEmptyIcon;
    private boolean         emptyLottieFailed = false;
    private Button          btnFollow;
    private Button          btnMessageCta;
    private android.view.View btnCtaCall;
    private LinearLayout    layoutInstagramCta;
    private LinearLayout    layoutExtraActions;
    private ImageButton     btnBack, btnMore, btnShareProfile, btnCreatorHub, btnSettings;
    private ImageButton     btnMessage, btnAudioCall, btnVideoCall, btnOpenX, btnOpenYoutube;
    private LinearLayout    layoutActions;
    // ── Suggested for you panel (Feature 1) ─────────────────────────────
    private LinearLayout    layoutSuggestedForYou;
    private LinearLayout    llSuggestedCards;
    private TextView        tvSeeAllSuggested;
    // ── Audio call in extra-actions row (Feature 2) ───────────────────
    private android.view.View btnCallRow;

    // ── Story Highlights ──────────────────────────────────────────────────
    private androidx.recyclerview.widget.RecyclerView rvHighlights;
    private android.view.View                         hsvHighlights;
    private android.view.View                         dividerHighlights;
    private HighlightsRowAdapter                      highlightsAdapter;
    private final java.util.List<HighlightsRowAdapter.HighlightAlbum> highlightAlbums = new java.util.ArrayList<>();
    // Used by loadHighlights() to detect newly-appeared albums (pulses their
    // ring — see HighlightsRowAdapter.HighlightAlbum#justAdded).
    private final java.util.Set<String> knownHighlightAlbumIds = new java.util.HashSet<>();
    private boolean highlightsLoadedOnce = false;
    // Coalesces bursts of rebuild requests — loadHighlights()'s own initial
    // bind, loadHighlightRingOverrides(), and applyHighlightSeenState()'s two
    // passes (local cache + Firebase round-trip) can all fire within the
    // same frame right after a profile opens. Without this, that's up to
    // four separate DiffUtil computations + adapter updates back to back;
    // this flattens them into a single pass. See scheduleHighlightsRebuild().
    private volatile boolean highlightsRebuildQueued = false;

    // ── Avatar peek animation fields ──────────────────────────────────────
    private CircleImageView ivAnimChat, ivAnimX, ivAnimYoutube;
    private final Handler   animHandler    = new Handler(Looper.getMainLooper());
    private Runnable        animRunnable;
    private boolean         animRunning    = false;
    private TabLayout       tabLayout;
    private RecyclerView    rvReels;
      private RecyclerView    rvSeries;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefreshReels;
    // Guards against a second pull-to-refresh network round-trip stacking on
    // top of one already in flight (e.g. user yanks the list twice fast).
    private boolean pullRefreshInFlight = false;
    private ReelGridAdapter       adapter;
      private UserSeriesGridAdapter seriesAdapter;
    private ProgressBar     progressBar;
    private View            layoutEmpty;
    private View            layoutMultiSelectBar;
    private TextView        tvSelectedCount;
    private ImageButton     btnShareSelected, btnDeleteSelected, btnCancelSelect, btnDeleteAll;
    private View            layoutPrivateAccount;
    private View            btnViewAllReels;
    // Tracks the button's current animated show/hide state (separate from
    // whether it's *eligible* to show based on data) so onScroll doesn't
    // re-trigger an animation every single frame while scrolling.
    private boolean         viewAllButtonEligible = false;
    private boolean         viewAllButtonShown    = false;
    private View            layoutFollowersClick;
    private View            layoutFollowingClick;
    private View            btnRepostSection;
    private View            btnSeriesSection;
    private com.google.android.material.appbar.AppBarLayout appBarLayout;
    // Avatar frame — parallax + blur target for the profile header collapse
    // (see setupHeaderParallax()).
    private View frameAvatarParallax;
    // Lets the plain (non-scrollable) layoutEmpty view participate in the same
    // CoordinatorLayout nested-scroll chain that rvReels uses natively — see
    // setupSwipeBetweenTabs() / the touch listener below for why this is needed.
    private androidx.core.view.NestedScrollingChildHelper emptyStateScrollHelper;

      // ── Filter chips state ─────────────────────────────────────────────
      private static final int FILTER_ALL    = 0;
      private static final int FILTER_OLDEST = 1; // sorted by timestamp ascending
      private static final int FILTER_NEWEST = 2; // sorted by timestamp descending
      private static final int FILTER_VIEWED = 3; // most viewed
      // GAP FIX: no way to filter the grid by watched status at all before —
      // reuses the same watchedReelIdsCache the "Just watched" overlay is
      // built from (see loadWatchedReelIds()), so it's always in sync with
      // whatever's currently showing the badge.
      private static final int FILTER_NOT_WATCHED = 4;
      private int              activeFilter  = FILTER_ALL;
      private android.widget.HorizontalScrollView hsvFilterChips;
      private android.widget.LinearLayout         llFilterChips;

    // State
    private String  targetUid, targetName, targetPhoto;
    // Offline-first Room executor — was a single-thread executor; several
    // independent read/write tasks (loadReelGridFromRoom, cache writes,
    // highlight loads) were queuing behind each other on one thread even
    // though they touch unrelated data. A small fixed pool lets them run
    // concurrently while still keeping resource usage bounded.
    private final ExecutorService dbExecutor = Executors.newFixedThreadPool(2);

    // Min gap between two silentRefreshReels() network calls from onResume().
    private static final long SILENT_REFRESH_MIN_INTERVAL_MS = 15_000L;
    private long lastSilentRefreshAtMs = 0L;

    // Auto-retry a failed page load a couple of times with backoff instead of
    // silently giving up (Instagram-style: a dropped packet or a brief
    // reconnect shouldn't require the user to scroll up and down again to
    // re-trigger pagination). Keyed by tab so each grid's own failure count
    // is tracked independently.
    private static final int MAX_PAGE_LOAD_RETRIES = 2;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private final Map<Integer, Integer> pageLoadRetryCount = new HashMap<>();

    private void onPageLoadFailed(int tab, boolean refresh) {
        finishLoading(refresh, tab);
        if (refresh) return; // only auto-retry pagination fetches, not pull/tab refreshes
        int attempts = pageLoadRetryCount.getOrDefault(tab, 0);
        if (attempts >= MAX_PAGE_LOAD_RETRIES) return;
        pageLoadRetryCount.put(tab, attempts + 1);
        long backoffMs = 1000L * (attempts + 1); // 1s, then 2s
        retryHandler.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (tab == activeTab) loadCurrentTab(false);
        }, backoffMs);
    }

    private void onPageLoadSucceeded(int tab) {
        pageLoadRetryCount.remove(tab);
    }
    private boolean isFollowing      = false;
    private boolean isMultiSelect    = false;
    private boolean isSelf           = false;
    private boolean isAccountPrivate = false;
    private int     activeTab        = TAB_REELS;

    private final List<ReelModel> reelsTabData   = new ArrayList<>();
    private final List<ReelModel> likedTabData   = new ArrayList<>();
    private final List<ReelModel> savedTabData   = new ArrayList<>();
    private final List<ReelModel> repostsTabData = new ArrayList<>();
    private final List<ReelModel> duetTabData         = new ArrayList<>();
    private final List<ReelModel> collabRepostTabData = new ArrayList<>();
    private final Set<String>     selectedReelIds = new HashSet<>();
      private final java.util.List<DuetSeriesModel> seriesTabData = new ArrayList<>();
      private boolean seriesLoaded = false;

    private String  reelsLastKey = null, likedLastKey = null,
                    savedLastKey = null, repostsLastKey = null,
                    duetLastKey = null, collabRepostLastKey = null;
    private boolean reelsHasMore = true, likedHasMore = true,
                    savedHasMore = true, repostsHasMore = true,
                    duetHasMore = true, collabRepostHasMore = true;
    private boolean isLoadingMore = false;
    // Adapter position of the pagination footer row, captured the instant it's
    // shown (before any further list mutation) — see maybeLoadNextPage() /
    // finishLoading(). Needed to remove it with a precise notifyItemRemoved
    // instead of recomputing a position from a getItemCount() that may
    // already reflect items appended by the fetch that's completing.
    private int footerPositionAtShow = -1;
    // Mirror of whatever's currently passed to adapter.setWatchedReelIds() —
    // kept so the "Not watched" filter chip (FILTER_NOT_WATCHED) can check
    // membership without asking the adapter for its private state.
    private final java.util.Set<String> watchedReelIdsCache = new java.util.HashSet<>();

    // ── Realtime update helpers (self only) ───────────────────────────────
    /** Skip the silent grid refresh on the very first onResume (right after onCreate). */
    private boolean isFirstResume = true;
    /** Persistent count listener — auto-updates tvReelCount whenever a reel is added/removed. */
    private ValueEventListener reelCountLiveListener = null;

    private ReelModel         pinnedReel = null;
    private ReelPeekPreviewController peekController;
    // ULTRA: rv_reels and rv_series are both photo/video grids shown one
    // tab at a time (only one is ever on-screen), so their off-screen
    // ViewHolders can safely share ONE RecycledViewPool instead of each
    // RecyclerView keeping (and cold-inflating into) its own — switching
    // tabs reuses already-inflated+bound views instead of re-inflating.
    // Safe because ReelGridAdapter's view types (0/1/2 — see TYPE_SKELETON/
    // TYPE_REEL/TYPE_PINNED) and UserSeriesGridAdapter's (offset to 100 —
    // see its getItemViewType()) never overlap, so the pool never hands a
    // ReelVH back to the series adapter or vice versa.
    private final RecyclerView.RecycledViewPool gridSharedViewPool = new RecyclerView.RecycledViewPool();
    {
        // Default pool cap is 5 per viewType — bump TYPE_REEL since a 3-col
        // grid can have well over 5 off-screen cells cached at once (matches
        // the setItemViewCacheSize(12) used on both rvReels/rvSeries).
        gridSharedViewPool.setMaxRecycledViews(ReelGridAdapter.TYPE_REEL, 14);
        gridSharedViewPool.setMaxRecycledViews(ReelGridAdapter.TYPE_PINNED, 2);
        gridSharedViewPool.setMaxRecycledViews(UserSeriesGridAdapter.TYPE_SERIES_CARD, 10);
    }
    private SwipeAwareGridLayoutManager gridLayoutManager;
    // Series-tab grid also needs to disable its own vertical scroll while a
    // horizontal tab-swipe is in progress (see SwipeAwareGridLayoutManager).
    private SwipeAwareGridLayoutManager seriesLayoutManager;

    /**
     * GridLayoutManager variant whose vertical scrolling can be switched off
     * on demand. Used to hard-stop the grid's own down/up scrolling for the
     * duration of a left/right tab-swipe gesture, so the vertical scroll
     * (and the AppBarLayout/CoordinatorLayout nested-scroll header collapse
     * it drives) can never fight the horizontal swipe for the same touch
     * stream — see buildSwipeListener()/setupSwipeBetweenTabs().
     */
    private static class SwipeAwareGridLayoutManager extends GridLayoutManager {
        private volatile boolean verticalScrollEnabled = true;

        SwipeAwareGridLayoutManager(android.content.Context context, int spanCount) {
            super(context, spanCount);
        }

        void setVerticalScrollEnabled(boolean enabled) {
            verticalScrollEnabled = enabled;
        }

        // BUG FIX (header wasn't collapsing / tab strip wasn't pinning below
        // the nav bar): RecyclerView only enters the nested-scroll path that
        // drives AppBarLayout's collapse when its LayoutManager reports
        // canScrollVertically() == true. Stock GridLayoutManager bases that
        // purely on whether ITS OWN content overflows the RecyclerView's
        // viewport — with a short grid (few reels), super.canScrollVertically()
        // is false, so RecyclerView never starts a nested scroll at all and
        // the drag never reaches CoordinatorLayout/AppBarLayout. Same root
        // cause as the empty-tab case already patched via
        // emptyStateScrollHelper, just for 1+ items instead of 0. Always
        // reporting true here (still gated by verticalScrollEnabled, which
        // the horizontal swipe-between-tabs logic flips off mid-drag) makes
        // RecyclerView always offer the drag to the AppBarLayout first via
        // dispatchNestedPreScroll — matching Instagram's behavior on sparse
        // profiles.
        @Override
        public boolean canScrollVertically() {
            return verticalScrollEnabled;
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_reels);

        // Remove the gray system nav-bar strip — make it transparent so it
        // blends with the screen background (Instagram-style, no solid bar).
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        boolean isNightMode = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        androidx.core.view.WindowInsetsControllerCompat insetsController =
                androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightNavigationBars(!isNightMode);

        targetUid   = getIntent().getStringExtra(EXTRA_UID);
        targetName  = getIntent().getStringExtra(EXTRA_NAME);
        targetPhoto = getIntent().getStringExtra(EXTRA_PHOTO);
        if (targetUid == null || targetUid.isEmpty()) { finish(); return; }

        isSelf = targetUid.equals(safeMyUid());

        bindViews();
        setupHeader();
        setupHeaderParallax();
        setupScrollPagination();
        setupTabs();
        setupFilterChips();
        setupMultiSelectBar();
        loadFromRoom(); // Offline-first: Room se instant load (profile header)
        loadReelGridFromRoom(); // Offline-first: Room se instant load (reels grid — advance #6)
        loadUserProfile();
        setupHighlights();
        // Deferred one frame: header + reel grid are the critical first
        // paint, highlights are secondary (Instagram loads its highlights
        // row after the core profile content, not blocking it).
        if (rvHighlights != null) rvHighlights.post(this::loadHighlights);
        else loadHighlights();
        if (isSelf) {
            // Advance #3 — one-time, battery/network-friendly backfill of
            // BlurHash for reels posted before that feature shipped.
            com.callx.app.workers.BlurHashBackfillWorker.enqueueFor(getApplicationContext(), targetUid);
            // One-time backfill of the new Duet/Collab Repost grid tab
            // indexes for reels posted before those tabs shipped — see
            // DuetCollabIndexBackfillWorker.
            com.callx.app.workers.DuetCollabIndexBackfillWorker.enqueueFor(getApplicationContext(), targetUid);
        }
        loadFollowState();
        loadVerifiedStatus();
        loadMutualFollowers();
        loadPinnedReel();
        loadCurrentTab(true);
        setupViewAllReelsButton();
        setupPullToRefresh();
        loadWatchedReelIds();
        loadReelCount();
        checkActiveStory();
        loadAccountPrivacy();
        setupStatsClicks();
        loadAvatarAndStartAnimation();
    }

    // ── Bind views ────────────────────────────────────────────────────────

    private void bindViews() {
        ivAvatar             = findViewById(R.id.iv_avatar);
        ivVerified           = findViewById(R.id.iv_verified);
        viewStoryRing        = findViewById(R.id.view_story_ring);
        // Ring stays hidden until checkActiveStory() resolves whether this
        // user has an active story and whether it's seen/unseen — avoids a
        // one-frame gradient flash before the real seen-state is known.
        if (viewStoryRing != null) {
            viewStoryRing.setVisibility(View.GONE);
        }
        tvName               = findViewById(R.id.tv_name);
        tvDisplayName        = findViewById(R.id.tv_display_name);
        tvReelCount          = findViewById(R.id.tv_reel_count);
        tvFollowers          = findViewById(R.id.tv_followers);
        tvFollowing          = findViewById(R.id.tv_following);
        tvBio                = findViewById(R.id.tv_bio);
        // FIX: tv_bio was clickable in XML (and its comment said "tap to
        // expand") but no click listener actually existed anywhere — tapping
        // the bio did nothing. Wiring it up now. Also guards against the
        // layout "jump" this kind of toggle usually causes inside an
        // AppBarLayout header with scroll|exitUntilCollapsed: instead of an
        // abrupt requestLayout(), androidx.transition.TransitionManager
        // animates the height change over a couple of frames, and it runs
        // on the AppBarLayout itself so the collapsed/expanded scroll offset
        // is recalculated smoothly against the new content height rather
        // than snapping.
        if (tvBio != null) {
            tvBio.setOnClickListener(v -> {
                android.view.ViewGroup transitionRoot =
                        appBarLayout != null ? appBarLayout : (android.view.ViewGroup) tvBio.getParent();
                androidx.transition.TransitionManager.beginDelayedTransition(
                        transitionRoot, new androidx.transition.AutoTransition().setDuration(180));
                boolean isExpanded = tvBio.getMaxLines() != 3;
                tvBio.setMaxLines(isExpanded ? 3 : Integer.MAX_VALUE);
                tvBio.setEllipsize(isExpanded ? android.text.TextUtils.TruncateAt.END : null);
            });
        }
        tvMutualFollowers    = findViewById(R.id.tv_mutual_followers);
        layoutMutualFollowers= findViewById(R.id.layout_mutual_followers);
        ivMutual1            = findViewById(R.id.iv_mutual_1);
        ivMutual2            = findViewById(R.id.iv_mutual_2);
        ivMutual3            = findViewById(R.id.iv_mutual_3);
        tvEmptyTitle         = findViewById(R.id.tv_empty_title);
        tvEmptySubtitle      = findViewById(R.id.tv_empty_subtitle);
        lottieEmpty          = findViewById(R.id.lottie_empty);
        ivEmptyIcon          = findViewById(R.id.iv_empty_icon);
        setupEmptyStateLottie();
        btnFollow            = findViewById(R.id.btn_follow);
        btnBack              = findViewById(R.id.btn_back);
        btnShareProfile      = findViewById(R.id.btn_share_profile);
        btnCreatorHub        = findViewById(R.id.btn_creator_hub);
        btnSettings          = findViewById(R.id.btn_settings);
        btnMore              = findViewById(R.id.btn_more);
        btnMessage           = findViewById(R.id.btn_message);
        btnAudioCall         = findViewById(R.id.btn_audio_call);
        btnVideoCall         = findViewById(R.id.btn_video_call);
        btnOpenX             = findViewById(R.id.btn_open_x);
        btnOpenYoutube       = findViewById(R.id.btn_open_youtube);
        ivAnimChat           = findViewById(R.id.iv_anim_chat);
        ivAnimX              = findViewById(R.id.iv_anim_x);
        ivAnimYoutube        = findViewById(R.id.iv_anim_youtube);
        layoutActions        = findViewById(R.id.layout_actions);
        tabLayout            = findViewById(R.id.tab_layout);
        rvReels              = findViewById(R.id.rv_reels);
          rvSeries             = findViewById(R.id.rv_series);
        swipeRefreshReels    = findViewById(R.id.swipe_refresh_reels);
        hsvFilterChips       = findViewById(R.id.hsv_filter_chips);
        llFilterChips        = findViewById(R.id.ll_filter_chips);
        progressBar          = findViewById(R.id.progress_bar);
        layoutEmpty          = findViewById(R.id.layout_empty);
        btnViewAllReels      = findViewById(R.id.btn_view_all_reels);
        layoutMultiSelectBar = findViewById(R.id.layout_multi_select_bar);
        tvSelectedCount      = findViewById(R.id.tv_selected_count);
        btnShareSelected     = findViewById(R.id.btn_share_selected);
        layoutPrivateAccount = findViewById(R.id.layout_private_account);
        layoutFollowersClick = findViewById(R.id.layout_followers_click);
        layoutFollowingClick = findViewById(R.id.layout_following_click);
        btnRepostSection     = findViewById(R.id.btn_repost_section);
        btnSeriesSection     = findViewById(R.id.btn_series_section);
        btnDeleteSelected    = findViewById(R.id.btn_delete_selected);
        btnCancelSelect      = findViewById(R.id.btn_cancel_select);
        btnDeleteAll         = findViewById(R.id.btn_delete_all);
        tvPhone          = findViewById(R.id.tv_phone);
        tvWhatsapp       = findViewById(R.id.tv_whatsapp);
        tvInstagram      = findViewById(R.id.tv_instagram);
        tvYoutube        = findViewById(R.id.tv_youtube);
        tvOtherLink      = findViewById(R.id.tv_other_link);
        layoutPhone      = findViewById(R.id.layout_phone);
        layoutWhatsapp   = findViewById(R.id.layout_whatsapp);
        layoutInstagram  = findViewById(R.id.layout_instagram);
        layoutYoutube    = findViewById(R.id.layout_youtube);
        layoutOtherLink  = findViewById(R.id.layout_other_link);
        appBarLayout     = findViewById(R.id.app_bar);
        frameAvatarParallax = findViewById(R.id.frame_avatar_parallax);
        hsvBioLinks       = findViewById(R.id.hsv_bio_links);
        llBioChips        = findViewById(R.id.ll_bio_chips);
        layoutProfileSong = findViewById(R.id.layout_profile_song);
        tvProfileSongName = findViewById(R.id.tv_profile_song_name);
        layoutAddSongStub = findViewById(R.id.layout_add_song_stub);
        btnMessageCta     = findViewById(R.id.btn_message_cta);
        btnCtaCall       = findViewById(R.id.btn_cta_call);
        layoutInstagramCta = findViewById(R.id.layout_instagram_cta);
        layoutExtraActions    = findViewById(R.id.layout_extra_actions);
        layoutSuggestedForYou = findViewById(R.id.layout_suggested_for_you);
        llSuggestedCards      = findViewById(R.id.ll_suggested_cards);
        tvSeeAllSuggested     = findViewById(R.id.tv_see_all_suggested);
        btnCallRow            = findViewById(R.id.btn_call_row);
        rvHighlights       = findViewById(R.id.rv_highlights);
        hsvHighlights      = findViewById(R.id.hsv_highlights);
        dividerHighlights  = findViewById(R.id.divider_highlights);
    }

    // ── Header ────────────────────────────────────────────────────────────

    private void setupHeader() {
        btnBack.setOnClickListener(v -> {
            if (isMultiSelect) { exitMultiSelectMode(); return; }
            finish();
        });

        if (targetName  != null) { tvName.setText(targetName); if (tvDisplayName != null) tvDisplayName.setText(targetName); }
        // Avatar placeholder only — actual HD load happens in loadAvatarAndStartAnimation()
        // after Firebase returns photoUrl. Permanently cached (DiskCacheStrategy.ALL).
        if (ivAvatar != null) ivAvatar.setImageResource(R.drawable.ic_person);

        if (btnShareProfile != null) btnShareProfile.setOnClickListener(v -> shareProfile());

        if (btnCreatorHub != null) {
            btnCreatorHub.setVisibility(isSelf ? View.VISIBLE : View.GONE);
            if (isSelf) btnCreatorHub.setOnClickListener(v ->
                startActivity(new Intent(this, ReelCreatorHubActivity.class)));
        }

        if (btnSettings != null) {
            btnSettings.setVisibility(isSelf ? View.VISIBLE : View.GONE);
            if (isSelf) btnSettings.setOnClickListener(v -> {
                // Reel profile edit — reels/users/{uid} node
                startActivity(new Intent(this, ReelEditProfileActivity.class));
            });
        }

        adapter = new ReelGridAdapter(
              this, activeTabData(),
            pos -> {
                // GAP FIX: the pagination footer spinner row (see
                // setupPullToRefresh/maybeLoadNextPage) is a real adapter
                // position with no reel behind it — without this guard a tap
                // there fell through to openPlayerAt()'s clamp-to-last-item
                // fallback and silently opened the wrong (last) reel.
                if (adapter != null && adapter.getItemViewType(pos) == ReelGridAdapter.TYPE_FOOTER_LOADING) return;
                if (isMultiSelect) toggleSelection(pos); else openPlayerAt(pos);
            },
            this, this
        );
        peekController = new ReelPeekPreviewController(this);
        // NOTE: previously this closed the mini video player the instant the
        // user's finger lifted off the long-pressed cell — no longer wired.
        // The peek popup now stays open after release; it only closes when
        // the user taps OUTSIDE the mini player (the dimmed scrim area —
        // see ReelPeekPreviewController#show()'s scrim click listener), taps
        // "Watch Reel", or the activity is paused/destroyed/tab-switched.
        // Instagram-style quick-like: double-tap a grid cell to like without
        // opening the player. See likeReelFromGrid() below.
        adapter.setOnDoubleTapLikeListener(this::likeReelFromGrid);

        // Series tab setup
        seriesAdapter = new UserSeriesGridAdapter(this);
        if (rvSeries != null) {
            seriesLayoutManager = new SwipeAwareGridLayoutManager(this, 2);
            rvSeries.setLayoutManager(seriesLayoutManager);
            rvSeries.setAdapter(seriesAdapter);
            // ULTRA (parity with rv_reels): same reasoning as below — fixed
            // bounds (match_parent, doesn't depend on adapter content), no
            // per-cell animation needed for a photo/video grid, and a larger
            // off-screen ViewHolder cache so switching into/out of this tab
            // and fast-scrolling reuse bound views instead of re-inflating.
            rvSeries.setHasFixedSize(true);
            rvSeries.setItemAnimator(null);
            rvSeries.setItemViewCacheSize(12);
            seriesLayoutManager.setInitialPrefetchItemCount(6);
            seriesLayoutManager.setItemPrefetchEnabled(true);
            rvSeries.setRecycledViewPool(gridSharedViewPool);
            seriesAdapter.setOnSeriesClickListener(series -> {
                Intent si = new Intent(this, com.callx.app.social.DuetSeriesActivity.class);
                si.putExtra(com.callx.app.social.DuetSeriesActivity.EXTRA_SERIES_ID, series.seriesId);
                startActivity(si);
            });
        }
        adapter.setShowViewsOverlay(isSelf);

        gridLayoutManager = new SwipeAwareGridLayoutManager(this, 3);
        gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                int type = adapter.getItemViewType(position);
                return (type == ReelGridAdapter.TYPE_PINNED
                        || type == ReelGridAdapter.TYPE_FOOTER_LOADING) ? 3 : 1;
            }
        });

        rvReels.setLayoutManager(gridLayoutManager);
        rvReels.setAdapter(adapter);
        rvReels.addItemDecoration(new ReelGridAdapter.WhiteGridDecoration(this));
        // KEY FIX: RecyclerView must NOT have nested scrolling disabled.
        // It lives inside swipe_refresh_reels (SwipeRefreshLayout), which is
        // itself a NestedScrollingParent — nested scroll events still pass
        // through it to drive AppBarLayout's collapse exactly as before, so
        // adding pull-to-refresh didn't change anything here.
        rvReels.setNestedScrollingEnabled(true);
        // ULTRA: rv_reels is match_parent width / 0dp+weight=1 height in the
        // layout — its own bounds never depend on adapter content, so
        // RecyclerView can skip the extra measure pass hasFixedSize(false)
        // otherwise triggers on every adapter change.
        rvReels.setHasFixedSize(true);
        // ULTRA: default ItemAnimator fades every insert/remove/change (e.g.
        // the silent top-insert on resume, filter re-sort, pagination
        // append) — that's extra per-frame alpha compositing across up to
        // 3 grid columns for no real UX benefit on a photo/video grid.
        // Instagram's own grid doesn't animate cell changes either.
        rvReels.setItemAnimator(null);
        // ULTRA: cache more off-screen ViewHolders so fast flings and
        // tab-switch scroll-to-top reuse already-bound views instead of
        // re-inflating/re-binding (default cache size is 2).
        rvReels.setItemViewCacheSize(12);
        gridLayoutManager.setInitialPrefetchItemCount(9);
        gridLayoutManager.setItemPrefetchEnabled(true);
        rvReels.setRecycledViewPool(gridSharedViewPool);
        setupGlidePreloader();
        setupSwipeBetweenTabs();

        // Instagram-style CTA buttons visible only for other users
        if (layoutInstagramCta  != null) layoutInstagramCta.setVisibility(isSelf ? View.GONE : View.VISIBLE);
        if (layoutExtraActions  != null) layoutExtraActions.setVisibility(isSelf ? View.GONE : View.VISIBLE);
        if (layoutActions       != null) layoutActions.setVisibility(View.GONE); // legacy bar hidden
        if (btnFollow           != null) btnFollow.setVisibility(isSelf ? View.GONE : View.VISIBLE);

        setupActionButtons();
        setupMoreMenu();

        if (ivAvatar != null) {
            // Tap: status ring hai to status open, warna avatar-zoom seedha
            // simple tap se hi khulta hai — pehle yeh sirf long-press se
            // khulta tha, ab woh trigger click pe move ho gaya hai (long-
            // press listener isliye hata diya, ab redundant hai).
            ivAvatar.setOnClickListener(v -> openStatusOrAvatarZoom(v));

            // Long-press: story (seen ya unseen) ho tab bhi seedha avatar-zoom
            // khol do — story check bypass karke — taki user sirf photo
            // dekhna chahe to dekh sake, tap se story open kiye bina.
            ivAvatar.setOnLongClickListener(v -> {
                showAvatarZoom(v, targetPhoto, targetName);
                return true;
            });
        }
    }

    /**
     * Avatar tap handler: agar target user ka active (24h) status hai to
     * status viewer khulta hai — jaisa pehle hota tha. Status na ho (ya
     * check abhi pending ho) to avatar-zoom dialog khulta hai, jo pehle
     * sirf long-press se accessible tha.
     */
    private void openStatusOrAvatarZoom(View sourceView) {
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        FirebaseUtils.getUserStatusRef(targetUid)
            .orderByChild("timestamp").startAt((double) cutoff).limitToFirst(1)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (snap.exists() && snap.getChildrenCount() > 0) {
                        try {
                            Class<?> cls = Class.forName("com.callx.app.viewer.StatusViewerActivity");
                            Intent i = new Intent(UserReelsActivity.this, cls);
                            i.putExtra("ownerUid",  targetUid);
                            i.putExtra("ownerName", targetName != null ? targetName : "");
                            startActivity(i);
                        } catch (ClassNotFoundException e) {
                            Toast.makeText(UserReelsActivity.this, "Status viewer unavailable", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        showAvatarZoom(sourceView, targetPhoto, targetName);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    // Firebase check fail ho jaye to bhi avatar-zoom dikha do —
                    // tap kabhi silently no-op nahi hona chahiye.
                    showAvatarZoom(sourceView, targetPhoto, targetName);
                }
            });
    }

    /**
     * Glide RecyclerViewPreloader — replaces the old manual "warm the next
     * N cells" call that used to run inside the adapter's
     * onViewAttachedToWindow(). This scroll listener watches rv_reels'
     * actual scroll direction/velocity and asks the adapter (via
     * ReelGridAdapter#getPreloadItems/getPreloadRequestBuilder) which
     * upcoming thumbnails to warm into Glide's cache — so by the time a
     * cell scrolls into view its thumbnail is typically already decoded,
     * instead of popping in as the user scrolls past it.
     */
    private void setupGlidePreloader() {
        if (rvReels == null || adapter == null) return;
        ListPreloader.PreloadSizeProvider<String> sizeProvider =
                (item, adapterPosition, perItemPosition) -> {
                    int size = adapter.getGridThumbSizePx();
                    return new int[]{size, size};
                };
        RecyclerViewPreloader<String> preloader = new RecyclerViewPreloader<>(
                Glide.with(this), adapter, sizeProvider, ReelGridAdapter.PRELOAD_AHEAD);
        rvReels.addOnScrollListener(preloader);
    }

    /**
     * LinkedIn/Instagram-style depth on header collapse: as the AppBarLayout
     * collapses (user scrolls the grid up), the collapsible header content
     * (child 0 — avatar/stats/bio row) scrolls up at a slightly SLOWER rate
     * than the actual scroll (a classic parallax offset), fading out as it
     * goes; the avatar itself additionally scales down and — on API 31+ —
     * gains a soft blur that increases with collapse fraction, so it reads
     * as receding into depth rather than just sliding off. Everything resets
     * cleanly back to normal the moment the header re-expands.
     */
    private void setupHeaderParallax() {
        if (appBarLayout == null || appBarLayout.getChildCount() == 0) return;
        final View headerContent = appBarLayout.getChildAt(0); // the scroll|exitUntilCollapsed child
        appBarLayout.addOnOffsetChangedListener((layout, verticalOffset) -> {
            int totalRange = layout.getTotalScrollRange();
            if (totalRange <= 0) return;
            float fraction = Math.min(1f, Math.abs(verticalOffset) / (float) totalRange);

            // Parallax: header content trails the real scroll (moves up
            // slower), giving it depth instead of scrolling 1:1 with the grid.
            headerContent.setTranslationY(verticalOffset * 0.45f);
            headerContent.setAlpha(Math.max(0f, 1f - fraction * 1.3f));

            if (frameAvatarParallax != null) {
                float scale = 1f - (fraction * 0.22f);
                frameAvatarParallax.setScaleX(scale);
                frameAvatarParallax.setScaleY(scale);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    try {
                        if (fraction <= 0.01f) {
                            frameAvatarParallax.setRenderEffect(null);
                        } else {
                            float blurPx = 1f + fraction * 11f; // up to ~12px at full collapse
                            frameAvatarParallax.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                                    blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP));
                        }
                    } catch (Throwable ignored) {
                        // Older/odd devices that report S+ but reject the effect — skip blur, keep scale/parallax.
                    }
                }
            }
        });
    }

    // ── Scroll listener for header collapse + pagination ────────────────────

    /**
     * SCROLLING FIX v5: root cause of the header never collapsing was the
     * AppBarLayout child ORDER — it had a pinned (noScroll) nav bar BEFORE
     * the scrollable header, which is an unreliable configuration for
     * CoordinatorLayout. The nav bar now lives entirely outside the
     * AppBarLayout (see activity_user_reels.xml), so AppBarLayout only has
     * the standard [scroll, then pin] child order.
     *
     * The header's scroll flags (scroll|exitUntilCollapsed) + the content
     * container's app:layout_behavior="appbar_scrolling_view_behavior" are
     * enough for CoordinatorLayout to drive the collapse natively — no
     * manual AppBarLayout.setExpanded() driving is needed (that was causing
     * scroll flicker/junk by fighting the native nested-scroll animation).
     */
    // ULTRA (scroll throttle): pagination's threshold check itself is cheap
    // (a boolean + two int reads), but calling loadCurrentTab() as soon as
    // that threshold is crossed means it can fire mid-fling — right when
    // the fling is fastest and least likely to actually land near that
    // position, and right when we least want to spend main-thread/network
    // budget on a Firebase read. Debounce it: onScrolled only *schedules* a
    // check ~120ms out and cancels/reschedules on every subsequent scroll
    // event, so it only truly runs once the finger/fling has settled down.
    // onScrollStateChanged(IDLE) short-circuits straight to the check the
    // moment scrolling actually stops, instead of waiting out the delay.
    // PAGINATION — INSTAGRAM-LEVEL APPROACH
    // ───────────────────────────────────────────────────────────────────────
    // Old behavior: onScrolled() debounced the threshold check by 120ms and
    // cancelled/rescheduled it on every subsequent scroll event. That means
    // during a continuous fast fling the check NEVER ran until the fling
    // fully settled to SCROLL_STATE_IDLE — by which point the user could
    // already be at the very last loaded row, so the next-page fetch only
    // *started* right as the grid ran out of items to show. That's the
    // "scroll ruk jata hai" stall: the RecyclerView wasn't broken, the
    // network fetch was simply starting too late.
    //
    // Fix (matches how Instagram's feed/grid actually behaves):
    //  1. Check the threshold on every onScrolled() call directly — no
    //     debounce. The check itself is just two int reads + a boolean, so
    //     running it every frame during a fling is negligible; isLoadingMore
    //     already guards against firing the same fetch twice.
    //  2. Prefetch distance is now expressed in "how many items are left
    //     unseen", defaulting to a full PAGE_SIZE — i.e. the next page starts
    //     loading as soon as the user scrolls into the last page's worth of
    //     items, not 3 rows before the literal end. With PAGE_SIZE now 18
    //     that's a comfortable lead the network almost always beats.
    private void setupScrollPagination() {
          rvReels.addOnScrollListener(new RecyclerView.OnScrollListener() {
              @Override
              public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                  // NOTE: AppBarLayout collapse/expand is already driven natively by
                  // CoordinatorLayout's nested-scroll (app:layout_behavior=
                  // "@string/appbar_scrolling_view_behavior" + the header's own
                  // scroll flags). Manually calling appBarLayout.setExpanded(..., true)
                  // here as well fights that ongoing touch-driven offset animation and
                  // is what was causing the scroll/flicker/junk pattern — removed.
                  if (dy > 0) maybeLoadNextPage();
              }

              @Override
              public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                  if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                      maybeLoadNextPage();
                  }
              }
          });
      }

    /** Runs the pagination threshold check — now called live during scroll (see setupScrollPagination). */
    private void maybeLoadNextPage() {
        if (isFinishing() || isDestroyed()) return;
        if (isLoadingMore) return;
        if (!getCurrentTabHasMore()) return;
        if (gridLayoutManager == null || adapter == null) return;
        int total       = gridLayoutManager.getItemCount();
        int lastVisible = gridLayoutManager.findLastVisibleItemPosition();
        if (total <= 0 || lastVisible < 0) return;
        // Trigger the fetch once the user has scrolled into the last
        // page-worth of items, with a floor of a few rows for small pages.
        int prefetchDistance = Math.max(PAGE_SIZE, gridLayoutManager.getSpanCount() * 4);
        if (lastVisible >= total - prefetchDistance) {
            adapter.setLoadingFooterVisible(true);
            footerPositionAtShow = adapter.getItemCount() - 1;
            loadCurrentTab(false);
        }
    }

    private boolean getCurrentTabHasMore() {
        switch (activeTab) {
            case TAB_LIKED:  return likedHasMore;
            case TAB_SAVED:  return savedHasMore;
            case TAB_REPOST: return repostsHasMore;
            case TAB_DUET:   return duetHasMore;
            case TAB_COLLAB_REPOST: return collabRepostHasMore;
            case TAB_SERIES: return false;
            default:         return reelsHasMore;
        }
    }


      // ── Filter Popup (Reels tab only, shown on re-tap of the tab icon) ─────

      private void setupFilterChips() {
          // Old always-visible chip row is retired — filters now live in a
          // compact popup that only appears when the user re-taps the Reels
          // tab, and only while that tab is active (see onTabReselected()).
          if (hsvFilterChips != null) hsvFilterChips.setVisibility(android.view.View.GONE);
      }

      private void updateChipStyle(android.widget.TextView chip, boolean selected) {
          if (selected) {
              chip.setTextColor(android.graphics.Color.BLACK);
              chip.setBackgroundResource(R.drawable.bg_filter_chip_selected);
          } else {
              chip.setTextColor(android.graphics.Color.WHITE);
              chip.setBackgroundResource(R.drawable.bg_filter_chip_unselected);
          }
      }

      /** Resolve the tappable view for a given TabLayout tab position (used as popup anchor). Cached after first resolve. */
      private android.view.View tabAnchorView(TabLayout.Tab tab) {
          int pos = tab.getPosition();
          if (tabAnchorViewsCache == null) {
              tabAnchorViewsCache = new android.view.View[tabLayout.getTabCount()];
          }
          if (pos >= 0 && pos < tabAnchorViewsCache.length && tabAnchorViewsCache[pos] != null) {
              return tabAnchorViewsCache[pos];
          }
          android.view.View resolved = tabLayout;
          try {
              android.view.ViewGroup strip = (android.view.ViewGroup) tabLayout.getChildAt(0);
              if (strip != null && pos < strip.getChildCount()) {
                  resolved = strip.getChildAt(pos);
              }
          } catch (Exception ignored) {}
          if (pos >= 0 && pos < tabAnchorViewsCache.length) tabAnchorViewsCache[pos] = resolved;
          return resolved;
      }

      /**
       * Compact popup — Instagram-style small card with a checkmark next to
       * the active filter. Only ever invoked for the Reels tab.
       */
      private void showFilterPopup(TabLayout.Tab tab) {
          if (isFinishing() || isDestroyed()) return;
          android.view.View anchor = tabAnchorView(tab);
          float density = getResources().getDisplayMetrics().density;

          android.widget.LinearLayout menu = new android.widget.LinearLayout(this);
          menu.setOrientation(android.widget.LinearLayout.VERTICAL);
          android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
          bg.setColor(0xFFFAFAFA);
          bg.setCornerRadius(16 * density);
          menu.setBackground(bg);
          menu.setElevation(8 * density);
          int padV = (int) (4 * density);
          menu.setPadding(0, padV, 0, padV);

          android.widget.PopupWindow popup = new android.widget.PopupWindow(
                  menu, (int) (190 * density), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true);
          popup.setElevation(8 * density);
          popup.setOutsideTouchable(true);
          popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));

          String[] labels = {"All", "Oldest", "Newest", "Most viewed", "Not watched"};
          int[]    filters = {FILTER_ALL, FILTER_OLDEST, FILTER_NEWEST, FILTER_VIEWED, FILTER_NOT_WATCHED};

          for (int i = 0; i < labels.length; i++) {
              final int filter = filters[i];
              android.widget.LinearLayout row = new android.widget.LinearLayout(this);
              row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
              row.setGravity(android.view.Gravity.CENTER_VERTICAL);
              row.setPadding((int) (18 * density), (int) (13 * density), (int) (18 * density), (int) (13 * density));
              row.setBackground(getResources().getDrawable(android.R.drawable.list_selector_background, null));

              android.widget.TextView tv = new android.widget.TextView(this);
              tv.setText(labels[i]);
              tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f);
              tv.setTextColor(0xFF111111);
              android.widget.LinearLayout.LayoutParams tvLp = new android.widget.LinearLayout.LayoutParams(
                      0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
              row.addView(tv, tvLp);

              if (filter == activeFilter) {
                  android.widget.TextView check = new android.widget.TextView(this);
                  check.setText("\u2713");
                  check.setTextColor(getResources().getColor(R.color.brand_primary, null));
                  check.setTypeface(null, android.graphics.Typeface.BOLD);
                  check.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
                  row.addView(check);
              }

              row.setOnClickListener(v -> {
                  activeFilter = filter;
                  applyFilter();
                  popup.dismiss();
              });
              menu.addView(row);

              if (i < labels.length - 1) {
                  android.view.View divider = new android.view.View(this);
                  divider.setBackgroundColor(0xFFEDEDED);
                  menu.addView(divider, android.view.ViewGroup.LayoutParams.MATCH_PARENT, (int) density);
              }
          }

          popup.showAsDropDown(anchor, 0, (int) (6 * density));
      }

      private void applyFilter() {
          if (adapter == null) return;
          List<ReelModel> source = activeTabData();
          // Posts tab (leftmost): sort/other chips still apply, but only
          // ever within the photo-only subset — never mix videos back in.
          if (isPostsTabActive()) source = filterPhotoPostsOnly(source);
          List<ReelModel> filtered;
          switch (activeFilter) {
              case FILTER_OLDEST:
                  filtered = new ArrayList<>(source);
                  filtered.sort((a, b2) -> Long.compare(a.timestamp, b2.timestamp));
                  break;
              case FILTER_NEWEST:
                  filtered = new ArrayList<>(source);
                  filtered.sort((a, b2) -> Long.compare(b2.timestamp, a.timestamp));
                  break;
              case FILTER_VIEWED:
                  filtered = new ArrayList<>(source);
                  filtered.sort((a, b2) -> b2.viewsCount - a.viewsCount);
                  break;
              case FILTER_NOT_WATCHED:
                  filtered = new ArrayList<>();
                  for (ReelModel r : source) {
                      if (r.reelId == null || !watchedReelIdsCache.contains(r.reelId)) filtered.add(r);
                  }
                  break;
              default:
                  filtered = source;
          }
          adapter.setFilteredData(filtered);
      }

      // ── Tabs ──────────────────────────────────────────────────────────────

    private void setupTabs() {
        // PERF (ultra): tiny helper used below to defer non-critical grid-
        // color work until the main thread's message queue is COMPLETELY
        // empty — a stronger guarantee than View.post(), which only queues
        // the work after whatever is *currently* queued (which can still
        // land inside the same burst of work that produces the first
        // frame, e.g. if input/layout messages are already pending). An
        // IdleHandler only fires once there is truly nothing left for the
        // main thread to do, which in practice means the first frame has
        // already been measured, laid out, drawn, AND handed off to
        // SurfaceFlinger — so this work can never compete with the pixels
        // the user is waiting to see.
        if (tabLayout == null) return;

        // Instagram-style: this screen opens with Reels as the active tab,
        // not the leftmost Posts tab (TabLayout otherwise auto-selects
        // position 0). Select it BEFORE the listener below is attached, so
        // this only moves the visual highlight — it doesn't fire
        // onTabSelected and re-run the tab-switch pipeline (data load for
        // the Reels tab already happens further down onCreate()).
        TabLayout.Tab defaultReelsTab = tabLayout.getTabAt(REELS_STRIP_POSITION);
        if (defaultReelsTab != null) defaultReelsTab.select();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                int newPos = tab.getPosition();
                // Direction for the ViewPager2-style slide: +1 moving right
                // (tab strip position increases), -1 moving left. No slide
                // (direct swap) on the very first tab selection at cold start.
                int direction = (lastTabStripPosition < 0) ? 0
                        : (newPos > lastTabStripPosition ? 1 : (newPos < lastTabStripPosition ? -1 : 0));
                lastTabStripPosition = newPos;

                Runnable applyTabSwitch = () -> {
                    activeTabPosition = newPos;
                    activeTab = VISIBLE_TAB_DATA[activeTabPosition];
                    exitMultiSelectMode();
                    // FIX: previously the header's collapsed/expanded scroll
                    // state just carried over across tabs — scroll down on
                    // Reels to collapse the header, switch to Liked, and it's
                    // still collapsed there even though Liked hasn't been
                    // scrolled at all. Instagram resets the header on every tab
                    // change; do the same here so each tab always starts fresh.
                    if (appBarLayout != null) appBarLayout.setExpanded(true, true);
                    boolean isSeries = (activeTab == TAB_SERIES);
                    if (rvSeries != null) rvSeries.setVisibility(isSeries ? android.view.View.VISIBLE : android.view.View.GONE);
                    if (rvReels  != null) rvReels.setVisibility(isSeries  ? android.view.View.GONE   : android.view.View.VISIBLE);
                    // BUG FIX: the adapter is one shared instance across the
                    // Reels/Liked/Saved/Repost tabs, so it must be re-pointed at
                    // THIS tab's own backing list every time the tab changes —
                    // otherwise it keeps showing whichever list it was last
                    // pointed at (e.g. Liked/Saved/Repost all showing Reels).
                    // A pagination footer left over from the tab just switched
                    // away from must never survive as a phantom row here — the
                    // setDataList() call right below already fires its own
                    // structural notify that will reflect the corrected count,
                    // so this is a plain flag reset, no separate notify needed.
                    if (adapter.isLoadingFooterVisible()) adapter.resetLoadingFooterState();
                    footerPositionAtShow = -1;
                    if (!isSeries) {
                        adapter.setDataList(activeTabData());
                        // Posts tab (leftmost, Instagram-style): show photo
                        // posts only — filter on top of the just-set full list.
                        if (isPostsTabActive()) applyFilter();
                    }
                    if (isSeries ? seriesTabData.isEmpty() : activeTabData().isEmpty()) loadCurrentTab(true);
                    else { refreshEmptyState(); updateViewAllButton(); }
                    // Each tab keeps its OWN accent color — re-apply whichever
                    // color (or default) belongs to the tab we just switched to.
                    applyGridAccentColorForActiveTab();
                };

                if (direction == 0) applyTabSwitch.run();
                else slideSwapGridContent(direction, applyTabSwitch);
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {
                if (activeTab == TAB_REELS) {
                    showFilterPopup(tab);
                } else if (activeTab == TAB_SERIES && rvSeries != null) {
                    rvSeries.smoothScrollToPosition(0);
                } else if (rvReels != null) {
                    rvReels.smoothScrollToPosition(0);
                }
            }
        });

        // Owner-only: long-press a tab to recolor JUST THAT tab's indicator/
        // icon AND the grid's thumbnail separator lines — each tab keeps its
        // own color independently (same per-item pattern as the bio chips).
        // PERF: deferred via post() instead of running inline inside
        // onCreate's setupTabs() call — wiring 5 long-click listeners is
        // cheap on its own, but every non-essential piece of work pulled
        // out of the synchronous onCreate path is one less thing competing
        // with the very first measure/layout/draw pass, so the screen's
        // first frame goes up sooner. The listeners are only needed once
        // the user can actually interact with the tabs, which is always
        // after that first frame anyway.
        // Grid tab color picker moved to the 3-dot menu only (see
        // setupMoreMenu() → "Grid Color", applies to the currently active
        // tab) — no longer wired as a long-press here.
    }

    /**
     * PERF (ultra): runs {@code r} only once the main thread's message
     * queue is completely idle — i.e. only AFTER the first frame has
     * actually been measured/laid-out/drawn and handed to the system to
     * present, not merely "queued after whatever is currently pending"
     * like {@code View.post()}. Used for grid-tab-color work that has zero
     * urgency (long-press listeners, re-tinting after a Firebase fetch)
     * so it can never contend with the frame the user is waiting to see.
     * Runs at most once per call (IdleHandler removes itself via the
     * {@code false} return).
     */
    private static void runWhenMainThreadIdle(Runnable r) {
        android.os.Looper.myQueue().addIdleHandler(() -> {
            r.run();
            return false;
        });
    }

    /** Returns the tab key string ("reels"/"liked"/"saved"/"repost"/"series") for a tab position, or null if out of range. */
    private String gridTabKey(int tabPos) {
        if (tabPos < 0 || tabPos >= GRID_TAB_KEYS.length) return null;
        return GRID_TAB_KEYS[tabPos];
    }

    /**
     * Looks up the currently ACTIVE tab's own saved color (falling back to
     * the legacy single color for the Reels tab only, for users upgrading
     * from the old shared-color version), then applies it to that tab's
     * indicator/icon tint AND the currently-visible grid's background —
     * exactly like {@link #applyGridAccentColor(String)} used to, except
     * now it's per-tab instead of one color shared by all tabs.
     */
    private void applyGridAccentColorForActiveTab() {
        String hex = (activeTab >= 0 && activeTab < gridAccentColorsByTab.length)
                ? gridAccentColorsByTab[activeTab] : null;
        if (hex == null && activeTab == TAB_REELS) hex = legacyGridAccentColorHex; // one-time fallback
        applyGridAccentColor(hex);
    }

    /** Memoized hex→int parse — avoids re-parsing the same color string on every tab revisit. */
    private Integer parseGridColorCached(String hex) {
        if (hex == null || hex.isEmpty()) return null;
        Integer cached = gridColorParseCache.get(hex);
        if (cached != null) return cached;
        try {
            int c = android.graphics.Color.parseColor(hex);
            gridColorParseCache.put(hex, c);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** Memoized per-color tab-icon-tint ColorStateList (selected=color, unselected=~65% alpha of color). */
    private android.content.res.ColorStateList tabIconTintFor(int color) {
        android.content.res.ColorStateList cached = gridTabIconTintCache.get(color);
        if (cached != null) return cached;
        int dimmed = (0xA6 << 24) | (color & 0x00FFFFFF);
        android.content.res.ColorStateList csl = new android.content.res.ColorStateList(
                new int[][]{ {android.R.attr.state_selected}, {} },
                new int[]{ color, dimmed });
        gridTabIconTintCache.put(color, csl);
        return csl;
    }

    private int getGridDefaultIndicatorColor() {
        if (gridDefaultIndicatorColorCached == null) {
            gridDefaultIndicatorColorCached =
                    resolveAttrColor(com.google.android.material.R.attr.colorOnSurface, 0xFF111111);
        }
        return gridDefaultIndicatorColorCached;
    }

    private android.content.res.ColorStateList getGridDefaultTabIconTint() {
        if (gridDefaultTabIconTintCached == null) {
            gridDefaultTabIconTintCached =
                    androidx.core.content.ContextCompat.getColorStateList(this, R.color.tab_icon_tint_selector);
        }
        return gridDefaultTabIconTintCached;
    }

    private int getGridDefaultGutterColor() {
        if (!gridDefaultGutterColorResolved) {
            gridDefaultGutterColorCached =
                    androidx.core.content.ContextCompat.getColor(this, R.color.reel_grid_gutter);
            gridDefaultGutterColorResolved = true;
        }
        return gridDefaultGutterColorCached;
    }

    /**
     * Applies (or clears) the custom accent color for whichever tab is
     * CURRENTLY active, across BOTH that tab's indicator/icon and the
     * grid's thumbnail separator lines:
     *  - Active tab's indicator + icon tint → the picked color (this is
     *    what keeps the active tab "colourful" instead of plain
     *    black/white).
     *  - The visible grid's background (rvSeries for the Series tab,
     *    rvReels for every other tab) → the picked color, which is what
     *    actually shows through the gaps WhiteGridDecoration leaves
     *    between thumbnails (those gaps have never drawn their own color
     *    — they just reveal whatever's behind them).
     * Null/empty hex resets both back to the original theme-aware defaults.
     * Note: this only ever touches the ACTIVE tab's indicator/icon (a
     * TabLayout only has one indicator color at a time) — each tab's own
     * color is re-applied automatically whenever that tab becomes active,
     * via {@link #applyGridAccentColorForActiveTab()}.
     *
     * PERF: every value here is resolved through a cache, and every native
     * setter (setSelectedTabIndicatorColor / setTabIconTint /
     * setBackgroundColor) is skipped if the target is already showing that
     * exact value. This matters because this method runs on every single
     * tab switch/swipe/reselect — without the guards, fast swiping across
     * tabs would force a fresh indicator recolor + icon tint allocation +
     * RecyclerView background invalidate on every frame of the swipe.
     */
    private void applyGridAccentColor(String hex) {
        Integer parsed = parseGridColorCached(hex);
        int indicatorColor = parsed != null ? parsed : getGridDefaultIndicatorColor();
        android.content.res.ColorStateList tint = parsed != null ? tabIconTintFor(parsed) : getGridDefaultTabIconTint();
        int bgColor = parsed != null ? parsed : getGridDefaultGutterColor();

        if (tabLayout != null) {
            if (lastAppliedIndicatorColor == null || lastAppliedIndicatorColor != indicatorColor) {
                tabLayout.setSelectedTabIndicatorColor(indicatorColor);
                lastAppliedIndicatorColor = indicatorColor;
            }
            if (lastAppliedTabIconTint != tint) {
                tabLayout.setTabIconTint(tint);
                lastAppliedTabIconTint = tint;
            }
        }

        boolean isSeries = (activeTab == TAB_SERIES);
        android.view.View activeGrid = isSeries ? rvSeries : rvReels;
        if (activeGrid != null) {
            Integer lastBg = isSeries ? lastAppliedRvSeriesBgColor : lastAppliedRvReelsBgColor;
            if (lastBg == null || lastBg != bgColor) {
                activeGrid.setBackgroundColor(bgColor);
                if (isSeries) lastAppliedRvSeriesBgColor = bgColor;
                else          lastAppliedRvReelsBgColor  = bgColor;
            }
        }
    }

    /**
     * Long-press entry point (isSelf only) — opens the shared rainbow color
     * picker pre-filled with THIS tab's current color, and on pick,
     * persists it under this tab's own key
     * (reels/users/{targetUid}/gridAccentColors/{tabKey}) and — if this tab
     * is the one currently active — applies it immediately. Other tabs keep
     * whatever color (or default) they already had; picking a color for one
     * tab never touches any other tab's color.
     */
    private void openGridAccentColorPicker(int tabPos) {
        if (!isSelf || targetUid == null) return;
        String key = gridTabKey(tabPos);
        if (key == null) return;
        String currentHex = gridAccentColorsByTab[tabPos];
        if (currentHex == null && tabPos == TAB_REELS) currentHex = legacyGridAccentColorHex;
        final String fCurrentHex = currentHex;
        com.callx.app.utils.RainbowStripColorPickerBottomSheet.show(
                this, "Grid Accent Color", fCurrentHex,
                fCurrentHex != null && !fCurrentHex.isEmpty(),
                colorHex -> {
                    gridAccentColorsByTab[tabPos] = colorHex;
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reels/users").child(targetUid)
                        .child("gridAccentColors").child(key)
                        .setValue(colorHex);
                    if (tabPos == activeTab) applyGridAccentColor(colorHex);
                });
    }

    private List<ReelModel> activeTabData() {
        switch (activeTab) {
            case TAB_LIKED:  return likedTabData;
            case TAB_SAVED:  return savedTabData;
            case TAB_REPOST: return repostsTabData;
            case TAB_DUET:   return duetTabData;
            case TAB_COLLAB_REPOST: return collabRepostTabData;
            default:         return reelsTabData;
        }
    }

    /** True when the leftmost "Posts" tab (photo-only, Instagram-style) is the one showing. */
    private boolean isPostsTabActive() {
        return activeTab == TAB_REELS && activeTabPosition == POSTS_STRIP_POSITION;
    }

    /** Photo-only subset of a reel list — used to back the Posts tab's grid. */
    private List<ReelModel> filterPhotoPostsOnly(List<ReelModel> source) {
        List<ReelModel> out = new ArrayList<>();
        for (ReelModel r : source) {
            if (r != null && "photo_slideshow".equals(r.mediaType)) out.add(r);
        }
        return out;
    }

    /**
     * The list currently BACKING the grid on screen — same as
     * activeTabData() for every tab except Posts, where the adapter shows a
     * filtered COPY (not reelsTabData itself). Any code that maps an
     * adapter position to a ReelModel (tap-to-open, like, long-press,
     * multi-select) must index into THIS, not activeTabData(), or it'll
     * grab the wrong reel while the Posts tab is active.
     */
    private List<ReelModel> currentGridData() {
        return isPostsTabActive() ? filterPhotoPostsOnly(activeTabData()) : activeTabData();
    }

    // ── Swipe left/right on the grid to switch tabs (mirrors Reels/Repost/Duet) ──

    private void switchToTab(int newPos) {
        if (newPos < 0 || newPos >= VISIBLE_TAB_DATA.length) return; // out of range — no-op at edges
        if (tabLayout == null) return;
        TabLayout.Tab t = tabLayout.getTabAt(newPos);
        if (t != null) t.select();
    }

    /**
     * ViewPager2-style slide between grid tabs: whichever grid is currently
     * showing (rv_reels for Reels/Repost/Duet/Collab/Liked/Saved, rv_series
     * for Series) slides out in {@code direction} while fading, THEN
     * {@code applyTabSwitch} runs (swaps the adapter's backing list /
     * toggles rv_reels↔rv_series visibility / kicks off loadCurrentTab — all
     * the existing tab-change logic, untouched), and finally whichever grid
     * ends up visible slides in from the opposite side. Both the tab-strip
     * click path and the existing drag-swipe path funnel through
     * TabLayout.Tab#select() → onTabSelected(), so this one place animates
     * both without duplicating the direction math at the gesture layer.
     */
    private void slideSwapGridContent(int direction, Runnable applyTabSwitch) {
        View outgoing = (activeTab == TAB_SERIES) ? rvSeries : rvReels;
        if (outgoing == null || outgoing.getWidth() <= 0) {
            applyTabSwitch.run(); // not laid out yet (e.g. very early) — just swap
            return;
        }
        float distance = outgoing.getWidth() * 0.35f; // subtle slide, not a full-screen page turn
        outgoing.animate().cancel();
        // PERF: promote to a hardware layer for the duration of the slide —
        // translationX+alpha on a RecyclerView otherwise re-draws/re-
        // composites every visible child on every animation frame. A
        // hardware layer rasterizes the view ONCE into a GPU texture and
        // then just cheaply transforms/blends that texture per frame,
        // which is what keeps this slide smooth on a media-heavy grid.
        // Always turned back to LAYER_TYPE_NONE once the animation ends —
        // layers cost GPU memory and must not be left on for normal
        // (non-animating) scrolling.
        outgoing.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        outgoing.animate()
                .translationX(-direction * distance)
                .alpha(0f)
                .setDuration(120)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    outgoing.setTranslationX(0f);
                    outgoing.setAlpha(1f);
                    outgoing.setLayerType(View.LAYER_TYPE_NONE, null);

                    applyTabSwitch.run();

                    View incoming = (activeTab == TAB_SERIES) ? rvSeries : rvReels;
                    if (incoming == null) return;
                    incoming.animate().cancel();
                    incoming.setTranslationX(direction * distance);
                    incoming.setAlpha(0f);
                    incoming.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    incoming.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(200)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .withEndAction(() -> incoming.setLayerType(View.LAYER_TYPE_NONE, null))
                            .start();
                })
                .start();
    }

    private void setupSwipeBetweenTabs() {
        final float touchSlopPx = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        // ADVANCED SWIPE: lowered from 60dp/200dp so a light, short flick
        // switches tabs instead of needing a big deliberate drag.
        final float swipeThresholdPx = 24 * getResources().getDisplayMetrics().density;
        final float swipeVelocityPx  = 80 * getResources().getDisplayMetrics().density; // px/sec min fling speed

        // One GestureDetector PER view (state must not be shared between rv_reels / rv_series / layout_empty).
        RecyclerView.OnItemTouchListener reelsListener =
                buildSwipeListener(rvReels, gridLayoutManager, touchSlopPx, swipeThresholdPx, swipeVelocityPx);
        RecyclerView.OnItemTouchListener seriesListener =
                buildSwipeListener(rvSeries, seriesLayoutManager, touchSlopPx, swipeThresholdPx, swipeVelocityPx);

        if (rvReels  != null && reelsListener  != null) rvReels.addOnItemTouchListener(reelsListener);
        if (rvSeries != null && seriesListener != null) rvSeries.addOnItemTouchListener(seriesListener);

        // IMPORTANT: refreshEmptyState() sets rvReels.setVisibility(GONE) and shows
        // layoutEmpty instead whenever the active tab (Liked/Saved/Reposts) has no
        // items. Since a GONE view receives no touch events at all, the swipe
        // listener above never fires there — which is why swiping used to get
        // stuck on the first empty tab and couldn't go further. Attach the same
        // fling detection to layoutEmpty as a plain touch listener so swiping keeps
        // working through empty tabs too.
        //
        // NOTE: layoutEmpty is a plain (non-clickable) LinearLayout. Returning
        // false from ACTION_DOWN here meant the view was never registered as a
        // touch target, so ACTION_MOVE/ACTION_UP never reached it afterwards —
        // the GestureDetector only ever saw the DOWN event and could never
        // detect a fling. Returning true keeps the full gesture flowing to us.
        // This is still safe for any CTA buttons inside the empty state: a
        // touch that starts on a child is routed straight to that child by
        // Android before it ever reaches this parent-level listener.
        //
        // BUG FIX: the block above only ever handled horizontal (left/right)
        // flings for tab switching. Vertical (up/down) drags were swallowed
        // here and never reached the CoordinatorLayout, so on any tab with
        // zero items (Liked/Saved/Reposts/Series with no content) the profile
        // header could not collapse or re-expand and the screen felt "stuck" —
        // up/down scroll appeared completely dead. rvReels doesn't have this
        // problem because RecyclerView is a NestedScrollingChild by default
        // and drives the AppBarLayout's collapse via CoordinatorLayout's
        // native nested-scroll chain. layoutEmpty is a plain View, so it
        // never participated in that chain. Fix: manually dispatch vertical
        // drag deltas through a NestedScrollingChildHelper, exactly the way a
        // NestedScrollingChild would, so empty tabs scroll (collapse/expand
        // the header) just like the grid does.
        // BUG FIX 5 of 5: the block above only ever tracked drag deltas
        // (ACTION_MOVE), never fling velocity. A quick flick on an empty tab
        // would move the header a tiny bit and then just stop dead the
        // moment the finger lifts — no momentum — unlike rvReels, which gets
        // fling handling for free from RecyclerView. Track velocity with a
        // VelocityTracker and dispatch it as a nested fling on ACTION_UP so
        // empty tabs get the same throw/momentum feel as the grid.
        if (layoutEmpty != null) {
            final android.view.GestureDetector emptyStateDetector =
                    createTabSwipeGestureDetector(swipeThresholdPx, swipeVelocityPx);
            emptyStateScrollHelper = new androidx.core.view.NestedScrollingChildHelper(layoutEmpty);
            emptyStateScrollHelper.setNestedScrollingEnabled(true);

            final int[] lastY = {0};
            final int[] consumed = new int[2];
            final int[] offsetInWindow = new int[2];
            final android.view.VelocityTracker[] velocityTracker = {null};

            layoutEmpty.setOnTouchListener((v, e) -> {
                emptyStateDetector.onTouchEvent(e);

                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastY[0] = (int) (e.getRawY() + 0.5f);
                        emptyStateScrollHelper.startNestedScroll(
                                ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH);
                        velocityTracker[0] = android.view.VelocityTracker.obtain();
                        velocityTracker[0].addMovement(e);
                        break;

                    case MotionEvent.ACTION_MOVE: {
                        if (velocityTracker[0] != null) velocityTracker[0].addMovement(e);
                        int y = (int) (e.getRawY() + 0.5f);
                        int dy = lastY[0] - y; // positive dy == finger moving up
                        lastY[0] = y;
                        consumed[0] = 0;
                        consumed[1] = 0;

                        // Let the AppBarLayout (via CoordinatorLayout) consume the
                        // scroll first — this is what actually collapses/expands
                        // the header — same order RecyclerView uses internally.
                        if (emptyStateScrollHelper.dispatchNestedPreScroll(
                                dy, 0, consumed, offsetInWindow, ViewCompat.TYPE_TOUCH)) {
                            lastY[0] -= consumed[1];
                        }
                        emptyStateScrollHelper.dispatchNestedScroll(
                                0, consumed[1], 0, dy - consumed[1],
                                offsetInWindow, ViewCompat.TYPE_TOUCH);
                        break;
                    }

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (velocityTracker[0] != null && e.getActionMasked() == MotionEvent.ACTION_UP) {
                            velocityTracker[0].addMovement(e);
                            velocityTracker[0].computeCurrentVelocity(1000);
                            // Screen-space Y velocity is inverted relative to
                            // "upward scroll" dy convention used above.
                            float flingVelocityY = -velocityTracker[0].getYVelocity();
                            if (!emptyStateScrollHelper.dispatchNestedPreFling(0, flingVelocityY)) {
                                emptyStateScrollHelper.dispatchNestedFling(0, flingVelocityY, true);
                            }
                        }
                        if (velocityTracker[0] != null) {
                            velocityTracker[0].recycle();
                            velocityTracker[0] = null;
                        }
                        emptyStateScrollHelper.stopNestedScroll(ViewCompat.TYPE_TOUCH);
                        break;
                    }
                }
                return true;
            });
        }
    }

    /** Builds a GestureDetector that switches tabs on a left/right fling. */
    private android.view.GestureDetector createTabSwipeGestureDetector(
            final float swipeThresholdPx, final float swipeVelocityPx) {
        return new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                        if (e1 == null) return false;
                        float dx = e2.getX() - e1.getX();
                        float dy = e2.getY() - e1.getY();
                        if (Math.abs(dx) > swipeThresholdPx
                                && Math.abs(dx) > Math.abs(dy)
                                && Math.abs(velocityX) > swipeVelocityPx) {
                            // Swipe left (finger moves right→left) → next tab.
                            // Swipe right (finger moves left→right) → previous tab.
                            switchToTab(dx < 0 ? activeTabPosition + 1 : activeTabPosition - 1);
                            return true;
                        }
                        return false;
                    }
                });
    }

    private RecyclerView.OnItemTouchListener buildSwipeListener(
            final RecyclerView rv, final SwipeAwareGridLayoutManager lm,
            final float touchSlopPx, final float swipeThresholdPx, final float swipeVelocityPx) {
        if (rv == null) return null;

        final android.view.GestureDetector gestureDetector =
                createTabSwipeGestureDetector(swipeThresholdPx, swipeVelocityPx);

        return new RecyclerView.OnItemTouchListener() {
            private float downX, downY;
            private boolean draggingHorizontally = false;
            // Once the gesture is resolved as vertical, don't keep re-checking
            // every move event — leave the grid's normal scroll alone until
            // finger lift.
            private boolean resolvedVertical = false;

            private void unlockVerticalScroll() {
                if (lm != null) lm.setVerticalScrollEnabled(true);
            }

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent e) {
                gestureDetector.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX(); downY = e.getY();
                        draggingHorizontally = false;
                        resolvedVertical = false;
                        unlockVerticalScroll();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (resolvedVertical) break;
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (!draggingHorizontally) {
                            boolean movedEnough = Math.abs(dx) > touchSlopPx || Math.abs(dy) > touchSlopPx;
                            if (movedEnough) {
                                if (Math.abs(dx) > Math.abs(dy)) {
                                    // Horizontal intent: lock the grid's own vertical
                                    // scroll (and the header collapse it drives via
                                    // nested scrolling) OFF right now, in the same
                                    // event pass that RecyclerView itself would use
                                    // to decide whether to start a vertical drag —
                                    // this is what removes the scroll/swipe conflict
                                    // instead of just racing it.
                                    draggingHorizontally = true;
                                    if (lm != null) lm.setVerticalScrollEnabled(false);
                                    android.view.ViewParent p = recyclerView.getParent();
                                    if (p != null) p.requestDisallowInterceptTouchEvent(true);
                                } else {
                                    // Vertical intent: leave scrolling enabled and stop
                                    // evaluating direction for the rest of this gesture.
                                    resolvedVertical = true;
                                }
                            }
                        }
                        return draggingHorizontally;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        draggingHorizontally = false;
                        resolvedVertical = false;
                        unlockVerticalScroll();
                        android.view.ViewParent parent = recyclerView.getParent();
                        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent e) {
                gestureDetector.onTouchEvent(e);
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    draggingHorizontally = false;
                    resolvedVertical = false;
                    unlockVerticalScroll();
                    android.view.ViewParent parent = recyclerView.getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                }
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallow) {}
        };
    }

    // ── Privacy ───────────────────────────────────────────────────────────

    private void loadAccountPrivacy() {
        if (isSelf) return;
        FirebaseUtils.getUserRef(targetUid).child("isPrivate")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    isAccountPrivate = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                    applyPrivacyState();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void applyPrivacyState() {
        if (isFinishing() || isDestroyed()) return;
        boolean blocked = isAccountPrivate && !isFollowing && !isSelf;
        if (layoutPrivateAccount != null)
            layoutPrivateAccount.setVisibility(blocked ? View.VISIBLE : View.GONE);
        if (rvReels    != null) rvReels.setVisibility(blocked ? View.GONE : View.VISIBLE);
        if (tabLayout  != null) { tabLayout.setAlpha(blocked ? 0.4f : 1f); tabLayout.setEnabled(!blocked); }
    }

    // ── Stats clicks ──────────────────────────────────────────────────────

    private void setupStatsClicks() {
        if (layoutFollowersClick != null)
            layoutFollowersClick.setOnClickListener(v -> openFollowersList());
        else if (tvFollowers != null)
            tvFollowers.setOnClickListener(v -> openFollowersList());

        if (layoutFollowingClick != null)
            layoutFollowingClick.setOnClickListener(v -> openFollowingList());
        else if (tvFollowing != null)
            tvFollowing.setOnClickListener(v -> openFollowingList());
    }

    private void openFollowersList() {
        if (isAccountPrivate && !isFollowing && !isSelf) {
            Toast.makeText(this, "This account is private", Toast.LENGTH_SHORT).show(); return;
        }
        Intent i = new Intent(this, com.callx.app.followers.FollowConnectionsActivity.class);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_UID,       targetUid);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_NAME,      targetName != null ? targetName : "");
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_IS_SELF,   isSelf);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_START_TAB, com.callx.app.followers.FollowConnectionsActivity.TAB_FOLLOWERS);
        startActivity(i);
    }

    private void openFollowingList() {
        if (isAccountPrivate && !isFollowing && !isSelf) {
            Toast.makeText(this, "This account is private", Toast.LENGTH_SHORT).show(); return;
        }
        Intent i = new Intent(this, com.callx.app.followers.FollowConnectionsActivity.class);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_UID,       targetUid);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_NAME,      targetName != null ? targetName : "");
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_IS_SELF,   isSelf);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_START_TAB, com.callx.app.followers.FollowConnectionsActivity.TAB_FOLLOWING);
        startActivity(i);
    }

    // ── Story Ring (Feature 14) ───────────────────────────────────────────

    private void checkActiveStory() {
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        // limitToLast(1) instead of limitToFirst(1): also gives us the
        // NEWEST active story's timestamp in the same query, so we can tell
        // whether this is a story the viewer hasn't seen this profile
        // screen since — needed for the first-time reveal below.
        FirebaseUtils.getUserStatusRef(targetUid)
            .orderByChild("timestamp").startAt((double) cutoff).limitToLast(1)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    boolean hasActive = snap.exists() && snap.getChildrenCount() > 0;
                    long latestTs = 0L;
                    if (hasActive) {
                        for (DataSnapshot child : snap.getChildren()) {
                            Long ts = child.child("timestamp").getValue(Long.class);
                            if (ts != null && ts > latestTs) latestTs = ts;
                        }
                    }
                    handleStoryRingVisibility(hasActive, latestTs);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * Decides whether to (a) hide the ring, (b) show the normal static ring
     * immediately, or (c) play the one-time reveal sweep first.
     *
     * (c) only happens the FIRST time this viewer opens this profile screen
     * after {@code latestStoryTs} was posted — tracked per target user in
     * SharedPreferences so re-opening the same profile later just shows the
     * plain static ring, no repeat animation.
     */
    private void handleStoryRingVisibility(boolean show, long latestStoryTs) {
        if (isFinishing() || isDestroyed() || viewStoryRing == null) return;
        cancelStoryRingReveal();

        if (!show) {
            viewStoryRing.setVisibility(View.GONE);
            return;
        }

        android.content.SharedPreferences prefs =
            getSharedPreferences("story_ring_prefs", MODE_PRIVATE);
        String prefKey = "last_seen_story_ts_" + targetUid;
        long lastSeenTs = prefs.getLong(prefKey, 0L);

        if (latestStoryTs > 0 && latestStoryTs > lastSeenTs) {
            playStoryRingReveal(latestStoryTs, prefKey, prefs);
        } else {
            showStoryRingStatic();
        }
    }

    /**
     * Ring stays hidden for 1s, then sweeps in clockwise from a single point
     * to a full circle over 2s (smooth, one revolution), then settles into
     * the normal fixed gradient ring — exactly once per new story per
     * viewer, then remembered via SharedPreferences.
     */
    private void playStoryRingReveal(long latestStoryTs, String prefKey,
                                      android.content.SharedPreferences prefs) {
        viewStoryRing.setBackground(null);
        viewStoryRing.setVisibility(View.INVISIBLE);

        storyRingRevealRunnable = () -> {
            if (isFinishing() || isDestroyed() || viewStoryRing == null) return;

            com.callx.app.utils.StoryRingRevealDrawable revealDrawable =
                new com.callx.app.utils.StoryRingRevealDrawable(dpToPx(3f));
            viewStoryRing.setBackground(revealDrawable);
            viewStoryRing.setVisibility(View.VISIBLE);

            storyRingRevealAnimator = android.animation.ValueAnimator.ofFloat(0f, 360f);
            storyRingRevealAnimator.setDuration(2000);
            storyRingRevealAnimator.setInterpolator(new android.view.animation.LinearInterpolator());
            storyRingRevealAnimator.addUpdateListener(anim ->
                revealDrawable.setSweepDegrees((float) anim.getAnimatedValue()));
            storyRingRevealAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean wasCancelled = false;
                @Override public void onAnimationCancel(android.animation.Animator animation) {
                    wasCancelled = true;
                }
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (wasCancelled) return; // interrupted (e.g. activity closing) — don't mark as seen
                    // Full circle done — hand off to the normal, cheap,
                    // permanently-static ring. No pulsing, no blinking.
                    showStoryRingStatic();
                    prefs.edit().putLong(prefKey, latestStoryTs).apply();
                }
            });
            storyRingRevealAnimator.start();
        };
        storyRingHandler.postDelayed(storyRingRevealRunnable, 1000);
    }

    /**
     * Normal steady state: gradient while the story is unseen, flat gray
     * once fully seen (Instagram behavior) — checked against the same
     * app-wide StatusCacheManager the Status tab and Reels feed read from,
     * so a story viewed in the new StatusViewerActivity is reflected here
     * immediately, no restart needed.
     */
    private void showStoryRingStatic() {
        if (isFinishing() || isDestroyed() || viewStoryRing == null) return;
        boolean unseen = com.callx.app.cache.StatusCacheManager
                .getInstance(this).hasUnseen(targetUid);
        if (unseen) {
            viewStoryRing.setBackground(
                    com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(3f,
                            getResources().getDisplayMetrics().density));
        } else {
            viewStoryRing.setBackground(
                    androidx.core.content.ContextCompat.getDrawable(this,
                            com.callx.app.core.R.drawable.circle_status_seen));
        }
        viewStoryRing.setVisibility(View.VISIBLE);
    }

    private void cancelStoryRingReveal() {
        if (storyRingRevealRunnable != null) {
            storyRingHandler.removeCallbacks(storyRingRevealRunnable);
            storyRingRevealRunnable = null;
        }
        if (storyRingRevealAnimator != null) {
            storyRingRevealAnimator.cancel();
            storyRingRevealAnimator = null;
        }
    }

    // ── Share Profile (Feature 8) ─────────────────────────────────────────

    private void shareProfile() {
        String deepLink = com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/profile/" + targetUid;
        String name     = targetName != null ? targetName : "a creator";
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, "Check out " + name + "'s Reels on CallX!\n" + deepLink);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Profile link", deepLink));
        startActivity(Intent.createChooser(share, "Share Profile"));
    }

    // ── Verified Badge (Feature 9) ────────────────────────────────────────

    // ══════════════════════════════════════════════════════════════════════
    // Story Highlights — Instagram-style horizontal album row
    // Firebase: statusHighlights/{uid}/{albumId}/{statusId}
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Set up the horizontal highlights RecyclerView with LinearLayoutManager.
     * Must be called once after bindViews(). Data is injected by loadHighlights().
     */
    private void setupHighlights() {
        if (rvHighlights == null) return;
        androidx.recyclerview.widget.LinearLayoutManager lm =
                new androidx.recyclerview.widget.LinearLayoutManager(
                        this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false);
        // Now that the row is match_parent width (no longer nested in a
        // HorizontalScrollView — see activity_user_reels.xml), RecyclerView
        // actually recycles off-screen items. Ask LayoutManager to warm a
        // few extra items just past the visible edge before they're needed,
        // same idea as Instagram's highlights row prefetch.
        lm.setInitialPrefetchItemCount(4);
        rvHighlights.setLayoutManager(lm);
        // The row's own height/width no longer depend on adapter content
        // (match_parent width, fixed 110dp height), so RecyclerView can skip
        // re-measuring itself on every adapter change.
        rvHighlights.setHasFixedSize(true);
        rvHighlights.setItemViewCacheSize(6);
        rvHighlights.setItemAnimator(null);
        rvHighlights.setNestedScrollingEnabled(false);
        // FIX: with layoutEmpty and the header both now actively
        // participating in vertical nested scrolling, be explicit that this
        // row's horizontal drags must never be stolen by an ancestor's
        // vertical scroll — RecyclerView normally figures this out itself
        // via onInterceptTouchEvent, but stating it here removes any
        // ambiguity for whoever adds a vertical gesture (e.g. long-press
        // drag-to-reorder) to this row later.
        rvHighlights.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    break;
            }
            return false; // let RecyclerView still handle the event normally
        });

        java.util.List<HighlightsRowAdapter.HighlightAlbum> adapterItems = new java.util.ArrayList<>();
        if (isSelf) adapterItems.add(HighlightsRowAdapter.HighlightAlbum.newButton()); // "+" first

        highlightsAdapter = new HighlightsRowAdapter(this, adapterItems, isSelf,
                new HighlightsRowAdapter.Listener() {

            @Override public void onAlbumClicked(HighlightsRowAdapter.HighlightAlbum album) {
                openHighlightAlbum(album);
            }

            @Override public void onAlbumLongPressed(HighlightsRowAdapter.HighlightAlbum album, int pos) {
                showHighlightManageSheet(album, pos);
            }

            @Override public void onNewClicked() {
                openCreateHighlight();
            }
        });
        rvHighlights.setAdapter(highlightsAdapter);
    }

    /**
     * Push the current highlightAlbums list into the already-attached
     * adapter via DiffUtil (see HighlightsRowAdapter.submitAlbums) instead
     * of building a brand-new adapter + calling setAdapter() again — that
     * used to throw away the RecyclerView's recycled view pool and re-fetch
     * every cover image even for albums that hadn't changed.
     */
    private void rebuildHighlightsAdapter() {
        if (highlightsAdapter == null) { setupHighlights(); return; }
        java.util.List<HighlightsRowAdapter.HighlightAlbum> adapterItems = new java.util.ArrayList<>();
        if (isSelf) adapterItems.add(HighlightsRowAdapter.HighlightAlbum.newButton()); // "+" first
        adapterItems.addAll(highlightAlbums);
        highlightsAdapter.submitAlbums(adapterItems);
    }

    /**
     * Debounced/coalesced entry point for every rebuild trigger that isn't a
     * direct user tap (loadHighlights' initial bind, ring-color overrides,
     * seen-state sync). Also thread-safe: runOnUiThread() runs immediately
     * if already on the UI thread, or posts otherwise, so callers never need
     * to reason about which thread a Firebase callback landed on.
     */
    private void scheduleHighlightsRebuild() {
        if (highlightsRebuildQueued) return;
        highlightsRebuildQueued = true;
        runOnUiThread(() -> {
            highlightsRebuildQueued = false;
            rebuildHighlightsAdapter();
        });
    }

    /**
     * Load highlight albums from Firebase.
     * Path: statusHighlights/{targetUid}/{albumId}/<statusItems>
     * Builds one HighlightAlbum per albumId using the first child as the cover.
     */
    private void loadHighlights() {
        if (targetUid == null) return;

        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("statusHighlights")
            .child(targetUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {

            @Override
            public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                highlightAlbums.clear();

                for (com.google.firebase.database.DataSnapshot albumSnap : snap.getChildren()) {
                    String albumId = albumSnap.getKey();
                    if (albumId == null) continue;

                    String albumName    = null;
                    String coverUrl     = null;
                    String coverBgColor = null;
                    int    itemCount    = 0;

                    for (com.google.firebase.database.DataSnapshot item : albumSnap.getChildren()) {
                        itemCount++;
                        if (itemCount == 1) {
                            // Use first item as cover
                            try {
                                String hn = item.child("highlightAlbumName").getValue(String.class);
                                if (hn != null) albumName = hn;
                                String tu = item.child("thumbnailUrl").getValue(String.class);
                                String mu = item.child("mediaUrl").getValue(String.class);
                                coverUrl = (tu != null && !tu.isEmpty()) ? tu : mu;
                                coverBgColor = item.child("bgColor").getValue(String.class);
                            } catch (Exception ignored) {}
                        }
                    }

                    if (itemCount == 0) continue; // skip empty albums
                    if (albumName == null || albumName.isEmpty()) albumName = toDisplayName(albumId);

                    HighlightsRowAdapter.HighlightAlbum album = new HighlightsRowAdapter.HighlightAlbum(
                            albumId, albumName, coverUrl, coverBgColor, itemCount);
                    // Pulse the ring for any album that wasn't here on the
                    // previous load (e.g. just created via CreateHighlightActivity) —
                    // never on the very first load of this screen, or every
                    // existing highlight would pulse the moment the profile opens.
                    if (highlightsLoadedOnce && !knownHighlightAlbumIds.contains(albumId)) {
                        album.justAdded = true;
                    }
                    highlightAlbums.add(album);
                }
                knownHighlightAlbumIds.clear();
                for (HighlightsRowAdapter.HighlightAlbum a : highlightAlbums) knownHighlightAlbumIds.add(a.albumId);
                highlightsLoadedOnce = true;

                runOnUiThread(() -> {
                    boolean hasContent = !highlightAlbums.isEmpty() || isSelf;
                    if (hsvHighlights  != null) hsvHighlights.setVisibility(hasContent ? android.view.View.VISIBLE : android.view.View.GONE);
                    if (dividerHighlights != null) dividerHighlights.setVisibility(hasContent ? android.view.View.VISIBLE : android.view.View.GONE);
                    rebuildHighlightsAdapter();
                });

                // Both run right after the base list binds — fire together so
                // their (independent) Firebase reads overlap instead of
                // chaining, and route their UI updates through the coalesced
                // scheduleHighlightsRebuild() below instead of each forcing
                // its own separate adapter pass.
                loadHighlightRingOverrides();
                applyHighlightSeenState();
            }

            @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {
                // Silently fail — highlights are non-critical
                if (isSelf) {
                    runOnUiThread(() -> {
                        if (hsvHighlights != null) hsvHighlights.setVisibility(android.view.View.VISIBLE);
                        rebuildHighlightsAdapter();
                    });
                }
            }
        });
    }

    /**
     * Pulls the custom ring color/mode (if any) set per-album via
     * StatusHighlightSettingsBottomSheet / StatusAddToHighlightBottomSheet's
     * ring color picker, from statusHighlightMeta/{targetUid}/{albumId}, and
     * merges it into the already-loaded highlightAlbums so the row shows the
     * user's chosen ring instead of the default gradient.
     */
    private void loadHighlightRingOverrides() {
        if (targetUid == null) return;
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("statusHighlightMeta")
            .child(targetUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    if (!snap.exists()) return;
                    boolean changed = false;
                    for (HighlightsRowAdapter.HighlightAlbum album : highlightAlbums) {
                        com.google.firebase.database.DataSnapshot metaSnap = snap.child(album.albumId);
                        if (!metaSnap.exists()) continue;
                        String ringColor = metaSnap.child("ringColor").getValue(String.class);
                        String ringMode  = metaSnap.child("ringMode").getValue(String.class);
                        if (ringColor != null && !ringColor.isEmpty()) {
                            album.ringColor = ringColor;
                            album.ringMode  = ringMode;
                            changed = true;
                        }
                    }
                    if (changed) scheduleHighlightsRebuild();
                }
                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) { }
            });
    }

    /**
     * Instagram-style highlight seen tracking — flips the ring from
     * gradient to flat gray for albums the current viewer has already
     * opened. Never touches isSelf (owners always see their own ring in
     * full color) and never overrides a custom ringColor (that ring is
     * permanent — bindAlbum() checks ringColor before seenByViewer).
     * Two passes: instant local cache first (no network wait), then a
     * Firebase read to pick up albums seen from other devices/sessions.
     */
    private void applyHighlightSeenState() {
        if (isSelf || targetUid == null) return;
        String myUid = safeMyUid();
        if (myUid == null) return;

        boolean localChanged = false;
        for (HighlightsRowAdapter.HighlightAlbum album : highlightAlbums) {
            if (com.callx.app.utils.HighlightSeenState.isSeenLocally(this, targetUid, album.albumId)) {
                album.seenByViewer = true;
                localChanged = true;
            }
        }
        if (localChanged) scheduleHighlightsRebuild();

        com.callx.app.utils.StatusHighlightManager.getHighlightSeenRef(myUid, targetUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                    if (!snap.exists()) return;
                    boolean changed = false;
                    for (HighlightsRowAdapter.HighlightAlbum album : highlightAlbums) {
                        if (!album.seenByViewer && snap.hasChild(album.albumId)) {
                            album.seenByViewer = true;
                            changed = true;
                        }
                    }
                    if (changed) scheduleHighlightsRebuild();
                }
                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) { }
            });
    }

    /**
     * Open StatusViewerActivity for a specific highlight album.
     * Uses Class.forName to avoid hard cross-module dependency.
     */
    private void openHighlightAlbum(HighlightsRowAdapter.HighlightAlbum album) {
        // Instagram-style: mark seen the moment the album is tapped (ring
        // flips to gray right away, doesn't wait for the viewer to close) —
        // only for someone else's highlights, never your own.
        if (!isSelf && targetUid != null && !album.seenByViewer) {
            String myUid = safeMyUid();
            if (myUid != null) {
                album.seenByViewer = true;
                com.callx.app.utils.StatusHighlightManager.markHighlightSeen(this, myUid, targetUid, album.albumId);
                rebuildHighlightsAdapter();
            }
        }
        try {
            Class<?> cls = Class.forName("com.callx.app.viewer.StatusViewerActivity");
            android.content.Intent i = new android.content.Intent(this, cls);
            i.putExtra("ownerUid",  targetUid);
            i.putExtra("ownerName", album.albumName);
            i.putExtra("highlightAlbumId", album.albumId);
            // Instagram-style: hand over every other highlight ring on this
            // profile, in the same left-to-right order they're shown, so
            // finishing this album's stories auto-continues straight into
            // the next ring instead of just closing the viewer.
            ArrayList<String> queueIds = new ArrayList<>();
            ArrayList<String> queueNames = new ArrayList<>();
            for (HighlightsRowAdapter.HighlightAlbum a : highlightAlbums) {
                if (a.isNew) continue;
                queueIds.add(a.albumId);
                queueNames.add(a.albumName);
            }
            i.putStringArrayListExtra("queueAlbumIds", queueIds);
            i.putStringArrayListExtra("queueAlbumNames", queueNames);
            startActivity(i);
        } catch (ClassNotFoundException ex) {
            Toast.makeText(this, album.albumName, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Open CreateHighlightActivity — the Instagram-style "New Highlight" flow:
     * pick from your own statuses (live + archived), then choose an existing
     * album or create a new one (with optional custom ring color). Same flow
     * as the Status tab's "Add to Highlight", just reached from the "+" on
     * this profile's Highlights row instead of from the status viewer.
     * Uses Class.forName to avoid a hard cross-module dependency on feature-status.
     */
    private void openCreateHighlight() {
        try {
            Class<?> cls = Class.forName("com.callx.app.highlights.CreateHighlightActivity");
            android.content.Intent i = new android.content.Intent(this, cls);
            startActivity(i);
        } catch (ClassNotFoundException | android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "Highlights not available", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Open StatusHighlightsActivity so the self-user can manage all albums
     * (create new, re-order, rename, delete).
     */
    private void openManageHighlights() {
        try {
            Class<?> cls = Class.forName("com.callx.app.highlights.StatusHighlightsActivity");
            android.content.Intent i = new android.content.Intent(this, cls);
            i.putExtra("ownerUid", targetUid);
            startActivity(i);
        } catch (ClassNotFoundException | android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "Highlights manager not available", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Long-press context sheet for self user: rename album, ring color,
     * delete album.
     */
    private void showHighlightManageSheet(HighlightsRowAdapter.HighlightAlbum album, int adapterPos) {
        if (isFinishing() || isDestroyed()) return;

        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(album.albumName)
            .setItems(new String[]{"✏  Rename album", "\uD83C\uDFA8  Ring color", "🗑  Delete album"}, (d, which) -> {
                if      (which == 0) showHighlightRenameDialog(album, adapterPos);
                else if (which == 1) showHighlightRingColorPicker(album, adapterPos);
                else                 confirmDeleteHighlight(album, adapterPos);
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    /**
     * Opens the shared rainbow ring-color sheet (same one used at highlight
     * creation time in CreateHighlightActivity) pre-filled with this
     * album's current custom ring, if any. Picking a color/mode persists it
     * to statusHighlightMeta/{uid}/{albumId}/ringColor+ringMode — PERMANENT,
     * shown regardless of seen state (bindAlbum() checks ringColor first).
     * "Use default" clears the override and falls back to the normal
     * gradient/seen-gray ring.
     */
    private void showHighlightRingColorPicker(HighlightsRowAdapter.HighlightAlbum album, int adapterPos) {
        if (targetUid == null) return;
        com.callx.app.highlights.HighlightRingColorPickerBottomSheet.show(
                this, album.ringColor, album.ringMode,
                album.ringColor != null && !album.ringColor.isEmpty(),
                (colorHex, mode) -> {
                    album.ringColor = colorHex;
                    album.ringMode  = mode;
                    if (colorHex != null) {
                        com.callx.app.utils.StatusHighlightManager.setAlbumRingStyle(
                                targetUid, album.albumId, colorHex, mode);
                    } else {
                        com.callx.app.utils.StatusHighlightManager.clearAlbumRingStyle(
                                targetUid, album.albumId);
                    }
                    if (highlightsAdapter != null) highlightsAdapter.notifyItemChanged(adapterPos);
                });
    }

    private void showHighlightRenameDialog(HighlightsRowAdapter.HighlightAlbum album, int adapterPos) {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(album.albumName);
        et.setSelection(album.albumName != null ? album.albumName.length() : 0);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad / 2, pad, pad / 2);

        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Rename highlight")
            .setView(et)
            .setPositiveButton("Save", (d, w) -> {
                String newName = et.getText().toString().trim();
                if (newName.isEmpty()) return;
                // Update every item in the album on Firebase
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("statusHighlights")
                    .child(targetUid)
                    .child(album.albumId)
                    .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                            for (com.google.firebase.database.DataSnapshot child : snap.getChildren())
                                child.getRef().child("highlightAlbumName").setValue(newName);
                        }
                        @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                    });
                album.albumName = newName;
                if (highlightsAdapter != null) highlightsAdapter.notifyItemChanged(adapterPos);
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    private void confirmDeleteHighlight(HighlightsRowAdapter.HighlightAlbum album, int adapterPos) {
        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete \"" + album.albumName + "\"?")
            .setMessage("This will permanently remove this highlight album.")
            .setPositiveButton("Delete", (d, w) -> {
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("statusHighlights")
                    .child(targetUid)
                    .child(album.albumId)
                    .removeValue();
                highlightAlbums.remove(album);
                rebuildHighlightsAdapter();
                if (highlightAlbums.isEmpty() && !isSelf) {
                    if (hsvHighlights    != null) hsvHighlights.setVisibility(android.view.View.GONE);
                    if (dividerHighlights != null) dividerHighlights.setVisibility(android.view.View.GONE);
                }
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    /** "album_id" → "Album Id" */
    private static String toDisplayName(String id) {
        if (id == null) return "";
        String[] parts = id.replace("_", " ").replace("-", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private void loadVerifiedStatus() {
        FirebaseUtils.getUserRef(targetUid).child("isVerified")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (ivVerified != null)
                        ivVerified.setVisibility(
                            Boolean.TRUE.equals(snap.getValue(Boolean.class))
                                ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Mutual Followers (Feature 10) ─────────────────────────────────────
    //
    // FIX: Instagram-style mutual friends detection using BOTH reel followers
    // AND main-app contacts. Previously only checked reelFollowers/{uid} which
    // is empty unless users explicitly followed each other in the reels section.
    // Now: collect from reelFollowers (reel-system) PLUS reelFollows (people
    // target follows who also follow me) for a complete picture. This matches
    // how Instagram finds mutual connections across their full social graph.

    private void loadMutualFollowers() {
        String myUid = safeMyUid();
        if (myUid == null || myUid.isEmpty() || isSelf) return;

        // Step 1: Get MY reel followers (people who follow me in reel system)
        FirebaseUtils.getReelFollowersRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot myFollowersSnap) {
                    final Set<String> myNetwork = new HashSet<>();
                    for (DataSnapshot s : myFollowersSnap.getChildren()) {
                        if (s.getKey() != null) myNetwork.add(s.getKey());
                    }

                    // Step 2: ALSO get people I follow (reelFollows) —
                    // adds to "my network" so we find more mutual connections
                    FirebaseUtils.getReelFollowsRef(myUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot myFollowsSnap) {
                                for (DataSnapshot s : myFollowsSnap.getChildren()) {
                                    if (s.getKey() != null) myNetwork.add(s.getKey());
                                }

                                // Step 3: Get TARGET's reel followers
                                FirebaseUtils.getReelFollowersRef(targetUid)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot tSnap) {
                                            mutualUidsList.clear();
                                            // Intersection: target's followers who are in my network
                                            for (DataSnapshot s : tSnap.getChildren()) {
                                                if (s.getKey() != null && myNetwork.contains(s.getKey())
                                                        && !s.getKey().equals(myUid)) {
                                                    mutualUidsList.add(s.getKey());
                                                }
                                            }

                                            // Step 4: ALSO check people target follows who are in my network
                                            FirebaseUtils.getReelFollowsRef(targetUid)
                                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                                    @Override public void onDataChange(@NonNull DataSnapshot tFollowsSnap) {
                                                        for (DataSnapshot s : tFollowsSnap.getChildren()) {
                                                            if (s.getKey() != null
                                                                    && myNetwork.contains(s.getKey())
                                                                    && !s.getKey().equals(myUid)
                                                                    && !mutualUidsList.contains(s.getKey())) {
                                                                mutualUidsList.add(s.getKey());
                                                            }
                                                        }
                                                        // Now fetch profiles for display
                                                        fetchMutualProfiles();
                                                    }
                                                    @Override public void onCancelled(@NonNull DatabaseError e) {
                                                        fetchMutualProfiles(); // show what we have
                                                    }
                                                });
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {
                                            fetchMutualProfiles();
                                        }
                                    });
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {
                                // myNetwork only has followers — still try target lookup
                                FirebaseUtils.getReelFollowersRef(targetUid)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot tSnap) {
                                            mutualUidsList.clear();
                                            for (DataSnapshot s : tSnap.getChildren())
                                                if (s.getKey() != null && myNetwork.contains(s.getKey())
                                                        && !s.getKey().equals(myUid))
                                                    mutualUidsList.add(s.getKey());
                                            fetchMutualProfiles();
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e2) {
                                            fetchMutualProfiles();
                                        }
                                    });
                            }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /** Fetches name+photo for the first 3 mutual UIDs and calls showMutualFollowers. */
    private void fetchMutualProfiles() {
        if (mutualUidsList.isEmpty()) {
            showMutualFollowers(new ArrayList<>(), new ArrayList<>());
            return;
        }
        int fetchCount = Math.min(3, mutualUidsList.size());
        List<String> names  = new ArrayList<>();
        List<String> photos = new ArrayList<>();
        final int[] done = {0};
        for (int i = 0; i < fetchCount; i++) {
            String uid = mutualUidsList.get(i);
            FirebaseUtils.getUserRef(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot us) {
                        String n     = us.child("name").getValue(String.class);
                        String thumb = us.child("thumbUrl").getValue(String.class);
                        String photo = us.child("photoUrl").getValue(String.class);
                        String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        names.add(n != null ? n : "User");
                        photos.add(p != null ? p : "");
                        done[0]++;
                        if (done[0] >= fetchCount) showMutualFollowers(names, photos);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        names.add("User"); photos.add("");
                        done[0]++;
                        if (done[0] >= fetchCount) showMutualFollowers(names, photos);
                    }
                });
        }
    }

    private void showMutualFollowers(List<String> names, List<String> photos) {
        if (layoutMutualFollowers == null || isFinishing() || isDestroyed()) return;
        int count = mutualUidsList.size();
        if (count <= 0) {
            layoutMutualFollowers.setVisibility(View.GONE);
            return;
        }

        // ── Load avatars (up to 3, overlapping: avatar1=front, avatar3=back) ──
        CircleImageView[] ivs = {ivMutual1, ivMutual2, ivMutual3};
        for (int i = 0; i < 3; i++) {
            if (ivs[i] == null) continue;
            if (i < photos.size() && !photos.get(i).isEmpty()) {
                ivs[i].setVisibility(View.VISIBLE);
                Glide.with(this).load(photos.get(i))
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .circleCrop()
                    .override(240, 240)
                    .into(ivs[i]);
            } else if (i < names.size()) {
                ivs[i].setVisibility(View.VISIBLE);
                ivs[i].setImageResource(R.drawable.ic_person);
            } else {
                ivs[i].setVisibility(View.GONE);
            }
        }

        // ── Build text: "Followed by name1, name2 and X others" ──
        String text;
        if (count == 1) {
            text = "Followed by " + names.get(0);
        } else if (count == 2) {
            text = "Followed by " + names.get(0) + " and " + names.get(1);
        } else {
            int others = count - 2;
            text = "Followed by " + names.get(0) + ", " + names.get(1)
                + " and " + others + (others == 1 ? " other" : " others");
        }

        if (tvMutualFollowers != null) tvMutualFollowers.setText(text);
        layoutMutualFollowers.setVisibility(View.VISIBLE);
        layoutMutualFollowers.setOnClickListener(v -> openMutualFollowers());
    }

    private void openMutualFollowers() {
        Intent i = new Intent(this, com.callx.app.followers.FollowConnectionsActivity.class);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_UID,       targetUid);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_NAME,      targetName != null ? targetName : "");
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_IS_SELF,   isSelf);
        i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_START_TAB, com.callx.app.followers.FollowConnectionsActivity.TAB_MUTUAL);
        if (!mutualUidsList.isEmpty())
            i.putStringArrayListExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_MUTUAL_UIDS,
                    new ArrayList<>(mutualUidsList));
        startActivity(i);
    }

    // ── Pinned Reel (Feature 6) ───────────────────────────────────────────

    private void loadPinnedReel() {
        FirebaseDatabase.getInstance().getReference("reelPinned").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String pinnedId = snap.getValue(String.class);
                    if (pinnedId == null || pinnedId.isEmpty()) return;
                    FirebaseUtils.getReelsRef().child(pinnedId)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot s) {
                                ReelModel r = s.getValue(ReelModel.class);
                                if (r != null && activeTab == TAB_REELS) {
                                    pinnedReel = r;
                                    adapter.setPinnedReel(r);
                                }
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void pinReel(String reelId) {
        FirebaseDatabase.getInstance().getReference("reelPinned")
            .child(targetUid).setValue(reelId)
            .addOnSuccessListener(v -> {
                Toast.makeText(this, "Reel pinned!", Toast.LENGTH_SHORT).show();
                loadPinnedReel();
            });
    }

    private void unpinReel() {
        FirebaseDatabase.getInstance().getReference("reelPinned")
            .child(targetUid).removeValue()
            .addOnSuccessListener(v -> {
                pinnedReel = null;
                adapter.setPinnedReel(null);
                Toast.makeText(this, "Pinned reel removed", Toast.LENGTH_SHORT).show();
            });
    }

    // ── Data loading ──────────────────────────────────────────────────────

    private void loadCurrentTab(boolean refresh) {
        switch (activeTab) {
            case TAB_LIKED:  loadLikedReels(refresh);    break;
            case TAB_SAVED:  loadSavedReels(refresh);    break;
            case TAB_REPOST: loadRepostedReels(refresh); break;
            case TAB_DUET:   loadDuetReels(refresh);     break;
            case TAB_COLLAB_REPOST: loadCollabRepostReels(refresh); break;
            case TAB_SERIES: loadSeriesTab(refresh);     break;
            default:         loadUserReels(refresh);     break;
        }
    }

    private void loadUserReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { reelsLastKey = null; reelsHasMore = true; reelsTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getReelsByUserRef(targetUid), reelsLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) reelsHasMore = false;
                if (snap.getChildrenCount() == 0) { onPageLoadSucceeded(TAB_REELS); finishLoading(refresh, TAB_REELS); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) reelsLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, reelsTabData, refresh, TAB_REELS);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onPageLoadFailed(TAB_REELS, refresh); }
        });
    }

    private void loadLikedReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { likedLastKey = null; likedHasMore = true; likedTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getReelLikedByUserRef(targetUid), likedLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) likedHasMore = false;
                if (snap.getChildrenCount() == 0) { onPageLoadSucceeded(TAB_LIKED); finishLoading(refresh, TAB_LIKED); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) likedLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, likedTabData, refresh, TAB_LIKED);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onPageLoadFailed(TAB_LIKED, refresh); }
        });
    }

    private void loadSavedReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { savedLastKey = null; savedHasMore = true; savedTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getReelSavesRef(targetUid), savedLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) savedHasMore = false;
                if (snap.getChildrenCount() == 0) { onPageLoadSucceeded(TAB_SAVED); finishLoading(refresh, TAB_SAVED); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) savedLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, savedTabData, refresh, TAB_SAVED);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onPageLoadFailed(TAB_SAVED, refresh); }
        });
    }

    
      // ── Duet Series tab ────────────────────────────────────────────────────
      private void loadSeriesTab(boolean refresh) {
          if (seriesLoaded && !refresh) return;
          if (rvSeries == null) return;
          seriesLoaded = false;
          seriesTabData.clear();
          seriesAdapter.setItems(seriesTabData);

          if (progressBar != null) progressBar.setVisibility(View.VISIBLE);

          com.google.firebase.database.FirebaseDatabase.getInstance(Constants.DB_URL)
              .getReference("userDuetSeries")
              .child(targetUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override
                  public void onDataChange(@NonNull DataSnapshot titlesSnap) {
                      if (isFinishing() || isDestroyed()) return;
                      if (!titlesSnap.exists() || titlesSnap.getChildrenCount() == 0) {
                          if (progressBar != null) progressBar.setVisibility(View.GONE);
                          if (layoutEmpty != null) {
                              tvEmptyTitle.setText("No Series Yet");
                              tvEmptySubtitle.setText("This creator hasn't started a Duet Series");
                              showEmptyLayout(true);
                          }
                          seriesLoaded = true;
                          return;
                      }
                      if (layoutEmpty != null) showEmptyLayout(false);

                      // Fetch each seriesId's full DuetSeriesModel
                      java.util.List<DuetSeriesModel> fetched = new java.util.ArrayList<>();
                      long[] remaining = {titlesSnap.getChildrenCount()};

                      for (DataSnapshot s : titlesSnap.getChildren()) {
                          String seriesId = s.getKey();
                          if (seriesId == null) { remaining[0]--; continue; }
                          com.google.firebase.database.FirebaseDatabase.getInstance(Constants.DB_URL)
                              .getReference("duetSeries").child(seriesId)
                              .addListenerForSingleValueEvent(new ValueEventListener() {
                                  @Override
                                  public void onDataChange(@NonNull DataSnapshot seriesSnap) {
                                      if (isFinishing() || isDestroyed()) return;
                                      DuetSeriesModel m = seriesSnap.getValue(DuetSeriesModel.class);
                                      if (m != null) fetched.add(m);
                                      remaining[0]--;
                                      if (remaining[0] <= 0) {
                                          // Sort by newest first
                                          fetched.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
                                          seriesTabData.addAll(fetched);
                                          seriesAdapter.setItems(seriesTabData);
                                          if (progressBar != null) progressBar.setVisibility(View.GONE);
                                          if (layoutEmpty != null)
                                              showEmptyLayout(fetched.isEmpty());
                                          seriesLoaded = true;
                                      }
                                  }
                                  @Override
                                  public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                                      remaining[0]--;
                                  }
                              });
                      }
                  }
                  @Override
                  public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                      if (progressBar != null) progressBar.setVisibility(View.GONE);
                  }
              });
      }

  private void loadRepostedReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { repostsLastKey = null; repostsHasMore = true; repostsTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getReelRepostsByUserRef(targetUid), repostsLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) repostsHasMore = false;
                if (snap.getChildrenCount() == 0) { onPageLoadSucceeded(TAB_REPOST); finishLoading(refresh, TAB_REPOST); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) repostsLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, repostsTabData, refresh, TAB_REPOST);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onPageLoadFailed(TAB_REPOST, refresh); }
        });
    }

    /** Duet tab — reels this user has duetted, indexed at userDuetReels/{uid} (see FirebaseUtils). */
    private void loadDuetReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { duetLastKey = null; duetHasMore = true; duetTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getUserDuetReelsRef(targetUid), duetLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) duetHasMore = false;
                if (snap.getChildrenCount() == 0) { onPageLoadSucceeded(TAB_DUET); finishLoading(refresh, TAB_DUET); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) duetLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, duetTabData, refresh, TAB_DUET);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onPageLoadFailed(TAB_DUET, refresh); }
        });
    }

    /** Collab Repost tab — reels published via an accepted Collab Repost invite, indexed at
     *  userCollabRepostReels/{uid} (see FirebaseUtils). */
    private void loadCollabRepostReels(boolean refresh) {
        if (isLoadingMore && !refresh) return;
        isLoadingMore = true;
        if (refresh) { collabRepostLastKey = null; collabRepostHasMore = true; collabRepostTabData.clear(); showSkeleton(); }
        Query q = buildQuery(FirebaseUtils.getUserCollabRepostReelsRef(targetUid), collabRepostLastKey);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed()) return;
                if (snap.getChildrenCount() < PAGE_SIZE) collabRepostHasMore = false;
                if (snap.getChildrenCount() == 0) { onPageLoadSucceeded(TAB_COLLAB_REPOST); finishLoading(refresh, TAB_COLLAB_REPOST); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) collabRepostLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, collabRepostTabData, refresh, TAB_COLLAB_REPOST);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { onPageLoadFailed(TAB_COLLAB_REPOST, refresh); }
        });
    }

    private Query buildQuery(DatabaseReference ref, String lastKey) {
        return lastKey == null
            ? ref.orderByKey().limitToLast(PAGE_SIZE)
            : ref.orderByKey().endBefore(lastKey).limitToLast(PAGE_SIZE);
    }

    private List<String> extractIds(DataSnapshot snap) {
        List<String> ids = new ArrayList<>();
        for (DataSnapshot s : snap.getChildren()) ids.add(s.getKey());
        Collections.reverse(ids);
        return ids;
    }

    private void showSkeleton() {
        // Defensive: a pagination footer left set from a prior in-flight
        // page load must never survive into skeleton mode's own item count
        // math (getItemCount() ignores the footer flag while skeletonMode is
        // on, but the flag would otherwise still be stale/true once skeleton
        // mode turns back off in finishLoading()).
        if (adapter.isLoadingFooterVisible()) adapter.resetLoadingFooterState();
        footerPositionAtShow = -1;
        adapter.setSkeletonMode(true);
        adapter.notifyDataSetChanged();
        showEmptyLayout(false);
    }

    private void fetchAndAppend(List<String> ids, List<ReelModel> target,
                                boolean refresh, int tab) {
        onPageLoadSucceeded(tab); // a page came back fine — clear any pending retry backoff for this tab
        final int[] remaining = {ids.size()};
        final List<ReelModel> fetched = new ArrayList<>();
        // Remember where the new items will land so we can notify just that
        // range instead of the whole grid (avoids re-binding/re-loading
        // already-visible thumbnails on every "load more" during scroll —
        // this was the visual "refresh" while scrolling).
        final int insertStart = target.size();
        for (String id : ids) {
            FirebaseUtils.getReelsRef().child(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!isFinishing() && !isDestroyed()) {
                            ReelModel r = null;
                            try {
                                r = snap.getValue(ReelModel.class);
                            } catch (Exception ex) {
                                // A single record with a field-type mismatch (e.g. an old/
                                // new schema clash) used to throw here and silently stall
                                // this whole batch forever, since `remaining[0]` never got
                                // decremented for it — the tab looked permanently empty even
                                // though the userReposts/userDuetReels/etc. index entry was
                                // correct. Skip just this one record instead.
                            }
                            if (r != null) fetched.add(r);
                        }
                        if (--remaining[0] == 0) {
                            fetched.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                            // De-dupe against whatever is already in `target`. This matters
                            // because loadReelGridFromRoom() warm-starts the Reels tab from
                            // the Room cache on a background thread while this Firebase
                            // fetch is in flight — if Room's callback lands first (list was
                            // empty right after the synchronous reelsTabData.clear() in
                            // loadUserReels(), before Firebase responded), it fills `target`
                            // with the same page we're about to fetch. Blindly appending here
                            // used to double every reel in the grid. Same guard also protects
                            // pagination against a repeated key landing in two pages.
                            Set<String> existingIds = new HashSet<>();
                            for (ReelModel r : target)
                                if (r != null && r.reelId != null) existingIds.add(r.reelId);
                            int added = 0;
                            for (ReelModel r : fetched) {
                                if (r == null) continue;
                                if (r.reelId != null && !existingIds.add(r.reelId)) continue; // already present
                                target.add(r);
                                added++;
                            }
                            finishLoading(refresh, tab, insertStart, added);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (--remaining[0] == 0) finishLoading(refresh, tab);
                    }
                });
        }
    }

    private void setupViewAllReelsButton() {
          if (btnViewAllReels == null) return;
          btnViewAllReels.setOnClickListener(v -> {
              Intent i = new Intent(this, AllReelsFullActivity.class);
              i.putExtra(AllReelsFullActivity.EXTRA_UID,   targetUid);
              i.putExtra(AllReelsFullActivity.EXTRA_NAME,  targetName  != null ? targetName  : "");
              i.putExtra(AllReelsFullActivity.EXTRA_PHOTO, targetPhoto != null ? targetPhoto : "");
              i.putExtra(AllReelsFullActivity.EXTRA_TAB,   activeTab);
              startActivity(i);
          });
          // Starts hidden (alpha=0, GONE per XML) — reveal is entirely
          // scroll-driven, see the OnScrollListener below.
          viewAllButtonShown = false;
          if (rvReels != null) {
              rvReels.addOnScrollListener(new RecyclerView.OnScrollListener() {
                  @Override
                  public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                      if (!viewAllButtonEligible) return;
                      // Scrolled away from the very top → reveal; back at the
                      // top → hide. canScrollVertically(-1) is cheap (no
                      // allocation) and re-evaluated on every scroll delta,
                      // so this stays in sync during fast flings too.
                      boolean scrolledDown = recyclerView.canScrollVertically(-1);
                      if (scrolledDown) showViewAllButtonAnimated();
                      else              hideViewAllButtonAnimated();
                  }
              });
          }
          updateViewAllButton();
      }

      private void updateViewAllButton() {
          if (btnViewAllReels == null) return;
          List<ReelModel> data = activeTabData();
          // Eligible to show "View All" if we loaded a full page (likely
          // more exist) or if any data exists — actual visibility is then
          // driven by scroll position (see showViewAllButtonAnimated /
          // hideViewAllButtonAnimated), never a hard on/off here.
          viewAllButtonEligible = !data.isEmpty();
          if (!viewAllButtonEligible) {
              hideViewAllButtonAnimated();
              return;
          }
          // Data/tab may have just changed under an already-scrolled grid
          // (e.g. tab switch) — sync immediately to the current scroll
          // position instead of waiting for the next scroll event.
          boolean scrolledDown = rvReels != null && rvReels.canScrollVertically(-1);
          if (scrolledDown) showViewAllButtonAnimated();
          else               hideViewAllButtonAnimated();
      }

      /** Smoothly fades + slides the pill up into view. No-op if already shown. */
      private void showViewAllButtonAnimated() {
          if (btnViewAllReels == null || viewAllButtonShown) return;
          viewAllButtonShown = true;
          btnViewAllReels.animate().cancel();
          if (btnViewAllReels.getVisibility() != View.VISIBLE) {
              btnViewAllReels.setAlpha(0f);
              btnViewAllReels.setScaleX(0.9f);
              btnViewAllReels.setScaleY(0.9f);
              btnViewAllReels.setTranslationY(24f);
              btnViewAllReels.setVisibility(View.VISIBLE);
          }
          btnViewAllReels.animate()
                  .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                  .setDuration(220)
                  .setInterpolator(new android.view.animation.DecelerateInterpolator())
                  .start();
      }

      /** Smoothly fades + slides the pill back down out of view. No-op if already hidden. */
      private void hideViewAllButtonAnimated() {
          if (btnViewAllReels == null || !viewAllButtonShown) return;
          viewAllButtonShown = false;
          btnViewAllReels.animate().cancel();
          btnViewAllReels.animate()
                  .alpha(0f).scaleX(0.9f).scaleY(0.9f).translationY(24f)
                  .setDuration(160)
                  .setInterpolator(new android.view.animation.AccelerateInterpolator())
                  .withEndAction(() -> {
                      if (!viewAllButtonShown) btnViewAllReels.setVisibility(View.GONE);
                  })
                  .start();
      }

      private List<ReelModel> dataForTab(int tab) {
        switch (tab) {
            case TAB_LIKED:  return likedTabData;
            case TAB_SAVED:  return savedTabData;
            case TAB_REPOST: return repostsTabData;
            case TAB_DUET:   return duetTabData;
            case TAB_COLLAB_REPOST: return collabRepostTabData;
            default:         return reelsTabData;
        }
    }

    /** Firebase index ref backing each tab — same refs loadCurrentTab() already reads from. */
    private DatabaseReference refForTab(int tab) {
        switch (tab) {
            case TAB_LIKED:  return FirebaseUtils.getReelLikedByUserRef(targetUid);
            case TAB_SAVED:  return FirebaseUtils.getReelSavesRef(targetUid);
            case TAB_REPOST: return FirebaseUtils.getReelRepostsByUserRef(targetUid);
            case TAB_DUET:   return FirebaseUtils.getUserDuetReelsRef(targetUid);
            case TAB_COLLAB_REPOST: return FirebaseUtils.getUserCollabRepostReelsRef(targetUid);
            default:         return FirebaseUtils.getReelsByUserRef(targetUid);
        }
    }

    /**
     * Pull-to-refresh (swipe_refresh_reels).
     *
     * ULTRA-OPTIMIZED refresh path — deliberately NOT a call to loadCurrentTab(true):
     * that path clears the whole tab list, shows the shimmer skeleton, re-fetches
     * everything already-loaded, and finishes with a full notifyDataSetChanged()
     * (every visible cell re-bound, every thumbnail re-decoded). Fine for a first
     * load / tab switch, way too heavy for "user flicked the list down and wants
     * to see new stuff in under a second":
     *
     *  - Network:  fetch ONLY the newest PAGE_SIZE ids (orderByKey().limitToLast),
     *              never the whole loaded history — smallest possible payload.
     *  - Memory:   merge into the SAME List instance already backing the adapter
     *              (in place update/prepend) — no allocation of a fresh tab list,
     *              no touching the older pages already scrolled past.
     *  - Render:   ReelGridAdapter#diffDataSetChanged() (DiffUtil) dispatches only
     *              the actual inserted/changed rows — untouched cells are never
     *              rebound, so Glide never re-decodes thumbnails that didn't change.
     *  - Network debounce: pullRefreshInFlight guards against a second yank
     *              stacking another round-trip on top of one still in flight.
     *  - Pagination cursor (reelsLastKey/likedLastKey/...) is left untouched, so
     *    "load more" at the bottom keeps working from exactly where it was.
     */
    private void setupPullToRefresh() {
        if (swipeRefreshReels == null) return;
        swipeRefreshReels.setColorSchemeResources(
                R.color.brand_primary,
                R.color.brand_primary_dark);
        swipeRefreshReels.setOnRefreshListener(this::onPullToRefresh);
    }

    private void onPullToRefresh() {
        if (swipeRefreshReels == null) return;
        // Header (avatar/name/bio/links/followers/following/song) only ever
        // loaded once from onCreate() → loadUserProfile(). Pull-to-refresh
        // was only merging the grid below, so the top area never updated.
        // Fire it in parallel with the grid refresh below — it's its own
        // set of single-value listeners, so it doesn't block/slow the
        // grid's ultra-optimized merge path.
        loadUserProfile();
        if (activeTab == TAB_SERIES) {
            // Series lists are tiny (a handful of albums) — the normal
            // refresh path is already cheap enough here, no need for a
            // second bespoke code path just for this tab.
            loadCurrentTab(true);
            swipeRefreshReels.postDelayed(() -> {
                if (swipeRefreshReels != null) swipeRefreshReels.setRefreshing(false);
            }, 500);
            return;
        }
        if (pullRefreshInFlight) return;
        pullRefreshInFlight = true;
        final int tabAtStart = activeTab;
        refForTab(tabAtStart).orderByKey().limitToLast(PAGE_SIZE)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) { finishPullRefresh(); return; }
                    List<String> ids = extractIds(snap);
                    if (ids.isEmpty()) { finishPullRefresh(); return; }
                    mergeFreshIds(ids, tabAtStart);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) { finishPullRefresh(); }
            });
    }

    /** Parallel single-value fetch of just the freshest page's ReelModels (mirrors fetchAndAppend's fan-out). */
    private void mergeFreshIds(List<String> ids, int tabAtStart) {
        final int[] remaining = {ids.size()};
        final Map<String, ReelModel> fetchedById = new HashMap<>();
        for (String id : ids) {
            FirebaseUtils.getReelsRef().child(id)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!isFinishing() && !isDestroyed()) {
                            try {
                                ReelModel r = snap.getValue(ReelModel.class);
                                if (r != null && r.reelId != null) fetchedById.put(r.reelId, r);
                            } catch (Exception ignored) {
                                // Same defensive skip as fetchAndAppend — one bad record
                                // must not stall the whole refresh.
                            }
                        }
                        if (--remaining[0] == 0) applyPullRefreshMerge(fetchedById, tabAtStart);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        if (--remaining[0] == 0) applyPullRefreshMerge(fetchedById, tabAtStart);
                    }
                });
        }
    }

    private void applyPullRefreshMerge(Map<String, ReelModel> fetchedById, int tabAtStart) {
        if (isFinishing() || isDestroyed()) { finishPullRefresh(); return; }
        List<ReelModel> target = dataForTab(tabAtStart);
        // Snapshot BEFORE mutating target in place — diffDataSetChanged() needs
        // the pre-mutation shape to compute a real diff against post-mutation.
        List<ReelModel> oldSnapshot = new ArrayList<>(target);
        for (ReelModel fresh : fetchedById.values()) {
            int idx = -1;
            for (int i = 0; i < target.size(); i++) {
                ReelModel existing = target.get(i);
                if (existing != null && fresh.reelId.equals(existing.reelId)) { idx = i; break; }
            }
            if (idx >= 0) target.set(idx, fresh); // refreshed stats/caption/thumb, same slot
            else          target.add(0, fresh);   // brand-new since last load → newest, goes on top
        }
        target.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
        if (tabAtStart == activeTab && adapter != null) {
            if (isPostsTabActive()) {
                // Same reasoning as finishLoading(): Posts' displayList is a
                // filtered copy, not `target` itself — re-derive through the
                // filter path instead of diffing the wrong list.
                applyFilter();
            } else if (activeFilter == FILTER_ALL) {
                // displayList == target here (same reference, re-pointed on
                // every tab switch — see setupTabs()), so a direct diff against
                // it is safe and gives the minimal-rebind path described above.
                adapter.diffDataSetChanged(oldSnapshot);
            } else {
                // A filter chip (Oldest/Newest/Most-viewed) is active, so the
                // adapter's displayList is a separately-sorted copy, not
                // `target` itself — re-derive it through the normal filter
                // path instead of diffing the wrong list.
                applyFilter();
            }
            refreshEmptyState();
            updateViewAllButton();
        }
        if (!target.isEmpty()) cacheGridPage(tabAtStart, target);
        finishPullRefresh();
    }

    private void finishPullRefresh() {
        pullRefreshInFlight = false;
        if (swipeRefreshReels != null) swipeRefreshReels.setRefreshing(false);
    }

    private void finishLoading(boolean refresh, int tab) {
        finishLoading(refresh, tab, -1, 0);
    }

    /**
     * SCROLL REFRESH FIX: previously this always called adapter.notifyDataSetChanged(),
     * which re-binds every visible cell (including already-loaded thumbnails) any time
     * loadCurrentTab(false) ran during pagination — this is what looked like the grid
     * "refreshing" every time the user scrolled to the bottom.
     *
     * Now: a real refresh (pull-to-refresh / tab switch, skeleton -> data swap) still does
     * a full notifyDataSetChanged, but pagination only notifies the newly appended range,
     * so existing cells are left untouched.
     */
    private void finishLoading(boolean refresh, int tab, int insertStart, int insertCount) {
        if (isFinishing() || isDestroyed()) return;
        isLoadingMore = false;
        boolean wasSkeleton = adapter.isSkeletonMode();
        adapter.setSkeletonMode(false);
        if (tab == activeTab) {
            if (isPostsTabActive()) {
                // Posts tab's displayed list is a filtered COPY of
                // reelsTabData (not the same reference), so the raw
                // insertStart/insertCount index math above (computed
                // against reelsTabData's own size) does not line up with
                // the adapter's smaller photo-only list — always re-derive
                // + diff the filtered subset instead of a positional insert.
                applyFilter();
                if (adapter.isLoadingFooterVisible()) {
                    adapter.hideLoadingFooterAt(footerPositionAtShow);
                    footerPositionAtShow = -1;
                }
            } else if (refresh) {
                if (wasSkeleton) {
                    // Skeleton → real content: crossfade instead of the old
                    // instant notifyDataSetChanged() swap.
                    crossfadeSkeletonToContent(() -> adapter.notifyDataSetChanged());
                } else {
                    adapter.notifyDataSetChanged();
                }
            } else if (insertCount > 0) {
                if (adapter.isLoadingFooterVisible()) {
                    adapter.hideLoadingFooterAt(footerPositionAtShow);
                    footerPositionAtShow = -1;
                }
                adapter.notifyItemRangeInserted(insertStart, insertCount);
            } else if (adapter.isLoadingFooterVisible()) {
                // No new rows landed (page came back empty / hasMore flipped
                // false) — still need to take the spinner row back down.
                adapter.hideLoadingFooterAt(footerPositionAtShow);
                footerPositionAtShow = -1;
            }
        }
        if (progressBar  != null) progressBar.setVisibility(View.GONE);
        if (tab == activeTab) { refreshEmptyState(); updateViewAllButton(); }
        List<ReelModel> tabData = dataForTab(tab);
        if (!tabData.isEmpty()) cacheGridPage(tab, tabData);
    }

    /**
     * Crossfades the shimmer skeleton grid into the real loaded content
     * instead of the old abrupt swap: snapshots rv_reels exactly as it looks
     * right now (still showing skeleton cells), runs {@code swapDataAndNotify}
     * to bind the real data underneath, then overlays the snapshot on top and
     * fades it out — so the shimmer visibly dissolves into the thumbnails
     * instead of popping. Falls back to an instant swap if anything about the
     * snapshot/overlay can't be done safely (view not laid out yet, etc.).
     */
    private void crossfadeSkeletonToContent(Runnable swapDataAndNotify) {
        if (rvReels == null || isFinishing() || isDestroyed()
                || rvReels.getWidth() <= 0 || rvReels.getHeight() <= 0) {
            swapDataAndNotify.run();
            return;
        }
        Bitmap snapshot;
        try {
            snapshot = Bitmap.createBitmap(rvReels.getWidth(), rvReels.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(snapshot);
            rvReels.draw(c);
        } catch (Throwable t) {
            swapDataAndNotify.run();
            return;
        }

        swapDataAndNotify.run(); // real thumbnails are bound underneath now

        try {
            ViewGroup decor = (ViewGroup) getWindow().getDecorView();
            ImageView overlay = new ImageView(this);
            overlay.setImageBitmap(snapshot);
            overlay.setScaleType(ImageView.ScaleType.FIT_XY);

            int[] loc = new int[2];
            rvReels.getLocationInWindow(loc);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(rvReels.getWidth(), rvReels.getHeight());
            lp.leftMargin = loc[0];
            lp.topMargin  = loc[1];
            decor.addView(overlay, lp);

            overlay.animate()
                    .alpha(0f)
                    .setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(() -> {
                        ViewGroup p = (ViewGroup) overlay.getParent();
                        if (p != null) p.removeView(overlay);
                    })
                    .start();
        } catch (Throwable ignored) {
            // Data is already swapped correctly — worst case is no crossfade.
        }
    }

    /**
     * One-time wiring for the illustrated empty-state animation. If the
     * Lottie asset ever fails to load/parse on a device, we fall back to the
     * plain static icon that used to be the only option — the empty state
     * never ends up showing nothing.
     */
    private void setupEmptyStateLottie() {
        if (lottieEmpty == null) return;
        lottieEmpty.setFailureListener(t -> {
            emptyLottieFailed = true;
            lottieEmpty.setVisibility(View.GONE);
            if (ivEmptyIcon != null) ivEmptyIcon.setVisibility(View.VISIBLE);
        });
    }

    /**
     * Single source of truth for showing/hiding the empty-state block —
     * used by refreshEmptyState() (Reels/Liked/Saved/Repost/Duet/Collab tabs)
     * and loadSeriesTab()/showSkeleton() so the Lottie illustration and its
     * static fallback stay correctly in sync everywhere layoutEmpty toggles.
     */
    private void showEmptyLayout(boolean show) {
        if (layoutEmpty != null) layoutEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            if (lottieEmpty != null) lottieEmpty.pauseAnimation();
            return;
        }
        if (!emptyLottieFailed && lottieEmpty != null) {
            lottieEmpty.setVisibility(View.VISIBLE);
            if (ivEmptyIcon != null) ivEmptyIcon.setVisibility(View.GONE);
            if (!lottieEmpty.isAnimating()) lottieEmpty.playAnimation();
        } else if (ivEmptyIcon != null) {
            ivEmptyIcon.setVisibility(View.VISIBLE);
        }
    }

    private void refreshEmptyState() {
        if (activeTab == TAB_SERIES) return; // series tab manages own empty state
        boolean isPosts = isPostsTabActive();
        boolean empty = (isPosts ? filterPhotoPostsOnly(activeTabData()) : activeTabData()).isEmpty()
                && !adapter.hasPinned();
        showEmptyLayout(empty);
        if (rvReels != null) rvReels.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tvEmptyTitle == null) return;
        if (isPosts) {
            tvEmptyTitle.setText("No Posts Yet");
            if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Photo posts will appear here.");
            return;
        }
        switch (activeTab) {
            case TAB_LIKED:
                tvEmptyTitle.setText("No Liked Reels");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Liked reels will appear here."); break;
            case TAB_SAVED:
                tvEmptyTitle.setText("No Saved Reels");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Saved reels will appear here."); break;
            case TAB_REPOST:
                tvEmptyTitle.setText("No Reposts");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Reposted reels will appear here."); break;
            case TAB_DUET:
                tvEmptyTitle.setText("No Duets");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Duets this creator posts will appear here."); break;
            case TAB_COLLAB_REPOST:
                tvEmptyTitle.setText("No Collab Reposts");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("Collab reposts will appear here."); break;
            default:
                tvEmptyTitle.setText("No Reels Yet");
                if (tvEmptySubtitle != null) tvEmptySubtitle.setText("This creator hasn't posted any reels yet.");
        }
    }

    // ── Grid quick-like (double-tap) ─────────────────────────────────────

    /**
     * Instagram-style double-tap-to-like from the grid: never unlikes —
     * a repeat double-tap on an already-liked reel just re-plays the heart
     * burst (handled purely in ReelGridAdapter) with no extra write here.
     * Mirrors the Firebase write pattern in ReelSocialController#toggleLike()
     * so like counts/notifications stay consistent with the player screen.
     */
    private void likeReelFromGrid(int adapterPosition) {
        if (adapter == null) return;
        final ReelModel targetReel;
        if (adapter.hasPinned() && adapterPosition == 0) {
            targetReel = pinnedReel;
        } else {
            int dataIdx = adapter.hasPinned() ? adapterPosition - 1 : adapterPosition;
            List<ReelModel> data = currentGridData();
            if (dataIdx < 0 || dataIdx >= data.size()) return;
            targetReel = data.get(dataIdx);
        }
        if (targetReel == null || targetReel.reelId == null) return;

        final String myUid = safeMyUid();
        if (myUid == null) return;
        final int finalAdapterPosition = adapterPosition;

        DatabaseReference likeRef = FirebaseUtils.getReelLikesRef(targetReel.reelId).child(myUid);
        likeRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (isFinishing() || isDestroyed() || snap.exists()) return; // already liked — heart burst is enough

                DatabaseReference countRef = FirebaseUtils.getReelsRef().child(targetReel.reelId).child("likesCount");
                DatabaseReference likedByUserRef = FirebaseUtils.getReelLikedByUserRef(myUid).child(targetReel.reelId);
                likeRef.setValue(System.currentTimeMillis()); // FIX: timestamp value for liker-avatar-row recency ordering
                likedByUserRef.setValue(System.currentTimeMillis());
                countRef.runTransaction(new Transaction.Handler() {
                    @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                        Integer c = d.getValue(Integer.class);
                        d.setValue(c != null ? c + 1 : 1);
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(@Nullable DatabaseError e, boolean committed, @Nullable DataSnapshot s) {}
                });

                targetReel.likesCount = targetReel.likesCount + 1;
                if (adapter != null) adapter.notifyItemChanged(finalAdapterPosition);

                if (targetReel.uid != null && !targetReel.uid.equals(myUid)) {
                    String myName = FirebaseUtils.getCurrentName();
                    com.callx.app.utils.PushNotify.notifyReelLike(
                        targetReel.uid, myUid, myName, targetReel.reelId,
                        targetReel.thumbUrl != null ? targetReel.thumbUrl : "");
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    // ── Open player ───────────────────────────────────────────────────────

    private void openPlayerAt(int adapterPos) {
        // Pinned reel occupies position 0 in adapter — skip it when calculating reel index
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;
        if (reelIdx < 0) reelIdx = 0;

        List<ReelModel> data = currentGridData();
        if (data.isEmpty()) return;

        // Clamp to valid range
        int safeIdx = Math.min(reelIdx, data.size() - 1);

        // Build ordered ID list from current sorted data (latest first already)
        ArrayList<String> ids = new ArrayList<>();
        for (ReelModel r : data)
            if (r != null && r.reelId != null) ids.add(r.reelId);

        Intent intent = new Intent(this, SingleReelPlayerActivity.class);
        intent.putStringArrayListExtra(SingleReelPlayerActivity.EXTRA_REEL_IDS, ids);
        // safeIdx ensures the tapped reel plays first — not position 0
        intent.putExtra(SingleReelPlayerActivity.EXTRA_START_POSITION, safeIdx);
        intent.putExtra(SingleReelPlayerActivity.EXTRA_TITLE,
            targetName != null ? targetName + "'s Reels" : "Reels");

        // ── Grid → player open animation ────────────────────────────────
        // Instagram-style "pinch zoom" reveal: the tapped thumbnail scales
        // up from its exact on-screen rect to fill the player, instead of
        // the plain default activity swap. Uses ActivityOptions'
        // makeScaleUpAnimation (framework-native, no fragile shared-element
        // matching needed across the player's ViewPager2/Fragment views).
        startActivity(intent, buildGridOpenTransitionBundle(adapterPos));
    }

    /**
     * Builds the scale-up reveal options anchored to the tapped grid cell's
     * thumbnail, if it's currently laid out and on-screen. Falls back to a
     * plain (no-bundle) launch if the ViewHolder/thumbnail can't be resolved
     * — e.g. the tap happened right as the view was being recycled.
     */
    private android.os.Bundle buildGridOpenTransitionBundle(int adapterPos) {
        try {
            if (rvReels == null) return null;
            RecyclerView.ViewHolder holder = rvReels.findViewHolderForAdapterPosition(adapterPos);
            if (holder == null) return null;
            View thumb = holder.itemView.findViewById(R.id.iv_thumb);
            if (thumb == null) thumb = holder.itemView.findViewById(R.id.iv_pinned_thumb);
            if (thumb == null || thumb.getWidth() <= 0 || thumb.getHeight() <= 0) return null;
            int[] loc = new int[2];
            thumb.getLocationOnScreen(loc);
            ActivityOptions options = ActivityOptions.makeScaleUpAnimation(
                    thumb, 0, 0, thumb.getWidth(), thumb.getHeight());
            return options.toBundle();
        } catch (Throwable t) {
            return null; // safe fallback — plain startActivity(intent, null) below
        }
    }

    // ── Long press → peek preview ───────────────────────────────────────
    //
    // Fires the instant a press-and-hold crosses the system long-press
    // timeout (see ReelGridAdapter#wireItemInteractions) — this is the
    // "peek START" edge. Finger release/lift is NOT wired to close the
    // peek anymore — the popup stays open after release and only closes
    // via a tap outside the mini player (its scrim), "Watch Reel", or the
    // activity's own dismissPreviewDialog() calls (onPause/onDestroy/tab
    // switch). A quick tap never reaches this method at all.
    //
    // ULTRA: long-press now ONLY opens the mini player peek — it no longer
    // ALSO immediately fires the "Reel Options" AlertDialog or multi-select
    // alongside it (that was the bug: both used to fire together the moment
    // you long-pressed, on the same gesture). Management options for the
    // owner's own reel (Insights/Pin/Share/Delete) are now rendered as a
    // compact card INSIDE the peek popup itself (see
    // ReelPeekPreviewController's card_peek_options) — reachable only by
    // tapping "Options" inside the peek, not before it. Multi-select is no
    // longer reachable from long-press at all — see setupMoreMenu() → "Select".

    /**
     * True if the reel has ANY playable source for the long-press mini
     * preview — mirrors ReelPeekPreviewController#hasPreviewableVideo().
     * BUG FIX: previously this gate checked ONLY reel.videoUrl, so reels
     * whose videoUrl happened to be empty (relying on hlsManifestUrl /
     * video480 / video720 / video1080 instead) silently failed to open the
     * mini player on long-press — this is why the feature "worked on some
     * reels but not others". Photo-slideshow reels (no video field at all)
     * still correctly get no preview.
     */
    private boolean hasPreviewableVideo(ReelModel reel) {
        if (reel == null) return false;
        return (reel.hlsManifestUrl != null && !reel.hlsManifestUrl.isEmpty())
                || (reel.videoUrl   != null && !reel.videoUrl.isEmpty())
                || (reel.video480   != null && !reel.video480.isEmpty())
                || (reel.video720   != null && !reel.video720.isEmpty())
                || (reel.video1080  != null && !reel.video1080.isEmpty());
    }

    @Override
    public void onLongPress(int adapterPos) {
        List<ReelModel> data = currentGridData();
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;
        // Self's own Reels tab gets management options (Insights/Pin/Share/
        // Delete) in the peek's compact card; anyone else's reel just gets
        // the plain mini-player preview with no options row.
        boolean ownerContext = isSelf && activeTab == TAB_REELS;

        ReelModel reel;
        // BUG FIX: the pinned reel occupies adapter position 0 for EVERY
        // viewer (owner or not) whenever one is set — it was previously
        // only resolved here when `ownerContext` was true, so long-pressing
        // the pinned card silently did nothing on anyone else's profile
        // (adapterPos==0 → reelIdx==-1 → falls through to `reel = null`
        // below). The pinned reel itself should always resolve; only the
        // owner-only MANAGEMENT OPTIONS (Insights/Pin/Share/Delete) stay
        // gated behind ownerContext, further down.
        boolean isPinnedCell = adapter.hasPinned() && adapterPos == 0 && pinnedReel != null;
        if (isPinnedCell) {
            reel = pinnedReel;
        } else if (reelIdx >= 0 && reelIdx < data.size()) {
            reel = data.get(reelIdx);
        } else {
            reel = null;
        }

        if (reel == null) return;

        if (!hasPreviewableVideo(reel)) {
            // Nothing to preview — owner still gets the options card via a
            // reel with no video is rare/unexpected for this screen, so
            // just no-op rather than falling back to a dialog/multi-select.
            return;
        }

        final ReelModel finalReel = reel;
        List<ReelPeekPreviewController.PeekOption> options = new ArrayList<>();
        if (ownerContext) {
            boolean isPinned = pinnedReel != null && finalReel.reelId != null
                    && finalReel.reelId.equals(pinnedReel.reelId);
            options.add(new ReelPeekPreviewController.PeekOption("View Insights", R.drawable.ic_eye, () ->
                    ReelAnalyticsBottomSheet.newInstance(finalReel).show(getSupportFragmentManager(), "analytics")));
            options.add(new ReelPeekPreviewController.PeekOption(
                    isPinned ? "Unpin Reel" : "Pin Reel", R.drawable.ic_pin, () -> {
                        if (isPinned) unpinReel(); else pinReel(finalReel.reelId);
                    }));
            options.add(new ReelPeekPreviewController.PeekOption("Share", R.drawable.ic_share_reel, this::shareProfile));
            options.add(new ReelPeekPreviewController.PeekOption("Delete", R.drawable.ic_delete, () ->
                    confirmDeleteSingleReel(finalReel)));
        }

        View sourceCell = null;
        RecyclerView.ViewHolder svh = rvReels.findViewHolderForAdapterPosition(adapterPos);
        if (svh != null) sourceCell = svh.itemView;

        peekController.show(reel, options, () -> openPlayerAt(adapterPos), sourceCell);
    }

    // ── Analytics sheet (Feature 15) ──────────────────────────────────────
    // Kept as a helper so the individual PeekOption actions above (and any
    // other callers) can still reuse the confirm-delete flow etc.

    private void confirmDeleteSingleReel(ReelModel reel) {
        AlertDialogStyler.showReusableConfirm(this, "delete_single_reel",
            AlertDialogStyler.DialogSize.DEFAULT,
            "Delete Reel",
            "This reel will be permanently deleted.",
            "Delete", () -> {
                FirebaseUtils.getReelsRef().child(reel.reelId).removeValue();
                FirebaseUtils.getReelsByUserRef(targetUid).child(reel.reelId).removeValue();
                if (pinnedReel != null && reel.reelId.equals(pinnedReel.reelId)) unpinReel();
                List<ReelModel> oldSnapshot = new ArrayList<>(reelsTabData);
                reelsTabData.remove(reel);
                adapter.diffDataSetChanged(oldSnapshot);
                refreshEmptyState();
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            },
            null, null,
            "Cancel");
    }

    // ── Peek preview dismiss ────────────────────────────────────────────
    // Kept under its original name (called from onPause/onDestroy/the
    // adapter's release-callback) — now just closes the peek popup instead
    // of the old full-screen Dialog + ExoPlayer pair it used to own.

    private void dismissPreviewDialog() {
        if (peekController != null) peekController.dismiss();
    }

    // ── Multi-select (Feature 5) ──────────────────────────────────────────

    private void setupMultiSelectBar() {
        if (layoutMultiSelectBar == null) return;
        if (btnCancelSelect  != null) btnCancelSelect.setOnClickListener(v -> exitMultiSelectMode());
        if (btnShareSelected != null) btnShareSelected.setOnClickListener(v -> shareSelectedReels());
        if (btnDeleteSelected != null) btnDeleteSelected.setOnClickListener(v -> deleteSelectedReels());
        // Delete All — only visible for the profile owner
        if (btnDeleteAll != null) {
            btnDeleteAll.setVisibility(isSelf ? View.VISIBLE : View.GONE);
            if (isSelf) btnDeleteAll.setOnClickListener(v -> deleteAllReels());
        }
    }

    private void enterMultiSelectMode(int initialPos) {
        isMultiSelect = true;
        adapter.setMultiSelectMode(true);
        if (layoutMultiSelectBar != null) layoutMultiSelectBar.setVisibility(View.VISIBLE);
        // ULTRA: entry point moved from long-press to the 3-dot menu (see
        // setupMoreMenu() → "Select") — that entry has no specific cell the
        // user pressed, so it calls this with -1 to just arm select mode
        // without pre-selecting anything.
        if (initialPos >= 0) toggleSelection(initialPos);
    }

    private void exitMultiSelectMode() {
        isMultiSelect = false;
        selectedReelIds.clear();
        adapter.setMultiSelectMode(false);
        adapter.clearSelections();
        if (layoutMultiSelectBar != null) layoutMultiSelectBar.setVisibility(View.GONE);
        if (tvSelectedCount != null) tvSelectedCount.setText("0 Selected");
    }

    private void toggleSelection(int adapterPos) {
        List<ReelModel> data = currentGridData();
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;
        if (reelIdx < 0 || reelIdx >= data.size()) return;
        String reelId = data.get(reelIdx).reelId;
        if (selectedReelIds.contains(reelId)) {
            selectedReelIds.remove(reelId); adapter.setSelected(adapterPos, false);
        } else {
            selectedReelIds.add(reelId); adapter.setSelected(adapterPos, true);
        }
        adapter.notifyItemChanged(adapterPos);
        if (tvSelectedCount != null) tvSelectedCount.setText(selectedReelIds.size() + " Selected");
        if (selectedReelIds.isEmpty()) exitMultiSelectMode();
    }

    @Override
    public void onSelectionChanged(int count) {
        if (tvSelectedCount != null) tvSelectedCount.setText(count + " Selected");
    }

    private void shareSelectedReels() {
        if (selectedReelIds.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (ReelModel r : activeTabData())
            if (selectedReelIds.contains(r.reelId) && r.videoUrl != null)
                sb.append(r.videoUrl).append("\n");
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, sb.toString().trim());
        startActivity(Intent.createChooser(share, "Share Reels"));
        exitMultiSelectMode();
    }

    private void deleteSelectedReels() {
        String myUid = safeMyUid();
        if (myUid == null || selectedReelIds.isEmpty()) return;
        if (!targetUid.equals(myUid)) {
            Toast.makeText(this, "You can only delete your own reels", Toast.LENGTH_SHORT).show(); return;
        }
        AlertDialogStyler.showReusableConfirm(this, "delete_selected_reels",
            AlertDialogStyler.DialogSize.DEFAULT,
            "Delete Reels",
            "Delete " + selectedReelIds.size() + " reel(s)? This cannot be undone.",
            "Delete", () -> {
                for (String id : new HashSet<>(selectedReelIds)) {
                    FirebaseUtils.getReelsRef().child(id).removeValue();
                    FirebaseUtils.getReelsByUserRef(myUid).child(id).removeValue();
                    FirebaseUtils.db().getReference("userReels").child(myUid).child(id).removeValue();
                    if (pinnedReel != null && id.equals(pinnedReel.reelId)) unpinReel();
                }
                List<ReelModel> oldSnapshot = new ArrayList<>(activeTabData());
                activeTabData().removeIf(r -> selectedReelIds.contains(r.reelId));
                exitMultiSelectMode();
                if (isPostsTabActive()) {
                    // displayList is a filtered copy, not activeTabData()
                    // itself — re-derive it instead of diffing the wrong list.
                    applyFilter();
                } else {
                    adapter.diffDataSetChanged(oldSnapshot);
                }
                refreshEmptyState();
                Toast.makeText(this, "Deleted successfully", Toast.LENGTH_SHORT).show();
            },
            null, null,
            "Cancel");
    }

    private void deleteAllReels() {
        String myUid = safeMyUid();
        if (myUid == null || !targetUid.equals(myUid)) return;
        List<ReelModel> data = activeTabData();
        if (data.isEmpty()) {
            Toast.makeText(this, "No reels to delete", Toast.LENGTH_SHORT).show();
            exitMultiSelectMode();
            return;
        }
        AlertDialogStyler.showReusableConfirm(this, "delete_all_reels",
            AlertDialogStyler.DialogSize.DEFAULT,
            "Delete All Reels",
            "Delete all " + data.size() + " reel(s)? This cannot be undone.",
            "Delete All", () -> {
                for (ReelModel r : new ArrayList<>(data)) {
                    if (r.reelId == null) continue;
                    FirebaseUtils.getReelsRef().child(r.reelId).removeValue();
                    FirebaseUtils.getReelsByUserRef(myUid).child(r.reelId).removeValue();
                    FirebaseUtils.db().getReference("userReels").child(myUid).child(r.reelId).removeValue();
                }
                if (pinnedReel != null) unpinReel();
                List<ReelModel> oldSnapshot = new ArrayList<>(data);
                data.clear();
                exitMultiSelectMode();
                adapter.diffDataSetChanged(oldSnapshot);
                refreshEmptyState();
                Toast.makeText(this, "All reels deleted", Toast.LENGTH_SHORT).show();
            },
            null, null,
            "Cancel");
    }

    // ── Action buttons ────────────────────────────────────────────────────

    private void setupActionButtons() {
        if (btnMessage != null) btnMessage.setOnClickListener(v ->
            launchActivity("com.callx.app.conversation.ChatActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto"},
                new String[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto)}));

        if (btnAudioCall != null) btnAudioCall.setOnClickListener(v -> {
            String cid = FirebaseDatabase.getInstance().getReference("calls").push().getKey();
            launchActivity("com.callx.app.call.CallActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto","isCaller","video","callId"},
                new Object[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto), true, false, orEmpty(cid)});
        });
        if (btnVideoCall != null) btnVideoCall.setOnClickListener(v -> {
            String cid = FirebaseDatabase.getInstance().getReference("calls").push().getKey();
            launchActivity("com.callx.app.call.CallActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto","isCaller","video","callId"},
                new Object[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto), true, true, orEmpty(cid)});
        });

        // X profile button
        if (btnOpenX != null) btnOpenX.setOnClickListener(v -> {
            if (targetUid == null || targetUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.profile.XProfileSheet");
                java.lang.reflect.Method method = cls.getMethod("showProfile",
                        androidx.fragment.app.FragmentManager.class, String.class);
                method.invoke(null, getSupportFragmentManager(), targetUid);
            } catch (Exception e) {
                Toast.makeText(this, "X profile not available", Toast.LENGTH_SHORT).show();
            }
        });

        // YouTube channel button
        if (btnOpenYoutube != null) btnOpenYoutube.setOnClickListener(v -> {
            if (targetUid == null || targetUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.channel.YouTubeChannelActivity");
                Intent i = new Intent(this, cls);
                i.putExtra("uid",  targetUid);
                i.putExtra("name", orEmpty(targetName));
                startActivity(i);
            } catch (ClassNotFoundException e) {
                Toast.makeText(this, "YouTube channel not available", Toast.LENGTH_SHORT).show();
            }
        });

        if (btnFollow     != null) btnFollow.setOnClickListener(v -> toggleFollow());
        if (btnMessageCta != null) btnMessageCta.setOnClickListener(v ->
            launchActivity("com.callx.app.conversation.ChatActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto"},
                new String[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto)}));
        // +person button → show/hide suggested panel
        if (btnCtaCall != null) btnCtaCall.setOnClickListener(v -> toggleSuggestedPanel());

        // Call button in extra-actions row → audio call
        if (btnCallRow != null) btnCallRow.setOnClickListener(v -> {
            String cid = FirebaseDatabase.getInstance().getReference("calls").push().getKey();
            launchActivity("com.callx.app.call.CallActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto","isCaller","video","callId"},
                new Object[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto), true, false, orEmpty(cid)});
        });

        // "See all" → FollowConnectionsActivity on Suggested tab (same screen as Followers/Following)
        if (tvSeeAllSuggested != null) tvSeeAllSuggested.setOnClickListener(v -> {
            android.content.Intent i = new android.content.Intent(
                    this, com.callx.app.followers.FollowConnectionsActivity.class);
            i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_UID,       targetUid);
            i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_NAME,      orEmpty(targetName));
            i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_IS_SELF,   isSelf);
            i.putExtra(com.callx.app.followers.FollowConnectionsActivity.EXTRA_START_TAB,
                       com.callx.app.followers.FollowConnectionsActivity.TAB_SUGGESTED);
            startActivity(i);
        });
    }

    // ── Suggested for you panel toggle + loader ─────────────────────────

    private boolean suggestedPanelOpen = false;
    private boolean suggestedLoaded    = false;

    private void toggleSuggestedPanel() {
        if (layoutSuggestedForYou == null) return;
        suggestedPanelOpen = !suggestedPanelOpen;
        layoutSuggestedForYou.setVisibility(suggestedPanelOpen ? View.VISIBLE : View.GONE);
        if (suggestedPanelOpen && !suggestedLoaded) {
            suggestedLoaded = true;
            loadSuggestedUsers();
        }
    }

    private void loadSuggestedUsers() {
        if (targetUid == null || llSuggestedCards == null) return;
        String myUid;
        try {
            com.google.firebase.auth.FirebaseUser cu =
                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
            myUid = cu != null ? cu.getUid() : null;
        } catch (Exception e) { myUid = null; }
        final String finalMyUid = myUid;

        // Load people targetUser follows → filter out myself + people I already follow
        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("reelFollows").child(targetUid)
            .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                    // First get my follows set, then filter
                    if (finalMyUid == null) {
                        buildSuggestedCards(snap, null, new java.util.HashSet<>());
                        return;
                    }
                    com.google.firebase.database.FirebaseDatabase.getInstance(DB)
                        .getReference("reelFollows").child(finalMyUid)
                        .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                            @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot mySnap) {
                                java.util.Set<String> myFollowing = new java.util.HashSet<>();
                                for (com.google.firebase.database.DataSnapshot s : mySnap.getChildren())
                                    myFollowing.add(s.getKey());
                                buildSuggestedCards(snap, finalMyUid, myFollowing);
                            }
                            @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                                buildSuggestedCards(snap, finalMyUid, new java.util.HashSet<>());
                            }
                        });
                }
                @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {}
            });
    }

    private void buildSuggestedCards(com.google.firebase.database.DataSnapshot snap,
                                     String myUid, java.util.Set<String> myFollowing) {
        java.util.List<String> candidates = new java.util.ArrayList<>();
        for (com.google.firebase.database.DataSnapshot s : snap.getChildren()) {
            String uid = s.getKey();
            if (uid == null) continue;
            if (uid.equals(myUid)) continue;
            if (myFollowing.contains(uid)) continue;
            candidates.add(uid);
            if (candidates.size() >= 5) break;  // show max 5 in panel
        }
        if (candidates.isEmpty()) {
            // No suggestions — hide panel
            runOnUiThread(() -> {
                if (layoutSuggestedForYou != null)
                    layoutSuggestedForYou.setVisibility(View.GONE);
                suggestedPanelOpen = false;
            });
            return;
        }
        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
        final int total = candidates.size();
        final int[] done = {0};
        final java.util.Map<String, String[]> userMap = new java.util.LinkedHashMap<>();
        for (String uid : candidates) {
            final String candidateUid = uid;
            com.google.firebase.database.FirebaseDatabase.getInstance(DB)
                .getReference("users").child(uid)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot us) {
                        String name  = us.child("name").getValue(String.class);
                        String photo = us.child("photoUrl").getValue(String.class);
                        String thumb = us.child("thumbUrl").getValue(String.class);
                        photo = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                        userMap.put(candidateUid, new String[]{
                            name  != null ? name  : "User",
                            photo != null ? photo : ""
                        });
                        done[0]++;
                        if (done[0] >= total) renderSuggestedCards(userMap, myFollowing);
                    }
                    @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                        done[0]++;
                        if (done[0] >= total) renderSuggestedCards(userMap, myFollowing);
                    }
                });
        }
    }

    private void renderSuggestedCards(java.util.Map<String, String[]> userMap,
                                      java.util.Set<String> myFollowing) {
        if (isFinishing() || isDestroyed()) return;
        runOnUiThread(() -> {
            if (llSuggestedCards == null) return;
            llSuggestedCards.removeAllViews();
            android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
            for (java.util.Map.Entry<String, String[]> entry : userMap.entrySet()) {
                String   uid   = entry.getKey();
                String[] info  = entry.getValue();
                String   name  = info[0];
                String   photo = info[1];

                android.view.View card = inflater.inflate(
                    R.layout.item_suggested_card, llSuggestedCards, false);

                de.hdodenhof.circleimageview.CircleImageView ivAvatar =
                    card.findViewById(R.id.iv_avatar);
                android.widget.TextView tvName = card.findViewById(R.id.tv_name);
                android.widget.Button   btnFol = card.findViewById(R.id.btn_follow);
                android.widget.ImageButton btnX = card.findViewById(R.id.btn_dismiss);

                tvName.setText(name);
                if (photo != null && !photo.isEmpty() && ivAvatar != null) {
                    com.bumptech.glide.Glide.with(this)
                        .load(photo).circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .override(128, 128)
                        .into(ivAvatar);
                }

                // Follow button
                final boolean[] followed = {myFollowing.contains(uid)};
                if (btnFol != null) {
                    btnFol.setText(followed[0] ? "Following" : "Follow");
                    btnFol.setOnClickListener(v -> {
                        String myUid2;
                        try {
                            com.google.firebase.auth.FirebaseUser cu2 =
                                com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
                            myUid2 = cu2 != null ? cu2.getUid() : null;
                        } catch (Exception ex) { myUid2 = null; }
                        if (myUid2 == null) return;
                        followed[0] = !followed[0];
                        btnFol.setText(followed[0] ? "Following" : "Follow");
                        final String fu = myUid2;
                        if (followed[0]) {
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("reelFollows").child(fu).child(uid).setValue(true);
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("reelFollowers").child(uid).child(fu).setValue(true);
                        } else {
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("reelFollows").child(fu).child(uid).removeValue();
                            com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("reelFollowers").child(uid).child(fu).removeValue();
                        }
                    });
                }
                // Dismiss X button
                if (btnX != null) {
                    final android.view.View cardRef = card;
                    btnX.setOnClickListener(v -> {
                        if (llSuggestedCards != null) llSuggestedCards.removeView(cardRef);
                        if (llSuggestedCards != null && llSuggestedCards.getChildCount() == 0) {
                            if (layoutSuggestedForYou != null)
                                layoutSuggestedForYou.setVisibility(View.GONE);
                            suggestedPanelOpen = false;
                        }
                    });
                }
                // Card tap → open profile
                card.setOnClickListener(v -> {
                    android.content.Intent i2 = new android.content.Intent(
                            this, UserReelsActivity.class);
                    i2.putExtra(EXTRA_UID,  uid);
                    i2.putExtra(EXTRA_NAME, name);
                    if (photo != null && !photo.isEmpty()) i2.putExtra(EXTRA_PHOTO, photo);
                    startActivity(i2);
                });

                llSuggestedCards.addView(card);
            }
        });
    }

    // ── Avatar Peek Animation ─────────────────────────────────────────────
    /**
     * Teen alag animation avatars load karte hain — har button ke liye:
     *   - Chat button    → users/{uid}           (main CallX profile, HD cached)
     *   - X button       → x/users/{uid}          (X / Twitter profile, cached)
     *   - YouTube button → youtube/channels/{uid}  (YouTube channel, cached)
     * Agar koi platform profile nahi hai to ic_person placeholder rahega.
     * Phir teeno avatars pe loop animation start hoti hai (peek out → hold → peek in → repeat).
     * Main ivAvatar HD-only load hota hai — permanently cached via DiskCacheStrategy.ALL.
     */

    // ── HD Avatar loader (permanently cached) ────────────────────────────────
    /**
     * Always loads photoUrl at HD quality (720×720) — no low-res fallback.
     *
     * Caching strategy:
     *  • DiskCacheStrategy.ALL  → source file + decoded bitmap both cached on disk permanently.
     *  • skipMemoryCache(false) → decoded bitmap also lives in LRU memory cache.
     *  • On revisit: zero network — instant display from memory or disk cache.
     *  • override(720,720)      → HD decode, sharp even on xxxhdpi screens.
     *
     * Called from loadAvatarAndStartAnimation() after Firebase returns photoUrl.
     */
    private void loadProfileAvatarInstagramStyle(String photoUrl) {
        if (ivAvatar == null || photoUrl == null || photoUrl.isEmpty()) return;

        Glide.with(this)
            .load(photoUrl)
            .circleCrop()
            .diskCacheStrategy(DiskCacheStrategy.ALL)      // source + decoded bitmap permanently cached
            .override(720, 720)                            // HD always — xxxhdpi pe bhi sharp
            .placeholder(R.drawable.ic_person)
            .skipMemoryCache(false)                        // memory cache active — revisit pe instant display
            .into(ivAvatar);
    }

    private void loadAvatarAndStartAnimation() {
        if (targetUid == null) return;

        final String DB = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";

        // isSelf: Firebase se fresh photoUrl fetch karo (Intent ka targetPhoto stale ho sakta hai)
        // Animation avatars (Chat/X/YouTube) sirf other users ke liye hain.
        if (isSelf) {
            com.google.firebase.database.FirebaseDatabase.getInstance(DB)
                .getReference("users").child(targetUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String photo = snap.child("photoUrl").getValue(String.class);
                        if (photo != null && !photo.isEmpty()) {
                            loadProfileAvatarInstagramStyle(photo);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
            return;
        }

        // Other user: Firebase se photoUrl fetch karo → ivAvatar HD load + ivAnimChat.
        // Single Firebase call, dono kaam.
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("users").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String photo = snap.child("photoUrl").getValue(String.class);

                    // ivAvatar — HD direct load, permanently cached
                    if (photo != null && !photo.isEmpty()) {
                        loadProfileAvatarInstagramStyle(photo);
                    }

                    // ivAnimChat — animation icon (small view), permanently cached
                    if (ivAnimChat != null && photo != null && !photo.isEmpty()) {
                        Glide.with(UserReelsActivity.this)
                            .load(photo).circleCrop()
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .placeholder(R.drawable.ic_person)
                            .skipMemoryCache(false)
                            .override(96, 96)
                            .into(ivAnimChat);
                    }
                    startAvatarPeekLoop();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    startAvatarPeekLoop();
                }
            });

        // 2) X avatar — x/users/{uid}
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("x/users").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb
                               : (photo != null && !photo.isEmpty()) ? photo : null;
                    if (ivAnimX == null || url == null) return;
                    Glide.with(UserReelsActivity.this)
                        .load(url).circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .override(240, 240)
                        .into(ivAnimX);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });

        // 3) YouTube avatar — youtube/channels/{uid}
        com.google.firebase.database.FirebaseDatabase.getInstance(DB)
            .getReference("youtube/channels").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String thumb = snap.child("thumbUrl").getValue(String.class);
                    String photo = snap.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb
                               : (photo != null && !photo.isEmpty()) ? photo : null;
                    if (ivAnimYoutube == null || url == null) return;
                    Glide.with(UserReelsActivity.this)
                        .load(url).circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .override(240, 240)
                        .into(ivAnimYoutube);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    /**
     * Loop: Chat → X → YouTube → Chat → ...
     * Each cycle: peek out (600ms) → hold 3s → peek in (600ms) → wait 3s → next button
     */
    private void startAvatarPeekLoop() {
        if (animRunning) return;
        animRunning = true;

        CircleImageView[] views = {ivAnimChat, ivAnimX, ivAnimYoutube};

        // Initialize all: hidden, scaled to 0, centered on button
        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f);
            iv.setScaleY(0f);
            iv.setAlpha(0f);
        }

        animRunnable = new Runnable() {
            int idx = 0;

            @Override public void run() {
                if (!animRunning || isFinishing() || isDestroyed()) return;

                CircleImageView iv = views[idx % views.length];
                idx++;

                if (iv == null) {
                    animHandler.postDelayed(this, 500);
                    return;
                }

                // Reset to hidden/zero state
                iv.setScaleX(0f);
                iv.setScaleY(0f);
                iv.setAlpha(0f);
                iv.setVisibility(View.VISIBLE);

                // Zoom IN: scale 0 → 1.05 (subtle overshoot) then settle to 1.0, alpha 0 → 1
                ObjectAnimator scaleXIn  = ObjectAnimator.ofFloat(iv, "scaleX", 0f, 1.05f, 1.0f);
                ObjectAnimator scaleYIn  = ObjectAnimator.ofFloat(iv, "scaleY", 0f, 1.05f, 1.0f);
                ObjectAnimator alphaIn   = ObjectAnimator.ofFloat(iv, "alpha",  0f, 1f);
                scaleXIn.setDuration(450);
                scaleYIn.setDuration(450);
                alphaIn.setDuration(250);
                scaleXIn.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));
                scaleYIn.setInterpolator(new android.view.animation.DecelerateInterpolator(2f));

                AnimatorSet zoomIn = new AnimatorSet();
                zoomIn.playTogether(scaleXIn, scaleYIn, alphaIn);

                // Zoom OUT: scale 1.0 → 0, alpha 1 → 0  (after 3s hold)
                ObjectAnimator scaleXOut = ObjectAnimator.ofFloat(iv, "scaleX", 1.0f, 0f);
                ObjectAnimator scaleYOut = ObjectAnimator.ofFloat(iv, "scaleY", 1.0f, 0f);
                ObjectAnimator alphaOut  = ObjectAnimator.ofFloat(iv, "alpha",  1f, 0f);
                scaleXOut.setDuration(400);
                scaleYOut.setDuration(400);
                alphaOut.setDuration(400);
                scaleXOut.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
                scaleYOut.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));

                AnimatorSet zoomOut = new AnimatorSet();
                zoomOut.playTogether(scaleXOut, scaleYOut, alphaOut);
                zoomOut.setStartDelay(3000); // hold visible for 3 seconds

                AnimatorSet full = new AnimatorSet();
                full.playSequentially(zoomIn, zoomOut);
                full.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        iv.setVisibility(View.INVISIBLE);
                        iv.setScaleX(0f);
                        iv.setScaleY(0f);
                        iv.setAlpha(0f);
                        // 3 second gap then next button
                        if (animRunning && !isFinishing() && !isDestroyed())
                            animHandler.postDelayed(animRunnable, 3000);
                    }
                });
                full.start();
            }
        };

        animHandler.postDelayed(animRunnable, 1500);
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    private void stopAvatarAnimation() {
        animRunning = false;
        animHandler.removeCallbacks(animRunnable);
        CircleImageView[] views = {ivAnimChat, ivAnimX, ivAnimYoutube};
        for (CircleImageView iv : views) {
            if (iv == null) continue;
            iv.setVisibility(View.INVISIBLE);
            iv.setScaleX(0f);
            iv.setScaleY(0f);
            iv.setAlpha(0f);
        }
    }

    private void launchActivity(String className, String[] keys, String[] values) {
        try {
            Class<?> cls = Class.forName(className);
            Intent i = new Intent(this, cls);
            for (int x = 0; x < keys.length; x++) i.putExtra(keys[x], values[x]);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    private void launchActivity(String className, String[] keys, Object[] values) {
        try {
            Class<?> cls = Class.forName(className);
            Intent i = new Intent(this, cls);
            for (int x = 0; x < keys.length; x++) {
                if (values[x] instanceof Boolean) i.putExtra(keys[x], (boolean) values[x]);
                else i.putExtra(keys[x], (String) values[x]);
            }
            startActivity(i);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Follow ────────────────────────────────────────────────────────────

    private void loadFollowState() {
        String myUid = safeMyUid();
        if (myUid == null || isSelf) return;
        FirebaseUtils.getReelFollowsRef(myUid).child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    isFollowing = snap.exists() && Boolean.TRUE.equals(snap.getValue(Boolean.class));
                    updateFollowButton();
                    applyPrivacyState();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void toggleFollow() {
        String myUid = safeMyUid();
        if (myUid == null) return;
        if (isFollowing) {
            // Show Instagram-style "Following" bottom sheet instead of immediately unfollowing
            showFollowingOptionsSheet();
            return;
        }
        isFollowing = true;
        updateFollowButton();
        applyPrivacyState();
        FirebaseUtils.getReelFollowsRef(myUid).child(targetUid).setValue(true);
        FirebaseUtils.getReelFollowersRef(targetUid).child(myUid).setValue(true);
        updateFollowerCountUI(1);
        // Auto-show suggested panel after follow (like Instagram)
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!suggestedPanelOpen) toggleSuggestedPanel();
        }, 600);
    }

    private void updateFollowButton() {
        if (btnFollow == null) return;
        if (isFollowing) {
            btnFollow.setText("Following  ▾");
            btnFollow.setTextColor(0xFF222222);
            try {
                android.graphics.drawable.Drawable d =
                    androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_btn_outline_pill);
                btnFollow.setBackground(d != null ? d.mutate() : null);
            } catch (Exception e) {
                btnFollow.setBackgroundColor(0xFFEEEEEE);
            }
        } else {
            btnFollow.setText("Follow");
            btnFollow.setTextColor(0xFFFFFFFF);
            try {
                android.graphics.drawable.Drawable d =
                    androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_btn_follow_pill);
                btnFollow.setBackground(d != null ? d.mutate() : null);
            } catch (Exception e) {
                btnFollow.setBackgroundColor(0xFF6C5CE7);
            }
        }
    }

    /**
     * Instagram-style "Following" bottom sheet.
     * Shows: Add to Close Friends, Add to favorites, Mute, Restrict, Unfollow.
     */
    private void showFollowingOptionsSheet() {
        if (isFinishing() || isDestroyed()) return;
        android.view.View sheetView = android.view.LayoutInflater.from(this)
            .inflate(android.R.layout.simple_list_item_1, null);

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        // Build sheet layout programmatically
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(0, 24, 0, 40);
        container.setBackgroundColor(android.graphics.Color.WHITE);

        // Title
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText(targetName != null ? targetName : "");
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, 16);
        title.setTextColor(0xFF111111);
        container.addView(title);

        // Divider
        android.view.View div = new android.view.View(this);
        div.setBackgroundColor(0xFFEEEEEE);
        android.widget.LinearLayout.LayoutParams divLp = new android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
        container.addView(div, divLp);

        // Option rows
        String[] labels   = {"Add to Close Friends list", "Add to favorites", "Mute", "Restrict", "Unfollow"};
        int[]    iconRes  = {
            android.R.drawable.star_on,
            android.R.drawable.btn_star_big_off,
            android.R.drawable.ic_lock_idle_lock,
            android.R.drawable.ic_menu_close_clear_cancel,
            android.R.drawable.ic_delete
        };
        boolean[] hasArrow = {false, false, true, true, false};

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int ph = (int)(20 * getResources().getDisplayMetrics().density);
            int pv = (int)(16 * getResources().getDisplayMetrics().density);
            row.setPadding(ph, pv, ph, pv);
            row.setBackground(obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground})
                .getDrawable(0));
            row.setClickable(true); row.setFocusable(true);

            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(labels[idx]);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            tv.setTextColor(idx == 4 ? 0xFFE53935 : 0xFF111111); // Unfollow in red
            tv.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tv);

            if (hasArrow[idx]) {
                android.widget.TextView arrow = new android.widget.TextView(this);
                arrow.setText("›");
                arrow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f);
                arrow.setTextColor(0xFF888888);
                row.addView(arrow);
            }

            row.setOnClickListener(v -> {
                sheet.dismiss();
                handleFollowingSheetOption(idx);
            });

            container.addView(row);

            // Light divider between rows (not after last)
            if (i < labels.length - 1) {
                android.view.View rowDiv = new android.view.View(this);
                rowDiv.setBackgroundColor(0xFFF2F2F2);
                android.widget.LinearLayout.LayoutParams rdLp =
                    new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
                int lm = (int)(20 * getResources().getDisplayMetrics().density);
                rdLp.setMarginStart(lm);
                container.addView(rowDiv, rdLp);
            }
        }

        sheet.setContentView(container);
        sheet.show();
    }

    private void handleFollowingSheetOption(int idx) {
        String myUid = safeMyUid();
        switch (idx) {
            case 0: // Add to Close Friends
                if (myUid != null) {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reelCloseFriends").child(myUid).child(targetUid).setValue(true);
                }
                Toast.makeText(this, "Added to Close Friends list", Toast.LENGTH_SHORT).show(); break;
            case 1: // Add to favorites
                if (myUid != null) {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reelFavorites").child(myUid).child(targetUid).setValue(true);
                }
                Toast.makeText(this, "Added to Favorites", Toast.LENGTH_SHORT).show(); break;
            case 2: // Mute — show sub-sheet with toggles
                showMuteSheet(); break;
            case 3: // Restrict
                if (myUid != null) {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reelRestricted").child(myUid).child(targetUid).setValue(true);
                }
                Toast.makeText(this, "Restricted. They won't know.", Toast.LENGTH_SHORT).show(); break;
            case 4: // Unfollow
                if (myUid == null) return;
                isFollowing = false;
                updateFollowButton();
                applyPrivacyState();
                FirebaseUtils.getReelFollowsRef(myUid).child(targetUid).removeValue();
                FirebaseUtils.getReelFollowersRef(targetUid).child(myUid).removeValue();
                updateFollowerCountUI(-1);
                break;
        }
    }

    /**
     * Mute sub-sheet — shown when user picks "Mute" from the Following options bottom sheet.
     * Mirrors Instagram's Mute screen with toggles for Posts, Stories, Activity bubbles, etc.
     * Toggle states are persisted to Firebase under reelMute/{myUid}/{targetUid}.
     */
    private void showMuteSheet() {
        if (isFinishing() || isDestroyed()) return;
        String myUid = safeMyUid();

        com.google.android.material.bottomsheet.BottomSheetDialog sheet =
            new com.google.android.material.bottomsheet.BottomSheetDialog(this);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setBackgroundColor(android.graphics.Color.WHITE);

        float dp = getResources().getDisplayMetrics().density;
        int padH = (int)(20 * dp);
        int padV = (int)(16 * dp);

        // Header row with back arrow + title
        android.widget.LinearLayout header = new android.widget.LinearLayout(this);
        header.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(padH, (int)(14 * dp), padH, (int)(14 * dp));

        android.widget.ImageButton btnBack = new android.widget.ImageButton(this);
        try { btnBack.setImageResource(R.drawable.ic_back); } catch (Exception ignored) {}
        btnBack.setBackground(null);
        btnBack.setOnClickListener(v -> sheet.dismiss());
        header.addView(btnBack, (int)(32 * dp), (int)(32 * dp));

        android.widget.TextView tvTitle = new android.widget.TextView(this);
        tvTitle.setText("Mute");
        tvTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setTextColor(0xFF111111);
        android.widget.LinearLayout.LayoutParams titleLp = new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = (int)(12 * dp);
        header.addView(tvTitle, titleLp);
        root.addView(header);

        // Divider
        android.view.View topDiv = new android.view.View(this);
        topDiv.setBackgroundColor(0xFFEEEEEE);
        root.addView(topDiv, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1);

        // Mute toggle items
        String[] muteItems = {
            "Posts", "Stories", "Activity bubbles on content",
            "Notes", "Notes on the map", "Location on the map", "Instants"
        };
        String[] muteKeys = {
            "posts", "stories", "activityBubbles",
            "notes", "notesOnMap", "locationOnMap", "instants"
        };

        // Load current mute state from Firebase and build rows
        android.widget.LinearLayout itemsContainer = new android.widget.LinearLayout(this);
        itemsContainer.setOrientation(android.widget.LinearLayout.VERTICAL);

        for (int i = 0; i < muteItems.length; i++) {
            final String key = muteKeys[i];
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(padH, padV, padH, padV);

            android.widget.TextView label = new android.widget.TextView(this);
            label.setText(muteItems[i]);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            label.setTextColor(0xFF111111);
            android.widget.LinearLayout.LayoutParams lblLp = new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(label, lblLp);

            androidx.appcompat.widget.SwitchCompat sw = new androidx.appcompat.widget.SwitchCompat(this);
            sw.setChecked(false);
            row.addView(sw);

            // Load persisted state
            if (myUid != null) {
                com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("reelMute").child(myUid).child(targetUid).child(key)
                    .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override
                        public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                            Boolean val = snap.getValue(Boolean.class);
                            sw.setChecked(Boolean.TRUE.equals(val));
                        }
                        @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                    });
            }

            // Persist toggle changes
            sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (myUid != null) {
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reelMute").child(myUid).child(targetUid).child(key)
                        .setValue(isChecked);
                }
            });

            itemsContainer.addView(row);

            // Divider between rows
            if (i < muteItems.length - 1) {
                android.view.View div = new android.view.View(this);
                div.setBackgroundColor(0xFFF2F2F2);
                android.widget.LinearLayout.LayoutParams divLp =
                    new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1);
                divLp.setMarginStart(padH);
                itemsContainer.addView(div, divLp);
            }
        }

        // Footer note
        android.widget.TextView tvNote = new android.widget.TextView(this);
        tvNote.setText("We won't let them know you muted them.");
        tvNote.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        tvNote.setTextColor(0xFF888888);
        tvNote.setPadding(padH, (int)(12 * dp), padH, (int)(32 * dp));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        android.widget.LinearLayout svContent = new android.widget.LinearLayout(this);
        svContent.setOrientation(android.widget.LinearLayout.VERTICAL);
        svContent.addView(itemsContainer);
        svContent.addView(tvNote);
        sv.addView(svContent);
        root.addView(sv);

        sheet.setContentView(root);
        sheet.show();
    }

    /**
     * Open "About this account" screen — shows date joined, country, former usernames.
     */
    private void openAboutAccount() {
        android.content.Intent i = new android.content.Intent(this, AboutAccountActivity.class);
        i.putExtra(AboutAccountActivity.EXTRA_UID,   targetUid);
        i.putExtra(AboutAccountActivity.EXTRA_NAME,  orEmpty(targetName));
        i.putExtra(AboutAccountActivity.EXTRA_PHOTO, orEmpty(targetPhoto));
        startActivity(i);
    }

    private void updateFollowerCountUI(int delta) {
        if (tvFollowers == null) return;
        try {
            int cur = Integer.parseInt(tvFollowers.getText().toString().split(" ")[0]);
            tvFollowers.setText(String.valueOf(cur + delta));
        } catch (Exception ignored) {}
    }

    // ── Profile data ──────────────────────────────────────────────────────

    // ── Offline-first: Room se naam + photo turant dikhao ──────────────────
    // ── Reels grid offline-first warm-start (advance #6) ─────────────────
    // Room se cached first page turant dikhao — Firebase response se pehle
    // hi grid khaali na dikhe. loadCurrentTab(true) abhi bhi chalega aur
    // fresh data aane par yeh silently replace ho jayega.
    private void loadReelGridFromRoom() {
        if (targetUid == null || targetUid.isEmpty()) return;
        dbExecutor.execute(() -> {
            List<ReelModel> cached = com.callx.app.cache.ReelThumbCacheManager
                    .loadPageBlocking(getApplicationContext(), targetUid, TAB_REELS, PAGE_SIZE);
            if (cached.isEmpty()) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!reelsTabData.isEmpty()) return; // Firebase already won the race — don't clobber
                reelsTabData.addAll(cached);
                if (activeTab == TAB_REELS) {
                    if (isPostsTabActive()) applyFilter(); else adapter.notifyDataSetChanged();
                    refreshEmptyState();
                    updateViewAllButton();
                }
            });
        });
    }

    // Firebase se aaya fresh page → Room mein save + next-screen prefetch cache mein bhi daal do
    private void cacheGridPage(int tab, List<ReelModel> data) {
        if (tab != TAB_REELS) return; // sirf apni "reels" grid ko hi persist karo (liked/saved dusre ke data hain)
        com.callx.app.cache.ReelThumbCacheManager.savePage(getApplicationContext(), targetUid, tab, data);
        com.callx.app.cache.ReelGridPrefetchCache.put(targetUid, tab, data);
    }

    /**
     * Instagram-style "Just watched" grid overlay (see item_saved_reel.xml's
     * view_watched_scrim/tv_just_watched + ReelGridAdapter#setWatchedReelIds).
     *
     * Works on ANY profile's Reels grid (own or someone else's) — the
     * membership check is always against the CURRENT signed-in viewer's own
     * watch history (Firebase reelWatchHistory/{myUid}, written once per
     * reel by ReelSocialController#recordView() whenever THIS viewer
     * actually watches something, on any profile's grid or the main feed).
     *
     * Two-phase load, same "cache first, sync second" shape as the rest of
     * this screen's Room usage:
     *  1) Local reel_watch_history_cache (Room) — full membership set read
     *     off the main thread, applied to the adapter the instant it lands.
     *     No Firebase round-trip needed for the common case (nothing new
     *     watched since last time this screen was open).
     *  2) A SINGLE incremental Firebase read — reelWatchHistory/{myUid}
     *     filtered to entries newer than the latest one already cached
     *     (orderByValue().startAt(cursor)) — picks up anything watched on
     *     another device/session since, merges it into Room, and only then
     *     re-applies to the adapter if it actually added anything new.
     */
    // GAP FIX v2: 7-day window was still way too wide — a real user watching
    // a decent number of reels ended up with most of a profile's grid
    // tagged "Just watched", which isn't what the feature is for. Instagram
    // scopes this to the CURRENT session, so this now uses
    // AppSessionTracker's process-start timestamp (see :core) as the floor
    // instead of a fixed calendar window — a reel watched in a previous app
    // open never shows the badge again, no matter how recently.
    private long recentWatchWindowStart() {
        return com.callx.app.utils.AppSessionTracker.getSessionStartMs();
    }

    private void loadWatchedReelIds() {
        final String myUid = safeMyUid();
        if (myUid == null) return;
        final long windowStart = recentWatchWindowStart();
        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            // Expire anything from before this session before reading, so a
            // stale badge never has the chance to flash even once.
            db.reelWatchHistoryCacheDao().pruneOlderThan(windowStart);
            List<String> cachedIds = db.reelWatchHistoryCacheDao().getRecentReelIds(windowStart);
            Long latestCached = db.reelWatchHistoryCacheDao().getLatestWatchedAt();
            final java.util.Set<String> watchedSet = new java.util.HashSet<>(cachedIds);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                watchedReelIdsCache.clear();
                watchedReelIdsCache.addAll(watchedSet);
                if (adapter != null && !watchedSet.isEmpty()) adapter.setWatchedReelIds(watchedSet);
            });

            // Floor at windowStart (session start), never below it — the
            // pruneOlderThan() call just above wipes anything from before
            // this session, so latestCached is often null right after a
            // fresh app open, and a plain "cursor = 0" fallback would mean
            // re-fetching this viewer's ENTIRE watch history from Firebase
            // on the very first Reels-grid open of every session. Flooring
            // at windowStart keeps the query bounded to "this session only"
            // while still being incremental (skips already-synced ids)
            // across repeated grid opens within the same session.
            long cursor = latestCached != null ? Math.max(latestCached + 1, windowStart) : windowStart;
            Query syncQuery = FirebaseUtils.getReelWatchHistoryRef(myUid).orderByValue().startAt(cursor);
            syncQuery.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!snap.exists()) return;
                    List<ReelWatchHistoryCacheEntity> fresh = new java.util.ArrayList<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        Long ts = child.getValue(Long.class);
                        if (child.getKey() != null) {
                            long watchedAt = ts != null ? ts : 0L;
                            fresh.add(new ReelWatchHistoryCacheEntity(child.getKey(), watchedAt));
                            if (watchedAt >= windowStart) watchedSet.add(child.getKey());
                        }
                    }
                    if (fresh.isEmpty()) return;
                    dbExecutor.execute(() -> {
                        AppDatabase db2 = AppDatabase.getInstance(getApplicationContext());
                        db2.reelWatchHistoryCacheDao().insertAll(fresh);
                        // Heavy-user grid users could accumulate this table
                        // indefinitely otherwise — 2000 reelIds is far more
                        // than any grid will ever scroll through in one
                        // session, so trimming past that costs nothing real.
                        db2.reelWatchHistoryCacheDao().pruneToMax(2000);
                    });
                    runOnUiThread(() -> {
                        if (isFinishing() || isDestroyed()) return;
                        watchedReelIdsCache.clear();
                        watchedReelIdsCache.addAll(watchedSet);
                        if (adapter != null) adapter.setWatchedReelIds(watchedSet);
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
        });
    }

    private void loadFromRoom() {
        if (targetUid == null || targetUid.isEmpty()) return;
        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            UserEntity cached = db.userDao().getUser(targetUid);
            if (cached == null) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // Name
                if (cached.name != null && !cached.name.isEmpty()) {
                    targetName = cached.name;
                    if (tvName != null) tvName.setText(cached.name);
                    if (tvDisplayName != null) tvDisplayName.setText(cached.name);
                }
                // Avatar — thumb fast, fallback full photo
                String url = (cached.thumbUrl != null && !cached.thumbUrl.isEmpty())
                    ? cached.thumbUrl : cached.photoUrl;
                if (url != null && !url.isEmpty() && ivAvatar != null) {
                    targetPhoto = url;
                    Glide.with(UserReelsActivity.this).load(url).circleCrop()
                        .override(240, 240)
                        .placeholder(R.drawable.ic_person).into(ivAvatar);
                }
                // Bio / about
                if (cached.about != null && !cached.about.isEmpty() && tvBio != null) {
                    tvBio.setText(cached.about);
                    tvBio.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    // ── Firebase se aaya data → Room mein save karo ──────────────────────
    // ── Profile-song strip: custom accent color + remove ────────────────────

    /**
     * Applies (or clears) the custom accent color on both strip states —
     * the filled profile-song pill and the empty "Add a song" stub — so
     * whichever one is visible always reflects the user's picked color.
     * Null/empty hex falls back to the default theme-aware bg_song_pill.xml
     * (light) / drawable-night/bg_song_pill.xml (dark) drawables.
     */
    private void applyStripAccentColor(String hex) {
        android.graphics.drawable.Drawable bg;
        if (hex != null && !hex.isEmpty()) {
            try {
                int color = android.graphics.Color.parseColor(hex);
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                gd.setCornerRadius(20f * getResources().getDisplayMetrics().density);
                gd.setStroke(Math.round(1f * getResources().getDisplayMetrics().density), color);
                // Faint tint of the picked color as fill, same "premium hairline
                // pill" feel as the default drawable, just recolored.
                gd.setColor((0x22 << 24) | (color & 0x00FFFFFF));
                bg = gd;
            } catch (Exception e) {
                bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_song_pill);
            }
        } else {
            bg = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_song_pill);
        }
        if (layoutProfileSong  != null) layoutProfileSong.setBackground(bg);
        if (layoutAddSongStub  != null) layoutAddSongStub.setBackground(bg != null ? bg.getConstantState().newDrawable().mutate() : null);
    }

    /**
     * Long-press entry point (isSelf only, both strip states) — opens the
     * shared "common rainbow box" picker (core module, reused from the
     * highlight ring color picker) and persists the chosen color to
     * reels/users/{targetUid}/profileSongStripColor.
     */
    /**
     * "Default Colour" (More menu, isSelf only) — clears every custom accent
     * color on this profile in one go: the bio-chip colors (per-chip AND
     * the legacy shared one), the grid-tab colors (per-tab AND the legacy
     * shared one), and the profile-song strip color. Removes each node from
     * Firebase (reels/users/{targetUid}/...) and re-applies the default,
     * theme-aware styling to the already-visible views immediately, without
     * needing a fresh profile reload.
     */
    private void resetAllColorsToDefault() {
        if (!isSelf || targetUid == null) return;

        // Local state
        profileBioChipColorsMap.clear();
        profileBioStripColorHex = null;
        java.util.Arrays.fill(gridAccentColorsByTab, null);
        legacyGridAccentColorHex = null;
        profileSongStripColorHex = null;

        // Firebase — remove the custom-color nodes so they fall back to
        // default the next time the profile is loaded too.
        com.google.firebase.database.DatabaseReference userRef =
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("reels/users").child(targetUid);
        userRef.child("profileBioChipColors").removeValue();
        userRef.child("profileBioStripColor").removeValue();
        userRef.child("gridAccentColors").removeValue();
        userRef.child("gridAccentColor").removeValue();
        userRef.child("profileSongStripColor").removeValue();

        // Re-render the already-visible views with default styling.
        buildBioChips(lastBioLinks);
        applyGridAccentColorForActiveTab();
        applyStripAccentColor(null);

        Toast.makeText(this, "Colours reset to default", Toast.LENGTH_SHORT).show();
    }

    private void openStripColorPicker() {
        if (!isSelf || targetUid == null) return;
        com.callx.app.utils.RainbowStripColorPickerBottomSheet.show(
                this, "Strip Color", profileSongStripColorHex,
                profileSongStripColorHex != null && !profileSongStripColorHex.isEmpty(),
                colorHex -> {
                    profileSongStripColorHex = colorHex;
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reels/users").child(targetUid)
                        .child("profileSongStripColor")
                        .setValue(colorHex);
                    applyStripAccentColor(colorHex);
                });
    }

    /** Remove-song entry point from the Open/Replace/Remove tap menu (isSelf only). */
    private void removeProfileSong() {
        if (!isSelf || targetUid == null) return;
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(targetUid)
            .child("profileSong")
            .removeValue();
    }

    private void saveToRoom(String name, String photo, String thumb, String bio) {
        if (targetUid == null) return;
        dbExecutor.execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            UserEntity e = db.userDao().getUser(targetUid);
            if (e == null) e = new UserEntity();
            e.uid = targetUid;
            if (name  != null && !name.isEmpty())  e.name     = name;
            if (photo != null && !photo.isEmpty()) e.photoUrl = photo;
            if (thumb != null && !thumb.isEmpty()) e.thumbUrl = thumb;
            if (bio   != null && !bio.isEmpty())   e.about    = bio;
            e.cachedAt = System.currentTimeMillis();
            db.userDao().insertUser(e);
        });
    }

    private void loadUserProfile() {
        // Reels profile load karo (reels/users/{uid}) — chat profile nahi
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String name      = snap.child("displayName").getValue(String.class);
                String photo     = snap.child("photoUrl").getValue(String.class);
                String photoThumb = snap.child("thumbUrl").getValue(String.class);
                String bio       = snap.child("bio").getValue(String.class);
                String website   = snap.child("website").getValue(String.class);
                String instagram = snap.child("instagramHandle").getValue(String.class);
                String youtube   = snap.child("youtubeChannelUrl").getValue(String.class);
                String twitter   = snap.child("twitterHandle").getValue(String.class);

                if (name != null) { targetName = name; if (tvName != null) tvName.setText(name); if (tvDisplayName != null) tvDisplayName.setText(name); }
                if (photo != null && !photo.isEmpty()) {
                    targetPhoto = photo;
                    String displayPhoto = (photoThumb != null && !photoThumb.isEmpty()) ? photoThumb : photo;
                    Glide.with(UserReelsActivity.this).load(displayPhoto).circleCrop()
                        .override(240, 240)
                        .placeholder(R.drawable.ic_person).into(ivAvatar);
                }

                // Bio
                if (tvBio != null) {
                    tvBio.setText(bio != null ? bio : "");
                    tvBio.setVisibility(bio != null && !bio.isEmpty() ? View.VISIBLE : View.GONE);
                }

                // Website / social links from Reels profile — build compact chip row.
                // 4th element = stable "chip type" key (website/instagram/youtube/
                // twitter), used to look up & persist THIS chip's own color —
                // independent of the others and independent of list position.
                java.util.List<String[]> links = new java.util.ArrayList<>();
                if (!isEmpty(website)) {
                    String websiteUrl = website.startsWith("http") ? website : "https://" + website;
                    links.add(new String[]{"📞", website, websiteUrl, "website"});
                }
                if (!isEmpty(instagram)) {
                    String igLabel = instagram.startsWith("@") ? instagram : "@" + instagram;
                    String igUrl = instagram.startsWith("http") ? instagram
                        : "https://instagram.com/" + instagram.replace("@", "");
                    links.add(new String[]{"📷", igLabel, igUrl, "instagram"});
                }
                if (!isEmpty(youtube)) {
                    links.add(new String[]{"▶", youtube, youtube, "youtube"});
                }
                if (!isEmpty(twitter)) {
                    String twLabel = twitter.startsWith("@") ? twitter : "@" + twitter;
                    String twUrl = twitter.startsWith("http") ? twitter
                        : "https://x.com/" + twitter.replace("@", "");
                    links.add(new String[]{"✗", twLabel, twUrl, "twitter"});
                }

                // Legacy single-color value (pre-fix app versions) — used ONLY
                // as a one-time fallback default for chips that don't yet have
                // their own per-type color saved. Never written to again.
                profileBioStripColorHex = snap.child("profileBioStripColor").getValue(String.class);

                // New per-chip color map: reels/users/{uid}/profileBioChipColors/{type}
                profileBioChipColorsMap.clear();
                DataSnapshot chipColorsSnap = snap.child("profileBioChipColors");
                for (DataSnapshot c : chipColorsSnap.getChildren()) {
                    String hex = c.getValue(String.class);
                    if (hex != null && !hex.isEmpty()) profileBioChipColorsMap.put(c.getKey(), hex);
                }

                lastBioLinks = links;
                buildBioChips(links);

                // Grid tabs + thumbnail grid-line accent color — per-tab
                // array (see gridAccentColorsByTab / GRID_TAB_KEY_TO_INDEX).
                // Legacy single-color value (pre-fix app versions) — used ONLY
                // as a one-time fallback default for the Reels tab if it
                // doesn't yet have its own per-tab color saved.
                legacyGridAccentColorHex = snap.child("gridAccentColor").getValue(String.class);
                java.util.Arrays.fill(gridAccentColorsByTab, null);
                DataSnapshot gridColorsSnap = snap.child("gridAccentColors");
                for (DataSnapshot g : gridColorsSnap.getChildren()) {
                    String hex = g.getValue(String.class);
                    Integer idx = GRID_TAB_KEY_TO_INDEX.get(g.getKey());
                    if (hex != null && !hex.isEmpty() && idx != null) gridAccentColorsByTab[idx] = hex;
                }
                // PERF (ultra): defer until the main thread is fully idle —
                // stronger guarantee than post() that this never competes
                // with the first frame (see runWhenMainThreadIdle above).
                runWhenMainThreadIdle(UserReelsActivity.this::applyGridAccentColorForActiveTab);

                // ── Profile song pill ────────────────────────────────────────────
                // Read profileSong from the same snapshot (already loaded)
                DataSnapshot songSnap = snap.child("profileSong");
                String songTitle  = songSnap.child("title").getValue(String.class);
                String songArtist = songSnap.child("artist").getValue(String.class);
                String songId     = songSnap.child("soundId").getValue(String.class);
                String songCover  = songSnap.child("coverUrl").getValue(String.class);
                String songUrl    = songSnap.child("soundUrl").getValue(String.class);
                Long   songDurMs  = songSnap.child("durationMs").getValue(Long.class);

                // Custom strip accent color (long-press picked, shared rainbow box)
                profileSongStripColorHex = snap.child("profileSongStripColor").getValue(String.class);
                applyStripAccentColor(profileSongStripColorHex);

                if (layoutProfileSong != null && songTitle != null && !songTitle.isEmpty()) {
                    // Build display text: "▷ Title - Artist"
                    String displayText = songTitle;
                    if (songArtist != null && !songArtist.isEmpty())
                        displayText = songTitle + "...";
                    tvProfileSongName.setText(displayText);
                    layoutProfileSong.setVisibility(View.VISIBLE);

                    // Capture finals for lambda
                    final String fSongId    = songId != null ? songId : "";
                    final String fSongTitle = songTitle;
                    final String fArtist    = songArtist != null ? songArtist : "";
                    final String fCoverUrl  = songCover  != null ? songCover  : "";
                    final String fSoundUrl  = songUrl    != null ? songUrl    : "";
                    final int    fDurMs     = songDurMs  != null ? songDurMs.intValue() : 0;

                    Runnable openSoundDetail = () -> {
                        // Instagram-style: SoundDetailActivity ka exact content
                        // BottomSheetDialogFragment mein — fullscreen slide-up sheet
                        com.callx.app.music.SoundDetailSheetFragment sheet =
                            com.callx.app.music.SoundDetailSheetFragment.newInstance(
                                fSongId, fSongTitle, fArtist,
                                fCoverUrl, fSoundUrl, fDurMs);
                        sheet.show(UserReelsActivity.this.getSupportFragmentManager(), "sound_detail_full");
                    };

                    if (isSelf) {
                        // isSelf: tap → Open / Replace / Remove menu
                        layoutProfileSong.setOnClickListener(v -> {
                            PopupMenu menu = new PopupMenu(UserReelsActivity.this, v);
                            menu.getMenu().add(0, 1, 0, "Open Song");
                            menu.getMenu().add(0, 2, 1, "Replace Song");
                            menu.getMenu().add(0, 3, 2, "Remove Song");
                            menu.setOnMenuItemClickListener(item -> {
                                if (item.getItemId() == 1) {
                                    openSoundDetail.run();
                                } else if (item.getItemId() == 2) {
                                    startActivity(new Intent(UserReelsActivity.this,
                                        com.callx.app.music.ReelTrendingAudioActivity.class));
                                } else if (item.getItemId() == 3) {
                                    removeProfileSong();
                                }
                                return true;
                            });
                            menu.show();
                        });
                        // Strip color picker moved to the 3-dot menu only
                        // (see setupMoreMenu()) — no longer a long-press here.
                        layoutProfileSong.setOnLongClickListener(null);
                    } else {
                        layoutProfileSong.setOnClickListener(v -> openSoundDetail.run());
                        layoutProfileSong.setOnLongClickListener(null);
                    }
                } else {
                    // No song set
                    if (layoutProfileSong  != null) layoutProfileSong.setVisibility(View.GONE);
                    // isSelf → show "Add a song" stub pill
                    if (layoutAddSongStub != null) {
                        layoutAddSongStub.setVisibility(isSelf ? View.VISIBLE : View.GONE);
                        if (isSelf) {
                            layoutAddSongStub.setOnClickListener(v -> {
                                // Open Trending Audio so user can pick & add a song
                                Intent i = new Intent(UserReelsActivity.this,
                                    com.callx.app.music.ReelTrendingAudioActivity.class);
                                startActivity(i);
                            });
                            // Strip color picker moved to the 3-dot menu only.
                            layoutAddSongStub.setOnLongClickListener(null);
                        }
                    }
                }


                // FIX: Room mein save karo — next time offline instantly dikhega
                saveToRoom(name, photo, photoThumb, bio);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
        // Save to Room after Firebase load — next time offline kaam aayega
        // (called inside onDataChange above via saveToRoom)
        FirebaseUtils.getReelFollowersRef(targetUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (tvFollowers != null) tvFollowers.setText(String.valueOf(snap.getChildrenCount()));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
        FirebaseUtils.getReelFollowsRef(targetUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (tvFollowing != null) tvFollowing.setText(String.valueOf(snap.getChildrenCount()));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }


    // ── Bio chip row ─────────────────────────────────────────────────────

    /**
     * Resolve a theme attribute (e.g. android.R.attr.textColorPrimary or
     * a custom ?attr/colorOnSurface) to its actual color for the current
     * light/dark theme, so programmatically-built views stay theme-aware.
     */
    private int resolveAttrColor(int attrResId, int fallback) {
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(attrResId, tv, true)) {
            if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            } else if (tv.resourceId != 0) {
                try { return androidx.core.content.ContextCompat.getColor(this, tv.resourceId); }
                catch (Exception ignored) {}
            }
        }
        return fallback;
    }

    /**
     * Build compact chip row in hsv_bio_links (Screenshot 2 style).
     * Each chip: thin premium pill (theme-aware bg_bio_chip.xml /
     * drawable-night/bg_bio_chip.xml) with icon + label, all in one
     * scrollable row. isSelf → long-press an INDIVIDUAL chip opens the
     * shared rainbow color picker to recolor JUST that chip (each chip
     * keeps its own color, tracked by its stable "type" key); others still
     * get a copy-link long-press.
     * @param links list of {iconEmoji, displayLabel, clickUrl, chipTypeKey}
     */
    private void buildBioChips(java.util.List<String[]> links) {
        if (llBioChips == null || hsvBioLinks == null) return;
        llBioChips.removeAllViews();
        if (links.isEmpty()) {
            hsvBioLinks.setVisibility(View.GONE);
            return;
        }
        hsvBioLinks.setVisibility(View.VISIBLE);
        float density = getResources().getDisplayMetrics().density;
        // Thin, premium pill — matches the profile-song strip's compact feel.
        int hPad  = (int)(10 * density);
        int vPad  = (int)(5  * density);
        int mEnd  = (int)(8  * density);
        int textColor = resolveAttrColor(android.R.attr.textColorPrimary, 0xFF222222);

        for (String[] link : links) {
            String emoji   = link[0];
            String label   = link[1];
            String url     = link[2];
            String typeKey = link[3];

            android.widget.TextView chip = new android.widget.TextView(this);
            android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(mEnd);
            chip.setLayoutParams(lp);
            chip.setPadding(hPad, vPad, hPad, vPad);
            chip.setText(emoji + "  " + label);
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
            chip.setTextColor(textColor);
            chip.setSingleLine(true);
            chip.setMaxEms(12);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);

            // Starting background — this chip's OWN saved color if it has
            // one, else the legacy single color (one-time fallback for
            // upgrading users), else the default theme-aware pill.
            String initialHex = profileBioChipColorsMap.get(typeKey);
            if (initialHex == null) initialHex = profileBioStripColorHex;
            chip.setBackground(buildChipDrawable(initialHex));
            chip.setClickable(true);
            chip.setFocusable(true);

            if (url != null && !url.isEmpty()) {
                chip.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } catch (Exception ex) {
                        Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            chip.setTag(typeKey);
            if (isSelf) {
                // Strip color picker moved to the 3-dot menu only
                // (see setupMoreMenu() → openBioStripColorMenuEntry()).
                chip.setOnLongClickListener(null);
            } else if (url != null && !url.isEmpty()) {
                // Viewer: long-press still copies the link.
                chip.setOnLongClickListener(v -> {
                    android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("copy", url));
                    Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
            llBioChips.addView(chip);
        }
    }

    /**
     * Builds the pill background drawable for a single chip from a hex
     * color (or the default theme-aware bg_bio_chip.xml when hex is
     * null/empty/invalid). Always returns a fresh mutable drawable instance
     * — never shared between chips — so recoloring one chip can never leak
     * onto another.
     */
    private android.graphics.drawable.Drawable buildChipDrawable(String hex) {
        if (hex != null && !hex.isEmpty()) {
            try {
                int color = android.graphics.Color.parseColor(hex);
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
                gd.setCornerRadius(16f * getResources().getDisplayMetrics().density);
                gd.setStroke(Math.round(1f * getResources().getDisplayMetrics().density), color);
                gd.setColor((0x22 << 24) | (color & 0x00FFFFFF));
                return gd;
            } catch (Exception e) {
                // fall through to default below
            }
        }
        android.graphics.drawable.Drawable def =
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_bio_chip);
        return def != null ? def.getConstantState().newDrawable().mutate() : null;
    }

    /**
     * Applies a color to exactly ONE chip view (identified by the view
     * itself, already resolved from the long-press callback) — sibling
     * chips are never touched.
     */
    private void applyChipAccentColor(android.widget.TextView chip, String hex) {
        if (chip == null) return;
        chip.setBackground(buildChipDrawable(hex));
    }

    /**
     * Long-press entry point (isSelf only) — opens the shared rainbow color
     * picker (core module) pre-filled with THIS chip's current color, and
     * on pick, persists it under this chip's own type key
     * (reels/users/{targetUid}/profileBioChipColors/{typeKey}) and recolors
     * ONLY this one chip. Other chips keep whatever color (or default)
     * they already had.
     */
    private void openBioStripColorPicker(String typeKey, android.widget.TextView chipView) {
        if (!isSelf || targetUid == null) return;
        String currentHex = profileBioChipColorsMap.get(typeKey);
        com.callx.app.utils.RainbowStripColorPickerBottomSheet.show(
                this, "Bio Strip Color", currentHex,
                currentHex != null && !currentHex.isEmpty(),
                true, "bio strips",
                (colorHex, applyToAll) -> {
                    if (applyToAll) {
                        applyBioStripColorToAll(colorHex);
                        return;
                    }
                    profileBioChipColorsMap.put(typeKey, colorHex);
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("reels/users").child(targetUid)
                        .child("profileBioChipColors").child(typeKey)
                        .setValue(colorHex);
                    applyChipAccentColor(chipView, colorHex);
                });
    }

    /**
     * Applies one color to EVERY visible bio chip at once (the "Apply to
     * all bio strips" checkbox) — sets the shared fallback color and clears
     * every chip's individual override so they all pick it up.
     */
    private void applyBioStripColorToAll(String hex) {
        if (targetUid == null) return;
        profileBioStripColorHex = hex;
        profileBioChipColorsMap.clear();
        if (llBioChips != null) {
            for (int i = 0; i < llBioChips.getChildCount(); i++) {
                android.view.View child = llBioChips.getChildAt(i);
                if (child instanceof android.widget.TextView) {
                    applyChipAccentColor((android.widget.TextView) child, hex);
                }
            }
        }
        com.google.firebase.database.DatabaseReference userRef =
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("reels/users").child(targetUid);
        if (hex != null) userRef.child("profileBioStripColor").setValue(hex);
        else userRef.child("profileBioStripColor").removeValue();
        userRef.child("profileBioChipColors").removeValue(); // clear all per-chip overrides
    }

    /**
     * Entry point from the 3-dot menu ("🌈 Bio Strip Color") — the direct
     * long-press-per-chip trigger was removed, so if there's more than one
     * bio chip currently shown, ask which one to color first (with "Apply
     * to all" still available inside that picker).
     */
    private void openBioStripColorMenuEntry() {
        if (llBioChips == null || llBioChips.getChildCount() == 0) {
            Toast.makeText(this, "No bio strips to color yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (llBioChips.getChildCount() == 1) {
            android.view.View only = llBioChips.getChildAt(0);
            if (only instanceof android.widget.TextView && only.getTag() instanceof String) {
                openBioStripColorPicker((String) only.getTag(), (android.widget.TextView) only);
            }
            return;
        }
        List<String> labels = new ArrayList<>();
        List<android.widget.TextView> chipViews = new ArrayList<>();
        for (int i = 0; i < llBioChips.getChildCount(); i++) {
            android.view.View c = llBioChips.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                labels.add(((android.widget.TextView) c).getText().toString());
                chipViews.add((android.widget.TextView) c);
            }
        }
        AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
            .setTitle("Pick a bio strip to color")
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                android.widget.TextView chosen = chipViews.get(which);
                String typeKey = (String) chosen.getTag();
                openBioStripColorPicker(typeKey, chosen);
            })
            .setNegativeButton("Cancel", null)
            .create());
    }

    // ── Social link helper ──────────────────────────────────────────────
    private boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }

    private void bindSocialRow(View rowLayout, TextView tv, String rawValue, String url, String displayText) {
        if (rowLayout == null || tv == null) return;
        if (isEmpty(rawValue)) {
            rowLayout.setVisibility(View.GONE);
            return;
        }
        rowLayout.setVisibility(View.VISIBLE);
        tv.setText(displayText != null ? displayText : rawValue);
        rowLayout.setOnClickListener(v -> {
            if (url == null) return;
            try {
                android.net.Uri uri = android.net.Uri.parse(url);
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(UserReelsActivity.this, "Cannot open link", Toast.LENGTH_SHORT).show();
            }
        });
        rowLayout.setOnLongClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("copy", rawValue));
            Toast.makeText(UserReelsActivity.this, "Copied!", Toast.LENGTH_SHORT).show();
            return true;
        });
    }


    /**
     * ✅ Instagram approach: persistent ValueEventListener so the reel count
     * updates in real-time whenever a reel is added/removed — no manual
     * re-query needed after upload. Listener is removed in onDestroy.
     */
    private void loadReelCount() {
        if (reelCountLiveListener != null) return; // already attached
        DatabaseReference ref = FirebaseUtils.getReelsByUserRef(targetUid);
        reelCountLiveListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (tvReelCount != null)
                    tvReelCount.setText(String.valueOf(snap.getChildrenCount()));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        ref.addValueEventListener(reelCountLiveListener);
    }

    // ── More menu ─────────────────────────────────────────────────────────

    private void setupMoreMenu() {
        if (btnMore == null) return;
        btnMore.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(this, btnMore);
            menu.getMenu().add(0, 1, 0, "Share Profile");
            menu.getMenu().add(0, 2, 0, "Copy Profile Link");
            // ULTRA: multi-select entry point moved here from long-press
            // (long-press is now ONLY the mini-player peek — see
            // onLongPress()). Hidden on the Series tab (no multi-select
            // support there) and when the current grid is empty.
            boolean canSelect = activeTab != TAB_SERIES && !activeTabData().isEmpty();
            if (canSelect) menu.getMenu().add(0, 15, 0, "☑️ Select");
            // Liked/Saved moved out of the tab strip (which now shows just
            // Reels/Repost/Duet, Instagram-style) and into here — opens
            // straight into AllReelsFullActivity instead of switching this
            // screen's grid.
            menu.getMenu().add(0, 10, 0, "❤️ Liked Reels");
            menu.getMenu().add(0, 11, 0, "🔖 Saved Reels");
            if (isSelf)  menu.getMenu().add(0, 5, 0, "Creator Dashboard");
            if (isSelf && pinnedReel != null) menu.getMenu().add(0, 4, 0, "Remove Pinned Reel");
            boolean dockedEnabled = com.callx.app.docked.DockedPlayerSettings.isEnabled(this);
            if (isSelf) menu.getMenu().add(0, 8, 0,
                dockedEnabled ? "Docked Reel Player: ON (tap to turn off)"
                              : "Docked Reel Player: OFF (tap to turn on)");
            if (isSelf)  menu.getMenu().add(0, 6, 0, "🗑️ Delete All Reels");
            // Color-customization entry points — previously triggered by
            // long-pressing the grid tab / song strip / bio chips directly;
            // now only reachable from here (single gated entry point).
            if (isSelf)  menu.getMenu().add(0, 12, 0, "🎨 Grid Color");
            if (isSelf)  menu.getMenu().add(0, 13, 0, "🎵 Song Strip Color");
            if (isSelf)  menu.getMenu().add(0, 14, 0, "🌈 Bio Strip Color");
            if (isSelf)  menu.getMenu().add(0, 9, 0, "Default Colour");
            if (!isSelf) menu.getMenu().add(0, 3, 0, "Report User");
            if (!isSelf) menu.getMenu().add(0, 7, 0, "About this account");
            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: shareProfile(); break;
                    case 2:
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Link",
                            com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/profile/" + targetUid));
                        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show(); break;
                    case 3: Toast.makeText(this, "Report submitted. Thank you.", Toast.LENGTH_SHORT).show(); break;
                    case 4: unpinReel(); break;
                    case 5: startActivity(new Intent(this, ReelCreatorDashboardActivity.class)); break;
                    case 6: deleteAllReels(); break;
                    case 7: openAboutAccount(); break;
                    case 9: resetAllColorsToDefault(); break;
                    case 15: enterMultiSelectMode(-1); break;
                    case 12: openGridAccentColorPicker(activeTab); break;
                    case 13: openStripColorPicker(); break;
                    case 14: openBioStripColorMenuEntry(); break;
                    case 10: openLikedOrSavedFullScreen(1); break; // AllReelsFullActivity.TAB_LIKED
                    case 11: openLikedOrSavedFullScreen(2); break; // AllReelsFullActivity.TAB_SAVED
                    case 8: {
                        boolean newState = !com.callx.app.docked.DockedPlayerSettings.isEnabled(this);
                        com.callx.app.docked.DockedPlayerSettings.setEnabled(this, newState);
                        Toast.makeText(this, newState
                            ? "Docked reel player turned ON"
                            : "Docked reel player turned OFF", Toast.LENGTH_SHORT).show();
                        break;
                    }
                }
                return true;
            });
            menu.show();
        });
    }

    /** Opens Liked/Saved reels full-screen (they're no longer tabs on this screen). */
    private void openLikedOrSavedFullScreen(int allReelsFullActivityTabConstant) {
        Intent i = new Intent(this, AllReelsFullActivity.class);
        i.putExtra(AllReelsFullActivity.EXTRA_UID,   targetUid);
        i.putExtra(AllReelsFullActivity.EXTRA_NAME,  targetName  != null ? targetName  : "");
        i.putExtra(AllReelsFullActivity.EXTRA_PHOTO, targetPhoto != null ? targetPhoto : "");
        i.putExtra(AllReelsFullActivity.EXTRA_TAB,   allReelsFullActivityTabConstant);
        startActivity(i);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }

    private String orEmpty(String s) { return s != null ? s : ""; }

    // ── Avatar zoom dialog ────────────────────────────────────────────────

    private void showAvatarZoom(View sourceView, String photoUrl, String name) {
        if (isFinishing() || isDestroyed()) return;
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            this, sourceView, photoUrl, name, R.drawable.ic_person, R.drawable.ic_close);
    }

    @Override protected void onPause()  { super.onPause();  dismissPreviewDialog(); stopAvatarAnimation(); if (lottieEmpty != null) lottieEmpty.pauseAnimation(); }

    /**
     * ✅ Instagram approach: on every resume AFTER the first (i.e. returning
     * from upload, camera, player, settings…), silently check if new reels
     * were uploaded and prepend them to the grid — no skeleton flash, no full
     * reload. The persistent count listener already keeps tvReelCount live.
     */
    @Override
    protected void onResume() {
        super.onResume();
        loadAvatarAndStartAnimation();
        if (lottieEmpty != null && layoutEmpty != null
                && layoutEmpty.getVisibility() == View.VISIBLE && !emptyLottieFailed) {
            lottieEmpty.resumeAnimation();
        }
        if (!isFirstResume && isSelf && activeTab == TAB_REELS) {
            // Throttled: onResume can fire many times in quick succession
            // (returning from a dialog, switching apps, back-press) and each
            // call was hitting Firebase again even with nothing new to fetch.
            long now = System.currentTimeMillis();
            if (now - lastSilentRefreshAtMs >= SILENT_REFRESH_MIN_INTERVAL_MS) {
                lastSilentRefreshAtMs = now;
                silentRefreshReels();
            }
        }
        if (!isFirstResume && isSelf) {
            // Picks up any album just created/added-to via CreateHighlightActivity.
            loadHighlights();
        }
        isFirstResume = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissPreviewDialog();
        stopAvatarAnimation();
        cancelStoryRingReveal();
        retryHandler.removeCallbacksAndMessages(null);
        if (lottieEmpty != null) lottieEmpty.cancelAnimation();
        dbExecutor.shutdown();
        // Remove persistent Firebase listeners to avoid memory/network leaks
        if (reelCountLiveListener != null && targetUid != null) {
            try { FirebaseUtils.getReelsByUserRef(targetUid)
                      .removeEventListener(reelCountLiveListener); } catch (Exception ignored) {}
            reelCountLiveListener = null;
        }
    }

    // ── Silent grid refresh (called from onResume for self) ───────────────

    /**
     * Fetches the latest page of the user's own reels and prepends any IDs
     * not already in {@code reelsTabData}. Does NOT show the skeleton, does NOT
     * reset pagination state — existing items stay put. Only genuinely new
     * reels are inserted at position 0 (or 1 if a reel is pinned).
     *
     * This is how Instagram handles returning to the profile after an upload:
     * the grid is augmented at the top, not wiped and reloaded.
     */
    private void silentRefreshReels() {
        if (isLoadingMore || targetUid == null) return;
        isLoadingMore = true;

        FirebaseUtils.getReelsByUserRef(targetUid)
            .orderByKey()
            .limitToLast(PAGE_SIZE)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (isFinishing() || isDestroyed()) { isLoadingMore = false; return; }

                    List<String> freshIds = extractIds(snap); // newest-first

                    // Build set of already-known reel IDs
                    Set<String> knownIds = new HashSet<>();
                    for (ReelModel r : reelsTabData)
                        if (r != null && r.reelId != null) knownIds.add(r.reelId);

                    // Keep only IDs we haven't loaded yet
                    List<String> newIds = new ArrayList<>();
                    for (String id : freshIds)
                        if (!knownIds.contains(id)) newIds.add(id);

                    if (newIds.isEmpty()) { isLoadingMore = false; return; }

                    // Batch-fetch full ReelModels for all new IDs as a single fan-out/join
                    // instead of N independent addListenerForSingleValueEvent callbacks.
                    //
                    // NOTE (root cause): Realtime Database has no native multi-get across
                    // arbitrary non-contiguous keys (unlike Firestore's getAll(docRefs)), and
                    // reelsByUser/{uid}/{reelId} only stores a boolean marker — not enough
                    // data to render the grid — so a single query can't replace this fetch
                    // without denormalizing full reel data into reelsByUser at write time
                    // (touches ReelUploadActivity, CollabPostAcceptActivity,
                    // CollabRepostAcceptActivity, ReelNotificationActionReceiver + a data
                    // migration for existing reelsByUser records). That's a real fix worth
                    // doing, but out of scope here. Tasks.whenAllComplete() below removes the
                    // fragile shared-counter callback pyramid and gives one safe join point
                    // with per-item failure isolation — same number of reads, no race risk.
                    List<Task<DataSnapshot>> tasks = new ArrayList<>(newIds.size());
                    for (String id : newIds) {
                        tasks.add(FirebaseUtils.getReelsRef().child(id).get());
                    }

                    Tasks.whenAllComplete(tasks).addOnCompleteListener(ignored -> {
                        isLoadingMore = false;
                        if (isFinishing() || isDestroyed()) return;

                        List<ReelModel> fetched = new ArrayList<>();
                        for (Task<DataSnapshot> t : tasks) {
                            if (!t.isSuccessful()) continue; // isolate per-item failure, don't drop the whole batch
                            DataSnapshot s = t.getResult();
                            if (s == null) continue;
                            ReelModel r = s.getValue(ReelModel.class);
                            if (r != null) fetched.add(r);
                        }
                        if (fetched.isEmpty()) return;

                        // Sort newest-first then prepend
                        fetched.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                        reelsTabData.addAll(0, fetched);
                        if (activeTab == TAB_REELS && adapter != null) {
                            if (isPostsTabActive()) {
                                // Displayed list here is a filtered copy, not
                                // reelsTabData itself — a raw positional
                                // insert would be against the wrong indices.
                                applyFilter();
                            } else {
                                int basePos = adapter.hasPinned() ? 1 : 0;
                                adapter.notifyItemRangeInserted(basePos, fetched.size());
                                // Scroll to top so new reel is immediately visible
                                if (rvReels != null)
                                    rvReels.post(() -> rvReels.smoothScrollToPosition(0));
                            }
                        }
                        refreshEmptyState();
                    });
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) { isLoadingMore = false; }
            });
    }
}
