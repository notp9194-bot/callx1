package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * DraftStore — SharedPreferences-backed draft persistence for chat surfaces
 * that don't have their own Room "draft" column (Group chat, Group topic
 * chat). 1:1 chat already persists drafts via ChatDao (chats.draft column);
 * this store covers the rest without needing a schema migration on
 * GroupEntity — safe from being wiped out by REPLACE-strategy group syncs.
 *
 * Usage:
 *   DraftStore.save(context, "group_" + groupId, text);
 *   String draft = DraftStore.get(context, "group_" + groupId);
 *   DraftStore.clear(context, "group_" + groupId);
 */
public final class DraftStore {

    private static final String PREFS_NAME = "callx_drafts";

    private DraftStore() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Draft save karo. Khaali/blank text ko clear() ke barabar treat kiya jata hai. */
    public static void save(Context context, String key, String draft) {
        if (context == null || TextUtils.isEmpty(key)) return;
        if (TextUtils.isEmpty(draft)) {
            clear(context, key);
            return;
        }
        prefs(context).edit().putString(key, draft).apply();
    }

    /** Draft load karo — koi draft na ho to null return hota hai. */
    public static String get(Context context, String key) {
        if (context == null || TextUtils.isEmpty(key)) return null;
        return prefs(context).getString(key, null);
    }

    /** Draft clear karo — message send ho jaane ke baad call karo. */
    public static void clear(Context context, String key) {
        if (context == null || TextUtils.isEmpty(key)) return;
        prefs(context).edit().remove(key).apply();
    }
}
