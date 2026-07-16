package com.callx.app.music;

import com.callx.app.camera.ReelCameraActivity;
import com.callx.app.editor.ReelEditorActivity;
import com.callx.app.upload.ReelUploadActivity;

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
 *  2. Extract raw PCM from video's mic audio track
 *  3. Extract raw PCM from music audio file
 *  4. Mix PCM samples with volume weights (micVol, musicVol)
 *  5. Optionally mix voiceover PCM track (voiceoverVol)
 *  6. Re-encode mixed PCM → AAC via MediaCodec
 *  7. Mux final AAC + original video track → output MP4
 *
 * Uses only Android built-in APIs (MediaExtractor, MediaMuxer, MediaCodec).
 * No FFmpeg binary required — LiTr dependency already in build.gradle is used
 * for video passthrough; audio mixing is done natively here.
 */
public class AudioMixHelper {

    private static final String TAG = "AudioMixHelper";

    public interface MixCallback {
        void onProgress(int percent);
        void onSuccess(String outputPath);
        void onError(Exception e);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * ✅ NEW — "Use in Camera" flow:
     * Completely replace the recorded video's mic audio track with the given sound URL.
     * Mic audio is set to 0 volume; sound URL audio is set to 1.0 (full volume).
     *
     * This is called from ReelCameraActivity right after recording finishes,
     * BEFORE the video is passed to ReelEditorActivity.
     *
     * @param context   App context
     * @param videoPath Absolute path to the recorded video (contains mic audio)
     * @param soundUrl  Cloudinary/Firebase URL of the selected original audio
     * @param callback  Result callback (always called on main thread)
     */
    public static void replaceAudioWithSound(
            Context context,
            String videoPath,
            String soundUrl,
            MixCallback callback) {

        // mic volume = 0 (mute mic), music volume = 1.0 (full sound URL)
        mixAndExport(
            context,
            videoPath,
            soundUrl,
            null,   // no voiceover
            0.0f,   // micVol = 0 → remove original recording audio
            1.0f,   // musicVol = 1 → only the selected sound URL
            0.0f,   // voiceoverVol = 0
            callback
        );
    }

    /**
     * Main entry point — call from ReelUploadActivity before upload.
     *
     * @param context      App context
     * @param videoPath    Absolute path to recorded video (with mic audio)
     * @param musicUrl     URL of background music (nullable/empty = skip music track)
     * @param voiceoverPath Path to voiceover AAC file (nullable/empty = skip)
     * @param micVol       Mic audio volume 0.0-1.0
     * @param musicVol     Music volume 0.0-1.0
     * @param voiceoverVol Voiceover volume 0.0-1.0
     * @param callback     Result callback (always called on main thread)
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

        // Step 2: Decode mic audio from video
        short[] micPcm = extractPcmFromVideo(videoPath);
        mainHandler.post(() -> callback.onProgress(40));

        // Step 3: Decode music audio (trimmed to mic length)
        short[] musicPcm = null;
        if (musicFile != null) {
            musicPcm = extractPcmFromFile(musicFile.getAbsolutePath(), micPcm.length);
        }
        mainHandler.post(() -> callback.onProgress(55));

        // Step 4: Decode voiceover
        short[] voiceoverPcm = null;
        if (voiceoverPath != null && !voiceoverPath.isEmpty()) {
            File voFile = new File(voiceoverPath);
            if (voFile.exists()) {
                voiceoverPcm = extractPcmFromFile(voiceoverPath, micPcm.length);
            }
        }
        mainHandler.post(() -> callback.onProgress(65));

        // Step 5: Mix PCM samples
        short[] mixedPcm = mixPcm(micPcm, micVol, musicPcm, musicVol, voiceoverPcm, voiceoverVol);
        mainHandler.post(() -> callback.onProgress(75));

        // Step 6: Encode mixed PCM → AAC temp file
        File aacTemp = new File(context.getCacheDir(),
                "mixed_audio_" + System.currentTimeMillis() + ".aac");
        encodePcmToAac(mixedPcm, aacTemp.getAbsolutePath());
        mainHandler.post(() -> callback.onProgress(88));

        // Step 7: Mux video track + new audio track → final MP4
        String outputPath = new File(context.getCacheDir(),
                "final_reel_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        muxVideoAndAudio(videoPath, aacTemp.getAbsolutePath(), outputPath);
        mainHandler.post(() -> callback.onProgress(98));

        // Cleanup temp files
        aacTemp.delete();
        if (musicFile != null) musicFile.delete();

        return outputPath;
    }

    // ── Step 1: Download music ─────────────────────────────────────────────

    private static File downloadToCache(Context context, String urlStr) throws IOException {
        File cacheFile = new File(context.getCacheDir(),
                "music_cache_" + urlStr.hashCode() + ".aac");
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile; // Already cached
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

    /**
     * Extract PCM from the audio track embedded in a video file.
     */
    private static short[] extractPcmFromVideo(String videoPath) throws Exception {
        return extractPcmFromFile(videoPath, Integer.MAX_VALUE);
    }

