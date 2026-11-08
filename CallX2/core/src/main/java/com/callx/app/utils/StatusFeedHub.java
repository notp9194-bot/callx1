package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * v757 — client side of the server-built status feed (see "STATUS FEED FAN-OUT" in functions/index.js).
 *
 * Replaces "one status/{contactUid} listener per contact" with ONE listener on
 * statusFeed/{me} (+ a tiny statusFeedMeta/{me} marker). The feed only lists contacts that
 * currently have a live status this user may see, so screens attach status/{owner} listeners
 * for those owners only.
 *
 * States:
 *   PENDING      – feed/meta snapshot not here yet (callers wait; do not attach per-contact listeners)
 *   READY        – server has built this user's feed; trust {@link #owners}
 *   UNAVAILABLE  – nothing usable after FALLBACK_MS (server down / feature not deployed):
 *                  callers fall back to the old per-contact listeners. If the feed becomes READY
 *                  later, callers are notified again and switch over.
 *
 * Threading: every method must be called from the main thread (Firebase callbacks already are).
 */
public final class StatusFeedHub {
    private static final String TAG = "StatusFeedHub";

    public enum State { PENDING, READY, UNAVAILABLE }

    /** One owner's row of statusFeed/{me}. */
    public static final class Entry {
        public String ownerUid;
        public int    n;          // live statuses visible to me
        public long   ts;         // newest status timestamp
        public long   exp;        // earliest effective expiry among the live ones
        public String id;         // newest status id
        public String type;       // newest status type
        public String thumb;      // newest status thumbnail url
        public String media;      // newest status media url
        public String text;       // newest status text/caption snippet
        public final Set<String> ids = new HashSet<>(); // visible live status ids (newest <= 40)
    }

    public interface Listener {
        void onFeed(@NonNull State state, @NonNull Map<String, Entry> owners);
    }

    private static final long FALLBACK_MS      = 5_000L;
    private static final long REBUILD_AFTER_MS = 6L * 60 * 60 * 1000;
    private static final long OWNER_SYNC_DEBOUNCE_MS = 2_500L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient HTTP = new OkHttpClient();

    private static final List<Listener> listeners = new ArrayList<>();
    private static String uid;
    private static ValueEventListener feedListener;
    private static ValueEventListener metaListener;
    private static boolean feedLoaded, metaLoaded, fallbackFired, rebuildRequested;
    private static long builtAt;
    private static State state = State.PENDING;
    private static Map<String, Entry> owners = new LinkedHashMap<>();

    private static final Runnable FALLBACK = new Runnable() {
        @Override public void run() {
            fallbackFired = true;
            resolveAndDispatch();
        }
    };

    private StatusFeedHub() {}

    // ── Public API ──────────────────────────────────────────────────────────

    /** Registers a listener and starts the shared feed listeners on first use. Delivers current state immediately if known. */
    public static void attach(String myUid, Listener l) {
        if (myUid == null || myUid.isEmpty() || l == null) return;
        if (!myUid.equals(uid)) { stopListening(); uid = myUid; }
        if (!listeners.contains(l)) listeners.add(l);
        if (feedListener == null) startListening();
        else if (state != State.PENDING) l.onFeed(state, Collections.unmodifiableMap(owners));
    }

    /** Unregisters a listener; the shared Firebase listeners stop when the last one leaves. */
    public static void detach(Listener l) {
        listeners.remove(l);
        if (listeners.isEmpty()) stopListening();
    }

    public static State state() { return state; }

    public static Map<String, Entry> owners() { return Collections.unmodifiableMap(owners); }

    /** Ask the server to refresh my contacts' feeds (call after my statuses changed). Debounced. */
    public static void syncOwnerSoon() {
        MAIN.removeCallbacks(OWNER_SYNC);
        MAIN.postDelayed(OWNER_SYNC, OWNER_SYNC_DEBOUNCE_MS);
    }

