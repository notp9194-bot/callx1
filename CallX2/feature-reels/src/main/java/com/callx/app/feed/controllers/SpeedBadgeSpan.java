package com.callx.app.feed.controllers;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Small rounded "1.5x" pill drawn inline in the music label. Replaces the old
 * plain " · 1.5x" suffix, which read like part of the song name and implied it
 * was the speed the creator picked — it is only the speed the fingerprint
 * match detected relative to the original sound.
 */
final class SpeedBadgeSpan extends ReplacementSpan {
    private final String label;
    private final RectF rect = new RectF();
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);

    SpeedBadgeSpan(String label) {
        this.label = label;
        bg.setColor(0x33FFFFFF);
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                       @Nullable Paint.FontMetricsInt fm) {
        float scale = 0.78f;
        float old = paint.getTextSize();
        paint.setTextSize(old * scale);
        int w = (int) (paint.measureText(label) + 2 * pad(old));
        paint.setTextSize(old);
        return w;
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint paint) {
        float old = paint.getTextSize();
        int oldColor = paint.getColor();
        float p = pad(old);
        paint.setTextSize(old * 0.78f);
        float tw = paint.measureText(label);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float h = fm.descent - fm.ascent;
        float cy = (top + bottom) / 2f;
        rect.set(x, cy - h / 2f - p / 3f, x + tw + 2 * p, cy + h / 2f + p / 3f);
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, bg);
        paint.setColor(oldColor);
        canvas.drawText(label, x + p, cy - (fm.ascent + fm.descent) / 2f, paint);
        paint.setTextSize(old);
    }

    private static float pad(float textSize) { return textSize * 0.35f; }
}
