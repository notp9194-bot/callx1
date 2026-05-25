package com.callx.app.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.callx.app.adapters.XTweetAdapter;
import com.callx.app.models.XNotification;
import com.callx.app.models.XProfile;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.utils.XProfileManager;
import com.callx.app.x.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * XProfileActivity — Full X profile screen.
 * Uses the new XProfile model + XProfileManager.
 * Tabs: Posts / Replies / Media / Likes
 *
 * Fixes applied:
 *  1. onDestroy() removes the Firebase real-time listener to prevent leaks & stale callbacks.
 *  2. profileLoadedOnce flag prevents loadCurrentTab() from firing on every real-time update.
 *  3. Renamed inner finish() helpers to dispatchTweets() to avoid shadowing Activity.finish().
 *  4. Guard against empty myUid being passed to Firebase child() calls.
 */
public class XProfileActivity extends AppCompatActivity {

    private static final int TAB_POSTS   = 0;
    private static final int TAB_REPLIES = 1;
    private static final int TAB_MEDIA   = 2;
    private static final int TAB_LIKES   = 3;

    private String targetUid, myUid;
    private XProfile xProfile;
    private boolean isFollowing;
    private ValueEventListener profileListener;
    private int currentTab = TAB_POSTS;

    /** Prevents loadCurrentTab() from firing on every real-time profile update. */
    private boolean profileLoadedOnce = false;

    private TabLayout tabLayout;
    private RecyclerView rv;
    private ProgressBar pbProfile;
    private XTweetAdapter adapter;

