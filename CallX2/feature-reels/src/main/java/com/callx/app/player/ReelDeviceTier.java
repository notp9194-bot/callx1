package com.callx.app.player;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Cheap, cached "is this a low-end phone" check for the reels pipeline.
 * Low-end = Android's own isLowRamDevice() flag, or a per-app heap class of
 * 128MB or less (typical for 2–3GB phones that don't set the flag).
 */
public final class ReelDeviceTier {

    private ReelDeviceTier() {}

    private static volatile Boolean lowEnd;

    public static boolean isLowEnd(Context ctx) {
        Boolean v = lowEnd;
        if (v == null) {
            boolean r = false;
            try {
                ActivityManager am = (ActivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.ACTIVITY_SERVICE);
                r = am != null && (am.isLowRamDevice() || am.getMemoryClass() <= 128);
            } catch (Exception ignored) {}
            lowEnd = v = r;
        }
        return v;
    }
}
