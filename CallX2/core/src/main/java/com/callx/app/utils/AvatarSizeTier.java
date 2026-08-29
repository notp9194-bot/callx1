package com.callx.app.utils;

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
}
