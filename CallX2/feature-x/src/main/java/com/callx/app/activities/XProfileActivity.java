package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.adapters.XTweetAdapter;
import com.callx.app.models.XNotification;
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

        if (targetUid == null) targetUid = myUid;

        findViewById(R.id.btn_x_profile_back).setOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_x_profile_tweets);
        adapter = new XTweetAdapter(this, new XTweetAdapter.OnTweetActionListener() {
            @Override public void onLike(XTweet tweet, boolean liked) {
                XFirebaseUtils.tweetLikesRef(tweet.id).child(myUid).setValue(liked ? true : null);
                XFirebaseUtils.userLikedTweetsRef(myUid).child(tweet.id).setValue(liked ? true : null);
                XFirebaseUtils.tweetRef(tweet.id).child("likeCount")
                    .setValue(liked ? tweet.likeCount + 1 : Math.max(0, tweet.likeCount - 1));
            }
            @Override public void onRetweet(XTweet tweet, boolean rt) {
                XFirebaseUtils.tweetRetweetsRef(tweet.id).child(myUid).setValue(rt ? true : null);
                XFirebaseUtils.userRetweetsRef(myUid).child(tweet.id).setValue(rt ? true : null);
                XFirebaseUtils.tweetRef(tweet.id).child("retweetCount")
                    .setValue(rt ? tweet.retweetCount + 1 : Math.max(0, tweet.retweetCount - 1));
            }
            @Override public void onReply(XTweet tweet) {
                startActivity(new Intent(XProfileActivity.this, XComposeActivity.class)
                    .putExtra("reply_to_id", tweet.id)
                    .putExtra("reply_to_handle", tweet.authorHandle));
            }
            @Override public void onQuote(XTweet tweet) {
                startActivity(new Intent(XProfileActivity.this, XComposeActivity.class)
                    .putExtra("quote_tweet_id", tweet.id));
            }
            @Override public void onBookmark(XTweet tweet) {
                boolean bkd = tweet.isBookmarkedBy(myUid);
                XFirebaseUtils.userBookmarksRef(myUid).child(tweet.id).setValue(bkd ? null : true);
                Toast.makeText(XProfileActivity.this,
                    bkd ? "Removed from Bookmarks" : "Added to Bookmarks",
                    Toast.LENGTH_SHORT).show();
            }
            @Override public void onShare(XTweet tweet) {
                Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, tweet.text + " — via CallX");
                startActivity(Intent.createChooser(share, "Share post"));
            }
            @Override public void onMore(XTweet tweet, View anchor) {
                PopupMenu menu = new PopupMenu(XProfileActivity.this, anchor);
                boolean mine = myUid.equals(tweet.authorUid);
                if (mine) {
                    menu.getMenu().add(0, 1, 0, "Delete post");
                    menu.getMenu().add(0, 2, 0, tweet.isPinned ? "Unpin" : "Pin to profile");
                }
                menu.getMenu().add(0, 3, 0, "Copy link");
                menu.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        XFirebaseUtils.tweetRef(tweet.id).child("isDeleted").setValue(true);
                        XFirebaseUtils.globalFeedRef().child(tweet.id).removeValue();
                        XFirebaseUtils.userTweetsRef(myUid).child(tweet.id).removeValue();
                        adapter.removeTweet(tweet.id);
                    } else if (item.getItemId() == 2) {
                        boolean pin = !tweet.isPinned;
                        XFirebaseUtils.tweetRef(tweet.id).child("isPinned").setValue(pin);
                        XFirebaseUtils.xUserRef(myUid).child("pinnedTweetId")
                            .setValue(pin ? tweet.id : null);
                    } else if (item.getItemId() == 3) {
                        ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null)
                            cm.setPrimaryClip(ClipData.newPlainText("link",
                                "https://callx.app/x/tweet/" + tweet.id));
                        Toast.makeText(XProfileActivity.this, "Link copied",
                            Toast.LENGTH_SHORT).show();
                    }
                    return true;
                });
                menu.show();
            }
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
                xUser.uid = snap.getKey();
                bindProfile();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        XFirebaseUtils.xUserRef(targetUid).addValueEventListener(userListener);
    }

    private void bindProfile() {
        ImageView ivBanner   = findViewById(R.id.iv_x_profile_banner);
        ImageView ivAvatar   = findViewById(R.id.iv_x_profile_avatar);
        ImageView ivVerified = findViewById(R.id.iv_x_profile_verified);
        TextView tvName      = findViewById(R.id.tv_x_profile_name);
        TextView tvHandle    = findViewById(R.id.tv_x_profile_handle);
        TextView tvBio       = findViewById(R.id.tv_x_profile_bio);
        TextView tvFollowers = findViewById(R.id.tv_x_profile_followers);
        TextView tvFollowing = findViewById(R.id.tv_x_profile_following);
        MaterialButton btnFollow = findViewById(R.id.btn_x_follow);

        if (xUser.bannerUrl != null && !xUser.bannerUrl.isEmpty())
            Glide.with(this).load(xUser.bannerUrl).centerCrop().into(ivBanner);
        Glide.with(this).load(xUser.photoUrl).circleCrop().into(ivAvatar);
        tvName.setText(xUser.name);
        tvHandle.setText("@" + xUser.handle);
        tvBio.setText(xUser.bio != null ? xUser.bio : "");
        tvFollowers.setText(fmt(xUser.followerCount) + " Followers");
        tvFollowing.setText(fmt(xUser.followingCount) + " Following");
        ivVerified.setVisibility(xUser.verified || xUser.blueVerified ? View.VISIBLE : View.GONE);

        if (targetUid.equals(myUid)) {
            btnFollow.setText("Edit profile");
            btnFollow.setOnClickListener(v ->
                startActivity(new Intent(this, XEditProfileActivity.class)));
        } else {
            // Check follow status from followers map
            XFirebaseUtils.userFollowersRef(targetUid).child(myUid).get()
                .addOnSuccessListener(ds -> {
                    isFollowing = Boolean.TRUE.equals(ds.getValue(Boolean.class));
                    btnFollow.setText(isFollowing ? "Following" : "Follow");
                });
            btnFollow.setOnClickListener(v -> toggleFollow(btnFollow));
        }
    }

    private void toggleFollow(MaterialButton btn) {
        isFollowing = !isFollowing;
        btn.setText(isFollowing ? "Following" : "Follow");
        XFirebaseUtils.userFollowersRef(targetUid).child(myUid).setValue(isFollowing ? true : null);
        XFirebaseUtils.userFollowingRef(myUid).child(targetUid).setValue(isFollowing ? true : null);

        // Fix: correctly update target's followerCount
        XFirebaseUtils.xUserRef(targetUid).child("followerCount").get().addOnSuccessListener(ds -> {
            Long c = ds.getValue(Long.class);
            long newVal = Math.max(0, (c != null ? c : 0) + (isFollowing ? 1 : -1));
            XFirebaseUtils.xUserRef(targetUid).child("followerCount").setValue(newVal);
        });
        // Fix: correctly update MY followingCount
        XFirebaseUtils.xUserRef(myUid).child("followingCount").get().addOnSuccessListener(ds -> {
            Long c = ds.getValue(Long.class);
            long newVal = Math.max(0, (c != null ? c : 0) + (isFollowing ? 1 : -1));
            XFirebaseUtils.xUserRef(myUid).child("followingCount").setValue(newVal);
        });

        if (isFollowing) {
            // Push follow notification with my profile data
            com.callx.app.utils.FirebaseUtils.getUserRef(myUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        XNotification n = new XNotification();
                        n.type         = "follow";
                        n.fromUid      = myUid;
                        n.fromName     = snap.child("name").getValue(String.class);
                        n.fromPhotoUrl = snap.child("photoUrl").getValue(String.class);
                        if (n.fromName == null) n.fromName = "Someone";
                        n.timestamp    = System.currentTimeMillis();
                        n.read         = false;
                        n.notified     = false;
                        XFirebaseUtils.xNotificationsRef(targetUid).push().setValue(n);
                        XFirebaseUtils.xUnreadNotifCountRef(targetUid).get().addOnSuccessListener(ds -> {
                            Long c = ds.getValue(Long.class);
                            XFirebaseUtils.xUnreadNotifCountRef(targetUid)
                                .setValue(c != null ? c + 1 : 1);
                        });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        }
    }

    private void loadTweets() {
        tweetsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<XTweet> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XTweet t = ds.getValue(XTweet.class);
                    if (t != null && !t.isDeleted) { t.id = ds.getKey(); list.add(t); }
                }
                // Pinned tweet first
                if (xUser != null && xUser.pinnedTweetId != null) {
                    list.sort((a, b) -> {
                        if (xUser.pinnedTweetId.equals(a.id)) return -1;
                        if (xUser.pinnedTweetId.equals(b.id)) return 1;
                        return Long.compare(b.timestamp, a.timestamp);
                    });
                } else {
                    Collections.sort(list, (a, b) -> Long.compare(b.timestamp, a.timestamp));
                }
                adapter.setTweets(list);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        XFirebaseUtils.userTweetsRef(targetUid)
            .addValueEventListener(tweetsListener);
    }

    private String fmt(long n) {
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
        return String.format("%.1fM", n / 1_000_000.0);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (userListener != null)  XFirebaseUtils.xUserRef(targetUid).removeEventListener(userListener);
        if (tweetsListener != null) XFirebaseUtils.userTweetsRef(targetUid).removeEventListener(tweetsListener);
    }
}
