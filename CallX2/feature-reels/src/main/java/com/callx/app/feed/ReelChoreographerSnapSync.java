package com.callx.app.feed;

import android.view.Choreographer;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

/**
 * ReelChoreographerSnapSync — PERF/UX advance: "Choreographer-synced page snap".
 *
 * ReelPageTransformer already applies the Instagram-style scale+fade purely
 * from the position ViewPager2 hands it in {@code onPageScrolled}. That
 * callback normally does line up with vsync, but it's ultimately dispatched
 * off RecyclerView's own scroll/fling step — under load (a big layout pass,
 * a GC, a burst of main-thread work from loadMoreReels()/preloaders firing
 * on the same page-select) a scroll callback can land a frame late, which
 * on a snap-heavy vertical feed like this reads as a visible stutter right
 * as the page is settling into place — the exact moment it's most noticeable.
 *
 * This class doesn't replace ViewPager2's own snapping (that's RecyclerView's
 * PagerSnapHelper, untouched) — it just makes sure the CURRENT and
 * NEIGHBORING pages get an explicit, vsync-driven invalidate/re-transform
 * for every frame between SCROLL_STATE_DRAGGING/SETTLING and back to IDLE,
 * by posting a Choreographer.FrameCallback loop instead of relying solely on
 * however many onPageScrolled calls RecyclerView happens to dispatch during
 * that window. Each tick just re-asks the transformer to re-apply against
 * the page's current translation — cheap (a few view-property sets), and a
 * no-op visually on any frame where nothing actually changed.
 *
 * Usage (ReelsFragment):
 *   snapSync = new ReelChoreographerSnapSync(vpReels, new ReelPageTransformer());
 *   // inside the existing OnPageChangeCallback:
 *   public void onPageScrollStateChanged(int state) { snapSync.onScrollStateChanged(state); }
 */
public final class ReelChoreographerSnapSync {

    private final ViewPager2 pager;
    private final ViewPager2.PageTransformer transformer;
    private final Choreographer choreographer = Choreographer.getInstance();

    private boolean running = false;

    public ReelChoreographerSnapSync(ViewPager2 pager, ViewPager2.PageTransformer transformer) {
        this.pager = pager;
        this.transformer = transformer;
    }

    /** Stops the frame-callback loop immediately — call from onDestroyView. */
    public void stop() {
        running = false;
    }

    public void onScrollStateChanged(int state) {
        boolean shouldRun = state == ViewPager2.SCROLL_STATE_DRAGGING
            || state == ViewPager2.SCROLL_STATE_SETTLING;
        if (shouldRun && !running) {
            running = true;
            choreographer.postFrameCallback(frameCallback);
        } else if (!shouldRun) {
            running = false; // frameCallback below stops re-posting itself
        }
    }

    private final Choreographer.FrameCallback frameCallback = frameTimeNanos -> {
        if (!running) return;
        reapplyTransformOnVisiblePages();
        // Re-post for the next vsync while still dragging/settling.
        choreographer.postFrameCallback(this.frameCallback);
    };

    /**
     * Walks the ViewPager2's inner RecyclerView children (current +
     * immediate neighbors — the only ones visibly moving during a snap) and
     * re-applies the page transform against their live translationX/Y, so
     * the scale+fade tracks the actual frame-accurate scroll position
     * rather than whatever position the last onPageScrolled call reported.
     */
    private void reapplyTransformOnVisiblePages() {
        View recyclerChild = pager.getChildAt(0);
        if (!(recyclerChild instanceof RecyclerView)) return;
        RecyclerView rv = (RecyclerView) recyclerChild;

        int width = pager.getWidth();
        if (width == 0) return;

        for (int i = 0; i < rv.getChildCount(); i++) {
            View page = rv.getChildAt(i);
            float position = page.getLeft() / (float) width;
            // ViewPager2 lays pages out horizontally internally even in a
            // vertical feed configuration; for a vertical orientation the
            // meaningful axis is translationY instead — cover both so this
            // is a drop-in regardless of vpReels' orientation setting.
            if (pager.getOrientation() == ViewPager2.ORIENTATION_VERTICAL) {
                position = page.getTop() / (float) Math.max(1, pager.getHeight());
            }
            transformer.transformPage(page, position);
        }
    }
}
