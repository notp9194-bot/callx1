package com.callx.app.models;
public class Message {
    public String id;
    public String senderId;
    public String senderName;
    public String text;
    public String type;          // text | image | video | audio | file
    public String mediaUrl;
    public String thumbnailUrl;
    public String fileName;
    public Long fileSize;
    public Long duration;        // ms (audio/video)
    public Long timestamp;
    // Backward compat
    public String imageUrl;
    public Message() {}
}
