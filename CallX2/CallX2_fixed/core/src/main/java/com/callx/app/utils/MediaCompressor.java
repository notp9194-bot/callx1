package com.callx.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MediaCompressor v24 — Quality-aware image + video compression.
 *
 * Images:
 *   - Max 1280×1280 px (configurable via compressImageWithQuality)
 *   - JPEG quality 80% default (configurable)
 *   - WebP for smaller output on Android 11+
 *
 * Videos:
 *   - File < threshold → compress nahi, seedha upload
 *   - Uses VideoQualityPreferences.Quality for adaptive settings
 *   - Audio track copy as-is (fast, no quality loss)
 *   - Fail hone pe original file return (no crash)
 */
public class MediaCompressor {

    private static final String TAG = "MediaCompressor";

    // ── Image settings ────────────────────────────────────────────
    private static final int  MAX_IMAGE_PX    = 1280;
    private static final int  IMAGE_QUALITY   = 80;
    private static final int  THUMB_MAX_PX    = 320;
    private static final int  THUMB_QUALITY   = 70;

    // ── Video settings ────────────────────────────────────────────
    private static final long VIDEO_SKIP_BYTES = 10L * 1024 * 1024; // <10 MB → skip
    private static final int  VIDEO_FPS        = 30;
    private static final int  I_FRAME_SECS     = 2;

    private static final ExecutorService sPool = Executors.newFixedThreadPool(2);
    private static final Handler         sMain = new Handler(Looper.getMainLooper());

    // ── Public interfaces ─────────────────────────────────────────

    public interface VideoCallback {
        void onDone(File outFile);
        void onError(String msg);
    }

    // ─────────────────────────────────────────────────────────────
    // IMAGE COMPRESSION
    // ─────────────────────────────────────────────────────────────

    /**
     * Compress image at default quality (1280px, JPEG 80%).
     */
    public static byte[] compressImage(Context ctx, Uri uri) {
        return compressImageWithQuality(ctx, uri, MAX_IMAGE_PX, IMAGE_QUALITY, false);
    }

    /**
     * Compress image thumbnail (320px, WebP 70%).
     */
    public static byte[] compressImageThumb(Context ctx, Uri uri) {
        return compressImageWithQuality(ctx, uri, THUMB_MAX_PX, THUMB_QUALITY, true);
    }

