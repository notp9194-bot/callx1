package com.callx.app.helpers;

import android.content.Context;
import android.media.MediaCodec;
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
 * AudioMixHelper — Instagram-style "Record first, mix later" audio pipeline.
 *
 * Flow:
 *  1. Download music URL → local cache file
 *  2. Extract raw PCM from video's mic audio track  ← now detects actual sampleRate/channels
 *  3. Extract raw PCM from music audio file          ← resampled to match mic sampleRate/channels
 *  4. Mix PCM samples with volume weights (micVol, musicVol)
 *  5. Optionally mix voiceover PCM track (voiceoverVol)
 *  6. Re-encode mixed PCM → AAC via MediaCodec      ← uses detected sampleRate/channels
 *  7. Mux final AAC + original video track → output MP4
 *
 * BUG FIX (v3 → v4):
 *  Previously, encodePcmToAac() used hardcoded sampleRate=44100 and channelCount=1.
 *  If the source audio was 48 kHz or stereo, the decoded PCM had a different number of
 *  samples than what 44100 Hz expected, causing slow/fast playback in uploaded videos.
 *  Fix: detect actual sampleRate + channelCount from the video's audio track, resample
 *  music PCM to match, and encode the mixed output at the correct rate.
 */
public class AudioMixHelper {

    private static final String TAG = "AudioMixHelper";

    // ── Target output format — detected from the recorded video audio track ──
    // These are set once per mix job inside doMix() and used by encodePcmToAac().
    // Safe because the executor is single-threaded.
    private static int TARGET_SAMPLE_RATE   = 44100;
    private static int TARGET_CHANNEL_COUNT = 1;

    public interface MixCallback {
        void onProgress(int percent);
        void onSuccess(String outputPath);
        void onError(Exception e);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * ✅ "Use in Camera" flow:
     * Completely replace the recorded video's mic audio track with the given sound URL.
     * Mic audio is set to 0 volume; sound URL audio is set to 1.0 (full volume).
     */
    public static void replaceAudioWithSound(
            Context context,
            String videoPath,
            String soundUrl,
            MixCallback callback) {

        mixAndExport(
            context,
            videoPath,
            soundUrl,
            null,
            0.0f,   // micVol = 0 → mute original recording
            1.0f,   // musicVol = 1 → only the selected sound
            0.0f,
            callback
        );
    }

