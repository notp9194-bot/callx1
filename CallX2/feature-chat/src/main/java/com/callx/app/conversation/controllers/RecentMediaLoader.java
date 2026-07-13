package com.callx.app.conversation.controllers;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the device's recent photos/videos straight from MediaStore.Files
 * (images ∪ video, sorted by DATE_ADDED desc) — powers both the compact
 * attach-sheet strip and the expanded "Recents" grid.
 *
 * Runs synchronously — ALWAYS call off the main thread (see
 * ChatMediaController, which dispatches this on a background executor and
 * posts the result back).
 */
public final class RecentMediaLoader {

    private RecentMediaLoader() {}

    public static final class Item {
        public final Uri uri;
        public final boolean isVideo;
        public final long durationMs;

        Item(Uri uri, boolean isVideo, long durationMs) {
            this.uri = uri;
            this.isVideo = isVideo;
            this.durationMs = durationMs;
        }
    }

    /** @param limit max items to return (strip needs ~30, grid can ask for more). */
    public static List<Item> loadRecent(Context context, int limit) {
        List<Item> out = new ArrayList<>();

        Uri filesUri = MediaStore.Files.getContentUri("external");
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Video.VideoColumns.DURATION
        };
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR "
                + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?";
        String[] selectionArgs = {
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO)
        };
        String order = MediaStore.Files.FileColumns.DATE_ADDED + " DESC LIMIT " + Math.max(1, limit);

        try (Cursor c = context.getContentResolver().query(
                filesUri, projection, selection, selectionArgs, order)) {
            if (c == null) return out;

            int idIdx       = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
            int typeIdx      = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
            int durIdx       = c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION);

            while (c.moveToNext()) {
                long id = c.getLong(idIdx);
                int type = c.getInt(typeIdx);
                boolean isVideo = type == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;
                long duration = (isVideo && durIdx >= 0) ? c.getLong(durIdx) : 0L;

                Uri contentUri = isVideo
                        ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                Uri itemUri = ContentUris.withAppendedId(contentUri, id);
                out.add(new Item(itemUri, isVideo, duration));
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            // No permission yet, or a weird OEM cursor shape — caller just gets
            // an empty strip/grid instead of a crash; the icon-grid attach
            // options (Gallery/Camera/etc.) keep working regardless.
        }
        return out;
    }
}
