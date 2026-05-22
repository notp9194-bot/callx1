package com.callx.app.adapters;

  import android.animation.AnimatorSet;
  import android.animation.ObjectAnimator;
  import android.content.Context;
  import android.content.Intent;
  import android.graphics.Color;
  import android.os.Handler;
  import android.os.Looper;
  import android.text.SpannableStringBuilder;
  import android.text.Spanned;
  import android.text.TextPaint;
  import android.text.TextUtils;
  import android.text.method.LinkMovementMethod;
  import android.text.style.ClickableSpan;
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
  import com.callx.app.activities.ReelShareSheetActivity;
  import com.callx.app.activities.SingleReelPlayerActivity;
  import com.callx.app.activities.HashtagReelsActivity;
  import com.callx.app.activities.UserReelsActivity;
  import com.callx.app.models.ReelModel;
  import com.google.android.material.bottomsheet.BottomSheetDialog;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.FirebaseDatabase;
  import com.google.firebase.database.ValueEventListener;
  import de.hdodenhof.circleimageview.CircleImageView;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.regex.Matcher;
  import java.util.regex.Pattern;

  @androidx.annotation.OptIn(markerClass = UnstableApi.class)
  public class HomeFeedAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

      private static final int TYPE_SKELETON = 0;
      private static final int TYPE_FEED     = 1;
      private static final String[] REACTION_EMOJIS = {
          "\u2764\uFE0F","\uD83D\uDE02","\uD83D\uDE2E",
          "\uD83D\uDE22","\uD83D\uDE21","\uD83D\uDD25"
      };

      private final Context         ctx;
      private final List<ReelModel> items;
      private final String          myUid;
      private       boolean         isLoading;
      private final Map<Integer, ExoPlayer> playerMap = new HashMap<>();

      public HomeFeedAdapter(Context ctx, List<ReelModel> items, String myUid) {
          this.ctx = ctx; this.items = items; this.myUid = myUid; this.isLoading = true;
      }

      public void setLoading(boolean loading) { isLoading = loading; notifyDataSetChanged(); }

      public void replaceItems(List<ReelModel> newItems) {
          items.clear(); items.addAll(newItems); notifyDataSetChanged();
      }

      @Override public int getItemViewType(int pos) { return isLoading ? TYPE_SKELETON : TYPE_FEED; }
      @Override public int getItemCount()            { return isLoading ? 3 : items.size(); }

      @NonNull @Override
      public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
          LayoutInflater inf = LayoutInflater.from(ctx);
          if (viewType == TYPE_SKELETON)
              return new SkeletonVH(inf.inflate(R.layout.item_home_skeleton_feed, parent, false));
          return new FeedVH(inf.inflate(R.layout.item_home_feed_post, parent, false));
      }

      @Override
      public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
          if (holder instanceof FeedVH) bindFeed((FeedVH) holder, pos);
      }

      @Override
      public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
          super.onViewRecycled(holder);
          if (holder instanceof FeedVH) releasePlayer(holder.getAdapterPosition(), ((FeedVH) holder).playerView);
      }

      // ── Bind ─────────────────────────────────────────────────────────────────
      private void bindFeed(FeedVH h, int pos) {
          if (pos >= items.size()) return;
          ReelModel reel = items.get(pos);

          Glide.with(ctx).load(reel.ownerThumbUrl != null ? reel.ownerThumbUrl : reel.ownerPhoto)
              .apply(RequestOptions.circleCropTransform().placeholder(R.drawable.ic_person)).into(h.ivAvatar);
          h.ivAvatar.setOnClickListener(v -> openUserProfile(reel));

          // [F12] Close Friends badge
          h.badgeCloseFriends.setVisibility(Boolean.TRUE.equals(reel.isCloseFriends) ? View.VISIBLE : View.GONE);

          // [F13] Collab
          h.tvOwner.setText(reel.ownerName != null ? reel.ownerName : "");
          if (reel.collabUid != null && !reel.collabUid.isEmpty()) {
              h.tvCollabWith.setText("with @" + (reel.collabName != null ? reel.collabName : "user"));
              h.tvCollabWith.setVisibility(View.VISIBLE);
          } else { h.tvCollabWith.setVisibility(View.GONE); }

          h.tvTime.setText(fmtTime(reel.timestamp));

          // [F11] Location
          if (reel.location != null && !reel.location.isEmpty()) {
              h.tvLocation.setText("  " + reel.location);
              h.tvLocation.setVisibility(View.VISIBLE);
          } else { h.tvLocation.setVisibility(View.GONE); }

          // [F19] Creator views chip
          if (myUid != null && myUid.equals(reel.ownerUid)) {
              h.tvCreatorViews.setText(fmt(reel.viewCount) + " views");
              h.tvCreatorViews.setVisibility(View.VISIBLE);
          } else { h.tvCreatorViews.setVisibility(View.GONE); }

          Glide.with(ctx).load(reel.thumbnailUrl != null ? reel.thumbnailUrl : reel.videoUrl)
              .placeholder(R.drawable.ic_reels).into(h.ivThumb);

          // [F3] Inline video autoplay
          boolean isVideo = reel.videoUrl != null && !reel.videoUrl.isEmpty();
          h.ivVideoBadge.setVisibility(isVideo ? View.VISIBLE : View.GONE);
          if (isVideo) setupInlinePlayer(h, pos, reel.videoUrl);

          // [F20] Seen count
          h.tvSeenCount.setVisibility(reel.viewCount > 0 ? View.VISIBLE : View.GONE);
          if (reel.viewCount > 0) h.tvSeenCount.setText(fmt(reel.viewCount));

          // Repost badge
          if (reel.repostedFromName != null && !reel.repostedFromName.isEmpty()) {
              h.tvRepostBy.setText("Reposted by " + reel.ownerName);
              h.layoutRepostBadge.setVisibility(View.VISIBLE);
          } else { h.layoutRepostBadge.setVisibility(View.GONE); }

          checkLikeState(h, reel);
          h.tvLikes.setText(fmt(reel.likeCount));
          h.tvComments.setText(fmt(reel.commentCount));
          h.tvShares.setText(fmt(reel.repostCount));

          setupMediaGestures(h, reel); // [F6] + [F7]
          setupCaption(h, reel);       // [F8] + [F9] + [F10]

          h.btnComment.setOnClickListener(v -> {
              Intent i = new Intent(ctx, ReelCommentActivity.class);
              i.putExtra("reelId", reel.reelId); ctx.startActivity(i);
          });
          h.btnSave.setOnClickListener(v -> showSaveSheet(reel)); // [F17]
          checkSaveState(h, reel);
          h.btnShare.setOnClickListener(v -> {
              Intent i = new Intent(ctx, ReelShareSheetActivity.class);
              i.putExtra("reelId", reel.reelId); i.putExtra("videoUrl", reel.videoUrl); ctx.startActivity(i);
          });
          h.btnMore.setOnClickListener(v -> { /* popup menu */ });
          h.ivThumb.setOnClickListener(v -> openReel(reel, pos));
      }

      // [F3] ExoPlayer
      private void setupInlinePlayer(FeedVH h, int pos, String url) {
          releasePlayer(pos, h.playerView);
          ExoPlayer player = new ExoPlayer.Builder(ctx).build();
          player.setMediaItem(MediaItem.fromUri(url));
          player.setVolume(0f);
          player.setRepeatMode(Player.REPEAT_MODE_ONE);
          player.prepare(); player.setPlayWhenReady(true);
          h.playerView.setPlayer(player);
          h.playerView.setVisibility(View.VISIBLE);
          h.ivVideoBadge.setVisibility(View.GONE);
          player.addListener(new Player.Listener() {
              @Override public void onPlaybackStateChanged(int state) {
                  if (!h.itemView.isAttachedToWindow()) return;
                  h.pbVideoBuffer.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                  if (state == Player.STATE_READY) {
                      h.ivThumb.setVisibility(View.GONE);
                      h.btnFeedMute.setVisibility(View.VISIBLE);
                  }
              }
          });
          h.btnFeedMute.setOnClickListener(v -> {
              boolean muted = player.getVolume() == 0f;
              player.setVolume(muted ? 1f : 0f);
              h.btnFeedMute.setImageResource(muted ? R.drawable.ic_volume_up : R.drawable.ic_volume_off);
          });
          playerMap.put(pos, player);
      }

      private void releasePlayer(int pos, PlayerView pv) {
          ExoPlayer old = playerMap.remove(pos);
          if (old != null) { old.stop(); old.release(); }
          if (pv != null) pv.setPlayer(null);
      }

      public void releaseAllPlayers() {
          for (ExoPlayer p : playerMap.values()) { p.stop(); p.release(); }
          playerMap.clear();
      }

      // [F6] Double-tap + [F7] Long-press
      private void setupMediaGestures(FeedVH h, ReelModel reel) {
          GestureDetector gd = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
              @Override public boolean onDoubleTap(MotionEvent e) { performLike(h, reel); showLikeAnim(h); return true; }
              @Override public boolean onSingleTapConfirmed(MotionEvent e) { openReel(reel, h.getAdapterPosition()); return true; }
          });
          h.frameMedia.setOnTouchListener((v, event) -> { gd.onTouchEvent(event); return true; });
          h.btnLike.setOnLongClickListener(v -> { showReactionSheet(reel, h); return true; });
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
                          reel.likeCount = Math.max(0, reel.likeCount - 1);
                          if (h.itemView.isAttachedToWindow()) {
                              h.btnLike.setImageResource(R.drawable.ic_heart);
                              h.btnLike.clearColorFilter();
                              h.tvLikes.setText(fmt(reel.likeCount));
                          }
                      } else {
                          FirebaseDatabase.getInstance().getReference(ref).setValue(true);
                          reel.likeCount++;
                          if (h.itemView.isAttachedToWindow()) {
                              h.btnLike.setImageResource(R.drawable.ic_heart_filled);
                              h.btnLike.setColorFilter(ContextCompat.getColor(ctx, R.color.brand_primary));
                              h.tvLikes.setText(fmt(reel.likeCount));
                          }
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void showLikeAnim(FeedVH h) {
          if (!h.itemView.isAttachedToWindow()) return;
          h.ivLikeAnim.setVisibility(View.VISIBLE);
          h.ivLikeAnim.setAlpha(0f); h.ivLikeAnim.setScaleX(0.5f); h.ivLikeAnim.setScaleY(0.5f);
          AnimatorSet set = new AnimatorSet();
          set.playTogether(
              ObjectAnimator.ofFloat(h.ivLikeAnim, "alpha",  0f,1f,1f,0f),
              ObjectAnimator.ofFloat(h.ivLikeAnim, "scaleX", 0.5f,1.4f,1.1f,1.4f),
              ObjectAnimator.ofFloat(h.ivLikeAnim, "scaleY", 0.5f,1.4f,1.1f,1.4f)
          );
          set.setDuration(700); set.start();
          new Handler(Looper.getMainLooper()).postDelayed(
              () -> { if (h.itemView.isAttachedToWindow()) h.ivLikeAnim.setVisibility(View.GONE); }, 750);
      }

      // [F7] Reaction sheet
      private void showReactionSheet(ReelModel reel, FeedVH h) {
          BottomSheetDialog d = new BottomSheetDialog(ctx, R.style.ThemeOverlay_App_BottomSheetDialog);
          View sheet = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_post_reactions, null);
          d.setContentView(sheet);
          int[] ids = { R.id.btn_react_heart, R.id.btn_react_laugh, R.id.btn_react_wow,
                        R.id.btn_react_sad,   R.id.btn_react_angry, R.id.btn_react_fire };
          for (int i = 0; i < ids.length; i++) {
              final String emoji = REACTION_EMOJIS[i];
              TextView btn = sheet.findViewById(ids[i]);
              if (btn == null) continue;
              btn.setOnClickListener(v -> {
                  if (myUid != null) {
                      Map<String,Object> m = new HashMap<>();
                      m.put("emoji", emoji); m.put("ts", System.currentTimeMillis());
                      FirebaseDatabase.getInstance().getReference("reelReactions/" + reel.reelId + "/" + myUid).setValue(m);
                  }
                  if (h.itemView.isAttachedToWindow()) {
                      h.layoutReactionsDisplay.setVisibility(View.VISIBLE);
                      h.tvReactionEmoji.setText(emoji); h.tvReactionCount.setText("1");
                  }
                  d.dismiss();
              });
          }
          d.show();
      }

      // [F8,F9,F10] Caption
      private void setupCaption(FeedVH h, ReelModel reel) {
          String caption = reel.caption != null ? reel.caption : "";
          if (caption.isEmpty()) { h.tvCaption.setVisibility(View.GONE); h.btnSeeMore.setVisibility(View.GONE); return; }
          h.tvCaption.setVisibility(View.VISIBLE);
          SpannableStringBuilder ssb = new SpannableStringBuilder(caption);
          int color = ContextCompat.getColor(ctx, R.color.brand_primary);

          // [F8] Hashtags
          Matcher hm = Pattern.compile("#(\\w+)").matcher(caption);
          while (hm.find()) {
              final String tag = hm.group(1);
              ssb.setSpan(new ClickableSpan() {
                  @Override public void onClick(@NonNull View v) {
                      Intent i = new Intent(ctx, HashtagReelsActivity.class); i.putExtra("hashtag", tag); ctx.startActivity(i);
                  }
                  @Override public void updateDrawState(@NonNull TextPaint ds) { ds.setColor(color); ds.setUnderlineText(false); }
              }, hm.start(), hm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          }

          // [F9] Mentions
          Matcher mm = Pattern.compile("@(\\w+)").matcher(caption);
          while (mm.find()) {
              final String uname = mm.group(1);
              ssb.setSpan(new ClickableSpan() {
                  @Override public void onClick(@NonNull View v) {
                      Intent i = new Intent(ctx, UserReelsActivity.class); i.putExtra("name", uname); ctx.startActivity(i);
                  }
                  @Override public void updateDrawState(@NonNull TextPaint ds) { ds.setColor(color); ds.setUnderlineText(false); }
              }, mm.start(), mm.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          }

          h.tvCaption.setText(ssb);
          h.tvCaption.setMovementMethod(LinkMovementMethod.getInstance());
          h.tvCaption.setHighlightColor(Color.TRANSPARENT);

          // [F10] See more / less
          h.tvCaption.setMaxLines(2);
          h.tvCaption.setEllipsize(TextUtils.TruncateAt.END);
          h.tvCaption.post(() -> {
              if (h.tvCaption.getLayout() != null && h.tvCaption.getLayout().getLineCount() > 2)
                  h.btnSeeMore.setVisibility(View.VISIBLE);
              else h.btnSeeMore.setVisibility(View.GONE);
          });
          final boolean[] exp = {false};
          h.btnSeeMore.setOnClickListener(v -> {
              exp[0] = !exp[0];
              if (exp[0]) { h.tvCaption.setMaxLines(Integer.MAX_VALUE); h.tvCaption.setEllipsize(null); h.btnSeeMore.setText("less"); }
              else { h.tvCaption.setMaxLines(2); h.tvCaption.setEllipsize(TextUtils.TruncateAt.END); h.btnSeeMore.setText("more"); }
          });
      }

      // [F17] Save sheet
      private void showSaveSheet(ReelModel reel) {
          BottomSheetDialog d = new BottomSheetDialog(ctx, R.style.ThemeOverlay_App_BottomSheetDialog);
          View sheet = LayoutInflater.from(ctx).inflate(R.layout.bottom_sheet_save_collection, null);
          d.setContentView(sheet);
          View btnQ = sheet.findViewById(R.id.btn_save_quick);
          View btnC = sheet.findViewById(R.id.btn_save_to_collection);
          if (btnQ != null) btnQ.setOnClickListener(v -> {
              if (myUid != null) {
                  FirebaseDatabase.getInstance().getReference("reelSaves/" + reel.reelId + "/" + myUid).setValue(true);
                  FirebaseDatabase.getInstance().getReference("userSaves/" + myUid + "/" + reel.reelId).setValue(true);
              }
              d.dismiss();
          });
          if (btnC != null) btnC.setOnClickListener(v -> {
              Intent i = new Intent(ctx, ReelBookmarkCollectionsActivity.class);
              i.putExtra("reelId", reel.reelId); ctx.startActivity(i); d.dismiss();
          });
          d.show();
      }

      private void checkSaveState(FeedVH h, ReelModel reel) {
          if (myUid == null) return;
          FirebaseDatabase.getInstance().getReference("reelSaves/" + reel.reelId + "/" + myUid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      if (snap.exists() && h.itemView.isAttachedToWindow()) {
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
                      if (snap.exists() && h.itemView.isAttachedToWindow()) {
                          h.btnLike.setImageResource(R.drawable.ic_heart_filled);
                          h.btnLike.setColorFilter(ContextCompat.getColor(ctx, R.color.brand_primary));
                      }
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {}
              });
      }

      private void openReel(ReelModel reel, int pos) {
          Intent i = new Intent(ctx, SingleReelPlayerActivity.class); i.putExtra("reelId", reel.reelId); ctx.startActivity(i);
      }
      private void openUserProfile(ReelModel reel) {
          Intent i = new Intent(ctx, UserReelsActivity.class); i.putExtra("uid", reel.ownerUid); i.putExtra("name", reel.ownerName); ctx.startActivity(i);
      }

      private String fmt(long n) {
          if (n < 1000) return String.valueOf(n);
          if (n < 1_000_000) return String.format("%.1fK", n / 1000.0);
          return String.format("%.1fM", n / 1_000_000.0);
      }
      private String fmtTime(long ts) {
          long s = (System.currentTimeMillis() - ts) / 1000;
          if (s < 60) return "just now";
          long m = s / 60; if (m < 60) return m + "m ago";
          long h = m / 60; if (h < 24) return h + "h ago";
          return (h / 24) + "d ago";
      }

      // ── ViewHolders ───────────────────────────────────────────────────────────
      static class SkeletonVH extends RecyclerView.ViewHolder { SkeletonVH(@NonNull View v){super(v);} }

      static class FeedVH extends RecyclerView.ViewHolder {
          CircleImageView ivAvatar; TextView badgeCloseFriends,tvOwner,tvCollabWith,tvTime,tvLocation,tvCreatorViews;
          ImageButton btnMore; View frameMedia; ImageView ivThumb,btnFeedMute,ivVideoBadge,ivLikeAnim;
          PlayerView playerView; ProgressBar pbVideoBuffer; TextView tvSeenCount;
          View layoutRepostBadge; TextView tvRepostBy;
          ImageButton btnLike,btnComment,btnShare,btnSave;
          TextView tvLikes,tvComments,tvShares;
          LinearLayout layoutReactionsDisplay; TextView tvReactionEmoji,tvReactionCount;
          TextView tvCaption,btnSeeMore;
          FeedVH(@NonNull View v) {
              super(v);
              ivAvatar=v.findViewById(R.id.iv_post_avatar); badgeCloseFriends=v.findViewById(R.id.badge_close_friends);
              tvOwner=v.findViewById(R.id.tv_post_owner); tvCollabWith=v.findViewById(R.id.tv_collab_with);
              tvTime=v.findViewById(R.id.tv_post_time); tvLocation=v.findViewById(R.id.tv_post_location);
              tvCreatorViews=v.findViewById(R.id.tv_creator_views); btnMore=v.findViewById(R.id.btn_post_more);
              frameMedia=v.findViewById(R.id.frame_media); ivThumb=v.findViewById(R.id.iv_post_thumb);
              playerView=v.findViewById(R.id.player_view_feed); btnFeedMute=v.findViewById(R.id.btn_feed_mute);
              pbVideoBuffer=v.findViewById(R.id.pb_video_buffer); ivVideoBadge=v.findViewById(R.id.iv_video_badge);
              ivLikeAnim=v.findViewById(R.id.iv_like_anim); tvSeenCount=v.findViewById(R.id.tv_seen_count);
              layoutRepostBadge=v.findViewById(R.id.layout_repost_badge); tvRepostBy=v.findViewById(R.id.tv_repost_by);
              btnLike=v.findViewById(R.id.btn_post_like); tvLikes=v.findViewById(R.id.tv_post_likes);
              btnComment=v.findViewById(R.id.btn_post_comment); tvComments=v.findViewById(R.id.tv_post_comments);
              btnShare=v.findViewById(R.id.btn_post_share); tvShares=v.findViewById(R.id.tv_post_shares);
              btnSave=v.findViewById(R.id.btn_post_save);
              layoutReactionsDisplay=v.findViewById(R.id.layout_reactions_display);
              tvReactionEmoji=v.findViewById(R.id.tv_reaction_emoji); tvReactionCount=v.findViewById(R.id.tv_reaction_count);
              tvCaption=v.findViewById(R.id.tv_post_caption); btnSeeMore=v.findViewById(R.id.btn_see_more_caption);
          }
      }
  }