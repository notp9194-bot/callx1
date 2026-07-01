package com.callx.app.broadcast;

/**
 * BroadcastMessage — Firebase data model for messages sent through a broadcast list.
 *
 * Firebase path: broadcast_messages/{listId}/{messageId}
 */
public class BroadcastMessage {

    public String id;
    public String text;
    public String type;        // text | image | video | audio | file
    public String mediaUrl;
    public String fileName;
    public String caption;
    public String senderId;
    public long   timestamp;
    public int    deliveredCount;   // how many recipients received it
    public int    totalRecipients;

    public BroadcastMessage() {}

    public BroadcastMessage(String id, String text, String type,
                             String mediaUrl, String fileName, String caption,
                             String senderId, long timestamp,
                             int totalRecipients) {
        this.id               = id;
        this.text             = text;
        this.type             = type;
        this.mediaUrl         = mediaUrl;
        this.fileName         = fileName;
        this.caption          = caption;
        this.senderId         = senderId;
        this.timestamp        = timestamp;
        this.deliveredCount   = 0;
        this.totalRecipients  = totalRecipients;
    }
}
