package com.callx.app.feed;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView tuned for the Home feed's "water-flow" interaction.
 *
 * The important safety rule here is that velocity is shaped before calling
 * RecyclerView's implementation exactly once. An OnFlingListener must never
 * call fling() again from inside its callback because RecyclerView dispatches
 * that callback from fling() itself.
 */
public final class WaterFlowRecyclerView extends RecyclerView {

    /** A restrained boost adds inertia without turning a short swipe into a jump. */
    private static final float FLOW_MOMENTUM = 1.12f;

    public WaterFlowRecyclerView(@NonNull Context context) {
        super(context);
        configureWaterFlow();
    }

    public WaterFlowRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        configureWaterFlow();
    }

    public WaterFlowRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs,
                                 int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        configureWaterFlow();
    }

    private void configureWaterFlow() {
        // EdgeEffectFactory is the only path that can render the liquid edge
        // wave while preserving RecyclerView's normal scroll/layout pipeline.
        setOverScrollMode(View.OVER_SCROLL_ALWAYS);
        ReelLiquidScrollEffect.applyWaterEdgeEffect(this);

        // Paging slop makes a small deliberate drag feel intentional instead
        // of handing tiny diagonal movements to the vertical feed.
        setScrollingTouchSlop(TOUCH_SLOP_PAGING);
    }

    @Override
    public boolean fling(int velocityX, int velocityY) {
        int min = ViewConfiguration.get(getContext()).getScaledMinimumFlingVelocity();
        if (Math.abs(velocityY) <= min) {
            return super.fling(velocityX, velocityY);
        }

        int max = ViewConfiguration.get(getContext()).getScaledMaximumFlingVelocity();
        long shaped = Math.round(velocityY * FLOW_MOMENTUM);
        int flowVelocityY = (int) Math.max(-max, Math.min(max, shaped));

        // Deliberately call super once. This keeps RecyclerView's native
        // OverScroller, nested scrolling, edge absorption, and recycling.
        return super.fling(velocityX, flowVelocityY);
    }
}