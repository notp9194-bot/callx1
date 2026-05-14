package com.callx.app.models;
import java.util.HashMap;
import java.util.Map;
public class Group {
    public String id;
    public String name;
    public String description;
    public String iconUrl;
    public String createdBy;
    public String adminUid;          // primary admin (creator by default)
    public Long createdAt;
    public String lastMessage;
    public String lastSenderName;    // last message ka sender display name
    public Long lastMessageAt;
    public Map<String, Boolean> members  = new HashMap<>();
    public Map<String, Boolean> admins   = new HashMap<>();
    // mutedBy/{uid} = true => us user ke liye group muted hai (silent push)
    public Map<String, Boolean> mutedBy  = new HashMap<>();
    // unread/{uid} = count for that user (server-incremented)
    public Map<String, Long>    unread   = new HashMap<>();
    public Group() {}
}
