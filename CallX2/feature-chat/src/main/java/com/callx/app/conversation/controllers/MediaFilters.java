package com.callx.app.conversation.controllers;

import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

/**
 * Filter definitions for the chat media editor's swipe-up filter carousel.
 *
 * Filters: None, Pop, B&W, Cool, Chrome, Film, Warm, Vivid, Fade
 *
 * Each is expressed as a {@link ColorMatrix} so it can be:
 *  1. Applied live as a {@link ColorMatrixColorFilter} on the preview ImageView
 *  2. Baked permanently into the final send-out bitmap (Canvas + Paint)
 *
 * One definition, two use-sites — the sent photo always matches the preview.
 */
public final class MediaFilters {

    private MediaFilters() {}

    public static final String[] NAMES = {
        "None", "Pop", "B&W", "Cool", "Chrome", "Film", "Warm", "Vivid", "Fade"
    };

    /** Representative swatch color for the filter thumbnail strip. */
    public static int previewColor(int index) {
        switch (index) {
            case 0:  return 0xFF808080; // None — neutral grey
            case 1:  return 0xFFFF7744; // Pop — warm orange punch
            case 2:  return 0xFF888888; // B&W — mid grey
            case 3:  return 0xFF4499CC; // Cool — sky blue
            case 4:  return 0xFF556677; // Chrome — steel blue-grey
            case 5:  return 0xFFBB9944; // Film — warm amber
            case 6:  return 0xFFFF9955; // Warm — sunset orange
            case 7:  return 0xFF8844CC; // Vivid — vibrant purple
            case 8:  return 0xFF99AAAA; // Fade — muted sage
            default: return 0xFF808080;
        }
    }

    /** @param index into {@link #NAMES}. */
    public static ColorMatrix matrixFor(int index) {
        switch (index) {
            case 1: return pop();
            case 2: return blackAndWhite();
            case 3: return cool();
            case 4: return chrome();
            case 5: return film();
            case 6: return warm();
            case 7: return vivid();
            case 8: return fade();
            default: return identity();
        }
    }

    public static ColorMatrixColorFilter filterFor(int index) {
        return new ColorMatrixColorFilter(matrixFor(index));
    }

    // ── Filter implementations ────────────────────────────────────────────

    private static ColorMatrix identity() {
        return new ColorMatrix();
    }

    /** Pop — boosted saturation + punch contrast. */
    private static ColorMatrix pop() {
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1.6f);
        ColorMatrix contrast = contrastMatrix(1.18f);
        sat.postConcat(contrast);
        return sat;
    }

    /** B&W — full desaturation. */
    private static ColorMatrix blackAndWhite() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        return m;
    }

    /** Cool — blue push, reduced red. */
    private static ColorMatrix cool() {
        return new ColorMatrix(new float[]{
                0.90f, 0f,    0f,    0f, -4f,
                0f,    1.0f,  0f,    0f,  6f,
                0f,    0f,    1.18f, 0f, 14f,
                0f,    0f,    0f,    1f,  0f
        });
    }

    /** Chrome — high contrast, gentle desaturation, cool shadows. */
    private static ColorMatrix chrome() {
        ColorMatrix contrast = contrastMatrix(1.28f);
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.82f);
        contrast.postConcat(sat);
        return contrast;
    }

    /** Film — warm sepia-leaning tone, softened contrast. */
    private static ColorMatrix film() {
        ColorMatrix m = new ColorMatrix(new float[]{
                1.10f, 0.05f, 0f,    0f, 12f,
                0.02f, 1.0f,  0f,    0f,  6f,
                0f,    0.02f, 0.86f, 0f, -4f,
                0f,    0f,    0f,    1f,  0f
        });
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.88f);
        m.postConcat(sat);
        return m;
    }

    /** Warm — sunset orange/yellow tint. */
    private static ColorMatrix warm() {
        return new ColorMatrix(new float[]{
                1.12f, 0.06f, 0f,    0f, 16f,
                0f,    1.02f, 0f,    0f,  8f,
                0f,    0f,    0.88f, 0f, -8f,
                0f,    0f,    0f,    1f,  0f
        });
    }

    /** Vivid — hyper-saturated with punchy contrast. */
    private static ColorMatrix vivid() {
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1.9f);
        ColorMatrix contrast = contrastMatrix(1.22f);
        sat.postConcat(contrast);
        return sat;
    }

    /** Fade — reduced contrast + slightly lifted blacks (matte look). */
    private static ColorMatrix fade() {
        ColorMatrix contrast = contrastMatrix(0.82f);
        // Lift shadows by adding to offset
        ColorMatrix lift = new ColorMatrix(new float[]{
                1f, 0f, 0f, 0f, 28f,
                0f, 1f, 0f, 0f, 28f,
                0f, 0f, 1f, 0f, 28f,
                0f, 0f, 0f, 1f,  0f
        });
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.78f);
        contrast.postConcat(lift);
        contrast.postConcat(sat);
        return contrast;
    }

    // ── Utility ───────────────────────────────────────────────────────────

    private static ColorMatrix contrastMatrix(float contrast) {
        float translate = (-0.5f * contrast + 0.5f) * 255f;
        return new ColorMatrix(new float[]{
                contrast, 0f,       0f,       0f, translate,
                0f,       contrast, 0f,       0f, translate,
                0f,       0f,       contrast, 0f, translate,
                0f,       0f,       0f,       1f, 0f
        });
    }
}
