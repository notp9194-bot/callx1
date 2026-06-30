package com.callx.app.utils;

import android.app.Dialog;
import android.view.MotionEvent;

import com.github.chrisbanes.photoview.PhotoView;

/**
 * AVATAR ZOOM — SWIPE-DOWN-TO-CLOSE
 * ──────────────────────────────────────────────────────────────────────
 * Common helper jo sab 6 showAvatarZoom() jagah pe use hota hai
 * (UserProfileActivity, ProfileActivity, CallsFragment, ChatsFragment,
 * ReelUserProfileSheet, UserReelsActivity).
 *
 * Strategy (jaisa decide kiya tha):
 *   - PhotoView ka built-in OnSingleFlingListener use karte hain.
 *   - Agar user ne pinch-zoom kiya hua hai (scale > ~1.05f) toh swipe
 *     ignore karte hain — warna pan/zoom ke saath conflict ho jayega.
 *   - Fling ka direction check karte hain: Y velocity dominant honi
 *     chahiye (downward) aur ek minimum threshold cross karni chahiye,
 *     tabhi dialog.dismiss() karte hain. Sideways ya upward flings
 *     ignore ho jate hain.
 *
 * Usage: PhotoView aur Dialog create karne ke baad bas ek line:
 *   AvatarZoomSwipeHelper.attachSwipeToClose(photoView, dialog);
 */
public final class AvatarZoomSwipeHelper {

    private AvatarZoomSwipeHelper() {
        // no instances
    }

    /** Fling ki minimum downward velocity (px/sec) jisse dismiss trigger ho. */
    private static final float MIN_DOWN_VELOCITY = 800f;

    /**
     * Agar scale isse zyada hai, matlab user ne zoom kiya hua hai —
     * tab swipe-to-close ko ignore karte hain taaki pan gesture na toote.
     */
    private static final float ZOOMED_IN_THRESHOLD = 1.05f;

    /**
     * PhotoView par swipe-down-to-close attach karta hai.
     *
     * @param photoView jis par gesture detect karna hai
     * @param dialog    jo dismiss hoga jab valid downward swipe mile
     */
    public static void attachSwipeToClose(PhotoView photoView, Dialog dialog) {
        if (photoView == null || dialog == null) return;

        photoView.setOnSingleFlingListener(
            (MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) -> {
                // Zoomed in hai toh swipe ko ignore karo — pinch/pan priority par hai.
                if (photoView.getScale() > ZOOMED_IN_THRESHOLD) return false;

                float absX = Math.abs(velocityX);
                float absY = Math.abs(velocityY);

                // Downward dominant fling chahiye, sideways/upward nahi.
                boolean isDownward = velocityY > MIN_DOWN_VELOCITY;
                boolean isDominantVertical = absY > absX * 1.5f;

                if (isDownward && isDominantVertical) {
                    dialog.dismiss();
                    return true;
                }
                return false;
            });
    }
}
