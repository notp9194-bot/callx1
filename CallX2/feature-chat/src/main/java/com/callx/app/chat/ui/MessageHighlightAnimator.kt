package com.callx.app.chat.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * MessageHighlightAnimator — Scrolls to and highlights the original message.
 */
object MessageHighlightAnimator {

    private const val HIGHLIGHT_COLOR_START = 0xFFFFEB3B.toInt()
    private const val HIGHLIGHT_COLOR_END   = 0x00FFEB3B
    private const val FLASH_DURATION_MS     = 1500L
    private const val SCROLL_SETTLE_DELAY   = 400L

    fun scrollAndHighlight(rv: RecyclerView, position: Int, fabBackBtn: View?) {
        if (position < 0 || rv.layoutManager == null) return

        fabBackBtn?.let {
            it.visibility = View.VISIBLE
            it.animate().alpha(1f).setDuration(200).start()
        }

        rv.smoothScrollToPosition(position)

        rv.postDelayed({
            val vh = rv.findViewHolderForAdapterPosition(position)
            if (vh != null) {
                flashHighlight(vh.itemView)
            } else {
                (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
                rv.postDelayed({
                    rv.findViewHolderForAdapterPosition(position)?.let { h ->
                        flashHighlight(h.itemView)
                    }
                }, 300)
            }
        }, SCROLL_SETTLE_DELAY)
    }

    fun flashHighlight(view: View) {
        view.setBackgroundColor(HIGHLIGHT_COLOR_START)
        ValueAnimator.ofObject(ArgbEvaluator(), HIGHLIGHT_COLOR_START, HIGHLIGHT_COLOR_END).apply {
            duration = FLASH_DURATION_MS
            addUpdateListener { view.setBackgroundColor(it.animatedValue as Int) }
            start()
        }
    }
}
