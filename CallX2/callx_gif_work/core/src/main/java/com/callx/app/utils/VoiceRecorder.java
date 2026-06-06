package com.callx.app.utils;

import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

/**
 * VoiceRecorder — thin wrapper around MediaRecorder for voice messages.
 *
 * Usage:
 *   VoiceRecorder recorder = new VoiceRecorder();
 *   recorder.start(context);   // begin recording
 *   Uri uri = recorder.stop(context);  // finish & get file URI
 *   recorder.cancel();         // discard without saving
 */
public class VoiceRecorder {

    private MediaRecorder mediaRecorder;
    private File          outFile;
    private long          startedAt;

    public VoiceRecorder() {}

    /**
     * Start recording to a temp .m4a file in the app cache.
     * @return true if recording started successfully, false on failure.
     */
    public boolean start(Context ctx) {
        try {
            File dir = new File(ctx.getCacheDir(), "voice");
            if (!dir.exists()) dir.mkdirs();
            outFile = new File(dir, "vm_" + System.currentTimeMillis() + ".m4a");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(ctx);
            } else {
                //noinspection deprecation
                mediaRecorder = new MediaRecorder();
            }

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(64000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setOutputFile(outFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            startedAt = System.currentTimeMillis();
            return true;
        } catch (IOException | RuntimeException e) {
            cleanup();
            return false;
        }
    }

    /**
     * Stop recording and return a content URI for the recorded file.
     * Returns null if nothing was recorded or the file is empty.
     */
    public Uri stop(Context ctx) {
        if (mediaRecorder == null || outFile == null) return null;
        try { mediaRecorder.stop(); }   catch (Exception ignored) {}
        try { mediaRecorder.release(); } catch (Exception ignored) {}
        mediaRecorder = null;

        if (!outFile.exists() || outFile.length() == 0) return null;

        return FileProvider.getUriForFile(
                ctx,
                ctx.getPackageName() + ".fileprovider",
                outFile
        );
    }

    /** Duration in milliseconds since start() was called. */
    public long getDuration() {
        return System.currentTimeMillis() - startedAt;
    }

    /** Cancel and discard the current recording without saving. */
    public void cancel() {
        cleanup();
    }

    private void cleanup() {
        try { if (mediaRecorder != null) mediaRecorder.release(); } catch (Exception ignored) {}
        mediaRecorder = null;
        if (outFile != null && outFile.exists()) outFile.delete();
        outFile = null;
    }
}
