package com.callx.app.stickers;

import android.content.Context;
import android.graphics.*;
import android.os.CountDownTimer;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.*;
import androidx.annotation.NonNull;

/**
 * StatusStickerOverlayView — renders a live sticker card on top of a status/story preview.
 *
 * Supports six sticker types:
 *  🎵 music     — album art + song + artist, pulsing equaliser bars
 *  ⏳ countdown — live ticking countdown to a target date, coloured card
 *  🧠 quiz      — multiple-choice question card with option rows
 *  💬 question  — open question box with "Send a reply" hint
 *  🗳️ poll      — 2-option vote card, reveals live % split once voted
 *  🎚️ slider    — draggable emoji rating slider (0-100%), reveals live average once released
 *  👤 mention    — @username tag card, tap opens that user's profile
 *  #️⃣ hashtag   — #topic tag card, tap opens the hashtag's feed
 *  🔗 link       — tappable link pill, tap opens it in the browser
 *  ➕ addyours   — prompt card, tap opens the composer to join the chain
 *
 * The view is draggable by default. The host activity should call
 * {@link #attachDragToParent(ViewGroup)} after adding this view to a FrameLayout.
 *
 * Usage:
 *   StatusStickerOverlayView v = StatusStickerOverlayView.fromJson(ctx, stickerJson);
 *   frameLayout.addView(v);
 *   v.attachDragToParent(frameLayout);
 */
public class StatusStickerOverlayView extends LinearLayout {

    private CountDownTimer countdownTimer;
    private String stickerType;
    private String stickerJson;

    // ── Size control (pinch + Small/Medium/Large buttons in the composer) ──
    public static final float SCALE_SMALL  = 0.7f;
    public static final float SCALE_MEDIUM = 1.0f;
    public static final float SCALE_LARGE  = 1.4f;
    private static final float SCALE_MIN = 0.3f, SCALE_MAX = 5.0f;
    private float stickerScale = SCALE_MEDIUM;
    private OnStickerTappedListener stickerTapListener;

    /** Fired on a plain tap (not a drag/pinch) — composer uses this to show the size buttons. */
    public interface OnStickerTappedListener { void onTapped(StatusStickerOverlayView sticker); }
    public void setOnStickerTappedListener(OnStickerTappedListener l) { this.stickerTapListener = l; }

    /** Drag lifecycle hook (composer uses it for the safe-area guide + edge haptics). */
    public interface OnStickerDragListener {
        void onDragStart(StatusStickerOverlayView sticker);
        void onDragMove(StatusStickerOverlayView sticker);
        void onDragEnd(StatusStickerOverlayView sticker);
    }
    private OnStickerDragListener dragListener;
    public void setOnStickerDragListener(OnStickerDragListener l) { this.dragListener = l; }
    public float getStickerScale() { return stickerScale; }

    // ═════════════════════════════════════════════════════════════════════
    // v649 — "ultra advanced" sticker transform engine: public API
    // ═════════════════════════════════════════════════════════════════════

    /** Fired once per finished gesture (or composer action) that really changed x/y/scale/rotation → undo/redo. */
    public interface OnTransformCommittedListener {
        void onTransformCommitted(StatusStickerOverlayView sticker, float[] before, float[] after);
    }
    private OnTransformCommittedListener commitListener;
    public void setOnTransformCommittedListener(OnTransformCommittedListener l) { this.commitListener = l; }

    private boolean selectedUi;          // dotted border + corner resize/rotate handle (composer only)
    private boolean locked;              // locked stickers ignore drag / pinch / handle
    private boolean removing;            // true from the moment removal is decided
    private boolean autoRaiseOnTouch = true;
    public void setAutoRaiseOnTouch(boolean v) { autoRaiseOnTouch = v; }
    public boolean isRemoving() { return removing; }
    public boolean isLocked() { return locked; }
    public boolean isSelectedUi() { return selectedUi; }
    public void setLocked(boolean v) { locked = v; invalidate(); }
    public void setSelectedUi(boolean v) { if (selectedUi != v) { selectedUi = v; invalidate(); } }

    // gesture-engine state
    private float[] gBefore;                                   // state at gesture start (undo "before")
    private float nudgeTargetX = Float.NaN, nudgeTargetY = Float.NaN;
    private boolean tfActive, handleActive, rotSnapped, scaleSnapped;
    private float tfStartDist, tfStartAngle, tfStartScale, tfStartRot, tfStartMidX, tfStartMidY,
            tfStartCx, tfStartCy, tfLastDAng;
    private float hStartDist, hStartAngle, hStartScale, hStartRot, hLastDAng;
    private float snapTx = Float.NaN, snapTy = Float.NaN;
    private final float[] tmpPt = new float[2];
    // v650 perf: reused buffers so a drag MOVE allocates nothing.
    private final float[] snapOut = new float[2];
    private float[] snapTargets = new float[0];   // flat {cx, cy, halfW, halfH} of every OTHER sticker, built once per gesture
    private int snapTargetCount;
    private View[] rotCur;                        // rotation chrome of the parent in use (avoids a map lookup per MOVE)

    /** {x, y, scale, rotationDeg} — x/y already include a pending safe-area nudge. */
    public float[] captureState() {
        return new float[]{
                Float.isNaN(nudgeTargetX) ? getX() : nudgeTargetX,
                Float.isNaN(nudgeTargetY) ? getY() : nudgeTargetY,
                stickerScale, stickerRotationDeg};
    }

    /** Animated jump back to a state from {@link #captureState()} (undo / redo). */
    public void restoreState(float[] s) {
        if (s == null || s.length < 4) return;
        animate().cancel();
        float curRot = getRotation();
        stickerScale = clampScale(s[2]);
        stickerRotationDeg = normDeg(s[3]);
        float target = curRot + normDeg(stickerRotationDeg - curRot); // shortest way round
        animate().x(s[0]).y(s[1]).scaleX(stickerScale).scaleY(stickerScale).rotation(target)
                .setDuration(260).setInterpolator(new OvershootInterpolator(1.1f))
                .withEndAction(() -> setRotation(stickerRotationDeg)).start();
        invalidate();
    }

    /** Asks for a small slide of (dx,dy) px when the current gesture finishes (safe-area magnet). */
    public void nudgeBy(float dx, float dy) {
        nudgeTargetX = getX() + dx;
        nudgeTargetY = getY() + dy;
    }

    /** {centerX, centerY, halfWidth, halfHeight} of the on-screen (scaled + rotated) bounds, in parent px. */
    public float[] getVisualBounds() {
        float[] out = new float[4];
        fillVisualBounds(out, 0);
        return out;
    }

    /** Allocation-free {@link #getVisualBounds()}: writes the 4 values into {@code out[off..off+3]}. */
    public void fillVisualBounds(float[] out, int off) {
        double r = Math.toRadians(getRotation());
        float c = (float) Math.abs(Math.cos(r)), sn = (float) Math.abs(Math.sin(r));
        float w = getWidth() * stickerScale, h = getHeight() * stickerScale;
        out[off]     = getX() + getWidth() / 2f;
        out[off + 1] = getY() + getHeight() / 2f;
        out[off + 2] = (w * c + h * sn) / 2f;
        out[off + 3] = (w * sn + h * c) / 2f;
    }

    /** Snapshot every other live sticker's bounds ONCE per gesture (they cannot move while this one is dragged). */
    private void collectSnapTargets(ViewGroup parent) {
        int n = parent.getChildCount();
        if (snapTargets.length < n * 4) snapTargets = new float[n * 4];
        int c = 0;
        for (int i = 0; i < n; i++) {
            View o = parent.getChildAt(i);
            if (o == this || !(o instanceof StatusStickerOverlayView) || o.getVisibility() != View.VISIBLE) continue;
            StatusStickerOverlayView os = (StatusStickerOverlayView) o;
            if (os.removing) continue;
            os.fillVisualBounds(snapTargets, c * 4);
            c++;
        }
        snapTargetCount = c;
    }

