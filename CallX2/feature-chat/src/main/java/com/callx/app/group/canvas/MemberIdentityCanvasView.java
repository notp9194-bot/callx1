package com.callx.app.group.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * MemberIdentityCanvasView — Canvas replacement for the "name + role badge +
 * status" block in item_group_member.xml (a vertical LinearLayout holding a
 * horizontal LinearLayout(name TextView, badge TextView) plus a status
 * TextView below).
 *
 * WHY THIS EXISTS
 * ─────────────────
 * That block was 3 TextViews across 2 nested LinearLayouts, one of which
 * (the badge) carries its own GradientDrawable background that's
 * inflated/toggled per bind (VISIBLE ↔ GONE as role changes). For a group
 * member list — which can run to hundreds of rows and re-binds on every
 * online-status/last-seen tick — that's 3 measure passes + 2 layout passes
 * + a drawable toggle per row per update.
 *
 * This view draws the name, an optional inline role pill, and the status
 * line in one onDraw(): name text, then (if a role is set) a rounded-rect
 * pill drawn directly with canvas.drawRoundRect() right after it, then the
 * status line below. Layout math (badge position depends on name width) is
 * done once per name/role change, not per draw.
 */
public class MemberIdentityCanvasView extends View {

    private static final float NAME_TEXT_SP = 15f;
    private static final float BADGE_TEXT_SP = 10f;
    private static final float STATUS_TEXT_SP = 12f;
    private static final float BADGE_MARGIN_START_DP = 6f;
    private static final float BADGE_PADDING_H_DP = 6f;
    private static final float BADGE_PADDING_V_DP = 1f;
    private static final float BADGE_CORNER_RADIUS_DP = 8f;
    private static final float ROW_GAP_DP = 2f;

    private static final int NAME_COLOR = 0xFFFFFFFF;   // matches @color/text_primary intent
    private static final int STATUS_COLOR = 0xFF9CA3AF; // matches @color/text_muted intent
    private static final int BADGE_TEXT_COLOR = 0xFF4CAF50; // brand_primary
    private static final int BADGE_BG_COLOR = 0x334CAF50;   // matches bg_unread_badge intent

    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint statusPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF badgeRect = new RectF();

    private final float badgeMarginStartPx, badgePaddingHPx, badgePaddingVPx, badgeCornerRadiusPx, rowGapPx;

    private String nameText = "";
    private String badgeText = null; // null = no badge
    private String statusText = "";

    private float nameWidth = 0f;
    private float badgeTextWidth = 0f;
    private boolean layoutDirty = true;

    public MemberIdentityCanvasView(Context context) { this(context, null); }

    public MemberIdentityCanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        badgeMarginStartPx = BADGE_MARGIN_START_DP * density;
        badgePaddingHPx = BADGE_PADDING_H_DP * density;
        badgePaddingVPx = BADGE_PADDING_V_DP * density;
        badgeCornerRadiusPx = BADGE_CORNER_RADIUS_DP * density;
        rowGapPx = ROW_GAP_DP * density;

        namePaint.setColor(NAME_COLOR);
        namePaint.setTextSize(spToPx(NAME_TEXT_SP));

        badgePaint.setColor(BADGE_TEXT_COLOR);
        badgePaint.setFakeBoldText(true);
        badgePaint.setTextSize(spToPx(BADGE_TEXT_SP));

        statusPaint.setColor(STATUS_COLOR);
        statusPaint.setTextSize(spToPx(STATUS_TEXT_SP));

        badgeBgPaint.setStyle(Paint.Style.FILL);
        badgeBgPaint.setColor(BADGE_BG_COLOR);

        setWillNotDraw(false);
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    /** Sets the display name (e.g. "Ravi (You)"). No-op if unchanged. */
    public void setName(String name) {
        String safe = name != null ? name : "";
        if (safe.equals(nameText)) return;
        nameText = safe;
        layoutDirty = true;
        requestLayout();
        invalidate();
    }

    /** Sets the role badge label ("Admin" / "Creator"), or null/empty to hide it. */
    public void setBadge(@Nullable String badge) {
        String safe = (badge != null && !badge.isEmpty()) ? badge : null;
        if (safe == null ? badgeText == null : safe.equals(badgeText)) return;
        badgeText = safe;
        layoutDirty = true;
        requestLayout();
        invalidate();
    }

    /** Sets the status line ("Online" / "last seen 5 min ago"). No-op if unchanged. */
    public void setStatus(String status) {
        String safe = status != null ? status : "";
        if (safe.equals(statusText)) return;
        statusText = safe;
        invalidate();
    }

    private void rebuildLayoutIfNeeded() {
        if (!layoutDirty) return;
        nameWidth = namePaint.measureText(nameText);
        badgeTextWidth = badgeText != null ? badgePaint.measureText(badgeText) : 0f;
        layoutDirty = false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        rebuildLayoutIfNeeded();
        Paint.FontMetrics nameFm = namePaint.getFontMetrics();
        Paint.FontMetrics statusFm = statusPaint.getFontMetrics();
        float nameLineHeight = nameFm.descent - nameFm.ascent;
        float statusLineHeight = statusFm.descent - statusFm.ascent;
        int desiredHeight = (int) Math.ceil(nameLineHeight + rowGapPx + statusLineHeight);

        float badgeTotalWidth = badgeText != null
                ? badgeMarginStartPx + badgeTextWidth + badgePaddingHPx * 2 : 0f;
        int desiredWidth = (int) Math.ceil(nameWidth + badgeTotalWidth);

        int width = resolveSize(desiredWidth, widthMeasureSpec);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        if (w <= 0) return;
        rebuildLayoutIfNeeded();

        Paint.FontMetrics nameFm = namePaint.getFontMetrics();
        float nameBaseline = -nameFm.ascent;
        String nameToDraw = nameText;
        float maxNameWidth = badgeText != null
                ? Math.max(0f, w - badgeMarginStartPx - badgeTextWidth - badgePaddingHPx * 2)
                : w;
        float drawnNameWidth = nameWidth;
        if (nameWidth > maxNameWidth && maxNameWidth > 0) {
            nameToDraw = TextUtils.ellipsize(nameText, namePaint, maxNameWidth, TextUtils.TruncateAt.END).toString();
            drawnNameWidth = namePaint.measureText(nameToDraw);
        }
        canvas.drawText(nameToDraw, 0, nameBaseline, namePaint);

        if (badgeText != null) {
            float badgeLeft = drawnNameWidth + badgeMarginStartPx;
            float badgeHeight = (badgePaint.getFontMetrics().descent - badgePaint.getFontMetrics().ascent) + badgePaddingVPx * 2;
            badgeRect.set(badgeLeft, 0, badgeLeft + badgeTextWidth + badgePaddingHPx * 2, badgeHeight);
            canvas.drawRoundRect(badgeRect, badgeCornerRadiusPx, badgeCornerRadiusPx, badgeBgPaint);
            Paint.FontMetrics badgeFm = badgePaint.getFontMetrics();
            float badgeTextY = badgeRect.centerY() - (badgeFm.ascent + badgeFm.descent) / 2f;
            canvas.drawText(badgeText, badgeLeft + badgePaddingHPx, badgeTextY, badgePaint);
        }

        float statusY = (nameFm.descent - nameFm.ascent) + rowGapPx - statusPaint.getFontMetrics().ascent;
        String statusToDraw = statusText;
        if (statusPaint.measureText(statusText) > w) {
            statusToDraw = TextUtils.ellipsize(statusText, statusPaint, w, TextUtils.TruncateAt.END).toString();
        }
        canvas.drawText(statusToDraw, 0, statusY, statusPaint);
    }
}
