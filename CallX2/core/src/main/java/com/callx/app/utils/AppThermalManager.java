package com.callx.app.utils;

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
 * AppThermalManager — app-wide real-time thermal + battery monitor.
 *
 * Originally lived as ReelThermalManager inside feature-reels (Reels-only).
 * Moved here so feature-chat can react to the SAME thermal state without
 * feature-chat taking a dependency on feature-reels — both modules already
 * depend on :core. ReelThermalManager now delegates to this singleton
 * internally (see feature-reels/player/ReelThermalManager) so there is
 * still only ONE PowerManager listener / battery receiver / poll loop for
 * the whole app, not one per feature module.
 *
 * THERMAL LEVELS (matching PowerManager constants on API 29+):
 *  SAFE     → full prewarm + full preload, all animations/prefetch on
 *  LIGHT    → reduced background work
 *  MODERATE → background/prefetch work reduced further
 *  HOT      → all non-essential preload/prefetch/decorative work OFF
 *
 * Singleton, main-thread-safe. Registers a real-time listener on API 29+
 * so it reacts within one event cycle; falls back to polling on older
 * devices / as a supplement.
 */
public final class AppThermalManager {

    private static final String TAG = "AppThermalManager";

    public enum Level { SAFE, LIGHT, MODERATE, HOT }

    private static volatile AppThermalManager instance;

    public static AppThermalManager get(Context ctx) {
        if (instance == null) {
            synchronized (AppThermalManager.class) {
                if (instance == null) instance = new AppThermalManager(ctx.getApplicationContext());
            }
        }
        return instance;
    }

    private volatile Level currentLevel = Level.SAFE;
    private final Context appCtx;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private PowerManager.OnThermalStatusChangedListener thermalListener;

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            Level prev = currentLevel;
            currentLevel = evaluate();
            if (currentLevel != prev) notifyListeners();
            mainHandler.postDelayed(this, 8_000L);
        }
    };

    private AppThermalManager(Context appCtx) {
        this.appCtx = appCtx;
        currentLevel = evaluate();
        registerRealTimeListener();
        mainHandler.postDelayed(pollRunnable, 8_000L);
    }

    public Level getLevel() { return currentLevel; }

    public boolean canPrewarmHeavyWork() {
        return currentLevel == Level.SAFE;
    }

    public boolean canBackgroundPreload() {
        return currentLevel == Level.SAFE || currentLevel == Level.LIGHT;
    }

    public boolean canReducedPreload() {
        return currentLevel != Level.HOT;
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Rarely needed — this is a shared, app-wide singleton now. */
    public void release() {
        mainHandler.removeCallbacks(pollRunnable);
        unregisterRealTimeListener();
    }

    private Level evaluate() {
        try {
            PowerManager pm = (PowerManager) appCtx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return Level.SAFE;

            if (pm.isPowerSaveMode()) {
                Log.d(TAG, "evaluate: power-save → HOT");
                return Level.HOT;
            }

            Intent battery = appCtx.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level  = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale  = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                                || status == BatteryManager.BATTERY_STATUS_FULL;
                if (!charging && level >= 0 && scale > 0) {
                    int pct = (int) (level * 100f / scale);
                    if (pct <= 15) {
                        Log.d(TAG, "evaluate: battery=" + pct + "% → HOT");
                        return Level.HOT;
                    }
                    if (pct <= 30) {
                        Log.d(TAG, "evaluate: battery=" + pct + "% → MODERATE");
                        return Level.MODERATE;
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int thermal = pm.getCurrentThermalStatus();
                if (thermal >= 3) {
                    Log.d(TAG, "evaluate: thermal=" + thermal + " → HOT");
                    return Level.HOT;
                }
                if (thermal == 2) {
                    Log.d(TAG, "evaluate: thermal=" + thermal + " → MODERATE");
                    return Level.MODERATE;
                }
                if (thermal == 1) {
                    return Level.LIGHT;
                }
            }

            return Level.SAFE;

        } catch (Exception e) {
            Log.w(TAG, "evaluate failed: " + e.getMessage());
            return Level.SAFE;
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
