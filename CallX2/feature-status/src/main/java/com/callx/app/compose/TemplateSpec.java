package com.callx.app.compose;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v767 — typed view of {@code templateJson} for the v2 template screens (editor / video slots / preview).
 *
 * <p>Fully backward compatible: the JSON stays {@code "v":1} and every new key is OPTIONAL. Old templates (no new keys)
 * parse as photo slots with default filter / animation; old apps simply ignore the new keys.</p>
 *
 * <pre>
 * { "v":1, "slots":[ {base..}, {cx,cy,w,h,rot, shape.., "k":"video", "d":4000, "flt":"warm", "an":"zoom_in"} ],
 *   "cat":"love", "tm":0, "tdur":15000, "msync":1 }
 * </pre>
 * k = slot kind ("img" default | "video"), d = slot duration ms, flt = filter key, an = animation key,
 * tdur = whole template duration ms, msync = 1 → cuts follow the music beat (editor "Music Sync").
 *
 * <p>v774 MULTI-SCENE: optional {@code "scenes":[{"d":3000},{"d":2500,"slots":[..]},..]}. Scene 0 is ALWAYS the root {@code "slots"}
 * (its entry only carries the length), so an old app / the v772 Editor still sees a normal one-scene template; scenes 1.. carry their
 * own {@code slots} (same shape as the root ones, first = that scene's full-canvas base). Scenes play one after another.</p>
 */
public final class TemplateSpec {

    public static final String KIND_IMG = "img";
    public static final String KIND_VIDEO = "video";

    public static final String[] FILTERS = {"natural", "warm", "cool", "bw", "vivid", "fade"};
    public static final String[] ANIMS = {"none", "zoom_in", "zoom_out", "pan_l", "pan_r", "fade"};

    public static final String DEF_FILTER = FILTERS[0];
    public static final String DEF_ANIM = ANIMS[0];

    public static final int MIN_SLOT_MS = 1_000;
    public static final int MAX_SLOT_MS = 15_000;
    public static final int DEF_SLOT_MS = 3_000;
    public static final int MAX_TOTAL_MS = 60_000;

    /** v774: scene limits. */
    public static final int MAX_SCENES = 5;
    public static final int MIN_SCENE_MS = 1_000;
    public static final int MAX_SCENE_MS = 10_000;
    public static final int DEF_SCENE_MS = 3_000;

    /** One box / scene of the template. */
    public static final class Slot {
        public boolean base;
        @NonNull public String kind = KIND_IMG;
        /** Slot duration in ms (0 = not set → {@link #DEF_SLOT_MS} when a duration is needed). */
        public int durMs;
        @NonNull public String filter = DEF_FILTER;
        @NonNull public String anim = DEF_ANIM;

        public boolean isVideo() { return KIND_VIDEO.equals(kind); }

        /** Effective duration, clamped. */
        public int effectiveMs() { return clampSlotMs(durMs > 0 ? durMs : DEF_SLOT_MS); }

        /** Writes ONLY non-default values, so old-style slots stay byte-identical. */
        public void putJson(@NonNull JSONObject o) {
            try {
                if (isVideo()) o.put("k", KIND_VIDEO);
                if (durMs > 0) o.put("d", clampSlotMs(durMs));
                if (!DEF_FILTER.equals(filter)) o.put("flt", filter);
                if (!DEF_ANIM.equals(anim)) o.put("an", anim);
            } catch (Exception ignored) { }
        }

        static Slot fromJson(@NonNull JSONObject o) {
            final Slot s = new Slot();
            s.base = o.optBoolean("base", false);
            s.kind = KIND_VIDEO.equals(o.optString("k", KIND_IMG)) ? KIND_VIDEO : KIND_IMG;
            s.durMs = Math.max(0, o.optInt("d", 0));
            s.filter = known(FILTERS, o.optString("flt", DEF_FILTER), DEF_FILTER);
            s.anim = known(ANIMS, o.optString("an", DEF_ANIM), DEF_ANIM);
            return s;
        }
    }

    /** v774: one scene of a multi-scene template. */
    public static final class Scene {
        public int durMs = DEF_SCENE_MS;
        /** Scene 0: the root {@link TemplateSpec#slots}. Scenes 1..: parsed from the scene's own "slots". */
        public final List<Slot> slots = new ArrayList<>();
        /** Raw geometry of scenes 1.. (null for scene 0 — the caller reads the root "slots"). */
        @Nullable public JSONArray rawSlots;

        public int effectiveMs() { return clampSceneMs(durMs); }
    }

    public final List<Slot> slots = new ArrayList<>();
    /** v774: empty = ordinary one-scene template; otherwise 2..{@link #MAX_SCENES} scenes (index 0 = root slots). */
    public final List<Scene> scenes = new ArrayList<>();
    /** Whole template duration in ms (0 = not set). */
    public int totalMs;
    public boolean musicSync;

    /** Parses templateJson; never throws. Returns an empty spec for null / bad JSON. */
    @NonNull public static TemplateSpec parse(@Nullable String json) {
        final TemplateSpec t = new TemplateSpec();
        if (json == null || json.isEmpty()) return t;
        try {
            final JSONObject root = new JSONObject(json);
            final JSONArray a = root.optJSONArray("slots");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                final JSONObject o = a.optJSONObject(i);
                if (o != null) t.slots.add(Slot.fromJson(o));
            }
            t.totalMs = Math.max(0, Math.min(MAX_TOTAL_MS, root.optInt("tdur", 0)));
            t.musicSync = root.optInt("msync", 0) == 1;
            final JSONArray sc = root.optJSONArray("scenes");
            if (sc != null && sc.length() >= 2) {
                for (int i = 0; i < sc.length() && i < MAX_SCENES; i++) {
                    final JSONObject so = sc.optJSONObject(i);
                    if (so == null) break;
                    final Scene s = new Scene();
                    s.durMs = clampSceneMs(so.optInt("d", DEF_SCENE_MS));
                    if (i == 0) {
                        s.slots.addAll(t.slots);
                    } else {
                        s.rawSlots = so.optJSONArray("slots");
                        if (s.rawSlots == null || s.rawSlots.length() == 0) { t.scenes.clear(); break; }   // broken scene → plain one-scene template
                        for (int k = 0; k < s.rawSlots.length(); k++) {
                            final JSONObject o = s.rawSlots.optJSONObject(k);
                            if (o != null) s.slots.add(Slot.fromJson(o));
                        }
                    }
                    t.scenes.add(s);
                }
                if (t.scenes.size() < 2) t.scenes.clear();
            }
        } catch (Exception ignored) { }
        return t;
    }

    /** Writes the template-level keys (only when set). Call on the root object next to "slots". */
    public void putMeta(@NonNull JSONObject root) {
        try {
            if (totalMs > 0) root.put("tdur", Math.min(MAX_TOTAL_MS, totalMs));
            if (musicSync) root.put("msync", 1);
        } catch (Exception ignored) { }
    }

    public boolean isMultiScene() { return scenes.size() >= 2; }

    /** Sum of the scene lengths (0 for a one-scene template). */
    public int scenesTotalMs() {
        int sum = 0;
        for (Scene s : scenes) sum += s.effectiveMs();
        return Math.min(MAX_TOTAL_MS, sum);
    }

    public boolean hasVideo() {
        for (Slot s : slots) if (s.isVideo()) return true;
        return false;
    }

    /** Duration to show on a card / editor: explicit total, else sum of the non-base slot durations, else 0. */
    public int displayMs() {
        if (totalMs > 0) return totalMs;
        if (isMultiScene()) return scenesTotalMs();
        int sum = 0;
        for (Slot s : slots) if (!s.base && s.durMs > 0) sum += s.effectiveMs();
        return Math.min(MAX_TOTAL_MS, sum);
    }

    /** "0:15" / "1:05"; "" when ms <= 0. */
    @NonNull public static String formatDuration(long ms) {
        if (ms <= 0) return "";
        final long sec = Math.max(1, Math.round(ms / 1000.0));
        return String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
    }

    public static int clampSlotMs(int ms) { return Math.max(MIN_SLOT_MS, Math.min(MAX_SLOT_MS, ms)); }

    public static int clampSceneMs(int ms) { return Math.max(MIN_SCENE_MS, Math.min(MAX_SCENE_MS, ms)); }

    @NonNull private static String known(@NonNull String[] keys, @Nullable String v, @NonNull String def) {
        if (v == null) return def;
        for (String k : keys) if (k.equals(v)) return v;
        return def;
    }
}
