package com.callx.app.services;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * StatusBackgroundService — Foreground DATA_SYNC service.
 *
 * Keeps Firebase status listeners alive even when app is in background/killed.
 * Woken by FCM (type="status") → delivers rich notification → no missed statuses.
 *
 * Responsibilities:
 *  1. Real-time Firebase listener on statuses of all contacts
 *  2. FCM wake handler (new status push → show notification instantly)
 *  3. Deduplication (don't notify twice for same status)
 *  4. Periodic sync on reconnect (handle offline gap)
 *  5. Auto-cleanup of expired statuses on contact list
 *  6. Contact list change detection (new contact added → subscribe their statuses)
 *
 * AndroidManifest requirements:
 *  <service android:name=".services.StatusBackgroundService"
 *           android:foregroundServiceType="dataSync"
 *           android:stopWithTask="false" />
 *  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
 */
public class StatusBackgroundService extends Service {

    private static final String TAG            = "StatusBgSvc";
    private static final String CHANNEL_ID     = "ch_status_bg_service";
    private static final int    NOTIF_ID        = 9500;
    private static final long   SYNC_INTERVAL  = TimeUnit.MINUTES.toMillis(5);

    // ── Dedup cache: statusId → notified timestamp ────────────────────────
    private final Set<String> notifiedStatusIds =
        Collections.synchronizedSet(new HashSet<>());

    // ── Firebase listeners: contactUid → listener ─────────────────────────
    private final Map<String, ValueEventListener> contactListeners = new ConcurrentHashMap<>();
    private ValueEventListener contactsListener;
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor();

    private String myUid;

    // ── Lifecycle ─────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIF_ID, buildServiceNotification());
        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid != null) {
            watchContacts();
            schedulePeriodicSync();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle FCM wake (intent carries status extras)
        if (intent != null) {
            String fromUid    = intent.getStringExtra("fromUid");
            String fromName   = intent.getStringExtra("fromName");
            String fromPhoto  = intent.getStringExtra("fromPhoto");
            String statusType = intent.getStringExtra("statusType");
            String text       = intent.getStringExtra("text");
            String mediaUrl   = intent.getStringExtra("mediaUrl");
            String statusId   = intent.getStringExtra("statusId");

            if (fromUid != null && !fromUid.isEmpty()) {
                handleFcmStatusWake(fromUid, fromName, fromPhoto,
                    statusType, text, mediaUrl, statusId);
            }
        }
        return START_STICKY; // Restart on kill
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeAllListeners();
        try { scheduler.shutdownNow(); } catch (Exception ignored) {}
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Contact watching ──────────────────────────────────────────────────
    private void watchContacts() {
        if (myUid == null) return;
        contactsListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Set<String> currentContacts = new HashSet<>();
                for (DataSnapshot c : snap.getChildren()) currentContacts.add(c.getKey());

                // Add listeners for new contacts
                for (String uid : currentContacts) {
                    if (!contactListeners.containsKey(uid)) {
                        watchContactStatuses(uid);
                    }
                }
                // Remove listeners for removed contacts
                Iterator<Map.Entry<String, ValueEventListener>> it =
                    contactListeners.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, ValueEventListener> e = it.next();
                    if (!currentContacts.contains(e.getKey())) {
                        FirebaseUtils.getUserStatusRef(e.getKey())
                            .removeEventListener(e.getValue());
                        it.remove();
                    }
                }
            }
            @Override public void onCancelled(DatabaseError e) {
                android.util.Log.w(TAG, "Contacts listener cancelled: " + e.getMessage());
            }
        };
        FirebaseUtils.getContactsRef(myUid).addValueEventListener(contactsListener);
    }

    private void watchContactStatuses(String contactUid) {
        if (contactListeners.containsKey(contactUid)) return;
        long sinceMs = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24);

        ValueEventListener listener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                for (DataSnapshot child : snap.getChildren()) {
                    StatusItem item = child.getValue(StatusItem.class);
                    if (item == null) continue;
                    if (item.deleted || item.archived) continue;
                    if (item.expiresAt > 0 && System.currentTimeMillis() > item.expiresAt) continue;
                    if (item.statusId == null) item.statusId = child.getKey();

                    // Skip already-seen statuses
                    if (StatusSeenTracker.get().hasSeenLocally(item.statusId,
                            myUid != null ? myUid : "")) continue;

                    // Dedup: only notify once per status per process lifetime
                    if (notifiedStatusIds.contains(item.statusId)) continue;
                    notifiedStatusIds.add(item.statusId);

                    // Notify
                    StatusNotificationHelper.notifyNewStatus(
                        getApplicationContext(),
                        item.ownerUid,
                        item.ownerName   != null ? item.ownerName   : "Contact",
                        item.ownerPhoto  != null ? item.ownerPhoto  : "",
                        item.type        != null ? item.type        : "text",
                        item.mediaUrl    != null ? item.mediaUrl    : "",
                        item.text        != null ? item.text        : "",
                        item.statusId);
                }
            }
            @Override public void onCancelled(DatabaseError e) {}
        };

        FirebaseUtils.getUserStatusRef(contactUid)
            .orderByChild("timestamp")
            .startAt(sinceMs)
            .addValueEventListener(listener);

        contactListeners.put(contactUid, listener);
    }

    // ── FCM wake ─────────────────────────────────────────────────────────
    private void handleFcmStatusWake(String fromUid, String fromName, String fromPhoto,
                                      String statusType, String text, String mediaUrl,
                                      String statusId) {
        if (statusId != null && notifiedStatusIds.contains(statusId)) return;
        if (statusId != null) notifiedStatusIds.add(statusId);

        StatusNotificationHelper.notifyNewStatus(
            getApplicationContext(),
            fromUid,
            fromName   != null ? fromName   : "Contact",
            fromPhoto  != null ? fromPhoto  : "",
            statusType != null ? statusType : "text",
            mediaUrl   != null ? mediaUrl   : "",
            text       != null ? text       : "",
            statusId   != null ? statusId   : "");
    }

    // ── Periodic sync ─────────────────────────────────────────────────────
    private void schedulePeriodicSync() {
        scheduler.scheduleAtFixedRate(() -> {
            if (myUid == null) return;
            // Re-trigger contact sync (catches any listener drops due to network gaps)
            watchContacts();
            // Cleanup own expired statuses
            StatusExpiryManager.cleanupExpiredForUser(myUid);
        }, SYNC_INTERVAL, SYNC_INTERVAL, TimeUnit.MILLISECONDS);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────
    private void removeAllListeners() {
        for (Map.Entry<String, ValueEventListener> e : contactListeners.entrySet()) {
            try {
                FirebaseUtils.getUserStatusRef(e.getKey()).removeEventListener(e.getValue());
            } catch (Exception ignored) {}
        }
        contactListeners.clear();
        if (myUid != null && contactsListener != null) {
            try {
                FirebaseUtils.getContactsRef(myUid).removeEventListener(contactsListener);
            } catch (Exception ignored) {}
        }
    }

    // ── Notification ─────────────────────────────────────────────────────
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Status Sync", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("Keeps status updates live");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .createNotificationChannel(ch);
    }

    private Notification buildServiceNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CallX")
            .setContentText("Keeping status updates fresh…")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build();
    }
}
