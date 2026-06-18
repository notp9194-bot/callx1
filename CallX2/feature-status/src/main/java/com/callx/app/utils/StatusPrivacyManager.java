package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/**
 * StatusPrivacyManager — Full privacy control for who can see statuses.
 *
 * Modes:
 *   EVERYONE  — all users (incl. non-contacts)
 *   CONTACTS  — only contacts (default, WhatsApp-style)
 *   EXCEPT    — contacts EXCEPT specific UIDs
 *   ONLY      — only specific UIDs
 *   CLOSE_FRIENDS — only UIDs in the "close friends" list
 *
 * Stored in SharedPreferences so default survives process death.
 * Per-status overrides are stored in StatusItem.privacy + StatusItem.privacyList.
 */
public class StatusPrivacyManager {

    // ── Mode constants ─────────────────────────────────────────────────────
    public static final String MODE_EVERYONE      = "everyone";
    public static final String MODE_CONTACTS      = "contacts";
    public static final String MODE_EXCEPT        = "except";
    public static final String MODE_ONLY          = "only";
    public static final String MODE_CLOSE_FRIENDS = "close_friends";

    private static final String PREFS_NAME       = "callx_status_privacy";
    private static final String KEY_DEFAULT_MODE = "default_mode";
    private static final String KEY_EXCEPT_LIST  = "except_list";
    private static final String KEY_ONLY_LIST    = "only_list";
    private static final String KEY_CLOSE_FRIENDS = "close_friends_list";
    private static final String KEY_MUTED_LIST   = "muted_status_contacts";

    private static volatile StatusPrivacyManager instance;
    private final SharedPreferences prefs;

    private StatusPrivacyManager(Context ctx) {
        prefs = ctx.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static StatusPrivacyManager get(Context ctx) {
        if (instance == null) synchronized (StatusPrivacyManager.class) {
            if (instance == null) instance = new StatusPrivacyManager(ctx);
        }
        return instance;
    }

    // ── Default mode ──────────────────────────────────────────────────────
    public String getDefaultMode() {
        return prefs.getString(KEY_DEFAULT_MODE, MODE_CONTACTS);
    }

    public void setDefaultMode(String mode) {
        prefs.edit().putString(KEY_DEFAULT_MODE, mode).apply();
    }

    // ── Except list (contacts to HIDE from) ──────────────────────────────
    public Set<String> getExceptList() {
        return prefs.getStringSet(KEY_EXCEPT_LIST, new HashSet<>());
    }

    public void setExceptList(Set<String> uids) {
        prefs.edit().putStringSet(KEY_EXCEPT_LIST, uids).apply();
    }

    public void addToExceptList(String uid) {
        Set<String> list = new HashSet<>(getExceptList());
        list.add(uid);
        setExceptList(list);
    }

    public void removeFromExceptList(String uid) {
        Set<String> list = new HashSet<>(getExceptList());
        list.remove(uid);
        setExceptList(list);
    }

    // ── Only list (specific contacts ALLOWED to see) ──────────────────────
    public Set<String> getOnlyList() {
        return prefs.getStringSet(KEY_ONLY_LIST, new HashSet<>());
    }

    public void setOnlyList(Set<String> uids) {
        prefs.edit().putStringSet(KEY_ONLY_LIST, uids).apply();
    }

    public void addToOnlyList(String uid) {
        Set<String> list = new HashSet<>(getOnlyList());
        list.add(uid);
        setOnlyList(list);
    }

    public void removeFromOnlyList(String uid) {
        Set<String> list = new HashSet<>(getOnlyList());
        list.remove(uid);
        setOnlyList(list);
    }

    // ── Close Friends ─────────────────────────────────────────────────────
    public Set<String> getCloseFriends() {
        return prefs.getStringSet(KEY_CLOSE_FRIENDS, new HashSet<>());
    }

    public void setCloseFriends(Set<String> uids) {
        prefs.edit().putStringSet(KEY_CLOSE_FRIENDS, uids).apply();
    }

    public void addCloseFriend(String uid) {
        Set<String> list = new HashSet<>(getCloseFriends());
        list.add(uid);
        setCloseFriends(list);
    }

    public void removeCloseFriend(String uid) {
        Set<String> list = new HashSet<>(getCloseFriends());
        list.remove(uid);
        setCloseFriends(list);
    }

    public boolean isCloseFriend(String uid) {
        return getCloseFriends().contains(uid);
    }

    // ── Mute (hide someone's status from MY feed) ─────────────────────────
    public Set<String> getMutedContacts() {
        return prefs.getStringSet(KEY_MUTED_LIST, new HashSet<>());
    }

    public void muteContact(String uid) {
        Set<String> list = new HashSet<>(getMutedContacts());
        list.add(uid);
        prefs.edit().putStringSet(KEY_MUTED_LIST, list).apply();
    }

    public void unmuteContact(String uid) {
        Set<String> list = new HashSet<>(getMutedContacts());
        list.remove(uid);
        prefs.edit().putStringSet(KEY_MUTED_LIST, list).apply();
    }

    public boolean isMuted(String uid) {
        return getMutedContacts().contains(uid);
    }

    // ── Visibility check ─────────────────────────────────────────────────
    /**
     * Check if a given viewerUid can see a status based on its privacy settings.
     * @param statusPrivacy   The status's privacy mode (from StatusItem.privacy)
     * @param privacyList     The status's UID list (from StatusItem.privacyList)
     * @param ownerContacts   Set of UIDs that the owner has as contacts
     * @param viewerUid       UID of the person trying to view
     */
    public boolean canView(String statusPrivacy,
                           List<String> privacyList,
                           Set<String> ownerContacts,
                           String viewerUid) {
        if (statusPrivacy == null) statusPrivacy = MODE_CONTACTS;
        switch (statusPrivacy) {
            case MODE_EVERYONE:
                return true;
            case MODE_CONTACTS:
                return ownerContacts.contains(viewerUid);
            case MODE_EXCEPT:
                if (privacyList != null && privacyList.contains(viewerUid)) return false;
                return ownerContacts.contains(viewerUid);
            case MODE_ONLY:
                return privacyList != null && privacyList.contains(viewerUid);
            case MODE_CLOSE_FRIENDS:
                return privacyList != null && privacyList.contains(viewerUid);
            default:
                return ownerContacts.contains(viewerUid);
        }
    }

    /** Human-readable label for privacy mode display in UI */
    public static String getModeLabel(String mode) {
        if (mode == null) return "My Contacts";
        switch (mode) {
            case MODE_EVERYONE:      return "Everyone";
            case MODE_CONTACTS:      return "My Contacts";
            case MODE_EXCEPT:        return "My Contacts Except...";
            case MODE_ONLY:          return "Only Share With...";
            case MODE_CLOSE_FRIENDS: return "Close Friends Only";
            default:                 return "My Contacts";
        }
    }

    /** Icon resource name for privacy mode (return R.drawable.* int in activity) */
    public static int getModeIconRes(String mode) {
        // Caller should resolve actual R.drawable values
        // Returns an index for switch in UI layer
        if (mode == null) return 1;
        switch (mode) {
            case MODE_EVERYONE:      return 0;
            case MODE_CONTACTS:      return 1;
            case MODE_EXCEPT:        return 2;
            case MODE_ONLY:          return 3;
            case MODE_CLOSE_FRIENDS: return 4;
            default:                 return 1;
        }
    }
}
