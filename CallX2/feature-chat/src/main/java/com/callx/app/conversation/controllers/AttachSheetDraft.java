package com.callx.app.conversation.controllers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory draft of the attach sheet (picked media + caption + HD / view-once flags), so a
 * rotation (Activity recreate) or an accidental swipe-dismiss doesn't throw the selection away.
 * Keyed per surface/chat (see AttachSheetRecentMediaBinder#draftKey), expires after
 * {@link #TTL_MS}, cleared on send/edit. Holds only Uris + strings — no Activity/View refs.
 */
final class AttachSheetDraft {

    private static final long TTL_MS = 60L * 60L * 1000L;
    private static final int MAX_DRAFTS = 8;

    static final class Draft {
        final List<RecentMediaLoader.Item> items;
        final String caption;
        final boolean hd, viewOnce;
        final long savedAt;

        Draft(List<RecentMediaLoader.Item> items, String caption, boolean hd, boolean viewOnce) {
            this.items = new ArrayList<>(items);
            this.caption = caption == null ? "" : caption;
            this.hd = hd;
            this.viewOnce = viewOnce;
            this.savedAt = System.currentTimeMillis();
        }
    }

    private static final Map<String, Draft> DRAFTS = new LinkedHashMap<String, Draft>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Draft> eldest) {
            return size() > MAX_DRAFTS;
        }
    };

    private AttachSheetDraft() {}

    /** Saves the draft, or removes it when there is nothing worth keeping. */
    static synchronized void save(String key, List<RecentMediaLoader.Item> items, String caption,
                                  boolean hd, boolean viewOnce) {
        if (key == null) return;
        boolean empty = (items == null || items.isEmpty()) && (caption == null || caption.trim().isEmpty());
        if (empty) { DRAFTS.remove(key); return; }
        DRAFTS.put(key, new Draft(items != null ? items : new ArrayList<>(), caption, hd, viewOnce));
    }

    /** Returns the live draft for this key (null if none/expired). Does not remove it. */
    static synchronized Draft peek(String key) {
        if (key == null) return null;
        Draft d = DRAFTS.get(key);
        if (d == null) return null;
        if (System.currentTimeMillis() - d.savedAt > TTL_MS) { DRAFTS.remove(key); return null; }
        return d;
    }

    static synchronized void clear(String key) {
        if (key != null) DRAFTS.remove(key);
    }
}
