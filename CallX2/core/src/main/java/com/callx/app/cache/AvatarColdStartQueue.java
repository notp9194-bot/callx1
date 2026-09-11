package com.callx.app.cache;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AvatarColdStartQueue — FIX (cold-start priority queue): app cold-open par
 * visible-screen avatars ({@link AvatarBinderCore#bind}, always
 * Priority.HIGH) already load turant — that path never touches this class.
 * The gap was {@link AvatarBinderCore#prefetch}'s scroll-ahead work
 * (Priority.LOW): the very first frames after process start are often
 * several adapters/lists initializing at once (chat list, a status ring
 * strip, a follow-list widget...), and every one of them can fire its own
 * prefetch() in the same cold-start window the visible screen's OWN
 * HIGH-priority avatars are still racing to paint — all of it landing on
 * Glide's request queue and the network stack together right when
 * time-to-first-paint matters most.
 *
 * This defers (never cancels) prefetch()'s LOW-priority work during a short
 * post-launch window, staggering it out in small steps instead of firing it
 * all in the same frame as the visible screen's own binds. Visible-row
 * bind() is completely untouched — it never routes through this class, so
 * it's never delayed by so much as a millisecond.
 *
 * The window closes the moment the first screen is actually up (see
 * {@link #markFirstScreenPainted()}, called from CallxApp's
 * ActivityLifecycleCallbacks#onActivityResumed) OR after
 * {@link #WINDOW_CEILING_MS} regardless — a safety valve so a missed/late
 * resume signal (e.g. a process started purely for a background job) can
 * never leave prefetch() gated forever.
 */
public final class AvatarColdStartQueue {

    private AvatarColdStartQueue() {}

    private static final long WINDOW_CEILING_MS = 2500L; // hard safety ceiling
    private static final long STAGGER_STEP_MS = 120L;    // gap between successive staggered prefetch batches
    private static final int MAX_STAGGER_SLOTS = 8;      // beyond this, just run now — bounded worst-case delay

    private static volatile long sProcessStartAtMs = 0L;
    private static final AtomicBoolean sReleased = new AtomicBoolean(true); // released by default until onProcessStart runs
    private static final AtomicLong sNextSlot = new AtomicLong(0);
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /** Call once from CallxApp#onCreate, as early as possible. */
    public static void onProcessStart() {
        sProcessStartAtMs = SystemClock.elapsedRealtime();
        sNextSlot.set(0);
        sReleased.set(false);
        // Safety valve — release unconditionally after the ceiling even if
        // no Activity ever resumes in this process (e.g. a WorkManager-only
        // background launch), so prefetch() can never stay gated forever.
        sMainHandler.postDelayed(() -> sReleased.set(true), WINDOW_CEILING_MS);
    }

    /** Call from CallxApp's ActivityLifecycleCallbacks#onActivityResumed —
     *  idempotent, so it's fine to call on every resume, not just the first. */
    public static void markFirstScreenPainted() {
        sReleased.set(true);
    }

    private static boolean inColdStartWindow() {
        if (sReleased.get()) return false;
        return SystemClock.elapsedRealtime() - sProcessStartAtMs < WINDOW_CEILING_MS;
    }

    /**
     * Runs {@code task} immediately outside the cold-start window (the
     * overwhelmingly common case — this only ever matters in the first
     * couple seconds after process start). Inside the window, schedules it
     * on the next staggered slot instead, so a burst of prefetch() calls
     * from several simultaneously-initializing adapters doesn't all hit the
     * network in the same frame as the visible screen's own HIGH-priority
     * binds. Always runs on the main thread (matches Glide's own
     * main-thread request-issuing convention).
     */
    public static void runStaggered(Runnable task) {
        if (!inColdStartWindow()) {
            task.run();
            return;
        }
        long slot = sNextSlot.getAndIncrement();
        if (slot >= MAX_STAGGER_SLOTS) {
            task.run(); // past the bounded stagger budget — run now rather than let delay keep growing
            return;
        }
        sMainHandler.postDelayed(task, STAGGER_STEP_MS * (slot + 1));
    }
}
