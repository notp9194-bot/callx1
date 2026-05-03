package com.callx.app.utils;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VideoCompressor — WhatsApp-level video compression
 *
 * FLOW:
 *   Original (any size/resolution) → Resize → Bitrate reduce → H.264 MP4
 *   ├── Compressed video (~5MB–15MB from 50MB)
 *   └── Thumbnail WebP  (~15KB–30KB)
 *
 * Strategy:
 *   1080p → 720p  (bitrate: 1.5Mbps)
 *   720p  → 480p  (bitrate: 800Kbps)
 *   480p  → 480p  (bitrate: 600Kbps)
 *
 * Usage:
 *   VideoCompressor.compress(ctx, uri, new VideoCompressor.Callback() {
 *       public void onProgress(int pct)       { progressBar.setProgress(pct); }
 *       public void onSuccess(Result r)       { upload(r); }
 *       public void onError(Exception e)      { showError(); }
 *   });
 */
public class VideoCompressor {

    private static final String TAG = "VideoCompressor";

    // ── Config ────────────────────────────────────────────────────────────
    // Target resolutions
    private static final int RES_1080P = 1080;
    private static final int RES_720P  =  720;
    private static final int RES_480P  =  480;

    // Target bitrates (bits/sec)
    private static final int BITRATE_HIGH   = 1_500_000;  // for 720p output
    private static final int BITRATE_MEDIUM =   800_000;  // for 480p output
    private static final int BITRATE_LOW    =   600_000;  // for low-res output

    // Max output file size target
    private static final long MAX_OUTPUT_BYTES = 16 * 1024 * 1024L; // 16MB

    // Mime types
    private static final String MIME_VIDEO = "video/avc";  // H.264
    private static final String MIME_AUDIO = "audio/mp4a-latm"; // AAC

    // Single background thread — no UI freeze
    private static final ExecutorService BG = Executors.newSingleThreadExecutor();
    private static final Handler MAIN       = new Handler(Looper.getMainLooper());

    // ─────────────────────────────────────────────────────────────────────

    public interface Callback {
        void onProgress(int percent);   // 0-100
        void onSuccess(Result result);
        void onError(Exception e);
    }

    public static class Result {
        public final File   videoFile;       // compressed MP4
        public final File   thumbFile;       // thumbnail WebP
        public final long   originalBytes;
        public final long   compressedBytes;
        public final int    durationMs;
        public final int    width;
        public final int    height;

        Result(File video, File thumb, long origBytes,
               long compBytes, int durationMs, int width, int height) {
            this.videoFile       = video;
            this.thumbFile       = thumb;
            this.originalBytes   = origBytes;
            this.compressedBytes = compBytes;
            this.durationMs      = durationMs;
            this.width           = width;
            this.height          = height;
        }

