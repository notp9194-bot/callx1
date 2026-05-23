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
  import android.widget.TextView;
  import android.widget.Toast;
  import androidx.annotation.NonNull;
  import androidx.core.content.ContextCompat;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.activities.XHashtagActivity;
  import com.callx.app.activities.XProfileActivity;
  import com.callx.app.activities.XTweetDetailActivity;
  import com.callx.app.models.XTweet;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.firebase.auth.FirebaseAuth;
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
          void onBookmark(XTweet tweet);
          void onShare(XTweet tweet);
          void onMore(XTweet tweet, View anchor);
      }

      public XTweetAdapter(Context ctx, OnTweetActionListener listener) {
          this.ctx      = ctx;
          this.listener = listener;
          this.myUid    = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
      }

      public void setTweets(List<XTweet> list) {
          tweets.clear();
          tweets.addAll(list);
          notifyDataSetChanged();
      }

      public void addTweet(XTweet tweet) {
          tweets.add(0, tweet);
          notifyItemInserted(0);
      }

      public void removeTweet(String id) {
          for (int i = 0; i < tweets.size(); i++) {
              if (id.equals(tweets.get(i).id)) {
                  tweets.remove(i);
                  notifyItemRemoved(i);
                  return;
              }
          }
      }

      @NonNull @Override
      public TweetVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          View v = LayoutInflater.from(ctx).inflate(R.layout.item_x_tweet, parent, false);
          return new TweetVH(v);
      }

      @Override
      public void onBindViewHolder(@NonNull TweetVH h, int position) {
          XTweet tweet = tweets.get(position);
          h.bind(tweet);
      }

      @Override public int getItemCount() { return tweets.size(); }

      class TweetVH extends RecyclerView.ViewHolder {
          ImageView ivAvatar, ivMedia, ivVerified;
          TextView tvName, tvHandle, tvTime, tvText,
                   tvLikes, tvRetweets, tvReplies, tvViews;
          View btnLike, btnRetweet, btnReply, btnBookmark, btnShare, btnMore;
          ImageView icLike, icRetweet, icBookmark;

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
          }

          void bind(XTweet tweet) {
              // Avatar
              Glide.with(ctx).load(tweet.authorPhotoUrl)
                  .circleCrop().placeholder(R.drawable.ic_person)
                  .into(ivAvatar);
              ivAvatar.setOnClickListener(v -> openProfile(tweet.authorUid));

              // Meta
              tvName.setText(tweet.authorName);
              tvHandle.setText("@" + tweet.authorHandle);
              tvTime.setText(formatTime(tweet.timestamp));
              ivVerified.setVisibility(tweet.authorVerified ? View.VISIBLE : View.GONE);

              // Text with hashtag + mention highlighting
              tvText.setText(buildSpannable(tweet.text));
              tvText.setMovementMethod(LinkMovementMethod.getInstance());

              // Media
              if (tweet.mediaUrl != null && !tweet.mediaUrl.isEmpty()) {
                  ivMedia.setVisibility(View.VISIBLE);
                  Glide.with(ctx).load(
                      "video".equals(tweet.mediaType) ? tweet.thumbnailUrl : tweet.mediaUrl)
                      .centerCrop().into(ivMedia);
                  ivMedia.setOnClickListener(v -> {
                      ctx.startActivity(new Intent(ctx, XTweetDetailActivity.class)
                          .putExtra("tweet_id", tweet.id));
                  });
              } else {
                  ivMedia.setVisibility(View.GONE);
              }

              // Counts
              tvLikes.setText(formatCount(tweet.likeCount));
              tvRetweets.setText(formatCount(tweet.retweetCount));
              tvReplies.setText(formatCount(tweet.replyCount));
              tvViews.setText(formatCount(tweet.viewCount));

              // Like state
              boolean liked = tweet.isLikedBy(myUid);
              icLike.setColorFilter(liked
                  ? ContextCompat.getColor(ctx, R.color.x_like_active)
                  : ContextCompat.getColor(ctx, R.color.x_icon_default));

              // Retweet state
              boolean rted = tweet.isRetweetedBy(myUid);
              icRetweet.setColorFilter(rted
                  ? ContextCompat.getColor(ctx, R.color.x_retweet_active)
                  : ContextCompat.getColor(ctx, R.color.x_icon_default));

              // Bookmark state
              boolean bkd = tweet.isBookmarkedBy(myUid);
              icBookmark.setColorFilter(bkd
                  ? ContextCompat.getColor(ctx, R.color.x_bookmark_active)
                  : ContextCompat.getColor(ctx, R.color.x_icon_default));

              // Actions
              btnLike.setOnClickListener(v -> {
                  if (listener != null) listener.onLike(tweet, !liked);
              });
              btnRetweet.setOnClickListener(v -> {
                  if (listener != null) listener.onRetweet(tweet, !rted);
              });
              btnReply.setOnClickListener(v -> {
                  if (listener != null) listener.onReply(tweet);
              });
              btnBookmark.setOnClickListener(v -> {
                  if (listener != null) listener.onBookmark(tweet);
              });
              btnShare.setOnClickListener(v -> {
                  if (listener != null) listener.onShare(tweet);
              });
              btnMore.setOnClickListener(v -> {
                  if (listener != null) listener.onMore(tweet, v);
              });

              // Open detail on tap
              itemView.setOnClickListener(v -> {
                  ctx.startActivity(new Intent(ctx, XTweetDetailActivity.class)
                      .putExtra("tweet_id", tweet.id));
              });
          }

          private void openProfile(String uid) {
              ctx.startActivity(new Intent(ctx, XProfileActivity.class)
                  .putExtra("uid", uid));
          }

          private SpannableString buildSpannable(String text) {
              if (text == null) return new SpannableString("");
              SpannableString ss = new SpannableString(text);
              int accentColor = ContextCompat.getColor(ctx, R.color.x_accent);
              // Hashtags
              Pattern hashPat = Pattern.compile("#\w+");
              Matcher hm = hashPat.matcher(text);
              while (hm.find()) {
                  final String tag = hm.group();
                  ss.setSpan(new ForegroundColorSpan(accentColor), hm.start(), hm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                  ss.setSpan(new ClickableSpan() {
                      @Override public void onClick(@NonNull View w) {
                          ctx.startActivity(new Intent(ctx, XHashtagActivity.class)
                              .putExtra("hashtag", tag));
                      }
                      @Override public void updateDrawState(@NonNull TextPaint ds) {
                          ds.setColor(accentColor); ds.setUnderlineText(false);
                      }
                  }, hm.start(), hm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
              }
              // Mentions
              Pattern mentionPat = Pattern.compile("@\w+");
              Matcher mm = mentionPat.matcher(text);
              while (mm.find()) {
                  final String mention = mm.group();
                  ss.setSpan(new ForegroundColorSpan(accentColor), mm.start(), mm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                  ss.setSpan(new ClickableSpan() {
                      @Override public void onClick(@NonNull View w) {
                          String handle = mention.substring(1);
                          Toast.makeText(ctx, "Opening @" + handle, Toast.LENGTH_SHORT).show();
                      }
                      @Override public void updateDrawState(@NonNull TextPaint ds) {
                          ds.setColor(accentColor); ds.setUnderlineText(false);
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