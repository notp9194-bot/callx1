package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.callx.app.cache.ChatAvatarBinder;
import com.callx.app.chat.R;

/**
 * RecordingStripCanvasView — Canvas-rendered replacement for the
 * avatar + mic-icon + name trio inside ll_voice_recording_strip (previously
 * a CircleImageView + ImageView + TextView, each its own measure/layout/
 * draw pass). Same rationale and constants as {@link TypingStripCanvasView}
 * (this strip uses the exact same avatar size/border/name style — it's the
 * "someone is recording" sibling of "someone is typing").
 *
 * The live waveform (RecordingWaveformView, driven by RecordingPreviewController)
 * is deliberately NOT folded into this view — it's already its own tuned
 * canvas view (fixed-capacity ring buffer, allocation-free per sample) used
 * both here and in our own recording bar, so merging it in would duplicate
 * that logic for no benefit. It stays a sibling View next to this one inside
 * ll_voice_recording_strip.
 *
 * Mic icon is rasterized to a small white Bitmap once in init() (tinted
 * ic_mic) instead of calling Drawable#draw() every frame — cheap either way
 * at this size, but keeps every drawn element here a plain drawBitmap()/
 * drawText() call, consistent with how the avatar is handled.
 */
public class RecordingStripCanvasView extends View {

    private static final float AVATAR_SIZE_DP = 22f;
    private static final float AVATAR_BORDER_DP = 1f;
    private static final float MIC_SIZE_DP = 14f;
    private static final float AVATAR_MIC_GAP_DP = 7f;
    private static final float MIC_NAME_GAP_DP = 5f;
    private static final float NAME_TEXT_SP = 12f;
    private static final float MAX_NAME_WIDTH_DP = 160f;

    private final TextPaint namePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF avatarRect = new RectF();
    private final Paint.FontMetrics nameFontMetrics = new Paint.FontMetrics();

    private int avatarSizePx, borderPx, avatarMicGapPx, micSizePx, micNameGapPx, maxNameWidthPx;

    private Bitmap avatarBitmap; // circleCrop()-ped, handed back by ChatAvatarBinder.bindBitmap()
    private String pendingAvatarUrl; // also doubles as the staleness token for bindBitmap()'s callback

    private Bitmap micBitmap; // ic_mic, tinted white, rasterized once

    private String nameText = "";
    private CharSequence nameEllipsized = "";
    private float nameWidth;
    private boolean nameLayoutDirty = true;

    public RecordingStripCanvasView(Context context) { super(context); init(); }
    public RecordingStripCanvasView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public RecordingStripCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        avatarSizePx = (int) (AVATAR_SIZE_DP * density);
        borderPx = (int) (AVATAR_BORDER_DP * density);
        avatarMicGapPx = (int) (AVATAR_MIC_GAP_DP * density);
        micSizePx = (int) (MIC_SIZE_DP * density);
        micNameGapPx = (int) (MIC_NAME_GAP_DP * density);
        maxNameWidthPx = (int) (MAX_NAME_WIDTH_DP * density);

        namePaint.setColor(Color.WHITE);
        namePaint.setFakeBoldText(true);
        namePaint.setTextSize(spToPx(NAME_TEXT_SP));
        namePaint.getFontMetrics(nameFontMetrics); // cached once — text size never changes post-init

        avatarBorderPaint.setStyle(Paint.Style.STROKE);
        avatarBorderPaint.setStrokeWidth(borderPx);
        avatarBorderPaint.setColor(0x80FFFFFF);

        avatarPlaceholderPaint.setColor(0x33FFFFFF);
        avatarPlaceholderPaint.setStyle(Paint.Style.FILL);

