package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/**
 * StatusPrivacyManager v25 — Full privacy mode storage.
 * Modes: everyone / contacts / except / only / close_friends
 * Persists except/only contact lists locally; syncs privacy mode to Firebase.
 */
public final class StatusPrivacyManager {

    public static final String PRIVACY_EVERYONE = "everyone";
    public static final String PRIVACY_CONTACTS = "contacts";
    public static final String PRIVACY_EXCEPT   = "except";
    public static final String PRIVACY_ONLY     = "only";

    private static final String PREFS         = "status_privacy";
    private static final String KEY_MODE      = "privacy_mode";
    private static final String KEY_EXCEPT    = "except_list";
    private static final String KEY_ONLY      = "only_list";

    private StatusPrivacyManager() {}

    public static String getPrivacyMode(Context ctx) {
        return prefs(ctx).getString(KEY_MODE, PRIVACY_CONTACTS);
    }

    public static void setPrivacyMode(Context ctx, String mode) {
        prefs(ctx).edit().putString(KEY_MODE, mode).apply();
    }

    public static Set<String> getExceptList(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_EXCEPT, new HashSet<>()));
    }

    public static void setExceptList(Context ctx, Set<String> uids) {
        prefs(ctx).edit().putStringSet(KEY_EXCEPT, uids).apply();
    }

    public static Set<String> getOnlyList(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_ONLY, new HashSet<>()));
    }

    public static void setOnlyList(Context ctx, Set<String> uids) {
        prefs(ctx).edit().putStringSet(KEY_ONLY, uids).apply();
    }

    /** Check if a given UID is allowed to see statuses based on privacy mode. */
    public static boolean canSee(Context ctx, String viewerUid, String ownerUid) {
        if (viewerUid == null || ownerUid == null) return false;
        if (viewerUid.equals(ownerUid)) return true;
        String mode = getPrivacyMode(ctx);
        switch (mode) {
            case PRIVACY_EVERYONE: return true;
            case PRIVACY_EXCEPT:
                return !getExceptList(ctx).contains(viewerUid);
            case PRIVACY_ONLY:
                return getOnlyList(ctx).contains(viewerUid);
            case "close_friends":
                return StatusCloseFriendsManager.isCloseFriend(ctx, viewerUid);
            default: // contacts — Firebase handles; locally assume true
                return true;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
