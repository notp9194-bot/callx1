package com.callx.app.activities;

  import android.content.Intent;
  import android.os.Bundle;
  import android.view.View;
  import android.widget.EditText;
  import android.widget.ImageView;
  import android.widget.TextView;
  import android.widget.Toast;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.adapters.XTweetAdapter;
  import com.callx.app.models.XTweet;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.ArrayList;
  import java.util.Collections;
  import java.util.List;

  public class XTweetDetailActivity extends AppCompatActivity {

      private String tweetId, myUid;
      private XTweet rootTweet;
      private RecyclerView rvReplies;
      private XTweetAdapter repliesAdapter;
      private ValueEventListener tweetListener, repliesListener;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_x_tweet_detail);

          tweetId = getIntent().getStringExtra("tweet_id");
          myUid   = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

          findViewById(R.id.btn_x_detail_back).setOnClickListener(v -> finish());

          rvReplies = findViewById(R.id.rv_x_replies);
          repliesAdapter = new XTweetAdapter(this, new XTweetAdapter.OnTweetActionListener() {
              @Override public void onLike(XTweet tweet, boolean liked) { handleLike(tweet, liked); }
              @Override public void onRetweet(XTweet tweet, boolean r) {}
              @Override public void onReply(XTweet tweet) {
                  startActivity(new Intent(XTweetDetailActivity.this, XComposeActivity.class)
                      .putExtra("reply_to_id", tweet.id)
                      .putExtra("reply_to_handle", tweet.authorHandle));
              }
              @Override public void onBookmark(XTweet tweet) {}
              @Override public void onShare(XTweet tweet) {}
              @Override public void onMore(XTweet tweet, View anchor) {}
          });
          rvReplies.setLayoutManager(new LinearLayoutManager(this));
          rvReplies.setAdapter(repliesAdapter);

          // Reply box
          EditText etReply = findViewById(R.id.et_x_detail_reply);
          View btnSendReply = findViewById(R.id.btn_x_send_reply);
          btnSendReply.setOnClickListener(v -> {
              String text = etReply.getText().toString().trim();
              if (!text.isEmpty() && tweetId != null) {
                  Intent compose = new Intent(this, XComposeActivity.class)
                      .putExtra("reply_to_id", tweetId)
                      .putExtra("prefill", text);
                  startActivity(compose);
                  etReply.setText("");
              }
          });

          loadTweet();
          loadReplies();
      }

      private void loadTweet() {
          tweetListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  rootTweet = snap.getValue(XTweet.class);
                  if (rootTweet == null) return;
                  rootTweet.id = snap.getKey();
                  // Bind root tweet
                  ImageView ivAvatar = findViewById(R.id.iv_x_detail_avatar);
                  TextView tvName    = findViewById(R.id.tv_x_detail_name);
                  TextView tvHandle  = findViewById(R.id.tv_x_detail_handle);
                  TextView tvText    = findViewById(R.id.tv_x_detail_text);
                  TextView tvLikes   = findViewById(R.id.tv_x_detail_likes);
                  TextView tvRt      = findViewById(R.id.tv_x_detail_retweets);
                  TextView tvViews   = findViewById(R.id.tv_x_detail_views);

                  Glide.with(XTweetDetailActivity.this).load(rootTweet.authorPhotoUrl)
                      .circleCrop().into(ivAvatar);
                  tvName.setText(rootTweet.authorName);
                  tvHandle.setText("@" + rootTweet.authorHandle);
                  tvText.setText(rootTweet.text);
                  tvLikes.setText(fmt(rootTweet.likeCount) + " Likes");
                  tvRt.setText(fmt(rootTweet.retweetCount) + " Reposts");
                  tvViews.setText(fmt(rootTweet.viewCount) + " Views");

                  // Increment view count
                  XFirebaseUtils.tweetRef(tweetId).child("viewCount")
                      .setValue(rootTweet.viewCount + 1);
              }
              @Override public void onCancelled(DatabaseError e) {}
          };
          XFirebaseUtils.tweetRef(tweetId).addValueEventListener(tweetListener);
      }

      private void loadReplies() {
          repliesListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  List<String> replyIds = new ArrayList<>();
                  for (DataSnapshot ds : snap.getChildren()) replyIds.add(ds.getKey());
                  // Load each reply tweet
                  List<XTweet> replies = new ArrayList<>();
                  for (String rid : replyIds) {
                      XFirebaseUtils.tweetRef(rid).get().addOnSuccessListener(ds -> {
                          XTweet t = ds.getValue(XTweet.class);
                          if (t != null && !t.isDeleted) { t.id = ds.getKey(); replies.add(t); }
                          Collections.sort(replies, (a, b) -> Long.compare(a.timestamp, b.timestamp));
                          repliesAdapter.setTweets(new ArrayList<>(replies));
                      });
                  }
              }
              @Override public void onCancelled(DatabaseError e) {}
          };
          XFirebaseUtils.tweetRepliesRef(tweetId).addListenerForSingleValueEvent(repliesListener);
      }

      private void handleLike(XTweet tweet, boolean liked) {
          XFirebaseUtils.tweetLikesRef(tweet.id).child(myUid).setValue(liked ? true : null);
          XFirebaseUtils.tweetRef(tweet.id).child("likeCount")
              .setValue(liked ? tweet.likeCount + 1 : Math.max(0, tweet.likeCount - 1));
      }

      private String fmt(long n) {
          if (n < 1000) return String.valueOf(n);
          if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
          return String.format("%.1fM", n / 1_000_000.0);
      }

      @Override protected void onDestroy() {
          super.onDestroy();
          if (tweetListener != null) XFirebaseUtils.tweetRef(tweetId).removeEventListener(tweetListener);
      }
  }