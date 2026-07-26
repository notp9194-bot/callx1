package com.callx.app.player;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ReelThermalManager — Instagram-level real-time thermal + battery monitor
 * for the Reels feed.
 *
 * WHY THIS EXISTS (the N+1 thermal problem):
 * ─────────────────────────────────────────
 * Instagram only has ONE active hardware video decoder running at any time
 * (the currently visible reel). Pre-loading for N+1 is done purely as byte
 * caching — downloading bytes into the ExoPlayer cache so the NEXT reel can
 * start an ExoPlayer from cache-hit instantly, with ZERO second decoder
 * allocated until the user actually swipes.
 *
 * The previous approach built a FULL ExoPlayer (with an actual MediaCodec
 * hardware decoder allocated) for N+1, PLUS ran byte downloads via
 * ReelVideoPreloader, PLUS ran the PredictivePreloader. Three concurrent
 * workloads for a reel the user hasn't even requested yet — exactly why
 * the phone heated up during fast scrolling.
 *
 * THIS MANAGER provides a single source of truth for thermal state so that:
 *  - ExoPlayer prewarm (actual decoder allocation) → only on SAFE thermal
 *  - Byte preloading (network downloads) → on SAFE + LIGHT
 *  - Predictive preloading → on SAFE only
 *  - Everything stops → on MODERATE+ or battery < 15%
 *
 * THERMAL LEVELS (matching PowerManager constants on API 29+):
 *  NONE     → full prewarm + full preload
 *  LIGHT    → byte preload only (no ExoPlayer decoder)
 *  MODERATE → byte preload only, reduced bytes
 *  SEVERE+  → NO preloading at all
 *
 * Singleton, main-thread-safe. Registers a real-time listener on API 29+
 * so we react within one event cycle; falls back to polling on older devices.
 */
public final class ReelThermalManager {

    private static final String TAG = "ReelThermalManager";

    // ── Thermal level enum ─────────────────────────────────────────────────
    public enum Level { SAFE, LIGHT, MODERATE, HOT }

    // ── Singleton ─────────────────────────────────────────────────────────
    private static volatile ReelThermalManager instance;

    public static ReelThermalManager get(Context ctx) {
        if (instance == null) {
            synchronized (ReelThermalManager.class) {
                if (instance == null) instance = new ReelThermalManager(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    // ── State ──────────────────────────────────────────────────────────────
    private volatile Level currentLevel = Level.SAFE;
    private final Context appCtx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    // API 29+ real-time listener
    private PowerManager.OnThermalStatusChangedListener thermalListener;

    // Polling runnable (API < 29 fallback or supplement)
    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            Level prev = currentLevel;
            currentLevel = evaluate();
            if (currentLevel != prev) notifyListeners();
            mainHandler.postDelayed(this, 8_000L); // check every 8s
        }
    };

    private ReelThermalManager(Context appCtx) {
        this.appCtx = appCtx;
        currentLevel = evaluate();
        registerRealTimeListener();
        mainHandler.postDelayed(pollRunnable, 8_000L);
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Current thermal level (updated continuously). */
    public Level getLevel() { return currentLevel; }

    /**
     * True if it's safe to build a FULL ExoPlayer (hardware decoder) for N+1.
     * Instagram-style: only when device is completely cool.
     */
    public boolean canPrewarmExoPlayer() {
        return currentLevel == Level.SAFE;
    }

    /**
     * True if byte preloading (network downloads into cache) is OK.
     * Cheaper than ExoPlayer prewarm — allowed up to LIGHT thermal.
     */
    public boolean canBytePreload() {
        return currentLevel == Level.SAFE || currentLevel == Level.LIGHT;
    }

    /**
     * True if REDUCED byte preloading is OK (use smaller preload_bytes).
     */
    public boolean canReducedBytePreload() {
        return currentLevel != Level.HOT;
    }

    /** Add a listener to be called on the main thread when thermal level changes. */
    public void addChangeListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Stop all monitoring. Call from ReelsFragment.onDestroyView() when
     * Reels is being torn down (not on every tab switch).
     */
    public void release() {
        mainHandler.removeCallbacks(pollRunnable);
        unregisterRealTimeListener();
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private Level evaluate() {
        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return Level.SAFE;

            // 1. Power-save mode → always treat as HOT
            if (pm.isPowerSaveMode()) {
                Log.d(TAG, "evaluate: power-save → HOT");
                return Level.HOT;
            }

            // 2. Battery level check (not charging AND < 15%)
            Intent battery = appCtx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level  = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale  = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                                || status == BatteryManager.BATTERY_STATUS_FULL;
                if (!charging && level >= 0 && scale > 0) {
                    int pct = (int)(level * 100f / scale);
                    if (pct <= 15) {
                        Log.d(TAG, "evaluate: battery=" + pct + "% → HOT");
                        return Level.HOT;
                    }
                    if (pct <= 30 && !charging) {
                        // Low but not critical → MODERATE
                        Log.d(TAG, "evaluate: battery=" + pct + "% → MODERATE");
                        return Level.MODERATE;
                    }
                }
            }

            // 3. Thermal status (API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int thermal = pm.getCurrentThermalStatus();
                // THERMAL_STATUS_NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4
                if (thermal >= 3) { // SEVERE or worse
                    Log.d(TAG, "evaluate: thermal=" + thermal + " → HOT");
                    return Level.HOT;
                }
                if (thermal == 2) { // MODERATE
                    Log.d(TAG, "evaluate: thermal=" + thermal + " → MODERATE");
                    return Level.MODERATE;
                }
                if (thermal == 1) { // LIGHT
                    return Level.LIGHT;
                }
            }

            return Level.SAFE;

        } catch (Exception e) {
            Log.w(TAG, "evaluate failed: " + e.getMessage());
            return Level.SAFE; // fail open
        }
    }

    private void notifyListeners() {
        for (Runnable r : listeners) {
            try { mainHandler.post(r); } catch (Exception ignored) {}
        }
    }

    private void registerRealTimeListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    thermalListener = status -> {
                        Level prev = currentLevel;
                        currentLevel = evaluate();
                        Log.d(TAG, "thermalChanged: status=" + status + " → " + currentLevel);
                        if (currentLevel != prev) notifyListeners();
                    };
                    pm.addThermalStatusListener(mainHandler::post, thermalListener);
                    Log.d(TAG, "Real-time thermal listener registered (API 29+)");
                }
            } catch (Exception e) {
                Log.w(TAG, "registerRealTimeListener failed: " + e.getMessage());
            }
        }
    }

    private void unregisterRealTimeListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) pm.removeThermalStatusListener(thermalListener);
            } catch (Exception e) {
                Log.w(TAG, "unregisterRealTimeListener: " + e.getMessage());
            }
            thermalListener = null;
        }
    }
}
