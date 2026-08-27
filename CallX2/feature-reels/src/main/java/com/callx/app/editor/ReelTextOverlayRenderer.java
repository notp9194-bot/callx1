package com.callx.app.editor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.LinearGradient;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/**
 * Builds a real, sharp {@link TextView} for a "type":"text" sticker_json entry at
 * PLAYBACK time (ReelPlayerFragment for video reels, ReelPhotoSlideshowAdapter for
 * photo reels) — the Instagram-style "overlay/layer" approach:
 *
 *   text is stored as styling metadata (position/font/color/size/etc, see
 *   ReelEditorActivity#mergeTextOverlaysIntoStickerJson) and rendered fresh on
 *   top of the player at full sharpness, instead of being burned into the
 *   video/photo's pixels before Cloudinary compression — which is what used to
 *   make the text go blurry/pixelated together with the rest of the compressed
 *   media. The media itself is still free to compress as much as it needs to;
 *   only the text is kept off the pixel path.
 *
 * Mirrors ReelEditorActivity's private applyStyleToView()/resolvePreviewTypeface()/
 * applyFontFlourish() so what plays back looks identical to what the user styled
 * in the editor. Duplicated here (rather than reusing those private methods)
 * so playback has no dependency on the editor Activity's state.
 */
public final class ReelTextOverlayRenderer {

    private static final float OUTLINE_WIDTH_DP = 2.5f;

    private ReelTextOverlayRenderer() {}

    /** Builds a styled, positioned-and-ready TextView for one parsed overlay item.
     *  Caller is responsible for adding it to a FrameLayout and placing it at
     *  {@code item.x}/{@code item.y} (fractions of the layer's width/height,
     *  anchored at the text block's CENTER — matching how the editor recorded
     *  the position and how the hard-bake exporter draws it). */
    public static View build(Context ctx, ReelVideoExportEngine.OverlayItem item) {
        int dp = (int) ctx.getResources().getDisplayMetrics().density;
        StyledTextView tv = new StyledTextView(ctx);
        tv.setText(item.text);
        tv.setTextSize(item.textSizeSp);
        tv.setTypeface(resolveTypeface(item.fontKey, item.bold, item.italic));
        tv.setGravity("left".equals(item.align) ? Gravity.START
            : "right".equals(item.align) ? Gravity.END : Gravity.CENTER);

        boolean highlight = "highlight".equals(item.bgStyle);
        int textColor = item.color;
        if (highlight) {
            double luminance = 0.299 * Color.red(item.color) + 0.587 * Color.green(item.color) + 0.114 * Color.blue(item.color);
            textColor = luminance > 150 ? Color.BLACK : Color.WHITE;
        }
        tv.setTextColor(textColor);
        tv.setPadding(10 * dp, 6 * dp, 10 * dp, 6 * dp);

        if ("none".equals(item.bgStyle)) {
            tv.setShadowLayer(4f, 0f, 2f, 0x99000000);
            tv.setBackground(null);
        } else {
            tv.setShadowLayer(0f, 0f, 0f, 0);
            GradientDrawable bg = new GradientDrawable();
            if (highlight) {
                bg.setColor(item.color);
                bg.setCornerRadius(6f * dp);
            } else if ("solid".equals(item.bgStyle)) {
                bg.setColor(0xEE000000);
                bg.setCornerRadius(6f * dp);
            } else { // pill
                bg.setColor(0x66000000);
                bg.setCornerRadius(999f);
            }
            tv.setBackground(bg);
        }

        applyFontFlourish(tv, item.fontKey, dp);

        tv.setGradientFill(item.gradientEnabled, item.gradientStart, item.gradientEnd);
        tv.setOutline(item.outlineEnabled, item.outlineColor, OUTLINE_WIDTH_DP * dp);

        tv.setRotation(item.rotationDeg);
        tv.setScaleX(item.scale);
        tv.setScaleY(item.scale);
        return tv;
    }

    private static Typeface resolveTypeface(String fontKey, boolean bold, boolean italic) {
        Typeface base;
        if ("serif".equals(fontKey)) base = Typeface.SERIF;
        else if ("mono".equals(fontKey)) base = Typeface.MONOSPACE;
        else if ("condensed".equals(fontKey)) base = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
        else if ("strong".equals(fontKey)) base = Typeface.create("sans-serif-black", Typeface.NORMAL);
        else if ("neon".equals(fontKey)) base = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
        else if ("typewriter".equals(fontKey)) base = Typeface.MONOSPACE;
        else base = Typeface.SANS_SERIF;

        int style = Typeface.NORMAL;
        if (bold && italic) style = Typeface.BOLD_ITALIC;
        else if (bold) style = Typeface.BOLD;
        else if (italic) style = Typeface.ITALIC;
        return Typeface.create(base, style);
    }

    private static void applyFontFlourish(TextView tv, String fontKey, int dp) {
        if ("neon".equals(fontKey)) {
            tv.setLetterSpacing(0.05f);
            tv.setShadowLayer(16f * dp, 0f, 0f, tv.getCurrentTextColor());
        } else if ("typewriter".equals(fontKey)) {
            tv.setLetterSpacing(0.08f);
        } else if ("strong".equals(fontKey)) {
            tv.setLetterSpacing(0.01f);
        } else {
            tv.setLetterSpacing(0f);
        }
    }

    /** TextView subclass adding gradient/multi-colour fill + outline/stroke —
     *  a playback-side twin of ReelEditorActivity's private StyledOverlayTextView. */
    private static class StyledTextView extends TextView {
        private int[] gradientColors;
        private boolean outlineEnabled;
        private int outlineColor = Color.BLACK;
        private float outlineWidthPx;

        StyledTextView(Context ctx) { super(ctx); }

        void setGradientFill(boolean enabled, int startColor, int endColor) {
            gradientColors = enabled ? new int[]{startColor, endColor} : null;
            rebuildGradientShader();
        }

        void setOutline(boolean enabled, int color, float widthPx) {
            this.outlineEnabled = enabled;
            this.outlineColor = color;
            this.outlineWidthPx = widthPx;
            invalidate();
        }

        private void rebuildGradientShader() {
            if (gradientColors != null && getWidth() > 0) {
                getPaint().setShader(new LinearGradient(
                    0f, 0f, getWidth(), 0f, gradientColors, null, Shader.TileMode.CLAMP));
            } else {
                getPaint().setShader(null);
            }
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rebuildGradientShader();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (outlineEnabled) {
                TextPaint tp = getPaint();
                int savedColor = tp.getColor();
                Paint.Style savedStyle = tp.getStyle();
                float savedStrokeWidth = tp.getStrokeWidth();
                Shader savedShader = tp.getShader();

                tp.setStyle(Paint.Style.STROKE);
                tp.setStrokeWidth(outlineWidthPx);
                tp.setStrokeJoin(Paint.Join.ROUND);
                tp.setStrokeMiter(2f);
                tp.setColor(outlineColor);
                tp.setShader(null);
                super.onDraw(canvas);

                tp.setStyle(savedStyle);
                tp.setStrokeWidth(savedStrokeWidth);
                tp.setColor(savedColor);
                tp.setShader(savedShader);
            }
            super.onDraw(canvas);
        }
    }
}
