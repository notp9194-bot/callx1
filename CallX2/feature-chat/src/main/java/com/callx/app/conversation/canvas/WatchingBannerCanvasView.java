package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.callx.app.cache.ChatAvatarBinder;
import com.callx.app.utils.AvatarSizeTier;

/**
 * WatchingBannerCanvasView — canvas-rendered replacement for the old
 * ll_watching_banner LinearLayout (ll_watching_avatars [iv_watching_avatar +
 * iv_watching_avatar2 + tv_watching_more badge] + tv_watching_name pill).
 * Sibling of {@link TypingStripCanvasView} / {@link RecordingStripCanvasView}
 * — same rationale: this banner toggles show/hide on every partner-arrives/
 * partner-leaves Firebase tick (1:1: ChatPresenceController#watchPartnerInChatScreen;
 * group: GroupWatchingController#watchGroupPresence), so collapsing 5 Views
 * (2 avatars + badge + name pill + their shared parent) into ONE that just
 * redraws avoids paying inflate + a 5-deep measure/layout pass on every
 * single toggle — the same trade that already justified the typing-strip
 * and recording-strip rewrites (see those classes' docs). A plain ViewStub
 * only buys a ONE-TIME inflate saving; after that first inflate it's back
 * to a normal 5-view LinearLayout paying full measure/layout on every
 * show/hide, which is the wrong trade for a view that toggles this often.
 *
 * 1:1 chats (ChatPresenceController) only ever call setName()/setAvatarUrl()
 * — the secondary avatar and "+N" badge stay unset/invisible, so the banner
 * looks identical to before. Group chats (GroupWatchingController)
 * additionally call setSecondaryAvatarUrl()/setBadgeText() for 2 and 3+
 * watchers respectively, e.g. "Asha, Ravi aur 2 others dekh rahe hain".
 *
 * Layout is a vertical stack, centered horizontally (mirrors the old
 * LinearLayout orientation="vertical" gravity="center_horizontal"):
 *   row 1 — up to 3 overlapping circles: avatar1, avatar2 (-14dp overlap),
 *           "+N" badge (-14dp overlap on avatar2)
 *   row 2 — the name pill (rounded-rect fill + white bold text)
 *
 * Sub-region taps (avatar1 / avatar2 / badge) are hit-tested directly in
 * onTouchEvent() against cached bounds and fire their own listeners,
 * independent of the whole-banner setOnClickListener() — this is what let
 * GroupWatchingController keep "tap a specific face -> jump to that
 * person" / "tap +N -> open the watchers sheet" / "tap anywhere else ->
 * open the watchers sheet" all on a single View instead of 3 separate
 * clickable children.
 */
public class WatchingBannerCanvasView extends View {

    private static final float AVATAR_SIZE_DP = 48f;
    private static final float AVATAR_BORDER_DP = 2f;
    private static final float AVATAR_OVERLAP_DP = 14f;
    private static final float AVATARS_PILL_GAP_DP = 6f;
    private static final float PILL_PAD_H_DP = 14f;
    private static final float PILL_PAD_V_DP = 5f;
    private static final float PILL_RADIUS_DP = 14f;
    private static final float NAME_TEXT_SP = 12f;
    private static final float MAX_NAME_WIDTH_DP = 260f;
    private static final float BADGE_TEXT_SP = 12f;

    private static final int COLOR_BORDER_GREEN = 0xFF22C55E;
    private static final int COLOR_PILL_PURPLE = 0xFF6C2DC7;
    private static final int COLOR_BADGE_PURPLE = 0xFF7B3FD4;
    private static final int COLOR_PLACEHOLDER = 0x33FFFFFF;

    /** Tier for these 48dp avatars — SMALL(48) undersells it slightly the
     *  way ChatAvatarBinder's own 50dp list-row tier notes, but this is the
     *  exact same size class as that row, so reuse its 48dp-anchored tier
     *  math instead of inventing a new bucket for one screen's banner. */
    private static final AvatarSizeTier AVATAR_TIER = AvatarSizeTier.forViewSizeDp(48);

    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint badgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF avatarRect = new RectF();
    private final RectF pillRect = new RectF();
    private final Paint.FontMetrics nameFontMetrics = new Paint.FontMetrics();
    private final Paint.FontMetrics badgeFontMetrics = new Paint.FontMetrics();

    // Cached layout bounds — computed once in onSizeChanged()/content
    // changes, reused by both onDraw()'s clip-skip-free direct paint and
    // onTouchEvent()'s hit-testing. Never recomputed per frame/per tap.
    private final Rect avatar1Bounds = new Rect();
    private final Rect avatar2Bounds = new Rect();
    private final Rect badgeBounds = new Rect();
    private final Rect pillBoundsInt = new Rect();

