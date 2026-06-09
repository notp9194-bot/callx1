package com.callx.app.call;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dual-side voice call recorder using Android AudioRecord.
 *
 * Strategy:
 *   Primary   → AudioSource.VOICE_COMMUNICATION   (mic, with echo cancellation)
 *   Secondary → AudioSource.VOICE_UPLINK or VOICE_DOWNLINK  (remote side)
 *
 * On most Android devices, VOICE_COMMUNICATION already captures a mix of
 * local + remote audio during an active call (similar to WhatsApp behavior).
 * We additionally attempt VOICE_DOWNLINK for the remote stream.
 * Both streams are mixed sample-by-sample into a single mono WAV file.
 *
 * No WebRTC internal API required — works with stream-webrtc-android:1.1.2.
 */
public class CallRecorder {

    private static final String TAG = "CallRecorder";

    private static final int SAMPLE_RATE    = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2;

    private final android.content.Context context;
    private final AtomicBoolean recording = new AtomicBoolean(false);

    // Primary recorder: VOICE_COMMUNICATION (local mic + some remote bleed)
    private AudioRecord primaryRecord;
    // Secondary recorder: VOICE_DOWNLINK (remote stream, may not be available on all devices)
    private AudioRecord secondaryRecord;

    private int bufferSizeBytes;

    private ExecutorService primaryExecutor;
    private ExecutorService secondaryExecutor;
    private ExecutorService writerExecutor;

    // Ring buffer for remote samples — short[], indexed by write/read pointers
    private static final int REMOTE_BUF_CAPACITY = 512;
    private final java.util.concurrent.ArrayBlockingQueue<short[]> remoteQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(REMOTE_BUF_CAPACITY);

    private File outputFile;
    private FileOutputStream fos;
    private long totalSamplesWritten = 0;

