package com.callx.app.viewmodel;

import android.app.Application;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import androidx.paging.PagingLiveData;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.repository.ChatRepository;
import com.callx.app.utils.E2EEncryptionManager;
import com.callx.app.utils.FirebaseUtils;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.callx.app.conversation.ChatActivity;

/**
 * ChatViewModel — MVVM ViewModel for 1:1 ChatActivity.
 *
 * WHY ViewModel?
 *   - Survives rotation: Activity destroy/recreate ke baad bhi LiveData alive rehti hai
 *   - No memory leaks: Firebase listeners ViewModel.onCleared() mein detach hote hain
 *   - Separation of concerns: Business logic Activity mein nahi, ViewModel mein hai
 *   - Testability: ViewModel ko unit test kiya ja sakta hai Activity ke bina
 *
 * Architecture:
 *   ChatActivity  ──observe──►  ChatViewModel  ──►  ChatRepository
 *                                    │                    │
 *                                    │              Firebase + Room DB
 *                                    │
 *                              LiveData<PagingData<Message>>
 *                              LiveData<String>  (typingStatus)
 *                              LiveData<Boolean> (partnerOnline)
 *                              LiveData<Boolean> (isBlocked)
 *                              LiveData<String>  (pinnedMessage)
 *                              MutableLiveData<String> (errorEvent)
 */
public class ChatViewModel extends AndroidViewModel {

    private static final String TAG = "ChatViewModel";

    // ── Config ─────────────────────────────────────────────────────────────
    private static final int PAGE_SIZE     = 20;
    private static final int PREFETCH_DIST = 10;
    private static final int INITIAL_LOAD  = 40;

    // ── Dependencies ───────────────────────────────────────────────────────
    private final ChatRepository  repo;
    private final AppDatabase     db;
    private final ExecutorService ioExecutor;

    // ── Chat identifiers (set once via init()) ─────────────────────────────
    private String chatId;
    private String currentUid;
    private String partnerUid;

    // ── LiveData exposed to Activity ───────────────────────────────────────
    private final MutableLiveData<String>  typingStatus    = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> partnerOnline   = new MutableLiveData<>(false);
    private final MutableLiveData<String>  partnerLastSeen = new MutableLiveData<>("");
    private final MutableLiveData<Boolean> isBlocked       = new MutableLiveData<>(false);
    private final MutableLiveData<Boolean> isMuted         = new MutableLiveData<>(false);
    private final MutableLiveData<String>  pinnedMsgId     = new MutableLiveData<>(null);
    private final MutableLiveData<String>  pinnedMsgText   = new MutableLiveData<>(null);
    private final MutableLiveData<String>  errorEvent      = new MutableLiveData<>();
    private final MutableLiveData<Boolean> networkStatus   = new MutableLiveData<>(true);

    // ── Paging LiveData (created once via initPaging) ──────────────────────
    private LiveData<PagingData<MessageEntity>> pagedMessages;

    // ── Firebase listeners (tracked for cleanup) ───────────────────────────
    private DatabaseReference  typingRef;
    private ValueEventListener typingListener;
    private DatabaseReference  onlineRef;
    private ValueEventListener onlineListener;
    private DatabaseReference  blockRef;
    private ValueEventListener blockListener;
    private DatabaseReference  muteRef;
    private ValueEventListener muteListener;
    private DatabaseReference  pinnedRef;
    private ValueEventListener pinnedListener;
    private DatabaseReference  messagesRef;
    private ChildEventListener messageChildListener;

    // ── Typing debounce ────────────────────────────────────────────────────
    private final android.os.Handler typingHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable stopTypingRunnable = () -> setOurTypingStatus(false);

    public ChatViewModel(@NonNull Application application) {
        super(application);
        repo       = ChatRepository.getInstance(application);
        db         = AppDatabase.getInstance(application);
        ioExecutor = Executors.newFixedThreadPool(3);
    }

