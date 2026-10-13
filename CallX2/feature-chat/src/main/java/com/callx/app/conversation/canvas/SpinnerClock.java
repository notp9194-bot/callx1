package com.callx.app.conversation.canvas;

import android.view.Choreographer;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

/**
 * v612 — one process-wide ticker for every indeterminate download/upload
 * spinner in the chat list.
 *
 * <p>Before: each gated bubble ran its own self-rescheduling chain —
 * {@code postInvalidateOnAnimation()} when its 30fps window had elapsed, else
 * {@code postInvalidateDelayed(rest)}, issued from inside {@code onDraw()}.
 * With N parallel downloads that is N Handler/Choreographer chains, and because
 * a scroll-triggered draw also re-arms the chain, chains could stack on the same
 * view (more than one pending invalidate per spinner).
 *
 * <p>Now: a view registers itself from {@code drawProgressRing()} (idempotent)
 * and this class posts exactly ONE {@link Choreographer.FrameCallback} for all of
 * them. A tick invalidates every registered view in the same frame (so all rings
 * advance together, one vsync, no per-view Handler messages) and the callback
 * stops by itself when nothing is registered.
 *
 * <p>Self-correcting lifecycle: a view that did not draw an indeterminate ring
 * since the previous tick (download finished, view rebound to a non-spinner
 * message, ...) is dropped on the next tick — the clock never needs to know the
 * bubble's state machine. A detached view is dropped immediately via
 * {@link #unregister}.
 *
 * <p>Main thread only. Holds strong refs only while a view is registered and
 * attached; {@code onDetachedFromWindow()} unregisters, so no Activity leak.
 */
final class SpinnerClock implements Choreographer.FrameCallback {

    private static final SpinnerClock INSTANCE = new SpinnerClock();

    /** Tolerance so a 60Hz frame (16.6ms) lands the 2-frame/33ms cadence instead of 3 frames. */
    private static final long FRAME_SLACK_MS = 4L;

    private MessageBubbleCanvasView[] views = new MessageBubbleCanvasView[8];
    private int count;
    private boolean posted;
    private long lastTickMs = Long.MIN_VALUE;
    private long intervalMs = 32L;

    private SpinnerClock() {}

    /** Idempotent. {@code minIntervalMs}: 32 normal tier, 64 low-RAM tier. */
    @MainThread
    static void register(@NonNull MessageBubbleCanvasView v, long minIntervalMs) {
        final SpinnerClock c = INSTANCE;
        c.intervalMs = minIntervalMs;
        for (int i = 0; i < c.count; i++) {
            if (c.views[i] == v) return;
        }
        if (c.count == c.views.length) {
            c.views = java.util.Arrays.copyOf(c.views, c.count * 2);
        }
        c.views[c.count++] = v;
        c.schedule();
    }

    @MainThread
    static void unregister(@NonNull MessageBubbleCanvasView v) {
        INSTANCE.removeAt(INSTANCE.indexOf(v));
    }

    /** Test/diagnostics hook. */
    @MainThread
    static int registeredCount() {
        return INSTANCE.count;
    }

    private int indexOf(MessageBubbleCanvasView v) {
        for (int i = 0; i < count; i++) if (views[i] == v) return i;
        return -1;
    }

    private void removeAt(int i) {
        if (i < 0 || i >= count) return;
        views[i] = views[count - 1];
        views[--count] = null;
    }

    private void schedule() {
        if (posted || count == 0) return;
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        posted = false;
        if (count == 0) return;
        final long nowMs = frameTimeNanos / 1_000_000L;
        if (lastTickMs == Long.MIN_VALUE || nowMs - lastTickMs >= intervalMs - FRAME_SLACK_MS) {
            lastTickMs = nowMs;
            android.os.Trace.beginSection("SpinnerClock#tick");
            try {
                // Backwards: removeAt() swaps the last element into slot i.
                for (int i = count - 1; i >= 0; i--) {
                    final MessageBubbleCanvasView v = views[i];
                    if (!v.isAttachedToWindow() || !v.consumeSpinnerTick()) {
                        removeAt(i);
                    }
                }
            } finally {
                android.os.Trace.endSection();
            }
        }
        schedule();
    }
}
