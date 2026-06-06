package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * BubbleShapeManager — Controls the corner shape style of chat bubbles.
 *
 * Works alongside ChatThemeManager: colours come from theme, shape comes here.
 *
 * Usage:
 *   BubbleShapeManager.get(ctx).getCurrentShape()
 *   BubbleShapeManager.get(ctx).setShape(BubbleShapeManager.SHAPE_ROUND)
 *   // Then call ChatThemeManager.applyBubble(...) which reads shape automatically
 */
public class BubbleShapeManager {

    // ── Shape IDs ─────────────────────────────────────────────────────────
    /** Classic rounded corners — smooth on all sides */
    public static final int SHAPE_ROUND        = 0;
    /** WhatsApp-style: one sharp tail corner, rest rounded */
    public static final int SHAPE_TAIL         = 1;
    /** Fully pill-shaped bubbles */
    public static final int SHAPE_PILL         = 2;
    /** Almost square with very subtle rounding */
    public static final int SHAPE_SQUARE       = 3;
    /** Wavy / scalloped irregular corners */
    public static final int SHAPE_SQUIRCLE     = 4;

    public static final String[] SHAPE_NAMES = {
        "⬛ Classic Rounded",
        "💬 Tail (WhatsApp Style)",
        "💊 Pill",
        "🔲 Square",
        "🌀 Squircle"
    };

    public static final String[] SHAPE_DESC = {
        "Smooth rounded corners on all sides",
        "Pointed tail corner like WhatsApp",
        "Fully pill-shaped, very modern",
        "Minimal rounding — almost square",
        "Squircle — between square and circle"
    };

    // ── Corner radii (large = R_L, medium = R_M, small = R_S, tiny = R_T) ─
    // Values in dp — multiplied by density at runtime
    public static final float R_L = 20f;   // large round
    public static final float R_M = 12f;   // medium
    public static final float R_S = 4f;    // small / tail corner
    public static final float R_T = 2f;    // near-square
    public static final float R_PILL = 50f; // pill

    // ── SharedPreferences ─────────────────────────────────────────────────
    private static final String PREF_NAME  = "bubble_shape_prefs";
    private static final String KEY_SHAPE  = "bubble_shape";

    // ── Singleton ─────────────────────────────────────────────────────────
    private static BubbleShapeManager instance;
    private final SharedPreferences prefs;
    private int currentShape;

    private BubbleShapeManager(Context ctx) {
        prefs        = ctx.getApplicationContext()
                          .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        currentShape = prefs.getInt(KEY_SHAPE, SHAPE_TAIL);
    }

    public static BubbleShapeManager get(Context ctx) {
        if (instance == null) instance = new BubbleShapeManager(ctx);
        return instance;
    }

    // ── Public API ────────────────────────────────────────────────────────

    public int getCurrentShape() { return currentShape; }

    public void setShape(int shapeId) {
        currentShape = shapeId;
        prefs.edit().putInt(KEY_SHAPE, shapeId).apply();
    }

    /**
     * Returns the 8-value corner radii array (in px) for the given shape + side.
     * Order: [topLeft, topLeft, topRight, topRight, bottomRight, bottomRight, bottomLeft, bottomLeft]
     *
     * @param sent    true = sent message side (right), false = received (left)
     * @param density screen density from DisplayMetrics
     */
    public float[] getCornerRadii(boolean sent, float density) {
        switch (currentShape) {

            case SHAPE_ROUND:
                // All corners large — symmetric, clean
                return allCorners(R_L, density);

            case SHAPE_TAIL:
                // Sent: sharp bottom-right (the "tail")
                // Received: sharp top-left (the "tail")
                if (sent) {
                    return new float[]{
                        R_L * density, R_L * density,   // topLeft
                        R_L * density, R_L * density,   // topRight
                        R_S * density, R_S * density,   // bottomRight  ← tail
                        R_L * density, R_L * density    // bottomLeft
                    };
                } else {
                    return new float[]{
                        R_S * density, R_S * density,   // topLeft  ← tail
                        R_L * density, R_L * density,   // topRight
                        R_L * density, R_L * density,   // bottomRight
                        R_L * density, R_L * density    // bottomLeft
                    };
                }

            case SHAPE_PILL:
                // Fully pill — very large radius on all corners
                return allCorners(R_PILL, density);

            case SHAPE_SQUARE:
                // Tiny radius — nearly square, modern/minimal
                return allCorners(R_T, density);

            case SHAPE_SQUIRCLE:
                // Medium on all but slightly more on opposite diagonal
                // Creates a subtle squircle-like feel
                if (sent) {
                    return new float[]{
                        R_M * density, R_M * density,   // topLeft
                        R_L * density, R_L * density,   // topRight
                        R_M * density, R_M * density,   // bottomRight
                        R_L * density, R_L * density    // bottomLeft
                    };
                } else {
                    return new float[]{
                        R_L * density, R_L * density,   // topLeft
                        R_M * density, R_M * density,   // topRight
                        R_L * density, R_L * density,   // bottomRight
                        R_M * density, R_M * density    // bottomLeft
                    };
                }

            default:
                return allCorners(R_L, density);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private float[] allCorners(float r, float density) {
        float px = r * density;
        return new float[]{px, px, px, px, px, px, px, px};
    }
}
