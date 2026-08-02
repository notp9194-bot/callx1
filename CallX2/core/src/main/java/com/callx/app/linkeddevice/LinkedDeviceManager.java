package com.callx.app.linkeddevice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single entry point for the "Linked Devices" feature — pairing a new
 * desktop/web session via QR code, listening to the live device list,
 * renaming/revoking sessions, and cleaning up expired pairing attempts.
 *
 * The phone is always the *authenticator*: only it can approve a pairing
 * request, because only it holds the real Firebase Auth session. The web
 * client never gets the user's password/credentials — it gets a narrowly
 * scoped custom token minted server-side (see functions/index.js) only
 * after the phone explicitly approves the exact pairing code shown on screen.
 */
public class LinkedDeviceManager {

    public interface PairingCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface PairingSessionCallback {
        void onFound(PairingSession session);
        void onNotFound();
        void onExpired();
        void onError(String message);
    }

    public interface DeviceListListener {
        void onDevicesChanged(List<LinkedDevice> devices);
    }

    private static LinkedDeviceManager instance;

    public static synchronized LinkedDeviceManager get() {
        if (instance == null) instance = new LinkedDeviceManager();
        return instance;
    }

    private LinkedDeviceManager() { }

    /**
     * Call after the phone's camera decodes a QR payload. The QR only ever
     * encodes the bare pairing code (e.g. "callx2-pair:AB3F9K2Q"), never
     * anything sensitive — looking it up requires knowing the exact code
     * AND having the phone (already-authenticated) user tap Approve.
     */
    public void lookupPairingSession(String pairingCode, @NonNull PairingSessionCallback cb) {
        FirebaseUtils.getPairingSessionRef(pairingCode).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                PairingSession session = snapshot.getValue(PairingSession.class);
                if (session == null) {
                    cb.onNotFound();
                    return;
                }
                if (System.currentTimeMillis() > session.expiresAt
                        || PairingSession.STATUS_EXPIRED.equals(session.status)) {
                    cb.onExpired();
                    return;
                }
                cb.onFound(session);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                cb.onError(error.getMessage());
            }
        });
    }

    /**
     * Approves a pairing request scanned from a desktop QR code. Creates the
     * users/{uid}/linkedDevices/{deviceId} entry and flips the pairing
     * session to "approved" so the Cloud Function trigger can mint a custom
     * token for the web client to sign in with.
     */
    public void approvePairing(String pairingCode, PairingSession session, @NonNull PairingCallback cb) {
        String uid = FirebaseUtils.getMyUid();
        if (uid == null || uid.isEmpty()) {
            cb.onError("Not signed in");
            return;
        }

        String deviceId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        String browser = session.deviceInfo != null ? session.deviceInfo.browser : "Unknown browser";
        String os = session.deviceInfo != null ? session.deviceInfo.os : "Unknown OS";
        String label = session.deviceInfo != null && session.deviceInfo.label != null
                ? session.deviceInfo.label : (browser + " on " + os);

        LinkedDevice device = new LinkedDevice(deviceId, label, browser, os, pairingCode, now);

        Map<String, Object> updates = new HashMap<>();
        updates.put("/users/" + uid + "/linkedDevices/" + deviceId, deviceToMap(device));
        updates.put("/pairingSessions/" + pairingCode + "/status", PairingSession.STATUS_APPROVED);
        updates.put("/pairingSessions/" + pairingCode + "/uid", uid);
        updates.put("/pairingSessions/" + pairingCode + "/deviceId", deviceId);

        FirebaseUtils.db().getReference().updateChildren(updates)
                .addOnSuccessListener(unused -> cb.onSuccess())
                .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    public void denyPairing(String pairingCode, @NonNull PairingCallback cb) {
        FirebaseUtils.getPairingSessionRef(pairingCode).child("status")
                .setValue(PairingSession.STATUS_DENIED)
                .addOnSuccessListener(unused -> cb.onSuccess())
                .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    /** Live list of this account's linked devices, most recently linked first. */
    public ValueEventListener listenDevices(@NonNull DeviceListListener listener) {
        String uid = FirebaseUtils.getMyUid();
        ValueEventListener vel = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<LinkedDevice> devices = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    LinkedDevice d = child.getValue(LinkedDevice.class);
                    if (d != null && d.isActive()) devices.add(d);
                }
                java.util.Collections.sort(devices, (a, b) -> Long.compare(b.linkedAt, a.linkedAt));
                listener.onDevicesChanged(devices);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) { /* no-op — UI keeps last known list */ }
        };
        FirebaseUtils.getLinkedDevicesRef(uid).addValueEventListener(vel);
        return vel;
    }

    public void stopListening(ValueEventListener vel) {
        String uid = FirebaseUtils.getMyUid();
        if (vel != null) FirebaseUtils.getLinkedDevicesRef(uid).removeEventListener(vel);
    }

    /**
     * Revokes a single companion session. The web client is listening on its
     * own device node and force-logs-out the moment it disappears/flips to
     * "revoked" (see callx2-web.html).
     */
    public void revokeDevice(String deviceId, @NonNull PairingCallback cb) {
        String uid = FirebaseUtils.getMyUid();
        FirebaseUtils.getLinkedDeviceRef(uid, deviceId).removeValue()
                .addOnSuccessListener(unused -> cb.onSuccess())
                .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    /** "Log out of all other devices" — mirrors the WhatsApp Linked Devices screen action. */
    public void revokeAllDevices(@NonNull PairingCallback cb) {
        String uid = FirebaseUtils.getMyUid();
        FirebaseUtils.getLinkedDevicesRef(uid).removeValue()
                .addOnSuccessListener(unused -> cb.onSuccess())
                .addOnFailureListener(e -> cb.onError(e.getMessage()));
    }

    @Nullable
    private Map<String, Object> deviceToMap(LinkedDevice d) {
        Map<String, Object> m = new HashMap<>();
        m.put("deviceId", d.deviceId);
        m.put("deviceName", d.deviceName);
        m.put("browser", d.browser);
        m.put("os", d.os);
        m.put("pairingCode", d.pairingCode);
        m.put("status", d.status);
        m.put("linkedAt", d.linkedAt);
        m.put("lastActiveAt", d.lastActiveAt);
        return m;
    }
}
