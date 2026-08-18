package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;

import java.util.HashMap;
import java.util.Map;

/**
 * ChatPrivacyManager — Per-chat privacy controls.
 *
 * Stores settings per chatId/groupId in a dedicated SharedPreferences file.
 * Covers:
 *   1. Disappearing messages (24h / 7d / 30d)     → messages auto-delete after set time
 *   2. Message self-destruct timer (per message)   → view-once / timed delete after send
 *   3. Auto-delete old messages                    → purge messages older than N days
 *
 * Firebase path  (1:1):  chatPrivacy/{chatId}/{uid}/
 * Firebase path (group): groups/{groupId}/privacy/
 */
public class ChatPrivacyManager {

    // ── Disappearing messages ─────────────────────────────────────────────
    public static final long DISAPPEAR_OFF  = 0L;
    public static final long DISAPPEAR_24H  = 24 * 3_600_000L;
    public static final long DISAPPEAR_7D   = 7  * 24 * 3_600_000L;
    public static final long DISAPPEAR_30D  = 30 * 24 * 3_600_000L;

    // ── Message timer (self-destruct) ────────────────────────────────────
    public static final long MSG_TIMER_OFF  = 0L;
    public static final long MSG_TIMER_10S  = 10_000L;
    public static final long MSG_TIMER_30S  = 30_000L;
    public static final long MSG_TIMER_1M   = 60_000L;
    public static final long MSG_TIMER_5M   = 5 * 60_000L;
    public static final long MSG_TIMER_1H   = 3_600_000L;

    // ── Auto-delete old messages ─────────────────────────────────────────
    public static final long AUTO_DELETE_OFF  = 0L;
    public static final long AUTO_DELETE_7D   = 7L;
    public static final long AUTO_DELETE_30D  = 30L;
    public static final long AUTO_DELETE_90D  = 90L;
    public static final long AUTO_DELETE_180D = 180L;

    private static final String PREF_PREFIX   = "callx_chat_privacy_";
    private static final String KEY_DISAPPEAR  = "disappearing_ms";
    private static final String KEY_MSG_TIMER  = "msg_timer_ms";
    private static final String KEY_AUTO_DELETE = "auto_delete_days";

    private final SharedPreferences prefs;
    private final String            chatId;
    private final boolean           isGroup;

    // ─────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────

    public ChatPrivacyManager(@NonNull Context ctx, @NonNull String chatId, boolean isGroup) {
        this.chatId  = chatId;
        this.isGroup = isGroup;
        this.prefs   = ctx.getSharedPreferences(PREF_PREFIX + chatId, Context.MODE_PRIVATE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 1. Disappearing Messages
    // ─────────────────────────────────────────────────────────────────────

    public long getDisappearingMs() {
        return prefs.getLong(KEY_DISAPPEAR, DISAPPEAR_OFF);
    }

    public void setDisappearingMs(long ms) {
        prefs.edit().putLong(KEY_DISAPPEAR, ms).apply();
        pushToFirebase(KEY_DISAPPEAR, ms == 0 ? null : ms);
    }

    public @NonNull String getDisappearingLabel() {
        long ms = getDisappearingMs();
        if (ms == DISAPPEAR_OFF) return "Off";
        if (ms == DISAPPEAR_24H) return "24 hours";
        if (ms == DISAPPEAR_7D)  return "7 days";
        if (ms == DISAPPEAR_30D) return "30 days";
        return "Off";
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. Message Timer (self-destruct after recipient reads)
    // ─────────────────────────────────────────────────────────────────────

    public long getMsgTimerMs() {
        return prefs.getLong(KEY_MSG_TIMER, MSG_TIMER_OFF);
    }

    public void setMsgTimerMs(long ms) {
        prefs.edit().putLong(KEY_MSG_TIMER, ms).apply();
        pushToFirebase(KEY_MSG_TIMER, ms == 0 ? null : ms);
    }

    public @NonNull String getMsgTimerLabel() {
        long ms = getMsgTimerMs();
        if (ms == MSG_TIMER_OFF) return "Off";
        if (ms == MSG_TIMER_10S) return "10 seconds";
        if (ms == MSG_TIMER_30S) return "30 seconds";
        if (ms == MSG_TIMER_1M)  return "1 minute";
        if (ms == MSG_TIMER_5M)  return "5 minutes";
        if (ms == MSG_TIMER_1H)  return "1 hour";
        return "Off";
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. Auto-Delete Old Messages
    // ─────────────────────────────────────────────────────────────────────

    public long getAutoDeleteDays() {
        return prefs.getLong(KEY_AUTO_DELETE, AUTO_DELETE_OFF);
    }

    public void setAutoDeleteDays(long days) {
        prefs.edit().putLong(KEY_AUTO_DELETE, days).apply();
        pushToFirebase(KEY_AUTO_DELETE, days == 0 ? null : days);
    }

    public @NonNull String getAutoDeleteLabel() {
        long days = getAutoDeleteDays();
        if (days == AUTO_DELETE_OFF)  return "Never";
        if (days == AUTO_DELETE_7D)   return "After 7 days";
        if (days == AUTO_DELETE_30D)  return "After 30 days";
        if (days == AUTO_DELETE_90D)  return "After 90 days";
        if (days == AUTO_DELETE_180D) return "After 6 months";
        return "Never";
    }

    /**
     * Returns the cutoff timestamp (ms) before which messages should be purged.
     * Returns 0 if auto-delete is off.
     */
    public long getAutoDeleteCutoffMs() {
        long days = getAutoDeleteDays();
        if (days == AUTO_DELETE_OFF) return 0L;
        return System.currentTimeMillis() - (days * 24 * 3_600_000L);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Firebase sync
    // ─────────────────────────────────────────────────────────────────────

    private void pushToFirebase(String key, Object value) {
        DatabaseReference ref = getFirebaseRef();
        if (ref == null) return;
        ref.child(key).setValue(value);
    }

    public void syncAllToFirebase() {
        DatabaseReference ref = getFirebaseRef();
        if (ref == null) return;
        Map<String, Object> map = new HashMap<>();
        long disappear  = getDisappearingMs();
        long msgTimer   = getMsgTimerMs();
        long autoDelete = getAutoDeleteDays();
        map.put(KEY_DISAPPEAR,   disappear  == 0 ? null : disappear);
        map.put(KEY_MSG_TIMER,   msgTimer   == 0 ? null : msgTimer);
        map.put(KEY_AUTO_DELETE, autoDelete == 0 ? null : autoDelete);
        ref.updateChildren(map);
    }

    private DatabaseReference getFirebaseRef() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return null;
            if (isGroup) {
                return FirebaseUtils.getGroupsRef().child(chatId).child("privacy");
            } else {
                return FirebaseUtils.db().getReference("chatPrivacy")
                        .child(chatId).child(user.getUid());
            }
        } catch (Exception e) {
            return null;
        }
    }
}
