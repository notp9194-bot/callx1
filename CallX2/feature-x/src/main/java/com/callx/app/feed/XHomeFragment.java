package com.callx.app.feed;
// (PushNotify import added for FCM X notifications — v24 fix)

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import com.callx.app.compose.XComposeActivity;
import com.callx.app.cache.XTweetCacheManager;
import com.callx.app.cache.XTweetImagePreloader;
import com.callx.app.cache.XTweetMediaPreloader;
import com.callx.app.models.XNotification;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.callx.app.x.R;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

public class XHomeFragment extends Fragment implements XTweetAdapter.OnTweetActionListener {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private View layoutEmpty;
    private View layoutError;
    private TextView tvEmptyTitle;
    private TextView tvEmptySubtitle;
    private View btnRetry;
    private XTweetAdapter adapter;
    private FloatingActionButton fabCompose;
    private ValueEventListener feedListener;
    private Query currentFeedRef;
    private String myUid;
    private int activeTab = 0;

    // Pagination
    private static final int PAGE_SIZE = 30;
    private long oldestTimestamp = Long.MAX_VALUE;
    private boolean isLoadingMore = false;
    private boolean hasMorePages  = true;

    // Block/mute sets (loaded once on start)
    private final Set<String> blockedUids = new HashSet<>();
    private final Set<String> mutedUids   = new HashSet<>();

    // Preloaders
    private XTweetMediaPreloader mediaPreloader;
    private XTweetImagePreloader imagePreloader;

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

        recyclerView  = view.findViewById(R.id.rv_x_home);
        swipeRefresh  = view.findViewById(R.id.swipe_x_home);
        fabCompose    = view.findViewById(R.id.fab_x_compose);
        layoutEmpty   = view.findViewById(R.id.layout_x_empty);
        layoutError   = view.findViewById(R.id.layout_x_error);
        tvEmptyTitle  = view.findViewById(R.id.tv_x_empty_title);
        tvEmptySubtitle = view.findViewById(R.id.tv_x_empty_subtitle);
        btnRetry      = view.findViewById(R.id.btn_x_retry);

        adapter = new XTweetAdapter(requireContext(), this);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        recyclerView.setItemViewCacheSize(20);

        swipeRefresh.setColorSchemeResources(R.color.x_accent);
        swipeRefresh.setOnRefreshListener(this::refreshFeed);

