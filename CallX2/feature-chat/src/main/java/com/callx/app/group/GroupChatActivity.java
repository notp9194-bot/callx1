package com.callx.app.group;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.paging.LoadState;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;
import androidx.paging.PagingLiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;
import com.callx.app.conversation.MessagePagingAdapter;
import com.callx.app.cache.CacheManager;
import com.callx.app.chat.databinding.ActivityChatBinding;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.AudioRecorderHelper;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import com.callx.app.conversation.ChatActivity;
import com.callx.app.chat.ui.GifAwareEditText;
import androidx.core.view.inputmethod.InputContentInfoCompat;
import com.callx.app.conversation.MessageAdapter;
import com.callx.app.chat.ui.MessageHighlightAnimator;

/**
 * GroupChatActivity — Production-grade group chat screen.
 *
 * FIX #7 (HIGH): Replaced MessageAdapter + full List<Message> load
 *   with MessagePagingAdapter + Paging 3.
 *   Old: Firebase ValueEventListener loaded ALL group messages into a List
 *   → For large groups (1000+ messages) this caused:
 *     a) OOM crash on low-RAM devices
 *     b) Firebase reads charging for all historical data on every open
 *     c) UI freeze while binding all items at once
 *   Fix: Same architecture as ChatActivity — Room native PagingSource
 *   + Firebase ChildEventListener inserts into Room → auto-invalidates pager.
 *
 * All original features preserved:
 *   - Feature 2:  Reply to message
 *   - Feature 8:  Pinned message banner
 *   - Feature 9:  Group Admin Controls (member list, kick, promote/demote, rename)
 *   - Feature 10: Group Invite Link
 *   + Typing indicator, online member count, voice/media messages
 */
