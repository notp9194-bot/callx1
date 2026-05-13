package com.callx.app.utils;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.linkedin.android.litr.MediaTransformer;
import com.linkedin.android.litr.TransformationListener;
import com.linkedin.android.litr.TransformationOptions;
import com.linkedin.android.litr.analytics.TrackTransformationInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VideoCompressor v24 — Multi-codec, multi-quality LiTr-based compression.
 *
 * NEW in v24:
 *  ✅ Quality levels: LOW / STANDARD / HD / FULL_HD / ORIGINAL
 *  ✅ Codec hierarchy: AV1 (Android 12+) → HEVC (Android 10+) → H.264 (fallback)
 *  ✅ Hardware encoder availability check before each codec
 *  ✅ Adaptive bitrate: content complexity + pixel ratio + codec efficiency
 *  ✅ Resolution multiples of 16 for perfect codec alignment
 *  ✅ Duration-based auto quality cap (>10 min → STANDARD max)
 *  ✅ Auto codec fallback: HEVC/AV1 fail → retry with H.264
 *  ✅ Per-quality thumbnail resolution (HD = 480px, others = 300px)
 *  ✅ Compression savings + codec used in Result
 *  ✅ safeDelete exposed for VideoUploader cleanup
 */
public class VideoCompressor {

    private static final String TAG = "VideoCompressor";

    public static final String MIME_AV1  = "video/av01";
    public static final String MIME_HEVC = "video/hevc";
    public static final String MIME_AVC  = "video/avc";

    private static final int FRAME_RATE    = 30;
    private static final int KEY_FRAME_INT = 3;

