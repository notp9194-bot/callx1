package com.callx.app.conversation.workers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.callx.app.db.AppDatabase;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ULTRA-OPT (WorkManager background sync): ChatActivity.attachPendingStatusListener()
 * only tracks tick updates (sent -> delivered -> read) while the chat screen is
 * open — onDestroy() tears every one of those listeners down (see
 * pendingStatusListeners field doc there). If the recipient's "read" write lands
 * a few seconds AFTER the user backs out, that update is missed entirely, and
 * reopening the chat has to re-discover the same pending ids and re-sync them
 * from scratch via syncPendingStatusListeners()/batchFetchAndAttach() —
 * "fresh delta" work that a short background window can avoid altogether.
 *
 * This Worker picks up exactly where the in-Activity listeners left off: same
 * Room query (MessageDao#getPendingOutgoingMessageIds), same per-message
 * targeted listener idea — just running for a bounded window after the chat
 * closes instead of only while it's on screen. Whatever resolves to 'read'
 * during that window is already in Room by the time the user reopens, so
 * that reopen's own sync pass finds fewer (often zero) pending ids left to
 * chase — no full-window re-fetch, no restarting the same work twice.
 *
 * Enqueued from ChatActivity#onPause() as unique work keyed by chatId, so
 * reopening and leaving again just replaces the still-running job instead of
 * stacking duplicate listeners on the same messages.
 */
public class ChatStatusSyncWorker extends Worker {

    private static final String KEY_CHAT_ID = "chatId";
    private static final String KEY_MY_UID  = "myUid";
    // "kuch der" — bounded background tracking window. Long enough to catch
    // a read receipt that lands just after the user leaves, short enough
    // that WorkManager's background execution budget (and the recipient's
    // battery) isn't held hostage by a chat nobody's looking at anymore.
    private static final long LISTEN_WINDOW_MS = 45_000;

    public ChatStatusSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Enqueues (or replaces) the background sync job for one chat. */
    public static void enqueue(Context context, String chatId, String myUid) {
        if (context == null || chatId == null || myUid == null) return;
        Data input = new Data.Builder()
                .putString(KEY_CHAT_ID, chatId)
                .putString(KEY_MY_UID, myUid)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ChatStatusSyncWorker.class)
                .setInputData(input)
                .build();
        // Unique per chat: reopening + leaving again should replace the
        // still-running job for THIS chat, not pile another one on top of it
        // (which would mean two workers racing to attach the same listeners).
        WorkManager.getInstance(context)
                .enqueueUniqueWork("chat_status_sync_" + chatId, ExistingWorkPolicy.REPLACE, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        String chatId = getInputData().getString(KEY_CHAT_ID);
        String myUid  = getInputData().getString(KEY_MY_UID);
        if (chatId == null || myUid == null) return Result.failure();

        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        List<String> ids = db.messageDao().getPendingOutgoingMessageIds(chatId, myUid);
        if (ids.isEmpty()) return Result.success(); // nothing in flight — nothing to track

        DatabaseReference messagesRef = FirebaseUtils.getMessagesRef(chatId);
        CountDownLatch allResolved = new CountDownLatch(1);
        AtomicInteger remaining = new AtomicInteger(ids.size());
        Map<String, ValueEventListener> listeners = new ConcurrentHashMap<>();

        for (String id : ids) {
            DatabaseReference ref = messagesRef.child(id);
            ValueEventListener listener = new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snapshot) {
                    if (!snapshot.exists()) {
                        finishOne(id, listeners, ref, remaining, allResolved);
                        return;
                    }
                    String status = snapshot.child("status").getValue(String.class);
                    Long deliveredAt = snapshot.child("deliveredAt").getValue(Long.class);
                    Long readAt      = snapshot.child("readAt").getValue(Long.class);
                    if (status != null) {
                        // Ticks-only write — never touches text/media, so no
                        // E2EE decrypt needed here (see MessageDao#updateStatusTicks doc).
                        db.messageDao().updateStatusTicks(id, status, deliveredAt, readAt);
                    }
                    if ("read".equals(status)) {
                        finishOne(id, listeners, ref, remaining, allResolved);
                    }
                }
                @Override public void onCancelled(DatabaseError error) {
                    finishOne(id, listeners, ref, remaining, allResolved);
                }
            };
            listeners.put(id, listener);
            ref.addValueEventListener(listener);
        }

        try {
            allResolved.await(LISTEN_WINDOW_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // Window elapsed (or everything resolved early) — detach whatever's
        // still attached. This job doesn't keep listening past its own
        // doWork(); the next foreground open re-attaches live listeners for
        // anything genuinely still pending.
        for (Map.Entry<String, ValueEventListener> e : listeners.entrySet()) {
            messagesRef.child(e.getKey()).removeEventListener(e.getValue());
        }

        return Result.success();
    }

    private static void finishOne(String id, Map<String, ValueEventListener> listeners,
                                   DatabaseReference ref, AtomicInteger remaining,
                                   CountDownLatch allResolved) {
        ValueEventListener l = listeners.remove(id);
        if (l != null) ref.removeEventListener(l);
        if (remaining.decrementAndGet() <= 0) allResolved.countDown();
    }
}
