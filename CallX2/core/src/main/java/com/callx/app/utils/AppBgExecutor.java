package com.callx.app.utils;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * AppBgExecutor — one shared, never-shut-down background pool for small
 * fire-and-forget DB/Firebase writes (folder assignment, chat delete, one-off
 * Room reads on a fragment, etc).
 *
 * PERF FIX: several call sites (ChatsFragment, ChatListViewModel, and
 * others) used to do `Executors.newSingleThreadExecutor().execute(...)`
 * inline — a BRAND NEW single-thread pool created on every call, with
 * nothing anywhere ever calling shutdown() on it. Each of those threads
 * lives forever (a plain ExecutorService's worker thread does not exit
 * just because its one task finished — it sits parked waiting for more
 * work). A normal session — assigning a few chats to folders, deleting a
 * couple of chats, switching folder tabs a handful of times — leaks a
 * handful of permanently-idle-but-alive threads every time; a long day of
 * normal usage leaks dozens. Each one holds a ~1MB stack and adds to
 * scheduler contention app-wide, which is exactly the kind of thing that
 * makes "everything feels slower than it used to" true without any single
 * screen being the obvious cause.
 *
 * Fix: one small shared pool, reused by every call site below instead of
 * spinning up a new one each time. Bounded (4 threads) since this is only
 * ever used for quick, low-frequency background writes — not for anything
 * throughput-sensitive (Paging/message sync already have their own
 * dedicated executors).
 *
 * SIZE NOTE: bumped 3 → 4 when ChatMessageSender's per-send Room insert
 * (previously its own always-leaked thread) was folded into this pool —
 * that call site is higher-frequency than the others (fires on every
 * message send), so one extra thread keeps it from queuing behind
 * unrelated folder/backup/export writes during a fast send burst.
 */
public final class AppBgExecutor {

    private static final Executor INSTANCE = Executors.newFixedThreadPool(4);

    private AppBgExecutor() {}

    public static Executor get() {
        return INSTANCE;
    }

    public static void execute(Runnable task) {
        INSTANCE.execute(task);
    }
}