public class GroupChatActivity extends AppCompatActivity
        implements GroupWatchingController.Delegate, GroupStarredController.Delegate {

    private static final String TAG           = "GroupChatActivity";
    private static final int    PAGE_SIZE     = 20;
    private static final int    INITIAL_LOAD  = 40;
    private static final int    PREFETCH_DIST = 10;
    private static final int    REQ_AUDIO     = 200;

    // ── View binding ───────────────────────────────────────────────────────
    private ActivityChatBinding binding;

    // ── Identifiers ────────────────────────────────────────────────────────
    private String groupId, groupName, groupPhoto, currentUid, currentName;

    // ── Paging 3 (FIX #7) ─────────────────────────────────────────────────
    private MessagePagingAdapter pagingAdapter;
    private AppDatabase          db;
    private final Executor       ioExecutor = Executors.newFixedThreadPool(2);

    // ── Firebase refs ──────────────────────────────────────────────────────
    private DatabaseReference  groupMessagesRef;
    private ChildEventListener messageListener;

    // ── PERF FIX: write-coalescing buffer for Firebase → Room sync ─────────
    // Same root cause as ChatActivity (1:1 chat): Firebase replays the last
    // N group messages as N separate onChildAdded() events fired back to
    // back when the screen opens. Writing each one to Room immediately
    // meant N separate PagingSource invalidations → N separate
    // submitData()/diff/layout passes, seen as the list jumping up/down
    // while it loaded. Buffer everything that arrives within a short
    // window and flush it as ONE Room transaction instead.
    private static final long WRITE_FLUSH_DEBOUNCE_MS = 80;
    private final Map<String, Message> pendingUpserts = new LinkedHashMap<>();
    private final LinkedHashSet<String> pendingRemovals = new LinkedHashSet<>();
    private final android.os.Handler writeFlushHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean writeFlushScheduled = false;
    private final Runnable writeFlushRunnable = this::flushPendingRoomWrites;

    // ── PERF FIX: don't fight stackFromEnd on the very first render ────────
    // See the matching field/comment in ChatActivity for the full reasoning.
    private boolean firstPageRendered = false;
    private DatabaseReference  typingRef;
    private ValueEventListener typingListener;
    private DatabaseReference  typingReplyRef;
    private ValueEventListener typingReplyListener;
    private DatabaseReference  membersRef;
    private ValueEventListener membersListener;
    private final Map<String, ValueEventListener> presenceListeners = new HashMap<>();

    // ── State ──────────────────────────────────────────────────────────────
    private boolean isAdmin    = false;
    private boolean isRecording = false;
    private final Map<String, String> memberNames = new HashMap<>();
    private final Map<String, String> memberRoles = new HashMap<>();
    private final Map<String, Long>   memberLastSeen = new HashMap<>();
    private final Map<String, String> memberPhotos   = new HashMap<>();
    private final Map<String, String> typingNames    = new HashMap<>();
    private int   totalMembers = 0;
    private boolean amTyping   = false;

    // ── "Watching banner" — overlapping avatars for members who currently
    //    have this group's chat screen open & foregrounded ────────────────
    private GroupWatchingController watchingController;
    private GroupStarredController  starredController;

    // ── Handlers ───────────────────────────────────────────────────────────
    private final android.os.Handler typingHandler  = new android.os.Handler(android.os.Looper.getMainLooper());
    // ── Disappearing messages expiry cleanup ──────────────────────────────
    private final android.os.Handler expiryHandler  = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable expiryRunnable;
    private final Runnable           stopTyping     = () -> setMyTyping(false);
    private final android.os.Handler subtitleHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable subtitleTick = new Runnable() {
        @Override public void run() {
            refreshSubtitle();
            subtitleHandler.postDelayed(this, 30_000L);
        }
    };

    // ── Reply ──────────────────────────────────────────────────────────────
    private Message replyingTo = null;

    // ── Pinned ─────────────────────────────────────────────────────────────
    private String pinnedMsgId = null;

    // ── Media pickers ──────────────────────────────────────────────────────
    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker;
    private ActivityResultLauncher<String> wallpaperPicker;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();

    // ── Network monitoring (Task 5) ────────────────────────────────────────
    private ConnectivityManager          connMgr;
    private ConnectivityManager.NetworkCallback netCallback;

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        applyScreenTheme();

        groupId     = getIntent().getStringExtra("groupId");
        groupName   = getIntent().getStringExtra("groupName");
        groupPhoto  = getIntent().getStringExtra("groupPhoto");
        if (groupId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        currentName = FirebaseUtils.getCurrentName();
        groupMessagesRef = FirebaseUtils.getGroupMessagesRef(groupId);

        // PERF FIX v8: DB ko background mein init karo, UI turant shuru karo.
        // db is null initially; Firebase events buffer hote hain pendingUpserts mein.
        // onGroupDbReady() mein Room Paging shuru hoga + buffer flush hoga.

        setupToolbar();
        setupPickers();
        setupPagingRecyclerView();   // RecyclerView + adapter ready (Room query baad mein)
        setupInputBar();
        setupPinnedBanner();
        setupReplyCancel();
        setupRealtimeHeader();
        setupGroupWatching();
        checkAdminStatus();
        watchPinnedMessage();
        setupNetworkMonitor();

        // Shimmer show karo taaki user blank screen na dekhe
        if (binding.shimmerContainer != null) {
            binding.shimmerContainer.startShimmer();
            binding.shimmerContainer.setVisibility(android.view.View.VISIBLE);
        }

        // Firebase listener immediately — events buffer honge jab tak DB ready nahi
        startRealtimeListener();

        // DB background mein
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            db = AppDatabase.getInstance(this);
            runOnUiThread(this::onGroupDbReady);
        });

        // Disappearing messages — expired messages cleanup
        scheduleExpiryCleanup();

        // Analytics + delta sync + predictive preload
        CacheManager.getInstance(this).getAnalytics().recordChatOpen(groupId);
        ChatRepository.getInstance(this).preloadRecentChats(groupId);

        // Fix 10: Group unread counter reset karo jab chat khulo
        // Server pe increment hota tha lekin reset call missing tha — badge badhta rehta tha
        if (currentUid != null && !currentUid.isEmpty()) {
            FirebaseUtils.db().getReference("groups")
                .child(groupId).child("unread").child(currentUid).setValue(0);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (watchingController != null) watchingController.setOurInChatScreen(true);
        onTypingStripScreenResumed();
    }

    @Override
    protected void onDestroy() {
        subtitleHandler.removeCallbacks(subtitleTick);
        typingHandler.removeCallbacks(stopTyping);
        if (expiryRunnable != null) expiryHandler.removeCallbacks(expiryRunnable);
        setMyTyping(false);
        if (groupMessagesRef != null && messageListener != null)
            groupMessagesRef.removeEventListener(messageListener);
        // PERF FIX: flush any buffered Firebase→Room writes immediately
        // instead of losing them if the debounce window hadn't fired yet.
        writeFlushHandler.removeCallbacks(writeFlushRunnable);
        flushPendingRoomWrites();
        if (typingRef  != null && typingListener  != null)
            typingRef.removeEventListener(typingListener);
        if (typingReplyRef != null && typingReplyListener != null)
            typingReplyRef.removeEventListener(typingReplyListener);
        if (membersRef != null && membersListener != null)
            membersRef.removeEventListener(membersListener);
        for (Map.Entry<String, ValueEventListener> e : presenceListeners.entrySet())
            FirebaseUtils.getUserRef(e.getKey()).removeEventListener(e.getValue());
        presenceListeners.clear();
        if (watchingController != null) watchingController.release();
        if (typingDotsAnimator != null) {
            typingDotsAnimator.stop();
            typingDotsAnimator = null;
        }
        if (connMgr != null && netCallback != null) {
            try { connMgr.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        typingHandler.removeCallbacks(stopTyping);
        setMyTyping(false);
        if (watchingController != null) {
            watchingController.setOurInChatScreen(false);
            watchingController.clearViewingMessage();
        }
        onTypingStripScreenPaused();
        super.onPause();
    }

    // ─────────────────────────────────────────────────────────────────────
    // NETWORK MONITORING (Task 5)
    // ─────────────────────────────────────────────────────────────────────

    // ── Disappearing Messages — Expiry Cleanup ────────────────────────────
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
        if (groupMessagesRef == null) return;
        long nowMs = System.currentTimeMillis();
        groupMessagesRef.orderByChild("expiresAt").endAt(nowMs)
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@androidx.annotation.NonNull com.google.firebase.database.DataSnapshot snap) {
                        for (com.google.firebase.database.DataSnapshot child : snap.getChildren()) {
                            Object raw = child.child("expiresAt").getValue();
                            if (raw instanceof Long && (Long) raw > 0 && (Long) raw <= nowMs) {
                                child.getRef().removeValue();
                            }
                        }
                    }
                    @Override public void onCancelled(@androidx.annotation.NonNull com.google.firebase.database.DatabaseError e) {}
                });
    }

    private void setupNetworkMonitor() {
        connMgr = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connMgr == null) return;

        boolean online = isOnline();
        updateOfflineBanner(!online);
        updateSendButtonState(online);   // v17

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                runOnUiThread(() -> {
                    updateOfflineBanner(false);
                    updateSendButtonState(true);   // v17
                    retryPendingMessages();         // v17
                });
            }
            @Override public void onLost(Network n) {
                runOnUiThread(() -> {
                    updateOfflineBanner(true);
                    updateSendButtonState(false);   // v17
                });
            }
        };
        try {
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            connMgr.registerNetworkCallback(req, netCallback);
        } catch (Exception ignored) {}
    }

    private boolean isOnline() {
        if (connMgr == null) return true;
        Network active = connMgr.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = connMgr.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void updateOfflineBanner(boolean offline) {
        if (binding == null) return;
        android.widget.TextView banner =
                binding.getRoot().findViewById(R.id.tv_offline_banner);
        if (banner != null) banner.setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TOOLBAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        // Custom WhatsApp-style header
        binding.btnBack.setOnClickListener(v -> {
            if (pagingAdapter != null && pagingAdapter.isInMultiSelectMode()) {
                pagingAdapter.exitMultiSelectMode();
                hideMultiSelectBar();
            } else {
                finish();
            }
        });
        if (groupName != null) {
            binding.tvPartnerName.setText(groupName);
        }
        binding.ivPartnerAvatar.setOnClickListener(v -> {
            Intent i = new Intent(this, GroupInfoActivity.class);
            i.putExtra("groupId", groupId);
            startActivity(i);
        });
        if (groupPhoto != null && !groupPhoto.isEmpty()) {
            com.bumptech.glide.Glide.with(this).load(groupPhoto)
                    .placeholder(com.callx.app.core.R.drawable.ic_group)
                    .into(binding.ivPartnerAvatar);
        }

        // Voice call
        binding.btnToolbarVoiceCall.setOnClickListener(v -> startGroupCall(false));

        // Video call
        binding.btnToolbarVideoCall.setOnClickListener(v -> startGroupCall(true));

        binding.btnMoreOptions.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(this, binding.btnMoreOptions);
            android.view.Menu m = popup.getMenu();
            m.add(0, R.id.menu_group_info,           0, "ℹ Group Info").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            m.add(0, R.id.menu_group_settings,       1, "⚙ Group Settings").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            m.add(0, R.id.menu_invite,               2, "🔗 Invite Link").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            m.add(0, R.id.menu_starred,              3, "⭐ Starred Messages").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            m.add(0, R.id.action_chat_customization, 4, "🎨 Chat Customization").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            m.add(0, R.id.action_chat_privacy,       5, "🛡 Chat Privacy").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            if (isAdmin) {
                m.add(0, R.id.menu_admin_panel, 6, "👑 Admin Panel").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
                m.add(0, R.id.menu_rename,      7, "✏ Rename Group").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            }
            popup.setOnMenuItemClickListener(item -> onOptionsItemSelected(item));
            popup.show();
        });
    }

    private void startGroupCall(boolean isVideo) {
        String callId = "gcall_" + groupId + "_" + System.currentTimeMillis();
        Intent i = new Intent().setClassName(this, "com.callx.app.group.GroupCallActivity");
        i.putExtra("gcall_group_id",   groupId);
        i.putExtra("gcall_group_name", groupName);
        i.putExtra("gcall_group_icon", groupPhoto != null ? groupPhoto : "");
        i.putExtra("gcall_call_id",    callId);
        i.putExtra("gcall_is_video",   isVideo);
        i.putExtra("gcall_is_caller",  true);
        startActivity(i);
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIX #7 — PAGING 3: RecyclerView + Adapter
    // ─────────────────────────────────────────────────────────────────────

    private void setupPagingRecyclerView() {
        pagingAdapter = new MessagePagingAdapter(currentUid, true /* isGroup */);

        pagingAdapter.setActionListener(new MessagePagingAdapter.ActionListener() {
            @Override public void onReply(Message m)               { startReply(m); }
            @Override public void onDelete(Message m)              { confirmDelete(m); }
            @Override public void onReact(Message m, String emoji) { sendReaction(m, emoji); }
            @Override public void onStar(Message m)                { starredController.toggleStar(m); }
            @Override public void onCopy(Message m)                { copyText(m); }
            @Override public void onForward(Message m)             { forwardMessage(m); }
            @Override public void onPollVote(Message m, int idx)   { castPollVote(m, idx); }
            @Override public void onPollToggleClose(Message m)    { togglePollClosed(m); }
            @Override public void onNavigateToOriginal(String messageId) {
                scrollToMessageId(messageId);
            }
        });

        // Multi-select: selection bar show/hide
        pagingAdapter.setMultiSelectListener(count -> {
            if (count > 0) {
                showMultiSelectBar(count);
            } else {
                hideMultiSelectBar();
            }
        });

        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        llm.setInitialPrefetchItemCount(6);
        binding.rvMessages.setLayoutManager(llm);
        binding.rvMessages.setAdapter(pagingAdapter);
        // PERF: fixed-size RV, large view cache, shared RecycledViewPool
        binding.rvMessages.setHasFixedSize(true);
        binding.rvMessages.setItemViewCacheSize(20);
        androidx.recyclerview.widget.RecyclerView.RecycledViewPool groupPool =
                new androidx.recyclerview.widget.RecyclerView.RecycledViewPool();
        groupPool.setMaxRecycledViews(1, 5);
        groupPool.setMaxRecycledViews(2, 5);
        groupPool.setMaxRecycledViews(3, 3);
        groupPool.setMaxRecycledViews(4, 3);
        groupPool.setMaxRecycledViews(5, 3);
        binding.rvMessages.setRecycledViewPool(groupPool);
        com.callx.app.chat.performance.SwipeOptimizer.disableChangeAnimations(binding.rvMessages);

        binding.rvMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return;
                if (watchingController == null) return;
                int pos = llm.findLastCompletelyVisibleItemPosition();
                if (pos < 0) pos = llm.findLastVisibleItemPosition();
                if (pos < 0 || pos >= pagingAdapter.getItemCount()) return;
                com.callx.app.models.Message m = pagingAdapter.peek(pos);
                if (m == null) return;
                String mid = m.messageId != null ? m.messageId : m.id;
                watchingController.publishViewingMessage(mid);
            }
        });

        // Auto-scroll when new message arrives at tail
        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
                if (!firstPageRendered) {
                    // stackFromEnd(true) already anchors the first layout pass
                    // at the bottom — an extra explicit scroll here was causing
                    // the visible jump while the group chat opened.
                    firstPageRendered = true;
                    return;
                }
                int total  = pagingAdapter.getItemCount();
                int lastVis = llm.findLastVisibleItemPosition();
                if (lastVis >= total - itemCount - 2)
                    binding.rvMessages.scrollToPosition(total - 1);
            }
        });

        // Shimmer: hide on first load, show on loading
        pagingAdapter.addLoadStateListener(states -> {
            LoadState refresh = states.getRefresh();
            if (refresh instanceof LoadState.Loading) {
                if (binding.rvMessages.getVisibility() == View.GONE) {
                    binding.shimmerContainer.startShimmer();
                    binding.shimmerContainer.setVisibility(View.VISIBLE);
                }
            } else {
                binding.shimmerContainer.stopShimmer();
                binding.shimmerContainer.setVisibility(View.GONE);
                binding.rvMessages.setVisibility(View.VISIBLE);
            }
            return null;
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIX #7 — PAGING 3: Pager → LiveData → Adapter
    // Room PagingSource auto-invalidates on insert → no manual invalidate needed
    // ─────────────────────────────────────────────────────────────────────

    /** DB ready hone ke baad Room Paging start karo + buffered events flush karo. */
    private void onGroupDbReady() {
        if (isFinishing() || isDestroyed()) return;
        observePagedMessages();
        // Pending Firebase events (jo pehle se aa chuke hain) ek transaction mein flush karo
        writeFlushHandler.removeCallbacks(writeFlushRunnable);
        flushPendingRoomWrites();
        // Background cleanup — DB guaranteed non-null yahan.
        // BUG FIX (same as ChatActivity): yeh DELETE query immediately chalti
        // thi har group chat open pe, jo messages table invalidate kar deti
        // thi aur Paging3 ko force-reload karaati thi — har baar visible
        // "reload" dikhta tha chahe data already cached ho. Ab 10s baad.
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
            ioExecutor.execute(() -> {
                if (db != null) db.messageDao().pruneOldMessages(groupId, 500);
            }), 10_000L
        );
    }

    private void observePagedMessages() {
        Pager<Integer, MessageEntity> pager = new Pager<>(
                new PagingConfig(PAGE_SIZE, PREFETCH_DIST, false, INITIAL_LOAD),
                () -> db.messageDao().getMessagesPagingSource(groupId)
        );

        androidx.lifecycle.Transformations.map(
                PagingLiveData.getLiveData(pager),
                pagingData -> PagingDataTransforms.map(
                        pagingData, ioExecutor, GroupChatActivity::entityToModel)
        ).observe(this, pagingData ->
                pagingAdapter.submitData(getLifecycle(), pagingData)
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIX #7 — FIREBASE REAL-TIME LISTENER (delta-sync into Room)
    // ─────────────────────────────────────────────────────────────────────

    private void startRealtimeListener() {
        // DB query MUST run on a background thread — Room forbids main-thread access.
        ioExecutor.execute(() -> {
            long lastTs = CacheManager.getInstance(this).getLastSyncTimestamp(groupId);
            runOnUiThread(() -> attachFirebaseListener(lastTs));
        });
    }

    private void attachFirebaseListener(long lastTs) {
        Query query = lastTs > 0
                ? groupMessagesRef.orderByChild("timestamp").startAfter((double) lastTs)
                : groupMessagesRef.orderByChild("timestamp").limitToLast(INITIAL_LOAD);

        messageListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot s, String prev) {
                Message m = s.getValue(Message.class);
                if (m == null) return;
                m.id = s.getKey();
                saveToRoom(m);
                markRead(m);
            }
            @Override public void onChildChanged(DataSnapshot s, String prev) {
                Message m = s.getValue(Message.class);
                if (m == null) return;
                m.id = s.getKey();
                saveToRoom(m);
            }
            @Override public void onChildRemoved(DataSnapshot s) {
                String key = s.getKey();
                if (key == null) return;
                // PERF FIX: buffer the removal so it coalesces with the rest
                // of the burst into one Room transaction.
                pendingUpserts.remove(key);
                pendingRemovals.add(key);
                scheduleWriteFlush();
            }
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {}
        };
        query.addChildEventListener(messageListener);
    }

    /**
     * PERF FIX: buffers a Firebase add/change event instead of writing it to
     * Room straight away. Everything queued within WRITE_FLUSH_DEBOUNCE_MS
     * gets applied in a single Room transaction (MessageDao#applyBufferedChanges)
     * — this is what stops opening a group chat from rendering one message
     * at a time with a visible jump.
     */
    private void saveToRoom(Message m) {
        if (m == null || m.id == null) return;
        pendingUpserts.put(m.id, m);
        pendingRemovals.remove(m.id); // a fresh upsert always wins over a stale pending removal
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
        if (pendingUpserts.isEmpty() && pendingRemovals.isEmpty()) return;

        List<Message> upsertsSnapshot = new ArrayList<>(pendingUpserts.values());
        List<String> removalsSnapshot = new ArrayList<>(pendingRemovals);
        pendingUpserts.clear();
        pendingRemovals.clear();

        if (db == null) return;
        ioExecutor.execute(() -> {
            List<MessageEntity> entities = new ArrayList<>(upsertsSnapshot.size());
            for (Message m : upsertsSnapshot) entities.add(modelToEntity(m));
            db.messageDao().applyBufferedChanges(entities, removalsSnapshot, null);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // ENTITY ↔ MODEL CONVERSION
    // ─────────────────────────────────────────────────────────────────────

    static Message entityToModel(MessageEntity e) {
        Message m           = new Message();
        m.id                = e.id;
        m.messageId         = e.id;
        m.senderId          = e.senderId;
        m.senderName        = e.senderName;
        m.text              = e.text;
        m.type              = e.type;
        m.mediaUrl          = e.mediaUrl;
        m.imageUrl          = "image".equals(e.type) ? e.mediaUrl : null;
        m.thumbnailUrl      = e.thumbnailUrl;
        m.fileName          = e.fileName;
        m.fileSize          = e.fileSize;
        m.duration          = e.duration;
        m.timestamp         = e.timestamp;
        m.status            = e.status;
        m.replyToId         = e.replyToId;
        m.replyToText       = e.replyToText;
        m.replyToSenderName = e.replyToSenderName;
        m.edited            = e.edited;
        m.editedAt          = e.editedAt;
        m.editHistory       = com.callx.app.utils.EditHistoryJsonUtil.historyFromJson(e.editHistoryJson);
        m.deleted           = e.deleted;
        m.forwardedFrom     = e.forwardedFrom;
        m.starred           = e.starred;
        m.pinned            = e.pinned;
        m.fontStyle         = e.fontStyle;  // FIX: typing style — Room se load hone par preserve karo
        m.expiresAt         = e.expiresAt;  // Disappearing messages
        m.pollQuestion      = e.pollQuestion;
        m.pollOptions       = com.callx.app.utils.PollJsonUtil.optionsFromJson(e.pollOptionsJson);
        m.pollVotes         = com.callx.app.utils.PollJsonUtil.votesFromJson(e.pollVotesJson);
        m.pollAnonymous     = e.pollAnonymous;
        m.pollClosed        = e.pollClosed;
        m.pollMultiChoice   = e.pollMultiChoice;
        return m;
    }

    private MessageEntity modelToEntity(Message m) {
        MessageEntity e         = new MessageEntity();
        e.id                    = m.id != null ? m.id : "";
        e.chatId                = groupId;
        e.senderId              = m.senderId;
        e.senderName            = m.senderName;
        e.text                  = m.text;
        e.type                  = m.type != null ? m.type : "text";
        e.mediaUrl              = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
        e.thumbnailUrl          = m.thumbnailUrl;
        e.fileName              = m.fileName;
        e.fileSize              = m.fileSize;
        e.duration              = m.duration;
        e.timestamp             = m.timestamp;
        e.status                = m.status;
        e.replyToId             = m.replyToId;
        e.replyToText           = m.replyToText;
        e.replyToSenderName     = m.replyToSenderName;
        e.edited                = m.edited;
        e.editedAt              = m.editedAt;
        e.editHistoryJson       = com.callx.app.utils.EditHistoryJsonUtil.historyToJson(m.editHistory);
        e.deleted               = m.deleted;
        e.forwardedFrom         = m.forwardedFrom;
        e.starred               = Boolean.TRUE.equals(m.starred);
        e.pinned                = Boolean.TRUE.equals(m.pinned);
        e.isGroup               = true;
        e.syncedAt              = System.currentTimeMillis();
        e.fontStyle             = m.fontStyle;
        e.expiresAt             = m.expiresAt;  // Disappearing messages
        e.pollQuestion          = m.pollQuestion;
        e.pollOptionsJson       = com.callx.app.utils.PollJsonUtil.optionsToJson(m.pollOptions);
        e.pollVotesJson         = com.callx.app.utils.PollJsonUtil.votesToJson(m.pollVotes);
        e.pollAnonymous         = m.pollAnonymous;
        e.pollClosed            = m.pollClosed;
        e.pollMultiChoice       = m.pollMultiChoice;
        return e;
    }

    // ─────────────────────────────────────────────────────────────────────
    // INPUT BAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupInputBar() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                boolean has = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(has ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(has ? View.GONE : View.VISIBLE);

                // Character counter: 200 se kam bacha ho toh dikhao
                int remaining = MAX_MESSAGE_LENGTH - s.length();
                if (remaining <= 200) {
                    binding.etMessage.setError(remaining < 0
                        ? "Limit exceeded! (" + Math.abs(remaining) + " extra)"
                        : remaining + " characters remaining");
                } else {
                    binding.etMessage.setError(null);
                }

                if (has) {
                    setMyTyping(true);
                    typingHandler.removeCallbacks(stopTyping);
                    typingHandler.postDelayed(stopTyping, 4_000L);
                } else {
                    typingHandler.removeCallbacks(stopTyping);
                    setMyTyping(false);
                }
            }
        });
        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnMic.setOnClickListener(v -> toggleRecording());

        // GIF support: Google Keyboard se GIF aane par handle karo
        if (binding.etMessage instanceof GifAwareEditText) {
            ((GifAwareEditText) binding.etMessage).setGifReceivedListener(contentInfo -> {
                contentInfo.requestPermission();
                Uri gifUri = contentInfo.getContentUri();
                sendGifMessage(gifUri, contentInfo);
            });
        }
    }

    private static final int MAX_MESSAGE_LENGTH = 4000;

    private void sendText() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        if (text.length() > MAX_MESSAGE_LENGTH) {
            binding.etMessage.setError("Message too long! Max " + MAX_MESSAGE_LENGTH + " characters allowed.");
            Toast.makeText(this, "Message too long! Max " + MAX_MESSAGE_LENGTH + " characters.", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.etMessage.setText("");
        // setText ke baad post() se style re-apply karo — setText typeface disturb karta hai
        typingHandler.removeCallbacks(stopTyping);
        setMyTyping(false);
        Message m = buildOutgoing();
        m.type = "text";
        m.fontStyle = 0;
        m.text = text;
        pushMessage(m, text);
        clearReply();
    }

    private Message buildOutgoing() {
        Message m    = new Message();
        m.senderId   = currentUid;
        m.senderName = currentName;
        m.timestamp  = System.currentTimeMillis();
        m.status     = "sent";
        if (replyingTo != null) {
            m.replyToId         = replyingTo.id;
            m.replyToText       = replyingTo.text != null
                    ? replyingTo.text : "[" + replyingTo.type + "]";
            m.replyToSenderName = replyingTo.senderName;
        }
        return m;
    }

    // ─────────────────────────────────────────────────────────────────────
    // POLLS
    // ─────────────────────────────────────────────────────────────────────

    private void showCreatePollDialog() {
        com.callx.app.chat.ui.CreatePollDialog.show(this, (question, options, anonymous, multiChoice) -> {
            Message m = buildOutgoing();
            m.type = "poll";
            m.pollQuestion = question;
            m.pollOptions = options;
            m.pollVotes = new java.util.HashMap<>();
            m.pollAnonymous = anonymous;
            m.pollClosed = false;
            m.pollMultiChoice = multiChoice;
            m.text = "\uD83D\uDCCA " + question;
            pushMessage(m, "\uD83D\uDCCA Poll: " + question);
            clearReply();
        });
    }

    /**
     * Casts/toggles the current user's vote(s) — same Firebase + Room sync
     * pattern as 1:1 chat. Multi-choice polls tick/un-tick individual
     * options; single-choice polls replace the previous vote.
     */
    private void castPollVote(Message m, int optionIndex) {
        if (m == null || groupMessagesRef == null) return;
        String id = m.messageId != null ? m.messageId : m.id;
        if (id == null || id.isEmpty()) return;
        if (Boolean.TRUE.equals(m.pollClosed)) {
            Toast.makeText(this, "This poll is closed", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean multiChoice = Boolean.TRUE.equals(m.pollMultiChoice);
        java.util.Map<String, java.util.List<Integer>> votes = m.pollVotes != null
                ? new java.util.HashMap<>(m.pollVotes) : new java.util.HashMap<>();
        java.util.List<Integer> mine = votes.get(currentUid);
        java.util.List<Integer> updatedMine = new java.util.ArrayList<>(mine != null ? mine : java.util.Collections.emptyList());

        if (multiChoice) {
            if (updatedMine.contains(optionIndex)) {
                updatedMine.remove(Integer.valueOf(optionIndex));
            } else {
                updatedMine.add(optionIndex);
            }
        } else {
            updatedMine.clear();
            updatedMine.add(optionIndex);
        }

        if (updatedMine.isEmpty()) {
            votes.remove(currentUid);
            groupMessagesRef.child(id).child("pollVotes").child(currentUid).removeValue();
        } else {
            votes.put(currentUid, updatedMine);
            groupMessagesRef.child(id).child("pollVotes").child(currentUid).setValue(updatedMine);
        }

        m.pollVotes = votes;
        ioExecutor.execute(() -> {
            try {
                MessageEntity e = db.messageDao().getMessageById(id);
                if (e != null) {
                    e.pollVotesJson = com.callx.app.utils.PollJsonUtil.votesToJson(votes);
                    db.messageDao().updateMessage(e);
                }
            } catch (Exception ignored) {}
        });
    }

    /** Poll creator closes/reopens voting. */
    private void togglePollClosed(Message m) {
        if (m == null || groupMessagesRef == null) return;
        String id = m.messageId != null ? m.messageId : m.id;
        if (id == null || id.isEmpty()) return;
        boolean newClosed = !Boolean.TRUE.equals(m.pollClosed);
        groupMessagesRef.child(id).child("pollClosed").setValue(newClosed);
        ioExecutor.execute(() -> db.messageDao().updatePollClosed(id, newClosed));
        Toast.makeText(this, newClosed ? "Poll closed" : "Poll reopened", Toast.LENGTH_SHORT).show();
    }

    // v17: Local-first push — pehle Room (pending), phir Firebase
    private void pushMessage(Message m, String preview) {
        String key = groupMessagesRef.push().getKey();
        if (key == null) return;
        m.id = key;

        // Disappearing messages — ChatPrivacyManager se disappear timer check karo
        com.callx.app.utils.ChatPrivacyManager privMgr =
                new com.callx.app.utils.ChatPrivacyManager(this, groupId, true);
        long disappearMs = privMgr.getDisappearingMs();
        if (disappearMs > 0) {
            m.expiresAt = m.timestamp + disappearMs;
        }

        // Step 1: Room mein turant save karo status=pending
        MessageEntity pending = modelToEntity(m);
        pending.status = "pending";
        ioExecutor.execute(() -> db.messageDao().insertMessage(pending));

        if (isOnline()) {
            firebasePushGroup(m, key, preview);
        } else {
            Toast.makeText(this, "No connection — message queued", Toast.LENGTH_SHORT).show();
        }
    }

    private void firebasePushGroup(Message m, String key, String preview) {
        groupMessagesRef.child(key).setValue(m)
            .addOnSuccessListener(unused ->
                ioExecutor.execute(() -> db.messageDao().updateStatus(key, "sent")))
            .addOnFailureListener(e -> { /* pending rakho */ });

        Map<String, Object> upd = new HashMap<>();
        upd.put("lastMessage", preview);
        upd.put("lastMessageAt", m.timestamp);
        FirebaseUtils.getGroupsRef().child(groupId).updateChildren(upd);

        PushNotify.notifyGroupMessage(groupId, currentUid, currentName,
                groupName, key, preview, m.type != null ? m.type : "text");
    }

    // v17: Send button offline hone par disable
    private void updateSendButtonState(boolean online) {
        if (binding == null) return;
        if (binding.btnSend != null) {
            binding.btnSend.setEnabled(online);
            binding.btnSend.setAlpha(online ? 1.0f : 0.4f);
        }
        if (binding.btnMic != null) {
            binding.btnMic.setEnabled(online);
            binding.btnMic.setAlpha(online ? 1.0f : 0.4f);
        }
    }

    // v17: Online hone par pending messages retry
    private void retryPendingMessages() {
        ioExecutor.execute(() -> {
            List<MessageEntity> pending = db.messageDao().getPendingMessages(groupId);
            if (pending == null || pending.isEmpty()) return;
            for (MessageEntity pe : pending) {
                Message m = entityToModel(pe);
                m.status = "sent";
                String preview = pe.text != null ? pe.text : "[" + pe.type + "]";
                runOnUiThread(() -> firebasePushGroup(m, pe.id, preview));
            }
        });
    }

    private void markRead(Message m) {
        if (m == null || m.id == null || currentUid.equals(m.senderId)) return;
        groupMessagesRef.child(m.id).child("readBy")
                .child(currentUid).setValue(true);
    }

    // ─────────────────────────────────────────────────────────────────────
    // REPLY (Feature 2)
    // ─────────────────────────────────────────────────────────────────────

    private void setupReplyCancel() {
        if (binding.btnCancelReply != null)
            binding.btnCancelReply.setOnClickListener(v -> clearReply());
    }

    private void startReply(Message m) {
        replyingTo = m;
        if (binding.llReplyBar != null) {
            binding.llReplyBar.setVisibility(View.VISIBLE);
            if (binding.tvReplyBarName != null)
                binding.tvReplyBarName.setText(m.senderName != null ? m.senderName : "");
            if (binding.tvReplyBarText != null)
                binding.tvReplyBarText.setText(
                        m.text != null ? m.text : "[" + m.type + "]");
        }
        binding.etMessage.requestFocus();
    }

    private void clearReply() {
        replyingTo = null;
        if (binding.llReplyBar != null)
            binding.llReplyBar.setVisibility(View.GONE);
        // Don't wait for the next keystroke's setMyTyping() call — the
        // bubble highlight should disappear the instant the reply bar does.
        if (amTyping && typingReplyRef != null) {
            publishMyTypingReplyTarget(typingReplyRef.child(currentUid));
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ACTION HANDLERS
    // ─────────────────────────────────────────────────────────────────────

    private void confirmDelete(Message m) {
        new AlertDialog.Builder(this)
                .setTitle("Delete message?")
                .setPositiveButton("Delete", (d, w) -> {
                    groupMessagesRef.child(m.id).child("deleted").setValue(true);
                    groupMessagesRef.child(m.id).child("text").setValue("");
                    ioExecutor.execute(() -> db.messageDao().softDelete(m.id));
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void sendReaction(Message m, String emoji) {
        if (m.id == null) return;
        groupMessagesRef.child(m.id).child("reactions").child(currentUid).setValue(emoji);
    }

    // toggleStar() moved to GroupStarredController#toggleStar — same
    // Firebase + Room write, just no longer inline on the Activity.

    private void copyText(Message m) {
        if (m.text == null || m.text.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.text));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
        }
    }

    private void forwardMessage(Message m) {
        Intent i = new Intent().setClassName(this, "com.callx.app.activities.ContactsActivity");
        i.putExtra("forwardText",     m.text);
        i.putExtra("forwardType",     m.type);
        i.putExtra("forwardMedia",    m.mediaUrl);
        i.putExtra("forwardFileName", m.fileName);
        startActivity(i);
    }

    private void forwardSelectedMessages() {
        java.util.List<com.callx.app.models.Message> selected = pagingAdapter.getSelectedMessages();
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(this, "Koi message select nahi", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.ArrayList<String> texts     = new java.util.ArrayList<>();
        java.util.ArrayList<String> types     = new java.util.ArrayList<>();
        java.util.ArrayList<String> medias    = new java.util.ArrayList<>();
        java.util.ArrayList<String> fileNames = new java.util.ArrayList<>();
        selected.sort((a, b) -> Long.compare(a.timestamp, b.timestamp));
        for (com.callx.app.models.Message m : selected) {
            texts.add(m.text != null ? m.text : "");
            types.add(m.type != null ? m.type : "text");
            medias.add(m.mediaUrl != null ? m.mediaUrl : "");
            fileNames.add(m.fileName != null ? m.fileName : "");
        }
        Intent i = new Intent().setClassName(this, "com.callx.app.activities.ContactsActivity");
        i.putStringArrayListExtra("forwardTexts",     texts);
        i.putStringArrayListExtra("forwardTypes",     types);
        i.putStringArrayListExtra("forwardMedias",    medias);
        i.putStringArrayListExtra("forwardFileNames", fileNames);
        startActivity(i);
        pagingAdapter.exitMultiSelectMode();
    }

    private boolean selectionToolbarSetup = false;

    private void setupSelectionToolbar() {
        if (selectionToolbarSetup) return;
        selectionToolbarSetup = true;

        android.view.View btnClose = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> {
            pagingAdapter.exitMultiSelectMode();
            hideMultiSelectBar();
        });

        android.view.View btnFwd = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_forward);
        if (btnFwd != null) btnFwd.setOnClickListener(v -> forwardSelectedMessages());

        android.view.View btnDel = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_delete);
        if (btnDel != null) btnDel.setOnClickListener(v -> {
            java.util.List<com.callx.app.models.Message> sel = pagingAdapter.getSelectedMessages();
            if (!sel.isEmpty()) {
                confirmDelete(sel.get(0));
                pagingAdapter.exitMultiSelectMode();
                hideMultiSelectBar();
            }
        });

        android.view.View btnStar = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_star);
        if (btnStar != null) btnStar.setOnClickListener(v -> {
            java.util.List<com.callx.app.models.Message> sel = pagingAdapter.getSelectedMessages();
            for (com.callx.app.models.Message m : sel) starredController.toggleStar(m);
            pagingAdapter.exitMultiSelectMode();
            hideMultiSelectBar();
        });

        android.view.View btnReply = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_reply);
        if (btnReply != null) btnReply.setOnClickListener(v -> {
            java.util.List<com.callx.app.models.Message> sel = pagingAdapter.getSelectedMessages();
            if (!sel.isEmpty()) startReply(sel.get(0));
            pagingAdapter.exitMultiSelectMode();
            hideMultiSelectBar();
        });
    }

    private void showMultiSelectBar(int count) {
        setupSelectionToolbar();
        binding.toolbar.setVisibility(android.view.View.GONE);
        android.view.View selBar = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.ll_selection_toolbar);
        if (selBar != null) selBar.setVisibility(android.view.View.VISIBLE);
        android.widget.TextView tvCount = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.tv_selection_count);
        if (tvCount != null) tvCount.setText(String.valueOf(count));
        android.view.View btnInfo = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.btn_selection_info);
        if (btnInfo != null) btnInfo.setVisibility(count == 1 ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void hideMultiSelectBar() {
        binding.toolbar.setVisibility(android.view.View.VISIBLE);
        android.view.View selBar = binding.getRoot().findViewById(
                com.callx.app.chat.R.id.ll_selection_toolbar);
        if (selBar != null) selBar.setVisibility(android.view.View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PINNED MESSAGE (Feature 8)
    // ─────────────────────────────────────────────────────────────────────

    private void setupPinnedBanner() {
        if (binding.llPinnedBanner == null) return;
        if (binding.btnUnpin != null)
            binding.btnUnpin.setOnClickListener(v -> {
                if (pinnedMsgId != null) unpinMessage(pinnedMsgId);
            });
    }

    private void watchPinnedMessage() {
        FirebaseUtils.getGroupsRef().child(groupId).child("pinnedMessageId")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        pinnedMsgId = s.getValue(String.class);
                        if (pinnedMsgId == null || pinnedMsgId.isEmpty()) {
                            hidePinnedBanner();
                        } else {
                            fetchAndShowPinnedBanner(pinnedMsgId);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    private void fetchAndShowPinnedBanner(String msgId) {
        groupMessagesRef.child(msgId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Message m = s.getValue(Message.class);
                if (m == null) { hidePinnedBanner(); return; }
                String txt = m.text != null ? m.text
                        : "[" + (m.type != null ? m.type : "media") + "]";
                if (binding.llPinnedBanner != null) {
                    if (binding.tvPinnedPreview != null)
                        binding.tvPinnedPreview.setText("📌  " + txt);
                    binding.llPinnedBanner.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void hidePinnedBanner() {
        if (binding.llPinnedBanner != null)
            binding.llPinnedBanner.setVisibility(View.GONE);
        pinnedMsgId = null;
    }

    private void unpinMessage(String msgId) {
        groupMessagesRef.child(msgId).child("pinned").setValue(false);
        FirebaseUtils.getGroupsRef().child(groupId).child("pinnedMessageId").removeValue();
        hidePinnedBanner();
        Toast.makeText(this, "Unpinned", Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // REAL-TIME HEADER (typing + member count)
    // ─────────────────────────────────────────────────────────────────────

    private void setupRealtimeHeader() {
        typingRef  = FirebaseUtils.getGroupTypingRef(groupId);
        membersRef = FirebaseUtils.getGroupMembersRef(groupId);

        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                typingNames.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getKey();
                    if (uid == null || uid.equals(currentUid)) continue;
                    Object val = c.getValue();
                    String name = val != null ? String.valueOf(val) : "";
                    if (name.isEmpty() || "true".equalsIgnoreCase(name))
                        name = memberNames.getOrDefault(uid, "Someone");
                    typingNames.put(uid, name);
                }
                refreshSubtitle();
                refreshTypingStrip();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        typingRef.addValueEventListener(typingListener);

        // Per-message "someone is replying to this" glow — aggregated across
        // every other member currently typing into the reply bar, mirrors
        // GroupWatchingController's per-message viewing-dot aggregation.
        typingReplyRef = FirebaseUtils.getChatTypingReplyRef(groupId);
        typingReplyListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                java.util.Set<String> ids = new HashSet<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getKey();
                    if (uid == null || uid.equals(currentUid)) continue;
                    String mid = c.getValue(String.class);
                    if (mid != null) ids.add(mid);
                }
                if (pagingAdapter != null) pagingAdapter.setReplyTargetMessageIds(ids);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        typingReplyRef.addValueEventListener(typingReplyListener);

        membersListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Set<String> latest = new HashSet<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getKey();
                    if (uid == null) continue;
                    latest.add(uid);
                    String name = c.child("name").getValue(String.class);
                    String role = c.child("role").getValue(String.class);
                    memberNames.put(uid, name != null ? name : "Member");
                    memberRoles.put(uid, role != null ? role : "member");
                }
                totalMembers = latest.size();
                for (String uid : latest) {
                    if (!presenceListeners.containsKey(uid) && !uid.equals(currentUid))
                        subscribePresence(uid);
                }
                refreshSubtitle();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        membersRef.addValueEventListener(membersListener);
        subtitleHandler.post(subtitleTick);
    }

    private void subscribePresence(String uid) {
        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Long ls = snap.child("lastSeen").getValue(Long.class);
                memberLastSeen.put(uid, ls != null ? ls : 0L);
                String photo = snap.child("photoUrl").getValue(String.class);
                if (photo != null) memberPhotos.put(uid, photo);
                refreshSubtitle();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        presenceListeners.put(uid, l);
        FirebaseUtils.getUserRef(uid).addValueEventListener(l);
    }

    // ── "Watching banner" (overlapping avatars for in-chat-screen members) ──

    private void setupGroupWatching() {
        watchingController = new GroupWatchingController(this);
        watchingController.init();
        starredController = new GroupStarredController(this);
    }

    @Override public Activity getActivity() { return this; }
    @Override public ActivityChatBinding getBinding() { return binding; }
    @Override public String getGroupId() { return groupId; }
    @Override public String getCurrentUid() { return currentUid; }

    // ── GroupStarredController.Delegate (getActivity/getGroupId above
    //    already satisfy two of its five methods) ────────────────────────
    @Override public AppDatabase getDb() { return db; }
    @Override public Executor getIoExecutor() { return ioExecutor; }
    @Override public DatabaseReference getGroupMessagesRef() { return groupMessagesRef; }
    // ─────────────────────────────────────────────────────────────────────
    // SCROLL TO MESSAGE BY ID — shared by reply "jump to original" and the
    // watching-banner "jump to their position" feature. If the message
    // isn't currently loaded in the paging adapter's window, falls back to
    // a Room lookup (same approach as ChatActivity's 1:1 equivalent) to
    // compute an approximate position and scroll there.
    // ─────────────────────────────────────────────────────────────────────

    private void scrollToMessageId(String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        for (int i = 0; i < pagingAdapter.getItemCount(); i++) {
            com.callx.app.models.Message m = pagingAdapter.peek(i);
            if (m != null && (messageId.equals(m.id) || messageId.equals(m.messageId))) {
                com.callx.app.chat.ui.MessageHighlightAnimator.scrollAndHighlight(
                    binding.rvMessages, i, binding.fabBackToLatest);
                return;
            }
        }
        final String gId = groupId;
        ioExecutor.execute(() -> {
            if (db == null || gId == null) {
                runOnUiThread(() -> android.widget.Toast.makeText(GroupChatActivity.this,
                        "Message not in view — scroll up to find it", android.widget.Toast.LENGTH_SHORT).show());
                return;
            }
            MessageEntity target = db.messageDao().getMessageById(messageId);
            if (target == null || target.timestamp == null) {
                runOnUiThread(() -> android.widget.Toast.makeText(GroupChatActivity.this,
                        "Original message not found", android.widget.Toast.LENGTH_SHORT).show());
                return;
            }
            int posFromBottom = db.messageDao().countMessagesAfterTimestamp(gId, target.timestamp);
            int approxPos = pagingAdapter.getItemCount() - posFromBottom - 1;
            final int safePos = Math.max(0, approxPos);
            runOnUiThread(() -> {
                if (binding.fabBackToLatest != null) {
                    binding.fabBackToLatest.setVisibility(View.VISIBLE);
                    binding.fabBackToLatest.setAlpha(1f);
                    binding.fabBackToLatest.setVisibility(View.VISIBLE);
                }
                binding.rvMessages.scrollToPosition(safePos);
                binding.rvMessages.postDelayed(() -> {
                    RecyclerView.ViewHolder vh = binding.rvMessages.findViewHolderForAdapterPosition(safePos);
                    if (vh != null) com.callx.app.chat.ui.MessageHighlightAnimator.flashHighlight(vh.itemView);
                }, 500);
            });
        });
    }

    /** GroupWatchingController.Delegate — "jump to their position" entry point. */
    @Override public void navigateToMessage(String messageId) { scrollToMessageId(messageId); }

    @Override public Map<String, String> getMemberNames() { return memberNames; }
    @Override public Map<String, String> getMemberPhotos() { return memberPhotos; }
    @Override public MessagePagingAdapter getPagingAdapter() { return pagingAdapter; }



    private void setMyTyping(boolean typing) {
        DatabaseReference replyRef = FirebaseUtils.getChatTypingReplyRef(groupId).child(currentUid);
        if (typing == amTyping) {
            // Typing state itself unchanged — still re-publish the reply
            // target in case the user switched which message they're
            // replying to without toggling typing off in between.
            publishMyTypingReplyTarget(replyRef);
            return;
        }
        amTyping = typing;
        DatabaseReference me = FirebaseUtils.getGroupTypingRef(groupId).child(currentUid);
        if (typing) {
            me.setValue(currentName != null ? currentName : "Someone");
            me.onDisconnect().removeValue();
            publishMyTypingReplyTarget(replyRef);
        } else {
            me.removeValue();
            lastWrittenReplyTargetId = null;
            replyRef.removeValue();
            replyRef.onDisconnect().cancel();
        }
    }

    /** uid of whatever message id we last actually wrote to
     *  chatTypingReply/{groupId}/{currentUid}, so unchanged values (e.g.
     *  still typing, reply bar unchanged) skip the Firebase round trip. */
    private String lastWrittenReplyTargetId = null;

    private void publishMyTypingReplyTarget(DatabaseReference replyRef) {
        String targetId = replyingTo != null
                ? (replyingTo.messageId != null ? replyingTo.messageId : replyingTo.id)
                : null;
        if (targetId == null ? lastWrittenReplyTargetId == null : targetId.equals(lastWrittenReplyTargetId)) {
            return;
        }
        lastWrittenReplyTargetId = targetId;
        if (targetId == null) {
            replyRef.removeValue();
            replyRef.onDisconnect().cancel();
        } else {
            replyRef.setValue(targetId);
            replyRef.onDisconnect().removeValue();
        }
    }

    private void refreshSubtitle() {
        if (getSupportActionBar() == null) return;
        // Typing no longer shown in the subtitle — see ll_typing_strip
        // (floating bottom-left pill, driven by refreshTypingStrip()).
        long now = System.currentTimeMillis();
        int online = 0;
        for (Long ls : memberLastSeen.values())
            if (ls != null && (now - ls) < com.callx.app.utils.Constants.ONLINE_WINDOW_MS) online++;
        int total = totalMembers > 0 ? totalMembers : (memberLastSeen.size() + 1);
        String sub = total + (total == 1 ? " member" : " members");
        if (online > 0) sub = online + " online, " + sub;
        getSupportActionBar().setSubtitle(sub);
    }

    // ── Typing strip (floating bottom-left, avatar + name + animated dots) ──
    // Mirrors ChatPresenceController's 1:1 typing strip, but aggregates
    // potentially multiple simultaneous typers from typingNames (uid -> name,
    // populated by the existing typingListener in setupRealtimeHeader()).

    private com.callx.app.chat.ui.TypingDotsAnimator typingDotsAnimator;
    /** Mirrors ChatPresenceController's same-named flag — true while this
     *  screen is paused/backgrounded, so the dots loop stays stopped even
     *  if typingNames is non-empty. */
    private boolean screenPaused = false;

    /** Call from onPause(). Stops the dots loop only — the strip itself
     *  stays as-is (visible/hidden), and the underlying typingListener
     *  keeps tracking typingNames in the background exactly like before. */
    private void onTypingStripScreenPaused() {
        screenPaused = true;
        if (typingDotsAnimator != null) typingDotsAnimator.stop();
    }

    /** Call from onResume(). Restarts the dots loop if someone is (still)
     *  typing — covers backgrounding the app mid-typing-burst. */
    private void onTypingStripScreenResumed() {
        screenPaused = false;
        if (!typingNames.isEmpty() && binding.llTypingStrip != null
                && binding.llTypingStrip.getVisibility() == View.VISIBLE
                && typingDotsAnimator != null) {
            typingDotsAnimator.start();
        }
    }

    private void refreshTypingStrip() {
        if (binding.llTypingStrip == null) return;
        if (typingNames.isEmpty()) {
            hideTypingStrip();
            return;
        }

        int n = typingNames.size();
        java.util.Iterator<Map.Entry<String, String>> it = typingNames.entrySet().iterator();
        Map.Entry<String, String> first = it.next();
        String label;
        if (n == 1) {
            label = first.getValue() + " typing";
        } else if (n == 2) {
            Map.Entry<String, String> second = it.next();
            label = first.getValue() + ", " + second.getValue() + " typing";
        } else {
            label = first.getValue() + " +" + (n - 1) + " typing";
        }
        binding.tvTypingName.setText(label);

        String photo = memberPhotos.get(first.getKey());
        if (photo != null && !photo.isEmpty()) {
            com.bumptech.glide.Glide.with(this)
                    .load(photo)
                    .placeholder(com.callx.app.chat.R.drawable.ic_person)
                    .into(binding.ivTypingAvatar);
        } else {
            binding.ivTypingAvatar.setImageResource(com.callx.app.chat.R.drawable.ic_person);
        }

        if (typingDotsAnimator == null) {
            typingDotsAnimator = new com.callx.app.chat.ui.TypingDotsAnimator(
                    binding.dotTyping1, binding.dotTyping2, binding.dotTyping3);
        }
        // Don't start the bounce loop while backgrounded — onTypingStripScreenResumed()
        // will pick it back up once we're visible again.
        if (!screenPaused) typingDotsAnimator.start();

        if (binding.llTypingStrip.getVisibility() == View.VISIBLE) {
            // Already showing — still make sure the watching banner reflects
            // current priority.
            com.callx.app.chat.ui.BannerPriorityCoordinator.onTypingStripShown(
                    binding.llWatchingBanner, binding.llTypingStrip);
            return;
        }

        binding.llTypingStrip.setAlpha(1f);
        binding.llTypingStrip.setScaleX(1f);
        binding.llTypingStrip.setScaleY(1f);
        binding.llTypingStrip.setVisibility(View.VISIBLE);
        com.callx.app.chat.ui.BannerPriorityCoordinator.onTypingStripShown(
                binding.llWatchingBanner, binding.llTypingStrip);
    }

    private void hideTypingStrip() {
        if (binding.llTypingStrip == null) return;
        if (binding.llTypingStrip.getVisibility() != View.VISIBLE) return;

        if (typingDotsAnimator != null) typingDotsAnimator.stop();
        binding.llTypingStrip.setVisibility(View.GONE);
        binding.llTypingStrip.setAlpha(1f);
        binding.llTypingStrip.setScaleX(1f);
        binding.llTypingStrip.setScaleY(1f);
        com.callx.app.chat.ui.BannerPriorityCoordinator.onTypingStripHidden(
                binding.llWatchingBanner);
    }

    // ─────────────────────────────────────────────────────────────────────
    // ADMIN CONTROLS (Feature 9)
    // ─────────────────────────────────────────────────────────────────────

    private void checkAdminStatus() {
        FirebaseUtils.getGroupMembersRef(groupId).child(currentUid).child("role")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        isAdmin = "admin".equals(s.getValue(String.class));
                        invalidateOptionsMenu();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    private void showAdminPanel() {
        List<String> display = new ArrayList<>(), uids = new ArrayList<>();
        for (Map.Entry<String, String> e : memberNames.entrySet()) {
            if (e.getKey().equals(currentUid)) continue;
            uids.add(e.getKey());
            String role = memberRoles.get(e.getKey());
            display.add(e.getValue() + ("admin".equals(role) ? "  👑" : ""));
        }
        if (display.isEmpty()) {
            Toast.makeText(this, "No other members", Toast.LENGTH_SHORT).show(); return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Group Members")
                .setItems(display.toArray(new String[0]),
                        (d, i) -> showMemberOptions(uids.get(i)))
                .show();
    }

    private void showMemberOptions(String uid) {
        String name   = memberNames.getOrDefault(uid, "Member");
        boolean isAdm = "admin".equals(memberRoles.getOrDefault(uid, "member"));
        String[] opts = { "Remove from group", isAdm ? "Revoke admin" : "Make admin" };
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(opts, (d, which) -> {
                    if (which == 0) confirmRemoveMember(uid, name);
                    else            toggleMemberAdmin(uid, name, isAdm);
                }).show();
    }

    private void confirmRemoveMember(String uid, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setPositiveButton("Remove", (d, w) -> {
                    FirebaseUtils.getGroupMembersRef(groupId).child(uid).removeValue();
                    FirebaseUtils.db().getReference("users")
                            .child(uid).child("groups").child(groupId).removeValue();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void toggleMemberAdmin(String uid, String name, boolean wasAdmin) {
        String newRole = wasAdmin ? "member" : "admin";
        FirebaseUtils.getGroupMembersRef(groupId).child(uid).child("role").setValue(newRole);
        memberRoles.put(uid, newRole);
        Toast.makeText(this, name + (wasAdmin ? ": admin revoked" : ": now admin 👑"),
                Toast.LENGTH_SHORT).show();
    }

    private void renameGroup() {
        EditText et = new EditText(this);
        et.setText(groupName);
        et.setSelection(et.getText().length());
        int p = dp(16); et.setPadding(p, p, p, p);
        new AlertDialog.Builder(this)
                .setTitle("Rename Group")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newName = et.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    groupName = newName;
                    FirebaseUtils.getGroupsRef().child(groupId).child("name").setValue(newName);
                    if (getSupportActionBar() != null) getSupportActionBar().setTitle(newName);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // INVITE LINK (Feature 10)
    // ─────────────────────────────────────────────────────────────────────

    private void shareInviteLink() {
        String link = com.callx.app.utils.Constants.DEEP_LINK_BASE_URL + "/join/" + groupId;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, "Join my group on CallX: " + link);
        startActivity(Intent.createChooser(i, "Share invite link"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // MEDIA
    // ─────────────────────────────────────────────────────────────────────

    private void setupPickers() {
        wallpaperPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    try {
                        getContentResolver().takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (SecurityException ignored) {}
                    showWallpaperScopeDialog(uri);
                });
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image", "image", null); });
        videoPicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "video", "video", null); });
        audioPicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "audio", "raw", null); });
        filePicker  = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null)
                    uploadAndSend(uri, "file", "raw", FileUtils.fileName(this, uri)); });
    }

    private void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_attach, null);
        v.findViewById(R.id.opt_gallery).setOnClickListener(x -> { sheet.dismiss(); imagePicker.launch("image/*"); });
        v.findViewById(R.id.opt_video).setOnClickListener(x  -> { sheet.dismiss(); videoPicker.launch("video/*"); });
        v.findViewById(R.id.opt_audio).setOnClickListener(x  -> { sheet.dismiss(); audioPicker.launch("audio/*"); });
        v.findViewById(R.id.opt_file).setOnClickListener(x   -> { sheet.dismiss(); filePicker.launch("*/*"); });
        View optPoll = v.findViewById(R.id.opt_poll);
        if (optPoll != null) {
            optPoll.setOnClickListener(x -> { sheet.dismiss(); showCreatePollDialog(); });
        }
        sheet.setContentView(v); sheet.show();
    }

    // ─────────────────────────────────────────────────────────────────────
    // GIF MESSAGE — Google Keyboard se aaya GIF send karo
    // ─────────────────────────────────────────────────────────────────────

    private void sendGifMessage(Uri gifUri, androidx.core.view.inputmethod.InputContentInfoCompat contentInfo) {
        if (gifUri == null) {
            if (contentInfo != null) contentInfo.releasePermission();
            return;
        }
        if (!isOnline()) {
            if (contentInfo != null) contentInfo.releasePermission();
            Toast.makeText(this, "No connection — GIF send nahi ho sakta", Toast.LENGTH_SHORT).show();
            return;
        }
        binding.uploadProgress.setVisibility(View.VISIBLE);
        Toast.makeText(this, "GIF bhej raha hai...", Toast.LENGTH_SHORT).show();
        CloudinaryUploader.upload(this, gifUri, "callx/gif", "image",
                new CloudinaryUploader.UploadCallback() {
                    @Override
                    public void onSuccess(CloudinaryUploader.Result r) {
                        if (contentInfo != null) contentInfo.releasePermission();
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m  = buildOutgoing();
                        m.type     = "gif";
                        // Cloudinary URL as-is use karo — m.type="gif" se Glide
                        // asGif() use karega. URL pe .gif append karna GALAT tha —
                        // Cloudinary URL break ho jaata tha, GIF blank dikhta tha.
                        String gifUrl = r.secureUrl;
                        m.mediaUrl = gifUrl;
                        m.imageUrl = gifUrl;
                        pushMessage(m, "🎞️ GIF");
                    }
                    @Override
                    public void onError(String err) {
                        if (contentInfo != null) contentInfo.releasePermission();
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(GroupChatActivity.this,
                                err != null ? err : "GIF upload failed",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void uploadAndSend(Uri uri, String msgType, String resourceType, String fileName) {
        // OFFLINE FIX: Media upload needs internet — check before starting
        if (!isOnline()) {
            Toast.makeText(this,
                "No connection — media send kar'ne ke liye internet chahiye",
                Toast.LENGTH_LONG).show();
            return;
        }

        binding.uploadProgress.setVisibility(View.VISIBLE);

        // Video: v21 pipeline — thumbnail + compress + dual Firebase upload
        if ("video".equals(msgType)) {
            binding.uploadProgress.setIndeterminate(false);
            binding.uploadProgress.setMax(100);
            binding.uploadProgress.setProgress(0);

            com.callx.app.utils.VideoCompressor.compress(
                    this, uri, new com.callx.app.utils.VideoCompressor.Callback() {

                @Override
                public void onProgress(int percent) {
                    binding.uploadProgress.setProgress(percent / 2);
                }

                @Override
                public void onSuccess(com.callx.app.utils.VideoCompressor.Result result) {
                    com.callx.app.utils.VideoUploader.upload(
                            GroupChatActivity.this, result,
                            new com.callx.app.utils.VideoUploader.UploadCallback() {

                        @Override
                        public void onProgress(int percent) {
                            binding.uploadProgress.setProgress(50 + percent / 2);
                        }

                        @Override
                        public void onSuccess(String thumbUrl, String videoUrl,
                                              int durationMs, int width, int height) {
                            binding.uploadProgress.setVisibility(View.GONE);
                            Message m      = buildOutgoing();
                            m.type         = "video";
                            m.mediaUrl     = videoUrl;
                            m.thumbnailUrl = thumbUrl;
                            m.duration     = (long) durationMs;
                            pushMessage(m, "\uD83C\uDFAC Video");
                            clearReply();
                        }

                        @Override
                        public void onError(Exception e) {
                            binding.uploadProgress.setVisibility(View.GONE);
                            Toast.makeText(GroupChatActivity.this,
                                    e != null ? e.getMessage() : "Upload failed",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }

                @Override
                public void onError(Exception e) {
                    android.util.Log.w("GroupChat", "Video compress failed, fallback", e);
                    doUpload(uri, msgType, resourceType, fileName);
                }
            });
        } else {
            doUpload(uri, msgType, resourceType, fileName);
        }
    }

    private void doUpload(Uri uri, String msgType, String resourceType, String fileName) {
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri, "callx/" + msgType, resourceType,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result r) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m = buildOutgoing();
                        m.type    = msgType;
                        m.mediaUrl = r.secureUrl;
                        m.imageUrl = "image".equals(msgType) ? r.secureUrl : null;
                        m.fileName = fileName;
                        m.fileSize = r.bytes != null ? r.bytes : size;
                        m.duration = r.durationMs;
                        String preview = mediaPreview(msgType, fileName);
                        pushMessage(m, preview);
                        clearReply();
                    }
                    @Override public void onError(String err) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(GroupChatActivity.this,
                                err != null ? err : "Upload failed", Toast.LENGTH_LONG).show();
                    }
                });
    }

    private static String mediaPreview(String type, String fileName) {
        switch (type) {
            case "image": return "📷 Photo";
            case "video": return "🎬 Video";
            case "audio": return "🎤 Voice";
            case "file":  return "📎 " + (fileName != null ? fileName : "File");
            default:      return "Media";
        }
    }

    private void toggleRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (!isRecording) {
            if (recorder.start(this)) {
                isRecording = true;
                binding.btnMic.setBackgroundResource(R.drawable.circle_reject);
            }
        } else {
            isRecording = false;
            binding.btnMic.setBackgroundResource(R.drawable.circle_primary);
            Uri u = recorder.stop(this);
            if (u != null) uploadAndSend(u, "audio", "raw", null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == REQ_AUDIO && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
            toggleRecording();
    }

    // ─────────────────────────────────────────────────────────────────────
    // MENU
    // ─────────────────────────────────────────────────────────────────────

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, R.id.menu_group_info, 0, "ℹ Group Info")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, R.id.menu_group_settings, 1, "⚙ Group Settings")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, R.id.menu_invite, 2, "🔗 Invite Link")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, R.id.menu_starred, 3, "⭐ Starred Messages")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, R.id.action_chat_customization, 4, "🎨 Chat Customization")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, R.id.action_chat_privacy, 5, "🛡 Chat Privacy")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        if (isAdmin) {
            menu.add(0, R.id.menu_admin_panel, 4, "👑 Admin Panel")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            menu.add(0, R.id.menu_rename, 5, "✏ Rename Group")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_group_info) {
            Intent i = new Intent(this, GroupInfoActivity.class);
            i.putExtra(GroupInfoActivity.EXTRA_GROUP_ID,   groupId);
            i.putExtra(GroupInfoActivity.EXTRA_GROUP_NAME, groupName);
            startActivity(i); return true;
        }
        if (id == R.id.menu_group_settings) {
            Intent i = new Intent(this, GroupSettingsActivity.class);
            i.putExtra(GroupSettingsActivity.EXTRA_GROUP_ID,   groupId);
            i.putExtra(GroupSettingsActivity.EXTRA_GROUP_NAME, groupName);
            startActivity(i); return true;
        }
        if (id == R.id.menu_invite)      { shareInviteLink(); return true; }
        if (id == R.id.menu_starred) {
            starredController.openManageList();
            return true;
        }
        if (id == R.id.menu_admin_panel) { if (isAdmin) showAdminPanel(); return true; }
        if (id == R.id.menu_rename)      { if (isAdmin) renameGroup(); return true; }
        if (id == R.id.action_chat_customization) { showChatCustomizationMenu(); return true; }
        if (id == R.id.action_chat_privacy) { showGroupChatPrivacySheet(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showGroupChatPrivacySheet() {
        com.callx.app.chat.ui.ChatPrivacyBottomSheet sheet =
                com.callx.app.chat.ui.ChatPrivacyBottomSheet.newInstance(
                        groupId, true, groupName != null ? groupName : "Group");
        sheet.show(getSupportFragmentManager(),
                com.callx.app.chat.ui.ChatPrivacyBottomSheet.TAG);
    }

    // ── Apply Chat Screen Theme ───────────────────────────────────────────
    private void applyScreenTheme() {
        com.callx.app.utils.ChatThemeManager mgr =
                com.callx.app.utils.ChatThemeManager.get(this);

        android.view.View toolbar   = binding.toolbar;
        android.view.View chatRoot  = binding.getRoot();
        android.view.View inputRow  = binding.llInputRow;
        android.view.View replyAccent = binding.viewReplyAccent;

        if (binding.tvReplyBarName != null) {
            binding.tvReplyBarName.setTextColor(mgr.getPrimaryColor());
        }

        mgr.applyScreenTheme(
                toolbar,
                chatRoot,
                inputRow,
                binding.btnSend,
                binding.btnMic,
                binding.fabBackToLatest,
                replyAccent);

        // Apply wallpaper
        applyWallpaper();
    }

    // ── Wallpaper apply ───────────────────────────────────────────────────
    private void applyWallpaper() {
        android.widget.ImageView ivWall = binding.ivChatWallpaper;
        if (ivWall == null) return;
        String uriStr = com.callx.app.utils.ChatWallpaperManager.get(this)
                            .getEffectiveWallpaper(groupId);
        if (uriStr == null) {
            ivWall.setVisibility(android.view.View.GONE);
            ivWall.setImageDrawable(null);
        } else {
            ivWall.setVisibility(android.view.View.VISIBLE);
            com.bumptech.glide.Glide.with(this)
                 .load(android.net.Uri.parse(uriStr))
                 .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                 .centerCrop()
                 .into(ivWall);
        }
    }

    // ── Chat Customization submenu (wallpaper only) ──────────────────────
    private void showChatCustomizationMenu() {
        showWallpaperPicker();
    }

    // ── Wallpaper Picker ──────────────────────────────────────────────────
    private void showWallpaperPicker() {
        wallpaperPicker.launch("image/*");
    }

    private void showWallpaperScopeDialog(android.net.Uri uri) {
        com.callx.app.utils.ChatWallpaperManager wm =
                com.callx.app.utils.ChatWallpaperManager.get(this);
        String[] options = {"🙋 This group only", "🌐 All chats (Global)", "❌ Remove wallpaper"};
        new android.app.AlertDialog.Builder(this)
            .setTitle("🖼️ Set Wallpaper")
            .setItems(options, (d, which) -> {
                if (which == 0) {
                    wm.setWallpaper(groupId, uri);
                    applyWallpaper();
                } else if (which == 1) {
                    wm.setGlobalWallpaper(uri);
                    applyWallpaper();
                } else {
                    wm.clearWallpaper(groupId);
                    wm.clearGlobalWallpaper();
                    applyWallpaper();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }





    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
