package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;

/**
 * Draws the reel-share card — mirrors layout_msg_reel_share.xml: rounded
 * 165×237dp thumbnail (or #1A1A1A placeholder), top gradient + avatar/
 * username header, centered play glyph, bottom gradient + caption + "⬡
 * Reels" label, and the timestamp/tick pill in the bottom-end corner
 * (always shown, same as ll_msg_footer there).
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file split,
 * no behavior change) — bind/measure/touch logic for the reel-share card
 * stays on the host view; this class only owns the draw() call.
 */
final class ReelShareRenderer {

    private final MessageBubbleCanvasView host;

    // PERF (ultra-opt pass): username only actually changes on rebind
    // (bindReelShare()/setReelShareUsername()), but TextUtils.ellipsize()
    // was re-running (and re-allocating its result) on every single draw()
    // during scroll — same pattern already fixed in ContactRenderer/
    // FileBubbleRenderer. Cache and only recompute when the raw text or
    // available width actually changes.
    private String lastUsernameRaw;
    private float lastUsernameMaxW = -1f;
    private String cachedUsernameDisplay;

    ReelShareRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
        float r = MessageBubbleCanvasView.REEL_CORNER_RADIUS_DP * host.density;

        // ── Thumbnail (or placeholder), clipped to the card's rounded shape ──
        if (host.reelThumbBitmap != null) {
            float scale = Math.max(host.reelCardRect.width() / host.reelThumbBitmap.getWidth(),
                    host.reelCardRect.height() / host.reelThumbBitmap.getHeight());
            float dx = host.reelCardRect.left - (host.reelThumbBitmap.getWidth() * scale - host.reelCardRect.width()) / 2f;
            float dy = host.reelCardRect.top - (host.reelThumbBitmap.getHeight() * scale - host.reelCardRect.height()) / 2f;
            host.reelShaderMatrix.reset();
            host.reelShaderMatrix.setScale(scale, scale);
            host.reelShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    host.reelThumbBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(host.reelShaderMatrix);
            host.reelThumbPaint.setShader(shader);
            canvas.drawRoundRect(host.reelCardRect, r, r, host.reelThumbPaint);
        } else {
            canvas.drawRoundRect(host.reelCardRect, r, r, host.reelCardBgPaint);
        }

        // ── Top gradient (fades thumbnail so the header reads clearly) ──
        host.reelTopGradient.setBounds(
                (int) host.reelCardRect.left, (int) host.reelCardRect.top,
                (int) host.reelCardRect.right, (int) (host.reelCardRect.top + MessageBubbleCanvasView.REEL_TOP_GRADIENT_DP * host.density));
        host.reelTopGradient.draw(canvas);

        // ── Bottom gradient (fades thumbnail so caption/label reads clearly) ──
        host.reelBottomGradient.setBounds(
                (int) host.reelCardRect.left, (int) (host.reelCardRect.bottom - MessageBubbleCanvasView.REEL_BOTTOM_GRADIENT_DP * host.density),
                (int) host.reelCardRect.right, (int) host.reelCardRect.bottom);
        host.reelBottomGradient.draw(canvas);

        // ── Header: avatar + username ──
        if (host.reelAvatarBitmap != null) {
            float scale = Math.max(host.reelAvatarRect.width() / host.reelAvatarBitmap.getWidth(),
                    host.reelAvatarRect.height() / host.reelAvatarBitmap.getHeight());
            float dx = host.reelAvatarRect.left - (host.reelAvatarBitmap.getWidth() * scale - host.reelAvatarRect.width()) / 2f;
            float dy = host.reelAvatarRect.top - (host.reelAvatarBitmap.getHeight() * scale - host.reelAvatarRect.height()) / 2f;
            host.reelAvatarShaderMatrix.reset();
            host.reelAvatarShaderMatrix.setScale(scale, scale);
            host.reelAvatarShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    host.reelAvatarBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(host.reelAvatarShaderMatrix);
            host.reelAvatarPaint.setShader(shader);
            canvas.drawOval(host.reelAvatarRect, host.reelAvatarPaint);
        } else {
            canvas.drawOval(host.reelAvatarRect, host.reelAvatarPlaceholderPaint);
        }

