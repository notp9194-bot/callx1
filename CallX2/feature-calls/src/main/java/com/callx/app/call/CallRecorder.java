package com.callx.app.call;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import org.webrtc.AudioTrackSink;

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dual-side voice call recorder.
 *
 * Strategy:
 *  - Local mic  → AudioRecord (16-bit PCM, 16kHz mono)
 *  - Remote     → WebRTC AudioTrackSink (same format, injected by CallActivity)
 *  Both streams are queued, mixed sample-by-sample, and written to a WAV file.
 *
 * Usage:
 *   recorder = new CallRecorder(context);
 *   remoteTrack.addSink(recorder.getRemoteSink());   // before call connects
 *   recorder.start();                                 // when call connects
 *   recorder.stop();                                  // on endCall
 *   String path = recorder.getOutputPath();
 */
public class CallRecorder {

    private static final String TAG = "CallRecorder";

    // Audio config — must match on both sides
    private static final int SAMPLE_RATE   = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT  = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit

    private final Context context;
    private final AtomicBoolean recording = new AtomicBoolean(false);

    private AudioRecord audioRecord;
    private int bufferSizeBytes;

    // Queues for local + remote PCM chunks (short arrays)
    private final BlockingQueue<short[]> localQueue  = new ArrayBlockingQueue<>(512);
    private final BlockingQueue<short[]> remoteQueue = new ArrayBlockingQueue<>(512);

    private ExecutorService captureExecutor;
    private ExecutorService mixerExecutor;

    private File outputFile;
    private FileOutputStream fos;
    private long totalSamplesWritten = 0;

    // ── Remote sink (WebRTC → queue) ──────────────────────────────────────
    private final AudioTrackSink remoteSink = new AudioTrackSink() {
        @Override
        public void onData(ByteBuffer audioData, int bitsPerSample,
                           int sampleRate, int numberOfChannels,
                           int numberOfFrames, int absoluteCaptureTimestampMs) {
            if (!recording.get()) return;
            // Convert ByteBuffer → short[]
            int numSamples = numberOfFrames * numberOfChannels;
            short[] samples = new short[numSamples];
            audioData.rewind();
            ByteBuffer copy = ByteBuffer.allocate(audioData.remaining())
                    .order(ByteOrder.LITTLE_ENDIAN);
            copy.put(audioData);
            copy.rewind();
            for (int i = 0; i < numSamples && copy.remaining() >= 2; i++) {
                samples[i] = copy.getShort();
            }
            // Down-mix to mono if stereo
            if (numberOfChannels == 2) {
                short[] mono = new short[numberOfFrames];
                for (int i = 0; i < numberOfFrames; i++) {
                    mono[i] = (short) ((samples[i * 2] + samples[i * 2 + 1]) / 2);
                }
                samples = mono;
            }
            if (!remoteQueue.offer(samples)) {
                remoteQueue.poll(); // drop oldest if full
                remoteQueue.offer(samples);
            }
        }
    };

