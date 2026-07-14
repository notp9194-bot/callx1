package com.callx.app.conversation.controllers;

import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;

/**
 * The six filters shown in the "swipe up for filters" carousel on
 * {@link MediaEditActivity} (Screenshot 3): None, Pop, B&W, Cool, Chrome,
 * Film. Each is expressed as a {@link ColorMatrix} so it can be applied
 * live to the preview ImageView via a {@link ColorMatrixColorFilter} AND
 * baked permanently into the final bitmap at send-time using the exact
 * same matrix (Canvas + Paint#setColorFilter) — one definition, two use
 * sites, so the sent photo always matches what was previewed.
 */
public final class MediaFilters {

    private MediaFilters() {}

    public static final String[] NAMES = {"None", "Pop", "B&W", "Cool", "Chrome", "Film"};

    /** @param index into {@link #NAMES}. */
    public static ColorMatrix matrixFor(int index) {
        switch (index) {
            case 1: return pop();
            case 2: return blackAndWhite();
            case 3: return cool();
            case 4: return chrome();
            case 5: return film();
            default: return identity();
        }
    }

    public static ColorMatrixColorFilter filterFor(int index) {
        return new ColorMatrixColorFilter(matrixFor(index));
    }

    private static ColorMatrix identity() {
        return new ColorMatrix();
    }

    /** Punchy: boosted saturation + a touch of contrast. */
    private static ColorMatrix pop() {
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(1.55f);
        ColorMatrix contrast = contrastMatrix(1.15f);
        sat.postConcat(contrast);
        return sat;
    }

    private static ColorMatrix blackAndWhite() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        return m;
    }

    /** Cool: pushes toward blue/cyan, pulls back red slightly. */
    private static ColorMatrix cool() {
        ColorMatrix m = new ColorMatrix(new float[]{
                0.92f, 0f,    0f,    0f, 0f,
                0f,    1.0f,  0f,    0f, 6f,
                0f,    0f,    1.14f, 0f, 12f,
                0f,    0f,    0f,    1f, 0f
        });
        return m;
    }

    /** Chrome: higher contrast, gently desaturated, cool-ish shadows. */
    private static ColorMatrix chrome() {
        ColorMatrix contrast = contrastMatrix(1.25f);
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.85f);
        contrast.postConcat(sat);
        return contrast;
    }

    /** Film: warm sepia-leaning tone with softened contrast. */
    private static ColorMatrix film() {
        ColorMatrix m = new ColorMatrix(new float[]{
                1.08f, 0.04f, 0f,    0f, 10f,
                0.02f, 1.0f,  0f,    0f, 6f,
                0f,    0.02f, 0.88f, 0f, -4f,
                0f,    0f,    0f,    1f, 0f
        });
        ColorMatrix sat = new ColorMatrix();
        sat.setSaturation(0.9f);
        m.postConcat(sat);
        return m;
    }

    private static ColorMatrix contrastMatrix(float contrast) {
        float translate = (-0.5f * contrast + 0.5f) * 255f;
        return new ColorMatrix(new float[]{
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
        });
    }
}
