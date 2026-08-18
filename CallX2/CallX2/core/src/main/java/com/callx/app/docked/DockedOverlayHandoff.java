package com.callx.app.docked;

import android.app.Activity;

import androidx.annotation.NonNull;

/**
 * Minimal cross-module contract for the chat-docked mini reel player.
 *
 * feature-reels' ReelChatDockedPlayer implements this so that screens living
 * in modules that don't (and shouldn't) depend on feature-reels — e.g.
 * ChatActivity / ChatListAdapter in feature-chat — can hand the floating
 * player off before navigating away, and re-attach it into their own
 * window, without ever referencing ExoPlayer/media3 types directly.
 *
 * See {@link DockedOverlayRegistry} for how instances are published.
 */
public interface DockedOverlayHandoff {

    /** True while a reel is docked AND its overlay is attached/visible somewhere. */
    boolean isShowing();

    /**
     * True while a docked session exists at all (player alive), regardless
     * of whether its overlay view is currently attached to any window.
     */
    boolean isActive();

    /**
     * Remove the overlay from whatever Activity window it's in right now,
     * WITHOUT stopping playback — the session stays alive so any screen
     * that later calls {@link #attachToActivity} can pick it back up with
     * no reload / no dropped frame.
     */
    void detachKeepAlive();

    /**
     * Re-parent the still-playing overlay into a different Activity's
     * window. No-op if there is no active session.
     */
    void attachToActivity(@NonNull Activity activity);
}
