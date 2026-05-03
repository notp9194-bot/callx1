package com.callx.app.utils;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.linkedin.android.litr.MediaTransformer;
import com.linkedin.android.litr.TransformationListener;
import com.linkedin.android.litr.TransformationOptions;
import com.linkedin.android.litr.analytics.TrackTransformationInfo;
import com.linkedin.android.litr.codec.MediaCodecDecoder;
import com.linkedin.android.litr.codec.MediaCodecEncoder;
import com.linkedin.android.litr.io.MediaExtractorMediaSource;
import com.linkedin.android.litr.io.MediaMuxerMediaTarget;
import com.linkedin.android.litr.render.GlVideoRenderer;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * VideoCompressor — WhatsApp-level video compression using LiTr (LinkedIn)
 *
 * FLOW:
 *   Original (any size) → LiTr transcode → H.264 MP4 (resized + bitrate reduced)
 *   ├── Compressed video  (720p→~5MB, 1080p→~10MB)
 *   └── Thumbnail WebP    (~15–30KB, extracted at 1s)
 *
 * Strategy:
 *   1080p → 720p  @ 1.5 Mbps
 *   720p  → 480p  @ 800 Kbps
 *   480p  → 480p  @ 600 Kbps
 *   Already small → keep as-is @ 400 Kbps
 *
 * Usage:
 *   VideoCompressor.compress(ctx, uri, callback);
 */
public class VideoCompressor {

    private static final String TAG = "VideoCompressor";

    // Target bitrates (bps)
    private static final int BITRATE_HIGH   = 1_500_000;
    private static final int BITRATE_MEDIUM =   800_000;
    private static final int BITRATE_LOW    =   600_000;
    private static final int BITRATE_MIN    =   400_000;

    private static final int    FRAME_RATE      = 30;
    private static final int    KEY_FRAME_INT   = 3;     // keyframe every 3s
    private static final String MIME_VIDEO      = "video/avc"; // H.264

    private static final ExecutorService BG   = Executors.newSingleThreadExecutor();
    private static final Handler         MAIN = new Handler(Looper.getMainLooper());

    // ── Public types ──────────────────────────────────────────────────────

    public interface Callback {
        void onProgress(int percent);
        void onSuccess(Result result);
        void onError(Exception e);
    }

    public interface ProgressListener {
        void onProgress(int percent);
    }

    public static class Result {
        public final File videoFile;
        public final File thumbFile;
        public final long originalBytes;
        public final long compressedBytes;
        public final int  durationMs;
        public final int  width;
        public final int  height;

        Result(File video, File thumb, long origBytes,
               long compBytes, int durationMs, int w, int h) {
            this.videoFile       = video;
            this.thumbFile       = thumb;
            this.originalBytes   = origBytes;
            this.compressedBytes = compBytes;
            this.durationMs      = durationMs;
            this.width           = w;
            this.height          = h;
        }

        public String compressionSummary() {
            return String.format("%.1fMB → %.1fMB (%.0f%% saved), %dx%d, %ds",
                originalBytes   / 1_000_000f,
                compressedBytes / 1_000_000f,
                100f * (1f - (float) compressedBytes / Math.max(originalBytes, 1)),
                width, height, durationMs / 1000);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Async compress — callbacks on main thread */
    public static void compress(Context ctx, Uri videoUri, Callback callback) {
        BG.execute(() -> {
            try {
                Result r = compressSync(ctx, videoUri,
                    pct -> MAIN.post(() -> callback.onProgress(pct)));
                MAIN.post(() -> callback.onSuccess(r));
            } catch (Exception e) {
                Log.e(TAG, "Compression failed", e);
                MAIN.post(() -> callback.onError(e));
            }
        });
    }

    /** Blocking compress — call only from background thread */
    public static Result compressSync(Context ctx, Uri videoUri,
                                      ProgressListener progressCb)
        throws Exception {

        // 1. Read metadata
        VideoMetadata meta = getMetadata(ctx, videoUri);
        Log.i(TAG, "Original: " + meta.width + "x" + meta.height
            + " dur=" + meta.durationMs + "ms size=" + meta.fileSizeBytes + "B");

        // 2. Target resolution & bitrate
        int[] target  = calcTarget(meta.width, meta.height);
        int   targetW = target[0];
        int   targetH = target[1];
        int   bitrate = calcBitrate(Math.max(targetW, targetH));

        // 3. Generate thumbnail
        File thumbFile = generateThumbnail(ctx, videoUri, meta.durationMs);

        // 4. Compress via LiTr
        File outDir = new File(ctx.getCacheDir(), "vid_compress");
        outDir.mkdirs();
        File outFile = new File(outDir, "vid_" + UUID.randomUUID() + ".mp4");

        compressWithLiTr(ctx, videoUri, outFile, targetW, targetH, bitrate, progressCb);

        long outSize = outFile.length();
        Log.i(TAG, "Done: " + outFile.length() / 1024 + " KB → " + targetW + "x" + targetH);

        return new Result(outFile, thumbFile,
            meta.fileSizeBytes, outSize,
            meta.durationMs, targetW, targetH);
    }

    // ── LiTr compression core ─────────────────────────────────────────────

    private static void compressWithLiTr(Context ctx, Uri inputUri, File outFile,
                                          int targetW, int targetH, int bitrate,
                                          ProgressListener progressCb)
        throws Exception {

        CountDownLatch             latch  = new CountDownLatch(1);
        AtomicReference<Exception> error  = new AtomicReference<>();

        // Build video target format
        android.media.MediaFormat videoFmt =
            android.media.MediaFormat.createVideoFormat(MIME_VIDEO, targetW, targetH);
        videoFmt.setInteger(android.media.MediaFormat.KEY_BIT_RATE,      bitrate);
        videoFmt.setInteger(android.media.MediaFormat.KEY_FRAME_RATE,    FRAME_RATE);
        videoFmt.setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_INT);
        videoFmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
            android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        String requestId = UUID.randomUUID().toString();

        MediaTransformer transformer = new MediaTransformer(ctx.getApplicationContext());

        transformer.transform(
            requestId,
            inputUri,
            outFile.getAbsolutePath(),
            videoFmt,
            null,   // audio format null = copy audio as-is
            new TransformationListener() {
                @Override
                public void onStarted(@androidx.annotation.NonNull String id) {
                    Log.d(TAG, "LiTr started: " + id);
                }

                @Override
                public void onProgress(@androidx.annotation.NonNull String id, float progress) {
                    if (progressCb != null) {
                        progressCb.onProgress(Math.min((int)(progress * 100), 95));
                    }
                }

                @Override
                public void onCompleted(@androidx.annotation.NonNull String id,
                                        List<TrackTransformationInfo> trackInfos) {
                    Log.d(TAG, "LiTr completed: " + id);
                    if (progressCb != null) progressCb.onProgress(100);
                    latch.countDown();
                }

                @Override
                public void onCancelled(@androidx.annotation.NonNull String id,
                                        List<TrackTransformationInfo> trackInfos) {
                    error.set(new Exception("Compression cancelled"));
                    latch.countDown();
                }

                @Override
                public void onError(@androidx.annotation.NonNull String id,
                                    Throwable cause,
                                    List<TrackTransformationInfo> trackInfos) {
                    Log.e(TAG, "LiTr error: " + (cause != null ? cause.getMessage() : "unknown"));
                    error.set(new Exception("Compression failed: " +
                        (cause != null ? cause.getMessage() : "unknown")));
                    latch.countDown();
                }
            },
            new TransformationOptions.Builder()
                .setGranularity(MediaTransformer.GRANULARITY_DEFAULT)
                .build()
        );

        // Wait up to 15 minutes for large videos
        boolean finished = latch.await(15, TimeUnit.MINUTES);
        transformer.release();

        if (!finished) throw new Exception("Compression timed out");
        if (error.get() != null) throw error.get();
        if (!outFile.exists() || outFile.length() == 0)
            throw new Exception("Output file empty after compression");
    }

