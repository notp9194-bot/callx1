package com.callx.app.utils;
import com.callx.app.models.StatusItem;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import androidx.annotation.NonNull;
import java.util.HashMap;
import java.util.Map;

/**
 * StatusHighlightManager — Add expired/active statuses to Highlights albums.
 * Firebase paths:
 *   statusHighlights/{ownerUid}/{albumId}/{statusId}   — per-album copy of the status (permanent)
 *   statusHighlightMeta/{ownerUid}/{albumId}           — album settings: name, cover
 *   statusArchive/{ownerUid}/{statusId}                — archive (unrelated feature)
 *
 * v39 — Instagram-style highlight system:
 *  - A status added to a highlight keeps living in the album forever, even after
 *    the original status/story expires (StatusFragment / StatusViewerActivity's
 *    live-feed loader filters by expiresAt, but nothing here ever does — this
 *    is what makes the album "permanent").
 *  - The ORIGINAL live status doc (status/{ownerUid}/{statusId}) is now also kept
 *    in sync (isHighlighted / highlightAlbumId / highlightAlbumIds) so the owner's
 *    delete-confirmation flow can correctly detect "this status is inside N
 *    highlight album(s)" and offer the "delete + remove from highlights too" choice.
 *  - Albums now support rename + custom cover + delete (statusHighlightMeta),
 *    closing the previously-missing "highlight editing & settings" gap.
 */
public final class StatusHighlightManager {
    private StatusHighlightManager() {}

    // ── Highlights: add / remove single item ────────────────────────────────
    public static void addToHighlight(String ownerUid, StatusItem item,
                                      String albumId, String albumName) {
        if (ownerUid == null || item == null || albumId == null) return;
        final String statusId = item.id != null ? item.id
                : FirebaseUtils.db().getReference().push().getKey();
        // 1) Write the permanent per-album copy.
        DatabaseReference albumItemRef = FirebaseUtils.db()
            .getReference("statusHighlights")
            .child(ownerUid)
            .child(albumId)
            .child(statusId);
        Map<String, Object> data = new HashMap<>(item.toMap());
        data.put("isHighlighted",     true);
        data.put("highlightAlbumId",  albumId);
        data.put("highlightAlbumName", albumName);
        Map<String, Object> albumIds = item.highlightAlbumIds != null
                ? new HashMap<>(item.highlightAlbumIds) : new HashMap<>();
        albumIds.put(albumId, true);
        data.put("highlightAlbumIds", albumIds);
        albumItemRef.setValue(data);
        // 2) Keep the ORIGINAL live status doc in sync so we can tell later
        //    (e.g. on delete) that this status belongs to highlight album(s).
        if (statusId != null) {
            Map<String, Object> originalUpdate = new HashMap<>();
            originalUpdate.put("isHighlighted", true);
            originalUpdate.put("highlightAlbumId", albumId);
            originalUpdate.put("highlightAlbumName", albumName);
            originalUpdate.put("highlightAlbumIds/" + albumId, true);
            FirebaseUtils.getStatusRef().child(ownerUid).child(statusId)
                .updateChildren(originalUpdate);
        }
        // 3) Ensure album meta exists (name at minimum), without clobbering
        //    an existing custom cover if one was already set.
        getAlbumMetaRef(ownerUid, albumId).child("name").setValue(albumName);
        getAlbumMetaRef(ownerUid, albumId).child("updatedAt").setValue(ServerValue.TIMESTAMP);
    }

    public static void removeFromHighlight(String ownerUid, String albumId, String statusId) {
        if (ownerUid == null || albumId == null || statusId == null) return;
        FirebaseUtils.db()
            .getReference("statusHighlights")
            .child(ownerUid)
            .child(albumId)
            .child(statusId)
            .removeValue();
        // Clean the membership flag on the original live status (if it still exists).
        DatabaseReference originalRef = FirebaseUtils.getStatusRef().child(ownerUid).child(statusId);
        originalRef.child("highlightAlbumIds").child(albumId).removeValue();
        // Recompute isHighlighted based on remaining memberships (best-effort).
        originalRef.child("highlightAlbumIds").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                if (!snap.exists() || snap.getChildrenCount() == 0) {
                    Map<String, Object> clear = new HashMap<>();
                    clear.put("isHighlighted", false);
                    originalRef.updateChildren(clear);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    /** Removes this status from EVERY highlight album it belongs to (used by
     *  the "Delete & remove from Highlights" option in the delete-confirm sheet). */
    public static void removeStatusFromAllHighlights(String ownerUid, StatusItem item) {
        if (ownerUid == null || item == null || item.id == null) return;
        if (item.highlightAlbumIds != null && !item.highlightAlbumIds.isEmpty()) {
            for (String albumId : item.highlightAlbumIds.keySet()) {
                removeFromHighlight(ownerUid, albumId, item.id);
            }
            return;
        }
        // Fallback: item wasn't carrying the map (e.g. loaded before sync existed) —
        // scan all albums once and remove any match.
        final String statusId = item.id;
        getHighlightsRef(ownerUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot albumSnap : snap.getChildren()) {
                    String albumId = albumSnap.getKey();
                    if (albumId != null && albumSnap.hasChild(statusId)) {
                        removeFromHighlight(ownerUid, albumId, statusId);
                    }
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
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

    // ── Album settings (rename / cover / delete) ────────────────────────────
    public static DatabaseReference getAlbumMetaRef(String ownerUid, String albumId) {
        return FirebaseUtils.db()
            .getReference("statusHighlightMeta")
            .child(ownerUid)
            .child(albumId);
    }

    /** Renames the album: updates the meta name + every item copy's highlightAlbumName
     *  (so the name stays correct wherever it's read from). */
    public static void renameAlbum(String ownerUid, String albumId, String newName) {
        if (ownerUid == null || albumId == null || newName == null || newName.trim().isEmpty()) return;
        final String name = newName.trim();
        getAlbumMetaRef(ownerUid, albumId).child("name").setValue(name);
        getAlbumRef(ownerUid, albumId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Map<String, Object> multiUpdate = new HashMap<>();
                for (DataSnapshot itemSnap : snap.getChildren()) {
                    String statusId = itemSnap.getKey();
                    if (statusId == null) continue;
                    multiUpdate.put(statusId + "/highlightAlbumName", name);
                    // Also fix up the original live doc if its "most recent album" pointer matches.
                    FirebaseUtils.getStatusRef().child(ownerUid).child(statusId)
                        .child("highlightAlbumId").addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(@NonNull DataSnapshot idSnap) {
                                if (albumId.equals(idSnap.getValue(String.class))) {
                                    FirebaseUtils.getStatusRef().child(ownerUid).child(statusId)
                                        .child("highlightAlbumName").setValue(name);
                                }
                            }
                            @Override public void onCancelled(@NonNull DatabaseError e) { }
                        });
                }
                if (!multiUpdate.isEmpty()) getAlbumRef(ownerUid, albumId).updateChildren(multiUpdate);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) { }
        });
    }

