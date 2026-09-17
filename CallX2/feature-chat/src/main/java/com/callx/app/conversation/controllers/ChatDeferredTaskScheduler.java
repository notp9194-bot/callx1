package com.callx.app.conversation.controllers;

import android.os.Handler;
import android.os.Looper;

import java.util.function.BooleanSupplier;

/**
 * Centralizes ChatActivity's "not needed on the very first frame" work —
 * deferred controller inits (playback-presence, recording-preview,
 * live-typing, pin, scheduled-send, ...) and background cleanup tasks
 * (prune, expiry cleanup, recent-chats preload).
 *
 * WHY THIS EXISTS (isolation / regression-risk reduction):
 * Before this class, every deferred controller/task was its own
 * `deferredTaskHandler.postDelayed(() -> { if (isFinishing() || isDestroyed())
 * return; ... }, delay)` block hand-written directly inside ChatActivity's
 * onCreate/onStart. Each new optional controller (poll, contact-share,
 * location-share, etc.) meant copy-pasting that same liveness-check
 * boilerplate into an already-huge Activity — easy to get the guard wrong
 * or forget it entirely, which is exactly how a leaked callback running
 * after onDestroy() sneaks in.
 *
 * Now: one shared Handler, one shared liveness check (supplied once at
 * construction), and callers just call schedule(delayMs, task). Adding a
 * new deferred controller is a one-line schedule() call instead of a new
 * hand-rolled postDelayed block — the guard can't be forgotten because
 * it's applied here, once, for everyone.
 *
 * Not a behavior change: same Handler semantics, same delays, same
 * "skip if Activity is finishing/destroyed" guard as before — just no
 * longer duplicated at every call site.
 */
public class ChatDeferredTaskScheduler {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BooleanSupplier isActivityGone;

    /**
     * @param isActivityGone returns true when the scheduled task should be
     *                        skipped (e.g. {@code () -> isFinishing() || isDestroyed()}).
     */
    public ChatDeferredTaskScheduler(BooleanSupplier isActivityGone) {
        this.isActivityGone = isActivityGone;
    }

    /** Runs {@code task} after {@code delayMs}, unless the Activity is gone by then. */
    public void schedule(long delayMs, Runnable task) {
        handler.postDelayed(() -> {
            if (isActivityGone.getAsBoolean()) return;
            task.run();
        }, delayMs);
    }

    /** Cancels every pending deferred task. Call from onDestroy(). */
    public void cancelAll() {
        handler.removeCallbacksAndMessages(null);
    }
}
