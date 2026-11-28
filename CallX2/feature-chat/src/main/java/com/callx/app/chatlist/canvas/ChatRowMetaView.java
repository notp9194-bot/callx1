package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.callx.app.chat.R;

/**
 * ChatRowMetaView — v947. Replaces, per chat-list row, the right-hand meta column:
 *   LinearLayout(ll_meta_normal) + ChatListUnreadBadgeView + ChatListCallButtonsView   (3 views -> 1).
 *
 * Geometry is byte-for-byte what the old vertical LinearLayout produced (so ChatListTextPrecompute stays valid):
 *   • width  = max(badgeW, callsW)            badgeW = max(20dp, textW + 12dp) when count > 0 else 0
 *                                             callsW = 34*2+6 = 74dp when calls are visible else 0
 *   • height = badgeH + [6dp margin + 34dp]   (the 6dp top margin of the call row was counted even when the badge
 *                                             had 0 height — kept, so rows don't shift by a pixel)
 *   • badge is right-aligned (gravity=end) at the top; the call icons fill the bottom 34dp.
 *
 * Draw: one roundRect + one text for the badge, two vector icons for the calls — zero allocation, pre-baked coords.
 * Touch: only the call-button strip is interactive (video = left half, voice = right half). Everything else
 * returns false on ACTION_DOWN so the row's own click / long-click still fires.
 *
 * Every setter is a no-op (no invalidate / requestLayout) when its value did not change; requestLayout is only
 * issued when the measured size actually changes (badge 0<->n, digit count change, calls shown/hidden).
 */
public class ChatRowMetaView extends View {

    private static final float TEXT_SIZE_SP = 11f;
    private static final float BADGE_MIN_DP = 20f;
    private static final float BADGE_RADIUS_DP = 10f;
    private static final float BADGE_PAD_H_DP = 6f;
    private static final float BADGE_PAD_V_DP = 2f;

    private static final float BTN_W_DP = 34f;
    private static final float BTN_GAP_DP = 6f;
    private static final float BTN_H_DP = 34f;
    private static final float CALLS_TOP_MARGIN_DP = 6f;
    private static final float ICON_DP = 16f;

    private static final int COLOR_VIDEO = 0xFF5B5BF6;
    private static final int COLOR_VOICE = 0xFF22C55E;
    private static final int COLOR_BADGE = 0xFF4CAF50;
    private static final int COLOR_BADGE_MUTED = 0xFF9AA5B1;

    // Constant states shared by every row's icon copies (loaded once per process, not once per inflated row).
    private static Drawable.ConstantState sVideoCs, sPhoneCs;

    private final float density;
    private final float badgeMinPx, badgeRadiusPx, badgePadHPx, badgePadVPx;
    private final float callsWPx, callsHPx, callsMarginPx;

    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final float textHalfH;          // (descent - ascent) / 2, cached — never getFontMetrics() in draw/measure
    private final float textAscentDescentMid;

    private final Drawable videoIcon, phoneIcon;

    // ── state ───────────────────────────────────────────────────────────────
    private long count = 0;
    private String label = "";
    private float badgeW, badgeH;           // 0 when hidden
    private boolean muted = false;
    private boolean callsVisible = false;

    // laid-out coords (recomputed in onSizeChanged / when state changes after layout)
    private float badgeTextX, badgeTextY;
    private float callsTop, midX;

    private Runnable onVoice, onVideo;
    private boolean touchDown;
    private boolean touchOnCalls;

    public ChatRowMetaView(Context ctx) { this(ctx, null); }

    public ChatRowMetaView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
        final float sp = ctx.getResources().getDisplayMetrics().scaledDensity;

        badgeMinPx = BADGE_MIN_DP * density;
        badgeRadiusPx = BADGE_RADIUS_DP * density;
        badgePadHPx = BADGE_PAD_H_DP * density;
        badgePadVPx = BADGE_PAD_V_DP * density;
        callsWPx = (BTN_W_DP * 2 + BTN_GAP_DP) * density;
        callsHPx = BTN_H_DP * density;
        callsMarginPx = CALLS_TOP_MARGIN_DP * density;

        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(COLOR_BADGE);

        textPaint.setTextSize(TEXT_SIZE_SP * sp);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        final Paint.FontMetrics fm = textPaint.getFontMetrics();
        textHalfH = (fm.descent - fm.ascent) / 2f;
        textAscentDescentMid = (fm.ascent + fm.descent) / 2f;