    // ─────────────────────────────────────────────────────────────────────
    // INIT — called once from ChatActivity.onCreate()
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Initialize ViewModel with chat identifiers.
     * Must be called before observing any LiveData.
     */
    public void init(String chatId, String currentUid, String partnerUid) {
        if (this.chatId != null) return; // Already initialized (rotation safe)

        this.chatId     = chatId;
        this.currentUid = currentUid;
        this.partnerUid = partnerUid;

        // Ensure E2E keys are ready for this chat
        E2EEncryptionManager.getInstance(getApplication())
                .ensureKeysExist(currentUid, partnerUid, success -> {
                    if (!success) Log.w(TAG, "E2E key setup failed for chat: " + chatId);
                });

        initPaging();
        startRealtimeListener();
        watchTyping();
        watchPartnerOnlineStatus();
        watchBlock();
        watchMute();
        watchPinnedMessage();
        markMessagesRead();

        // Background: delta sync + preload + old message pruning
        repo.syncMessagesDelta(chatId);
        repo.preloadRecentChats(chatId);
        ioExecutor.execute(() -> db.messageDao().pruneOldMessages(chatId, 500));
    }

    // ─────────────────────────────────────────────────────────────────────
    // PAGING 3 SETUP
    // ─────────────────────────────────────────────────────────────────────

    private void initPaging() {
        Pager<Integer, MessageEntity> pager = new Pager<>(
                new PagingConfig(PAGE_SIZE, PREFETCH_DIST, false, INITIAL_LOAD),
                () -> db.messageDao().getMessagesPagingSource(chatId)
        );
        pagedMessages = PagingLiveData.getLiveData(pager);
    }

    // ─────────────────────────────────────────────────────────────────────
    // REALTIME FIREBASE → ROOM SYNC
    // ─────────────────────────────────────────────────────────────────────

    private void startRealtimeListener() {
        messagesRef = FirebaseUtils.getMessagesRef(chatId);
        E2EEncryptionManager encryption =
                E2EEncryptionManager.getInstance(getApplication());

        messageChildListener = new ChildEventListener() {
            @Override
            public void onChildAdded(@NonNull DataSnapshot snap, String prev) {
                Message raw = snap.getValue(Message.class);
                if (raw == null) return;
                if (raw.messageId == null) raw.messageId = snap.getKey();
                if (raw.id == null)        raw.id        = snap.getKey();

                // Decrypt message text if encrypted
                if (raw.text != null && raw.text.startsWith("enc:")) {
                    try {
                        raw.text = encryption.decrypt(raw.text, partnerUid);
                    } catch (Exception e) {
                        Log.w(TAG, "Decrypt failed for msg: " + raw.messageId, e);
                    }
                }

                Message msg = raw;
                ioExecutor.execute(() -> {
                    MessageEntity entity = messageToEntity(msg);
                    db.messageDao().insertMessage(entity);
                });
            }

            @Override
            public void onChildChanged(@NonNull DataSnapshot snap, String prev) {
                Message raw = snap.getValue(Message.class);
                if (raw == null) return;
                if (raw.messageId == null) raw.messageId = snap.getKey();
                if (raw.id == null)        raw.id        = snap.getKey();

                if (raw.text != null && raw.text.startsWith("enc:")) {
                    try {
                        raw.text = encryption.decrypt(raw.text, partnerUid);
                    } catch (Exception e) {
                        Log.w(TAG, "Decrypt failed on update: " + raw.messageId, e);
                    }
                }

                Message msg = raw;
                ioExecutor.execute(() -> {
                    MessageEntity entity = messageToEntity(msg);
                    db.messageDao().insertMessage(entity);
                });
            }

            @Override
            public void onChildRemoved(@NonNull DataSnapshot snap) {
                String msgId = snap.getKey();
                if (msgId != null) {
                    ioExecutor.execute(() -> db.messageDao().softDelete(msgId));
                }
            }

            @Override public void onChildMoved(@NonNull DataSnapshot s, String p) {}
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Log.e(TAG, "Messages listener cancelled: " + e.getMessage());
            }
        };

