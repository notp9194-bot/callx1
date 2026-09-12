package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Draws the location-share card — mirrors item_msg_location.xml: a
 * bubbleless rounded card with a purple map-thumbnail header (pin
 * placeholder when no map bitmap is available), a translucent purple
 * address strip (encoded via StaticLayout), and a bottom "Open in Maps"
 * row — same rounded-card clip treatment as ContactRenderer.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file split,
 * no behavior change) — bind/measure/touch logic for the location card
 * stays on the host view; this class only owns the draw() call.
 */
final class LocationRenderer {

    private final MessageBubbleCanvasView host;

    LocationRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    // PERF: reused across every draw() instead of the no-arg
    // paint.getFontMetrics(), which allocates a new FontMetrics object
    // every call — same GC-pressure-at-60fps class of bug already fixed
    // for the reactions badge / audio waveform.
    private final Paint.FontMetrics buttonFm = new Paint.FontMetrics();

    // PERF: cached rounded-rect clip path — was `new Path()` +
    // addRoundRect() on every single draw() during scroll. Same
    // recompute-on-change pattern as ReelShareRenderer's clipPath: only
    // rebuilt when locationCardRect's bounds or the corner radius change.
    private final android.graphics.Path clipPath = new android.graphics.Path();
    private float lastClipLeft = Float.NaN, lastClipTop, lastClipRight, lastClipBottom, lastClipR;

    private void ensureClipPath(float r) {
        android.graphics.RectF rect = host.locationCardRect;
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

    // PERF: cached map BitmapShader — was `new BitmapShader(...)` on every
    // single draw() while a decoded map bitmap is showing. Same pattern as
    // MediaGroupRenderer's cellShaders: rebuilt only when the bitmap
    // reference or its scale/translate actually changed since the last draw.
    private android.graphics.BitmapShader mapShader;
    private android.graphics.Bitmap lastMapBitmap;
    private float lastMapScale = Float.NaN, lastMapDx, lastMapDy;

    void draw(Canvas canvas) {
        float r = MessageBubbleCanvasView.LOCATION_CORNER_RADIUS_DP * host.density;
        canvas.save();
        ensureClipPath(r);
        canvas.clipPath(clipPath);

        // ── Map header ──
        canvas.drawRect(host.locationMapRect, host.locationMapBgPaint);
        if (host.locationMapBitmap != null) {
            float scale = Math.max(host.locationMapRect.width() / host.locationMapBitmap.getWidth(),
                    host.locationMapRect.height() / host.locationMapBitmap.getHeight());
            float dx = host.locationMapRect.left - (host.locationMapBitmap.getWidth() * scale - host.locationMapRect.width()) / 2f;
            float dy = host.locationMapRect.top - (host.locationMapBitmap.getHeight() * scale - host.locationMapRect.height()) / 2f;

            if (mapShader == null || lastMapBitmap != host.locationMapBitmap
                    || scale != lastMapScale || dx != lastMapDx || dy != lastMapDy) {
                host.locationMapShaderMatrix.reset();
                host.locationMapShaderMatrix.setScale(scale, scale);
                host.locationMapShaderMatrix.postTranslate(dx, dy);
                mapShader = new android.graphics.BitmapShader(
                        host.locationMapBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                mapShader.setLocalMatrix(host.locationMapShaderMatrix);
                lastMapBitmap = host.locationMapBitmap;
                lastMapScale = scale;
                lastMapDx = dx;
                lastMapDy = dy;
            }
            host.locationMapBitmapPaint.setShader(mapShader);
            canvas.drawRect(host.locationMapRect, host.locationMapBitmapPaint);
        } else {
            // Placeholder pin — teardrop + circular head, matches
            // ic_location_pin's silhouette closely enough at this size.
            float pinSize = MessageBubbleCanvasView.LOCATION_PIN_SIZE_DP * host.density;
            float cx = host.locationMapRect.centerX();
            float cy = host.locationMapRect.centerY() - pinSize * 0.15f;
            host.locationPinPath.reset();
            host.locationPinPath.addCircle(cx, cy, pinSize * 0.32f, android.graphics.Path.Direction.CW);
            host.locationPinPath.moveTo(cx - pinSize * 0.32f, cy + pinSize * 0.08f);
            host.locationPinPath.lineTo(cx, cy + pinSize * 0.62f);
            host.locationPinPath.lineTo(cx + pinSize * 0.32f, cy + pinSize * 0.08f);
            host.locationPinPath.close();
            canvas.drawPath(host.locationPinPath, host.locationPinPaint);
        }

        // ── Divider under the map ──
        float dividerH = MessageBubbleCanvasView.LOCATION_DIVIDER_HEIGHT_DP * host.density;
        float divTop1 = host.locationMapRect.bottom;
        canvas.drawRect(host.locationCardRect.left, divTop1, host.locationCardRect.right, divTop1 + dividerH, host.locationDividerPaint);

        // ── Address strip ──
        float addrTop = divTop1 + dividerH;
        float addrBottom = host.locationButtonRect.top - dividerH;
        canvas.drawRect(host.locationCardRect.left, addrTop, host.locationCardRect.right, addrBottom, host.locationAddressBgPaint);
        if (host.locationAddressLayout != null) {
            canvas.save();
            canvas.translate(host.locationCardRect.left + MessageBubbleCanvasView.LOCATION_ADDRESS_PAD_H_DP * host.density,
                    addrTop + MessageBubbleCanvasView.LOCATION_ADDRESS_PAD_TOP_DP * host.density);
            host.locationAddressLayout.draw(canvas);
            canvas.restore();
        }

        // ── Divider above the button row ──
        canvas.drawRect(host.locationCardRect.left, addrBottom, host.locationCardRect.right, addrBottom + dividerH, host.locationDividerPaint);

        // ── "Open in Maps" row ──
        canvas.drawRect(host.locationButtonRect, host.locationButtonBgPaint);
        host.locationButtonTextPaint.getFontMetrics(buttonFm);
        Paint.FontMetrics lbfm = buttonFm;
        float locBtnBaselineY = host.locationButtonRect.centerY() - (lbfm.ascent + lbfm.descent) / 2f;
        canvas.drawText(MessageBubbleCanvasView.LOCATION_BUTTON_TEXT, host.locationButtonRect.centerX(), locBtnBaselineY, host.locationButtonTextPaint);

        canvas.restore();

        // ── Disappearing-message countdown — this card has no regular
        // timestamp/tick row (matches item_msg_location.xml having none),
        // so the expiry pill is a small floating badge over the map header
        // instead of sharing a footer row. Drawn after canvas.restore() so
        // it isn't clipped to the card's rounded shape. ──
        if (host.hasExpiry) {
            host.drawCornerExpiryPill(canvas, host.locationMapRect);
        }
    }
}
