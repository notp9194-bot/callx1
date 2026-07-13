package com.callx.app.conversation.controllers;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * v156 adds folder (MediaStore "bucket") support: the expanded header's
 * "Recents ▾" is now a real dropdown (see AttachSheetFolderPicker) that
 * lets the user switch the grid to just Camera / Videos / Screenshots /
 * Downloads / WhatsApp / any other on-device folder — same picker UX as
 * the native Google Photos share-sheet screenshot this was modeled on.
 *
 * Runs synchronously — ALWAYS call off the main thread (see
 * ChatMediaController, which dispatches this on a background executor and
 * posts the result back).
 */
public final class RecentMediaLoader {

    private static final String TAG = "RecentMediaLoader";

    /** Special filter key for the synthetic "Videos" folder (all videos, any bucket). */
    public static final String FILTER_VIDEOS = "__videos__";
    /** Filter key meaning "no filter" — every image + video, newest first (the default "Recents" view). */
    public static final String FILTER_ALL = null;

    // How many rows to scan (per table) when building the folder list / applying
    // a name filter. MediaStore reads of just a few indexed columns are cheap,
    // so this comfortably covers even large libraries (the reference device in
    // the screenshot this feature was built from has ~4.9k total items).
    private static final int FOLDER_SCAN_LIMIT = 8000;

    private RecentMediaLoader() {}

    public static final class Item {
        public final Uri uri;
        public final boolean isVideo;
        public final long durationMs;
        public final long dateAddedSec;
        public final String bucketName;

        Item(Uri uri, boolean isVideo, long durationMs, long dateAddedSec, String bucketName) {
            this.uri = uri;
            this.isVideo = isVideo;
            this.durationMs = durationMs;
            this.dateAddedSec = dateAddedSec;
            this.bucketName = bucketName == null ? "" : bucketName;
        }
    }

    /** One row in the "Recents ▾" folder-picker dropdown (see AttachSheetFolderPicker). */
    public static final class Folder {
        /** Filter key to pass back into loadRecentPage()'s filter param when this folder is picked. */
        public final String filterKey;
        public final String name;
        public final Uri coverUri;
        public final boolean coverIsVideo;
        public final int itemCount;
        /** True for the two static "More apps" / "See more" action rows appended
         *  after WhatsApp — these don't filter the grid at all, see AttachFolderAdapter
         *  and AttachSheetRecentMediaBinder's ACTION_MORE_APPS/ACTION_SEE_MORE handling. */
        public final boolean isAction;

        Folder(String filterKey, String name, Uri coverUri, boolean coverIsVideo, int itemCount) {
            this(filterKey, name, coverUri, coverIsVideo, itemCount, false);
        }

        private Folder(String filterKey, String name, Uri coverUri, boolean coverIsVideo, int itemCount, boolean isAction) {
            this.filterKey = filterKey;
            this.name = name;
            this.coverUri = coverUri;
            this.coverIsVideo = coverIsVideo;
            this.itemCount = itemCount;
            this.isAction = isAction;
        }

        static Folder action(String filterKey, String name) {
            return new Folder(filterKey, name, null, false, -1, true);
        }
    }

    /** Filter key for the "More apps" action row — see AttachSheetRecentMediaBinder. */
    public static final String ACTION_MORE_APPS = "__action_more_apps__";
    /** Filter key for the "See more" action row — see AttachSheetRecentMediaBinder. */
    public static final String ACTION_SEE_MORE = "__action_see_more__";

    /** @param limit max items to return (strip needs ~30, grid can ask for more). */
    public static List<Item> loadRecent(Context context, int limit) {
        return loadRecentPage(context, 0, limit);
    }

    public static List<Item> loadRecentPage(Context context, int offset, int limit) {
        return loadRecentPage(context, offset, limit, FILTER_ALL);
    }

