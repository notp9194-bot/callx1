package com.callx.app.conversation;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Transformations;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
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
import com.callx.app.conversation.controllers.ChatScheduledSendController;
import com.callx.app.conversation.controllers.ChatScreenshotNotifier;
import com.callx.app.conversation.controllers.ChatSearchController;
import com.callx.app.conversation.controllers.ChatThemeController;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.TypingStyleManager;
import com.callx.app.utils.UnicodeStyler;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
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
 *   Pager<Integer, MessageEntity> ──LiveData──► PagingAdapter ──► RecyclerView
 */
public class ChatActivity extends AppCompatActivity implements ChatActivityDelegate {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final String TAG           = "ChatActivity";
    // FIX #6: Tuned PagingConfig values.
    // INITIAL_LOAD 40→30: fetching 40 on first open is wasteful — user sees ~12 items.
    // PREFETCH_DIST 10→5: 10 items ahead = too eager on DB thread. 5 = half screen ahead, still smooth.
    private static final int    PAGE_SIZE     = 20;
    private static final int    PREFETCH_DIST = 5;
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
    private AppDatabase          db;
    private final Executor       ioExecutor = Executors.newFixedThreadPool(2);

    // ── Firebase ───────────────────────────────────────────────────────────
    private DatabaseReference  messagesRef;
    private ChildEventListener messageListener;

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
    private ReplyController  replyController;
    private ItemTouchHelper  swipeHelper;

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

    // ── Controllers ────────────────────────────────────────────────────────
    private ChatBlockController    blockController;
    private ChatPresenceController presenceController;
    private ChatPlaybackPresenceController playbackPresenceController;
    private ChatLiveTypingController liveTypingController;
    private ChatEmojiBurstController emojiBurstController;
    private ChatPinController      pinController;
    private MessageEditHistoryController editHistoryController;
    private ChatReactionController reactionController;
    private ChatPollController     pollController;
    private ChatStarredController  starredController;
    private ChatScheduledSendController scheduledSendController;
    private ChatSearchController   searchController;
    private ChatThemeController    themeController;
    private ChatMediaController    mediaController;
    private ChatMessageSender      messageSender;
    private ChatScreenshotNotifier screenshotNotifier;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Controllers that register ActivityResultLaunchers MUST be created
        // before super.onCreate() or at least before onStart.
        mediaController = new ChatMediaController(this, this);
        mediaController.registerPickers();   // Must happen early

        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

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
        playbackPresenceController = new ChatPlaybackPresenceController(this);
        liveTypingController = new ChatLiveTypingController(this);
        emojiBurstController = new ChatEmojiBurstController(this);
        pinController      = new ChatPinController(this);
        editHistoryController = new MessageEditHistoryController(this);
        reactionController = new ChatReactionController(this);
        pollController     = new ChatPollController(this);
        starredController  = new ChatStarredController(this);
        scheduledSendController = new ChatScheduledSendController(this);
        searchController   = new ChatSearchController(this);
        themeController    = new ChatThemeController(this);
        messageSender      = new ChatMessageSender(this);
        screenshotNotifier = new ChatScreenshotNotifier(this);

