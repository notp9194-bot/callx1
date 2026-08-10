package com.callx.app.feed;

import com.callx.app.profile.ReelProfileSetupActivity;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.utils.ReelFirebaseUtils;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.reels.R;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.analytics.ReelCreatorDashboardActivity;
import com.callx.app.creator.ReelCreatorHubActivity;
import com.callx.app.notifications.ReelNotificationsActivity;
import com.callx.app.upload.ReelUploadActivity;
import com.callx.app.explore.ReelSearchActivity;
import com.callx.app.cache.ReelCacheManager;
import com.callx.app.cache.ReelPredictivePreloader;
import com.callx.app.cache.ReelVideoPreloader;
import com.callx.app.cache.ReelThumbnailPreloader;
import com.callx.app.cache.ReelUiStatePrecomputer;
import com.callx.app.player.ReelOfflineManager;
import com.callx.app.player.PrewarmThrottleGuard;
import com.callx.app.player.ReelThermalManager;
import com.callx.app.player.ExoPlayerPool;
import com.callx.app.feed.ReelsAdapter;
import com.callx.app.models.ReelModel;
import com.callx.app.ranking.FeedRankingEngine;
import com.callx.app.ranking.RankingProfile;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.core.content.ContextCompat;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

/**
 * ReelsFragment — Full-screen vertical reel feed with its own bottom navigation.
 *
 * Feeds:
 *  ✅ For You (FYP) — all reels sorted by trendingScore()
 *  ✅ Following     — reels from accounts the user follows
 *
 * Navigation:
 *  ✅ Home tab      — Instagram-like social hub (HomeFragment shown in home_container)
 *  ✅ Reels tab     — full-screen vertical reel feed
 *  ✅ Create        — opens ReelCameraActivity
 *  ✅ Activity      — opens ReelNotificationsActivity
 *  ✅ Creator       — shows current user's avatar as tab icon; opens ReelCreatorDashboardActivity
 *
 * Fix #2: Position memory
 *  The suppressNavScrollToTop flag prevents programmatic setSelectedItemId calls
 *  (triggered when returning from Create/Notifications/Creator activities) from
 *  firing the feed listener and scrolling the ViewPager2 back to position 0.
 *  Without this fix, tapping "Create" while at reel #5 scrolled back to #0 on return.
 */
public class ReelsFragment extends Fragment {

    private static final int PAGE_SIZE = 10;

    // ── Instagram-level thermal manager ──────────────────────────────────────
    // Single source of truth for thermal/battery state — gates ExoPlayer
    // prewarm (hardware decoder allocation) AND byte preloading independently.
    private ReelThermalManager thermalManager;
    private final Runnable thermalChangeListener = this::onThermalChanged;

    private ViewPager2           vpReels;
    private ReelsAdapter         adapter;
    private View                 layoutEmpty;
    private ProgressBar          progressReels;
    private ImageButton          btnUpload;
    private ImageButton          btnReelBack;
    private View                 btnPostFirst;
    private TextView             btnFyp, btnFollowing;
    private View                 feedIndicator;
    private BottomNavigationView reelBottomNav;
    private ImageButton          btnSearch;
    private View                 topBar;

    /** Home tab overlay container — HomeFragment lives here */
    private FrameLayout          homeContainer;

    /**
     * FIX #2: Prevents programmatic setSelectedItemId calls from triggering
     * scroll-to-top in the reel_nav_feed listener.
     *
     * Set to true BEFORE calling setSelectedItemId programmatically (e.g. after
     * launching Create / Notifications / Creator activities). The listener
     * reads this flag, skips the scroll, and resets it to false.
     */
    private boolean suppressNavScrollToTop = false;

    /**
     * FIX #1 & #3: Tracks whether the Reels tab is the currently visible tab in MainActivity.
     * Set to true only when MainActivity calls onTabResumed(), false when onTabPaused().
     * Prevents reel playback when user is on any other tab or returns from any activity
     * while not on the Reels tab.
     */
    private boolean isTabActive = false;

    private boolean isFypMode = true;

    private final List<ReelModel> allReels       = new ArrayList<>();
    private final Set<String>     blockedUids    = new HashSet<>();
    private final List<ReelModel> followingReels = new ArrayList<>();
    // Instagram-level ranking snapshot for the currently loaded FYP feed —
    // see FeedRankingEngine / RankingProfile (shared with HomeFragment).
    private RankingProfile        rankingProfile = new RankingProfile();
    private ValueEventListener    reelsListener;
    private ValueEventListener    followListener;
    private int                   currentPage   = 0;
    /**
     * Instagram-style unlimited scroll: index into the current source list
     * (allReels/followingReels) that the NEXT appended page starts from.
     * Wraps back to 0 via modulo once it reaches the end, so loadMoreReels()
     * never runs dry — the feed keeps recycling already-fetched reels
     * instead of hard-stopping like the old "if (currentPage >= source.size())
     * return;" guard used to. currentPage itself keeps its original meaning
     * (total items appended into the adapter so far) since it still drives
     * the "close to the end, load more" trigger in onPageSelected().
     */
    private int                   sourceCursor  = 0;
    private boolean               loading       = false;
    private int                   savedPosition = 0;

