package com.callx.app.community.canvas;

import android.graphics.Color;

/** Tiny color helpers so CommunityPostCanvasView's Paint setup doesn't need a color-math dependency. */
final class ColorUtil {

    private ColorUtil() {}

    /** Returns a lighter tint of `color`, used for the avatar-placeholder background. */
    static int lighten(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.max(0f, hsv[1] * 0.55f);
        hsv[2] = Math.min(1f, hsv[2] + (1f - hsv[2]) * 0.35f + 0.15f);
        return Color.HSVToColor(hsv);
    }

    /** Returns `color` with alpha replaced by `alpha` (0-255). */
    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
