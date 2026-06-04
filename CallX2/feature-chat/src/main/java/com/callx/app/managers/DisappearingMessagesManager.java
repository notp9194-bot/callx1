package com.callx.app.managers;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

import androidx.annotation.NonNull;

/**
 * DisappearingMessagesManager
 *
 * Manages disappearing message timers for a chat.
 *
 * How it works:
 *   1. One user sets a timer (e.g. 24h) via DisappearingTimerDialog
 *   2. Manager writes timer to Firebase: chats/{chatId}/disappearingTimer = 86400000
 *   3. Both users' apps listen for this value and display hourglass header
 *   4. When a message is DELIVERED (status = "read"), a countdown starts locally
 *      via Handler.postDelayed → deletes message from local Room DB
 *   5. Firebase Cloud Function (disappearingMessageCleaner) runs every 5 min
 *      and hard-deletes expired messages server-side for guarantee
 *      (in case app was closed before local deletion ran)
 *
 * Timer options (milliseconds):
 *   OFF    = 0
 *   5s     = 5_000          (testing only)
 *   30s    = 30_000
 *   1 min  = 60_000
 *   1 hr   = 3_600_000
 *   24 hr  = 86_400_000     (most common — "Stories" style)
 *   7 days = 604_800_000    (WhatsApp default)
 *
 * Usage:
 *   manager = new DisappearingMessagesManager(chatId);
 *   manager.listen(duration -> updateHeaderBadge(duration));
 *   manager.setTimer(DisappearingMessagesManager.DURATION_24H);
 *   manager.scheduleDelete(messageId, deliveredTimestamp);
 *   manager.stopListening();
 */
public class DisappearingMessagesManager {

    // ── Timer constants (ms) ───────────────────────────────────────────────
    public static final long DURATION_OFF     = 0L;
    public static final long DURATION_5S      = 5_000L;
    public static final long DURATION_30S     = 30_000L;
    public static final long DURATION_1MIN    = 60_000L;
    public static final long DURATION_1HR     = 3_600_000L;
    public static final long DURATION_24HR    = 86_400_000L;
    public static final long DURATION_7DAYS   = 604_800_000L;

    // ── Firebase paths ─────────────────────────────────────────────────────
    private static final String PATH_CHATS    = "chats";
    private static final String PATH_MESSAGES = "messages";
    private static final String KEY_TIMER     = "disappearingTimer";
    private static final String KEY_EXPIRES   = "expiresAt";
    private static final String KEY_DELETED   = "deleted";

    // ── State ──────────────────────────────────────────────────────────────
    private final String            chatId;
    private final DatabaseReference chatRef;
    private final Handler           handler = new Handler(Looper.getMainLooper());

    // Active local deletion tasks: messageId → Runnable
    private final Map<String, Runnable> pendingDeletes = new HashMap<>();

    // Firebase listener (to stop when activity pauses)
    private ValueEventListener timerListener;

    // Current timer in this chat
    private long currentDurationMs = DURATION_OFF;

    public interface OnTimerChanged { void onChanged(long durationMs); }

    // ─────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────

    public DisappearingMessagesManager(String chatId) {
        this.chatId  = chatId;
        this.chatRef = FirebaseDatabase.getInstance()
                .getReference(PATH_CHATS).child(chatId);
    }

    // ─────────────────────────────────────────────────────────────────────
    // LISTEN FOR TIMER CHANGES (from other user or self on another device)
    // ─────────────────────────────────────────────────────────────────────

