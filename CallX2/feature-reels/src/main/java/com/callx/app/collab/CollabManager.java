package com.callx.app.collab;

import com.callx.app.models.CollabModel;
import com.callx.app.models.CollabSeriesModel;
import com.google.firebase.database.*;
import java.util.*;

/**
 * CollabManager — Instagram-style Joint Post system.
 * Owner invites another creator; when accepted, reel shows both handles,
 * appears in both profiles, both get analytics.
 */
public class CollabManager {

    public interface CollabCallback { void onDone(boolean ok, String error); }
    public interface CollabListCallback { void onList(List<CollabModel> list); }
    public interface CollabStatusCallback { void onStatus(String status, CollabModel model); }

    private static final String NODE_COLLABS          = "reelCollabs";
    private static final String NODE_COLLAB_PENDING   = "collabPending";
    private static final String NODE_COLLAB_SERIES    = "collabSeries";
    private static final String NODE_REELS            = "reels";
    private static final String NODE_NOTIF            = "reel_notifications";

    private final DatabaseReference db;
    private final String myUid, myName, myPhoto;

    public CollabManager(String myUid, String myName, String myPhoto) {
        this.db      = FirebaseDatabase.getInstance().getReference();
        this.myUid   = myUid;
        this.myName  = myName;
        this.myPhoto = myPhoto;
    }

    // ── Send collab invite ────────────────────────────────────────────────────
    public void sendCollabInvite(String reelId, String inviteeUid,
                                  String inviteeName, String inviteePhoto,
                                  CollabCallback cb) {
        String key = db.child(NODE_COLLABS).child(reelId).push().getKey();
        if (key == null) { cb.onDone(false, "DB error"); return; }

        CollabModel model = new CollabModel(reelId, myUid, myName, myPhoto,
                                            inviteeUid, inviteeName, inviteePhoto);
        model.collabId = key;

        Map<String, Object> updates = new HashMap<>();
        updates.put(NODE_COLLABS + "/" + reelId + "/" + key, modelToMap(model));
        // Pending inbox for invitee
        updates.put(NODE_COLLAB_PENDING + "/" + inviteeUid + "/" + key, modelToMap(model));

        db.updateChildren(updates)
          .addOnSuccessListener(a -> {
              sendCollabNotification(key, reelId, inviteeUid, "collab_invite");
              cb.onDone(true, null);
          })
          .addOnFailureListener(e -> cb.onDone(false, e.getMessage()));
    }

    // ── Accept collab invite ──────────────────────────────────────────────────
    public void acceptCollab(String collabId, String reelId, String ownerUid, CollabCallback cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(NODE_COLLABS + "/" + reelId + "/" + collabId + "/status", CollabModel.STATUS_ACCEPTED);
        updates.put(NODE_COLLABS + "/" + reelId + "/" + collabId + "/respondedAt",
                    System.currentTimeMillis());
        updates.put(NODE_COLLAB_PENDING + "/" + myUid + "/" + collabId, null);
        // Add invitee to reel's collaborators list
        updates.put(NODE_REELS + "/" + reelId + "/collaboratorUids/" + myUid, myName);
        updates.put(NODE_REELS + "/" + reelId + "/collaboratorPhotos/" + myUid, myPhoto);
        // Reel appears in invitee's profile grid
        updates.put("userCollabReels/" + myUid + "/" + reelId, System.currentTimeMillis());

        db.updateChildren(updates)
          .addOnSuccessListener(a -> {
              sendCollabNotification(collabId, reelId, ownerUid, "collab_accepted");
              cb.onDone(true, null);
          })
          .addOnFailureListener(e -> cb.onDone(false, e.getMessage()));
    }

    // ── Reject collab invite ──────────────────────────────────────────────────
    public void rejectCollab(String collabId, String reelId, String ownerUid, CollabCallback cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(NODE_COLLABS + "/" + reelId + "/" + collabId + "/status", CollabModel.STATUS_REJECTED);
        updates.put(NODE_COLLABS + "/" + reelId + "/" + collabId + "/respondedAt",
                    System.currentTimeMillis());
        updates.put(NODE_COLLAB_PENDING + "/" + myUid + "/" + collabId, null);

        db.updateChildren(updates)
          .addOnSuccessListener(a -> {
              sendCollabNotification(collabId, reelId, ownerUid, "collab_rejected");
              cb.onDone(true, null);
          })
          .addOnFailureListener(e -> cb.onDone(false, e.getMessage()));
    }

    // ── Cancel collab invite (by owner) ──────────────────────────────────────
    public void cancelCollab(String collabId, String reelId, String inviteeUid, CollabCallback cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(NODE_COLLABS + "/" + reelId + "/" + collabId + "/status", CollabModel.STATUS_CANCELLED);
        updates.put(NODE_COLLAB_PENDING + "/" + inviteeUid + "/" + collabId, null);

        db.updateChildren(updates)
          .addOnSuccessListener(a -> cb.onDone(true, null))
          .addOnFailureListener(e -> cb.onDone(false, e.getMessage()));
    }

