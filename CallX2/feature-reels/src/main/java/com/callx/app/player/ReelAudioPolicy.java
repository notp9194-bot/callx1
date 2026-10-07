package com.callx.app.player;

import android.content.Context;
import android.media.AudioManager;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;

/**
 * ReelAudioPolicy — one place for the reel player's audio-focus and
 * "someone else's audio is playing" decisions.
 *
 * <h3>Audio focus</h3>
 * Pooled players are built with handleAudioFocus=false (see
 * {@code AdaptiveStreamingManager.buildBarePlayer}). ExoPlayer requests
 * focus whenever playWhenReady=true, even at volume 0, so a muted reel
 * with focus enabled would pause the user's music. Instead the reel
 * controller flips focus on only while the reel is actually audible
 * (volume > 0) via {@link #setFocusEnabled}. With focus enabled ExoPlayer
 * itself pauses on a call / another app's playback, ducks on a transient
 * duck request, and resumes after a transient loss.
 *
 * <h3>Other app's audio</h3>
 * {@link #isOtherAudioActive} is true only when music is active and it is
 * not reels' own audible playback (tracked via {@link #setOwnAudible}),
 * because AudioManager.isMusicActive() cannot tell the two apart. The
 * controller uses it to start a reel muted (Instagram-style) instead of
 * stealing focus from the user's music.
 *
 * Main-thread only.
 */
public final class ReelAudioPolicy {

    private ReelAudioPolicy() {}

    public static final AudioAttributes ATTRS = new AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build();

    /** Number of reel controllers currently producing audible sound. */
    private static int ownAudibleCount = 0;

    /** Enables/disables ExoPlayer's own audio-focus handling on this player. */
    public static void setFocusEnabled(ExoPlayer player, boolean enabled) {
        if (player == null) return;
        try {
            player.setAudioAttributes(ATTRS, enabled);
        } catch (Exception ignored) {}
    }

    /** Called by each controller when its audible-playing state flips. */
    public static void setOwnAudible(boolean audible) {
        ownAudibleCount = Math.max(0, ownAudibleCount + (audible ? 1 : -1));
    }

    /**
     * True when another app (music, podcast, video) is producing sound and
     * reels itself is not the one doing it.
     */
    public static boolean isOtherAudioActive(Context ctx) {
        if (ctx == null || ownAudibleCount > 0) return false;
        try {
            AudioManager am = (AudioManager) ctx.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Exception e) {
            return false;
        }
    }
}
