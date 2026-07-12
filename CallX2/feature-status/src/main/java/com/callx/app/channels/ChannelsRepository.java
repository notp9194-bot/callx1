package com.callx.app.channels;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Seeds a small, realistic set of channels and persists per-user state (following,
 * dismissed suggestions, unread count, suggestions-expanded) in SharedPreferences.
 * There is no channels backend, so posts are static sample text — this keeps the
 * Channels section on the Updates screen fully interactive (follow/unfollow, dismiss,
 * open, mark-as-read, explore) without needing a server.
 */
public class ChannelsRepository {

    private static final String PREFS = "callx_channels_prefs";
    private static final String KEY_FOLLOWING = "following_";   // + id -> boolean
    private static final String KEY_DISMISSED = "dismissed_";   // + id -> boolean
    private static final String KEY_UNREAD    = "unread_";      // + id -> int
    private static final String KEY_SUGGESTIONS_EXPANDED = "suggestions_expanded";

    private final SharedPreferences prefs;
    private final Map<String, ChannelItem> byId = new LinkedHashMap<>();

    public ChannelsRepository(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        seed();
        applyPersistedState();
    }

    private void add(ChannelItem c) { byId.put(c.id, c); }

    private void seed() {
        add(new ChannelItem("who_cares", "Who Cares?", false, "Entertainment", 214_000, new String[]{
                "Good morning everyone \u2600\uFE0F",
                "New drop tonight, stay tuned \uD83D\uDC40",
                "Minimum requirements \uD83D\uDE06\uD83D\uDE0F"
        }));
        add(new ChannelItem("bbc_news", "BBC News", true, "News", 8_400_000, new String[]{
                "Breaking: markets react to overnight developments.",
                "Full coverage available on our website."
        }));
        add(new ChannelItem("gna_university", "GNA University", true, "Education", 62_000, new String[]{
                "Admissions open for the new semester.",
                "Campus placement drive results announced."
        }));
        add(new ChannelItem("aashirvad_cinemas", "Aashirvad Cinemas", false, "Entertainment", 5_200, new String[]{
                "New releases this weekend \uD83C\uDFA5"
        }));
        add(new ChannelItem("lulu_mall", "LuLu Mall Kozhikode", false, "Shopping", 41_000, new String[]{
                "Weekend offers live now!"
        }));
        add(new ChannelItem("nde_updates", "NDE Upcoming Updates", false, "News", 3_100, new String[]{
                "Notification schedule released."
        }));
        add(new ChannelItem("tech_daily", "Tech Daily", true, "Technology", 1_250_000, new String[]{
                "Today's top 5 tech stories.",
                "New phone leaks surface online."
        }));
        add(new ChannelItem("cricket_buzz", "Cricket Buzz", false, "Sports", 980_000, new String[]{
                "Match preview: today's line-ups are in."
        }));

        // "Who Cares?" ships pre-followed with an unread badge, matching the reference
        // screenshot exactly for a first-run install.
        ChannelItem whoCares = byId.get("who_cares");
        whoCares.following = true;
        whoCares.unreadCount = 999; // shown as "999+"
        whoCares.lastPostAtMillis = System.currentTimeMillis();
    }

    private void applyPersistedState() {
        for (ChannelItem c : byId.values()) {
            if (prefs.contains(KEY_FOLLOWING + c.id)) c.following = prefs.getBoolean(KEY_FOLLOWING + c.id, c.following);
            c.dismissed = prefs.getBoolean(KEY_DISMISSED + c.id, false);
            if (prefs.contains(KEY_UNREAD + c.id)) c.unreadCount = prefs.getInt(KEY_UNREAD + c.id, c.unreadCount);
        }
    }

    public boolean isSuggestionsExpanded() { return prefs.getBoolean(KEY_SUGGESTIONS_EXPANDED, true); }

    public void setSuggestionsExpanded(boolean expanded) {
        prefs.edit().putBoolean(KEY_SUGGESTIONS_EXPANDED, expanded).apply();
    }

    public List<ChannelItem> getFollowed() {
        List<ChannelItem> out = new ArrayList<>();
        for (ChannelItem c : byId.values()) if (c.following) out.add(c);
        return out;
    }

    /** Not-yet-followed, not-dismissed channels — shown under "Find channels to follow". */
    public List<ChannelItem> getSuggestions() {
        List<ChannelItem> out = new ArrayList<>();
        for (ChannelItem c : byId.values()) if (!c.following && !c.dismissed) out.add(c);
        return out;
    }

    /** All channels — followed and not — used by the "Explore" bottom sheet. */
    public List<ChannelItem> getAll() {
        return new ArrayList<>(byId.values());
    }

    public ChannelItem get(String id) { return byId.get(id); }

    public void setFollowing(String id, boolean following) {
        ChannelItem c = byId.get(id);
        if (c == null) return;
        c.following = following;
        if (following) {
            c.dismissed = false;
            prefs.edit().putBoolean(KEY_DISMISSED + id, false).apply();
        }
        prefs.edit().putBoolean(KEY_FOLLOWING + id, following).apply();
    }

    public void dismissSuggestion(String id) {
        ChannelItem c = byId.get(id);
        if (c == null) return;
        c.dismissed = true;
        prefs.edit().putBoolean(KEY_DISMISSED + id, true).apply();
    }

    public void markRead(String id) {
        ChannelItem c = byId.get(id);
        if (c == null) return;
        c.unreadCount = 0;
        prefs.edit().putInt(KEY_UNREAD + id, 0).apply();
    }
}
