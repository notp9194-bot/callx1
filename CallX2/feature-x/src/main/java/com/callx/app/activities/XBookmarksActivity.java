package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.adapters.XTweetAdapter;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

public class XBookmarksActivity extends AppCompatActivity implements XTweetAdapter.OnTweetActionListener {

    private String myUid;
    private XTweetAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_bookmarks);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        findViewById(R.id.btn_x_bookmarks_back).setOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_x_bookmarks);
        adapter = new XTweetAdapter(this, this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        loadBookmarks();
    }

    private void loadBookmarks() {
        XFirebaseUtils.userBookmarksRef(myUid).get().addOnSuccessListener(snap -> {
            List<String> ids = new ArrayList<>();
            for (DataSnapshot ds : snap.getChildren()) ids.add(ds.getKey());
            List<XTweet> tweets = new ArrayList<>();
            long[] pending = {ids.size()};
            if (ids.isEmpty()) { adapter.setTweets(tweets); return; }
            for (String id : ids) {
                XFirebaseUtils.tweetRef(id).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot ds) {
                        XTweet t = ds.getValue(XTweet.class);
                        if (t != null && !t.isDeleted) { t.id = ds.getKey(); tweets.add(t); }
                        pending[0]--;
                        if (pending[0] <= 0) {
                            tweets.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                            adapter.setTweets(new ArrayList<>(tweets));
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) { pending[0]--; }
                });
            }
        });
    }

    @Override public void onLike(XTweet t, boolean l) {
        XFirebaseUtils.tweetLikesRef(t.id).child(myUid).setValue(l ? true : null);
        XFirebaseUtils.userLikedTweetsRef(myUid).child(t.id).setValue(l ? true : null);
        XFirebaseUtils.tweetRef(t.id).child("likeCount")
            .setValue(l ? t.likeCount + 1 : Math.max(0, t.likeCount - 1));
    }
    @Override public void onRetweet(XTweet t, boolean r) {
        XFirebaseUtils.tweetRetweetsRef(t.id).child(myUid).setValue(r ? true : null);
        XFirebaseUtils.userRetweetsRef(myUid).child(t.id).setValue(r ? true : null);
        XFirebaseUtils.tweetRef(t.id).child("retweetCount")
            .setValue(r ? t.retweetCount + 1 : Math.max(0, t.retweetCount - 1));
    }
    @Override public void onReply(XTweet t) {
        startActivity(new Intent(this, XComposeActivity.class)
            .putExtra("reply_to_id", t.id).putExtra("reply_to_handle", t.authorHandle));
    }
    @Override public void onQuote(XTweet t) {
        startActivity(new Intent(this, XComposeActivity.class).putExtra("quote_tweet_id", t.id));
    }
    @Override public void onBookmark(XTweet t) {
        XFirebaseUtils.userBookmarksRef(myUid).child(t.id).removeValue();
        adapter.removeTweet(t.id);
        Toast.makeText(this, "Removed from Bookmarks", Toast.LENGTH_SHORT).show();
    }
    @Override public void onShare(XTweet t) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, t.text + " — via CallX");
        startActivity(Intent.createChooser(i, "Share"));
    }
    @Override public void onMore(XTweet t, View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Remove bookmark");
        m.getMenu().add(0, 2, 0, "Copy link");
        m.getMenu().add(0, 3, 0, "Quote post");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: onBookmark(t); break;
                case 2:
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link",
                        "https://callx.app/x/tweet/" + t.id));
                    Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
                    break;
                case 3: onQuote(t); break;
            }
            return true;
        });
        m.show();
    }
}
