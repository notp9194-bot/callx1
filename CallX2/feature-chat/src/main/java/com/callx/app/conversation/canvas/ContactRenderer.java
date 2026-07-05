package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextUtils;

/**
 * Draws the contact-share card — mirrors item_msg_contact.xml: one rounded
 * 165dp-wide card (single #1C1C1E background covers both the "top section"
 * and the "View Contact" row, since they share the same color in the
 * legacy layout — only the divider line is visually distinct), a circular
 * avatar (placeholder ic_person glyph if no photo), name + phone stacked
 * beside it, and the "View Contact" label centered in its own row below a
 * thin divider.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file split,
 * no behavior change) — bind/measure/touch logic for the contact card
 * stays on the host view; this class only owns the draw() call.
 */
final class ContactRenderer {

    private final MessageBubbleCanvasView host;

    ContactRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
        float r = MessageBubbleCanvasView.CONTACT_CORNER_RADIUS_DP * host.density;
        canvas.save();
        // Clip to the card's rounded shape (android:clipToOutline on the
        // legacy ll_contact_card) so the flat divider/button-row rects
        // drawn below don't square off the bottom corners.
        android.graphics.Path clipPath = new android.graphics.Path();
        clipPath.addRoundRect(host.contactCardRect, r, r, android.graphics.Path.Direction.CW);
        canvas.clipPath(clipPath);

        canvas.drawRect(host.contactCardRect, host.contactCardBgPaint);

        // ── Avatar (photo or placeholder) ──
        if (host.contactAvatarBitmap != null) {
            float scale = Math.max(host.contactAvatarRect.width() / host.contactAvatarBitmap.getWidth(),
                    host.contactAvatarRect.height() / host.contactAvatarBitmap.getHeight());
            float dx = host.contactAvatarRect.left - (host.contactAvatarBitmap.getWidth() * scale - host.contactAvatarRect.width()) / 2f;
            float dy = host.contactAvatarRect.top - (host.contactAvatarBitmap.getHeight() * scale - host.contactAvatarRect.height()) / 2f;
            host.contactAvatarShaderMatrix.reset();
            host.contactAvatarShaderMatrix.setScale(scale, scale);
            host.contactAvatarShaderMatrix.postTranslate(dx, dy);

            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                    host.contactAvatarBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(host.contactAvatarShaderMatrix);
            host.contactAvatarPaint.setShader(shader);
            canvas.drawOval(host.contactAvatarRect, host.contactAvatarPaint);
        } else {
            canvas.drawOval(host.contactAvatarRect, host.contactAvatarPlaceholderPaint);
        }

        // ── Name / phone column beside the avatar ──
        float textX = host.contactAvatarRect.right + MessageBubbleCanvasView.CONTACT_TEXT_GAP_DP * host.density;
        float textMaxW = host.contactCardRect.right - MessageBubbleCanvasView.CONTACT_PAD_H_DP * host.density - textX;
        String nameToDraw = host.contactNameEllipsizeCache.get(host.contactName, host.contactNamePaint,
                textMaxW, TextUtils.TruncateAt.END);
        String phoneToDraw = host.contactPhoneEllipsizeCache.get(host.contactPhone, host.contactPhonePaint,
                textMaxW, TextUtils.TruncateAt.END);

        Paint.FontMetrics nfm = host.contactNamePaint.getFontMetrics();
        Paint.FontMetrics phfm = host.contactPhonePaint.getFontMetrics();
        float nameH = nfm.descent - nfm.ascent;
        float phoneH = phfm.descent - phfm.ascent;
        boolean hasPhone = !phoneToDraw.isEmpty();
        float phoneGap = hasPhone ? MessageBubbleCanvasView.CONTACT_PHONE_GAP_DP * host.density : 0;
        float blockH = nameH + (hasPhone ? phoneGap + phoneH : 0);
        float blockTop = host.contactAvatarRect.centerY() - blockH / 2f;

        canvas.drawText(nameToDraw, textX, blockTop - nfm.ascent, host.contactNamePaint);
        if (hasPhone) {
            float phoneBaselineY = blockTop + nameH + phoneGap - phfm.ascent;
            canvas.drawText(phoneToDraw, textX, phoneBaselineY, host.contactPhonePaint);
        }

        // ── Divider ──
        float dividerTop = host.contactCardRect.top + MessageBubbleCanvasView.CONTACT_TOP_HEIGHT_DP * host.density;
        canvas.drawRect(host.contactCardRect.left, dividerTop, host.contactCardRect.right,
                dividerTop + MessageBubbleCanvasView.CONTACT_DIVIDER_HEIGHT_DP * host.density, host.contactDividerPaint);

        // ── "View Contact" row ──
        Paint.FontMetrics bfm = host.contactButtonTextPaint.getFontMetrics();
        float btnBaselineY = host.contactButtonRect.centerY() - (bfm.ascent + bfm.descent) / 2f;
        canvas.drawText(MessageBubbleCanvasView.CONTACT_BUTTON_TEXT, host.contactButtonRect.centerX(), btnBaselineY, host.contactButtonTextPaint);

        canvas.restore();

        // ── Disappearing-message countdown — this card has no regular
        // timestamp/tick row (matches item_msg_contact.xml having none), so
        // the expiry pill is a small floating badge in the card's top-end
        // corner instead of sharing a footer row. Drawn after canvas.restore()
        // so it isn't clipped to the card's rounded shape. ──
        if (host.hasExpiry) {
            host.drawCornerExpiryPill(canvas, host.contactCardRect);
        }
    }
}
