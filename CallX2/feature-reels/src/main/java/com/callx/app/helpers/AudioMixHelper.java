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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AudioMixHelper v2 — Instagram-style "Record first, mix later" audio pipeline.
 *
 * Flow:
 *  1. Download music URL → local cache file  (SHA-256 cache key, 3-retry)
 *  2. Extract raw PCM from video mic track   (stereo→mono downmix)
 *  3. Extract raw PCM from music file        (resampled to mic sample rate)
 *  4. Mix PCM tracks with soft limiter       (no hard-clip distortion)
 *  5. Optionally mix voiceover PCM           (AAC_ADTS wrapped before decode)
 *  6. Encode mixed PCM → AAC via MediaCodec
 *  7. Interleaved mux video + audio → MP4   (timestamp-ordered samples)
 *
 * All 13 bugs fixed — see AudioMixHelper_FIXES.md for details.
 */
public class AudioMixHelper {

    private static final String TAG = "AudioMixHelper";

    // ── Public API ──────────────────────────────────────────────────────────

    public interface MixCallback {
        void onProgress(int percent);   // main thread, 0–100
        void onSuccess(String outputPath); // main thread
        void onError(Exception e);         // main thread
    }

    /** Returned by mixAndExport; call cancel() to abort the in-flight job. */
    public static final class MixHandle {
        private final Future<?>     future;
        private final AtomicBoolean cancelled;
        MixHandle(Future<?> f, AtomicBoolean c) { future = f; cancelled = c; }
        public void cancel() { cancelled.set(true); future.cancel(true); }
    }

