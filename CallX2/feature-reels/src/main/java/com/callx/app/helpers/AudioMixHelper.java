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
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AudioMixHelper — Instagram-style "Record first, mix later" audio pipeline.
 *
 * Fixes applied:
 *  ✅ Sample rate auto-detected from source (not hardcoded 44100)
 *  ✅ If video has no mic track, music length is used as output length
 *  ✅ Music loops if shorter than video
 *  ✅ Channel count auto-detected and normalised to stereo
 *  ✅ Proper HTTP headers for Firebase Storage downloads
 *  ✅ All PCM mixed at a common 44100 Hz / stereo after resampling hint
 */
public class AudioMixHelper {

    private static final String TAG        = "AudioMixHelper";
    private static final int    SAMPLE_RATE = 44100;
    private static final int    CHANNELS    = 2; // stereo output

    public interface MixCallback {
        void onProgress(int percent);
        void onSuccess(String outputPath);
        void onError(Exception e);
    }

    private static final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private static final Handler         mainHandler = new Handler(Looper.getMainLooper());

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
                String out = doMix(context, videoPath, musicUrl, voiceoverPath,
                        micVol, musicVol, voiceoverVol, callback);
                mainHandler.post(() -> callback.onSuccess(out));
            } catch (Exception e) {
                Log.e(TAG, "Mix failed", e);
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    // ── Core pipeline ─────────────────────────────────────────────────────

    private static String doMix(
            Context context,
            String videoPath,
            String musicUrl,
            String voiceoverPath,
            float micVol,
            float musicVol,
            float voiceoverVol,
            MixCallback callback) throws Exception {

        post(callback, 5);

        // Step 1: Download background music
        File musicFile = null;
        if (musicUrl != null && !musicUrl.isEmpty()) {
            Log.d(TAG, "Downloading music: " + musicUrl);
            musicFile = downloadToCache(context, musicUrl);
            Log.d(TAG, "Music downloaded: " + musicFile.length() + " bytes");
        }
        post(callback, 20);

        // Step 2: Decode mic audio from video
        PcmData micData = extractPcm(videoPath, true /* isVideo */);
        Log.d(TAG, "Mic PCM: " + micData.samples.length + " samples @ " + micData.sampleRate + " Hz, ch=" + micData.channels);
        post(callback, 40);

        // Step 3: Decode music
        PcmData musicData = null;
        if (musicFile != null && musicFile.exists() && musicFile.length() > 0) {
            musicData = extractPcm(musicFile.getAbsolutePath(), false);
            Log.d(TAG, "Music PCM: " + musicData.samples.length + " samples @ " + musicData.sampleRate + " Hz, ch=" + musicData.channels);
        }
        post(callback, 55);

        // Step 4: Decode voiceover
        PcmData voiceData = null;
        if (voiceoverPath != null && !voiceoverPath.isEmpty()) {
            File vf = new File(voiceoverPath);
            if (vf.exists()) voiceData = extractPcm(voiceoverPath, false);
        }
        post(callback, 65);

        // Step 5: Resample all to SAMPLE_RATE / stereo, then mix
        // Output length = mic length (if > 0), else music length
        int outLen = micData.samples.length > 0 ? micData.samples.length
                   : (musicData != null ? musicData.samples.length : 0);

        if (outLen == 0) throw new Exception("Nothing to mix — mic and music both empty");

        // Resample each track to target rate/channels and to outLen
        short[] mic16  = resample(micData,   SAMPLE_RATE, CHANNELS, outLen,  false);
        short[] mus16  = resample(musicData, SAMPLE_RATE, CHANNELS, outLen,  true  /* loop */);
        short[] vo16   = resample(voiceData, SAMPLE_RATE, CHANNELS, outLen,  false);

        short[] mixed = mixPcm(mic16, micVol, mus16, musicVol, vo16, voiceoverVol);
        post(callback, 75);

        // Step 6: Encode mixed PCM → AAC (inside temp MP4)
        File aacTemp = new File(context.getCacheDir(),
                "mixed_audio_" + System.currentTimeMillis() + ".mp4");
        encodePcmToAac(mixed, aacTemp.getAbsolutePath(), SAMPLE_RATE, CHANNELS);
        post(callback, 88);

        // Step 7: Mux original video track + new audio → final MP4
        String outputPath = new File(context.getCacheDir(),
                "final_reel_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        muxVideoAndAudio(videoPath, aacTemp.getAbsolutePath(), outputPath);
        post(callback, 98);

        // Cleanup
        aacTemp.delete();
        if (musicFile != null) musicFile.delete();

        return outputPath;
    }

    // ── Download ──────────────────────────────────────────────────────────

    private static File downloadToCache(Context context, String urlStr) throws IOException {
        // Use hash of full URL as cache key
        File cacheFile = new File(context.getCacheDir(),
                "music_dl_" + Math.abs(urlStr.hashCode()) + ".tmp");
        if (cacheFile.exists() && cacheFile.length() > 1024) {
            return cacheFile; // already cached
        }
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " downloading music: " + urlStr);
        }
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(cacheFile)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        return cacheFile;
    }

    // ── PCM extraction ────────────────────────────────────────────────────

    static class PcmData {
        short[] samples;
        int     sampleRate;
        int     channels;
        PcmData(short[] s, int r, int c) { samples = s; sampleRate = r; channels = c; }
    }

    /**
     * Decode the first audio track from any MediaExtractor-supported file.
     * Returns native sample-rate + channel-count so we can resample properly.
     */
    private static PcmData extractPcm(String path, boolean isVideo) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(path);

        int audioTrack = -1;
        MediaFormat fmt = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat f = extractor.getTrackFormat(i);
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i;
                fmt = f;
                break;
            }
        }
        if (audioTrack < 0 || fmt == null) {
            extractor.release();
            // No audio track — return empty with sensible defaults
            return new PcmData(new short[0], SAMPLE_RATE, CHANNELS);
        }

        int srcRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : SAMPLE_RATE;
        int srcCh   = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

        extractor.selectTrack(audioTrack);
        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        ArrayList<Short> samples = new ArrayList<>(srcRate * 60 * srcCh);
        MediaCodec.BufferInfo info   = new MediaCodec.BufferInfo();
        boolean inputDone  = false;
        boolean outputDone = false;
        long    timeout    = 10_000L;

        while (!outputDone) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(timeout);
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
            int outIdx = codec.dequeueOutputBuffer(info, timeout);
            if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    ShortBuffer sb = outBuf.asShortBuffer();
                    while (sb.hasRemaining()) samples.add(sb.get());
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }
        }
        codec.stop();
        codec.release();
        extractor.release();

        short[] arr = new short[samples.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = samples.get(i);
        return new PcmData(arr, srcRate, srcCh);
    }

    // ── Resample + channel convert ────────────────────────────────────────

    /**
     * Naive linear-interpolation resample + mono→stereo or stereo→mono.
     * Also pads/truncates to targetLen and optionally loops.
     */
    private static short[] resample(PcmData src, int targetRate, int targetCh,
                                    int targetLen, boolean loop) {
        if (src == null || src.samples.length == 0) return new short[targetLen * targetCh];

        // After channel-mixing, source is mono or stereo depending on targetCh
        // Ratio: how many src samples per dst sample
        double ratio = (double) src.sampleRate * src.channels
                     / ((double) targetRate * targetCh);

        short[] out = new short[targetLen * targetCh]; // targetCh = 2 (stereo)

        int srcTotal = src.samples.length;
        for (int i = 0; i < out.length; i++) {
            double srcIdx = i * ratio;
            if (loop) srcIdx = srcIdx % srcTotal;
            int    lo   = (int) srcIdx % srcTotal;
            int    hi   = (lo + 1) % srcTotal;
            double frac = srcIdx - (int) srcIdx;
            // linear interpolation
            double s = src.samples[lo] * (1.0 - frac) + src.samples[hi] * frac;
            out[i] = clamp(s);
        }
        return out;
    }

    // ── Mix ───────────────────────────────────────────────────────────────

    private static short[] mixPcm(short[] mic, float micVol,
                                   short[] mus, float musVol,
                                   short[] vo,  float voVol) {
        int len = mic.length;
        short[] out = new short[len];
        for (int i = 0; i < len; i++) {
            double s = 0;
            s += mic[i] * micVol;
            if (mus != null && mus.length > 0) s += mus[i] * musVol;
            if (vo  != null && vo.length  > 0 && i < vo.length) s += vo[i] * voVol;
            out[i] = clamp(s);
        }
        return out;
    }

    private static short clamp(double v) {
        if (v > Short.MAX_VALUE) return Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) return Short.MIN_VALUE;
        return (short) v;
    }

    // ── Encode PCM → AAC (wrapped in MP4) ────────────────────────────────

    private static void encodePcmToAac(short[] pcm, String outPath,
                                        int sampleRate, int channels) throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 192_000);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        MediaMuxer muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int   muxTrack      = -1;
        boolean muxerStarted = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int  frameSize      = 1024; // AAC frame size
        int  offset         = 0;
        boolean inputDone   = false;
        long presUs         = 0;
        long frameDurUs     = (long)(frameSize * 1_000_000L / sampleRate);

        while (true) {
            if (!inputDone) {
                int inIdx = encoder.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = encoder.getInputBuffer(inIdx);
                    inBuf.clear();
                    if (offset >= pcm.length) {
                        encoder.queueInputBuffer(inIdx, 0, 0, presUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        int toWrite = Math.min(frameSize * channels, pcm.length - offset);
                        ShortBuffer sb = inBuf.asShortBuffer();
                        sb.put(pcm, offset, toWrite);
                        offset += toWrite;
                        encoder.queueInputBuffer(inIdx, 0, toWrite * 2, presUs, 0);
                        presUs += frameDurUs;
                    }
                }
            }
            int outIdx = encoder.dequeueOutputBuffer(info, 10_000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxTrack     = muxer.addTrack(encoder.getOutputFormat());
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
        if (muxerStarted) { muxer.stop(); }
        muxer.release();
    }

    // ── Mux video + audio ─────────────────────────────────────────────────

    private static void muxVideoAndAudio(
            String videoPath, String audioPath, String outputPath) throws Exception {

        // --- Video extractor (video track only)
        MediaExtractor videoEx = new MediaExtractor();
        videoEx.setDataSource(videoPath);
        int videoTrack = -1;
        for (int i = 0; i < videoEx.getTrackCount(); i++) {
            String mime = videoEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) { videoTrack = i; break; }
        }
        if (videoTrack < 0) {
            videoEx.release();
            throw new Exception("No video track in: " + videoPath);
        }
        videoEx.selectTrack(videoTrack);
        MediaFormat videoFmt = videoEx.getTrackFormat(videoTrack);

        // --- Audio extractor (from mixed AAC file)
        MediaExtractor audioEx = new MediaExtractor();
        audioEx.setDataSource(audioPath);
        int audioTrack = -1;
        for (int i = 0; i < audioEx.getTrackCount(); i++) {
            String mime = audioEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { audioTrack = i; break; }
        }
        if (audioTrack < 0) {
            videoEx.release();
            audioEx.release();
            throw new Exception("No audio track in mixed file: " + audioPath);
        }
        audioEx.selectTrack(audioTrack);
        MediaFormat audioFmt = audioEx.getTrackFormat(audioTrack);

        // --- Mux both
        MediaMuxer muxer      = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxVideo          = muxer.addTrack(videoFmt);
        int muxAudio          = muxer.addTrack(audioFmt);
        muxer.start();

        ByteBuffer buf        = ByteBuffer.allocate(2 * 1024 * 1024); // 2 MB buffer
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        // Write video
        videoEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true) {
            buf.clear();
            int size = videoEx.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset          = 0;
            info.size            = size;
            info.presentationTimeUs = videoEx.getSampleTime();
            info.flags           = videoEx.getSampleFlags();
            muxer.writeSampleData(muxVideo, buf, info);
            videoEx.advance();
        }

        // Write mixed audio
        audioEx.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        while (true) {
            buf.clear();
            int size = audioEx.readSampleData(buf, 0);
            if (size < 0) break;
            info.offset          = 0;
            info.size            = size;
            info.presentationTimeUs = audioEx.getSampleTime();
            info.flags           = audioEx.getSampleFlags();
            muxer.writeSampleData(muxAudio, buf, info);
            audioEx.advance();
        }

        muxer.stop();
        muxer.release();
        videoEx.release();
        audioEx.release();
    }

    // ── Util ──────────────────────────────────────────────────────────────

    private static void post(MixCallback cb, int pct) {
        mainHandler.post(() -> cb.onProgress(pct));
    }
}