        public String compressionSummary() {
            return String.format("%.1fMB → %.1fMB (%.0f%% saved), %dx%d, %ds",
                originalBytes   / 1_000_000f,
                compressedBytes / 1_000_000f,
                100f * (1 - (float) compressedBytes / originalBytes),
                width, height,
                durationMs / 1000);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Compress on background thread, callbacks on main thread */
    public static void compress(Context ctx, Uri videoUri, Callback callback) {
        BG.execute(() -> {
            try {
                Result result = compressSync(ctx, videoUri,
                    pct -> MAIN.post(() -> callback.onProgress(pct)));
                MAIN.post(() -> callback.onSuccess(result));
            } catch (Exception e) {
                Log.e(TAG, "Compression failed", e);
                MAIN.post(() -> callback.onError(e));
            }
        });
    }

    // ── Core compression (runs on background thread) ──────────────────────

    public static Result compressSync(Context ctx, Uri videoUri,
                                      ProgressListener progressListener)
        throws IOException {

        // 1. Read original video metadata
        VideoMetadata meta = getMetadata(ctx, videoUri);
        Log.i(TAG, "Original: " + meta.width + "x" + meta.height
            + " " + meta.durationMs + "ms " + meta.fileSizeBytes + " bytes");

        // 2. Determine output resolution + bitrate
        int[] targetRes  = calcTargetResolution(meta.width, meta.height);
        int   targetW    = targetRes[0];
        int   targetH    = targetRes[1];
        int   bitrate    = calcBitrate(targetH);

        // 3. Generate thumbnail (frame at 1 second or middle)
        File thumbFile = generateThumbnail(ctx, videoUri, meta.durationMs);

        // 4. Compress video
        File videoOut = compressVideo(ctx, videoUri, meta,
            targetW, targetH, bitrate, progressListener);

        Log.i(TAG, "Compressed: " + new Result(videoOut, thumbFile,
            meta.fileSizeBytes, videoOut.length(),
            meta.durationMs, targetW, targetH).compressionSummary());

        return new Result(videoOut, thumbFile,
            meta.fileSizeBytes, videoOut.length(),
            meta.durationMs, targetW, targetH);
    }

    // ── Step 1: Read metadata ─────────────────────────────────────────────

    static class VideoMetadata {
        int  width, height, durationMs, frameRate;
        long fileSizeBytes;
        boolean hasAudio;
    }

    private static VideoMetadata getMetadata(Context ctx, Uri uri) throws IOException {
        VideoMetadata meta = new VideoMetadata();
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, uri);
            meta.width    = Integer.parseInt(mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            meta.height   = Integer.parseInt(mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            meta.durationMs = Integer.parseInt(mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION));

            String fr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            meta.frameRate = (fr != null) ? (int) Float.parseFloat(fr) : 30;
            if (meta.frameRate <= 0 || meta.frameRate > 60) meta.frameRate = 30;

            String hasAudioStr = mmr.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            meta.hasAudio = "yes".equalsIgnoreCase(hasAudioStr);

        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }

        // Get file size via InputStream
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            meta.fileSizeBytes = in != null ? in.available() : 0;
        } catch (Exception e) {
            meta.fileSizeBytes = 0;
        }
        return meta;
    }

    // ── Step 2: Target resolution ─────────────────────────────────────────

    private static int[] calcTargetResolution(int srcW, int srcH) {
        int longSide  = Math.max(srcW, srcH);
        int shortSide = Math.min(srcW, srcH);

        int targetLong;
        if (longSide > RES_1080P) {
            targetLong = RES_720P;       // 1080p+ → 720p
        } else if (longSide > RES_720P) {
            targetLong = RES_720P;       // 720p-1080p → 720p
        } else if (longSide > RES_480P) {
            targetLong = RES_480P;       // 480p-720p → 480p
        } else {
            targetLong  = longSide;      // already small, keep
        }

        // Maintain aspect ratio
        float scale     = (float) targetLong / longSide;
        int targetShort = (int)(shortSide * scale);

        // Ensure even dimensions (H.264 requirement)
        targetLong  = makeEven(targetLong);
        targetShort = makeEven(targetShort);

        // Return as W, H (same orientation as source)
        boolean isPortrait = srcH > srcW;
        return isPortrait
            ? new int[]{targetShort, targetLong}
            : new int[]{targetLong, targetShort};
    }

    private static int calcBitrate(int targetH) {
        if (targetH >= 720) return BITRATE_HIGH;
        if (targetH >= 480) return BITRATE_MEDIUM;
        return BITRATE_LOW;
    }

    private static int makeEven(int v) {
        return v % 2 == 0 ? v : v - 1;
    }

    // ── Step 3: Generate thumbnail ─────────────────────────────────────────

    static File generateThumbnail(Context ctx, Uri videoUri, int durationMs) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(ctx, videoUri);