    /**
     * Paged version for the expanded Recents grid's "unlimited" scroll.
     * Since images/videos live in two separate MediaStore tables, we can't
     * just LIMIT/OFFSET each table independently and merge (offsets would
     * drift out of date-order once merged). Instead each call re-queries
     * BOTH tables up to (offset+limit) rows, sorts the merged list by
     * date_added, then slices out just the requested page. This re-reads
     * a growing prefix on every page — fine here since MediaStore cursor
     * reads are cheap and this only runs while the user is actively
     * scrolling an open sheet (not a persistent/background query).
     *
     * @param offset how many newest items to skip (0 = first page)
     * @param limit  page size
     * @param filter which folder to restrict to: {@link #FILTER_ALL} (everything),
     *               {@link #FILTER_VIDEOS} (every video regardless of folder), or
     *               a bucket name (matched case-insensitively) as returned by
     *               {@link Folder#filterKey} for a real on-device folder.
     */
    public static List<Item> loadRecentPage(Context context, int offset, int limit, String filter) {
        int safeOffset = Math.max(0, offset);
        int safeLimit  = Math.max(1, limit);
        int total = safeOffset + safeLimit;

        boolean videosOnly = FILTER_VIDEOS.equals(filter);
        String bucketFilter = (filter != null && !videosOnly) ? filter : null;

        List<Item> images = videosOnly ? new ArrayList<>() : queryImages(context, total, bucketFilter);
        List<Item> videos = queryVideos(context, total, bucketFilter);

        List<Item> merged = new ArrayList<>(images.size() + videos.size());
        merged.addAll(images);
        merged.addAll(videos);
        Collections.sort(merged, (a, b) -> Long.compare(b.dateAddedSec, a.dateAddedSec));

        if (safeOffset >= merged.size()) return new ArrayList<>(); // no more pages
        int end = Math.min(merged.size(), safeOffset + safeLimit);
        return new ArrayList<>(merged.subList(safeOffset, end));
    }

    /**
     * Builds the folder list for the "Recents ▾" dropdown: Recents (everything),
     * Camera, Videos, Screenshots, Downloads, WhatsApp — each only included if
     * it actually has at least one item on this device — followed by the two
     * static "More apps" / "See more" action rows, matching the reference
     * screenshot's dropdown exactly. "More apps" hands off to the system
     * content chooser (same as the Document chip) and "See more" hands off to
     * the system Photos picker (same as the Gallery chip) — see
     * AttachSheetRecentMediaBinder's ACTION_MORE_APPS / ACTION_SEE_MORE handling.
     */
    public static List<Folder> loadFolders(Context context) {
        List<Item> images = queryImages(context, FOLDER_SCAN_LIMIT, null);
        List<Item> videos = queryVideos(context, FOLDER_SCAN_LIMIT, null);

        List<Item> merged = new ArrayList<>(images.size() + videos.size());
        merged.addAll(images);
        merged.addAll(videos);
        Collections.sort(merged, (a, b) -> Long.compare(b.dateAddedSec, a.dateAddedSec));

        List<Folder> result = new ArrayList<>();
        if (merged.isEmpty()) {
            // Even with nothing on-device yet, "More apps"/"See more" still work
            // (they hand off to system pickers, not this device's MediaStore).
            result.add(Folder.action(ACTION_MORE_APPS, "More apps"));
            result.add(Folder.action(ACTION_SEE_MORE, "See more"));
            return result;
        }

        // Recents = everything, cover = newest item overall.
        Item newestOverall = merged.get(0);
        result.add(new Folder(FILTER_ALL, "Recents", newestOverall.uri, newestOverall.isVideo, merged.size()));

        // Per-bucket aggregation (first occurrence in the date-desc list = newest → cover).
        Map<String, BucketAgg> byBucket = new LinkedHashMap<>();
        int videoTotal = 0;
        Item newestVideo = null;
        for (Item item : merged) {
            if (item.isVideo) {
                videoTotal++;
                if (newestVideo == null) newestVideo = item;
            }
            String key = item.bucketName.trim().toLowerCase(Locale.US);
            if (key.isEmpty()) continue;
            BucketAgg agg = byBucket.get(key);
            if (agg == null) {
                agg = new BucketAgg(item.bucketName.trim(), item.uri, item.isVideo);
                agg.coverDateHint = item.dateAddedSec;
                byBucket.put(key, agg);
            }
            agg.count++;
        }

        BucketAgg camera = findBucket(byBucket, "camera");
        if (camera != null) result.add(camera.toFolder("Camera"));

        // Videos = synthetic "every video, any folder" entry, matching the
        // reference screenshot's dedicated Videos tile.
        if (videoTotal > 0 && newestVideo != null) {
            result.add(new Folder(FILTER_VIDEOS, "Videos", newestVideo.uri, true, videoTotal));
        }

        BucketAgg screenshots = findBucket(byBucket, "screenshot");
        if (screenshots != null) result.add(screenshots.toFolder("Screenshots"));

        BucketAgg downloads = findBucket(byBucket, "download");
        if (downloads != null) result.add(downloads.toFolder("Downloads"));

        // WhatsApp media lives in split buckets ("WhatsApp Images", "WhatsApp
        // Video", "WhatsApp Animated Gifs", ...) — merge every bucket whose
        // name contains "whatsapp" into one tile, like the reference picker.
        BucketAgg whatsapp = null;
        for (Map.Entry<String, BucketAgg> e : byBucket.entrySet()) {
            if (!e.getKey().contains("whatsapp")) continue;
            BucketAgg sub = e.getValue();
            if (whatsapp == null) {
                whatsapp = new BucketAgg("WhatsApp", sub.coverUri, sub.coverIsVideo);
                whatsapp.count = 0;
                whatsapp.coverDateHint = sub.coverDateHint;
            }
            whatsapp.count += sub.count;
            if (sub.coverDateHint > whatsapp.coverDateHint || whatsapp.count == sub.count) {
                whatsapp.coverUri = sub.coverUri;
                whatsapp.coverIsVideo = sub.coverIsVideo;
                whatsapp.coverDateHint = sub.coverDateHint;
            }
        }
        if (whatsapp != null) {
            result.add(new Folder("__whatsapp__", "WhatsApp", whatsapp.coverUri, whatsapp.coverIsVideo, whatsapp.count));
        }

        // Static action rows — always last, exactly like the reference screenshot.
        result.add(Folder.action(ACTION_MORE_APPS, "More apps"));
        result.add(Folder.action(ACTION_SEE_MORE, "See more"));

        return result;
    }

