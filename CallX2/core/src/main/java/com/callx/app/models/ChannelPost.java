package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.Map;

/**
 * ChannelPost — shared core model for a post inside a channel.
 *
 * Firebase node: channelPosts/{channelId}/{postId}/
 */
@IgnoreExtraProperties
public class ChannelPost {

    public String id;
    public String channelId;

    // ── Content ────────────────────────────────────────────────────────────
    public String text;
    public String type;          // "text" | "image" | "video" | "link"
    public String mediaUrl;
    public String thumbnailUrl;
    public String linkUrl;
    public String linkTitle;
    public String linkDescription;

    // ── Metadata ────────────────────────────────────────────────────────────
    public long   timestamp;
    public long   viewCount;
    public long   forwardCount;

    // ── Social ────────────────────────────────────────────────────────────
    /** uid → emoji, e.g. "uid123" → "👍" */
    public Map<String, String> reactions;

    public ChannelPost() {}
}
