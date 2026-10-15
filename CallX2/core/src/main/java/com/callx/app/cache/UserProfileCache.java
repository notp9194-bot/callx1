package com.callx.app.cache;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UserProfileCache — current users/{uid}/photoUrl + name, resolved once per uid per session (6h TTL).
 * Status items only carry a photo/name COPIED at post time, so a contact who changed their photo
 * (or an item with an empty copy) showed a stale/blank avatar. The status tray prefers this value
 * and falls back to the item's copy.
 */
public final class UserProfileCache {
    private static final long TTL_MS = 6L * 60 * 60 * 1000;
    private static UserProfileCache sInstance;

    private static final class Entry {
        final String photo, name; final long at;
        Entry(String photo, String name) { this.photo = photo; this.name = name; this.at = System.currentTimeMillis(); }
        boolean expired() { return System.currentTimeMillis() - at > TTL_MS; }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private UserProfileCache() {}
    public static synchronized UserProfileCache getInstance() {
        if (sInstance == null) sInstance = new UserProfileCache();
        return sInstance;
    }

    /** Fresh photo URL, or null if unknown / user has none (caller falls back). */
    @Nullable public String getPhoto(@Nullable String uid) {
        Entry e = uid == null ? null : cache.get(uid);
        return (e == null || e.photo.isEmpty()) ? null : e.photo;
    }

    @Nullable public String getName(@Nullable String uid) {
        Entry e = uid == null ? null : cache.get(uid);
        return (e == null || e.name.isEmpty()) ? null : e.name;
    }

    /** Resolves uids not cached yet (deduped). onChanged runs on the main thread once per batch of new data. */
    public void resolveBatch(@Nullable Collection<String> uids, @NonNull Runnable onChanged) {
        if (uids == null) return;
        for (String uid : new LinkedHashSet<>(uids)) {
            if (uid == null || uid.isEmpty()) continue;
            Entry c = cache.get(uid);
            if (c != null && !c.expired()) continue;
            synchronized (inFlight) { if (!inFlight.add(uid)) continue; }
            final String u = uid;
            FirebaseUtils.getUserRef(u).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot s) {
                    String p = s.child("photoUrl").getValue(String.class);
                    String n = s.child("name").getValue(String.class);
                    Entry old = cache.get(u);
                    cache.put(u, new Entry(p == null ? "" : p, n == null ? "" : n));
                    synchronized (inFlight) { inFlight.remove(u); }
                    boolean changed = old == null || !old.photo.equals(p == null ? "" : p) || !old.name.equals(n == null ? "" : n);
                    if (changed) main.post(onChanged);
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {
                    synchronized (inFlight) { inFlight.remove(u); }
                }
            });
        }
    }
}
