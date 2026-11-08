package com.callx.app.utils;

import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One shared, bounded background pool for Status work (bitmap compose, file IO, downloads,
 * link previews). Replaces ad-hoc {@code new Thread(...)} and per-call
 * {@code Executors.newSingleThreadExecutor()} (which was never shut down, so every call leaked
 * a thread). Threads are daemon, background priority, and time out when idle.
 *
 * Rule: heavy work here, UI/View work on the main thread. Callers must re-check
 * {@code isFinishing()} before touching the UI from a result.
 */
public final class StatusBg {
    private StatusBg() {}

    private static final AtomicInteger N = new AtomicInteger();

    public static final ExecutorService IO = create(3);

    private static ExecutorService create(int threads) {
        final ThreadFactory tf = r -> {
            Thread t = new Thread(() -> {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
                catch (Throwable ignored) {}
                r.run();
            }, "status-bg-" + N.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        final ThreadPoolExecutor ex = new ThreadPoolExecutor(
                threads, threads, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), tf);
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }
}
