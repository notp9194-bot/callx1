package com.callx.app.chat.gesture

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.callx.app.chat.performance.SwipeOptimizer
import com.callx.app.models.Message

/**
 * SwipeReplyHandler — WhatsApp/Telegram-grade swipe-to-reply gesture engine.
 *
 * • Uses ItemTouchHelper.Callback for reliable gesture detection
 * • translationX ONLY — zero layout passes during swipe
 * • Rubber-band non-linear resistance: drag feels natural
 * • Debounce + fling guard: no accidental triggers
 * • RTL support: direction flips for right-to-left layouts
 */
class SwipeReplyHandler(
    private val messages: List<Message>,
    private val currentUid: String,
    private val listener: OnSwipeReplyListener
) : ItemTouchHelper.Callback() {

    companion object {
        var ENABLE_SWIPE_REPLY = true
        var ENABLE_HAPTICS = true
        var ENABLE_SOUND = false

        private const val TRIGGER_RATIO  = 0.18f
        private const val MIN_TRIGGER_DP = 72f
        private const val MAX_DRAG_RATIO = 0.30f
        private const val ICON_APPEAR_DP = 20f
        private const val VELOCITY_GUARD = 3500f
        private const val DEBOUNCE_MS    = 350L
        private const val ICON_SIZE_DP   = 36
    }

    interface OnSwipeReplyListener {
        fun onSwipeReply(message: Message)
    }

    private var triggered = false
    private var lastTriggerTime = 0L
    private var hapticFired = false
    private var currentSwipeDx = 0f
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var replyIcon: Drawable? = null
    private var density = 0f

    override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
        if (!ENABLE_SWIPE_REPLY) return 0
        if (density == 0f) density = rv.context.resources.displayMetrics.density
        if (replyIcon == null) {
            replyIcon = ContextCompat.getDrawable(rv.context, com.callx.app.chat.R.drawable.ic_reply)
        }
        return makeMovementFlags(0, ItemTouchHelper.START or ItemTouchHelper.END)
    }

    override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

    override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 2.0f
    override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
    override fun getSwipeVelocityThreshold(defaultValue: Float) = 0f

    override fun onChildDraw(
        c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
        dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
    ) {
        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            return
        }

        val view = vh.itemView
        val maxDrag = view.width * MAX_DRAG_RATIO
        val triggerThreshold = maxOf(view.width * TRIGGER_RATIO, MIN_TRIGGER_DP * density)
        val absDx = Math.abs(dX)
        val resistedDx = dX * (1f - absDx / (2f * maxDrag))
        val clampedDx = if (dX > 0) minOf(resistedDx, maxDrag) else maxOf(resistedDx, -maxDrag)

        currentSwipeDx = clampedDx
        SwipeOptimizer.setTranslationXSafe(view, clampedDx)

        // Draw reply icon
        val absResisted = Math.abs(clampedDx)
        val iconAppearThreshold = ICON_APPEAR_DP * density
        if (absResisted > iconAppearThreshold) {
            drawReplyIcon(c, view, clampedDx, absResisted, maxDrag)
        }

        // Trigger reply
        if (absDx >= triggerThreshold && !triggered) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTime > DEBOUNCE_MS) {
                triggered = true
                lastTriggerTime = now
                triggerHaptic(view)
                val pos = vh.bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt() && pos < messages.size && pos >= 0) {
                    listener.onSwipeReply(messages[pos])
                }
            }
        }
        if (absDx < triggerThreshold * 0.5f) triggered = false
    }

    private fun drawReplyIcon(c: Canvas, view: View, dx: Float, absDx: Float, maxDrag: Float) {
        val icon = replyIcon ?: return
        val iconSize = (ICON_SIZE_DP * density).toInt()
        val alpha = ((absDx / maxDrag) * 255).toInt().coerceIn(0, 255)
        icon.alpha = alpha

        val cx = if (dx > 0) view.left + absDx * 0.4f else view.right - absDx * 0.4f
        val cy = view.top + view.height / 2f
        val half = iconSize / 2
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.draw(c)
    }

    override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
        triggered = false
        hapticFired = false
        SwipeOptimizer.springBack(vh.itemView)
    }

    private fun triggerHaptic(view: View) {
        if (!ENABLE_HAPTICS || hapticFired) return
        hapticFired = true
        try {
            val vib = view.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vib.vibrate(30)
            }
        } catch (_: Exception) {}
    }
}
