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
import android.view.Choreographer;
import android.view.View;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

/**
 * TypingStripCanvasView — Canvas-rendered replacement for ll_typing_strip
 * (a LinearLayout hosting a CircleImageView avatar, a TextView name, and a
 * child LinearLayout of 3 dot Views bounced by TypingDotsAnimator).
 *
 * WHY THIS EXISTS
 * ─────────────────
 * TypingDotsAnimator's real bounce loop was previously made a NO-OP
 * ("removed for performance" — see git history) because animating 3
 * separate Views' translationY/alpha on a property-animator loop, every
 * frame, for as long as the partner keeps typing, meant 3 independent
 * invalidate() calls bubbling up through ll_typing_dots → ll_typing_strip
 * → the activity_chat.xml root FrameLayout on every tick — competing for
 * main-thread frame budget with whatever the message RecyclerView was
 * doing in the same frame (scrolling/flinging is exactly when a partner
 * is likely to be replying, i.e. typing). Losing the bounce animation
 * entirely was the trade the previous fix made to stop that bleed.
 *
 * BASE VERSION (single-View canvas rewrite) already got:
 *   • ONE View instead of 5 (avatar + name + 3 dots) — one measure, one
 *     layout, one onDraw() paints all of it.
 *   • Avatar decoded ONCE into a circular Bitmap (Glide circleCrop()).
 *   • Name text re-measured only on a genuine text change, not per draw.
 *
 * THIS PASS — advanced/per-frame optimization, on top of the above:
 *
 *  1. CHOREOGRAPHER INSTEAD OF ValueAnimator — ValueAnimator's own
 *     AnimationHandler + PropertyValuesHolder machinery boxes every
 *     animated float into a Float and runs interpolator/keyframe
 *     evaluation neither of which this view needs (the bounce curve is a
 *     closed-form function of elapsed time, not a keyframe set). Driving
 *     the loop off a raw Choreographer.FrameCallback removes that layer
 *     entirely — phase is computed directly from frameTimeNanos with zero
 *     autoboxing, which matters here because this callback runs on every
 *     vsync (~60-120/sec) for as long as the partner is typing.
 *
 *  2. DIRTY-RECT INVALIDATION, NOT A FULL-VIEW INVALIDATE — every dot
 *     frame previously called invalidate() (whole view: avatar + name +
 *     dots all re-recorded into the display list) even though only the
 *     3 dots actually move. Now only the dots' bounding box (padded for
 *     the bounce range) is invalidated via invalidate(l,t,r,b). onDraw()
 *     then checks the canvas' clip bounds and skips the drawBitmap()/
 *     drawText() calls entirely when the avatar/name region isn't part
 *     of the dirty rect — so a steady-state bounce loop stops re-issuing
 *     an avatar bitmap draw and a text draw 60+ times a second for pixels
 *     that haven't changed.
 *
 *  3. NO TRIG IN THE HOT PATH — the bounce curve (a per-dot half-sine)
 *     is sampled at BOUNCE_LUT_SIZE points once in init() instead of
 *     calling Math.sin() for 3 dots on every single frame.
 *
 *  4. FontMetrics CACHED — Paint#getFontMetrics() allocates a new
 *     FontMetrics object on every call; the old onDraw() called it once
 *     per frame (i.e. every dot tick, not just on text change). Built
 *     once in init()/whenever text size changes instead.
 *
 *  5. NO EXPLICIT HARDWARE LAYER — deliberately NOT calling
 *     setLayerType(LAYER_TYPE_HARDWARE, ...) here. With hardware
 *     acceleration on (the app default), this View already gets its own
 *     RenderNode as a FrameLayout sibling of the message RecyclerView —
 *     invalidating it doesn't force the RecyclerView to re-record. An
 *     explicit layer would add a second GPU-texture copy of a view this
 *     small for no benefit, so it's skipped on purpose rather than
 *     cargo-culted in.
 */
public class TypingStripCanvasView extends View {

