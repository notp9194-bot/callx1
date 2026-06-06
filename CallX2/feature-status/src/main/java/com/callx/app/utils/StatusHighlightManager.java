package com.callx.app.utils;
import com.callx.app.models.StatusItem;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;
/**
 * StatusHighlightManager — Add expired/active statuses to Highlights albums.
 * Firebase path: statusHighlights/{ownerUid}/{albumId}/{statusId}
 * Also supports archive: statusArchive/{ownerUid}/{statusId}
 */
public final class StatusHighlightManager {
    private StatusHighlightManager() {}
    // ── Highlights ────────────────────────────────────────────────────────
    public static void addToHighlight(String ownerUid, StatusItem item,
                                      String albumId, String albumName) {
        if (ownerUid == null || item == null || albumId == null) return;
        DatabaseReference ref = FirebaseUtils.db()
            .getReference("statusHighlights")
            .child(ownerUid)
            .child(albumId)
            .child(item.id != null ? item.id : FirebaseUtils.db().getReference().push().getKey());
        Map<String, Object> data = new HashMap<>(item.toMap());
        data.put("isHighlighted",     true);
        data.put("highlightAlbumId",  albumId);
        data.put("highlightAlbumName", albumName);
        ref.setValue(data);
    }
    public static void removeFromHighlight(String ownerUid, String albumId, String statusId) {
        if (ownerUid == null || albumId == null || statusId == null) return;
        FirebaseUtils.db()
            .getReference("statusHighlights")
            .child(ownerUid)
            .child(albumId)
            .child(statusId)
            .removeValue();
    }
    public static DatabaseReference getHighlightsRef(String ownerUid) {
        return FirebaseUtils.db()
            .getReference("statusHighlights")
            .child(ownerUid);
    }
    public static DatabaseReference getAlbumRef(String ownerUid, String albumId) {
        return FirebaseUtils.db()
            .getReference("statusHighlights")
            .child(ownerUid)
            .child(albumId);
    }
    // ── Archive ───────────────────────────────────────────────────────────
    public static void archiveStatus(String ownerUid, StatusItem item) {
        if (ownerUid == null || item == null) return;
        String key = item.id != null ? item.id : "unknown";
        Map<String, Object> data = new HashMap<>(item.toMap());
        data.put("isArchived", true);
        data.put("archivedAt", ServerValue.TIMESTAMP);
        FirebaseUtils.db()
            .getReference("statusArchive")
            .child(ownerUid)
            .child(key)
            .setValue(data);
    }
    public static void unarchiveStatus(String ownerUid, String statusId) {
        if (ownerUid == null || statusId == null) return;
        FirebaseUtils.db()
            .getReference("statusArchive")
            .child(ownerUid)
            .child(statusId)
            .removeValue();
    }
    public static DatabaseReference getArchiveRef(String ownerUid) {
        return FirebaseUtils.db()
            .getReference("statusArchive")
            .child(ownerUid);
    }
}