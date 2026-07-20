package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.HashMap;
import java.util.Map;

/**
 * GroupTopic — Telegram-style topic/thread within a group.
 *
 * Firebase path: groups/{groupId}/topics/{topicId}
 *
 * Features:
 *  - Named topic threads with emoji icon
 *  - Closed topics (read-only for non-admins)
 *  - Pinned topics (shown at top of list)
 *  - Message count per topic
 *  - Last message preview per topic
 *  - Unread counts per user per topic
 */
@IgnoreExtraProperties
public class GroupTopic {

    /** Firebase push key */
    public String id;

    /** Display name, e.g. "General", "Announcements", "Random" */
    public String name;

    /** Optional single emoji shown as icon, e.g. "📢", "💬", "🎮" */
    public String emoji;

    /** Short description shown below name */
    public String description;

    /** UID of the admin who created this topic */
    public String createdBy;

    /** Creation timestamp (ms) */
    public long createdAt;

    /**
     * Closed topics are read-only — only admins can post.
     * Useful for announcement-only channels within a group.
     */
    public boolean closed;

    /** Pinned topics are always shown at the top of the topics list */
    public boolean pinned;

    /** Total message count in this topic (for display) */
    public int messageCount;

    /** Last message text preview */
    public String lastMessage;

    /** Timestamp of the last message */
    public long lastMessageAt;

    /** Sender name of the last message */
    public String lastSenderName;

    /**
     * Unread count per user: uid → count
     * Incremented when a message is posted to this topic,
     * reset to 0 when the user opens the topic.
     */
    public Map<String, Long> unread = new HashMap<>();

    /** Soft delete — topic hidden but messages preserved */
    public boolean deleted;

    public GroupTopic() {}

    public GroupTopic(String id, String name, String emoji, String description,
                      String createdBy, long createdAt) {
        this.id = id;
        this.name = name;
        this.emoji = emoji;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.closed = false;
        this.pinned = false;
        this.messageCount = 0;
    }
}
