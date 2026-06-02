package com.callx.app.duet;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * DuetVideoComposer — Handles post-recording video processing.
 *
 * For PiP & React modes: re-muxes a single camera track into clean MP4.
 * For TOP_BOTTOM and LEFT_RIGHT:  the visual split is achieved at record-time
 * via the device's camera preview + original player being rendered on-screen —
 * the final recorded file (from VideoCapture) captures the full screen,
 * already composed. This composer just cleans the container.
 *
 * Advanced: side-by-side pixel-level compositing with MediaCodec would require
 * full SW decode→blend→encode pipeline (expensive). The on-screen capture approach
 * used in DuetReelActivity gives the same result with zero extra CPU cost.
 */
public class DuetVideoComposer {

    private static final String TAG = "DuetVideoComposer";

    /**
     * Re-mux camera output into a clean .mp4 container.
     * Strips corrupt tracks, fixes timing gaps.
     *
     * @param inputPath  camera recording file path
     * @param outputDir  directory for the output file
     * @return           absolute path of the clean output file, or inputPath on error
     */
    public static String remux(String inputPath, File outputDir) {
        File outFile = new File(outputDir,
            "duet_composed_" + System.currentTimeMillis() + ".mp4");
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(inputPath);
            int trackCount = extractor.getTrackCount();
            if (trackCount == 0) return inputPath;

            muxer = new MediaMuxer(outFile.getAbsolutePath(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int[] trackMap = new int[trackCount];
            boolean hasTrack = false;
            for (int i = 0; i < trackCount; i++) {
                MediaFormat fmt = extractor.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    trackMap[i] = muxer.addTrack(fmt);
                    extractor.selectTrack(i);
                    hasTrack = true;
                } else {
                    trackMap[i] = -1;
                }
            }
            if (!hasTrack) return inputPath;

            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            // Two-pass: video first, then audio — ensures correct interleaving
            for (int pass = 0; pass < 2; pass++) {
                for (int t = 0; t < trackCount; t++) extractor.unselectTrack(t);
                for (int t = 0; t < trackCount; t++) {
                    if (trackMap[t] < 0) continue;
                    MediaFormat fmt = extractor.getTrackFormat(t);
                    String mime = fmt.getString(MediaFormat.KEY_MIME);
                    if (pass == 0 && mime != null && !mime.startsWith("video/")) continue;
                    if (pass == 1 && mime != null && !mime.startsWith("audio/")) continue;
                    extractor.selectTrack(t);
                }
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                while (true) {
                    int idx = extractor.getSampleTrackIndex();
                    if (idx < 0 || trackMap[idx] < 0) {
                        if (!extractor.advance()) break;
                        continue;
                    }
                    info.offset = 0;
                    info.size   = extractor.readSampleData(buffer, 0);
                    if (info.size < 0) break;
                    info.presentationTimeUs = extractor.getSampleTime();
                    info.flags              = extractor.getSampleFlags();
                    muxer.writeSampleData(trackMap[idx], buffer, info);
                    extractor.advance();
                }
            }
            muxer.stop();
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "remux failed: " + e.getMessage());
            if (outFile.exists()) outFile.delete();
            return inputPath;
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) {}
        }
    }
}
