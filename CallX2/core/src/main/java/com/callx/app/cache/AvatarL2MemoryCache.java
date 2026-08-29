package com.callx.app.cache;

import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.util.Log;
import android.util.LruCache;

import java.lang.ref.WeakReference;

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
 */
public final class AvatarL2MemoryCache implements ComponentCallbacks2 {

    private final String tag;
    private final LruCache<String, WeakReference<Bitmap>> cache;

    public AvatarL2MemoryCache(String tag, int maxEntries) {
        this.tag = tag;
        this.cache = new LruCache<>(maxEntries);
    }

    public Bitmap get(String url) {
        if (url == null || url.isEmpty()) return null;
        WeakReference<Bitmap> ref = cache.get(url);
        if (ref == null) return null;
        Bitmap bmp = ref.get();
        if (bmp == null || bmp.isRecycled()) {
            cache.remove(url);
            return null;
        }
        return bmp;
    }

    public void put(String url, Bitmap bitmap) {
        if (url == null || url.isEmpty() || bitmap == null || bitmap.isRecycled()) return;
        cache.put(url, new WeakReference<>(bitmap));
    }

    @Override
    public void onTrimMemory(int level) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            int evicted = cache.size();
            cache.evictAll();
            Log.d("AvatarL2Cache", "[" + tag + "] TRIM_MEMORY_COMPLETE — cleared " + evicted + " entries");
        }
        // Below COMPLETE (UI_HIDDEN/BACKGROUND/MODERATE/RUNNING_*): intentional
        // no-op — this is exactly the "survive MODERATE" behavior the fix asked for.
    }

    @Override
    public void onLowMemory() {
        // Pre-ComponentCallbacks2 signal, roughly equivalent to COMPLETE.
        cache.evictAll();
        Log.d("AvatarL2Cache", "[" + tag + "] onLowMemory — cleared");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // No-op — cache keys don't depend on configuration.
    }
}
