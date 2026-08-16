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

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player == null || !prepared) return;
            try {
                if (player.isPlaying()) {
                    int pos = player.getCurrentPosition();
                    float progress = durationMs > 0 ? Math.min(1f, pos / (float) durationMs) : 0f;
                    if (listener != null) listener.onProgress(progress);
                    handler.postDelayed(this, 50L);
                }
            } catch (IllegalStateException ignored) {}
        }
    };

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Prepares (synchronously — file is local and tiny) a player over the
     *  given path. Safe to call once per pause window; call release()
     *  before preparing again over a different/updated file. */
    public boolean prepare(String filePath) {
        release();
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

    /** Resumes/starts playback from wherever seekTo() last left it. */
    public void play() {
        if (player == null || !prepared) return;
        try {
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
     *  waveform and to reset to the start on a fresh pause. */
    public void seekTo(float progress0to1) {
        if (player == null || !prepared || durationMs <= 0) return;
        int pos = Math.round(Math.max(0f, Math.min(1f, progress0to1)) * durationMs);
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
