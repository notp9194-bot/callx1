package com.callx.app.conversation.controllers;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;

/**
 * VoicePreviewPlayer — lets the user hear back a voice note they've paused
 * mid-recording but not yet sent (Feature: pause/resume/preview/adjust
 * before send, WhatsApp-style).
 *
 * Plays straight off the same temp .m4a file VoiceRecorder is writing to.
 * Must only be used while the recorder itself is PAUSED — MediaRecorder
 * holds the mic/output file, so this is only ever constructed for the
 * paused-preview window, and released before recording resumes or the
 * clip is sent.
 *
 * Feature: TRIM-AWARE PLAYBACK. {@link #setTrimRange(float, float)}
 * constrains preview playback to the window the user picked with the
 * waveform's drag handles: play() starts from trimStart (jumping there
 * first if the playhead is currently outside the window), and the
 * progress tick stops playback and rewinds to trimStart the instant it
 * reaches trimEnd — so previewing always plays exactly what will be sent,
 * never the cut head/tail. The actual audio file isn't touched here; the
 * physical cut happens once, at send time, via VoiceTrimmer.
 */
public class VoicePreviewPlayer {

    public interface Listener {
        /** progress0to1 in [0,1]; called ~every 50ms while playing. */
        void onProgress(float progress0to1);
        void onPlaybackFinished();
    }

    private MediaPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Listener listener;
    private int durationMs = 0;
    private boolean prepared = false;

    private float trimStartFrac = 0f;
    private float trimEndFrac = 1f;

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player == null || !prepared) return;
            try {
                if (player.isPlaying()) {
                    int pos = player.getCurrentPosition();
                    float progress = durationMs > 0 ? Math.min(1f, pos / (float) durationMs) : 0f;
                    if (progress >= trimEndFrac - 0.001f) {
                        // Hit the trimmed-out tail — stop here instead of
                        // playing into audio that got cut, then rewind the
                        // playhead back to the start of the selection.
                        pause();
                        seekTo(trimStartFrac);
                        if (listener != null) {
                            listener.onProgress(trimStartFrac);
                            listener.onPlaybackFinished();
                        }
                        return;
                    }
                    if (listener != null) listener.onProgress(progress);
                    handler.postDelayed(this, 50L);
                }
            } catch (IllegalStateException ignored) {}
        }
    };

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Constrains playback/seek to a sub-range of the clip — the
     *  fractions picked via the preview waveform's trim handles. */
    public void setTrimRange(float startFrac, float endFrac) {
        trimStartFrac = Math.max(0f, Math.min(1f, startFrac));
        trimEndFrac = Math.max(trimStartFrac, Math.min(1f, endFrac));
    }

    /** Prepares (synchronously — file is local and tiny) a player over the
     *  given path. Safe to call once per pause window; call release()
     *  before preparing again over a different/updated file. Resets the
     *  trim window back to the full clip — callers re-apply a saved
     *  selection via setTrimRange() afterward if needed. */
    public boolean prepare(String filePath) {
        release();
        trimStartFrac = 0f;
        trimEndFrac = 1f;
        try {
            player = new MediaPlayer();
            player.setDataSource(filePath);
            player.prepare();
            durationMs = player.getDuration();
            prepared = true;
            player.setOnCompletionListener(mp -> {
                handler.removeCallbacks(progressTick);
                if (listener != null) listener.onPlaybackFinished();
            });
            return true;
        } catch (IOException | IllegalStateException e) {
            prepared = false;
            return false;
        }
    }

    public boolean isPrepared() {
        return prepared;
    }

    public int getDurationMs() {
        return durationMs;
    }

    /** Resumes/starts playback from wherever seekTo() last left it — first
     *  snapping into the trim window if the playhead is currently outside
     *  it (e.g. a fresh prepare(), or the handles moved past it). */
    public void play() {
        if (player == null || !prepared) return;
        try {
            float progressNow = durationMs > 0 ? player.getCurrentPosition() / (float) durationMs : 0f;
            if (progressNow < trimStartFrac - 0.001f || progressNow >= trimEndFrac - 0.001f) {
                seekTo(trimStartFrac);
            }
            player.start();
            handler.post(progressTick);
        } catch (IllegalStateException ignored) {}
    }

    public void pause() {
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) player.pause();
        } catch (IllegalStateException ignored) {}
        handler.removeCallbacks(progressTick);
    }

    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** progress0to1 in [0,1] — used both for user drag-to-seek on the
     *  waveform and to reset to the start on a fresh pause. Clamped to the
     *  current trim window so seeking (including the auto-rewind on
     *  reaching trimEnd) never lands outside the selection. */
    public void seekTo(float progress0to1) {
        if (player == null || !prepared || durationMs <= 0) return;
        float clamped = Math.max(trimStartFrac, Math.min(trimEndFrac,
                Math.max(0f, Math.min(1f, progress0to1))));
        int pos = Math.round(clamped * durationMs);
        try { player.seekTo(pos); } catch (IllegalStateException ignored) {}
    }

    /** Stops playback and releases the underlying MediaPlayer + file
     *  handle — MUST be called before VoiceRecorder.resume()/stop() reuses
     *  the same file, and on delete/cancel. Safe to call repeatedly. */
    public void release() {
        handler.removeCallbacks(progressTick);
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
        }
        player = null;
        prepared = false;
        durationMs = 0;
    }
}
