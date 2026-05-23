package com.callx.app.activities;

  import android.content.Intent;
  import android.os.Bundle;
  import android.text.Editable;
  import android.text.TextWatcher;
  import android.widget.EditText;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.adapters.XTweetAdapter;
  import com.callx.app.models.XTweet;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import java.util.ArrayList;
  import java.util.List;

  public class XSearchActivity extends AppCompatActivity {

      private XTweetAdapter adapter;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_x_search);

          findViewById(R.id.btn_x_search_back).setOnClickListener(v -> finish());

          RecyclerView rv = findViewById(R.id.rv_x_search);
          adapter = new XTweetAdapter(this, new XTweetAdapter.OnTweetActionListener() {
              @Override public void onLike(XTweet t, boolean l) {}
              @Override public void onRetweet(XTweet t, boolean r) {}
              @Override public void onReply(XTweet t) { startActivity(new Intent(XSearchActivity.this, XComposeActivity.class).putExtra("reply_to_id", t.id)); }
              @Override public void onBookmark(XTweet t) {}
              @Override public void onShare(XTweet t) {}
              @Override public void onMore(XTweet t, android.view.View a) {}
          });
          rv.setLayoutManager(new LinearLayoutManager(this));
          rv.setAdapter(adapter);

          EditText et = findViewById(R.id.et_x_search_query);
          et.addTextChangedListener(new TextWatcher() {
              @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
              @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                  if (s.length() >= 2) search(s.toString().trim());
              }
              @Override public void afterTextChanged(Editable s) {}
          });
          et.requestFocus();
      }

      private void search(String q) {
          XFirebaseUtils.tweetsRef()
              .orderByChild("text").startAt(q).endAt(q + "\uf8ff").limitToFirst(20)
              .get().addOnSuccessListener(snap -> {
                  List<XTweet> list = new ArrayList<>();
                  for (com.google.firebase.database.DataSnapshot ds : snap.getChildren()) {
                      XTweet t = ds.getValue(XTweet.class);
                      if (t != null && !t.isDeleted) { t.id = ds.getKey(); list.add(t); }
                  }
                  adapter.setTweets(list);
              });
      }
  }