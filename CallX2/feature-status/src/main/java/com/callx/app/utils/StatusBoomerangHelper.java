package com.callx.app.utils;

import android.content.Context;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.os.Build;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * StatusBoomerangHelper v26 — Create boomerang (forward+reverse loop) from short video.
 * Extracts frames → reverses → appends → re-encodes to GIF-style looping video.
 */
public final class StatusBoomerangHelper {
    private StatusBoomerangHelper() {}

    public interface BoomerangCallback {
        void onProgress(int pct);
        void onSuccess(File outputFile);
        void onError(String msg);
    }

    /** Extract frames from video, create forward+reverse loop, save as MP4 */
    public static void createBoomerang(Context ctx, Uri videoUri, BoomerangCallback cb) {
        if (ctx == null || videoUri == null) { if (cb != null) cb.onError("Invalid input"); return; }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (cb != null) cb.onProgress(10);
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(ctx, videoUri);
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;
                // Limit to 3 seconds for boomerang
                long extractMs = Math.min(durationMs, 3000);
                int frameCount = 15; // extract 15 frames
                long interval  = extractMs * 1000L / frameCount; // microseconds
                List<Bitmap> frames = new ArrayList<>();
                for (int i = 0; i < frameCount; i++) {
                    long time = i * interval;
                    Bitmap bmp = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (bmp != null) frames.add(bmp);
                    if (cb != null) cb.onProgress(10 + (i * 40 / frameCount));
                }
                retriever.release();
                // Build boomerang sequence: forward + reverse
                List<Bitmap> boomerang = new ArrayList<>(frames);
                List<Bitmap> reversed  = new ArrayList<>(frames);
                Collections.reverse(reversed);
                boomerang.addAll(reversed);

                if (cb != null) cb.onProgress(60);
                // Save as animated GIF via bitmap sequence → video using MediaMuxer
                File outputDir = new File(ctx.getCacheDir(), "boomerang");
                outputDir.mkdirs();
                File outputFile = new File(outputDir, "boomerang_" + System.currentTimeMillis() + ".mp4");
                encodeFramesToMp4(boomerang, outputFile, 800, 800);
                for (Bitmap b : boomerang) if (!b.isRecycled()) b.recycle();
                if (cb != null) { cb.onProgress(100); cb.onSuccess(outputFile); }
            } catch (Exception e) {
                if (cb != null) cb.onError("Boomerang failed: " + e.getMessage());
            }
        });
    }

    private static void encodeFramesToMp4(List<Bitmap> frames, File out, int w, int h) throws Exception {
        if (frames.isEmpty()) throw new Exception("No frames");
        // Scale frames to consistent size
        int width  = frames.get(0).getWidth();
        int height = frames.get(0).getHeight();
        // Ensure even dimensions for H.264
        width  = width  % 2 == 0 ? width  : width - 1;
        height = height % 2 == 0 ? height : height - 1;
        if (width <= 0 || height <= 0) throw new Exception("Invalid frame size");

        MediaMuxer muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 15);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        android.view.Surface surface = encoder.createInputSurface();
        encoder.start();

        int trackIndex = -1;
        boolean muxerStarted = false;
        MediaCodec.BufferInfo bufInfo = new MediaCodec.BufferInfo();
        long presentationUs = 0;
        long frameDuration  = 1_000_000L / 15; // 15fps

        android.graphics.Canvas canvas;
        for (Bitmap bmp : frames) {
            canvas = surface.lockCanvas(null);
            canvas.drawBitmap(Bitmap.createScaledBitmap(bmp, width, height, true), 0, 0, null);
            surface.unlockCanvasAndPost(canvas);
            // Drain encoder
            int idx = encoder.dequeueOutputBuffer(bufInfo, 10000);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                trackIndex = muxer.addTrack(encoder.getOutputFormat());
                muxer.start(); muxerStarted = true;
                idx = encoder.dequeueOutputBuffer(bufInfo, 10000);
            }
            if (idx >= 0 && muxerStarted) {
                bufInfo.presentationTimeUs = presentationUs;
                muxer.writeSampleData(trackIndex, encoder.getOutputBuffer(idx), bufInfo);
                encoder.releaseOutputBuffer(idx, false);
                presentationUs += frameDuration;
            }
        }
        encoder.signalEndOfInputStream();
        int idx = encoder.dequeueOutputBuffer(bufInfo, 100000);
        if (idx >= 0 && muxerStarted) {
            muxer.writeSampleData(trackIndex, encoder.getOutputBuffer(idx), bufInfo);
            encoder.releaseOutputBuffer(idx, false);
        }
        encoder.stop(); encoder.release(); surface.release();
        if (muxerStarted) muxer.stop();
        muxer.release();
    }
}
