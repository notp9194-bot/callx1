// ═══════════════════════════════════════════════════════════════════════════
// PushNotify — Fix 11: notifyDuetInvite() method
// ═══════════════════════════════════════════════════════════════════════════
//
// Add this static method to your existing PushNotify.java class.
// Called by DuetInviteActivity when a creator invites someone to duet.
//
// Firebase node written: duetInvites/{targetUid}/{fromUid}_{reelId}
// FCM payload: TYPE_DUET_INVITE → opens DuetReelActivity for that reel
// ═══════════════════════════════════════════════════════════════════════════

package com.callx.app.workers;

/**
 * PATCH FILE — contains the notifyDuetInvite() method to add to PushNotify.java
 *
 * Add these imports to PushNotify.java if not already present:
 *   import java.util.HashMap;
 *   import java.util.Map;
 *   import com.callx.app.utils.FirebaseUtils;
 */
public class PushNotify_DuetInvite_Patch {

    // ── Paste this method into PushNotify.java ────────────────────────────────

    /**
     * Fix 11: Send Duet Invite push notification + in-app notification.
     *
     * @param targetUid   UID of the invited user
     * @param fromUid     UID of the reel creator sending the invite
     * @param fromName    display name of the creator
     * @param fromPhoto   avatar URL of the creator
     * @param reelId      the reel being offered for duet
     * @param videoUrl    URL of that reel (so notification can show preview)
     */
    public static void notifyDuetInvite(String targetUid, String fromUid,
                                         String fromName, String fromPhoto,
                                         String reelId,   String videoUrl) {

        if (targetUid == null || targetUid.isEmpty()) return;
        if (fromUid   == null || fromUid.isEmpty())   return;
        if (reelId    == null || reelId.isEmpty())    return;

        long now = System.currentTimeMillis();

        // ── 1. In-app notification entry ─────────────────────────────────────
        Map<String, Object> notif = new HashMap<>();
        notif.put("type",      "duet_invite");
        notif.put("fromUid",   fromUid);
        notif.put("fromName",  fromName  != null ? fromName  : "Someone");
        notif.put("fromPhoto", fromPhoto != null ? fromPhoto : "");
        notif.put("reelId",    reelId);
        notif.put("videoUrl",  videoUrl  != null ? videoUrl  : "");
        notif.put("message",   fromName + " invited you to duet their reel");
        notif.put("read",      false);
        notif.put("timestamp", now);

        String notifKey = fromUid + "_" + reelId + "_invite";
        FirebaseUtils.db()
            .getReference("notifications")
            .child(targetUid)
            .child(notifKey)
            .setValue(notif);

        // ── 2. FCM push notification via server ──────────────────────────────
        // Assumes your server at /notify/duet_invite accepts:
        //   { targetUid, fromUid, fromName, fromPhoto, reelId, videoUrl }
        // and sends FCM with data.type = "TYPE_DUET_INVITE"
        // The CallxMessagingService handles TYPE_DUET_INVITE → open DuetReelActivity
        Map<String, Object> payload = new HashMap<>();
        payload.put("targetUid", targetUid);
        payload.put("fromUid",   fromUid);
        payload.put("fromName",  fromName  != null ? fromName  : "Someone");
        payload.put("fromPhoto", fromPhoto != null ? fromPhoto : "");
        payload.put("reelId",    reelId);
        payload.put("videoUrl",  videoUrl  != null ? videoUrl  : "");
        payload.put("timestamp", now);

        // Store pending FCM payload under server queue path
        // (your server's FCM dispatcher reads this node)
        FirebaseUtils.db()
            .getReference("fcmQueue")
            .push()
            .setValue(payload);
    }
}
