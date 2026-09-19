package com.callx.app.cache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UsernameCache — session-scoped, TTL-bound cache for users/{uid}/username.
 *
 * WHY: reels/posts only carry a denormalized display name (ReelModel.ownerName),
 * but feed headers should show the @username like Instagram. This resolves each
 * unique owner uid ONCE per session and serves every later bind from memory:
 *  - in-flight dedup: the same uid bound by several rows before the first read
 *    returns attaches only ONE Firebase listener;
 *  - resolveBatch(uids): warms a whole page in one pass, before rows bind;
 *  - put(uid, username): screens that already fetched a username (e.g.
 *    UserReelsActivity's header read) seed it here so nobody re-reads it.
 * Same pattern as VerifiedStatusCache (memory only — no disk persistence).
 */
public final class UsernameCache {

    private static final long TTL_MS = 12L * 60 * 60 * 1000; // 12h

    private static UsernameCache sInstance;

    private static final class Entry {
        final String username; // "" = resolved, but user has no username set
        final long resolvedAt;
        Entry(String username, long resolvedAt) { this.username = username; this.resolvedAt = resolvedAt; }
        boolean isExpired() { return System.currentTimeMillis() - resolvedAt > TTL_MS; }
    }

    public interface Callback {
        /** username is "" when the user has none (caller should fall back to name). */
        void onResult(@NonNull String username);
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Map<String, List<Callback>> pending = new java.util.HashMap<>();

    private UsernameCache() {}

    public static synchronized UsernameCache getInstance() {
        if (sInstance == null) sInstance = new UsernameCache();
        return sInstance;
    }

    /** null = unknown/expired (caller should resolve()); "" = known, no username. */
    @Nullable
    public String getCached(@Nullable String uid) {
        if (uid == null || uid.isEmpty()) return null;
        Entry e = cache.get(uid);
        if (e == null || e.isExpired()) return null;
        return e.username;
    }

    /** Seeds a username a screen already fetched — zero extra reads. */
    public void put(@Nullable String uid, @Nullable String username) {
        if (uid == null || uid.isEmpty() || username == null || username.trim().isEmpty()) return;
        cache.put(uid, new Entry(username.trim(), System.currentTimeMillis()));
    }

    public void resolve(@NonNull final String uid, @NonNull Callback callback) {
        Entry cached = cache.get(uid);
        if (cached != null && !cached.isExpired()) {
            callback.onResult(cached.username);
            return;
        }
        synchronized (pending) {
            List<Callback> waiters = pending.get(uid);
            if (waiters != null) { // a read for this uid is already in flight
                waiters.add(callback);
                return;
            }
            List<Callback> fresh = new ArrayList<>();
            fresh.add(callback);
            pending.put(uid, fresh);
        }
        FirebaseUtils.getUserRef(uid).child("username").addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                String u = snapshot.getValue(String.class);
                deliver(uid, u != null ? u.trim() : "", true);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                deliver(uid, "", false); // don't cache a failed read
            }
        });
    }

    /** Warms the cache for a page of uids (dedup'd against cache + in-flight). */
    public void resolveBatch(@Nullable Collection<String> uids) {
        if (uids == null) return;
        for (String uid : new java.util.LinkedHashSet<>(uids)) {
            if (uid == null || uid.isEmpty() || getCached(uid) != null) continue;
            resolve(uid, u -> { /* cache warm only */ });
        }
    }

    private void deliver(String uid, String username, boolean store) {
        if (store) cache.put(uid, new Entry(username, System.currentTimeMillis()));
        List<Callback> waiters;
        synchronized (pending) { waiters = pending.remove(uid); }
        if (waiters == null) return;
        for (Callback cb : waiters) cb.onResult(username);
    }
}
