package com.callx.app.utils;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * E2eeDecryptExecutor — ULTRA-OPT (partner-sharded FIFO pool).
 *
 * BEFORE: one single shared thread for EVERY partner's E2EEncryptionManager#
 * decrypt() call, app-wide. Correct (see history below) but needlessly slow
 * under load: opening Chat A while a background FCM/db burst is decrypting
 * Chat B's history, or having ChatActivity + a SmallWindow floating chat
 * open for two different partners at once, serialized ALL of it onto one
 * thread even though those partners' double-ratchet chains are completely
 * independent of each other.
 *
 * WHY FIFO ORDER MATTERS (unchanged from the single-thread version): the
 * double ratchet is order-dependent — decrypting message N+1 before message
 * N for the SAME partner can desync that partner's chain. Firebase always
 * calls onChildAdded/onChildChanged on the main thread in the order events
 * happened, so decrypt work for one partner must be processed strictly FIFO,
 * in submission order, to preserve that.
 *
 * THE FIX — shard by partnerUid, not globally: E2EEncryptionManager already
 * keeps a per-partner lock (partnerLocks / lockFor(partnerUid)) because two
 * DIFFERENT partners' sessions never touch each other's ratchet state. So
 * instead of one global FIFO thread, this pool keeps BUCKET_COUNT dedicated
 * single-thread executors and routes every task by
 * {@code floorMod(partnerUid.hashCode(), BUCKET_COUNT)}.
 *   - Same partnerUid always hashes to the same bucket → every task for
 *     that partner still lands on the same single thread, in submission
 *     order → FIFO-per-partner (the only ordering guarantee that was ever
 *     actually required) is fully preserved, bit-for-bit as safe as before.
 *   - Different partnerUids usually land in different buckets → their
 *     decrypts now run truly in parallel instead of queueing behind each
 *     other. (Two partners occasionally colliding into the same bucket is
 *     harmless — that pair just falls back to the old serialized behavior,
 *     never a correctness issue, only a rare miss on the parallelism win.)
 *
 * BUCKET_COUNT is a small fixed number (not tied to core count) — this is
 * cheap FIFO crypto/disk-I/O work, not CPU-bound compute, so a handful of
 * dedicated threads is enough to unblock the common "2-3 chats/notifications
 * active at once" case without spawning a thread per partner.
 *
 * WHY NOT PER-ACTIVITY / PER-PARTNER-ON-DEMAND: creating and never shutting
 * down a fresh executor per partner would leak a thread per unique
 * conversation ever opened over the life of the app. Fixed, shared,
 * app-lifetime buckets avoid that entirely — same reasoning as the original
 * single-thread version's own "WHY NOT PER-ACTIVITY" note.
 *
 * Kept as its own dedicated pool rather than folded into ChatIoExecutor:
 * decrypt work must never queue behind unrelated I/O (DB writes, exports,
 * media uploads) on the same pool, or a burst of those could stall message
 * decryption — and conversely, a burst of incoming messages must never
 * starve other chat I/O. Same reasoning as ChatIoExecutor's own separation
 * from AppBgExecutor.
 */
public final class E2eeDecryptExecutor {

    /** Small and fixed on purpose — see class doc. */
    private static final int BUCKET_COUNT = 4;

    private static final ExecutorService[] BUCKETS = new ExecutorService[BUCKET_COUNT];

    static {
        for (int i = 0; i < BUCKET_COUNT; i++) {
            BUCKETS[i] = Executors.newSingleThreadExecutor(namedDaemonFactory(i));
        }
    }

    private static ThreadFactory namedDaemonFactory(int bucketIndex) {
        final AtomicInteger seq = new AtomicInteger(0);
        return r -> {
            Thread t = new Thread(r, "E2eeDecrypt-" + bucketIndex + "-" + seq.incrementAndGet());
            // Daemon: this pool is app-lifetime and intentionally never
            // shut down (see class doc), so it must not hold the JVM open.
            t.setDaemon(true);
            return t;
        };
    }

    private E2eeDecryptExecutor() {}

    /** floorMod (not %) so a negative hashCode still maps into [0, BUCKET_COUNT). */
    private static int bucketFor(String partnerUid) {
        if (partnerUid == null || partnerUid.isEmpty()) return 0;
        return Math.floorMod(partnerUid.hashCode(), BUCKET_COUNT);
    }

    /**
     * PREFERRED entry point. Routes to the FIFO bucket dedicated to this
     * partner, so decrypts for other partners are never blocked behind this
     * one. Always pass the same partnerUid string that
     * E2EEncryptionManager#decrypt(...)/lockFor(partnerUid) uses for this
     * task, or FIFO-per-partner is not guaranteed.
     */
    public static void execute(String partnerUid, Runnable task) {
        BUCKETS[bucketFor(partnerUid)].execute(task);
    }

    /**
     * Legacy no-partner overload — kept only so any not-yet-migrated call
     * site still compiles and still runs off the main thread. Always routes
     * to bucket 0, so it stays strictly FIFO against every other unkeyed
     * caller, but forfeits the parallelism win above. Prefer
     * {@link #execute(String, Runnable)}.
     */
    @Deprecated
    public static void execute(Runnable task) {
        BUCKETS[0].execute(task);
    }

    /**
     * Returns an {@link Executor} view scoped to one partner — handy for
     * callers (e.g. a ChatActivity open for exactly one partner) that want
     * to hold a field instead of passing partnerUid on every call.
     */
    public static Executor forPartner(String partnerUid) {
        return BUCKETS[bucketFor(partnerUid)];
    }

    /** Legacy accessor — returns bucket 0 for parity with the old single-
     * thread {@code get()}. Prefer {@link #forPartner(String)}. */
    @Deprecated
    public static Executor get() {
        return BUCKETS[0];
    }
}