    // ── Metadata reader ───────────────────────────────────────────────────

    static class VideoMetadata {
        int  width, height, durationMs;
        long fileSizeBytes;
    }

    private static VideoMetadata getMetadata(Context ctx, Uri uri) throws IOException {
        VideoMetadata meta = new VideoMetadata();
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            String w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            String h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            meta.width      = w != null ? Integer.parseInt(w) : 1280;
            meta.height     = h != null ? Integer.parseInt(h) : 720;
            meta.durationMs = d != null ? Integer.parseInt(d) : 0;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            meta.fileSizeBytes = in != null ? in.available() : 0;
        } catch (Exception ignored) {}
        return meta;
    }

    // ── Resolution & bitrate logic ─────────────────────────────────────────

    private static int[] calcTarget(int srcW, int srcH) {
        int longSide  = Math.max(srcW, srcH);
        int shortSide = Math.min(srcW, srcH);

        int targetLong;
        if      (longSide > 1080) targetLong = 720;
        else if (longSide > 720)  targetLong = 720;
        else if (longSide > 480)  targetLong = 480;
        else                       targetLong = longSide;

        float scale     = (float) targetLong / longSide;
        int targetShort = makeEven((int)(shortSide * scale));
        targetLong      = makeEven(targetLong);

        boolean portrait = srcH > srcW;
        return portrait
            ? new int[]{targetShort, targetLong}
            : new int[]{targetLong, targetShort};
    }

    private static int calcBitrate(int longSide) {
        if (longSide >= 720) return BITRATE_HIGH;
        if (longSide >= 480) return BITRATE_MEDIUM;
        if (longSide >= 360) return BITRATE_LOW;
        return BITRATE_MIN;
    }

    private static int makeEven(int v) { return v % 2 == 0 ? v : v - 1; }

    // ── Thumbnail generator ───────────────────────────────────────────────

    static File generateThumbnail(Context ctx, Uri videoUri, int durationMs) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, videoUri);
            long timeUs = Math.min(1_000_000L, (durationMs * 1000L) / 2);
            android.graphics.Bitmap frame = mmr.getFrameAtTime(timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null)
                frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) throw new IOException("Cannot extract frame");

            int size = 300;
            int w = frame.getWidth(), h = frame.getHeight();
            int min = Math.min(w, h);
            android.graphics.Bitmap cropped =
                android.graphics.Bitmap.createBitmap(frame, (w - min) / 2, (h - min) / 2, min, min);
            android.graphics.Bitmap thumb =
                android.graphics.Bitmap.createScaledBitmap(cropped, size, size, true);
            frame.recycle();
            if (cropped != thumb) cropped.recycle();

            File dir = new File(ctx.getCacheDir(), "vid_compress");
            dir.mkdirs();
            File out = new File(dir, "thumb_" + UUID.randomUUID() + ".webp");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    thumb.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 75, fos);
                } else {
                    //noinspection deprecation
                    thumb.compress(android.graphics.Bitmap.CompressFormat.WEBP, 75, fos);
                }
            }
            thumb.recycle();
            Log.d(TAG, "Thumbnail: " + out.length() / 1000 + "KB");
            return out;

        } catch (Exception e) {
            Log.e(TAG, "Thumbnail failed: " + e.getMessage());
            File fallback = new File(ctx.getCacheDir(), "thumb_fallback.webp");
            try { fallback.createNewFile(); } catch (IOException ignored) {}
            return fallback;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }
}
