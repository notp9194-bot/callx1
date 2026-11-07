package com.callx.app.viewer;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads the NEXT status's music clip while the current status is playing, so when the viewer
 * swipes/advances the song starts instantly from a local file instead of buffering from the network.
 *
 * Small + safe: audio only, size-capped, one background thread, old files pruned, every failure
 * silently ignored (playback just falls back to streaming the URL like before).
 */
final class StoryMusicPrefetcher {
    private static final long MAX_BYTES = 6L * 1024 * 1024;   // a status clip is short; never fetch long tracks
    private static final int KEEP_FILES = 10;
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, Boolean> INFLIGHT = new ConcurrentHashMap<>();

    private StoryMusicPrefetcher() {}

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "story_music_prefetch");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static File fileFor(Context c, String url) {
        return new File(dir(c), Integer.toHexString(url.hashCode()) + ".snd");
    }

    /** A fully downloaded local copy of this URL, or null. */
    static File cached(Context c, String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            File f = fileFor(c, url);
            return (f.exists() && f.length() > 0) ? f : null;
        } catch (Exception e) { return null; }
    }

    static void prefetch(final Context ctx, final String url) {
        if (ctx == null || url == null || !url.startsWith("http")) return;
        final Context app = ctx.getApplicationContext();
        if (cached(app, url) != null || INFLIGHT.putIfAbsent(url, Boolean.TRUE) != null) return;
        EXEC.execute(() -> {
            File tmp = null;
            try {
                File out = fileFor(app, url);
                tmp = new File(out.getPath() + ".part");
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(8000);
                long len = c.getContentLengthLong();
                if (len > MAX_BYTES) { c.disconnect(); return; }
                try (InputStream in = c.getInputStream(); FileOutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16 * 1024];
                    long total = 0; int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_BYTES) { tmp.delete(); return; }
                        os.write(buf, 0, n);
                    }
                }
                if (tmp.length() > 0) tmp.renameTo(out);
                prune(app);
            } catch (Exception ignored) {
                if (tmp != null) tmp.delete();
            } finally {
                INFLIGHT.remove(url);
            }
        });
    }

    private static void prune(Context c) {
        try {
            File[] fs = dir(c).listFiles();
            if (fs == null || fs.length <= KEEP_FILES) return;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            for (int i = 0; i < fs.length - KEEP_FILES; i++) fs[i].delete();
        } catch (Exception ignored) {}
    }
}
