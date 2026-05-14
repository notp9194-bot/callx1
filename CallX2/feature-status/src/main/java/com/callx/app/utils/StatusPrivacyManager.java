package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.callx.app.models.StatusItem;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages status privacy settings.
 * Privacy modes:
 *   "everyone"  — all CallX users can see your status
 *   "contacts"  — only contacts can see (default)
 *   "except"    — contacts except the specified UIDs
 *   "only"      — only the specified UIDs
 *
 * Also provides a client-side filter so that when loading a contact's
 * statuses we skip items the owner has blocked us from viewing.
 */
public final class StatusPrivacyManager {

    private static final String PREFS_NAME      = "status_privacy_prefs";
    private static final String KEY_PRIVACY_MODE = "privacy_mode";
    private static final String KEY_EXCEPT_LIST  = "privacy_except_list";
    private static final String KEY_ONLY_LIST    = "privacy_only_list";

    public static final String PRIVACY_EVERYONE = "everyone";
    public static final String PRIVACY_CONTACTS = "contacts";
    public static final String PRIVACY_EXCEPT   = "except";
    public static final String PRIVACY_ONLY     = "only";

    private StatusPrivacyManager() {}

    // ── Read / write local prefs ──────────────────────────────────────────

    public static String getPrivacyMode(Context ctx) {
        return prefs(ctx).getString(KEY_PRIVACY_MODE, PRIVACY_CONTACTS);
    }

    public static void setPrivacyMode(Context ctx, String mode) {
        prefs(ctx).edit().putString(KEY_PRIVACY_MODE, mode).apply();
    }

    public static Set<String> getExceptList(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_EXCEPT_LIST,
                Collections.emptySet()));
    }

    public static void setExceptList(Context ctx, Set<String> uids) {
        prefs(ctx).edit().putStringSet(KEY_EXCEPT_LIST, uids).apply();
    }

    public static Set<String> getOnlyList(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY_ONLY_LIST,
                Collections.emptySet()));
    }

    public static void setOnlyList(Context ctx, Set<String> uids) {
        prefs(ctx).edit().putStringSet(KEY_ONLY_LIST, uids).apply();
    }

    // ── Visibility check ─────────────────────────────────────────────────

    /**
     * Should the current user (viewerUid) be able to see this status item?
     * Uses the privacy fields embedded in the StatusItem itself.
     *
     * @param item      status to check
     * @param viewerUid UID of the person trying to view the status
     * @param isContact true if viewerUid is in the owner's contacts
     */
    public static boolean isVisibleTo(StatusItem item, String viewerUid,
                                      boolean isContact) {
        if (item == null || item.deleted) return false;
        if (item.isExpired()) return false;
        // Owner always sees their own statuses
        if (viewerUid != null && viewerUid.equals(item.ownerUid)) return true;

        String mode = item.privacy == null ? PRIVACY_CONTACTS : item.privacy;
        switch (mode) {
            case PRIVACY_EVERYONE:
                return true;
            case PRIVACY_CONTACTS:
                return isContact;
            case PRIVACY_EXCEPT: {
                if (!isContact) return false;
                List<String> excluded = item.privacyList;
                return excluded == null || !excluded.contains(viewerUid);
            }
            case PRIVACY_ONLY: {
                List<String> allowed = item.privacyList;
                return allowed != null && allowed.contains(viewerUid);
            }
            default:
                return isContact;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
