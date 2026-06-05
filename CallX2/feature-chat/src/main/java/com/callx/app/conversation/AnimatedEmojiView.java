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

public class AnimatedEmojiView extends View {

    private final Paint emojiPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String emoji = "\uD83E\uDD14"; // 🤔

    // Use distinct names — avoid clash with View.scaleX / View.scaleY
    private float emojiScaleX = 1f;
    private float emojiScaleY = 1f;
    private float emojiTranslateY = 0f;

    private AnimatorSet entrySet;
    private ValueAnimator idleAnimator;
    private boolean pendingStart = false;

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
        emojiPaint.setTextAlign(Paint.Align.CENTER);
        emojiPaint.setTextSize(120f); // default, updated in onSizeChanged
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (h > 0) {
            emojiPaint.setTextSize(h * 0.65f);
            // If startAnimation() was called before layout
            if (pendingStart) {
                pendingStart = false;
                playEntryAnimation();
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() == 0 || getHeight() == 0) return;

        int cx = getWidth() / 2;
        int cy = getHeight() / 2;

        canvas.save();
        canvas.translate(cx, cy + emojiTranslateY);
        canvas.scale(emojiScaleX, emojiScaleY);
        Paint.FontMetrics fm = emojiPaint.getFontMetrics();
        float textY = -(fm.ascent + fm.descent) / 2f;
        canvas.drawText(emoji, 0, textY, emojiPaint);
        canvas.restore();
    }

    public void startAnimation() {
        cancelAll();
        emojiScaleX = 1f;
        emojiScaleY = 1f;
        emojiTranslateY = 0f;

        if (getHeight() == 0) {
            // Layout not done yet — defer
            pendingStart = true;
            return;
        }
        playEntryAnimation();
    }

    private void playEntryAnimation() {
        float startY = getHeight() * 0.6f;
        float midY   = -getHeight() * 0.04f;

        // Phase 1: slide up
        ValueAnimator slideUp = ValueAnimator.ofFloat(startY, midY);
        slideUp.setDuration(300);
        slideUp.setInterpolator(new DecelerateInterpolator(1.8f));
        slideUp.addUpdateListener(a -> {
            emojiTranslateY = (float) a.getAnimatedValue();
            invalidate();
        });

        // Phase 2: squish
        ValueAnimator squishX = ValueAnimator.ofFloat(1f, 1.22f, 1f);
        squishX.setDuration(280);
        squishX.addUpdateListener(a -> { emojiScaleX = (float) a.getAnimatedValue(); invalidate(); });

        ValueAnimator squishY = ValueAnimator.ofFloat(1f, 0.75f, 1f);
        squishY.setDuration(280);
        squishY.addUpdateListener(a -> { emojiScaleY = (float) a.getAnimatedValue(); invalidate(); });

        // Phase 3: bounce settle
        ValueAnimator bounce = ValueAnimator.ofFloat(midY, 0f);
        bounce.setDuration(260);
        bounce.setInterpolator(new OvershootInterpolator(2.2f));
        bounce.addUpdateListener(a -> {
            emojiTranslateY = (float) a.getAnimatedValue();
            invalidate();
        });

        entrySet = new AnimatorSet();
        entrySet.play(slideUp).before(squishX);
        entrySet.play(squishX).with(squishY);
        entrySet.play(bounce).after(squishX);
        entrySet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                emojiTranslateY = 0f;
                emojiScaleX = 1f;
                emojiScaleY = 1f;
                invalidate();
                startIdleLoop();
            }
        });
        entrySet.start();
    }

    private void startIdleLoop() {
        idleAnimator = ValueAnimator.ofFloat(0f, -10f, 0f, 4f, 0f);
        idleAnimator.setDuration(3000);
        idleAnimator.setRepeatCount(ValueAnimator.INFINITE);
        idleAnimator.setRepeatMode(ValueAnimator.RESTART);
        idleAnimator.addUpdateListener(a -> {
            emojiTranslateY = (float) a.getAnimatedValue();
            invalidate();
        });
        idleAnimator.start();
    }

    public void stopAnimation() {
        cancelAll();
        pendingStart = false;
        emojiTranslateY = 0f;
        emojiScaleX = 1f;
        emojiScaleY = 1f;
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
