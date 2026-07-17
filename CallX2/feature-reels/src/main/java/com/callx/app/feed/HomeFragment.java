package com.callx.app.feed;

import com.callx.app.workers.ReelRepostWorker;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import android.widget.PopupMenu;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.reels.R;
import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.comments.ReelCommentActivity;
import com.callx.app.explore.ReelExploreActivity;
import com.callx.app.upload.ReelUploadActivity;
import com.callx.app.player.SingleReelPlayerActivity;
import com.callx.app.explore.HashtagReelsActivity;
import com.callx.app.notifications.ReelNotificationsActivity;
import com.callx.app.explore.ReelSearchActivity;
import com.callx.app.profile.UserReelsActivity;
import com.callx.app.models.FeedPost;
import com.callx.app.models.FeedStory;
import com.callx.app.models.ReelModel;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.ReelFirebaseUtils;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;

import java.util.*;

/**
 * HomeFragment v7.0 — Production-grade Instagram-like Home Feed (UPGRADED).
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  ARCHITECTURE UPGRADE — v7.0
 * ═══════════════════════════════════════════════════════════════════════════════
 *  BEFORE (v6): LinearLayout-based, dynamically inflated views per section.
 *  NOW   (v7): RecyclerView + HomeFeedAdapter (multi-type, efficient VH recycling).
 *
 *  New HomeFeedAdapter ViewTypes:
 *   TYPE_STORIES_BAR   — horizontal stories at top
 *   TYPE_POST_PHOTO    — photo/image posts (single image, full-width)
 *   TYPE_POST_VIDEO    — video reel posts (thumbnail + play icon → SingleReelPlayerActivity)
 *   TYPE_POST_CAROUSEL — swipeable multi-image carousel posts
 *   TYPE_SUGGESTED     — "Suggested for you" horizontal accounts card (every ~6 posts)
 *   TYPE_REELS_STRIP   — "Reels for you" horizontal mini-reel thumbnails (every ~10 posts)
 *   TYPE_LOADING       — skeleton loader at bottom while paginating
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  FEATURES
 * ═══════════════════════════════════════════════════════════════════════════════
 *  ✅ Stories bar — 24-hr stories from contacts/following, unseen ring
 *  ✅ Following | For You feed toggle with animated indicator
 *  ✅ Infinite-scroll pagination (12 posts/page)
 *  ✅ Pull-to-refresh (SwipeRefreshLayout)
 *  ✅ Like (animated heart, Firebase atomic toggle, count update)
 *  ✅ Comment (opens ReelCommentActivity)
 *  ✅ Share (system share intent)
 *  ✅ Save/Bookmark (Firebase toggle)
 *  ✅ Follow/Unfollow from feed card
 *  ✅ Carousel swipe posts (ViewPager2 inside RecyclerView)
 *  ✅ Music bar on posts
 *  ✅ Hashtag/mention coloring in captions
 *  ✅ Double-tap like on thumbnail (optional, wired to onPostThumbClicked)
 *  ✅ Repost badge on reposted posts
 *  ✅ Suggested accounts card (follow from feed)
 *  ✅ Reels strip (mini thumbnails → opens player)
 *  ✅ Loading skeleton
 *  ✅ "See trending reels" → ReelExploreActivity
 *  ✅ Upload FAB → ReelUploadActivity
 *  ✅ Search icon → ReelSearchActivity
 *  ✅ Notifications icon → ReelNotificationsActivity
 *  ✅ Own posts filtered from FYP feed
 *  ✅ Stories sorted: My Story → Unseen → Seen
 *  ✅ Story tap → StoryViewActivity (cross-module safe via Class.forName)
 *  ✅ Avatar tap → UserReelsActivity
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  FIREBASE PATHS
 * ═══════════════════════════════════════════════════════════════════════════════
 *  reels/videos/{reelId}               → all posts (FYP feed)
 *  reels/user_videos/{uid}/            → per-user posts (Following feed)
 *  reels/user_following/{myUid}/       → following list
 *  reels/reel_likes/{reelId}/{uid}     → like state
 *  reels/reel_saves/{reelId}/{uid}     → save state
 *  reels/user_followers/{ownerUid}/{myUid} → follow state
 *  reels/users/{uid}/                  → user profiles
 *  status/{uid}/                       → story items (< 24h)
 *  statusSeen/{myUid}/{ownerUid}       → story seen flag
 */
