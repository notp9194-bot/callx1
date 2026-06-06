package com.callx.app.chat.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * MessageHighlightAnimator — Scrolls to and highlights the original message.
 *
 * Behavior:
 *   1. Smooth scroll to the target position
 *   2. After scroll settles: flash yellow → transparent (1.5s fade-out)
 *   3. "Back to latest" FAB shown during navigation, hidden after return
 */
public class MessageHighlightAnimator {

    private static final int    HIGHLIGHT_COLOR_START = 0xFFFFEB3B; // Yellow
    private static final int    HIGHLIGHT_COLOR_END   = 0x00FFEB3B; // Transparent
    private static final long   FLASH_DURATION_MS     = 1500L;
    private static final long   SCROLL_SETTLE_DELAY   = 400L;

    private MessageHighlightAnimator() {}

    /**
     * Scroll to position and highlight the item view.
     *
     * @param rv         RecyclerView containing the messages
     * @param position   Adapter position to scroll to
     * @param fabBackBtn "Back to latest" FAB — shown during navigation (nullable)
     */
    public static void scrollAndHighlight(
            @NonNull RecyclerView rv,
            int position,
            @Nullable View fabBackBtn) {

        if (position < 0) return;
        if (rv.getLayoutManager() == null) return;

        // Show FAB
        if (fabBackBtn != null) {
            fabBackBtn.setVisibility(View.VISIBLE);
            fabBackBtn.animate().alpha(1f).setDuration(200).start();
        }

        // Smooth scroll to position
        rv.smoothScrollToPosition(position);

        // After scroll settles, find and highlight the view
        rv.postDelayed(() -> {
            RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
            if (vh != null) {
                flashHighlight(vh.itemView);
            } else {
                // View not yet laid out — try layout manager
                if (rv.getLayoutManager() instanceof LinearLayoutManager) {
                    ((LinearLayoutManager) rv.getLayoutManager())
                            .scrollToPositionWithOffset(position, 0);
                    rv.postDelayed(() -> {
                        RecyclerView.ViewHolder h2 =
                                rv.findViewHolderForAdapterPosition(position);
                        if (h2 != null) flashHighlight(h2.itemView);
                    }, 300);
                }
            }
        }, SCROLL_SETTLE_DELAY);
    }

    /**
     * Flash the background of a view yellow → transparent.
     */
    public static void flashHighlight(@NonNull View view) {
        view.setBackgroundColor(HIGHLIGHT_COLOR_START);
        ValueAnimator animator = ValueAnimator.ofObject(
                new ArgbEvaluator(),
                HIGHLIGHT_COLOR_START,
                HIGHLIGHT_COLOR_END);
        animator.setDuration(FLASH_DURATION_MS);
        animator.addUpdateListener(anim -> {
            int color = (int) anim.getAnimatedValue();
            view.setBackgroundColor(color);
        });
        animator.start();
    }

    /**
     * Hide the back-to-latest FAB with fade-out.
     */
    public static void hideFab(@Nullable View fab) {
        if (fab == null || fab.getVisibility() != View.VISIBLE) return;
        fab.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> fab.setVisibility(View.GONE))
                .start();
    }
}
