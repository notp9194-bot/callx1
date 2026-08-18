package com.callx.app.conversation.info;

import android.content.Context;
import android.util.AttributeSet;

import androidx.recyclerview.widget.RecyclerView;

/**
 * A RecyclerView that clamps its own measured height instead of always
 * resolving to wrap_content's "fit every child" or match_parent's "always
 * full available space".
 *
 * Used by MessageInfoBottomSheet: a 1:1 chat's info is only 3 short rows —
 * without a cap, plain wrap_content would try to measure/lay out however
 * many rows exist (fine here, but the general pattern doesn't scale), and
 * match_parent would make the sheet always full height even for 3 rows.
 * With this, the sheet hugs short content and clamps + scrolls internally
 * once a large group's "Read by" list would otherwise push past the cap.
 */
public class MaxHeightRecyclerView extends RecyclerView {

    private int maxHeightPx = Integer.MAX_VALUE;

    public MaxHeightRecyclerView(Context context) {
        super(context);
    }

    public MaxHeightRecyclerView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MaxHeightRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setMaxHeightPx(int px) {
        if (maxHeightPx == px) return;
        maxHeightPx = px;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.EXACTLY) {
            int capped = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST);
            super.onMeasure(widthSpec, capped);
        } else {
            super.onMeasure(widthSpec, heightSpec);
        }
    }
}
