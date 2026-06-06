package com.callx.app.gif;

import com.giphy.sdk.core.models.Media;
import com.giphy.sdk.core.models.Images;
import com.giphy.sdk.core.models.Image;

/**
 * GifUtils — safe URL extraction for GIPHY SDK 2.3.14.
 * All getters null-checked; returns "" on any failure.
 */
public class GifUtils {

    /**
     * Best URL to send as the GIF message body.
     * Priority: downsized gif → fixed_width gif → original gif.
     */
    public static String getGifUrl(Media gif) {
        if (gif == null) return "";
        try {
            Images imgs = gif.getImages();
            if (imgs == null) return "";

            // downsized — smaller file, still animated
            Image downsized = imgs.getDownsized();
            if (downsized != null && notEmpty(downsized.getGifUrl()))
                return downsized.getGifUrl();

            // fixed_width
            Image fixedWidth = imgs.getFixedWidth();
            if (fixedWidth != null && notEmpty(fixedWidth.getGifUrl()))
                return fixedWidth.getGifUrl();

            // original fallback
            Image original = imgs.getOriginal();
            if (original != null && notEmpty(original.getGifUrl()))
                return original.getGifUrl();

        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Static preview thumbnail — used as Glide placeholder before GIF animates.
     * Priority: fixedWidthStill → fixedWidthSmall → downsized.
     */
    public static String getPreviewUrl(Media gif) {
        if (gif == null) return "";
        try {
            Images imgs = gif.getImages();
            if (imgs == null) return "";

            // fixed_width_still — static jpg/webp, very fast
            Image still = imgs.getFixedWidthStill();
            if (still != null && notEmpty(still.getGifUrl()))
                return still.getGifUrl();

            // fixed_width_small — small animated, good for grid thumb
            Image small = imgs.getFixedWidthSmall();
            if (small != null && notEmpty(small.getGifUrl()))
                return small.getGifUrl();

            // downsized fallback
            Image downsized = imgs.getDownsized();
            if (downsized != null && notEmpty(downsized.getGifUrl()))
                return downsized.getGifUrl();

        } catch (Exception ignored) {}
        return "";
    }

    /** URL to use in grid thumbnail (small + fast). */
    public static String getThumbUrl(Media gif) {
        if (gif == null) return "";
        try {
            Images imgs = gif.getImages();
            if (imgs == null) return "";

            Image small = imgs.getFixedWidthSmall();
            if (small != null && notEmpty(small.getGifUrl()))
                return small.getGifUrl();

            Image still = imgs.getFixedWidthStill();
            if (still != null && notEmpty(still.getGifUrl()))
                return still.getGifUrl();

        } catch (Exception ignored) {}
        return getGifUrl(gif);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
