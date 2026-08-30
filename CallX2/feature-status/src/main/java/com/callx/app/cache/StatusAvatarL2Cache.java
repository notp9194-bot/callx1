package com.callx.app.cache;

import android.content.Context;

/**
 * Status module's own instance of {@link AvatarL2MemoryCache} — the status
 * tray/list avatars (StatusListAdapter's My-Status row, contact rows, and
 * the horizontal WhatsApp-style card carousel) flow through this. See
 * AvatarL2MemoryCache for why it exists and why it deliberately survives
 * TRIM_MEMORY_MODERATE.
 *
 * GAP THIS CLOSES: Status previously had none of this — StatusListAdapter's
 * three avatar bind sites (bindMyStatus, bindContact, StatusCardAdapter)
 * loaded the raw ownerPhoto straight through Glide with only an
 * AvatarCacheAnalytics listener wired (CDN/cache-tier *monitoring*, not an
 * actual L2/L3 tier to monitor) — no shared AvatarSizeTier bucketing, no
 * density-aware/WebP-AVIF transform, no warm-restart-surviving memory tier,
 * no disk tier for cold starts. Reels and Chat both already had this exact
 * L2+L3 pair (see ReelsAvatarL2Cache / ChatAvatarL2Cache); Status was the
 * one list screen still missing it.
 *
 * Registered lazily (first access, on the application context) with its OWN
 * registerComponentCallbacks call — independent of ReelsAvatarL2Cache's and
 * ChatAvatarL2Cache's registration, so a heavy trim in reels or chat can
 * never delay or skip status's, and vice versa.
 *
 * Sized like Chat's (avatar rows only, no video-tile-sized bitmaps) —
 * the status tray is typically a short contact list per session.
 */
public final class StatusAvatarL2Cache {

    private static final int MAX_ENTRIES = 64;

    private static volatile AvatarL2MemoryCache sInstance;

    private StatusAvatarL2Cache() {}

    public static AvatarL2MemoryCache get(Context ctx) {
        AvatarL2MemoryCache instance = sInstance;
        if (instance == null) {
            synchronized (StatusAvatarL2Cache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarL2MemoryCache("status", MAX_ENTRIES);
                    ctx.getApplicationContext().registerComponentCallbacks(instance);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ── L3 (disk, survives process death) — separate folder + instance
    // from Reels'/Chat's L3, same independent-per-module reasoning.
    private static final long L3_MAX_BYTES = 1L * 1024 * 1024; // 1 MB — small resized avatar tiles only
    private static volatile AvatarL3DiskCache sL3;

    public static AvatarL3DiskCache l3(Context ctx) {
        AvatarL3DiskCache instance = sL3;
        if (instance == null) {
            synchronized (StatusAvatarL2Cache.class) {
                instance = sL3;
                if (instance == null) {
                    instance = new AvatarL3DiskCache(ctx, "status", L3_MAX_BYTES);
                    sL3 = instance;
                }
            }
        }
        return instance;
    }
}
