package com.callx.app.utils;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;
/**
 * StatusMuteManager — Mute/unmute contacts' status updates locally.
 * Muted contacts still load but are hidden from "Recent updates" section.
 * Data stored in SharedPreferences — no Firebase sync needed.
 */
public final class StatusMuteManager {
    private static final String PREFS  = "status_mute_prefs";
    private static final String KEY    = "muted_uids";
    private StatusMuteManager() {}
    public static boolean isMuted(Context ctx, String uid) {
        if (uid == null) return false;
        return getMutedSet(ctx).contains(uid);
    }
    public static void mute(Context ctx, String uid) {
        if (uid == null) return;
        Set<String> s = getMutedSet(ctx);
        s.add(uid);
        save(ctx, s);
    }
    public static void unmute(Context ctx, String uid) {
        if (uid == null) return;
        Set<String> s = getMutedSet(ctx);
        s.remove(uid);
        save(ctx, s);
    }
    public static void toggle(Context ctx, String uid) {
        if (isMuted(ctx, uid)) unmute(ctx, uid);
        else                   mute(ctx, uid);
    }
    public static Set<String> getMutedSet(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
    }
    private static void save(Context ctx, Set<String> s) {
        prefs(ctx).edit().putStringSet(KEY, s).apply();
    }
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}