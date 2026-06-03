package com.callx.app.viewmodel;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingDataTransforms;
import androidx.paging.PagingLiveData;

import com.callx.app.cache.CacheManager;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ChatViewModel — MVVM ViewModel for 1:1 ChatActivity.
 *
 * Responsibilities (moved OUT of ChatActivity):
 *  - All Firebase listeners (messages, typing, online, block, pin)
 *  - Room DB operations (read, insert, prune, draft)
 *  - Paging 3 LiveData creation
 *  - Network state monitoring
 *  - Message send / edit / delete / react / star / pin logic
 *
 * Activity only:
 *  - observe LiveData → update UI
 *  - forward user actions → call ViewModel methods
 *
 * Rotation-safe: all state survives configuration changes.
 */
public class ChatViewModel extends AndroidViewModel {

    private static final String TAG       = "ChatViewModel";
    private static final int    PAGE_SIZE = 20;
    private static final int    PREFETCH  = 10;
    private static final int    INIT_LOAD = 40;

    // ── Core identifiers ───────────────────────────────────────────────────
    private final String chatId;
    private final String partnerUid;
    public  final String currentUid;
    public  final String currentName;

    // ── Infrastructure ─────────────────────────────────────────────────────
    private final AppDatabase    db;
    private final ChatRepository repo;
    private final Executor       ioExecutor = Executors.newFixedThreadPool(3);
    private final Handler        mainHandler = new Handler(Looper.getMainLooper());

    // ── Firebase refs ──────────────────────────────────────────────────────
    private final DatabaseReference messagesRef;
    private ChildEventListener  messageListener;
    private ValueEventListener  typingListener;
    private ValueEventListener  onlineListener;
    private ValueEventListener  blockListener;
    private ValueEventListener  permaBlockListener;
    private ValueEventListener  muteListener;
    private ValueEventListener  pinnedListener;

    // ── Paged messages LiveData (Paging 3) ────────────────────────────────
    private LiveData<PagingData<Message>> pagedMessages;

    // ── UI state LiveData ─────────────────────────────────────────────────
    /** true = partner is typing */
    private final MutableLiveData<Boolean>        isTyping       = new MutableLiveData<>(false);
    /** "online" | "last seen …" */
    private final MutableLiveData<String>         partnerStatus  = new MutableLiveData<>("");
    /** true = this chat is muted */
    private final MutableLiveData<Boolean>        isMuted        = new MutableLiveData<>(false);
    /** true = we blocked partner OR partner blocked us */
    private final MutableLiveData<Boolean>        isBlocked      = new MutableLiveData<>(false);
    /** true = partner has us permanently blocked */
    private final MutableLiveData<Boolean>        permaBlocked   = new MutableLiveData<>(false);
    /** current pinned message text (null = none) */
    private final MutableLiveData<String>         pinnedText     = new MutableLiveData<>(null);
    private final MutableLiveData<String>         pinnedId       = new MutableLiveData<>(null);
    /** true = device has internet */
    private final MutableLiveData<Boolean>        isOnline       = new MutableLiveData<>(true);
    /** one-shot error events */
    private final MutableLiveData<String>         errorEvent     = new MutableLiveData<>();
    /** one-shot snackbar messages */
    private final MutableLiveData<String>         snackEvent     = new MutableLiveData<>();
    /** triggered after message sent (scroll to bottom) */
    private final MutableLiveData<Boolean>        scrollToBottom = new MutableLiveData<>();
    /** multi-select: count of selected messages (0 = not in multi-select) */
    private final MutableLiveData<Integer>        selectedCount  = new MutableLiveData<>(0);

    // ── Typing debounce ───────────────────────────────────────────────────
    private final Runnable stopTypingRunnable = () -> setOurTypingStatus(false);

    // ── Network callback ──────────────────────────────────────────────────
    private ConnectivityManager connMgr;
    private ConnectivityManager.NetworkCallback netCallback;

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR (called by ChatViewModelFactory)
    // ─────────────────────────────────────────────────────────────────────