    /**
     * Main entry point — call from ReelUploadActivity before upload.
     */
    public static void mixAndExport(
            Context context,
            String videoPath,
            String musicUrl,
            String voiceoverPath,
            float micVol,
            float musicVol,
            float voiceoverVol,
            MixCallback callback) {

        executor.execute(() -> {
            try {
                String outputPath = doMix(context, videoPath, musicUrl, voiceoverPath,
                        micVol, musicVol, voiceoverVol, callback);
                mainHandler.post(() -> callback.onSuccess(outputPath));
            } catch (Exception e) {
                Log.e(TAG, "Mix failed", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    // ── Core mix logic ────────────────────────────────────────────────────

    private static String doMix(
            Context context,
            String videoPath,
            String musicUrl,
            String voiceoverPath,
            float micVol,
            float musicVol,
            float voiceoverVol,
            MixCallback callback) throws Exception {

        mainHandler.post(() -> callback.onProgress(5));

        // Step 1: Download music to cache if needed
        File musicFile = null;
        if (musicUrl != null && !musicUrl.isEmpty()) {
            musicFile = downloadToCache(context, musicUrl);
        }
        mainHandler.post(() -> callback.onProgress(20));

        // Step 2: Detect actual audio format from the recorded video FIRST
        //         so we know what sampleRate/channels the mic PCM will be.
        AudioFormat micFormat = detectAudioFormat(videoPath);
        TARGET_SAMPLE_RATE   = micFormat.sampleRate;
        TARGET_CHANNEL_COUNT = micFormat.channelCount;
        Log.d(TAG, "Detected video audio format: " + TARGET_SAMPLE_RATE
                + " Hz, " + TARGET_CHANNEL_COUNT + " ch");

        // Step 3: Decode mic audio from video (at its native format)
        short[] micPcm = extractPcmFromVideo(videoPath);
        mainHandler.post(() -> callback.onProgress(40));

        // Step 4: Decode music audio and RESAMPLE to match mic format
        short[] musicPcm = null;
        if (musicFile != null) {
            AudioFormat musicFormat = detectAudioFormat(musicFile.getAbsolutePath());
            short[] rawMusicPcm = extractPcmFromFile(
                    musicFile.getAbsolutePath(), Integer.MAX_VALUE);

            // Resample music to match mic sampleRate + channelCount
            musicPcm = resampleAndConvert(
                    rawMusicPcm,
                    musicFormat.sampleRate,
                    musicFormat.channelCount,
                    TARGET_SAMPLE_RATE,
                    TARGET_CHANNEL_COUNT,
                    micPcm.length);
        }
        mainHandler.post(() -> callback.onProgress(55));

        // Step 5: Decode voiceover and resample to match mic format
        short[] voiceoverPcm = null;
        if (voiceoverPath != null && !voiceoverPath.isEmpty()) {
            File voFile = new File(voiceoverPath);
            if (voFile.exists()) {
                AudioFormat voFormat = detectAudioFormat(voiceoverPath);
                short[] rawVoPcm = extractPcmFromFile(voiceoverPath, Integer.MAX_VALUE);
                voiceoverPcm = resampleAndConvert(
                        rawVoPcm,
                        voFormat.sampleRate,
                        voFormat.channelCount,
                        TARGET_SAMPLE_RATE,
                        TARGET_CHANNEL_COUNT,
                        micPcm.length);
            }
        }
        mainHandler.post(() -> callback.onProgress(65));

        // Step 6: Mix PCM samples
        short[] mixedPcm = mixPcm(micPcm, micVol, musicPcm, musicVol, voiceoverPcm, voiceoverVol);
        mainHandler.post(() -> callback.onProgress(75));

        // Step 7: Encode mixed PCM → AAC at the correct sample rate
        File aacTemp = new File(context.getCacheDir(),
                "mixed_audio_" + System.currentTimeMillis() + ".aac");
        encodePcmToAac(mixedPcm, aacTemp.getAbsolutePath(),
                TARGET_SAMPLE_RATE, TARGET_CHANNEL_COUNT);
        mainHandler.post(() -> callback.onProgress(88));

        // Step 8: Mux video track + new audio track → final MP4
        String outputPath = new File(context.getCacheDir(),
                "final_reel_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        muxVideoAndAudio(videoPath, aacTemp.getAbsolutePath(), outputPath);
        mainHandler.post(() -> callback.onProgress(98));

        // Cleanup temp files
        aacTemp.delete();
        if (musicFile != null) musicFile.delete();

        return outputPath;
    }

    // ── Audio format detection ─────────────────────────────────────────────

    private static class AudioFormat {
        int sampleRate;
        int channelCount;
        AudioFormat(int sr, int cc) {
            sampleRate   = sr;
            channelCount = cc;
        }
    }

    /**
     * Read sampleRate and channelCount from first audio track in the file.
     * Falls back to 44100/1 if not found (safe default).
     */
    private static AudioFormat detectAudioFormat(String filePath) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(filePath);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fmt = ex.getTrackFormat(i);
                String mime = fmt.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    int sr = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                    int cc = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                    return new AudioFormat(sr, cc);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "detectAudioFormat failed for " + filePath + ": " + e.getMessage());
        } finally {
            ex.release();
        }
        return new AudioFormat(44100, 1); // safe fallback
    }

    // ── Resampling + channel conversion ───────────────────────────────────

    /**
     * Resample PCM from (srcRate, srcCh) → (dstRate, dstCh) and
     * trim/pad to exactly `targetSamples` output samples.
     *
     * Uses linear interpolation — sufficient quality for background music mixing.
     */
    private static short[] resampleAndConvert(
            short[] src,
            int srcRate, int srcCh,
            int dstRate, int dstCh,
            int targetSamples) {

        if (src == null || src.length == 0) return new short[targetSamples];

        // 1. Convert stereo → mono by averaging channels (if needed)
        short[] mono;
        if (srcCh == 2) {
            mono = new short[src.length / 2];
            for (int i = 0; i < mono.length; i++) {
                mono[i] = (short) ((src[i * 2] + src[i * 2 + 1]) / 2);
            }
        } else {
            mono = src;
        }

        // 2. Convert mono → stereo by duplicating (if needed)
        // Then handle rate conversion.
        // For simplicity we always produce mono output matching dstCh=1 flow,
        // but if dstCh==2 we duplicate after rate conversion.

        // 3. Resample with linear interpolation
        double ratio = (double) srcRate / dstRate;
        int resampledLen = (int)(mono.length / ratio);
        short[] resampled = new short[resampledLen];

        for (int i = 0; i < resampledLen; i++) {
            double srcIdx = i * ratio;
            int lo = (int) srcIdx;
            int hi = Math.min(lo + 1, mono.length - 1);
            double frac = srcIdx - lo;
            resampled[i] = (short)(mono[lo] * (1 - frac) + mono[hi] * frac);
        }

        // 4. If dstCh == 2, duplicate to stereo
        short[] channelConverted;
        if (dstCh == 2) {
            channelConverted = new short[resampled.length * 2];
            for (int i = 0; i < resampled.length; i++) {
                channelConverted[i * 2]     = resampled[i];
                channelConverted[i * 2 + 1] = resampled[i];
            }
        } else {
            channelConverted = resampled;
        }

        // 5. Trim or zero-pad to match mic PCM length
        short[] out = new short[targetSamples];
        int copyLen = Math.min(channelConverted.length, targetSamples);
        System.arraycopy(channelConverted, 0, out, 0, copyLen);
        // Remaining samples stay 0 (silence) if music is shorter than video

        return out;
    }

    // ── Step 1: Download music ─────────────────────────────────────────────

    private static File downloadToCache(Context context, String urlStr) throws IOException {
        File cacheFile = new File(context.getCacheDir(),
                "music_cache_" + urlStr.hashCode() + ".aac");
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile;
        }
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(cacheFile)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        return cacheFile;
    }

    // ── Step 2/3: Extract PCM ──────────────────────────────────────────────

    private static short[] extractPcmFromVideo(String videoPath) throws Exception {
        return extractPcmFromFile(videoPath, Integer.MAX_VALUE);
    }

    /**
     * Generic PCM extractor — works on MP4 (video+audio), AAC, or any
     * MediaExtractor-supported container.
     *
     * NOTE: Returns PCM at the file's NATIVE sample rate and channel count.
     * Call resampleAndConvert() afterwards if you need a different format.
     */
    private static short[] extractPcmFromFile(String filePath, int maxSamples) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(filePath);

        int audioTrack = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i;
                break;
            }
        }
        if (audioTrack < 0) {
            extractor.release();
            return new short[0];
        }

