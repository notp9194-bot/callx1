package com.callx.app.utils;

import android.app.ActivityManager;
import android.content.Context;

/**
 * DeviceTier — shared, process-wide low-RAM device classification.
 *
 * Same signal CallxGlideModule already uses privately to halve its own
 * memory-cache/bitmap-pool budgets on low-end hardware
 * (ActivityManager#isLowRamDevice() OR app heap class < 128 MB — the
 * standard Android "this device is RAM-constrained" check, also used by
 * ChatsFragment's avatar-prefetch gate and UnifiedVideoCacheManager).
 * Pulled out here so feature-chat's conversation screen — which had no
 * device-tier awareness at all — can scale thumbnail decode size and
 * decorative-animation cost the same way, without duplicating the
 * ActivityManager lookup or its threshold in a third place.
 *
 * Computed once per process and cached: ActivityManager's values never
 * change at runtime, so there's no reason to hit the system service on
 * every bind() during a fast scroll.
 */
public final class DeviceTier {

    private static final int LOW_RAM_MEMORY_CLASS_MB = 128;

    private static volatile Boolean sIsLowRam = null;

    private DeviceTier() {}

    public static boolean isLowRamDevice(Context ctx) {
        Boolean cached = sIsLowRam;
        if (cached != null) return cached;
        boolean result = computeIsLowRam(ctx);
        sIsLowRam = result; // worst case two threads compute the same value once
        return result;
    }

    private static boolean computeIsLowRam(Context ctx) {
        if (ctx == null) return false;
        ActivityManager am = (ActivityManager) ctx.getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        if (am.isLowRamDevice()) return true;
        return am.getMemoryClass() < LOW_RAM_MEMORY_CLASS_MB;
    }
}
