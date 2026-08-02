package com.callx.app.linkeddevice;

import androidx.annotation.Keep;

import com.google.firebase.database.IgnoreExtraProperties;

/**
 * Ephemeral node written by the web/desktop client when it displays a QR
 * code, and updated by the phone once the user approves or denies it.
 *
 * Stored at: pairingSessions/{pairingCode}   (short-lived, TTL ~90s while pending)
 *
 * Lifecycle:
 *   1. Web writes {status:"pending", deviceInfo, createdAt, expiresAt}
 *   2. Phone scans the QR (which just encodes the pairingCode), reads this
 *      node, shows an approval sheet.
 *   3. Phone writes {status:"approved", uid, deviceId} (or "denied").
 *   4. A Cloud Function trigger (see functions/index.js) notices the
 *      approval, mints a short-lived custom auth token for `uid`, and writes
 *      it back to this same node as `customToken`.
 *   5. Web signs in with that token via signInWithCustomToken, then deletes
 *      the pairingSessions/{pairingCode} node (it's single-use).
 */
@Keep
@IgnoreExtraProperties
public class PairingSession {

    public static final String STATUS_PENDING  = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_DENIED   = "denied";
    public static final String STATUS_EXPIRED  = "expired";

    /** How long a QR code stays valid before the web client must regenerate it. */
    public static final long PAIRING_TTL_MS = 90_000L;

    public String status;
    public long createdAt;
    public long expiresAt;
    public DeviceInfo deviceInfo;

    // Filled in once approved:
    public String uid;
    public String deviceId;
    public String customToken;     // minted by Cloud Function, consumed once by the web client

    @Keep
    public PairingSession() { /* required by Firebase */ }

    @Keep
    @IgnoreExtraProperties
    public static class DeviceInfo {
        public String browser;
        public String os;
        public String label; // human-readable, e.g. "Chrome on Windows"

        @Keep
        public DeviceInfo() { }

        public DeviceInfo(String browser, String os) {
            this.browser = browser;
            this.os = os;
            this.label = browser + " on " + os;
        }
    }
}