    public CallRecorder(android.content.Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Start recording. Call this when the call is confirmed connected.
     * @return true if recording started.
     */
    public boolean start() {
        if (recording.get()) return false;

        bufferSizeBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSizeBytes <= 0) bufferSizeBytes = 3200;

        // Primary: VOICE_COMMUNICATION
        primaryRecord = tryCreateAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        if (primaryRecord == null) {
            // Fallback to MIC
            primaryRecord = tryCreateAudioRecord(MediaRecorder.AudioSource.MIC);
        }
        if (primaryRecord == null) {
            Log.e(TAG, "Cannot create primary AudioRecord");
            return false;
        }

        // Secondary: VOICE_DOWNLINK (remote audio) — optional, may fail on some devices
        secondaryRecord = tryCreateAudioRecord(MediaRecorder.AudioSource.VOICE_DOWNLINK);
        // VOICE_UPLINK as another attempt if DOWNLINK fails
        if (secondaryRecord == null) {
            secondaryRecord = tryCreateAudioRecord(MediaRecorder.AudioSource.VOICE_UPLINK);
        }
        // Not a hard failure — we proceed without secondary if unavailable

        outputFile = buildOutputFile();
        if (outputFile == null) {
            Log.e(TAG, "Cannot create output file");
            primaryRecord.release(); primaryRecord = null;
            if (secondaryRecord != null) { secondaryRecord.release(); secondaryRecord = null; }
            return false;
        }

        try {
            fos = new FileOutputStream(outputFile);
            writeWavHeader(fos, 0); // placeholder
        } catch (IOException e) {
            Log.e(TAG, "Cannot open output stream", e);
            primaryRecord.release(); primaryRecord = null;
            if (secondaryRecord != null) { secondaryRecord.release(); secondaryRecord = null; }
            return false;
        }

        recording.set(true);
        remoteQueue.clear();
        totalSamplesWritten = 0;

        primaryExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CallRec-Primary");
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });
        writerExecutor = Executors.newSingleThreadExecutor(r ->
                new Thread(r, "CallRec-Writer"));

        primaryExecutor.execute(this::primaryCaptureLoop);

        if (secondaryRecord != null) {
            secondaryExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CallRec-Secondary");
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            });
            secondaryExecutor.execute(this::secondaryCaptureLoop);
        }

        Log.i(TAG, "Recording started → " + outputFile.getAbsolutePath()
                + "  secondary=" + (secondaryRecord != null));
        return true;
    }

    /** Stop recording and finalize WAV header. */
    public void stop() {
        if (!recording.getAndSet(false)) return;

        stopAudioRecord(primaryRecord);   primaryRecord = null;
        stopAudioRecord(secondaryRecord); secondaryRecord = null;

        shutdownExecutor(primaryExecutor);
        shutdownExecutor(secondaryExecutor);
        shutdownExecutor(writerExecutor);
        primaryExecutor = secondaryExecutor = writerExecutor = null;

        finalizeWavHeader();
        try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        fos = null;

        Log.i(TAG, "Recording stopped. Samples=" + totalSamplesWritten
                + "  path=" + getOutputPath());
    }

    /** Absolute path of saved WAV file (valid after stop()). */
    public String getOutputPath() {
        return outputFile != null ? outputFile.getAbsolutePath() : null;
    }

    // ── Primary capture (local + bleed) ──────────────────────────────────

    private void primaryCaptureLoop() {
        if (primaryRecord == null) return;
        primaryRecord.startRecording();
        int samplesPerBuf = bufferSizeBytes / BYTES_PER_SAMPLE;
        short[] buf = new short[samplesPerBuf];

        while (recording.get()) {
            int read = primaryRecord.read(buf, 0, buf.length);
            if (read <= 0) continue;

            // Pull matching remote chunk
            short[] remote = remoteQueue.poll();

            // Mix
            short[] mixed = new short[read];
            for (int i = 0; i < read; i++) {
                int s = buf[i];
                if (remote != null && i < remote.length) s += remote[i];
                if (s >  Short.MAX_VALUE) s =  Short.MAX_VALUE;
                if (s <  Short.MIN_VALUE) s =  Short.MIN_VALUE;
                mixed[i] = (short) s;
            }
            writeSamples(mixed);
        }
    }

    // ── Secondary capture (remote stream) ────────────────────────────────

    private void secondaryCaptureLoop() {
        if (secondaryRecord == null) return;
        secondaryRecord.startRecording();
        int samplesPerBuf = bufferSizeBytes / BYTES_PER_SAMPLE;
        short[] buf = new short[samplesPerBuf];

        while (recording.get()) {
            int read = secondaryRecord.read(buf, 0, buf.length);
            if (read <= 0) continue;
            short[] chunk = new short[read];
            System.arraycopy(buf, 0, chunk, 0, read);
            if (!remoteQueue.offer(chunk)) {
                remoteQueue.poll();
                remoteQueue.offer(chunk);
            }
        }
    }

    // ── WAV write ─────────────────────────────────────────────────────────

    private synchronized void writeSamples(short[] samples) {
        if (fos == null || samples == null) return;
        try {
            byte[] bytes = new byte[samples.length * BYTES_PER_SAMPLE];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(samples);
            fos.write(bytes);
            totalSamplesWritten += samples.length;
        } catch (IOException e) {
            Log.e(TAG, "writeSamples error", e);
        }
    }

    // ── WAV header ────────────────────────────────────────────────────────

    private static void writeWavHeader(FileOutputStream out, long numSamples)
            throws IOException {
        int channels      = 1;
        int bitsPerSample = 16;
        int byteRate      = SAMPLE_RATE * channels * bitsPerSample / 8;
        int blockAlign    = channels * bitsPerSample / 8;
        long dataBytes    = numSamples * blockAlign;
        long chunkSize    = 36 + dataBytes;

        byte[] h = new byte[44];
        h[0]='R'; h[1]='I'; h[2]='F'; h[3]='F';
        putInt32LE(h,  4, (int) chunkSize);
        h[8]='W'; h[9]='A'; h[10]='V'; h[11]='E';
        h[12]='f'; h[13]='m'; h[14]='t'; h[15]=' ';
        putInt32LE(h, 16, 16);
        putInt16LE(h, 20, 1);
        putInt16LE(h, 22, channels);
        putInt32LE(h, 24, SAMPLE_RATE);
        putInt32LE(h, 28, byteRate);
        putInt16LE(h, 32, blockAlign);
        putInt16LE(h, 34, bitsPerSample);
        h[36]='d'; h[37]='a'; h[38]='t'; h[39]='a';
        putInt32LE(h, 40, (int) dataBytes);
        out.write(h);
    }

    private void finalizeWavHeader() {
        if (outputFile == null || !outputFile.exists()) return;
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {
            long dataBytes = totalSamplesWritten * BYTES_PER_SAMPLE;
            raf.seek(4);  writeInt32LE(raf, (int)(36 + dataBytes));
            raf.seek(40); writeInt32LE(raf, (int) dataBytes);
        } catch (IOException e) {
            Log.e(TAG, "finalizeWavHeader failed", e);
        }
    }

    private static void putInt32LE(byte[] b, int off, int v) {
        b[off]   = (byte)  v;
        b[off+1] = (byte) (v >>  8);
        b[off+2] = (byte) (v >> 16);
        b[off+3] = (byte) (v >> 24);
    }

    private static void putInt16LE(byte[] b, int off, int v) {
        b[off]   = (byte)  v;
        b[off+1] = (byte) (v >> 8);
    }

    private static void writeInt32LE(RandomAccessFile raf, int v) throws IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8)  & 0xFF);
        raf.write((v >> 16) & 0xFF);
        raf.write((v >> 24) & 0xFF);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private AudioRecord tryCreateAudioRecord(int source) {
        try {
            AudioRecord ar = new AudioRecord(
                    source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                    bufferSizeBytes * 4);
            if (ar.getState() == AudioRecord.STATE_INITIALIZED) return ar;
            ar.release();
        } catch (Exception e) {
            Log.d(TAG, "AudioRecord source=" + source + " failed: " + e.getMessage());
        }
        return null;
    }

    private static void stopAudioRecord(AudioRecord ar) {
        if (ar == null) return;
        try { ar.stop(); } catch (Exception ignored) {}
        try { ar.release(); } catch (Exception ignored) {}
    }

    private static void shutdownExecutor(ExecutorService ex) {
        if (ex == null) return;
        try {
            ex.shutdown();
            ex.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
    }

    // ── Output file ───────────────────────────────────────────────────────

    private File buildOutputFile() {
        try {
            File dir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dir = new File(context.getExternalFilesDir(
                        Environment.DIRECTORY_RECORDINGS), "CallRecordings");
            } else {
                dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC), "CallX2/Recordings");
            }
            if (!dir.exists() && !dir.mkdirs()) {
                dir = new File(context.getFilesDir(), "Recordings");
                dir.mkdirs();
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date());
            return new File(dir, "Call_" + stamp + ".wav");
        } catch (Exception e) {
            Log.e(TAG, "buildOutputFile failed", e);
            return null;
        }
    }
}
