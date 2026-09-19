package com.callx.app.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaCache — Local disk cache for audio, video, and file messages.
 *
 * How it works:
 *   1. URL ka MD5 hash bana ke filename banata hai
 *   2. App ke cache folder mein check karta hai — file hai to seedha return
 *   3. File nahi hai to background mein download karta hai, phir callback deta hai
 *
 * Result: Pehli baar sirf download hota hai. Baar baar kholo — zero data use.
 */
public class MediaCache {

    private static final String TAG       = "MediaCache";
    private static final String DIR_NAME  = "callx_media_cache";
    private static final long   MAX_CACHE = 200L * 1024 * 1024; // 200 MB limit

    private static final ExecutorService sPool =
            Executors.newFixedThreadPool(3);
    private static final Handler sMain =
            new Handler(Looper.getMainLooper());

    public interface Callback {
        void onReady(File file);
        void onError(String reason);
    }

    /** Used by the WhatsApp-style manual-download pill (image bubbles). */
    public interface ProgressCallback {
        void onProgress(int percent);
        /**
         * Optional: a coarse-to-sharp preview decoded from the file WHILE it
         * is still downloading (progressive-JPEG full images only — see
         * {@link CloudinaryUploader#deriveProgressiveFullUrl}). Fires zero or
         * more times, always before {@link #onReady}, each call sharper than
         * the last. Default no-op so every existing plain/video/audio/file
         * caller (which never gets this — see {@link #downloadWithProgress}
         * for exactly which path fires it) needs no changes.
         */
        default void onPartialBitmap(android.graphics.Bitmap partial) {}
        void onReady(File file);
        void onError(String reason);
    }

    public interface SizeCallback {
        void onSize(long bytes);
        void onError(String reason);
    }

    /** Advance #5 — callback for {@link #fetchEarlyPreview}. Always invoked
     *  on the main thread, and only ever called when a bitmap actually
     *  decoded (never fired with null — a failed/empty attempt just never
     *  calls back at all, so callers don't need a null check). */
    public interface EarlyPreviewCallback {
        void onPreview(android.graphics.Bitmap bitmap);
    }

    // Advance #5: how much of the progressive-JPEG full image to pull for
    // the early Range preview. A progressive JPEG's first scans (DC/low-
    // frequency AC) land in the first several KB of the file — enough for a
    // genuinely sharper-than-ThumbHash preview frame — long before the real
    // download (below) reaches its own first partial-decode milestone (see
    // downloadWithProgress's 20/45/70% ladder). Deliberately small: this is
    // a throwaway parallel fetch, not the real download.
    private static final int EARLY_PREVIEW_RANGE_BYTES = 28 * 1024;

