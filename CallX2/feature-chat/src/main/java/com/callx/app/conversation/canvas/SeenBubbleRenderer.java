package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;

/**
 * Draws a "watched your reel" / "seen your status" system bubble — mirrors
 * item_reel_seen_bubble.xml / item_status_seen_bubble.xml: a circular
 * avatar (photo or placeholder) sitting to the LEFT of the card, then a
 * small rounded card (own solid colour per type) with an optional
 * thumbnail + play/eye overlay glyph, an icon + italic label row, an
 * optional sender name, and a small time line.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file split,
 * no behavior change) — bind/measure/touch logic for the seen bubble
 * stays on the host view; this class only owns the draw() call.
 */
final class SeenBubbleRenderer {

    private final MessageBubbleCanvasView host;

    SeenBubbleRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
        // ── Avatar (outside/left of the card) ──
        if (host.seenAvatarBitmap != null) {
            float scale = Math.max(host.seenAvatarRect.width() / host.seenAvatarBitmap.getWidth(),
                    host.seenAvatarRect.height() / host.seenAvatarBitmap.getHeight());
            float dx = host.seenAvatarRect.left - (host.seenAvatarBitmap.getWidth() * scale - host.seenAvatarRect.width()) / 2f;
            float dy = host.seenAvatarRect.top - (host.seenAvatarBitmap.getHeight() * scale - host.seenAvatarRect.height()) / 2f;
            host.seenAvatarShaderMatrix.reset();
            host.seenAvatarShaderMatrix.setScale(scale, scale);
            host.seenAvatarShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    host.seenAvatarBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(host.seenAvatarShaderMatrix);
            host.seenAvatarPaint.setShader(shader);
            canvas.drawOval(host.seenAvatarRect, host.seenAvatarPaint);
        } else {
            canvas.drawOval(host.seenAvatarRect, host.seenAvatarPlaceholderPaint);
        }

        // ── Card ──
        float r = MessageBubbleCanvasView.SEEN_CARD_CORNER_DP * host.density;
        host.seenCardBgPaint.setColor(host.seenIsReel ? MessageBubbleCanvasView.SEEN_REEL_BG_COLOR : MessageBubbleCanvasView.SEEN_STATUS_BG_COLOR);
        canvas.save();
        android.graphics.Path clipPath = new android.graphics.Path();
        clipPath.addRoundRect(host.seenCardRect, r, r, android.graphics.Path.Direction.CW);
        canvas.clipPath(clipPath);
        canvas.drawRect(host.seenCardRect, host.seenCardBgPaint);

        float padH = MessageBubbleCanvasView.SEEN_CARD_PAD_H_DP * host.density;
        float padEnd = MessageBubbleCanvasView.SEEN_CARD_PAD_END_DP * host.density;
        float left = host.seenCardRect.left + padH;
        float right = host.seenCardRect.right - padEnd;
        float cursorY = host.seenCardRect.top + MessageBubbleCanvasView.SEEN_CARD_PAD_TOP_DP * host.density;

        // ── Optional thumbnail + play/eye overlay ──
        if (host.seenHasThumb) {
            canvas.drawRect(host.seenThumbRect, host.seenThumbBgPaint);
            if (host.seenThumbBitmap != null) {
                float scale = Math.max(host.seenThumbRect.width() / host.seenThumbBitmap.getWidth(),
                        host.seenThumbRect.height() / host.seenThumbBitmap.getHeight());
                float dx = host.seenThumbRect.left - (host.seenThumbBitmap.getWidth() * scale - host.seenThumbRect.width()) / 2f;
                float dy = host.seenThumbRect.top - (host.seenThumbBitmap.getHeight() * scale - host.seenThumbRect.height()) / 2f;
                host.seenThumbShaderMatrix.reset();
                host.seenThumbShaderMatrix.setScale(scale, scale);
                host.seenThumbShaderMatrix.postTranslate(dx, dy);
                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                        host.seenThumbBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                shader.setLocalMatrix(host.seenThumbShaderMatrix);
                host.seenThumbPaint.setShader(shader);
                canvas.drawRect(host.seenThumbRect, host.seenThumbPaint);
            }
            String overlayGlyph = host.seenIsReel ? MessageBubbleCanvasView.SEEN_REEL_PLAY_GLYPH : MessageBubbleCanvasView.SEEN_STATUS_EYE_GLYPH;
            canvas.drawText(overlayGlyph, host.seenThumbRect.centerX(),
                    host.seenThumbRect.centerY() - (host.seenOverlayIconPaint.ascent() + host.seenOverlayIconPaint.descent()) / 2f,
                    host.seenOverlayIconPaint);
            cursorY = host.seenThumbRect.bottom + MessageBubbleCanvasView.SEEN_THUMB_MARGIN_BOTTOM_DP * host.density;
        }

        // ── Icon + italic label row ──
        Paint.FontMetrics ifm = host.seenIconPaint.getFontMetrics();
        Paint.FontMetrics lfm = host.seenLabelPaint.getFontMetrics();
        float rowH = Math.max(ifm.descent - ifm.ascent, lfm.descent - lfm.ascent);
        float rowCenterY = cursorY + rowH / 2f;
        String iconGlyph = host.seenIsReel ? MessageBubbleCanvasView.SEEN_REEL_ICON_GLYPH : MessageBubbleCanvasView.SEEN_STATUS_ICON_GLYPH;
        canvas.drawText(iconGlyph, left, rowCenterY - (ifm.ascent + ifm.descent) / 2f, host.seenIconPaint);
        float labelX = left + host.seenIconPaint.measureText(iconGlyph) + MessageBubbleCanvasView.SEEN_ICON_LABEL_GAP_DP * host.density;
        String labelText = host.seenIsReel ? MessageBubbleCanvasView.SEEN_REEL_LABEL_TEXT : MessageBubbleCanvasView.SEEN_STATUS_LABEL_TEXT;
        String labelToDraw = TextUtils.ellipsize(labelText, host.seenLabelPaint,
                Math.max(1, right - labelX), TextUtils.TruncateAt.END).toString();
        canvas.drawText(labelToDraw, labelX, rowCenterY - (lfm.ascent + lfm.descent) / 2f, host.seenLabelPaint);
        cursorY += rowH;

        // ── Optional sender name (groups) ──
        if (host.seenHasName) {
            Paint.FontMetrics nfm = host.seenNamePaint.getFontMetrics();
            cursorY += MessageBubbleCanvasView.SEEN_NAME_GAP_TOP_DP * host.density;
            String nameToDraw = TextUtils.ellipsize(host.seenName, host.seenNamePaint,
                    Math.max(1, right - left), TextUtils.TruncateAt.END).toString();
            canvas.drawText(nameToDraw, left, cursorY - nfm.ascent, host.seenNamePaint);
            cursorY += (nfm.descent - nfm.ascent);
        }

        // ── Time line ──
        Paint.FontMetrics tfm = host.seenTimePaint.getFontMetrics();
        cursorY += MessageBubbleCanvasView.SEEN_TIME_GAP_TOP_DP * host.density;
        canvas.drawText(host.footerTimeText, left, cursorY - tfm.ascent, host.seenTimePaint);

        canvas.restore();
    }
}