    private static final float AVATAR_SIZE_DP = 22f;
    private static final float AVATAR_BORDER_DP = 1f;
    private static final float NAME_TEXT_SP = 12f;
    private static final float DOT_SIZE_DP = 5f;
    private static final float DOT_GAP_DP = 3f;
    private static final float GAP_DP = 6f;
    private static final float NAME_DOTS_GAP_DP = 6f;
    private static final float MAX_NAME_WIDTH_DP = 180f;
    private static final float BOUNCE_HEIGHT_DP = 3f;
    private static final long DOT_CYCLE_NANOS = 900_000_000L;
    private static final float DOT_PHASE_STAGGER = 0.22f;

    /** Precomputed half-sine bounce curve — avoids Math.sin() per dot per frame. */
    private static final int BOUNCE_LUT_SIZE = 64;
    private static final float[] BOUNCE_LUT = new float[BOUNCE_LUT_SIZE];
    static {
        for (int i = 0; i < BOUNCE_LUT_SIZE; i++) {
            float t = i / (float) BOUNCE_LUT_SIZE;
            double s = Math.sin(t * Math.PI);
            BOUNCE_LUT[i] = s > 0 ? (float) s : 0f;
        }
    }

    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF avatarRect = new RectF();
    private final Paint.FontMetrics nameFontMetrics = new Paint.FontMetrics();

    // Cached layout bounds — computed once in onSizeChanged(), reused by
    // both onDraw()'s clip-skip check and the per-frame dirty-rect calc.
    private final Rect avatarBoundsInt = new Rect();
    private final Rect nameBoundsInt = new Rect();
    private final Rect dotsBoundsInt = new Rect();
    private final Rect clipBoundsScratch = new Rect(); // reused every onDraw, never allocated per-frame

    private int avatarSizePx, borderPx, gapPx, nameDotsGapPx, dotSizePx, dotGapPx, maxNameWidthPx;
    private int bounceHeightPx;

    private Bitmap avatarBitmap; // already circleCrop()-ped by Glide
    private CustomTarget<Bitmap> pendingAvatarTarget;
    private String pendingAvatarUrl;

    private String nameText = "";
    private CharSequence nameEllipsized = "";
    private float nameWidth;
    private boolean nameLayoutDirty = true;

