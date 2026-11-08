package com.callx.app.compose;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v785 (item 18) — turns ANY audio track Android can decode (MP3, Opus, Vorbis, AMR, FLAC, WAV, AAC in odd containers …)
 * into a small AAC-LC {@code .m4a} so {@link TemplateVideoCompositor} can mux it into the template mp4.
 *
 * Before v785 the compositor only copied an AAC track as-is, so a clip whose sound was MP3 / Opus / … gave a SILENT video.
 *
 * Two plain phases (a template is ≤ 60 s, so the PCM of the wanted window is at most ~11 MB):
 *   1. decode [startUs, startUs + durUs) to 16-bit PCM (float PCM is converted, > 2 channels are reduced to the first two);
 *   2. encode that PCM with the platform AAC encoder into an MPEG-4 audio file with timestamps starting at 0.
 *
 * Self-contained, call from a background thread. Returns false (and leaves no file) on any failure — the caller then simply
 * keeps the video silent, exactly as before.
 */
final class TemplateAudioTranscoder {

    private static final String TAG = "TplAudioTx";
    private static final long TIMEOUT_US = 10_000L;
    private static final int MAX_SECONDS = 65;

    private TemplateAudioTranscoder() { }

    /** @return the first audio track's mime of {@code path}, or null when the file has none / cannot be read. */
    @Nullable
    static String audioMime(@NonNull String path) {
        MediaExtractor ex = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(path);
            for (int i = 0; i < ex.getTrackCount(); i++) {
                final String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return mime;
            }
        } catch (Exception ignored) {
        } finally {
            if (ex != null) ex.release();
        }
        return null;
    }

    /** Decoded window: interleaved 16-bit PCM. */
    private static final class Pcm {
        short[] data = new short[1 << 16];
        int len;                  // shorts used
        int sampleRate, channels;

        void append(short v) {
            if (len == data.length) data = java.util.Arrays.copyOf(data, Math.min(data.length * 2, data.length + (1 << 22)));
            data[len++] = v;
        }
    }

    static boolean toAac(@NonNull String src, long startUs, long durUs, @NonNull File out, @Nullable AtomicBoolean cancel) {
        try {
            final Pcm pcm = decode(src, Math.max(0L, startUs), Math.max(1L, durUs), cancel);
            if (pcm == null || pcm.len < pcm.channels * 1024) return false;   // nothing usable (less than one AAC frame)
            return encode(pcm, out, cancel);
        } catch (Throwable t) {
            Log.w(TAG, "transcode failed — video stays silent", t);
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return false;
        }
    }

    // ── decode ───────────────────────────────────────────────────────────────

    @Nullable
    private static Pcm decode(String src, long startUs, long durUs, @Nullable AtomicBoolean cancel) throws Exception {
        MediaExtractor ex = null;
        MediaCodec dec = null;
        try {
            ex = new MediaExtractor();
            ex.setDataSource(src);
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                final MediaFormat f = ex.getTrackFormat(i);
                final String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0 || fmt == null) return null;
            ex.selectTrack(track);
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();

            final Pcm pcm = new Pcm();
            int sr = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            boolean pcmFloat = false;

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;
            long wantFrames = Long.MAX_VALUE;     // frames (per channel) still to keep
            long idleSince = System.nanoTime();
            final long endLimitUs = startUs + durUs;

            while (!outEos) {
                if (cancel != null && cancel.get()) return null;
                if (System.nanoTime() - idleSince > 15_000_000_000L) throw new IllegalStateException("decoder stalled");
                if (!inEos) {
                    final int ii = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (ii >= 0) {
                        final ByteBuffer ib = dec.getInputBuffer(ii);
                        final long t = ex.getSampleTime();
                        final int n = ib == null || t < 0 ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0 || t > endLimitUs + 300_000L) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, t, 0);
                            ex.advance();
                        }
                    }
                }
                final int oi = dec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    final MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    pcmFloat = android.os.Build.VERSION.SDK_INT >= 24 && of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT;
                } else if (oi >= 0) {
                    idleSince = System.nanoTime();
                    if (info.size > 0 && wantFrames > 0) {
                        final ByteBuffer ob = dec.getOutputBuffer(oi);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ob.order(ByteOrder.nativeOrder());
                            if (pcm.sampleRate == 0) {
                                pcm.sampleRate = sr;
                                pcm.channels = Math.max(1, Math.min(2, ch));
                                final long maxFrames = Math.min((long) MAX_SECONDS * sr, durUs * sr / 1_000_000L + 1);
                                wantFrames = Math.max(1, maxFrames);
                            }
                            final int bytesPer = pcmFloat ? 4 : 2;
                            final int frames = info.size / (bytesPer * Math.max(1, ch));
                            // drop what lies before the wanted start (the seek lands on the previous sync sample)
                            int skip = 0;
                            if (info.presentationTimeUs < startUs) {
                                skip = (int) Math.min(frames, (startUs - info.presentationTimeUs) * sr / 1_000_000L);
                            }
                            final ShortBuffer sb = pcmFloat ? null : ob.asShortBuffer();
                            final FloatBuffer fb = pcmFloat ? ob.asFloatBuffer() : null;
                            for (int f = skip; f < frames && wantFrames > 0; f++, wantFrames--) {
                                for (int c = 0; c < pcm.channels; c++) {
                                    final int idx = f * ch + c;
                                    if (pcmFloat) {
                                        final float v = Math.max(-1f, Math.min(1f, fb.get(idx)));
                                        pcm.append((short) Math.round(v * 32767f));
                                    } else {
                                        pcm.append(sb.get(idx));
                                    }
                                }
                            }
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    if (wantFrames <= 0) outEos = true;
                }
            }
            return pcm.sampleRate == 0 ? null : pcm;
        } finally {
            try { if (dec != null) dec.stop(); } catch (Throwable ignored) { }
            try { if (dec != null) dec.release(); } catch (Throwable ignored) { }
            try { if (ex != null) ex.release(); } catch (Throwable ignored) { }
        }
    }

    // ── encode ───────────────────────────────────────────────────────────────

    private static boolean encode(Pcm pcm, File out, @Nullable AtomicBoolean cancel) throws Exception {
        MediaCodec enc = null;
        MediaMuxer mux = null;
        boolean muxStarted = false;
        try {
            final MediaFormat ef = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, pcm.sampleRate, pcm.channels);
            ef.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, pcm.channels == 1 ? 96_000 : 128_000);
            ef.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int track = -1;

            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int pos = 0;                       // shorts already queued
            long framesQueued = 0;
            boolean inEos = false, outEos = false;
            long idleSince = System.nanoTime();
            while (!outEos) {
                if (cancel != null && cancel.get()) return false;
                if (System.nanoTime() - idleSince > 15_000_000_000L) throw new IllegalStateException("encoder stalled");
                if (!inEos) {
                    final int ii = enc.dequeueInputBuffer(TIMEOUT_US);
                    if (ii >= 0) {
                        final ByteBuffer ib = enc.getInputBuffer(ii);
                        if (ib == null) {
                            enc.queueInputBuffer(ii, 0, 0, framesQueued * 1_000_000L / pcm.sampleRate, 0);
                            continue;
                        }
                        ib.clear();
                        ib.order(ByteOrder.nativeOrder());
                        final int cap = ib.capacity() / 2 / pcm.channels * pcm.channels;   // whole frames only
                        final int n = Math.min(cap, pcm.len - pos);
                        if (n <= 0) {
                            enc.queueInputBuffer(ii, 0, 0, framesQueued * 1_000_000L / pcm.sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            ib.asShortBuffer().put(pcm.data, pos, n);
                            enc.queueInputBuffer(ii, 0, n * 2, framesQueued * 1_000_000L / pcm.sampleRate, 0);
                            pos += n;
                            framesQueued += n / pcm.channels;
                        }
                        idleSince = System.nanoTime();
                    }
                }
                final int oi = enc.dequeueOutputBuffer(info, TIMEOUT_US);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxStarted = true;
                } else if (oi >= 0) {
                    idleSince = System.nanoTime();
                    final ByteBuffer ob = enc.getOutputBuffer(oi);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;   // the muxer got it with the format
                    if (info.size > 0 && muxStarted && ob != null) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(track, ob, info);
                    }
                    enc.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                }
            }
            if (muxStarted) { mux.stop(); muxStarted = false; }
            mux.release();
            mux = null;
            return out.exists() && out.length() > 512;
        } finally {
            try { if (enc != null) enc.stop(); } catch (Throwable ignored) { }
            try { if (enc != null) enc.release(); } catch (Throwable ignored) { }
            try { if (mux != null) { if (muxStarted) mux.stop(); mux.release(); } } catch (Throwable ignored) { }
            if (!out.exists() || out.length() <= 512) //noinspection ResultOfMethodCallIgnored
                out.delete();
        }
    }
}