        extractor.selectTrack(audioTrack);
        MediaFormat format = extractor.getTrackFormat(audioTrack);

        MediaCodec codec = MediaCodec.createDecoderByType(
                format.getString(MediaFormat.KEY_MIME));
        codec.configure(format, null, null, 0);
        codec.start();

        java.util.ArrayList<Short> samples = new java.util.ArrayList<>(48000 * 60 * 2);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone  = false;
        boolean outputDone = false;
        long timeoutUs = 10_000;

        while (!outputDone && samples.size() < maxSamples) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(timeoutUs);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    int sampleSize = extractor.readSampleData(inBuf, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sampleSize,
                                extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = codec.dequeueOutputBuffer(info, timeoutUs);
            if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    ShortBuffer shortBuf = outBuf.asShortBuffer();
                    while (shortBuf.hasRemaining() && samples.size() < maxSamples) {
                        samples.add(shortBuf.get());
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }

        codec.stop();
        codec.release();
        extractor.release();

        short[] result = new short[samples.size()];
        for (int i = 0; i < samples.size(); i++) result[i] = samples.get(i);
        return result;
    }

    // ── Step 4: Mix PCM ────────────────────────────────────────────────────

    private static short[] mixPcm(
            short[] mic,    float micVol,
            short[] music,  float musicVol,
            short[] vo,     float voVol) {

        int len = mic.length;
        short[] out = new short[len];

        for (int i = 0; i < len; i++) {
            float sample = mic[i] * micVol;

            if (music != null && i < music.length) {
                sample += music[i] * musicVol;
            }
            if (vo != null && i < vo.length) {
                sample += vo[i] * voVol;
            }

            // Hard clip to prevent wrap-around distortion
            if (sample > Short.MAX_VALUE)  sample = Short.MAX_VALUE;
            if (sample < Short.MIN_VALUE)  sample = Short.MIN_VALUE;
            out[i] = (short) sample;
        }
        return out;
    }