        videoIcon = tinted(ctx, R.drawable.ic_video_call, COLOR_VIDEO, true);
        phoneIcon = tinted(ctx, R.drawable.ic_phone, COLOR_VOICE, false);
    }

    private static synchronized Drawable tinted(Context ctx, int res, int color, boolean video) {
        Drawable d = null;
        Drawable.ConstantState cs = video ? sVideoCs : sPhoneCs;
        if (cs != null) {
            d = cs.newDrawable(ctx.getResources());
        } else {
            final Drawable base = ContextCompat.getDrawable(ctx, res);
            if (base != null) {
                final Drawable.ConstantState got = base.getConstantState();
                if (got != null) {
                    if (video) sVideoCs = got; else sPhoneCs = got;
                    d = got.newDrawable(ctx.getResources());
                } else {
                    d = base;
                }
            }
        }
        if (d == null) return null;
        d = d.mutate();
        DrawableCompat.setTint(d, color);
        return d;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API
    // ═════════════════════════════════════════════════════════════════════════

    /** Unread count; 0 hides the pill. No-op when unchanged. */
    public void setBadgeCount(long c) {
        if (c == count) return;
        final boolean wasVisible = count > 0;
        final int prevLen = label.length();
        count = c;
        if (c <= 0) {
            label = "";
            badgeW = badgeH = 0f;
        } else {
            label = c > 99 ? "99+" : String.valueOf(c);
            badgeW = Math.max(badgeMinPx, textPaint.measureText(label) + badgePadHPx * 2f);
            badgeH = Math.max(badgeMinPx, textHalfH * 2f + badgePadVPx * 2f);
        }
        final boolean nowVisible = c > 0;
        if (wasVisible != nowVisible || label.length() != prevLen) {
            requestLayout();      // size changed
        }
        relayoutCoords();
        invalidate();
    }

    /** Dimmed grey pill for muted chats (same size). */
    public void setMutedStyle(boolean m) {
        if (m == muted) return;
        muted = m;
        badgePaint.setColor(m ? COLOR_BADGE_MUTED : COLOR_BADGE);
        if (count > 0) invalidate();
    }

    /** Shows/hides the voice+video strip (hidden for group rows and in selection mode). */
    public void setCallsVisible(boolean v) {
        if (v == callsVisible) return;
        callsVisible = v;
        requestLayout();
        invalidate();
    }

    public void setCallListeners(@Nullable Runnable voice, @Nullable Runnable video) {
        onVoice = voice;
        onVideo = video;
    }

    public boolean hasBadge() { return count > 0; }

    // ═════════════════════════════════════════════════════════════════════════
    // Measure / layout / draw
    // ═════════════════════════════════════════════════════════════════════════

    private float contentW() { return Math.max(badgeW, callsVisible ? callsWPx : 0f); }

    private float contentH() { return badgeH + (callsVisible ? callsMarginPx + callsHPx : 0f); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = (int) Math.ceil(contentW());
        final int h = (int) Math.ceil(contentH());
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayoutCoords();
    }

    /** Pre-bakes every draw coordinate; called on size change and when badge/calls state changes. */
    private void relayoutCoords() {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // badge: top-right
        if (badgeW > 0f) {
            pill.set(w - badgeW, 0f, w, badgeH);
            badgeTextX = pill.centerX();
            badgeTextY = badgeH / 2f - textAscentDescentMid;
        }

        // calls: bottom strip, right-aligned (== full width, since callsW is the max width when visible)
        callsTop = badgeH + callsMarginPx;
        final float left = w - callsWPx;
        final float cy = callsTop + callsHPx / 2f;
        final float iconD = ICON_DP * density;
        final float btnW = BTN_W_DP * density;
        final float gap = BTN_GAP_DP * density;

        if (videoIcon != null) {
            final int l = Math.round(left + btnW / 2f - iconD / 2f);
            final int t = Math.round(cy - iconD / 2f);
            videoIcon.setBounds(l, t, Math.round(l + iconD), Math.round(t + iconD));
        }
        if (phoneIcon != null) {
            final int l = Math.round(left + btnW + gap + btnW / 2f - iconD / 2f);
            final int t = Math.round(cy - iconD / 2f);
            phoneIcon.setBounds(l, t, Math.round(l + iconD), Math.round(t + iconD));
        }
        midX = left + btnW + gap / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (badgeW > 0f && !label.isEmpty()) {
            canvas.drawRoundRect(pill, badgeRadiusPx, badgeRadiusPx, badgePaint);
            canvas.drawText(label, badgeTextX, badgeTextY, textPaint);
        }
        if (callsVisible) {
            if (videoIcon != null) videoIcon.draw(canvas);
            if (phoneIcon != null) phoneIcon.draw(canvas);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Touch — only the call strip is interactive
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final float y = e.getY();
                touchOnCalls = callsVisible && y >= callsTop && y <= callsTop + callsHPx;
                if (!touchOnCalls) return false;          // let the row handle click / long-click
                touchDown = true;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                touchDown = false;
                touchOnCalls = false;
                return true;
            case MotionEvent.ACTION_UP:
                if (touchDown) {
                    touchDown = false;
                    touchOnCalls = false;
                    performClick();
                    final boolean video = e.getX() < midX;
                    if (video) { if (onVideo != null) onVideo.run(); }
                    else if (onVoice != null) onVoice.run();
                }
                return true;
            default:
                return touchOnCalls;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
