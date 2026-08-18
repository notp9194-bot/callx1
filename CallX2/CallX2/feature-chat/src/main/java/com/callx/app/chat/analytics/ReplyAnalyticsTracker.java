package com.callx.app.chat.analytics;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ReplyAnalyticsTracker — in-memory swipe-to-reply analytics, now with a
 * batched, debounced Firebase flush (see ReplyAnalyticsFlushWorker).
 *
 * Tracks:
 *   • Swipe attempts (any swipe gesture started)
 *   • Trigger success rate (threshold reached / attempts)
 *   • Undo usage count
 *   • Average swipe distance (pixels)
 *
 * PERF ADV — event → Firebase write pipeline:
 *   Every onSwipeAttempt()/onSwipeTriggered()/onUndoUsed() call is a pure
 *   local counter increment (near-zero cost, safe to call on every touch
 *   event) PLUS a call to scheduleFlush(), which enqueues
 *   ReplyAnalyticsFlushWorker as UNIQUE work with ExistingWorkPolicy.REPLACE
 *   and a short initial delay. Ten swipes in the next few seconds still
 *   collapse into exactly one enqueue → one network write, and the job
 *   survives process death (unlike a plain Handler debounce), so nothing
 *   is lost if the user swipes the app away right after a burst of swipes.
 *   This deliberately does NOT write to Firebase directly from these
 *   methods — that would be exactly the per-event write cost this exists
 *   to avoid.
 */
public class ReplyAnalyticsTracker {

    private static final String TAG = "ReplyAnalytics";

    // ── Session-lifetime counters (never reset by a flush) — for any
    // future in-app debug/stats display. Independent of the "pending"
    // counters below, which is what actually gets flushed to Firebase. ──
    private final AtomicInteger swipeAttempts    = new AtomicInteger(0);
    private final AtomicInteger triggerSuccess   = new AtomicInteger(0);
    private final AtomicInteger undoCount        = new AtomicInteger(0);
    private final AtomicLong    totalSwipePixels = new AtomicLong(0);

    // ── Pending-since-last-flush counters — drained by snapshotAndReset()
    // and written to Firebase in one batch by ReplyAnalyticsFlushWorker. ──
    private final AtomicInteger pendingSwipeAttempts    = new AtomicInteger(0);
    private final AtomicInteger pendingTriggerSuccess   = new AtomicInteger(0);
    private final AtomicInteger pendingUndoCount        = new AtomicInteger(0);
    private final AtomicLong    pendingTotalSwipePixels = new AtomicLong(0);

    private static final String WORK_NAME = "reply_analytics_flush";
    private static final long   FLUSH_DEBOUNCE_SECONDS = 20;

    private static final ReplyAnalyticsTracker INSTANCE = new ReplyAnalyticsTracker();
    public static ReplyAnalyticsTracker get() { return INSTANCE; }

    private ReplyAnalyticsTracker() {}

    /** Call when a swipe gesture is detected (any dx > 0). */
    public void onSwipeAttempt(Context ctx, float distancePx) {
        swipeAttempts.incrementAndGet();
        totalSwipePixels.addAndGet((long) Math.abs(distancePx));
        pendingSwipeAttempts.incrementAndGet();
        pendingTotalSwipePixels.addAndGet((long) Math.abs(distancePx));
        scheduleFlush(ctx);
    }

    /** Call when swipe threshold is crossed (reply triggered). */
    public void onSwipeTriggered(Context ctx) {
        triggerSuccess.incrementAndGet();
        pendingTriggerSuccess.incrementAndGet();
        logStats();
        scheduleFlush(ctx);
    }

    /** Call when user taps Undo in snackbar. */
    public void onUndoUsed(Context ctx) {
        undoCount.incrementAndGet();
        pendingUndoCount.incrementAndGet();
        scheduleFlush(ctx);
    }