    // FIX 12: cached thread pool so each job gets its own thread; cancel via Future
    private static final ExecutorService EXECUTOR =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "AudioMixHelper");
                t.setDaemon(true);
                return t;
            });

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * Full entry-point with music start-offset and cancellation support.
     *
     * @param musicStartMs  Start position inside the music track in milliseconds (0 = from start)
     * @return              MixHandle — call .cancel() to abort
     */
    public static MixHandle mixAndExport(
            Context context,
            String  videoPath,
            String  musicUrl,
            long    musicStartMs,
            String  voiceoverPath,
            float   micVol,
            float   musicVol,
            float   voiceoverVol,
            MixCallback callback) {

        AtomicBoolean cancelled = new AtomicBoolean(false);

        Future<?> future = EXECUTOR.submit(() -> {
            List<File> temps = new ArrayList<>();
            try {
                String out = doMix(context, videoPath, musicUrl, musicStartMs,
                        voiceoverPath, micVol, musicVol, voiceoverVol,
                        cancelled, temps, callback);
                if (!cancelled.get()) MAIN.post(() -> callback.onSuccess(out));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "Mix failed", e);
                if (!cancelled.get()) MAIN.post(() -> callback.onError(e));
            } finally {
                // FIX 11: always clean up on any path (success, error, cancel)
                for (File f : temps) { try { if (f != null) f.delete(); } catch (Exception ignored) {} }
            }
        });

        return new MixHandle(future, cancelled);
    }

    /** Convenience overload — no musicStartMs, no MixHandle. */
    public static void mixAndExport(
            Context context, String videoPath, String musicUrl,
            String voiceoverPath, float micVol, float musicVol, float voiceoverVol,
            MixCallback callback) {
        mixAndExport(context, videoPath, musicUrl, 0L,
                voiceoverPath, micVol, musicVol, voiceoverVol, callback);
    }

    // ── Core pipeline ───────────────────────────────────────────────────────

    private static String doMix(
            Context context, String videoPath, String musicUrl, long musicStartMs,
            String voiceoverPath, float micVol, float musicVol, float voiceoverVol,
            AtomicBoolean cancelled, List<File> temps, MixCallback cb) throws Exception {

        post(cb, 3);

        // Step 1 — download music (FIX 4: background; FIX 13: retry; FIX 5: SHA-256 key)
        File musicFile = null;
        if (musicUrl != null && !musicUrl.isEmpty()) {
            musicFile = downloadToCache(context, musicUrl, cancelled);
            temps.add(musicFile);
        }
        post(cb, 15);
        checkCancelled(cancelled);

        // Step 2 — decode mic (FIX 1: stereo→mono, FIX 3: short[] not ArrayList<Short>)
        AudioBuffer micBuf = extractAudio(videoPath, Integer.MAX_VALUE);
        int targetRate = micBuf.sampleRate > 0 ? micBuf.sampleRate : 44100;
        int targetLen  = micBuf.mono.length;
        post(cb, 35);
        checkCancelled(cancelled);

        // Step 3 — decode music (FIX 2: resample, FIX 9: musicStartMs trim)
        short[] musicPcm = null;
        if (musicFile != null) {
            AudioBuffer mb = extractAudio(musicFile.getAbsolutePath(), Integer.MAX_VALUE);
            short[] resampled = resample(mb.mono, mb.sampleRate, targetRate);
            long skipSamples = (long) musicStartMs * targetRate / 1000L;
            int startIdx = (int) Math.min(skipSamples, resampled.length);
            int copyLen  = (int) Math.min(resampled.length - startIdx, targetLen);
            musicPcm = new short[copyLen];
            System.arraycopy(resampled, startIdx, musicPcm, 0, copyLen);
        }
        post(cb, 52);
        checkCancelled(cancelled);

        // Step 4 — decode voiceover (FIX 6: ADTS→M4A wrap)
        short[] voPcm = null;
        if (voiceoverPath != null && !voiceoverPath.isEmpty()) {
            File vf = new File(voiceoverPath);
            if (vf.exists()) {
                File wrapped = wrapAdtsIfNeeded(context, vf);
                if (wrapped != vf) temps.add(wrapped);
                AudioBuffer vb = extractAudio(wrapped.getAbsolutePath(), Integer.MAX_VALUE);
                voPcm = resample(vb.mono, vb.sampleRate, targetRate);
                if (voPcm.length > targetLen) {
                    short[] t = new short[targetLen];
                    System.arraycopy(voPcm, 0, t, 0, targetLen);
                    voPcm = t;
                }
            }
        }
        post(cb, 62);
        checkCancelled(cancelled);

        // Step 5 — mix with soft limiter (FIX 7)
        short[] mixed = mixPcm(micBuf.mono, micVol, musicPcm, musicVol, voPcm, voiceoverVol);
        post(cb, 72);
        checkCancelled(cancelled);

        // Step 6 — encode → AAC/M4A (FIX 10: real per-frame progress)
        File aacTemp = new File(context.getCacheDir(),
                "mixed_" + System.currentTimeMillis() + ".m4a");
        temps.add(aacTemp);
        encodePcmToAac(mixed, targetRate, aacTemp.getAbsolutePath(), cancelled,
                p -> post(cb, 72 + p / 6));   // maps 0-100 → 72-88
        post(cb, 88);
        checkCancelled(cancelled);

        // Step 7 — interleaved mux (FIX 8)
        String outputPath = new File(context.getCacheDir(),
                "reel_final_" + System.currentTimeMillis() + ".mp4").getAbsolutePath();
        muxInterleaved(videoPath, aacTemp.getAbsolutePath(), outputPath, cancelled);
        post(cb, 98);

        return outputPath;
    }

    // ── AudioBuffer ─────────────────────────────────────────────────────────

    private static class AudioBuffer {
        final short[] mono;
        final int     sampleRate;
        AudioBuffer(short[] mono, int sampleRate) {
            this.mono = mono; this.sampleRate = sampleRate;
        }
    }

    // ── FIX 4 / 5 / 13: Download with SHA-256 cache key & retry ─────────

    private static File downloadToCache(Context ctx, String urlStr,
                                        AtomicBoolean cancelled) throws IOException {
        String hash     = sha256(urlStr);
        File   cached   = new File(ctx.getCacheDir(), "music_" + hash + ".aac");
        if (cached.exists() && cached.length() > 0) return cached;

        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (cancelled.get()) throw new IOException("Cancelled");
            try {
                URL url  = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15_000);
                conn.setReadTimeout(30_000);
                conn.setRequestProperty("User-Agent", "CallX/2.0");
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(cached)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (cancelled.get()) { cached.delete(); throw new IOException("Cancelled"); }
                        out.write(buf, 0, n);
                    }
                } finally { conn.disconnect(); }
                return cached;
            } catch (IOException e) {
                last = e;
                Log.w(TAG, "Download attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < 3) try { Thread.sleep(500L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt(); throw new IOException("Cancelled");
                }
            }
        }
        throw last;
    }

    // ── FIX 1 / 3: Extract audio as mono short[] ────────────────────────

    private static AudioBuffer extractAudio(String path, int maxMonoSamples) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(path);

        int    trackIdx  = -1;
        int    channels  = 1;
        int    rate      = 44100;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i);
            String mime   = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                trackIdx = i;
                if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                break;
            }
        }
        if (trackIdx < 0) { ex.release(); return new AudioBuffer(new short[0], rate); }

        ex.selectTrack(trackIdx);
        MediaFormat fmt = ex.getTrackFormat(trackIdx);
        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);

        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0);
        codec.start();

        // FIX 3: primitive short[] with manual growth
        int    cap     = Math.min(rate * 60 * channels, 44100 * 60 * 2);
        short[] rawBuf = new short[Math.max(cap, 8192)];
        int    rawLen  = 0;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inDone = false, outDone = false;

        while (!outDone) {
            if (!inDone) {
                int idx = codec.dequeueInputBuffer(10_000);
                if (idx >= 0) {
                    ByteBuffer ib   = codec.getInputBuffer(idx);
                    int sampleSize  = ex.readSampleData(ib, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inDone = true;
                    } else {
                        codec.queueInputBuffer(idx, 0, sampleSize, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int idx = codec.dequeueOutputBuffer(info, 10_000);
            if (idx >= 0) {
                ByteBuffer ob = codec.getOutputBuffer(idx);
                if (ob != null && info.size > 0) {
                    ShortBuffer sb  = ob.asShortBuffer();
                    int avail       = sb.remaining();
                    if (rawLen + avail > rawBuf.length) {
                        short[] grown = new short[Math.max(rawBuf.length * 2, rawLen + avail)];
                        System.arraycopy(rawBuf, 0, grown, 0, rawLen);
                        rawBuf = grown;
                    }
                    sb.get(rawBuf, rawLen, avail);
                    rawLen += avail;
                }
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
            }
        }
        codec.stop(); codec.release(); ex.release();

        // FIX 1: stereo/multichannel → mono
        final int ch = channels;
        short[] mono;
        if (ch >= 2) {
            int monoLen = Math.min(rawLen / ch, maxMonoSamples);
            mono = new short[monoLen];
            for (int i = 0; i < monoLen; i++) {
                long sum = 0;
                for (int c = 0; c < ch; c++) { int j = i*ch+c; if (j < rawLen) sum += rawBuf[j]; }
                mono[i] = (short)(sum / ch);
            }
        } else {
            int len = Math.min(rawLen, maxMonoSamples);
            mono = new short[len];
            System.arraycopy(rawBuf, 0, mono, 0, len);
        }
        return new AudioBuffer(mono, rate);
    }

    // ── FIX 2: Linear resampler ──────────────────────────────────────────

    private static short[] resample(short[] src, int srcRate, int dstRate) {
        if (srcRate == dstRate || src.length == 0) return src;
        double ratio  = (double) srcRate / dstRate;
        int    len    = (int)(src.length / ratio);
        short[] dst   = new short[len];
        for (int i = 0; i < len; i++) {
            double si  = i * ratio;
            int lo     = (int) si;
            int hi     = Math.min(lo + 1, src.length - 1);
            double frac = si - lo;
            dst[i] = (short)(src[lo] * (1.0 - frac) + src[hi] * frac);
        }
        return dst;
    }

    // ── FIX 6: Wrap raw AAC_ADTS → M4A container ───────────────────────

    private static File wrapAdtsIfNeeded(Context ctx, File f) throws Exception {
        byte[] hdr = new byte[4];
        try (FileInputStream fis = new FileInputStream(f)) { fis.read(hdr); }
        boolean isAdts = (hdr[0] & 0xFF) == 0xFF && (hdr[1] & 0xF0) == 0xF0;
        if (!isAdts) return f;

        File out = new File(ctx.getCacheDir(), "vo_" + System.currentTimeMillis() + ".m4a");
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(f.getAbsolutePath());
        int track = -1;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) { track = i; break; }
        }
        if (track < 0) { ex.release(); return f; }
        ex.selectTrack(track);
        MediaFormat fmt = ex.getTrackFormat(track);
        MediaMuxer mux  = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxTrack    = mux.addTrack(fmt);
        mux.start();
        ByteBuffer buf  = ByteBuffer.allocate(256 * 1024);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (true) {
            buf.clear();
            int sz = ex.readSampleData(buf, 0);
            if (sz < 0) break;
            bi.offset = 0; bi.size = sz;
            bi.presentationTimeUs = ex.getSampleTime();
            bi.flags = ex.getSampleFlags();
            mux.writeSampleData(muxTrack, buf, bi);
            ex.advance();
        }
        mux.stop(); mux.release(); ex.release();
        return out;
    }

    // ── FIX 7: Mix with soft limiter ─────────────────────────────────────

    private static short[] mixPcm(short[] mic, float mv,
                                   short[] music, float muv,
                                   short[] vo, float vov) {
        int len   = mic.length;
        short[] o = new short[len];
        for (int i = 0; i < len; i++) {
            float s = mic[i] * mv;
            if (music != null && i < music.length) s += music[i] * muv;
            if (vo    != null && i < vo.length)    s += vo[i]    * vov;
            // FIX 7: soft limiter — cubic saturation above ±24000
            float abs = Math.abs(s);
            if (abs > 24000f) {
                float excess = (abs - 24000f) / Short.MAX_VALUE;
                float soft   = excess / (1f + excess);
                s = Math.signum(s) * (24000f + soft * (Short.MAX_VALUE - 24000f));
            }
            if (s > Short.MAX_VALUE) s = Short.MAX_VALUE;
            else if (s < Short.MIN_VALUE) s = Short.MIN_VALUE;
            o[i] = (short) s;
        }
        return o;
    }

    // ── Encode PCM → AAC (FIX 10: real progress) ────────────────────────

    private interface ProgressHook { void report(int pct); }

    private static void encodePcmToAac(short[] pcm, int rate, String outPath,
                                        AtomicBoolean cancelled,
                                        ProgressHook hook) throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();

        MediaMuxer mux  = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxTrack    = -1;
        boolean started = false;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int FRAME = 1024, offset = 0;
        boolean inDone  = false;
        long pts  = 0, frameDur = (long)(FRAME * 1_000_000L / rate);
        int  total = (pcm.length + FRAME - 1) / FRAME, done = 0;

        try {
            while (true) {
                checkCancelled(cancelled);
                if (!inDone) {
                    int idx = enc.dequeueInputBuffer(10_000);
                    if (idx >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(idx);
                        ib.clear();
                        if (offset >= pcm.length) {
                            enc.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            int n = Math.min(FRAME, pcm.length - offset);
                            ib.asShortBuffer().put(pcm, offset, n);
                            enc.queueInputBuffer(idx, 0, n * 2, pts, 0);
                            offset += n; pts += frameDur; done++;
                            if (hook != null && total > 0) hook.report(100 * done / total);
                        }
                    }
                }
                int idx = enc.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxTrack = mux.addTrack(enc.getOutputFormat());
                    mux.start(); started = true;
                } else if (idx >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(idx);
                    if (started && ob != null
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && info.size > 0) {
                        ob.position(info.offset); ob.limit(info.offset + info.size);
                        mux.writeSampleData(muxTrack, ob, info);
                    }
                    enc.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
        } finally {
            enc.stop(); enc.release();
            if (started) mux.stop();
            mux.release();
        }
    }

    // ── FIX 8: Interleaved mux ───────────────────────────────────────────

    private static void muxInterleaved(String videoPath, String audioPath,
                                        String outPath, AtomicBoolean cancelled) throws Exception {
        MediaExtractor vEx = new MediaExtractor(); vEx.setDataSource(videoPath);
        int vTrack = -1;
        for (int i = 0; i < vEx.getTrackCount(); i++) {
            String m = vEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("video/")) { vTrack = i; break; }
        }
        if (vTrack < 0) { vEx.release(); throw new Exception("No video track: " + videoPath); }
        vEx.selectTrack(vTrack);

        MediaExtractor aEx = new MediaExtractor(); aEx.setDataSource(audioPath);
        int aTrack = -1;
        for (int i = 0; i < aEx.getTrackCount(); i++) {
            String m = aEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) { aTrack = i; break; }
        }
        if (aTrack < 0) { vEx.release(); aEx.release(); throw new Exception("No audio track in mixed"); }
        aEx.selectTrack(aTrack);

        MediaMuxer mux = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int mvt = mux.addTrack(vEx.getTrackFormat(vTrack));
        int mat = mux.addTrack(aEx.getTrackFormat(aTrack));
        mux.start();

        ByteBuffer buf = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        long vPts = pts(vEx), aPts = pts(aEx);
        boolean vOk = vPts != Long.MAX_VALUE, aOk = aPts != Long.MAX_VALUE;

        try {
            while (vOk || aOk) {
                checkCancelled(cancelled);
                if (vOk && (!aOk || vPts <= aPts)) {
                    buf.clear();
                    int sz = vEx.readSampleData(buf, 0);
                    if (sz > 0) {
                        bi.offset = 0; bi.size = sz;
                        bi.presentationTimeUs = vPts; bi.flags = vEx.getSampleFlags();
                        mux.writeSampleData(mvt, buf, bi);
                    }
                    vEx.advance(); vPts = pts(vEx);
                    if (vPts == Long.MAX_VALUE) vOk = false;
                } else {
                    buf.clear();
                    int sz = aEx.readSampleData(buf, 0);
                    if (sz > 0) {
                        bi.offset = 0; bi.size = sz;
                        bi.presentationTimeUs = aPts; bi.flags = aEx.getSampleFlags();
                        mux.writeSampleData(mat, buf, bi);
                    }
                    aEx.advance(); aPts = pts(aEx);
                    if (aPts == Long.MAX_VALUE) aOk = false;
                }
            }
        } finally {
            mux.stop(); mux.release(); vEx.release(); aEx.release();
        }
    }

    private static long pts(MediaExtractor ex) {
        long t = ex.getSampleTime(); return t < 0 ? Long.MAX_VALUE : t;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static void checkCancelled(AtomicBoolean c) throws InterruptedException {
        if (c != null && c.get()) throw new InterruptedException("Cancelled");
    }

    private static void post(MixCallback cb, int pct) {
        MAIN.post(() -> cb.onProgress(pct));
    }

    /** FIX 5: SHA-256 of URL → no 32-bit hash collisions */
    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "fb_" + Integer.toHexString(s.hashCode());
        }
    }

    private AudioMixHelper() {}
}
