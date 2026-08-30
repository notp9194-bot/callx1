package com.callx.app.cache;

import android.content.Context;

/**
 * Shared {@link AvatarL2MemoryCache} instance for the app's "misc" screens —
 * AccountMenuActivity, BlockedUsersActivity, ContactsActivity,
 * AllNotificationsActivity, NotificationCenterActivity,
 * CreateBroadcastActivity, MutedChatsActivity and
 * GlobalSavedMessagesActivity — all flow through this via
 * {@link MiscAvatarBinder}. See {@link AvatarL2MemoryCache} for why it
 * exists and why it deliberately survives TRIM_MEMORY_MODERATE.
 *
 * GAP THIS CLOSES: every one of these 8 screens previously loaded avatars
 * with a bare {@code Glide.with().load()} — no shared {@link
 * com.callx.app.utils.AvatarSizeTier} bucket (each screen hardcoded its own
 * flat .override() px, e.g. 96/240/720), no L2/L3 reuse, no lifecycle-aware
 * cancel, and no velocity-based prefetch — unlike Profile/Search/Status/
 * Chat/Follow/Reels, which already had all of that. This is that group's
 * shared cache pair, one L2 + one L3 for the whole "misc" bucket (these 8
 * screens are one-off utility/settings screens, not a family of screens
 * each warranting their own independently-sized cache the way
 * Reels/Chat/Status/Profile do).
 *
 * Registered lazily (first access, on the application context) with its OWN
 * registerComponentCallbacks call — independent of every other module's
 * registration, so a heavy trim in Reels/Chat/Status/Profile can never delay
 * or skip this one, and vice versa.
 */
public final class MiscAvatarL2Cache {

    private static final int MAX_ENTRIES = 64;

    private static volatile AvatarL2MemoryCache sInstance;

    private MiscAvatarL2Cache() {}

    public static AvatarL2MemoryCache get(Context ctx) {
        AvatarL2MemoryCache instance = sInstance;
        if (instance == null) {
            synchronized (MiscAvatarL2Cache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarL2MemoryCache("misc", MAX_ENTRIES);
                    ctx.getApplicationContext().registerComponentCallbacks(instance);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ── L3 (disk, survives process death) — separate folder + instance
    // from every other module's L3, same independent-per-module reasoning.
    private static final long L3_MAX_BYTES = 1L * 1024 * 1024; // 1 MB — small resized tiles across 8 low-traffic screens
    private static volatile AvatarL3DiskCache sL3;

    public static AvatarL3DiskCache l3(Context ctx) {
        AvatarL3DiskCache instance = sL3;
        if (instance == null) {
            synchronized (MiscAvatarL2Cache.class) {
                instance = sL3;
                if (instance == null) {
                    instance = new AvatarL3DiskCache(ctx, "misc", L3_MAX_BYTES);
                    sL3 = instance;
                }
            }
        }
        return instance;
    }
}
