package com.callx.app.utils;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * PreviewAudioEncoder
 * ────────────────────────────────────────────────────────────────────────
 * Instagram-style "sound preview" transcode.
 *
 * PROBLEM this fixes:
 *  VideoUploader.uploadOriginalAudio() used to just remux (copy, no
 *  re-encode) the video's own audio track — full stereo, ~128 kbps, full
 *  length. That same file was then streamed on SoundDetailActivity's play
 *  button, costing 200-300 KB per play even though the video track was
 *  already disabled there. Instagram never streams the original-quality
 *  audio on the sound-preview screen — it serves a tiny mono, low-bitrate
 *  preview asset instead.
 *
 * WHAT THIS DOES:
 *  Decodes the source file's audio track to raw 16-bit PCM (via
 *  MediaExtractor + MediaCodec decoder), downmixes multi-channel audio to
 *  mono (simple channel-average — no resampling, so no risk of the
 *  "slow audio" bug), then re-encodes to AAC-LC at a low bitrate and mono
 *  channel count, muxed into a plain .m4a container.
 *
 * A 15-30s clip at 32 kbps mono AAC is typically 60-120 KB (~4x smaller
 * than the previous 128 kbps stereo passthrough), matching what a
 * preview-only playback screen actually needs.
 *
 * Runs entirely on Android's built-in MediaCodec APIs — no FFmpeg needed.
 */
public final class PreviewAudioEncoder {

    private static final String TAG = "PreviewAudioEncoder";

    /** Target bitrate for the low-data preview track. */
    public static final int PREVIEW_BITRATE_BPS = 32_000; // 32 kbps

    private PreviewAudioEncoder() {}

    /**
     * Generates a small mono/low-bitrate AAC preview file from the audio
     * track of {@code sourceFile} (a video or audio container).
     *
     * @param sourceFile local file containing at least one audio track
     * @param outputDir  directory to write the temp preview file into
     *                   (caller is responsible for deleting it after upload)
     * @return the generated .m4a preview file
     */
    public static File generatePreview(File sourceFile, File outputDir) throws Exception {
        DecodedPcm decoded = decodeToMonoPcm(sourceFile.getAbsolutePath());
        if (decoded.pcm.length == 0) throw new Exception("No audio samples decoded from " + sourceFile);

        File outFile = new File(outputDir,
            "preview_audio_" + System.currentTimeMillis() + ".m4a");
        encodeMonoPcmToAac(decoded.pcm, decoded.sampleRate, PREVIEW_BITRATE_BPS, outFile.getAbsolutePath());

        Log.d(TAG, "Preview audio generated: " + outFile.length() / 1024 + " KB ("
            + decoded.sampleRate + "Hz mono, " + (PREVIEW_BITRATE_BPS / 1000) + "kbps) → " + outFile);
        return outFile;
    }

    // ── Decode: audio track → mono 16-bit PCM (no resampling) ─────────────

    /** Simple holder so decode() can return both the samples and the true sample rate. */
    private static final class DecodedPcm {
        final short[] pcm;
        final int     sampleRate;
        DecodedPcm(short[] pcm, int sampleRate) { this.pcm = pcm; this.sampleRate = sampleRate; }
    }

    private static DecodedPcm decodeToMonoPcm(String filePath) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(filePath);

        int audioTrack = -1;
        MediaFormat inputFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack  = i;
                inputFormat = fmt;
                break;
            }
        }
        if (audioTrack < 0 || inputFormat == null) {
            extractor.release();
            throw new Exception("No audio track found in " + filePath);
        }
        extractor.selectTrack(audioTrack);

        int srcSampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
            ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int srcChannelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
            ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

        MediaCodec codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
        codec.configure(inputFormat, null, null, 0);
        codec.start();

        java.util.ArrayList<Short> samples = new java.util.ArrayList<>(44100 * 30);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone = false, outputDone = false;
        long timeoutUs = 10_000;

        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(timeoutUs);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    int size = extractor.readSampleData(inBuf, 0);
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, size, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outFmt = codec.getOutputFormat();
                if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    srcSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    srcChannelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            } else if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    ShortBuffer sBuf = outBuf.asShortBuffer();
                    if (srcChannelCount <= 1) {
                        while (sBuf.hasRemaining()) samples.add(sBuf.get());
                    } else {
                        // Downmix: average all channels in each frame so N frames
                        // → N mono samples (duration/pitch stay correct).
                        while (sBuf.remaining() >= srcChannelCount) {
                            long sum = 0;
                            for (int c = 0; c < srcChannelCount; c++) sum += sBuf.get();
                            samples.add((short) (sum / srcChannelCount));
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }
        }

        codec.stop();
        codec.release();
        extractor.release();

        short[] pcm = new short[samples.size()];
        for (int i = 0; i < pcm.length; i++) pcm[i] = samples.get(i);
        return new DecodedPcm(pcm, srcSampleRate);
    }

    // ── Encode: mono PCM → low-bitrate AAC (.m4a) ──────────────────────────

    private static void encodeMonoPcmToAac(short[] pcm, int sampleRate, int bitRate, String outPath)
            throws Exception {
        MediaFormat format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1 /* mono */);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        MediaMuxer muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxTrack = -1;
        boolean muxerStarted = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int frameSize = 1024; // AAC frame = 1024 samples
        int offset = 0;
        boolean inputDone = false;
        long presentationUs = 0;
        long frameDurationUs = (long) (frameSize * 1_000_000L / sampleRate);

        while (true) {
            if (!inputDone) {
                int inIdx = encoder.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = encoder.getInputBuffer(inIdx);
                    inBuf.clear();
                    if (offset >= pcm.length) {
                        encoder.queueInputBuffer(inIdx, 0, 0, presentationUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        int samplesToWrite = Math.min(frameSize, pcm.length - offset);
                        ShortBuffer sb = inBuf.asShortBuffer();
                        sb.put(pcm, offset, samplesToWrite);
                        offset += samplesToWrite;
                        encoder.queueInputBuffer(inIdx, 0, samplesToWrite * 2, presentationUs, 0);
                        presentationUs += frameDurationUs;
                    }
                }
            }

            int outIdx = encoder.dequeueOutputBuffer(info, 10_000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxTrack = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
            } else if (outIdx >= 0) {
                ByteBuffer outBuf = encoder.getOutputBuffer(outIdx);
                if (muxerStarted && outBuf != null
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && info.size > 0) {
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    muxer.writeSampleData(muxTrack, outBuf, info);
                }
                encoder.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }

        encoder.stop();
        encoder.release();
        if (muxerStarted) muxer.stop();
        muxer.release();
    }
}
