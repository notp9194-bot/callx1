package com.callx.app.chat.ui;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.TouchDelegate;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.chat.R;

/**
 * v817: reply-preview row that lives INSIDE the input capsule ({@link ChatInputBarContainer}),
 * exactly like the voice-recording bar does. Showing it makes the capsule itself grow upward
 * (the capsule's own height-reveal animation plays) with the reply card on top and the normal
 * input row below, so reply + input read as ONE expanded rounded container.
 *
 * <p>History: v814 merged a separate sibling bar with the capsule through background swaps;
 * v816 added fade/rise animation and the 48dp close touch target. v817 removed the merge hack
 * (no more mergeCapsule / bg_chat_input_capsule_reply) — the capsule is always the one pill.
 * Call sites keep using plain setVisibility(VISIBLE/GONE).</p>
 *
 * <ul>
 *  <li>Show: fade + small rise (180ms) while the capsule expands.</li>
 *  <li>Hide: fade + small drop (130ms); real GONE (and capsule shrink) happens after it.</li>
 *  <li>The close button keeps its small 26dp look but has a 48dp touch target.</li>
 * </ul>
 */
public class ReplyBarLayout extends FrameLayout {

    private static final long ENTER_MS = 180L;
    private static final long EXIT_MS  = 130L;
    private static final int  MIN_TOUCH_DP = 48;

    private final float density;
    private boolean exiting;

    // PERF (same idea as the recording bar's cached LayoutRecordingBarBinding): child views are
    // resolved ONCE in onFinishInflate, so bind() and onLayout() never call findViewById again.
    private TextView  tvName;
    private TextView  tvText;
    private ImageView ivType;
    private ImageView ivThumb;
    private ImageButton btnClose;
    private View      llTexts;
    private View      accent;

    // PERF: reused across layout passes (onLayout used to allocate a Rect + TouchDelegate on
    // EVERY pass, including every keyboard / capsule-height relayout while the reply is shown).
    private final Rect hit = new Rect();
    private final Rect lastHit = new Rect();
    private boolean hasDelegate;

    public ReplyBarLayout(@NonNull Context c) { this(c, null, 0); }
    public ReplyBarLayout(@NonNull Context c, @Nullable AttributeSet a) { this(c, a, 0); }
    public ReplyBarLayout(@NonNull Context c, @Nullable AttributeSet a, int s) {
        super(c, a, s);
        density = c.getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        tvName   = findViewById(R.id.tv_reply_bar_name);
        tvText   = findViewById(R.id.tv_reply_bar_text);
        ivType   = findViewById(R.id.iv_reply_bar_type);
        ivThumb  = findViewById(R.id.iv_reply_bar_thumb);
        btnClose = findViewById(R.id.btn_cancel_reply);
        llTexts  = findViewById(R.id.ll_reply_bar_texts);
        accent   = findViewById(R.id.view_reply_accent);
    }

    @Nullable public TextView  nameView()   { return tvName; }
    @Nullable public TextView  textView()   { return tvText; }
    @Nullable public ImageView typeView()   { return ivType; }
    @Nullable public ImageView thumbView()  { return ivThumb; }
    @Nullable public ImageButton closeView(){ return btnClose; }
    @Nullable public View      textsView()  { return llTexts; }
    @Nullable public View      accentView() { return accent; }

    // ── Visibility + animation ─────────────────────────────────────────────

    @Override
    public void setVisibility(int visibility) {
        if (visibility == GONE && getVisibility() == VISIBLE && !exiting && isLaidOut()) {
            exiting = true;
            animate().cancel();
            animate().alpha(0f).translationY(6f * density)
                    .setDuration(EXIT_MS)
                    .setInterpolator(new AccelerateInterpolator())
                    .withLayer()   // PERF: GPU texture only while animating, released after
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
        super.setVisibility(GONE);   // -> container re-measures, capsule shrinks
        setAlpha(1f);
        setTranslationY(0f);
    }

    @Override
    protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView != this) return;
        if (visibility == VISIBLE) {
            animate().cancel();
            setAlpha(0f);
            setTranslationY(8f * density);
            animate().alpha(1f).translationY(0f)
                    .setDuration(ENTER_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .withLayer()   // PERF: GPU texture only while animating, released after
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
        final View close = btnClose;
        if (close == null || close.getVisibility() != VISIBLE) return;
        close.getHitRect(hit);
        int min = (int) (MIN_TOUCH_DP * density);
        int padX = Math.max(0, (min - hit.width()) / 2);
        int padY = Math.max(0, (min - hit.height()) / 2);
        hit.inset(-padX, -padY);
        hit.intersect(0, 0, getWidth(), getHeight());
        // PERF: only rebuild the delegate when the touch rect really moved.
        if (hasDelegate && hit.equals(lastHit)) return;
        lastHit.set(hit);
        hasDelegate = true;
        setTouchDelegate(new TouchDelegate(new Rect(hit), close));
    }
}