        float usernameX = host.reelAvatarRect.right + MessageBubbleCanvasView.REEL_AVATAR_TEXT_GAP_DP * host.density;
        Paint.FontMetrics ufm = host.reelUsernamePaint.getFontMetrics();
        float usernameBaselineY = host.reelAvatarRect.centerY() - (ufm.ascent + ufm.descent) / 2f;
        float usernameMaxW = host.reelCardRect.right - MessageBubbleCanvasView.REEL_HEADER_PAD_H_DP * host.density - usernameX;
        float safeUsernameMaxW = Math.max(1, usernameMaxW);
        String usernameToDraw;
        if (cachedUsernameDisplay != null && host.reelUsername.equals(lastUsernameRaw) && safeUsernameMaxW == lastUsernameMaxW) {
            usernameToDraw = cachedUsernameDisplay;
        } else {
            usernameToDraw = TextUtils.ellipsize(host.reelUsername, host.reelUsernamePaint,
                    safeUsernameMaxW, TextUtils.TruncateAt.END).toString();
            lastUsernameRaw = host.reelUsername;
            lastUsernameMaxW = safeUsernameMaxW;
            cachedUsernameDisplay = usernameToDraw;
        }
        canvas.drawText(usernameToDraw, usernameX, usernameBaselineY, host.reelUsernamePaint);

        // ── Centered play glyph ──
        Paint.FontMetrics pfm = host.reelPlayIconPaint.getFontMetrics();
        float playBaselineY = host.reelCardRect.centerY() - (pfm.ascent + pfm.descent) / 2f;
        canvas.drawText(MessageBubbleCanvasView.REEL_PLAY_GLYPH, host.reelCardRect.centerX(), playBaselineY, host.reelPlayIconPaint);

        // ── Bottom: caption + "⬡ Reels" label ──
        float bottomPadH = MessageBubbleCanvasView.REEL_BOTTOM_PAD_H_DP * host.density;
        float bottomPadBottom = MessageBubbleCanvasView.REEL_BOTTOM_PAD_BOTTOM_DP * host.density;
        Paint.FontMetrics lfm = host.reelLabelPaint.getFontMetrics();
        float labelBaselineY = host.reelCardRect.bottom - bottomPadBottom - lfm.descent;
        canvas.drawText(MessageBubbleCanvasView.REEL_LABEL_TEXT, host.reelCardRect.left + bottomPadH, labelBaselineY, host.reelLabelPaint);

        if (host.reelHasCaption && host.reelCaptionLayout != null) {
            float captionBottom = labelBaselineY + lfm.ascent - MessageBubbleCanvasView.REEL_LABEL_GAP_TOP_DP * host.density;
            canvas.save();
            canvas.translate(host.reelCardRect.left + bottomPadH, captionBottom - host.reelCaptionLayout.getHeight());
            host.reelCaptionLayout.draw(canvas);
            canvas.restore();
        }

        // ── Timestamp/tick pill — always shown, bottom-end corner ──
        float rr = MessageBubbleCanvasView.MEDIA_PILL_CORNER_DP * host.density;
        canvas.drawRoundRect(host.mediaPillRect, rr, rr, host.mediaPillBgPaint);
        float pillPadH = MessageBubbleCanvasView.MEDIA_PILL_PADDING_H_DP * host.density;
        float textBaselineY = host.mediaPillRect.bottom - (host.mediaPillRect.height()
                - (host.mediaPillTextPaint.descent() - host.mediaPillTextPaint.ascent())) / 2f
                - host.mediaPillTextPaint.descent();
        float tickReserve = host.sent ? (MessageBubbleCanvasView.TICK_SIZE_DP + MessageBubbleCanvasView.TICK_GAP_DP) * host.density : 0;
        float timeX = host.mediaPillRect.right - pillPadH - tickReserve - host.mediaPillTextPaint.measureText(host.footerTimeText);
        canvas.drawText(host.footerTimeText, timeX, textBaselineY, host.mediaPillTextPaint);
        if (host.hasExpiry) {
            canvas.drawText(host.expiryText, timeX - host.expiryReserveWidth(), textBaselineY, host.expiryPaint);
        }
        if (host.sent) {
            // PERF (ultra-opt pass): was `new Paint(host.tickPaint)` — a full
            // Paint object allocation on every single draw() for every sent
            // reel-share bubble on screen, every frame of a fling. Only the
            // color actually needs saving/restoring here, so swap to a plain
            // int — zero allocation, same visual result.
            int savedTickColor = host.tickPaint.getColor();
            host.tickPaint.setColor(MessageBubbleCanvasView.MEDIA_PILL_TEXT);
            host.drawTick(canvas, host.mediaPillRect.right - pillPadH - MessageBubbleCanvasView.TICK_SIZE_DP * host.density, textBaselineY);
            host.tickPaint.setColor(savedTickColor);
        }
    }
}
