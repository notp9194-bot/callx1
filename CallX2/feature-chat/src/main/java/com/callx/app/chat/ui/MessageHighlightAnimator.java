package com.callx.app.chat.ui;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * MessageHighlightAnimator — NO animation version for performance.
 * Scrolls instantly to position. No color flash.
 */
public class MessageHighlightAnimator {

    private static final long SCROLL_SETTLE_DELAY = 100L; // reduced from 400ms

    private MessageHighlightAnimator() {}

    public static void scrollAndHighlight(
            @NonNull RecyclerView rv,
            int position,
            @Nullable View fabBackBtn) {

        if (position < 0) return;
        if (rv.getLayoutManager() == null) return;

        if (fabBackBtn != null) {
            fabBackBtn.setVisibility(View.VISIBLE);
            fabBackBtn.setAlpha(1f);
        }

        if (rv.getLayoutManager() instanceof LinearLayoutManager) {
            ((LinearLayoutManager) rv.getLayoutManager()).scrollToPositionWithOffset(position, 0);
        } else {
            rv.scrollToPosition(position);
        }
    }

    /** No-op: flash removed for performance. */
    public static void flashHighlight(@NonNull View view) {
        // No animation
    }

    public static void hideFab(@Nullable View fab) {
        if (fab == null || fab.getVisibility() != View.VISIBLE) return;
        fab.setVisibility(View.GONE);
        fab.setAlpha(0f);
    }
}
