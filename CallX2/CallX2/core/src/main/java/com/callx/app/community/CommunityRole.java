package com.callx.app.community;

import androidx.annotation.Nullable;

/**
 * Community member role constants.
 * Stored as a String in CommunityMemberEntity.role and in Firebase.
 *
 * OWNER  — full control: disable community, manage invite links
 * ADMIN  — moderation: approve/reject joins, mute/ban members, delete posts, manage events
 * MEMBER — default
 */
public final class CommunityRole {

    private CommunityRole() {}

    public static final String OWNER  = "OWNER";
    public static final String ADMIN  = "ADMIN";
    public static final String MEMBER = "MEMBER";

    /**
     * Returns true if the given role can perform admin-level actions.
     */
    public static boolean isAdminOrOwner(@Nullable String role) {
        return OWNER.equals(role) || ADMIN.equals(role);
    }
}
