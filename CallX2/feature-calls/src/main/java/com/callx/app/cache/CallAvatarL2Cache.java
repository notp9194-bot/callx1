package com.callx.app.cache;

import android.content.Context;

/**
 * Calls module's own instance of {@link AvatarL2MemoryCache} — call history
 * rows (CallHistoryAdapter), the contact bottom sheet (CallsFragment), the
 * in-call screen (CallActivity), and the incoming group-call screen
 * (IncomingGroupCallActivity) all flow through this. See
 * AvatarL2MemoryCache for why it exists and why it deliberately survives
 * TRIM_MEMORY_MODERATE (only cleared on COMPLETE).
 *
 * Registered lazily (first access, on the application context) with its OWN
 * registerComponentCallbacks call — independent of ChatAvatarL2Cache /
 * ReelsAvatarL2Cache / StatusAvatarL2Cache / ProfileAvatarL2Cache /
 * SearchAvatarL2Cache's registrations, so a heavy trim triggered by, say,
 * the reels feed scrolling through video frames can never delay or skip
 * the calls list's warm-restart cache, and vice versa — each module trims
 * independently per onTrimMemory's per-module design.
 *
 * Sized for a call log list (a screenful of ~15-20 rows plus the handful of
 * single avatars in the bottom sheets / in-call screen) — smaller than
 * reels/chat's list caches, larger than the tiny avatar-stack caches.
 */
public final class CallAvatarL2Cache {

    private static final int MAX_ENTRIES = 64;

    private static volatile AvatarL2MemoryCache sInstance;

    private CallAvatarL2Cache() {}

    public static AvatarL2MemoryCache get(Context ctx) {
        AvatarL2MemoryCache instance = sInstance;
        if (instance == null) {
            synchronized (CallAvatarL2Cache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarL2MemoryCache("calls", MAX_ENTRIES);
                    ctx.getApplicationContext().registerComponentCallbacks(instance);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ── L3 (disk, survives process death) — own folder + instance, same
    // independent-per-module reasoning as the L2 cache above.
    private static final long L3_MAX_BYTES = 1L * 1024 * 1024; // 1 MB — small partner-avatar set

    private static volatile AvatarL3DiskCache sL3;

    public static AvatarL3DiskCache l3(Context ctx) {
        AvatarL3DiskCache instance = sL3;
        if (instance == null) {
            synchronized (CallAvatarL2Cache.class) {
                instance = sL3;
                if (instance == null) {
                    instance = new AvatarL3DiskCache(ctx, "calls", L3_MAX_BYTES);
                    sL3 = instance;
                }
            }
        }
        return instance;
    }
}
