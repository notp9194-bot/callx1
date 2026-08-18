package com.callx.app.utils;

import android.os.Handler;
import android.os.Looper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ExpiryTickManager — single shared {@link Handler} that drives ALL disappearing-message
 * countdowns in the chat screen.
 *
 * PERF FIX: Previously every bound message with an active expiry ran its own
 * {@code android.os.CountDownTimer}. A CountDownTimer is backed by its own Handler +
 * Message posting loop; with 100 disappearing messages visible/recycled across a session
 * that's 100 independent timer objects all firing onTick() every second — wasted wakeups,
 * GC churn from repeated boxed Long posts, and 100x the work for what is visually just
 * "update some TextViews once a second".
 *
 * NEW: one Handler, one Runnable, one postDelayed(1000) loop for the whole RecyclerView.
 * Each bound ViewHolder registers itself (keyed by the holder instance) with its
 * expiresAt timestamp + a tiny Listener. Every tick we walk the registry once and push
 * updates to whichever entries are still alive. The loop self-stops when the registry is
 * empty and restarts lazily on the next register() call — so an idle chat (no disappearing
 * messages on screen) costs nothing.
 */
public final class ExpiryTickManager {

    public interface Listener {
        void onTick(long remainingMs);
        void onFinish();
    }

    private static final long TICK_INTERVAL_MS = 1000L;

    private static final ExpiryTickManager INSTANCE = new ExpiryTickManager();

    public static ExpiryTickManager get() { return INSTANCE; }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Object, Entry> entries = new ConcurrentHashMap<>();
    private boolean ticking = false;

    private ExpiryTickManager() {}

    private static final class Entry {
        final long expiresAt;
        final Listener listener;
        Entry(long expiresAt, Listener listener) {
            this.expiresAt = expiresAt;
            this.listener = listener;
        }
    }

    /**
     * Register (or replace) the countdown for {@code key} — typically the RecyclerView
     * ViewHolder instance currently bound to this message. Replacing is implicit: calling
     * register() again with the same key simply overwrites the previous entry, so callers
     * don't need to separately "cancel" before re-binding — same ergonomics as the old
     * CountDownTimer.cancel() + new CountDownTimer() pattern, minus the extra object.
     */
    public void register(Object key, long expiresAt, Listener listener) {
        if (key == null || listener == null) return;
        entries.put(key, new Entry(expiresAt, listener));
        ensureTicking();
    }

    /** Call on rebind-with-no-expiry and on onViewRecycled to stop updates for this holder. */
    public void unregister(Object key) {
        if (key == null) return;
        entries.remove(key);
    }

    private void ensureTicking() {
        if (ticking) return;
        ticking = true;
        handler.postDelayed(tick, TICK_INTERVAL_MS);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!entries.isEmpty()) {
                long now = System.currentTimeMillis();
                for (Map.Entry<Object, Entry> e : entries.entrySet()) {
                    Entry entry = e.getValue();
                    long remaining = entry.expiresAt - now;
                    if (remaining <= 0) {
                        entries.remove(e.getKey());
                        entry.listener.onFinish();
                    } else {
                        entry.listener.onTick(remaining);
                    }
                }
            }
            if (entries.isEmpty()) {
                ticking = false; // loop stops; register() will restart it on demand
            } else {
                handler.postDelayed(tick, TICK_INTERVAL_MS);
            }
        }
    };
}
