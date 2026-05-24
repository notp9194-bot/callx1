package com.callx.app.models;

import java.util.Map;

public class XDMMessage {
    public String id;
    public String senderUid;
    public String text;
    public String mediaUrl;
    public String mediaType;          // "image" | "video"
    public long   timestamp;
    public boolean seen;
    public long   seenAt;             // timestamp when seen

    // Reply-to message
    public String replyToMsgId;
    public String replyToText;        // snippet for display

    // Emoji reactions {emoji : {uid : true}}
    public Map<String, Map<String, Boolean>> reactions;

    // Forwarded
    public boolean forwarded;
    public String  forwardedFromName;

    /** Helper: get reaction count for a given emoji */
    public int reactionCount(String emoji) {
        if (reactions == null || reactions.get(emoji) == null) return 0;
        return reactions.get(emoji).size();
    }

    /** Helper: check if uid reacted with emoji */
    public boolean hasReacted(String uid, String emoji) {
        if (reactions == null) return false;
        Map<String, Boolean> users = reactions.get(emoji);
        return users != null && Boolean.TRUE.equals(users.get(uid));
    }
}
