package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * BubbleShapeManager — Controls the corner shape style of chat bubbles.
 *
 * Works alongside ChatThemeManager: colours come from theme, shape comes here.
 * 15 shape options total.
 */
public class BubbleShapeManager {

    // ── Shape IDs ─────────────────────────────────────────────────────────
    public static final int SHAPE_ROUND         = 0;   // Classic rounded, symmetric
    public static final int SHAPE_TAIL          = 1;   // WhatsApp-style tail corner
    public static final int SHAPE_PILL          = 2;   // Full pill
    public static final int SHAPE_SQUARE        = 3;   // Near-square minimal
    public static final int SHAPE_SQUIRCLE      = 4;   // Diagonal alternating medium/large
    public static final int SHAPE_SHARP_TAIL    = 5;   // Extra sharp tail (zero radius)
    public static final int SHAPE_DOUBLE_TAIL   = 6;   // Two sharp corners (top+bottom on tail side)
    public static final int SHAPE_LEAF          = 7;   // Leaf: two opposite sharp, two round
    public static final int SHAPE_CLOUD         = 8;   // Cloud: very large on top, small bottom
    public static final int SHAPE_DIAMOND       = 9;   // All corners at medium + asymmetric feel
    public static final int SHAPE_TEARDROP      = 10;  // Large top, tiny bottom
    public static final int SHAPE_WAVE          = 11;  // Alternating small/large all 4 corners
    public static final int SHAPE_NOTCH         = 12;  // Flat top, rounded bottom
    public static final int SHAPE_PEBBLE        = 13;  // Slightly asymmetric, organic feel
    public static final int SHAPE_SHARP_ALL     = 14;  // Perfectly sharp — zero radius everywhere
    public static final int SHAPE_RIBBON        = 15;  // Flat top + round bottom (sent), opposite (recv)
    public static final int SHAPE_SHIELD        = 16;  // Arched top, flat bottom — badge/shield look
    public static final int SHAPE_TICKET        = 17;  // One side pill, one side square — ticket stub
    public static final int SHAPE_GEM           = 18;  // Large TL+BR, tiny TR+BL — gem-cut diagonal
    public static final int SHAPE_SOFT_TAIL     = 19;  // Gentle medium tail — softer than WhatsApp
    public static final int SHAPE_BULLET        = 20;  // Flat leading side, pill trailing side
    public static final int SHAPE_RAINDROP      = 21;  // One XL bulge corner, rest small
    public static final int SHAPE_TOAST         = 22;  // Round top + sides, flat bottom
    public static final int SHAPE_ARCH          = 23;  // Very large top corners, zero bottom
    public static final int SHAPE_BOWTIE        = 24;  // Outer corners large, inner corners tiny

    public static final String[] SHAPE_NAMES = {
        "⬛ Classic Rounded",
        "💬 Tail — WhatsApp",
        "💊 Pill",
        "🔲 Square",
        "🌀 Squircle",
        "📐 Sharp Tail",
        "🦋 Double Tail",
        "🍃 Leaf",
        "☁️ Cloud",
        "🔷 Diamond Cut",
        "💧 Teardrop",
        "〰️ Wave",
        "🪟 Notch",
        "🪨 Pebble",
        "🗡️ Sharp Edge",
        "🎀 Ribbon",
        "🛡️ Shield",
        "🎫 Ticket",
        "💎 Gem Cut",
        "🫧 Soft Tail",
        "🔫 Bullet",
        "🌧️ Raindrop",
        "🍞 Toast",
        "🏛️ Arch",
        "🎗️ Bowtie"
    };

    public static final String[] SHAPE_DESC = {
        "Smooth large rounding on all corners",
        "One sharp tail corner, like WhatsApp",
        "Fully pill-shaped, very modern",
        "Minimal rounding — almost square",
        "Alternating medium/large diagonals",
        "Zero-radius tail — extra crisp edge",
        "Sharp on both tail-side corners",
        "Two sharp, two round — leaf silhouette",
        "Big top curves, tighter bottom corners",
        "Uniform medium — clean diamond feel",
        "Very large top, tiny bottom rounding",
        "Small-large alternating all corners",
        "Flat sharp top, fully rounded bottom",
        "Organic: slight tilt in corner sizes",
        "Zero radius — perfectly sharp edges",
        "Flat top corners, rounded bottom corners",
        "Fully arched top, flat square bottom",
        "Pill on one side, square on the other",
        "Large diagonal corners, tiny opposite pair",
        "Medium tail — softer than WhatsApp style",
        "Flat on leading edge, pill on trailing side",
        "One large bulge corner, three small corners",
        "Rounded top and sides, flat zero bottom",
        "Very arched top corners, sharp flat bottom",
        "Outer corners large, inner corners tiny"
    };

