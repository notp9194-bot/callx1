package com.callx.app.utils;

import android.util.Log;
import org.webrtc.RTCStats;
import org.webrtc.RTCStatsReport;
import java.util.Map;

/**
 * CallEncryptionHelper — DTLS-SRTP encryption status verification.
 *
 * WebRTC mandates DTLS-SRTP for all media. This helper parses RTCStatsReport
 * to confirm the DTLS handshake state and SRTP cipher suite, then returns
 * a human-readable status for display in the call UI.
 *
 * Status levels:
 *   ENCRYPTED    DTLS "connected", SRTP cipher confirmed
 *   PENDING      DTLS "connecting" / "new"
 *   UNENCRYPTED  DTLS "failed" — should never happen with standard WebRTC
 *   UNKNOWN      Stats not yet available
 */
public class CallEncryptionHelper {

    private static final String TAG = "CallEncryptionHelper";

    public enum EncryptionStatus {
        UNKNOWN     ("\uD83D\uDD12"),          // 🔒 (placeholder)
        PENDING     ("Securing\u2026"),        // Securing…
        ENCRYPTED   ("\uD83D\uDD12 Encrypted"), // 🔒 Encrypted
        UNENCRYPTED ("\u26A0 Not encrypted");   // ⚠ Not encrypted

        public final String label;
        EncryptionStatus(String label) { this.label = label; }
    }

    /** Parse RTCStatsReport and return encryption status. */
    public static EncryptionStatus extractStatus(RTCStatsReport report) {
        if (report == null) return EncryptionStatus.UNKNOWN;

        for (Map.Entry<String, RTCStats> entry : report.getStatsMap().entrySet()) {
            RTCStats s = entry.getValue();
            if (!"transport".equals(s.getType())) continue;

            Map<String, Object> m = s.getMembers();
            Object dtlsState  = m.get("dtlsState");
            Object srtpCipher = m.get("srtpCipher");

            String state  = dtlsState  != null ? dtlsState.toString()  : "";
            String cipher = srtpCipher != null ? srtpCipher.toString() : "";

            switch (state) {
                case "connected":
                case "closed":
                    if (!cipher.isEmpty()) {
                        Log.d(TAG, "SRTP cipher: " + cipher);
                        return EncryptionStatus.ENCRYPTED;
                    }
                    return EncryptionStatus.PENDING;
                case "failed":
                    Log.w(TAG, "DTLS negotiation failed — media NOT encrypted");
                    return EncryptionStatus.UNENCRYPTED;
                case "connecting":
                case "new":
                    return EncryptionStatus.PENDING;
                default:
                    break;
            }
        }
        return EncryptionStatus.UNKNOWN;
    }
}
