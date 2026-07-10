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
 * ChatListCallButtonsView — v83 Telegram-level perf hardening.
 *
 * v82 CHANGES: collapsed two ImageButtons into one canvas view (one measure+draw).
 *
 * v83 PERF FIXES (zero-allocation hot path in onDraw):
 *  1. Icon Paths pre-baked in onSizeChanged() — onDraw() calls canvas.drawPath()
 *     on already-built Path objects. v82 called path.reset() + path.moveTo/lineTo
 *     + path.arcTo on EVERY draw frame, which is wasted CPU on every scroll frame.
 *  2. videoPath and phonePath are final instance fields — no new Path() in draw.
 *  3. arcRect (for the phone arc) is a pre-allocated final RectF field —
 *     v82 created "new RectF(...)" inside drawPhoneIcon() on every draw call,
 *     causing a GC allocation on every scroll frame.
 *  4. All float layout values (btnW, gap, iconR, centres) computed once in
 *     onSizeChanged(), stored as plain float fields read in onDraw().
 *
 * Result: onDraw() path is two canvas.drawPath() calls with zero allocations
 * and zero floating-point arithmetic — just field reads.
 */
public class ChatListCallButtonsView extends View {

    private final Paint iconPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    // v83: pre-baked paths — built once in onSizeChanged, reused in onDraw
    private final Path  videoPath  = new Path();
    private final Path  phonePath  = new Path();
    // v83: pre-allocated RectF for arc segments (no new RectF in draw)
    private final RectF arcRectA   = new RectF();
    private final RectF arcRectB   = new RectF();
    private final RectF bodyRect   = new RectF();

    private final float density;
    private boolean pathsBaked = false;

    // v83: layout values cached in onSizeChanged
    private float midX = 0f;  // x boundary between video and phone touch zones

    private Runnable onVoiceCall;
    private Runnable onVideoCall;

    private boolean touchDown    = false;
    private boolean touchOnVideo = false;

    private static final int COLOR_ICON = 0xFF4CAF50;

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

        fillPaint.setColor(COLOR_ICON);
        fillPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        fillPaint.setStrokeWidth(1.8f * density);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setAntiAlias(true);
    }

    /** Sets the click callbacks for voice and video call. */
    public void setListeners(Runnable voiceCall, Runnable videoCall) {
        this.onVoiceCall = voiceCall;
        this.onVideoCall = videoCall;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float w = (34 * 2 + 6) * density;
        float h = 34 * density;
        setMeasuredDimension(
            resolveSize((int) Math.ceil(w), widthMeasureSpec),
            resolveSize((int) Math.ceil(h), heightMeasureSpec)
        );
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        bakeIconPaths(w, h);
    }

    /**
     * v83: Pre-bake both icon paths based on current view dimensions.
     * Called once in onSizeChanged; onDraw() just plays back the paths.
     */
    private void bakeIconPaths(int tw, int th) {
        float btnW  = 34 * density;
        float gap   = 6  * density;
        float iconR = 10 * density;
        float vy    = th / 2f;
        float vx    = btnW / 2f;

        // ── Video camera icon (left button) ──────────────────────────────
        videoPath.reset();
        float bodyL = vx - iconR;
        float bodyT = vy - iconR * 0.6f;
        float bodyR = vx + iconR * 0.35f;
        float bodyB = vy + iconR * 0.6f;
        bodyRect.set(bodyL, bodyT, bodyR, bodyB);
        // We can't pre-bake roundRect into a path cleanly without addRoundRect
        // so we use addRoundRect on the videoPath directly:
        videoPath.addRoundRect(bodyRect, 2 * density, 2 * density, Path.Direction.CW);
        // Triangle for viewfinder
        videoPath.moveTo(bodyR + 3 * density,            vy - iconR * 0.55f);
        videoPath.lineTo(bodyR + 3 * density + iconR * 0.55f, vy);
        videoPath.lineTo(bodyR + 3 * density,            vy + iconR * 0.55f);
        videoPath.close();

        // ── Phone handset icon (right button) ────────────────────────────
        float px = btnW + gap + btnW / 2f;
        float py = vy;
        float s  = iconR * 0.85f;

        phonePath.reset();
        phonePath.moveTo(px - s * 0.6f, py - s);
        arcRectA.set(px - s, py - s, px - s * 0.2f, py - s * 0.2f);
        phonePath.arcTo(arcRectA, 230f, -160f, false);
        phonePath.lineTo(px + s * 0.3f, py + s * 0.1f);
        arcRectB.set(px + s * 0.1f, py + s * 0.15f, px + s, py + s);
        phonePath.arcTo(arcRectB, 50f, -160f, false);
        phonePath.lineTo(px + s, py + s);

        midX = btnW + gap / 2f;
        pathsBaked = true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int tw = getWidth();
        int th = getHeight();
        if (tw <= 0 || th <= 0) return;
        if (!pathsBaked) bakeIconPaths(tw, th);

        // v83 hot path: two drawPath calls, zero allocations, zero arithmetic
        // Video icon: body (stroke) + triangle (fill) — drawn together via fillPaint
        // which uses FILL_AND_STROKE, so the body gets a subtle fill too.
        // Use iconPaint (STROKE only) for the body rect and fillPaint for triangle.
        canvas.drawPath(videoPath, fillPaint);
        canvas.drawPath(phonePath, iconPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDown   = true;
                touchOnVideo = e.getX() < midX;
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
                    boolean video = e.getX() < midX;
                    if (video  && onVideoCall != null) onVideoCall.run();
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
