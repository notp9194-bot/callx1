package com.callx.app.social;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.*;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * DuetVideoCompositor
 *
 * Composites two MP4 videos (camera recording + original reel) into a single output file.
 *
 * Supported layout modes (matches DuetReelActivity constants):
 *   0 = SIDE_BY_SIDE  — videos placed left/right, equal width
 *   1 = TOP_BOTTOM    — videos placed top/bottom, equal height
 *   2 = REACT_PIP     — camera fills frame, original in bottom-left corner (30% size)
 *
 * Audio: Decodes PCM from both sources, mixes them at the given originalVolume (0..1),
 *        then encodes the mixed PCM as AAC in the output file.
 *
 * Thread: Runs synchronously. Caller MUST run this on a background thread.
 *
 * Returns true if compositing succeeded, false if it fell back to the camera file only.
 */
public class DuetVideoCompositor {

    private static final String TAG         = "DuetVideoCompositor";
    private static final long   TIMEOUT_US  = 10_000L;
    private static final int    OUTPUT_W    = 1080;
    private static final int    OUTPUT_H    = 1920;
    private static final int    VIDEO_BIT   = 6_000_000; // 6 Mbps
    private static final int    AUDIO_RATE  = 44100;
    private static final int    AUDIO_CHAN  = 2;
    private static final int    AUDIO_BIT   = 128_000;

    /**
     * @param cameraPath      Absolute path to the user's CameraX recording
     * @param originalUrl     URL or path of the original reel (must be accessible)
     * @param outputPath      Absolute path for the composited output MP4
     * @param layoutMode      0=side-by-side, 1=top-bottom, 2=pip
     * @param originalVolume  0.0..1.0 — mix level for original reel's audio
     * @return true on success
     */
    public boolean composite(String cameraPath, String originalUrl,
                             String outputPath, int layoutMode, float originalVolume) {
        try {
            return doComposite(cameraPath, originalUrl, outputPath, layoutMode, originalVolume);
        } catch (Exception e) {
            Log.e(TAG, "composite() failed: " + e.getMessage(), e);
            return false;
        }
    }