        messagesRef.orderByChild("timestamp").limitToLast(50)
                .addChildEventListener(messageChildListener);
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIREBASE WATCHERS
    // ─────────────────────────────────────────────────────────────────────

    private void watchTyping() {
        typingRef = FirebaseUtils.db().getReference("typing").child(chatId);
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                boolean partnerTyping = false;
                for (DataSnapshot child : snap.getChildren()) {
                    if (!child.getKey().equals(currentUid)) {
                        Boolean t = child.getValue(Boolean.class);
                        if (Boolean.TRUE.equals(t)) { partnerTyping = true; break; }
                    }
                }
                typingStatus.postValue(partnerTyping ? "typing..." : "");
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        typingRef.addValueEventListener(typingListener);
    }

    private void watchPartnerOnlineStatus() {
        onlineRef = FirebaseUtils.getUserRef(partnerUid);
        onlineListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Boolean online = snap.child("online").getValue(Boolean.class);
                partnerOnline.postValue(Boolean.TRUE.equals(online));
                if (!Boolean.TRUE.equals(online)) {
                    Object last = snap.child("lastSeen").getValue();
                    partnerLastSeen.postValue(last != null ? formatLastSeen(last) : "");
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        onlineRef.addValueEventListener(onlineListener);
    }

    private void watchBlock() {
        blockRef = FirebaseUtils.db().getReference("blocked")
                .child(currentUid).child(partnerUid);
        blockListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isBlocked.postValue(snap.exists() && Boolean.TRUE.equals(snap.getValue(Boolean.class)));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        blockRef.addValueEventListener(blockListener);
    }

    private void watchMute() {
        muteRef = FirebaseUtils.db().getReference("muted")
                .child(currentUid).child(chatId);
        muteListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                isMuted.postValue(snap.exists());
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        muteRef.addValueEventListener(muteListener);
    }

    private void watchPinnedMessage() {
        pinnedRef = FirebaseUtils.db().getReference("chats")
                .child(chatId).child("pinnedMessage");
        pinnedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                pinnedMsgId.postValue(snap.child("id").getValue(String.class));
                pinnedMsgText.postValue(snap.child("text").getValue(String.class));
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        pinnedRef.addValueEventListener(pinnedListener);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SEND MESSAGE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Send a message with optional E2E encryption.
     * Encrypts text before pushing to Firebase.
     */
    public void sendMessage(Message msg, String preview) {
        E2EEncryptionManager encryption =
                E2EEncryptionManager.getInstance(getApplication());

        ioExecutor.execute(() -> {
            Message toSend = msg;
            // Encrypt text payload
            if ("text".equals(msg.type) && msg.text != null && !msg.text.isEmpty()) {
                try {
                    String encrypted = encryption.encrypt(msg.text, partnerUid);
                    toSend = cloneWithEncryptedText(msg, encrypted);
                } catch (Exception e) {
                    Log.w(TAG, "Encrypt failed — sending plain", e);
                }
            }

            // Save locally first (optimistic update)
            MessageEntity local = messageToEntity(msg); // plain text in Room
            db.messageDao().insertMessage(local);

            // Push to Firebase (encrypted)
            String key = FirebaseUtils.getMessagesRef(chatId).push().getKey();
            if (key == null) { errorEvent.postValue("Send failed: no Firebase key"); return; }

            if (toSend.messageId == null) toSend.messageId = key;
            if (toSend.id == null)        toSend.id        = key;

            Map<String, Object> payload = buildPayload(toSend);
            FirebaseUtils.getMessagesRef(chatId).child(key).setValue(payload)
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Firebase send failed", e);
                        errorEvent.postValue("Message send failed. Will retry when online.");
                    });

            // Update chat list preview
            updateChatPreview(preview, toSend.timestamp);
        });
    }

