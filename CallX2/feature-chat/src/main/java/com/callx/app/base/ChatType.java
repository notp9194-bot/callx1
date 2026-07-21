package com.callx.app.base;

/**
 * Unified chat type enum — replaces the need for separate Activity classes
 * per chat context. Used by BaseChatActivity and ChatRouter.
 */
public enum ChatType {
    PERSONAL,       // 1-on-1 direct message
    GROUP,          // Group conversation
    GROUP_TOPIC,    // Sub-topic inside a group
    BROADCAST,      // Broadcast list (one-to-many)
    CHANNEL,        // Channel (read-mostly, admin posts)
    COMMUNITY_CHAT  // Chat inside a community group
}
