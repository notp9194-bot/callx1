package com.callx.app.feed;

/**
 * Implemented by the hosting Activity (MainActivity) to receive an instant
 * notification when the user changes their Reels display-mode preference
 * (Immersive vs Normal) from the Reels 3-dot menu — while still sitting on
 * the Reels tab, i.e. with no tab-switch to trigger the usual re-check.
 *
 * The value passed is one of
 * {@code com.callx.app.utils.ReelDisplayModePrefs.MODE_IMMERSIVE} /
 * {@code MODE_NORMAL}.
 */
public interface ReelDisplayModeListener {
    void onReelDisplayModeChanged(String mode);
}
