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

    // Story ring gradient stops — updated brand spec (colour flow, from top,
    // clockwise, percentages measured exactly as spec'd):
    //   0%–15%   Pink–Purple mix   #8A2BE2 → #FF1493
    //   15%–55%  Pink–Red          #FF1493 → #FF3B30
    //   55%–75%  Orange            #FF6A00 → #FFA500
    //   75%–100% Yellow            #FFD600 → #FFF200
    // Each band blends smoothly between its own two hex stops; bands are
    // joined with a duplicated position (55%, 75%) wherever the spec's end
    // color of one band doesn't match the start color of the next, giving a
    // hard edge exactly there instead of an unwanted blend across it. The
    // loop does NOT close seamlessly (yellow #FFF200 at 100% is a different
    // color from purple #8A2BE2 at 0%) — that visible seam at 12 o'clock is
    // intentional, matching the reference spec's "Starting from Top" flow.
    private static final int[] INSTA_GRADIENT_COLORS = {
        0xFF8A2BE2, // purple            (0%)
        0xFFFF1493, // pink              (15% — end of Pink-Purple / start of Pink-Red)
        0xFFFF3B30, // red               (55% — end of Pink-Red)
        0xFFFF6A00, // orange            (55% — start of Orange, hard edge)
        0xFFFFA500, // orange (lighter)  (75% — end of Orange)
        0xFFFFD600, // yellow            (75% — start of Yellow, hard edge)
        0xFFFFF200  // yellow (lighter)  (100%)
    };

    // Cumulative positions (0..1) matching the percentages above exactly.
    // 0.55 and 0.75 each appear twice to create the hard band edges noted above.
    private static final float[] INSTA_GRADIENT_POSITIONS = {
        0f, 0.15f, 0.55f, 0.55f, 0.75f, 0.75f, 1f
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
