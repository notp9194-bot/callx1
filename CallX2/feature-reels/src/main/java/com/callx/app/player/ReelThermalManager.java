package com.callx.app.player;

import android.content.Context;

import com.callx.app.utils.AppThermalManager;

/**
 * ReelThermalManager — Reels-facing wrapper around the shared
 * {@link AppThermalManager} singleton (core module).
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
 * THIS WRAPPER used to run its own PowerManager listener + battery receiver
 * + poll loop. It now delegates all of that to {@link AppThermalManager}
 * (core), which feature-chat also reads — so the whole app shares ONE
 * thermal monitor instead of a separate one per feature module. The public
 * API here (Level enum, method names) is unchanged so none of the 20+
 * existing Reels call sites needed to change.
 */
public final class ReelThermalManager {

    public enum Level { SAFE, LIGHT, MODERATE, HOT }

    private static volatile ReelThermalManager instance;
    private final AppThermalManager shared;

    public static ReelThermalManager get(Context ctx) {
        if (instance == null) {
            synchronized (ReelThermalManager.class) {
                if (instance == null) instance = new ReelThermalManager(ctx);
            }
        }
        return instance;
    }

    private ReelThermalManager(Context ctx) {
        this.shared = AppThermalManager.get(ctx);
    }

    public Level getLevel() {
        return map(shared.getLevel());
    }

    /**
     * True if it's safe to build a FULL ExoPlayer (hardware decoder) for N+1.
     * Instagram-style: only when device is completely cool.
     */
    public boolean canPrewarmExoPlayer() {
        return shared.canPrewarmHeavyWork();
    }

    /**
     * True if byte preloading (network downloads into cache) is OK.
     * Cheaper than ExoPlayer prewarm — allowed up to LIGHT thermal.
     */
    public boolean canBytePreload() {
        return shared.canBackgroundPreload();
    }

    /** True if REDUCED byte preloading is OK (use smaller preload_bytes). */
    public boolean canReducedBytePreload() {
        return shared.canReducedPreload();
    }

    /** Add a listener to be called on the main thread when thermal level changes. */
    public void addChangeListener(Runnable listener) {
        shared.addChangeListener(listener);
    }

    public void removeChangeListener(Runnable listener) {
        shared.removeChangeListener(listener);
    }

    /**
     * No-op now — the underlying monitor is a shared app-wide singleton
     * (other features, e.g. Chat, may still be using it). Kept only so
     * existing ReelsFragment.onDestroyView() call sites don't need changes.
     */
    public void release() {
        // Intentionally does not stop AppThermalManager — it's shared.
    }

    private static Level map(AppThermalManager.Level l) {
        switch (l) {
            case LIGHT:    return Level.LIGHT;
            case MODERATE: return Level.MODERATE;
            case HOT:      return Level.HOT;
            default:       return Level.SAFE;
        }
    }
}
