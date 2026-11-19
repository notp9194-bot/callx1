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
import android.util.LruCache;
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
    // Long system lines wrap onto extra lines; the chip never grows wider than this share of the row.
    private static final float MAX_CHIP_WIDTH_FRACTION = 0.85f;
    private static final float AVATAR_DIAMETER_DP = 16f;
    private static final float AVATAR_GAP_DP = 5f;
    // Date rows ("Today"): fully-rounded pill with a hairline rule on each side.
    private static final float RULE_PAD_H_DP = 14f;
    private static final float RULE_PAD_V_DP = 5f;
    private static final float RULE_STROKE_DP = 1f;
    private static final float RULE_EDGE_INSET_DP = 12f;
    private static final float RULE_GAP_DP = 10f;
    private static final int RULE_ALPHA = 0x55;

    // ── Process-wide shared state ────────────────────────────────────────────
    // Every separator row looks identical apart from its label, so the Paints, px constants, font
    // metrics and measured/wrapped text are shared by ALL instances (UI-thread only). Opening a chat
    // therefore allocates nothing per row except the View itself, and a repeated label ("Today",
    // "Yesterday", "Nov 18") is measured/wrapped once for the whole process.
    private static final TextPaint TEXT = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint CHIP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint RULE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint AVATAR = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF SCRATCH = new RectF();
    private static final Paint.FontMetrics FM = new Paint.FontMetrics();
    private static final Matrix SHADER_MATRIX = new Matrix();
    private static final LruCache<String, Float> LABEL_WIDTH = new LruCache<>(64);
    private static final LruCache<String, Wrapped> WRAPPED = new LruCache<>(24);
    private static float sMetricsKey = -1f;
    private static int sOuterPadV, sPadH, sPadV, sRadius, sAvatarD, sAvatarGap;
    private static int sRulePadH, sRulePadV, sRuleInset, sRuleGap;
    private static float sLineHeight, sAscent;
    private static boolean sThemeApplied = false, sThemeDark = true;

    private static final class Wrapped {
        final StaticLayout layout; final float widest; final float height;
        Wrapped(StaticLayout l, float w, float h) { layout = l; widest = w; height = h; }
    }

    /** Recomputes px constants/text metrics only when density or font scale actually changed. */
    private static void ensureStatics(Context c) {
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        float key = dm.density * 1000f + c.getResources().getConfiguration().fontScale;
        if (key == sMetricsKey) return;
        sMetricsKey = key;
        float d = dm.density;
        sOuterPadV = (int) (OUTER_PAD_V_DP * d);
        sPadH = (int) (CHIP_PAD_H_DP * d);
        sPadV = (int) (CHIP_PAD_V_DP * d);
        sRadius = (int) (CHIP_RADIUS_DP * d);
        sAvatarD = Math.round(AVATAR_DIAMETER_DP * d);
        sAvatarGap = Math.round(AVATAR_GAP_DP * d);
        sRulePadH = Math.round(RULE_PAD_H_DP * d);
        sRulePadV = Math.round(RULE_PAD_V_DP * d);
        sRuleInset = Math.round(RULE_EDGE_INSET_DP * d);
        sRuleGap = Math.round(RULE_GAP_DP * d);
        CHIP.setStyle(Paint.Style.FILL);
        AVATAR.setStyle(Paint.Style.FILL);
        RULE.setStyle(Paint.Style.STROKE);
        RULE.setStrokeWidth(Math.max(1f, RULE_STROKE_DP * d));
        RULE.setStrokeCap(Paint.Cap.ROUND);
        TEXT.setFakeBoldText(true);
        TEXT.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, dm));
        TEXT.getFontMetrics(FM);
        sAscent = FM.ascent;
        sLineHeight = (float) Math.ceil(FM.descent - FM.ascent);
        LABEL_WIDTH.evictAll();   // widths depend on the text size
        WRAPPED.evictAll();
    }

    /** Applies chip/text/rule colours to the shared paints (all rows agree on the theme). */
    private static void applyThemeStatics(Context c, boolean force) {
        boolean dark = ChatThemeManager.isDarkMode(c);
        if (!force && sThemeApplied && dark == sThemeDark) return;
        sThemeApplied = true;
        sThemeDark = dark;
        int text = dark ? TEXT_COLOR_DARK : TEXT_COLOR_LIGHT;
        CHIP.setColor(dark ? CHIP_COLOR_DARK : CHIP_COLOR_LIGHT);
        TEXT.setColor(text);
        RULE.setColor((text & 0x00FFFFFF) | (RULE_ALPHA << 24));
    }

    // ── Per-instance state (kept to the bare minimum) ────────────────────────
    private String label = "";
    private boolean showRules = false;
    @Nullable private Bitmap avatarBitmap;
    @Nullable private BitmapShader avatarShader;
    @Nullable private Bitmap lastShaderBitmap;

    // Content (depends on label / avatar / rules / width) — rebuilt only when one of those changes.
    private boolean contentValid = false;
    private int contentWidth = -1;
    @Nullable private Wrapped wrapped;   // null → single-line fast path (no StaticLayout at all)
    private float textW, textH, chipH;
    private int wrapMaxW;

    // Geometry (depends on content) — computed once, then onDraw is just a handful of draw calls.
    private boolean geomValid = false;
    private int geomVersion = 0, shaderGeomVersion = -1;
    private float chipL, chipT, chipR, chipB, chipRadius;
    private float textX, textBaseline;           // single-line
    private float wrapX, wrapY;                  // wrapped layout origin
    private float ruleY, ruleLeftX1, ruleLeftX2, ruleRightX1, ruleRightX2;
    private boolean drawRuleLeft, drawRuleRight;
    private float avL, avT;

    public DateSeparatorCanvasView(Context context) { super(context); init(); }
    public DateSeparatorCanvasView(Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public DateSeparatorCanvasView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        ensureStatics(getContext());
        applyThemeStatics(getContext(), false);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setSaveEnabled(false);
    }

    private void invalidateContent() {
        contentValid = false;
        geomValid = false;
    }

    /** @param text e.g. "Today", "Yesterday", or "MMM d". No-op (no relayout, no redraw) when unchanged. */
    public void setLabel(@Nullable String text) {
        String next = text != null ? text : "";
        if (next.equals(label)) return;
        label = next;
        invalidateContent();
        requestLayout();
        invalidate();
    }

    /** Join/leave member avatar drawn left of the label; null hides it. No-op when unchanged. */
    public void setAvatar(@Nullable Bitmap bitmap) {
        Bitmap next = (bitmap != null && !bitmap.isRecycled()) ? bitmap : null;
        if (next == avatarBitmap) return;
        avatarBitmap = next;
        invalidateContent();
        requestLayout();
        invalidate();
    }

    /** Date rows only: pill + side rules. Must be set on every bind (rows are recycled); no-op when unchanged. */
    public void setShowRules(boolean show) {
        if (show == showRules) return;
        showRules = show;
        invalidateContent();
        requestLayout();
        invalidate();
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ensureStatics(getContext());
        applyThemeStatics(getContext(), true);
        invalidateContent();
        requestLayout();
        invalidate();
    }

    private int padH() { return showRules ? sRulePadH : sPadH; }
    private int padV() { return showRules ? sRulePadV : sPadV; }

    /** Builds text metrics for {@code viewWidth}. Single-line labels never touch StaticLayout. */
    private void ensureContent(int viewWidth) {
        if (contentValid && contentWidth == viewWidth) return;
        contentValid = true;
        contentWidth = viewWidth;
        geomValid = false;
        wrapped = null;
        float avatarSpace = avatarBitmap != null ? sAvatarD + sAvatarGap : 0f;
        wrapMaxW = Math.max(Math.round(viewWidth * MAX_CHIP_WIDTH_FRACTION - padH() * 2f - avatarSpace), 1);
        if (label.isEmpty()) {
            textW = 0f; textH = 0f;
        } else {
            Float cachedW = LABEL_WIDTH.get(label);
            float w;
            if (cachedW != null) w = cachedW;
            else { w = TEXT.measureText(label); LABEL_WIDTH.put(label, w); }
            if (w <= wrapMaxW && label.indexOf('\n') < 0) {
                textW = w;
                textH = sLineHeight;
            } else {
                final String key = label + '\u0001' + wrapMaxW;
                Wrapped wr = WRAPPED.get(key);
                if (wr == null) {
                    StaticLayout l = StaticLayout.Builder.obtain(label, 0, label.length(), TEXT, wrapMaxW)
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setIncludePad(false)
                            .build();
                    float widest = 0f;
                    for (int i = 0; i < l.getLineCount(); i++) widest = Math.max(widest, l.getLineWidth(i));
                    wr = new Wrapped(l, widest, l.getHeight());
                    WRAPPED.put(key, wr);
                }
                wrapped = wr;
                textW = wr.widest;
                textH = wr.height;
            }
        }
        float textBlock = (float) Math.ceil(textH) + padV() * 2f;
        float avatarBlock = avatarBitmap != null ? sAvatarD + padV() * 2f : 0f;
        chipH = Math.max(textBlock, avatarBlock);
    }

    /** Pre-computes every coordinate onDraw needs. Runs once per content/size change. */
    private void ensureGeometry(int viewWidth) {
        if (geomValid) return;
        geomValid = true;
        geomVersion++;
        boolean hasAvatar = avatarBitmap != null;
        float avatarSpace = hasAvatar ? sAvatarD + sAvatarGap : 0f;
        float chipW = textW + padH() * 2f + avatarSpace;
        chipL = (viewWidth - chipW) / 2f;
        chipT = sOuterPadV;
        chipR = chipL + chipW;
        chipB = chipT + chipH;
        chipRadius = showRules ? chipH / 2f : sRadius;
        if (hasAvatar) {
            avL = chipL + padH();
            avT = chipT + (chipH - sAvatarD) / 2f;
        }
        float textAreaLeft = chipL + padH() + avatarSpace;
        if (wrapped == null) {
            textX = textAreaLeft;
            // Single line is vertically centred in the chip: baseline = top + (chipH - lineH)/2 - ascent.
            textBaseline = chipT + (chipH - textH) / 2f - sAscent;
        } else {
            wrapX = textAreaLeft + (textW - wrapMaxW) / 2f;
            wrapY = chipT + (chipH - textH) / 2f;
        }
        if (showRules) {
            ruleY = (chipT + chipB) / 2f;
            ruleLeftX1 = sRuleInset;
            ruleLeftX2 = chipL - sRuleGap;
            ruleRightX1 = chipR + sRuleGap;
            ruleRightX2 = viewWidth - sRuleInset;
            drawRuleLeft = ruleLeftX2 > ruleLeftX1;
            drawRuleRight = ruleRightX2 > ruleRightX1;
        } else {
            drawRuleLeft = drawRuleRight = false;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        ensureContent(width);
        int height = sOuterPadV + Math.round(chipH) + sOuterPadV;
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (label.isEmpty()) return;
        final int w = getWidth();
        ensureContent(w);
        ensureGeometry(w);

        if (drawRuleLeft) canvas.drawLine(ruleLeftX1, ruleY, ruleLeftX2, ruleY, RULE);
        if (drawRuleRight) canvas.drawLine(ruleRightX1, ruleY, ruleRightX2, ruleY, RULE);

        SCRATCH.set(chipL, chipT, chipR, chipB);
        canvas.drawRoundRect(SCRATCH, chipRadius, chipRadius, CHIP);

        if (avatarBitmap != null) drawAvatar(canvas);

        if (wrapped == null) {
            canvas.drawText(label, textX, textBaseline, TEXT);
        } else {
            int save = canvas.save();
            canvas.translate(wrapX, wrapY);
            wrapped.layout.draw(canvas);
            canvas.restoreToCount(save);
        }
    }

    /** Center-cropped circular blit; the BitmapShader is built once per bitmap, re-matrixed only if the geometry moved. */
    private void drawAvatar(Canvas canvas) {
        Bitmap bmp = avatarBitmap;
        if (bmp == null || bmp.isRecycled()) return;
        SCRATCH.set(avL, avT, avL + sAvatarD, avT + sAvatarD);
        if (avatarShader == null || lastShaderBitmap != bmp) {
            avatarShader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            lastShaderBitmap = bmp;
            shaderGeomVersion = -1;
        }
        if (shaderGeomVersion != geomVersion) {
            float scale = Math.max(sAvatarD / (float) bmp.getWidth(), sAvatarD / (float) bmp.getHeight());
            float dx = avL - (bmp.getWidth() * scale - sAvatarD) / 2f;
            float dy = avT - (bmp.getHeight() * scale - sAvatarD) / 2f;
            SHADER_MATRIX.reset();
            SHADER_MATRIX.setScale(scale, scale);
            SHADER_MATRIX.postTranslate(dx, dy);
            avatarShader.setLocalMatrix(SHADER_MATRIX);
            shaderGeomVersion = geomVersion;
        }
        AVATAR.setShader(avatarShader);
        canvas.drawOval(SCRATCH, AVATAR);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Recycled rows must not pin a (possibly large) avatar bitmap or shader while detached.
        avatarShader = null;
        lastShaderBitmap = null;
        shaderGeomVersion = -1;
    }
}
