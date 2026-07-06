package com.callx.app.utils;

import com.github.chrisbanes.photoview.PhotoView;

/**
 * PINCH-ZOOM STATE CHECK
 * ──────────────────────────────────────────────────────────────────────
 * MediaViewerActivity ke single-media viewer se extract kiya gaya
 * common helper — "is PhotoView currently pinch-zoomed?" check.
 *
 * Isse koi bhi screen jahan PhotoView hai (single-media viewer,
 * multi-media/grouped gallery viewer, avatar zoom dialog, status
 * viewer, etc.) apne swipe/drag gestures ko pinch-zoom ke saath
 * conflict hone se bacha sakti hai — bas gesture start karne se
 * pehle isZoomedIn() check kar lo.
 *
 * Usage:
 *   if (PhotoViewZoomUtils.isZoomedIn(photoView)) {
 *       // user zoomed in hai — apna swipe-to-close/reply gesture skip karo
 *       return;
 *   }
 */
public final class PhotoViewZoomUtils {

    private PhotoViewZoomUtils() {
        // no instances
    }

    /** Isse zyada scale ho to samjho user ne pinch-zoom kiya hua hai. */
    public static final float ZOOMED_IN_THRESHOLD = 1.05f;

    /**
     * True agar diya gaya PhotoView abhi zoomed-in state mein hai
     * (pinch se scale ZOOMED_IN_THRESHOLD se zyada badhi hui hai).
     * Null-safe — null PhotoView ke liye false return karta hai.
     */
    public static boolean isZoomedIn(PhotoView photoView) {
        return photoView != null && photoView.getScale() > ZOOMED_IN_THRESHOLD;
    }
}
