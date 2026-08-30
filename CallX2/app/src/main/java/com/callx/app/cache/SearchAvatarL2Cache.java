package com.callx.app.cache;

import android.content.Context;

/**
 * Search results' own instance of {@link AvatarL2MemoryCache} — avatars
 * bound by {@link SearchAvatarBinder} in SearchResultAdapter flow through
 * this. See {@link AvatarL2MemoryCache} for why it exists and why it
 * deliberately survives TRIM_MEMORY_MODERATE.
 *
 * Registered lazily (first access, on the application context) with its OWN
 * registerComponentCallbacks call — independent of ReelsAvatarL2Cache's /
 * ChatAvatarL2Cache's / StatusAvatarL2Cache's / ProfileAvatarL2Cache's
 * registration, so a heavy trim in any other module can never delay or skip
 * search's, and vice versa.
 *
 * Sized like the other scrolled-list caches (Reels 80 / Chat list rows
 * share Chat's), NOT like ProfileAvatarL2Cache's "handful of avatars per
 * screen" 24 — a live-as-you-type search session can scroll through many
 * distinct users in one sitting, same shape as the reel/chat lists.
 */
public final class SearchAvatarL2Cache {

    private static final int MAX_ENTRIES = 64;

    private static volatile AvatarL2MemoryCache sInstance;

    private SearchAvatarL2Cache() {}

    public static AvatarL2MemoryCache get(Context ctx) {
        AvatarL2MemoryCache instance = sInstance;
        if (instance == null) {
            synchronized (SearchAvatarL2Cache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarL2MemoryCache("search", MAX_ENTRIES);
                    ctx.getApplicationContext().registerComponentCallbacks(instance);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ── L3 (disk, survives process death) — separate folder + instance
    // from every other module's L3, same independent-per-module reasoning.
    private static final long L3_MAX_BYTES = 1L * 1024 * 1024; // 1 MB — small resized tiles only
    private static volatile AvatarL3DiskCache sL3;

    public static AvatarL3DiskCache l3(Context ctx) {
        AvatarL3DiskCache instance = sL3;
        if (instance == null) {
            synchronized (SearchAvatarL2Cache.class) {
                instance = sL3;
                if (instance == null) {
                    instance = new AvatarL3DiskCache(ctx, "search", L3_MAX_BYTES);
                    sL3 = instance;
                }
            }
        }
        return instance;
    }
}
