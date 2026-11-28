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
 * Colour swatch grid (4 columns x N rows) drawn by ONE view (instead of 8 views per grid).
 * Paints/rects/path are created once; onDraw allocates nothing; taps are hit-tested by arithmetic.
 *
 * v928: configurable rows (the "recent colours" strip is a 1-row grid), empty tiles (colour 0 on a
 * non-default grid) are skipped, and the view describes its selection for TalkBack.
 */
final class BubbleSwatchGridView extends View {

    interface OnPick { void onPick(int index); }

    private static final int COLS = 4;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rf = new RectF();
    private final Path tickPath = new Path();

    private final int rows, n;
    private final float d, pad, gap, tileRadius;
    private final int[] colors;
    private int selected = -1;
    private OnPick listener;
    private float tw, th;
    private String descPrefix = "Colour";
    private String[] names;

    BubbleSwatchGridView(Context c, int ringColor) { this(c, ringColor, 2); }

    BubbleSwatchGridView(Context c, int ringColor, int rows) {
        super(c);
        this.rows = rows;
        this.n = COLS * rows;
        this.colors = new int[n];
        d = c.getResources().getDisplayMetrics().density;
        pad = 5f * d;
        gap = 10f * d;
        tileRadius = 16f * d;
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
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    /** Colours are already resolved (slot 0 = theme default colour for display). 0 = empty tile. */
    void setColors(int[] resolved) {
        java.util.Arrays.fill(colors, 0);
        System.arraycopy(resolved, 0, colors, 0, Math.min(n, resolved.length));
        refreshDescription();
        invalidate();
    }

    void setSelected(int index) {
        if (index != selected) { selected = index; refreshDescription(); invalidate(); }
    }

    void setOnPick(OnPick l) { listener = l; }

    /** TalkBack text: "<prefix>: <name> selected". {@code names} may be null (hex is spoken instead). */
    void setDescription(String prefix, String[] names) {
        this.descPrefix = prefix;
        this.names = names;
        refreshDescription();
    }

    private String nameOf(int i) {
        if (names != null && i >= 0 && i < names.length) return names[i];
        if (i >= 0 && i < n && colors[i] != 0) return String.format("#%06X", colors[i] & 0xFFFFFF);
        return "Custom";
    }

    private void refreshDescription() {
        setContentDescription(descPrefix + ": " + (selected >= 0 ? nameOf(selected) + " selected"
                : "custom colour selected") + ". Tap a swatch to change.");
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        final int w = MeasureSpec.getSize(wSpec);
        tw = (w - pad * 2f - gap * (COLS - 1)) / COLS;
        th = tw * 0.74f;
        setMeasuredDimension(w, Math.round(pad * 2f + th * rows + gap * (rows - 1)));
    }

    @Override
    protected void onDraw(Canvas c) {
        for (int i = 0; i < n; i++) {
            if (colors[i] == 0) continue;   // empty tile (recents strip)
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
            } else if (i == 0 && names != null && names.length > 0 && "Default".equals(names[0])) {
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
                if (e.getX() >= pad && e.getY() >= pad
                        && col >= 0 && col < COLS && row >= 0 && row < rows) {
                    final int i = row * COLS + col;
                    if (colors[i] != 0) {
                        performClick();
                        if (listener != null) listener.onPick(i);
                        announceForAccessibility(nameOf(i) + " selected");
                    }
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
