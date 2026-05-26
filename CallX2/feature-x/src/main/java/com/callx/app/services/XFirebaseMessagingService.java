package com.callx.app.services;

import android.util.Log;
import androidx.annotation.NonNull;
import com.callx.app.notifications.XNotificationChannelManager;
import com.callx.app.notifications.XNotificationHelper;
import com.callx.app.utils.XFirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.HashMap;
import java.util.Map;

/**
 * X Module — Firebase Cloud Messaging Service.
 *
 * Receives push notifications in both foreground and background.
 * Token is stored at x/fcm_tokens/{uid} so Cloud Functions or server
 * can send targeted messages to specific users.
 *
 * Data payload keys (sent from server/Cloud Functions):
 *   type        : "like" | "retweet" | "reply" | "mention" | "quote" | "follow" | "dm"
 *   fromUid     : sender UID
 *   fromName    : sender display name
 *   fromPhoto   : sender photo URL
 *   tweetId     : target tweet ID (null for "follow" / "dm")
 *   conversationId : DM conversation ID (only for "dm" type)
 *   otherUid    : DM other user UID
 *   otherHandle : DM other user handle
 *   otherPhoto  : DM other user photo
 *   preview     : DM message preview text
 */
public class XFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "XFCMService";

    /** Called when app receives an FCM message (foreground and data-only background). */
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        XNotificationChannelManager.ensureChannels(this);

        Map<String, String> data = message.getData();
        if (data.isEmpty()) return;

        String type     = data.get("type");
        String fromName = data.get("fromName");
        String fromPhoto= data.get("fromPhoto");
        String tweetId  = data.get("tweetId");

        if (fromName == null || fromName.isEmpty()) fromName = "Someone";

        if ("dm".equals(type)) {
            XNotificationHelper.postDM(this,
                fromName, fromPhoto,
                data.get("conversationId"),
                data.get("otherUid"),
                data.get("otherHandle"),
                data.get("otherPhoto"),
                data.get("preview"));
        } else {
            XNotificationHelper.postTweetInteraction(this, type, fromName, fromPhoto, tweetId);
        }
    }

    /**
     * Called when FCM token is refreshed.
     * Uploads new token to Firebase so server always has an up-to-date token.
     */
    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "FCM token refreshed");
        uploadTokenToFirebase(token);
    }

    /** Upload or refresh token to Firebase — call this once at app startup too. */
    public static void uploadTokenIfSignedIn() {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null) return;

        FirebaseMessaging.getInstance().getToken()
            .addOnSuccessListener(token -> {
                if (token == null || token.isEmpty()) return;
                Map<String, Object> payload = new HashMap<>();
                payload.put("token",     token);
                payload.put("updatedAt", System.currentTimeMillis());
                payload.put("platform",  "android");
                XFirebaseUtils.fcmTokenRef(uid).setValue(payload);
            })
            .addOnFailureListener(e -> Log.w(TAG, "Failed to get FCM token", e));
    }

    private void uploadTokenToFirebase(String token) {
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid == null) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("token",     token);
        payload.put("updatedAt", System.currentTimeMillis());
        payload.put("platform",  "android");
        XFirebaseUtils.fcmTokenRef(uid).setValue(payload);
    }
}
