package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HighlightSeenState — app-wide optimistic local cache of "has the current
 * viewer already opened this highlight album", keyed by ownerUid + albumId.
 *
 * WHY THIS EXISTS: a highlight album is permanent (unlike a 24h story), so
 * seen-state can't be derived by comparing timestamps the way
 * {@link StorySeenState} does for regular stories — it's a flat "opened or
 * not" flag per (viewer, album) pair. Mirrors StorySeenState's design: mark
 * locally the instant the album is tapped, so the ring flips from gradient
 * to gray on the very next bind with zero network wait, then
 * StatusHighlightManager.markHighlightSeen() syncs the same fact to
 * Firebase (highlightSeen/{viewerUid}/{ownerUid}/{albumId}) in the
 * background so it's picked up on other devices/sessions too.
 *
 * Backed by SharedPreferences so the mark survives process death. The
 * in-memory set is the hot path — every read after the first for a given
 * (owner, album) pair is a plain ConcurrentHashMap-backed lookup.
 */
public final class HighlightSeenState {

    private static final String PREFS_NAME = "highlight_seen_state";

    private static volatile SharedPreferences prefs;
    private static final Set<String> memCache =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private HighlightSeenState() {}

    private static String key(String ownerUid, String albumId) {
        return ownerUid + "|" + albumId;
    }

    /** Optimistically mark this (owner, album) pair as seen by the current viewer. */
    public static void markSeen(Context ctx, String ownerUid, String albumId) {
        if (ownerUid == null || albumId == null) return;
        String k = key(ownerUid, albumId);
        memCache.add(k);
        if (ctx != null) {
            getPrefs(ctx).edit().putBoolean(k, true).apply();
        }
    }

    /** True if we already know locally (this device) that this album was opened. */
    public static boolean isSeenLocally(Context ctx, String ownerUid, String albumId) {
        if (ownerUid == null || albumId == null) return false;
        String k = key(ownerUid, albumId);
        if (memCache.contains(k)) return true;
        if (ctx == null) return false;
        boolean fromDisk = getPrefs(ctx).getBoolean(k, false);
        if (fromDisk) memCache.add(k);
        return fromDisk;
    }

    private static SharedPreferences getPrefs(Context ctx) {
        SharedPreferences local = prefs;
        if (local == null) {
            synchronized (HighlightSeenState.class) {
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
