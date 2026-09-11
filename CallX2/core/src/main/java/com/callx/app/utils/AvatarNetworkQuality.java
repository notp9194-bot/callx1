package com.callx.app.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

/**
 * FIX (#14 — adaptive quality per network): AvatarUrlBuilder used to always
 * request a flat "q_auto" from Cloudinary no matter what the device's
 * actual connection looked like — fine on WiFi/4G+, but on a genuinely slow
 * 2G/3G link that same q_auto-compressed byte size measurably adds to
 * time-to-first-paint for every avatar on screen (chat list, follow list,
 * reel comments — anywhere several avatars bind at once). This class buckets
 * the CURRENT connection into GOOD/MODERATE/POOR once per short window, and
 * {@link AvatarUrlBuilder} maps that bucket to a cheaper Cloudinary
 * q_auto:<level> on the slower buckets. Only the compression target
 * changes — tier/dpr pixel dimensions are untouched — so layout never
 * shifts, just the byte size the CDN sends down.
 *
 * Deliberately permission-free: this reads {@link NetworkCapabilities}'
 * OS-reported link-bandwidth ESTIMATE, not TelephonyManager's exact radio
 * generation (getNetworkType()/getDataNetworkType() need READ_PHONE_STATE
 * on many OEM/API combinations — a runtime-dangerous permission this
 * courtesy feature doesn't justify asking for). The bandwidth estimate
 * correlates closely enough with radio generation for a coarse 3-bucket
 * split without a new permission prompt, and — same as
 * AvatarBinderCore#isNetworkPrefetchAllowed — fails OPEN (GOOD) on anything
 * unexpected, since the worst case of failing open is a slightly bigger
 * image, not a silently degraded experience forever on some device/OEM.
 *
 * Cached for {@link #CACHE_TTL_MS}: avatar URLs are built once per row per
 * scroll pass, sometimes dozens of times a second during a fast fling —
 * re-querying ConnectivityManager for every single avatar would be wasted
 * binder-call overhead for a value that essentially never changes mid-scroll.
 */
public final class AvatarNetworkQuality {

    private AvatarNetworkQuality() {}

    public enum Bucket { GOOD, MODERATE, POOR }

    // Downstream bandwidth ESTIMATE thresholds (kbps) — NetworkCapabilities'
    // own class-based estimate, not a live speed test. Roughly: below the
    // first, the link behaves like 2G; below the second, like 3G.
    private static final int POOR_CEILING_KBPS = 400;
    private static final int MODERATE_CEILING_KBPS = 4000;

    private static final long CACHE_TTL_MS = 4000L;
    private static volatile Bucket sCached = Bucket.GOOD;
    private static volatile long sCachedAtMs = 0L;

    public static Bucket current(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (now - sCachedAtMs < CACHE_TTL_MS) return sCached;
        Bucket detected = detect(ctx);
        sCached = detected;
        sCachedAtMs = now;
        return detected;
    }

    private static Bucket detect(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getApplicationContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return Bucket.GOOD;
            Network net = cm.getActiveNetwork();
            if (net == null) return Bucket.GOOD; // no active network — real offline is handled elsewhere, not here
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            if (caps == null) return Bucket.GOOD;

            // WiFi/Ethernet never gets downgraded regardless of the reported
            // bandwidth number (which can be a conservative OEM default) —
            // this feature only exists to help cellular's genuinely slow tail.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return Bucket.GOOD;
            }

            int downKbps = caps.getLinkDownstreamBandwidthKbps();
            if (downKbps <= 0) return Bucket.GOOD; // unreported — fail open
            if (downKbps < POOR_CEILING_KBPS) return Bucket.POOR;
            if (downKbps < MODERATE_CEILING_KBPS) return Bucket.MODERATE;
            return Bucket.GOOD;
        } catch (Exception e) {
            return Bucket.GOOD;
        }
    }

    /** Cloudinary q_auto level for a bucket — pairs with
     *  {@link AvatarUrlBuilder#bestFormatParam}'s sibling doc; only the
     *  compression target changes, tier/dpr px stay identical either way. */
    public static String qAutoParam(Bucket bucket) {
        switch (bucket) {
            case POOR:     return "q_auto:low";
            case MODERATE: return "q_auto:eco";
            default:       return "q_auto";
        }
    }
}
