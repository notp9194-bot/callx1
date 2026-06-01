package com.callx.app.helpers;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * DuetVideoMerger v2 — Reliable side-by-side compositor using Surface-based decode.
 *
 * Pipeline:
 *  1. (Optional) Download original URL → local cache file
 *  2. Decode both video tracks via MediaCodec → ImageReader surfaces (YUV_420_888)
 *  3. Composite each frame pair side-by-side using Canvas
 *  4. Re-encode via MediaCodec using getInputImage() (handles all YUV layouts)
 *  5. Passthrough audio from user's recorded video (avoids PCM decode complexity)
 *  6. Mux video + audio → output MP4
 *
 * v2 fixes over v1:
 *  ✅ Surface-based decode: eliminates YUV-format guessing (NV12 vs NV21 device variance)
 *  ✅ getInputImage() for encoder: handles all stride/pixel-stride layouts correctly
 *  ✅ Actual frame dimensions from MediaFormat (not assumed HALF_W × HEIGHT)
 *  ✅ Safe Semaphore coordination between decoder surface and ImageReader
 *  ✅ Fallback: if merge fails for any reason, callback receives the error and
 *     DuetReelActivity already falls back to user video without showing an error toast
 */
public class DuetVideoMerger {

    private static final String TAG = "DuetVideoMerger";

    private static final int OUTPUT_WIDTH    = 720;
    private static final int OUTPUT_HEIGHT   = 1280;
    private static final int HALF_W          = OUTPUT_WIDTH / 2;
    private static final int VIDEO_BIT_RATE  = 4_000_000;
    private static final int FRAME_RATE      = 30;
    private static final int I_FRAME_INT     = 1;
    private static final long TIMEOUT_US     = 10_000L;
    private static final long SEM_TIMEOUT_MS = 500L;

    // ─── Public API ───────────────────────────────────────────────────────────

    public interface MergeCallback {
        void onProgress(int percent);
        void onSuccess(String outputPath);
        void onError(Exception e);
    }

    private static final ExecutorService executor   = Executors.newSingleThreadExecutor();
    private static final Handler         mainThread = new Handler(Looper.getMainLooper());

    /** Download originalUrl → local cache, then merge. */
    public static void downloadAndMerge(android.content.Context ctx,
                                        String originalUrl,
                                        String userVideoPath,
                                        String outputPath,
                                        MergeCallback cb) {
        executor.execute(() -> {
            try {
                File cached = new File(ctx.getCacheDir(),
                    "duet_orig_" + System.currentTimeMillis() + ".mp4");
                downloadToFile(originalUrl, cached);
                mergeInternal(cached.getAbsolutePath(), userVideoPath, outputPath, cb);
            } catch (Exception e) {
                Log.e(TAG, "downloadAndMerge failed", e);
                mainThread.post(() -> cb.onError(e));
            }
        });
    }

    /** Merge two already-local video files side-by-side. */
    public static void merge(String originalPath,
                             String userVideoPath,
                             String outputPath,
                             MergeCallback cb) {
        executor.execute(() -> mergeInternal(originalPath, userVideoPath, outputPath, cb));
    }

    // ─── Core implementation ──────────────────────────────────────────────────

