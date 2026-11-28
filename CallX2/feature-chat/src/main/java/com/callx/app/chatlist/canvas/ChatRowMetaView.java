package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Bitmap;
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

    private final float density;
    private final float badgeMinPx, badgeRadiusPx, badgePadHPx, badgePadVPx;
    private final float callsWPx, callsHPx, callsMarginPx;

    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final float textHalfH;          // (descent - ascent) / 2, cached — never getFontMetrics() in draw/measure
    private final float textAscentDescentMid;

    // v948: shared pre-rasterized icons (ChatRowIconCache) — drawBitmap, no per-VH VectorDrawable.
    private final Bitmap videoBmp, phoneBmp;
    private final int iconPx;
    private float videoX, phoneX, iconY;
    /** v948: constant height = avatar box, so a badge / call-strip change never needs requestLayout (row height is fixed). */
    private final int fixedH;
    private float badgeHMax;
    private int curW = 0;

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

        iconPx = Math.round(ICON_DP * density);
        videoBmp = ChatRowIconCache.get(ctx, R.drawable.ic_video_call, iconPx, COLOR_VIDEO);
        phoneBmp = ChatRowIconCache.get(ctx, R.drawable.ic_phone, iconPx, COLOR_VOICE);
        fixedH = Math.round(58f * density);
        badgeHMax = Math.max(badgeMinPx, textHalfH * 2f + badgePadVPx * 2f);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API
    // ═════════════════════════════════════════════════════════════════════════

    /** Unread count; 0 hides the pill. No-op when unchanged. */
    public void setBadgeCount(long c) {
        if (c == count) return;
        count = c;
        if (c <= 0) {
            label = "";
            badgeW = badgeH = 0f;
        } else {
            label = c > 99 ? "99+" : String.valueOf(c);
            badgeW = Math.max(badgeMinPx, textPaint.measureText(label) + badgePadHPx * 2f);
            badgeH = badgeHMax;
        }
        // v948: height is constant (fixedH) — only a WIDTH change (group rows: pill width / appears) needs a layout.
        updateWidth();
        relayoutCoords();
        invalidate();
    }

    /** requestLayout only when the measured width really changes. */
    private void updateWidth() {
        final int w = (int) Math.ceil(contentW());
        if (w != curW) {
            curW = w;
            requestLayout();
        }
    }

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
        updateWidth();      // v948: was an unconditional requestLayout
        relayoutCoords();
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
        curW = (int) Math.ceil(contentW());
        setMeasuredDimension(resolveSize(curW, widthMeasureSpec), resolveSize(fixedH, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        relayoutCoords();
    }

    private void relayoutCoords() {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // v948: block (badge + call strip) is vertically centred INSIDE this fixed-height view, which reproduces
        // the old wrap_content + gravity=center_vertical placement exactly (y0 may be slightly negative; the row
        // sets clipChildren=false).
        final float y0 = (h - contentH()) / 2f;

        // badge: top-right
        if (badgeW > 0f) {
            pill.set(w - badgeW, y0, w, y0 + badgeH);
            badgeTextX = pill.centerX();
            badgeTextY = y0 + badgeH / 2f - textAscentDescentMid;
        }

        // calls: bottom strip, right-aligned
        callsTop = y0 + badgeH + callsMarginPx;
        final float left = w - callsWPx;
        final float cy = callsTop + callsHPx / 2f;
        final float btnW = BTN_W_DP * density;
        final float gap = BTN_GAP_DP * density;

        videoX = Math.round(left + btnW / 2f - iconPx / 2f);
        phoneX = Math.round(left + btnW + gap + btnW / 2f - iconPx / 2f);
        iconY  = Math.round(cy - iconPx / 2f);
        midX = left + btnW + gap / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (badgeW > 0f && !label.isEmpty()) {
            canvas.drawRoundRect(pill, badgeRadiusPx, badgeRadiusPx, badgePaint);
            canvas.drawText(label, badgeTextX, badgeTextY, textPaint);
        }
        if (callsVisible) {
            if (videoBmp != null) canvas.drawBitmap(videoBmp, videoX, iconY, null);
            if (phoneBmp != null) canvas.drawBitmap(phoneBmp, phoneX, iconY, null);
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
