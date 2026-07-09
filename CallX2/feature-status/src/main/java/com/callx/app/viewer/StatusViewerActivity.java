package com.callx.app.viewer;
  import android.content.Intent;
  import android.graphics.Color;
  import android.net.Uri;
  import android.os.Bundle;
  import android.os.Handler;
  import android.os.Looper;
  import android.text.TextUtils;
  import android.view.*;
  import android.view.animation.*;
  import android.widget.*;
  import androidx.annotation.NonNull;
  import androidx.annotation.Nullable;
  import androidx.annotation.OptIn;
  import androidx.appcompat.app.AlertDialog;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.media3.common.*;
  import androidx.media3.common.util.UnstableApi;
  import androidx.media3.datasource.cache.CacheDataSource;
  import androidx.media3.exoplayer.ExoPlayer;
  import androidx.media3.exoplayer.source.ProgressiveMediaSource;
  import com.bumptech.glide.Glide;
  import com.callx.app.cache.StatusVideoCacheManager;
  import com.callx.app.status.R;
  import com.callx.app.status.databinding.ActivityStatusViewerBinding;
  import com.callx.app.models.StatusItem;
  import com.callx.app.utils.*;
  import com.google.android.material.bottomsheet.BottomSheetDialog;
  import com.google.firebase.database.*;
  import java.util.*;
  import com.callx.app.highlights.StatusAddToHighlightBottomSheet;
  import com.callx.app.analytics.StatusAnalyticsBottomSheet;
  import com.callx.app.interactions.StatusDeleteConfirmBottomSheet;
  import com.callx.app.interactions.StatusForwardBottomSheet;
  import com.callx.app.interactions.StatusReactionBottomSheet;
  import com.callx.app.interactions.StatusReplyBottomSheet;
  import com.callx.app.interactions.StatusSeenByBottomSheet;
  import com.callx.app.utils.StatusCloseFriendsManager;
  import com.callx.app.utils.StatusDownloadHelper;
  import com.callx.app.utils.StatusHighlightManager;
  import com.callx.app.utils.StatusMentionHelper;
  import com.callx.app.utils.StatusMuteManager;
  import com.callx.app.utils.StatusSeenTracker;
  /**
   * StatusViewerActivity v26 — Fully comprehensive story/status viewer.
   *
   * FIXES v26:
   *   FIX: setupDownloadButton() — was findViewWithTag("btn_download") (no tag in XML → null), now binding.btnDownload
   *   FIX: setupForwardButton() — was findViewWithTag("btn_forward") (no tag in XML → null), now binding.btnForward
   *   FIX: hideAllContent() — was findViewWithTag("tv_location_tag") → null, now binding.tvLocationTag
   *   FIX: showLocationTag() — was findViewWithTag("tv_location_tag") → null, now binding.tvLocationTag
   *   FIX: updateExpiryLabel() — was findViewWithTag("tv_expiry_label") → null, now binding.tvExpiryLabel
   *   FIX: showOwnerMoreMenu() — added "Who viewed this" option (was completely missing)
   *   FIX: btn_download and btn_forward made visible for viewer (were always GONE, no code showed them)
   *
   * ORIGINAL (fully working):
   *   Multi-segment progress bar, tap/hold gestures, ExoPlayer cache, DiskCacheStrategy,
   *   Text/image/video/gif/link status types, Reply, Reactions, Mute, Seen tracking,
   *   Analytics, Highlights, Delete, Archive, Cross-fade, Keep screen ON.
   */
  public class StatusViewerActivity extends AppCompatActivity {
      public static final String EXTRA_OWNER_UID  = "ownerUid";
      public static final String EXTRA_OWNER_NAME = "ownerName";
      private ActivityStatusViewerBinding binding;
      private final List<StatusItem> items         = new ArrayList<>();
      private final List<String>     seenInSession  = new ArrayList<>();
      private int     idx         = 0;
      private ExoPlayer player;
      private final Handler  handler      = new Handler(Looper.getMainLooper());
      private Runnable       progressRunner;
      private boolean        paused       = false;
      private long           remainingMs  = 0;
      private boolean        isMuted      = false;
      private long           viewStartTime = 0;
      private String myUid, ownerUid, ownerName;
      private final List<ProgressBar> segmentBars = new ArrayList<>();
      private GestureDetector swipeDetector;
      // ── Lifecycle ─────────────────────────────────────────────────────────
      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          binding = ActivityStatusViewerBinding.inflate(getLayoutInflater());
          setContentView(binding.getRoot());
          getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          getWindow().getDecorView().setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
          ownerUid  = getIntent().getStringExtra(EXTRA_OWNER_UID);
          ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
          if (ownerUid == null) { finish(); return; }
          try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { myUid = null; }
          setupSwipeDownGesture();
          setupTouchZones();
          setupCloseButton();
          setupReactionButton();
          setupReplyButton();
          setupMoreButton();
          setupMuteButton();
          setupDownloadButton();
          setupForwardButton();
          binding.tvOwner.setText(ownerName != null ? ownerName : "Status");
          if (StatusCloseFriendsManager.isCloseFriend(this, ownerUid))
              binding.tvOwner.setText("\u2B50 " + (ownerName != null ? ownerName : "Status"));
          load(ownerUid);
      }
      @Override protected void onPause()  { super.onPause();  pauseProgress(); }
      @Override protected void onResume() { super.onResume(); if (paused) resumeProgress(); }
      @Override
      protected void onDestroy() {
          releasePlayer();
          stopProgress();
          handler.removeCallbacksAndMessages(null);
          getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          if (!seenInSession.isEmpty() && ownerUid != null) {
              String thumbForBubble = "";
              if (!items.isEmpty()) {
                  StatusItem first = items.get(0);
                  if (first.thumbnailUrl != null && !first.thumbnailUrl.isEmpty())
                      thumbForBubble = first.thumbnailUrl;
                  else if (first.mediaUrl != null && "image".equals(first.type))
                      thumbForBubble = first.mediaUrl;
              }
              StatusSeenTracker.markSeenBatch(ownerUid, seenInSession,
                      ownerName != null ? ownerName : "", thumbForBubble);
          }
          if (viewStartTime > 0 && idx < items.size()) {
              StatusItem cur = items.get(idx);
              if (cur.id != null)
                  StatusSeenTracker.recordViewDuration(ownerUid, cur.id,
                          System.currentTimeMillis() - viewStartTime);
          }
          super.onDestroy();
      }
      // ── Load ──────────────────────────────────────────────────────────────
      private void load(String uid) {
          FirebaseUtils.getStatusRef().child(uid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      long now = System.currentTimeMillis();
                      for (DataSnapshot c : snap.getChildren()) {
                          StatusItem s = c.getValue(StatusItem.class);
                          if (s == null || s.deleted) continue;
                          if (s.expiresAt != null && s.expiresAt < now) continue;
                          items.add(s);
                      }
                      if (items.isEmpty()) {
                          // WhatsApp-style: reached here via a "replied to
                          // status" quote-box tap (or a stale status link)
                          // after the status already expired/was deleted —
                          // tell the user why instead of silently closing.
                          Toast.makeText(StatusViewerActivity.this,
                                  "This status is no longer available", Toast.LENGTH_SHORT).show();
                          finish(); return;
                      }
                      items.sort((a, b) -> Long.compare(
                              a.timestamp == null ? 0 : a.timestamp,
                              b.timestamp == null ? 0 : b.timestamp));
                      StatusItem first = items.get(0);
                      if (first.ownerPhoto != null && !first.ownerPhoto.isEmpty())
                          Glide.with(StatusViewerActivity.this).load(first.ownerPhoto)
                               .circleCrop().into(binding.ivOwner);
                      buildSegmentBars();
                      showCurrent();
                      // FIX: show download+forward for viewer, hide for owner
                      boolean isOwner = myUid != null && myUid.equals(ownerUid);
                      binding.btnDownload.setVisibility(isOwner ? View.GONE : View.VISIBLE);
                      binding.btnForward.setVisibility(isOwner ? View.GONE : View.VISIBLE);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) { finish(); }
              });
      }
      // ── Segment bar ───────────────────────────────────────────────────────
      private void buildSegmentBars() {
          binding.segmentsContainer.removeAllViews();
          segmentBars.clear();
          int count = items.size();
          for (int i = 0; i < count; i++) {
              ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
              pb.setMax(1000);
              pb.setProgress(0);
              LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dpToPx(2), 1f);
              lp.setMarginEnd(i < count - 1 ? dpToPx(3) : 0);
              pb.setLayoutParams(lp);
              pb.getProgressDrawable().setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN);
              binding.segmentsContainer.addView(pb);
              segmentBars.add(pb);
          }
      }
      private void fillSegmentsBefore(int currentIdx) {
          for (int i = 0; i < segmentBars.size(); i++)
              segmentBars.get(i).setProgress(i < currentIdx ? 1000 : 0);
      }
      // ── Show current item ─────────────────────────────────────────────────
      private void showCurrent() {
          if (idx >= items.size()) { finish(); return; }
          StatusItem s = items.get(idx);
          if (viewStartTime > 0 && idx > 0) {
              StatusItem prev = items.get(idx - 1);
              if (prev.id != null)
                  StatusSeenTracker.recordViewDuration(ownerUid, prev.id,
                          System.currentTimeMillis() - viewStartTime);
          }
          viewStartTime = System.currentTimeMillis();
          fillSegmentsBefore(idx);
          updateHeaderTimestamp(s);
          updateSeenByInfo(s);
          updateExpiryLabel(s);
          crossFadeIn();
          if (s.id != null && !s.id.isEmpty() && myUid != null && !myUid.equals(ownerUid))
              if (!seenInSession.contains(s.id)) seenInSession.add(s.id);
          binding.btnMute.setVisibility(View.GONE);
          hideAllContent();
          switch (s.type != null ? s.type : "") {
              case "text":
                  showTextStatus(s); break;
              case "image":
                  if (s.mediaUrl != null) showImageStatusFromUrl(s.mediaUrl, s.caption); break;
              case "video": case "reel_story": case "reel_clip":
                  if (s.mediaUrl != null) { showVideoStatus(s); break; }
                  if (s.thumbnailUrl != null) { showImageStatusFromUrl(s.thumbnailUrl, s.caption); break; }
                  next(); break;
              case "link":  showLinkStatus(s); break;
              case "gif": case "sticker": showGifStatus(s); break;
              default: next();
          }
      }
      // ── Content renderers ─────────────────────────────────────────────────
      private void showTextStatus(StatusItem s) {
          binding.flTextStatus.setVisibility(View.VISIBLE);
          binding.tvTextStatus.setText(StatusMentionHelper.highlight(s.text != null ? s.text : ""));
          try {
              if (s.bgColor != null) binding.flTextStatus.setBackgroundColor(Color.parseColor(s.bgColor));
              else binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
          } catch (Exception e) {
              binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
          }
          if (s.textColor != null) {
              try { binding.tvTextStatus.setTextColor(Color.parseColor(s.textColor)); }
              catch (Exception ignored) {}
          }
          applyFontStyle(binding.tvTextStatus, s.fontStyle);
          if (s.textSize > 0) binding.tvTextStatus.setTextSize(s.textSize);
          if (s.textAlign != null) {
              switch (s.textAlign) {
                  case "left":  binding.tvTextStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); break;
                  case "right": binding.tvTextStatus.setGravity(Gravity.END   | Gravity.CENTER_VERTICAL); break;
                  default:      binding.tvTextStatus.setGravity(Gravity.CENTER);
              }
          }
          if (s.locationName != null && !s.locationName.isEmpty()) showLocationTag(s.locationName);
          showCaption(s.caption);
          startProgress(5_000L);
      }
      private void showImageStatusFromUrl(String url, String caption) {
          binding.ivStatus.setVisibility(View.VISIBLE);
          Glide.with(this).load(url)
               .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
               .placeholder(android.R.drawable.screen_background_dark)
               .into(binding.ivStatus);
          showCaption(caption);
          startProgress(5_000L);
      }
      private void showLinkStatus(StatusItem s) {
          if (s.linkImageUrl != null && !s.linkImageUrl.isEmpty())
              showImageStatusFromUrl(s.linkImageUrl, s.linkTitle);
          else showTextStatus(s);
          if (s.linkUrl != null) {
              binding.tvCaption.setClickable(true);
              binding.tvCaption.setOnClickListener(v -> {
                  pauseProgress();
                  startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(s.linkUrl)));
              });
          }
      }
      private void showGifStatus(StatusItem s) {
          binding.ivStatus.setVisibility(View.VISIBLE);
          String url = s.gifUrl != null ? s.gifUrl : s.stickerUrl != null ? s.stickerUrl : s.mediaUrl;
          if (url != null)
              Glide.with(this).asGif().load(url)
                   .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                   .placeholder(android.R.drawable.screen_background_dark)
                   .into(binding.ivStatus);
          showCaption(s.caption);
          startProgress(4_000L);
      }
      @OptIn(markerClass = UnstableApi.class)
      private void showVideoStatus(StatusItem s) {
          binding.playerView.setVisibility(View.VISIBLE);
          binding.btnMute.setVisibility(View.VISIBLE);
          releasePlayer();
          ExoPlayer.Builder builder = new ExoPlayer.Builder(this);
          if (StatusVideoCacheManager.isInitialized()) {
              CacheDataSource.Factory cf = StatusVideoCacheManager.getCacheDataSourceFactory();
              ProgressiveMediaSource ms = new ProgressiveMediaSource.Factory(cf)
                      .createMediaSource(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
              player = builder.build();
              binding.playerView.setPlayer(player);
              player.setMediaSource(ms);
          } else {
              player = builder.build();
              binding.playerView.setPlayer(player);
              player.setMediaItem(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
          }
          player.setVolume(isMuted ? 0f : 1f);
          long estimated = s.durationSec > 0 ? Math.min(s.durationSec * 1000L, 30_000L) : 15_000L;
          player.addListener(new Player.Listener() {
              @Override public void onPlaybackStateChanged(int state) {
                  if (state == Player.STATE_READY) {
                      long real = player.getDuration();
                      long dur = (real > 0 && real != Long.MIN_VALUE) ? Math.min(real, 30_000L) : estimated;
                      stopProgress(); startProgress(dur);
                  } else if (state == Player.STATE_ENDED) { next(); }
              }
          });
          player.prepare(); player.setPlayWhenReady(true);
          startProgress(estimated);
          showCaption(s.caption);
      }
      private void hideAllContent() {
          binding.flTextStatus.setVisibility(View.GONE);
          binding.ivStatus.setVisibility(View.GONE);
          binding.playerView.setVisibility(View.GONE);
          binding.tvCaption.setVisibility(View.GONE);
          // FIX: was findViewWithTag("tv_location_tag") — always null — now binding ref
          binding.tvLocationTag.setVisibility(View.GONE);
      }
      private void showCaption(String caption) {
          if (!TextUtils.isEmpty(caption)) {
              binding.tvCaption.setVisibility(View.VISIBLE);
              binding.tvCaption.setText(StatusMentionHelper.highlight(caption));
          }
      }
      // FIX: was findViewWithTag("tv_location_tag") → null, now binding.tvLocationTag
      private void showLocationTag(String location) {
          binding.tvLocationTag.setText("\uD83D\uDCCD " + location);
          binding.tvLocationTag.setVisibility(View.VISIBLE);
      }
      // ── Progress ──────────────────────────────────────────────────────────
      private void startProgress(long durationMs) {
          stopProgress(); paused = false; remainingMs = durationMs;
          runProgressTick(durationMs, durationMs);
      }
      private void runProgressTick(final long totalMs, final long remaining) {
          final long STEP = 50L;
          progressRunner = new Runnable() {
              long elapsed = totalMs - remaining;
              @Override public void run() {
                  if (paused) return;
                  elapsed += STEP;
                  int prog = (int) Math.min(1000L, (elapsed * 1000L) / totalMs);
                  if (idx < segmentBars.size()) segmentBars.get(idx).setProgress(prog);
                  if (elapsed >= totalMs) { next(); }
                  else { remainingMs = totalMs - elapsed; handler.postDelayed(this, STEP); }
              }
          };
          handler.postDelayed(progressRunner, STEP);
      }
      private void stopProgress() {
          if (progressRunner != null) { handler.removeCallbacks(progressRunner); progressRunner = null; }
      }
      private void pauseProgress() {
          if (paused) return; paused = true;
          if (player != null) player.setPlayWhenReady(false);
          stopProgress();
      }
      private void resumeProgress() {
          if (!paused) return; paused = false;
          if (player != null) player.setPlayWhenReady(true);
          if (idx < items.size()) {
              StatusItem s = items.get(idx);
              long total;
              if ("video".equals(s.type) || "reel_story".equals(s.type) || "reel_clip".equals(s.type)) {
                  long real = (player != null) ? player.getDuration() : Long.MIN_VALUE;
                  total = (real > 0 && real != Long.MIN_VALUE) ? Math.min(real, 30_000L)
                          : (s.durationSec > 0 ? Math.min(s.durationSec * 1000L, 30_000L) : 15_000L);
              } else total = 5_000L;
              runProgressTick(total, remainingMs);
          }
      }
      // ── Navigation ────────────────────────────────────────────────────────
      private void next()     { releasePlayer(); stopProgress(); idx++; showCurrent(); }
      private void previous() { releasePlayer(); stopProgress(); idx = Math.max(0, idx - 1); showCurrent(); }
      // ── Swipe down ────────────────────────────────────────────────────────
      private void setupSwipeDownGesture() {
          swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
              @Override public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float vx, float vy) {
                  if (e1 == null) return false;
                  float dy = e2.getRawY() - e1.getRawY();
                  if (dy > 120 && Math.abs(vy) > 100) { finishWithAnimation(); return true; }
                  return false;
              }
          });
      }
      private void finishWithAnimation() {
          AlphaAnimation fade = new AlphaAnimation(1f, 0f);
          fade.setDuration(200);
          fade.setAnimationListener(new Animation.AnimationListener() {
              @Override public void onAnimationStart(Animation a) {}
              @Override public void onAnimationRepeat(Animation a) {}
              @Override public void onAnimationEnd(Animation a) { finish(); }
          });
          binding.getRoot().startAnimation(fade);
      }
      private void crossFadeIn() {
          binding.getRoot().setAlpha(0f);
          binding.getRoot().animate().alpha(1f).setDuration(150).start();
      }
      // ── Touch zones ───────────────────────────────────────────────────────
      private void setupTouchZones() {
          binding.touchLayer.setOnTouchListener((v, e) -> {
              swipeDetector.onTouchEvent(e);
              switch (e.getAction()) {
                  case MotionEvent.ACTION_DOWN: pauseProgress(); break;
                  case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                      resumeProgress();
                      float x = e.getX(), w = v.getWidth();
                      if (e.getEventTime() - e.getDownTime() < 200) {
                          if (x < w / 3f) previous(); else next();
                      }
                      break;
              }
              return true;
          });
      }
      // ── Buttons ───────────────────────────────────────────────────────────
      private void setupCloseButton() {
          binding.btnCloseStatus.setOnClickListener(v -> finishWithAnimation());
      }
      private void setupReactionButton() {
          binding.btnReact.setOnClickListener(v -> {
              if (myUid != null && myUid.equals(ownerUid)) return;
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null) return;
              pauseProgress();
              StatusReactionBottomSheet.show(this, current, myUid, (emoji, removed) -> {
                  if (removed) { if (current.reactions != null) current.reactions.remove(myUid); }
                  else { if (current.reactions == null) current.reactions = new HashMap<>(); current.reactions.put(myUid, emoji); }
                  // WhatsApp-style: an emoji reaction to a status previously
                  // only wrote to statuses/{ownerUid}/{id}/reactions — never
                  // touched the 1:1 chat at all, so neither side ever saw
                  // "Reacted 😂 to your status" show up as a chat bubble.
                  // Only fire on an actual new reaction, not on toggle-off.
                  if (!removed) sendReactionToChat(current, emoji);
                  updateSeenByInfo(current);
                  resumeProgress();
              });
          });
      }
      private void setupReplyButton() {
          if (myUid != null && myUid.equals(ownerUid)) {
              binding.etReply.setVisibility(View.GONE);
              binding.btnSendReply.setVisibility(View.GONE);
              return;
          }
          binding.etReply.setOnClickListener(v -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null || myUid == null) return;
              pauseProgress();
              StatusReplyBottomSheet.show(this, current, ownerName, myUid, ownerUid, msg -> resumeProgress());
          });
          binding.etReply.setOnFocusChangeListener((v, has) -> { if (has) pauseProgress(); else resumeProgress(); });
          binding.btnSendReply.setOnClickListener(v -> {
              String msg = binding.etReply.getText() != null
                      ? binding.etReply.getText().toString().trim() : "";
              if (TextUtils.isEmpty(msg)) return;
              if (myUid == null || ownerUid == null) return;
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              sendReplyToChat(ownerUid, msg, current);
              binding.etReply.setText(""); binding.etReply.clearFocus();
              resumeProgress();
              Toast.makeText(this, "Reply sent", Toast.LENGTH_SHORT).show();
          });
      }
      /** FIX v26: was findViewWithTag("btn_download") — tag not in XML → always null → click never registered */
      private void setupDownloadButton() {
          binding.btnDownload.setOnClickListener(v -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null) return;
              if (!StatusDownloadHelper.hasPermission(this)) {
                  StatusDownloadHelper.requestPermission(this); return;
              }
              StatusDownloadHelper.downloadStatus(this, current);
          });
      }
      /** FIX v26: was findViewWithTag("btn_forward") — tag not in XML → always null → click never registered */
      private void setupForwardButton() {
          binding.btnForward.setOnClickListener(v -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null || myUid == null) return;
              pauseProgress();
              StatusForwardBottomSheet.show(this, current, myUid, this::resumeProgress);
          });
      }
      private void setupMoreButton() {
          binding.btnMore.setOnClickListener(v -> {
              pauseProgress();
              boolean isOwner = myUid != null && myUid.equals(ownerUid);
              if (isOwner) showOwnerMoreMenu(); else showViewerMoreMenu();
          });
      }
      /** FIX v26: "Who viewed this" added as first option (was completely missing from owner menu) */
      private void showOwnerMoreMenu() {
          StatusItem current = idx < items.size() ? items.get(idx) : null;
          String[] opts = {"Who viewed this", "Delete this status", "Archive status", "Add to Highlights", "Analytics", "Cancel"};
          new AlertDialog.Builder(this)
              .setItems(opts, (d, w) -> {
                  if (w == 0) {
                      // FIX: "Who viewed this" — open SeenByBottomSheet directly from menu
                      if (current != null) {
                          StatusSeenByBottomSheet.show(this, current, this::resumeProgress);
                      } else resumeProgress();
                  } else if (w == 1 && current != null && current.id != null) {
                      String previewUrl = current.thumbnailUrl != null ? current.thumbnailUrl : current.mediaUrl;
                      StatusDeleteConfirmBottomSheet.show(this, current.type, previewUrl, () -> {
                          StatusSeenTracker.deleteStatus(ownerUid, current.id);
                          items.remove(idx);
                          Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
                          if (items.isEmpty()) { finish(); return; }
                          idx = Math.min(idx, items.size() - 1);
                          buildSegmentBars(); stopProgress(); showCurrent();
                      });
                  } else if (w == 2 && current != null) {
                      StatusHighlightManager.archiveStatus(ownerUid, current);
                      Toast.makeText(this, "Archived \u2713", Toast.LENGTH_SHORT).show();
                      resumeProgress();
                  } else if (w == 3 && current != null) {
                      showAddToHighlightBottomSheet(current);
                  } else if (w == 4 && current != null) {
                      showAnalyticsBottomSheet(current);
                  } else {
                      resumeProgress();
                  }
              })
              .setOnCancelListener(d -> resumeProgress())
              .show();
      }
      private void showViewerMoreMenu() {
          String muteLabel = StatusMuteManager.isMuted(this, ownerUid)
                  ? "Unmute " + ownerName : "Mute " + ownerName;
          String[] opts = {muteLabel, "Download", "Forward", "Report", "Cancel"};
          new AlertDialog.Builder(this)
              .setItems(opts, (d, w) -> {
                  if (w == 0) {
                      StatusMuteManager.toggle(this, ownerUid);
                      String msg = StatusMuteManager.isMuted(this, ownerUid)
                              ? ownerName + " muted" : ownerName + " unmuted";
                      Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                      finish();
                  } else if (w == 1) {
                      StatusItem cur = idx < items.size() ? items.get(idx) : null;
                      if (cur != null) StatusDownloadHelper.downloadStatus(this, cur);
                  } else if (w == 2) {
                      StatusItem cur = idx < items.size() ? items.get(idx) : null;
                      if (cur != null && myUid != null) StatusForwardBottomSheet.show(this, cur, myUid);
                  } else if (w == 3) {
                      Toast.makeText(this, "Reported", Toast.LENGTH_SHORT).show();
                  }
                  resumeProgress();
              })
              .setOnCancelListener(d -> resumeProgress())
              .show();
      }
      private void showAddToHighlightBottomSheet(StatusItem item) {
          pauseProgress();
          StatusAddToHighlightBottomSheet.show(this, ownerUid, item, albumName -> {
              Toast.makeText(this, "Added to " + albumName + " \u2713", Toast.LENGTH_SHORT).show();
              resumeProgress();
          });
      }
      private void showAnalyticsBottomSheet(StatusItem item) {
          pauseProgress();
          StatusAnalyticsBottomSheet.show(this, item, this::resumeProgress);
      }
      private void setupMuteButton() {
          binding.btnMute.setOnClickListener(v -> {
              isMuted = !isMuted;
              if (player != null) player.setVolume(isMuted ? 0f : 1f);
              binding.btnMute.setImageResource(isMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
          });
      }
      // ── Seen-by info ──────────────────────────────────────────────────────
      private void updateSeenByInfo(StatusItem s) {
          if (myUid != null && myUid.equals(ownerUid)) {
              int count = s.getViewCount();
              binding.tvSeenBy.setVisibility(View.VISIBLE);
              String reactionSummary = buildReactionSummary(s);
              binding.tvSeenBy.setText("\uD83D\uDC41 " + count + (reactionSummary.isEmpty() ? "" : "  " + reactionSummary));
              binding.tvSeenBy.setOnClickListener(v -> {
                  pauseProgress();
                  StatusSeenByBottomSheet.show(this, s, this::resumeProgress);
              });
          } else {
              binding.tvSeenBy.setVisibility(View.GONE);
              if (s.hasReaction(myUid)) {
                  String myReaction = s.getReaction(myUid);
                  binding.btnReact.setContentDescription("React (" + myReaction + ")");
              }
          }
      }
      private String buildReactionSummary(StatusItem s) {
          if (s.reactions == null || s.reactions.isEmpty()) return "";
          Map<String, Integer> counts = new LinkedHashMap<>();
          for (String e : s.reactions.values()) counts.merge(e, 1, Integer::sum);
          StringBuilder sb = new StringBuilder();
          for (Map.Entry<String, Integer> e : counts.entrySet())
              sb.append(e.getKey()).append(e.getValue() > 1 ? "\u00D7" + e.getValue() : "").append(" ");
          return sb.toString().trim();
      }
      // ── Header helpers ────────────────────────────────────────────────────
      private void updateHeaderTimestamp(StatusItem s) {
          if (s.timestamp != null) binding.tvTimestamp.setText(formatAgo(System.currentTimeMillis() - s.timestamp));
          else binding.tvTimestamp.setText("");
      }
      /** FIX v26: was findViewWithTag("tv_expiry_label") → null, now binding.tvExpiryLabel */
      private void updateExpiryLabel(StatusItem s) {
          if (s.expiresAt != null) {
              long diffMs = s.expiresAt - System.currentTimeMillis();
              if (diffMs > 0) {
                  long hoursLeft = diffMs / 3_600_000L;
                  String label = hoursLeft < 1 ? "Expires <1h" : "Expires in " + hoursLeft + "h";
                  binding.tvExpiryLabel.setText(label);
                  binding.tvExpiryLabel.setVisibility(View.VISIBLE);
              } else {
                  binding.tvExpiryLabel.setVisibility(View.GONE);
              }
          } else {
              binding.tvExpiryLabel.setVisibility(View.GONE);
          }
      }
      // ── Utilities ─────────────────────────────────────────────────────────
      private String formatAgo(long ms) {
          if (ms < 60_000) return "just now";
          if (ms < 3_600_000) return (ms / 60_000) + "m ago";
          if (ms < 86_400_000) return (ms / 3_600_000) + "h ago";
          return (ms / 86_400_000) + "d ago";
      }
      private void applyFontStyle(TextView tv, String style) {
          if (style == null) return;
          switch (style) {
              case "bold":        tv.setTypeface(null, android.graphics.Typeface.BOLD); break;
              case "italic":      tv.setTypeface(null, android.graphics.Typeface.ITALIC); break;
              case "bold_italic": tv.setTypeface(null, android.graphics.Typeface.BOLD_ITALIC); break;
              default:            tv.setTypeface(null, android.graphics.Typeface.NORMAL);
          }
      }
      private void sendReplyToChat(String toUid, String msg, @Nullable StatusItem repliedStatus) {
          String chatId = FirebaseUtils.getChatId(myUid, toUid);
          String msgId = FirebaseUtils.getMessagesRef(chatId).push().getKey();
          if (msgId == null) return;
          Map<String, Object> data = new HashMap<>();
          data.put("id",        msgId);
          // BUG FIX: this used to write key "sender" instead of "senderId".
          // Message.java's Firebase POJO mapping only knows "senderId", so
          // the old key silently deserialized to null on every device that
          // read this message back — breaking sent/received attribution
          // for the quick inline reply (the full StatusReplyBottomSheet
          // path already used the right key).
          data.put("senderId",  myUid);
          data.put("text",      msg);
          data.put("type",      "text");
          data.put("timestamp", ServerValue.TIMESTAMP);
          data.put("seen",      false);
          // Quoted "replying to status" box — same fields the full
          // StatusReplyBottomSheet sets, so the quick inline reply shows
          // the same WhatsApp-style quote box instead of a bare text bubble.
          if (repliedStatus != null) {
              data.put("replyToType",       repliedStatus.type != null ? repliedStatus.type : "text");
              data.put("replyToText",       StatusReplyBottomSheet.getPreviewText(repliedStatus));
              data.put("replyToSenderName", ownerName != null ? ownerName : "Status");
              data.put("replyToId",         "status_" + (repliedStatus.id != null ? repliedStatus.id : "unknown"));
              String thumb = repliedStatus.thumbnailUrl != null ? repliedStatus.thumbnailUrl
                      : ("image".equals(repliedStatus.type) ? repliedStatus.mediaUrl : null);
              if (thumb != null) data.put("replyToMediaUrl", thumb);
          }
          FirebaseUtils.getMessagesRef(chatId).child(msgId).setValue(data);
      }

      /**
       * WhatsApp-style "Reacted 😂 to your status" chat bubble. Sent as a
       * normal text message (text = emoji) carrying the same replyTo*
       * quoted-status fields a status reply uses, so it renders through the
       * existing reply-quote-box + reaction-badge bubble UI and the
       * status_-prefixed tap-to-reopen-status handling — no new bubble
       * type needed.
       */
      private void sendReactionToChat(StatusItem reactedStatus, String emoji) {
          if (myUid == null || ownerUid == null || reactedStatus == null) return;
          String chatId = FirebaseUtils.getChatId(myUid, ownerUid);
          String msgId = FirebaseUtils.getMessagesRef(chatId).push().getKey();
          if (msgId == null) return;
          Map<String, Object> data = new HashMap<>();
          data.put("id",                  msgId);
          data.put("senderId",            myUid);
          data.put("text",                emoji);
          data.put("type",                "text");
          data.put("timestamp",           ServerValue.TIMESTAMP);
          data.put("seen",                false);
          data.put("replyToType",         reactedStatus.type != null ? reactedStatus.type : "text");
          data.put("replyToText",         StatusReplyBottomSheet.getPreviewText(reactedStatus));
          data.put("replyToSenderName",   ownerName != null ? ownerName : "Status");
          data.put("replyToId",           "status_" + (reactedStatus.id != null ? reactedStatus.id : "unknown"));
          String thumb = reactedStatus.thumbnailUrl != null ? reactedStatus.thumbnailUrl
                  : ("image".equals(reactedStatus.type) ? reactedStatus.mediaUrl : null);
          if (thumb != null) data.put("replyToMediaUrl", thumb);
          FirebaseUtils.getMessagesRef(chatId).child(msgId).setValue(data);
      }
      private int dpToPx(int dp) {
          return Math.round(dp * getResources().getDisplayMetrics().density);
      }
      private void releasePlayer() {
          if (player != null) { player.release(); player = null; }
      }
  }