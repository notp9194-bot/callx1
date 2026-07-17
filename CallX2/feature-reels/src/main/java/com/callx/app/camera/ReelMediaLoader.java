package com.callx.app.camera;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * ReelMediaLoader — loads recent photos and videos from MediaStore.
 * Queries images and videos in separate passes (avoids OEM cursor bugs
 * when mixing image-only and video-only columns in one query).
 *
 * Always call off the main thread.
 */
public final class ReelMediaLoader {

    private static final String TAG = "ReelMediaLoader";
    private static final int    DEFAULT_LIMIT = 80;

    private ReelMediaLoader() {}

    /** Filter passed to {@link #load}. */
    public enum Filter { ALL, PHOTOS, VIDEOS }

    /** One gallery item. */
    public static final class Item {
        public final Uri  uri;
        public final boolean isVideo;
        public final long durationMs;
        public final long dateAddedSec;

        Item(Uri uri, boolean isVideo, long durationMs, long dateAddedSec) {
            this.uri          = uri;
            this.isVideo      = isVideo;
            this.durationMs   = durationMs;
            this.dateAddedSec = dateAddedSec;
        }
    }

    /** Load up to {@code limit} recent items matching {@code filter}. */
    public static List<Item> load(Context ctx, Filter filter, int limit) {
        List<Item> out = new ArrayList<>();
        if (filter != Filter.VIDEOS) out.addAll(queryImages(ctx, limit));
        if (filter != Filter.PHOTOS) out.addAll(queryVideos(ctx, limit));

        // Sort newest-first across the merged list
        Collections.sort(out, (a, b) -> Long.compare(b.dateAddedSec, a.dateAddedSec));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Convenience — load with default limit. */
    public static List<Item> load(Context ctx, Filter filter) {
        return load(ctx, filter, DEFAULT_LIMIT);
    }

    // ── Images ────────────────────────────────────────────────────────────

    private static List<Item> queryImages(Context ctx, int limit) {
        Uri base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED
        };
        String order = MediaStore.Images.Media.DATE_ADDED + " DESC";
        List<Item> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(base, proj, null, null, order)) {
            if (c == null) return out;
            int idIdx   = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            while (c.moveToNext() && out.size() < limit) {
                long id   = c.getLong(idIdx);
                long date = c.getLong(dateIdx);
                out.add(new Item(ContentUris.withAppendedId(base, id), false, 0L, date));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "queryImages: " + e.getMessage());
        }
        return out;
    }

    // ── Videos ───────────────────────────────────────────────────────────

    private static List<Item> queryVideos(Context ctx, int limit) {
        Uri base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION
        };
        String order = MediaStore.Video.Media.DATE_ADDED + " DESC";
        List<Item> out = new ArrayList<>();
        try (Cursor c = ctx.getContentResolver().query(base, proj, null, null, order)) {
            if (c == null) return out;
            int idIdx   = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int durIdx  = c.getColumnIndex(MediaStore.Video.Media.DURATION);
            while (c.moveToNext() && out.size() < limit) {
                long id  = c.getLong(idIdx);
                long dur = durIdx >= 0 ? c.getLong(durIdx) : 0L;
                long dt  = c.getLong(dateIdx);
                out.add(new Item(ContentUris.withAppendedId(base, id), true, dur, dt));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "queryVideos: " + e.getMessage());
        }
        return out;
    }

    /** Format video duration ms → "m:ss". */
    public static String formatDuration(long ms) {
        long totalSec = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSec / 60, totalSec % 60);
    }
}
