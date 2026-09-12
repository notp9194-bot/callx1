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

    // ── Ellipsize cache — name/phone only actually change on rebind
    // (bindContact()), but the old code re-ran TextUtils.ellipsize() (which
    // internally remeasures the whole string) on every single draw() during
    // scroll. Cache the result and only recompute when either the raw text
    // or the available column width (textMaxW, which tracks the card's
    // layout) has changed since the last draw.
    private String lastNameRaw;
    private float lastNameMaxW = -1f;
    private String cachedNameDisplay;
    private String lastPhoneRaw;
    private float lastPhoneMaxW = -1f;
    private String cachedPhoneDisplay;

    // PERF: reused across every draw() call instead of the no-arg
    // paint.getFontMetrics(), which allocates a brand-new FontMetrics
    // object every single call — same GC-pressure-at-60fps class of bug
    // already fixed for the reactions badge / audio waveform.
    private final Paint.FontMetrics nameFm = new Paint.FontMetrics();
    private final Paint.FontMetrics phoneFm = new Paint.FontMetrics();
    private final Paint.FontMetrics buttonFm = new Paint.FontMetrics();

    // PERF: cached rounded-rect clip path — was `new Path()` + addRoundRect()
    // on every single draw() during scroll. Same recompute-on-change pattern
    // as ReelShareRenderer's clipPath: only rebuilt when contactCardRect's
    // bounds (or the corner radius) actually change since the last draw.
    private final android.graphics.Path clipPath = new android.graphics.Path();
    private float lastClipLeft = Float.NaN, lastClipTop, lastClipRight, lastClipBottom, lastClipR;

    private void ensureClipPath(float r) {
        android.graphics.RectF rect = host.contactCardRect;
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

    // PERF: cached avatar BitmapShader — was `new BitmapShader(...)` on
    // every single draw() while a photo avatar is showing. Same pattern as
    // MediaGroupRenderer's cellShaders: rebuilt only when the bitmap
    // reference or its scale/translate (derived from the avatar rect)
    // actually changed since the last draw.
    private android.graphics.BitmapShader avatarShader;
    private android.graphics.Bitmap lastAvatarBitmap;
    private float lastAvatarScale = Float.NaN, lastAvatarDx, lastAvatarDy;

    void draw(Canvas canvas) {
        float r = MessageBubbleCanvasView.CONTACT_CORNER_RADIUS_DP * host.density;
        canvas.save();
        // Clip to the card's rounded shape (android:clipToOutline on the
        // legacy ll_contact_card) so the flat divider/button-row rects
        // drawn below don't square off the bottom corners.
        ensureClipPath(r);
        canvas.clipPath(clipPath);

        canvas.drawRect(host.contactCardRect, host.contactCardBgPaint);

        // ── Avatar (photo or placeholder) ──
        if (host.contactAvatarBitmap != null) {
            float scale = Math.max(host.contactAvatarRect.width() / host.contactAvatarBitmap.getWidth(),
                    host.contactAvatarRect.height() / host.contactAvatarBitmap.getHeight());
            float dx = host.contactAvatarRect.left - (host.contactAvatarBitmap.getWidth() * scale - host.contactAvatarRect.width()) / 2f;
            float dy = host.contactAvatarRect.top - (host.contactAvatarBitmap.getHeight() * scale - host.contactAvatarRect.height()) / 2f;

            if (avatarShader == null || lastAvatarBitmap != host.contactAvatarBitmap
                    || scale != lastAvatarScale || dx != lastAvatarDx || dy != lastAvatarDy) {
                host.contactAvatarShaderMatrix.reset();
                host.contactAvatarShaderMatrix.setScale(scale, scale);
                host.contactAvatarShaderMatrix.postTranslate(dx, dy);
                avatarShader = new android.graphics.BitmapShader(
                        host.contactAvatarBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                avatarShader.setLocalMatrix(host.contactAvatarShaderMatrix);
                lastAvatarBitmap = host.contactAvatarBitmap;
                lastAvatarScale = scale;
                lastAvatarDx = dx;
                lastAvatarDy = dy;
            }
            host.contactAvatarPaint.setShader(avatarShader);
            canvas.drawOval(host.contactAvatarRect, host.contactAvatarPaint);
        } else {
            canvas.drawOval(host.contactAvatarRect, host.contactAvatarPlaceholderPaint);
        }

        // ── Name / phone column beside the avatar ──
        float textX = host.contactAvatarRect.right + MessageBubbleCanvasView.CONTACT_TEXT_GAP_DP * host.density;
        float textMaxW = host.contactCardRect.right - MessageBubbleCanvasView.CONTACT_PAD_H_DP * host.density - textX;
        float safeMaxW = Math.max(1, textMaxW);
        String nameToDraw;
        if (cachedNameDisplay != null && host.contactName.equals(lastNameRaw) && safeMaxW == lastNameMaxW) {
            nameToDraw = cachedNameDisplay;
        } else {
            nameToDraw = TextUtils.ellipsize(host.contactName, host.contactNamePaint,
                    safeMaxW, TextUtils.TruncateAt.END).toString();
            lastNameRaw = host.contactName;
            lastNameMaxW = safeMaxW;
            cachedNameDisplay = nameToDraw;
        }
        String phoneToDraw;
        if (cachedPhoneDisplay != null && host.contactPhone.equals(lastPhoneRaw) && safeMaxW == lastPhoneMaxW) {
            phoneToDraw = cachedPhoneDisplay;
        } else {
            phoneToDraw = TextUtils.ellipsize(host.contactPhone, host.contactPhonePaint,
                    safeMaxW, TextUtils.TruncateAt.END).toString();
            lastPhoneRaw = host.contactPhone;
            lastPhoneMaxW = safeMaxW;
            cachedPhoneDisplay = phoneToDraw;
        }

        host.contactNamePaint.getFontMetrics(nameFm);
        host.contactPhonePaint.getFontMetrics(phoneFm);
        Paint.FontMetrics nfm = nameFm;
        Paint.FontMetrics phfm = phoneFm;
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
        host.contactButtonTextPaint.getFontMetrics(buttonFm);
        Paint.FontMetrics bfm = buttonFm;
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
