package com.callx.app.feed;

import android.os.Looper;
import android.os.MessageQueue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * HomeFeedCardPool — idle-time layout pre-inflation for the Home feed.
 *
 * Inflating {@code item_home_feed_post} is one of the most expensive single
 * operations the feed performs (deep FrameLayout + PlayerView + overlay +
 * action row, ~40 views), and it used to happen on the exact frame the card
 * was needed: {@code renderOneFeedItem()} spreads cards one-per-16 ms, but
 * each of those frames still pays the full inflate + bind cost, which is what
 * makes the first flings after opening Home (and every infinite-scroll page
 * append) drop frames.
 *
 * This moves the inflate off the critical frame entirely by doing it while
 * the main thread is *idle* — an {@link MessageQueue.IdleHandler} only runs
 * when there is nothing else queued, so pre-inflation never competes with a
 * scroll or an animation. When the feed then asks for a card it usually gets
 * a ready-made view and only pays for binding.
 *
 * AsyncLayoutInflater was deliberately not used: these cards contain a
 * {@code PlayerView}, whose construction touches view-system state that is
 * not safe to build off the main thread.
 *
 * The pool is bounded ({@link #MAX_POOLED}) so it can never become a
 * retained-view leak of its own, and it hands back {@code null} rather than
 * blocking when empty — callers fall back to a normal inflate.
 */
public final class HomeFeedCardPool {

    /** Ready-to-bind cards kept warm. Roughly one screen's worth. */
    private static final int MAX_POOLED = 4;

    private final LayoutInflater inflater;
    @LayoutRes private final int layoutRes;
    private final ViewGroup parentForParams;
    private final Deque<View> pool = new ArrayDeque<>(MAX_POOLED);

    private boolean idleHandlerInstalled = false;
    private boolean released = false;

    private final MessageQueue.IdleHandler idleHandler = new MessageQueue.IdleHandler() {
        @Override public boolean queueIdle() {
            if (released) { idleHandlerInstalled = false; return false; }
            if (pool.size() < MAX_POOLED) {
                pool.addLast(inflate());
            }
            // Keep the handler registered only while there is still refilling
            // to do — an always-registered idle handler wakes on every idle.
            boolean keep = pool.size() < MAX_POOLED;
            if (!keep) idleHandlerInstalled = false;
            return keep;
        }
    };

    public HomeFeedCardPool(@NonNull LayoutInflater inflater,
                            @LayoutRes int layoutRes,
                            @NonNull ViewGroup parentForParams) {
        this.inflater        = inflater;
        this.layoutRes       = layoutRes;
        this.parentForParams = parentForParams;
        scheduleRefill();
    }

    /**
     * A pre-inflated card if one is warm, otherwise a freshly inflated one.
     * Never returns a view that is attached to a parent.
     */
    @NonNull
    public View obtain() {
        View v = pool.pollFirst();
        scheduleRefill();
        return v != null ? v : inflate();
    }

    /** Drops warm views and stops refilling (call from onDestroyView). */
    public void release() {
        released = true;
        pool.clear();
        if (idleHandlerInstalled) {
            Looper.myQueue().removeIdleHandler(idleHandler);
            idleHandlerInstalled = false;
        }
    }

    private void scheduleRefill() {
        if (released || idleHandlerInstalled || pool.size() >= MAX_POOLED) return;
        idleHandlerInstalled = true;
        Looper.myQueue().addIdleHandler(idleHandler);
    }

    /** attachToRoot=false so the view carries the container's LayoutParams. */
    @NonNull
    private View inflate() {
        return inflater.inflate(layoutRes, parentForParams, false);
    }

    @Nullable
    public static HomeFeedCardPool createOrNull(@Nullable LayoutInflater inflater,
                                                @LayoutRes int layoutRes,
                                                @Nullable ViewGroup parentForParams) {
        if (inflater == null || parentForParams == null) return null;
        return new HomeFeedCardPool(inflater, layoutRes, parentForParams);
    }
}
