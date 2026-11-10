package com.callx.app.social;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * v790 — What happens AFTER a Remix / Collab reel was saved (called from ReelUploadActivity):
 *  remix  → reelRemixes/{original}/{new} record, remixCount++, userRemixes index, in-app notification to the original creator
 *  collab → the original creator becomes an accepted co-creator (he already approved the request):
 *           collabMap + isCollabPost on the new reel, then the post is added to BOTH creators' Collabs / profile indexes
 */
public final class RemixPublishHelper {
    private RemixPublishHelper() {}

    public static void afterPublish(@NonNull String mode, @NonNull String newReelId,
                                    @NonNull String originalReelId, @NonNull String originalOwnerUid,
                                    @Nullable String originalOwnerName, @Nullable String originalThumb,
                                    @Nullable String originalVideoUrl, @Nullable String layoutMode,
                                    @NonNull String myUid, @Nullable String myName, @Nullable String myPhoto,
                                    @Nullable String newVideoUrl, @Nullable String newThumbUrl, @Nullable String caption) {
        if (originalReelId.isEmpty() || originalOwnerUid.isEmpty()) return;
        final String layout = layoutMode == null || layoutMode.isEmpty() ? "side_by_side" : layoutMode;

        if (RemixCollabTarget.MODE_COLLAB.equals(mode)) {
            publishCollab(newReelId, originalOwnerUid, originalOwnerName, myUid);
            return;
        }
        ReelRemixActivity.publishRemixToFirebase(
                originalReelId, newReelId, originalOwnerUid, originalOwnerName,
                originalThumb, originalVideoUrl, myUid, myName, myPhoto,
                newVideoUrl == null ? "" : newVideoUrl, newThumbUrl, caption, layout);
    }

    private static void publishCollab(@NonNull final String newReelId, @NonNull final String ownerUid,
                                      @Nullable final String ownerName, @NonNull final String myUid) {
        final long now = System.currentTimeMillis();
        final com.google.firebase.database.DatabaseReference root = FirebaseUtils.db().getReference();

        // 1) the new reel is mine → I may write its collabMap; the original creator is accepted from the start
        Map<String, Object> entry = new HashMap<>();
        entry.put("uid", ownerUid);
        entry.put("displayName", ownerName == null ? "" : ownerName);
        entry.put("handle", "");
        entry.put("avatarUrl", "");
        entry.put("status", "accepted");
        entry.put("invitedAt", now);
        entry.put("respondedAt", now);
        Map<String, Object> up = new HashMap<>();
        up.put("reels/" + newReelId + "/collabMap/" + ownerUid, entry);
        up.put("reels/" + newReelId + "/isCollabPost", true);
        up.put("reels/" + newReelId + "/isCollabPending", false);
        up.put("reels/" + newReelId + "/collabUid", ownerUid);
        up.put("reels/" + newReelId + "/collabDisplayName", ownerName == null ? "" : ownerName);
        up.put("reels/" + newReelId + "/collabAcceptedAt", now);
        up.put("userCollabPosts/" + myUid + "/" + newReelId, now);
        root.updateChildren(up).addOnCompleteListener(t -> {
            if (!t.isSuccessful()) return;
            // 2) second write (rules read the state BEFORE a write): show the post on the co-creator's profile / feed too
            Map<String, Object> fan = new HashMap<>();
            fan.put("userCollabPosts/" + ownerUid + "/" + newReelId, now);
            fan.put("reelsByUser/" + ownerUid + "/" + newReelId, true);
            fan.put("userReels/" + ownerUid + "/" + newReelId, true);
            // a staged "invite more collaborators" write on the same reel may have set isCollabPost=false a moment
            // earlier — the original creator already accepted, so the post is a live collab.
            fan.put("reels/" + newReelId + "/isCollabPost", true);
            root.updateChildren(fan);
        });
    }
}
