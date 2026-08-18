package com.callx.app.utils;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashSet;
import java.util.Set;
/**
 * StatusHideManager — Hide/unhide contacts' status updates locally.
 * Hidden contacts still load in the background but are removed entirely from
 * "Recent updates" / "Muted" and shown only inside the "Hidden updates" screen.
 * Data stored in SharedPreferences — no Firebase sync needed (same pattern as
 * {@link StatusMuteManager}, kept as a separate store since hide and mute are
 * independent, WhatsApp-style concepts).
 */
public final class StatusHideManager {
    private static final String PREFS = "status_hide_prefs";
    private static final String KEY   = "hidden_uids";
    private StatusHideManager() {}
    public static boolean isHidden(Context ctx, String uid) {
        if (uid == null) return false;
        return getHiddenSet(ctx).contains(uid);
    }
    public static void hide(Context ctx, String uid) {
        if (uid == null) return;
        Set<String> s = getHiddenSet(ctx);
        s.add(uid);
        save(ctx, s);
    }
    public static void unhide(Context ctx, String uid) {
        if (uid == null) return;
        Set<String> s = getHiddenSet(ctx);
        s.remove(uid);
        save(ctx, s);
    }
    public static void toggle(Context ctx, String uid) {
        if (isHidden(ctx, uid)) unhide(ctx, uid);
        else                    hide(ctx, uid);
    }
    public static Set<String> getHiddenSet(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
    }
    private static void save(Context ctx, Set<String> s) {
        prefs(ctx).edit().putStringSet(KEY, s).apply();
    }
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
