package com.callx.app.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.paging.Pager;
import androidx.paging.PagingConfig;
import androidx.paging.PagingData;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.MessageDao;
import com.callx.app.db.dao.ChatDao;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.db.entity.ChatEntity;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MessageRepository — Single source of truth for chat messages.
 *
 * Architecture:
 *   ChatActivity → ChatViewModel → MessageRepository → (Room + Firebase)
 *
 * Offline-first: Room is always read-source.
 * Firebase syncs write to Room, LiveData propagates to ViewModel → UI.
 *
 * Key patterns (WhatsApp-level):
 *   1. Paging3 keyset-based loading (no OFFSET scan)
 *   2. Delta sync: only fetch messages newer than last known timestamp
 *   3. Optimistic local write: message appears instantly, Firebase confirms later
 *   4. Bulk DB writes: batched to minimize PagingSource invalidations
 */
public class MessageRepository {

    private static volatile MessageRepository sInstance;

    private final MessageDao      msgDao;
    private final ChatDao         chatDao;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    private MessageRepository(Context ctx) {
        AppDatabase db = AppDatabase.getInstance(ctx);
        this.msgDao  = db.messageDao();
        this.chatDao = db.chatDao();
    }

    public static MessageRepository getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (MessageRepository.class) {
                if (sInstance == null)
                    sInstance = new MessageRepository(ctx.getApplicationContext());
            }
        }
        return sInstance;
    }

    // ── READ — Live ────────────────────────────────────────────────────────

    /** LiveData stream of messages — auto-updates when DB changes (ASC order). */
    public LiveData<List<MessageEntity>> getMessages(String chatId) {
        return msgDao.getMessages(chatId);
    }

    /**
     * Paging3 source — smooth infinite scroll via Room keyset PagingSource.
     * Pages load in constant time regardless of message count.
     */
    public Pager<Integer, MessageEntity> getMessagesPager(String chatId) {
        return new Pager<>(
            new PagingConfig(
                /* pageSize         */ 40,
                /* prefetchDistance */ 10,
                /* enablePlaceholders */ false
            ),
            () -> msgDao.getMessagesPagingSource(chatId)
        );
    }

    /** Last message for a chat — for chat list preview. */
    @WorkerThread
    public MessageEntity getLastMessage(String chatId) {
        return msgDao.getLastMessage(chatId);
    }

    /** Starred messages LiveData. */
    public LiveData<List<MessageEntity>> getStarredMessages() {
        return msgDao.getStarredMessages();
    }

    /** Search messages in a chat. */
    public List<MessageEntity> searchMessages(String chatId, String query, int limit) {
        String pattern = "%" + query.replace("%", "\\%").replace("_", "\\_") + "%";
        return msgDao.searchMessagesByText(chatId, pattern, limit);
    }

    // ── READ — Chat list ──────────────────────────────────────────────────

    /** LiveData of all chats for the chat list screen. */
    public LiveData<List<ChatEntity>> getChats() {
        return chatDao.getAllChats();
    }

    public LiveData<List<ChatEntity>> getArchivedChats() {
        return chatDao.getArchivedChats();
    }

    // ── WRITE — Send message (optimistic) ────────────────────────────────

    public interface SendCallback { void onQueued(String localId); void onSent(boolean success); }

    /**
     * Optimistically insert message in Room (shows in UI instantly),
     * then write to Firebase. On success, update status to "sent".
     * On failure, mark status "failed" for retry.
     */
    public void sendMessage(MessageEntity msg, SendCallback cb) {
        // 1. Optimistic local insert
        executor.execute(() -> {
            msgDao.insertMessage(msg);
            if (cb != null) mainHandler.post(() -> cb.onQueued(msg.id));
        });

        // 2. Firebase write
        FirebaseUtils.getMessagesRef(msg.chatId).child(msg.id)
            .setValue(msg, (e, ref) -> {
                boolean ok = (e == null);
                executor.execute(() -> msgDao.updateStatus(msg.id, ok ? "sent" : "failed"));
                if (cb != null) mainHandler.post(() -> cb.onSent(ok));
            });
    }

    // ── WRITE — Status updates ────────────────────────────────────────────

    public void updateStatus(String messageId, String status) {
        executor.execute(() -> msgDao.updateStatus(messageId, status));
    }

    public void markReadBulk(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        executor.execute(() -> msgDao.markReadBulk(ids));
    }

    public void softDelete(String messageId) {
        executor.execute(() -> msgDao.softDelete(messageId));
    }

    // ── WRITE — Sync from Firebase ────────────────────────────────────────

    /**
     * Delta sync: fetch messages from Firebase newer than last local timestamp.
     * Safe to call from background (WorkManager / onStart).
     */
    public void deltaSync(String chatId) {
        executor.execute(() -> {
            Long lastTs = msgDao.getLastTimestamp(chatId);
            long sinceTs = lastTs != null ? lastTs : 0;
            FirebaseUtils.getMessagesRef(chatId)
                .orderByChild("timestamp")
                .startAfter(sinceTs)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (!snap.exists()) return;
                        executor.execute(() -> {
                            for (DataSnapshot ds : snap.getChildren()) {
                                MessageEntity m = ds.getValue(MessageEntity.class);
                                if (m != null) { m.id = ds.getKey(); msgDao.insertMessage(m); }
                            }
                        });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
        });
    }

    // ── WRITE — insert batch (from Firebase real-time listener) ──────────

    public void insertMessages(List<MessageEntity> messages) {
        if (messages == null || messages.isEmpty()) return;
        executor.execute(() -> msgDao.insertMessages(messages));
    }

    public void insertMessage(MessageEntity message) {
        executor.execute(() -> msgDao.insertMessage(message));
    }
}
