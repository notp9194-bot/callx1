package com.callx.app.utils;

/**
 * Chat-list "last message" preview text.
 *
 * BUG FIX: previously the chat list just showed whatever raw string was
 * stored in User.lastMessage. For media messages that raw string was
 * whatever the SENDER'S client happened to compute at send time (which
 * some code paths get right — e.g. ChatMediaController already writes
 * "📷 Photo" / "🎤 Voice message" — but others don't, so a photoUrl-only
 * message could show up in the list with no label at all, or plain text).
 *
 * Fix: the chat list now derives the label AUTHORITATIVELY from the
 * message's stored `lastMessageType` field (synced from Firebase /
 * Room — see User.lastMessageType / ChatEntity.lastMessageType) instead
 * of trusting free-text. Text messages (or unknown/null type) still fall
 * back to the raw lastMessage string, so normal chat previews are
 * unaffected.
 */
public final class ChatListPreviewUtil {

    private ChatListPreviewUtil() {}

    /**
     * @param type        message type ("text"/"image"/"video"/... or null)
     * @param rawLastMsg  the raw lastMessage string as stored (used as-is
     *                    for text messages, and as a fallback if type is
     *                    null/unrecognized)
     * @param emptyText   text to show when there's no message at all yet
     */
    public static String buildPreview(String type, String rawLastMsg, String emptyText) {
        String label = labelForType(type);
        if (label != null) return label;
        if (rawLastMsg != null && !rawLastMsg.isEmpty()) return rawLastMsg;
        return emptyText;
    }

    /** @return the emoji label for a known media type, or null for
     *  "text"/null/unrecognized types (caller should fall back to raw text). */
    public static String labelForType(String type) {
        if (type == null) return null;
        switch (type) {
            case "image":       return "📷 Photo";
            case "multi_media": return "📷 Photos";
            case "video":       return "🎬 Video";
            case "audio":       return "🎤 Voice message";
            case "gif":         return "🎞️ GIF";
            case "sticker":     return "🏷️ Sticker";
            case "poll":        return "📊 Poll";
            case "contact":     return "👤 Contact";
            case "location":    return "📍 Location";
            case "reel_share":  return "📹 Reel";
            case "document":
            case "file":        return "📄 Document";
            case "text":
            default:            return null;
        }
    }
}
