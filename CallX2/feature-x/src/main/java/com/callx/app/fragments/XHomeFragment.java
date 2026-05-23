package com.callx.app.fragments;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.LayoutInflater;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.Toast;
  import androidx.annotation.NonNull;
  import androidx.annotation.Nullable;
  import androidx.fragment.app.Fragment;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
  import com.callx.app.activities.XComposeActivity;
  import com.callx.app.adapters.XTweetAdapter;
  import com.callx.app.models.XTweet;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.android.material.floatingactionbutton.FloatingActionButton;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.ArrayList;
  import java.util.Collections;
  import java.util.List;

  public class XHomeFragment extends Fragment implements XTweetAdapter.OnTweetActionListener {

      private RecyclerView recyclerView;
      private SwipeRefreshLayout swipeRefresh;
      private XTweetAdapter adapter;
      private FloatingActionButton fabCompose;
      private ValueEventListener feedListener;
      private String myUid;
      private int activeTab = 0; // 0=ForYou, 1=Following

      @Nullable @Override
      public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                               @Nullable Bundle savedInstanceState) {
          return inflater.inflate(R.layout.fragment_x_home, container, false);
      }

      @Override
      public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
          super.onViewCreated(view, savedInstanceState);
          myUid = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

          recyclerView  = view.findViewById(R.id.rv_x_home);
          swipeRefresh  = view.findViewById(R.id.swipe_x_home);
          fabCompose    = view.findViewById(R.id.fab_x_compose);

          adapter = new XTweetAdapter(requireContext(), this);
          recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
          recyclerView.setAdapter(adapter);

          swipeRefresh.setOnRefreshListener(this::loadFeed);
          fabCompose.setOnClickListener(v -> startActivity(
              new Intent(requireContext(), XComposeActivity.class)));

          // Tabs: For You / Following
          view.findViewById(R.id.tab_x_for_you).setOnClickListener(v -> {
              activeTab = 0;
              updateTabUI(view);
              loadFeed();
          });
          view.findViewById(R.id.tab_x_following).setOnClickListener(v -> {
              activeTab = 1;
              updateTabUI(view);
              loadFeed();
          });
          updateTabUI(view);
          loadFeed();
      }

      private void updateTabUI(View view) {
          view.findViewById(R.id.tab_x_for_you).setSelected(activeTab == 0);
          view.findViewById(R.id.tab_x_following).setSelected(activeTab == 1);
      }

      private void loadFeed() {
          if (isDetached() || getContext() == null) return;
          swipeRefresh.setRefreshing(true);

          // Detach old listener
          if (feedListener != null) {
              XFirebaseUtils.globalFeedRef().removeEventListener(feedListener);
          }

          DatabaseReference ref = activeTab == 0
              ? XFirebaseUtils.globalFeedRef().limitToLast(50)
              : XFirebaseUtils.userFeedRef(myUid).limitToLast(50);

          feedListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (!isAdded()) return;
                  List<XTweet> list = new ArrayList<>();
                  for (DataSnapshot ds : snap.getChildren()) {
                      XTweet tweet = ds.getValue(XTweet.class);
                      if (tweet != null && !tweet.isDeleted) {
                          tweet.id = ds.getKey();
                          list.add(tweet);
                      }
                  }
                  Collections.sort(list, (a, b) -> Long.compare(b.timestamp, a.timestamp));
                  adapter.setTweets(list);
                  swipeRefresh.setRefreshing(false);
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  if (isAdded()) swipeRefresh.setRefreshing(false);
              }
          };

          ref.addValueEventListener(feedListener);
      }

      // ── Tweet Actions ──────────────────────────────────────────────────────

      @Override public void onLike(XTweet tweet, boolean liked) {
          XFirebaseUtils.tweetLikesRef(tweet.id).child(myUid).setValue(liked ? true : null);
          XFirebaseUtils.userLikedTweetsRef(myUid).child(tweet.id).setValue(liked ? true : null);
          XFirebaseUtils.tweetRef(tweet.id).child("likeCount")
              .setValue(liked ? tweet.likeCount + 1 : Math.max(0, tweet.likeCount - 1));
          // Push notification to tweet author
          if (liked && !myUid.equals(tweet.authorUid)) {
              pushXNotification(tweet, "like");
          }
      }

      @Override public void onRetweet(XTweet tweet, boolean retweeted) {
          XFirebaseUtils.tweetRetweetsRef(tweet.id).child(myUid).setValue(retweeted ? true : null);
          XFirebaseUtils.userRetweetsRef(myUid).child(tweet.id).setValue(retweeted ? true : null);
          XFirebaseUtils.tweetRef(tweet.id).child("retweetCount")
              .setValue(retweeted ? tweet.retweetCount + 1 : Math.max(0, tweet.retweetCount - 1));
          if (retweeted && !myUid.equals(tweet.authorUid)) {
              pushXNotification(tweet, "retweet");
          }
      }

      @Override public void onReply(XTweet tweet) {
          startActivity(new Intent(requireContext(), XComposeActivity.class)
              .putExtra("reply_to_id", tweet.id)
              .putExtra("reply_to_handle", tweet.authorHandle));
      }

      @Override public void onBookmark(XTweet tweet) {
          boolean alreadyBkd = tweet.isBookmarkedBy(myUid);
          XFirebaseUtils.userBookmarksRef(myUid).child(tweet.id)
              .setValue(alreadyBkd ? null : true);
          Toast.makeText(requireContext(),
              alreadyBkd ? "Removed from Bookmarks" : "Added to Bookmarks",
              Toast.LENGTH_SHORT).show();
      }

      @Override public void onShare(XTweet tweet) {
          Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
          share.putExtra(Intent.EXTRA_TEXT,
              tweet.authorName + ": " + tweet.text + " — via CallX");
          startActivity(Intent.createChooser(share, "Share post"));
      }

      @Override public void onMore(XTweet tweet, View anchor) {
          Toast.makeText(requireContext(), "More options", Toast.LENGTH_SHORT).show();
      }

      private void pushXNotification(XTweet tweet, String type) {
          com.callx.app.models.XNotification notif = new com.callx.app.models.XNotification();
          notif.type        = type;
          notif.fromUid     = myUid;
          notif.tweetId     = tweet.id;
          notif.tweetSnippet= tweet.text;
          notif.timestamp   = System.currentTimeMillis();
          notif.read        = false;
          XFirebaseUtils.xNotificationsRef(tweet.authorUid).push().setValue(notif);
      }

      @Override public void onDestroyView() {
          super.onDestroyView();
          if (feedListener != null)
              XFirebaseUtils.globalFeedRef().removeEventListener(feedListener);
      }
  }