        if (fabCompose != null)
            fabCompose.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), XComposeActivity.class)));

        if (btnRetry != null)
            btnRetry.setOnClickListener(v -> refreshFeed());

        XTweetCacheManager.init(requireContext());
        mediaPreloader = new XTweetMediaPreloader(requireContext());
        imagePreloader = new XTweetImagePreloader(requireContext());

        // Infinite scroll + media preload
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int firstVisible = lm.findFirstVisibleItemPosition();
                mediaPreloader.preloadFrom(adapter.getTweets(), firstVisible);
                imagePreloader.preloadFrom(adapter.getTweets(), firstVisible);
                int lastVisible = lm.findLastVisibleItemPosition();
                int total = adapter.getItemCount();
                if (!isLoadingMore && hasMorePages && lastVisible >= total - 5) {
                    loadMoreFeed();
                }
            }
        });

        view.findViewById(R.id.tab_x_for_you).setOnClickListener(v -> {
            activeTab = 0; updateTabUI(view); refreshFeed();
        });
        view.findViewById(R.id.tab_x_following).setOnClickListener(v -> {
            activeTab = 1; updateTabUI(view); refreshFeed();
        });
        updateTabUI(view);

        // Load block/mute lists, then feed
        loadBlockMuteLists(() -> refreshFeed());
    }

    private void updateTabUI(View view) {
        if (view == null) return;
        view.findViewById(R.id.tab_x_for_you).setSelected(activeTab == 0);
        view.findViewById(R.id.tab_x_following).setSelected(activeTab == 1);
        // Update empty state text based on active tab
        if (tvEmptyTitle != null)
            tvEmptyTitle.setText(activeTab == 0 ? "Welcome to X" : "Nothing here yet");
        if (tvEmptySubtitle != null)
            tvEmptySubtitle.setText(activeTab == 0
                ? "Posts from people you follow will appear here. Follow some accounts to get started."
                : "When you follow someone, their posts show up here. Find people to follow in Explore.");
    }

    // ── State visibility helpers ─────────────────────────────────────────────

    private void showFeed() {
        if (recyclerView  != null) recyclerView.setVisibility(View.VISIBLE);
        if (layoutEmpty   != null) layoutEmpty.setVisibility(View.GONE);
        if (layoutError   != null) layoutError.setVisibility(View.GONE);
    }

    private void showEmpty() {
        if (recyclerView != null) recyclerView.setVisibility(View.GONE);
        if (layoutEmpty  != null) layoutEmpty.setVisibility(View.VISIBLE);
        if (layoutError  != null) layoutError.setVisibility(View.GONE);
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
    }

    private void showError() {
        if (adapter.getItemCount() > 0) {
            // Already has content — don't replace with error screen, just stop refreshing
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            return;
        }
        if (recyclerView != null) recyclerView.setVisibility(View.GONE);
        if (layoutEmpty  != null) layoutEmpty.setVisibility(View.GONE);
        if (layoutError  != null) layoutError.setVisibility(View.VISIBLE);
        if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
    }

    // ── Block / Mute loading ─────────────────────────────────────────────────

    private void loadBlockMuteLists(Runnable onDone) {
        if (myUid.isEmpty()) { onDone.run(); return; }
        final int[] pending = {2};
        Runnable check = () -> { if (--pending[0] == 0) onDone.run(); };

        XFirebaseUtils.userBlockedRef(myUid).get().addOnSuccessListener(snap -> {
            blockedUids.clear();
            for (DataSnapshot ds : snap.getChildren()) blockedUids.add(ds.getKey());
            check.run();
        }).addOnFailureListener(e -> check.run());

        XFirebaseUtils.userMutedRef(myUid).get().addOnSuccessListener(snap -> {
            mutedUids.clear();
            for (DataSnapshot ds : snap.getChildren()) mutedUids.add(ds.getKey());
            check.run();
        }).addOnFailureListener(e -> check.run());
    }

    // ── Feed loading ─────────────────────────────────────────────────────────

    private void refreshFeed() {
        if (isDetached() || getContext() == null) return;
        oldestTimestamp = Long.MAX_VALUE;
        hasMorePages    = true;
        isLoadingMore   = false;
        adapter.setTweets(new ArrayList<>());
        if (swipeRefresh != null) swipeRefresh.setRefreshing(true);
        if (layoutEmpty  != null) layoutEmpty.setVisibility(View.GONE);
        if (layoutError  != null) layoutError.setVisibility(View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(View.VISIBLE);
        if (feedListener != null && currentFeedRef != null)
            currentFeedRef.removeEventListener(feedListener);
        loadFeedPage(true);
    }

    private void loadFeedPage(boolean replace) {
        Query query;
        if (activeTab == 0) {
            query = XFirebaseUtils.globalFeedRef()
                .orderByChild("timestamp")
                .endAt(oldestTimestamp == Long.MAX_VALUE ? Double.MAX_VALUE : (double)(oldestTimestamp - 1))
                .limitToLast(PAGE_SIZE);
        } else {
            query = XFirebaseUtils.userFeedRef(myUid)
                .orderByChild("timestamp")
                .endAt(oldestTimestamp == Long.MAX_VALUE ? Double.MAX_VALUE : (double)(oldestTimestamp - 1))
                .limitToLast(PAGE_SIZE);
        }
        currentFeedRef = query;
        feedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                List<XTweet> page = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XTweet t = ds.getValue(XTweet.class);
                    if (t == null || t.isDeleted) continue;
                    t.id = ds.getKey();
                    if (blockedUids.contains(t.authorUid)) continue;
                    if (mutedUids.contains(t.authorUid))   continue;
                    // For "For You" tab: skip followers-only posts from other accounts
                    if (activeTab == 0 && "followers".equals(t.audience)
                            && !t.authorUid.equals(myUid)) continue;
                    page.add(t);
                }
                page.sort((a, b) -> Long.compare(b.timestamp, a.timestamp));
                hasMorePages = page.size() >= PAGE_SIZE;
                if (!page.isEmpty())
                    oldestTimestamp = page.get(page.size() - 1).timestamp;

                if (replace) {
                    adapter.setTweets(page);
                } else {
                    List<XTweet> merged = new ArrayList<>(adapter.getTweets());
                    merged.addAll(page);
                    adapter.setTweets(merged);
                }

                isLoadingMore = false;

                // Show correct state
                if (adapter.getItemCount() == 0 && replace) {
                    showEmpty();
                } else {
                    showFeed();
                }

                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                loadEngagementStates(page);
            }

            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (!isAdded()) return;
                isLoadingMore = false;
                showError();
            }
        };
        currentFeedRef.addValueEventListener(feedListener);
    }

    private void loadMoreFeed() {
        if (isLoadingMore || !hasMorePages || myUid.isEmpty()) return;
        isLoadingMore = true;
        loadFeedPage(false);
    }

    /** Load like/retweet/bookmark states in batch */
    private void loadEngagementStates(List<XTweet> tweets) {
        if (myUid.isEmpty() || !isAdded()) return;
        for (XTweet tweet : tweets) {
            final XTweet t = tweet;
            XFirebaseUtils.tweetLikesRef(t.id).child(myUid).get()
                .addOnSuccessListener(ds -> {
                    if (!isAdded()) return;
                    if (t.likes == null) t.likes = new HashMap<>();
                    t.likes.put(myUid, ds.getValue() != null);
                    int idx = adapter.getTweets().indexOf(t);
                    if (idx >= 0) adapter.notifyItemChanged(idx);
                });
            XFirebaseUtils.tweetRetweetsRef(t.id).child(myUid).get()
                .addOnSuccessListener(ds -> {
                    if (!isAdded()) return;
                    if (t.retweets == null) t.retweets = new HashMap<>();
                    t.retweets.put(myUid, ds.getValue() != null);
                    int idx = adapter.getTweets().indexOf(t);
                    if (idx >= 0) adapter.notifyItemChanged(idx);
                });
            XFirebaseUtils.userBookmarksRef(myUid).child(t.id).get()
                .addOnSuccessListener(ds -> {
                    if (!isAdded()) return;
                    if (t.bookmarks == null) t.bookmarks = new HashMap<>();
                    t.bookmarks.put(myUid, ds.getValue() != null);
                    int idx = adapter.getTweets().indexOf(t);
                    if (idx >= 0) adapter.notifyItemChanged(idx);
                });
        }
    }

    // ── Tweet Actions ────────────────────────────────────────────────────────

    @Override public void onLike(XTweet tweet, boolean liked) {
        XFirebaseUtils.tweetLikesRef(tweet.id).child(myUid).setValue(liked ? true : null);
        XFirebaseUtils.userLikedTweetsRef(myUid).child(tweet.id).setValue(liked ? true : null);
        XFirebaseUtils.tweetRef(tweet.id).child("likeCount")
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @NonNull @Override
                public com.google.firebase.database.Transaction.Result doTransaction(
                        @NonNull com.google.firebase.database.MutableData data) {
                    Long cur = data.getValue(Long.class);
                    if (cur == null) cur = 0L;
                    data.setValue(liked ? cur + 1 : Math.max(0, cur - 1));
                    return com.google.firebase.database.Transaction.success(data);
                }
                @Override public void onComplete(com.google.firebase.database.DatabaseError e,
                        boolean c, com.google.firebase.database.DataSnapshot s) {}
            });
        if (liked && !myUid.equals(tweet.authorUid)) pushNotif(tweet, "like");
    }

    @Override public void onRetweet(XTweet tweet, boolean retweeted) {
        XFirebaseUtils.tweetRetweetsRef(tweet.id).child(myUid).setValue(retweeted ? true : null);
        XFirebaseUtils.userRetweetsRef(myUid).child(tweet.id).setValue(retweeted ? true : null);
        XFirebaseUtils.tweetRef(tweet.id).child("retweetCount")
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @NonNull @Override
                public com.google.firebase.database.Transaction.Result doTransaction(
                        @NonNull com.google.firebase.database.MutableData data) {
                    Long cur = data.getValue(Long.class);
                    if (cur == null) cur = 0L;
                    data.setValue(retweeted ? cur + 1 : Math.max(0, cur - 1));
                    return com.google.firebase.database.Transaction.success(data);
                }
                @Override public void onComplete(com.google.firebase.database.DatabaseError e,
                        boolean c, com.google.firebase.database.DataSnapshot s) {}
            });
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
        XFirebaseUtils.userBookmarksRef(myUid).child(tweet.id).get()
            .addOnSuccessListener(snap -> {
                boolean was = snap.getValue() != null;
                boolean now = !was;
                XFirebaseUtils.userBookmarksRef(myUid).child(tweet.id).setValue(now ? true : null);
                XFirebaseUtils.tweetRef(tweet.id).child("bookmarks").child(myUid).setValue(now ? true : null);
                XFirebaseUtils.tweetRef(tweet.id).child("bookmarkCount")
                    .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                        @NonNull @Override
                        public com.google.firebase.database.Transaction.Result doTransaction(
                                @NonNull com.google.firebase.database.MutableData data) {
                            Long cur = data.getValue(Long.class);
                            if (cur == null) cur = 0L;
                            data.setValue(now ? cur + 1 : Math.max(0, cur - 1));
                            return com.google.firebase.database.Transaction.success(data);
                        }
                        @Override public void onComplete(com.google.firebase.database.DatabaseError e,
                                boolean c, com.google.firebase.database.DataSnapshot s) {}
                    });
                Toast.makeText(requireContext(),
                    now ? "Added to Bookmarks" : "Removed from Bookmarks", Toast.LENGTH_SHORT).show();
            });
    }

    @Override public void onShare(XTweet tweet) {
        String shareText = (tweet.authorName != null ? tweet.authorName : "")
            + ": " + (tweet.text != null ? tweet.text : "") + " — via CallX";
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, shareText);
        startActivity(Intent.createChooser(i, "Share post"));
    }

    @Override public void onMore(XTweet tweet, View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(requireContext(), anchor);
        boolean mine = myUid.equals(tweet.authorUid);
        if (mine) {
            menu.getMenu().add(0, 1, 0, "Delete post");
            menu.getMenu().add(0, 2, 0, tweet.isPinned ? "Unpin from profile" : "Pin to profile");
            if (tweet.editedAt == 0) menu.getMenu().add(0, 9, 0, "Edit post");
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
                case 9: editTweet(tweet); break;
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
        if (adapter.getItemCount() == 0) showEmpty();
        Toast.makeText(requireContext(), "Post deleted", Toast.LENGTH_SHORT).show();
    }

    private void togglePin(XTweet tweet) {
        boolean pin = !tweet.isPinned;
        XFirebaseUtils.tweetRef(tweet.id).child("isPinned").setValue(pin);
        XFirebaseUtils.xUserRef(myUid).child("pinnedTweetId").setValue(pin ? tweet.id : null);
        Toast.makeText(requireContext(), pin ? "Post pinned" : "Post unpinned", Toast.LENGTH_SHORT).show();
    }

    private void editTweet(XTweet tweet) {
        startActivity(new Intent(requireContext(), XComposeActivity.class)
            .putExtra("edit_tweet_id", tweet.id)
            .putExtra("edit_text", tweet.text));
    }

    private void followUser(XTweet tweet) {
        XFirebaseUtils.userFollowersRef(tweet.authorUid).child(myUid).setValue(true);
        XFirebaseUtils.userFollowingRef(myUid).child(tweet.authorUid).setValue(true);
        XFirebaseUtils.xUserRef(tweet.authorUid).child("followerCount")
            .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                @NonNull @Override
                public com.google.firebase.database.Transaction.Result doTransaction(
                        @NonNull com.google.firebase.database.MutableData data) {
                    Long cur = data.getValue(Long.class);
                    data.setValue(cur != null ? cur + 1 : 1);
                    return com.google.firebase.database.Transaction.success(data);
                }
                @Override public void onComplete(com.google.firebase.database.DatabaseError e,
                        boolean c, com.google.firebase.database.DataSnapshot s) {}
            });
        pushNotif(tweet, "follow");
        Toast.makeText(requireContext(), "Following @" + tweet.authorHandle, Toast.LENGTH_SHORT).show();
    }

    private void muteUser(XTweet tweet) {
        XFirebaseUtils.userMutedRef(myUid).child(tweet.authorUid).setValue(true);
        mutedUids.add(tweet.authorUid);
        List<XTweet> filtered = new ArrayList<>();
        for (XTweet t : adapter.getTweets())
            if (!t.authorUid.equals(tweet.authorUid)) filtered.add(t);
        adapter.setTweets(filtered);
        if (adapter.getItemCount() == 0) showEmpty();
        Toast.makeText(requireContext(), "Muted @" + tweet.authorHandle, Toast.LENGTH_SHORT).show();
    }

    private void blockUser(XTweet tweet) {
        XFirebaseUtils.userBlockedRef(myUid).child(tweet.authorUid).setValue(true);
        XFirebaseUtils.userBlockedRef(tweet.authorUid).child(myUid).setValue(true);
        blockedUids.add(tweet.authorUid);
        List<XTweet> filtered = new ArrayList<>();
        for (XTweet t : adapter.getTweets())
            if (!t.authorUid.equals(tweet.authorUid)) filtered.add(t);
        adapter.setTweets(filtered);
        if (adapter.getItemCount() == 0) showEmpty();
        Toast.makeText(requireContext(), "Blocked @" + tweet.authorHandle, Toast.LENGTH_SHORT).show();
    }

    private void reportTweet(XTweet tweet) {
        XFirebaseUtils.tweetReportsRef(tweet.id).child(myUid).setValue(
            new java.util.HashMap<String, Object>() {{
                put("reason", "spam"); put("ts", System.currentTimeMillis());
            }});
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

    /**
     * pushNotif — X notification dual-path sender (v24 fix)
     *
     * Path 1 (in-app bell):  Firebase Realtime DB  → XNotification node
     *                         Works only when app is FOREGROUND.
     *
     * Path 2 (FCM push):     PushNotify.notifyX()  → POST /notify/x  → FCM
     *                         Works in BACKGROUND and KILLED state.
     *
     * Root cause of original bug: Path 2 was missing — PushNotify.notifyX() was
     * never called, so background/killed notifications never arrived.
     */
    private void pushNotif(XTweet tweet, String type) {
        com.callx.app.utils.FirebaseUtils.getUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String fromName  = snap.child("name").getValue(String.class);
                    String fromPhoto = snap.child("photoUrl").getValue(String.class);
                    String fromThumb = snap.child("thumbUrl").getValue(String.class);
                    if (fromName  == null) fromName  = "Someone";
                    if (fromPhoto == null) fromPhoto = "";
                    if (fromThumb == null) fromThumb = "";

                    // ── Path 1: Firebase DB (in-app bell icon) ─────────────
                    XNotification n = new XNotification();
                    n.type         = type;
                    n.fromUid      = myUid;
                    n.fromName     = fromName;
                    n.fromPhotoUrl = fromPhoto;
                    n.fromThumbUrl = fromThumb;
                    n.tweetId      = tweet.id;
                    n.tweetSnippet = tweet.text != null
                        ? tweet.text.substring(0, Math.min(tweet.text.length(), 80)) : "";
                    n.timestamp    = System.currentTimeMillis();
                    n.read         = false;
                    n.notified     = false;
                    XFirebaseUtils.xNotificationsRef(tweet.authorUid).push().setValue(n);
                    XFirebaseUtils.xUnreadNotifCountRef(tweet.authorUid)
                        .runTransaction(new com.google.firebase.database.Transaction.Handler() {
                            @NonNull @Override
                            public com.google.firebase.database.Transaction.Result doTransaction(
                                    @NonNull com.google.firebase.database.MutableData data) {
                                Long c = data.getValue(Long.class);
                                data.setValue(c != null ? c + 1 : 1);
                                return com.google.firebase.database.Transaction.success(data);
                            }
                            @Override public void onComplete(com.google.firebase.database.DatabaseError e,
                                    boolean c, com.google.firebase.database.DataSnapshot s) {}
                        });

                    // ── Path 2: FCM push (background / killed state) ───────
                    // Uses thumbUrl (smaller) as avatar — server falls back to
                    // photoUrl automatically if thumbUrl is empty.
                    final String avatarUrl = !fromThumb.isEmpty() ? fromThumb : fromPhoto;
                    PushNotify.notifyX(
                        tweet.authorUid,   // toUid
                        myUid,             // fromUid
                        fromName,          // fromName
                        avatarUrl,         // fromPhoto
                        type,              // type: "like" | "retweet" | "follow" | …
                        tweet.id           // tweetId
                    );
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (feedListener != null && currentFeedRef != null)
            currentFeedRef.removeEventListener(feedListener);
        if (mediaPreloader != null) mediaPreloader.shutdown();
    }
}
