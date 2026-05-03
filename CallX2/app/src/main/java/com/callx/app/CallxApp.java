package com.callx.app;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.callx.app.activities.AuthActivity;
import com.callx.app.activities.LockScreenActivity;
import com.callx.app.cache.CacheAnalytics;
import com.callx.app.cache.CacheManager;
import com.callx.app.cache.NetworkCacheHelper;
import com.callx.app.services.StatusBackgroundService;
import com.callx.app.sync.SyncWorker;
import com.callx.app.utils.AppLockManager;
import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class CallxApp extends Application {

    private static final String TAG = "CallxApp";
    private static int    sActivityRefs = 0;
    private static String sMyPhotoUrl   = "";

    public static boolean isAppInForeground()  { return sActivityRefs > 0; }
    public static String  getMyPhotoUrlCached() { return sMyPhotoUrl; }

    // ──────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();

        try {
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .setPersistenceEnabled(false); // disabled: we use our own 3-tier cache
        } catch (Exception e) {
            Log.w(TAG, "Firebase persistence set: " + e.getMessage());
        }

        createChannels();
        registerForegroundTracking();   // FIX #5 + FIX #6 wiring is here
        cacheMyPhotoUrl();
        initCacheSystem();
    }

    // ──────────────────────────────────────────────────────────────
    // onTrimMemory — respond to Android low-memory signals
    //
    // FIX #6: evictConnectionPool() added for TRIM_MEMORY_UI_HIDDEN.
    //   Closes idle TCP connections when app fully backgrounds.
    //   Prevents wasted file descriptors + allows OS to reclaim sockets.
    // ──────────────────────────────────────────────────────────────
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        try {
            CacheManager cache = CacheManager.getInstance(this);

            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                cache.clearMemoryCache();
                Log.w(TAG, "onTrimMemory CRITICAL — full memory cache cleared");

            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                cache.evictLowPriority();
                Log.d(TAG, "onTrimMemory MODERATE — low priority items evicted");

            } else if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                // App fully backgrounded
                cache.evictLowPriority();

                // FIX #6: close idle OkHttp connections — frees OS sockets
                NetworkCacheHelper.evictConnectionPool(this);

                Log.d(TAG, "onTrimMemory UI_HIDDEN — evicted + connections closed");
            }
        } catch (Exception e) {
            Log.w(TAG, "onTrimMemory error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Ultra Advanced Cache System init
    // ──────────────────────────────────────────────────────────────
    private void initCacheSystem() {
        try {
            CacheManager cacheManager = CacheManager.getInstance(this);
            cacheManager.preloadTopChats();
            SyncWorker.schedule(this);

            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                Intent svc = new Intent(this, StatusBackgroundService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            }
            Log.d(TAG, "Ultra Advanced Cache System initialized");
        } catch (Exception e) {
            Log.w(TAG, "Cache init error: " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Foreground tracking + FIX #5: guaranteed analytics flush
    //
    // FIX #5: When sActivityRefs drops to 0 (all activities stopped,
    //   app going to background), call CacheAnalytics.flushNow().
    //   This guarantees analytics are written to SharedPreferences
    //   BEFORE the OS can send SIGKILL.
    //   SIGKILL can only arrive after onStop() returns, so this flush
    //   always completes in time.
    // ──────────────────────────────────────────────────────────────
    private void registerForegroundTracking() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {

            @Override public void onActivityCreated(Activity a, Bundle s) {}

            @Override public void onActivityStarted(Activity a) {
                sActivityRefs++;
            }

            @Override public void onActivityResumed(Activity a) {
                if (!(a instanceof LockScreenActivity)
                        && !(a instanceof AuthActivity)) {
                    AppLockManager lm = new AppLockManager(a);
                    if (lm.isLockEnabled() && !LockScreenActivity.isUnlocked()) {
                        a.startActivity(new Intent(a, LockScreenActivity.class));
                    }
                }
            }

            @Override public void onActivityPaused(Activity a) {}

            @Override public void onActivityStopped(Activity a) {
                if (sActivityRefs > 0) sActivityRefs--;

                if (sActivityRefs == 0) {
                    // All activities stopped — app going to background

                    // FIX #5: flush analytics NOW before OS can SIGKILL
                    try {
                        CacheAnalytics.getInstance(CallxApp.this).flushNow();
                    } catch (Exception e) {
                        Log.w(TAG, "Analytics flush on background: " + e.getMessage());
                    }

                    // Reset app lock
                    LockScreenActivity.resetUnlock();
                }
            }

            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }

    // ──────────────────────────────────────────────────────────────
    private void cacheMyPhotoUrl() {
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
            String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("users").child(uid).child("photoUrl")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        Object v = s.getValue();
                        sMyPhotoUrl = v == null ? "" : String.valueOf(v);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        } catch (Exception ignored) {}
    }

    // ──────────────────────────────────────────────────────────────
    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

        makeChannel(nm, Constants.CHANNEL_CALLS, "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH, true, true, attrs,
            android.app.Notification.VISIBILITY_PUBLIC, true);

        makeChannel(nm, Constants.CHANNEL_CALLS_INCOMING, "Incoming Ring",
            NotificationManager.IMPORTANCE_HIGH, true, true, attrs,
            android.app.Notification.VISIBILITY_PUBLIC, true);

        makeChannel(nm, Constants.CHANNEL_MESSAGES, "Messages",
            NotificationManager.IMPORTANCE_HIGH, true, false, null,
            android.app.Notification.VISIBILITY_PRIVATE, false);

        makeChannel(nm, Constants.CHANNEL_GROUPS, "Group Messages",
            NotificationManager.IMPORTANCE_HIGH, true, false, null,
            android.app.Notification.VISIBILITY_PRIVATE, true);

        makeChannel(nm, Constants.CHANNEL_GROUPS_MUTED, "Group Messages (Muted)",
            NotificationManager.IMPORTANCE_LOW, false, false, null,
            android.app.Notification.VISIBILITY_PRIVATE, false);

        makeChannel(nm, Constants.CHANNEL_STATUS, "Status / Story",
            NotificationManager.IMPORTANCE_DEFAULT, false, false, null,
            android.app.Notification.VISIBILITY_PUBLIC, false);

        makeChannel(nm, Constants.CHANNEL_REQUESTS, "Contact Requests",
            NotificationManager.IMPORTANCE_HIGH, true, false, null,
            android.app.Notification.VISIBILITY_PUBLIC, false);

        makeChannel(nm, Constants.CHANNEL_BLOCK, "Blocked Senders",
            NotificationManager.IMPORTANCE_HIGH, false, false, null,
            android.app.Notification.VISIBILITY_PUBLIC, false);

        makeChannel(nm, Constants.CHANNEL_MUTED, "Muted Conversations",
            NotificationManager.IMPORTANCE_LOW, false, false, null,
            android.app.Notification.VISIBILITY_PRIVATE, false);

        // Group call channels
        NotificationChannel gcallIn = new NotificationChannel(
            Constants.CHANNEL_GROUP_CALLS_INCOMING, "Incoming Group Calls",
            NotificationManager.IMPORTANCE_HIGH);
        gcallIn.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs);
        gcallIn.enableVibration(true);
        gcallIn.setVibrationPattern(new long[]{0, 500, 250, 500});
        gcallIn.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        gcallIn.setShowBadge(true);
        nm.createNotificationChannel(gcallIn);

        makeChannel(nm, Constants.CHANNEL_GROUP_CALLS_ONGOING, "Ongoing Group Call",
            NotificationManager.IMPORTANCE_LOW, false, false, null,
            android.app.Notification.VISIBILITY_PUBLIC, false);

        makeChannel(nm, Constants.CHANNEL_GROUP_CALLS_MISSED, "Missed Group Calls",
            NotificationManager.IMPORTANCE_DEFAULT, true, false, null,
            android.app.Notification.VISIBILITY_PRIVATE, true);

        makeChannel(nm, Constants.CHANNEL_STATUS_BG_SERVICE, "Status Sync Service",
            NotificationManager.IMPORTANCE_MIN, false, false, null,
            android.app.Notification.VISIBILITY_SECRET, false);
    }

    private static void makeChannel(NotificationManager nm,
                                    String id, String name, int importance,
                                    boolean vibrate, boolean sound,
                                    AudioAttributes attrs, int lockscreen,
                                    boolean badge) {
        NotificationChannel ch = new NotificationChannel(id, name, importance);
        ch.enableVibration(vibrate);
        if (sound && attrs != null)
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs);
        else
            ch.setSound(null, null);
        ch.setLockscreenVisibility(lockscreen);
        ch.setShowBadge(badge);
        nm.createNotificationChannel(ch);
    }
}