        micBitmap = renderMicBitmap();

        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
    }

    private float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    /** Rasterizes ic_mic (tinted white) into a small Bitmap once, so onDraw()
     *  is just a drawBitmap() call like everything else here. */
    @Nullable
    private Bitmap renderMicBitmap() {
        Drawable d = ContextCompat.getDrawable(getContext(), R.drawable.ic_mic);
        if (d == null || micSizePx <= 0) return null;
        d = d.mutate();
        DrawableCompat.setTint(d, Color.WHITE);
        Bitmap bmp = Bitmap.createBitmap(micSizePx, micSizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, micSizePx, micSizePx);
        d.draw(c);
        return bmp;
    }

    // ── Content setters ──────────────────────────────────────────────────

    public void setName(String name) {
        if (name == null) name = "";
        if (name.equals(nameText)) return;
        nameText = name;
        nameLayoutDirty = true;
        requestLayout();
        invalidate();
    }

    /**
     * Loads and circle-crops the avatar once; cheap no-op if the photo hasn't
     * changed. FIX (avatar optimization — reuse core pipeline): routed
     * through {@link ChatAvatarBinder#bindBitmap} instead of a flat
     * un-tiered Glide load into a one-off target — same responsive/
     * version-tagged URL, L2 memory + L3 disk tiers, and analytics every
     * other chat avatar surface uses. This strip's avatar is almost always
     * the SAME partner already shown in the chat list row (and often the
     * reel-share header), so this shares those cache entries — typically an
     * instant L2 hit the moment a recording notice pops up instead of a
     * fresh decode, which matters here since the strip can show/hide
     * rapidly as the partner starts/stops recording.
     */
    public void setAvatarUrl(@Nullable String photo) {
        if (photo != null && photo.equals(pendingAvatarUrl) && avatarBitmap != null) return;
        pendingAvatarUrl = photo;
        if (photo == null || photo.isEmpty()) {
            avatarBitmap = null;
            invalidate();
            return;
        }
        final String requestedPhoto = photo;
        ChatAvatarBinder.bindBitmap(getContext(), photo, 0L, resource -> {
            // Stale — strip has been rebound (or cleared) to a different
            // photo since this request went out; drop the result.
            if (!requestedPhoto.equals(pendingAvatarUrl)) return;
            avatarBitmap = resource;
            invalidate();
        });
    }

    // ── Measure / draw ────────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (nameLayoutDirty) rebuildNameLayout();

        int contentWidth = avatarSizePx + avatarMicGapPx + micSizePx + micNameGapPx
                + (int) Math.ceil(nameWidth);
        int contentHeight = avatarSizePx;

        int width = getPaddingLeft() + contentWidth + getPaddingRight();
        int height = getPaddingTop() + contentHeight + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    private void rebuildNameLayout() {
        nameEllipsized = TextUtils.ellipsize(nameText, namePaint, maxNameWidthPx, TextUtils.TruncateAt.END);
        nameWidth = namePaint.measureText(nameEllipsized, 0, nameEllipsized.length());
        nameLayoutDirty = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (nameLayoutDirty) rebuildNameLayout();

        int centerY = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom()) / 2;
        int left = getPaddingLeft();

        // ── Avatar ──
        float avatarCx = left + avatarSizePx / 2f;
        float avatarRadius = avatarSizePx / 2f;
        if (avatarBitmap != null && !avatarBitmap.isRecycled()) {
            avatarRect.set(avatarCx - avatarRadius, centerY - avatarRadius,
                    avatarCx + avatarRadius, centerY + avatarRadius);
            canvas.drawBitmap(avatarBitmap, null, avatarRect, null);
        } else {
            canvas.drawCircle(avatarCx, centerY, avatarRadius, avatarPlaceholderPaint);
        }
        canvas.drawCircle(avatarCx, centerY, avatarRadius - borderPx / 2f, avatarBorderPaint);

        // ── Mic icon ──
        int micLeft = left + avatarSizePx + avatarMicGapPx;
        if (micBitmap != null) {
            int micTop = centerY - micSizePx / 2;
            canvas.drawBitmap(micBitmap, micLeft, micTop, null);
        }

        // ── Name ──
        int nameLeft = micLeft + micSizePx + micNameGapPx;
        float nameBaseline = centerY - (nameFontMetrics.ascent + nameFontMetrics.descent) / 2f;
        canvas.drawText(nameEllipsized, 0, nameEllipsized.length(), nameLeft, nameBaseline, namePaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // bindBitmap() (see MessagePagingAdapter's reel-share avatar for the
        // same pattern) has no external target to Glide.clear() — its
        // in-flight request is left to finish, and the staleness check in
        // setAvatarUrl()'s callback (pendingAvatarUrl) discards the result
        // if this strip has since been rebound to a different photo.
        pendingAvatarUrl = null;
    }
}