    private static BucketAgg findBucket(Map<String, BucketAgg> byBucket, String needle) {
        for (Map.Entry<String, BucketAgg> e : byBucket.entrySet()) {
            if (e.getKey().contains(needle)) return e.getValue();
        }
        return null;
    }

    /** Mutable per-bucket accumulator used only while building loadFolders()'s result. */
    private static final class BucketAgg {
        final String key;
        final String displayName;
        Uri coverUri;
        boolean coverIsVideo;
        long coverDateHint;
        int count;

        BucketAgg(String displayName, Uri coverUri, boolean coverIsVideo) {
            this.key = displayName.trim().toLowerCase(Locale.US);
            this.displayName = displayName;
            this.coverUri = coverUri;
            this.coverIsVideo = coverIsVideo;
        }

        Folder toFolder(String labelOverride) {
            // Exact-name filter for a specific real bucket — LOWER()-matched
            // in queryImages/queryVideos, so casing on device doesn't matter.
            return new Folder(displayName, labelOverride, coverUri, coverIsVideo, count);
        }
    }

    private static List<Item> queryImages(Context context, int limit, String bucketFilter) {
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        };
        String order = MediaStore.Images.Media.DATE_ADDED + " DESC";

        String selection = null;
        String[] selectionArgs = null;
        if (bucketFilter != null) {
            selection = "LOWER(" + MediaStore.Images.Media.BUCKET_DISPLAY_NAME + ") LIKE ?";
            selectionArgs = new String[]{"%" + bucketFilter.toLowerCase(Locale.US) + "%"};
        }

        List<Item> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(uri, projection, selection, selectionArgs, order)) {
            if (c == null) return out;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            int bucketIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            while (c.moveToNext() && out.size() < limit) {
                long id = c.getLong(idIdx);
                long dateAdded = c.getLong(dateIdx);
                String bucket = bucketIdx >= 0 ? c.getString(bucketIdx) : null;
                out.add(new Item(ContentUris.withAppendedId(uri, id), false, 0L, dateAdded, bucket));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // No permission yet, or a weird OEM cursor shape — caller just gets
            // an empty strip/grid instead of a crash; the icon-grid attach
            // options (Gallery/Camera/etc.) keep working regardless.
            Log.w(TAG, "queryImages failed", e);
        }
        return out;
    }

    private static List<Item> queryVideos(Context context, int limit, String bucketFilter) {
        Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        };
        String order = MediaStore.Video.Media.DATE_ADDED + " DESC";

        String selection = null;
        String[] selectionArgs = null;
        if (bucketFilter != null) {
            selection = "LOWER(" + MediaStore.Video.Media.BUCKET_DISPLAY_NAME + ") LIKE ?";
            selectionArgs = new String[]{"%" + bucketFilter.toLowerCase(Locale.US) + "%"};
        }

        List<Item> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(uri, projection, selection, selectionArgs, order)) {
            if (c == null) return out;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int dateIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int durIdx = c.getColumnIndex(MediaStore.Video.Media.DURATION);
            int bucketIdx = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            while (c.moveToNext() && out.size() < limit) {
                long id = c.getLong(idIdx);
                long dateAdded = c.getLong(dateIdx);
                long duration = durIdx >= 0 ? c.getLong(durIdx) : 0L;
                String bucket = bucketIdx >= 0 ? c.getString(bucketIdx) : null;
                out.add(new Item(ContentUris.withAppendedId(uri, id), true, duration, dateAdded, bucket));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "queryVideos failed", e);
        }
        return out;
    }
}