    // ── Step 5: Encode PCM → AAC ───────────────────────────────────────────

    /**
     * FIXED: sampleRate and channelCount are now passed in (detected from source audio)
     * instead of being hardcoded. This ensures the AAC file plays at the correct speed.
     */
    private static void encodePcmToAac(
            short[] pcm,
            String outPath,
            int sampleRate,
            int channelCount) throws Exception {

        int bitRate = 128_000;

        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        MediaMuxer muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxTrack = -1;
        boolean muxerStarted = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int frameSize = 1024; // AAC frame = 1024 samples per channel
        int offset = 0;
        boolean inputDone = false;
        long presentationUs = 0;
        // frameDurationUs must reflect channelCount:
        // each AAC frame is 1024 samples per channel at the given sample rate
        long frameDurationUs = (long)(frameSize * 1_000_000L / sampleRate);

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
                        // For stereo: each AAC frame needs 1024 * channelCount shorts
                        int samplesToWrite = Math.min(frameSize * channelCount,
                                pcm.length - offset);
                        ShortBuffer sb = inBuf.asShortBuffer();
                        sb.put(pcm, offset, samplesToWrite);
                        offset += samplesToWrite;
                        encoder.queueInputBuffer(inIdx, 0, samplesToWrite * 2,
                                presentationUs, 0);
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

    // ── Step 6: Mux video track + new audio track ──────────────────────────

    private static void muxVideoAndAudio(
            String videoPath, String audioPath, String outputPath) throws Exception {

        // Extract video track from original MP4
        MediaExtractor videoEx = new MediaExtractor();
        videoEx.setDataSource(videoPath);
        int videoTrack = -1;
        for (int i = 0; i < videoEx.getTrackCount(); i++) {
            String mime = videoEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                videoTrack = i;
                break;
            }
        }
        if (videoTrack < 0) {
            videoEx.release();
            throw new Exception("No video track found in: " + videoPath);
        }
        videoEx.selectTrack(videoTrack);
        MediaFormat videoFormat = videoEx.getTrackFormat(videoTrack);

        // Extract audio from mixed AAC file
        MediaExtractor audioEx = new MediaExtractor();
        audioEx.setDataSource(audioPath);
        int audioTrack = -1;
        for (int i = 0; i < audioEx.getTrackCount(); i++) {
            String mime = audioEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i;
                break;
            }
        }
        if (audioTrack < 0) {
            videoEx.release();
            audioEx.release();
            throw new Exception("No audio track found in mixed file");
        }
        audioEx.selectTrack(audioTrack);
        MediaFormat audioFormat = audioEx.getTrackFormat(audioTrack);

        // Mux both into output
        MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxVideoTrack = muxer.addTrack(videoFormat);
        int muxAudioTrack = muxer.addTrack(audioFormat);
        muxer.start();

        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // Write video samples
        while (true) {
            buf.clear();
            int size = videoEx.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = videoEx.getSampleTime();
            info.flags = videoEx.getSampleFlags();
            muxer.writeSampleData(muxVideoTrack, buf, info);
            videoEx.advance();
        }

        // Write mixed audio samples
        while (true) {
            buf.clear();
            int size = audioEx.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset = 0;
            info.size = size;
            info.presentationTimeUs = audioEx.getSampleTime();
            info.flags = audioEx.getSampleFlags();
            muxer.writeSampleData(muxAudioTrack, buf, info);
            audioEx.advance();
        }

        muxer.stop();
        muxer.release();
        videoEx.release();
        audioEx.release();
    }
}
