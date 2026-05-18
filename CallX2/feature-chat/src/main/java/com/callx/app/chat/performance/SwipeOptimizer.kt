package com.callx.app.chat.performance

import android.view.View
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator

/**
 * SwipeOptimizer — Performance utilities for lag-free swipe-to-reply.
 *
 * Key principles:
 *   • ONLY translationX during swipe — never requestLayout()
 *   • Disable RecyclerView change animations (prevents flicker)
 *   • Spring-back uses DynamicAnimation (hardware-accelerated)
 */
object SwipeOptimizer {

    fun setTranslationXSafe(view: View, dx: Float) {
        view.translationX = dx
    }

    fun springBack(view: View) {
        val currentTx = view.translationX
        if (Math.abs(currentTx) < 0.5f) {
            view.translationX = 0f
            return
        }
        SpringAnimation(view, DynamicAnimation.TRANSLATION_X, 0f).apply {
            spring.stiffness = SpringForce.STIFFNESS_MEDIUM
            spring.dampingRatio = 0.7f
            setStartVelocity(-currentTx * 2f)
            start()
        }
    }

    fun disableChangeAnimations(rv: RecyclerView) {
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    fun enableHardwareLayer(v: View) {
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    fun clearHardwareLayer(v: View) {
        v.setLayerType(View.LAYER_TYPE_NONE, null)
    }
}
