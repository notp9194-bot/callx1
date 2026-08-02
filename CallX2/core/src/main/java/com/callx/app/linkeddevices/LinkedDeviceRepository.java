package com.callx.app.linkeddevices;

import androidx.annotation.NonNull;

import com.callx.app.utils.Constants;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * All Firebase reads/writes for the "Linked Devices" pairing handshake.
 *
 * Firebase layout:
 *   pairingRequests/{pairingId}
 *       primaryUid        : uid that generated the QR
 *       status            : "pending" -> "awaiting_approval" -> "approved" | "rejected" | "expired"
 *       companionUid      : filled in once the companion scans the QR
 *       companionDeviceName, companionPlatform, companionAppVersion
 *       createdAt         : server timestamp
 *
 *   users/{primaryUid}/linkedDevices/{companionUid}
 *       deviceName, platform, appVersion, linkedAt, lastActiveAt
 *
 * See /firebase_rules/firebase_linked_devices_rules.json for the security
 * rules this depends on (a device only ever writes its own pairing request
 * or its own linkedDevices entry).
 */
public class LinkedDeviceRepository {

    private static final String NODE_PAIRING = "pairingRequests";
    private static final String NODE_USERS = "users";
    private static final String NODE_LINKED_DEVICES = "linkedDevices";
    private static final long PAIRING_TTL_MS = 5 * 60 * 1000L; // QR code valid for 5 minutes

    public interface PairingRequestListener {
        void onCompanionRequested(String companionUid, String deviceName, String platform);
        void onExpired();
        void onError(String message);
    }

    public interface ApprovalListener {
        void onApproved(String primaryUid, String primaryName);
        void onRejected();
        void onExpired();
        void onError(String message);
    }

    public interface DeviceListListener {
        void onDevices(List<LinkedDeviceModel> devices);
        void onError(String message);
    }

    private DatabaseReference root() {
        return FirebaseDatabase.getInstance(Constants.DB_URL).getReference();
    }

    // ── Primary device: generate + watch a pairing session ─────────────────

    /** Creates a fresh pairing session and returns its id — encode this + primaryUid into the QR. */
    public String createPairingSession(String primaryUid) {
        String pairingId = UUID.randomUUID().toString();
        Map<String, Object> data = new HashMap<>();
        data.put("primaryUid", primaryUid);
        data.put("status", "pending");
        data.put("createdAt", ServerValue.TIMESTAMP);
        root().child(NODE_PAIRING).child(pairingId).setValue(data);
        return pairingId;
    }

