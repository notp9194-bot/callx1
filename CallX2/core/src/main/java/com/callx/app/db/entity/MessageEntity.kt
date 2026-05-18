package com.callx.app.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room DB entity for cached messages.
 * Indexed on chatId + timestamp for fast query performance.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["chatId", "timestamp"]),
        Index(value = ["chatId", "starred"]),
        Index(value = ["syncedAt"])
    ]
)
data class MessageEntity(
    @PrimaryKey var id: String = "",
    var chatId: String? = null,
    var senderId: String? = null,
    var senderName: String? = null,
    var senderPhoto: String? = null,
    var text: String? = null,
    var type: String? = null,
    var mediaUrl: String? = null,
    var thumbnailUrl: String? = null,
    var fileName: String? = null,
    var fileSize: Long? = null,
    var duration: Long? = null,
    var timestamp: Long? = null,
    var status: String? = null,
    var replyToId: String? = null,
    var replyToText: String? = null,
    var replyToSenderName: String? = null,
    var replyToType: String? = null,
    var replyToMediaUrl: String? = null,
    var edited: Boolean? = null,
    var editedAt: Long? = null,
    var deleted: Boolean? = null,
    var forwardedFrom: String? = null,
    var starred: Boolean? = null,
    var pinned: Boolean? = null,
    var isGroup: Boolean? = null,
    var syncedAt: Long = 0L,
    var reelId: String? = null,
    var reelThumbUrl: String? = null,
    /** Offline media upload queue — local URI stored for retry */
    var mediaLocalPath: String? = null,
    /** "image" | "video" | "raw" | "auto" */
    var mediaResourceType: String? = null
)
