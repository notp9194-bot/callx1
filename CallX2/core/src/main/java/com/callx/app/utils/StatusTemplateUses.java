package com.callx.app.utils;

import androidx.annotation.Nullable;

import com.google.firebase.database.ServerValue;

/**
 * v743: "N used" counter of photo-slot templates — statusTemplateUses/{templateOwnerUid}/{statusId}/{userUid} = server time.
 * Matches the v743 rules: one row per (template, user), never by the template's owner, and only while the template is live.
 * Every call is best-effort (a rejected write just means the counter doesn't move).
 */
public final class StatusTemplateUses {

    /** Rules cap templateFromName at 120 chars. */
    public static final int MAX_NAME = 120;
    /** Rules cap templateJson / templateStickersJson at 60000 chars. */
    public static final int MAX_JSON = 60_000;

    private StatusTemplateUses() { }

    private static boolean ne(@Nullable String s) { return s != null && !s.isEmpty(); }

    /** Trims a display name to what the rules accept. */
    public static String capName(@Nullable String name) {
        if (name == null) return "";
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /** Counts {@code me} as a user of the template (originUid, originId). Skips self-use and empty ids. */
    public static void record(@Nullable String originUid, @Nullable String originId) {
        try {
            final String me = FirebaseUtils.getCurrentUid();
            if (!ne(originUid) || !ne(originId) || !ne(me) || me.equals(originUid)) return;
            FirebaseUtils.db().getReference("statusTemplateUses").child(originUid).child(originId).child(me)
                    .setValue(ServerValue.TIMESTAMP);
        } catch (Exception ignored) { /* counter is best-effort */ }
    }

    /** Owner removes the whole counter of a status he deleted. */
    public static void clear(@Nullable String ownerUid, @Nullable String statusId) {
        try {
            final String me = FirebaseUtils.getCurrentUid();
            if (!ne(ownerUid) || !ne(statusId) || !ne(me) || !me.equals(ownerUid)) return;
            FirebaseUtils.db().getReference("statusTemplateUses").child(ownerUid).child(statusId).removeValue();
        } catch (Exception ignored) { }
    }
}
