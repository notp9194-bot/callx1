package com.callx.app.feed;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/**
 * Wraps a horizontally scrolling child (the status carousel) that lives inside a horizontal pager
 * (the main ViewPager2 tabs). Port of Google's ViewPager2 "NestedScrollableHost" sample, without the
 * ViewPager2 dependency (feature-status doesn't have it) — the parent pager is assumed horizontal.
 *
 * - Horizontal drag the child can still scroll in that direction: parents are blocked, carousel scrolls.
 * - Child already at the start/end for that direction (or a vertical drag): parents may intercept,
 *   so the tab switches / the list scrolls normally.
 */
public class NestedScrollableHost extends FrameLayout {

    private final int touchSlop;
    private float initialX, initialY;

    public NestedScrollableHost(Context context) { this(context, null); }

    public NestedScrollableHost(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    /** True if any visible child can still scroll horizontally in {@code direction} (-1 = left/start, 1 = right/end). */
    private boolean canChildScroll(int direction) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == VISIBLE && c.canScrollHorizontally(direction)) return true;
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        handleInterceptTouchEvent(e);
        return super.onInterceptTouchEvent(e);
    }

    private void handleInterceptTouchEvent(MotionEvent e) {
        if (getParent() == null) return;
        // Nothing to scroll at all (e.g. 1-2 cards): leave every gesture to the parents.
        if (!canChildScroll(-1) && !canChildScroll(1)) return;

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                initialX = e.getX();
                initialY = e.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE: {
                final float dx = e.getX() - initialX;
                final float dy = e.getY() - initialY;
                // Pager is horizontal: weigh horizontal movement half as much, as in the Google sample.
                final float scaledDx = Math.abs(dx) * 0.5f;
                final float scaledDy = Math.abs(dy);
                if (scaledDx > touchSlop || scaledDy > touchSlop) {
                    if (scaledDy > scaledDx) {
                        // Vertical gesture: let the list scroll.
                        getParent().requestDisallowInterceptTouchEvent(false);
                    } else {
                        // Horizontal: carousel keeps it only while it can still move that way.
                        // Finger moving right (dx > 0) scrolls content towards the start (direction -1).
                        final int direction = dx > 0 ? -1 : 1;
                        getParent().requestDisallowInterceptTouchEvent(canChildScroll(direction));
                    }
                }
                break;
            }
            default: break;
        }
    }
}
