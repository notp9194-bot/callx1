package com.callx.app.conversation;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.Transformations;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.callx.app.cache.CacheManager;
import com.callx.app.cache.LastMessagesCache;
import com.callx.app.chat.R;
import com.callx.app.chat.analytics.ReplyAnalyticsTracker;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.chat.gesture.SwipeReplyHandler;
import com.callx.app.chat.performance.SwipeOptimizer;
import com.callx.app.chat.reply.ReplyController;
import com.callx.app.chat.reply.ReplyDataMapper;
import com.callx.app.chat.ui.GifAwareEditText;
import com.callx.app.chat.ui.MessageHighlightAnimator;
import com.callx.app.conversation.controllers.ChatActivityDelegate;
import com.callx.app.conversation.controllers.ChatBlockController;
import com.callx.app.conversation.controllers.ChatEmojiBurstController;
import com.callx.app.conversation.controllers.ChatLiveTypingController;
import com.callx.app.conversation.controllers.ChatMediaController;
import com.callx.app.conversation.controllers.ChatMessageSender;
import com.callx.app.conversation.controllers.ChatPinController;
import com.callx.app.conversation.controllers.ChatPollController;
import com.callx.app.conversation.controllers.ChatStarredController;
import com.callx.app.conversation.controllers.ChatReactionController;
import com.callx.app.conversation.controllers.MessageEditHistoryController;
import com.callx.app.conversation.controllers.ChatPresenceController;
import com.callx.app.conversation.controllers.ChatPlaybackPresenceController;
import com.callx.app.conversation.controllers.RecordingPreviewController;
import com.callx.app.conversation.controllers.ChatScheduledSendController;
import com.callx.app.conversation.controllers.ChatViewOnceController;
import com.callx.app.conversation.controllers.ChatScreenshotNotifier;
import com.callx.app.conversation.controllers.ChatSearchController;
import com.callx.app.conversation.controllers.ChatThemeController;
import com.callx.app.conversation.controllers.ChatExportController;
import com.callx.app.group.ChatBackupActivity;
import com.callx.app.conversation.controllers.ChatContactShareController;
import com.callx.app.conversation.controllers.ChatLocationShareController;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ChatActivity — 1:1 chat screen coordinator.
 *
 * All heavy logic is delegated to controller classes:
 *   • ChatBlockController    — block / perma-block / unblock-joy / special-request
 *   • ChatPresenceController — typing, online-status, in-chat-screen presence, mute, mark-read
 *   • ChatPlaybackPresenceController — "listening…/watching…" badge while partner plays a voice note/video
 *   • ChatPinController      — pin / unpin
 *   • MessageEditHistoryController — edit message text + view prior versions
 *   • ChatScheduledSendController — schedule a message to auto-send later (WorkManager)
 *   • ChatSearchController   — in-chat search
 *   • ChatThemeController    — theme, wallpaper, customization, privacy dialogs
 *   • ChatMediaController    — media pickers, upload, camera, GIF, voice
 *   • ChatMessageSender      — local-first send, Firebase push, pending retry
 *
 * Architecture:
 *   Firebase RT DB ──ChildEventListener──► Room DB (auto-invalidates PagingSource)
 *   Pager<Long, MessageEntity> (keyset) ──LiveData──► PagingAdapter ──► RecyclerView
 */
