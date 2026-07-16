package com.callx.app.messages;

import java.util.Map;

/**
 * XGroupDMMessage — Message model for X Group DM conversations.
 *
 * Firebase path:
 *   x/dm_group_messages/{groupId}/{messageId}
 *
 * Key differences from XDMMessage:
 *   - senderName / senderThumbUrl stored on every message so the adapter
 *     can show the sender's avatar + name without a separate lookup.
 *   - No 1:1 "seen" field; seen state is tracked per-member at
 *     x/dm_groups/{groupId}/seen/{memberUid} → lastSeenMsgId.
 */
public class XGroupDMMessage {

    public String  id;
    public String  senderUid;
    public String  senderName;
    public String  senderThumbUrl;   // 100×100 avatar thumb — stored at send time

    public String  text;
    public String  mediaUrl;
    public String  mediaType;        // "image" | "video"
    public long    timestamp;

    // Reply-to
    public String  replyToMsgId;
    public String  replyToText;
    public String  replyToSenderName;

    // Emoji reactions {emoji : {uid : true}}
    public Map<String, Map<String, Boolean>> reactions;

    // Forwarded
    public boolean forwarded;
    public String  forwardedFromName;

    // System / meta messages
    public boolean isSystemMessage;  // e.g. "Alice joined", "Bob left"
    public String  systemText;

    /** Number of members that have seen this message (populated at runtime). */
    public transient int seenByCount = 0;

    // ── Helpers ───────────────────────────────────────────────────────────

    public int reactionCount(String emoji) {
        if (reactions == null || reactions.get(emoji) == null) return 0;
        return reactions.get(emoji).size();
    }

    public boolean hasReacted(String uid, String emoji) {
        if (reactions == null) return false;
        Map<String, Boolean> users = reactions.get(emoji);
        return users != null && Boolean.TRUE.equals(users.get(uid));
    }
}
