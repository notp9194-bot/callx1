package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AvatarCacheAnalytics — Instagram-style avatar CDN monitoring: tracks which
 * TIER actually served every avatar resolution app-wide (L2 memory / L3
 * disk / Glide's own memory+disk / a real CDN network round-trip / the
 * background pre-warm worker), so it's possible to answer "which tier is
 * actually pulling its weight" instead of guessing from anecdote.
 *
 * Single app-wide instance (not per-module like AvatarL2MemoryCache) —
 * unlike the caches themselves, there's no benefit to splitting the stats
 * by module; the whole point is one aggregate picture across reels, chat,
 * comments, everywhere an avatar is ever bound.
 *
 * Counts persist across restarts (debounced SharedPreferences flush, same
 * pattern as CacheAnalytics) so the ratio reflects real usage over days,
 * not just the current session.
 */
public final class AvatarCacheAnalytics {

    /** Where an avatar resolution was actually served from, cheapest first. */
    public enum Tier {
        L2_MEMORY,     // AvatarL2MemoryCache / Reels-or-ChatAvatarL2Cache in-process hit
        L3_DISK,       // AvatarL3DiskCache hit (survives process death)
        PREWARM,       // Bitmap already primed by AvatarPreWarmWorker before the user ever scrolled here
        GLIDE_MEMORY,  // Glide's own LruResourceCache (DataSource.MEMORY_CACHE)
        GLIDE_DISK,    // Glide's resource/data disk cache (DataSource.RESOURCE_DISK_CACHE / DATA_DISK_CACHE / LOCAL)
        CDN_NETWORK    // Real network round-trip to Cloudinary/CDN (DataSource.REMOTE)
    }

    private static final String TAG            = "AvatarCacheAnalytics";
    private static final String PREFS_NAME     = "avatar_cache_analytics";
    private static final long   FLUSH_DEBOUNCE_MS = 5_000L;
    private static final int    LOG_EVERY_N    = 50; // periodic Logcat snapshot, not every single load

    private static volatile AvatarCacheAnalytics sInstance;

    private final SharedPreferences prefs;
    private final Map<Tier, AtomicLong> counts = new EnumMap<>(Tier.class);
    private final AtomicLong totalLoads;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean flushPending = false;

    private AvatarCacheAnalytics(Context appCtx) {
        prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        for (Tier t : Tier.values()) {
            counts.put(t, new AtomicLong(prefs.getLong(t.name(), 0L)));
        }
        totalLoads = new AtomicLong(prefs.getLong("total", 0L));
    }

    public static AvatarCacheAnalytics getInstance(Context ctx) {
        AvatarCacheAnalytics instance = sInstance;
        if (instance == null) {
            synchronized (AvatarCacheAnalytics.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarCacheAnalytics(ctx.getApplicationContext());
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    /** Record one avatar resolution as having been served by `tier`. Call exactly once per bind, at the point it's known which tier actually won. */
    public void record(Tier tier) {
        if (tier == null) return;
        counts.get(tier).incrementAndGet();
        long total = totalLoads.incrementAndGet();
        scheduleDebouncedFlush();
        if (total % LOG_EVERY_N == 0) logSnapshot();
    }

    /**
     * Maps a Glide {@link DataSource} straight to a {@link Tier} — the one
     * choke point every RequestListener#onResourceReady across the app
     * should funnel through (see ReelUiController#loadOwnerAvatarNow), so
     * the CDN split stays consistent no matter which screen recorded it.
     */
    public static Tier fromGlideDataSource(DataSource ds) {
        if (ds == null) return Tier.CDN_NETWORK;
        switch (ds) {
            case MEMORY_CACHE:
                return Tier.GLIDE_MEMORY;
            case RESOURCE_DISK_CACHE:
            case DATA_DISK_CACHE:
            case LOCAL:
                return Tier.GLIDE_DISK;
            case REMOTE:
            default:
                return Tier.CDN_NETWORK;
        }
    }

    /**
     * Drop-in {@link RequestListener} that ONLY records the CDN/cache split
     * — no L2/L3 writes, no UI side effects — for any screen's avatar
     * Glide.load(...).listener(AvatarCacheAnalytics.glideListener(ctx)).
     * Lets chat/group/profile/status/follow-list avatar binds all feed the
     * same app-wide split this class tracks, without each of those modules
     * re-implementing the DataSource→Tier mapping themselves.
     */
    public static <T> RequestListener<T> glideListener(Context ctx) {
        AvatarCacheAnalytics analytics = getInstance(ctx);
        return new RequestListener<T>() {
            @Override
            public boolean onLoadFailed(GlideException e, Object model, Target<T> target, boolean isFirstResource) {
                return false; // don't swallow the failure — let the caller's own error()/placeholder still apply
            }

            @Override
            public boolean onResourceReady(T resource, Object model, Target<T> target, DataSource dataSource, boolean isFirstResource) {
                analytics.record(fromGlideDataSource(dataSource));
                return false; // don't swallow the resource — let the caller's .into(view) still receive it
            }
        };
    }

    public Map<Tier, Long> snapshot() {
        Map<Tier, Long> out = new EnumMap<>(Tier.class);
        for (Tier t : Tier.values()) out.put(t, counts.get(t).get());
        return out;
    }

    public long getTotalLoads() { return totalLoads.get(); }

    /** Fraction of all resolutions that did NOT require a real CDN network round-trip. */
    public float hitRatio() {
        long total = totalLoads.get();
        if (total == 0) return 0f;
        long network = counts.get(Tier.CDN_NETWORK).get();
        return 1f - (network / (float) total);
    }

    public void logSnapshot() {
        long total = totalLoads.get();
        StringBuilder sb = new StringBuilder("split (total=").append(total).append("): ");
        for (Tier t : Tier.values()) {
            long c = counts.get(t).get();
            float pct = total == 0 ? 0f : (c * 100f / total);
            sb.append(t.name()).append('=').append(String.format(Locale.US, "%.1f%%", pct)).append(' ');
        }
        Log.d(TAG, sb.toString());
    }

    private void scheduleDebouncedFlush() {
        if (!flushPending) {
            flushPending = true;
            mainHandler.postDelayed(this::flush, FLUSH_DEBOUNCE_MS);
        }
    }

    private void flush() {
        flushPending = false;
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<Tier, AtomicLong> e : counts.entrySet()) editor.putLong(e.getKey().name(), e.getValue().get());
        editor.putLong("total", totalLoads.get());
        editor.apply();
    }
}
