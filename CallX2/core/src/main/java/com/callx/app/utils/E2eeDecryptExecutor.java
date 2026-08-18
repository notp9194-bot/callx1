package com.callx.app.utils;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * E2eeDecryptExecutor — one shared, app-lifetime, never-shut-down SINGLE
 * THREAD dedicated to E2EEncryptionManager#decrypt() calls fired from
 * Firebase's ChildEventListener callbacks (which run on the main thread by
 * default).
 *
 * WHY A SEPARATE POOL FROM ChatIoExecutor (which is 6 threads): the double
 * ratchet is order-dependent — decrypting message N+1 before message N for
 * the same partner can desync the ratchet chain. Firebase always calls
 * onChildAdded/onChildChanged on the main thread in the order events
 * happened, so as long as decrypt work is handed off to a queue that
 * processes strictly FIFO, arrival order is preserved exactly as if it ran
 * synchronously — which a single-thread executor guarantees and a
 * multi-thread pool does not (two decrypts could start on different threads
 * and finish in either order). Using ChatIoExecutor's 6 threads here would
 * reintroduce exactly that race.
 *
 * WHY NOT PER-ACTIVITY: an Executors.newSingleThreadExecutor() created fresh
 * in ChatActivity and never shut down would leak one thread per chat-open —
 * the same class of bug ChatIoExecutor's own doc comment describes being
 * fixed for the general I/O pool. One shared, app-lifetime thread avoids
 * that entirely.
 *
 * Kept as its own dedicated thread rather than folded into ChatIoExecutor:
 * decrypt work must never queue behind unrelated I/O (DB writes, exports,
 * media uploads) on the same pool, or a burst of those could stall message
 * decryption — and conversely, a burst of incoming messages must never
 * starve other chat I/O. Same reasoning as ChatIoExecutor's own separation
 * from AppBgExecutor.
 */
public final class E2eeDecryptExecutor {

    private static final Executor INSTANCE = Executors.newSingleThreadExecutor();

    private E2eeDecryptExecutor() {}

    public static Executor get() {
        return INSTANCE;
    }

    public static void execute(Runnable task) {
        INSTANCE.execute(task);
    }
}
