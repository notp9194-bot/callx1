package com.callx.app.utils;

import android.os.Process;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PERF v840: ThreadFactory that lowers worker priority. Executors created on the main thread
 * inherit its priority, so ~30 background pools competed with the main thread for CPU during
 * chat open (worst on a cold process). Only deferrable / non-critical pools use this —
 * ChatIoExecutor, UiCriticalReadExecutor and E2eeDecryptExecutor (main waits on them) do NOT.
 *
 * <ul>
 *   <li>{@link #LOW}  = THREAD_PRIORITY_BACKGROUND (10): prefetch / dedup — pure housekeeping.
 *       Android moves these into the background cgroup, so they are heavily throttled while the
 *       UI is busy.</li>
 *   <li>{@link #MILD} = 8: user-visible work (upload / download / compress / re-encrypt). Stays in
 *       the foreground cgroup (only ≥10 leaves it) but yields to the main thread's nice 0.</li>
 * </ul>
 */
public final class PrioThreadFactory implements ThreadFactory {

    public static final int LOW  = Process.THREAD_PRIORITY_BACKGROUND;
    public static final int MILD = 8;

    private final String prefix;
    private final int niceValue;
    private final AtomicInteger n = new AtomicInteger();

    public PrioThreadFactory(String prefix, int niceValue) {
        this.prefix = prefix;
        this.niceValue = niceValue;
    }

    @Override
    public Thread newThread(final Runnable r) {
        return new Thread(() -> {
            try { Process.setThreadPriority(niceValue); } catch (Throwable ignored) { }
            r.run();
        }, prefix + "-" + n.incrementAndGet());
    }
}
