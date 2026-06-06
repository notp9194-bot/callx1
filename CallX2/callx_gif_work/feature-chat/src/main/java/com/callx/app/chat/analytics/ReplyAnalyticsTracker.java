package com.callx.app.chat.analytics;

import android.util.Log;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ReplyAnalyticsTracker — Lightweight in-memory analytics for swipe-to-reply.
 *
 * Tracks:
 *   • Swipe attempts (any swipe gesture started)
 *   • Trigger success rate (threshold reached / attempts)
 *   • Undo usage count
 *   • Average swipe distance (pixels)
 *
 * Note: For production, swap the log calls with your analytics SDK (Firebase, Mixpanel, etc.)
 */
public class ReplyAnalyticsTracker {

    private static final String TAG = "ReplyAnalytics";

    private final AtomicInteger swipeAttempts    = new AtomicInteger(0);
    private final AtomicInteger triggerSuccess   = new AtomicInteger(0);
    private final AtomicInteger undoCount        = new AtomicInteger(0);
    private final AtomicLong    totalSwipePixels = new AtomicLong(0);

    private static final ReplyAnalyticsTracker INSTANCE = new ReplyAnalyticsTracker();
    public static ReplyAnalyticsTracker get() { return INSTANCE; }

    private ReplyAnalyticsTracker() {}

    /** Call when a swipe gesture is detected (any dx > 0). */
    public void onSwipeAttempt(float distancePx) {
        swipeAttempts.incrementAndGet();
        totalSwipePixels.addAndGet((long) Math.abs(distancePx));
    }

    /** Call when swipe threshold is crossed (reply triggered). */
    public void onSwipeTriggered() {
        triggerSuccess.incrementAndGet();
        logStats();
    }

    /** Call when user taps Undo in snackbar. */
    public void onUndoUsed() {
        undoCount.incrementAndGet();
    }

    /** Returns success rate as 0.0-1.0. */
    public float getSuccessRate() {
        int attempts = swipeAttempts.get();
        if (attempts == 0) return 0f;
        return (float) triggerSuccess.get() / attempts;
    }

    /** Returns average swipe distance in pixels. */
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
    }
}