    /**
     * Advance #5 — fires a tiny HTTP Range request (first
     * {@link #EARLY_PREVIEW_RANGE_BYTES} bytes only) against a
     * progressive-JPEG full-image URL (see
     * {@link CloudinaryUploader#deriveProgressiveFullUrl}) and, if enough
     * scan data landed inside that range to decode anything, hands back an
     * early sharp(er) preview — runs fully in parallel with, and
     * independently of, the real {@link #getWithProgress} download below;
     * the bytes read here are never written to the on-disk cache file, this
     * is a decode-and-discard preview read only.
     *
     * Best-effort by construction, matching the milestone partial-decode in
     * {@link #downloadWithProgress}: a CDN that ignores the Range header
     * (serves 200 with the full body — still fine, this just stops reading
     * once the cap is hit) or scan data that's truncated mid-frame and
     * fails to decode simply means the callback never fires; the caller's
     * existing ThumbHash placeholder / download-progress partials cover
     * that case exactly as before this method existed. Never call this for
     * a Media-E2E URL — same restriction as deriveProgressiveFullUrl
     * (ciphertext isn't decodable Cloudinary-side or client-side before the
     * full GCM tag verifies).
     */
    public static void fetchEarlyPreview(String url, EarlyPreviewCallback cb) {
        if (url == null || url.isEmpty() || cb == null) return;
        sPool.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestProperty("Range", "bytes=0-" + (EARLY_PREVIEW_RANGE_BYTES - 1));
                conn.setConnectTimeout(8_000);
                conn.setReadTimeout(8_000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                int code = conn.getResponseCode();
                // 206 = server honored Range (Cloudinary does); 200 = server
                // ignored it and is sending the whole file from the start —
                // either way just read up to the cap below and stop there.
                if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) return;
                byte[] buf = new byte[EARLY_PREVIEW_RANGE_BYTES];
                int total = 0;
                try (InputStream in = conn.getInputStream()) {
                    int n;
                    while (total < buf.length && (n = in.read(buf, total, buf.length - total)) != -1) {
                        total += n;
                    }
                }
                if (total <= 0) return;
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                opts.inSampleSize = 4; // cheap decode — transient preview, not the final render
                android.graphics.Bitmap partial =
                        android.graphics.BitmapFactory.decodeByteArray(buf, 0, total, opts);
                if (partial != null) {
                    sMain.post(() -> cb.onPreview(partial));
                }
            } catch (Exception ignored) {
                // Best-effort — see javadoc above. The real download's own
                // progress/partial/onReady path is completely unaffected.
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    // In-memory only — avoids a HEAD request every time a bubble rebinds
    // during scroll. Cleared naturally on process death.
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> sRemoteSizeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Fetches the remote file size (Content-Length) for the "139 kB"-style
     * label shown on the un-downloaded media pill, without downloading the
     * file itself. Cached in memory per URL.
     */
    public static void getRemoteSize(Context ctx, String url, SizeCallback cb) {
        if (url == null || url.isEmpty()) {
            if (cb != null) cb.onError("Invalid URL");
            return;
        }
        Long cached = sRemoteSizeCache.get(url);
        if (cached != null) {
            if (cb != null) cb.onSize(cached);
            return;
        }
        File already = getCached(ctx, url);
        if (already != null) {
            long len = already.length();
            sRemoteSizeCache.put(url, len);
            if (cb != null) cb.onSize(len);
            return;
        }
        sPool.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("HEAD");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);
                conn.setInstanceFollowRedirects(true);
                conn.connect();
                long len = conn.getContentLengthLong();
                if (len > 0) sRemoteSizeCache.put(url, len);
                final long finalLen = len;
                sMain.post(() -> {
                    if (cb == null) return;
                    if (finalLen > 0) cb.onSize(finalLen);
                    else cb.onError("Unknown size");
                });
            } catch (Exception e) {
                sMain.post(() -> { if (cb != null) cb.onError(e.getMessage()); });
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /**
     * Same as {@link #get} but reports live percentage while downloading —
     * powers the manual-download pill on received image bubbles. If already
     * cached, jumps straight to onReady (no progress callbacks).
     */
    public static void getWithProgress(Context ctx, String url, ProgressCallback cb) {
        getWithProgress(ctx, url, null, cb);
    }

    /** Media E2E (image) variant of {@link #getWithProgress(Context, String, ProgressCallback)} —
     *  see {@link #get(Context, String, byte[], Callback)} for what {@code decryptKey} does. */
    public static void getWithProgress(Context ctx, String url, byte[] decryptKey, ProgressCallback cb) {
        getWithProgress(ctx, url, decryptKey, null, cb);
    }

    /**
     * Media E2E variant that also verifies a WhatsApp-style ciphertext
     * SHA-256 digest (see MediaE2ECrypto.KeyEnvelope#fullDigest /
     * #thumbDigest) as the bytes come off the network, BEFORE the caller
     * ever sees the decrypted file. On a mismatch — a corrupted or tampered
     * download — this fails cleanly via {@code onError} instead of letting
     * the caller silently trust a bad file (GCM would eventually catch it
     * too, but only after fully decrypting; this catches it immediately
     * with a clear reason). Pass null for {@code expectedDigest} to skip
     * this check (e.g. a legacy v1 message that never carried one).
     */
    public static void getWithProgress(Context ctx, String url, byte[] decryptKey,
                                        byte[] expectedDigest, ProgressCallback cb) {
        getWithProgress(ctx, url, url, decryptKey, expectedDigest, cb);
    }

    /**
     * Same as {@link #getWithProgress(Context, String, byte[], byte[], ProgressCallback)}
     * but downloads from a DIFFERENT url ({@code fetchUrl}) than the one used
     * to key the on-disk cache / dedupe ({@code cacheKeyUrl}) — lets a caller
     * request a Cloudinary delivery-transform variant (e.g.
     * {@link CloudinaryUploader#deriveProgressiveFullUrl}) for the actual
     * network bytes while every other lookup in the app (MediaCache.getCached,
     * the in-memory bitmap pool, downloadingMediaUrls dedupe, the
     * tap-to-view/MediaViewerActivity intent, etc.) keeps using the message's
     * real, stable {@code mediaUrl} as the key — so switching the delivery
     * transform never causes a cache miss/re-download loop against code that
     * still looks things up by the original URL. Pass {@code cacheKeyUrl}
     * for both if there's no separate delivery variant (that's what every
     * other overload above does).
     */
    public static void getWithProgress(Context ctx, String cacheKeyUrl, String fetchUrl,
                                        byte[] decryptKey, byte[] expectedDigest, ProgressCallback cb) {
        if (ctx == null || cacheKeyUrl == null || cacheKeyUrl.isEmpty()) {
            if (cb != null) cb.onError("Invalid URL");
            return;
        }
        File cached = cacheFileFor(ctx, cacheKeyUrl);
        if (cached != null && cached.exists() && cached.length() > 0) {
            if (cb != null) cb.onReady(cached);
            return;
        }
        String actualFetchUrl = (fetchUrl == null || fetchUrl.isEmpty()) ? cacheKeyUrl : fetchUrl;
        sPool.execute(() -> {
            File result = downloadWithProgress(ctx, cacheKeyUrl, actualFetchUrl, decryptKey, expectedDigest,
                    new ProgressTick() {
                @Override public void onTick(int percent) {
                    if (cb != null) sMain.post(() -> cb.onProgress(percent));
                }
                @Override public void onPartial(android.graphics.Bitmap partial) {
                    if (cb != null) sMain.post(() -> cb.onPartialBitmap(partial));
                }
            });
            if (result != null && result.exists()) {
                sMain.post(() -> { if (cb != null) cb.onReady(result); });
            } else {
                sMain.post(() -> { if (cb != null) cb.onError("Download failed or storage issue"); });
            }
        });
    }

    private interface ProgressTick {
        void onTick(int percent);
        /** Default no-op — only the plaintext image path (see
         *  {@link #downloadWithProgress}) ever calls this. */
        default void onPartial(android.graphics.Bitmap partial) {}
    }

    // PERF: ~30fps ceiling on progress ticks. The percent-changed dedupe
    // below already caps this at ~100 calls per download total, but on a
    // fast LAN/wifi connection all 100 of those percent jumps can land
    // within a couple hundred ms — a burst of onProgress() calls (each one
    // a sMain.post() + a canvas invalidate()) far above the display's real
    // refresh rate, which the UI can never actually show anyway. Gating
    // ticks to one every ~33ms throttles that burst to something the
    // screen can actually render, with zero visible difference (the eye
    // can't tell 30 progress updates/sec from 300). percent == 100 (or the
    // dedupe's implicit "final tick") is never throttled — completion must
    // always land immediately so onReady()'s swap-in isn't preceded by a
    // stale percentage frozen mid-throttle-window.
    private static final long PROGRESS_TICK_MIN_INTERVAL_MS = 33L;

    /**
     * Sabse pehle local cache check karta hai.
     * Agar file exist karti hai — turant callback (zero network).
     * Agar nahi hai — background mein download karke callback deta hai.
     */
    public static void get(Context ctx, String url, Callback cb) {
        get(ctx, url, null, cb);
    }

    /**
     * Media E2E (image): same as {@link #get(Context, String, Callback)},
     * but when {@code decryptKey} is non-null the downloaded bytes are
     * streamed through {@link MediaE2ECrypto#decryptStream} before ever
     * touching disk — the cache file on disk holds only the decrypted
     * plaintext image, the ciphertext only ever exists transiently as it
     * comes off the network socket. Everything downstream (Glide loading
     * from the returned File, the bitmap pool, etc.) needs no changes at
     * all, since it just sees a normal decoded-image file either way.
     * Pass null for every non-encrypted message (audio/video/file/gif/
     * sticker, or an image sent before this feature existed) — unchanged
     * behavior.
     */
    public static void get(Context ctx, String url, byte[] decryptKey, Callback cb) {
        get(ctx, url, decryptKey, null, cb);
    }

    /** Digest-verifying variant of {@link #get(Context, String, byte[], Callback)} —
     *  see {@link #getWithProgress(Context, String, byte[], byte[], ProgressCallback)}
     *  for what {@code expectedDigest} does. Pass null to skip verification. */
    public static void get(Context ctx, String url, byte[] decryptKey, byte[] expectedDigest, Callback cb) {
        if (ctx == null || url == null || url.isEmpty()) {
            if (cb != null) cb.onError("Invalid URL");
            return;
        }

        File cached = cacheFileFor(ctx, url);
        if (cached != null && cached.exists() && cached.length() > 0) {
            Log.d(TAG, "Cache HIT: " + cached.getName() + " (" + cached.length() + " bytes)");
            if (cb != null) cb.onReady(cached);
            return;
        }

        Log.d(TAG, "Cache MISS — downloading: " + url);
        sPool.execute(() -> {
            File result = download(ctx, url, decryptKey, expectedDigest);
            if (result != null && result.exists()) {
                Log.d(TAG, "Download succeeded, file: " + result.getAbsolutePath());
                sMain.post(() -> {
                    if (cb != null) cb.onReady(result);
                });
            } else {
                Log.e(TAG, "Download failed or file not created");
                sMain.post(() -> {
                    if (cb != null) cb.onError("Download failed or storage issue");
                });
            }
        });
    }

    /**
     * Sirf check karo — download mat karo.
     * Returns null agar cached nahi hai.
     */
    public static File getCached(Context ctx, String url) {
        if (ctx == null || url == null || url.isEmpty()) return null;
        File f = cacheFileFor(ctx, url);
        return (f != null && f.exists() && f.length() > 0) ? f : null;
    }

    /**
     * Deletes the cached copy of {@code url} (plus any half-written .tmp).
     * Used when a "cached" file turns out to be undecodable — truncated, or
     * ciphertext that an old key-less prefetch saved as if it were the image —
     * so it stops passing {@link #getCached}'s "is it downloaded?" test (which
     * made the chat bubble show it as downloaded and the viewer open to a
     * black screen forever). After this the media simply reads as "not
     * downloaded" again and can be re-fetched properly.
     */
    public static boolean invalidate(Context ctx, String url) {
        if (ctx == null || url == null || url.isEmpty()) return false;
        File f = cacheFileFor(ctx, url);
        if (f == null) return false;
        boolean deleted = f.exists() && f.delete();
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        if (tmp.exists()) tmp.delete();
        sRemoteSizeCache.remove(url);
        Log.w(TAG, "invalidate(): dropped undecodable cache entry " + f.getName() + " deleted=" + deleted);
        return deleted;
    }

    /**
     * Seeds the cache with a LOCAL file for a given remote URL — used right
     * after a successful upload so the sender can immediately self-play
     * their own just-sent media without re-downloading it (and, for
     * E2E-encrypted media, without needing to decrypt it — the sender
     * already has the plaintext locally, and {@link #get} intentionally
     * never derives the decrypt key for the sender's own outgoing message).
     * No-op (silently) on any failure — self-playback just falls back to
     * the normal decrypt/download path if seeding didn't happen.
     */
    public static void put(Context ctx, String url, android.net.Uri sourceUri) {
        if (ctx == null || url == null || url.isEmpty() || sourceUri == null) return;
        sPool.execute(() -> {
            File tmp = null;
            try {
                evictIfNeeded(ctx);
                File out = cacheFileFor(ctx, url);
                if (out == null) return;
                tmp = new File(out.getParentFile(), out.getName() + ".seed.tmp");
                try (InputStream in = ctx.getContentResolver().openInputStream(sourceUri);
                     FileOutputStream fos = new FileOutputStream(tmp)) {
                    if (in == null) return;
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                    try { fos.getFD().sync(); } catch (Exception ignored) {}
                }
                if (tmp.exists() && tmp.length() > 0) {
                    if (out.exists()) out.delete();
                    if (!tmp.renameTo(out)) tmp.delete();
                }
            } catch (Exception e) {
                Log.w(TAG, "put() seed-cache failed for " + url + ": " + e.getMessage());
            } finally {
                if (tmp != null && tmp.exists()) tmp.delete();
            }
        });
    }

    /**
     * Cache size check (bytes mein).
     */
    public static long getCacheSizeBytes(Context ctx) {
        File dir = cacheDir(ctx);
        if (dir == null) {
            Log.w(TAG, "getCacheSizeBytes: cache dir is null");
            return 0;
        }
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            Log.d(TAG, "getCacheSizeBytes: " + files.length + " files in cache");
            for (File f : files) {
                total += f.length();
                Log.d(TAG, "  - " + f.getName() + ": " + f.length() + " bytes");
            }
        } else {
            Log.w(TAG, "getCacheSizeBytes: listFiles returned null");
        }
        Log.d(TAG, "getCacheSizeBytes TOTAL: " + total + " bytes (" + (total / 1024 / 1024) + " MB)");
        return total;
    }

