package com.callx.app.linkeddevice;

import androidx.annotation.Keep;

import com.google.firebase.database.Exclude;
import com.google.firebase.database.IgnoreExtraProperties;

/**
 * A single linked companion session (e.g. "CallX2 Web" running in a desktop
 * browser). Mirrors WhatsApp's "Linked Devices" model: the phone remains the
 * source of truth / primary auth identity, and each companion is just an
 * approved, revocable read-write session scoped to the same account.
 *
 * Stored at: users/{uid}/linkedDevices/{deviceId}
 */
@Keep
@IgnoreExtraProperties
public class LinkedDevice {

    public static final String STATUS_ACTIVE  = "active";
    public static final String STATUS_REVOKED = "revoked";

    public String deviceId;
    public String deviceName;      // e.g. "Chrome on Windows"
    public String browser;         // "Chrome", "Firefox", "Edge", "Safari"
    public String os;              // "Windows", "macOS", "Linux"
    public String pairingCode;     // the code this device was paired with (audit trail)
    public String status;          // active | revoked
    public long linkedAt;
    public long lastActiveAt;      // updated by a periodic heartbeat from the web client
    public String approxLocation;  // optional, best-effort, e.g. "Mumbai, India" — never precise GPS

    @Keep
    public LinkedDevice() { /* required by Firebase */ }

    public LinkedDevice(String deviceId, String deviceName, String browser, String os,
                         String pairingCode, long linkedAt) {
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.browser = browser;
        this.os = os;
        this.pairingCode = pairingCode;
        this.status = STATUS_ACTIVE;
        this.linkedAt = linkedAt;
        this.lastActiveAt = linkedAt;
    }

    @Exclude
    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }
}
