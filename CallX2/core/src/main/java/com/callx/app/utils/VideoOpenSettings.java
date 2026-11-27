package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * A/B flag for the video-open GPU-layer experiment (default OFF = old behaviour).
 * When ON, MediaViewerActivity does not wrap a VIDEO view in LAYER_TYPE_HARDWARE for the
 * open/close dock animation (TextureView + layer = an extra copy per frame).
 * Toggle: viewer ⋮ menu → "Video open GPU layer". Compare the VideoOpenPerf log / jank.
 */
public final class VideoOpenSettings {
    private static final String PREF = "callx_perf";
    private static final String KEY_NO_LAYER = "video_open_no_hw_layer";
    private static volatile Boolean cached;

    private VideoOpenSettings() {}

    public static boolean noHwLayer(Context ctx) {
        Boolean c = cached;
        if (c != null) return c;
        boolean v = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(KEY_NO_LAYER, false);
        cached = v;
        return v;
    }

    public static void setNoHwLayer(Context ctx, boolean v) {
        cached = v;
        SharedPreferences.Editor e = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE).edit();
        e.putBoolean(KEY_NO_LAYER, v).apply();
    }
}
