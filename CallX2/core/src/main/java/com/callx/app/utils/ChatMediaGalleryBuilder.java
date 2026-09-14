package com.callx.app.utils;

import android.util.LruCache;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.callx.app.db.ChatMediaRow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram-style chat-wide media gallery — turns
 * {@link com.callx.app.db.dao.MessageDao#getChatMediaRows(String)}'s lean,
 * timestamp-ordered row list into the flat
 * {@code List<Map<String,Object>>} MediaViewerActivity's gallery mode
 * already consumes, plus the start index of whichever item the user
 * actually tapped.
 *
 * A "multi_media" row (several photos/videos sent together) is expanded
 * in-place into its individual items, exactly like Telegram/WhatsApp
 * treat an album as several consecutive gallery pages rather than a
 * separate sub-gallery — so swiping off the end of one message's album
 * continues straight into the next message's media, and so on for the
 * whole chat.
 *
 * #1 In-memory gallery cache: the full flattened per-chat list is kept in
 * an LruCache keyed by chatId, so re-opening the gallery for the same chat
 * (very common — user backs out and taps another photo) skips the Room
 * row fetch + flatten entirely; only a tiny COUNT+MAX freshness probe
 * (MessageDao#getChatMediaFreshness) runs to make sure nothing changed.
 *
 * #2 Windowed loading: callers no longer get handed a single fixed-size
 * capped list. Instead {@link #window} slices a small ±radius window out
 * of the (cached-or-just-built) full list around the tapped item — small
 * enough to pass through an Intent extra instantly — and the full list
 * stays in this cache so MediaViewerActivity can pull further windows
 * (see {@link #slice}) as the user swipes toward either edge, with no
 * extra DB round-trip.
 *
 * Pure/offline aside from the cache — takes rows already read from Room,
 * does no I/O itself. Run on a background thread.
 */
public final class ChatMediaGalleryBuilder {

    private ChatMediaGalleryBuilder() {}

    /** Initial/step window radius — see class doc, item #2. */
    public static final int WINDOW_RADIUS = 50;

    public static final class Result {
        /** Flattened, chronologically-ordered gallery items (may be empty). */
        public final List<Map<String, Object>> items;
        /** Index of the tapped item within {@link #items}, or -1 if it couldn't be located. */
        public final int startIndex;

        Result(List<Map<String, Object>> items, int startIndex) {
            this.items = items;
            this.startIndex = startIndex;
        }
    }

    /** A window sliced out of the full per-chat list, for MediaViewerActivity's Intent. */
    public static final class Window {
        public final List<Map<String, Object>> items;
        /** Index of the tapped item within {@link #items} (local to this window). */
        public final int localStartIndex;
        /** Global index of items.get(0) within the chat's full media list. */
        public final int windowStartGlobal;
        /** Total number of media items in the chat (may be larger than items.size()). */
        public final int totalCount;

        Window(List<Map<String, Object>> items, int localStartIndex, int windowStartGlobal, int totalCount) {
            this.items = items;
            this.localStartIndex = localStartIndex;
            this.windowStartGlobal = windowStartGlobal;
            this.totalCount = totalCount;
        }
    }

    // ── #1 In-memory gallery cache ──────────────────────────────────────
    private static final class CacheEntry {
        final List<Map<String, Object>> items;
        final Map<String, int[]> idRanges; // messageId -> {startIndex, count}
        final int cnt;
        final long maxTs;

        CacheEntry(List<Map<String, Object>> items, Map<String, int[]> idRanges, int cnt, long maxTs) {
            this.items = items;
            this.idRanges = idRanges;
            this.cnt = cnt;
            this.maxTs = maxTs;
        }
    }

    // A handful of recently-viewed chats is plenty — each entry is just a
    // flat list of small maps (url/thumbUrl/mediaType strings), nothing
    // decoded/bitmapped, so this stays cheap even for a chat with a few
    // thousand media items.
    private static final LruCache<String, CacheEntry> GALLERY_CACHE = new LruCache<>(4);

    /** Drop a chat's cached gallery — call if a chat gets wiped/deleted. Not required for new incoming media (see {@link #resolve} freshness check). */
    public static void invalidate(String chatId) {
        if (chatId != null) GALLERY_CACHE.remove(chatId);
    }

    /**
     * Cache read-only lookup: returns a Result immediately if {@code chatId}
     * is cached AND {@code freshness} confirms nothing changed since it was
     * cached — no row fetch, no flattening. Returns null on any kind of
     * miss (never caches anything itself), so callers know to fall back to
     * {@link #resolve} with freshly-fetched rows.
     */
    @WorkerThread
    @Nullable
    public static Result peek(@Nullable String chatId, @Nullable com.callx.app.db.ChatMediaFreshness freshness,
                               String tappedMessageId, int tappedSubIndex) {
        if (chatId == null || freshness == null) return null;
        CacheEntry cached = GALLERY_CACHE.get(chatId);
        if (cached == null || !freshness.matches(cached.cnt, cached.maxTs)) return null;
        return new Result(cached.items, locate(cached.idRanges, tappedMessageId, tappedSubIndex));
    }

    /**
     * Flattens {@code rows} (always required — call {@link #peek} first and
     * only fall back here on a miss) and, if {@code chatId}/{@code freshness}
     * are given, caches the result for next time.
     */
    @WorkerThread
    public static Result resolve(@Nullable String chatId, List<ChatMediaRow> rows,
                                  @Nullable com.callx.app.db.ChatMediaFreshness freshness,
                                  String tappedMessageId, int tappedSubIndex) {
        Map<String, int[]> idRanges = new HashMap<>();
        List<Map<String, Object>> items = flatten(rows, idRanges);
        int start = locate(idRanges, tappedMessageId, tappedSubIndex);

        if (chatId != null && freshness != null) {
            GALLERY_CACHE.put(chatId, new CacheEntry(items, idRanges, freshness.cnt,
                    freshness.maxTs == null ? 0L : freshness.maxTs));
        }
        return new Result(items, start);
    }

    /** Legacy no-cache entry point (kept for callers that don't key off a chatId). */
    @WorkerThread
    public static Result build(List<ChatMediaRow> rows, String tappedMessageId, int tappedSubIndex) {
        Map<String, int[]> idRanges = new HashMap<>();
        List<Map<String, Object>> items = flatten(rows, idRanges);
        return new Result(items, locate(idRanges, tappedMessageId, tappedSubIndex));
    }

    @WorkerThread
    private static List<Map<String, Object>> flatten(@Nullable List<ChatMediaRow> rows, Map<String, int[]> idRangesOut) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (rows == null) return items;

        for (ChatMediaRow row : rows) {
            if (row == null) continue;

            if ("multi_media".equals(row.type) && row.mediaItemsJson != null && !row.mediaItemsJson.isEmpty()) {
                List<Map<String, Object>> group = MediaItemsJsonUtil.mediaItemsFromJson(row.mediaItemsJson);
                if (group.isEmpty()) continue;
                int base = items.size();
                items.addAll(group);
                if (row.id != null) idRangesOut.put(row.id, new int[]{base, group.size()});
            } else {
                String url = (row.mediaUrl != null && !row.mediaUrl.isEmpty()) ? row.mediaUrl : row.text;
                if (url == null || url.isEmpty()) continue; // skip malformed/still-uploading rows

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("url", url);
                if (row.thumbnailUrl != null && !row.thumbnailUrl.isEmpty()) {
                    item.put("thumbUrl", row.thumbnailUrl);
                }
                item.put("mediaType", "video".equals(row.type) ? "video" : "image");

                int idx = items.size();
                items.add(item);
                if (row.id != null) idRangesOut.put(row.id, new int[]{idx, 1});
            }
        }
        return items;
    }

    private static int locate(Map<String, int[]> idRanges, String tappedMessageId, int tappedSubIndex) {
        if (tappedMessageId == null) return -1;
        int[] range = idRanges.get(tappedMessageId);
        if (range == null) return -1;
        int sub = (tappedSubIndex >= 0 && tappedSubIndex < range[1]) ? tappedSubIndex : 0;
        return range[0] + sub;
    }

    /**
     * #2 Windowed loading: slice a ±{@link #WINDOW_RADIUS} window out of
     * {@code result} around its startIndex, instead of handing the whole
     * (possibly huge) list to the caller. Small enough to always pass
     * safely through an Intent extra (Binder ~1MB limit) regardless of
     * how long the chat's media history is — the full list stays cached
     * (see {@link #resolve}) for {@link #slice} to pull further windows
     * from as the user swipes toward either edge.
     */
    @WorkerThread
    public static Window window(Result result) {
        List<Map<String, Object>> all = result.items;
        int total = all.size();
        if (result.startIndex < 0 || total == 0) {
            return new Window(all, result.startIndex, 0, total);
        }
        int from = Math.max(0, result.startIndex - WINDOW_RADIUS);
        int to = Math.min(total, result.startIndex + WINDOW_RADIUS + 1);
        List<Map<String, Object>> windowed = (from == 0 && to == total)
                ? all : new ArrayList<>(all.subList(from, to));
        return new Window(windowed, result.startIndex - from, from, total);
    }

    /**
     * Pulls a [from,to) slice straight out of the cached full list for
     * {@code chatId} — used by MediaViewerActivity to grow the pager's
     * window as the user swipes near either edge. Returns null if the
     * chat's gallery isn't (or is no longer) cached — e.g. evicted by the
     * 4-chat LruCache, or the viewer was opened via the no-chatId fallback
     * path — in which case the caller simply stops expanding (the pager
     * just stops at its current window, same as the old fixed cap did).
     */
    @Nullable
    public static List<Map<String, Object>> slice(@Nullable String chatId, int from, int to) {
        if (chatId == null) return null;
        CacheEntry cached = GALLERY_CACHE.get(chatId);
        if (cached == null) return null;
        int size = cached.items.size();
        from = Math.max(0, from);
        to = Math.min(size, to);
        if (from >= to) return new ArrayList<>();
        return new ArrayList<>(cached.items.subList(from, to));
    }

    public static int cachedTotalCount(@Nullable String chatId) {
        if (chatId == null) return 0;
        CacheEntry cached = GALLERY_CACHE.get(chatId);
        return cached == null ? 0 : cached.items.size();
    }
}
