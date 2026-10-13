package com.callx.app.viewer;

import android.media.MediaPlayer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * Plays only a short CLIP of a long song for a status music sticker.
 *
 * WHY: when a track has no previewAudioUrl, the sticker falls back to the full
 * audio_url, and a status used to play the whole song from 0:00 (intro, silence,
 * a 3-4 min stream). Instagram/WhatsApp play a ~30s highlight instead.
 *
 * Rules (see {@link #apply}):
 *  - a short track (a real preview, <= 45s) and no explicit range → untouched,
 *    plays whole + loops as before;
 *  - explicit range (startMs/endMs stored on the sticker from the editor's music
 *    trim) → exactly that range, looped;
 *  - long track with no range → a 30s clip starting ~25% into the song (skips the
 *    usual intro, lands near the body/chorus), looped. The 25% is a heuristic —
 *    the exact moment can't be known without a range from the poster.
 *
 * MediaPlayer can't loop a sub-range, so a 200ms watcher seeks back to the clip
 * start once the end is reached (OnCompletion covers the natural-end case).
 *
 * MIX AUDIO (status music sticker): {@link #setMix} carries the poster's music volume,
 * fade-in/out (applied at the start/end of EVERY loop of the clip) and pitch shift.
 * The caller multiplies its own volume (mute / duck) by {@link #volumeFactor()} and
 * re-applies it whenever the {@link #setVolumeListener volume listener} fires (the
 * watcher ticks every 60ms while a fade is configured, so the ramp is smooth).
 *
 * javac note: nothing here is a lambda in a field initializer touching a blank
 * final field (that was the earlier ReelAudioFocusHelper build error).
 */
public final class StatusMusicClip {

    /** Anything longer than this with no explicit range is treated as a full song. */
    public static final int FULL_SONG_THRESHOLD_MS = 45_000;
    /** Length of the automatic highlight clip. */
    public static final int CLIP_LENGTH_MS = 30_000;
    private static final int TICK_MS = 200;
    /** Stay this far from the natural end so we always loop via seek, never via 0:00. */
    private static final int END_MARGIN_MS = 400;

    /** Faster watcher while a fade is running, so the volume ramp has no audible steps. */
    private static final int FADE_TICK_MS = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private MediaPlayer pitchPlayer;   // player the pitch was applied to (re-applied before start())
    private int startMs;
    private int endMs;
    private Runnable ticker;

    // ── Mix Audio (all optional; defaults = "no mix") ──
    private float mixVol = 1f;         // music volume 0..1
    private int fadeInMs = 0;
    private int fadeOutMs = 0;
    private float pitchSemitones = 0f; // -6..+6
    private Runnable volumeListener;   // fired on every watcher tick while a fade is configured
    private boolean clipActive = false;

    /**
     * Sets the poster's Mix Audio values. Call BEFORE {@link #apply}. Pass (-1, 0, 0, 0) /
     * (1, 0, 0, 0) for a sticker without a mix. A negative volume means "not set" → 1.
     */
    public void setMix(float musicVol, int fadeInMs, int fadeOutMs, float pitchSemitones) {
        this.mixVol = musicVol < 0f ? 1f : Math.min(1f, musicVol);
        this.fadeInMs = Math.max(0, fadeInMs);
        this.fadeOutMs = Math.max(0, fadeOutMs);
        this.pitchSemitones = Math.max(-6f, Math.min(6f, pitchSemitones));
    }

    /** Called (main thread) whenever the volume should be re-applied because a fade moved. */
    public void setVolumeListener(Runnable r) { this.volumeListener = r; }

    private boolean hasFade() { return fadeInMs > 0 || fadeOutMs > 0; }

    /**
     * Factor (0..1) the caller multiplies into its volume: music volume × current fade.
     * Fade-in ramps over the first {@code fadeInMs} of the clip, fade-out over the last
     * {@code fadeOutMs}, on every loop. Without a mix this is 1.
     */
    public float volumeFactor() {
        float f = mixVol;
        MediaPlayer m = mp;
        if (m == null || !clipActive || !hasFade()) return f;
        int pos;
        try { pos = m.getCurrentPosition(); } catch (Exception e) { return f; }
        // A seek is async: right after it the position can still be the old one → clamp into the clip.
        pos = Math.max(startMs, Math.min(endMs, pos));
        int len = endMs - startMs;
        int fin = Math.min(fadeInMs, len / 2);
        int fout = Math.min(fadeOutMs, len / 2);
        if (fin > 0) f *= Math.min(1f, (pos - startMs) / (float) fin);
        if (fout > 0) f *= Math.min(1f, (endMs - pos) / (float) fout);
        return Math.max(0f, Math.min(1f, f));
    }

    /**
     * Call from OnPrepared, BEFORE start(). Seeks to the clip start and arms the loop watcher.
     * @param requestedStartMs sticker's stored start (ms) or 0
     * @param requestedEndMs   sticker's stored end (ms) or 0
     * @return true if a clip was applied; false = play the track normally (setLooping(true) stays valid)
     */
    public boolean apply(MediaPlayer player, int requestedStartMs, int requestedEndMs) {
        stopTicker();
        clipActive = false;
        if (player == null) return false;
        applyPitch(player, true); // before start(); no-op for pitch 0
        int d;
        try { d = player.getDuration(); } catch (Exception e) { return false; }
        if (d <= 0) return false;

        boolean explicit = requestedStartMs > 0 || requestedEndMs > requestedStartMs;
        int s, e;
        if (!explicit && d <= FULL_SONG_THRESHOLD_MS) {
            // Real preview → whole thing. Only take over looping when a fade needs to be
            // re-triggered on every loop (plain setLooping(true) can't do that).
            if (!hasFade() || d < 2_000) return false;
            s = 0;
            e = d - END_MARGIN_MS;
        } else {
            s = requestedStartMs > 0 ? requestedStartMs : (explicit ? 0 : d / 4);
            e = requestedEndMs > s ? requestedEndMs : s + CLIP_LENGTH_MS;

            // Clamp into the real track.
            if (s >= d - 2_000) s = Math.max(0, d - CLIP_LENGTH_MS);
            e = Math.min(e, d - END_MARGIN_MS);
            if (e - s < 1_000) return false; // nonsensical range → just play normally
        }

        this.mp = player;
        this.startMs = s;
        this.endMs = e;
        this.clipActive = true;

        player.setLooping(false); // we loop the clip ourselves
        player.setOnCompletionListener(p -> {
            // Natural end reached (watcher missed it) → back to the clip start, not 0:00.
            try { seek(p, startMs); p.start(); } catch (Exception ignored) {}
        });
        try { seek(player, s); } catch (Exception ignored) {}
        notifyVolume(); // fade-in: start silent instead of at full volume

        final int tick = hasFade() ? FADE_TICK_MS : TICK_MS;
        ticker = new Runnable() {
            @Override public void run() {
                MediaPlayer m = mp;
                if (m == null) return;
                try {
                    if (m.isPlaying() && m.getCurrentPosition() >= endMs) seek(m, startMs);
                } catch (Exception ignored) {}
                if (hasFade()) notifyVolume();
                handler.postDelayed(this, tick);
            }
        };
        handler.postDelayed(ticker, tick);
        return true;
    }

    private void notifyVolume() {
        Runnable r = volumeListener;
        if (r != null) { try { r.run(); } catch (Exception ignored) {} }
    }

    /**
     * Applies the pitch shift (semitones → playback pitch 2^(st/12), speed stays 1.0).
     * API 23+ only; silently ignored below or if the device rejects it.
     * Call again right before start(): some API 23-25 builds drop the params on pause/start.
     * @param keepPaused on some builds setPlaybackParams() itself starts a prepared player;
     *                   true → pause it again if it was not playing before.
     */
    public void applyPitch(MediaPlayer player, boolean keepPaused) {
        if (player == null) return;
        pitchPlayer = player;
        if (pitchSemitones == 0f || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            boolean wasPlaying = player.isPlaying();
            float pitch = (float) Math.pow(2.0, pitchSemitones / 12.0);
            player.setPlaybackParams(new android.media.PlaybackParams().setSpeed(1f).setPitch(pitch));
            if (keepPaused && !wasPlaying && player.isPlaying()) player.pause();
        } catch (Exception ignored) {}
    }

    /** Re-applies the pitch to the current player; call just before MediaPlayer.start(). */
    public void reapplyPitch() {
        if (pitchSemitones != 0f && pitchPlayer != null) applyPitch(pitchPlayer, false);
    }

    /** Stop watching (call whenever the MediaPlayer is released / replaced). */
    public void stop() {
        stopTicker();
        clipActive = false;
        mp = null;
        pitchPlayer = null;
    }

    private void stopTicker() {
        if (ticker != null) { handler.removeCallbacks(ticker); ticker = null; }
    }

    private static void seek(MediaPlayer p, int ms) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        else p.seekTo(ms);
    }
}