    /**
     * Poora media cache saaf karo.
     */
    public static void clearAll(Context ctx) {
        sPool.execute(() -> {
            File dir = cacheDir(ctx);
            if (dir == null) return;
            File[] files = dir.listFiles();
            if (files != null) for (File f : files) f.delete();
            Log.d(TAG, "Media cache cleared");
        });
    }

    private static File download(Context ctx, String urlStr) {
        return download(ctx, urlStr, null, null);
    }

    /** @param decryptKey non-null for a Media-E2E image — bytes are decrypted
     *  (see {@link MediaE2ECrypto#decryptStream}) as they're written, so the
     *  cache file on disk ends up holding plaintext, never ciphertext.
     *  @param expectedDigest optional WhatsApp-style ciphertext SHA-256 (see
     *  MediaE2ECrypto.KeyEnvelope) — when non-null, the raw bytes read off
     *  the network are hashed as they stream through decryptStream, and
     *  compared against this once the stream ends; a mismatch is treated as
     *  a failed download (deletes the partial file, returns null) rather
     *  than silently caching a corrupted/tampered result. */
    private static File download(Context ctx, String urlStr, byte[] decryptKey, byte[] expectedDigest) {
        HttpURLConnection conn = null;
        try {
            evictIfNeeded(ctx);

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + code + " for " + urlStr);
                return null;
            }

