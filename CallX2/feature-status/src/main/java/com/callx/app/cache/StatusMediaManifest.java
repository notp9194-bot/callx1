package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.cache.SimpleCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * StatusMediaManifest — bookkeeping for status videos in the shared player cache:
 *   url → expiresAt   (evict the moment a status dies, like WhatsApp)
 *   url → bytes       (so status can have its OWN quota inside the 300MB cache it shares with chat / X / music)
 *
 * sweepNow():   drop expired videos.          trimToQuota(): if over QUOTA_BYTES evict soonest-to-expire first.
 * hasRoom():    prefetch never starts a video that would push status past its quota.
 * (Images live in Glide's disk cache, which cannot evict a single entry — they still age out via Glide's LRU.)
 */
@OptIn(markerClass = UnstableApi.class)
public final class StatusMediaManifest {
    private StatusMediaManifest() {}

    private static final String PREFS = "status_media_manifest";
    private static final String PREFS_BYTES = "status_media_bytes";
    public static final long QUOTA_BYTES = 120L * 1024 * 1024;     // status' share of the 300MB "other" cache
    private static final long GRACE_MS = 5L * 60_000L;              // don't yank a video a viewer may still have open
    private static final long DEFAULT_LIFE_MS = 24L * 3_600_000L;   // status max life when the real expiry is unknown
    private static final Executor IO = Executors.newSingleThreadExecutor();

    private static SharedPreferences prefs(Context c)      { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static SharedPreferences bytesPrefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS_BYTES, Context.MODE_PRIVATE); }

    /** expiresAtMs ≤ 0 → assume the standard 24h life from now. Keeps the latest expiry if recorded twice. */
    public static void record(Context ctx, String url, long expiresAtMs) {
        if (ctx == null || url == null || url.isEmpty()) return;
        try {
            final long exp = expiresAtMs > 0 ? expiresAtMs : System.currentTimeMillis() + DEFAULT_LIFE_MS;
            final SharedPreferences p = prefs(ctx);
            if (exp > p.getLong(url, 0L)) p.edit().putLong(url, exp).apply();
        } catch (Exception ignored) {}
    }

    public static void record(Context ctx, String url, long expiresAtMs, long bytes) {
        record(ctx, url, expiresAtMs);
        if (ctx == null || url == null || url.isEmpty() || bytes <= 0) return;
        try { bytesPrefs(ctx).edit().putLong(url, bytes).apply(); } catch (Exception ignored) {}
    }

    /** Bytes of status video we are tracking (cheap: prefs only). */
    public static long totalBytes(Context ctx) {
        long t = 0;
        try {
            for (Object v : bytesPrefs(ctx).getAll().values()) if (v instanceof Long) t += (Long) v;
        } catch (Exception ignored) {}
        return t;
    }

    public static boolean hasRoom(Context ctx, long needBytes) { return totalBytes(ctx) + needBytes <= QUOTA_BYTES; }

    public static void sweepAsync(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> { sweepNow(app); trimToQuota(app); });
    }

    private static SimpleCache cacheOrNull(Context app) {
        try { StatusVideoCacheManager.init(app); return StatusVideoCacheManager.getSimpleCache(); }
        catch (Exception e) { return null; }
    }

    /** Blocking — call off the main thread. Returns how many expired videos were removed. */
    public static int sweepNow(Context app) {
        int removed = 0;
        try {
            final SharedPreferences p = prefs(app);
            final SharedPreferences bp = bytesPrefs(app);
            final Map<String, ?> all = p.getAll();
            final long now = System.currentTimeMillis();
            SharedPreferences.Editor ed = null, bed = null;
            SimpleCache cache = null;
            for (Map.Entry<String, ?> e : all.entrySet()) {
                if (!(e.getValue() instanceof Long)) continue;
                if ((Long) e.getValue() + GRACE_MS > now) continue; // still alive
                if (cache == null) { cache = cacheOrNull(app); if (cache == null) return removed; }
                try { cache.removeResource(e.getKey()); } catch (Exception ignored) {}
                if (ed == null) { ed = p.edit(); bed = bp.edit(); }
                ed.remove(e.getKey()); bed.remove(e.getKey());
                removed++;
            }
            if (ed != null) { ed.apply(); bed.apply(); }
        } catch (Exception ignored) {}
        return removed;
    }

    /** Over quota → evict soonest-to-expire first (they are about to die anyway). Blocking. */
    public static void trimToQuota(Context app) {
        try {
            long total = totalBytes(app);
            if (total <= QUOTA_BYTES) return;
            final SharedPreferences p = prefs(app), bp = bytesPrefs(app);
            final List<Map.Entry<String, ?>> list = new ArrayList<>();
            for (Map.Entry<String, ?> e : p.getAll().entrySet()) if (e.getValue() instanceof Long) list.add(e);
            Collections.sort(list, (a, b) -> Long.compare((Long) a.getValue(), (Long) b.getValue()));
            final SimpleCache cache = cacheOrNull(app);
            if (cache == null) return;
            final SharedPreferences.Editor ed = p.edit(), bed = bp.edit();
            for (Map.Entry<String, ?> e : list) {
                if (total <= QUOTA_BYTES) break;
                final String url = e.getKey();
                total -= bp.getLong(url, 0L);
                try { cache.removeResource(url); } catch (Exception ignored) {}
                ed.remove(url); bed.remove(url);
            }
            ed.apply(); bed.apply();
        } catch (Exception ignored) {}
    }

    /** Remove every tracked status video from the player cache and forget them. Blocking. */
    public static void clearAll(Context app) {
        try {
            final SharedPreferences p = prefs(app);
            final SimpleCache cache = cacheOrNull(app);
            if (cache != null)
                for (String url : new ArrayList<>(p.getAll().keySet())) { try { cache.removeResource(url); } catch (Exception ignored) {} }
            p.edit().clear().apply();
            bytesPrefs(app).edit().clear().apply();
        } catch (Exception ignored) {}
    }
}
