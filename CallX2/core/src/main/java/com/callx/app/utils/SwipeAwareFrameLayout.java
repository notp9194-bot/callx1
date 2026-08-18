package com.callx.app.utils;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * SWIPE-AWARE ROOT — shared touch-hook for MediaSwipeReplyCloseHelper
 * ──────────────────────────────────────────────────────────────────────
 * MediaSwipeReplyCloseHelper needs to see every MotionEvent BEFORE any
 * child (button, ImageView, etc.) gets a chance to consume it — that's
 * how MediaViewerActivity wires it (Activity#dispatchTouchEvent) and how
 * ReelPeekPreviewController wires it (PopupWindow#setTouchInterceptor).
 *
 * Neither hook exists for a view that's just sitting inline inside a
 * normal layout (e.g. a Fragment's bottom mini-player bar) — there's no
 * Activity/Popup boundary to intercept at. The only equivalent is
 * overriding dispatchTouchEvent() on the ViewGroup itself, which requires
 * a real subclass (a plain View.OnTouchListener set on a ViewGroup is
 * NOT called first — children still see ACTION_DOWN before it does).
 *
 * This was previously a private inner class duplicated inside
 * DialogFullscreenHelper (SwipeAwareRoot). Pulled out here as one shared,
 * inflatable (has the AttributeSet constructor) class so any XML layout
 * can just declare this as a view's root tag and wire a swipe helper to
 * it via setSwipeHelper() — no per-screen copy of this forwarding logic.
 *
 * Usage (XML): replace the container's tag, e.g.
 *   <FrameLayout android:id="@+id/layout_mini_player" .../>
 * becomes
 *   <com.callx.app.utils.SwipeAwareFrameLayout android:id="@+id/layout_mini_player" .../>
 *
 * Usage (code):
 *   SwipeAwareFrameLayout root = view.findViewById(R.id.layout_mini_player);
 *   root.setSwipeHelper(new MediaSwipeReplyCloseHelper(context, root, ...));
 */
public class SwipeAwareFrameLayout extends FrameLayout {

    private MediaSwipeReplyCloseHelper swipeHelper;

    public SwipeAwareFrameLayout(Context context) {
        super(context);
    }

    public SwipeAwareFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SwipeAwareFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** Pass null to detach (e.g. once the owning Fragment's view is torn down). */
    public void setSwipeHelper(MediaSwipeReplyCloseHelper swipeHelper) {
        this.swipeHelper = swipeHelper;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeHelper != null && swipeHelper.onTouch(ev)) return true;
        return super.dispatchTouchEvent(ev);
    }
}