    private static final Runnable OWNER_SYNC = new Runnable() {
        @Override public void run() { post("owner", false, null); }
    };

    // ── Own-status watcher: one place that notices EVERY post/delete/privacy change ─────────

    private static ValueEventListener ownListener;
    private static String ownUid;

    /**
     * Watches status/{me} and calls {@link #syncOwnerSoon()} whenever the structural fields
     * (ids, deleted, expiry, privacy) differ from what the server was last told. The last-synced
     * fingerprint is persisted, so posts made offline or while the app was killed are synced on
     * the next launch. Counters (views/reactions/replies) do not change the fingerprint.
     */
    public static void startOwnStatusWatch(Context ctx, String myUid) {
        if (ctx == null || myUid == null || myUid.isEmpty()) return;
        if (ownListener != null && myUid.equals(ownUid)) return;
        stopOwnStatusWatch();
        final SharedPreferences sp =
                ctx.getApplicationContext().getSharedPreferences("status_feed_sync", Context.MODE_PRIVATE);
        final String key = "fp_" + myUid;
        ownUid = myUid;
        ownListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                final String fp = fingerprint(snap);
                final String prev = sp.getString(key, null);
                if (fp.equals(prev)) return;
                if (prev == null && !snap.exists()) { sp.edit().putString(key, fp).apply(); return; }
                MAIN.removeCallbacks(OWNER_SYNC);
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        // persist only after the server accepted the sync, so a failed/offline attempt is retried
                        post("owner", false, new Runnable() {
                            @Override public void run() { sp.edit().putString(key, fp).apply(); }
                        });
                    }
                }, OWNER_SYNC_DEBOUNCE_MS);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        FirebaseUtils.getStatusRef().child(myUid).addValueEventListener(ownListener);
    }

    public static void stopOwnStatusWatch() {
        if (ownListener != null && ownUid != null) {
            try { FirebaseUtils.getStatusRef().child(ownUid).removeEventListener(ownListener); }
            catch (Exception ignored) {}
        }
        ownListener = null;
        ownUid = null;
    }

    private static String fingerprint(DataSnapshot own) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (DataSnapshot st : own.getChildren()) {
            count++;
            sb.append(st.getKey()).append('|')
              .append(st.child("deleted").getValue()).append('|')
              .append(st.child("expiresAt").getValue()).append('|')
              .append(st.child("timestamp").getValue()).append('|')
              .append(st.child("scheduledAt").getValue()).append('|')
              .append(st.child("privacy").getValue()).append('|')
              .append(st.child("isCloseFriends").getValue()).append('|');
            for (DataSnapshot p : st.child("privacyList").getChildren()) sb.append(p.getValue()).append(',');
            sb.append(';');
        }
        return Integer.toHexString(sb.toString().hashCode()) + ":" + count;
    }

    // ── Feed listening ──────────────────────────────────────────────────────

    private static void startListening() {
        final String me = uid;
        if (me == null) return;
        feedLoaded = false; metaLoaded = false; fallbackFired = false; rebuildRequested = false;
        builtAt = 0L;
        state = State.PENDING;
        owners = new LinkedHashMap<>();

        metaListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                Long b = snap.child("builtAt").getValue(Long.class);
                builtAt = b == null ? 0L : b;
                metaLoaded = true;
                if (!rebuildRequested && (builtAt == 0L
                        || System.currentTimeMillis() - builtAt > REBUILD_AFTER_MS)) {
                    rebuildRequested = true;
                    post("viewer", builtAt == 0L, null); // first build is forced; refreshes are throttled server-side
                }
                resolveAndDispatch();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                metaLoaded = true; // rules/auth problem -> let the fallback decide
                resolveAndDispatch();
            }
        };
        feedListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                final Map<String, Entry> next = new LinkedHashMap<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String k = c.getKey();
                    if (k == null || k.isEmpty()) continue;
                    Entry e = parseEntry(k, c);
                    if (e.n > 0) next.put(k, e);
                }
                owners = next;
                feedLoaded = true;
                resolveAndDispatch();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                feedLoaded = true;
                resolveAndDispatch();
            }
        };
        FirebaseUtils.db().getReference("statusFeedMeta").child(me).addValueEventListener(metaListener);
        FirebaseUtils.db().getReference("statusFeed").child(me).addValueEventListener(feedListener);
        MAIN.removeCallbacks(FALLBACK);
        MAIN.postDelayed(FALLBACK, FALLBACK_MS);
    }

    private static void stopListening() {
        MAIN.removeCallbacks(FALLBACK);
        final String me = uid;
        if (me != null) {
            try {
                if (metaListener != null)
                    FirebaseUtils.db().getReference("statusFeedMeta").child(me).removeEventListener(metaListener);
                if (feedListener != null)
                    FirebaseUtils.db().getReference("statusFeed").child(me).removeEventListener(feedListener);
            } catch (Exception ignored) {}
        }
        metaListener = null;
        feedListener = null;
        feedLoaded = false; metaLoaded = false; fallbackFired = false; rebuildRequested = false;
        builtAt = 0L;
        state = State.PENDING;
        owners = new LinkedHashMap<>();
        uid = null;
    }

    private static Entry parseEntry(String owner, DataSnapshot c) {
        Entry e = new Entry();
        e.ownerUid = owner;
        Long n = c.child("n").getValue(Long.class);
        Long ts = c.child("ts").getValue(Long.class);
        Long exp = c.child("exp").getValue(Long.class);
        e.n = n == null ? 0 : n.intValue();
        e.ts = ts == null ? 0L : ts;
        e.exp = exp == null ? 0L : exp;
        e.id = c.child("id").getValue(String.class);
        e.type = c.child("ty").getValue(String.class);
        e.thumb = c.child("th").getValue(String.class);
        e.media = c.child("mu").getValue(String.class);
        e.text = c.child("tx").getValue(String.class);
        for (DataSnapshot idSnap : c.child("ids").getChildren()) {
            if (idSnap.getKey() != null) e.ids.add(idSnap.getKey());
        }
        return e;
    }

    private static void resolveAndDispatch() {
        State next;
        if (metaLoaded && feedLoaded && builtAt > 0L) next = State.READY;
        else if (fallbackFired) next = State.UNAVAILABLE;
        else next = State.PENDING;
        state = next;
        if (next == State.PENDING) return; // nothing useful to tell callers yet
        final Map<String, Entry> view = Collections.unmodifiableMap(owners);
        for (Listener l : new ArrayList<>(listeners)) {
            try { l.onFeed(next, view); }
            catch (Exception e) { Log.w(TAG, "listener failed: " + e.getMessage()); }
        }
    }

    // ── Server call ─────────────────────────────────────────────────────────

    private static void post(final String op, final boolean force, final Runnable onSuccess) {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) return;
        u.getIdToken(false).addOnSuccessListener(result -> {
            try {
                String token = result.getToken();
                if (token == null) return;
                JSONObject body = new JSONObject().put("op", op).put("force", force);
                Request req = new Request.Builder()
                        .url(Constants.SERVER_URL + "/status/feed/sync")
                        .header("Authorization", "Bearer " + token)
                        .post(RequestBody.create(body.toString(),
                                MediaType.parse("application/json; charset=utf-8")))
                        .build();
                HTTP.newCall(req).enqueue(new Callback() {
                    @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.w(TAG, "feed sync (" + op + ") failed: " + e.getMessage());
                    }
                    @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try {
                            if (response.isSuccessful()) {
                                if (onSuccess != null) onSuccess.run();
                            } else {
                                Log.w(TAG, "feed sync (" + op + ") HTTP " + response.code());
                            }
                        } finally {
                            response.close();
                        }
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "feed sync (" + op + ") error: " + e.getMessage());
            }
        });
    }
}
