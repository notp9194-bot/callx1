package com.callx.app.utils;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * UiCriticalReadExecutor — dedicated, never-shut-down single-thread pool
 * reserved for the ONE Room read a screen needs to show its first real data
 * (e.g. Chat List's "load from Room for instant offline display" query).
 *
 * PERF FIX v237 — Chat List "Load time 378ms vs 150ms target":
 * ChatsFragment.loadFromRoom() used to dispatch through the shared
 * AppBgExecutor (4 threads) — the SAME pool that also carries folder
 * assignment writes, chat-delete writes, backup/export writes, and
 * ChatMessageSender's per-message-send Room insert (the highest-frequency
 * consumer of that pool). If any of those happened to be queued or running
 * at the exact moment the Chat List screen opened, the first-paint read sat
 * behind them, adding straight to the measured "Load time". None of those
 * writes are remotely as latency-sensitive as "the very first screen the
 * user sees needs to paint real data" — they're fire-and-forget by design.
 *
 * Fix: give first-paint reads their OWN single dedicated thread, same
 * "shared, app-lifetime, never shutdown" idiom as AppBgExecutor (see that
 * class's doc — this is intentional, not the v182 per-call-executor leak
 * pattern), so a screen's first read never queues behind unrelated writes.
 *
 * Keep this for "instant first paint" reads ONLY (small, one-shot, off the
 * main thread). Anything else — writes, high-frequency work, bulk reads —
 * still belongs on AppBgExecutor.
 */
public final class UiCriticalReadExecutor {

    private static final Executor INSTANCE = Executors.newSingleThreadExecutor();

    private UiCriticalReadExecutor() {}

    public static Executor get() {
        return INSTANCE;
    }

    public static void execute(Runnable task) {
        INSTANCE.execute(task);
    }
}