    /** Sets a specific status item (already inside the album) as the album cover. */
    public static void setAlbumCover(String ownerUid, String albumId, String coverStatusId, String coverUrl) {
        if (ownerUid == null || albumId == null) return;
        Map<String, Object> meta = new HashMap<>();
        meta.put("coverStatusId", coverStatusId);
        meta.put("coverUrl", coverUrl != null ? coverUrl : "");
        meta.put("updatedAt", ServerValue.TIMESTAMP);
        getAlbumMetaRef(ownerUid, albumId).updateChildren(meta);
    }

    /** Sets a custom ring color/mode for the album (used by the highlights row
     *  gradient ring in the profile). {@code mode} is either
     *  {@link com.callx.app.utils.HighlightRingDrawable#MODE_SOLID} or
     *  {@link com.callx.app.utils.HighlightRingDrawable#MODE_DOMINANT}. */
    public static void setAlbumRingStyle(String ownerUid, String albumId, String colorHex, String mode) {
        if (ownerUid == null || albumId == null || colorHex == null || mode == null) return;
        Map<String, Object> meta = new HashMap<>();
        meta.put("ringColor", colorHex);
        meta.put("ringMode", mode);
        meta.put("updatedAt", ServerValue.TIMESTAMP);
        getAlbumMetaRef(ownerUid, albumId).updateChildren(meta);
    }

    /** Resets the album back to the default app-wide multi-color ring. */
    public static void clearAlbumRingStyle(String ownerUid, String albumId) {
        if (ownerUid == null || albumId == null) return;
        getAlbumMetaRef(ownerUid, albumId).child("ringColor").removeValue();
        getAlbumMetaRef(ownerUid, albumId).child("ringMode").removeValue();
    }

    /** Deletes the whole album: clears membership flags on every original live status,
     *  then removes the album's items and its settings/meta. */
    public static void deleteAlbum(String ownerUid, String albumId) {
        if (ownerUid == null || albumId == null) return;
        getAlbumRef(ownerUid, albumId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                for (DataSnapshot itemSnap : snap.getChildren()) {
                    String statusId = itemSnap.getKey();
                    if (statusId == null) continue;
                    DatabaseReference originalRef = FirebaseUtils.getStatusRef()
                        .child(ownerUid).child(statusId);
                    originalRef.child("highlightAlbumIds").child(albumId).removeValue();
                }
                getAlbumRef(ownerUid, albumId).removeValue();
                getAlbumMetaRef(ownerUid, albumId).removeValue();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                getAlbumRef(ownerUid, albumId).removeValue();
                getAlbumMetaRef(ownerUid, albumId).removeValue();
            }
        });
    }

    // ── Seen tracking (Instagram-style gradient→gray ring) ──────────────────
    //
    // A highlight album is permanent, so seen-state is a flat "opened or
    // not" flag per (viewer, album) pair rather than a timestamp comparison
    // like regular 24h stories use. Firebase path:
    //   highlightSeen/{viewerUid}/{ownerUid}/{albumId}
    // Never applies to the owner viewing their own highlights — an owner's
    // own ring always stays in its normal gradient/custom color, never
    // grayed out, matching Instagram.

    /** Marks {@code albumId} (owned by {@code ownerUid}) as seen by
     *  {@code viewerUid}. No-op when the viewer is the owner. Also warms
     *  {@link com.callx.app.utils.HighlightSeenState}'s local cache (if
     *  {@code ctx} is non-null) so the ring flips to gray immediately,
     *  without waiting on this Firebase write to round-trip. */
    public static void markHighlightSeen(android.content.Context ctx, String viewerUid,
                                         String ownerUid, String albumId) {
        if (viewerUid == null || ownerUid == null || albumId == null) return;
        if (viewerUid.equals(ownerUid)) return;
        if (ctx != null) com.callx.app.utils.HighlightSeenState.markSeen(ctx, ownerUid, albumId);
        FirebaseUtils.db().getReference("highlightSeen")
            .child(viewerUid).child(ownerUid).child(albumId)
            .setValue(ServerValue.TIMESTAMP);
    }

    /** Ref to every album {@code viewerUid} has already seen for {@code ownerUid} —
     *  read once (addListenerForSingleValueEvent) and check snap.hasChild(albumId). */
    public static DatabaseReference getHighlightSeenRef(String viewerUid, String ownerUid) {
        return FirebaseUtils.db()
            .getReference("highlightSeen")
            .child(viewerUid)
            .child(ownerUid);
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
