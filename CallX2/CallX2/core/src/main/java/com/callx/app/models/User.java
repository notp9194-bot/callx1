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
    // Chat-list v22: read receipts (ticks) + media label support.
    // lastMessageType   -> "text"/"image"/"video"/"audio"/"gif"/"sticker"/
    //                      "poll"/"contact"/"location"/"multi_media"/
    //                      "reel_share"/"document"/"file". Drives the
    //                      emoji label shown in the chat list row instead
    //                      of trusting free-text lastMessage for media.
    // lastMessageStatus -> "sent"/"delivered"/"read". Only rendered as
    //                      ticks when lastMessageSenderUid == the viewer's
    //                      own uid (i.e. the viewer sent it).
    // lastMessageSenderUid -> uid of whoever sent the last message.
    // lastMessageId     -> Firebase message key, used to target status
    //                      (delivered/read) updates at the correct row.
    public String lastMessageType;
    public String lastMessageStatus;
    public String lastMessageSenderUid;
    public String lastMessageId;
    // Social links
    public String whatsapp;      // WhatsApp number or link
    public String instagram;     // Instagram handle or URL
    public String youtube;       // YouTube channel URL
    public String otherLink;     // Any other link (website, Twitter, etc.)
    public User() {}
}
