package com.callx.app.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room DB entity for chat metadata cache.
 * Covers both 1-on-1 and group chats.
 *
 * Plain class (not data class) with @JvmField on every property so that
 * Java classes (SyncWorker) can access fields directly without Kotlin getters.
 */
@Entity(
    tableName = "chats",
    indices = [
        Index(value = ["lastMessageAt"]),
        Index(value = ["type"])
    ]
)
class ChatEntity {

    @JvmField @PrimaryKey var chatId: String = ""
    /** "private" or "group" */
    @JvmField var type: String? = null
    @JvmField var partnerUid: String? = null
    @JvmField var partnerName: String? = null
    @JvmField var partnerPhoto: String? = null
    @JvmField var partnerThumb: String? = null
    @JvmField var lastMessage: String? = null
    @JvmField var lastMessageAt: Long? = null
    @JvmField var unread: Long? = null
    @JvmField var muted: Boolean? = null
    @JvmField var pinned: Boolean? = null
    @JvmField var syncedAt: Long = System.currentTimeMillis()
    /** Draft message persist */
    @JvmField var draft: String? = null
    /** Offline read receipt queue */
    @JvmField var pendingMarkRead: Boolean? = null
}