public class ChatActivity extends AppCompatActivity implements ChatActivityDelegate,
        com.callx.app.conversation.info.MessageInfoBottomSheet.HostRecyclerPauseListener {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final String TAG           = "ChatActivity";
    // PERF: PagingConfig tuning.
    // PAGE_SIZE=20 — one screen worth; Room reads 20 rows per page-append request.
    // PREFETCH_DIST=10 — Room starts next page when 10 items remain unscrolled;
    //   sweet spot: low enough to avoid wasted pre-fetches, high enough to hide
    //   load latency during a fast flick.
    // INITIAL_LOAD=30 — first open shows 30 messages (~2.5 screens); more = slower cold open.
    // enablePlaceholders=false — placeholders force Paging 3 to know total count up-front
    //   (expensive Firebase query); disabling avoids that and keeps the list growing naturally.
    private static final int    PAGE_SIZE     = 20;
    // PERF #7: Increased PREFETCH_DIST 10→20 — Room starts fetching the next
    // page when 20 items remain unscrolled, giving twice the runway for DB
    // reads to complete before the user reaches the page boundary. Prevents
    // the "white flash" on fast flings that exhaust the previous 10-item
    // buffer before the page arrives. enablePlaceholders=false is already
    // set in attachPagerWithKey(), so no total-count query is triggered.
    private static final int    PREFETCH_DIST = 20;
    private static final int    INITIAL_LOAD  = 30;
    private static final int    MAX_MESSAGE_LENGTH = 4000;

    // ── View binding ───────────────────────────────────────────────────────
    private ActivityChatBinding binding;

    // ── Chat identifiers ───────────────────────────────────────────────────
    private String chatId;
    private String partnerUid;
    private String partnerName;
    private String partnerPhoto;
    private String partnerThumb;
    private String currentUid;
    private String currentName;

    // ── State flags ────────────────────────────────────────────────────────
    private boolean isMuted               = false;
    private boolean isBlocked             = false;
    private boolean isRecording           = false;
    private boolean partnerPermaBlockedMe = false;
    private boolean iPermaBlockedPartner  = false;

    // ── Paging 3 ──────────────────────────────────────────────────────────
    private MessagePagingAdapter pagingAdapter;
    private boolean isViewOnceModeOn = false;
    /** Feature 2: expiry duration chosen by sender (0 = no expiry). */
    private long selectedViewOnceExpiryMs = 0L;
    /** Feature 1: track active view-once dialog so onPause() can auto-dismiss it. */
    private android.app.AlertDialog activeViewOnceDialog = null;
    private AppDatabase          db;
    // PERF FIX: 2 → 4. On chat open, getMessageCount / getLastSyncTimestamp /
    // LastMessagesCache-seeding all queue on this pool; 2 threads was enough
    // to serialize them on low-end devices and add visible delay.
    private final Executor       ioExecutor = Executors.newFixedThreadPool(4);

    // ── Firebase ───────────────────────────────────────────────────────────
    private DatabaseReference  messagesRef;
    private ChildEventListener messageListener;
    // TICK FIX: dedicated status-sync listener — see attachFirebaseListener()
    // doc comment below for why this is needed alongside messageListener.
    private ChildEventListener statusSyncListener;
    // How many of the most-recent messages stay "live" for delivered/read
    // tick updates. Wider than INITIAL_LOAD on purpose: INITIAL_LOAD is only
    // the first-open page size, but a message can sit around a while before
    // the partner's "delivered"/"read" write lands, so this window needs
    // enough headroom that a normal back-and-forth conversation still has
    // its ticks tracked live.
    private static final int STATUS_SYNC_WINDOW = 100;

    // ── PERF FIX: write-coalescing buffer for Firebase → Room sync ─────────
    // Root cause of the "chat opens with 3-4s delay + up/down jump" bug:
    // Firebase replays the last N messages as N separate onChildAdded()
    // events fired back-to-back. The old code wrote each one to Room the
    // instant it arrived, so opening a chat with 30 messages meant 30
    // separate Room writes → 30 separate PagingSource invalidations → 30
    // separate submitData()/diff/layout passes, visible as the list
    // growing and re-jumping to the bottom one message at a time.
    // Fix: buffer every add/change/remove/read-receipt that arrives within
    // a short window and flush them all in ONE Room transaction — see
    // MessageDao#applyBufferedChanges(). One transaction = one invalidation
    // = one single-pass render.
    private static final long WRITE_FLUSH_DEBOUNCE_MS = 80;
    private final java.util.Map<String, Message> pendingUpserts = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashSet<String> pendingRemovals = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> pendingReadIds = new java.util.LinkedHashSet<>();
    private final android.os.Handler writeFlushHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean writeFlushScheduled = false;
    private final Runnable writeFlushRunnable = this::flushPendingRoomWrites;

    // ── PERF FIX: don't fight stackFromEnd on the very first render ────────
    // The LinearLayoutManager's stackFromEnd(true) already anchors the
    // layout at the bottom the first time items are inserted into an empty
    // RecyclerView. Forcing an extra explicit scrollToPosition() on that
    // same first insert produced a visible double-scroll / snap right as
    // the chat opened. We only need the explicit scroll for LATER inserts
    // (a genuinely new message arriving while the user is at the bottom).
    private boolean firstPageRendered = false;

    // initialScrollDone: guards the one-time "first data on screen" scroll
    // logic in onItemRangeInserted. Deliberately SEPARATE from firstPageRendered
    // because firstPageRendered is also set early (line ~350) on a warm-cache hit,
    // which caused onItemRangeInserted to skip the guard and call an explicit
    // scrollToPosition() on an un-anchored RecyclerView — producing the visible
    // "top → bottom" scroll animation every time chat opened from the cache.
    private boolean initialScrollDone = false;

    // ── WhatsApp-style intelligent scroll state ───────────────────────────
    // SCROLL_PREFS: SharedPreferences key for persisting scroll position and
    // last-seen-message timestamp across chat screen opens/closes.
    private static final String SCROLL_PREFS   = "chat_scroll_prefs_v2";
    // isUserAtBottom: true when user is within 3 items of the last message.
    // NOTE: starts as FALSE — restoreScrollOrGoToUnread() sets it to true
    //       only when we actually land at the bottom after the first render.
    //       Starting true caused Paging 3's second+ insert bursts (which fire
    //       BEFORE the post()-delayed restoreScrollOrGoToUnread() runs) to
    //       call scrollToPosition(total-1) and produce the visible top→bottom
    //       scroll every time the chat opened.
    // Used to decide whether to auto-scroll on new inserts.
    private boolean isUserAtBottom             = false;
    // pendingNewMsgCount: count of messages from others that arrived while
    // user was scrolled up. Shown in the "↓ N new messages" indicator.
    private int     pendingNewMsgCount         = 0;

    // ── WHATSAPP-STYLE REVEAL SCROLL TUNING ─────────────────────────────
    // Only one auto-scroll should ever be "in flight" toward the tail —
    // if several inserts land in quick succession (fast back-to-back
    // messages), each one cancels the previous pending post and reposts
    // with the freshest target instead of stacking multiple competing
    // smoothScrollBy() calls (which is what causes a stutter/"double
    // bounce" feel instead of one continuous glide).
    private Runnable pendingAutoScrollRunnable = null;
    // Material "standard decelerate" bezier — fast start, gentle settle.
    // Matches the motion feel WhatsApp/Telegram use for their own list
    // reveal instead of RecyclerView's default (linear-ish) scroller curve.
    private static final android.view.animation.Interpolator WHATSAPP_REVEAL_INTERPOLATOR =
            new android.view.animation.PathInterpolator(0.22f, 0.61f, 0.36f, 1f);
    // Duration scales gently with distance so a tall multi-line bubble
    // doesn't feel "too fast" while a one-line bubble doesn't feel
    // "too slow" — clamped so it's always a quick, buttery reveal and
    // never a slow crawl or a jarring snap.
    private static final float WHATSAPP_REVEAL_MS_PER_PIXEL   = 0.6f;
    private static final int   WHATSAPP_REVEAL_MIN_DURATION_MS = 160;
    private static final int   WHATSAPP_REVEAL_MAX_DURATION_MS = 280;

    // ── PERF FIX: don't show the skeleton at all for fast/cached loads ─────
    // Shimmer used to be set VISIBLE unconditionally the instant onCreate ran,
    // before Paging even had a chance to check whether Room already had this
    // chat's messages cached. Result: even a 5ms cache hit still showed a
    // guaranteed skeleton flash every single open. Real fix: don't show
    // shimmer immediately — schedule it 150ms out, and cancel that scheduled
    // show the moment data actually arrives. A cached chat resolves in a few
    // ms, so the shimmer runnable gets cancelled before it ever runs — no
    // flash at all. A genuinely slow (cold network) load still shows it,
    // same as before, just slightly delayed.
    private static final long SHIMMER_SHOW_DELAY_MS = 150;
    private final android.os.Handler shimmerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable shimmerShowRunnable = () -> {
        if (isFinishing() || isDestroyed()) return;
        if (binding.shimmerContainer != null) {
            binding.shimmerContainer.startShimmer();
            binding.shimmerContainer.setVisibility(View.VISIBLE);
        }
    };

    // ── Reply state ────────────────────────────────────────────────────────
    private Message replyingTo = null;
    // Set right before startReply() when the reply was triggered by a
    // swipe-up gesture on a SPECIFIC image/video inside a multi_media
    // gallery (MediaViewerActivity) — lets the reply-bar preview show that
    // exact item's thumb/caption instead of the generic group thumb.
    // -1 = not from gallery / not item-specific.
    private int pendingReplyItemIndex = -1;
    private ReplyController  replyController;
    private ItemTouchHelper  swipeHelper;

    // ── Edge-swipe-to-back (left/right screen edge se swipe karke back) ────
    private com.callx.app.utils.EdgeSwipeBackHelper edgeSwipeBackHelper;

    // ── Network ────────────────────────────────────────────────────────────
    private ConnectivityManager connMgr;
    private ConnectivityManager.NetworkCallback netCallback;

    // ── Typing debounce ────────────────────────────────────────────────────
    private final android.os.Handler typingHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    // NOTE: must be a method reference (not a lambda with a field ref) to avoid
    // "illegal forward reference" — presenceController is declared further down.
    private final Runnable stopTypingRunnable = this::onStopTypingTimeout;

    // ── Disappearing messages expiry ───────────────────────────────────────
    private final android.os.Handler expiryHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable expiryRunnable;

    // ── Selection toolbar ──────────────────────────────────────────────────
    private boolean selectionToolbarSetup = false;

    // ── Glide preload state ────────────────────────────────────────────────
    // Strong references to in-flight WarmCacheTargets so Glide cannot GC/cancel
    // them before the decoded Bitmap is stored in the LRU memory cache.
    // Cleared (and the requests cancelled) in onPause() and onDestroy().
    private final java.util.List<WarmCacheTarget> activePreloadTargets = new java.util.ArrayList<>();
    // Timestamp of the last preload run — used to debounce onResume() calls
    // (e.g. returning immediately from a permission dialog should not re-fire).
    private long lastPreloadTimeMs = 0L;
    private static final long PRELOAD_DEBOUNCE_MS = 3_000L; // 3 s between warm-up runs

    // ── Controllers ────────────────────────────────────────────────────────
    private ChatBlockController    blockController;
    private ChatPresenceController presenceController;
    private ChatPlaybackPresenceController playbackPresenceController;
    private RecordingPreviewController recordingPreviewController;
    private ChatLiveTypingController liveTypingController;
    private ChatEmojiBurstController emojiBurstController;
    private ChatPinController      pinController;
    private MessageEditHistoryController editHistoryController;
    private ChatReactionController reactionController;
    private ChatPollController     pollController;
    private ChatStarredController  starredController;
    private ChatContactShareController contactShareController;
    private ChatLocationShareController locationShareController;
    private ChatScheduledSendController scheduledSendController;
    /** Feature 13: View Once / Secret Message controller. */
    private ChatViewOnceController viewOnceController;
    private ChatSearchController   searchController;
    private com.callx.app.conversation.controllers.ChatMentionController mentionController;
    private ChatThemeController    themeController;
    private ChatExportController   exportController;
    private ChatMediaController    mediaController;
    private ChatMessageSender      messageSender;
    private ChatScreenshotNotifier screenshotNotifier;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // TraceSectionMetric("ChatActivity#onCreate") — full cold-start cost from
        // Activity creation to end of onCreate. This is the top-level benchmark
        // section. Target: < 300ms. If > 300ms, drill into sub-sections:
        //   DB#getInstance    → SQLCipher init slow
        //   SecurityManager#init → EncryptedSharedPreferences slow
        //   ChatRepo#syncDelta   → Firebase query taking too long
        // Trace.endSection() is called at the very end of this method.
        android.os.Trace.beginSection("ChatActivity#onCreate");

        // Controllers that register ActivityResultLaunchers MUST be created
        // before super.onCreate() or at least before onStart.
        mediaController = new ChatMediaController(this, this);
        mediaController.registerPickers();   // Must happen early

        super.onCreate(savedInstanceState);

        // PERF FIX: WhatsApp-style reveal — postpone the slide-in transition
        // until the first page of messages is actually laid out. Without
        // this, the window animation (right→left slide) starts the instant
        // the Activity is created, racing against message load; the user
        // sees an empty screen slide in and messages "pop" in afterward.
        // startPostponedContentTransition() (below) resumes the slide once
        // content is genuinely ready — warm-cache hit resumes almost
        // immediately; a cold load resumes as soon as Paging3's first
        // LoadState.NotLoading arrives.
        //
        // NOTE: the old version of this fired an *unconditional* 500ms
        // Handler.postDelayed as well, racing the real "data ready" signal.
        // Firebase/Paging loads routinely take longer than 500ms, so that
        // timer almost always won the race and played the slide against an
        // empty RecyclerView — messages then popped in after the animation
        // had already finished, instead of sliding in together like
        // WhatsApp. That unconditional timer is gone. The only remaining
        // fallback is a genuine safety ceiling (SAFETY_TRANSITION_TIMEOUT_MS)
        // so a chat whose load truly hangs doesn't freeze the screen forever.
        getWindow().requestFeature(android.view.Window.FEATURE_ACTIVITY_TRANSITIONS);
        getWindow().setEnterTransition(new android.transition.Slide(android.view.Gravity.END));
        getWindow().setExitTransition(new android.transition.Slide(android.view.Gravity.END));
        // PERF: activity_chat.xml's root FrameLayout has an opaque
        // match_parent android:background (surface_chat_bg), so the theme's
        // windowBackground drawn underneath it is never actually visible —
        // it's a full-screen overdraw layer painted and immediately covered
        // every single frame. Killing it here is the standard fix (same one
        // Android Studio's "Debug GPU Overdraw" tool recommends for any
        // screen with its own opaque root background).
        getWindow().setBackgroundDrawable(null);
        postponeEnterTransition();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                this::startPostponedContentTransition, SAFETY_TRANSITION_TIMEOUT_MS);

        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Left/right screen-edge se swipe karke back jaane ke liye
        // (RecyclerView ke andar "swipe to reply" se clash nahi karta —
        // sirf edge se shuru hone wala swipe hi back trigger karta hai).
        edgeSwipeBackHelper = new com.callx.app.utils.EdgeSwipeBackHelper(this, binding.getRoot());

        // Reels-tab jaisa full-screen look: status bar transparent (behind
        // content) + bottom nav bar hidden the moment chat screen khulti hai.
        com.callx.app.utils.ImmersiveModeUtils.enterImmersive(this);
        com.callx.app.utils.ImmersiveModeUtils.applyTopInsetPadding(binding.toolbar);
        com.callx.app.utils.ImmersiveModeUtils.applyImeBottomPaddingAnimated(binding.getRoot());

        // Press-and-hold mic gesture (waveform + swipe-to-cancel/lock) —
        // binding must exist first; presenceController hookup happens once
        // that controller is constructed below.
        mediaController.attachMicGesture();

        // SKELETON REMOVED (by request): belt-and-suspenders — XML default
        // is now visibility="gone" too, but force it here as well in case
        // any other code path ever flips it back on.
        if (binding.shimmerContainer != null) {
            binding.shimmerContainer.stopShimmer();
            binding.shimmerContainer.setVisibility(View.GONE);
        }

        readIntentExtras();
        if (partnerUid == null || partnerUid.isEmpty()) {
            android.util.Log.w(TAG, "partnerUid null — finishing");
            finish(); return;
        }

        // ─────────────────────────────────────────────────────────────────────
        // PERF FIX v8: "Parallel init" — UI aur Firebase listener DB ke
        // ready hone ka intezaar nahi karte.
        //
        // OLD FLOW (slow):
        //   onCreate -> ioExecutor(DB.getInstance) -> [500-2000ms wait] ->
        //   onDbReady -> RecyclerView setup -> Firebase listener -> load
        //   Result: 3-4 second blank screen
        //
        // NEW FLOW (instant):
        //   onCreate -> [IMMEDIATELY] controllers + toolbar + RecyclerView +
        //               Firebase listener (buffering) + shimmer visible
        //           -> [BACKGROUND] DB.getInstance()
        //           -> [DB READY] observePagedMessages() — Room Paging starts
        //           -> [80ms] flushPendingRoomWrites() — buffered Firebase
        //              events written to Room in one transaction
        //   Result: Firebase data appears in <500ms, shimmer only on cold boot
        //
        // Key insight: Paging3 setup aur Firebase listener DB ke bina chal
        // sakte hain. Jab DB ready hota hai tab hi Room query start hoti hai.
        // ─────────────────────────────────────────────────────────────────────

        // STEP 1 [IMMEDIATE, MAIN THREAD]: Controllers + UI init
        // Yeh sab DB-independent hain
        blockController    = new ChatBlockController(this);
        presenceController = new ChatPresenceController(this);
        recordingPreviewController = new RecordingPreviewController(this);
        mediaController.setRecordingListener(recording -> {
            // Publish voice-note recording state to Firebase so the partner
            // sees "Rahul is recording a voice message" on their screen.
            if (presenceController != null) presenceController.publishOurRecordingState(recording);
            if (!recording && recordingPreviewController != null) {
                recordingPreviewController.onOurRecordingStopped();
            }
        });
        mediaController.setAmplitudeListener(level -> {
            // Same 100ms sample already computed for our own waveform bar —
            // forwarded here, internally throttled to ~200ms before it ever
            // touches Firebase. See RecordingPreviewController perf notes.
            if (recordingPreviewController != null) {
                recordingPreviewController.onOurAmplitudeSample(level);
            }
        });
        playbackPresenceController = new ChatPlaybackPresenceController(this);
        liveTypingController = new ChatLiveTypingController(this);
        emojiBurstController = new ChatEmojiBurstController(this);
        pinController      = new ChatPinController(this);
        editHistoryController = new MessageEditHistoryController(this);
        reactionController = new ChatReactionController(this);
        pollController     = new ChatPollController(this);
        starredController  = new ChatStarredController(this);
        contactShareController  = new ChatContactShareController(
                this, this::buildOutgoing, this::pushMessage);
        locationShareController = new ChatLocationShareController(
                this, this::buildOutgoing, this::pushMessage);
        scheduledSendController = new ChatScheduledSendController(this);
        viewOnceController = new ChatViewOnceController(this);
        // NOTE: pagingAdapter.setViewOnceOpenListener(...) is wired in
        // setupPagingRecyclerView() right after pagingAdapter is constructed —
        // it CANNOT be set here because pagingAdapter is still null at this
        // point in onCreate, which was crashing ChatActivity with an NPE
        // every time the chat screen was opened.

        searchController   = new ChatSearchController(this);
        themeController    = new ChatThemeController(this);
        // mentionController is initialized later in setupMentionController() once
        // partnerUid / partnerName / partnerPhoto are known (after profile load).
        exportController   = new ChatExportController(this);
        messageSender      = new ChatMessageSender(this);
        screenshotNotifier = new ChatScreenshotNotifier(this);

        setupToolbar();
        setupProfileCard();
        themeController.applyScreenTheme();
        setupPagingRecyclerView();   // RecyclerView + adapter ready (no Room yet)
        setupInputBar();
        setupMentionController();
        setupBackPressHandler();
        setupSwipeToReply();
        setupFabBackToLatest();
        setupHeaderAutoHide();
        setupNetworkMonitor();

        // ─────────────────────────────────────────────────────────────────
        // PERF FIX: in-memory "last messages" cache — instant warm-render.
        //
        // Even with the DB-warm fast path (onDbReady same-frame call below),
        // a Room query is still a real query — cursor open, row mapping,
        // PagingSource construction. That's microseconds normally, but on a
        // loaded low-end device under jank it can still cost a visible frame
        // or two. LastMessagesCache sidesteps even that: if we already have
        // this chat's last ≤20 messages sitting in memory from a previous
        // visit (this session), submit them to the adapter RIGHT NOW, same
        // frame, with zero I/O. Room's real PagingData arrives a moment
        // later and PagingDataAdapter's DiffUtil reconciles the two lists —
        // since they're almost always identical, that reconciliation is a
        // no-op (no flicker, no jump). If the cache is stale, the diff just
        // patches the few rows that changed.
        //
        // This is a *rendering* fast path only — Room + Firebase remain the
        // only sources of truth; see onDbReady()/observePagedMessages() and
        // flushPendingRoomWrites() below for the real pipeline, which always
        // runs regardless of whether this cache hit anything.
        // ─────────────────────────────────────────────────────────────────
        boolean warmCacheHit = AppDatabase.isWarm() && LastMessagesCache.getInstance().has(chatId);
        if (warmCacheHit) {
            java.util.List<Message> cached = LastMessagesCache.getInstance().get(chatId);
            pagingAdapter.submitData(getLifecycle(), PagingData.from(withDateSeparators(cached)));
            // NOTE: do NOT call startPostponedContentTransition() here.
            // submitData() is async — AsyncPagingDataDiffer computes the
            // diff on a background dispatcher and only applies it to the
            // RecyclerView (notifyItemRangeInserted) a frame or two later.
            // Calling the preDraw-wait immediately after this line was
            // racing that diff: the very next preDraw often fired while
            // the RecyclerView was STILL EMPTY, so the slide-in resumed
            // against a blank list and messages then blinked/popped in a
            // frame later. The real trigger now lives in
            // onItemRangeInserted() below, which only fires once the diff
            // has actually landed and the RecyclerView truly has laid-out
            // content — see there for the corresponding call.
        }

        // PERF FIX: don't flash shimmer for fast/cached loads — schedule it
        // 150ms out instead of showing it unconditionally right away. See
        // shimmerShowRunnable above. Cancelled in addLoadStateListener the
        // moment real data (cached or fresh) actually arrives. Skipped
        // entirely on a warm-cache hit — there's already content on screen.
        // SKELETON REMOVED (by request): shimmer was still flashing on cold
        // loads (first-ever open this session / no warm cache). Scheduling
        // disabled entirely — cold loads now just show llEmptyChat-style
        // blank until real data arrives, same as a warm-cache load.
        // shimmerHandler.postDelayed(shimmerShowRunnable, SHIMMER_SHOW_DELAY_MS);

        // Firebase listener IMMEDIATELY lagao — DB ready hone se pehle bhi
        // messages queue mein buffer hote hain (pendingUpserts map mein).
        // Jab DB ready hoga tab flush hoga — sab ek saath render hoga.
        startRealtimeListenerEarly();

        // Presence: toolbar mein online/typing dikhane ke liye turant chahiye
        presenceController.init();

        // STEP 2 [BACKGROUND-OR-SYNC]: DB warm karo
        //
        // PERF FIX v15: Pehle yahan unconditionally ioExecutor.execute() →
        // runOnUiThread() lagaya jaata tha — chahe DB already warm ho
        // (common case: app-start prewarm already chal gaya, ya yeh dusra/
        // teesra chat screen hai is session mein). Yeh 2 thread-hops khud
        // Handler-queue delay add karte hain (har hop ek extra message-loop
        // cycle), bhale hi koi real I/O na ho — isi wajah se "2 second baad
        // message load" jaisa lag rha tha chahe Room query khud fast thi.
        //
        // AppDatabase.getInstance() jab sInstance already set hai, sirf ek
        // synchronized null-check hai (no I/O) — isliye warm case mein use
        // SEEDHA main thread pe call karna safe hai. Sirf cold case mein
        // (SQLCipher load + migrations + file I/O) background thread chahiye.
        if (AppDatabase.isWarm()) {
            // FAST PATH: same frame, synchronous — koi thread-hop nahi.
            db = AppDatabase.getInstance(this);
            onDbReady();
        } else {
            // COLD PATH: pehli baar DB build ho rahi hai — background thread
            // zaroori hai taaki SQLCipher/migration I/O main thread block na kare.
            ioExecutor.execute(() -> {
                db = AppDatabase.getInstance(this);
                runOnUiThread(this::onDbReady);
            });
        }
        // Closes TraceSectionMetric("ChatActivity#onCreate") opened at top of method.
        android.os.Trace.endSection();
    }

    private void onDbReady() {
        if (isFinishing() || isDestroyed()) return;

        // DB ready hai — ab Room-dependent cheezein start karo
        observePagedMessages();      // Paging3 Room se load karna shuru karega
        markMessagesReadOnOpen();

        // Buffered Firebase events (jo pehle se aa chuke hain) ab flush karo
        // Ek hi Room transaction mein — ek invalidation — ek render pass
        writeFlushHandler.removeCallbacks(writeFlushRunnable);
        flushPendingRoomWrites();

        restoreDraft();

        // PERF FIX: prime/refresh LastMessagesCache from Room — the actual
        // source of truth — so the warm-render fast path in onCreate() has
        // accurate data NEXT time this chat is opened. Cheap indexed query
        // (DESC+LIMIT on chatId+timestamp index, see MessageDao), runs once
        // in the background and never touches the UI thread.
        ioExecutor.execute(() -> {
            if (db == null) return;
            java.util.List<MessageEntity> entities = db.messageDao().getLastMessagesAsc(chatId, 20);
            java.util.List<Message> models = new java.util.ArrayList<>(entities.size());
            for (MessageEntity e : entities) models.add(entityToModel(e));
            LastMessagesCache.getInstance().seed(chatId, models);
        });

        // Non-critical 300ms baad
        binding.getRoot().postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            playbackPresenceController.init();
            recordingPreviewController.init();
            liveTypingController.init();
            screenshotNotifier.init();
        }, 300);

        // Low-priority 600ms baad
        binding.getRoot().postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            pinController.init();
            scheduledSendController.init();
        }, 600);

        // Background cleanup (10s baad — load se compete na kare)
        //
        // BUG FIX: pruneOldMessages() pehle turant (no delay) chal raha tha
        // har chat open pe. Yeh `messages` table pe ek DELETE query hai —
        // Room ka invalidation tracker DELETE dekh ke active PagingSource
        // (jo isi table ko observe karta hai) ko invalidate kar deta tha,
        // jisse Paging3 dobara query + re-render karta tha. Result: chat
        // already Room me cached hone ke bawajood, HAR baar khulne pe
        // messages visibly "reload" hote dikhte the. Ab yeh bhi 10s baad
        // chalega — jab tak user chat padh raha hota hai, list ko disturb
        // nahi karega.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
            ioExecutor.execute(() -> {
                if (db != null) db.messageDao().pruneOldMessages(chatId, 500);
            }), 10_000L
        );
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
            this::scheduleExpiryCleanup, 10_000L
        );
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
            ChatRepository.getInstance(this).preloadRecentChats(chatId), 3_000L
        );
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("show_unblock_joy", false)) {
            binding.getRoot().postDelayed(() -> blockController.checkAndShowUnblockJoy(), 600);
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        // Left/right edge se swipe -> back. EdgeSwipeBackHelper sirf edge-zone
        // se shuru hone wale horizontal swipe ko hi consume karta hai, isliye
        // normal taps / RecyclerView scroll / swipe-to-reply untouched rehte hain.
        if (edgeSwipeBackHelper != null && edgeSwipeBackHelper.onDispatchTouchEvent(ev)) {
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // System bars ka hide state window focus lose hone par (dialog, share
        // sheet, notification shade) reset ho sakta hai — focus wapas aate hi
        // dobara hide karo taaki status bar ki jagah gap na dikhe.
        if (hasFocus) {
            com.callx.app.utils.ImmersiveModeUtils.enterImmersive(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-assert immersive full-screen (system can restore bars after
        // returning from a picker / dialog / app-switch).
        com.callx.app.utils.ImmersiveModeUtils.enterImmersive(this);
        // Publish that we currently have THIS chat screen open & foregrounded,
        // so the partner's chat header can show "active in this chat".
        if (presenceController != null) {
            presenceController.setOurInChatScreen(true);
            presenceController.onScreenResumed();
        }
        if (recordingPreviewController != null) recordingPreviewController.onScreenResumed();
        if (screenshotNotifier != null) screenshotNotifier.onScreenResumed();

        // Grouped-media gallery (MediaViewerActivity) swipe-up-to-reply
        // handoff — see GalleryReplyBridge for why this can't be a direct
        // call from the viewer.
        if (chatId != null && pagingAdapter != null) {
            int itemIdx = GalleryReplyBridge.peekItemIndex(chatId);
            String replyMsgId = GalleryReplyBridge.consumeIfMatches(chatId);
            if (replyMsgId != null) {
                Message rm = pagingAdapter.findMessageById(replyMsgId);
                if (rm != null) startReply(rm, itemIdx);
            }
        }

        // Gallery "Forward" (whole group or selected items) handoff.
        if (chatId != null && pagingAdapter != null) {
            String[] fwdMsgIdHolder = new String[1];
            java.util.List<Integer> fwdIndices =
                    GalleryForwardBridge.consumeIfMatches(chatId, fwdMsgIdHolder);
            if (fwdIndices != null && fwdMsgIdHolder[0] != null) {
                Message gm = pagingAdapter.findMessageById(fwdMsgIdHolder[0]);
                if (gm != null) forwardGalleryMessage(gm, fwdIndices);
            }
        }

        // Gallery per-item delete/star/caption-edit handoff.
        if (chatId != null && pagingAdapter != null) {
            GalleryItemActionBridge.PendingAction pa = GalleryItemActionBridge.consumeIfMatches(chatId);
            if (pa != null && pa.messageId != null) {
                Message gm = pagingAdapter.findMessageById(pa.messageId);
                if (gm != null) applyGalleryItemAction(gm, pa);
            }
        }

        // MediaViewerActivity "Edit" action handoff — view a sent/received
        // photo, tweak it in MediaEditActivity, and resend as a new
        // message (see GalleryEditBridge).
        if (chatId != null && mediaController != null) {
            GalleryEditBridge.Pending editPending = GalleryEditBridge.consumeIfMatches(chatId);
            if (editPending != null) {
                mediaController.sendEditedMedia(Uri.parse(editPending.uri), editPending.caption, editPending.isHD);
            }
        }

        // PERF: warm the Glide cache with the last 10 image-bearing messages so
        // the first scroll feels instant — decoded Bitmaps land in Glide's LRU
        // memory cache before the user ever touches the list.
        preloadLastImageMessages();
    }

    // ─────────────────────────────────────────────────────────────────────
    // GLIDE CACHE WARM-UP
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Silent no-op Glide target used purely to warm the in-memory and on-disk
     * Glide caches before the RecyclerView asks for those images.
     *
     * Why CustomTarget and not just preload(w, h)?
     *   • preload() returns a fire-and-forget Target that Glide holds only
     *     weakly — it can be collected before the decode finishes under
     *     memory pressure, making the cache-warm guarantee unreliable.
     *   • A strong reference held in {@link #activePreloadTargets} keeps
     *     every request alive until onResourceReady() fires, at which point
     *     the decoded Bitmap is guaranteed to be in Glide's LRU memory cache.
     *     onResourceReady() intentionally does nothing — the cache entry IS
     *     the side-effect we want.
     *   • Instances are cleared (and the requests cancelled) in
     *     {@link #clearActivePreloadTargets()}, called from onPause() and
     *     onDestroy(), so they never outlive the activity.
     *
     * Dimensions are passed at construction time so each target matches the
     * actual pixel size the RecyclerView will request — Glide's memory-cache
     * key is size-specific, so a mismatch means the preloaded entry is never
     * reused by the adapter.
     */
    private static final class WarmCacheTarget extends CustomTarget<android.graphics.drawable.Drawable> {

        /** @param widthPx  exact pixel width the RecyclerView will request */
        WarmCacheTarget(int widthPx, int heightPx) {
            super(widthPx, heightPx);
        }

        @Override
        public void onResourceReady(
                @NonNull android.graphics.drawable.Drawable resource,
                @androidx.annotation.Nullable Transition<? super android.graphics.drawable.Drawable> transition) {
            // Intentionally empty — the decoded Bitmap is already stored in
            // Glide's LRU memory cache as a side-effect of this call completing.
        }

        @Override
        public void onLoadCleared(
                @androidx.annotation.Nullable android.graphics.drawable.Drawable placeholder) {
            // Intentionally empty — we hold no view reference to null out.
        }
    }

    /**
     * Reads the last ≤10 image/gif/video messages from the in-memory
     * {@link LastMessagesCache} (zero I/O — already populated on previous
     * open or from onDbReady's seed) and fires a Glide preload of each
     * message's THUMBNAIL (never full-res) via a strongly-held
     * {@link WarmCacheTarget}.
     *
     * Debounced: consecutive onResume() calls within {@link #PRELOAD_DEBOUNCE_MS}
     * (e.g. returning from a system permission dialog) are ignored.
     *
     * Pixel size (200x200) matches what MessagePagingAdapter's bubble
     * thumbnail requests at runtime so every preloaded Bitmap is reused
     * directly from Glide's LRU memory cache without a re-decode. Full
     * resolution is intentionally never preloaded here — it only loads
     * when the user taps a bubble (WhatsApp-style lazy media).
     */
    private void preloadLastImageMessages() {
        if (chatId == null || isFinishing() || isDestroyed()) return;

        // Debounce — skip if we ran less than PRELOAD_DEBOUNCE_MS ago.
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastPreloadTimeMs < PRELOAD_DEBOUNCE_MS) return;
        lastPreloadTimeMs = nowMs;

        java.util.List<Message> cached = LastMessagesCache.getInstance().get(chatId);
        if (cached == null || cached.isEmpty()) return;

        int preloaded = 0;
        // Walk newest → oldest so the most-likely-visible images are decoded
        // first (conversation is anchored at the bottom).
        for (int i = cached.size() - 1; i >= 0 && preloaded < 10; i--) {
            Message m = cached.get(i);
            if (m == null || Boolean.TRUE.equals(m.deleted)) continue;

            String url     = null;
            int    widthPx = 200;   // thumbnail size, not bubble/full size
            int    htPx    = 200;

            if ("image".equals(m.type) || "gif".equals(m.type) || "sticker".equals(m.type)) {
                // PERF FIX (WhatsApp-style lazy media): preload the THUMBNAIL
                // only, not the full-resolution mediaUrl. This used to load
                // m.mediaUrl at 720x720 for up to 10 images EVERY time the
                // chat screen opened (onResume) — a real full-quality
                // download per image, even for media the user never tapped.
                // That's why opening the chat looked like it was
                // "downloading everything," and tapping an image afterward
                // looked like "nothing downloads" — it was already fetched.
                // Full resolution now only ever loads on demand, when the
                // user actually taps a bubble (see MessagePagingAdapter's
                // image click → showImageActionSheet/MediaViewerActivity).
                String rawFallback = (m.mediaUrl != null && !m.mediaUrl.isEmpty()) ? m.mediaUrl : m.imageUrl;
                url = (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                        ? m.thumbnailUrl
                        // No real thumbnailUrl — derive a lightweight Cloudinary
                        // transform URL instead of preloading the raw full-res
                        // asset (see CloudinaryUploader.deriveThumbUrl).
                        : com.callx.app.utils.CloudinaryUploader.deriveThumbUrl(rawFallback, 200);
                widthPx = 200; htPx = 200;
            } else if ("video".equals(m.type)) {
                // For video we only preload the thumbnail — full assets are
                // streamed on demand and far too large to cache eagerly.
                url = m.thumbnailUrl;
                widthPx = 200; htPx = 200;
            }

            if (url == null || url.isEmpty()) continue;

            // Build a strongly-referenced target so Glide cannot collect it
            // before the decode finishes.  Glide.with(Activity) is main-thread-
            // safe here because onResume() always runs on the main thread.
            WarmCacheTarget target = new WarmCacheTarget(widthPx, htPx);
            activePreloadTargets.add(target);

            Glide.with(this)
                    .load(url)
                    .override(widthPx, htPx)
                    .into(target);

            preloaded++;
        }
    }

    /**
     * Cancels every in-flight Glide preload and releases the strong
     * references so the targets can be collected normally.
     * Called from {@link #onPause()} and {@link #onDestroy()}.
     */
    private void clearActivePreloadTargets() {
        for (WarmCacheTarget t : activePreloadTargets) {
            Glide.with(this).clear(t);
        }
        activePreloadTargets.clear();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Cancel any in-flight Glide preloads so we don't decode images for a
        // chat the user just left.  Clearing the strong references also lets
        // Glide GC the targets normally.
        clearActivePreloadTargets();
        // WhatsApp-style: persist scroll position + last-seen-ts so we can
        // intelligently restore (or jump to first unread) on re-open.
        saveScrollState();
        saveDraft();
        if (presenceController != null) {
            presenceController.clearOurTypingStatus();
            // We've left this chat screen (backgrounded app, or navigated to
            // another screen) — partner should no longer see "active in this chat".
            presenceController.setOurInChatScreen(false);
            presenceController.clearViewingMessage();
            // Stop the partner's typing-dots bounce loop — no point animating
            // a strip nobody can see while we're backgrounded.
            presenceController.onScreenPaused();
            // If we left mid-recording, clear the recording badge on the partner's screen.
            if (isRecording) {
                presenceController.publishOurRecordingState(false);
                if (recordingPreviewController != null) recordingPreviewController.onOurRecordingStopped();
            }
        }
        if (recordingPreviewController != null) recordingPreviewController.onScreenPaused();
        if (screenshotNotifier != null) screenshotNotifier.onScreenPaused();
        if (liveTypingController != null) liveTypingController.clearOurPreview();
        typingHandler.removeCallbacks(stopTypingRunnable);
        // Feature 1: auto-close view-once dialog when app goes to background
        if (activeViewOnceDialog != null && activeViewOnceDialog.isShowing()) {
            activeViewOnceDialog.dismiss(); // triggers onDismissListener → doCleanupAndDelete
        }
        activeViewOnceDialog = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveDraft();
        // Cancel any remaining Glide preloads before the activity is torn down.
        clearActivePreloadTargets();
        shimmerHandler.removeCallbacks(shimmerShowRunnable);

        if (messagesRef != null && messageListener != null)
            messagesRef.removeEventListener(messageListener);
        if (messagesRef != null && statusSyncListener != null)
            messagesRef.removeEventListener(statusSyncListener);

        // PERF FIX: flush any buffered Firebase→Room writes immediately
        // instead of losing them if the debounce window hadn't fired yet.
        writeFlushHandler.removeCallbacks(writeFlushRunnable);
        flushPendingRoomWrites();

        typingHandler.removeCallbacks(stopTypingRunnable);
        if (expiryRunnable != null) expiryHandler.removeCallbacks(expiryRunnable);

        if (connMgr != null && netCallback != null) {
            try { connMgr.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
        if (replyController != null) replyController.release();

        if (presenceController != null) presenceController.release();
        if (playbackPresenceController != null) playbackPresenceController.release();
        if (recordingPreviewController != null) recordingPreviewController.release();
        if (liveTypingController != null) liveTypingController.destroy();
        if (emojiBurstController != null) emojiBurstController.release();
        if (blockController    != null) blockController.release();
        if (scheduledSendController != null) scheduledSendController.release();
        if (viewOnceController != null) viewOnceController.release();
        if (screenshotNotifier != null) screenshotNotifier.release();

        // Feature: clean up search highlights + mention watcher
        if (searchController  != null) searchController.onDestroy();
        if (mentionController != null) mentionController.onDestroy();
    }

    // ─────────────────────────────────────────────────────────────────────
    // ChatActivityDelegate IMPLEMENTATION
    // ─────────────────────────────────────────────────────────────────────

    @Override public ActivityChatBinding getBinding()        { return binding; }
    @Override public String getChatId()                      { return chatId; }
    @Override public String getPartnerUid()                  { return partnerUid; }
    @Override public String getPartnerName()                 { return partnerName; }
    @Override public String getPartnerPhoto()                { return partnerPhoto; }
    @Override public String getPartnerThumb()                { return partnerThumb; }
    @Override public String getCurrentUid()                  { return currentUid; }
    @Override public String getCurrentName()                 { return currentName; }
    @Override public AppDatabase getDb()                     { return db; }
    @Override public Executor getIoExecutor()                { return ioExecutor; }
    @Override public DatabaseReference getMessagesRef()      { return messagesRef; }
    @Override public boolean isMuted()                       { return isMuted; }
    @Override public void setMuted(boolean v)                { isMuted = v; }
    @Override public boolean isBlocked()                     { return isBlocked; }
    @Override public void setBlocked(boolean v)              { isBlocked = v; }
    @Override public boolean isPartnerPermaBlockedMe()       { return partnerPermaBlockedMe; }
    @Override public void setPartnerPermaBlockedMe(boolean v){ partnerPermaBlockedMe = v; }
    @Override public boolean isIPermaBlockedPartner()        { return iPermaBlockedPartner; }
    @Override public void setIPermaBlockedPartner(boolean v) { iPermaBlockedPartner = v; }
    @Override public boolean isRecording()                   { return isRecording; }
    @Override public void setRecording(boolean v)            { isRecording = v; }
    @Override public void runOnMain(Runnable r)              { runOnUiThread(r); }
    @Override public void showToast(String msg)              { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    @Override public void invalidateMenu()                   { invalidateOptionsMenu(); }
    @Override public android.app.Activity getActivity()      { return this; }
    @Override public androidx.fragment.app.FragmentManager getSupportFragmentManager() {
        return super.getSupportFragmentManager();
    }
    @Override public MessagePagingAdapter getPagingAdapter() { return pagingAdapter; }
    @Override public void refreshScreenTheme()               { themeController.applyScreenTheme(); }
    @Override public void refreshWallpaper()                 { themeController.applyWallpaper(); }
    @Override public void launchWallpaperPicker()            { mediaController.launchWallpaperPicker(); }
    @Override public void launchPollCreator()                { pollController.showCreatePollDialog(); }
    @Override public void launchContactSharePicker()         { contactShareController.launch(); }
    @Override public void launchLocationSharePicker()        { locationShareController.launch(); }
    @Override public void navigateToOriginal(String messageId) { navigateToOriginalMsg(messageId, null); }
    // ChatSearchController.SearchDelegate — search jumps to a match the same
    // way reply-tap jumps to the original: navigateToOriginalMsg() already
    // handles "not currently loaded" via the Room + approximate-position
    // fallback (see its own doc comment), so search gets that for free.
    @Override public void navigateToMessage(String messageId)  { navigateToOriginalMsg(messageId, null); }
    @Override public String getCurrentReplyTargetId() {
        if (replyingTo == null) return null;
        return replyingTo.messageId != null ? replyingTo.messageId : replyingTo.id;
    }

    @Override
    public boolean isOnline() {
        if (connMgr == null) return true;
        Network active = connMgr.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = connMgr.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override
    public Message buildOutgoing() {
        Message m    = new Message();
        m.senderId   = currentUid;
        m.senderName = currentName;
        m.timestamp  = System.currentTimeMillis();
        m.status     = "sent";
        if (replyingTo != null) {
            ReplyDataMapper.applyReplyFields(m, replyingTo, currentUid);
        }
        return m;
    }

    @Override
    public void pushMessage(Message m, String previewText) {
        // Feature 13: View Once — apply tag if toggle is ON, then auto-reset
        // (one-shot per message, same UX pattern as WhatsApp's view-once camera).
        if (isViewOnceModeOn) {
            com.callx.app.conversation.controllers.ChatViewOnceController.tagMessageAsViewOnce(m);
            // Feature 2: store expiry duration on the message — ChatMessageSender
            // schedules the WorkManager job after the Firebase key is assigned.
            // -1 = "Expire with View Once" (no timer — delete on open only, classic flow)
            // >0  = timer duration — set expiresAt; ChatMessageSender schedules WorkManager job
            if (selectedViewOnceExpiryMs > 0L) {
                m.viewOnceExpiresAt = System.currentTimeMillis() + selectedViewOnceExpiryMs;
            }
            // -1 and 0 both leave viewOnceExpiresAt null → no timer scheduled
            selectedViewOnceExpiryMs = 0L;
            setViewOnceMode(false);
            // HIDE real content from chat list — replace preview with generic label.
            previewText = "🔒 View Once";
        }
        messageSender.pushMessage(m, previewText);
    }

    // ─────────────────────────────────────────────────────────────────────
    // POLLS — moved to ChatPollController (showCreatePollDialog / castVote /
    // toggleClosed). launchPollCreator() and the onPollVote/onPollToggleClose
    // action-sheet callbacks now delegate straight to it.
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public String insertLocalPendingMedia(Message m) {
        return messageSender.insertLocalPendingMedia(m);
    }

    @Override
    public void finalizeMediaMessage(Message m, String previewText) {
        messageSender.finalizeMediaMessage(m, previewText);
    }

    @Override
    public void markMediaFailed(String messageId) {
        messageSender.markMediaFailed(messageId);
    }

    @Override
    public void firebasePushMessage(Message m, String key, String previewText) {
        messageSender.firebasePushMessage(m, key, previewText);
    }

    @Override
    public void clearReply() {
        replyingTo = null;
        if (replyController != null) replyController.cancel();
        // Don't wait for the next keystroke — replyingTo just became null,
        // so if we're still typing, immediately drop the highlight on
        // whatever bubble was being replied to.
        if (presenceController != null) presenceController.publishTypingReplyTarget();
        if (binding.llReplyBar == null) return;
        binding.llReplyBar.setVisibility(View.GONE);
        binding.llReplyBar.setAlpha(1f);
        binding.llReplyBar.setTranslationY(0f);
        if (binding.ivReplyBarThumb != null) binding.ivReplyBarThumb.setVisibility(View.GONE);
    }

    @Override
    public void startReply(Message m) { startReply(m, -1); }

    /** @param galleryItemIndex which mediaItems[] entry was on-screen when the
     *  swipe-up happened inside MediaViewerActivity's gallery, or -1 if this
     *  reply wasn't triggered from there / isn't item-specific. */
    public void startReply(Message m, int galleryItemIndex) {
        pendingReplyItemIndex = galleryItemIndex;
        if (replyController != null) {
            replyController.onSwipeReply(m);
        } else {
            activateReplyDirect(m);
        }
    }

    @Override
    public void activateReplyDirect(Message m) {
        replyingTo = m;
        if (binding.llReplyBar == null) return;

        // Item-specific gallery reply: if the swipe-up happened on a specific
        // image/video inside a multi_media group, quote THAT item instead of
        // the generic group preview. One-shot — consumed and reset here.
        int itemIndex = pendingReplyItemIndex;
        pendingReplyItemIndex = -1;
        java.util.Map<String, Object> galleryItem = null;
        if (itemIndex >= 0 && "multi_media".equals(m.type)
                && m.mediaItems != null && itemIndex < m.mediaItems.size()) {
            galleryItem = m.mediaItems.get(itemIndex);
        }

        String senderName = (currentUid != null && currentUid.equals(m.senderId))
                ? "You" : (m.senderName != null ? m.senderName : "");
        String preview;
        if (Boolean.TRUE.equals(m.deleted)) {
            preview = "\uD83D\uDEAB  Original message unavailable";
        } else if (galleryItem != null) {
            Object itemCaption = galleryItem.get("caption");
            boolean isVideo = "video".equals(galleryItem.get("mediaType"));
            preview = (itemCaption instanceof String && !((String) itemCaption).isEmpty())
                    ? (String) itemCaption
                    : (isVideo ? "\uD83C\uDFAC Video" : "\uD83D\uDCF7 Photo");
        } else if (m.text != null && !m.text.isEmpty()) {
            preview = m.text;
        } else {
            preview = buildTypePreviewLocal(m);
        }

        if (binding.tvReplyBarName != null) binding.tvReplyBarName.setText(senderName);
        if (binding.tvReplyBarText != null) binding.tvReplyBarText.setText(preview);

        if (binding.ivReplyBarThumb != null) {
            String thumbUrl = null;
            if (galleryItem != null) {
                Object u  = galleryItem.get("thumbUrl");
                Object u2 = galleryItem.get("url");
                thumbUrl = (u instanceof String && !((String) u).isEmpty()) ? (String) u
                        : (u2 instanceof String ? (String) u2 : null);
            } else if ("image".equals(m.type)) thumbUrl = m.mediaUrl;
            else if ("video".equals(m.type)) thumbUrl = m.thumbnailUrl;
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                binding.ivReplyBarThumb.setVisibility(View.VISIBLE);
                Glide.with(this).load(thumbUrl).centerCrop().override(720, 720).into(binding.ivReplyBarThumb);
            } else {
                binding.ivReplyBarThumb.setVisibility(View.GONE);
            }
        }

        binding.llReplyBar.setAlpha(1f);
        binding.llReplyBar.setTranslationY(0f);
        binding.llReplyBar.setVisibility(View.VISIBLE);
        binding.etMessage.requestFocus();
        // WhatsApp-style: swiping to reply should pop the keyboard back up
        // immediately, exactly like tapping the input field would — plain
        // requestFocus() only sets focus and does nothing if the keyboard
        // was already dismissed (e.g. user was scrolling with keyboard
        // closed when they swiped). Force it open on the next frame so the
        // reply bar's requestFocus() has actually taken effect first.
        binding.etMessage.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(binding.etMessage, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
        });
        ReplyAnalyticsTracker.get().onSwipeTriggered(this);
    }

    // ─────────────────────────────────────────────────────────────────────
    // DRAFT
    // ─────────────────────────────────────────────────────────────────────

    private void saveDraft() {
        if (db == null || chatId == null || binding == null) return;
        String draftText = binding.etMessage.getText() != null
                ? binding.etMessage.getText().toString() : "";
        ioExecutor.execute(() ->
                db.chatDao().saveDraft(chatId, draftText));
    }

    private void restoreDraft() {
        if (db == null || chatId == null) return;
        ioExecutor.execute(() -> {
            String draft = db.chatDao().getDraft(chatId);
            if (draft != null && !draft.isEmpty()) {
                runOnUiThread(() -> {
                    if (binding != null && binding.etMessage != null) {
                        binding.etMessage.setText(draft);
                        binding.etMessage.setSelection(draft.length());
                    }
                });
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // EXPIRY CLEANUP (Disappearing messages)
    // ─────────────────────────────────────────────────────────────────────

    private void scheduleExpiryCleanup() {
        expiryRunnable = new Runnable() {
            @Override public void run() {
                if (db == null) return;
                ioExecutor.execute(() -> {
                    int deleted = db.messageDao().deleteExpiredMessages(System.currentTimeMillis());
                    if (deleted > 0) deleteExpiredFromFirebase();
                });
                expiryHandler.postDelayed(this, 30_000L);
            }
        };
        expiryHandler.post(expiryRunnable);
    }

    private void deleteExpiredFromFirebase() {
        if (messagesRef == null || chatId == null) return;
        long nowMs = System.currentTimeMillis();
        messagesRef.orderByChild("expiresAt").endAt(nowMs)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        for (DataSnapshot child : snap.getChildren()) {
                            Object raw = child.child("expiresAt").getValue();
                            if (raw instanceof Long) {
                                long expiresAt = (Long) raw;
                                if (expiresAt > 0 && expiresAt <= nowMs) child.getRef().removeValue();
                            }
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    // ─────────────────────────────────────────────────────────────────────
    // NETWORK MONITORING
    // ─────────────────────────────────────────────────────────────────────

    private void setupNetworkMonitor() {
        connMgr = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connMgr == null) return;

        boolean online = isOnline();
        updateOfflineBanner(!online);
        messageSender.updateSendButtonState(online);

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                runOnUiThread(() -> {
                    updateOfflineBanner(false);
                    messageSender.updateSendButtonState(true);
                    messageSender.retryPendingMessages();
                // Feature 13: flush any offline view-once deletes
                if (viewOnceController != null) viewOnceController.flushPendingDeletes();
                    ChatRepository.getInstance(getApplicationContext()).syncMessagesDelta(chatId);
                });
            }
            @Override public void onLost(Network n) {
                runOnUiThread(() -> {
                    updateOfflineBanner(true);
                    messageSender.updateSendButtonState(false);
                });
            }
        };
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            connMgr.registerNetworkCallback(req, netCallback);
        } catch (Exception ignored) {}
    }

    private void updateOfflineBanner(boolean offline) {
        if (binding == null) return;
        android.widget.TextView banner = binding.getRoot().findViewById(R.id.tv_offline_banner);
        if (banner != null) banner.setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // INTENT EXTRAS
    // ─────────────────────────────────────────────────────────────────────

    private void readIntentExtras() {
        Intent i    = getIntent();
        partnerUid  = i.getStringExtra("partnerUid");
        partnerName = i.getStringExtra("partnerName");
        partnerPhoto= i.getStringExtra("partnerPhoto");
        partnerThumb= i.getStringExtra("partnerThumb");
        currentName = i.getStringExtra("currentName");

        // HUN-FIX: currentName is only ever set via this intent extra, but
        // notification taps (message notif, reaction notif, etc.) never pass
        // it — so if the user reacts to a message right after opening chat
        // from a notification, PushNotify.notifyMessageReaction() sends an
        // empty fromName, and the OTHER person's reaction notification shows
        // "Someone" instead of this user's real name. Fall back to the
        // FirebaseAuth display name (set at signup/profile update) so
        // currentName is never silently empty.
        if (currentName == null || currentName.trim().isEmpty()) {
            com.google.firebase.auth.FirebaseUser selfUser =
                    FirebaseAuth.getInstance().getCurrentUser();
            if (selfUser != null && selfUser.getDisplayName() != null) {
                currentName = selfUser.getDisplayName();
            }
        }

        // Feature 5: notification tap passes notif_msg_id so we can scroll to the
        // message. We intentionally do NOT auto-open view-once content from here —
        // the user must manually tap the bubble to open view-once messages.
        String notifMsgId = i.getStringExtra("notif_msg_id");
        if (notifMsgId != null && !notifMsgId.isEmpty()) {
            // Scroll to that message after adapter loads, without opening media
            binding.getRoot().postDelayed(() -> scrollToMessageById(notifMsgId), 800);
        }

        com.google.firebase.auth.FirebaseUser fu = FirebaseAuth.getInstance().getCurrentUser();
        currentUid = fu != null ? fu.getUid() : "";
        chatId     = buildChatId(currentUid, partnerUid);
        messagesRef= FirebaseUtils.getMessagesRef(chatId);

        // Forward payload handling
        String fwdText  = i.getStringExtra("forwardText");
        String fwdType  = i.getStringExtra("forwardType");
        String fwdMedia = i.getStringExtra("forwardMedia");
        ArrayList<String> fwdTexts     = i.getStringArrayListExtra("forwardTexts");
        ArrayList<String> fwdTypes     = i.getStringArrayListExtra("forwardTypes");
        ArrayList<String> fwdMedias    = i.getStringArrayListExtra("forwardMedias");
        ArrayList<String> fwdFileNames = i.getStringArrayListExtra("forwardFileNames");
        ArrayList<String> fwdMediaItemsJsonList = i.getStringArrayListExtra("forwardMediaItemsJsonList");
        ArrayList<String> fwdCaptionsList       = i.getStringArrayListExtra("forwardCaptionsList");

        if (fwdTexts != null && !fwdTexts.isEmpty()) {
            binding.getRoot().post(() -> {
                for (int idx = 0; idx < fwdTexts.size(); idx++) {
                    final int fi = idx;
                    binding.getRoot().postDelayed(() -> {
                        String t  = fwdTexts.get(fi);
                        String tp = fwdTypes.get(fi);
                        String mu = fwdMedias.get(fi);
                        // #2 fix — a multi_media (grouped) message inside a
                        // multi-select forward carries its mediaItems here
                        // instead of just mu (the first item's URL); rebuild
                        // the full gallery instead of falling through to the
                        // generic single-media branch below.
                        String groupJson = fwdMediaItemsJsonList != null && fi < fwdMediaItemsJsonList.size()
                                ? fwdMediaItemsJsonList.get(fi) : "";
                        Message m2 = buildOutgoing();
                        m2.forwardedFrom = partnerName;
                        if ("multi_media".equals(tp) && groupJson != null && !groupJson.isEmpty()) {
                            m2.type = "multi_media";
                            m2.mediaItems = com.callx.app.utils.MediaItemsJsonUtil.mediaItemsFromJson(groupJson);
                            String cap = fwdCaptionsList != null && fi < fwdCaptionsList.size()
                                    ? fwdCaptionsList.get(fi) : null;
                            m2.caption = (cap != null && !cap.isEmpty()) ? cap : null;
                            if (m2.caption != null) m2.text = m2.caption;
                            Object firstUrl = !m2.mediaItems.isEmpty() ? m2.mediaItems.get(0).get("url") : null;
                            if (firstUrl instanceof String) m2.mediaUrl = (String) firstUrl;
                            pushMessage(m2, "\uD83D\uDCF7 Photos (forwarded)");
                        } else if ("text".equals(tp) || tp == null) {
                            m2.type = "text"; m2.text = t;
                            pushMessage(m2, t != null ? t : "");
                        } else if (mu != null && !mu.isEmpty()) {
                            m2.type = tp; m2.mediaUrl = mu;
                            m2.imageUrl = "image".equals(tp) ? mu : null;
                            m2.fileName = fwdFileNames != null && fi < fwdFileNames.size() ? fwdFileNames.get(fi) : null;
                            String preview = "image".equals(tp) ? "\uD83D\uDCF7 Photo (forwarded)"
                                           : "video".equals(tp) ? "\uD83C\uDFAC Video (forwarded)"
                                           : "audio".equals(tp) ? "\uD83C\uDFA4 Voice (forwarded)"
                                           : "\uD83D\uDCCE File (forwarded)";
                            pushMessage(m2, preview);
                        }
                    }, idx * 150L);
                }
            });
        } else if (fwdText != null && !fwdText.isEmpty() && "text".equals(fwdType)) {
            if (binding != null && binding.etMessage != null) {
                binding.etMessage.post(() -> {
                    binding.etMessage.setText(fwdText);
                    binding.etMessage.setSelection(fwdText.length());
                });
            }
        } else if (fwdText != null && !fwdText.isEmpty() && "reel_share".equals(fwdType)) {
            // Reel shared from Instagram via ContactsActivity — OR forwarded reel card
            final String sharedText    = fwdText;
            final String fwdReelId     = i.getStringExtra("forwardReelId");
            final String fwdReelUrl    = i.getStringExtra("forwardReelShareUrl");
            final String fwdReelThumb  = i.getStringExtra("forwardReelShareThumb");
            final String fwdReelCap    = i.getStringExtra("forwardReelShareCaption");
            final String fwdReelUser   = i.getStringExtra("forwardReelShareUsername");
            final String fwdReelPhoto  = i.getStringExtra("forwardReelShareOwnerPhoto");

            boolean isForwardedCard = (fwdReelId != null && !fwdReelId.isEmpty())
                    || (fwdReelUrl != null && !fwdReelUrl.isEmpty());

            if (isForwardedCard) {
                // Full reel card forward — all fields available, build card directly
                binding.getRoot().post(() -> {
                    com.callx.app.models.Message msg = buildOutgoing();
                    msg.type               = "reel_share";
                    msg.reelId             = fwdReelId;
                    msg.reelShareUrl       = fwdReelUrl;
                    msg.reelShareThumb     = fwdReelThumb;
                    msg.reelShareCaption   = fwdReelCap;
                    msg.reelShareUsername  = fwdReelUser;
                    msg.reelShareOwnerPhoto= fwdReelPhoto;
                    msg.text               = fwdReelUrl != null ? fwdReelUrl : sharedText;
                    msg.forwardedFrom      = partnerName;
                    pushMessage(msg, "📹 Reel");
                });
            } else {
                // External Instagram share — extract URL from text
                binding.getRoot().post(() -> {
                    String url = extractFirstUrl(sharedText);
                    if (url == null) return;
                    java.util.regex.Matcher um = java.util.regex.Pattern
                            .compile("@([A-Za-z0-9._]+)").matcher(sharedText);
                    String username = um.find() ? um.group(1) : "";
                    String caption  = sharedText.replace(url, "").trim();
                    com.callx.app.models.Message msg = buildOutgoing();
                    msg.type              = "reel_share";
                    msg.reelShareUrl      = url;
                    msg.reelShareUsername = username;
                    msg.reelShareCaption  = caption.isEmpty() ? null : caption;
                    msg.reelShareThumb    = null;
                    msg.text              = url;
                    pushMessage(msg, "📹 Reel");
                });
            }
        } else if ("multi_media".equals(fwdType)
                && i.getStringExtra("forwardMediaItemsJson") != null
                && !i.getStringExtra("forwardMediaItemsJson").isEmpty()) {
            // #1 fix — must be checked BEFORE the generic fwdMedia branch
            // below: a multi_media message also carries m.mediaUrl as a
            // fallback (first item's url, set by ChatMediaController), so
            // if this check ran after the generic branch it would always
            // win first and silently downgrade the forward into a single
            // image with no mediaItems (= the "doesn't look like grouped
            // media" bug).
            String fwdMediaItemsJson = i.getStringExtra("forwardMediaItemsJson");
            String fwdCaption        = i.getStringExtra("forwardCaption");
            binding.getRoot().post(() -> {
                Message m = buildOutgoing();
                m.type = "multi_media";
                m.mediaItems = com.callx.app.utils.MediaItemsJsonUtil.mediaItemsFromJson(fwdMediaItemsJson);
                m.caption = fwdCaption;
                if (fwdCaption != null && !fwdCaption.isEmpty()) m.text = fwdCaption;
                Object firstUrl = !m.mediaItems.isEmpty() ? m.mediaItems.get(0).get("url") : null;
                if (firstUrl instanceof String) m.mediaUrl = (String) firstUrl;
                m.forwardedFrom = partnerName;
                pushMessage(m, "\uD83D\uDCF7 Photos (forwarded)");
            });
        } else if (fwdMedia != null && !fwdMedia.isEmpty()) {
            binding.getRoot().post(() -> {
                Message m  = buildOutgoing();
                m.type     = fwdType != null ? fwdType : "image";
                m.mediaUrl = fwdMedia;
                m.imageUrl = "image".equals(m.type) ? fwdMedia : null;
                m.forwardedFrom = partnerName;
                String preview = "image".equals(m.type) ? "\uD83D\uDCF7 Photo (forwarded)"
                               : "video".equals(m.type) ? "\uD83C\uDFAC Video (forwarded)"
                               : "audio".equals(m.type) ? "\uD83C\uDFA4 Voice (forwarded)"
                               : "\uD83D\uDCCE File (forwarded)";
                pushMessage(m, preview);
            });
        }

        // ── Instagram reel share intent handling ──────────────────────────
        // When user shares a reel from Instagram, it fires ACTION_SEND with
        // text/plain containing the reel URL. We detect this and send it as
        // a rich "reel_share" message type instead of a plain text link.
        String action = i.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            String sharedText = i.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && !sharedText.isEmpty()) {
                handleIncomingShareText(sharedText);
            }
        }
    }

    private static String buildChatId(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
    }

    /**
     * Called when ChatActivity is launched via a share intent (ACTION_SEND, text/plain).
     * Detects Instagram reel URLs and sends them as a rich "reel_share" message.
     * All other shared URLs/text fall back to pre-filling the input box.
     */
    private void handleIncomingShareText(String sharedText) {
        // Extract first URL from the shared text
        String url = extractFirstUrl(sharedText);
        if (url == null) {
            // No URL — just pre-fill the compose box
            if (binding != null && binding.etMessage != null) {
                binding.etMessage.post(() -> {
                    binding.etMessage.setText(sharedText);
                    binding.etMessage.setSelection(sharedText.length());
                });
            }
            return;
        }

        // Detect Instagram reel URL patterns:
        // https://www.instagram.com/reel/XYZ
        // https://instagram.com/reel/XYZ
        // https://www.instagram.com/p/XYZ  (some reels share with /p/)
        boolean isInstagramReel = url.contains("instagram.com/reel/")
                || url.contains("instagram.com/reels/")
                || (url.contains("instagram.com/p/") && sharedText.toLowerCase().contains("reel"));

        if (isInstagramReel) {
            // Extract @username if present in the shared text (Instagram often includes it)
            String username = "";
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("@([A-Za-z0-9._]+)").matcher(sharedText);
            if (m.find()) username = m.group(1);

            // Use the rest of the text (minus the URL) as caption
            String caption = sharedText.replace(url, "").trim();

            final String finalUsername = username;
            final String finalCaption  = caption;
            final String finalUrl      = url;

            binding.getRoot().post(() -> {
                com.callx.app.models.Message msg = buildOutgoing();
                msg.type               = "reel_share";
                msg.reelShareUrl       = finalUrl;
                msg.reelShareUsername  = finalUsername;
                msg.reelShareCaption   = finalCaption.isEmpty() ? null : finalCaption;
                msg.reelShareThumb     = null; // no thumb from share intent; card still looks good
                msg.text               = finalUrl; // fallback text for notifications
                pushMessage(msg, "📹 Reel");
            });
        } else {
            // Non-Instagram URL — pre-fill compose box (user can send manually)
            if (binding != null && binding.etMessage != null) {
                binding.etMessage.post(() -> {
                    binding.etMessage.setText(sharedText);
                    binding.etMessage.setSelection(sharedText.length());
                });
            }
        }
    }

    /** Extracts the first http/https URL from a string, or null if none found. */
    private static String extractFirstUrl(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s]+").matcher(text);
        return m.find() ? m.group() : null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // TOOLBAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        binding.btnBack.setOnClickListener(v -> {
            if (pagingAdapter != null && pagingAdapter.isInMultiSelectMode()) {
                pagingAdapter.exitMultiSelectMode();
                hideMultiSelectBar();
            } else {
                finish();
            }
        });

        if (partnerName != null) binding.tvPartnerName.setText(partnerName);
        binding.ivPartnerAvatar.setOnClickListener(v -> openAvatarZoom());

        String headerAvatar = (partnerThumb != null && !partnerThumb.isEmpty()) ? partnerThumb : partnerPhoto;
        if (headerAvatar != null && !headerAvatar.isEmpty()) {
            Glide.with(this).load(headerAvatar).placeholder(R.drawable.ic_person).circleCrop()
                    .override(96, 96)
                    .into(binding.ivPartnerAvatar);
        } else {
            FirebaseUtils.getUserRef(partnerUid).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    String thumb = s.child("thumbUrl").getValue(String.class);
                    String photo = s.child("photoUrl").getValue(String.class);
                    String url = (thumb != null && !thumb.isEmpty()) ? thumb : photo;
                    if (url != null && !url.isEmpty()) {
                        if (photo != null) partnerPhoto = photo;
                        if (thumb != null) partnerThumb = thumb;
                        Glide.with(ChatActivity.this).load(url).placeholder(R.drawable.ic_person)
                                .override(96, 96)
                                .circleCrop().into(binding.ivPartnerAvatar);
                    }
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
        }

        binding.btnToolbarVoiceCall.setOnClickListener(v -> startCall(false));
        binding.btnToolbarVideoCall.setOnClickListener(v -> startCall(true));

        // Reel button
        binding.btnToolbarReel.setOnClickListener(v -> {
            if (partnerUid == null || partnerUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.profile.UserReelsActivity");
                Intent in = new Intent(this, cls);
                in.putExtra("uid",   partnerUid);
                in.putExtra("name",  partnerName != null ? partnerName : "");
                in.putExtra("photo", partnerPhoto != null ? partnerPhoto : "");
                startActivity(in);
            } catch (ClassNotFoundException e) {
                Toast.makeText(this, "Reels profile not available", Toast.LENGTH_SHORT).show();
            }
        });

        // Hanging reel animation removed for performance

        // X profile
        binding.btnToolbarX.setOnClickListener(v -> {
            if (partnerUid == null || partnerUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.profile.XProfileSheet");
                java.lang.reflect.Method method = cls.getMethod("showProfile",
                        androidx.fragment.app.FragmentManager.class, String.class);
                method.invoke(null, getSupportFragmentManager(), partnerUid);
            } catch (Exception e) {
                Toast.makeText(this, "X profile not available", Toast.LENGTH_SHORT).show();
            }
        });

        // YouTube channel
        binding.btnToolbarYoutube.setOnClickListener(v -> {
            if (partnerUid == null || partnerUid.isEmpty()) return;
            try {
                Class<?> cls = Class.forName("com.callx.app.channel.YouTubeChannelActivity");
                Intent in = new Intent(this, cls);
                in.putExtra("uid",  partnerUid);
                in.putExtra("name", partnerName != null ? partnerName : "");
                startActivity(in);
            } catch (ClassNotFoundException e) {
                Toast.makeText(this, "YouTube channel not available", Toast.LENGTH_SHORT).show();
            }
        });

        binding.btnMoreOptions.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, binding.btnMoreOptions);
            popup.getMenuInflater().inflate(com.callx.app.chat.R.menu.chat_menu, popup.getMenu());
            android.view.MenuItem muteItem = popup.getMenu().findItem(R.id.action_mute);
            if (muteItem != null) muteItem.setTitle(isMuted ? "\uD83D\uDD14 Unmute" : "\uD83D\uDD15 Mute");
            popup.setOnMenuItemClickListener(item -> onOptionsItemSelected(item));
            popup.show();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // PROFILE CARD — Instagram-style card (avatar, name, Reels/X/YouTube
    // stats, Subscribe, opt-in "View Community") below the header capsule.
    // ─────────────────────────────────────────────────────────────────────

    private com.callx.app.conversation.ChatProfileCardBinder profileCardBinder;

    private void setupProfileCard() {
        profileCardBinder = new com.callx.app.conversation.ChatProfileCardBinder(this,
                com.callx.app.chat.databinding.LayoutChatProfileCardBinding.bind(
                        binding.includeProfileCard.getRoot()));
        profileCardBinder.bind(partnerUid, partnerName, partnerPhoto, partnerThumb);

        View.OnClickListener toggle = v -> profileCardBinder.toggleExpanded();
        binding.ivPartnerAvatar.setOnClickListener(v -> { openAvatarZoom(); toggle.onClick(v); });
        View nameRow = findViewById(R.id.ll_partner_name_row);
        if (nameRow != null) nameRow.setOnClickListener(toggle);
    }

    private void startCall(boolean isVideo) {
        Intent i = new Intent().setClassName(this, "com.callx.app.call.CallActivity");
        i.putExtra("partnerUid",   partnerUid);
        i.putExtra("partnerName",  partnerName);
        i.putExtra("partnerPhoto", partnerPhoto);
        i.putExtra("partnerThumb", partnerThumb);
        i.putExtra("isCaller",     true);
        i.putExtra("video",        isVideo);
        startActivity(i);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PAGING 3 — RecyclerView + Adapter
    // ─────────────────────────────────────────────────────────────────────

    private void setupPagingRecyclerView() {
        pagingAdapter = new MessagePagingAdapter(currentUid, false);
        pagingAdapter.setChatId(chatId);

        // Feature 13: View Once — wire adapter listener to controller + viewer launch
        // (Moved here from early onCreate block — pagingAdapter must exist first.)
        pagingAdapter.setViewOnceOpenListener(message -> {
            if (viewOnceController == null) return;
            viewOnceController.openViewOnce(message, () -> showViewOnceDialog(message));
        });

        // Feature 3: long-press on sender's lock bubble → revoke confirm dialog
        pagingAdapter.setViewOnceRevokeListener(message -> {
            String msgId = message.messageId != null ? message.messageId : message.id;
            com.callx.app.utils.AlertDialogStyler.showRounded(
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Remove message?")
                    .setMessage("This will permanently delete the message before your partner opens it. They will see \"Removed\".")
                    .setPositiveButton("Remove", (d, w) -> {
                        if (viewOnceController != null && msgId != null) {
                            viewOnceController.revokeViewOnce(msgId,
                                () -> Toast.makeText(this, "Message removed", Toast.LENGTH_SHORT).show(),
                                () -> Toast.makeText(this, "Failed to remove, try again", Toast.LENGTH_SHORT).show());
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .create(), com.callx.app.utils.AlertDialogStyler.DialogSize.WIDE);
        });

        pagingAdapter.setActionListener(new MessagePagingAdapter.ActionListener() {
            @Override public void onReply(Message m)               { startReply(m); }
            @Override public void onDelete(Message m)              { confirmDeleteMessage(m); }
            @Override public void onReact(Message m, String emoji) { reactionController.toggleReaction(m, emoji); }
            @Override public void onReactionTap(Message m)        { reactionController.showReactedUsers(m); }
            @Override public void onStar(Message m)                { starredController.toggleStar(m); }
            @Override public void onCopy(Message m)                { copyText(m); }
            @Override public void onForward(Message m)             { forwardMessage(m); }
            @Override public void onNavigateToOriginal(String mid) { navigateToOriginalMsg(mid, null); }
            @Override public void onNavigateToOriginal(String mid, String senderId) { navigateToOriginalMsg(mid, senderId); }
            @Override public void onRetry(Message m) {
                if (m.id == null) return;
                // Failed local-first media upload (mediaLocalPath still set,
                // never got a real mediaUrl) — retry the compress/upload
                // pipeline instead of re-pushing straight to Firebase.
                boolean isFailedMediaUpload = m.mediaLocalPath != null && !m.mediaLocalPath.isEmpty()
                        && (m.mediaUrl == null || m.mediaUrl.isEmpty());
                if (isFailedMediaUpload) {
                    mediaController.retryFailedMediaUpload(m);
                    return;
                }
                String preview = m.text != null ? m.text : (m.type != null ? "[" + m.type + "]" : "[message]");
                messageSender.firebasePushMessage(m, m.id, preview);
            }
            @Override public void onEdit(Message m)                { editHistoryController.editMessage(m); }
            @Override public void onShowEditHistory(Message m)     { editHistoryController.showHistory(m); }
            @Override public void onPin(Message m)                 { pinController.pinMessage(m); }
            @Override public void onPollVote(Message m, int idx)   { pollController.castVote(m, idx); }
            @Override public void onPollToggleClose(Message m)    { pollController.toggleClosed(m); }
            @Override public void onPlaybackStateChanged(Message m, boolean playing) {
                if (playbackPresenceController != null && m != null) {
                    String mid = m.messageId != null ? m.messageId : m.id;
                    playbackPresenceController.publishPlaybackState(mid, playing);
                }
            }
        });

        pagingAdapter.setMultiSelectListener(count -> {
            if (count > 0) showMultiSelectBar(count);
            else hideMultiSelectBar();
        });

        // PERF: Custom LinearLayoutManager — overrides calculateExtraLayoutSpace()
        // to pre-layout one full screen worth of items beyond each edge.
        // Default is 0: RV only lays out exactly what fits on screen, so fast
        // flings hit blank frames before new items are laid out. One extra
        // screen (display height pixels) hides that entirely.
        LinearLayoutManager llm = new LinearLayoutManager(this) {
            @Override
            protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state,
                                                     @NonNull int[] extraLayoutSpace) {
                int screenHeight = getResources().getDisplayMetrics().heightPixels;
                // Pre-layout 1.5× the screen height off both edges.
                // At 1× a 60fps fling on a 6.7" display (~900px/frame) could
                // exhaust the pre-laid buffer in ~1.1 frames — visible as a
                // flash of blank rows at the leading edge of a fast fling.
                // 1.5× adds a comfortable margin (≈1.7 frames of headroom)
                // without the memory cost of a full 2× pre-layout.
                // [0] = extra before first visible item, [1] = after last.
                int extra = (int)(screenHeight * 1.5f);
                extraLayoutSpace[0] = extra;
                extraLayoutSpace[1] = extra;
            }
        };
        llm.setStackFromEnd(true);
        llm.setReverseLayout(false);
        // PERF #3: Tell LinearLayoutManager how many items to prefetch.
        // Default is 2 — increasing to 8 means the next 8 items are inflated
        // and bound during RenderThread idle time before the user scrolls to
        // them. Matches the gap visible on a fast fling on a 6.5" display.
        llm.setInitialPrefetchItemCount(8);
        binding.rvMessages.setLayoutManager(llm);
        // PERF: build the 4 bubble-drawable combos now, before the first
        // layout pass — see ChatThemeManager.preWarm() for why.
        com.callx.app.utils.ChatThemeManager.get(this).preWarm(this);
        // PERF: setScrollingTouchSlop(TOUCH_SLOP_DEFAULT) — RV defaults to
        // TOUCH_SLOP_PAGING (larger tolerance, designed for ViewPagers).
        // TOUCH_SLOP_DEFAULT is the standard touch threshold used by ListView/ScrollView.
        // Result: scroll starts responding earlier → feels snappier.
        binding.rvMessages.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_DEFAULT);
        binding.rvMessages.setAdapter(pagingAdapter);
        // FIX #2b: setHasFixedSize(true) — RecyclerView won't re-measure
        // its own size every time an item changes; valid because the RV fills
        // the screen and its own size never changes (only item content changes).
        binding.rvMessages.setHasFixedSize(true);
        // PERF #3: Increase view cache from default 2 → 10.
        // With 5 view types (sent/received/status-seen/reel-seen/call) and a
        // typical visible window of ~12 items, cache=2 means almost every
        // onBind() must pull from the recycle pool (slow). 28 keeps more
        // recently off-screen views ready to rebind without reinflation,
        // especially at fast flings that can scroll past 20+ items instantly.
        binding.rvMessages.setItemViewCacheSize(28);
        // FIX #2d: Tune RecycledViewPool per view type (5 types × 5 each).
        // Default pool size is 5 already but explicit sizing prevents the pool
        // from being exhausted on fast flings that scroll past many bubbles.
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        // PERF: increased TYPE_SENT/RECEIVED pool to 18 — on a fast fling
        // (Telegram-speed flick, not just a slow drag) the visible window
        // can blow past 12-16 bubbles before the list settles; the old
        // pool=10 was still getting exhausted mid-fling on exactly that kind
        // of fast fling, forcing expensive ViewHolder inflation instead of
        // reuse. Less-common types bumped 3 → 6 for the same reason.
        pool.setMaxRecycledViews(1 /* TYPE_SENT */,        18);
        pool.setMaxRecycledViews(2 /* TYPE_RECEIVED */,    18);
        pool.setMaxRecycledViews(3 /* TYPE_STATUS_SEEN */,  6);
        pool.setMaxRecycledViews(4 /* TYPE_REEL_SEEN */,    6);
        pool.setMaxRecycledViews(5 /* TYPE_CALL_ENTRY */,   6);
        // PERF FIX: TYPE_CANVAS_SENT/RECEIVED (11/12) were missing here entirely,
        // silently falling back to RecyclerView's default pool size of 5. Since
        // isCanvasEligible() now covers text/image/video/gif/file/audio/poll/
        // contact/location/multi_media/reel_share — i.e. almost every bubble in
        // the chat — these two types are actually the hottest ones in the pool,
        // not the coldest. A fast Telegram-speed fling was blowing past 5
        // recycled canvas views almost immediately, forcing a fresh
        // MessageBubbleCanvasView allocation (+ full Paint/StaticLayout setup)
        // instead of reuse. Sized same as TYPE_SENT/RECEIVED since canvas has
        // fully replaced them as the primary bubble path.
        pool.setMaxRecycledViews(11 /* TYPE_CANVAS_SENT */,     18);
        pool.setMaxRecycledViews(12 /* TYPE_CANVAS_RECEIVED */, 18);
        binding.rvMessages.setRecycledViewPool(pool);
        // PERF (v176): warm the pool with a few TYPE_CANVAS_SENT/RECEIVED
        // holders BEFORE the user's first scroll, so the very first fling
        // never pays onCreateViewHolder() cost mid-gesture. Posted (not
        // inline) so it runs after the current cold-open frame, not during
        // it — see MessagePagingAdapter.warmUpRecycledViewPool() doc.
        binding.rvMessages.post(() ->
                pagingAdapter.warmUpRecycledViewPool(binding.rvMessages, pool, 6));
        // PERF: scroll-ahead image preloading — fast fling ke dauran agli
        // ~8 items ki thumbnail Glide cache mein pehle se fetch ho jaati
        // hai, taaki late-load blank image na dikhe. Size (200,200) wahi
        // hai jo bind() mein image/video thumbnail ke liye sabse pehle
        // load hoti hai — isliye preload aur actual load same cache-key
        // use karte hain (dobara download nahi hota).
        com.callx.app.utils.ChatMediaPreloader.attach(this, binding.rvMessages, 200, 200,
                position -> {
                    Message m = pagingAdapter.peek(position);
                    if (m == null || m.type == null) return null;
                    switch (m.type) {
                        case "image":
                        case "gif":
                        case "sticker":
                        case "video":
                            return (m.thumbnailUrl != null && !m.thumbnailUrl.isEmpty())
                                    ? m.thumbnailUrl
                                    : m.mediaUrl;
                        default:
                            return null;
                    }
                });
        SwipeOptimizer.disableChangeAnimations(binding.rvMessages);
        // WHATSAPP-STYLE FIX: kill the default ItemAnimator entirely.
        // DefaultItemAnimator fades/translates every inserted row in from
        // its previous position. When 20-30 messages land in one bulk
        // insert (cold open, or the buffered Firebase flush), that shows
        // up as the whole list visibly "sliding"/scrolling into place
        // even though stackFromEnd already laid it out correctly. WhatsApp
        // has no such animation — the chat just appears, already in place.
        // Setting itemAnimator to null makes every insert/remove/move an
        // instant, non-animated layout pass.
        binding.rvMessages.setItemAnimator(null);
        // PERF: disable nested scrolling — chat RV lives inside a CoordinatorLayout
        // but should own its own fling; nested-scroll overhead adds friction and
        // causes subtle frame drops during fast swipes.
        binding.rvMessages.setNestedScrollingEnabled(false);
        // PERF OPT #1: OVER_SCROLL_NEVER — eliminates edge glow/stretch on
        // over-scroll. The EdgeEffect triggers an extra Canvas draw call on every
        // frame while user is at top/bottom boundary. Chat never needs this; removing
        // it shaves ~1-2ms per overscroll frame.
        binding.rvMessages.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // PERF OPT #2: LAYER_TYPE_NONE — ensure no software layer is inherited.
        // Hardware-accelerated drawing is default on API 14+; a stray software layer
        // forces every frame to paint into a CPU Bitmap — fatal for scroll perf.
        binding.rvMessages.setLayerType(View.LAYER_TYPE_NONE, null);
        // PERF OPT #3: setSaveEnabled(false) — skips the O(n) child-state traversal
        // at onSaveInstanceState time. Chat scroll position is managed programmatically,
        // so the automatic state-save is useless work.
        binding.rvMessages.setSaveEnabled(false);

        // PERF OPT #4: fling-only hardware layer on the RecyclerView.
        // Every text bubble draws a rounded-corner GradientDrawable background
        // (BubbleShapeManager) plus a TextView — cheap individually, but during
        // a fast fling the layout is repainting many of these every frame.
        // Switching the whole RecyclerView to LAYER_TYPE_HARDWARE only while
        // actively dragging/settling turns that repeated bubble-shape drawing
        // into a single cached GPU texture that's just translated per frame;
        // switching back to LAYER_TYPE_NONE the instant scrolling stops avoids
        // paying the (larger) GPU memory cost of a hardware layer while idle.
        // This is the same technique WhatsApp/Telegram-style chat lists use to
        // keep fling buttery on mid-range devices.
        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    rv.setLayerType(View.LAYER_TYPE_NONE, null);
                } else if (rv.getLayerType() != View.LAYER_TYPE_HARDWARE) {
                    rv.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                }
            }
        });

        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                if (!initialScrollDone) {
                    // Very first data on screen — whether it came from the
                    // warm-cache path (firstPageRendered already true) or a
                    // cold Paging 3 load. stackFromEnd(true) anchors the
                    // layout at the bottom on its own; no scroll call is
                    // issued here or in restoreScrollOrGoToUnread() — see
                    // that method for why even a non-animated scroll call
                    // was removed entirely.
                    initialScrollDone = true;
                    firstPageRendered = true;
                    binding.rvMessages.post(() -> restoreScrollOrGoToUnread());
                    return;
                }
                int total = pagingAdapter.getItemCount();
                int othersCount = 0;
                for (int i = positionStart; i < Math.min(positionStart + itemCount, total); i++) {
                    Message m = pagingAdapter.peek(i);
                    if (m != null && m.senderId != null && !m.senderId.equals(currentUid)) {
                        othersCount++;
                    }
                }
                // WHATSAPP-STYLE AUTO-SCROLL: only fires when the freshly
                // inserted rows land at the very TAIL of the list (a real new
                // message — not an older page getting prepended while the
                // user has scrolled up to read history; that insert happens
                // at positionStart 0 and must NOT move the viewport).
                boolean isTailInsert = (positionStart + itemCount) >= total;
                if (isTailInsert && isUserAtBottom) {
                    // User is already sitting at the bottom, so the new
                    // message should reveal itself the way WhatsApp does:
                    // the existing bubbles glide upward smoothly and the new
                    // one settles into view — not an instant jump, and not
                    // "nothing happens until you scroll manually" (that was
                    // the old AUTO-SCROLL DISABLED behaviour, which made new
                    // messages feel stuck/laggy instead of alive).
                    // The distance covered is only the height of the 1-2 new
                    // rows since we're already pinned at the tail, so the
                    // built-in smooth scroller covers it in a couple of
                    // frames — no janky "flying past many items" like a
                    // long-distance smoothScrollToPosition would cause.
                    if (pendingAutoScrollRunnable != null) {
                        binding.rvMessages.removeCallbacks(pendingAutoScrollRunnable);
                    }
                    pendingAutoScrollRunnable = () -> {
                        pendingAutoScrollRunnable = null;
                        if (binding == null) return;
                        // Don't fight an active user gesture — if they started
                        // dragging/flinging between the insert and this posted
                        // frame, let their gesture win. stackFromEnd() means
                        // the tail keeps growing underneath them regardless,
                        // and onScrolled()'s own atBottom check will pick up
                        // cleanly once they let go.
                        if (binding.rvMessages.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
                        smoothScrollToBottomWhatsAppStyle(pagingAdapter.getItemCount() - 1);
                    };
                    binding.rvMessages.post(pendingAutoScrollRunnable);
                    pendingNewMsgCount = 0;
                    hideNewMessagesIndicator();
                } else if (othersCount > 0 && !isUserAtBottom) {
                    pendingNewMsgCount += othersCount;
                    updateNewMessagesIndicator(pendingNewMsgCount);
                }
            }
        });

        // WHATSAPP-STYLE REVEAL — the definitive fix.
        //
        // Everything tried before this (submitData()-adjacent calls,
        // onItemRangeInserted-based heuristics) was an approximation of
        // "content is really on screen now." AndroidX Paging ships an
        // exact, purpose-built signal for this: addOnPagesUpdatedListener().
        // Per Paging3's own docs, it fires once a new generation of
        // PagingData has been fully diffed AND applied to the RecyclerView
        // — i.e. the adapter's presented list == what's about to be drawn.
        // This is the officially recommended hook for coordinating Paging
        // with postponed activity transitions / shared elements.
        //
        // Guard flag: only the first firing with real items matters — the
        // reveal only needs to happen once. Later generations (new
        // messages arriving, etc.) just update in place with no transition
        // replay, same as before.
        final boolean[] pagesReadyForReveal = {false};
        pagingAdapter.addOnPagesUpdatedListener(() -> {
            if (!pagesReadyForReveal[0] && pagingAdapter.getItemCount() > 0) {
                pagesReadyForReveal[0] = true;
                startPostponedContentTransition();
                // BUG FIX (cold-open big-bubble race): keep the adapter's
                // text-precompute path synchronous until the very first
                // page has actually settled on screen, then flip it back
                // to async for fling performance. See asyncTextEnabled's
                // javadoc in MessagePagingAdapter for the full story.
                binding.rvMessages.postDelayed(
                        () -> pagingAdapter.asyncTextEnabled = true, 400L);
            }
            // addOnPagesUpdatedListener() takes a Kotlin Function0<Unit>, not a
            // java.lang.Runnable — from Java that lambda must explicitly hand
            // back Unit.INSTANCE or javac rejects it as "missing return value".
            return kotlin.Unit.INSTANCE;
        });

        pagingAdapter.addLoadStateListener(states -> {
            androidx.paging.LoadState refresh = states.getRefresh();
            if (refresh instanceof androidx.paging.LoadState.Loading) {
                // Still loading — let the already-scheduled delayed shimmer
                // fire on its own (don't show it earlier than planned).
                binding.llEmptyChat.setVisibility(View.GONE);
            } else {
                // Data resolved (cached or fresh) — cancel the pending
                // shimmer-show before it ever gets a chance to appear.
                shimmerHandler.removeCallbacks(shimmerShowRunnable);
                binding.shimmerContainer.stopShimmer();
                binding.shimmerContainer.setVisibility(View.GONE);
                if (refresh instanceof androidx.paging.LoadState.Error) {
                    String msg = ((androidx.paging.LoadState.Error) refresh).getError().getMessage();
                    Toast.makeText(this, "Failed to load messages: " + msg, Toast.LENGTH_SHORT).show();
                }
                boolean isEmpty = pagingAdapter.getItemCount() == 0;
                binding.rvMessages.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                binding.llEmptyChat.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                // Confirmed-empty chat (genuinely zero messages, ever) —
                // onItemRangeInserted() will never fire since nothing is
                // being inserted, so this is the only signal we'll get.
                // Safe to reveal immediately: there's no content to wait
                // for a layout pass on. Non-empty chats are deliberately
                // NOT handled here — onItemRangeInserted() above triggers
                // the reveal once real items are actually laid out, which
                // is what removed the blink/pop.
                if (isEmpty) {
                    startPostponedContentTransition();
                }
            }
            return null;
        });
    }

    // Swappable Paging source: a MediatorLiveData lets us tear down the old
    // Pager's LiveData and attach a brand-new, freshly-anchored one on demand
    // (see attachPagerWithKey / reanchorPagingToBottomAfterWrite below) instead
    // of being stuck with whatever refresh-key Paging 3's own Room-invalidation
    // path decides to center on.
    private MediatorLiveData<PagingData<Message>> pagingMediator;
    private LiveData<PagingData<Message>> currentPagingLiveSource;

    // LIVE-UPDATE FIX: reference to whichever MessageKeysetPagingSource
    // instance is CURRENTLY backing the live Pager (see attachPagerWithKey's
    // factory lambda, which updates this on every new instance Paging3
    // creates — including the ones it creates internally after a prior
    // invalidate()). severPagingIfAtBottom()/reanchorPagingToBottom() call
    // .invalidate() on this instead of tearing down/rebuilding the whole
    // Pager — invalidate() is the correct, lightweight Paging3 API for
    // "the data changed, please reload," and (unlike a full Pager rebuild)
    // it does NOT reset the RemoteMediator or the LiveData/Transformations
    // pipeline, so the adapter's AsyncPagingDataDiffer just quietly diffs
    // the refreshed page against what's already on screen — no visible
    // "whole list rebuilding" moment, and messages actually show up live
    // again (a bare no-op here — the previous fix — silenced the flicker
    // by never refreshing anything at all, which is why sends/receives
    // stopped appearing until the chat was reopened).
    private volatile com.callx.app.db.paging.MessageKeysetPagingSource currentKeysetSource;

    // PERF FIX: guards startPostponedContentTransition() so it only ever
    // actually resumes the transition once, no matter how many of the
    // trigger points below fire (warm-cache hit, first real Paging load,
    // or the safety timeout).
    private boolean contentTransitionStarted = false;

    // Genuine ceiling only — a normal load is expected to resolve via the
    // real "data ready" triggers (warm-cache hit at line ~488, or Paging's
    // LoadState.NotLoading at line ~1771) well before this fires. This
    // exists purely so a chat whose load hangs/fails doesn't leave the
    // screen frozen mid-transition forever.
    private static final long SAFETY_TRANSITION_TIMEOUT_MS = 3000L;

    private void startPostponedContentTransition() {
        if (contentTransitionStarted) return;
        contentTransitionStarted = true;
        // Wait one layout pass so the just-submitted RecyclerView content
        // is actually measured/laid out before the slide plays — otherwise
        // the transition can still start a frame too early.
        binding.getRoot().getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        binding.getRoot().getViewTreeObserver().removeOnPreDrawListener(this);
                        startPostponedEnterTransition();
                        return true;
                    }
                });
    }

    private void observePagedMessages() {
        pagingMediator = new MediatorLiveData<>();
        pagingMediator.observe(this, pagingData -> pagingAdapter.submitData(getLifecycle(), pagingData));
        attachFreshBottomAnchoredPager();
    }

    /**
     * PERF FIX (root cause of "few messages instant, many messages slow"):
     * this used to look up the row COUNT and anchor a position-keyed Pager
     * at (count - 1). Room's generated PagingSource for that query is
     * OFFSET-based, so anchoring near the end of a LARGE chat meant SQLite
     * had to walk past thousands of rows before returning a page — cost
     * scaled with chat history size. See MessageKeysetPagingSource for the
     * full explanation.
     *
     * With keyset pagination, REFRESH always means "the most recent page"
     * regardless of key (see MessageKeysetPagingSource.loadSingle), so
     * there's no count to look up at all — no thread hop, no OFFSET scan,
     * no per-chat-size cost. This is strictly simpler AND faster than the
     * in-memory count-cache from the previous fix, which is now removed.
     */
    private void attachFreshBottomAnchoredPager() {
        attachPagerWithKey(null);
    }

    /**
     * Replaces whatever Pager is currently feeding the adapter with a brand
     * new one built with the given initialKey. Removing the old LiveData
     * source first means the old Pager's own Room-invalidation-triggered
     * auto-refresh (whose refresh-key centering is what was causing the
     * "jumps to top / lands in some old batch" bug on every send/receive)
     * can never push another update into the adapter once replaced.
     */
    private void attachPagerWithKey(Long initialKey) {
        if (isFinishing() || isDestroyed() || binding == null || pagingMediator == null) return;
        if (currentPagingLiveSource != null) pagingMediator.removeSource(currentPagingLiveSource);
        // FIX: RemoteMediator added — previously the Pager only ever read
        // from Room, so scrolling up past whatever the one-shot delta sync
        // had fetched simply dead-ended (see MessageRemoteMediator's class
        // doc). This lets PREPEND reach further back into Firebase on demand.
        com.callx.app.repository.ChatRepository chatRepository =
                com.callx.app.repository.ChatRepository.getInstance(this);
        Pager<Long, MessageEntity> pager = new Pager<>(
                new PagingConfig(PAGE_SIZE, PREFETCH_DIST, false, INITIAL_LOAD),
                initialKey,
                new com.callx.app.db.paging.MessageRemoteMediator(chatRepository, chatId, PAGE_SIZE),
                () -> {
                    com.callx.app.db.paging.MessageKeysetPagingSource src =
                            new com.callx.app.db.paging.MessageKeysetPagingSource(db.messageDao(), chatId, PAGE_SIZE);
                    // Paging3 calls this factory again on its own every time
                    // the previous source is invalidated (manually via
                    // reanchorPagingToBottom() below, or from any other
                    // invalidation path) — always keep the reference pointed
                    // at whichever instance is actually live right now.
                    currentKeysetSource = src;
                    return src;
                }
        );
        currentPagingLiveSource = Transformations.map(
                PagingLiveData.getLiveData(pager),
                pagingData -> {
                    PagingData<Message> mapped =
                            PagingDataTransforms.map(pagingData, ioExecutor, ChatActivity::entityToModel);
                    // Insert date separators as synthetic Message rows (type="date_separator").
                    // insertSeparators() is called with (before, after):
                    //   • before=null  → after is first item → always insert a date chip above it.
                    //   • after=null   → before is last item  → no chip needed at the end.
                    //   • both non-null → insert chip only when they belong to different days.
                    return PagingDataTransforms.insertSeparators(mapped, ioExecutor,
                            (before, after) -> {
                                if (after == null) return null; // end of list — no separator needed
                                boolean differentDay = before == null  // first item
                                        || before.timestamp == null
                                        || after.timestamp == null
                                        || !isSamePagingDay(before.timestamp, after.timestamp);
                                if (!differentDay) return null;
                                Message sep = new Message();
                                sep.type = "date_separator";
                                sep.messageId = "sep_" + (after.timestamp != null ? after.timestamp : 0);
                                sep.text = formatPagingDateLabel(after.timestamp != null ? after.timestamp : 0);
                                return sep;
                            });
                }
        );
        pagingMediator.addSource(currentPagingLiveSource, pagingMediator::setValue);
    }

    /**
     * FLICKER FIX + LIVE-UPDATE FIX (see currentKeysetSource's field doc for
     * the full story): this used to tear down the entire Pager/LiveData/
     * transform pipeline on every single write (2–3 times per message —
     * insertMessage("pending"), updateStatus("sent"), and every incoming
     * flushPendingRoomWrites()), which is what made the whole chat look
     * like it was rebuilding on every send/receive. A bare no-op fixed
     * that but silently broke live updates entirely, since
     * MessageKeysetPagingSource is a hand-written RxPagingSource (not a
     * Room-generated one) — Room's InvalidationTracker never automatically
     * refreshes it, so NOTHING was telling Paging3 to reload after a write
     * once the manual teardown was removed. New messages only appeared
     * after leaving and reopening the chat, which rebuilds the whole
     * pipeline fresh in onCreate().
     *
     * The actual fix: call invalidate() on the specific PagingSource
     * instance that's currently live (see reanchorPagingToBottom() below)
     * instead of severing/rebuilding anything. invalidate() is exactly the
     * API Paging3 expects for "the underlying data changed, please
     * reload" — it reuses the SAME Pager/RemoteMediator/LiveData chain
     * (no visible reset), and MessageKeysetPagingSource.getRefreshKey()
     * unconditionally returns null, so the reload this triggers always
     * lands on the newest page regardless of what triggered it.
     */
    @Override
    public boolean severPagingIfAtBottom() {
        return isUserAtBottom;
    }

    /** Call from ANY thread, AFTER a live write commits, when
     *  severPagingIfAtBottom() returned true for that same write. See its
     *  doc above — this just invalidates the current PagingSource instance
     *  instead of rebuilding the whole Pager. */
    @Override
    public void reanchorPagingToBottom() {
        com.callx.app.db.paging.MessageKeysetPagingSource src = currentKeysetSource;
        if (src != null) src.invalidate();
    }

    private void startRealtimeListenerEarly() {
        // PERF FIX v8: DB ready hone se PEHLE Firebase listener lagao.
        // Incoming messages pendingUpserts buffer mein queue hote hain.
        // flushPendingRoomWrites() onDbReady() mein call hoga — ek transaction.
        //
        // BUG FIX: yeh pehle `this.db != null` check karta tha — lekin `db`
        // field ek ALAG ioExecutor task se set hota hai (Step 2, neeche) jo
        // SAME 2-thread pool pe concurrently chalta hai. Race condition ki
        // wajah se `db` yahan almost always abhi tak null hota tha, har
        // single chat open pe lastTs=0 ban jaata tha — matlab Firebase
        // hamesha FULL limitToLast(30) resync karta tha (delta sync kabhi
        // nahi chalta tha), saare 30 messages re-upsert hote, Room table
        // invalidate hoti, aur Paging poori list reload kar deta — yahi
        // "har baar messages reload hote hain" wapas aane ka asli reason.
        // AppDatabase pehle se warm hai (app-start fix), seedha call karo —
        // koi gating ki zaroorat nahi.
        ioExecutor.execute(() -> {
            long lastTs = CacheManager.getInstance(this).getLastSyncTimestamp(chatId);
            runOnUiThread(() -> attachFirebaseListener(lastTs));
        });
    }

    private void startRealtimeListener() {
        ioExecutor.execute(() -> {
            long lastTs = CacheManager.getInstance(this).getLastSyncTimestamp(chatId);
            runOnUiThread(() -> attachFirebaseListener(lastTs));
        });
    }

    private void attachFirebaseListener(long lastTs) {
        com.google.firebase.database.Query query = lastTs > 0
                ? messagesRef.orderByChild("timestamp").startAfter((double) lastTs)
                : messagesRef.orderByChild("timestamp").limitToLast(INITIAL_LOAD);

        messageListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot snapshot, String prev) {
                Message m = snapshot.getValue(Message.class);
                if (m == null) return;
                m.id = snapshot.getKey();
                saveToRoom(m, false);
                // PERF FIX v25: no longer fires MessageStatusSync.upgradeStatus()
                // (an individual Firebase runTransaction() + synchronous
                // SharedPreferences/PendingAckQueue write) per incoming message
                // here. That ran on the main thread for every single message in
                // the initial burst — opening a chat with 80-100 unread messages
                // meant 80-100 back-to-back transactions + disk writes competing
                // with the very first frames of RecyclerView layout/scroll, which
                // is what made "chat khulna slow" / scroll jank on open. The chat
                // is open right now, so delivered and read happen in the same
                // instant anyway — presenceController.markRead() below already
                // buffers ALL of this into ONE batched updateChildren() call that
                // now stamps status=read + deliveredAt + readAt together (see
                // ChatPresenceController#flushPendingReadStatus()). The
                // transaction-based upgradeStatus() path stays in place for the
                // genuinely rare, one-off cases where the chat is NOT open —
                // CallxMessagingService (FCM push) and GlobalDeliveryAckManager.
                presenceController.markRead(m);
                if (emojiBurstController != null) emojiBurstController.onMessageReceived(m);
            }
            @Override public void onChildChanged(DataSnapshot snapshot, String prev) {
                Message m = snapshot.getValue(Message.class);
                if (m == null) return;
                m.id = snapshot.getKey();
                saveToRoom(m, true);
            }
            @Override public void onChildRemoved(DataSnapshot snapshot) {
                String key = snapshot.getKey();
                if (key == null) return;
                // PERF FIX: buffer the removal too, so a delete that lands in the
                // same burst as the initial load coalesces into the same single
                // Room transaction instead of firing its own invalidation.
                pendingUpserts.remove(key);
                pendingRemovals.add(key);
                scheduleWriteFlush();
            }
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError error) {}
        };
        query.addChildEventListener(messageListener);

        // ── TICK FIX ─────────────────────────────────────────────────────
        // BUG: messageListener above is attached to a DELTA query —
        // startAfter(lastTs) on every reopen after the first. A brand-new
        // Firebase Query only reports onChildChanged for children that are
        // INSIDE its own filtered result set; a message whose timestamp is
        // <= lastTs was never part of this query's window, so once the
        // sender leaves the chat and comes back later, Firebase never
        // tells this listener about a "sent" -> "delivered" -> "read"
        // update that happens on that already-synced message. Net effect:
        // ticks silently freeze at "sent" (one grey tick) forever, because
        // nothing is watching that message for changes any more — exactly
        // what was reported ("only the sent single tick ever shows").
        //
        // FIX: a second, small ChildEventListener scoped to the last
        // STATUS_SYNC_WINDOW messages (by timestamp), same as the very
        // first page load would be. Its onChildAdded is a no-op — new/
        // already-loaded messages are fully handled by messageListener and
        // the initial Room cache, so we don't want to double-process them
        // here. Its onChildChanged reuses the exact same saveToRoom() path,
        // which upserts the FULL message row via Room REPLACE — cheap,
        // since PagingDataAdapter's DiffUtil.ItemCallback already detects
        // "only status changed" and returns PAYLOAD_STATUS (see
        // MessagePagingAdapter), so the UI does a draw-only tick update
        // instead of a full rebind. No effect on scroll/paging performance:
        // this listener never touches the Pager, only the messages table.
        com.google.firebase.database.Query statusQuery =
                messagesRef.orderByChild("timestamp").limitToLast(STATUS_SYNC_WINDOW);
        statusSyncListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot snapshot, String prev) {
                // Handled by messageListener / initial Room load — ignore here.
            }
            @Override public void onChildChanged(DataSnapshot snapshot, String prev) {
                Message m = snapshot.getValue(Message.class);
                if (m == null) return;
                m.id = snapshot.getKey();
                saveToRoom(m, true);
            }
            @Override public void onChildRemoved(DataSnapshot snapshot) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError error) {}
        };
        statusQuery.addChildEventListener(statusSyncListener);
    }

    private void saveToRoom(Message m, boolean isUpdate) {
        queueRoomWrite(m);
    }

    /**
     * PERF FIX: buffers a Firebase add/change event instead of writing it to
     * Room straight away. Everything queued within WRITE_FLUSH_DEBOUNCE_MS
     * gets applied in a single Room transaction — see scheduleWriteFlush()
     * and MessageDao#applyBufferedChanges() for the full explanation.
     */
    private void queueRoomWrite(Message m) {
        if (m == null || m.id == null) return;
        pendingUpserts.put(m.id, m);
        pendingRemovals.remove(m.id); // a fresh upsert always wins over a stale pending removal
        scheduleWriteFlush();
    }

    @Override
    public void queueMarkRead(String messageId) {
        if (messageId == null) return;
        pendingReadIds.add(messageId);
        scheduleWriteFlush();
    }

    private void scheduleWriteFlush() {
        if (writeFlushScheduled) return;
        writeFlushScheduled = true;
        writeFlushHandler.postDelayed(writeFlushRunnable, WRITE_FLUSH_DEBOUNCE_MS);
    }

    /** Applies every buffered Firebase event since the last flush in ONE Room transaction. */
    private void flushPendingRoomWrites() {
        writeFlushScheduled = false;
        if (pendingUpserts.isEmpty() && pendingRemovals.isEmpty() && pendingReadIds.isEmpty()) return;

        java.util.List<Message> upsertsSnapshot = new java.util.ArrayList<>(pendingUpserts.values());
        java.util.List<String> removalsSnapshot = new java.util.ArrayList<>(pendingRemovals);
        java.util.List<String> readSnapshot = new java.util.ArrayList<>(pendingReadIds);
        pendingUpserts.clear();
        pendingRemovals.clear();
        pendingReadIds.clear();

        if (db == null) return;
        // PERF FIX: keep LastMessagesCache in sync with every Firebase-driven
        // Room write — new messages, edits/status changes, and removals all
        // flow through here (see queueRoomWrite/onChildRemoved above), so
        // hooking the cache update here covers every case in one place:
        // next time this chat is reopened, the warm-render fast path in
        // onCreate() will have accurate, current data.
        for (Message m : upsertsSnapshot) LastMessagesCache.getInstance().upsert(chatId, m);
        for (String removedId : removalsSnapshot) LastMessagesCache.getInstance().removeMessage(chatId, removedId);

        // BUG FIX (v2): sever the OLD Pager's source HERE, before the write
        // starts — see severPagingIfAtBottom() doc above for why doing this
        // after the write loses the race against Room's invalidation
        // tracker (which is what caused the top-jump to persist even after
        // the v1 fix). Shared with ChatMessageSender's direct write paths
        // (insertMessage / updateStatus) which bypass this buffered method
        // entirely — see severPagingIfAtBottom()/reanchorPagingToBottom().
        boolean willReanchor = severPagingIfAtBottom();

        ioExecutor.execute(() -> {
            java.util.List<MessageEntity> entities = new java.util.ArrayList<>(upsertsSnapshot.size());
            for (Message m : upsertsSnapshot) entities.add(modelToEntity(m));
            db.messageDao().applyBufferedChanges(entities, removalsSnapshot, readSnapshot);

            if (willReanchor) reanchorPagingToBottom();
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // WHATSAPP-STYLE SCROLL STATE MANAGEMENT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Saves the current scroll position and the last-seen message timestamp
     * to SharedPreferences. Called from onPause() so it persists across
     * screen closes (back-press, home, other screen navigations).
     *
     * Saved data:
     *   "pos_<chatId>"    — first visible item index
     *   "off_<chatId>"    — pixel offset of that item's top edge
     *   "lastTs_<chatId>" — timestamp of the last message in the adapter
     *                        (used to detect new messages on re-open)
     */
    private void saveScrollState() {
        if (binding == null || chatId == null || pagingAdapter == null) return;
        LinearLayoutManager llm = (LinearLayoutManager) binding.rvMessages.getLayoutManager();
        if (llm == null) return;
        int firstPos = llm.findFirstVisibleItemPosition();
        if (firstPos < 0) return;
        android.view.View firstView = llm.findViewByPosition(firstPos);
        int offset = (firstView != null) ? firstView.getTop() : 0;
        // Last message timestamp — used to detect messages that arrived
        // while we were away from this chat.
        long lastMsgTs = 0;
        int total = pagingAdapter.getItemCount();
        if (total > 0) {
            Message last = pagingAdapter.peek(total - 1);
            if (last != null && last.timestamp != null) lastMsgTs = last.timestamp;
        }
        getSharedPreferences(SCROLL_PREFS, MODE_PRIVATE).edit()
                .putInt("pos_" + chatId, firstPos)
                .putInt("off_" + chatId, offset)
                .putLong("lastTs_" + chatId, lastMsgTs)
                .apply();
    }

    /**
     * Chat-open scroll behaviour: land on the latest (bottom) message with
     * ZERO programmatic scroll calls of any kind — no scrollToPosition(),
     * no scrollToPositionWithOffset(), no smoothScroll. We rely entirely on
     * LinearLayoutManager's stackFromEnd(true) (set in setupPagingRecyclerView),
     * which anchors its FIRST layout pass at the last adapter item natively —
     * the bottom message is simply where the view starts, not where it
     * "scrolls to". Calling scrollToPosition() here — even though it's a
     * non-animated jump — was occasionally visible as a one-frame snap when
     * a second insert (warm-cache → real Room data reconciliation, or a
     * Paging prefetch batch) landed between the layout pass and this post()
     * callback running, since "total-1" could already be stale by then.
     * Removing the call entirely eliminates that class of bug at the root:
     * there is no longer any code path on chat-open that can move the list.
     */
    private void restoreScrollOrGoToUnread() {
        if (pagingAdapter == null || binding == null) return;
        if (pagingAdapter.getItemCount() == 0) return;

        // No scroll call. stackFromEnd already placed the bottom message
        // in view on first layout. We only reset the indicator/state here.
        pendingNewMsgCount = 0;
        hideNewMessagesIndicator();
        isUserAtBottom = true;
    }

    /**
     * WHATSAPP-STYLE SMOOTH REVEAL for a freshly-arrived tail message.
     *
     * FAST PATH: because calculateExtraLayoutSpace() (see setupPagingRecyclerView)
     * already pre-lays one full screen beyond the fold, the new row is almost
     * always already measured & positioned off-screen by the time this runs.
     * That means we can read its exact pixel position and hand RecyclerView's
     * own smoothScrollBy(dx, dy, interpolator, duration) overload the precise
     * distance — one continuous, physically accurate glide with a real
     * Material easing curve, instead of LinearSmoothScroller's frame-by-frame
     * "seek the target" approximation. Duration adapts slightly to distance
     * (a tall multi-line bubble vs. a short one-liner) but stays clamped to a
     * quick, buttery range.
     *
     * FALLBACK: on the rare chance the row isn't laid out yet (e.g. a large
     * batch landed beyond the pre-layout window), LinearSmoothScroller still
     * handles it correctly, just without the exact-pixel precision.
     */
    private void smoothScrollToBottomWhatsAppStyle(int targetPosition) {
        if (binding == null || targetPosition < 0) return;
        RecyclerView rv = binding.rvMessages;
        RecyclerView.LayoutManager rawLm = rv.getLayoutManager();
        if (!(rawLm instanceof LinearLayoutManager)) return;
        LinearLayoutManager lm = (LinearLayoutManager) rawLm;

        android.view.View targetView = lm.findViewByPosition(targetPosition);
        if (targetView != null) {
            int viewportBottom = rv.getHeight() - rv.getPaddingBottom();
            int dy = targetView.getBottom() - viewportBottom;
            if (dy <= 0) return; // already fully visible — nothing to reveal
            int duration = (int) Math.max(WHATSAPP_REVEAL_MIN_DURATION_MS,
                    Math.min(WHATSAPP_REVEAL_MAX_DURATION_MS, dy * WHATSAPP_REVEAL_MS_PER_PIXEL));
            rv.smoothScrollBy(0, dy, WHATSAPP_REVEAL_INTERPOLATOR, duration);
            return;
        }
        LinearSmoothScroller scroller = new LinearSmoothScroller(this) {
            @Override protected int getVerticalSnapPreference() {
                return LinearSmoothScroller.SNAP_TO_END;
            }
            @Override protected float calculateSpeedPerPixel(android.util.DisplayMetrics dm) {
                return 1.5f / dm.densityDpi;
            }
        };
        scroller.setTargetPosition(targetPosition);
        lm.startSmoothScroll(scroller);
    }

    /** Shows or updates the "↓ N new messages" floating indicator chip. */
    private void updateNewMessagesIndicator(int count) {
        if (binding.tvNewMessagesIndicator == null) return;
        String text = count == 1 ? "↓ 1 new message" : "↓ " + count + " new messages";
        binding.tvNewMessagesIndicator.setText(text);
        binding.tvNewMessagesIndicator.setVisibility(View.VISIBLE);
        // Also make the FAB visible so both appear together
        if (binding.fabBackToLatest != null) {
            binding.fabBackToLatest.setVisibility(View.VISIBLE);
            binding.fabBackToLatest.setAlpha(1f);
        }
    }

    /** Hides the "↓ N new messages" indicator. */
    private void hideNewMessagesIndicator() {
        if (binding.tvNewMessagesIndicator == null) return;
        binding.tvNewMessagesIndicator.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // ENTITY ↔ MODEL CONVERSION
    // ─────────────────────────────────────────────────────────────────────

    // ── Date separator helpers — used by insertSeparators() in the paging pipeline ──
    private static boolean isSamePagingDay(long ts1, long ts2) {
        return (ts1 / 86_400_000L) == (ts2 / 86_400_000L);
    }

    /**
     * PERF FIX: mirrors the date-separator logic from attachPagerWithKey()'s
     * insertSeparators() transform, but runs synchronously over an in-memory
     * List<Message> — used ONLY for the warm-cache seed path.
     *
     * Root cause of the residual "pop" after screen open: the warm-cache
     * path (LastMessagesCache → PagingData.from(cached)) submitted raw
     * messages with NO date-separator chips, while the real Paging3
     * pipeline a moment later DOES insert them. DiffUtil then saw the
     * separator rows as brand-new items and inserted them live into an
     * already-visible RecyclerView — bubbles visibly shifting to make room,
     * which read as a "pop" even though the actual messages hadn't changed.
     *
     * Building the cached seed with the exact same separators up front means
     * the cached submission and the real submission are structurally
     * identical, so DiffUtil finds no changes at all when real data lands —
     * no insert, no shift, no pop.
     */
    private static java.util.List<Message> withDateSeparators(java.util.List<Message> ascMessages) {
        java.util.List<Message> out = new java.util.ArrayList<>(ascMessages.size() + 4);
        Message prev = null;
        for (Message m : ascMessages) {
            boolean differentDay = prev == null
                    || prev.timestamp == null
                    || m.timestamp == null
                    || !isSamePagingDay(prev.timestamp, m.timestamp);
            if (differentDay) {
                Message sep = new Message();
                sep.type = "date_separator";
                sep.messageId = "sep_" + (m.timestamp != null ? m.timestamp : 0);
                sep.text = formatPagingDateLabel(m.timestamp != null ? m.timestamp : 0);
                out.add(sep);
            }
            out.add(m);
            prev = m;
        }
        return out;
    }

    private static String formatPagingDateLabel(long timestamp) {
        java.util.Calendar msgCal = java.util.Calendar.getInstance();
        msgCal.setTimeInMillis(timestamp);
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar yesterday = java.util.Calendar.getInstance();
        yesterday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        boolean isToday = msgCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
                && msgCal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR);
        boolean isYesterday = msgCal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR)
                && msgCal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR);
        if (isToday) return "Today";
        if (isYesterday) return "Yesterday";
        boolean sameYear = msgCal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR);
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                sameYear ? "d MMM" : "d MMM yyyy", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(timestamp));
    }

    static Message entityToModel(MessageEntity e) {
        Message m = com.callx.app.utils.MessageEntityMapper.toModel(e);
        // PERF: kick off background StaticLayout precompute here — this
        // method already runs on ioExecutor (see PagingDataTransforms.map
        // in attachPagerWithKey()), off the UI thread, before `m` ever
        // reaches the adapter/paging list. See the cache javadoc in
        // MessageBubbleCanvasView for the full safety reasoning. Cheap and
        // safe to call unconditionally — it no-ops for anything not a
        // plain, non-deleted text message.
        if (m != null && "text".equals(m.type)) {
            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                    .precomputeTextLayoutIfPossible(m.text, Boolean.TRUE.equals(m.deleted));
        }
        // PERF: single-image/video captions and media-group captions are
        // plain text runs measured at the exact same maxTextWidth a text
        // bubble uses (see MessageBubbleCanvasView's isMedia/isMediaGroup
        // measure branches), so they share this same cache — no dedicated
        // caption cache needed.
        if (m != null && m.caption != null && !m.caption.isEmpty()
                && ("image".equals(m.type) || "video".equals(m.type) || "multi_media".equals(m.type))) {
            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                    .precomputeTextLayoutIfPossible(m.caption, false);
        }
        if (m != null && "poll".equals(m.type) && m.pollOptions != null) {
            for (String opt : m.pollOptions) {
                com.callx.app.conversation.canvas.MessageBubbleCanvasView
                        .precomputePollOptionLayoutIfPossible(opt);
            }
        }
        // PERF ADV: same idea, for the reply-preview strip (sender name +
        // quoted text) — one of the most common message shapes in the app.
        // See precomputeReplyLayoutIfPossible() javadoc for full reasoning.
        if (m != null && m.replyToText != null && !m.replyToText.isEmpty()) {
            com.callx.app.conversation.canvas.MessageBubbleCanvasView
                    .precomputeReplyLayoutIfPossible(
                            m.replyToSenderName,
                            m.replyToText,
                            m.replyToMediaUrl != null && !m.replyToMediaUrl.isEmpty());
        }
        return m;
    }

    private MessageEntity modelToEntity(Message m) {
        MessageEntity e = new MessageEntity();
        e.id = m.id != null ? m.id : ""; e.chatId = chatId; e.senderId = m.senderId;
        e.senderName = m.senderName; e.senderPhoto = m.senderPhoto; e.text = m.text;
        e.type = m.type != null ? m.type : "text"; e.mediaUrl = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
        e.thumbnailUrl = m.thumbnailUrl; e.fileName = m.fileName; e.fileSize = m.fileSize;
        e.duration = m.duration; e.timestamp = m.timestamp; e.status = m.status;
        e.deliveredAt = m.deliveredAt; e.readAt = m.readAt;
        e.replyToId = m.replyToId; e.replyToText = m.replyToText;
        e.replyToSenderName = m.replyToSenderName; e.replyToType = m.replyToType;
        e.replyToMediaUrl = m.replyToMediaUrl; e.edited = m.edited; e.editedAt = m.editedAt; e.deleted = m.deleted;
        e.editHistoryJson = com.callx.app.utils.EditHistoryJsonUtil.historyToJson(m.editHistory);
        e.forwardedFrom = m.forwardedFrom; e.starred = Boolean.TRUE.equals(m.starred);
        e.reactionsJson = com.callx.app.utils.ReactionJsonUtil.reactionsToJson(m.reactions);
        e.pinned = Boolean.TRUE.equals(m.pinned); e.reelId = m.reelId;
        e.reelOwnerUid = m.reelOwnerUid;
        e.statusOwnerUid = m.statusOwnerUid; e.statusOwnerName = m.statusOwnerName;
        e.statusThumbUrl = m.statusThumbUrl;
        e.reelThumbUrl = m.reelThumbUrl; e.fontStyle = m.fontStyle; e.expiresAt = m.expiresAt;
        e.viewOnce = m.viewOnce; e.viewOnceState = m.viewOnceState; e.openedAt = m.openedAt; e.viewOnceExpiresAt = m.viewOnceExpiresAt;
        e.pollQuestion    = m.pollQuestion;
        e.pollOptionsJson = com.callx.app.utils.PollJsonUtil.optionsToJson(m.pollOptions);
        e.pollVotesJson   = com.callx.app.utils.PollJsonUtil.votesToJson(m.pollVotes);
        e.pollAnonymous   = m.pollAnonymous;
        e.pollClosed      = m.pollClosed;
        e.pollMultiChoice = m.pollMultiChoice;
        e.reelShareUrl        = m.reelShareUrl;
        e.reelShareThumb      = m.reelShareThumb;
        e.reelShareCaption    = m.reelShareCaption;
        e.reelShareUsername   = m.reelShareUsername;
        e.reelShareOwnerPhoto = m.reelShareOwnerPhoto;
        e.mediaItemsJson = com.callx.app.utils.MediaItemsJsonUtil.mediaItemsToJson(m.mediaItems);
        e.caption        = m.caption;
        e.contactName = m.contactName; e.contactPhone = m.contactPhone;
        e.contactPhone2 = m.contactPhone2; e.contactPhotoUrl = m.contactPhotoUrl;
        e.locationLat = m.locationLat; e.locationLng = m.locationLng; e.locationAddress = m.locationAddress;
        e.broadcast = m.broadcast;
        // BUG FIX (v43): see AppDatabase.MIGRATION_42_43 — these previously
        // had no Room column and were silently dropped on every round-trip.
        e.mediaWidth = m.mediaWidth; e.mediaHeight = m.mediaHeight;
        // BUG FIX (v44): blurHash — see AppDatabase.MIGRATION_43_44.
        e.blurHash = m.blurHash;
        e.syncedAt = System.currentTimeMillis();
        return e;
    }

    // ─────────────────────────────────────────────────────────────────────
    // INPUT BAR
    // ─────────────────────────────────────────────────────────────────────

    // Tracks whether the attach/camera icons are currently expanded (shown),
    // so we don't restart the same animation redundantly on every keystroke.
    private Boolean inputIconsExpanded = null;

    // Drives the capsule's own height with real spring physics whenever the
    // EditText grows/shrinks between 1-4 lines. Re-entrancy guard prevents
    // our own frame-by-frame LayoutParams writes from re-triggering themselves.
    private SpringAnimation capsuleHeightSpring;
    private boolean isCapsuleHeightAnimating = false;

    // Smart link detection — compose-time preview card above input bar.
    private com.callx.app.chat.ui.ComposeLinkPreviewController composeLinkPreview;

    private void setupInputBar() {
        setupInputCapsuleAnimations();

        if (binding.llLinkPreviewBar != null) {
            composeLinkPreview = new com.callx.app.chat.ui.ComposeLinkPreviewController(
                    binding.etMessage,
                    binding.llLinkPreviewBar,
                    binding.ivLinkPreviewThumb,
                    binding.tvLinkPreviewDomain,
                    binding.tvLinkPreviewTitle,
                    binding.btnCancelLinkPreview);
        }

        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                // PERF: this fires on every keystroke. s.toString() copies the
                // whole buffer (up to MAX_MESSAGE_LENGTH chars) — compute it
                // once and reuse everywhere below instead of calling it 3x.
                String text = s.toString();
                boolean hasText = !text.trim().isEmpty();
                animateSendMicSwap(hasText);
                animateAttachCameraIcons(!hasText);

                int remaining = MAX_MESSAGE_LENGTH - s.length();
                if (binding.tvCharCount != null) {
                    if (remaining <= 200) {
                        binding.tvCharCount.setVisibility(View.VISIBLE);
                        binding.tvCharCount.setText(s.length() + "/" + MAX_MESSAGE_LENGTH);
                        binding.tvCharCount.setTextColor(ContextCompat.getColor(
                                ChatActivity.this,
                                remaining < 0 ? R.color.error_red : R.color.text_muted));
                    } else {
                        binding.tvCharCount.setVisibility(View.GONE);
                    }
                }
                binding.etMessage.setError(remaining < 0
                        ? "Limit exceeded! (" + Math.abs(remaining) + " extra)"
                        : null);

                if (hasText) {
                    if (presenceController != null) presenceController.setOurTypingStatus(true);
                    typingHandler.removeCallbacks(stopTypingRunnable);
                    typingHandler.postDelayed(stopTypingRunnable, 2000);
                } else {
                    typingHandler.removeCallbacks(stopTypingRunnable);
                    if (presenceController != null) presenceController.setOurTypingStatus(false);
                }
                if (liveTypingController != null) liveTypingController.onOurTextChanged(text);
                if (composeLinkPreview != null) composeLinkPreview.onTextChanged(text);
            }
        });

        binding.btnSend.setOnClickListener(v -> sendTextMessage());
        binding.btnSend.setOnLongClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            String text = binding.etMessage.getText().toString().trim();
            scheduledSendController.showSchedulePicker(text, () -> {
                binding.etMessage.setText("");
                if (presenceController != null) presenceController.clearOurTypingStatus();
                if (liveTypingController != null) liveTypingController.clearOurPreview();
                if (composeLinkPreview != null) composeLinkPreview.reset();
                ioExecutor.execute(() -> {
                    if (db != null && chatId != null) db.chatDao().saveDraft(chatId, "");
                });
            });
            return true;
        });
        binding.btnAttach.setOnClickListener(v -> mediaController.showAttachSheet());
        binding.btnViewOnce.setOnClickListener(v -> showViewOnceExpiryPicker());
        binding.btnCamera.setOnClickListener(v -> mediaController.launchCamera());

        if (binding.btnCancelReply != null)
            binding.btnCancelReply.setOnClickListener(v -> clearReply());

        if (binding.etMessage instanceof GifAwareEditText) {
            ((GifAwareEditText) binding.etMessage).setGifReceivedListener(contentInfo -> {
                contentInfo.requestPermission();
                mediaController.sendGifMessage(contentInfo.getContentUri(), contentInfo);
            });
            ((GifAwareEditText) binding.etMessage).setPasteAsFileListener((pastedText, insertAsText) -> {
                new AlertDialog.Builder(this)
                        .setTitle("Large paste detected")
                        .setMessage("You pasted " + pastedText.length() + " characters. Send as text or as a .txt file?")
                        .setPositiveButton("Send as Text", (d, w) -> insertAsText.run())
                        .setNegativeButton("Send as .txt file", (d, w) -> sendPastedTextAsFile(pastedText))
                        .setNeutralButton("Cancel", null)
                        .show();
            });
        }
    }

    /**
     * Writes a large pasted string out to a temp .txt file and sends it
     * through the same attachment pipeline a picked document uses — the
     * "Send as .txt file" choice from the paste-detection prompt above.
     */
    private void sendPastedTextAsFile(String text) {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "pasted_text");
            if (!dir.exists()) dir.mkdirs();
            String fileName = "Pasted text " + new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH-mm-ss", java.util.Locale.getDefault()).format(new java.util.Date()) + ".txt";
            java.io.File file = new java.io.File(dir, fileName);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
                fos.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            mediaController.uploadAndSend(uri, "file", "raw", fileName);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't send as file", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // INPUT CAPSULE ANIMATIONS — smooth multi-line grow + Telegram/Instagram
    // style icon fade-out as the user types.
    // ─────────────────────────────────────────────────────────────────────

    private float dpToPxInput(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    /**
     * Makes the capsule smoothly animate its own height whenever the
     * EditText grows/shrinks between 1 and 4 lines, instead of the height
     * snapping instantly on every layout pass.
     *
     * PERF FIX: the previous version drove this with LayoutParams.height on
     * every spring frame, i.e. a setLayoutParams() -> requestLayout() call
     * ~60x/sec. requestLayout() forces a full measure+layout traversal of
     * the whole window (every ViewGroup.onLayout() in the tree runs again,
     * including rv_messages), which is real jank while messages are on
     * screen. Real height/position never needs to be touched frame-by-frame
     * here: Android already lays the capsule out at its true final size in
     * a single pass (that's what fires this listener). All the animation
     * has to do is *reveal* that already-correct layout gradually.
     *
     * So instead we let the real layout stand untouched, and only clip the
     * capsule's drawn region from the old height up to the new height via
     * View.setClipBounds(). clipBounds only invalidates + redraws that one
     * view (RenderNode property, GPU-composited) -- it never calls
     * requestLayout, so rv_messages and everything else is untouched during
     * the whole animation.
     */
    private void setupInputCapsuleAnimations() {
        binding.cvInputCapsule.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            if (isCapsuleHeightAnimating) return; // avoid re-entrancy from our own clip updates

            int newHeight = bottom - top;
            int oldHeight = oldBottom - oldTop;
            if (oldHeight <= 0 || newHeight <= 0 || oldHeight == newHeight) return;

            animateCapsuleRevealTo(oldHeight, newHeight);
        });
    }

    /**
     * Springs the *visible* (clipped) region of the capsule from fromHeight
     * up to its already-final toHeight, bottom-anchored (matches the old
     * behaviour: the pill grows upward as lines are added, bottom edge
     * stays put). No LayoutParams / requestLayout involved at any point.
     */
    private void animateCapsuleRevealTo(int fromHeight, int toHeight) {
        final View capsule = binding.cvInputCapsule;
        final int width = capsule.getWidth();
        if (width <= 0) return;

        if (capsuleHeightSpring != null) capsuleHeightSpring.cancel();
        isCapsuleHeightAnimating = true;

        final Rect clip = new Rect(0, Math.max(0, toHeight - fromHeight), width, toHeight);
        capsule.setClipBounds(clip);

        FloatValueHolder heightHolder = new FloatValueHolder(fromHeight);
        capsuleHeightSpring = new SpringAnimation(heightHolder);
        capsuleHeightSpring.setSpring(new SpringForce(toHeight)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        capsuleHeightSpring.setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_PIXELS);
        capsuleHeightSpring.addUpdateListener((animation, value, velocity) -> {
            int h = Math.round(value);
            clip.set(0, Math.max(0, toHeight - h), width, toHeight);
            capsule.setClipBounds(clip); // draw-only, no layout pass
        });
        capsuleHeightSpring.addEndListener((animation, canceled, value, velocity) -> {
            isCapsuleHeightAnimating = false;
            capsule.setClipBounds(null); // release clip so future real layouts aren't cropped
        });
        capsuleHeightSpring.start();
    }

    /**
     * Crossfades the mic <-> send buttons instead of an instant GONE/VISIBLE
     * jump-cut.
     */
    private void animateSendMicSwap(boolean hasText) {
        animateIconTo(binding.btnSend, hasText);
        animateIconTo(binding.btnMic, !hasText);
    }

    /**
     * Telegram/Instagram-style: attach + camera icons shrink & fade away as
     * soon as text is typed, freeing up room for the multi-line input, and
     * smoothly grow back in when the text is cleared.
     */
    private void animateAttachCameraIcons(boolean expand) {
        if (inputIconsExpanded != null && inputIconsExpanded == expand) return;
        inputIconsExpanded = expand;
        animateIconTo(binding.btnAttach, expand);
        animateIconTo(binding.btnCamera, expand);
    }

    // Shared overshoot interpolator for the icon "pop back in" bounce.
    // Tension 2.2 gives a lively but tasteful overshoot -- noticeably springy
    // without wobbling past a natural finger-tap target size.
    private static final OvershootInterpolator ICON_EXPAND_OVERSHOOT = new OvershootInterpolator(2.2f);

    /**
     * Animates a single icon button between fully shown (original width,
     * alpha 1, scale 1) and fully collapsed (width 0, alpha 0, scale 0.6).
     *
     * Collapsing (text typed in) stays a clean shrink+fade -- no bounce --
     * since that motion is about ceding space quickly and a bounce there
     * reads as jittery. Expanding (text cleared) overshoots slightly past
     * full size before settling, which reads as a lively "pop" back in.
     */
    private void animateIconTo(final ImageButton icon, boolean expand) {
        if (icon == null) return;

        Integer fullWidth = (Integer) icon.getTag(icon.getId());
        if (fullWidth == null) {
            ViewGroup.LayoutParams initialLp = icon.getLayoutParams();
            fullWidth = initialLp.width > 0 ? initialLp.width : (int) dpToPxInput(34);
            icon.setTag(icon.getId(), fullWidth);
        }
        final int targetWidth = fullWidth;

        boolean currentlyVisible = icon.getVisibility() == View.VISIBLE && icon.getAlpha() > 0.4f;
        if (currentlyVisible == expand) return;

        Object runningTag = icon.getTag();
        if (runningTag instanceof ValueAnimator) {
            ((ValueAnimator) runningTag).cancel();
        }

        float startFraction = icon.getVisibility() == View.VISIBLE
                ? Math.max(0f, Math.min(1f, icon.getAlpha())) : 0f;
        float endFraction = expand ? 1f : 0f;

        ValueAnimator animator = ValueAnimator.ofFloat(startFraction, endFraction);
        animator.setDuration(expand ? 320 : 200);
        animator.setInterpolator(expand ? ICON_EXPAND_OVERSHOOT : new DecelerateInterpolator());
        icon.setTag(animator);
        if (expand) icon.setVisibility(View.VISIBLE);
        animator.addUpdateListener(a -> {
            // f can briefly exceed 1 during the overshoot portion of the
            // expand curve -- that's intentional, it's what drives the bounce.
            float f = (float) a.getAnimatedValue();
            float alphaClamped = Math.max(0f, Math.min(1f, f));
            icon.setAlpha(alphaClamped);
            icon.setScaleX(0.6f + 0.4f * f);
            icon.setScaleY(0.6f + 0.4f * f);
            ViewGroup.LayoutParams lp = icon.getLayoutParams();
            lp.width = Math.max(1, Math.round(targetWidth * Math.max(0f, f)));
            icon.setLayoutParams(lp);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (!expand) {
                    icon.setVisibility(View.GONE);
                } else {
                    // Land exactly on the resting values in case the overshoot
                    // curve's last emitted frame wasn't precisely 1.0.
                    icon.setScaleX(1f);
                    icon.setScaleY(1f);
                    icon.setAlpha(1f);
                    ViewGroup.LayoutParams lp = icon.getLayoutParams();
                    lp.width = targetWidth;
                    icon.setLayoutParams(lp);
                }
            }
        });
        animator.start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // TYPING TIMEOUT CALLBACK
    // ─────────────────────────────────────────────────────────────────────

    private void onStopTypingTimeout() {
        if (presenceController != null) presenceController.setOurTypingStatus(false);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SEND TEXT MESSAGE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Feature 2 — Expiry timer picker.
     *
     * Options:
     *  • "Expire with View Once" (-1) — delete on open only, no timer (classic view-once)
     *  • "1 hour / 6 hours / 24 hours / 3 days / 7 days" — delete on open OR when timer runs out,
     *    whichever comes first.
     *
     * selectedViewOnceExpiryMs == -1  → no timer, delete only on receiver open
     * selectedViewOnceExpiryMs >  0   → timer set; also delete on open if receiver opens before timer
     */
    private void showViewOnceExpiryPicker() {
        String[] options  = {
            "Expire with View Once",   // delete on open, no timer
            "1 hour",
            "6 hours",
            "24 hours",
            "3 days",
            "7 days"
        };
        // -1 = "expire on view" (no timer). >0 = timer duration in ms.
        long[] durations = {-1L, 3_600_000L, 21_600_000L, 86_400_000L, 259_200_000L, 604_800_000L};

        com.callx.app.utils.AlertDialogStyler.showRounded(
            new android.app.AlertDialog.Builder(this)
                .setTitle("View Once — Expiry")
                .setItems(options, (d, which) -> {
                    selectedViewOnceExpiryMs = durations[which];
                    setViewOnceMode(true);
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    if (isViewOnceModeOn) {
                        setViewOnceMode(false);
                        selectedViewOnceExpiryMs = 0L;
                    }
                })
                .create());
    }

    /**
     * Toggles "View Once" send mode. Auto-resets to off after one send.
     */
    private void setViewOnceMode(boolean on) {
        isViewOnceModeOn = on;
        if (binding == null || binding.btnViewOnce == null) return;
        binding.btnViewOnce.setColorFilter(on
                ? android.graphics.Color.parseColor("#FF6200EE")   // active tint
                : android.graphics.Color.parseColor("#FF8A8A8A")); // idle/grey tint
        binding.etMessage.setHint(on ? "View once message…" : getString(R.string.hint_message));
    }

    /**
     * Feature 2: Schedule a WorkManager job to expire the view-once message
     * after delayMs. The Worker checks that viewOnceState is still "sent"
     * before calling markExpiredByTimer(), so already-opened messages are safe.
     */
    private void sendTextMessage() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_MESSAGE_LENGTH) {
            binding.etMessage.setError("Message too long! Max " + MAX_MESSAGE_LENGTH + " characters allowed.");
            Toast.makeText(this, "Message too long!", Toast.LENGTH_SHORT).show();
            return;
        }
        // Dismiss @mention suggestions before clearing the field
        if (mentionController != null) mentionController.dismissSuggestions();
        binding.etMessage.setText("");
        if (presenceController != null) presenceController.clearOurTypingStatus();
        if (liveTypingController != null) liveTypingController.clearOurPreview();
        ioExecutor.execute(() -> {
            if (db != null && chatId != null) db.chatDao().saveDraft(chatId, "");
        });
        Message m = buildOutgoing();
        m.type = "text";
        m.fontStyle = 0;
        m.text = text;
        pushMessage(m, text);
        clearReply();
        if (composeLinkPreview != null) composeLinkPreview.reset();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MARK READ
    // ─────────────────────────────────────────────────────────────────────

    private void markMessagesReadOnOpen() {
        if (presenceController != null) presenceController.markMessagesRead();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MESSAGE ACTIONS
    // ─────────────────────────────────────────────────────────────────────

    private void copyText(Message m) {
        if (m.text == null || m.text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message", m.text));
            Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // VIEW ONCE — AlertDialog viewer (content shown inside dialog; deleted on close)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Shows view-once content inside an AlertDialog.
     * When the user dismisses the dialog (any way: OK / back / outside tap),
     * the message is permanently deleted from Firebase AND local DB on both sides.
     *
     * Supports: text, image, video, audio, file.
     * FLAG_SECURE is already set by ChatViewOnceController before this runs.
     */
    private void showViewOnceDialog(@NonNull com.callx.app.models.Message message) {
        if (isFinishing() || isDestroyed()) return;

        String msgType = message.type != null ? message.type : "text";
        String msgId   = message.messageId != null ? message.messageId : message.id;

        // Build dialog container
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(48, 32, 48, 16);
        container.setGravity(android.view.Gravity.CENTER);

        // ── Header icon + label ────────────────────────────────────────────
        android.widget.TextView tvHeader = new android.widget.TextView(this);
        tvHeader.setText("🔒  View Once");
        tvHeader.setTextSize(13f);
        tvHeader.setTextColor(0xFF888888);
        tvHeader.setGravity(android.view.Gravity.CENTER);
        tvHeader.setPadding(0, 0, 0, 12);
        container.addView(tvHeader);

        // References for cleanup
        final android.widget.ImageView[] ivHolder  = {null};
        final android.media.MediaPlayer[] mpHolder = {null};
        final android.widget.VideoView[]  vvHolder = {null};
        // Guard: ensure cleanup+delete runs exactly once
        final boolean[] dismissed = {false};

        switch (msgType) {

            case "image": {
                android.widget.ImageView iv = new android.widget.ImageView(this);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                int imgSize = (int) (getResources().getDisplayMetrics().widthPixels * 0.75f);
                android.widget.LinearLayout.LayoutParams lpImg =
                        new android.widget.LinearLayout.LayoutParams(imgSize, imgSize);
                lpImg.gravity = android.view.Gravity.CENTER;
                iv.setLayoutParams(lpImg);
                ivHolder[0] = iv;
                container.addView(iv);
                if (message.mediaUrl != null) {
                    com.bumptech.glide.Glide.with(this)
                            .load(message.mediaUrl)
                            .skipMemoryCache(true)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                            .override(720, 720)
                            .into(iv);
                }
                break;
            }

            case "video": {
                android.widget.VideoView vv = new android.widget.VideoView(this);
                int vidW = (int) (getResources().getDisplayMetrics().widthPixels * 0.75f);
                int vidH = (int) (vidW * 9f / 16f);
                android.widget.LinearLayout.LayoutParams lpVid =
                        new android.widget.LinearLayout.LayoutParams(vidW, vidH);
                lpVid.gravity = android.view.Gravity.CENTER;
                vv.setLayoutParams(lpVid);
                vvHolder[0] = vv;
                container.addView(vv);
                if (message.mediaUrl != null) {
                    vv.setVideoURI(android.net.Uri.parse(message.mediaUrl));
                    vv.start();
                }
                break;
            }

            case "audio": {
                // Play/stop button + duration label
                android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, 8, 0, 8);

                android.widget.ImageButton btnPlay = new android.widget.ImageButton(this);
                btnPlay.setImageResource(android.R.drawable.ic_media_play);
                btnPlay.setBackground(null);
                btnPlay.setPadding(8, 8, 8, 8);
                row.addView(btnPlay);

                android.widget.TextView tvDur = new android.widget.TextView(this);
                long durMs = message.duration != null ? message.duration : 0L;
                tvDur.setText(durMs > 0 ? formatDurationVo(durMs) : "--:--");
                tvDur.setTextSize(16f);
                tvDur.setPadding(16, 0, 0, 0);
                row.addView(tvDur);
                container.addView(row);

                if (message.mediaUrl != null) {
                    android.media.MediaPlayer mp = new android.media.MediaPlayer();
                    mpHolder[0] = mp;
                    try {
                        mp.setDataSource(message.mediaUrl);
                        mp.prepareAsync();
                        mp.setOnPreparedListener(prepared -> {
                            prepared.start();
                            btnPlay.setImageResource(android.R.drawable.ic_media_pause);
                        });
                        mp.setOnCompletionListener(done ->
                                btnPlay.setImageResource(android.R.drawable.ic_media_play));
                        btnPlay.setOnClickListener(v -> {
                            if (mp.isPlaying()) {
                                mp.pause();
                                btnPlay.setImageResource(android.R.drawable.ic_media_play);
                            } else {
                                mp.start();
                                btnPlay.setImageResource(android.R.drawable.ic_media_pause);
                            }
                        });
                    } catch (Exception ignored) {}
                }
                break;
            }

            case "multi_media": {
                // #9 fix — grouped/multi-image view-once messages previously
                // fell through to the generic text/default branch (blank
                // dialog). Now shown as a simple swipeable ViewPager2 of
                // every item, same "tap to open, gone on close" contract as
                // the single image/video cases above.
                java.util.List<java.util.Map<String, Object>> items = message.mediaItems;
                if (items != null && !items.isEmpty()) {
                    int size = (int) (getResources().getDisplayMetrics().widthPixels * 0.75f);
                    androidx.viewpager2.widget.ViewPager2 pager =
                            new androidx.viewpager2.widget.ViewPager2(this);
                    android.widget.LinearLayout.LayoutParams lpPager =
                            new android.widget.LinearLayout.LayoutParams(size, size);
                    lpPager.gravity = android.view.Gravity.CENTER;
                    pager.setLayoutParams(lpPager);

                    pager.setAdapter(new androidx.recyclerview.widget.RecyclerView.Adapter<
                            androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                        @Override public int getItemCount() { return items.size(); }
                        @androidx.annotation.NonNull @Override
                        public androidx.recyclerview.widget.RecyclerView.ViewHolder onCreateViewHolder(
                                @androidx.annotation.NonNull android.view.ViewGroup parent, int viewType) {
                            android.widget.ImageView iv = new android.widget.ImageView(parent.getContext());
                            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                            iv.setLayoutParams(new android.view.ViewGroup.LayoutParams(
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                    android.view.ViewGroup.LayoutParams.MATCH_PARENT));
                            return new androidx.recyclerview.widget.RecyclerView.ViewHolder(iv) {};
                        }
                        @Override public void onBindViewHolder(
                                @androidx.annotation.NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder h, int pos) {
                            Object urlObj = items.get(pos).get("url");
                            android.widget.ImageView iv = (android.widget.ImageView) h.itemView;
                            iv.setContentDescription("Photo " + (pos + 1) + " of " + items.size());
                            if (urlObj instanceof String) {
                                com.bumptech.glide.Glide.with(iv.getContext())
                                        .load((String) urlObj)
                                        .skipMemoryCache(true)
                                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                                        .override(720, 720)
                                        .into(iv);
                            }
                        }
                    });
                    container.addView(pager);

                    if (items.size() > 1) {
                        android.widget.TextView tvCounter = new android.widget.TextView(this);
                        tvCounter.setTextSize(11f);
                        tvCounter.setTextColor(0xFF888888);
                        tvCounter.setGravity(android.view.Gravity.CENTER);
                        tvCounter.setPadding(0, 6, 0, 0);
                        tvCounter.setText("1 / " + items.size());
                        container.addView(tvCounter);
                        pager.registerOnPageChangeCallback(
                                new androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
                                    @Override public void onPageSelected(int position) {
                                        tvCounter.setText((position + 1) + " / " + items.size());
                                    }
                                });
                    }
                } else {
                    android.widget.TextView tvEmpty = new android.widget.TextView(this);
                    tvEmpty.setText("\uD83D\uDCF7 Photos");
                    tvEmpty.setTextSize(16f);
                    tvEmpty.setGravity(android.view.Gravity.CENTER);
                    container.addView(tvEmpty);
                }
                break;
            }

            case "file": {
                android.widget.TextView tvFile = new android.widget.TextView(this);
                tvFile.setText("📄  " + (message.fileName != null ? message.fileName : "File"));
                tvFile.setTextSize(16f);
                tvFile.setGravity(android.view.Gravity.CENTER);
                tvFile.setPadding(0, 8, 0, 8);
                container.addView(tvFile);
                break;
            }

            default: {
                // text (and anything else)
                android.widget.TextView tvContent = new android.widget.TextView(this);
                tvContent.setText(message.text != null ? message.text : "");
                tvContent.setTextSize(17f);
                tvContent.setGravity(android.view.Gravity.CENTER);
                tvContent.setPadding(0, 8, 0, 8);
                container.addView(tvContent);
                break;
            }
        }

        // ── Warning footer ─────────────────────────────────────────────────
        android.widget.TextView tvWarn = new android.widget.TextView(this);
        tvWarn.setText("⚠️  This message will be permanently deleted when you close this dialog.");
        tvWarn.setTextSize(11f);
        tvWarn.setTextColor(0xFFAA4444);
        tvWarn.setGravity(android.view.Gravity.CENTER);
        tvWarn.setPadding(0, 20, 0, 0);
        container.addView(tvWarn);

        // Helper: release media + trigger permanent delete — runs exactly once
        Runnable doCleanupAndDelete = () -> {
            if (dismissed[0]) return;
            dismissed[0] = true;

            // Release Glide image
            if (ivHolder[0] != null && !isDestroyed()) {
                try { com.bumptech.glide.Glide.with(this).clear(ivHolder[0]); } catch (Exception ignored) {}
            }
            // Release VideoView
            if (vvHolder[0] != null) {
                try { vvHolder[0].stopPlayback(); vvHolder[0].setVideoURI(null); } catch (Exception ignored) {}
            }
            // Release MediaPlayer
            if (mpHolder[0] != null) {
                try { if (mpHolder[0].isPlaying()) mpHolder[0].stop(); } catch (Exception ignored) {}
                try { mpHolder[0].release(); } catch (Exception ignored) {}
                mpHolder[0] = null;
            }
            // Trigger permanent delete on both sides + notify sender (viewed silent push)
            if (viewOnceController != null && msgId != null) {
                viewOnceController.onViewerClosed(msgId, message.senderId);
            }
        };

        // ── Build dialog ───────────────────────────────────────────────────
        // setCancelable(false): back button and outside tap do NOT dismiss.
        // User must explicitly tap "Close & Delete". This prevents accidental
        // loss before user has finished viewing.
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("View Once Message")
                .setView(container)
                .setPositiveButton("Close & Delete", null) // wired below after show()
                .setCancelable(false)
                .create();

        // SECURITY: Block screenshot/screen-record inside dialog too (defence-in-depth).
        // FLAG_SECURE is already set by ChatViewOnceController, but dialog windows
        // can have a separate surface in some OEM builds — set it here explicitly.
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            // ROUNDED CORNERS: replace default AlertDialog background with rounded drawable.
            // Must be set before show() so the window layout pass picks it up.
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        // Wire positive button AFTER show() to prevent auto-dismiss on click.
        // We dismiss manually after cleanup so no race condition with dismiss listener.
        dialog.setOnShowListener(d -> {
            // Apply rounded background on the dialog's decorView after it is shown
            if (dialog.getWindow() != null) {
                dialog.getWindow().getDecorView().setBackground(
                        androidx.core.content.ContextCompat.getDrawable(
                                this, com.callx.app.chat.R.drawable.bg_view_once_dialog));
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                  .setOnClickListener(v -> {
                      doCleanupAndDelete.run();
                      dialog.dismiss();
                  });
        });

        // Dismiss listener is a safety net: if dialog is dismissed by any other
        // means (e.g., activity finish, system), ensure cleanup still runs.
        dialog.setOnDismissListener(d -> {
            doCleanupAndDelete.run();
            activeViewOnceDialog = null; // Feature 1: clear reference on any dismiss
        });

        dialog.show();
        activeViewOnceDialog = dialog; // Feature 1: track for onPause auto-dismiss
    }

    /**
     * Feature 5: Scroll RecyclerView to the message with the given ID.
     * Called after notification tap. Does NOT open view-once content automatically.
     */
    private void scrollToMessageById(String targetMsgId) {
        if (pagingAdapter == null || targetMsgId == null) return;
        int count = pagingAdapter.getItemCount();
        for (int i = 0; i < count; i++) {
            com.callx.app.models.Message m = pagingAdapter.peek(i);
            if (m == null) continue;
            String id = m.messageId != null ? m.messageId : m.id;
            if (targetMsgId.equals(id)) {
                binding.rvMessages.scrollToPosition(i);
                return;
            }
        }
    }

    /** Format milliseconds → M:SS for view-once audio label. */
    private static String formatDurationVo(long ms) {
        long secs = ms / 1000;
        long mins = secs / 60;
        secs = secs % 60;
        return String.format(java.util.Locale.US, "%d:%02d", mins, secs);
    }

    // editMessage() moved to MessageEditHistoryController#editMessage —
    // now also pushes the pre-edit text into editHistory (see controller).

    // toggleStar() moved to ChatStarredController#toggleStar — same
    // Firebase + Room write, just no longer inline on the Activity.

    // sendReaction() moved to ChatReactionController#toggleReaction — now
    // also writes through to Room (reactionsJson) and supports un-reacting
    // by tapping the same emoji again.

    private void confirmDeleteMessage(Message m) {
        boolean isMine = currentUid != null && currentUid.equals(m.senderId);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Delete message").setNegativeButton("Cancel", null);
        if (isMine) {
            builder.setPositiveButton("Delete for everyone", (d, w) -> {
                        messagesRef.child(m.id).child("deleted").setValue(true);
                        messagesRef.child(m.id).child("text").setValue("");
                        ioExecutor.execute(() -> db.messageDao().softDelete(m.id));
                        LastMessagesCache.getInstance().removeMessage(chatId, m.id);
                    })
                    .setNeutralButton("Delete for me", (d, w) -> {
                        ioExecutor.execute(() -> db.messageDao().softDelete(m.id));
                        LastMessagesCache.getInstance().removeMessage(chatId, m.id);
                    });
        } else {
            builder.setMessage("Delete this message for you only?")
                    .setPositiveButton("Delete for me", (d, w) -> {
                        ioExecutor.execute(() -> db.messageDao().softDelete(m.id));
                        LastMessagesCache.getInstance().removeMessage(chatId, m.id);
                    });
        }
        com.callx.app.utils.AlertDialogStyler.showRounded(builder.create(),
                com.callx.app.utils.AlertDialogStyler.DialogSize.WIDE);
    }

    private void forwardMessage(Message m) {
        Intent i = new Intent().setClassName(this, "com.callx.app.activities.ContactsActivity");
        i.putExtra("forwardText",  m.text);
        i.putExtra("forwardType",  m.type != null ? m.type : "text");
        i.putExtra("forwardMedia", m.mediaUrl);
        i.putExtra("forwardFileName", m.fileName);
        // ── multi_media: pass the full mediaItems group so a tap on
        // "Forward" (whole group, no quick-forward subset) sends every
        // image/video together, not just the first one ──
        if ("multi_media".equals(m.type) && m.mediaItems != null && !m.mediaItems.isEmpty()) {
            i.putExtra("forwardMediaItemsJson",
                    com.callx.app.utils.MediaItemsJsonUtil.mediaItemsToJson(m.mediaItems));
            i.putExtra("forwardCaption", m.caption);
        }
        // ── reel_share: pass all card fields so forwarded card renders correctly ──
        if ("reel_share".equals(m.type)) {
            i.putExtra("forwardReelId",            m.reelId             != null ? m.reelId             : "");
            i.putExtra("forwardReelShareUrl",       m.reelShareUrl       != null ? m.reelShareUrl       : "");
            i.putExtra("forwardReelShareThumb",     m.reelShareThumb     != null ? m.reelShareThumb     : "");
            i.putExtra("forwardReelShareCaption",   m.reelShareCaption   != null ? m.reelShareCaption   : "");
            i.putExtra("forwardReelShareUsername",  m.reelShareUsername  != null ? m.reelShareUsername  : "");
            i.putExtra("forwardReelShareOwnerPhoto",m.reelShareOwnerPhoto!= null ? m.reelShareOwnerPhoto: "");
        }
        startActivity(i);
    }

    /**
     * Forward called from MediaViewerActivity's gallery selection toolbar
     * (#1 — select/multi-forward gallery se nahi hota — fixed). If
     * {@code indices} is empty, the WHOLE group is forwarded (mirrors
     * forwardMessage()'s multi_media branch); otherwise only the selected
     * items are bundled into a subset and forwarded.
     */
    private void forwardGalleryMessage(Message groupMessage, java.util.List<Integer> indices) {
        if (groupMessage.mediaItems == null || groupMessage.mediaItems.isEmpty()) return;
        if (indices == null || indices.isEmpty()) {
            forwardMessage(groupMessage);
            return;
        }
        java.util.List<java.util.Map<String, Object>> subset = new ArrayList<>();
        for (Integer idx : indices) {
            if (idx != null && idx >= 0 && idx < groupMessage.mediaItems.size()) {
                subset.add(groupMessage.mediaItems.get(idx));
            }
        }
        if (subset.isEmpty()) return;
        Message subsetMsg = new Message();
        subsetMsg.type = "multi_media";
        subsetMsg.mediaItems = subset;
        subsetMsg.caption = subset.size() == groupMessage.mediaItems.size() ? groupMessage.caption : null;
        forwardMessage(subsetMsg);
    }

    /**
     * Applies a per-item delete/star/caption-edit action requested from the
     * gallery (#4, #2) by mutating the group's mediaItems list and pushing
     * the updated array to Firebase. If a delete empties the group entirely,
     * the whole message is soft-deleted instead of left as an empty bubble.
     */
    private void applyGalleryItemAction(Message groupMessage, GalleryItemActionBridge.PendingAction action) {
        if (groupMessage.mediaItems == null || action.itemIndex < 0
                || action.itemIndex >= groupMessage.mediaItems.size()) return;
        String msgId = groupMessage.id != null && !groupMessage.id.isEmpty()
                ? groupMessage.id : groupMessage.messageId;
        if (msgId == null || messagesRef == null) return;

        java.util.List<java.util.Map<String, Object>> updated =
                new ArrayList<>(groupMessage.mediaItems);

        switch (action.action) {
            case GalleryItemActionBridge.ACTION_DELETE_ITEM: {
                updated.remove(action.itemIndex);
                if (updated.isEmpty()) {
                    // Last item removed — delete the whole message like a
                    // normal single-media delete (consistent with existing
                    // confirmDeleteMessage() soft-delete behavior).
                    messagesRef.child(msgId).child("deleted").setValue(true);
                    messagesRef.child(msgId).child("text").setValue("");
                    messagesRef.child(msgId).child("mediaItems").setValue(null);
                } else {
                    messagesRef.child(msgId).child("mediaItems").setValue(updated);
                }
                Toast.makeText(this, "Photo removed", Toast.LENGTH_SHORT).show();
                break;
            }
            case GalleryItemActionBridge.ACTION_STAR_ITEM:
            case GalleryItemActionBridge.ACTION_UNSTAR_ITEM: {
                java.util.Map<String, Object> item =
                        new java.util.LinkedHashMap<>(updated.get(action.itemIndex));
                item.put("starred", GalleryItemActionBridge.ACTION_STAR_ITEM.equals(action.action));
                updated.set(action.itemIndex, item);
                messagesRef.child(msgId).child("mediaItems").setValue(updated);
                Toast.makeText(this,
                        GalleryItemActionBridge.ACTION_STAR_ITEM.equals(action.action) ? "Starred" : "Unstarred",
                        Toast.LENGTH_SHORT).show();
                break;
            }
            case GalleryItemActionBridge.ACTION_EDIT_CAPTION: {
                java.util.Map<String, Object> item =
                        new java.util.LinkedHashMap<>(updated.get(action.itemIndex));
                if (action.caption == null || action.caption.isEmpty()) {
                    item.remove("caption");
                } else {
                    item.put("caption", action.caption);
                }
                updated.set(action.itemIndex, item);
                messagesRef.child(msgId).child("mediaItems").setValue(updated);
                break;
            }
            default: break;
        }
    }

    private void forwardSelectedMessages() {
        List<Message> selected = pagingAdapter.getSelectedMessages();
        if (selected.isEmpty()) { Toast.makeText(this, "Koi message select nahi", Toast.LENGTH_SHORT).show(); return; }
        ArrayList<String> texts = new ArrayList<>(), types = new ArrayList<>(),
                medias = new ArrayList<>(), fileNames = new ArrayList<>();
        // #2 fix — multi-select forward of a multi_media (grouped) message
        // was flattening it down to just m.mediaUrl (first item only) and
        // dropping mediaItems entirely, so the forwarded bubble showed as
        // a plain "📷 Photos" text fallback instead of the gallery grid.
        // These two parallel arrays carry the full group + caption through
        // ContactsActivity so the receiving side can rebuild it properly.
        ArrayList<String> mediaItemsJsonList = new ArrayList<>(), captions = new ArrayList<>();
        selected.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        for (Message m : selected) {
            texts.add(m.text != null ? m.text : ""); types.add(m.type != null ? m.type : "text");
            medias.add(m.mediaUrl != null ? m.mediaUrl : ""); fileNames.add(m.fileName != null ? m.fileName : "");
            boolean isGroup = "multi_media".equals(m.type) && m.mediaItems != null && !m.mediaItems.isEmpty();
            mediaItemsJsonList.add(isGroup
                    ? com.callx.app.utils.MediaItemsJsonUtil.mediaItemsToJson(m.mediaItems) : "");
            captions.add(isGroup && m.caption != null ? m.caption : "");
        }
        Intent i = new Intent().setClassName(this, "com.callx.app.activities.ContactsActivity");
        i.putStringArrayListExtra("forwardTexts", texts); i.putStringArrayListExtra("forwardTypes", types);
        i.putStringArrayListExtra("forwardMedias", medias); i.putStringArrayListExtra("forwardFileNames", fileNames);
        i.putStringArrayListExtra("forwardMediaItemsJsonList", mediaItemsJsonList);
        i.putStringArrayListExtra("forwardCaptionsList", captions);
        startActivity(i);
        pagingAdapter.exitMultiSelectMode();
    }

    // ─────────────────────────────────────────────────────────────────────
    // SELECTION TOOLBAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupSelectionToolbar() {
        if (selectionToolbarSetup) return;
        selectionToolbarSetup = true;

        View btnClose = binding.getRoot().findViewById(com.callx.app.chat.R.id.btn_selection_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> { pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar(); });
        View btnFwd = binding.getRoot().findViewById(com.callx.app.chat.R.id.btn_selection_forward);
        if (btnFwd != null) btnFwd.setOnClickListener(v -> forwardSelectedMessages());
        View btnDel = binding.getRoot().findViewById(com.callx.app.chat.R.id.btn_selection_delete);
        if (btnDel != null) btnDel.setOnClickListener(v -> {
            List<Message> sel = pagingAdapter.getSelectedMessages();
            if (sel.isEmpty()) return;
            String msg = sel.size() == 1 ? "Delete this message?" : "Delete " + sel.size() + " messages?";
            com.callx.app.utils.AlertDialogStyler.showRounded(
                new AlertDialog.Builder(this).setTitle("Delete messages").setMessage(msg)
                    .setPositiveButton("Delete for everyone", (d, w) -> {
                        for (Message m : sel) {
                            messagesRef.child(m.id).child("deleted").setValue(true);
                            messagesRef.child(m.id).child("text").setValue("");
                            final String mid = m.id;
                            ioExecutor.execute(() -> db.messageDao().softDelete(mid));
                            LastMessagesCache.getInstance().removeMessage(chatId, mid);
                        }
                        pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
                    })
                    .setNeutralButton("Delete for me", (d, w) -> {
                        for (Message m : sel) {
                            final String mid = m.id;
                            ioExecutor.execute(() -> db.messageDao().softDelete(mid));
                            LastMessagesCache.getInstance().removeMessage(chatId, mid);
                        }
                        pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
                    })
                    .setNegativeButton("Cancel", null).create(),
                    com.callx.app.utils.AlertDialogStyler.DialogSize.WIDE);
        });
        View btnStar = binding.getRoot().findViewById(com.callx.app.chat.R.id.btn_selection_star);
        if (btnStar != null) btnStar.setOnClickListener(v -> {
            for (Message m : pagingAdapter.getSelectedMessages()) starredController.toggleStar(m);
            pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
        });
        View btnReply = binding.getRoot().findViewById(com.callx.app.chat.R.id.btn_selection_reply);
        if (btnReply != null) btnReply.setOnClickListener(v -> {
            List<Message> sel = pagingAdapter.getSelectedMessages();
            if (!sel.isEmpty()) startReply(sel.get(0));
            pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
        });
        View btnInfo = binding.getRoot().findViewById(com.callx.app.chat.R.id.btn_selection_info);
        if (btnInfo != null) btnInfo.setOnClickListener(v -> {
            List<Message> sel = pagingAdapter.getSelectedMessages();
            if (sel.size() == 1) showMessageInfoDialog(sel.get(0));
            pagingAdapter.exitMultiSelectMode(); hideMultiSelectBar();
        });
    }

    private void showMultiSelectBar(int count) {
        setupSelectionToolbar();
        binding.toolbar.setVisibility(View.GONE);
        View selBar = binding.getRoot().findViewById(com.callx.app.chat.R.id.ll_selection_toolbar);
        if (selBar != null) selBar.setVisibility(View.VISIBLE);
        android.widget.TextView tvCount = binding.getRoot().findViewById(com.callx.app.chat.R.id.tv_selection_count);
        if (tvCount != null) tvCount.setText(String.valueOf(count));
        View btnInfo = binding.getRoot().findViewById(com.callx.app.chat.R.id.btn_selection_info);
        if (btnInfo != null) btnInfo.setVisibility(count == 1 ? View.VISIBLE : View.GONE);
    }

    private void hideMultiSelectBar() {
        binding.toolbar.setVisibility(View.VISIBLE);
        View selBar = binding.getRoot().findViewById(com.callx.app.chat.R.id.ll_selection_toolbar);
        if (selBar != null) selBar.setVisibility(View.GONE);
    }

    private void showMessageInfoDialog(Message m) {
        if (m == null) return;
        boolean isOutgoing = m.senderId != null && currentUid != null && m.senderId.equals(currentUid);

        com.callx.app.conversation.info.MessageInfoData data = new com.callx.app.conversation.info.MessageInfoData();
        data.isGroup = false;
        data.isOutgoing = isOutgoing;
        data.messageType = m.type != null ? m.type : "text";
        data.previewLabel = com.callx.app.conversation.info.MessageInfoPreviewUtil.buildPreview(m);
        data.sentAt = m.timestamp != null ? m.timestamp : 0L;
        data.deliveredAt = (m.deliveredAt != null && m.deliveredAt > 0) ? m.deliveredAt : null;
        data.readAt = (m.readAt != null && m.readAt > 0) ? m.readAt : null;
        data.incomingStatus = m.status;

        com.callx.app.conversation.info.MessageInfoBridge.set(data);
        com.callx.app.conversation.info.MessageInfoBottomSheet.newInstance()
                .show(getSupportFragmentManager(), com.callx.app.conversation.info.MessageInfoBottomSheet.TAG);
    }

    // ── MessageInfoBottomSheet.HostRecyclerPauseListener ────────────────────
    // The sheet fully covers rvMessages while open — nothing the user does
    // can scroll it — so pause its layout/scroll compute for that interval
    // instead of having both RecyclerViews do layout work in the same frame.
    @Override
    public void onMessageInfoOpened() {
        binding.rvMessages.suppressLayout(true);
    }

    @Override
    public void onMessageInfoClosed() {
        binding.rvMessages.suppressLayout(false);
    }


    // ─────────────────────────────────────────────────────────────────────
    // NAVIGATION
    // ─────────────────────────────────────────────────────────────────────

    private void openAvatarZoom() {
        if (partnerUid == null || partnerUid.isEmpty()) return;
        Intent intent = new Intent().setClassName(this, "com.callx.app.activities.UserProfileActivity");
        intent.putExtra("uid",    partnerUid);
        intent.putExtra("name",   partnerName  != null ? partnerName  : "");
        intent.putExtra("photo",  partnerPhoto != null ? partnerPhoto : "");
        intent.putExtra("chatId", chatId       != null ? chatId       : "");
        startActivity(intent);
    }

    private void openEditProfile() {
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.ProfileActivity");
            startActivity(new Intent(this, cls));
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Edit Profile unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAllMediaLinksDocs() {
        try {
            Class<?> cls = Class.forName("com.callx.app.activities.AllMediaLinksDocsActivity");
            Intent i = new Intent(this, cls);
            i.putExtra("chatId",      chatId);
            i.putExtra("partnerName", partnerName);
            i.putExtra("isGroup",     false);
            startActivity(i);
        } catch (ClassNotFoundException e) {
            android.util.Log.e(TAG, "AllMediaLinksDocsActivity not found", e);
        }
    }

    private void openSmallWindow() {
        Context appCtx = getApplicationContext();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(appCtx)) {
            Intent permIntent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + appCtx.getPackageName()));
            startActivity(permIntent);
            Toast.makeText(this, "'Display over other apps' permission dijiye phir Small Window use karo", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Class<?> svcClass = Class.forName("com.callx.app.smallwindow.SmallWindowService");
            Intent svc = new Intent(appCtx, svcClass);
            svc.putExtra("name",   partnerName != null ? partnerName : "Chat");
            svc.putExtra("status", "CallX Small Window");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                appCtx.startForegroundService(svc);
            } else {
                appCtx.startService(svc);
            }
            moveTaskToBack(true);
        } catch (ClassNotFoundException e) {
            Toast.makeText(this, "Small Window unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // NAVIGATE TO ORIGINAL (reply jump)
    // ─────────────────────────────────────────────────────────────────────

    private void navigateToOriginalMsg(String messageId, @Nullable String senderId) {
        if (messageId == null || messageId.isEmpty()) return;
        // WhatsApp-style: a reply-to-status quote box is not a real chat
        // message (StatusReplyBottomSheet.sendReply stamps replyToId as
        // "status_" + statusId), so searching for it in pagingAdapter/DB
        // like a normal reply always missed and surfaced a confusing
        // "Original message not found" toast. Tapping that quote box
        // should instead open the status itself (same ACTION_OPEN_STATUS
        // deep-link the status_seen bubble uses).
        //
        // BUG FIX: the chat partner is NOT always the status owner. This
        // quote box appears on BOTH sides of the 1:1 chat:
        //   • If I sent this message (I replied/reacted to partner's
        //     status), the partner owns the status.
        //   • If the PARTNER sent this message (they replied/reacted to
        //     MY status), I own the status — this is the case that was
        //     broken, since ownerUid was always hardcoded to partnerUid,
        //     so opening a reply/reaction to your OWN status tried to load
        //     a status from partnerUid's collection that never existed
        //     there, surfacing "This status is no longer available" even
        //     though the status was mine and still live.
        // senderId tells us which side sent the chat message; fall back to
        // the old partner-owns-it assumption only if it's unavailable.
        if (messageId.startsWith("status_")) {
            String ownerUid;
            if (senderId != null && !senderId.isEmpty()) {
                ownerUid = senderId.equals(currentUid) ? partnerUid : currentUid;
            } else {
                ownerUid = partnerUid;
            }
            if (ownerUid == null || ownerUid.isEmpty()) {
                Toast.makeText(this, "This status is no longer available", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean isMine = ownerUid.equals(currentUid);
            String targetStatusId = messageId.substring("status_".length());
            android.content.Intent intent = new android.content.Intent(
                    com.callx.app.utils.Constants.ACTION_OPEN_STATUS);
            intent.putExtra("ownerUid", ownerUid);
            intent.putExtra("ownerName", isMine ? "You" : (partnerName != null ? partnerName : ""));
            intent.putExtra("targetStatusId", targetStatusId);
            intent.setPackage(getPackageName());
            try {
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(this, "Status viewer not available", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        int pos = -1;
        for (int i = 0; i < pagingAdapter.getItemCount(); i++) {
            Message m = pagingAdapter.peek(i);
            if (m != null && (messageId.equals(m.id) || messageId.equals(m.messageId))) {
                pos = i; break;
            }
        }
        if (pos >= 0) {
            MessageHighlightAnimator.scrollAndHighlight(binding.rvMessages, pos, binding.fabBackToLatest);
        } else {
            final String cId = chatId;
            ioExecutor.execute(() -> {
                if (db == null || cId == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Message not in view — scroll up to find it", Toast.LENGTH_SHORT).show());
                    return;
                }
                MessageEntity target = db.messageDao().getMessageById(messageId);
                if (target == null || target.timestamp == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Original message not found", Toast.LENGTH_SHORT).show());
                    return;
                }
                int posFromBottom = db.messageDao().countMessagesAfterTimestamp(cId, target.timestamp);
                int approxPos = pagingAdapter.getItemCount() - posFromBottom - 1;
                final int safePos = Math.max(0, approxPos);
                runOnUiThread(() -> {
                    if (binding.fabBackToLatest != null) {
                        binding.fabBackToLatest.setVisibility(View.VISIBLE);
                        binding.fabBackToLatest.setAlpha(1f);
                        binding.fabBackToLatest.setVisibility(View.VISIBLE);
                    }
                    // scrollToPositionWithOffset centres the target item near the top of
                    // the viewport (offset 0 = flush top). Avoids the jank of scrollToPosition()
                    // which can leave the item partially off-screen and then snap again.
                    LinearLayoutManager jumpLlm =
                            (LinearLayoutManager) binding.rvMessages.getLayoutManager();
                    if (jumpLlm != null) jumpLlm.scrollToPositionWithOffset(safePos, 0);
                    binding.rvMessages.postDelayed(() -> {
                        RecyclerView.ViewHolder vh = binding.rvMessages.findViewHolderForAdapterPosition(safePos);
                        if (vh != null) MessageHighlightAnimator.flashHighlight(vh.itemView);
                    }, 500);
                });
            });
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // SWIPE TO REPLY + FAB
    // ─────────────────────────────────────────────────────────────────────

    // Kept so a reverse-swipe cancel (see onSwipeReplyCancelled below) can
    // dismiss the "Replying to X…" snackbar immediately instead of leaving
    // it on screen for a reply that's no longer actually queued up.
    private Snackbar pendingReplySnackbar;

    // ─────────────────────────────────────────────────────────────────────
    // @MENTION CONTROLLER — 1:1 chat
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Initialize and attach the ChatMentionController.
     * Called after setupInputBar() — partnerUid/partnerName/partnerPhoto are
     * already populated by readIntentExtras() earlier in onCreate.
     */
    private void setupMentionController() {
        if (partnerUid == null || partnerUid.isEmpty()) return;
        mentionController = new com.callx.app.conversation.controllers.ChatMentionController(
                this, partnerUid, partnerName, partnerPhoto);
        mentionController.attach();
    }

    // ─────────────────────────────────────────────────────────────────────
    // BACK PRESS — close search/mention before finishing activity
    // ─────────────────────────────────────────────────────────────────────

    private void setupBackPressHandler() {
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() {
                        // 1. Search open → close it first
                        if (searchController != null && searchController.isOpen()) {
                            searchController.closeSearch();
                            return;
                        }
                        // 2. Mention suggestions visible → dismiss them
                        if (mentionController != null && mentionController.isShowing()) {
                            mentionController.dismissSuggestions();
                            return;
                        }
                        // 3. Normal back
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                    }
                });
    }

    private void setupSwipeToReply() {
        SwipeReplyHandler handler = new SwipeReplyHandler(this,
                new java.util.AbstractList<Message>() {
                    @Override public Message get(int index) { return pagingAdapter.peek(index); }
                    @Override public int size() { return pagingAdapter.getItemCount(); }
                },
                currentUid,
                new SwipeReplyHandler.OnSwipeReplyListener() {
                    @Override public void onSwipeReply(Message message, int adapterPosition) {
                        ReplyAnalyticsTracker.get().onSwipeAttempt(ChatActivity.this, 100f);
                        startReply(message);
                    }

                    // BUG FIX: user dragged past the reply threshold, then
                    // swiped back to (near) the original position without
                    // releasing — WhatsApp cancels the reply-in-progress
                    // here instead of leaving it queued up.
                    @Override public void onSwipeReplyCancelled() {
                        if (pendingReplySnackbar != null) {
                            pendingReplySnackbar.dismiss();
                            pendingReplySnackbar = null;
                        }
                        if (replyController != null) replyController.cancel();
                    }
                });

        swipeHelper = new ItemTouchHelper(handler);
        swipeHelper.attachToRecyclerView(binding.rvMessages);

        replyController = new ReplyController(new ReplyController.Callback() {
            @Override public void onReplyActivated(Message message) { activateReplyDirect(message); }
            @Override public void onReplyCancelled() {}
            @Override public void onPendingUndo(Message message, Runnable cancelAction) {
                String senderName = (currentUid != null && currentUid.equals(message.senderId))
                        ? "You" : (message.senderName != null ? message.senderName : "Unknown");
                pendingReplySnackbar = Snackbar.make(binding.getRoot(), "Replying to " + senderName + "\u2026", Snackbar.LENGTH_SHORT)
                        .setAction("UNDO", v -> { cancelAction.run(); ReplyAnalyticsTracker.get().onUndoUsed(ChatActivity.this); });
                pendingReplySnackbar.show();
            }
            @Override public void onNavigateToOriginal(String messageId) { navigateToOriginalMsg(messageId, null); }
            @Override public void onUndoConfirmed() {}
        });
    }

    // ═════════════════════════════════════════════════════════════════
    // HEADER AUTO-HIDE ON SCROLL — the floating header capsule slides up
    // + fades out when the user scrolls down (towards newer messages) and
    // slides back down + fades in when they scroll up, giving the message
    // list more screen space while actively reading. Skipped while the
    // selection toolbar or search bar is showing since those replace/need
    // the header area.
    // ═════════════════════════════════════════════════════════════════
    private boolean isHeaderCapsuleHidden = false;
    private float headerScrollAccum = 0f;

    private void setupHeaderAutoHide() {
        if (binding.cvHeaderCapsule == null || binding.rvMessages == null) return;

        final float hideThresholdPx = 24f * getResources().getDisplayMetrics().density;

        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (Math.abs(dy) < 2) return;

                // Don't fight with selection/search modes — keep header visible there.
                boolean selectionActive = binding.llSelectionToolbar != null
                        && binding.llSelectionToolbar.getVisibility() == View.VISIBLE;
                boolean searchActive = binding.llSearchBar != null
                        && binding.llSearchBar.getVisibility() == View.VISIBLE;
                if (selectionActive || searchActive) {
                    if (isHeaderCapsuleHidden) showHeaderCapsule();
                    return;
                }

                // Reset the accumulator on a direction flip so a quick reversal
                // feels immediately responsive instead of fighting stale momentum.
                if ((dy > 0 && headerScrollAccum < 0) || (dy < 0 && headerScrollAccum > 0)) {
                    headerScrollAccum = 0f;
                }
                headerScrollAccum += dy;

                if (headerScrollAccum > hideThresholdPx && !isHeaderCapsuleHidden) {
                    hideHeaderCapsule();
                    headerScrollAccum = 0f;
                } else if (headerScrollAccum < -hideThresholdPx && isHeaderCapsuleHidden) {
                    showHeaderCapsule();
                    headerScrollAccum = 0f;
                }
            }

            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                // Always reveal the header once the list comes to rest at the very
                // top of the loaded page — avoids it staying hidden with nothing
                // left to scroll.
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                    if (lm != null && lm.findFirstVisibleItemPosition() == 0 && isHeaderCapsuleHidden) {
                        showHeaderCapsule();
                    }
                }
            }
        });
    }

    private void hideHeaderCapsule() {
        if (binding.cvHeaderCapsule == null || isHeaderCapsuleHidden) return;
        isHeaderCapsuleHidden = true;
        float distance = binding.cvHeaderCapsule.getHeight()
                + binding.cvHeaderCapsule.getTop()
                + (16f * getResources().getDisplayMetrics().density);
        // PERF (ultra-opt pass): withLayer() caches the capsule's whole
        // subtree (avatar, ripple buttons, card corner clip + elevation
        // shadow) into a single hardware layer for the animation's
        // duration, so each of these frames only needs to re-composite one
        // cached texture instead of re-issuing every child's draw calls —
        // this animation always fires while rv_messages is actively
        // flinging, so it's directly competing for the same 16ms frame
        // budget. ViewPropertyAnimator auto-restores LAYER_TYPE_NONE when
        // the animation ends, so no manual cleanup needed.
        binding.cvHeaderCapsule.animate()
                .translationY(-distance)
                .alpha(0f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withLayer()
                .start();
    }

    private void showHeaderCapsule() {
        if (binding.cvHeaderCapsule == null || !isHeaderCapsuleHidden) return;
        isHeaderCapsuleHidden = false;
        binding.cvHeaderCapsule.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withLayer()
                .start();
    }

    private void setupFabBackToLatest() {
        if (binding.fabBackToLatest == null) return;

        // "Back to latest" FAB — also clears the new-messages indicator
        binding.fabBackToLatest.setOnClickListener(v -> {
            pendingNewMsgCount = 0;
            hideNewMessagesIndicator();
            int last = pagingAdapter.getItemCount() - 1;
            if (last >= 0) {
                // scrollToPositionWithOffset(pos, 0) anchors the target item flush at the
                // top of the viewport instantly — no animation, no jank, no mid-scroll
                // layout invalidations. smoothScrollToPosition() was visually janky when
                // the user was far from the bottom (list flew past many items).
                ((LinearLayoutManager) binding.rvMessages.getLayoutManager())
                        .scrollToPositionWithOffset(last, 0);
            }
            MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
        });

        // "↓ N new messages" indicator chip — tapping scrolls to bottom
        if (binding.tvNewMessagesIndicator != null) {
            binding.tvNewMessagesIndicator.setOnClickListener(v -> {
                pendingNewMsgCount = 0;
                hideNewMessagesIndicator();
                int last = pagingAdapter.getItemCount() - 1;
                if (last >= 0) {
                    ((LinearLayoutManager) binding.rvMessages.getLayoutManager())
                            .scrollToPositionWithOffset(last, 0);
                }
                MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
            });
        }

        // PERF: Glide RecyclerViewPreloader — prefetches images for upcoming items
        // in the scroll direction before they're needed. The preloader integrates with
        // the existing pause/resume strategy: Glide is paused on SETTLING/DRAGGING,
        // so preloads are queued and executed on IDLE when loads are safe to start.
        com.bumptech.glide.ListPreloader.PreloadSizeProvider<com.callx.app.models.Message>
                sizeProvider = new com.bumptech.glide.ListPreloader.PreloadSizeProvider<com.callx.app.models.Message>() {
            @Override
            public int[] getPreloadSize(@NonNull com.callx.app.models.Message item,
                                        int adapterPosition, int perItemPosition) {
                if (item.mediaUrl == null && item.thumbnailUrl == null) return null;
                return new int[]{320, 320};
            }
        };
        com.bumptech.glide.ListPreloader.PreloadModelProvider<com.callx.app.models.Message>
                modelProvider = new com.bumptech.glide.ListPreloader.PreloadModelProvider<com.callx.app.models.Message>() {
            @NonNull @Override
            public List<com.callx.app.models.Message> getPreloadItems(int position) {
                com.callx.app.models.Message m = pagingAdapter.peek(position);
                if (m == null) return Collections.emptyList();
                String type = m.type != null ? m.type : "";
                boolean isMedia = "image".equals(type) || "gif".equals(type) || "sticker".equals(type) || "video".equals(type);
                if (!isMedia || (m.mediaUrl == null && m.thumbnailUrl == null))
                    return Collections.emptyList();
                return Collections.singletonList(m);
            }
            @Nullable @Override
            public com.bumptech.glide.RequestBuilder<android.graphics.drawable.Drawable>
                    getPreloadRequestBuilder(@NonNull com.callx.app.models.Message item) {
                String url = "video".equals(item.type)
                        ? (item.thumbnailUrl != null ? item.thumbnailUrl : item.mediaUrl)
                        : (item.mediaUrl != null ? item.mediaUrl : item.imageUrl);
                if (url == null) return null;
                return com.bumptech.glide.Glide.with(ChatActivity.this)
                        .load(url)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                        .override(320, 320);
            }
        };
        com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader<com.callx.app.models.Message>
                glidePreloader = new com.bumptech.glide.integration.recyclerview.RecyclerViewPreloader<>(
                        com.bumptech.glide.Glide.with(this),
                        modelProvider,
                        sizeProvider,
                        5 /* preload 5 items in the scroll direction */);
        binding.rvMessages.addOnScrollListener(glidePreloader);

        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int lastVis = lm.findLastVisibleItemPosition();
                int total   = pagingAdapter.getItemCount();
                boolean atBottom = (lastVis >= total - 3);
                isUserAtBottom = atBottom;
                if (atBottom) {
                    // User reached (or is at) the bottom:
                    //   • reset pending counter
                    //   • hide the "new messages" indicator
                    //   • hide the FAB
                    pendingNewMsgCount = 0;
                    hideNewMessagesIndicator();
                    MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
                } else if (dy < 0) {
                    // User is scrolling UP away from the bottom — show FAB
                    // (indicator only appears when new msgs arrive, not just on scroll)
                    if (binding.fabBackToLatest.getVisibility() != View.VISIBLE) {
                        binding.fabBackToLatest.setVisibility(View.VISIBLE);
                        binding.fabBackToLatest.setAlpha(1f);
                    }
                }
            }

            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                // PERF: Glide pause/resume — during a fast fling, pausing Glide stops
                // it from starting new image-decode tasks for off-screen items that
                // will scroll past before they're needed. Resuming on idle/settling
                // lets it catch up with whatever is now actually visible.
                // This mirrors WhatsApp's image-loading strategy.
                // PERF FIX: Glide pause/resume strategy.
                // OLD: pause on SETTLING, resume on DRAGGING — wrong because
                //   DRAGGING includes fast finger swipes, resuming Glide while
                //   the list is still moving causes mid-fling image decodes.
                // NEW: pause on SETTLING (fling) AND DRAGGING (while finger
                //   moves), resume ONLY on IDLE (list fully stopped).
                //   This mirrors Glide's own RecyclerViewPreloader recommendation.
                if (newState == RecyclerView.SCROLL_STATE_SETTLING
                        || newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    // Finger moving or list flinging — halt all pending decodes.
                    com.bumptech.glide.Glide.with(ChatActivity.this).pauseRequestsRecursive();
                    com.callx.app.utils.LinkPreviewFetcher.setScrolling(true);
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // List fully stopped — resume image loading for visible items.
                    com.bumptech.glide.Glide.with(ChatActivity.this).resumeRequestsRecursive();
                    com.callx.app.utils.LinkPreviewFetcher.setScrolling(false);
                }
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                if (presenceController == null) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                int pos = lm.findLastCompletelyVisibleItemPosition();
                if (pos < 0) pos = lm.findLastVisibleItemPosition();
                if (pos < 0 || pos >= pagingAdapter.getItemCount()) return;
                com.callx.app.models.Message m = pagingAdapter.peek(pos);
                if (m == null) return;
                String mid = m.messageId != null ? m.messageId : m.id;
                presenceController.publishViewingMessage(mid);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // MENU
    // ─────────────────────────────────────────────────────────────────────

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu); return true;
    }

    @Override public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        MenuItem muteItem = menu.findItem(R.id.action_mute);
        if (muteItem != null) muteItem.setTitle(isMuted ? "\uD83D\uDD14 Unmute" : "\uD83D\uDD15 Mute");
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_view_profile)          { openAvatarZoom();                          return true; }
        if (id == R.id.action_edit_profile)           { openEditProfile();                          return true; }
        if (id == R.id.action_starred) {
            starredController.openManageList();
            return true;
        }
        if (id == R.id.action_search)                { searchController.openSearch();             return true; }
        if (id == R.id.action_mute)                  { presenceController.toggleMute();           return true; }
        if (id == R.id.action_block)                 { blockController.confirmBlockUser();        return true; }
        if (id == R.id.action_clear_chat)            { confirmClearChat();                         return true; }
        if (id == R.id.action_chat_customization)    { themeController.showChatCustomizationMenu(); return true; }
        if (id == R.id.action_media_links_docs)      { openAllMediaLinksDocs();                   return true; }
        if (id == R.id.action_security)              { themeController.showChatSecuritySheet();   return true; }
        if (id == R.id.action_chat_privacy)          { themeController.showChatPrivacySheet();    return true; }
        if (id == R.id.action_export_chat)           { exportController.showExportSheet();        return true; }
        if (id == R.id.action_chat_backup) {
            Intent bi = new Intent(this, ChatBackupActivity.class);
            bi.putExtra(ChatBackupActivity.EXTRA_CHAT_ID,   chatId);
            bi.putExtra(ChatBackupActivity.EXTRA_CHAT_NAME, partnerName);
            startActivity(bi);
            return true;
        }
        if (id == R.id.action_small_window)          { openSmallWindow();                          return true; }
        return super.onOptionsItemSelected(item);
    }

    // ─────────────────────────────────────────────────────────────────────
    // CLEAR CHAT
    // ─────────────────────────────────────────────────────────────────────

    private void confirmClearChat() {
        com.callx.app.utils.AlertDialogStyler.showRounded(
            new AlertDialog.Builder(this)
                .setTitle("Clear chat?").setMessage("All messages will be deleted locally.")
                .setPositiveButton("Clear", (d, w) -> {
                    ioExecutor.execute(() -> db.messageDao().deleteAllForChat(chatId));
                    CacheManager.getInstance(this).invalidateMessages(chatId);
                    Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).create(),
                com.callx.app.utils.AlertDialogStyler.DialogSize.COMPACT);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PERMISSIONS
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ChatMediaController.REQ_CAMERA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            mediaController.launchCamera();
        }
        if (requestCode == ChatMediaController.REQ_AUDIO
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            mediaController.onAudioPermissionGranted();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (contactShareController.handleResult(requestCode, resultCode, data)) return;
        if (locationShareController.handleResult(requestCode, resultCode, data)) return;
    }

    // ─────────────────────────────────────────────────────────────────────
    // UTILS
    // ─────────────────────────────────────────────────────────────────────

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private String buildTypePreviewLocal(Message m) {
        if (m.type == null) return "[message]";
        switch (m.type) {
            case "image":  return "\uD83D\uDCF7 Photo";
            case "gif":    return "\uD83C\uDEDF\uFE0F GIF";
            case "sticker": return "\uD83C\uDFF7\uFE0F Sticker";
            case "video":  return "\uD83C\uDFAC Video";
            case "audio":  return "\uD83C\uDFA4 Voice message";
            case "file":   return "\uD83D\uDCCE " + (m.fileName != null ? m.fileName : "File");
            case "poll":   return "\uD83D\uDCCA " + (m.pollQuestion != null ? m.pollQuestion : "Poll");
            default:       return "[" + m.type + "]";
        }
    }
}
