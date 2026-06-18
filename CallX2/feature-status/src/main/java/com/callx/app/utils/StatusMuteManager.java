package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;

/**
 * StatusMuteManager — Hide specific contacts' statuses from your feed.
 *
 * Muting is purely local (SharedPrefs). The muted user has NO idea.
 * Their statuses are simply skipped during StatusFragment list building.
 */
public class StatusMuteManager {

    private static final String PREFS = "callx_status_mute";
    private static final String KEY   = "muted_uids";

    private static volatile StatusMuteManager instance;
    private final SharedPreferences prefs;

    private StatusMuteManager(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static StatusMuteManager get(Context ctx) {
        if (instance == null) synchronized (StatusMuteManager.class) {
            if (instance == null) instance = new StatusMuteManager(ctx);
        }
        return instance;
    }

    public void mute(String uid) {
        Set<String> set = new HashSet<>(getMutedUids());
        set.add(uid);
        prefs.edit().putStringSet(KEY, set).apply();
    }

    public void unmute(String uid) {
        Set<String> set = new HashSet<>(getMutedUids());
        set.remove(uid);
        prefs.edit().putStringSet(KEY, set).apply();
    }

    public boolean isMuted(String uid) {
        return getMutedUids().contains(uid);
    }

    public Set<String> getMutedUids() {
        return prefs.getStringSet(KEY, new HashSet<>());
    }

    public void clearAll() {
        prefs.edit().remove(KEY).apply();
    }
}
