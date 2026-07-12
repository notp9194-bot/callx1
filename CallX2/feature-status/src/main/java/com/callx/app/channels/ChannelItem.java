package com.callx.app.channels;

/**
 * A WhatsApp-style "Channel" — a one-way broadcast feed the user can follow.
 * Channels are a self-contained, purely local feature (seeded list + on-device
 * follow/dismiss/read state) — there is no backend for channel content yet.
 */
public class ChannelItem {
    public final String id;
    public final String name;
    public final boolean verified;
    public final String category;
    public final String[] posts;      // sample broadcast posts, newest last

    // Mutable, persisted via ChannelsRepository / SharedPreferences:
    public boolean following;
    public boolean dismissed;
    public int followerCount;
    public int unreadCount;
    public long lastPostAtMillis;

    public ChannelItem(String id, String name, boolean verified, String category,
                        int followerCount, String[] posts) {
        this.id = id;
        this.name = name;
        this.verified = verified;
        this.category = category;
        this.followerCount = followerCount;
        this.posts = posts;
    }

    public String latestPost() {
        return (posts != null && posts.length > 0) ? posts[posts.length - 1] : "";
    }

    public String followerCountLabel() {
        if (followerCount >= 1_000_000) return String.format("%.1fM followers", followerCount / 1_000_000f);
        if (followerCount >= 1_000) return String.format("%.1fK followers", followerCount / 1_000f);
        return followerCount + " followers";
    }
}
