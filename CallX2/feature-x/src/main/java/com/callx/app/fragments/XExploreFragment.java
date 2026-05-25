package com.callx.app.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.*;
import com.bumptech.glide.Glide;
import com.callx.app.activities.*;
import com.callx.app.adapters.XTweetAdapter;
import com.callx.app.models.*;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.utils.XWhoToFollowManager;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

public class XExploreFragment extends Fragment implements XTweetAdapter.OnTweetActionListener {

    private NestedScrollView nsvExplore;
    private LinearLayout    llSearchState;
    private LinearLayout    llWtfSection;
    private LinearLayout    llWtfCards;
    private View            dividerWtf;
    private LinearLayout    llTrendingTopics;
    private LinearLayout    llUserResults;
    private ProgressBar     pbTrending;
    private RecyclerView    rvTrending, rvResults;
    private EditText        etSearch;
    private View            dividerSearch;
    private XTweetAdapter   trendingAdapter, resultsAdapter;
    private ValueEventListener trendingFeedListener;
    private String myUid;

    private static final long H24 = 24 * 3_600_000L;

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_x_explore, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

        etSearch         = view.findViewById(R.id.et_x_search);
        nsvExplore       = view.findViewById(R.id.nsv_x_explore);
        llSearchState    = view.findViewById(R.id.ll_search_state);
        llWtfSection     = view.findViewById(R.id.ll_wtf_section);
        llWtfCards       = view.findViewById(R.id.ll_wtf_cards);
        dividerWtf       = view.findViewById(R.id.divider_wtf);
        llTrendingTopics = view.findViewById(R.id.ll_trending_topics);
        llUserResults    = view.findViewById(R.id.ll_x_user_results);
        pbTrending       = view.findViewById(R.id.pb_trending);
        rvTrending       = view.findViewById(R.id.rv_x_trending);
        rvResults        = view.findViewById(R.id.rv_x_search_results);
        dividerSearch    = view.findViewById(R.id.divider_search);

        // Trending feed (non-scrollable inside NestedScrollView)
        trendingAdapter = new XTweetAdapter(requireContext(), this);
        rvTrending.setLayoutManager(new LinearLayoutManager(requireContext()) {
            @Override public boolean canScrollVertically() { return false; }
        });
        rvTrending.setAdapter(trendingAdapter);
        rvTrending.setNestedScrollingEnabled(false);

        // Search results adapter
        resultsAdapter = new XTweetAdapter(requireContext(), this);
        rvResults.setLayoutManager(new LinearLayoutManager(requireContext()));
        rvResults.setAdapter(resultsAdapter);

