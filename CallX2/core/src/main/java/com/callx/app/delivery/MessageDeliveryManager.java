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
 * MessageDeliveryManager — Production-grade delivery status manager.
 *
 * Responsibilities:
 *   1. markDelivered() — call karo jab message pehli baar device pe aaye
 *      (FCM background ya onChildAdded foreground dono mein)
 *   2. markSeen()     — call karo jab chat screen open ho aur message dikh jaaye
 *   3. writeSentAt()  — call karo jab sender message push kare
 *
 * Rules:
 *   - Sirf receiver likhega deliveredAt aur seenAt (never sender)
 *   - Sirf sender likhega sentAt
 *   - Ek baar seen ho gaya toh dobara delivered mat likho
 *   - Atomic updateChildren — partial writes nahi
 *   - Background thread pe Firebase write — UI block nahi hoga
 *   - Idempotent — baar baar call karo, Firebase mein sirf zaroori update jayega
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

    // ──────────────────────────────────────────────────────────────────────
    // PUBLIC API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * markDelivered — jab message device pe pahunche (FCM ya Firebase listener).
     *
     * Sirf receiver call kare. Sender ka message toh "sent" status se start karta hai.
     * Agar already "seen" hai toh yeh call no-op hoga.
     *
     * @param chatId     Firebase path ke liye
     * @param messageId  message ka key
     * @param currentStatus current status Firebase mein (agar pata ho)
     * @param currentDeliveredAt existing deliveredAt value (0 agar pata nahi)
     */
    public void markDelivered(String chatId, String messageId,
                              String currentStatus, long currentDeliveredAt) {
        if (chatId == null || chatId.isEmpty()) return;
        if (messageId == null || messageId.isEmpty()) return;

        // Already seen — downgrade mat karo
        if ("seen".equals(currentStatus)) return;
        // Already delivered with timestamp — idempotent
        if ("delivered".equals(currentStatus) && currentDeliveredAt > 0) return;

        executor.execute(() -> {
            long nowMs = System.currentTimeMillis();
            Map<String, Object> updates = new HashMap<>();
            updates.put("status",      "delivered");
            updates.put("deliveredAt", nowMs);

            FirebaseUtils.getMessagesRef(chatId)
                .child(messageId)
                .updateChildren(updates)
                .addOnSuccessListener(v ->
                    Log.d(TAG, "deliveredAt written: " + messageId))
                .addOnFailureListener(e ->
                    Log.w(TAG, "deliveredAt write failed: " + e.getMessage()));
        });
    }

    /**
     * markSeen — jab receiver chat screen pe message dekhe.
     *
     * Atomic write: status="seen" + seenAt + deliveredAt (agar missing tha).
     * Idempotent — already seen messages pe no-op.
     *
     * @param chatId      Firebase path
     * @param messageId   message key
     * @param deliveredAt existing deliveredAt (0 agar nahi tha — tab bhi set karega)
     * @param seenAt      existing seenAt (0 agar nahi tha)
     */
    public void markSeen(String chatId, String messageId,
                         long deliveredAt, long seenAt) {
        if (chatId == null || chatId.isEmpty()) return;
        if (messageId == null || messageId.isEmpty()) return;

        // Already fully seen — no-op
        if (seenAt > 0) return;

        executor.execute(() -> {
            long nowMs = System.currentTimeMillis();
            Map<String, Object> updates = new HashMap<>();
            updates.put("status",  "seen");
            updates.put("seenAt",  nowMs);
            // deliveredAt bhi set karo agar missing tha
            if (deliveredAt <= 0) {
                updates.put("deliveredAt", nowMs);
            }

            FirebaseUtils.getMessagesRef(chatId)
                .child(messageId)
                .updateChildren(updates)
                .addOnSuccessListener(v ->
                    Log.d(TAG, "seenAt written: " + messageId))
                .addOnFailureListener(e ->
                    Log.w(TAG, "seenAt write failed — will retry on next open: " + e.getMessage()));
        });
    }

    /**
     * writeSentAt — sirf sender call kare jab message push kare.
     * sentAt = server timestamp ke badle local timestamp (offline mein bhi kaam kare).
     */
    public void writeSentAt(String chatId, String messageId, long sentAt) {
        if (chatId == null || chatId.isEmpty()) return;
        if (messageId == null || messageId.isEmpty()) return;

        executor.execute(() ->
            FirebaseUtils.getMessagesRef(chatId)
                .child(messageId)
                .child("sentAt")
                .setValue(sentAt)
        );
    }

    /**
     * markDeliveredFromFCM — FCM background/killed state mein call karo.
     *
     * App foreground mein nahi hai — sirf delivery mark karo, seen nahi.
     * Firebase read nahi karta (zero latency) — seedha write.
     */
    public void markDeliveredFromFCM(String chatId, String messageId) {
        if (chatId == null || chatId.isEmpty()) return;
        if (messageId == null || messageId.isEmpty()) return;

        executor.execute(() -> {
            long nowMs = System.currentTimeMillis();
            Map<String, Object> updates = new HashMap<>();
            updates.put("status",      "delivered");
            updates.put("deliveredAt", nowMs);

            // Conditional write — agar already "seen" hai toh overwrite mat karo
            // Firebase Security Rules se bhi enforce hona chahiye production mein
            DatabaseReference ref = FirebaseUtils.getMessagesRef(chatId).child(messageId);
            ref.child("status").addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    String existingStatus = snapshot.getValue(String.class);
                    if ("seen".equals(existingStatus)) {
                        Log.d(TAG, "FCM: already seen, skip delivered write: " + messageId);
                        return;
                    }
                    ref.updateChildren(updates)
                        .addOnSuccessListener(v -> Log.d(TAG, "FCM deliveredAt: " + messageId))
                        .addOnFailureListener(e -> Log.w(TAG, "FCM delivered fail: " + e.getMessage()));
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    // Offline — retry on next open via onChildAdded
                    Log.w(TAG, "FCM delivered check cancelled: " + e.getMessage());
                }
            });
        });
    }
}
