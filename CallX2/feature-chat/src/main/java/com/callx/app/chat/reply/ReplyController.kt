package com.callx.app.chat.reply

import android.os.Handler
import android.os.Looper
import com.callx.app.models.Message

/**
 * ReplyController — Orchestrates the reply lifecycle.
 *
 * State machine:
 *   IDLE ──swipe──► PENDING_UNDO ──timeout──► ACTIVE ──cancel──► IDLE
 */
class ReplyController(private val callback: Callback) {

    enum class State { IDLE, PENDING_UNDO, ACTIVE, NAVIGATING }

    interface Callback {
        fun onReplyActivated(message: Message)
        fun onReplyCancelled()
        fun onPendingUndo(message: Message, cancelAction: Runnable)
        fun onNavigateToOriginal(messageId: String)
        fun onUndoConfirmed()
    }

    companion object {
        var ENABLE_UNDO = true
        private const val UNDO_TIMEOUT_MS = 2000L
    }

    private var state = State.IDLE
    private var pending: Message? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stateManager = ReplyStateManager()
    private var undoTimeoutRunnable: Runnable? = null

    fun onSwipeReply(message: Message) {
        if (state == State.ACTIVE || state == State.PENDING_UNDO) return
        pending = message
        if (ENABLE_UNDO) {
            state = State.PENDING_UNDO
            scheduleUndoTimeout(message)
            callback.onPendingUndo(message) { undo() }
        } else {
            activateReply(message)
        }
    }

    private fun activateReply(message: Message) {
        cancelUndoTimeout()
        state = State.ACTIVE
        stateManager.setActive(message)
        callback.onReplyActivated(message)
    }

    fun undo() {
        if (state != State.PENDING_UNDO) return
        cancelUndoTimeout()
        state = State.IDLE
        pending = null
        stateManager.clear()
        callback.onUndoConfirmed()
    }

    fun cancelReply() {
        cancelUndoTimeout()
        state = State.IDLE
        pending = null
        stateManager.clear()
        callback.onReplyCancelled()
    }

    fun navigateToOriginal(messageId: String) {
        state = State.NAVIGATING
        callback.onNavigateToOriginal(messageId)
    }

    fun onReturnFromNavigation() {
        if (state == State.NAVIGATING) state = State.ACTIVE
    }

    fun getState(): State = state
    fun getActiveMessage(): Message? = stateManager.getActive()
    fun getStateManager(): ReplyStateManager = stateManager

    private fun scheduleUndoTimeout(message: Message) {
        cancelUndoTimeout()
        undoTimeoutRunnable = Runnable { activateReply(message) }
        handler.postDelayed(undoTimeoutRunnable!!, UNDO_TIMEOUT_MS)
    }

    private fun cancelUndoTimeout() {
        undoTimeoutRunnable?.let { handler.removeCallbacks(it) }
        undoTimeoutRunnable = null
    }
}
