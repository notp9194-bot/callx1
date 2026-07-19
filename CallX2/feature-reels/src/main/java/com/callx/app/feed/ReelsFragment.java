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
import com.callx.app.player.ReelOfflineManager;
import com.callx.app.feed.ReelsAdapter;
import com.callx.app.models.ReelModel;
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
    private ValueEventListener    reelsListener;
    private ValueEventListener    followListener;
    private int                   currentPage   = 0;
    private boolean               loading       = false;
    private int                   savedPosition = 0;

    private ReelVideoPreloader     videoPreloader;
    private ReelThumbnailPreloader thumbPreloader;
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
        // ── Instagram-style instant playback ──────────────────────────────
        // offscreenPageLimit=3 → N-1, N, N+1, N+2 fragments all kept alive.
        // Combined with preparePlayerSilently() in ReelPlayerFragment, the next
        // 2 reels are always pre-prepared → zero buffering spinner on swipe.
        vpReels.setOffscreenPageLimit(3);

        ReelCacheManager.init(requireContext());
        videoPreloader = new ReelVideoPreloader(requireContext());
        // Wire preloader to existing fragments if any (e.g. after config change)
        wirePreloaderToVisibleFragment();
        thumbPreloader = new ReelThumbnailPreloader(requireContext());
        // v5: Init predictive preloader + offline manager
        predictivePreloader = new ReelPredictivePreloader(requireContext());
        offlineManager      = ReelOfflineManager.get(requireContext());

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
            public void onPageSelected(int position) {
                controlPlayback(position);
                int reelIndex = adapter.toReelIndex(position);
                if (reelIndex >= currentPage - 3) loadMoreReels();
                List<ReelModel> cur = isFypMode ? allReels : followingReels;
                if (videoPreloader != null) videoPreloader.preloadFrom(cur, reelIndex);
                // Sync preloader to newly visible fragment
                wirePreloaderToCurrentFragment(position);
                if (thumbPreloader != null) thumbPreloader.preloadFrom(cur, reelIndex);
                // v5/v6: Record watch event + drive predictive preload order
                // (velocity-adaptive: fast flicks shrink the lookahead window/bytes)
                if (predictivePreloader != null && reelIndex < cur.size()) {
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
        // manually apply white tint to all non-creator menu items here.
        applyWhiteTintToNavIcons();

        // FIX #3: Load current user's avatar and set it as the Creator tab icon
        // post() defers until after the view is fully laid out so menu items are ready
        reelBottomNav.post(() -> loadCreatorAvatar());
        reelBottomNav.post(() -> loadNotificationBadge());

        return v;
    }

    // ── FIX #3: Creator tab avatar ────────────────────────────────────────
    /**
     * Loads the current user's profile photo via Glide, converts it to a circular
     * BitmapDrawable, and sets it as the icon for the Creator tab menu item.
     * Called once from onCreateView and refreshed on each onStart.
     */
    /**
     * Applies white tint to all bottom nav icons except the Creator tab.
     * Required because itemIconTint is set to @null in XML so that the creator
     * avatar bitmap is not washed out with a white overlay.
     */
    private void applyWhiteTintToNavIcons() {
        if (reelBottomNav == null || getContext() == null) return;
        android.content.res.ColorStateList white =
            android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(requireContext(), com.callx.app.reels.R.color.white));
        int[] nonCreatorIds = {
            R.id.reel_nav_home,
            R.id.reel_nav_feed,
            R.id.reel_nav_create,
            R.id.reel_nav_notifications
        };
        for (int id : nonCreatorIds) {
            android.view.MenuItem item = reelBottomNav.getMenu().findItem(id);
            if (item != null && item.getIcon() != null) {
                item.getIcon().setTintList(white);
            }
        }
        // Creator tab: clear any tint so the avatar shows in true color
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

        // Inject HomeFragment once; reuse it on subsequent taps
        if (getChildFragmentManager().findFragmentByTag("home_fragment") == null) {
            getChildFragmentManager()
                .beginTransaction()
                .replace(R.id.home_container, new HomeFragment(), "home_fragment")
                .commitAllowingStateLoss();
        }
        pauseAllReels();
    }

    /**
     * Hides the HomeFragment overlay and resumes reel playback.
     * Called when user taps the Reels tab.
     */
    private void hideHomeTab() {
        if (homeContainer != null) homeContainer.setVisibility(View.GONE);
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

        if (videoPreloader != null) { videoPreloader.shutdown(); videoPreloader = null; }
        thumbPreloader = null;
        super.onDestroyView();
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
        // Refresh icon tints (white for all, null for creator) then reload avatar
        applyWhiteTintToNavIcons();
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
        pauseAllReels();
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
                allReels.clear();
                for (DataSnapshot s : snap.getChildren()) {
                    ReelModel reel = s.getValue(ReelModel.class);
                    if (reel != null) {
                        if (reel.reelId == null) reel.reelId = s.getKey();
                        allReels.add(reel);
                    }
                }
                Collections.sort(allReels, (a, b) ->
                    Float.compare(b.trendingScore(), a.trendingScore()));

                // Remove blocked users from feed
                allReels.removeIf(r -> r.uid != null && blockedUids.contains(r.uid));

                showLoading(false);
                loading = false;

                if (isFypMode) {
                    if (allReels.isEmpty()) showEmpty(true, "No Reels Yet", "Be the first to share!");
                    else { showEmpty(false, null, null); if (savedPosition > 0) { renderPageAtPosition(allReels, savedPosition); } else { renderPage(allReels); } }
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
        savedPosition = 0;
        if (videoPreloader != null) videoPreloader.preloadFrom(source.subList(0, end), 0);
        if (thumbPreloader != null) thumbPreloader.preloadFrom(source.subList(0, end), 0);
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
        }
        int safePos = Math.min(position, adapter.getItemCount() - 1);
        if (getActivity() != null) getActivity().runOnUiThread(() -> {
            if (!isAdded() || vpReels == null) return;
            vpReels.setCurrentItem(safePos, false);
            controlPlayback(safePos);
        });
    }

    private void loadMoreReels() {
        List<ReelModel> source = isFypMode ? allReels : followingReels;
        if (currentPage >= source.size()) return;
        int end = Math.min(currentPage + PAGE_SIZE, source.size());
        adapter.addReels(source.subList(currentPage, end));
        currentPage = end;
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
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Fragment f = getChildFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(i));
            if (f instanceof ReelPlayerFragment) {
                boolean shouldPlay = isTabActive && !homeVisible && (i == activePosition);
                ((ReelPlayerFragment) f).setUserVisibleHint(shouldPlay);
            }
        }
    }

    private void pauseAllReels() {
        for (int i = 0; i < adapter.getItemCount(); i++) {
            Fragment f = getChildFragmentManager()
                .findFragmentByTag("f" + adapter.getItemId(i));
            if (f instanceof ReelPlayerFragment) {
                ((ReelPlayerFragment) f).setUserVisibleHint(false);
            }
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