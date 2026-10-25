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
  import org.json.JSONObject;
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
      /** Optional — avatar URL the caller already has (status tray). Avoids relying on StatusItem.ownerPhoto. */
      public static final String EXTRA_OWNER_PHOTO = "ownerPhoto";
      public static final String EXTRA_QUEUE_OWNER_PHOTOS = "queueOwnerPhotos";
      /** Optional — set by ChatActivity when opened from a reply/reaction
       *  quote-box tap, so the viewer opens directly on the exact status
       *  that was replied/reacted to (WhatsApp jumps to that specific
       *  story, not just the owner's oldest active one). */
      public static final String EXTRA_TARGET_STATUS_ID = "targetStatusId";
      /** Open ONLY the target status (no other stories of the owner) — used by the reshared-story chip so Back returns straight to the reshare. */
      public static final String EXTRA_SINGLE_STATUS_ONLY = "singleStatusOnly";
      private boolean singleStatusOnly;
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
      // ── Standby player (video → video advance) ───────────────────────────
      // A SECOND ExoPlayer pre-prepares (and pre-buffers) the NEXT video while the current status
      // plays; when that video's turn comes the two players are swapped (ping-pong) instead of
      // resetting one player and re-opening the source. Only ever 2 players. Kill switch: set
      // STANDBY_PLAYER_ENABLED=false to get the old single-player behaviour back.
      private static final boolean STANDBY_PLAYER_ENABLED = true;
      private ExoPlayer standbyPlayer;             // idle, or holding `standbyUrl` prepared-but-paused
      private String    standbyUrl;                // null = standbyPlayer is idle
      private boolean   videoFrameRendered;        // first frame of the CURRENT video is on screen
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
      private static final java.util.concurrent.ExecutorService REPLY_PARSE_BG =
              java.util.concurrent.Executors.newSingleThreadExecutor();
      private Query repliesListenerRef;
      private Query repliesSyncQuery;      // v514: keepSynced(true) while this status is on screen
      private boolean ownProfilePrewarmed; // v514
      private ValueEventListener repliesListener;
      private ValueEventListener liveStatusListener;
      private com.google.firebase.database.DatabaseReference liveStatusRef;
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
          // v639: publish the real measured header-chrome height so the editor's safe-area guide follows
          // this layout instead of a hardcoded dp (see StoryChromePrefs / StorySafeAreaView).
          binding.llTopChrome.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
              if (b - t > 0) com.callx.app.utils.StoryChromePrefs.publishTop(StatusViewerActivity.this, b - t);
          });
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
      private View        reshareChipView;
      private View        reshareContainerView;   // null until first reshared status
      private String      ownerPhotoHint;
      private final List<String> queueOwnerPhotos = new ArrayList<>();
      private View        quickReactionOverlayView; // null until first swipe-up-to-react
      private View        quickReactionCardView;
      private TextView[]  quickReactionCells;
      private androidx.activity.OnBackPressedCallback quickReactionBack;
      private boolean     quickReactionSent;
      private static final String[] QUICK_EMOJIS = {
              "\uD83D\uDE02", "\uD83D\uDE2E", "\uD83D\uDE0D",   // 😂 😮 😍
              "\uD83D\uDE22", "\uD83D\uDC4F", "\uD83D\uDD25" }; // 😢 👏 🔥
      private static final String[] QUICK_EMOJI_NAMES = {
              "laughing face", "surprised face", "heart-eyes face",
              "crying face", "clapping hands", "fire" };
      /** Keeps the scrim exactly above the reply bar while the keyboard moves it. */
      private final View.OnLayoutChangeListener quickReactionBarListener =
              (v, l, t, r, bt, ol, ot, or, ob) -> syncQuickReactionInsets();
      private boolean     columnHwLayerOn = false;
      // v583: user-switch perf — shared interpolator (no per-swipe alloc), deferred live listener,
      // and "already warmed neighbours for this queue position" marker.
      private static final android.view.animation.Interpolator CUBE_INTERP =
              new android.view.animation.DecelerateInterpolator();
      private int liveAttachSeq = 0;
      private int adjacentWarmedPos = Integer.MIN_VALUE;
      // v508: last-reply bubble is a ViewStub (only inflated once a status really has replies)
      private View        lastReplyBubbleView;
      private String      lastReplySig;      // newest-3 signature → skip rebind/animation when nothing changed
      private long        replyHideAtMs, replyHideRemainingMs; // auto-hide deadline (uptime) / time left while paused
      private boolean     replyHidePending, lastReplyExiting;
      private View        lastReplyMoreTv;
      private android.widget.LinearLayout mentionChip; // "Add to your story  ✕" — only on statuses that mention me
      private boolean     lastReplyShown;    // bubble currently on screen (entry already played)
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
      /** Extends the bubble's tap area to ~48dp (visual pill stays compact). Must be cleared whenever the bubble is hidden. */
      private void setReplyHitSlop(boolean on) {
          final View b = lastReplyBubbleView;
          if (b == null || !(b.getParent() instanceof android.view.ViewGroup)) return;
          final android.view.ViewGroup parent = (android.view.ViewGroup) b.getParent();
          if (!on) { parent.setTouchDelegate(null); return; }
          b.post(() -> {
              if (b.getVisibility() != View.VISIBLE || b.getWidth() == 0) return;
              final int slopV = Math.max(0, (dpToPx(48) - b.getHeight()) / 2), slopH = dpToPx(8);
              android.graphics.Rect r = new android.graphics.Rect(
                      b.getLeft() - slopH, b.getTop() - slopV, b.getRight() + slopH, b.getBottom() + slopV);
              parent.setTouchDelegate(new android.view.TouchDelegate(r, b));
          });
      }
      private boolean replyCollapsed;
      /** Collapses the pill to avatars only (text hidden) or expands it back; one short ChangeBounds, no per-frame work. */
      private void setReplyCollapsed(boolean collapse) {
          final View b = lastReplyBubbleView;
          if (b == null || lastReplyTextTv == null || replyCollapsed == collapse) return;
          replyCollapsed = collapse;
          if (b.getVisibility() == View.VISIBLE && b instanceof android.view.ViewGroup) {
              android.transition.TransitionManager.beginDelayedTransition((android.view.ViewGroup) b,
                      new android.transition.ChangeBounds().setDuration(200L));
          }
          lastReplyTextTv.setVisibility(collapse ? View.GONE : View.VISIBLE);
          // tap area follows the new width once the transition settles
          handler.postDelayed(() -> { if (lastReplyShown && !chromeHidden) setReplyHitSlop(true); }, 260L);
      }
      // ── @mention taps (text status + caption) and the mentions list ───────────────────────────
      private static final java.util.regex.Pattern MENTION_TAP = java.util.regex.Pattern.compile("@([A-Za-z0-9_]{3,30})");
      /** If the tap lands on an @username inside the visible text status / caption, returns that username. */
      private String mentionAt(TextView tv, float rawX, float rawY) {
          if (tv == null || !tv.isShown() || tv.getLayout() == null || tv.getText() == null) return null;
          final int[] loc = new int[2];
          tv.getLocationOnScreen(loc);
          final float x = rawX - loc[0] - tv.getTotalPaddingLeft() + tv.getScrollX();
          final float y = rawY - loc[1] - tv.getTotalPaddingTop() + tv.getScrollY();
          if (x < 0 || y < 0 || x > tv.getLayout().getWidth() || y > tv.getLayout().getHeight()) return null;
          final int line = tv.getLayout().getLineForVertical((int) y);
          final int off = tv.getLayout().getOffsetForHorizontal(line, x);
          final java.util.regex.Matcher m = MENTION_TAP.matcher(tv.getText());
          while (m.find()) if (off >= m.start() && off <= m.end()) return m.group(1).toLowerCase();
          return null;
      }
      private boolean tryOpenMentionAt(MotionEvent e) {
          String u = mentionAt(textStatusTv, e.getRawX(), e.getRawY());
          if (u == null) u = mentionAt(binding.tvCaption, e.getRawX(), e.getRawY());
          if (u == null) return false;
          openProfileByUsername(u);
          return true;
      }
      /** usernames/{name} → uid → profile. Pauses the story while looking up / away; resumes if nothing opens. */
      private void openProfileByUsername(String username) {
          pauseProgress();
          FirebaseUtils.db().getReference("usernames").child(username).get().addOnSuccessListener(ns -> {
              String uid = ns.getValue(String.class);
              if (uid == null || uid.isEmpty()) {
                  Toast.makeText(this, getString(R.string.viewer_toast_user_not_found, username), Toast.LENGTH_SHORT).show();
                  resumeProgress(); return;
              }
              openProfileByUid(uid, username);
          }).addOnFailureListener(x -> resumeProgress());
      }
      private void openProfileByUid(String uid, String fallbackName) {
          FirebaseUtils.getUserRef(uid).get().addOnSuccessListener(u -> {
              String name = u.child("name").getValue(String.class);
              if (name == null || name.isEmpty()) name = fallbackName;
              String photo = u.child("profileImage").getValue(String.class);
              if (photo == null || photo.isEmpty()) photo = u.child("photoUrl").getValue(String.class);
              try {
                  Intent intent = new Intent(this, Class.forName("com.callx.app.activities.UserProfileActivity"));
                  intent.putExtra("uid", uid); intent.putExtra("name", name);
                  if (photo != null) intent.putExtra("photo", photo);
                  startActivity(intent); // onResume() resumes the story when we come back
              } catch (Exception ex) { resumeProgress(); }
          }).addOnFailureListener(x -> resumeProgress());
      }
      /** Dialog listing everyone mentioned in this status (server-resolved mentionNames); tap → profile. */
      private void showMentionsList(StatusItem s) {
          if (s == null || s.mentionNames == null || s.mentionNames.isEmpty()) {
              Toast.makeText(this, R.string.viewer_toast_no_mentions, Toast.LENGTH_SHORT).show(); resumeProgress(); return;
          }
          final List<String> names = new ArrayList<>(s.mentionNames.keySet());
          Collections.sort(names);
          final String[] labels = new String[names.size()];
          for (int i = 0; i < labels.length; i++) labels[i] = "@" + names.get(i);
          AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
              .setTitle("Mentioned (" + labels.length + ")")
              .setItems(labels, (d, w) -> {
                  String uid = s.mentionNames.get(names.get(w));
                  if (uid != null && !uid.isEmpty()) openProfileByUid(uid, names.get(w)); else resumeProgress();
              })
              .setOnCancelListener(d -> resumeProgress())
              .create());
      }
      // ── "Mentioned you" chip (Instagram-style: add to your story / remove mention) ──────────
      /** usernames in s.mentionNames whose uid is me (server-written by functions: onStatusCreatedMentions). */
      private List<String> myMentionUsernames(StatusItem s) {
          List<String> out = new ArrayList<>();
          if (s == null || myUid == null || s.mentionNames == null) return out;
          for (Map.Entry<String, String> e : s.mentionNames.entrySet())
              if (myUid.equals(e.getValue())) out.add(e.getKey());
          return out;
      }
      private void updateMentionChip(StatusItem s) {
          final boolean show = !isHighlightMode && myUid != null && !myUid.equals(ownerUid)
                  && !myMentionUsernames(s).isEmpty();
          if (!show) { if (mentionChip != null) mentionChip.setVisibility(View.GONE); return; }
          if (mentionChip == null) {
              final android.widget.LinearLayout chip = new android.widget.LinearLayout(this);
              chip.setOrientation(android.widget.LinearLayout.HORIZONTAL);
              chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
              chip.setPadding(dpToPx(14), dpToPx(8), dpToPx(6), dpToPx(8));
              android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
              bg.setColor(0x99000000); bg.setCornerRadius(dpToPx(20));
              chip.setBackground(bg);
              final TextView add = new TextView(this);
              add.setText("Mentioned you \u00B7 Add to your story");
              add.setTextColor(0xFFFFFFFF); add.setTextSize(13);
              add.setTypeface(add.getTypeface(), android.graphics.Typeface.BOLD);
              add.setPadding(0, 0, dpToPx(8), 0);
              final TextView x = new TextView(this);
              x.setText("\u2715"); x.setTextColor(0xCCFFFFFF); x.setTextSize(14);
              x.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
              x.setContentDescription(getString(R.string.viewer_cd_remove_mention));
              chip.addView(add); chip.addView(x);
              add.setOnClickListener(v -> {
                  StatusItem cur = idx < items.size() ? items.get(idx) : null;
                  if (cur == null) return;
                  pauseProgress();
                  startActivity(com.callx.app.utils.StatusReshareHelper
                          .buildReshareStatusIntent(this, cur, ownerName, ownerUid)); // mentioned users may always reshare
              });
              x.setOnClickListener(v -> {
                  StatusItem cur = idx < items.size() ? items.get(idx) : null;
                  if (cur != null) removeMyMention(cur);
              });
              FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                      ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                      android.view.Gravity.BOTTOM | android.view.Gravity.END);
              lp.setMargins(0, 0, dpToPx(12), dpToPx(12));
              binding.flStoryCard.addView(chip, lp);
              mentionChip = chip;
          }
          mentionChip.bringToFront();
          mentionChip.setVisibility(View.VISIBLE);
      }
      /** "Remove mention": deletes my uid from the status' mentionNames and my statusMentions index row (both allowed by rules). */
      private void removeMyMention(StatusItem s) {
          if (s == null || s.id == null || myUid == null) return;
          for (String u : myMentionUsernames(s)) {
              FirebaseUtils.getStatusRef().child(ownerUid).child(s.id).child("mentionNames").child(u).removeValue();
              s.mentionNames.remove(u);
          }
          FirebaseUtils.db().getReference("statusMentions").child(myUid).child(ownerUid + "_" + s.id).removeValue();
          if (mentionChip != null) mentionChip.setVisibility(View.GONE);
          Toast.makeText(this, R.string.viewer_toast_mention_removed, Toast.LENGTH_SHORT).show();
      }
      private void scheduleReplyHide(long delayMs) {
          if (replyOverlayAutoHide == null) return;
          handler.removeCallbacks(replyOverlayAutoHide);
          replyHideAtMs = android.os.SystemClock.uptimeMillis() + delayMs;
          handler.postDelayed(replyOverlayAutoHide, delayMs);
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
              lastReplyMoreTv = b.findViewById(R.id.tv_last_reply_more);
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
          // Emojis are set from code (real UTF-16 surrogate pairs). The old "\uD83D\uDE02" escapes in the
          // layout XML rendered as EMPTY text on device -> an empty dark card (the swipe-up "bug").
          final int[] ids = { R.id.tv_qr_1, R.id.tv_qr_2, R.id.tv_qr_3, R.id.tv_qr_4, R.id.tv_qr_5, R.id.tv_qr_6 };
          quickReactionCells = new TextView[ids.length];
          for (int i = 0; i < ids.length; i++) {
              TextView tv = ov.findViewById(ids[i]);
              quickReactionCells[i] = tv;
              if (tv == null) continue;
              tv.setText(QUICK_EMOJIS[i]);
              tv.setTag(QUICK_EMOJIS[i]);
              tv.setContentDescription(getString(R.string.viewer_cd_react_with, QUICK_EMOJI_NAMES[i]));
              tv.setOnClickListener(this::onQuickEmojiTapped);
              tv.setOnTouchListener((cv, ev) -> {
                  if (quickReactionSent) return false;
                  switch (ev.getActionMasked()) {
                      case MotionEvent.ACTION_DOWN:
                          cv.animate().cancel();
                          cv.animate().scaleX(0.86f).scaleY(0.86f).alpha(0.75f).setStartDelay(0).setDuration(70).start();
                          break;
                      case MotionEvent.ACTION_UP:
                      case MotionEvent.ACTION_CANCEL:
                          cv.animate().cancel();
                          cv.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0).setDuration(90).start();
                          break;
                      default: break;
                  }
                  return false; // let the click listener fire
              });
          }
          // 2: typing hides the grid (Instagram); clearing the text brings it back.
          binding.etReply.addTextChangedListener(new android.text.TextWatcher() {
              @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
              @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
              @Override public void afterTextChanged(android.text.Editable e) {
                  if (!binding.etReply.hasFocus() || quickReactionSent) return;
                  if (e == null || e.length() == 0) showQuickReactionUi();
                  else hideQuickReactionUi();
              }
          });
          // Scrim: tap outside the grid or swipe down = dismiss (Instagram). Grid cells consume their own taps.
          final GestureDetector scrimGestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
              @Override public boolean onDown(MotionEvent e) { return true; }
              @Override public boolean onSingleTapUp(MotionEvent e) { closeQuickReactionOverlay(); return true; }
              @Override public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float vx, float vy) {
                  if (e1 != null && vy > 600 && Math.abs(vy) > Math.abs(vx)) { closeQuickReactionOverlay(); return true; }
                  return false;
              }
          });
          ov.setOnTouchListener((v, e) -> scrimGestures.onTouchEvent(e));
          if (quickReactionCardView != null) quickReactionCardView.setOnClickListener(v -> {});
          // Back closes just the overlay (+ keyboard), not the whole viewer.
          quickReactionBack = new androidx.activity.OnBackPressedCallback(false) {
              @Override public void handleOnBackPressed() { closeQuickReactionOverlay(); }
          };
          getOnBackPressedDispatcher().addCallback(this, quickReactionBack);
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
          android.os.Trace.beginSection("StatusViewer#open"); // macrobenchmark TraceSectionMetric
          warmDecoderInfoAsync(); // codec list query off the main thread → first video builds its decoder faster
          overridePendingTransition(0, 0); // v508: no system open animation (theme also disables it) → faster first paint
          binding = ActivityStatusViewerBinding.inflate(getLayoutInflater());
          setContentView(binding.getRoot());
          getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
          setupEdgeToEdge();
          ownerUid  = getIntent().getStringExtra(EXTRA_OWNER_UID);
          ownerName = getIntent().getStringExtra(EXTRA_OWNER_NAME);
          ownerPhotoHint = getIntent().getStringExtra(EXTRA_OWNER_PHOTO);
          List<String> qPhotos = getIntent().getStringArrayListExtra(EXTRA_QUEUE_OWNER_PHOTOS);
          if (qPhotos != null) queueOwnerPhotos.addAll(qPhotos);
          targetStatusId = getIntent().getStringExtra(EXTRA_TARGET_STATUS_ID);
          singleStatusOnly = getIntent().getBooleanExtra(EXTRA_SINGLE_STATUS_ONLY, false);
          highlightAlbumId = getIntent().getStringExtra(EXTRA_HIGHLIGHT_ALBUM_ID);
          isHighlightMode = highlightAlbumId != null && !highlightAlbumId.isEmpty();
          if (ownerUid == null) { android.os.Trace.endSection(); finish(); return; }
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
          spinView.attachTargets(binding.ivStatus, binding.playerView, binding.ivVideoPoster);
          setupStabilizerButton();
          setupMediaRoundedCorners();
          bindOwnerAvatar(null); // instant: tray's own URL (L2/L3 cache hit), before the status list even loads
          load(ownerUid);
          android.os.Trace.endSection();
      }

      // ── Decoder warm-up ───────────────────────────────────────────────────
      // MediaCodecUtil caches the codec list per MIME type; the FIRST query walks the whole
      // MediaCodecList (tens of ms, sometimes 100+ on low-end devices). ExoPlayer would do it on
      // the first video's critical path, so we do it once per process on a background thread.
      private static final java.util.concurrent.atomic.AtomicBoolean DECODERS_WARMED =
              new java.util.concurrent.atomic.AtomicBoolean(false);
      @OptIn(markerClass = UnstableApi.class)
      private static void warmDecoderInfoAsync() {
          if (!DECODERS_WARMED.compareAndSet(false, true)) return;
          Thread t = new Thread(() -> {
              try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); }
              catch (Throwable ignored) {}
              for (String mime : new String[]{"video/avc", "video/hevc", "audio/mp4a-latm"}) {
                  try {
                      androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, false, false);
                  } catch (Throwable ignored) {}
              }
          }, "status-decoder-warm");
          t.setDaemon(true);
          t.start();
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
          // Priority: tray URL (same cache key the tray painted) → item's own → any loaded item with a photo.
          String photo = ownerPhotoHint;
          if (photo == null || photo.isEmpty()) photo = item != null ? item.ownerPhoto : null;
          if ((photo == null || photo.isEmpty()) && !items.isEmpty()) {
              for (StatusItem it : items) {
                  if (it != null && it.ownerPhoto != null && !it.ownerPhoto.isEmpty()) { photo = it.ownerPhoto; break; }
              }
          }
          if (photo == null || photo.isEmpty()) {
              // Never leave the previous person's avatar showing after a queue switch.
              binding.ivOwner.setImageResource(R.drawable.ic_person);
              return;
          }
          long version = com.callx.app.cache.AvatarVersionSyncManager
                  .getInstance(this).getCachedVersion(ownerUid);
          com.callx.app.cache.StatusAvatarBinder.bind(
                  this, binding.ivOwner, photo, version, R.drawable.ic_person);
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
          handler.removeCallbacks(expiryWatchRunnable);
          detachAddYoursCounts();
          detachLiveStatusListener();
          detachRepliesListener();
          releasePlayer();
          destroyPlayer();
          releaseStandby();
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
                                  R.string.viewer_toast_status_unavailable, Toast.LENGTH_SHORT).show();
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
                              if (singleStatusOnly) { StatusItem keep = items.get(found); items.clear(); items.add(keep); idx = 0; }
                          } else {
                              // The specific status this reply/reaction pointed
                              // at has since expired/been deleted, but the
                              // owner still has other active statuses — let
                              // the viewer open (at the first one) rather than
                              // silently failing, but say why the exact one
                              // isn't there.
                              Toast.makeText(StatusViewerActivity.this,
                                      R.string.viewer_toast_that_status_unavailable, Toast.LENGTH_SHORT).show();
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
          scheduleLiveStatusListener(uid); // v583: after first frame, not before it
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
                          if (!s.isLive(now)) continue;
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
                                  R.string.viewer_toast_highlight_empty, Toast.LENGTH_SHORT).show();
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
      // ── Dead-status pruning (deleted / expired while viewer is open) ──────
      private static boolean isDead(StatusItem s, long now) {
          return !StatusItem.isLive(s, now);
      }
      /** Drops deleted/expired items and rebuilds the segment bars. Highlights are permanent → skipped.
       *  Returns true if the item that was current (idx) itself got removed; idx is kept pointing at the same item (or its successor). */
      /** If the status being watched hits its expiry while the viewer is open, drop it and move on (ring/queue stay consistent). */
      private final Runnable expiryWatchRunnable = () -> {
          if (isFinishing() || isDestroyed() || isHighlightMode || items.isEmpty()) return;
          if (idx >= items.size() || !isDead(items.get(idx), com.callx.app.utils.ServerClock.now())) {
              // not expired yet (clock moved) — re-arm
              if (idx < items.size()) scheduleExpiryWatch(items.get(idx));
              return;
          }
          releasePlayer(); stopProgress();
          pruneDead();            // current removed -> idx already points at its successor
          if (items.isEmpty()) { finish(); return; }
          showCurrent();
      };
      private void scheduleExpiryWatch(StatusItem s) {
          handler.removeCallbacks(expiryWatchRunnable);
          if (isHighlightMode || s == null) return;
          long left = s.effectiveExpiry() - com.callx.app.utils.ServerClock.now();
          if (left <= 0) left = 250L;
          handler.postDelayed(expiryWatchRunnable, Math.min(left + 250L, 60_000L));
      }
      private boolean pruneDead() {
          if (isHighlightMode || items.isEmpty()) return false;
          final long now = com.callx.app.utils.ServerClock.now();
          boolean changed = false, curRemoved = false;
          int newIdx = idx;
          for (int i = items.size() - 1; i >= 0; i--) {
              if (!isDead(items.get(i), now)) continue;
              items.remove(i);
              changed = true;
              if (i < idx) newIdx--; else if (i == idx) curRemoved = true;
          }
          if (changed) { idx = Math.max(0, newIdx); buildSegmentBars(); }
          return curRemoved;
      }
      private void attachLiveStatusListener(String uid) {
          detachLiveStatusListener();
          if (uid == null) return;
          liveStatusRef = FirebaseUtils.getStatusRef().child(uid);
          liveStatusListener = new ValueEventListener() {
              @Override public void onDataChange(@NonNull DataSnapshot snap) {
                  if (isFinishing() || isDestroyed() || items.isEmpty()) return;
                  final long now = com.callx.app.utils.ServerClock.now();
                  Set<String> alive = new HashSet<>();
                  for (DataSnapshot c : snap.getChildren()) {
                      try {
                          StatusItem si = c.getValue(StatusItem.class);
                          if (si != null && !isDead(si, now) && si.id != null) alive.add(si.id);
                      } catch (Exception ignored) {}
                  }
                  StatusItem cur = idx < items.size() ? items.get(idx) : null;
                  boolean changed = false;
                  for (int i = items.size() - 1; i >= 0; i--) {
                      StatusItem it = items.get(i);
                      if (it.id != null && !alive.contains(it.id)) { items.remove(i); changed = true; }
                  }
                  if (!changed) return;
                  if (items.isEmpty()) { finish(); return; }
                  buildSegmentBars();
                  int pos = cur == null ? -1 : items.indexOf(cur);
                  if (pos >= 0) { idx = pos; fillSegmentsBefore(idx); }
                  else { stopProgress(); releasePlayer(); idx = Math.min(idx, items.size() - 1); showCurrent(); }
              }
              @Override public void onCancelled(@NonNull DatabaseError e) {}
          };
          liveStatusRef.addValueEventListener(liveStatusListener);
      }
      /** v583: the live-status ValueEventListener re-reads + parses the owner's whole status node on
       *  the main thread the moment it attaches. Attach it AFTER the first frame of the new owner
       *  (and drop it if the user already swiped on) so it never sits in the swipe's critical path. */
      private void scheduleLiveStatusListener(final String uid) {
          if (uid == null) return;
          final int token = ++liveAttachSeq;
          android.view.Choreographer.getInstance().postFrameCallback(t -> handler.postDelayed(() -> {
              if (token != liveAttachSeq || isFinishing() || isDestroyed() || !uid.equals(ownerUid)) return;
              attachLiveStatusListener(uid);
          }, 120L));
      }
      private void detachLiveStatusListener() {
          liveAttachSeq++; // cancels any pending scheduled attach
          if (liveStatusRef != null && liveStatusListener != null)
              liveStatusRef.removeEventListener(liveStatusListener);
          liveStatusRef = null; liveStatusListener = null;
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
          scheduleExpiryWatch(s);
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
              setReplyHitSlop(false);
              lastReplyBubbleView.setVisibility(View.GONE);
              lastReplyBubbleView.setTranslationY(0f);
          }
          clearLiveComments(); // previous status's floating comments must not linger
          lastReplyShown = false; lastReplySig = null; lastReplyExiting = false; replyCollapsed = false;
          final StatusItem shown = s;
          deferAfterFirstFrame(seq, () -> updateLastReplyOverlay(shown));
          updateExpiryLabel(s);
          updateMentionChip(s);
          deferAfterFirstFrame(seq, () -> playReactionBurstIfAny(shown));
          deferAfterFirstFrame(seq, this::prefetchAdjacentQueueEntries); // v583: both neighbours, once per owner
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
          applyReshareCardMode(s);
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
                      final android.view.View chip = flReshare.findViewById(R.id.ll_reshare_chip);
                      final android.view.View bigCard = flReshare.findViewById(R.id.ll_reshare_attribution_card);
                      if ("status".equals(s.resharedFromType) && chip != null) {
                          // Reshared STORY → small chip; tap opens the original owner's story (profile if it's gone).
                          if (bigCard != null) bigCard.setVisibility(android.view.View.GONE);
                          chip.setVisibility(android.view.View.VISIBLE);
                          final String cName = s.resharedFromOwnerName;
                          final String cUser = s.resharedFromOwnerUsername;
                          // @username when we have it; otherwise the display name WITHOUT an "@" (names contain spaces)
                          final String chipLabel = (cUser != null && !cUser.isEmpty()) ? "@" + cUser : cName;
                          ((android.widget.TextView) flReshare.findViewById(R.id.tv_reshare_chip_text)).setText("\u21BB " + chipLabel);
                          final android.widget.ImageView cAv = flReshare.findViewById(R.id.iv_reshare_chip_avatar);
                          if (cAv != null) {
                              if (s.resharedFromOwnerAvatar != null && !s.resharedFromOwnerAvatar.isEmpty()) {
                                  cAv.setVisibility(android.view.View.VISIBLE);
                                  cAv.setOutlineProvider(new android.view.ViewOutlineProvider() { // round (was Glide circleCrop)
                                      @Override public void getOutline(View v, android.graphics.Outline o) {
                                          o.setOval(0, 0, v.getWidth(), v.getHeight());
                                      }
                                  });
                                  cAv.setClipToOutline(true);
                                  bindReshareAvatar(cAv, s.resharedFromOwnerAvatar, cName, s.resharedFromOwnerUid);
                              } else cAv.setVisibility(android.view.View.GONE);
                          }
                          reshareChipView = chip;
                          chip.setAlpha(1f); chip.setEnabled(true);
                          if (!java.util.Objects.equals(flReshare.getTag(), s.id)) { // entry: fade + 6dp rise, once per status
                              flReshare.setTag(s.id);
                              flReshare.setAlpha(0f); flReshare.setTranslationY(dpToPx(6));
                              flReshare.animate().alpha(chromeHidden ? 0f : 1f).translationY(0f).setDuration(220L).setStartDelay(150L).start();
                          }
                          chip.setContentDescription(getString(R.string.viewer_cd_view_original_by, cName));
                          final String cUid = s.resharedFromOwnerUid != null ? s.resharedFromOwnerUid : "";
                          final String cId  = s.resharedFromId != null ? s.resharedFromId : "";
                          chip.setOnClickListener(v -> openOriginalStory(cUid, cId, chipLabel));
                          if (reshareCardActive) flReshare.setVisibility(android.view.View.GONE); // header on the card replaces the chip
                          return;
                      }
                      if (chip != null) chip.setVisibility(android.view.View.GONE);
                      flReshare.animate().cancel(); flReshare.setAlpha(1f); flReshare.setTranslationY(0f); flReshare.setTag(null);
                      if (bigCard != null) bigCard.setVisibility(android.view.View.VISIBLE);
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
              android.widget.Toast.makeText(this, R.string.viewer_toast_original_post_open_failed,
                  android.widget.Toast.LENGTH_SHORT).show();
          }
      }

      /** Tapping the reshare attribution card (anywhere except the explicit
       *  "View Original →" sub-text) opens the ORIGINAL CREATOR's reels
       *  profile screen — same UserReelsActivity used everywhere else in the
       *  app as the generic profile screen. feature-status has no compile-time
       *  dependency on feature-reels, so this reflects the class by name,
       *  same pattern as openOriginalContent() above. */
      /**
       * Reshared story chip → the ORIGINAL owner's story. Checks (in order): id present, I haven't blocked the
       * owner, the original still exists / isn't deleted / isn't expired, and its audience (only / except /
       * close friends lists) includes me. Anything else falls back to the owner's profile with a toast.
       */
      private void openOriginalStory(final String origOwnerUid, final String origStatusId, final String origOwnerName) {
          if (origOwnerUid == null || origOwnerUid.isEmpty()) return;
          if (origStatusId == null || origStatusId.isEmpty()) { // legacy reshare: id was never stored
              Toast.makeText(this, R.string.viewer_toast_original_unavailable, Toast.LENGTH_SHORT).show();
              openOriginalCreatorProfile(origOwnerUid, origOwnerName);
              return;
          }
          pauseProgress();
          final View chip = reshareChipView;
          if (chip != null) { chip.setEnabled(false); chip.setAlpha(0.55f); } // "working" feedback, blocks double taps
          final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean(false);
          final Runnable restore = () -> { if (chip != null) { chip.setEnabled(true); chip.setAlpha(1f); } };
          final Runnable fallback = () -> {
              if (!done.compareAndSet(false, true)) return;
              restore.run();
              Toast.makeText(this, R.string.viewer_toast_original_gone, Toast.LENGTH_SHORT).show();
              openOriginalCreatorProfile(origOwnerUid, origOwnerName); // onResume() resumes if it opened
              handler.postDelayed(() -> { if (!isFinishing() && paused && hasWindowFocus()) resumeProgress(); }, 700L);
          };
          // 1) instant path: contacts' live statuses are already in the app-wide cache; 2) else read in PARALLEL with the block check
          StatusItem cached = null;
          try {
              for (StatusItem c : com.callx.app.cache.StatusCacheManager.getInstance(this).getStatuses(origOwnerUid))
                  if (origStatusId.equals(c.id)) { cached = c; break; }
          } catch (Exception ignored) {}
          final StatusItem[] orig = { cached };
          final boolean[] statusLoaded = { cached != null }, blockLoaded = { false }, blocked = { false }, failed = { false };
          final Runnable decide = () -> {
              if (done.get() || !statusLoaded[0] || !blockLoaded[0]) return;
              if (failed[0] || blocked[0] || orig[0] == null || isDead(orig[0], System.currentTimeMillis())
                      || !canIViewOriginal(orig[0])) { fallback.run(); return; }
              if (!done.compareAndSet(false, true)) return;
              restore.run();
              Intent i = new Intent(this, StatusViewerActivity.class);
              i.putExtra(EXTRA_OWNER_UID, origOwnerUid);
              i.putExtra(EXTRA_OWNER_NAME, orig[0].ownerName != null ? orig[0].ownerName : (origOwnerName != null ? origOwnerName : ""));
              i.putExtra(EXTRA_TARGET_STATUS_ID, origStatusId);
              i.putExtra(EXTRA_SINGLE_STATUS_ONLY, true); // Back goes straight to the reshare, no queue / extra stories
              startActivity(i); // we stay paused underneath; onResume() resumes when the user comes back
          };
          FirebaseUtils.getBlocksRef(myUid).child(origOwnerUid).get()
              .addOnSuccessListener(b -> { blocked[0] = b.exists(); blockLoaded[0] = true; decide.run(); })
              .addOnFailureListener(x -> { blockLoaded[0] = true; decide.run(); }); // can't tell → don't block the user
          if (cached == null) {
              FirebaseUtils.getStatusRef().child(origOwnerUid).child(origStatusId).get()
                  .addOnSuccessListener(snap -> {
                      try { orig[0] = snap.getValue(StatusItem.class); } catch (Exception ignored) {}
                      statusLoaded[0] = true; decide.run();
                  })
                  .addOnFailureListener(x -> { failed[0] = true; statusLoaded[0] = true; decide.run(); });
          } else decide.run();
      }
      /** Audience check from the story's own privacy fields (contacts-only is enforced server-side/by the feed). */
      private boolean canIViewOriginal(StatusItem o) {
          if (myUid == null) return false;
          final List<String> list = o.privacyList;
          if (StatusPrivacyManager.PRIVACY_ONLY.equals(o.privacy)) return list != null && list.contains(myUid);
          if (StatusPrivacyManager.PRIVACY_EXCEPT.equals(o.privacy)) return list == null || !list.contains(myUid);
          if (o.isCloseFriends) return list != null && list.contains(myUid);
          return true;
      }
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
              android.widget.Toast.makeText(this, R.string.viewer_toast_profile_open_failed,
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
      private void prefetchNextVideo(String url) { prefetchNextVideo(url, true); }
      private void prefetchNextVideo(String url, boolean standby) {
          if (url == null || url.isEmpty()) return;
          try {
              if (videoPreloader == null) videoPreloader = new com.callx.app.cache.StatusMediaPreloader(this);
              videoPreloader.preloadVideoUrl(url);
          } catch (Exception ignored) {}
          if (standby) prepareStandby(url); // standby ExoPlayer pre-buffers it too → video→video swap instead of a cold start
      }

      /** Warms Glide's cache with the NEXT status's photo so swiping/auto-advance is instant. */
      private void prefetchNextImage() {
          if (isFinishing() || isDestroyed()) return;
          if (idx + 1 < items.size()) {
              prefetchItem(items.get(idx + 1));
              // Photos are short (~5 s): also warm the one after that when it is a photo (cheap, small win).
              if (idx + 2 < items.size() && "image".equals(items.get(idx + 2).type)) prefetchItem(items.get(idx + 2));
              return;
          }
          prefetchNextQueueEntry(); // last segment of this person → warm the NEXT person's first one
      }
      /** Instagram-style: while the last story of the current person plays, the next person's
       *  first story (media + blurred backdrop) is already being fetched, so the cube turn lands
       *  on a ready frame instead of an empty card. Reads the app-wide cache — no extra Firebase read. */
      private void prefetchNextQueueEntry() {
          if (isHighlightMode) return;
          warmQueueEntry(queuePos + 1);
      }
      /** v583: warm BOTH the next and the previous owner's first status as soon as an owner opens —
       *  a swipe can happen at any moment, not only on the last segment. Once per queue position. */
      private void prefetchAdjacentQueueEntries() {
          if (isHighlightMode || isFinishing() || isDestroyed() || adjacentWarmedPos == queuePos) return;
          adjacentWarmedPos = queuePos;
          warmQueueEntry(queuePos + 1);
          warmQueueEntry(queuePos - 1);
      }
      private void warmQueueEntry(int pos) {
          if (pos < 0 || pos >= queueOwnerUids.size()) return;
          try {
              List<StatusItem> l = com.callx.app.cache.StatusCacheManager.getInstance(this)
                      .getStatuses(queueOwnerUids.get(pos));
              if (!l.isEmpty()) prefetchItem(l.get(0), true);
          } catch (Exception ignored) {}
      }
      private void prefetchItem(StatusItem n) { prefetchItem(n, false); }
      /** adjacent=true (other owner's first status): HIGH priority, and never touches the standby
       *  ExoPlayer (it is reserved for THIS owner's next segment). */
      private void prefetchItem(StatusItem n, final boolean adjacent) {
          if (n == null || n.type == null) return;
          String url = null;
          switch (n.type) {
              case "image":
                  url = n.mediaUrl; break;
              case "video": case "reel_story": case "reel_clip":
                  if (n.mediaUrl == null || n.mediaUrl.isEmpty()) url = n.thumbnailUrl;
                  else { prefetchNextVideo(n.mediaUrl, !adjacent); preloadVideoPoster(n); } // plays via ExoPlayer — warm its cache + poster
                  break;
              case "link":
                  url = n.linkImageUrl; break;
              case "reel_reshare": case "post_reshare": case "channel_post_reshare": case "status_reshare": {
                  boolean video = n.resharedMediaType != null && !n.resharedMediaType.isEmpty()
                          ? "video".equals(n.resharedMediaType) : looksLikeVideoUrl(n.mediaUrl);
                  if (n.mediaUrl != null && !n.mediaUrl.isEmpty() && !video) url = n.mediaUrl;
                  else if (n.mediaUrl != null && !n.mediaUrl.isEmpty()) { prefetchNextVideo(n.mediaUrl, !adjacent); preloadVideoPoster(n); }
                  break;
              }
              default: break;
          }
          if (url == null || url.isEmpty()) return;
          try {
              Glide.with(this).asBitmap().load(url)
                      .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                      .priority(adjacent ? com.bumptech.glide.Priority.HIGH : com.bumptech.glide.Priority.NORMAL)
                      .apply(imgBgOptions()).preload(); // same key as loadRootBackdrop()
              Glide.with(this).load(url)
                      .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                      .priority(adjacent ? com.bumptech.glide.Priority.HIGH : com.bumptech.glide.Priority.NORMAL)
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
                       if (idx == reqIdx) { releaseImageHold(); onReshareMediaSize(resource.getIntrinsicWidth(), resource.getIntrinsicHeight()); }
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
      private ExoPlayer buildStatusPlayer() {
          // Small start-up buffers so a 5–30 s story begins after ~0.5 s of data instead of ExoPlayer's 2.5 s default.
          return new ExoPlayer.Builder(this)
                  .setLoadControl(new androidx.media3.exoplayer.DefaultLoadControl.Builder()
                          .setBufferDurationsMs(2_500, 15_000, 500, 1_000)
                          .build())
                  .build();
      }
      @OptIn(markerClass = UnstableApi.class)
      private void setStatusMedia(ExoPlayer p, String url) {
          if (StatusVideoCacheManager.isInitialized()) {
              CacheDataSource.Factory cf = StatusVideoCacheManager.getCacheDataSourceFactory();
              ProgressiveMediaSource ms = new ProgressiveMediaSource.Factory(cf)
                      .createMediaSource(MediaItem.fromUri(Uri.parse(url)));
              p.setMediaSource(ms);
          } else {
              p.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
          }
      }
      /** Pre-prepares the NEXT video on the standby player (paused). Main thread. */
      private void prepareStandby(String url) {
          if (!STANDBY_PLAYER_ENABLED || url == null || url.isEmpty()) return;
          if (isFinishing() || isDestroyed() || url.equals(standbyUrl)) return;
          try {
              if (standbyPlayer == null) standbyPlayer = buildStatusPlayer();
              standbyPlayer.stop();
              standbyPlayer.clearMediaItems();
              standbyPlayer.setPlayWhenReady(false);
              setStatusMedia(standbyPlayer, url);
              standbyPlayer.prepare();
              standbyUrl = url;
          } catch (Exception e) {
              releaseStandby();
          }
      }
      /** The standby player if it holds exactly `url` and is already buffered/ready, else null. */
      private ExoPlayer takeReadyStandby(String url) {
          if (!STANDBY_PLAYER_ENABLED || standbyPlayer == null || url == null || !url.equals(standbyUrl)) return null;
          try {
              if (standbyPlayer.getPlaybackState() == Player.STATE_READY && standbyPlayer.getPlayerError() == null)
                  return standbyPlayer;
          } catch (Exception ignored) {}
          return null;
      }
      private void releaseStandby() {
          if (standbyPlayer != null) { try { standbyPlayer.release(); } catch (Exception ignored) {} }
          standbyPlayer = null; standbyUrl = null;
      }

      // ── Video poster (no black frame before the first video frame) ───────
      private com.bumptech.glide.request.RequestOptions posterOptions() {
          final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
          return new com.bumptech.glide.request.RequestOptions()
                  .override(Math.max(1, dm.widthPixels / 2), Math.max(1, dm.heightPixels / 2))
                  .transform(new com.bumptech.glide.load.resource.bitmap.FitCenter(),
                          new com.bumptech.glide.load.resource.bitmap.RoundedCorners(Math.max(1, mediaCornerPx() / 2)));
      }
      private void showVideoPoster(StatusItem s) {
          final String thumb = s.thumbnailUrl;
          if (thumb == null || thumb.isEmpty() || binding.ivVideoPoster == null) return;
          final int seq = showSeq;
          binding.ivVideoPoster.setVisibility(View.VISIBLE);
          Glide.with(this).load(thumb)
                  .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                  .apply(posterOptions())
                  .dontAnimate()
                  .listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>() {
                      @Override public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e, Object model,
                              com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target, boolean isFirstResource) {
                          return false;
                      }
                      @Override public boolean onResourceReady(android.graphics.drawable.Drawable resource, Object model,
                              com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                              com.bumptech.glide.load.DataSource dataSource, boolean isFirstResource) {
                          // The video's first frame (or a newer status) beat the poster → don't paint it over live video.
                          if (videoFrameRendered || seq != showSeq) {
                              binding.ivVideoPoster.setVisibility(View.GONE);
                              return true;
                          }
                          return false;
                      }
                  })
                  .into(binding.ivVideoPoster);
      }
      private void hideVideoPoster() {
          if (binding == null || binding.ivVideoPoster == null) return;
          if (binding.ivVideoPoster.getVisibility() == View.VISIBLE) {
              binding.ivVideoPoster.setVisibility(View.GONE);
              if (!isDestroyed()) Glide.with(this).clear(binding.ivVideoPoster); // free the bitmap
          }
      }
      /** Warms Glide with the next video's poster so it paints instantly when that video opens. */
      private void preloadVideoPoster(StatusItem n) {
          if (n == null || n.thumbnailUrl == null || n.thumbnailUrl.isEmpty()) return;
          try {
              Glide.with(this).load(n.thumbnailUrl)
                      .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                      .apply(posterOptions()).preload();
          } catch (Exception ignored) {}
      }

      @OptIn(markerClass = UnstableApi.class)
      private void showVideoStatus(StatusItem s) {
          binding.playerView.setVisibility(View.VISIBLE);
          binding.btnMute.setVisibility(View.VISIBLE);
          releasePlayer(); // stops + resets the shared player and detaches the previous video's listener
          videoFrameRendered = false;
          showVideoPoster(s);
          // Video → video: if the standby player already holds THIS video prepared, swap to it (the
          // old player goes idle and becomes the next standby). Otherwise the normal single-player path.
          boolean swapped = false;
          ExoPlayer ready = takeReadyStandby(s.mediaUrl);
          if (ready != null) {
              ExoPlayer old = player;
              player = ready;
              standbyPlayer = old; // already stopped + cleared by releasePlayer() (null if there was none)
              standbyUrl = null;
              swapped = true;
          }
          if (player == null) player = buildStatusPlayer(); // built once: no per-status player/decoder/thread setup
          binding.playerView.setPlayer(player); // no-op when it's already attached
          if (!swapped) setStatusMedia(player, s.mediaUrl);
          applyVideoVolume();
          long estimated = s.durationSec > 0 ? Math.min(s.durationSec * 1000L, 30_000L) : 15_000L;
          final String statusIdForLog = s.id;
          final Runnable onReady = () -> {
              if (player == null) return;
              long real = player.getDuration();
              long dur = (real > 0 && real != Long.MIN_VALUE) ? Math.min(real, 30_000L) : estimated;
              stopProgress(); startProgress(dur);
          };
          playerListener = new Player.Listener() {
              @Override public void onPlaybackStateChanged(int state) {
                  if (state == Player.STATE_READY) onReady.run();
                  else if (state == Player.STATE_ENDED) { next(); }
              }
              @Override public void onRenderedFirstFrame() {
                  videoFrameRendered = true;
                  hideVideoPoster();
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
          if (swapped) {
              player.setPlayWhenReady(true);
              // Already buffered: STATE_READY won't be re-delivered to the listener we just added.
              if (player.getPlaybackState() == Player.STATE_READY) onReady.run(); else startProgress(estimated);
          } else {
              player.prepare(); player.setPlayWhenReady(true);
              startProgress(estimated);
          }
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
      // ── Instagram-style reposted story: the original story sits in a rounded 9:16 card (≈80% width)
      //    over the blurred backdrop, with the ORIGINAL owner's avatar + name on the card's top-left.
      private boolean reshareCardActive;
      private View    reshareHeaderView, reshareHeaderScrim;
      private int reshareBoxW, reshareBoxH;         // card box size → stickers / text overlays map into THIS box
      private StatusItem reshareCardItem;
      private boolean reshareLayoutListenerSet;
      private FrameLayout reshareCardFrame;              // rounded, clipping container that HOLDS the media views while the card is shown
      private int reshareOrigIndex = -1;
      private float reshareSavedScale = 1f;
      private float reshareMediaRatio;                   // learned from the decoded media when the post has no mediaWidth/Height
      private void applyReshareCardMode(StatusItem s) { applyReshareCardMode(s, true); }
      private void applyReshareCardMode(StatusItem s, boolean animate) {
          if (!"status_reshare".equals(s.type)) return;
          final boolean hasMedia = (s.mediaUrl != null && !s.mediaUrl.isEmpty())
                  || (s.thumbnailUrl != null && !s.thumbnailUrl.isEmpty())
                  || (s.resharedThumbnailUrl != null && !s.resharedThumbnailUrl.isEmpty());
          if (!hasMedia) return;
          final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
          final android.widget.FrameLayout card = binding.flStoryCard;
          final int w = card.getWidth() > 0 ? card.getWidth() : dm.widthPixels;
          final int h = card.getHeight() > 0 ? card.getHeight() : dm.heightPixels;
          final int topInset = dpToPx(96), bottomInset = dpToPx(24);
          final int availH = Math.max(1, h - topInset - bottomInset);
          // Card follows the media's own aspect ratio (clamped 1:2 … 1:1), 9:16 when unknown → nothing gets cropped.
          float ratio = (s.mediaWidth > 0 && s.mediaHeight > 0) ? (float) s.mediaWidth / s.mediaHeight
                  : reshareMediaRatio > 0f ? reshareMediaRatio : 9f / 16f;
          ratio = Math.max(0.5f, Math.min(1f, ratio));
          int bw0 = Math.round(w * 0.8f), bh = Math.round(bw0 / ratio);
          if (bh > availH) bh = availH;
          final int bw = Math.min(bw0, Math.round(bh * ratio));
          final int top = topInset + (availH - bh) / 2;
          final int left = (w - bw) / 2;
          final int radius = mediaCornerPx(); // same corner as every other media (no double rounding)

          final android.view.ViewOutlineProvider round = new android.view.ViewOutlineProvider() {
              @Override public void getOutline(View v, android.graphics.Outline o) {
                  o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
              }
          };
          // The media views (photo / player / poster) move INTO one rounded, clipping frame. Whatever they draw
          // (stabilizer overscan, zoomed video, poster) can never spill outside the card.
          if (reshareCardFrame == null) {
              reshareCardFrame = new FrameLayout(this);
              reshareCardFrame.setOutlineProvider(round);
              reshareCardFrame.setClipToOutline(true);
              reshareCardFrame.setClipChildren(true);
          }
          final View[] media = { binding.ivStatus, binding.playerView, binding.ivVideoPoster };
          if (reshareCardFrame.getParent() == null) {
              reshareOrigIndex = Math.max(0, card.indexOfChild(binding.ivStatus));
              reshareSavedScale = binding.ivStatus.getScaleX();
              for (View v : media) if (v.getParent() == card) card.removeView(v);
              for (View v : media) {
                  v.setScaleX(1f); v.setScaleY(1f); v.setRotation(0f); // card is always level + 1:1 (no 1.42x overscan)
                  reshareCardFrame.addView(v, new FrameLayout.LayoutParams(
                          android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
              }
              card.addView(reshareCardFrame, Math.min(reshareOrigIndex, card.getChildCount()));
          }
          final FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(bw, bh, android.view.Gravity.TOP | android.view.Gravity.START);
          flp.leftMargin = left; flp.topMargin = top;
          reshareCardFrame.setLayoutParams(flp);
          reshareCardFrame.invalidateOutline();
          binding.ivStatus.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
          binding.ivVideoPoster.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP); // video poster fills the card too
          reshareCardActive = true;
          binding.playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM); // video fills the card, no black bars
          reshareBoxW = bw; reshareBoxH = bh; reshareCardItem = s;

          // Stickers + text overlays live in the SAME box as the card (so they land where the poster placed them).
          if (stickerOverlayView != null || (s.stickersJson != null && !s.stickersJson.isEmpty())) {
              final FrameLayout so = stickerOverlay();
              final FrameLayout.LayoutParams olp = new FrameLayout.LayoutParams(bw, bh, Gravity.TOP | Gravity.START);
              olp.leftMargin = left; olp.topMargin = top;
              so.setLayoutParams(olp);
          }

          // Re-fit the card when the story area changes size (keyboard, rotation, split-screen).
          if (!reshareLayoutListenerSet) {
              reshareLayoutListenerSet = true;
              card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> {
                  if (!reshareCardActive || reshareCardItem == null) return;
                  if ((r - l) == (orr - ol) && (b - t) == (ob - ot)) return;
                  if (reshareHintShowing()) dismissReshareHint(true); // pill was placed for the old size
                  v.post(() -> { if (reshareCardActive && reshareCardItem != null) applyReshareCardMode(reshareCardItem, false); });
              });
          }

          // Soft top scrim so the header stays readable on bright photos.
          if (reshareHeaderScrim == null) {
              reshareHeaderScrim = new View(this);
              reshareHeaderScrim.setClickable(false);
              reshareHeaderScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
              card.addView(reshareHeaderScrim);
          }
          final android.graphics.drawable.GradientDrawable sg = new android.graphics.drawable.GradientDrawable(
                  android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[] { 0x4D000000, 0x00000000 });
          sg.setCornerRadii(new float[] { radius, radius, radius, radius, 0, 0, 0, 0 });
          reshareHeaderScrim.setBackground(sg);
          final android.widget.FrameLayout.LayoutParams slp =
                  new android.widget.FrameLayout.LayoutParams(bw, dpToPx(48), android.view.Gravity.TOP | android.view.Gravity.START);
          slp.leftMargin = left; slp.topMargin = top;
          reshareHeaderScrim.setLayoutParams(slp);
          reshareHeaderScrim.setVisibility(View.VISIBLE);

          // Header: original owner avatar + name, top-left inside the card.
          if (reshareHeaderView == null) {
              final android.widget.LinearLayout row = new android.widget.LinearLayout(this);
              row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
              row.setGravity(android.view.Gravity.CENTER_VERTICAL);
              final de.hdodenhof.circleimageview.CircleImageView av = new de.hdodenhof.circleimageview.CircleImageView(this);
              av.setId(R.id.iv_reshare_chip_avatar);
              av.setImageResource(R.drawable.ic_person);
              row.addView(av, new android.widget.LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)));
              final TextView tv = new TextView(this);
              tv.setTag("reshare_header_name");
              tv.setTextColor(Color.WHITE);
              tv.setTextSize(14f);
              tv.setTypeface(null, android.graphics.Typeface.BOLD);
              tv.setMaxLines(1);
              tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
              tv.setShadowLayer(4f, 0f, 1f, 0xB3000000);
              final android.widget.LinearLayout.LayoutParams tlp =
                      new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
              tlp.setMarginStart(dpToPx(8));
              row.addView(tv, tlp);
              reshareHeaderView = row;
              card.addView(row);
          }
          final android.widget.LinearLayout row = (android.widget.LinearLayout) reshareHeaderView;
          final android.widget.FrameLayout.LayoutParams hlp =
                  new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                          android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP | android.view.Gravity.START);
          hlp.leftMargin = left + dpToPx(12); hlp.topMargin = top + dpToPx(12);
          hlp.rightMargin = w - left - bw + dpToPx(12);
          row.setLayoutParams(hlp);
          final String cName = s.resharedFromOwnerName != null ? s.resharedFromOwnerName : "";
          final String cUser = s.resharedFromOwnerUsername;
          final String label = (cUser != null && !cUser.isEmpty()) ? cUser : cName;
          final TextView tv = (TextView) row.findViewWithTag("reshare_header_name");
          if (tv != null) tv.setText(label);
          final android.widget.ImageView av = (android.widget.ImageView) row.findViewById(R.id.iv_reshare_chip_avatar);
          if (av != null) bindReshareAvatar(av, s.resharedFromOwnerAvatar, cName, s.resharedFromOwnerUid);
          final String cUid = s.resharedFromOwnerUid != null ? s.resharedFromOwnerUid : "";
          final String cId  = s.resharedFromId != null ? s.resharedFromId : "";
          row.setClickable("status".equals(s.resharedFromType));
          row.setOnClickListener("status".equals(s.resharedFromType) ? v -> openOriginalStory(cUid, cId, label) : null);
          row.setContentDescription(getString(R.string.viewer_cd_view_original_by, cName));
          row.setVisibility(cName.isEmpty() ? View.GONE : View.VISIBLE);
          row.bringToFront();
          // entry: fade-in (+ 6dp drop) for header and scrim together
          final float endA = chromeHidden ? 0f : 1f;
          for (View v : new View[] { reshareHeaderScrim, row }) {
              v.animate().cancel();
              if (animate) {
                  v.setAlpha(0f);
                  v.setTranslationY(v == row ? -dpToPx(6) : 0f);
                  v.animate().alpha(endA).translationY(0f).setDuration(220L).setStartDelay(120L).start();
              } else { v.setAlpha(endA); v.setTranslationY(0f); }
          }
      }
      /** Old reposts carry no mediaWidth/Height: learn the real ratio from the decoded photo, then re-fit the card.
       *  Skipped when the status has stickers (they were already placed in the first box). */
      /** Original owner's avatar for the repost header + the long-press pill — through the core avatar
       *  optimizer (same TINY tier / URL / cache key as the live comments and the replies sheet),
       *  initials drawable when there is no photo. */
      private void bindReshareAvatar(android.widget.ImageView av, String url, String name, String uid) {
          final android.graphics.drawable.Drawable initials = new com.callx.app.utils.InitialsAvatarDrawable(name, uid);
          if (url == null || url.isEmpty()) {
              Glide.with(this).clear(av);
              av.setImageDrawable(initials);
              return;
          }
          final int px = com.callx.app.utils.AvatarUrlBuilder.tierPx(this, com.callx.app.utils.AvatarSizeTier.TINY);
          Glide.with(this)
                  .load(com.callx.app.utils.AvatarUrlBuilder.buildResponsive(
                          this, url, com.callx.app.utils.AvatarSizeTier.TINY))
                  .apply(new com.bumptech.glide.request.RequestOptions()
                          .override(px, px)
                          .placeholder(initials)
                          .error(initials)
                          .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                          .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE))
                  .dontAnimate()
                  .into(av);
      }
      private void onReshareMediaSize(int mw, int mh) {
          final StatusItem it = reshareCardItem;
          if (!reshareCardActive || it == null || mw <= 0 || mh <= 0) return;
          if (it.mediaWidth > 0 && it.mediaHeight > 0) return;
          if (it.stickersJson != null && !it.stickersJson.isEmpty()) return;
          final float r = Math.max(0.5f, Math.min(1f, (float) mw / mh));
          if (Math.abs(r - reshareMediaRatio) < 0.01f) return;
          reshareMediaRatio = r;
          applyReshareCardMode(it, false);
      }
      // ── Long-press on the reposted card → "original owner" pill above the finger (Instagram). Tap it → original story.
      private View reshareHintView;
      private boolean reshareHintPaused;
      private final Runnable reshareHintAutoHide = () -> dismissReshareHint(true);
      private androidx.activity.OnBackPressedCallback reshareHintBackCb;
      private androidx.activity.OnBackPressedCallback reshareHintBack() {
          if (reshareHintBackCb == null) {
              reshareHintBackCb = new androidx.activity.OnBackPressedCallback(false) {
                  @Override public void handleOnBackPressed() { dismissReshareHint(true); } // Back closes just the pill
              };
              getOnBackPressedDispatcher().addCallback(this, reshareHintBackCb);
          }
          return reshareHintBackCb;
      }
      private boolean reshareHintShowing() { return reshareHintView != null && reshareHintView.getVisibility() == View.VISIBLE; }
      private void maybeShowReshareHint() {
          final StatusItem it = reshareCardItem;
          if (!reshareCardActive || it == null || reshareCardFrame == null || reshareCardFrame.getParent() == null) return;
          // finger must be on the card itself
          if (touchDownX < reshareCardFrame.getLeft() || touchDownX > reshareCardFrame.getRight()
                  || touchDownY < reshareCardFrame.getTop() || touchDownY > reshareCardFrame.getBottom()) return;
          if (it.resharedFromOwnerName == null || it.resharedFromOwnerName.isEmpty()) return;
          final FrameLayout card = binding.flStoryCard;
          dismissReshareHint(false);
          final String cName = it.resharedFromOwnerName;
          final String cUser = it.resharedFromOwnerUsername;
          final String label = (cUser != null && !cUser.isEmpty()) ? cUser : cName;
          final String cUid = it.resharedFromOwnerUid != null ? it.resharedFromOwnerUid : "";
          final String cId  = it.resharedFromId != null ? it.resharedFromId : "";
          final boolean isStory = "status".equals(it.resharedFromType);

          final android.widget.LinearLayout root = new android.widget.LinearLayout(this);
          root.setOrientation(android.widget.LinearLayout.VERTICAL);
          root.setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4)); // transparent pad → tap area ≈ 48dp (pill itself stays 40dp)
          root.setClipChildren(false); root.setClipToPadding(false);
          final android.widget.LinearLayout pill = new android.widget.LinearLayout(this);
          pill.setOrientation(android.widget.LinearLayout.HORIZONTAL);
          pill.setGravity(Gravity.CENTER_VERTICAL);
          pill.setPadding(dpToPx(8), dpToPx(6), dpToPx(10), dpToPx(6));
          final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
          // Near the card's top edge there is no room above the finger → pill goes BELOW it, tail points up.
          final boolean below = touchDownY < dpToPx(82);
          final int hintColor = reshareHintColor(touchDownX, below ? touchDownY + dpToPx(40) : touchDownY - dpToPx(40));
          bg.setColor(hintColor); bg.setCornerRadius(dpToPx(14));
          bg.setStroke(dpToPx(1), 0x26FFFFFF); // hairline edge so the pill never melts into a same-coloured backdrop
          pill.setBackground(bg);
          pill.setElevation(dpToPx(3));
          final de.hdodenhof.circleimageview.CircleImageView av = new de.hdodenhof.circleimageview.CircleImageView(this);
          pill.addView(av, new android.widget.LinearLayout.LayoutParams(dpToPx(28), dpToPx(28)));
          // Reuse the header's already-loaded avatar (own copy of the drawable → painted on the very first frame,
          // no initials flash). Only if the header has no photo yet do we go through the avatar optimizer.
          final android.widget.ImageView headerAv = reshareHeaderView != null
                  ? (android.widget.ImageView) reshareHeaderView.findViewById(R.id.iv_reshare_chip_avatar) : null;
          final android.graphics.drawable.Drawable hd = headerAv != null ? headerAv.getDrawable() : null;
          if (hd != null && !(hd instanceof com.callx.app.utils.InitialsAvatarDrawable) && hd.getConstantState() != null) {
              av.setImageDrawable(hd.getConstantState().newDrawable(getResources()).mutate());
          } else {
              bindReshareAvatar(av, it.resharedFromOwnerAvatar, cName, it.resharedFromOwnerUid);
          }
          final TextView tv = new TextView(this);
          tv.setText(label); tv.setTextColor(Color.WHITE); tv.setTextSize(13f);
          tv.setTypeface(null, android.graphics.Typeface.BOLD);
          tv.setMaxLines(1); tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
          tv.setMaxWidth(dpToPx(200));
          final android.widget.LinearLayout.LayoutParams tlp = new android.widget.LinearLayout.LayoutParams(
                  android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
          tlp.setMarginStart(dpToPx(8));
          pill.addView(tv, tlp);
          final TextView chev = new TextView(this);
          chev.setText("\u203A"); chev.setTextColor(Color.WHITE); chev.setTextSize(20f);
          final android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                  android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
          clp.setMarginStart(dpToPx(8));
          pill.addView(chev, clp);
          final android.widget.LinearLayout.LayoutParams pillLp = new android.widget.LinearLayout.LayoutParams(
                  android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
          if (!below) root.addView(pill, pillLp);
          // tail pointing down at the finger
          final View tail = new View(this);
          final android.graphics.drawable.GradientDrawable tbg = new android.graphics.drawable.GradientDrawable();
          tbg.setColor(hintColor); tbg.setCornerRadius(dpToPx(2));
          tail.setBackground(tbg);
          tail.setRotation(45f);
          final android.widget.LinearLayout.LayoutParams tail_lp = new android.widget.LinearLayout.LayoutParams(dpToPx(10), dpToPx(10));
          if (below) { tail_lp.bottomMargin = -dpToPx(6); root.addView(tail, tail_lp); root.addView(pill, pillLp); }
          else { tail_lp.topMargin = -dpToPx(6); root.addView(tail, tail_lp); }

          root.measure(View.MeasureSpec.makeMeasureSpec(card.getWidth(), View.MeasureSpec.AT_MOST),
                  View.MeasureSpec.makeMeasureSpec(card.getHeight(), View.MeasureSpec.AT_MOST));
          final int rw = root.getMeasuredWidth(), rh = root.getMeasuredHeight(), edge = dpToPx(8);
          final int rootLeft = Math.max(edge, Math.min(Math.round(touchDownX - rw / 2f), card.getWidth() - rw - edge));
          final int rootTop = below ? Math.round(touchDownY) + dpToPx(20)
                  : Math.max(edge, Math.round(touchDownY) - rh - dpToPx(20));
          tail_lp.leftMargin = Math.max(dpToPx(12), Math.min(Math.round(touchDownX) - rootLeft - dpToPx(5) - dpToPx(4), rw - dpToPx(26)));
          root.requestLayout();
          final FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                  android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                  Gravity.TOP | Gravity.START);
          rlp.leftMargin = rootLeft; rlp.topMargin = rootTop;
          root.setLayoutParams(rlp);
          root.setOnClickListener(v -> {
              dismissReshareHint(false);
              if (isStory) openOriginalStory(cUid, cId, label); else openOriginalCreatorProfile(cUid, cName);
          });
          root.setContentDescription(getString(R.string.viewer_cd_view_original_by, cName));
          root.setAlpha(0f); root.setScaleX(0.9f); root.setScaleY(0.9f);
          root.setPivotX(Math.max(0, Math.min(rw, Math.round(touchDownX) - rootLeft))); root.setPivotY(below ? 0f : rh);
          card.addView(root);
          root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220L)
                  .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
          root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
          reshareHintBack().setEnabled(true);
          reshareHintView = root;
          reshareHintPaused = true; // the hold already paused the story; stay paused while the pill is open
          handler.removeCallbacks(reshareHintAutoHide);
          handler.postDelayed(reshareHintAutoHide, 5_000L);
      }
      /** Pill colour = the status's own backdrop colour at the pill's spot, 20% darker (Instagram look), then pushed
       *  dark enough that the white name always reads. Image: the blurred backdrop at that spot. Video: the poster's
       *  average. Nothing available (text): neutral dark grey. */
      private int reshareHintColor(float cardX, float cardY) {
          int r = 0x3A, g = 0x3A, b = 0x3A;
          try {
              int[] rgb = sampleBackdropAt(cardX, cardY);
              if (rgb == null) rgb = sampleDrawableAverage(binding.ivVideoPoster);
              if (rgb != null) { r = rgb[0]; g = rgb[1]; b = rgb[2]; }
          } catch (Exception ignored) { /* keep grey */ }
          float k = 0.8f; // 20% darker
          float rr = r * k, gg = g * k, bb = b * k;
          final float lum = (0.299f * rr + 0.587f * gg + 0.114f * bb) / 255f;
          if (lum > 0.38f) { final float f = 0.38f / lum; rr *= f; gg *= f; bb *= f; } // light photo → keep white text readable
          return 0xFF000000 | (Math.round(rr) << 16) | (Math.round(gg) << 8) | Math.round(bb);
      }
      private static android.graphics.Bitmap bitmapOf(android.graphics.drawable.Drawable d) {
          if (d instanceof android.graphics.drawable.TransitionDrawable) {
              final android.graphics.drawable.TransitionDrawable td = (android.graphics.drawable.TransitionDrawable) d;
              d = td.getDrawable(td.getNumberOfLayers() - 1); // final frame, not the cross-fade start
          }
          if (!(d instanceof android.graphics.drawable.BitmapDrawable)) return null;
          android.graphics.Bitmap bmp = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
          if (bmp == null || bmp.isRecycled()) return null;
          if (android.os.Build.VERSION.SDK_INT >= 26 && bmp.getConfig() == android.graphics.Bitmap.Config.HARDWARE)
              bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
          return bmp;
      }
      /** Blurred whole-window backdrop (iv_root_bg, centerCrop) colour around a point given in story-card coords. */
      private int[] sampleBackdropAt(float cardX, float cardY) {
          final ImageView rb = binding.ivRootBg;
          if (rb == null || rb.getVisibility() != View.VISIBLE || rb.getWidth() == 0 || rb.getHeight() == 0) return null;
          final android.graphics.Bitmap bmp = bitmapOf(rb.getDrawable());
          if (bmp == null) return null;
          final int bw = bmp.getWidth(), bh = bmp.getHeight(), vw = rb.getWidth(), vh = rb.getHeight();
          final float sc = Math.max((float) vw / bw, (float) vh / bh);
          final float ox = (bw * sc - vw) / 2f, oy = (bh * sc - vh) / 2f;
          final int[] cl = new int[2], rl = new int[2];
          binding.flStoryCard.getLocationInWindow(cl);
          rb.getLocationInWindow(rl);
          final int cx = Math.max(0, Math.min(bw - 1, Math.round((cl[0] + cardX - rl[0] + ox) / sc)));
          final int cy = Math.max(0, Math.min(bh - 1, Math.round((cl[1] + cardY - rl[1] + oy) / sc)));
          long r = 0, g = 0, b = 0; int n = 0;
          for (int yy = Math.max(0, cy - 2); yy <= Math.min(bh - 1, cy + 2); yy++)
              for (int xx = Math.max(0, cx - 2); xx <= Math.min(bw - 1, cx + 2); xx++) {
                  final int px = bmp.getPixel(xx, yy);
                  r += (px >> 16) & 0xff; g += (px >> 8) & 0xff; b += px & 0xff; n++;
              }
          return n == 0 ? null : new int[] { Math.round(r / (float) n), Math.round(g / (float) n), Math.round(b / (float) n) };
      }
      /** Average colour of a whole image view's bitmap (8x8 grid) — used for the video poster. */
      private int[] sampleDrawableAverage(ImageView iv) {
          if (iv == null || iv.getVisibility() != View.VISIBLE) return null;
          final android.graphics.Bitmap bmp = bitmapOf(iv.getDrawable());
          if (bmp == null) return null;
          long r = 0, g = 0, b = 0; int n = 0;
          for (int yi = 0; yi < 8; yi++)
              for (int xi = 0; xi < 8; xi++) {
                  final int px = bmp.getPixel(Math.min(bmp.getWidth() - 1, (xi * 2 + 1) * bmp.getWidth() / 16),
                          Math.min(bmp.getHeight() - 1, (yi * 2 + 1) * bmp.getHeight() / 16));
                  r += (px >> 16) & 0xff; g += (px >> 8) & 0xff; b += px & 0xff; n++;
              }
          return new int[] { Math.round(r / (float) n), Math.round(g / (float) n), Math.round(b / (float) n) };
      }
      private void dismissReshareHint(boolean resume) {
          handler.removeCallbacks(reshareHintAutoHide);
          if (reshareHintBackCb != null) reshareHintBackCb.setEnabled(false);
          final View hv = reshareHintView;
          reshareHintView = null;
          if (hv != null) {
              hv.animate().cancel();
              hv.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(120L).withEndAction(() -> {
                  if (hv.getParent() instanceof android.view.ViewGroup) ((android.view.ViewGroup) hv.getParent()).removeView(hv);
              }).start();
              hv.setClickable(false);
          }
          if (reshareHintPaused) {
              reshareHintPaused = false;
              if (resume && !isFinishing() && !isDestroyed()) resumeProgress();
          }
      }
      private void resetReshareCardMode() {
          dismissReshareHint(false);
          if (reshareHeaderView != null) { reshareHeaderView.animate().cancel(); reshareHeaderView.setVisibility(View.GONE); }
          if (reshareHeaderScrim != null) { reshareHeaderScrim.animate().cancel(); reshareHeaderScrim.setVisibility(View.GONE); }
          if (!reshareCardActive) return;
          reshareCardActive = false; reshareCardItem = null; reshareBoxW = reshareBoxH = 0;
          if (stickerOverlayView != null)
              stickerOverlayView.setLayoutParams(new FrameLayout.LayoutParams(
                      android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
          reshareMediaRatio = 0f;
          if (reshareCardFrame != null && reshareCardFrame.getParent() == binding.flStoryCard) {
              final FrameLayout card = binding.flStoryCard;
              final View[] media = { binding.ivStatus, binding.playerView, binding.ivVideoPoster };
              reshareCardFrame.removeAllViews();
              card.removeView(reshareCardFrame);
              int at = Math.min(Math.max(0, reshareOrigIndex), card.getChildCount());
              for (View v : media) {
                  v.setScaleX(reshareSavedScale); v.setScaleY(reshareSavedScale);
                  card.addView(v, at++, new FrameLayout.LayoutParams(
                          android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
              }
          }
          binding.ivStatus.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
          binding.ivVideoPoster.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
          binding.playerView.setResizeMode(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
      }
      private void hideAllContent() {
          resetReshareCardMode();
          if (binding.ivRootBg != null) { binding.ivRootBg.setVisibility(View.GONE); binding.ivRootBg.setImageDrawable(null); }
          binding.flStoryCard.setBackgroundColor(Color.BLACK); // video / text: solid card again
          if (textLayerView != null) textLayerView.setVisibility(View.GONE);
          binding.ivStatus.setVisibility(View.GONE);
          binding.ivStatusBg.setVisibility(View.GONE);
          binding.playerView.setVisibility(View.GONE);
          hideVideoPoster(); // a previous video's poster must never linger over the next status
          binding.tvCaption.setVisibility(View.GONE);
          // FIX: was findViewWithTag("tv_location_tag") — always null — now binding ref
          binding.tvLocationTag.setVisibility(View.GONE);
          detachAddYoursCounts(); // v578: live "N added" listeners of the previous status
          if (stickerOverlayView != null) stickerOverlayView.removeAllViews();
          stickerZoomScrim = null; // just got removed along with everything else above
          videoUnderMusic = false; // new status → its own video starts at normal volume
          videoMixVol = -1f;       // …and without the previous status's Mix Audio volume
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
      // Mix Audio: poster's chosen original-video volume (0..1). -1 = no mix → old 0.25 ducking applies.
      private float videoMixVol = -1f;
      // (5) Full-length tracks (no preview URL) used to play from 0:00 for minutes.
      // StatusMusicClip plays a ~30s highlight (or the poster's trimmed range) instead.
      private final StatusMusicClip musicClip = new StatusMusicClip();
      { musicClip.setVolumeListener(() -> applyMusicVolume()); } // fade-in/out ramp
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
          float v;
          if (isMuted) v = 0f;
          else if (videoUnderMusic && videoMixVol >= 0f) v = videoMixVol;   // poster's Mix Audio choice
          else v = videoUnderMusic ? VIDEO_VOLUME_UNDER_MUSIC : 1f;
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
          // × Mix Audio factor (music volume + current fade-in/out position); 1 when no mix.
          float v = isMuted ? 0f : (musicDucked ? 0.2f : 1f) * musicClip.volumeFactor();
          try { musicPlayer.setVolume(v, v); } catch (Exception ignored) {}
      }

      /** Start/continue the music if it is prepared, allowed and we can get focus. */
      private void resumeMusicPlayback() {
          if (musicPlayer == null || !musicPrepared || musicFocusLost) return;
          try {
              if (musicPlayer.isPlaying()) return;
              if (!acquireMusicFocus()) return; // e.g. a call is active — stay silent
              musicClip.reapplyPitch(); // Mix Audio pitch (no-op when 0)
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
          videoMixVol = -1f;
          applyVideoVolume();
          if (isFinishing()) return;
          String code = (what != 0 || extra != 0) ? " (" + what + "/" + extra + ")" : "";
          android.widget.Toast.makeText(this, getString(R.string.viewer_toast_music_failed, code),
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
      /** Places one "overlaytext" entry over the photo's real (fitCenter) rectangle inside the story card. */
      private void renderTextOverlay(StatusItem item, JSONObject o) {
          final FrameLayout ov = stickerOverlay();
          float frameW = ov.getWidth() > 0 ? ov.getWidth()
                  : binding.flStoryCard.getWidth() > 0 ? binding.flStoryCard.getWidth()
                  : getResources().getDisplayMetrics().widthPixels;
          float frameH = ov.getHeight() > 0 ? ov.getHeight()
                  : binding.flStoryCard.getHeight() > 0 ? binding.flStoryCard.getHeight()
                  : getResources().getDisplayMetrics().heightPixels;
          if (reshareCardActive && reshareBoxW > 0) { frameW = reshareBoxW; frameH = reshareBoxH; }
          float fitW = frameW, fitH = frameH, left = 0f, top = 0f;
          if (!reshareCardActive && item != null && item.mediaWidth > 0 && item.mediaHeight > 0) {
              float f = Math.min(frameW / item.mediaWidth, frameH / item.mediaHeight);
              fitW = item.mediaWidth * f;
              fitH = item.mediaHeight * f;
              left = (frameW - fitW) / 2f;
              top = (frameH - fitH) / 2f;
          }
          com.callx.app.utils.StatusTextOverlayRenderer.place(ov, o, left, top, fitW, fitH);
      }

      private void renderStickers(StatusItem item) {
          if (item == null || item.stickersJson == null || item.stickersJson.isEmpty()) return;
          try {
              JSONArray arr = new JSONArray(item.stickersJson);
              final int stickerSeq = showSeq; // renderStickers() runs synchronously inside showCurrent()
              int dp = (int) getResources().getDisplayMetrics().density;
              for (int i = 0; i < arr.length(); i++) {
                  // Text overlay layer (same idea as Reels): drawn fresh and sharp, not part of the photo.
                  final JSONObject entry = arr.getJSONObject(i);
                  if (com.callx.app.utils.StatusTextOverlayRenderer.isOverlayText(entry)) {
                      renderTextOverlay(item, entry);
                      continue;
                  }
                  String stickerJson = entry.toString();
                  StatusStickerOverlayView sticker = StatusStickerOverlayView.fromJson(this, stickerJson);
                  if ("addyours".equals(sticker.getStickerType())) bindAddYoursCount(sticker);

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

                  if (reshareCardActive && reshareBoxW > 0) { frameW = reshareBoxW; frameH = reshareBoxH; } // reposted card box
                  final boolean centred = sticker.hasCenterPlacement() && frameW > 0 && frameH > 0;
                  final float density = getResources().getDisplayMetrics().density;
                  FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                          centred ? Math.round(sticker.getSavedWdp() * density) : Math.min(frameW - dp * 32, dp * 280),
                          FrameLayout.LayoutParams.WRAP_CONTENT,
                          sticker.hasSavedPosition() ? Gravity.TOP | Gravity.START : Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                  float centreX = 0f, centreY = 0f, fitK = 1f;
                  if (centred) {
                      // v650: the composer canvas (9:16) is shown fitCenter in this card (see StoryFrameSpec) → map the
                      // sticker into that SAME box (not the whole card) and scale it by box width / canvas width,
                      // so size + spot match the poster's preview on any screen shape.
                      final float aspect = sticker.getSavedFrameWdp() / sticker.getSavedFrameHdp();
                      float boxW = frameW, boxH = boxW / aspect;
                      if (boxH > frameH) { boxH = frameH; boxW = boxH * aspect; }
                      final float boxL = (frameW - boxW) / 2f, boxT = (frameH - boxH) / 2f;
                      centreX = boxL + sticker.getSavedCxRatio() * boxW;
                      centreY = boxT + sticker.getSavedCyRatio() * boxH;
                      fitK = (boxW / density) / sticker.getSavedFrameWdp();
                  }
                  if (centred) {
                      lp.leftMargin = 0;
                      lp.topMargin = 0; // exact spot is applied in a pre-draw pass once the card is measured
                  } else if (sticker.hasSavedPosition()) {
                      // Poster dragged/pinched this sticker to a specific spot in the
                      // composer — reproduce that exact spot here instead of the
                      // generic stacked layout below.
                      lp.leftMargin = (int) (sticker.getSavedPosXRatio() * frameW);
                      lp.topMargin  = (int) (sticker.getSavedPosYRatio() * frameH);
                  } else {
                      lp.topMargin = dp * (140 + i * 90); // legacy sticker with no saved position: stack as before
                  }
                  stickerOv.addView(sticker, lp);
                  if (centred) sticker.placeCenteredOnceLaidOut(centreX, centreY, fitK, frameW, frameH);

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
                                              handler
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
                      // Mix Audio: music volume / fades / pitch for the clip, original-video volume for the video.
                      // No mix on the sticker → neutral values, so old statuses behave exactly as before.
                      if (sticker.hasMusicMix()) {
                          musicClip.setMix(sticker.getMusicMixVol(), sticker.getMusicFadeInMs(),
                                  sticker.getMusicFadeOutMs(), sticker.getMusicPitch());
                          videoMixVol = sticker.getMusicOrigVol(); // -1 (photo / not set) keeps the 0.25 ducking
                      } else {
                          musicClip.setMix(-1f, 0, 0, 0f);
                          videoMixVol = -1f;
                      }
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
                                          handler
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
                                              handler
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
                                              handler
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
                      armStickerZoomGate(sticker, () -> openAddYoursComposer(sticker, item.id));
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
                          // The sheet's own preview player takes (permanent) audio focus, so our helper got
                          // AUDIOFOCUS_LOSS, abandoned focus and left musicFocusLost = true with nothing
                          // ever clearing it → resumeMusicPlayback() bailed out and the status stayed silent.
                          // The sheet is gone now: clear that stale state so resume re-requests focus + starts.
                          musicFocusLost = false;
                          musicDucked = false;
                          applyMusicVolume();
                          settleStickerReaction(sticker); // → resumeProgress() → resumeMusicPlayback()
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
      private void openAddYoursComposer(final StatusStickerOverlayView sticker, final String statusId) {
          final String prompt = sticker.getAddYoursPrompt();
          if (prompt == null || prompt.isEmpty()) { settleStickerReaction(sticker); return; }
          String originUid  = sticker.getAddYoursOriginUid();
          String originName = sticker.getAddYoursOriginName();
          if (originUid == null || originUid.isEmpty()) {
              originUid  = ownerUid;
              originName = ownerName;
          }
          final String fOriginUid = originUid, fOriginName = originName;
          final String chainId = com.callx.app.stickers.AddYoursHelper.resolveChainId(sticker, ownerUid, statusId);
          pauseProgress();
          // v578: one join per user per chain. If I already added mine -> show the chain instead of the composer.
          // A slow/offline check must never trap the viewer, so after 2.5s we simply open the composer.
          final java.util.concurrent.atomic.AtomicBoolean handled = new java.util.concurrent.atomic.AtomicBoolean(false);
          final Runnable onTimeout = () -> {
              if (handled.compareAndSet(false, true))
                  launchAddYoursComposer(sticker, chainId, prompt, fOriginUid, fOriginName);
          };
          handler.postDelayed(onTimeout, 2500L);
          com.callx.app.stickers.AddYoursHelper.checkJoined(chainId, myUid, joined -> {
              if (!handled.compareAndSet(false, true)) return;
              handler.removeCallbacks(onTimeout);
              if (isFinishing() || isDestroyed()) return;
              if (joined) {
                  android.widget.Toast.makeText(this, R.string.add_yours_toast_already, android.widget.Toast.LENGTH_SHORT).show();
                  com.callx.app.stickers.AddYoursHelper.openChain(this, chainId, prompt, fOriginUid, fOriginName,
                          sticker.getAddYoursTemplateJson()); // onResume() settles the sticker
              } else {
                  launchAddYoursComposer(sticker, chainId, prompt, fOriginUid, fOriginName);
              }
          });
      }

      /** Opens NewStatusActivity pre-loaded with the chain's sticker (+ the starter's template look). */
      private void launchAddYoursComposer(StatusStickerOverlayView sticker, String chainId, String prompt,
                                          String originUid, String originName) {
          if (isFinishing() || isDestroyed()) return;
          try {
              String json = com.callx.app.stickers.AddYoursHelper.buildJoinJson(
                      prompt, chainId, originUid, originName, sticker.getAddYoursTemplateJson());
              Intent intent = new Intent(this, com.callx.app.compose.NewStatusActivity.class);
              intent.putExtra(com.callx.app.compose.NewStatusActivity.EXTRA_PREFILL_STICKER_JSON, json);
              intent.putExtra(com.callx.app.compose.NewStatusActivity.EXTRA_PREFILL_TOAST,
                      com.callx.app.stickers.AddYoursHelper.adding(this, originName));
              pauseProgress();
              startActivity(intent); // onResume() will settle the sticker (shrink + resume) once we come back
          } catch (Exception ignored) {
              // Malformed data -- sticker just stays inert.
              settleStickerReaction(sticker);
          }
      }

      // v578: live "N people added" label on Add Yours stickers (+ tap -> chain screen)
      private final ArrayList<Runnable> addYoursCountDetachers = new ArrayList<>(2);
      private void bindAddYoursCount(final StatusStickerOverlayView sticker) {
          final String cid = sticker.getAddYoursChainId();
          if (cid == null || cid.isEmpty()) return; // pre-v578 sticker: no chain -> no counter
          addYoursCountDetachers.add(com.callx.app.stickers.AddYoursHelper.attachCount(cid, sticker));
          sticker.setOnAddYoursCountClick(() -> {
              pauseProgress(); // onResume() resumes when we come back
              String ou = sticker.getAddYoursOriginUid(), on = sticker.getAddYoursOriginName();
              if (ou == null || ou.isEmpty()) { ou = ownerUid; on = ownerName; }
              com.callx.app.stickers.AddYoursHelper.openChain(this, cid, sticker.getAddYoursPrompt(),
                      ou, on, sticker.getAddYoursTemplateJson());
          });
      }
      private void detachAddYoursCounts() {
          for (Runnable r : addYoursCountDetachers) { try { r.run(); } catch (Exception ignored) {} }
          addYoursCountDetachers.clear();
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
          pauseLiveRows();
          if (replyOverlayAutoHide != null && replyHideAtMs > 0 && !lastReplyExiting) {
              replyHideRemainingMs = Math.max(600L, replyHideAtMs - android.os.SystemClock.uptimeMillis());
              handler.removeCallbacks(replyOverlayAutoHide);
              replyHidePending = true;
          }
          if (player != null) player.setPlayWhenReady(false);
          if (musicPlayer != null) { try { if (musicPlayer.isPlaying()) musicPlayer.pause(); } catch (Exception ignored) {} }
          syncMusicStickerPlaying();
          stopProgress();
      }
      private void resumeProgress() {
          if (!paused) return; paused = false;
          resumeLiveRows();
          if (replyHidePending && replyOverlayAutoHide != null) { replyHidePending = false; scheduleReplyHide(replyHideRemainingMs); }
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
          if (isFinishing() || isDestroyed()) return;
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
          boolean curGone = pruneDead();
          if (!curGone) idx++; // current removed → idx already points at its successor
          showCurrent();
      }
      private void previous() {
          releasePlayer(); stopProgress();
          pruneDead();
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
          // v582: direction fixed — forward (left swipe) = new card hinges on its RIGHT edge and
          // opens toward the left (next page); back (right swipe) = hinges on LEFT edge.
          col.setPivotX(forward ? col.getWidth() : 0f);
          col.setPivotY(col.getHeight() / 2f);
          col.setRotationY(forward ? 70f : -70f);
          col.setTranslationX(forward ? col.getWidth() * 0.25f : -col.getWidth() * 0.25f);
          col.setAlpha(1f); // v581: no alpha dip during the cube turn
          col.animate().rotationY(0f).translationX(0f).setDuration(250L)
                  .setInterpolator(CUBE_INTERP)
                  .withEndAction(() -> { col.setPivotX(col.getWidth() / 2f); col.setRotationY(0f); col.setTranslationX(0f); columnHwLayerOff(); })
                  .start();
      }
      /** Tears down the current owner/album's on-screen state and loads the
       *  queue entry at the given position, resetting playback to its first
       *  segment. */
      private void switchQueueEntry(int pos) {
          releasePlayer(); stopProgress();
          detachLiveStatusListener();
          items.clear();
          idx = 0;
          targetStatusId = null; // only meant for the FIRST owner we opened on
          viewStartTime = 0;
          reactionBurstPlayedFor.clear();
          // v581/v583: keep the previous frame only when the new owner is served from the in-memory
          // cache (showCurrent() swaps it synchronously, same frame). On a cache miss / highlight
          // album the data arrives later, so clear now instead of rotating the OLD user's content in.
          boolean cacheReady = false;
          if (!isHighlightMode) {
              try {
                  cacheReady = !com.callx.app.cache.StatusCacheManager.getInstance(this)
                          .getStatuses(queueOwnerUids.get(pos)).isEmpty();
              } catch (Exception ignored) {}
          }
          if (!cacheReady) hideAllContent();
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
              ownerPhotoHint = pos < queueOwnerPhotos.size() ? queueOwnerPhotos.get(pos) : null;
              bindOwnerAvatar(null);
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
                  // (see screenshot ref): a large emoji grid appears centered over
                  // the story card; the keyboard opens only when the reply pill is tapped.
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
      // v581: removed root-level crossFadeIn() — fading the whole (dark-bg) root 0→1 on every
      // status/user change made the screen go black and re-brighten. Content cross-fades at
      // image level (Glide withCrossFade) instead.
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
      private final Runnable holdPauseRunnable = () -> { touchHoldPaused = true; pauseProgress(); setChromeHidden(true); maybeShowReshareHint(); };
      private boolean touchSwallow;

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
          final View rc = reshareContainerView;
          if (rc != null && rc.getVisibility() == View.VISIBLE) {
              rc.animate().cancel();
              rc.animate().alpha(hide ? 0f : 1f).setDuration(hide ? 150L : 200L).start();
          }
          // Reply bubble hides with the rest of the chrome (skip while it is already fading out on its own).
          final View rb = lastReplyBubbleView;
          if (rb != null && lastReplyShown && !lastReplyExiting && rb.getVisibility() == View.VISIBLE) {
              rb.animate().cancel();
              rb.animate().alpha(hide ? 0f : 1f).translationY(0f).setDuration(hide ? 150L : 200L).start();
              rb.setClickable(!hide);
              setReplyHitSlop(!hide);
          }
          // Reposted-story card header (+ its scrim) hides/shows with the chrome too.
          if (reshareCardActive && reshareHeaderView != null && reshareHeaderView.getVisibility() == View.VISIBLE) {
              reshareHeaderView.animate().cancel();
              reshareHeaderView.animate().alpha(hide ? 0f : 1f).translationY(0f).setDuration(hide ? 150L : 200L).start();
              if (reshareHeaderScrim != null) {
                  reshareHeaderScrim.animate().cancel();
                  reshareHeaderScrim.animate().alpha(hide ? 0f : 1f).setDuration(hide ? 150L : 200L).start();
              }
          }
          // Live comment stack hides/shows with the chrome too.
          if (binding.llLiveComments != null && binding.llLiveComments.getVisibility() == View.VISIBLE) {
              binding.llLiveComments.animate().cancel();
              binding.llLiveComments.animate().alpha(hide ? 0f : 1f).setDuration(hide ? 150L : 200L).start();
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
              // The "view original" pill is open: the next touch only closes it (no next/prev, no pause).
              if (e.getActionMasked() == MotionEvent.ACTION_DOWN && reshareHintView != null
                      && reshareHintView.getVisibility() == View.VISIBLE) {
                  dismissReshareHint(true);
                  touchSwallow = true;
                  return true;
              }
              if (touchSwallow) {
                  if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) touchSwallow = false;
                  return true;
              }
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
                          if (reshareHintShowing()) dismissReshareHint(false); // dragging away → pill goes
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
                      if (touchHoldPaused) { touchHoldPaused = false; if (!reshareHintShowing()) resumeProgress(); } // pill open → stay paused until it is dismissed
                      // Slow upward drag (no fling) also opens the reaction grid, like a fast swipe.
                      if (isUp && !flung && touchMoved && !isQuickReactionOverlayShown()) {
                          final float udy = e.getRawY() - dragDownRawY;
                          final float udx = e.getRawX() - dragDownRawX;
                          final float minUp = 72f * getResources().getDisplayMetrics().density;
                          if (udy < -minUp && Math.abs(udy) > Math.abs(udx) * 1.2f) {
                              openQuickReactionOverlay();
                              break;
                          }
                      }
                      boolean tap = isUp && !flung && !touchMoved
                              && (e.getEventTime() - e.getDownTime()) < TAP_MAX_MS;
                      if (tap && tryOpenMentionAt(e)) break; // tapped an @username → open profile instead of next/prev
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
      private boolean isOwnStatus() { return myUid != null && myUid.equals(ownerUid); }

      /** Swipe up = Instagram: big emoji grid over the story AND the reply keyboard. */
      private void openQuickReactionOverlay() {
          if (isOwnStatus()) return; // owner can't react to own status
          showQuickReactionUi();
          if (binding.etReply != null) {
              binding.etReply.requestFocus();
              android.view.inputmethod.InputMethodManager imm =
                      (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
              if (imm != null) imm.showSoftInput(binding.etReply, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
          }
      }

      /** Scrim bottom edge = reply bar top, so the grid is centered in the (keyboard-shrunk) story area. */
      private void syncQuickReactionInsets() {
          final View ov = quickReactionOverlayView;
          if (ov == null) return;
          android.view.ViewGroup.LayoutParams lp = ov.getLayoutParams();
          if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
              android.view.ViewGroup.MarginLayoutParams m = (android.view.ViewGroup.MarginLayoutParams) lp;
              int bar = binding.llReplyBar.getHeight();
              if (m.bottomMargin != bar) { m.bottomMargin = bar; ov.setLayoutParams(m); }
          }
      }

      /** Shows the grid (idempotent). Used by swipe-up AND by tapping the "Send message" pill. */
      private void showQuickReactionUi() {
          if (isOwnStatus()) return;
          final View overlay = ensureQuickReactionOverlay();
          if (overlay.getVisibility() == View.VISIBLE && overlay.getAlpha() > 0f) return;
          pauseProgress();
          quickReactionSent = false;
          overlay.animate().cancel(); // a pending fade-out end action must not hide a re-opened overlay
          syncQuickReactionInsets();
          binding.llReplyBar.removeOnLayoutChangeListener(quickReactionBarListener);
          binding.llReplyBar.addOnLayoutChangeListener(quickReactionBarListener);
          overlay.setAlpha(0f);
          overlay.setVisibility(View.VISIBLE);
          overlay.animate().alpha(1f).setDuration(140).start();
          // staggered pop-in, slight overshoot — each emoji bounces in one after the other
          if (quickReactionCells != null) {
              final StatusItem curItem = idx < items.size() ? items.get(idx) : null;
              final String mine = (curItem != null && myUid != null) ? curItem.getReaction(myUid) : null;
              final android.view.animation.OvershootInterpolator pop = new android.view.animation.OvershootInterpolator(2.2f);
              for (int i = 0; i < quickReactionCells.length; i++) {
                  final TextView c = quickReactionCells[i];
                  if (c == null) continue;
                  c.animate().cancel();
                  final boolean isMine = mine != null && mine.equals(c.getTag());
                  c.setBackgroundResource(isMine ? R.drawable.bg_quick_reaction_current : 0);
                  final String nm = QUICK_EMOJI_NAMES[i];
                  c.setContentDescription(isMine
                          ? getString(R.string.viewer_cd_your_reaction, nm)
                          : getString(R.string.viewer_cd_react_with, nm));
                  c.setAlpha(0f); c.setScaleX(0.3f); c.setScaleY(0.3f);
                  c.animate().alpha(1f).scaleX(1f).scaleY(1f)
                          .setStartDelay(40L + i * 35L).setDuration(240).setInterpolator(pop).start();
              }
          }
          if (quickReactionBack != null) quickReactionBack.setEnabled(true);
      }

      /** Hides the grid only (keyboard / focus untouched). */
      private void hideQuickReactionUi() {
          final View overlay = quickReactionOverlayView;
          if (quickReactionBack != null) quickReactionBack.setEnabled(false);
          binding.llReplyBar.removeOnLayoutChangeListener(quickReactionBarListener);
          if (overlay == null || overlay.getVisibility() != View.VISIBLE) return;
          overlay.animate().cancel();
          overlay.animate().alpha(0f).setDuration(120)
                  .withEndAction(() -> overlay.setVisibility(View.GONE)).start();
      }

      private void closeQuickReactionOverlay() {
          if (quickReactionOverlayView == null) return; // never opened → nothing to close
          hideQuickReactionUi();
          android.view.inputmethod.InputMethodManager imm =
                  (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
          if (imm != null && binding.etReply != null) imm.hideSoftInputFromWindow(binding.etReply.getWindowToken(), 0);
          if (binding.etReply != null) binding.etReply.clearFocus();
          resumeProgress();
      }

      private void onQuickEmojiTapped(View v) {
          Object tag = v.getTag();
          if (tag == null || quickReactionSent) return;
          quickReactionSent = true; // ignore double-taps while the pop animation plays
          v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
          v.animate().cancel();
          v.animate().scaleX(1.7f).scaleY(1.7f).alpha(1f).setStartDelay(0).setDuration(110)
                  .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
          if (quickReactionCells != null) {
              for (TextView c : quickReactionCells) {
                  if (c == null || c == v) continue;
                  c.animate().cancel();
                  c.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setStartDelay(0).setDuration(120).start();
              }
          }
          sendQuickReaction(tag.toString());          // Firebase + chat bubble + floating burst (burst shows once grid is gone)
          handler.postDelayed(this::closeQuickReactionOverlay, 140);
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
              binding.btnPublicComment.setVisibility(View.GONE);
              binding.llReplyRow.setVisibility(View.GONE); // own story: no reply/react row (bar stays as the nav-bar spacer)
              return;
          }
          // v511: "Send message" pill = PRIVATE reply (chat DM only). Typing happens inline,
          // like Instagram. No bottom sheet opens from the pill anymore.
          binding.etReply.setOnFocusChangeListener((v, has) -> {
              if (has) { showQuickReactionUi(); pauseProgress(); }
              else { hideQuickReactionUi(); resumeProgress(); }
          });
          binding.btnSendReply.setOnClickListener(v -> {
              String msg = binding.etReply.getText() != null
                      ? binding.etReply.getText().toString().trim() : "";
              // Instagram-style paper plane: nothing typed → it shares the story
              // (same forward sheet as the ⋯ menu); typed text → sends it privately.
              if (TextUtils.isEmpty(msg)) { runAct(actForward); return; }
              if (myUid == null || ownerUid == null) return;
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null) return;
              StatusReplyBottomSheet.sendPrivateReply(myUid, ownerUid, ownerName, current, msg);
              binding.etReply.clearFocus();   // first: focus listener hides the grid, so setText("") can't re-show it
              binding.etReply.setText("");
              android.view.inputmethod.InputMethodManager sentImm =
                      (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
              if (sentImm != null) sentImm.hideSoftInputFromWindow(binding.etReply.getWindowToken(), 0);
              resumeProgress();
              Toast.makeText(this, R.string.viewer_toast_sent_privately, Toast.LENGTH_SHORT).show();
          });
          // v511: separate comment icon = PUBLIC comment (visible to all viewers, no DM).
          binding.btnPublicComment.setOnClickListener(v -> {
              StatusItem current = idx < items.size() ? items.get(idx) : null;
              if (current == null || myUid == null) return;
              pauseProgress();
              android.app.Dialog d = StatusReplyBottomSheet.showPublicComment(
                      this, current, ownerName, myUid, ownerUid, msg -> resumeProgress());
              if (d != null) d.setOnDismissListener(x -> resumeProgress());
              else resumeProgress();
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
                  Toast.makeText(this, getString(R.string.viewer_toast_sharing_disabled, ownerName),
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
              Toast.makeText(this, getString(nowEnabled ? R.string.viewer_toast_stabilizer_on : R.string.viewer_toast_stabilizer_off),
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
          final int mc = current != null && current.mentionNames != null ? current.mentionNames.size() : 0;
          String[] opts = {"Who viewed this", "Delete this status", "Archive status", "Add to Highlights", "Analytics", stabLabel, mc > 0 ? "Mentions (" + mc + ")" : "Mentions", "Cancel"};
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
                                  ? getString(R.string.viewer_toast_deleted_highlights) : getString(R.string.viewer_toast_deleted), Toast.LENGTH_SHORT).show();
                          if (items.isEmpty()) { finish(); return; }
                          idx = Math.min(idx, items.size() - 1);
                          buildSegmentBars(); stopProgress(); showCurrent();
                      });
                  } else if (w == 2 && current != null) {
                      StatusHighlightManager.archiveStatus(ownerUid, current);
                      Toast.makeText(this, R.string.viewer_toast_archived, Toast.LENGTH_SHORT).show();
                      resumeProgress();
                  } else if (w == 3 && current != null) {
                      showAddToHighlightBottomSheet(current);
                  } else if (w == 4 && current != null) {
                      showAnalyticsBottomSheet(current);
                  } else if (w == 5) {
                      runAct(actStabilizer);
                      resumeProgress();
                  } else if (w == 6) {
                      showMentionsList(current);
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
                      Toast.makeText(this, R.string.viewer_toast_removed_from_highlight, Toast.LENGTH_SHORT).show();
                      if (items.isEmpty()) { finish(); return; }
                      idx = Math.min(idx, items.size() - 1);
                      buildSegmentBars(); stopProgress(); showCurrent();
                  } else if (w == 2) {
                      promptRenameHighlightAlbum();
                  } else if (w == 3 && current != null) {
                      String coverUrl = current.thumbnailUrl != null ? current.thumbnailUrl : current.mediaUrl;
                      StatusHighlightManager.setAlbumCover(ownerUid, highlightAlbumId, current.id, coverUrl);
                      Toast.makeText(this, R.string.viewer_toast_cover_updated, Toast.LENGTH_SHORT).show();
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
                      Toast.makeText(this, R.string.viewer_toast_renamed, Toast.LENGTH_SHORT).show();
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
                  Toast.makeText(this, R.string.viewer_toast_highlight_deleted, Toast.LENGTH_SHORT).show();
                  finish();
              })
              .setNegativeButton("Cancel", (d, w) -> resumeProgress())
              .setOnCancelListener(d -> resumeProgress())
              .create());
      }
      /** v653: Report is real now — reason picker, then one write-once record under /reports/{ownerUid}/. */
      private void showReportDialog(final StatusItem cur) {
          pauseProgress();
          final String[] reasons = getResources().getStringArray(R.array.viewer_report_reasons);
          AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
              .setTitle(R.string.viewer_report_title)
              .setItems(reasons, (d, w) -> submitStatusReport(cur, reasons[w], w))
              .setNegativeButton(R.string.viewer_cancel, (d, w) -> resumeProgress())
              .setOnCancelListener(d -> resumeProgress())
              .create());
      }

      private void submitStatusReport(StatusItem cur, String reason, int reasonIndex) {
          if (cur == null || cur.id == null || ownerUid == null || myUid == null || myUid.isEmpty()) {
              Toast.makeText(this, R.string.viewer_toast_report_failed, Toast.LENGTH_SHORT).show();
              resumeProgress();
              return;
          }
          java.util.Map<String, Object> r = new java.util.HashMap<>();
          r.put("reportedUid", ownerUid);          // same fields the admin "Reports" screen already reads
          r.put("reporterUid", myUid);
          r.put("reason", reason);
          r.put("reasonIndex", reasonIndex);
          r.put("status", "open");
          r.put("timestamp", System.currentTimeMillis());
          r.put("contentType", "status");
          r.put("statusId", cur.id);
          // one report per (status, reporter): the rules are write-once, a 2nd attempt is refused
          final String key = "status_" + cur.id + "_" + myUid;
          FirebaseUtils.db().getReference("reports").child(ownerUid).child(key).setValue(r)
              .addOnSuccessListener(u -> {
                  if (isFinishing()) return;
                  Toast.makeText(this, R.string.viewer_toast_report_sent, Toast.LENGTH_SHORT).show();
                  resumeProgress();
              })
              .addOnFailureListener(e -> {
                  if (isFinishing()) return;
                  String m = e.getMessage() != null ? e.getMessage().toLowerCase(java.util.Locale.ROOT) : "";
                  Toast.makeText(this, m.contains("permission") ? R.string.viewer_toast_report_already
                          : R.string.viewer_toast_report_failed, Toast.LENGTH_SHORT).show();
                  resumeProgress();
              });
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
                      ? getString(R.string.viewer_toast_muted, ownerName) : getString(R.string.viewer_toast_unmuted, ownerName), Toast.LENGTH_SHORT).show();
              finish();
          });
          labels.add("Download");  actions.add(() -> { runAct(actDownload); resumeProgress(); });
          labels.add("Forward");   actions.add(() -> runAct(actForward)); // sheet resumes progress itself
          if (cur != null && cur.allowSharing) {
              labels.add("Repost to my status"); actions.add(() -> runAct(actRepost));
          }
          labels.add(StoryStabilizerPrefs.isEnabled(this) ? "Turn stabilizer off" : "Turn stabilizer on");
          actions.add(() -> { runAct(actStabilizer); resumeProgress(); });
          if (cur != null && cur.mentionNames != null && !cur.mentionNames.isEmpty()) {
              labels.add("Mentions (" + cur.mentionNames.size() + ")"); actions.add(() -> showMentionsList(cur));
          }
          if (myUid == null || !myUid.equals(ownerUid)) {   // no point reporting your own status
              labels.add("Report"); actions.add(() -> showReportDialog(cur));
          }
          labels.add("Cancel");    actions.add(this::resumeProgress);
          AlertDialogStyler.showRounded(new AlertDialog.Builder(this)
              .setItems(labels.toArray(new String[0]), (d, w) -> actions.get(w).run())
              .setOnCancelListener(d -> resumeProgress())
              .create());
      }
      private void showAddToHighlightBottomSheet(StatusItem item) {
          pauseProgress();
          StatusAddToHighlightBottomSheet.show(this, ownerUid, item, albumName -> {
              Toast.makeText(this, getString(R.string.viewer_toast_added_to_album, albumName), Toast.LENGTH_SHORT).show();
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
              clearLiveComments();
              setReplyHitSlop(false);
              if (lastReplyBubbleView != null) lastReplyBubbleView.setVisibility(View.GONE);
              lastReplyShown = false; lastReplySig = null; lastReplyExiting = false; replyCollapsed = false;
              return;
          }
          prewarmLiveRows(); // 2 pooled comment rows inflated once, while the main thread is idle
          // Bubble only shows the newest 3 commenters + newest text → listen to just those 3
          // (was: the whole replies node, re-downloaded + re-parsed on every new comment).
          // Needs ".indexOn": ["timestamp"] on status/$uid/$statusId/replies.
          repliesListenerRef = FirebaseUtils.getStatusRepliesRef(ownerUid, s.id)
                  .orderByChild("timestamp").limitToLast(4); // 4th only tells us "more than 3" (+ badge)
          repliesListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  final ValueEventListener self = this;
                  // v513: parse + sort off the main thread; only the bubble bind runs on main.
                  REPLY_PARSE_BG.execute(() -> {
                      Map<String, StatusItem.ReplyPreview> repliesMap = new LinkedHashMap<>();
                      for (DataSnapshot child : snap.getChildren()) {
                          // manual field reads — no reflection (getValue(Class))
                          Object t = child.child("timestamp").getValue();
                          StatusItem.ReplyPreview r = new StatusItem.ReplyPreview(
                                  child.child("uid").getValue(String.class),
                                  child.child("name").getValue(String.class),
                                  child.child("avatarUrl").getValue(String.class),
                                  child.child("text").getValue(String.class),
                                  t instanceof Number ? Long.valueOf(((Number) t).longValue()) : null);
                          if (child.getKey() != null) repliesMap.put(child.getKey(), r);
                      }
                      final List<StatusItem.ReplyPreview> newestFirst = new ArrayList<>(repliesMap.values());
                      Collections.sort(newestFirst, (a, b) -> {
                          long ta = a.timestamp != null ? a.timestamp : 0;
                          long tb = b.timestamp != null ? b.timestamp : 0;
                          return Long.compare(tb, ta);
                      });
                      handler.post(() -> {
                          if (repliesListener != self || isFinishing() || isDestroyed()) return; // detached / segment changed
                          // Keep the StatusItem in sync so a tap on the overlay opens
                          // StatusRepliesBottomSheet with this newest-few as an instant seed (it then goes live).
                          s.replies = repliesMap;
                          bindLastReplyBubble(s, newestFirst);
                      });
                  });
              }
              @Override public void onCancelled(DatabaseError e) {
                  android.util.Log.e("StatusViewer",
                      "Replies listener cancelled for " + ownerUid + "/" + s.id
                          + ": " + e.getMessage());
              }
          };
          repliesListenerRef.addValueEventListener(repliesListener);
          // v514 keepSynced: same query the sheet listens on → opening the sheet is served from the local cache.
          // Released in detachRepliesListener() (segment change / close). Persistence is enabled in CallxApp.
          try {
              repliesSyncQuery = com.callx.app.interactions.StatusRepliesBottomSheet.liveQuery(
                      FirebaseUtils.getStatusRepliesRef(ownerUid, s.id));
              repliesSyncQuery.keepSynced(true);
          } catch (Exception ignored) { repliesSyncQuery = null; }
          // v514 prewarm: my profile + avatar (compose sheet / Post) are ready before the viewer taps comment.
          // The last 3 commenters' avatars are warmed by bindLastReplyBubble() (same TINY cache key as the sheet rows).
          if (!ownProfilePrewarmed && myUid != null && !myUid.equals(ownerUid)) {
              ownProfilePrewarmed = true;
              com.callx.app.interactions.StatusReplyBottomSheet.prewarm(this, myUid);
          }
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
              clearLiveComments();
              if (lastReplyBubbleView != null) {
                  setReplyHitSlop(false);
                  lastReplyBubbleView.setVisibility(View.GONE);
                  lastReplyBubbleView.setAlpha(1f);
              }
              lastReplyShown = false; lastReplySig = null; lastReplyExiting = false; replyCollapsed = false;
              return;
          }
          // Instagram-style perf: Firebase re-fires for unrelated child edits — if the newest-3
          // (uid, ts, text) are identical, skip the whole rebind + animation + timer reset.
          final StringBuilder sigB = new StringBuilder();
          for (int i = 0; i < Math.min(3, newestFirst.size()); i++) {
              StatusItem.ReplyPreview r = newestFirst.get(i);
              sigB.append(r.uid).append('|').append(r.timestamp).append('|').append(r.avatarUrl).append('|')
                  .append(r.text == null ? 0 : r.text.hashCode()).append(';');
          }
          final boolean hasMore = newestFirst.size() > 3;
          sigB.append(hasMore ? "+" : "");
          final String sig = sigB.toString();
          if (sig.equals(lastReplySig)) return; // nothing new → keep the live comment queue as is
          lastReplySig = sig;
          // The 3-avatar stack pill is removed: the (inflated, always GONE) bubble only stays as the
          // tap target for the live comment row → replies sheet. Live comment animation is untouched.
          final View bubble = lastReplyBubble();
          bubble.setVisibility(View.GONE);
          lastReplyShown = false; lastReplyExiting = false;
          bubble.setOnClickListener(v -> {
              if (replyOverlayAutoHide != null) handler.removeCallbacks(replyOverlayAutoHide);
              pauseProgress();
              com.callx.app.interactions.StatusRepliesBottomSheet.show(this, ownerUid, myUid, s, this::resumeProgress);
          });
          updateLiveComments(newestFirst, bubble);
      }

      // ═════════════════════════════════════════════════════════════════════
      //  Live comment stack (Instagram-style)
      //   • v576: ONE comment at a time, no background (like Instagram): nothing → small avatar + text zoom
      //     in (scale+fade+rise) → stays ~2s → zooms back out → next queued comment appears
      //   • comments that arrive while one is showing wait in livePending (FIFO)
      //   • timers freeze while the story is held/paused (pauseLiveRows/resumeLiveRows)
      //   • tapping a row opens the same replies sheet as the avatar pill
      //
      //  PERF:
      //   • row views are POOLED (max 3 alive + a small pool) — a comment never inflates a layout after
      //     the first ones; 2 rows are pre-inflated once per activity from an idle handler, so even the
      //     very first comment skips the inflate on the main thread
      //   • one shared pre-draw listener for the FLIP (no per-comment listener/anonymous class), and
      //     several rows added in the same frame are handled in a single bottom→top pass
      //   • shared static interpolators, cached dp values, avatar reload skipped when the URL is unchanged,
      //     comment text capped (140 chars) before the span/StaticLayout work
      //   • rows animate with a hardware layer only while they fade (withLayer); the container is a
      //     NoOverlapLinearLayout, so chrome-hide alpha never forces an offscreen layer
      // ═════════════════════════════════════════════════════════════════════
      private static final int  LIVE_MAX_ROWS        = 1;   // one visible comment at a time
      private static final int  LIVE_QUEUE_MAX       = 3;   // newest N waiting/shown on first bind
      private static final int  LIVE_POOL_MAX        = 3;
      private static final int  LIVE_TEXT_MAX_CHARS  = 140;
      private static final long LIVE_ENTER_MS        = 280L;
      private static final long LIVE_EXIT_MS         = 200L;
      private static final long LIVE_GAP_MS          = 220L; // empty beat between two comments
      private static final long LIVE_ROW_VISIBLE_MS  = LIVE_ENTER_MS + 2_000L; // ~2s fully visible after the zoom-in
      private static final float LIVE_ZOOM_FROM      = 0.6f;
      private static final android.view.animation.Interpolator LIVE_DECEL =
              new android.view.animation.DecelerateInterpolator(1.6f);
      private static final android.view.animation.Interpolator LIVE_ACCEL =
              new android.view.animation.AccelerateInterpolator();
      private static final class LiveRow {
          View v; Runnable exit; long deadline; long remaining;
      }
      private final ArrayList<LiveRow> liveRows = new ArrayList<>(LIVE_MAX_ROWS + 1);
      private final java.util.HashSet<String> liveShownKeys = new java.util.HashSet<>();
      private final ArrayDeque<View> livePool = new ArrayDeque<>(LIVE_POOL_MAX + 1);
      private final ArrayDeque<StatusItem.ReplyPreview> livePending = new ArrayDeque<>();
      private View liveBubble;
      private boolean liveBusy, livePrewarmed; // liveBusy: a row is entering/showing/exiting (or in the gap)
      private final Runnable liveGapRunnable = () -> { liveBusy = false; drainLiveQueue(); };
      private int liveRisePx = -1;

      private static String liveKey(StatusItem.ReplyPreview r) {
          return r.uid + "|" + r.timestamp;
      }
      private int liveRisePx() {
          if (liveRisePx < 0) liveRisePx = dpToPx(14);
          return liveRisePx;
      }
      private View inflateLiveRow() {
          return getLayoutInflater().inflate(R.layout.item_status_live_comment, binding.llLiveComments, false);
      }
      private View obtainLiveRow() {
          View v = livePool.pollFirst();
          return v != null ? v : inflateLiveRow();
      }
      private void recycleLiveRow(View v) {
          v.animate().cancel();
          v.setAlpha(0f);
          v.setTranslationY(0f);
          v.setOnClickListener(null);
          if (livePool.size() < LIVE_POOL_MAX) livePool.addLast(v);
      }
      /** Inflates 2 rows once per activity while the main thread is idle (first comment then has no inflate cost). */
      private void prewarmLiveRows() {
          if (livePrewarmed) return;
          livePrewarmed = true;
          Looper.myQueue().addIdleHandler(() -> {
              if (!isFinishing() && !isDestroyed() && binding != null) {
                  while (livePool.size() < 2) {
                      View v = inflateLiveRow();
                      v.setAlpha(0f);
                      livePool.addLast(v);
                  }
              }
              return false; // one-shot
          });
      }
      /** Queues comments not shown yet (oldest first); they play one after another. */
      private void updateLiveComments(List<StatusItem.ReplyPreview> newestFirst, final View bubble) {
          final int n = Math.min(LIVE_QUEUE_MAX, newestFirst.size());
          boolean added = false;
          for (int i = n - 1; i >= 0; i--) {
              final StatusItem.ReplyPreview r = newestFirst.get(i);
              if (!liveShownKeys.add(liveKey(r))) continue;
              livePending.addLast(r);
              added = true;
          }
          while (livePending.size() > LIVE_QUEUE_MAX) livePending.pollFirst(); // never fall far behind
          liveBubble = bubble;
          if (added) drainLiveQueue();
      }
      private void drainLiveQueue() {
          if (isFinishing() || isDestroyed() || binding == null) return;
          if (liveBusy || livePending.isEmpty() || liveBubble == null) return;
          addLiveRow(livePending.pollFirst(), liveBubble);
      }
      private void bindLiveRow(View row, StatusItem.ReplyPreview r) {
          final android.widget.ImageView av = row.findViewById(R.id.iv_live_avatar);
          final TextView tv = row.findViewById(R.id.tv_live_text);
          String who = r.name != null && !r.name.isEmpty() ? r.name : "Someone";
          if (who.length() > 14) who = who.substring(0, 13).trim() + "\u2026";
          String body = r.text != null ? r.text.replace('\n', ' ') : "";
          if (body.length() > LIVE_TEXT_MAX_CHARS) body = body.substring(0, LIVE_TEXT_MAX_CHARS).trim() + "\u2026";
          final android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(who);
          sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, who.length(),
                  android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          sb.append("  ");
          final int bs = sb.length();
          sb.append(body);
          sb.setSpan(new android.text.style.ForegroundColorSpan(0xCCFFFFFF), bs, sb.length(),
                  android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
          tv.setText(sb);
          final String url = r.avatarUrl;
          if (url != null && !url.isEmpty()) {
              if (!url.equals(av.getTag(R.id.iv_live_avatar))) { // pooled row already shows this avatar → no reload
                  av.setTag(R.id.iv_live_avatar, url);
                  // same TINY cache key as the avatar pill / replies sheet → already warm
                  final int px = com.callx.app.utils.AvatarUrlBuilder.tierPx(this, com.callx.app.utils.AvatarSizeTier.TINY);
                  Glide.with(this)
                          .load(com.callx.app.utils.AvatarUrlBuilder.buildResponsive(
                                  this, url, com.callx.app.utils.AvatarSizeTier.TINY))
                          .apply(new com.bumptech.glide.request.RequestOptions()
                                  .override(px, px)
                                  .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                                  .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.RESOURCE))
                          .into(av);
              }
          } else if (av.getTag(R.id.iv_live_avatar) != null) {
              av.setTag(R.id.iv_live_avatar, null);
              av.setImageResource(R.drawable.ic_person);
          }
      }
      private void addLiveRow(StatusItem.ReplyPreview r, final View bubble) {
          if (isFinishing() || isDestroyed()) return;
          final android.widget.LinearLayout box = binding.llLiveComments;
          liveBusy = true;
          final View row = obtainLiveRow();
          bindLiveRow(row, r);
          row.setOnClickListener(v -> bubble.performClick()); // same replies sheet as the pill
          final LiveRow lr = new LiveRow();
          lr.v = row;
          lr.exit = () -> removeLiveRow(lr, false);
          if (paused) { lr.deadline = 0; lr.remaining = LIVE_ROW_VISIBLE_MS; }
          else { lr.deadline = android.os.SystemClock.uptimeMillis() + LIVE_ROW_VISIBLE_MS; handler.postDelayed(lr.exit, LIVE_ROW_VISIBLE_MS); }
          liveRows.add(lr);
          box.setAlpha(chromeHidden ? 0f : 1f);
          if (box.getVisibility() != View.VISIBLE) box.setVisibility(View.VISIBLE);
          // start state: invisible, small, slightly low → zooms up into place around the avatar
          row.animate().cancel();
          row.setAlpha(0f);
          row.setScaleX(LIVE_ZOOM_FROM);
          row.setScaleY(LIVE_ZOOM_FROM);
          row.setPivotX(dpToPx(14));
          row.setPivotY(dpToPx(12));
          row.setTranslationY(liveRisePx());
          box.addView(row);
          row.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                  .setDuration(LIVE_ENTER_MS).setInterpolator(LIVE_DECEL).withLayer().start();
      }
      /** Zooms the row back out (its timer ran out), removes it, then plays the next queued comment after a short gap. */
      private void removeLiveRow(final LiveRow lr, boolean quick) {
          if (lr == null || !liveRows.remove(lr)) return;
          handler.removeCallbacks(lr.exit);
          final android.widget.LinearLayout box = binding.llLiveComments;
          final View row = lr.v;
          if (row.getParent() != box) { liveBusy = false; return; }
          row.animate().cancel();
          row.animate().alpha(0f).scaleX(LIVE_ZOOM_FROM).scaleY(LIVE_ZOOM_FROM).translationY(liveRisePx() / 2f)
                  .setDuration(quick ? 100L : LIVE_EXIT_MS)
                  .setInterpolator(LIVE_ACCEL).withLayer()
                  .withEndAction(() -> {
                      if (row.getParent() != box) return; // clearLiveComments() already reset everything
                      box.removeView(row);
                      recycleLiveRow(row);
                      if (box.getChildCount() == 0) box.setVisibility(View.GONE);
                      handler.postDelayed(liveGapRunnable, LIVE_GAP_MS);
                  }).start();
      }
      private void clearLiveComments() {
          if (liveRows.isEmpty() && livePending.isEmpty() && liveShownKeys.isEmpty() && !liveBusy
                  && (binding == null || binding.llLiveComments == null || binding.llLiveComments.getChildCount() == 0)) return;
          for (LiveRow lr : liveRows) handler.removeCallbacks(lr.exit);
          handler.removeCallbacks(liveGapRunnable);
          if (binding != null && binding.llLiveComments != null) {
              final android.widget.LinearLayout box = binding.llLiveComments;
              box.removeAllViews();
              box.setVisibility(View.GONE);
          }
          livePending.clear();
          liveBusy = false;
          liveBubble = null;
          for (LiveRow lr : liveRows) recycleLiveRow(lr.v);
          liveRows.clear();
          liveShownKeys.clear();
      }
      private void pauseLiveRows() {
          if (liveRows.isEmpty()) return;
          final long now = android.os.SystemClock.uptimeMillis();
          for (LiveRow lr : liveRows) {
              if (lr.deadline > 0) {
                  lr.remaining = Math.max(600L, lr.deadline - now);
                  lr.deadline = 0;
                  handler.removeCallbacks(lr.exit);
              }
          }
      }
      private void resumeLiveRows() {
          if (liveRows.isEmpty()) return;
          final long now = android.os.SystemClock.uptimeMillis();
          for (LiveRow lr : liveRows) {
              if (lr.deadline == 0 && lr.remaining > 0) {
                  lr.deadline = now + lr.remaining;
                  handler.postDelayed(lr.exit, lr.remaining);
              }
          }
      }
      private void detachRepliesListener() {
          if (repliesSyncQuery != null) {
              try { repliesSyncQuery.keepSynced(false); } catch (Exception ignored) {}
              repliesSyncQuery = null;
          }
          if (repliesListenerRef != null && repliesListener != null)
              repliesListenerRef.removeEventListener(repliesListener);
          repliesListenerRef = null;
          repliesListener = null;
          if (replyOverlayAutoHide != null) {
              handler.removeCallbacks(replyOverlayAutoHide);
              replyOverlayAutoHide = null;
              replyHideAtMs = 0; replyHidePending = false;
          }
      }
      /** Keeps the quick-like heart filled/outline in sync with whether I've already ❤️'d this status. */
      private void updateLikeHeartIcon(StatusItem s) {
          if (binding.btnLikeHeart == null) return;
          boolean liked = s != null && LIKE_EMOJI.equals(s.getReaction(myUid));
          binding.btnLikeHeart.setImageResource(liked ? R.drawable.ic_heart_filled : R.drawable.ic_heart);
          binding.btnLikeHeart.setContentDescription(getString(liked ? R.string.viewer_cd_remove_like : R.string.viewer_cd_like));
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
          if (s.effectiveExpiry() > 0) {
              long diffMs = s.effectiveExpiry() - System.currentTimeMillis();
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