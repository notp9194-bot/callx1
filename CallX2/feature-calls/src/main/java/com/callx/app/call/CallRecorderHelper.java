package com.callx.app.call;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * CallRecorderHelper — VoIP call recording using MediaRecorder.
 *
 * Audio source: VOICE_COMMUNICATION — captures microphone + VoIP speaker on most devices.
 * Format: AAC inside .m4a — smallest size, best quality for voice.
 *
 * Storage: app-private external storage (getExternalFilesDir).
 * - Android 10+: no permission needed.
 * - Android 9 and below: requires WRITE_EXTERNAL_STORAGE (check before calling start).
 *
 * Usage:
 *   helper = new CallRecorderHelper(context, "Alice", "2025-06-08_14-30");
 *   boolean ok = helper.start();          // returns false if mic unavailable
 *   String path = helper.stop();          // returns saved file path, or null if failed
 *
 * Saved at: <externalFilesDir>/CallRecordings/CallX_<partner>_<date>.m4a
 */
public class CallRecorderHelper {

    private static final String TAG   = "CallRecorder";
    private static final String DIR   = "CallRecordings";
    private static final String PREFIX = "CallX_";

    private final Context context;
    private final String  partnerName;
    private final String  callId;

    private MediaRecorder recorder;
    private String        outputPath;
    private boolean       recording = false;

    public CallRecorderHelper(Context context, String partnerName, String callId) {
        this.context     = context.getApplicationContext();
        this.partnerName = sanitize(partnerName);
        this.callId      = callId != null ? callId : "call";
    }

    /**
     * Start recording. Returns true if started successfully.
     * Call this only after call is connected (audio stream active).
     */
    public boolean start() {
        if (recording) return true;
        try {
            File dir = new File(context.getExternalFilesDir(null), DIR);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

            String ts   = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
            String name = PREFIX + partnerName + "_" + ts + ".m4a";
            outputPath  = new File(dir, name).getAbsolutePath();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                recorder = new MediaRecorder(context);
            } else {
                //noinspection deprecation
                recorder = new MediaRecorder();
            }

            // VOICE_COMMUNICATION = VoIP source: mic + speaker mixed on supported devices
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(64_000);   // 64 kbps — good quality for voice
            recorder.setAudioSamplingRate(16_000);       // 16 kHz — standard for speech
            recorder.setOutputFile(outputPath);
            recorder.prepare();
            recorder.start();
            recording = true;
            Log.i(TAG, "Recording started: " + outputPath);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            release();
            return false;
        }
    }

    /**
     * Stop recording. Returns the saved file path, or null if recording failed.
     */
    public String stop() {
        if (!recording || recorder == null) return null;
        try {
            recorder.stop();
            Log.i(TAG, "Recording saved: " + outputPath);
        } catch (Exception e) {
            Log.w(TAG, "Recording stop error (empty file?): " + e.getMessage());
            outputPath = null;   // discard empty/corrupt file
        } finally {
            release();
        }
        return outputPath;
    }

    public boolean isRecording() { return recording; }
    public String  getOutputPath() { return outputPath; }

    private void release() {
        recording = false;
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    /** Strip characters unsafe for filenames */
    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_").substring(0, Math.min(name.length(), 30));
    }
}
