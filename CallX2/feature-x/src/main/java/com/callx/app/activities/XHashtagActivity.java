package com.callx.app.activities;

  import android.os.Bundle;
  import android.widget.TextView;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.adapters.XTweetAdapter;
  import com.callx.app.models.XTweet;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.firebase.database.*;
  import java.util.ArrayList;
  import java.util.Collections;
  import java.util.List;

  public class XHashtagActivity extends AppCompatActivity {

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_x_hashtag);

          String hashtag = getIntent().getStringExtra("hashtag");
          if (hashtag == null) { finish(); return; }

          TextView tvTitle = findViewById(R.id.tv_x_hashtag_title);
          tvTitle.setText(hashtag);
          findViewById(R.id.btn_x_hashtag_back).setOnClickListener(v -> finish());

          RecyclerView rv = findViewById(R.id.rv_x_hashtag_tweets);
          XTweetAdapter adapter = new XTweetAdapter(this, new XTweetAdapter.OnTweetActionListener() {
              @Override public void onLike(XTweet t, boolean l) {}
              @Override public void onRetweet(XTweet t, boolean r) {}
              @Override public void onReply(XTweet t) {}
              @Override public void onBookmark(XTweet t) {}
              @Override public void onShare(XTweet t) {}
              @Override public void onMore(XTweet t, android.view.View a) {}
          });
          rv.setLayoutManager(new LinearLayoutManager(this));
          rv.setAdapter(adapter);

          // Load tweets with this hashtag
          String tag = hashtag.replace("#", "").toLowerCase();
          XFirebaseUtils.hashtagFeedRef(tag).limitToLast(30).get()
              .addOnSuccessListener(snap -> {
                  List<String> ids = new ArrayList<>();
                  for (DataSnapshot ds : snap.getChildren()) ids.add(ds.getKey());
                  List<XTweet> list = new ArrayList<>();
                  for (String id : ids) {
                      XFirebaseUtils.tweetRef(id).get().addOnSuccessListener(ds2 -> {
                          XTweet t = ds2.getValue(XTweet.class);
                          if (t != null && !t.isDeleted) { t.id = ds2.getKey(); list.add(t); }
                          Collections.sort(list, (a, b) -> Long.compare(b.timestamp, a.timestamp));
                          adapter.setTweets(new ArrayList<>(list));
                      });
                  }
              });
      }
  }