    private static float clampScale(float s) { return Math.max(SCALE_MIN, Math.min(SCALE_MAX, s)); }
    private static float normDeg(float d) {
        d %= 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    // ── Canvas-wide two-finger gesture (host routes touches from the empty canvas to the selected sticker) ──
    public void beginCanvasGesture(ViewGroup parent, float x0, float y0, float x1, float y1) {
        gBefore = captureState();
        nudgeTargetX = nudgeTargetY = Float.NaN;
        tfBegin(x0, y0, x1, y1);
        rotCur = getOrCreateRot(parent);
        if (dragListener != null) dragListener.onDragStart(this);
    }
    public void updateCanvasGesture(ViewGroup parent, float x0, float y0, float x1, float y1) {
        if (!tfActive) return;
        tfUpdate(parent, x0, y0, x1, y1);
        if (dragListener != null) dragListener.onDragMove(this);
    }
    public void endCanvasGesture(ViewGroup parent) {
        tfActive = false;
        hideSnapChrome(parent);
        if (dragListener != null) dragListener.onDragEnd(this);
        animateRelease();
        commitTransformIfChanged();
    }

    // ── internals ────────────────────────────────────────────────────────
    private void commitTransformIfChanged() {
        float[] before = gBefore;
        gBefore = null;
        float[] after = captureState();
        nudgeTargetX = nudgeTargetY = Float.NaN;
        if (before == null || commitListener == null) return;
        if (Math.abs(before[0] - after[0]) > 1.5f || Math.abs(before[1] - after[1]) > 1.5f
                || Math.abs(before[2] - after[2]) > 0.004f
                || Math.abs(normDeg(before[3] - after[3])) > 0.4f) {
            commitListener.onTransformCommitted(this, before, after);
        }
    }

    /** Springy settle after a gesture (pickup 1.05x → real scale), optionally sliding into a nudge target. */
    private void animateRelease() {
        android.view.ViewPropertyAnimator a = animate().scaleX(stickerScale).scaleY(stickerScale).alpha(1f)
                .setDuration(220).setInterpolator(new OvershootInterpolator(2.0f));
        if (!Float.isNaN(nudgeTargetX) && !Float.isNaN(nudgeTargetY)) a.x(nudgeTargetX).y(nudgeTargetY);
        a.start();
    }

    /** Finger {idx} of {@code e} in the PARENT's coordinate space (stable even while this view rotates/scales). */
    private void toParent(MotionEvent e, int idx, float[] out) {
        tmpPt[0] = e.getX(idx);
        tmpPt[1] = e.getY(idx);
        getMatrix().mapPoints(tmpPt);
        out[0] = tmpPt[0] + getLeft();
        out[1] = tmpPt[1] + getTop();
    }

    private void tfBegin(float x0, float y0, float x1, float y1) {
        animate().cancel();
        setScaleX(stickerScale);
        setScaleY(stickerScale);
        tfActive = true;
        tfStartDist = Math.max(1f, (float) Math.hypot(x1 - x0, y1 - y0));
        tfStartAngle = (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
        tfStartScale = stickerScale;
        tfStartRot = stickerRotationDeg;
        tfStartMidX = (x0 + x1) / 2f;
        tfStartMidY = (y0 + y1) / 2f;
        tfStartCx = getX() + getWidth() / 2f;
        tfStartCy = getY() + getHeight() / 2f;
        tfLastDAng = 0f;
        rotSnapped = false;
        scaleSnapped = false;
    }

    /** Pinch + twist + two-finger pan as ONE similarity transform around the fingers' midpoint. */
    private void tfUpdate(ViewGroup parent, float x0, float y0, float x1, float y1) {
        float dist = Math.max(1f, (float) Math.hypot(x1 - x0, y1 - y0));
        float scale = resolveScale(tfStartScale * dist / tfStartDist);

        float ang = (float) Math.toDegrees(Math.atan2(y1 - y0, x1 - x0));
        float dAng = ang - tfStartAngle;
        while (dAng - tfLastDAng > 180f) dAng -= 360f;
        while (dAng - tfLastDAng < -180f) dAng += 360f;
        tfLastDAng = dAng;
        float rot = resolveRotation(tfStartRot, dAng);
        float dTheta = rot - tfStartRot;

        float midX = (x0 + x1) / 2f, midY = (y0 + y1) / 2f;
        double rad = Math.toRadians(dTheta);
        float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
        float k = scale / tfStartScale;
        float v0x = tfStartCx - tfStartMidX, v0y = tfStartCy - tfStartMidY;
        float cx = midX + k * (cos * v0x - sin * v0y);
        float cy = midY + k * (sin * v0x + cos * v0y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            cx = Math.max(0f, Math.min(parent.getWidth(), cx));
            cy = Math.max(0f, Math.min(parent.getHeight(), cy));
        }
        applyScale(scale);
        applyRotation(rot);
        setX(cx - getWidth() / 2f);
        setY(cy - getHeight() / 2f);
        updateRotChrome(parent, rotSnapped, rot, Math.abs(dAng) > 2f);
    }

    /** Scale with a magnetic stop (haptic) at 1.0x. */
    private float resolveScale(float raw) {
        float s = clampScale(raw);
        boolean near = Math.abs(s - 1f) < 0.04f;
        if (near) s = 1f;
        if (near && !scaleSnapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        scaleSnapped = near;
        return s;
    }

    /** Rotation with magnetic stops every 45° (±4°) — tick on entry. Returns the (unnormalised) angle to apply. */
    private float resolveRotation(float startRot, float dAng) {
        float raw = startRot + dAng;
        float rn = normDeg(raw);
        float nearest = Math.round(rn / 45f) * 45f;
        boolean snap = Math.abs(rn - nearest) < 4f;
        if (snap && !rotSnapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        rotSnapped = snap;
        return snap ? raw + (nearest - rn) : raw;
    }

    // ── Corner handle: one-finger resize + rotate (bottom-right, only while selected) ──
    // v650: the handle keeps a usable ON-SCREEN size at every sticker scale. Visual radius is 11dp
    // (never below 8dp, shrunk only for very small cards); the touch zone is never below 32dp wide.
    private static final float HANDLE_R_DP = 11f, HANDLE_R_MIN_DP = 8f, HANDLE_TOUCH_MIN_DP = 32f;
    private final float[] handleG = new float[4];   // {cx, cy, visualRadius, hitRadius} in LOCAL px

    private float[] handleGeom() {
        float w = getWidth(), h = getHeight();
        float sc = Math.max(0.15f, stickerScale);
        float k = 1f / sc;
        float d = dpf(getContext());
        float rScreen = Math.max(HANDLE_R_MIN_DP * d, Math.min(HANDLE_R_DP * d, Math.min(w, h) * sc * 0.22f));
        float r = rScreen * k;
        float pad = d * 2f * k;
        float hitScreen = Math.max(rScreen * 1.8f, HANDLE_TOUCH_MIN_DP * d * 0.5f);
        handleG[0] = Math.max(r, w - r - pad);
        handleG[1] = Math.max(r, h - r - pad);
        handleG[2] = r;
        handleG[3] = hitScreen * k;
        return handleG;
    }
    private boolean inHandle(float x, float y) {
        if (locked) return false;
        float[] g = handleGeom();
        float dx = x - g[0], dy = y - g[1], rr = g[3];
        return dx * dx + dy * dy <= rr * rr;
    }
    private void handleBegin(MotionEvent e) {
        float[] p = new float[2];
        toParent(e, 0, p);
        float cx = getX() + getWidth() / 2f, cy = getY() + getHeight() / 2f;
        hStartDist = Math.max(1f, (float) Math.hypot(p[0] - cx, p[1] - cy));
        hStartAngle = (float) Math.toDegrees(Math.atan2(p[1] - cy, p[0] - cx));
        hStartScale = stickerScale;
        hStartRot = stickerRotationDeg;
        hLastDAng = 0f;
        rotSnapped = false;
        scaleSnapped = false;
    }
    private void handleUpdate(ViewGroup parent, float px, float py) {
        float cx = getX() + getWidth() / 2f, cy = getY() + getHeight() / 2f;
        float dist = Math.max(1f, (float) Math.hypot(px - cx, py - cy));
        float ang = (float) Math.toDegrees(Math.atan2(py - cy, px - cx));
        float dAng = ang - hStartAngle;
        while (dAng - hLastDAng > 180f) dAng -= 360f;
        while (dAng - hLastDAng < -180f) dAng += 360f;
        hLastDAng = dAng;
        float scale = resolveScale(hStartScale * dist / hStartDist);
        float rot = resolveRotation(hStartRot, dAng);
        applyScale(scale);
        applyRotation(rot);
        updateRotChrome(parent, rotSnapped, rot, Math.abs(dAng) > 2f);
    }

    // ── Selection chrome (dotted border + handle + lock badge) — never drawn into snapshots ──
    private Paint ringPaint, handleFill, handleStroke, handleGlyph, lockPaint;
    private float ringK = -1f;
    private final RectF ringRect = new RectF();
    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (selectedUi && canvas.isHardwareAccelerated() && getWidth() > 0) drawSelectionChrome(canvas);
    }
    private void drawSelectionChrome(Canvas c) {
        float d = dpf(getContext());
        float k = 1f / Math.max(0.15f, stickerScale);
        if (ringPaint == null) {
            ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setColor(0xF2FFFFFF);
            handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            handleFill.setColor(Color.WHITE);
            handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            handleStroke.setStyle(Paint.Style.STROKE);
            handleStroke.setColor(0xFFFF3B7A);
            handleGlyph = new Paint(Paint.ANTI_ALIAS_FLAG);
            handleGlyph.setStyle(Paint.Style.STROKE);
            handleGlyph.setStrokeCap(Paint.Cap.ROUND);
            handleGlyph.setColor(0xFF222222);
            lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            lockPaint.setTextAlign(Paint.Align.RIGHT);
        }
        if (Math.abs(k - ringK) > 0.001f) {
            ringK = k;
            ringPaint.setStrokeWidth(d * 1.5f * k);
            ringPaint.setPathEffect(new DashPathEffect(new float[]{d * 6f * k, d * 4f * k}, 0f));
            handleStroke.setStrokeWidth(d * 1.5f * k);
            handleGlyph.setStrokeWidth(d * 1.6f * k);
        }
        float w = getWidth(), h = getHeight(), in = d * 1.5f * k, rad = d * 14f * k;
        ringRect.set(in, in, w - in, h - in);
        c.drawRoundRect(ringRect, rad, rad, ringPaint);
        if (locked) {
            lockPaint.setTextSize(d * 15f * k);
            c.drawText("\uD83D\uDD12", w - d * 8f * k, d * 22f * k, lockPaint);
            return;
        }
        float[] g = handleGeom();
        c.drawCircle(g[0], g[1], g[2], handleFill);
        c.drawCircle(g[0], g[1], g[2], handleStroke);
        float a = g[2] * 0.5f, t = g[2] * 0.38f;
        c.drawLine(g[0] - a, g[1] - a, g[0] + a, g[1] + a, handleGlyph);          // diagonal ⤡
        c.drawLine(g[0] + a, g[1] + a, g[0] + a - t, g[1] + a, handleGlyph);
        c.drawLine(g[0] + a, g[1] + a, g[0] + a, g[1] + a - t, handleGlyph);
        c.drawLine(g[0] - a, g[1] - a, g[0] - a + t, g[1] - a, handleGlyph);
        c.drawLine(g[0] - a, g[1] - a, g[0] - a, g[1] - a + t, handleGlyph);
    }

    // ── Rotation (two-finger twist, mirrors the pinch-to-scale gesture) ────
    private float stickerRotationDeg = 0f;
    public float getStickerRotationDeg() { return stickerRotationDeg; }
    /** Applies (and persists via {@link #toJsonWithScale()}) a new rotation, in degrees. */
    public void applyRotation(float degrees) {
        // Normalise into -180..180 so it never accumulates into huge numbers
        // across many gestures, while still spinning freely either direction.
        float d = degrees % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        stickerRotationDeg = d;
        setRotation(stickerRotationDeg);
    }

    // ── Removal (long-press OR drag-to-trash) ───────────────────────────────
    /** Fired once this sticker has actually been removed from its parent (either gesture). */
    public interface OnStickerRemovedListener { void onRemoved(StatusStickerOverlayView sticker); }
    private OnStickerRemovedListener removedListener;
    /**
     * Lets a host screen react to removal (e.g. drop it from its own tracking
     * list, rebuild sticker JSON, clear a toolbar badge) WITHOUT re-attaching
     * its own long-click listener — {@link #attachDragToParent} already owns
     * both removal gestures (long-press and drag-to-trash) so hosts never
     * need to reimplement "remove + rebuild" themselves.
     */
    public void setOnStickerRemovedListener(OnStickerRemovedListener l) { this.removedListener = l; }

    // ── v651: undo support for delete / add ──────────────────────────────────
    private float[] preRemovalState, preRemovalOverride;
    private int preRemovalIndex = -1;
    private String lastCountdownTarget;
    private TextView[] lastCountdownViews;

    /** {x, y, scale, rot} the sticker had right before its last removal (null if it was never removed). */
    public float[] getPreRemovalState() { return preRemovalState; }
    /** z-index inside the parent right before the last removal (-1 if unknown). */
    public int getPreRemovalIndex() { return preRemovalIndex; }

    /** Removes this sticker with the normal shrink animation (toolbar delete button). Locked stickers are ignored. */
    public void removeWithAnimation() {
        if (locked || removing || !(getParent() instanceof ViewGroup)) return;
        removeAnimated((ViewGroup) getParent(), true, null);
    }

    /**
     * Brings a removed sticker back (call AFTER adding it to its parent again): clears the removal flags,
     * restores pose + visibility once it has been laid out, restarts a countdown and pops it in.
     */
    public void reviveAt(float[] st) {
        removing = false;
        animate().cancel();
        nudgeTargetX = nudgeTargetY = Float.NaN;
        final float[] state = (st != null && st.length >= 4) ? st : captureState();
        stickerScale = clampScale(state[2]);
        stickerRotationDeg = normDeg(state[3]);
        setRotation(stickerRotationDeg);
        setScaleX(stickerScale * 0.7f);
        setScaleY(stickerScale * 0.7f);
        setAlpha(0f);
        if (lastCountdownViews != null && countdownTimer == null) startCountdown(lastCountdownTarget, lastCountdownViews);
        getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (getWidth() == 0) return true;
                getViewTreeObserver().removeOnPreDrawListener(this);
                setX(state[0]);
                setY(state[1]);
                animate().scaleX(stickerScale).scaleY(stickerScale).alpha(1f).setDuration(200)
                        .setInterpolator(new OvershootInterpolator(1.2f)).start();
                return true;
            }
        });
        invalidate();
    }

    /** Shared removal path for both the long-press and drag-to-trash gestures. */
    private void removeAnimated(final ViewGroup parent, boolean withHaptic) {
        removeAnimated(parent, withHaptic, null);
    }

    /** Same, but when {@code suckTarget} is given the sticker is pulled into it while shrinking + spinning. */
    private void removeAnimated(final ViewGroup parent, boolean withHaptic, View suckTarget) {
        // v651: remember where this sticker was (state + z-index) so the composer can undo the delete.
        preRemovalState = preRemovalOverride != null ? preRemovalOverride : captureState();
        preRemovalOverride = null;
        preRemovalIndex = parent.indexOfChild(this);
        removing = true;
        if (withHaptic) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (countdownTimer != null) { countdownTimer.cancel(); countdownTimer = null; }
        animate().cancel();
        android.view.ViewPropertyAnimator a = animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(180)
            .setInterpolator(new android.view.animation.AccelerateInterpolator());
        if (suckTarget != null && suckTarget.getParent() == parent) {
            float tx = suckTarget.getX() + suckTarget.getWidth() / 2f - getWidth() / 2f;
            float ty = suckTarget.getY() + suckTarget.getHeight() / 2f - getHeight() / 2f;
            a.x(tx).y(ty).rotationBy(120f).setDuration(240);
        }
        a.withEndAction(() -> {
            parent.removeView(this);
            if (removedListener != null) removedListener.onRemoved(this);
        }).start();
    }

    // ── Viewer "tap to zoom, react to return" gate ───────────────────────────
    // Used only by the status VIEWER (never the composer): a viewer's first
    // tap enlarges the sticker to the middle of the screen — front and centre,
    // above everything else — so they can read/answer it properly, and the
    // story pauses for as long as it stays enlarged. Whatever counts as the
    // viewer's "reaction" for that sticker type (a quiz pick, a poll vote, a
    // slider release, a countdown toggle, or handing off to an external
    // sheet/screen) then shrinks it back to exactly the spot the poster
    // dropped it at and lets the story resume.
    private boolean zoomGateArmed;
    private boolean zoomedIn;
    private float zoomBaseX, zoomBaseY, zoomBaseScaleX = 1f, zoomBaseScaleY = 1f;
    private GestureDetector zoomGateTapDetector;

    /** True once {@link #zoomToFront} has enlarged this sticker and before {@link #restoreFromZoom} runs. */
    public boolean isZoomedIn() { return zoomedIn; }

