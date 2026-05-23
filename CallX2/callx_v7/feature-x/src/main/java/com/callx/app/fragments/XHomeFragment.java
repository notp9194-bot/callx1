package com.callx.app.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.callx.app.activities.XComposeActivity;
import com.callx.app.adapters.XTweetAdapter;
import com.callx.app.models.XNotification;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class XHomeFragment extends Fragment implements XTweetAdapter.OnTweetActionListener {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private XTweetAdapter adapter;
    private FloatingActionButton fabCompose;
    private ValueEventListener feedListener;
    private Query currentFeedRef;
    private String myUid;
    private int activeTab = 0;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_x_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        recyclerView = view.findViewById(R.id.rv_x_home);
        swipeRefresh = view.findViewById(R.id.swipe_x_home);
        fabCompose   = view.findViewById(R.id.fab_x_compose);

        adapter = new XTweetAdapter(requireContext(), this);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::loadFeed);
        fabCompose.setOnClickListener(v ->
            startActivity(new Intent(requireContext(), XComposeActivity.class)));

        view.findViewById(R.id.tab_x_for_you).setOnClickListener(v -> {
            activeTab = 0; updateTabUI(view); loadFeed();
        });
        view.findViewById(R.id.tab_x_following).setOnClickListener(v -> {
            activeTab = 1; updateTabUI(view); loadFeed();
        });
        updateTabUI(view);
        loadFeed();
    }

    private void updateTabUI(View view) {
        view.findViewById(R.id.tab_x_for_you).setSelected(activeTab == 0);
        view.findViewById(R.id.tab_x_following).setSelected(activeTab == 1);
    }

    private void loadFeed() {
        if (isDetached() || getContext() == null) return;
        swipeRefresh.setRefreshing(true);
        if (feedListener != null && currentFeedRef != null)
            currentFeedRef.removeEventListener(feedListener);

        currentFeedRef = activeTab == 0
            ? XFirebaseUtils.globalFeedRef().limitToLast(50)
            : XFirebaseUtils.userFeedRef(myUid).limitToLast(50);

        feedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                List<XTweet> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XTweet t = ds.getValue(XTweet.class);
                    if (t != null && !t.isDeleted) { t.id = ds.getKey(); list.add(t); }
                }
                Collections.sort(list, (a, b) -> Long.compare(b.timestamp, a.timestamp));
                adapter.setTweets(list);
                swipeRefresh.setRefreshing(false);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (isAdded()) swipeRefresh.setRefreshing(false);
            }
        };
        currentFeedRef.addValueEventListener(feedListener);
    }

    // ── Tweet Actions ───────────────────────────────────────────────────────

    @Override public void onLike(XTweet tweet, boolean liked) {
        XFirebaseUtils.tweetLikesRef(tweet.id).child(myUid).setValue(liked ? true : null);
        XFirebaseUtils.userLikedTweetsRef(myUid).child(tweet.id).setValue(liked ? true : null);
        XFirebaseUtils.tweetRef(tweet.id).child("likeCount")
            .setValue(liked ? tweet.likeCount + 1 : Math.max(0, tweet.likeCount - 1));
        if (liked && !myUid.equals(tweet.authorUid)) pushNotif(tweet, "like");
    }

    @Override public void onRetweet(XTweet tweet, boolean retweeted) {
        XFirebaseUtils.tweetRetweetsRef(tweet.id).child(myUid).setValue(retweeted ? true : null);
        XFirebaseUtils.userRetweetsRef(myUid).child(tweet.id).setValue(retweeted ? true : null);
        XFirebaseUtils.tweetRef(tweet.id).child("retweetCount")
            .setValue(retweeted ? tweet.retweetCount + 1 : Math.max(0, tweet.retweetCount - 1));
        if (retweeted && !myUid.equals(tweet.authorUid)) pushNotif(tweet, "retweet");
    }

    @Override public void onReply(XTweet tweet) {
        startActivity(new Intent(requireContext(), XComposeActivity.class)
            .putExtra("reply_to_id", tweet.id)
            .putExtra("reply_to_handle", tweet.authorHandle));
    }

    @Override public void onQuote(XTweet tweet) {
        startActivity(new Intent(requireContext(), XComposeActivity.class)
            .putExtra("quote_tweet_id", tweet.id));
    }

    @Override public void onBookmark(XTweet tweet) {
        boolean bkd = tweet.isBookmarkedBy(myUid);
        XFirebaseUtils.userBookmarksRef(myUid).child(tweet.id).setValue(bkd ? null : true);
        Toast.makeText(requireContext(),
            bkd ? "Removed from Bookmarks" : "Added to Bookmarks", Toast.LENGTH_SHORT).show();
    }

    @Override public void onShare(XTweet tweet) {
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, tweet.authorName + ": " + tweet.text + " — via CallX");
        startActivity(Intent.createChooser(i, "Share post"));
    }

    @Override public void onMore(XTweet tweet, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        boolean mine = myUid.equals(tweet.authorUid);
        if (mine) {
            menu.getMenu().add(0, 1, 0, "Delete post");
            menu.getMenu().add(0, 2, 0, tweet.isPinned ? "Unpin from profile" : "Pin to profile");
        } else {
            menu.getMenu().add(0, 3, 0, "Follow @" + tweet.authorHandle);
            menu.getMenu().add(0, 4, 0, "Mute @" + tweet.authorHandle);
            menu.getMenu().add(0, 5, 0, "Block @" + tweet.authorHandle);
            menu.getMenu().add(0, 6, 0, "Report post");
        }
        menu.getMenu().add(0, 7, 0, "Copy link");
        menu.getMenu().add(0, 8, 0, "Quote post");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: deleteTweet(tweet); break;
                case 2: togglePin(tweet); break;
                case 3: followUser(tweet); break;
                case 4: muteUser(tweet); break;
                case 5: blockUser(tweet); break;
                case 6: reportTweet(tweet); break;
                case 7: copyLink(tweet); break;
                case 8: onQuote(tweet); break;
            }
            return true;
        });
        menu.show();
    }

    private void deleteTweet(XTweet tweet) {
        XFirebaseUtils.tweetRef(tweet.id).child("isDeleted").setValue(true);
        XFirebaseUtils.globalFeedRef().child(tweet.id).removeValue();
        XFirebaseUtils.userTweetsRef(myUid).child(tweet.id).removeValue();
        XFirebaseUtils.userFeedRef(myUid).child(tweet.id).removeValue();
        adapter.removeTweet(tweet.id);
        Toast.makeText(requireContext(), "Post deleted", Toast.LENGTH_SHORT).show();
    }

    private void togglePin(XTweet tweet) {
        boolean pin = !tweet.isPinned;
        XFirebaseUtils.tweetRef(tweet.id).child("isPinned").setValue(pin);
        XFirebaseUtils.xUserRef(myUid).child("pinnedTweetId").setValue(pin ? tweet.id : null);
        Toast.makeText(requireContext(), pin ? "Post pinned to profile" : "Post unpinned",
            Toast.LENGTH_SHORT).show();
    }

    private void followUser(XTweet tweet) {
        XFirebaseUtils.userFollowersRef(tweet.authorUid).child(myUid).setValue(true);
        XFirebaseUtils.userFollowingRef(myUid).child(tweet.authorUid).setValue(true);
        XFirebaseUtils.xUserRef(tweet.authorUid).child("followerCount").get()
            .addOnSuccessListener(ds -> {
                Long c = ds.getValue(Long.class);
                XFirebaseUtils.xUserRef(tweet.authorUid).child("followerCount")
                    .setValue(c != null ? c + 1 : 1);
            });
        pushNotif(tweet, "follow");
        Toast.makeText(requireContext(), "Following @" + tweet.authorHandle, Toast.LENGTH_SHORT).show();
    }

    private void muteUser(XTweet tweet) {
        XFirebaseUtils.userMutedRef(myUid).child(tweet.authorUid).setValue(true);
        Toast.makeText(requireContext(), "Muted @" + tweet.authorHandle, Toast.LENGTH_SHORT).show();
    }

    private void blockUser(XTweet tweet) {
        XFirebaseUtils.userBlockedRef(myUid).child(tweet.authorUid).setValue(true);
        XFirebaseUtils.userBlockedRef(tweet.authorUid).child(myUid).setValue(true);
        Toast.makeText(requireContext(), "Blocked @" + tweet.authorHandle, Toast.LENGTH_SHORT).show();
    }

    private void reportTweet(XTweet tweet) {
        XFirebaseUtils.tweetReportsRef(tweet.id).child(myUid).setValue(true);
        Toast.makeText(requireContext(), "Reported. Thank you.", Toast.LENGTH_SHORT).show();
    }

    private void copyLink(XTweet tweet) {
        ClipboardManager cm = (ClipboardManager)
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null)
            cm.setPrimaryClip(ClipData.newPlainText("Post link",
                "https://callx.app/x/tweet/" + tweet.id));
        Toast.makeText(requireContext(), "Link copied", Toast.LENGTH_SHORT).show();
    }

    private void pushNotif(XTweet tweet, String type) {
        com.callx.app.utils.FirebaseUtils.getUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    XNotification n = new XNotification();
                    n.type         = type;
                    n.fromUid      = myUid;
                    n.fromName     = snap.child("name").getValue(String.class);
                    n.fromPhotoUrl = snap.child("photoUrl").getValue(String.class);
                    n.fromThumbUrl = snap.child("thumbUrl").getValue(String.class);
                    if (n.fromName == null) n.fromName = "Someone";
                    if (n.fromPhotoUrl == null) n.fromPhotoUrl = "";
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
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (feedListener != null && currentFeedRef != null)
            currentFeedRef.removeEventListener(feedListener);
    }
}