            File out = cacheFileFor(ctx, urlStr);
            if (out == null) {
                Log.e(TAG, "Could not determine cache file path");
                return null;
            }

            // Clear existing partial file
            if (out.exists()) out.delete();

            File tmpDir = out.getParentFile();
            if (tmpDir == null || !tmpDir.exists()) {
                Log.e(TAG, "Cache directory does not exist: " + tmpDir);
                return null;
            }

            File tmp = new File(tmpDir, out.getName() + ".tmp");
            if (tmp.exists()) tmp.delete();

            java.security.DigestInputStream digestIn = null;
            try (InputStream rawIn = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                InputStream in = rawIn;
                if (decryptKey != null && expectedDigest != null) {
                    // Hash the CIPHERTEXT bytes as they're read for decryption
                    // (not the plaintext being written out) — that's what the
                    // sender's digest in the envelope was computed over.
                    digestIn = new java.security.DigestInputStream(rawIn, MessageDigest.getInstance("SHA-256"));
                    in = digestIn;
                }
                if (decryptKey != null) {
                    MediaE2ECrypto.decryptStream(in, fos, decryptKey);
                } else {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                }
                fos.flush();
                try { fos.getFD().sync(); } catch (Exception ignored) {}
            }

            if (digestIn != null) {
                byte[] actual = digestIn.getMessageDigest().digest();
                if (!MediaE2ECrypto.digestsEqual(actual, expectedDigest)) {
                    Log.w(TAG, "Digest mismatch for " + urlStr + " — corrupted or tampered download, discarding");
                    if (tmp.exists()) tmp.delete();
                    return null;
                }
            }

