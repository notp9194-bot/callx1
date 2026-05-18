package com.callx.app.chat.reply

import com.callx.app.models.Message

/**
 * ReplyStateManager — Single source of truth for the active reply state.
 */
class ReplyStateManager {

    private var activeReplyMessage: Message? = null

    fun setActive(message: Message) { activeReplyMessage = message }
    fun clear() { activeReplyMessage = null }
    fun getActive(): Message? = activeReplyMessage
    fun hasActive(): Boolean = activeReplyMessage != null

    fun getDisplaySenderName(currentUid: String?): String {
        val m = activeReplyMessage ?: return ""
        return if (currentUid != null && currentUid == m.senderId) "You"
               else m.senderName ?: ""
    }

    fun getReplyPreviewText(): String {
        val m = activeReplyMessage ?: return ""
        if (m.deleted == true) return "🚫  Original message unavailable"
        if (!m.text.isNullOrEmpty()) return m.text!!
        return when (m.type) {
            "image" -> "📷 Photo"
            "video" -> "🎬 Video"
            "audio" -> "🎤 Voice message"
            "file"  -> "📎 ${m.fileName ?: "File"}"
            else    -> "[${m.type ?: "message"}]"
        }
    }

    fun getReplyThumbnailUrl(): String? {
        val m = activeReplyMessage ?: return null
        return when (m.type) {
            "image" -> m.mediaUrl
            "video" -> m.thumbnailUrl
            else    -> null
        }
    }

    fun getReplyType(): String = activeReplyMessage?.type ?: "text"

    fun isValid(): Boolean {
        val m = activeReplyMessage ?: return false
        val id = m.id ?: m.messageId
        return !id.isNullOrEmpty()
    }
}
