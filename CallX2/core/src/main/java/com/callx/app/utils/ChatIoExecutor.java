package com.callx.app.utils;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * ChatIoExecutor — one shared, app-lifetime, never-shut-down thread pool for
 * conversation-screen I/O (ChatActivity + GroupChatActivity: message loads,
 * sends, reactions, polls, exports, scheduled sends, presence writes, etc).
 *
 * WHATSAPP-STYLE FIX: ChatActivity and GroupChatActivity used to each spin
 * up their OWN ExecutorService — `newFixedThreadPool(4)` / `newFixedThreadPool(2)`
 * — in onCreate() and shut it down in onDestroy(). WhatsApp does not do this;
 * it keeps a small number of long-lived, app-wide pools and never tears one
 * down just because one screen closed. Creating/destroying a thread pool on
 * every single chat open is expensive by itself (allocating threads is not
 * free), and — as the RejectedExecutionException crash fixed earlier in
 * ChatActivity showed — tying a pool's lifetime to one Activity instance
 * means ANY task still in flight (or posted with a delay) when the user
 * backs out of the chat races the shutdown() call and can crash the app.
 *
 * Fix: a single shared pool for this whole class of work, reused by every
 * ChatActivity/GroupChatActivity instance for as long as the app process is
 * alive — same "shared, app-lifetime, never shutdown" idiom already used by
 * AppBgExecutor (general fire-and-forget writes) and UiCriticalReadExecutor
 * (first-paint reads). Kept as its OWN pool rather than folded into
 * AppBgExecutor because conversation I/O is much higher-frequency (every
 * message send/receive) than AppBgExecutor's low-frequency writes, and
 * mixing the two would let a chat-open flood queue behind unrelated work
 * (or vice versa) — see UiCriticalReadExecutor's doc comment for the same
 * reasoning applied to first-paint reads.
 *
 * Sized at 6: covers ChatActivity's old 4 + GroupChatActivity's old 2 with
 * the same total headroom as before, for the (common) case of a 1:1 chat and
 * a group chat both being open at once (e.g. one in the back stack).
 */
public final class ChatIoExecutor {

    private static final Executor INSTANCE = Executors.newFixedThreadPool(6);

    private ChatIoExecutor() {}

    public static Executor get() {
        return INSTANCE;
    }

    public static void execute(Runnable task) {
        INSTANCE.execute(task);
    }
}
