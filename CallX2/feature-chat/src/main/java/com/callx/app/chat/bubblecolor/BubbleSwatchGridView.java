package com.callx.app.chat.bubblecolor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.callx.app.utils.BubbleColorStore;

/**
 * 2x4 colour swatch grid drawn by ONE view (instead of 8 views x 2 grids = 16 inflated children).
 * Paints/rects/path are created once; onDraw allocates nothing; taps are hit-tested by arithmetic.
 */
final class BubbleSwatchGridView extends View {

    interface OnPick { void onPick(int index); }

    private static final int COLS = 4, ROWS = 2, N = COLS * ROWS;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path tickPath = new Path();

    private final float d, pad, gap, tileRadius;
    private final int ringColor;
    private final int[] colors = new int[N];
    private int selected = -1;
    private OnPick listener;
    private float tw, th;

    BubbleSwatchGridView(Context c, int ringColor) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        pad = 5f * d;
        gap = 10f * d;
        tileRadius = 16f * d;
        this.ringColor = ringColor;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * d);
        ring.setColor(ringColor);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeWidth(3f * d);
        tick.setStrokeCap(Paint.Cap.ROUND);
        tick.setStrokeJoin(Paint.Join.ROUND);
        tick.setColor(0xFFFFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(11f * c.getResources().getDisplayMetrics().scaledDensity);
        label.setFakeBoldText(true);
    }

    /** Colours are already resolved (slot 0 = theme default colour for display). */
    void setColors(int[] resolved) {
        System.arraycopy(resolved, 0, colors, 0, Math.min(N, resolved.length));
        invalidate();
    }

    void setSelected(int index) {
        if (index != selected) { selected = index; invalidate(); }
    }

    void setOnPick(OnPick l) { listener = l; }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        final int w = MeasureSpec.getSize(wSpec);
        tw = (w - pad * 2f - gap * (COLS - 1)) / COLS;
        th = tw * 0.74f;
        setMeasuredDimension(w, Math.round(pad * 2f + th * ROWS + gap * (ROWS - 1)));
    }

    @Override
    protected void onDraw(Canvas c) {
        for (int i = 0; i < N; i++) {
            final float l = pad + (i % COLS) * (tw + gap);
            final float t = pad + (i / COLS) * (th + gap);
            rf.set(l, t, l + tw, t + th);
            fill.setColor(colors[i]);
            c.drawRoundRect(rf, tileRadius, tileRadius, fill);
            if (i == selected) {
                rf.inset(-3.5f * d, -3.5f * d);
                c.drawRoundRect(rf, tileRadius + 3f * d, tileRadius + 3f * d, ring);
                final float cx = l + tw / 2f, cy = t + th / 2f, s = th * 0.17f;
                tickPath.rewind();
                tickPath.moveTo(cx - s, cy);
                tickPath.lineTo(cx - s * 0.3f, cy + s * 0.75f);
                tickPath.lineTo(cx + s, cy - s * 0.7f);
                tick.setColor(BubbleColorStore.readableOn(colors[i]));
                c.drawPath(tickPath, tick);
            } else if (i == 0) {
                label.setColor(BubbleColorStore.readableOn(colors[i]));
                c.drawText("Default", l + tw / 2f, t + th / 2f + 4f * d, label);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;
            case MotionEvent.ACTION_UP: {
                final int col = (int) ((e.getX() - pad) / (tw + gap));
                final int row = (int) ((e.getY() - pad) / (th + gap));
                if (col >= 0 && col < COLS && row >= 0 && row < ROWS) {
                    final int i = row * COLS + col;
                    performClick();
                    if (listener != null) listener.onPick(i);
                }
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() { return super.performClick(); }
}