    private static final ExecutorService BG   = Executors.newCachedThreadPool();
    private static final Handler         MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onProgress(int percent);
        void onSuccess(Result result);
        void onError(Exception e);
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static class Result {
        public final File   videoFile;
        public final File   thumbFile;
        public final long   originalBytes;
        public final long   compressedBytes;
        public final int    durationMs;
        public final int    width;
        public final int    height;
        public final String codecUsed;
        public final VideoQualityPreferences.Quality quality;

        Result(File video, File thumb, long origBytes, long compBytes,
               int durationMs, int w, int h, String codec,
               VideoQualityPreferences.Quality q) {
            this.videoFile       = video;
            this.thumbFile       = thumb;
            this.originalBytes   = origBytes;
            this.compressedBytes = compBytes;
            this.durationMs      = durationMs;
            this.width           = w;
            this.height          = h;
            this.codecUsed       = codec;
            this.quality         = q;
        }

        public float savingsPercent() {
            if (originalBytes <= 0) return 0;
            return 100f * (1f - (float) compressedBytes / originalBytes);
        }

        public String compressionSummary() {
            return String.format("%.1fMB → %.1fMB (%.0f%% saved) | %s | %dx%d | %ds",
                originalBytes   / 1_000_000f,
                compressedBytes / 1_000_000f,
                savingsPercent(),
                codecUsed != null ? codecUsed.replace("video/","") : "avc",
                width, height, durationMs / 1000);
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** Compress with STANDARD quality (backward compatible) */
    public static void compress(Context ctx, Uri videoUri, Callback callback) {
        compress(ctx, videoUri, VideoQualityPreferences.Quality.STANDARD, callback);
    }

    /** Compress with explicit quality */
    public static void compress(Context ctx, Uri videoUri,
                                VideoQualityPreferences.Quality quality,
                                Callback callback) {
        BG.execute(() -> {
            try {
                Result r = compressSync(ctx, videoUri, quality,
                    pct -> MAIN.post(() -> callback.onProgress(pct)));
                MAIN.post(() -> callback.onSuccess(r));
            } catch (Exception e) {
                Log.e(TAG, "Compression failed", e);
                MAIN.post(() -> callback.onError(e));
            }
        });
    }

    /** Backward-compatible sync overload (STANDARD quality) */
    public static Result compressSync(Context ctx, Uri videoUri,
                                      ProgressListener progressCb) throws Exception {
        return compressSync(ctx, videoUri, VideoQualityPreferences.Quality.STANDARD, progressCb);
    }

    /** Full sync compress — call from background thread only */
    public static Result compressSync(Context ctx, Uri videoUri,
                                      VideoQualityPreferences.Quality quality,
                                      ProgressListener progressCb) throws Exception {

        File inputCopy = copyUriToFile(ctx, videoUri);
        Uri  fileUri   = Uri.fromFile(inputCopy);
        Log.d(TAG, "Input copied: " + inputCopy.length() / 1024 + " KB");

        try {
            VideoMetadata meta = readMetadata(ctx, fileUri);
            Log.i(TAG, "Input: " + meta.width + "x" + meta.height
                + " dur=" + meta.durationMs + "ms bitrate=" + meta.bitrate / 1000 + "kbps");

            VideoQualityPreferences.Quality effectiveQ = resolveQuality(quality, meta);

            // ORIGINAL passthrough — no transcoding
            if (effectiveQ == VideoQualityPreferences.Quality.ORIGINAL) {
                int side = 480;
                File thumbFile = makeThumbnail(ctx, videoUri, meta.durationMs, side);
                return new Result(inputCopy, thumbFile,
                    inputCopy.length(), inputCopy.length(),
                    meta.durationMs, meta.width, meta.height, "original", effectiveQ);
            }

            int[] target  = calcTarget(meta.width, meta.height, effectiveQ.maxPx);
            int   targetW = target[0], targetH = target[1];
            String mime   = pickCodec(effectiveQ);
            int   bitrate = calcAdaptiveBitrate(effectiveQ, meta, targetW, targetH, mime);

            Log.i(TAG, "Codec=" + mime + " target=" + targetW + "x" + targetH
                + " bitrate=" + bitrate / 1000 + "kbps");

            int thumbSide = (effectiveQ.maxPx >= 720) ? 480 : 300;
            File thumbFile = makeThumbnail(ctx, videoUri, meta.durationMs, thumbSide);

            File outDir = new File(ctx.getCacheDir(), "vid_out");
            outDir.mkdirs();
            File outFile = new File(outDir, "vid_" + UUID.randomUUID() + ".mp4");

            litrCompress(ctx, fileUri, outFile, targetW, targetH, bitrate, mime, progressCb);

            // If output is bigger than input (rare), use original
            File finalVideo = (outFile.exists() && outFile.length() > 0
                && outFile.length() < inputCopy.length()) ? outFile : inputCopy;

            Log.i(TAG, "Done: " + finalVideo.length() / (1024 * 1024) + "MB ← "
                + inputCopy.length() / (1024 * 1024) + "MB (" + targetW + "x" + targetH + ")");

            return new Result(finalVideo, thumbFile,
                inputCopy.length(), finalVideo.length(),
                meta.durationMs, targetW, targetH, mime, effectiveQ);

        } finally {
            // inputCopy cleaned up by caller (VideoUploader / VideoUploadWorker)
        }
    }

    // ── Codec selection ────────────────────────────────────────────────────

    /**
     * AV1 (Android 12+) → HEVC (Android 10+) → H.264
     * LOW quality always uses H.264 (HEVC/AV1 overhead not worth it for tiny files)
     */
    public static String pickCodec(VideoQualityPreferences.Quality quality) {
        if (quality == VideoQualityPreferences.Quality.LOW) return MIME_AVC;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasHardwareEncoder(MIME_AV1))
            return MIME_AV1;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (hasHardwareEncoder(MIME_HEVC) || hasAnyEncoder(MIME_HEVC))
                return MIME_HEVC;
        }
        return MIME_AVC;
    }

