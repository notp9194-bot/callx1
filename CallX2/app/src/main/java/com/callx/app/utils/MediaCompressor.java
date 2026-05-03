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
 * MediaCompressor — Image aur Video compress karta hai upload se pehle.
 *
 * Images:
 *   - Max 1280×1280 px tak scale down
 *   - JPEG quality 80% pe compress
 *   - Typically 70–85% size reduction original se
 *
 * Videos:
 *   - File < VIDEO_SKIP_BYTES  → compress nahi, seedha upload
 *   - File >= VIDEO_SKIP_BYTES → MediaCodec H.264 pipeline (720p max, 1.5 Mbps)
 *   - Audio track: re-encode nahi, copy as-is (fast)
 *   - Fail ho toh original file return (no crash)
 */
public class MediaCompressor {

    private static final String TAG = "MediaCompressor";

    // ── Image settings ────────────────────────────────────────────
    private static final int  MAX_IMAGE_PX   = 1280;
    private static final int  IMAGE_QUALITY  = 80;

    // ── Video settings ────────────────────────────────────────────
    private static final long VIDEO_SKIP_BYTES = 20L * 1024 * 1024; // <20 MB → skip
    private static final int  MAX_VIDEO_PX    = 720;
    private static final int  VIDEO_BITRATE   = 1_500_000;           // 1.5 Mbps
    private static final int  VIDEO_FPS       = 30;
    private static final int  I_FRAME_SECS    = 2;

    private static final ExecutorService sPool = Executors.newFixedThreadPool(2);
    private static final Handler         sMain = new Handler(Looper.getMainLooper());

    // ── Public interfaces ─────────────────────────────────────────

    public interface VideoCallback {
        void onDone(File outFile);   // outFile: compressed, ya original if small/error
        void onError(String msg);
    }

    // ─────────────────────────────────────────────────────────────
    // IMAGE COMPRESSION
    // ─────────────────────────────────────────────────────────────

    /**
     * URI se image padho, resize karo, JPEG 80% compress karo.
     * Returns compressed byte[] ya null on error.
     * Synchronous — background thread pe call karo.
     */
    public static byte[] compressImage(Context ctx, Uri uri) {
        try {
            // Step 1 – dimensions pado bina bitmap load kiye
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, opts);
            }
            int srcW = opts.outWidth, srcH = opts.outHeight;
            if (srcW <= 0 || srcH <= 0) return null;

