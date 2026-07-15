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

    private CodecSupport() {}

    /**
     * Returns the best Cloudinary video-codec transform value (vc_<value>)
     * this device can hardware-decode: "av01" (AV1) preferred for the best
     * bandwidth savings, then "h265" (HEVC), else "auto" (let Cloudinary /
     * the player negotiate — effectively today's H.264 default).
     */
    public static String preferredVideoCodec() {
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
