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

    /** ms accumulated across all completed record segments, i.e. everything
     *  BEFORE the current (possibly still running) segment. Rolled into
     *  getDuration() so the timer/waveform stay correct across pause/resume
     *  instead of resetting or double-counting the paused gap. */
    private long elapsedBeforeSegment = 0L;
    private boolean paused = false;

    public VoiceRecorder() {}

    /** Pause/resume (MediaRecorder#pause/#resume) needs API 24 — below that,
     *  callers should hide the pause control entirely and keep the old
     *  hold-to-record-then-send/delete flow. */
    public static boolean isPauseResumeSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Pause the current recording segment in place (same output file —
     *  resume() continues writing into it, no merge step needed). No-op if
     *  not currently recording or already paused, or on APIs below 24. */
    public boolean pause() {
        if (mediaRecorder == null || paused || !isPauseResumeSupported()) return false;
        try {
            mediaRecorder.pause();
            elapsedBeforeSegment += System.currentTimeMillis() - startedAt;
            paused = true;
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Resume a paused recording. No-op if not paused. */
    public boolean resume() {
        if (mediaRecorder == null || !paused || !isPauseResumeSupported()) return false;
        try {
            mediaRecorder.resume();
            startedAt = System.currentTimeMillis();
            paused = false;
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** Absolute path of the in-progress/paused output file — used to build
     *  a MediaPlayer preview while the recording is paused. Null before
     *  start() or after cancel(). */
    public String getOutputFilePath() {
        return outFile != null ? outFile.getAbsolutePath() : null;
    }

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
            elapsedBeforeSegment = 0L;
            paused = false;
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
        // stop() is valid from PAUSED too (API 24+, which is the only API
        // level pause() could have gotten us into that state on) — no need
        // to resume first.
        try { mediaRecorder.stop(); }   catch (Exception ignored) {}
        try { mediaRecorder.release(); } catch (Exception ignored) {}
        mediaRecorder = null;
        paused = false;

        if (!outFile.exists() || outFile.length() == 0) return null;

        return FileProvider.getUriForFile(
                ctx,
                ctx.getPackageName() + ".fileprovider",
                outFile
        );
    }

    /** Total recorded duration in ms, correct across any number of
     *  pause()/resume() cycles — does NOT include time spent paused. */
    public long getDuration() {
        long current = paused ? 0L : (System.currentTimeMillis() - startedAt);
        return elapsedBeforeSegment + current;
    }

    /**
     * Current peak amplitude (0..32767) since the last call, straight from
     * MediaRecorder — used to drive the live waveform bars while recording.
     * Safe to call at any time; returns 0 if nothing is recording or the
     * platform call fails (some OEM MediaRecorder implementations throw
     * IllegalStateException in edge cases around start/stop).
     */
    public int getMaxAmplitudeSafe() {
        if (mediaRecorder == null) return 0;
        try {
            return mediaRecorder.getMaxAmplitude();
        } catch (IllegalStateException e) {
            return 0;
        }
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
        paused = false;
        elapsedBeforeSegment = 0L;
    }
}
