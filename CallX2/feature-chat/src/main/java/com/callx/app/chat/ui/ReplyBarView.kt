package com.callx.app.chat.ui

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.bumptech.glide.Glide
import com.callx.app.chat.R
import com.callx.app.chat.reply.ReplyStateManager
import com.callx.app.models.Message

/**
 * ReplyBarView — Slide-up reply bar shown above the input field.
 */
class ReplyBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private var tvSenderName: TextView? = null
    private var tvContent: TextView? = null
    private var ivThumbnail: ImageView? = null
    private var btnCancel: ImageButton? = null
    private var onCancelListener: Runnable? = null

    init {
        orientation = HORIZONTAL
        visibility = View.GONE
    }

    fun bind(message: Message?, stateManager: ReplyStateManager, currentUid: String?) {
        if (message == null) { hide(); return }
        tvSenderName?.text = stateManager.getDisplaySenderName(currentUid)
        tvContent?.text = stateManager.getReplyPreviewText()

        val thumbUrl = stateManager.getReplyThumbnailUrl()
        if (ivThumbnail != null) {
            if (!thumbUrl.isNullOrEmpty()) {
                ivThumbnail!!.visibility = View.VISIBLE
                Glide.with(context).load(thumbUrl).centerCrop().into(ivThumbnail!!)
            } else {
                ivThumbnail!!.visibility = View.GONE
            }
        }
        btnCancel?.setOnClickListener { onCancelListener?.run(); hide() }
        show()
    }

    fun setOnCancelListener(listener: Runnable) { onCancelListener = listener }

    fun show() {
        if (visibility == View.VISIBLE) return
        visibility = View.VISIBLE
        translationY = height.toFloat()
        animate().translationY(0f).setDuration(200)
            .setInterpolator(FastOutSlowInInterpolator()).start()
    }

    fun hide() {
        if (visibility != View.VISIBLE) return
        animate().translationY(height.toFloat()).setDuration(150)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction { visibility = View.GONE }.start()
    }
}