    private int avatarSizePx, borderPx, overlapPx, avatarsPillGapPx;
    private int pillPadHPx, pillPadVPx, pillRadiusPx, maxNameWidthPx;

    private Bitmap avatar1Bitmap;
    private Bitmap avatar2Bitmap;
    private String pendingAvatar1Url;
    private String pendingAvatar2Url;

    private boolean avatar2Visible = false;
    private boolean badgeVisible = false;

    private String nameText = "";
    private CharSequence nameEllipsized = "";
    private float nameWidth;
    private boolean nameLayoutDirty = true;

    private String badgeText = "";

    private Runnable onAvatar1Click;
    private Runnable onAvatar2Click;
    private Runnable onBadgeClick;

    private enum Region { NONE, AVATAR1, AVATAR2, BADGE }
    private Region touchDownRegion = Region.NONE;

    public WatchingBannerCanvasView(Context context) { super(context); init(); }
    public WatchingBannerCanvasView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public WatchingBannerCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        avatarSizePx = (int) (AVATAR_SIZE_DP * density);
        borderPx = (int) (AVATAR_BORDER_DP * density);
        overlapPx = (int) (AVATAR_OVERLAP_DP * density);
        avatarsPillGapPx = (int) (AVATARS_PILL_GAP_DP * density);
        pillPadHPx = (int) (PILL_PAD_H_DP * density);
        pillPadVPx = (int) (PILL_PAD_V_DP * density);
        pillRadiusPx = (int) (PILL_RADIUS_DP * density);
        maxNameWidthPx = (int) (MAX_NAME_WIDTH_DP * density);

        namePaint.setColor(Color.WHITE);
        namePaint.setFakeBoldText(true);
        namePaint.setTextSize(spToPx(NAME_TEXT_SP));
        namePaint.getFontMetrics(nameFontMetrics); // cached once — text size never changes post-init

        badgePaint.setColor(Color.WHITE);
        badgePaint.setFakeBoldText(true);
        badgePaint.setTextAlign(Paint.Align.CENTER);
        badgePaint.setTextSize(spToPx(BADGE_TEXT_SP));
        badgePaint.getFontMetrics(badgeFontMetrics);

        pillPaint.setStyle(Paint.Style.FILL);
        pillPaint.setColor(COLOR_PILL_PURPLE);

        avatarBorderPaint.setStyle(Paint.Style.STROKE);
        avatarBorderPaint.setStrokeWidth(borderPx);
        avatarBorderPaint.setColor(COLOR_BORDER_GREEN);

        avatarPlaceholderPaint.setColor(COLOR_PLACEHOLDER);
        avatarPlaceholderPaint.setStyle(Paint.Style.FILL);

        badgeFillPaint.setStyle(Paint.Style.FILL);
        badgeFillPaint.setColor(COLOR_BADGE_PURPLE);

        badgeStrokePaint.setStyle(Paint.Style.STROKE);
        badgeStrokePaint.setStrokeWidth(borderPx);
        badgeStrokePaint.setColor(COLOR_BORDER_GREEN);

        setWillNotDraw(false);
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    // ── Content setters ──────────────────────────────────────────────────

    /** @param name already-formatted label, e.g. "Asha aapko dekh rha hai"
     *  or "Asha, Ravi aur 2 others dekh rahe hain". */
    public void setName(String name) {
        if (name == null) name = "";
        if (name.equals(nameText)) return;
        nameText = name;
        nameLayoutDirty = true;
        requestLayout();
        invalidate();
    }

    /**
     * Primary (always-visible-when-showing) watcher avatar. Routed through
     * {@link ChatAvatarBinder#bindBitmap} — same L2/L3-shared pipeline every
     * other avatar surface uses (chat list row, header, typing/recording
     * strips) — rather than a one-off Glide load, since this is almost
     * always the same partner/member already cached from those surfaces.
     */
    public void setAvatarUrl(@Nullable String url) {
        if (url != null && url.equals(pendingAvatar1Url) && avatar1Bitmap != null) return;
        pendingAvatar1Url = url;
        if (url == null || url.isEmpty()) {
            avatar1Bitmap = null;
            invalidate();
            return;
        }
        final String requested = url;
        ChatAvatarBinder.bindBitmap(getContext(), url, 0L, AVATAR_TIER, resource -> {
            if (!requested.equals(pendingAvatar1Url)) return; // stale — rebound since
            avatar1Bitmap = resource;
            invalidate(avatar1Bounds.isEmpty() ? null : avatar1Bounds);
        });
    }

