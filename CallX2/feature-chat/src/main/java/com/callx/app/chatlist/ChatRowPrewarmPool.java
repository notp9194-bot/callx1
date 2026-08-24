package com.callx.app.chatlist;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.callx.app.chat.R;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ChatRowPrewarmPool — v95 ultra optimization: off-main-thread XML inflation
 * for chat-list rows.
 *
 * ROOT COST BEING REMOVED
 * ────────────────────────
 * Every RecyclerView.Adapter.onCreateViewHolder() call pays the FULL cost of
 * LayoutInflater.inflate(R.layout.item_chat, ...) synchronously on the main
 * thread: XML parse, resource resolution, and construction of the entire
 * canvas-view subtree (ChatRowContentView, ChatListUnreadBadgeView,
 * ChatListStoryRingView, ChatListCallButtonsView, CircleImageView, etc).
 * With the 25-VH RecycledViewPool this only happens ~25 times total per
 * process — but ALL 25 of those inflations currently land in a tight burst
 * exactly when the user opens the Chats tab for the very first time (or
 * after a process-wide pool eviction), which is also the exact moment
 * they're most likely to immediately start scrolling. That burst is real,
 * synchronous main-thread work stacked right at first paint.
 *
 * FIX — same idea androidx.asynclayoutinflater.AsyncLayoutInflater uses,
 * implemented directly here to avoid a new dependency: a single background
 * HandlerThread holds its own LayoutInflater (cloned into the target
 * Context via cloneInContext, exactly as AsyncLayoutInflater does — this is
 * the officially-supported way to inflate off the main thread). It inflates
 * item_chat rows ahead of time and parks the finished, fully-constructed
 * View objects in a lock-free queue. ChatListAdapter.onCreateViewHolder()
 * then just polls this queue first — a queue poll is O(1) and effectively
 * free compared to a real inflate — and only falls back to a normal
 * synchronous inflate if the pool hasn't caught up yet (e.g. a very fast
 * burst of scrolling before prewarm finishes).
 *
 * SAFETY NOTES
 *  • inflate(id, parent, false) is called with attachToRoot=false and the
 *    real RecyclerView passed only so the correct LayoutParams subtype gets
 *    generated for the row (RecyclerView.LayoutParams) — the parent itself
 *    is never mutated (no addView) from the background thread, so this is
 *    the same safe pattern AsyncLayoutInflater uses internally.
 *  • The inflater is cloned once per Context via cloneInContext — no shared
 *    mutable LayoutInflater state crosses threads.
 *  • start()/stop() are idempotent and safe to call from onViewCreated /
 *    onDestroyView respectively.
 */
final class ChatRowPrewarmPool {

    // Enough to cover the RecycledViewPool ceiling (25) without over-inflating
    // views that will just sit unused — see RecyclerViewPoolViewModel.
    private static final int TARGET_SIZE = 14;

    private final ConcurrentLinkedQueue<View> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlightOrQueued = new AtomicInteger(0);

    private HandlerThread thread;
    private Handler bgHandler;
    private LayoutInflater bgInflater;

    void start(Context appContext, ViewGroup parentForLayoutParams) {
        if (thread != null) return; // already running
        thread = new HandlerThread("ChatRowPrewarm");
        thread.start();
        bgHandler = new Handler(thread.getLooper());
        bgInflater = LayoutInflater.from(appContext).cloneInContext(appContext);

        for (int i = 0; i < TARGET_SIZE; i++) {
            scheduleOneInflate(parentForLayoutParams);
        }
    }

    private void scheduleOneInflate(ViewGroup parentForLayoutParams) {
        if (inFlightOrQueued.get() >= TARGET_SIZE) return;
        inFlightOrQueued.incrementAndGet();
        bgHandler.post(() -> {
            try {
                View row = bgInflater.inflate(R.layout.item_chat, parentForLayoutParams, false);
                queue.offer(row);
            } catch (Throwable ignored) {
                // Falls back silently — onCreateViewHolder just inflates normally.
            } finally {
                inFlightOrQueued.decrementAndGet();
            }
        });
    }

    /** Returns a pre-inflated row, or null if the pool hasn't produced one yet. */
    View poll() {
        return queue.poll();
    }

    void stop() {
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            bgHandler = null;
            bgInflater = null;
        }
        queue.clear();
        inFlightOrQueued.set(0);
    }
}