    /** Primary device listens here after showing the QR — fires once a companion scans it. */
    public ValueEventListener watchPairingSession(String pairingId, PairingRequestListener listener) {
        DatabaseReference ref = root().child(NODE_PAIRING).child(pairingId);
        ValueEventListener vel = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) { listener.onExpired(); return; }
                Long createdAt = snapshot.child("createdAt").getValue(Long.class);
                if (createdAt != null && System.currentTimeMillis() - createdAt > PAIRING_TTL_MS) {
                    listener.onExpired();
                    return;
                }
                String status = snapshot.child("status").getValue(String.class);
                if ("awaiting_approval".equals(status)) {
                    String companionUid = snapshot.child("companionUid").getValue(String.class);
                    String deviceName = snapshot.child("companionDeviceName").getValue(String.class);
                    String platform = snapshot.child("companionPlatform").getValue(String.class);
                    listener.onCompanionRequested(companionUid, deviceName, platform);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                listener.onError(error.getMessage());
            }
        };
        ref.addValueEventListener(vel);
        return vel;
    }

    public void stopWatching(String pairingId, ValueEventListener listener) {
        if (listener == null) return;
        root().child(NODE_PAIRING).child(pairingId).removeEventListener(listener);
    }

    /** Primary device taps "Approve" on the incoming request. */
    public void approvePairing(String pairingId, String primaryUid, String primaryName,
                                String companionUid, String deviceName, String platform, String appVersion) {
        LinkedDeviceModel model = new LinkedDeviceModel(
            companionUid, deviceName, platform, appVersion,
            System.currentTimeMillis(), System.currentTimeMillis());
        root().child(NODE_USERS).child(primaryUid).child(NODE_LINKED_DEVICES)
            .child(companionUid).setValue(model.toMap());

        Map<String, Object> statusUpdate = new HashMap<>();
        statusUpdate.put("status", "approved");
        statusUpdate.put("primaryName", primaryName == null ? "" : primaryName);
        root().child(NODE_PAIRING).child(pairingId).updateChildren(statusUpdate);
    }

    /** Primary device taps "Deny". */
    public void rejectPairing(String pairingId) {
        root().child(NODE_PAIRING).child(pairingId).child("status").setValue("rejected");
    }

    // ── Companion device: scan QR, submit request, wait for approval ───────

    /** Companion calls this right after scanning the QR (already anonymously signed in). */
    public void submitCompanionRequest(String pairingId, String companionUid,
                                        String deviceName, String platform, String appVersion) {
        Map<String, Object> update = new HashMap<>();
        update.put("status", "awaiting_approval");
        update.put("companionUid", companionUid);
        update.put("companionDeviceName", deviceName);
        update.put("companionPlatform", platform);
        update.put("companionAppVersion", appVersion);
        root().child(NODE_PAIRING).child(pairingId).updateChildren(update);
    }

    /** Companion listens here after submitting its request. */
    public ValueEventListener watchApproval(String pairingId, ApprovalListener listener) {
        DatabaseReference ref = root().child(NODE_PAIRING).child(pairingId);
        ValueEventListener vel = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) { listener.onExpired(); return; }
                String status = snapshot.child("status").getValue(String.class);
                String primaryUid = snapshot.child("primaryUid").getValue(String.class);
                if ("approved".equals(status)) {
                    String primaryName = snapshot.child("primaryName").getValue(String.class);
                    listener.onApproved(primaryUid, primaryName);
                } else if ("rejected".equals(status)) {
                    listener.onRejected();
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                listener.onError(error.getMessage());
            }
        };
        ref.addValueEventListener(vel);
        return vel;
    }

    // ── Managing already-linked devices (primary side) ─────────────────────

    public ValueEventListener watchLinkedDevices(String primaryUid, DeviceListListener listener) {
        DatabaseReference ref = root().child(NODE_USERS).child(primaryUid).child(NODE_LINKED_DEVICES);
        ValueEventListener vel = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<LinkedDeviceModel> devices = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    LinkedDeviceModel m = new LinkedDeviceModel();
                    m.deviceUid = child.getKey();
                    m.deviceName = child.child("deviceName").getValue(String.class);
                    m.platform = child.child("platform").getValue(String.class);
                    m.appVersion = child.child("appVersion").getValue(String.class);
                    Long linkedAt = child.child("linkedAt").getValue(Long.class);
                    Long lastActiveAt = child.child("lastActiveAt").getValue(Long.class);
                    m.linkedAt = linkedAt == null ? 0 : linkedAt;
                    m.lastActiveAt = lastActiveAt == null ? 0 : lastActiveAt;
                    devices.add(m);
                }
                listener.onDevices(devices);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                listener.onError(error.getMessage());
            }
        };
        ref.addValueEventListener(vel);
        return vel;
    }

    public void stopWatchingDevices(String primaryUid, ValueEventListener listener) {
        if (listener == null) return;
        root().child(NODE_USERS).child(primaryUid).child(NODE_LINKED_DEVICES).removeEventListener(listener);
    }

    /** Primary taps "Remove" on a linked device — instantly revokes its data access. */
    public void revokeDevice(String primaryUid, String companionUid) {
        root().child(NODE_USERS).child(primaryUid).child(NODE_LINKED_DEVICES).child(companionUid).removeValue();
    }

    /** Companion pings this periodically (e.g. on app foreground) so "Last active" stays fresh. */
    public void touchLastActive(String primaryUid, String companionUid) {
        root().child(NODE_USERS).child(primaryUid).child(NODE_LINKED_DEVICES)
            .child(companionUid).child("lastActiveAt").setValue(ServerValue.TIMESTAMP);
    }
}