    /** Group-only second overlapping avatar. Passing null/empty hides it
     *  (and the badge, transitively, if it was only shown alongside it). */
    public void setSecondaryAvatarUrl(@Nullable String url) {
        boolean wasVisible = avatar2Visible;
        avatar2Visible = url != null && !url.isEmpty();
        if (avatar2Visible) {
            if (!url.equals(pendingAvatar2Url) || avatar2Bitmap == null) {
                pendingAvatar2Url = url;
                final String requested = url;
                ChatAvatarBinder.bindBitmap(getContext(), url, 0L, AVATAR_TIER, resource -> {
                    if (!requested.equals(pendingAvatar2Url)) return;
                    avatar2Bitmap = resource;
                    invalidate(avatar2Bounds.isEmpty() ? null : avatar2Bounds);
                });
            }
        } else {
            pendingAvatar2Url = null;
            avatar2Bitmap = null;
        }
        if (wasVisible != avatar2Visible) {
            requestLayout();
            invalidate();
        }
    }

    /** Group-only "+N" badge. Passing null/empty hides it. */
    public void setBadgeText(@Nullable String text) {
        boolean wasVisible = badgeVisible;
        String newText = text == null ? "" : text;
        badgeVisible = !newText.isEmpty();
        boolean textChanged = !newText.equals(badgeText);
        badgeText = newText;
        if (wasVisible != badgeVisible) {
            requestLayout();
            invalidate();
        } else if (badgeVisible && textChanged) {
            invalidate(badgeBounds.isEmpty() ? null : badgeBounds);
        }
    }

    // ── Sub-region click listeners ───────────────────────────────────────

