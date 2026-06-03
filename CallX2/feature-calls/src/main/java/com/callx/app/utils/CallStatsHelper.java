package com.callx.app.utils;

import org.webrtc.PeerConnection;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsCollectorCallback;
import org.webrtc.RTCStatsReport;
import java.math.BigInteger;
import java.util.Map;

/**
 * Feature: Call Stats Overlay
 *
 * PeerConnection.getStats() parse karke human-readable stats return karta hai.
 * Stats:
 *  - txBitrateKbps — outbound bitrate (kbps)
 *  - rxBitrateKbps — inbound bitrate  (kbps)
 *  - packetLossPct — packet loss percentage
 *  - rttMs         — round-trip time (ms)
 */
public class CallStatsHelper {

    public interface StatsCallback {
        void onStats(long txKbps, long rxKbps, double lossPct, long rttMs);
    }

    // State for bitrate delta calculation
    private static long lastTxBytes = 0, lastRxBytes = 0, lastStatsTs = 0;

    public static void collect(PeerConnection pc, StatsCallback cb) {
        if (pc == null || cb == null) return;
        pc.getStats(report -> {
            if (report == null) return;
            long now = System.currentTimeMillis();
            long txBytes = 0, rxBytes = 0;
            long packetsLost = 0, packetsReceived = 0;
            double rttSec = -1;

            for (RTCStats stats : report.getStatsMap().values()) {
                Map<String, Object> m = stats.getMembers();
                switch (stats.getType()) {
                    case "outbound-rtp": {
                        Object b = m.get("bytesSent");
                        if (b instanceof BigInteger)
                            txBytes = ((BigInteger) b).longValue();
                        else if (b instanceof Long)
                            txBytes = (Long) b;
                        break;
                    }
                    case "inbound-rtp": {
                        Object b = m.get("bytesReceived");
                        if (b instanceof BigInteger)
                            rxBytes = ((BigInteger) b).longValue();
                        else if (b instanceof Long)
                            rxBytes = (Long) b;
                        Object lost = m.get("packetsLost");
                        if (lost instanceof Integer) packetsLost += (Integer) lost;
                        else if (lost instanceof Long) packetsLost += (Long) lost;
                        Object recv = m.get("packetsReceived");
                        if (recv instanceof Integer) packetsReceived += (Integer) recv;
                        else if (recv instanceof Long) packetsReceived += (Long) recv;
                        break;
                    }
                    case "remote-inbound-rtp": {
                        Object rtt = m.get("roundTripTime");
                        if (rtt instanceof Double) rttSec = (Double) rtt;
                        break;
                    }
                }
            }

            long dtMs = (lastStatsTs == 0) ? 1000 : Math.max(1, now - lastStatsTs);
            long txKbps = (txBytes - lastTxBytes) * 8 / dtMs;
            long rxKbps = (rxBytes - lastRxBytes) * 8 / dtMs;
            lastTxBytes = txBytes; lastRxBytes = rxBytes; lastStatsTs = now;

            double total = packetsLost + packetsReceived;
            double lossPct = (total > 0) ? (packetsLost * 100.0 / total) : 0.0;
            long rttMs = (rttSec >= 0) ? (long)(rttSec * 1000) : -1;

            cb.onStats(Math.max(0, txKbps), Math.max(0, rxKbps), lossPct, rttMs);
        });
    }

    /** Reset delta state when a new call starts */
    public static void reset() {
        lastTxBytes = 0; lastRxBytes = 0; lastStatsTs = 0;
    }
}
