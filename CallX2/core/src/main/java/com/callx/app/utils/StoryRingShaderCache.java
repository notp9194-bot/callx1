package com.callx.app.utils;

import android.graphics.Matrix;
import android.graphics.SweepGradient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * StoryRingShaderCache — v40 "reverse advance" optimization.
 *
 * WHY: StoryRingGradientDrawable / ChatListStoryRingView were each building a
 * brand-new SweepGradient + Matrix on every bounds/size change. In a
 * RecyclerView (chat list, status row, reel comments, home feed) that means
 * a fresh shader + matrix allocation on almost every bind/recycle, even
 * though 95% of rings on screen share the exact same pixel size (e.g. every
 * avatar in the chat list is 48dp). That's needless GC churn = jank.
 *
 * FIX: All story rings now share ONE small LRU cache keyed by (width,height).
 * The first ring of a given size builds the shader once; every other ring of
 * that same size (every other row in the list) reuses the identical
 * SweepGradient object. Android Shaders are stateless/immutable once built,
 * so sharing one instance across many Paints on many views is safe.
 *
 * Cache is capped at 8 distinct sizes (small avatars, story feed thumbs,
 * reel/profile rings, etc.) — far more than the app actually uses at once —
 * so it can never grow unbounded.
 */
public final class StoryRingShaderCache {

    private StoryRingShaderCache() {}

    // Story ring gradient stops — Purple → Pink/Magenta → Orange → Yellow,
    // matching the app's brand colour-flow spec (percentages measured from
    // the top, clockwise): Purple 25% (0-25%), Pink/Magenta 20% (25-45%),
    // Orange 20% (45-65%), Yellow 35% (65-100%). Palindromed (first color ==
    // last color) so the sweep closes the loop with zero seam.
    private static final int[] INSTA_GRADIENT_COLORS = {
        0xFF7D00FF, // purple   (0%)
        0xFFFF2D7A, // pink/magenta (25%)
        0xFFFF8A00, // orange   (45%)
        0xFFFFD600, // yellow   (65%)
        0xFF7D00FF  // purple — loop closes here at 100%, no seam
    };

    // Cumulative positions (0..1) matching the percentages above exactly.
    private static final float[] INSTA_GRADIENT_POSITIONS = {
        0f, 0.25f, 0.45f, 0.65f, 1f
    };

    private static final int MAX_CACHED_SIZES = 8;

    // Access-order LinkedHashMap => cheap built-in LRU via removeEldestEntry.
    private static final Map<Long, SweepGradient> CACHE =
        new LinkedHashMap<Long, SweepGradient>(MAX_CACHED_SIZES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, SweepGradient> eldest) {
                return size() > MAX_CACHED_SIZES;
            }
        };

    private static long keyFor(int w, int h) {
        return ((long) w << 32) | (h & 0xFFFFFFFFL);
    }

    /**
     * Returns a shared, cached SweepGradient for the given local (0,0)-based
     * width/height, pre-rotated -90° so it starts at 12 o'clock like
     * Instagram. Builds it once per distinct size, reused after that.
     *
     * Caller must draw using the SAME local coordinate space the shader was
     * built for (i.e. translate the canvas to bounds.left/top first if the
     * drawable's bounds aren't already at 0,0 — see StoryRingGradientDrawable).
     */
    public static synchronized SweepGradient get(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        long key = keyFor(width, height);
        SweepGradient cached = CACHE.get(key);
        if (cached != null) return cached;

        float cx = width / 2f;
        float cy = height / 2f;
        SweepGradient sg = new SweepGradient(cx, cy, INSTA_GRADIENT_COLORS, INSTA_GRADIENT_POSITIONS);
        Matrix matrix = new Matrix();
        matrix.postRotate(-90, cx, cy);
        sg.setLocalMatrix(matrix);

        CACHE.put(key, sg);
        return sg;
    }
}
