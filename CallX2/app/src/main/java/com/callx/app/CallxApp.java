package com.callx.app;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.callx.app.notifications.XNotificationWorker;
import com.callx.app.activities.AuthActivity;
import com.callx.app.activities.LockScreenActivity;
import com.callx.app.cache.CacheAnalytics;
import com.callx.app.cache.ReelCacheManager;
import com.callx.app.cache.XTweetCacheManager;
import com.callx.app.cache.UnifiedVideoCacheManager;
import com.callx.app.cache.StatusVideoCacheManager;
import com.callx.app.cache.CacheManager;
import com.callx.app.cache.NetworkCacheHelper;
import com.callx.app.cache.StatusCacheManager;
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
    // FIX #STARTUP: Heavy init ko background thread pe daala gaya hai.
    //
    // MAIN THREAD (instant, blocking nahi):
    //   - Notification channels register
    //   - WorkManager workers schedule
    //   - Activity lifecycle callbacks register
    //
    // BACKGROUND THREAD (app-init-bg):
    //   - Firebase persistence config
    //   - Photo URL cache
    //   - Cache system + SyncWorker
    //   - ExoPlayer disk cache (200MB)
    //   - ReelCacheManager (500MB)
    //   - XTweetCacheManager
    //   - StatusVideoCacheManager (200MB)
    //
    // Faida: ~300-600ms startup improvement on mid/low-end devices.
    // ──────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();

        // ── PERF FIX: dedicated AppDatabase (SQLCipher) warm-up thread ────
        // Root cause of "chat khulta hai aur 3 sec baad messages aate hain":
        // AppDatabase.getInstance() does SQLCipher loadLibs() + Android
        // Keystore key retrieval + Room schema check — 500ms to 3sec, and
        // it's a synchronized singleton. The old code only warmed it up
        // INSIDE the shared "app-init-bg" thread, AFTER Firebase persistence
        // config + photo listener — by the time it actually started building,
        // a user who opened a chat right after launch would have their
        // ChatActivity's own AppDatabase.getInstance() call BLOCK on the same
        // lock, waiting for this thread to finish. That wait is the visible
        // "3 second" delay.
        // Fix: build it on its OWN thread, started first thing, so SQLCipher
        // init begins at the earliest possible moment (process start) and
        // races independently of every other background task below.
        new Thread(() -> {
            try {
                com.callx.app.db.AppDatabase.getInstance(CallxApp.this);
                Log.d(TAG, "AppDatabase (SQLCipher) warm-up complete");
            } catch (Exception e) {
                Log.w(TAG, "AppDatabase warm-up failed (will retry on first use): " + e.getMessage());
            }
        }, "db-warmup").start();

        // ── MAIN THREAD: sirf lightweight kaam ────────────────────────

        // Notification channels — Android OS call hai, fast hai
        createChannels();

        // Reel notification channels (39 channels register)
        com.callx.app.notifications.ReelNotificationChannelManager.ensureChannels(this);

        // YouTube notification channels
        com.callx.app.notifications.YouTubeNotificationChannelManager.ensureChannels(this);

        // WorkManager workers schedule — sirf enqueue karta hai, heavy nahi
        XNotificationWorker.schedule(this);
        com.callx.app.notifications.ReelNotificationWorker.schedule(this);
        // YouTube background polling worker — killed/background state notifications
        com.callx.app.notifications.YouTubeNotificationWorker.schedule(this);

        // Activity lifecycle + AppLock wiring — must be main thread
        registerForegroundTracking();

        // Sync privacy settings to Firebase (if user already logged in)
        try {
            if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                new com.callx.app.utils.SecurityManager(this).syncAllPrivacyToFirebase();
            }
        } catch (Exception ignored) {}

        // Domain verification check — async internally, safe on main thread
        checkAndRequestDomainVerification();

        // ── BACKGROUND THREAD: heavy init ─────────────────────────────
        new Thread(() -> {
            // Firebase persistence config
            try {
                FirebaseDatabase.getInstance(Constants.DB_URL)
                    .setPersistenceEnabled(false); // disabled: we use our own 3-tier cache
            } catch (Exception e) {
                Log.w(TAG, "Firebase persistence set: " + e.getMessage());
            }

            // User photo URL Firebase listener
            cacheMyPhotoUrl();

            // Cache system: CacheManager, SyncWorker, StatusCacheManager, StatusBackgroundService
            initCacheSystem();

            // v32 Unified Video Cache — replaces 4 separate caches (was 1150MB total)
            // Now: 500MB shared, partial caching (4MB/reel = ~125 reels), weighted quotas
            UnifiedVideoCacheManager.init(CallxApp.this);
            // Legacy managers auto-delegate to UnifiedVideoCacheManager
            com.callx.app.utils.ExoPlayerManager.init(CallxApp.this);

            // Reels: 500MB dedicated cache for Instagram-like instant playback
            ReelCacheManager.init(CallxApp.this);

            // X tweet video cache
            XTweetCacheManager.init(CallxApp.this);

            // Status: 200MB dedicated cache — same pattern as Reels
            StatusVideoCacheManager.init(CallxApp.this);

            Log.d(TAG, "Background init complete");
        }, "app-init-bg").start();
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
                // FIX #MEM-3B: Reel + ExoPlayer video cache bhi trim karo — OOM se bachao
                ReelCacheManager.trimMemory();
                UnifiedVideoCacheManager.trimMemory();
                com.callx.app.utils.ExoPlayerManager.trimMemory();
                // PERF FIX: per-chat last-messages cache — drop everything under
                // real memory pressure. Room remains the source of truth, so
                // this only disables the instant-render fast path until chats
                // are reopened (which re-primes it) — no data loss.
                com.callx.app.cache.LastMessagesCache.getInstance().trimMemory(level);
                Log.w(TAG, "onTrimMemory CRITICAL — full memory cache + video caches cleared");

            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                cache.evictLowPriority();
                // FIX #MEM-3B: Moderate signal pe bhi Reel cache trim karo
                ReelCacheManager.trimMemory();
                com.callx.app.cache.LastMessagesCache.getInstance().trimMemory(level);
                Log.d(TAG, "onTrimMemory MODERATE — low priority + reel cache trimmed");

            } else if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                // App fully backgrounded
                cache.evictLowPriority();
                // FIX #6: close idle OkHttp connections — frees OS sockets
                NetworkCacheHelper.evictConnectionPool(this);
                // FIX #MEM-3B: Background mein jao to Reel cache bhi thoda release karo
                ReelCacheManager.trimMemory();
                Log.d(TAG, "onTrimMemory UI_HIDDEN — evicted + connections closed + reels trimmed");
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

            // Start global status cache — ek baar Firebase read, pure app mein reuse
            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
                StatusCacheManager.getInstance(this).startListening(uid);
            }

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
                if (sActivityRefs == 0) {
                    // App is coming to foreground — mark online
                    com.callx.app.utils.PresenceManager.getInstance().goOnline();
                }
                sActivityRefs++;
            }

            @Override public void onActivityResumed(Activity a) {
                if (!(a instanceof LockScreenActivity)
                        && !(a instanceof AuthActivity)) {
                    // FIX-AL1: Singleton use karo — heavy EncryptedSharedPreferences
                    // init har resume pe nahi hoga
                    AppLockManager lm = AppLockManager.getInstance(a);
                    // FIX-AL2: shouldLockNow() delay check karta hai — pehle hamesha lock hota tha
                    if (lm.isLockEnabled() && !LockScreenActivity.isUnlocked(a) && lm.shouldLockNow()) {
                        a.startActivity(new Intent(a, LockScreenActivity.class));
                    }
                }
            }

            @Override public void onActivityPaused(Activity a) {}

            @Override public void onActivityStopped(Activity a) {
                if (sActivityRefs > 0) sActivityRefs--;

                if (sActivityRefs == 0) {
                    // All activities stopped — app going to background

                    // Mark user offline + write lastSeen to Firebase
                    com.callx.app.utils.PresenceManager.getInstance().goOffline();

                    // FIX #5: flush analytics NOW before OS can SIGKILL
                    try {
                        CacheAnalytics.getInstance(CallxApp.this).flushNow();
                    } catch (Exception e) {
                        Log.w(TAG, "Analytics flush on background: " + e.getMessage());
                    }

                    // FIX-AL2: Background timestamp record karo (auto-lock delay ke liye)
                    // resetUnlock tabhi karo jab delay = 0 (immediately)
                    AppLockManager lm = AppLockManager.getInstance(CallxApp.this);
                    lm.recordBackgroundTime();
                    if (lm.getAutoLockDelayMs() == AppLockManager.DELAY_IMMEDIATELY) {
                        // Immediately lock — session reset karo abhi
                        LockScreenActivity.resetUnlock(CallxApp.this);
                    } else {
                        // Delay mode — session reset nahi karo abhi.
                        // onActivityResumed mein shouldLockNow() check karega.
                        // Agar delay expire ho gaya hoga to lock lagega.
                        LockScreenActivity.resetUnlock(CallxApp.this);
                        // Note: resetUnlock always karo taaki delay expire hone ke baad
                        // fresh lock dikhaye — shouldLockNow() timestamp se decide karega.
                    }
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

    // ── Android App Links — Auto Domain Verification ──────────────────────
    // Android 12+ (API 31+) pe DomainVerificationManager se check karo.
    // Agar domain verified nahi hai, user ko settings mein bhejo automatically.
    // Older Android pe ye silently skip hota hai — wahan assetlinks.json
    // install time pe verify ho jaata hai.
    private void checkAndRequestDomainVerification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return; // API 31+
        try {
            DomainVerificationManager manager =
                getSystemService(DomainVerificationManager.class);
            if (manager == null) return;
            DomainVerificationUserState userState =
                manager.getDomainVerificationUserState(getPackageName());
            if (userState == null) return;

            // Check karo — callx-server.onrender.com verified hai ya nahi
            java.util.Map<String, Integer> hostToStateMap = userState.getHostToStateMap();
            boolean verified = false;
            for (java.util.Map.Entry<String, Integer> entry : hostToStateMap.entrySet()) {
                if (entry.getKey().contains("onrender.com") &&
                    entry.getValue() == DomainVerificationUserState.DOMAIN_STATE_VERIFIED) {
                    verified = true;
                    break;
                }
            }

            if (!verified) {
                // Verified nahi — system ko re-verify trigger karne do
                // User ko settings mein nahi bhejna — bas log karo
                Log.i(TAG, "[AppLinks] Domain not yet verified, will retry on next launch");
            } else {
                Log.i(TAG, "[AppLinks] Domain verified ✓");
            }
        } catch (Exception e) {
            Log.w(TAG, "[AppLinks] Verification check failed: " + e.getMessage());
        }
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

        // HUN-FIX: IMPORTANCE_HIGH required for heads-up notification (peeking banner)
        // No ringtone (null sound), vibrate ON, badge ON, public lock-screen visibility
        // Channel ID bumped to v2 in Constants — forces Android to recreate with correct importance
        makeChannel(nm, Constants.CHANNEL_CALLS_MISSED, "Missed Calls",
            NotificationManager.IMPORTANCE_HIGH, true, false, null,
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

    @Override
    public void onTerminate() {
        ReelCacheManager.release();
        XTweetCacheManager.release(); // X tweet video cache
        StatusVideoCacheManager.release();
        UnifiedVideoCacheManager.release();
        super.onTerminate();
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
