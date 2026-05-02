package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Feature 11: Draft Message Save
 * Persists unsent message drafts per chatId so they survive activity recreation.
 */
public class DraftManager {
    private static final String PREFS = "chat_drafts";
    private static DraftManager instance;
    private final SharedPreferences prefs;

    private DraftManager(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized DraftManager getInstance(Context ctx) {
        if (instance == null) instance = new DraftManager(ctx);
        return instance;
    }

    public void saveDraft(String chatId, String text) {
        if (text == null || text.trim().isEmpty()) {
            prefs.edit().remove(chatId).apply();
        } else {
            prefs.edit().putString(chatId, text.trim()).apply();
        }
    }

    public String getDraft(String chatId) {
        return prefs.getString(chatId, "");
    }

    public void clearDraft(String chatId) {
        prefs.edit().remove(chatId).apply();
    }

    public boolean hasDraft(String chatId) {
        String d = prefs.getString(chatId, "");
        return d != null && !d.isEmpty();
    }
}
