package com.callx.app.fragments;

  import android.animation.ObjectAnimator;
  import android.content.Intent;
  import android.graphics.Color;
  import android.os.Bundle;
  import android.os.Handler;
  import android.os.Looper;
  import android.view.*;
  import android.widget.*;
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
  import com.callx.app.activities.ReelBookmarkCollectionsActivity;
  import com.callx.app.activities.ReelCameraActivity;
  import com.callx.app.activities.ReelChallengeActivity;
  import com.callx.app.activities.ReelCommentActivity;
  import com.callx.app.activities.ReelExploreActivity;
  import com.callx.app.activities.ReelLiveActivity;
  import com.callx.app.activities.ReelNotificationsActivity;
  import com.callx.app.activities.ReelUploadActivity;
  import com.callx.app.activities.SingleReelPlayerActivity;
  import com.callx.app.activities.UserReelsActivity;
  import com.callx.app.adapters.HomeChallengeAdapter;
  import com.callx.app.adapters.HomeFeedAdapter;
  import com.callx.app.adapters.HomeLiveAdapter;
  import com.callx.app.adapters.HomeStoriesAdapter;
  import com.callx.app.models.ReelModel;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.android.material.chip.Chip;
  import com.google.android.material.chip.ChipGroup;
  import com.google.firebase.database.*;
  import de.hdodenhof.circleimageview.CircleImageView;

  import java.util.*;
  import java.util.concurrent.TimeUnit;

  /**
   * HomeFragment — Production-grade Reels Home Tab.
   *
   * ALL 17 features implemented:
   *  [F1]  RecyclerView + HomeFeedAdapter (view recycling, no LinearLayout)
   *  [F2]  Shimmer skeleton loading for feed + trending + stories
   *  [F3]  Inline ExoPlayer video autoplay in feed cards (muted)
   *  [F4]  "New posts available" floating banner (Firebase real-time listener)
   *  [F5]  Activity notification badge (unread count on notification icon)
   *  [F6]  Double-tap to like (handled in HomeFeedAdapter)
   *  [F7]  Long-press reaction picker bottom sheet (handled in HomeFeedAdapter)
   *  [F8]  Tappable #hashtag spans (handled in HomeFeedAdapter)
   *  [F9]  Tappable @mention spans (handled in HomeFeedAdapter)
   *  [F10] Caption expand/collapse "more/less" (handled in HomeFeedAdapter)
   *  [F11] Location tag on feed cards (handled in HomeFeedAdapter)
   *  [F12] Close Friends badge on avatar (handled in HomeFeedAdapter)
   *  [F13] Collab "with @user" indicator (handled in HomeFeedAdapter)
   *  [F14] Live Now section with HomeLiveAdapter
   *  [F15] Topic/Hashtag filter chips bar
   *  [F16] Featured Challenges row with HomeChallengeAdapter
   *  [F17] Save to collection sheet (handled in HomeFeedAdapter)
   */
  public class HomeFragment extends Fragment {

      // ── Views ────────────────────────────────────────────────────────────────
      private SwipeRefreshLayout  swipeRefresh;
      private RecyclerView        rvStories;
      private RecyclerView        rvFeed;
      private RecyclerView        rvLiveNow;
      private RecyclerView        rvChallenges;
      private ChipGroup           chipGroupTopics;
      private View                layoutFeedSkeleton;
      private View                skeletonTrending;
      private TextView            tvFeedEmpty;
      private TextView            btnHomeFollowing;
      private TextView            btnHomeForYou;
      private View                vFeedIndicator;
      private TextView            tvNewPostsBanner;       // [F4]
      private TextView            tvNotifBadge;           // [F5]
      private ImageButton         btnHomeNotifications;   // [F5]
      private ImageButton         btnHomeUpload;
      private CircleImageView     ivMyStoryAvatar;
      private TextView            btnSeeAllTrending;
      private TextView            btnSeeAllChallenges;
      private TextView            btnClearHistory;
      private LinearLayout        sectionLiveNow;
      private LinearLayout        sectionChallenges;
      private LinearLayout        sectionTrending;
      private LinearLayout        sectionFriendsActivity;
      private LinearLayout        sectionContinueWatching;
      private LinearLayout        sectionSuggestedCreators;
      private LinearLayout        containerTrending;
      private LinearLayout        containerFriendsActivity;
      private LinearLayout        containerContinueWatching;
      private LinearLayout        containerSuggestedCreators;
      private ProgressBar         pbActivity;
      private ProgressBar         pbContinue;
      private ProgressBar         pbSuggested;

      // ── Adapters ────────────────────────────────────────────────────────────
      private HomeFeedAdapter           feedAdapter;
      private HomeStoriesAdapter        storiesAdapter;
      private HomeLiveAdapter           liveAdapter;
      private HomeChallengeAdapter      challengeAdapter;

      // ── Data ────────────────────────────────────────────────────────────────
      private final List<ReelModel>                      feedItems      = new ArrayList<>();
      private final List<HomeStoriesAdapter.StoryEntry>  storyEntries   = new ArrayList<>();
      private final List<HomeLiveAdapter.LiveUser>       liveUsers      = new ArrayList<>();
      private final List<HomeChallengeAdapter.Challenge> challenges     = new ArrayList<>();

      private boolean isFollowingMode = true;
      private String  selectedTopic   = null;  // [F15] null = all topics

      // [F4] Track "new posts" — pending new reel IDs detected by real-time listener
      private final Set<String>  loadedReelIds      = new HashSet<>();
      private final Set<String>  pendingNewReelIds   = new HashSet<>();
      private ValueEventListener feedRealTimeListener;

      // [F5] Notification badge
      private int unreadNotifCount = 0;

      // Story model for sorting
      private final Set<String> unseenOwnerUids = new HashSet<>();

      // Topics for [F15]
      private static final String[] TOPICS = {
          "All", "Comedy", "Music", "Dance", "Food",
          "Travel", "Fashion", "Tech", "Sports", "Art"
      };

      // ── Lifecycle ────────────────────────────────────────────────────────────
      @Nullable @Override
      public View onCreateView(@NonNull LayoutInflater inflater,
                               @Nullable ViewGroup container,
                               @Nullable Bundle savedInstanceState) {
          View v = inflater.inflate(R.layout.fragment_home, container, false);
          bindViews(v);
          setupTopicChips();       // [F15]
          setupRecyclerViews();    // [F1]
          setupListeners();
          loadMyAvatar();
          watchNotifBadge();       // [F5]
          loadAllSections();
          startFeedRealTimeWatcher(); // [F4]
          return v;
      }

      @Override public void onDestroyView() {
          super.onDestroyView();
          stopFeedRealTimeWatcher();
          if (feedAdapter != null) feedAdapter.releaseAllPlayers(); // [F3]
      }

      // ── View binding ─────────────────────────────────────────────────────────
      private void bindViews(View v) {
          swipeRefresh             = v.findViewById(R.id.swipe_refresh_home);
          rvStories                = v.findViewById(R.id.rv_stories);
          rvFeed                   = v.findViewById(R.id.rv_feed);
          rvLiveNow                = v.findViewById(R.id.rv_live_now);
          rvChallenges             = v.findViewById(R.id.rv_challenges);
          chipGroupTopics          = v.findViewById(R.id.chip_group_topics);
          layoutFeedSkeleton       = v.findViewById(R.id.layout_feed_skeleton);
          skeletonTrending         = v.findViewById(R.id.skeleton_trending);
          tvFeedEmpty              = v.findViewById(R.id.tv_feed_empty);
          btnHomeFollowing         = v.findViewById(R.id.btn_home_following);
          btnHomeForYou            = v.findViewById(R.id.btn_home_for_you);
          vFeedIndicator           = v.findViewById(R.id.v_feed_indicator);
          tvNewPostsBanner         = v.findViewById(R.id.tv_new_posts_banner);
          tvNotifBadge             = v.findViewById(R.id.tv_notif_badge);
          btnHomeNotifications     = v.findViewById(R.id.btn_home_notifications);
          btnHomeUpload            = v.findViewById(R.id.btn_home_upload);
          ivMyStoryAvatar          = v.findViewById(R.id.iv_my_story_avatar);
          btnSeeAllTrending        = v.findViewById(R.id.btn_see_all_trending);
          btnSeeAllChallenges      = v.findViewById(R.id.btn_see_all_challenges);
          btnClearHistory          = v.findViewById(R.id.btn_clear_history);
          sectionLiveNow           = v.findViewById(R.id.section_live_now);
          sectionChallenges        = v.findViewById(R.id.section_challenges);
          sectionTrending          = v.findViewById(R.id.section_trending);
          sectionFriendsActivity   = v.findViewById(R.id.section_friends_activity);
          sectionContinueWatching  = v.findViewById(R.id.section_continue_watching);
          sectionSuggestedCreators = v.findViewById(R.id.section_suggested_creators);
          containerTrending        = v.findViewById(R.id.container_trending);
          containerFriendsActivity = v.findViewById(R.id.container_friends_activity);
          containerContinueWatching= v.findViewById(R.id.container_continue_watching);
          containerSuggestedCreators=v.findViewById(R.id.container_suggested_creators);
          pbActivity               = v.findViewById(R.id.pb_activity);
          pbContinue               = v.findViewById(R.id.pb_continue);
          pbSuggested              = v.findViewById(R.id.pb_suggested);
      }

      // ── [F1] RecyclerViews setup ─────────────────────────────────────────────
      private void setupRecyclerViews() {
          String myUid = safeMyUid();

          // Feed RecyclerView — LinearLayoutManager, nestedScrollingEnabled=false set in XML
          feedAdapter = new HomeFeedAdapter(requireContext(), feedItems, myUid);
          rvFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
          rvFeed.setAdapter(feedAdapter);
          rvFeed.setHasFixedSize(false);

          // Stories RecyclerView — horizontal
          storiesAdapter = new HomeStoriesAdapter(requireContext(), storyEntries,
              new HomeStoriesAdapter.OnStoryClickListener() {
                  @Override public void onAddStory() { launchAddStory(); }
                  @Override public void onStoryClick(HomeStoriesAdapter.StoryEntry e) {
                      openStoryViewer(e.uid, e.name);
                  }
              });
          rvStories.setLayoutManager(
              new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
          rvStories.setAdapter(storiesAdapter);

          // Live Now RecyclerView — horizontal  [F14]
          liveAdapter = new HomeLiveAdapter(requireContext(), liveUsers);
          rvLiveNow.setLayoutManager(
              new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
          rvLiveNow.setAdapter(liveAdapter);

          // Challenges RecyclerView — horizontal  [F16]
          challengeAdapter = new HomeChallengeAdapter(requireContext(), challenges);
          rvChallenges.setLayoutManager(
              new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
          rvChallenges.setAdapter(challengeAdapter);
      }

      // ── [F15] Topic filter chips ─────────────────────────────────────────────
      private void setupTopicChips() {
          if (chipGroupTopics == null || !isAdded() || getContext() == null) return;
          for (String topic : TOPICS) {
              Chip chip = new Chip(requireContext());
              chip.setText(topic);
              chip.setCheckable(true);
              chip.setCheckedIconVisible(false);
              chip.setChipBackgroundColorResource(R.color.brand_primary);
              chip.setTextColor(Color.WHITE);
              chip.setTextSize(12f);

              if (topic.equals("All")) {
                  chip.setChecked(true);
                  chip.setChipBackgroundColorResource(R.color.brand_primary);
              } else {
                  chip.setChipBackgroundColor(
                      android.content.res.ColorStateList.valueOf(0xFF2A2A2A));
              }

              chip.setOnCheckedChangeListener((btn, checked) -> {
                  if (checked) {
                      selectedTopic = topic.equals("All") ? null : topic;
                      refreshFeedWithTopic();
                  }
              });
              chipGroupTopics.addView(chip);
          }
      }

      private void refreshFeedWithTopic() {
          feedItems.clear();
          feedAdapter.setLoading(true);
          showFeedSkeleton(true);
          loadFeed();
      }

      // ── Listeners ────────────────────────────────────────────────────────────
      private void setupListeners() {
          swipeRefresh.setColorSchemeResources(R.color.brand_primary);
          swipeRefresh.setOnRefreshListener(() -> {
              pendingNewReelIds.clear();
              loadedReelIds.clear();
              hideBanner();
              clearAllSections();
              loadAllSections();
          });

          btnHomeFollowing.setOnClickListener(v -> switchFeedMode(true));
          btnHomeForYou.setOnClickListener(v -> switchFeedMode(false));
          updateFeedToggleUI();

          if (btnSeeAllTrending != null)
              btnSeeAllTrending.setOnClickListener(v -> {
                  if (isAdded() && getContext() != null)
                      startActivity(new Intent(getContext(), ReelExploreActivity.class));
              });

          if (btnSeeAllChallenges != null)
              btnSeeAllChallenges.setOnClickListener(v -> {
                  if (isAdded() && getContext() != null)
                      startActivity(new Intent(getContext(), ReelChallengeActivity.class));
              });

          if (btnClearHistory != null)
              btnClearHistory.setOnClickListener(v -> clearWatchHistory());

          if (btnHomeUpload != null)
              btnHomeUpload.setOnClickListener(v -> {
                  if (isAdded() && getContext() != null)
                      startActivity(new Intent(getContext(), ReelUploadActivity.class));
              });

          // [F5] Notification icon → activity screen
          if (btnHomeNotifications != null)
              btnHomeNotifications.setOnClickListener(v -> {
                  if (isAdded() && getContext() != null) {
                      unreadNotifCount = 0;
                      updateNotifBadge();
                      // Mark all notifications read
                      String uid = safeMyUid();
                      if (uid != null)
                          FirebaseDatabase.getInstance().getReference("notifUnread/" + uid).removeValue();
                      startActivity(new Intent(getContext(), ReelNotificationsActivity.class));
                  }
              });

          // [F4] New posts banner tap → scroll to top + dismiss
          if (tvNewPostsBanner != null)
              tvNewPostsBanner.setOnClickListener(v -> {
                  hideBanner();
                  pendingNewReelIds.clear();
                  if (rvFeed != null) rvFeed.scrollToPosition(0);
                  clearAllSections();
                  loadAllSections();
              });
      }

      // ── Feed toggle ──────────────────────────────────────────────────────────
      private void switchFeedMode(boolean following) {
          isFollowingMode = following;
          updateFeedToggleUI();
          feedItems.clear();
          showFeedSkeleton(true);
          loadFeed();
      }

      private void updateFeedToggleUI() {
          if (btnHomeFollowing == null || btnHomeForYou == null) return;
          btnHomeFollowing.setAlpha(isFollowingMode ? 1f : 0.55f);
          btnHomeFollowing.setTypeface(null,
              isFollowingMode ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
          btnHomeForYou.setAlpha(isFollowingMode ? 0.55f : 1f);
          btnHomeForYou.setTypeface(null,
              isFollowingMode ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);

          if (vFeedIndicator != null) {
              View target = isFollowingMode ? btnHomeFollowing : btnHomeForYou;
              target.post(() -> {
                  if (vFeedIndicator == null) return;
                  vFeedIndicator.animate().translationX(target.getLeft()).setDuration(180).start();
                  ViewGroup.LayoutParams lp = vFeedIndicator.getLayoutParams();
                  lp.width = target.getWidth();
                  vFeedIndicator.setLayoutParams(lp);
              });
          }
      }

      // ── Load everything ───────────────────────────────────────────────────────
      private void loadAllSections() {
          loadStories();
          loadFeed();
          loadLiveNow();        // [F14]
          loadChallenges();     // [F16]
          loadTrending();
          loadFriendsActivity();
          loadContinueWatching();
          loadSuggestedCreators();
          swipeRefresh.setRefreshing(false);
      }

      // ── [F4] Real-time new posts watcher ─────────────────────────────────────
      private void startFeedRealTimeWatcher() {
          String myUid = safeMyUid();
          if (myUid == null) return;

          DatabaseReference ref = FirebaseDatabase.getInstance().getReference("reels");
          feedRealTimeListener = ref.orderByChild("timestamp")
              .limitToLast(5)
              .addValueEventListener(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      boolean hasNew = false;
                      for (DataSnapshot child : snap.getChildren()) {
                          String id = child.getKey();
                          if (id != null && !loadedReelIds.contains(id) && !pendingNewReelIds.contains(id)) {
                              pendingNewReelIds.add(id);
                              hasNew = true;
                          }
                      }
                      if (hasNew && !loadedReelIds.isEmpty()) showBanner();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void stopFeedRealTimeWatcher() {
          if (feedRealTimeListener != null)
              FirebaseDatabase.getInstance().getReference("reels")
                  .removeEventListener(feedRealTimeListener);
      }

      private void showBanner() {
          if (tvNewPostsBanner == null || !isAdded()) return;
          tvNewPostsBanner.setVisibility(View.VISIBLE);
          tvNewPostsBanner.setAlpha(0f);
          tvNewPostsBanner.animate().alpha(1f).setDuration(300).start();
      }

      private void hideBanner() {
          if (tvNewPostsBanner == null) return;
          tvNewPostsBanner.animate().alpha(0f).setDuration(200)
              .withEndAction(() -> tvNewPostsBanner.setVisibility(View.GONE)).start();
      }

      // ── [F5] Notification badge ───────────────────────────────────────────────
      private void watchNotifBadge() {
          String myUid = safeMyUid();
          if (myUid == null || tvNotifBadge == null) return;

          FirebaseDatabase.getInstance().getReference("notifUnread/" + myUid)
              .addValueEventListener(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      int count = (int) snap.getChildrenCount();
                      unreadNotifCount = count;
                      updateNotifBadge();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void updateNotifBadge() {
          if (tvNotifBadge == null || !isAdded()) return;
          if (unreadNotifCount > 0) {
              tvNotifBadge.setVisibility(View.VISIBLE);
              tvNotifBadge.setText(unreadNotifCount > 99 ? "99+" : String.valueOf(unreadNotifCount));
          } else {
              tvNotifBadge.setVisibility(View.GONE);
          }
      }

      // ── Shimmer skeleton control [F2] ─────────────────────────────────────────
      private void showFeedSkeleton(boolean show) {
          if (layoutFeedSkeleton == null) return;
          layoutFeedSkeleton.setVisibility(show ? View.VISIBLE : View.GONE);
          if (rvFeed != null) rvFeed.setVisibility(show ? View.GONE : View.VISIBLE);
          // Start/stop shimmer
          try {
              com.facebook.shimmer.ShimmerFrameLayout shimmer =
                  layoutFeedSkeleton.findViewWithTag("shimmer");
              if (shimmer != null) {
                  if (show) shimmer.startShimmer(); else shimmer.stopShimmer();
              }
          } catch (Exception ignored) {}
      }

      // ── Stories  [F1, F2] ────────────────────────────────────────────────────
      private void loadStories() {
          String myUid = safeMyUid();
          if (myUid == null) return;
          unseenOwnerUids.clear();

          FirebaseUtils.getStatusSeenRef(myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot seenSnap) {
                      if (!isAdded()) return;
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
                  if (!isAdded()) return;
                  List<String> uids = new ArrayList<>();
                  for (DataSnapshot c : snap.getChildren()) uids.add(c.getKey());
                  if (uids.isEmpty()) return;
                  collectStoryEntries(uids, 0, seenMap, new ArrayList<>());
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          });
      }

      private void collectStoryEntries(List<String> uids, int idx,
                                       Map<String, Set<String>> seenMap,
                                       List<HomeStoriesAdapter.StoryEntry> collected) {
          if (!isAdded()) return;
          if (idx >= uids.size() || idx >= 20) {
              collected.sort((a, b) -> Boolean.compare(!a.hasUnseen, !b.hasUnseen));
              storyEntries.clear();
              storyEntries.addAll(collected);
              if (storiesAdapter != null) storiesAdapter.notifyDataSetChanged();
              return;
          }
          String uid = uids.get(idx);
          FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  long now = System.currentTimeMillis();
                  long cutoff = now - TimeUnit.HOURS.toMillis(24);
                  FirebaseUtils.getUserStatusRef(uid).orderByChild("timestamp").startAt(cutoff)
                      .addListenerForSingleValueEvent(new ValueEventListener() {
                          @Override public void onDataChange(@NonNull DataSnapshot statusSnap) {
                              if (statusSnap.hasChildren()) {
                                  String name  = snap.child("name").getValue(String.class);
                                  String photo = snap.child("thumbUrl").getValue(String.class);
                                  if (photo == null) photo = snap.child("photo").getValue(String.class);
                                  Set<String> seenIds = seenMap.getOrDefault(uid, Collections.emptySet());
                                  boolean hasUnseen = false;
                                  for (DataSnapshot s : statusSnap.getChildren()) {
                                      if (!seenIds.contains(s.getKey())) { hasUnseen = true; break; }
                                  }
                                  HomeStoriesAdapter.StoryEntry entry = new HomeStoriesAdapter.StoryEntry();
                                  entry.uid = uid; entry.name = name; entry.photo = photo;
                                  entry.hasUnseen = hasUnseen;
                                  collected.add(entry);
                              }
                              collectStoryEntries(uids, idx + 1, seenMap, collected);
                          }
                          @Override public void onCancelled(@NonNull DatabaseError e) {
                              collectStoryEntries(uids, idx + 1, seenMap, collected);
                          }
                      });
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  collectStoryEntries(uids, idx + 1, seenMap, collected);
              }
          });
      }

      // ── Feed  [F1, F2, F3] ───────────────────────────────────────────────────
      private void loadFeed() {
          String myUid = safeMyUid();
          if (myUid == null) { showFeedSkeleton(false); return; }

          if (isFollowingMode) {
              loadFollowingFeed(myUid);
          } else {
              loadForYouFeed();
          }
      }

      private void loadFollowingFeed(String myUid) {
          FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  List<String> followUids = new ArrayList<>();
                  for (DataSnapshot c : snap.getChildren()) followUids.add(c.getKey());
                  if (followUids.isEmpty()) {
                      showFeedSkeleton(false);
                      if (tvFeedEmpty != null) tvFeedEmpty.setVisibility(View.VISIBLE);
                      return;
                  }
                  collectFollowingReels(followUids, 0, new ArrayList<>());
              }
              @Override public void onCancelled(@NonNull DatabaseError e) { showFeedSkeleton(false); }
          });
      }

      private void collectFollowingReels(List<String> uids, int idx, List<ReelModel> collected) {
          if (!isAdded()) return;
          if (idx >= uids.size() || idx >= 30) {
              // Sort by timestamp desc
              collected.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
              // [F15] Topic filter
              List<ReelModel> filtered = applyTopicFilter(collected);
              applyFeedData(filtered);
              return;
          }
          String uid = uids.get(idx);
          FirebaseDatabase.getInstance().getReference("reels")
              .orderByChild("ownerUid").equalTo(uid)
              .limitToLast(5)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      for (DataSnapshot r : snap.getChildren()) {
                          ReelModel m = r.getValue(ReelModel.class);
                          if (m != null) { m.reelId = r.getKey(); collected.add(m); loadedReelIds.add(m.reelId); }
                      }
                      collectFollowingReels(uids, idx + 1, collected);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      collectFollowingReels(uids, idx + 1, collected);
                  }
              });
      }

      private void loadForYouFeed() {
          FirebaseDatabase.getInstance().getReference("reels")
              .orderByChild("trendingScore").limitToLast(30)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded()) return;
                      List<ReelModel> list = new ArrayList<>();
                      for (DataSnapshot r : snap.getChildren()) {
                          ReelModel m = r.getValue(ReelModel.class);
                          if (m != null) { m.reelId = r.getKey(); list.add(m); loadedReelIds.add(m.reelId); }
                      }
                      Collections.reverse(list);
                      List<ReelModel> filtered = applyTopicFilter(list);
                      applyFeedData(filtered);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { showFeedSkeleton(false); }
              });
      }

      /** [F15] Filter feed by selected topic (hashtag) */
      private List<ReelModel> applyTopicFilter(List<ReelModel> input) {
          if (selectedTopic == null) return input;
          List<ReelModel> out = new ArrayList<>();
          for (ReelModel r : input) {
              if (r.caption != null &&
                  r.caption.toLowerCase().contains(selectedTopic.toLowerCase())) {
                  out.add(r);
              }
          }
          return out.isEmpty() ? input : out; // fallback to all if no match
      }

      private void applyFeedData(List<ReelModel> data) {
          showFeedSkeleton(false);
          if (tvFeedEmpty != null) tvFeedEmpty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
          feedItems.clear();
          feedItems.addAll(data);
          if (feedAdapter != null) {
              feedAdapter.setLoading(false);
              feedAdapter.notifyDataSetChanged();
          }
      }

      // ── [F14] Live Now ───────────────────────────────────────────────────────
      private void loadLiveNow() {
          FirebaseDatabase.getInstance().getReference("liveStreams")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      liveUsers.clear();
                      for (DataSnapshot s : snap.getChildren()) {
                          HomeLiveAdapter.LiveUser u = new HomeLiveAdapter.LiveUser();
                          u.uid         = s.child("hostUid").getValue(String.class);
                          u.name        = s.child("hostName").getValue(String.class);
                          u.photo       = s.child("hostPhoto").getValue(String.class);
                          u.streamId    = s.getKey();
                          Long vc       = s.child("viewerCount").getValue(Long.class);
                          u.viewerCount = vc != null ? vc : 0;
                          if (u.uid != null) liveUsers.add(u);
                      }
                      if (sectionLiveNow != null)
                          sectionLiveNow.setVisibility(liveUsers.isEmpty() ? View.GONE : View.VISIBLE);
                      if (liveAdapter != null) liveAdapter.notifyDataSetChanged();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      // ── [F16] Challenges ─────────────────────────────────────────────────────
      private void loadChallenges() {
          FirebaseDatabase.getInstance().getReference("reelChallenges")
              .orderByChild("videoCount").limitToLast(6)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      challenges.clear();
                      for (DataSnapshot s : snap.getChildren()) {
                          HomeChallengeAdapter.Challenge c = new HomeChallengeAdapter.Challenge();
                          c.id        = s.getKey();
                          c.tag       = s.child("tag").getValue(String.class);
                          c.thumbUrl  = s.child("thumbUrl").getValue(String.class);
                          Long vc     = s.child("videoCount").getValue(Long.class);
                          c.videoCount= vc != null ? vc : 0;
                          if (c.tag != null) challenges.add(c);
                      }
                      Collections.reverse(challenges);
                      if (sectionChallenges != null)
                          sectionChallenges.setVisibility(challenges.isEmpty() ? View.GONE : View.VISIBLE);
                      if (challengeAdapter != null) challengeAdapter.notifyDataSetChanged();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      // ── Trending reels ────────────────────────────────────────────────────────
      private void loadTrending() {
          if (skeletonTrending != null) skeletonTrending.setVisibility(View.VISIBLE);
          FirebaseDatabase.getInstance().getReference("reels")
              .orderByChild("trendingScore").limitToLast(10)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      if (skeletonTrending != null) skeletonTrending.setVisibility(View.GONE);
                      if (sectionTrending != null) sectionTrending.setVisibility(View.VISIBLE);
                      if (containerTrending != null) containerTrending.removeAllViews();
                      List<DataSnapshot> reels = new ArrayList<>();
                      for (DataSnapshot r : snap.getChildren()) reels.add(r);
                      Collections.reverse(reels);
                      for (DataSnapshot r : reels) {
                          ReelModel m = r.getValue(ReelModel.class);
                          if (m != null) {
                              m.reelId = r.getKey();
                              addTrendingCard(m);
                          }
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      if (skeletonTrending != null) skeletonTrending.setVisibility(View.GONE);
                  }
              });
      }

      private void addTrendingCard(ReelModel reel) {
          if (!isAdded() || getContext() == null || containerTrending == null) return;
          View card = LayoutInflater.from(getContext()).inflate(R.layout.item_home_trending, containerTrending, false);
          ImageView ivThumb   = card.findViewById(R.id.iv_trending_thumb);
          TextView  tvLikes   = card.findViewById(R.id.tv_trending_likes);
          TextView  tvOwner   = card.findViewById(R.id.tv_trending_owner);
          Glide.with(this).load(reel.thumbnailUrl).centerCrop().placeholder(android.R.color.darker_gray).into(ivThumb);
          if (tvLikes  != null) tvLikes.setText(formatCount(reel.likeCount));
          if (tvOwner  != null) tvOwner.setText(reel.ownerName != null ? "@" + reel.ownerName : "");
          card.setOnClickListener(v -> {
              Intent i = new Intent(getContext(), SingleReelPlayerActivity.class);
              i.putExtra("reelId", reel.reelId);
              startActivity(i);
          });
          containerTrending.addView(card);
      }

      // ── Friends Activity ──────────────────────────────────────────────────────
      private void loadFriendsActivity() {
          String myUid = safeMyUid();
          if (myUid == null || pbActivity == null) return;
          FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded() || getView() == null) return;
                  if (pbActivity != null) pbActivity.setVisibility(View.GONE);
                  List<String> uids = new ArrayList<>();
                  for (DataSnapshot c : snap.getChildren()) uids.add(c.getKey());
                  loadActivityItems(uids);
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  if (pbActivity != null) pbActivity.setVisibility(View.GONE);
              }
          });
      }

      private void loadActivityItems(List<String> uids) {
          if (uids.isEmpty()) return;
          String uid = uids.get(new Random().nextInt(Math.min(uids.size(), 5)));
          FirebaseDatabase.getInstance().getReference("reelNotifications/" + uid)
              .orderByChild("timestamp").limitToLast(5)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      if (sectionFriendsActivity != null) sectionFriendsActivity.setVisibility(View.VISIBLE);
                      for (DataSnapshot n : snap.getChildren()) {
                          String type     = n.child("type").getValue(String.class);
                          String fromName = n.child("fromName").getValue(String.class);
                          Long   ts       = n.child("timestamp").getValue(Long.class);
                          if (type != null && fromName != null) addActivityRow(fromName, type, ts != null ? ts : 0);
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void addActivityRow(String name, String type, long ts) {
          if (!isAdded() || getContext() == null || containerFriendsActivity == null) return;
          View row = LayoutInflater.from(getContext()).inflate(R.layout.item_notification_center, containerFriendsActivity, false);
          TextView tvText = row.findViewById(R.id.tv_notif_text);
          TextView tvTime = row.findViewById(R.id.tv_notif_time);
          String verb = type.equals("like") ? "liked a reel" : type.equals("comment") ? "commented" : "shared a reel";
          if (tvText != null) tvText.setText(name + " " + verb);
          if (tvTime != null) tvTime.setText(formatTimeAgo(ts));
          containerFriendsActivity.addView(row);
      }

      // ── Continue Watching ─────────────────────────────────────────────────────
      private void loadContinueWatching() {
          String myUid = safeMyUid();
          if (myUid == null) return;
          FirebaseDatabase.getInstance().getReference("reelWatchProgress/" + myUid)
              .orderByChild("lastWatchedAt").limitToLast(8)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      if (pbContinue != null) pbContinue.setVisibility(View.GONE);
                      if (!snap.hasChildren()) return;
                      if (sectionContinueWatching != null) sectionContinueWatching.setVisibility(View.VISIBLE);
                      for (DataSnapshot pw : snap.getChildren()) {
                          String reelId  = pw.getKey();
                          Long   progress= pw.child("progressPercent").getValue(Long.class);
                          Long   lastTs  = pw.child("lastWatchedAt").getValue(Long.class);
                          if (reelId != null) loadContinueWatchingCard(reelId, progress != null ? progress.intValue() : 40);
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      if (pbContinue != null) pbContinue.setVisibility(View.GONE);
                  }
              });
      }

      private void loadContinueWatchingCard(String reelId, int progressPct) {
          FirebaseDatabase.getInstance().getReference("reels/" + reelId)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getContext() == null || containerContinueWatching == null) return;
                      ReelModel m = snap.getValue(ReelModel.class);
                      if (m == null) return;
                      m.reelId = reelId;
                      View card = LayoutInflater.from(getContext()).inflate(R.layout.item_home_continue_watching, containerContinueWatching, false);
                      ImageView  ivThumb = card.findViewById(R.id.iv_cw_thumb);
                      ProgressBar pb    = card.findViewById(R.id.pb_cw_progress);
                      TextView   tvOwner= card.findViewById(R.id.tv_cw_owner);
                      Glide.with(HomeFragment.this).load(m.thumbnailUrl).centerCrop().into(ivThumb);
                      if (pb != null) pb.setProgress(progressPct);
                      if (tvOwner != null) tvOwner.setText(m.ownerName != null ? "@" + m.ownerName : "");
                      card.setOnClickListener(v -> {
                          Intent i = new Intent(getContext(), SingleReelPlayerActivity.class);
                          i.putExtra("reelId", m.reelId);
                          startActivity(i);
                      });
                      containerContinueWatching.addView(card);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      // ── Suggested Creators ────────────────────────────────────────────────────
      private void loadSuggestedCreators() {
          FirebaseDatabase.getInstance().getReference("users")
              .orderByChild("reelCount").limitToLast(10)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      if (pbSuggested != null) pbSuggested.setVisibility(View.GONE);
                      if (!snap.hasChildren()) return;
                      if (sectionSuggestedCreators != null) sectionSuggestedCreators.setVisibility(View.VISIBLE);
                      for (DataSnapshot u : snap.getChildren()) {
                          String uid   = u.getKey();
                          String name  = u.child("name").getValue(String.class);
                          String photo = u.child("thumbUrl").getValue(String.class);
                          if (photo == null) photo = u.child("photo").getValue(String.class);
                          if (name != null) addSuggestedCreatorCard(uid, name, photo);
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      if (pbSuggested != null) pbSuggested.setVisibility(View.GONE);
                  }
              });
      }

      private void addSuggestedCreatorCard(String uid, String name, String photo) {
          if (!isAdded() || getContext() == null || containerSuggestedCreators == null) return;
          View card = LayoutInflater.from(getContext()).inflate(R.layout.item_follow_user, containerSuggestedCreators, false);
          CircleImageView ivAvatar = card.findViewById(R.id.iv_follow_avatar);
          TextView tvName          = card.findViewById(R.id.tv_follow_name);
          TextView btnFollow        = card.findViewById(R.id.btn_follow);
          if (ivAvatar != null) Glide.with(this).load(photo)
              .apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person)).into(ivAvatar);
          if (tvName != null) tvName.setText(name);
          if (btnFollow != null) {
              btnFollow.setOnClickListener(v -> {
                  String myUid = safeMyUid();
                  if (myUid == null) return;
                  FirebaseDatabase.getInstance().getReference("contacts/" + myUid + "/" + uid).setValue(true);
                  btnFollow.setText("Following");
              });
          }
          card.setOnClickListener(v -> {
              Intent i = new Intent(getContext(), UserReelsActivity.class);
              i.putExtra("uid", uid); i.putExtra("name", name); i.putExtra("photo", photo != null ? photo : "");
              startActivity(i);
          });
          containerSuggestedCreators.addView(card);
      }

      // ── My Avatar ─────────────────────────────────────────────────────────────
      private void loadMyAvatar() {
          String myUid = safeMyUid();
          if (myUid == null || ivMyStoryAvatar == null) return;
          FirebaseUtils.getUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded() || ivMyStoryAvatar == null) return;
                  String photo = snap.child("thumbUrl").getValue(String.class);
                  if (photo == null) photo = snap.child("photo").getValue(String.class);
                  if (photo != null && !photo.isEmpty())
                      Glide.with(HomeFragment.this).load(photo)
                          .apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person))
                          .into(ivMyStoryAvatar);
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          });
          ivMyStoryAvatar.setOnClickListener(v -> launchAddStory());
      }

      // ── Clear all ─────────────────────────────────────────────────────────────
      private void clearAllSections() {
          feedItems.clear();
          storyEntries.clear();
          liveUsers.clear();
          challenges.clear();
          if (feedAdapter != null) { feedAdapter.releaseAllPlayers(); feedAdapter.notifyDataSetChanged(); }
          if (storiesAdapter != null) storiesAdapter.notifyDataSetChanged();
          if (liveAdapter != null) liveAdapter.notifyDataSetChanged();
          if (challengeAdapter != null) challengeAdapter.notifyDataSetChanged();
          if (containerTrending != null) containerTrending.removeAllViews();
          if (containerFriendsActivity != null) containerFriendsActivity.removeAllViews();
          if (containerContinueWatching != null) containerContinueWatching.removeAllViews();
          if (containerSuggestedCreators != null) containerSuggestedCreators.removeAllViews();
          if (sectionLiveNow != null) sectionLiveNow.setVisibility(View.GONE);
          if (sectionChallenges != null) sectionChallenges.setVisibility(View.GONE);
          if (sectionTrending != null) sectionTrending.setVisibility(View.GONE);
          if (sectionFriendsActivity != null) sectionFriendsActivity.setVisibility(View.GONE);
          if (sectionContinueWatching != null) sectionContinueWatching.setVisibility(View.GONE);
          if (sectionSuggestedCreators != null) sectionSuggestedCreators.setVisibility(View.GONE);
          showFeedSkeleton(true);
          if (pbActivity != null) pbActivity.setVisibility(View.VISIBLE);
          if (pbContinue != null) pbContinue.setVisibility(View.VISIBLE);
          if (pbSuggested != null) pbSuggested.setVisibility(View.VISIBLE);
      }

      // ── Clear watch history ───────────────────────────────────────────────────
      private void clearWatchHistory() {
          String myUid = safeMyUid();
          if (myUid == null) return;
          FirebaseDatabase.getInstance().getReference("reelWatchProgress/" + myUid).removeValue();
          if (containerContinueWatching != null) containerContinueWatching.removeAllViews();
          if (sectionContinueWatching != null) sectionContinueWatching.setVisibility(View.GONE);
      }

      // ── Story viewer + Add story ──────────────────────────────────────────────
      private void openStoryViewer(String uid, String name) {
          if (!isAdded() || getContext() == null) return;
          try {
              Class<?> cls = Class.forName("com.callx.app.activities.StatusViewerActivity");
              Intent i = new Intent(getContext(), cls);
              i.putExtra("ownerUid", uid); i.putExtra("ownerName", name);
              startActivity(i);
          } catch (ClassNotFoundException e) {
              startActivity(new Intent(getContext(), ReelExploreActivity.class));
          }
      }

      private void launchAddStory() {
          if (!isAdded() || getContext() == null) return;
          try {
              Class<?> cls = Class.forName("com.callx.app.activities.NewStatusActivity");
              startActivity(new Intent(getContext(), cls));
          } catch (ClassNotFoundException e) {
              startActivity(new Intent(getContext(), ReelCameraActivity.class));
          }
      }

      // ── Utils ─────────────────────────────────────────────────────────────────
      private String safeMyUid() {
          try {
              com.google.firebase.auth.FirebaseUser u =
                  com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser();
              return u != null ? u.getUid() : null;
          } catch (Exception e) { return null; }
      }

      private String formatCount(long n) {
          if (n < 1000) return String.valueOf(n);
          if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
          return String.format("%.1fM", n / 1_000_000.0);
      }

      private String formatTimeAgo(long ts) {
          long diff = System.currentTimeMillis() - ts;
          long secs = diff / 1000;
          if (secs < 60) return "just now";
          long mins = secs / 60;
          if (mins < 60) return mins + "m";
          long hrs = mins / 60;
          if (hrs < 24) return hrs + "h";
          return (hrs / 24) + "d";
      }
  }