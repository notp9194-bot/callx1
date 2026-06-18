package com.callx.app.utils;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.callx.app.models.StatusItem;
import com.callx.app.receivers.StatusExpiryReceiver;
import com.google.firebase.database.*;
import java.util.concurrent.TimeUnit;

/**
 * StatusExpiryManager — Handles:
 *  1. Scheduling local AlarmManager reminder 2h before a status expires
 *  2. Hard-deleting expired statuses from Firebase (server-side preferred,
 *     client fallback on next StatusFragment load)
 *  3. Moving expired statuses to archive node
 *  4. Extending a status TTL by another 24h
 *
 * Server-side cleanup is the canonical approach (Cloud Scheduler → /status/cleanup).
 * This class is the client-side safety net.
 */
public class StatusExpiryManager {

    private static final long REMINDER_BEFORE_MS = TimeUnit.HOURS.toMillis(2);
    private static final long DEFAULT_TTL_MS      = TimeUnit.HOURS.toMillis(24);

    // ── Schedule expiry reminder (call after posting a new status) ────────
    public static void scheduleExpiryReminder(Context ctx, StatusItem item) {
        if (item == null || item.statusId == null) return;
        long fireAt = item.expiresAt - REMINDER_BEFORE_MS;
        if (fireAt <= System.currentTimeMillis()) return; // already past

        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        Intent i = new Intent(ctx, StatusExpiryReceiver.class);
        i.setAction("com.callx.app.ACTION_STATUS_EXPIRY_REMINDER");
        i.putExtra("statusId",   item.statusId);
        i.putExtra("ownerUid",   item.ownerUid);
        i.putExtra("statusText", item.text != null ? item.text : "");
        i.putExtra("statusType", item.type != null ? item.type : "text");

        PendingIntent pi = PendingIntent.getBroadcast(ctx,
            item.statusId.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, fireAt, pi);
            }
        } catch (SecurityException ignored) {
            am.set(AlarmManager.RTC_WAKEUP, fireAt, pi);
        }
    }

    /** Cancel a previously scheduled reminder (e.g., user deleted status early) */
    public static void cancelExpiryReminder(Context ctx, String statusId) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, StatusExpiryReceiver.class);
        i.setAction("com.callx.app.ACTION_STATUS_EXPIRY_REMINDER");
        PendingIntent pi = PendingIntent.getBroadcast(ctx, statusId.hashCode(), i,
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) am.cancel(pi);
    }

    // ── Archive an expired status (move to statusArchive/{ownerUid}/{statusId}) ──
    public static void archiveExpiredStatus(String ownerUid, String statusId) {
        DatabaseReference src = FirebaseUtils.getUserStatusRef(ownerUid).child(statusId);
        src.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                if (!snap.exists()) return;
                Object data = snap.getValue();
                if (data == null) return;
                // Write to archive node
                FirebaseDatabase.getInstance().getReference()
                    .child("statusArchive").child(ownerUid).child(statusId)
                    .setValue(data, (error, ref) -> {
                        if (error == null) {
                            // Mark original as archived (don't delete — keep for highlights)
                            src.child("archived").setValue(true);
                            src.child("deleted").setValue(true);
                        }
                    });
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    // ── Extend TTL by 24h ─────────────────────────────────────────────────
    public static void extendStatusTtl(String ownerUid, String statusId, Context ctx) {
        DatabaseReference ref = FirebaseUtils.getUserStatusRef(ownerUid).child(statusId);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Long currentExpiry = snap.child("expiresAt").getValue(Long.class);
                long now = System.currentTimeMillis();
                long newExpiry = Math.max(now, currentExpiry != null ? currentExpiry : now)
                    + DEFAULT_TTL_MS;
                ref.child("expiresAt").setValue(newExpiry);
                // Re-schedule reminder
                StatusItem dummy = new StatusItem();
                dummy.statusId = statusId;
                dummy.ownerUid = ownerUid;
                dummy.expiresAt = newExpiry;
                dummy.text = snap.child("text").getValue(String.class);
                dummy.type = snap.child("type").getValue(String.class);
                cancelExpiryReminder(ctx, statusId);
                scheduleExpiryReminder(ctx, dummy);
            }
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    // ── Client-side cleanup on StatusFragment load ─────────────────────────
    /**
     * Called by StatusFragment when building the status list.
     * Moves any expired statuses to archive and removes them from the active node.
     * The server cron (/status/cleanup) is the primary cleanup mechanism.
     */
    public static void cleanupExpiredForUser(String ownerUid) {
        long now = System.currentTimeMillis();
        FirebaseUtils.getUserStatusRef(ownerUid)
            .orderByChild("expiresAt").endAt(now)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    for (DataSnapshot child : snap.getChildren()) {
                        Boolean archived = child.child("archived").getValue(Boolean.class);
                        if (archived == null || !archived) {
                            archiveExpiredStatus(ownerUid, child.getKey());
                        }
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
}
