package com.callx.app.viewer;
import com.callx.app.utils.AlertDialogStyler;
  import android.content.Intent;
  import android.graphics.Color;
  import android.net.Uri;
  import android.os.Bundle;
  import android.os.Handler;
  import android.os.Looper;
  import android.text.TextUtils;
  import android.util.Log;
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
  import com.callx.app.stickers.StatusStickerOverlayView;
  import org.json.JSONArray;
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
      // Debug tag for the repost-crash + music-sticker-autoplay investigation.
      // Filter logcat with "StatusViewerDbg" to trace exactly which branch a
      // given status/sticker takes and why (adb logcat -s StatusViewerDbg).
      private static final String DBG = "StatusViewerDbg";
      public static final String EXTRA_OWNER_UID  = "ownerUid";
      public static final String EXTRA_OWNER_NAME = "ownerName";
      /** Optional — set by ChatActivity when opened from a reply/reaction
       *  quote-box tap, so the viewer opens directly on the exact status
       *  that was replied/reacted to (WhatsApp jumps to that specific
       *  story, not just the owner's oldest active one). */
      public static final String EXTRA_TARGET_STATUS_ID = "targetStatusId";
      /** v39 — When set, the viewer loads this owner's Highlight ALBUM
       *  (statusHighlights/{ownerUid}/{albumId}) instead of their live status
       *  feed, and never filters by expiresAt — highlight items are permanent,
       *  Instagram-style, even after the original story expired/was deleted. */
      public static final String EXTRA_HIGHLIGHT_ALBUM_ID = "highlightAlbumId";
      /** v40 — Instagram-style continuous playback. Ordered queue of other
       *  owners' UIDs/names to auto-advance through once the current owner's
       *  stories finish — instead of just closing the viewer. Not used in
       *  highlight mode (see EXTRA_QUEUE_ALBUM_IDS for that). */
      public static final String EXTRA_QUEUE_OWNER_UIDS  = "queueOwnerUids";
      public static final String EXTRA_QUEUE_OWNER_NAMES = "queueOwnerNames";
      /** v40 — Same idea for Highlights: ordered queue of album IDs/names
       *  (same owner) to auto-advance through once the current album's
       *  items finish, e.g. tapping the 2nd highlight ring on a profile
       *  continues straight into the 3rd, 4th, etc — exactly like Instagram
       *  chaining highlight reels together instead of closing after one. */
      public static final String EXTRA_QUEUE_ALBUM_IDS   = "queueAlbumIds";
      public static final String EXTRA_QUEUE_ALBUM_NAMES = "queueAlbumNames";
      private final List<String> queueOwnerUids  = new ArrayList<>();
      private final List<String> queueOwnerNames = new ArrayList<>();
      private final List<String> queueAlbumIds   = new ArrayList<>();
      private final List<String> queueAlbumNames = new ArrayList<>();
      private int queuePos = 0;
      private String highlightAlbumId;
      private boolean isHighlightMode;
      private String targetStatusId;
      private ActivityStatusViewerBinding binding;
      private final List<StatusItem> items         = new ArrayList<>();
      private final List<String>     seenInSession  = new ArrayList<>();
      // Instagram-style floating-emoji replay: guards against re-triggering
      // the burst every time showCurrent() re-runs for the same status
      // (e.g. swiping back to a previously-viewed segment in this session).
      private final java.util.Set<String> reactionBurstPlayedFor = new java.util.HashSet<>();
      private int     idx         = 0;
      private ExoPlayer player;
      // Separate audio-only player for a 🎵 music sticker's linked preview
      // clip — independent of `player` above (which only ever holds the
      // status's own video track). Autoplays as soon as the status carrying
      // the sticker is shown, mirrors Instagram/WhatsApp behaviour.
      private android.media.MediaPlayer musicPlayer;
      private final Handler  handler      = new Handler(Looper.getMainLooper());
      private Runnable       progressRunner;
      private boolean        paused       = false;
      private long           remainingMs  = 0;
      private boolean        isMuted      = false;
      private long           viewStartTime = 0;
      private String myUid, ownerUid, ownerName;
      private final List<ProgressBar> segmentBars = new ArrayList<>();
      // ── Latest-reply overlay (owner-only, Instagram-style) ─────────────────
      private DatabaseReference repliesListenerRef;
      private ValueEventListener repliesListener;
      private Runnable          replyOverlayAutoHide;
      private static final long REPLY_OVERLAY_AUTO_HIDE_MS = 4000;
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
          targetStatusId = getIntent().getStringExtra(EXTRA_TARGET_STATUS_ID);
          highlightAlbumId = getIntent().getStringExtra(EXTRA_HIGHLIGHT_ALBUM_ID);
          isHighlightMode = highlightAlbumId != null && !highlightAlbumId.isEmpty();
          if (ownerUid == null) { finish(); return; }
          // v40 — resolve the continuous-playback queue (if the caller sent
          // one) and figure out where in it we're starting, so
          // goToNextQueueEntryOrFinish()/goToPreviousQueueEntry() know what
          // comes before/after the entry we're opening on.
          List<String> qUids = getIntent().getStringArrayListExtra(EXTRA_QUEUE_OWNER_UIDS);
          if (qUids != null) queueOwnerUids.addAll(qUids);
          List<String> qNames = getIntent().getStringArrayListExtra(EXTRA_QUEUE_OWNER_NAMES);
          if (qNames != null) queueOwnerNames.addAll(qNames);
          List<String> qAlbumIds = getIntent().getStringArrayListExtra(EXTRA_QUEUE_ALBUM_IDS);
          if (qAlbumIds != null) queueAlbumIds.addAll(qAlbumIds);
          List<String> qAlbumNames = getIntent().getStringArrayListExtra(EXTRA_QUEUE_ALBUM_NAMES);
          if (qAlbumNames != null) queueAlbumNames.addAll(qAlbumNames);
          if (isHighlightMode && !queueAlbumIds.isEmpty()) {
              int found = queueAlbumIds.indexOf(highlightAlbumId);
              queuePos = Math.max(found, 0);
          } else if (!isHighlightMode && !queueOwnerUids.isEmpty()) {
              int found = queueOwnerUids.indexOf(ownerUid);
              queuePos = Math.max(found, 0);
          }
          try { myUid = FirebaseUtils.getCurrentUid(); } catch (Exception e) { myUid = null; }
          setupSwipeDownGesture();
          setupTouchZones();
          setupCloseButton();
          setupReactionButton();
          setupLikeHeartButton();
          setupQuickReactionOverlay();
          setupReplyButton();
          setupMoreButton();
          setupMuteButton();
          setupDownloadButton();
          setupForwardButton();
          setupRepostButton();
          binding.tvOwner.setText(ownerName != null ? ownerName : "Status");
          if (StatusCloseFriendsManager.isCloseFriend(this, ownerUid))
              binding.tvOwner.setText("\u2B50 " + (ownerName != null ? ownerName : "Status"));
          com.callx.app.utils.VerifiedBadgeUtils.bindForUid(
              (android.widget.ImageView) findViewById(R.id.iv_owner_verified), ownerUid);
          load(ownerUid);
      }
      @Override protected void onPause()  { super.onPause();  pauseProgress(); }
      @Override protected void onResume() {
          super.onResume();
          StatusStickerOverlayView zoomed = findZoomedSticker();
          if (zoomed != null) settleStickerReaction(zoomed); // shrinks back, then resumes
          else if (paused) resumeProgress();
      }
      /** The sticker currently enlarged front-and-centre by the tap-to-zoom
       *  gate, if any — at most one can be zoomed in at a time. */
      private StatusStickerOverlayView findZoomedSticker() {
          for (int i = 0; i < binding.flStickerOverlay.getChildCount(); i++) {
              View child = binding.flStickerOverlay.getChildAt(i);
              if (child instanceof StatusStickerOverlayView && ((StatusStickerOverlayView) child).isZoomedIn()) {
                  return (StatusStickerOverlayView) child;
              }
          }
          return null;
      }
      @Override
      protected void onDestroy() {
          detachRepliesListener();
          releasePlayer();
          if (binding.tvStatusSongName != null) binding.tvStatusSongName.release();
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
              StatusSeenTracker.markSeenBatch(this, ownerUid, seenInSession,
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
          if (isHighlightMode) { loadHighlightAlbum(uid); return; }
          FirebaseUtils.getStatusRef().child(uid)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      long now = System.currentTimeMillis();
                      for (DataSnapshot c : snap.getChildren()) {
                          StatusItem s;
                          try {
                              s = c.getValue(StatusItem.class);
                          } catch (Exception parseErr) {
                              // A single malformed child used to silently vanish from the
                              // list (or worse, crash the whole onDataChange before any
                              // items were added) with zero trace of which row or why.
                              Log.e(DBG, "load() failed to parse status child key=" + c.getKey(), parseErr);
                              CrashReporter.report(StatusViewerActivity.this, "StatusViewer.load.parseChild", parseErr);
                              continue;
                          }
                          if (s == null || Boolean.TRUE.equals(s.deleted)) continue;
                          if (s.expiresAt != null && s.expiresAt < now) continue;
                          Log.d(DBG, "load() added item id=" + s.id + " type=" + s.type
                                  + " resharedMediaType=" + s.resharedMediaType
                                  + " hasMediaUrl=" + (s.mediaUrl != null && !s.mediaUrl.isEmpty()));
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
                      if (targetStatusId != null && !targetStatusId.isEmpty()) {
                          int found = -1;
                          for (int i = 0; i < items.size(); i++) {
                              if (targetStatusId.equals(items.get(i).id)) { found = i; break; }
                          }
                          if (found >= 0) {
                              idx = found;
                          } else {
                              // The specific status this reply/reaction pointed
                              // at has since expired/been deleted, but the
                              // owner still has other active statuses — let
                              // the viewer open (at the first one) rather than
                              // silently failing, but say why the exact one
                              // isn't there.
                              Toast.makeText(StatusViewerActivity.this,
                                      "That status is no longer available", Toast.LENGTH_SHORT).show();
                          }
                      }
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
                      // btnRepost visibility is refined per-item in showCurrent() based on allowSharing
                      binding.btnRepost.setVisibility(isOwner ? View.GONE : View.VISIBLE);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      CrashReporter.reportSilently(StatusViewerActivity.this, "StatusViewer.load.onCancelled", e.toException());
                      finish();
                  }
              });
      }
      /** v39 — Loads a Highlight album's permanent copies. Deliberately does NOT
       *  filter by expiresAt: once a status is in a Highlight it stays visible
       *  forever, exactly like Instagram, regardless of whether the original
       *  story already expired. */
      private void loadHighlightAlbum(String uid) {
          StatusHighlightManager.getAlbumRef(uid, highlightAlbumId)
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      for (DataSnapshot c : snap.getChildren()) {
                          StatusItem s = c.getValue(StatusItem.class);
                          if (s == null) continue;
                          items.add(s);
                      }
                      if (items.isEmpty()) {
                          Toast.makeText(StatusViewerActivity.this,
                                  "This highlight is empty", Toast.LENGTH_SHORT).show();
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
                      boolean isOwner = myUid != null && myUid.equals(ownerUid);
                      binding.btnDownload.setVisibility(isOwner ? View.GONE : View.VISIBLE);
                      binding.btnForward.setVisibility(isOwner ? View.GONE : View.VISIBLE);
                      // btnRepost visibility is refined per-item in showCurrent() based on allowSharing
                      binding.btnRepost.setVisibility(isOwner ? View.GONE : View.VISIBLE);
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      CrashReporter.reportSilently(StatusViewerActivity.this, "StatusViewer.loadHighlightAlbum.onCancelled", e.toException());
                      finish();
                  }
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
          if (idx >= items.size()) { goToNextQueueEntryOrFinish(); return; }
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
          updateLastReplyOverlay(s);
          updateExpiryLabel(s);
          playReactionBurstIfAny(s);
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
              case "reel_reshare": case "post_reshare": case "channel_post_reshare":
              case "status_reshare":
                  // Wrapped in try/catch + reported: this is the exact code path
                  // behind the "repost dekhte waqt silent crash" reports — any
                  // exception here now shows the crash screen with a trace
                  // instead of leaving the viewer stuck/blank with no clue why.
                  try {
                      // ROOT CAUSE of the repost crash: this used to be
                      // "mediaUrl present -> showVideoStatus()" unconditionally.
                      // A reshare's own `type` (status_reshare/reel_reshare/...)
                      // never says whether the underlying media is a photo or a
                      // video, so an image repost was being handed to ExoPlayer,
                      // which errored out immediately and left/broke the viewer.
                      // resharedMediaType (added by StoryReshareActivity at
                      // repost time) now tells us the real type; for OLD reshare
                      // rows saved before this field existed, fall back to a
                      // file-extension guess instead of assuming "video".
                      boolean looksLikeVideo;
                      if (s.resharedMediaType != null && !s.resharedMediaType.isEmpty()) {
                          looksLikeVideo = "video".equals(s.resharedMediaType);
                          Log.d(DBG, "reshareRender id=" + s.id + " using resharedMediaType=" + s.resharedMediaType);
                      } else {
                          looksLikeVideo = looksLikeVideoUrl(s.mediaUrl);
                          Log.d(DBG, "reshareRender id=" + s.id + " resharedMediaType MISSING (legacy row) — guessed from URL, looksLikeVideo=" + looksLikeVideo);
                      }
                      Log.d(DBG, "reshareRender id=" + s.id + " type=" + s.type
                              + " mediaUrl=" + (s.mediaUrl == null ? "null" : "present")
                              + " thumbnailUrl=" + (s.thumbnailUrl == null ? "null" : "present")
                              + " resharedThumbnailUrl=" + (s.resharedThumbnailUrl == null ? "null" : "present")
                              + " text=" + (s.text == null || s.text.isEmpty() ? "empty" : "present"));

                      if (s.mediaUrl != null && !s.mediaUrl.isEmpty() && looksLikeVideo) {
                          showVideoStatus(s);
                      } else if (s.mediaUrl != null && !s.mediaUrl.isEmpty()) {
                          showImageStatusFromUrl(s.mediaUrl, s.caption);
                      } else if (s.thumbnailUrl != null && !s.thumbnailUrl.isEmpty()) { showImageStatusFromUrl(s.thumbnailUrl, s.caption); }
                      else if (s.resharedThumbnailUrl != null && !s.resharedThumbnailUrl.isEmpty()) { showImageStatusFromUrl(s.resharedThumbnailUrl, s.caption); }
                      else if (s.text != null && !s.text.isEmpty()) { showTextStatus(s); }
                      else { showUnavailableFallback(); }
                  } catch (Exception e) {
                      Log.e(DBG, "reshareRender CRASH for id=" + s.id + " type=" + s.type, e);
                      CrashReporter.report(this, "StatusViewer.reshareRender", e);
                      showUnavailableFallback();
                  }
                  break;
              default: next();
          }
          renderStickers(s);

          // ── Reshare attribution card ─────────────────────────────────────
          try {
              android.view.View flReshare = findViewById(R.id.fl_reshare_attribution_container);
              if (flReshare != null) {
                  boolean isReshare = "reel_reshare".equals(s.type)
                                   || "post_reshare".equals(s.type)
                                   || "channel_post_reshare".equals(s.type)
                                   || "status_reshare".equals(s.type);
                  if (isReshare && s.resharedFromOwnerName != null && !s.resharedFromOwnerName.isEmpty()) {
                      flReshare.setVisibility(android.view.View.VISIBLE);
                      android.widget.ImageView ivThumb = flReshare.findViewById(R.id.iv_reshare_thumb);
                      if (ivThumb != null && s.resharedThumbnailUrl != null && !s.resharedThumbnailUrl.isEmpty())
                          loadReshareThumbOptimized(ivThumb, s.resharedThumbnailUrl);
                      android.widget.TextView tvOwner = flReshare.findViewById(R.id.tv_reshare_original_owner);
                      if (tvOwner != null) tvOwner.setText("@" + s.resharedFromOwnerName);
                      android.widget.TextView tvBadge = flReshare.findViewById(R.id.tv_reshare_type_badge);
                      if (tvBadge != null) tvBadge.setText(
                          "post".equals(s.resharedFromType) ? "Post" :
                          "channel_post".equals(s.resharedFromType) ? "Channel" :
                          "status".equals(s.resharedFromType) ? "Status" : "Reel");
                      android.widget.TextView tvView = flReshare.findViewById(R.id.tv_view_original_btn);
                      if (tvView != null) {
                          final String ft = s.resharedFromType != null ? s.resharedFromType : "reel";
                          final String fi = s.resharedFromId   != null ? s.resharedFromId   : "";
                          final String fu = s.resharedFromOwnerUid != null ? s.resharedFromOwnerUid : "";
                          tvView.setOnClickListener(v -> openOriginalContent(ft, fi, fu));
                      }
                      // Tapping the card itself (thumb / badge / owner name —
                      // anything except the "View Original →" sub-text above,
                      // which keeps its own listener) opens the ORIGINAL
                      // CREATOR's profile, not the reshared content.
                      android.view.View cardRoot = flReshare.findViewById(R.id.ll_reshare_attribution_card);
                      if (cardRoot != null) {
                          final String creatorUid  = s.resharedFromOwnerUid   != null ? s.resharedFromOwnerUid   : "";
                          final String creatorName = s.resharedFromOwnerName  != null ? s.resharedFromOwnerName  : "";
                          cardRoot.setOnClickListener(v -> openOriginalCreatorProfile(creatorUid, creatorName));
                      }
                  } else {
                      flReshare.setVisibility(android.view.View.GONE);
                  }

                  // ── Repost button: show only if owner allows sharing and viewer ≠ owner ──
                  boolean amOwner = myUid != null && myUid.equals(ownerUid);
                  if (!amOwner) {
                      boolean canRepost = s.allowSharing && !isReshare; // don't chain-repost reshared items
                      binding.btnRepost.setVisibility(canRepost ? android.view.View.VISIBLE : android.view.View.GONE);
                  }
              }
          } catch (Exception e) {
              CrashReporter.report(this, "StatusViewer.reshareAttributionCard", e);
          }
      }

      /** Cached target decode size (px) for the reshare attribution thumb —
       *  computed once per process, not per bind. Layout uses 56dp, but we
       *  resolve it from the ImageView's own declared LayoutParams so this
       *  stays correct even if the layout later changes the dp value. */
      private static volatile int sReshareThumbPx = 0;

      /** Ultra-optimized, size-aware decode for the reshare attribution card
       *  thumbnail. The card is tiny (56dp) but source images/video-frame
       *  thumbnails coming from Cloudinary/Firebase can be arbitrarily large,
       *  so a plain Glide.load()+centerCrop() (previous code) still decodes
       *  a full-resolution bitmap into memory before downscaling for
       *  display — wasteful on a list of reshared statuses swiped quickly.
       *
       *  This instead:
       *   - Resolves the exact target pixel size from the ImageView's own
       *     layout params (falls back to 56dp) and decodes AT that size via
       *     Glide's override(), so the hardware bitmap allocated is only
       *     ever thumb-sized — no oversized intermediate bitmap, no jank.
       *   - Uses RGB_565 instead of ARGB_8888: these are opaque photo/video
       *     thumbnails (no alpha channel needed), which halves per-pixel
       *     memory (2 bytes vs 4) for identical visual result at this size.
       *   - DownsampleStrategy.CENTER_OUTSIDE mirrors centerCrop's crop
       *     behavior but tells Glide's decoder to downsample during decode
       *     (inSampleSize), not after — the expensive part of "size k
       *     hisab se decode" happens on the decoder thread, not via a
       *     later Bitmap.createScaledBitmap() pass.
       *   - DiskCacheStrategy.RESOURCE caches the already-transformed
       *     (already downsampled+cropped) bitmap on disk, so repeat views
       *     of the same reshared status (re-swiping) skip re-decoding and
       *     re-downsampling entirely, not just skip the network hit.
       *   - Fixed-size override() also means Glide can serve from its
       *     bitmap pool instead of allocating fresh, and
       *     dontAnimate()+dontTransform() variance-free requests coalesce
       *     Glide's internal engine key, so rapid rebind while swiping
       *     between statuses reuses in-flight/completed loads instead of
       *     starting duplicate decodes for the same URL+size.
       *   - Priority.IMMEDIATE since this is on-screen, above-the-fold
       *     content the user is looking at right now. */
      private void loadReshareThumbOptimized(android.widget.ImageView ivThumb, String url) {
          int px = sReshareThumbPx;
          if (px <= 0) {
              int lw = ivThumb.getLayoutParams() != null ? ivThumb.getLayoutParams().width : 0;
              px = lw > 0
                  ? lw
                  : Math.round(56 * getResources().getDisplayMetrics().density);
              sReshareThumbPx = px;
          }
          com.bumptech.glide.request.RequestOptions opts = new com.bumptech.glide.request.RequestOptions()
              .override(px, px)
              .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
              .downsample(com.bumptech.glide.load.resource.bitmap.DownsampleStrategy.CENTER_OUTSIDE)
              .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE)
              .centerCrop()
              .dontAnimate();
          com.bumptech.glide.Glide.with(this)
              .load(url)
              .apply(opts)
              .priority(com.bumptech.glide.Priority.IMMEDIATE)
              .into(ivThumb);
      }

      /** Navigate to the original reel or post that was reshared. */
      private void openOriginalContent(String fromType, String fromId, String fromUid) {
          if (fromId == null || fromId.isEmpty()) return;
          try {
              android.content.Intent i;
              if ("reel".equals(fromType)) {
                  try { i = new android.content.Intent(this, Class.forName("com.callx.app.feed.SingleReelPlayerActivity")); } catch (ClassNotFoundException _e) { return; }
                  i.putExtra("reel_id", fromId);
                  i.putExtra("owner_uid", fromUid);
              } else {
                  try { i = new android.content.Intent(this, Class.forName("com.callx.app.activities.UserProfileActivity")); } catch (ClassNotFoundException _e) { return; }
                  i.putExtra("uid", fromUid);
              }
              startActivity(i);
          } catch (Exception e) {
              android.widget.Toast.makeText(this, "Could not open original post",
                  android.widget.Toast.LENGTH_SHORT).show();
          }
      }

      /** Tapping the reshare attribution card (anywhere except the explicit
       *  "View Original →" sub-text) opens the ORIGINAL CREATOR's reels
       *  profile screen — same UserReelsActivity used everywhere else in the
       *  app as the generic profile screen. feature-status has no compile-time
       *  dependency on feature-reels, so this reflects the class by name,
       *  same pattern as openOriginalContent() above. */
      private void openOriginalCreatorProfile(String uid, String name) {
          if (uid == null || uid.isEmpty()) return;
          try {
              android.content.Intent i;
              try { i = new android.content.Intent(this, Class.forName("com.callx.app.profile.UserReelsActivity")); }
              catch (ClassNotFoundException _e) { return; }
              i.putExtra("uid",  uid);
              i.putExtra("name", name != null ? name : "");
              startActivity(i);
          } catch (Exception e) {
              android.widget.Toast.makeText(this, "Could not open profile",
                  android.widget.Toast.LENGTH_SHORT).show();
          }
      }

      /** Best-effort guess for legacy reshare rows with no resharedMediaType saved. */
      private boolean looksLikeVideoUrl(String url) {
          if (url == null || url.isEmpty()) return false;
          String u = url.toLowerCase(Locale.ROOT);
          int q = u.indexOf('?');
          if (q >= 0) u = u.substring(0, q);
          return u.endsWith(".mp4") || u.endsWith(".mov") || u.endsWith(".3gp")
                  || u.endsWith(".webm") || u.endsWith(".mkv");
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
      /**
       * Last-resort fallback for a reshare item that truly has no renderable
       * content (no media, no thumbnail, no original text — shouldn't happen
       * after the reshare fix, but kept as a safety net). Shows a plain card
       * instead of silently skipping/closing the viewer, since a silent
       * finish() looks exactly like a crash to the user.
       */
      private void showUnavailableFallback() {
          binding.flTextStatus.setVisibility(View.VISIBLE);
          binding.flTextStatus.setBackgroundResource(R.drawable.gradient_brand);
          binding.tvTextStatus.setText("This content is no longer available");
          binding.tvTextStatus.setTextColor(Color.WHITE);
          startProgress(5_000L);
      }
      private void showImageStatusFromUrl(String url, String caption) {
          binding.ivStatus.setVisibility(View.VISIBLE);
          binding.ivStatusBg.setVisibility(View.VISIBLE);
          // WhatsApp-style: full photo, never cropped (fitCenter, set in
          // XML) + a blurred/darkened cropped copy of the same photo filling
          // the screen behind it, instead of hard-cropping the real photo.
          Glide.with(this).load(url)
               .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
               .apply(new com.bumptech.glide.request.RequestOptions()
                       .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                               new StatusBlurTransformation(20)))
               .placeholder(android.R.drawable.screen_background_dark)
               .into(binding.ivStatusBg);
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
          final String statusIdForLog = s.id;
          player.addListener(new Player.Listener() {
              @Override public void onPlaybackStateChanged(int state) {
                  if (state == Player.STATE_READY) {
                      long real = player.getDuration();
                      long dur = (real > 0 && real != Long.MIN_VALUE) ? Math.min(real, 30_000L) : estimated;
                      stopProgress(); startProgress(dur);
                  } else if (state == Player.STATE_ENDED) { next(); }
              }
              // Previously unhandled — ExoPlayer delivers a bad/unsupported
              // media URL (e.g. a reshared IMAGE wrongly routed here, a dead
              // link, unsupported codec) as an async onPlayerError, not a
              // thrown exception, so it slipped past every try/catch in
              // showCurrent() and just left the viewer stuck on a frozen
              // black frame with the progress bar paused forever — which is
              // exactly what "screen closes / misbehaves right after
              // opening" looks like from the user's side. Now logged and
              // recovered: skip to the next status instead of hanging.
              @Override public void onPlayerError(@NonNull PlaybackException error) {
                  Log.e(DBG, "showVideoStatus onPlayerError id=" + statusIdForLog
                          + " mediaUrl=" + s.mediaUrl + " errorCode=" + error.errorCode, error);
                  CrashReporter.reportSilently(StatusViewerActivity.this,
                          "StatusViewer.showVideoStatus.onPlayerError", error);
                  next();
              }
          });
          player.prepare(); player.setPlayWhenReady(true);
          startProgress(estimated);
          showCaption(s.caption);
      }
      private void hideAllContent() {
          binding.flTextStatus.setVisibility(View.GONE);
          binding.ivStatus.setVisibility(View.GONE);
          binding.ivStatusBg.setVisibility(View.GONE);
          binding.playerView.setVisibility(View.GONE);
          binding.tvCaption.setVisibility(View.GONE);
          // FIX: was findViewWithTag("tv_location_tag") — always null — now binding ref
          binding.tvLocationTag.setVisibility(View.GONE);
          binding.flStickerOverlay.removeAllViews();
          stickerZoomScrim = null; // just got removed along with everything else above
          releaseMusicPlayer(); // stop the previous status's music sticker before the next one renders
          hideStatusSongTicker(); // reset the below-name audio ticker; renderStickers() re-shows it if this status has one
      }

      /** Hides + pauses the below-name audio-name ticker (ll_status_song_row /
       *  tv_status_song_name) — called on every status change before the next
       *  status's stickers (if any) are rendered. */
      private void hideStatusSongTicker() {
          if (binding.llStatusSongRow != null) binding.llStatusSongRow.setVisibility(View.GONE);
          if (binding.tvStatusSongName != null) binding.tvStatusSongName.pause();
      }

      /** Shows the below-name audio ticker for a music sticker found on the
       *  current status — "Song · Artist" (falls back to just the song name
       *  when no artist is set), same "Song · Artist" join format as the reel
       *  player's ReelUiController#buildMusicDisplay(). Called once per music
       *  sticker from renderStickers(); harmless if a status somehow has more
       *  than one (last one wins, matches "only shows one row" reel behavior). */
      private void showStatusSongTicker(StatusStickerOverlayView sticker) {
          if (binding.llStatusSongRow == null || binding.tvStatusSongName == null) return;
          String song = sticker.getMusicSong();
          if (song == null || song.isEmpty()) return;
          String artist = sticker.getMusicArtist();
          String display = (artist != null && !artist.isEmpty() && !song.contains(artist))
                  ? song + " · " + artist : song;
          binding.tvStatusSongName.setText(display);
          binding.llStatusSongRow.setVisibility(View.VISIBLE);
          binding.tvStatusSongName.resume();
      }

      // ── Music sticker inline autoplay ────────────────────────────────────
      // Starts playing a music sticker's linked preview clip as soon as the
      // status carrying it is shown. Uses its own MediaPlayer (not the
      // ExoPlayer `player` field, which is reserved for the status's own
      // video track) so a music sticker plays regardless of whether the
      // status itself is a photo, text card, video, or gif.
      private void startMusicStickerAudio(String url) {
          Log.d(DBG, "startMusicStickerAudio url=" + url + " isMuted=" + isMuted + " paused=" + paused);
          releaseMusicPlayer();
          try {
              musicPlayer = new android.media.MediaPlayer();
              musicPlayer.setAudioAttributes(new android.media.AudioAttributes.Builder()
                      .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                      .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                      .build());
              musicPlayer.setDataSource(url);
              // Loop so the clip keeps playing even if it's shorter than however
              // long the viewer ends up spending on this status.
              musicPlayer.setLooping(true);
              musicPlayer.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f);
              musicPlayer.setOnPreparedListener(mp -> {
                  Log.d(DBG, "startMusicStickerAudio prepared, paused=" + paused + " -> " + (!paused ? "starting" : "NOT starting (viewer paused)"));
                  if (!paused) mp.start();
              });
              musicPlayer.setOnErrorListener((mp, what, extra) -> {
                  // This is the most likely real cause of "music sticker never
                  // plays, no error shown" — bad/expired URL, unsupported codec,
                  // network failure, etc. all land here silently before. Now
                  // reported (non-intrusive — logged + saved to file, doesn't
                  // interrupt the viewer) so the actual reason is visible.
                  CrashReporter.reportSilently(StatusViewerActivity.this, "StatusViewer.musicSticker.onError",
                          new RuntimeException("MediaPlayer error what=" + what + " extra=" + extra + " url=" + url));
                  releaseMusicPlayer();
                  return true;
              });
              musicPlayer.prepareAsync();
          } catch (Exception e) {
              // Bad/unreachable preview URL — sticker just stays silent, never crash the viewer.
              CrashReporter.reportSilently(this, "StatusViewer.startMusicStickerAudio", e);
              releaseMusicPlayer();
          }
      }
      /**
       * Fallback lookup for music-sticker autoplay: mirrors
       * SoundDetailFragment#loadSoundDataFromMusicLibrary() — tracks picked
       * from Trending Audio's "Music" tab live under musicLibrary/{soundId},
       * not sounds/{soundId}. Without this, autoplay stayed silent for any
       * sticker built from a Music-tab track while manually tapping it (which
       * opens the full sheet — same two-step lookup) still played fine.
       */
      private void resolveFromMusicLibrary(String soundId, int requestedIdx) {
          FirebaseUtils.getMusicLibraryRef().child(soundId)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (idx != requestedIdx) return; // swiped away while this was in flight
                  if (!snap.exists()) {
                      Log.d(DBG, "musicSticker autoplay: soundId=" + soundId + " not found in musicLibrary/ either — giving up silently");
                      return;
                  }
                  String resolved = null;
                  for (String key : new String[]{"previewAudioUrl", "audioUrl"}) {
                      String u = snap.child(key).getValue(String.class);
                      if (u != null && !u.isEmpty()) { resolved = u; break; }
                  }
                  if (resolved != null) {
                      Log.d(DBG, "musicSticker autoplay: resolved from musicLibrary/" + soundId);
                      startMusicStickerAudio(resolved);
                  } else {
                      Log.d(DBG, "musicSticker autoplay: musicLibrary/" + soundId + " exists but has no audio field");
                  }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  Log.e(DBG, "musicSticker autoplay: musicLibrary/" + soundId + " lookup cancelled", e.toException());
              }
          });
      }

      private void releaseMusicPlayer() {
          if (musicPlayer != null) {
              try { musicPlayer.release(); } catch (Exception ignored) {}
              musicPlayer = null;
          }
      }

      // ── Interactive stickers (music/countdown/quiz/question) ────────────────
      // Renders the poster's stickers on top of the current status (any media
      // type) and, for a 💬 Question sticker, wires a tap to open the
      // dedicated answer sheet — mirrors setupReplyButton()'s pause/resume
      // pattern so the progress timer stops while the viewer is typing.
      private void renderStickers(StatusItem item) {
          if (item == null || item.stickersJson == null || item.stickersJson.isEmpty()) return;
          try {
              JSONArray arr = new JSONArray(item.stickersJson);
              int dp = (int) getResources().getDisplayMetrics().density;
              for (int i = 0; i < arr.length(); i++) {
                  String stickerJson = arr.getJSONObject(i).toString();
                  StatusStickerOverlayView sticker = StatusStickerOverlayView.fromJson(this, stickerJson);

                  // Frame may not have been measured yet on the very first status
                  // shown — fall back to screen size so the ratio math below still
                  // lines the sticker up correctly instead of pinning it at (0,0).
                  int frameW = binding.flStickerOverlay.getWidth() > 0
                          ? binding.flStickerOverlay.getWidth() : getResources().getDisplayMetrics().widthPixels;
                  int frameH = binding.flStickerOverlay.getHeight() > 0
                          ? binding.flStickerOverlay.getHeight() : getResources().getDisplayMetrics().heightPixels;

                  FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                          Math.min(frameW - dp * 32, dp * 280),
                          FrameLayout.LayoutParams.WRAP_CONTENT,
                          sticker.hasSavedPosition() ? Gravity.TOP | Gravity.START : Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                  if (sticker.hasSavedPosition()) {
                      // Poster dragged/pinched this sticker to a specific spot in the
                      // composer — reproduce that exact spot here instead of the
                      // generic stacked layout below.
                      lp.leftMargin = (int) (sticker.getSavedPosXRatio() * frameW);
                      lp.topMargin  = (int) (sticker.getSavedPosYRatio() * frameH);
                  } else {
                      lp.topMargin = dp * (140 + i * 90); // legacy sticker with no saved position: stack as before
                  }
                  binding.flStickerOverlay.addView(sticker, lp);

                  if ("question".equals(sticker.getStickerType())
                          && myUid != null && !myUid.equals(ownerUid)) {
                      armStickerZoomGate(sticker, () -> {
                          StatusItem current = idx < items.size() ? items.get(idx) : null;
                          if (current == null) { settleStickerReaction(sticker); return; }
                          StatusReplyBottomSheet.showForQuestion(this, current, ownerName, myUid, ownerUid,
                                  sticker.getQuestionPrompt(), msg -> settleStickerReaction(sticker));
                      });
                  }

                  if ("quiz".equals(sticker.getStickerType())
                          && myUid != null && !myUid.equals(ownerUid)) {
                      StatusItem current = idx < items.size() ? items.get(idx) : null;
                      final int stickerIndex = i;
                      final String statusId = current != null ? current.id : null;
                      if (statusId != null) {
                          // Restore a prior answer if this viewer already voted, otherwise let them tap once.
                          FirebaseUtils.getStatusQuizVoteRef(ownerUid, statusId, stickerIndex, myUid)
                                  .addListenerForSingleValueEvent(new ValueEventListener() {
                              @Override public void onDataChange(DataSnapshot snap) {
                                  if (snap.exists()) {
                                      Long prevSelected = snap.child("selectedIndex").getValue(Long.class);
                                      if (prevSelected != null) sticker.revealQuizAnswer(prevSelected.intValue());
                                  } else {
                                      armStickerZoomGate(sticker);
                                      sticker.setOnQuizOptionSelectedListener(selectedIndex -> {
                                          StatusItem cur = idx < items.size() ? items.get(idx) : null;
                                          if (cur == null) return;
                                          pauseProgress();
                                          java.util.List<String> opts = sticker.getQuizOptions();
                                          String selectedText = opts != null && selectedIndex < opts.size()
                                                  ? opts.get(selectedIndex) : "";
                                          boolean isCorrect = selectedIndex == sticker.getQuizCorrectIndex();
                                          sticker.revealQuizAnswer(selectedIndex);
                                          StatusReplyBottomSheet.sendQuizAnswer(myUid, ownerUid, ownerName,
                                                  cur, stickerIndex, sticker.getQuizQuestion(),
                                                  selectedText, selectedIndex, isCorrect);
                                          // Give the viewer a beat to see the ✓/✗ reveal, then shrink back and resume.
                                          new Handler(Looper.getMainLooper())
                                                  .postDelayed(() -> settleStickerReaction(sticker), 1200);
                                      });
                                  }
                              }
                              @Override public void onCancelled(DatabaseError e) {}
                          });
                      }
                  }

                  if ("music".equals(sticker.getStickerType())) {
                      // NEW: below-name audio ticker (ll_status_song_row /
                      // tv_status_song_name) — Instagram/reel-style, reusing
                      // core's MusicTickerView, the exact same component the
                      // reel player's bio song row already uses. Only shown
                      // when the poster actually attached a music sticker to
                      // this status; hideStatusSongTicker() (called from
                      // hideAllContent() on every status change) keeps it
                      // hidden otherwise.
                      showStatusSongTicker(sticker);
                      // Autoplay the sticker's preview clip the instant the status
                      // appears — WhatsApp/Instagram-style. Independent of whether
                      // it's linked to a real Reels track (tap-to-open still only
                      // applies when isMusicLinkedToReelSound() is true, below).
                      String soundUrl = sticker.getMusicSoundUrl();
                      Log.d(DBG, "musicSticker autoplay: soundId=" + sticker.getMusicSoundId()
                              + " directSoundUrl=" + (soundUrl == null || soundUrl.isEmpty() ? "empty" : "present"));
                      if (soundUrl != null && !soundUrl.isEmpty()) {
                          startMusicStickerAudio(soundUrl);
                      } else {
                          // Composer usually only stores the soundId, not a direct
                          // playable URL — that's why tapping the sticker to open the
                          // Sound Detail sheet has audio (it resolves the URL) while
                          // autoplay above stayed silent. Resolve it the same way here.
                          String soundId = sticker.getMusicSoundId();
                          if (soundId != null && !soundId.isEmpty()) {
                              final int requestedIdx = idx;
                              FirebaseUtils.db().getReference("sounds").child(soundId)
                                      .addListenerForSingleValueEvent(new ValueEventListener() {
                                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                                      // Viewer may have swiped to another status by the
                                      // time this lookup returns — don't start audio
                                      // for a status that's no longer showing.
                                      if (idx != requestedIdx) return;
                                      if (!snap.exists()) {
                                          // ROOT CAUSE of "Ad status music sticker never
                                          // autoplays": tracks picked from Trending Audio's
                                          // "Music" tab live under the musicLibrary node, NOT
                                          // sounds/{soundId} — this exact fallback is why
                                          // tapping the sticker (which opens the full Sound
                                          // Detail sheet, and THAT sheet checks musicLibrary
                                          // too) plays fine while autoplay here stayed silent.
                                          Log.d(DBG, "musicSticker autoplay: soundId=" + soundId
                                                  + " not found under sounds/, trying musicLibrary/");
                                          resolveFromMusicLibrary(soundId, requestedIdx);
                                          return;
                                      }
                                      String resolved = null;
                                      for (String key : new String[]{"previewAudioUrl", "audioUrl", "audio_url", "url"}) {
                                          String u = snap.child(key).getValue(String.class);
                                          if (u != null && !u.isEmpty()) { resolved = u; break; }
                                      }
                                      if (resolved != null) {
                                          Log.d(DBG, "musicSticker autoplay: resolved from sounds/" + soundId);
                                          startMusicStickerAudio(resolved);
                                      } else {
                                          Log.d(DBG, "musicSticker autoplay: sounds/" + soundId + " exists but no audio field, trying musicLibrary/");
                                          resolveFromMusicLibrary(soundId, requestedIdx);
                                      }
                                  }
                                  @Override public void onCancelled(@NonNull DatabaseError e) {
                                      Log.e(DBG, "musicSticker autoplay: sounds/" + soundId + " lookup cancelled", e.toException());
                                  }
                              });
                          }
                      }
                  }

                  if ("music".equals(sticker.getStickerType()) && sticker.isMusicLinkedToReelSound()) {
                      armStickerZoomGate(sticker, () -> openMusicStickerSoundSheet(sticker));
                  }

                  if ("countdown".equals(sticker.getStickerType())
                          && myUid != null && !myUid.equals(ownerUid)) {
                      StatusItem current = idx < items.size() ? items.get(idx) : null;
                      final int stickerIndex = i;
                      final String statusId = current != null ? current.id : null;
                      if (statusId != null) {
                          FirebaseUtils.getStatusCountdownSubscriberRef(ownerUid, statusId, stickerIndex, myUid)
                                  .addListenerForSingleValueEvent(new ValueEventListener() {
                              @Override public void onDataChange(DataSnapshot snap) {
                                  if (snap.exists()) {
                                      sticker.setCountdownSubscribed(true);
                                  }
                                  armStickerZoomGate(sticker);
                                  sticker.setOnCountdownSubscribeToggleListener(nowSubscribed -> {
                                      StatusItem cur = idx < items.size() ? items.get(idx) : null;
                                      if (cur == null) return;
                                      if (nowSubscribed) {
                                          StatusReplyBottomSheet.sendCountdownSubscription(myUid, ownerUid,
                                                  ownerName, cur, stickerIndex, sticker.getCountdownLabel());
                                      } else {
                                          // Quietly remove the subscription — no DM on unsubscribe,
                                          // so the poster isn't notified every time someone changes their mind.
                                          FirebaseUtils.getStatusCountdownSubscriberRef(
                                                  ownerUid, statusId, stickerIndex, myUid).removeValue();
                                      }
                                      // Give the viewer a beat to see the bell toggle, then shrink back and resume.
                                      new Handler(Looper.getMainLooper())
                                              .postDelayed(() -> settleStickerReaction(sticker), 500);
                                  });
                              }
                              @Override public void onCancelled(DatabaseError e) {}
                          });
                      }
                  }
                  if ("poll".equals(sticker.getStickerType())
                          && myUid != null && !myUid.equals(ownerUid)) {
                      StatusItem current = idx < items.size() ? items.get(idx) : null;
                      final int stickerIndex = i;
                      final String statusId = current != null ? current.id : null;
                      if (statusId != null) {
                          // Restore a prior vote if this viewer already voted, otherwise let them tap once.
                          FirebaseUtils.getStatusPollVoteRef(ownerUid, statusId, stickerIndex, myUid)
                                  .addListenerForSingleValueEvent(new ValueEventListener() {
                              @Override public void onDataChange(DataSnapshot snap) {
                                  if (snap.exists()) {
                                      String prevOption = snap.child("option").getValue(String.class);
                                      revealPollWithCounts(sticker, ownerUid, statusId, stickerIndex, prevOption);
                                  } else {
                                      armStickerZoomGate(sticker);
                                      sticker.setOnPollOptionSelectedListener(selectedOption -> {
                                          StatusItem cur = idx < items.size() ? items.get(idx) : null;
                                          if (cur == null) return;
                                          String selectedText = "A".equals(selectedOption)
                                                  ? sticker.getPollOptionA() : sticker.getPollOptionB();
                                          StatusReplyBottomSheet.sendPollVote(myUid, ownerUid, ownerName,
                                                  cur, stickerIndex, sticker.getPollQuestion(),
                                                  selectedOption, selectedText);
                                          revealPollWithCounts(sticker, ownerUid, statusId, stickerIndex, selectedOption);
                                          // Give the viewer a beat to see the % split, then shrink back and resume.
                                          new Handler(Looper.getMainLooper())
                                                  .postDelayed(() -> settleStickerReaction(sticker), 1200);
                                      });
                                  }
                              }
                              @Override public void onCancelled(DatabaseError e) {}
                          });
                      }
                  }

                  if ("slider".equals(sticker.getStickerType())
                          && myUid != null && !myUid.equals(ownerUid)) {
                      StatusItem current = idx < items.size() ? items.get(idx) : null;
                      final int stickerIndex = i;
                      final String statusId = current != null ? current.id : null;
                      if (statusId != null) {
                          // Restore a prior response if this viewer already rated, otherwise let them drag once.
                          FirebaseUtils.getStatusSliderResponseRef(ownerUid, statusId, stickerIndex, myUid)
                                  .addListenerForSingleValueEvent(new ValueEventListener() {
                              @Override public void onDataChange(DataSnapshot snap) {
                                  if (snap.exists()) {
                                      Long prevValue = snap.child("value").getValue(Long.class);
                                      int myVal = prevValue != null ? prevValue.intValue() : 50;
                                      revealSliderWithAverage(sticker, ownerUid, statusId, stickerIndex, myVal);
                                  } else {
                                      armStickerZoomGate(sticker);
                                      sticker.setOnSliderValueSubmittedListener(value -> {
                                          StatusItem cur = idx < items.size() ? items.get(idx) : null;
                                          if (cur == null) return;
                                          StatusReplyBottomSheet.sendSliderResponse(myUid, ownerUid, ownerName,
                                                  cur, stickerIndex, sticker.getSliderQuestion(),
                                                  sticker.getSliderEmoji(), value);
                                          revealSliderWithAverage(sticker, ownerUid, statusId, stickerIndex, value);
                                          // Give the viewer a beat to see the average, then shrink back and resume.
                                          new Handler(Looper.getMainLooper())
                                                  .postDelayed(() -> settleStickerReaction(sticker), 1200);
                                      });
                                  }
                              }
                              @Override public void onCancelled(DatabaseError e) {}
                          });
                      }
                  }
                  if ("mention".equals(sticker.getStickerType())) {
                      armStickerZoomGate(sticker, () -> openMentionStickerProfile(sticker));
                  }

                  if ("hashtag".equals(sticker.getStickerType())) {
                      armStickerZoomGate(sticker, () -> openHashtagStickerFeed(sticker));
                  }

                  if ("link".equals(sticker.getStickerType())) {
                      armStickerZoomGate(sticker, () -> openLinkSticker(sticker));
                  }

                  if ("addyours".equals(sticker.getStickerType())
                          && myUid != null && !myUid.equals(ownerUid)) {
                      armStickerZoomGate(sticker, () -> openAddYoursComposer(sticker));
                  }
              }
          } catch (Exception e) {
              // Malformed/legacy stickersJson, or a bug in one of the sticker
              // handlers below (music/quiz/poll/etc.) — used to swallow this
              // completely. Now reported instead: if a music sticker silently
              // isn't playing, or any other sticker misbehaves, this surfaces
              // the actual exception instead of just skipping rendering with
              // no trace of why.
              CrashReporter.report(this, "StatusViewer.renderStickers", e);
          }
      }

      /** Reads all votes on a 🗳️ Poll sticker and reveals the live A/B percentage split. */
      private void revealPollWithCounts(StatusStickerOverlayView sticker, String ownerUid,
                                         String statusId, int stickerIndex, String myOption) {
          FirebaseUtils.getStatusPollVotesRef(ownerUid, statusId, stickerIndex)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  int countA = 0, countB = 0;
                  for (DataSnapshot child : snap.getChildren()) {
                      String opt = child.child("option").getValue(String.class);
                      if ("A".equals(opt)) countA++;
                      else if ("B".equals(opt)) countB++;
                  }
                  sticker.revealPollResult(myOption, countA, countB);
              }
              @Override public void onCancelled(DatabaseError e) {
                  sticker.revealPollResult(myOption, "A".equals(myOption) ? 1 : 0,
                          "B".equals(myOption) ? 1 : 0);
              }
          });
      }

      /** Reads all responses on a 🎚️ Slider sticker and reveals the live average. */
      private void revealSliderWithAverage(StatusStickerOverlayView sticker, String ownerUid,
                                            String statusId, int stickerIndex, int myValue) {
          FirebaseUtils.getStatusSliderResponsesRef(ownerUid, statusId, stickerIndex)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  long total = 0; int count = 0;
                  for (DataSnapshot child : snap.getChildren()) {
                      Long val = child.child("value").getValue(Long.class);
                      if (val != null) { total += val; count++; }
                  }
                  int avg = count > 0 ? Math.round(total / (float) count) : myValue;
                  sticker.revealSliderAverage(myValue, avg);
              }
              @Override public void onCancelled(DatabaseError e) {
                  sticker.revealSliderAverage(myValue, myValue);
              }
          });
      }
      /**
       * Opens the exact same Sound Detail bottom sheet Reels uses (SoundDetailSheetFragment),
       * pre-filled with the track this music sticker points to. Loaded via reflection since
       * feature-status has no compile-time dependency on feature-reels — only pauses/resumes
       * the story progress bar around the sheet's lifetime.
       */
      private void openMusicStickerSoundSheet(StatusStickerOverlayView sticker) {
          try {
              Class<?> sheetCls = Class.forName("com.callx.app.music.SoundDetailSheetFragment");
              java.lang.reflect.Method newInstance = sheetCls.getMethod("newInstance",
                      String.class, String.class, String.class, String.class, String.class, int.class);
              Object sheetObj = newInstance.invoke(null,
                      sticker.getMusicSoundId(),
                      sticker.getMusicSong(),
                      sticker.getMusicArtist(),
                      sticker.getMusicCoverUrl(),
                      sticker.getMusicSoundUrl(),
                      0);
              if (!(sheetObj instanceof androidx.fragment.app.DialogFragment)) { settleStickerReaction(sticker); return; }
              final androidx.fragment.app.DialogFragment sheet = (androidx.fragment.app.DialogFragment) sheetObj;

              pauseProgress();
              getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                      new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                  @Override public void onFragmentDestroyed(
                          @NonNull androidx.fragment.app.FragmentManager fm,
                          @NonNull androidx.fragment.app.Fragment f) {
                      if (f == sheet) {
                          settleStickerReaction(sticker);
                          fm.unregisterFragmentLifecycleCallbacks(this);
                      }
                  }
              }, false);
              sheet.show(getSupportFragmentManager(), "sound_detail_full");
          } catch (Exception ignored) {
              // Reels module unavailable at runtime — the sticker just stays inert.
              settleStickerReaction(sticker);
          }
      }

      /**
       * Resolves the 👤 Mention sticker's username to a uid (Firebase "users" node,
       * same lookup pattern chat mentions use) then opens that profile. Loaded via
       * reflection since feature-status has no compile-time dependency on the app
       * module — pauses/resumes the story progress bar around the lookup + activity.
       */
      private void openMentionStickerProfile(StatusStickerOverlayView sticker) {
          String username = sticker.getMentionUsername();
          if (username == null || username.isEmpty()) { settleStickerReaction(sticker); return; }
          pauseProgress();
          com.google.firebase.database.FirebaseDatabase.getInstance()
                  .getReference("users").orderByChild("username").equalTo(username).limitToFirst(1)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  if (!snap.exists()) { settleStickerReaction(sticker); return; }
                  DataSnapshot userSnap = snap.getChildren().iterator().next();
                  String uid = userSnap.getKey();
                  String name = userSnap.child("name").getValue(String.class);
                  if (name == null || name.isEmpty()) name = username;
                  String photo = userSnap.child("profileImage").getValue(String.class);
                  if (photo == null || photo.isEmpty()) photo = userSnap.child("photoUrl").getValue(String.class);
                  boolean opened = false;
                  try {
                      Class<?> profileCls = Class.forName("com.callx.app.activities.UserProfileActivity");
                      Intent intent = new Intent(StatusViewerActivity.this, profileCls);
                      intent.putExtra("uid", uid);
                      intent.putExtra("name", name);
                      if (photo != null) intent.putExtra("photo", photo);
                      startActivity(intent);
                      opened = true; // onResume() will settle the sticker (shrink + resume) once we come back
                  } catch (Exception ignored2) {
                      // Profile activity unavailable at runtime — the sticker just stays inert.
                  }
                  if (!opened) settleStickerReaction(sticker);
              }
              @Override public void onCancelled(DatabaseError e) { settleStickerReaction(sticker); }
          });
      }

      /**
       * Opens the same Hashtag feed the X ("Twitter-like") tab uses for a
       * # Hashtag sticker. Loaded via reflection since feature-status has no
       * compile-time dependency on feature-x — pauses/resumes the story
       * progress bar around the activity's lifetime.
       */
      private void openHashtagStickerFeed(StatusStickerOverlayView sticker) {
          String tag = sticker.getHashtagTag();
          if (tag == null || tag.isEmpty()) { settleStickerReaction(sticker); return; }
          try {
              Class<?> hashtagCls = Class.forName("com.callx.app.search.XHashtagActivity");
              Intent intent = new Intent(this, hashtagCls);
              intent.putExtra("hashtag", tag);
              pauseProgress();
              startActivity(intent); // onResume() will settle the sticker (shrink + resume) once we come back
          } catch (Exception ignored) {
              // X module unavailable at runtime — the sticker just stays inert.
              settleStickerReaction(sticker);
          }
      }

      /**
       * Opens the 🔗 Link sticker's URL in the device's default browser. The
       * scheme was already normalised (https:// prepended if missing) back
       * at creation time in StatusStickerPickerSheet, so this is just a plain
       * ACTION_VIEW — no reflection needed since it's a system intent, not an
       * in-app screen.
       */
      private void openLinkSticker(StatusStickerOverlayView sticker) {
          String url = sticker.getLinkUrl();
          if (url == null || url.isEmpty()) { settleStickerReaction(sticker); return; }
          try {
              pauseProgress();
              startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
              // onResume() will settle the sticker (shrink + resume) once we come back from the browser
          } catch (Exception ignored) {
              // No app can handle this link — sticker just stays inert.
              settleStickerReaction(sticker);
          }
      }

      /**
       * Opens NewStatusActivity pre-loaded with the same ➕ Add Yours prompt so
       * this viewer can post their own story continuing the chain. Both
       * activities live in feature-status, so this is a direct reference
       * (no reflection needed, unlike the cross-module Mention/Hashtag taps).
       *
       * originUid/originName carry forward the *original* chain-starter's
       * identity across repeated taps — if the tapped sticker already has an
       * origin (meaning the current status is itself a chain reply), that
       * origin is preserved rather than being overwritten with this ownerUid.
       */
      private void openAddYoursComposer(StatusStickerOverlayView sticker) {
          String prompt = sticker.getAddYoursPrompt();
          if (prompt == null || prompt.isEmpty()) { settleStickerReaction(sticker); return; }
          String originUid  = sticker.getAddYoursOriginUid();
          String originName = sticker.getAddYoursOriginName();
          if (originUid == null || originUid.isEmpty()) {
              originUid  = ownerUid;
              originName = ownerName;
          }
          try {
              org.json.JSONObject o = new org.json.JSONObject();
              o.put("type", "addyours");
              o.put("prompt", prompt);
              o.put("originUid", originUid);
              o.put("originName", originName != null ? originName : "");

              Intent intent = new Intent(this, com.callx.app.compose.NewStatusActivity.class);
              intent.putExtra(com.callx.app.compose.NewStatusActivity.EXTRA_PREFILL_STICKER_JSON, o.toString());
              intent.putExtra(com.callx.app.compose.NewStatusActivity.EXTRA_PREFILL_TOAST,
                      "Adding to " + (originName != null && !originName.isEmpty() ? originName : "their") + "'s prompt");
              pauseProgress();
              startActivity(intent); // onResume() will settle the sticker (shrink + resume) once we come back
          } catch (Exception ignored) {
              // Malformed data — sticker just stays inert.
              settleStickerReaction(sticker);
          }
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
          if (musicPlayer != null) { try { if (musicPlayer.isPlaying()) musicPlayer.pause(); } catch (Exception ignored) {} }
          stopProgress();
      }
      private void resumeProgress() {
          if (!paused) return; paused = false;
          if (player != null) player.setPlayWhenReady(true);
          if (musicPlayer != null) { try { if (!musicPlayer.isPlaying()) musicPlayer.start(); } catch (Exception ignored) {} }
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
      // ── Sticker "tap to zoom, react to return" wiring ────────────────────
      // First tap on any interactive sticker (music/countdown/quiz/poll/
      // slider/question/etc.) enlarges it front-and-centre and pauses the
      // story so the viewer can read/answer it properly. Whatever counts as
      // that sticker's "reaction" then shrinks it back to exactly where the
      // poster placed it and resumes the story.
      private void armStickerZoomGate(StatusStickerOverlayView sticker) { armStickerZoomGate(sticker, null); }
      /** @param afterZoomed for one-shot stickers (question/music/mention/hashtag/
       *  link/add-yours) whose "reaction" IS opening another sheet/screen — runs
       *  once the zoom-in animation finishes. Pass null for stickers with their
       *  own inner controls (quiz/poll/slider/countdown), which just wait for the
       *  viewer's next tap on the real option once zoomed in. */
      private void armStickerZoomGate(StatusStickerOverlayView sticker, Runnable afterZoomed) {
          sticker.armViewerZoomGate(() -> {
              pauseProgress();
              showStickerZoomScrim();
              sticker.zoomToFront(binding.flStickerOverlay, afterZoomed);
          });
      }
      /** Call once a sticker's reaction is done — shrinks it back and resumes. */
      private void settleStickerReaction(StatusStickerOverlayView sticker) {
          hideStickerZoomScrim();
          sticker.restoreFromZoom(this::resumeProgress);
      }
      // ── Dim backdrop behind a zoomed-in sticker ──────────────────────────
      private View stickerZoomScrim;
      private void showStickerZoomScrim() {
          if (stickerZoomScrim != null) return;
          View scrim = new View(this);
          scrim.setBackgroundColor(0xCC000000);
          scrim.setAlpha(0f);
          scrim.setClickable(true); // swallow taps outside the zoomed sticker (no next/previous while zoomed)
          scrim.setOnClickListener(v -> {
              StatusStickerOverlayView z = findZoomedSticker();
              if (z != null) settleStickerReaction(z); // tapping outside dismisses the zoom early
          });
          binding.flStickerOverlay.addView(scrim, new FrameLayout.LayoutParams(
                  FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
          scrim.animate().alpha(1f).setDuration(200).start();
          stickerZoomScrim = scrim;
      }
      private void hideStickerZoomScrim() {
          if (stickerZoomScrim == null) return;
          final View scrim = stickerZoomScrim;
          stickerZoomScrim = null;
          scrim.animate().alpha(0f).setDuration(180)
                  .withEndAction(() -> binding.flStickerOverlay.removeView(scrim)).start();
      }
      // ── Navigation ────────────────────────────────────────────────────────
      private void next() {
          releasePlayer(); stopProgress();
          idx++;
          showCurrent();
      }
      private void previous() {
          releasePlayer(); stopProgress();
          if (idx == 0) {
              // Instagram-style: tapping/swiping back on the very first
              // segment jumps to the previous owner/highlight in the queue
              // (if any) instead of just re-playing the same first segment.
              goToPreviousQueueEntry();
              return;
          }
          idx = idx - 1;
          showCurrent();
      }
      // ── Queue navigation (Instagram-style continuous playback) ─────────────
      private boolean hasNextInQueue() {
          if (isHighlightMode) return !queueAlbumIds.isEmpty() && queuePos < queueAlbumIds.size() - 1;
          return !queueOwnerUids.isEmpty() && queuePos < queueOwnerUids.size() - 1;
      }
      private boolean hasPreviousInQueue() {
          if (isHighlightMode) return !queueAlbumIds.isEmpty() && queuePos > 0;
          return !queueOwnerUids.isEmpty() && queuePos > 0;
      }
      /** Called whenever the current owner/album's last segment finishes
       *  (auto-play timer) or the viewer taps/swipes past the end. Instagram
       *  never just closes here while there's more queued up — playback
       *  continues straight into the next person's (or next highlight's)
       *  stories. Only closes once the whole queue is exhausted, which is
       *  also the correct behavior when no queue was passed in at all
       *  (single-owner / single-album viewing, e.g. deep links). */
      private void goToNextQueueEntryOrFinish() {
          if (!hasNextInQueue()) { finish(); return; }
          switchQueueEntry(queuePos + 1);
      }
      /** Called when swiping/tapping back past the first segment. If there's
       *  nothing before this entry in the queue, just restarts the current
       *  first segment (there's nowhere else to go). */
      private void goToPreviousQueueEntry() {
          if (!hasPreviousInQueue()) { showCurrent(); return; }
          switchQueueEntry(queuePos - 1);
      }
      /** Skips straight to the next/previous owner or highlight in the
       *  queue, abandoning whatever's left of the current one — this is
       *  the explicit horizontal-swipe gesture (see onFling below), as
       *  opposed to the natural "ran out of segments" auto-advance. */
      private void skipToAdjacentQueueEntry(boolean forward) {
          if (forward) goToNextQueueEntryOrFinish();
          else goToPreviousQueueEntry();
      }
      /** Tears down the current owner/album's on-screen state and loads the
       *  queue entry at the given position, resetting playback to its first
       *  segment. */
      private void switchQueueEntry(int pos) {
          releasePlayer(); stopProgress();
          items.clear();
          idx = 0;
          viewStartTime = 0;
          reactionBurstPlayedFor.clear();
          hideAllContent();
          queuePos = pos;
          if (isHighlightMode) {
              highlightAlbumId = queueAlbumIds.get(pos);
              ownerName = pos < queueAlbumNames.size() ? queueAlbumNames.get(pos) : highlightAlbumId;
              binding.tvOwner.setText(ownerName != null ? ownerName : "Highlight");
              loadHighlightAlbum(ownerUid);
          } else {
              ownerUid  = queueOwnerUids.get(pos);
              ownerName = pos < queueOwnerNames.size() ? queueOwnerNames.get(pos) : ownerUid;
              binding.tvOwner.setText(ownerName != null ? ownerName : "Status");
              if (StatusCloseFriendsManager.isCloseFriend(this, ownerUid))
                  binding.tvOwner.setText("\u2B50 " + (ownerName != null ? ownerName : "Status"));
              load(ownerUid);
          }
      }
      // ── Swipe down / up / left / right ───────────────────────────────────
      private void setupSwipeDownGesture() {
          swipeDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
              @Override public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float vx, float vy) {
                  if (e1 == null) return false;
                  float dx = e2.getRawX() - e1.getRawX();
                  float dy = e2.getRawY() - e1.getRawY();
                  // BUGFIX: previously this only ever checked dy/vy, with no
                  // comparison against dx/vx. A fast horizontal swipe almost
                  // always has *some* vertical drift, so it was easily big
                  // enough to cross the old dy>120 threshold on its own and
                  // get misread as "swipe down to close" — the story closed
                  // instead of advancing. Now each direction only wins when
                  // it's actually the dominant one.
                  boolean horizontalDominant = Math.abs(dx) > Math.abs(dy);
                  if (horizontalDominant) {
                      // Swipe LEFT/RIGHT — Instagram-style: jump straight to
                      // the next/previous person's (or next/previous
                      // highlight's) stories, skipping whatever's left of
                      // the current one.
                      if (dx < -120 && Math.abs(vx) > 100) { skipToAdjacentQueueEntry(true); return true; }
                      if (dx > 120 && Math.abs(vx) > 100) { skipToAdjacentQueueEntry(false); return true; }
                      return false;
                  }
                  if (dy > 120 && Math.abs(vy) > 100) { finishWithAnimation(); return true; }
                  // Swipe UP — WhatsApp/Instagram-style quick reaction picker
                  // (see screenshot ref): a large emoji grid appears above the
                  // reply bar and the reply box gets keyboard focus, exactly
                  // like swiping up on a status there does.
                  if (dy < -120 && vy < -100) { openQuickReactionOverlay(); return true; }
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
      /**
       * WhatsApp-style quick-like: a single tap on the heart icon sends a ❤️
       * reaction immediately — no bottom sheet, no picker — with a small
       * bounce + a one-shot floating-heart burst for feedback. Tapping again
       * removes the like (toggle), same as tapping the same emoji twice in
       * the full picker already does via StatusReactionBottomSheet.
       */
      /**
       * Swipe-up quick reaction overlay — WhatsApp/Instagram-style: a dark
       * scrim with a big 3x2 emoji grid appears above the reply bar, and the
       * reply box gets keyboard focus at the same time. Tapping an emoji
       * reacts immediately using the same Firebase write + chat-message path
       * as the quick-like heart; tapping the scrim outside the card dismisses
       * without reacting.
       */
      private void setupQuickReactionOverlay() {
          if (binding.flQuickReactionOverlay == null) return;
          // Can't react to your own status — same restriction as btn_react/btn_like_heart.
          if (myUid != null && myUid.equals(ownerUid)) return;
          View.OnClickListener emojiClick = v -> {
              Object tag = v.getTag();
              if (tag == null) { closeQuickReactionOverlay(); return; }
              sendQuickReaction(tag.toString());
              closeQuickReactionOverlay();
          };
          if (binding.tvQr1 != null) binding.tvQr1.setOnClickListener(emojiClick);
          if (binding.tvQr2 != null) binding.tvQr2.setOnClickListener(emojiClick);
          if (binding.tvQr3 != null) binding.tvQr3.setOnClickListener(emojiClick);
          if (binding.tvQr4 != null) binding.tvQr4.setOnClickListener(emojiClick);
          if (binding.tvQr5 != null) binding.tvQr5.setOnClickListener(emojiClick);
          if (binding.tvQr6 != null) binding.tvQr6.setOnClickListener(emojiClick);
          // Tap anywhere on the dim scrim outside the card dismisses without reacting.
          binding.flQuickReactionOverlay.setOnClickListener(v -> closeQuickReactionOverlay());
          // Swallow taps on the card itself so they don't bubble to the scrim above.
          if (binding.llQuickReactionCard != null) binding.llQuickReactionCard.setOnClickListener(v -> {});
      }
      private void openQuickReactionOverlay() {
          if (binding.flQuickReactionOverlay == null) return;
          if (myUid != null && myUid.equals(ownerUid)) return; // owner can't react to own status
          pauseProgress();
          binding.flQuickReactionOverlay.setAlpha(0f);
          binding.flQuickReactionOverlay.setVisibility(View.VISIBLE);
          binding.flQuickReactionOverlay.animate().alpha(1f).setDuration(150).start();
          if (binding.llQuickReactionCard != null) {
              binding.llQuickReactionCard.setScaleX(0.85f);
              binding.llQuickReactionCard.setScaleY(0.85f);
              binding.llQuickReactionCard.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
          }
          // Bring up the reply keyboard at the same time, matching the
          // reference swipe-up behaviour.
          if (binding.etReply != null) {
              binding.etReply.requestFocus();
              android.view.inputmethod.InputMethodManager imm =
                      (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
              if (imm != null) imm.showSoftInput(binding.etReply, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
          }
      }
      private void closeQuickReactionOverlay() {
          if (binding.flQuickReactionOverlay == null) return;
          binding.flQuickReactionOverlay.animate().alpha(0f).setDuration(120)
                  .withEndAction(() -> binding.flQuickReactionOverlay.setVisibility(View.GONE)).start();
          android.view.inputmethod.InputMethodManager imm =
                  (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          if (imm != null && binding.etReply != null) imm.hideSoftInputFromWindow(binding.etReply.getWindowToken(), 0);
          if (binding.etReply != null) binding.etReply.clearFocus();
          resumeProgress();
      }
      /** Shared by the emoji grid and can be reused anywhere a one-tap emoji reaction is needed. */
      private void sendQuickReaction(String emoji) {
          StatusItem current = idx < items.size() ? items.get(idx) : null;
          if (current == null || myUid == null) return;
          String existing = current.getReaction(myUid);
          boolean isNew = !emoji.equals(existing);
          StatusSeenTracker.reactTo(current.ownerUid, current.id, emoji, existing, newEmoji -> {
              if (current.reactions == null) current.reactions = new HashMap<>();
              if (newEmoji == null) current.reactions.remove(myUid);
              else current.reactions.put(myUid, newEmoji);
              updateSeenByInfo(current);
          });
          if (isNew) {
              sendReactionToChat(current, emoji);
              if (binding.reactionBurstOverlay != null) binding.reactionBurstOverlay.playBurst(emoji);
          }
      }
      private void setupLikeHeartButton() {
          if (binding.btnLikeHeart == null) return;
          if (myUid != null && myUid.equals(ownerUid)) {
              binding.btnLikeHeart.setVisibility(View.GONE);
              return;
          }
          binding.btnLikeHeart.setOnClickListener(v -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null || myUid == null) return;
              String existing = current.getReaction(myUid);
              boolean alreadyLiked = LIKE_EMOJI.equals(existing);
              // Small bounce for tactile feedback on every tap, like/unlike alike.
              v.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120)
                      .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start())
                      .start();
              // Same Firebase write StatusReactionBottomSheet uses — toggles off
              // automatically when the emoji passed in matches the existing one.
              StatusSeenTracker.reactTo(current.ownerUid, current.id, LIKE_EMOJI, existing, newEmoji -> {
                  if (current.reactions == null) current.reactions = new HashMap<>();
                  if (newEmoji == null) current.reactions.remove(myUid);
                  else current.reactions.put(myUid, newEmoji);
                  updateSeenByInfo(current);
              });
              if (!alreadyLiked) {
                  // Only announce a NEW like in the 1:1 chat, same as the full
                  // picker does — not on toggle-off.
                  sendReactionToChat(current, LIKE_EMOJI);
                  if (binding.reactionBurstOverlay != null) binding.reactionBurstOverlay.playBurst(LIKE_EMOJI);
              }
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
      /**
       * Repost button — lets a viewer repost the current status to their own story.
       * Only visible when owner's allowSharing == true. On click, launches
       * StoryReshareActivity with contentType="status" carrying the full attribution.
       */
      private void setupRepostButton() {
          binding.btnRepost.setOnClickListener(v -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null) return;
              if (!current.allowSharing) {
                  Toast.makeText(this, ownerName + " has disabled sharing for this status",
                          Toast.LENGTH_SHORT).show();
                  return;
              }
              pauseProgress();
              android.content.Intent intent = com.callx.app.utils.StatusReshareHelper
                      .buildReshareStatusIntent(this, current, ownerName, ownerUid);
              startActivity(intent);
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
          if (isHighlightMode) { showHighlightOwnerMoreMenu(); return; }
          StatusItem current = idx < items.size() ? items.get(idx) : null;
          String[] opts = {"Who viewed this", "Delete this status", "Archive status", "Add to Highlights", "Analytics", "Cancel"};
          AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
              .setItems(opts, (d, w) -> {
                  if (w == 0) {
                      // FIX: "Who viewed this" — open SeenByBottomSheet directly from menu
                      if (current != null) {
                          StatusSeenByBottomSheet.show(this, current, this::resumeProgress);
                      } else resumeProgress();
                  } else if (w == 1 && current != null && current.id != null) {
                      String previewUrl = current.thumbnailUrl != null ? current.thumbnailUrl : current.mediaUrl;
                      boolean inHighlights = current.isHighlighted
                              || (current.highlightAlbumIds != null && !current.highlightAlbumIds.isEmpty());
                      StatusDeleteConfirmBottomSheet.show(this, current.type, previewUrl, inHighlights, alsoRemoveFromHighlights -> {
                          if (alsoRemoveFromHighlights) {
                              StatusHighlightManager.removeStatusFromAllHighlights(ownerUid, current);
                          }
                          StatusSeenTracker.deleteStatus(ownerUid, current.id);
                          items.remove(idx);
                          Toast.makeText(this, alsoRemoveFromHighlights
                                  ? "Deleted & removed from Highlights" : "Deleted", Toast.LENGTH_SHORT).show();
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
              .create());
      }
      /** v39 — Owner's "..." menu while browsing INSIDE a Highlight album.
       *  Replaces the normal delete/archive/add-to-highlight set with
       *  highlight-specific actions: remove just this item, rename the
       *  album, or delete the whole album — the previously-missing
       *  "Highlight editing & settings" system. */
      private void showHighlightOwnerMoreMenu() {
          StatusItem current = idx < items.size() ? items.get(idx) : null;
          String[] opts = {"Who viewed this", "Remove from Highlight", "Rename Highlight",
                  "Set as Cover", "Delete Entire Highlight", "Cancel"};
          AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
              .setItems(opts, (d, w) -> {
                  if (w == 0) {
                      if (current != null) StatusSeenByBottomSheet.show(this, current, this::resumeProgress);
                      else resumeProgress();
                  } else if (w == 1 && current != null && current.id != null) {
                      StatusHighlightManager.removeFromHighlight(ownerUid, highlightAlbumId, current.id);
                      items.remove(idx);
                      Toast.makeText(this, "Removed from Highlight", Toast.LENGTH_SHORT).show();
                      if (items.isEmpty()) { finish(); return; }
                      idx = Math.min(idx, items.size() - 1);
                      buildSegmentBars(); stopProgress(); showCurrent();
                  } else if (w == 2) {
                      promptRenameHighlightAlbum();
                  } else if (w == 3 && current != null) {
                      String coverUrl = current.thumbnailUrl != null ? current.thumbnailUrl : current.mediaUrl;
                      StatusHighlightManager.setAlbumCover(ownerUid, highlightAlbumId, current.id, coverUrl);
                      Toast.makeText(this, "Cover updated \u2713", Toast.LENGTH_SHORT).show();
                      resumeProgress();
                  } else if (w == 4) {
                      confirmDeleteHighlightAlbum();
                  } else {
                      resumeProgress();
                  }
              })
              .setOnCancelListener(d -> resumeProgress())
              .create());
      }
      private void promptRenameHighlightAlbum() {
          EditText input = new EditText(this);
          input.setText(ownerName);
          input.setSelection(input.getText() != null ? input.getText().length() : 0);
          AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
              .setTitle("Rename Highlight")
              .setView(input)
              .setPositiveButton("Save", (d, w) -> {
                  String newName = input.getText() != null ? input.getText().toString().trim() : "";
                  if (!newName.isEmpty()) {
                      StatusHighlightManager.renameAlbum(ownerUid, highlightAlbumId, newName);
                      ownerName = newName;
                      binding.tvOwner.setText(ownerName);
                      Toast.makeText(this, "Renamed \u2713", Toast.LENGTH_SHORT).show();
                  }
                  resumeProgress();
              })
              .setNegativeButton("Cancel", (d, w) -> resumeProgress())
              .setOnCancelListener(d -> resumeProgress())
              .create());
      }
      private void confirmDeleteHighlightAlbum() {
          AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
              .setTitle("Delete this Highlight?")
              .setMessage("All statuses in \"" + ownerName + "\" will be removed from Highlights. The original stories (if still active) won't be affected.")
              .setPositiveButton("Delete", (d, w) -> {
                  StatusHighlightManager.deleteAlbum(ownerUid, highlightAlbumId);
                  Toast.makeText(this, "Highlight deleted", Toast.LENGTH_SHORT).show();
                  finish();
              })
              .setNegativeButton("Cancel", (d, w) -> resumeProgress())
              .setOnCancelListener(d -> resumeProgress())
              .create());
      }
      private void showViewerMoreMenu() {
          String muteLabel = StatusMuteManager.isMuted(this, ownerUid)
                  ? "Unmute " + ownerName : "Mute " + ownerName;
          String[] opts = {muteLabel, "Download", "Forward", "Report", "Cancel"};
          AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
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
              .create());
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
              if (musicPlayer != null) { try { musicPlayer.setVolume(isMuted ? 0f : 1f, isMuted ? 0f : 1f); } catch (Exception ignored) {} }
              binding.btnMute.setImageResource(isMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
          });
      }
      // ── Seen-by info ──────────────────────────────────────────────────────
      private static final String LIKE_EMOJI = "\u2764\uFE0F"; // ❤️
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
          updateLikeHeartIcon(s);
      }
      /**
       * Instagram-style bottom-left "recent comments" overlay. Now shown to
       * EVERY viewer (not just the owner) — matches the requested behavior of
       * a public comment strip under the story, like Instagram's public post/
       * reel comments, rather than a private-reply indicator. Attaches a live
       * listener on status/{ownerUid}/{statusId}/replies for the currently-
       * shown segment so a comment posted while someone is watching shows up
       * immediately, and detaches the previous segment's listener first to
       * avoid leaking one listener per swipe.
       */
      private void updateLastReplyOverlay(StatusItem s) {
          detachRepliesListener();
          if (binding.llLastReplyBubble == null) return;
          if (s == null || s.id == null || s.id.isEmpty()) {
              binding.llLastReplyBubble.setVisibility(View.GONE);
              return;
          }
          repliesListenerRef = FirebaseUtils.getStatusRepliesRef(ownerUid, s.id);
          repliesListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  Map<String, StatusItem.ReplyPreview> repliesMap = new LinkedHashMap<>();
                  for (DataSnapshot child : snap.getChildren()) {
                      StatusItem.ReplyPreview r = child.getValue(StatusItem.ReplyPreview.class);
                      if (r != null) repliesMap.put(child.getKey(), r);
                  }
                  // Keep the StatusItem in sync so a tap on the overlay opens
                  // StatusRepliesBottomSheet with the exact same data, no re-fetch.
                  s.replies = repliesMap;
                  List<StatusItem.ReplyPreview> newestFirst = new ArrayList<>(repliesMap.values());
                  Collections.sort(newestFirst, (a, b) -> {
                      long ta = a.timestamp != null ? a.timestamp : 0;
                      long tb = b.timestamp != null ? b.timestamp : 0;
                      return Long.compare(tb, ta);
                  });
                  bindLastReplyBubble(s, newestFirst);
              }
              @Override public void onCancelled(DatabaseError e) { }
          };
          repliesListenerRef.addValueEventListener(repliesListener);
      }
      /**
       * Binds up to the 3 most recent commenters as an overlapping avatar stack
       * (newest frontmost/rightmost, matching Instagram's "recent repliers"
       * cluster) plus the newest comment's text, then auto-hides the whole
       * overlay a few seconds later with a fade — same "glance and it goes away"
       * behavior as Instagram's own story reply indicator. A tap on the bubble
       * (see {@link #updateLastReplyOverlay}'s click listener setup below)
       * re-opens it immediately via the full-list bottom sheet, and cancels
       * any pending auto-hide.
       */
      private void bindLastReplyBubble(StatusItem s, List<StatusItem.ReplyPreview> newestFirst) {
          if (binding.llLastReplyBubble == null) return;
          if (replyOverlayAutoHide != null) handler.removeCallbacks(replyOverlayAutoHide);
          if (newestFirst.isEmpty()) {
              binding.llLastReplyBubble.setVisibility(View.GONE);
              binding.llLastReplyBubble.setAlpha(1f);
              return;
          }
          android.widget.ImageView[] slots = {
              binding.ivLastReplyAvatar1, binding.ivLastReplyAvatar2, binding.ivLastReplyAvatar3
          };
          // slots[2] (rightmost/top-of-stack) = newest; older ones fill leftward.
          for (int slot = 0; slot < slots.length; slot++) {
              int replyIdx = slots.length - 1 - slot; // 2,1,0
              if (slots[slot] == null) continue;
              if (replyIdx < newestFirst.size()) {
                  StatusItem.ReplyPreview r = newestFirst.get(replyIdx);
                  slots[slot].setVisibility(View.VISIBLE);
                  if (r.avatarUrl != null && !r.avatarUrl.isEmpty())
                      Glide.with(this).load(r.avatarUrl).into(slots[slot]);
                  else
                      slots[slot].setImageResource(R.drawable.ic_person);
              } else {
                  slots[slot].setVisibility(View.GONE);
              }
          }
          StatusItem.ReplyPreview latest = newestFirst.get(0);
          if (binding.tvLastReplyText != null) {
              String who = latest.name != null && !latest.name.isEmpty() ? latest.name : "Someone";
              binding.tvLastReplyText.setText(who + ": " + (latest.text != null ? latest.text : ""));
          }
          binding.llLastReplyBubble.animate().cancel();
          binding.llLastReplyBubble.setAlpha(1f);
          binding.llLastReplyBubble.setVisibility(View.VISIBLE);
          binding.llLastReplyBubble.setOnClickListener(v -> {
              if (replyOverlayAutoHide != null) handler.removeCallbacks(replyOverlayAutoHide);
              pauseProgress();
              com.callx.app.interactions.StatusRepliesBottomSheet.show(this, ownerUid, myUid, s, this::resumeProgress);
          });
          replyOverlayAutoHide = () -> {
              if (binding.llLastReplyBubble == null) return;
              binding.llLastReplyBubble.animate()
                  .alpha(0f).setDuration(300)
                  .withEndAction(() -> {
                      if (binding.llLastReplyBubble != null)
                          binding.llLastReplyBubble.setVisibility(View.GONE);
                  }).start();
          };
          handler.postDelayed(replyOverlayAutoHide, REPLY_OVERLAY_AUTO_HIDE_MS);
      }
      private void detachRepliesListener() {
          if (repliesListenerRef != null && repliesListener != null)
              repliesListenerRef.removeEventListener(repliesListener);
          repliesListenerRef = null;
          repliesListener = null;
          if (replyOverlayAutoHide != null) {
              handler.removeCallbacks(replyOverlayAutoHide);
              replyOverlayAutoHide = null;
          }
      }
      /** Keeps the quick-like heart filled/outline in sync with whether I've already ❤️'d this status. */
      private void updateLikeHeartIcon(StatusItem s) {
          if (binding.btnLikeHeart == null) return;
          boolean liked = s != null && LIKE_EMOJI.equals(s.getReaction(myUid));
          binding.btnLikeHeart.setImageResource(liked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
          binding.btnLikeHeart.setContentDescription(liked ? "Remove like" : "Like status");
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
          if (isHighlightMode) { binding.tvExpiryLabel.setVisibility(View.GONE); return; }
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
              data.put("replyToSenderName", StatusReplyBottomSheet.statusReplyLabel(ownerName));
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
      /**
       * Instagram-style one-shot floating-emoji replay: checks whether the
       * status currently on screen has any recorded reactions
       * (statusReactions/{ownerUid}/{statusId}/*, written by
       * sendReactionToChat()) and, if so, plays the burst on
       * reaction_burst_overlay. Fires at most once per status per viewer
       * session (see reactionBurstPlayedFor). Cheap single-value read —
       * no listener kept alive, since the burst is a one-shot replay, not
       * a live indicator.
       */
      private void playReactionBurstIfAny(StatusItem s) {
          if (s == null || s.id == null || s.id.isEmpty() || ownerUid == null) return;
          if (!reactionBurstPlayedFor.add(s.id)) return; // already played this session
          com.callx.app.reaction.FloatingReactionOverlayView overlay = binding.reactionBurstOverlay;
          if (overlay == null) return;
          FirebaseDatabase.getInstance().getReference("statusReactions")
                  .child(ownerUid).child(s.id)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
                      @Override public void onDataChange(@NonNull DataSnapshot snap) {
                          if (!snap.exists()) return;
                          List<String> emojis = new ArrayList<>();
                          for (DataSnapshot child : snap.getChildren()) {
                              String emoji = child.getValue(String.class);
                              if (emoji != null && !emoji.isEmpty()) emojis.add(emoji);
                          }
                          if (!emojis.isEmpty()) overlay.playBurst(emojis);
                      }
                      @Override public void onCancelled(@NonNull DatabaseError e) {}
                  });
      }

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
          data.put("replyToSenderName",   StatusReplyBottomSheet.statusReplyLabel(ownerName));
          data.put("replyToId",           "status_" + (reactedStatus.id != null ? reactedStatus.id : "unknown"));
          String thumb = reactedStatus.thumbnailUrl != null ? reactedStatus.thumbnailUrl
                  : ("image".equals(reactedStatus.type) ? reactedStatus.mediaUrl : null);
          if (thumb != null) data.put("replyToMediaUrl", thumb);
          FirebaseUtils.getMessagesRef(chatId).child(msgId).setValue(data);

          // NEW: mirrors reels' reelReactions/{reelId}/{uid}=emoji pattern —
          // lets the status owner's replay of this status (or the reel it
          // was shared from) trigger the Instagram-style floating-emoji
          // burst (see playReactionBurstIfAny()), same as reel reactions
          // already do via ReelSocialController#loadLiveReactionCounts().
          if (reactedStatus.id != null && !reactedStatus.id.isEmpty()) {
              FirebaseDatabase.getInstance().getReference("statusReactions")
                      .child(ownerUid).child(reactedStatus.id).child(myUid).setValue(emoji);
          }
      }
      private int dpToPx(int dp) {
          return Math.round(dp * getResources().getDisplayMetrics().density);
      }
      private void releasePlayer() {
          if (player != null) { player.release(); player = null; }
          releaseMusicPlayer();
          if (binding != null && binding.reactionBurstOverlay != null) binding.reactionBurstOverlay.stop();
      }
  }