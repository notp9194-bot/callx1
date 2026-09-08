package com.callx.app.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Variable-speed "ramp" curve editor for the reel edit screen's Speed tool.
 *
 * Shows the clip's duration as a horizontal timeline. The user places
 * keyframes (position, speed) along it; the view draws a smooth curve
 * through them (0.3x at the bottom, 1x at the middle dashed line, 3x at
 * the top) and lets the user:
 *   - Tap empty curve space  -> add a keyframe there
 *   - Drag a keyframe        -> move it in time (X) and speed (Y)
 *   - Long-press a keyframe  -> delete it (min 2 keyframes always kept)
 *
 * Endpoints (t=0 and t=durationMs) always exist and cannot be deleted,
 * only their speed can move — this guarantees the ramp always spans the
 * whole clip so export/preview never has to guess a boundary speed.
 */
public class SpeedRampCurveView extends View {

    public static class Keyframe {
        public long posMs;
        public float speed;
        public Keyframe(long posMs, float speed) { this.posMs = posMs; this.speed = speed; }
    }

    public interface OnRampChangeListener {
        /** Fired on every drag update and add/delete — cheap, UI-thread only. */
        void onRampChanged(List<Keyframe> keyframes);
    }

    private static final float MIN_SPEED = 0.3f;
    private static final float MAX_SPEED = 3.0f;
    private static final float DEFAULT_SPEED = 1.0f;
    private static final long  DELETE_LONGPRESS_MS = 420;

    private long durationMs = 1000;
    private final List<Keyframe> keyframes = new ArrayList<>();
    private OnRampChangeListener listener;

    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint nodeActivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int draggingIndex = -1;
    private float downX, downY;
    private boolean movedSinceDown;
    private long downTimeMs;
    private final Runnable longPressCheck = this::checkLongPress;

    private long playheadMs = -1; // -1 = hidden

    private static final float TRACK_PADDING_H = 28f;
    private static final float TRACK_PADDING_V = 22f;
    private static final float NODE_RADIUS = 16f;
    private static final float NODE_TOUCH_SLOP = 42f;

    public SpeedRampCurveView(Context c) { super(c); init(); }
    public SpeedRampCurveView(Context c, AttributeSet a) { super(c, a); init(); }
    public SpeedRampCurveView(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(dp(3));
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        curvePaint.setColor(Color.parseColor("#00E5FF"));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.parseColor("#2600E5FF"));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        gridPaint.setColor(Color.parseColor("#1AFFFFFF"));

