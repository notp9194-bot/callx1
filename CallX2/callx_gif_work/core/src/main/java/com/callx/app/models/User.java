package com.callx.app.models;
public class User {
    public String uid;
    public String email;
    public String name;
    public String emoji;
    public String callxId;
    public String about;
    public String bio;           // Reel profile bio (short tagline)
    public String phone;         // Phone / WhatsApp number
    public String photoUrl;
    public String thumbUrl;      // 100×100 WebP — chat list / notification avatars
    public String fcmToken;
    public Long lastSeen;
    // WhatsApp style chat list metadata
    public String lastMessage;
    public Long lastMessageAt;
    public Long unread;
    // Social links
    public String whatsapp;      // WhatsApp number or link
    public String instagram;     // Instagram handle or URL
    public String youtube;       // YouTube channel URL
    public String otherLink;     // Any other link (website, Twitter, etc.)
    public User() {}
}
