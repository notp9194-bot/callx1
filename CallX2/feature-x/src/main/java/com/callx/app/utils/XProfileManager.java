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

    // ── In-memory profile cache — same session mein repeat Firebase call nahi ──
    private static final Map<String, XProfile> sCache = new java.util.concurrent.ConcurrentHashMap<>();

    public interface ProfileCallback {
        void onProfile(XProfile profile);
    }

    public interface SaveCallback {
        void onSuccess();
        void onError(String error);
    }

    // ── Load ─────────────────────────────────────────────────────────────────

    /** One-time load of an X profile — cache-first, Firebase fallback. */
    public static void load(String uid, ProfileCallback cb) {
        // FIX: Same session mein cache se instant load — Firebase call nahi
        if (uid != null && sCache.containsKey(uid)) {
            if (cb != null) cb.onProfile(sCache.get(uid));
            return;
        }
        XFirebaseUtils.xUserRef(uid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    XProfile p = snap.getValue(XProfile.class);
                    if (p != null) {
                        p.uid = snap.getKey();
                        sCache.put(uid, p); // Cache karo next call ke liye
                    }
                    if (cb != null) cb.onProfile(p);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    if (cb != null) cb.onProfile(null);
                }
            });
    }

    /** Cache invalidate karo jab profile update ho (EditProfile ke baad call karo). */
    public static void invalidate(String uid) {
        if (uid != null) sCache.remove(uid);
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
     * If the handle changed, the new handle is reserved via a collision-safe
     * transaction FIRST (same pattern as AccountMenuActivity's username
     * change) — only once that commits does the profile get updated and the
     * old handle released. Previously this saved the profile first and
     * reserved the handle as an afterthought, which both left a race window
     * (two people could tap Save on the same handle at once) and meant the
     * users/{uid}/handle field could never be validated against x_handles
     * ownership server-side, since the reservation didn't exist yet at the
     * moment the profile write happened.
     */
    public static void save(String uid, XProfile profile,
                             String oldHandle, SaveCallback cb) {
        if (uid == null || profile == null) {
            if (cb != null) cb.onError("Invalid parameters");
            return;
        }

        String newHandle = profile.handle != null ? profile.handle : "";
        String prevHandle = oldHandle != null ? oldHandle : "";

        if (!newHandle.equals(prevHandle) && !newHandle.isEmpty()) {
            XFirebaseUtils.xHandleRef(newHandle).runTransaction(new Transaction.Handler() {
                @NonNull
                @Override
                public Transaction.Result doTransaction(@NonNull MutableData data) {
                    Object current = data.getValue();
                    if (current != null && !current.equals(uid)) {
                        return Transaction.abort();
                    }
                    data.setValue(uid);
                    return Transaction.success(data);
                }
                @Override
                public void onComplete(DatabaseError error, boolean committed, DataSnapshot snap) {
                    if (error != null) {
                        if (cb != null) cb.onError(error.getMessage());
                        return;
                    }
                    if (!committed) {
                        if (cb != null) cb.onError("Handle already taken");
                        return;
                    }
                    writeProfileThenReleaseOldHandle(uid, profile, prevHandle, newHandle, cb);
                }
            });
        } else {
            writeProfileThenReleaseOldHandle(uid, profile, prevHandle, newHandle, cb);
        }
    }

    private static void writeProfileThenReleaseOldHandle(String uid, XProfile profile,
            String prevHandle, String newHandle, SaveCallback cb) {
        Map<String, Object> updates = profile.toProfileMap();
        XFirebaseUtils.xUserRef(uid).updateChildren(updates)
            .addOnSuccessListener(v -> {
                if (!newHandle.equals(prevHandle) && !prevHandle.isEmpty()) {
                    XFirebaseUtils.xHandlesRef().child(prevHandle).removeValue();
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