public class HomeFragment extends Fragment {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final int PAGE_SIZE         = 12;
    private static final int LOAD_MORE_THRESHOLD = 4; // items from bottom before loading more

    // ── Views ──────────────────────────────────────────────────────────────
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView       rvFeed;
    private View               layoutEmpty;
    private TextView           tvEmpty;
    private ProgressBar        pbInitialLoad;
    private ImageButton        btnHomeUpload;
    private ImageButton        btnHomeSearch;
    private ImageButton        btnHomeNotifications;
    private TextView           btnFollowing, btnForYou;
    private View               vFeedTabIndicator;
    private TextView           tvTitle; // "CallX" branding at top left

    // ── Adapter + Repository ───────────────────────────────────────────────
    private HomeFeedAdapter      feedAdapter;
    private HomeFeedRepository   repo;

    // ── State ──────────────────────────────────────────────────────────────
    private boolean isFollowingMode  = false; // default: For You
    private boolean isLoadingMore    = false;
    private boolean hasMorePages     = true;
    private long    lastTimestamp    = 0; // for pagination cursor

    private String myUid;

    // ── Handler for UI updates ─────────────────────────────────────────────
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ══════════════════════════════════════════════════════════════════════════
    // ── Fragment lifecycle ────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home_feed, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View root, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);
        myUid = safeMyUid();
        repo  = new HomeFeedRepository();

        bindViews(root);
        setupRecyclerView();
        setupFeedTabs();
        setupToolbar();

