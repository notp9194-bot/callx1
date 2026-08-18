package com.callx.app.utils;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

/**
 * CodecSupport — detects whether this device has a hardware decoder for
 * HEVC (H.265) or AV1, so the reels player can request the smallest/most
 * efficient Cloudinary-encoded stream the device can actually decode
 * without falling back to (slow, battery-heavy) software decoding.
 *
 * Result is cached after the first check — MediaCodecList enumeration is
 * a few ms of work, no need to repeat it on every reel/video load.
 */
public final class CodecSupport {

    private static final String TAG = "CodecSupport";

    private static volatile String cachedPreferredCodec; // "av01" | "h265" | "auto"

    // Bug fix: if a codec-transformed stream fails to play (Cloudinary account/plan
    // doesn't actually support on-the-fly vc_av01/vc_h265 transcoding for this asset,
    // or the server rejects the transform), we disable codec-forcing for the rest of
    // the session so every other reel doesn't hit the same dead end. Without this,
    // one unsupported transform could make ALL reels fail to play.
    private static volatile boolean disabledForSession = false;

    private CodecSupport() {}

    /**
     * Returns the best Cloudinary video-codec transform value (vc_<value>)
     * this device can hardware-decode: "av01" (AV1) preferred for the best
     * bandwidth savings, then "h265" (HEVC), else "auto" (let Cloudinary /
     * the player negotiate — effectively today's H.264 default).
     */
    public static String preferredVideoCodec() {
        if (disabledForSession) return "auto";
        String cached = cachedPreferredCodec;
        if (cached != null) return cached;
        synchronized (CodecSupport.class) {
            if (cachedPreferredCodec != null) return cachedPreferredCodec;
            String result;
            try {
                if (hasDecoderFor("video/av01")) {
                    result = "av01";
                } else if (hasDecoderFor("video/hevc")) {
                    result = "h265";
                } else {
                    result = "auto";
                }
            } catch (Exception e) {
                Log.w(TAG, "Codec probe failed, falling back to auto: " + e.getMessage());
                result = "auto";
            }
            cachedPreferredCodec = result;
            return result;
        }
    }

    /**
     * Single source of truth for turning a chosen Cloudinary progressive URL
     * into the codec-transformed playback URL — used by the player AND every
     * preloader so they compute the identical string (and therefore the same
     * CacheDataSource cache key). If the preloader and the player ever derive
     * different URLs for the same reel/quality, preloaded bytes are cached
     * under one key while playback requests another — every reel then
     * downloads twice (once wasted by the preloader, once again for real
     * playback), which is the #1 cause of runaway data usage after enabling
     * codec forcing. Always call this instead of hand-rolling vc_ logic.
     */
    public static String applyToUrl(String url) {
        if (url == null || url.isEmpty()) return url;
        if (url.contains(".m3u8") || url.contains(".mpd")) return url; // HLS/DASH already codec-laddered
        String codec = preferredVideoCodec();
        if ("auto".equals(codec)) return url; // no hardware decoder, or disabled after a failure
        return com.callx.app.utils.CloudinaryUploader.deriveVideoCodecUrl(url, codec);
    }

    /**
     * Called from the player's error handler when a codec-transformed stream
     * fails to load. Turns codec-forcing off for the rest of this app
     * session (cheap, in-memory — re-probes fresh next launch) so the
     * player and every preloader immediately fall back to plain URLs
     * instead of repeatedly hitting the same broken transform.
     */
    public static void disableForSession() {
        disabledForSession = true;
        cachedPreferredCodec = "auto";
        Log.w(TAG, "Codec forcing disabled for this session after a playback failure");
    }

    public static boolean isDisabledForSession() {
        return disabledForSession;
    }

    private static boolean hasDecoderFor(String mimeType) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mimeType)) return true;
            }
        }
        return false;
    }
}
