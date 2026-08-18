package com.callx.app.utils;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * VoiceTrimmer — cuts a finalized .m4a voice-note down to the range the
 * user picked with the preview waveform's drag handles (Feature: preview
 * adjust — trim start/end before send, Telegram-style). Stream-copies
 * compressed AAC samples between the two cut points via
 * MediaExtractor → MediaMuxer, no re-encoding — the same approach
 * {@link VoiceSegmentMerger} already uses for stitching pause/resume
 * segments together; trimming is just "copy one input, but skip the
 * samples outside [startUs, endUs] and re-base timestamps to 0".
 *
 * Called once, from ChatMediaController#finishAndSend(), only when the
 * user actually moved a handle (trimStart > 0 or trimEnd < 1). If trimming
 * fails for any reason the caller falls back to sending the untrimmed
 * file — a failed trim should never block the send.
 */
public final class VoiceTrimmer {

    private VoiceTrimmer() {}

    /**
     * @param input     the recorded/merged source file (VoiceRecorder's
     *                  finalized output, before this call)
     * @param output    destination file — created fresh; deleted again if
     *                  trimming fails partway through
     * @param startFrac 0..1, start of the kept range (from
     *                  VoiceNotePreviewWaveformView#getTrimStart())
     * @param endFrac   0..1, end of the kept range, must be > startFrac
     *                  (from #getTrimEnd())
     * @return output on success, or null if trimming failed / there was
     *         nothing meaningful to cut — caller should fall back to
     *         sending {@code input} untrimmed in that case.
     */
    public static File trim(File input, File output, float startFrac, float endFrac) {
        if (input == null || !input.exists() || output == null) return null;

        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean wroteAny = false;
        try {
            extractor.setDataSource(input.getAbsolutePath());

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
            if (audioTrack < 0 || format == null) return null;

            long fullDurationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0L;
            if (fullDurationUs <= 0) return null;

            long startUs = (long) (Math.max(0f, Math.min(1f, startFrac)) * fullDurationUs);
            long endUs = (long) (Math.max(0f, Math.min(1f, endFrac)) * fullDurationUs);
            if (endUs <= startUs) return null;

            extractor.selectTrack(audioTrack);
            // Land at or just before startUs; AAC frames are each
            // independently decodable (no GOP-style inter-frame
            // dependency the way video has), so in practice this lands
            // within one ~23ms frame of the exact cut point. The
            // sampleTimeUs < startUs guard below skips any frames the
            // seek landed on before the real start.
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxTrack = muxer.addTrack(format);
            muxer.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer buffer = ByteBuffer.allocate(1 << 20); // 1MB — plenty for one AAC sample

            while (true) {
                long sampleTimeUs = extractor.getSampleTime();
                if (sampleTimeUs < 0 || sampleTimeUs > endUs) break;
                if (sampleTimeUs < startUs) {
                    // Before the real cut point — skip without writing.
                    extractor.advance();
                    continue;
                }

                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;

                info.offset = 0;
                info.size = size;
                // Re-base so the trimmed file's first sample is at t=0 —
                // required for a valid, seekable, playable container.
                info.presentationTimeUs = sampleTimeUs - startUs;
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(muxTrack, buffer, info);
                wroteAny = true;
                extractor.advance();
            }

            if (!wroteAny) return null;
            muxer.stop();
            return output;
        } catch (Exception e) {
            if (output.exists()) output.delete();
            return null;
        } finally {
            extractor.release();
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }
}
