package com.callx.app.splash

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * TextView jiska text ek diagonal multi-color gradient (screenshot ke "X"
 * jaisa — blue → purple → pink → orange) se fill hota hai.
 *
 * onSizeChanged pe shader rebuild hota hai taaki text ke exact pixel
 * bounds ke hisaab se gradient stretch ho, screenshot jesi hi saturation
 * aur brightness ke sath.
 */
class GradientTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    var gradientColors: IntArray = intArrayOf(
        Color.parseColor("#4A56FF"),
        Color.parseColor("#9B4DFF"),
        Color.parseColor("#FF3D9E"),
        Color.parseColor("#FF8A3D")
    )
        set(value) {
            field = value
            rebuildShader()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader()
    }

    private fun rebuildShader() {
        if (width <= 0 || height <= 0) return
        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            gradientColors, null, Shader.TileMode.CLAMP
        )
        invalidate()
    }
}
