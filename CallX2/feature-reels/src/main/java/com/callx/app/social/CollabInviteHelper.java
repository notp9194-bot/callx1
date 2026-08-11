package com.callx.app.social;

import android.content.Context;

import com.callx.app.notifications.CollabRepostNotificationHelper;
import com.callx.app.utils.Constants;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ✅ MULTI-COLLABORATOR SUPPORT — upload-time invites.
 *
 * Mirrors the Firebase-writing logic in {@link CollabPostInviteActivity#sendInvites()},
 * but for the case where collaborators were picked on the upload/post-details screen
 * (via {@link CollabPostInviteActivity} in staging mode) BEFORE the reel existed. Call
 * this once the reel has just been saved and its reelId is known, right after the
 * "reel posted" success callback in ReelUploadActivity.
 */
public final class CollabInviteHelper {

    private CollabInviteHelper() {}

    public interface Callback {
        void onDone();
    }

    /** One staged collaborator picked on the upload screen. */
    public static class StagedCollaborator {
        public final String uid, displayName, handle, avatarUrl;
        public StagedCollaborator(String uid, String displayName, String handle, String avatarUrl) {
            this.uid = uid;
            this.displayName = displayName != null ? displayName : "";
            this.handle = handle != null ? handle : "";
            this.avatarUrl = avatarUrl != null ? avatarUrl : "";
        }
    }

    /** Builds the staged list from the parallel ArrayLists returned by CollabPostInviteActivity. */
    public static List<StagedCollaborator> fromParallelLists(
            List<String> uids, List<String> names, List<String> handles, List<String> avatars) {
        List<StagedCollaborator> out = new ArrayList<>();
        if (uids == null) return out;
        for (int i = 0; i < uids.size(); i++) {
            out.add(new StagedCollaborator(
                uids.get(i),
                names   != null && i < names.size()   ? names.get(i)   : "",
                handles != null && i < handles.size() ? handles.get(i) : "",
                avatars != null && i < avatars.size() ? avatars.get(i) : ""
            ));
        }
        return out;
    }

    /** Sends one invite per staged collaborator and seeds the new reel's collabMap. */
    public static void sendInvitesForNewReel(Context ctx, String reelId, String thumbUrl,
                                              List<StagedCollaborator> collaborators,
                                              String myUid, String myName, Callback callback) {
        if (collaborators == null || collaborators.isEmpty() || reelId == null || reelId.isEmpty()) {
            if (callback != null) callback.onDone();
            return;
        }

        DatabaseReference root = FirebaseDatabase.getInstance(Constants.DB_URL).getReference();
        Map<String, Object> updates = new HashMap<>();
        long now = System.currentTimeMillis();
        List<String> inviteIds = new ArrayList<>();

        for (StagedCollaborator target : collaborators) {
            String inviteId = root.child("collabPostInvites").push().getKey();
            if (inviteId == null) continue;
            inviteIds.add(inviteId);

            Map<String, Object> invite = new HashMap<>();
            invite.put("inviteId",        inviteId);
            invite.put("reelId",          reelId);
            invite.put("initiatorUid",    myUid);
            invite.put("initiatorName",   myName != null ? myName : "");
            invite.put("collaboratorUid", target.uid);
            invite.put("thumbUrl",        thumbUrl != null ? thumbUrl : "");
            invite.put("status",          "pending");
            invite.put("createdAt",       now);

            updates.put("collabPostInvites/" + target.uid + "/" + inviteId, invite);
            updates.put("collabPostInvitesSent/" + myUid + "/" + inviteId, invite);

            Map<String, Object> collabEntry = new HashMap<>();
            collabEntry.put("uid",         target.uid);
            collabEntry.put("displayName", target.displayName);
            collabEntry.put("handle",      target.handle);
            collabEntry.put("avatarUrl",   target.avatarUrl);
            collabEntry.put("status",      "pending");
            collabEntry.put("inviteId",    inviteId);
            collabEntry.put("invitedAt",   now);
            updates.put("reels/" + reelId + "/collabMap/" + target.uid, collabEntry);
        }

        // Reel-level flags + legacy single-collaborator mirror (first invitee),
        // same convention as CollabPostInviteActivity.sendInvites().
        StagedCollaborator first = collaborators.get(0);
        updates.put("reels/" + reelId + "/isCollabPending",   true);
        updates.put("reels/" + reelId + "/isCollabPost",      false);
        updates.put("reels/" + reelId + "/collabInviteId",    inviteIds.isEmpty() ? "" : inviteIds.get(0));
        updates.put("reels/" + reelId + "/collabUid",         first.uid);
        updates.put("reels/" + reelId + "/collabDisplayName", first.displayName);
        updates.put("reels/" + reelId + "/collabAvatarUrl",   first.avatarUrl);

        root.updateChildren(updates, (error, ref) -> {
            if (error == null) {
                for (int i = 0; i < collaborators.size() && i < inviteIds.size(); i++) {
                    StagedCollaborator target = collaborators.get(i);
                    try {
                        CollabRepostNotificationHelper.notifyCollabInvite(
                            ctx, target.uid, myUid, myName != null ? myName : "Someone",
                            reelId, inviteIds.get(i), thumbUrl != null ? thumbUrl : "");
                    } catch (Exception ignored) {}
                }
            }
            if (callback != null) callback.onDone();
        });
    }
}
