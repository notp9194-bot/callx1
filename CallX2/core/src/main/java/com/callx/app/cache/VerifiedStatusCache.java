package com.callx.app.cache;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VerifiedStatusCache — app-wide singleton, session-scoped + disk-persisted,
 * TTL-bound cache for users/{uid}/isVerified.
 *
 * WHY: the verified badge now shows next to a name on every list screen
 * (chat list, comments, search results, calls tab, status tab, profile,
 * DMs, story viewer...). Without this, every adapter row would fire its
 * own Firebase read on every bind/scroll for a value that almost never
 * changes during a session. This caches each uid's isVerified value after
 * the first resolution and serves every later bind instantly.
 *
 * BATCH RESOLVE: a single row bind still triggers one Firebase read per
 * unknown uid (RTDB has no native multi-get across separate paths), but
 * two things keep a fast-scrolling list from spamming requests:
 *  1. In-flight dedup — if row A and row B both bind the same not-yet-known
 *     uid before the first read returns (common on a fast fling, or when
 *     the same uid appears twice in one screen), only ONE Firebase listener
 *     is attached; both callers are queued and notified together when it
 *     resolves.
 *  2. resolveBatch(uids) — call this once when a screen's data is set
 *     (list submitted / page loaded), BEFORE the RecyclerView starts
 *     binding rows. It warms the cache for every uid on that page in one
 *     pass (dedup'd against cache + in-flight), so by the time the user
 *     actually scrolls, bindForUid() on each row is a pure cache hit with
 *     zero network calls — the per-row Firebase path only lightens the load
 *     once instead of once per bind/rebind.
 *
 * DISK PERSISTENCE: call {@link #init(Context)} once, early in
 * CallxApp#onCreate() (same pattern as UnifiedVideoCacheManager.init(),
 * StatusVideoCacheManager.init(), etc.) — it synchronously loads every
 * previously-resolved uid→(isVerified, resolvedAt) pair from
 * SharedPreferences into the in-memory map (a few hundred entries loads in
 * low single-digit ms, same ballpark as ChatSnapshotCache's cold-start
 * read). Every later resolution is written back with
 * SharedPreferences#apply() (async, non-blocking). Net effect: after the
 * first successful resolve of a uid, a cold app restart shows its badge
 * immediately on first bind — bindForUid() never has to wait on Firebase
 * again for that uid, even across process death (until the entry ages out —
 * see TTL below).
 *
 * TTL + LIVE INVALIDATION: a cached true/false doesn't stay authoritative
 * forever — an admin can grant or revoke a badge (AdminVerificationListActivity)
 * at any time, and a session left open for days shouldn't keep showing a
 * stale badge.
 *  1. TTL — every cache entry carries the timestamp it was resolved at.
 *     getCached()/resolve() treat an entry older than {@link #TTL_MS}
 *     (12h) as a miss, so it transparently re-resolves from Firebase (and
 *     re-persists a fresh timestamp) the next time anyone asks for that uid
 *     — no manual sweep/cleanup pass needed, expiry is checked lazily on
 *     read.
 *  2. Live push for the signed-in user's OWN uid — call
 *     {@link #listenSelf(String)} once after login (see CallxApp#onCreate).
 *     This attaches a single persistent Firebase listener on our own
 *     users/{myUid}/isVerified node, so if an admin approves/revokes us
 *     mid-session the cache updates instantly instead of waiting up to 12h.
 *     We deliberately do NOT do this for every uid we've ever cached — that
 *     would mean one live connection per user ever seen in a list, which
 *     doesn't scale. Other users' entries rely on the TTL.
 */
public final class VerifiedStatusCache {

    private static final String PREFS_NAME = "verified_status_cache";
    /** How long a resolved entry stays authoritative before a re-check is forced. */
    private static final long TTL_MS = 12L * 60 * 60 * 1000; // 12h

    private static VerifiedStatusCache sInstance;

    private static final class Entry {
        final boolean verified;
        final long resolvedAt;
        Entry(boolean verified, long resolvedAt) { this.verified = verified; this.resolvedAt = resolvedAt; }
        boolean isExpired() { return System.currentTimeMillis() - resolvedAt > TTL_MS; }
    }

    private final Map<String, Entry> cache = new HashMap<>();
    // uid -> queued callbacks waiting on the one in-flight Firebase read for that uid.
    private final Map<String, List<Callback>> pending = new HashMap<>();
    private SharedPreferences prefs; // null until init(Context) is called — persistence degrades gracefully
    private com.google.firebase.database.ValueEventListener selfListener;
    private String selfListenerUid;

    public interface Callback {
        void onResult(boolean isVerified);
    }

    private VerifiedStatusCache() {}

    public static synchronized VerifiedStatusCache getInstance() {
        if (sInstance == null) sInstance = new VerifiedStatusCache();
        return sInstance;
    }

    /**
     * Call once, early in Application#onCreate() (application context only —
     * never hold an Activity here). Loads the disk-persisted cache
     * synchronously; safe to call more than once (no-op after the first).
     */
    public static synchronized void init(@NonNull Context context) {
        VerifiedStatusCache inst = getInstance();
        if (inst.prefs != null) return; // already initialized
        inst.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Map<String, ?> all = inst.prefs.getAll();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            String key = e.getKey();
            if (key.endsWith(TS_SUFFIX)) continue; // timestamp entries are read alongside their value below
            if (e.getValue() instanceof Boolean) {
                long ts = inst.prefs.getLong(key + TS_SUFFIX, 0L); // 0 = legacy entry from before TTL, treated as already-expired
                inst.cache.put(key, new Entry((Boolean) e.getValue(), ts));
            }
        }
    }

    private static final String TS_SUFFIX = "_ts";

    /**
     * Attaches a live listener on the SIGNED-IN user's own verified status so
     * an admin grant/revoke reflects instantly instead of waiting on the TTL.
     * Call after login (and again after account switch); safe to call
     * repeatedly with the same uid (no-op) or a new one (swaps the listener).
     */
    public synchronized void listenSelf(String myUid) {
        if (myUid == null || myUid.isEmpty() || myUid.equals(selfListenerUid)) return;
        if (selfListener != null && selfListenerUid != null) {
            FirebaseUtils.getIsVerifiedRef(selfListenerUid).removeEventListener(selfListener);
        }
        selfListenerUid = myUid;
        selfListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                deliver(myUid, Boolean.TRUE.equals(snapshot.getValue(Boolean.class)));
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { /* keep last known value */ }
        };
        FirebaseUtils.getIsVerifiedRef(myUid).addValueEventListener(selfListener);
    }

    /** Returns the cached value if known and not expired, else null (caller should also call resolve()). */
    public Boolean getCached(String uid) {
        if (uid == null) return null;
        Entry e = cache.get(uid);
        if (e == null || e.isExpired()) return null;
        return e.verified;
    }

    /** Resolves isVerified for uid (from cache if present & fresh, else Firebase), then calls back on the main thread. */
    public void resolve(@NonNull String uid, @NonNull Callback callback) {
        Entry cached = cache.get(uid);
        if (cached != null && !cached.isExpired()) {
            callback.onResult(cached.verified);
            return;
        }
        synchronized (pending) {
            List<Callback> waiters = pending.get(uid);
            if (waiters != null) {
                // Someone already has a listener in flight for this uid —
                // queue behind it instead of firing a second Firebase read.
                waiters.add(callback);
                return;
            }
            List<Callback> fresh = new ArrayList<>();
            fresh.add(callback);
            pending.put(uid, fresh);
        }
        FirebaseUtils.getIsVerifiedRef(uid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                boolean verified = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
                deliver(uid, verified);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                deliver(uid, false);
            }
        });
    }

    private void deliver(String uid, boolean verified) {
        long now = System.currentTimeMillis();
        cache.put(uid, new Entry(verified, now));
        if (prefs != null) {
            prefs.edit()
                    .putBoolean(uid, verified)
                    .putLong(uid + TS_SUFFIX, now)
                    .apply();
        }
        List<Callback> waiters;
        synchronized (pending) {
            waiters = pending.remove(uid);
        }
        if (waiters != null) {
            for (Callback cb : waiters) cb.onResult(verified);
        }
    }

    /**
     * Warms the cache for a whole screen's worth of uids in one pass — call
     * this when a list's data is set (e.g. adapter.setResults()/submitList()),
     * not per-row. Skips uids already cached-and-fresh or already in flight,
     * so calling it repeatedly (pagination, refresh) never duplicates work
     * and expired entries get transparently refreshed. No callback: rows
     * bind against the cache afterwards via the normal bindForUid() path.
     */
    public void resolveBatch(Collection<String> uids) {
        if (uids == null) return;
        for (String uid : uids) {
            if (uid == null || uid.isEmpty()) continue;
            Entry e = cache.get(uid);
            if (e != null && !e.isExpired()) continue;
            synchronized (pending) {
                if (pending.containsKey(uid)) continue;
            }
            resolve(uid, isVerified -> { /* just warming the cache */ });
        }
    }

    /** Call when a badge is approved/rejected elsewhere (e.g. after admin action synced down) to force a re-check. */
    public void invalidate(String uid) {
        if (uid == null) return;
        cache.remove(uid);
        if (prefs != null) prefs.edit().remove(uid).remove(uid + TS_SUFFIX).apply();
    }
}
