package com.callx.app.models;

public class User {
    public String uid;
    public String email;
    public String name;
    public String emoji;
    public String callxId;
    public String mobile;
    public String about;
    public String photoUrl;
    public String fcmToken;
    public Long lastSeen;
    // Chat list metadata
    public String lastMessage;
    public Long lastMessageAt;
    public Long unread;
    // Presence
    public Boolean isOnline;
    // Status
    public String statusUrl;
    public Long statusTimestamp;

    public User() {}

    // Convenience getter for unread count
    public int getUnreadCount() {
        return unread == null ? 0 : unread.intValue();
    }
}
