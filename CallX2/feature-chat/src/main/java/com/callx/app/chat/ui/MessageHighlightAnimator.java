package com.callx.app.chat.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
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

    // ─────────────────────────────────────────────────────────────────────
    // FLOATING INDICATOR SHOW/HIDE (scroll-to-latest FAB + "new messages" chip)
    // ─────────────────────────────────────────────────────────────────────
    // WHY: both views used to pop in/out with setVisibility(VISIBLE/GONE).
    //   1. A hard pop looks abrupt (WhatsApp/Telegram fade+scale these).
    //   2. VISIBLE<->GONE makes the parent run a measure+layout pass — exactly the cost
    //      this app avoids elsewhere (see tv_emoji_burst in activity_chat.xml). We now
    //      only ever toggle VISIBLE<->INVISIBLE and animate alpha/scale, which is a pure
    //      draw-time change.
    // The view's TARGET state is stored in a tag so that calling showIndicator() from
    // onScrolled() (every frame) does not restart the animation again and again.
    private static final long INDICATOR_SHOW_MS  = 180L;
    private static final long INDICATOR_HIDE_MS  = 140L; // hide slightly quicker than show: feels snappier
    private static final float INDICATOR_HIDDEN_SCALE = 0.7f;

    /**
     * PERF #1: cheap "is it already shown (or fading in)?" check (2 field reads, no allocation).
     * Lets the per-frame scroll listener skip all its distance maths once the FAB is up.
     */
    public static boolean isShowing(@Nullable View v) {
        return v != null && v.getVisibility() == View.VISIBLE
                && !Boolean.FALSE.equals(v.getTag(R.id.tag_indicator_target_shown));
    }

    /** Fades + scales the view in. Safe to call repeatedly; no-op if already shown/showing. */
    public static void showIndicator(@Nullable View v) {
        if (v == null) return;
        // Already visible AND already heading to "shown" -> nothing to do.
        if (v.getVisibility() == View.VISIBLE
                && Boolean.TRUE.equals(v.getTag(R.id.tag_indicator_target_shown))) {
            return;
        }
        v.setTag(R.id.tag_indicator_target_shown, Boolean.TRUE);
        // Cancel a running hide so its end-action cannot flip us back to INVISIBLE.
        v.animate().cancel();
        if (v.getVisibility() != View.VISIBLE) {
            // Starting from fully hidden: set the "from" frame before making it visible,
            // otherwise a single frame at alpha 1 would flash.
            v.setAlpha(0f);
            v.setScaleX(INDICATOR_HIDDEN_SCALE);
            v.setScaleY(INDICATOR_HIDDEN_SCALE);
            v.setVisibility(View.VISIBLE);
        }
        v.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(INDICATOR_SHOW_MS)
                .setInterpolator(new DecelerateInterpolator())
                // PERF #4: FAB (shadow + icon) and chip (bg + text) have OVERLAPPING content, so
                // an alpha animation would otherwise make the GPU do a saveLayer (offscreen
                // pass) on EVERY frame. withLayer() renders the view ONCE into a hardware layer
                // for the ~180ms animation, then drops it automatically — each frame is then
                // just a cheap texture blit, and the view's display list is not re-recorded.
                .withLayer()
                .start();
    }

    /** Fades + scales the view out, then parks it INVISIBLE (never GONE). No-op if already hidden. */
    public static void hideIndicator(@Nullable View v) {
        if (v == null || v.getVisibility() != View.VISIBLE) return; // already hidden
        if (Boolean.FALSE.equals(v.getTag(R.id.tag_indicator_target_shown))) return; // already fading out
        v.setTag(R.id.tag_indicator_target_shown, Boolean.FALSE);
        v.animate().cancel();
        v.animate()
                .alpha(0f).scaleX(INDICATOR_HIDDEN_SCALE).scaleY(INDICATOR_HIDDEN_SCALE)
                .setDuration(INDICATOR_HIDE_MS)
                .setInterpolator(new AccelerateInterpolator())
                .withLayer() // PERF #4: same single-layer trick for the fade-out
                .withEndAction(() -> {
                    // Only finish the hide if nobody asked to show it again meanwhile.
                    if (!Boolean.TRUE.equals(v.getTag(R.id.tag_indicator_target_shown))) {
                        v.setVisibility(View.INVISIBLE);
                    }
                })
                .start();
    }

    public static void hideFab(@Nullable View fab) {
        if (fab == null || fab.getVisibility() != View.VISIBLE) return;
        fab.setVisibility(View.GONE);
        fab.setAlpha(0f);
    }
}