    public ChatViewModel(@NonNull Application app,
                         String chatId,
                         String partnerUid,
                         String partnerName) {
        super(app);
        this.chatId     = chatId;
        this.partnerUid = partnerUid;

        FirebaseUser fu = FirebaseAuth.getInstance().getCurrentUser();
        this.currentUid  = fu != null ? fu.getUid()          : "";
        this.currentName = fu != null ? (fu.getDisplayName() != null ? fu.getDisplayName() : "") : "";

        db          = AppDatabase.getInstance(app);
        repo        = ChatRepository.getInstance(app);
        messagesRef = FirebaseUtils.getMessagesRef(chatId);

        initPagedMessages();
        startRealtimeListener();
        startPresenceListeners();
        setupNetworkMonitor();

        // Background: prune old messages + preload other chats
        ioExecutor.execute(() -> db.messageDao().pruneOldMessages(chatId, 500));
        repo.preloadRecentChats(chatId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC LiveData GETTERS (Activity observes these)
    // ─────────────────────────────────────────────────────────────────────

    public LiveData<PagingData<Message>> getPagedMessages() { return pagedMessages; }
    public LiveData<Boolean>  isTyping()       { return isTyping;       }
    public LiveData<String>   partnerStatus()  { return partnerStatus;  }
    public LiveData<Boolean>  isMuted()        { return isMuted;        }
    public LiveData<Boolean>  isBlocked()      { return isBlocked;      }
    public LiveData<Boolean>  permaBlocked()   { return permaBlocked;   }
    public LiveData<String>   pinnedText()     { return pinnedText;     }
    public LiveData<String>   pinnedId()       { return pinnedId;       }
    public LiveData<Boolean>  isOnline()       { return isOnline;       }
    public LiveData<String>   errorEvent()     { return errorEvent;     }
    public LiveData<String>   snackEvent()     { return snackEvent;     }
    public LiveData<Boolean>  scrollToBottom() { return scrollToBottom; }
    public LiveData<Integer>  selectedCount()  { return selectedCount;  }

    // ─────────────────────────────────────────────────────────────────────
    // PAGING 3 SETUP
    // ─────────────────────────────────────────────────────────────────────

    private void initPagedMessages() {
        Pager<Integer, MessageEntity> pager = new Pager<>(
            new PagingConfig(PAGE_SIZE, PREFETCH, false, INIT_LOAD),
            () -> db.messageDao().getMessagesPagingSource(chatId)
        );

        LiveData<PagingData<MessageEntity>> rawLiveData =
                PagingLiveData.getLiveData(pager);

        pagedMessages = androidx.lifecycle.Transformations.map(
            rawLiveData,
            data -> PagingDataTransforms.map(
                data,
                Executors.newSingleThreadExecutor(),
                entity -> {
                    Message m = new Message();
                    m.id              = entity.id;
                    m.messageId       = entity.id;
                    m.senderId        = entity.senderId;
                    m.senderName      = entity.senderName;
                    m.senderPhoto     = entity.senderPhoto;
                    m.text            = entity.text;
                    m.type            = entity.type;
                    m.mediaUrl        = entity.mediaUrl;
                    m.thumbnailUrl    = entity.thumbnailUrl;
                    m.fileName        = entity.fileName;
                    m.fileSize        = entity.fileSize;
                    m.duration        = entity.duration;
                    m.timestamp       = entity.timestamp;
                    m.imageUrl        = entity.mediaUrl;
                    m.status          = entity.status;
                    m.replyToId       = entity.replyToId;
                    m.replyToText     = entity.replyToText;
                    m.replyToSenderName = entity.replyToSenderName;
                    m.replyToType     = entity.replyToType;
                    m.replyToMediaUrl = entity.replyToMediaUrl;
                    m.edited          = entity.edited;
                    m.editedAt        = entity.editedAt;
                    m.deleted         = entity.deleted;
                    m.forwardedFrom   = entity.forwardedFrom;
                    m.starred         = entity.starred;
                    m.pinned          = entity.pinned;
                    m.reelId          = entity.reelId;
                    m.reelThumbUrl    = entity.reelThumbUrl;
                    m.fontStyle       = entity.fontStyle;
                    return m;
                }
            )
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIREBASE — Real-time message listener (Room insert → PagingSource invalidates)
    // ─────────────────────────────────────────────────────────────────────

    private void startRealtimeListener() {
        long lastTs = CacheManager.getInstance(getApplication())
                          .getLastSyncTimestamp(chatId);

        messageListener = new ChildEventListener() {
            @Override public void onChildAdded(@NonNull DataSnapshot s, String prev) {
                insertOrUpdateEntity(s);
            }
            @Override public void onChildChanged(@NonNull DataSnapshot s, String prev) {
                insertOrUpdateEntity(s);
            }
            @Override public void onChildRemoved(@NonNull DataSnapshot s) {
                String id = s.getKey();
                if (id != null) {
                    ioExecutor.execute(() -> db.messageDao().markDeleted(id));
                }
            }
            @Override public void onChildMoved(@NonNull DataSnapshot s, String prev) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Log.e(TAG, "messagesRef cancelled: " + e.getMessage());
            }
        };

        messagesRef.orderByChild("timestamp")
                   .startAt(lastTs > 0 ? lastTs + 1 : 0)
                   .addChildEventListener(messageListener);
    }

    private void insertOrUpdateEntity(DataSnapshot s) {
        try {
            Message m = s.getValue(Message.class);
            if (m == null) return;
            if (m.id == null) m.id = s.getKey();
            if (m.messageId == null) m.messageId = m.id;

            MessageEntity e = new MessageEntity();
            e.id              = m.id;
            e.chatId          = chatId;
            e.senderId        = m.senderId;
            e.senderName      = m.senderName;
            e.senderPhoto     = m.senderPhoto;
            e.text            = m.text;
            e.type            = m.type;
            e.mediaUrl        = m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
            e.thumbnailUrl    = m.thumbnailUrl;
            e.fileName        = m.fileName;
            e.fileSize        = m.fileSize;
            e.duration        = m.duration;
            e.timestamp       = m.timestamp;
            e.status          = m.status;
            e.replyToId       = m.replyToId;
            e.replyToText     = m.replyToText;
            e.replyToSenderName = m.replyToSenderName;
            e.replyToType     = m.replyToType;
            e.replyToMediaUrl = m.replyToMediaUrl;
            e.edited          = m.edited;
            e.editedAt        = m.editedAt;
            e.deleted         = m.deleted;
            e.forwardedFrom   = m.forwardedFrom;
            e.starred         = m.starred;
            e.pinned          = m.pinned;
            e.reelId          = m.reelId;
            e.reelThumbUrl    = m.reelThumbUrl;
            e.fontStyle       = m.fontStyle;
            e.syncedAt        = System.currentTimeMillis();

            ioExecutor.execute(() -> {
                db.messageDao().insertOrReplace(e);
                CacheManager.getInstance(getApplication())
                    .updateLastSyncTimestamp(chatId,
                        m.timestamp != null ? m.timestamp : System.currentTimeMillis());
            });
        } catch (Exception ex) {
            Log.e(TAG, "insertOrUpdateEntity error", ex);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIREBASE — Presence / meta listeners
    // ─────────────────────────────────────────────────────────────────────

    private void startPresenceListeners() {
        DatabaseReference userRef = FirebaseUtils.getUserRef(partnerUid);

        // Online / last seen
        onlineListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Boolean online = s.child("online").getValue(Boolean.class);
                if (Boolean.TRUE.equals(online)) {
                    partnerStatus.postValue("online");
                } else {
                    Long lastSeen = s.child("lastSeen").getValue(Long.class);
                    if (lastSeen != null) {
                        partnerStatus.postValue("last seen " + formatLastSeen(lastSeen));
                    } else {
                        partnerStatus.postValue("");
                    }
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        userRef.addValueEventListener(onlineListener);

        // Typing
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                Boolean typing = s.getValue(Boolean.class);
                isTyping.postValue(Boolean.TRUE.equals(typing));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getTypingRef(chatId, partnerUid)
                     .addValueEventListener(typingListener);

        // Mute
        muteListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                isMuted.postValue(Boolean.TRUE.equals(s.getValue(Boolean.class)));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getMuteRef(chatId, currentUid)
                     .addValueEventListener(muteListener);

        // Block
        blockListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                isBlocked.postValue(s.exists());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getBlockRef(currentUid, partnerUid)
                     .addValueEventListener(blockListener);

        // Partner perma-blocked us
        permaBlockListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                permaBlocked.postValue(s.exists());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getBlockRef(partnerUid, currentUid)
                     .addValueEventListener(permaBlockListener);

        // Pinned message
        pinnedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot s) {
                String id   = s.child("id").getValue(String.class);
                String text = s.child("text").getValue(String.class);
                pinnedId.postValue(id);
                pinnedText.postValue(text);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getPinnedRef(chatId)
                     .addValueEventListener(pinnedListener);
    }

    // ─────────────────────────────────────────────────────────────────────
    // NETWORK MONITORING
    // ─────────────────────────────────────────────────────────────────────

    private void setupNetworkMonitor() {
        connMgr = (ConnectivityManager)
                getApplication().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        if (connMgr == null) return;

        isOnline.postValue(checkOnlineNow());

        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) {
                isOnline.postValue(true);
                retryPendingMessages();
            }
            @Override public void onLost(Network n) {
                isOnline.postValue(false);
            }
        };
        try {
            connMgr.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                netCallback);
        } catch (Exception ignored) {}
    }

    private boolean checkOnlineNow() {
        if (connMgr == null) return true;
        Network active = connMgr.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = connMgr.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ─────────────────────────────────────────────────────────────────────
    // MESSAGE ACTIONS — called from Activity on user interaction
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Push a new outgoing message to Firebase + Room.
     * Activity calls this after building the Message object.
     */
    public void sendMessage(Message m, String previewText) {
        if (m.id == null) m.id = messagesRef.push().getKey();
        if (m.id == null) return;
        m.messageId = m.id;
        m.senderId  = currentUid;
        m.timestamp = System.currentTimeMillis();
        m.status    = "sent";

        // Optimistic insert → Room → pager shows immediately
        MessageEntity optimistic = toEntity(m);
        ioExecutor.execute(() -> db.messageDao().insertOrReplace(optimistic));

        // Firebase push
        final String msgId = m.id;
        messagesRef.child(msgId).setValue(m)
            .addOnSuccessListener(task -> updateChatMeta(m, previewText))
            .addOnFailureListener(e -> {
                Log.e(TAG, "sendMessage failed: " + e.getMessage());
                // Mark as failed in Room so UI can show retry
                ioExecutor.execute(() -> db.messageDao().updateStatus(msgId, "failed"));
                errorEvent.postValue("Message send failed. Tap to retry.");
            });

        scrollToBottom.postValue(true);
    }

    /** Edit an existing message text. */
    public void editMessage(String msgId, String newText) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("text",     newText);
        updates.put("edited",   true);
        updates.put("editedAt", System.currentTimeMillis());
        messagesRef.child(msgId).updateChildren(updates)
            .addOnFailureListener(e -> errorEvent.postValue("Edit failed. Try again."));
    }

    /** Delete a message for everyone. */
    public void deleteMessage(String msgId) {
        Map<String, Object> updates = new HashMap<>();
        updates.put("deleted", true);
        updates.put("text",    "");
        updates.put("mediaUrl", null);
        messagesRef.child(msgId).updateChildren(updates)
            .addOnFailureListener(e -> errorEvent.postValue("Delete failed. Try again."));
    }

    /** Toggle emoji reaction on a message. */
    public void reactMessage(String msgId, String emoji) {
        FirebaseUtils.getMessagesRef(chatId)
            .child(msgId).child("reactions").child(currentUid)
            .setValue(emoji)
            .addOnFailureListener(e -> errorEvent.postValue("Reaction failed."));
    }

    /** Toggle star on a message. */
    public void toggleStar(Message m) {
        boolean newStar = !Boolean.TRUE.equals(m.starred);
        messagesRef.child(m.id).child("starred").setValue(newStar)
            .addOnFailureListener(e -> errorEvent.postValue("Star action failed."));
    }

    /** Pin a message (or null to unpin). */
    public void pinMessage(Message m) {
        Map<String, Object> pinData = new HashMap<>();
        pinData.put("id",   m.id);
        pinData.put("text", m.text != null ? m.text : "[media]");
        FirebaseUtils.getPinnedRef(chatId).setValue(pinData)
            .addOnSuccessListener(v -> snackEvent.postValue("Message pinned"))
            .addOnFailureListener(e -> errorEvent.postValue("Pin failed."));
    }

    /** Unpin the current pinned message. */
    public void unpinMessage() {
        FirebaseUtils.getPinnedRef(chatId).removeValue()
            .addOnSuccessListener(v -> snackEvent.postValue("Message unpinned"));
    }

    /** Mark all messages in this chat as read. */
    public void markRead() {
        ioExecutor.execute(() -> db.messageDao().markAllRead(chatId));
        FirebaseUtils.getUnreadRef(chatId, currentUid).setValue(0);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TYPING STATUS
    // ─────────────────────────────────────────────────────────────────────

    /** Call when user is actively typing. Debounces stop after 2s. */
    public void onUserTyping() {
        setOurTypingStatus(true);
        mainHandler.removeCallbacks(stopTypingRunnable);
        mainHandler.postDelayed(stopTypingRunnable, 2000);
    }

    /** Call on Activity.onPause() or when input cleared. */
    public void clearTypingStatus() {
        mainHandler.removeCallbacks(stopTypingRunnable);
        setOurTypingStatus(false);
    }

    private void setOurTypingStatus(boolean typing) {
        FirebaseUtils.getTypingRef(chatId, currentUid).setValue(typing ? true : null);
    }

    // ─────────────────────────────────────────────────────────────────────
    // DRAFT
    // ─────────────────────────────────────────────────────────────────────

    public void saveDraft(String text) {
        ioExecutor.execute(() -> db.chatDao().saveDraft(chatId, text != null ? text : ""));
    }

    /** Returns draft text via callback on main thread. */
    public void restoreDraft(java.util.function.Consumer<String> callback) {
        ioExecutor.execute(() -> {
            String draft = db.chatDao().getDraft(chatId);
            mainHandler.post(() -> callback.accept(draft != null ? draft : ""));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // UNREAD COUNT (for divider position)
    // ─────────────────────────────────────────────────────────────────────

    public void getUnreadInfo(java.util.function.BiConsumer<Integer, Integer> callback) {
        ioExecutor.execute(() -> {
            long unread = db.chatDao().getUnreadCount(chatId);
            int total   = db.messageDao().getMessageCount(chatId);
            int divPos  = (int) Math.max(0, total - unread);
            mainHandler.post(() -> callback.accept(divPos, (int) unread));
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // MULTI-SELECT COUNTER (adapter calls this)
    // ─────────────────────────────────────────────────────────────────────

    public void onSelectionChanged(int count) {
        selectedCount.postValue(count);
    }

    // ─────────────────────────────────────────────────────────────────────
    // RETRY PENDING
    // ─────────────────────────────────────────────────────────────────────

    private void retryPendingMessages() {
        ioExecutor.execute(() -> {
            java.util.List<MessageEntity> pending =
                    db.messageDao().getFailedMessages(chatId);
            for (MessageEntity e : pending) {
                Message m = new Message();
                m.id       = e.id;
                m.senderId = e.senderId;
                m.text     = e.text;
                m.type     = e.type;
                m.mediaUrl = e.mediaUrl;
                m.timestamp = e.timestamp;
                m.status   = "sent";
                messagesRef.child(m.id).setValue(m)
                    .addOnSuccessListener(v ->
                        ioExecutor.execute(() -> db.messageDao().updateStatus(m.id, "sent")));
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private void updateChatMeta(Message m, String previewText) {
        Map<String, Object> chatUpdate = new HashMap<>();
        chatUpdate.put("lastMessage",   previewText);
        chatUpdate.put("lastTimestamp", m.timestamp);
        chatUpdate.put("lastSenderId",  m.senderId);
        FirebaseUtils.getChatRef(chatId).updateChildren(chatUpdate);
        FirebaseUtils.getUnreadRef(chatId, partnerUid)
                     .setValue(com.google.firebase.database.ServerValue.increment(1));
    }

    private MessageEntity toEntity(Message m) {
        MessageEntity e   = new MessageEntity();
        e.id              = m.id;
        e.chatId          = chatId;
        e.senderId        = m.senderId;
        e.senderName      = m.senderName;
        e.text            = m.text;
        e.type            = m.type;
        e.mediaUrl        = m.mediaUrl;
        e.thumbnailUrl    = m.thumbnailUrl;
        e.fileName        = m.fileName;
        e.fileSize        = m.fileSize;
        e.duration        = m.duration;
        e.timestamp       = m.timestamp;
        e.status          = m.status;
        e.replyToId       = m.replyToId;
        e.replyToText     = m.replyToText;
        e.replyToSenderName = m.replyToSenderName;
        e.replyToType     = m.replyToType;
        e.replyToMediaUrl = m.replyToMediaUrl;
        e.edited          = m.edited;
        e.deleted         = m.deleted;
        e.forwardedFrom   = m.forwardedFrom;
        e.starred         = m.starred;
        e.pinned          = m.pinned;
        e.fontStyle       = m.fontStyle;
        e.syncedAt        = System.currentTimeMillis();
        return e;
    }

    private String formatLastSeen(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000)          return "just now";
        if (diff < 3_600_000)       return (diff / 60_000) + "m ago";
        if (diff < 86_400_000)      return (diff / 3_600_000) + "h ago";
        return new java.text.SimpleDateFormat("dd MMM", java.util.Locale.getDefault()).format(new java.util.Date(ts));
    }

    // ─────────────────────────────────────────────────────────────────────
    // CLEANUP
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCleared() {
        super.onCleared();
        mainHandler.removeCallbacks(stopTypingRunnable);
        clearTypingStatus();

        if (messagesRef != null && messageListener != null)
            messagesRef.removeEventListener(messageListener);

        DatabaseReference userRef = FirebaseUtils.getUserRef(partnerUid);
        if (onlineListener  != null) userRef.removeEventListener(onlineListener);
        if (typingListener  != null) FirebaseUtils.getTypingRef(chatId, partnerUid).removeEventListener(typingListener);
        if (muteListener    != null) FirebaseUtils.getMuteRef(chatId, currentUid).removeEventListener(muteListener);
        if (blockListener   != null) FirebaseUtils.getBlockRef(currentUid, partnerUid).removeEventListener(blockListener);
        if (permaBlockListener != null) FirebaseUtils.getBlockRef(partnerUid, currentUid).removeEventListener(permaBlockListener);
        if (pinnedListener  != null) FirebaseUtils.getPinnedRef(chatId).removeEventListener(pinnedListener);

        if (connMgr != null && netCallback != null) {
            try { connMgr.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        }
    }
}
