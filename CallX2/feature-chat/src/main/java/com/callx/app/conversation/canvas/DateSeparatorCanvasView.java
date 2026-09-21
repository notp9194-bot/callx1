package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * DateSeparatorCanvasView — Canvas-rendered replacement for
 * item_date_separator.xml (FrameLayout > TextView chip with bg_date_chip
 * background). Last remaining View-inflated row in the message list
 * (TYPE_DATE_SEPARATOR) — every other bubble/system row already renders
 * through MessageBubbleCanvasView. Low-frequency (once per day change),
 * so this is a cleanup/consistency pass rather than a scroll-jank fix,
 * but it removes the last per-item inflate() + findViewById() pair from
 * onCreateViewHolder/onBindViewHolder and lets this row recycle as a
 * plain View like every other holder in the list.
 *
 * Visuals match bg_date_chip.xml exactly: solid #3A3A4A fill, 10dp corner
 * radius, white 11sp bold centered text, 12dp/3dp chip padding, 10dp/10dp
 * outer top/bottom padding — all baked in as constants below since the
 * chip's look never varies at runtime (only the label text changes).
 *
 * Feature 8: this row is also reused (via MessagePagingAdapter's
 * TYPE_DATE_SEPARATOR routing for "system" join/leave rows) to show a
 * small circular avatar to the left of the text — e.g. "🖼 Priya joined
 * the group". Purely additive: {@link #setAvatar(Bitmap)} is null for
 * every plain date/security-event chip, which draws exactly as before.
 */
public class DateSeparatorCanvasView extends View {

    private static final float OUTER_PAD_V_DP = 10f;
    private static final float CHIP_PAD_H_DP = 12f;
    private static final float CHIP_PAD_V_DP = 3f;
    private static final float CHIP_RADIUS_DP = 10f;
    private static final float TEXT_SP = 11f;
    private static final int CHIP_COLOR = 0xFF3A3A4A;
    // Feature 8: avatar circle diameter + gap before the label text.
    private static final float AVATAR_DIAMETER_DP = 16f;
    private static final float AVATAR_GAP_DP = 5f;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF chipRect = new RectF();
    private final RectF avatarRect = new RectF();
    private final Matrix avatarShaderMatrix = new Matrix();
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();

    private int outerPadVPx, chipPadHPx, chipPadVPx, chipRadiusPx;
    private int avatarDiameterPx, avatarGapPx;
    private String label = "";
    private float textWidth = 0f;

    @Nullable private Bitmap avatarBitmap;
    private BitmapShader avatarShader;
    private Bitmap lastShaderBitmap;

    public DateSeparatorCanvasView(Context context) { super(context); init(); }
    public DateSeparatorCanvasView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public DateSeparatorCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        outerPadVPx = (int) (OUTER_PAD_V_DP * density);
        chipPadHPx = (int) (CHIP_PAD_H_DP * density);
        chipPadVPx = (int) (CHIP_PAD_V_DP * density);
        chipRadiusPx = (int) (CHIP_RADIUS_DP * density);
        avatarDiameterPx = Math.round(AVATAR_DIAMETER_DP * density);
        avatarGapPx = Math.round(AVATAR_GAP_DP * density);

        chipPaint.setStyle(Paint.Style.FILL);
        chipPaint.setColor(CHIP_COLOR);

        textPaint.setColor(Color.WHITE);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                getResources().getDisplayMetrics()));
        textPaint.getFontMetrics(fontMetrics);

        avatarPaint.setStyle(Paint.Style.FILL);

        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setSaveEnabled(false);
    }

    /** @param text e.g. "Today", "Yesterday", or "MMM d" — same strings bindMessage() used to feed tv_date_label. */
    public void setLabel(@Nullable String text) {
        String next = text != null ? text : "";
        if (next.equals(label)) return;
        label = next;
        textWidth = textPaint.measureText(label);
        requestLayout();
        invalidate();
    }

    /**
     * Feature 8: the join/leave member's avatar, drawn as a small circle
     * immediately left of the label inside the same chip. Pass null to
     * hide it (plain date/security-event chips, or before the bitmap has
     * resolved) — widens/narrows the chip and re-centers it either way.
     */
    public void setAvatar(@Nullable Bitmap bitmap) {
        if (bitmap == avatarBitmap) return;
        avatarBitmap = (bitmap != null && !bitmap.isRecycled()) ? bitmap : null;
        requestLayout();
        invalidate();
    }

    private float chipTextHeight() {
        return (float) Math.ceil(fontMetrics.descent - fontMetrics.ascent) + chipPadVPx * 2f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int chipHeight = Math.round(Math.max(chipTextHeight(), avatarBitmap != null ? avatarDiameterPx + chipPadVPx * 2f : 0));
        int height = outerPadVPx + chipHeight + outerPadVPx;
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (label.isEmpty()) return;

        boolean hasAvatar = avatarBitmap != null;
        float avatarSpace = hasAvatar ? avatarDiameterPx + avatarGapPx : 0f;

        int viewWidth = getWidth();
        float chipWidth = textWidth + chipPadHPx * 2f + avatarSpace;
        float chipHeight = Math.max(chipTextHeight(), hasAvatar ? avatarDiameterPx + chipPadVPx * 2f : 0f);
        float left = (viewWidth - chipWidth) / 2f;
        float top = outerPadVPx;

        chipRect.set(left, top, left + chipWidth, top + chipHeight);
        canvas.drawRoundRect(chipRect, chipRadiusPx, chipRadiusPx, chipPaint);

        if (hasAvatar) {
            float avatarTop = top + (chipHeight - avatarDiameterPx) / 2f;
            avatarRect.set(left + chipPadHPx, avatarTop,
                    left + chipPadHPx + avatarDiameterPx, avatarTop + avatarDiameterPx);
            drawAvatar(canvas);
        }

        float textLeft = left + chipPadHPx + avatarSpace;
        float baseline = top + (chipHeight - (fontMetrics.descent - fontMetrics.ascent)) / 2f - fontMetrics.ascent;
        canvas.drawText(label, textLeft, baseline, textPaint);
    }

    /** Center-cropped circular blit of {@link #avatarBitmap} into {@link #avatarRect}, cached BitmapShader. */
    private void drawAvatar(Canvas canvas) {
        Bitmap bmp = avatarBitmap;
        if (bmp == null || bmp.isRecycled()) return;

        if (avatarShader == null || lastShaderBitmap != bmp) {
            float scale = Math.max(avatarRect.width() / bmp.getWidth(), avatarRect.height() / bmp.getHeight());
            float dx = avatarRect.left - (bmp.getWidth() * scale - avatarRect.width()) / 2f;
            float dy = avatarRect.top - (bmp.getHeight() * scale - avatarRect.height()) / 2f;
            avatarShaderMatrix.reset();
            avatarShaderMatrix.setScale(scale, scale);
            avatarShaderMatrix.postTranslate(dx, dy);
            avatarShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            avatarShader.setLocalMatrix(avatarShaderMatrix);
            lastShaderBitmap = bmp;
        }
        avatarPaint.setShader(avatarShader);
        canvas.drawOval(avatarRect, avatarPaint);
    }
}