    private Message cloneWithEncryptedText(Message original, String encText) {
        Message clone = new Message();
        clone.id            = original.id;
        clone.messageId     = original.messageId;
        clone.senderId      = original.senderId;
        clone.senderName    = original.senderName;
        clone.senderPhoto   = original.senderPhoto;
        clone.text          = encText; // encrypted
        clone.type          = original.type;
        clone.mediaUrl      = original.mediaUrl;
        clone.thumbnailUrl  = original.thumbnailUrl;
        clone.fileName      = original.fileName;
        clone.fileSize      = original.fileSize;
        clone.duration      = original.duration;
        clone.timestamp     = original.timestamp;
        clone.status        = original.status;
        clone.replyToId     = original.replyToId;
        clone.replyToText   = original.replyToText;
        clone.replyToSenderName = original.replyToSenderName;
        clone.replyToType   = original.replyToType;
        clone.replyToMediaUrl = original.replyToMediaUrl;
        clone.forwardedFrom = original.forwardedFrom;
        clone.fontStyle     = original.fontStyle;
        return clone;
    }

    private Map<String, Object> buildPayload(Message msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("messageId",       msg.messageId);
        m.put("senderId",        msg.senderId);
        m.put("senderName",      msg.senderName);
        m.put("senderPhoto",     msg.senderPhoto);
        m.put("text",            msg.text);
        m.put("type",            msg.type);
        m.put("mediaUrl",        msg.mediaUrl);
        m.put("thumbnailUrl",    msg.thumbnailUrl);
        m.put("fileName",        msg.fileName);
        m.put("fileSize",        msg.fileSize);
        m.put("duration",        msg.duration);
        m.put("timestamp",       msg.timestamp);
        m.put("status",          msg.status != null ? msg.status : "sent");
        m.put("replyToId",       msg.replyToId);
        m.put("replyToText",     msg.replyToText);
        m.put("replyToSenderName", msg.replyToSenderName);
        m.put("replyToType",     msg.replyToType);
        m.put("replyToMediaUrl", msg.replyToMediaUrl);
        m.put("forwardedFrom",   msg.forwardedFrom);
        m.put("fontStyle",       msg.fontStyle);
        // Remove nulls
        m.values().removeIf(v -> v == null);
        return m;
    }

    private void updateChatPreview(String preview, Long ts) {
        Map<String, Object> update = new HashMap<>();
        update.put("lastMessage",   preview);
        update.put("lastTimestamp", ts);
        update.put("lastSenderId",  currentUid);
        FirebaseUtils.db().getReference("chatList").child(currentUid).child(chatId).updateChildren(update);
        FirebaseUtils.db().getReference("chatList").child(partnerUid).child(chatId).updateChildren(update);
    }

    // ─────────────────────────────────────────────────────────────────────
    // TYPING STATUS
    // ─────────────────────────────────────────────────────────────────────

    /** Call on every keystroke — debounces auto-stop after 3s. */
    public void onUserTyping() {
        setOurTypingStatus(true);
        typingHandler.removeCallbacks(stopTypingRunnable);
        typingHandler.postDelayed(stopTypingRunnable, 3000);
    }

    public void onUserStoppedTyping() {
        typingHandler.removeCallbacks(stopTypingRunnable);
        setOurTypingStatus(false);
    }

    private void setOurTypingStatus(boolean typing) {
        if (chatId == null || currentUid == null) return;
        FirebaseUtils.db().getReference("typing").child(chatId).child(currentUid)
                .setValue(typing ? true : null);
    }

    private void clearOurTypingStatus() {
        if (chatId == null || currentUid == null) return;
        FirebaseUtils.db().getReference("typing").child(chatId).child(currentUid).removeValue();
    }

    // ─────────────────────────────────────────────────────────────────────
    // READ RECEIPTS
    // ─────────────────────────────────────────────────────────────────────

