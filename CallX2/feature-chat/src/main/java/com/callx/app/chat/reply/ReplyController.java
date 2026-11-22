package com.callx.app.chat.reply;

import android.os.Handler;
import android.os.Looper;

import com.callx.app.models.Message;

/**
 * ReplyController — Orchestrates the reply lifecycle.
 *
 * State machine:
 *   IDLE ──swipe──► ACTIVE ──cancel──► IDLE
 *
 * v819: the old PENDING_UNDO step (2-second "Replying to X… UNDO" snackbar) is gone. It was
 * already disabled (WhatsApp has no such step — swipe shows the reply bar + keyboard instantly),
 * so the state, timeout, snackbar callbacks and undo() were dead code.
 */
public class ReplyController {

    public enum State { IDLE, ACTIVE, NAVIGATING }

    public interface Callback {
        /** Called when a reply is activated (swipe, menu, or restore). */
        void onReplyActivated(Message message);
        /** Called when user cancels reply bar. */
        void onReplyCancelled();
        /** Called to scroll to original message. */
        void onNavigateToOriginal(String messageId);
    }

    private State   state   = State.IDLE;

    private final Callback          callback;
    private final Handler           handler;
    private final ReplyStateManager stateManager;
    private Runnable navigationResetRunnable;

    public ReplyController(Callback callback) {
        this.callback     = callback;
        this.handler      = new Handler(Looper.getMainLooper());
        this.stateManager = new ReplyStateManager();
    }

    /**
     * Called immediately after a successful swipe (or menu "Reply").
     * Always (re)targets the reply — also when one is already active, exactly like WhatsApp:
     * swiping ANOTHER bubble while a reply is showing simply retargets the reply bar.
     */
    public void onSwipeReply(Message message) {
        if (message == null) return;
        state = State.ACTIVE;
        stateManager.setActive(message);
        if (callback != null) callback.onReplyActivated(message);
    }

    /** User cancelled reply (X button on reply bar) or it was sent. */
    public void cancel() {
        state = State.IDLE;
        stateManager.clear();
        if (callback != null) callback.onReplyCancelled();
    }

    /** User tapped the reply preview (inside a bubble or on the reply bar) — go to original. */
    public void navigateToOriginal(String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        state = State.NAVIGATING;
        if (callback != null) callback.onNavigateToOriginal(messageId);
        // Reset navigation state after a tick — back to ACTIVE if a reply is still pending.
        if (navigationResetRunnable != null) handler.removeCallbacks(navigationResetRunnable);
        final State restore = stateManager.hasActive() ? State.ACTIVE : State.IDLE;
        navigationResetRunnable = () -> {
            if (state == State.NAVIGATING) state = restore;
            navigationResetRunnable = null;
        };
        handler.postDelayed(navigationResetRunnable, 1500);
    }

    /** Returns current active reply message (null if none). */
    public Message getReplyingTo() { return stateManager.getActive(); }

    public boolean isActive() { return state == State.ACTIVE; }
    public State getState()   { return state; }

    public void release() {
        if (navigationResetRunnable != null) {
            handler.removeCallbacks(navigationResetRunnable);
            navigationResetRunnable = null;
        }
        state = State.IDLE;
        stateManager.clear();
    }
}
