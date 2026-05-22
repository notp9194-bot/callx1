package com.callx.app.delivery;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MessageDeliveryManager — Production delivery status manager.
 *
 * State machine (WhatsApp-identical):
 *
 *   sent → delivered → seen
 *
 *   - sent:      Sender ne Firebase pe push kiya
 *   - delivered: Receiver ka device ne FCM receive kiya (app open/closed dono)
 *   - seen:      Receiver ne chat screen kholi ya notification se "Mark as read" kiya
 *
 * Rules:
 *   1. Sirf receiver likhega deliveredAt + seenAt
 *   2. Sirf sender likhega sentAt
 *   3. seen → delivered downgrade kabhi nahi
 *   4. Atomic Firebase updateChildren — partial writes nahi
 *   5. Idempotent — baar baar call safe hai
 */
public class MessageDeliveryManager {

    private static final String TAG = "DeliveryMgr";
    private static volatile MessageDeliveryManager sInstance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private MessageDeliveryManager() {}

    public static MessageDeliveryManager get() {
        if (sInstance == null) {
            synchronized (MessageDeliveryManager.class) {
                if (sInstance == null) sInstance = new MessageDeliveryManager();
            }
        }
        return sInstance;
    }

    // ── 1. FCM receive = delivered (background / killed / foreground) ─────
    public void markDeliveredFromFCM(Context ctx, String chatId, String messageId) {
        if (empty(chatId) || empty(messageId)) return;
        executor.execute(() -> {
            DatabaseReference ref = FirebaseUtils.getMessagesRef(chatId).child(messageId);
            // Read current status first — never downgrade from "seen"
            ref.child("status").addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    String existing = snap.getValue(String.class);
                    if ("seen".equals(existing)) {
                        Log.d(TAG, "FCM: already seen, skip: " + messageId);
                        return;
                    }
                    long nowMs = System.currentTimeMillis();
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("status",      "delivered");
                    upd.put("deliveredAt", nowMs);
                    ref.updateChildren(upd)
                       .addOnSuccessListener(v -> {
                           Log.d(TAG, "FCM deliveredAt OK: " + messageId);
                           // Room update
                           if (ctx != null) {
                               try {
                                   com.callx.app.db.AppDatabase.getInstance(ctx)
                                       .messageDao()
                                       .updateStatusDelivered(messageId, "delivered", nowMs);
                               } catch (Exception e) {
                                   Log.w(TAG, "Room delivered update: " + e.getMessage());
                               }
                           }
                       })
                       .addOnFailureListener(e -> Log.w(TAG, "FCM delivered fail: " + e.getMessage()));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    Log.w(TAG, "FCM status check cancelled: " + e.getMessage());
                }
            });
        });
    }

    // ── 2. Chat screen open = seen ────────────────────────────────────────
    public void markSeen(Context ctx, String chatId, String messageId,
                         long existingDeliveredAt, long existingSeenAt) {
        if (empty(chatId) || empty(messageId)) return;
        // Already seen — idempotent
        if (existingSeenAt > 0) return;

        executor.execute(() -> {
            long nowMs = System.currentTimeMillis();
            Map<String, Object> upd = new HashMap<>();
            upd.put("status",  "seen");
            upd.put("seenAt",  nowMs);
            // deliveredAt bhi set karo agar FCM miss ho gaya tha
            if (existingDeliveredAt <= 0) upd.put("deliveredAt", nowMs);

            FirebaseUtils.getMessagesRef(chatId).child(messageId)
                .updateChildren(upd)
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "seenAt OK: " + messageId);
                    if (ctx != null) {
                        try {
                            com.callx.app.db.AppDatabase.getInstance(ctx)
                                .messageDao()
                                .updateStatusSeen(messageId, "seen", nowMs);
                        } catch (Exception e) {
                            Log.w(TAG, "Room seen update: " + e.getMessage());
                        }
                    }
                })
                .addOnFailureListener(e -> Log.w(TAG, "seenAt fail: " + e.getMessage()));
        });
    }

    // ── 3. Notification "Mark as read" = seen (killed/background) ────────
    public void markSeenFromNotification(Context ctx, String chatId, String messageId) {
        if (empty(chatId) || empty(messageId)) return;
        // Same as markSeen but without existing timestamps check (we don't have them)
        executor.execute(() -> {
            long nowMs = System.currentTimeMillis();
            Map<String, Object> upd = new HashMap<>();
            upd.put("status",      "seen");
            upd.put("seenAt",      nowMs);
            upd.put("deliveredAt", nowMs); // set both in case FCM was missed

            FirebaseUtils.getMessagesRef(chatId).child(messageId)
                .updateChildren(upd)
                .addOnSuccessListener(v -> {
                    Log.d(TAG, "Notif markSeen OK: " + messageId);
                    if (ctx != null) {
                        try {
                            com.callx.app.db.AppDatabase.getInstance(ctx)
                                .messageDao()
                                .updateStatusSeen(messageId, "seen", nowMs);
                        } catch (Exception e) {
                            Log.w(TAG, "Room notif seen: " + e.getMessage());
                        }
                    }
                })
                .addOnFailureListener(e -> Log.w(TAG, "Notif seen fail: " + e.getMessage()));
        });
    }

    // ── 4. Sender push = sentAt ───────────────────────────────────────────
    public void writeSentAt(String chatId, String messageId, long sentAt) {
        if (empty(chatId) || empty(messageId)) return;
        executor.execute(() ->
            FirebaseUtils.getMessagesRef(chatId).child(messageId)
                .child("sentAt").setValue(sentAt)
        );
    }

    private static boolean empty(String s) {
        return s == null || s.isEmpty();
    }
}