    public void markMessagesRead() {
        if (chatId == null || partnerUid == null) return;
        ioExecutor.execute(() -> {
            List<MessageEntity> unread = db.messageDao().getUnreadMessages(chatId, partnerUid);
            for (MessageEntity e : unread) {
                FirebaseUtils.getMessagesRef(chatId).child(e.id)
                        .child("status").setValue("read");
                e.status = "read";
                db.messageDao().insertMessage(e);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // DRAFT
    // ─────────────────────────────────────────────────────────────────────

    public void saveDraft(String text) {
        if (chatId == null) return;
        ioExecutor.execute(() -> db.chatDao().saveDraft(chatId, text));
    }

    public LiveData<String> getDraft() {
        return db.chatDao().getDraftLive(chatId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // PUBLIC LIVEDATA ACCESSORS
    // ─────────────────────────────────────────────────────────────────────

    public LiveData<PagingData<MessageEntity>> getPagedMessages()  { return pagedMessages; }
    public LiveData<String>  getTypingStatus()               { return typingStatus; }
    public LiveData<Boolean> getPartnerOnline()              { return partnerOnline; }
    public LiveData<String>  getPartnerLastSeen()            { return partnerLastSeen; }
    public LiveData<Boolean> getIsBlocked()                  { return isBlocked; }
    public LiveData<Boolean> getIsMuted()                    { return isMuted; }
    public LiveData<String>  getPinnedMsgId()                { return pinnedMsgId; }
    public LiveData<String>  getPinnedMsgText()              { return pinnedMsgText; }
    public LiveData<String>  getErrorEvent()                 { return errorEvent; }
    public LiveData<Boolean> getNetworkStatus()              { return networkStatus; }
    public String getChatId()                                { return chatId; }
    public String getCurrentUid()                            { return currentUid; }

    public void setNetworkOnline(boolean online) {
        networkStatus.postValue(online);
        if (online && chatId != null) repo.syncMessagesDelta(chatId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // LIFECYCLE — cleanup on ViewModel death
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCleared() {
        super.onCleared();

        // Detach all Firebase listeners — NO MEMORY LEAKS
        if (typingListener != null && typingRef != null)
            typingRef.removeEventListener(typingListener);
        if (onlineListener != null && onlineRef != null)
            onlineRef.removeEventListener(onlineListener);
        if (blockListener != null && blockRef != null)
            blockRef.removeEventListener(blockListener);
        if (muteListener != null && muteRef != null)
            muteRef.removeEventListener(muteListener);
        if (pinnedListener != null && pinnedRef != null)
            pinnedRef.removeEventListener(pinnedListener);
        if (messageChildListener != null && messagesRef != null)
            messagesRef.removeEventListener(messageChildListener);

        clearOurTypingStatus();
        typingHandler.removeCallbacks(stopTypingRunnable);
        ioExecutor.shutdown();
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private MessageEntity messageToEntity(Message m) {
        MessageEntity e = new MessageEntity();
        e.id              = m.messageId != null ? m.messageId : (m.id != null ? m.id : "");
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
        e.status          = m.status != null ? m.status : "sent";
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
        e.isGroup         = m.isGroup;
        e.fontStyle       = m.fontStyle;
        e.syncedAt        = System.currentTimeMillis();
        return e;
    }

    private String formatLastSeen(Object lastSeen) {
        if (lastSeen instanceof Long) {
            long ts = (Long) lastSeen;
            long diff = System.currentTimeMillis() - ts;
            if (diff < 60_000)    return "last seen just now";
            if (diff < 3_600_000) return "last seen " + (diff / 60_000) + "m ago";
            if (diff < 86_400_000) return "last seen " + (diff / 3_600_000) + "h ago";
            return "last seen " + new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(new java.util.Date(ts));
        }
        return "last seen recently";
    }
}