            if (tmp.exists() && tmp.length() > 0) {
                if (tmp.renameTo(out)) {
                    Log.d(TAG, "Cached: " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
                    return out;
                } else {
                    Log.w(TAG, "Failed to rename temp to final: " + tmp.getAbsolutePath() + " → " + out.getAbsolutePath());
                    if (tmp.exists()) tmp.delete();
                    return null;
                }
            } else {
                Log.w(TAG, "Temp file empty or not created: " + tmp.getAbsolutePath());
                if (tmp.exists()) tmp.delete();
            }
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Download error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static File downloadWithProgress(Context ctx, String cacheKeyUrl, String fetchUrl, ProgressTick tick) {
        return downloadWithProgress(ctx, cacheKeyUrl, fetchUrl, null, null, tick);
    }

    private static File downloadWithProgress(Context ctx, String cacheKeyUrl, String fetchUrl,
                                              byte[] decryptKey, ProgressTick tick) {
        return downloadWithProgress(ctx, cacheKeyUrl, fetchUrl, decryptKey, null, tick);
    }

    /** @param cacheKeyUrl determines the on-disk cache filename (via
     *  {@link #cacheFileFor}) — always the message's real, stable URL.
     *  @param fetchUrl the URL actually opened over the network — normally
     *  the same as {@code cacheKeyUrl}, but callers can pass a Cloudinary
     *  delivery-transform variant instead (see the
     *  {@link #getWithProgress(Context, String, String, byte[], byte[], ProgressCallback)}
     *  overload's doc).
     *  @param expectedDigest see {@link #download(Context, String, byte[], byte[])} —
     *  same WhatsApp-style ciphertext SHA-256 check, applied here too. */
    private static File downloadWithProgress(Context ctx, String cacheKeyUrl, String fetchUrl, byte[] decryptKey,
                                              byte[] expectedDigest, ProgressTick tick) {
        HttpURLConnection conn = null;
        try {
            evictIfNeeded(ctx);

            URL url = new URL(fetchUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setInstanceFollowRedirects(true);
            conn.connect();

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + code + " for " + fetchUrl);
                return null;
            }

            long total = conn.getContentLengthLong(); // -1 if unknown
            if (total > 0) sRemoteSizeCache.put(cacheKeyUrl, total);

            File out = cacheFileFor(ctx, cacheKeyUrl);
            if (out == null) return null;
            if (out.exists()) out.delete();

            File tmpDir = out.getParentFile();
            if (tmpDir == null || !tmpDir.exists()) return null;

            File tmp = new File(tmpDir, out.getName() + ".tmp");
            if (tmp.exists()) tmp.delete();

            java.security.DigestInputStream digestIn = null;
            try (InputStream rawIn = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                if (decryptKey != null) {
                    // Hash the CIPHERTEXT bytes as they're read for decryption
                    // when an expected digest was supplied — same file-hash
                    // check as the plain download() path, just layered under
                    // the progress-counting wrapper below.
                    InputStream hashSource = rawIn;
                    if (expectedDigest != null) {
                        digestIn = new java.security.DigestInputStream(rawIn, MessageDigest.getInstance("SHA-256"));
                        hashSource = digestIn;
                    }
                    // Progress is tracked off ciphertext bytes read (the chunk
                    // length/tag framing overhead is negligible — a few dozen
                    // bytes per 64KB — so this tracks real download progress
                    // closely enough for the UI's percentage pill).
                    long[] downloadedHolder = {0};
                    int[] lastPercentHolder = {-1};
                    long[] lastTickAtHolder = {0L};
                    InputStream counting = new java.io.FilterInputStream(hashSource) {
                        @Override public int read(byte[] b, int off, int len) throws IOException {
                            int n = super.read(b, off, len);
                            if (n > 0) {
                                downloadedHolder[0] += n;
                                if (total > 0 && tick != null) {
                                    int percent = (int) Math.min(99, (downloadedHolder[0] * 100) / total);
                                    if (percent != lastPercentHolder[0]) {
                                        long now = android.os.SystemClock.elapsedRealtime();
                                        // 99 is the last tick this loop ever reports (100 comes
                                        // from the caller after the stream closes) — never throttle it.
                                        boolean isFinal = percent >= 99;
                                        if (isFinal || now - lastTickAtHolder[0] >= PROGRESS_TICK_MIN_INTERVAL_MS) {
                                            lastPercentHolder[0] = percent;
                                            lastTickAtHolder[0] = now;
                                            tick.onTick(percent);
                                        }
                                    }
                                }
                            }
                            return n;
                        }
                    };
                    MediaE2ECrypto.decryptStream(counting, fos, decryptKey);
                } else {
                    long downloaded = 0;
                    int lastPercent = -1;
                    long lastTickAt = 0L;
                    // Progressive-JPEG partial-decode preview: only reachable
                    // on this plaintext (non-E2E) path — an E2E download runs
                    // through the decryptKey branch above instead, and never
                    // gets partial previews (see deriveProgressiveFullUrl's
                    // doc: partially-decrypted ciphertext can't be trusted or
                    // even correctly decoded before the GCM auth tag at the
                    // very end verifies — this is a security boundary, not
                    // just a missing feature). A handful of fixed percentage
                    // milestones is enough to feel "progressively sharpening"
                    // without spending a decode on every single progress
                    // tick; each attempt is wrapped in try/catch because a
                    // progressive JPEG cut off mid-scan is a normal, expected
                    // failure mode (not every milestone will successfully
                    // decode, especially the earliest one) — just skip that
                    // preview and let the next milestone (or the final
                    // onReady decode) take over.
                    int nextPartialIdx = 0;
                    final int[] partialMilestones = {20, 45, 70};
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = rawIn.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                        downloaded += n;
                        if (total > 0 && tick != null) {
                            int percent = (int) Math.min(100, (downloaded * 100) / total);
                            if (percent != lastPercent) {
                                long now = android.os.SystemClock.elapsedRealtime();
                                boolean isFinal = percent >= 100;
                                if (isFinal || now - lastTickAt >= PROGRESS_TICK_MIN_INTERVAL_MS) {
                                    lastPercent = percent;
                                    lastTickAt = now;
                                    tick.onTick(percent);
                                }
                                if (nextPartialIdx < partialMilestones.length
                                        && percent >= partialMilestones[nextPartialIdx]) {
                                    nextPartialIdx++;
                                    try {
                                        fos.flush();
                                        android.graphics.BitmapFactory.Options opts =
                                                new android.graphics.BitmapFactory.Options();
                                        opts.inSampleSize = 4; // cheap decode — transient preview, not the final render
                                        android.graphics.Bitmap partial =
                                                android.graphics.BitmapFactory.decodeFile(tmp.getAbsolutePath(), opts);
                                        if (partial != null) tick.onPartial(partial);
                                    } catch (Exception ignored) {
                                        // Truncated progressive JPEG mid-scan — expected, just skip this milestone
                                    }
                                }
                            }
                        }
                    }
                }
                fos.flush();
                try { fos.getFD().sync(); } catch (Exception ignored) {}
            }

            if (digestIn != null) {
                byte[] actual = digestIn.getMessageDigest().digest();
                if (!MediaE2ECrypto.digestsEqual(actual, expectedDigest)) {
                    Log.w(TAG, "Digest mismatch for " + cacheKeyUrl + " — corrupted or tampered download, discarding");
                    if (tmp.exists()) tmp.delete();
                    return null;
                }
            }

            if (tmp.exists() && tmp.length() > 0) {
                if (tmp.renameTo(out)) {
                    if (tick != null) tick.onTick(100);
                    return out;
                } else {
                    if (tmp.exists()) tmp.delete();
                    return null;
                }
            }
            if (tmp.exists()) tmp.delete();
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Download (progress) error: " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static File cacheFileFor(Context ctx, String url) {
        File dir = cacheDir(ctx);
        if (dir == null) return null;
        String ext = extensionFor(url);
        return new File(dir, md5(url) + ext);
    }

