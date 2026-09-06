package com.callx.app.chat.performance;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * FastFlingRecyclerView — v2 (ultra-advanced pass).
 *
 * ── WHAT CHANGED FROM v1 ───────────────────────────────────────────────────
 * v1 boosted every fling by one flat constant (velocity * 1.35). That's
 * functionally "more glide" but has two real gaps versus how WhatsApp/
 * Telegram's lists actually feel:
 *   1. A FLAT multiplier scales a light flick and a hard flick by the same
 *      ratio. In practice a hard flick already covers a lot of ground on
 *      its own, so a flat boost mostly adds extra (sometimes runaway-feeling)
 *      distance to the flings that least needed it, while a genuinely soft
 *      flick — the one the original ask was about — gets the same modest
 *      bump as everything else.
 *   2. Velocity boost alone doesn't interact with the rest of the screen's
 *      perf pipeline. rv_messages already turns on a hardware layer only
 *      while a real finger-driven gesture is in progress (see ChatActivity's
 *      handleLayerTypeStateChanged() — deliberately excludes purely
 *      programmatic settles to avoid a mid-content-change flicker). A v1
 *      boosted fling continues gliding well after finger-up, i.e. exactly
 *      the window that guard switches the layer OFF, so the longer the
 *      glide got, the more frames of full bubble-canvas repaint it paid for
 *      — the boost was fighting the app's own scroll-perf optimization.
 *
 * ── v2 FIXES ────────────────────────────────────────────────────────────
 * 1. NON-LINEAR, DIMINISHING BOOST CURVE — same asymptotic shape used by
 *    RubberBandEdgeEffectFactory for overscroll resistance, applied here in
 *    reverse (a gain that shrinks as speed grows instead of a resistance
 *    that grows as pull grows):
 *        boosted(v) = v + BOOST_GAIN * v / (1 + |v| / REF_VELOCITY)
 *    For a soft flick (|v| << REF_VELOCITY) this is close to a flat
 *    (1 + BOOST_GAIN)× multiplier — the small-scroll-travels-further feel.
 *    For a hard flick (|v| >> REF_VELOCITY) the added term saturates
 *    towards a fixed cap instead of scaling further, so the relative boost
 *    fades out and a hard flick still feels controlled, not runaway. Final
 *    result is still hard-clamped to the platform's max fling velocity as
 *    a last-resort safety net.
 * 2. FLING-ORIGIN TRACKING — onTouchEvent() marks whether a genuine
 *    finger-driven gesture is in progress for exactly the duration
 *    super.onTouchEvent() may synchronously call fling() from the
 *    ACTION_UP that ends it, so fling() can tell a real touch-released
 *    fling apart from a programmatic one (smoothScrollBy() reveal on a new
 *    message, restoreScrollOrGoToUnread(), etc.) — the same distinction
 *    ChatActivity's own layer-toggle already cares about, just not
 *    something a plain RecyclerView can report on its own.
 * 3. OnUserFlingListener — fires only for a real touch-released fling,
 *    letting the host Activity extend its hardware-layer window to cover
 *    the whole (now longer) boosted glide instead of dropping it the
 *    instant the finger lifts. Purely additive: a host that never sets a
 *    listener behaves exactly like a boosted-only RecyclerView.
 * 4. LIVE VELOCITY EXPOSURE (v3) — getLastFlingVelocityY() reports the
 *    boosted launch velocity of the fling currently in flight (0 once
 *    idle). A boosted medium flick now sustains a higher average speed for
 *    longer than before this feature existed, which means the LayoutManager's
 *    calculateExtraLayoutSpace() pre-laid buffer — sized for the OLD,
 *    un-boosted glide profile — and its initial prefetch count can both run
 *    dry mid-glide, flashing blank rows at the leading edge exactly the way
 *    the original PERF comment on that buffer warned about. Exposing the
 *    launch velocity lets the host LayoutManager scale its buffer/prefetch
 *    to how fast THIS particular glide actually is, instead of permanently
 *    paying for worst-case headroom (memory + layout cost) on every scroll,
 *    including slow ones that never needed it.
 *
 * Drop-in replacement for androidx.recyclerview.widget.RecyclerView in
 * layout XML — no other setup call site needs to change unless it wants
 * the OnUserFlingListener hook or getLastFlingVelocityY().
 */
public class FastFlingRecyclerView extends RecyclerView {

    // Cap on the *relative* boost a soft flick can get (≈+55% at v→0).
    private static final float BOOST_GAIN = 0.55f;
    // Speed (px/s) at which the boost has fallen to half its small-flick
    // value. Tuned around a typical medium flick on a ~2400px-tall 6.5"
    // display; a light "nudge" scroll sits well below this, a hard corner-
    // to-corner fling sits well above it.
    private static final float REF_VELOCITY = 6000f;

    private int maxFlingVelocity;
    private boolean userGestureInProgress = false;
    private OnUserFlingListener flingListener;
    // v3: launch velocity of the in-flight fling, 0 while idle/dragging.
    // Read by the host LayoutManager to size its pre-layout buffer to the
    // actual glide, not a fixed worst-case constant — see javadoc point 4.
    private int lastFlingVelocityY = 0;

    public interface OnUserFlingListener {
        /** Fired only when the boosted fling was triggered by a real touch release. */
        void onUserFlingStarted(int boostedVelocityY);
    }

    public FastFlingRecyclerView(Context context) {
        super(context);
        init(context);
    }

    public FastFlingRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public FastFlingRecyclerView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(Context context) {
        maxFlingVelocity = ViewConfiguration.get(context).getScaledMaximumFlingVelocity();
        // v3: self-registered listener (public API, no reflection needed)
        // purely to clear lastFlingVelocityY the instant the glide ends —
        // keeps the field meaningful as "how fast is THIS glide" rather than
        // "how fast was the last one that ever ran".
        addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    lastFlingVelocityY = 0;
                }
            }
        });
    }

    public void setOnUserFlingListener(OnUserFlingListener listener) {
        this.flingListener = listener;
    }

    /** Boosted launch velocity (px/s) of the fling currently in flight; 0 if idle/dragging. */
    public int getLastFlingVelocityY() {
        return lastFlingVelocityY;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            userGestureInProgress = true;
        }
        // super.onTouchEvent() is what may synchronously invoke fling()
        // below when this ACTION_UP is the release of a flung gesture —
        // userGestureInProgress must still be true for that call.
        boolean handled = super.onTouchEvent(e);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            userGestureInProgress = false;
        }
        return handled;
    }

    @Override
    public boolean fling(int velocityX, int velocityY) {
        boolean isUserGestureFling = userGestureInProgress;
        int boostedY = clampToMax(applyBoostCurve(velocityY));
        boolean handled = super.fling(velocityX, boostedY);
        if (handled) {
            lastFlingVelocityY = boostedY;
            if (isUserGestureFling && flingListener != null) {
                flingListener.onUserFlingStarted(boostedY);
            }
        }
        return handled;
    }

    /** Diminishing-gain boost — see class javadoc for the shape/reasoning. */
    private int applyBoostCurve(int v) {
        float absV = Math.abs((float) v);
        float boosted = v + (BOOST_GAIN * v) / (1f + absV / REF_VELOCITY);
        return (int) boosted;
    }

    private int clampToMax(int velocity) {
        if (velocity > maxFlingVelocity) return maxFlingVelocity;
        if (velocity < -maxFlingVelocity) return -maxFlingVelocity;
        return velocity;
    }
}
