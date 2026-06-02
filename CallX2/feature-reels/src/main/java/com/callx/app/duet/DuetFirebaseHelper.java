package com.callx.app.duet;

import androidx.annotation.NonNull;

import com.callx.app.utils.ReelFirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * DuetFirebaseHelper — Production Firebase operations for the Duet system.
 *
 * Firebase structure managed here:
 *   reels/reel_duets/{originalReelId}/{duetReelId}  → {duetUid, duetReelId, timestamp}
 *   reels/user_duets/{uid}/{duetReelId}              → originalReelId
 *   reels/videos/{originalReelId}/duetCount          → int (via Transaction)
 *   reels/remix_settings/{originalReelId}/duet       → "everyone"|"followers"|"off"
 *   reels/notifications/{ownerUid}/{notifId}         → in-app notification entry
 */
public class DuetFirebaseHelper {

    private static final String ROOT       = "reels";
    private static final String DUETS_NODE = "reel_duets";
    private static final String USER_DUETS = "user_duets";

    private static DatabaseReference root() {
        return FirebaseDatabase.getInstance().getReference(ROOT);
    }

    // ── Refs ─────────────────────────────────────────────────────────────────

    public static DatabaseReference duetsOfReelRef(String originalReelId) {
        return root().child(DUETS_NODE).child(originalReelId);
    }

    public static DatabaseReference userDuetsRef(String uid) {
        return root().child(USER_DUETS).child(uid);
    }

    public static DatabaseReference remixSettingsRef(String reelId) {
        return root().child("remix_settings").child(reelId);
    }

    // ── Write duet to Firebase ────────────────────────────────────────────────

    /**
     * Called from ReelUploadActivity after a duet is successfully uploaded.
     * Atomically:
     *  1. Writes duet entry under reel_duets/{originalReelId}/{duetReelId}
     *  2. Writes user_duets/{currentUid}/{duetReelId} → originalReelId
     *  3. Increments duetCount on original reel via Transaction
     *  4. Writes in-app notification to the original creator
     */
    public static void recordDuetPosted(
            String originalReelId,
            String originalOwnerUid,
            String duetReelId,
            String dueterName,
            String dueterPhotoUrl,
            OnDuetRecordedCallback callback) {

        String currentUid = currentUid();
        if (currentUid == null || originalReelId == null || duetReelId == null) {
            if (callback != null) callback.onError("Invalid parameters");
            return;
        }

        // 1. Write duet index entry
        Map<String, Object> duetEntry = new HashMap<>();
        duetEntry.put("duetReelId",    duetReelId);
        duetEntry.put("duetUid",       currentUid);
        duetEntry.put("dueterName",    dueterName != null ? dueterName : "");
        duetEntry.put("dueterPhoto",   dueterPhotoUrl != null ? dueterPhotoUrl : "");
        duetEntry.put("timestamp",     ServerValue.TIMESTAMP);

        Map<String, Object> userDuetEntry = new HashMap<>();
        userDuetEntry.put("originalReelId", originalReelId);
        userDuetEntry.put("timestamp",      ServerValue.TIMESTAMP);

        // Multi-path write
        Map<String, Object> multiPath = new HashMap<>();
        multiPath.put(DUETS_NODE + "/" + originalReelId + "/" + duetReelId, duetEntry);
        multiPath.put(USER_DUETS  + "/" + currentUid    + "/" + duetReelId, userDuetEntry);

        root().updateChildren(multiPath)
            .addOnSuccessListener(unused -> {
                // 2. Increment duetCount via atomic transaction
                DatabaseReference countRef =
                    root().child("videos").child(originalReelId).child("duetCount");
                countRef.runTransaction(new Transaction.Handler() {
                    @NonNull @Override
                    public Transaction.Result doTransaction(@NonNull MutableData data) {
                        Integer current = data.getValue(Integer.class);
                        data.setValue(current == null ? 1 : current + 1);
                        return Transaction.success(data);
                    }
                    @Override
                    public void onComplete(DatabaseError error, boolean committed,
                                          DataSnapshot snap) {
                        // 3. Write in-app notification to original owner
                        if (originalOwnerUid != null && !originalOwnerUid.equals(currentUid)) {
                            writeInAppNotification(originalOwnerUid, originalReelId,
                                duetReelId, dueterName);
                        }
                        if (callback != null) {
                            if (error == null) callback.onSuccess();
                            else callback.onError(error.getMessage());
                        }
                    }
                });
            })
            .addOnFailureListener(e -> {
                if (callback != null) callback.onError(e.getMessage());
            });
    }

    // ── Check duet permission ─────────────────────────────────────────────────

    /**
     * Checks if the current user is allowed to duet a given reel.
     * Respects the remix_settings.duet field: "everyone" | "followers" | "off"
     */
    public static void checkDuetPermission(String reelId, String ownerUid,
                                            OnPermissionCheckCallback callback) {
        if (callback == null) return;
        remixSettingsRef(reelId).child("duet")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String perm = snap.getValue(String.class);
                    if (perm == null || perm.equals("everyone")) {
                        callback.onResult(true);
                        return;
                    }
                    if (perm.equals("off")) {
                        callback.onResult(false);
                        return;
                    }
                    // "followers" — check if current user follows owner
                    String uid = currentUid();
                    if (uid == null) { callback.onResult(false); return; }
                    ReelFirebaseUtils.reelFollowersRef(ownerUid).child(uid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot snap2) {
                                callback.onResult(snap2.exists());
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) {
                                callback.onResult(false);
                            }
                        });
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    callback.onResult(true); // fail-open
                }
            });
    }

    // ── Load duets of a reel ─────────────────────────────────────────────────

    public static void loadDuetsOfReel(String originalReelId, int limit,
                                        OnDuetsLoadedCallback callback) {
        duetsOfReelRef(originalReelId)
            .orderByChild("timestamp")
            .limitToLast(limit)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    java.util.List<String> duetReelIds = new java.util.ArrayList<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        String id = child.child("duetReelId").getValue(String.class);
                        if (id != null) duetReelIds.add(id);
                    }
                    if (callback != null) callback.onLoaded(duetReelIds);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (callback != null) callback.onLoaded(new java.util.ArrayList<>());
                }
            });
    }

    // ── In-app notification ───────────────────────────────────────────────────

    private static void writeInAppNotification(String ownerUid, String originalReelId,
                                               String duetReelId, String dueterName) {
        String notifId = root().child("notifications").child(ownerUid).push().getKey();
        if (notifId == null) return;
        Map<String, Object> notif = new HashMap<>();
        notif.put("type",          "duet");
        notif.put("originalReelId", originalReelId);
        notif.put("duetReelId",    duetReelId);
        notif.put("actorUid",      currentUid());
        notif.put("actorName",     dueterName != null ? dueterName : "Someone");
        notif.put("timestamp",     ServerValue.TIMESTAMP);
        notif.put("read",          false);
        root().child("notifications").child(ownerUid).child(notifId).setValue(notif);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String currentUid() {
        com.google.firebase.auth.FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        return user != null ? user.getUid() : null;
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    public interface OnDuetRecordedCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface OnPermissionCheckCallback {
        void onResult(boolean canDuet);
    }

    public interface OnDuetsLoadedCallback {
        void onLoaded(java.util.List<String> duetReelIds);
    }
}
