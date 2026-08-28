package com.callx.app.cache;

import android.graphics.Bitmap;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * ReusableThumbBitmapPool — PERF advance: "bitmap downsample + reuse" for
 * the Home tab feed's inline video thumbnail (ivThumb/thumbView).
 *
 * {@link ReelFirstFrameCache#getCached(String)}'s disk-decode fallback used
 * to call a bare {@code BitmapFactory.decodeFile()} on every miss — each
 * call allocates a brand-new mutable pixel buffer (these decoded frames are
 * downscaled to {@code TARGET_WIDTH_PX} but still a few hundred KB each),
 * which is thrown straight at the GC the moment the card scrolls past and
 * its ViewHolder is recycled. On a fast fling through many reels whose
 * memCache entry has already been evicted (LRU cap = 12), that's a fresh
 * allocation on essentially every card attach — exactly the kind of
 * churn that shows up as jank via GC pauses mid-scroll.
 *
 * This pool holds a small number of reusable, mutable ARGB_8888 buffers
 * that {@link BitmapFactory.Options#inBitmap} can decode directly into
 * instead of allocating a new one, the same "inBitmap" technique Instagram/
 * Reels-style feeds use for exactly this kind of repeated same-shaped
 * thumbnail decode.
 *
 * Safety: a bitmap must never be handed back into this pool while
 * something might still be displaying it — reusing it for a new decode
 * overwrites its pixels in place, which would corrupt whatever View still
 * held a reference. The only call site that returns bitmaps here is
 * {@link ReelFirstFrameCache#releaseIfEvicted(String, Bitmap)}, invoked
 * from HomeFragment's {@code onViewRecycled()} — i.e. only once a card's
 * ViewHolder is being torn down for a completely different post AND that
 * exact bitmap object is no longer the live entry in memCache (so no other
 * call to {@code getCached()} for that key can hand the same object out
 * again). See that method for the exact identity check.
 */
final class ReusableThumbBitmapPool {

    private static final String TAG = "ReusableThumbPool";

    /** Mirrors ExoPlayerPool's POOL_SIZE=3 reasoning: at most a
     *  previous/current/next-prefetch card is ever mid-decode at once in
     *  this linear feed, so 3 idle buffers covers the realistic worst case
     *  without holding extra pixel memory the feed will never use. */
    private final int capacity;
    private final Deque<Bitmap> pooled;

    ReusableThumbBitmapPool(int capacity) {
        this.capacity = capacity;
        this.pooled = new ArrayDeque<>(capacity);
    }

    /**
     * Returns a mutable ARGB_8888 buffer whose dimensions exactly match
     * (reqWidth, reqHeight) — BitmapFactory's inBitmap contract is
     * satisfied trivially that way, no reconfigure() edge cases to worry
     * about — or null if nothing in the pool matches (caller falls back to
     * a plain decode). Doesn't allocate; a miss here just means "no reuse
     * this time", never an error.
     */
    synchronized Bitmap obtain(int reqWidth, int reqHeight) {
        if (reqWidth <= 0 || reqHeight <= 0) return null;
        java.util.Iterator<Bitmap> it = pooled.iterator();
        while (it.hasNext()) {
            Bitmap b = it.next();
            if (b.isRecycled()) { it.remove(); continue; }
            if (b.getWidth() == reqWidth && b.getHeight() == reqHeight && b.isMutable()) {
                it.remove();
                return b;
            }
        }
        return null;
    }

    /**
     * Returns a bitmap to the pool for future reuse, or recycles it
     * outright if the pool is already full / the bitmap isn't reusable.
     * Caller (ReelFirstFrameCache) is responsible for only calling this
     * once it's certain nothing else still references the bitmap — see
     * class doc.
     */
    synchronized void release(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || !bitmap.isMutable()) return;
        if (pooled.size() >= capacity) {
            // Pool's already full of same-shaped buffers — an extra one
            // isn't worth holding onto, so let it go rather than grow
            // unbounded (different reels can have different aspect ratios
            // even after the shared TARGET_WIDTH_PX downscale).
            try { bitmap.recycle(); } catch (Exception ignored) {}
            return;
        }
        pooled.addLast(bitmap);
        Log.d(TAG, "release: pooled (" + pooled.size() + "/" + capacity + " buffers held)");
    }

    /** Drops every pooled buffer — mirrors ReelFirstFrameCache.trimMemory(). */
    synchronized void trimMemory() {
        for (Bitmap b : pooled) {
            try { if (!b.isRecycled()) b.recycle(); } catch (Exception ignored) {}
        }
        pooled.clear();
    }
}
