package com.callx.app.models;
import java.util.Map;
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

    // Feature 1: Read Receipts (sent | delivered | read)
    public String status;

    // Feature 2: Reply / Quote
    public String replyToId;
    public String replyToText;
    public String replyToSenderName;

    // Feature 3: Reactions  { uid -> emoji }
    public Map<String, String> reactions;

    // Feature 4: Edited flag
    public Boolean edited;

    // Feature 5: Delete for everyone
    public Boolean deleted;

    // Feature 6: Forwarded from name
    public String forwardedFrom;

    // Feature 7: Starred
    public Boolean starred;

    // Feature 8: Pinned (stored here for convenience, also in chat meta)
    public Boolean pinned;

    public Message() {}
}
