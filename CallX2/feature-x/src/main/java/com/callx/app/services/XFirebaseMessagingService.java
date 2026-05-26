package com.callx.app.services;

import android.util.Log;
import androidx.annotation.NonNull;
import com.callx.app.notifications.XNotificationChannelManager;
import com.callx.app.notifications.XFCMNotificationHandler;
import com.callx.app.utils.XFirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.HashMap;
import java.util.Map;

/**
 * XFirebaseMessagingService — DEPRECATED.
 *
 * Background/killed state delivery iske through nahi hoti thi kyunki:
 *   1. Android me ek app ka sirf EK FirebaseMessagingService active hota hai.
 *      CallxMessagingService pehle se manifest me registered tha, isliye yeh
 *      service kabhi bhi FCM messages receive nahi kar pa rahi thi.
 *   2. Bina Executor wrap ke avatar download FCM wakelock expire hone se fail hota.
 *
 * Fix:
 *   - Routing ab CallxMessagingService → XFCMNotificationHandler ke through hoti hai.
 *   - Server payload me "x_notif_type" key bhejo (pehle "type" tha).
 *   - XFCMNotificationHandler har type ko Executor me wrap karta hai — killed state safe.
 *
 * Yeh stub sirf manifest/build errors rokne ke liye rakha hai.
 * Agar manifest me registered hai to hata do.
 *
 * Token upload logic: CallxApp.java ya AuthActivity me
 * XFirebaseMessagingService.uploadTokenIfSignedIn() call karo —
 * woh kaam karta rehega.
 */
public class XFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "XFCMService";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        // Deprecated — messages ab CallxMessagingService → XFCMNotificationHandler se jaate hain.
        // Agar koi purana "type" payload aa raha ho to yahan se bhi route kar do
        // taaki transition period me koi notification miss na ho.
        Map<String, String> data = message.getData();
        if (!data.isEmpty() && data.containsKey("type")
                && !data.containsKey("x_notif_type")) {
            // Purana payload format — "type" ko "x_notif_type" me convert karke handle karo
            Map<String, String> mapped = new HashMap<>(data);
            mapped.put("x_notif_type", data.get("type"));
            XNotificationChannelManager.ensureChannels(this);
            XFCMNotificationHandler.handle(this, mapped);
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        Log.d(TAG, "FCM token refreshed");
        uploadTokenToFirebase(token);
    }

    /** App startup me call karo — token Firebase me upload karta hai. */
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
                .addOnFailureListener(e -> Log.w(TAG, "FCM token get failed", e));
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
