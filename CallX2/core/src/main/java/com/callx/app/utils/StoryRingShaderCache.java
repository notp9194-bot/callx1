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

    // Story ring gradient stops — colour flow (from top, clockwise):
    //   0%    Purple   #8A2BE2
    //   15%   Pink     #FF1493
    //   55%   Red-Orange blend #FF5318 (midpoint of the old Red/Orange bands)
    //   75%   Orange-Yellow blend #FFBE00 (midpoint of the old Orange/Yellow bands)
    //   100%  Yellow   #FFF200
    //
    // PREVIOUS BUG ("4 jagah pe patti color separate ho rahi hai"): each
    // "band" used to carry its own start/end color pair, and wherever one
    // band's end color didn't match the next band's start color (55%, 75%)
    // the two colors were placed at (almost) the same position to force a
    // hard cut. That's what produced the visible seam lines cutting the
    // ring into segments — and because Skia's SweepGradient divides by the
    // interval width between stops, a truly zero-width interval there could
    // even paint a solid black line.
    //
    // FIX: every position below now has exactly ONE color, so the shader
    // just linearly blends from one hex stop straight into the next all the
    // way around — no duplicate/near-duplicate positions, no hard edges, no
    // seams. The 55%/75% colors are the midpoint blend of what used to be
    // two different band colors at that angle, so the transition still
    // passes through red→orange and orange→yellow, just continuously.
    private static final int[] INSTA_GRADIENT_COLORS = {
        0xFF8A2BE2, // purple  (0%)
        0xFFFF1493, // pink    (15%)
        0xFFFF5318, // red-orange blend (55%)
        0xFFFFBE00, // orange-yellow blend (75%)
        0xFFFFF200  // yellow  (100%)
    };

    // Cumulative positions (0..1) matching the colors above — each unique,
    // no duplicates, so there is no seam/hard-edge anywhere on the ring.
    private static final float[] INSTA_GRADIENT_POSITIONS = {
        0f, 0.15f, 0.55f, 0.75f, 1f
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
