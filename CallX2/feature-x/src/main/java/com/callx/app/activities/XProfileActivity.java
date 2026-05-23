package com.callx.app.activities;

  import android.os.Bundle;
  import android.view.View;
  import android.widget.ImageView;
  import android.widget.TextView;
  import android.widget.Toast;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.adapters.XTweetAdapter;
  import com.callx.app.models.XTweet;
  import com.callx.app.models.XUser;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.android.material.button.MaterialButton;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.ArrayList;
  import java.util.Collections;
  import java.util.List;

  public class XProfileActivity extends AppCompatActivity {

      private String targetUid, myUid;
      private XUser xUser;
      private boolean isFollowing;
      private ValueEventListener userListener, tweetsListener;
      private XTweetAdapter adapter;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_x_profile);

          targetUid = getIntent().getStringExtra("uid");
          myUid     = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

          findViewById(R.id.btn_x_profile_back).setOnClickListener(v -> finish());

          RecyclerView rv = findViewById(R.id.rv_x_profile_tweets);
          adapter = new XTweetAdapter(this, new XTweetAdapter.OnTweetActionListener() {
              @Override public void onLike(XTweet tweet, boolean liked) {
                  XFirebaseUtils.tweetRef(tweet.id).child("likeCount")
                      .setValue(liked ? tweet.likeCount+1 : Math.max(0,tweet.likeCount-1));
              }
              @Override public void onRetweet(XTweet t, boolean r) {}
              @Override public void onReply(XTweet t) {}
              @Override public void onBookmark(XTweet t) {}
              @Override public void onShare(XTweet t) {}
              @Override public void onMore(XTweet t, View a) {}
          });
          rv.setLayoutManager(new LinearLayoutManager(this));
          rv.setAdapter(adapter);

          loadProfile();
          loadTweets();
      }

      private void loadProfile() {
          userListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  xUser = snap.getValue(XUser.class);
                  if (xUser == null) return;
                  bindProfile();
              }
              @Override public void onCancelled(DatabaseError e) {}
          };
          XFirebaseUtils.xUserRef(targetUid).addValueEventListener(userListener);
      }

      private void bindProfile() {
          ImageView ivBanner  = findViewById(R.id.iv_x_profile_banner);
          ImageView ivAvatar  = findViewById(R.id.iv_x_profile_avatar);
          ImageView ivVerified= findViewById(R.id.iv_x_profile_verified);
          TextView tvName     = findViewById(R.id.tv_x_profile_name);
          TextView tvHandle   = findViewById(R.id.tv_x_profile_handle);
          TextView tvBio      = findViewById(R.id.tv_x_profile_bio);
          TextView tvFollowers= findViewById(R.id.tv_x_profile_followers);
          TextView tvFollowing= findViewById(R.id.tv_x_profile_following);
          MaterialButton btnFollow = findViewById(R.id.btn_x_follow);

          if (xUser.bannerUrl != null) Glide.with(this).load(xUser.bannerUrl).centerCrop().into(ivBanner);
          Glide.with(this).load(xUser.photoUrl).circleCrop().into(ivAvatar);
          tvName.setText(xUser.name);
          tvHandle.setText("@" + xUser.handle);
          tvBio.setText(xUser.bio != null ? xUser.bio : "");
          tvFollowers.setText(fmt(xUser.followerCount) + " Followers");
          tvFollowing.setText(fmt(xUser.followingCount) + " Following");
          ivVerified.setVisibility(xUser.verified || xUser.blueVerified ? View.VISIBLE : View.GONE);

          // Self profile
          if (targetUid.equals(myUid)) {
              btnFollow.setText("Edit profile");
              btnFollow.setOnClickListener(v ->
                  Toast.makeText(this, "Edit profile coming soon", Toast.LENGTH_SHORT).show());
          } else {
              isFollowing = xUser.isFollowedBy(myUid);
              btnFollow.setText(isFollowing ? "Following" : "Follow");
              btnFollow.setOnClickListener(v -> toggleFollow());
          }
      }

      private void toggleFollow() {
          isFollowing = !isFollowing;
          MaterialButton btn = findViewById(R.id.btn_x_follow);
          btn.setText(isFollowing ? "Following" : "Follow");
          // Update Firebase
          XFirebaseUtils.userFollowersRef(targetUid).child(myUid).setValue(isFollowing ? true : null);
          XFirebaseUtils.userFollowingRef(myUid).child(targetUid).setValue(isFollowing ? true : null);
          long delta = isFollowing ? 1 : -1;
          if (xUser != null) {
              XFirebaseUtils.xUserRef(targetUid).child("followerCount")
                  .setValue(Math.max(0, xUser.followerCount + delta));
              XFirebaseUtils.xUserRef(myUid).child("followingCount")
                  .setValue(Math.max(0, delta));
          }
          // Push follow notification
          if (isFollowing) {
              com.callx.app.models.XNotification notif = new com.callx.app.models.XNotification();
              notif.type = "follow";
              notif.fromUid = myUid;
              notif.timestamp = System.currentTimeMillis();
              XFirebaseUtils.xNotificationsRef(targetUid).push().setValue(notif);
              XFirebaseUtils.xUnreadNotifCountRef(targetUid).get().addOnSuccessListener(ds -> {
                  Long c = ds.getValue(Long.class);
                  XFirebaseUtils.xUnreadNotifCountRef(targetUid).setValue(c != null ? c + 1 : 1);
              });
          }
      }

      private void loadTweets() {
          tweetsListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  List<XTweet> list = new ArrayList<>();
                  for (DataSnapshot ds : snap.getChildren()) {
                      XTweet t = ds.getValue(XTweet.class);
                      if (t != null && !t.isDeleted && targetUid.equals(t.authorUid)) {
                          t.id = ds.getKey(); list.add(t);
                      }
                  }
                  Collections.sort(list, (a, b) -> Long.compare(b.timestamp, a.timestamp));
                  adapter.setTweets(list);
              }
              @Override public void onCancelled(DatabaseError e) {}
          };
          XFirebaseUtils.tweetsRef().orderByChild("authorUid").equalTo(targetUid)
              .limitToLast(30).addValueEventListener(tweetsListener);
      }

      private String fmt(long n) {
          if (n < 1000) return String.valueOf(n);
          if (n < 1_000_000) return String.format("%.1fK", n/1000.0);
          return String.format("%.1fM", n/1_000_000.0);
      }

      @Override protected void onDestroy() {
          super.onDestroy();
          if (userListener != null) XFirebaseUtils.xUserRef(targetUid).removeEventListener(userListener);
          if (tweetsListener != null) XFirebaseUtils.tweetsRef().removeEventListener(tweetsListener);
      }
  }