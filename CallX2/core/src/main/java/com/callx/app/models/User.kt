package com.callx.app.models

data class User(
    var uid: String? = null,
    var email: String? = null,
    var name: String? = null,
    var emoji: String? = null,
    var callxId: String? = null,
    var about: String? = null,
    var photoUrl: String? = null,
    var thumbUrl: String? = null,
    var fcmToken: String? = null,
    var lastSeen: Long? = null,
    var lastMessage: String? = null,
    var lastMessageAt: Long? = null,
    var unread: Long? = null
)
