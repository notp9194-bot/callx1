package com.callx.app.cache;

import android.content.Context;

/**
 * Reels module's own instance of {@link AvatarL2MemoryCache} — owner
 * avatars from ReelUiController#loadOwnerAvatarNow and AvatarPrefetcher
 * flow through this. See AvatarL2MemoryCache for why it exists and why
 * it deliberately survives TRIM_MEMORY_MODERATE.
 *
 * Registered lazily (first access, on the application context) via its
 * OWN registerComponentCallbacks call — separate from CallxApp's central
 * onTrimMemory chain and from {@link com.callx.app.cache.ChatAvatarL2Cache}'s
 * registration, so reels' trim never waits on or gets skipped by chat's.
 *
 * Sized for the owner-avatar strip (SMALL/TINY tiers only, tiny bitmaps) —
 * 80 entries comfortably covers a long single scroll session.
 */
public final class ReelsAvatarL2Cache {

    private static final int MAX_ENTRIES = 80;

    private static volatile AvatarL2MemoryCache sInstance;

    private ReelsAvatarL2Cache() {}

    public static AvatarL2MemoryCache get(Context ctx) {
        AvatarL2MemoryCache instance = sInstance;
        if (instance == null) {
            synchronized (ReelsAvatarL2Cache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarL2MemoryCache("reels", MAX_ENTRIES);
                    ctx.getApplicationContext().registerComponentCallbacks(instance);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }
}
