package com.callx.app;
import com.callx.app.utils.FirebaseUtils;

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

        // "Just watched" reels-grid overlay is scoped to the CURRENT app
        // session (see AppSessionTracker + UserReelsActivity#loadWatchedReelIds
        // in feature-reels) — touching it here, first thing, forces its
        // session-start timestamp to be captured at actual process start
        // instead of lazily whenever the Reels tab first happens to load.
        com.callx.app.utils.AppSessionTracker.getSessionStartMs();

        // ── CRASH CAPTURE: on-device crash trace (no adb/logcat needed) ────
        // Registered first so it wraps every subsequent line in onCreate too.
        // On any uncaught exception anywhere in the app: saves the full
        // stack trace to files/last_crash.txt AND launches CrashReportActivity
        // so the trace can be read/copied straight off the crashed screen.
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                pw.println("Thread: " + thread.getName());
                pw.println("Time: " + new java.util.Date());
                throwable.printStackTrace(pw);
                String trace = sw.toString();

                com.callx.app.activities.CrashReportActivity.saveTraceToFile(this, trace);

                Intent i = new Intent(this, com.callx.app.activities.CrashReportActivity.class);
                i.putExtra(com.callx.app.activities.CrashReportActivity.EXTRA_TRACE, trace);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            } catch (Throwable ignored) {
                // Fall through to previous/default handler below no matter what.
            } finally {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(1);
            }
        });

        // ── PERF: StrictMode (DEBUG builds only) ──────────────────────────
        // Catches exactly the class of bug the user asked to check for:
        // accidental disk/DB/network calls hiding inside RecyclerView
        // bind() paths, which show up as scroll jank/frame drops rather
        // than a crash, and are otherwise easy to miss. Logs a stack trace
        // to Logcat (StrictMode.ThreadPolicy default penalty) instead of
        // crashing — safe to leave on for regular dev testing, and this
        // block is entirely compiled out of release builds since
        // BuildConfig.DEBUG is a compile-time constant.
        //
        // If this starts logging violations, check for: SharedPreferences
        // .commit()/apply() reads, Room DAO calls, File I/O, or Firebase
        // synchronous calls made directly from onBindViewHolder() or from
        // a main-thread listener callback instead of a background executor.
        if (BuildConfig.DEBUG) {
            // v243: detectAll() — every ThreadPolicy check the current API
            // level supports (disk reads/writes, network, custom slow calls,
            // resource mismatches on O+, unbuffered I/O on P+, explicit GC
            // on S+), instead of hand-picking a subset. Catches the small
            // accidental main-thread call (e.g. a stray SharedPreferences
            // read added later in some unrelated screen) that a manually
            // curated list would silently miss.
            android.os.StrictMode.setThreadPolicy(
                    new android.os.StrictMode.ThreadPolicy.Builder()
                            .detectAll()
                            .penaltyLog()
                            .build());
            android.os.StrictMode.setVmPolicy(
                    new android.os.StrictMode.VmPolicy.Builder()
                            .detectLeakedSqlLiteObjects()
                            .detectLeakedClosableObjects()
                            .detectActivityLeaks()             // v243: Activity context outliving its lifecycle
                            .detectLeakedRegistrationObjects() // v243: unregistered BroadcastReceiver/ServiceConnection
                            .penaltyLog()
                            .build());
        }

        // ── PERF FIX v33: Firebase Realtime Database disk persistence ─────
        // Root cause of "chat khulne ke 2 sec baad messages load hote hain":
        // setPersistenceEnabled(false) tha (see old comment: "we use our own
        // 3-tier cache") — lekin woh 3-tier cache (Room/LastMessagesCache)
        // sirf PURANE messages turant dikhata hai. Naye/latest messages ke
        // liye ChatActivity ka ChildEventListener HAMESHA Firebase server se
        // fresh network round-trip karta tha har single chat open pe — wahi
        // asli 2 second wait hai, aur slow network pe aur zyada.
        // Fix: persistence ENABLE karo. Firebase SDK apna khud ka on-disk
        // cache maintain karega — listener attach hote hi cached data turant
        // (same-frame, disk se) mil jaata hai, aur background mein silently
        // server se sync ho jaata hai. Isi wajah se WhatsApp/Telegram jaisa
        // "instant" feel aata hai.
        // MUST be the very first FirebaseDatabase call in the process — call
        // it synchronously here, before the db-warmup thread below and
        // before any other code path can touch FirebaseDatabase.getInstance().
        try {
            FirebaseDatabase.getInstance(Constants.DB_URL)
                    .setPersistenceEnabled(true);
            // Default persistence cache is 10MB — bump it a bit since chat
            // history + presence + typing nodes all share this cache.
            FirebaseDatabase.getInstance(Constants.DB_URL)
                    .setPersistenceCacheSizeBytes(20L * 1024 * 1024); // 20MB
        } catch (Exception e) {
            // Can throw if some other code already touched FirebaseDatabase
            // before this line ran (e.g. process re-used after config
            // change) — safe to ignore, DB just runs without local disk
            // cache for this process lifetime.
            Log.w(TAG, "Firebase persistence enable failed: " + e.getMessage());
        }

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
        //
        // PERF FIX v237 — Chat List "Load time 378ms vs 150ms target":
        // AppDatabase.getInstance() itself (Room.databaseBuilder(...).build())
        // is CHEAP — it's just object construction, no I/O. The real cost
        // (SQLCipher/Keystore init, opening the .db file, running the 16
        // migrations' schema-hash validation across ~20 entities) only
        // happens lazily, on whichever thread makes the FIRST ACTUAL DAO
        // query. This thread only ever called getInstance() and never
        // touched a DAO — so that entire cost was silently deferred to
        // whatever screen the user opened first. In a normal cold start
        // that's the Chat List (default tab), so ChatsFragment.loadFromRoom()
        // was the one eating the full DB-open tax on top of its own tiny
        // 11-row query — exactly the gap between the reported 378ms and the
        // 150ms target. Same trick already applied to Glide below (see
        // "PERF FIX" comment on the Glide warm-up) — do the identical thing
        // for Room: run one trivial, real DAO call here so the file-open +
        // migration-validation cost is paid on this background thread,
        // BEFORE the user ever reaches the Chat List, instead of on the
        // Chat List's own timed load-start→load-end window.
        new Thread(() -> {
            try {
                com.callx.app.db.AppDatabase db =
                        com.callx.app.db.AppDatabase.getInstance(CallxApp.this);
                // Cheapest possible real query — forces SQLiteOpenHelper to
                // actually open/create/migrate the file right here. Result
                // is thrown away; only the "DB is genuinely open" side
                // effect matters.
                db.chatDao().getChatCount();
                Log.d(TAG, "AppDatabase warm-up complete");
            } catch (Exception e) {
                Log.w(TAG, "AppDatabase warm-up failed (will retry on first use): " + e.getMessage());
            } finally {
                // v240 — ALWAYS flip this, even on failure. MainActivity's
                // splash gate (see setKeepOnScreenCondition) waits on this
                // flag with its own bounded timeout, but flipping it here
                // too means a failed warm-up doesn't leave the flag stuck
                // false relying only on the timeout — the real first DAO
                // call from ChatsFragment will just re-pay the cost as
                // before this fix ever existed.
                com.callx.app.db.AppDatabase.markDbWarmupComplete();
            }
        }, "db-warmup").start();

        // ── MAIN THREAD: sirf lightweight kaam ────────────────────────

        // Notification channels moved to background thread below (see
        // "PERF FIX v238" comment on that thread) — ~55+ createNotification
        // Channel() Binder/IPC calls to system_server were all running here
        // synchronously on EVERY cold start, direct main-thread cost sitting
        // in front of the very first frame. None of this needs to be ready
        // before the UI paints — channels only need to exist before the
        // FIRST notification is ever shown, which is always well after
        // launch. WorkManager schedule/enqueue calls stay here — that's a
        // one-line enqueue into a DB write queue, not a per-item Binder call.

        // WorkManager workers schedule — sirf enqueue karta hai, heavy nahi
        XNotificationWorker.schedule(this);
        com.callx.app.notifications.ReelNotificationWorker.schedule(this);
        // YouTube background polling worker — killed/background state notifications
        com.callx.app.notifications.YouTubeNotificationWorker.schedule(this);
        // ✅ FIX: Trending Sound worker was built but never scheduled anywhere,
        // so it never actually ran. Enqueue it here alongside the other workers.
        com.callx.app.workers.TrendingSoundWorker.scheduleIfNeeded(this);
        // Reels avatar background pre-warm — charging+WiFi(+idle) only, see
        // AvatarPreWarmWorker's own doc. One-line enqueue, same as every
        // other WorkManager schedule() call on this thread.
        com.callx.app.workers.AvatarPreWarmWorker.schedule(this);
        // ✅ Channel scheduled posts: auto-publish overdue posts every 15 min.
        // Must run at startup so posts scheduled before the last app kill are
        // published promptly — not just when the user opens the composer.
        com.callx.app.channel.ChannelScheduledPostWorker.schedulePeriodicWork(this);
        // ✅ Status scheduled posts: auto-publish overdue scheduled statuses
        // every 15 min, same cadence/pattern as the channel worker above.
        com.callx.app.services.StatusScheduledPostWorker.schedulePeriodicWork(this);

        // Activity lifecycle + AppLock wiring — must be main thread
        com.callx.app.utils.PresenceManager.getInstance().init(this);
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
            // PERF FIX v238 — "app fresh open / recent-apps-kill open slow":
            // Notification channel registration (createChannels() — ~15
            // channels — plus ReelNotificationChannelManager's 39 and
            // YouTubeNotificationChannelManager's) used to run synchronously
            // on the MAIN thread, first thing in onCreate, on every single
            // cold start. Each createNotificationChannel() call is a real
            // Binder IPC round-trip to system_server, not free local work —
            // 55+ of them stacked in front of the very first frame added a
            // real, measurable chunk of the "slow open" the user was
            // reporting even after the Chat List's own snapshot-cache fix.
            // None of this needs to be done before the UI paints — a
            // channel only needs to exist before the FIRST notification
            // through it is shown, which is always well after the user is
            // already looking at the Chat List. Moved here, first thing on
            // this background thread, so it's off the critical path to
            // first paint but still finishes well before any notification
            // could realistically be posted.
            try {
                createChannels();
                com.callx.app.notifications.ReelNotificationChannelManager.ensureChannels(CallxApp.this);
                com.callx.app.notifications.YouTubeNotificationChannelManager.ensureChannels(CallxApp.this);
                Log.d(TAG, "Notification channels registered (background)");
            } catch (Exception e) {
                Log.w(TAG, "Notification channel registration failed: " + e.getMessage());
            }

            // Firebase persistence already enabled synchronously above
            // (must run before any FirebaseDatabase.getInstance() call).

            // PERF FIX: warm up Glide's singleton HERE, off the main thread,
            // instead of letting it happen for free on the Chat List's first
            // row bind. Glide.get(context) triggers the same one-time cost
            // that used to land inside ChatListAdapter.onBindViewHolder()
            // for whichever row bound first — GlideBuilder/registry setup,
            // disk cache directory creation + journal open, memory cache
            // allocation. That one-time tax was showing up as a real ~30ms
            // outlier in the Chat List's row-bind p99 (see Ultra Advanced
            // Diagnostics → Row Bind Cost). Doing it here means it's already
            // paid for, on a background thread, by the time the user ever
            // scrolls to the Chats tab.
            try {
                com.bumptech.glide.Glide.get(CallxApp.this);
                Log.d(TAG, "Glide warm-up complete");
            } catch (Exception e) {
                Log.w(TAG, "Glide warm-up failed (will fall back to lazy init on first use): " + e.getMessage());
            }

            // User photo URL Firebase listener
            cacheMyPhotoUrl();

            // ── E2E RE-KEY FIX: self-healing "🔒 Unable to decrypt message" ──
            // Listens for other devices telling us they lost their session
            // with a partner of ours and need us to re-key (see
            // E2EEncryptionManager#listenForReKeyRequests for the full
            // explanation — asymmetric session loss after a reinstall/new
            // device is the actual root cause of that marker persisting on
            // every message instead of just failing once). Cheap to attach
            // (a single Firebase listener on our own uid's node) and only
            // ever does real work when a partner is genuinely stuck.
            try {
                if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                    String myUid = FirebaseUtils.getCurrentUid();
                    com.callx.app.utils.E2EEncryptionManager.getInstance(CallxApp.this)
                            .listenForReKeyRequests(myUid);
                }
            } catch (Exception e) {
                Log.w(TAG, "E2E re-key listener attach failed: " + e.getMessage());
            }

            // Cache system: CacheManager, SyncWorker, StatusCacheManager
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

            // Verified-badge cache: loads previously-resolved uid→isVerified
            // pairs from disk so a cold restart shows badges immediately
            // instead of every list row waiting on a fresh Firebase read.
            com.callx.app.cache.VerifiedStatusCache.init(CallxApp.this);
            // Live invalidation for our OWN badge only (see class doc for
            // why this isn't done for every cached uid) — an admin
            // grant/revoke reflects instantly instead of waiting on the
            // cache's 12h TTL.
            try {
                if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                    com.callx.app.cache.VerifiedStatusCache.getInstance()
                            .listenSelf(FirebaseUtils.getCurrentUid());
                }
            } catch (Exception e) {
                Log.w(TAG, "Verified-status self listener attach failed: " + e.getMessage());
            }

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

            // ✅ ROOT-CAUSE FIX: TRIM_MEMORY_UI_HIDDEN (20) fires on EVERY
            // single app backgrounding — home button, app switch, screen
            // lock — it is NOT a real memory-pressure signal, just "the UI
            // isn't visible right now". UI_HIDDEN does only lightweight,
            // reversible cleanup and NEVER touches the disk video cache.
            if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                cache.evictLowPriority();
                NetworkCacheHelper.evictConnectionPool(this);
                com.callx.app.cache.AvatarHttpCache.evictConnectionPool();
                Log.d(TAG, "onTrimMemory UI_HIDDEN — light cleanup only, video cache preserved");
                return;
            }

            // FIX #5 (avatar versioning plan): previously called
            // Glide.get(this).clearMemory() on every level >= MODERATE — but
            // MODERATE fires constantly during normal use (any time Android
            // wants some memory back, nowhere near "about to kill this
            // process"), so it was wiping every decoded avatar bitmap on
            // routine backgrounding and killing warm-restart speed for no
            // real benefit. Now: MODERATE only lowers Glide's memory
            // category (lets its LRU trim gradually as new requests come
            // in, non-destructive) and the dedicated per-module L2 avatar
            // caches (AvatarL2MemoryCache/ReelsAvatarL2Cache/ChatAvatarL2Cache,
            // each registered independently via its own
            // registerComponentCallbacks) are left completely untouched at
            // this level by design. A full clearMemory() only happens at
            // COMPLETE below, alongside those L2 caches' own COMPLETE-only
            // eviction — the only level where trimming decoded bitmaps is
            // actually justified.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                com.bumptech.glide.Glide.get(this).setMemoryCategory(
                        com.bumptech.glide.MemoryCategory.LOW);
            }
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                com.bumptech.glide.Glide.get(this).clearMemory();
            }

            // ✅ ROOT-CAUSE FIX #2: BACKGROUND(40) fires as soon as the
            // process enters Android's background/cached process list —
            // basically every single time the app is backgrounded, not
            // just under real memory pressure. RUNNING_CRITICAL(15) is a
            // *foreground* signal and can genuinely mean "about to OOM
            // right now", but in practice fires far more often than an
            // actual OOM on many devices too. Both used to call
            // ReelCacheManager.trimMemory(), which deletes ~50% of the
            // PERSISTENT reel video disk cache (SimpleCache.removeResource)
            // — meaning reels re-downloaded on almost every reopen, even
            // right after being watched minutes earlier. These two levels
            // now only touch genuinely in-memory, instantly-reconstructible
            // state — the disk video cache is left completely alone.
            // NOTE: deliberately NOT calling ExoPlayerManager.trimMemory()
            // here — it shares the same UnifiedVideoCacheManager SimpleCache
            // as ReelCacheManager (see ExoPlayerManager.init()) and would
            // silently reintroduce the exact same disk-cache wipe under a
            // different name.
            if (level == ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                    || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                cache.evictLowPriority();
                Log.d(TAG, "onTrimMemory BACKGROUND/CRITICAL — memory-only cleanup, disk video cache preserved");
                return;
            }

            // Real "process about to be reclaimed" pressure only from here:
            // MODERATE(60)/COMPLETE(80) are the levels Android sends as a
            // background process gets closer to being killed outright for
            // memory — this is the only tier where trimming the persistent
            // disk video cache is actually justified.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
                cache.clearMemoryCache();
                // FIX #MEM-3B / FIX #cache-compound: ReelCacheManager,
                // ExoPlayerManager and UnifiedVideoCacheManager all delegate
                // to the SAME underlying SimpleCache (see each class' init())
                // — calling trimMemory() on all three back-to-back doesn't
                // trim 50% once, it compounds to ~50%×50%×50% ≈ 12.5% of the
                // original cache surviving. One call already trims every
                // module's disk cache (reels + chat/status/x share pool).
                UnifiedVideoCacheManager.trimMemory();
                // PERF FIX: per-chat last-messages cache — drop everything under
                // real memory pressure. Room remains the source of truth, so
                // this only disables the instant-render fast path until chats
                // are reopened (which re-primes it) — no data loss.
                com.callx.app.cache.LastMessagesCache.getInstance().trimMemory(level);
                com.callx.app.cache.ReelFirstFrameCache.get(this).trimMemory();
                Log.w(TAG, "onTrimMemory COMPLETE — full memory cache + video caches cleared");

            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
                cache.evictLowPriority();
                // FIX #cache-compound-2: pehle yahan ReelCacheManager.trimMemory() seedha
                // call hota tha — wahi shared SimpleCache jo UnifiedVideoCacheManager bhi
                // use karta hai, isliye duplicate wipe path yahin se wapas aa jata tha.
                // Ab sirf ek hi function (UnifiedVideoCacheManager.trimMemory()) shared
                // cache ko touch karta hai — reels + chat/status/x sab modules ke liye.
                UnifiedVideoCacheManager.trimMemory();
                com.callx.app.cache.LastMessagesCache.getInstance().trimMemory(level);
                Log.d(TAG, "onTrimMemory MODERATE — low priority + unified video cache trimmed");
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
                String uid = FirebaseUtils.getCurrentUid();
                StatusCacheManager.getInstance(this).startListening(uid);
            }

            // WhatsApp-level approach: don't run a permanent foreground
            // service just to "watch" for new statuses — that's what kept
            // the sticky "Watching for new statuses…" notification alive
            // in the tray the whole time the app was ever opened once.
            // CallxMessagingService#showStatus() already posts the status
            // notification directly from the FCM "status" push (including
            // the app-killed case, since FCM wakes the process on its own)
            // — StatusBackgroundService's realtime listeners were fully
            // redundant with that path and never actually got started by
            // it. Left the service class/manifest entry in place in case
            // it's wired up again later; just no longer auto-started here.
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
    // WhatsApp-style background grace period: chhoti background trips
    // (notification check, 2-3 second app-switch) par turant presence/ack
    // disconnect NAHI karte — sirf agar app is duration se zyada der
    // background me rahe tabhi goOffline() fire hota hai. Foreground pe
    // wapas aane se pehle hi cancel ho jaye to koi disconnect/reconnect
    // hota hi nahi (bilkul WhatsApp jaisa "halka connection zinda" behavior).
    private static final long BACKGROUND_GRACE_MS = 2 * 60 * 1000L; // 2 minutes
    private final android.os.Handler backgroundGraceHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingGoOffline;

    private void registerForegroundTracking() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {

            @Override public void onActivityCreated(Activity a, Bundle s) {}

            @Override public void onActivityStarted(Activity a) {
                if (sActivityRefs == 0) {
                    // App is coming back to foreground within (or after) the
                    // grace window — cancel any pending offline-flip first.
                    if (pendingGoOffline != null) {
                        backgroundGraceHandler.removeCallbacks(pendingGoOffline);
                        pendingGoOffline = null;
                    }
                    // goOnline() itself no-ops if we never actually went
                    // offline (isOnline still true) — so a quick app-switch
                    // inside the grace window causes zero Firebase writes.
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
                    // All activities stopped — app going to background.
                    //
                    // Don't flip presence offline immediately: schedule it
                    // BACKGROUND_GRACE_MS out. onActivityStarted() above
                    // cancels this if the user comes back before it fires —
                    // so brief switches (notification pull-down, another
                    // app for a few seconds) never touch Firebase at all,
                    // exactly like WhatsApp keeping a light connection alive.
                    if (pendingGoOffline != null) {
                        backgroundGraceHandler.removeCallbacks(pendingGoOffline);
                    }
                    pendingGoOffline = () -> {
                        com.callx.app.utils.PresenceManager.getInstance().goOffline();
                        pendingGoOffline = null;
                    };
                    backgroundGraceHandler.postDelayed(pendingGoOffline, BACKGROUND_GRACE_MS);

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
            String uid = FirebaseUtils.getCurrentUid();
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

        // Emoji reaction notifications — background/killed-state safe (see
        // PushNotify.notifyMessageReaction / notifyGroupMessageReaction and
        // CallxMessagingService#handleMessageReaction).
        // HUN-FIX: was IMPORTANCE_DEFAULT — that's why heads-up (peeking banner)
        // never showed for reactions. Android 8+ ONLY shows heads-up when the
        // channel importance is IMPORTANCE_HIGH; NotificationCompat.Builder's
        // setPriority() is ignored on O+, only the channel importance matters.
        makeChannel(nm, Constants.CHANNEL_REACTIONS, "Message Reactions",
            NotificationManager.IMPORTANCE_HIGH, true, false, null,
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

        // ── Channel post notifications ─────────────────────────────────────
        makeChannel(nm, "channel_posts", "Channel Updates",
            NotificationManager.IMPORTANCE_DEFAULT, false, false, null,
            android.app.Notification.VISIBILITY_PUBLIC, true);
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