    private final ActivityResultLauncher<Intent> editProfileLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) loadProfile();
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_profile);

        targetUid = getIntent().getStringExtra("uid");
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        if (targetUid == null || targetUid.isEmpty()) targetUid = myUid;

        // Safety: if both are empty (not logged in), finish early to avoid Firebase "" path crash
        if (targetUid.isEmpty()) {
            Toast.makeText(this, "Please log in to view profiles", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        pbProfile = findViewById(R.id.pb_x_profile);
        tabLayout = findViewById(R.id.tab_x_profile);
        rv        = findViewById(R.id.rv_x_profile_tweets);

        adapter = new XTweetAdapter(this, makeTweetListener());
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        View btnBack = findViewById(R.id.btn_x_profile_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        View tvFollowers = findViewById(R.id.tv_x_profile_followers);
        View tvFollowing = findViewById(R.id.tv_x_profile_following);
        if (tvFollowers != null)
            tvFollowers.setOnClickListener(v ->
                Toast.makeText(this, "Followers list coming soon", Toast.LENGTH_SHORT).show());
        if (tvFollowing != null)
            tvFollowing.setOnClickListener(v ->
                Toast.makeText(this, "Following list coming soon", Toast.LENGTH_SHORT).show());

        View btnShare = findViewById(R.id.btn_x_profile_share);
        if (btnShare != null) btnShare.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && xProfile != null)
                cm.setPrimaryClip(ClipData.newPlainText("Profile",
                    "https://callx.app/x/@" + (xProfile.handle != null ? xProfile.handle : "")));
            Toast.makeText(this, "Profile link copied", Toast.LENGTH_SHORT).show();
        });

        setupTabs();
        loadProfile();

        if (!targetUid.equals(myUid))
            XProfileManager.incrementProfileViews(targetUid);
    }

    // ── Fix 1: Remove Firebase listener on destroy ────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        XProfileManager.stopObserving(targetUid, profileListener);
        profileListener = null;
    }

    // ── Tabs ─────────────────────────────────────────────────────────────────

    private void setupTabs() {
        if (tabLayout == null) return;
        tabLayout.addTab(tabLayout.newTab().setText("Posts"));
        tabLayout.addTab(tabLayout.newTab().setText("Replies"));
        tabLayout.addTab(tabLayout.newTab().setText("Media"));
        tabLayout.addTab(tabLayout.newTab().setText("Likes"));
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                loadCurrentTab();
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void loadCurrentTab() {
        if (pbProfile != null) pbProfile.setVisibility(View.VISIBLE);
        adapter.setTweets(new ArrayList<>());
        switch (currentTab) {
            case TAB_POSTS:   loadPosts();   break;
            case TAB_REPLIES: loadReplies(); break;
            case TAB_MEDIA:   loadMedia();   break;
            case TAB_LIKES:   loadLikes();   break;
        }
    }

    // ── Profile load ──────────────────────────────────────────────────────────

    private void loadProfile() {
        if (pbProfile != null) pbProfile.setVisibility(View.VISIBLE);
        XProfileManager.stopObserving(targetUid, profileListener);

        profileListener = XProfileManager.observe(targetUid, profile -> {
            if (isDestroyed() || isFinishing()) return;
            if (profile == null) {
                if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                return;
            }
            xProfile = profile;
            bindProfile();
            if (pbProfile != null) pbProfile.setVisibility(View.GONE);

            // Fix 2: Only load tabs on the FIRST profile snapshot.
            // Subsequent real-time updates (e.g. follower count change) should
            // refresh profile UI only — NOT re-fetch and clear the tweet list.
            if (!profileLoadedOnce) {
                profileLoadedOnce = true;
                loadCurrentTab();
            }
        });
    }

    // ── Profile bind ─────────────────────────────────────────────────────────

    private void bindProfile() {
        ImageView ivBanner   = findViewById(R.id.iv_x_profile_banner);
        ImageView ivAvatar   = findViewById(R.id.iv_x_profile_avatar);
        ImageView ivVerified = findViewById(R.id.iv_x_profile_verified);
        TextView  tvName     = findViewById(R.id.tv_x_profile_name);
        TextView  tvHandle   = findViewById(R.id.tv_x_profile_handle);
        TextView  tvBio      = findViewById(R.id.tv_x_profile_bio);
        TextView  tvFollowers= findViewById(R.id.tv_x_profile_followers);
        TextView  tvFollowing= findViewById(R.id.tv_x_profile_following);
        TextView  tvTweets   = findViewById(R.id.tv_x_profile_tweet_count);
        MaterialButton btnFollow = findViewById(R.id.btn_x_follow);

        if (xProfile.bannerUrl != null && !xProfile.bannerUrl.isEmpty() && ivBanner != null)
            Glide.with(this).load(xProfile.bannerUrl)
                .apply(new RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.ALL))
                .into(ivBanner);

        if (ivAvatar != null && xProfile.avatarForList() != null)
            Glide.with(this).load(xProfile.avatarForList())
                .apply(new RequestOptions().circleCrop().diskCacheStrategy(DiskCacheStrategy.ALL))
                .into(ivAvatar);

        if (tvName    != null) tvName.setText(xProfile.name != null ? xProfile.name : "");
        if (tvHandle  != null) tvHandle.setText(xProfile.handle != null ? "@" + xProfile.handle : "");
        if (tvBio     != null) tvBio.setText(xProfile.bio != null ? xProfile.bio : "");
        if (tvFollowers != null) tvFollowers.setText(fmt(xProfile.followerCount) + "\nFollowers");
        if (tvFollowing != null) tvFollowing.setText(fmt(xProfile.followingCount) + "\nFollowing");
        if (tvTweets  != null) tvTweets.setText(fmt(xProfile.tweetCount) + "\nPosts");
        if (ivVerified != null) ivVerified.setVisibility(
            (xProfile.verified || xProfile.blueVerified) ? View.VISIBLE : View.GONE);

        bindMeta();

        View ivLock = findViewById(R.id.iv_x_profile_lock);
        if (ivLock != null) ivLock.setVisibility(xProfile.privateAccount ? View.VISIBLE : View.GONE);

        if (targetUid.equals(myUid)) {
            if (btnFollow != null) {
                btnFollow.setText("Edit profile");
                btnFollow.setOnClickListener(v ->
                    editProfileLauncher.launch(new Intent(this, XEditProfileActivity.class)));
            }
        } else if (!myUid.isEmpty()) {
            // Fix 4: only query followers if myUid is non-empty
            XFirebaseUtils.userFollowersRef(targetUid).child(myUid).get()
                .addOnSuccessListener(ds -> {
                    isFollowing = Boolean.TRUE.equals(ds.getValue(Boolean.class));
                    if (btnFollow != null) {
                        btnFollow.setText(isFollowing ? "Following" : "Follow");
                        btnFollow.setOnClickListener(v -> toggleFollow(btnFollow));
                    }
                });
        }
    }

    private void bindMeta() {
        TextView tvLocation = findViewById(R.id.tv_x_profile_location);
        TextView tvWebsite  = findViewById(R.id.tv_x_profile_website);
        TextView tvJoined   = findViewById(R.id.tv_x_profile_joined);

        if (tvLocation != null) {
            if (xProfile.location != null && !xProfile.location.isEmpty()) {
                tvLocation.setText("📍 " + xProfile.location);
                tvLocation.setVisibility(View.VISIBLE);
            } else tvLocation.setVisibility(View.GONE);
        }
        if (tvWebsite != null) {
            if (xProfile.website != null && !xProfile.website.isEmpty()) {
                tvWebsite.setText("🔗 " + xProfile.website);
                tvWebsite.setVisibility(View.VISIBLE);
                tvWebsite.setOnClickListener(v ->
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(
                        xProfile.website.startsWith("http")
                            ? xProfile.website : "https://" + xProfile.website))));
            } else tvWebsite.setVisibility(View.GONE);
        }
        if (tvJoined != null && xProfile.joinedTs > 0) {
            java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault());
            tvJoined.setText("📅 Joined " + sdf.format(new Date(xProfile.joinedTs)));
            tvJoined.setVisibility(View.VISIBLE);
        }
    }

    // ── Tabs: Posts / Replies / Media / Likes ─────────────────────────────────

    private void loadPosts() {
        XFirebaseUtils.userTweetsRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<XTweet> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        XTweet t = ds.getValue(XTweet.class);
                        if (t != null && !t.isDeleted) { t.id = ds.getKey(); list.add(t); }
                    }
                    sortWithPinned(list);
                    adapter.setTweets(list);
                    if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                }
            });
    }

    private void loadReplies() {
        XFirebaseUtils.userRepliesRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<XTweet> list = new ArrayList<>();
                    long[] pending = {snap.getChildrenCount()};
                    if (pending[0] == 0) {
                        adapter.setTweets(list);
                        if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                        return;
                    }
                    for (DataSnapshot ds : snap.getChildren()) {
                        String id = ds.getKey();
                        if (id == null) { pending[0]--; continue; }
                        XFirebaseUtils.tweetRef(id)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot ts) {
                                    XTweet t = ts.getValue(XTweet.class);
                                    if (t != null && !t.isDeleted && t.replyToTweetId != null) {
                                        t.id = ts.getKey(); list.add(t);
                                    }
                                    // Fix 3: renamed from finish() to dispatchTweets() to avoid
                                    // shadowing Activity.finish()
                                    if (--pending[0] <= 0) dispatchTweets(list);
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    if (--pending[0] <= 0) dispatchTweets(list);
                                }
                                private void dispatchTweets(List<XTweet> l) {
                                    l.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                                    adapter.setTweets(l);
                                    if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                }
            });
    }

    private void loadMedia() {
        XFirebaseUtils.userTweetsRef(targetUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<XTweet> list = new ArrayList<>();
                    for (DataSnapshot ds : snap.getChildren()) {
                        XTweet t = ds.getValue(XTweet.class);
                        if (t != null && !t.isDeleted && t.isMedia()) { t.id = ds.getKey(); list.add(t); }
                    }
                    list.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                    adapter.setTweets(list);
                    if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                }
            });
    }

    private void loadLikes() {
        XFirebaseUtils.userLikedTweetsRef(targetUid).limitToLast(50)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    List<XTweet> list = new ArrayList<>();
                    long[] pending = {snap.getChildrenCount()};
                    if (pending[0] == 0) {
                        adapter.setTweets(list);
                        if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                        return;
                    }
                    for (DataSnapshot ds : snap.getChildren()) {
                        String id = ds.getKey();
                        if (id == null) { pending[0]--; continue; }
                        XFirebaseUtils.tweetRef(id)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override public void onDataChange(@NonNull DataSnapshot ts) {
                                    XTweet t = ts.getValue(XTweet.class);
                                    if (t != null && !t.isDeleted) { t.id = ts.getKey(); list.add(t); }
                                    // Fix 3: renamed from finish() to dispatchTweets()
                                    if (--pending[0] <= 0) dispatchTweets(list);
                                }
                                @Override public void onCancelled(@NonNull DatabaseError e) {
                                    if (--pending[0] <= 0) dispatchTweets(list);
                                }
                                private void dispatchTweets(List<XTweet> l) {
                                    l.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                                    adapter.setTweets(l);
                                    if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                                }
                            });
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (pbProfile != null) pbProfile.setVisibility(View.GONE);
                }
            });
    }

    private void sortWithPinned(List<XTweet> list) {
        String pinned = xProfile != null ? xProfile.pinnedTweetId : null;
        list.sort((a, b) -> {
            if (pinned != null && pinned.equals(a.id)) return -1;
            if (pinned != null && pinned.equals(b.id)) return 1;
            return Long.compare(b.timestamp, a.timestamp);
        });
    }

    // ── Follow / Unfollow ─────────────────────────────────────────────────────

    private void toggleFollow(MaterialButton btn) {
        if (myUid.isEmpty()) return; // Fix 4: guard against empty uid
        isFollowing = !isFollowing;
        btn.setText(isFollowing ? "Following" : "Follow");
        final boolean followed = isFollowing;

        if (followed) {
            XFirebaseUtils.userFollowersRef(targetUid).child(myUid).setValue(true);
            XFirebaseUtils.userFollowingRef(myUid).child(targetUid).setValue(true);
        } else {
            XFirebaseUtils.userFollowersRef(targetUid).child(myUid).removeValue();
            XFirebaseUtils.userFollowingRef(myUid).child(targetUid).removeValue();
        }

        XFirebaseUtils.xUserRef(targetUid).child("followerCount")
            .runTransaction(txHandler(followed));
        XFirebaseUtils.xUserRef(myUid).child("followingCount")
            .runTransaction(txHandler(followed));

        if (followed) pushFollowNotif();
    }

    private Transaction.Handler txHandler(boolean increment) {
        return new Transaction.Handler() {
            @NonNull @Override
            public Transaction.Result doTransaction(@NonNull MutableData d) {
                Long c = d.getValue(Long.class);
                d.setValue(Math.max(0, (c != null ? c : 0) + (increment ? 1 : -1)));
                return Transaction.success(d);
            }
            @Override public void onComplete(DatabaseError e, boolean c, DataSnapshot s) {}
        };
    }

    private void pushFollowNotif() {
        if (myUid.isEmpty()) return; // Fix 4: guard against empty uid
        com.callx.app.utils.FirebaseUtils.getUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    XNotification n = new XNotification();
                    n.type         = "follow";
                    n.fromUid      = myUid;
                    n.fromName     = snap.child("name").getValue(String.class);
                    n.fromPhotoUrl = snap.child("photoUrl").getValue(String.class);
                    n.fromThumbUrl = snap.child("thumbUrl").getValue(String.class);
                    if (n.fromName == null) n.fromName = "Someone";
                    n.timestamp    = System.currentTimeMillis();
                    n.read         = false;
                    n.notified     = false;
                    XFirebaseUtils.xNotificationsRef(targetUid).push().setValue(n);
                    XFirebaseUtils.xUnreadNotifCountRef(targetUid)
                        .runTransaction(txHandler(true));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Tweet actions ─────────────────────────────────────────────────────────

    private XTweetAdapter.OnTweetActionListener makeTweetListener() {
        return new XTweetAdapter.OnTweetActionListener() {
            @Override public void onLike(XTweet tweet, boolean liked) {
                if (tweet.id == null || myUid.isEmpty()) return;
                XFirebaseUtils.tweetLikesRef(tweet.id).child(myUid).setValue(liked ? true : null);
                XFirebaseUtils.userLikedTweetsRef(myUid).child(tweet.id).setValue(liked ? true : null);
                XFirebaseUtils.tweetRef(tweet.id).child("likeCount").runTransaction(txHandler(liked));
            }
            @Override public void onRetweet(XTweet tweet, boolean rt) {
                if (tweet.id == null || myUid.isEmpty()) return;
                XFirebaseUtils.tweetRetweetsRef(tweet.id).child(myUid).setValue(rt ? true : null);
                XFirebaseUtils.userRetweetsRef(myUid).child(tweet.id).setValue(rt ? true : null);
                XFirebaseUtils.tweetRef(tweet.id).child("retweetCount").runTransaction(txHandler(rt));
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
                if (tweet.id == null || myUid.isEmpty()) return;
                boolean bkd = tweet.isBookmarkedBy(myUid);
                XFirebaseUtils.userBookmarksRef(myUid).child(tweet.id).setValue(bkd ? null : true);
                XFirebaseUtils.tweetRef(tweet.id).child("bookmarkCount").runTransaction(txHandler(!bkd));
                Toast.makeText(XProfileActivity.this,
                    bkd ? "Removed from Bookmarks" : "Added to Bookmarks", Toast.LENGTH_SHORT).show();
            }
            @Override public void onShare(XTweet tweet) {
                Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, tweet.text + " — via CallX");
                startActivity(Intent.createChooser(share, "Share post"));
            }
            @Override public void onMore(XTweet tweet, View anchor) {
                android.widget.PopupMenu menu =
                    new android.widget.PopupMenu(XProfileActivity.this, anchor);
                boolean mine = myUid.equals(tweet.authorUid);
                if (mine) {
                    menu.getMenu().add(0, 1, 0, "Delete post");
                    menu.getMenu().add(0, 2, 0, tweet.isPinned ? "Unpin" : "Pin to profile");
                }
                menu.getMenu().add(0, 3, 0, "Copy link");
                menu.setOnMenuItemClickListener(item -> {
                    switch (item.getItemId()) {
                        case 1:
                            if (tweet.id == null) return false;
                            XFirebaseUtils.tweetRef(tweet.id).child("isDeleted").setValue(true);
                            XFirebaseUtils.globalFeedRef().child(tweet.id).removeValue();
                            XFirebaseUtils.userTweetsRef(myUid).child(tweet.id).removeValue();
                            adapter.removeTweet(tweet.id);
                            return true;
                        case 2:
                            if (tweet.id == null) return false;
                            boolean nowPinned = !tweet.isPinned;
                            XFirebaseUtils.tweetRef(tweet.id).child("isPinned").setValue(nowPinned);
                            if (nowPinned)
                                XFirebaseUtils.xUserRef(myUid).child("pinnedTweetId").setValue(tweet.id);
                            else
                                XFirebaseUtils.xUserRef(myUid).child("pinnedTweetId").removeValue();
                            return true;
                        case 3:
                            ClipboardManager cm = (ClipboardManager)
                                getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null && tweet.id != null)
                                cm.setPrimaryClip(ClipData.newPlainText("Post",
                                    "https://callx.app/x/post/" + tweet.id));
                            Toast.makeText(XProfileActivity.this,
                                "Link copied", Toast.LENGTH_SHORT).show();
                            return true;
                    }
                    return false;
                });
                menu.show();
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String fmt(long n) {
        if (n <= 0) return "0";
        if (n < 1000) return String.valueOf(n);
        if (n < 1_000_000) return String.format(Locale.US, "%.1fK", n / 1000.0);
        return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
    }
}