        setupToolbar();
        themeController.applyScreenTheme();
        setupPagingRecyclerView();   // RecyclerView + adapter ready (no Room yet)
        setupInputBar();
        setupSwipeToReply();
        setupFabBackToLatest();
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
            pagingAdapter.submitData(getLifecycle(), PagingData.from(cached));
            firstPageRendered = true; // already showing content — no stackFromEnd double-anchor needed
        }

        // PERF FIX: don't flash shimmer for fast/cached loads — schedule it
        // 150ms out instead of showing it unconditionally right away. See
        // shimmerShowRunnable above. Cancelled in addLoadStateListener the
        // moment real data (cached or fresh) actually arrives. Skipped
        // entirely on a warm-cache hit — there's already content on screen.
        if (!warmCacheHit) {
            shimmerHandler.postDelayed(shimmerShowRunnable, SHIMMER_SHOW_DELAY_MS);
        }

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
    protected void onResume() {
        super.onResume();
        // Publish that we currently have THIS chat screen open & foregrounded,
        // so the partner's chat header can show "active in this chat".
        if (presenceController != null) {
            presenceController.setOurInChatScreen(true);
            presenceController.onScreenResumed();
        }
        if (screenshotNotifier != null) screenshotNotifier.onScreenResumed();
    }

    @Override
    protected void onPause() {
        super.onPause();
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
            if (isRecording) presenceController.publishOurRecordingState(false);
        }
        if (screenshotNotifier != null) screenshotNotifier.onScreenPaused();
        if (liveTypingController != null) liveTypingController.clearOurPreview();
        typingHandler.removeCallbacks(stopTypingRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveDraft();
        shimmerHandler.removeCallbacks(shimmerShowRunnable);

        if (messagesRef != null && messageListener != null)
            messagesRef.removeEventListener(messageListener);

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
        if (liveTypingController != null) liveTypingController.destroy();
        if (emojiBurstController != null) emojiBurstController.release();
        if (blockController    != null) blockController.release();
        if (scheduledSendController != null) scheduledSendController.release();
        if (screenshotNotifier != null) screenshotNotifier.release();
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
    @Override public void navigateToOriginal(String messageId) { navigateToOriginalMsg(messageId); }
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
        messageSender.pushMessage(m, previewText);
    }

    // ─────────────────────────────────────────────────────────────────────
    // POLLS — moved to ChatPollController (showCreatePollDialog / castVote /
    // toggleClosed). launchPollCreator() and the onPollVote/onPollToggleClose
    // action-sheet callbacks now delegate straight to it.
    // ─────────────────────────────────────────────────────────────────────

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
        binding.llReplyBar.animate()
                .alpha(0f).translationY(20f).setDuration(150)
                .withEndAction(() -> {
                    binding.llReplyBar.setVisibility(View.GONE);
                    binding.llReplyBar.setAlpha(1f);
                    binding.llReplyBar.setTranslationY(0f);
                })
                .start();
        if (binding.ivReplyBarThumb != null) binding.ivReplyBarThumb.setVisibility(View.GONE);
    }

    @Override
    public void startReply(Message m) {
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

        String senderName = (currentUid != null && currentUid.equals(m.senderId))
                ? "You" : (m.senderName != null ? m.senderName : "");
        String preview;
        if (Boolean.TRUE.equals(m.deleted)) {
            preview = "\uD83D\uDEAB  Original message unavailable";
        } else if (m.text != null && !m.text.isEmpty()) {
            preview = m.text;
        } else {
            preview = buildTypePreviewLocal(m);
        }

        if (binding.tvReplyBarName != null) binding.tvReplyBarName.setText(senderName);
        if (binding.tvReplyBarText != null) binding.tvReplyBarText.setText(preview);

        if (binding.ivReplyBarThumb != null) {
            String thumbUrl = null;
            if ("image".equals(m.type)) thumbUrl = m.mediaUrl;
            else if ("video".equals(m.type)) thumbUrl = m.thumbnailUrl;
            if (thumbUrl != null && !thumbUrl.isEmpty()) {
                binding.ivReplyBarThumb.setVisibility(View.VISIBLE);
                Glide.with(this).load(thumbUrl).centerCrop().into(binding.ivReplyBarThumb);
            } else {
                binding.ivReplyBarThumb.setVisibility(View.GONE);
            }
        }

        binding.llReplyBar.setVisibility(View.VISIBLE);
        binding.llReplyBar.setAlpha(0f);
        binding.llReplyBar.setTranslationY(40f);
        binding.llReplyBar.animate().alpha(1f).translationY(0f).setDuration(200).start();
        binding.etMessage.requestFocus();
        ReplyAnalyticsTracker.get().onSwipeTriggered();
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

        if (fwdTexts != null && !fwdTexts.isEmpty()) {
            binding.getRoot().post(() -> {
                for (int idx = 0; idx < fwdTexts.size(); idx++) {
                    final int fi = idx;
                    binding.getRoot().postDelayed(() -> {
                        String t  = fwdTexts.get(fi);
                        String tp = fwdTypes.get(fi);
                        String mu = fwdMedias.get(fi);
                        Message m2 = buildOutgoing();
                        m2.forwardedFrom = partnerName;
                        if ("text".equals(tp) || tp == null) {
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
    }

    private static String buildChatId(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        return a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
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

        // Hanging reel animation
        LinearLayout reelHanging = binding.llReelHanging;
        if (reelHanging != null) {
            reelHanging.post(() -> {
                RotateAnimation swing = new RotateAnimation(-12f, 12f,
                        Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.0f);
                swing.setDuration(1800);
                swing.setRepeatCount(Animation.INFINITE);
                swing.setRepeatMode(Animation.REVERSE);
                swing.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
                reelHanging.startAnimation(swing);
            });
        }

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
        pagingAdapter.setActionListener(new MessagePagingAdapter.ActionListener() {
            @Override public void onReply(Message m)               { startReply(m); }
            @Override public void onDelete(Message m)              { confirmDeleteMessage(m); }
            @Override public void onReact(Message m, String emoji) { reactionController.toggleReaction(m, emoji); }
            @Override public void onReactionTap(Message m)        { reactionController.showReactedUsers(m); }
            @Override public void onStar(Message m)                { starredController.toggleStar(m); }
            @Override public void onCopy(Message m)                { copyText(m); }
            @Override public void onForward(Message m)             { forwardMessage(m); }
            @Override public void onNavigateToOriginal(String mid) { navigateToOriginalMsg(mid); }
            @Override public void onRetry(Message m) {
                if (m.id == null) return;
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

        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        llm.setReverseLayout(false);
        // FIX #2a: Tell LinearLayoutManager how many items to prefetch.
        // Default is 2 — increasing to 5 means the next 5 items are inflated
        // during idle time before the user scrolls to them (no stutter).
        llm.setInitialPrefetchItemCount(6);
        binding.rvMessages.setLayoutManager(llm);
        binding.rvMessages.setAdapter(pagingAdapter);
        // FIX #2b: setHasFixedSize(true) — RecyclerView won't re-measure
        // its own size every time an item changes; valid because the RV fills
        // the screen and its own size never changes (only item content changes).
        binding.rvMessages.setHasFixedSize(true);
        // FIX #2c: Increase view cache from default 2 → 10.
        // With 5 view types (sent/received/status-seen/reel-seen/call) and a
        // typical visible window of ~12 items, cache=2 means almost every
        // onBind() must pull from the recycle pool (slow). Cache=10 keeps the
        // most recently off-screen views ready to rebind without reinflation.
        binding.rvMessages.setItemViewCacheSize(20);
        // FIX #2d: Tune RecycledViewPool per view type (5 types × 5 each).
        // Default pool size is 5 already but explicit sizing prevents the pool
        // from being exhausted on fast flings that scroll past many bubbles.
        RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();
        pool.setMaxRecycledViews(1 /* TYPE_SENT */,        5);
        pool.setMaxRecycledViews(2 /* TYPE_RECEIVED */,    5);
        pool.setMaxRecycledViews(3 /* TYPE_STATUS_SEEN */, 3);
        pool.setMaxRecycledViews(4 /* TYPE_REEL_SEEN */,   3);
        pool.setMaxRecycledViews(5 /* TYPE_CALL_ENTRY */,  3);
        binding.rvMessages.setRecycledViewPool(pool);
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

        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                if (!firstPageRendered) {
                    // First-ever data for this screen: stackFromEnd(true) already
                    // anchors the initial layout pass at the bottom. An extra
                    // explicit scroll here is what caused the visible jump while
                    // the chat was opening — skip it.
                    firstPageRendered = true;
                    return;
                }
                int total = pagingAdapter.getItemCount();
                int lastVis = llm.findLastVisibleItemPosition();
                if (lastVis >= total - itemCount - 2) {
                    binding.rvMessages.scrollToPosition(total - 1);
                }
            }
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
            }
            return null;
        });
    }

    private void observePagedMessages() {
        Pager<Integer, MessageEntity> pager = new Pager<>(
                new PagingConfig(PAGE_SIZE, PREFETCH_DIST, false, INITIAL_LOAD),
                () -> db.messageDao().getMessagesPagingSource(chatId)
        );
        Transformations.map(
                PagingLiveData.getLiveData(pager),
                pagingData -> PagingDataTransforms.map(pagingData, ioExecutor, ChatActivity::entityToModel)
        ).observe(this, pagingData -> pagingAdapter.submitData(getLifecycle(), pagingData));
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
        ioExecutor.execute(() -> {
            java.util.List<MessageEntity> entities = new java.util.ArrayList<>(upsertsSnapshot.size());
            for (Message m : upsertsSnapshot) entities.add(modelToEntity(m));
            db.messageDao().applyBufferedChanges(entities, removalsSnapshot, readSnapshot);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // ENTITY ↔ MODEL CONVERSION
    // ─────────────────────────────────────────────────────────────────────

    static Message entityToModel(MessageEntity e) {
        Message m = new Message();
        m.id = e.id; m.messageId = e.id; m.senderId = e.senderId; m.senderName = e.senderName;
        m.senderPhoto = e.senderPhoto; m.text = e.text; m.type = e.type; m.mediaUrl = e.mediaUrl;
        m.imageUrl = "image".equals(e.type) ? e.mediaUrl : null; m.thumbnailUrl = e.thumbnailUrl;
        m.fileName = e.fileName; m.fileSize = e.fileSize; m.duration = e.duration;
        m.timestamp = e.timestamp; m.status = e.status; m.replyToId = e.replyToId;
        m.replyToText = e.replyToText; m.replyToSenderName = e.replyToSenderName;
        m.replyToType = e.replyToType; m.replyToMediaUrl = e.replyToMediaUrl;
        m.edited = e.edited; m.editedAt = e.editedAt; m.deleted = e.deleted; m.forwardedFrom = e.forwardedFrom;
        m.editHistory = com.callx.app.utils.EditHistoryJsonUtil.historyFromJson(e.editHistoryJson);
        m.starred = e.starred; m.pinned = e.pinned; m.reelId = e.reelId;
        m.reactions = com.callx.app.utils.ReactionJsonUtil.reactionsFromJson(e.reactionsJson);
        m.reelThumbUrl = e.reelThumbUrl; m.fontStyle = e.fontStyle; m.expiresAt = e.expiresAt;
        m.pollQuestion = e.pollQuestion;
        m.pollOptions  = com.callx.app.utils.PollJsonUtil.optionsFromJson(e.pollOptionsJson);
        m.pollVotes    = com.callx.app.utils.PollJsonUtil.votesFromJson(e.pollVotesJson);
        m.pollAnonymous = e.pollAnonymous;
        m.pollClosed    = e.pollClosed;
        m.pollMultiChoice = e.pollMultiChoice;
        return m;
    }

    private MessageEntity modelToEntity(Message m) {
        MessageEntity e = new MessageEntity();
        e.id = m.id != null ? m.id : ""; e.chatId = chatId; e.senderId = m.senderId;
        e.senderName = m.senderName; e.senderPhoto = m.senderPhoto; e.text = m.text;
        e.type = m.type != null ? m.type : "text"; e.mediaUrl = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
        e.thumbnailUrl = m.thumbnailUrl; e.fileName = m.fileName; e.fileSize = m.fileSize;
        e.duration = m.duration; e.timestamp = m.timestamp; e.status = m.status;
        e.replyToId = m.replyToId; e.replyToText = m.replyToText;
        e.replyToSenderName = m.replyToSenderName; e.replyToType = m.replyToType;
        e.replyToMediaUrl = m.replyToMediaUrl; e.edited = m.edited; e.editedAt = m.editedAt; e.deleted = m.deleted;
        e.editHistoryJson = com.callx.app.utils.EditHistoryJsonUtil.historyToJson(m.editHistory);
        e.forwardedFrom = m.forwardedFrom; e.starred = Boolean.TRUE.equals(m.starred);
        e.reactionsJson = com.callx.app.utils.ReactionJsonUtil.reactionsToJson(m.reactions);
        e.pinned = Boolean.TRUE.equals(m.pinned); e.reelId = m.reelId;
        e.reelThumbUrl = m.reelThumbUrl; e.fontStyle = m.fontStyle; e.expiresAt = m.expiresAt;
        e.pollQuestion    = m.pollQuestion;
        e.pollOptionsJson = com.callx.app.utils.PollJsonUtil.optionsToJson(m.pollOptions);
        e.pollVotesJson   = com.callx.app.utils.PollJsonUtil.votesToJson(m.pollVotes);
        e.pollAnonymous   = m.pollAnonymous;
        e.pollClosed      = m.pollClosed;
        e.pollMultiChoice = m.pollMultiChoice;
        e.syncedAt = System.currentTimeMillis();
        return e;
    }

    // ─────────────────────────────────────────────────────────────────────
    // INPUT BAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupInputBar() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                boolean hasText = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(hasText ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(hasText ? View.GONE : View.VISIBLE);

                int remaining = MAX_MESSAGE_LENGTH - s.length();
                if (remaining <= 200) {
                    binding.etMessage.setError(remaining < 0
                            ? "Limit exceeded! (" + Math.abs(remaining) + " extra)"
                            : remaining + " characters remaining");
                } else {
                    binding.etMessage.setError(null);
                }

                if (hasText) {
                    if (presenceController != null) presenceController.setOurTypingStatus(true);
                    typingHandler.removeCallbacks(stopTypingRunnable);
                    typingHandler.postDelayed(stopTypingRunnable, 2000);
                } else {
                    typingHandler.removeCallbacks(stopTypingRunnable);
                    if (presenceController != null) presenceController.setOurTypingStatus(false);
                }
                if (liveTypingController != null) liveTypingController.onOurTextChanged(s.toString());
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
                ioExecutor.execute(() -> {
                    if (db != null && chatId != null) db.chatDao().saveDraft(chatId, "");
                });
            });
            return true;
        });
        binding.btnMic.setOnClickListener(v -> {
            boolean wasRecording = isRecording;
            mediaController.toggleRecording();
            // Publish voice-note recording state to Firebase so the partner
            // sees "Rahul is recording a voice message D83CDF99FE0F" on their screen.
            if (presenceController != null) {
                presenceController.publishOurRecordingState(isRecording);
            }
        });
        binding.btnAttach.setOnClickListener(v -> mediaController.showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> mediaController.launchCamera());

        if (binding.btnCancelReply != null)
            binding.btnCancelReply.setOnClickListener(v -> clearReply());

        if (binding.etMessage instanceof GifAwareEditText) {
            ((GifAwareEditText) binding.etMessage).setGifReceivedListener(contentInfo -> {
                contentInfo.requestPermission();
                mediaController.sendGifMessage(contentInfo.getContentUri(), contentInfo);
            });
        }
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

    private void sendTextMessage() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_MESSAGE_LENGTH) {
            binding.etMessage.setError("Message too long! Max " + MAX_MESSAGE_LENGTH + " characters allowed.");
            Toast.makeText(this, "Message too long!", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.etMessage.setText("");
        binding.etMessage.post(() -> TypingStyleManager.get(this).applyToInput(binding.etMessage));
        if (presenceController != null) presenceController.clearOurTypingStatus();
        if (liveTypingController != null) liveTypingController.clearOurPreview();
        ioExecutor.execute(() -> {
            if (db != null && chatId != null) db.chatDao().saveDraft(chatId, "");
        });
        Message m = buildOutgoing();
        m.type = "text";
        m.fontStyle = TypingStyleManager.get(this).getCurrentStyle();
        if (m.fontStyle == TypingStyleManager.STYLE_SAMSUNG_SCRIPT) {
            text = UnicodeStyler.toScript(text);
        }
        m.text = text;
        pushMessage(m, text);
        clearReply();
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
        builder.show();
    }

    private void forwardMessage(Message m) {
        Intent i = new Intent().setClassName(this, "com.callx.app.activities.ContactsActivity");
        i.putExtra("forwardText", m.text); i.putExtra("forwardType", m.type != null ? m.type : "text");
        i.putExtra("forwardMedia", m.mediaUrl); i.putExtra("forwardFileName", m.fileName);
        startActivity(i);
    }

    private void forwardSelectedMessages() {
        List<Message> selected = pagingAdapter.getSelectedMessages();
        if (selected.isEmpty()) { Toast.makeText(this, "Koi message select nahi", Toast.LENGTH_SHORT).show(); return; }
        ArrayList<String> texts = new ArrayList<>(), types = new ArrayList<>(),
                medias = new ArrayList<>(), fileNames = new ArrayList<>();
        selected.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        for (Message m : selected) {
            texts.add(m.text != null ? m.text : ""); types.add(m.type != null ? m.type : "text");
            medias.add(m.mediaUrl != null ? m.mediaUrl : ""); fileNames.add(m.fileName != null ? m.fileName : "");
        }
        Intent i = new Intent().setClassName(this, "com.callx.app.activities.ContactsActivity");
        i.putStringArrayListExtra("forwardTexts", texts); i.putStringArrayListExtra("forwardTypes", types);
        i.putStringArrayListExtra("forwardMedias", medias); i.putStringArrayListExtra("forwardFileNames", fileNames);
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
                    .setNegativeButton("Cancel", null).show();
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
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", java.util.Locale.getDefault());
        String sentTime = (m.timestamp != null && m.timestamp > 0) ? sdf.format(new java.util.Date(m.timestamp)) : "Unknown";
        String statusLabel = m.status != null ? m.status : "unknown";
        String typeLabel   = m.type   != null ? m.type   : "text";
        String info = "Sent:  " + sentTime + "\nStatus:  " + statusLabel
                + "\nType:  " + typeLabel + "\nTo:  " + (partnerName != null ? partnerName : partnerUid);
        new AlertDialog.Builder(this).setTitle("\u2139 Message Info").setMessage(info)
                .setPositiveButton("OK", null).show();
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

    private void navigateToOriginalMsg(String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
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
                        binding.fabBackToLatest.animate().alpha(1f).setDuration(200).start();
                    }
                    binding.rvMessages.scrollToPosition(safePos);
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

    private void setupSwipeToReply() {
        SwipeReplyHandler handler = new SwipeReplyHandler(this,
                new java.util.AbstractList<Message>() {
                    @Override public Message get(int index) { return pagingAdapter.peek(index); }
                    @Override public int size() { return pagingAdapter.getItemCount(); }
                },
                currentUid,
                (message, adapterPosition) -> {
                    ReplyAnalyticsTracker.get().onSwipeAttempt(100f);
                    startReply(message);
                });

        swipeHelper = new ItemTouchHelper(handler);
        swipeHelper.attachToRecyclerView(binding.rvMessages);

        replyController = new ReplyController(new ReplyController.Callback() {
            @Override public void onReplyActivated(Message message) { activateReplyDirect(message); }
            @Override public void onReplyCancelled() {}
            @Override public void onPendingUndo(Message message, Runnable cancelAction) {
                String senderName = (currentUid != null && currentUid.equals(message.senderId))
                        ? "You" : (message.senderName != null ? message.senderName : "Unknown");
                Snackbar.make(binding.getRoot(), "Replying to " + senderName + "\u2026", Snackbar.LENGTH_SHORT)
                        .setAction("UNDO", v -> { cancelAction.run(); ReplyAnalyticsTracker.get().onUndoUsed(); })
                        .show();
            }
            @Override public void onNavigateToOriginal(String messageId) { navigateToOriginalMsg(messageId); }
            @Override public void onUndoConfirmed() {}
        });
    }

    private void setupFabBackToLatest() {
        if (binding.fabBackToLatest == null) return;
        binding.fabBackToLatest.setOnClickListener(v -> {
            int last = pagingAdapter.getItemCount() - 1;
            if (last >= 0) binding.rvMessages.smoothScrollToPosition(last);
            MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
        });
        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                if (lm.findLastVisibleItemPosition() >= pagingAdapter.getItemCount() - 3) {
                    MessageHighlightAnimator.hideFab(binding.fabBackToLatest);
                }
            }

            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
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
        if (id == R.id.action_small_window)          { openSmallWindow();                          return true; }
        return super.onOptionsItemSelected(item);
    }

    // ─────────────────────────────────────────────────────────────────────
    // CLEAR CHAT
    // ─────────────────────────────────────────────────────────────────────

    private void confirmClearChat() {
        new AlertDialog.Builder(this)
                .setTitle("Clear chat?").setMessage("All messages will be deleted locally.")
                .setPositiveButton("Clear", (d, w) -> {
                    ioExecutor.execute(() -> db.messageDao().deleteAllForChat(chatId));
                    CacheManager.getInstance(this).invalidateMessages(chatId);
                    Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
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
            mediaController.toggleRecording();
        }
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
            case "video":  return "\uD83C\uDFAC Video";
            case "audio":  return "\uD83C\uDFA4 Voice message";
            case "file":   return "\uD83D\uDCCE " + (m.fileName != null ? m.fileName : "File");
            case "poll":   return "\uD83D\uDCCA " + (m.pollQuestion != null ? m.pollQuestion : "Poll");
            default:       return "[" + m.type + "]";
        }
    }
}
