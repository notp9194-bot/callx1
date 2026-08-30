package com.callx.app.cache;

import android.content.Context;

/**
 * Chat module's own instance of {@link AvatarL2MemoryCache} — community
 * member avatars (CommunityMemberAvatarStackView, CommunityAvatarPreloader)
 * flow through this. See AvatarL2MemoryCache for why it exists and why it
 * deliberately survives TRIM_MEMORY_MODERATE.
 *
 * Registered lazily (first access, on the application context) with its
 * OWN registerComponentCallbacks call — independent of
 * {@link com.callx.app.cache.ReelsAvatarL2Cache}'s registration, so a
 * heavy trim in reels can never delay or skip chat's, and vice versa.
 *
 * Sized smaller than reels' — avatar stacks only ever show a handful of
 * members per screen (MAX_VISIBLE = 4 in CommunityMemberAvatarStackView).
 */
public final class ChatAvatarL2Cache {

    private static final int MAX_ENTRIES = 48;

    private static volatile AvatarL2MemoryCache sInstance;

    private ChatAvatarL2Cache() {}

    public static AvatarL2MemoryCache get(Context ctx) {
        AvatarL2MemoryCache instance = sInstance;
        if (instance == null) {
            synchronized (ChatAvatarL2Cache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarL2MemoryCache("chat", MAX_ENTRIES);
                    ctx.getApplicationContext().registerComponentCallbacks(instance);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ── L3 (disk, survives process death) — separate folder + instance
    // from ReelsAvatarL2Cache's L3, same independent-per-module reasoning.
    private static final long L3_MAX_BYTES = 1L * 1024 * 1024; // 1 MB — a handful of tiny member tiles
    private static volatile AvatarL3DiskCache sL3;

    public static AvatarL3DiskCache l3(Context ctx) {
        AvatarL3DiskCache instance = sL3;
        if (instance == null) {
            synchronized (ChatAvatarL2Cache.class) {
                instance = sL3;
                if (instance == null) {
                    instance = new AvatarL3DiskCache(ctx, "chat", L3_MAX_BYTES);
                    sL3 = instance;
                }
            }
        }
        return instance;
    }
}
