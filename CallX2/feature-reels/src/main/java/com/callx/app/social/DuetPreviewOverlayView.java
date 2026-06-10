package com.callx.app.social;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

/**
 * DuetPreviewOverlayView — Fix 5: Real-time Composite Preview
 *
 * A lightweight overlay drawn on top of the split-view layout that shows
 * the user exactly what the final composited output will look like:
 *   - Side-by-side divider line + labels ("Original" | "You")
 *   - Top-bottom divider + labels
 *   - PiP corner indicator
 *   - Reaction-bubble circle outline
 *
 * This is purely informational — no actual video compositing at preview time
 * (that would require a GL context). It closes the UX gap where users
 * didn't know what the final output looked like until post-processing.
 */
public class DuetPreviewOverlayView extends View {

    public static final int LAYOUT_SIDE_BY_SIDE    = 0;
    public static final int LAYOUT_TOP_BOTTOM      = 1;
    public static final int LAYOUT_REACT_PIP       = 2;
    public static final int LAYOUT_REACTION_BUBBLE = 3;

    private int   layoutMode = LAYOUT_SIDE_BY_SIDE;
    private float bubbleX    = 0.2f; // normalized 0..1
    private float bubbleY    = 0.75f;

    private final Paint dividerPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DuetPreviewOverlayView(Context ctx) {
        super(ctx);
        init();
    }

    public DuetPreviewOverlayView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        dividerPaint.setColor(Color.WHITE);
        dividerPaint.setStrokeWidth(3f);
        dividerPaint.setStyle(Paint.Style.STROKE);
        dividerPaint.setAlpha(160);

        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextSize(28f);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setShadowLayer(6f, 0f, 2f, Color.BLACK);

        bubblePaint.setColor(Color.WHITE);
        bubblePaint.setStrokeWidth(4f);
        bubblePaint.setStyle(Paint.Style.STROKE);
        bubblePaint.setAlpha(200);

        shadowPaint.setColor(0x44000000);
        shadowPaint.setStyle(Paint.Style.FILL);
    }

    /** Call this to switch layout mode — triggers redraw */
    public void setLayoutMode(int mode) {
        if (this.layoutMode != mode) {
            this.layoutMode = mode;
            invalidate();
        }
    }

    /** Update bubble position (0..1 normalized) for LAYOUT_REACTION_BUBBLE mode */
    public void setBubblePosition(float nx, float ny) {
        this.bubbleX = nx;
        this.bubbleY = ny;
        if (layoutMode == LAYOUT_REACTION_BUBBLE) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        switch (layoutMode) {
            case LAYOUT_SIDE_BY_SIDE:
                drawSideBySide(canvas, w, h);
                break;
            case LAYOUT_TOP_BOTTOM:
                drawTopBottom(canvas, w, h);
                break;
            case LAYOUT_REACT_PIP:
                drawPip(canvas, w, h);
                break;
            case LAYOUT_REACTION_BUBBLE:
                drawReactionBubble(canvas, w, h);
                break;
        }
    }

    private void drawSideBySide(Canvas canvas, int w, int h) {
        float mid = w / 2f;
        canvas.drawLine(mid, 0, mid, h, dividerPaint);
        drawLabel(canvas, "Original", 20, h - 30);
        drawLabel(canvas, "You", mid + 20, h - 30);
    }

    private void drawTopBottom(Canvas canvas, int w, int h) {
        float mid = h / 2f;
        canvas.drawLine(0, mid, w, mid, dividerPaint);
        drawLabel(canvas, "You", 20, mid - 12);
        drawLabel(canvas, "Original", 20, h - 30);
    }

    private void drawPip(Canvas canvas, int w, int h) {
        int pipW = (int)(w * 0.3f);
        int pipH = (int)(h * 0.3f);
        int margin = 24;
        Rect pip = new Rect(margin, margin, margin + pipW, margin + pipH);
        canvas.drawRect(pip, dividerPaint);
        drawLabel(canvas, "Original", margin + 8, margin + pipH - 12);
        drawLabel(canvas, "You (full screen)", 20, h - 30);
    }

    private void drawReactionBubble(Canvas canvas, int w, int h) {
        float cx = bubbleX * w;
        float cy = bubbleY * h;
        float r  = Math.min(w, h) * 0.18f;
        canvas.drawCircle(cx, cy, r, bubblePaint);
        drawLabel(canvas, "You", cx - 14, cy + 6);
        drawLabel(canvas, "Original", 20, h - 30);
    }

    private void drawLabel(Canvas canvas, String text, float x, float y) {
        canvas.drawText(text, x, y, labelPaint);
    }
}
