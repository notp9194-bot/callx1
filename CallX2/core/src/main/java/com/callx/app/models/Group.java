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
    // Group-list v24: read receipts (ticks) + media label support — group
    // analogue of the same fields on User (1:1 chat list). lastMessageStatus
    // here reflects the AGGREGATE status from GroupMessageStatusSync
    // (delivered = every other member has it, read = every other member has
    // seen it), matching WhatsApp's group-tick semantics. Only rendered as
    // ticks when lastMessageSenderUid == the viewer's own uid.
    public String lastMessageType;
    public String lastMessageStatus;
    public String lastMessageSenderUid;
    public String lastMessageId;
    public Map<String, Boolean> members  = new HashMap<>();
    public Map<String, Boolean> admins   = new HashMap<>();
    // mutedBy/{uid} = true => us user ke liye group muted hai (silent push)
    public Map<String, Boolean> mutedBy  = new HashMap<>();
    // unread/{uid} = count for that user (server-incremented)
    public Map<String, Long>    unread   = new HashMap<>();
    public Group() {}

    // ── Group Topics (Telegram-style threads) ────────────────────────────
    // topicsEnabled = admin-toggled; hides Topics UI when false
    public boolean topicsEnabled;

    // ── Anonymous Posting ────────────────────────────────────────────────
    // anonymousPostingEnabled = true means non-admin members can tick
    // "Post anonymously" before sending.
    // Stored at: groups/{groupId}/groupSettings/anonymousPostingEnabled

    // ── Slow Mode ────────────────────────────────────────────────────────
    // slowModeSecs stored at: groups/{groupId}/groupSettings/slowModeSecs
    // Enforced client-side in GroupChatActivity.
}

