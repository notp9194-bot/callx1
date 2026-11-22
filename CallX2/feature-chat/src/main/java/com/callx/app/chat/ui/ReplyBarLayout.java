package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.chat.R;

/**
 * v814: reply-preview container that merges with the input capsule (WhatsApp look).
 * While VISIBLE it sits flush on top of {@code cv_input_capsule}: its own top corners are
 * rounded (bg_chat_reply_container) and the capsule switches to a bottom-rounded background
 * with no top margin, so both read as ONE rounded container. When hidden the capsule is
 * restored. Doing it from onVisibilityChanged means no call site (1:1, group, reply controller)
 * needs to know about it.
 *
 * v816 polish:
 *  - Smooth show (fade + 12dp rise, 180ms) and hide (fade + 10dp drop, 130ms). The capsule
 *    is un-merged only once the hide animation finished, so there is never a visual gap.
 *    Call sites keep using plain setVisibility(VISIBLE/GONE) — nothing else changes.
 *  - The close button keeps its small 26dp look but gets a 48dp touch target (TouchDelegate).
 */
public class ReplyBarLayout extends FrameLayout {

    private static final long ENTER_MS = 180L;
    private static final long EXIT_MS  = 130L;
    private static final int  MIN_TOUCH_DP = 48;

    private final float density;
    private int capsuleTopMargin = -1;
    private boolean exiting;

    public ReplyBarLayout(@NonNull Context c) { this(c, null, 0); }
    public ReplyBarLayout(@NonNull Context c, @Nullable AttributeSet a) { this(c, a, 0); }
    public ReplyBarLayout(@NonNull Context c, @Nullable AttributeSet a, int s) {
        super(c, a, s);
        density = c.getResources().getDisplayMetrics().density;
    }

    // ── Visibility + animation ─────────────────────────────────────────────

    @Override
    public void setVisibility(int visibility) {
        if (visibility == GONE && getVisibility() == VISIBLE && !exiting && isLaidOut()) {
            exiting = true;
            animate().cancel();
            animate().alpha(0f).translationY(10f * density)
                    .setDuration(EXIT_MS)
                    .setInterpolator(new AccelerateInterpolator())
                    .withEndAction(this::finishHide)
                    .start();
            return; // real GONE happens in finishHide()
        }
        if (visibility == VISIBLE && exiting) {          // re-targeted while hiding
            animate().cancel();
            exiting = false;
            setAlpha(1f);
            setTranslationY(0f);
        }
        super.setVisibility(visibility);
    }

    private void finishHide() {
        if (!exiting) return;
        exiting = false;
        super.setVisibility(GONE);   // -> onVisibilityChanged -> capsule restored
        setAlpha(1f);
        setTranslationY(0f);
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView != this) return;
        mergeCapsule(visibility == VISIBLE);
        if (visibility == VISIBLE) {
            animate().cancel();
            setAlpha(0f);
            setTranslationY(12f * density);
            animate().alpha(1f).translationY(0f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        animate().cancel();
        if (exiting) {
            exiting = false;
            super.setVisibility(GONE);
        }
        setAlpha(1f);
        setTranslationY(0f);
        super.onDetachedFromWindow();
    }

    // ── 48dp touch target for the (visually small) close button ─────────────

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        View close = findViewById(R.id.btn_cancel_reply);
        if (close == null || close.getVisibility() != VISIBLE) return;
        Rect hit = new Rect();
        close.getHitRect(hit);
        int min = (int) (MIN_TOUCH_DP * density);
        int padX = Math.max(0, (min - hit.width()) / 2);
        int padY = Math.max(0, (min - hit.height()) / 2);
        hit.inset(-padX, -padY);
        hit.intersect(0, 0, getWidth(), getHeight());
        setTouchDelegate(new TouchDelegate(hit, close));
    }

    // ── Capsule merge ──────────────────────────────────────────────────────

    private void mergeCapsule(boolean merged) {
        if (!(getParent() instanceof View)) return;
        View capsule = ((View) getParent()).findViewById(R.id.cv_input_capsule);
        if (capsule == null) return;
        ViewGroup.LayoutParams lp = capsule.getLayoutParams();
        if (!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        if (capsuleTopMargin < 0) capsuleTopMargin = mlp.topMargin; // first call = original 6dp
        capsule.setBackgroundResource(merged
                ? R.drawable.bg_chat_input_capsule_reply
                : R.drawable.bg_chat_input_capsule);
        int want = merged ? 0 : capsuleTopMargin;
        if (mlp.topMargin != want) {
            mlp.topMargin = want;
            capsule.setLayoutParams(mlp);
        }
    }
}
