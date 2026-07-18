package com.callx.app.profile;

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
    // These tab constants are kept for internal data methods — no longer shown as tabs
    private static final int TAB_REPOST = 3;
    private static final int TAB_SERIES = 4;

    // Views
    private CircleImageView ivAvatar;
    private ImageView       ivVerified;
    private View            viewStoryRing;
    private TextView        tvName, tvDisplayName, tvReelCount, tvFollowers, tvFollowing, tvBio;
    private TextView        tvMutualFollowers;
    private LinearLayout    layoutMutualFollowers;
    private CircleImageView ivMutual1, ivMutual2, ivMutual3;
    private List<String>    mutualUidsList = new ArrayList<>();
    private TextView        tvPhone, tvWhatsapp, tvInstagram, tvYoutube, tvOtherLink;
    private View            layoutPhone, layoutWhatsapp, layoutInstagram, layoutYoutube, layoutOtherLink;
    private android.widget.HorizontalScrollView hsvBioLinks;
    private LinearLayout    llBioChips;
    private TextView        tvEmptyTitle, tvEmptySubtitle;
    private Button          btnFollow;
    private Button          btnMessageCta;
    private android.view.View btnCtaCall;
    private LinearLayout    layoutInstagramCta;
    private LinearLayout    layoutExtraActions;
    private ImageButton     btnBack, btnMore, btnShareProfile, btnCreatorHub, btnSettings;
    private ImageButton     btnMessage, btnAudioCall, btnVideoCall, btnOpenX, btnOpenYoutube;
    private LinearLayout    layoutActions;

    // ── Story Highlights ──────────────────────────────────────────────────
    private androidx.recyclerview.widget.RecyclerView rvHighlights;
    private android.widget.HorizontalScrollView       hsvHighlights;
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
    private final Set<String>     selectedReelIds = new HashSet<>();
      private final java.util.List<DuetSeriesModel> seriesTabData = new ArrayList<>();
      private boolean seriesLoaded = false;

    private String  reelsLastKey = null, likedLastKey = null,
                    savedLastKey = null, repostsLastKey = null;
    private boolean reelsHasMore = true, likedHasMore = true,
                    savedHasMore = true, repostsHasMore = true;
    private boolean isLoadingMore = false;

    // ── Realtime update helpers (self only) ───────────────────────────────
    /** Skip the silent grid refresh on the very first onResume (right after onCreate). */
    private boolean isFirstResume = true;
    /** Persistent count listener — auto-updates tvReelCount whenever a reel is added/removed. */
    private ValueEventListener reelCountLiveListener = null;

    private ReelModel         pinnedReel = null;
    private Dialog            previewDialog;
    private ExoPlayer         previewPlayer;
    private GridLayoutManager gridLayoutManager;

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
        loadHighlights();
        if (isSelf) {
            // Advance #3 — one-time, battery/network-friendly backfill of
            // BlurHash for reels posted before that feature shipped.
            com.callx.app.workers.BlurHashBackfillWorker.enqueueFor(getApplicationContext(), targetUid);
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
        tvName               = findViewById(R.id.tv_name);
        tvDisplayName        = findViewById(R.id.tv_display_name);
        tvReelCount          = findViewById(R.id.tv_reel_count);
        tvFollowers          = findViewById(R.id.tv_followers);
        tvFollowing          = findViewById(R.id.tv_following);
        tvBio                = findViewById(R.id.tv_bio);
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
        hsvBioLinks      = findViewById(R.id.hsv_bio_links);
        llBioChips       = findViewById(R.id.ll_bio_chips);
        btnMessageCta    = findViewById(R.id.btn_message_cta);
        btnCtaCall       = findViewById(R.id.btn_cta_call);
        layoutInstagramCta = findViewById(R.id.layout_instagram_cta);
        layoutExtraActions = findViewById(R.id.layout_extra_actions);
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
            rvSeries.setLayoutManager(new GridLayoutManager(this, 2));
            rvSeries.setAdapter(seriesAdapter);
            seriesAdapter.setOnSeriesClickListener(series -> {
                Intent si = new Intent(this, com.callx.app.social.DuetSeriesActivity.class);
                si.putExtra(com.callx.app.social.DuetSeriesActivity.EXTRA_SERIES_ID, series.seriesId);
                startActivity(si);
            });
        }
        adapter.setShowViewsOverlay(isSelf);

        gridLayoutManager = new GridLayoutManager(this, 3);
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
        rvReels.setHasFixedSize(false);
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

      /** Resolve the tappable view for a given TabLayout tab position (used as popup anchor). */
      private android.view.View tabAnchorView(TabLayout.Tab tab) {
          try {
              android.view.ViewGroup strip = (android.view.ViewGroup) tabLayout.getChildAt(0);
              if (strip != null && tab.getPosition() < strip.getChildCount()) {
                  return strip.getChildAt(tab.getPosition());
              }
          } catch (Exception ignored) {}
          return tabLayout;
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
        if (tabLayout == null) return;
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                activeTab = tab.getPosition();
                exitMultiSelectMode();
                boolean isSeries = (activeTab == TAB_SERIES);
                if (rvSeries != null) rvSeries.setVisibility(isSeries ? android.view.View.VISIBLE : android.view.View.GONE);
                if (rvReels  != null) rvReels.setVisibility(isSeries  ? android.view.View.GONE   : android.view.View.VISIBLE);
                if (!isSeries) adapter.notifyDataSetChanged();
                if (isSeries ? seriesTabData.isEmpty() : activeTabData().isEmpty()) loadCurrentTab(true);
                else { refreshEmptyState(); updateViewAllButton(); }
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
    }

    private List<ReelModel> activeTabData() {
        switch (activeTab) {
            case TAB_LIKED:  return likedTabData;
            case TAB_SAVED:  return savedTabData;
            case TAB_REPOST: return repostsTabData;
            default:         return reelsTabData;
        }
    }

    // ── Swipe left/right on the grid to switch tabs (mirrors the 5 icon tabs) ──

    private void switchToTab(int newPos) {
        if (newPos < TAB_REELS || newPos > TAB_SERIES) return; // out of range — no-op at edges
        if (tabLayout == null) return;
        TabLayout.Tab t = tabLayout.getTabAt(newPos);
        if (t != null) t.select();
    }

    private void setupSwipeBetweenTabs() {
        final float touchSlopPx = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        final float swipeThresholdPx = 60 * getResources().getDisplayMetrics().density;
        final float swipeVelocityPx  = 200 * getResources().getDisplayMetrics().density; // px/sec min fling speed

        // One GestureDetector PER view (state must not be shared between rv_reels / rv_series / layout_empty).
        RecyclerView.OnItemTouchListener reelsListener =
                buildSwipeListener(rvReels, touchSlopPx, swipeThresholdPx, swipeVelocityPx);
        RecyclerView.OnItemTouchListener seriesListener =
                buildSwipeListener(rvSeries, touchSlopPx, swipeThresholdPx, swipeVelocityPx);

        if (rvReels  != null && reelsListener  != null) rvReels.addOnItemTouchListener(reelsListener);
        if (rvSeries != null && seriesListener != null) rvSeries.addOnItemTouchListener(seriesListener);

        // IMPORTANT: refreshEmptyState() sets rvReels.setVisibility(GONE) and shows
        // layoutEmpty instead whenever the active tab (Liked/Saved/Reposts) has no
        // items. Since a GONE view receives no touch events at all, the swipe
        // listener above never fires there — which is why swiping used to get
        // stuck on the first empty tab and couldn't go further. Attach the same
        // fling detection to layoutEmpty as a plain touch listener so swiping keeps
        // working through empty tabs too.
        if (layoutEmpty != null) {
            final android.view.GestureDetector emptyStateDetector =
                    createTabSwipeGestureDetector(swipeThresholdPx, swipeVelocityPx);
            layoutEmpty.setOnTouchListener((v, e) -> {
                emptyStateDetector.onTouchEvent(e);
                return false; // don't swallow taps on any CTA buttons inside the empty state
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
                            switchToTab(dx < 0 ? activeTab + 1 : activeTab - 1);
                            return true;
                        }
                        return false;
                    }
                });
    }

    private RecyclerView.OnItemTouchListener buildSwipeListener(
            final RecyclerView rv, final float touchSlopPx, final float swipeThresholdPx, final float swipeVelocityPx) {
        if (rv == null) return null;

        final android.view.GestureDetector gestureDetector =
                createTabSwipeGestureDetector(swipeThresholdPx, swipeVelocityPx);

        return new RecyclerView.OnItemTouchListener() {
            private float downX, downY;
            private boolean draggingHorizontally = false;

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView recyclerView, @NonNull MotionEvent e) {
                gestureDetector.onTouchEvent(e);
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX(); downY = e.getY(); draggingHorizontally = false;
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getX() - downX, dy = e.getY() - downY;
                        if (!draggingHorizontally && Math.abs(dx) > touchSlopPx && Math.abs(dx) > Math.abs(dy)) {
                            draggingHorizontally = true;
                            // Stop the AppBarLayout/CoordinatorLayout from stealing the
                            // gesture mid-swipe so the fling always reaches onFling().
                            android.view.ViewParent p = recyclerView.getParent();
                            if (p != null) p.requestDisallowInterceptTouchEvent(true);
                        }
                        return draggingHorizontally;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        draggingHorizontally = false;
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
        FirebaseUtils.getUserStatusRef(targetUid)
            .orderByChild("timestamp").startAt((double) cutoff).limitToFirst(1)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    showStoryRing(snap.exists() && snap.getChildrenCount() > 0);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void showStoryRing(boolean show) {
        if (isFinishing() || isDestroyed() || viewStoryRing == null) return;
        viewStoryRing.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            ObjectAnimator pulse = ObjectAnimator.ofFloat(viewStoryRing, "alpha", 0.6f, 1f);
            pulse.setDuration(900);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.setRepeatMode(ObjectAnimator.REVERSE);
            pulse.start();
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
        rvHighlights.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(
                this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false));
        rvHighlights.setHasFixedSize(false);
        rvHighlights.setNestedScrollingEnabled(false);
        rebuildHighlightsAdapter();
    }

    /** Rebuild and attach a fresh adapter from the current highlightAlbums list. */
    private void rebuildHighlightsAdapter() {
        java.util.List<HighlightsRowAdapter.HighlightAlbum> adapterItems = new java.util.ArrayList<>();
        if (isSelf) adapterItems.add(HighlightsRowAdapter.HighlightAlbum.newButton()); // "+" first
        adapterItems.addAll(highlightAlbums);

        highlightsAdapter = new HighlightsRowAdapter(adapterItems, isSelf,
                new HighlightsRowAdapter.Listener() {

            @Override public void onAlbumClicked(HighlightsRowAdapter.HighlightAlbum album) {
                openHighlightAlbum(album);
            }

            @Override public void onAlbumLongPressed(HighlightsRowAdapter.HighlightAlbum album, int pos) {
                showHighlightManageSheet(album, pos);
            }

            @Override public void onNewClicked() {
                openManageHighlights();
            }
        });
        if (rvHighlights != null) rvHighlights.setAdapter(highlightsAdapter);
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

        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(album.albumName)
            .setItems(new String[]{"✏  Rename album", "🗑  Delete album"}, (d, which) -> {
                if (which == 0) showHighlightRenameDialog(album, adapterPos);
                else            confirmDeleteHighlight(album, adapterPos);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showHighlightRenameDialog(HighlightsRowAdapter.HighlightAlbum album, int adapterPos) {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(album.albumName);
        et.setSelection(album.albumName != null ? album.albumName.length() : 0);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad / 2, pad, pad / 2);

        new androidx.appcompat.app.AlertDialog.Builder(this)
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
            .show();
    }

    private void confirmDeleteHighlight(HighlightsRowAdapter.HighlightAlbum album, int adapterPos) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
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
            .show();
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

    private void loadMutualFollowers() {
        String myUid = safeMyUid();
        if (myUid == null || isSelf) return;
        FirebaseUtils.getReelFollowersRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot mySnap) {
                    Set<String> mine = new HashSet<>();
                    for (DataSnapshot s : mySnap.getChildren()) mine.add(s.getKey());
                    FirebaseUtils.getReelFollowersRef(targetUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot tSnap) {
                                mutualUidsList.clear();
                                for (DataSnapshot s : tSnap.getChildren())
                                    if (mine.contains(s.getKey())) mutualUidsList.add(s.getKey());
                                if (mutualUidsList.isEmpty()) {
                                    showMutualFollowers(new ArrayList<>(), new ArrayList<>());
                                    return;
                                }
                                // Fetch name + photo of first 3 mutual users
                                int fetchCount = Math.min(3, mutualUidsList.size());
                                List<String> names  = new ArrayList<>();
                                List<String> photos = new ArrayList<>();
                                final int[] done = {0};
                                for (int i = 0; i < fetchCount; i++) {
                                    String uid = mutualUidsList.get(i);
                                    FirebaseUtils.getUserRef(uid)
                                        .addListenerForSingleValueEvent(new ValueEventListener() {
                                            @Override public void onDataChange(@NonNull DataSnapshot us) {
                                                String n = us.child("name").getValue(String.class);
                                                String thumb = us.child("thumbUrl").getValue(String.class);
                                                String photo = us.child("photoUrl").getValue(String.class);
                                                String p = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                                                names.add(n != null ? n : "User");
                                                photos.add(p != null ? p : "");
                                                done[0]++;
                                                if (done[0] >= fetchCount)
                                                    showMutualFollowers(names, photos);
                                            }
                                            @Override public void onCancelled(@NonNull DatabaseError e) {
                                                names.add("User"); photos.add("");
                                                done[0]++;
                                                if (done[0] >= fetchCount)
                                                    showMutualFollowers(names, photos);
                                            }
                                        });
                                }
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
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
                            ReelModel r = snap.getValue(ReelModel.class);
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
        new AlertDialog.Builder(this)
            .setTitle("Reel Options")
            .setItems(new String[]{"View Insights", "Pin Reel", "Share", "Delete"}, (d, which) -> {
                switch (which) {
                    case 0:
                        ReelAnalyticsBottomSheet.newInstance(reel)
                            .show(getSupportFragmentManager(), "analytics"); break;
                    case 1: pinReel(reel.reelId); break;
                    case 2: shareProfile(); break;
                    case 3: confirmDeleteSingleReel(reel); break;
                }
            }).show();
    }

    private void confirmDeleteSingleReel(ReelModel reel) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Reel")
            .setMessage("This reel will be permanently deleted.")
            .setPositiveButton("Delete", (d, w) -> {
                FirebaseUtils.getReelsRef().child(reel.reelId).removeValue();
                FirebaseUtils.getReelsByUserRef(targetUid).child(reel.reelId).removeValue();
                if (pinnedReel != null && reel.reelId.equals(pinnedReel.reelId)) unpinReel();
                reelsTabData.remove(reel);
                adapter.notifyDataSetChanged();
                refreshEmptyState();
                Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
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
        new AlertDialog.Builder(this)
            .setTitle("Delete Reels")
            .setMessage("Delete " + selectedReelIds.size() + " reel(s)? This cannot be undone.")
            .setPositiveButton("Delete", (d, w) -> {
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
            })
            .setNegativeButton("Cancel", null).show();
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
        new AlertDialog.Builder(this)
            .setTitle("Delete All Reels")
            .setMessage("Delete all " + data.size() + " reel(s)? This cannot be undone.")
            .setPositiveButton("Delete All", (d, w) -> {
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
            })
            .setNegativeButton("Cancel", null).show();
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
        if (btnCtaCall != null) btnCtaCall.setOnClickListener(v -> {
            String cid = FirebaseDatabase.getInstance().getReference("calls").push().getKey();
            launchActivity("com.callx.app.call.CallActivity",
                new String[]{"partnerUid","partnerName","partnerPhoto","isCaller","video","callId"},
                new Object[]{targetUid, orEmpty(targetName), orEmpty(targetPhoto), true, false, orEmpty(cid)});
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
                Toast.makeText(this, "Added to Close Friends", Toast.LENGTH_SHORT).show(); break;
            case 1: // Add to favorites
                Toast.makeText(this, "Added to Favorites", Toast.LENGTH_SHORT).show(); break;
            case 2: // Mute
                Toast.makeText(this, "Muted", Toast.LENGTH_SHORT).show(); break;
            case 3: // Restrict
                Toast.makeText(this, "Restricted", Toast.LENGTH_SHORT).show(); break;
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

                // Website / social links from Reels profile — build compact chip row
                java.util.List<String[]> links = new java.util.ArrayList<>();
                if (!isEmpty(website)) {
                    String websiteUrl = website.startsWith("http") ? website : "https://" + website;
                    links.add(new String[]{"📞", website, websiteUrl});
                }
                if (!isEmpty(instagram)) {
                    String igLabel = instagram.startsWith("@") ? instagram : "@" + instagram;
                    String igUrl = instagram.startsWith("http") ? instagram
                        : "https://instagram.com/" + instagram.replace("@", "");
                    links.add(new String[]{"📷", igLabel, igUrl});
                }
                if (!isEmpty(youtube)) {
                    links.add(new String[]{"▶", youtube, youtube});
                }
                if (!isEmpty(twitter)) {
                    String twLabel = twitter.startsWith("@") ? twitter : "@" + twitter;
                    String twUrl = twitter.startsWith("http") ? twitter
                        : "https://x.com/" + twitter.replace("@", "");
                    links.add(new String[]{"✗", twLabel, twUrl});
                }
                buildBioChips(links);

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
     * Build compact chip row in hsv_bio_links (Screenshot 2 style).
     * Each chip: rounded border pill with icon + label, all in one scrollable row.
     * @param links list of {iconEmoji, displayLabel, clickUrl}
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
        int hPad  = (int)(12 * density);
        int vPad  = (int)(7  * density);
        int mEnd  = (int)(8  * density);
        int corner= (int)(20 * density);

        for (String[] link : links) {
            String emoji   = link[0];
            String label   = link[1];
            String url     = link[2];

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
            chip.setTextColor(0xFF222222);
            chip.setSingleLine(true);
            chip.setMaxEms(12);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);

            // Rounded border background
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(corner);
            bg.setColor(0xFFF5F5F5);
            bg.setStroke((int)(1 * density), 0xFFCCCCCC);
            chip.setBackground(bg);

            if (url != null && !url.isEmpty()) {
                chip.setClickable(true);
                chip.setFocusable(true);
                chip.setOnClickListener(v -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
                    } catch (Exception ex) {
                        Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show();
                    }
                });
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
            if (isSelf)  menu.getMenu().add(0, 5, 0, "Creator Dashboard");
            if (isSelf && pinnedReel != null) menu.getMenu().add(0, 4, 0, "Remove Pinned Reel");
            if (isSelf)  menu.getMenu().add(0, 6, 0, "🗑️ Delete All Reels");
            if (!isSelf) menu.getMenu().add(0, 3, 0, "Report User");
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
                }
                return true;
            });
            menu.show();
        });
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
        isFirstResume = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissPreviewDialog();
        stopAvatarAnimation();
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
