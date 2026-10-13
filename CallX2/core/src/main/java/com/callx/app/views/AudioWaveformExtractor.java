package com.callx.app.views;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AudioWaveformExtractor — builds a REAL loudness waveform for a track.
 *
 * Decodes the audio with MediaExtractor + MediaCodec (any format Android can play),
 * measures RMS energy per time bucket across the whole track and normalises it to
 * 0..1 bar heights. Runs on a single background thread, is cancellable, and caches
 * the result on disk (cacheDir/waveforms) so re-opening the same song is instant.
 *
 * Failure (network error, unsupported codec, too long, timeout) => onFailed(); the
 * caller simply keeps the placeholder waveform.
 */
public final class AudioWaveformExtractor {

    public interface Callback {
        /** Main thread. amps.length == BUCKETS, values in 0.14..1. */
        void onResult(@NonNull float[] amps);
        /** Main thread. */
        void onFailed();
    }

    /** Handle to cancel an in-flight extraction (e.g. user picked another song). */
    public static final class Job {
        private volatile boolean cancelled;
        public void cancel() { cancelled = true; }
        public boolean isCancelled() { return cancelled; }
    }

    public static final int BUCKETS = 160;
    private static final int CACHE_VERSION = 1;
    private static final long MAX_DURATION_US = 15L * 60 * 1_000_000L;
    private static final long TIMEOUT_MS = 60_000L;
    private static final int MAX_CACHE_FILES = 60;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio-waveform");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private AudioWaveformExtractor() {}

    public static Job extract(@NonNull Context context, String url, int durationHintMs,
                              @NonNull Callback cb) {
        final Context app = context.getApplicationContext();
        final Job job = new Job();
        if (url == null || url.isEmpty()) {
            MAIN.post(cb::onFailed);
            return job;
        }
        EXEC.execute(() -> {
            if (job.cancelled) return;
            float[] result = null;
            try {
                result = readCache(app, url);
                if (result == null) {
                    result = decode(app, url, durationHintMs, job);
                    if (result != null && !job.cancelled) writeCache(app, url, result);
                }
            } catch (Throwable ignored) {
                result = null;
            }
            if (job.cancelled) return;
            final float[] out = result;
            MAIN.post(() -> {
                if (job.cancelled) return;
                if (out != null) cb.onResult(out); else cb.onFailed();
            });
        });
        return job;
    }

    // ── Decode ──────────────────────────────────────────────────────────

    private static float[] decode(Context ctx, String url, int durationHintMs, Job job) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            Uri uri = Uri.parse(url);
            if (uri.getScheme() == null) ex.setDataSource(url);
            else ex.setDataSource(ctx, uri, null);

            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) return null;
            ex.selectTrack(track);

            long durUs = durationHintMs > 0 ? durationHintMs * 1000L : 0L;
            if (durUs <= 0 && fmt.containsKey(MediaFormat.KEY_DURATION)) durUs = fmt.getLong(MediaFormat.KEY_DURATION);
            if (durUs <= 0 || durUs > MAX_DURATION_US) return null;

            int channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            final double[] energy = new double[BUCKETS];
            final long[] count = new long[BUCKETS];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false, outDone = false;
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;

            while (!outDone) {
                if (job.cancelled || System.currentTimeMillis() > deadline) return null;
                if (!inDone) {
                    int ii = codec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(ii);
                        int sz = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (sz < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, sz, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ShortBuffer sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer();
                            int b = (int) Math.min(BUCKETS - 1, Math.max(0, info.presentationTimeUs * BUCKETS / durUs));
                            int ch = Math.max(1, channels);
                            int n = sb.remaining();
                            double sum = 0; long c = 0;
                            // Stride over frames: plenty of precision for a ~1.5s bucket, much cheaper.
                            for (int p = 0; p + ch <= n; p += ch * 4) {
                                double m = 0;
                                for (int k = 0; k < ch; k++) m += sb.get(p + k);
                                m /= (ch * 32768.0);
                                sum += m * m; c++;
                            }
                            energy[b] += sum; count[b] += c;
                        }
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            }
            return normalise(energy, count);
        } finally {
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} try { codec.release(); } catch (Exception ignored) {} }
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    private static float[] normalise(double[] energy, long[] count) {
        float[] v = new float[BUCKETS];
        boolean any = false;
        for (int i = 0; i < BUCKETS; i++) {
            if (count[i] > 0) { v[i] = (float) Math.sqrt(energy[i] / count[i]); any = true; }
            else v[i] = -1f;
        }
        if (!any) return null;
        // Fill empty buckets from the nearest known neighbour (e.g. when the duration hint overshoots).
        float last = 0f;
        for (int i = 0; i < BUCKETS; i++) { if (v[i] >= 0) last = v[i]; else v[i] = last; }
        for (int i = BUCKETS - 1; i >= 0; i--) { if (count[i] > 0) last = v[i]; else if (v[i] == 0f) v[i] = last; }
        // 3-tap smoothing so single loud hits don't spike the bars.
        float[] s = new float[BUCKETS];
        float max = 1e-6f;
        for (int i = 0; i < BUCKETS; i++) {
            float a = v[Math.max(0, i - 1)], b = v[i], c = v[Math.min(BUCKETS - 1, i + 1)];
            s[i] = 0.25f * a + 0.5f * b + 0.25f * c;
            if (s[i] > max) max = s[i];
        }
        for (int i = 0; i < BUCKETS; i++) {
            s[i] = 0.14f + 0.86f * (float) Math.pow(s[i] / max, 0.7);
        }
        return s;
    }

    // ── Disk cache ──────────────────────────────────────────────────────

    private static File cacheFile(Context ctx, String url) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(url.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        File dir = new File(ctx.getCacheDir(), "waveforms");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, sb + ".wf");
    }

    private static float[] readCache(Context ctx, String url) {
        try {
            File f = cacheFile(ctx, url);
            if (!f.exists() || f.length() != 2 + BUCKETS) return null;
            byte[] buf = new byte[2 + BUCKETS];
            try (FileInputStream in = new FileInputStream(f)) {
                if (in.read(buf) != buf.length) return null;
            }
            if (buf[0] != CACHE_VERSION || (buf[1] & 0xFF) != (BUCKETS & 0xFF)) return null;
            float[] out = new float[BUCKETS];
            for (int i = 0; i < BUCKETS; i++) out[i] = (buf[2 + i] & 0xFF) / 255f;
            return out;
        } catch (Throwable t) { return null; }
    }

    private static void writeCache(Context ctx, String url, float[] amps) {
        try {
            File f = cacheFile(ctx, url);
            byte[] buf = new byte[2 + BUCKETS];
            buf[0] = CACHE_VERSION; buf[1] = (byte) (BUCKETS & 0xFF);
            for (int i = 0; i < BUCKETS; i++) buf[2 + i] = (byte) Math.round(Math.max(0f, Math.min(1f, amps[i])) * 255f);
            try (FileOutputStream out = new FileOutputStream(f)) { out.write(buf); }
            File[] all = f.getParentFile().listFiles();
            if (all != null && all.length > MAX_CACHE_FILES) {
                java.util.Arrays.sort(all, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
                for (int i = 0; i < all.length - MAX_CACHE_FILES; i++) all[i].delete();
            }
        } catch (Throwable ignored) {}
    }
}
