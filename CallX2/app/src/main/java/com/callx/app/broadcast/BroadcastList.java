package com.callx.app.broadcast;

import java.util.HashMap;
import java.util.Map;

/**
 * BroadcastList — Firebase data model.
 *
 * Firebase path: broadcast_lists/{ownerUid}/{listId}
 */
public class BroadcastList {

    public String              id;
    public String              name;
    public long                createdAt;
    public long                updatedAt;
    public Map<String, Boolean> recipients;   // uid → true
    public String              lastMessage;
    public String              lastMessageType;
    public long                lastMessageTime;
    public int                 sentCount;      // total messages sent through this list

    public BroadcastList() {}

    public BroadcastList(String id, String name, long createdAt) {
        this.id          = id;
        this.name        = name;
        this.createdAt   = createdAt;
        this.updatedAt   = createdAt;
        this.recipients  = new HashMap<>();
        this.lastMessage     = "";
        this.lastMessageType = "text";
        this.lastMessageTime = 0;
        this.sentCount       = 0;
    }

    public int recipientCount() {
        return recipients == null ? 0 : recipients.size();
    }
}