        // "See more" navigates to XSearchActivity
        View btnSeeMore = view.findViewById(R.id.tv_wtf_see_more);
        if (btnSeeMore != null)
            btnSeeMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), XSearchActivity.class)));

        // Refresh trending button
        View btnRefresh = view.findViewById(R.id.tv_trending_refresh);
        if (btnRefresh != null) btnRefresh.setOnClickListener(v -> loadTrendingTopics());

        // Search watcher
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                String q = s.toString().trim();
                if (q.length() > 0) { showSearchState(true); searchAll(q); }
                else                { showSearchState(false); clearSearchResults(); }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Load all sections in parallel
        loadWhoToFollow();
        loadTrendingTopics();
        loadTrendingFeed();
    }

    private void showSearchState(boolean search) {
        nsvExplore.setVisibility(search ? View.GONE : View.VISIBLE);
        llSearchState.setVisibility(search ? View.VISIBLE : View.GONE);
    }

    private void clearSearchResults() {
        if (llUserResults != null) llUserResults.removeAllViews();
        resultsAdapter.setTweets(new ArrayList<>());
        if (dividerSearch != null) dividerSearch.setVisibility(View.GONE);
    }

    // ── Who to Follow ─────────────────────────────────────────────────────────

    private void loadWhoToFollow() {
        XWhoToFollowManager.getSuggestions(myUid, suggestions -> {
            if (!isAdded() || llWtfCards == null || suggestions.isEmpty()) return;
            requireActivity().runOnUiThread(() -> renderSuggestionCards(suggestions));
        });
    }

    private void renderSuggestionCards(List<XWhoToFollowManager.SuggestedUser> suggestions) {
        if (!isAdded() || llWtfCards == null) return;
        llWtfCards.removeAllViews();

        for (XWhoToFollowManager.SuggestedUser su : suggestions) {
            View card = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_x_who_to_follow, llWtfCards, false);

            de.hdodenhof.circleimageview.CircleImageView ivAvatar =
                card.findViewById(R.id.iv_suggestion_avatar);
            TextView tvName   = card.findViewById(R.id.tv_suggestion_name);
            TextView tvHandle = card.findViewById(R.id.tv_suggestion_handle);
            TextView tvMutual = card.findViewById(R.id.tv_suggestion_mutual);
            Button   btnFollow= card.findViewById(R.id.btn_suggestion_follow);

            String wtfAvatarUrl = (su.user.thumbUrl != null && !su.user.thumbUrl.isEmpty())
                ? su.user.thumbUrl : su.user.photoUrl;
            Glide.with(requireContext())
                .load(wtfAvatarUrl)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .into(ivAvatar);

            tvName.setText(su.user.name != null ? su.user.name : "User");
            tvHandle.setText("@" + (su.user.handle != null ? su.user.handle : ""));

            if (su.mutualCount > 0) {
                tvMutual.setVisibility(View.VISIBLE);
                tvMutual.setText(su.mutualCount + " mutual"
                    + (su.mutualCount == 1 ? "" : "s"));
            } else if (su.user.followerCount > 0) {
                tvMutual.setVisibility(View.VISIBLE);
                String fc = su.user.followerCount >= 1_000_000
                    ? String.format("%.1fM", su.user.followerCount / 1_000_000.0)
                    : su.user.followerCount >= 1_000
                        ? String.format("%.1fK", su.user.followerCount / 1_000.0)
                        : String.valueOf(su.user.followerCount);
                tvMutual.setText(fc + " followers");
            } else {
                tvMutual.setVisibility(View.GONE);
            }

            // Track follow state per card
            final boolean[] following = { su.isFollowing };
            updateFollowButton(btnFollow, following[0]);

            btnFollow.setOnClickListener(v -> {
                following[0] = !following[0];
                updateFollowButton(btnFollow, following[0]);
                String targetUid = su.user.uid;
                if (following[0]) {
                    XFirebaseUtils.userFollowersRef(targetUid).child(myUid).setValue(true);
                    XFirebaseUtils.userFollowingRef(myUid).child(targetUid).setValue(true);
                    XFirebaseUtils.xUserRef(targetUid).child("followerCount").get()
                        .addOnSuccessListener(ds -> {
                            Long c = ds.getValue(Long.class);
                            XFirebaseUtils.xUserRef(targetUid).child("followerCount")
                                .setValue(c != null ? c + 1 : 1);
                        });
                    XFirebaseUtils.xUserRef(myUid).child("followingCount").get()
                        .addOnSuccessListener(ds -> {
                            Long c = ds.getValue(Long.class);
                            XFirebaseUtils.xUserRef(myUid).child("followingCount")
                                .setValue(c != null ? c + 1 : 1);
                        });
                } else {
                    XFirebaseUtils.userFollowersRef(targetUid).child(myUid).removeValue();
                    XFirebaseUtils.userFollowingRef(myUid).child(targetUid).removeValue();
                    XFirebaseUtils.xUserRef(targetUid).child("followerCount").get()
                        .addOnSuccessListener(ds -> {
                            Long c = ds.getValue(Long.class);
                            XFirebaseUtils.xUserRef(targetUid).child("followerCount")
                                .setValue(Math.max(0, c != null ? c - 1 : 0));
                        });
                }
            });

            // Tap card body → open profile sheet
            card.setOnClickListener(v ->
                XProfileSheet.show(getParentFragmentManager(), su.user.uid));

            llWtfCards.addView(card);
        }

        // Show section
        llWtfSection.setVisibility(View.VISIBLE);
        if (dividerWtf != null) dividerWtf.setVisibility(View.VISIBLE);
    }

    private void updateFollowButton(Button btn, boolean isFollowing) {
        if (isFollowing) {
            btn.setText("Following");
            btn.setTextColor(requireContext().getColor(R.color.x_text_primary));
            btn.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(R.color.x_bg_secondary)));
        } else {
            btn.setText("Follow");
            btn.setTextColor(requireContext().getColor(R.color.x_bg_primary));
            btn.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    requireContext().getColor(R.color.x_text_primary)));
        }
    }

    // ── Trending Topics ────────────────────────────────────────────────────────

    private void loadTrendingTopics() {
        if (!isAdded() || llTrendingTopics == null) return;
        llTrendingTopics.removeAllViews();
        if (pbTrending != null) pbTrending.setVisibility(View.VISIBLE);

        long cutoff = System.currentTimeMillis() - H24;
        XFirebaseUtils.trendingRef()
            .orderByChild("lastPostAt").startAt(cutoff).limitToLast(50)
            .get()
            .addOnSuccessListener(snap -> {
                if (!isAdded()) return;
                List<XTrendingTopic> topics = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XTrendingTopic t = parseTrendingTopic(ds);
                    if (t != null) topics.add(t);
                }
                topics.sort((a, b) -> Long.compare(b.count24h, a.count24h));
                if (topics.size() > 10) topics = topics.subList(0, 10);
                if (pbTrending != null) pbTrending.setVisibility(View.GONE);
                if (topics.isEmpty()) loadTopicsFallback();
                else renderTopicRows(topics);
            })
            .addOnFailureListener(e -> { if (!isAdded()) return; loadTopicsFallback(); });
    }

    private void loadTopicsFallback() {
        XFirebaseUtils.trendingRef().orderByChild("countAll").limitToLast(10)
            .get().addOnSuccessListener(snap -> {
                if (!isAdded()) return;
                List<XTrendingTopic> topics = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XTrendingTopic t = parseTrendingTopic(ds);
                    if (t != null) topics.add(t);
                }
                topics.sort((a, b) -> Long.compare(b.countAll, a.countAll));
                if (pbTrending != null) pbTrending.setVisibility(View.GONE);
                renderTopicRows(topics);
            });
    }

    private XTrendingTopic parseTrendingTopic(DataSnapshot ds) {
        String clean = ds.child("cleanTag").getValue(String.class);
        if (clean == null || clean.isEmpty()) return null;
        XTrendingTopic t = new XTrendingTopic();
        t.cleanTag   = clean;
        t.displayTag = ds.child("displayTag").getValue(String.class);
        if (t.displayTag == null) t.displayTag = "#" + clean;
        Long c24 = ds.child("count24h").getValue(Long.class);
        Long cAll= ds.child("countAll").getValue(Long.class);
        Long last= ds.child("lastPostAt").getValue(Long.class);
        t.count24h   = c24  != null ? c24  : 0;
        t.countAll   = cAll != null ? cAll : 0;
        t.lastPostAt = last != null ? last : 0;
        return t;
    }

    private void renderTopicRows(List<XTrendingTopic> topics) {
        if (!isAdded() || llTrendingTopics == null) return;
        llTrendingTopics.removeAllViews();
        for (int i = 0; i < topics.size(); i++) {
            XTrendingTopic topic = topics.get(i);
            View row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_x_trending_topic, llTrendingTopics, false);
            ((TextView) row.findViewById(R.id.tv_trending_rank)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.tv_trending_tag)).setText(topic.displayTag);
            long c = topic.count24h > 0 ? topic.count24h : topic.countAll;
            String countStr = c >= 1_000_000
                ? String.format(java.util.Locale.US, "%.1fM posts", c / 1_000_000.0)
                : c >= 1_000
                    ? String.format(java.util.Locale.US, "%.1fK posts", c / 1_000.0)
                    : c + (c == 1 ? " post" : " posts");
            ((TextView) row.findViewById(R.id.tv_trending_count))
                .setText(countStr + (topic.count24h > 0 ? " · Trending" : " · All time"));
            row.setOnClickListener(v -> startActivity(
                new Intent(requireContext(), XHashtagActivity.class)
                    .putExtra("hashtag", topic.displayTag)));
            llTrendingTopics.addView(row);
            View div = new View(requireContext());
            div.setBackgroundColor(requireContext().getColor(R.color.x_divider));
            llTrendingTopics.addView(div,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
    }

    // ── Trending Feed ──────────────────────────────────────────────────────────

    private void loadTrendingFeed() {
        trendingFeedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!isAdded()) return;
                List<XTweet> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XTweet t = ds.getValue(XTweet.class);
                    if (t != null && !t.isDeleted) { t.id = ds.getKey(); list.add(t); }
                }
                list.sort((a, b) -> Long.compare(
                    b.likeCount * 2 + b.retweetCount * 3 + b.replyCount + b.viewCount / 10,
                    a.likeCount * 2 + a.retweetCount * 3 + a.replyCount + a.viewCount / 10));
                if (list.size() > 20) list = list.subList(0, 20);
                trendingAdapter.setTweets(list);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        XFirebaseUtils.globalFeedRef().limitToLast(100).addValueEventListener(trendingFeedListener);
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    private void searchAll(String q) {
        searchUsers(q);
        searchTweets(q);
    }

    private void searchUsers(String q) {
        if (llUserResults == null) return;
        String qLow = q.toLowerCase(java.util.Locale.US);
        XFirebaseUtils.root_x_users()
            .orderByChild("handle").startAt(qLow).endAt(qLow + "\uF8FF").limitToFirst(5)
            .get().addOnSuccessListener(snap -> {
                if (!isAdded()) return;
                llUserResults.removeAllViews();
                boolean has = snap.getChildrenCount() > 0;
                if (dividerSearch != null) dividerSearch.setVisibility(has ? View.VISIBLE : View.GONE);
                for (DataSnapshot ds : snap.getChildren()) {
                    XUser u = ds.getValue(XUser.class);
                    if (u == null) continue;
                    u.uid = ds.getKey();
                    View row = LayoutInflater.from(requireContext())
                        .inflate(R.layout.item_x_user_row, llUserResults, false);
                    String srchAvatarUrl = (u.thumbUrl != null && !u.thumbUrl.isEmpty()) ? u.thumbUrl : u.photoUrl;
                    Glide.with(requireContext()).load(srchAvatarUrl).circleCrop()
                        .placeholder(R.drawable.ic_person)
                        .into((android.widget.ImageView) row.findViewById(R.id.iv_x_user_avatar));
                    ((TextView) row.findViewById(R.id.tv_x_user_name)).setText(u.name);
                    ((TextView) row.findViewById(R.id.tv_x_user_handle)).setText("@" + u.handle);
                    String uid = u.uid;
                    row.setOnClickListener(v ->
                        XProfileSheet.show(getParentFragmentManager(), uid));
                    llUserResults.addView(row);
                }
            });
    }

    private void searchTweets(String q) {
        XFirebaseUtils.tweetsRef()
            .orderByChild("text").startAt(q).endAt(q + "\uF8FF").limitToFirst(20)
            .get().addOnSuccessListener(snap -> {
                if (!isAdded()) return;
                List<XTweet> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XTweet t = ds.getValue(XTweet.class);
                    if (t != null && !t.isDeleted) { t.id = ds.getKey(); list.add(t); }
                }
                resultsAdapter.setTweets(list);
            });
    }

    // ── Tweet Actions ──────────────────────────────────────────────────────────

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
        i.putExtra(Intent.EXTRA_TEXT, tweet.text + " — via CallX");
        startActivity(Intent.createChooser(i, "Share"));
    }
    @Override public void onMore(XTweet tweet, View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, "Copy link");
        menu.getMenu().add(0, 2, 0, "Quote post");
        menu.getMenu().add(0, 3, 0, "Report post");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    ClipboardManager cm = (ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link",
                        "https://callx.app/x/tweet/" + tweet.id));
                    Toast.makeText(requireContext(), "Link copied", Toast.LENGTH_SHORT).show();
                    break;
                case 2: onQuote(tweet); break;
                case 3:
                    XFirebaseUtils.tweetReportsRef(tweet.id).child(myUid).setValue(true);
                    Toast.makeText(requireContext(), "Reported. Thank you.", Toast.LENGTH_SHORT).show();
                    break;
            }
            return true;
        });
        menu.show();
    }

    @Override public void onDestroyView() {
        super.onDestroyView();
        if (trendingFeedListener != null)
            XFirebaseUtils.globalFeedRef().removeEventListener(trendingFeedListener);
    }
}