        baselinePaint.setStyle(Paint.Style.STROKE);
        baselinePaint.setStrokeWidth(dp(1.5f));
        baselinePaint.setColor(Color.parseColor("#55FFFFFF"));
        baselinePaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(6), dp(5)}, 0));

        nodePaint.setStyle(Paint.Style.FILL);
        nodePaint.setColor(Color.parseColor("#00E5FF"));

        nodeRingPaint.setStyle(Paint.Style.STROKE);
        nodeRingPaint.setStrokeWidth(dp(2.5f));
        nodeRingPaint.setColor(Color.WHITE);

        nodeActivePaint.setStyle(Paint.Style.FILL);
        nodeActivePaint.setColor(Color.parseColor("#FF3B5C"));

        playheadPaint.setStyle(Paint.Style.STROKE);
        playheadPaint.setStrokeWidth(dp(2));
        playheadPaint.setColor(Color.parseColor("#FFFFFF"));

        labelPaint.setColor(Color.parseColor("#CCFFFFFF"));
        labelPaint.setTextSize(dp(11));
        labelPaint.setAntiAlias(true);

        resetFlat();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    // ── Public API ──────────────────────────────────────────────────────

    public void setDurationMs(long durationMs) {
        this.durationMs = Math.max(200, durationMs);
        clampAllToDuration();
        invalidate();
    }

    public void setPlayheadMs(long posMs) { this.playheadMs = posMs; invalidate(); }
    public void hidePlayhead() { this.playheadMs = -1; invalidate(); }

    public void setOnRampChangeListener(OnRampChangeListener l) { this.listener = l; }

    /** Resets to a flat 1x curve spanning the whole clip (i.e. "no ramp"). */
    public void resetFlat() {
        keyframes.clear();
        keyframes.add(new Keyframe(0, DEFAULT_SPEED));
        keyframes.add(new Keyframe(durationMs, DEFAULT_SPEED));
        invalidate();
        notifyChange();
    }

    public void loadKeyframes(List<Keyframe> kfs) {
        keyframes.clear();
        if (kfs == null || kfs.size() < 2) { resetFlat(); return; }
        for (Keyframe k : kfs) keyframes.add(new Keyframe(k.posMs, clampSpeed(k.speed)));
        Collections.sort(keyframes, Comparator.comparingLong(k -> k.posMs));
        keyframes.get(0).posMs = 0;
        keyframes.get(keyframes.size() - 1).posMs = durationMs;
        invalidate();
    }

    public List<Keyframe> getKeyframes() { return keyframes; }

    public boolean isFlat() {
        for (Keyframe k : keyframes) if (Math.abs(k.speed - DEFAULT_SPEED) > 0.01f) return false;
        return true;
    }

    /** Applies a named preset curve, replacing all current keyframes. */
    public void applyPreset(String preset) {
        keyframes.clear();
        long d = durationMs;
        switch (preset) {
            case "slowmo_dip":
                keyframes.add(new Keyframe(0, 1.0f));
                keyframes.add(new Keyframe(Math.round(d * 0.4), 0.3f));
                keyframes.add(new Keyframe(Math.round(d * 0.6), 0.3f));
                keyframes.add(new Keyframe(d, 1.0f));
                break;
            case "build_up":
                keyframes.add(new Keyframe(0, 0.4f));
                keyframes.add(new Keyframe(d, 2.5f));
                break;
            case "fast_slow_fast":
                keyframes.add(new Keyframe(0, 2.0f));
                keyframes.add(new Keyframe(Math.round(d * 0.45), 0.4f));
                keyframes.add(new Keyframe(Math.round(d * 0.55), 0.4f));
                keyframes.add(new Keyframe(d, 2.0f));
                break;
            case "timelapse_end":
                keyframes.add(new Keyframe(0, 1.0f));
                keyframes.add(new Keyframe(Math.round(d * 0.7), 1.0f));
                keyframes.add(new Keyframe(d, 3.0f));
                break;
            default: // "flat"
                keyframes.add(new Keyframe(0, 1.0f));
                keyframes.add(new Keyframe(d, 1.0f));
        }
        invalidate();
        notifyChange();
    }

    /** Interpolated speed at an arbitrary position, for live preview sync. */
    public float speedAt(long posMs) {
        if (keyframes.isEmpty()) return DEFAULT_SPEED;
        if (posMs <= keyframes.get(0).posMs) return keyframes.get(0).speed;
        for (int i = 0; i < keyframes.size() - 1; i++) {
            Keyframe a = keyframes.get(i), b = keyframes.get(i + 1);
            if (posMs >= a.posMs && posMs <= b.posMs) {
                if (b.posMs == a.posMs) return a.speed;
                float t = (posMs - a.posMs) / (float) (b.posMs - a.posMs);
                return a.speed + (b.speed - a.speed) * t;
            }
        }
        return keyframes.get(keyframes.size() - 1).speed;
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || keyframes.size() < 2) return;

        RectF track = new RectF(TRACK_PADDING_H, TRACK_PADDING_V, w - TRACK_PADDING_H, h - TRACK_PADDING_V);

        // Speed gridlines: 0.3x, 1x (baseline), 2x, 3x
        drawGridLine(canvas, track, 3.0f, "3x");
        drawGridLine(canvas, track, 2.0f, "2x");
        drawGridLine(canvas, track, 0.3f, "0.3x");
        float baselineY = yForSpeed(track, DEFAULT_SPEED);
        canvas.drawLine(track.left, baselineY, track.right, baselineY, baselinePaint);
        canvas.drawText("1x", track.left, baselineY - dp(4), labelPaint);

        // Curve + fill
        Path curve = new Path();
        Path fill = new Path();
        for (int i = 0; i < keyframes.size(); i++) {
            Keyframe k = keyframes.get(i);
            float x = xForPos(track, k.posMs);
            float y = yForSpeed(track, k.speed);
            if (i == 0) { curve.moveTo(x, y); fill.moveTo(x, track.bottom); fill.lineTo(x, y); }
            else { curve.lineTo(x, y); fill.lineTo(x, y); }
        }
        fill.lineTo(xForPos(track, keyframes.get(keyframes.size() - 1).posMs), track.bottom);
        fill.close();
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(curve, curvePaint);

        // Nodes
        for (int i = 0; i < keyframes.size(); i++) {
            Keyframe k = keyframes.get(i);
            float x = xForPos(track, k.posMs);
            float y = yForSpeed(track, k.speed);
            canvas.drawCircle(x, y, NODE_RADIUS, i == draggingIndex ? nodeActivePaint : nodePaint);
            canvas.drawCircle(x, y, NODE_RADIUS, nodeRingPaint);
        }

        // Playhead
        if (playheadMs >= 0) {
            float px = xForPos(track, playheadMs);
            canvas.drawLine(px, track.top - dp(6), px, track.bottom + dp(6), playheadPaint);
        }
    }

    private void drawGridLine(Canvas canvas, RectF track, float speed, String label) {
        float y = yForSpeed(track, speed);
        canvas.drawLine(track.left, y, track.right, y, gridPaint);
        canvas.drawText(label, track.left, y - dp(4), labelPaint);
    }

    private float xForPos(RectF track, long posMs) {
        float t = durationMs <= 0 ? 0 : posMs / (float) durationMs;
        return track.left + t * track.width();
    }

    private float yForSpeed(RectF track, float speed) {
        float t = (speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED); // 0..1
        return track.bottom - t * track.height();
    }

    private long posForX(RectF track, float x) {
        float t = (x - track.left) / track.width();
        t = Math.max(0f, Math.min(1f, t));
        return Math.round(t * durationMs);
    }

    private float speedForY(RectF track, float y) {
        float t = (track.bottom - y) / track.height();
        t = Math.max(0f, Math.min(1f, t));
        return clampSpeed(MIN_SPEED + t * (MAX_SPEED - MIN_SPEED));
    }

    private float clampSpeed(float s) { return Math.max(MIN_SPEED, Math.min(MAX_SPEED, s)); }

    // ── Touch handling ──────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        RectF track = new RectF(TRACK_PADDING_H, TRACK_PADDING_V, getWidth() - TRACK_PADDING_H, getHeight() - TRACK_PADDING_V);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX(); downY = event.getY(); movedSinceDown = false;
                downTimeMs = System.currentTimeMillis();
                draggingIndex = findNearestNode(track, downX, downY);
                if (draggingIndex >= 0) postDelayed(longPressCheck, DELETE_LONGPRESS_MS);
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (Math.abs(event.getX() - downX) > 6 || Math.abs(event.getY() - downY) > 6) movedSinceDown = true;
                if (draggingIndex >= 0 && movedSinceDown) {
                    removeCallbacks(longPressCheck);
                    Keyframe k = keyframes.get(draggingIndex);
                    k.speed = speedForY(track, event.getY());
                    boolean isEndpoint = draggingIndex == 0 || draggingIndex == keyframes.size() - 1;
                    if (!isEndpoint) {
                        long newPos = posForX(track, event.getX());
                        long lo = keyframes.get(draggingIndex - 1).posMs + 40;
                        long hi = keyframes.get(draggingIndex + 1).posMs - 40;
                        if (hi > lo) k.posMs = Math.max(lo, Math.min(hi, newPos));
                    }
                    invalidate();
                    notifyChange();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressCheck);
                if (draggingIndex < 0 && !movedSinceDown) {
                    // Tap on empty curve area -> add a new keyframe there
                    long pos = posForX(track, downX);
                    float speed = speedForY(track, downY);
                    addKeyframe(pos, speed);
                }
                draggingIndex = -1;
                invalidate();
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void checkLongPress() {
        if (draggingIndex > 0 && draggingIndex < keyframes.size() - 1 && !movedSinceDown) {
            keyframes.remove(draggingIndex);
            draggingIndex = -1;
            invalidate();
            notifyChange();
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    }

    private int findNearestNode(RectF track, float x, float y) {
        int best = -1; float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < keyframes.size(); i++) {
            Keyframe k = keyframes.get(i);
            float nx = xForPos(track, k.posMs), ny = yForSpeed(track, k.speed);
            float dist = (float) Math.hypot(x - nx, y - ny);
            if (dist < NODE_TOUCH_SLOP && dist < bestDist) { bestDist = dist; best = i; }
        }
        return best;
    }

    private void addKeyframe(long posMs, float speed) {
        if (keyframes.size() >= 8) return; // keep the curve legible
        for (Keyframe k : keyframes) if (Math.abs(k.posMs - posMs) < 60) return; // too close to existing
        keyframes.add(new Keyframe(posMs, clampSpeed(speed)));
        Collections.sort(keyframes, Comparator.comparingLong(k -> k.posMs));
        notifyChange();
    }

    private void clampAllToDuration() {
        if (keyframes.isEmpty()) return;
        keyframes.get(0).posMs = 0;
        keyframes.get(keyframes.size() - 1).posMs = durationMs;
    }

    private void notifyChange() {
        if (listener != null) listener.onRampChanged(keyframes);
    }
}
