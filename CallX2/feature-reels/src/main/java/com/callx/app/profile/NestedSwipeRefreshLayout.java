package com.callx.app.profile;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.NestedScrollingParent3;
import androidx.core.view.NestedScrollingParentHelper;
import androidx.core.view.ViewCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import android.view.View;

/**
 * NestedSwipeRefreshLayout — Instagram-level header collapse fix.
 *
 * Problem: SwipeRefreshLayout only implements NestedScrollingParent v1.
 * CoordinatorLayout's AppBarLayout needs NestedScrollingParent v2/v3 to
 * receive TYPE_TOUCH scroll events and collapse the AppBarLayout header.
 *
 * Fix: Extend SwipeRefreshLayout and properly implement NestedScrollingParent3.
 * All nested scroll events are forwarded up to CoordinatorLayout so the
 * AppBarLayout can collapse/expand exactly like Instagram.
 */
public class NestedSwipeRefreshLayout extends SwipeRefreshLayout
        implements NestedScrollingParent3 {

    private final NestedScrollingParentHelper mParentHelper =
            new NestedScrollingParentHelper(this);

    public NestedSwipeRefreshLayout(@NonNull Context context) {
        super(context);
    }

    public NestedSwipeRefreshLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    // ─── NestedScrollingParent3 ──────────────────────────────────────────

    @Override
    public boolean onStartNestedScroll(@NonNull View child, @NonNull View target,
                                       int axes, int type) {
        // Accept vertical scrolling from any child, for both TOUCH and NON_TOUCH types
        return (axes & ViewCompat.SCROLL_AXIS_VERTICAL) != 0;
    }

    @Override
    public void onNestedScrollAccepted(@NonNull View child, @NonNull View target,
                                       int axes, int type) {
        mParentHelper.onNestedScrollAccepted(child, target, axes, type);
        // Start nested scroll upward so CoordinatorLayout (AppBarLayout) can receive events
        startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, type);
    }

    @Override
    public void onStopNestedScroll(@NonNull View target, int type) {
        mParentHelper.onStopNestedScroll(target, type);
        stopNestedScroll(type);
    }

    @Override
    public void onNestedPreScroll(@NonNull View target, int dx, int dy,
                                  @NonNull int[] consumed, int type) {
        // KEY: Forward pre-scroll to CoordinatorLayout BEFORE the child scrolls.
        // This lets AppBarLayout absorb the scroll first (header collapse/expand).
        final int[] parentConsumed = new int[2];
        if (dispatchNestedPreScroll(dx, dy, parentConsumed, null, type)) {
            consumed[0] += parentConsumed[0];
            consumed[1] += parentConsumed[1];
        }
    }

    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed,
                               int dxUnconsumed, int dyUnconsumed, int type,
                               @NonNull int[] consumed) {
        // Forward any unconsumed scroll up so CoordinatorLayout can act on it
        final int[] parentConsumed = new int[2];
        dispatchNestedScroll(dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
                null, type, parentConsumed);
        consumed[0] += parentConsumed[0];
        consumed[1] += parentConsumed[1];
    }

    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed,
                               int dxUnconsumed, int dyUnconsumed, int type) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
                type, new int[]{0, 0});
    }

    // ─── NestedScrollingParent2 (fallback overrides) ─────────────────────

    @Override
    public boolean onStartNestedScroll(@NonNull View child, @NonNull View target, int axes) {
        return onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public void onNestedScrollAccepted(@NonNull View child, @NonNull View target, int axes) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public void onStopNestedScroll(@NonNull View target) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public void onNestedPreScroll(@NonNull View target, int dx, int dy,
                                  @NonNull int[] consumed) {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH);
    }

    @Override
    public void onNestedScroll(@NonNull View target, int dxConsumed, int dyConsumed,
                               int dxUnconsumed, int dyUnconsumed) {
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed,
                ViewCompat.TYPE_TOUCH);
    }

    @Override
    public int getNestedScrollAxes() {
        return mParentHelper.getNestedScrollAxes();
    }
}
