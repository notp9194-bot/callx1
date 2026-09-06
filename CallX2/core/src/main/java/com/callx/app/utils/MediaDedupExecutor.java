package com.callx.app.utils;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * MediaDedupExecutor — one shared, app-lifetime, never-shut-down pool for
 * MediaDedupManager's post-upload cache writes (local Room insert + the
 * fire-and-forget server /media/dedup-register call).
 *
 * WHY ITS OWN POOL rather than reusing AppBgExecutor: the server register
 * call is a real network request that can sit for up to its timeout under a
 * flaky connection. AppBgExecutor's doc explicitly scopes it to "quick,
 * low-frequency" DB/Firebase writes — a stalled network call sitting on one
 * of AppBgExecutor's 4 threads during a bad-connection moment would queue
 * unrelated folder/backup/export writes behind it. Keeping this on its own
 * small pool means a slow dedup register never blocks anything else, and a
 * burst of registers (a multi-image group upload finishing at once) never
 * starves AppBgExecutor's other callers either.
 *
 * Sized at 2: this work is fire-and-forget and non-blocking by design (see
 * MediaDedupManager#register), so it never needs to keep up with upload
 * throughput in real time — it just needs to eventually catch up.
 */
public final class MediaDedupExecutor {

    private static final Executor INSTANCE = Executors.newFixedThreadPool(2);

    private MediaDedupExecutor() {}

    public static Executor get() {
        return INSTANCE;
    }

    public static void execute(Runnable task) {
        INSTANCE.execute(task);
    }
}
