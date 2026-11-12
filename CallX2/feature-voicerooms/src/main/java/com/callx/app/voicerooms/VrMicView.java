package com.callx.app.voicerooms;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.Nullable;

/**
 * VrMicView — banner ka premium "3D" studio mic illustration (Canvas se bana,
 * koi image asset nahi => crisp har density pe, dark/light dono me chalta hai).
 *
 * Design box 100 x 104 units; view me center + scale hota hai.
 * setFloating(true) => halka upar-neeche tairne wala animation (sirf room banner pe).
 */
public class VrMicView extends View {

    private static final float W = 100f, H = 104f;

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbitBack = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbitFront = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yoke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stem = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint head = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path headPath = new Path();
    private final Path starPath = new Path();
    private final RectF tmp = new RectF();

    private boolean floating;
    private ObjectAnimator bob;

    public VrMicView(Context c) { this(c, null); }
    public VrMicView(Context c, @Nullable AttributeSet a) { this(c, a, 0); }
    public VrMicView(Context c, @Nullable AttributeSet a, int s) {
        super(c, a, s);
        init();
    }

    private void init() {
        glow.setShader(new RadialGradient(50, 52, 48,
                0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));

        orbitBack.setStyle(Paint.Style.STROKE);
        orbitBack.setStrokeWidth(1.4f);
        orbitBack.setColor(0x44FFFFFF);
        orbitFront.setStyle(Paint.Style.STROKE);
        orbitFront.setStrokeWidth(1.8f);
        orbitFront.setColor(0xAAFFFFFF);
        orbitFront.setStrokeCap(Paint.Cap.ROUND);

        yoke.setStyle(Paint.Style.STROKE);
        yoke.setStrokeWidth(4.5f);
        yoke.setStrokeCap(Paint.Cap.ROUND);
        yoke.setShader(new LinearGradient(21, 15, 79, 73,
                0xFFE0D4FF, 0xFF8B5CF6, Shader.TileMode.CLAMP));

        stem.setShader(new LinearGradient(47.5f, 0, 52.5f, 0,
                0xFFC4B5FD, 0xFF7C3AED, Shader.TileMode.CLAMP));
        base.setShader(new LinearGradient(0, 92, 0, 99,
                0xFFA78BFA, 0xFF5B21B6, Shader.TileMode.CLAMP));

        head.setShader(new LinearGradient(30, 6, 70, 62,
                new int[]{0xFFC4B5FD, 0xFF7C3AED, 0xFF3B1A9E},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        dots.setColor(0x40FFFFFF);
        band.setShader(new LinearGradient(30, 0, 70, 0,
                0xFF38BDF8, 0xFF2563EB, Shader.TileMode.CLAMP));
        shine.setColor(0x55FFFFFF);
        star.setColor(0xFFFFFFFF);

        headPath.addRoundRect(new RectF(30, 6, 70, 62), 20, 20, Path.Direction.CW);
    }

    /** Halka float animation on/off (attach hone par chalta hai). */
    public void setFloating(boolean on) {
        floating = on;
        if (isAttachedToWindow()) { if (on) startBob(); else stopBob(); }
    }

    private void startBob() {
        if (bob != null) return;
        float amp = 4f * getResources().getDisplayMetrics().density;
        bob = ObjectAnimator.ofFloat(this, View.TRANSLATION_Y, -amp, amp);
        bob.setDuration(1800);
        bob.setRepeatMode(ObjectAnimator.REVERSE);
        bob.setRepeatCount(ObjectAnimator.INFINITE);
        bob.setInterpolator(new AccelerateDecelerateInterpolator());
        bob.start();
    }

    private void stopBob() {
        if (bob != null) { bob.cancel(); bob = null; setTranslationY(0f); }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (floating) startBob();
    }

    @Override protected void onDetachedFromWindow() {
        stopBob();
        super.onDetachedFromWindow();
    }

    private void sparkle(Canvas c, float cx, float cy, float r, int alpha) {
        starPath.reset();
        starPath.moveTo(cx, cy - r);
        starPath.quadTo(cx, cy, cx + r, cy);
        starPath.quadTo(cx, cy, cx, cy + r);
        starPath.quadTo(cx, cy, cx - r, cy);
        starPath.quadTo(cx, cy, cx, cy - r);
        star.setAlpha(alpha);
        c.drawPath(starPath, star);
    }

    @Override protected void onDraw(Canvas c) {
        float s = Math.min(getWidth() / W, getHeight() / H);
        if (s <= 0) return;
        c.save();
        c.translate((getWidth() - W * s) / 2f, (getHeight() - H * s) / 2f);
        c.scale(s, s);

        // soft glow
        c.drawCircle(50, 52, 48, glow);

        // orbit ring (back half, full ellipse faint)
        c.save();
        c.rotate(-18, 50, 58);
        tmp.set(4, 45, 96, 71);
        c.drawOval(tmp, orbitBack);
        c.restore();

        // yoke (cradle) + stem + base
        tmp.set(21, 15, 79, 73);
        c.drawArc(tmp, 0, 180, false, yoke);
        tmp.set(47.5f, 72, 52.5f, 93);
        c.drawRect(tmp, stem);
        tmp.set(31, 92, 69, 99);
        c.drawRoundRect(tmp, 3.5f, 3.5f, base);

        // mic head
        c.drawPath(headPath, head);
        c.save();
        c.clipPath(headPath);
        // blue ring band
        tmp.set(30, 38, 70, 45);
        c.drawRect(tmp, band);
        // grille dots
        int row = 0;
        for (float y = 13f; y < 58f; y += 6.2f, row++) {
            if (y > 36f && y < 47f) continue; // band ke neeche mat banao
            for (float x = 34f + (row % 2) * 3.1f; x < 67f; x += 6.2f) {
                c.drawCircle(x, y, 1.15f, dots);
            }
        }
        // left shine
        tmp.set(34, 12, 38.5f, 46);
        c.drawRoundRect(tmp, 2.2f, 2.2f, shine);
        c.restore();

        // orbit ring front (lower half passes in front of the mic)
        c.save();
        c.rotate(-18, 50, 58);
        tmp.set(4, 45, 96, 71);
        c.drawArc(tmp, 10, 160, false, orbitFront);
        c.restore();

        // sparkles
        sparkle(c, 86, 14, 7f, 235);
        sparkle(c, 12, 80, 4.5f, 190);
        sparkle(c, 90, 72, 3.5f, 160);

        c.restore();
    }
}
