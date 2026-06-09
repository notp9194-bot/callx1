package com.callx.app.group;

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
import com.callx.app.starred.StarredMessagesActivity;
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
public class GroupChatActivity extends AppCompatActivity {

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

        // Fix 10: Group unread counter reset karo jab chat khulo
        // Server pe increment hota tha lekin reset call missing tha — badge badhta rehta tha
        if (currentUid != null && !currentUid.isEmpty()) {
            FirebaseUtils.db().getReference("groups")
                .child(groupId).child("unread").child(currentUid).setValue(0);
        }
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
            @Override public void onStar(Message m)                { toggleStar(m); }
            @Override public void onCopy(Message m)                { copyText(m); }
            @Override public void onForward(Message m)             { forwardMessage(m); }
            @Override public void onNavigateToOriginal(String messageId) {
                if (messageId == null || messageId.isEmpty()) return;
                for (int i = 0; i < pagingAdapter.getItemCount(); i++) {
                    com.callx.app.models.Message m = pagingAdapter.peek(i);
                    if (m != null && (messageId.equals(m.id) || messageId.equals(m.messageId))) {
                        com.callx.app.chat.ui.MessageHighlightAnimator.scrollAndHighlight(
                            binding.rvMessages, i, null);
                        return;
                    }
                }
                android.widget.Toast.makeText(GroupChatActivity.this,
                    "Original message not loaded — scroll up to find it",
                    android.widget.Toast.LENGTH_SHORT).show();
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
                markDelivered(m);
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
        m.fontStyle         = e.fontStyle;  // FIX: typing style — Room se load hone par preserve karo
        m.isGroup           = true;
        // FIX: Message info timestamps — Room se restore karo
        m.editedAt          = e.editedAt;
        m.deliveredAt       = e.deliveredAt;
        m.readAt            = e.readAt;
        // FIX: Group read maps — JSON → Map
        if (e.deliveredToJson != null && !e.deliveredToJson.isEmpty()) {
            m.deliveredTo = com.callx.app.conversation.MessageInfoActivity.parseReadMap(e.deliveredToJson);
        }
        if (e.readByJson != null && !e.readByJson.isEmpty()) {
            m.readBy = com.callx.app.conversation.MessageInfoActivity.parseReadMap(e.readByJson);
        }
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
        e.fontStyle             = m.fontStyle;
        // FIX: Message info timestamps — Room mein persist karo
        e.editedAt              = m.editedAt;
        e.deliveredAt           = m.deliveredAt;
        e.readAt                = m.readAt;
        // FIX: Group read maps — Map → JSON string
        if (m.deliveredTo != null && !m.deliveredTo.isEmpty()) {
            e.deliveredToJson = mapToJson(m.deliveredTo);
        }
        if (m.readBy != null && !m.readBy.isEmpty()) {
            e.readByJson = mapToJson(m.readBy);
        }
        return e;
    }

