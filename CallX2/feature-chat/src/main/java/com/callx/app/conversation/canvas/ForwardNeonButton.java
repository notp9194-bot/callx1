package com.callx.app.conversation.canvas;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.CornerPathEffect;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Neon "share/forward" button used beside media bubbles (quick-forward).
 *
 * Static look  : dark disc + glowing violet ring + white curved-arrow.
 * Tap animation: 9-frame timeline (0.0s … 1.6s) —
 *   0.0  solid glowing ring
 *   0.1  shock-wave ring expands + bright spark at the right
 *   0.3  ring starts converting to dashes (spark head travels round)
 *   0.6  more dashes
 *   0.8  fully dotted ring
 *   1.0  sparkle particles burst outward
 *   1.2  arrow dashes right with motion trail + speed lines
 *   1.4  arrow touches ring edge, spark flare  (== ACTION_AT_MS, click fires here)
 *   1.6  solid glowing ring, arrow back at centre
 *
 * Zero allocation in {@link #draw}. One painter per canvas view (lazy);
 * the idle look is a static, size-keyed cached Bitmap (see {@link #staticBitmap}).
 */
final class ForwardNeonButton {

    /** Whole animation length. */
    static final long DURATION_MS  = 1600L;
    /** Moment (ms) the real forward action is fired — arrow "launch" frame. */
    static final long ACTION_AT_MS = 1400L;

    /** True when the user turned system animations off (animator scale 0):
     *  callers skip the animation and act immediately. */
    static boolean motionReduced(android.content.Context c) {
        try {
            return android.provider.Settings.Global.getFloat(c.getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── DARK palette (original neon look) ───────────────────────────
    private static final int D_RING  = 0xFFB87CFF;
    private static final int D_GLOW  = 0xFF7A2BFF;
    private static final int D_DISC  = 0xF2090514;
    private static final int D_TRAIL = 0xFFC9A0FF;
    private static final int D_ARROW = 0xFFFFFFFF;
    private static final int D_HEAD  = 0xFFFFFFFF;
    // ── LIGHT palette: light disc + deeper, saturated violet so the ring,
    //    glow, sparks, trail and arrow keep contrast on light wallpapers ──
    private static final int L_RING  = 0xFF7A2BFF;
    private static final int L_GLOW  = 0xFF7A2BFF;
    private static final int L_DISC  = 0xF5E6DAFF;   // slightly deeper so the disc edge reads on white
    private static final int L_TRAIL = 0xFF8B4DFF;
    private static final int L_ARROW = 0xFF5A14D6;
    private static final int L_HEAD  = 0xFF3A0CA3;

    private int ringC = D_RING, glowC = D_GLOW, discC = D_DISC,
                trailC = D_TRAIL, arrowC = D_ARROW, headC = D_HEAD;
    private float glowBoost = 1f;
    private float sparkScale = 1f;
    private boolean themeDark = true;

    private static final int DOTS = 28;
    private static final int SPARKS = 12;
    private static final float[] GLOW_K = {3.2f, 2.0f, 1.0f};
    private static final int[] GLOW_A = {0x26, 0x3A, 0x60};
    private static final float[] LINE_Y = {-0.22f, 0.02f, 0.26f};
    private static final float[] LINE_LEN = {0.55f, 0.8f, 0.45f};

    private final Paint disc   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotted = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval   = new RectF();
    private final Path arrow   = new Path();

    private float builtForSize = -1f;

    ForwardNeonButton() {
        disc.setStyle(Paint.Style.FILL);
        disc.setColor(discC);

        glow.setStyle(Paint.Style.STROKE);
        glow.setColor(glowC);

        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(ringC);
        ring.setStrokeCap(Paint.Cap.ROUND);

        dotted.setStyle(Paint.Style.STROKE);
        dotted.setColor(ringC);
        dotted.setStrokeCap(Paint.Cap.ROUND);

        fill.setStyle(Paint.Style.FILL);
        fill.setColor(arrowC);
        fill.setPathEffect(new CornerPathEffect(1.4f));

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(trailC);

        // Material "reply" glyph mirrored → curved forward/share arrow (24-unit box).
        arrow.moveTo(14f, 9f);
        arrow.lineTo(14f, 5f);
        arrow.lineTo(21f, 12f);
        arrow.lineTo(14f, 19f);
        arrow.lineTo(14f, 14.9f);
        arrow.cubicTo(9f, 14.9f, 5.5f, 16.5f, 3f, 20f);
        arrow.cubicTo(4f, 15f, 7f, 10f, 14f, 9f);
        arrow.close();
    }

    /** Switch palette (cheap no-op when the mode did not change). */
    private void applyTheme(boolean dark) {
        if (dark == themeDark) return;
        themeDark = dark;
        ringC  = dark ? D_RING  : L_RING;
        glowC  = dark ? D_GLOW  : L_GLOW;
        discC  = dark ? D_DISC  : L_DISC;
        trailC = dark ? D_TRAIL : L_TRAIL;
        arrowC = dark ? D_ARROW : L_ARROW;
        headC  = dark ? D_HEAD  : L_HEAD;
        glowBoost = dark ? 1f : 1.7f;
        sparkScale = dark ? 1f : 1.35f;
        disc.setColor(discC);
        glow.setColor(glowC);
        ring.setColor(ringC);
        dotted.setColor(ringC);
        fill.setColor(arrowC);
        line.setColor(trailC);
    }

    /** ms < 0 or >= DURATION → idle (static) look. */
    void draw(Canvas c, float left, float top, float size, long ms, boolean dark) {
        applyTheme(dark);
        final float cx = left + size / 2f, cy = top + size / 2f;
        final float R  = size * 0.32f;               // ring radius
        final float sw = Math.max(1.2f, size * 0.055f);

        if (builtForSize != size) {
            builtForSize = size;
            float circ = (float) (2.0 * Math.PI * R);
            float period = circ / DOTS;
            float on = Math.max(0.01f, period * 0.5f - sw);
            dotted.setPathEffect(new DashPathEffect(new float[]{on, period - on}, 0f));
        }
        ring.setStrokeWidth(sw);
        dotted.setStrokeWidth(sw);
        oval.set(cx - R, cy - R, cx + R, cy + R);

        final boolean idle = ms < 0 || ms >= DURATION_MS;

        // disc: dark interior in dark mode, light lavender in light mode
        disc.setAlpha(dark ? 0xF2 : 0xF5);
        c.drawCircle(cx, cy, R + sw * 0.4f, disc);
        if (!dark) {
            // Light disc blends into a light wallpaper and looked smaller than the
            // dark one: a soft violet edge at the SAME radius keeps the circle's
            // visible size identical in both modes.
            ring.setStrokeWidth(sw * 0.6f);
            ring.setAlpha(120);
            c.drawCircle(cx, cy, R + sw * 0.4f, ring);
            ring.setStrokeWidth(sw);
            ring.setAlpha(255);
        }

        if (idle) {
            drawGlow(c, 0f, 360f, sw, 1f);
            ring.setAlpha(255);
            c.drawCircle(cx, cy, R, ring);
            drawArrow(c, cx, cy, R, 0f, 1f, 1f);
            return;
        }

        // glow breathing at start and end
        float pulse = 1f + 0.35f * bump(ms, 0, 300) + 0.35f * bump(ms, 1450, 1600);

        // ── ring body ────────────────────────────────────────────────
        if (ms < 200) {                                    // frames 1-2: solid
            drawGlow(c, 0f, 360f, sw, pulse);
            ring.setAlpha(255);
            c.drawCircle(cx, cy, R, ring);
        } else if (ms < 850) {                             // frames 3-5: solid → dashes
            float p = ease((ms - 200) / 650f);
            float sweep = 360f * p;
            drawGlow(c, 0f, 360f, sw, 0.8f);
            dotted.setAlpha(255);
            if (sweep > 0.5f) c.drawArc(oval, 0f, sweep, false, dotted);
            ring.setAlpha(255);
            if (sweep < 359.5f) c.drawArc(oval, sweep, 360f - sweep, false, ring);
        } else if (ms < 1250) {                            // frames 5-7: full dots
            drawGlow(c, 0f, 360f, sw, 0.6f);
            dotted.setAlpha(255);
            c.drawArc(oval, 0f, 359.9f, false, dotted);
        } else {                                           // frames 8-9: dots → solid
            float q = (ms - 1250) / 350f;
            drawGlow(c, 0f, 360f, sw, (0.6f + 0.4f * q) * pulse);
            dotted.setAlpha((int) (255 * (1f - q)));
            c.drawArc(oval, 0f, 359.9f, false, dotted);
            ring.setAlpha((int) (255 * q));
            c.drawCircle(cx, cy, R, ring);
        }

        // ── shock-wave ring (frame 2) ────────────────────────────────
        if (ms >= 60 && ms < 420) {
            float p = (ms - 60) / 360f;
            line.setColor(glowC);
            line.setStrokeWidth(sw * 0.6f);
            line.setAlpha((int) (140 * (1f - p)));
            c.drawCircle(cx, cy, R * (1f + 0.45f * p), line);
            line.setColor(trailC);
        }

        // ── sparkle burst (frame 6) ──────────────────────────────────
        if (ms >= 800 && ms < 1250) {
            float pp = (ms - 800) / 450f;
            float a = (float) Math.sin(Math.PI * pp);
            fill.setColor(trailC);
            for (int i = 0; i < SPARKS; i++) {
                float ang = (float) Math.toRadians(i * 30f + (i % 3) * 7f);
                float base = R * (1.12f + 0.28f * (((i * 37) % 10) / 10f));
                float rad = base + R * 0.5f * pp;
                float s = sparkScale * sw * (0.45f + 0.55f * (((i * 13) % 5) / 5f));
                fill.setAlpha((int) (230 * a));
                c.drawCircle(cx + (float) Math.cos(ang) * rad,
                             cy + (float) Math.sin(ang) * rad, s, fill);
            }
            fill.setColor(arrowC);
        }

        // ── travelling spark head ────────────────────────────────────
        if (ms >= 100 && ms < 1500) {
            float ang;
            if (ms < 200)       ang = 0f;
            else if (ms < 850)  ang = 360f * ease((ms - 200) / 650f);
            else                ang = 0f;
            float a = ms < 200 ? (ms - 100) / 100f : (ms > 1400 ? (1500 - ms) / 100f : 1f);
            float rad = (float) Math.toRadians(ang);
            float sx = cx + (float) Math.cos(rad) * R;
            float sy = cy + (float) Math.sin(rad) * R;
            float flare = (ms >= 1250 && ms <= 1450) ? 1.6f : 1f;
            glow.setStyle(Paint.Style.FILL);
            glow.setAlpha((int) (110 * a));
            c.drawCircle(sx, sy, sw * 2.6f * flare, glow);
            glow.setStyle(Paint.Style.STROKE);
            fill.setColor(headC);
            fill.setAlpha((int) (255 * a));
            c.drawCircle(sx, sy, sw * 1.05f * flare, fill);
            fill.setColor(arrowC);
            fill.setAlpha(255);
        }

        // ── arrow (frames 7-9) ───────────────────────────────────────
        float off = 0f, aAlpha = 1f, scale = 1f, trail = 0f;
        if (ms >= 1000 && ms < 1200) {
            float p = (ms - 1000) / 200f;
            off = 0.20f * R * p * p;
            trail = p * 0.6f;
        } else if (ms >= 1200 && ms < 1400) {
            float p = (ms - 1200) / 200f;
            off = R * (0.20f + 0.25f * p);
            trail = 0.6f + 0.4f * p;
        } else if (ms >= 1400 && ms < 1480) {
            off = 0.45f * R;
            trail = 1f - (ms - 1400) / 80f;
            aAlpha = trail;
        } else if (ms >= 1480) {
            float p = (ms - 1480) / 120f;
            aAlpha = p;
            scale = 0.85f + 0.15f * p;
        }
        if (trail > 0.02f) drawTrail(c, cx, cy, R, sw, off, trail);
        drawArrow(c, cx, cy, R, off, aAlpha, scale);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void drawGlow(Canvas c, float start, float sweep, float sw, float mul) {
        final float[] k = GLOW_K;
        final int[] al = GLOW_A;
        for (int i = 0; i < 3; i++) {
            glow.setStrokeWidth(sw * (1f + k[i]));
            glow.setAlpha(Math.min(255, (int) (al[i] * mul * glowBoost)));
            c.drawCircle(oval.centerX(), oval.centerY(), oval.width() / 2f, glow);
        }
    }

    private void drawArrow(Canvas c, float cx, float cy, float R, float off, float alpha, float scale) {
        if (alpha <= 0.01f) return;
        float s = (R * 1.12f / 18f) * scale;
        c.save();
        c.translate(cx + off, cy);
        c.scale(s, s);
        c.translate(-12f, -12.5f);
        fill.setAlpha((int) (255 * Math.min(1f, alpha)));
        c.drawPath(arrow, fill);
        c.restore();
        fill.setAlpha(255);
    }

    private void drawTrail(Canvas c, float cx, float cy, float R, float sw, float off, float amt) {
        // ghost copies of the arrow behind it
        float gap = R * 0.14f;
        for (int k = 1; k <= 3; k++) {
            float s = R * 1.12f / 18f;
            c.save();
            c.translate(cx + off - k * gap, cy);
            c.scale(s, s);
            c.translate(-12f, -12.5f);
            fill.setColor(trailC);
            fill.setAlpha((int) (110f * amt / k));
            c.drawPath(arrow, fill);
            c.restore();
        }
        fill.setColor(arrowC);
        fill.setAlpha(255);
        // speed lines
        line.setStrokeWidth(sw * 0.55f);
        float x1 = cx + off - R * 0.55f;
        final float[] ys = LINE_Y;
        final float[] len = LINE_LEN;
        for (int i = 0; i < 3; i++) {
            line.setAlpha((int) ((themeDark ? 150 : 210) * amt));
            float y = cy + ys[i] * R;
            c.drawLine(x1 - len[i] * R, y, x1, y, line);
        }
    }

    private static float bump(long ms, long from, long to) {
        if (ms <= from || ms >= to) return 0f;
        return (float) Math.sin(Math.PI * (ms - from) / (float) (to - from));
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    // ── idle bitmap (cached) ─────────────────────────────────────────

    private static final android.util.SparseArray<Bitmap> IDLE_CACHE = new android.util.SparseArray<>(4);

    static synchronized Bitmap staticBitmap(float size, boolean dark) {
        int key = Math.round(size * 10f) * 2 + (dark ? 1 : 0);
        Bitmap cached = IDLE_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        int w = Math.max(1, Math.round(size));
        Bitmap bmp = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888);
        bmp.setDensity(Bitmap.DENSITY_NONE);
        new ForwardNeonButton().draw(new Canvas(bmp), 0f, 0f, w, -1L, dark);
        IDLE_CACHE.put(key, bmp);
        return bmp;
    }
}