    /**
     * Full-control image compression.
     * @param maxPx     max dimension
     * @param quality   JPEG/WebP quality 0-100
     * @param useWebP   use WebP format (smaller, Android 11+)
     */
    public static byte[] compressImageWithQuality(Context ctx, Uri uri,
                                                   int maxPx, int quality,
                                                   boolean useWebP) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, opts);
            }
            int srcW = opts.outWidth, srcH = opts.outHeight;
            if (srcW <= 0 || srcH <= 0) return null;

            opts.inSampleSize      = calcSampleSize(srcW, srcH, maxPx);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig  = Bitmap.Config.RGB_565;

            Bitmap bmp;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bmp == null) return null;
            bmp = scaleBitmap(bmp, maxPx);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (useWebP && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out);
            } else {
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out);
            }
            bmp.recycle();

            byte[] result = out.toByteArray();
            Log.d(TAG, "Image compressed: " + result.length / 1024 + " KB");
            return result;

        } catch (Exception e) {
            Log.w(TAG, "Image compress failed: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────
    // VIDEO COMPRESSION
    // ─────────────────────────────────────────────────────────────

    /** Compress video with default STANDARD quality. */
    public static void compressVideo(Context ctx, Uri srcUri, File outFile, VideoCallback cb) {
        compressVideoWithQuality(ctx, srcUri, outFile,
            VideoQualityPreferences.Quality.STANDARD, cb);
    }

    /** Compress video with specific quality setting. */
    public static void compressVideoWithQuality(Context ctx, Uri srcUri, File outFile,
                                                 VideoQualityPreferences.Quality quality,
                                                 VideoCallback cb) {
        sPool.execute(() -> {
            try {
                File tempIn = new File(ctx.getCacheDir(),
                    "vc_in_" + System.currentTimeMillis() + ".mp4");
                if (!copyUriToFile(ctx, srcUri, tempIn)) {
                    post(cb, null, "Cannot read video");
                    return;
                }

                if (tempIn.length() < VIDEO_SKIP_BYTES) {
                    Log.d(TAG, "Video small (<10MB), skip compress");
                    post(cb, tempIn, null);
                    return;
                }

                Log.d(TAG, "Compressing video " + tempIn.length() / (1024 * 1024)
                    + " MB [" + quality.label + "]");

                int maxPx   = quality.maxPx == Integer.MAX_VALUE ? 720 : quality.maxPx;
                int bitrate = quality.bitrate == Integer.MAX_VALUE ? 1_500_000 : quality.bitrate;

                File result = transcodeVideo(tempIn, outFile, maxPx, bitrate);
                post(cb, result != null ? result : tempIn, null);

            } catch (Exception e) {
                Log.e(TAG, "Video compress error: " + e.getMessage());
                post(cb, null, e.getMessage());
            }
        });
    }

    // ─────────────────────────────────────────────────────────────
    // VIDEO TRANSCODING — MediaCodec pipeline
    // ─────────────────────────────────────────────────────────────

    private static File transcodeVideo(File src, File out, int maxPx, int bitrate) {
        MediaExtractor videoEx = null;
        MediaExtractor audioEx = null;
        MediaCodec     decoder = null;
        MediaCodec     encoder = null;
        Surface        encSurf = null;
        MediaMuxer     muxer   = null;

        try {
            videoEx = new MediaExtractor();
            videoEx.setDataSource(src.getAbsolutePath());

            int videoTrack = -1, audioTrack = -1;
            MediaFormat videoFmt = null, audioFmt = null;
            for (int i = 0; i < videoEx.getTrackCount(); i++) {
                MediaFormat fmt  = videoEx.getTrackFormat(i);
                String      mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/") && videoTrack < 0) {
                    videoTrack = i; videoFmt = fmt;
                } else if (mime != null && mime.startsWith("audio/") && audioTrack < 0) {
                    audioTrack = i; audioFmt = fmt;
                }
            }
            if (videoTrack < 0 || videoFmt == null) {
                Log.w(TAG, "No video track found");
                return src;
            }

            int srcW = videoFmt.containsKey(MediaFormat.KEY_WIDTH)
                ? videoFmt.getInteger(MediaFormat.KEY_WIDTH) : 1280;
            int srcH = videoFmt.containsKey(MediaFormat.KEY_HEIGHT)
                ? videoFmt.getInteger(MediaFormat.KEY_HEIGHT) : 720;
            int[] dim  = scaleDim(srcW, srcH, maxPx);
            int   outW = dim[0], outH = dim[1];

            MediaFormat encFmt = MediaFormat.createVideoFormat("video/avc", outW, outH);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            encFmt.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS);
            encFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SECS);

            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encSurf = encoder.createInputSurface();
            encoder.start();

            String videoMime = videoFmt.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(videoMime);
            decoder.configure(videoFmt, encSurf, null, 0);
            decoder.start();

            muxer = new MediaMuxer(out.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int muxAudioIdx = -1;
            if (audioTrack >= 0 && audioFmt != null)
                muxAudioIdx = muxer.addTrack(audioFmt);

            videoEx.selectTrack(videoTrack);
            runVideoLoop(videoEx, decoder, encoder, muxer, muxAudioIdx >= 0);

            if (muxAudioIdx >= 0) {
                audioEx = new MediaExtractor();
                audioEx.setDataSource(src.getAbsolutePath());
                audioEx.selectTrack(audioTrack);
                copyAudio(audioEx, muxer, muxAudioIdx);
            }

            muxer.stop();
            Log.d(TAG, "Transcoded: " + out.length() / (1024 * 1024) + " MB");
            return out;

        } catch (Exception e) {
            Log.e(TAG, "Transcode failed: " + e.getMessage());
            try { if (out.exists()) out.delete(); } catch (Exception ignored) {}
            return src;
        } finally {
            safeRelease(decoder);
            safeRelease(encoder);
            if (encSurf != null) try { encSurf.release(); } catch (Exception ignored) {}
            try { if (videoEx != null) videoEx.release(); } catch (Exception ignored) {}
            try { if (audioEx != null) audioEx.release(); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private static void runVideoLoop(MediaExtractor ex, MediaCodec dec,
                                     MediaCodec enc, MediaMuxer muxer,
                                     boolean hasAudio) throws Exception {
        boolean decInputDone = false, decOutputDone = false, encDone = false;
        int     muxVideoIdx  = -1;
        boolean muxStarted   = false;

        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();

        while (!encDone) {
            if (!decInputDone) {
                int inIdx = dec.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = dec.getInputBuffer(inIdx);
                    if (inBuf == null) { decInputDone = true; continue; }
                    int n = ex.readSampleData(inBuf, 0);
                    if (n < 0) {
                        dec.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        decInputDone = true;
                    } else {
                        dec.queueInputBuffer(inIdx, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            if (!decOutputDone) {
                int outIdx = dec.dequeueOutputBuffer(decInfo, 10_000);
                if (outIdx >= 0) {
                    boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    dec.releaseOutputBuffer(outIdx, true);
                    if (eos) { enc.signalEndOfInputStream(); decOutputDone = true; }
                }
            }
            int encIdx = enc.dequeueOutputBuffer(encInfo, 10_000);
            if (encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxStarted) {
                    muxVideoIdx = muxer.addTrack(enc.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                }
            } else if (encIdx >= 0) {
                ByteBuffer encBuf = enc.getOutputBuffer(encIdx);
                boolean isConfig = (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!isConfig && muxStarted && encInfo.size > 0 && encBuf != null) {
                    encBuf.position(encInfo.offset);
                    encBuf.limit(encInfo.offset + encInfo.size);
                    muxer.writeSampleData(muxVideoIdx, encBuf, encInfo);
                }
                enc.releaseOutputBuffer(encIdx, false);
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encDone = true;
            }
        }
    }

    private static void copyAudio(MediaExtractor audioEx, MediaMuxer muxer, int muxAudioIdx) {
        ByteBuffer buf  = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int n = audioEx.readSampleData(buf, 0);
            if (n < 0) break;
            info.offset = 0; info.size = n;
            info.presentationTimeUs = audioEx.getSampleTime();
            info.flags = audioEx.getSampleFlags();
            muxer.writeSampleData(muxAudioIdx, buf, info);
            audioEx.advance();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────

    private static int calcSampleSize(int w, int h, int maxPx) {
        int sample = 1;
        while ((w / (sample * 2)) > maxPx || (h / (sample * 2)) > maxPx) sample *= 2;
        return sample;
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxPx) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxPx && h <= maxPx) return src;
        float ratio  = Math.min((float) maxPx / w, (float) maxPx / h);
        Bitmap scaled = Bitmap.createScaledBitmap(src,
            Math.round(w * ratio), Math.round(h * ratio), true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    private static int[] scaleDim(int w, int h, int maxPx) {
        if (w <= maxPx && h <= maxPx) return new int[]{w, h};
        float r = Math.min((float) maxPx / w, (float) maxPx / h);
        return new int[]{Math.round(w * r) & ~1, Math.round(h * r) & ~1};
    }

    private static boolean copyUriToFile(Context ctx, Uri uri, File out) {
        try (InputStream is  = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (is == null) return false;
            byte[] buf = new byte[65536]; int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            return out.length() > 0;
        } catch (IOException e) {
            Log.e(TAG, "copyUriToFile failed: " + e.getMessage());
            return false;
        }
    }

    private static void safeRelease(MediaCodec codec) {
        if (codec == null) return;
        try { codec.stop(); }    catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
    }

    private static void post(VideoCallback cb, File file, String err) {
        sMain.post(() -> {
            if (file != null) cb.onDone(file);
            else              cb.onError(err != null ? err : "Compression failed");
        });
    }

    private MediaCompressor() {}
}
