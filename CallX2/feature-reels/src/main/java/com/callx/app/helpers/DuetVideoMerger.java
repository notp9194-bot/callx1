package com.callx.app.helpers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DuetVideoMerger — Composites two videos side-by-side into a single MP4.
 *
 * Pipeline:
 *  1. (Optional) Download original URL → local cache file
 *  2. Decode both video tracks frame-by-frame via MediaExtractor + MediaCodec
 *  3. Composite each pair of frames side-by-side on a Bitmap using Canvas
 *  4. Re-encode composite frames → H.264 with MediaCodec
 *  5. Mix audio PCM from both inputs (original 50%, user mic 50%)
 *  6. Encode mixed PCM → AAC via MediaCodec
 *  7. Mux video + audio → output MP4 via MediaMuxer
 *
 * Uses only Android built-in APIs — no FFmpeg required.
 * Mirrors the AudioMixHelper pattern already in this project.
 */
public class DuetVideoMerger {

    private static final String TAG = "DuetVideoMerger";

    private static final int OUTPUT_WIDTH  = 720;
    private static final int OUTPUT_HEIGHT = 1280;
    private static final int HALF_W        = OUTPUT_WIDTH / 2;
    private static final int VIDEO_BIT_RATE = 3_500_000;
    private static final int FRAME_RATE     = 30;
    private static final int I_FRAME_INTERVAL = 1;
    private static final int AUDIO_SAMPLE_RATE = 44100;
    private static final int AUDIO_BIT_RATE    = 128_000;
    private static final int AUDIO_CHANNELS    = 2;
    private static final int TIMEOUT_US        = 10_000;

    public interface MergeCallback {
        void onProgress(int percent);
        void onSuccess(String outputPath);
        void onError(Exception e);
    }

    private static final ExecutorService executor  = Executors.newSingleThreadExecutor();
    private static final Handler         mainThread = new Handler(Looper.getMainLooper());

    /**
     * Download a remote URL to a local cache file, then merge.
     * Call this when originalPath is an http/https URL.
     */
    public static void downloadAndMerge(Context ctx,
                                        String originalUrl,
                                        String userVideoPath,
                                        String outputPath,
                                        MergeCallback cb) {
        executor.execute(() -> {
            try {
                File cached = new File(ctx.getCacheDir(), "duet_orig_" + System.currentTimeMillis() + ".mp4");
                downloadToFile(originalUrl, cached);
                mergeInternal(cached.getAbsolutePath(), userVideoPath, outputPath, cb);
            } catch (Exception e) {
                Log.e(TAG, "downloadAndMerge failed", e);
                mainThread.post(() -> cb.onError(e));
            }
        });
    }

