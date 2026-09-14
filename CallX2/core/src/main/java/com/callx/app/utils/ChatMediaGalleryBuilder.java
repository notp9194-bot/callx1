package com.callx.app.utils;

import androidx.annotation.WorkerThread;

import com.callx.app.db.ChatMediaRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram-style chat-wide media gallery — turns
 * {@link com.callx.app.db.dao.MessageDao#getChatMediaRows(String)}'s lean,
 * timestamp-ordered row list into the flat
 * {@code List<Map<String,Object>>} MediaViewerActivity's gallery mode
 * already consumes (same shape MediaItemsJsonUtil round-trips for a single
 * grouped message — this just does it across the WHOLE chat instead of one
 * message at a time), plus the start index of whichever item the user
 * actually tapped.
 *
 * A "multi_media" row (several photos/videos sent together) is expanded
 * in-place into its individual items, exactly like Telegram/WhatsApp
 * treat an album as several consecutive gallery pages rather than a
 * separate sub-gallery — so swiping off the end of one message's album
 * continues straight into the next message's media, and so on for the
 * whole chat.
 *
 * Pure/offline — takes rows already read from Room, does no I/O itself.
 * Run {@link com.callx.app.db.dao.MessageDao#getChatMediaRows(String)} on a
 * background thread and call {@link #build} there too before handing the
 * result back to the UI thread.
 */
public final class ChatMediaGalleryBuilder {

    private ChatMediaGalleryBuilder() {}

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

    @WorkerThread
    public static Result build(List<ChatMediaRow> rows, String tappedMessageId, int tappedSubIndex) {
        List<Map<String, Object>> items = new ArrayList<>();
        int startIndex = -1;
        if (rows == null) return new Result(items, startIndex);

        for (ChatMediaRow row : rows) {
            if (row == null) continue;

            if ("multi_media".equals(row.type) && row.mediaItemsJson != null && !row.mediaItemsJson.isEmpty()) {
                List<Map<String, Object>> group = MediaItemsJsonUtil.mediaItemsFromJson(row.mediaItemsJson);
                if (group.isEmpty()) continue;
                int base = items.size();
                items.addAll(group);
                if (row.id != null && row.id.equals(tappedMessageId)) {
                    int sub = (tappedSubIndex >= 0 && tappedSubIndex < group.size()) ? tappedSubIndex : 0;
                    startIndex = base + sub;
                }
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
                if (row.id != null && row.id.equals(tappedMessageId)) {
                    startIndex = idx;
                }
            }
        }
        return new Result(items, startIndex);
    }

    /**
     * Safety cap for very long chat histories: a gallery list is passed to
     * MediaViewerActivity via an Intent extra (Binder transaction, ~1MB
     * total limit) — an ancient chat with thousands of photos/videos could
     * otherwise risk a TransactionTooLargeException. Windows the list down
     * to at most {@code maxItems}, centered on startIndex, same idea as
     * Telegram's own paged media loading (it never hands the whole
     * chat-history media list to the viewer in one shot either). Swiping
     * to the very edge of this window still just stops there — a small,
     * deliberate trade-off against the added complexity of loading further
     * pages on demand mid-swipe.
     */
    @WorkerThread
    public static Result cap(Result result, int maxItems) {
        if (result.items.size() <= maxItems || result.startIndex < 0) return result;
        int half = maxItems / 2;
        int from = Math.max(0, result.startIndex - half);
        int to = Math.min(result.items.size(), from + maxItems);
        from = Math.max(0, to - maxItems); // re-clamp if we hit the end
        return new Result(new ArrayList<>(result.items.subList(from, to)), result.startIndex - from);
    }
}
