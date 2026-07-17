package com.callx.app.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * LocalDraftsManager — Offline-first draft persistence via SharedPreferences JSON.
 *
 * No Firebase dependency — works offline, zero-crash.
 * Thumbnails extracted from local video file and cached as JPEG in getCacheDir().
 *
 * Draft JSON schema:
 *   { id, videoPath, thumbPath, caption, musicName, musicUrl,
 *     trimStartMs, trimEndMs, durationMs, timestamp,
 *     filterName, stickersJson, fontIdx, sizeSp }
 */
public final class LocalDraftsManager {

    private static final String PREFS_NAME = "reel_local_drafts";
    private static final String KEY_DRAFTS = "drafts_v2";

    private LocalDraftsManager() {}

    // ─────────────────────────────────────────────────────────────────────
    //  Model
    // ─────────────────────────────────────────────────────────────────────
    public static class LocalDraft {
        public String id           = UUID.randomUUID().toString();
        public String videoPath    = "";   // absolute local file path
        public String thumbPath    = "";   // absolute local JPEG thumbnail path
        public String caption      = "";
        public String musicName    = "";
        public String musicUrl     = "";
        public long   trimStartMs  = 0;
        public long   trimEndMs    = 0;
        public long   durationMs   = 0;
        public long   timestamp    = System.currentTimeMillis();
        public String filterName   = "";
        public String stickersJson = "";
        public float  speedX       = 1.0f;

        public LocalDraft() {}

        /** Human-readable age string: "just now", "5m ago", "2h ago", "3d ago" */
        public String ageLabel() {
            long diff = System.currentTimeMillis() - timestamp;
            if (diff < 60_000)               return "just now";
            if (diff < 3_600_000)            return (diff / 60_000)   + "m ago";
            if (diff < 86_400_000)           return (diff / 3_600_000) + "h ago";
            return                                   (diff / 86_400_000) + "d ago";
        }

        /** Duration string: "0:12", "1:03" */
        public String durationLabel() {
            if (durationMs <= 0) return "";
            long sec = durationMs / 1000;
            return String.format("%d:%02d", sec / 60, sec % 60);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────────────────

    /** Save (or replace) a draft. Thumbnail is extracted asynchronously if missing. */
    public static void save(Context ctx, LocalDraft draft) {
        if (draft.id == null || draft.id.isEmpty())
            draft.id = UUID.randomUUID().toString();

        // Extract thumbnail synchronously (call from background thread when possible)
        if ((draft.thumbPath == null || draft.thumbPath.isEmpty())
                && draft.videoPath != null && !draft.videoPath.isEmpty()) {
            draft.thumbPath = extractThumbnail(ctx, draft.videoPath, draft.id);
        }

        List<LocalDraft> all = getAll(ctx);

        // Replace existing by id
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(draft.id)) {
                all.set(i, draft);
                persist(ctx, all);
                return;
            }
        }

        // New draft — prepend (newest first)
        all.add(0, draft);
        persist(ctx, all);
    }

    /** Save from a background thread (extracts thumbnail then saves). */
    public static void saveAsync(Context ctx, LocalDraft draft, Runnable onDone) {
        new Thread(() -> {
            save(ctx, draft);
            if (onDone != null) onDone.run();
        }).start();
    }

    /** Load all drafts (newest first). Never throws. */
    public static List<LocalDraft> getAll(Context ctx) {
        SharedPreferences prefs = prefs(ctx);
        String json = prefs.getString(KEY_DRAFTS, "[]");
        List<LocalDraft> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                LocalDraft d = fromJson(arr.getJSONObject(i));
                if (d != null) result.add(d);
            }
        } catch (Exception ignored) {}
        return result;
    }

    /** Total count of saved local drafts. */
    public static int count(Context ctx) {
        return getAll(ctx).size();
    }

    /** Delete one draft by id. Also deletes its cached thumbnail file. */
    public static void delete(Context ctx, String id) {
        List<LocalDraft> all = getAll(ctx);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).id.equals(id)) {
                String thumb = all.get(i).thumbPath;
                if (thumb != null && !thumb.isEmpty()) new File(thumb).delete();
                all.remove(i);
                break;
            }
        }
        persist(ctx, all);
    }

    /** Delete all drafts and their cached thumbnails. */
    public static void deleteAll(Context ctx) {
        for (LocalDraft d : getAll(ctx)) {
            if (d.thumbPath != null && !d.thumbPath.isEmpty()) new File(d.thumbPath).delete();
        }
        prefs(ctx).edit().remove(KEY_DRAFTS).apply();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Thumbnail extraction
    // ─────────────────────────────────────────────────────────────────────
    private static String extractThumbnail(Context ctx, String videoPath, String id) {
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(videoPath);
            Bitmap bmp = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            mmr.release();
            if (bmp == null) return "";

            File out = new File(ctx.getCacheDir(), "draft_thumb_" + id + ".jpg");
            FileOutputStream fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos);
            fos.flush();
            fos.close();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  JSON helpers
    // ─────────────────────────────────────────────────────────────────────
    private static void persist(Context ctx, List<LocalDraft> drafts) {
        try {
            JSONArray arr = new JSONArray();
            for (LocalDraft d : drafts) arr.put(toJson(d));
            prefs(ctx).edit().putString(KEY_DRAFTS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static JSONObject toJson(LocalDraft d) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id",          d.id);
        o.put("videoPath",   d.videoPath);
        o.put("thumbPath",   d.thumbPath);
        o.put("caption",     d.caption);
        o.put("musicName",   d.musicName);
        o.put("musicUrl",    d.musicUrl);
        o.put("trimStartMs", d.trimStartMs);
        o.put("trimEndMs",   d.trimEndMs);
        o.put("durationMs",  d.durationMs);
        o.put("timestamp",   d.timestamp);
        o.put("filterName",  d.filterName);
        o.put("stickersJson",d.stickersJson);
        o.put("speedX",      d.speedX);
        return o;
    }

    private static LocalDraft fromJson(JSONObject o) {
        try {
            LocalDraft d       = new LocalDraft();
            d.id               = o.optString("id",          UUID.randomUUID().toString());
            d.videoPath        = o.optString("videoPath",   "");
            d.thumbPath        = o.optString("thumbPath",   "");
            d.caption          = o.optString("caption",     "");
            d.musicName        = o.optString("musicName",   "");
            d.musicUrl         = o.optString("musicUrl",    "");
            d.trimStartMs      = o.optLong("trimStartMs",   0);
            d.trimEndMs        = o.optLong("trimEndMs",     0);
            d.durationMs       = o.optLong("durationMs",    0);
            d.timestamp        = o.optLong("timestamp",     System.currentTimeMillis());
            d.filterName       = o.optString("filterName",  "");
            d.stickersJson     = o.optString("stickersJson","");
            d.speedX           = (float) o.optDouble("speedX", 1.0);
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
