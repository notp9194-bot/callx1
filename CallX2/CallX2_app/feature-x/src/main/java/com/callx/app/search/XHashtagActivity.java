package com.callx.app.search;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.callx.app.feed.XTweetAdapter;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
import com.callx.app.compose.XComposeActivity;

public class XHashtagActivity extends AppCompatActivity {

    private String myUid;
    private XTweetAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_hashtag);

        String hashtag = getIntent().getStringExtra("hashtag");
        if (hashtag == null) { finish(); return; }

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        TextView tvTitle = findViewById(R.id.tv_x_hashtag_title);
        tvTitle.setText(hashtag);
        findViewById(R.id.btn_x_hashtag_back).setOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_x_hashtag_tweets);
        adapter = new XTweetAdapter(this, new XTweetAdapter.OnTweetActionListener() {
            @Override public void onLike(XTweet t, boolean l) {
                XFirebaseUtils.tweetLikesRef(t.id).child(myUid).setValue(l ? true : null);
                XFirebaseUtils.tweetRef(t.id).child("likeCount")
                    .setValue(l ? t.likeCount + 1 : Math.max(0, t.likeCount - 1));
            }
            @Override public void onRetweet(XTweet t, boolean r) {
                XFirebaseUtils.tweetRetweetsRef(t.id).child(myUid).setValue(r ? true : null);
                XFirebaseUtils.tweetRef(t.id).child("retweetCount")
                    .setValue(r ? t.retweetCount + 1 : Math.max(0, t.retweetCount - 1));
            }
            @Override public void onReply(XTweet t) {
                startActivity(new Intent(XHashtagActivity.this, XComposeActivity.class)
                    .putExtra("reply_to_id", t.id).putExtra("reply_to_handle", t.authorHandle));
            }
            @Override public void onQuote(XTweet t) {
                startActivity(new Intent(XHashtagActivity.this, XComposeActivity.class)
                    .putExtra("quote_tweet_id", t.id));
            }
            @Override public void onBookmark(XTweet t) {
                boolean bkd = t.isBookmarkedBy(myUid);
                XFirebaseUtils.userBookmarksRef(myUid).child(t.id).setValue(bkd ? null : true);
                Toast.makeText(XHashtagActivity.this,
                    bkd ? "Removed from Bookmarks" : "Saved", Toast.LENGTH_SHORT).show();
            }
            @Override public void onShare(XTweet t) {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, t.text + " — via CallX");
                startActivity(Intent.createChooser(i, "Share"));
            }
            @Override public void onMore(XTweet t, View a) {
                PopupMenu m = new PopupMenu(XHashtagActivity.this, a);
                m.getMenu().add(0, 1, 0, "Copy link");
                m.getMenu().add(0, 2, 0, "Report");
                m.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link",
                            "https://callx.app/x/tweet/" + t.id));
                        Toast.makeText(XHashtagActivity.this, "Link copied", Toast.LENGTH_SHORT).show();
                    } else {
                        XFirebaseUtils.tweetReportsRef(t.id).child(myUid).setValue(true);
                        Toast.makeText(XHashtagActivity.this, "Reported", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
                m.show();
            }
        });
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        String tag = hashtag.replace("#", "").toLowerCase(java.util.Locale.US);
        XFirebaseUtils.hashtagFeedRef(tag).limitToLast(50).get()
            .addOnSuccessListener(snap -> {
                List<String> ids = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) ids.add(ds.getKey());
                List<XTweet> list = new ArrayList<>();
                for (String id : ids) {
                    XFirebaseUtils.tweetRef(id).get().addOnSuccessListener(ds2 -> {
                        XTweet t = ds2.getValue(XTweet.class);
                        if (t != null && !t.isDeleted) { t.id = ds2.getKey(); list.add(t); }
                        list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                        adapter.setTweets(new ArrayList<>(list));
                    });
                }
            });
    }
}
