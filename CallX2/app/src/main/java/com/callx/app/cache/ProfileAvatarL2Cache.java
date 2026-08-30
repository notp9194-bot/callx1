package com.callx.app.cache;

import android.content.Context;

/**
 * Profile screens' own instance of {@link AvatarL2MemoryCache} — the hero
 * avatar on ProfileActivity (own profile, 120dp iv_avatar) and
 * UserProfileActivity (partner profile, 90dp iv_avatar_large), plus the
 * three small Reel/X/YouTube "peek" avatars on UserProfileActivity, all
 * flow through this via {@link ProfileAvatarBinder}. See
 * {@link AvatarL2MemoryCache} for why it exists and why it deliberately
 * survives TRIM_MEMORY_MODERATE.
 *
 * GAP THIS CLOSES: both profile screens previously had none of this —
 * every avatar bind loaded straight through Glide with only an
 * {@link AvatarCacheAnalytics} listener wired (CDN/cache-tier *monitoring*,
 * not an actual L2/L3 tier to monitor), and UserProfileActivity didn't even
 * go through {@link com.callx.app.utils.AvatarUrlBuilder} — flat
 * ".override(720,720)"/".override(240,240)" everywhere, so the same user's
 * avatar produced a different CDN URL/cache-key than every other screen in
 * the app (reel player, comments, chat list...) that already shares one via
 * a bucketed {@link com.callx.app.utils.AvatarSizeTier}. Reels, Chat and
 * Status all already had this exact L2+L3 pair (ReelsAvatarL2Cache /
 * ChatAvatarL2Cache / StatusAvatarL2Cache); Profile was the one screen pair
 * still missing it.
 *
 * Registered lazily (first access, on the application context) with its OWN
 * registerComponentCallbacks call — independent of Reels'/Chat's/Status's
 * registration, so a heavy trim in any other module can never delay or skip
 * Profile's, and vice versa.
 *
 * Sized smaller than the list-screen caches (Reels 80 / Chat / Status 64) —
 * a profile screen only ever shows a handful of distinct avatars per
 * session (the hero avatar, plus the 3 peek avatars on UserProfileActivity),
 * never an open-ended scrolled list.
 */
public final class ProfileAvatarL2Cache {

    private static final int MAX_ENTRIES = 24;

    private static volatile AvatarL2MemoryCache sInstance;

    private ProfileAvatarL2Cache() {}

    public static AvatarL2MemoryCache get(Context ctx) {
        AvatarL2MemoryCache instance = sInstance;
        if (instance == null) {
            synchronized (ProfileAvatarL2Cache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarL2MemoryCache("profile", MAX_ENTRIES);
                    ctx.getApplicationContext().registerComponentCallbacks(instance);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ── L3 (disk, survives process death) — separate folder + instance
    // from every other module's L3, same independent-per-module reasoning.
    private static final long L3_MAX_BYTES = 512L * 1024; // 512 KB — a handful of small resized tiles only
    private static volatile AvatarL3DiskCache sL3;

    public static AvatarL3DiskCache l3(Context ctx) {
        AvatarL3DiskCache instance = sL3;
        if (instance == null) {
            synchronized (ProfileAvatarL2Cache.class) {
                instance = sL3;
                if (instance == null) {
                    instance = new AvatarL3DiskCache(ctx, "profile", L3_MAX_BYTES);
                    sL3 = instance;
                }
            }
        }
        return instance;
    }
}
