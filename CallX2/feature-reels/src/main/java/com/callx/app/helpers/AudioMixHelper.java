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
 * AudioMixHelper — Production-level "Record first, mix later" audio pipeline.
 *
 * Fully native Android (no FFmpeg). Pipeline:
 *  1. Download music URL → local cache (with disk-cache key, retry on 503)
 *  2. Decode mic PCM from video (stereo-aware, downmix to mono)
 *  3. Decode music PCM (loop to fit video duration, trim offset supported)
 *  4. Decode voiceover PCM
 *  5. Mix with per-track volume weights
 *  6. Apply: fade-in (50ms), fade-out (150ms), soft-knee limiter, loudness normalisation
 *  7. Encode mixed PCM → AAC-LC via MediaCodec (44100 Hz, 192 kbps)
 *  8. Mux final AAC + original video track → output MP4 (frame-accurate timestamps)
 *
 * New in this version:
 *  ✅ Stereo-aware decoding (downmix to mono)
 *  ✅ Loop music to fill video duration
 *  ✅ Crossfade at loop boundaries (10 ms)
 *  ✅ musicStartMs offset into track (from ReelMusicTrimActivity)
 *  ✅ Smooth fade-in / fade-out at audio boundaries
 *  ✅ Soft-knee limiter (prevents harsh digital clipping)
 *  ✅ Loudness normalisation (approx –14 LUFS via peak RMS)
 *  ✅ Disk-cached music download (avoids re-download on retry)
 *  ✅ Thread-safe cancel support
 *  ✅ Progress callback with accurate stage pct
 *  ✅ HTTP 503 retry (3 attempts with back-off)
 */
public class AudioMixHelper {

    private static final String TAG = "AudioMixHelper";

    private static final int   SAMPLE_RATE       = 44100;
    private static final int   CHANNEL_COUNT     = 1;           // mono output
    private static final int   BIT_RATE          = 192_000;
    private static final int   FADE_IN_MS        = 50;
    private static final int   FADE_OUT_MS       = 150;
    private static final float LIMITER_THRESHOLD = 0.92f * Short.MAX_VALUE;
    private static final float LIMITER_KNEE      = 0.05f;      // soft-knee width (fraction)
    private static final float TARGET_RMS_RATIO  = 0.18f;      // proxy for –14 LUFS

    private static volatile boolean cancelled = false;

    public interface MixCallback {
        void onProgress(int percent);
        void onSuccess(String outputPath);
        void onError(Exception e);
    }

    private static final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private static final Handler         mainHandler = new Handler(Looper.getMainLooper());

    /** Cancel the in-progress mix (best-effort). */
    public static void cancel() { cancelled = true; }

