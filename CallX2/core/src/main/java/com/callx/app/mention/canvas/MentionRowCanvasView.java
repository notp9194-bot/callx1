package com.callx.app.mention.canvas;

import android.content.Context;
import android.graphics.Bitmap;
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

import com.callx.app.cache.MiscAvatarBinder;

/**
 * MentionRowCanvasView — Canvas replacement for item_mention_suggest.xml
 * (a LinearLayout hosting a circular avatar ImageView, a name TextView, and
 * a static "@" hint TextView).
 *
 * WHY THIS EXISTS
 * ─────────────────
 * The @mention dropdown calls notifyDataSetChanged() on every keystroke
 * (filter() re-runs contains-match over the member list) — so every row in
 * the visible dropdown gets a full re-measure + re-layout + re-draw of 3
 * child Views on every character typed, not just a text update. Collapsing
 * avatar + name + "@" badge into one View with a single onDraw() removes
 * that per-keystroke layout-pass multiplication: one measure, one layout,
 * one draw call per row regardless of how many characters were typed.
 *
 * Avatar is decoded once via Glide.asBitmap().transform(CircleCrop()) (same
 * circleCrop the old ImageView used) and cached until the URL changes, same
 * pattern as TypingStripCanvasView's avatar loader.
 */
public class MentionRowCanvasView extends View {

    private static final float AVATAR_SIZE_DP = 32f;
    private static final float PADDING_H_DP = 14f;
    private static final float NAME_MARGIN_START_DP = 12f;
    private static final float BADGE_MARGIN_START_DP = 8f;
    private static final float NAME_TEXT_SP = 13f;
    private static final float BADGE_TEXT_SP = 13f;

    private static final int PLACEHOLDER_BG = 0x33FFFFFF;
    private static final int NAME_COLOR = 0xFFFFFFFF; // matches @color/text_primary intent
    private static final int BADGE_COLOR = 0x994CAF50; // brand_primary @ ~60% alpha, matches old alpha=0.6

    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF avatarRect = new RectF();

    private final float avatarSizePx, paddingHPx, nameMarginStartPx, badgeMarginStartPx;

    private Bitmap avatarBitmap;
    private String pendingAvatarUrl;

    private String nameText = "";
    private CharSequence nameEllipsized = "";
    private static final String BADGE_TEXT = "@";
    private float badgeWidth;

    public MentionRowCanvasView(Context context) { this(context, null); }

    public MentionRowCanvasView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        avatarSizePx = AVATAR_SIZE_DP * density;
        paddingHPx = PADDING_H_DP * density;
        nameMarginStartPx = NAME_MARGIN_START_DP * density;
        badgeMarginStartPx = BADGE_MARGIN_START_DP * density;

        namePaint.setColor(NAME_COLOR);
        namePaint.setFakeBoldText(true);
        namePaint.setTextSize(spToPx(NAME_TEXT_SP));

        badgePaint.setColor(BADGE_COLOR);
        badgePaint.setFakeBoldText(true);
        badgePaint.setTextSize(spToPx(BADGE_TEXT_SP));
        badgeWidth = badgePaint.measureText(BADGE_TEXT);

        avatarPlaceholderPaint.setColor(PLACEHOLDER_BG);
        avatarPlaceholderPaint.setStyle(Paint.Style.FILL);

        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    /** Sets the display name. No-op if unchanged. */
    public void setName(String name) {
        String safe = name != null ? name : "";
        if (safe.equals(nameText)) return;
        nameText = safe;
        nameEllipsized = null; // recomputed lazily in onDraw once width is known
        invalidate();
    }

    /**
     * Loads (or clears) the circular avatar. Cheap no-op if URL unchanged.
     * FIX (avatar-optimization — reuse core pipeline): routed through
     * {@link MiscAvatarBinder#bindBitmap} instead of a flat, un-tiered
     * {@code .override(720, 720)} decode (720x720 pixels for a 32dp row!)
     * — now requests exactly MENTION_TIER's pixels and shares
     * MiscAvatarL2Cache/L3 + CDN analytics with every other "misc" avatar
     * surface, so a mention already seen elsewhere paints instantly.
     */
    public void setAvatarUrl(@Nullable String url) {
        if (url != null && url.equals(pendingAvatarUrl) && avatarBitmap != null) return;
        pendingAvatarUrl = url;
        avatarBitmap = null;
        invalidate();

        if (url == null || url.isEmpty()) return;

        final String requestedUrl = url;
        MiscAvatarBinder.bindBitmap(getContext(), url, 0L, resource -> {
            // Stale — row has been rebound (recycled dropdown) to a
            // different photo since this request went out; drop the result.
            if (!requestedUrl.equals(pendingAvatarUrl)) return;
            avatarBitmap = resource;
            invalidate();
        });
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float avatarTop = (h - avatarSizePx) / 2f;
        avatarRect.set(paddingHPx, avatarTop, paddingHPx + avatarSizePx, avatarTop + avatarSizePx);

        if (avatarBitmap != null && !avatarBitmap.isRecycled()) {
            canvas.drawBitmap(avatarBitmap, null, avatarRect, null);
        } else {
            canvas.drawOval(avatarRect, avatarPlaceholderPaint);
        }

        float nameStartX = avatarRect.right + nameMarginStartPx;
        float badgeEndX = w - paddingHPx;
        float nameEndX = badgeEndX - badgeMarginStartPx - badgeWidth;
        float availableNameWidth = nameEndX - nameStartX;

        if (availableNameWidth > 0) {
            if (nameEllipsized == null) {
                nameEllipsized = TextUtils.ellipsize(nameText, namePaint, availableNameWidth, TextUtils.TruncateAt.END);
            }
            Paint.FontMetrics nameFm = namePaint.getFontMetrics();
            float nameY = h / 2f - (nameFm.ascent + nameFm.descent) / 2f;
            canvas.drawText(nameEllipsized, 0, nameEllipsized.length(), nameStartX, nameY, namePaint);
        }

        Paint.FontMetrics badgeFm = badgePaint.getFontMetrics();
        float badgeY = h / 2f - (badgeFm.ascent + badgeFm.descent) / 2f;
        canvas.drawText(BADGE_TEXT, nameEndX + badgeMarginStartPx, badgeY, badgePaint);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        nameEllipsized = null; // re-ellipsize for the new width
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // bindBitmap() has no external target to Glide.clear() — its
        // in-flight request is left to finish, and the staleness check in
        // setAvatarUrl()'s callback (pendingAvatarUrl) discards the result
        // if this row has since been rebound to a different photo.
        pendingAvatarUrl = null;
    }
}