    private ReelVideoPreloader     videoPreloader;
    private ReelThumbnailPreloader thumbPreloader;
    private ReelUiStatePrecomputer uiStatePrecomputer;
    // v5: Predictive preloader + offline manager
    private ReelPredictivePreloader predictivePreloader;
    private float lastScrollVelocity = 0f; // v6: px/ms, feeds adaptive preload window
    private ReelOfflineManager      offlineManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_reels, container, false);

        vpReels        = v.findViewById(R.id.vp_reels);
        layoutEmpty    = v.findViewById(R.id.layout_empty);
        progressReels  = v.findViewById(R.id.progress_reels);
        btnUpload      = v.findViewById(R.id.btn_upload_reel);
        btnReelBack    = v.findViewById(R.id.btn_reel_back);
        btnPostFirst   = v.findViewById(R.id.btn_post_first_reel);
        btnFyp         = v.findViewById(R.id.btn_fyp);
        btnFollowing   = v.findViewById(R.id.btn_following);
        feedIndicator  = v.findViewById(R.id.feed_indicator);
        reelBottomNav  = v.findViewById(R.id.reel_bottom_nav);
        btnSearch      = v.findViewById(R.id.btn_reel_search);
        homeContainer  = v.findViewById(R.id.home_container);

        // ── Edge-to-edge window insets ────────────────────────────────────
        // top_bar: statusBarHeight padding so buttons sit BELOW the status bar.
        // Video extends behind the transparent status bar (Instagram Reels style).
        topBar = v.findViewById(R.id.top_bar);
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (view, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            view.setPadding(
                view.getPaddingLeft(),
                statusBarHeight,
                view.getPaddingRight(),
                view.getPaddingBottom());
            return insets;
        });

        // reel_bottom_nav: navigationBarHeight padding keeps nav items above
        // the gesture bar / 3-button nav on edge-to-edge screens.
        ViewCompat.setOnApplyWindowInsetsListener(reelBottomNav, (view, insets) -> {
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            view.setPadding(
                view.getPaddingLeft(), view.getPaddingTop(),
                view.getPaddingRight(), navBarHeight);
            return insets;
        });

        adapter = new ReelsAdapter(this);
        adapter.setGamesCardsEnabled(true); // Mini Games card every 3 reels (YouTube-Playables style)
        vpReels.setAdapter(adapter);
        // ── Optimized offscreen limit ──────────────────────────────────────
        // FIX: offscreenPageLimit=1 → sirf N-1 aur N+1 fragments alive hain.
        // Pehle 4 tha: 5 fragments simultaneously alive → 5 sets of Firebase
        // real-time listeners + multiple ExoPlayer instances → phone garam +
        // hang. Ab 1 hai: sirf 3 fragments → drastically kam RAM/CPU/Firebase.
        // N+1 ka prewarm (preparePlayerSilently) abhi bhi hota hai — ExoPlayer
        // prepare() fragment exist hone ke baad bhi kaam karta hai. Zero
        // buffering on normal swipe maintained.
        vpReels.setOffscreenPageLimit(1);

        /*
         * PERF: keep ViewPager2 on its native full-page path.
         *
         * A scale/alpha PageTransformer forces Android to invalidate and
         * composite two full-screen video surfaces on every scroll frame.
         * A second Choreographer loop used to walk the inner RecyclerView and
         * repeat that work once more per vsync. Reels are already full-screen
         * pages, so the native pager animation is both cheaper and smoother.
         */
        configurePagerForVideoScroll();

        // PERF (advance #8 — "GPU decode warm-up"): fire-and-forget,
        // process-lifetime-once codec + EGL warm-up on a background thread
        // so the FIRST reel opened this session doesn't pay the one-time
        // codec-HAL/GL-driver init cost that every reel after it skips.
        com.callx.app.player.GpuDecodeWarmup.warmUpOnce(requireContext());

        // ── Instagram-level thermal manager (real-time monitoring) ────────────
        // Must be initialized BEFORE preloaders so we can gate them immediately.
        thermalManager = ReelThermalManager.get(requireContext());
        thermalManager.addChangeListener(thermalChangeListener);

        ReelCacheManager.init(requireContext());
        videoPreloader = new ReelVideoPreloader(requireContext());
        // Wire preloader to existing fragments if any (e.g. after config change)
        wirePreloaderToVisibleFragment();
        thumbPreloader = new ReelThumbnailPreloader(requireContext());
        uiStatePrecomputer = new ReelUiStatePrecomputer();
        // v5: Init predictive preloader + offline manager
        predictivePreloader = new ReelPredictivePreloader(requireContext());
        offlineManager      = ReelOfflineManager.get(requireContext());

        // PERF (advance #5 — predictive prefetch extended to player level):
        // when the learned model's top-ranked upcoming reel already has an
        // instantiated fragment (within offscreenPageLimit), warm its
        // ExoPlayer for real instead of just its cache bytes. Purely
        // additive on top of the positional N+1/N+2/N+3 prewarm below —
        // this is what lets "the reel the user is statistically about to
        // watch" jump the queue even if it's not the very next one.
        predictivePreloader.setPlayerPrewarmListener((reel, offset, score) -> {
            if (reel == null || reel.reelId == null || !isTabActive) return;
            if (PrewarmThrottleGuard.shouldThrottleExtraDistance(requireContext())) return;
            int curPos = vpReels.getCurrentItem();
            // With offscreenPageLimit=1 only N+1 can be instantiated ahead of
            // the current page. Searching the rest of the feed cannot find a
            // live fragment and needlessly turns a page callback into O(feed).
            int nextPos = curPos + 1;
            if (nextPos < adapter.getItemCount()) {
                Fragment nf = getChildFragmentManager()
                    .findFragmentByTag("f" + adapter.getItemId(nextPos));
                if (nf instanceof ReelPlayerFragment) {
                    ReelModel bound = ((ReelPlayerFragment) nf).getReel();
                    if (bound != null && reel.reelId.equals(bound.reelId)) {
                        ((ReelPlayerFragment) nf).prewarmPlayer();
                    }
                }
            }
        });

        vpReels.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            private long lastScrollNs = 0;
            private int  lastOffsetPx = 0;
            private float scrollVelocityPxPerMs = 0f;

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                // v6: derive scroll velocity (px/ms) for the predictive preloader
                long nowNs = System.nanoTime();
                if (lastScrollNs != 0) {
                    long dtMs = (nowNs - lastScrollNs) / 1_000_000L;
                    if (dtMs > 0) {
                        int dPx = Math.abs(positionOffsetPixels - lastOffsetPx);
                        scrollVelocityPxPerMs = (float) dPx / dtMs;
                    }
                }
                lastScrollNs = nowNs;
                lastOffsetPx = positionOffsetPixels;
                ReelsFragment.this.lastScrollVelocity = scrollVelocityPxPerMs;
            }

            @Override
            public void onPageScrollStateChanged(int state) {
            }

            @Override
            public void onPageSelected(int position) {
                controlPlayback(position);
                int reelIndex = adapter.toReelIndex(position);
                if (reelIndex >= currentPage - 3) loadMoreReels();
                List<ReelModel> cur = isFypMode ? allReels : followingReels;
                // ── Thermal-gated byte preloading ────────────────────────────────
                // Byte preloading (network downloads) is allowed up to LIGHT thermal.
                // On MODERATE+ we skip all preloading to let the device cool down.
                // The CURRENT reel still plays fine — only the speculative prefetch stops.
                ReelThermalManager.Level thermalLevel = thermalManager != null
                    ? thermalManager.getLevel() : ReelThermalManager.Level.SAFE;
                boolean canPreload = thermalLevel != ReelThermalManager.Level.HOT;
                if (canPreload) {
                    if (videoPreloader != null) videoPreloader.preloadFrom(cur, reelIndex);
                }
                // Sync preloader to newly visible fragment
                wirePreloaderToCurrentFragment(position);
                if (thumbPreloader != null) thumbPreloader.preloadFrom(cur, reelIndex);
                if (uiStatePrecomputer != null) uiStatePrecomputer.precomputeFrom(cur, reelIndex);
                // v5/v6: Record watch event + drive predictive preload order
                // (velocity-adaptive: fast flicks shrink the lookahead window/bytes)
                // Predictive preload only on SAFE thermal (it's the most aggressive)
                boolean canPredictivePreload = (thermalLevel == ReelThermalManager.Level.SAFE
                    || thermalLevel == ReelThermalManager.Level.LIGHT);
                if (canPredictivePreload && predictivePreloader != null && reelIndex < cur.size()) {
                    predictivePreloader.preloadSmartFrom(cur, reelIndex, lastScrollVelocity);
                    android.util.Log.d("ReelsFragment", "Predictive preload from pos=" + reelIndex
                        + " vel=" + lastScrollVelocity + "px/ms");
                }
            }
        });

        btnReelBack.setOnClickListener(x -> {
            if (getActivity() != null) getActivity().onBackPressed();
        });

        btnUpload.setOnClickListener(x ->
            startActivity(new Intent(getContext(), ReelUploadActivity.class)));

        if (btnPostFirst != null)
            btnPostFirst.setOnClickListener(x ->
                startActivity(new Intent(getContext(), ReelUploadActivity.class)));

        btnFyp.setOnClickListener(x -> switchFeed(true));
        btnFollowing.setOnClickListener(x -> switchFeed(false));

        if (btnSearch != null)
            btnSearch.setOnClickListener(x ->
                startActivity(new Intent(getContext(), ReelSearchActivity.class)));

        // ── Reels bottom navigation ────────────────────────────────────────
        reelBottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            updateNavIconTints(id);

            if (id == R.id.reel_nav_home) {
                // Show the Home tab overlay (HomeFragment)
                showHomeTab();
                return true;

            } else if (id == R.id.reel_nav_feed) {
                // FIX #2: Skip scroll-to-top if this selection was triggered programmatically
                //         (e.g. returning from Create/Creator/Notifications activities).
                hideHomeTab();
                if (!suppressNavScrollToTop && vpReels != null && vpReels.getCurrentItem() != 0) {
                    vpReels.setCurrentItem(0, true);
                }
                suppressNavScrollToTop = false;
                return true;

            } else if (id == R.id.reel_nav_create) {
                // FIX #2: Set suppress flag BEFORE calling setSelectedItemId so the
                //         reel_nav_feed listener does NOT scroll back to top.
                suppressNavScrollToTop = true;
                startActivity(new Intent(getContext(), ReelCameraActivity.class));
                reelBottomNav.setSelectedItemId(R.id.reel_nav_feed);
                return true;

            } else if (id == R.id.reel_nav_notifications) {
                suppressNavScrollToTop = true;
                startActivity(new Intent(getContext(), ReelNotificationsActivity.class));
                reelBottomNav.setSelectedItemId(R.id.reel_nav_feed);
                return true;

            } else if (id == R.id.reel_nav_creator) {
                suppressNavScrollToTop = true;
                String myUid  = safeMyUid();
                String myName = com.callx.app.utils.FirebaseUtils.getCurrentName();
                // Check if Reel profile exists (reels/users/{uid}), else setup pehle
                com.callx.app.utils.ReelFirebaseUtils.reelUserRef(myUid != null ? myUid : "")
                    .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                        @Override
                        public void onDataChange(com.google.firebase.database.DataSnapshot snap) {
                            if (!snap.exists()) {
                                // First time — Reel profile setup
                                startActivity(new Intent(getContext(),
                                    com.callx.app.profile.ReelProfileSetupActivity.class));
                            } else {
                                // Reel profile hai — directly open
                                Intent i = new Intent(getContext(), com.callx.app.profile.UserReelsActivity.class);
                                i.putExtra("uid",  myUid  != null ? myUid  : "");
                                i.putExtra("name", myName != null ? myName : "");
                                i.putExtra("photo", "");
                                i.putExtra("is_own_profile", true);
                                startActivity(i);
                            }
                        }
                        @Override public void onCancelled(com.google.firebase.database.DatabaseError e) {
                            // Fallback: directly open
                            Intent i = new Intent(getContext(), com.callx.app.profile.UserReelsActivity.class);
                            i.putExtra("uid",  myUid  != null ? myUid  : "");
                            i.putExtra("name", myName != null ? myName : "");
                            i.putExtra("photo", "");
                            i.putExtra("is_own_profile", true);
                            startActivity(i);
                        }
                    });
                reelBottomNav.setSelectedItemId(R.id.reel_nav_feed);
                return true;
            }
            return false;
        });

        // Start on the Reels feed tab (suppressed so it doesn't scroll to top on init)
        suppressNavScrollToTop = true;
        reelBottomNav.setSelectedItemId(R.id.reel_nav_feed);

        // Since itemIconTint="@null" in XML (needed so creator avatar isn't tinted white),
        // manually apply the modern selected/unselected tint treatment here.
        updateNavIconTints(R.id.reel_nav_feed);

        // FIX #3: Load current user's avatar and set it as the Creator tab icon
        // post() defers until after the view is fully laid out so menu items are ready
        reelBottomNav.post(() -> loadCreatorAvatar());
        reelBottomNav.post(() -> loadNotificationBadge());

        // Instagram-style tab warm-up (see prewarmHomeTab() doc) — deferred a
        // beat so it never competes with the very first reel's video buffer.
        vpReels.postDelayed(this::prewarmHomeTab, 400L);

        return v;
    }

    /**
     * Instagram-style Home-tab warm-up. Previously HomeFragment (and its
     * Firebase fetches for stories/feed/trending/activity/continue-watching/
     * suggested-creators — each its own separate network round trip) was
     * only created the FIRST time the user tapped the Home nav item inside
     * Reels — so that tap visibly showed the feed being assembled piece by
     * piece (loading spinners, sections popping in one at a time) instead of
     * just being there already, the way Instagram's own Home tab is.
     * Instagram keeps every bottom-tab warm in the background the moment
     * you're anywhere in the app, so switching to it is instant.
     * Do the same here: attach HomeFragment now, while it's still hidden
     * (home_container stays GONE — see fragment_reels.xml), so all its
     * fetches are already in flight — usually already finished — by the
     * time the user actually taps Home. showHomeTab() just flips visibility.
     */
    private void prewarmHomeTab() {
        if (homeContainer == null || !isAdded()) return;
        if (getChildFragmentManager().findFragmentByTag("home_fragment") != null) return;
        getChildFragmentManager()
            .beginTransaction()
            .replace(R.id.home_container, new HomeFragment(), "home_fragment")
            .commitAllowingStateLoss();
    }

    // ── FIX #3: Creator tab avatar ────────────────────────────────────────
    /**
     * Loads the current user's profile photo via Glide, converts it to a circular
     * BitmapDrawable, and sets it as the icon for the Creator tab menu item.
     * Called once from onCreateView and refreshed on each onStart.
     */
    /**
     * Modern selected/unselected icon treatment for the Reels bottom nav —
     * dims non-active tabs and keeps the active one at full opacity, paired
     * with the pill-shaped active-indicator (see Widget.ReelNav.ActiveIndicator
     * in shape_reel_nav.xml) behind it. Replaces the old flat "every icon is
     * the same solid white, all the time" look.
     *
     * Still skips the creator tab's icon — that slot holds the user's own
     * avatar bitmap (loadCreatorAvatar()), not a tintable vector, and must
     * stay untinted so the photo isn't washed out. The active-indicator pill
     * still renders behind it when selected, so it's not left without any
     * selected-state feedback.
     */
    private void updateNavIconTints(int selectedId) {
        if (reelBottomNav == null || getContext() == null) return;
        android.content.res.ColorStateList selectedTint =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.reel_nav_icon_selected));
        android.content.res.ColorStateList unselectedTint =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), R.color.reel_nav_icon_unselected));
        int[] nonCreatorIds = {
            R.id.reel_nav_home,
            R.id.reel_nav_feed,
            R.id.reel_nav_create,
            R.id.reel_nav_notifications
        };
        for (int id : nonCreatorIds) {
            android.view.MenuItem item = reelBottomNav.getMenu().findItem(id);
            if (item != null && item.getIcon() != null) {
                item.getIcon().setTintList(id == selectedId ? selectedTint : unselectedTint);
            }
        }
        // Creator tab: clear any tint so the avatar shows in true color —
        // the active-indicator pill alone communicates its selected state.
        android.view.MenuItem creatorItem = reelBottomNav.getMenu().findItem(R.id.reel_nav_creator);
        if (creatorItem != null && creatorItem.getIcon() != null) {
            creatorItem.getIcon().setTintList(null);
        }
    }

    private void loadNotificationBadge() {
        if (reelBottomNav == null || !isAdded() || getContext() == null) return;
        String myUid = safeMyUid();
        if (myUid == null) return;
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reel_notifications").child(myUid)
            .addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!isAdded() || reelBottomNav == null) return;
                    long unread = 0;
                    for (DataSnapshot s : snap.getChildren()) {
                        Boolean seen = s.child("seen").getValue(Boolean.class);
                        if (seen == null || !seen) unread++;
                    }
                    final long count = unread;
                    requireActivity().runOnUiThread(() -> {
                        if (reelBottomNav == null || !isAdded()) return;
                        com.google.android.material.badge.BadgeDrawable badge =
                            reelBottomNav.getOrCreateBadge(R.id.reel_nav_notifications);
                        if (count > 0) {
                            badge.setVisible(true);
                            badge.setNumber((int) Math.min(count, 99));
                            badge.setBackgroundColor(
                                ContextCompat.getColor(requireContext(), R.color.brand_primary));
                            badge.setBadgeTextColor(
                                ContextCompat.getColor(requireContext(), android.R.color.white));
                        } else {
                            badge.setVisible(false);
                        }
                    });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void loadCreatorAvatar() {
        String myUid = safeMyUid();
        if (myUid == null || reelBottomNav == null || !isAdded() || getContext() == null) return;

        // Reels system ka avatar load karo (reels/users/{uid}) — chat profile nahi
        com.google.firebase.database.FirebaseDatabase.getInstance()
            .getReference("reels/users").child(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded() || getContext() == null || reelBottomNav == null) return;

                // thumbUrl (100×100 WebP) — perfect for 28dp BottomNav icon
                String photo = snap.child("thumbUrl").getValue(String.class);
                if (photo == null || photo.isEmpty()) {
                    photo = snap.child("photoUrl").getValue(String.class);
                }
                if (photo == null || photo.isEmpty()) return;

                final String finalPhoto = photo;
                int sizePx = dpToPx(28); // 28dp — standard BottomNav icon size

                Glide.with(requireContext())
                    .asBitmap()
                    .load(finalPhoto)
                    .apply(RequestOptions.circleCropTransform().override(sizePx, sizePx))
                    .into(new CustomTarget<Bitmap>(sizePx, sizePx) {
                        @Override
                        public void onResourceReady(@NonNull Bitmap resource,
                                                    @Nullable Transition<? super Bitmap> t) {
                            if (!isAdded() || getContext() == null || reelBottomNav == null) return;
                            android.view.MenuItem creatorItem =
                                reelBottomNav.getMenu().findItem(R.id.reel_nav_creator);
                            if (creatorItem != null) {
                                // RoundedBitmapDrawable renders more reliably in BottomNavigationView
                                RoundedBitmapDrawable rd =
                                    RoundedBitmapDrawableFactory
                                        .create(getResources(), resource);
                                rd.setCircular(true);
                                rd.setTintList(null); // no tint — show real colors
                                creatorItem.setIcon(rd);
                            }
                        }
                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {}
                    });
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    // ── FIX #4: Home tab show/hide ────────────────────────────────────────

    /**
     * Shows the HomeFragment in the home_container overlay.
     * Pauses reel playback while home tab is visible.
     */
    private void showHomeTab() {
        if (homeContainer == null) return;
        homeContainer.setVisibility(View.VISIBLE);

        // Normally a no-op — prewarmHomeTab() already injected HomeFragment
        // in the background when Reels first opened. This stays only as a
        // defensive fallback (e.g. the 400ms warm-up post() never fired).
        prewarmHomeTab();

        // Now that it's genuinely on-screen, let it actually start playing
        // its current feed-card video (see HomeFragment.onTabBecameVisible()).
        androidx.fragment.app.Fragment home =
            getChildFragmentManager().findFragmentByTag("home_fragment");
        if (home instanceof HomeFragment) ((HomeFragment) home).onTabBecameVisible();

        pauseAllReels();
    }

    /**
     * Hides the HomeFragment overlay and resumes reel playback.
     * Called when user taps the Reels tab.
     */
    private void hideHomeTab() {
        if (homeContainer != null) homeContainer.setVisibility(View.GONE);
        androidx.fragment.app.Fragment home =
            getChildFragmentManager().findFragmentByTag("home_fragment");
        if (home instanceof HomeFragment) ((HomeFragment) home).onTabBecameHidden();
        if (vpReels != null) controlPlayback(vpReels.getCurrentItem());
    }

    /**
     * Public entry-point called by HomeFragment when user taps a post/reel
     * inside the Home tab and wants to open the reel video feed.
     */
    public void showReelFeed() {
        if (reelBottomNav == null) return;
        suppressNavScrollToTop = true;
        reelBottomNav.setSelectedItemId(R.id.reel_nav_feed);
    }

    @Override
    public void onDestroyView() {
        // CRASH FIX: Remove Firebase listeners BEFORE destroying view.
        // Without this, Firebase callbacks fire after vpReels/adapter are null → NPE crash.
        removeListeners();

        // Release thermal manager listener (NOT the manager itself — it's a singleton
        // that lives for the process; we just remove our callback)
        if (thermalManager != null) {
            thermalManager.removeChangeListener(thermalChangeListener);
            thermalManager = null;
        }

        if (videoPreloader != null) { videoPreloader.shutdown(); videoPreloader = null; }
        thumbPreloader = null;
        if (uiStatePrecomputer != null) { uiStatePrecomputer.shutdown(); uiStatePrecomputer = null; }
        if (predictivePreloader != null) { predictivePreloader.shutdown(); predictivePreloader = null; }
        // PERF (advance #3): hard-release the pool here — this is the whole
        // Reels feature going away (not just a tab switch), so there's no
        // more reason to keep the pooled ExoPlayer instances warm.
        if (getContext() != null) ExoPlayerPool.get(getContext()).releaseAll();
        super.onDestroyView();
    }

    /**
     * Called by ReelThermalManager when device thermal / battery state changes.
     * Immediately cancels byte preloading if device becomes too hot, so we don't
     * keep feeding CPU/network resources to background downloads during throttling.
     */
    private void onThermalChanged() {
        if (thermalManager == null) return;
        ReelThermalManager.Level level = thermalManager.getLevel();
        android.util.Log.d("ReelsFragment", "Thermal changed → " + level);
        if (level == ReelThermalManager.Level.HOT) {
            // Cancel all active byte downloads immediately
            if (videoPreloader != null) videoPreloader.cancelAll();
            if (predictivePreloader != null) predictivePreloader.cancelAll();
            android.util.Log.d("ReelsFragment", "Thermal HOT: all preloads cancelled");
        }
    }

    // ── Preloader → Fragment wiring ───────────────────────────────────────────

    private void wirePreloaderToCurrentFragment(int position) {
        if (adapter == null || vpReels == null) return;
        try {
            androidx.fragment.app.Fragment f = getChildFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(position));
            if (f instanceof com.callx.app.feed.ReelPlayerFragment && videoPreloader != null) {
                ((com.callx.app.feed.ReelPlayerFragment) f).setPreloader(videoPreloader);
            }
        } catch (Exception ignored) {}
    }

    private void wirePreloaderToVisibleFragment() {
        if (vpReels == null) return;
        wirePreloaderToCurrentFragment(vpReels.getCurrentItem());
    }

    @Override
    public void onStart() {
        super.onStart();
        // Refresh icon tints (selected/unselected, null for creator) then reload avatar
        int currentlySelected = reelBottomNav != null
            ? reelBottomNav.getSelectedItemId() : R.id.reel_nav_feed;
        updateNavIconTints(currentlySelected);
        // Refresh creator avatar whenever fragment comes to foreground
        loadCreatorAvatar();

        // FIX #LAZY-REELS: isTabActive guard — agar user ne Reels tab abhi nahi khola
        // (offscreenPageLimit ki wajah se fragment create hua lekin visible nahi hai)
        // to Firebase fetch aur video preload bilkul nahi hoga.
        // Jab user pehli baar Reels tab tap karega, onTabResumed() → onStart() dobara
        // call hoga aur tab isTabActive = true hoga — tab fetch hoga.
        if (!isTabActive) return;

        loadBlockedUids();
        if (allReels.isEmpty()) {
            loadFypReels();
        } else if (isFypMode) {
            renderPageAtPosition(allReels, savedPosition);
        } else {
            renderPageAtPosition(followingReels, savedPosition);
        }
    }

    @Override
    public void onStop() {
        if (vpReels != null) savedPosition = vpReels.getCurrentItem();
        removeListeners();
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        // FIX #1 & #3: Only resume reel playback if:
        //   1. The Reels tab is the currently active tab in MainActivity, AND
        //   2. The Home tab overlay is NOT visible (user is on Reels feed, not Home tab).
        // This prevents background reel playback when:
        //   - User plays a video from Home tab and returns (Fix #1)
        //   - User visits any other tab (Chats, Status, Groups, Calls) and comes back (Fix #3)
        if (!isTabActive) return;
        boolean homeVisible = homeContainer != null
                && homeContainer.getVisibility() == android.view.View.VISIBLE;
        if (!homeVisible) {
            int pos = (vpReels != null) ? vpReels.getCurrentItem() : 0;
            controlPlayback(pos);
        }
    }

    @Override
    public void onPause() {
        if (vpReels != null) savedPosition = vpReels.getCurrentItem();
        // BACKGROUND PLAY: this onPause() fires when the Activity itself goes
        // to the background. If the user turned on "Background Play" from the
        // Reels 3-dot menu, skip the force-pause here so the current reel keeps
        // playing with audio — ReelPlayerFragment#onPause() makes the same
        // check per-fragment. All OTHER pause paths (tab switch, Home overlay,
        // feed scroll) still call pauseAllReels() unconditionally.
        boolean keepPlayingInBackground = getContext() != null
                && com.callx.app.utils.ReelBackgroundPlaySettings.isEnabled(getContext());
        if (!keepPlayingInBackground) {
            pauseAllReels();
        }
        super.onPause();
    }

    // ── Feed toggle ───────────────────────────────────────────────────────

    private void switchFeed(boolean fyp) {
        isFypMode = fyp;
        animateFeedIndicator(fyp);

        if (fyp) {
            btnFyp.setAlpha(1.0f);
            btnFyp.setTypeface(null, android.graphics.Typeface.BOLD);
            btnFollowing.setAlpha(0.55f);
            btnFollowing.setTypeface(null, android.graphics.Typeface.NORMAL);
        } else {
            btnFyp.setAlpha(0.55f);
            btnFyp.setTypeface(null, android.graphics.Typeface.NORMAL);
            btnFollowing.setAlpha(1.0f);
            btnFollowing.setTypeface(null, android.graphics.Typeface.BOLD);
        }

        pauseAllReels();
        adapter.setReels(new ArrayList<>());
        currentPage   = 0;
        sourceCursor  = 0;
        savedPosition = 0;
        loading       = false;
        if (videoPreloader != null) videoPreloader.cancelAll();

        if (fyp) renderPage(allReels);
        else     loadFollowingFeed();
    }

    private void animateFeedIndicator(boolean fyp) {
        if (feedIndicator == null || btnFyp == null || btnFollowing == null) return;
        float targetX = fyp ? 0f : btnFyp.getWidth() + dpToPx(12);
        ObjectAnimator.ofFloat(feedIndicator, "translationX", targetX).setDuration(200).start();
    }

    // ── FYP feed ──────────────────────────────────────────────────────────

    private void loadFypReels() {
        if (loading) return;
        loading = true;
        showLoading(true);

        reelsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                // FIX #2: Save current position BEFORE re-rendering so that a Firebase
                // data change triggered by repost (or any other write) does not reset
                // the ViewPager back to position 0. We restore to savedPosition after reload.
                if (vpReels != null && vpReels.getCurrentItem() > 0) {
                    savedPosition = vpReels.getCurrentItem();
                }
                List<ReelModel> fetched = new ArrayList<>();
                for (DataSnapshot s : snap.getChildren()) {
                    ReelModel reel = s.getValue(ReelModel.class);
                    if (reel != null) {
                        if (reel.reelId == null) reel.reelId = s.getKey();
                        if (!"close_friends".equals(reel.audienceType) || (safeMyUid() != null && (safeMyUid().equals(reel.uid) || com.callx.reels.utils.ReelCloseFriendsUtil.isCloseFriend(getContext(), reel.uid)))) {
                        fetched.add(reel);
                        }
                    }
                }
                // Remove blocked users before ranking (no point scoring them).
                fetched.removeIf(r -> r.uid != null && blockedUids.contains(r.uid));

                String myUid = safeMyUid();
                Set<String> followedForRanking = new HashSet<>();
                if (myUid != null) {
                    FirebaseUtils.getReelFollowsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot fSnap) {
                            for (DataSnapshot fs : fSnap.getChildren()) followedForRanking.add(fs.getKey());
                            applyFypRanking(fetched, myUid, followedForRanking);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            applyFypRanking(fetched, myUid, followedForRanking);
                        }
                    });
                } else {
                    applyFypRanking(fetched, null, followedForRanking);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                showLoading(false);
                loading = false;
            }
        };
        FirebaseUtils.getReelsRef()
            .orderByChild("timestamp")
            .addValueEventListener(reelsListener);
    }

    /**
     * Loads this user's {@link RankingProfile} (relationship + real creator
     * watch-affinity + topic prefs + repeat suppression) and runs it through
     * {@link FeedRankingEngine} — engagement × recency, relationship,
     * watch-time/completion, topic personalization, discovery boost, and a
     * diversity re-rank pass so one creator can't cluster several cards in a
     * row. Same engine/signals as HomeFragment's For-You feed, so Reels tab
     * and Home tab rank consistently instead of using two different
     * heuristics.
     */
    private void applyFypRanking(List<ReelModel> fetched, @Nullable String myUid, Set<String> followedUids) {
        RankingProfile.load(myUid, followedUids, profile -> {
            if (!isAdded() || getContext() == null) return;
            rankingProfile = profile;
            allReels.clear();
            allReels.addAll(FeedRankingEngine.buildRankedFeed(fetched, profile));

            showLoading(false);
            loading = false;

            if (isFypMode) {
                if (allReels.isEmpty()) showEmpty(true, "No Reels Yet", "Be the first to share!");
                else {
                    showEmpty(false, null, null);
                    if (savedPosition > 0) renderPageAtPosition(allReels, savedPosition);
                    else renderPage(allReels);
                }
            }
        });
    }

    // ── Following feed ────────────────────────────────────────────────────

    private void loadFollowingFeed() {
        String myUid = safeMyUid();
        if (myUid == null) { showEmpty(true, "Not logged in", ""); return; }
        showLoading(true);

        followListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                Set<String> followedUids = new HashSet<>();
                for (DataSnapshot s : snap.getChildren()) followedUids.add(s.getKey());

                if (followedUids.isEmpty()) {
                    showLoading(false);
                    showEmpty(true, "No Following Feed", "Follow people to see their reels here.");
                    return;
                }

                followingReels.clear();
                for (ReelModel reel : allReels) {
                    if (followedUids.contains(reel.uid)) followingReels.add(reel);
                }
                Collections.sort(followingReels, (a, b) -> Long.compare(b.timestamp, a.timestamp));

                showLoading(false);
                if (followingReels.isEmpty()) {
                    showEmpty(true, "No Reels Yet", "People you follow haven't posted reels.");
                } else {
                    showEmpty(false, null, null);
                    renderPage(followingReels);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) { showLoading(false); }
        };
        FirebaseUtils.getReelFollowsRef(myUid).addValueEventListener(followListener);
    }

    // ── Render + paginate ─────────────────────────────────────────────────

    private void renderPage(List<ReelModel> source) {
        // CRASH FIX: Guard against fragment detach before adapter operations
        if (!isAdded() || getActivity() == null || adapter == null || vpReels == null) return;
        int end = Math.min(PAGE_SIZE, source.size());
        adapter.setReels(source.subList(0, end));
        currentPage   = end;
        sourceCursor  = source.isEmpty() ? 0 : (end % source.size());
        savedPosition = 0;
        if (videoPreloader != null) videoPreloader.preloadFrom(source.subList(0, end), 0);
        if (thumbPreloader != null) thumbPreloader.preloadFrom(source.subList(0, end), 0);
        if (uiStatePrecomputer != null) uiStatePrecomputer.precomputeFrom(source.subList(0, end), 0);
        if (getActivity() != null) getActivity().runOnUiThread(() -> {
            if (!isAdded() || vpReels == null) return;
            vpReels.setCurrentItem(0, false);
            controlPlayback(0);
        });
    }

    private void renderPageAtPosition(List<ReelModel> source, int position) {
        if (source.isEmpty()) return;
        // CRASH FIX: Guard against fragment detach
        if (!isAdded() || getActivity() == null || adapter == null || vpReels == null) return;
        int end = Math.max(Math.min(PAGE_SIZE, source.size()),
                           Math.min(position + 1, source.size()));
        if (adapter.getItemCount() == 0) {
            adapter.setReels(source.subList(0, end));
            currentPage = end;
            sourceCursor = source.isEmpty() ? 0 : (end % source.size());
        }
        int safePos = Math.min(position, adapter.getItemCount() - 1);
        if (getActivity() != null) getActivity().runOnUiThread(() -> {
            if (!isAdded() || vpReels == null) return;
            vpReels.setCurrentItem(safePos, false);
            controlPlayback(safePos);
        });
    }

    /**
     * Instagram-style unlimited scroll. The old version stopped dead once
     * `currentPage >= source.size()` — the feed just ended and swiping up
     * further did nothing. Instagram's Reels tab never does that: once
     * you've gone through what it fetched, it keeps serving reels (from the
     * same pool, recycled) rather than presenting a hard "end of feed".
     * This wraps `sourceCursor` around the source list via modulo so a page
     * is always available, no matter how far the user keeps scrolling.
     */
    private void loadMoreReels() {
        List<ReelModel> source = isFypMode ? allReels : followingReels;
        int size = source.size();
        if (size == 0) return;

        List<ReelModel> nextBatch = new ArrayList<>(Math.min(PAGE_SIZE, size));
        for (int i = 0; i < PAGE_SIZE; i++) {
            nextBatch.add(source.get(sourceCursor));
            sourceCursor = (sourceCursor + 1) % size;
        }
        adapter.addReels(nextBatch);
        currentPage += nextBatch.size();
    }

    private void removeListeners() {
        if (reelsListener != null) {
            FirebaseUtils.getReelsRef().removeEventListener(reelsListener);
            reelsListener = null;
        }
        if (followListener != null) {
            String myUid = safeMyUid();
            if (myUid != null)
                FirebaseUtils.getReelFollowsRef(myUid).removeEventListener(followListener);
            followListener = null;
        }
    }

    // ── v5 accessor: lets ReelPlayerFragment notify predictivePreloader ────────
    public void notifyReelWatched(String reelId, java.util.List<String> tags, String uid) {
        if (predictivePreloader == null) return;
        // Find the matching ReelModel in the current list
        java.util.List<ReelModel> cur = isFypMode ? allReels : followingReels;
        if (cur == null || cur.isEmpty()) return;
        for (ReelModel reel : cur) {
            if (reel.reelId != null && reel.reelId.equals(reelId)) {
                long totalMs = reel.duration > 0 ? reel.duration : 15_000L;
                // Estimate watched duration from playback position exposed by ViewPager2 page selected
                long watchedMs = totalMs; // conservative: credit full watch when notified
                predictivePreloader.recordWatch(reel, watchedMs, totalMs);
                break;
            }
        }
    }

    // ── Playback control ──────────────────────────────────────────────────

    private void controlPlayback(int activePosition) {
        // ROOT FIX: Two guards must BOTH be true before any reel can play:
        // 1. isTabActive  — user is on the Reels tab in MainActivity
        // 2. homeNotVisible — the Home overlay is not covering the reel feed
        //    (user opened Home tab inside Reels, then launched an activity from there
        //     and came back — onStart fires and would otherwise play the reel behind
        //     the Home overlay)
        boolean homeVisible = homeContainer != null
                && homeContainer.getVisibility() == android.view.View.VISIBLE;
        /*
         * ViewPager2 is configured with offscreenPageLimit=1, so only the
         * current page and its immediate neighbours can own live player
         * fragments. Never scan the whole feed (which can contain hundreds of
         * items) on a page change.
         */
        int firstAttached = Math.max(0, activePosition - 1);
        int lastAttached  = Math.min(adapter.getItemCount() - 1, activePosition + 1);
        for (int i = firstAttached; i <= lastAttached; i++) {
            Fragment f = getChildFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(i));
            if (f instanceof ReelPlayerFragment) {
                boolean shouldPlay = isTabActive && !homeVisible && (i == activePosition);
                ((ReelPlayerFragment) f).setUserVisibleHint(shouldPlay);
            }
        }

        // ── Instagram-level N+1 prewarm (thermal-gated) ──────────────────────
        // Instagram approach: only ONE hardware decoder active at a time.
        // N+1 prewarm (ExoPlayer decoder allocation) fires ONLY when device is
        // thermally safe (no throttling, battery OK). On moderate thermal we
        // skip ExoPlayer prewarm entirely — byte preloading via videoPreloader
        // (below in onPageSelected) is all that happens, which is enough for
        // instant play because ExoPlayer reads from the pre-cached bytes.
        //
        // This eliminates the main thermal risk: two concurrent hardware decoders
        // (current reel playing + N+1 prewarmed) is exactly what heated the
        // device. Now at most ONE decoder is ever allocated at a time.
        if (isTabActive && !homeVisible) {
            ReelThermalManager.Level thermal = thermalManager != null
                ? thermalManager.getLevel() : ReelThermalManager.Level.SAFE;
            // Widened to SAFE + LIGHT: Instagram-style instant play on swipe needs
            // the next reel's ExoPlayer already built and buffering *before* the
            // user swipes to it. Gating this purely to SAFE meant that on any
            // everyday LIGHT thermal reading the next reel's player was never
            // prewarmed, so every swipe paid the full "build ExoPlayer from
            // scratch" cost — the visible "plays after a short delay" bug.
            boolean canPrewarmDecoder = (thermal == ReelThermalManager.Level.SAFE
                || thermal == ReelThermalManager.Level.LIGHT);
            if (canPrewarmDecoder) {
                int pos = activePosition + 1;
                if (pos < adapter.getItemCount()) {
                    Fragment nf = getChildFragmentManager()
                        .findFragmentByTag("f" + adapter.getItemId(pos));
                    if (nf instanceof ReelPlayerFragment) {
                        ((ReelPlayerFragment) nf).prewarmPlayer();
                    }
                }
            }
            // On LIGHT/MODERATE thermal: log that we skipped ExoPlayer prewarm
            // (byte preloading still handles smooth playback via cache)
            else {
                android.util.Log.d("ReelsFragment",
                    "ExoPlayer prewarm skipped — thermal=" + thermal + " (byte cache handles it)");
            }
        }
    }

    private void pauseAllReels() {
        if (adapter == null || adapter.getItemCount() == 0 || vpReels == null) return;
        int center = vpReels.getCurrentItem();
        int firstAttached = Math.max(0, center - 1);
        int lastAttached  = Math.min(adapter.getItemCount() - 1, center + 1);
        for (int i = firstAttached; i <= lastAttached; i++) {
            Fragment f = getChildFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(i));
            if (f instanceof ReelPlayerFragment) {
                ((ReelPlayerFragment) f).setUserVisibleHint(false);
            }
        }
    }

    /**
     * Applies the cheap, native RecyclerView settings that are safe for a
     * full-screen video pager. ViewPager2 owns the inner RecyclerView, so this
     * intentionally avoids replacing its PagerSnapHelper or LayoutManager.
     */
    private void configurePagerForVideoScroll() {
        if (vpReels == null) return;
        vpReels.setOverScrollMode(View.OVER_SCROLL_NEVER);
        View inner = vpReels.getChildAt(0);
        if (inner instanceof androidx.recyclerview.widget.RecyclerView) {
            androidx.recyclerview.widget.RecyclerView rv =
                (androidx.recyclerview.widget.RecyclerView) inner;
            rv.setHasFixedSize(true);
            rv.setItemViewCacheSize(1);
            rv.setItemAnimator(null);
            rv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            rv.setNestedScrollingEnabled(false);
        }
    }

    // ── Tab visibility callbacks (called by MainActivity on tab switch) ───────

    /**
     * Called by MainActivity when the user switches TO the Reels tab.
     * Resumes playback of the currently visible reel.
     *
     * FIX #LAZY-REELS: Agar reels abhi tak load nahi hue (pehli baar tab khula),
     * to yahan se loadFypReels() trigger karo. offscreenPageLimit=1 ki wajah se
     * onStart() mein isTabActive=false tha, isliye fetch nahi hua tha.
     */
    public void onTabResumed() {
        isTabActive = true;

        // Pehli baar tab khula — ab fetch karo (lazy load trigger)
        if (allReels.isEmpty()) {
            loadFypReels();
            return;
        }

        // Already loaded — sirf playback resume karo
        boolean homeVisible = homeContainer != null
                && homeContainer.getVisibility() == android.view.View.VISIBLE;
        if (!homeVisible && vpReels != null) {
            controlPlayback(vpReels.getCurrentItem());
        }
    }

    /**
     * Called by MainActivity when the user switches AWAY from the Reels tab.
     * Pauses all reel videos so they do NOT play in the background.
     */
    public void onTabPaused() {
        isTabActive = false;
        pauseAllReels();
    }

    /**
     * Chat-tab docking variant of {@link #onTabPaused()}.
     *
     * Instead of pausing all reels, this transfers the current reel's ExoPlayer
     * surface to the Activity-level mini overlay so playback continues while the
     * user browses chat. All OTHER (off-screen) reels are still paused normally.
     *
     * @param onPlayerReady Callback that receives the ExoPlayer + its original
     *                      PlayerView once the surface handoff is ready.
     *                      Called synchronously if a transferable player exists;
     *                      falls back to a normal pause if not (photo reel, no player).
     */
    @OptIn(markerClass = UnstableApi.class)
    public void onTabPausedForChat(PlayerReadyForDockCallback onPlayerReady) {
        isTabActive = false;

        if (vpReels == null || adapter == null || adapter.getItemCount() == 0) {
            pauseAllReels();
            return;
        }

        int currentPos = vpReels.getCurrentItem();
        ReelPlayerFragment currentFragment = null;

        // Find the currently visible ReelPlayerFragment
        try {
            androidx.fragment.app.Fragment f = getChildFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(currentPos));
            if (f instanceof ReelPlayerFragment) {
                currentFragment = (ReelPlayerFragment) f;
            }
        } catch (Exception ignored) {}

        // Attempt surface transfer for the current fragment
        boolean transferred = false;
        if (currentFragment != null) {
            androidx.media3.exoplayer.ExoPlayer player = currentFragment.getActivePlayer();
            androidx.media3.ui.PlayerView pv            = currentFragment.getPlayerViewForDock();
            if (player != null && pv != null) {
                // Surface will be moved to mini overlay — do NOT pause this fragment.
                // Pause all others.
                // offscreenPageLimit=1 means only the current page and its
                // immediate neighbours can have attached player fragments.
                // Do not walk the entire (potentially very large) feed just
                // to pause views that cannot be alive.
                int firstAttached = Math.max(0, currentPos - 1);
                int lastAttached  = Math.min(adapter.getItemCount() - 1, currentPos + 1);
                for (int i = firstAttached; i <= lastAttached; i++) {
                    if (i == currentPos) continue;
                    try {
                        androidx.fragment.app.Fragment other = getChildFragmentManager()
                            .findFragmentByTag("f" + adapter.getItemId(i));
                        if (other instanceof ReelPlayerFragment) {
                            ((ReelPlayerFragment) other).setUserVisibleHint(false);
                        }
                    } catch (Exception ignored) {}
                }
                // Pass thumbUrl for blurred thumbnail fallback (Feature 6)
                String thumbUrl = (currentFragment.getReel() != null)
                        ? currentFragment.getReel().thumbUrl : null;
                onPlayerReady.onReady(player, pv, thumbUrl);
                transferred = true;
            }
        }

        if (!transferred) {
            // Photo reel, no player, or fragment not found — normal pause
            pauseAllReels();
        }
    }

    /**
     * Advance ViewPager2 to next reel while the mini docked player is showing.
     * Transfers the new reel's ExoPlayer surface to the existing docked player
     * so the user sees the next reel without leaving Chat tab.
     *
     * Called by MainActivity when the user swipes up on the mini player (Feature 4).
     */
    @OptIn(markerClass = UnstableApi.class)
    public void advanceToNextForDockedPlayer(@androidx.annotation.NonNull ReelChatDockedPlayer docked) {
        if (vpReels == null || adapter == null || adapter.getItemCount() == 0) return;

        int current = vpReels.getCurrentItem();
        int next    = current + 1;
        if (next >= adapter.getItemCount()) return; // already at last reel

        // Pause the current fragment (its surface was already taken by mini player)
        try {
            androidx.fragment.app.Fragment old = getChildFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(current));
            if (old instanceof ReelPlayerFragment) {
                ((ReelPlayerFragment) old).setUserVisibleHint(false);
            }
        } catch (Exception ignored) {}

        // Advance ViewPager2 to next position
        vpReels.setCurrentItem(next, true);

        // Wait briefly for the next fragment to initialise its ExoPlayer,
        // then transfer its surface to the docked mini player.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isAdded() || !docked.isShowing()) return;
            try {
                androidx.fragment.app.Fragment f = getChildFragmentManager()
                    .findFragmentByTag("f" + adapter.getItemId(next));
                if (f instanceof ReelPlayerFragment) {
                    ReelPlayerFragment nextFrag = (ReelPlayerFragment) f;
                    androidx.media3.exoplayer.ExoPlayer nextPlayer = nextFrag.getActivePlayer();
                    androidx.media3.ui.PlayerView nextView          = nextFrag.getPlayerViewForDock();
                    String thumbUrl = (nextFrag.getReel() != null)
                            ? nextFrag.getReel().thumbUrl : null;
                    if (nextPlayer != null && nextView != null) {
                        docked.updatePlayer(nextPlayer, nextView, thumbUrl);
                    }
                }
            } catch (Exception ignored) {}
        }, 500); // 500 ms: enough for ExoPlayer to prepare the next item
    }

    /** Callback for onTabPausedForChat — delivers player + view + thumbUrl to MainActivity. */
    @UnstableApi
    public interface PlayerReadyForDockCallback {
        void onReady(androidx.media3.exoplayer.ExoPlayer player,
                     androidx.media3.ui.PlayerView fragmentPlayerView,
                     @androidx.annotation.Nullable String thumbUrl);
    }

    public void advanceToNext() {
        if (vpReels == null) return;
        int next = vpReels.getCurrentItem() + 1;
        if (next < adapter.getItemCount()) vpReels.setCurrentItem(next, true);
    }

    /** Called by ReelPlayerFragment after user blocks a reel owner — remove their reels from feed */
    public void onUserBlocked(String blockedUid) {
        if (blockedUid == null) return;
        blockedUids.add(blockedUid);
        int before = vpReels != null ? vpReels.getCurrentItem() : 0;
        allReels.removeIf(r -> blockedUid.equals(r.uid));
        followingReels.removeIf(r -> blockedUid.equals(r.uid));
        adapter.setReels(isFypMode ? allReels : followingReels);
        // Stay at same position or clamp if at end
        if (vpReels != null) {
            int clamped = Math.min(before, adapter.getItemCount() - 1);
            if (clamped >= 0) vpReels.setCurrentItem(clamped, false);
        }
    }

    public void onReelPlaybackStateChanged(boolean isPlaying) {
        if (reelBottomNav == null) return;
        // Don't animate nav when Home tab is showing
        if (homeContainer != null && homeContainer.getVisibility() == View.VISIBLE) return;

        if (isPlaying) {
            // Hide bottom nav downward
            int navH = reelBottomNav.getHeight() > 0 ? reelBottomNav.getHeight() : dpToPx(60);
            reelBottomNav.animate()
                .translationY(navH).alpha(0f).setDuration(220)
                .withEndAction(() -> reelBottomNav.setVisibility(View.GONE))
                .start();
            // Hide top bar upward
            if (topBar != null) {
                int barH = topBar.getHeight() > 0 ? topBar.getHeight() : dpToPx(60);
                topBar.animate()
                    .translationY(-barH).alpha(0f).setDuration(220)
                    .withEndAction(() -> topBar.setVisibility(View.INVISIBLE))
                    .start();
            }
            // Hide feed indicator (For You underline)
            if (feedIndicator != null) {
                feedIndicator.animate().alpha(0f).setDuration(220)
                    .withEndAction(() -> feedIndicator.setVisibility(View.INVISIBLE))
                    .start();
            }
        } else {
            // Show bottom nav
            reelBottomNav.setVisibility(View.VISIBLE);
            reelBottomNav.animate().translationY(0f).alpha(1f).setDuration(220).start();
            // Show top bar
            if (topBar != null) {
                topBar.setVisibility(View.VISIBLE);
                topBar.animate().translationY(0f).alpha(1f).setDuration(220).start();
            }
            // Show feed indicator
            if (feedIndicator != null) {
                feedIndicator.setVisibility(View.VISIBLE);
                feedIndicator.animate().alpha(1f).setDuration(220).start();
            }
        }
    }

    public void prependNewReel(ReelModel reel) {
        allReels.add(0, reel);
        adapter.prependReel(reel);
        showEmpty(false, null, null);
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (progressReels != null)
                progressReels.setVisibility(show ? View.VISIBLE : View.GONE);
        });
    }

    private void showEmpty(boolean show, @Nullable String title, @Nullable String subtitle) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (layoutEmpty != null) {
                layoutEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show && title != null) {
                    TextView tvTitle = layoutEmpty.findViewById(R.id.tv_empty_title);
                    TextView tvSub   = layoutEmpty.findViewById(R.id.tv_empty_subtitle);
                    if (tvTitle != null) tvTitle.setText(title);
                    if (tvSub   != null) tvSub.setText(subtitle != null ? subtitle : "");
                }
            }
            if (vpReels != null)
                vpReels.setVisibility(show ? View.GONE : View.VISIBLE);
        });
    }

    @Nullable
    private String safeMyUid() {
        try {
            String uid = FirebaseUtils.getCurrentUid();
            return (uid != null && !uid.isEmpty()) ? uid : null;
        }
        catch (Exception e) { return null; }
    }

    private int dpToPx(int dp) {
        if (getContext() == null) return dp * 3;
        return (int)(dp * getContext().getResources().getDisplayMetrics().density);
    }
    // ── Blocked users ─────────────────────────────────────────────────────
    private void loadBlockedUids() {
        String myUid = safeMyUid();
        if (myUid == null) return;
        com.callx.app.utils.FirebaseUtils.getBlocksRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    blockedUids.clear();
                    for (DataSnapshot ds : snap.getChildren())
                        if (ds.getKey() != null) blockedUids.add(ds.getKey());
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

}