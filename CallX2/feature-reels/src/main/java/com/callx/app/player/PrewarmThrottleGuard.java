package com.callx.app.player;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * PrewarmThrottleGuard — battery/thermal aware gate for reel pre-warming.
 *
 * Prewarming (building extra muted ExoPlayer instances, decoding first-frame
 * bitmaps, running predictive player-level warms) is pure UX polish — it's
 * never required for correctness, only for shaving the swipe-to-play gap.
 * That makes it the right thing to skip whenever the device is under real
 * thermal or battery pressure, since none of that work is user-visible if
 * skipped (the reel still plays fine, just via the normal cold-start path
 * once it becomes actually visible).
 *
 * Checked, in order:
 *  1. PowerManager.isPowerSaveMode() — user explicitly opted into battery
 *     saver; respect it everywhere, not just for prewarm, but this is the
 *     one place that's ours to gate.
 *  2. Battery level low (<15%) AND not charging — matches the threshold
 *     ReelABREngine.autoThrottleForBattery() already uses for Data Saver,
 *     kept consistent here.
 *  3. Device thermal status at or above THROTTLING (API 29+) — the OS
 *     itself is about to start slowing things down; building extra codec
 *     instances right now only makes that worse.
 */
public final class PrewarmThrottleGuard {

    private static final String TAG = "PrewarmThrottleGuard";
    private static final int    LOW_BATTERY_PCT = 15;

    private PrewarmThrottleGuard() {}

    /** True when ANY form of speculative prewarm should be skipped right now. */
    public static boolean shouldThrottle(Context ctx) {
        if (ctx == null) return true;
        Context appCtx = ctx.getApplicationContext();

        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) {
                Log.d(TAG, "throttle: power save mode ON");
                return true;
            }

            if (pm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int thermal = pm.getCurrentThermalStatus();
                // THERMAL_STATUS_MODERATE(1) is still fine; THROTTLING(2)+ means
                // the OS itself is already shedding load — stop adding more.
                if (thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                    Log.d(TAG, "throttle: thermal status=" + thermal);
                    return true;
                }
            }

            Intent battery = appCtx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                                 || status == BatteryManager.BATTERY_STATUS_FULL;
                if (level >= 0 && scale > 0 && !charging) {
                    int pct = (int) (level * 100f / scale);
                    if (pct <= LOW_BATTERY_PCT) {
                        Log.d(TAG, "throttle: battery=" + pct + "% not charging");
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // Fail open — a stat lookup failing shouldn't block the whole feed.
            Log.w(TAG, "shouldThrottle check failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * A softer variant used for the *extra* N+2/N+3 speculative prewarm —
     * moderate thermal status alone (without severe) trims back to only
     * the always-safe N+1 warm. Kept separate from {@link #shouldThrottle}
     * so N+1 (the one that actually removes the visible thumbnail flash)
     * keeps working right up until things get severe/low-battery, while
     * the "nice to have" extra distance backs off earlier.
     */
    public static boolean shouldThrottleExtraDistance(Context ctx) {
        if (shouldThrottle(ctx)) return true;
        if (ctx == null) return true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PowerManager pm = (PowerManager) ctx.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
