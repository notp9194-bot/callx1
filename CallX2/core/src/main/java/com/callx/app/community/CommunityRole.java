package com.callx.app.community;

/**
 * v30: Community member roles. Kept as plain String constants (not a Java
 * enum) because CommunityMemberEntity.role is stored as raw TEXT in Room
 * and as a raw string under communities/{id}/members/{uid}/role in
 * Firebase — an enum would need a Room TypeConverter for no real benefit.
 */
public final class CommunityRole {
    public static final String OWNER  = "OWNER";
    public static final String ADMIN  = "ADMIN";
    public static final String MEMBER = "MEMBER";

    private CommunityRole() {}

    public static boolean isAdminOrOwner(String role) {
        return OWNER.equals(role) || ADMIN.equals(role);
    }
}