    private boolean doComposite(String cameraPath, String originalUrl,
                                String outputPath, int layoutMode, float originalVol)
            throws Exception {

        // ── Setup decoders ────────────────────────────────────────────────────
        VideoDecoder camDecoder  = new VideoDecoder(cameraPath);
        VideoDecoder origDecoder = new VideoDecoder(originalUrl);
        AudioDecoder camAudio    = new AudioDecoder(cameraPath);
        AudioDecoder origAudio   = new AudioDecoder(originalUrl);

        // ── Setup encoder ─────────────────────────────────────────────────────
        MediaFormat videoFmt = buildVideoFormat();
        MediaCodec  videoEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        videoEnc.configure(videoFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encSurface = videoEnc.createInputSurface();
        videoEnc.start();

        MediaFormat audioFmt = buildAudioFormat();
        MediaCodec  audioEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        audioEnc.configure(audioFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        audioEnc.start();

        // ── Setup muxer ───────────────────────────────────────────────────────
        MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int videoTrackIdx = -1;
        int audioTrackIdx = -1;
        boolean muxerStarted = false;

        // ── Video compositing loop ────────────────────────────────────────────
        Canvas canvas = null;
        Bitmap camFrame  = null;
        Bitmap origFrame = null;

        long presentationUs = 0;
        long frameDurationUs = 33_333; // ~30 fps

        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();

        camDecoder.start();
        origDecoder.start();

        while (true) {
            camFrame  = camDecoder.nextFrame();
            origFrame = origDecoder.nextFrame();

            if (camFrame == null && origFrame == null) break;

            // Use last valid frame if one stream ended before the other
            if (camFrame  == null && origDecoder.lastFrame != null) camFrame  = origDecoder.lastFrame;
            if (origFrame == null && camDecoder.lastFrame  != null) origFrame = camDecoder.lastFrame;
            if (camFrame == null || origFrame == null) break;

            // Draw composite to encoder surface
            canvas = encSurface.lockHardwareCanvas();
            if (canvas == null) canvas = encSurface.lockCanvas(null);
            drawComposite(canvas, camFrame, origFrame, layoutMode);
            encSurface.unlockCanvasAndPost(canvas);

            // Drain video encoder
            while (true) {
                int idx = videoEnc.dequeueOutputBuffer(encInfo, TIMEOUT_US);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    videoTrackIdx = muxer.addTrack(videoEnc.getOutputFormat());
                    if (audioTrackIdx >= 0 && !muxerStarted) { muxer.start(); muxerStarted = true; }
                    continue;
                }
                if (idx < 0) break;
                ByteBuffer buf = videoEnc.getOutputBuffer(idx);
                if (buf != null && muxerStarted) {
                    encInfo.presentationTimeUs = presentationUs;
                    muxer.writeSampleData(videoTrackIdx, buf, encInfo);
                }
                videoEnc.releaseOutputBuffer(idx, false);
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
            presentationUs += frameDurationUs;
        }

        // Signal EOS to video encoder
        videoEnc.signalEndOfInputStream();
        drainEncoder(videoEnc, muxer, videoTrackIdx, encInfo, muxerStarted);

        // ── Audio mixing ──────────────────────────────────────────────────────
        short[] camPcm  = camAudio.decodePcm();
        short[] origPcm = origAudio.decodePcm();
        short[] mixed   = mixPcm(camPcm, origPcm, originalVol);
        feedAudioEncoder(audioEnc, mixed);

        MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        while (true) {
            int idx = audioEnc.dequeueOutputBuffer(audioInfo, TIMEOUT_US);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                audioTrackIdx = muxer.addTrack(audioEnc.getOutputFormat());
                if (videoTrackIdx >= 0 && !muxerStarted) { muxer.start(); muxerStarted = true; }
                continue;
            }
            if (idx < 0) break;
            ByteBuffer buf = audioEnc.getOutputBuffer(idx);
            if (buf != null && muxerStarted && audioTrackIdx >= 0) {
                muxer.writeSampleData(audioTrackIdx, buf, audioInfo);
            }
            audioEnc.releaseOutputBuffer(idx, false);
            if ((audioInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }

        // ── Teardown ──────────────────────────────────────────────────────────
        videoEnc.stop(); videoEnc.release();
        audioEnc.stop(); audioEnc.release();
        encSurface.release();
        if (muxerStarted) { muxer.stop(); }
        muxer.release();
        camDecoder.release();
        origDecoder.release();

        Log.i(TAG, "doComposite() success → " + outputPath);
        return true;
    }

    // ── Draw composite frame ──────────────────────────────────────────────────

    private void drawComposite(Canvas canvas, Bitmap cam, Bitmap orig, int layoutMode) {
        canvas.drawARGB(255, 0, 0, 0);

        switch (layoutMode) {
            case DuetReelActivity.LAYOUT_SIDE_BY_SIDE: {
                int half = OUTPUT_W / 2;
                Rect leftRect  = new Rect(0,    0, half,     OUTPUT_H);
                Rect rightRect = new Rect(half, 0, OUTPUT_W, OUTPUT_H);
                canvas.drawBitmap(orig, null, leftRect,  null);
                canvas.drawBitmap(cam,  null, rightRect, null);
                break;
            }
            case DuetReelActivity.LAYOUT_TOP_BOTTOM: {
                int half = OUTPUT_H / 2;
                Rect topRect    = new Rect(0, 0,    OUTPUT_W, half);
                Rect bottomRect = new Rect(0, half, OUTPUT_W, OUTPUT_H);
                canvas.drawBitmap(orig, null, topRect,    null);
                canvas.drawBitmap(cam,  null, bottomRect, null);
                break;
            }
            case DuetReelActivity.LAYOUT_REACT_PIP:
            default: {
                // Camera fills full frame
                Rect fullRect = new Rect(0, 0, OUTPUT_W, OUTPUT_H);
                canvas.drawBitmap(cam, null, fullRect, null);
                // Original in bottom-left corner at 30% size with 12px margin
                int pipW = (int)(OUTPUT_W * 0.30f);
                int pipH = (int)(OUTPUT_H * 0.30f);
                int margin = 24;
                Rect pipRect = new Rect(margin, OUTPUT_H - pipH - margin,
                                        margin + pipW, OUTPUT_H - margin);
                // Semi-transparent rounded overlay for pip (just draw the frame)
                android.graphics.Paint border = new android.graphics.Paint();
                border.setColor(0xFFFFFFFF);
                border.setStyle(android.graphics.Paint.Style.STROKE);
                border.setStrokeWidth(4);
                canvas.drawBitmap(orig, null, pipRect, null);
                canvas.drawRect(pipRect, border);
                break;
            }
        }
    }

    // ── PCM audio mixing ──────────────────────────────────────────────────────

    private short[] mixPcm(short[] cam, short[] orig, float origVol) {
        int len = Math.max(cam.length, orig.length);
        short[] out = new short[len];
        for (int i = 0; i < len; i++) {
            float c = (i < cam.length)  ? cam[i]  : 0;
            float o = (i < orig.length) ? orig[i] * origVol : 0;
            float mix = c + o;
            // Clamp to short range
            if (mix > 32767)  mix = 32767;
            if (mix < -32768) mix = -32768;
            out[i] = (short) mix;
        }
        return out;
    }

    private void feedAudioEncoder(MediaCodec enc, short[] pcm) {
        byte[] bytes = new byte[pcm.length * 2];
        ByteBuffer bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) bb.putShort(s);

        int offset = 0;
        while (offset < bytes.length) {
            int inIdx = enc.dequeueInputBuffer(TIMEOUT_US);
            if (inIdx < 0) continue;
            ByteBuffer ib = enc.getInputBuffer(inIdx);
            if (ib == null) continue;
            ib.clear();
            int chunk = Math.min(ib.capacity(), bytes.length - offset);
            ib.put(bytes, offset, chunk);
            offset += chunk;
            boolean eos = offset >= bytes.length;
            enc.queueInputBuffer(inIdx, 0, chunk, 0,
                eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            if (eos) break;
        }
    }

    private void drainEncoder(MediaCodec enc, MediaMuxer muxer, int trackIdx,
                               MediaCodec.BufferInfo info, boolean muxerStarted) {
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, TIMEOUT_US);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (idx < 0) break;
            ByteBuffer buf = enc.getOutputBuffer(idx);
            if (buf != null && muxerStarted && trackIdx >= 0) {
                muxer.writeSampleData(trackIdx, buf, info);
            }
            enc.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
        }
    }

    // ── MediaFormat builders ──────────────────────────────────────────────────

    private MediaFormat buildVideoFormat() {
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                                                      OUTPUT_W, OUTPUT_H);
        f.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                     MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        return f;
    }

