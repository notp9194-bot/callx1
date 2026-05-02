package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.callx.app.models.Message;
import com.google.firebase.database.DatabaseReference;

import java.util.HashMap;
import java.util.Map;

/**
 * Feature 8: Disappearing Messages
 *
 * - Per-chat timer: 24h, 7d, 90d, or custom seconds.
 * - When a message is sent, its expiresAt = timestamp + timerMs.
 * - A background Handler checks every minute for expired messages and deletes them.
 * - Timer setting is stored per chatId in SharedPreferences AND in Firebase
 *   (chat metadata) so both participants share the same timer.
 */
public class DisappearingMessageManager {

    private static final String TAG   = "DisappearMsg";
    private static final String PREFS = "disappear_prefs";

    public static final long TIMER_OFF  = 0L;
    public static final long TIMER_24H  = 24L * 3600 * 1000;
    public static final long TIMER_7D   = 7L  * 86400 * 1000;
    public static final long TIMER_90D  = 90L * 86400 * 1000;

    private static DisappearingMessageManager instance;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // chatId → list of (msgId, DatabaseRef) scheduled for deletion
    private final Map<String, Runnable> scheduled = new HashMap<>();

    private DisappearingMessageManager(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized DisappearingMessageManager getInstance(Context ctx) {
        if (instance == null) instance = new DisappearingMessageManager(ctx);
        return instance;
    }

    // ── Timer settings ─────────────────────────────────────────────────────

    public void setTimer(String chatId, long durationMs) {
        prefs.edit().putLong(chatId, durationMs).apply();
    }

    public long getTimer(String chatId) {
        return prefs.getLong(chatId, TIMER_OFF);
    }

    public boolean isActive(String chatId) {
        return getTimer(chatId) > 0;
    }

    /** Label shown in UI (e.g. "24 hours", "7 days") */
    public static String timerLabel(long durationMs) {
        if (durationMs == TIMER_OFF)  return "Off";
        if (durationMs == TIMER_24H)  return "24 hours";
        if (durationMs == TIMER_7D)   return "7 days";
        if (durationMs == TIMER_90D)  return "90 days";
        long secs = durationMs / 1000;
        if (secs < 3600) return (secs / 60) + " minutes";
        if (secs < 86400) return (secs / 3600) + " hours";
        return (secs / 86400) + " days";
    }

    // ── Schedule deletion ──────────────────────────────────────────────────

    /**
     * Schedule Firebase deletion of a message at its expiresAt time.
     * Safe to call multiple times — old runnable is replaced.
     */
    public void scheduleDelete(Message msg, DatabaseReference msgRef) {
        if (msg.expiresAt == null || msg.expiresAt <= 0) return;
        long delay = msg.expiresAt - System.currentTimeMillis();
        if (delay <= 0) {
            // Already expired — delete immediately
            msgRef.removeValue()
                  .addOnFailureListener(e -> Log.e(TAG, "Delete failed: " + msg.id, e));
            return;
        }
        cancelScheduled(msg.id);
        Runnable r = () -> {
            msgRef.removeValue()
                  .addOnFailureListener(e -> Log.e(TAG, "Scheduled delete failed: " + msg.id, e));
            scheduled.remove(msg.id);
        };
        handler.postDelayed(r, delay);
        scheduled.put(msg.id, r);
        Log.d(TAG, "Scheduled delete for " + msg.id + " in " + delay + "ms");
    }

    public void cancelScheduled(String msgId) {
        Runnable r = scheduled.remove(msgId);
        if (r != null) handler.removeCallbacks(r);
    }

    public void cancelAll() {
        for (Runnable r : scheduled.values()) handler.removeCallbacks(r);
        scheduled.clear();
    }

    /** Apply timer to a freshly built message before sending. */
    public void applyTimer(String chatId, Message msg) {
        long timer = getTimer(chatId);
        if (timer > 0) msg.expiresAt = System.currentTimeMillis() + timer;
    }
}
