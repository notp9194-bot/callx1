package com.callx.app.conversation;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * Telegram-style animated emoji for empty chat state.
 * Shows 🤔 thinking emoji with:
 * 1. Entry: slide up + bounce overshoot
 * 2. Squish on land: scaleX wide, scaleY short
 * 3. Idle: gentle floating wobble loop
 */
public class AnimatedEmojiView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String emoji = "🤔";

    private float scaleX = 1f;
    private float scaleY = 1f;
    private float translateY = 0f;
    private float textSize;

    private AnimatorSet entrySet;
    private ValueAnimator idleAnimator;
    private boolean entryDone = false;

    public AnimatedEmojiView(Context context) {
        super(context);
        init();
    }

    public AnimatedEmojiView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AnimatedEmojiView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        // Emoji size = 60% of view height
        textSize = h * 0.6f;
        paint.setTextSize(textSize);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int cx = getWidth() / 2;
        int cy = getHeight() / 2;

        canvas.save();
        canvas.translate(cx + 0f, cy + translateY);
        canvas.scale(scaleX, scaleY);
        // Draw emoji centered
        Paint.FontMetrics fm = paint.getFontMetrics();
        float textY = -(fm.ascent + fm.descent) / 2f;
        canvas.drawText(emoji, 0, textY, paint);
        canvas.restore();
    }

    /**
     * Call this when the empty state becomes visible.
     * Plays entry animation then starts idle loop.
     */
    public void startAnimation() {
        cancelAll();
        entryDone = false;

        // Start offscreen below
        float startTranslate = getHeight() * 0.5f;
        translateY = startTranslate;
        scaleX = 1f;
        scaleY = 1f;
        invalidate();

        // Phase 1: Slide up with overshoot (300ms)
        ValueAnimator slideUp = ValueAnimator.ofFloat(startTranslate, -getHeight() * 0.05f);
        slideUp.setDuration(320);
        slideUp.setInterpolator(new DecelerateInterpolator(1.8f));
        slideUp.addUpdateListener(a -> {
            translateY = (float) a.getAnimatedValue();
            invalidate();
        });

        // Phase 2: Squish on land — scaleX wide, scaleY squish (120ms)
        ValueAnimator squishX = ValueAnimator.ofFloat(1f, 1.25f, 1f);
        squishX.setDuration(300);
        squishX.addUpdateListener(a -> { scaleX = (float) a.getAnimatedValue(); invalidate(); });

        ValueAnimator squishY = ValueAnimator.ofFloat(1f, 0.72f, 1f);
        squishY.setDuration(300);
        squishY.addUpdateListener(a -> { scaleY = (float) a.getAnimatedValue(); invalidate(); });

        // Phase 3: Bounce back up with overshoot (280ms)
        ValueAnimator bounceBack = ValueAnimator.ofFloat(-getHeight() * 0.05f, 0f);
        bounceBack.setDuration(280);
        bounceBack.setInterpolator(new OvershootInterpolator(2.5f));
        bounceBack.addUpdateListener(a -> {
            translateY = (float) a.getAnimatedValue();
            invalidate();
        });

        entrySet = new AnimatorSet();
        entrySet.play(slideUp).before(squishX);
        entrySet.play(squishX).with(squishY);
        entrySet.play(bounceBack).after(squishX);
        entrySet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                entryDone = true;
                translateY = 0f;
                scaleX = 1f;
                scaleY = 1f;
                invalidate();
                startIdleLoop();
            }
        });
        entrySet.start();
    }

    /** Gentle float up-down loop after entry */
    private void startIdleLoop() {
        idleAnimator = ValueAnimator.ofFloat(0f, -12f, 0f, 5f, 0f);
        idleAnimator.setDuration(2800);
        idleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        idleAnimator.setRepeatMode(ValueAnimator.RESTART);
        idleAnimator.setInterpolator(new DecelerateInterpolator());
        idleAnimator.addUpdateListener(a -> {
            translateY = (float) a.getAnimatedValue();
            invalidate();
        });
        idleAnimator.start();
    }

    public void stopAnimation() {
        cancelAll();
        translateY = 0f;
        scaleX = 1f;
        scaleY = 1f;
        invalidate();
    }

    private void cancelAll() {
        if (entrySet != null) { entrySet.cancel(); entrySet = null; }
        if (idleAnimator != null) { idleAnimator.cancel(); idleAnimator = null; }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelAll();
    }
}
