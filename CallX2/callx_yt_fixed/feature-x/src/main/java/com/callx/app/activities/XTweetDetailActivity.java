package com.callx.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.adapters.XTweetAdapter;
import com.callx.app.models.XNotification;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * XTweetDetailActivity — v25:
 *  ✅ viewCount increment via Transaction (race-condition safe)
 *  ✅ Replies loaded in batch (no N+1 per reply)
 *  ✅ likeCount/retweetCount via Transaction
 *  ✅ DiffUtil in XTweetAdapter
 *  ✅ Proper listener cleanup
 */
public class XTweetDetailActivity extends AppCompatActivity {

    private String tweetId, myUid;
    private XTweet rootTweet;
    private RecyclerView rvReplies;
    private XTweetAdapter repliesAdapter;
    private ValueEventListener tweetListener;
    private ProgressBar pbDetail;

    // Header views
    private ImageView ivAvatar, ivVerified, ivMedia;
    private TextView tvName, tvHandle, tvTime, tvText, tvLikes, tvRetweets, tvReplies, tvViews;
    private View btnLike, btnRetweet, btnReply, btnBookmark, btnShare;
    private ImageView icLike, icRetweet, icBookmark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_tweet_detail);

        tweetId = getIntent().getStringExtra("tweet_id");
        myUid   = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        pbDetail  = findViewById(R.id.pb_x_detail);
        rvReplies = findViewById(R.id.rv_x_detail_replies);

        repliesAdapter = new XTweetAdapter(this, makeActionListener());
        rvReplies.setLayoutManager(new LinearLayoutManager(this));
        rvReplies.setAdapter(repliesAdapter);
        rvReplies.setNestedScrollingEnabled(false);

        bindHeaderViews();
        View btnBack = findViewById(R.id.btn_x_detail_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Compose reply
        View etReply = findViewById(R.id.et_x_detail_reply);
        if (etReply != null) etReply.setOnClickListener(v -> {
            if (rootTweet != null)
                startActivity(new Intent(this, XComposeActivity.class)
                    .putExtra("reply_to_id", tweetId)
                    .putExtra("reply_to_handle", rootTweet.authorHandle));
        });

        loadTweet();
    }

    private void bindHeaderViews() {
        ivAvatar   = findViewById(R.id.iv_x_detail_avatar);
        ivVerified = findViewById(R.id.iv_x_detail_verified);
        ivMedia    = findViewById(R.id.iv_x_detail_media);
        tvName     = findViewById(R.id.tv_x_detail_name);
        tvHandle   = findViewById(R.id.tv_x_detail_handle);
        tvTime     = findViewById(R.id.tv_x_detail_time);
        tvText     = findViewById(R.id.tv_x_detail_text);
        tvLikes    = findViewById(R.id.tv_x_detail_likes);
        tvRetweets = findViewById(R.id.tv_x_detail_retweets);
        tvReplies  = findViewById(R.id.tv_x_detail_replies);
        tvViews    = findViewById(R.id.tv_x_detail_views);
        btnLike    = findViewById(R.id.btn_x_detail_like);
        btnRetweet = findViewById(R.id.btn_x_detail_retweet);
        btnReply   = findViewById(R.id.btn_x_detail_reply);
        btnBookmark= findViewById(R.id.btn_x_detail_bookmark);
        btnShare   = findViewById(R.id.btn_x_detail_share);
        icLike     = findViewById(R.id.ic_x_detail_like);
        icRetweet  = findViewById(R.id.ic_x_detail_retweet);
        icBookmark = findViewById(R.id.ic_x_detail_bookmark);
    }

    // ── Load root tweet ───────────────────────────────────────────────────────

    private void loadTweet() {
        if (tweetId == null) return;
        if (pbDetail != null) pbDetail.setVisibility(View.VISIBLE);
        tweetListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                rootTweet = snap.getValue(XTweet.class);
                if (rootTweet == null || rootTweet.isDeleted) { finish(); return; }
                rootTweet.id = snap.getKey();
                bindTweet();
                loadEngagement();
                loadRepliesBatch();
                if (pbDetail != null) pbDetail.setVisibility(View.GONE);
                // Increment view count atomically
                incrementViewCount();
            }
            @Override public void onCancelled(DatabaseError e) {
                if (pbDetail != null) pbDetail.setVisibility(View.GONE);
            }
        };
        XFirebaseUtils.tweetRef(tweetId).addValueEventListener(tweetListener);
    }

    private void incrementViewCount() {
        XFirebaseUtils.tweetRef(tweetId).child("viewCount")
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @NonNull @Override
                public com.google.firebase.database.Transaction.Result doTransaction(
                        @NonNull com.google.firebase.database.MutableData data) {
                    Long cur = data.getValue(Long.class);
                    data.setValue(cur != null ? cur + 1 : 1);
                    return com.google.firebase.database.Transaction.success(data);
                }
                @Override public void onComplete(DatabaseError e, boolean c, DataSnapshot s) {}
            });
    }

    private void bindTweet() {
        String avatarUrl = (rootTweet.authorThumbUrl != null && !rootTweet.authorThumbUrl.isEmpty())
            ? rootTweet.authorThumbUrl : rootTweet.authorPhotoUrl;
        if (ivAvatar != null) {
            Glide.with(this).load(avatarUrl)
                .apply(new RequestOptions().circleCrop().diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_person))
                .into(ivAvatar);
            if (rootTweet.authorUid != null)
                ivAvatar.setOnClickListener(v -> startActivity(
                    new Intent(this, XProfileActivity.class).putExtra("uid", rootTweet.authorUid)));
        }
        if (ivVerified != null) ivVerified.setVisibility(rootTweet.authorVerified ? View.VISIBLE : View.GONE);
        if (tvName   != null) tvName.setText(rootTweet.authorName);
        if (tvHandle != null) tvHandle.setText("@" + rootTweet.authorHandle);
        if (tvTime   != null) {
            String ts = new SimpleDateFormat("h:mm a · MMM d, yyyy", Locale.US)
                .format(new Date(rootTweet.timestamp));
            if (rootTweet.editedAt > 0) ts += " (edited)";
            tvTime.setText(ts);
        }
        if (tvText   != null) tvText.setText(rootTweet.text);

        // Media
        if (ivMedia != null && rootTweet.mediaUrl != null && !rootTweet.mediaUrl.isEmpty()) {
            ivMedia.setVisibility(View.VISIBLE);
            Glide.with(this).load(rootTweet.thumbnailUrl != null
                ? rootTweet.thumbnailUrl : rootTweet.mediaUrl)
                .apply(new RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.ALL))
                .into(ivMedia);
            boolean isVideo = "video".equals(rootTweet.mediaType);
            ivMedia.setOnClickListener(v -> {
                if (isVideo) startActivity(new Intent(this, XVideoPlayerActivity.class)
                    .putExtra("video_url", rootTweet.mediaUrl));
                else startActivity(new Intent(this, XImageViewerActivity.class)
                    .putExtra("image_url", rootTweet.mediaUrl));
            });
        }

        bindCounts();
        bindActionButtons();
    }

    private void bindCounts() {
        if (tvLikes   != null) tvLikes.setText(fmtFull(rootTweet.likeCount) + " Likes");
        if (tvRetweets!= null) tvRetweets.setText(fmtFull(rootTweet.retweetCount) + " Reposts");
        if (tvReplies != null) tvReplies.setText(fmtFull(rootTweet.replyCount) + " Replies");
        if (tvViews   != null) tvViews.setText(fmtFull(rootTweet.viewCount) + " Views");
    }

    private void loadEngagement() {
        if (myUid.isEmpty()) return;
        XFirebaseUtils.tweetLikesRef(tweetId).child(myUid).get()
            .addOnSuccessListener(ds -> {
                if (rootTweet.likes == null) rootTweet.likes = new java.util.HashMap<>();
                rootTweet.likes.put(myUid, ds.getValue() != null);
                updateLikeUI();
            });
        XFirebaseUtils.tweetRetweetsRef(tweetId).child(myUid).get()
            .addOnSuccessListener(ds -> {
                if (rootTweet.retweets == null) rootTweet.retweets = new java.util.HashMap<>();
                rootTweet.retweets.put(myUid, ds.getValue() != null);
                updateRetweetUI();
            });
        XFirebaseUtils.userBookmarksRef(myUid).child(tweetId).get()
            .addOnSuccessListener(ds -> {
                if (rootTweet.bookmarks == null) rootTweet.bookmarks = new java.util.HashMap<>();
                rootTweet.bookmarks.put(myUid, ds.getValue() != null);
                updateBookmarkUI();
            });
    }

    private void bindActionButtons() {
        if (btnLike     != null) btnLike.setOnClickListener(v -> toggleLike());
        if (btnRetweet  != null) btnRetweet.setOnClickListener(v -> toggleRetweet());
        if (btnReply    != null) btnReply.setOnClickListener(v ->
            startActivity(new Intent(this, XComposeActivity.class)
                .putExtra("reply_to_id", tweetId)
                .putExtra("reply_to_handle", rootTweet != null ? rootTweet.authorHandle : "")));
        if (btnBookmark != null) btnBookmark.setOnClickListener(v -> toggleBookmark());
        if (btnShare    != null) btnShare.setOnClickListener(v -> sharePost());
    }

    private void toggleLike() {
        if (rootTweet == null) return;
        boolean liked = !rootTweet.isLikedBy(myUid);
        if (rootTweet.likes == null) rootTweet.likes = new java.util.HashMap<>();
        rootTweet.likes.put(myUid, liked);
        rootTweet.likeCount = Math.max(0, rootTweet.likeCount + (liked ? 1 : -1));
        bindCounts(); updateLikeUI();
        XFirebaseUtils.tweetLikesRef(tweetId).child(myUid).setValue(liked ? true : null);
        XFirebaseUtils.userLikedTweetsRef(myUid).child(tweetId).setValue(liked ? true : null);
        XFirebaseUtils.tweetRef(tweetId).child("likeCount")
            .runTransaction(txCounter(liked));
    }

    private void toggleRetweet() {
        if (rootTweet == null) return;
        boolean rt = !rootTweet.isRetweetedBy(myUid);
        if (rootTweet.retweets == null) rootTweet.retweets = new java.util.HashMap<>();
        rootTweet.retweets.put(myUid, rt);
        rootTweet.retweetCount = Math.max(0, rootTweet.retweetCount + (rt ? 1 : -1));
        bindCounts(); updateRetweetUI();
        XFirebaseUtils.tweetRetweetsRef(tweetId).child(myUid).setValue(rt ? true : null);
        XFirebaseUtils.userRetweetsRef(myUid).child(tweetId).setValue(rt ? true : null);
        XFirebaseUtils.tweetRef(tweetId).child("retweetCount").runTransaction(txCounter(rt));
    }

    private void toggleBookmark() {
        if (rootTweet == null) return;
        boolean bkd = !rootTweet.isBookmarkedBy(myUid);
        if (rootTweet.bookmarks == null) rootTweet.bookmarks = new java.util.HashMap<>();
        rootTweet.bookmarks.put(myUid, bkd);
        rootTweet.bookmarkCount = Math.max(0, rootTweet.bookmarkCount + (bkd ? 1 : -1));
        bindCounts(); updateBookmarkUI();
        XFirebaseUtils.userBookmarksRef(myUid).child(tweetId).setValue(bkd ? true : null);
        XFirebaseUtils.tweetRef(tweetId).child("bookmarkCount").runTransaction(txCounter(bkd));
        Toast.makeText(this, bkd ? "Added to Bookmarks" : "Removed from Bookmarks", Toast.LENGTH_SHORT).show();
    }

    private void sharePost() {
        if (rootTweet == null) return;
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, rootTweet.text + "\nhttps://callx.app/x/tweet/" + tweetId);
        startActivity(Intent.createChooser(i, "Share post"));
    }

    private void updateLikeUI() {
        if (icLike != null) icLike.setColorFilter(rootTweet.isLikedBy(myUid)
            ? getColor(R.color.x_like_active) : getColor(R.color.x_icon_default));
    }
    private void updateRetweetUI() {
        if (icRetweet != null) icRetweet.setColorFilter(rootTweet.isRetweetedBy(myUid)
            ? getColor(R.color.x_retweet_active) : getColor(R.color.x_icon_default));
    }
    private void updateBookmarkUI() {
        if (icBookmark != null) icBookmark.setColorFilter(rootTweet.isBookmarkedBy(myUid)
            ? getColor(R.color.x_bookmark_active) : getColor(R.color.x_icon_default));
    }

    // ── Batch-load replies (no N+1) ───────────────────────────────────────────

    private void loadRepliesBatch() {
        XFirebaseUtils.tweetsRef()
            .orderByChild("replyToTweetId").equalTo(tweetId)
            .limitToLast(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    List<XTweet> replies = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        XTweet t = ds.getValue(XTweet.class);
                        if (t != null && !t.isDeleted) { t.id = ds.getKey(); replies.add(t); }
                    }
                    replies.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
                    repliesAdapter.setTweets(replies);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private com.google.firebase.database.Transaction.Handler txCounter(boolean increment) {
        return new com.google.firebase.database.Transaction.Handler() {
            @NonNull @Override
            public com.google.firebase.database.Transaction.Result doTransaction(
                    @NonNull com.google.firebase.database.MutableData d) {
                Long c = d.getValue(Long.class);
                d.setValue(Math.max(0, (c != null ? c : 0) + (increment ? 1 : -1)));
                return com.google.firebase.database.Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError e, boolean c, DataSnapshot s) {}
        };
    }

    private XTweetAdapter.OnTweetActionListener makeActionListener() {
        return new XTweetAdapter.OnTweetActionListener() {
            @Override public void onLike(XTweet t, boolean liked) {
                XFirebaseUtils.tweetLikesRef(t.id).child(myUid).setValue(liked ? true : null);
                XFirebaseUtils.tweetRef(t.id).child("likeCount").runTransaction(txCounter(liked));
            }
            @Override public void onRetweet(XTweet t, boolean rt) {
                XFirebaseUtils.tweetRetweetsRef(t.id).child(myUid).setValue(rt ? true : null);
                XFirebaseUtils.tweetRef(t.id).child("retweetCount").runTransaction(txCounter(rt));
            }
            @Override public void onReply(XTweet t) {
                startActivity(new Intent(XTweetDetailActivity.this, XComposeActivity.class)
                    .putExtra("reply_to_id", t.id).putExtra("reply_to_handle", t.authorHandle));
            }
            @Override public void onQuote(XTweet t) {
                startActivity(new Intent(XTweetDetailActivity.this, XComposeActivity.class)
                    .putExtra("quote_tweet_id", t.id));
            }
            @Override public void onBookmark(XTweet t) {
                boolean bkd = t.isBookmarkedBy(myUid);
                XFirebaseUtils.userBookmarksRef(myUid).child(t.id).setValue(bkd ? null : true);
                Toast.makeText(XTweetDetailActivity.this,
                    bkd ? "Removed" : "Added to Bookmarks", Toast.LENGTH_SHORT).show();
            }
            @Override public void onShare(XTweet t) {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
                i.putExtra(Intent.EXTRA_TEXT, t.text + " — via CallX");
                startActivity(Intent.createChooser(i, "Share"));
            }
            @Override public void onMore(XTweet t, View anchor) {}
        };
    }

    private String fmtFull(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.US, "%,.0f", (double) n);
        return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (tweetListener != null) XFirebaseUtils.tweetRef(tweetId).removeEventListener(tweetListener);
    }
}
