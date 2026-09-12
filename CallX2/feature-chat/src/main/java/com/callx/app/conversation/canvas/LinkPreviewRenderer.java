package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Draws the link-preview card computed in onMeasure — rounded card
 * background (same #33000000/#22000000 sent/received treatment as the
 * reply-preview strip), an optional top thumbnail band (centerCrop
 * bitmap, or a plain placeholder box while it's still loading), then the
 * domain row and bold title underneath. Card tap handling lives in
 * onTouchEvent (linkCardRect hit-test) on the host.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file
 * split, no behavior change) — bind/measure/touch logic for the link
 * preview stays on the host view; this class only owns the draw() call.
 */
final class LinkPreviewRenderer {

    private final MessageBubbleCanvasView host;

    LinkPreviewRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    // PERF: reused across every draw() instead of the no-arg
    // paint.getFontMetrics(), which allocates a new FontMetrics object
    // every call — same GC-pressure-at-60fps class of bug already fixed
    // for the reactions badge / audio waveform.
    private final Paint.FontMetrics domainFm = new Paint.FontMetrics();

    // PERF: cached rounded-top-corner clip path — was `new Path()` +
    // addRoundRect() on every single draw() during scroll (both the
    // decoded-thumb and placeholder branches). Same recompute-on-change
    // pattern as ReelShareRenderer's clipPath: only rebuilt when
    // linkCardRect's bounds or the corner radius actually change.
    private final android.graphics.Path clipPath = new android.graphics.Path();
    private float lastClipLeft = Float.NaN, lastClipTop, lastClipRight, lastClipBottom, lastClipR;

    private void ensureClipPath(float r) {
        android.graphics.RectF rect = host.linkCardRect;
        if (rect.left == lastClipLeft && rect.top == lastClipTop && rect.right == lastClipRight
                && rect.bottom == lastClipBottom && r == lastClipR) {
            return;
        }
        clipPath.reset();
        clipPath.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW);
        lastClipLeft = rect.left;
        lastClipTop = rect.top;
        lastClipRight = rect.right;
        lastClipBottom = rect.bottom;
        lastClipR = r;
    }

    // PERF: cached thumb BitmapShader — was `new BitmapShader(...)` on
    // every single draw() while a decoded thumb is showing. Same pattern as
    // MediaGroupRenderer's cellShaders: rebuilt only when the bitmap
    // reference or its scale/translate actually changed since the last draw.
    private android.graphics.BitmapShader thumbShader;
    private android.graphics.Bitmap lastThumbBitmap;
    private float lastThumbScale = Float.NaN, lastThumbDx, lastThumbDy;

    void draw(Canvas canvas) {
        float r = MessageBubbleCanvasView.LINK_PREVIEW_CORNER_DP * host.density;
        canvas.drawRoundRect(host.linkCardRect, r, r, host.linkCardBgPaint);

        float textLeft = host.linkCardRect.left + MessageBubbleCanvasView.LINK_PREVIEW_PAD_H_DP * host.density;
        float textRight = host.linkCardRect.right - MessageBubbleCanvasView.LINK_PREVIEW_PAD_H_DP * host.density;
        float cursorY;

        if (host.linkHasThumb) {
            if (host.linkThumbBitmap != null) {
                // Rounded-top-corner centerCrop, same BitmapShader technique
                // drawMedia() uses for the single-image bubble — clipped to
                // the card's own round-rect so only the top two corners
                // actually round (bottom corners are covered by the text
                // column below, so a full round-rect clip reads correctly).
                float scale = Math.max(host.linkThumbRect.width() / host.linkThumbBitmap.getWidth(),
                        host.linkThumbRect.height() / host.linkThumbBitmap.getHeight());
                float dx = host.linkThumbRect.left - (host.linkThumbBitmap.getWidth() * scale - host.linkThumbRect.width()) / 2f;
                float dy = host.linkThumbRect.top - (host.linkThumbBitmap.getHeight() * scale - host.linkThumbRect.height()) / 2f;

                if (thumbShader == null || lastThumbBitmap != host.linkThumbBitmap
                        || scale != lastThumbScale || dx != lastThumbDx || dy != lastThumbDy) {
                    host.linkThumbShaderMatrix.reset();
                    host.linkThumbShaderMatrix.setScale(scale, scale);
                    host.linkThumbShaderMatrix.postTranslate(dx, dy);
                    thumbShader = new android.graphics.BitmapShader(
                            host.linkThumbBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                    thumbShader.setLocalMatrix(host.linkThumbShaderMatrix);
                    lastThumbBitmap = host.linkThumbBitmap;
                    lastThumbScale = scale;
                    lastThumbDx = dx;
                    lastThumbDy = dy;
                }
                host.linkThumbPaint.setShader(thumbShader);
                int saveCount = canvas.save();
                ensureClipPath(r);
                canvas.clipPath(clipPath);
                canvas.drawRect(host.linkThumbRect, host.linkThumbPaint);
                canvas.restoreToCount(saveCount);
            } else {
                // Not decoded yet — plain placeholder band, same rounded-top shape.
                int saveCount = canvas.save();
                ensureClipPath(r);
                canvas.clipPath(clipPath);
                canvas.drawRect(host.linkThumbRect, host.linkThumbPlaceholderPaint);
                canvas.restoreToCount(saveCount);
            }
            cursorY = host.linkThumbRect.bottom + MessageBubbleCanvasView.LINK_PREVIEW_PAD_TOP_DP * host.density;
        } else {
            cursorY = host.linkCardRect.top + MessageBubbleCanvasView.LINK_PREVIEW_PAD_TOP_DP * host.density;
        }

        if (!host.linkDomain.isEmpty()) {
            host.linkDomainPaint.getFontMetrics(domainFm);
            Paint.FontMetrics dfm = domainFm;
            float baselineY = cursorY - dfm.ascent;
            canvas.drawText(host.linkDomain.toUpperCase(java.util.Locale.getDefault()), textLeft, baselineY, host.linkDomainPaint);
            cursorY = baselineY + dfm.descent + MessageBubbleCanvasView.LINK_PREVIEW_TITLE_GAP_DP * host.density;
        }

        if (host.linkTitleLayout != null) {
            canvas.save();
            canvas.translate(textLeft, cursorY);
            host.linkTitleLayout.draw(canvas);
            canvas.restore();
        }
    }
}
