package com.callx.app.player;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * NetworkQualityMonitor — Real-time network type + quality tracking
 *
 * Features:
 *  ✅ Listens to network changes via ConnectivityManager.NetworkCallback
 *  ✅ Classifies: WIFI / CELLULAR_5G / CELLULAR_4G / CELLULAR_3G / CELLULAR_2G / NONE
 *  ✅ Fires callbacks when quality changes (for ABR to adapt)
 *  ✅ Singleton — one instance per app process
 *  ✅ Safe to call from any thread (callbacks on main thread)
 *
 * Usage:
 *   NetworkQualityMonitor.get(context).addListener(listener);
 *   NetworkQualityMonitor.get(context).startMonitoring();
 */
public class NetworkQualityMonitor {

    private static final String TAG = "NetQuality";

    public enum Quality {
        NONE,
        CELLULAR_2G,   // < 100 Kbps
        CELLULAR_3G,   // 100 – 1000 Kbps
        CELLULAR_4G,   // 1 – 20 Mbps
        CELLULAR_5G,   // > 20 Mbps cellular
        WIFI,          // Wi-Fi (assume good)
        ETHERNET       // Ethernet (excellent)
    }

    public interface NetworkQualityListener {
        void onQualityChanged(Quality newQuality);
    }

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static volatile NetworkQualityMonitor instance;

    public static NetworkQualityMonitor get(Context ctx) {
        if (instance == null) {
            synchronized (NetworkQualityMonitor.class) {
                if (instance == null) instance = new NetworkQualityMonitor(ctx);
            }
        }
        return instance;
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private final Context              appCtx;
    private final ConnectivityManager  cm;
    private final Handler              mainHandler;
    private final List<NetworkQualityListener> listeners = new ArrayList<>();

    private Quality         currentQuality = Quality.NONE;
    private boolean         monitoring     = false;

    private final ConnectivityManager.NetworkCallback netCallback =
        new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                updateQuality(network);
            }
            @Override
            public void onLost(@NonNull Network network) {
                dispatchQuality(Quality.NONE);
            }
            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                                              @NonNull NetworkCapabilities caps) {
                dispatchQuality(classifyCapabilities(caps));
            }
        };

    private NetworkQualityMonitor(Context ctx) {
        appCtx      = ctx.getApplicationContext();
        cm          = (ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void startMonitoring() {
        if (monitoring || cm == null) return;
        monitoring = true;
        NetworkRequest req = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        cm.registerNetworkCallback(req, netCallback);

        // Snapshot current state
        Network active = cm.getActiveNetwork();
        if (active != null) updateQuality(active);
        Log.d(TAG, "Monitoring started");
    }

    public void stopMonitoring() {
        if (!monitoring || cm == null) return;
        monitoring = false;
        try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        Log.d(TAG, "Monitoring stopped");
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    public void addListener(NetworkQualityListener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(NetworkQualityListener l) {
        listeners.remove(l);
    }

    // ── Current quality ───────────────────────────────────────────────────────

    public Quality currentQuality() {
        return currentQuality;
    }

    public boolean isNetworkGoodForHD() {
        return currentQuality == Quality.WIFI
            || currentQuality == Quality.ETHERNET
            || currentQuality == Quality.CELLULAR_5G
            || currentQuality == Quality.CELLULAR_4G;
    }

    public boolean isOffline() {
        return currentQuality == Quality.NONE;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void updateQuality(Network network) {
        if (cm == null) return;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) { dispatchQuality(Quality.NONE); return; }
        dispatchQuality(classifyCapabilities(caps));
    }

    private Quality classifyCapabilities(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return Quality.ETHERNET;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))     return Quality.WIFI;

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            int bwKbps = caps.getLinkDownstreamBandwidthKbps();
            if (bwKbps >= 20_000) return Quality.CELLULAR_5G;
            if (bwKbps >= 1_000)  return Quality.CELLULAR_4G;
            if (bwKbps >= 100)    return Quality.CELLULAR_3G;
            return Quality.CELLULAR_2G;
        }
        return Quality.NONE;
    }

    private void dispatchQuality(Quality q) {
        if (q == currentQuality) return;
        currentQuality = q;
        Log.d(TAG, "Quality → " + q);
        mainHandler.post(() -> {
            for (NetworkQualityListener l : new ArrayList<>(listeners)) {
                l.onQualityChanged(q);
            }
        });
    }
}
