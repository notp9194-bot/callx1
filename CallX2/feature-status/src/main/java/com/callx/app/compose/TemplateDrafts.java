package com.callx.app.compose;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * v774 — local drafts of the Create Template screen (My Templates > Drafts).
 *
 * A draft = the screen's choices (scenes / frame / effects / music / category) — NOT a posted status, so it lives on this device only
 * (SharedPreferences, one JSON array per account). Each entry: {@code {"id","ts","json","state"}}; {@code json} is the finished
 * templateJson (for the card preview), {@code state} is everything {@link CreateTemplateActivity} needs to restore itself.
 */
public final class TemplateDrafts {

    public static final int MAX_DRAFTS = 20;
    private static final int MAX_STATE_CHARS = 30_000;

    public static final class Draft {
        public String id, json, state;
        public long ts;
        /** Number of scenes (1 for a normal template) — for the card subtitle. */
        public int scenes() { return TemplateSpec.parse(json).isMultiScene() ? TemplateSpec.parse(json).scenes.size() : 1; }
    }

    private TemplateDrafts() { }

    private static SharedPreferences prefs(@NonNull Context c) {
        return c.getApplicationContext().getSharedPreferences("template_drafts", Context.MODE_PRIVATE);
    }

    private static String key(@NonNull String uid) { return "d_" + uid; }

    @NonNull
    public static List<Draft> list(@NonNull Context c, @NonNull String uid) {
        final List<Draft> out = new ArrayList<>();
        try {
            final JSONArray a = new JSONArray(prefs(c).getString(key(uid), "[]"));
            for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                final Draft d = new Draft();
                d.id = o.optString("id", "");
                d.ts = o.optLong("ts", 0L);
                d.json = o.optString("json", "");
                d.state = o.optString("state", "");
                if (!d.id.isEmpty() && !d.state.isEmpty()) out.add(d);
            }
        } catch (Exception ignored) { }
        return out;
    }

    @Nullable
    public static Draft get(@NonNull Context c, @NonNull String uid, @Nullable String id) {
        if (id == null) return null;
        for (Draft d : list(c, uid)) if (id.equals(d.id)) return d;
        return null;
    }

    /** Creates (id == null) or replaces a draft; returns its id, or null when too big / failed. Oldest drafts beyond the cap are dropped. */
    @Nullable
    public static String save(@NonNull Context c, @NonNull String uid, @Nullable String id, @NonNull String json, @NonNull String state) {
        if (state.length() > MAX_STATE_CHARS || json.length() > MAX_STATE_CHARS) return null;
        try {
            final String did = id != null && !id.isEmpty() ? id : "d" + System.currentTimeMillis();
            final List<Draft> all = list(c, uid);
            for (int i = all.size() - 1; i >= 0; i--) if (did.equals(all.get(i).id)) all.remove(i);
            final Draft n = new Draft();
            n.id = did; n.ts = System.currentTimeMillis(); n.json = json; n.state = state;
            all.add(0, n);
            while (all.size() > MAX_DRAFTS) all.remove(all.size() - 1);
            write(c, uid, all);
            return did;
        } catch (Exception e) {
            return null;
        }
    }

    public static void delete(@NonNull Context c, @NonNull String uid, @Nullable String id) {
        if (id == null) return;
        final List<Draft> all = list(c, uid);
        for (int i = all.size() - 1; i >= 0; i--) if (id.equals(all.get(i).id)) all.remove(i);
        write(c, uid, all);
    }

    private static void write(Context c, String uid, List<Draft> all) {
        try {
            final JSONArray a = new JSONArray();
            for (Draft d : all)
                a.put(new JSONObject().put("id", d.id).put("ts", d.ts).put("json", d.json).put("state", d.state));
            prefs(c).edit().putString(key(uid), a.toString()).apply();
        } catch (Exception ignored) { }
    }
}
