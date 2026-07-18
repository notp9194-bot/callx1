package com.callx.app.feed;

import com.callx.app.workers.ReelRepostWorker;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
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
import com.callx.app.models.ReelModel;
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

    private boolean isFollowingMode = true;

    // Tracks which ownerUids have at least one unseen status item for this viewer
    private final Set<String> unseenOwnerUids = new HashSet<>();

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
        setupListeners();
        loadAllSections();
        return v;
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
    }

    private void setupListeners() {
        swipeRefresh.setColorSchemeResources(R.color.brand_primary);
        swipeRefresh.setOnRefreshListener(() -> {
            unseenOwnerUids.clear();
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
    }

    private void switchFeedMode(boolean following) {
        isFollowingMode = following;
        updateFeedToggleUI();
        if (containerFeed != null) containerFeed.removeAllViews();
        showFeedLoading(true);
        showFeedEmpty(false);
        loadFeed();
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

                        boolean hasReelStory = false;
                        for (DataSnapshot s : statusSnap.getChildren()) {
                            Long ts = s.child("timestamp").getValue(Long.class);
                            if (ts == null || ts <= cutoff) continue;
                            hasActive = true;
                            if (mySeenForOwner == null || !mySeenForOwner.contains(s.getKey())) {
                                allSeen = false; // at least one unseen
                            }
                            // ★ Check for reel_story type — triggers gradient ring
                            String type = s.child("type").getValue(String.class);
                            if ("reel_story".equals(type)) hasReelStory = true;
                        }

                        if (hasActive) {
                            collected.add(new StoryEntry(uid, name, photo, !allSeen, hasReelStory));
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

            // ★ Gradient ring for reel_story type (Instagram-style)
            ImageView ivGradientRing = storyView.findViewById(R.id.iv_reel_story_gradient_ring);

            if (entry.hasReelStory) {
                // Show gradient sweep ring, with a white gap between ring and avatar (Instagram-style)
                if (ivGradientRing != null) ivGradientRing.setVisibility(View.VISIBLE);
                avatar.setBorderColor(0xFFFFFFFF);
                avatar.setBorderWidth(dpToPx(3));
                if (ivSeenRing != null) ivSeenRing.setVisibility(View.GONE);
            } else if (entry.hasUnseen) {
                // Brand color ring for unseen WhatsApp-style status
                if (ivGradientRing != null) ivGradientRing.setVisibility(View.GONE);
                avatar.setBorderColor(getResources().getColor(R.color.brand_primary, null));
                avatar.setBorderWidth(dpToPx(3));
                if (ivSeenRing != null) ivSeenRing.setVisibility(View.GONE);
            } else {
                // Gray ring for all-seen status
                if (ivGradientRing != null) ivGradientRing.setVisibility(View.GONE);
                avatar.setBorderColor(0xFF666666);
                avatar.setBorderWidth(dpToPx(2));
                if (ivSeenRing != null) {
                    ivSeenRing.setVisibility(View.VISIBLE);
                    ivSeenRing.setColorFilter(0xFF666666, android.graphics.PorterDuff.Mode.SRC_IN);
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
                .limitToLast(20)
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
                        posts.sort((a, b) -> Float.compare(b.trendingScore(), a.trendingScore()));
                        renderFeedPosts(posts, uid);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        showFeedLoading(false);
                        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                    }
                });
        }
    }

    private void loadReelsForFeed(Set<String> followedUids, String myUid) {
        FirebaseUtils.getReelsRef()
            .orderByChild("timestamp")
            .limitToLast(50)
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
                    posts.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                    renderFeedPosts(posts, myUid);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    showFeedLoading(false);
                    if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                }
            });
    }

    private void renderFeedPosts(List<ReelModel> posts, String myUid) {
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
                                renderFeedPostsWithState(posts, likedIds, savedIds, myUid);
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {
                                renderFeedPostsWithState(posts, new HashSet<>(), savedIds, myUid);
                            }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    renderFeedPostsWithState(posts, new HashSet<>(), new HashSet<>(), myUid);
                }
            });
        } else {
            renderFeedPostsWithState(posts, new HashSet<>(), new HashSet<>(), null);
        }
    }

    private void renderFeedPostsWithState(List<ReelModel> posts, Set<String> likedIds,
                                           Set<String> savedIds, String myUid) {
        if (!isAdded() || getContext() == null) return;
        requireActivity().runOnUiThread(() -> {
            if (containerFeed == null || !isAdded()) return;
            containerFeed.removeAllViews();
            int count = Math.min(posts.size(), 10);
            for (int i = 0; i < count; i++) {
                addFeedPostCard(posts.get(i), likedIds, savedIds, myUid);
            }
        });
    }

    private void addFeedPostCard(ReelModel reel, Set<String> likedIds,
                                  Set<String> savedIds, String myUid) {
        if (!isAdded() || getContext() == null || containerFeed == null) return;
        View card = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_home_feed_post, containerFeed, false);

        CircleImageView avatar   = card.findViewById(R.id.iv_post_avatar);
        TextView tvOwner         = card.findViewById(R.id.tv_post_owner);
        TextView tvTime          = card.findViewById(R.id.tv_post_time);
        ImageView ivThumb        = card.findViewById(R.id.iv_post_thumb);
        ImageView ivVideoBadge   = card.findViewById(R.id.iv_video_badge);
        TextView tvCaption       = card.findViewById(R.id.tv_post_caption);
        TextView tvLikes         = card.findViewById(R.id.tv_post_likes);
        TextView tvComments      = card.findViewById(R.id.tv_post_comments);
        TextView tvReposts       = card.findViewById(R.id.tv_post_reposts);
        ImageButton btnLike      = card.findViewById(R.id.btn_post_like);
        ImageButton btnComment   = card.findViewById(R.id.btn_post_comment);
        ImageButton btnRepost    = card.findViewById(R.id.btn_post_repost);
        ImageButton btnSave      = card.findViewById(R.id.btn_post_save);

        tvOwner.setText(reel.ownerName != null ? "@" + reel.ownerName : "@user");
        tvTime.setText(formatAgo(reel.timestamp));
        String captionText = reel.caption != null ? reel.caption : "";
        tvCaption.setText(captionText);
        if (captionText.contains("#")) {
            android.text.SpannableString spannable = new android.text.SpannableString(captionText);
            java.util.regex.Pattern hp = java.util.regex.Pattern.compile("#(\\w+)");
            java.util.regex.Matcher hm = hp.matcher(captionText);
            while (hm.find()) {
                final String tag = hm.group(1);
                final int hs = hm.start(), he = hm.end();
                spannable.setSpan(new android.text.style.ClickableSpan() {
                    @Override public void onClick(@NonNull android.view.View w) {
                        if (!isAdded() || getContext() == null || tag == null) return;
                        Intent hi = new Intent(getContext(), HashtagReelsActivity.class);
                        hi.putExtra("hashtag", tag);
                        startActivity(hi);
                    }
                    @Override public void updateDrawState(@NonNull android.text.TextPaint ds) {
                        ds.setColor(0xFF00C6FF); ds.setUnderlineText(false);
                    }
                }, hs, he, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            tvCaption.setText(spannable);
            tvCaption.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
        tvLikes.setText(formatCount(reel.likesCount));
        tvComments.setText(formatCount(reel.commentsCount));
        tvReposts.setText(formatCount(reel.repostCount));

        // Video posts show play icon
        if (ivVideoBadge != null) ivVideoBadge.setVisibility(View.VISIBLE);

        // ── Liked state ──
        final boolean[] isLiked = {reel.reelId != null && likedIds.contains(reel.reelId)};
        if (btnLike != null) {
            btnLike.setImageResource(isLiked[0]
                ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
        }

        // ── Saved state ──
        final boolean[] isSaved = {reel.reelId != null && savedIds.contains(reel.reelId)};
        if (btnSave != null) {
            btnSave.setImageResource(isSaved[0]
                ? R.drawable.ic_bookmark_filled : R.drawable.ic_bookmark);
        }

        if (reel.thumbUrl != null && !reel.thumbUrl.isEmpty()) {
            Glide.with(requireContext()).load(reel.thumbUrl)
                .override(720, 720)
                .centerCrop().placeholder(R.drawable.ic_reels).into(ivThumb);
        }
        if (reel.ownerPhoto != null && !reel.ownerPhoto.isEmpty()) {
            Glide.with(requireContext()).load(reel.ownerPhoto)
                .apply(RequestOptions.circleCropTransform())
                .placeholder(R.drawable.ic_person).into(avatar);
        }

        final String reelId   = reel.reelId;
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

        // Owner name tap → also opens reel profile
        tvOwner.setOnClickListener(x -> avatar.performClick());

        // ── Like button ──
        if (btnLike != null) {
            btnLike.setOnClickListener(x -> {
                if (myUid == null || reelId == null) return;
                isLiked[0] = !isLiked[0];
                if (isLiked[0]) {
                    btnLike.setImageResource(R.drawable.ic_heart_filled);
                    FirebaseUtils.getReelLikesRef(reelId).child(myUid).setValue(true);
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

        // ── Repost button ──
        if (btnRepost != null) {
            btnRepost.setOnClickListener(x -> {
                if (myUid == null || reelId == null || !isAdded() || getContext() == null) return;
                // Block reposting own reel
                if (myUid.equals(ownerUid)) {
                    Toast.makeText(requireContext(), "You can't repost your own reel", Toast.LENGTH_SHORT).show();
                    return;
                }
                long now = System.currentTimeMillis();
                // Direct Firebase writes — immediately visible in UserReelsActivity Reposts tab
                com.google.firebase.database.FirebaseDatabase db =
                    com.google.firebase.database.FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL);
                db.getReference("reelReposts").child(reelId).child(myUid).setValue(now);
                db.getReference("userReposts").child(myUid).child(reelId).setValue(now);
                db.getReference("reels").child(reelId).child("repostCount")
                    .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                        @androidx.annotation.NonNull
                        @Override public com.google.firebase.database.Transaction.Result doTransaction(
                                @androidx.annotation.NonNull com.google.firebase.database.MutableData d) {
                            Integer c = d.getValue(Integer.class);
                            d.setValue(c != null ? c + 1 : 1);
                            return com.google.firebase.database.Transaction.success(d);
                        }
                        @Override public void onComplete(com.google.firebase.database.DatabaseError e,
                                boolean committed, com.google.firebase.database.DataSnapshot s) {}
                    });
                // WorkManager for notification dispatch only
                com.callx.app.workers.ReelRepostWorker.enqueue(
                    requireContext(), reelId, myUid, FirebaseUtils.getCurrentName(),
                    ownerUid, reel.ownerName, reel.thumbUrl);
                Toast.makeText(requireContext(), "Reposted!", Toast.LENGTH_SHORT).show();
                try {
                    int cur = Integer.parseInt(tvReposts.getText().toString());
                    tvReposts.setText(formatCount(cur + 1));
                } catch (Exception ignored) {
                    tvReposts.setText(formatCount(reel.repostCount + 1));
                }
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

        containerFeed.addView(card);
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
}
