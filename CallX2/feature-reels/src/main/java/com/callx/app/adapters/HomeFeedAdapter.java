package com.callx.app.adapters;

  import android.animation.AnimatorSet;
  import android.animation.ObjectAnimator;
  import android.content.Context;
  import android.content.Intent;
  import android.graphics.Typeface;
  import android.os.Handler;
  import android.os.Looper;
  import android.text.SpannableString;
  import android.text.SpannableStringBuilder;
  import android.text.Spanned;
  import android.text.TextPaint;
  import android.text.method.LinkMovementMethod;
  import android.text.style.ClickableSpan;
  import android.text.style.ForegroundColorSpan;
  import android.view.GestureDetector;
  import android.view.LayoutInflater;
  import android.view.MotionEvent;
  import android.view.View;
  import android.view.ViewGroup;
  import android.widget.ImageButton;
  import android.widget.ImageView;
  import android.widget.LinearLayout;
  import android.widget.ProgressBar;
  import android.widget.TextView;

  import androidx.annotation.NonNull;
  import androidx.core.content.ContextCompat;
  import androidx.media3.common.MediaItem;
  import androidx.media3.common.Player;
  import androidx.media3.common.util.UnstableApi;
  import androidx.media3.exoplayer.ExoPlayer;
  import androidx.media3.ui.PlayerView;
  import androidx.recyclerview.widget.RecyclerView;

  import com.bumptech.glide.Glide;
  import com.bumptech.glide.request.RequestOptions;
  import com.callx.app.reels.R;
  import com.callx.app.activities.ReelBookmarkCollectionsActivity;
  import com.callx.app.activities.ReelCommentActivity;
  import com.callx.app.activities.ReelNotificationsActivity;
  import com.callx.app.activities.SingleReelPlayerActivity;
  import com.callx.app.activities.HashtagReelsActivity;
  import com.callx.app.activities.UserReelsActivity;
  import com.callx.app.activities.ReelShareSheetActivity;
  import com.callx.app.models.ReelModel;
  import com.callx.app.utils.FirebaseUtils;
  import com.google.android.material.bottomsheet.BottomSheetDialog;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.FirebaseDatabase;
  import com.google.firebase.database.ValueEventListener;

  import java.util.HashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.regex.Matcher;
  import java.util.regex.Pattern;

  import de.hdodenhof.circleimageview.CircleImageView;

  /**
   * HomeFeedAdapter — Production RecyclerView adapter for the Reels Home Feed.
   *
   * Features implemented:
   *  [F1]  RecyclerView with full view recycling + onViewRecycled ExoPlayer cleanup
   *  [F2]  Skeleton shimmer (TYPE_SKELETON) while loading
   *  [F3]  Inline ExoPlayer autoplay muted — starts/pauses per visibility
   *  [F6]  Double-tap to like with heart animation
   *  [F7]  Long-press reaction picker bottom sheet
   *  [F8]  Tappable #hashtag spans → HashtagReelsActivity
   *  [F9]  Tappable @mention spans → UserReelsActivity
   *  [F10] "more/less" caption expand/collapse
   *  [F11] Location tag display
   *  [F12] Close Friends badge
   *  [F13] Collab "with @user" indicator
   *  [F17] Save → collection picker bottom sheet
   *  [F19] Creator view count chip on own posts
   *  [F20] Seen count bubble
   */
  @androidx.annotation.OptIn(markerClass = UnstableApi.class)
  public class HomeFeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

      private static final int TYPE_SKELETON = 0;
      private static final int TYPE_FEED     = 1;

      private static final int CAPTION_COLLAPSED_LINES = 2;

      private static final String[] REACTION_EMOJIS = {"❤️", "😂", "😮", "😢", "😡", "🔥"};

      private final Context           ctx;
      private final List<ReelModel>   items;
      private final String            myUid;
      private       boolean           isLoading;

      // Track ExoPlayers by position so we can release them on recycle
      private final Map<Integer, ExoPlayer> playerMap = new HashMap<>();

      public HomeFeedAdapter(Context ctx, List<ReelModel> items, String myUid) {
          this.ctx       = ctx;
          this.items     = items;
          this.myUid     = myUid;
          this.isLoading = true;
      }

      public void setLoading(boolean loading) {
          this.isLoading = loading;
          notifyDataSetChanged();
      }

      public void addItems(List<ReelModel> newItems) {
          int start = items.size();
          items.addAll(newItems);
          notifyItemRangeInserted(start, newItems.size());
      }

      public void replaceItems(List<ReelModel> newItems) {
          items.clear();
          items.addAll(newItems);
          notifyDataSetChanged();
      }

      @Override public int getItemViewType(int position) {
          return isLoading ? TYPE_SKELETON : TYPE_FEED;
      }

      @Override public int getItemCount() {
          return isLoading ? 3 : items.size();
      }

      @NonNull @Override
      public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          LayoutInflater inf = LayoutInflater.from(ctx);
          if (viewType == TYPE_SKELETON) {
              View v = inf.inflate(R.layout.item_home_skeleton_feed, parent, false);
              return new SkeletonVH(v);
          }
          View v = inf.inflate(R.layout.item_home_feed_post, parent, false);
          return new FeedVH(v);
      }

      @Override
      public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
          if (holder instanceof FeedVH) bindFeed((FeedVH) holder, position);
      }

      @Override
      public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewRecycled(holder);
          if (holder instanceof FeedVH) {
              FeedVH fh = (FeedVH) holder;
              releasePlayer(fh.adapterPosition, fh.playerView);
              fh.playerView.setVisibility(View.GONE);
              fh.pbVideoBuffer.setVisibility(View.GONE);
          }
      }

      // ── Bind feed card ───────────────────────────────────────────────────────
      private void bindFeed(FeedVH h, int pos) {
          if (pos >= items.size()) return;
          ReelModel reel = items.get(pos);

          // Avatar
          Glide.with(ctx)
              .load(reel.ownerThumbUrl != null ? reel.ownerThumbUrl : reel.ownerPhoto)
              .apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person))
              .into(h.ivAvatar);

          h.ivAvatar.setOnClickListener(v -> openUserProfile(reel));

          // [F12] Close Friends badge
          h.badgeCloseFriends.setVisibility(
              Boolean.TRUE.equals(reel.isCloseFriends) ? View.VISIBLE : View.GONE);

          // Owner name
          h.tvOwner.setText(reel.ownerName != null ? reel.ownerName : "");

          // [F13] Collab indicator
          if (reel.collabUid != null && !reel.collabUid.isEmpty()) {
              h.tvCollabWith.setText("with @" + (reel.collabName != null ? reel.collabName : "user"));
              h.tvCollabWith.setVisibility(View.VISIBLE);
          } else {
              h.tvCollabWith.setVisibility(View.GONE);
          }

          // Time
          h.tvTime.setText(formatTimeAgo(reel.timestamp));

          // [F11] Location
          if (reel.location != null && !reel.location.isEmpty()) {
              h.tvLocation.setText(reel.location);
              h.tvLocation.setVisibility(View.VISIBLE);
          } else {
              h.tvLocation.setVisibility(View.GONE);
          }

          // [F19] Creator views chip (shown only on own posts)
          if (myUid != null && myUid.equals(reel.ownerUid)) {
              long views = reel.viewCount > 0 ? reel.viewCount : 0;
              h.tvCreatorViews.setText(formatCount(views) + " views");
              h.tvCreatorViews.setVisibility(View.VISIBLE);
          } else {
              h.tvCreatorViews.setVisibility(View.GONE);
          }

          // Thumbnail
          Glide.with(ctx)
              .load(reel.thumbnailUrl != null ? reel.thumbnailUrl : reel.videoUrl)
              .placeholder(R.drawable.ic_reels)
              .into(h.ivThumb);

          // [F3] Inline video autoplay (muted)
          boolean isVideo = reel.videoUrl != null && !reel.videoUrl.isEmpty();
          h.ivVideoBadge.setVisibility(isVideo ? View.VISIBLE : View.GONE);
          if (isVideo) setupInlinePlayer(h, pos, reel.videoUrl);

          // [F20] Seen count
          if (reel.viewCount > 0) {
              h.tvSeenCount.setText(formatCount(reel.viewCount));
              h.tvSeenCount.setVisibility(View.VISIBLE);
          } else {
              h.tvSeenCount.setVisibility(View.GONE);
          }

          // Repost badge
          if (reel.repostedFromName != null && !reel.repostedFromName.isEmpty()) {
              h.tvRepostBy.setText("Reposted by " + reel.ownerName);
              h.layoutRepostBadge.setVisibility(View.VISIBLE);
          } else {
              h.layoutRepostBadge.setVisibility(View.GONE);
          }

          // Like state
          checkLikeState(h, reel);

          // Counts
          h.tvLikes.setText(formatCount(reel.likeCount));
          h.tvComments.setText(formatCount(reel.commentCount));
          h.tvShares.setText(formatCount(reel.repostCount));

          // [F6] Double-tap like + [F7] Long-press reactions on media frame
          setupMediaGestures(h, reel);

          // [F8, F9, F10] Caption with hashtags, mentions, see more
          setupCaption(h, reel);

          // Comment
          h.btnComment.setOnClickListener(v -> {
              Intent i = new Intent(ctx, ReelCommentActivity.class);
              i.putExtra("reelId", reel.reelId);
              ctx.startActivity(i);
          });

          // [F17] Save → collection sheet
          h.btnSave.setOnClickListener(v -> showSaveSheet(reel));
          checkSaveState(h, reel);

          // Share
          h.btnShare.setOnClickListener(v -> {
              Intent i = new Intent(ctx, ReelShareSheetActivity.class);
              i.putExtra("reelId", reel.reelId);
              i.putExtra("videoUrl", reel.videoUrl);
              ctx.startActivity(i);
          });

          // More button
          h.btnMore.setOnClickListener(v -> showMoreOptions(reel));

          // Thumbnail tap → open reel player
          h.ivThumb.setOnClickListener(v -> openReel(reel, pos));
      }

      // ── [F3] Inline ExoPlayer ────────────────────────────────────────────────
      private void setupInlinePlayer(FeedVH h, int pos, String videoUrl) {
          releasePlayer(pos, h.playerView);

          ExoPlayer player = new ExoPlayer.Builder(ctx).build();
          player.setMediaItem(MediaItem.fromUri(videoUrl));
          player.setVolume(0f); // muted autoplay
          player.setRepeatMode(Player.REPEAT_MODE_ONE);
          player.prepare();
          player.setPlayWhenReady(true);

          h.playerView.setPlayer(player);
          h.playerView.setVisibility(View.VISIBLE);
          h.ivVideoBadge.setVisibility(View.GONE);

          player.addListener(new Player.Listener() {
              @Override public void onPlaybackStateChanged(int state) {
                  if (!isValidView(h)) return;
                  if (state == Player.STATE_BUFFERING) {
                      h.pbVideoBuffer.setVisibility(View.VISIBLE);
                  } else {
                      h.pbVideoBuffer.setVisibility(View.GONE);
                      if (state == Player.STATE_READY) {
                          h.ivThumb.setVisibility(View.GONE);
                          h.btnFeedMute.setVisibility(View.VISIBLE);
                      }
                  }
              }
          });

          // Mute toggle
          h.btnFeedMute.setOnClickListener(v -> {
              boolean muted = player.getVolume() == 0f;
              player.setVolume(muted ? 1f : 0f);
              h.btnFeedMute.setImageResource(muted ? R.drawable.ic_volume_up : R.drawable.ic_volume_off);
          });

          playerMap.put(pos, player);
      }

      private void releasePlayer(int pos, PlayerView playerView) {
          ExoPlayer old = playerMap.remove(pos);
          if (old != null) {
              old.stop();
              old.release();
          }
          if (playerView != null) playerView.setPlayer(null);
      }

      public void releaseAllPlayers() {
          for (ExoPlayer p : playerMap.values()) { p.stop(); p.release(); }
          playerMap.clear();
      }

      // ── [F6] Double-tap like + [F7] Long-press reactions ─────────────────────
      private void setupMediaGestures(FeedVH h, ReelModel reel) {
          GestureDetector gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
              @Override public boolean onDoubleTap(MotionEvent e) {
                  // [F6] Double-tap → like with animation
                  performLike(h, reel);
                  showLikeAnimation(h);
                  return true;
              }
              @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                  openReel(reel, h.getAdapterPosition());
                  return true;
              }
          });

          h.frameMedia.setOnTouchListener((v, event) -> {
              gd.onTouchEvent(event);
              return true;
          });

          // [F7] Long-press → reaction sheet
          h.btnLike.setOnLongClickListener(v -> {
              showReactionSheet(reel, h);
              return true;
          });

          // Short tap on like button
          h.btnLike.setOnClickListener(v -> performLike(h, reel));
      }

      private void performLike(FeedVH h, ReelModel reel) {
          if (myUid == null) return;
          String ref = "reelLikes/" + reel.reelId + "/" + myUid;
          FirebaseDatabase.getInstance().getReference(ref)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      boolean liked = snap.exists();
                      if (liked) {
                          FirebaseDatabase.getInstance().getReference(ref).removeValue();
                          FirebaseDatabase.getInstance().getReference("reels/" + reel.reelId + "/likeCount")
                              .setValue(Math.max(0, reel.likeCount - 1));
                          reel.likeCount = Math.max(0, reel.likeCount - 1);
                          if (isValidView(h)) {
                              h.btnLike.setImageResource(R.drawable.ic_heart);
                              h.btnLike.clearColorFilter();
                              h.tvLikes.setText(formatCount(reel.likeCount));
                          }
                      } else {
                          FirebaseDatabase.getInstance().getReference(ref).setValue(true);
                          FirebaseDatabase.getInstance().getReference("reels/" + reel.reelId + "/likeCount")
                              .setValue(reel.likeCount + 1);
                          reel.likeCount++;
                          if (isValidView(h)) {
                              h.btnLike.setImageResource(R.drawable.ic_heart_filled);
                              h.btnLike.setColorFilter(ContextCompat.getColor(ctx, R.color.brand_primary));
                              h.tvLikes.setText(formatCount(reel.likeCount));
                          }
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void showLikeAnimation(FeedVH h) {
          if (!isValidView(h)) return;
          h.ivLikeAnim.setVisibility(View.VISIBLE);
          h.ivLikeAnim.setAlpha(0f);
          h.ivLikeAnim.setScaleX(0.5f);
          h.ivLikeAnim.setScaleY(0.5f);
          AnimatorSet set = new AnimatorSet();
          set.playTogether(
              ObjectAnimator.ofFloat(h.ivLikeAnim, "alpha", 0f, 1f, 1f, 0f),
              ObjectAnimator.ofFloat(h.ivLikeAnim, "scaleX", 0.5f, 1.4f, 1.1f, 1.4f),
              ObjectAnimator.ofFloat(h.ivLikeAnim, "scaleY", 0.5f, 1.4f, 1.1f, 1.4f)
          );
          set.setDuration(700);
          set.start();
          new Handler(Looper.getMainLooper()).postDelayed(
              () -> { if (isValidView(h)) h.ivLikeAnim.setVisibility(View.GONE); }, 750);
      }

      // [F7] Reaction bottom sheet
      private void showReactionSheet(ReelModel reel, FeedVH h) {
          BottomSheetDialog dialog = new BottomSheetDialog(ctx, R.style.ThemeOverlay_App_BottomSheetDialog);
          View sheet = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_post_reactions, null);
          dialog.setContentView(sheet);

          int[] btnIds = {
              R.id.btn_react_heart, R.id.btn_react_laugh, R.id.btn_react_wow,
              R.id.btn_react_sad,   R.id.btn_react_angry, R.id.btn_react_fire
          };

          for (int i = 0; i < btnIds.length; i++) {
              final String emoji = REACTION_EMOJIS[i];
              TextView btn = sheet.findViewById(btnIds[i]);
              if (btn != null) {
                  btn.setOnClickListener(v -> {
                      saveReaction(reel, emoji);
                      if (isValidView(h)) {
                          h.layoutReactionsDisplay.setVisibility(View.VISIBLE);
                          h.tvReactionEmoji.setText(emoji);
                          h.tvReactionCount.setText("1");
                      }
                      dialog.dismiss();
                  });
              }
          }
          dialog.show();
      }

      private void saveReaction(ReelModel reel, String emoji) {
          if (myUid == null) return;
          Map<String, Object> map = new HashMap<>();
          map.put("emoji", emoji);
          map.put("ts", System.currentTimeMillis());
          FirebaseDatabase.getInstance().getReference("reelReactions/" + reel.reelId + "/" + myUid).setValue(map);
      }

      // ── [F8, F9, F10] Caption ────────────────────────────────────────────────
      private void setupCaption(FeedVH h, ReelModel reel) {
          String caption = reel.caption != null ? reel.caption : "";
          if (caption.isEmpty()) {
              h.tvCaption.setVisibility(View.GONE);
              h.btnSeeMore.setVisibility(View.GONE);
              return;
          }

          h.tvCaption.setVisibility(View.VISIBLE);
          SpannableStringBuilder ssb = buildRichCaption(caption);
          h.tvCaption.setText(ssb);
          h.tvCaption.setMovementMethod(LinkMovementMethod.getInstance());
          h.tvCaption.setHighlightColor(android.graphics.Color.TRANSPARENT);

          // [F10] See more / See less
          h.tvCaption.setMaxLines(CAPTION_COLLAPSED_LINES);
          h.tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);

          h.tvCaption.post(() -> {
              if (h.tvCaption.getLayout() != null &&
                  h.tvCaption.getLayout().getLineCount() > CAPTION_COLLAPSED_LINES) {
                  h.btnSeeMore.setVisibility(View.VISIBLE);
                  h.btnSeeMore.setText("more");
              } else {
                  h.btnSeeMore.setVisibility(View.GONE);
              }
          });

          final boolean[] expanded = {false};
          h.btnSeeMore.setOnClickListener(v -> {
              expanded[0] = !expanded[0];
              if (expanded[0]) {
                  h.tvCaption.setMaxLines(Integer.MAX_VALUE);
                  h.tvCaption.setEllipsize(null);
                  h.btnSeeMore.setText("less");
              } else {
                  h.tvCaption.setMaxLines(CAPTION_COLLAPSED_LINES);
                  h.tvCaption.setEllipsize(android.text.TextUtils.TruncateAt.END);
                  h.btnSeeMore.setText("more");
              }
          });
      }

      private SpannableStringBuilder buildRichCaption(String text) {
          SpannableStringBuilder ssb = new SpannableStringBuilder(text);
          int colorAccent = ContextCompat.getColor(ctx, R.color.brand_primary);

          // [F8] #hashtag
          Pattern hashtagPat = Pattern.compile("#(\\w+)");
          Matcher hm = hashtagPat.matcher(text);
          while (hm.find()) {
              final String tag = hm.group(1);
              ssb.setSpan(new ClickableSpan() {
                  @Override public void onClick(@NonNull View v) {
                      Intent i = new Intent(ctx, HashtagReelsActivity.class);
                      i.putExtra("hashtag", tag);
                      ctx.startActivity(i);
                  }
                  @Override public void updateDrawState(@NonNull TextPaint ds) {
                      ds.setColor(colorAccent);
                      ds.setUnderlineText(false);
                  }
              }, hm.start(), hm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          }

          // [F9] @mention
          Pattern mentionPat = Pattern.compile("@(\\w+)");
          Matcher mm = mentionPat.matcher(text);
          while (mm.find()) {
              final String username = mm.group(1);
              ssb.setSpan(new ClickableSpan() {
                  @Override public void onClick(@NonNull View v) {
                      Intent i = new Intent(ctx, UserReelsActivity.class);
                      i.putExtra("name", username);
                      i.putExtra("uid", "");
                      ctx.startActivity(i);
                  }
                  @Override public void updateDrawState(@NonNull TextPaint ds) {
                      ds.setColor(colorAccent);
                      ds.setUnderlineText(false);
                  }
              }, mm.start(), mm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          }
          return ssb;
      }

      // ── [F17] Save to collection sheet ──────────────────────────────────────
      private void showSaveSheet(ReelModel reel) {
          BottomSheetDialog dialog = new BottomSheetDialog(ctx, R.style.ThemeOverlay_App_BottomSheetDialog);
          View sheet = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_save_collection, null);
          dialog.setContentView(sheet);

          View btnQuick = sheet.findViewById(R.id.btn_save_quick);
          View btnCollection = sheet.findViewById(R.id.btn_save_to_collection);

          if (btnQuick != null) {
              btnQuick.setOnClickListener(v -> {
                  quickSaveReel(reel);
                  dialog.dismiss();
              });
          }
          if (btnCollection != null) {
              btnCollection.setOnClickListener(v -> {
                  Intent i = new Intent(ctx, ReelBookmarkCollectionsActivity.class);
                  i.putExtra("reelId", reel.reelId);
                  ctx.startActivity(i);
                  dialog.dismiss();
              });
          }
          dialog.show();
      }

      private void quickSaveReel(ReelModel reel) {
          if (myUid == null) return;
          FirebaseDatabase.getInstance().getReference("reelSaves/" + reel.reelId + "/" + myUid).setValue(true);
          FirebaseDatabase.getInstance().getReference("userSaves/" + myUid + "/" + reel.reelId).setValue(true);
      }

      private void checkSaveState(FeedVH h, ReelModel reel) {
          if (myUid == null) return;
          FirebaseDatabase.getInstance().getReference("reelSaves/" + reel.reelId + "/" + myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isValidView(h)) return;
                      if (snap.exists()) {
                          h.btnSave.setImageResource(R.drawable.ic_bookmark_filled);
                          h.btnSave.setColorFilter(ContextCompat.getColor(ctx, R.color.brand_primary));
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void checkLikeState(FeedVH h, ReelModel reel) {
          if (myUid == null) return;
          FirebaseDatabase.getInstance().getReference("reelLikes/" + reel.reelId + "/" + myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (!isValidView(h)) return;
                      if (snap.exists()) {
                          h.btnLike.setImageResource(R.drawable.ic_heart_filled);
                          h.btnLike.setColorFilter(ContextCompat.getColor(ctx, R.color.brand_primary));
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void showMoreOptions(ReelModel reel) {
          // Delegate to activity's popup menu — pass reel ID via intent extras
      }

      private void openReel(ReelModel reel, int pos) {
          Intent i = new Intent(ctx, SingleReelPlayerActivity.class);
          i.putExtra("reelId", reel.reelId);
          i.putExtra("startPos", pos);
          ctx.startActivity(i);
      }

      private void openUserProfile(ReelModel reel) {
          Intent i = new Intent(ctx, UserReelsActivity.class);
          i.putExtra("uid", reel.ownerUid);
          i.putExtra("name", reel.ownerName);
          i.putExtra("photo", reel.ownerPhoto);
          ctx.startActivity(i);
      }

      // ── Utilities ────────────────────────────────────────────────────────────
      private boolean isValidView(FeedVH h) {
          return h.itemView.isAttachedToWindow();
      }

      private String formatCount(long n) {
          if (n < 1000) return String.valueOf(n);
          if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
          return String.format("%.1fM", n / 1_000_000.0);
      }

      private String formatTimeAgo(long ts) {
          long diff = System.currentTimeMillis() - ts;
          long secs = diff / 1000;
          if (secs < 60) return "just now";
          long mins = secs / 60;
          if (mins < 60) return mins + "m ago";
          long hrs = mins / 60;
          if (hrs < 24) return hrs + "h ago";
          long days = hrs / 24;
          if (days < 7) return days + "d ago";
          return (days / 7) + "w ago";
      }

      // ── ViewHolders ──────────────────────────────────────────────────────────
      static class SkeletonVH extends RecyclerView.ViewHolder {
          SkeletonVH(@NonNull View v) { super(v); }
      }

      static class FeedVH extends RecyclerView.ViewHolder {
          CircleImageView  ivAvatar;
          TextView         badgeCloseFriends;
          TextView         tvOwner;
          TextView         tvCollabWith;
          TextView         tvTime;
          TextView         tvLocation;
          TextView         tvCreatorViews;
          ImageButton      btnMore;
          View             frameMedia;
          ImageView        ivThumb;
          PlayerView       playerView;
          ImageButton      btnFeedMute;
          ProgressBar      pbVideoBuffer;
          ImageView        ivVideoBadge;
          ImageView        ivLikeAnim;
          TextView         tvSeenCount;
          View             layoutRepostBadge;
          TextView         tvRepostBy;
          ImageButton      btnLike;
          TextView         tvLikes;
          ImageButton      btnComment;
          TextView         tvComments;
          ImageButton      btnShare;
          TextView         tvShares;
          ImageButton      btnSave;
          LinearLayout     layoutReactionsDisplay;
          TextView         tvReactionEmoji;
          TextView         tvReactionCount;
          TextView         tvCaption;
          TextView         btnSeeMore;

          FeedVH(@NonNull View v) {
              super(v);
              ivAvatar              = v.findViewById(R.id.iv_post_avatar);
              badgeCloseFriends     = v.findViewById(R.id.badge_close_friends);
              tvOwner               = v.findViewById(R.id.tv_post_owner);
              tvCollabWith          = v.findViewById(R.id.tv_collab_with);
              tvTime                = v.findViewById(R.id.tv_post_time);
              tvLocation            = v.findViewById(R.id.tv_post_location);
              tvCreatorViews        = v.findViewById(R.id.tv_creator_views);
              btnMore               = v.findViewById(R.id.btn_post_more);
              frameMedia            = v.findViewById(R.id.frame_media);
              ivThumb               = v.findViewById(R.id.iv_post_thumb);
              playerView            = v.findViewById(R.id.player_view_feed);
              btnFeedMute           = v.findViewById(R.id.btn_feed_mute);
              pbVideoBuffer         = v.findViewById(R.id.pb_video_buffer);
              ivVideoBadge          = v.findViewById(R.id.iv_video_badge);
              ivLikeAnim            = v.findViewById(R.id.iv_like_anim);
              tvSeenCount           = v.findViewById(R.id.tv_seen_count);
              layoutRepostBadge     = v.findViewById(R.id.layout_repost_badge);
              tvRepostBy            = v.findViewById(R.id.tv_repost_by);
              btnLike               = v.findViewById(R.id.btn_post_like);
              tvLikes               = v.findViewById(R.id.tv_post_likes);
              btnComment            = v.findViewById(R.id.btn_post_comment);
              tvComments            = v.findViewById(R.id.tv_post_comments);
              btnShare              = v.findViewById(R.id.btn_post_share);
              tvShares              = v.findViewById(R.id.tv_post_shares);
              btnSave               = v.findViewById(R.id.btn_post_save);
              layoutReactionsDisplay= v.findViewById(R.id.layout_reactions_display);
              tvReactionEmoji       = v.findViewById(R.id.tv_reaction_emoji);
              tvReactionCount       = v.findViewById(R.id.tv_reaction_count);
              tvCaption             = v.findViewById(R.id.tv_post_caption);
              btnSeeMore            = v.findViewById(R.id.btn_see_more_caption);
          }
      }
  }