    // ── Corner radii constants (dp) ───────────────────────────────────────
    private static final float R_XL   = 28f;  // extra large
    private static final float R_L    = 20f;  // large
    private static final float R_M    = 12f;  // medium
    private static final float R_S    = 5f;   // small
    private static final float R_XS   = 2f;   // near-zero / near-square
    private static final float R_ZERO = 0f;   // perfectly sharp
    private static final float R_PILL = 50f;  // pill

    // ── SharedPreferences ─────────────────────────────────────────────────
    private static final String PREF_NAME = "bubble_shape_prefs";
    private static final String KEY_SHAPE = "bubble_shape";

    // ── Singleton ─────────────────────────────────────────────────────────
    private static BubbleShapeManager instance;
    private final SharedPreferences prefs;
    private final Context appContext;
    private int currentShape;

    private BubbleShapeManager(Context ctx) {
        appContext   = ctx.getApplicationContext();
        prefs        = appContext
                          .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        currentShape = prefs.getInt(KEY_SHAPE, SHAPE_TAIL);
    }

    public static BubbleShapeManager get(Context ctx) {
        if (instance == null) instance = new BubbleShapeManager(ctx);
        return instance;
    }

    public int getCurrentShape() { return currentShape; }

    public void setShape(int shapeId) {
        currentShape = shapeId;
        prefs.edit().putInt(KEY_SHAPE, shapeId).apply();
        // PERF: shape is part of ChatThemeManager's cached-bubble key —
        // invalidate that cache so the new shape renders immediately
        // instead of showing stale cached bubble drawables.
        ChatThemeManager.get(appContext).clearBubbleCache();
    }

