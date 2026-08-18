package com.callx.app.music;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import androidx.annotation.Nullable;
import java.util.Random;

public class SoundWaveformView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] heights = new float[12];
    private final float[] targets = new float[12];
    private ValueAnimator animator;
    private boolean isPlaying = false;
    private final Random random = new Random();

    public SoundWaveformView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(8f);
        initAnimator();
    }

    private void initAnimator() {
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(400);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(animation -> {
            for (int i = 0; i < heights.length; i++) {
                if (Math.abs(heights[i] - targets[i]) < 0.1f) {
                    targets[i] = isPlaying ? 0.3f + random.nextFloat() * 0.7f : 0.2f + random.nextFloat() * 0.2f;
                }
                heights[i] += (targets[i] - heights[i]) * 0.2f;
            }
            invalidate();
        });
        animator.start();
    }

    public void setPlaying(boolean playing) {
        this.isPlaying = playing;
        if (playing) {
            animator.setDuration(200);
        } else {
            animator.setDuration(600);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float spacing = w / (heights.length + 1);
        
        for (int i = 0; i < heights.length; i++) {
            float x = spacing * (i + 1);
            float barHeight = h * heights[i];
            canvas.drawLine(x, (h - barHeight) / 2f, x, (h + barHeight) / 2f, paint);
        }
    }
}