    /**
     * Mix and export — all heavy work is off the main thread.
     *
     * @param context       App context
     * @param videoPath     Absolute path to recorded/picked video (with mic audio)
     * @param musicUrl      URL of background music (null/empty = skip)
     * @param voiceoverPath Path to voiceover AAC file (null/empty = skip)
     * @param micVol        Mic volume 0.0–1.0
     * @param musicVol      Music volume 0.0–1.0
     * @param voiceoverVol  Voiceover volume 0.0–1.0
     * @param musicStartMs  Trim start offset into the music track in ms
     * @param callback      Progress/result callbacks on main thread
     */
    public static void mixAndExport(
            Context context,
            String videoPath,
            String musicUrl,
            String voiceoverPath,
            float micVol,
            float musicVol,
            float voiceoverVol,
            int musicStartMs,
            MixCallback callback) {

        cancelled = false;
        executor.execute(() -> {
            try {
                String out = doMix(context, videoPath, musicUrl, voiceoverPath,
                        micVol, musicVol, voiceoverVol, musicStartMs, callback);
                if (!cancelled) mainHandler.post(() -> callback.onSuccess(out));
            } catch (CancelledException ignored) {
                // silently dropped
            } catch (Exception e) {
                Log.e(TAG, "Mix failed", e);
                if (!cancelled) mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /** Convenience overload without musicStartMs (defaults to 0). */
    public static void mixAndExport(
            Context context, String videoPath, String musicUrl, String voiceoverPath,
            float micVol, float musicVol, float voiceoverVol, MixCallback callback) {
        mixAndExport(context, videoPath, musicUrl, voiceoverPath,
                micVol, musicVol, voiceoverVol, 0, callback);
    }

    // ── Core pipeline ──────────────────────────────────────────────────────

    private static String doMix(
            Context context, String videoPath, String musicUrl, String voiceoverPath,
            float micVol, float musicVol, float voiceoverVol,
            int musicStartMs, MixCallback callback) throws Exception {

        progress(callback, 3);

        // Stage 1: Download music
        File musicFile = null;
        if (musicUrl != null && !musicUrl.isEmpty()) {
            progress(callback, 8);
            musicFile = downloadToCache(context, musicUrl);
        }
        progress(callback, 18);
        checkCancelled();

        // Stage 2: Decode mic from video
        short[] micPcm = extractPcmFromVideo(videoPath);
        progress(callback, 35);
        checkCancelled();

        // Stage 3: Decode music (looped to mic length, with start offset)
        short[] musicPcm = null;
        if (musicFile != null) {
            musicPcm = extractAndLoopPcm(musicFile.getAbsolutePath(),
                    micPcm.length, musicStartMs);
        }
        progress(callback, 52);
        checkCancelled();

        // Stage 4: Decode voiceover
        short[] voiceoverPcm = null;
        if (voiceoverPath != null && !voiceoverPath.isEmpty()) {
            File vf = new File(voiceoverPath);
            if (vf.exists() && vf.length() > 0)
                voiceoverPcm = extractPcmFromFile(voiceoverPath, micPcm.length, 0);
        }
        progress(callback, 62);
        checkCancelled();

        // Stage 5: Mix
        short[] raw = mixPcm(micPcm, micVol, musicPcm, musicVol, voiceoverPcm, voiceoverVol);
        progress(callback, 70);

        // Stage 6: Post-process
        applyFades(raw);
        normalise(raw);
        softLimit(raw);
        progress(callback, 78);
        checkCancelled();

        // Stage 7: Encode PCM → AAC
        File aacTemp = new File(context.getCacheDir(),
                "mixed_audio_" + System.currentTimeMillis() + ".aac");
        encodePcmToAac(raw, aacTemp.getAbsolutePath());
        progress(callback, 90);
        checkCancelled();

        // Stage 8: Mux video + audio
        String outputPath = new File(context.getCacheDir(),
                "final_reel_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        muxVideoAndAudio(videoPath, aacTemp.getAbsolutePath(), outputPath);
        progress(callback, 98);

        aacTemp.delete();
        if (musicFile != null) musicFile.delete();
        return outputPath;
    }

    // ── Download ──────────────────────────────────────────────────────────

    private static File downloadToCache(Context context, String urlStr) throws IOException {
        String key   = "music_" + Math.abs(urlStr.hashCode()) + ".aac";
        File   cache = new File(context.getCacheDir(), key);
        if (cache.exists() && cache.length() > 4096) return cache;

        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setRequestProperty("User-Agent", "CallX/2.0");
                int rc = conn.getResponseCode();
                if (rc == 503 && attempt < 3) {
                    Thread.sleep(1000L * attempt);
                    continue;
                }
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(cache)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                return cache;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(500L * attempt); } catch (InterruptedException ignored) {}
            } catch (InterruptedException ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw last != null ? last : new IOException("Download failed: " + urlStr);
    }

    // ── PCM extraction ────────────────────────────────────────────────────

    private static short[] extractPcmFromVideo(String videoPath) throws Exception {
        return extractPcmFromFile(videoPath, Integer.MAX_VALUE, 0);
    }

    /**
     * Decode audio from any MediaExtractor-supported container.
     * Handles stereo → mono downmix. Supports seeked start offset.
     */
    private static short[] extractPcmFromFile(
            String filePath, int maxSamples, int startOffsetMs) throws Exception {

        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(filePath);

        int track    = -1;
        int channels = 1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat fmt = ex.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                track    = i;
                channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                break;
            }
        }
        if (track < 0) { ex.release(); return new short[0]; }
        ex.selectTrack(track);
        if (startOffsetMs > 0)
            ex.seekTo(startOffsetMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        MediaFormat fmt = ex.getTrackFormat(track);
        MediaCodec  codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        final int ch = channels;
        ArrayList<Short>  samples   = new ArrayList<>(SAMPLE_RATE * 60);
        MediaCodec.BufferInfo info  = new MediaCodec.BufferInfo();
        boolean inputDone = false;

        while (!inputDone || samples.size() < maxSamples) {
            if (!inputDone) {
                int inIdx = codec.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                    int sz = ex.readSampleData(inBuf, 0);
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(info, 10_000);
            if (outIdx >= 0) {
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && info.size > 0) {
                    ShortBuffer sb = outBuf.asShortBuffer();
                    while (sb.hasRemaining() && samples.size() < maxSamples) {
                        if (ch >= 2) {
                            short l = sb.hasRemaining() ? sb.get() : 0;
                            short r = sb.hasRemaining() ? sb.get() : l;
                            samples.add((short)((l + r) >> 1));
                        } else {
                            samples.add(sb.get());
                        }
                    }
                }
                codec.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
        codec.stop(); codec.release(); ex.release();

        short[] result = new short[samples.size()];
        for (int i = 0; i < result.length; i++) result[i] = samples.get(i);
        return result;
    }

    /** Extract and loop music to fill targetSamples, with 10ms crossfade at boundaries. */
    private static short[] extractAndLoopPcm(
            String filePath, int targetSamples, int startOffsetMs) throws Exception {

        short[] raw = extractPcmFromFile(filePath, Integer.MAX_VALUE, startOffsetMs);
        if (raw.length == 0) return new short[0];
        if (raw.length >= targetSamples) return java.util.Arrays.copyOf(raw, targetSamples);

        short[] looped = new short[targetSamples];
        int written = 0;
        while (written < targetSamples) {
            int n = Math.min(raw.length, targetSamples - written);
            System.arraycopy(raw, 0, looped, written, n);
            written += n;
        }
        // 10ms crossfade at each loop boundary
        int xfade = Math.min(SAMPLE_RATE / 100, raw.length / 4);
        for (int rep = 1; rep * raw.length < targetSamples; rep++) {
            int boundary = rep * raw.length;
            for (int i = 0; i < xfade && boundary + i < targetSamples; i++) {
                float ratio = (float) i / xfade;
                looped[boundary + i] = (short)(looped[boundary + i] * ratio
                        + raw[i] * (1f - ratio));
            }
        }
        return looped;
    }

    // ── Mix ───────────────────────────────────────────────────────────────

    private static short[] mixPcm(
            short[] mic,   float micVol,
            short[] music, float musicVol,
            short[] vo,    float voVol) {

        short[] out = new short[mic.length];
        for (int i = 0; i < mic.length; i++) {
            float s = mic[i] * micVol;
            if (music != null && i < music.length) s += music[i] * musicVol;
            if (vo    != null && i < vo.length)    s += vo[i]    * voVol;
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(s)));
        }
        return out;
    }

    // ── Post-processing ───────────────────────────────────────────────────

    private static void applyFades(short[] pcm) {
        int fi = Math.min((int)(SAMPLE_RATE * FADE_IN_MS  / 1000f), pcm.length);
        int fo = Math.min((int)(SAMPLE_RATE * FADE_OUT_MS / 1000f), pcm.length);
        for (int i = 0; i < fi; i++) pcm[i] = (short)(pcm[i] * ((float) i / fi));
        for (int i = 0; i < fo; i++) {
            int pos = pcm.length - 1 - i;
            pcm[pos] = (short)(pcm[pos] * ((float) i / fo));
        }
    }

    private static void normalise(short[] pcm) {
        if (pcm.length == 0) return;
        double sumSq = 0;
        for (short s : pcm) sumSq += (double) s * s;
        double rms = Math.sqrt(sumSq / pcm.length);
        if (rms < 1.0) return;
        float gain = Math.min(4.0f, (float)(TARGET_RMS_RATIO * Short.MAX_VALUE / rms));
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) Math.max(Short.MIN_VALUE,
                    Math.min(Short.MAX_VALUE, Math.round(pcm[i] * gain)));
        }
    }

    /**
     * Soft-knee limiter: smoothly compresses peaks above threshold instead
     * of hard-clipping, preventing harsh digital distortion.
     */
    private static void softLimit(short[] pcm) {
        float thr  = LIMITER_THRESHOLD;
        float knee = thr * LIMITER_KNEE;
        float ceil = Short.MAX_VALUE;

        for (int i = 0; i < pcm.length; i++) {
            float s   = pcm[i];
            float abs = Math.abs(s);
            float start = thr - knee;
            if (abs > start) {
                float over = abs - start;
                float k2   = 2f * knee;
                float compressed;
                if (over < k2) {
                    compressed = start + over - (over * over) / (2f * k2);
                } else {
                    compressed = thr + (float) Math.sqrt((abs - thr) * knee) * 2f;
                }
                compressed = Math.min(compressed, ceil);
                pcm[i] = (short)(s < 0 ? -compressed : compressed);
            }
        }
    }

    // ── Encode PCM → AAC ──────────────────────────────────────────────────

    private static void encodePcmToAac(short[] pcm, String outPath) throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);

        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaMuxer muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxTrack = -1;
        boolean muxStarted = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        final int FRAME = 1024;
        int      offset     = 0;
        boolean  inputDone  = false;
        long     presentUs  = 0;
        long     frameDurUs = (long)(FRAME * 1_000_000L / SAMPLE_RATE);

