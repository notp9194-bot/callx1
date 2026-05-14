package com.callx.app.chat.reply;

import android.os.Handler;
import android.os.Looper;

import com.callx.app.models.Message;

/**
 * ReplyController — Orchestrates the reply lifecycle.
 *
 * State machine:
 *   IDLE ──swipe──► PENDING_UNDO ──timeout──► ACTIVE ──cancel──► IDLE
 *                        │
 *                       undo
 *                        │
 *                       IDLE
 */
public class ReplyController {

    public enum State { IDLE, PENDING_UNDO, ACTIVE, NAVIGATING }

    public interface Callback {
        /** Called when reply is confirmed (undo window expired). */
        void onReplyActivated(Message message);
        /** Called when user cancels reply bar. */
        void onReplyCancelled();
        /** Called during undo window (show snackbar). */
        void onPendingUndo(Message message, Runnable cancelAction);
        /** Called to scroll to original message. */
        void onNavigateToOriginal(String messageId);
        /** Called when undo confirmed — state reset to IDLE. */
        void onUndoConfirmed();
    }

    // Feature flags
    public static boolean ENABLE_UNDO = true;
    private static final long UNDO_TIMEOUT_MS = 2000L;

    private State   state   = State.IDLE;
    private Message pending = null;

    private final Callback         callback;
    private final Handler          handler;
    private final ReplyStateManager stateManager;
    private Runnable undoTimeoutRunnable;

    public ReplyController(Callback callback) {
        this.callback     = callback;
        this.handler      = new Handler(Looper.getMainLooper());
        this.stateManager = new ReplyStateManager();
    }

    /**
     * Called immediately after successful swipe gesture.
     * If undo is enabled, enters PENDING_UNDO state (snackbar shown).
     * If undo is disabled, activates reply immediately.
     */
    public void onSwipeReply(Message message) {
        if (state == State.ACTIVE || state == State.PENDING_UNDO) return;
        pending = message;

        if (ENABLE_UNDO) {
            state = State.PENDING_UNDO;
            scheduleUndoTimeout(message);
            Runnable cancelAction = this::undo;
            if (callback != null) callback.onPendingUndo(message, cancelAction);
        } else {
            activateReply(message);
        }
    }

    /**
     * User confirms reply (undo window expired or undo disabled).
     */
    private void activateReply(Message message) {
        cancelUndoTimeout();
        state = State.ACTIVE;
        stateManager.setActive(message);
        if (callback != null) callback.onReplyActivated(message);
    }

    /**
     * User tapped UNDO in snackbar, or performed reverse swipe.
     */
    public void undo() {
        if (state != State.PENDING_UNDO) return;
        cancelUndoTimeout();
        state   = State.IDLE;
        pending = null;
        stateManager.clear();
        if (callback != null) callback.onUndoConfirmed();
    }

    /**
     * User cancelled reply (X button on reply bar).
     */
    public void cancel() {
        cancelUndoTimeout();
        state   = State.IDLE;
        pending = null;
        stateManager.clear();
        if (callback != null) callback.onReplyCancelled();
    }

    /**
     * User tapped the reply preview inside a bubble — navigate to original.
     */
    public void navigateToOriginal(String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        state = State.NAVIGATING;
        if (callback != null) callback.onNavigateToOriginal(messageId);
        // Reset navigation state after a tick
        handler.postDelayed(() -> {
            if (state == State.NAVIGATING) state = State.IDLE;
        }, 1500);
    }

    /** Returns current active reply message (null if none). */
    public Message getReplyingTo() { return stateManager.getActive(); }

    public boolean isActive() { return state == State.ACTIVE; }
    public State getState()   { return state; }

    private void scheduleUndoTimeout(Message message) {
        cancelUndoTimeout();
        undoTimeoutRunnable = () -> {
            if (state == State.PENDING_UNDO) activateReply(message);
        };
        handler.postDelayed(undoTimeoutRunnable, UNDO_TIMEOUT_MS);
    }

    private void cancelUndoTimeout() {
        if (undoTimeoutRunnable != null) {
            handler.removeCallbacks(undoTimeoutRunnable);
            undoTimeoutRunnable = null;
        }
    }

    public void release() {
        cancelUndoTimeout();
        state   = State.IDLE;
        pending = null;
        stateManager.clear();
    }
}
