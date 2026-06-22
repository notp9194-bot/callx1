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
 * ChatActivityDelegate — Write-coalescing buffer for WhatsApp-level smoothness
 *
 * ROOT PROBLEM (old code):
 *   Opening a chat replays ~30 Firebase events back-to-back.
 *   Each event → insertMessage() → PagingSource invalidated → DiffUtil → layout pass.
 *   Result: 30+ layout passes, visible jump/flicker, 3-4 second settling delay.
 *
 * FIX:
 *   All Firebase add/change/remove/markRead events are buffered here.
 *   After 80ms of silence (debounce), ONE call to MessageDao.applyBufferedChanges()
 *   flushes everything in a single @Transaction → 1 PagingSource invalidation.
 *
 * RESULT:
 *   30 messages on open → 1 DB transaction → 1 DiffUtil pass → zero jump/flicker.
 */
public class ChatActivityDelegate {

    private static final long DEBOUNCE_MS    = 80L;
    private static final int  MAX_BUFFER_SIZE = 50;

    private final MessageDao      messageDao;
    private final ExecutorService ioExecutor;
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private final Object          lock        = new Object();

    /** Keyed by ID so later updates overwrite earlier ones in the same buffer window. */
    private final Map<String, MessageEntity> upsertBuffer = new LinkedHashMap<>();
    private final List<String>               removeBuffer = new ArrayList<>();
    private final List<String>               readBuffer   = new ArrayList<>();
    private boolean flushScheduled = false;

    private final Runnable flushRunnable = this::flush;

    public ChatActivityDelegate(MessageDao messageDao, ExecutorService ioExecutor) {
        this.messageDao = messageDao;
        this.ioExecutor = ioExecutor;
    }

    // ── Public API — called from Firebase ChildEventListeners ────────────────

    public void queueUpsert(MessageEntity entity) {
        synchronized (lock) { upsertBuffer.put(entity.id, entity); }
        scheduleFlush();
    }

    public void queueRemove(String msgId) {
        synchronized (lock) {
            removeBuffer.add(msgId);
            upsertBuffer.remove(msgId);
        }
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

    /** Call from onDestroy() — flushes immediately so nothing is lost mid-debounce. */
    public void flushNow() {
        mainHandler.removeCallbacks(flushRunnable);
        flush();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private void scheduleFlush() {
        int total;
        synchronized (lock) {
            total = upsertBuffer.size() + removeBuffer.size() + readBuffer.size();
        }
        if (total >= MAX_BUFFER_SIZE) {
            mainHandler.removeCallbacks(flushRunnable);
            flush();
            return;
        }
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
                flushScheduled = false;
                return;
            }
            upserts = new ArrayList<>(upsertBuffer.values());
            removes = new ArrayList<>(removeBuffer);
            reads   = new ArrayList<>(readBuffer);
            upsertBuffer.clear();
            removeBuffer.clear();
            readBuffer.clear();
            flushScheduled = false;
        }
        ioExecutor.execute(() -> messageDao.applyBufferedChanges(upserts, removes, reads));
    }
}