    /**
     * Merge two already-local video files side-by-side.
     */
    public static void merge(String originalPath,
                             String userVideoPath,
                             String outputPath,
                             MergeCallback cb) {
        executor.execute(() -> mergeInternal(originalPath, userVideoPath, outputPath, cb));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core merge implementation
    // ─────────────────────────────────────────────────────────────────────────

    private static void mergeInternal(String leftPath, String rightPath,
                                      String outputPath, MergeCallback cb) {
        MediaMuxer      muxer     = null;
        MediaCodec      vidEnc    = null;
        MediaCodec      audEnc    = null;
        MediaCodec      leftDec   = null;
        MediaCodec      rightDec  = null;
        MediaExtractor  leftEx    = null;
        MediaExtractor  rightEx   = null;
        MediaExtractor  leftAudEx = null;
        MediaExtractor  rightAudEx= null;

        try {
            mainThread.post(() -> cb.onProgress(5));

            // ── 1. Open extractors ────────────────────────────────────────
            leftEx    = new MediaExtractor();
            rightEx   = new MediaExtractor();
            leftEx.setDataSource(leftPath);
            rightEx.setDataSource(rightPath);

            int leftVidTrack  = findTrack(leftEx,  "video/");
            int rightVidTrack = findTrack(rightEx, "video/");
            if (leftVidTrack < 0 || rightVidTrack < 0) throw new IOException("No video track");

            MediaFormat leftVidFmt  = leftEx.getTrackFormat(leftVidTrack);
            MediaFormat rightVidFmt = rightEx.getTrackFormat(rightVidTrack);
            long totalDurationUs = Math.max(
                leftVidFmt.getLong(MediaFormat.KEY_DURATION),
                rightVidFmt.getLong(MediaFormat.KEY_DURATION));

            // ── 2. Create decoders ────────────────────────────────────────
            String leftMime  = leftVidFmt.getString(MediaFormat.KEY_MIME);
            String rightMime = rightVidFmt.getString(MediaFormat.KEY_MIME);
            leftDec  = MediaCodec.createDecoderByType(leftMime);
            rightDec = MediaCodec.createDecoderByType(rightMime);

            leftDec.configure(leftVidFmt,  null, null, 0);
            rightDec.configure(rightVidFmt, null, null, 0);
            leftDec.start();
            rightDec.start();
            leftEx.selectTrack(leftVidTrack);
            rightEx.selectTrack(rightVidTrack);

            // ── 3. Create H.264 encoder ───────────────────────────────────
            MediaFormat encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                OUTPUT_WIDTH, OUTPUT_HEIGHT);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE,         VIDEO_BIT_RATE);
            encFmt.setInteger(MediaFormat.KEY_FRAME_RATE,       FRAME_RATE);
            encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
            encFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            vidEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            vidEnc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            vidEnc.start();

            // ── 4. Create muxer ───────────────────────────────────────────
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // ── 5. Mix video frames ───────────────────────────────────────
            mainThread.post(() -> cb.onProgress(10));
            int muxVideoTrack = -1;
            boolean muxerStarted = false;
            boolean leftEos  = false;
            boolean rightEos = false;

            Bitmap leftBmp  = Bitmap.createBitmap(HALF_W, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888);
            Bitmap rightBmp = Bitmap.createBitmap(HALF_W, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888);
            Bitmap outBmp   = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas   = new Canvas(outBmp);
            Paint  paint    = new Paint(Paint.FILTER_BITMAP_FLAG);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long frameCount  = 0;
            long estFrames   = (totalDurationUs / 1_000_000L) * FRAME_RATE;
            if (estFrames <= 0) estFrames = 900;

            // Feed both decoders simultaneously, drain frames in lock-step
            while (!leftEos || !rightEos) {
                // Feed left decoder
                if (!leftEos) {
                    int inIdx = leftDec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer buf = leftDec.getInputBuffer(inIdx);
                        int sz = leftEx.readSampleData(buf, 0);
                        if (sz < 0) {
                            leftDec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            leftDec.queueInputBuffer(inIdx, 0, sz, leftEx.getSampleTime(), 0);
                            leftEx.advance();
                        }
                    }
                }
                // Feed right decoder
                if (!rightEos) {
                    int inIdx = rightDec.dequeueInputBuffer(TIMEOUT_US);
                    if (inIdx >= 0) {
                        ByteBuffer buf = rightDec.getInputBuffer(inIdx);
                        int sz = rightEx.readSampleData(buf, 0);
                        if (sz < 0) {
                            rightDec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            rightDec.queueInputBuffer(inIdx, 0, sz, rightEx.getSampleTime(), 0);
                            rightEx.advance();
                        }
                    }
                }

                // Drain left decoder
                int leftOut = leftDec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (leftOut >= 0) {
                    ByteBuffer decoded = leftDec.getOutputBuffer(leftOut);
                    bitmapFromYuv(decoded, info, leftBmp);
                    leftDec.releaseOutputBuffer(leftOut, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) leftEos = true;
                }

                // Drain right decoder
                int rightOut = rightDec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (rightOut >= 0) {
                    ByteBuffer decoded = rightDec.getOutputBuffer(rightOut);
                    bitmapFromYuv(decoded, info, rightBmp);
                    rightDec.releaseOutputBuffer(rightOut, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) rightEos = true;
                }

                // Composite and encode one output frame
                canvas.drawBitmap(leftBmp,  new android.graphics.Rect(0, 0, HALF_W, OUTPUT_HEIGHT),
                    new android.graphics.RectF(0, 0, HALF_W, OUTPUT_HEIGHT), paint);
                canvas.drawBitmap(rightBmp, new android.graphics.Rect(0, 0, HALF_W, OUTPUT_HEIGHT),
                    new android.graphics.RectF(HALF_W, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT), paint);

                // Feed composite bitmap to encoder
                int encIn = vidEnc.dequeueInputBuffer(TIMEOUT_US);
                if (encIn >= 0) {
                    ByteBuffer encBuf = vidEnc.getInputBuffer(encIn);
                    yuv420FromBitmap(outBmp, encBuf);
                    long pts = frameCount * 1_000_000L / FRAME_RATE;
                    if (leftEos && rightEos) {
                        vidEnc.queueInputBuffer(encIn, 0, encBuf.limit(), pts,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        vidEnc.queueInputBuffer(encIn, 0, encBuf.limit(), pts, 0);
                    }
                    frameCount++;
                }

                // Drain encoder → mux
                MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
                int encOut = vidEnc.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxVideoTrack = muxer.addTrack(vidEnc.getOutputFormat());
                    // Audio track will be added after PCM mixing
                }
                if (encOut >= 0) {
                    if (!muxerStarted && muxVideoTrack >= 0) {
                        muxer.start();
                        muxerStarted = true;
                    }
                    if (muxerStarted && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        muxer.writeSampleData(muxVideoTrack,
                            vidEnc.getOutputBuffer(encOut), encInfo);
                    }
                    vidEnc.releaseOutputBuffer(encOut, false);
                }

                // Progress update
                int pct = 10 + (int) (frameCount * 60L / estFrames);
                if (frameCount % 30 == 0) mainThread.post(() -> cb.onProgress(Math.min(pct, 70)));
            }

