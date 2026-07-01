package com.callx.app.utils;

import android.app.Activity;
import android.content.res.Resources;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * EDGE SWIPE — BACK GESTURE
 * ──────────────────────────────────────────────────────────────────────
 * Reusable helper: kisi bhi Activity ko LEFT ya RIGHT screen-edge se
 * horizontal swipe karke "back" (finish) karne ka gesture deta hai —
 * WhatsApp / iOS jaisa edge-swipe-back.
 *
 * Kyun edge-only (poore screen par nahi)?
 *   Chat screen ke andar RecyclerView items par pehle se hi "swipe to
 *   reply" (SwipeReplyHandler) gesture hai jo horizontal swipe use
 *   karta hai. Agar poore screen par swipe-to-back allow karte toh
 *   dono gestures clash kar jate. Isliye sirf screen ke bilkul
 *   left/right edge (~24dp thin strip) se shuru hone wala swipe hi
 *   back trigger karta hai — baaki jagah scroll / reply-swipe / taps
 *   bilkul normal kaam karte rehte hain.
 *
 * Kaam kaise karta hai:
 *   Activity.dispatchTouchEvent() view hierarchy ke touch consume
 *   karne SE PEHLE har MotionEvent dekhta hai — isliye yehi sahi jagah
 *   hai edge-swipe detect karne ke liye, RecyclerView / Buttons ke
 *   consume karne ka wait kiye bina aur unke saath clash kiye bina.
 *
 * USAGE (kisi bhi Activity mein — sirf 3 lines):
 *
 *   private EdgeSwipeBackHelper swipeBackHelper;
 *
 *   protected void onCreate(Bundle b) {
 *       super.onCreate(b);
 *       setContentView(binding.getRoot());
 *       swipeBackHelper = new EdgeSwipeBackHelper(this, binding.getRoot());
 *   }
 *
 *   @Override
 *   public boolean dispatchTouchEvent(MotionEvent ev) {
 *       if (swipeBackHelper != null && swipeBackHelper.onDispatchTouchEvent(ev)) {
 *           return true;
 *       }
 *       return super.dispatchTouchEvent(ev);
 *   }
 *
 * Custom behaviour chahiye (default finish() ki jagah kuch aur karna
 * ho) toh 3-arg constructor use karo:
 *   new EdgeSwipeBackHelper(this, binding.getRoot(), () -> { ... });
 */
public class EdgeSwipeBackHelper {

    /** Callback jo trigger hota hai jab valid edge-swipe complete ho jaaye. */
    public interface SwipeBackListener {
        void onSwipeBack();
    }

    /** Screen ke left/right edge se kitni width (dp) tak gesture "candidate" mana jaaye. */
    private static final int EDGE_ZONE_DP = 24;

    /** Itni horizontal distance (dp) cross karne ke baad hi back trigger hota hai. */
    private static final int TRIGGER_DISTANCE_DP = 70;

    private final View rootView;
    private final SwipeBackListener listener;
    private final float edgeZonePx;
    private final float triggerDistancePx;
    private final int touchSlop;

    private float downX, downY;
    private boolean candidateFromLeft;
    private boolean candidateFromRight;
    private boolean dragging;
    private boolean rejected;

    public EdgeSwipeBackHelper(Activity activity, View rootView) {
        this(activity, rootView, defaultListener(activity));
    }

    public EdgeSwipeBackHelper(Activity activity, View rootView, SwipeBackListener listener) {
        this.rootView = rootView;
        this.listener = listener;
        float density = Resources.getSystem().getDisplayMetrics().density;
        this.edgeZonePx = EDGE_ZONE_DP * density;
        this.triggerDistancePx = TRIGGER_DISTANCE_DP * density;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
    }

    private static SwipeBackListener defaultListener(Activity activity) {
        return () -> {
            activity.onBackPressed();
            activity.overridePendingTransition(
                    android.R.anim.slide_in_left,
                    android.R.anim.slide_out_right);
        };
    }

    /**
     * Activity.dispatchTouchEvent() se call karo, sabse pehle.
     * True return kare toh event yahin consume ho gaya hai — us case
     * mein super.dispatchTouchEvent() call MAT karna.
     */
    public boolean onDispatchTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                int width = rootView.getWidth();
                candidateFromLeft = downX <= edgeZonePx;
                candidateFromRight = width > 0 && downX >= width - edgeZonePx;
                dragging = false;
                rejected = !(candidateFromLeft || candidateFromRight);
                // DOWN kabhi consume nahi karte — taps/clicks normal chalte rahen.
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (rejected || (!candidateFromLeft && !candidateFromRight)) return false;

                float diffX = ev.getRawX() - downX;
                float diffY = ev.getRawY() - downY;

                if (!dragging) {
                    // Vertical intent zyada dikh raha hai (list scroll) — hat jao.
                    if (Math.abs(diffY) > touchSlop && Math.abs(diffY) > Math.abs(diffX)) {
                        rejected = true;
                        return false;
                    }
                    boolean correctDirection = candidateFromLeft ? diffX > 0 : diffX < 0;
                    if (correctDirection && Math.abs(diffX) > touchSlop * 2f) {
                        dragging = true;
                        // Baaki hierarchy (RecyclerView/Button) ko cancel bhej dete
                        // hain taaki wo apna partial-press/scroll state chhod de aur
                        // hum hi gesture ke akele owner ban jaayein.
                        sendCancelToRoot(ev);
                    }
                }
                // Dragging shuru hone ke baad hi hum event consume karte hain.
                return dragging;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (dragging) {
                    float diffX = ev.getRawX() - downX;
                    dragging = false;
                    if (Math.abs(diffX) > triggerDistancePx) {
                        listener.onSwipeBack();
                    }
                    return true;
                }
                return false;
            }

            default:
                return dragging;
        }
    }

    private void sendCancelToRoot(MotionEvent original) {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_CANCEL, original.getX(), original.getY(), 0);
        rootView.dispatchTouchEvent(cancel);
        cancel.recycle();
    }
}
