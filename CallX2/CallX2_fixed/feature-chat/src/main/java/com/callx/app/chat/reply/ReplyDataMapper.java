package com.callx.app.chat.reply;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.Message;

import java.util.List;

/**
 * ReplyDataMapper — Populates reply fields on outgoing Messages.
 *
 * Responsibilities:
 *   • Map replyTo fields from the source Message to the outgoing Message
 *   • Handle temp-ID mapping for unsynced messages
 *   • Handle deleted/unavailable original messages gracefully
 *   • Resolve replyToId from paginated list (find by ID after pagination)
 */
public class ReplyDataMapper {

    private ReplyDataMapper() {}

    /**
     * Populates all reply fields on the outgoing message from the reply source.
     *
     * @param outgoing     The message being sent (will have reply fields set)
     * @param replySource  The message being replied to
     * @param currentUid   UID of the sender (for "You" label logic)
     */
    public static void applyReplyFields(
            @NonNull Message outgoing,
            @NonNull Message replySource,
            @Nullable String currentUid) {

        // Resolve ID: prefer id, fallback to messageId (legacy)
        String replyId = resolveId(replySource);
        outgoing.replyToId = replyId;

        // Sender name
        if (currentUid != null && currentUid.equals(replySource.senderId)) {
            outgoing.replyToSenderName = "You";
        } else {
            outgoing.replyToSenderName = replySource.senderName != null
                    ? replySource.senderName : "";
        }

        // Text preview
        if (Boolean.TRUE.equals(replySource.deleted)) {
            outgoing.replyToText = "\uD83D\uDEAB  This message was deleted";
        } else if (replySource.text != null && !replySource.text.isEmpty()) {
            outgoing.replyToText = replySource.text;
        } else {
            outgoing.replyToText = buildTypePreview(replySource);
        }

        // Type and media URL
        outgoing.replyToType     = replySource.type != null ? replySource.type : "text";
        outgoing.replyToMediaUrl = resolveMediaUrl(replySource);
    }

    /**
     * Finds a message by ID in a list (used for pagination scroll).
     * Returns position, or -1 if not found.
     */
    public static int findPositionById(@NonNull List<Message> messages, @Nullable String id) {
        if (id == null || id.isEmpty()) return -1;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (id.equals(m.id) || id.equals(m.messageId)) return i;
        }
        return -1;
    }

    /**
     * Validates reply fields on a received message (null-safety + ID mismatch fallback).
     * Returns a corrected/validated copy or the same object if valid.
     */
    public static void sanitizeIncoming(@NonNull Message m) {
        // Null-safe replyToId
        if (m.replyToId != null && m.replyToId.isEmpty()) m.replyToId = null;
        // If replyToText is null but replyToId exists, set a fallback
        if (m.replyToId != null && m.replyToText == null) {
            m.replyToText = "[Original message]";
        }
        // Type default
        if (m.replyToType == null && m.replyToId != null) {
            m.replyToType = "text";
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private static String resolveId(Message m) {
        if (m.id != null && !m.id.isEmpty()) return m.id;
        if (m.messageId != null && !m.messageId.isEmpty()) return m.messageId;
        return "unknown_" + System.currentTimeMillis();
    }

    private static String buildTypePreview(Message m) {
        if (m.type == null) return "[message]";
        switch (m.type) {
            case "image":  return "\uD83D\uDCF7 Photo";
            case "video":  return "\uD83C\uDFAC Video";
            case "audio":  return "\uD83C\uDFA4 Voice message";
            case "file":   return "\uD83D\uDCCE " + (m.fileName != null ? m.fileName : "File");
            default:       return "[" + m.type + "]";
        }
    }

    private static String resolveMediaUrl(Message m) {
        if (m.type == null) return null;
        switch (m.type) {
            case "image": return m.mediaUrl != null ? m.mediaUrl : m.imageUrl;
            case "video": return m.thumbnailUrl;
            default:      return null;
        }
    }
}