    // ── Remove co-author (after publish) ─────────────────────────────────────
    public void removeCoAuthor(String reelId, String coAuthorUid, CollabCallback cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(NODE_REELS + "/" + reelId + "/collaboratorUids/" + coAuthorUid, null);
        updates.put(NODE_REELS + "/" + reelId + "/collaboratorPhotos/" + coAuthorUid, null);
        updates.put("userCollabReels/" + coAuthorUid + "/" + reelId, null);

        db.updateChildren(updates)
          .addOnSuccessListener(a -> cb.onDone(true, null))
          .addOnFailureListener(e -> cb.onDone(false, e.getMessage()));
    }

    // ── Get pending collabs (invites for me) ──────────────────────────────────
    public void getPendingCollabs(CollabListCallback cb) {
        db.child(NODE_COLLAB_PENDING).child(myUid)
          .orderByChild("invitedAt")
          .get().addOnSuccessListener(snap -> {
            List<CollabModel> list = new ArrayList<>();
            for (DataSnapshot c : snap.getChildren()) {
                CollabModel m = c.getValue(CollabModel.class);
                if (m != null && CollabModel.STATUS_PENDING.equals(m.status)) list.add(0, m);
            }
            cb.onList(list);
        });
    }

    // ── Create collab series/playlist ─────────────────────────────────────────
    public void createCollabSeries(String title, String desc, CollabCallback cb) {
        String key = db.child(NODE_COLLAB_SERIES).push().getKey();
        if (key == null) { cb.onDone(false, "DB error"); return; }

        CollabSeriesModel series = new CollabSeriesModel(title, desc, myUid, myName);
        series.seriesId = key;

        Map<String, Object> map = new HashMap<>();
        map.put("seriesId", key);
        map.put("title", title);
        map.put("description", desc);
        map.put("creatorUid", myUid);
        map.put("creatorName", myName);
        map.put("isPublic", true);
        map.put("reelCount", 0);
        map.put("createdAt", System.currentTimeMillis());

        db.child(NODE_COLLAB_SERIES).child(key).updateChildren(map)
          .addOnSuccessListener(a -> cb.onDone(true, null))
          .addOnFailureListener(e -> cb.onDone(false, e.getMessage()));
    }

    // ── Add reel to series ────────────────────────────────────────────────────
    public void addReelToSeries(String seriesId, String reelId, CollabCallback cb) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(NODE_COLLAB_SERIES + "/" + seriesId + "/reels/" + reelId,
                    System.currentTimeMillis());
        updates.put(NODE_COLLAB_SERIES + "/" + seriesId + "/reelCount",
                    ServerValue.increment(1));
        updates.put(NODE_REELS + "/" + reelId + "/seriesId", seriesId);

        db.updateChildren(updates)
          .addOnSuccessListener(a -> cb.onDone(true, null))
          .addOnFailureListener(e -> cb.onDone(false, e.getMessage()));
    }

    // ── Invite collaborator to series ─────────────────────────────────────────
    public void inviteToSeries(String seriesId, String inviteeUid, CollabCallback cb) {
        db.child(NODE_COLLAB_SERIES).child(seriesId).child("collaborators").child(inviteeUid)
          .setValue("invited")
          .addOnSuccessListener(a -> cb.onDone(true, null))
          .addOnFailureListener(e -> cb.onDone(false, e.getMessage()));
    }

    // ── Send collab FCM notification ──────────────────────────────────────────
    private void sendCollabNotification(String collabId, String reelId,
                                         String targetUid, String type) {
        String notifKey = db.child(NODE_NOTIF).child(targetUid).push().getKey();
        if (notifKey == null) return;
        Map<String, Object> n = new HashMap<>();
        n.put("type",       type);
        n.put("senderUid",  myUid);
        n.put("senderName", myName);
        n.put("senderPhoto",myPhoto);
        n.put("reel_id",    reelId);
        n.put("collab_id",  collabId);
        n.put("timestamp",  System.currentTimeMillis());
        n.put("read",       false);
        db.child(NODE_NOTIF).child(targetUid).child(notifKey).updateChildren(n);
    }

    private Map<String, Object> modelToMap(CollabModel m) {
        Map<String, Object> map = new HashMap<>();
        map.put("collabId",     m.collabId);
        map.put("reelId",       m.reelId);
        map.put("ownerUid",     m.ownerUid);
        map.put("ownerName",    m.ownerName);
        map.put("ownerPhoto",   m.ownerPhoto);
        map.put("inviteeUid",   m.inviteeUid);
        map.put("inviteeName",  m.inviteeName);
        map.put("inviteePhoto", m.inviteePhoto);
        map.put("status",       m.status);
        map.put("invitedAt",    m.invitedAt);
        return map;
    }

    public String myUid()   { return myUid; }
    public String myName()  { return myName; }
    public String myPhoto() { return myPhoto; }
}
