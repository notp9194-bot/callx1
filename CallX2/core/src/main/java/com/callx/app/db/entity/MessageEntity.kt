package com.callx.app.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room DB entity for cached messages.
 * Indexed on chatId + timestamp for fast query performance.
 *
 * Plain class (not data class) with @JvmField on every property so that
 * Java classes (SyncWorker, NotificationReplyWorker) can access fields
 * directly — e.g. entity.id = "…" — without going through Kotlin getters.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["chatId", "timestamp"]),
        Index(value = ["chatId", "starred"]),
        Index(value = ["syncedAt"])
    ]
)
class MessageEntity {

    @JvmField @PrimaryKey var id: String = ""
    @JvmField var chatId: String? = null
    @JvmField var senderId: String? = null
    @JvmField var senderName: String? = null
    @JvmField var senderPhoto: String? = null
    @JvmField var text: String? = null
    @JvmField var type: String? = null
    @JvmField var mediaUrl: String? = null
    @JvmField var thumbnailUrl: String? = null
    @JvmField var fileName: String? = null
    @JvmField var fileSize: Long? = null
    @JvmField var duration: Long? = null
    @JvmField var timestamp: Long? = null
    @JvmField var status: String? = null
    @JvmField var replyToId: String? = null
    @JvmField var replyToText: String? = null
    @JvmField var replyToSenderName: String? = null
    @JvmField var replyToType: String? = null
    @JvmField var replyToMediaUrl: String? = null
    @JvmField var edited: Boolean? = null
    @JvmField var editedAt: Long? = null
    @JvmField var deleted: Boolean? = null
    @JvmField var forwardedFrom: String? = null
    @JvmField var starred: Boolean? = null
    @JvmField var pinned: Boolean? = null
    @JvmField var isGroup: Boolean? = null
    @JvmField var syncedAt: Long = 0L
    @JvmField var reelId: String? = null
    @JvmField var reelThumbUrl: String? = null
    /** Offline media upload queue — local URI stored for retry */
    @JvmField var mediaLocalPath: String? = null
    /** "image" | "video" | "raw" | "auto" */
    @JvmField var mediaResourceType: String? = null
}
