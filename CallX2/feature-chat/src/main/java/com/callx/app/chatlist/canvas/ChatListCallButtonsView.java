package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * ChatListCallButtonsView — v82 canvas optimisation.
 *
 * WHY THIS EXISTS
 * ───────────────
 * The old call-buttons row was a horizontal LinearLayout containing two
 * ImageButton widgets, each inflating a tinted vector drawable for voice/video
 * icons. Per-bind cost:
 *   • Two ImageButton measure/layout passes inside the LinearLayout
 *   • Two VectorDrawable mutate/tint draws
 *   • LinearLayout's own measure + layout
 *
 * This view collapses both buttons into ONE plain View:
 *   • Video icon (camera body + lens) drawn left via Path+canvas
 *   • Voice icon (phone handset) drawn right via Path+canvas
 *   • Touch regions split at midpoint — onTouchEvent delivers clicks
 *     without needing nested clickable children, same technique
 *     MessageBubbleCanvasView uses for in-bubble tap zones.
 *
 * Icons are drawn with brand_primary (#4CAF50) stroke/fill at ~20dp icon size
 * inside a 34x34dp touch target per button, matching the old layout exactly.
 *
 * PERF: setVisible() hides the entire view (GONE) during selection mode rather
 * than measuring/drawing either button. setListeners() is called once during
 * full bind and captured in fields so onTouchEvent doesn't re-allocate on
 * every event.
 */
public class ChatListCallButtonsView extends View {

    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  path      = new Path();
    private final RectF rect      = new RectF();

    private final float density;

    private Runnable onVoiceCall;
    private Runnable onVideoCall;

    // Touch tracking
    private boolean touchDown     = false;
    private boolean touchOnVideo  = false;

    private static final int COLOR_ICON = 0xFF4CAF50; // brand_primary

    public ChatListCallButtonsView(Context ctx) {
        this(ctx, null);
    }

    public ChatListCallButtonsView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        iconPaint.setColor(COLOR_ICON);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(1.8f * density);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * Sets the click callbacks for voice and video call. Pass null to clear.
     */
    public void setListeners(Runnable voiceCall, Runnable videoCall) {
        this.onVoiceCall  = voiceCall;
        this.onVideoCall  = videoCall;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Two 34dp touch targets side-by-side with 6dp gap
        float w = (34 * 2 + 6) * density;
        float h = 34 * density;
        setMeasuredDimension(
            resolveSize((int) Math.ceil(w), widthMeasureSpec),
            resolveSize((int) Math.ceil(h), heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int tw = getWidth();
        int th = getHeight();
        if (tw <= 0 || th <= 0) return;

        float btnW  = 34 * density;
        float gap   = 6 * density;
        float iconR = 10 * density; // icon "radius" — half of ~20dp icon

        // Video button centre (left)
        float vx = btnW / 2f;
        float vy = th / 2f;
        drawVideoIcon(canvas, vx, vy, iconR);

        // Voice button centre (right)
        float px = btnW + gap + btnW / 2f;
        float py = th / 2f;
        drawPhoneIcon(canvas, px, py, iconR);
    }

    // ── Video camera icon ────────────────────────────────────────────────
    // Rectangle body (left ~60%) + triangle "lens/viewfinder" pointing right
    private void drawVideoIcon(Canvas canvas, float cx, float cy, float r) {
        iconPaint.setStyle(Paint.Style.STROKE);
        float bodyL = cx - r;
        float bodyT = cy - r * 0.6f;
        float bodyR = cx + r * 0.35f;
        float bodyB = cy + r * 0.6f;
        rect.set(bodyL, bodyT, bodyR, bodyB);
        canvas.drawRoundRect(rect, 2 * density, 2 * density, iconPaint);

        // Triangle pointing right
        iconPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        path.reset();
        path.moveTo(bodyR + 3 * density, cy - r * 0.55f);
        path.lineTo(bodyR + 3 * density + r * 0.55f, cy);
        path.lineTo(bodyR + 3 * density, cy + r * 0.55f);
        path.close();
        canvas.drawPath(path, iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
    }

    // ── Phone handset icon ───────────────────────────────────────────────
    // Simplified phone shape: two small arcs for earpiece+mouthpiece + body
    private void drawPhoneIcon(Canvas canvas, float cx, float cy, float r) {
        iconPaint.setStyle(Paint.Style.STROKE);
        // Draw a simplified phone using a path that approximates a handset
        path.reset();
        float s = r * 0.85f; // scale
        // Earpiece cup (top-left)
        path.moveTo(cx - s * 0.6f, cy - s);
        path.arcTo(new RectF(cx - s, cy - s, cx - s * 0.2f, cy - s * 0.2f),
                   230f, -160f, false);
        // Body
        path.lineTo(cx + s * 0.3f, cy + s * 0.1f);
        // Mouthpiece cup (bottom-right)
        path.arcTo(new RectF(cx + s * 0.1f, cy + s * 0.15f, cx + s, cy + s),
                   50f, -160f, false);
        path.lineTo(cx + s, cy + s);
        canvas.drawPath(path, iconPaint);
    }

    // ── Touch handling ───────────────────────────────────────────────────
    // Left half = video call, right half = voice call. Simple mid-split
    // because both buttons are equal-width and always laid out that way.
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int tw = getWidth();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDown = true;
                touchOnVideo = e.getX() < tw / 2f;
                setPressed(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                touchDown = false;
                setPressed(false);
                return true;
            case MotionEvent.ACTION_UP:
                if (touchDown) {
                    touchDown = false;
                    setPressed(false);
                    performClick();
                    boolean video = e.getX() < tw / 2f;
                    if (video && onVideoCall != null) onVideoCall.run();
                    else if (!video && onVoiceCall != null) onVoiceCall.run();
                }
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
