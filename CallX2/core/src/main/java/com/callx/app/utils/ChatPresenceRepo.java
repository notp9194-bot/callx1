package com.callx.app.utils;

import androidx.annotation.NonNull;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * WHATSAPP-STYLE SHARED PRESENCE LAYER.
 *
 * ChatPresenceController used to attach its own fresh Firebase
 * ValueEventListener for every one of its 7 paths (online/last-seen,
 * typing, in-chat-screen, per-message viewing, typing-reply-target,
 * recording, mute) the instant a chat screen opened, and tear every one of
 * them straight back down in onDestroy(). Reopen the same 1:1 chat a few
 * seconds later — extremely common: back button -> tap the same
 * conversation again, or a notification tap while it was already open a
 * moment ago — and all 7 re-subscribe from scratch: brand-new network
 * round-trips that re-fire every "initial value" callback ("bar bar bing")
 * even though nothing about the partner's presence actually changed.
 *
 * This class keeps ONE real Firebase listener alive per path, shared by
 * however many ChatPresenceController instances currently care about it,
 * and keeps it alive for a short grace period after the last one leaves
 * instead of detaching immediately — exactly like WhatsApp doesn't
 * re-query presence every time you bounce in and out of a conversation.
 * A subscriber that arrives while the path is still warm gets the cached
 * value replayed instantly, with zero network round-trip.
 *
 * Callers only ever get back a plain Runnable to call on unsubscribe — no
 * Activity/delegate reference is ever held inside this class, so there is
 * no risk of leaking a destroyed ChatActivity: only the raw Firebase
 * listener + its last known value are kept warm during the grace window.
 */
public final class ChatPresenceRepo {

    /** How long a path's real Firebase listener is kept attached after the
     *  last local observer leaves, before actually detaching it. */
    private static final long GRACE_MS = 15_000L;

    private static final ChatPresenceRepo INSTANCE = new ChatPresenceRepo();
    public static ChatPresenceRepo get() { return INSTANCE; }
    private ChatPresenceRepo() {}

    public interface Observer {
        void onChanged(DataSnapshot snapshot);
    }

    private static final class Entry {
        final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
        DatabaseReference ref;
        ValueEventListener firebaseListener;
        DataSnapshot lastValue;
        boolean hasValue;
        Runnable pendingDetach;
    }

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * Subscribes to a Firebase path, shared across every current caller
     * using the same {@code key}. Returns a Runnable — call it once (from
     * the caller's teardown/release) to unsubscribe; the underlying
     * Firebase listener is only actually detached once every subscriber for
     * that key has unsubscribed AND the grace window has elapsed without
     * a new one arriving.
     *
     * @param key    stable identity for this path, e.g. "typing:" + chatId
     * @param ref    the DatabaseReference to attach to on first subscribe;
     *               ignored on subsequent calls for an already-warm key
     * @param observer callback invoked on every value change, and once
     *                 immediately/synchronously if a cached value is already warm
     */
    public Runnable observe(String key, DatabaseReference ref, Observer observer) {
        Entry entry;
        synchronized (entries) {
            entry = entries.get(key);
            if (entry == null) {
                entry = new Entry();
                entry.ref = ref;
                entries.put(key, entry);
            }
            if (entry.pendingDetach != null) {
                // Reopened within the grace window — cancel the scheduled
                // teardown, the underlying Firebase subscription never left.
                handler.removeCallbacks(entry.pendingDetach);
                entry.pendingDetach = null;
            }
            entry.observers.add(observer);
            if (entry.firebaseListener == null) {
                final Entry fe = entry;
                entry.firebaseListener = new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                        fe.lastValue = snapshot;
                        fe.hasValue = true;
                        for (Observer o : fe.observers) o.onChanged(snapshot);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError error) {}
                };
                entry.ref.addValueEventListener(entry.firebaseListener);
            } else if (entry.hasValue) {
                // Instant paint from the cached value — no need to wait for
                // Firebase to re-deliver something it already told us.
                observer.onChanged(entry.lastValue);
            }
        }
        final Entry capturedEntry = entry;
        return () -> unsubscribe(key, capturedEntry, observer);
    }

    private void unsubscribe(String key, Entry entry, Observer observer) {
        entry.observers.remove(observer);
        if (!entry.observers.isEmpty()) return;
        entry.pendingDetach = () -> {
            synchronized (entries) {
                if (!entry.observers.isEmpty()) return; // someone re-subscribed first
                if (entry.firebaseListener != null) {
                    entry.ref.removeEventListener(entry.firebaseListener);
                }
                entries.remove(key);
            }
        };
        handler.postDelayed(entry.pendingDetach, GRACE_MS);
    }
}
