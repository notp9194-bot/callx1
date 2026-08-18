package com.callx.app.player;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;

/**
 * ReelLoopSeekHelper — frame-perfect seek reset for looping reels
 * (PERF advance #7 — "frame-perfect seek reset").
 *
 * The problem: reels play with {@code Player.REPEAT_MODE_ONE}. When
 * ExoPlayer's internal position reaches the true end of the stream, it
 * fires its own automatic "restart from 0" transition — but that happens
 * AFTER the player has already reported STATE_ENDED, which involves the
 * renderer briefly going idle before the internal seek-to-0 command is
 * issued and the decoder resumes. On several mid-range decoders that
 * idle→reseek round trip is visible as a one-frame freeze/flash right at
 * the loop point, worse for HLS reels where the "end" is also a segment
 * boundary.
 *
 * The fix: never let the player actually reach STATE_ENDED. A lightweight
 * poll (every {@link #POLL_INTERVAL_MS}) watches the current position
 * against the duration; once we're within {@link #PREEMPT_MS} of the true
 * end, we issue our OWN {@code seekTo(0)} — with
 * {@link SeekParameters#EXACT} so it lands on frame 0 precisely instead of
 * the nearest preceding sync sample. Because this manual seek always fires
 * before the natural end-of-stream, ExoPlayer's own STATE_ENDED→
 * REPEAT_MODE_ONE auto-restart path never actually triggers during normal
 * playback — REPEAT_MODE_ONE stays set purely as a safety net for the rare
 * case a poll cycle gets delayed (app briefly backgrounded, main thread
 * busy) and the player reaches the end on its own.
 *
 * Lifecycle: one instance per live ExoPlayer. Since reels use a pooled
 * ExoPlayer ({@link ExoPlayerPool}) that gets handed to a DIFFERENT reel
 * later, {@link #detach()} MUST be called before that player is returned
 * to the pool or released — otherwise this helper keeps polling and
 * seeking a player that now belongs to someone else. ReelPlayerController
 * detaches in every one of its player-teardown paths (releasePlayer(),
 * codec-fallback rebuild, quality-switch rebuild).
 */
@OptIn(markerClass = UnstableApi.class)
public final class ReelLoopSeekHelper {

    private static final String TAG = "ReelLoopSeekHelper";

    private static final long POLL_INTERVAL_MS = 150L;
    /** Issue the manual loop-seek this many ms before the true end. */
    private static final long PREEMPT_MS = 180L;

    private final ExoPlayer player;
    private final Handler   handler = new Handler(Looper.getMainLooper());
    private final Runnable  pollRunnable = this::poll;
    private boolean         attached = false;

    public ReelLoopSeekHelper(ExoPlayer player) {
        this.player = player;
    }

    /** Starts the pre-emptive loop-seek polling for this player. */
    public void attach() {
        if (attached) return;
        attached = true;
        try {
            // EXACT keeps every seek this player does frame-accurate — not
            // just our loop-point seeks, but also resumePos on a quality
            // switch and any user scrub — instead of snapping to the
            // nearest preceding sync sample.
            player.setSeekParameters(SeekParameters.EXACT);
        } catch (Exception e) {
            Log.w(TAG, "attach: setSeekParameters failed: " + e.getMessage());
        }
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    /** Stops polling. Safe to call more than once. MUST be called before
     *  this player is returned to the pool or released. */
    public void detach() {
        attached = false;
        handler.removeCallbacks(pollRunnable);
    }

    private void poll() {
        if (!attached) return;
        try {
            if (player.getPlaybackState() == Player.STATE_READY && player.isPlaying()) {
                long duration = player.getDuration();
                long position = player.getCurrentPosition();
                if (duration != C.TIME_UNSET && duration > 0) {
                    long remaining = duration - position;
                    if (isNearLoopPoint(remaining)) {
                        player.seekTo(0);
                    }
                }
            }
        } catch (Exception e) {
            // Player mid-teardown (quality switch / release race) — next
            // poll cycle (or detach()) recovers; nothing to do here.
            Log.w(TAG, "poll: skipped (" + e.getMessage() + ")");
        }
        if (attached) handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private boolean isNearLoopPoint(long remainingMs) {
        return remainingMs > 0 && remainingMs <= PREEMPT_MS;
    }
}
