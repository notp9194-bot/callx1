package com.callx.app.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room DB entity for chat metadata cache.
 * Covers both 1-on-1 and group chats.
 */
@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["lastMessageAt"]),
        Index(value = ["type"])
    ]
)
data class ChatEntity(
    @PrimaryKey var chatId: String = "",
    /** "private" or "group" */
    var type: String? = null,
    var partnerUid: String? = null,
    var partnerName: String? = null,
    var partnerPhoto: String? = null,
    var partnerThumb: String? = null,
    var lastMessage: String? = null,
    var lastMessageAt: Long? = null,
    var unread: Long? = null,
    var muted: Boolean? = null,
    var pinned: Boolean? = null,
    var syncedAt: Long = System.currentTimeMillis(),
    /** Draft message persist */
    var draft: String? = null,
    /** Offline read receipt queue */
    var pendingMarkRead: Boolean? = null
)
