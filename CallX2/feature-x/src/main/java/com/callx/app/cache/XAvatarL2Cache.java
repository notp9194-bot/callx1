package com.callx.app.cache;

import android.content.Context;

/**
 * X module's own instance of {@link AvatarL2MemoryCache} — the home feed
 * (XTweetAdapter), notifications (XNotificationAdapter), DM list
 * (XMessagePreviewAdapter), DM conversation header (XDMConversationActivity),
 * tweet detail (XTweetDetailActivity), profile sheet (XProfileSheet), explore
 * "who to follow" cards and user search rows (XExploreFragment,
 * XSearchActivity), and the blocked/muted user lists (XBlockedUsersActivity)
 * all flow through this. See AvatarL2MemoryCache for why it exists and why
 * it deliberately survives TRIM_MEMORY_MODERATE (only cleared on COMPLETE).
 *
 * Registered lazily (first access, on the application context) with its OWN
 * registerComponentCallbacks call — independent of every other module's
 * *AvatarL2Cache registration (Chat/Calls/Reels/Status/Profile/Search/
 * YouTube), so a heavy trim triggered by, say, the reels player decoding
 * video frames can never delay or skip this module's warm-restart cache,
 * and vice versa — each module trims independently per onTrimMemory's
 * per-module design.
 *
 * Sized for a feed/notifications/DM-list screenful plus the handful of
 * single avatars on detail/header/sheet screens.
 */
public final class XAvatarL2Cache {

    private static final int MAX_ENTRIES = 96;

    private static volatile AvatarL2MemoryCache sInstance;

    private XAvatarL2Cache() {}

    public static AvatarL2MemoryCache get(Context ctx) {
        AvatarL2MemoryCache instance = sInstance;
        if (instance == null) {
            synchronized (XAvatarL2Cache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarL2MemoryCache("x", MAX_ENTRIES);
                    ctx.getApplicationContext().registerComponentCallbacks(instance);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ── L3 (disk, survives process death) — own folder + instance, same
    // independent-per-module reasoning as the L2 cache above.
    private static final long L3_MAX_BYTES = 2L * 1024 * 1024; // 2 MB — feed + DM + notif avatar set

    private static volatile AvatarL3DiskCache sL3;

    public static AvatarL3DiskCache l3(Context ctx) {
        AvatarL3DiskCache instance = sL3;
        if (instance == null) {
            synchronized (XAvatarL2Cache.class) {
                instance = sL3;
                if (instance == null) {
                    instance = new AvatarL3DiskCache(ctx, "x", L3_MAX_BYTES);
                    sL3 = instance;
                }
            }
        }
        return instance;
    }
}
