package com.callx.app.utils;

import android.content.Context;

/**
 * BubbleShapeManager — Single minimal Rounded shape. No switching.
 */
public class BubbleShapeManager {

    public static final int SHAPE_ROUND         = 0;
    public static final int SHAPE_TAIL          = 1;
    public static final int SHAPE_PILL          = 2;
    public static final int SHAPE_SQUARE        = 3;
    public static final int SHAPE_SQUIRCLE      = 4;
    public static final int SHAPE_SHARP_TAIL    = 5;
    public static final int SHAPE_DOUBLE_TAIL   = 6;
    public static final int SHAPE_LEAF          = 7;
    public static final int SHAPE_CLOUD         = 8;
    public static final int SHAPE_DIAMOND       = 9;
    public static final int SHAPE_TEARDROP      = 10;
    public static final int SHAPE_WAVE          = 11;
    public static final int SHAPE_NOTCH         = 12;
    public static final int SHAPE_PEBBLE        = 13;
    public static final int SHAPE_SHARP_ALL     = 14;
    public static final int SHAPE_RIBBON        = 15;
    public static final int SHAPE_SHIELD        = 16;
    public static final int SHAPE_TICKET        = 17;
    public static final int SHAPE_GEM           = 18;
    public static final int SHAPE_SOFT_TAIL     = 19;
    public static final int SHAPE_BULLET        = 20;
    public static final int SHAPE_RAINDROP      = 21;
    public static final int SHAPE_TOAST         = 22;
    public static final int SHAPE_ARCH          = 23;
    public static final int SHAPE_BOWTIE        = 24;

    public static final String[] SHAPE_NAMES = {
        "\u25A0 Classic Rounded"
    };

    public static final String[] SHAPE_DESC = {
        "Smooth rounded corners"
    };

    private static final float R_L = 20f;

    private static BubbleShapeManager instance;
    private final Context appContext;

    private BubbleShapeManager(Context ctx) {
        appContext = ctx.getApplicationContext();
    }

    public static BubbleShapeManager get(Context ctx) {
        if (instance == null) instance = new BubbleShapeManager(ctx);
        return instance;
    }

    public int getCurrentShape() { return SHAPE_ROUND; }

    public void setShape(int shapeId) {
        ChatThemeManager.get(appContext).clearBubbleCache();
    }

    public float[] getCornerRadii(boolean sent, float density) {
        float px = R_L * density;
        return new float[]{px, px, px, px, px, px, px, px};
    }
}
