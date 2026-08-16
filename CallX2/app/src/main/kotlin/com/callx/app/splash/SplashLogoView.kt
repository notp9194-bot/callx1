package com.callx.app.splash

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Screenshot ke "Kali X" app-icon ko canvas pe redraw karta hai: rounded
 * gradient square (blue → purple → pink → orange) + neon glow halo + white
 * chat-bubble mark (open "C" ring, tail, 3 dots, notification dot).
 *
 * Purely code-drawn — koi PNG/vector asset ki zaroorat nahi, isliye kisi
 * bhi size pe screenshot jesi hi sharpness/brightness milti hai.
 */
class SplashLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val gradColors = intArrayOf(
        Color.parseColor("#4A56FF"),
        Color.parseColor("#9B4DFF"),
        Color.parseColor("#FF3D9E"),
        Color.parseColor("#FF8A3D")
    )
    private val gradPositions = floatArrayOf(0f, 0.38f, 0.68f, 1f)

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 255, 255, 255)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    init {
        // BlurMaskFilter needs an unaccelerated canvas.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val cy = h / 2f
        val corner = w * 0.28f

        val shader = LinearGradient(0f, 0f, w, h, gradColors, gradPositions, Shader.TileMode.CLAMP)

        // ── outer neon glow halo ────────────────────────────────────
        glowPaint.shader = shader
        glowPaint.maskFilter = BlurMaskFilter(w * 0.14f, BlurMaskFilter.Blur.NORMAL)
        glowPaint.alpha = 200
        val glowRect = RectF(w * 0.02f, h * 0.02f, w * 0.98f, h * 0.98f)
        canvas.drawRoundRect(glowRect, corner, corner, glowPaint)

        // ── main rounded gradient square ────────────────────────────
        bgPaint.shader = shader
        val rect = RectF(w * 0.09f, h * 0.09f, w * 0.91f, h * 0.91f)
        canvas.drawRoundRect(rect, corner, corner, bgPaint)

        // subtle light inner border
        borderPaint.strokeWidth = w * 0.012f
        canvas.drawRoundRect(rect, corner, corner, borderPaint)

        // ── white chat-bubble mark ──────────────────────────────────
        ringPaint.strokeWidth = w * 0.075f
        ringPaint.maskFilter = BlurMaskFilter(w * 0.018f, BlurMaskFilter.Blur.SOLID)
        val arcRect = RectF(cx - w * 0.22f, cy - w * 0.23f, cx + w * 0.22f, cy + w * 0.19f)
        canvas.drawArc(arcRect, 32f, 292f, false, ringPaint)

        // speech-bubble tail, bottom-left
        val tail = Path().apply {
            moveTo(cx - w * 0.21f, cy + h * 0.09f)
            lineTo(cx - w * 0.29f, cy + h * 0.19f)
            lineTo(cx - w * 0.09f, cy + h * 0.11f)
            close()
        }
        canvas.drawPath(tail, whitePaint)

        // 3 dots inside the bubble
        val dotR = w * 0.022f
        val dotY = cy - h * 0.005f
        canvas.drawCircle(cx - w * 0.085f, dotY, dotR, whitePaint)
        canvas.drawCircle(cx, dotY, dotR, whitePaint)
        canvas.drawCircle(cx + w * 0.085f, dotY, dotR, whitePaint)

        // small notification dot, top-right of the ring
        canvas.drawCircle(cx + w * 0.205f, cy - w * 0.205f, w * 0.036f, whitePaint)
    }
}