            leftBmp.recycle();
            rightBmp.recycle();
            outBmp.recycle();

            mainThread.post(() -> cb.onProgress(75));

            // ── 6. Mix audio ──────────────────────────────────────────────
            leftAudEx  = new MediaExtractor();
            rightAudEx = new MediaExtractor();
            leftAudEx.setDataSource(leftPath);
            rightAudEx.setDataSource(rightPath);

            int leftAudTrack  = findTrack(leftAudEx,  "audio/");
            int rightAudTrack = findTrack(rightAudEx, "audio/");

            if (leftAudTrack >= 0 && rightAudTrack >= 0) {
                leftAudEx.selectTrack(leftAudTrack);
                rightAudEx.selectTrack(rightAudTrack);

                short[] leftPcm  = extractPcm(leftAudEx,  leftAudEx.getTrackFormat(leftAudTrack));
                short[] rightPcm = extractPcm(rightAudEx, rightAudEx.getTrackFormat(rightAudTrack));

                int len = Math.max(leftPcm.length, rightPcm.length);
                short[] mixed = new short[len];
                for (int i = 0; i < len; i++) {
                    float l = (i < leftPcm.length)  ? leftPcm[i]  * 0.5f : 0f;
                    float r = (i < rightPcm.length) ? rightPcm[i] * 0.5f : 0f;
                    float s = l + r;
                    mixed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) s));
                }

                // Encode mixed PCM → AAC
                String mixedAacPath = outputPath + "_aud.aac";
                encodePcmToAac(mixed, mixedAacPath);

                // Remux: video track already written; add audio to final output
                muxAudioIntoMp4(outputPath, mixedAacPath, outputPath + "_final.mp4");
                new File(outputPath).delete();
                new File(mixedAacPath).delete();
                new File(outputPath + "_final.mp4").renameTo(new File(outputPath));
            }

            mainThread.post(() -> cb.onProgress(100));
            mainThread.post(() -> cb.onSuccess(outputPath));

        } catch (Exception e) {
            Log.e(TAG, "mergeInternal failed", e);
            mainThread.post(() -> cb.onError(e));
        } finally {
            safeStop(vidEnc);  safeStop(audEnc);
            safeStop(leftDec); safeStop(rightDec);
            safeRelease(leftEx); safeRelease(rightEx);
            safeRelease(leftAudEx); safeRelease(rightAudEx);
            if (muxer != null) { try { muxer.stop(); muxer.release(); } catch (Exception ignored) {} }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Audio helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static short[] extractPcm(MediaExtractor ex, MediaFormat fmt) throws IOException {
        String mime = fmt.getString(MediaFormat.KEY_MIME);
        MediaCodec dec = MediaCodec.createDecoderByType(mime);
        dec.configure(fmt, null, null, 0);
        dec.start();

        java.util.ArrayList<Short> samples = new java.util.ArrayList<>(1_000_000);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eos = false;

        while (!eos) {
            int inIdx = dec.dequeueInputBuffer(TIMEOUT_US);
            if (inIdx >= 0) {
                ByteBuffer buf = dec.getInputBuffer(inIdx);
                int sz = ex.readSampleData(buf, 0);
                if (sz < 0) {
                    dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                } else {
                    dec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                    ex.advance();
                }
            }
            int outIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx >= 0) {
                ByteBuffer pcmBuf = dec.getOutputBuffer(outIdx);
                ShortBuffer sb = pcmBuf.asShortBuffer();
                while (sb.hasRemaining()) samples.add(sb.get());
                dec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
            }
        }
        safeStop(dec);

        short[] result = new short[samples.size()];
        for (int i = 0; i < result.length; i++) result[i] = samples.get(i);
        return result;
    }

    private static void encodePcmToAac(short[] pcm, String outPath) throws IOException {
        MediaFormat fmt = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        FileOutputStream fos = new FileOutputStream(outPath);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int offset = 0;
        boolean eos = false;
        final int INPUT_CHUNK = 1024;

        while (!eos) {
            int inIdx = enc.dequeueInputBuffer(TIMEOUT_US);
            if (inIdx >= 0 && offset < pcm.length) {
                ByteBuffer buf = enc.getInputBuffer(inIdx);
                int chunk = Math.min(INPUT_CHUNK, pcm.length - offset);
                buf.clear();
                for (int i = 0; i < chunk; i++) buf.putShort(pcm[offset + i]);
                buf.flip();
                long pts = (long) offset * 1_000_000L / (AUDIO_SAMPLE_RATE * AUDIO_CHANNELS);
                boolean last = (offset + chunk) >= pcm.length;
                enc.queueInputBuffer(inIdx, 0, buf.limit(), pts,
                    last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                offset += chunk;
            }
            int outIdx = enc.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx >= 0) {
                ByteBuffer aacBuf = enc.getOutputBuffer(outIdx);
                // Write ADTS header for each AAC frame
                byte[] adts = new byte[7];
                writeAdtsHeader(adts, info.size);
                fos.write(adts);
                byte[] data = new byte[info.size];
                aacBuf.get(data);
                fos.write(data);
                enc.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
            }
        }
        fos.close();
        safeStop(enc);
    }

    private static void writeAdtsHeader(byte[] header, int dataLen) {
        int totalLen = dataLen + 7;
        header[0] = (byte) 0xFF;
        header[1] = (byte) 0xF1;
        header[2] = (byte) (((0x02 - 1) << 6) | (3 << 2) | (AUDIO_CHANNELS >> 2));
        header[3] = (byte) (((AUDIO_CHANNELS & 3) << 6) | (totalLen >> 11));
        header[4] = (byte) ((totalLen & 0x7FF) >> 3);
        header[5] = (byte) (((totalLen & 7) << 5) | 0x1F);
        header[6] = (byte) 0xFC;
    }

    private static void muxAudioIntoMp4(String videoOnlyMp4,
                                        String aacPath,
                                        String outputMp4) throws IOException {
        MediaExtractor vidEx = new MediaExtractor();
        MediaExtractor audEx = new MediaExtractor();
        vidEx.setDataSource(videoOnlyMp4);
        audEx.setDataSource(aacPath);

        int vt = findTrack(vidEx, "video/");
        int at = findTrack(audEx, "audio/");
        vidEx.selectTrack(vt);
        if (at >= 0) audEx.selectTrack(at);

        MediaMuxer muxer = new MediaMuxer(outputMp4, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxVid = muxer.addTrack(vidEx.getTrackFormat(vt));
        int muxAud = (at >= 0) ? muxer.addTrack(audEx.getTrackFormat(at)) : -1;
        muxer.start();

        ByteBuffer buf = ByteBuffer.allocate(1 << 20);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // Copy video
        while (true) {
            buf.clear();
            int sz = vidEx.readSampleData(buf, 0);
            if (sz < 0) break;
            info.presentationTimeUs = vidEx.getSampleTime();
            info.size   = sz;
            info.offset = 0;
            info.flags  = vidEx.getSampleFlags();
            muxer.writeSampleData(muxVid, buf, info);
            vidEx.advance();
        }
        // Copy audio
        if (muxAud >= 0) {
            while (true) {
                buf.clear();
                int sz = audEx.readSampleData(buf, 0);
                if (sz < 0) break;
                info.presentationTimeUs = audEx.getSampleTime();
                info.size   = sz;
                info.offset = 0;
                info.flags  = audEx.getSampleFlags();
                muxer.writeSampleData(muxAud, buf, info);
                audEx.advance();
            }
        }

        muxer.stop();
        muxer.release();
        vidEx.release();
        audEx.release();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bitmap / YUV helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static void bitmapFromYuv(ByteBuffer yuv, MediaCodec.BufferInfo info, Bitmap out) {
        if (yuv == null || info.size <= 0) return;
        int w = out.getWidth();
        int h = out.getHeight();
        byte[] data = new byte[info.size];
        yuv.position(info.offset);
        yuv.get(data, 0, Math.min(info.size, data.length));
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int yVal = data[y * w + x] & 0xFF;
                int uvOffset = w * h + (y / 2) * w + (x & ~1);
                if (uvOffset + 1 >= data.length) { pixels[y * w + x] = 0xFF000000; continue; }
                int u = (data[uvOffset]     & 0xFF) - 128;
                int v = (data[uvOffset + 1] & 0xFF) - 128;
                int r = Math.max(0, Math.min(255, (int)(yVal + 1.402f  * v)));
                int g = Math.max(0, Math.min(255, (int)(yVal - 0.344f  * u - 0.714f * v)));
                int b = Math.max(0, Math.min(255, (int)(yVal + 1.772f  * u)));
                pixels[y * w + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static void yuv420FromBitmap(Bitmap bmp, ByteBuffer out) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        out.clear();
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8)  & 0xFF;
            int b = p & 0xFF;
            out.put((byte) Math.max(0, Math.min(255, (int)(0.299f*r + 0.587f*g + 0.114f*b))));
        }
        for (int row = 0; row < h; row += 2) {
            for (int col = 0; col < w; col += 2) {
                int p = pixels[row * w + col];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8)  & 0xFF;
                int b = p & 0xFF;
                out.put((byte) Math.max(0, Math.min(255, (int)(-0.169f*r - 0.331f*g + 0.5f  *b + 128))));
                out.put((byte) Math.max(0, Math.min(255, (int)( 0.5f  *r - 0.419f*g - 0.081f*b + 128))));
            }
        }
        out.flip();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static int findTrack(MediaExtractor ex, String mimePrefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private static void downloadToFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.connect();
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
            throw new IOException("HTTP " + conn.getResponseCode());
        try (InputStream in  = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
    }

    private static void safeStop(MediaCodec codec) {
        if (codec == null) return;
        try { codec.stop();    } catch (Exception ignored) {}
        try { codec.release(); } catch (Exception ignored) {}
    }

    private static void safeRelease(MediaExtractor ex) {
        if (ex == null) return;
        try { ex.release(); } catch (Exception ignored) {}
    }
}
