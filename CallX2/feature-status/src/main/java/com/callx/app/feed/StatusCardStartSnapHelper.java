package com.callx.app.feed;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Status carousel snap: after a scroll/fling the first card settles flush with the start padding
 * (cards are never left half-cut at the left edge). No snapping once the end of the list is reached,
 * so the last card is not pulled back.
 */
public final class StatusCardStartSnapHelper extends LinearSnapHelper {

    private OrientationHelper helper;

    private OrientationHelper helper(RecyclerView.LayoutManager lm) {
        if (helper == null || helper.getLayoutManager() != lm) helper = OrientationHelper.createHorizontalHelper(lm);
        return helper;
    }

    @Override
    public int[] calculateDistanceToFinalSnap(@NonNull RecyclerView.LayoutManager lm, @NonNull View target) {
        int[] out = new int[2];
        if (lm.canScrollHorizontally()) {
            OrientationHelper oh = helper(lm);
            out[0] = oh.getDecoratedStart(target) - oh.getStartAfterPadding();
        }
        return out;
    }

    /**
     * Fling target, start-aligned like {@link #findSnapView}: a forward fling lands on the card after the
     * first visible one (+1 more card per ~5000 px/s, max 2 extra); a backward fling lands on the cut-off
     * first card, or the previous one if it is already flush. Without this, LinearSnapHelper's centre-based
     * target made fast flings settle 1-2 cards off. NO_POSITION → plain fling + idle snap (end of list).
     */
    @Override
    public int findTargetSnapPosition(RecyclerView.LayoutManager lm, int velocityX, int velocityY) {
        if (!(lm instanceof LinearLayoutManager)) return super.findTargetSnapPosition(lm, velocityX, velocityY);
        LinearLayoutManager llm = (LinearLayoutManager) lm;
        final int count = llm.getItemCount();
        if (count == 0) return RecyclerView.NO_POSITION;
        final int first = llm.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) return RecyclerView.NO_POSITION;
        final int extra = Math.min(2, Math.abs(velocityX) / 5000);
        if (velocityX > 0) {
            if (llm.findLastCompletelyVisibleItemPosition() == count - 1) return RecyclerView.NO_POSITION; // end: don't pull back
            return Math.min(count - 1, first + 1 + extra);
        }
        View fv = llm.findViewByPosition(first);
        OrientationHelper oh = helper(lm);
        boolean flush = fv == null || oh.getDecoratedStart(fv) >= oh.getStartAfterPadding();
        return Math.max(0, (flush ? first - 1 : first) - extra);
    }

    @Nullable
    @Override
    public View findSnapView(RecyclerView.LayoutManager lm) {
        if (!(lm instanceof LinearLayoutManager)) return super.findSnapView(lm);
        LinearLayoutManager llm = (LinearLayoutManager) lm;
        int first = llm.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) return null;
        if (llm.findLastCompletelyVisibleItemPosition() == llm.getItemCount() - 1) return null;
        View firstView = llm.findViewByPosition(first);
        if (firstView == null) return null;
        OrientationHelper oh = helper(lm);
        // Keep the first card unless more than half of it has scrolled out, then move on to the next.
        if (oh.getDecoratedEnd(firstView) >= oh.getDecoratedMeasurement(firstView) / 2) return firstView;
        View next = llm.findViewByPosition(first + 1);
        return next != null ? next : firstView;
    }
}
