package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * StorySeenState — app-wide optimistic local cache of "when did I last
 * mark this uid's story as seen".
 *
 * WHY THIS EXISTS: StatusSeenTracker.markSeen()/markSeenBatch() write the
 * seen flag to Firebase, but that round-trip isn't instant — every ring
 * drawn app-wide (reels bio avatar, chat list, status feed, comments,
 * profile) would otherwise keep showing the gradient "unseen" ring for a
 * beat after the user actually viewed the story, until the Firebase
 * listener StatusCacheManager is attached to comes back around. This class
 * is the local, zero-latency side of that: markSeen() stamps "now" for a
 * uid immediately, in memory, so any ring-drawing code that consults it can
 * reflect the seen state on the very next bind — no waiting on the network.
 *
 * Backed by SharedPreferences so the mark survives process death (app
 * killed mid-story-view, reopened later — still shows seen instead of
 * flashing unseen again for a frame). The in-memory map is the hot path:
 * every read after the first for a given uid is a plain ConcurrentHashMap
 * lookup, no disk I/O.
 *
 * This was previously called from StatusSeenTracker but the class itself
 * had never actually been added to the repo — a straight compile error.
 * This is the real implementation matching the intent already documented
 * at the call sites.
 */
public final class StorySeenState {

    private static final String PREFS_NAME = "story_seen_state";

    private static volatile SharedPreferences prefs;
    private static final Map<String, Long> memCache = new ConcurrentHashMap<>();

    private StorySeenState() {}

    /** Optimistically stamp uid's story as seen as of timestampMs. Safe to call off the main thread. */
    public static void markSeen(Context ctx, String ownerUid, long timestampMs) {
        if (ownerUid == null) return;
        memCache.put(ownerUid, timestampMs);
        if (ctx != null) {
            getPrefs(ctx).edit().putLong(ownerUid, timestampMs).apply();
        }
    }

    /** Last known "seen at" timestamp for uid, or 0 if never marked seen locally. */
    public static long getSeenAt(Context ctx, String ownerUid) {
        if (ownerUid == null) return 0L;
        Long cached = memCache.get(ownerUid);
        if (cached != null) return cached;
        if (ctx == null) return 0L;
        long fromDisk = getPrefs(ctx).getLong(ownerUid, 0L);
        memCache.put(ownerUid, fromDisk);
        return fromDisk;
    }

    /** True if the given story-item timestamp is covered by a seen mark we already have locally. */
    public static boolean isSeenLocally(Context ctx, String ownerUid, long statusTimestampMs) {
        return getSeenAt(ctx, ownerUid) >= statusTimestampMs;
    }

    private static SharedPreferences getPrefs(Context ctx) {
        SharedPreferences local = prefs;
        if (local == null) {
            synchronized (StorySeenState.class) {
                local = prefs;
                if (local == null) {
                    local = ctx.getApplicationContext()
                            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs = local;
                }
            }
        }
        return local;
    }
}
