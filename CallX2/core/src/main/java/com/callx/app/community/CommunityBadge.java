package com.callx.app.community;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * v31: Member badge/level constants + display helpers.
 *
 * Stored as a plain String in CommunityMemberEntity.badge.
 * Firebase path: communities/{communityId}/members/{uid}/badge -> String
 *
 * Badges are assigned manually by admins/owners; future versions can add
 * auto-levelling logic based on post/comment counts.
 */
public final class CommunityBadge {

    private CommunityBadge() {}

    public static final String NONE            = "none";
    public static final String EARLY_MEMBER    = "early_member";
    public static final String ACTIVE          = "active";
    public static final String TOP_CONTRIBUTOR = "top_contributor";
    public static final String VERIFIED        = "verified";
    public static final String MODERATOR       = "moderator";

    /**
     * Returns true if the badge is absent / unset.
     */
    public static boolean isNone(@Nullable String badge) {
        return badge == null || badge.isEmpty() || NONE.equals(badge);
    }

    /**
     * Emoji icon shown next to member name in the member list.
     */
    @NonNull
    public static String getEmojiIcon(@Nullable String badge) {
        if (badge == null) return "";
        switch (badge) {
            case EARLY_MEMBER:    return "🌱";
            case ACTIVE:          return "⚡";
            case TOP_CONTRIBUTOR: return "🏆";
            case VERIFIED:        return "✅";
            case MODERATOR:       return "🛡️";
            default:              return "";
        }
    }

    /**
     * Human-readable label for a badge, e.g. displayed in member profile or chip.
     */
    @NonNull
    public static String getDisplayName(@Nullable String badge) {
        if (badge == null) return "";
        switch (badge) {
            case EARLY_MEMBER:    return "Early Member";
            case ACTIVE:          return "Active";
            case TOP_CONTRIBUTOR: return "Top Contributor";
            case VERIFIED:        return "Verified";
            case MODERATOR:       return "Moderator";
            default:              return "";
        }
    }

    /**
     * Color hex code used to tint the badge chip text.
     */
    @NonNull
    public static String getBadgeColor(@Nullable String badge) {
        if (badge == null) return "#757575";
        switch (badge) {
            case EARLY_MEMBER:    return "#4CAF50";
            case ACTIVE:          return "#FF9800";
            case TOP_CONTRIBUTOR: return "#F44336";
            case VERIFIED:        return "#2196F3";
            case MODERATOR:       return "#9C27B0";
            default:              return "#757575";
        }
    }
}
