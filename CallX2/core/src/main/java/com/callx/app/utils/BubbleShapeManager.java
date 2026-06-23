package com.callx.app.utils;

import android.content.Context;

/** Stub — bubble shape system removed. ChatThemeManager handles corners directly. */
public class BubbleShapeManager {
    public static final int SHAPE_TAIL = 1;

    private static BubbleShapeManager instance;
    private BubbleShapeManager(Context ctx) {}

    public static BubbleShapeManager get(Context ctx) {
        if (instance == null) instance = new BubbleShapeManager(ctx);
        return instance;
    }

    public int getCurrentShape() { return SHAPE_TAIL; }
    public void setShape(int id) {}
    public float[] getCornerRadii(boolean sent, float density) {
        float r = 18f * density;
        float t = 4f * density;
        return sent
            ? new float[]{r, r, t, t, r, r, r, r}
            : new float[]{t, t, r, r, r, r, r, r};
    }
}
