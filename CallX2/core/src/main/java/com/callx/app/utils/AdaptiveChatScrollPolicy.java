package com.callx.app.utils;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Keeps chat scrolling work inside a device-aware budget.
 *
 * A chat screen previously paid for the largest layout/cache/prefetch window
 * even while the user was idle, on a low-memory phone, or on metered data.
 * This policy samples those two device signals occasionally (not once per
 * scroll frame) and exposes small, cheap knobs for the chat UI and media
 * preloader.
 */
public final class AdaptiveChatScrollPolicy {
    private static final long DEVICE_PROBE_INTERVAL_MS = 750L;
    private static final float FAST_FLING_REFERENCE_PX_PER_SECOND = 6000f;

    private final Context appContext;
    private long lastDeviceProbeMs = Long.MIN_VALUE;
    private boolean lowMemoryDevice;
    private boolean meteredOrOffline;

    public AdaptiveChatScrollPolicy(@NonNull Context context) {
        appContext = context.getApplicationContext();
        refreshDeviceSignals(true);
    }

    /**
     * Extra layout space as a multiple of the display height.
     * Slow/idle states intentionally use a small window; only a real fling
     * earns the larger runway needed to hide layout latency.
     */
    public float layoutMultiplier(int scrollState, int flingVelocityPxPerSecond) {
        refreshDeviceSignals(false);
        float velocityRatio = Math.min(1f,
                Math.abs(flingVelocityPxPerSecond) / FAST_FLING_REFERENCE_PX_PER_SECOND);

        final float multiplier;
        if (scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
            multiplier = (lowMemoryDevice ? 0.85f : 1.15f)
                    + velocityRatio * (lowMemoryDevice ? 0.50f : 1.05f);
        } else if (scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
            multiplier = lowMemoryDevice ? 0.55f : 0.75f;
        } else {
            multiplier = lowMemoryDevice ? 0.40f : 0.55f;
        }

        // A metered/offline connection should not make the UI retain a large
        // decode/layout runway for media that may never be fetched.
        return clamp(multiplier * (meteredOrOffline ? 0.82f : 1f), 0.35f, 2.20f);
    }

    /** RecyclerView's ahead-of-viewport item prefetch count. */
    public int initialPrefetchCount(int scrollState, int flingVelocityPxPerSecond) {
        refreshDeviceSignals(false);
        if (lowMemoryDevice) {
            return scrollState == RecyclerView.SCROLL_STATE_SETTLING ? 2 : 3;
        }
        if (scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
            return Math.abs(flingVelocityPxPerSecond) >= 4500 ? 6 : 4;
        }
        if (scrollState == RecyclerView.SCROLL_STATE_DRAGGING) return 3;
        return meteredOrOffline ? 3 : 4;
    }

    /** Detached holder cache size. Large enough for a settled list, bounded during motion. */
    public int itemViewCacheSize(int scrollState) {
        refreshDeviceSignals(false);
        if (lowMemoryDevice) {
            return scrollState == RecyclerView.SCROLL_STATE_SETTLING ? 6 : 5;
        }
        int size = scrollState == RecyclerView.SCROLL_STATE_SETTLING ? 16
                : (scrollState == RecyclerView.SCROLL_STATE_DRAGGING ? 8 : 12);
        return meteredOrOffline ? Math.max(5, size - 2) : size;
    }

    /** Actual media requests to issue for the current scroll state. */
    public int mediaPreloadCount(int scrollState, int velocityPxPerSecond, int hardCap) {
        refreshDeviceSignals(false);
        int count;
        if (lowMemoryDevice || meteredOrOffline) {
            count = scrollState == RecyclerView.SCROLL_STATE_SETTLING
                    ? (Math.abs(velocityPxPerSecond) >= 4500 ? 1 : 2) : 3;
        } else if (scrollState == RecyclerView.SCROLL_STATE_SETTLING) {
            count = Math.abs(velocityPxPerSecond) >= 4500 ? 3 : 4;
        } else if (scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
            count = 3;
        } else {
            count = 6;
        }
        return Math.max(0, Math.min(hardCap, count));
    }

    public boolean isLowMemoryDevice() {
        refreshDeviceSignals(false);
        return lowMemoryDevice;
    }

    private void refreshDeviceSignals(boolean force) {
        long now = SystemClock.uptimeMillis();
        if (!force && now - lastDeviceProbeMs < DEVICE_PROBE_INTERVAL_MS) return;
        lastDeviceProbeMs = now;

        ActivityManager activityManager =
                (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        if (activityManager != null) {
            activityManager.getMemoryInfo(memoryInfo);
            float availableRatio = memoryInfo.totalMem > 0
                    ? (float) memoryInfo.availMem / (float) memoryInfo.totalMem : 1f;
            lowMemoryDevice = activityManager.isLowRamDevice()
                    || memoryInfo.lowMemory
                    || availableRatio < 0.12f;
        } else {
            lowMemoryDevice = false;
        }

        ConnectivityManager connectivityManager =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) {
            meteredOrOffline = true;
        } else {
            meteredOrOffline = connectivityManager.isActiveNetworkMetered()
                    || connectivityManager.getActiveNetworkInfo() == null
                    || !connectivityManager.getActiveNetworkInfo().isConnected();
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}