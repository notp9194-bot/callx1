package com.callx.app.conversation.info;

import com.callx.app.models.Message;

/**
 * Builds the short one-line preview shown at the top of the Message Info
 * screen (the same "type → label" mapping style used for chat-list last
 * message previews), so the screen can show something meaningful for
 * media messages instead of an empty text field.
 */
public final class MessageInfoPreviewUtil {

    private MessageInfoPreviewUtil() {}

    public static String buildPreview(Message m) {
        if (m == null) return "";
        String type = m.type != null ? m.type : "text";

        switch (type) {
            case "image":
                return "\uD83D\uDCF7 Photo";
            case "video":
                return "\uD83C\uDFA5 Video";
            case "audio":
                return "\uD83C\uDFA4 Voice message";
            case "gif":
                return "GIF";
            case "sticker":
                return "Sticker";
            case "file":
            case "document":
                return "\uD83D\uDCCE " + (m.fileName != null ? m.fileName : "Document");
            case "contact":
                return "\uD83D\uDC64 Contact";
            case "location":
                return "\uD83D\uDCCD Location";
            case "poll":
                return "\uD83D\uDCCA " + (m.text != null && !m.text.isEmpty() ? m.text : "Poll");
            case "multi_media":
                return "\uD83D\uDCF7 Photos";
            case "reel_share":
                return "\uD83C\uDFAC Reel";
            case "reel_seen":
                return "\uD83D\uDC41 Reel seen";
            case "reel_link":
                return "\uD83D\uDD17 Reel link";
            default:
                return m.text != null && !m.text.isEmpty() ? m.text : "Message";
        }
    }
}
