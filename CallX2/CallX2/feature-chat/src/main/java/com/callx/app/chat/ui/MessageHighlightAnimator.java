package com.callx.app.chat.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;

/**
 * MessageHighlightAnimator — scrolls to a jumped-to message and flashes it so
 * the user can actually tell "this is the original message" (WhatsApp/
 * Telegram-style reply-jump pulse).
 *
 * flashHighlight() used to be an intentional no-op ("flash removed for
 * performance") because it was previously wired to fire from hot rebind
 * paths. It's only ever called once per user-initiated jump (reply tap,
 * search result, "jump to their position" banner) — a single short
 * ValueAnimator on one View has no measurable cost there, so the highlight
 * is restored.
 */
public class MessageHighlightAnimator {

    private static final long SCROLL_SETTLE_DELAY = 100L; // reduced from 400ms

    // Warm amber pulse — reads clearly against both sent (brand green) and
    // received (neutral) bubble backgrounds, and against dark/AMOLED theme.
    private static final int FLASH_COLOR = 0xFFFFC107;
    private static final int FLASH_MAX_ALPHA = 130; // out of 255 — translucent, doesn't hide bubble content
    private static final long FLASH_DURATION_MS = 900L;

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

    /**
     * Flashes the jumped-to row so it's unmistakable which message the user
     * landed on. Targets ll_bubble (the actual message bubble container)
     * when present so the pulse hugs the bubble shape instead of the whole
     * row's padding; falls back to the row itself for bubble types that
     * don't expose that id (call-entry rows, etc).
     */
    public static void flashHighlight(@NonNull View rowView) {
        View target = rowView.findViewById(R.id.ll_bubble);
        if (target == null) target = rowView;
        final View flashTarget = target;

        final ColorDrawable overlay = new ColorDrawable(FLASH_COLOR);
        overlay.setAlpha(0);
        // Additive overlay via foreground — never touches the bubble's own
        // background drawable (theme-swapped, sent/received/has-reply shape),
        // same technique already used for the "someone is replying to this"
        // glow (see MessagePagingAdapter's bg_reply_target_highlight usage).
        flashTarget.setForeground(overlay);

        // Two quick pulses (up, down, up, down) reads as a deliberate
        // "here it is" flash rather than a single fade a user might miss.
        ValueAnimator anim = ValueAnimator.ofInt(0, FLASH_MAX_ALPHA, 0, FLASH_MAX_ALPHA, 0);
        anim.setDuration(FLASH_DURATION_MS);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> overlay.setAlpha((int) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                // Only clear if nothing newer (a second rapid jump) replaced it.
                if (flashTarget.getForeground() == overlay) flashTarget.setForeground(null);
            }
        });
        anim.start();
    }

    public static void hideFab(@Nullable View fab) {
        if (fab == null || fab.getVisibility() != View.VISIBLE) return;
        fab.setVisibility(View.GONE);
        fab.setAlpha(0f);
    }
}
