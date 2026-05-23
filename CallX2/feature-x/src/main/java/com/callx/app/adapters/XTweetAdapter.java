package com.callx.app.adapters;

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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.activities.XHashtagActivity;
import com.callx.app.activities.XImageViewerActivity;
import com.callx.app.activities.XProfileActivity;
import com.callx.app.activities.XTweetDetailActivity;
import com.callx.app.activities.XVideoPlayerActivity;
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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XTweetAdapter extends RecyclerView.Adapter<XTweetAdapter.TweetVH> {

    private final Context ctx;
    private final List<XTweet> tweets = new ArrayList<>();
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
    }

    public void setTweets(List<XTweet> list) {
        tweets.clear(); tweets.addAll(list); notifyDataSetChanged();
    }

    public void addTweet(XTweet tweet) {
        tweets.add(0, tweet); notifyItemInserted(0);
    }

    /**
     * Scroll listener ke liye current list return karta hai.
     * XHomeFragment ka preloader is list se preload karta hai.
     */
    public List<XTweet> getTweets() {
        return tweets;
    }

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

    class TweetVH extends RecyclerView.ViewHolder {
        ImageView ivAvatar, ivMedia, ivVerified;
        TextView tvName, tvHandle, tvTime, tvText, tvLikes, tvRetweets, tvReplies, tvViews;
        View btnLike, btnRetweet, btnReply, btnBookmark, btnShare, btnMore;
        ImageView icLike, icRetweet, icBookmark;
        // Quote tweet card
        View cardQuote;
        TextView tvQuoteName, tvQuoteText;
        // Poll container
        LinearLayout llPoll;

        TweetVH(View v) {
            super(v);
            ivAvatar   = v.findViewById(R.id.iv_x_avatar);
            ivMedia    = v.findViewById(R.id.iv_x_media);
            ivVerified = v.findViewById(R.id.iv_x_verified);
            tvName     = v.findViewById(R.id.tv_x_name);
            tvHandle   = v.findViewById(R.id.tv_x_handle);
            tvTime     = v.findViewById(R.id.tv_x_time);
            tvText     = v.findViewById(R.id.tv_x_text);
            tvLikes    = v.findViewById(R.id.tv_x_likes);
            tvRetweets = v.findViewById(R.id.tv_x_retweets);
            tvReplies  = v.findViewById(R.id.tv_x_replies);
            tvViews    = v.findViewById(R.id.tv_x_views);
            btnLike    = v.findViewById(R.id.btn_x_like);
            btnRetweet = v.findViewById(R.id.btn_x_retweet);
            btnReply   = v.findViewById(R.id.btn_x_reply);
            btnBookmark= v.findViewById(R.id.btn_x_bookmark);
            btnShare   = v.findViewById(R.id.btn_x_share);
            btnMore    = v.findViewById(R.id.btn_x_more);
            icLike     = v.findViewById(R.id.ic_x_like);
            icRetweet  = v.findViewById(R.id.ic_x_retweet);
            icBookmark = v.findViewById(R.id.ic_x_bookmark);
            cardQuote  = v.findViewById(R.id.card_x_quote);
            tvQuoteName= v.findViewById(R.id.tv_x_quote_name);
            tvQuoteText= v.findViewById(R.id.tv_x_quote_text);
            llPoll     = v.findViewById(R.id.ll_x_poll);
        }

        void bind(XTweet tweet) {
            // Avatar + profile tap
            String tweetAvatarUrl = (tweet.authorThumbUrl != null && !tweet.authorThumbUrl.isEmpty())
                ? tweet.authorThumbUrl : tweet.authorPhotoUrl;
            Glide.with(ctx).load(tweetAvatarUrl)
                .circleCrop().placeholder(R.drawable.ic_person).into(ivAvatar);
            ivAvatar.setOnClickListener(v -> ctx.startActivity(
                new Intent(ctx, XProfileActivity.class).putExtra("uid", tweet.authorUid)));

            // Meta
            tvName.setText(tweet.authorName);
            tvHandle.setText("@" + tweet.authorHandle);
            tvTime.setText(formatTime(tweet.timestamp));
            ivVerified.setVisibility(tweet.authorVerified ? View.VISIBLE : View.GONE);

            // Text with clickable hashtags + mentions
            tvText.setText(buildSpannable(tweet.text));
            tvText.setMovementMethod(LinkMovementMethod.getInstance());

            // Media
            bindMedia(tweet);

            // Quote tweet card
            bindQuote(tweet);

            // Poll
            bindPoll(tweet);

            // Counts
            tvLikes.setText(formatCount(tweet.likeCount));
            tvRetweets.setText(formatCount(tweet.retweetCount));
            tvReplies.setText(formatCount(tweet.replyCount));
            tvViews.setText(formatCount(tweet.viewCount));

            // Action states
            boolean liked = tweet.isLikedBy(myUid);
            icLike.setColorFilter(liked
                ? ContextCompat.getColor(ctx, R.color.x_like_active)
                : ContextCompat.getColor(ctx, R.color.x_icon_default));

            boolean rted = tweet.isRetweetedBy(myUid);
            icRetweet.setColorFilter(rted
                ? ContextCompat.getColor(ctx, R.color.x_retweet_active)
                : ContextCompat.getColor(ctx, R.color.x_icon_default));

            boolean bkd = tweet.isBookmarkedBy(myUid);
            icBookmark.setColorFilter(bkd
                ? ContextCompat.getColor(ctx, R.color.x_bookmark_active)
                : ContextCompat.getColor(ctx, R.color.x_icon_default));

            // Buttons
            btnLike.setOnClickListener(v -> { if (listener != null) listener.onLike(tweet, !liked); });
            btnRetweet.setOnClickListener(v -> { if (listener != null) listener.onRetweet(tweet, !rted); });
            btnReply.setOnClickListener(v -> { if (listener != null) listener.onReply(tweet); });
            btnBookmark.setOnClickListener(v -> { if (listener != null) listener.onBookmark(tweet); });
            btnShare.setOnClickListener(v -> { if (listener != null) listener.onShare(tweet); });
            btnMore.setOnClickListener(v -> { if (listener != null) listener.onMore(tweet, v); });

            itemView.setOnClickListener(v -> ctx.startActivity(
                new Intent(ctx, XTweetDetailActivity.class).putExtra("tweet_id", tweet.id)));
        }

        private void bindMedia(XTweet tweet) {
            if (ivMedia == null) return;
            if (tweet.mediaUrl != null && !tweet.mediaUrl.isEmpty()) {
                ivMedia.setVisibility(View.VISIBLE);
                boolean isVideo = "video".equals(tweet.mediaType);
                String thumb = isVideo && tweet.thumbnailUrl != null ? tweet.thumbnailUrl : tweet.mediaUrl;
                Glide.with(ctx).load(thumb).centerCrop().into(ivMedia);
                ivMedia.setOnClickListener(v -> {
                    if (isVideo) {
                        ctx.startActivity(new Intent(ctx, XVideoPlayerActivity.class)
                            .putExtra("video_url", tweet.mediaUrl));
                    } else {
                        ctx.startActivity(new Intent(ctx, XImageViewerActivity.class)
                            .putExtra("image_url", tweet.mediaUrl));
                    }
                });
            } else {
                ivMedia.setVisibility(View.GONE);
            }
        }

        private void bindQuote(XTweet tweet) {
            if (cardQuote == null) return;
            if (tweet.quotedTweetId != null && !tweet.quotedTweetId.isEmpty()) {
                cardQuote.setVisibility(View.VISIBLE);
                XFirebaseUtils.tweetRef(tweet.quotedTweetId).addListenerForSingleValueEvent(
                    new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            XTweet q = snap.getValue(XTweet.class);
                            if (q == null || q.isDeleted) { cardQuote.setVisibility(View.GONE); return; }
                            if (tvQuoteName != null)
                                tvQuoteName.setText("@" + q.authorHandle + ": ");
                            if (tvQuoteText != null) tvQuoteText.setText(q.text);
                            cardQuote.setOnClickListener(v -> ctx.startActivity(
                                new Intent(ctx, XTweetDetailActivity.class)
                                    .putExtra("tweet_id", tweet.quotedTweetId)));
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            cardQuote.setVisibility(View.GONE);
                        }
                    });
            } else {
                cardQuote.setVisibility(View.GONE);
            }
        }

        private void bindPoll(XTweet tweet) {
            if (llPoll == null) return;
            if (tweet.pollId != null && !tweet.pollId.isEmpty()) {
                llPoll.setVisibility(View.VISIBLE);
                XFirebaseUtils.tweetPollRef(tweet.pollId).addListenerForSingleValueEvent(
                    new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot snap) {
                            XPoll poll = snap.getValue(XPoll.class);
                            if (poll == null) { llPoll.setVisibility(View.GONE); return; }
                            llPoll.removeAllViews();
                            boolean voted = poll.userVotes.containsKey(myUid);
                            boolean expired = poll.expired ||
                                (poll.expiresAt > 0 && System.currentTimeMillis() > poll.expiresAt);
                            long total = poll.totalVotes();
                            for (String option : poll.options) {
                                View row = LayoutInflater.from(ctx)
                                    .inflate(R.layout.item_x_poll_option, llPoll, false);
                                TextView tvOpt = row.findViewById(R.id.tv_poll_option);
                                TextView tvPct = row.findViewById(R.id.tv_poll_percent);
                                ProgressBar pb  = row.findViewById(R.id.pb_poll);
                                tvOpt.setText(option);
                                int pct = poll.percentFor(option);
                                boolean myVote = option.equals(poll.userVotes.get(myUid));
                                if (voted || expired) {
                                    tvPct.setVisibility(View.VISIBLE);
                                    tvPct.setText(pct + "%");
                                    pb.setVisibility(View.VISIBLE);
                                    pb.setProgress(pct);
                                    if (myVote) row.setBackgroundResource(R.drawable.bg_x_poll_voted);
                                } else {
                                    tvPct.setVisibility(View.GONE);
                                    pb.setVisibility(View.GONE);
                                    row.setOnClickListener(v -> castVote(poll, tweet.pollId, option));
                                }
                                llPoll.addView(row);
                            }
                            // Footer: total votes + time
                            View footer = LayoutInflater.from(ctx)
                                .inflate(R.layout.item_x_poll_footer, llPoll, false);
                            TextView tvTotal = footer.findViewById(R.id.tv_poll_total);
                            if (tvTotal != null)
                                tvTotal.setText(total + " vote" + (total != 1 ? "s" : "")
                                    + (expired ? " · Final results" : ""));
                            llPoll.addView(footer);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            llPoll.setVisibility(View.GONE);
                        }
                    });
            } else {
                llPoll.setVisibility(View.GONE);
            }
        }

        private void castVote(XPoll poll, String pollId, String option) {
            if (myUid.isEmpty()) return;
            // Optimistic update
            XFirebaseUtils.tweetPollRef(pollId).child("userVotes").child(myUid).setValue(option);
            Long cur = poll.voteCounts.get(option);
            XFirebaseUtils.tweetPollRef(pollId).child("voteCounts").child(option)
                .setValue(cur != null ? cur + 1 : 1);
            notifyItemChanged(getAdapterPosition());
        }

        private SpannableString buildSpannable(String text) {
            if (text == null) return new SpannableString("");
            SpannableString ss = new SpannableString(text);
            int accent = ContextCompat.getColor(ctx, R.color.x_accent);
            // Hashtags
            Matcher hm = Pattern.compile("#\\w+").matcher(text);
            while (hm.find()) {
                final String tag = hm.group();
                ss.setSpan(new ForegroundColorSpan(accent), hm.start(), hm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new ClickableSpan() {
                    @Override public void onClick(@NonNull View w) {
                        ctx.startActivity(new Intent(ctx, XHashtagActivity.class)
                            .putExtra("hashtag", tag));
                    }
                    @Override public void updateDrawState(@NonNull TextPaint ds) {
                        ds.setColor(accent); ds.setUnderlineText(false);
                    }
                }, hm.start(), hm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // Mentions — navigate to profile by handle lookup
            Matcher mm = Pattern.compile("@\\w+").matcher(text);
            while (mm.find()) {
                final String mention = mm.group();
                final String handle = mention.substring(1).toLowerCase(Locale.US);
                ss.setSpan(new ForegroundColorSpan(accent), mm.start(), mm.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ss.setSpan(new ClickableSpan() {
                    @Override public void onClick(@NonNull View w) {
                        // Look up uid by handle
                        XFirebaseUtils.root_x_users()
                            .orderByChild("handle")
                            .equalTo(handle)
                            .limitToFirst(1)
                            .get()
                            .addOnSuccessListener(snap -> {
                                for (DataSnapshot ds : snap.getChildren()) {
                                    ctx.startActivity(new Intent(ctx, XProfileActivity.class)
                                        .putExtra("uid", ds.getKey()));
                                    return;
                                }
                                Toast.makeText(ctx, "@" + handle + " not found",
                                    Toast.LENGTH_SHORT).show();
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
    }
}
