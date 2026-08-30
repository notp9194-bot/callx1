package com.callx.app.cache;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.callx.app.db.AppDatabase;
import com.callx.app.db.dao.UserDao;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AvatarVersionSyncManager — Instagram-style avatar delta-sync.
 *
 * PROBLEM this replaces: {@link com.callx.app.repository.UserRepository#attachLiveUser}
 * attaches a {@link ValueEventListener} on the WHOLE "users/{uid}" node —
 * every screen that only cares "did this person's avatar change" (a reel's
 * owner-avatar bind, a follow-list row, AvatarPrefetcher) was paying for a
 * full-user-object payload + deserialize on EVERY unrelated field change
 * (bio edit, online flag flip, lastSeen tick...) just to notice the one
 * field it actually needed. On a screen watching many users at once (a
 * following list, close-friends pre-warm) that's N full snapshots instead
 * of N tiny scalar reads.
 *
 * This manager listens ONLY on "users/{uid}/avatarVersion" — a single long
 * — per user. A real avatar change is a version bump (see ProfileActivity's
 * upload flow / UserEntity#avatarVersion), so this is the ONE field that
 * actually needs to be live; everything else (name, bio, photoUrl string
 * itself) can stay on whatever slower-refresh path it already had.
 *
 * REFCOUNTED per uid: many call sites can watch() the same uid (e.g. the
 * same creator appears in the feed AND in a follow list at once) without
 * multiplying Firebase listeners — exactly one "avatarVersion" listener is
 * ever attached per uid, detached only once the last watcher unwatch()es.
 *
 * Every real version change also opportunistically refreshes Room's cached
 * avatarVersion column (UserDao#updateAvatarVersion) so any other screen
 * reading from Room (not watching live) still converges quickly, without
 * needing its own live listener at all.
 */
public final class AvatarVersionSyncManager {

    public interface Listener {
        /** Called on the main thread whenever uid's avatarVersion changes (never fired for a no-op re-write of the same value). */
        void onAvatarVersionChanged(@NonNull String uid, long newVersion);
    }

    private static volatile AvatarVersionSyncManager sInstance;

    public static AvatarVersionSyncManager getInstance(Context ctx) {
        AvatarVersionSyncManager instance = sInstance;
        if (instance == null) {
            synchronized (AvatarVersionSyncManager.class) {
                instance = sInstance;
                if (instance == null) {
                    instance = new AvatarVersionSyncManager(ctx.getApplicationContext());
                    sInstance = instance;
                }
            }
        }
        return instance;
    }

    private static final class Watch {
        DatabaseReference ref;
        ValueEventListener fbListener;
        volatile long lastKnownVersion = -1L; // -1 = not yet resolved
        final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    }

    private final UserDao userDao;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Watch> watches = new ConcurrentHashMap<>();

    private AvatarVersionSyncManager(Context appCtx) {
        this.userDao = AppDatabase.getInstance(appCtx).userDao();
    }

    /**
     * Attach a targeted avatarVersion watch for uid. Safe to call repeatedly
     * for the same (uid, listener) pair — Firebase's own dedup means a
     * second addValueEventListener with the same instance is a no-op, but
     * callers should still pair every watch() with exactly one unwatch()
     * (e.g. onBecameVisible/onBecameInvisible) to keep the refcount honest.
     */
    public void watch(String uid, @NonNull Listener listener) {
        if (uid == null || uid.isEmpty()) return;
        Watch w = watches.computeIfAbsent(uid, this::createWatch);
        w.listeners.add(listener);
        if (w.lastKnownVersion >= 0) {
            // Late subscriber — replay the last-known value immediately instead
            // of leaving it waiting for the NEXT change to ever fire.
            long v = w.lastKnownVersion;
            mainHandler.post(() -> listener.onAvatarVersionChanged(uid, v));
        }
    }

    /** Detach one listener; the underlying Firebase listener is removed only once nobody is left watching this uid. */
    public void unwatch(String uid, Listener listener) {
        if (uid == null || listener == null) return;
        Watch w = watches.get(uid);
        if (w == null) return;
        w.listeners.remove(listener);
        if (w.listeners.isEmpty()) {
            watches.remove(uid);
            if (w.ref != null && w.fbListener != null) w.ref.removeEventListener(w.fbListener);
        }
    }

    private Watch createWatch(String uid) {
        Watch w = new Watch();
        // TARGETED path — "users/{uid}/avatarVersion", never "users/{uid}" whole.
        w.ref = FirebaseUtils.getUserRef(uid).child("avatarVersion");
        w.fbListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Long boxed = snap.getValue(Long.class);
                long version = boxed != null ? boxed : 0L;
                if (version == w.lastKnownVersion) return; // diff — no real change, don't re-notify
                w.lastKnownVersion = version;
                dbExecutor.execute(() -> userDao.updateAvatarVersion(uid, version));
                for (Listener l : w.listeners) l.onAvatarVersionChanged(uid, version);
            }
            @Override public void onCancelled(@NonNull DatabaseError error) { /* transient — next write re-fires */ }
        };
        w.ref.addValueEventListener(w.fbListener);
        return w;
    }

    /**
     * Synchronous best-effort read of whatever version this manager last
     * observed for uid (from an active watch() or a prior resolve), or 0 if
     * nothing has been resolved yet. Unlike watch(), this never attaches a
     * new Firebase listener — it's for call sites that bind MANY rows at
     * once (e.g. StatusListAdapter's contact list/carousel) where attaching
     * a live per-row listener for every row would defeat the whole point of
     * this class being a targeted, refcounted watch rather than N listeners.
     * Those call sites can pass this straight into
     * AvatarUrlBuilder's avatarVersion param: 0 means "no change known yet"
     * (same unversioned behavior as before this existed), and a real bump —
     * once ANY screen's watch() or fetchVersionsOnce() has observed it —
     * is picked up by every subsequent bind automatically.
     */
    public long getCachedVersion(String uid) {
        if (uid == null || uid.isEmpty()) return 0L;
        Watch w = watches.get(uid);
        return (w != null && w.lastKnownVersion > 0) ? w.lastKnownVersion : 0L;
    }

    // ── One-shot batch resolve (used by AvatarPreWarmWorker) ────────────────

    public interface BatchCallback { void onDone(@NonNull Map<String, Long> uidToVersion); }

    /**
     * Resolves avatarVersion for a whole uid list in one pass — still one
     * targeted "avatarVersion" child read PER uid (never a bulk whole-node
     * fetch of "users/"), just batched into a single callback so a caller
     * like AvatarPreWarmWorker doesn't have to hand-roll its own counting.
     */
    public void fetchVersionsOnce(List<String> uids, @NonNull BatchCallback callback) {
        if (uids == null || uids.isEmpty()) {
            callback.onDone(new HashMap<>());
            return;
        }
        Map<String, Long> result = new ConcurrentHashMap<>();
        int[] remaining = {uids.size()};
        for (String uid : uids) {
            FirebaseUtils.getUserRef(uid).child("avatarVersion")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        Long v = snap.getValue(Long.class);
                        result.put(uid, v != null ? v : 0L);
                        finishOne();
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) { finishOne(); }

                    private void finishOne() {
                        synchronized (remaining) {
                            remaining[0]--;
                            if (remaining[0] <= 0) mainHandler.post(() -> callback.onDone(result));
                        }
                    }
                });
        }
    }
}
