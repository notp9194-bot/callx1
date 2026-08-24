package com.callx.app.chatlist;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import com.callx.app.chat.R;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ContactSheetPrewarmPool — ultra optimization for the "tap an avatar in
 * Chats" contact bottom sheet (see ChatsFragment.showContactBottomSheet()).
 *
 * ROOT COST BEING REMOVED
 * ────────────────────────
 * bottom_sheet_contact_call.xml is a deep layout (avatar row + name/status +
 * message/voice/video/history action row + 3 social platform rows, each with
 * an icon, a follower/subscriber TextView, and a follow/subscribe Button —
 * 100+ views total once inflated). Previously this was inflated fully
 * synchronously, on the main thread, on the exact frame the user tapped the
 * avatar — the same class of jank ChatRowPrewarmPool already fixed for chat
 * list rows (item_chat), just now happening on tap instead of on scroll.
 *
 * FIX — identical technique to ChatRowPrewarmPool: a dedicated background
 * HandlerThread holds a context-cloned LayoutInflater and keeps a couple of
 * ready-to-bind View instances parked in a lock-free queue. By the time the
 * user actually taps an avatar, showContactBottomSheet() just polls this
 * queue — an O(1) operation — instead of paying the real inflate cost. The
 * pool refills itself in the background right after every poll so the next
 * tap (a different contact, or the same one again) is just as instant.
 *
 * Small TARGET_SIZE on purpose: unlike chat rows (up to 25 alive at once via
 * RecycledViewPool), only one contact sheet is ever open at a time — 2 warm
 * spares comfortably covers back-to-back taps without over-inflating views
 * that would otherwise just sit unused.
 *
 * SAFETY NOTES — same as ChatRowPrewarmPool:
 *  • inflate(id, null, false): attachToRoot=false, parent=null matches the
 *    original synchronous call site exactly (BottomSheetDialog.setContentView
 *    doesn't need a RecyclerView.LayoutParams-typed root), so behavior is
 *    unchanged versus the previous inline inflate.
 *  • The inflater is cloned once per Context via cloneInContext — no shared
 *    mutable LayoutInflater state crosses threads.
 *  • start()/stop() are idempotent and safe to call from onViewCreated /
 *    onDestroyView respectively.
 *  • Every polled view still goes through the exact same findViewById +
 *    rebind logic in showContactBottomSheet() as before, so there's no
 *    stale-state risk from reuse — this pool never reuses a view that has
 *    already been shown; it only pre-builds fresh ones ahead of time.
 */
final class ContactSheetPrewarmPool {

    private static final int TARGET_SIZE = 2;

    private final ConcurrentLinkedQueue<View> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inFlightOrQueued = new AtomicInteger(0);

    private HandlerThread thread;
    private Handler bgHandler;
    private LayoutInflater bgInflater;

    void start(Context appContext) {
        if (thread != null) return; // already running
        thread = new HandlerThread("ContactSheetPrewarm");
        thread.start();
        bgHandler = new Handler(thread.getLooper());
        bgInflater = LayoutInflater.from(appContext).cloneInContext(appContext);

        for (int i = 0; i < TARGET_SIZE; i++) {
            scheduleOneInflate();
        }
    }

    private void scheduleOneInflate() {
        if (bgHandler == null || inFlightOrQueued.get() >= TARGET_SIZE) return;
        inFlightOrQueued.incrementAndGet();
        bgHandler.post(() -> {
            try {
                View sheet = bgInflater.inflate(R.layout.bottom_sheet_contact_call, null, false);
                queue.offer(sheet);
            } catch (Throwable ignored) {
                // Falls back silently — showContactBottomSheet() just inflates normally.
            } finally {
                inFlightOrQueued.decrementAndGet();
            }
        });
    }

    /** Returns a pre-inflated sheet view, or null if the pool hasn't produced one yet
     *  (falls back to a normal synchronous inflate at the call site). Immediately
     *  schedules a background refill so the pool stays warm for the next tap. */
    View poll() {
        View v = queue.poll();
        scheduleOneInflate();
        return v;
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
