package com.callx.app.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.webrtc.PeerConnection;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;

import java.util.Map;

/**
 * CallNetworkMonitor — Real-time call quality monitoring.
 *
 * Tracks:
 *  - Android NetworkCapabilities → connectivity events
 *  - RTCStatsReport polling (every 3s) → packet loss, jitter, RTT
 *
 * Quality levels:
 *   EXCELLENT  loss <1%  rtt <100ms  jitter <20ms
 *   GOOD       loss <5%  rtt <200ms  jitter <50ms
 *   FAIR       loss <15% rtt <400ms
 *   POOR       anything worse
 */
public class CallNetworkMonitor {

    private static final String TAG              = "CallNetworkMonitor";
    private static final long   POLL_INTERVAL_MS = 3_000L;

    public enum Quality { UNKNOWN, EXCELLENT, GOOD, FAIR, POOR }

    public interface Callback {
        void onQualityChanged(Quality quality, String label);
        void onNetworkLost();
        void onNetworkRestored();
    }

    private final Context       context;
    private final PeerConnection pc;
    private final Callback      cb;
    private final Handler       handler = new Handler(Looper.getMainLooper());

    private ConnectivityManager.NetworkCallback netCallback;
    private Runnable                            pollRunnable;
    private Quality                             lastQuality = Quality.UNKNOWN;
    private boolean                             running     = false;

    public CallNetworkMonitor(Context context, PeerConnection pc, Callback cb) {
        this.context = context.getApplicationContext();
        this.pc      = pc;
        this.cb      = cb;
    }

    public void start() {
        if (running) return;
        running = true;
        registerNetworkCallback();
        schedulePoll();
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        unregisterNetworkCallback();
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onLost(Network network) {
                    handler.post(() -> { if (cb != null) cb.onNetworkLost(); });
                }
                @Override public void onAvailable(Network network) {
                    handler.post(() -> { if (cb != null) cb.onNetworkRestored(); });
                }
            };
            cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                netCallback);
        } catch (Exception e) {
            Log.w(TAG, "registerNetworkCallback: " + e.getMessage());
        }
    }

    private void unregisterNetworkCallback() {
        try {
            if (netCallback == null) return;
            ConnectivityManager cm = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {}
    }

    private void schedulePoll() {
        pollRunnable = () -> {
            collectStats();
            if (running) handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
        };
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void collectStats() {
        if (pc == null || !running) return;
        try {
            pc.getStats(report -> handler.post(() -> parseStats(report)));
        } catch (Exception e) {
            Log.w(TAG, "getStats: " + e.getMessage());
        }
    }

    private void parseStats(RTCStatsReport report) {
        if (report == null || !running) return;
        double lossPercent = 0;
        double rttMs       = 0;
        double jitterMs    = 0;
        boolean gotInbound = false;

        for (Map.Entry<String, RTCStats> entry : report.getStatsMap().entrySet()) {
            RTCStats s    = entry.getValue();
            String   type = s.getType();
            Map<String, Object> m = s.getMembers();

            if ("inbound-rtp".equals(type)) {
                gotInbound = true;
                Object recv = m.get("packetsReceived");
                Object lost = m.get("packetsLost");
                if (recv instanceof Number && lost instanceof Number) {
                    double r = ((Number) recv).doubleValue();
                    double l = ((Number) lost).doubleValue();
                    if (r + l > 0) lossPercent = (l / (r + l)) * 100.0;
                }
                Object jit = m.get("jitter");
                if (jit instanceof Number) jitterMs = ((Number) jit).doubleValue() * 1000;
            }
            if ("remote-inbound-rtp".equals(type)) {
                Object rtt = m.get("roundTripTime");
                if (rtt instanceof Number) rttMs = ((Number) rtt).doubleValue() * 1000;
            }
        }

        if (!gotInbound) return;
        Quality q = classify(lossPercent, rttMs, jitterMs);
        if (q != lastQuality) {
            lastQuality = q;
            if (cb != null) cb.onQualityChanged(q, buildLabel(q, lossPercent, rttMs));
        }
    }

    private Quality classify(double loss, double rtt, double jitter) {
        if (loss < 1  && rtt < 100 && jitter < 20) return Quality.EXCELLENT;
        if (loss < 5  && rtt < 200 && jitter < 50) return Quality.GOOD;
        if (loss < 15 || rtt  < 400)               return Quality.FAIR;
        return Quality.POOR;
    }

    private String buildLabel(Quality q, double loss, double rtt) {
        switch (q) {
            case EXCELLENT: return "Excellent connection";
            case GOOD:      return "Good connection";
            case FAIR:      return String.format("Fair  %.0f%% loss", loss);
            case POOR:      return String.format("Poor  %.0f%% loss  %.0fms", loss, rtt);
            default:        return "";
        }
    }

    public Quality getLastQuality() { return lastQuality; }
}
