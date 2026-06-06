package com.callx.app.utils;

import androidx.annotation.NonNull;
import com.callx.app.models.XProfile;
import com.google.firebase.database.*;
import java.util.Map;

/**
 * XProfileManager — Single place for all X profile read/write operations.
 *
 * Usage:
 *   XProfileManager.load(uid, profile -> { ... });
 *   XProfileManager.save(uid, profile, onSuccess, onError);
 *   XProfileManager.updateAvatar(uid, photoUrl, thumbUrl);
 *   XProfileManager.updateBanner(uid, bannerUrl);
 */
public class XProfileManager {

    public interface ProfileCallback {
        void onProfile(XProfile profile);
    }

    public interface SaveCallback {
        void onSuccess();
        void onError(String error);
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    /** One-time load of an X profile. */
    public static void load(String uid, ProfileCallback cb) {
        XFirebaseUtils.xUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    XProfile p = snap.getValue(XProfile.class);
                    if (p != null) p.uid = snap.getKey();
                    if (cb != null) cb.onProfile(p);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (cb != null) cb.onProfile(null);
                }
            });
    }

    /** Real-time listener for an X profile. Returns the listener so caller can detach it. */
    public static ValueEventListener observe(String uid, ProfileCallback cb) {
        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                XProfile p = snap.getValue(XProfile.class);
                if (p != null) p.uid = snap.getKey();
                if (cb != null) cb.onProfile(p);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                if (cb != null) cb.onProfile(null);
            }
        };
        XFirebaseUtils.xUserRef(uid).addValueEventListener(listener);
        return listener;
    }

    /** Detach a previously registered listener. */
    public static void stopObserving(String uid, ValueEventListener listener) {
        if (listener != null)
            XFirebaseUtils.xUserRef(uid).removeEventListener(listener);
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    /**
     * Saves editable fields of an XProfile.
     * If the handle changed, atomically updates the handle index.
     */
    public static void save(String uid, XProfile profile,
                             String oldHandle, SaveCallback cb) {
        if (uid == null || profile == null) {
            if (cb != null) cb.onError("Invalid parameters");
            return;
        }

        Map<String, Object> updates = profile.toProfileMap();
        XFirebaseUtils.xUserRef(uid).updateChildren(updates)
            .addOnSuccessListener(v -> {
                // Update handle index if handle changed
                String newHandle = profile.handle != null ? profile.handle : "";
                if (!newHandle.equals(oldHandle != null ? oldHandle : "")) {
                    if (oldHandle != null && !oldHandle.isEmpty())
                        XFirebaseUtils.xHandlesRef().child(oldHandle).removeValue();
                    if (!newHandle.isEmpty())
                        XFirebaseUtils.xHandlesRef().child(newHandle).setValue(uid);
                }
                if (cb != null) cb.onSuccess();
            })
            .addOnFailureListener(e -> {
                if (cb != null) cb.onError(e.getMessage());
            });
    }

    // ── Avatar / Banner ───────────────────────────────────────────────────────

    /** Update avatar URLs after successful Cloudinary upload. */
    public static void updateAvatar(String uid, String photoUrl, String thumbUrl) {
        DatabaseReference ref = XFirebaseUtils.xUserRef(uid);
        ref.child("photoUrl").setValue(photoUrl);
        if (thumbUrl != null && !thumbUrl.isEmpty())
            ref.child("thumbUrl").setValue(thumbUrl);
        ref.child("updatedAt").setValue(System.currentTimeMillis());
    }

    /** Update banner URL after successful Cloudinary upload. */
    public static void updateBanner(String uid, String bannerUrl) {
        XFirebaseUtils.xUserRef(uid).child("bannerUrl").setValue(bannerUrl);
        XFirebaseUtils.xUserRef(uid).child("updatedAt")
            .setValue(System.currentTimeMillis());
    }

    // ── Handle uniqueness check ───────────────────────────────────────────────

    public interface HandleCheckCallback {
        void onResult(boolean available);
    }

    /** Returns true if the handle is available (not taken by another user). */
    public static void checkHandleAvailable(String handle, String currentUid,
                                             HandleCheckCallback cb) {
        XFirebaseUtils.xHandleRef(handle)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    if (!snap.exists()) { if (cb != null) cb.onResult(true); return; }
                    // Available if taken by the same user (they own it already)
                    String owner = snap.getValue(String.class);
                    if (cb != null) cb.onResult(currentUid.equals(owner));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (cb != null) cb.onResult(false);
                }
            });
    }

    // ── Profile view counter ─────────────────────────────────────────────────

    /** Increment profileViews counter (call when someone views another user's profile). */
    public static void incrementProfileViews(String uid) {
        XFirebaseUtils.userProfileViewsRef(uid)
            .runTransaction(new Transaction.Handler() {
                @NonNull @Override
                public Transaction.Result doTransaction(@NonNull MutableData d) {
                    Long c = d.getValue(Long.class);
                    d.setValue(c != null ? c + 1 : 1);
                    return Transaction.success(d);
                }
                @Override public void onComplete(DatabaseError e, boolean c, DataSnapshot s) {}
            });
    }
}
