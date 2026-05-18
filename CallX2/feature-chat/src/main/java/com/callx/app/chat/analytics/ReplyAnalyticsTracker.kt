package com.callx.app.chat.analytics

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ReplyAnalyticsTracker — Lightweight in-memory analytics for swipe-to-reply.
 */
class ReplyAnalyticsTracker private constructor() {

    private val swipeAttempts  = AtomicInteger(0)
    private val triggerSuccess = AtomicInteger(0)
    private val undoCount      = AtomicInteger(0)
    private val totalSwipePixels = AtomicLong(0)

    companion object {
        private const val TAG = "ReplyAnalytics"
        val get by lazy { ReplyAnalyticsTracker() }
    }

    fun onSwipeAttempt(distancePx: Float) {
        swipeAttempts.incrementAndGet()
        totalSwipePixels.addAndGet(Math.abs(distancePx).toLong())
    }

    fun onSwipeTriggered() {
        triggerSuccess.incrementAndGet()
        logStats()
    }

    fun onUndoUsed() { undoCount.incrementAndGet() }

    fun getSuccessRate(): Float {
        val attempts = swipeAttempts.get()
        return if (attempts == 0) 0f else triggerSuccess.get().toFloat() / attempts
    }

    fun getAvgSwipeDistance(): Float {
        val attempts = swipeAttempts.get()
        return if (attempts == 0) 0f else totalSwipePixels.get().toFloat() / attempts
    }

    fun getSwipeAttempts() = swipeAttempts.get()
    fun getTriggerSuccess() = triggerSuccess.get()
    fun getUndoCount() = undoCount.get()

    private fun logStats() {
        Log.d(TAG, "Reply stats → attempts=${swipeAttempts.get()} " +
            "success=${triggerSuccess.get()} rate=${"%.0f".format(getSuccessRate() * 100)}% " +
            "undos=${undoCount.get()} avgDist=${"%.0f".format(getAvgSwipeDistance())}px")
    }

    fun reset() {
        swipeAttempts.set(0)
        triggerSuccess.set(0)
        undoCount.set(0)
        totalSwipePixels.set(0)
    }
}
