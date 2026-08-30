package com.callx.app.cache;

import android.content.Context;

/**
 * YouTube module's own instance of {@link AvatarL2MemoryCache} — channel
 * screen (YouTubeChannelActivity), subscriber list (YouTubeSubscribersActivity),
 * comments (YouTubeCommentAdapter), shorts feed (YouTubeShortsFragment), and
 * the video player screen (YouTubePlayerActivity) all flow through this.
 * See AvatarL2MemoryCache for why it exists and why it deliberately survives
 * TRIM_MEMORY_MODERATE (only cleared on COMPLETE).
 *
 * Registered lazily (first access, on the application context) with its OWN
 * registerComponentCallbacks call — independent of CallAvatarL2Cache /
 * ChatAvatarL2Cache / ReelsAvatarL2Cache / StatusAvatarL2Cache /
 * ProfileAvatarL2Cache / SearchAvatarL2Cache's registrations, so a heavy
 * trim triggered by, say, ExoPlayer decoding shorts video frames can never
 * delay or skip another module's warm-restart cache, and vice versa — each
 * module trims independently per onTrimMemory's per-module design.
 *
 * Sized for a comments/subscribers list screenful plus the handful of
 * single avatars on the channel/player screens.
 */
public final class YouTubeAvatarL2Cache {

    private static final int MAX_ENTRIES = 64;

    private static volatile AvatarL2MemoryCache sInstance;

    private YouTubeAvatarL2Cache() {}

    public static AvatarL2MemoryCache get(Context ctx) {
        AvatarL2MemoryCache instance = sInstance;
        if (instance == null) {
            synchronized (YouTubeAvatarL2Cache.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarL2MemoryCache("youtube", MAX_ENTRIES);
                    ctx.getApplicationContext().registerComponentCallbacks(instance);
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    // ── L3 (disk, survives process death) — own folder + instance, same
    // independent-per-module reasoning as the L2 cache above.
    private static final long L3_MAX_BYTES = 1L * 1024 * 1024; // 1 MB — small channel-avatar set

    private static volatile AvatarL3DiskCache sL3;

    public static AvatarL3DiskCache l3(Context ctx) {
        AvatarL3DiskCache instance = sL3;
        if (instance == null) {
            synchronized (YouTubeAvatarL2Cache.class) {
                instance = sL3;
                if (instance == null) {
                    instance = new AvatarL3DiskCache(ctx, "youtube", L3_MAX_BYTES);
                    sL3 = instance;
                }
            }
        }
        return instance;
    }
}
