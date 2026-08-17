package com.callx.app.utils;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewOutlineProvider;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * SWIPE-UP-TO-REPLY / SWIPE-DOWN-TO-CLOSE — v3 (advanced interactive gesture)
 * ──────────────────────────────────────────────────────────────────────
 * MediaViewerActivity ke grouped-media (multi-media) gallery mode se
 * extract kiya gaya common helper, taaki single-media viewer bhi same
 * gesture reuse kar sake (pehle sirf gallery mode mein tha).
 *
 * v3 upgrade — ab ye ek "live" Telegram/Instagram-style interactive dismiss
 * hai, sirf ek plain translateY nahi:
 *   • Drag ke saath-saath `dragView` progressively SCALE-DOWN hota hai
 *     (rubber-band feel) aur corner-radius bhi progressively round hota
 *     hai — jaise photo apni chhoti thumbnail shape ki taraf morph ho
 *     rahi ho, drag khatam hone se pehle hi.
 *   • Top-bar/toolbar/page-counter jaisi "chrome" views (agar diya gaya
 *     ho) drag fraction ke saath hi live fade hoti hain, sirf release pe
 *     nahi.
 *   • Background scrim ek eased (linear nahi) curve pe dim hota hai taaki
 *     dismissal atmospherically zyada premium/polished lage.
 *   • VelocityTracker se fling detect hota hai — tez flick pe, chahe drag
 *     distance threshold tak na pahuncha ho, viewer band ho jaata hai
 *     (Telegram/Instagram jaisa "throw to dismiss").
 *   • Threshold cross na ho to ek physics-based SpringAnimation se
 *     natural, halka bouncy snap-back hota hai — flat linear tween nahi.
 *
 * Pinch-zoom se conflict na ho isliye caller PhotoViewZoomUtils.isZoomedIn()
 * ko ZoomedStateProvider ke through wire kare — jab tak user zoomed-in hai,
 * gesture start hi nahi hoga.
 *
 * Usage — Activity ke andar ek instance banao:
 *
 *   swipeHelper = new MediaSwipeReplyCloseHelper(
 *       this,
 *       dragView,               // jo scale/translateY/alpha ke saath move hota hai
 *       rootBackgroundView,     // jiska background black→transparent fade hota hai
 *       pagerToCancelOrNull,    // ViewPager2 jaisa horizontal-scroll view, drag start pe cancel karne ke liye (single-media mode mein null de do)
 *       () -> PhotoViewZoomUtils.isZoomedIn(activePhotoView),
 *       new MediaSwipeReplyCloseHelper.Callback() {
 *           public void onSwipeUpReply() { ... }
 *           public void onSwipeDownClose(float velocityY) { closeViewer(velocityY); }
 *       });
 *   swipeHelper.setChromeViews(binding.llTopBar, binding.tvPageCounter);
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
        /**
         * Swipe UP ya DOWN threshold cross ho gaya (ya tez fling), viewer
         * close karo. `velocityY` (px/sec, signed — down positive) taaki
         * caller apni exit animation ki speed/direction usi se seed kar
         * sake, taaki drag se close-animation mein koi visual "jump" na ho.
         */
        void onSwipeDownClose(float velocityY);
    }

    /** Har baar naye gesture se pehle check hota hai — true ho to gesture skip. */
    public interface ZoomedStateProvider {
        boolean isZoomedIn();
    }

    private static final float SWIPE_DISMISS_THRESHOLD_DP = 100f;
    /** Drag distance (dp) pe live scale/radius/scrim effect apne max tak pahunch jaata hai. */
    private static final float MAX_EFFECT_DRAG_DP = 320f;
    /** Max drag pe dragView is fraction tak scale-down hota hai (rubber band floor). */
    private static final float MIN_DRAG_SCALE = 0.62f;
    /** Fully dragged-out par on-screen corner-radius (matches chat-bubble media rounding). */
    private static final float MAX_DRAG_RADIUS_DP = 20f;
    /** Isse tez fling ho to, chahe distance threshold cross na ho, phir bhi dismiss. */
    private static final float FLING_VELOCITY_DP_PER_SEC = 1100f;

    private final Context context;
    private final View dragView;
    private final View backgroundView;      // nullable
    private final View viewToCancelOnDrag;  // nullable — e.g. ViewPager2, so it stops trying to page
    private final ZoomedStateProvider zoomedStateProvider; // nullable
    private final Callback callback;
    private final int touchSlop;
    // PERF (ultra-advanced pass v2): cached once instead of re-resolving
    // Resources → DisplayMetrics → density on every dp() call — dp() runs
    // multiple times per ACTION_MOVE frame during a live drag, so this
    // avoids a repeated lookup chain on the hottest path in this class.
    private final float density;

    private View[] chromeViews = new View[0];

    private float startX, startY;
    private boolean dragging = false;
    private boolean enabled = true;
    private VelocityTracker velocityTracker;

    private float currentLocalRadiusPx = 0f;
    // PERF (ultra-advanced pass v2): the local (pre-transform) radius that
    // was actually last handed to invalidateOutline(). Kept separate from
    // currentLocalRadiusPx (which must always hold the exact live value —
    // getCurrentOnScreenRadiusPx() and animation-continuity math depend on
    // it) so radius updates can be tracked precisely while the relatively
    // expensive native outline recompute is only triggered when the value
    // has moved enough to matter. See updateLocalRadius() below.
    private float lastInvalidatedLocalRadiusPx = -1f;
    private static final float RADIUS_INVALIDATE_EPSILON_PX = 0.5f;
    private SpringAnimation snapBackSpring;

    // ── Idle-state override (default = old behaviour: full-bleed square,
    //    scale 1, radius 0 — matches MediaViewerActivity's chat-media use).
    private float idleScale = 1f;
    private float idleOnScreenRadiusPx = 0f;
    private boolean insetSquareClip = false;

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
        this.density = context.getResources().getDisplayMetrics().density;
        installCornerClip();
    }

    /** Optional — top bar / toolbar / page-counter jaisi views jo drag ke saath live fade ho. */
    public void setChromeViews(View... views) {
        this.chromeViews = (views != null) ? views : new View[0];
    }

    /**
     * Overrides the "resting" (undragged) baseline this gesture springs
     * back to / drags away from. Default is (scale=1, radius=0) — the
     * old full-bleed-square media-viewer assumption. A caller whose idle
     * state is itself a circle smaller than the full view (e.g. the
     * WhatsApp/Instagram-style avatar viewer, which never un-rounds to a
     * square) MUST call this before any drag happens, otherwise the very
     * first drag frame snaps to the wrong baseline (visible as a jarring
     * "pops to a square" glitch).
     *
     * @param insetSquareClip true for a dragView whose own width/height
     *        aren't square (e.g. full-screen match_parent) — clips an
     *        inset centered square instead of rounding the full view
     *        rect's corners, which is the only way a rounded-rect outline
     *        reads as an actual circle instead of a stadium/pill shape.
     */
    public void configureIdleState(float idleScale, float idleOnScreenRadiusPx, boolean insetSquareClip) {
        this.idleScale = idleScale;
        this.idleOnScreenRadiusPx = idleOnScreenRadiusPx;
        this.insetSquareClip = insetSquareClip;
        this.currentLocalRadiusPx = idleScale > 0.001f ? idleOnScreenRadiusPx / idleScale : 0f;
        // Keep the epsilon-tracker in sync so the very next live-radius
        // update correctly compares against this real baseline instead of
        // a stale value from a previous dialog instance.
        this.lastInvalidatedLocalRadiusPx = currentLocalRadiusPx;
        dragView.invalidateOutline();
    }

    /** Select-mode jaise cases mein gesture ko temporarily band karne ke liye. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Caller (MediaViewerActivity) ke apne open/close "dock to thumbnail"
     * animations bhi isi outline-clip machinery ko drive kar sakein — taaki
     * live-drag se release-animation tak corner-radius mein koi seam/jump
     * na ho, dono ek hi underlying state share karte hain.
     */
    public void setLiveCornerRadius(float onScreenRadiusPx, float currentScale) {
        float newLocalRadius = currentScale > 0.001f ? onScreenRadiusPx / currentScale : 0f;
        updateLocalRadius(newLocalRadius);
    }

    /**
     * PERF (ultra-advanced pass v2): single choke point for every radius
     * change in this class (live drag, spring-back, and the dock open/close
     * animators via setLiveCornerRadius). currentLocalRadiusPx is always
     * kept exact, but the native invalidateOutline() → outline recompute →
     * RenderNode re-clip is only actually triggered once the LOCAL radius
     * has moved by more than RADIUS_INVALIDATE_EPSILON_PX since the last
     * time it was invalidated — sub-pixel moves are visually identical
     * either way, so paying the native re-clip cost for them is pure waste.
     * On a 90/120Hz-touch-sampling device a drag can call this far more
     * often than the display actually redraws, so this is a meaningful cut
     * in native work, not just a cosmetic threshold.
     */
    private void updateLocalRadius(float newLocalRadius) {
        currentLocalRadiusPx = newLocalRadius;
        if (Math.abs(newLocalRadius - lastInvalidatedLocalRadiusPx) >= RADIUS_INVALIDATE_EPSILON_PX) {
            lastInvalidatedLocalRadiusPx = newLocalRadius;
            dragView.invalidateOutline();
        }
    }

    /**
     * PERF (ultra-advanced pass): while the interactive drag/dock animation
     * is live, dragView's scale + translationY + alpha + outline-clip all
     * change together on *every* frame. Without a GPU layer, each of those
     * frames re-draws + re-clips the view's full content from scratch. By
     * caching the view into a hardware layer for the duration of the
     * gesture, the (relatively expensive) content draw + clip happens once,
     * and every subsequent frame is just a cheap GPU-composited transform —
     * this is what makes the difference between a slightly-stuttery drag and
     * a genuinely butter-smooth one on mid/low-end devices.
     * Reverted back to LAYER_TYPE_NONE the instant the gesture settles (see
     * resetVisualsInstant) — a hardware layer costs GPU memory, so it's only
     * held for as long as it's actually earning its keep.
     */
    private void enableHardwareLayerForGesture() {
        if (dragView.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            dragView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }
    }

    private void disableHardwareLayerForGesture() {
        if (dragView.getLayerType() != View.LAYER_TYPE_NONE) {
            dragView.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    /** Current on-screen (post-scale) corner radius — animation continuity ke liye starting point. */
    public float getCurrentOnScreenRadiusPx() {
        float scale = dragView.getScaleX();
        return currentLocalRadiusPx * (scale > 0f ? scale : 1f);
    }

    /** dragView par rounded-corner clipping enable karta hai; radius live drag ke saath update hota hai. */
    private void installCornerClip() {
        dragView.setClipToOutline(true);
        dragView.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
                float r = Math.max(0f, currentLocalRadiusPx);
                if (insetSquareClip) {
                    // TRUE CIRCLE fix: a rounded-rect spanning the view's
                    // FULL (possibly non-square, e.g. full-screen) width/
                    // height only ever looks like a circle when the view
                    // itself is square — otherwise, for any radius under
                    // half the LARGER dimension, it reads as a stadium/pill
                    // (rounded corners, straight sides), not a circle. So
                    // instead clip an inset SQUARE region of side 2r,
                    // centered in the view — that square-in-square-view
                    // relationship is what makes the outline a real circle
                    // regardless of the outer dragView's own aspect ratio.
                    float side = Math.min(2f * r, Math.min(view.getWidth(), view.getHeight()));
                    float cx = view.getWidth() / 2f;
                    float cy = view.getHeight() / 2f;
                    outline.setRoundRect(
                            Math.round(cx - side / 2f), Math.round(cy - side / 2f),
                            Math.round(cx + side / 2f), Math.round(cy + side / 2f),
                            side / 2f);
                } else {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            }
        });
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
                if (snapBackSpring != null) { snapBackSpring.cancel(); snapBackSpring = null; }
                if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                else velocityTracker.clear();
                velocityTracker.addMovement(ev);
                return false;

            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    // Pinch-zoomed hai to naya drag start hi mat karo.
                    if (zoomedStateProvider != null && zoomedStateProvider.isZoomedIn()) return false;

                    float dx0 = ev.getRawX() - startX;
                    float dy0 = ev.getRawY() - startY;
                    if (Math.abs(dy0) > touchSlop && Math.abs(dy0) > Math.abs(dx0) * 1.5f) {
                        dragging = true;
                        enableHardwareLayerForGesture();
                        if (viewToCancelOnDrag != null) {
                            MotionEvent cancel = MotionEvent.obtain(ev);
                            cancel.setAction(MotionEvent.ACTION_CANCEL);
                            viewToCancelOnDrag.dispatchTouchEvent(cancel);
                            cancel.recycle();
                        }
                    } else {
                        if (velocityTracker != null) velocityTracker.addMovement(ev);
                        return false;
                    }
                }
                if (velocityTracker != null) velocityTracker.addMovement(ev);

                float dy = ev.getRawY() - startY;
                applyLiveDragFrame(dy);
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) {
                    recycleVelocityTracker();
                    return false;
                }
                float dy = ev.getRawY() - startY;
                float thresholdPx = dp(SWIPE_DISMISS_THRESHOLD_DP);
                dragging = false;

                float velocityY = 0f;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(ev);
                    velocityTracker.computeCurrentVelocity(1000); // px/sec
                    velocityY = velocityTracker.getYVelocity();
                }
                float flingPx = dp(FLING_VELOCITY_DP_PER_SEC);
                boolean pastDistanceThreshold = (dy <= -thresholdPx || dy >= thresholdPx);
                // Fling ke liye velocity aur drag direction same sign hone chahiye
                // (warna ek chhota jhatka galat direction mein bhi dismiss trigger
                // kar dega — Telegram bhi direction-consistent fling hi maanta hai).
                boolean pastFlingThreshold = Math.abs(velocityY) >= flingPx
                        && Math.signum(velocityY) == Math.signum(dy != 0 ? dy : velocityY);

                recycleVelocityTracker();

                // UPDATE: swipe-up-to-reply retired — ab UP aur DOWN dono
                // hi directions viewer ko close karte hain (pehle sirf DOWN
                // close karta tha, UP reply trigger karta tha). onSwipeUpReply
                // ab kabhi fire nahi hota; interface mein backward-compat ke
                // liye reh gaya hai.
                if (pastDistanceThreshold || pastFlingThreshold) {
                    callback.onSwipeDownClose(velocityY);
                } else {
                    springBack(velocityY);
                }
                return true;
            }

            default:
                return false;
        }
    }

    /** Ek touch-move frame ke liye saare live-drag visuals (position/scale/radius/fade/scrim) apply karta hai. */
    private void applyLiveDragFrame(float dy) {
        dragView.setTranslationY(dy);

        float t = Math.min(1f, Math.abs(dy) / dp(MAX_EFFECT_DRAG_DP));
        float scale = idleScale - t * (idleScale - idleScale * MIN_DRAG_SCALE);
        dragView.setScaleX(scale);
        dragView.setScaleY(scale);

        if (insetSquareClip) {
            // Idle-circle case: leave the LOCAL (pre-transform) radius
            // exactly where configureIdleState left it. Since on-screen
            // radius = local * scale, it then shrinks automatically and
            // smoothly in lockstep with the rubber-band scale-down above —
            // the circle stays a circle the whole drag. (The old formula
            // below reset local radius toward 0 every frame, which is what
            // made the circle instantly snap to a near-full rectangle the
            // moment any drag started.)
            //
            // PERF (ultra-advanced pass v2): because the LOCAL radius is
            // deliberately left untouched here, the outline's actual
            // pre-transform geometry is bit-for-bit identical every frame
            // of this gesture — only dragView's scale/translationY change,
            // and those are composited transforms applied *after* clipping,
            // not part of the outline itself. So this branch used to call
            // invalidateOutline() ~every touch-move frame for genuinely zero
            // visual effect. It's removed entirely (not just throttled) —
            // every avatar-viewer drag in the app now does zero native
            // outline recomputes for its whole duration.
        } else {
            // On-screen radius directly t-driven (0 → MAX_DRAG_RADIUS_DP); local
            // (pre-transform) radius compensates for the current scale so the
            // *visible* radius on screen grows smoothly and linearly with t —
            // see class doc for the math.
            float onScreenRadiusPx = t * dp(MAX_DRAG_RADIUS_DP);
            float newLocalRadius = scale > 0.001f ? onScreenRadiusPx / scale : 0f;
            updateLocalRadius(newLocalRadius);
        }

        // Content itself fades a little as it's dragged out — subtle, not
        // a full fade (background scrim carries most of the dimming).
        dragView.setAlpha(1f - t * 0.22f);

        // Chrome (top bar / toolbar / page counter) fades out live with the
        // drag instead of only reacting on release — feels attached to the
        // same gesture rather than a separate after-the-fact animation.
        float chromeAlpha = 1f - Math.min(1f, t * 1.6f);
        for (View v : chromeViews) {
            if (v != null) v.setAlpha(chromeAlpha);
        }

        // Background scrim — eased (not linear) so it dims a bit faster at
        // the start of the gesture, giving the dismissal a more atmospheric,
        // premium feel instead of a mechanical linear fade.
        if (backgroundView != null) {
            float easedT = (float) Math.pow(t, 0.72);
            int alpha = (int) (255 * (1f - easedT));
            backgroundView.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
        }
    }

    /** Threshold tak nahi pahuncha — natural bouncy spring se sab kuch wapas center/full-scale/square. */
    private void springBack(float initialVelocityY) {
        final float startTranslation = dragView.getTranslationY();
        if (startTranslation == 0f) {
            resetVisualsInstant();
            return;
        }

        SpringForce force = new SpringForce(0f)
                .setDampingRatio(SpringForce.DAMPING_RATIO_LOW_BOUNCY)
                .setStiffness(SpringForce.STIFFNESS_MEDIUM);

        snapBackSpring = new SpringAnimation(dragView, DynamicAnimation.TRANSLATION_Y, 0f);
        snapBackSpring.setSpring(force);
        snapBackSpring.setStartVelocity(initialVelocityY);
        snapBackSpring.addUpdateListener((animation, value, velocity) -> {
            // Derive a 0..1 "settled" fraction from how far translationY has
            // sprung back, and drive scale/radius/alpha/scrim from the same
            // fraction — keeps every property perfectly in sync with the
            // spring instead of running a second, separately-timed tween.
            float frac = 1f - Math.min(1f, Math.abs(value) / Math.abs(startTranslation));
            float t = 1f - frac; // same meaning as `t` in applyLiveDragFrame
            float scale = idleScale - t * (idleScale - idleScale * MIN_DRAG_SCALE);
            dragView.setScaleX(scale);
            dragView.setScaleY(scale);
            // PERF (ultra-advanced pass v2): same reasoning as
            // applyLiveDragFrame — for the insetSquareClip (avatar) case the
            // local radius never moves during this spring, so skip the
            // outline call for it entirely; for the non-circle case, route
            // through updateLocalRadius so sub-pixel spring ticks (this
            // listener can fire well above 60Hz) don't each force a native
            // re-clip.
            if (!insetSquareClip) {
                float onScreenRadiusPx = t * dp(MAX_DRAG_RADIUS_DP);
                float newLocalRadius = scale > 0.001f ? onScreenRadiusPx / scale : 0f;
                updateLocalRadius(newLocalRadius);
            }
            dragView.setAlpha(1f - t * 0.22f);
            float chromeAlpha = 1f - Math.min(1f, t * 1.6f);
            for (View v : chromeViews) {
                if (v != null) v.setAlpha(chromeAlpha);
            }
            if (backgroundView != null) {
                float easedT = (float) Math.pow(t, 0.72);
                int alpha = (int) (255 * (1f - easedT));
                backgroundView.setBackgroundColor(Color.argb(alpha, 0, 0, 0));
            }
        });
        snapBackSpring.addEndListener((animation, canceled, value, velocity) -> {
            if (!canceled) resetVisualsInstant();
        });
        snapBackSpring.start();
    }

    /** Hard-resets all live-drag visual state to "resting" — used once a spring/gesture fully settles. */
    private void resetVisualsInstant() {
        dragView.setTranslationY(0f);
        dragView.setScaleX(idleScale);
        dragView.setScaleY(idleScale);
        dragView.setAlpha(1f);
        currentLocalRadiusPx = idleScale > 0.001f ? idleOnScreenRadiusPx / idleScale : 0f;
        lastInvalidatedLocalRadiusPx = currentLocalRadiusPx;
        dragView.invalidateOutline();
        for (View v : chromeViews) {
            if (v != null) v.setAlpha(1f);
        }
        if (backgroundView != null) backgroundView.setBackgroundColor(Color.BLACK);
        // Gesture fully settled — release the GPU layer, see enableHardwareLayerForGesture() doc.
        disableHardwareLayerForGesture();
    }

    private void recycleVelocityTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    private float dp(float value) {
        return value * density;
    }
}