            // Use frame at 1 second, or middle if short video
            long timeUs = Math.min(1_000_000L, (durationMs * 1000L) / 2);
            android.graphics.Bitmap frame = mmr.getFrameAtTime(timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

            if (frame == null) {
                frame = mmr.getFrameAtTime(0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) throw new IOException("Cannot extract frame");

            // Center-crop square thumbnail (same as ImageCompressor)
            int size = 300;
            int w = frame.getWidth(), h = frame.getHeight();
            int min = Math.min(w, h);
            int x = (w - min) / 2, y = (h - min) / 2;
            android.graphics.Bitmap cropped = android.graphics.Bitmap
                .createBitmap(frame, x, y, min, min);
            android.graphics.Bitmap thumb   = android.graphics.Bitmap
                .createScaledBitmap(cropped, size, size, true);
            frame.recycle();
            cropped.recycle();

            // Save as WebP
            File dir = new File(ctx.getCacheDir(), "vid_compress");
            dir.mkdirs();
            File out = new File(dir, "thumb_" + UUID.randomUUID() + ".webp");

            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    thumb.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 75, fos);
                } else {
                    thumb.compress(android.graphics.Bitmap.CompressFormat.WEBP, 75, fos);
                }
            }
            thumb.recycle();
            Log.d(TAG, "Thumbnail: " + out.length() / 1000 + "KB");
            return out;

        } catch (Exception e) {
            Log.e(TAG, "Thumbnail generation failed: " + e.getMessage());
            // Return empty file as fallback
            File fallback = new File(ctx.getCacheDir(), "thumb_fallback.webp");
            try { fallback.createNewFile(); } catch (IOException ignored) {}
            return fallback;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }

    // ── Step 4: MediaCodec compression (H.264) ───────────────────────────

    private static File compressVideo(Context ctx, Uri inputUri,
                                      VideoMetadata meta,
                                      int targetW, int targetH,
                                      int targetBitrate,
                                      ProgressListener progressCb)
        throws IOException {

        File outDir = new File(ctx.getCacheDir(), "vid_compress");
        outDir.mkdirs();
        File outFile = new File(outDir, "vid_" + UUID.randomUUID() + ".mp4");

        // Copy URI to temp file (MediaExtractor needs file path or FD)
        File tmpInput = copyUriToTemp(ctx, inputUri);

        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(tmpInput.getAbsolutePath());

        int videoTrackIdx = -1;
        int audioTrackIdx = -1;
        MediaFormat videoFormat = null;
        MediaFormat audioFormat = null;

        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/") && videoTrackIdx < 0) {
                videoTrackIdx = i;
                videoFormat = fmt;
            } else if (mime != null && mime.startsWith("audio/") && audioTrackIdx < 0) {
                audioTrackIdx = i;
                audioFormat = fmt;
            }
        }

        if (videoTrackIdx < 0) {
            tmpInput.delete();
            throw new IOException("No video track found");
        }

        // Setup encoder
        MediaFormat encoderFmt = MediaFormat.createVideoFormat(MIME_VIDEO, targetW, targetH);
        encoderFmt.setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate);
        encoderFmt.setInteger(MediaFormat.KEY_FRAME_RATE, meta.frameRate);
        encoderFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2); // keyframe every 2s
        encoderFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        MediaCodec encoder = MediaCodec.createEncoderByType(MIME_VIDEO);
        encoder.configure(encoderFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        android.view.Surface encoderSurface = encoder.createInputSurface();
        encoder.start();

        // Setup decoder (reads from extractor → renders to encoder surface)
        extractor.selectTrack(videoTrackIdx);
        MediaCodec decoder = MediaCodec.createDecoderByType(
            videoFormat.getString(MediaFormat.KEY_MIME));
        decoder.configure(videoFormat, encoderSurface, null, 0);
        decoder.start();

        // Setup muxer
        MediaMuxer muxer = new MediaMuxer(outFile.getAbsolutePath(),
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Run transcode loop
        transcodeVideo(extractor, decoder, encoder, muxer,
            audioTrackIdx, audioFormat, tmpInput, meta.durationMs, progressCb);

        // Cleanup
        decoder.stop();    decoder.release();
        encoder.stop();    encoder.release();
        encoderSurface.release();
        extractor.release();
        muxer.stop();      muxer.release();
        tmpInput.delete();

        return outFile;
    }

    // ── Transcode loop ────────────────────────────────────────────────────

    private static void transcodeVideo(MediaExtractor extractor,
                                       MediaCodec decoder,
                                       MediaCodec encoder,
                                       MediaMuxer muxer,
                                       int audioTrackIdx,
                                       MediaFormat audioFormat,
                                       File tmpInput,
                                       int durationMs,
                                       ProgressListener progressCb)
        throws IOException {

        final int TIMEOUT_US = 10_000;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        boolean decoderDone = false;
        boolean encoderDone = false;
        int     muxVideoTrack = -1;
        int     muxAudioTrack = -1;
        long    lastProgressUs = 0;

        // Add audio track directly (copy, no re-encode)
        if (audioTrackIdx >= 0 && audioFormat != null) {
            muxAudioTrack = muxer.addTrack(audioFormat);
        }
        // Video track will be added once encoder outputs format

        while (!encoderDone) {

            // ── Feed decoder ──
            if (!decoderDone) {
                int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = decoder.getInputBuffer(inIdx);
                    int sampleSize = extractor.readSampleData(inBuf, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        decoderDone = true;
                    } else {
                        long ptsUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0);
                        extractor.advance();

                        // Progress based on presentation time
                        if (durationMs > 0 && ptsUs - lastProgressUs > 500_000L) {
                            int pct = (int)(100L * ptsUs / (durationMs * 1000L));
                            if (progressCb != null) progressCb.onProgress(Math.min(pct, 95));
                            lastProgressUs = ptsUs;
                        }
                    }
                }
            }

            // ── Drain decoder → encoder (via Surface, auto) ──
            int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx >= 0) {
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                decoder.releaseOutputBuffer(outIdx, true); // render to encoder surface
                if (eos) encoder.signalEndOfInputStream();
            }

            // ── Drain encoder → muxer ──
            int encIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                // Start muxer once all tracks are added
                muxer.start();
                // Copy audio passthrough
                if (muxAudioTrack >= 0) {
                    copyAudioTrack(tmpInput, audioTrackIdx, muxer, muxAudioTrack);
                }
            } else if (encIdx >= 0) {
                ByteBuffer encBuf = encoder.getOutputBuffer(encIdx);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    && muxVideoTrack >= 0 && encBuf != null) {
                    muxer.writeSampleData(muxVideoTrack, encBuf, info);
                }
                encoder.releaseOutputBuffer(encIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encoderDone = true;
                }
            }
        }
        if (progressCb != null) progressCb.onProgress(100);
    }

    // ── Audio copy (no re-encode — faster, no quality loss) ───────────────

    private static void copyAudioTrack(File srcFile, int audioTrackIdx,
                                       MediaMuxer muxer, int muxAudioTrack) {
        MediaExtractor audioEx = new MediaExtractor();
        try {
            audioEx.setDataSource(srcFile.getAbsolutePath());
            audioEx.selectTrack(audioTrackIdx);
            ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int sz = audioEx.readSampleData(buf, 0);
                if (sz < 0) break;
                info.offset         = 0;
                info.size           = sz;
                info.presentationTimeUs = audioEx.getSampleTime();
                info.flags          = audioEx.getSampleFlags();
                muxer.writeSampleData(muxAudioTrack, buf, info);
                audioEx.advance();
            }
        } catch (Exception e) {
            Log.w(TAG, "Audio copy failed: " + e.getMessage());
        } finally {
            audioEx.release();
        }
    }

    // ── Copy URI → temp file ──────────────────────────────────────────────

    private static File copyUriToTemp(Context ctx, Uri uri) throws IOException {
        File tmp = new File(ctx.getCacheDir(), "vid_input_" + UUID.randomUUID() + ".mp4");
        try (java.io.InputStream in  = ctx.getContentResolver().openInputStream(uri);
             java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Cannot open input URI");
            byte[] buf = new byte[64 * 1024];
            int    read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
        }
        return tmp;
    }

    // ── Progress listener ─────────────────────────────────────────────────

    public interface ProgressListener {
        void onProgress(int percent);
    }
}
