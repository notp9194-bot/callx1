package com.callx.app.feed;

import android.os.Handler;
import android.os.Looper;
import androidx.viewpager2.widget.ViewPager2;
import java.util.ArrayList;
import java.util.List;

/**
 * ReelsFeedScrollStateManager — scroll-state gate for the Reels tab's
 * vertical feed (ReelsFragment's ViewPager2).
 *
 * Mirrors HomeFeedScrollStateManager's IDLE/DRAGGING/SETTLING(=FLINGING)
 * state machine (SETTLE_DELAY_MS grace period before declaring IDLE again),
 * but scoped to a single, narrow job: shave decoder/CPU/network cost off a
 * fast swipe by deferring purely decorative per-reel work until the fling
 * settles.
 *
 * WHAT THIS GATES (deferred during FLINGING, flushed on settle):
 *  - Liker avatar row: ReelSocialController#fetchLikerAvatars() — a
 *    windowed Firebase query + up to 3 Glide decodes, pure decoration.
 *  - Mutual-followers row: ReelSocialController#loadReelMutualFollowers()
 *    — can chain several extra Firebase reads per reel via
 *    MutualFollowersCache, also pure decoration.
 *
 * WHAT THIS NEVER GATES (fires the instant a reel becomes visible,
 * fling or not — see ReelSocialController#startFirebaseListeners):
 *  - likesCount / commentsCount / sharesCount / repostCount listener
 *  - like / save / follow / repost state listeners
 *  - recordView() / markReelNotificationsRead()
 *  These are the actual counts/likes reads the feed's numbers are built
 *  from — deferring them would show stale/blank state on every fast swipe,
 *  which is the one thing this manager is explicitly NOT allowed to do.
 *
 * Also unrelated to and never touched by this class: video/decoder
 * preload, which has its own separate thermal gating
 * (ReelThermalManager / PrewarmThrottleGuard) keyed off device heat, not
 * scroll state.
 */
public class ReelsFeedScrollStateManager {

    private static final int SETTLE_DELAY_MS = 300;

    private static volatile ReelsFeedScrollStateManager instance;

    public static ReelsFeedScrollStateManager get() {
        if (instance == null) {
            synchronized (ReelsFeedScrollStateManager.class) {
                if (instance == null) instance = new ReelsFeedScrollStateManager();
            }
        }
        return instance;
    }

    private int currentState = ViewPager2.SCROLL_STATE_IDLE;
    private boolean flinging = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable settleRunnable;
    private final List<Runnable> pendingOnSettle = new ArrayList<>();

    private ReelsFeedScrollStateManager() {}

    /** Called from ReelsFragment's ViewPager2.OnPageChangeCallback#onPageScrollStateChanged. */
    public void onScrollStateChanged(int state) {
        currentState = state;
        switch (state) {
            case ViewPager2.SCROLL_STATE_IDLE:
                cancelSettleTimer();
                flushIfIdle();
                break;
            case ViewPager2.SCROLL_STATE_DRAGGING:
                // Finger down — not yet a fling, but treat like one so a
                // fast flick doesn't fire decoration work between finger-down
                // and the fling's SETTLING phase.
                flinging = true;
                cancelSettleTimer();
                break;
            case ViewPager2.SCROLL_STATE_SETTLING:
                // Momentum scroll — the actual "FLINGING" window.
                flinging = true;
                cancelSettleTimer();
                settleRunnable = () -> {
                    flinging = false;
                    flushIfIdle();
                };
                mainHandler.postDelayed(settleRunnable, SETTLE_DELAY_MS);
                break;
            default:
                break;
        }
    }

    private void flushIfIdle() {
        if (flinging) return;
        if (pendingOnSettle.isEmpty()) return;
        List<Runnable> due = new ArrayList<>(pendingOnSettle);
        pendingOnSettle.clear();
        for (Runnable r : due) r.run();
    }

    private void cancelSettleTimer() {
        if (settleRunnable != null) {
            mainHandler.removeCallbacks(settleRunnable);
            settleRunnable = null;
        }
    }

    public boolean isFlinging() {
        return flinging;
    }

    /**
     * Runs {@code r} immediately if the feed isn't currently flinging;
     * otherwise queues it to run once the fling settles. Never use this for
     * counts/likes/follow/save state — only for decorative per-reel work
     * (see class doc).
     */
    public void runNowOrWhenSettled(Runnable r) {
        if (!flinging) {
            r.run();
        } else {
            pendingOnSettle.add(r);
        }
    }
}
