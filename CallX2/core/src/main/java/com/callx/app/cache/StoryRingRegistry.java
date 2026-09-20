package com.callx.app.cache;

import android.content.Context;
import android.view.View;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * StoryRingRegistry — makes "seen" propagate to EVERY open screen instantly,
 * Instagram-style, instead of only the screen the user actually watched the
 * story from.
 *
 * Backstory: {@link StatusCacheManager} was already a correct single source
 * of truth (one Firebase listener, in-memory statusMap/seenMap, instant
 * local optimistic mark via StorySeenState) — but only HomeFragment and
 * StatusFragment ever called {@code addObserver()} on it. Every other
 * ring — chat list, call history, followers/following/mutual, discover
 * people, suggested accounts, close friends, collab inbox, notifications,
 * reel comments, posts/reels grids, etc — read the cache exactly ONCE at
 * bind() time and never found out when it changed. So marking a story seen
 * on one screen left the ring stale everywhere else until that screen was
 * torn down and recreated.
 *
 * Fix: instead of hand-wiring addObserver()/notifyDataSetChanged() into
 * ~35 separate adapters/activities (lifecycle-error-prone, easy to miss a
 * spot), every ring-drawing call site registers ONE small "how do I redraw
 * myself" callback here, keyed by the ring View itself. This class
 * subscribes to StatusCacheManager exactly once (first registration, for
 * the whole app's lifetime) and on every update just re-runs every
 * currently-attached ring's callback — so any ring on screen, on any
 * screen, in any module, updates the instant Firebase's statusSeen node
 * changes (or the moment a story is watched locally, thanks to
 * StorySeenState's optimistic mark already flowing through hasUnseen()).
 *
 * Usage at a ring bind site (see StoryRingApplier, ChatListAdapter,
 * CallHistoryAdapter for real examples):
 * <pre>
 *   Runnable refresh = () -> { ...read StatusCacheManager, set ring state... };
 *   refresh.run();                                   // paint it now
 *   StoryRingRegistry.register(ctx, ringView, refresh); // and keep it live
 * </pre>
 * Safe to call on every bind/rebind of a recycled RecyclerView row — each
 * call simply replaces this View's previous callback with the latest one
 * (latest uid), so a recycled row never fires a stale row's refresh logic.
 */
public final class StoryRingRegistry {

    // View → "redraw this ring" callback. WeakHashMap so rows/views that
    // are gone (Activity destroyed, row view GC'd) fall out on their own —
    // no manual unregister() needed at any of the ~35 call sites.
    private static final Map<View, Runnable> RINGS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile boolean subscribed = false;

    private StoryRingRegistry() {}

    /**
     * Register (or replace) the refresh callback for a ring view.
     * Call this every time you bind/paint a ring — cheap map put, and it
     * guarantees this View always points at the CURRENT row's refresh
     * logic even after RecyclerView recycles it into a different uid.
     */
    public static void register(Context ctx, View ringHostView, Runnable refresh) {
        if (ringHostView == null || refresh == null) return;
        RINGS.put(ringHostView, refresh);
        ensureSubscribed(ctx);
    }

    private static void ensureSubscribed(Context ctx) {
        if (subscribed || ctx == null) return;
        synchronized (StoryRingRegistry.class) {
            if (subscribed) return;
            subscribed = true;
            StatusCacheManager.getInstance(ctx).addObserver(StoryRingRegistry::refreshAll);
        }
    }

    /**
     * Fired by StatusCacheManager whenever status data OR seen data changes
     * (a story posted/expired, or statusSeen/{myUid} gets a new write from
     * marking a story seen anywhere in the app). Re-runs every currently
     * attached ring's own refresh logic.
     */
    private static void refreshAll() {
        // Snapshot under the lock, then run callbacks outside it — a
        // refresh callback touching a View can indirectly trigger layout
        // callbacks; never want to be holding RINGS' lock for that.
        View[] views;
        Runnable[] callbacks;
        synchronized (RINGS) {
            views = RINGS.keySet().toArray(new View[0]);
            callbacks = new Runnable[views.length];
            for (int i = 0; i < views.length; i++) {
                callbacks[i] = RINGS.get(views[i]);
            }
        }
        for (int i = 0; i < views.length; i++) {
            View v = views[i];
            Runnable r = callbacks[i];
            if (v == null || r == null) continue;
            // Detached/recycled rows will get the correct state on their
            // next real bind() anyway — skip so we don't paint into a row
            // that's currently showing a completely different uid.
            if (!v.isAttachedToWindow()) continue;
            try {
                r.run();
            } catch (Exception ignored) {}
        }
    }
}
