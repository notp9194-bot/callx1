package com.callx.app.feed;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * HomeFeedScrollStateManager — Scroll state machine that gates background work
 * (prefetch, Glide, analytics) based on RecyclerView scroll state.
 *
 * Problem: When user flings the feed, a rapidly-firing sequence of onScroll
 * callbacks + RecyclerView's aggressive ViewHolder binding can steal frame time
 * from the scroll choreographer, causing jank. Traditional apps pause Glide
 * during this, Instagram reduces prefetch priority.
 *
 * Solution: Track scroll state transitions and signal listening subsystems:
 *  • FLINGING: Pause non-critical work (prefetch metadata, Glide decode)
 *  • DRAGGING: Reduce priority but continue
 *  • SETTLING (300ms grace after fling stops): Resume normal priority
 *  • IDLE: Full prefetch + eager work
 *
 * RecyclerView.OnScrollListener.onScrollStateChanged(state) →
 * this.onRecyclerScrollStateChanged(state) → callback fires for subscribers
 * (metadataCache, prefetchManager, etc.).
 */
public class HomeFeedScrollStateManager {

    public interface StateChangeListener {
        void onStateChanged(int newState);
    }

    private static final String TAG = "ScrollStateManager";
    private static final int SETTLE_DELAY_MS = 300;

    private int currentState = HomeFeedUltraOptimizer.SCROLL_IDLE;
    private final StateChangeListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable settleRunnable = null;

    public HomeFeedScrollStateManager(@NonNull StateChangeListener listener) {
        this.listener = listener;
    }

    /**
     * Called from HomeFragment's RecyclerView.OnScrollListener.onScrollStateChanged.
     * Maps RecyclerView scroll states to our internal state machine.
     */
    public void onRecyclerScrollStateChanged(int recyclerState) {
        int newState;

        switch (recyclerState) {
            case RecyclerView.SCROLL_STATE_IDLE:
                // Scroll stopped cleanly (no momentum)
                newState = HomeFeedUltraOptimizer.SCROLL_IDLE;
                cancelSettleTimer();
                break;

            case RecyclerView.SCROLL_STATE_DRAGGING:
                // User's finger is actively on the screen
                newState = HomeFeedUltraOptimizer.SCROLL_DRAGGING;
                cancelSettleTimer();
                break;

            case RecyclerView.SCROLL_STATE_SETTLING:
                // RecyclerView is coasting / decelerating (momentum scroll,
                // sometimes called "fling settling"). This is the highest-jank
                // window — ViewHolders are binding aggressively as the list
                // decelerates. Pause non-critical work.
                newState = HomeFeedUltraOptimizer.SCROLL_FLINGING;
                cancelSettleTimer();

                // Queue a delayed transition to SETTLING state after momentum
                // is exhausted; subsystems can resume normal priority.
                settleRunnable = () -> {
                    currentState = HomeFeedUltraOptimizer.SCROLL_SETTLING;
                    fireStateChange(HomeFeedUltraOptimizer.SCROLL_SETTLING);

                    // One more 300ms later, return to IDLE for full prefetch
                    mainHandler.postDelayed(() -> {
                        if (currentState == HomeFeedUltraOptimizer.SCROLL_SETTLING) {
                            currentState = HomeFeedUltraOptimizer.SCROLL_IDLE;
                            fireStateChange(HomeFeedUltraOptimizer.SCROLL_IDLE);
                        }
                    }, SETTLE_DELAY_MS);
                };
                mainHandler.postDelayed(settleRunnable, SETTLE_DELAY_MS);
                break;

            default:
                return;
        }

        if (newState != currentState) {
            currentState = newState;
            fireStateChange(newState);
        }
    }

    /**
     * Fired repeatedly by RecyclerView.OnScrollListener.onScrolled as the list
     * moves. Can be used to detect "slow scroll" vs "fast fling" and tune work
     * priority accordingly, but mostly a no-op.
     */
    public void onRecyclerScrolled(int dx, int dy) {
        // Could compute velocity here if needed, but RecyclerView scroll state
        // transition is usually sufficient signal.
    }

    private void fireStateChange(int newState) {
        if (listener != null) {
            listener.onStateChanged(newState);
        }
    }

    private void cancelSettleTimer() {
        if (settleRunnable != null) {
            mainHandler.removeCallbacks(settleRunnable);
            settleRunnable = null;
        }
    }

    public int getCurrentState() {
        return currentState;
    }

    public boolean isFlinging() {
        return currentState == HomeFeedUltraOptimizer.SCROLL_FLINGING;
    }

    public boolean isDragging() {
        return currentState == HomeFeedUltraOptimizer.SCROLL_DRAGGING;
    }

    public boolean isIdle() {
        return currentState == HomeFeedUltraOptimizer.SCROLL_IDLE;
    }

    public void shutdown() {
        cancelSettleTimer();
    }
}
