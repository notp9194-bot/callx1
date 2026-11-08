package com.callx.app.compose;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * v772 — one definition of the template filters / animations ({@link TemplateSpec#FILTERS}, {@link TemplateSpec#ANIMS}),
 * shared by the Template Editor (swatches), the composer (photo boxes) and {@link TemplateVideoCompositor} (GL shader),
 * so a box looks the same everywhere.
 *
 * Filters are plain 4x5 Android colour matrices (offsets 0..255). Animations are a function of the slot's progress p (0..1).
 */
public final class TemplateEffects {

    private TemplateEffects() { }

    /** Max extra zoom of the zoom / pan animations (1.0 = none). */
    private static final float ZOOM_MAX = 1.25f;
    private static final float PAN_ZOOM = 1.2f;
    /** Fraction of the slot used by the fade-in. */
    private static final float FADE_PART = 0.25f;

    // ── filters ──────────────────────────────────────────────────────────────

    public static boolean isNatural(@Nullable String key) {
        return key == null || key.isEmpty() || TemplateSpec.DEF_FILTER.equals(key);
    }

    /** 4x5 colour matrix for the filter, or null for "natural" (no change). */
    @Nullable
    public static float[] matrix(@Nullable String key) {
        if (isNatural(key)) return null;
        switch (key) {
            case "warm":
                return new float[]{
                        1.08f, 0, 0, 0, 10,
                        0, 1.00f, 0, 0, 2,
                        0, 0, 0.88f, 0, -8,
                        0, 0, 0, 1, 0};
            case "cool":
                return new float[]{
                        0.90f, 0, 0, 0, -6,
                        0, 1.00f, 0, 0, 0,
                        0, 0, 1.10f, 0, 10,
                        0, 0, 0, 1, 0};
            case "bw": {
                final ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0f);
                return cm.getArray().clone();
            }
            case "vivid": {
                final ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(1.4f);
                final float c = 1.08f, off = (1f - c) * 128f;
                cm.postConcat(new ColorMatrix(new float[]{
                        c, 0, 0, 0, off,
                        0, c, 0, 0, off,
                        0, 0, c, 0, off,
                        0, 0, 0, 1, 0}));
                return cm.getArray().clone();
            }
            case "fade": {
                final ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0.82f);
                final float c = 0.86f, off = (1f - c) * 128f + 12f;
                cm.postConcat(new ColorMatrix(new float[]{
                        c, 0, 0, 0, off,
                        0, c, 0, 0, off,
                        0, 0, c, 0, off,
                        0, 0, 0, 1, 0}));
                return cm.getArray().clone();
            }
            default:
                return null;
        }
    }

    /** Column-major mat4 for the GL shader: {@code rgb' = (M * vec4(rgb, 1)).rgb}. Identity for "natural". */
    @NonNull
    public static float[] glMatrix(@Nullable String key) {
        final float[] a = matrix(key);
        final float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1f;
        if (a == null) return m;
        for (int r = 0; r < 3; r++) {
            m[0 * 4 + r] = a[r * 5];
            m[1 * 4 + r] = a[r * 5 + 1];
            m[2 * 4 + r] = a[r * 5 + 2];
            m[3 * 4 + r] = a[r * 5 + 4] / 255f;
        }
        return m;
    }

    @Nullable
    public static ColorMatrixColorFilter colorFilter(@Nullable String key) {
        final float[] a = matrix(key);
        return a == null ? null : new ColorMatrixColorFilter(new ColorMatrix(a));
    }

    /** Returns {@code src} itself for "natural", else a filtered copy (the source is never touched or recycled). */
    @NonNull
    public static Bitmap applyFilter(@NonNull Bitmap src, @Nullable String key) {
        final ColorMatrixColorFilter f = colorFilter(key);
        if (f == null || src.getWidth() <= 0 || src.getHeight() <= 0) return src;
        try {
            final Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(f);
            new Canvas(out).drawBitmap(src, 0f, 0f, p);
            return out;
        } catch (OutOfMemoryError e) {
            return src;   // better an unfiltered box than a crash
        }
    }

    // ── animations ───────────────────────────────────────────────────────────

    public static boolean hasAnim(@Nullable String key) {
        return key != null && !key.isEmpty() && !TemplateSpec.DEF_ANIM.equals(key);
    }

    /**
     * State of an animation at progress {@code p} of the slot (0..1).
     * @param out {zoom (>= 1), panX (-1..1), panY (-1..1), alpha (0..1)}
     */
    public static void animState(@Nullable String key, float p, @NonNull float[] out) {
        out[0] = 1f; out[1] = 0f; out[2] = 0f; out[3] = 1f;
        if (!hasAnim(key)) return;
        final float t = smooth(Math.max(0f, Math.min(1f, p)));
        switch (key) {
            case "zoom_in":  out[0] = 1f + (ZOOM_MAX - 1f) * t; break;
            case "zoom_out": out[0] = ZOOM_MAX - (ZOOM_MAX - 1f) * t; break;
            case "pan_l":    out[0] = PAN_ZOOM; out[1] = -1f + 2f * t; break;   // window moves right → picture drifts left
            case "pan_r":    out[0] = PAN_ZOOM; out[1] = 1f - 2f * t; break;
            case "fade":     out[3] = Math.min(1f, Math.max(0f, p) / FADE_PART); break;
            default: break;
        }
    }

    private static float smooth(float t) { return t * t * (3f - 2f * t); }
}
