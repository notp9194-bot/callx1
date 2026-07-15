package com.callx.app.cache;

import com.callx.app.models.ReelModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReelGridPrefetchCache — advance #2 (predictive prefetch on app-open).
 *
 * When a profile's reels tab finishes its first Firebase page load, the
 * exact same list is almost always what "View All Reels" would show first
 * too. Instead of making the user wait for a second round-trip when they
 * tap through, we stash that first page here in memory, keyed by
 * (uid, tab). The next screen (AllReelsFullActivity) checks this cache in
 * onCreate: a hit renders the grid instantly with zero spinner, then
 * still kicks off its own Firebase refresh silently in the background so
 * the data never goes stale.
 *
 * Deliberately in-memory only (no disk/Room) — this is a short-lived
 * "just navigated here" hint, not a durable offline cache; entries expire
 * after a few minutes so a long-idle app doesn't show ancient data.
 */
public final class ReelGridPrefetchCache {

    private static final long TTL_MS = 3 * 60 * 1000L; // 3 minutes

    private static final Map<String, Entry> cache = new HashMap<>();

    private ReelGridPrefetchCache() {}

    private static final class Entry {
        final List<ReelModel> data;
        final long cachedAt;
        Entry(List<ReelModel> data) { this.data = data; this.cachedAt = System.currentTimeMillis(); }
        boolean isExpired() { return System.currentTimeMillis() - cachedAt > TTL_MS; }
    }

    private static String key(String uid, int tab) {
        return uid + ":" + tab;
    }

    /** Stash a copy of the first page for (uid, tab). Safe to call from any thread. */
    public static synchronized void put(String uid, int tab, List<ReelModel> data) {
        if (uid == null || uid.isEmpty() || data == null || data.isEmpty()) return;
        cache.put(key(uid, tab), new Entry(new ArrayList<>(data)));
    }

    /** Returns the prefetched list for (uid, tab), or null if absent/expired. */
    public static synchronized List<ReelModel> get(String uid, int tab) {
        if (uid == null || uid.isEmpty()) return null;
        Entry e = cache.get(key(uid, tab));
        if (e == null) return null;
        if (e.isExpired()) { cache.remove(key(uid, tab)); return null; }
        return new ArrayList<>(e.data);
    }

    /** Drop a consumed entry so a stale copy can't be reused across visits. */
    public static synchronized void consume(String uid, int tab) {
        if (uid == null) return;
        cache.remove(key(uid, tab));
    }
}
