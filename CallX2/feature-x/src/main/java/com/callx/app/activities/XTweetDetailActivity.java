package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.XTweetAdapter;
import com.callx.app.models.XNotification;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

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

        if (tweetId == null) { finish(); return; }

        findViewById(R.id.btn_x_detail_back).setOnClickListener(v -> finish());

        rvReplies = findViewById(R.id.rv_x_replies);
        repliesAdapter = new XTweetAdapter(this, new XTweetAdapter.OnTweetActionListener() {
            @Override public void onLike(XTweet t, boolean l) {
                XFirebaseUtils.tweetLikesRef(t.id).child(myUid).setValue(l ? true : null);
                XFirebaseUtils.userLikedTweetsRef(myUid).child(t.id).setValue(l ? true : null);
                XFirebaseUtils.tweetRef(t.id).child("likeCount")
                    .setValue(l ? t.likeCount + 1 : Math.max(0, t.likeCount - 1));
            }
            @Override public void onRetweet(XTweet t, boolean r) {
                XFirebaseUtils.tweetRetweetsRef(t.id).child(myUid).setValue(r ? true : null);
                XFirebaseUtils.tweetRef(t.id).child("retweetCount")
                    .setValue(r ? t.retweetCount + 1 : Math.max(0, t.retweetCount - 1));
            }
            @Override public void onReply(XTweet t) {
                startActivity(new Intent(XTweetDetailActivity.this, XComposeActivity.class)
                    .putExtra("reply_to_id", t.id)
                    .putExtra("reply_to_handle", t.authorHandle));
            }
            @Override public void onQuote(XTweet t) {
                startActivity(new Intent(XTweetDetailActivity.this, XComposeActivity.class)
                    .putExtra("quote_tweet_id", t.id));
            }
            @Override public void onBookmark(XTweet t) {
                boolean bkd = t.isBookmarkedBy(myUid);
                XFirebaseUtils.userBookmarksRef(myUid).child(t.id).setValue(bkd ? null : true);
                Toast.makeText(XTweetDetailActivity.this,
                    bkd ? "Removed from Bookmarks" : "Added to Bookmarks", Toast.LENGTH_SHORT).show();
            }
            @Override public void onShare(XTweet t) {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, t.text + " — via CallX");
                startActivity(Intent.createChooser(i, "Share"));
            }
            @Override public void onMore(XTweet t, View a) {
                android.widget.PopupMenu m = new android.widget.PopupMenu(XTweetDetailActivity.this, a);
                m.getMenu().add(0, 1, 0, "Copy link");
                if (myUid.equals(t.authorUid)) m.getMenu().add(0, 2, 0, "Delete");
                m.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link",
                            "https://callx.app/x/tweet/" + t.id));
                        Toast.makeText(XTweetDetailActivity.this, "Link copied", Toast.LENGTH_SHORT).show();
                    } else if (item.getItemId() == 2) {
                        XFirebaseUtils.tweetRef(t.id).child("isDeleted").setValue(true);
                        repliesAdapter.removeTweet(t.id);
                    }
                    return true;
                });
                m.show();
            }
        });
        rvReplies.setLayoutManager(new LinearLayoutManager(this));
        rvReplies.setAdapter(repliesAdapter);

        // Quick reply box
        EditText etReply = findViewById(R.id.et_x_detail_reply);
        View btnSendReply = findViewById(R.id.btn_x_send_reply);
        if (btnSendReply != null) {
            btnSendReply.setOnClickListener(v -> {
                String text = etReply != null ? etReply.getText().toString().trim() : "";
                if (!text.isEmpty()) {
                    startActivity(new Intent(this, XComposeActivity.class)
                        .putExtra("reply_to_id", tweetId)
                        .putExtra("reply_to_handle",
                            rootTweet != null ? rootTweet.authorHandle : "")
                        .putExtra("prefill", text));
                    if (etReply != null) etReply.setText("");
                }
            });
        }

        // Share action on root tweet
        View btnShareRoot = findViewById(R.id.btn_x_detail_share);
        if (btnShareRoot != null) {
            btnShareRoot.setOnClickListener(v -> {
                if (rootTweet == null) return;
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, rootTweet.text + " — via CallX");
                startActivity(Intent.createChooser(i, "Share post"));
            });
        }

        loadTweet();
        loadReplies();
    }

    private void loadTweet() {
        tweetListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                rootTweet = snap.getValue(XTweet.class);
                if (rootTweet == null) return;
                rootTweet.id = snap.getKey();

                ImageView ivAvatar = findViewById(R.id.iv_x_detail_avatar);
                TextView tvName    = findViewById(R.id.tv_x_detail_name);
                TextView tvHandle  = findViewById(R.id.tv_x_detail_handle);
                TextView tvText    = findViewById(R.id.tv_x_detail_text);
                TextView tvLikes   = findViewById(R.id.tv_x_detail_likes);
                TextView tvRt      = findViewById(R.id.tv_x_detail_retweets);
                TextView tvViews   = findViewById(R.id.tv_x_detail_views);
                TextView tvReplies = findViewById(R.id.tv_x_detail_replies);

                if (ivAvatar != null)
                    Glide.with(XTweetDetailActivity.this).load(rootTweet.authorPhotoUrl)
                        .circleCrop().into(ivAvatar);
                if (tvName != null)   tvName.setText(rootTweet.authorName);
                if (tvHandle != null) tvHandle.setText("@" + rootTweet.authorHandle);
                if (tvText != null)   tvText.setText(rootTweet.text);
                if (tvLikes != null)  tvLikes.setText(fmt(rootTweet.likeCount) + " Likes");
                if (tvRt != null)     tvRt.setText(fmt(rootTweet.retweetCount) + " Reposts");
                if (tvViews != null)  tvViews.setText(fmt(rootTweet.viewCount) + " Views");
                if (tvReplies != null) tvReplies.setText(fmt(rootTweet.replyCount) + " Replies");

                // Like button
                View btnLike = findViewById(R.id.btn_x_detail_like);
                if (btnLike != null) {
                    btnLike.setOnClickListener(v -> {
                        boolean liked = rootTweet.isLikedBy(myUid);
                        XFirebaseUtils.tweetLikesRef(tweetId).child(myUid).setValue(!liked ? true : null);
                        XFirebaseUtils.userLikedTweetsRef(myUid).child(tweetId).setValue(!liked ? true : null);
                        XFirebaseUtils.tweetRef(tweetId).child("likeCount")
                            .setValue(!liked ? rootTweet.likeCount + 1 : Math.max(0, rootTweet.likeCount - 1));
                        if (!liked && !myUid.equals(rootTweet.authorUid))
                            pushNotif(rootTweet, "like");
                    });
                }

                // Retweet button
                View btnRt = findViewById(R.id.btn_x_detail_retweet);
                if (btnRt != null) {
                    btnRt.setOnClickListener(v -> {
                        boolean rted = rootTweet.isRetweetedBy(myUid);
                        XFirebaseUtils.tweetRetweetsRef(tweetId).child(myUid).setValue(!rted ? true : null);
                        XFirebaseUtils.tweetRef(tweetId).child("retweetCount")
                            .setValue(!rted ? rootTweet.retweetCount + 1 : Math.max(0, rootTweet.retweetCount - 1));
                        if (!rted && !myUid.equals(rootTweet.authorUid))
                            pushNotif(rootTweet, "retweet");
                    });
                }

                // Avatar tap → profile
                if (ivAvatar != null) {
                    ivAvatar.setOnClickListener(v -> startActivity(
                        new Intent(XTweetDetailActivity.this,
                            com.callx.app.activities.XProfileActivity.class)
                            .putExtra("uid", rootTweet.authorUid)));
                }

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
                if (snap.getChildrenCount() == 0) { repliesAdapter.setTweets(new java.util.ArrayList<>()); return; }
                List<XTweet> list = new java.util.ArrayList<>();
                long[] pending = { snap.getChildrenCount() };
                for (DataSnapshot ds : snap.getChildren()) {
                    String replyId = ds.getKey();
                    if (replyId == null) { pending[0]--; continue; }
                    XFirebaseUtils.tweetRef(replyId).addListenerForSingleValueEvent(
                        new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot tSnap) {
                                XTweet t = tSnap.getValue(XTweet.class);
                                if (t != null && !t.isDeleted) {
                                    t.id = tSnap.getKey(); list.add(t);
                                }
                                pending[0]--;
                                if (pending[0] <= 0) {
                                    list.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
                                    repliesAdapter.setTweets(new java.util.ArrayList<>(list));
                                }
                            }
                            @Override public void onCancelled(DatabaseError e) {
                                pending[0]--;
                            }
                        });
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        XFirebaseUtils.tweetRepliesRef(tweetId).addValueEventListener(repliesListener);
    }

    private void pushNotif(XTweet tweet, String type) {
        com.callx.app.utils.FirebaseUtils.getUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    XNotification n = new XNotification();
                    n.type         = type;
                    n.fromUid      = myUid;
                    n.fromName     = snap.child("name").getValue(String.class);
                    n.fromPhotoUrl = snap.child("thumbUrl").getValue(String.class);
                    if (n.fromPhotoUrl == null || n.fromPhotoUrl.isEmpty())
                        n.fromPhotoUrl = snap.child("photoUrl").getValue(String.class);
                    if (n.fromName == null) n.fromName = "Someone";
                    n.tweetId      = tweet.id;
                    n.tweetSnippet = tweet.text != null
                        ? tweet.text.substring(0, Math.min(tweet.text.length(), 80)) : "";
                    n.timestamp    = System.currentTimeMillis();
                    n.read         = false;
                    n.notified     = false;
                    XFirebaseUtils.xNotificationsRef(tweet.authorUid).push().setValue(n);
                    XFirebaseUtils.xUnreadNotifCountRef(tweet.authorUid).get()
                        .addOnSuccessListener(ds -> {
                            Long c = ds.getValue(Long.class);
                            XFirebaseUtils.xUnreadNotifCountRef(tweet.authorUid)
                                .setValue(c != null ? c + 1 : 1);
                        });
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private String fmt(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (tweetListener != null)  XFirebaseUtils.tweetRef(tweetId).removeEventListener(tweetListener);
        if (repliesListener != null) XFirebaseUtils.tweetRepliesRef(tweetId).removeEventListener(repliesListener);
    }
}