    private MediaFormat buildAudioFormat() {
        MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,
                                                      AUDIO_RATE, AUDIO_CHAN);
        f.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE,
                     MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        return f;
    }

    // ── Inner helper: VideoDecoder ────────────────────────────────────────────

    private static class VideoDecoder {
        private final MediaExtractor  extractor;
        private final MediaCodec      decoder;
        private final int             trackIdx;
        Bitmap lastFrame;
        private boolean eos = false;

        VideoDecoder(String path) throws Exception {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int idx = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { idx = i; break; }
            }
            if (idx < 0) throw new IOException("No video track in: " + path);
            trackIdx = idx;
            extractor.selectTrack(trackIdx);
            MediaFormat fmt = extractor.getTrackFormat(trackIdx);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime != null ? mime : "video/avc");
            decoder.configure(fmt, null, null, 0);
            decoder.start();
        }

        void start() { /* decoder already started in constructor */ }

        /** Returns next decoded frame as a Bitmap, or null if EOS. */
        Bitmap nextFrame() {
            if (eos) return null;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // Feed input
            int inIdx = decoder.dequeueInputBuffer(10_000);
            if (inIdx >= 0) {
                ByteBuffer ib = decoder.getInputBuffer(inIdx);
                if (ib != null) {
                    int size = extractor.readSampleData(ib, 0);
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                                 MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eos = true;
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size,
                                                 extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            // Drain output
            int outIdx = decoder.dequeueOutputBuffer(info, 10_000);
            if (outIdx >= 0) {
                // Render to surface (we need to get bitmap from output buffer)
                ByteBuffer ob = decoder.getOutputBuffer(outIdx);
                MediaFormat fmt = decoder.getOutputFormat();
                int w = fmt.getInteger(MediaFormat.KEY_WIDTH);
                int h = fmt.getInteger(MediaFormat.KEY_HEIGHT);

                // Create Bitmap from YUV buffer
                if (ob != null && w > 0 && h > 0) {
                    Bitmap bmp = yuvToBitmap(ob, w, h);
                    decoder.releaseOutputBuffer(outIdx, false);
                    if (bmp != null) {
                        lastFrame = bmp;
                        return bmp;
                    }
                } else {
                    decoder.releaseOutputBuffer(outIdx, false);
                }
            }
            return eos ? null : lastFrame;
        }

        /**
         * Basic YUV I420 → ARGB_8888 conversion.
         * MediaCodec typically outputs COLOR_FormatYUV420Flexible on modern devices.
         */
        private Bitmap yuvToBitmap(ByteBuffer yuv, int width, int height) {
            try {
                int[] argb = new int[width * height];
                byte[] data = new byte[yuv.remaining()];
                yuv.get(data);

                int ySize  = width * height;
                int uvSize = ySize / 4;

                for (int j = 0; j < height; j++) {
                    for (int i = 0; i < width; i++) {
                        int yVal  = (data[j * width + i] & 0xFF);
                        int uvRow = j / 2;
                        int uvCol = i / 2;
                        int uVal  = (data[ySize            + uvRow * (width/2) + uvCol] & 0xFF) - 128;
                        int vVal  = (data[ySize + uvSize   + uvRow * (width/2) + uvCol] & 0xFF) - 128;

                        int r = (int)(yVal + 1.402f  * vVal);
                        int g = (int)(yVal - 0.344f  * uVal - 0.714f * vVal);
                        int b = (int)(yVal + 1.772f  * uVal);
                        r = Math.max(0, Math.min(255, r));
                        g = Math.max(0, Math.min(255, g));
                        b = Math.max(0, Math.min(255, b));
                        argb[j * width + i] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
                return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
            } catch (Exception e) {
                Log.w(TAG, "yuvToBitmap failed: " + e.getMessage());
                return null;
            }
        }

        void release() {
            try { decoder.stop(); decoder.release(); } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    // ── Inner helper: AudioDecoder ────────────────────────────────────────────

    private static class AudioDecoder {
        private final MediaExtractor extractor;
        private final MediaCodec     decoder;

        AudioDecoder(String path) throws Exception {
            extractor = new MediaExtractor();
            extractor.setDataSource(path);
            int idx = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { idx = i; break; }
            }
            if (idx < 0) throw new IOException("No audio track in: " + path);
            extractor.selectTrack(idx);
            MediaFormat fmt = extractor.getTrackFormat(idx);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            decoder = MediaCodec.createDecoderByType(mime != null ? mime : "audio/mp4a-latm");
            decoder.configure(fmt, null, null, 0);
            decoder.start();
        }

        /** Decode entire audio stream to 16-bit PCM shorts. */
        short[] decodePcm() {
            java.util.ArrayList<Short> samples = new java.util.ArrayList<>();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;

            while (true) {
                if (!inputDone) {
                    int inIdx = decoder.dequeueInputBuffer(10_000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = decoder.getInputBuffer(inIdx);
                        if (ib != null) {
                            int size = extractor.readSampleData(ib, 0);
                            if (size < 0) {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, size,
                                    extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIdx = decoder.dequeueOutputBuffer(info, 10_000);
                if (outIdx >= 0) {
                    ByteBuffer ob = decoder.getOutputBuffer(outIdx);
                    if (ob != null) {
                        ob.rewind();
                        ShortBuffer sb = ob.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                        while (sb.hasRemaining()) samples.add(sb.get());
                    }
                    decoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break;
                }
            }

            short[] arr = new short[samples.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = samples.get(i);
            return arr;
        }

        void release() {
            try { decoder.stop(); decoder.release(); } catch (Exception ignored) {}
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }
}
