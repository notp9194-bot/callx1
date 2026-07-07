package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * PendingAckQueue — local durable queue for message-status upgrades
 * (delivered/read) that couldn't be confirmed immediately (offline,
 * transient Firebase error, app killed mid-write).
 *
 * Entries are tiny strings "chatId|msgId|status" stored in SharedPreferences
 * (survives process death, unlike an in-memory list). On reconnect
 * (GlobalDeliveryAckManager's ".info/connected" listener) or app foreground,
 * retryAll() re-attempts every queued upgrade via MessageStatusSync's
 * transaction — which is itself idempotent, so retrying something that
 * actually already committed is always safe (no-op).
 */
public final class PendingAckQueue {

    private static final String PREFS = "pending_ack_queue";
    private static final String KEY_ENTRIES = "entries";
    private static final String TAG = "PendingAckQueue";

    private PendingAckQueue() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Queue an upgrade so it survives a crash/offline gap until confirmed. */
    public static void enqueue(Context ctx, String chatId, String msgId, String status) {
        try {
            SharedPreferences p = prefs(ctx);
            Set<String> current = new HashSet<>(p.getStringSet(KEY_ENTRIES, new HashSet<>()));
            current.add(chatId + "|" + msgId + "|" + status);
            p.edit().putStringSet(KEY_ENTRIES, current).apply();
        } catch (Exception e) {
            Log.w(TAG, "enqueue failed: " + e.getMessage());
        }
    }

    /** Remove an entry once its transaction has actually committed. */
    public static void dequeue(Context ctx, String chatId, String msgId, String status) {
        try {
            SharedPreferences p = prefs(ctx);
            Set<String> current = new HashSet<>(p.getStringSet(KEY_ENTRIES, new HashSet<>()));
            if (current.remove(chatId + "|" + msgId + "|" + status)) {
                p.edit().putStringSet(KEY_ENTRIES, current).apply();
            }
        } catch (Exception e) {
            Log.w(TAG, "dequeue failed: " + e.getMessage());
        }
    }

    /**
     * Re-attempt every queued upgrade. Called when Firebase's ".info/connected"
     * flips back to true, and once on app foreground as a belt-and-suspenders pass.
     */
    public static void retryAll(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            Set<String> current = new HashSet<>(p.getStringSet(KEY_ENTRIES, new HashSet<>()));
            if (current.isEmpty()) return;
            for (String entry : current) {
                String[] parts = entry.split("\\|", 3);
                if (parts.length != 3) continue;
                String chatId = parts[0], msgId = parts[1], status = parts[2];
                MessageStatusSync.upgradeStatus(
                        ctx, FirebaseUtils.getMessagesRef(chatId), chatId, msgId, status);
            }
        } catch (Exception e) {
            Log.w(TAG, "retryAll failed: " + e.getMessage());
        }
    }
}
