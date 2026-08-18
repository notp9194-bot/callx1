package com.callx.app.chat.analytics;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.WorkerParameters;
import androidx.work.Worker;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ReplyAnalyticsFlushWorker — PERF ADV: batched, debounced Firebase write
 * for swipe-to-reply analytics.
 *
 * ════════════════════════════════════════════════════════════════════════
 *  WHY THIS EXISTS
 * ════════════════════════════════════════════════════════════════════════
 * ReplyAnalyticsTracker used to be purely in-memory (AtomicInteger
 * counters) — every swipe/trigger/undo bumped a local counter and logged
 * it, but nothing was ever actually sent anywhere, so all of it was lost
 * the moment the process died. The fix is NOT "write to Firebase on every
 * event" (that would be a real per-event write cost, exactly what we're
 * trying to avoid) — it's to buffer events locally (already free — plain
 * counter increments) and flush the accumulated deltas in ONE batched
 * write via WorkManager, which:
 *
 *   1. COALESCES bursts — ReplyAnalyticsTracker.scheduleFlush() enqueues
 *      this worker as unique work with ExistingWorkPolicy.REPLACE and a
 *      short initial delay, so ten swipes in the next few seconds still
 *      result in exactly one enqueued job, not ten.
 *   2. SURVIVES PROCESS DEATH — unlike a plain Handler.postDelayed()
 *      debounce (used elsewhere in this app for typing/read-receipts,
 *      where that's the right tradeoff because those need to feel
 *      "live"), a WorkManager job is persisted to disk and still runs
 *      after the user swipes the app away or the system kills it for
 *      memory. Analytics counters aren't latency-sensitive, so this is
 *      pure upside here.
 *   3. RETRIES FOR FREE — NetworkType.CONNECTED constraint means the
 *      write simply waits for connectivity instead of firing-and-failing
 *      silently the way a bare DatabaseReference.setValue() call would
 *      while offline.
 *
 * Firebase path: analytics/replySwipe/{uid} — 4 counters, aggregated with
 * ServerValue.increment() so concurrent flushes from multiple sessions/
 * devices for the same user add up correctly instead of clobbering.
 */
public class ReplyAnalyticsFlushWorker extends Worker {

    public ReplyAnalyticsFlushWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String uid = FirebaseUtils.getCurrentUid();
        if (uid == null) return Result.success(); // not signed in — nothing to attribute this to

        // Atomically snapshot-and-reset — see ReplyAnalyticsTracker.snapshotAndReset()
        // javadoc. If another burst of swipes happens WHILE this worker is
        // running, they land in the counters this call just reset, and the
        // next scheduleFlush() debounce cycle picks them up — never lost,
        // never double-counted.
        ReplyAnalyticsTracker.Snapshot snap = ReplyAnalyticsTracker.get().snapshotAndReset();
        if (snap.isEmpty()) return Result.success(); // nothing accumulated since the last flush

        Map<String, Object> updates = new HashMap<>();
        updates.put("swipeAttempts", ServerValue.increment(snap.swipeAttempts));
        updates.put("triggerSuccess", ServerValue.increment(snap.triggerSuccess));
        updates.put("undoCount", ServerValue.increment(snap.undoCount));
        updates.put("totalSwipePixels", ServerValue.increment(snap.totalSwipePixels));
        updates.put("lastFlushedAt", ServerValue.TIMESTAMP);

        DatabaseReference ref = FirebaseUtils.db()
                .getReference("analytics").child("replySwipe").child(uid);

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        ref.updateChildren(updates, (error, r) -> {
            ok[0] = (error == null);
            latch.countDown();
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        if (ok[0]) return Result.success();
        // Failed (offline mid-flight, etc.) — put the deltas back so the
        // next successful flush still includes them, then let WorkManager
        // retry with its backoff policy.
        ReplyAnalyticsTracker.get().mergeBack(snap);
        return Result.retry();
    }
}
