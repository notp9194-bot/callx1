package com.callx.app.splash

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View

/**
 * Splash ka pura-black background + ambient neon swirl light-streaks —
 * screenshot ke left blue/purple aur right pink/orange glow arcs jaisa.
 */
class SplashBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private class Streak(
        val colors: IntArray,
        val cxFrac: Float, val cyFrac: Float,
        val radiusFrac: Float,
        val startAngle: Float, val sweep: Float,
        val strokeFrac: Float, val alpha: Int
    )

    private val streaks = listOf(
        // left side — blue / purple swirl
        Streak(intArrayOf(Color.parseColor("#3D5AFE"), Color.parseColor("#8B3DFF"), Color.parseColor("#3D5AFE")), -0.28f, 0.32f, 0.80f, 195f, 130f, 0.010f, 210),
        Streak(intArrayOf(Color.parseColor("#5B6BFF"), Color.parseColor("#B23DFF"), Color.parseColor("#5B6BFF")), -0.22f, 0.55f, 1.00f, 188f, 108f, 0.006f, 170),
        Streak(intArrayOf(Color.parseColor("#2E3FE0"), Color.parseColor("#6A2FE0"), Color.parseColor("#2E3FE0")), -0.34f, 0.16f, 0.55f, 205f, 120f, 0.015f, 140),
        // right side — pink / orange swirl
        Streak(intArrayOf(Color.parseColor("#FF3D9E"), Color.parseColor("#FF8A3D"), Color.parseColor("#FF3D9E")), 1.18f, 0.40f, 0.92f, 318f, 140f, 0.013f, 220),
        Streak(intArrayOf(Color.parseColor("#FF4D6A"), Color.parseColor("#FFB23D"), Color.parseColor("#FF4D6A")), 1.25f, 0.62f, 1.10f, 328f, 118f, 0.007f, 180),
        Streak(intArrayOf(Color.parseColor("#FF2E7A"), Color.parseColor("#FF6A2F"), Color.parseColor("#FF2E7A")), 1.12f, 0.20f, 0.58f, 298f, 132f, 0.017f, 150)
    )

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val base = if (w > h) w else h

        for (s in streaks) {
            val cx = w * s.cxFrac
            val cy = h * s.cyFrac
            val r = base * s.radiusFrac
            val rect = RectF(cx - r, cy - r, cx + r, cy + r)

            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = w * s.strokeFrac
                strokeCap = Paint.Cap.ROUND
                shader = SweepGradient(cx, cy, s.colors, null)
                maskFilter = BlurMaskFilter(w * 0.022f, BlurMaskFilter.Blur.NORMAL)
                alpha = s.alpha
            }
            canvas.drawArc(rect, s.startAngle, s.sweep, false, glow)

            // thin bright core line on top, for a crisp neon edge
            val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = w * s.strokeFrac * 0.22f
                strokeCap = Paint.Cap.ROUND
                shader = SweepGradient(cx, cy, s.colors, null)
                alpha = (s.alpha * 0.85f).toInt()
            }
            canvas.drawArc(rect, s.startAngle, s.sweep, false, core)
        }
    }
}
