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
      // Album-wide opt-back-in flag for the level-stabilizer, read once from
      // statusHighlightMeta/{owner}/{albumId}/stabilizerEnabled when a
      // Highlight is opened (see loadHighlightAlbum()). Only consulted while
      // isHighlightMode is true; ordinary Stories use each item's own
      // StatusItem.stabilizerEnabled instead. Owner sets this from the
      // long-press "Level Stabilizer" option in StatusHighlightSettingsBottomSheet.
      private boolean highlightStabilizerEnabled;
      private String targetStatusId;
      private ActivityStatusViewerBinding binding;
      private final List<StatusItem> items         = new ArrayList<>();
      private final List<String>     seenInSession  = new ArrayList<>();
      // Instagram-style floating-emoji replay: guards against re-triggering
      // the burst every time showCurrent() re-runs for the same status
      // (e.g. swiping back to a previously-viewed segment in this session).
      private final java.util.Set<String> reactionBurstPlayedFor = new java.util.HashSet<>();
      private int     idx         = 0;
      private ExoPlayer player;                    // ONE instance reused for every video status (destroyed in onDestroy)
      private Player.Listener playerListener;      // the current video's listener (swapped per status)
      // Separate audio-only player for a 🎵 music sticker's linked preview
      // clip — independent of `player` above (which only ever holds the
      // status's own video track). Autoplays as soon as the status carrying
      // the sticker is shown, mirrors Instagram/WhatsApp behaviour.
      private android.media.MediaPlayer musicPlayer;
      private final Handler  handler      = new Handler(Looper.getMainLooper());
      // Progress is driven by Choreographer (vsync) + SystemClock, not a 50 ms Handler poll:
      // smooth on 60/120 Hz panels, no wake-ups while paused, and — unlike ValueAnimator —
      // immune to the system "animator duration scale" (battery saver / dev options set it to 0).
      private boolean         progressActive;
      private long            progTotalMs, progStartElapsed, progStartUptime;
      private boolean        paused       = false;
      private long           remainingMs  = 0;
      private boolean        isMuted      = false;
      private StatusStickerOverlayView musicStickerView; // current status's music sticker (bars/ticker follow playback)
      private long           viewStartTime = 0;
      private String myUid, ownerUid, ownerName;
      private final List<ProgressBar> segmentBars = new ArrayList<>();
      // ── Latest-reply overlay (owner-only, Instagram-style) ─────────────────
      private DatabaseReference repliesListenerRef;
      private ValueEventListener repliesListener;
      private Runnable          replyOverlayAutoHide;
      private static final long REPLY_OVERLAY_AUTO_HIDE_MS = 4000;
      private GestureDetector swipeDetector;
      // Instagram-style "Spin View" — pans the Story media (never the whole
      // screen) in response to phone rotation. Fully independent of `player`
      // (see StorySpinViewController's class doc). Lazily built in onCreate,
      // started/stopped with the activity's own visibility lifecycle.
      private StorySpinViewController spinView;
      // ── Edge-to-edge / immersive / cutout insets ──────────────────────────
      private void setupEdgeToEdge() {
          androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
          if (android.os.Build.VERSION.SDK_INT >= 28) {
              // draw into the notch / punch-hole area (black bg there) instead of a black bar
              getWindow().getAttributes().layoutInDisplayCutoutMode =
                      WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
          }
          hideSystemBars();
          final float d = getResources().getDisplayMetrics().density;
          final int cardTopGap = (int) (12 * d), replyBottomBase = (int) (12 * d);
          androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
              // Status bars are hidden (immersive) → plain getInsets() would be 0 for them. Use the
              // visibility-ignoring variant so the story card always starts BELOW where the status
              // bar / notch is, like Instagram's card, instead of jamming against the screen edge.
              androidx.core.graphics.Insets top = insets.getInsetsIgnoringVisibility(
                      androidx.core.view.WindowInsetsCompat.Type.systemBars()
                              | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
              androidx.core.graphics.Insets bottom = insets.getInsets(
                      androidx.core.view.WindowInsetsCompat.Type.systemBars()
                              | androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                              | androidx.core.view.WindowInsetsCompat.Type.ime()); // keyboard lifts the reply bar (card shrinks)
              // story card clears the notch / status bar (header lives INSIDE the card now)
              android.view.ViewGroup.MarginLayoutParams cardLp =
                      (android.view.ViewGroup.MarginLayoutParams) binding.flStoryCard.getLayoutParams();
              if (cardLp.topMargin != top.top + cardTopGap) {
                  cardLp.topMargin = top.top + cardTopGap;
                  binding.flStoryCard.setLayoutParams(cardLp);
              }
              // reply bar (below the card) clears the gesture-nav bar (and keyboard)
              binding.llReplyBar.setPadding(binding.llReplyBar.getPaddingLeft(),
                      binding.llReplyBar.getPaddingTop(), binding.llReplyBar.getPaddingRight(),
                      replyBottomBase + bottom.bottom);
              return insets;
          });
          androidx.core.view.ViewCompat.requestApplyInsets(binding.getRoot());
      }
      private void hideSystemBars() {
          androidx.core.view.WindowInsetsControllerCompat c =
                  androidx.core.view.WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
          c.setSystemBarsBehavior(
                  androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
          c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars());
      }
      /** Dialogs / bottom sheets / keyboard can bring the bars back — hide them again when we regain focus. */
      @Override public void onWindowFocusChanged(boolean hasFocus) {
          super.onWindowFocusChanged(hasFocus);
          if (hasFocus) hideSystemBars();
      }
      // ── v508: viewer's own sticker answers — memory cache + deferred (post-first-frame) read ──
      // quiz / poll / slider / countdown each used to fire a Firebase read synchronously while the
      // status was being rendered. The answer is per (owner,status,sticker,me) and only changes
      // when *this* viewer answers, so it is cached for the process lifetime (updated on answer)
      // and, on a miss, fetched after the first frame instead of during it.
      private static final java.util.concurrent.ConcurrentHashMap<String, Object> STICKER_OWN_STATE =
              new java.util.concurrent.ConcurrentHashMap<>();
      private static final Object NO_STATE = new Object();        // viewer hasn't answered yet
      private static final Object VOTED_UNKNOWN = new Object();   // record exists but value unreadable
      /** soundId -> resolved preview URL, so a music sticker seen twice never re-reads Firebase. */
      private static final java.util.concurrent.ConcurrentHashMap<String, String> SOUND_URL_CACHE =
              new java.util.concurrent.ConcurrentHashMap<>();
      private interface SnapParser { Object parse(DataSnapshot snap); }
      private interface StateApply { void apply(Object state); }
      private static void putOwnStickerState(String key, Object state) {
          if (STICKER_OWN_STATE.size() > 400) STICKER_OWN_STATE.clear(); // tiny entries; just keep it bounded
          STICKER_OWN_STATE.put(key, state != null ? state : NO_STATE);
      }
      private static String stickerStateKey(String type, String ownerUid, String statusId, int stickerIndex, String myUid) {
          return type + "|" + ownerUid + "|" + statusId + "|" + stickerIndex + "|" + myUid;
      }
      private void loadOwnStickerState(final int seq, final String key, final DatabaseReference ref,
                                       final SnapParser parser, final StateApply apply) {
          final Object hit = STICKER_OWN_STATE.get(key);
          if (hit != null) { apply.apply(hit); return; } // cached → zero network, applied this frame
          deferAfterFirstFrame(seq, () -> ref.addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  Object st = snap.exists() ? parser.parse(snap) : NO_STATE;
                  if (st == null) st = NO_STATE;
                  putOwnStickerState(key, st);
                  if (isFinishing() || isDestroyed()) return;
                  apply.apply(st);
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          }));
      }
      // ── v507: lazily inflated layers (ViewStub) + hardware layer during animation ──
      private FrameLayout stickerOverlayView;     // null until first sticker / error card
      private FrameLayout textLayerView;          // null until first text status
      private TextView    textStatusTv;
      private View        reshareContainerView;   // null until first reshared status
      private View        quickReactionOverlayView; // null until first swipe-up-to-react
      private View        quickReactionCardView;
      private boolean     columnHwLayerOn = false;
      // v508: last-reply bubble is a ViewStub (only inflated once a status really has replies)
      private View        lastReplyBubbleView;
      private android.widget.ImageView[] lastReplyAvatars;
      private TextView    lastReplyTextTv;
      // v508: the hidden Download/Forward/Repost/Stabilizer/React ImageButtons are gone — they were
      // never visible, only click proxies. Their click bodies are plain Runnables now.
      private Runnable actDownload, actForward, actRepost, actStabilizer, actReact;
      private static void runAct(Runnable r) { if (r != null) r.run(); }

      private FrameLayout stickerOverlay() {
          if (stickerOverlayView == null) stickerOverlayView = (FrameLayout) binding.stubStickerOverlay.inflate();
          return stickerOverlayView;
      }
      private FrameLayout textLayer() {
          if (textLayerView == null) {
              textLayerView = (FrameLayout) binding.stubTextStatus.inflate();
              textStatusTv  = textLayerView.findViewById(R.id.tv_text_status);
          }
          return textLayerView;
      }
      private View lastReplyBubble() {
          if (lastReplyBubbleView == null) {
              final View b = binding.stubLastReplyBubble.inflate();
              lastReplyBubbleView = b;
              lastReplyAvatars = new android.widget.ImageView[] {
                      b.findViewById(R.id.iv_last_reply_avatar_1),
                      b.findViewById(R.id.iv_last_reply_avatar_2),
                      b.findViewById(R.id.iv_last_reply_avatar_3) };
              lastReplyTextTv = b.findViewById(R.id.tv_last_reply_text);
          }
          return lastReplyBubbleView;
      }
      private View reshareContainer() {
          if (reshareContainerView == null) reshareContainerView = binding.stubReshareCard.inflate();
          return reshareContainerView;
      }
      private boolean isQuickReactionOverlayShown() {
          return quickReactionOverlayView != null && quickReactionOverlayView.getVisibility() == View.VISIBLE;
      }
      /** Inflates the swipe-up emoji overlay on first use and wires its listeners once. */
      private View ensureQuickReactionOverlay() {
          if (quickReactionOverlayView != null) return quickReactionOverlayView;
          final View ov = binding.stubQuickReaction.inflate();
          quickReactionOverlayView = ov;
          quickReactionCardView = ov.findViewById(R.id.ll_quick_reaction_card);
          View.OnClickListener emojiClick = v -> {
              Object tag = v.getTag();
              if (tag == null) { closeQuickReactionOverlay(); return; }
              sendQuickReaction(tag.toString());
              closeQuickReactionOverlay();
          };
          final int[] ids = { R.id.tv_qr_1, R.id.tv_qr_2, R.id.tv_qr_3, R.id.tv_qr_4, R.id.tv_qr_5, R.id.tv_qr_6 };
          for (int id : ids) {
              View b = ov.findViewById(id);
              if (b != null) b.setOnClickListener(emojiClick);
          }
          // Tap anywhere on the dim scrim outside the card dismisses without reacting.
          ov.setOnClickListener(v -> closeQuickReactionOverlay());
          // Swallow taps on the card itself so they don't bubble to the scrim above.
          if (quickReactionCardView != null) quickReactionCardView.setOnClickListener(v -> {});
          return ov;
      }
      /** Drag / cube animations: promote the whole viewer column to a GPU texture so each
       *  frame is just a matrix transform (no re-draw of photo + blur + chrome). */
      private void columnHwLayerOn() {
          if (columnHwLayerOn || binding == null) return;
          columnHwLayerOn = true;
          binding.llViewerColumn.setLayerType(View.LAYER_TYPE_HARDWARE, null);
      }
      private void columnHwLayerOff() {
          if (!columnHwLayerOn || binding == null) return;
          columnHwLayerOn = false;
          binding.llViewerColumn.setLayerType(View.LAYER_TYPE_NONE, null);
      }
      // ── Lifecycle ─────────────────────────────────────────────────────────
      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          overridePendingTransition(0, 0); // v508: no system open animation (theme also disables it) → faster first paint
          binding = ActivityStatusViewerBinding.inflate(getLayoutInflater());
          setContentView(binding.getRoot());
          getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          setupEdgeToEdge();
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
          final float cardR = 14f * getResources().getDisplayMetrics().density;
          binding.flStoryCard.setOutlineProvider(new android.view.ViewOutlineProvider() {
              @Override public void getOutline(View v, android.graphics.Outline o) {
                  o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), cardR);
              }
          });
          binding.flStoryCard.setClipToOutline(true);
          binding.flStoryCard.setBackgroundColor(Color.BLACK);
          setupSwipeDownGesture();
          setupTouchZones();
          setupCloseButton();
          setupReactionButton();
          setupLikeHeartButton();
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
          spinView = new StorySpinViewController(this);
          spinView.attachTargets(binding.ivStatus, binding.playerView);
          setupStabilizerButton();
          setupMediaRoundedCorners();
          load(ownerUid);
      }

      // Rounds the visible corners of the story media itself (photo/gif/video),
      // 14dp, matching the reply-thumb rounding used in chat. Outline clipping
      // (rather than a Glide bitmap transform) is used so it works uniformly
      // for the ImageView (image/gif) AND the Media3 PlayerView (video), which
      // can't take a Glide transform, and costs nothing extra at draw time
      // once set (GPU-clipped via RenderNode, no per-frame bitmap work).
      //
      // PERF (ultra): provider + resolved px are static/shared across every
      // StatusViewerActivity instance (density is constant per device/process),
      // so opening a new story never re-allocates the outline callback —
      // onCreate only attaches the one already-built provider to this
      // activity's two views.
      private static volatile float sMediaCornerPx = -1f;
      private static final ViewOutlineProvider MEDIA_OUTLINE = new ViewOutlineProvider() {
          @Override
          public void getOutline(View view, android.graphics.Outline outline) {
              outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), sMediaCornerPx);
          }
      };
      // Reuses the SAME advanced avatar pipeline FollowConnectionsActivity's
      // row avatar gets from FollowAvatarBinder — here it's StatusAvatarBinder
      // (already local to this module, used by the status tray/carousel), so
      // no new cross-module dependency is introduced. Replaces the old flat
      // Glide.load(ownerPhoto).circleCrop() with: L2 memory cache check → L3
      // disk cache check → a density-aware tiered decode chained with a TINY
      // blur-up thumbnail, RGB_565 (opaque avatar, no alpha byte wasted), and
      // a write-back into L2+L3 so reopening the same person's story (or the
      // tray row painting it first) is an instant cache hit instead of a
      // fresh network/decode every time.
      private void bindOwnerAvatar(StatusItem item) {
          if (item.ownerPhoto == null || item.ownerPhoto.isEmpty()) return;
          long version = com.callx.app.cache.AvatarVersionSyncManager
                  .getInstance(this).getCachedVersion(ownerUid);
          com.callx.app.cache.StatusAvatarBinder.bind(
                  this, binding.ivOwner, item.ownerPhoto, version, R.drawable.ic_person);
      }

      /** 14dp, resolved once against device density and cached — shared by
       *  the Glide RoundedCorners transform (image/gif) and the video's
       *  inner-frame outline clip below. */
      private int mediaCornerPx() {
          if (sMediaCornerPx < 0f) {
              sMediaCornerPx = 14f * getResources().getDisplayMetrics().density;
          }
          return (int) sMediaCornerPx;
      }

      // BUG FIX (corners not visible): iv_status/player_view are match_parent
      // full-screen containers, but the actual photo/video is letterboxed
      // (fitCenter / RESIZE_MODE_FIT) INSIDE them. Outline-clipping the
      // full-screen container only rounds the corners of the screen itself
      // (invisible — nothing but backdrop sits there); it never touched the
      // photo/video's real edge. Fixed by:
      //  • image/gif — Glide RoundedCorners bitmap transform in
      //    showImageStatusFromUrl()/showGifStatus() bakes the rounding into
      //    the decoded pixels, so it shows exactly where the photo ends,
      //    letterboxed or not.
      //  • video — PlayerView contains its own inner AspectRatioFrameLayout
      //    (exo_content_frame) that PlayerView itself resizes to the video's
      //    real on-screen rect; clip THAT view instead of the outer
      //    match_parent PlayerView.
      private void setupMediaRoundedCorners() {
          mediaCornerPx(); // ensure sMediaCornerPx resolved before MEDIA_OUTLINE reads it
          View contentFrame = binding.playerView.findViewById(
                  androidx.media3.ui.R.id.exo_content_frame);
          if (contentFrame != null) {
              contentFrame.setOutlineProvider(MEDIA_OUTLINE);
              contentFrame.setClipToOutline(true);
          } else {
              // Fallback for a PlayerView layout without the standard frame id
              // (custom exo layout) — still better than nothing.
              binding.playerView.setOutlineProvider(MEDIA_OUTLINE);
              binding.playerView.setClipToOutline(true);
          }
      }
      @Override protected void onPause()  { super.onPause();  pauseProgress(); if (spinView != null) spinView.stop();
          if (musicFocus != null) musicFocus.abandon(); } // backgrounded → let the other app's music resume (re-requested on resume)
      @Override protected void onResume() {
          super.onResume();
          startSpinViewIfAllowed();
          StatusStickerOverlayView zoomed = findZoomedSticker();
          if (zoomed != null) settleStickerReaction(zoomed); // shrinks back, then resumes
          else if (paused) resumeProgress();
      }
      /** The sticker currently enlarged front-and-centre by the tap-to-zoom
       *  gate, if any — at most one can be zoomed in at a time. */
      private StatusStickerOverlayView findZoomedSticker() {
          final FrameLayout so = stickerOverlayView;
          if (so == null) return null; // never inflated → nothing can be zoomed
          for (int i = 0; i < so.getChildCount(); i++) {
              View child = so.getChildAt(i);
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
          destroyPlayer();
          if (videoPreloader != null) { videoPreloader.shutdown(); videoPreloader = null; }
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
      /** Everything that happens once `items` is filled (from the app-wide cache OR Firebase). */
      private void applyLoadedItems() {
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
                      bindOwnerAvatar(first);
                      buildSegmentBars();
                      showCurrent();
                      // FIX: show download+forward for viewer, hide for owner
                      boolean isOwner = myUid != null && myUid.equals(ownerUid);
                      // v508: Download/Forward/Repost are menu-only now (no hidden proxy views to toggle)
      }
      private void load(String uid) {
          if (isHighlightMode) { loadHighlightAlbum(uid); return; }
          // FAST PATH: StatusCacheManager is an app-wide, always-listening (CallxApp) copy of every
          // contact's active statuses, already sorted. Open straight from it — no Firebase round-trip
          // before the first frame. Falls through to the network read when the cache has nothing for
          // this owner, or doesn't (yet) contain the specific status we were asked to open.
          try {
              List<StatusItem> cached = com.callx.app.cache.StatusCacheManager.getInstance(this).getStatuses(uid);
              if (!cached.isEmpty()) {
                  boolean targetOk = targetStatusId == null || targetStatusId.isEmpty();
                  if (!targetOk) for (StatusItem c : cached) if (targetStatusId.equals(c.id)) { targetOk = true; break; }
                  if (targetOk) { items.addAll(cached); applyLoadedItems(); return; }
              }
          } catch (Exception e) { items.clear(); }
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
                          dbgLog( "load() added item id=" + s.id + " type=" + s.type
                                  + " resharedMediaType=" + s.resharedMediaType
                                  + " hasMediaUrl=" + (s.mediaUrl != null && !s.mediaUrl.isEmpty()));
                          items.add(s);
                      }
                      applyLoadedItems();
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
          // Reset immediately so a moment-long stale "on" from the
          // PREVIOUS album (while this album's own meta fetch is still
          // in flight) can't let spinView.start() run against the wrong
          // album's setting, even briefly.
          highlightStabilizerEnabled = false;
          // Album-wide stabilizer opt-in — fetched in parallel with the
          // album's items below; whichever finishes second calls
          // startSpinViewIfAllowed() so the very first story shown already
          // reflects the correct on/off state either way.
          StatusHighlightManager.getAlbumMetaRef(uid, highlightAlbumId)
              .child("stabilizerEnabled")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(@NonNull DataSnapshot snap) {
                      Boolean v = snap.getValue(Boolean.class);
                      highlightStabilizerEnabled = v != null && v;
                      startSpinViewIfAllowed();
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      highlightStabilizerEnabled = false;
                  }
              });
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
                      bindOwnerAvatar(first);
                      buildSegmentBars();
                      showCurrent();
                      boolean isOwner = myUid != null && myUid.equals(ownerUid);
                      // v508: Download/Forward/Repost are menu-only now (no hidden proxy views to toggle)
                  }
                  @Override public void onCancelled(@NonNull DatabaseError e) {
                      CrashReporter.reportSilently(StatusViewerActivity.this, "StatusViewer.loadHighlightAlbum.onCancelled", e.toException());
                      finish();
                  }
              });
      }
      // ── Segment bar ───────────────────────────────────────────────────────
      private void buildSegmentBars() {
          // v509: reuse the existing ProgressBars — only add / drop the tail when the count changed
          // (was removeAllViews() + N new ProgressBars + N drawable inflations on every status
          // list load / delete). Same count (the common case) = zero view creation.
          final int count = items.size();
          final android.view.ViewGroup box = binding.segmentsContainer;
          while (segmentBars.size() > count) {
              ProgressBar extra = segmentBars.remove(segmentBars.size() - 1);
              box.removeView(extra);
          }
          while (segmentBars.size() < count) {
              ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
              pb.setMax(1000);
              // rounded track (white 30%) + rounded white fill — was a whole-drawable WHITE filter (track turned white too)
              pb.setProgressDrawable(androidx.core.content.ContextCompat.getDrawable(this, R.drawable.status_segment_progress));
              box.addView(pb, new LinearLayout.LayoutParams(0, dpToPx(2), 1f));
              segmentBars.add(pb);
          }
          final int gap = dpToPx(2);
          for (int i = 0; i < count; i++) {
              ProgressBar pb = segmentBars.get(i);
              LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) pb.getLayoutParams();
              final int wantEnd = i < count - 1 ? gap : 0; // only the last bar has no trailing gap
              if (lp.getMarginEnd() != wantEnd) { lp.setMarginEnd(wantEnd); pb.setLayoutParams(lp); }
              if (pb.getProgress() != 0) pb.setProgress(0);
          }
      }
      private void fillSegmentsBefore(int currentIdx) {
          for (int i = 0; i < segmentBars.size(); i++)
              segmentBars.get(i).setProgress(i < currentIdx ? 1000 : 0);
      }
      // ── Show current item ─────────────────────────────────────────────────
      private int showSeq; // bumps on every showCurrent(); deferred work from an older status is dropped
      /** Runs `r` right AFTER the next frame has been drawn (so the photo / video is already on
       *  screen), and only if we're still on the same status. Keeps Firebase listeners, sensor
       *  start-up and Glide prefetch off the critical path to first paint. */
      private void deferAfterFirstFrame(final int seq, final Runnable r) {
          android.view.Choreographer.getInstance().postFrameCallback(t -> handler.post(() -> {
              if (isFinishing() || isDestroyed() || seq != showSeq) return;
              r.run();
          }));
      }
      private void showCurrent() {
          if (idx >= items.size()) { goToNextQueueEntryOrFinish(); return; }
          final int seq = ++showSeq;
          StatusItem s = items.get(idx);
          if (viewStartTime > 0 && idx > 0) {
              StatusItem prev = items.get(idx - 1);
              if (prev.id != null)
                  StatusSeenTracker.recordViewDuration(ownerUid, prev.id,
                          System.currentTimeMillis() - viewStartTime);
          }
          viewStartTime = System.currentTimeMillis();
          deferAfterFirstFrame(seq, this::startSpinViewIfAllowed);
          fillSegmentsBefore(idx);
          updateHeaderTimestamp(s);
          updateSeenByInfo(s);
          // Replies strip: drop the previous status's listener + bubble NOW (no stale flash),
          // attach the new Firebase listener once the frame is up.
          detachRepliesListener();
          if (lastReplyBubbleView != null) {
              lastReplyBubbleView.animate().cancel();
              lastReplyBubbleView.setVisibility(View.GONE);
          }
          final StatusItem shown = s;
          deferAfterFirstFrame(seq, () -> updateLastReplyOverlay(shown));
          updateExpiryLabel(s);
          deferAfterFirstFrame(seq, () -> playReactionBurstIfAny(shown));
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
                          dbgLog( "reshareRender id=" + s.id + " using resharedMediaType=" + s.resharedMediaType);
                      } else {
                          looksLikeVideo = looksLikeVideoUrl(s.mediaUrl);
                          dbgLog( "reshareRender id=" + s.id + " resharedMediaType MISSING (legacy row) — guessed from URL, looksLikeVideo=" + looksLikeVideo);
                      }
                      dbgLog( "reshareRender id=" + s.id + " type=" + s.type
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
          deferAfterFirstFrame(seq, this::prefetchNextImage);

          // ── Reshare attribution card ─────────────────────────────────────
          try {
              // v507: card is a ViewStub — inflated only the first time a reshared status shows.
              {
                  boolean isReshare = "reel_reshare".equals(s.type)
                                   || "post_reshare".equals(s.type)
                                   || "channel_post_reshare".equals(s.type)
                                   || "status_reshare".equals(s.type);
                  if (isReshare && s.resharedFromOwnerName != null && !s.resharedFromOwnerName.isEmpty()) {
                      final android.view.View flReshare = reshareContainer();
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
                  } else if (reshareContainerView != null) {
                      reshareContainerView.setVisibility(android.view.View.GONE);
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
          final FrameLayout textLayer = textLayer(); // v507: inflated on first text status
          final TextView textTv = textStatusTv;
          textLayer.setVisibility(View.VISIBLE);
          textTv.setText(StatusMentionHelper.highlightFast(s.text));
          try {
              if (s.bgColor != null) textLayer.setBackgroundColor(Color.parseColor(s.bgColor));
              else textLayer.setBackgroundResource(R.drawable.gradient_brand);
          } catch (Exception e) {
              textLayer.setBackgroundResource(R.drawable.gradient_brand);
          }
          if (s.textColor != null) {
              try { textTv.setTextColor(Color.parseColor(s.textColor)); }
              catch (Exception ignored) {}
          }
          applyFontStyle(textTv, s.fontStyle);
          if (s.textSize > 0) textTv.setTextSize(s.textSize);
          if (s.textAlign != null) {
              switch (s.textAlign) {
                  case "left":  textTv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); break;
                  case "right": textTv.setGravity(Gravity.END   | Gravity.CENTER_VERTICAL); break;
                  default:      textTv.setGravity(Gravity.CENTER);
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
          final FrameLayout textLayer = textLayer();
          textLayer.setVisibility(View.VISIBLE);
          textLayer.setBackgroundResource(R.drawable.gradient_brand);
          textStatusTv.setText("This content is no longer available");
          textStatusTv.setTextColor(Color.WHITE);
          startProgress(5_000L);
      }
      // ── Image-ready hold: progress bar waits (max IMAGE_HOLD_TIMEOUT_MS) until the
      // photo is actually on screen, so a slow network never burns the 5s on a blank frame.
      private static final long IMAGE_HOLD_TIMEOUT_MS = 3_000L;
      private boolean imageHolding = false;
      private long    imageHoldTotalMs = 0L;
      private final Runnable imageHoldTimeout = () -> releaseImageHold();

      private void beginImageHold(long totalMs) {
          stopProgress();
          paused = false;
          remainingMs = totalMs;
          imageHoldTotalMs = totalMs;
          imageHolding = true;
          handler.removeCallbacks(imageHoldTimeout);
          handler.postDelayed(imageHoldTimeout, IMAGE_HOLD_TIMEOUT_MS);
      }
      /** Image shown (or failed / timed out) → let the bar run (music hold, if any, starts it instead). */
      private void releaseImageHold() {
          handler.removeCallbacks(imageHoldTimeout);
          if (!imageHolding) return;
          imageHolding = false;
          if (musicHolding) return;          // music release will start the bar (same total)
          if (!paused) startProgress(imageHoldTotalMs);
      }
      private void cancelImageHold() {
          handler.removeCallbacks(imageHoldTimeout);
          imageHolding = false;
      }

      // Shared by the real load AND the next-status prefetch → identical Glide cache keys.
      private com.bumptech.glide.request.RequestOptions imgBgOptions() {
          final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
          return new com.bumptech.glide.request.RequestOptions()
                  .override(Math.max(1, dm.widthPixels / 6), Math.max(1, dm.heightPixels / 6))
                  .transform(new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                          new StatusBlurTransformation(12)); // bigger blur (was 4) — Instagram-style soft backdrop
      }
      private com.bumptech.glide.request.RequestOptions imgFgOptions() {
          final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
          return new com.bumptech.glide.request.RequestOptions()
                  .override(dm.widthPixels, dm.heightPixels)
                  .transform(new com.bumptech.glide.load.resource.bitmap.FitCenter(),
                          new com.bumptech.glide.load.resource.bitmap.RoundedCorners(mediaCornerPx()));
      }

      private com.callx.app.cache.StatusMediaPreloader videoPreloader; // lazily created, shut down in onDestroy

      /** Warms the player cache with the first ~2MB of the NEXT status's video. */
      private void prefetchNextVideo(String url) {
          if (url == null || url.isEmpty()) return;
          try {
              if (videoPreloader == null) videoPreloader = new com.callx.app.cache.StatusMediaPreloader(this);
              videoPreloader.preloadVideoUrl(url);
          } catch (Exception ignored) {}
      }

      /** Warms Glide's cache with the NEXT status's photo so swiping/auto-advance is instant. */
      private void prefetchNextImage() {
          if (isFinishing() || isDestroyed()) return;
          if (idx + 1 < items.size()) { prefetchItem(items.get(idx + 1)); return; }
          prefetchNextQueueEntry(); // last segment of this person → warm the NEXT person's first one
      }
      /** Instagram-style: while the last story of the current person plays, the next person's
       *  first story (media + blurred backdrop) is already being fetched, so the cube turn lands
       *  on a ready frame instead of an empty card. Reads the app-wide cache — no extra Firebase read. */
      private void prefetchNextQueueEntry() {
          if (isHighlightMode || !hasNextInQueue()) return;
          try {
              List<StatusItem> next = com.callx.app.cache.StatusCacheManager.getInstance(this)
                      .getStatuses(queueOwnerUids.get(queuePos + 1));
              if (!next.isEmpty()) prefetchItem(next.get(0));
          } catch (Exception ignored) {}
      }
      private void prefetchItem(StatusItem n) {
          if (n == null || n.type == null) return;
          String url = null;
          switch (n.type) {
              case "image":
                  url = n.mediaUrl; break;
              case "video": case "reel_story": case "reel_clip":
                  if (n.mediaUrl == null || n.mediaUrl.isEmpty()) url = n.thumbnailUrl;
                  else prefetchNextVideo(n.mediaUrl); // plays via ExoPlayer — warm its cache
                  break;
              case "link":
                  url = n.linkImageUrl; break;
              case "reel_reshare": case "post_reshare": case "channel_post_reshare": case "status_reshare": {
                  boolean video = n.resharedMediaType != null && !n.resharedMediaType.isEmpty()
                          ? "video".equals(n.resharedMediaType) : looksLikeVideoUrl(n.mediaUrl);
                  if (n.mediaUrl != null && !n.mediaUrl.isEmpty() && !video) url = n.mediaUrl;
                  else if (n.mediaUrl != null && !n.mediaUrl.isEmpty()) prefetchNextVideo(n.mediaUrl);
                  break;
              }
              default: break;
          }
          if (url == null || url.isEmpty()) return;
          try {
              Glide.with(this).asBitmap().load(url)
                      .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                      .apply(imgBgOptions()).preload(); // same key as loadRootBackdrop()
              Glide.with(this).load(url)
                      .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                      .apply(imgFgOptions()).preload();
          } catch (Exception ignored) {}
      }

      // ── Load-failure UI: one silent auto-retry, then a tap-to-retry card ──
      private void showImageLoadError(final String url, final String caption) {
          showLoadErrorCard(() -> showImageStatusFromUrl(url, caption, 0));
      }

      /** Tap-to-retry card shared by photo + GIF. Text comes from strings.xml;
       *  exposed to TalkBack as a live-announced button with a 48dp touch target. */
      private void showLoadErrorCard(final Runnable retry) {
          final FrameLayout so = stickerOverlay();
          View old = so.findViewWithTag("img_load_error");
          if (old != null) so.removeView(old); // only the error card — stickers stay
          final float density = getResources().getDisplayMetrics().density;
          TextView tv = new TextView(this);
          tv.setTag("img_load_error");
          tv.setText(com.callx.app.core.R.string.status_load_failed);
          tv.setTextColor(Color.WHITE);
          tv.setTextSize(14);
          tv.setGravity(Gravity.CENTER);
          int pad = (int) (16 * density);
          tv.setPadding(pad * 2, pad, pad * 2, pad);
          tv.setMinHeight((int) (48 * density));
          android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
          bg.setColor(0xCC000000);
          bg.setCornerRadius(14 * density);
          tv.setBackground(bg);
          tv.setClickable(true);
          tv.setFocusable(true);
          tv.setContentDescription(getString(com.callx.app.core.R.string.status_load_failed_desc));
          tv.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
          androidx.core.view.ViewCompat.setAccessibilityDelegate(tv,
                  new androidx.core.view.AccessibilityDelegateCompat() {
              @Override public void onInitializeAccessibilityNodeInfo(View host,
                      androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                  super.onInitializeAccessibilityNodeInfo(host, info);
                  info.setClassName(android.widget.Button.class.getName());
              }
          });
          tv.setOnClickListener(v -> {
              so.removeView(v);
              retry.run();
          });
          so.addView(tv, new FrameLayout.LayoutParams(
                  FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
      }

      private void showImageStatusFromUrl(String url, String caption) {
          showImageStatusFromUrl(url, caption, 0);
      }

      private void showImageStatusFromUrl(final String url, final String caption, final int attempt) {
          binding.ivStatus.setVisibility(View.VISIBLE);
          final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
          final int screenW = dm.widthPixels, screenH = dm.heightPixels;
          loadRootBackdrop(url);
          final int reqIdx = idx;
          // Hold BEFORE issuing the requests — a memory-cache hit can fire onResourceReady synchronously.
          beginImageHold(5_000L);
          // Full photo, never cropped; the blurred/dimmed backdrop is ONE whole-window layer
          // (iv_root_bg, see loadRootBackdrop) that also shows through the transparent card —
          // so each photo is decoded + blurred once, not twice.

          // Blur-up: the item's small thumbnail (if any, and not the same URL) shows instantly
          // while the full photo loads — no black flash.
          String thumbUrl = (idx < items.size()) ? items.get(idx).thumbnailUrl : null;
          com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> thumbReq = null;
          if (thumbUrl != null && !thumbUrl.isEmpty() && !thumbUrl.equals(url)) {
              thumbReq = Glide.with(this).load(thumbUrl)
                      .apply(new com.bumptech.glide.request.RequestOptions()
                              .override(Math.max(1, screenW / 4), Math.max(1, screenH / 4))
                              .transform(new com.bumptech.glide.load.resource.bitmap.FitCenter(),
                                      new com.bumptech.glide.load.resource.bitmap.RoundedCorners(Math.max(1, mediaCornerPx() / 4))));
          }
          com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable> fgReq = Glide.with(this).load(url)
               .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
               .apply(imgFgOptions()) // never decodes bigger than the screen
               .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                   @Override public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                           com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                       if (idx != reqIdx) return false;
                       if (attempt == 0) {
                           // silent auto-retry once (flaky network) — hold stays active meanwhile
                           handler.postDelayed(() -> {
                               if (idx == reqIdx && !isFinishing() && !isDestroyed())
                                   showImageStatusFromUrl(url, caption, 1);
                           }, 600L);
                       } else {
                           releaseImageHold();               // don't leave the bar frozen on a dead image
                           showImageLoadError(url, caption); // tap-to-retry card (bar still runs → auto-skips)
                       }
                       return false;
                   }
                   @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                           com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                           com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                       if (idx == reqIdx) releaseImageHold();
                       return false;
                   }
               });
          if (thumbReq != null) fgReq = fgReq.thumbnail(thumbReq);
          else fgReq = fgReq.placeholder(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); // blurred backdrop (ivStatusBg) shows through instead of a flat black flash
          fgReq.transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(150)).into(binding.ivStatus);
          binding.ivStatus.setContentDescription(TextUtils.isEmpty(caption)
                  ? getString(com.callx.app.core.R.string.status_photo_desc)
                  : getString(com.callx.app.core.R.string.status_photo_desc_with_caption, caption));
          showCaption(caption);
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
          String url = s.gifUrl != null ? s.gifUrl : s.stickerUrl != null ? s.stickerUrl : s.mediaUrl;
          binding.ivStatus.setContentDescription(TextUtils.isEmpty(s.caption)
                  ? getString(com.callx.app.core.R.string.status_gif_desc)
                  : getString(com.callx.app.core.R.string.status_gif_desc_with_caption, s.caption));
          showCaption(s.caption);
          if (url == null || url.isEmpty()) { startProgress(4_000L); return; }
          loadGif(url, 0);
      }

      /** attempt 0 = animated (asGif); attempt 1 = plain drawable decode (a static
       *  PNG/WebP sticker fails asGif); after that → tap-to-retry card. Photo parity:
       *  blurred backdrop, progress held until the frame is actually on screen. */
      private void loadGif(final String url, final int attempt) {
          binding.ivStatus.setVisibility(View.VISIBLE);
          final int reqIdx = idx;
          beginImageHold(4_000L);
          loadRootBackdrop(url);
          com.bumptech.glide.request.RequestOptions fgOpts = new com.bumptech.glide.request.RequestOptions()
                  .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                  .transform(new com.bumptech.glide.load.resource.bitmap.FitCenter(),
                          new com.bumptech.glide.load.resource.bitmap.RoundedCorners(mediaCornerPx()));
          final android.graphics.drawable.Drawable none = new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
          if (attempt == 0) {
              Glide.with(this).asGif().load(url).apply(fgOpts).placeholder(none)
                   .listener(this.<com.bumptech.glide.load.resource.gif.GifDrawable>gifListener(url, attempt, reqIdx))
                   .into(binding.ivStatus);
          } else {
              Glide.with(this).load(url).apply(fgOpts).placeholder(none)
                   .listener(this.<android.graphics.drawable.Drawable>gifListener(url, attempt, reqIdx))
                   .into(binding.ivStatus);
          }
      }

      private <R> com.bumptech.glide.request.RequestListener<R> gifListener(
              final String url, final int attempt, final int reqIdx) {
          return new com.bumptech.glide.request.RequestListener<R>() {
              @Override public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                      com.bumptech.glide.request.target.Target<R> target, boolean isFirstResource) {
                  if (idx != reqIdx || isFinishing() || isDestroyed()) return false;
                  if (attempt == 0) {
                      handler.post(() -> { if (idx == reqIdx && !isFinishing() && !isDestroyed()) loadGif(url, 1); });
                  } else {
                      releaseImageHold();
                      showLoadErrorCard(() -> loadGif(url, 0));
                  }
                  return false;
              }
              @Override public boolean onResourceReady(R resource, Object model,
                      com.bumptech.glide.request.target.Target<R> target,
                      com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                  if (idx == reqIdx) releaseImageHold();
                  return false;
              }
          };
      }
      @OptIn(markerClass = UnstableApi.class)
      private void showVideoStatus(StatusItem s) {
          binding.playerView.setVisibility(View.VISIBLE);
          binding.btnMute.setVisibility(View.VISIBLE);
          releasePlayer(); // stops + resets the shared player and detaches the previous video's listener
          if (player == null) {
              // Built once: no per-status player/decoder/thread setup. Small start-up buffers so a
              // 5–30 s story begins after ~0.5 s of data instead of ExoPlayer's 2.5 s default.
              player = new ExoPlayer.Builder(this)
                      .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                              .setBufferDurationsMs(2_500, 15_000, 500, 1_000)
                              .build())
                      .build();
          }
          binding.playerView.setPlayer(player); // no-op when it's already attached
          if (StatusVideoCacheManager.isInitialized()) {
              CacheDataSource.Factory cf = StatusVideoCacheManager.getCacheDataSourceFactory();
              ProgressiveMediaSource ms = new ProgressiveMediaSource.Factory(cf)
                      .createMediaSource(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
              player.setMediaSource(ms);
          } else {
              player.setMediaItem(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
          }
          applyVideoVolume();
          long estimated = s.durationSec > 0 ? Math.min(s.durationSec * 1000L, 30_000L) : 15_000L;
          final String statusIdForLog = s.id;
          playerListener = new Player.Listener() {
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
          };
          player.addListener(playerListener);
          player.prepare(); player.setPlayWhenReady(true);
          startProgress(estimated);
          showCaption(s.caption);
      }
      /** Same blurred/dimmed copy as iv_status_bg, but spanning the WHOLE window (status bar,
       *  card gap, reply bar) so the area around the rounded card is color-matched, not flat dark. */
      private void loadRootBackdrop(final String url) {
          if (binding.ivRootBg == null || url == null || url.isEmpty()) return;
          binding.ivRootBg.setVisibility(View.VISIBLE);
          binding.flStoryCard.setBackgroundColor(Color.TRANSPARENT); // let the blurred backdrop show through the card
          Glide.with(this).asBitmap().load(url)
               .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
               .apply(imgBgOptions())
               .transition(com.bumptech.glide.load.resource.bitmap.BitmapTransitionOptions.withCrossFade(150))
               .into(binding.ivRootBg);
      }
      private void hideAllContent() {
          if (binding.ivRootBg != null) { binding.ivRootBg.setVisibility(View.GONE); binding.ivRootBg.setImageDrawable(null); }
          binding.flStoryCard.setBackgroundColor(Color.BLACK); // video / text: solid card again
          if (textLayerView != null) textLayerView.setVisibility(View.GONE);
          binding.ivStatus.setVisibility(View.GONE);
          binding.ivStatusBg.setVisibility(View.GONE);
          binding.playerView.setVisibility(View.GONE);
          binding.tvCaption.setVisibility(View.GONE);
          // FIX: was findViewWithTag("tv_location_tag") — always null — now binding ref
          binding.tvLocationTag.setVisibility(View.GONE);
          if (stickerOverlayView != null) stickerOverlayView.removeAllViews();
          stickerZoomScrim = null; // just got removed along with everything else above
          videoUnderMusic = false; // new status → its own video starts at normal volume
          cancelMusicHold();    // a hold from the previous status must never start this one's progress
          cancelImageHold();
          releaseMusicPlayer(); // stop the previous status's music sticker (+ give audio focus back)
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
      //
      // Polish round (v479):
      //  (1) AUDIO FOCUS  — StatusAudioFocusHelper: pauses other apps' music while
      //      we play, pauses/resumes on calls, ducks on request.
      //  (3) FAIL FALLBACK — if the sticker's direct soundUrl errors out, we retry
      //      once via the soundId lookup (sounds/ → musicLibrary/); if that fails
      //      too the viewer sees a short toast instead of silence.
      //  (4) SYNC START   — for photo/text/gif statuses the progress bar is held
      //      (max MUSIC_HOLD_TIMEOUT_MS) until the audio is prepared, so bar and
      //      music start together instead of the music arriving seconds late.

      /** Longest we keep the progress bar waiting for the music to buffer. */
      private static final long MUSIC_HOLD_TIMEOUT_MS = 3_000L;

      // (2) A VIDEO status that also carries a music sticker used to play BOTH
      // soundtracks at full volume (video's own audio + the sticker's song).
      // Now the video's own audio is ducked to this level while the sticker music
      // is in play. Set to 0f if you'd rather the song fully replace the original
      // audio (Instagram-style); 1f restores the old "both full" behaviour.
      private static final float VIDEO_VOLUME_UNDER_MUSIC = 0.25f;
      private boolean videoUnderMusic = false;           // this status's video should be ducked
      // (5) Full-length tracks (no preview URL) used to play from 0:00 for minutes.
      // StatusMusicClip plays a ~30s highlight (or the poster's trimmed range) instead.
      private final StatusMusicClip musicClip = new StatusMusicClip();
      private int stickerMusicStartMs = 0;   // current status's sticker clip range (0 = unset)
      private int stickerMusicEndMs   = 0;
      private StatusAudioFocusHelper musicFocus;      // lazily created
      private boolean musicPrepared   = false;        // MediaPlayer reached prepared state
      private boolean musicFocusLost  = false;        // paused because another app/call took focus
      private boolean musicDucked     = false;        // another app asked us to be quiet
      private boolean musicHolding    = false;        // progress bar is waiting for the music
      private long    musicHoldTotalMs = 0L;          // full duration to start once released
      private final Runnable musicHoldTimeout = () -> releaseMusicHold();

      private StatusAudioFocusHelper musicFocus() {
          if (musicFocus == null) {
              musicFocus = new StatusAudioFocusHelper(this, new StatusAudioFocusHelper.Listener() {
                  @Override public void onFocusLost() {
                      musicFocusLost = true;
                      if (musicPlayer != null) {
                          try { if (musicPlayer.isPlaying()) musicPlayer.pause(); } catch (Exception ignored) {}
                      }
                      syncMusicStickerPlaying();
                  }
                  @Override public void onFocusRegained() {
                      musicFocusLost = false;
                      if (!paused) resumeMusicPlayback();
                  }
                  @Override public void onDuck(boolean ducked) {
                      musicDucked = ducked;
                      applyMusicVolume();
                  }
              });
          }
          return musicFocus;
      }

      /** Bars + ticker on the music sticker follow real playback (not paused, not muted). */
      private void syncMusicStickerPlaying() {
          if (musicStickerView == null) return;
          boolean playing = false;
          try { playing = musicPlayer != null && musicPlayer.isPlaying() && !isMuted; } catch (Exception ignored) {}
          musicStickerView.setMusicPlaying(playing);
      }

      /** Single place that decides the status VIDEO volume (mute + sticker-music ducking). */
      private void applyVideoVolume() {
          if (player == null) return;
          float v = isMuted ? 0f : (videoUnderMusic ? VIDEO_VOLUME_UNDER_MUSIC : 1f);
          try { player.setVolume(v); } catch (Exception ignored) {}
      }

      /** Muted viewers don't take focus (we would silence their other app for nothing). */
      private boolean acquireMusicFocus() {
          if (isMuted) return true;
          return musicFocus().request();
      }

      /** Single place that decides the music volume (mute + duck). */
      private void applyMusicVolume() {
          if (musicPlayer == null) return;
          float v = isMuted ? 0f : (musicDucked ? 0.2f : 1f);
          try { musicPlayer.setVolume(v, v); } catch (Exception ignored) {}
      }

      /** Start/continue the music if it is prepared, allowed and we can get focus. */
      private void resumeMusicPlayback() {
          if (musicPlayer == null || !musicPrepared || musicFocusLost) return;
          try {
              if (musicPlayer.isPlaying()) return;
              if (!acquireMusicFocus()) return; // e.g. a call is active — stay silent
              musicPlayer.start();
          } catch (Exception ignored) {}
          syncMusicStickerPlaying();
      }

      // ── (4) progress-bar hold while the music buffers ───────────────────
      private void beginMusicHold(StatusItem item) {
          if (paused || (!progressActive && !imageHolding)) return;
          // Videos keep their own timing — never make a video wait for the sticker music.
          if (item != null && ("video".equals(item.type) || "reel_story".equals(item.type)
                  || "reel_clip".equals(item.type))) return;
          musicHoldTotalMs = remainingMs; // startProgress() just set this to the full duration
          stopProgress();
          musicHolding = true;
          handler.removeCallbacks(musicHoldTimeout);
          handler.postDelayed(musicHoldTimeout, MUSIC_HOLD_TIMEOUT_MS);
      }
      /** Audio ready (or gave up / timed out) → let the progress bar run. */
      private void releaseMusicHold() {
          handler.removeCallbacks(musicHoldTimeout);
          if (!musicHolding) return;
          musicHolding = false;
          // If the viewer is holding the screen right now, resumeProgress() will start
          // the bar later using remainingMs, so there's nothing to do here.
          if (imageHolding) return;          // photo still loading → its release starts the bar
          if (!paused) startProgress(musicHoldTotalMs);
      }
      /** Status changed / closed → forget the hold WITHOUT starting any progress. */
      private void cancelMusicHold() {
          handler.removeCallbacks(musicHoldTimeout);
          musicHolding = false;
      }

      /**
       * @param url             direct playable URL from the sticker (may be escaped, see below)
       * @param fallbackSoundId if non-empty and this URL fails, retry once by soundId lookup
       * @param requestedIdx    status index this audio belongs to (stale callbacks are dropped)
       */
      private void startMusicStickerAudio(String url, final String fallbackSoundId, final int requestedIdx) {
          dbgLog( "startMusicStickerAudio url=" + url + " isMuted=" + isMuted + " paused=" + paused);
          // Defensive: a JSON-escaped URL ("https:\\/\\/host\\/a.mp3") is never playable.
          // Root fix is in StatusStickerOverlayView.jsonStr(); this guards any other path.
          if (url != null) url = url.replace("\\/", "/");
          final String playUrl = url;
          releaseMusicPlayerKeepFocus(); // keep focus: we are about to play again, avoids other-app flicker
          if (playUrl == null || playUrl.isEmpty()) {
              handleMusicFailure(fallbackSoundId, requestedIdx, 0, 0);
              return;
          }
          try {
              final android.media.MediaPlayer mp = new android.media.MediaPlayer();
              musicPlayer = mp;
              mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                      .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                      .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                      .build());
              mp.setDataSource(playUrl);
              // Loop so the clip keeps playing even if it's shorter than however
              // long the viewer ends up spending on this status.
              mp.setLooping(true);
              applyMusicVolume();
              mp.setOnPreparedListener(p -> {
                  // Stale callback (viewer already swiped to another status)? ignore.
                  if (musicPlayer != p || idx != requestedIdx) return;
                  musicPrepared = true;
                  // (5) jump to the highlight BEFORE start(); false → short preview, plays whole + loops
                  musicClip.apply(p, stickerMusicStartMs, stickerMusicEndMs);
                  releaseMusicHold(); // (4) bar + audio now start together
                  dbgLog( "startMusicStickerAudio prepared, paused=" + paused);
                  if (!paused) resumeMusicPlayback(); // (1) takes audio focus, then start()
              });
              mp.setOnErrorListener((p, what, extra) -> {
                  if (musicPlayer != p) return true; // stale player
                  // Bad/expired URL, unsupported codec, network failure ... all land here.
                  CrashReporter.reportSilently(StatusViewerActivity.this, "StatusViewer.musicSticker.onError",
                          new RuntimeException("MediaPlayer error what=" + what + " extra=" + extra + " url=" + playUrl));
                  releaseMusicPlayerKeepFocus();
                  handleMusicFailure(fallbackSoundId, requestedIdx, what, extra); // (3)
                  return true;
              });
              mp.prepareAsync();
          } catch (Exception e) {
              // Bad/unreachable preview URL — never crash the viewer.
              CrashReporter.reportSilently(this, "StatusViewer.startMusicStickerAudio", e);
              releaseMusicPlayerKeepFocus();
              handleMusicFailure(fallbackSoundId, requestedIdx, 0, 0);
          }
      }

      /**
       * (3) The direct URL failed. First failure → retry via soundId lookup (the same
       * route the Sound Detail sheet uses, which is why tapping the sticker still
       * played). No soundId left / second failure → tell the viewer instead of silence.
       */
      private void handleMusicFailure(String fallbackSoundId, int requestedIdx, int what, int extra) {
          releaseMusicHold(); // never keep the bar waiting for audio that isn't coming
          if (idx != requestedIdx) return; // swiped away meanwhile
          if (fallbackSoundId != null && !fallbackSoundId.isEmpty()) {
              dbgLog( "musicSticker: direct url failed (what=" + what + "/" + extra
                      + ") → retry by soundId=" + fallbackSoundId);
              resolveSoundIdAndPlay(fallbackSoundId, requestedIdx);
          } else {
              showMusicFailedToast(what, extra);
          }
      }

      private void showMusicFailedToast(int what, int extra) {
          // No song is coming → the video must not stay ducked for nothing.
          videoUnderMusic = false;
          applyVideoVolume();
          if (isFinishing()) return;
          String code = (what != 0 || extra != 0) ? " (" + what + "/" + extra + ")" : "";
          android.widget.Toast.makeText(this, "\uD83C\uDFB5 Music play nahi ho paya" + code,
                  android.widget.Toast.LENGTH_SHORT).show();
      }

      /**
       * Resolves a playable URL from the soundId (sounds/{id} first, then
       * musicLibrary/{id} — Trending Audio's "Music" tab tracks live there) and plays it.
       * The resolved URL gets NO further fallback, so a second failure ends in a toast.
       */
      private void resolveSoundIdAndPlay(String soundId, int requestedIdx) {
          final String cachedUrl = SOUND_URL_CACHE.get(soundId);
          if (cachedUrl != null) { // seen before → no Firebase round-trip on the audio critical path
              if (idx == requestedIdx) startMusicStickerAudio(cachedUrl, null, requestedIdx);
              return;
          }
          FirebaseUtils.db().getReference("sounds").child(soundId)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  // Viewer may have swiped to another status by the time this
                  // lookup returns — don't start audio for a status that's gone.
                  if (idx != requestedIdx) return;
                  String resolved = null;
                  if (snap.exists()) {
                      for (String key : new String[]{"previewAudioUrl", "audioUrl", "audio_url", "url"}) {
                          String u = snap.child(key).getValue(String.class);
                          if (u != null && !u.isEmpty()) { resolved = u; break; }
                      }
                  }
                  if (resolved != null) {
                      dbgLog( "musicSticker autoplay: resolved from sounds/" + soundId);
                      SOUND_URL_CACHE.put(soundId, resolved);
                      startMusicStickerAudio(resolved, null, requestedIdx);
                  } else {
                      dbgLog( "musicSticker autoplay: sounds/" + soundId + " missing or no audio field, trying musicLibrary/");
                      resolveFromMusicLibrary(soundId, requestedIdx);
                  }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  Log.e(DBG, "musicSticker autoplay: sounds/" + soundId + " lookup cancelled", e.toException());
                  if (idx == requestedIdx) { releaseMusicHold(); showMusicFailedToast(0, 0); }
              }
          });
      }

      /**
       * Fallback lookup for music-sticker autoplay: mirrors
       * SoundDetailFragment#loadSoundDataFromMusicLibrary() — tracks picked
       * from Trending Audio's "Music" tab live under musicLibrary/{soundId},
       * not sounds/{soundId}.
       */
      private void resolveFromMusicLibrary(String soundId, int requestedIdx) {
          FirebaseUtils.getMusicLibraryRef().child(soundId)
                  .addListenerForSingleValueEvent(new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (idx != requestedIdx) return; // swiped away while this was in flight
                  String resolved = null;
                  if (snap.exists()) {
                      for (String key : new String[]{"previewAudioUrl", "audioUrl"}) {
                          String u = snap.child(key).getValue(String.class);
                          if (u != null && !u.isEmpty()) { resolved = u; break; }
                      }
                  }
                  if (resolved != null) {
                      dbgLog( "musicSticker autoplay: resolved from musicLibrary/" + soundId);
                      SOUND_URL_CACHE.put(soundId, resolved);
                      startMusicStickerAudio(resolved, null, requestedIdx);
                  } else {
                      dbgLog( "musicSticker autoplay: musicLibrary/" + soundId + " not found / no audio field");
                      releaseMusicHold();
                      showMusicFailedToast(0, 0); // (3) no more silent failure
                  }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {
                  Log.e(DBG, "musicSticker autoplay: musicLibrary/" + soundId + " lookup cancelled", e.toException());
                  if (idx == requestedIdx) { releaseMusicHold(); showMusicFailedToast(0, 0); }
              }
          });
      }

      /** Releases the MediaPlayer but keeps audio focus (used right before re-playing). */
      private void releaseMusicPlayerKeepFocus() {
          musicClip.stop();
          if (musicPlayer != null) {
              try { musicPlayer.release(); } catch (Exception ignored) {}
              musicPlayer = null;
          }
          musicPrepared = false;
          musicFocusLost = false;
          musicDucked = false;
          syncMusicStickerPlaying();
      }

      /** Releases the MediaPlayer AND gives audio focus back (other app's music resumes). */
      private void releaseMusicPlayer() {
          releaseMusicPlayerKeepFocus();
          musicStickerView = null;
          if (musicFocus != null) musicFocus.abandon();
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
              final int stickerSeq = showSeq; // renderStickers() runs synchronously inside showCurrent()
              int dp = (int) getResources().getDisplayMetrics().density;
              for (int i = 0; i < arr.length(); i++) {
                  String stickerJson = arr.getJSONObject(i).toString();
                  StatusStickerOverlayView sticker = StatusStickerOverlayView.fromJson(this, stickerJson);

                  // Frame may not have been measured yet on the very first status
                  // shown — fall back to screen size so the ratio math below still
                  // lines the sticker up correctly instead of pinning it at (0,0).
                  // v507: overlay is a freshly inflated ViewStub on first use (not measured yet) —
                  // it is match_parent inside fl_story_card, so the (already measured) card is the
                  // exact same size; screen size stays the last-resort fallback.
                  final FrameLayout stickerOv = stickerOverlay();
                  int frameW = stickerOv.getWidth() > 0 ? stickerOv.getWidth()
                          : binding.flStoryCard.getWidth() > 0 ? binding.flStoryCard.getWidth()
                          : getResources().getDisplayMetrics().widthPixels;
                  int frameH = stickerOv.getHeight() > 0 ? stickerOv.getHeight()
                          : binding.flStoryCard.getHeight() > 0 ? binding.flStoryCard.getHeight()
                          : getResources().getDisplayMetrics().heightPixels;

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
                  stickerOv.addView(sticker, lp);

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
                          final String stateKey = stickerStateKey("quiz", ownerUid, statusId, stickerIndex, myUid);
                          loadOwnStickerState(stickerSeq, stateKey,
                                  FirebaseUtils.getStatusQuizVoteRef(ownerUid, statusId, stickerIndex, myUid),
                                  snap -> {
                                      Long prevSel = snap.child("selectedIndex").getValue(Long.class);
                                      return prevSel != null ? (Object) Integer.valueOf(prevSel.intValue()) : NO_STATE;
                                  },
                                  state -> {
                                      if (state instanceof Integer) {
                                          sticker.revealQuizAnswer((Integer) state);
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
                                              putOwnStickerState(stateKey, Integer.valueOf(selectedIndex));
                                              StatusReplyBottomSheet.sendQuizAnswer(myUid, ownerUid, ownerName,
                                                      cur, stickerIndex, sticker.getQuizQuestion(),
                                                      selectedText, selectedIndex, isCorrect);
                                              // Give the viewer a beat to see the ✓/✗ reveal, then shrink back and resume.
                                              new Handler(Looper.getMainLooper())
                                                      .postDelayed(() -> settleStickerReaction(sticker), 1200);
                                          });
                                      }
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
                      musicStickerView = sticker;
                      sticker.setMusicPlaying(false); // rests until the audio actually plays
                      showStatusSongTicker(sticker);
                      // Autoplay the sticker's preview clip the instant the status
                      // appears — WhatsApp/Instagram-style. Independent of whether
                      // it's linked to a real Reels track (tap-to-open still only
                      // applies when isMusicLinkedToReelSound() is true, below).
                      String soundUrl = sticker.getMusicSoundUrl();
                      final String musicSoundId = sticker.getMusicSoundId();
                      final int musicIdx = idx;
                      stickerMusicStartMs = sticker.getMusicStartMs(); // (5) clip range for this status
                      stickerMusicEndMs   = sticker.getMusicEndMs();
                      dbgLog( "musicSticker autoplay: soundId=" + musicSoundId
                              + " directSoundUrl=" + (soundUrl == null || soundUrl.isEmpty() ? "empty" : "present"));
                      boolean hasDirect = soundUrl != null && !soundUrl.isEmpty();
                      boolean hasId     = musicSoundId != null && !musicSoundId.isEmpty();
                      if (hasDirect || hasId) {
                          videoUnderMusic = true;   // (2) duck the video's own audio (no-op for photo/text)
                          applyVideoVolume();
                          beginMusicHold(item); // (4) bar waits (max 3s) so audio + progress start together
                          if (hasDirect) {
                              // (3) if this direct URL fails we retry once via the soundId
                              startMusicStickerAudio(soundUrl, musicSoundId, musicIdx);
                          } else {
                              // Composer stored only the soundId → resolve the URL like the Sound Detail sheet does.
                              resolveSoundIdAndPlay(musicSoundId, musicIdx);
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
                          final String stateKey = stickerStateKey("countdown", ownerUid, statusId, stickerIndex, myUid);
                          loadOwnStickerState(stickerSeq, stateKey,
                                  FirebaseUtils.getStatusCountdownSubscriberRef(ownerUid, statusId, stickerIndex, myUid),
                                  snap -> Boolean.TRUE,
                                  state -> {
                                      if (state == Boolean.TRUE) sticker.setCountdownSubscribed(true);
                                      armStickerZoomGate(sticker);
                                      sticker.setOnCountdownSubscribeToggleListener(nowSubscribed -> {
                                          StatusItem cur = idx < items.size() ? items.get(idx) : null;
                                          if (cur == null) return;
                                          putOwnStickerState(stateKey, nowSubscribed ? (Object) Boolean.TRUE : NO_STATE);
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
                          final String stateKey = stickerStateKey("poll", ownerUid, statusId, stickerIndex, myUid);
                          loadOwnStickerState(stickerSeq, stateKey,
                                  FirebaseUtils.getStatusPollVoteRef(ownerUid, statusId, stickerIndex, myUid),
                                  snap -> {
                                      String opt = snap.child("option").getValue(String.class);
                                      return opt != null ? (Object) opt : VOTED_UNKNOWN;
                                  },
                                  state -> {
                                      if (state != NO_STATE) {
                                          String prevOption = state instanceof String ? (String) state : null;
                                          revealPollWithCounts(sticker, ownerUid, statusId, stickerIndex, prevOption);
                                      } else {
                                          armStickerZoomGate(sticker);
                                          sticker.setOnPollOptionSelectedListener(selectedOption -> {
                                              StatusItem cur = idx < items.size() ? items.get(idx) : null;
                                              if (cur == null) return;
                                              String selectedText = "A".equals(selectedOption)
                                                      ? sticker.getPollOptionA() : sticker.getPollOptionB();
                                              putOwnStickerState(stateKey, selectedOption);
                                              StatusReplyBottomSheet.sendPollVote(myUid, ownerUid, ownerName,
                                                      cur, stickerIndex, sticker.getPollQuestion(),
                                                      selectedOption, selectedText);
                                              revealPollWithCounts(sticker, ownerUid, statusId, stickerIndex, selectedOption);
                                              // Give the viewer a beat to see the % split, then shrink back and resume.
                                              new Handler(Looper.getMainLooper())
                                                      .postDelayed(() -> settleStickerReaction(sticker), 1200);
                                          });
                                      }
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
                          final String stateKey = stickerStateKey("slider", ownerUid, statusId, stickerIndex, myUid);
                          loadOwnStickerState(stickerSeq, stateKey,
                                  FirebaseUtils.getStatusSliderResponseRef(ownerUid, statusId, stickerIndex, myUid),
                                  snap -> {
                                      Long prevValue = snap.child("value").getValue(Long.class);
                                      return Integer.valueOf(prevValue != null ? prevValue.intValue() : 50);
                                  },
                                  state -> {
                                      if (state instanceof Integer) {
                                          revealSliderWithAverage(sticker, ownerUid, statusId, stickerIndex, (Integer) state);
                                      } else {
                                          armStickerZoomGate(sticker);
                                          sticker.setOnSliderValueSubmittedListener(value -> {
                                              StatusItem cur = idx < items.size() ? items.get(idx) : null;
                                              if (cur == null) return;
                                              putOwnStickerState(stateKey, Integer.valueOf(value));
                                              StatusReplyBottomSheet.sendSliderResponse(myUid, ownerUid, ownerName,
                                                      cur, stickerIndex, sticker.getSliderQuestion(),
                                                      sticker.getSliderEmoji(), value);
                                              revealSliderWithAverage(sticker, ownerUid, statusId, stickerIndex, value);
                                              // Give the viewer a beat to see the average, then shrink back and resume.
                                              new Handler(Looper.getMainLooper())
                                                      .postDelayed(() -> settleStickerReaction(sticker), 1200);
                                          });
                                      }
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
                  .getReference("usernames").child(username).get()
                  .addOnSuccessListener(idxSnap -> {
              String uid = idxSnap.getValue(String.class);
              if (uid == null || uid.isEmpty()) { settleStickerReaction(sticker); return; }
              com.google.firebase.database.FirebaseDatabase.getInstance()
                      .getReference("users").child(uid).get()
                      .addOnSuccessListener(userSnap -> {
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
                  boolean finalOpened = opened;
                  if (!finalOpened) settleStickerReaction(sticker);
              }).addOnFailureListener(e -> settleStickerReaction(sticker));
          }).addOnFailureListener(e -> settleStickerReaction(sticker));
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

      // Caption: 3 lines + "…"; tap (only when actually truncated) expands it into a
      // scrollable box (max ~220dp) and pauses the story; tap again / tap outside collapses.
      private boolean captionExpanded = false;
      private void showCaption(String caption) {
          captionExpanded = false;
          final TextView tv = binding.tvCaption;
          tv.setOnClickListener(null);
          tv.setClickable(false);
          tv.setMaxLines(3);
          tv.setEllipsize(TextUtils.TruncateAt.END);
          tv.setMaxHeight(Integer.MAX_VALUE);
          tv.setMovementMethod(null);
          tv.scrollTo(0, 0);
          if (!TextUtils.isEmpty(caption)) {
              tv.setVisibility(View.VISIBLE);
              tv.setText(StatusMentionHelper.highlightFast(caption));
              tv.post(() -> {
                  android.text.Layout l = tv.getLayout();
                  if (l == null || l.getLineCount() == 0) return;
                  if (l.getEllipsisCount(l.getLineCount() - 1) > 0) {
                      tv.setOnClickListener(v -> { if (captionExpanded) collapseCaption(); else expandCaption(); });
                  }
              });
          }
      }
      private void expandCaption() {
          final TextView tv = binding.tvCaption;
          captionExpanded = true;
          pauseProgress();
          tv.setEllipsize(null);
          tv.setMaxLines(Integer.MAX_VALUE);
          tv.setMaxHeight((int) (220 * getResources().getDisplayMetrics().density));
          tv.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
      }
      private void collapseCaption() {
          final TextView tv = binding.tvCaption;
          captionExpanded = false;
          tv.setMovementMethod(null);
          tv.scrollTo(0, 0);
          tv.setMaxHeight(Integer.MAX_VALUE);
          tv.setMaxLines(3);
          tv.setEllipsize(TextUtils.TruncateAt.END);
          resumeProgress();
      }
      // FIX: was findViewWithTag("tv_location_tag") → null, now binding.tvLocationTag
      private void showLocationTag(String location) {
          binding.tvLocationTag.setText("\uD83D\uDCCD " + location);
          binding.tvLocationTag.setVisibility(View.VISIBLE);
      }
      // ── Progress ──────────────────────────────────────────────────────────
      private final android.view.Choreographer.FrameCallback progressFrame = new android.view.Choreographer.FrameCallback() {
          @Override public void doFrame(long frameTimeNanos) {
              if (!progressActive || paused) return;
              final long elapsed = progStartElapsed + (android.os.SystemClock.uptimeMillis() - progStartUptime);
              if (elapsed >= progTotalMs) {
                  progressActive = false;
                  remainingMs = 0;
                  if (idx < segmentBars.size()) segmentBars.get(idx).setProgress(1000);
                  next();
                  return;
              }
              if (idx < segmentBars.size())
                  segmentBars.get(idx).setProgress((int) ((elapsed * 1000L) / progTotalMs)); // same value → no-op
              remainingMs = progTotalMs - elapsed;
              android.view.Choreographer.getInstance().postFrameCallback(this);
          }
      };
      private void startProgress(long durationMs) {
          stopProgress(); paused = false; remainingMs = durationMs;
          runProgressTick(durationMs, durationMs);
      }
      private void runProgressTick(final long totalMs, final long remaining) {
          stopProgress();
          progTotalMs = Math.max(1L, totalMs);
          progStartElapsed = Math.max(0L, progTotalMs - remaining);
          progStartUptime = android.os.SystemClock.uptimeMillis();
          progressActive = true;
          android.view.Choreographer.getInstance().postFrameCallback(progressFrame);
      }
      private void stopProgress() {
          progressActive = false;
          android.view.Choreographer.getInstance().removeFrameCallback(progressFrame);
      }
      private void pauseProgress() {
          if (paused) return; paused = true;
          if (player != null) player.setPlayWhenReady(false);
          if (musicPlayer != null) { try { if (musicPlayer.isPlaying()) musicPlayer.pause(); } catch (Exception ignored) {} }
          syncMusicStickerPlaying();
          stopProgress();
      }
      private void resumeProgress() {
          if (!paused) return; paused = false;
          if (player != null) player.setPlayWhenReady(true);
          cancelMusicHold();      // viewer resumed → progress is driven by remainingMs below
          cancelImageHold();
          resumeMusicPlayback();  // re-acquires audio focus first, only starts if prepared
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
              sticker.zoomToFront(stickerOverlay(), afterZoomed);
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
          stickerOverlay().addView(scrim, new FrameLayout.LayoutParams(
                  FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
          scrim.animate().alpha(1f).setDuration(200).start();
          stickerZoomScrim = scrim;
      }
      private void hideStickerZoomScrim() {
          if (stickerZoomScrim == null) return;
          final View scrim = stickerZoomScrim;
          stickerZoomScrim = null;
          scrim.animate().alpha(0f).setDuration(180)
                  .withEndAction(() -> { if (stickerOverlayView != null) stickerOverlayView.removeView(scrim); }).start();
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
      /** Instagram-style cube turn between people: the incoming person's card swings in
       *  around the screen edge it comes from (right edge when going forward, left when back). */
      private void playUserSwitchTransition(final boolean forward) {
          final View col = binding.llViewerColumn;
          col.animate().cancel();
          columnHwLayerOn(); // v507: cube turn runs on a GPU texture
          col.setScaleX(1f); col.setScaleY(1f); col.setTranslationX(0f); col.setTranslationY(0f);
          col.setCameraDistance(getResources().getDisplayMetrics().density * 8000f);
          col.setPivotX(forward ? 0f : col.getWidth());
          col.setPivotY(col.getHeight() / 2f);
          col.setRotationY(forward ? -70f : 70f);
          col.setAlpha(0.4f);
          col.animate().rotationY(0f).alpha(1f).setDuration(280L)
                  .setInterpolator(new android.view.animation.DecelerateInterpolator())
                  .withEndAction(() -> { col.setPivotX(col.getWidth() / 2f); col.setRotationY(0f); columnHwLayerOff(); })
                  .start();
      }
      /** Tears down the current owner/album's on-screen state and loads the
       *  queue entry at the given position, resetting playback to its first
       *  segment. */
      private void switchQueueEntry(int pos) {
          releasePlayer(); stopProgress();
          items.clear();
          idx = 0;
          targetStatusId = null; // only meant for the FIRST owner we opened on
          viewStartTime = 0;
          reactionBurstPlayedFor.clear();
          hideAllContent();
          playUserSwitchTransition(pos > queuePos);
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
                  if (swipeDragging) return false; // drag-to-close handles its own release (see setupTouchZones)
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
      @Override public void finish() {
          super.finish();
          overridePendingTransition(0, 0); // v508: drag-close / fade already animated by the viewer itself
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
      // All tap/hold/swipe rules for the story surface live here:
      //  • quick tap (< TAP_MAX_MS, finger didn't move, no fling) → left third = previous, else next
      //  • hold (>= TAP_MAX_MS) → pause; release → resume. A plain tap never pauses/resumes
      //    (no 1-frame video/music pause flicker), and a swipe never double-fires as a tap.
      //  • ACTION_CANCEL never navigates.
      private static final long  TAP_MAX_MS = 200L;
      private static final float PREV_ZONE_FRACTION = 1f / 3f;
      private float   touchDownX, touchDownY;
      private boolean touchMoved, touchHoldPaused;
      private final Runnable holdPauseRunnable = () -> { touchHoldPaused = true; pauseProgress(); setChromeHidden(true); };

      // Swipe-down drag-to-close (Instagram style): the whole card + reply bar follows the finger,
      // shrinks a little, and on release either flies off (close) or springs back.
      private boolean swipeDragging, chromeHidden;
      private float dragDownRawX, dragDownRawY;
      private android.view.VelocityTracker dragVelocity;
      private static final float DRAG_CLOSE_FRACTION = 0.2f;   // of screen height
      private static final float DRAG_CLOSE_VELOCITY = 1500f;  // px/s

      /** Hold-to-pause: fade header / segments / caption / reply bar / scrims out, so only the photo shows. */
      private void setChromeHidden(boolean hide) {
          if (chromeHidden == hide) return;
          chromeHidden = hide;
          final View[] chrome = { binding.llTopChrome, binding.llBottomChrome, binding.llReplyBar,
                  binding.btnMute, binding.viewTopScrim, binding.viewBottomScrim };
          for (View c : chrome) {
              if (c == null) continue;
              c.animate().cancel();
              c.animate().alpha(hide ? 0f : 1f).setDuration(hide ? 150L : 200L).start();
          }
      }
      private void applyDrag(float dx, float dy) {
          final float h = getResources().getDisplayMetrics().heightPixels;
          final float p = Math.min(1f, dy / (h * 0.6f));
          final float scale = 1f - 0.25f * p;
          columnHwLayerOn(); // v507: GPU texture while the finger drags the card
          final View col = binding.llViewerColumn;
          col.setPivotX(col.getWidth() / 2f);   // user-switch cube transition moves the pivot to an edge
          col.setPivotY(col.getHeight() / 2f);
          col.setRotationY(0f);
          col.setTranslationX(dx * 0.6f);
          col.setTranslationY(dy);
          col.setScaleX(scale);
          col.setScaleY(scale);
      }
      private void closeByDrag() {
          final float h = getResources().getDisplayMetrics().heightPixels;
          binding.llViewerColumn.animate().translationY(h).scaleX(0.6f).scaleY(0.6f).alpha(0f)
                  .setDuration(180L).withEndAction(this::finish).start();
      }
      private void snapBackFromDrag() {
          binding.llViewerColumn.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
                  .setDuration(200L).setInterpolator(new android.view.animation.DecelerateInterpolator())
                  .withEndAction(this::columnHwLayerOff) // v507: back to normal rendering once settled
                  .start();
      }

      private void setupTouchZones() {
          final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
          binding.touchLayer.setOnTouchListener((v, e) -> {
              final boolean flung = swipeDetector.onTouchEvent(e); // true only when a fling was consumed (UP)
              switch (e.getActionMasked()) {
                  case MotionEvent.ACTION_DOWN:
                      touchDownX = e.getX(); touchDownY = e.getY();
                      dragDownRawX = e.getRawX(); dragDownRawY = e.getRawY(); // raw: the card moves while dragging
                      touchMoved = false; touchHoldPaused = false; swipeDragging = false;
                      if (dragVelocity != null) dragVelocity.recycle();
                      dragVelocity = android.view.VelocityTracker.obtain();
                      dragVelocity.addMovement(e);
                      handler.removeCallbacks(holdPauseRunnable);
                      handler.postDelayed(holdPauseRunnable, TAP_MAX_MS); // pause only if it becomes a hold
                      break;
                  case MotionEvent.ACTION_MOVE: {
                      if (dragVelocity != null) dragVelocity.addMovement(e);
                      final float rdx = e.getRawX() - dragDownRawX, rdy = e.getRawY() - dragDownRawY;
                      if (!touchMoved && (Math.abs(rdx) > slop || Math.abs(rdy) > slop)) {
                          touchMoved = true;
                          handler.removeCallbacks(holdPauseRunnable); // it's a swipe, not a hold
                      }
                      if (!swipeDragging && !captionExpanded && rdy > slop * 2 && rdy > Math.abs(rdx) * 1.2f
                              && !isQuickReactionOverlayShown()) {
                          swipeDragging = true;
                          handler.removeCallbacks(holdPauseRunnable);
                          if (!touchHoldPaused) { touchHoldPaused = true; pauseProgress(); }
                      }
                      if (swipeDragging) applyDrag(rdx, Math.max(0f, rdy));
                      break;
                  }
                  case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: {
                      handler.removeCallbacks(holdPauseRunnable);
                      final boolean isUp = e.getActionMasked() == MotionEvent.ACTION_UP;
                      setChromeHidden(false); // hold released → header / bar fade back in
                      float vy = 0f;
                      if (dragVelocity != null) {
                          dragVelocity.addMovement(e);
                          dragVelocity.computeCurrentVelocity(1000);
                          vy = dragVelocity.getYVelocity();
                          dragVelocity.recycle(); dragVelocity = null;
                      }
                      if (swipeDragging) {
                          swipeDragging = false;
                          final float dy = Math.max(0f, e.getRawY() - dragDownRawY);
                          final float h = getResources().getDisplayMetrics().heightPixels;
                          if (isUp && (dy > h * DRAG_CLOSE_FRACTION || vy > DRAG_CLOSE_VELOCITY)) {
                              touchHoldPaused = false;      // closing: stay paused, no resume
                              closeByDrag();
                          } else {
                              snapBackFromDrag();
                              if (touchHoldPaused) { touchHoldPaused = false; resumeProgress(); }
                          }
                          break;
                      }
                      if (captionExpanded) { touchHoldPaused = false; collapseCaption(); break; } // tap outside closes the expanded caption
                      if (touchHoldPaused) { touchHoldPaused = false; resumeProgress(); }
                      boolean tap = isUp && !flung && !touchMoved
                              && (e.getEventTime() - e.getDownTime()) < TAP_MAX_MS;
                      if (tap) {
                          if (e.getX() < v.getWidth() * PREV_ZONE_FRACTION) previous(); else next();
                      }
                      break;
                  }
              }
              return true;
          });
      }
      // ── Buttons ───────────────────────────────────────────────────────────
      private void setupCloseButton() {
          binding.btnCloseStatus.setOnClickListener(v -> finishWithAnimation());
      }
      private void setupReactionButton() {
          actReact = () -> {
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
          };
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
      // v507: the overlay is a ViewStub — inflated + wired on first swipe-up by
      // ensureQuickReactionOverlay() (no eager setup in onCreate any more).
      private void openQuickReactionOverlay() {
          if (myUid != null && myUid.equals(ownerUid)) return; // owner can't react to own status
          final View overlay = ensureQuickReactionOverlay();
          pauseProgress();
          overlay.setAlpha(0f);
          overlay.setVisibility(View.VISIBLE);
          overlay.animate().alpha(1f).setDuration(150).start();
          if (quickReactionCardView != null) {
              quickReactionCardView.setScaleX(0.85f);
              quickReactionCardView.setScaleY(0.85f);
              quickReactionCardView.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
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
          final View overlay = quickReactionOverlayView;
          if (overlay == null) return; // never opened → nothing to close
          overlay.animate().alpha(0f).setDuration(120)
                  .withEndAction(() -> overlay.setVisibility(View.GONE)).start();
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
          // Long-press = full emoji picker (btn_react is a hidden proxy now).
          binding.btnLikeHeart.setOnLongClickListener(v -> { runAct(actReact); return true; });
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
              binding.llReplyRow.setVisibility(View.GONE); // own story: no reply/react row (bar stays as the nav-bar spacer)
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
              // Instagram-style paper plane: nothing typed → it shares the story
              // (same forward sheet as the ⋯ menu); typed text → sends it as a reply.
              if (TextUtils.isEmpty(msg)) { runAct(actForward); return; }
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
          actDownload = () -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null) return;
              if (!StatusDownloadHelper.hasPermission(this)) {
                  StatusDownloadHelper.requestPermission(this); return;
              }
              StatusDownloadHelper.downloadStatus(this, current);
          };
      }
      /** FIX v26: was findViewWithTag("btn_forward") — tag not in XML → always null → click never registered */
      private void setupForwardButton() {
          actForward = () -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null || myUid == null) return;
              pauseProgress();
              StatusForwardBottomSheet.show(this, current, myUid, this::resumeProgress);
          };
      }
      /**
       * Repost button — lets a viewer repost the current status to their own story.
       * Only visible when owner's allowSharing == true. On click, launches
       * StoryReshareActivity with contentType="status" carrying the full attribution.
       */
      private void setupRepostButton() {
          actRepost = () -> {
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
          };
      }
      /**
       * Level-stabilizer toggle — global viewer preference (persists across
       * all stories, unlike allowSharing/stabilizerEnabled which are set by
       * the CREATOR per-story). Tapping flips StoryStabilizerPrefs and
       * immediately starts/stops the running StorySpinViewController so the
       * change is felt on the story currently open, no restart needed.
       */
      private void setupStabilizerButton() {
          actStabilizer = () -> {
              boolean nowEnabled = !StoryStabilizerPrefs.isEnabled(this);
              StoryStabilizerPrefs.setEnabled(this, nowEnabled);
              Toast.makeText(this, nowEnabled ? "Stabilizer on" : "Stabilizer off",
                      Toast.LENGTH_SHORT).show();
              startSpinViewIfAllowed();
          };
      }
      /** Starts spinView only when: the viewer's own preference allows it,
       *  the applicable "does the content allow it" flag allows it, AND the
       *  media itself isn't already landscape/rotated (a landscape video's
       *  own framing is the "rotation" — counter-rotating it too would
       *  fight the shot rather than help it). Safe to call any time.
       *
       *  Which "content allows it" flag applies depends on where we are:
       *   - Normal Stories tray (isHighlightMode == false): each story's
       *     OWN StatusItem.stabilizerEnabled (opt-in per post, default off).
       *   - Highlight album (isHighlightMode == true): the ALBUM-WIDE
       *     highlightStabilizerEnabled flag (opt-in per Highlight, default
       *     off, set later via the long-press "Level Stabilizer" option) —
       *     the individual story's own flag is deliberately ignored here,
       *     so turning it on for the album applies uniformly to every
       *     story inside it regardless of how each was originally posted. */
      private void startSpinViewIfAllowed() {
          if (spinView == null) return;
          StatusItem current = idx < items.size() ? items.get(idx) : null;
          boolean creatorAllows = isHighlightMode
                  ? highlightStabilizerEnabled
                  : (current != null && current.stabilizerEnabled);
          boolean isLandscapeMedia = current != null
                  && current.mediaWidth > 0 && current.mediaHeight > 0
                  && current.mediaWidth > current.mediaHeight;
          if (StoryStabilizerPrefs.isEnabled(this) && creatorAllows
                  && !isLandscapeMedia) {
              // First-time-ever "Tilt your phone" hint (Instagram-style),
              // shown the moment the stabilizer actually engages. No-op on
              // every call after the very first, and no-op instantly if
              // this viewer has already seen it on any earlier story — see
              // StoryRotateHintPrefs. Fired here rather than unconditionally
              // in onCreate so it only ever shows on a story that genuinely
              // has the effect on, never on an ordinary one.
              StoryRotateHintView.maybeShow(this, (android.view.ViewGroup) binding.getRoot());
              spinView.start();
          } else {
              spinView.stop();
          }
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
          String stabLabel = StoryStabilizerPrefs.isEnabled(this) ? "Turn stabilizer off" : "Turn stabilizer on";
          String[] opts = {"Who viewed this", "Delete this status", "Archive status", "Add to Highlights", "Analytics", stabLabel, "Cancel"};
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
                  } else if (w == 5) {
                      runAct(actStabilizer);
                      resumeProgress();
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
                  "Set as Cover", "Delete Entire Highlight",
                  StoryStabilizerPrefs.isEnabled(this) ? "Turn stabilizer off" : "Turn stabilizer on", "Cancel"};
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
                  } else if (w == 5) {
                      runAct(actStabilizer);
                      resumeProgress();
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
          final StatusItem cur = idx < items.size() ? items.get(idx) : null;
          // The floating side buttons moved into this menu. The (now hidden) buttons stay as click
          // proxies, so their listeners — permission check, pause/resume, allowSharing toast —
          // are reused as-is.
          final java.util.List<String> labels = new java.util.ArrayList<>();
          final java.util.List<Runnable> actions = new java.util.ArrayList<>();
          labels.add(StatusMuteManager.isMuted(this, ownerUid) ? "Unmute " + ownerName : "Mute " + ownerName);
          actions.add(() -> {
              StatusMuteManager.toggle(this, ownerUid);
              Toast.makeText(this, StatusMuteManager.isMuted(this, ownerUid)
                      ? ownerName + " muted" : ownerName + " unmuted", Toast.LENGTH_SHORT).show();
              finish();
          });
          labels.add("Download");  actions.add(() -> { runAct(actDownload); resumeProgress(); });
          labels.add("Forward");   actions.add(() -> runAct(actForward)); // sheet resumes progress itself
          if (cur != null && cur.allowSharing) {
              labels.add("Repost to my status"); actions.add(() -> runAct(actRepost));
          }
          labels.add(StoryStabilizerPrefs.isEnabled(this) ? "Turn stabilizer off" : "Turn stabilizer on");
          actions.add(() -> { runAct(actStabilizer); resumeProgress(); });
          labels.add("Report");    actions.add(() -> { Toast.makeText(this, "Reported", Toast.LENGTH_SHORT).show(); resumeProgress(); });
          labels.add("Cancel");    actions.add(this::resumeProgress);
          AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
              .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
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
              applyVideoVolume();
              applyMusicVolume();
              // Muted → don't hold other apps' audio hostage; unmuted → take focus again.
              if (isMuted) { if (musicFocus != null) musicFocus.abandon(); }
              else if (!paused) resumeMusicPlayback();
              binding.btnMute.setImageResource(isMuted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
              syncMusicStickerPlaying();
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
              // v508: hidden btn_react proxy removed; the heart (btn_like_heart) carries the state.
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
          if (s == null || s.id == null || s.id.isEmpty()) {
              if (lastReplyBubbleView != null) lastReplyBubbleView.setVisibility(View.GONE);
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
              @Override public void onCancelled(DatabaseError e) {
                  android.util.Log.e("StatusViewer",
                      "Replies listener cancelled for " + ownerUid + "/" + s.id
                          + ": " + e.getMessage());
              }
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
          if (replyOverlayAutoHide != null) handler.removeCallbacks(replyOverlayAutoHide);
          if (newestFirst.isEmpty()) {
              // No replies → never inflate the bubble; just reset it if it was shown before.
              if (lastReplyBubbleView != null) {
                  lastReplyBubbleView.setVisibility(View.GONE);
                  lastReplyBubbleView.setAlpha(1f);
              }
              return;
          }
          final View bubble = lastReplyBubble(); // v508: inflated on first status that has replies
          final android.widget.ImageView[] slots = lastReplyAvatars;
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
          if (lastReplyTextTv != null) {
              String who = latest.name != null && !latest.name.isEmpty() ? latest.name : "Someone";
              lastReplyTextTv.setText(who + ": " + (latest.text != null ? latest.text : ""));
          }
          bubble.animate().cancel();
          bubble.setAlpha(1f);
          bubble.setVisibility(View.VISIBLE);
          bubble.setOnClickListener(v -> {
              if (replyOverlayAutoHide != null) handler.removeCallbacks(replyOverlayAutoHide);
              pauseProgress();
              com.callx.app.interactions.StatusRepliesBottomSheet.show(this, ownerUid, myUid, s, this::resumeProgress);
          });
          replyOverlayAutoHide = () -> {
              bubble.animate()
                  .alpha(0f).setDuration(300)
                  .withEndAction(() -> bubble.setVisibility(View.GONE)).start();
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
          if (s.timestamp != null) binding.tvTimestamp.setText(formatAgoShort(System.currentTimeMillis() - s.timestamp));
          else binding.tvTimestamp.setText("");
      }
      /** FIX v26: was findViewWithTag("tv_expiry_label") → null, now binding.tvExpiryLabel */
      private void updateExpiryLabel(StatusItem s) {
          if (isHighlightMode || myUid == null || !myUid.equals(ownerUid)) {
              binding.tvExpiryLabel.setVisibility(View.GONE); return;
          }
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
      /** Instagram-style compact age for the header: "now", "32m", "17h", "2d". */
      private String formatAgoShort(long ms) {
          if (ms < 60_000) return getString(com.callx.app.core.R.string.status_time_now);
          if (ms < 3_600_000) return (ms / 60_000) + "m";
          if (ms < 86_400_000) return (ms / 3_600_000) + "h";
          return (ms / 86_400_000) + "d";
      }
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
      /** Debug-only logging: release builds skip the Log call (it was firing per status item). */
      private boolean dbgChecked, dbgOn;
      private void dbgLog(String msg) {
          if (!dbgChecked) { dbgOn = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0; dbgChecked = true; }
          if (dbgOn) Log.d(DBG, msg);
      }
      private int dpToPx(int dp) {
          return Math.round(dp * getResources().getDisplayMetrics().density);
      }
      /** Stops the current video and resets the SHARED player (kept alive for the next video status).
       *  The old listener is removed first so a stale STATE_ENDED/error can never call next(). */
      private void releasePlayer() {
          if (player != null) {
              try {
                  if (playerListener != null) player.removeListener(playerListener);
                  player.stop();
                  player.clearMediaItems();
              } catch (Exception ignored) {}
          }
          playerListener = null;
          releaseMusicPlayer();
          if (binding != null && binding.reactionBurstOverlay != null) binding.reactionBurstOverlay.stop();
      }
      /** Really frees the shared player (activity is going away). */
      private void destroyPlayer() {
          if (player != null) { try { player.release(); } catch (Exception ignored) {} player = null; }
          playerListener = null;
      }
  }