    /** Returns success rate as 0.0-1.0 (session lifetime, not just pending). */
    public float getSuccessRate() {
        int attempts = swipeAttempts.get();
        if (attempts == 0) return 0f;
        return (float) triggerSuccess.get() / attempts;
    }

    /** Returns average swipe distance in pixels (session lifetime). */
    public float getAvgSwipeDistance() {
        int attempts = swipeAttempts.get();
        if (attempts == 0) return 0f;
        return (float) totalSwipePixels.get() / attempts;
    }

    public int getSwipeAttempts()  { return swipeAttempts.get(); }
    public int getTriggerSuccess() { return triggerSuccess.get(); }
    public int getUndoCount()      { return undoCount.get(); }

    private void logStats() {
        Log.d(TAG, String.format(
                "Reply stats → attempts=%d success=%d rate=%.0f%% undos=%d avgDist=%.0fpx",
                swipeAttempts.get(), triggerSuccess.get(),
                getSuccessRate() * 100, undoCount.get(), getAvgSwipeDistance()));
    }

    /** Reset all counters (call on session end or for testing). */
    public void reset() {
        swipeAttempts.set(0);
        triggerSuccess.set(0);
        undoCount.set(0);
        totalSwipePixels.set(0);
        pendingSwipeAttempts.set(0);
        pendingTriggerSuccess.set(0);
        pendingUndoCount.set(0);
        pendingTotalSwipePixels.set(0);
    }

    // ── Flush plumbing (used by ReplyAnalyticsFlushWorker) ────────────────

    /** Immutable copy of the pending-since-last-flush deltas. */
    public static final class Snapshot {
        public final int  swipeAttempts;
        public final int  triggerSuccess;
        public final int  undoCount;
        public final long totalSwipePixels;

        Snapshot(int swipeAttempts, int triggerSuccess, int undoCount, long totalSwipePixels) {
            this.swipeAttempts = swipeAttempts;
            this.triggerSuccess = triggerSuccess;
            this.undoCount = undoCount;
            this.totalSwipePixels = totalSwipePixels;
        }

        public boolean isEmpty() {
            return swipeAttempts == 0 && triggerSuccess == 0
                    && undoCount == 0 && totalSwipePixels == 0;
        }
    }

    /**
     * Atomically drains the pending counters into a {@link Snapshot} and
     * zeroes them. Safe to call from the worker's background thread — any
     * increments that land concurrently (a swipe mid-flush) go into the
     * post-reset counters and are picked up by the next flush, never lost
     * and never double-counted.
     */
    public Snapshot snapshotAndReset() {
        return new Snapshot(
                pendingSwipeAttempts.getAndSet(0),
                pendingTriggerSuccess.getAndSet(0),
                pendingUndoCount.getAndSet(0),
                pendingTotalSwipePixels.getAndSet(0));
    }

    /** Called by the worker when a flush fails — puts the deltas back so
     *  the next successful flush still includes them. */
    public void mergeBack(Snapshot snap) {
        if (snap == null) return;
        pendingSwipeAttempts.addAndGet(snap.swipeAttempts);
        pendingTriggerSuccess.addAndGet(snap.triggerSuccess);
        pendingUndoCount.addAndGet(snap.undoCount);
        pendingTotalSwipePixels.addAndGet(snap.totalSwipePixels);
    }

    /**
     * Enqueues (or re-coalesces) a debounced batch flush. ExistingWorkPolicy
     * .REPLACE means a burst of events just keeps pushing the same unique
     * job's start time back — the actual Firebase write only happens once
     * swiping has been quiet for FLUSH_DEBOUNCE_SECONDS, exactly the same
     * "wait for quiet" idea as the Handler-based debounces used elsewhere
     * in this app for typing/read-receipts, but backed by WorkManager so
     * it isn't lost if the process dies before the delay elapses.
     */
    private void scheduleFlush(Context ctx) {
        if (ctx == null) return;
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReplyAnalyticsFlushWorker.class)
                .setInitialDelay(FLUSH_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(ctx.getApplicationContext())
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request);
    }
}
