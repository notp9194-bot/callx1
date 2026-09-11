package com.callx.app.cache;

import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AvatarL2MemoryCache — small warm-restart-oriented L2 bitmap cache that
 * sits ALONGSIDE Glide's own LruResourceCache, keyed by the exact resolved
 * URL (AvatarUrlBuilder output — tier + ?v= already baked in, so a real
 * avatar change is automatically a cache miss, never a stale hit).
 *
 * FIX (onTrimMemory point 5 — avatar versioning plan):
 * CallxApp#onTrimMemory used to call Glide.get(this).clearMemory() on
 * EVERY level >= MODERATE. MODERATE fires constantly during normal use
 * (any time Android wants some memory back, nowhere near "about to kill
 * this process") — so every routine avatar re-scroll after a MODERATE
 * signal re-decoded bitmaps that were perfectly fine a second ago,
 * killing warm-restart speed for no real benefit.
 *
 * This cache stores only WeakReference<Bitmap> — it never keeps a bitmap
 * alive by itself, so it still costs nothing under genuine pressure (GC
 * can reclaim entries any time). Its onTrimMemory() is a deliberate no-op
 * below COMPLETE: it survives MODERATE/BACKGROUND/UI_HIDDEN on purpose,
 * and only force-evicts everything at COMPLETE (or the legacy
 * onLowMemory() signal), matching "sirf COMPLETE pe clear".
 *
 * ONE INSTANCE PER MODULE, not a shared singleton: see ReelsAvatarL2Cache
 * (feature-reels) and ChatAvatarL2Cache (feature-chat). Each module
 * registers its OWN instance with Context#registerComponentCallbacks, so
 * trimming is independent per module — a slow or misbehaving trim path in
 * one module can never delay or skip the other's, unlike a single
 * app-wide handler that walks every cache serially in CallxApp.onTrimMemory.
 *
 * FIX (#8 — smart eviction): was a plain {@code android.util.LruCache},
 * which only ever evicts by recency — an avatar seen dozens of times (a
 * pinned contact, a close friend's row revisited every session) got its MAP
 * ENTRY pushed out by a single stranger row glanced at a moment more
 * recently, even though the weakly-held Bitmap behind it was still alive
 * and would otherwise have been an instant L2 hit. Since real memory
 * reclaim here was already GC's job either way (values are weak refs, not
 * the strong refs a normal LruCache assumes), pure recency ordering wasn't
 * buying anything a frequency-aware order couldn't do better — this is now
 * a hand-rolled frequency+recency ("LFRU") map instead: each entry tracks a
 * hit count and a last-access timestamp, and eviction (only when a genuinely
 * NEW url arrives at capacity) drops whichever entry currently has the
 * lowest hitCount/age score, not just whichever was least recently touched.
 */
public final class AvatarL2MemoryCache implements ComponentCallbacks2 {

    private static final class Entry {
        final WeakReference<Bitmap> ref;
        int hitCount;
        long lastAccessMs;

        Entry(Bitmap bitmap) {
            this.ref = new WeakReference<>(bitmap);
            this.hitCount = 1;
            this.lastAccessMs = SystemClock.elapsedRealtime();
        }
    }

    private final String tag;
    private final int maxEntries;
    private final Map<String, Entry> cache = new LinkedHashMap<>();
    private final Object lock = new Object();

    public AvatarL2MemoryCache(String tag, int maxEntries) {
        this.tag = tag;
        this.maxEntries = Math.max(1, maxEntries);
    }

    public Bitmap get(String url) {
        if (url == null || url.isEmpty()) return null;
        synchronized (lock) {
            Entry e = cache.get(url);
            if (e == null) return null;
            Bitmap bmp = e.ref.get();
            if (bmp == null || bmp.isRecycled()) {
                cache.remove(url); // GC already reclaimed it — drop the dead entry
                return null;
            }
            e.hitCount++;
            e.lastAccessMs = SystemClock.elapsedRealtime();
            return bmp;
        }
    }

    public void put(String url, Bitmap bitmap) {
        if (url == null || url.isEmpty() || bitmap == null || bitmap.isRecycled()) return;
        synchronized (lock) {
            Entry existing = cache.get(url);
            if (existing != null) {
                // Re-decode of a URL we already track (e.g. a stale weak-ref
                // GC'd the bitmap between binds) — refresh in place, counts
                // as a genuine reuse for scoring purposes, not a fresh entry.
                existing.hitCount++;
                existing.lastAccessMs = SystemClock.elapsedRealtime();
                return;
            }
            if (cache.size() >= maxEntries) evictLowestScore();
            cache.put(url, new Entry(bitmap));
        }
    }

    /** Caller must hold {@link #lock}. Drops whichever entry currently has
     *  the lowest hitCount/age score — see class doc for why that beats
     *  pure recency for this cache's weak-ref-backed entries. */
    private void evictLowestScore() {
        String worstKey = null;
        double worstScore = Double.MAX_VALUE;
        long now = SystemClock.elapsedRealtime();
        for (Map.Entry<String, Entry> e : cache.entrySet()) {
            Entry v = e.getValue();
            long ageMs = Math.max(1L, now - v.lastAccessMs);
            double score = v.hitCount / (double) ageMs;
            if (score < worstScore) {
                worstScore = score;
                worstKey = e.getKey();
            }
        }
        if (worstKey != null) cache.remove(worstKey);
    }

    @Override
    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            int evicted;
            synchronized (lock) {
                evicted = cache.size();
                cache.clear();
            }
            Log.d("AvatarL2Cache", "[" + tag + "] TRIM_MEMORY_COMPLETE — cleared " + evicted + " entries");
        }
        // Below COMPLETE (UI_HIDDEN/BACKGROUND/MODERATE/RUNNING_*): intentional
        // no-op — this is exactly the "survive MODERATE" behavior the fix asked for.
    }

    @Override
    public void onLowMemory() {
        // Pre-ComponentCallbacks2 signal, roughly equivalent to COMPLETE.
        synchronized (lock) {
            cache.clear();
        }
        Log.d("AvatarL2Cache", "[" + tag + "] onLowMemory — cleared");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // No-op — cache keys don't depend on configuration.
    }
}