    private final float[] dotOffsets = new float[3];
    private boolean dotsRunning = false;
    private long dotsStartNanos = 0L;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!dotsRunning) return;
            if (dotsStartNanos == 0L) dotsStartNanos = frameTimeNanos;
            long elapsed = frameTimeNanos - dotsStartNanos;
            float phase = (elapsed % DOT_CYCLE_NANOS) / (float) DOT_CYCLE_NANOS;
            computeDotOffsets(phase);
            invalidateDotsRegion();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public TypingStripCanvasView(Context context) { super(context); init(); }
    public TypingStripCanvasView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public TypingStripCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        avatarSizePx = (int) (AVATAR_SIZE_DP * density);
        borderPx = (int) (AVATAR_BORDER_DP * density);
        gapPx = (int) (GAP_DP * density);
        nameDotsGapPx = (int) (NAME_DOTS_GAP_DP * density);
        dotSizePx = (int) (DOT_SIZE_DP * density);
        dotGapPx = (int) (DOT_GAP_DP * density);
        maxNameWidthPx = (int) (MAX_NAME_WIDTH_DP * density);
        bounceHeightPx = (int) Math.ceil(BOUNCE_HEIGHT_DP * density);

        namePaint.setColor(Color.WHITE);
        namePaint.setFakeBoldText(true);
        namePaint.setTextSize(spToPx(NAME_TEXT_SP));
        namePaint.getFontMetrics(nameFontMetrics); // cached once — text size never changes post-init

        avatarBorderPaint.setStyle(Paint.Style.STROKE);
        avatarBorderPaint.setStrokeWidth(borderPx);
        avatarBorderPaint.setColor(0x80FFFFFF);

        avatarPlaceholderPaint.setColor(0x33FFFFFF);
        avatarPlaceholderPaint.setStyle(Paint.Style.FILL);

        dotPaint.setColor(Color.WHITE);
        dotPaint.setStyle(Paint.Style.FILL);

        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    // ── Content setters ──────────────────────────────────────────────────

    /** @param name already-formatted label, e.g. "Asha typing" or "Asha, Ravi typing" */
    public void setName(String name) {
        if (name == null) name = "";
        if (name.equals(nameText)) return;
        nameText = name;
        nameLayoutDirty = true;
        requestLayout();
        invalidate();
    }

    /** Loads and circle-crops the avatar once; cheap no-op if the URL hasn't changed. */
    public void setAvatarUrl(@Nullable String url) {
        if (url != null && url.equals(pendingAvatarUrl) && avatarBitmap != null) return;
        pendingAvatarUrl = url;
        if (url == null || url.isEmpty()) {
            avatarBitmap = null;
            invalidate();
            return;
        }
        if (pendingAvatarTarget != null) {
            Glide.with(getContext()).clear(pendingAvatarTarget);
        }
        pendingAvatarTarget = new CustomTarget<Bitmap>() {
            @Override public void onResourceReady(androidx.annotation.NonNull Bitmap resource,
                                                    @Nullable Transition<? super Bitmap> transition) {
                avatarBitmap = resource;
                if (avatarBoundsInt.isEmpty()) invalidate();
                else invalidate(avatarBoundsInt);
            }
            @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                avatarBitmap = null;
            }
        };
        Glide.with(getContext())
                .asBitmap()
                .circleCrop()
                .load(url)
                .override(avatarSizePx, avatarSizePx)
                .into(pendingAvatarTarget);
    }

    // ── Dot bounce animation — Choreographer-driven, dirty-rect only ────

    public void startDots() {
        if (dotsRunning) return;
        dotsRunning = true;
        dotsStartNanos = 0L; // re-synced on the next doFrame()
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    public void stopDots() {
        if (!dotsRunning) return;
        dotsRunning = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        java.util.Arrays.fill(dotOffsets, 0f);
        invalidateDotsRegion();
    }

    private void computeDotOffsets(float phase) {
        for (int i = 0; i < 3; i++) {
            float local = (phase + i * DOT_PHASE_STAGGER) % 1f;
            int idx = (int) (local * BOUNCE_LUT_SIZE);
            if (idx >= BOUNCE_LUT_SIZE) idx = BOUNCE_LUT_SIZE - 1;
            dotOffsets[i] = -bounceHeightPx * BOUNCE_LUT[idx];
        }
    }

    /** Invalidates only the dots' bounding box (padded for the bounce
     *  range) instead of the whole view — the avatar and name never move,
     *  so there's no reason to ask the display list to re-record them on
     *  every single dot tick. */
    private void invalidateDotsRegion() {
        if (dotsBoundsInt.isEmpty()) {
            invalidate(); // not laid out yet — fall back to a full invalidate once
            return;
        }
        invalidate(dotsBoundsInt.left, dotsBoundsInt.top - bounceHeightPx,
                dotsBoundsInt.right, dotsBoundsInt.bottom);
    }

    // ── Measure / layout ──────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (nameLayoutDirty) rebuildNameLayout();

        int contentWidth = avatarSizePx + gapPx + (int) Math.ceil(nameWidth) + nameDotsGapPx
                + (dotSizePx * 3 + dotGapPx * 2);
        int contentHeight = Math.max(avatarSizePx, dotSizePx) + bounceHeightPx;

        int width = getPaddingLeft() + contentWidth + getPaddingRight();
        int height = getPaddingTop() + contentHeight + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeCachedBounds();
    }

    /** Recomputes the static avatar/name/dots bounding rects used for the
     *  clip-skip check in onDraw() and the dirty-rect calc for the dot
     *  loop. Only runs on layout changes (size or a name change that
     *  actually altered content width), never per frame. */
    private void recomputeCachedBounds() {
        int left = getPaddingLeft();
        int centerY = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2;

        avatarBoundsInt.set(left, centerY - avatarSizePx / 2, left + avatarSizePx, centerY + avatarSizePx / 2);

        int nameLeft = left + avatarSizePx + gapPx;
        int nameTop = centerY - (int) Math.ceil((nameFontMetrics.descent - nameFontMetrics.ascent) / 2f);
        int nameRight = nameLeft + (int) Math.ceil(nameWidth);
        int nameBottom = nameTop + (int) Math.ceil(nameFontMetrics.descent - nameFontMetrics.ascent);
        nameBoundsInt.set(nameLeft, nameTop, nameRight, nameBottom);

        int dotsLeft = nameRight + nameDotsGapPx;
        int dotsRight = dotsLeft + dotSizePx * 3 + dotGapPx * 2;
        int dotsTop = centerY - dotSizePx / 2;
        int dotsBottom = centerY + dotSizePx / 2;
        dotsBoundsInt.set(dotsLeft, dotsTop, dotsRight, dotsBottom);
    }

    private void rebuildNameLayout() {
        nameEllipsized = TextUtils.ellipsize(nameText, namePaint, maxNameWidthPx, TextUtils.TruncateAt.END);
        nameWidth = namePaint.measureText(nameEllipsized, 0, nameEllipsized.length());
        nameLayoutDirty = false;
        // Content width may have changed (e.g. group label grew from
        // "Asha typing" to "Asha, Ravi typing") — bounds must be redone,
        // but only on this genuine-change path, not per draw/frame.
        if (getWidth() > 0) recomputeCachedBounds();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (nameLayoutDirty) rebuildNameLayout();
        if (dotsBoundsInt.isEmpty()) recomputeCachedBounds(); // first draw before any onSizeChanged edge case

        canvas.getClipBounds(clipBoundsScratch);

        int centerY = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2;

        // ── Avatar — skip the draw call entirely if this frame's dirty
        // rect doesn't touch it (steady-state dot bounce never does). ──
        if (Rect.intersects(clipBoundsScratch, avatarBoundsInt)) {
            float avatarCx = avatarBoundsInt.left + avatarSizePx / 2f;
            float avatarRadius = avatarSizePx / 2f;
            if (avatarBitmap != null && !avatarBitmap.isRecycled()) {
                avatarRect.set(avatarCx - avatarRadius, centerY - avatarRadius,
                        avatarCx + avatarRadius, centerY + avatarRadius);
                canvas.drawBitmap(avatarBitmap, null, avatarRect, null);
            } else {
                canvas.drawCircle(avatarCx, centerY, avatarRadius, avatarPlaceholderPaint);
            }
            canvas.drawCircle(avatarCx, centerY, avatarRadius - borderPx / 2f, avatarBorderPaint);
        }

        // ── Name — same clip-skip. ──
        if (Rect.intersects(clipBoundsScratch, nameBoundsInt)) {
            float nameBaseline = centerY - (nameFontMetrics.ascent + nameFontMetrics.descent) / 2f;
            canvas.drawText(nameEllipsized, 0, nameEllipsized.length(),
                    nameBoundsInt.left, nameBaseline, namePaint);
        }

        // ── Dots — always drawn; this is the part that's actually dirty
        // on every animation frame. ──
        float dotRadius = dotSizePx / 2f;
        float dotsLeft = dotsBoundsInt.left;
        for (int i = 0; i < 3; i++) {
            float cx = dotsLeft + dotRadius + i * (dotSizePx + dotGapPx);
            float cy = centerY + dotOffsets[i];
            canvas.drawCircle(cx, cy, dotRadius, dotPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Belt-and-suspenders: caller (ChatPresenceController/GroupChatActivity)
        // already stops this on screen-pause, but if the Activity is torn down
        // without going through that path, don't leak a running frame callback.
        if (dotsRunning) {
            dotsRunning = false;
            Choreographer.getInstance().removeFrameCallback(frameCallback);
        }
        if (pendingAvatarTarget != null) {
            Glide.with(getContext()).clear(pendingAvatarTarget);
        }
    }
}
