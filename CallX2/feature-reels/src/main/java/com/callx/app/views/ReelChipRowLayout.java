package com.callx.app.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

/**
 * LinearLayout used by the reel metadata chip row.
 *
 * Chip views are pooled across reel fragment views. ViewPager2 tears down
 * offscreen fragment views, so keeping a small application-context pool avoids
 * allocating another TextView/LayoutParams pair for every swipe while also
 * avoiding an Activity leak across configuration changes.
 *
 * During a bind several child TextViews can change their text and visibility
 * together. Coalescing requestLayout() calls here keeps the bind path to one
 * parent invalidation without changing the row's normal layout behavior.
 */
public final class ReelChipRowLayout extends LinearLayout {

    private static final int MAX_POOLED_CHIPS = 32;
    private static final ArrayDeque<TextView> CHIP_POOL = new ArrayDeque<>(MAX_POOLED_CHIPS);

    private int batchDepth;
    private boolean layoutRequestedDuringBatch;

    public ReelChipRowLayout(Context context) {
        super(context);
    }

    public ReelChipRowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ReelChipRowLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void beginBatchUpdate() {
        batchDepth++;
    }

    public void endBatchUpdate() {
        if (batchDepth == 0) return;
        batchDepth--;
        if (batchDepth == 0 && layoutRequestedDuringBatch) {
            layoutRequestedDuringBatch = false;
            super.requestLayout();
        }
    }

    /**
     * Returns a detached chip with its stable LayoutParams already installed,
     * then attaches it to this row. The application context is intentional:
     * pooled views must not retain a destroyed Activity.
     */
    public TextView obtainChip() {
        TextView chip = CHIP_POOL.pollFirst();
        if (chip == null) {
            chip = new TextView(getContext().getApplicationContext());
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(12f);
            chip.setAlpha(0.85f);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setLayoutParams(new LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }
        addView(chip);
        return chip;
    }

    /**
     * Detaches all chips and returns them to the bounded pool. This is called
     * when a fragment view is released, not during a visible-frame bind.
     */
    public void recycleChildren() {
        beginBatchUpdate();
        try {
            while (getChildCount() > 0) {
                View child = getChildAt(getChildCount() - 1);
                removeViewAt(getChildCount() - 1);
                if (child instanceof TextView && CHIP_POOL.size() < MAX_POOLED_CHIPS) {
                    TextView chip = (TextView) child;
                    chip.setOnClickListener(null);
                    chip.setTag(null);
                    chip.setText(null);
                    chip.setVisibility(GONE);
                    CHIP_POOL.addLast(chip);
                }
            }
        } finally {
            endBatchUpdate();
        }
    }

    @Override
    public void requestLayout() {
        if (batchDepth > 0) {
            layoutRequestedDuringBatch = true;
            return;
        }
        super.requestLayout();
    }
}