            // Step 2 – inSampleSize calculate karo
            opts.inSampleSize = calcSampleSize(srcW, srcH, MAX_IMAGE_PX);
            opts.inJustDecodeBounds = false;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bmp;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bmp == null) return null;

            // Step 3 – still too large? scale down
            bmp = scaleBitmap(bmp, MAX_IMAGE_PX);

            // Step 4 – JPEG compress
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, out);
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

    /**
     * Video URI ko background mein compress karta hai.
     * Agar file < VIDEO_SKIP_BYTES hai to seedha original return karta hai.
     * Agar MediaCodec transcoding fail ho to bhi original return karta hai.
     * Callback always main thread pe aata hai.
     */
    public static void compressVideo(Context ctx, Uri srcUri,
                                     File outFile, VideoCallback cb) {
        sPool.execute(() -> {
            try {
                // Pehle URI ko temp file mein copy karo (MediaExtractor needs path)
                File tempIn = new File(ctx.getCacheDir(),
                        "vc_in_" + System.currentTimeMillis() + ".mp4");
                if (!copyUriToFile(ctx, srcUri, tempIn)) {
                    post(cb, null, "Cannot read video");
                    return;
                }

                // File size check — chota video compress nahi karo
                if (tempIn.length() < VIDEO_SKIP_BYTES) {
                    Log.d(TAG, "Video small (<20MB), skip compress");
                    post(cb, tempIn, null);
                    return;
                }

                Log.d(TAG, "Compressing video " + tempIn.length() / (1024 * 1024) + " MB");
                File result = transcodeVideo(tempIn, outFile);
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

    private static File transcodeVideo(File src, File out) {
        MediaExtractor videoEx = null;
        MediaExtractor audioEx = null;
        MediaCodec     decoder = null;
        MediaCodec     encoder = null;
        Surface        encSurf = null;
        MediaMuxer     muxer   = null;

        try {
            // ── 1. Find tracks ───────────────────────────────────
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

            // ── 2. Output dimensions ─────────────────────────────
            int srcW = videoFmt.containsKey(MediaFormat.KEY_WIDTH)
                    ? videoFmt.getInteger(MediaFormat.KEY_WIDTH) : 1280;
            int srcH = videoFmt.containsKey(MediaFormat.KEY_HEIGHT)
                    ? videoFmt.getInteger(MediaFormat.KEY_HEIGHT) : 720;
            int[] dim  = scaleDim(srcW, srcH, MAX_VIDEO_PX);
            int   outW = dim[0], outH = dim[1];

            // ── 3. Set up encoder ────────────────────────────────
            MediaFormat encFmt = MediaFormat.createVideoFormat("video/avc", outW, outH);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
            encFmt.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS);
            encFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SECS);

            encoder = MediaCodec.createEncoderByType("video/avc");
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encSurf = encoder.createInputSurface();
            encoder.start();

            // ── 4. Set up decoder ────────────────────────────────
            String videoMime = videoFmt.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(videoMime);
            decoder.configure(videoFmt, encSurf, null, 0);
            decoder.start();

            // ── 5. Set up muxer ──────────────────────────────────
            muxer = new MediaMuxer(out.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Audio track pre-add
            int muxAudioIdx = -1;
            if (audioTrack >= 0 && audioFmt != null) {
                muxAudioIdx = muxer.addTrack(audioFmt);
            }

            // ── 6. Video transcode loop ──────────────────────────
            videoEx.selectTrack(videoTrack);
            int muxVideoIdx = runVideoLoop(videoEx, decoder, encoder, muxer,
                    muxAudioIdx >= 0);

            // ── 7. Copy audio track ──────────────────────────────
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
            return src; // fallback — original file return karo
        } finally {
            safeRelease(decoder);
            safeRelease(encoder);
            if (encSurf != null) try { encSurf.release(); } catch (Exception ignored) {}
            try { if (videoEx != null) videoEx.release(); } catch (Exception ignored) {}
            try { if (audioEx != null) audioEx.release(); } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private static int runVideoLoop(MediaExtractor ex, MediaCodec dec,
                                    MediaCodec enc, MediaMuxer muxer,
                                    boolean hasAudio) throws Exception {
        boolean decInputDone = false, decOutputDone = false, encDone = false;
        int     muxVideoIdx  = -1;
        boolean muxStarted   = false;

        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo decInfo = new MediaCodec.BufferInfo();

        while (!encDone) {
            // Feed compressed data to decoder
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
                        dec.queueInputBuffer(inIdx, 0, n,
                                ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }

            // Decoder → render to encoder surface
            if (!decOutputDone) {
                int outIdx = dec.dequeueOutputBuffer(decInfo, 10_000);
                if (outIdx >= 0) {
                    boolean eos = (decInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    dec.releaseOutputBuffer(outIdx, true); // render to encSurf
                    if (eos) {
                        enc.signalEndOfInputStream();
                        decOutputDone = true;
                    }
                }
            }

            // Read encoded data
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
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encDone = true;
                }
            }
        }
        return muxVideoIdx;
    }

    private static void copyAudio(MediaExtractor audioEx, MediaMuxer muxer,
                                   int muxAudioIdx) {
        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int n = audioEx.readSampleData(buf, 0);
            if (n < 0) break;
            info.offset         = 0;
            info.size           = n;
            info.presentationTimeUs = audioEx.getSampleTime();
            info.flags          = audioEx.getSampleFlags();
            muxer.writeSampleData(muxAudioIdx, buf, info);
            audioEx.advance();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────

    private static int calcSampleSize(int w, int h, int maxPx) {
        int sample = 1;
        while ((w / (sample * 2)) > maxPx || (h / (sample * 2)) > maxPx) sample *= 2;
        return sample;
    }

    private static Bitmap scaleBitmap(Bitmap src, int maxPx) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxPx && h <= maxPx) return src;
        float ratio = Math.min((float) maxPx / w, (float) maxPx / h);
        Bitmap scaled = Bitmap.createScaledBitmap(src,
                Math.round(w * ratio), Math.round(h * ratio), true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    private static int[] scaleDim(int w, int h, int maxPx) {
        if (w <= maxPx && h <= maxPx) return new int[]{w, h};
        float r = Math.min((float) maxPx / w, (float) maxPx / h);
        // Keep divisible by 2 (codec requirement)
        return new int[]{Math.round(w * r) & ~1, Math.round(h * r) & ~1};
    }

    private static boolean copyUriToFile(Context ctx, Uri uri, File out) {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (is == null) return false;
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            return out.length() > 0;
        } catch (IOException e) {
            Log.e(TAG, "copyUriToFile failed: " + e.getMessage());
            return false;
        }
    }

    private static void safeRelease(MediaCodec codec) {
        if (codec == null) return;
        try { codec.stop();    } catch (Exception ignored) {}
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
