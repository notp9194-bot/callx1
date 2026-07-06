package com.callx.app.conversation;

import android.os.Handler;
import android.os.Looper;

import androidx.recyclerview.widget.RecyclerView;

/**
 * v111 — RecycledViewPool Cross-Activity Sharing
 *
 * ChatActivity (1:1) and GroupChatActivity share identical ViewHolder types.
 * When the user navigates between chats the outgoing Activity deposits its
 * ViewHolders into this pool; the incoming Activity draws from it immediately —
 * first-frame layout requires zero re-inflation.
 *
 * ── Memory safety (no Activity Context leak) ─────────────────────────────────
 * ViewHolders hold itemViews; itemViews hold a reference to the Activity
 * Context they were inflated with.  A process-lifetime static pool that never
 * clears itself would keep destroyed Activities in memory indefinitely.
 *
 * Solution: when the last chat screen calls release(), schedule pool.clear()
 * after CLEAR_DELAY_MS (2 s).  If a new chat opens within that window, the
 * clear is cancelled and the pool is reused.  If no chat opens in time the
 * clear fires, freeing all ViewHolder/Context references.
 *
 * A→B navigation timeline:
 *   ActivityA.onDestroy()  → release() → schedules clear in 2 s
 *   ActivityB.onCreate()   → acquire() → cancels clear, uses pool  ✓  (0 inflation)
 *
 * User leaves chat entirely:
 *   ChatActivity.onDestroy() → release() → clear fires after 2 s  ✓  (no leak)
 *
 * ── View-type constants (must match MessagePagingAdapter.getItemViewType) ─────
 *   TYPE_SENT              (1)  → 25
 *   TYPE_RECEIVED          (2)  → 25
 *   TYPE_STATUS_SEEN       (3)  → 6
 *   TYPE_REEL_SEEN         (4)  → 6
 *   TYPE_CALL_ENTRY        (5)  → 6
 *   TYPE_CANVAS_SENT      (11)  → 25  ← v111: was 18/10, bumped to 25
 *   TYPE_CANVAS_RECEIVED  (12)  → 25  ← v111: was 18/10, bumped to 25
 *   Rare types (6–10)           → 4
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────────
 *
 *   In setupPagingRecyclerView() / setupRecyclerView():
 *     // REMOVE the old per-activity pool block, REPLACE with:
 *     binding.rvMessages.setRecycledViewPool(SharedChatRecycledViewPool.acquire());
 *
 *   In onDestroy() (both activities):
 *     SharedChatRecycledViewPool.release();
 *
 * Thread-safety: acquire/release are Activity-lifecycle calls — main thread only.
 */
public final class SharedChatRecycledViewPool {

    // ── View-type constants — must match MessagePagingAdapter exactly ─────────
    private static final int TYPE_SENT                   = 1;
    private static final int TYPE_RECEIVED               = 2;
    private static final int TYPE_STATUS_SEEN            = 3;
    private static final int TYPE_REEL_SEEN              = 4;
    private static final int TYPE_CALL_ENTRY             = 5;
    private static final int TYPE_HIDDEN                 = 6;
    private static final int TYPE_DATE_SEPARATOR         = 7;
    private static final int TYPE_VIEW_ONCE_SENT         = 8;
    private static final int TYPE_VIEW_ONCE_EXPIRED      = 9;
    private static final int TYPE_VIEW_ONCE_SENT_WAITING = 10;
    private static final int TYPE_CANVAS_SENT            = 11;
    private static final int TYPE_CANVAS_RECEIVED        = 12;

    /** After the last chat closes, wait this long before clearing pooled views. */
    private static final long CLEAR_DELAY_MS = 2_000L;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /** Created once, never nulled — contents are cleared on idle, not the object. */
    private static RecyclerView.RecycledViewPool sPool;

    /** How many chat screens currently hold a reference to the pool. */
    private static int sRefCount = 0;

    /** Pending deferred clear, cancelled by the next acquire(). */
    private static Runnable sClearRunnable;

    private SharedChatRecycledViewPool() {}

    /**
     * Call in setupPagingRecyclerView() / setupRecyclerView() — main thread only.
     * Returns the shared pool, cancelling any pending clear scheduled by a
     * previous release() so a rapid A→B transition reuses pooled ViewHolders.
     */
    public static RecyclerView.RecycledViewPool acquire() {
        if (sClearRunnable != null) {
            sMainHandler.removeCallbacks(sClearRunnable);
            sClearRunnable = null;
        }
        if (sPool == null) {
            sPool = new RecyclerView.RecycledViewPool();
            configurePool(sPool);
        }
        sRefCount++;
        return sPool;
    }

    /**
     * Call in onDestroy() of ChatActivity and GroupChatActivity — main thread only.
     * When refCount reaches zero, schedules a deferred pool.clear() so that
     * ViewHolder/Context references are freed if no new chat opens within
     * CLEAR_DELAY_MS.
     */
    public static void release() {
        if (sRefCount > 0) sRefCount--;
        if (sRefCount == 0 && sPool != null) {
            sClearRunnable = () -> {
                if (sRefCount == 0 && sPool != null) {
                    sPool.clear();
                }
                sClearRunnable = null;
            };
            sMainHandler.postDelayed(sClearRunnable, CLEAR_DELAY_MS);
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static void configurePool(RecyclerView.RecycledViewPool pool) {
        // v111: canvas types bumped from 18 (ChatActivity) / 10 (GroupChatActivity) → 25
        pool.setMaxRecycledViews(TYPE_CANVAS_SENT,            25);
        pool.setMaxRecycledViews(TYPE_CANVAS_RECEIVED,        25);
        pool.setMaxRecycledViews(TYPE_SENT,                   25);
        pool.setMaxRecycledViews(TYPE_RECEIVED,               25);
        pool.setMaxRecycledViews(TYPE_STATUS_SEEN,             6);
        pool.setMaxRecycledViews(TYPE_REEL_SEEN,               6);
        pool.setMaxRecycledViews(TYPE_CALL_ENTRY,              6);
        pool.setMaxRecycledViews(TYPE_HIDDEN,                  4);
        pool.setMaxRecycledViews(TYPE_DATE_SEPARATOR,          4);
        pool.setMaxRecycledViews(TYPE_VIEW_ONCE_SENT,          4);
        pool.setMaxRecycledViews(TYPE_VIEW_ONCE_EXPIRED,       4);
        pool.setMaxRecycledViews(TYPE_VIEW_ONCE_SENT_WAITING,  4);
    }
}