        outer:
        while (true) {
            if (!inputDone) {
                int inIdx = enc.dequeueInputBuffer(10_000);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = enc.getInputBuffer(inIdx);
                    inBuf.clear();
                    if (offset >= pcm.length) {
                        enc.queueInputBuffer(inIdx, 0, 0, presentUs,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        int n = Math.min(FRAME, pcm.length - offset);
                        inBuf.asShortBuffer().put(pcm, offset, n);
                        offset += n;
                        enc.queueInputBuffer(inIdx, 0, n * 2, presentUs, 0);
                        presentUs += frameDurUs;
                    }
                }
            }
            int outIdx = enc.dequeueOutputBuffer(info, 10_000);
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxTrack = muxer.addTrack(enc.getOutputFormat());
                muxer.start(); muxStarted = true;
            } else if (outIdx >= 0) {
                ByteBuffer outBuf = enc.getOutputBuffer(outIdx);
                if (muxStarted && outBuf != null
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && info.size > 0) {
                    outBuf.position(info.offset);
                    outBuf.limit(info.offset + info.size);
                    muxer.writeSampleData(muxTrack, outBuf, info);
                }
                enc.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break outer;
            }
        }
        enc.stop(); enc.release();
        if (muxStarted) muxer.stop();
        muxer.release();
    }

    // ── Mux ───────────────────────────────────────────────────────────────

    private static void muxVideoAndAudio(
            String videoPath, String audioPath, String outputPath) throws Exception {

        MediaExtractor vex = new MediaExtractor();
        vex.setDataSource(videoPath);
        int vt = -1;
        for (int i = 0; i < vex.getTrackCount(); i++) {
            String mime = vex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) { vt = i; break; }
        }
        if (vt < 0) { vex.release(); throw new Exception("No video track: " + videoPath); }
        vex.selectTrack(vt);

        MediaExtractor aex = new MediaExtractor();
        aex.setDataSource(audioPath);
        int at = -1;
        for (int i = 0; i < aex.getTrackCount(); i++) {
            String mime = aex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { at = i; break; }
        }
        if (at < 0) { vex.release(); aex.release();
            throw new Exception("No audio track in mixed file"); }
        aex.selectTrack(at);

        MediaMuxer mux = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int mvt = mux.addTrack(vex.getTrackFormat(vt));
        int mat = mux.addTrack(aex.getTrackFormat(at));
        mux.start();

        ByteBuffer buf = ByteBuffer.allocate(2 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        for (MediaExtractor ex = vex; ; ) {
            buf.clear();
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            info.offset = 0; info.size = sz;
            info.presentationTimeUs = ex.getSampleTime();
            info.flags = ex.getSampleFlags();
            mux.writeSampleData(ex == vex ? mvt : mat, buf, info);
            ex.advance();
        }
        // write audio
        while (true) {
            buf.clear();
            int sz = aex.readSampleData(buf, 0);
            if (sz < 0) break;
            info.offset = 0; info.size = sz;
            info.presentationTimeUs = aex.getSampleTime();
            info.flags = aex.getSampleFlags();
            mux.writeSampleData(mat, buf, info);
            aex.advance();
        }

        mux.stop(); mux.release();
        vex.release(); aex.release();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static void progress(MixCallback cb, int pct) {
        if (!cancelled) mainHandler.post(() -> cb.onProgress(pct));
    }

    private static void checkCancelled() throws CancelledException {
        if (cancelled) throw new CancelledException();
    }

    static class CancelledException extends Exception {
        CancelledException() { super("Mix cancelled by user"); }
    }
}
