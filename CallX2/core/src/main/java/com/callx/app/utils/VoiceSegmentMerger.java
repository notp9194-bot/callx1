package com.callx.app.utils;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * VoiceSegmentMerger — stitches multiple finalized .m4a voice-note segments
 * (each individually a valid, playable MPEG-4 container) into a single
 * output file, back to back, with no re-encoding.
 *
 * WHY THIS EXISTS:
 *  VoiceRecorder used to implement pause/resume via MediaRecorder#pause()/
 *  #resume(), which keeps writing into the SAME output file. That file's
 *  MP4 "moov" box (the header MediaPlayer needs to even open the file) is
 *  only finalized on MediaRecorder#stop() — so while paused-but-not-sent,
 *  the on-disk file is an incomplete container and MediaPlayer#prepare()
 *  on it reliably fails. That's why the preview Play button did nothing:
 *  VoicePreviewPlayer.prepare() was silently returning false.
 *
 *  The fix: VoiceRecorder now fully stops+releases the recorder on pause()
 *  (which DOES finalize that segment's file, making it immediately
 *  playable for the preview) and starts a brand new segment file on
 *  resume(). If the user only pauses once, there's exactly one finalized
 *  segment and no merge is needed. If they pause/resume more than once,
 *  this class concatenates all the finalized segments into one file right
 *  before send — a fast stream-copy (MediaExtractor → MediaMuxer,
 *  compressed-sample passthrough) with no audio re-encoding involved.
 */
public final class VoiceSegmentMerger {

    private VoiceSegmentMerger() {}

    /**
     * Merges {@code segments} (in order) into {@code outFile}. If there's
     * only one segment, this just copies it — no MediaMuxer needed.
     *
     * @return outFile on success, or null if nothing could be merged.
     */
    public static File merge(java.util.List<File> segments, File outFile) {
        java.util.List<File> valid = new java.util.ArrayList<>();
        for (File f : segments) {
            if (f != null && f.exists() && f.length() > 0) valid.add(f);
        }
        if (valid.isEmpty()) return null;
        if (valid.size() == 1) {
            return copyFile(valid.get(0), outFile) ? outFile : null;
        }

        MediaMuxer muxer = null;
        try {
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxTrack = -1;
            boolean muxerStarted = false;
            long presentationOffsetUs = 0L;
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            ByteBuffer buffer = ByteBuffer.allocate(1 << 20); // 1MB — plenty for one AAC sample

            for (File seg : valid) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(seg.getAbsolutePath());
                    int audioTrack = -1;
                    MediaFormat format = null;
                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat fmt = extractor.getTrackFormat(i);
                        String mime = fmt.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) {
                            audioTrack = i;
                            format = fmt;
                            break;
                        }
                    }
                    if (audioTrack < 0 || format == null) continue;
                    extractor.selectTrack(audioTrack);

                    if (!muxerStarted) {
                        muxTrack = muxer.addTrack(format);
                        muxer.start();
                        muxerStarted = true;
                    }

                    long segDurationUs = format.containsKey(MediaFormat.KEY_DURATION)
                            ? format.getLong(MediaFormat.KEY_DURATION) : 0L;
                    long lastSampleUs = 0L;

                    while (true) {
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) break;
                        long sampleTimeUs = extractor.getSampleTime();
                        lastSampleUs = Math.max(lastSampleUs, sampleTimeUs);
                        info.offset = 0;
                        info.size = size;
                        info.presentationTimeUs = sampleTimeUs + presentationOffsetUs;
                        info.flags = extractor.getSampleFlags();
                        muxer.writeSampleData(muxTrack, buffer, info);
                        extractor.advance();
                    }
                    // Advance the timeline by the segment's real duration when the
                    // container reports one; fall back to the last sample seen so
                    // segments never overlap in the merged timeline.
                    presentationOffsetUs += Math.max(segDurationUs, lastSampleUs);
                } finally {
                    extractor.release();
                }
            }

            if (!muxerStarted) return null;
            muxer.stop();
            return outFile;
        } catch (Exception e) {
            if (outFile.exists()) outFile.delete();
            return null;
        } finally {
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst);
             FileChannel inCh = in.getChannel();
             FileChannel outCh = out.getChannel()) {
            inCh.transferTo(0, inCh.size(), outCh);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
