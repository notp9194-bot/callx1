package com.callx.app.activities;

import android.Manifest;
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

import com.callx.app.R;
import com.callx.app.adapters.MessagePagingAdapter;
import com.callx.app.cache.CacheManager;
import com.callx.app.databinding.ActivityChatBinding;
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
public class GroupChatActivity extends AppCompatActivity {

    private static final String TAG           = "GroupChatActivity";
    private static final int    PAGE_SIZE     = 20;
    private static final int    INITIAL_LOAD  = 40;
    private static final int    PREFETCH_DIST = 10;
    private static final int    REQ_AUDIO     = 200;

    // ── View binding ───────────────────────────────────────────────────────
    private ActivityChatBinding binding;

    // ── Identifiers ────────────────────────────────────────────────────────
    private String groupId, groupName, currentUid, currentName;

    // ── Paging 3 (FIX #7) ─────────────────────────────────────────────────
    private MessagePagingAdapter pagingAdapter;
    private AppDatabase          db;
    private final Executor       ioExecutor = Executors.newFixedThreadPool(2);

    // ── Firebase refs ──────────────────────────────────────────────────────
    private DatabaseReference  groupMessagesRef;
    private ChildEventListener messageListener;
    private DatabaseReference  typingRef;
    private ValueEventListener typingListener;
    private DatabaseReference  membersRef;
    private ValueEventListener membersListener;
    private final Map<String, ValueEventListener> presenceListeners = new HashMap<>();

    // ── State ──────────────────────────────────────────────────────────────
    private boolean isAdmin    = false;
    private boolean isRecording = false;
    private final Map<String, String> memberNames = new HashMap<>();
    private final Map<String, String> memberRoles = new HashMap<>();
    private final Map<String, Long>   memberLastSeen = new HashMap<>();
    private final Map<String, String> typingNames    = new HashMap<>();
    private int   totalMembers = 0;
    private boolean amTyping   = false;

    // ── Handlers ───────────────────────────────────────────────────────────
    private final android.os.Handler typingHandler  = new android.os.Handler(android.os.Looper.getMainLooper());
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

        groupId     = getIntent().getStringExtra("groupId");
        groupName   = getIntent().getStringExtra("groupName");
        if (groupId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        currentName = FirebaseUtils.getCurrentName();
        groupMessagesRef = FirebaseUtils.getGroupMessagesRef(groupId);

        db = AppDatabase.getInstance(this);

        setupToolbar();
        setupPickers();

        // ── FIX #7: Paging 3 wiring ──
        setupPagingRecyclerView();
        observePagedMessages();
        startRealtimeListener();

        setupInputBar();
        setupPinnedBanner();
        setupReplyCancel();
        setupRealtimeHeader();
        checkAdminStatus();
        watchPinnedMessage();

        // ── Task 5: Offline banner + message pruning ──
        setupNetworkMonitor();
        ioExecutor.execute(() -> db.messageDao().pruneOldMessages(groupId, 500));

        // Analytics + delta sync + predictive preload
        CacheManager.getInstance(this).getAnalytics().recordChatOpen(groupId);
        ChatRepository.getInstance(this).preloadRecentChats(groupId);
    }

    @Override
    protected void onDestroy() {
        subtitleHandler.removeCallbacks(subtitleTick);
        typingHandler.removeCallbacks(stopTyping);
        setMyTyping(false);
        if (groupMessagesRef != null && messageListener != null)
            groupMessagesRef.removeEventListener(messageListener);
        if (typingRef  != null && typingListener  != null)
            typingRef.removeEventListener(typingListener);
        if (membersRef != null && membersListener != null)
            membersRef.removeEventListener(membersListener);
        for (Map.Entry<String, ValueEventListener> e : presenceListeners.entrySet())
            FirebaseUtils.getUserRef(e.getKey()).removeEventListener(e.getValue());
        presenceListeners.clear();
        if (connMgr != null && netCallback != null) {
            try { connMgr.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        typingHandler.removeCallbacks(stopTyping);
        setMyTyping(false);
        super.onPause();
    }

    // ─────────────────────────────────────────────────────────────────────
    // NETWORK MONITORING (Task 5)
    // ─────────────────────────────────────────────────────────────────────

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
                binding.getRoot().findViewById(com.callx.app.R.id.tv_offline_banner);
        if (banner != null) banner.setVisibility(offline ? View.VISIBLE : View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TOOLBAR
    // ─────────────────────────────────────────────────────────────────────

    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(groupName != null ? groupName : "Group");
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());
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
            @Override public void onStar(Message m)                { toggleStar(m); }
            @Override public void onCopy(Message m)                { copyText(m); }
            @Override public void onForward(Message m)             { forwardMessage(m); }
        });

        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(llm);
        binding.rvMessages.setAdapter(pagingAdapter);