    private static File cacheDir(Context ctx) {
        try {
            File appCache = ctx.getCacheDir();
            Log.d(TAG, "App cache dir: " + appCache.getAbsolutePath() + " | exists=" + appCache.exists() + " | writable=" + appCache.canWrite());
            
            File dir = new File(appCache, DIR_NAME);
            Log.d(TAG, "MediaCache dir: " + dir.getAbsolutePath() + " | exists=" + dir.exists());
            
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                Log.d(TAG, "Cache dir creation result: " + created + " for " + dir.getAbsolutePath());
                if (!created) {
                    Log.e(TAG, "Failed to create cache directory!");
                    return null;
                }
            }
            
            boolean isDir = dir.isDirectory();
            boolean canWrite = dir.canWrite();
            Log.d(TAG, "Cache dir final: isDir=" + isDir + " | canWrite=" + canWrite);
            
            return isDir ? dir : null;
        } catch (Exception e) {
            Log.e(TAG, "Cache dir error: " + e.getMessage(), e);
            return null;
        }
    }

    private static String extensionFor(String url) {
        try {
            String path = new URL(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot >= 0 && dot < path.length() - 1) {
                String ext = path.substring(dot).toLowerCase();
                if (ext.length() <= 5) return ext;
            }
        } catch (Exception ignored) {}
        return ".bin";
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private static void evictIfNeeded(Context ctx) {
        if (getCacheSizeBytes(ctx) < MAX_CACHE) return;
        File dir = cacheDir(ctx);
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long freed = 0;
        long target = MAX_CACHE / 4;
        for (File f : files) {
            freed += f.length();
            f.delete();
            if (freed >= target) break;
        }
        Log.d(TAG, "Evicted " + freed / 1024 + " KB from media cache");
    }
}
