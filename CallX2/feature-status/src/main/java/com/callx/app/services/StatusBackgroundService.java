package com.callx.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.callx.app.status.R;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.StatusMuteManager;
import com.callx.app.utils.StatusNotificationHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StatusBackgroundService — Foreground service (DATA_SYNC type).
 *
 * Keeps a Firebase RTDB listener alive in background.
 * Integration with StatusMuteManager: muted contacts are silently ignored.
 *
 * Lifecycle:
 *  1. Started from CallxApp.onCreate() — survives normal background kills.
 *  2. Also started from CallxMessagingService when FCM "status" push arrives.
 *  3. Cleans up all Firebase listeners in onDestroy().
 *
 * Features:
 *  - Watches "status" node for new children added by contacts.
 *  - Skips muted contacts (StatusMuteManager).
 *  - Deduplicates notifications per user (lastNotifiedAt map).
 *  - Handles app-killed → FCM-wake → notification flow.
 *  - Self-promoted to foreground with a silent, minimal notification.
 */
public class StatusBackgroundService extends Service {

    private static final String TAG           = "StatusBgSvc";
    private static final String FOREGROUND_CH = "callx_status_fg";
    private static final int    FOREGROUND_ID = 8001;

    private final Map<String, ChildEventListener> contactListeners =
            new ConcurrentHashMap<>();
    private final Map<String, Long> lastNotifiedAt = new ConcurrentHashMap<>();

    private DatabaseReference contactsRef;
    private ValueEventListener contactsListener;
    private String myUid;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
        createForegroundChannel();
        startForeground(FOREGROUND_ID, buildSilentNotification());
        try {
            myUid = FirebaseUtils.getCurrentUid();
        } catch (Exception e) {
            Log.w(TAG, "User not signed in — stopping service");
            stopSelf();
            return;
        }
        // Sync muted list from Firebase on startup so local cache is fresh
        StatusMuteManager.syncFromFirebase(this);
        attachContactsListener();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String fromUid   = intent.getStringExtra("fromUid");
            String fromName  = intent.getStringExtra("fromName");
            String fromPhoto = intent.getStringExtra("fromPhoto");
            String sType     = intent.getStringExtra("statusType");
            String text      = intent.getStringExtra("text");
            String mediaUrl  = intent.getStringExtra("mediaUrl");
            if (fromUid != null && fromName != null) {
                postNotificationIfNew(fromUid, fromName, fromPhoto,
                        sType, text, mediaUrl, System.currentTimeMillis());
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy — cleaning up listeners");
        removeAllContactListeners();
        if (contactsRef != null && contactsListener != null) {
            contactsRef.removeEventListener(contactsListener);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ── Firebase wiring ───────────────────────────────────────────────────

    private void attachContactsListener() {
        contactsRef = FirebaseUtils.getContactsRef(myUid);
        contactsListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                removeAllContactListeners();
                for (DataSnapshot child : snap.getChildren()) {
                    String contactUid = child.getKey();
                    if (contactUid != null) watchContactStatus(contactUid);
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {
                Log.w(TAG, "contacts listener cancelled: " + error.getMessage());
            }
        };
        contactsRef.addValueEventListener(contactsListener);
    }

    private void watchContactStatus(String contactUid) {
        if (contactListeners.containsKey(contactUid)) return;

        DatabaseReference statusRef = FirebaseUtils.getStatusRef().child(contactUid);
        ChildEventListener listener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snap, String prev) {
                try {
                    StatusItem item = snap.getValue(StatusItem.class);
                    if (item == null || item.deleted || item.isExpired()) return;
                    if (item.timestamp == null) return;

                    long age = System.currentTimeMillis() - item.timestamp;
                    if (age > 30_000L) return; // skip reconnect flush

                    if (myUid.equals(item.ownerUid)) return; // own status

                    // ── Mute check — skip if contact is muted ──
                    if (StatusMuteManager.isMuted(
                            StatusBackgroundService.this, item.ownerUid)) {
                        Log.d(TAG, "Skipping muted contact: " + item.ownerUid);
                        return;
                    }

                    postNotificationIfNew(
                            item.ownerUid, item.ownerName, item.ownerPhoto,
                            item.type, item.text, item.mediaUrl, item.timestamp);
                } catch (Exception e) {
                    Log.w(TAG, "onChildAdded error: " + e.getMessage());
                }
            }
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override
            public void onCancelled(DatabaseError e) {
                Log.w(TAG, "status listener cancelled for " + contactUid);
            }
        };

        statusRef.addChildEventListener(listener);
        contactListeners.put(contactUid, listener);
    }

    private void removeAllContactListeners() {
        for (Map.Entry<String, ChildEventListener> e : contactListeners.entrySet()) {
            FirebaseUtils.getStatusRef()
                    .child(e.getKey())
                    .removeEventListener(e.getValue());
        }
        contactListeners.clear();
    }

    // ── Notification posting ──────────────────────────────────────────────

    private void postNotificationIfNew(String fromUid, String fromName,
                                       String fromPhoto, String statusType,
                                       String text, String mediaUrl,
                                       long timestamp) {
        Long last = lastNotifiedAt.get(fromUid);
        if (last != null && timestamp <= last) return; // dedup
        lastNotifiedAt.put(fromUid, timestamp);

        // Double-check mute before posting (in case local prefs were updated
        // after the listener fired but before the notification posts)
        if (StatusMuteManager.isMuted(this, fromUid)) return;

        int notifId = (fromUid != null ? fromUid.hashCode() : 0) & 0x7FFFFFFF;
        StatusNotificationHelper.postStatusNotification(
                getApplicationContext(), fromUid, fromName, fromPhoto,
                statusType, text, mediaUrl, notifId);
    }

    // ── Foreground notification ───────────────────────────────────────────

    private void createForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel ch = new NotificationChannel(
                FOREGROUND_CH, "Status Background Sync",
                NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("Keeps status updates live in the background");
        ch.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(ch);
    }

    private Notification buildSilentNotification() {
        return new NotificationCompat.Builder(this, FOREGROUND_CH)
                .setSmallIcon(R.drawable.ic_status_notification)
                .setContentTitle("CallX")
                .setContentText("Watching for new statuses…")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .build();
    }
}
