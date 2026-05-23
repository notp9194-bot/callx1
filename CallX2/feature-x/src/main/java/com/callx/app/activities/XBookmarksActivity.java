package com.callx.app.activities;

  import android.os.Bundle;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.adapters.XTweetAdapter;
  import com.callx.app.models.XTweet;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.ArrayList;
  import java.util.Collections;
  import java.util.List;

  public class XBookmarksActivity extends AppCompatActivity {

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_x_bookmarks);

          String myUid = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

          findViewById(R.id.btn_x_bookmarks_back).setOnClickListener(v -> finish());

          RecyclerView rv = findViewById(R.id.rv_x_bookmarks);
          XTweetAdapter[] adapterHolder = new XTweetAdapter[1];
          adapterHolder[0] = new XTweetAdapter(this, new XTweetAdapter.OnTweetActionListener() {
              @Override public void onLike(XTweet t, boolean l) {}
              @Override public void onRetweet(XTweet t, boolean r) {}
              @Override public void onReply(XTweet t) {}
              @Override public void onBookmark(XTweet tweet) {
                  // Remove bookmark
                  XFirebaseUtils.userBookmarksRef(myUid).child(tweet.id).removeValue();
                  adapterHolder[0].removeTweet(tweet.id);
              }
              @Override public void onShare(XTweet t) {}
              @Override public void onMore(XTweet t, android.view.View a) {}
          });
          XTweetAdapter adapter = adapterHolder[0];
          rv.setLayoutManager(new LinearLayoutManager(this));
          rv.setAdapter(adapter);

          XFirebaseUtils.userBookmarksRef(myUid).get().addOnSuccessListener(snap -> {
              List<String> ids = new ArrayList<>();
              for (DataSnapshot ds : snap.getChildren()) ids.add(ds.getKey());
              List<XTweet> tweets = new ArrayList<>();
              for (String id : ids) {
                  XFirebaseUtils.tweetRef(id).get().addOnSuccessListener(ds -> {
                      XTweet t = ds.getValue(XTweet.class);
                      if (t != null && !t.isDeleted) { t.id = ds.getKey(); tweets.add(t); }
                      Collections.sort(tweets, (a, b) -> Long.compare(b.timestamp, a.timestamp));
                      adapter.setTweets(new ArrayList<>(tweets));
                  });
              }
          });
      }
  }