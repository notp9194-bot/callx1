package com.callx.app.conversation;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.LruCache;

/**
 * Chat-header partner flags (Reel / X / YouTube badges) kept across chat
 * re-opens so setupToolbar() can show the right icons on the FIRST frame
 * instead of waiting for the deferred partner-node read (pop-in).
 *
 * L1: process-wide LruCache (zero-cost, sync).
 * L2: tiny SharedPreferences entry per uid ("ts|flags"), read only on an L1
 *     miss (cold start), written with apply() (async, never blocks UI).
 *
 * A fresh entry (TTL) also lets ChatActivity skip the partner-node Firebase
 * read entirely when the avatar is already known.
 */
final class PartnerHeaderCache {
    static final int F_REEL = 1, F_X = 2, F_YT = 4;
    private static final long TTL_MS = 10L * 60L * 1000L;
    private static final String PREFS = "partner_header_flags";
    private static final LruCache<String, Entry> MEM = new LruCache<>(64);

    static final class Entry {
        final int flags;
        final long ts;
        Entry(int flags, long ts) { this.flags = flags; this.ts = ts; }
    }

    private PartnerHeaderCache() {}

    static Entry get(Context c, String uid) {
        if (uid == null || uid.isEmpty()) return null;
        Entry e = MEM.get(uid);
        if (e != null) return e;
        try {
            String raw = c.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(uid, null);
            if (raw == null) return null;
            int i = raw.indexOf('|');
            if (i <= 0) return null;
            e = new Entry(Integer.parseInt(raw.substring(i + 1)), Long.parseLong(raw.substring(0, i)));
            MEM.put(uid, e);
            return e;
        } catch (Throwable t) {
            return null;
        }
    }

    static void put(Context c, String uid, int flags) {
        if (uid == null || uid.isEmpty()) return;
        Entry old = MEM.get(uid);
        long now = System.currentTimeMillis();
        MEM.put(uid, new Entry(flags, now));
        // Skip the disk write when nothing changed and the entry is still fresh.
        if (old != null && old.flags == flags && now - old.ts < TTL_MS / 2) return;
        try {
            SharedPreferences sp = c.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sp.edit().putString(uid, now + "|" + flags).apply();
        } catch (Throwable ignored) { }
    }

    static boolean isFresh(Entry e) {
        return e != null && System.currentTimeMillis() - e.ts < TTL_MS;
    }
}
