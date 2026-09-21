package com.callx.app.utils;

import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * Coalesces high-frequency chat callbacks onto one main-thread frame.
 *
 * Firebase/decrypt callbacks can arrive in a burst (especially on chat open).
 * Posting one Runnable per message creates a long main-queue tail and lets
 * RecyclerView process many tiny mutations in separate frames. This queue
 * keeps the work ordered, but drains it once at the next frame boundary.
 * Message-keyed posts additionally replace an older pending task for the same
 * message, so a status burst does one latest bind instead of replaying every
 * intermediate state.
 *
 * The queued work is deliberately still supplied by the Activity, so this
 * class does not retain message data or know anything about chat lifecycle.
 */
public final class ChatUiEventBatcher {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private ArrayDeque<PendingTask> pending = new ArrayDeque<>();
    // Reused as the drain buffer. Swapping the two queues avoids copying every
    // pending task into a new ArrayDeque at the frame boundary. Posts that
    // arrive while callbacks are running go into the now-empty pending queue
    // and are therefore preserved for the next frame.
    private ArrayDeque<PendingTask> drainBuffer = new ArrayDeque<>();
    private final HashMap<String, PendingTask> pendingByMessage = new HashMap<>();
    private boolean frameScheduled;
    private boolean cancelled;
    private final Choreographer.FrameCallback frameCallback =
            frameTimeNanos -> drain();
    private final Runnable postFrameCallback = () ->
            Choreographer.getInstance().postFrameCallback(frameCallback);

    private static final class PendingTask {
        final String messageId;
        Runnable task;

        PendingTask(String messageId, Runnable task) {
            this.messageId = messageId;
            this.task = task;
        }
    }

    public void post(Runnable task) {
        if (task == null) return;
        enqueue(new PendingTask(null, task));
    }

    /**
     * Queues one latest task per message for the next frame. The first
     * occurrence keeps its position in the queue; later updates replace only
     * the Runnable payload, preserving cross-message arrival order while
     * collapsing same-message status/reaction/edit bursts.
     */
    public void postForMessage(String messageId, Runnable task) {
        if (task == null) return;
        if (messageId == null || messageId.isEmpty()) {
            post(task);
            return;
        }
        boolean schedule = false;
        synchronized (lock) {
            if (cancelled) return;
            PendingTask existing = pendingByMessage.get(messageId);
            if (existing != null) {
                existing.task = task;
                return;
            }
            PendingTask entry = new PendingTask(messageId, task);
            pendingByMessage.put(messageId, entry);
            pending.addLast(entry);
            if (!frameScheduled) {
                frameScheduled = true;
                schedule = true;
            }
        }
        if (schedule) scheduleNextFrame();
    }

    private void enqueue(PendingTask entry) {
        boolean schedule = false;
        synchronized (lock) {
            if (cancelled) return;
            pending.addLast(entry);
            if (!frameScheduled) {
                frameScheduled = true;
                schedule = true;
            }
        }
        if (schedule) scheduleNextFrame();
    }

    private void scheduleNextFrame() {
        main.post(postFrameCallback);
    }

    private void drain() {
        ArrayDeque<PendingTask> batch;
        synchronized (lock) {
            frameScheduled = false;
            if (cancelled || pending.isEmpty()) return;
            batch = pending;
            pending = drainBuffer;
            drainBuffer = batch;
            pendingByMessage.clear();
        }
        while (!batch.isEmpty()) {
            PendingTask pendingTask = batch.removeFirst();
            try {
                pendingTask.task.run();
            } catch (RuntimeException ignored) {
                // One stale Activity callback must not discard the rest of
                // the frame's message events.
            }
        }
        synchronized (lock) {
            if (!cancelled && !pending.isEmpty() && !frameScheduled) {
                frameScheduled = true;
                main.post(postFrameCallback);
            }
        }
    }

    public void cancel() {
        synchronized (lock) {
            cancelled = true;
            pending.clear();
            pendingByMessage.clear();
            frameScheduled = false;
        }
    }
}