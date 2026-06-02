package com.callx.app.duet;

import android.content.Context;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;

import java.util.HashMap;
import java.util.Map;

/**
 * DuetNotificationHelper — Sends FCM push + in-app notification to the
 * original reel creator when someone duets their reel.
 *
 * FCM payload is queued into:
 *   reels/fcm_queue/{ownerUid}/{pushId} → { type, fromName, originalReelId, duetReelId }
 *
 * A Cloud Function (or your existing FCM sender) picks this up and sends the push.
 */
public class DuetNotificationHelper {

    private static final String TAG      = "DuetNotifHelper";
    private static final String ROOT     = "reels";
    private static final String FCM_NODE = "fcm_queue";

    /**
     * Queue a "someone dueted your reel" FCM notification for the original creator.
     *
     * @param context        application context
     * @param ownerUid       UID of the original reel creator
     * @param originalReelId ID of the original reel
     * @param duetReelId     ID of the new duet reel
     * @param dueterName     display name of the user who made the duet
     * @param dueterPhotoUrl profile photo URL of the dueter
     */
    public static void queueDuetNotification(
            Context context,
            String ownerUid,
            String originalReelId,
            String duetReelId,
            String dueterName,
            String dueterPhotoUrl) {

        FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        if (me == null) return;
        if (ownerUid == null || ownerUid.equals(me.getUid())) return; // don't notify yourself

        try {
            String pushId = FirebaseDatabase.getInstance()
                .getReference(ROOT).child(FCM_NODE).child(ownerUid).push().getKey();
            if (pushId == null) return;

            Map<String, Object> payload = new HashMap<>();
            payload.put("type",            "duet");
            payload.put("fromUid",         me.getUid());
            payload.put("fromName",        dueterName != null ? dueterName : "Someone");
            payload.put("fromPhoto",       dueterPhotoUrl != null ? dueterPhotoUrl : "");
            payload.put("originalReelId",  originalReelId);
            payload.put("duetReelId",      duetReelId);
            payload.put("title",           dueterName + " dueted your reel 🎶");
            payload.put("body",            "Tap to watch the duet");
            payload.put("timestamp",       ServerValue.TIMESTAMP);
            payload.put("sent",            false);

            FirebaseDatabase.getInstance()
                .getReference(ROOT).child(FCM_NODE).child(ownerUid).child(pushId)
                .setValue(payload)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to queue duet notif: " + e.getMessage()));
        } catch (Exception e) {
            Log.e(TAG, "queueDuetNotification error", e);
        }
    }
}