    /**
     * Arms the tap-to-zoom gate: until the sticker is zoomed in, this view
     * swallows every touch itself (so a tap never lands directly on an inner
     * option/button underneath) and fires {@code onFirstTap} for a clean tap.
     * Once zoomed in, the gate steps aside so the real controls inside the
     * card (quiz options, poll choices, the slider, the countdown row, …)
     * receive taps normally — that follow-up tap is the viewer's "reaction".
     */
    public void armViewerZoomGate(Runnable onFirstTap) {
        zoomGateArmed = true;
        zoomGateTapDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapUp(MotionEvent e) {
                if (onFirstTap != null) onFirstTap.run();
                return true;
            }
        });
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (zoomGateArmed && !zoomedIn) return true; // steal every touch until zoomed in
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (zoomGateArmed && !zoomedIn) {
            if (zoomGateTapDetector != null) zoomGateTapDetector.onTouchEvent(event);
            if ("music".equals(stickerType)) { // light press feedback before the zoom-in
                int a = event.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) setAlpha(0.85f);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) setAlpha(1f);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * Enlarges this sticker and brings it in front of everything else in
     * {@code overlayParent}, centred on screen so the viewer can read it
     * properly. Remembers the exact spot/scale it started at so
     * {@link #restoreFromZoom} can put it back precisely. No-op if already
     * zoomed in.
     */
    public void zoomToFront(final ViewGroup overlayParent, final Runnable onZoomed) {
        if (zoomedIn) { if (onZoomed != null) onZoomed.run(); return; }
        zoomedIn = true;
        zoomBaseX = getX();
        zoomBaseY = getY();
        zoomBaseScaleX = getScaleX();
        zoomBaseScaleY = getScaleY();
        overlayParent.bringChildToFront(this);
        overlayParent.invalidate();
        setElevation(24f * getContext().getResources().getDisplayMetrics().density);
        final float targetScale = Math.min(SCALE_MAX * viewerFitK, Math.max(stickerScale, SCALE_MEDIUM) * 1.35f * viewerFitK);
        overlayParent.post(() -> {
            float targetX = (overlayParent.getWidth()  - getWidth())  / 2f;
            float targetY = (overlayParent.getHeight() - getHeight()) / 2f;
            animate().x(targetX).y(targetY).scaleX(targetScale).scaleY(targetScale)
                    .setDuration(260).setInterpolator(new OvershootInterpolator(0.9f))
                    .withEndAction(() -> { if (onZoomed != null) onZoomed.run(); })
                    .start();
        });
    }

    /**
     * Shrinks a zoomed-in sticker back to the exact spot/scale the poster
     * placed it at. No-op if it isn't currently zoomed in.
     */
    public void restoreFromZoom(final Runnable onRestored) {
        if (!zoomedIn) { if (onRestored != null) onRestored.run(); return; }
        animate().x(zoomBaseX).y(zoomBaseY).scaleX(zoomBaseScaleX).scaleY(zoomBaseScaleY)
                .setDuration(220).setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> {
                    zoomedIn = false;
                    setElevation(0f);
                    if (onRestored != null) onRestored.run();
                })
                .start();
    }


    // ── Music sticker metadata (links back to the Reels trending-audio track) ──
    private com.callx.app.views.MusicTickerView musicTicker;
    private boolean musicPlaying = true; // viewer/composer drive this via setMusicPlaying()
    private final java.util.List<View> eqBars = new java.util.ArrayList<>();
    private final java.util.List<android.animation.ObjectAnimator> eqAnimators = new java.util.ArrayList<>();
    private String musicSoundId, musicSong, musicArtist, musicCoverUrl, musicSoundUrl;
    // Optional clip range inside the song (ms) — from the editor's music trim. 0 = not set.
    private int musicStartMs = 0, musicEndMs = 0;
    // Optional Mix Audio settings (Reels' mixer, reused for Status). -1 = never set → viewers keep
    // their legacy behaviour (video ducked to 0.25, music at default volume). Old posts have none.
    private float musicOrigVol = -1f, musicMixVol = -1f, musicPitch = 0f;
    private int musicFadeInMs = 0, musicFadeOutMs = 0;

    // ── Quiz interactivity state (mirrors the question sticker's full flow) ──
    private String quizQuestion;
    private java.util.List<String> quizOptions;
    private java.util.List<Boolean> quizCorrects;
    private final java.util.List<TextView> quizOptionViews = new java.util.ArrayList<>();
    private boolean quizAnswered = false;
    private OnQuizOptionSelectedListener quizListener;

    /** Fired when a viewer taps an unanswered quiz option. Index is 0-based into getQuizOptions(). */
    public interface OnQuizOptionSelectedListener {
        void onOptionSelected(int index);
    }

    // ── Countdown "Remind me" subscription state ─────────────────────────────
    private String countdownLabel;
    private boolean countdownSubscribed = false;
    private boolean countdownExpired = false;
    private LinearLayout countdownSubscribeRow;
    private TextView countdownBellIcon;
    private TextView countdownSubscribeLabel;
    private OnCountdownSubscribeToggleListener countdownListener;

    /** Fired when a viewer taps the ⏳ Countdown card's "Remind me" row. newState = subscribed or not. */
    public interface OnCountdownSubscribeToggleListener {
        void onToggle(boolean nowSubscribed);
    }

    // ── Poll interactivity state ─────────────────────────────────────────────
    private String pollQuestion, pollOptionA, pollOptionB;
    private boolean pollAnswered = false;
    private TextView pollOptViewA, pollOptViewB;
    private View pollFillA, pollFillB;
    private View pollContainerA, pollContainerB;
    private TextView pollPctA, pollPctB;
    private OnPollOptionSelectedListener pollListener;

    /** Fired when a viewer taps an unanswered 🗳️ Poll option. option is "A" or "B". */
    public interface OnPollOptionSelectedListener {
        void onOptionSelected(String option);
    }

    // ── Slider interactivity state ───────────────────────────────────────────
    private String sliderQuestion, sliderEmoji;
    private boolean sliderAnswered = false;
    private SeekBar sliderBar;
    private TextView sliderValueLabel, sliderThumbEmoji, sliderAvgLabel;
    private OnSliderValueSubmittedListener sliderListener;

    /** Fired once a viewer releases the 🎚️ Slider thumb. value is 0-100. */
    public interface OnSliderValueSubmittedListener {
        void onSubmitted(int value);
    }

    // ── Mention sticker data (links to a tagged user's profile) ─────────────
    private String mentionUsername;

    // ── Hashtag sticker data (links to the topic's feed) ────────────────────
    private String hashtagTag;

    // ── Link sticker data (opens an external URL in the browser) ────────────
    private String linkUrl, linkLabel;

    // ── Add Yours sticker data (prompt + chain-origin attribution) ──────────
    private String addYoursPrompt, addYoursOriginUid, addYoursOriginName;
    // v578: chain id (counter / chain screen), template JSON carried along the chain, live "N added" label
    private String addYoursChainId, addYoursTemplateJson;
    private TextView tvAddYoursCount;

    private StatusStickerOverlayView(Context ctx) {
        super(ctx);
        setOrientation(VERTICAL);
        setClickable(true);
        setFocusable(true);
    }

    /** Sticker type used by {@link #wrapContent} for the composer's text layer. */
    public static final String TYPE_TEXT = "text";

    /**
     * v650: wraps an arbitrary content view (the composer's text overlay) in the SAME transform engine every
     * sticker uses — snap guides (frame centre + other stickers), corner resize/rotate handle with a minimum
     * touch size, undo/redo, lock, trash drop, canvas-wide pinch. The wrapper has no padding / background of
     * its own, so its bounds are exactly the content's bounds.
     */
    public static StatusStickerOverlayView wrapContent(Context ctx, View content, String type) {
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        v.stickerType = type;
        v.stickerJson = "{\"type\":\"" + type + "\"}";
        return v;
    }

    /** The sticker's type — "music"/"countdown"/"quiz"/"question". */
    public String getStickerType() { return stickerType; }

    /** For a "question" sticker, the prompt the poster typed (e.g. "Ask me anything!"). Null for other types. */
    public String getQuestionPrompt() {
        return "question".equals(stickerType) ? jsonStr(stickerJson, "prompt", "Ask me anything!") : null;
    }

    // ── Music sticker linking (tap → open the Reels Sound Detail sheet) ─────

    /** The Reels sound/track id this sticker points to, or null/empty if it isn't linked to one. */
    public String getMusicSoundId() { return "music".equals(stickerType) ? musicSoundId : null; }

    /** Song title shown on the card, or null for non-music stickers. */
    public String getMusicSong() { return "music".equals(stickerType) ? musicSong : null; }

    /** Artist name shown on the card, or null for non-music stickers. */
    public String getMusicArtist() { return "music".equals(stickerType) ? musicArtist : null; }

    /** Album art / cover URL, or null for non-music stickers. */
    public String getMusicCoverUrl() { return "music".equals(stickerType) ? musicCoverUrl : null; }

    /** Playable audio URL for the linked track, or null for non-music stickers. */
    public String getMusicSoundUrl() { return "music".equals(stickerType) ? musicSoundUrl : null; }
    /** Start (ms) of the part of the song to play, or 0 if the poster never set one. */
    public int getMusicStartMs() { return "music".equals(stickerType) ? musicStartMs : 0; }
    /** End (ms) of the part of the song to play, or 0 if not set. */
    public int getMusicEndMs()   { return "music".equals(stickerType) ? musicEndMs : 0; }

    /** True if the poster used Mix Audio (any of origVol/musicVol/fade/pitch present in the JSON). */
    public boolean hasMusicMix() {
        return "music".equals(stickerType)
            && (musicOrigVol >= 0f || musicMixVol >= 0f
                || musicFadeInMs > 0 || musicFadeOutMs > 0 || musicPitch != 0f);
    }
    /** Original (video) audio volume 0..1 chosen in Mix Audio, or -1 if never set. */
    public float getMusicOrigVol()   { return "music".equals(stickerType) ? musicOrigVol : -1f; }
    /** Music volume 0..1 chosen in Mix Audio, or -1 if never set. */
    public float getMusicMixVol()    { return "music".equals(stickerType) ? musicMixVol : -1f; }
    /** Fade-in length (ms) at the start of each music loop, 0 = none. */
    public int   getMusicFadeInMs()  { return "music".equals(stickerType) ? musicFadeInMs : 0; }
    /** Fade-out length (ms) at the end of each music loop, 0 = none. */
    public int   getMusicFadeOutMs() { return "music".equals(stickerType) ? musicFadeOutMs : 0; }
    /** Pitch shift in semitones (-6..+6), 0 = unchanged. */
    public float getMusicPitch()     { return "music".equals(stickerType) ? musicPitch : 0f; }

    /** True once this music sticker is linked to a real Reels track the viewer can open. */
    public boolean isMusicLinkedToReelSound() {
        return "music".equals(stickerType) && musicSoundId != null && !musicSoundId.isEmpty();
    }

    // ── Quiz interactive flow ────────────────────────────────────────────────

    /** The quiz's question text, or null for non-quiz stickers. */
    public String getQuizQuestion() { return "quiz".equals(stickerType) ? quizQuestion : null; }

    /** The quiz's option labels in order, or null for non-quiz stickers. */
    public java.util.List<String> getQuizOptions() { return "quiz".equals(stickerType) ? quizOptions : null; }

    /** Index of the correct option, or -1 if unknown/not a quiz. */
    public int getQuizCorrectIndex() {
        if (quizCorrects == null) return -1;
        for (int i = 0; i < quizCorrects.size(); i++) {
            if (Boolean.TRUE.equals(quizCorrects.get(i))) return i;
        }
        return -1;
    }

    /** True once this viewer has answered (tapped an option, or had a prior answer restored). */
    public boolean isQuizAnswered() { return quizAnswered; }

    /** Wires the tap-to-answer callback for an unanswered quiz sticker. No-op once answered. */
    public void setOnQuizOptionSelectedListener(OnQuizOptionSelectedListener listener) {
        this.quizListener = listener;
    }

    /**
     * Locks the quiz card into its answered state: disables further taps and
     * highlights the correct option green, a wrong selection red, and dims the rest —
     * mirrors what viewers already expect from the ✓/✗ pattern IG-style quiz stickers use.
     * Safe to call to restore a previously-recorded answer (e.g. on reopening the status).
     */
    public void revealQuizAnswer(int selectedIndex) {
        if (quizOptionViews.isEmpty() || quizOptions == null) return;
        quizAnswered = true;
        int correctIdx = getQuizCorrectIndex();
        int dp = dp(getContext());
        for (int i = 0; i < quizOptionViews.size(); i++) {
            TextView opt = quizOptionViews.get(i);
            opt.setOnClickListener(null);
            opt.setClickable(false);
            boolean isCorrect = (i == correctIdx);
            boolean isWrongSelected = (i == selectedIndex && i != correctIdx);
            String label = i < quizOptions.size() ? quizOptions.get(i) : "";
            opt.setText((isCorrect ? "\u2713 " : isWrongSelected ? "\u2717 " : "") + label);

            android.graphics.drawable.GradientDrawable optBg = new android.graphics.drawable.GradientDrawable();
            optBg.setCornerRadius(dp * 10);
            if (isCorrect) {
                optBg.setColor(0xFF2ECC71);
            } else if (isWrongSelected) {
                optBg.setColor(0xFFE74C3C);
            } else {
                optBg.setColor(0x22FFFFFF);
                optBg.setStroke(1, 0x33FFFFFF);
            }
            opt.setBackground(optBg);
            opt.setTextColor(Color.WHITE);
            opt.setAlpha((isCorrect || isWrongSelected) ? 1f : 0.5f);
        }
    }

    // ── Countdown interactive flow ───────────────────────────────────────────

    /** The countdown's label text (e.g. "My Birthday 🎂"), or null for non-countdown stickers. */
    public String getCountdownLabel() { return "countdown".equals(stickerType) ? countdownLabel : null; }

    /** True once this viewer has subscribed for a reminder (or had a prior subscription restored). */
    public boolean isCountdownSubscribed() { return countdownSubscribed; }

    /** True once the countdown has reached zero. */
    public boolean isCountdownExpired() { return countdownExpired; }

    /** Wires the tap-to-subscribe/unsubscribe callback. No-op once the countdown has expired. */
    public void setOnCountdownSubscribeToggleListener(OnCountdownSubscribeToggleListener listener) {
        this.countdownListener = listener;
    }

    /**
     * Sets the subscribed/unsubscribed visual state without firing the toggle listener —
     * used to restore a viewer's prior subscription when the status is reopened.
     */
    public void setCountdownSubscribed(boolean subscribed) {
        this.countdownSubscribed = subscribed;
        updateCountdownSubscribeUi();
    }

    private void updateCountdownSubscribeUi() {
        if (countdownSubscribeRow == null) return;
        if (countdownExpired) {
            countdownBellIcon.setText("\u23F0"); // ⏰
            countdownSubscribeLabel.setText("Countdown ended");
            countdownSubscribeRow.setClickable(false);
            countdownSubscribeRow.setAlpha(0.6f);
            return;
        }
        countdownBellIcon.setText(countdownSubscribed ? "\uD83D\uDD14" : "\uD83D\uDD15"); // 🔔 / 🔕
        countdownSubscribeLabel.setText(countdownSubscribed ? "Reminder set \u2713" : "Remind me");
    }

    // ── Poll interactive flow ────────────────────────────────────────────────

    /** The poll's question text, or null for non-poll stickers. */
    public String getPollQuestion() { return "poll".equals(stickerType) ? pollQuestion : null; }

    /** Option A's label, or null for non-poll stickers. */
    public String getPollOptionA() { return "poll".equals(stickerType) ? pollOptionA : null; }

    /** Option B's label, or null for non-poll stickers. */
    public String getPollOptionB() { return "poll".equals(stickerType) ? pollOptionB : null; }

    /** True once this viewer has voted (tapped an option, or had a prior vote restored). */
    public boolean isPollAnswered() { return pollAnswered; }

    /** Wires the tap-to-vote callback for an unanswered poll. No-op once voted. */
    public void setOnPollOptionSelectedListener(OnPollOptionSelectedListener listener) {
        this.pollListener = listener;
    }

    /**
     * Locks the poll into its voted state: disables further taps, highlights this
     * viewer's pick, and fills each option's percentage bar from the live vote counts.
     * Safe to call to restore a previously-recorded vote (e.g. on reopening the status).
     */
    public void revealPollResult(String selectedOption, int countA, int countB) {
        if (pollOptViewA == null || pollOptViewB == null) return;
        pollAnswered = true;
        int total = countA + countB;
        int pctA = total > 0 ? Math.round(countA * 100f / total) : 50;
        int pctB = total > 0 ? 100 - pctA : 50;

        if (pollContainerA != null) { pollContainerA.setOnClickListener(null); pollContainerA.setClickable(false); }
        if (pollContainerB != null) { pollContainerB.setOnClickListener(null); pollContainerB.setClickable(false); }

        pollPctA.setText(pctA + "%");
        pollPctB.setText(pctB + "%");
        pollPctA.setVisibility(VISIBLE);
        pollPctB.setVisibility(VISIBLE);

        ViewGroup.LayoutParams fillLpA = pollFillA.getLayoutParams();
        if (fillLpA instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) fillLpA).weight = Math.max(pctA, 1);
        ViewGroup.LayoutParams fillLpB = pollFillB.getLayoutParams();
        if (fillLpB instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) fillLpB).weight = Math.max(pctB, 1);
        pollFillA.requestLayout();
        pollFillB.requestLayout();

        boolean pickedA = "A".equals(selectedOption);
        pollOptViewA.setAlpha(pickedA ? 1f : 0.6f);
        pollOptViewB.setAlpha(!pickedA ? 1f : 0.6f);
        TextView pickedView = pickedA ? pollOptViewA : pollOptViewB;
        pickedView.setText("\u2713 " + pickedView.getText());
    }

    // ── Slider interactive flow ──────────────────────────────────────────────

    /** The slider's question/prompt text, or null for non-slider stickers. */
    public String getSliderQuestion() { return "slider".equals(stickerType) ? sliderQuestion : null; }

    /** The slider's emoji thumb, or null for non-slider stickers. */
    public String getSliderEmoji() { return "slider".equals(stickerType) ? sliderEmoji : null; }

    /** The tagged username (no leading @), or null for non-mention stickers. */
    public String getMentionUsername() { return "mention".equals(stickerType) ? mentionUsername : null; }

    /** The tagged topic (no leading #), or null for non-hashtag stickers. */
    public String getHashtagTag() { return "hashtag".equals(stickerType) ? hashtagTag : null; }

    // ── Link sticker linking (tap → open the URL in the browser) ────────────

    /** The full URL this sticker points to (always includes a scheme), or null for non-link stickers. */
    public String getLinkUrl() { return "link".equals(stickerType) ? linkUrl : null; }

    /** The custom label text the poster typed, or "" if they left it blank. Null for non-link stickers. */
    public String getLinkLabel() { return "link".equals(stickerType) ? linkLabel : null; }

    // ── Add Yours chain flow ─────────────────────────────────────────────────

    /** The prompt text (e.g. "My study era 📚"), or null for non-addyours stickers. */
    public String getAddYoursPrompt() { return "addyours".equals(stickerType) ? addYoursPrompt : null; }

    /** The uid of whoever started this chain, or "" if this status IS the origin. Null for other types. */
    public String getAddYoursOriginUid() { return "addyours".equals(stickerType) ? addYoursOriginUid : null; }

    /** The display name of whoever started this chain, or "" if this status IS the origin. Null for other types. */
    public String getAddYoursOriginName() { return "addyours".equals(stickerType) ? addYoursOriginName : null; }

    /** Chain id shared by every story in this Add Yours chain, or "" for stickers made before v578. Null for other types. */
    public String getAddYoursChainId() { return "addyours".equals(stickerType) ? addYoursChainId : null; }

    /** Template JSON ({bgColor,fontStyle,textColor,textAlign,scale,...}) the chain starter shared, or "" if none. Null for other types. */
    public String getAddYoursTemplateJson() { return "addyours".equals(stickerType) ? addYoursTemplateJson : null; }

    /** Updates the "N added" label (hidden while the count is 0). */
    public void setAddYoursCount(long count) {
        if (tvAddYoursCount == null) return;
        if (count <= 0) { tvAddYoursCount.setVisibility(View.GONE); return; }
        tvAddYoursCount.setText(getResources().getQuantityString(
                com.callx.app.core.R.plurals.add_yours_sticker_count, (int) Math.min(count, Integer.MAX_VALUE), count));
        tvAddYoursCount.setVisibility(View.VISIBLE);
    }

    /** Viewer only: tapping the "N added" label opens the chain screen (the composer leaves it non-clickable so drag still works). */
    public void setOnAddYoursCountClick(final Runnable r) {
        if (tvAddYoursCount == null) return;
        tvAddYoursCount.setOnClickListener(r == null ? null : v -> r.run());
    }

    /** True once this viewer has released the slider (or had a prior value restored). */
    public boolean isSliderAnswered() { return sliderAnswered; }

    /** Wires the release-to-submit callback for an unanswered slider. No-op once submitted. */
    public void setOnSliderValueSubmittedListener(OnSliderValueSubmittedListener listener) {
        this.sliderListener = listener;
    }

    /**
     * Locks the slider at myValue and shows the live average (0-100) as a small emoji
     * marker on the track. Safe to call to restore a previously-recorded response.
     */
    public void revealSliderAverage(int myValue, int avgValue) {
        if (sliderBar == null) return;
        sliderAnswered = true;
        sliderBar.setProgress(myValue);
        sliderBar.setEnabled(false);
        sliderValueLabel.setText(myValue + "%");
        if (sliderAvgLabel != null) {
            sliderAvgLabel.setVisibility(VISIBLE);
            sliderAvgLabel.setText("Avg " + avgValue + "%");
        }
    }

    // ── Factory ────────────────────────────────────────────────────────────

    /**
     * Create the appropriate sticker card from a JSON config produced by
     * {@link StatusStickerPickerSheet}.
     */
    public static StatusStickerOverlayView fromJson(Context ctx, String json) {
        String type = jsonStr(json, "type", "");
        StatusStickerOverlayView v;
        switch (type) {
            case "music":     v = buildMusic(ctx, json); break;
            case "countdown": v = buildCountdown(ctx, json); break;
            case "quiz":      v = buildQuiz(ctx, json); break;
            case "question":  v = buildQuestion(ctx, json); break;
            case "poll":      v = buildPoll(ctx, json); break;
            case "slider":    v = buildSlider(ctx, json); break;
            case "mention":   v = buildMention(ctx, json); break;
            case "hashtag":   v = buildHashtag(ctx, json); break;
            case "link":      v = buildLink(ctx, json); break;
            case "addyours":  v = buildAddYours(ctx, json); break;
            default:          v = buildQuestion(ctx, json); type = "question"; break;
        }
        v.stickerType = type;
        v.stickerJson = json;

        // BUG FIX: this used to read the saved scale with jsonStr(), a helper
        // that only matches QUOTED string values ("key":"value"). toJsonWithScale()
        // writes scale as a bare JSON number (o.put("scale", floatValue) →
        // "scale":1.2, no quotes), so jsonStr() could never find it and silently
        // fell back to the "1.0" default every time — every sticker reopened (or
        // reloaded in the viewer) reset to medium size regardless of what the
        // user pinched it to before posting. Use the numeric-aware jsonNum().
        float savedScale = SCALE_MEDIUM;
        try { savedScale = Float.parseFloat(jsonNum(json, "scale", "1.0")); } catch (Exception ignored) {}
        v.applyScale(savedScale);

        // Saved finger-rotation (degrees), same numeric-JSON pattern as scale above.
        float savedRotation = 0f;
        try { savedRotation = Float.parseFloat(jsonNum(json, "rotationDeg", "0")); } catch (Exception ignored) {}
        v.applyRotation(savedRotation);
        v.locked = json != null && json.replace(" ", "").contains("\"locked\":true");   // v651

        // Saved finger-adjusted position (posXRatio/posYRatio, 0..1 relative to
        // the parent frame) baked in by toJsonWithScale() at post-time. -1 means
        // this sticker JSON has no saved position (e.g. a legacy status posted
        // before this fix) — callers should fall back to their own default
        // placement in that case, via hasSavedPosition().
        try {
            v.savedPosXRatio = Float.parseFloat(jsonNum(json, "posXRatio", "-1"));
            v.savedPosYRatio = Float.parseFloat(jsonNum(json, "posYRatio", "-1"));
        } catch (Exception ignored) {
            v.savedPosXRatio = -1f;
            v.savedPosYRatio = -1f;
        }
        // v650: centre-based placement + the composer canvas it was made on (viewer maps it into the same 9:16 box).
        try {
            v.savedCxRatio = Float.parseFloat(jsonNum(json, "cxRatio", "-1"));
            v.savedCyRatio = Float.parseFloat(jsonNum(json, "cyRatio", "-1"));
            v.savedFrameWdp = Float.parseFloat(jsonNum(json, "frameWdp", "0"));
            v.savedFrameHdp = Float.parseFloat(jsonNum(json, "frameHdp", "0"));
            v.savedWdp = Float.parseFloat(jsonNum(json, "wDp", "0"));
        } catch (Exception ignored) {
            v.savedCxRatio = v.savedCyRatio = -1f;
            v.savedFrameWdp = v.savedFrameHdp = v.savedWdp = 0f;
        }
        return v;
    }

    // ── v650 centre placement (posted by the v650+ composer; older posts only have posXRatio/posYRatio) ──
    private float viewerFitK = 1f;   // viewer-only: box width / composer canvas width (zoom-to-front keeps this ratio)
    private float savedCxRatio = -1f, savedCyRatio = -1f, savedFrameWdp, savedFrameHdp, savedWdp;
    /** True when the post carries centre ratios + the composer canvas size (see {@link #toJsonWithScale()}). */
    public boolean hasCenterPlacement() {
        return savedCxRatio >= 0f && savedCyRatio >= 0f && savedFrameWdp > 0f && savedFrameHdp > 0f && savedWdp > 0f;
    }
    public float getSavedCxRatio() { return savedCxRatio; }
    public float getSavedCyRatio() { return savedCyRatio; }
    /** Composer canvas size (dp) this sticker was placed on. */
    public float getSavedFrameWdp() { return savedFrameWdp; }
    public float getSavedFrameHdp() { return savedFrameHdp; }
    /** Layout width (dp) the card had in the composer — the viewer re-uses it so text wraps identically. */
    public float getSavedWdp() { return savedWdp; }

    /**
     * Viewer: scales the card by {@code k} (viewer box width / composer canvas width) and, once laid out, puts its
     * CENTRE at (cx, cy) px of the parent — centre clamped to the parent like the composer does. Runs in a pre-draw
     * pass so the sticker never flashes at a wrong spot. Pivot stays the centre, so scale + rotation line up.
     */
    public void placeCenteredOnceLaidOut(final float cx, final float cy, float k, final float parentW, final float parentH) {
        final float kk = (k > 0.05f && k < 20f) ? k : 1f;
        viewerFitK = kk;
        setScaleX(stickerScale * kk);
        setScaleY(stickerScale * kk);
        getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (getWidth() == 0 || getHeight() == 0) return true;
                getViewTreeObserver().removeOnPreDrawListener(this);
                float x = Math.max(0f, Math.min(parentW, cx));
                float y = Math.max(0f, Math.min(parentH, cy));
                setX(x - getWidth() / 2f);
                setY(y - getHeight() / 2f);
                return true;
            }
        });
    }

    // ── Saved position (where the user's finger left it in the composer) ────
    private float savedPosXRatio = -1f, savedPosYRatio = -1f;
    /** True if this sticker was posted with a finger-adjusted position saved. */
    public boolean hasSavedPosition() { return savedPosXRatio >= 0f && savedPosYRatio >= 0f; }
    /** X position as a 0..1 ratio of the parent frame's width. Only valid if {@link #hasSavedPosition()}. */
    public float getSavedPosXRatio() { return savedPosXRatio; }
    /** Y position as a 0..1 ratio of the parent frame's height. Only valid if {@link #hasSavedPosition()}. */
    public float getSavedPosYRatio() { return savedPosYRatio; }

    /** Clamps and applies a new size, both visually and for persistence via {@link #toJsonWithScale()}. */
    public void applyScale(float scale) {
        stickerScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale));
        setScaleX(stickerScale);
        setScaleY(stickerScale);
        if (selectedUi) invalidate(); // keep the selection chrome the same on-screen size
    }

    /** Animated variant used by the Small/Medium/Large preset buttons. */
    public void animateToScale(float scale) {
        stickerScale = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale));
        animate().scaleX(stickerScale).scaleY(stickerScale).setDuration(150)
            .setInterpolator(new DecelerateInterpolator()).start();
        if (selectedUi) invalidate();
    }

    /**
     * The original sticker JSON with the current size AND the current
     * finger-dragged position baked in — call this at post-time (after the
     * user has finished dragging/pinching it on the compose preview) so the
     * exact same size/position shows up again when the status is viewed.
     * Position is saved as a 0..1 ratio of the parent frame's width/height
     * (not raw pixels) so it still lines up correctly even if the viewer's
     * overlay frame ends up a different size than the composer's.
     */
    public String toJsonWithScale() {
        try {
            org.json.JSONObject o = new org.json.JSONObject(stickerJson != null ? stickerJson : "{}");
            o.put("scale", stickerScale);
            o.put("rotationDeg", stickerRotationDeg);
            if (locked) o.put("locked", true); else o.remove("locked");   // v651: lock survives a draft reopen (composer strips it from posts)
            if (getParent() instanceof View) {
                View parent = (View) getParent();
                if (parent.getWidth() > 0 && parent.getHeight() > 0) {
                    o.put("posXRatio", getX() / parent.getWidth());
                    o.put("posYRatio", getY() / parent.getHeight());
                    // v650: centre ratios + canvas size → the viewer maps the SAME 9:16 box and scales the card by width
                    float d = getResources().getDisplayMetrics().density;
                    o.put("cxRatio", (getX() + getWidth() / 2f) / parent.getWidth());
                    o.put("cyRatio", (getY() + getHeight() / 2f) / parent.getHeight());
                    o.put("frameWdp", parent.getWidth() / d);
                    o.put("frameHdp", parent.getHeight() / d);
                    o.put("wDp", getWidth() / d);
                }
            }
            return o.toString();
        } catch (Exception e) {
            return stickerJson != null ? stickerJson : "{}";
        }
    }
    // ─── Music sticker ─────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildMusic(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 12, dp * 12, dp * 12, dp * 12);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE000000);
        bg.setStroke(Math.max(1, dp), 0x44FFFFFF);
        v.setBackground(bg);

        // Row: album art + text
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Album art placeholder
        ImageView ivArt = new ImageView(ctx);
        ivArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        String artUrl = jsonStr(json, "albumArt", "");
        if (!artUrl.isEmpty()) {
            // Shared avatar pipeline (tiered CDN URL + L2/L3 cache + RGB_565, no circle crop).
            com.callx.app.cache.MiscAvatarBinder.bind(ctx, ivArt, artUrl, 0L,
                    com.callx.app.utils.AvatarSizeTier.forViewSizeDp(48),
                    com.callx.app.core.R.drawable.bg_music_cover_placeholder, false);
        } else {
            android.graphics.drawable.GradientDrawable artBg = new android.graphics.drawable.GradientDrawable();
            artBg.setColor(0xFF222222);
            artBg.setCornerRadius(dp * 8);
            ivArt.setBackground(artBg);
        }
        // Round the cover (image or placeholder) — clip so Glide-loaded art gets corners too.
        final float artRadius = dp * 8;
        ivArt.setClipToOutline(true);
        ivArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(android.view.View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), artRadius);
            }
        });
        // Music note overlay
        TextView tvNote = new TextView(ctx);
        tvNote.setText("🎵");
        tvNote.setTextSize(22);
        tvNote.setGravity(android.view.Gravity.CENTER);

        FrameLayout artFrame = new FrameLayout(ctx);
        FrameLayout.LayoutParams artLp = new FrameLayout.LayoutParams(dp * 48, dp * 48);
        artLp.rightMargin = dp * 12;
        ivArt.setLayoutParams(new FrameLayout.LayoutParams(dp * 48, dp * 48));
        artFrame.addView(ivArt);
        // Emoji only on the placeholder — never on top of real album art.
        if (artUrl.isEmpty()) artFrame.addView(tvNote);
        ivArt.setContentDescription(ctx.getString(com.callx.app.core.R.string.music_sticker_album_art));
        row.addView(artFrame, artLp);

        // Song + artist text
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        String song   = jsonStr(json, "song",   ctx.getString(com.callx.app.core.R.string.music_sticker_unknown_song));
        String artist = jsonStr(json, "artist", "");
        String soundId  = jsonStr(json, "soundId", "");
        String soundUrl = jsonStr(json, "soundUrl", "");
        v.musicSong = song;
        v.musicArtist = artist;
        v.musicCoverUrl = artUrl;
        v.musicSoundId = soundId;
        v.musicSoundUrl = soundUrl;
        // Optional clip range (bare JSON numbers → jsonNum, not jsonStr).
        try { v.musicStartMs = (int) Float.parseFloat(jsonNum(json, "startMs", "0")); } catch (Exception ignored) {}
        try { v.musicEndMs   = (int) Float.parseFloat(jsonNum(json, "endMs",   "0")); } catch (Exception ignored) {}
        // Optional Mix Audio fields — absent in old stickers, in which case the defaults stay.
        try { v.musicOrigVol   = clamp01(Float.parseFloat(jsonNum(json, "origVol",  "-1"))); } catch (Exception ignored) {}
        try { v.musicMixVol    = clamp01(Float.parseFloat(jsonNum(json, "musicVol", "-1"))); } catch (Exception ignored) {}
        try { v.musicFadeInMs  = Math.max(0, (int) Float.parseFloat(jsonNum(json, "fadeInMs",  "0"))); } catch (Exception ignored) {}
        try { v.musicFadeOutMs = Math.max(0, (int) Float.parseFloat(jsonNum(json, "fadeOutMs", "0"))); } catch (Exception ignored) {}
        try { v.musicPitch     = Math.max(-6f, Math.min(6f, Float.parseFloat(jsonNum(json, "pitch", "0")))); } catch (Exception ignored) {}

        // Looping ticker (same MusicTickerView as the reel/status name rows) so long
        // song names scroll instead of getting cut with "…". Fixed width — it is a
        // custom View that fills the width it is given. Short names stay static.
        // Text width, clamped to 130dp — short names no longer leave an empty gap.
        final float spPx = ctx.getResources().getDisplayMetrics().scaledDensity;
        android.graphics.Paint mp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        mp.setTypeface(Typeface.DEFAULT_BOLD);
        mp.setTextSize(14 * spPx);
        float w = mp.measureText(song);
        if (!artist.isEmpty()) {
            mp.setTypeface(Typeface.DEFAULT);
            mp.setTextSize(12 * spPx);
            w = Math.max(w, mp.measureText(artist));
        }
        final int textW = Math.max(dp * 40, Math.min(dp * 130, (int) Math.ceil(w) + dp * 2));
        com.callx.app.views.MusicTickerView tvSong = new com.callx.app.views.MusicTickerView(ctx);
        v.musicTicker = tvSong;
        tvSong.setTextColor(Color.WHITE);
        tvSong.setTextBold(true);
        tvSong.setTextSizePx(14 * ctx.getResources().getDisplayMetrics().scaledDensity);
        tvSong.setText(song);
        textCol.addView(tvSong, new LinearLayout.LayoutParams(textW,
            LinearLayout.LayoutParams.WRAP_CONTENT));

        if (!artist.isEmpty()) {
            TextView tvArtist = new TextView(ctx);
            tvArtist.setText(artist);
            tvArtist.setTextColor(0xFFCCCCCC);
            tvArtist.setTextSize(12);
            tvArtist.setMaxLines(1);
            tvArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(tvArtist, new LinearLayout.LayoutParams(textW,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // Mini equaliser bars (3 rects animated via alpha)
        LinearLayout bars = new LinearLayout(ctx);
        bars.setOrientation(LinearLayout.HORIZONTAL);
        bars.setGravity(android.view.Gravity.BOTTOM);
        int[] heights = {dp * 8, dp * 14, dp * 10, dp * 16, dp * 8};
        for (int i = 0; i < 5; i++) {
            View bar = new View(ctx);
            android.graphics.drawable.GradientDrawable bd = new android.graphics.drawable.GradientDrawable();
            bd.setColor(0xFFFF3B5C);
            bd.setCornerRadius(dp * 2);
            bar.setBackground(bd);
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp * 3, heights[i]);
            barLp.rightMargin = dp * 2;
            bars.addView(bar, barLp);
            bar.setPivotY(heights[i]); // grow/shrink from the bottom edge
            v.eqBars.add(bar);
            v.eqAnimators.add(makeBarAnimator(bar, 200 + i * 100L));
        }
        LinearLayout.LayoutParams barsLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barsLp.topMargin = dp * 4;
        textCol.addView(bars, barsLp);

        row.addView(textCol, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Chevron hint — only shown when this sticker is linked to a real Reels
        // track, so viewers know tapping it opens that track's sound detail sheet.
        if (!soundId.isEmpty()) {
            TextView tvChevron = new TextView(ctx);
            tvChevron.setText("\u203A"); // ›
            tvChevron.setTextColor(0xFFCCCCCC);
            tvChevron.setTextSize(20);
            tvChevron.setGravity(android.view.Gravity.CENTER);
            tvChevron.setContentDescription(ctx.getString(com.callx.app.core.R.string.music_sticker_open_sound));
            LinearLayout.LayoutParams chevLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            chevLp.leftMargin = dp * 4;
            row.addView(tvChevron, chevLp);
        }

        v.setContentDescription(song + (artist.isEmpty() ? "" : ", " + artist));
        v.addView(row);
        return v;
    }

    private static android.animation.ObjectAnimator makeBarAnimator(View bar, long duration) {
        android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(bar, View.SCALE_Y, 1f, 0.3f);
        a.setDuration(duration);
        a.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        a.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        return a;
    }

    private void startEqualiser() {
        if (!musicPlaying) return;
        for (android.animation.ObjectAnimator a : eqAnimators) if (!a.isStarted()) a.start();
    }

    private void stopEqualiser() {
        for (android.animation.ObjectAnimator a : eqAnimators) a.cancel();
        for (View b : eqBars) b.setScaleY(0.4f); // idle (paused/muted) pose
    }

    /**
     * Playback state of this music sticker's audio. true = bars dance + ticker
     * scrolls; false (paused / muted / not playing) = bars rest, ticker frozen.
     * Driven by the status viewer and the composer preview.
     */
    public void setMusicPlaying(boolean playing) {
        if (musicPlaying == playing && !eqAnimators.isEmpty() && eqAnimators.get(0).isStarted() == playing) return;
        musicPlaying = playing;
        if (playing) {
            for (View b : eqBars) b.setScaleY(1f);
            if (isAttachedToWindow()) startEqualiser();
            if (musicTicker != null) musicTicker.resume();
        } else {
            stopEqualiser();
            if (musicTicker != null) musicTicker.pause();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startEqualiser();
    }

    // ─── Countdown sticker ─────────────────────────────────────────────────

    private static StatusStickerOverlayView buildCountdown(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 16, dp * 14, dp * 16, dp * 14);
        v.setGravity(android.view.Gravity.CENTER);

        String hexColor = jsonStr(json, "color", "#7C3AED");
        int baseColor;
        try { baseColor = Color.parseColor(hexColor); } catch (Exception e) { baseColor = 0xFF7C3AED; }

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            new int[]{baseColor, darken(baseColor, 0.65f)});
        bg.setCornerRadius(dp * 20);
        v.setBackground(bg);

        String label = jsonStr(json, "label", "Countdown");
        String targetDate = jsonStr(json, "targetDate", "");
        v.countdownLabel = label;

        // Header
        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("⏳");
        tvEmoji.setTextSize(28);
        tvEmoji.setGravity(android.view.Gravity.CENTER);
        v.addView(tvEmoji);

        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.WHITE);
        tvLabel.setTextSize(15);
        tvLabel.setTypeface(null, Typeface.BOLD);
        tvLabel.setGravity(android.view.Gravity.CENTER);
        tvLabel.setMaxLines(1);
        tvLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lbLp.bottomMargin = dp * 10;
        v.addView(tvLabel, lbLp);

        // Time blocks row
        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(android.view.Gravity.CENTER);

        String[][] blocks = {{"00","DAYS"},{"00","HRS"},{"00","MIN"},{"00","SEC"}};
        final TextView[] timeViews = new TextView[4];
        for (int i = 0; i < blocks.length; i++) {
            LinearLayout block = new LinearLayout(ctx);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setGravity(android.view.Gravity.CENTER);

            android.graphics.drawable.GradientDrawable blockBg = new android.graphics.drawable.GradientDrawable();
            blockBg.setColor(0x33FFFFFF);
            blockBg.setCornerRadius(dp * 10);
            block.setBackground(blockBg);
            block.setPadding(dp * 10, dp * 8, dp * 10, dp * 8);

            TextView tvVal = new TextView(ctx);
            tvVal.setText(blocks[i][0]);
            tvVal.setTextColor(Color.WHITE);
            tvVal.setTextSize(22);
            tvVal.setTypeface(null, Typeface.BOLD);
            tvVal.setGravity(android.view.Gravity.CENTER);
            block.addView(tvVal);
            timeViews[i] = tvVal;

            TextView tvUnit = new TextView(ctx);
            tvUnit.setText(blocks[i][1]);
            tvUnit.setTextColor(0xCCFFFFFF);
            tvUnit.setTextSize(10);
            tvUnit.setGravity(android.view.Gravity.CENTER);
            block.addView(tvUnit);

            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bLp.leftMargin  = dp * 4;
            bLp.rightMargin = dp * 4;
            timeRow.addView(block, bLp);

            // Colon separator (except after last)
            if (i < blocks.length - 1) {
                TextView colon = new TextView(ctx);
                colon.setText(":");
                colon.setTextColor(Color.WHITE);
                colon.setTextSize(20);
                colon.setTypeface(null, Typeface.BOLD);
                timeRow.addView(colon);
            }
        }

        v.addView(timeRow);

        // "🔔 Remind me" row — tap to subscribe/unsubscribe; StatusViewerActivity wires
        // the actual persistence + owner-notify via setOnCountdownSubscribeToggleListener.
        // Rendered but inert (no-op toggle) when no listener is attached, e.g. in the
        // NewStatusActivity composer preview.
        LinearLayout subscribeRow = new LinearLayout(ctx);
        subscribeRow.setOrientation(LinearLayout.HORIZONTAL);
        subscribeRow.setGravity(android.view.Gravity.CENTER);
        subscribeRow.setPadding(dp * 12, dp * 8, dp * 12, dp * 8);
        subscribeRow.setClickable(true);
        subscribeRow.setFocusable(true);

        android.graphics.drawable.GradientDrawable subBg = new android.graphics.drawable.GradientDrawable();
        subBg.setCornerRadius(dp * 20);
        subBg.setColor(0x33FFFFFF);
        subBg.setStroke(1, 0x55FFFFFF);
        subscribeRow.setBackground(subBg);

        TextView tvBell = new TextView(ctx);
        tvBell.setText("\uD83D\uDD15"); // 🔕
        tvBell.setTextSize(15);
        LinearLayout.LayoutParams bellLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bellLp.rightMargin = dp * 6;
        subscribeRow.addView(tvBell, bellLp);

        TextView tvSubLabel = new TextView(ctx);
        tvSubLabel.setText("Remind me");
        tvSubLabel.setTextColor(Color.WHITE);
        tvSubLabel.setTextSize(13);
        tvSubLabel.setTypeface(null, Typeface.BOLD);
        subscribeRow.addView(tvSubLabel);

        LinearLayout.LayoutParams subRowLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subRowLp.topMargin = dp * 12;
        subRowLp.gravity = android.view.Gravity.CENTER;
        v.addView(subscribeRow, subRowLp);

        v.countdownSubscribeRow   = subscribeRow;
        v.countdownBellIcon       = tvBell;
        v.countdownSubscribeLabel = tvSubLabel;

        subscribeRow.setOnClickListener(clickedView -> {
            if (v.countdownExpired) return;
            boolean newState = !v.countdownSubscribed;
            v.countdownSubscribed = newState;
            v.updateCountdownSubscribeUi();
            if (v.countdownListener != null) v.countdownListener.onToggle(newState);
        });

        // Start live countdown if target date is valid
        v.startCountdown(targetDate, timeViews);

        return v;
    }

    private void startCountdown(String targetDateStr, TextView[] timeViews) {
        lastCountdownTarget = targetDateStr;
        lastCountdownViews = timeViews;
        long targetMs = 0;
        try {
            if (targetDateStr != null && !targetDateStr.isEmpty()) {
                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.US);
                java.util.Date date = sdf.parse(targetDateStr);
                if (date != null) targetMs = date.getTime();
            }
        } catch (Exception ignored) {}

        if (targetMs <= 0) {
            // Default: 7 days from now
            targetMs = System.currentTimeMillis() + (7L * 24 * 60 * 60 * 1000);
        }

        long remainMs = targetMs - System.currentTimeMillis();
        if (remainMs <= 0) {
            timeViews[0].setText("00");
            timeViews[1].setText("00");
            timeViews[2].setText("00");
            timeViews[3].setText("00");
            countdownExpired = true;
            updateCountdownSubscribeUi();
            return;
        }

        final long finalTargetMs = targetMs;
        countdownTimer = new CountDownTimer(remainMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long total = finalTargetMs - System.currentTimeMillis();
                if (total < 0) total = 0;
                long days  = total / (24 * 60 * 60 * 1000);
                long hrs   = (total % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000);
                long mins  = (total % (60 * 60 * 1000)) / (60 * 1000);
                long secs  = (total % (60 * 1000)) / 1000;
                timeViews[0].setText(String.format(java.util.Locale.US, "%02d", days));
                timeViews[1].setText(String.format(java.util.Locale.US, "%02d", hrs));
                timeViews[2].setText(String.format(java.util.Locale.US, "%02d", mins));
                timeViews[3].setText(String.format(java.util.Locale.US, "%02d", secs));
            }
            @Override public void onFinish() {
                for (TextView tv : timeViews) tv.setText("00");
                StatusStickerOverlayView.this.countdownExpired = true;
                StatusStickerOverlayView.this.updateCountdownSubscribeUi();
            }
        }.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        for (android.animation.ObjectAnimator a : eqAnimators) a.cancel();
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
    }

    // ─── Quiz sticker ──────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildQuiz(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 14, dp * 14, dp * 14);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE1A0A3B);
        bg.setStroke(1, 0xFF7C3AED);
        v.setBackground(bg);

        // Header
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp * 10;

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("🧠");
        tvEmoji.setTextSize(18);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eLp.rightMargin = dp * 6;
        headerRow.addView(tvEmoji, eLp);

        TextView tvType = new TextView(ctx);
        tvType.setText("QUIZ");
        tvType.setTextColor(0xFFAA55FF);
        tvType.setTextSize(12);
        tvType.setTypeface(null, Typeface.BOLD);
        tvType.setLetterSpacing(0.12f);
        headerRow.addView(tvType);

        v.addView(headerRow, hrLp);

        // Question
        String question = jsonStr(json, "question", "Quiz question");
        TextView tvQuestion = new TextView(ctx);
        tvQuestion.setText(question);
        tvQuestion.setTextColor(Color.WHITE);
        tvQuestion.setTextSize(15);
        tvQuestion.setTypeface(null, Typeface.BOLD);
        tvQuestion.setMaxLines(3);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qLp.bottomMargin = dp * 10;
        v.addView(tvQuestion, qLp);

        // Parse options from JSON array [{text:"...",correct:bool}]
        java.util.List<String> opts = new java.util.ArrayList<>();
        java.util.List<Boolean> corrects = new java.util.ArrayList<>();
        try {
            int arrStart = json.indexOf("\"options\":[") + 10;
            int arrEnd   = json.indexOf("]", arrStart);
            if (arrStart > 9 && arrEnd > arrStart) {
                String arrContent = json.substring(arrStart + 1, arrEnd);
                // Parse each {text:"...",correct:bool} object
                int depth = 0, start = 0;
                for (int i = 0; i < arrContent.length(); i++) {
                    char c = arrContent.charAt(i);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) {
                            String obj = arrContent.substring(start, i + 1);
                            opts.add(jsonStr(obj, "text", "Option"));
                            corrects.add(obj.contains("\"correct\":true"));
                            start = i + 1;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (opts.isEmpty()) { opts.add("Option A"); opts.add("Option B"); corrects.add(true); corrects.add(false); }

        v.quizQuestion = question;
        v.quizOptions  = opts;
        v.quizCorrects = corrects;

        // NOTE: unlike the old build, the correct answer is never revealed up front —
        // that was spoiling the quiz for every viewer before they'd even tapped. Options
        // render neutral + tappable here; revealQuizAnswer() does the ✓/✗ styling once
        // this viewer has actually answered (see setOnQuizOptionSelectedListener).
        for (int i = 0; i < opts.size() && i < 4; i++) {
            final int idx = i;
            TextView opt = new TextView(ctx);
            opt.setText(opts.get(i));
            opt.setTextColor(Color.WHITE);
            opt.setTextSize(13);
            opt.setGravity(android.view.Gravity.CENTER);
            opt.setPadding(dp * 12, dp * 8, dp * 12, dp * 8);
            opt.setClickable(true);
            opt.setFocusable(true);

            android.graphics.drawable.GradientDrawable optBg = new android.graphics.drawable.GradientDrawable();
            optBg.setCornerRadius(dp * 10);
            optBg.setColor(0x33FFFFFF);
            optBg.setStroke(1, 0x55FFFFFF);
            opt.setBackground(optBg);

            opt.setOnClickListener(clickedView -> {
                if (v.quizAnswered) return;
                if (v.quizListener != null) v.quizListener.onOptionSelected(idx);
            });

            LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            optLp.bottomMargin = dp * 6;
            v.addView(opt, optLp);
            v.quizOptionViews.add(opt);
        }

        return v;
    }

    // ─── Question Box sticker ──────────────────────────────────────────────

    private static StatusStickerOverlayView buildQuestion(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 14, dp * 14, dp * 14);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE0A2B1E);
        bg.setStroke(1, 0xFF00C897);
        v.setBackground(bg);

        // Header
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp * 8;

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("💬");
        tvEmoji.setTextSize(18);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eLp.rightMargin = dp * 6;
        headerRow.addView(tvEmoji, eLp);

        TextView tvType = new TextView(ctx);
        tvType.setText("ASK ME");
        tvType.setTextColor(0xFF00C897);
        tvType.setTextSize(12);
        tvType.setTypeface(null, Typeface.BOLD);
        tvType.setLetterSpacing(0.12f);
        headerRow.addView(tvType);

        v.addView(headerRow, hrLp);

        // Prompt
        String prompt = jsonStr(json, "prompt", "Ask me anything!");
        TextView tvPrompt = new TextView(ctx);
        tvPrompt.setText(prompt);
        tvPrompt.setTextColor(Color.WHITE);
        tvPrompt.setTextSize(15);
        tvPrompt.setTypeface(null, Typeface.BOLD);
        tvPrompt.setMaxLines(3);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.bottomMargin = dp * 10;
        v.addView(tvPrompt, pLp);

        // Reply input box
        LinearLayout replyBox = new LinearLayout(ctx);
        replyBox.setOrientation(LinearLayout.HORIZONTAL);
        replyBox.setGravity(android.view.Gravity.CENTER_VERTICAL);
        replyBox.setPadding(dp * 12, dp * 10, dp * 12, dp * 10);

        android.graphics.drawable.GradientDrawable rBg = new android.graphics.drawable.GradientDrawable();
        rBg.setCornerRadius(dp * 24);
        rBg.setColor(0x33FFFFFF);
        rBg.setStroke(1, 0x55FFFFFF);
        replyBox.setBackground(rBg);

        TextView tvReply = new TextView(ctx);
        tvReply.setText("Send a reply…");
        tvReply.setTextColor(0x88FFFFFF);
        tvReply.setTextSize(13);
        replyBox.addView(tvReply, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvSend = new TextView(ctx);
        tvSend.setText("→");
        tvSend.setTextColor(0xFF00C897);
        tvSend.setTextSize(18);
        replyBox.addView(tvSend);

        v.addView(replyBox, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return v;
    }

    // ─── Poll sticker ──────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildPoll(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 14, dp * 14, dp * 14);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE0A1F2A);
        bg.setStroke(1, 0xFF17C3E0);
        v.setBackground(bg);

        // Header
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp * 10;

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("\uD83D\uDDF3\uFE0F"); // 🗳️
        tvEmoji.setTextSize(18);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eLp.rightMargin = dp * 6;
        headerRow.addView(tvEmoji, eLp);

        TextView tvType = new TextView(ctx);
        tvType.setText("POLL");
        tvType.setTextColor(0xFF17C3E0);
        tvType.setTextSize(12);
        tvType.setTypeface(null, Typeface.BOLD);
        tvType.setLetterSpacing(0.12f);
        headerRow.addView(tvType);

        v.addView(headerRow, hrLp);

        // Question
        String question = jsonStr(json, "question", "Poll question");
        String optA = jsonStr(json, "optionA", "Yes");
        String optB = jsonStr(json, "optionB", "No");
        v.pollQuestion = question;
        v.pollOptionA  = optA;
        v.pollOptionB  = optB;

        TextView tvQuestion = new TextView(ctx);
        tvQuestion.setText(question);
        tvQuestion.setTextColor(Color.WHITE);
        tvQuestion.setTextSize(15);
        tvQuestion.setTypeface(null, Typeface.BOLD);
        tvQuestion.setMaxLines(3);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qLp.bottomMargin = dp * 10;
        v.addView(tvQuestion, qLp);

        Object[] rA = buildPollOptionRow(ctx, dp, optA);
        Object[] rB = buildPollOptionRow(ctx, dp, optB);
        View containerA = (View) rA[0], containerB = (View) rB[0];
        v.pollOptViewA = (TextView) rA[1]; v.pollFillA = (View) rA[2]; v.pollPctA = (TextView) rA[3];
        v.pollOptViewB = (TextView) rB[1]; v.pollFillB = (View) rB[2]; v.pollPctB = (TextView) rB[3];

        LinearLayout.LayoutParams optLpA = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp * 42);
        optLpA.bottomMargin = dp * 8;
        v.addView(containerA, optLpA);
        v.addView(containerB, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp * 42));

        // Full pill is the tap target (not just the label), and gets disabled on vote.
        containerA.setOnClickListener(cv -> {
            if (v.pollAnswered) return;
            if (v.pollListener != null) v.pollListener.onOptionSelected("A");
        });
        containerB.setOnClickListener(cv -> {
            if (v.pollAnswered) return;
            if (v.pollListener != null) v.pollListener.onOptionSelected("B");
        });
        v.pollContainerA = containerA;
        v.pollContainerB = containerB;

        return v;
    }

    /** Builds one poll option pill: [container, labelView, fillView, pctView]. */
    private static Object[] buildPollOptionRow(Context ctx, int dp, String label) {
        FrameLayout container = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable pillBg = new android.graphics.drawable.GradientDrawable();
        pillBg.setCornerRadius(dp * 20);
        pillBg.setColor(0x22FFFFFF);
        pillBg.setStroke(1, 0x55FFFFFF);
        container.setBackground(pillBg);
        container.setClipToOutline(true);
        container.setClickable(true);
        container.setFocusable(true);

        // Percentage fill bar — hidden (0-width) until the viewer votes.
        LinearLayout fillRow = new LinearLayout(ctx);
        fillRow.setOrientation(LinearLayout.HORIZONTAL);
        View fill = new View(ctx);
        fill.setBackgroundColor(0x5517C3E0);
        View spacer = new View(ctx);
        fillRow.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f));
        fillRow.addView(spacer, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        container.addView(fillRow, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Label + percentage content row, drawn on top of the fill.
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(android.view.Gravity.CENTER_VERTICAL);
        content.setPadding(dp * 14, 0, dp * 14, 0);

        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        tvLabel.setTextColor(Color.WHITE);
        tvLabel.setTextSize(14);
        tvLabel.setTypeface(null, Typeface.BOLD);
        content.addView(tvLabel, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvPct = new TextView(ctx);
        tvPct.setTextColor(Color.WHITE);
        tvPct.setTextSize(13);
        tvPct.setTypeface(null, Typeface.BOLD);
        tvPct.setVisibility(GONE);
        content.addView(tvPct);

        container.addView(content, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        return new Object[]{container, tvLabel, fill, tvPct};
    }

    // ─── Slider sticker ────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildSlider(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 14, dp * 14, dp * 14);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE23122E);
        bg.setStroke(1, 0xFFE91E8C);
        v.setBackground(bg);

        String emoji = jsonStr(json, "emoji", "\u2764\uFE0F"); // ❤️
        String question = jsonStr(json, "question", "Rate it!");
        v.sliderEmoji = emoji;
        v.sliderQuestion = question;

        // Header
        LinearLayout headerRow = new LinearLayout(ctx);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hrLp.bottomMargin = dp * 10;

        TextView tvEmoji = new TextView(ctx);
        tvEmoji.setText("\uD83C\uDF9A\uFE0F"); // 🎚️
        tvEmoji.setTextSize(18);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        eLp.rightMargin = dp * 6;
        headerRow.addView(tvEmoji, eLp);

        TextView tvType = new TextView(ctx);
        tvType.setText("SLIDER");
        tvType.setTextColor(0xFFE91E8C);
        tvType.setTextSize(12);
        tvType.setTypeface(null, Typeface.BOLD);
        tvType.setLetterSpacing(0.12f);
        headerRow.addView(tvType);

        v.addView(headerRow, hrLp);

        // Question
        TextView tvQuestion = new TextView(ctx);
        tvQuestion.setText(question);
        tvQuestion.setTextColor(Color.WHITE);
        tvQuestion.setTextSize(15);
        tvQuestion.setTypeface(null, Typeface.BOLD);
        tvQuestion.setMaxLines(3);
        LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        qLp.bottomMargin = dp * 10;
        v.addView(tvQuestion, qLp);

        // Live value readout, above the track
        LinearLayout valueRow = new LinearLayout(ctx);
        valueRow.setOrientation(LinearLayout.HORIZONTAL);
        valueRow.setGravity(android.view.Gravity.CENTER);

        TextView tvThumbEmoji = new TextView(ctx);
        tvThumbEmoji.setText(emoji);
        tvThumbEmoji.setTextSize(20);
        LinearLayout.LayoutParams teLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        teLp.rightMargin = dp * 6;
        valueRow.addView(tvThumbEmoji, teLp);
        v.sliderThumbEmoji = tvThumbEmoji;

        TextView tvValue = new TextView(ctx);
        tvValue.setText("50%");
        tvValue.setTextColor(Color.WHITE);
        tvValue.setTextSize(15);
        tvValue.setTypeface(null, Typeface.BOLD);
        valueRow.addView(tvValue);
        v.sliderValueLabel = tvValue;

        TextView tvAvg = new TextView(ctx);
        tvAvg.setTextColor(0xFFE91E8C);
        tvAvg.setTextSize(12);
        tvAvg.setTypeface(null, Typeface.BOLD);
        tvAvg.setVisibility(GONE);
        LinearLayout.LayoutParams avgLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        avgLp.leftMargin = dp * 10;
        valueRow.addView(tvAvg, avgLp);
        v.sliderAvgLabel = tvAvg;

        LinearLayout.LayoutParams vrLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vrLp.bottomMargin = dp * 4;
        v.addView(valueRow, vrLp);

        // Track
        SeekBar seekBar = new SeekBar(ctx);
        seekBar.setMax(100);
        seekBar.setProgress(50);
        try {
            seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFFE91E8C));
            seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE91E8C));
        } catch (Exception ignored) {}
        v.sliderBar = seekBar;

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                tvValue.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (v.sliderAnswered) return;
                if (v.sliderListener != null) v.sliderListener.onSubmitted(sb.getProgress());
            }
        });

        v.addView(seekBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return v;
    }

    // ─── Mention sticker ───────────────────────────────────────────────────

    private static StatusStickerOverlayView buildMention(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 10, dp * 16, dp * 10);
        v.setOrientation(HORIZONTAL);
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 24);
        bg.setColor(0xEE14283A);
        bg.setStroke(1, 0xFF3D9BE9);
        v.setBackground(bg);

        String username = jsonStr(json, "username", "");
        v.mentionUsername = username;

        TextView tvAt = new TextView(ctx);
        tvAt.setText("👤");
        tvAt.setTextSize(16);
        LinearLayout.LayoutParams atLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        atLp.rightMargin = dp * 8;
        v.addView(tvAt, atLp);

        TextView tvUsername = new TextView(ctx);
        tvUsername.setText("@" + username);
        tvUsername.setTextColor(0xFF3D9BE9);
        tvUsername.setTextSize(15);
        tvUsername.setTypeface(null, Typeface.BOLD);
        tvUsername.setMaxLines(1);
        tvUsername.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.addView(tvUsername);

        return v;
    }

    // ─── Hashtag sticker ───────────────────────────────────────────────────

    private static StatusStickerOverlayView buildHashtag(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 14, dp * 10, dp * 16, dp * 10);
        v.setOrientation(HORIZONTAL);
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 24);
        bg.setColor(0xEE2A2410);
        bg.setStroke(1, 0xFFE9C93D);
        v.setBackground(bg);

        String tag = jsonStr(json, "tag", "");
        v.hashtagTag = tag;

        TextView tvHash = new TextView(ctx);
        tvHash.setText("#");
        tvHash.setTextColor(0xFFE9C93D);
        tvHash.setTextSize(18);
        tvHash.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.rightMargin = dp * 6;
        v.addView(tvHash, hLp);

        TextView tvTag = new TextView(ctx);
        tvTag.setText(tag);
        tvTag.setTextColor(0xFFE9C93D);
        tvTag.setTextSize(15);
        tvTag.setTypeface(null, Typeface.BOLD);
        tvTag.setMaxLines(1);
        tvTag.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.addView(tvTag);

        return v;
    }

    // ─── Link sticker ──────────────────────────────────────────────────────

    private static StatusStickerOverlayView buildLink(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 16, dp * 10, dp * 18, dp * 10);
        v.setOrientation(HORIZONTAL);
        v.setGravity(android.view.Gravity.CENTER_VERTICAL);

        // Rendered as a solid white pill (unlike the other dark cards) — mirrors
        // the high-contrast "link" pill viewers already expect from IG-style stories.
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 24);
        bg.setColor(0xFFFFFFFF);
        v.setBackground(bg);

        String url   = jsonStr(json, "url", "");
        String label = jsonStr(json, "label", "");
        v.linkUrl   = url;
        v.linkLabel = label;

        TextView tvIcon = new TextView(ctx);
        tvIcon.setText("\uD83D\uDD17"); // 🔗
        tvIcon.setTextSize(16);
        LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iLp.rightMargin = dp * 8;
        v.addView(tvIcon, iLp);

        String display = !label.isEmpty() ? label : displayHost(url);
        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(display);
        tvLabel.setTextColor(0xFF111111);
        tvLabel.setTextSize(15);
        tvLabel.setTypeface(null, Typeface.BOLD);
        tvLabel.setMaxLines(1);
        tvLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        v.addView(tvLabel);

        return v;
    }

    /** Best-effort host name for display (e.g. "example.com"), falling back to the raw URL. */
    private static String displayHost(String url) {
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            String host = u.getHost();
            return host != null ? host.replaceFirst("^www\\.", "") : url;
        } catch (Exception e) {
            return url;
        }
    }

    // ─── Add Yours sticker ─────────────────────────────────────────────────

    private static StatusStickerOverlayView buildAddYours(Context ctx, String json) {
        int dp = dp(ctx);
        StatusStickerOverlayView v = new StatusStickerOverlayView(ctx);
        v.setPadding(dp * 16, dp * 14, dp * 16, dp * 14);
        v.setGravity(android.view.Gravity.CENTER);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 18);
        bg.setColor(0xEE1A1A1A);
        bg.setStroke(dp * 2, 0xFFFFFFFF);
        v.setBackground(bg);

        String prompt     = jsonStr(json, "prompt", "Add Yours");
        String originUid  = jsonStr(json, "originUid", "");
        String originName = jsonStr(json, "originName", "");
        v.addYoursPrompt     = prompt;
        v.addYoursOriginUid  = originUid;
        v.addYoursOriginName = originName;
        v.addYoursChainId    = jsonStr(json, "chainId", "");
        try {
            org.json.JSONObject tplObj = new org.json.JSONObject(json).optJSONObject("template");
            v.addYoursTemplateJson = tplObj != null ? tplObj.toString() : "";
        } catch (Exception e) { v.addYoursTemplateJson = ""; }

        TextView tvPlus = new TextView(ctx);
        tvPlus.setText("\u2795"); // ➕
        tvPlus.setTextSize(22);
        tvPlus.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pLp.bottomMargin = dp * 6;
        v.addView(tvPlus, pLp);

        TextView tvHeader = new TextView(ctx);
        tvHeader.setText("ADD YOURS");
        tvHeader.setTextColor(Color.WHITE);
        tvHeader.setTextSize(12);
        tvHeader.setTypeface(null, Typeface.BOLD);
        tvHeader.setLetterSpacing(0.12f);
        tvHeader.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp * 6;
        v.addView(tvHeader, hLp);

        TextView tvPrompt = new TextView(ctx);
        tvPrompt.setText(prompt);
        tvPrompt.setTextColor(Color.WHITE);
        tvPrompt.setTextSize(16);
        tvPrompt.setTypeface(null, Typeface.BOLD);
        tvPrompt.setGravity(android.view.Gravity.CENTER);
        tvPrompt.setMaxLines(2);
        LinearLayout.LayoutParams promptLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (!originName.isEmpty()) promptLp.bottomMargin = dp * 6;
        v.addView(tvPrompt, promptLp);

        // Only present once a viewer has actually joined the chain — the origin
        // post itself has no originName yet, since it hasn't been added-to.
        if (!originName.isEmpty()) {
            TextView tvOrigin = new TextView(ctx);
            tvOrigin.setText("\u21B3 Started by " + originName);
            tvOrigin.setTextColor(0xFFBBBBBB);
            tvOrigin.setTextSize(11);
            tvOrigin.setGravity(android.view.Gravity.CENTER);
            v.addView(tvOrigin);
        }

        // v578: live "N people added" label (filled by AddYoursHelper.attachCount in the viewer; hidden at 0)
        TextView tvCount = new TextView(ctx);
        tvCount.setTextColor(0xFFFFFFFF);
        tvCount.setTextSize(11);
        tvCount.setTypeface(null, Typeface.BOLD);
        tvCount.setGravity(android.view.Gravity.CENTER);
        tvCount.setPadding(dp * 10, dp * 4, dp * 10, dp * 4);
        android.graphics.drawable.GradientDrawable countBg = new android.graphics.drawable.GradientDrawable();
        countBg.setCornerRadius(dp * 12);
        countBg.setColor(0x33FFFFFF);
        tvCount.setBackground(countBg);
        tvCount.setVisibility(View.GONE);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        countLp.topMargin = dp * 8;
        v.addView(tvCount, countLp);
        v.tvAddYoursCount = tvCount;

        return v;
    }

    // ─── Drag support v2 (drag + pinch + twist + pan, snap guides, corner handle, trash) ───
    //
    // One shared trash pill + shared guide lines per parent FrameLayout — however many
    // stickers live in that parent they reuse the same overlay chrome.
    //   pink   guides : frame centre lines (existing)
    //   yellow guides : alignment with another sticker's centre / edges
    //   rotation chrome: white line through the sticker at its angle + "45°" read-out

    private static final java.util.WeakHashMap<ViewGroup, View> trashChrome = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ViewGroup, View[]> guideChrome = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ViewGroup, View[]> alignChrome = new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<ViewGroup, View[]> rotChrome = new java.util.WeakHashMap<>();

    private static float dpf(Context ctx) { return ctx.getResources().getDisplayMetrics().density; }

    private static View getOrCreateTrash(ViewGroup parent) {
        View existing = trashChrome.get(parent);
        if (existing != null && existing.getParent() == parent) return existing;
        Context ctx = parent.getContext();
        int dp = dp(ctx);

        FrameLayout trash = new FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(0xCC1A1A1A);
        bg.setStroke((int) (1.5f * dp), 0x66FFFFFF);
        trash.setBackground(bg);

        TextView icon = new TextView(ctx);
        icon.setText("\uD83D\uDDD1\uFE0F"); // 🗑️
        icon.setTextSize(24);
        icon.setGravity(Gravity.CENTER);
        trash.addView(icon, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        int size = dp * 60;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = dp * 28;
        trash.setLayoutParams(lp);
        trash.setAlpha(0f);
        trash.setScaleX(0.6f); trash.setScaleY(0.6f);
        trash.setVisibility(View.GONE);
        trash.setElevation(40f * dp);

        parent.addView(trash);
        trashChrome.put(parent, trash);
        return trash;
    }

    private static View[] getOrCreateGuides(ViewGroup parent) {
        View[] existing = guideChrome.get(parent);
        if (existing != null && existing[0].getParent() == parent) return existing;
        Context ctx = parent.getContext();
        int dp = dp(ctx);

        View vLine = new View(ctx);
        vLine.setBackgroundColor(0xAAFF3B7A);
        vLine.setAlpha(0f);
        FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
            Math.max(1, dp / 2), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL);
        vLine.setLayoutParams(vlp);

        View hLine = new View(ctx);
        hLine.setBackgroundColor(0xAAFF3B7A);
        hLine.setAlpha(0f);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp / 2), Gravity.CENTER_VERTICAL);
        hLine.setLayoutParams(hlp);

        parent.addView(vLine);
        parent.addView(hLine);
        View[] pair = { vLine, hLine };
        guideChrome.put(parent, pair);
        return pair;
    }

    /** Yellow alignment guides (vertical, horizontal) used when snapping to another sticker. */
    private static View[] getOrCreateAlign(ViewGroup parent) {
        View[] existing = alignChrome.get(parent);
        if (existing != null && existing[0].getParent() == parent) return existing;
        Context ctx = parent.getContext();
        int dp = dp(ctx);
        View v = new View(ctx);
        v.setBackgroundColor(0xCCFFD60A);
        v.setAlpha(0f);
        v.setLayoutParams(new FrameLayout.LayoutParams(
            Math.max(1, dp / 2), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START));
        View h = new View(ctx);
        h.setBackgroundColor(0xCCFFD60A);
        h.setAlpha(0f);
        h.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp / 2), Gravity.TOP | Gravity.START));
        parent.addView(v);
        parent.addView(h);
        View[] pair = { v, h };
        alignChrome.put(parent, pair);
        return pair;
    }

    /** {angle line, degree read-out pill}. */
    private static View[] getOrCreateRot(ViewGroup parent) {
        View[] existing = rotChrome.get(parent);
        if (existing != null && existing[0].getParent() == parent) return existing;
        Context ctx = parent.getContext();
        int dp = dp(ctx);
        View line = new View(ctx);
        line.setBackgroundColor(0xCCFFFFFF);
        line.setAlpha(0f);
        line.setLayoutParams(new FrameLayout.LayoutParams(dp * 400, Math.max(2, dp), Gravity.TOP | Gravity.START));

        TextView label = new TextView(ctx);
        label.setTextColor(Color.WHITE);
        label.setTextSize(13);
        label.setTypeface(null, Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp * 10, dp * 4, dp * 10, dp * 4);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp * 14);
        bg.setColor(0xCC000000);
        label.setBackground(bg);
        label.setAlpha(0f);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        llp.topMargin = dp * 84;
        label.setLayoutParams(llp);

        parent.addView(line);
        parent.addView(label);
        View[] pair = { line, label };
        rotChrome.put(parent, pair);
        return pair;
    }

    /**
     * Fades a chrome view in/out ONLY when its target state actually changes. Calling animate() on every
     * MOVE (the old behaviour) cancelled and restarted 4-6 animators per touch event → jank on drag.
     */
    private static void fadeTo(View v, boolean show, int durMs) {
        Object cur = v.getTag(com.callx.app.core.R.id.callx_chrome_shown);
        if (cur == null) {
            if (!show && v.getAlpha() == 0f) { v.setTag(com.callx.app.core.R.id.callx_chrome_shown, Boolean.FALSE); return; }
        } else if (((Boolean) cur) == show) {
            return;
        }
        v.setTag(com.callx.app.core.R.id.callx_chrome_shown, show ? Boolean.TRUE : Boolean.FALSE);
        v.animate().alpha(show ? 1f : 0f).setDuration(durMs).start();
    }

    private void updateRotChrome(ViewGroup parent, boolean snapped, float rotDeg, boolean showLabel) {
        final int pw = parent.getWidth(), ph = parent.getHeight();
        if (pw <= 0 || ph <= 0) return;
        View[] r = rotCur;
        if (r == null || r[0].getParent() != parent) r = rotCur = getOrCreateRot(parent);
        if (snapped) {   // the angle line is only visible while snapped → position it only then
            float cx = getX() + getWidth() / 2f, cy = getY() + getHeight() / 2f;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) r[0].getLayoutParams();
            int len = (int) Math.hypot(pw, ph);
            if (lp.width != len) { lp.width = len; r[0].setLayoutParams(lp); }
            r[0].setPivotX(len / 2f);
            r[0].setPivotY(lp.height / 2f);
            r[0].setTranslationX(cx - len / 2f);
            r[0].setTranslationY(cy - lp.height / 2f);
            r[0].setRotation(rotDeg);
        }
        fadeTo(r[0], snapped, 80);
        if (showLabel) {
            int deg = Math.round(normDeg(rotDeg));
            Object last = r[1].getTag(com.callx.app.core.R.id.callx_chrome_deg);
            if (!(last instanceof Integer) || ((Integer) last) != deg) {
                r[1].setTag(com.callx.app.core.R.id.callx_chrome_deg, deg);
                ((TextView) r[1]).setText(deg + "\u00B0");
            }
        }
        fadeTo(r[1], showLabel, 80);
    }

    private static void hideSnapChrome(ViewGroup parent) {
        View[] g = guideChrome.get(parent);
        if (g != null) { fadeTo(g[0], false, 100); fadeTo(g[1], false, 100); }
        View[] a = alignChrome.get(parent);
        if (a != null) { fadeTo(a[0], false, 100); fadeTo(a[1], false, 100); }
        View[] r = rotChrome.get(parent);
        if (r != null) { fadeTo(r[0], false, 120); fadeTo(r[1], false, 120); }
    }

    private static void bringChromeToFront(ViewGroup parent) {
        View t = trashChrome.get(parent);
        if (t != null) t.bringToFront();
        View[][] sets = { guideChrome.get(parent), alignChrome.get(parent), rotChrome.get(parent) };
        for (View[] set : sets) if (set != null) { set[0].bringToFront(); set[1].bringToFront(); }
    }

    private void requestParentDisallow(boolean disallow) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
    }

    /**
     * Make this sticker draggable, pinch-to-scale, twist-to-rotate and removable inside {@code parent}:
     *
     *  • 1-finger drag, with magnetic snap to the frame centre (pink) AND to other stickers'
     *    centre / edges (yellow), haptic tick on each new snap. The centre can never leave the frame.
     *  • 2 fingers: pinch + twist + pan in ONE transform around the fingers' midpoint
     *    (the old twist used view-local coordinates, so it chased its own rotation and felt half-speed).
     *    Rotation magnets every 45° (line + degree read-out), scale magnet at 1.0x.
     *  • When selected ({@link #setSelectedUi}), the bottom-right handle resizes + rotates with ONE finger.
     *  • Drag onto the trash pill → the sticker is sucked in and removed.
     *  • A quick tap fires {@link #setOnStickerTappedListener}.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public void attachDragToParent(final ViewGroup parent) {
        final float[] startP   = new float[2];   // finger at gesture start, parent space
        final float[] curP     = new float[2];
        final float[] p2       = new float[2];
        final float[] startPos = new float[2];   // sticker x/y at gesture start
        final long[]  downTime = new long[1];
        final boolean[] moved  = new boolean[1];
        final boolean[] hoveringTrash = new boolean[1];
        final boolean[] trashShown    = new boolean[1];
        final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

        // v650: chrome views are looked up ONCE per gesture (ACTION_DOWN), not on every MOVE.
        final View[] trashH = new View[1];
        final View[][] guidesH = new View[1][];
        final View[][] alignH = new View[1][];

        setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN || trashH[0] == null
                    || trashH[0].getParent() != parent) {
                trashH[0] = getOrCreateTrash(parent);
                guidesH[0] = getOrCreateGuides(parent);
                alignH[0] = getOrCreateAlign(parent);
                rotCur = getOrCreateRot(parent);
            }
            final View trash = trashH[0];
            final View[] guides = guidesH[0];
            final View[] align = alignH[0];

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    requestParentDisallow(true);
                    toParent(event, 0, startP);
                    startPos[0] = getX();
                    startPos[1] = getY();
                    downTime[0] = System.currentTimeMillis();
                    moved[0] = false;
                    hoveringTrash[0] = false;
                    trashShown[0] = false;
                    snapTx = Float.NaN; snapTy = Float.NaN;
                    rotSnapped = false; scaleSnapped = false;
                    handleActive = false; tfActive = false;
                    nudgeTargetX = Float.NaN; nudgeTargetY = Float.NaN;
                    gBefore = captureState();
                    collectSnapTargets(parent);
                    animate().cancel();
                    if (Math.abs(normDeg(getRotation() - stickerRotationDeg)) > 0.01f) setRotation(stickerRotationDeg);
                    if (autoRaiseOnTouch) bringToFront();
                    bringChromeToFront(parent);
                    if (locked) return true; // locked: only a tap (select / unlock) is allowed
                    if (selectedUi && inHandle(event.getX(), event.getY())) {
                        handleActive = true;
                        moved[0] = true;
                        handleBegin(event);
                        if (dragListener != null) dragListener.onDragStart(this);
                        return true;
                    }
                    animate().scaleX(stickerScale * 1.05f).scaleY(stickerScale * 1.05f)
                        .setDuration(80).setInterpolator(new DecelerateInterpolator()).start();
                    if (dragListener != null) dragListener.onDragStart(this);
                    return true;
                }
                case MotionEvent.ACTION_POINTER_DOWN: {
                    requestParentDisallow(true);
                    if (locked || handleActive) return true;
                    if (event.getPointerCount() == 2) {
                        toParent(event, 0, curP);
                        toParent(event, 1, p2);
                        tfBegin(curP[0], curP[1], p2[0], p2[1]);
                        moved[0] = true;
                        hoveringTrash[0] = false;
                        setAlpha(1f);
                        hideTrashAndGuides(parent, trash, guides);
                        trashShown[0] = false;
                    }
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    requestParentDisallow(true);
                    if (locked) return true;
                    if (handleActive) {
                        toParent(event, 0, curP);
                        handleUpdate(parent, curP[0], curP[1]);
                        if (dragListener != null) dragListener.onDragMove(this);
                        return true;
                    }
                    if (tfActive) {
                        if (event.getPointerCount() >= 2) {
                            toParent(event, 0, curP);
                            toParent(event, 1, p2);
                            tfUpdate(parent, curP[0], curP[1], p2[0], p2[1]);
                            if (dragListener != null) dragListener.onDragMove(this);
                        }
                        return true;
                    }
                    if (event.getPointerCount() != 1) return true;

                    toParent(event, 0, curP);
                    float dx = curP[0] - startP[0];
                    float dy = curP[1] - startP[1];
                    if (!moved[0]) {
                        if (Math.abs(dx) <= touchSlop && Math.abs(dy) <= touchSlop) return true;
                        moved[0] = true;
                    }
                    if (!trashShown[0]) { showTrash(trash); trashShown[0] = true; }

                    float[] pos = snapPosition(parent, startPos[0] + dx, startPos[1] + dy, guides, align);
                    float tx = pos[0], ty = pos[1];
                    if (parent.getWidth() > 0 && parent.getHeight() > 0) {   // centre must stay inside the frame
                        float cx = Math.max(0f, Math.min(parent.getWidth(),  tx + view.getWidth()  / 2f));
                        float cy = Math.max(0f, Math.min(parent.getHeight(), ty + view.getHeight() / 2f));
                        tx = cx - view.getWidth() / 2f;
                        ty = cy - view.getHeight() / 2f;
                    }
                    view.setX(tx);
                    view.setY(ty);
                    if (dragListener != null) dragListener.onDragMove(this);

                    boolean hovering = overlaps(view, trash);
                    if (hovering != hoveringTrash[0]) {
                        hoveringTrash[0] = hovering;
                        setTrashArmed(trash, hovering);
                        if (hovering) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        float warn = hovering ? 0.55f : stickerScale * 1.05f;
                        view.animate().scaleX(warn).scaleY(warn).alpha(hovering ? 0.6f : 1f)
                            .setDuration(120).setInterpolator(new DecelerateInterpolator()).start();
                    }
                    return true;
                }
                case MotionEvent.ACTION_POINTER_UP: {
                    if (locked || handleActive) return true;
                    int lifted = event.getActionIndex();
                    int cnt = event.getPointerCount();
                    int[] rem = new int[2];
                    int k = 0;
                    for (int i = 0; i < cnt && k < 2; i++) if (i != lifted) rem[k++] = i;
                    if (cnt >= 3) {
                        if (tfActive) {   // re-anchor on the two fingers that are left
                            toParent(event, rem[0], curP);
                            toParent(event, rem[1], p2);
                            tfBegin(curP[0], curP[1], p2[0], p2[1]);
                        }
                        return true;
                    }
                    // one finger remains → continue as a plain drag from the current pose (no jump)
                    tfActive = false;
                    hideSnapChrome(parent);
                    toParent(event, rem[0], startP);
                    startPos[0] = getX();
                    startPos[1] = getY();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    requestParentDisallow(false);
                    final boolean isUp = event.getActionMasked() == MotionEvent.ACTION_UP;
                    final boolean willRemove = isUp && moved[0] && hoveringTrash[0] && !locked
                            && !handleActive && !tfActive && parent == getParent();
                    if (willRemove) removing = true;
                    handleActive = false;
                    tfActive = false;
                    hideTrashAndGuides(parent, trash, guides);
                    if (dragListener != null) dragListener.onDragEnd(this);
                    if (willRemove) {
                        preRemovalOverride = gBefore;   // undo puts it back where the drag started, not on the trash pill
                        gBefore = null;
                        removeAnimated(parent, true, trash);
                        return true;
                    }
                    animateRelease();
                    boolean isTap = isUp && !moved[0] && (System.currentTimeMillis() - downTime[0]) < 300;
                    commitTransformIfChanged();
                    if (isTap && stickerTapListener != null) stickerTapListener.onTapped(this);
                    return true;
                }
            }
            return false;
        });

        setOnLongClickListener(view -> {
            if (locked) return true;
            removeAnimated(parent, true, null);
            return true;
        });
    }

    /**
     * Applies snapping to a proposed top-left (tx,ty): frame centre first (pink), else the closest
     * centre/edge of another sticker (yellow). Also drives the guide lines + haptic ticks.
     */
    private float[] snapPosition(ViewGroup parent, float tx, float ty, View[] guides, View[] align) {
        final int W = parent.getWidth(), H = parent.getHeight();
        if (W <= 0 || H <= 0) { snapOut[0] = tx; snapOut[1] = ty; return snapOut; }
        final float w = getWidth(), h = getHeight();
        float cx = tx + w / 2f, cy = ty + h / 2f;
        double r = Math.toRadians(stickerRotationDeg);
        float c = (float) Math.abs(Math.cos(r)), sn = (float) Math.abs(Math.sin(r));
        float hw = (w * stickerScale * c + h * stickerScale * sn) / 2f;
        float hh = (w * stickerScale * sn + h * stickerScale * c) / 2f;
        final float snap = dpf(getContext()) * 9f;
        final float px = W / 2f, py = H / 2f;

        // ── X axis ── (frame centre wins; else nearest centre/edge of another sticker, from the cached targets)
        float bestD = Float.MAX_VALUE, bestT = Float.NaN;
        boolean centerHitX = false;
        if (Math.abs(cx - px) < snap) { bestD = px - cx; bestT = px; centerHitX = true; }
        else {
            for (int i = 0; i < snapTargetCount; i++) {
                final float tc = snapTargets[i * 4], thw = snapTargets[i * 4 + 2];
                for (int mi = 0; mi < 3; mi++) {
                    final float m = mi == 0 ? cx : (mi == 1 ? cx - hw : cx + hw);
                    for (int ti = 0; ti < 3; ti++) {
                        final float t = ti == 0 ? tc : (ti == 1 ? tc - thw : tc + thw);
                        final float d = t - m;
                        if (Math.abs(d) < snap && Math.abs(d) < Math.abs(bestD)) { bestD = d; bestT = t; }
                    }
                }
            }
        }
        if (!Float.isNaN(bestT)) {
            cx += bestD;
            if (Float.isNaN(snapTx) || Math.abs(snapTx - bestT) > 0.5f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            snapTx = bestT;
            fadeTo(guides[0], centerHitX, 100);
            align[0].setTranslationX(bestT);
            fadeTo(align[0], !centerHitX, 100);
        } else {
            snapTx = Float.NaN;
            fadeTo(guides[0], false, 100);
            fadeTo(align[0], false, 100);
        }

        // ── Y axis ──
        bestD = Float.MAX_VALUE; bestT = Float.NaN;
        boolean centerHitY = false;
        if (Math.abs(cy - py) < snap) { bestD = py - cy; bestT = py; centerHitY = true; }
        else {
            for (int i = 0; i < snapTargetCount; i++) {
                final float tc = snapTargets[i * 4 + 1], thh = snapTargets[i * 4 + 3];
                for (int mi = 0; mi < 3; mi++) {
                    final float m = mi == 0 ? cy : (mi == 1 ? cy - hh : cy + hh);
                    for (int ti = 0; ti < 3; ti++) {
                        final float t = ti == 0 ? tc : (ti == 1 ? tc - thh : tc + thh);
                        final float d = t - m;
                        if (Math.abs(d) < snap && Math.abs(d) < Math.abs(bestD)) { bestD = d; bestT = t; }
                    }
                }
            }
        }
        if (!Float.isNaN(bestT)) {
            cy += bestD;
            if (Float.isNaN(snapTy) || Math.abs(snapTy - bestT) > 0.5f) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            snapTy = bestT;
            fadeTo(guides[1], centerHitY, 100);
            align[1].setTranslationY(bestT);
            fadeTo(align[1], !centerHitY, 100);
        } else {
            snapTy = Float.NaN;
            fadeTo(guides[1], false, 100);
            fadeTo(align[1], false, 100);
        }
        snapOut[0] = cx - w / 2f;
        snapOut[1] = cy - h / 2f;
        return snapOut;
    }

    private static void showTrash(View trash) {
        trash.setVisibility(View.VISIBLE);
        trash.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
    }

    private static void hideTrashAndGuides(ViewGroup parent, View trash, View[] guides) {
        trash.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(150)
            .withEndAction(() -> trash.setVisibility(View.GONE)).start();
        setTrashArmed(trash, false);
        hideSnapChrome(parent);
    }

    private static void setTrashArmed(View trash, boolean armed) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        int dp = dp(trash.getContext());
        if (armed) {
            bg.setColor(0xFFFF3B30);
            bg.setStroke((int) (1.5f * dp), 0xFFFFFFFF);
            trash.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).start();
        } else {
            bg.setColor(0xCC1A1A1A);
            bg.setStroke((int) (1.5f * dp), 0x66FFFFFF);
            trash.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
        }
        trash.setBackground(bg);
    }

    /** True if the dragged sticker's centre currently sits over (or within half a pill of) the trash pill. */
    private static boolean overlaps(View sticker, View trash) {
        float sx = sticker.getX() + sticker.getWidth() / 2f;
        float sy = sticker.getY() + sticker.getHeight() / 2f;
        float tx = trash.getX() + trash.getWidth() / 2f;
        float ty = trash.getY() + trash.getHeight() / 2f;
        return Math.abs(sx - tx) <= trash.getWidth() && Math.abs(sy - ty) <= trash.getHeight();
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static String jsonStr(String json, String key, String def) {
        // BUG FIX (music sticker silent in status viewer): android's
        // org.json JSONObject.toString() escapes every "/" as "\/", so after the
        // viewer re-serialises the sticker (renderStickers → getJSONObject(i)
        // .toString()) a soundUrl arrives as  https:\/\/host\/a.mp3 .  The old
        // regex-only reader below never un-escaped that, so MediaPlayer got a
        // URL full of backslashes and failed silently — while the Sound Detail
        // sheet still played because it re-resolves the audio from soundId.
        // Fix: read the value with a real JSON parser (handles escaped slash, backslash, quote and unicode escapes),
        // and only fall back to the manual scan (now also un-escaping) if the
        // string isn't valid JSON.
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            Object v = o.opt(key);
            if (v instanceof String) return (String) v;
            // Key missing, or a bare number/bool (jsonNum() handles those) → default,
            // same as the old quoted-string-only behaviour.
            return def;
        } catch (Exception parseFail) {
            // fall through to manual scan
        }
        try {
            String search = "\"" + key + "\":\"";
            int start = json.indexOf(search);
            if (start < 0) return def;
            start += search.length();
            int end = json.indexOf("\"", start);
            if (end < 0) return def;
            return json.substring(start, end)
                    .replace("\\/", "/")
                    .replace("\\\"", "\"");
        } catch (Exception e) { return def; }
    }

    /**
     * Like {@link #jsonStr}, but for bare numeric JSON values (e.g. "scale":1.2,
     * "posXRatio":0.35) which are written WITHOUT surrounding quotes — jsonStr's
     * quote-delimited search never matches those and would always fall through
     * to the default.
     */
    /** Keeps 0..1 values, but preserves the -1 "not set" sentinel. */
    private static float clamp01(float f) { return f < 0f ? -1f : Math.min(1f, f); }

    private static String jsonNum(String json, String key, String def) {
        try {
            String search = "\"" + key + "\":";
            int start = json.indexOf(search);
            if (start < 0) return def;
            start += search.length();
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end))
                    || json.charAt(end) == '-' || json.charAt(end) == '.'
                    || json.charAt(end) == 'e' || json.charAt(end) == 'E'
                    || json.charAt(end) == '+')) {
                end++;
            }
            if (end == start) return def;
            return json.substring(start, end);
        } catch (Exception e) { return def; }
    }

    private static int dp(Context ctx) {
        return (int) ctx.getResources().getDisplayMetrics().density;
    }

    /** Darken a colour by the given factor (0 = black, 1 = original). */
    private static int darken(int color, float factor) {
        return Color.argb(Color.alpha(color),
            (int)(Color.red(color)   * factor),
            (int)(Color.green(color) * factor),
            (int)(Color.blue(color)  * factor));
    }
}
