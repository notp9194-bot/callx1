package com.callx.app.conversation.controllers;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the device's recent photos/videos — powers both the compact
 * attach-sheet strip and the expanded "Recents" grid.
 *
 * Images and videos are queried separately against their own dedicated
 * MediaStore tables (MediaStore.Images.Media / MediaStore.Video.Media)
 * instead of a single combined MediaStore.Files query. The old combined
 * query selected VideoColumns.DURATION alongside the Files table, which
 * some OEM ContentProvider implementations reject with an
 * IllegalArgumentException ("no such column") when the result set includes
 * image rows — the query blew up and the exception was silently swallowed,
 * so the sheet always showed "No recent photos or videos" even when the
 * device had plenty. Querying each table with only its own native columns
 * avoids the invalid-column case entirely.
 *
 * Runs synchronously — ALWAYS call off the main thread (see
 * ChatMediaController, which dispatches this on a background executor and
 * posts the result back).
 */
public final class RecentMediaLoader {

    private static final String TAG = "RecentMediaLoader";

    private RecentMediaLoader() {}

    public static final class Item {
        public final Uri uri;
        public final boolean isVideo;
        public final long durationMs;
        public final long dateAddedSec;

        Item(Uri uri, boolean isVideo, long durationMs, long dateAddedSec) {
            this.uri = uri;
            this.isVideo = isVideo;
            this.durationMs = durationMs;
            this.dateAddedSec = dateAddedSec;
        }
    }

    /** @param limit max items to return (strip needs ~30, grid can ask for more). */
    public static List<Item> loadRecent(Context context, int limit) {
        int safeLimit = Math.max(1, limit);

        List<Item> images = queryImages(context, safeLimit);
        List<Item> videos = queryVideos(context, safeLimit);

        List<Item> merged = new ArrayList<>(images.size() + videos.size());
        merged.addAll(images);
        merged.addAll(videos);
        Collections.sort(merged, (a, b) -> Long.compare(b.dateAddedSec, a.dateAddedSec));

        if (merged.size() > safeLimit) {
            merged = new ArrayList<>(merged.subList(0, safeLimit));
        }
        return merged;
    }

    private static List<Item> queryImages(Context context, int limit) {
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
        };
        String order = MediaStore.Images.Media.DATE_ADDED + " DESC LIMIT " + limit;

        List<Item> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(uri, projection, null, null, order)) {
            if (c == null) return out;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                long dateAdded = c.getLong(dateIdx);
                out.add(new Item(ContentUris.withAppendedId(uri, id), false, 0L, dateAdded));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // No permission yet, or a weird OEM cursor shape — caller just gets
            // an empty strip/grid instead of a crash; the icon-grid attach
            // options (Gallery/Camera/etc.) keep working regardless.
            Log.w(TAG, "queryImages failed", e);
        }
        return out;
    }

    private static List<Item> queryVideos(Context context, int limit) {
        Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION
        };
        String order = MediaStore.Video.Media.DATE_ADDED + " DESC LIMIT " + limit;

        List<Item> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(uri, projection, null, null, order)) {
            if (c == null) return out;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int durIdx = c.getColumnIndex(MediaStore.Video.Media.DURATION);
            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                long dateAdded = c.getLong(dateIdx);
                long duration = durIdx >= 0 ? c.getLong(durIdx) : 0L;
                out.add(new Item(ContentUris.withAppendedId(uri, id), true, duration, dateAdded));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "queryVideos failed", e);
        }
        return out;
    }
}
