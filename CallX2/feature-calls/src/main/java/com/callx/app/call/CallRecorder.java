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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Voice call recorder — captures local mic audio via AudioRecord.
 *
 * Uses VOICE_COMMUNICATION source (mic with echo cancellation, same as
 * what WebRTC uses). On most devices this captures both sides naturally
 * because the audio mixer blends earpiece output back into the mic path.
 *
 * VOICE_DOWNLINK is intentionally NOT used — it requires a privileged
 * system permission on Android 10+ and causes SecurityException crashes.
 *
 * Output: 16-bit PCM mono WAV, 16kHz.
 */
public class CallRecorder {

    private static final String TAG = "CallRecorder";

    private static final int SAMPLE_RATE     = 16000;
    private static final int CHANNEL_CONFIG  = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT    = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2;

    private final android.content.Context context;
    private final AtomicBoolean recording = new AtomicBoolean(false);

    private AudioRecord audioRecord;
    private ExecutorService captureExecutor;

    private File outputFile;
    private FileOutputStream fos;
    private long totalSamplesWritten = 0;

    public CallRecorder(android.content.Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Start recording. Safe to call from any thread.
     * Returns true if recording started successfully.
     */
    public boolean start() {
        if (recording.get()) return false;

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBuf <= 0) minBuf = 3200;
        final int bufSize = minBuf * 4;

        // Try audio sources in order of preference
        audioRecord = tryCreate(MediaRecorder.AudioSource.VOICE_COMMUNICATION, bufSize);
        if (audioRecord == null)
            audioRecord = tryCreate(MediaRecorder.AudioSource.MIC, bufSize);
        if (audioRecord == null) {
            Log.e(TAG, "No usable AudioRecord source");
            return false;
        }

        outputFile = buildOutputFile();
        if (outputFile == null) {
            audioRecord.release();
            audioRecord = null;
            Log.e(TAG, "Cannot create output file");
            return false;
        }

        try {
            fos = new FileOutputStream(outputFile);
            writeWavHeader(fos, 0);
        } catch (IOException e) {
            Log.e(TAG, "Cannot open output stream", e);
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        totalSamplesWritten = 0;
        recording.set(true);

        final int samplesPerBuf = bufSize / BYTES_PER_SAMPLE;
        captureExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CallRec-Capture");
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });
        captureExecutor.execute(() -> captureLoop(samplesPerBuf));

        Log.i(TAG, "Recording started → " + outputFile.getAbsolutePath());
        return true;
    }

    /** Stop recording and finalize the WAV file. Safe to call multiple times. */
    public void stop() {
        if (!recording.getAndSet(false)) return;

        // Stop AudioRecord first so captureLoop exits
        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Exception ignored) {}

        // Wait for capture thread to finish writing
        if (captureExecutor != null) {
            try {
                captureExecutor.shutdown();
                captureExecutor.awaitTermination(4, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            captureExecutor = null;
        }

        finalizeWavHeader();

        try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        fos = null;

        Log.i(TAG, "Recording stopped. samples=" + totalSamplesWritten
                + "  path=" + getOutputPath());
    }

    /** Path to saved WAV file. Valid after stop(). */
    public String getOutputPath() {
        return outputFile != null ? outputFile.getAbsolutePath() : null;
    }

    // ── Capture loop ──────────────────────────────────────────────────────

    private void captureLoop(int samplesPerBuf) {
        AudioRecord ar = audioRecord;
        if (ar == null) return;
        try {
            ar.startRecording();
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            return;
        }

        short[] buf = new short[samplesPerBuf];
        while (recording.get()) {
            int read = ar.read(buf, 0, buf.length);
            if (read > 0) writeSamples(buf, read);
        }
    }

    // ── Write ─────────────────────────────────────────────────────────────

    private synchronized void writeSamples(short[] buf, int count) {
        if (fos == null) return;
        try {
            byte[] bytes = new byte[count * BYTES_PER_SAMPLE];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(buf, 0, count);
            fos.write(bytes);
            totalSamplesWritten += count;
        } catch (IOException e) {
            Log.e(TAG, "write error", e);
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

        byte[] h = new byte[44];
        h[0]='R'; h[1]='I'; h[2]='F'; h[3]='F';
        putInt32LE(h,  4, (int)(36 + dataBytes));
        h[8]='W'; h[9]='A'; h[10]='V'; h[11]='E';
        h[12]='f'; h[13]='m'; h[14]='t'; h[15]=' ';
        putInt32LE(h, 16, 16);
        putInt16LE(h, 20, 1);           // PCM
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
        b[off]=(byte)v; b[off+1]=(byte)(v>>8); b[off+2]=(byte)(v>>16); b[off+3]=(byte)(v>>24);
    }
    private static void putInt16LE(byte[] b, int off, int v) {
        b[off]=(byte)v; b[off+1]=(byte)(v>>8);
    }
    private static void writeInt32LE(RandomAccessFile raf, int v) throws IOException {
        raf.write(v&0xFF); raf.write((v>>8)&0xFF); raf.write((v>>16)&0xFF); raf.write((v>>24)&0xFF);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static AudioRecord tryCreate(int source, int bufSize) {
        try {
            AudioRecord ar = new AudioRecord(
                    source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize);
            if (ar.getState() == AudioRecord.STATE_INITIALIZED) return ar;
            ar.release();
        } catch (Exception e) {
            Log.d(TAG, "AudioRecord source=" + source + " unavailable: " + e.getMessage());
        }
        return null;
    }

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
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            return new File(dir, "Call_" + stamp + ".wav");
        } catch (Exception e) {
            Log.e(TAG, "buildOutputFile failed", e);
            return null;
        }
    }
}
