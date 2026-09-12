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

    // PERF (ultra-opt pass): label/name only change on rebind, but
    // TextUtils.ellipsize() was re-running on every single draw() during
    // scroll — same pattern already fixed in ContactRenderer/
    // FileBubbleRenderer. Cache and only recompute when the raw text or
    // available width actually changes.
    private String lastLabelRaw;
    private float lastLabelMaxW = -1f;
    private String cachedLabelDisplay;
    private String lastSeenNameRaw;
    private float lastSeenNameMaxW = -1f;
    private String cachedSeenNameDisplay;

    // PERF: reused across every draw() instead of the no-arg
    // paint.getFontMetrics(), which allocates a new FontMetrics object
    // every call — same GC-pressure-at-60fps class of bug already fixed
    // for the reactions badge / audio waveform.
    private final Paint.FontMetrics iconFm = new Paint.FontMetrics();
    private final Paint.FontMetrics labelFm = new Paint.FontMetrics();
    private final Paint.FontMetrics nameFm = new Paint.FontMetrics();
    private final Paint.FontMetrics timeFm = new Paint.FontMetrics();

    // PERF: cached avatar BitmapShader — was `new BitmapShader(...)` on
    // every single draw() while a photo avatar is showing. Same pattern as
    // MediaGroupRenderer's cellShaders: rebuilt only when the bitmap
    // reference or its scale/translate actually changed since the last draw.
    private android.graphics.BitmapShader avatarShader;
    private android.graphics.Bitmap lastAvatarBitmap;
    private float lastAvatarScale = Float.NaN, lastAvatarDx, lastAvatarDy;

    // PERF: cached "folded corner" card clip path — was two `new Path()`s
    // plus a Path.Op(DIFFERENCE) boolean subtraction (the most expensive
    // Path operation available) rebuilt on every single draw() during
    // scroll, even though the card's rect/radius/fold amount only actually
    // change on rebind or a relayout. Rebuilt only when seenCardRect's
    // bounds or the fold amount actually change since the last draw.
    private final android.graphics.Path cardBasePath = new android.graphics.Path();
    private final android.graphics.Path cardNotchPath = new android.graphics.Path();
    private final android.graphics.Path cardClipPath = new android.graphics.Path();
    private float lastCardLeft = Float.NaN, lastCardTop, lastCardRight, lastCardBottom, lastCardFold;

    private void ensureCardClipPath(float r, float fold) {
        android.graphics.RectF rect = host.seenCardRect;
        if (rect.left == lastCardLeft && rect.top == lastCardTop && rect.right == lastCardRight
                && rect.bottom == lastCardBottom && fold == lastCardFold) {
            return;
        }
        cardBasePath.reset();
        cardBasePath.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW);
        cardNotchPath.reset();
        cardNotchPath.moveTo(rect.right - fold, rect.top - 1f);
        cardNotchPath.lineTo(rect.right + 1f, rect.top - 1f);
        cardNotchPath.lineTo(rect.right + 1f, rect.top + fold);
        cardNotchPath.close();
        cardClipPath.reset();
        cardClipPath.op(cardBasePath, cardNotchPath, android.graphics.Path.Op.DIFFERENCE);
        lastCardLeft = rect.left;
        lastCardTop = rect.top;
        lastCardRight = rect.right;
        lastCardBottom = rect.bottom;
        lastCardFold = fold;
    }

    // PERF: cached thumb round-rect clip path + BitmapShader — same
    // recompute-on-change treatment as the card clip path / avatar shader
    // above, keyed off seenThumbRect's bounds and the thumb bitmap.
    private final android.graphics.Path thumbClipPath = new android.graphics.Path();
    private float lastThumbClipLeft = Float.NaN, lastThumbClipTop, lastThumbClipRight, lastThumbClipBottom, lastThumbClipR;
    private android.graphics.BitmapShader thumbShader;
    private android.graphics.Bitmap lastThumbBitmap;
    private float lastThumbScale = Float.NaN, lastThumbDx, lastThumbDy;

    private void ensureThumbClipPath(float thumbR) {
        android.graphics.RectF rect = host.seenThumbRect;
        if (rect.left == lastThumbClipLeft && rect.top == lastThumbClipTop && rect.right == lastThumbClipRight
                && rect.bottom == lastThumbClipBottom && thumbR == lastThumbClipR) {
            return;
        }
        thumbClipPath.reset();
        thumbClipPath.addRoundRect(rect, thumbR, thumbR, android.graphics.Path.Direction.CW);
        lastThumbClipLeft = rect.left;
        lastThumbClipTop = rect.top;
        lastThumbClipRight = rect.right;
        lastThumbClipBottom = rect.bottom;
        lastThumbClipR = thumbR;
    }

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

            if (avatarShader == null || lastAvatarBitmap != host.seenAvatarBitmap
                    || scale != lastAvatarScale || dx != lastAvatarDx || dy != lastAvatarDy) {
                host.seenAvatarShaderMatrix.reset();
                host.seenAvatarShaderMatrix.setScale(scale, scale);
                host.seenAvatarShaderMatrix.postTranslate(dx, dy);
                avatarShader = new android.graphics.BitmapShader(
                        host.seenAvatarBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                avatarShader.setLocalMatrix(host.seenAvatarShaderMatrix);
                lastAvatarBitmap = host.seenAvatarBitmap;
                lastAvatarScale = scale;
                lastAvatarDx = dx;
                lastAvatarDy = dy;
            }
            host.seenAvatarPaint.setShader(avatarShader);
            canvas.drawOval(host.seenAvatarRect, host.seenAvatarPaint);
        } else {
            canvas.drawOval(host.seenAvatarRect, host.seenAvatarPlaceholderPaint);
        }

        // ── Card (MODERNIZE: folded top-right corner / "dog-ear" instead of
        // a plain 4-corner rounded card — reads as a folded note/bookmark) ──
        float r = MessageBubbleCanvasView.SEEN_CARD_CORNER_DP * host.density;
        float fold = Math.min(MessageBubbleCanvasView.SEEN_CARD_FOLD_DP * host.density,
                Math.min(host.seenCardRect.width(), host.seenCardRect.height()) * 0.4f);
        int bgColor = host.seenIsReel ? MessageBubbleCanvasView.SEEN_REEL_BG_COLOR : MessageBubbleCanvasView.SEEN_STATUS_BG_COLOR;
        host.seenCardBgPaint.setColor(bgColor);

        // Base shape: normal round-rect on all 4 corners, then subtract a
        // triangular notch from the top-right corner (Path.Op.DIFFERENCE) to
        // create the folded-corner silhouette.
        ensureCardClipPath(r, fold);

        canvas.save();
        canvas.clipPath(cardClipPath);
        canvas.drawRect(host.seenCardRect, host.seenCardBgPaint);

        float padH = MessageBubbleCanvasView.SEEN_CARD_PAD_H_DP * host.density;
        float padEnd = MessageBubbleCanvasView.SEEN_CARD_PAD_END_DP * host.density;
        float left = host.seenCardRect.left + padH;
        float right = host.seenCardRect.right - padEnd;
        float cursorY = host.seenCardRect.top + MessageBubbleCanvasView.SEEN_CARD_PAD_TOP_DP * host.density;

        // ── Optional thumbnail + play/eye overlay ──
        if (host.seenHasThumb) {
            // MODERNIZE: was a plain sharp-cornered drawRect() — every other
            // thumbnail/card in the app (link-preview, reel-share, media
            // bubbles) is rounded; this was the one outdated-looking
            // rectangle left. Clip to a round-rect on all 4 corners (the
            // thumb sits fully inset inside the card with margin on every
            // side — see seenThumbRect's measure — so a full round-rect
            // reads correctly, no need to match it to any card edge).
            float thumbR = MessageBubbleCanvasView.SEEN_THUMB_CORNER_DP * host.density;
            ensureThumbClipPath(thumbR);
            int thumbSaveCount = canvas.save();
            canvas.clipPath(thumbClipPath);
            canvas.drawRect(host.seenThumbRect, host.seenThumbBgPaint);
            if (host.seenThumbBitmap != null) {
                float scale = Math.max(host.seenThumbRect.width() / host.seenThumbBitmap.getWidth(),
                        host.seenThumbRect.height() / host.seenThumbBitmap.getHeight());
                float dx = host.seenThumbRect.left - (host.seenThumbBitmap.getWidth() * scale - host.seenThumbRect.width()) / 2f;
                float dy = host.seenThumbRect.top - (host.seenThumbBitmap.getHeight() * scale - host.seenThumbRect.height()) / 2f;

                if (thumbShader == null || lastThumbBitmap != host.seenThumbBitmap
                        || scale != lastThumbScale || dx != lastThumbDx || dy != lastThumbDy) {
                    host.seenThumbShaderMatrix.reset();
                    host.seenThumbShaderMatrix.setScale(scale, scale);
                    host.seenThumbShaderMatrix.postTranslate(dx, dy);
                    thumbShader = new android.graphics.BitmapShader(
                            host.seenThumbBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                    thumbShader.setLocalMatrix(host.seenThumbShaderMatrix);
                    lastThumbBitmap = host.seenThumbBitmap;
                    lastThumbScale = scale;
                    lastThumbDx = dx;
                    lastThumbDy = dy;
                }
                host.seenThumbPaint.setShader(thumbShader);
                canvas.drawRect(host.seenThumbRect, host.seenThumbPaint);
            }
            canvas.restoreToCount(thumbSaveCount);
            String overlayGlyph = host.seenIsReel ? MessageBubbleCanvasView.SEEN_REEL_PLAY_GLYPH : MessageBubbleCanvasView.SEEN_STATUS_EYE_GLYPH;
            canvas.drawText(overlayGlyph, host.seenThumbRect.centerX(),
                    host.seenThumbRect.centerY() - (host.seenOverlayIconPaint.ascent() + host.seenOverlayIconPaint.descent()) / 2f,
                    host.seenOverlayIconPaint);
            cursorY = host.seenThumbRect.bottom + MessageBubbleCanvasView.SEEN_THUMB_MARGIN_BOTTOM_DP * host.density;
        }

        // ── Icon + italic label row ──
        host.seenIconPaint.getFontMetrics(iconFm);
        host.seenLabelPaint.getFontMetrics(labelFm);
        Paint.FontMetrics ifm = iconFm;
        Paint.FontMetrics lfm = labelFm;
        float rowH = Math.max(ifm.descent - ifm.ascent, lfm.descent - lfm.ascent);
        float rowCenterY = cursorY + rowH / 2f;
        String iconGlyph = host.seenIsReel ? MessageBubbleCanvasView.SEEN_REEL_ICON_GLYPH : MessageBubbleCanvasView.SEEN_STATUS_ICON_GLYPH;
        canvas.drawText(iconGlyph, left, rowCenterY - (ifm.ascent + ifm.descent) / 2f, host.seenIconPaint);
        float labelX = left + host.seenIconPaint.measureText(iconGlyph) + MessageBubbleCanvasView.SEEN_ICON_LABEL_GAP_DP * host.density;
        String labelText = host.seenLabelOverride != null
                ? host.seenLabelOverride
                : (host.seenIsReel ? MessageBubbleCanvasView.SEEN_REEL_LABEL_TEXT : MessageBubbleCanvasView.SEEN_STATUS_LABEL_TEXT);
        float labelMaxW = Math.max(1, right - labelX);
        String labelToDraw;
        if (cachedLabelDisplay != null && labelText.equals(lastLabelRaw) && labelMaxW == lastLabelMaxW) {
            labelToDraw = cachedLabelDisplay;
        } else {
            labelToDraw = TextUtils.ellipsize(labelText, host.seenLabelPaint, labelMaxW, TextUtils.TruncateAt.END).toString();
            lastLabelRaw = labelText;
            lastLabelMaxW = labelMaxW;
            cachedLabelDisplay = labelToDraw;
        }
        canvas.drawText(labelToDraw, labelX, rowCenterY - (lfm.ascent + lfm.descent) / 2f, host.seenLabelPaint);
        cursorY += rowH;

        // ── Optional sender name (groups) ──
        if (host.seenHasName) {
            host.seenNamePaint.getFontMetrics(nameFm);
            Paint.FontMetrics nfm = nameFm;
            cursorY += MessageBubbleCanvasView.SEEN_NAME_GAP_TOP_DP * host.density;
            float nameMaxW = Math.max(1, right - left);
            String nameToDraw;
            if (cachedSeenNameDisplay != null && host.seenName.equals(lastSeenNameRaw) && nameMaxW == lastSeenNameMaxW) {
                nameToDraw = cachedSeenNameDisplay;
            } else {
                nameToDraw = TextUtils.ellipsize(host.seenName, host.seenNamePaint, nameMaxW, TextUtils.TruncateAt.END).toString();
                lastSeenNameRaw = host.seenName;
                lastSeenNameMaxW = nameMaxW;
                cachedSeenNameDisplay = nameToDraw;
            }
            canvas.drawText(nameToDraw, left, cursorY - nfm.ascent, host.seenNamePaint);
            cursorY += (nfm.descent - nfm.ascent);
        }

        // ── Time line ──
        host.seenTimePaint.getFontMetrics(timeFm);
        Paint.FontMetrics tfm = timeFm;
        cursorY += MessageBubbleCanvasView.SEEN_TIME_GAP_TOP_DP * host.density;
        canvas.drawText(host.footerTimeText, left, cursorY - tfm.ascent, host.seenTimePaint);

        canvas.restore();
    }
}
