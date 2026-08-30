package com.callx.app.utils;

import android.app.ActivityManager;
import android.content.Context;

/**
 * Fixed avatar/cover size tiers (Instagram-style bucketing).
 *
 * Before this, every call site asked AvatarUrlBuilder for its own exact dp
 * value (22, 26, 28, 32, 36, 46, 52, 120...). Each distinct dp produced a
 * distinct Cloudinary transform segment ("w_44,h_44,..." vs "w_56,h_56,..."),
 * which means a distinct URL, which means a distinct Glide cache key — so
 * the SAME user's avatar was decoded and cached separately per screen
 * (reel player, comments, mentions, search, follow list...) even though
 * the source photo never changed.
 *
 * Bucketing every call site to one of a handful of shared tiers means a
 * given avatar is fetched/decoded ONCE per tier and reused everywhere that
 * tier is requested — e.g. a reel's owner avatar (36dp) and the follow-list
 * row avatar (46dp) now both resolve to SMALL and share one cached bitmap.
 *
 * Always round UP to the smallest tier >= the view's actual size (see
 * forViewSizeDp) — never down, or the image will visibly under-resolve.
 */
public enum AvatarSizeTier {
    TINY(32),
    SMALL(48),
    MEDIUM(64),
    LARGE(96),
    XLARGE(150);

    public final int dp;

    AvatarSizeTier(int dp) {
        this.dp = dp;
    }

    /** Smallest tier whose dp size is >= viewSizeDp, so callers never under-resolve. */
    public static AvatarSizeTier forViewSizeDp(int viewSizeDp) {
        for (AvatarSizeTier tier : values()) {
            if (tier.dp >= viewSizeDp) return tier;
        }
        return XLARGE;
    }

    /** One step smaller, or itself if already the smallest tier (TINY). */
    public AvatarSizeTier downgraded() {
        AvatarSizeTier[] vals = values();
        int idx = ordinal();
        return idx > 0 ? vals[idx - 1] : this;
    }

    /**
     * FIX (device-memory-class multiplier): on a low-RAM device, resolve
     * one tier smaller than what the view asked for — e.g. a MEDIUM (64dp)
     * request decodes/holds SMALL (48dp) pixels instead. This is separate
     * from AvatarUrlBuilder's density bucketing (which controls sharpness
     * per screen density) — this controls the raw pixel BUDGET per avatar
     * bitmap, cut for devices where memory pressure and eviction churn
     * already dominate and an extra tier of resolution is wasted cost
     * more often than it's a visible improvement. Never below TINY.
     *
     * ALWAYS route requested tiers through this before building a URL or
     * an override() size — see AvatarUrlBuilder#tierPx, the single choke
     * point both build() overloads funnel through, so the CDN-requested
     * size and the Glide decode size can never drift apart.
     */
    public static AvatarSizeTier effectiveTier(Context ctx, AvatarSizeTier requested) {
        return isLowRamDevice(ctx) ? requested.downgraded() : requested;
    }

    // ActivityManager#isLowRamDevice() is a cheap call but there's no reason
    // to repeat it on every single avatar bind — it can't change mid-process.
    private static volatile Boolean sIsLowRam;

    private static boolean isLowRamDevice(Context ctx) {
        Boolean cached = sIsLowRam;
        if (cached != null) return cached;
        boolean result = false;
        try {
            ActivityManager am = (ActivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) result = am.isLowRamDevice();
        } catch (Exception ignored) {}
        sIsLowRam = result;
        return result;
    }
}
