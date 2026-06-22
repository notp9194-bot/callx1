package com.callx.app.conversation.controllers;

import android.os.Handler;
import android.os.Looper;
import com.callx.app.db.dao.MessageDao;
import com.callx.app.db.entity.MessageEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * ChatActivityDelegate — 80ms write-coalescing buffer.
 *
 * 30 Firebase onChildAdded callbacks → buffer → 1 Room @Transaction
 * → 1 PagingSource invalidation → zero jump / zero flicker.
 */
public class ChatActivityDelegate {

    private static final long DEBOUNCE_MS     = 80L;
    private static final int  MAX_BUFFER_SIZE = 50;

    private final MessageDao      messageDao;
    private final ExecutorService ioExecutor;
    private final Handler         mainHandler  = new Handler(Looper.getMainLooper());
    private final Object          lock         = new Object();

    private final Map<String, MessageEntity> upsertBuffer = new LinkedHashMap<>();
    private final List<String>               removeBuffer = new ArrayList<>();
    private final List<String>               readBuffer   = new ArrayList<>();
    private boolean flushScheduled = false;

    private final Runnable flushRunnable = this::flush;

    public ChatActivityDelegate(MessageDao messageDao, ExecutorService ioExecutor) {
        this.messageDao = messageDao;
        this.ioExecutor = ioExecutor;
    }

    public void queueUpsert(MessageEntity entity) {
        synchronized (lock) { upsertBuffer.put(entity.id, entity); }
        scheduleFlush();
    }

    public void queueRemove(String msgId) {
        synchronized (lock) { removeBuffer.add(msgId); upsertBuffer.remove(msgId); }
        scheduleFlush();
    }

    public void queueMarkRead(String msgId) {
        synchronized (lock) { readBuffer.add(msgId); }
        scheduleFlush();
    }

    public void queueMarkReadBulk(List<String> msgIds) {
        synchronized (lock) { readBuffer.addAll(msgIds); }
        scheduleFlush();
    }

    /** Call from onDestroy() — flushes immediately so nothing is lost. */
    public void flushNow() {
        mainHandler.removeCallbacks(flushRunnable);
        flush();
    }

    private void scheduleFlush() {
        int total;
        synchronized (lock) { total = upsertBuffer.size() + removeBuffer.size() + readBuffer.size(); }
        if (total >= MAX_BUFFER_SIZE) { mainHandler.removeCallbacks(flushRunnable); flush(); return; }
        synchronized (lock) {
            if (flushScheduled) mainHandler.removeCallbacks(flushRunnable);
            flushScheduled = true;
        }
        mainHandler.postDelayed(flushRunnable, DEBOUNCE_MS);
    }

    private void flush() {
        final List<MessageEntity> upserts;
        final List<String>        removes;
        final List<String>        reads;
        synchronized (lock) {
            if (upsertBuffer.isEmpty() && removeBuffer.isEmpty() && readBuffer.isEmpty()) {
                flushScheduled = false; return;
            }
            upserts = new ArrayList<>(upsertBuffer.values());
            removes = new ArrayList<>(removeBuffer);
            reads   = new ArrayList<>(readBuffer);
            upsertBuffer.clear(); removeBuffer.clear(); readBuffer.clear();
            flushScheduled = false;
        }
        ioExecutor.execute(() -> messageDao.applyBufferedChanges(upserts, removes, reads));
    }
}