    /**
     * Generic PCM extractor — works on MP4 (video+audio), AAC, or any
     * MediaExtractor-supported container.
     *
     * FIXED: Returns normalised 16-bit MONO PCM at exactly 44100 Hz so that
     * every caller (mic track, music track, voiceover) ends up on the same
     * timeline and the mixed output plays at the correct speed.
     *
     * Two bugs were present before this fix that both cause "audio sounds slow":
     *
     *  1. STEREO → MONO not handled.
     *     Music files (Cloudinary / Firebase originals) are almost always
     *     stereo (2 channels).  The old code read every PCM short straight
     *     into the sample list, so a 1-second stereo clip produced
     *     44100 × 2 = 88 200 shorts.  Those 88 200 shorts were then encoded
     *     as 44100-Hz mono, creating a 2-second clip — exactly half speed.
     *
     *  2. SAMPLE-RATE mismatch not handled.
     *     Many music files are mastered at 48 000 Hz.  The encoder always
     *     writes 44100 Hz.  48 000 raw samples encoded at 44100 Hz produce
     *     audio that is 48000/44100 ≈ 8.8 % too slow and slightly lower in
     *     pitch.
     *
     * Fix: after decoding, downmix multi-channel audio to mono, then
     * linearly resample to 44100 Hz if the source rate differs.
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
        MediaFormat inputFormat = extractor.getTrackFormat(audioTrack);

        // Read declared format properties — will be confirmed/overridden by
        // INFO_OUTPUT_FORMAT_CHANGED once decoding starts.
        int srcSampleRate   = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
        int srcChannelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

        MediaCodec codec = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME));
        codec.configure(inputFormat, null, null, 0);
        codec.start();

        // Collect raw PCM output (still multi-channel / original sample-rate at this stage)
        java.util.ArrayList<Short> rawSamples = new java.util.ArrayList<>(44100 * 60);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inputDone  = false;
        boolean outputDone = false;
        long timeoutUs = 10_000;

        while (!outputDone) {
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
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Codec output format is the ground truth — override declared values
                MediaFormat outFmt = codec.getOutputFormat();
                if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    srcSampleRate   = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    srcChannelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            } else if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    ShortBuffer shortBuf = outBuf.asShortBuffer();
                    if (srcChannelCount <= 1) {
                        // Mono — take samples directly
                        while (shortBuf.hasRemaining()) {
                            rawSamples.add(shortBuf.get());
                        }
                    } else {
                        // Multi-channel (stereo / surround) → downmix to mono.
                        // Average all channel samples in each frame to preserve
                        // correct duration: N stereo frames → N mono samples,
                        // not 2N samples that would halve the playback speed.
                        while (shortBuf.remaining() >= srcChannelCount) {
                            long sum = 0;
                            for (int c = 0; c < srcChannelCount; c++) {
                                sum += shortBuf.get();
                            }
                            rawSamples.add((short) (sum / srcChannelCount));
                        }
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

        short[] pcm = new short[rawSamples.size()];
        for (int i = 0; i < pcm.length; i++) pcm[i] = rawSamples.get(i);

        // Resample to the target 44100 Hz used by encodePcmToAac().
        // Without this, music mastered at 48 000 Hz (very common) plays
        // ~8.8 % too slowly after encoding, and 22050 Hz content plays 2× fast.
        final int TARGET_SAMPLE_RATE = 44100;
        if (srcSampleRate != TARGET_SAMPLE_RATE && srcSampleRate > 0 && pcm.length > 0) {
            pcm = resamplePcm(pcm, srcSampleRate, TARGET_SAMPLE_RATE);
        }

        // Honour the caller's maxSamples cap (used to trim music to mic length)
        if (pcm.length > maxSamples) {
            short[] trimmed = new short[maxSamples];
            System.arraycopy(pcm, 0, trimmed, 0, maxSamples);
            return trimmed;
        }
        return pcm;
    }

    /**
     * Linear-interpolation resampler.
     * Simple and fast — adequate quality for speech/music in short clips.
     * Converts {@code input} from {@code srcRate} Hz to {@code dstRate} Hz.
     */
    private static short[] resamplePcm(short[] input, int srcRate, int dstRate) {
        if (srcRate == dstRate || input.length == 0) return input;
        int dstLen = (int) ((long) input.length * dstRate / srcRate);
        short[] output = new short[dstLen];
        for (int i = 0; i < dstLen; i++) {
            float srcPos = (float) i * srcRate / dstRate;
            int   idx0   = (int) srcPos;
            int   idx1   = Math.min(idx0 + 1, input.length - 1);
            float frac   = srcPos - idx0;
            output[i] = (short) (input[idx0] * (1f - frac) + input[idx1] * frac);
        }
        return output;
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

    private static void encodePcmToAac(short[] pcm, String outPath) throws Exception {
        int sampleRate   = 44100;
        int channelCount = 1;
        int bitRate      = 128_000;

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
        int frameSize = 1024; // AAC frame = 1024 samples
        int offset = 0;
        boolean inputDone = false;
        long presentationUs = 0;
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
                        int samplesToWrite = Math.min(frameSize, pcm.length - offset);
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
