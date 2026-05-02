package com.callx.app.models;
public class User {
    public String uid;
    public String email;
    public String name;
    public String emoji;
    public String callxId;
    public String about;
    public String photoUrl;
    public String fcmToken;
    public Long lastSeen;
    // WhatsApp style chat list metadata
    public String lastMessage;
    public Long lastMessageAt;
    public Long unread;
    public User() {}
}