    public void setOnAvatar1ClickListener(@Nullable Runnable r) { onAvatar1Click = r; }
    public void setOnAvatar2ClickListener(@Nullable Runnable r) { onAvatar2Click = r; }
    public void setOnBadgeClickListener(@Nullable Runnable r) { onBadgeClick = r; }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownRegion = hitTestRegion((int) event.getX(), (int) event.getY());
                if (touchDownRegion != Region.NONE) return true; // consumed, resolved on UP
                return super.onTouchEvent(event);
            case MotionEvent.ACTION_UP: {
                Region r = touchDownRegion;
                touchDownRegion = Region.NONE;
                if (r != Region.NONE) {
                    fireRegionClick(r);
                    return true;
                }
                return super.onTouchEvent(event);
            }
            case MotionEvent.ACTION_CANCEL:
                touchDownRegion = Region.NONE;
                return super.onTouchEvent(event);
            default:
                return super.onTouchEvent(event);
        }
    }

    private Region hitTestRegion(int x, int y) {
        // Tested topmost-drawn-first: badge and avatar2 are painted on top
        // of avatar1's overlapped edge, so they should win any ambiguity in
        // the shared 14dp overlap strip.
        if (badgeVisible && badgeBounds.contains(x, y)) return Region.BADGE;
        if (avatar2Visible && avatar2Bounds.contains(x, y)) return Region.AVATAR2;
        if (avatar1Bounds.contains(x, y)) return Region.AVATAR1;
        return Region.NONE;
    }

    private void fireRegionClick(Region r) {
        Runnable listener = r == Region.BADGE ? onBadgeClick
                : r == Region.AVATAR2 ? onAvatar2Click
                : onAvatar1Click;
        if (listener != null) listener.run();
    }

    // ── Measure / layout ──────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (nameLayoutDirty) rebuildNameLayout();

        int avatarsRowWidth = avatarsRowWidth();
        int pillWidth = pillPadHPx * 2 + (int) Math.ceil(nameWidth);
        int contentWidth = Math.max(avatarsRowWidth, pillWidth);
        int contentHeight = avatarSizePx + avatarsPillGapPx + pillHeight();

        int width = getPaddingLeft() + contentWidth + getPaddingRight();
        int height = getPaddingTop() + contentHeight + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    private int avatarsRowWidth() {
        int extras = 0;
        if (avatar2Visible) extras++;
        if (badgeVisible) extras++;
        return avatarSizePx + extras * (avatarSizePx - overlapPx);
    }

    private int pillHeight() {
        return pillPadVPx * 2 + (int) Math.ceil(nameFontMetrics.descent - nameFontMetrics.ascent);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeCachedBounds();
    }

    /** Recomputes every drawn region's bounds — used both by onDraw() and
     *  by onTouchEvent()'s hit-testing. Only runs on a genuine layout
     *  change (size, or avatar2/badge visibility flip, or a name change
     *  that widened/narrowed the pill), never per frame or per tap. */
    private void recomputeCachedBounds() {
        if (getWidth() <= 0) return;
        int contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();

        int avatarsRowWidth = avatarsRowWidth();
        int avatarsLeft = getPaddingLeft() + Math.max(0, (contentWidth - avatarsRowWidth) / 2);
        int avatarsTop = getPaddingTop();

        avatar1Bounds.set(avatarsLeft, avatarsTop, avatarsLeft + avatarSizePx, avatarsTop + avatarSizePx);

        int nextLeft = avatar1Bounds.right - overlapPx;
        if (avatar2Visible) {
            avatar2Bounds.set(nextLeft, avatarsTop, nextLeft + avatarSizePx, avatarsTop + avatarSizePx);
            nextLeft = avatar2Bounds.right - overlapPx;
        } else {
            avatar2Bounds.setEmpty();
        }
        if (badgeVisible) {
            badgeBounds.set(nextLeft, avatarsTop, nextLeft + avatarSizePx, avatarsTop + avatarSizePx);
        } else {
            badgeBounds.setEmpty();
        }

        int pillWidth = pillPadHPx * 2 + (int) Math.ceil(nameWidth);
        int pillLeft = getPaddingLeft() + Math.max(0, (contentWidth - pillWidth) / 2);
        int pillTop = avatarsTop + avatarSizePx + avatarsPillGapPx;
        pillBoundsInt.set(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight());
    }

    private void rebuildNameLayout() {
        nameEllipsized = TextUtils.ellipsize(nameText, namePaint, maxNameWidthPx, TextUtils.TruncateAt.END);
        nameWidth = namePaint.measureText(nameEllipsized, 0, nameEllipsized.length());
        nameLayoutDirty = false;
        // Pill width may have changed — bounds must be redone, but only on
        // this genuine-change path, not per draw.
        if (getWidth() > 0) recomputeCachedBounds();
    }

    // ── Draw ────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (nameLayoutDirty) rebuildNameLayout();
        if (pillBoundsInt.isEmpty() && getWidth() > 0) recomputeCachedBounds(); // first-draw edge case

        drawAvatar(canvas, avatar1Bounds, avatar1Bitmap);
        if (avatar2Visible) drawAvatar(canvas, avatar2Bounds, avatar2Bitmap);
        if (badgeVisible) drawBadge(canvas, badgeBounds);
        drawPill(canvas, pillBoundsInt);
    }

    private void drawAvatar(Canvas canvas, Rect bounds, @Nullable Bitmap bitmap) {
        if (bounds.isEmpty()) return;
        float radius = bounds.width() / 2f;
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        if (bitmap != null && !bitmap.isRecycled()) {
            avatarRect.set(bounds);
            canvas.drawBitmap(bitmap, null, avatarRect, null);
        } else {
            canvas.drawCircle(cx, cy, radius, avatarPlaceholderPaint);
        }
        canvas.drawCircle(cx, cy, radius - borderPx / 2f, avatarBorderPaint);
    }

    private void drawBadge(Canvas canvas, Rect bounds) {
        if (bounds.isEmpty()) return;
        float radius = bounds.width() / 2f;
        float cx = bounds.exactCenterX();
        float cy = bounds.exactCenterY();
        canvas.drawCircle(cx, cy, radius, badgeFillPaint);
        canvas.drawCircle(cx, cy, radius - borderPx / 2f, badgeStrokePaint);
        if (!badgeText.isEmpty()) {
            float baseline = cy - (badgeFontMetrics.ascent + badgeFontMetrics.descent) / 2f;
            canvas.drawText(badgeText, cx, baseline, badgePaint);
        }
    }

    private void drawPill(Canvas canvas, Rect bounds) {
        if (bounds.isEmpty()) return;
        pillRect.set(bounds);
        canvas.drawRoundRect(pillRect, pillRadiusPx, pillRadiusPx, pillPaint);
        float textLeft = bounds.left + pillPadHPx;
        float baseline = bounds.exactCenterY() - (nameFontMetrics.ascent + nameFontMetrics.descent) / 2f;
        canvas.drawText(nameEllipsized, 0, nameEllipsized.length(), textLeft, baseline, namePaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // bindBitmap() has no external target to Glide.clear() — in-flight
        // requests are left to finish, and the staleness checks in
        // setAvatarUrl()/setSecondaryAvatarUrl()'s callbacks discard the
        // result if this banner has since been rebound or cleared.
        pendingAvatar1Url = null;
        pendingAvatar2Url = null;
        touchDownRegion = Region.NONE;
    }
}
