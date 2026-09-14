package com.callx.app.db;

/**
 * Telegram-style chat media gallery — lean projection row.
 *
 * Backs {@link com.callx.app.db.dao.MessageDao#getChatMediaRows(String)}.
 * Deliberately NOT the full MessageEntity/Message model — only the columns
 * needed to build a swipeable gallery list are selected, so this query stays
 * cheap even on a chat with thousands of messages (it rides the existing
 * (chatId, timestamp) index, same one getMessagesPagingSource() already
 * uses — no new index needed).
 */
public class ChatMediaRow {
    public String id;
    public long timestamp;
    public String type;            // "image" | "video" | "multi_media"
    public String mediaUrl;
    public String text;            // legacy fallback when mediaUrl is empty (same pattern used across the app)
    public String thumbnailUrl;
    public String mediaLocalPath;
    public String mediaItemsJson;  // only populated for type == "multi_media"
}
