package com.callx.app.chat.performance;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.metrics.performance.JankStats;

import java.util.Locale;

/**
 * v612 — on-device JankStats for the message list (debuggable builds only).
 *
 * <p>Macrobenchmark gives you numbers from a scripted run; this gives you the same kind of
 * numbers from YOUR hands on YOUR chat, with a logcat line per scroll gesture:
 * <pre>adb logcat -s ChatJank</pre>
 * {@code ChatJank: chat scroll: frames=212 jank=9 (4.2%) worstUi=41.3ms}
 *
 * <p>Zero cost at idle: tracking is switched on when the list leaves SCROLL_STATE_IDLE and off
 * when it returns, so only frames produced while the user is actually scrolling are counted.
 * Non-debuggable (release) builds never create it — {@link #attachIfEnabled} returns null.
 *
 * <p>Hooked from ChatActivity / GroupChatActivity's existing
 * {@code applyAdaptiveMessageRecyclerBudget()} (already invoked on every scroll-state change),
 * so no extra RecyclerView listener is registered.
 */
public final class ChatJankMonitor {

    private static final String TAG = "ChatJank";

    private final String screen;
    private final JankStats stats;

    // JankStats beta01 has no executor parameter, so the frame listener may run on a thread
    // other than main. All counters are guarded by `lock`.
    private final Object lock = new Object();
    private long frames, jankFrames;
    private long worstUiNs;
    private boolean scrolling;

    @Nullable
    public static ChatJankMonitor attachIfEnabled(Activity activity, String screen) {
        boolean debuggable =
                (activity.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable) return null;
        try {
            return new ChatJankMonitor(activity, screen);
        } catch (Throwable t) {
            Log.w(TAG, "JankStats unavailable: " + t);
            return null;
        }
    }

    private ChatJankMonitor(Activity activity, String screen) {
        this.screen = screen;
        this.stats = JankStats.createAndTrack(activity.getWindow(), frameData -> {
            synchronized (lock) {
                frames++;
                if (frameData.isJank()) jankFrames++;
                long d = frameData.getFrameDurationUiNanos();
                if (d > worstUiNs) worstUiNs = d;
            }
        });
        this.stats.setTrackingEnabled(false);
    }

    /** Call with RecyclerView.SCROLL_STATE_* on every scroll-state change (main thread). */
    public void onScrollState(int scrollState) {
        final boolean nowScrolling =
                scrollState != androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE;
        if (nowScrolling) {
            synchronized (lock) { scrolling = true; }
            stats.setTrackingEnabled(true);
        } else {
            stats.setTrackingEnabled(false);
            synchronized (lock) {
                if (scrolling) flush();
                scrolling = false;
            }
        }
    }

    /** Call from onDestroy(). */
    public void detach() {
        try { stats.setTrackingEnabled(false); } catch (Throwable ignored) {}
    }

    /** Caller holds `lock`. */
    private void flush() {
        if (frames > 0) {
            Log.i(TAG, String.format(Locale.US,
                    "%s scroll: frames=%d jank=%d (%.1f%%) worstUi=%.1fms",
                    screen, frames, jankFrames, 100.0 * jankFrames / frames, worstUiNs / 1e6));
        }
        frames = 0; jankFrames = 0; worstUiNs = 0;
    }
}
