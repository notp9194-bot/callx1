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

    // Auto-download gate for the swipe gallery (MediaViewerActivity): the
    // viewer must know WHO sent each item (to tell "mine, always loadable"
    // apart from "received, only loadable if the user already downloaded it
    // from the chat bubble") and, for a Media-E2E image, the key envelope
    // needed if the user taps "Download" on a not-yet-downloaded page.
    public String senderId;
    public String mediaKeyEnc;
    public Long   fileSize;        // nullable — lets the un-downloaded page show "1.2 MB" with no HEAD request
}
