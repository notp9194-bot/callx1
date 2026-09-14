package com.callx.app.cache;

import android.content.Context;
import android.util.Log;

import androidx.camera.lifecycle.ProcessCameraProvider;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * Caches the app-wide {@link ProcessCameraProvider} future so any camera
 * screen can skip the cold-start cost of resolving it.
 *
 * WHY: ProcessCameraProvider.getInstance(context) does real work the first
 * time it's called per process — binding to CameraX's internal service,
 * enumerating cameras, etc. Measured at roughly 100-300ms on a mid-range
 * device. Every camera screen used to call this itself right when the user
 * opened it (see ChatCameraActivity#startCamera()), so that cost landed
 * exactly when it hurts most — as a visible delay before the preview
 * appears.
 *
 * FIX: call {@link #warmUp(Context)} once, early, from CallxApp#onCreate().
 * It kicks off ProcessCameraProvider.getInstance() immediately and holds the
 * resulting future here. By the time the user actually opens a camera
 * screen, the future has almost certainly already resolved — get(context)
 * from that screen returns the cached future instead of starting a new
 * resolution, so its addListener() callback fires effectively instantly.
 *
 * Safe to call get() without ever calling warmUp() first (e.g. in a test or
 * a build variant that skips it) — it just falls back to resolving lazily
 * at that point, same as the old per-screen behavior.
 */
public final class CameraProviderCache {

    private static final String TAG = "CameraProviderCache";

    private static volatile ListenableFuture<ProcessCameraProvider> cachedFuture;

    private CameraProviderCache() {}

    /** Call once from CallxApp#onCreate() (main thread, fire-and-forget —
     *  don't block app startup waiting on this). */
    public static void warmUp(Context context) {
        if (cachedFuture != null) return; // already warming/warmed
        synchronized (CameraProviderCache.class) {
            if (cachedFuture != null) return;
            try {
                cachedFuture = ProcessCameraProvider.getInstance(context.getApplicationContext());
                Log.d(TAG, "ProcessCameraProvider warm-up kicked off");
            } catch (Exception e) {
                // Camera hardware/service unavailable on this device/emulator —
                // leave cachedFuture null so get() below falls back to a normal
                // per-call resolution attempt (and surfaces the real error there).
                Log.w(TAG, "warm-up failed, will resolve lazily on first real use", e);
            }
        }
    }

    /** Camera screens call this instead of ProcessCameraProvider.getInstance()
     *  directly. Returns the warmed-up future if warmUp() already ran for
     *  this process, otherwise starts (and caches) a fresh one on demand. */
    public static ListenableFuture<ProcessCameraProvider> get(Context context) {
        if (cachedFuture == null) warmUp(context);
        return cachedFuture;
    }
}
