package com.callx.app.conversation.controllers;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import com.callx.app.chat.R;

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
    /** Filter key for the merged "WhatsApp" folder (every bucket whose name contains "whatsapp"). */
    public static final String FILTER_WHATSAPP = "__whatsapp__";
    /** Filter key meaning "no filter" — every image + video, newest first (the default "Recents" view). */
    public static final String FILTER_ALL = null;

    // How many rows to scan (per table) when building the folder list / applying
    // a name filter. MediaStore reads of just a few indexed columns are cheap,
    // so this comfortably covers even large libraries (the reference device in
    // the screenshot this feature was built from has ~4.9k total items).
    private static final int FOLDER_SCAN_LIMIT = 8000;

    /** Which media types a surface wants (Config#mediaTypes in the binder). */
    public static final int MEDIA_ALL = 0;
    public static final int MEDIA_IMAGES = 1;
    public static final int MEDIA_VIDEOS = 2;

    private RecentMediaLoader() {}

    /**
     * Sort column. Q+ (API 29): DATE_TAKEN (ms) so a downloaded/restored old photo sits where it
     * was taken, not at the top because it was "added" today. MediaStore fills DATE_TAKEN for
     * most rows there (EXIF, else file time). Older versions keep DATE_ADDED (sec): DATE_TAKEN is
     * null for non-camera images there, which would bury them at the very end.
     */
    private static final boolean USE_TAKEN = android.os.Build.VERSION.SDK_INT >= 29;
    private static String dateCol() {
        return USE_TAKEN ? MediaStore.Images.Media.DATE_TAKEN : MediaStore.MediaColumns.DATE_ADDED;
    }

    private static long toDisplaySec(long sortKey, long addedSec) {
        if (USE_TAKEN) return sortKey > 0 ? sortKey / 1000L : addedSec;
        return sortKey;
    }

    public static final class Item {
        public final Uri uri;
        public final boolean isVideo;
        public final long durationMs;
        /** Capture time in seconds (falls back to date-added) — for display, e.g. the month chip. */
        public final long dateAddedSec;
        /** Raw value of the column the grid sorts/pages by (ms DATE_TAKEN on Q+, sec DATE_ADDED before). */
        public final long sortKey;
        public final String bucketName;
        /** MediaStore row id (parsed from uri) — the keyset-paging tie-breaker. */
        public final long id;

        Item(Uri uri, boolean isVideo, long durationMs, long sortKey, long addedSec, String bucketName) {
            this.uri = uri;
            this.isVideo = isVideo;
            this.durationMs = durationMs;
            this.sortKey = sortKey;
            this.dateAddedSec = toDisplaySec(sortKey, addedSec);
            this.bucketName = bucketName == null ? "" : bucketName;
            this.id = ContentUris.parseId(uri);
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
     *               a exact bucket name as returned by
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
        Collections.sort(merged, (a, b) -> Long.compare(b.sortKey, a.sortKey));

        if (safeOffset >= merged.size()) return new ArrayList<>(); // no more pages
        int end = Math.min(merged.size(), safeOffset + safeLimit);
        return new ArrayList<>(merged.subList(safeOffset, end));
    }

    /**
     * Keyset ("seek") paging: returns the page that comes strictly AFTER
     * {@code after} in the grid's order (date_added DESC, images before videos
     * on equal dates, then _id DESC); pass null for the first page.
     * Unlike the offset version this never re-reads the growing prefix —
     * every page costs the same no matter how deep the user has scrolled.
     */
    public static List<Item> loadPageAfter(Context context, Item after, int limit, String filter, int mediaTypes) {
        int safeLimit = Math.max(1, limit);
        boolean videosOnly = FILTER_VIDEOS.equals(filter) || mediaTypes == MEDIA_VIDEOS;
        boolean imagesOnly = mediaTypes == MEDIA_IMAGES;
        String bucketFilter = (filter != null && !FILTER_VIDEOS.equals(filter)) ? filter : null;

        List<Item> images = videosOnly ? new ArrayList<>() : queryImages(context, safeLimit, bucketFilter, after);
        List<Item> videos = imagesOnly ? new ArrayList<>() : queryVideos(context, safeLimit, bucketFilter, after);

        List<Item> merged = new ArrayList<>(images.size() + videos.size());
        merged.addAll(images);
        merged.addAll(videos);
        Collections.sort(merged, GRID_ORDER);
        return merged.size() <= safeLimit ? merged : new ArrayList<>(merged.subList(0, safeLimit));
    }

    /** sort key DESC (date taken / added), images before videos, then _id DESC — must match the SQL ORDER BY + keyset clauses below. */
    private static final java.util.Comparator<Item> GRID_ORDER = (a, b) -> {
        int c = Long.compare(b.sortKey, a.sortKey);
        if (c != 0) return c;
        if (a.isVideo != b.isVideo) return a.isVideo ? 1 : -1;
        return Long.compare(b.id, a.id);
    };

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
        return loadFolders(context, MEDIA_ALL);
    }

    /**
     * Single lightweight pass per table: only counts rows and remembers the
     * newest cover per bucket — no Item objects, no merge-sort of thousands of
     * rows (the old version materialised up to 8000 Items per table every time
     * the dropdown opened).
     */
    public static List<Folder> loadFolders(Context context, int mediaTypes) {
        Scan scan = new Scan();
        if (mediaTypes != MEDIA_VIDEOS) scanTable(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, scan);
        if (mediaTypes != MEDIA_IMAGES) scanTable(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, scan);

        List<Folder> result = new ArrayList<>();
        if (scan.total == 0) {
            // Even with nothing on-device yet, "More apps"/"See more" still work
            // (they hand off to system pickers, not this device's MediaStore).
            result.add(Folder.action(ACTION_MORE_APPS, context.getString(R.string.attach_folder_more_apps)));
            result.add(Folder.action(ACTION_SEE_MORE, context.getString(R.string.attach_folder_see_more)));
            return result;
        }

        // Recents = everything, cover = newest item overall.
        result.add(new Folder(FILTER_ALL, context.getString(R.string.attach_folder_recents),
                scan.newestUri, scan.newestIsVideo, scan.total));

        Map<String, BucketAgg> byBucket = scan.byBucket;

        // Exact bucket names only — a "contains" match picked "Camera360" /
        // "Downloads backup" style buckets for the wrong tile.
        BucketAgg camera = findBucketExact(byBucket, "camera");
        if (camera != null) result.add(camera.toFolder(context.getString(R.string.attach_folder_camera)));

        // Videos = synthetic "every video, any folder" entry, matching the
        // reference screenshot's dedicated Videos tile.
        if (mediaTypes == MEDIA_ALL && scan.videoTotal > 0 && scan.newestVideoUri != null) {
            result.add(new Folder(FILTER_VIDEOS, context.getString(R.string.attach_folder_videos),
                    scan.newestVideoUri, true, scan.videoTotal));
        }

        BucketAgg screenshots = findBucketExact(byBucket, "screenshots", "screenshot");
        if (screenshots != null) result.add(screenshots.toFolder(context.getString(R.string.attach_folder_screenshots)));

        BucketAgg downloads = findBucketExact(byBucket, "download", "downloads");
        if (downloads != null) result.add(downloads.toFolder(context.getString(R.string.attach_folder_downloads)));

        // WhatsApp media lives in split buckets ("WhatsApp Images", "WhatsApp
        // Video", "WhatsApp Animated Gifs", ...) — merge every bucket whose
        // name contains "whatsapp" into one tile, like the reference picker.
        Uri waCover = null;
        boolean waCoverIsVideo = false;
        long waCoverDate = Long.MIN_VALUE;
        int waCount = 0;
        for (Map.Entry<String, BucketAgg> e : byBucket.entrySet()) {
            if (!e.getKey().contains("whatsapp")) continue;
            BucketAgg sub = e.getValue();
            waCount += sub.count;
            if (waCover == null || sub.coverDateHint > waCoverDate) {
                waCover = sub.coverUri;
                waCoverIsVideo = sub.coverIsVideo;
                waCoverDate = sub.coverDateHint;
            }
        }
        if (waCover != null) {
            result.add(new Folder(FILTER_WHATSAPP, context.getString(R.string.attach_folder_whatsapp),
                    waCover, waCoverIsVideo, waCount));
        }

        // Static action rows — always last, exactly like the reference screenshot.
        result.add(Folder.action(ACTION_MORE_APPS, context.getString(R.string.attach_folder_more_apps)));
        result.add(Folder.action(ACTION_SEE_MORE, context.getString(R.string.attach_folder_see_more)));

        return result;
    }

    /** Accumulator for loadFolders(): totals + per-bucket count/newest cover. */
    private static final class Scan {
        final Map<String, BucketAgg> byBucket = new LinkedHashMap<>();
        int total, videoTotal;
        long newestDate = Long.MIN_VALUE, newestVideoDate = Long.MIN_VALUE;
        Uri newestUri, newestVideoUri;
        boolean newestIsVideo;
    }

    private static void scanTable(Context context, Uri tableUri, boolean isVideo, Scan scan) {
        String[] projection = {
                MediaStore.MediaColumns._ID,
                dateCol(),
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        };
        try (Cursor c = context.getContentResolver().query(tableUri, projection, null, null,
                dateCol() + " DESC")) {
            if (c == null) return;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int dateIdx = c.getColumnIndexOrThrow(dateCol());
            int bucketIdx = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);
            while (c.moveToNext()) {
                long date = c.getLong(dateIdx);
                scan.total++;
                boolean first = scan.newestUri == null;
                Uri uri = null; // built lazily — only rows that can become a cover need one
                if (first || date > scan.newestDate) {
                    uri = ContentUris.withAppendedId(tableUri, c.getLong(idIdx));
                    scan.newestDate = date; scan.newestUri = uri; scan.newestIsVideo = isVideo;
                }
                if (isVideo) {
                    scan.videoTotal++;
                    if (scan.newestVideoUri == null || date > scan.newestVideoDate) {
                        if (uri == null) uri = ContentUris.withAppendedId(tableUri, c.getLong(idIdx));
                        scan.newestVideoDate = date; scan.newestVideoUri = uri;
                    }
                }
                String name = bucketIdx >= 0 ? c.getString(bucketIdx) : null;
                if (name == null) continue;
                String trimmed = name.trim();
                if (trimmed.isEmpty()) continue;
                String key = trimmed.toLowerCase(Locale.US);
                BucketAgg agg = scan.byBucket.get(key);
                if (agg == null) {
                    if (uri == null) uri = ContentUris.withAppendedId(tableUri, c.getLong(idIdx));
                    agg = new BucketAgg(trimmed, uri, isVideo);
                    agg.coverDateHint = date;
                    scan.byBucket.put(key, agg);
                } else if (date > agg.coverDateHint) {
                    if (uri == null) uri = ContentUris.withAppendedId(tableUri, c.getLong(idIdx));
                    agg.coverUri = uri; agg.coverIsVideo = isVideo; agg.coverDateHint = date;
                }
                agg.count++;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "scanTable failed", e);
        }
    }

    private static BucketAgg findBucketExact(Map<String, BucketAgg> byBucket, String... names) {
        for (String n : names) {
            BucketAgg a = byBucket.get(n);
            if (a != null) return a;
        }
        return null;
    }

    /**
     * Builds the MediaStore selection for a bucket filter. Real folders are
     * matched EXACTLY on the bucket name (the filter key is the real
     * BUCKET_DISPLAY_NAME from loadFolders(), so no wildcard guessing);
     * the synthetic WhatsApp folder matches every "*whatsapp*" bucket.
     * The old code fed every key through LIKE '%key%' — "_" is a LIKE
     * wildcard so "__whatsapp__" never matched anything (empty grid), and
     * "Camera" also pulled in "Camera360".
     * @return {selection, arg}
     */
    private static String[] bucketSelection(String column, String bucketFilter) {
        if (FILTER_WHATSAPP.equals(bucketFilter)) {
            return new String[]{"LOWER(" + column + ") LIKE ?", "%whatsapp%"};
        }
        return new String[]{column + " = ?", bucketFilter};
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

    /**
     * Keyset clause for "rows strictly after {@code after}" in GRID_ORDER.
     * Images sort before videos on equal dates, so: images after a VIDEO need
     * date < d; videos after an IMAGE still include date == d.
     * @return {clause, args...} or null when after == null
     */
    private static String[] keysetClause(boolean forVideoTable, Item after) {
        if (after == null) return null;
        String d = Long.toString(after.sortKey);
        String date = dateCol();
        String idCol = MediaStore.MediaColumns._ID;
        // DATE_TAKEN can be NULL/0 on some rows: DESC puts them last, so they must stay
        // reachable AFTER every dated row (otherwise "date < ?" would silently drop them).
        String orNull = USE_TAKEN ? " OR " + date + " IS NULL" : "";
        boolean afterUndated = USE_TAKEN && after.sortKey <= 0;
        if (forVideoTable == after.isVideo) { // same table: classic (date, id) seek
            if (afterUndated) {
                return new String[]{"((" + date + " IS NULL OR " + date + " = 0) AND " + idCol + " < ?)",
                        Long.toString(after.id)};
            }
            return new String[]{"(" + date + " < ? OR (" + date + " = ? AND " + idCol + " < ?)" + orNull + ")",
                    d, d, Long.toString(after.id)};
        }
        if (forVideoTable) { // videos, last emitted was an image: same-date videos still pending
            if (afterUndated) return new String[]{"(" + date + " IS NULL OR " + date + " = 0)"};
            return new String[]{"(" + date + " <= ?" + orNull + ")", d};
        }
        // images, last emitted was a video: same-date images are already out
        if (afterUndated) return new String[]{"0 = 1"};
        return new String[]{"(" + date + " < ?" + orNull + ")", d};
    }

    /** ANDs a keyset clause onto an optional bucket selection. @return {selection, args...} */
    private static String[] withKeyset(String selection, String[] args, String[] ks) {
        String sel = selection == null ? ks[0] : "(" + selection + ") AND " + ks[0];
        int base = args == null ? 0 : args.length;
        String[] out = new String[1 + base + ks.length - 1];
        out[0] = sel;
        int n = 1;
        if (args != null) for (String a : args) out[n++] = a;
        for (int i = 1; i < ks.length; i++) out[n++] = ks[i];
        return out;
    }

    private static List<Item> queryImages(Context context, int limit, String bucketFilter) {
        return queryImages(context, limit, bucketFilter, null);
    }

    private static List<Item> queryImages(Context context, int limit, String bucketFilter, Item after) {
        Uri uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] projection = USE_TAKEN
                ? new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
                        dateCol(), MediaStore.Images.Media.BUCKET_DISPLAY_NAME}
                : new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
        String order = dateCol() + " DESC, " + MediaStore.Images.Media._ID + " DESC";

        String selection = null;
        String[] selectionArgs = null;
        if (bucketFilter != null) {
            String[] sel = bucketSelection(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, bucketFilter);
            selection = sel[0];
            selectionArgs = new String[]{sel[1]};
        }
        String[] ks = keysetClause(false, after);
        if (ks != null) {
            String[] r = withKeyset(selection, selectionArgs, ks);
            selection = r[0];
            selectionArgs = java.util.Arrays.copyOfRange(r, 1, r.length);
        }

        List<Item> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(uri, projection, selection, selectionArgs, order)) {
            if (c == null) return out;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
            int addedIdx = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
            int dateIdx = c.getColumnIndexOrThrow(dateCol());
            int bucketIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            while (c.moveToNext() && out.size() < limit) {
                long id = c.getLong(idIdx);
                long sortKey = c.getLong(dateIdx);
                long dateAdded = c.getLong(addedIdx);
                String bucket = bucketIdx >= 0 ? c.getString(bucketIdx) : null;
                out.add(new Item(ContentUris.withAppendedId(uri, id), false, 0L, sortKey, dateAdded, bucket));
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
        return queryVideos(context, limit, bucketFilter, null);
    }

    private static List<Item> queryVideos(Context context, int limit, String bucketFilter, Item after) {
        Uri uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = USE_TAKEN
                ? new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED, dateCol(),
                        MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_DISPLAY_NAME}
                : new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED,
                        MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
        String order = dateCol() + " DESC, " + MediaStore.Video.Media._ID + " DESC";

        String selection = null;
        String[] selectionArgs = null;
        if (bucketFilter != null) {
            String[] sel = bucketSelection(MediaStore.Video.Media.BUCKET_DISPLAY_NAME, bucketFilter);
            selection = sel[0];
            selectionArgs = new String[]{sel[1]};
        }
        String[] ks = keysetClause(true, after);
        if (ks != null) {
            String[] r = withKeyset(selection, selectionArgs, ks);
            selection = r[0];
            selectionArgs = java.util.Arrays.copyOfRange(r, 1, r.length);
        }

        List<Item> out = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(uri, projection, selection, selectionArgs, order)) {
            if (c == null) return out;
            int idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
            int addedIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
            int dateIdx = c.getColumnIndexOrThrow(dateCol());
            int durIdx = c.getColumnIndex(MediaStore.Video.Media.DURATION);
            int bucketIdx = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            while (c.moveToNext() && out.size() < limit) {
                long id = c.getLong(idIdx);
                long sortKey = c.getLong(dateIdx);
                long dateAdded = c.getLong(addedIdx);
                long duration = durIdx >= 0 ? c.getLong(durIdx) : 0L;
                String bucket = bucketIdx >= 0 ? c.getString(bucketIdx) : null;
                out.add(new Item(ContentUris.withAppendedId(uri, id), true, duration, sortKey, dateAdded, bucket));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "queryVideos failed", e);
        }
        return out;
    }
}
