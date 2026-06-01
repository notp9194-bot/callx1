package com.callx.app.utils;

import com.callx.app.models.StatusItem;
import com.google.firebase.database.*;
import java.util.*;

/** StatusHighlightManager v26 — FIX: null id bug; uses push() key when id is missing. */
public final class StatusHighlightManager {
    private StatusHighlightManager() {}

    public static void addToHighlight(String ownerUid, StatusItem item, String albumId, String albumName) {
        if (ownerUid == null || item == null || albumId == null) return;
        // FIX: was `item.id != null ? item.id : "unknown"` — now always generates unique key
        DatabaseReference ref = getAlbumRef(ownerUid, albumId);
        String key = (item.id != null && !item.id.isEmpty()) ? item.id : ref.push().getKey();
        if (key == null) key = String.valueOf(System.currentTimeMillis());
        Map<String, Object> data = new HashMap<>(item.toMap());
        data.put("isHighlighted", true);
        data.put("highlightAlbumId", albumId);
        data.put("highlightAlbumName", albumName);
        ref.child(key).setValue(data);
    }
    public static void removeFromHighlight(String ownerUid, String albumId, String statusId) {
        if (ownerUid == null || albumId == null || statusId == null) return;
        getAlbumRef(ownerUid, albumId).child(statusId).removeValue();
    }
    public static void renameAlbum(String ownerUid, String albumId, String newName) {
        if (ownerUid == null || albumId == null || newName == null) return;
        // Update highlightAlbumName on every item in this album
        getAlbumRef(ownerUid, albumId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Map<String, Object> updates = new HashMap<>();
                for (DataSnapshot c : snap.getChildren())
                    updates.put(c.getKey() + "/highlightAlbumName", newName);
                if (!updates.isEmpty()) getAlbumRef(ownerUid, albumId).updateChildren(updates);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }
    public static void archiveStatus(String ownerUid, StatusItem item) {
        if (ownerUid == null || item == null) return;
        // FIX: same key-generation fix
        DatabaseReference ref = getArchiveRef(ownerUid);
        String key = (item.id != null && !item.id.isEmpty()) ? item.id : ref.push().getKey();
        if (key == null) key = String.valueOf(System.currentTimeMillis());
        Map<String, Object> data = new HashMap<>(item.toMap());
        data.put("isArchived", true); data.put("archivedAt", ServerValue.TIMESTAMP);
        ref.child(key).setValue(data);
    }
    public static void unarchiveStatus(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        getArchiveRef(ownerUid).child(statusId).removeValue();
    }
    public static DatabaseReference getHighlightsRef(String uid) {
        return FirebaseUtils.db().getReference("statusHighlights").child(uid);
    }
    public static DatabaseReference getAlbumRef(String uid, String albumId) {
        return getHighlightsRef(uid).child(albumId);
    }
    public static DatabaseReference getArchiveRef(String uid) {
        return FirebaseUtils.db().getReference("statusArchive").child(uid);
    }
}
