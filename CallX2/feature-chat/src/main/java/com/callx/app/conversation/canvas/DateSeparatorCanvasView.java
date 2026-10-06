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
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;

import com.callx.app.utils.ChatThemeManager;

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
 * Long labels wrap onto multiple centered lines (chip max ~85% of the row
 * width) and the chip follows the light/dark theme, like WhatsApp.
 *
 * Feature 8: this row is also reused (via MessagePagingAdapter's
 * TYPE_DATE_SEPARATOR routing for every "system" row) to show a
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
    // Dark theme: solid #3A3A4A + white text (unchanged). Light theme: soft
    // white pill + muted slate text, like WhatsApp's light system chips.
    private static final int CHIP_COLOR_DARK = 0xFF3A3A4A;
    private static final int TEXT_COLOR_DARK = Color.WHITE;
    private static final int CHIP_COLOR_LIGHT = 0xF2FFFFFF;
    private static final int TEXT_COLOR_LIGHT = 0xFF54656F;
    // Long system lines (e.g. "X changed this group's settings to allow only
    // admins to send messages") wrap onto extra lines instead of running off
    // screen; the chip never grows wider than this share of the row.
    private static final float MAX_CHIP_WIDTH_FRACTION = 0.85f;
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
    // Wrapped label. Rebuilt only when the label or available width changes.
    @Nullable private StaticLayout textLayout;
    private int layoutWidthPx = -1;
    private float textWidth = 0f;   // widest wrapped line, px
    private float textHeight = 0f;  // total wrapped height, px
    private boolean darkTheme = true;

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

        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP,
                getResources().getDisplayMetrics()));
        textPaint.getFontMetrics(fontMetrics);
        applyTheme();

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
        textLayout = null; // rebuilt in onMeasure for the current width
        layoutWidthPx = -1;
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

    /** Picks chip/text colours for the current light/dark mode. */
    private void applyTheme() {
        darkTheme = ChatThemeManager.isDarkMode(getContext());
        chipPaint.setColor(darkTheme ? CHIP_COLOR_DARK : CHIP_COLOR_LIGHT);
        textPaint.setColor(darkTheme ? TEXT_COLOR_DARK : TEXT_COLOR_LIGHT);
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyTheme();
        invalidate();
    }

    /** Width available to the wrapped text for a row of {@code viewWidth} px. */
    private int textMaxWidth(int viewWidth) {
        float avatarSpace = avatarBitmap != null ? avatarDiameterPx + avatarGapPx : 0f;
        int w = Math.round(viewWidth * MAX_CHIP_WIDTH_FRACTION - chipPadHPx * 2f - avatarSpace);
        return Math.max(w, 1);
    }

    /** (Re)builds the wrapped layout when the label or available width changed. */
    private void ensureLayout(int viewWidth) {
        int maxW = textMaxWidth(viewWidth);
        if (textLayout != null && layoutWidthPx == maxW) return;
        layoutWidthPx = maxW;
        if (label.isEmpty()) {
            textLayout = null;
            textWidth = 0f;
            textHeight = 0f;
            return;
        }
        StaticLayout.Builder b = StaticLayout.Builder.obtain(label, 0, label.length(), textPaint, maxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false);
        textLayout = b.build();
        float widest = 0f;
        for (int i = 0; i < textLayout.getLineCount(); i++) {
            widest = Math.max(widest, textLayout.getLineWidth(i));
        }
        textWidth = widest;
        textHeight = textLayout.getHeight();
    }

    private float chipHeightPx() {
        float textBlock = (float) Math.ceil(textHeight) + chipPadVPx * 2f;
        float avatarBlock = avatarBitmap != null ? avatarDiameterPx + chipPadVPx * 2f : 0f;
        return Math.max(textBlock, avatarBlock);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        ensureLayout(width);
        int height = outerPadVPx + Math.round(chipHeightPx()) + outerPadVPx;
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (label.isEmpty()) return;
        ensureLayout(getWidth());
        if (textLayout == null) return;

        boolean hasAvatar = avatarBitmap != null;
        float avatarSpace = hasAvatar ? avatarDiameterPx + avatarGapPx : 0f;

        int viewWidth = getWidth();
        float chipWidth = textWidth + chipPadHPx * 2f + avatarSpace;
        float chipHeight = chipHeightPx();
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

        // Wrapped text block is centered inside the chip's text area (right of
        // the avatar). The layout is textMaxWidth wide and ALIGN_CENTER, so
        // shift it so the widest line sits exactly between the chip paddings.
        float textAreaLeft = left + chipPadHPx + avatarSpace;
        float textAreaWidth = textWidth;
        float layoutLeft = textAreaLeft + (textAreaWidth - layoutWidthPx) / 2f;
        float layoutTop = top + (chipHeight - textHeight) / 2f;
        int save = canvas.save();
        canvas.translate(layoutLeft, layoutTop);
        textLayout.draw(canvas);
        canvas.restoreToCount(save);
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
