package com.callx.app.models

/**
 * Represents a single chat message (1-on-1 or group).
 * Firebase serialization uses default no-arg constructor + public fields.
 */
data class Message(
    // Core
    var id: String? = null,
    var messageId: String? = null,
    var senderId: String? = null,
    var senderName: String? = null,
    var senderPhoto: String? = null,
    var text: String? = null,
    /** text | image | video | audio | file */
    var type: String? = null,
    var mediaUrl: String? = null,
    var thumbnailUrl: String? = null,
    var fileName: String? = null,
    var fileSize: Long? = null,
    var duration: Long? = null,
    var timestamp: Long? = null,
    /** Legacy field kept for backward compatibility */
    var imageUrl: String? = null,

    // Feature 1: Read Receipts — sent | delivered | read
    var status: String? = null,

    // Feature 2: Reply / Quote
    var replyToId: String? = null,
    var replyToText: String? = null,
    var replyToSenderName: String? = null,
    var replyToType: String? = null,
    var replyToMediaUrl: String? = null,

    // Feature 3: Emoji Reactions — Map of uid → emoji
    var reactions: Map<String, String>? = null,

    // Feature 4: Message Editing
    var edited: Boolean? = null,
    var editedAt: Long? = null,

    // Feature 5: Delete for Everyone
    var deleted: Boolean? = null,

    // Feature 6: Forward
    var forwardedFrom: String? = null,

    // Feature 7: Starred
    var starred: Boolean? = null,

    // Feature 8: Pinned
    var pinned: Boolean? = null,

    // Feature 9: Reel Seen Bubble
    var reelId: String? = null,
    var reelThumbUrl: String? = null,

    // Feature 10: Status Seen Bubble
    var statusOwnerUid: String? = null,
    var statusOwnerName: String? = null,
    var statusThumbUrl: String? = null,

    // Group flag
    var isGroup: Boolean = false
)