    /**
     * Returns the 8-value corner radii array (in px).
     * Order: [TL,TL, TR,TR, BR,BR, BL,BL]
     *
     * @param sent    true = sent (right side), false = received (left side)
     * @param density screen density
     */
    public float[] getCornerRadii(boolean sent, float density) {
        switch (currentShape) {

            // 0 — Classic Rounded: all corners large, symmetric
            case SHAPE_ROUND:
                return all(R_L, density);

            // 1 — Tail (WhatsApp): one sharp corner at message origin
            case SHAPE_TAIL:
                if (sent) return radii(R_L, R_L, R_S, R_L, density);   // BR sharp
                else       return radii(R_S, R_L, R_L, R_L, density);  // TL sharp

            // 2 — Pill: maximum rounding
            case SHAPE_PILL:
                return all(R_PILL, density);

            // 3 — Square: near-zero rounding
            case SHAPE_SQUARE:
                return all(R_XS, density);

            // 4 — Squircle: diagonal alternating M/L
            case SHAPE_SQUIRCLE:
                if (sent) return radii(R_M, R_L, R_M, R_L, density);
                else       return radii(R_L, R_M, R_L, R_M, density);

            // 5 — Sharp Tail: zero-radius tail corner (sharper than TAIL)
            case SHAPE_SHARP_TAIL:
                if (sent) return radii(R_L, R_L, R_ZERO, R_L, density);
                else       return radii(R_ZERO, R_L, R_L, R_L, density);

            // 6 — Double Tail: both corners on the tail side are sharp
            case SHAPE_DOUBLE_TAIL:
                if (sent) return radii(R_L, R_ZERO, R_ZERO, R_L, density);  // TR+BR sharp
                else       return radii(R_ZERO, R_L, R_L, R_ZERO, density); // TL+BL sharp

            // 7 — Leaf: opposite diagonal sharp, other diagonal round
            case SHAPE_LEAF:
                if (sent) return radii(R_L, R_S, R_L, R_S, density);
                else       return radii(R_S, R_L, R_S, R_L, density);

            // 8 — Cloud: very large top, smaller bottom
            case SHAPE_CLOUD:
                return radii(R_XL, R_XL, R_S, R_S, density);  // same for both sides

            // 9 — Diamond Cut: all corners same medium — clean, uniform
            case SHAPE_DIAMOND:
                return all(R_M, density);

            // 10 — Teardrop: large top rounding, near-zero bottom
            case SHAPE_TEARDROP:
                if (sent) return radii(R_XL, R_XL, R_XS, R_L, density);
                else       return radii(R_XL, R_XL, R_L, R_XS, density);

            // 11 — Wave: alternating small/large on every corner
            case SHAPE_WAVE:
                if (sent) return radii(R_L, R_S, R_L, R_S, density);
                else       return radii(R_S, R_L, R_S, R_L, density);

            // 12 — Notch: sharp top corners, fully rounded bottom
            case SHAPE_NOTCH:
                return radii(R_XS, R_XS, R_XL, R_XL, density);

            // 13 — Pebble: organic asymmetric — each corner slightly different
            case SHAPE_PEBBLE:
                if (sent) {
                    float d = density;
                    return new float[]{
                        R_L  * d, R_L  * d,   // TL
                        R_M  * d, R_M  * d,   // TR
                        R_S  * d, R_S  * d,   // BR  ← slightly tucked
                        R_XL * d, R_XL * d    // BL  ← flowy
                    };
                } else {
                    float d = density;
                    return new float[]{
                        R_S  * d, R_S  * d,   // TL  ← tucked
                        R_L  * d, R_L  * d,   // TR
                        R_XL * d, R_XL * d,   // BR  ← flowy
                        R_M  * d, R_M  * d    // BL
                    };
                }

            // 14 — Sharp Edge: zero radius everywhere
            case SHAPE_SHARP_ALL:
                return all(R_ZERO, density);

            // 15 — Ribbon: flat top, round bottom (sent) / round top, flat bottom (recv)
            case SHAPE_RIBBON:
                if (sent) return radii(R_XS, R_XS, R_XL, R_XL, density);
                else       return radii(R_XL, R_XL, R_XS, R_XS, density);

            // 16 — Shield: large top arc, flat bottom — like a badge or shield
            case SHAPE_SHIELD:
                return radii(R_XL, R_XL, R_XS, R_XS, density);

            // 17 — Ticket: pill on leading side, square on trailing side
            case SHAPE_TICKET:
                if (sent) return radii(R_PILL, R_XS, R_XS, R_PILL, density); // left=pill, right=square
                else       return radii(R_XS, R_PILL, R_PILL, R_XS, density); // right=pill, left=square

            // 18 — Gem Cut: large TL+BR, tiny TR+BL — diagonal gem facet
            case SHAPE_GEM:
                if (sent) return radii(R_XL, R_XS, R_XL, R_XS, density);
                else       return radii(R_XS, R_XL, R_XS, R_XL, density);

            // 19 — Soft Tail: medium-sharp tail — gentler than SHAPE_TAIL
            case SHAPE_SOFT_TAIL:
                if (sent) return radii(R_L, R_L, R_M, R_L, density);  // BR medium (soft tail)
                else       return radii(R_M, R_L, R_L, R_L, density); // TL medium (soft tail)

            // 20 — Bullet: flat/square leading side, pill on trailing side
            case SHAPE_BULLET:
                if (sent) return radii(R_PILL, R_XS, R_XS, R_PILL, density); // left=pill, right=flat
                else       return radii(R_XS, R_PILL, R_PILL, R_XS, density);// left=flat, right=pill

            // 21 — Raindrop: one XL bulge corner, remaining three small
            case SHAPE_RAINDROP:
                if (sent) {
                    float d = density;
                    return new float[]{
                        R_XL * d, R_XL * d,  // TL ← big bulge
                        R_S  * d, R_S  * d,  // TR
                        R_S  * d, R_S  * d,  // BR
                        R_S  * d, R_S  * d   // BL
                    };
                } else {
                    float d = density;
                    return new float[]{
                        R_S  * d, R_S  * d,  // TL
                        R_XL * d, R_XL * d,  // TR ← big bulge
                        R_S  * d, R_S  * d,  // BR
                        R_S  * d, R_S  * d   // BL
                    };
                }

            // 22 — Toast: round top + left + right, flat zero bottom
            case SHAPE_TOAST:
                return radii(R_XL, R_XL, R_ZERO, R_ZERO, density);

            // 23 — Arch: very large arched top, sharp flat bottom
            case SHAPE_ARCH:
                return radii(R_PILL, R_PILL, R_ZERO, R_ZERO, density);

            // 24 — Bowtie: outer corners large, inner (tail-adjacent) corners tiny
            case SHAPE_BOWTIE:
                if (sent) return radii(R_XL, R_XS, R_XS, R_XL, density); // TL+BL large, TR+BR tiny
                else       return radii(R_XS, R_XL, R_XL, R_XS, density);// TR+BR large, TL+BL tiny

            default:
                return all(R_L, density);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** All 4 corners same radius */
    private float[] all(float r, float density) {
        float px = r * density;
        return new float[]{px, px, px, px, px, px, px, px};
    }

    /**
     * 4-corner shorthand: TL, TR, BR, BL (each corner = 2 values internally)
     */
    private float[] radii(float tl, float tr, float br, float bl, float d) {
        return new float[]{
            tl * d, tl * d,
            tr * d, tr * d,
            br * d, br * d,
            bl * d, bl * d
        };
    }
}
