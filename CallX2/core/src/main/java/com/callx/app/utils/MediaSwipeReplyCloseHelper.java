package com.callx.app.utils;

import android.content.Context;
import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * SWIPE-UP-TO-REPLY / SWIPE-DOWN-TO-CLOSE
 * ──────────────────────────────────────────────────────────────────────
 * MediaViewerActivity ke grouped-media (multi-media) gallery mode se
 * extract kiya gaya common helper, taaki single-media viewer bhi same
 * gesture reuse kar sake (pehle sirf gallery mode mein tha).
 *
 * Pinch-zoom se conflict na ho isliye caller PhotoViewZoomUtils.isZoomedIn()
 * ko ZoomedStateProvider ke through wire kare — jab tak user zoomed-in hai,
 * gesture start hi nahi hoga.
 *
 * Usage — Activity ke andar ek instance banao:
 *
 *   swipeHelper = new MediaSwipeReplyCloseHelper(
 *       this,
 *       dragView,               // jo translateY/alpha ke saath move hota hai
 *       rootBackgroundView,     // jiska background black→transparent fade hota hai
 *       pagerToCancelOrNull,    // ViewPager2 jaisa horizontal-scroll view, drag start pe cancel karne ke liye (single-media mode mein null de do)
 *       () -> PhotoViewZoomUtils.isZoomedIn(activePhotoView),
 *       new MediaSwipeReplyCloseHelper.Callback() {
 *           public void onSwipeUpReply() { ... }
 *           public void onSwipeDownClose() { finish(); }
 *       });
 *
 * Phir Activity ke dispatchTouchEvent() se:
 *
 *   public boolean dispatchTouchEvent(MotionEvent ev) {
 *       if (swipeHelper != null && swipeHelper.onTouch(ev)) return true;
 *       return super.dispatchTouchEvent(ev);
 *   }
 */
public class MediaSwipeReplyCloseHelper {

    public interface Callback {
        /** Swipe UP threshold cross ho gaya — reply action fire karo. */
        void onSwipeUpReply();
        /** Swipe DOWN threshold cross ho gaya — viewer close karo. */
        void onSwipeDownClose();
    }

    /** Har baar naye gesture se pehle check hota hai — true ho to gesture skip. */
    public interface ZoomedStateProvider {
        boolean isZoomedIn();
    }

    private static final float SWIPE_DISMISS_THRESHOLD_DP = 100f;

    private final Context context;
    private final View dragView;
    private final View backgroundView;      // nullable
    private final View viewToCancelOnDrag;  // nullable — e.g. ViewPager2, so it stops trying to page
    private final ZoomedStateProvider zoomedStateProvider; // nullable
    private final Callback callback;
    private final int touchSlop;

    private float startX, startY;
    private boolean dragging = false;
    private boolean enabled = true;

    public MediaSwipeReplyCloseHelper(Context context, View dragView, View backgroundView,
                                       View viewToCancelOnDrag, ZoomedStateProvider zoomedStateProvider,
                                       Callback callback) {
        this.context = context;
        this.dragView = dragView;
        this.backgroundView = backgroundView;
        this.viewToCancelOnDrag = viewToCancelOnDrag;
        this.zoomedStateProvider = zoomedStateProvider;
        this.callback = callback;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** Select-mode jaise cases mein gesture ko temporarily band karne ke liye. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Activity ke dispatchTouchEvent() se har MotionEvent yaha bhejo.
     * True return kare to event consume ho gaya hai (aage propagate mat
     * karo); false pe caller apna normal dispatch chalaye.
     */
    public boolean onTouch(MotionEvent ev) {
        if (!enabled) return false;

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startX = ev.getRawX();
                startY = ev.getRawY();
                dragging = false;
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    // Pinch-zoomed hai to naya drag start hi mat karo.
                    if (zoomedStateProvider != null && zoomedStateProvider.isZoomedIn()) return false;

                    float dx0 = ev.getRawX() - startX;
                    float dy0 = ev.getRawY() - startY;
                    if (Math.abs(dy0) > touchSlop && Math.abs(dy0) > Math.abs(dx0) * 1.5f) {
                        dragging = true;
                        if (viewToCancelOnDrag != null) {
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            viewToCancelOnDrag.dispatchTouchEvent(cancel);
                            cancel.recycle();
                        }
                    } else {
                        return false;
                    }
                }
                float dy = ev.getRawY() - startY;
                dragView.setTranslationY(dy);
                float dragFraction = Math.min(1f, Math.abs(dy) / dp(400));
                dragView.setAlpha(1f - dragFraction * 0.6f);
                if (backgroundView != null) {
                    backgroundView.setBackgroundColor(
                            Color.argb((int) (255 * (1f - dragFraction)), 0, 0, 0));
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) return false;
                float dy = ev.getRawY() - startY;
                float thresholdPx = dp((int) SWIPE_DISMISS_THRESHOLD_DP);
                dragging = false;
                if (dy <= -thresholdPx) {
                    callback.onSwipeUpReply();
                } else if (dy >= thresholdPx) {
                    callback.onSwipeDownClose();
                } else {
                    // Threshold tak nahi pahuncha — snap back.
                    dragView.animate().translationY(0).alpha(1f).setDuration(180).start();
                    if (backgroundView != null) backgroundView.setBackgroundColor(Color.BLACK);
                }
                return true;
            }

            default:
                return false;
        }
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
