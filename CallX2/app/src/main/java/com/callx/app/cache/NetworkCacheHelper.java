package com.callx.app.cache;

import android.content.Context;

import com.callx.app.BuildConfig;

import java.io.File;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

/**
 * OkHttp Network Cache — Tier-3 cache layer for HTTP responses.
 * Cache size: 10 MB. Used for REST API calls and CDN media headers.
 *
 * Previously fixed (v8):
 *   - HttpLoggingInterceptor wired in DEBUG only.
 *
 * FIX #6 (MEDIUM): Connection pool eviction added.
 *
 *   Old: OkHttpClient static singleton — TCP connections kept open forever.
 *   → OkHttp connection pool holds up to 5 keep-alive connections per host.
 *   → When app goes to background, these sockets stay alive in the OS,
 *     consuming file descriptors + preventing complete network teardown.
 *   → On low-RAM devices the OS keeps these fds open even after TRIM signals,
 *     reducing available sockets for other apps.
 *
 *   Fix: evictConnectionPool() — calls connectionPool().evictAll() which
 *     immediately closes all idle connections. Called from CallxApp.onTrimMemory()
 *     when TRIM_MEMORY_UI_HIDDEN fires (app fully backgrounded).
 *     Active requests are never interrupted — evictAll() only closes IDLE sockets.
 */
public class NetworkCacheHelper {

    private static final long   CACHE_SIZE = 10L * 1024 * 1024; // 10 MB
    private static final String CACHE_DIR  = "http_cache";
    private static OkHttpClient sClient;

    public static synchronized OkHttpClient getClient(Context ctx) {
        if (sClient == null) {
            File  cacheDir  = new File(ctx.getApplicationContext().getCacheDir(), CACHE_DIR);
            Cache httpCache = new Cache(cacheDir, CACHE_SIZE);

            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .cache(httpCache)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20,    TimeUnit.SECONDS)
                    .writeTimeout(20,   TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true);

            if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
                logging.setLevel(HttpLoggingInterceptor.Level.HEADERS);
                builder.addNetworkInterceptor(logging);
            }

            sClient = builder.build();
        }
        return sClient;
    }

    // ─────────────────────────────────────────────────────────────
    // FIX #6: Evict idle TCP connections when app goes to background
    // ─────────────────────────────────────────────────────────────

    /**
     * Close all idle connections in the OkHttp connection pool.
     * Call from CallxApp.onTrimMemory(TRIM_MEMORY_UI_HIDDEN) — i.e., when
     * the app is fully backgrounded.
     *
     * Safe to call at any time: evictAll() only closes idle connections.
     * Any in-flight requests continue uninterrupted.
     */
    public static void evictConnectionPool(Context ctx) {
        try {
            OkHttpClient client = getClient(ctx);
            client.connectionPool().evictAll();
            // Also flush the HTTP disk cache write queue
            Cache cache = client.cache();
            if (cache != null) cache.flush();
        } catch (Exception e) {
            // Non-fatal — log and continue
            android.util.Log.w("NetworkCacheHelper",
                    "evictConnectionPool failed: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // STATS / UTILITY
    // ─────────────────────────────────────────────────────────────

    public static long getCacheSizeBytes(Context ctx) {
        try {
            Cache cache = getClient(ctx).cache();
            return cache != null ? cache.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static void evict(Context ctx) {
        try {
            Cache cache = getClient(ctx).cache();
            if (cache != null) cache.evictAll();
        } catch (Exception ignored) {}
    }
}
