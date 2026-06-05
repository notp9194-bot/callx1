package com.callx.app.feed;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import androidx.fragment.app.FragmentActivity;
import com.callx.app.search.XHashtagActivity;
import com.callx.app.player.XImageViewerActivity;
import com.callx.app.profile.XProfileSheet;
import com.callx.app.tweet.XTweetDetailActivity;
import com.callx.app.player.XVideoPlayerActivity;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.callx.app.models.XPoll;
import com.callx.app.models.XTweet;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XTweetAdapter extends RecyclerView.Adapter<XTweetAdapter.TweetVH> {

    private final Context ctx;
    private final List<XTweet> tweets = new ArrayList<>();
    // Quote tweet cache to avoid repeated fetches per bind
    private final Map<String, XTweet> quoteTweetCache = new HashMap<>();
    // Poll cache
    private final Map<String, XPoll> pollCache = new HashMap<>();
    private final String myUid;
    private OnTweetActionListener listener;

    public interface OnTweetActionListener {
        void onLike(XTweet tweet, boolean liked);
        void onRetweet(XTweet tweet, boolean retweeted);
        void onReply(XTweet tweet);
        void onQuote(XTweet tweet);
        void onBookmark(XTweet tweet);
        void onShare(XTweet tweet);
        void onMore(XTweet tweet, View anchor);
    }

    public XTweetAdapter(Context ctx, OnTweetActionListener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        setHasStableIds(true);
    }

    @Override public long getItemId(int pos) {
        String id = tweets.get(pos).id;
        return id != null ? id.hashCode() : pos;
    }

    // ── DiffUtil for smooth updates ─────────────────────────────────────────

    public void setTweets(List<XTweet> newList) {
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return tweets.size(); }
            @Override public int getNewListSize() { return newList.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                XTweet ot = tweets.get(o), nt = newList.get(n);
                return ot.id != null && ot.id.equals(nt.id);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                XTweet ot = tweets.get(o), nt = newList.get(n);
                return ot.likeCount == nt.likeCount
                    && ot.retweetCount == nt.retweetCount
                    && ot.replyCount  == nt.replyCount
                    && ot.viewCount   == nt.viewCount
                    && ot.isDeleted   == nt.isDeleted
                    && ot.isPinned    == nt.isPinned
                    && java.util.Objects.equals(ot.text, nt.text);
            }
        });
        tweets.clear();
        tweets.addAll(newList);
        diff.dispatchUpdatesTo(this);
    }

    public void addTweet(XTweet tweet) {
        tweets.add(0, tweet); notifyItemInserted(0);
    }

    public List<XTweet> getTweets() { return tweets; }

    public void removeTweet(String id) {
        for (int i = 0; i < tweets.size(); i++) {
            if (id.equals(tweets.get(i).id)) { tweets.remove(i); notifyItemRemoved(i); return; }
        }
    }

    @NonNull @Override
    public TweetVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TweetVH(LayoutInflater.from(ctx).inflate(R.layout.item_x_tweet, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull TweetVH h, int pos) { h.bind(tweets.get(pos)); }
    @Override public int getItemCount() { return tweets.size(); }

    // ── ViewHolder ──────────────────────────────────────────────────────────

    class TweetVH extends RecyclerView.ViewHolder {
        ImageView ivAvatar, ivMedia, ivVerified;
        TextView tvName, tvHandle, tvTime, tvText, tvLikes, tvRetweets, tvReplies, tvViews;
        TextView tvAudience, tvEdited, tvPinned, tvBookmarks;
        View btnLike, btnRetweet, btnReply, btnBookmark, btnShare, btnMore;
        ImageView icLike, icRetweet, icBookmark;
        // Quote tweet
        View cardQuote;
        TextView tvQuoteName, tvQuoteText;
        // Poll
        LinearLayout llPoll;
        // Multi-image grid
        GridLayout glMedia;
        // Link preview
        View cardLinkPreview;
        TextView tvLinkTitle, tvLinkDesc, tvLinkDomain;
        ImageView ivLinkThumb;
        // Thread line
        View vThreadLine;
        // Sensitive content
        View vSensitiveOverlay;

        TweetVH(View v) {
            super(v);
            ivAvatar       = v.findViewById(R.id.iv_x_avatar);
            ivMedia        = v.findViewById(R.id.iv_x_media);
            ivVerified     = v.findViewById(R.id.iv_x_verified);
            tvName         = v.findViewById(R.id.tv_x_name);
            tvHandle       = v.findViewById(R.id.tv_x_handle);
            tvTime         = v.findViewById(R.id.tv_x_time);
            tvText         = v.findViewById(R.id.tv_x_text);
            tvLikes        = v.findViewById(R.id.tv_x_likes);
            tvRetweets     = v.findViewById(R.id.tv_x_retweets);
            tvReplies      = v.findViewById(R.id.tv_x_replies);
            tvViews        = v.findViewById(R.id.tv_x_views);
            tvBookmarks    = v.findViewById(R.id.tv_x_bookmarks);
            tvAudience     = v.findViewById(R.id.tv_x_audience);
            tvEdited       = v.findViewById(R.id.tv_x_edited);
            tvPinned       = v.findViewById(R.id.tv_x_pinned);
            btnLike        = v.findViewById(R.id.btn_x_like);
            btnRetweet     = v.findViewById(R.id.btn_x_retweet);
            btnReply       = v.findViewById(R.id.btn_x_reply);
            btnBookmark    = v.findViewById(R.id.btn_x_bookmark);
            btnShare       = v.findViewById(R.id.btn_x_share);
            btnMore        = v.findViewById(R.id.btn_x_more);
            icLike         = v.findViewById(R.id.ic_x_like);
            icRetweet      = v.findViewById(R.id.ic_x_retweet);
            icBookmark     = v.findViewById(R.id.ic_x_bookmark);
            cardQuote      = v.findViewById(R.id.card_x_quote);
            tvQuoteName    = v.findViewById(R.id.tv_x_quote_name);
            tvQuoteText    = v.findViewById(R.id.tv_x_quote_text);
            llPoll         = v.findViewById(R.id.ll_x_poll);
            glMedia        = v.findViewById(R.id.gl_x_multi_media);
            cardLinkPreview= v.findViewById(R.id.card_x_link_preview);
            tvLinkTitle    = v.findViewById(R.id.tv_x_link_title);
            tvLinkDesc     = v.findViewById(R.id.tv_x_link_desc);
            tvLinkDomain   = v.findViewById(R.id.tv_x_link_domain);
            ivLinkThumb    = v.findViewById(R.id.iv_x_link_thumb);
            vThreadLine    = v.findViewById(R.id.v_x_thread_line);
            vSensitiveOverlay = v.findViewById(R.id.v_x_sensitive_overlay);
        }

        void bind(XTweet tweet) {
            // Avatar
            String avatarUrl = pick(tweet.authorThumbUrl, tweet.authorPhotoUrl);
            Glide.with(ctx).load(avatarUrl)
                .apply(new RequestOptions().circleCrop().diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_person))
                .into(ivAvatar);
            ivAvatar.setOnClickListener(v -> {
                if (ctx instanceof FragmentActivity)
                    XProfileSheet.showProfile(
                        ((FragmentActivity) ctx).getSupportFragmentManager(), tweet.authorUid);
            });

            // Meta
            tvName.setText(tweet.authorName);
            tvHandle.setText("@" + tweet.authorHandle);
            tvTime.setText(formatTime(tweet.timestamp));
            if (ivVerified != null)
                ivVerified.setVisibility(tweet.authorVerified ? View.VISIBLE : View.GONE);

            // Pinned label
            if (tvPinned != null)
                tvPinned.setVisibility(tweet.isPinned ? View.VISIBLE : View.GONE);

            // Edited label
            if (tvEdited != null)
                tvEdited.setVisibility(tweet.editedAt > 0 ? View.VISIBLE : View.GONE);

            // Audience label (followers/circle only)
            if (tvAudience != null) {
                if ("followers".equals(tweet.audience)) {
                    tvAudience.setVisibility(View.VISIBLE);
                    tvAudience.setText("Followers only");
                } else if ("circle".equals(tweet.audience)) {
                    tvAudience.setVisibility(View.VISIBLE);
                    tvAudience.setText("Circle");
                } else {
                    tvAudience.setVisibility(View.GONE);
                }
            }

            // Thread line
            if (vThreadLine != null)
                vThreadLine.setVisibility((tweet.isThread && !tweet.isThreadEnd) ? View.VISIBLE : View.GONE);

            // Text
            tvText.setText(buildSpannable(tweet.text));
            tvText.setMovementMethod(LinkMovementMethod.getInstance());

            // Sensitive overlay
            bindSensitive(tweet);

            // Media (single or multi)
            bindMedia(tweet);

            // Quote tweet (from cache)
            bindQuote(tweet);

            // Poll (from cache)
            bindPoll(tweet);

            // Link preview
            bindLinkPreview(tweet);

            // Counts
            tvLikes.setText(formatCount(tweet.likeCount));
            tvRetweets.setText(formatCount(tweet.retweetCount));
            tvReplies.setText(formatCount(tweet.replyCount));
            tvViews.setText(formatCount(tweet.viewCount));
            if (tvBookmarks != null) tvBookmarks.setText(formatCount(tweet.bookmarkCount));

            // Like state + color
            boolean liked = tweet.isLikedBy(myUid);
            if (icLike != null) icLike.setColorFilter(liked
                ? ContextCompat.getColor(ctx, R.color.x_like_active)
                : ContextCompat.getColor(ctx, R.color.x_icon_default));

            boolean rted = tweet.isRetweetedBy(myUid);
            if (icRetweet != null) icRetweet.setColorFilter(rted
                ? ContextCompat.getColor(ctx, R.color.x_retweet_active)
                : ContextCompat.getColor(ctx, R.color.x_icon_default));

            boolean bkd = tweet.isBookmarkedBy(myUid);
            if (icBookmark != null) icBookmark.setColorFilter(bkd
                ? ContextCompat.getColor(ctx, R.color.x_bookmark_active)
                : ContextCompat.getColor(ctx, R.color.x_icon_default));

            // Buttons — optimistic UI
            if (btnLike != null) btnLike.setOnClickListener(v -> {
                if (listener == null) return;
                boolean nowLiked = !tweet.isLikedBy(myUid);
                if (tweet.likes == null) tweet.likes = new java.util.HashMap<>();
                tweet.likes.put(myUid, nowLiked);
                tweet.likeCount = Math.max(0, tweet.likeCount + (nowLiked ? 1 : -1));
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_ID) notifyItemChanged(pos);
                if (nowLiked && icLike != null) animateLike(icLike);
                listener.onLike(tweet, nowLiked);
            });

            if (btnRetweet != null) btnRetweet.setOnClickListener(v -> {
                if (listener == null) return;
                boolean nowRted = !tweet.isRetweetedBy(myUid);
                if (tweet.retweets == null) tweet.retweets = new java.util.HashMap<>();
                tweet.retweets.put(myUid, nowRted);
                tweet.retweetCount = Math.max(0, tweet.retweetCount + (nowRted ? 1 : -1));
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_ID) notifyItemChanged(pos);
                listener.onRetweet(tweet, nowRted);
            });

            if (btnReply != null)
                btnReply.setOnClickListener(v -> { if (listener != null) listener.onReply(tweet); });
            if (btnBookmark != null) btnBookmark.setOnClickListener(v -> {
                if (listener == null) return;
                boolean nowBkd = !tweet.isBookmarkedBy(myUid);
                if (tweet.bookmarks == null) tweet.bookmarks = new java.util.HashMap<>();
                tweet.bookmarks.put(myUid, nowBkd);
                tweet.bookmarkCount = Math.max(0, tweet.bookmarkCount + (nowBkd ? 1 : -1));
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_ID) notifyItemChanged(pos);
                listener.onBookmark(tweet);
            });
            if (btnShare != null)
                btnShare.setOnClickListener(v -> { if (listener != null) listener.onShare(tweet); });
            if (btnMore != null) btnMore.setOnClickListener(v -> showMoreMenu(tweet, v));

            itemView.setOnClickListener(v -> ctx.startActivity(
                new Intent(ctx, XTweetDetailActivity.class).putExtra("tweet_id", tweet.id)));
        }

        // ── Like animation (scale burst) ──────────────────────────────────

        private void animateLike(ImageView icon) {
            icon.setColorFilter(ContextCompat.getColor(ctx, R.color.x_like_active));
            AnimatorSet set = new AnimatorSet();
            ObjectAnimator scaleUp1 = ObjectAnimator.ofFloat(icon, "scaleX", 1f, 1.5f);
            ObjectAnimator scaleUp2 = ObjectAnimator.ofFloat(icon, "scaleY", 1f, 1.5f);
            ObjectAnimator scaleDown1 = ObjectAnimator.ofFloat(icon, "scaleX", 1.5f, 1f);
            ObjectAnimator scaleDown2 = ObjectAnimator.ofFloat(icon, "scaleY", 1.5f, 1f);
            scaleUp1.setDuration(150); scaleUp2.setDuration(150);
            scaleDown1.setDuration(150); scaleDown2.setDuration(150);
            scaleDown1.setInterpolator(new OvershootInterpolator(3f));
            scaleDown2.setInterpolator(new OvershootInterpolator(3f));
            AnimatorSet upSet = new AnimatorSet(); upSet.playTogether(scaleUp1, scaleUp2);
            AnimatorSet downSet = new AnimatorSet(); downSet.playTogether(scaleDown1, scaleDown2);
            set.playSequentially(upSet, downSet);
            set.start();
        }

        // ── 3-dot (More) popup ────────────────────────────────────────────
        // Handles "View profile" + tweet-level actions entirely inside the adapter.
        // OnTweetActionListener.onMore() is intentionally no longer called here.

        private void showMoreMenu(XTweet tweet, View anchor) {
            PopupMenu menu = new PopupMenu(ctx, anchor);
            boolean mine = myUid.equals(tweet.authorUid);
            String handle = tweet.authorHandle != null ? tweet.authorHandle : "user";

            // View profile — always first
            menu.getMenu().add(0, 1, 0, "View @" + handle + "'s profile");

            if (mine) {
                menu.getMenu().add(0, 2, 1, "Delete post");
                menu.getMenu().add(0, 3, 2, tweet.isPinned ? "Unpin from profile" : "Pin to profile");
            } else {
                menu.getMenu().add(0, 4, 1, "Block @" + handle);
                menu.getMenu().add(0, 5, 2, "Mute @"  + handle);
                menu.getMenu().add(0, 6, 3, "Report post");
            }
            menu.getMenu().add(0, 7, 4, "Copy link");

            menu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {

                    case 1: // View Profile
                        if (ctx instanceof FragmentActivity)
                            XProfileSheet.showProfile(
                                ((FragmentActivity) ctx).getSupportFragmentManager(),
                                tweet.authorUid);
                        return true;

                    case 2: // Delete
                        if (tweet.id == null || myUid.isEmpty()) return false;
                        XFirebaseUtils.tweetRef(tweet.id).child("isDeleted").setValue(true);
                        XFirebaseUtils.globalFeedRef().child(tweet.id).removeValue();
                        XFirebaseUtils.userTweetsRef(myUid).child(tweet.id).removeValue();
                        return true;

                    case 3: // Pin / Unpin
                        if (tweet.id == null || myUid.isEmpty()) return false;
                        boolean nowPinned = !tweet.isPinned;
                        XFirebaseUtils.tweetRef(tweet.id).child("isPinned").setValue(nowPinned);
                        if (nowPinned)
                            XFirebaseUtils.xUserRef(myUid).child("pinnedTweetId").setValue(tweet.id);
                        else
                            XFirebaseUtils.xUserRef(myUid).child("pinnedTweetId").removeValue();
                        tweet.isPinned = nowPinned;
                        int p = getAdapterPosition();
                        if (p != RecyclerView.NO_ID) notifyItemChanged(p);
                        return true;

                    case 4: // Block
                        if (myUid.isEmpty() || tweet.authorUid == null) return false;
                        XFirebaseUtils.userBlockedRef(myUid).child(tweet.authorUid).setValue(true);
                        Toast.makeText(ctx, "@" + handle + " blocked", Toast.LENGTH_SHORT).show();
                        return true;

                    case 5: // Mute
                        if (myUid.isEmpty() || tweet.authorUid == null) return false;
                        XFirebaseUtils.userMutedRef(myUid).child(tweet.authorUid).setValue(true);
                        Toast.makeText(ctx, "@" + handle + " muted", Toast.LENGTH_SHORT).show();
                        return true;

                    case 6: // Report
                        Toast.makeText(ctx, "Post reported. Thank you.", Toast.LENGTH_SHORT).show();
                        return true;

                    case 7: // Copy link
                        ClipboardManager cm = (ClipboardManager)
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null && tweet.id != null)
                            cm.setPrimaryClip(ClipData.newPlainText("Post link",
                                "https://callx.app/x/post/" + tweet.id));
                        Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show();
                        return true;
                }
                return false;
            });
            menu.show();
        }

        // ── Sensitive content ─────────────────────────────────────────────

        private void bindSensitive(XTweet tweet) {
            if (vSensitiveOverlay == null) return;
            if (tweet.isSensitive) {
                vSensitiveOverlay.setVisibility(View.VISIBLE);
                TextView tvReveal = vSensitiveOverlay.findViewById(R.id.tv_sensitive_reveal);
                if (tvReveal != null) tvReveal.setOnClickListener(v ->
                    vSensitiveOverlay.setVisibility(View.GONE));
            } else {
                vSensitiveOverlay.setVisibility(View.GONE);
            }
        }

        // ── Single/Multi media ────────────────────────────────────────────

        private void bindMedia(XTweet tweet) {
            // Multi-image grid (up to 4)
            if (glMedia != null) {
                if (tweet.isMultiImage() && tweet.mediaUrls != null) {
                    glMedia.setVisibility(View.VISIBLE);
                    if (ivMedia != null) ivMedia.setVisibility(View.GONE);
                    glMedia.removeAllViews();
                    int count = Math.min(tweet.mediaUrls.size(), 4);
                    glMedia.setColumnCount(count <= 2 ? 1 : 2);
                    glMedia.setRowCount(count <= 2 ? count : 2);
                    for (int i = 0; i < count; i++) {
                        final String url = tweet.mediaUrls.get(i);
                        final String type = (tweet.mediaTypes != null && i < tweet.mediaTypes.size())
                            ? tweet.mediaTypes.get(i) : "image";
                        final String alt = (tweet.mediaAltTexts != null && i < tweet.mediaAltTexts.size())
                            ? tweet.mediaAltTexts.get(i) : "";
                        ImageView img = new ImageView(ctx);
                        img.setContentDescription(alt.isEmpty() ? "Image " + (i+1) : alt);
                        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
                        lp.width = 0; lp.height = (int)(160 * ctx.getResources().getDisplayMetrics().density);
                        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
                        lp.setMargins(2, 2, 2, 2);
                        img.setLayoutParams(lp);
                        Glide.with(ctx).load(url).centerCrop().diskCacheStrategy(DiskCacheStrategy.ALL).into(img);
                        final String finalType = type;
                        img.setOnClickListener(v -> {
                            if ("video".equals(finalType)) {
                                ctx.startActivity(new Intent(ctx, XVideoPlayerActivity.class).putExtra("video_url", url));
                            } else {
                                ctx.startActivity(new Intent(ctx, XImageViewerActivity.class).putExtra("image_url", url));
                            }
                        });
                        glMedia.addView(img);
                    }
                    return;
                } else {
                    glMedia.setVisibility(View.GONE);
                }
            }

            // Single media
            if (ivMedia == null) return;
            if (tweet.mediaUrl != null && !tweet.mediaUrl.isEmpty()) {
                ivMedia.setVisibility(View.VISIBLE);
                boolean isVideo = "video".equals(tweet.mediaType);
                String thumb = isVideo && tweet.thumbnailUrl != null ? tweet.thumbnailUrl : tweet.mediaUrl;
                Glide.with(ctx).load(thumb)
                    .apply(new RequestOptions().centerCrop().diskCacheStrategy(DiskCacheStrategy.ALL))
                    .into(ivMedia);
                ivMedia.setOnClickListener(v -> {
                    if (isVideo) {
                        ctx.startActivity(new Intent(ctx, XVideoPlayerActivity.class).putExtra("video_url", tweet.mediaUrl));
                    } else {
                        ctx.startActivity(new Intent(ctx, XImageViewerActivity.class).putExtra("image_url", tweet.mediaUrl));
                    }
                });
            } else {
                ivMedia.setVisibility(View.GONE);
            }
        }

        // ── Quote tweet (cache-first) ─────────────────────────────────────

        private void bindQuote(XTweet tweet) {
            if (cardQuote == null) return;
            if (tweet.quotedTweetId == null || tweet.quotedTweetId.isEmpty()) {
                cardQuote.setVisibility(View.GONE); return;
            }
            cardQuote.setVisibility(View.VISIBLE);
            // Check cache first
            if (quoteTweetCache.containsKey(tweet.quotedTweetId)) {
                XTweet q = quoteTweetCache.get(tweet.quotedTweetId);
                if (q == null || q.isDeleted) { cardQuote.setVisibility(View.GONE); return; }
                if (tvQuoteName != null) tvQuoteName.setText("@" + q.authorHandle);
                if (tvQuoteText != null) tvQuoteText.setText(q.text);
                cardQuote.setOnClickListener(v -> ctx.startActivity(
                    new Intent(ctx, XTweetDetailActivity.class).putExtra("tweet_id", tweet.quotedTweetId)));
                return;
            }
            // Fetch and cache
            XFirebaseUtils.tweetRef(tweet.quotedTweetId).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        XTweet q = snap.getValue(XTweet.class);
                        quoteTweetCache.put(tweet.quotedTweetId, q);
                        if (q == null || q.isDeleted) { cardQuote.setVisibility(View.GONE); return; }
                        if (tvQuoteName != null) tvQuoteName.setText("@" + q.authorHandle);
                        if (tvQuoteText != null) tvQuoteText.setText(q.text);
                        cardQuote.setOnClickListener(v -> ctx.startActivity(
                            new Intent(ctx, XTweetDetailActivity.class)
                                .putExtra("tweet_id", tweet.quotedTweetId)));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        cardQuote.setVisibility(View.GONE);
                    }
                });
        }

        // ── Poll (cache-first, Transaction for votes) ─────────────────────

        private void bindPoll(XTweet tweet) {
            if (llPoll == null) return;
            if (tweet.pollId == null || tweet.pollId.isEmpty()) { llPoll.setVisibility(View.GONE); return; }
            llPoll.setVisibility(View.VISIBLE);

            if (pollCache.containsKey(tweet.pollId)) {
                renderPoll(llPoll, pollCache.get(tweet.pollId), tweet.pollId);
                return;
            }
            XFirebaseUtils.tweetPollRef(tweet.pollId).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        XPoll poll = snap.getValue(XPoll.class);
                        if (poll == null) { llPoll.setVisibility(View.GONE); return; }
                        pollCache.put(tweet.pollId, poll);
                        renderPoll(llPoll, poll, tweet.pollId);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) { llPoll.setVisibility(View.GONE); }
                });
        }

        private void renderPoll(LinearLayout llPoll, XPoll poll, String pollId) {
            if (poll == null) { llPoll.setVisibility(View.GONE); return; }
            llPoll.removeAllViews();
            boolean voted = poll.userVotes != null && poll.userVotes.containsKey(myUid);
            boolean expired = poll.expired || (poll.expiresAt > 0 && System.currentTimeMillis() > poll.expiresAt);
            long total = poll.totalVotes();
            for (String option : poll.options) {
                View row = LayoutInflater.from(ctx).inflate(R.layout.item_x_poll_option, llPoll, false);
                TextView tvOpt = row.findViewById(R.id.tv_poll_option);
                TextView tvPct = row.findViewById(R.id.tv_poll_percent);
                ProgressBar pb  = row.findViewById(R.id.pb_poll);
                tvOpt.setText(option);
                int pct = poll.percentFor(option);
                boolean myVote = poll.userVotes != null && option.equals(poll.userVotes.get(myUid));
                if (voted || expired) {
                    if (tvPct != null) { tvPct.setVisibility(View.VISIBLE); tvPct.setText(pct + "%"); }
                    if (pb != null) { pb.setVisibility(View.VISIBLE); pb.setProgress(pct); }
                    if (myVote) row.setBackgroundResource(R.drawable.bg_x_poll_voted);
                } else {
                    if (tvPct != null) tvPct.setVisibility(View.GONE);
                    if (pb != null) pb.setVisibility(View.GONE);
                    row.setOnClickListener(v -> castVoteTx(poll, pollId, option));
                }
                llPoll.addView(row);
            }
            View footer = LayoutInflater.from(ctx).inflate(R.layout.item_x_poll_footer, llPoll, false);
            TextView tvTotal = footer.findViewById(R.id.tv_poll_total);
            if (tvTotal != null)
                tvTotal.setText(total + " vote" + (total != 1 ? "s" : "") + (expired ? " · Final results" : ""));
            llPoll.addView(footer);
        }

        /** Firebase Transaction-safe vote cast */
        private void castVoteTx(XPoll poll, String pollId, String option) {
            if (myUid.isEmpty()) return;
            // Record user vote
            XFirebaseUtils.tweetPollRef(pollId).child("userVotes").child(myUid).setValue(option);
            // Atomic increment via Transaction
            XFirebaseUtils.tweetPollRef(pollId).child("voteCounts").child(option)
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
            // Refresh poll from Firebase
            pollCache.remove(pollId);
            int pos = getAdapterPosition();
            if (pos != RecyclerView.NO_ID) notifyItemChanged(pos);
        }

        // ── Link preview ──────────────────────────────────────────────────

        private void bindLinkPreview(XTweet tweet) {
            if (cardLinkPreview == null) return;
            if (tweet.linkPreviewUrl == null || tweet.linkPreviewUrl.isEmpty()) {
                cardLinkPreview.setVisibility(View.GONE); return;
            }
            cardLinkPreview.setVisibility(View.VISIBLE);
            if (tvLinkTitle != null)  tvLinkTitle.setText(tweet.linkPreviewTitle != null ? tweet.linkPreviewTitle : tweet.linkPreviewUrl);
            if (tvLinkDesc != null)   tvLinkDesc.setText(tweet.linkPreviewDesc != null ? tweet.linkPreviewDesc : "");
            if (tvLinkDomain != null) tvLinkDomain.setText(tweet.linkPreviewDomain != null ? tweet.linkPreviewDomain : "");
            if (ivLinkThumb != null && tweet.linkPreviewImageUrl != null && !tweet.linkPreviewImageUrl.isEmpty()) {
                Glide.with(ctx).load(tweet.linkPreviewImageUrl).centerCrop().into(ivLinkThumb);
            }
            cardLinkPreview.setOnClickListener(v -> {
                Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(tweet.linkPreviewUrl));
                ctx.startActivity(i);
            });
        }

        // ── Spannable text ────────────────────────────────────────────────

        private SpannableString buildSpannable(String text) {
            if (text == null) return new SpannableString("");
            SpannableString ss = new SpannableString(text);
            int accent = ContextCompat.getColor(ctx, R.color.x_accent);
            // Hashtags
            Matcher hm = Pattern.compile("#\\w+").matcher(text);
            while (hm.find()) {
                final String tag = hm.group();
                ss.setSpan(new ForegroundColorSpan(accent), hm.start(), hm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new ClickableSpan() {
                    @Override public void onClick(@NonNull View w) {
                        ctx.startActivity(new Intent(ctx, XHashtagActivity.class).putExtra("hashtag", tag));
                    }
                    @Override public void updateDrawState(@NonNull TextPaint ds) {
                        ds.setColor(accent); ds.setUnderlineText(false);
                    }
                }, hm.start(), hm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Mentions
            Matcher mm = Pattern.compile("@\\w+").matcher(text);
            while (mm.find()) {
                final String handle = mm.group().substring(1).toLowerCase(Locale.US);
                ss.setSpan(new ForegroundColorSpan(accent), mm.start(), mm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new ClickableSpan() {
                    @Override public void onClick(@NonNull View w) {
                        XFirebaseUtils.root_x_users()
                            .orderByChild("handle").equalTo(handle).limitToFirst(1).get()
                            .addOnSuccessListener(snap -> {
                                for (DataSnapshot ds : snap.getChildren()) {
                                    if (ctx instanceof FragmentActivity)
                                        XProfileSheet.showProfile(
                                            ((FragmentActivity) ctx).getSupportFragmentManager(),
                                            ds.getKey());
                                    return;
                                }
                                Toast.makeText(ctx, "@" + handle + " not found", Toast.LENGTH_SHORT).show();
                            });
                    }
                    @Override public void updateDrawState(@NonNull TextPaint ds) {
                        ds.setColor(accent); ds.setUnderlineText(false);
                    }
                }, mm.start(), mm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return ss;
        }

        private String formatTime(long ts) {
            long diff = System.currentTimeMillis() - ts;
            if (diff < 60_000) return diff / 1000 + "s";
            if (diff < 3_600_000) return diff / 60_000 + "m";
            if (diff < 86_400_000) return diff / 3_600_000 + "h";
            return new SimpleDateFormat("MMM d", Locale.US).format(new Date(ts));
        }

        private String formatCount(long n) {
            if (n <= 0) return "";
            if (n < 1000) return String.valueOf(n);
            if (n < 1_000_000) return String.format(Locale.US, "%.1fK", n / 1000.0);
            return String.format(Locale.US, "%.1fM", n / 1_000_000.0);
        }

        private String pick(String a, String b) {
            return (a != null && !a.isEmpty()) ? a : b;
        }
    }
}