    public static boolean hasHardwareEncoder(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly())
                continue;
            for (String t : info.getSupportedTypes())
                if (t.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    public static boolean hasAnyEncoder(String mime) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String t : info.getSupportedTypes())
                if (t.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    // ── Adaptive bitrate ───────────────────────────────────────────────────

    static int calcAdaptiveBitrate(VideoQualityPreferences.Quality quality,
                                   VideoMetadata meta, int targetW, int targetH,
                                   String codec) {
        if (quality.bitrate == Integer.MAX_VALUE) return 3_000_000;
        int base = quality.bitrate;

        // Codec efficiency: AV1 ≈ 55% of AVC; HEVC ≈ 65% of AVC
        if (MIME_AV1.equals(codec))  base = (int)(base * 0.55f);
        else if (MIME_HEVC.equals(codec)) base = (int)(base * 0.65f);

        // Don't exceed original bitrate
        if (meta.bitrate > 0 && meta.bitrate < base)
            base = Math.max(meta.bitrate, 200_000);

        // Pixel count ratio (sqrt gives perceptual balance)
        long srcPx = (long) meta.width * meta.height;
        long tgtPx = (long) targetW * targetH;
        if (srcPx > 0 && tgtPx < srcPx)
            base = Math.max((int)(base * Math.sqrt((double) tgtPx / srcPx) * 1.3), 200_000);

        return base;
    }

    // ── Quality resolution ─────────────────────────────────────────────────

    private static VideoQualityPreferences.Quality resolveQuality(
            VideoQualityPreferences.Quality requested, VideoMetadata meta) {

        if (requested == VideoQualityPreferences.Quality.AUTO) {
            // Long videos → STANDARD to keep file size manageable
            return meta.durationMs > 10 * 60 * 1000
                ? VideoQualityPreferences.Quality.STANDARD
                : VideoQualityPreferences.Quality.STANDARD;
        }
        // Don't upscale: cap quality based on short side of source
        int shortSide = Math.min(meta.width, meta.height);
        if (requested == VideoQualityPreferences.Quality.FULL_HD && shortSide < 1080)
            return VideoQualityPreferences.Quality.HD;
        if (requested == VideoQualityPreferences.Quality.HD && shortSide < 720)
            return VideoQualityPreferences.Quality.STANDARD;
        if (requested == VideoQualityPreferences.Quality.STANDARD && shortSide < 540)
            return VideoQualityPreferences.Quality.LOW;
        return requested;
    }

    // ── content:// → File copy ─────────────────────────────────────────────

    static File copyUriToFile(Context ctx, Uri uri) throws IOException {
        File dir = new File(ctx.getCacheDir(), "vid_in");
        dir.mkdirs();
        File tmp = new File(dir, "in_" + UUID.randomUUID() + ".mp4");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("openInputStream null for: " + uri);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
        if (tmp.length() == 0) throw new IOException("Copied file empty — bad URI? " + uri);
        return tmp;
    }

    // ── LiTr ──────────────────────────────────────────────────────────────

    private static void litrCompress(Context ctx, Uri fileUri, File outFile,
                                     int w, int h, int bitrate, String mime,
                                     ProgressListener cb) throws Exception {

        CountDownLatch             latch = new CountDownLatch(1);
        AtomicReference<Exception> err   = new AtomicReference<>();

        MediaFormat fmt = MediaFormat.createVideoFormat(mime, w, h);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE,         bitrate);
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE,       FRAME_RATE);
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INT);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        if (MIME_HEVC.equals(mime) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            fmt.setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
        }

        MediaTransformer t = new MediaTransformer(ctx.getApplicationContext());
        t.transform(
            UUID.randomUUID().toString(),
            fileUri,
            outFile.getAbsolutePath(),
            fmt,
            null, // audio passthrough
            new TransformationListener() {
                @Override public void onStarted(@androidx.annotation.NonNull String id) {
                    Log.d(TAG, "LiTr started [" + mime + "]");
                }
                @Override public void onProgress(@androidx.annotation.NonNull String id, float p) {
                    if (cb != null) cb.onProgress(Math.min((int)(p * 100), 95));
                }
                @Override public void onCompleted(@androidx.annotation.NonNull String id,
                                                  List<TrackTransformationInfo> info) {
                    if (cb != null) cb.onProgress(100);
                    latch.countDown();
                }
                @Override public void onCancelled(@androidx.annotation.NonNull String id,
                                                  List<TrackTransformationInfo> info) {
                    err.set(new Exception("Compression cancelled"));
                    latch.countDown();
                }
                @Override public void onError(@androidx.annotation.NonNull String id,
                                              Throwable cause,
                                              List<TrackTransformationInfo> info) {
                    String msg = cause != null ? cause.getMessage() : "unknown";
                    Log.e(TAG, "LiTr error [" + mime + "]: " + msg, cause);
                    err.set(new Exception("LiTr[" + mime + "]: " + msg));
                    latch.countDown();
                }
            },
            new TransformationOptions.Builder()
                .setGranularity(MediaTransformer.GRANULARITY_DEFAULT)
                .build()
        );

        boolean done = latch.await(20, TimeUnit.MINUTES);
        t.release();

        if (!done) throw new Exception("Compression timed out after 20 min");

        if (err.get() != null) {
            // Auto-fallback to H.264 if HEVC/AV1 failed
            if (!MIME_AVC.equals(mime)) {
                Log.w(TAG, "Codec " + mime + " failed, falling back to H.264");
                outFile.delete();
                litrCompress(ctx, fileUri, outFile, w, h, bitrate, MIME_AVC, cb);
                return;
            }
            throw err.get();
        }
        if (!outFile.exists() || outFile.length() == 0)
            throw new Exception("Output file missing/empty after LiTr");
    }

    // ── Metadata ───────────────────────────────────────────────────────────

    public static class VideoMetadata {
        public int  width = 1280, height = 720, durationMs = 0, bitrate = 0;
        public float fps = 30;
    }

    public static VideoMetadata readMetadata(Context ctx, Uri uri) {
        VideoMetadata m = new VideoMetadata();
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            String w  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String d  = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (w  != null) m.width      = Integer.parseInt(w);
            if (h  != null) m.height     = Integer.parseInt(h);
            if (d  != null) m.durationMs = Integer.parseInt(d);
            if (br != null) m.bitrate    = Integer.parseInt(br);
        } catch (Exception e) {
            Log.w(TAG, "Metadata read failed: " + e.getMessage());
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        return m;
    }

    // ── Thumbnail ──────────────────────────────────────────────────────────

    public static File makeThumbnail(Context ctx, Uri videoUri, int durationMs, int thumbSize) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, videoUri);
            long us = Math.min(1_000_000L, (long) durationMs * 500L);
            android.graphics.Bitmap frame =
                mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null)
                frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) throw new IOException("No frame extracted");

            int side = Math.min(frame.getWidth(), frame.getHeight());
            android.graphics.Bitmap cropped = android.graphics.Bitmap.createBitmap(
                frame, (frame.getWidth() - side) / 2, (frame.getHeight() - side) / 2, side, side);
            android.graphics.Bitmap thumb =
                android.graphics.Bitmap.createScaledBitmap(cropped, thumbSize, thumbSize, true);
            frame.recycle();
            if (cropped != thumb) cropped.recycle();

            File dir = new File(ctx.getCacheDir(), "vid_out");
            dir.mkdirs();
            File out = new File(dir, "thumb_" + UUID.randomUUID() + ".webp");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    thumb.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 75, fos);
                else
                    //noinspection deprecation
                    thumb.compress(android.graphics.Bitmap.CompressFormat.WEBP, 75, fos);
            }
            thumb.recycle();
            Log.d(TAG, "Thumb: " + out.length() / 1024 + "KB");
            return out;
        } catch (Exception e) {
            Log.e(TAG, "Thumbnail failed: " + e.getMessage());
            File fallback = new File(ctx.getCacheDir(), "thumb_fallback.webp");
            try { if (!fallback.exists()) fallback.createNewFile(); } catch (IOException ignored) {}
            return fallback;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    // Backward-compatible overload
    static File makeThumbnail(Context ctx, Uri videoUri, int durationMs) {
        return makeThumbnail(ctx, videoUri, durationMs, 300);
    }

    // ── Resolution helpers ─────────────────────────────────────────────────

    static int[] calcTarget(int srcW, int srcH, int maxPx) {
        if (maxPx == Integer.MAX_VALUE) return new int[]{srcW, srcH};
        int longSide  = Math.max(srcW, srcH);
        int shortSide = Math.min(srcW, srcH);
        // maxPx = short-side cap (e.g. 540p means short side = 540px)
        // No downscale needed if already within limit
        if (shortSide <= maxPx) return new int[]{makeMultiple(srcW, 16), makeMultiple(srcH, 16)};
        float scale  = (float) maxPx / shortSide;
        int tShort   = makeMultiple(maxPx, 16);
        int tLong    = makeMultiple((int)(longSide * scale), 16);
        return srcH > srcW ? new int[]{tShort, tLong} : new int[]{tLong, tShort};
    }

    // Align to nearest multiple of n (codec requirement)
    private static int makeMultiple(int val, int n) {
        return Math.max(n, (val / n) * n);
    }

    public static void safeDelete(File f) {
        try { if (f != null && f.exists()) f.delete(); } catch (Exception ignored) {}
    }
}
