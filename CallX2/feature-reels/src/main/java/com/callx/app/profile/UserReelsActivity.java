package com.callx.app.profile;
import com.callx.app.utils.AlertDialogStyler;

import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.followers.FollowersListActivity;
import com.callx.app.followers.FollowingListActivity;
import com.callx.app.followers.MutualFollowersActivity;

import android.animation.ObjectAnimator;
import android.app.Dialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import com.google.android.material.appbar.AppBarLayout;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.view.ViewCompat;
import com.google.android.material.tabs.TabLayout;

import com.bumptech.glide.Glide;
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
import de.hdodenhof.circleimageview.CircleImageView;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.UserEntity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

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

    private static final int PAGE_SIZE  = 6;
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

    // Visible tab STRIP position (0..4 — what tab.getPosition() returns for
    // the 5 tabs: Reels, Repost, Duet, Collab Repost, Series) → internal data
    // constant above. Needed because the strip no longer has tabs in constant
    // order, so position and data-constant are no longer the same number.
    private static final int[] VISIBLE_TAB_DATA =
            { TAB_REELS, TAB_REPOST, TAB_DUET, TAB_COLLAB_REPOST, TAB_SERIES };
    // Current tab strip position (0..2) — separate from `activeTab`, which
    // holds the DATA constant (0/3/4). Used to compute swipe-left/right's
    // next/previous tab.
    private int activeTabPosition = 0;

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
    private View            layoutSongPillBubble;
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
    // through the 1dp item gaps (see WhiteGridDecoration).
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

    // ── Avatar peek animation fields ──────────────────────────────────────
    private CircleImageView ivAnimChat, ivAnimX, ivAnimYoutube;
    private final Handler   animHandler    = new Handler(Looper.getMainLooper());
    private Runnable        animRunnable;
    private boolean         animRunning    = false;
    private TabLayout       tabLayout;
    private RecyclerView    rvReels;
      private RecyclerView    rvSeries;
    private ReelGridAdapter       adapter;
      private UserSeriesGridAdapter seriesAdapter;
    private ProgressBar     progressBar;
    private View            layoutEmpty;
    private View            layoutMultiSelectBar;
    private TextView        tvSelectedCount;
    private ImageButton     btnShareSelected, btnDeleteSelected, btnCancelSelect, btnDeleteAll;
    private View            layoutPrivateAccount;
    private View            btnViewAllReels;
    private View            layoutFollowersClick;
    private View            layoutFollowingClick;
    private View            btnRepostSection;
    private View            btnSeriesSection;
    private com.google.android.material.appbar.AppBarLayout appBarLayout;
    // Lets the plain (non-scrollable) layoutEmpty view participate in the same
    // CoordinatorLayout nested-scroll chain that rvReels uses natively — see
    // setupSwipeBetweenTabs() / the touch listener below for why this is needed.
    private androidx.core.view.NestedScrollingChildHelper emptyStateScrollHelper;

      // ── Filter chips state ─────────────────────────────────────────────
      private static final int FILTER_ALL    = 0;
      private static final int FILTER_OLDEST = 1; // sorted by timestamp ascending
      private static final int FILTER_NEWEST = 2; // sorted by timestamp descending
      private static final int FILTER_VIEWED = 3; // most viewed
      private int              activeFilter  = FILTER_ALL;
      private android.widget.HorizontalScrollView hsvFilterChips;
      private android.widget.LinearLayout         llFilterChips;

    // State
    private String  targetUid, targetName, targetPhoto;
    // Offline-first Room executor
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
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

    // ── Realtime update helpers (self only) ───────────────────────────────
    /** Skip the silent grid refresh on the very first onResume (right after onCreate). */
    private boolean isFirstResume = true;
    /** Persistent count listener — auto-updates tvReelCount whenever a reel is added/removed. */
    private ValueEventListener reelCountLiveListener = null;

    private ReelModel         pinnedReel = null;
    private Dialog            previewDialog;
    private ExoPlayer         previewPlayer;
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
        // FIX v39: seamless gradient ring — replaces bg_story_ring.xml's 3-stop
        // XML sweep gradient which had a visible seam (see
        // StoryRingGradientDrawable doc for details).
        if (viewStoryRing != null) {
            viewStoryRing.setBackground(
                    com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(3f,
                            getResources().getDisplayMetrics().density));
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
        hsvBioLinks       = findViewById(R.id.hsv_bio_links);
        llBioChips        = findViewById(R.id.ll_bio_chips);
        layoutProfileSong = findViewById(R.id.layout_profile_song);
        layoutSongPillBubble = findViewById(R.id.layout_song_pill_bubble);
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
            pos -> { if (isMultiSelect) toggleSelection(pos); else openPlayerAt(pos); },
            this, this
        );

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
                return adapter.getItemViewType(position) == ReelGridAdapter.TYPE_PINNED ? 3 : 1;
            }
        });

        rvReels.setLayoutManager(gridLayoutManager);
        rvReels.setAdapter(adapter);
        rvReels.addItemDecoration(new ReelGridAdapter.WhiteGridDecoration(this));
        // KEY FIX: RecyclerView must NOT have nested scrolling disabled.
        // It lives directly inside a plain FrameLayout (no SwipeRefreshLayout,
        // no NestedScrollView wrapper), so it scrolls normally on its own and
        // drives AppBarLayout's collapse natively via nested scrolling.
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
        setupSwipeBetweenTabs();

        // Instagram-style CTA buttons visible only for other users
        if (layoutInstagramCta  != null) layoutInstagramCta.setVisibility(isSelf ? View.GONE : View.VISIBLE);
        if (layoutExtraActions  != null) layoutExtraActions.setVisibility(isSelf ? View.GONE : View.VISIBLE);
        if (layoutActions       != null) layoutActions.setVisibility(View.GONE); // legacy bar hidden
        if (btnFollow           != null) btnFollow.setVisibility(isSelf ? View.GONE : View.VISIBLE);

        setupActionButtons();
        setupMoreMenu();

        if (ivAvatar != null) {
            ivAvatar.setOnClickListener(v -> openStatusIfAvailable());
            ivAvatar.setOnLongClickListener(v -> { showAvatarZoom(targetPhoto, targetName); return true; });
        }
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

                  if (isLoadingMore) return;
                  if (!getCurrentTabHasMore()) return;
                  int total       = gridLayoutManager.getItemCount();
                  int lastVisible = gridLayoutManager.findLastVisibleItemPosition();
                  if (lastVisible >= total - 6) {
                      loadCurrentTab(false);
                  }
              }
          });
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

          String[] labels = {"All", "Oldest", "Newest", "Most viewed"};
          int[]    filters = {FILTER_ALL, FILTER_OLDEST, FILTER_NEWEST, FILTER_VIEWED};

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
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                activeTabPosition = tab.getPosition();
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
                if (!isSeries) adapter.setDataList(activeTabData());
                if (isSeries ? seriesTabData.isEmpty() : activeTabData().isEmpty()) loadCurrentTab(true);
                else { refreshEmptyState(); updateViewAllButton(); }
                // Each tab keeps its OWN accent color — re-apply whichever
                // color (or default) belongs to the tab we just switched to.
                applyGridAccentColorForActiveTab();
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
     *    actually shows through the 1dp gaps WhiteGridDecoration leaves
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

    // ── Swipe left/right on the grid to switch tabs (mirrors Reels/Repost/Duet) ──

    private void switchToTab(int newPos) {
        if (newPos < 0 || newPos >= VISIBLE_TAB_DATA.length) return; // out of range — no-op at edges
        if (tabLayout == null) return;
        TabLayout.Tab t = tabLayout.getTabAt(newPos);
        if (t != null) t.select();
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

    /** Normal steady state: fixed seamless gradient ring, no animation at all. */
    private void showStoryRingStatic() {
        if (isFinishing() || isDestroyed() || viewStoryRing == null) return;
        viewStoryRing.setBackground(
                com.callx.app.utils.StoryRingGradientDrawable.withStrokeDp(3f,
                        getResources().getDisplayMetrics().density));
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

    private void openStatusIfAvailable() {
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
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
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

                    highlightAlbums.add(new HighlightsRowAdapter.HighlightAlbum(
                            albumId, albumName, coverUrl, coverBgColor, itemCount));
                }

                runOnUiThread(() -> {
                    boolean hasContent = !highlightAlbums.isEmpty() || isSelf;
                    if (hsvHighlights  != null) hsvHighlights.setVisibility(hasContent ? android.view.View.VISIBLE : android.view.View.GONE);
                    if (dividerHighlights != null) dividerHighlights.setVisibility(hasContent ? android.view.View.VISIBLE : android.view.View.GONE);
                    rebuildHighlightsAdapter();
                });

                loadHighlightRingOverrides();
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
                    if (changed) runOnUiThread(() -> rebuildHighlightsAdapter());
                }
                @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) { }
            });
    }

    /**
     * Open StatusViewerActivity for a specific highlight album.
     * Uses Class.forName to avoid hard cross-module dependency.
     */
    private void openHighlightAlbum(HighlightsRowAdapter.HighlightAlbum album) {
        try {
            Class<?> cls = Class.forName("com.callx.app.viewer.StatusViewerActivity");
            android.content.Intent i = new android.content.Intent(this, cls);
            i.putExtra("ownerUid",  targetUid);
            i.putExtra("ownerName", album.albumName);
            i.putExtra("highlightAlbumId", album.albumId);
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
     * Long-press context sheet for self user: rename album, delete album.
     */
    private void showHighlightManageSheet(HighlightsRowAdapter.HighlightAlbum album, int adapterPos) {
        if (isFinishing() || isDestroyed()) return;

        AlertDialogStyler.showRounded(new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(album.albumName)
            .setItems(new String[]{"✏  Rename album", "🗑  Delete album"}, (d, which) -> {
                if (which == 0) showHighlightRenameDialog(album, adapterPos);
                else            confirmDeleteHighlight(album, adapterPos);
            })
            .setNegativeButton("Cancel", null)
            .create());
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
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_REELS); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) reelsLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, reelsTabData, refresh, TAB_REELS);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_REELS); }
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
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_LIKED); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) likedLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, likedTabData, refresh, TAB_LIKED);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_LIKED); }
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
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_SAVED); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) savedLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, savedTabData, refresh, TAB_SAVED);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_SAVED); }
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
                              layoutEmpty.setVisibility(View.VISIBLE);
                          }
                          seriesLoaded = true;
                          return;
                      }
                      if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);

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
                                              layoutEmpty.setVisibility(fetched.isEmpty() ? View.VISIBLE : View.GONE);
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
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_REPOST); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) repostsLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, repostsTabData, refresh, TAB_REPOST);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_REPOST); }
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
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_DUET); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) duetLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, duetTabData, refresh, TAB_DUET);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_DUET); }
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
                if (snap.getChildrenCount() == 0) { finishLoading(refresh, TAB_COLLAB_REPOST); return; }
                List<String> ids = extractIds(snap);
                if (!ids.isEmpty()) collabRepostLastKey = ids.get(ids.size() - 1);
                fetchAndAppend(ids, collabRepostTabData, refresh, TAB_COLLAB_REPOST);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { finishLoading(refresh, TAB_COLLAB_REPOST); }
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
        adapter.setSkeletonMode(true);
        adapter.notifyDataSetChanged();
        if (layoutEmpty != null) layoutEmpty.setVisibility(View.GONE);
    }

    private void fetchAndAppend(List<String> ids, List<ReelModel> target,
                                boolean refresh, int tab) {
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
                            target.addAll(fetched);
                            finishLoading(refresh, tab, insertStart, fetched.size());
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
          updateViewAllButton();
      }

      private void updateViewAllButton() {
          if (btnViewAllReels == null) return;
          List<ReelModel> data = activeTabData();
          // Show "View All" if we loaded a full page (likely more exist) or if any data exists
          boolean show = !data.isEmpty();
          btnViewAllReels.setVisibility(show ? View.VISIBLE : View.GONE);
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
        adapter.setSkeletonMode(false);
        if (tab == activeTab) {
            if (refresh) {
                adapter.notifyDataSetChanged();
            } else if (insertCount > 0) {
                adapter.notifyItemRangeInserted(insertStart, insertCount);
            }
        }
        if (progressBar  != null) progressBar.setVisibility(View.GONE);
        if (tab == activeTab) { refreshEmptyState(); updateViewAllButton(); }
        List<ReelModel> tabData = dataForTab(tab);
        if (!tabData.isEmpty()) cacheGridPage(tab, tabData);
    }

    private void refreshEmptyState() {
        if (activeTab == TAB_SERIES) return; // series tab manages own empty state
        boolean empty = activeTabData().isEmpty() && !adapter.hasPinned();
        if (layoutEmpty != null) layoutEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (rvReels != null) rvReels.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (tvEmptyTitle == null) return;
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

    // ── Open player ───────────────────────────────────────────────────────

    private void openPlayerAt(int adapterPos) {
        // Pinned reel occupies position 0 in adapter — skip it when calculating reel index
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;
        if (reelIdx < 0) reelIdx = 0;

        List<ReelModel> data = activeTabData();
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
        startActivity(intent);
    }

    // ── Long press ────────────────────────────────────────────────────────

    @Override
    public void onLongPress(int adapterPos) {
        List<ReelModel> data = activeTabData();
        int reelIdx = adapter.hasPinned() ? adapterPos - 1 : adapterPos;

        // Long-pressing the pinned reel tile (adapter position 0) has no
        // matching entry in `data` (reelIdx would be -1), so it used to
        // fall through to enterMultiSelectMode() instead of offering an
        // unpin option. Route it to the same options sheet directly.
        if (isSelf && activeTab == TAB_REELS && adapter.hasPinned() && adapterPos == 0 && pinnedReel != null) {
            showAnalyticsSheet(pinnedReel, adapterPos);
            return;
        }
        if (isSelf && activeTab == TAB_REELS && reelIdx >= 0 && reelIdx < data.size()) {
            showAnalyticsSheet(data.get(reelIdx), adapterPos);
            return;
        }
        if (reelIdx < 0 || reelIdx >= data.size()) { enterMultiSelectMode(adapterPos); return; }
        ReelModel reel = data.get(reelIdx);
        if (reel.videoUrl == null || reel.videoUrl.isEmpty()) { enterMultiSelectMode(adapterPos); return; }
        showVideoPreviewDialog(reel, adapterPos);
    }

    // ── Analytics sheet (Feature 15) ──────────────────────────────────────

    private void showAnalyticsSheet(ReelModel reel, int adapterPos) {
        boolean isPinned = pinnedReel != null && reel.reelId != null && reel.reelId.equals(pinnedReel.reelId);
        AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
            .setTitle("Reel Options")
            .setItems(new String[]{"View Insights", isPinned ? "Unpin Reel" : "Pin Reel", "Share", "Delete"}, (d, which) -> {
                switch (which) {
                    case 0:
                        ReelAnalyticsBottomSheet.newInstance(reel)
                            .show(getSupportFragmentManager(), "analytics"); break;
                    case 1: if (isPinned) unpinReel(); else pinReel(reel.reelId); break;
                    case 2: shareProfile(); break;
                    case 3: confirmDeleteSingleReel(reel); break;
                }
            }).create());
    }

    private void confirmDeleteSingleReel(ReelModel reel) {
        AlertDialogStyler.showReusableConfirm(this, "delete_single_reel",
            AlertDialogStyler.DialogSize.DEFAULT,
            "Delete Reel",
            "This reel will be permanently deleted.",
            "Delete", () -> {
                FirebaseUtils.getReelsRef().child(reel.reelId).removeValue();
                FirebaseUtils.getReelsByUserRef(targetUid).child(reel.reelId).removeValue();
                if (pinnedReel != null && reel.reelId.equals(pinnedReel.reelId)) unpinReel();
                reelsTabData.remove(reel);
                adapter.notifyDataSetChanged();
                refreshEmptyState();
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            },
            null, null,
            "Cancel");
    }

    // ── Video preview dialog (Feature 4) ──────────────────────────────────

    private void showVideoPreviewDialog(ReelModel reel, int adapterPos) {
        dismissPreviewDialog();
        previewDialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        previewDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        previewDialog.setContentView(R.layout.dialog_reel_preview);
        Window w = previewDialog.getWindow();
        if (w != null) w.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);

        PlayerView playerView = previewDialog.findViewById(R.id.preview_player_view);
        TextView   tvCap      = previewDialog.findViewById(R.id.tv_preview_caption);
        TextView   tvDur      = previewDialog.findViewById(R.id.tv_preview_duration);
        View       btnSelect  = previewDialog.findViewById(R.id.btn_preview_select);
        View       btnPlay    = previewDialog.findViewById(R.id.btn_preview_play);

        if (tvCap != null && reel.caption != null && !reel.caption.isEmpty()) {
            tvCap.setText(reel.caption); tvCap.setVisibility(View.VISIBLE);
        }
        if (tvDur != null && reel.duration > 0) {
            int s = (reel.duration / 1000) % 60, m = reel.duration / 60000;
            tvDur.setText(String.format(Locale.getDefault(), "%d:%02d", m, s));
        }

        previewPlayer = new ExoPlayer.Builder(this).build();
        previewPlayer.setVolume(0f);
        previewPlayer.setRepeatMode(Player.REPEAT_MODE_ONE);
        if (playerView != null) playerView.setPlayer(previewPlayer);
        previewPlayer.setMediaItem(MediaItem.fromUri(reel.videoUrl));
        previewPlayer.prepare();
        previewPlayer.setPlayWhenReady(true);

        if (btnSelect != null) btnSelect.setOnClickListener(v -> {
            dismissPreviewDialog(); enterMultiSelectMode(adapterPos);
        });
        if (btnPlay != null) btnPlay.setOnClickListener(v -> {
            dismissPreviewDialog(); openPlayerAt(adapterPos);
        });
        previewDialog.setOnDismissListener(d -> dismissPreviewDialog());
        previewDialog.show();
    }

    private void dismissPreviewDialog() {
        if (previewPlayer != null) { previewPlayer.release(); previewPlayer = null; }
        if (previewDialog != null && previewDialog.isShowing()) previewDialog.dismiss();
        previewDialog = null;
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
        toggleSelection(initialPos);
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
        List<ReelModel> data = activeTabData();
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
                activeTabData().removeIf(r -> selectedReelIds.contains(r.reelId));
                exitMultiSelectMode();
                adapter.notifyDataSetChanged();
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
                data.clear();
                exitMultiSelectMode();
                adapter.notifyDataSetChanged();
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
                    adapter.notifyDataSetChanged();
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
        if (layoutSongPillBubble != null) layoutSongPillBubble.setBackground(bg);
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

    private void showAvatarZoom(String photoUrl, String name) {
        if (isFinishing() || isDestroyed()) return;
        com.callx.app.utils.DialogFullscreenHelper.showAvatarZoom(
            this, photoUrl, name, R.drawable.ic_person, R.drawable.ic_close);
    }

    @Override protected void onPause()  { super.onPause();  dismissPreviewDialog(); stopAvatarAnimation(); }

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
        if (!isFirstResume && isSelf && activeTab == TAB_REELS) {
            silentRefreshReels();
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

                    // Fetch full ReelModel for each new ID
                    final int[]           remaining = {newIds.size()};
                    final List<ReelModel> fetched   = new ArrayList<>();
                    for (String id : newIds) {
                        FirebaseUtils.getReelsRef().child(id)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(@NonNull DataSnapshot s) {
                                    if (!isFinishing() && !isDestroyed()) {
                                        ReelModel r = s.getValue(ReelModel.class);
                                        if (r != null) fetched.add(r);
                                    }
                                    if (--remaining[0] == 0) onAllFetched();
                                }
                                @Override
                                public void onCancelled(@NonNull DatabaseError e) {
                                    if (--remaining[0] == 0) onAllFetched();
                                }

                                private void onAllFetched() {
                                    isLoadingMore = false;
                                    if (isFinishing() || isDestroyed() || fetched.isEmpty()) return;
                                    // Sort newest-first then prepend
                                    fetched.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                                    reelsTabData.addAll(0, fetched);
                                    if (activeTab == TAB_REELS && adapter != null) {
                                        int basePos = adapter.hasPinned() ? 1 : 0;
                                        adapter.notifyItemRangeInserted(basePos, fetched.size());
                                        // Scroll to top so new reel is immediately visible
                                        if (rvReels != null)
                                            rvReels.post(() -> rvReels.smoothScrollToPosition(0));
                                    }
                                    refreshEmptyState();
                                }
                            });
                    }
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) { isLoadingMore = false; }
            });
    }
}
