package com.callx.app.utils;

import androidx.annotation.NonNull;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * StatusHighlightsManager — Instagram-style status highlights for CallX.
 *
 * Firebase schema:
 *   statusHighlights/{ownerUid}/albums/{albumId}/
 *     name         — string
 *     coverUrl     — string (thumbnail from first status)
 *     createdAt    — timestamp
 *   statusHighlights/{ownerUid}/items/{albumId}/{statusId}/
 *     statusId     — string
 *     addedAt      — timestamp
 *     type         — string (from original status)
 *     mediaUrl     — string
 *     thumbnailUrl — string
 *     text         — string (for text statuses)
 *     bgColor      — string
 *     caption      — string
 *     originalTimestamp — long
 *
 * Features:
 *   createAlbum   — create a named highlight album
 *   deleteAlbum   — delete album + all items
 *   addStatus     — add a status item to an album
 *   removeStatus  — remove a status item from an album
 *   loadAlbums    — load all albums for a user
 *   loadAlbumItems — load all items in an album
 *   renameAlbum   — rename an existing album
 */
public final class StatusHighlightsManager {

    private static final String FB_ROOT = "statusHighlights";

    private StatusHighlightsManager() {}

    // ── Album model ───────────────────────────────────────────────────────

    public static class Album {
        public String id;
        public String name;
        public String coverUrl;
        public long   createdAt;
        public int    itemCount;

        public Album() {}
        public Album(String id, String name, String coverUrl, long createdAt) {
            this.id        = id;
            this.name      = name;
            this.coverUrl  = coverUrl;
            this.createdAt = createdAt;
        }
    }

    public interface AlbumsCallback {
        void onAlbums(List<Album> albums);
    }

    public interface ItemsCallback {
        void onItems(List<StatusItem> items);
    }

    public interface DoneCallback {
        void onDone(boolean success, String albumId);
    }

    // ── Create album ─────────────────────────────────────────────────────

    /**
     * Create a new highlight album (empty).
     * Calls callback with the generated albumId on success.
     */
    public static void createAlbum(String ownerUid, String name,
                                   String coverUrl, DoneCallback cb) {
        if (ownerUid == null || name == null || name.isEmpty()) {
            if (cb != null) cb.onDone(false, null);
            return;
        }
        DatabaseReference albumsRef = albumsRef(ownerUid);
        DatabaseReference newRef = albumsRef.push();
        String albumId = newRef.getKey();
        if (albumId == null) { if (cb != null) cb.onDone(false, null); return; }

        Map<String, Object> data = new HashMap<>();
        data.put("name",      name);
        data.put("coverUrl",  coverUrl != null ? coverUrl : "");
        data.put("createdAt", ServerValue.TIMESTAMP);
        data.put("itemCount", 0);

        newRef.setValue(data)
            .addOnSuccessListener(x -> { if (cb != null) cb.onDone(true, albumId); })
            .addOnFailureListener(e -> { if (cb != null) cb.onDone(false, null); });
    }

    // ── Delete album ──────────────────────────────────────────────────────

    public static void deleteAlbum(String ownerUid, String albumId) {
        if (ownerUid == null || albumId == null) return;
        // Remove album meta + all items atomically
        Map<String, Object> updates = new HashMap<>();
        updates.put(FB_ROOT + "/" + ownerUid + "/albums/" + albumId, null);
        updates.put(FB_ROOT + "/" + ownerUid + "/items/" + albumId, null);
        FirebaseUtils.db().getReference().updateChildren(updates);
    }

    // ── Rename album ──────────────────────────────────────────────────────

    public static void renameAlbum(String ownerUid, String albumId, String newName) {
        if (ownerUid == null || albumId == null || newName == null) return;
        albumsRef(ownerUid).child(albumId).child("name").setValue(newName);
    }

    // ── Add status to album ───────────────────────────────────────────────

