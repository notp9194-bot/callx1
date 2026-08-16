package com.callx.app.utils;

import android.content.Context;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * VoiceRecorder — thin wrapper around MediaRecorder for voice messages.
 *
 * Usage:
 *   VoiceRecorder recorder = new VoiceRecorder();
 *   recorder.start(context);   // begin recording
 *   Uri uri = recorder.stop(context);  // finish & get file URI
 *   recorder.cancel();         // discard without saving
 *
 * ── Pause/resume implementation ─────────────────────────────────────────
 * Pause/resume is implemented as SEGMENTS rather than MediaRecorder's own
 * pause()/resume() calls. Reason: MediaRecorder#pause() leaves the output
 * file's MP4 header (the "moov" box) unwritten — that box is only finalized
 * on stop() — so a file that's merely paused is NOT a valid, playable
 * container yet. That's exactly why the paused-preview Play button used to
 * do nothing: VoicePreviewPlayer.prepare()/MediaPlayer#prepare() on that
 * half-written file failed silently.
 *
 * Instead, pause() here fully stops+releases the recorder (which DOES
 * finalize the current segment into a normal, playable .m4a) and resume()
 * starts a NEW MediaRecorder into a NEW segment file. Every finalized
 * segment is kept in {@link #segments}. getOutputFilePathForPreview()
 * returns the single segment directly (fast path — no merge) or, if the
 * user paused more than once, merges every finalized segment into one temp
 * file via {@link VoiceSegmentMerger} so Play always has a complete,
 * playable clip of everything recorded so far. stop() does the same merge
 * (if needed) to produce the final sent file.
 */
public class VoiceRecorder {

    private MediaRecorder mediaRecorder;
    private File          currentSegmentFile;
    private File          dir;
    private long          startedAt;
    /** Application context, kept only for resume()'s Context-aware
     *  MediaRecorder(Context) constructor on API 31+ — set in start(). */
    private Context appContext;

    /** ms accumulated across all completed record segments, i.e. everything
     *  BEFORE the current (possibly still running) segment. Rolled into
     *  getDuration() so the timer/waveform stay correct across pause/resume
     *  instead of resetting or double-counting the paused gap. */
    private long elapsedBeforeSegment = 0L;
    private boolean paused = false;

    /** Every segment finalized so far (i.e. every completed pause), in
     *  recording order. Does NOT include the in-progress segment. */
    private final List<File> segments = new ArrayList<>();

    /** Cached merge of all finalized segments, so repeated preview-Play
     *  taps don't re-run the muxer every time. Invalidated whenever a new
     *  segment is finalized (pause()). */
    private File cachedPreviewFile;

    public VoiceRecorder() {}

    /** Pause/resume needs API 24 for MediaRecorder itself to be safely
     *  stop()-able and restart()-able mid-gesture on some OEM builds —
     *  callers below that should hide the pause control and keep the old
     *  hold-to-record-then-send/delete flow. */
    public static boolean isPauseResumeSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Pause the current recording segment. Finalizes it (stop+release) so
     *  it becomes an immediately-playable file for the preview player, then
     *  waits for resume()/stop() before continuing. No-op if not currently
     *  recording or already paused, or on APIs below 24. */
    public boolean pause() {
        if (mediaRecorder == null || paused || !isPauseResumeSupported()) return false;
        try {
            mediaRecorder.stop();
        } catch (Exception e) {
            // Segment too short / recorder in a bad state — discard it
            // rather than leaving a broken file in the segments list.
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
            if (currentSegmentFile != null && currentSegmentFile.exists()) currentSegmentFile.delete();
            currentSegmentFile = null;
            return false;
        }
        try { mediaRecorder.release(); } catch (Exception ignored) {}
        mediaRecorder = null;

        if (currentSegmentFile != null && currentSegmentFile.exists() && currentSegmentFile.length() > 0) {
            segments.add(currentSegmentFile);
        }
        currentSegmentFile = null;
        elapsedBeforeSegment += System.currentTimeMillis() - startedAt;
        paused = true;
        invalidatePreviewCache();
        return true;
    }

    /** Resume recording into a brand-new segment file. No-op if not paused. */
    public boolean resume() {
        if (!paused || dir == null || !isPauseResumeSupported()) return false;
        try {
            currentSegmentFile = new File(dir, "vm_" + System.currentTimeMillis() + ".m4a");
            MediaRecorder mr = createRecorder();
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setAudioEncodingBitRate(64000);
            mr.setAudioSamplingRate(44100);
            mr.setOutputFile(currentSegmentFile.getAbsolutePath());
            mr.prepare();
            mr.start();
            mediaRecorder = mr;
            startedAt = System.currentTimeMillis();
            paused = false;
            return true;
        } catch (IOException | RuntimeException e) {
            mediaRecorder = null;
            currentSegmentFile = null;
            return false;
        }
    }

    /**
     * Path to a file that can be handed straight to a MediaPlayer to
     * preview everything recorded so far, while still paused (not sent).
     * Fast path when there's only one finalized segment; merges all
     * finalized segments together (cached) otherwise. Null if nothing has
     * been finalized yet (e.g. paused before the minimum segment length).
     */
    public String getOutputFilePathForPreview() {
        if (segments.isEmpty()) return null;
        if (segments.size() == 1) return segments.get(0).getAbsolutePath();
        if (cachedPreviewFile != null && cachedPreviewFile.exists()) {
            return cachedPreviewFile.getAbsolutePath();
        }
        File out = new File(dir, "vm_preview_" + System.currentTimeMillis() + ".m4a");
        File merged = VoiceSegmentMerger.merge(segments, out);
        cachedPreviewFile = merged;
        return merged != null ? merged.getAbsolutePath() : null;
    }

    private void invalidatePreviewCache() {
        if (cachedPreviewFile != null && cachedPreviewFile.exists()) cachedPreviewFile.delete();
        cachedPreviewFile = null;
    }

    /**
     * Start recording to a temp .m4a file in the app cache.
     * @return true if recording started successfully, false on failure.
     */
    public boolean start(Context ctx) {
        try {
            appContext = ctx.getApplicationContext();
            dir = new File(ctx.getCacheDir(), "voice");
            if (!dir.exists()) dir.mkdirs();
            currentSegmentFile = new File(dir, "vm_" + System.currentTimeMillis() + ".m4a");
            segments.clear();
            cachedPreviewFile = null;

            mediaRecorder = createRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(64000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setOutputFile(currentSegmentFile.getAbsolutePath());
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

    private MediaRecorder createRecorder() {
        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception ignored) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && appContext != null) {
            return new MediaRecorder(appContext);
        }
        //noinspection deprecation
        return new MediaRecorder();
    }

    /**
     * Stop recording and return a content URI for the recorded file — the
     * finalized current segment plus any earlier paused segments, merged
     * into one file if there was more than one. Returns null if nothing
     * was recorded or the file is empty.
     */
    public Uri stop(Context ctx) {
        File finalFile = stopToFile(ctx);
        if (finalFile == null) return null;
        return FileProvider.getUriForFile(
                ctx,
                ctx.getPackageName() + ".fileprovider",
                finalFile
        );
    }

    /**
     * Same as {@link #stop(Context)} but returns the plain {@link File}
     * instead of wrapping it in a content Uri — needed by callers that
     * want to post-process the file (e.g. {@link VoiceTrimmer} cutting it
     * down to the user's selected trim window) before it gets uploaded.
     * Returns null if nothing was recorded or the file is empty.
     */
    public File stopToFile(Context ctx) {
        // Finalize whatever's still actively recording (if not already
        // paused) so it becomes part of the segment list.
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
            if (currentSegmentFile != null && currentSegmentFile.exists() && currentSegmentFile.length() > 0) {
                segments.add(currentSegmentFile);
            }
            currentSegmentFile = null;
        }
        paused = false;

        if (segments.isEmpty()) return null;

        File finalFile;
        if (segments.size() == 1) {
            finalFile = segments.get(0);
        } else {
            File out = new File(dir, "vm_final_" + System.currentTimeMillis() + ".m4a");
            finalFile = VoiceSegmentMerger.merge(segments, out);
            // Individual segments are no longer needed once merged.
            for (File seg : segments) {
                if (seg != null && seg.exists()) seg.delete();
            }
        }
        invalidatePreviewCache();
        segments.clear();

        if (finalFile == null || !finalFile.exists() || finalFile.length() == 0) return null;
        return finalFile;
    }

    /** Directory recordings are written into — needed by callers (e.g.
     *  ChatMediaController) that create a sibling temp file for
     *  VoiceTrimmer's output. */
    public File getWorkingDir() {
        return dir;
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

    /** Cancel and discard the current recording (including any earlier
     *  paused segments) without saving. */
    public void cancel() {
        cleanup();
    }

    private void cleanup() {
        try { if (mediaRecorder != null) mediaRecorder.release(); } catch (Exception ignored) {}
        mediaRecorder = null;
        if (currentSegmentFile != null && currentSegmentFile.exists()) currentSegmentFile.delete();
        currentSegmentFile = null;
        for (File seg : segments) {
            if (seg != null && seg.exists()) seg.delete();
        }
        segments.clear();
        invalidatePreviewCache();
        paused = false;
        elapsedBeforeSegment = 0L;
    }
}