    public CallRecorder(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Return this to attach to remote AudioTrack BEFORE calling start(). */
    public AudioTrackSink getRemoteSink() {
        return remoteSink;
    }

    /**
     * Start recording. Call this when the call is confirmed connected.
     * @return true if recording started successfully.
     */
    public boolean start() {
        if (recording.get()) return false;

        bufferSizeBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSizeBytes <= 0) bufferSizeBytes = 3200; // 100ms fallback

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                    bufferSizeBytes * 4);
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord create failed", e);
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        outputFile = buildOutputFile();
        if (outputFile == null) {
            Log.e(TAG, "Could not create output file");
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        try {
            fos = new FileOutputStream(outputFile);
            writeWavHeader(fos, 0); // placeholder — updated on stop
        } catch (IOException e) {
            Log.e(TAG, "Cannot open output file", e);
            audioRecord.release();
            audioRecord = null;
            return false;
        }

        recording.set(true);
        localQueue.clear();
        remoteQueue.clear();
        totalSamplesWritten = 0;

        captureExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CallRecorder-Capture");
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });
        mixerExecutor = Executors.newSingleThreadExecutor(r ->
                new Thread(r, "CallRecorder-Mixer"));

        captureExecutor.execute(this::captureLoop);
        mixerExecutor.execute(this::mixerLoop);

        Log.i(TAG, "Recording started → " + outputFile.getAbsolutePath());
        return true;
    }

    /** Stop recording and finalize the WAV file. */
    public void stop() {
        if (!recording.getAndSet(false)) return;

        // Stop capture
        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
        } catch (Exception ignored) {}

        // Shutdown executors — give them 3s to flush queues
        try {
            captureExecutor.shutdown();
            captureExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        try {
            mixerExecutor.shutdown();
            mixerExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        // Drain remaining queued remote audio
        drainRemoteQueue();

        // Update WAV header with actual data size
        finalizeWavHeader();

        try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        fos = null;

        Log.i(TAG, "Recording stopped. Samples written: " + totalSamplesWritten
                + "  File: " + (outputFile != null ? outputFile.getAbsolutePath() : "null"));
    }

    /** Path to the saved WAV file (available after stop()). */
    public String getOutputPath() {
        return outputFile != null ? outputFile.getAbsolutePath() : null;
    }

    // ── Local mic capture loop ────────────────────────────────────────────

    private void captureLoop() {
        if (audioRecord == null) return;
        audioRecord.startRecording();
        int samplesPerBuf = bufferSizeBytes / BYTES_PER_SAMPLE;
        short[] buf = new short[samplesPerBuf];

        while (recording.get()) {
            int read = audioRecord.read(buf, 0, buf.length);
            if (read > 0) {
                short[] chunk = new short[read];
                System.arraycopy(buf, 0, chunk, 0, read);
                if (!localQueue.offer(chunk)) {
                    localQueue.poll();
                    localQueue.offer(chunk);
                }
            }
        }
    }

    // ── Mixer loop: local + remote → WAV ─────────────────────────────────

    private void mixerLoop() {
        while (recording.get() || !localQueue.isEmpty()) {
            short[] local = null;
            try { local = localQueue.poll(80, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ignored) {}

            if (local == null) continue;

            // Get matching remote chunk (non-blocking)
            short[] remote = remoteQueue.poll();

            short[] mixed = new short[local.length];
            for (int i = 0; i < local.length; i++) {
                int s = local[i];
                if (remote != null && i < remote.length) s += remote[i];
                // Clamp to int16 range
                if (s > Short.MAX_VALUE)  s = Short.MAX_VALUE;
                if (s < Short.MIN_VALUE)  s = Short.MIN_VALUE;
                mixed[i] = (short) s;
            }

            writeSamples(mixed);
        }
    }

    private void drainRemoteQueue() {
        short[] chunk;
        while ((chunk = remoteQueue.poll()) != null) {
            writeSamples(chunk);
        }
    }

    private synchronized void writeSamples(short[] samples) {
        if (fos == null) return;
        try {
            byte[] bytes = new byte[samples.length * 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().put(samples);
            fos.write(bytes);
            totalSamplesWritten += samples.length;
        } catch (IOException e) {
            Log.e(TAG, "Write error", e);
        }
    }

    // ── WAV helpers ───────────────────────────────────────────────────────

    private static void writeWavHeader(FileOutputStream out, long numSamples)
            throws IOException {
        int channels    = 1;
        int bitsPerSample = 16;
        int byteRate    = SAMPLE_RATE * channels * bitsPerSample / 8;
        int blockAlign  = channels * bitsPerSample / 8;
        long dataBytes  = numSamples * blockAlign;
        long chunkSize  = 36 + dataBytes;

        // RIFF header (44 bytes total)
        byte[] h = new byte[44];
        // "RIFF"
        h[0]='R'; h[1]='I'; h[2]='F'; h[3]='F';
        putInt32LE(h,  4, (int) chunkSize);
        // "WAVE"
        h[8]='W'; h[9]='A'; h[10]='V'; h[11]='E';
        // "fmt "
        h[12]='f'; h[13]='m'; h[14]='t'; h[15]=' ';
        putInt32LE(h, 16, 16);           // subchunk1 size
        putInt16LE(h, 20, 1);            // PCM
        putInt16LE(h, 22, channels);
        putInt32LE(h, 24, SAMPLE_RATE);
        putInt32LE(h, 28, byteRate);
        putInt16LE(h, 32, blockAlign);
        putInt16LE(h, 34, bitsPerSample);
        // "data"
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
        b[off+1] = (byte) (v >> 8);
        b[off+2] = (byte) (v >> 16);
        b[off+3] = (byte) (v >> 24);
    }

    private static void putInt16LE(byte[] b, int off, int v) {
        b[off]   = (byte)  v;
        b[off+1] = (byte) (v >> 8);
    }

    private static void writeInt32LE(RandomAccessFile raf, int v) throws IOException {
        raf.write(v & 0xFF);
        raf.write((v >> 8) & 0xFF);
        raf.write((v >> 16) & 0xFF);
        raf.write((v >> 24) & 0xFF);
    }

    // ── Output file path ──────────────────────────────────────────────────

    private File buildOutputFile() {
        try {
            File dir;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ — app-specific directory, no permission needed
                dir = new File(context.getExternalFilesDir(
                        Environment.DIRECTORY_RECORDINGS), "CallRecordings");
            } else {
                dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MUSIC), "CallX2/Recordings");
            }
            if (!dir.exists() && !dir.mkdirs()) {
                // Fallback to internal storage
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
