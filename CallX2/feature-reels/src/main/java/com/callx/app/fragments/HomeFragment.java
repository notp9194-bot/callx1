package com.callx.app.fragments;

  import android.content.Intent;
  import android.graphics.Color;
  import android.graphics.Typeface;
  import android.os.Bundle;
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
  import com.callx.app.reels.R;
  import com.callx.app.activities.*;
  import com.callx.app.adapters.*;
  import com.callx.app.models.ReelModel;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.android.material.chip.Chip;
  import com.google.android.material.chip.ChipGroup;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.auth.FirebaseUser;
  import com.google.firebase.database.*;
  import de.hdodenhof.circleimageview.CircleImageView;
  import java.util.*;
  import java.util.concurrent.TimeUnit;

  /**
   * HomeFragment — All 17 production features.
   * F1  RecyclerView feed       F2  Skeleton shimmer        F3  Inline ExoPlayer
   * F4  New posts banner        F5  Notification badge      F6  Double-tap like
   * F7  Reaction picker         F8  Tappable hashtags       F9  Tappable mentions
   * F10 Caption expand          F11 Location tag            F12 Close Friends badge
   * F13 Collab indicator        F14 Live Now section        F15 Topic filter chips
   * F16 Challenges row          F17 Save to collection
   */
  public class HomeFragment extends Fragment {

      // Views
      private SwipeRefreshLayout  swipeRefresh;
      private RecyclerView        rvStories, rvFeed, rvLiveNow, rvChallenges;
      private ChipGroup           chipGroupTopics;
      private View                layoutFeedSkeleton;
      private TextView            tvFeedEmpty, btnHomeFollowing, btnHomeForYou;
      private View                vFeedIndicator, tvNewPostsBanner;
      private TextView            tvNotifBadge;
      private ImageButton         btnHomeNotifications, btnHomeUpload;
      private CircleImageView     ivMyStoryAvatar;
      private TextView            btnSeeAllTrending, btnSeeAllChallenges, btnClearHistory;
      private LinearLayout        sectionLiveNow, sectionChallenges, sectionTrending;
      private LinearLayout        sectionFriendsActivity, sectionContinueWatching, sectionSuggestedCreators;
      private LinearLayout        containerTrending, containerFriendsActivity;
      private LinearLayout        containerContinueWatching, containerSuggestedCreators;
      private ProgressBar         pbActivity, pbContinue, pbSuggested;

      // Adapters
      private HomeFeedAdapter      feedAdapter;
      private HomeStoriesAdapter   storiesAdapter;
      private HomeLiveAdapter      liveAdapter;
      private HomeChallengeAdapter challengeAdapter;

      // Data
      private final List<ReelModel>                      feedItems  = new ArrayList<>();
      private final List<HomeStoriesAdapter.StoryEntry>  stories    = new ArrayList<>();
      private final List<HomeLiveAdapter.LiveUser>       liveUsers  = new ArrayList<>();
      private final List<HomeChallengeAdapter.Challenge> challenges = new ArrayList<>();

      private boolean isFollowingMode = true;
      private String  selectedTopic   = null;

      // [F4] New posts watcher
      private final Set<String>  loadedIds  = new HashSet<>();
      private final Set<String>  pendingIds = new HashSet<>();
      private ValueEventListener newPostsListener;

      // [F5] Notification badge
      private int unreadCount = 0;

      private static final String[] TOPICS =
          {"All","Comedy","Music","Dance","Food","Travel","Fashion","Tech","Sports","Art"};

      @Nullable @Override
      public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup container,
                               @Nullable Bundle state) {
          View v = inf.inflate(R.layout.fragment_home, container, false);
          bindViews(v);
          setupTopicChips();
          setupRecyclerViews();
          setupListeners();
          loadMyAvatar();
          watchNotifBadge();       // [F5]
          loadAllSections();
          startNewPostsWatcher();  // [F4]
          return v;
      }

      @Override public void onDestroyView() {
          super.onDestroyView();
          stopNewPostsWatcher();
          if (feedAdapter != null) feedAdapter.releaseAllPlayers();
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

      // ── [F1] RecyclerViews ───────────────────────────────────────────────────
      private void setupRecyclerViews() {
          String myUid = uid();
          feedAdapter = new HomeFeedAdapter(requireContext(), feedItems, myUid);
          rvFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
          rvFeed.setAdapter(feedAdapter);
          rvFeed.setHasFixedSize(false);

          storiesAdapter = new HomeStoriesAdapter(requireContext(), stories,
              new HomeStoriesAdapter.OnStoryClickListener() {
                  @Override public void onAddStory() { launchAddStory(); }
                  @Override public void onStoryClick(HomeStoriesAdapter.StoryEntry e) { openStory(e.uid, e.name); }
              });
          rvStories.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
          rvStories.setAdapter(storiesAdapter);

          liveAdapter = new HomeLiveAdapter(requireContext(), liveUsers);
          rvLiveNow.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
          rvLiveNow.setAdapter(liveAdapter);

          challengeAdapter = new HomeChallengeAdapter(requireContext(), challenges);
          rvChallenges.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
          rvChallenges.setAdapter(challengeAdapter);
      }

      // ── [F15] Topic chips ────────────────────────────────────────────────────
      private void setupTopicChips() {
          if (chipGroupTopics == null || !isAdded()) return;
          for (String topic : TOPICS) {
              Chip chip = new Chip(requireContext());
              chip.setText(topic);
              chip.setCheckable(true);
              chip.setCheckedIconVisible(false);
              chip.setTextColor(Color.WHITE);
              chip.setTextSize(12f);
              if (topic.equals("All")) {
                  chip.setChecked(true);
                  chip.setChipBackgroundColorResource(R.color.brand_primary);
              } else {
                  chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(0xFF2A2A2A));
              }
              chip.setOnCheckedChangeListener((btn, checked) -> {
                  if (checked) { selectedTopic = topic.equals("All") ? null : topic; refreshFeed(); }
              });
              chipGroupTopics.addView(chip);
          }
      }

      private void refreshFeed() {
          feedItems.clear();
          showSkeleton(true);
          loadFeed();
      }

      // ── Listeners ────────────────────────────────────────────────────────────
      private void setupListeners() {
          swipeRefresh.setColorSchemeResources(R.color.brand_primary);
          swipeRefresh.setOnRefreshListener(() -> {
              pendingIds.clear(); loadedIds.clear(); hideBanner();
              clearAll(); loadAllSections();
          });
          btnHomeFollowing.setOnClickListener(v -> switchMode(true));
          btnHomeForYou.setOnClickListener(v -> switchMode(false));
          updateToggleUI();
          if (btnSeeAllTrending != null)    btnSeeAllTrending.setOnClickListener(v -> startActivity(new Intent(requireContext(), ReelExploreActivity.class)));
          if (btnSeeAllChallenges != null)  btnSeeAllChallenges.setOnClickListener(v -> startActivity(new Intent(requireContext(), ReelChallengeActivity.class)));
          if (btnClearHistory != null)      btnClearHistory.setOnClickListener(v -> clearWatchHistory());
          if (btnHomeUpload != null)        btnHomeUpload.setOnClickListener(v -> startActivity(new Intent(requireContext(), ReelUploadActivity.class)));
          // [F5] Notifications
          if (btnHomeNotifications != null) btnHomeNotifications.setOnClickListener(v -> {
              unreadCount = 0; updateBadge();
              String u = uid(); if (u != null) FirebaseDatabase.getInstance().getReference("notifUnread/" + u).removeValue();
              startActivity(new Intent(requireContext(), ReelNotificationsActivity.class));
          });
          // [F4] Banner tap
          if (tvNewPostsBanner != null) tvNewPostsBanner.setOnClickListener(v -> {
              hideBanner(); pendingIds.clear();
              if (rvFeed != null) rvFeed.scrollToPosition(0);
              clearAll(); loadAllSections();
          });
      }

      private void switchMode(boolean following) {
          isFollowingMode = following; updateToggleUI(); feedItems.clear(); showSkeleton(true); loadFeed();
      }

      private void updateToggleUI() {
          if (btnHomeFollowing == null || btnHomeForYou == null) return;
          btnHomeFollowing.setAlpha(isFollowingMode ? 1f : 0.55f);
          btnHomeFollowing.setTypeface(null, isFollowingMode ? Typeface.BOLD : Typeface.NORMAL);
          btnHomeForYou.setAlpha(isFollowingMode ? 0.55f : 1f);
          btnHomeForYou.setTypeface(null, isFollowingMode ? Typeface.NORMAL : Typeface.BOLD);
          if (vFeedIndicator != null) {
              View target = isFollowingMode ? btnHomeFollowing : btnHomeForYou;
              target.post(() -> {
                  if (vFeedIndicator == null) return;
                  vFeedIndicator.animate().translationX(target.getLeft()).setDuration(180).start();
                  ViewGroup.LayoutParams lp = vFeedIndicator.getLayoutParams();
                  lp.width = target.getWidth(); vFeedIndicator.setLayoutParams(lp);
              });
          }
      }

      // ── Load sections ─────────────────────────────────────────────────────────
      private void loadAllSections() {
          loadStories(); loadFeed(); loadLiveNow(); loadChallenges();
          loadTrending(); loadFriendsActivity(); loadContinueWatching(); loadSuggestedCreators();
          swipeRefresh.setRefreshing(false);
      }

      // ── [F4] New posts real-time watcher ─────────────────────────────────────
      private void startNewPostsWatcher() {
          if (uid() == null) return;
          newPostsListener = FirebaseDatabase.getInstance().getReference("reels")
              .orderByChild("timestamp").limitToLast(5)
              .addValueEventListener(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      boolean hasNew = false;
                      for (DataSnapshot c : snap.getChildren()) {
                          String id = c.getKey();
                          if (id != null && !loadedIds.contains(id) && pendingIds.add(id)) hasNew = true;
                      }
                      if (hasNew && !loadedIds.isEmpty()) showBanner();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void stopNewPostsWatcher() {
          if (newPostsListener != null)
              FirebaseDatabase.getInstance().getReference("reels").removeEventListener(newPostsListener);
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
          String u = uid();
          if (u == null || tvNotifBadge == null) return;
          FirebaseDatabase.getInstance().getReference("notifUnread/" + u)
              .addValueEventListener(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView() == null) return;
                      unreadCount = (int) snap.getChildrenCount(); updateBadge();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void updateBadge() {
          if (tvNotifBadge == null || !isAdded()) return;
          if (unreadCount > 0) {
              tvNotifBadge.setVisibility(View.VISIBLE);
              tvNotifBadge.setText(unreadCount > 99 ? "99+" : String.valueOf(unreadCount));
          } else {
              tvNotifBadge.setVisibility(View.GONE);
          }
      }

      // ── Skeleton [F2] ────────────────────────────────────────────────────────
      private void showSkeleton(boolean show) {
          if (layoutFeedSkeleton == null) return;
          layoutFeedSkeleton.setVisibility(show ? View.VISIBLE : View.GONE);
          if (rvFeed != null) rvFeed.setVisibility(show ? View.GONE : View.VISIBLE);
      }

      // ── Stories [F1] ─────────────────────────────────────────────────────────
      private void loadStories() {
          String myUid = uid();
          if (myUid == null) return;
          FirebaseUtils.getStatusSeenRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot seenSnap) {
                  if (!isAdded()) return;
                  Map<String,Set<String>> seenMap = new HashMap<>();
                  for (DataSnapshot on : seenSnap.getChildren()) {
                      Set<String> ids = new HashSet<>();
                      for (DataSnapshot sn : on.getChildren()) ids.add(sn.getKey());
                      seenMap.put(on.getKey(), ids);
                  }
                  FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
                      @Override public void onDataChange(@NonNull DataSnapshot snap) {
                          if (!isAdded()) return;
                          List<String> uids = new ArrayList<>();
                          for (DataSnapshot c : snap.getChildren()) uids.add(c.getKey());
                          collectStories(uids, 0, seenMap, new ArrayList<>());
                      }
                      @Override public void onCancelled(@NonNull DatabaseError e) {}
                  });
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          });
      }

      private void collectStories(List<String> uids, int idx, Map<String,Set<String>> seenMap, List<HomeStoriesAdapter.StoryEntry> collected) {
          if (!isAdded()) return;
          if (idx >= uids.size() || idx >= 20) {
              collected.sort((a,b) -> Boolean.compare(!a.hasUnseen, !b.hasUnseen));
              stories.clear(); stories.addAll(collected);
              if (storiesAdapter != null) storiesAdapter.notifyDataSetChanged();
              return;
          }
          String ownerUid = uids.get(idx);
          FirebaseUtils.getUserRef(ownerUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot uSnap) {
                  if (!isAdded()) return;
                  long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);
                  FirebaseUtils.getUserStatusRef(ownerUid).orderByChild("timestamp").startAt(cutoff)
                      .addListenerForSingleValueEvent(new ValueEventListener() {
                          @Override public void onDataChange(@NonNull DataSnapshot stSnap) {
                              if (stSnap.hasChildren()) {
                                  String name = uSnap.child("name").getValue(String.class);
                                  String photo = uSnap.child("thumbUrl").getValue(String.class);
                                  if (photo == null) photo = uSnap.child("photo").getValue(String.class);
                                  Set<String> seen = seenMap.getOrDefault(ownerUid, Collections.emptySet());
                                  boolean unseen = false;
                                  for (DataSnapshot s : stSnap.getChildren()) { if (!seen.contains(s.getKey())) { unseen=true; break; } }
                                  HomeStoriesAdapter.StoryEntry e = new HomeStoriesAdapter.StoryEntry();
                                  e.uid=ownerUid; e.name=name; e.photo=photo; e.hasUnseen=unseen;
                                  collected.add(e);
                              }
                              collectStories(uids, idx+1, seenMap, collected);
                          }
                          @Override public void onCancelled(@NonNull DatabaseError e) { collectStories(uids, idx+1, seenMap, collected); }
                      });
              }
              @Override public void onCancelled(@NonNull DatabaseError e) { collectStories(uids, idx+1, seenMap, collected); }
          });
      }

      // ── Feed [F1,F3,F15] ─────────────────────────────────────────────────────
      private void loadFeed() {
          String myUid = uid();
          if (myUid == null) { showSkeleton(false); return; }
          if (isFollowingMode) loadFollowingFeed(myUid);
          else loadForYouFeed();
      }

      private void loadFollowingFeed(String myUid) {
          FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  List<String> uids = new ArrayList<>();
                  for (DataSnapshot c : snap.getChildren()) uids.add(c.getKey());
                  if (uids.isEmpty()) { showSkeleton(false); if (tvFeedEmpty!=null) tvFeedEmpty.setVisibility(View.VISIBLE); return; }
                  gatherReels(uids, 0, new ArrayList<>());
              }
              @Override public void onCancelled(@NonNull DatabaseError e) { showSkeleton(false); }
          });
      }

      private void gatherReels(List<String> uids, int idx, List<ReelModel> collected) {
          if (!isAdded()) return;
          if (idx >= uids.size() || idx >= 30) {
              collected.sort((a,b) -> Long.compare(b.timestamp, a.timestamp));
              applyFeed(applyTopicFilter(collected)); return;
          }
          FirebaseDatabase.getInstance().getReference("reels").orderByChild("ownerUid").equalTo(uids.get(idx)).limitToLast(5)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      for (DataSnapshot r : snap.getChildren()) { ReelModel m=r.getValue(ReelModel.class); if(m!=null){m.reelId=r.getKey();collected.add(m);loadedIds.add(m.reelId);} }
                      gatherReels(uids, idx+1, collected);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { gatherReels(uids, idx+1, collected); }
              });
      }

      private void loadForYouFeed() {
          FirebaseDatabase.getInstance().getReference("reels").orderByChild("trendingScore").limitToLast(30)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded()) return;
                      List<ReelModel> list = new ArrayList<>();
                      for (DataSnapshot r : snap.getChildren()) { ReelModel m=r.getValue(ReelModel.class); if(m!=null){m.reelId=r.getKey();list.add(m);loadedIds.add(m.reelId);} }
                      Collections.reverse(list);
                      applyFeed(applyTopicFilter(list));
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { showSkeleton(false); }
              });
      }

      private List<ReelModel> applyTopicFilter(List<ReelModel> input) {
          if (selectedTopic == null) return input;
          List<ReelModel> out = new ArrayList<>();
          for (ReelModel r : input) { if (r.caption!=null && r.caption.toLowerCase().contains(selectedTopic.toLowerCase())) out.add(r); }
          return out.isEmpty() ? input : out;
      }

      private void applyFeed(List<ReelModel> data) {
          showSkeleton(false);
          if (tvFeedEmpty != null) tvFeedEmpty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
          feedItems.clear(); feedItems.addAll(data);
          if (feedAdapter != null) { feedAdapter.setLoading(false); feedAdapter.notifyDataSetChanged(); }
      }

      // ── [F14] Live Now ────────────────────────────────────────────────────────
      private void loadLiveNow() {
          FirebaseDatabase.getInstance().getReference("liveStreams")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView()==null) return;
                      liveUsers.clear();
                      for (DataSnapshot s : snap.getChildren()) {
                          HomeLiveAdapter.LiveUser u = new HomeLiveAdapter.LiveUser();
                          u.uid=s.child("hostUid").getValue(String.class); u.name=s.child("hostName").getValue(String.class);
                          u.photo=s.child("hostPhoto").getValue(String.class); u.streamId=s.getKey();
                          Long vc=s.child("viewerCount").getValue(Long.class); u.viewerCount=vc!=null?vc:0;
                          if (u.uid!=null) liveUsers.add(u);
                      }
                      if (sectionLiveNow!=null) sectionLiveNow.setVisibility(liveUsers.isEmpty()?View.GONE:View.VISIBLE);
                      if (liveAdapter!=null) liveAdapter.notifyDataSetChanged();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      // ── [F16] Challenges ─────────────────────────────────────────────────────
      private void loadChallenges() {
          FirebaseDatabase.getInstance().getReference("reelChallenges").orderByChild("videoCount").limitToLast(6)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView()==null) return;
                      challenges.clear();
                      for (DataSnapshot s : snap.getChildren()) {
                          HomeChallengeAdapter.Challenge c = new HomeChallengeAdapter.Challenge();
                          c.id=s.getKey(); c.tag=s.child("tag").getValue(String.class);
                          c.thumbUrl=s.child("thumbUrl").getValue(String.class);
                          Long vc=s.child("videoCount").getValue(Long.class); c.videoCount=vc!=null?vc:0;
                          if (c.tag!=null) challenges.add(c);
                      }
                      Collections.reverse(challenges);
                      if (sectionChallenges!=null) sectionChallenges.setVisibility(challenges.isEmpty()?View.GONE:View.VISIBLE);
                      if (challengeAdapter!=null) challengeAdapter.notifyDataSetChanged();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      // ── Trending ──────────────────────────────────────────────────────────────
      private void loadTrending() {
          FirebaseDatabase.getInstance().getReference("reels").orderByChild("trendingScore").limitToLast(10)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || getView()==null) return;
                      if (sectionTrending!=null) sectionTrending.setVisibility(View.VISIBLE);
                      if (containerTrending!=null) containerTrending.removeAllViews();
                      List<DataSnapshot> reels = new ArrayList<>();
                      for (DataSnapshot r : snap.getChildren()) reels.add(r);
                      Collections.reverse(reels);
                      for (DataSnapshot r : reels) {
                          ReelModel m=r.getValue(ReelModel.class); if(m!=null){m.reelId=r.getKey();addTrendingCard(m);}
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void addTrendingCard(ReelModel reel) {
          if (!isAdded() || getContext()==null || containerTrending==null) return;
          View card = LayoutInflater.from(getContext()).inflate(R.layout.item_home_trending, containerTrending, false);
          ImageView iv = card.findViewById(R.id.iv_trending_thumb);
          TextView tvL = card.findViewById(R.id.tv_trending_likes);
          TextView tvO = card.findViewById(R.id.tv_trending_owner);
          Glide.with(this).load(reel.thumbnailUrl).centerCrop().into(iv);
          if (tvL!=null) tvL.setText(fmt(reel.likeCount));
          if (tvO!=null) tvO.setText(reel.ownerName!=null?"@"+reel.ownerName:"");
          card.setOnClickListener(v -> { Intent i=new Intent(getContext(),SingleReelPlayerActivity.class); i.putExtra("reelId",reel.reelId); startActivity(i); });
          containerTrending.addView(card);
      }

      // ── Friends Activity ──────────────────────────────────────────────────────
      private void loadFriendsActivity() {
          String myUid = uid(); if (myUid==null) return;
          FirebaseUtils.getContactsRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()||getView()==null) return;
                  if (pbActivity!=null) pbActivity.setVisibility(View.GONE);
                  List<String> uids = new ArrayList<>();
                  for (DataSnapshot c : snap.getChildren()) uids.add(c.getKey());
                  if (uids.isEmpty()) return;
                  String pick = uids.get(new Random().nextInt(Math.min(uids.size(),5)));
                  FirebaseDatabase.getInstance().getReference("reelNotifications/"+pick).orderByChild("timestamp").limitToLast(5)
                      .addListenerForSingleValueEvent(new ValueEventListener() {
                          @Override public void onDataChange(@NonNull DataSnapshot nSnap) {
                              if (!isAdded()||getView()==null) return;
                              if (nSnap.hasChildren() && sectionFriendsActivity!=null) sectionFriendsActivity.setVisibility(View.VISIBLE);
                              for (DataSnapshot n : nSnap.getChildren()) {
                                  String type=n.child("type").getValue(String.class); String fromName=n.child("fromName").getValue(String.class);
                                  Long ts=n.child("timestamp").getValue(Long.class);
                                  if (type!=null&&fromName!=null) addActivityRow(fromName,type,ts!=null?ts:0);
                              }
                          }
                          @Override public void onCancelled(@NonNull DatabaseError e) {}
                      });
              }
              @Override public void onCancelled(@NonNull DatabaseError e) { if(pbActivity!=null) pbActivity.setVisibility(View.GONE); }
          });
      }

      private void addActivityRow(String name, String type, long ts) {
          if (!isAdded()||getContext()==null||containerFriendsActivity==null) return;
          View row = LayoutInflater.from(getContext()).inflate(R.layout.item_notification_center, containerFriendsActivity, false);
          TextView tvText=row.findViewById(R.id.tv_notif_text); TextView tvTime=row.findViewById(R.id.tv_notif_time);
          String verb = "like".equals(type)?"liked a reel":"comment".equals(type)?"commented":"shared a reel";
          if (tvText!=null) tvText.setText(name+" "+verb);
          if (tvTime!=null) tvTime.setText(fmtTime(ts));
          containerFriendsActivity.addView(row);
      }

      // ── Continue Watching ─────────────────────────────────────────────────────
      private void loadContinueWatching() {
          String myUid = uid(); if (myUid==null) return;
          FirebaseDatabase.getInstance().getReference("reelWatchProgress/"+myUid).orderByChild("lastWatchedAt").limitToLast(8)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded()||getView()==null) return;
                      if (pbContinue!=null) pbContinue.setVisibility(View.GONE);
                      if (!snap.hasChildren()) return;
                      if (sectionContinueWatching!=null) sectionContinueWatching.setVisibility(View.VISIBLE);
                      for (DataSnapshot pw : snap.getChildren()) {
                          String rid=pw.getKey(); Long p=pw.child("progressPercent").getValue(Long.class);
                          if (rid!=null) loadContinueCard(rid, p!=null?p.intValue():40);
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { if(pbContinue!=null) pbContinue.setVisibility(View.GONE); }
              });
      }

      private void loadContinueCard(String reelId, int pct) {
          FirebaseDatabase.getInstance().getReference("reels/"+reelId).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()||getContext()==null||containerContinueWatching==null) return;
                  ReelModel m=snap.getValue(ReelModel.class); if(m==null) return; m.reelId=reelId;
                  View card=LayoutInflater.from(getContext()).inflate(R.layout.item_home_continue_watching,containerContinueWatching,false);
                  ImageView iv=card.findViewById(R.id.iv_cw_thumb); ProgressBar pb=card.findViewById(R.id.pb_cw_progress);
                  TextView tvO=card.findViewById(R.id.tv_cw_owner);
                  Glide.with(HomeFragment.this).load(m.thumbnailUrl).centerCrop().into(iv);
                  if (pb!=null) pb.setProgress(pct);
                  if (tvO!=null) tvO.setText(m.ownerName!=null?"@"+m.ownerName:"");
                  card.setOnClickListener(v->{Intent i=new Intent(getContext(),SingleReelPlayerActivity.class);i.putExtra("reelId",m.reelId);startActivity(i);});
                  containerContinueWatching.addView(card);
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          });
      }

      // ── Suggested Creators ────────────────────────────────────────────────────
      private void loadSuggestedCreators() {
          FirebaseDatabase.getInstance().getReference("users").orderByChild("reelCount").limitToLast(10)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded()||getView()==null) return;
                      if (pbSuggested!=null) pbSuggested.setVisibility(View.GONE);
                      if (!snap.hasChildren()) return;
                      if (sectionSuggestedCreators!=null) sectionSuggestedCreators.setVisibility(View.VISIBLE);
                      for (DataSnapshot u : snap.getChildren()) {
                          String xid=u.getKey(); String name=u.child("name").getValue(String.class);
                          String photo=u.child("thumbUrl").getValue(String.class); if(photo==null)photo=u.child("photo").getValue(String.class);
                          if (name!=null) addCreatorCard(xid,name,photo);
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { if(pbSuggested!=null) pbSuggested.setVisibility(View.GONE); }
              });
      }

      private void addCreatorCard(String xid, String name, String photo) {
          if (!isAdded()||getContext()==null||containerSuggestedCreators==null) return;
          View card=LayoutInflater.from(getContext()).inflate(R.layout.item_follow_user,containerSuggestedCreators,false);
          CircleImageView iv=card.findViewById(R.id.iv_follow_avatar); TextView tvN=card.findViewById(R.id.tv_follow_name); TextView btnF=card.findViewById(R.id.btn_follow);
          if(iv!=null) Glide.with(this).load(photo).apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person)).into(iv);
          if(tvN!=null) tvN.setText(name);
          if(btnF!=null) btnF.setOnClickListener(v->{ String u=uid(); if(u!=null) FirebaseDatabase.getInstance().getReference("contacts/"+u+"/"+xid).setValue(true); btnF.setText("Following"); });
          card.setOnClickListener(v->{Intent i=new Intent(getContext(),UserReelsActivity.class);i.putExtra("uid",xid);i.putExtra("name",name);startActivity(i);});
          containerSuggestedCreators.addView(card);
      }

      // ── My avatar ─────────────────────────────────────────────────────────────
      private void loadMyAvatar() {
          String myUid = uid(); if (myUid==null||ivMyStoryAvatar==null) return;
          FirebaseUtils.getUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()||ivMyStoryAvatar==null) return;
                  String p=snap.child("thumbUrl").getValue(String.class); if(p==null) p=snap.child("photo").getValue(String.class);
                  if (p!=null&&!p.isEmpty()) Glide.with(HomeFragment.this).load(p).apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person)).into(ivMyStoryAvatar);
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          });
          ivMyStoryAvatar.setOnClickListener(v -> launchAddStory());
      }

      // ── Clear all ─────────────────────────────────────────────────────────────
      private void clearAll() {
          feedItems.clear(); stories.clear(); liveUsers.clear(); challenges.clear();
          if(feedAdapter!=null){feedAdapter.releaseAllPlayers();feedAdapter.notifyDataSetChanged();}
          if(storiesAdapter!=null)storiesAdapter.notifyDataSetChanged();
          if(liveAdapter!=null)liveAdapter.notifyDataSetChanged();
          if(challengeAdapter!=null)challengeAdapter.notifyDataSetChanged();
          if(containerTrending!=null)containerTrending.removeAllViews();
          if(containerFriendsActivity!=null)containerFriendsActivity.removeAllViews();
          if(containerContinueWatching!=null)containerContinueWatching.removeAllViews();
          if(containerSuggestedCreators!=null)containerSuggestedCreators.removeAllViews();
          if(sectionLiveNow!=null)sectionLiveNow.setVisibility(View.GONE);
          if(sectionChallenges!=null)sectionChallenges.setVisibility(View.GONE);
          if(sectionTrending!=null)sectionTrending.setVisibility(View.GONE);
          if(sectionFriendsActivity!=null)sectionFriendsActivity.setVisibility(View.GONE);
          if(sectionContinueWatching!=null)sectionContinueWatching.setVisibility(View.GONE);
          if(sectionSuggestedCreators!=null)sectionSuggestedCreators.setVisibility(View.GONE);
          showSkeleton(true);
          if(pbActivity!=null)pbActivity.setVisibility(View.VISIBLE);
          if(pbContinue!=null)pbContinue.setVisibility(View.VISIBLE);
          if(pbSuggested!=null)pbSuggested.setVisibility(View.VISIBLE);
      }

      private void clearWatchHistory() {
          String u=uid(); if(u==null) return;
          FirebaseDatabase.getInstance().getReference("reelWatchProgress/"+u).removeValue();
          if(containerContinueWatching!=null)containerContinueWatching.removeAllViews();
          if(sectionContinueWatching!=null)sectionContinueWatching.setVisibility(View.GONE);
      }

      private void openStory(String xid, String name) {
          if (!isAdded()||getContext()==null) return;
          try { Class<?> c=Class.forName("com.callx.app.activities.StatusViewerActivity"); Intent i=new Intent(getContext(),c); i.putExtra("ownerUid",xid); i.putExtra("ownerName",name); startActivity(i); }
          catch(ClassNotFoundException e) { startActivity(new Intent(getContext(),ReelExploreActivity.class)); }
      }

      private void launchAddStory() {
          if (!isAdded()||getContext()==null) return;
          try { Class<?> c=Class.forName("com.callx.app.activities.NewStatusActivity"); startActivity(new Intent(getContext(),c)); }
          catch(ClassNotFoundException e) { startActivity(new Intent(getContext(),ReelCameraActivity.class)); }
      }

      private String uid() {
          try { FirebaseUser u=FirebaseAuth.getInstance().getCurrentUser(); return u!=null?u.getUid():null; }
          catch(Exception e){return null;}
      }
      private String fmt(long n){ if(n<1000)return String.valueOf(n); if(n<1_000_000)return String.format("%.1fK",n/1000.0); return String.format("%.1fM",n/1_000_000.0); }
      private String fmtTime(long ts){ long s=(System.currentTimeMillis()-ts)/1000; if(s<60)return "just now"; long m=s/60; if(m<60)return m+"m"; long h=m/60; if(h<24)return h+"h"; return (h/24)+"d"; }
  }