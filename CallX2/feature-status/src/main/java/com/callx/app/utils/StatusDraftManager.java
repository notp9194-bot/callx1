package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import java.util.*;

/**
 * StatusDraftManager v26 — Full draft support: text + image/video URI + all settings.
 * FIX: Previously only text draft was saved; media + settings now included.
 */
public final class StatusDraftManager {
    private static final String PREFS = "status_draft_v26";
    private static final String KEY   = "draft_json";
    private StatusDraftManager() {}

    public static class Draft {
        public String text;
        public String caption;
        public String mediaUriStr;   // local URI string
        public String mediaType;     // "image"/"video"/"gif"
        public String bgColor;
        public String textColor;
        public String fontStyle;
        public String textAlign;
        public String privacy;
        public int    expiryHours;
        public boolean isCloseFriends;
        public long   savedAt;
    }

    public static void save(Context ctx, Draft draft) {
        if (ctx == null || draft == null) return;
        draft.savedAt = System.currentTimeMillis();
        prefs(ctx).edit().putString(KEY, new Gson().toJson(draft)).apply();
    }

    public static Draft load(Context ctx) {
        if (ctx == null) return null;
        String json = prefs(ctx).getString(KEY, null);
        if (json == null || json.isEmpty()) return null;
        try { return new Gson().fromJson(json, Draft.class); } catch (Exception e) { return null; }
    }

    public static boolean hasDraft(Context ctx) {
        if (ctx == null) return false;
        String json = prefs(ctx).getString(KEY, null);
        if (json == null || json.isEmpty()) return false;
        Draft d = load(ctx);
        if (d == null) return false;
        // Drafts older than 48h are considered stale
        return (System.currentTimeMillis() - d.savedAt) < 48 * 3600_000L;
    }

    public static void clear(Context ctx) {
        if (ctx != null) prefs(ctx).edit().remove(KEY).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
