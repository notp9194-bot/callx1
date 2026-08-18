package com.callx.app.chat.reply;

import androidx.annotation.Nullable;
import com.callx.app.models.Message;

/**
 * ReplyStateManager — Single source of truth for the active reply state.
 *
 * Responsibilities:
 *   • Hold the currently-being-replied-to Message
 *   • Validate reply data before activation
 *   • Provide null-safe access to all reply fields
 *   • Support temp-ID mapping for offline scenarios
 */
public class ReplyStateManager {

    private Message activeReplyMessage = null;

    /** Activate a reply to the given message. */
    public void setActive(Message message) {
        this.activeReplyMessage = message;
    }

    /** Clear the active reply. */
    public void clear() {
        this.activeReplyMessage = null;
    }

    /** Returns the active reply message or null. */
    @Nullable
    public Message getActive() {
        return activeReplyMessage;
    }

    /** True if there is an active reply. */
    public boolean hasActive() {
        return activeReplyMessage != null;
    }

    /** Returns reply sender name with "You" fallback for self messages. */
    public String getDisplaySenderName(String currentUid) {
        if (activeReplyMessage == null) return "";
        if (currentUid != null && currentUid.equals(activeReplyMessage.senderId)) return "You";
        return activeReplyMessage.senderName != null ? activeReplyMessage.senderName : "";
    }

    /** Returns a human-readable preview string for the reply bar. */
    public String getReplyPreviewText() {
        if (activeReplyMessage == null) return "";
        if (Boolean.TRUE.equals(activeReplyMessage.deleted))
            return "\uD83D\uDEAB  Original message unavailable";
        if (activeReplyMessage.text != null && !activeReplyMessage.text.isEmpty())
            return activeReplyMessage.text;
        String t = activeReplyMessage.type;
        if (t == null) return "[message]";
        switch (t) {
            case "image":  return "\uD83D\uDCF7 Photo";
            case "video":  return "\uD83C\uDFAC Video";
            case "audio":  return "\uD83C\uDFA4 Voice message";
            case "file":   return "\uD83D\uDCCE " + (activeReplyMessage.fileName != null
                                   ? activeReplyMessage.fileName : "File");
            default:       return "[" + t + "]";
        }
    }

    /** Returns the media URL for thumbnail display in reply bar, or null. */
    @Nullable
    public String getReplyThumbnailUrl() {
        if (activeReplyMessage == null) return null;
        String t = activeReplyMessage.type;
        if ("image".equals(t)) return activeReplyMessage.mediaUrl;
        if ("video".equals(t)) return activeReplyMessage.thumbnailUrl;
        return null;
    }

    /** Returns message type, defaulting to "text". */
    public String getReplyType() {
        if (activeReplyMessage == null) return "text";
        return activeReplyMessage.type != null ? activeReplyMessage.type : "text";
    }

    /**
     * Validates that the reply data is complete enough to send.
     * Returns false if the message has no usable ID.
     */
    public boolean isValid() {
        if (activeReplyMessage == null) return false;
        String id = activeReplyMessage.id != null
                ? activeReplyMessage.id : activeReplyMessage.messageId;
        return id != null && !id.isEmpty();
    }
}
