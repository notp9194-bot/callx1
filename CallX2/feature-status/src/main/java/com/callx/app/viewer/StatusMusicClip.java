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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer mp;
    private int startMs;
    private int endMs;
    private Runnable ticker;

    /**
     * Call from OnPrepared, BEFORE start(). Seeks to the clip start and arms the loop watcher.
     * @param requestedStartMs sticker's stored start (ms) or 0
     * @param requestedEndMs   sticker's stored end (ms) or 0
     * @return true if a clip was applied; false = play the track normally (setLooping(true) stays valid)
     */
    public boolean apply(MediaPlayer player, int requestedStartMs, int requestedEndMs) {
        stop();
        if (player == null) return false;
        int d;
        try { d = player.getDuration(); } catch (Exception e) { return false; }
        if (d <= 0) return false;

        boolean explicit = requestedStartMs > 0 || requestedEndMs > requestedStartMs;
        if (!explicit && d <= FULL_SONG_THRESHOLD_MS) return false; // real preview → whole thing

        int s = requestedStartMs > 0 ? requestedStartMs : (explicit ? 0 : d / 4);
        int e = requestedEndMs > s ? requestedEndMs : s + CLIP_LENGTH_MS;

        // Clamp into the real track.
        if (s >= d - 2_000) s = Math.max(0, d - CLIP_LENGTH_MS);
        e = Math.min(e, d - END_MARGIN_MS);
        if (e - s < 1_000) return false; // nonsensical range → just play normally

        this.mp = player;
        this.startMs = s;
        this.endMs = e;

        player.setLooping(false); // we loop the clip ourselves
        player.setOnCompletionListener(p -> {
            // Natural end reached (watcher missed it) → back to the clip start, not 0:00.
            try { seek(p, startMs); p.start(); } catch (Exception ignored) {}
        });
        try { seek(player, s); } catch (Exception ignored) {}

        ticker = new Runnable() {
            @Override public void run() {
                MediaPlayer m = mp;
                if (m == null) return;
                try {
                    if (m.isPlaying() && m.getCurrentPosition() >= endMs) seek(m, startMs);
                } catch (Exception ignored) {}
                handler.postDelayed(this, TICK_MS);
            }
        };
        handler.postDelayed(ticker, TICK_MS);
        return true;
    }

    /** Stop watching (call whenever the MediaPlayer is released / replaced). */
    public void stop() {
        if (ticker != null) { handler.removeCallbacks(ticker); ticker = null; }
        mp = null;
    }

    private static void seek(MediaPlayer p, int ms) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) p.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        else p.seekTo(ms);
    }
}