    private static String mapToJson(java.util.Map<String, Long> map) {
        if (map == null || map.isEmpty()) return "{}";
        org.json.JSONObject obj = new org.json.JSONObject();
        try {
            for (java.util.Map.Entry<String, Long> entry : map.entrySet()) {
                obj.put(entry.getKey(), entry.getValue());
            }
        } catch (org.json.JSONException ignored) {}
        return obj.toString();
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
        binding.etMessage.post(() ->
            com.callx.app.utils.TypingStyleManager.get(this).applyToInput(binding.etMessage)
        );
        typingHandler.removeCallbacks(stopTyping);
        setMyTyping(false);
        Message m = buildOutgoing();
        m.type = "text";
        m.fontStyle = com.callx.app.utils.TypingStyleManager.get(this).getCurrentStyle();
        // Samsung Script style — text ko Unicode Mathematical Script mein convert karo
        if (m.fontStyle == com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG_SCRIPT) {
            text = com.callx.app.utils.UnicodeStyler.toScript(text);
        }
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

    private void markDelivered(Message m) {
        if (m == null || m.id == null) return;
        if (currentUid == null || currentUid.equals(m.senderId)) return;
        long now = System.currentTimeMillis();
        groupMessagesRef.child(m.id).child("deliveredTo").child(currentUid).setValue(now);
        ioExecutor.execute(() -> db.messageDao().updateDeliveredAt(m.id, now));
    }

    private void markRead(Message m) {
        if (m == null || m.id == null || currentUid.equals(m.senderId)) return;
        long now = System.currentTimeMillis();
        // FIX: Long timestamp (millis) — was Boolean true, MessageInfoActivity expects Long
        groupMessagesRef.child(m.id).child("readBy").child(currentUid).setValue(now);
        ioExecutor.execute(() -> db.messageDao().updateReadAt(m.id, now));
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
            for (com.callx.app.models.Message m : sel) toggleStar(m);
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
            Intent i = new Intent(this, StarredMessagesActivity.class);
            i.putExtra("chatId",  groupId);
            i.putExtra("isGroup", true);
            startActivity(i); return true;
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

        com.callx.app.utils.TypingStyleManager.get(this).applyToInput(binding.etMessage);

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

    // ── Chat Customization submenu ────────────────────────────────────────
    private void showChatCustomizationMenu() {
        com.callx.app.chat.ui.ChatCustomizationBottomSheet sheet =
                com.callx.app.chat.ui.ChatCustomizationBottomSheet.newInstance();
        sheet.setOnOptionSelectedListener(option -> {
            switch (option) {
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_WALLPAPER:
                    showWallpaperPicker();   break;
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_THEME:
                    showThemePicker();       break;
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_BUBBLE:
                    showBubbleShapePicker(); break;
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_TYPING:
                    showTypingStylePicker(); break;
                case com.callx.app.chat.ui.ChatCustomizationBottomSheet.OPTION_FONT_SIZE:
                    showFontSizePicker();    break;
            }
        });
        sheet.show(getSupportFragmentManager(),
                com.callx.app.chat.ui.ChatCustomizationBottomSheet.TAG);
    }

    private void showFontSizePicker() {
        com.callx.app.chat.ui.MessageFontSizeBottomSheet sheet =
                com.callx.app.chat.ui.MessageFontSizeBottomSheet.newInstance();
        sheet.setOnSizeSelectedListener(which -> {
            if (pagingAdapter != null) pagingAdapter.notifyDataSetChanged();
        });
        sheet.show(getSupportFragmentManager(),
                com.callx.app.chat.ui.MessageFontSizeBottomSheet.TAG);
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

    // ── Chat Bubble Theme Picker ──────────────────────────────────────────
    private void showThemePicker() {
        com.callx.app.utils.ChatThemeManager mgr =
                com.callx.app.utils.ChatThemeManager.get(this);
        int current = mgr.getCurrentTheme();
        new android.app.AlertDialog.Builder(this)
            .setTitle("🎨 Choose Bubble Theme")
            .setSingleChoiceItems(
                com.callx.app.utils.ChatThemeManager.THEME_NAMES,
                current,
                (dialog, which) -> {
                    mgr.setTheme(which);
                    pagingAdapter.notifyDataSetChanged();
                    applyScreenTheme();
                    dialog.dismiss();
                })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showBubbleShapePicker() {
        com.callx.app.chat.ui.BubbleShapeBottomSheet sheet =
                com.callx.app.chat.ui.BubbleShapeBottomSheet.newInstance();
        sheet.setOnShapeSelectedListener(which -> {
            if (pagingAdapter != null) pagingAdapter.notifyDataSetChanged();
        });
        sheet.show(getSupportFragmentManager(),
                com.callx.app.chat.ui.BubbleShapeBottomSheet.TAG);
    }

    // ── Typing Style Picker ───────────────────────────────────────────────
    private void showTypingStylePicker() {
        com.callx.app.utils.TypingStyleManager mgr =
                com.callx.app.utils.TypingStyleManager.get(this);
        int current = mgr.getCurrentStyle();
        new android.app.AlertDialog.Builder(this)
            .setTitle("✍️ Choose Typing Style")
            .setSingleChoiceItems(
                com.callx.app.utils.TypingStyleManager.STYLE_NAMES,
                current,
                (dialog, which) -> {
                    if (which == com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG) {
                        dialog.dismiss();
                        showSamsungStyleSubmenu(mgr);
                        return;
                    }
                    mgr.setStyle(which);
                    dialog.dismiss();
                    binding.etMessage.post(() ->
                        mgr.applyToInput(binding.etMessage)
                    );
                })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showSamsungStyleSubmenu(com.callx.app.utils.TypingStyleManager mgr) {
        String scriptPreview = com.callx.app.utils.UnicodeStyler.toScript("Samsung Style");
        String[] options = {
            "🅢 Samsung One (Font)",
            scriptPreview + " (Script ✨)"
        };
        new android.app.AlertDialog.Builder(this)
            .setTitle("🅢 Samsung Style — Choose")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    mgr.setStyle(com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG);
                } else {
                    mgr.setStyle(com.callx.app.utils.TypingStyleManager.STYLE_SAMSUNG_SCRIPT);
                }
                binding.etMessage.post(() ->
                    mgr.applyToInput(binding.etMessage)
                );
            })
            .setNegativeButton("Back", (d, w) -> showTypingStylePicker())
            .show();
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
