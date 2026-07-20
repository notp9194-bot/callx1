package com.callx.app.channel.canvas;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * Draws a link-preview card below the post text:
 *   • Optional OG-image thumbnail (match-width × thumbHeight, centerCrop, rounded top)
 *   • Domain label (small, gold/primary accent)
 *   • Bold 2-line title
 *   • Rounded card border
 *
 * All heavy work (StaticLayout, BitmapShader) is cached and only rebuilt when
 * inputs change — zero allocation in the draw path.
 */
final class ChannelPostLinkRenderer {

    private final ChannelPostCanvasView host;

    ChannelPostLinkRenderer(ChannelPostCanvasView host) {
        this.host = host;
    }

    // Geometry set by layout(), consumed by draw().
    final RectF cardRect  = new RectF();
    final RectF thumbRect = new RectF();
    private float domainBaselineY;
    private float titleTop;
    private float cardBottom;

    // StaticLayout caches
    private String lastTitle;
    private int    lastTitleW = -1;
    private StaticLayout titleLayout;

    private String lastDomain;

    // Thumbnail shader cache
    private BitmapShader thumbShaderCache;
    private Bitmap       thumbShaderBitmap;
    private float        thumbShaderW = -1f, thumbShaderH = -1f;
    private final Path   thumbClipPath = new Path();
    private float        thumbClipW = -1f, thumbClipH = -1f;

    /**
     * Inject a pre-built title StaticLayout from ChannelPostLayoutPrewarmer.
     * The next layout() call uses this directly instead of rebuilding.
     */
    void setPrebuiltTitleLayout(StaticLayout layout) {
        if (layout == null) return;
        titleLayout = layout;
        lastTitle   = null; // force re-check on width mismatch
        lastTitleW  = layout.getWidth();
    }

    /**
     * Computes all geometry starting at `top`. Returns the bottom y of the card.
     */
    float layout(float left, float top, float right) {
        float r = host.linkCardCornerRadius;
        cardRect.set(left, top, right, top); // will grow

        float y = top;
        boolean hasThumb = host.linkThumbBitmap != null
                || (host.linkImageUrl != null && !host.linkImageUrl.isEmpty());

        // Thumbnail zone
        if (hasThumb) {
            thumbRect.set(left, y, right, y + host.linkThumbHeight);
            y = thumbRect.bottom;
        } else {
            thumbRect.setEmpty();
        }

        // Text pad
        y += host.linkCardPadTop;

        // Domain
        if (host.linkDomain != null && !host.linkDomain.isEmpty()) {
            Paint tmpPaint = host.linkDomainPaint;
            android.graphics.Paint.FontMetrics fm = tmpPaint.getFontMetrics();
            domainBaselineY = y - fm.ascent;
            y += (fm.descent - fm.ascent) + host.linkTitleDomainGap;
        }

        // Title StaticLayout
        int titleW = (int) Math.max(1f, right - left - host.linkCardPadH * 2f);
        String titleText = host.linkTitle != null && !host.linkTitle.isEmpty()
                ? host.linkTitle : (host.linkUrl != null ? host.linkUrl : "");
        if (titleLayout == null || !titleText.equals(lastTitle) || titleW != lastTitleW) {
            TextPaint p = host.linkTitlePaint;
            titleLayout = StaticLayout.Builder
                    .obtain(titleText, 0, titleText.length(), p, titleW)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(false)
                    .setMaxLines(2)
                    .setEllipsize(android.text.TextUtils.TruncateAt.END)
                    .build();
            lastTitle  = titleText;
            lastTitleW = titleW;
        }
        titleTop = y;
        y += titleLayout.getHeight() + host.linkCardPadBottom;

        cardRect.bottom = y;
        cardBottom = y;
        return y;
    }

    void draw(Canvas canvas) {
        if (cardRect.isEmpty()) return;
        float r = host.linkCardCornerRadius;

        // Card background + border
        canvas.drawRoundRect(cardRect, r, r, host.linkCardBgPaint);
        canvas.drawRoundRect(cardRect, r, r, host.linkCardStrokePaint);

        // Thumbnail
        if (!thumbRect.isEmpty()) {
            float w = thumbRect.width(), h = thumbRect.height();
            if (thumbClipW != w || thumbClipH != h) {
                thumbClipPath.reset();
                // Round only the top corners
                float[] radii = {r, r, r, r, 0f, 0f, 0f, 0f};
                thumbClipPath.addRoundRect(thumbRect, radii, Path.Direction.CW);
                thumbClipW = w; thumbClipH = h;
            }
            canvas.save();
            canvas.clipPath(thumbClipPath);
            if (host.linkThumbBitmap != null) {
                Bitmap bmp = host.linkThumbBitmap;
                if (thumbShaderCache == null || thumbShaderBitmap != bmp
                        || thumbShaderW != w || thumbShaderH != h) {
                    float scale = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                    float dx = thumbRect.left - (bmp.getWidth()  * scale - w) / 2f;
                    float dy = thumbRect.top  - (bmp.getHeight() * scale - h) / 2f;
                    android.graphics.Matrix mx = new android.graphics.Matrix();
                    mx.setScale(scale, scale);
                    mx.postTranslate(dx, dy);
                    BitmapShader shader = new BitmapShader(
                            bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader.setLocalMatrix(mx);
                    thumbShaderCache  = shader;
                    thumbShaderBitmap = bmp;
                    thumbShaderW = w; thumbShaderH = h;
                }
                host.linkThumbPaint.setShader(thumbShaderCache);
                canvas.drawRect(thumbRect, host.linkThumbPaint);
            } else {
                host.linkThumbPaint.setShader(null);
                canvas.drawRect(thumbRect, host.linkThumbPlaceholderPaint);
            }
            canvas.restore();
        }

        float textLeft = cardRect.left + host.linkCardPadH;

        // Domain
        if (host.linkDomain != null && !host.linkDomain.isEmpty()) {
            canvas.drawText(host.linkDomain, textLeft, domainBaselineY, host.linkDomainPaint);
        }

        // Title
        if (titleLayout != null) {
            canvas.save();
            canvas.translate(textLeft, titleTop);
            titleLayout.draw(canvas);
            canvas.restore();
        }
    }

    float getCardBottom() { return cardBottom; }
}