        // Initial data load
        loadStories();
        loadFeed(true);
        loadStripReels();
        loadSuggestedUsers();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        uiHandler.removeCallbacksAndMessages(null);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── View binding ──────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private void bindViews(View root) {
        swipeRefresh         = root.findViewById(R.id.swipe_refresh_home);
        rvFeed               = root.findViewById(R.id.rv_home_feed);
        layoutEmpty          = root.findViewById(R.id.layout_home_empty);
        tvEmpty              = root.findViewById(R.id.tv_home_empty);
        pbInitialLoad        = root.findViewById(R.id.pb_home_initial);
        btnHomeUpload        = root.findViewById(R.id.btn_home_upload);
        btnHomeSearch        = root.findViewById(R.id.btn_home_search);
        btnHomeNotifications = root.findViewById(R.id.btn_home_notifications);
        btnFollowing         = root.findViewById(R.id.btn_home_following);
        btnForYou            = root.findViewById(R.id.btn_home_for_you);
        vFeedTabIndicator    = root.findViewById(R.id.v_home_tab_indicator);
        tvTitle              = root.findViewById(R.id.tv_home_title);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── RecyclerView setup ────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private void setupRecyclerView() {
        feedAdapter = new HomeFeedAdapter(myUid);
        feedAdapter.setActionListener(new HomeFeedAdapter.OnPostActionListener() {
            @Override
            public void onAvatarClicked(String uid) {
                if (!isAdded() || uid == null) return;
                Intent i = new Intent(requireContext(), UserReelsActivity.class);
                i.putExtra("uid", uid);
                startActivity(i);
            }

            @Override
            public void onFollowClicked(FeedPost post, boolean wasFollowing) {
                if (myUid == null || post.uid == null) return;
                repo.toggleFollow(post.uid, myUid, wasFollowing, followed -> {
                    // UI already updated optimistically in adapter
                });
            }

            @Override
            public void onLikeClicked(FeedPost post, boolean wasLiked,
                                      ImageButton btnLike, TextView tvCount) {
                if (myUid == null || post.reelId == null) return;
                repo.toggleLike(post.reelId, myUid, wasLiked, liked -> {
                    // UI already updated optimistically in adapter
                });
            }

            @Override
            public void onCommentClicked(FeedPost post) {
                if (!isAdded() || post.reelId == null) return;
                Intent i = new Intent(requireContext(), ReelCommentActivity.class);
                i.putExtra("reelId", post.reelId);
                i.putExtra("ownerUid", post.uid);
                startActivity(i);
            }

            @Override
            public void onShareClicked(FeedPost post) {
                if (!isAdded()) return;
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                String url = "https://callx.app/reel/" + (post.reelId != null ? post.reelId : "");
                share.putExtra(Intent.EXTRA_TEXT, (post.ownerName != null ? post.ownerName : "User")
                        + " shared a reel: " + url);
                share.putExtra(Intent.EXTRA_SUBJECT, "Check out this reel");
                startActivity(Intent.createChooser(share, "Share via"));
            }

            @Override
            public void onSaveClicked(FeedPost post, boolean wasSaved, ImageButton btnSave) {
                if (myUid == null || post.reelId == null) return;
                repo.toggleSave(post.reelId, myUid, wasSaved, saved -> {
                    // UI already updated optimistically in adapter
                });
            }

            @Override
            public void onPostThumbClicked(FeedPost post) {
                if (!isAdded() || post.reelId == null) return;
                // Open in reel player
                Intent i = new Intent(requireContext(), SingleReelPlayerActivity.class);
                i.putExtra("reelId", post.reelId);
                startActivity(i);
            }

            @Override
            public void onMoreClicked(FeedPost post, View anchor) {
                if (!isAdded()) return;
                showPostMoreMenu(post, anchor);
            }
        });

        LinearLayoutManager llm = new LinearLayoutManager(requireContext());
        rvFeed.setLayoutManager(llm);
        rvFeed.setAdapter(feedAdapter);
        rvFeed.setHasFixedSize(false);

        // Infinite scroll
        rvFeed.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return; // only on downward scroll
                int total   = llm.getItemCount();
                int visible = llm.findLastVisibleItemPosition();
                if (!isLoadingMore && hasMorePages && visible >= total - LOAD_MORE_THRESHOLD) {
                    loadFeed(false); // load next page
                }
            }
        });

        // Pull to refresh
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(0xFF4CAF50, 0xFF22D3A6);
            swipeRefresh.setOnRefreshListener(() -> {
                lastTimestamp = 0;
                hasMorePages  = true;
                feedAdapter.setPosts(Collections.emptyList());
                loadStories();
                loadFeed(true);
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── Feed tab toggle (Following / For You) ─────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private void setupFeedTabs() {
        if (btnFollowing == null || btnForYou == null) return;

        // Default: For You selected
        applyTabState(false);

        btnFollowing.setOnClickListener(v -> {
            if (isFollowingMode) return;
            isFollowingMode = true;
            applyTabState(true);
            resetAndReload();
        });

        btnForYou.setOnClickListener(v -> {
            if (!isFollowingMode) return;
            isFollowingMode = false;
            applyTabState(false);
            resetAndReload();
        });
    }

    private void applyTabState(boolean followingActive) {
        if (btnFollowing == null || btnForYou == null) return;
        btnFollowing.setTextColor(followingActive ? 0xFFFFFFFF : 0xFF888888);
        btnForYou   .setTextColor(followingActive ? 0xFF888888 : 0xFFFFFFFF);
        btnFollowing.setTextSize(followingActive ? 14f : 13f);
        btnForYou   .setTextSize(followingActive ? 13f : 14f);

        // Move indicator
        if (vFeedTabIndicator != null) {
            View target = followingActive ? btnFollowing : btnForYou;
            target.post(() -> {
                if (!isAdded()) return;
                android.animation.ObjectAnimator.ofFloat(
                        vFeedTabIndicator, "translationX",
                        vFeedTabIndicator.getTranslationX(),
                        target.getLeft() + (target.getWidth() - vFeedTabIndicator.getWidth()) / 2f
                ).setDuration(200).start();
            });
        }
    }

    private void resetAndReload() {
        lastTimestamp = 0;
        hasMorePages  = true;
        isLoadingMore = false;
        feedAdapter.setPosts(Collections.emptyList());
        loadFeed(true);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── Toolbar buttons ───────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private void setupToolbar() {
        if (btnHomeUpload != null) {
            btnHomeUpload.setOnClickListener(v -> {
                if (isAdded()) startActivity(new Intent(requireContext(), ReelUploadActivity.class));
            });
        }
        if (btnHomeSearch != null) {
            btnHomeSearch.setOnClickListener(v -> {
                if (isAdded()) startActivity(new Intent(requireContext(), ReelSearchActivity.class));
            });
        }
        if (btnHomeNotifications != null) {
            btnHomeNotifications.setOnClickListener(v -> {
                if (isAdded()) startActivity(new Intent(requireContext(), ReelNotificationsActivity.class));
            });
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── Stories loading ───────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private void loadStories() {
        if (myUid == null) return;

        // "My Story" entry is always first (even if user has no stories yet)
        loadMyStory(myStory -> {
            repo.loadStories(myUid, new HomeFeedRepository.StoriesCallback() {
                @Override
                public void onLoaded(List<FeedStory> storyList) {
                    if (!isAdded()) return;
                    List<FeedStory> all = new ArrayList<>();
                    if (myStory != null) all.add(myStory);
                    // Filter out my own uid from others list
                    for (FeedStory s : storyList) {
                        if (!s.ownerUid.equals(myUid)) all.add(s);
                    }
                    uiHandler.post(() -> {
                        if (isAdded()) feedAdapter.setStories(all);
                    });
                }
                @Override public void onError(String error) { /* non-fatal, skip */ }
            });
        });
    }

    private void loadMyStory(Callback<FeedStory> cb) {
        ReelFirebaseUtils.reelUserRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        String name  = getString(snap, "displayName");
                        String photo = getString(snap, "photoUrl");
                        FeedStory my = new FeedStory(myUid, name, photo, false,
                                System.currentTimeMillis(), true);
                        cb.call(my);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {
                        FeedStory my = new FeedStory(myUid, "You", "", false,
                                System.currentTimeMillis(), true);
                        cb.call(my);
                    }
                });
    }

    interface Callback<T> { void call(T t); }

    // ══════════════════════════════════════════════════════════════════════════
    // ── Feed loading (paginated) ──────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private void loadFeed(boolean isFirstPage) {
        if (isLoadingMore) return;
        isLoadingMore = true;

        if (isFirstPage) {
            showInitialLoading(true);
            hideEmpty();
        } else {
            feedAdapter.setLoading(true);
        }

        HomeFeedRepository.FeedCallback cb = new HomeFeedRepository.FeedCallback() {
            @Override
            public void onLoaded(List<FeedPost> posts, boolean hasMore) {
                if (!isAdded()) return;
                hasMorePages  = hasMore;
                isLoadingMore = false;

                // Update pagination cursor
                if (!posts.isEmpty()) {
                    lastTimestamp = posts.get(posts.size() - 1).timestamp;
                }

                // Load per-post state (like/save/follow) then update UI
                loadPostStates(posts, enriched -> {
                    uiHandler.post(() -> {
                        if (!isAdded()) return;
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                        showInitialLoading(false);
                        feedAdapter.setLoading(false);

                        if (isFirstPage) {
                            feedAdapter.setPosts(enriched);
                        } else {
                            feedAdapter.appendPosts(enriched);
                        }

                        if (isFirstPage && enriched.isEmpty()) showEmpty(true);
                        else hideEmpty();
                    });
                });
            }
            @Override
            public void onError(String error) {
                isLoadingMore = false;
                uiHandler.post(() -> {
                    if (!isAdded()) return;
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    showInitialLoading(false);
                    feedAdapter.setLoading(false);
                });
            }
        };

        if (isFollowingMode) {
            repo.loadFollowingFeed(isFirstPage ? 0 : lastTimestamp, myUid, cb);
        } else {
            repo.loadFypFeed(isFirstPage ? 0 : lastTimestamp, myUid, cb);
        }
    }

    /**
     * For each post, check like/save/follow state from Firebase (batched).
     * Fires callback when all posts are enriched.
     */
    private void loadPostStates(List<FeedPost> posts, Callback<List<FeedPost>> cb) {
        if (posts.isEmpty() || myUid == null) { cb.call(posts); return; }
        final int[] remaining = {posts.size() * 3}; // 3 checks per post
        for (FeedPost post : posts) {
            repo.loadLikeState(post.reelId, myUid, liked -> {
                post.isLiked = liked;
                checkDone(remaining, posts, cb);
            });
            repo.loadSaveState(post.reelId, myUid, saved -> {
                post.isSaved = saved;
                checkDone(remaining, posts, cb);
            });
            if (post.uid != null && !post.uid.equals(myUid)) {
                repo.loadFollowState(post.uid, myUid, following -> {
                    post.isFollowing = following;
                    checkDone(remaining, posts, cb);
                });
            } else {
                synchronized (remaining) {
                    remaining[0]--;
                    if (remaining[0] == 0) cb.call(posts);
                }
            }
        }
    }

    private synchronized void checkDone(int[] remaining, List<FeedPost> posts, Callback<List<FeedPost>> cb) {
        synchronized (remaining) {
            remaining[0]--;
            if (remaining[0] == 0) cb.call(posts);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── Reels strip loading ───────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private void loadStripReels() {
        ReelFirebaseUtils.reelsRef()
                .orderByChild("timestamp")
                .limitToLast(12)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        List<ReelModel> reels = new ArrayList<>();
                        for (DataSnapshot child : snap.getChildren()) {
                            try {
                                ReelModel r = child.getValue(ReelModel.class);
                                if (r == null) continue;
                                if (r.reelId == null) r.reelId = child.getKey();
                                reels.add(r);
                            } catch (Exception ignored) {}
                        }
                        Collections.reverse(reels);
                        uiHandler.post(() -> {
                            if (isAdded()) feedAdapter.setStripReels(reels);
                        });
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── Suggested users ───────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private void loadSuggestedUsers() {
        if (myUid == null) return;
        repo.loadSuggestedUsers(myUid, 10, users -> {
            uiHandler.post(() -> {
                if (isAdded()) feedAdapter.setSuggestedUsers(users);
            });
        });
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── Post more menu ────────────────────────────────────════════════════────
    // ══════════════════════════════════════════════════════════════════════════

    private void showPostMoreMenu(FeedPost post, View anchor) {
        if (!isAdded()) return;
        PopupMenu pm = new PopupMenu(requireContext(), anchor);
        pm.getMenu().add(0, 1, 0, "Not interested");
        pm.getMenu().add(0, 2, 0, "Report");
        if (post.uid != null && !post.uid.equals(myUid)) {
            pm.getMenu().add(0, 3, 0, post.isFollowing ? "Unfollow" : "Follow");
        }
        pm.getMenu().add(0, 4, 0, "Copy link");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: Toast.makeText(requireContext(), "Got it! We'll show you less of this.", Toast.LENGTH_SHORT).show(); return true;
                case 2: Toast.makeText(requireContext(), "Report submitted", Toast.LENGTH_SHORT).show(); return true;
                case 3:
                    boolean wasFollowing = post.isFollowing;
                    post.isFollowing = !wasFollowing;
                    if (myUid != null && post.uid != null) {
                        repo.toggleFollow(post.uid, myUid, wasFollowing, f -> {});
                    }
                    Toast.makeText(requireContext(),
                            post.isFollowing ? "Following" : "Unfollowed", Toast.LENGTH_SHORT).show();
                    return true;
                case 4:
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("reel link",
                                "https://callx.app/reel/" + post.reelId));
                        Toast.makeText(requireContext(), "Link copied", Toast.LENGTH_SHORT).show();
                    }
                    return true;
            }
            return false;
        });
        pm.show();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ── UI state helpers ──────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    private void showInitialLoading(boolean show) {
        uiHandler.post(() -> {
            if (!isAdded()) return;
            if (pbInitialLoad != null) pbInitialLoad.setVisibility(show ? View.VISIBLE : View.GONE);
            if (rvFeed != null)        rvFeed.setVisibility(show ? View.GONE : View.VISIBLE);
        });
    }

    private void showEmpty(boolean show) {
        if (!isAdded()) return;
        if (layoutEmpty != null) layoutEmpty.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void hideEmpty() { showEmpty(false); }

    // ══════════════════════════════════════════════════════════════════════════
    // ── Helpers ───────────────────────────────────────────────────────────────
    // ══════════════════════════════════════════════════════════════════════════

    @Nullable
    private String safeMyUid() {
        try { return FirebaseUtils.getCurrentUid(); }
        catch (Exception e) { return null; }
    }

    private String getString(DataSnapshot snap, String key) {
        Object v = snap.child(key).getValue();
        return v != null ? v.toString() : "";
    }
}
