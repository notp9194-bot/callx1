package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;

/**
 * Channel — shared core model used by both feature-status (Updates tab)
 * and any future feature that needs to display/query channels.
 *
 * Firebase node: channels/{channelId}/
 */
@IgnoreExtraProperties
public class Channel {

    public String id;
    public String name;
    public String description;
    public String iconUrl;
    public long   followers;
    public boolean verified;
    public String category;
    public String ownerUid;
    public long   createdAt;

    // ── Last-post cache (denormalized for chat-row preview) ───────────────
    public long   lastPostAt;
    public String lastPostText;
    public String lastPostMediaUrl;
    public String lastPostType;   // "text" | "image" | "video" | "link"

    public Channel() {}

    /** Human-readable follower count: 1.2M, 45K, 987 */
    public String getFormattedFollowers() {
        if (followers >= 1_000_000_000L)
            return (followers / 1_000_000_000L) + "B";
        if (followers >= 1_000_000L)
            return String.format("%.1fM", followers / 1_000_000.0);
        if (followers >= 1_000L)
            return String.format("%.1fK", followers / 1_000.0);
        return String.valueOf(followers);
    }
}