    public void listen(OnTimerChanged callback) {
        timerListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Long val = snapshot.child(KEY_TIMER).getValue(Long.class);
                currentDurationMs = (val != null) ? val : DURATION_OFF;
                if (callback != null) callback.onChanged(currentDurationMs);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {}
        };
        chatRef.addValueEventListener(timerListener);
    }

    public void stopListening() {
        if (timerListener != null) {
            chatRef.removeEventListener(timerListener);
            timerListener = null;
        }
        cancelAllPendingDeletes();
    }

    // ─────────────────────────────────────────────────────────────────────
    // SET TIMER (called when user picks a duration from dialog)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Write the timer to Firebase. Both participants' apps will pick it up via listen().
     * Also writes a system message: "Messages in this chat now disappear after 24 hours"
     */
    public void setTimer(long durationMs) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(KEY_TIMER, durationMs == DURATION_OFF ? null : durationMs);
        chatRef.updateChildren(updates);

        // Write system message so both users see the change
        writeSystemMessage(durationMs);
    }

    private void writeSystemMessage(long durationMs) {
        String text = durationMs == DURATION_OFF
                ? "Disappearing messages turned off"
                : "⏳ Messages now disappear after " + formatDuration(durationMs);

        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "system";

        Map<String, Object> msg = new HashMap<>();
        msg.put("type",        "system");
        msg.put("text",        text);
        msg.put("timestamp",   System.currentTimeMillis());
        msg.put("senderId",    uid);
        msg.put("status",      "read");

        chatRef.child(PATH_MESSAGES).push().setValue(msg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // SCHEDULE LOCAL DELETION (called when message is delivered/read)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Schedule local deletion of a message after currentDurationMs.
     *
     * @param messageId        Firebase message ID
     * @param deliveredAt      Timestamp (ms) when message was marked "read"
     * @param onDeleteLocally  Callback to delete from local Room DB and RecyclerView
     */
    public void scheduleDelete(String messageId, long deliveredAt,
                               Runnable onDeleteLocally) {
        if (currentDurationMs == DURATION_OFF) return;

        long now       = System.currentTimeMillis();
        long expiresAt = deliveredAt + currentDurationMs;
        long delay     = expiresAt - now;

        if (delay <= 0) {
            // Already expired — delete immediately
            deleteFromFirebase(messageId);
            if (onDeleteLocally != null) handler.post(onDeleteLocally);
            return;
        }

        // Cancel any existing schedule for this message
        cancelDelete(messageId);

        Runnable task = () -> {
            deleteFromFirebase(messageId);
            if (onDeleteLocally != null) onDeleteLocally.run();
            pendingDeletes.remove(messageId);
        };
        pendingDeletes.put(messageId, task);
        handler.postDelayed(task, delay);

        // Also write expiresAt to Firebase so Cloud Function can clean up
        chatRef.child(PATH_MESSAGES).child(messageId)
                .child(KEY_EXPIRES).setValue(expiresAt);
    }

    /**
     * Call this when app resumes — re-schedule any messages that expired while app was closed.
     * Room DB gives us the message list; we check expiresAt for each.
     */
    public void rescheduleOnResume(java.util.List<com.callx.app.models.Message> messages,
                                   java.util.function.Consumer<String> onExpired) {
        if (currentDurationMs == DURATION_OFF || messages == null) return;

        long now = System.currentTimeMillis();
        for (com.callx.app.models.Message msg : messages) {
            if (msg.expiresAt > 0) {
                long delay = msg.expiresAt - now;
                if (delay <= 0) {
                    // Expired while app was closed
                    deleteFromFirebase(msg.messageId);
                    if (onExpired != null) onExpired.accept(msg.messageId);
                } else {
                    scheduleDelete(msg.messageId, msg.expiresAt - currentDurationMs,
                            () -> { if (onExpired != null) onExpired.accept(msg.messageId); });
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // FIREBASE DELETION
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Mark message as deleted in Firebase.
     * Cloud Function will also catch any that are missed (e.g. app offline).
     *
     * We do NOT remove the node — instead we tombstone it:
     *   { deleted: true, text: null, imageUrl: null, ... }
     * This keeps message ID in place so reply chains don't break.
     */
    private void deleteFromFirebase(String messageId) {
        Map<String, Object> tombstone = new HashMap<>();
        tombstone.put(KEY_DELETED,  true);
        tombstone.put("text",       null);
        tombstone.put("imageUrl",   null);
        tombstone.put("imageUrls",  null);
        tombstone.put("mediaUrl",   null);
        tombstone.put("thumbnailUrl", null);
        chatRef.child(PATH_MESSAGES).child(messageId).updateChildren(tombstone);
    }

    // ─────────────────────────────────────────────────────────────────────
    // CANCEL DELETES
    // ─────────────────────────────────────────────────────────────────────

    public void cancelDelete(String messageId) {
        Runnable task = pendingDeletes.remove(messageId);
        if (task != null) handler.removeCallbacks(task);
    }

    public void cancelAllPendingDeletes() {
        for (Runnable r : pendingDeletes.values()) handler.removeCallbacks(r);
        pendingDeletes.clear();
    }

    // ─────────────────────────────────────────────────────────────────────
    // GETTERS
    // ─────────────────────────────────────────────────────────────────────

    public long getCurrentDuration() { return currentDurationMs; }
    public boolean isActive()        { return currentDurationMs > DURATION_OFF; }

    // ─────────────────────────────────────────────────────────────────────
    // FORMAT HELPER
    // ─────────────────────────────────────────────────────────────────────

    public static String formatDuration(long ms) {
        if (ms == DURATION_OFF)   return "Off";
        if (ms == DURATION_5S)    return "5 seconds";
        if (ms == DURATION_30S)   return "30 seconds";
        if (ms == DURATION_1MIN)  return "1 minute";
        if (ms == DURATION_1HR)   return "1 hour";
        if (ms == DURATION_24HR)  return "24 hours";
        if (ms == DURATION_7DAYS) return "7 days";
        // Fallback for custom values
        if (ms < 60_000)          return (ms / 1000) + " seconds";
        if (ms < 3_600_000)       return (ms / 60_000) + " minutes";
        if (ms < 86_400_000)      return (ms / 3_600_000) + " hours";
        return (ms / 86_400_000) + " days";
    }

    public static String formatDurationShort(long ms) {
        if (ms == DURATION_OFF)   return "";
        if (ms < 60_000)          return (ms / 1000) + "s";
        if (ms < 3_600_000)       return (ms / 60_000) + "m";
        if (ms < 86_400_000)      return (ms / 3_600_000) + "h";
        return (ms / 86_400_000) + "d";
    }
}
