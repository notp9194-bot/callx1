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
