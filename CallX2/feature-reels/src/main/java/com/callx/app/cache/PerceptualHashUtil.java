package com.callx.app.cache;

import android.graphics.Bitmap;

/**
 * PerceptualHashUtil — cheap luma difference-hash (dHash) for comparing the
 * server-generated thumbnail against the video's actual decoded first frame.
 *
 * Used by revealCardThumbnailAfterFirstFrame() (HomeFragment) /
 * revealThumbnailAfterFirstFrame() (ReelPlayerController) to pick the
 * crossfade duration: a close visual match means the eye has nothing to
 * catch mid-fade, so the swap can be much shorter without reading as a cut;
 * a real mismatch (different crop/lighting/frame) keeps the fuller duration
 * so the transition still reads as smooth rather than a jarring pop.
 *
 * dHash, not a pixel-diff or full perceptual hash (pHash/DCT) — resizing to
 * a tiny 9×8 grid and comparing adjacent-pixel luma gradients is close to
 * free (a few microseconds) and is deliberately tolerant of the thumbnail
 * and decoded frame not being byte-identical (different JPEG compression,
 * a slightly different sub-second timestamp, minor color pipeline
 * differences) while still catching a genuinely different frame/scene.
 */
public final class PerceptualHashUtil {
    private PerceptualHashUtil() {}

    // 9 columns → 8 horizontal luma-gradient bits per row × 8 rows = 64-bit hash
    private static final int HASH_GRID_W = 9;
    private static final int HASH_GRID_H = 8;

    /** Hamming distance ≤ this (out of 64 bits) counts as a "close match" —
     *  i.e. the thumbnail and the decoded frame are visually close enough
     *  that a short crossfade won't read as a cut. */
    public static final int SIMILAR_THRESHOLD = 12;

    /** Computes a 64-bit dHash from the given bitmap. Returns 0 if the
     *  bitmap is null/unusable — callers should treat a hash of 0 for both
     *  sides as "unknown" rather than "identical" by checking for null
     *  bitmaps before calling, not by comparing against 0L. */
    public static long dHash(Bitmap src) {
        if (src == null || src.isRecycled() || src.getWidth() <= 0 || src.getHeight() <= 0) return 0L;
        Bitmap small;
        try {
            small = Bitmap.createScaledBitmap(src, HASH_GRID_W, HASH_GRID_H, true);
        } catch (Exception e) {
            return 0L;
        }
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < HASH_GRID_H; y++) {
            for (int x = 0; x < HASH_GRID_W - 1; x++) {
                int lumaLeft  = luma(small.getPixel(x, y));
                int lumaRight = luma(small.getPixel(x + 1, y));
                if (lumaLeft > lumaRight) hash |= (1L << bit);
                bit++;
            }
        }
        if (small != src) small.recycle();
        return hash;
    }

    /** Rec. 601 luma from an ARGB pixel — cheap integer math, no color-space conversion needed. */
    private static int luma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8)  & 0xFF;
        int b = argb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
