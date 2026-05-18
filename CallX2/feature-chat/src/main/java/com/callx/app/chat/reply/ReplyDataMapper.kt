package com.callx.app.chat.reply

import com.callx.app.models.Message

/**
 * ReplyDataMapper — Populates reply fields on outgoing Messages.
 */
object ReplyDataMapper {

    fun applyReplyFields(outgoing: Message, replySource: Message, currentUid: String?) {
        val replyId = replySource.id ?: replySource.messageId
        outgoing.replyToId = replyId

        outgoing.replyToSenderName = if (currentUid != null && currentUid == replySource.senderId)
            "You" else replySource.senderName ?: ""

        outgoing.replyToText = when {
            replySource.deleted == true          -> "🚫  This message was deleted"
            !replySource.text.isNullOrEmpty()    -> replySource.text
            else                                 -> buildTypePreview(replySource)
        }

        outgoing.replyToType     = replySource.type ?: "text"
        outgoing.replyToMediaUrl = resolveMediaUrl(replySource)
    }

    fun findPositionById(messages: List<Message>, id: String?): Int {
        if (id.isNullOrEmpty()) return -1
        return messages.indexOfFirst { id == it.id || id == it.messageId }
    }

    fun sanitizeIncoming(m: Message) {
        if (m.replyToId?.isEmpty() == true) m.replyToId = null
        if (m.replyToId != null && m.replyToText == null) m.replyToText = "[Original message]"
    }

    private fun buildTypePreview(m: Message): String = when (m.type) {
        "image" -> "📷 Photo"
        "video" -> "🎬 Video"
        "audio" -> "🎤 Voice message"
        "file"  -> "📎 ${m.fileName ?: "File"}"
        else    -> "[${m.type ?: "message"}]"
    }

    private fun resolveMediaUrl(m: Message): String? = when (m.type) {
        "image" -> m.mediaUrl
        "video" -> m.thumbnailUrl ?: m.mediaUrl
        else    -> null
    }
}
