package com.callx.app.community.canvas;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;

/**
 * Feed loading placeholder: 3 post-card shaped skeletons (avatar, name/time, text lines,
 * engagement row) with a soft pulse. Real card (CommunityPostCanvasView) ki geometry follow karta
 * hai (4dp edge margin, 85% / 420dp max width, 12dp padding/radius) taaki data aane par layout
 * jump na kare. Pulse sirf tab chalta hai jab view visible + attached ho.
 */
public class CommunityFeedSkeletonView extends View {

    private static final int CARD_COUNT = 3;

    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private ValueAnimator pulse;
    private float pulseAlpha = 1f;

    public CommunityFeedSkeletonView(Context context) { this(context, null); }

    public CommunityFeedSkeletonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        cardPaint.setColor(ContextCompat.getColor(context, R.color.surface_card));
        cardPaint.setStyle(Paint.Style.FILL);
        blockPaint.setColor(ContextCompat.getColor(context, R.color.divider));
        blockPaint.setStyle(Paint.Style.FILL);
        setWillNotDraw(false);
        setClickable(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float w = getWidth();
        final float edge = 4 * density;
        final float cardW = Math.min(Math.min(w - edge, w * 0.85f), 420 * density);
        final float pad = 12 * density;
        final float radius = 12 * density;
        final float avatar = 40 * density;
        final float gap = 6 * density;
        final float cardH = pad + avatar + 12 * density          // header
                + 3 * 12 * density + 2 * 8 * density             // 3 text lines
                + 12 * density + 22 * density + pad;             // engagement row
        float top = 0f;

        blockPaint.setAlpha((int) (255 * pulseAlpha));
        for (int i = 0; i < CARD_COUNT && top < getHeight(); i++) {
            rect.set(edge, top, edge + cardW, top + cardH);
            canvas.drawRoundRect(rect, radius, radius, cardPaint);

            final float left = edge + pad;
            final float inner = cardW - 2 * pad;
            float y = top + pad;

            // avatar
            canvas.drawCircle(left + avatar / 2f, y + avatar / 2f, avatar / 2f, blockPaint);
            // name + time
            float tx = left + avatar + 10 * density;
            drawBar(canvas, tx, y + 6 * density, tx + inner * 0.38f, y + 18 * density);
            drawBar(canvas, tx, y + 24 * density, tx + inner * 0.22f, y + 34 * density);
            y += avatar + 12 * density;

            // text lines (last one shorter)
            for (int l = 0; l < 3; l++) {
                float right = left + (l == 2 ? inner * 0.6f : inner);
                drawBar(canvas, left, y, right, y + 12 * density);
                y += 12 * density + 8 * density;
            }
            y += 4 * density;

            // engagement row: 2 icons left + 1 right
            drawBar(canvas, left, y, left + 44 * density, y + 18 * density);
            drawBar(canvas, left + 64 * density, y, left + 108 * density, y + 18 * density);
            drawBar(canvas, left + inner - 22 * density, y, left + inner, y + 18 * density);

            top += cardH + gap;
        }
    }

    private void drawBar(Canvas c, float l, float t, float r, float b) {
        rect.set(l, t, r, b);
        float rad = (b - t) / 2f;
        c.drawRoundRect(rect, rad, rad, blockPaint);
    }

    // ── Pulse lifecycle ─────────────────────────────────────────────────────────

    private void startPulse() {
        if (pulse != null) return;
        pulse = ValueAnimator.ofFloat(0.45f, 1f);
        pulse.setDuration(800L);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setInterpolator(new LinearInterpolator());
        pulse.addUpdateListener(a -> {
            pulseAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) { pulse.cancel(); pulse = null; }
        pulseAlpha = 1f;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) startPulse(); else stopPulse();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) startPulse();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        super.onDetachedFromWindow();
    }
}
