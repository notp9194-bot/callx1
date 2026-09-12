package com.callx.app.cache;

import android.content.Context;

import java.io.File;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.OkHttpClient;

/**
 * AvatarHttpCache — dedicated OkHttpClient (with its own on-disk HTTP Cache)
 * used ONLY to fetch avatar images through Glide (see
 * CallxGlideModule#registerComponents / AvatarUrlBuilder / AvatarPrefetcher).
 *
 * Why this exists (ETag/Last-Modified + version param, combined):
 *   AvatarUrlBuilder already appends ?v=<avatarVersion> so a REAL avatar
 *   change always produces a brand-new URL — that's the correctness
 *   guarantee (never show a stale photo). But by default Glide's network
 *   stack is a bare HttpURLConnection with no HTTP disk cache at all, so
 *   every time Glide's OWN resource cache is evicted (LRU pressure, app
 *   reinstall, disk-cache-only offscreen lookup misses — see
 *   ReelUiController#loadOwnerAvatarDiskCacheOnly) and the avatar is
 *   re-requested for the SAME (unchanged) URL, it re-downloads the full
 *   image body from scratch even though the CDN would happily confirm
 *   "unchanged" for a few dozen bytes.
 *
 *   Routing Glide's HTTP traffic through an OkHttpClient that has a Cache
 *   closes that gap for free — OkHttp automatically:
 *     1. Persists each response's ETag / Last-Modified validator headers
 *        (Cloudinary's delivery URLs send both) alongside the cached body.
 *     2. On a repeat request for the same URL, sends
 *        If-None-Match / If-Modified-Since automatically.
 *     3. A CDN 304 response means OkHttp serves the cached body straight
 *        from disk — no image bytes re-transferred, just a small header
 *        round-trip.
 *   No custom interceptor is needed for this — it's standard RFC 7234
 *   behavior built into OkHttp's Cache; we only have to supply one.
 *
 * Kept separate from NetworkCacheHelper's client on purpose: that one is
 * a small 10MB cache sized for REST/API JSON payloads (see its own class
 * doc) — mixing in image bytes would blow through it fast and evict useful
 * API cache entries. Avatars are tiny and extremely repeat-heavy (same
 * handful of URLs requested across many screens/tiers), so they get their
 * own larger, dedicated budget instead.
 */
public final class AvatarHttpCache {

    private static final long   CACHE_SIZE_BYTES = 20L * 1024 * 1024; // 20 MB — thousands of small avatar responses
    private static final String CACHE_DIR        = "avatar_http_cache";

    private static volatile OkHttpClient sClient;

    private AvatarHttpCache() {}

    public static OkHttpClient getClient(Context ctx) {
        OkHttpClient client = sClient;
        if (client == null) {
            synchronized (AvatarHttpCache.class) {
                client = sClient;
                if (client == null) {
                    File cacheDir = new File(ctx.getApplicationContext().getCacheDir(), CACHE_DIR);
                    Cache httpCache = new Cache(cacheDir, CACHE_SIZE_BYTES);
                    client = new OkHttpClient.Builder()
                            .cache(httpCache)
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(20, TimeUnit.SECONDS)
                            .writeTimeout(20, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .build();
                    sClient = client;
                }
            }
        }
        return client;
    }

    /** Mirrors NetworkCacheHelper#evictConnectionPool — call from CallxApp.onTrimMemory(UI_HIDDEN). */
    public static void evictConnectionPool() {
        OkHttpClient client = sClient;
        if (client == null) return;
        try {
            client.connectionPool().evictAll();
            Cache cache = client.cache();
            if (cache != null) cache.flush();
        } catch (Exception ignored) {}
    }
}