        // Auto-scroll when new message arrives at tail
        pagingAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override public void onItemRangeInserted(int positionStart, int itemCount) {
                super.onItemRangeInserted(positionStart, itemCount);
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
                if (key != null) ioExecutor.execute(() -> db.messageDao().softDelete(key));
            }
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {}
        };
        query.addChildEventListener(messageListener);
    }

    private void saveToRoom(Message m) {
        ioExecutor.execute(() -> db.messageDao().insertMessage(modelToEntity(m)));
        // Room auto-invalidates PagingSource — no explicit invalidate() needed
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
        m.deleted           = e.deleted;
        m.forwardedFrom     = e.forwardedFrom;
        m.starred           = e.starred;
        m.pinned            = e.pinned;
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
        e.deleted               = m.deleted;
        e.forwardedFrom         = m.forwardedFrom;
        e.starred               = Boolean.TRUE.equals(m.starred);
        e.pinned                = Boolean.TRUE.equals(m.pinned);
        e.isGroup               = true;
        e.syncedAt              = System.currentTimeMillis();
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
    }

    private void sendText() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        binding.etMessage.setText("");
        typingHandler.removeCallbacks(stopTyping);
        setMyTyping(false);
        Message m = buildOutgoing();
        m.type = "text";
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

    // v17: Local-first push — pehle Room (pending), phir Firebase
    private void pushMessage(Message m, String preview) {
        String key = groupMessagesRef.push().getKey();
        if (key == null) return;
        m.id = key;

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

    private void toggleStar(Message m) {
        boolean nowStarred = !Boolean.TRUE.equals(m.starred);
        ioExecutor.execute(() -> db.messageDao().updateStarred(m.id, nowStarred));
        groupMessagesRef.child(m.id).child("starred").setValue(nowStarred);
    }

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
        Intent i = new Intent(this, ContactsActivity.class);
        i.putExtra("forwardText",  m.text);
        i.putExtra("forwardType",  m.type);
        i.putExtra("forwardMedia", m.mediaUrl);
        startActivity(i);
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
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        typingRef.addValueEventListener(typingListener);

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
                refreshSubtitle();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        presenceListeners.put(uid, l);
        FirebaseUtils.getUserRef(uid).addValueEventListener(l);
    }

    private void setMyTyping(boolean typing) {
        if (typing == amTyping) return;
        amTyping = typing;
        DatabaseReference me = FirebaseUtils.getGroupTypingRef(groupId).child(currentUid);
        if (typing) {
            me.setValue(currentName != null ? currentName : "Someone");
            me.onDisconnect().removeValue();
        } else {
            me.removeValue();
        }
    }

    private void refreshSubtitle() {
        if (getSupportActionBar() == null) return;
        if (!typingNames.isEmpty()) {
            int n = typingNames.size();
            String sub;
            if (n == 1) sub = typingNames.values().iterator().next() + " is typing…";
            else        sub = n + " people are typing…";
            getSupportActionBar().setSubtitle(sub);
            return;
        }
        long now = System.currentTimeMillis();
        int online = 0;
        for (Long ls : memberLastSeen.values())
            if (ls != null && (now - ls) < com.callx.app.utils.Constants.ONLINE_WINDOW_MS) online++;
        int total = totalMembers > 0 ? totalMembers : (memberLastSeen.size() + 1);
        String sub = total + (total == 1 ? " member" : " members");
        if (online > 0) sub = online + " online, " + sub;
        getSupportActionBar().setSubtitle(sub);
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
        String link = "https://callx.app/join/" + groupId;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, "Join my group on CallX: " + link);
        startActivity(Intent.createChooser(i, "Share invite link"));
    }

    // ─────────────────────────────────────────────────────────────────────
    // MEDIA
    // ─────────────────────────────────────────────────────────────────────

    private void setupPickers() {
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image", "image", null); });
        videoPicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "video", "video", null); });
        audioPicker = registerForActivityResult(new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "audio", "video", null); });
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
        sheet.setContentView(v); sheet.show();
    }

    private void uploadAndSend(Uri uri, String msgType, String resourceType, String fileName) {
        binding.uploadProgress.setVisibility(View.VISIBLE);

        // Video: compress before upload (Task 4)
        if ("video".equals(msgType)) {
            java.io.File outFile = new java.io.File(getCacheDir(),
                    "vc_out_" + System.currentTimeMillis() + ".mp4");
            com.callx.app.utils.MediaCompressor.compressVideo(this, uri, outFile,
                    new com.callx.app.utils.MediaCompressor.VideoCallback() {
                        @Override public void onDone(java.io.File f) {
                            doUpload(android.net.Uri.fromFile(f), msgType, resourceType, fileName);
                        }
                        @Override public void onError(String msg) {
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
            if (u != null) uploadAndSend(u, "audio", "video", null);
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
            Intent i = new Intent(this, StarredMessagesActivity.class);
            i.putExtra("chatId",  groupId);
            i.putExtra("isGroup", true);
            startActivity(i); return true;
        }
        if (id == R.id.menu_admin_panel) { if (isAdmin) showAdminPanel(); return true; }
        if (id == R.id.menu_rename)      { if (isAdmin) renameGroup(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
