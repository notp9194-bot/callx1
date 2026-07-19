package com.callx.app.models;

import com.google.firebase.database.IgnoreExtraProperties;
import java.util.Map;

/**
 * Channel — shared core model used by both feature-status (Updates tab)
 * and any future feature that needs to display/query channels.
 *
 * Firebase node: channels/{channelId}/
 *
 * WhatsApp-level v2 — adds pinned post, invite code, growth metrics,
 * owner profile cache, and scheduled-post support.
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
    public boolean isPrivate;         // false = public (discoverable), true = invite-only
    public String inviteLink;         // deep-link invite URL for private channels
    public String inviteCode;         // short code used in the invite link
    public long   totalPosts;         // total posts ever published
    public long   totalViews;         // aggregate view count across all posts
    public long   weeklyGrowth;       // follower delta in the last 7 days (for trending)

    // Owner profile cache (denormalized for fast display)
    public String ownerName;
    public String ownerIconUrl;

    /** uid → role: "admin" | "owner". Owner is always included. */
    public Map<String, String> adminRoles;

    // ── Pinned post ───────────────────────────────────────────────────────
    public String pinnedPostId;       // ID of the post currently pinned to the top (null = none)

    // ── Last-post cache (denormalized for chat-row preview) ───────────────
    public long   lastPostAt;
    public String lastPostText;
    public String lastPostMediaUrl;
    public String lastPostType;   // "text" | "image" | "video" | "link" | "poll" | "audio" | "document"

    // ── Followers index (reverse mapping stored separately) ───────────────
    // Firebase node: channelFollowers/{channelId}/{uid} = true
    // Not stored in this model; queried separately.

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

    /** Returns true if the given uid is the owner or an admin. */
    public boolean isAdminOrOwner(String uid) {
        if (uid == null) return false;
        if (uid.equals(ownerUid)) return true;
        if (adminRoles == null) return false;
        String role = adminRoles.get(uid);
        return "admin".equals(role) || "owner".equals(role);
    }

    /** Generates an invite link for this channel. */
    public String buildInviteLink() {
        String code = inviteCode != null ? inviteCode : id;
        return "https://callx.app/channel/join/" + code;
    }
}