    private static void mergeInternal(String leftPath, String rightPath,
                                      String outputPath, MergeCallback cb) {

        HandlerThread bgThread = new HandlerThread("DuetMerge-BG");
        bgThread.start();
        Handler bgHandler = new Handler(bgThread.getLooper());

        ImageReader leftReader  = null;
        ImageReader rightReader = null;
        MediaCodec  leftDec     = null;
        MediaCodec  rightDec    = null;
        MediaCodec  vidEnc      = null;
        MediaExtractor leftEx   = null;
        MediaExtractor rightEx  = null;
        MediaMuxer muxer        = null;

        try {
            mainThread.post(() -> cb.onProgress(5));

            // ── 1. Probe input dimensions ─────────────────────────────────────
            leftEx  = new MediaExtractor();
            rightEx = new MediaExtractor();
            leftEx.setDataSource(leftPath);
            rightEx.setDataSource(rightPath);

            int leftVidTrack  = findTrack(leftEx,  "video/");
            int rightVidTrack = findTrack(rightEx, "video/");
            if (leftVidTrack < 0 || rightVidTrack < 0)
                throw new IOException("No video track found");

            MediaFormat leftFmt  = leftEx.getTrackFormat(leftVidTrack);
            MediaFormat rightFmt = rightEx.getTrackFormat(rightVidTrack);

            int leftW  = leftFmt.getInteger(MediaFormat.KEY_WIDTH);
            int leftH  = leftFmt.getInteger(MediaFormat.KEY_HEIGHT);
            int rightW = rightFmt.getInteger(MediaFormat.KEY_WIDTH);
            int rightH = rightFmt.getInteger(MediaFormat.KEY_HEIGHT);

            long totalDurUs = Math.max(
                safeGetLong(leftFmt,  MediaFormat.KEY_DURATION, 30_000_000L),
                safeGetLong(rightFmt, MediaFormat.KEY_DURATION, 30_000_000L));
            long estFrames = (totalDurUs / 1_000_000L) * FRAME_RATE;
            if (estFrames <= 0) estFrames = 900;

            // ── 2. ImageReaders for Surface-based decode ──────────────────────
            // YUV_420_888 is guaranteed on API 21+; handles NV12/NV21/I420 transparently
            final Semaphore leftSem  = new Semaphore(0);
            final Semaphore rightSem = new Semaphore(0);

            leftReader  = ImageReader.newInstance(leftW,  leftH,
                android.graphics.ImageFormat.YUV_420_888, 3);
            rightReader = ImageReader.newInstance(rightW, rightH,
                android.graphics.ImageFormat.YUV_420_888, 3);

            leftReader.setOnImageAvailableListener(r -> leftSem.release(),  bgHandler);
            rightReader.setOnImageAvailableListener(r -> rightSem.release(), bgHandler);

            // ── 3. Create decoders → render to ImageReader surfaces ───────────
            leftDec = MediaCodec.createDecoderByType(
                leftFmt.getString(MediaFormat.KEY_MIME));
            rightDec = MediaCodec.createDecoderByType(
                rightFmt.getString(MediaFormat.KEY_MIME));

            leftDec.configure(leftFmt,  leftReader.getSurface(),  null, 0);
            rightDec.configure(rightFmt, rightReader.getSurface(), null, 0);
            leftDec.start();
            rightDec.start();
            leftEx.selectTrack(leftVidTrack);
            rightEx.selectTrack(rightVidTrack);

            // ── 4. Create H.264 encoder (ByteBuffer + getInputImage) ──────────
            MediaFormat encFmt = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, OUTPUT_WIDTH, OUTPUT_HEIGHT);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE,         VIDEO_BIT_RATE);
            encFmt.setInteger(MediaFormat.KEY_FRAME_RATE,       FRAME_RATE);
            encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INT);
            encFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            vidEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            vidEnc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            vidEnc.start();

            // ── 5. Muxer (video track only for now — audio added in pass 2) ───
            String videoOnlyPath = outputPath + "_vid.mp4";
            muxer = new MediaMuxer(videoOnlyPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // ── 6. Frame loop ─────────────────────────────────────────────────
            mainThread.post(() -> cb.onProgress(10));

            Bitmap compositeBmp = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT,
                Bitmap.Config.ARGB_8888);
            Canvas  canvas = new Canvas(compositeBmp);
            Paint   paint  = new Paint(Paint.FILTER_BITMAP_FLAG);

            MediaCodec.BufferInfo info        = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo encInfo     = new MediaCodec.BufferInfo();
            boolean leftEos    = false;
            boolean rightEos   = false;
            int     muxVidTrack = -1;
            boolean muxStarted = false;
            long    frameCount = 0;
            Bitmap  lastLeft   = null;
            Bitmap  lastRight  = null;

            while (!leftEos || !rightEos) {

                // Feed left decoder
                if (!leftEos) feedDecoder(leftDec, leftEx);
                // Feed right decoder
                if (!rightEos) feedDecoder(rightDec, rightEx);

                // ── Drain left decoder ────────────────────────────────────────
                int leftOut = leftDec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (leftOut >= 0) {
                    boolean render = (info.size > 0);
                    leftDec.releaseOutputBuffer(leftOut, render);
                    if (render) {
                        // Wait for ImageReader to receive the rendered frame
                        boolean got = leftSem.tryAcquire(SEM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        if (got) {
                            Image img = leftReader.acquireLatestImage();
                            if (img != null) {
                                Bitmap bmp = imageToBitmap(img);
                                img.close();
                                if (lastLeft != null && lastLeft != bmp) lastLeft.recycle();
                                lastLeft = bmp;
                            }
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) leftEos = true;
                } else if (leftOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // format changed — no action needed for Surface decode
                }

                // ── Drain right decoder ───────────────────────────────────────
                int rightOut = rightDec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (rightOut >= 0) {
                    boolean render = (info.size > 0);
                    rightDec.releaseOutputBuffer(rightOut, render);
                    if (render) {
                        boolean got = rightSem.tryAcquire(SEM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        if (got) {
                            Image img = rightReader.acquireLatestImage();
                            if (img != null) {
                                Bitmap bmp = imageToBitmap(img);
                                img.close();
                                if (lastRight != null && lastRight != bmp) lastRight.recycle();
                                lastRight = bmp;
                            }
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) rightEos = true;
                } else if (rightOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // format changed — no action needed
                }

                // Only compose if we have at least one frame from each side
                if (lastLeft == null || lastRight == null) continue;

                // ── Composite side-by-side ────────────────────────────────────
                canvas.drawColor(0xFF000000);
                canvas.drawBitmap(lastLeft,
                    new Rect(0, 0, lastLeft.getWidth(),  lastLeft.getHeight()),
                    new RectF(0, 0, HALF_W, OUTPUT_HEIGHT), paint);
                canvas.drawBitmap(lastRight,
                    new Rect(0, 0, lastRight.getWidth(), lastRight.getHeight()),
                    new RectF(HALF_W, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT), paint);

                // ── Feed composite to encoder ─────────────────────────────────
                long pts = frameCount * 1_000_000L / FRAME_RATE;
                int encIn = vidEnc.dequeueInputBuffer(TIMEOUT_US);
                if (encIn >= 0) {
                    Image encImage = vidEnc.getInputImage(encIn);
                    if (encImage != null) {
                        bitmapToImage(compositeBmp, encImage);
                        int flags = (leftEos && rightEos)
                            ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
                        vidEnc.queueInputBuffer(encIn, 0, 0, pts, flags);
                    } else {
                        // Fallback: manual NV12 fill
                        ByteBuffer buf = vidEnc.getInputBuffer(encIn);
                        bitmapToNv12(compositeBmp, buf);
                        int flags = (leftEos && rightEos)
                            ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
                        vidEnc.queueInputBuffer(encIn, 0, buf.limit(), pts, flags);
                    }
                    frameCount++;
                }

                // ── Drain encoder → mux ───────────────────────────────────────
                int encOut = vidEnc.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxVidTrack = muxer.addTrack(vidEnc.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                }
                if (encOut >= 0) {
                    if (muxStarted && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        muxer.writeSampleData(muxVidTrack,
                            vidEnc.getOutputBuffer(encOut), encInfo);
                    }
                    vidEnc.releaseOutputBuffer(encOut, false);
                }

                // Progress
                int pct = 10 + (int)(frameCount * 60L / estFrames);
                if (frameCount % 15 == 0)
                    mainThread.post(() -> cb.onProgress(Math.min(pct, 70)));
            }

            // Drain remaining encoder output after EOS
            boolean encDone = false;
            while (!encDone) {
                int encOut = vidEnc.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && muxVidTrack < 0) {
                    muxVidTrack = muxer.addTrack(vidEnc.getOutputFormat());
                    muxer.start(); muxStarted = true;
                }
                if (encOut >= 0) {
                    if (muxStarted && (encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0)
                        muxer.writeSampleData(muxVidTrack,
                            vidEnc.getOutputBuffer(encOut), encInfo);
                    vidEnc.releaseOutputBuffer(encOut, false);
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        encDone = true;
                } else if (encOut == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break; // no more output
                }
            }

            if (lastLeft  != null) lastLeft.recycle();
            if (lastRight != null) lastRight.recycle();
            compositeBmp.recycle();

            mainThread.post(() -> cb.onProgress(75));

            // Release encoders/decoders before muxer stop
            safeStop(vidEnc);  vidEnc = null;
            safeStop(leftDec); leftDec = null;
            safeStop(rightDec); rightDec = null;
            muxer.stop(); muxer.release(); muxer = null;

            // ── 7. Pass 2: passthrough audio from user's recording ────────────
            // We only take audio from rightPath (user's CameraX recording).
            // This is simpler and more reliable than full PCM mix,
            // and matches what the user actually recorded.
            mainThread.post(() -> cb.onProgress(80));
            muxVideoWithAudio(videoOnlyPath, rightPath, outputPath);
            new File(videoOnlyPath).delete();

            mainThread.post(() -> cb.onProgress(100));
            mainThread.post(() -> cb.onSuccess(outputPath));

        } catch (Exception e) {
            Log.e(TAG, "mergeInternal failed", e);
            mainThread.post(() -> cb.onError(e));
        } finally {
            bgThread.quitSafely();
            safeClose(leftReader);
            safeClose(rightReader);
            safeStop(leftDec);
            safeStop(rightDec);
            safeStop(vidEnc);
            safeRelease(leftEx);
            safeRelease(rightEx);
            if (muxer != null) {
                try { muxer.stop(); muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    // ─── Image → Bitmap (YUV_420_888, handles all stride/pixel-stride) ────────

    private static Bitmap imageToBitmap(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer yBuf  = planes[0].getBuffer();
        ByteBuffer uBuf  = planes[1].getBuffer();
        ByteBuffer vBuf  = planes[2].getBuffer();
        int yRowStride   = planes[0].getRowStride();
        int uvRowStride  = planes[1].getRowStride();
        int uvPixStride  = planes[1].getPixelStride();

        int[] argb = new int[w * h];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int yIdx  = row * yRowStride + col;
                int uvIdx = (row / 2) * uvRowStride + (col / 2) * uvPixStride;

                int yv = (yBuf.capacity() > yIdx) ? (yBuf.get(yIdx) & 0xFF) : 0;
                int u  = (uBuf.capacity() > uvIdx) ? (uBuf.get(uvIdx) & 0xFF) - 128 : 0;
                int v  = (vBuf.capacity() > uvIdx) ? (vBuf.get(uvIdx) & 0xFF) - 128 : 0;

                int r = clamp(yv + (int)(1.402f * v));
                int g = clamp(yv - (int)(0.344f * u) - (int)(0.714f * v));
                int b = clamp(yv + (int)(1.772f * u));
                argb[row * w + col] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
    }

    // ─── Bitmap → encoder Image (handles all stride/pixel-stride layouts) ─────

    private static void bitmapToImage(Bitmap bmp, Image image) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        Image.Plane[] planes   = image.getPlanes();
        ByteBuffer yBuf        = planes[0].getBuffer();
        ByteBuffer uBuf        = planes[1].getBuffer();
        ByteBuffer vBuf        = planes[2].getBuffer();
        int yRowStride         = planes[0].getRowStride();
        int uvRowStride        = planes[1].getRowStride();
        int uvPixStride        = planes[1].getPixelStride();

        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int p = pixels[row * w + col];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8)  & 0xFF;
                int b = p & 0xFF;
                int yv = clamp((int)(0.299f*r + 0.587f*g + 0.114f*b));
                int idx = row * yRowStride + col;
                if (yBuf.capacity() > idx) yBuf.put(idx, (byte) yv);
            }
        }
        for (int row = 0; row < h; row += 2) {
            for (int col = 0; col < w; col += 2) {
                int p = pixels[row * w + col];
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8)  & 0xFF;
                int b = p & 0xFF;
                int u = clamp((int)(-0.169f*r - 0.331f*g + 0.5f  *b + 128));
                int v = clamp((int)( 0.5f  *r - 0.419f*g - 0.081f*b + 128));
                int uvIdx = (row / 2) * uvRowStride + (col / 2) * uvPixStride;
                if (uBuf.capacity() > uvIdx) uBuf.put(uvIdx, (byte) u);
                if (vBuf.capacity() > uvIdx) vBuf.put(uvIdx, (byte) v);
            }
        }
    }

    // ─── Fallback: Bitmap → NV12 ByteBuffer ──────────────────────────────────

    private static void bitmapToNv12(Bitmap bmp, ByteBuffer out) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        out.clear();
        // Y plane
        for (int p : pixels) {
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bv = p & 0xFF;
            out.put((byte) clamp((int)(0.299f*r + 0.587f*g + 0.114f*bv)));
        }
        // UV interleaved (NV12)
        for (int row = 0; row < h; row += 2) {
            for (int col = 0; col < w; col += 2) {
                int p = pixels[row * w + col];
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, bv = p & 0xFF;
                out.put((byte) clamp((int)(-0.169f*r - 0.331f*g + 0.5f  *bv + 128)));
                out.put((byte) clamp((int)( 0.5f  *r - 0.419f*g - 0.081f*bv + 128)));
            }
        }
        out.flip();
    }

    // ─── Pass 2: mux video-only MP4 with audio from source ───────────────────

    private static void muxVideoWithAudio(String videoOnlyPath,
                                          String audioSourcePath,
                                          String outputPath) throws IOException {
        MediaExtractor vidEx = new MediaExtractor();
        MediaExtractor audEx = new MediaExtractor();
        vidEx.setDataSource(videoOnlyPath);
        audEx.setDataSource(audioSourcePath);

        int vt = findTrack(vidEx, "video/");
        int at = findTrack(audEx, "audio/");
        vidEx.selectTrack(vt);

        MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxVid = muxer.addTrack(vidEx.getTrackFormat(vt));
        int muxAud = -1;
        if (at >= 0) {
            audEx.selectTrack(at);
            muxAud = muxer.addTrack(audEx.getTrackFormat(at));
        }
        muxer.start();

        ByteBuffer buf = ByteBuffer.allocate(1 << 20); // 1 MB
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // Copy video
        while (true) {
            buf.clear();
            int sz = vidEx.readSampleData(buf, 0);
            if (sz < 0) break;
            info.presentationTimeUs = vidEx.getSampleTime();
            info.size = sz; info.offset = 0;
            info.flags = vidEx.getSampleFlags();
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
                info.size = sz; info.offset = 0;
                info.flags = audEx.getSampleFlags();
                muxer.writeSampleData(muxAud, buf, info);
                audEx.advance();
            }
        }
        muxer.stop(); muxer.release();
        vidEx.release(); audEx.release();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static void feedDecoder(MediaCodec dec, MediaExtractor ex) {
        int inIdx = dec.dequeueInputBuffer(TIMEOUT_US);
        if (inIdx >= 0) {
            ByteBuffer buf = dec.getInputBuffer(inIdx);
            if (buf == null) return;
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) {
                dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            } else {
                dec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                ex.advance();
            }
        }
    }

    private static int findTrack(MediaExtractor ex, String mimePrefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private static long safeGetLong(MediaFormat fmt, String key, long fallback) {
        try { return fmt.getLong(key); } catch (Exception e) { return fallback; }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static void downloadToFile(String urlStr, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(60_000);
        conn.connect();
        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK)
            throw new IOException("HTTP " + conn.getResponseCode() + " for " + urlStr);
        try (InputStream in = conn.getInputStream();
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

    private static void safeClose(ImageReader reader) {
        if (reader == null) return;
        try { reader.close(); } catch (Exception ignored) {}
    }
}
