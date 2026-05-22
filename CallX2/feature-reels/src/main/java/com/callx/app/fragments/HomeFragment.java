package com.callx.app.fragments;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.*;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.annotation.Nullable;
  import androidx.core.content.ContextCompat;
  import androidx.fragment.app.Fragment;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
  import com.bumptech.glide.Glide;
  import com.bumptech.glide.request.RequestOptions;
  import com.callx.app.reels.R;
  import com.callx.app.activities.NotificationsActivity;
  import com.callx.app.activities.ReelCameraActivity;
  import com.callx.app.activities.ReelChallengesListActivity;
  import com.callx.app.activities.ReelExploreActivity;
  import com.callx.app.activities.StatusViewActivity;
  import com.callx.app.adapters.HomeChallengeAdapter;
  import com.callx.app.adapters.HomeFeedAdapter;
  import com.callx.app.adapters.HomeLiveAdapter;
  import com.callx.app.adapters.HomeStoriesAdapter;
  import com.callx.app.models.ReelModel;
  import com.google.android.material.chip.Chip;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import de.hdodenhof.circleimageview.CircleImageView;
  import java.util.*;

  public class HomeFragment extends Fragment {

      // Views
      private RecyclerView rvFeed, rvStories, rvLiveNow, rvChallenges;
      private SwipeRefreshLayout swipeRefresh;
      private TextView tvNewPostsBanner, tvNotifBadge, tvFeedEmpty, btnHomeFollowing, btnHomeForYou, btnSeeAllChallenges, btnSeeAllTrending, btnClearHistory, tvCreatorViews;
      private View vFeedIndicator;
      private LinearLayout sectionLiveNow, sectionChallenges, sectionTrending, sectionFriendsActivity, sectionContinueWatching, sectionSuggestedCreators, layoutFeedSkeleton;
      private LinearLayout containerTrending, containerFriendsActivity, containerContinueWatching, containerSuggestedCreators;
      private ProgressBar pbActivity, pbContinue, pbSuggested;
      private CircleImageView ivMyStoryAvatar;
      private com.google.android.material.chip.ChipGroup chipGroupTopics;

      // Adapters
      private HomeFeedAdapter feedAdapter;
      private HomeLiveAdapter liveAdapter;
      private HomeChallengeAdapter challengeAdapter;
      private HomeStoriesAdapter storiesAdapter;

      // Data
      private final List<ReelModel> feedItems         = new ArrayList<>();
      private final List<HomeLiveAdapter.LiveUser> liveUsers = new ArrayList<>();
      private final List<HomeChallengeAdapter.Challenge> challenges = new ArrayList<>();
      private final List<HomeStoriesAdapter.StoryEntry> stories = new ArrayList<>();

      // State
      private String myUid;
      private boolean isFollowingFeed = true;
      private String selectedTopic = "All";
      private boolean hasPendingNewPosts = false;

      // Firebase listeners (kept for cleanup)
      private ValueEventListener feedListener, liveListener, challengeListener, storyListener, notifListener;

      // Topic chips
      private static final String[] TOPIC_LABELS = {"All","Music","Comedy","Dance","Food","Travel","Fashion","Tech","Gaming","Sports"};

      @Override
      public View onCreateView(@NonNull LayoutInflater inf, ViewGroup container, Bundle saved) {
          return inf.inflate(R.layout.fragment_home, container, false);
      }

      @Override
      public void onViewCreated(@NonNull View view, @Nullable Bundle saved) {
          super.onViewCreated(view, saved);
          myUid = FirebaseAuth.getInstance().getUid();
          bindViews(view);
          setupTopicChips();
          setupFeedTabs();
          setupRecyclerViews();
          setupMyStoryAvatar();
          setupClickListeners(view);
          loadNotifBadge();          // [F4]
          loadFeed();                // [F1]
          loadStories();             // [F5]
          loadLiveUsers();           // [F14]
          loadChallenges();          // [F16]
          loadFriendsActivity();     // [F18]
          loadContinueWatching();    // [F15]
          loadSuggestedCreators();   // [F21]
      }

      private void bindViews(View v) {
          rvFeed                     = v.findViewById(R.id.rv_feed);
          rvStories                  = v.findViewById(R.id.rv_stories);
          rvLiveNow                  = v.findViewById(R.id.rv_live_now);
          rvChallenges               = v.findViewById(R.id.rv_challenges);
          swipeRefresh               = v.findViewById(R.id.swipe_refresh_home);
          tvNewPostsBanner           = v.findViewById(R.id.tv_new_posts_banner);
          tvNotifBadge               = v.findViewById(R.id.tv_notif_badge);
          tvFeedEmpty                = v.findViewById(R.id.tv_feed_empty);
          btnHomeFollowing           = v.findViewById(R.id.btn_home_following);
          btnHomeForYou              = v.findViewById(R.id.btn_home_for_you);
          vFeedIndicator             = v.findViewById(R.id.v_feed_indicator);
          btnSeeAllChallenges        = v.findViewById(R.id.btn_see_all_challenges);
          btnSeeAllTrending          = v.findViewById(R.id.btn_see_all_trending);
          btnClearHistory            = v.findViewById(R.id.btn_clear_history);
          sectionLiveNow             = v.findViewById(R.id.section_live_now);
          sectionChallenges          = v.findViewById(R.id.section_challenges);
          sectionTrending            = v.findViewById(R.id.section_trending);
          sectionFriendsActivity     = v.findViewById(R.id.section_friends_activity);
          sectionContinueWatching    = v.findViewById(R.id.section_continue_watching);
          sectionSuggestedCreators   = v.findViewById(R.id.section_suggested_creators);
          layoutFeedSkeleton         = v.findViewById(R.id.layout_feed_skeleton);
          containerTrending          = v.findViewById(R.id.container_trending);
          containerFriendsActivity   = v.findViewById(R.id.container_friends_activity);
          containerContinueWatching  = v.findViewById(R.id.container_continue_watching);
          containerSuggestedCreators = v.findViewById(R.id.container_suggested_creators);
          pbActivity                 = v.findViewById(R.id.pb_activity);
          pbContinue                 = v.findViewById(R.id.pb_continue);
          pbSuggested                = v.findViewById(R.id.pb_suggested);
          ivMyStoryAvatar            = v.findViewById(R.id.iv_my_story_avatar);
          chipGroupTopics            = v.findViewById(R.id.chip_group_topics);
      }

      // ── [F22] Topic chips ─────────────────────────────────────────────────────
      private void setupTopicChips() {
          if (chipGroupTopics == null || getContext() == null) return;
          for (String label : TOPIC_LABELS) {
              Chip chip = new Chip(requireContext());
              chip.setText(label);
              chip.setCheckable(true);
              chip.setChecked(label.equals("All"));
              chip.setChipBackgroundColorResource(R.color.chip_selector);
              chip.setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.chip_text_selector));
              chip.setOnCheckedChangeListener((btn, checked) -> {
                  if (checked) { selectedTopic = label; loadFeed(); }
              });
              chipGroupTopics.addView(chip);
          }
      }

      // ── [F1] Feed tabs ────────────────────────────────────────────────────────
      private void setupFeedTabs() {
          if (btnHomeFollowing == null) return;
          btnHomeFollowing.setOnClickListener(v -> switchTab(true));
          btnHomeForYou.setOnClickListener(v -> switchTab(false));
      }

      private void switchTab(boolean following) {
          isFollowingFeed = following;
          float activeAlpha = 1.0f, inactiveAlpha = 0.55f;
          btnHomeFollowing.setAlpha(following ? activeAlpha : inactiveAlpha);
          btnHomeFollowing.setTypeface(null, following ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
          btnHomeForYou.setAlpha(following ? inactiveAlpha : activeAlpha);
          btnHomeForYou.setTypeface(null, following ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD);
          if (vFeedIndicator != null) {
              vFeedIndicator.post(() -> {
                  android.animation.ObjectAnimator anim = android.animation.ObjectAnimator.ofFloat(vFeedIndicator, "translationX", following ? 0f : btnHomeFollowing.getWidth() + 20f);
                  anim.setDuration(200); anim.start();
              });
          }
          loadFeed();
      }

      // ── RecyclerViews ─────────────────────────────────────────────────────────
      private void setupRecyclerViews() {
          if (getContext() == null) return;
          feedAdapter = new HomeFeedAdapter(requireContext(), feedItems, myUid);
          rvFeed.setLayoutManager(new LinearLayoutManager(requireContext()));
          rvFeed.setNestedScrollingEnabled(false);
          rvFeed.setAdapter(feedAdapter);

          liveAdapter = new HomeLiveAdapter(requireContext(), liveUsers);
          rvLiveNow.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
          rvLiveNow.setAdapter(liveAdapter);

          challengeAdapter = new HomeChallengeAdapter(requireContext(), challenges);
          rvChallenges.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
          rvChallenges.setAdapter(challengeAdapter);

          storiesAdapter = new HomeStoriesAdapter(requireContext(), stories, new HomeStoriesAdapter.OnStoryClickListener() {
              @Override public void onAddStory() {
                  startActivity(new Intent(requireContext(), ReelCameraActivity.class));
              }
              @Override public void onStoryClick(HomeStoriesAdapter.StoryEntry e) {
                  Intent i = new Intent(requireContext(), StatusViewActivity.class);
                  i.putExtra("uid", e.uid); i.putExtra("name", e.name); startActivity(i);
              }
          });
          rvStories.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
          rvStories.setAdapter(storiesAdapter);
      }

      // ── [F5] My story avatar ──────────────────────────────────────────────────
      private void setupMyStoryAvatar() {
          if (myUid == null || getContext() == null || ivMyStoryAvatar == null) return;
          FirebaseDatabase.getInstance().getReference("users/" + myUid + "/photo")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      String photo = snap.getValue(String.class);
                      if (photo != null && isAdded())
                          Glide.with(requireContext()).load(photo)
                              .apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person))
                              .into(ivMyStoryAvatar);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
          ivMyStoryAvatar.setOnClickListener(v -> startActivity(new Intent(requireContext(), ReelCameraActivity.class)));
      }

      // ── Click listeners ───────────────────────────────────────────────────────
      private void setupClickListeners(View root) {
          if (root.findViewById(R.id.btn_home_notifications) != null)
              root.findViewById(R.id.btn_home_notifications).setOnClickListener(v -> startActivity(new Intent(requireContext(), NotificationsActivity.class)));
          if (root.findViewById(R.id.btn_home_upload) != null)
              root.findViewById(R.id.btn_home_upload).setOnClickListener(v -> startActivity(new Intent(requireContext(), ReelCameraActivity.class)));
          if (tvNewPostsBanner != null)
              tvNewPostsBanner.setOnClickListener(v -> { tvNewPostsBanner.setVisibility(View.GONE); hasPendingNewPosts = false; loadFeed(); });
          if (swipeRefresh != null) {
              swipeRefresh.setColorSchemeColors(ContextCompat.getColor(requireContext(), R.color.brand_primary));
              swipeRefresh.setOnRefreshListener(() -> { loadFeed(); loadLiveUsers(); loadChallenges(); });
          }
          if (btnSeeAllChallenges != null) btnSeeAllChallenges.setOnClickListener(v -> startActivity(new Intent(requireContext(), ReelChallengesListActivity.class)));
          if (btnSeeAllTrending != null)   btnSeeAllTrending.setOnClickListener(v -> startActivity(new Intent(requireContext(), ReelExploreActivity.class)));
          if (btnClearHistory != null)     btnClearHistory.setOnClickListener(v -> clearWatchHistory());
      }

      // ── [F4] Notification badge ───────────────────────────────────────────────
      private void loadNotifBadge() {
          if (myUid == null || tvNotifBadge == null) return;
          notifListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  long count = snap.getChildrenCount();
                  if (count > 0) {
                      tvNotifBadge.setText(count > 99 ? "99+" : String.valueOf(count));
                      tvNotifBadge.setVisibility(View.VISIBLE);
                  } else { tvNotifBadge.setVisibility(View.GONE); }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          };
          FirebaseDatabase.getInstance().getReference("notifUnread/" + myUid).addValueEventListener(notifListener);
      }

      // ── [F1] Load feed ────────────────────────────────────────────────────────
      private void loadFeed() {
          if (feedAdapter == null) return;
          feedAdapter.setLoading(true);
          if (layoutFeedSkeleton != null) layoutFeedSkeleton.setVisibility(View.VISIBLE);
          if (tvFeedEmpty != null) tvFeedEmpty.setVisibility(View.GONE);

          DatabaseReference ref;
          if (isFollowingFeed) {
              ref = FirebaseDatabase.getInstance().getReference("reels");
          } else {
              ref = FirebaseDatabase.getInstance().getReference("reels");
          }

          ref.limitToLast(30).addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  feedItems.clear();
                  for (DataSnapshot d : snap.getChildren()) {
                      ReelModel r = d.getValue(ReelModel.class);
                      if (r != null) {
                          r.reelId = d.getKey();
                          if (selectedTopic.equals("All") || (r.topic != null && r.topic.equalsIgnoreCase(selectedTopic)))
                              feedItems.add(0, r);
                      }
                  }
                  if (layoutFeedSkeleton != null) layoutFeedSkeleton.setVisibility(View.GONE);
                  feedAdapter.setLoading(false);
                  feedAdapter.replaceItems(feedItems);
                  if (tvFeedEmpty != null) tvFeedEmpty.setVisibility(feedItems.isEmpty() ? View.VISIBLE : View.GONE);
                  if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                  listenForNewPosts();
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  if (!isAdded()) return;
                  if (layoutFeedSkeleton != null) layoutFeedSkeleton.setVisibility(View.GONE);
                  feedAdapter.setLoading(false);
                  if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
              }
          });
      }

      // [F4] Real-time new posts banner
      private void listenForNewPosts() {
          if (myUid == null || tvNewPostsBanner == null) return;
          long since = System.currentTimeMillis();
          feedListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  for (DataSnapshot d : snap.getChildren()) {
                      ReelModel r = d.getValue(ReelModel.class);
                      if (r != null && r.timestamp > since && !r.ownerUid.equals(myUid)) {
                          hasPendingNewPosts = true;
                          tvNewPostsBanner.setVisibility(View.VISIBLE);
                          return;
                      }
                  }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          };
          FirebaseDatabase.getInstance().getReference("reels").orderByChild("timestamp")
              .startAt((double) since).addValueEventListener(feedListener);
      }

      // ── [F5] Stories ──────────────────────────────────────────────────────────
      private void loadStories() {
          if (myUid == null) return;
          FirebaseDatabase.getInstance().getReference("contacts/" + myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot contactsSnap) {
                      if (!isAdded()) return;
                      List<String> uids = new ArrayList<>();
                      for (DataSnapshot d : contactsSnap.getChildren()) uids.add(d.getKey());
                      stories.clear();
                      if (uids.isEmpty()) { if (storiesAdapter != null) storiesAdapter.notifyDataSetChanged(); return; }
                      final int[] done = {0};
                      for (String uid : uids) {
                          FirebaseDatabase.getInstance().getReference("users/" + uid)
                              .addListenerForSingleValueEvent(new ValueEventListener() {
                                  @Override public void onDataChange(@NonNull DataSnapshot us) {
                                      HomeStoriesAdapter.StoryEntry e = new HomeStoriesAdapter.StoryEntry();
                                      e.uid   = uid;
                                      e.name  = us.child("name").getValue(String.class);
                                      e.photo = us.child("photo").getValue(String.class);
                                      FirebaseDatabase.getInstance().getReference("statusSeen/" + myUid + "/" + uid)
                                          .addListenerForSingleValueEvent(new ValueEventListener() {
                                              @Override public void onDataChange(@NonNull DataSnapshot seen) {
                                                  e.hasUnseen = !seen.exists();
                                                  stories.add(e);
                                                  done[0]++;
                                                  if (done[0] >= uids.size() && isAdded() && storiesAdapter != null)
                                                      storiesAdapter.notifyDataSetChanged();
                                              }
                                              @Override public void onCancelled(@NonNull DatabaseError ex) { done[0]++; }
                                          });
                                  }
                                  @Override public void onCancelled(@NonNull DatabaseError ex) { done[0]++; }
                              });
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      // ── [F14] Live users ──────────────────────────────────────────────────────
      private void loadLiveUsers() {
          liveListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  liveUsers.clear();
                  for (DataSnapshot d : snap.getChildren()) {
                      HomeLiveAdapter.LiveUser u = new HomeLiveAdapter.LiveUser();
                      u.streamId   = d.getKey();
                      u.uid        = d.child("hostUid").getValue(String.class);
                      u.name       = d.child("hostName").getValue(String.class);
                      u.photo      = d.child("hostPhoto").getValue(String.class);
                      Long vc      = d.child("viewerCount").getValue(Long.class);
                      u.viewerCount = vc != null ? vc : 0;
                      liveUsers.add(u);
                  }
                  if (sectionLiveNow != null) sectionLiveNow.setVisibility(liveUsers.isEmpty() ? View.GONE : View.VISIBLE);
                  if (liveAdapter != null) liveAdapter.notifyDataSetChanged();
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          };
          FirebaseDatabase.getInstance().getReference("liveStreams").addValueEventListener(liveListener);
      }

      // ── [F16] Challenges ──────────────────────────────────────────────────────
      private void loadChallenges() {
          challengeListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  challenges.clear();
                  for (DataSnapshot d : snap.getChildren()) {
                      HomeChallengeAdapter.Challenge c = new HomeChallengeAdapter.Challenge();
                      c.id        = d.getKey();
                      c.tag       = d.child("tag").getValue(String.class);
                      c.thumbUrl  = d.child("thumbUrl").getValue(String.class);
                      Long vc     = d.child("videoCount").getValue(Long.class);
                      c.videoCount = vc != null ? vc : 0;
                      challenges.add(c);
                  }
                  if (sectionChallenges != null) sectionChallenges.setVisibility(challenges.isEmpty() ? View.GONE : View.VISIBLE);
                  if (challengeAdapter != null) challengeAdapter.notifyDataSetChanged();
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          };
          FirebaseDatabase.getInstance().getReference("reelChallenges").orderByChild("videoCount")
              .limitToLast(10).addValueEventListener(challengeListener);
      }

      // ── [F18] Friends activity ────────────────────────────────────────────────
      private void loadFriendsActivity() {
          if (myUid == null || containerFriendsActivity == null) return;
          sectionFriendsActivity.setVisibility(View.VISIBLE);
          FirebaseDatabase.getInstance().getReference("contacts/" + myUid)
              .limitToFirst(5).addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded()) return;
                      if (pbActivity != null) pbActivity.setVisibility(View.GONE);
                      if (!snap.exists()) { sectionFriendsActivity.setVisibility(View.GONE); return; }
                      for (DataSnapshot d : snap.getChildren()) {
                          String uid = d.getKey();
                          FirebaseDatabase.getInstance().getReference("users/" + uid)
                              .addListenerForSingleValueEvent(new ValueEventListener() {
                                  @Override public void onDataChange(@NonNull DataSnapshot us) {
                                      if (!isAdded()) return;
                                      View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_home_story, containerFriendsActivity, false);
                                      de.hdodenhof.circleimageview.CircleImageView iv = row.findViewById(R.id.iv_story_avatar);
                                      TextView tv = row.findViewById(R.id.tv_story_name);
                                      String photo = us.child("photo").getValue(String.class);
                                      String name  = us.child("name").getValue(String.class);
                                      if (photo != null) Glide.with(requireContext()).load(photo)
                                          .apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person)).into(iv);
                                      if (tv != null && name != null) tv.setText(name);
                                      containerFriendsActivity.addView(row);
                                  }
                                  @Override public void onCancelled(@NonNull DatabaseError e) {}
                              });
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      if (isAdded() && pbActivity != null) pbActivity.setVisibility(View.GONE);
                  }
              });
      }

      // ── [F15] Continue watching ───────────────────────────────────────────────
      private void loadContinueWatching() {
          if (myUid == null || containerContinueWatching == null) return;
          sectionContinueWatching.setVisibility(View.VISIBLE);
          FirebaseDatabase.getInstance().getReference("reelWatchProgress/" + myUid)
              .limitToLast(5).addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded()) return;
                      if (pbContinue != null) pbContinue.setVisibility(View.GONE);
                      if (!snap.exists()) { sectionContinueWatching.setVisibility(View.GONE); return; }
                      if (sectionTrending != null) sectionTrending.setVisibility(View.VISIBLE);
                      for (DataSnapshot d : snap.getChildren()) {
                          String reelId = d.getKey();
                          Long progress = d.child("progress").getValue(Long.class);
                          Long duration = d.child("duration").getValue(Long.class);
                          if (progress == null || duration == null || duration == 0) continue;
                          if ((progress * 100 / duration) >= 95) continue;  // skip fully watched
                          addContinueWatchingCard(reelId, progress, duration);
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      if (isAdded() && pbContinue != null) pbContinue.setVisibility(View.GONE);
                  }
              });
      }

      private void addContinueWatchingCard(String reelId, long progress, long duration) {
          FirebaseDatabase.getInstance().getReference("reels/" + reelId)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded() || containerContinueWatching == null) return;
                      ReelModel r = snap.getValue(ReelModel.class);
                      if (r == null) return;
                      r.reelId = reelId;
                      View card = LayoutInflater.from(requireContext()).inflate(R.layout.item_home_story, containerContinueWatching, false);
                      ImageView iv = card.findViewById(R.id.iv_story_avatar);
                      TextView tv  = card.findViewById(R.id.tv_story_name);
                      if (r.thumbnailUrl != null) Glide.with(requireContext()).load(r.thumbnailUrl)
                          .apply(RequestOptions.centerCropTransform().placeholder(R.drawable.ic_reels)).into(iv);
                      if (tv != null) {
                          int pct = (int)(progress * 100 / duration);
                          tv.setText(pct + "% watched");
                      }
                      card.setOnClickListener(v -> {
                          Intent i = new Intent(requireContext(), com.callx.app.activities.SingleReelPlayerActivity.class);
                          i.putExtra("reelId", reelId); i.putExtra("seekTo", progress); startActivity(i);
                      });
                      containerContinueWatching.addView(card);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void clearWatchHistory() {
          if (myUid == null) return;
          FirebaseDatabase.getInstance().getReference("reelWatchProgress/" + myUid).removeValue();
          if (containerContinueWatching != null) containerContinueWatching.removeAllViews();
          if (sectionContinueWatching != null) sectionContinueWatching.setVisibility(View.GONE);
      }

      // ── [F21] Suggested creators ──────────────────────────────────────────────
      private void loadSuggestedCreators() {
          if (myUid == null || containerSuggestedCreators == null) return;
          sectionSuggestedCreators.setVisibility(View.VISIBLE);
          FirebaseDatabase.getInstance().getReference("users")
              .orderByChild("followerCount").limitToLast(6)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isAdded()) return;
                      if (pbSuggested != null) pbSuggested.setVisibility(View.GONE);
                      if (!snap.exists()) { sectionSuggestedCreators.setVisibility(View.GONE); return; }
                      for (DataSnapshot d : snap.getChildren()) {
                          String uid = d.getKey();
                          if (uid != null && uid.equals(myUid)) continue;
                          View card = LayoutInflater.from(requireContext()).inflate(R.layout.item_home_story, containerSuggestedCreators, false);
                          de.hdodenhof.circleimageview.CircleImageView iv = card.findViewById(R.id.iv_story_avatar);
                          TextView tv = card.findViewById(R.id.tv_story_name);
                          String photo = d.child("photo").getValue(String.class);
                          String name  = d.child("name").getValue(String.class);
                          if (photo != null) Glide.with(requireContext()).load(photo)
                              .apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person)).into(iv);
                          if (tv != null) tv.setText(name != null ? name : "");
                          card.setOnClickListener(v -> {
                              Intent i = new Intent(requireContext(), com.callx.app.activities.UserReelsActivity.class);
                              i.putExtra("uid", uid); startActivity(i);
                          });
                          containerSuggestedCreators.addView(card);
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      if (isAdded() && pbSuggested != null) pbSuggested.setVisibility(View.GONE);
                  }
              });
      }

      // ── Lifecycle cleanup ─────────────────────────────────────────────────────
      @Override public void onDestroyView() {
          super.onDestroyView();
          if (feedAdapter != null) feedAdapter.releaseAllPlayers();
          DatabaseReference reelsRef = FirebaseDatabase.getInstance().getReference("reels");
          if (feedListener != null) reelsRef.removeEventListener(feedListener);
          DatabaseReference liveRef = FirebaseDatabase.getInstance().getReference("liveStreams");
          if (liveListener != null) liveRef.removeEventListener(liveListener);
          DatabaseReference chalRef = FirebaseDatabase.getInstance().getReference("reelChallenges");
          if (challengeListener != null) chalRef.removeEventListener(challengeListener);
          if (notifListener != null && myUid != null)
              FirebaseDatabase.getInstance().getReference("notifUnread/" + myUid).removeEventListener(notifListener);
      }
  }