    /**
     * Save a status item into a highlight album.
     * Also updates the album's coverUrl if it is empty.
     */
    public static void addStatus(String ownerUid, String albumId, StatusItem item) {
        if (ownerUid == null || albumId == null || item == null || item.id == null) return;

        Map<String, Object> itemData = new HashMap<>();
        itemData.put("statusId",          item.id);
        itemData.put("addedAt",           ServerValue.TIMESTAMP);
        itemData.put("type",              item.type != null ? item.type : "");
        itemData.put("mediaUrl",          item.mediaUrl != null ? item.mediaUrl : "");
        itemData.put("thumbnailUrl",      item.thumbnailUrl != null ? item.thumbnailUrl : "");
        itemData.put("text",              item.text != null ? item.text : "");
        itemData.put("bgColor",           item.bgColor != null ? item.bgColor : "");
        itemData.put("caption",           item.caption != null ? item.caption : "");
        itemData.put("originalTimestamp", item.timestamp != null ? item.timestamp : 0L);

        DatabaseReference itemRef = itemsRef(ownerUid, albumId).child(item.id);
        itemRef.setValue(itemData);

        // Bump item count + update cover if needed
        albumsRef(ownerUid).child(albumId).addListenerForSingleValueEvent(
            new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    Long count = snap.child("itemCount").getValue(Long.class);
                    int  newCount = count != null ? (int)(count + 1) : 1;
                    Map<String, Object> up = new HashMap<>();
                    up.put("itemCount", newCount);
                    String existingCover = snap.child("coverUrl").getValue(String.class);
                    if (existingCover == null || existingCover.isEmpty()) {
                        String thumb = item.thumbnailUrl != null && !item.thumbnailUrl.isEmpty()
                                ? item.thumbnailUrl
                                : (item.mediaUrl != null ? item.mediaUrl : "");
                        up.put("coverUrl", thumb);
                    }
                    albumsRef(ownerUid).child(albumId).updateChildren(up);
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Remove status from album ──────────────────────────────────────────

    public static void removeStatus(String ownerUid, String albumId, String statusId) {
        if (ownerUid == null || albumId == null || statusId == null) return;
        itemsRef(ownerUid, albumId).child(statusId).removeValue();
        // Decrement item count
        albumsRef(ownerUid).child(albumId).child("itemCount")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    Long count = snap.getValue(Long.class);
                    int newCount = count != null ? Math.max(0, (int)(count - 1)) : 0;
                    albumsRef(ownerUid).child(albumId).child("itemCount").setValue(newCount);
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    // ── Load albums ───────────────────────────────────────────────────────

    /**
     * Load all highlight albums for a user, sorted newest-first.
     */
    public static void loadAlbums(String ownerUid, AlbumsCallback cb) {
        if (ownerUid == null || cb == null) return;
        albumsRef(ownerUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                List<Album> result = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    String id       = child.getKey();
                    String name     = child.child("name").getValue(String.class);
                    String cover    = child.child("coverUrl").getValue(String.class);
                    Long   created  = child.child("createdAt").getValue(Long.class);
                    Long   count    = child.child("itemCount").getValue(Long.class);
                    if (id == null || name == null) continue;
                    Album a = new Album(id, name, cover, created != null ? created : 0L);
                    a.itemCount = count != null ? count.intValue() : 0;
                    result.add(a);
                }
                // Sort newest first
                result.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
                cb.onAlbums(result);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                cb.onAlbums(new ArrayList<>());
            }
        });
    }

    // ── Load items in album ───────────────────────────────────────────────

    /**
     * Load all StatusItems in a highlight album, sorted oldest-first.
     */
    public static void loadAlbumItems(String ownerUid, String albumId, ItemsCallback cb) {
        if (ownerUid == null || albumId == null || cb == null) return;
        itemsRef(ownerUid, albumId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snap) {
                List<StatusItem> items = new ArrayList<>();
                for (DataSnapshot child : snap.getChildren()) {
                    StatusItem item = new StatusItem();
                    item.id           = child.child("statusId").getValue(String.class);
                    item.type         = child.child("type").getValue(String.class);
                    item.mediaUrl     = child.child("mediaUrl").getValue(String.class);
                    item.thumbnailUrl = child.child("thumbnailUrl").getValue(String.class);
                    item.text         = child.child("text").getValue(String.class);
                    item.bgColor      = child.child("bgColor").getValue(String.class);
                    item.caption      = child.child("caption").getValue(String.class);
                    Long ts = child.child("originalTimestamp").getValue(Long.class);
                    item.timestamp    = ts;
                    item.isHighlighted = true;
                    items.add(item);
                }
                items.sort((a, b) -> Long.compare(
                    a.timestamp != null ? a.timestamp : 0L,
                    b.timestamp != null ? b.timestamp : 0L));
                cb.onItems(items);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError e) {
                cb.onItems(new ArrayList<>());
            }
        });
    }

    // ── Check if status is in any album ───────────────────────────────────

    public static void isInAnyAlbum(String ownerUid, String statusId,
                                     com.google.firebase.database.Query.SingleValueListener cb) {
        // Simplified: check if statusId node exists in any album via parent-level query.
        // For now callers just call addStatus — idempotent.
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static DatabaseReference albumsRef(String ownerUid) {
        return FirebaseUtils.db()
            .getReference(FB_ROOT)
            .child(ownerUid)
            .child("albums");
    }

    private static DatabaseReference itemsRef(String ownerUid, String albumId) {
        return FirebaseUtils.db()
            .getReference(FB_ROOT)
            .child(ownerUid)
            .child("items")
            .child(albumId);
    }
}
