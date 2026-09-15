package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Draws the single-image/video media slot: rounded centerCrop bitmap (or
 * placeholder box), optional "GIF" badge, optional video play-glyph +
 * duration overlay, optional download gate, then either a caption +
 * normal footer or a captionless translucent timestamp/tick pill
 * overlaid on the bottom-right corner.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file
 * split, no behavior change) — bind/measure/touch logic for the media
 * bubble stays on the host view; this class only owns the draw() call
 * (drawMedia + its two private helpers, drawVideoPlayOverlay and
 * drawMediaDownloadGate).
 */
final class MediaRenderer {

    private final MessageBubbleCanvasView host;

    MediaRenderer(MessageBubbleCanvasView host) {
        this.host = host;
        gifBadgeBgPaint.setColor(0xCC000000);
        voiceBadgeBgPaint.setColor(0xCC000000);
        voiceBadgeIconPaint.setColor(0xFFFFFFFF);
        voiceBadgeIconPaint.setStyle(Paint.Style.FILL);
        voiceBadgeDurPaint.setColor(0xFFFFFFFF);
        voiceBadgeDurPaint.setTextSize(host.spToPx(11f));
    }

    // ── PERF: BitmapShader cache ─────────────────────────────────────────
    // This instance is created once per MessageBubbleCanvasView and reused
    // across every rebind while the view is recycled (same lifetime as
    // MediaGroupRenderer, which already caches its per-cell shaders this
    // way — see that class's javadoc). Before this fix, draw() built a
    // brand-new BitmapShader (including the scale/translate matrix setup)
    // on literally every single call, which matters a lot during an
    // indeterminate download/upload spinner: that redraws the whole bubble
    // at up to 60fps (throttled to ~30fps as of v108) for as long as
    // progress is unknown, so this shader was being rebuilt 30+ times/sec
    // for a bitmap and rect that hadn't changed at all.
    //
    // Rebuild trigger is simple and easy to verify: the cached shader is
    // reused only while both the bitmap *reference* and mediaRect's exact
    // bounds are unchanged since it was built. Any bitmap swap (new image
    // loaded) or rect change (bubble resized/rebound) naturally falls
    // through to a fresh build — same safety shape as the text-layout
    // cache in this class: correct-by-construction cache invalidation,
    // not a manually-tracked dirty flag that could be forgotten somewhere.
    private android.graphics.Bitmap cachedShaderBitmap;
    private android.graphics.BitmapShader cachedShader;
    private float cachedRectLeft = Float.NaN, cachedRectTop = Float.NaN,
            cachedRectRight = Float.NaN, cachedRectBottom = Float.NaN;

    // Reused GIF-badge background paint — draw() previously allocated a
    // fresh `new Paint()` for this on every call (only its color ever
    // changes, and it never actually changes at that — always 0xCC000000).
    private final Paint gifBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // v36 PERF: these two used to be `new RectF(...)` allocated fresh on
    // every single draw() call — for a video bubble (which always shows
    // the duration badge) that's a GC-churning allocation on every frame
    // while the indeterminate download spinner is animating (~30fps, see
    // MessageBubbleCanvasView#drawMediaWithOptionalCache's javadoc), same
    // exact class of bug already fixed for the renderer classes elsewhere
    // in this codebase (pre-allocated Paint/Path/RectF out of draw()).
    // Reused via .set() instead — zero allocation on the hot path.
    private final RectF gifBadgeRectF = new RectF();
    private final RectF durationBadgeRectF = new RectF();

    // ── Feature: Voice Caption on Photo (Canvas) ───────────────────────
    // Play/pause + duration pill overlaid on the media rect's bottom-start
    // corner for an image that also carries a short attached voice note
    // (host.voiceUrl). Same dark-pill visual language as gifBadgeBgPaint/
    // the group duration badge, just its own paints since the pill's
    // white icon+text combo doesn't match either of those exactly.
    private final Paint voiceBadgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint voiceBadgeIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.text.TextPaint voiceBadgeDurPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF voiceBadgePillRectF = new RectF();
    private final android.graphics.Path voiceBadgeGlyphPath = new android.graphics.Path();

    void draw(Canvas canvas, int hPad, int vPad) {
        draw(canvas, hPad, vPad, false);
    }

    /**
     * @param spinnerHandledSeparately when true, this skips drawing the
     *        live indeterminate spinner ring itself — the caller is
     *        recording everything else into a cached Picture and will
     *        draw just the ring, every frame, via
     *        drawIndeterminateSpinnerOnly() on top of it. Determinate
     *        progress (0-100%) is never affected by this flag — it only
     *        changes on real progress events rather than every frame, so
     *        it's always drawn here directly, cache or no cache. See
     *        MessageBubbleCanvasView#drawMediaWithOptionalCache() for the
     *        full picture (pun intended).
     */
    void draw(Canvas canvas, int hPad, int vPad, boolean spinnerHandledSeparately) {
        float r = MessageBubbleCanvasView.MEDIA_CORNER_RADIUS_DP * host.density;
        if (host.mediaBitmap != null) {
            // Rounded-corner centerCrop: scale a BitmapShader so the source
            // bitmap fills mediaRect exactly (matching ImageView's
            // centerCrop), then clip to a round rect with drawRoundRect —
            // avoids clipPath (which can force a software layer on some
            // Android versions) while still giving true rounded corners.
            boolean rectMatches = host.mediaRect.left == cachedRectLeft
                    && host.mediaRect.top == cachedRectTop
                    && host.mediaRect.right == cachedRectRight
                    && host.mediaRect.bottom == cachedRectBottom;
            if (cachedShader != null && cachedShaderBitmap == host.mediaBitmap && rectMatches) {
                host.mediaBitmapPaint.setShader(cachedShader);
            } else {
                float scale = Math.max(host.mediaRect.width() / host.mediaBitmap.getWidth(),
                        host.mediaRect.height() / host.mediaBitmap.getHeight());
                float dx = host.mediaRect.left - (host.mediaBitmap.getWidth() * scale - host.mediaRect.width()) / 2f;
                float dy = host.mediaRect.top - (host.mediaBitmap.getHeight() * scale - host.mediaRect.height()) / 2f;
                host.mediaShaderMatrix.reset();
                host.mediaShaderMatrix.setScale(scale, scale);
                host.mediaShaderMatrix.postTranslate(dx, dy);

                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                        host.mediaBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                shader.setLocalMatrix(host.mediaShaderMatrix);
                host.mediaBitmapPaint.setShader(shader);

                cachedShader = shader;
                cachedShaderBitmap = host.mediaBitmap;
                cachedRectLeft = host.mediaRect.left;
                cachedRectTop = host.mediaRect.top;
                cachedRectRight = host.mediaRect.right;
                cachedRectBottom = host.mediaRect.bottom;
            }
            canvas.drawRoundRect(host.mediaRect, r, r, host.mediaBitmapPaint);
        } else {
            // Not decoded yet — plain placeholder box, same rounded shape.
            // Also drop any stale cached shader so a bitmap arriving later
            // is guaranteed to take the fresh-build path above (cachedShaderBitmap
            // would otherwise still reference the old bitmap only by luck of
            // object identity never colliding — clearing is the safe explicit choice).
            cachedShader = null;
            cachedShaderBitmap = null;
            canvas.drawRoundRect(host.mediaRect, r, r, host.mediaPlaceholderPaint);
        }

        // PREMIUM REDESIGN (v32): hairline glass edge traced around the
        // media rect — bitmap, placeholder, and (further below) duration
        // badge/timestamp pill all share this same subtle-border language
        // so the whole media bubble reads as one deliberate "glass card"
        // instead of a flat rounded rect with things floating on top of it.
        canvas.drawRoundRect(host.mediaRect, r, r, host.mediaBorderPaint);

        // ── GIF badge — "GIF" pill in top-start corner, WhatsApp/Telegram style ──
        if (host.isGifBubble) {
            float badgePad = 4f * host.density;
            float badgeR   = 4f * host.density;
            float badgeTsz = host.spToPx(10f);
            host.textPaint.setTextSize(badgeTsz);
            host.textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            float tw = host.textPaint.measureText(MessageBubbleCanvasView.GIF_BADGE_TEXT);
            float bw = tw + badgePad * 2f;
            float bh = badgeTsz + badgePad * 2f;
            float bx = host.mediaRect.left + 6f * host.density;
            float by = host.mediaRect.top  + 6f * host.density;
            gifBadgeRectF.set(bx, by, bx + bw, by + bh);
            canvas.drawRoundRect(gifBadgeRectF, badgeR, badgeR, gifBadgeBgPaint);
            host.textPaint.setColor(0xFFFFFFFF);
            canvas.drawText(MessageBubbleCanvasView.GIF_BADGE_TEXT, bx + badgePad, by + badgePad + badgeTsz * 0.85f, host.textPaint);
            host.textPaint.setTypeface(Typeface.DEFAULT); // restore
        }

        if (host.isVideoMedia) {
            drawVideoPlayOverlay(canvas);
        }

        // ── Feature: Voice Caption on Photo (Canvas) ────────────────────
        // Skipped while mediaGated (nothing meaningful to overlay on an
        // unfetched image yet — same precedent as the GIF/video overlays
        // above, which the download gate below covers instead). Drawn
        // regardless of mediaHasCaption: unlike the legacy View bubble's
        // bottom-pinned gradient scrim (which the caption text itself sits
        // inside of), Canvas's caption is a separate row BELOW mediaRect
        // (see the mediaHasCaption branch further down), so the badge —
        // which lives entirely inside mediaRect — never visually competes
        // with it either way. The bottom inset still widens a little when
        // a caption is present so the badge doesn't sit flush against the
        // exact edge where the caption block begins right underneath.
        if (host.voiceUrl != null && !host.voiceUrl.isEmpty()) {
            if (!host.mediaGated) {
                float badgeBottomInset = (host.mediaHasCaption ? 10f : 6f) * host.density;
                float badgeX = host.mediaRect.left + 6f * host.density;
                float badgeY = host.mediaRect.bottom - badgeBottomInset;
                drawVoiceBadge(canvas, badgeX, badgeY);
            } else {
                host.voiceBadgeRect.setEmpty();
            }
        } else {
            host.voiceBadgeRect.setEmpty();
        }

        if (host.mediaGated) {
            // Manual-download gate covers the whole slot (idle pill or live
            // spinner/percentage) — same precedent as the group gate: while
            // it's up, the timestamp/tick pill below is skipped entirely
            // (nothing meaningful to show over an unfetched image yet).
            drawMediaDownloadGate(canvas, spinnerHandledSeparately);
            return;
        }

        if (host.mediaHasCaption && host.textLayout != null) {
            float captionTop = host.mediaRect.bottom + MessageBubbleCanvasView.MEDIA_CAPTION_GAP_DP * host.density;
            canvas.save();
            canvas.translate(host.bubbleLeft + hPad, captionTop);
            host.textLayout.draw(canvas);
            canvas.restore();

            // ── Read-more / Read-less strip (long caption) ────────────
            if (host.hasLongText) {
                host.drawReadMoreStrip(canvas, host.bubbleLeft + hPad, captionTop + host.textLayout.getHeight());
            } else {
                host.readMoreRect.setEmpty();
            }

            host.drawFooter(canvas, host.bubbleRect.bottom - vPad * 0.4f, host.bubbleRect.right - hPad);
        } else {
            host.readMoreRect.setEmpty();
            // Captionless image: translucent timestamp/tick pill overlaid
            // on the image's bottom-right corner, WhatsApp-style.
            float rr = MessageBubbleCanvasView.MEDIA_PILL_CORNER_DP * host.density;
            canvas.drawRoundRect(host.mediaPillRect, rr, rr, host.mediaPillBgPaint);
            canvas.drawRoundRect(host.mediaPillRect, rr, rr, host.mediaPillBorderPaint);
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
                // BUG FIX: this used to force the tick to always draw in
                // MEDIA_PILL_TEXT (solid white) "so it reads on the pill" —
                // but that meant the tick never visually changed color when
                // a message went delivered -> read, because host.tickPaint
                // already carries the correct grey/gold color (set by
                // bind()/setDeliveryStatus() from ChatThemeManager
                // .getTickColor(read)) and this was overwriting it with
                // white on every single draw. Both real tick colors
                // (0xFF8FAF9F grey, 0xFFD4AF37 gold) read perfectly fine
                // against the pill's translucent black background — same
                // as the WhatsApp-style pill this mirrors — so just draw
                // with the paint as-is instead of hijacking its color.
                host.drawTick(canvas, host.mediaPillRect.right - pillPadH - MessageBubbleCanvasView.TICK_SIZE_DP * host.density, textBaselineY);
            }
        }
    }

    /**
     * Draws the play-circle+triangle glyph centered on mediaRect, plus a
     * duration badge in the bottom-left corner — mirrors the legacy
     * fl_video/iv_video_thumb treatment for a single "video" message.
     * Reuses groupPlayCirclePaint/groupPlayTrianglePaint/
     * groupDurationTextPaint/groupDurationBgPaint/groupPlayTrianglePath
     * from the media-GROUP video-cell overlay — identical visual, no
     * separate constants needed for the single-video case.
     */
    // v36 PERF: measureText() re-walks every glyph in the string — cheap
    // once, but wasteful if paid again on every single frame while the
    // cached-Picture path is bypassed (indeterminate download spinner,
    // see drawMediaWithOptionalCache). videoDuration is static for the
    // lifetime of a bind (a video's length doesn't change), so cache the
    // last-measured value and only re-measure when the string itself
    // actually changes (new bind/recycle, or a caption/duration update).
    private String cachedDurationText;
    private float cachedDurationTextWidth;

    private float measureDurationText(String duration, Paint paint) {
        if (!duration.equals(cachedDurationText)) {
            cachedDurationText = duration;
            cachedDurationTextWidth = paint.measureText(duration);
        }
        return cachedDurationTextWidth;
    }

    private void drawVideoPlayOverlay(Canvas canvas) {
        float cx = host.mediaRect.centerX(), cy = host.mediaRect.centerY();
        float circleR = (MessageBubbleCanvasView.SINGLE_VIDEO_PLAY_CIRCLE_DP * host.density) / 2f;
        canvas.drawCircle(cx, cy, circleR, host.groupPlayCirclePaint);
        // Hairline ring traced just inside the fill edge (half the stroke
        // width in from circleR) so the stroke doesn't get clipped/aliased
        // right at the disc's outer boundary.
        canvas.drawCircle(cx, cy, circleR - host.groupPlayRingPaint.getStrokeWidth() / 2f, host.groupPlayRingPaint);

        float triR = (MessageBubbleCanvasView.SINGLE_VIDEO_PLAY_TRIANGLE_DP * host.density) / 2f;
        host.groupPlayTrianglePath.reset();
        host.groupPlayTrianglePath.moveTo(cx - triR * 0.5f, cy - triR * 0.8f);
        host.groupPlayTrianglePath.lineTo(cx - triR * 0.5f, cy + triR * 0.8f);
        host.groupPlayTrianglePath.lineTo(cx + triR * 0.9f, cy);
        host.groupPlayTrianglePath.close();
        canvas.drawPath(host.groupPlayTrianglePath, host.groupPlayTrianglePaint);

        if (host.videoDuration != null && !host.videoDuration.isEmpty()) {
            float durPadH = 6 * host.density, durPadV = 3 * host.density;
            float textW = measureDurationText(host.videoDuration, host.groupDurationTextPaint);
            float textH = host.groupDurationTextPaint.descent() - host.groupDurationTextPaint.ascent();
            float left = host.mediaRect.left + 6 * host.density;
            float bottom = host.mediaRect.bottom - 6 * host.density;
            durationBadgeRectF.set(left, bottom - textH - durPadV * 2, left + textW + durPadH * 2, bottom);
            float durR = MessageBubbleCanvasView.GROUP_DURATION_CORNER_DP * host.density;
            canvas.drawRoundRect(durationBadgeRectF, durR, durR, host.groupDurationBgPaint);
            canvas.drawRoundRect(durationBadgeRectF, durR, durR, host.groupDurationBorderPaint);
            float textBaseline = durationBadgeRectF.bottom - durPadV - host.groupDurationTextPaint.descent();
            canvas.drawText(host.videoDuration, durationBadgeRectF.left + durPadH, textBaseline, host.groupDurationTextPaint);
        }
    }

    /**
     * PERF (ultra): Paint.measureText() walks every glyph in the string —
     * cheap once, but this badge can redraw on every full-bubble
     * cache rebuild (any unrelated field on the bubble changing) PLUS
     * every isVoicePlaying toggle's own dirty-rect redraw (see
     * MessageBubbleCanvasView#invalidateVoiceBadgeRegion). The duration
     * string itself is static for the whole lifetime of a bind — only
     * isVoicePlaying ticks, never voiceDuration (see setVoiceCaption's
     * javadoc) — so both the measured text width AND the pill's derived
     * width/height (same inputs → same output, every single time) are
     * cached here and only ever recomputed when the string actually
     * changes. Same precedent as measureDurationText()'s video-duration
     * cache just above. After the first draw of a given duration string,
     * every subsequent draw call pays zero measureText cost — pure field
     * reads instead.
     */
    private String cachedVoiceDurText;
    private float cachedVoiceDurTextWidth;
    private float cachedVoicePillW, cachedVoicePillH;

    private void ensureVoiceBadgeGeometry(String durText, float density) {
        if (durText.equals(cachedVoiceDurText)) return; // cache hit — nothing changed since last draw
        cachedVoiceDurText = durText;
        cachedVoiceDurTextWidth = voiceBadgeDurPaint.measureText(durText);
        float iconD = 22f * density;
        float padH = 8f * density;
        float padV = 5f * density;
        float gap = 6f * density;
        float textH = voiceBadgeDurPaint.descent() - voiceBadgeDurPaint.ascent();
        cachedVoicePillH = Math.max(iconD, textH) + padV * 2;
        cachedVoicePillW = padH + iconD + gap + cachedVoiceDurTextWidth + padH;
    }

    /**
     * Feature: Voice Caption on Photo (Canvas). Draws a play/pause +
     * duration pill (bg_voice_duration_pill's look, drawn manually here
     * instead of a Drawable) whose bottom-start corner anchors at (x, y).
     * Mirrors the legacy fl_voice_on_image treatment: dark translucent
     * pill, white glyph, white "m:ss" duration label. Records the pill's
     * final bounds into host.voiceBadgeRect so onTouchEvent can hit-test
     * taps on it (see MessageBubbleCanvasView#onTouchEvent's voiceBadgeRect
     * block, checked BEFORE the general mediaRect tap).
     */
    private void drawVoiceBadge(Canvas canvas, float x, float y) {
        float density = host.density;
        float iconD = 22f * density;
        float padH = 8f * density;
        float gap = 6f * density;
        String durText = host.voiceDuration != null ? host.voiceDuration : "0:00";
        ensureVoiceBadgeGeometry(durText, density); // PERF: no-op (field reads only) unless durText changed
        float pillH = cachedVoicePillH;
        float pillW = cachedVoicePillW;

        // (x, y) is the bottom-start anchor point of the pill.
        voiceBadgePillRectF.set(x, y - pillH, x + pillW, y);
        float r = pillH / 2f;
        canvas.drawRoundRect(voiceBadgePillRectF, r, r, voiceBadgeBgPaint);

        float iconCx = voiceBadgePillRectF.left + padH + iconD / 2f;
        float iconCy = voiceBadgePillRectF.centerY();
        if (host.isVoicePlaying) {
            // Pause glyph — two rounded vertical bars.
            float barW = iconD * 0.16f;
            float barH = iconD * 0.55f;
            float barGap = iconD * 0.18f;
            float barR = 1.5f * density;
            canvas.drawRoundRect(iconCx - barGap / 2f - barW, iconCy - barH / 2f,
                    iconCx - barGap / 2f, iconCy + barH / 2f, barR, barR, voiceBadgeIconPaint);
            canvas.drawRoundRect(iconCx + barGap / 2f, iconCy - barH / 2f,
                    iconCx + barGap / 2f + barW, iconCy + barH / 2f, barR, barR, voiceBadgeIconPaint);
        } else {
            // Play glyph — filled triangle, same construction as
            // drawVideoPlayOverlay's triangle just below/left of center.
            float triR = iconD * 0.32f;
            voiceBadgeGlyphPath.reset();
            voiceBadgeGlyphPath.moveTo(iconCx - triR * 0.55f, iconCy - triR * 0.85f);
            voiceBadgeGlyphPath.lineTo(iconCx - triR * 0.55f, iconCy + triR * 0.85f);
            voiceBadgeGlyphPath.lineTo(iconCx + triR * 0.95f, iconCy);
            voiceBadgeGlyphPath.close();
            canvas.drawPath(voiceBadgeGlyphPath, voiceBadgeIconPaint);
        }

        float textBaseline = voiceBadgePillRectF.centerY()
                - (voiceBadgeDurPaint.ascent() + voiceBadgeDurPaint.descent()) / 2f;
        canvas.drawText(durText, iconCx + iconD / 2f + gap, textBaseline, voiceBadgeDurPaint);

        host.voiceBadgeRect.set(voiceBadgePillRectF);
    }

    /**
     * Draws the single-media download gate: a dim scrim over mediaRect plus
     * a centered pill — idle "⬇ <label>" (tap to start) when !mediaDownloading,
     * or a live spinner/percentage ring while mediaDownloading is true. See
     * setMediaDownloadGate()/setMediaDownloadProgress().
     */
    private void drawMediaDownloadGate(Canvas canvas, boolean spinnerHandledSeparately) {
        float r = MessageBubbleCanvasView.MEDIA_CORNER_RADIUS_DP * host.density;
        canvas.drawRoundRect(host.mediaRect, r, r, host.mediaGateScrimPaint);

        String label = host.mediaDownloading
                ? (host.mediaDownloadProgress >= 0 ? host.mediaDownloadProgress + "%" : "")
                : (host.mediaDownloadLabel.isEmpty() ? "Photo" : host.mediaDownloadLabel);

        float iconSize = MessageBubbleCanvasView.GROUP_GATE_PILL_ICON_DP * host.density;
        float iconGap = MessageBubbleCanvasView.GROUP_GATE_PILL_ICON_GAP_DP * host.density;
        float padH = MessageBubbleCanvasView.GROUP_GATE_PILL_PAD_H_DP * host.density;
        float padV = MessageBubbleCanvasView.GROUP_GATE_PILL_PAD_V_DP * host.density;
        float textW = label.isEmpty() ? 0 : host.mediaGatePillTextPaint.measureText(label);
        float contentH = Math.max(iconSize, host.mediaGatePillTextPaint.descent() - host.mediaGatePillTextPaint.ascent());
        float pillW = padH * 2 + iconSize + (label.isEmpty() ? 0 : iconGap + textW);
        float pillH = padV * 2 + contentH;
        float cx = host.mediaRect.centerX(), cy = host.mediaRect.centerY();
        host.mediaGatePillRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f);

        float pillR = MessageBubbleCanvasView.GROUP_GATE_PILL_CORNER_DP * host.density;
        canvas.drawRoundRect(host.mediaGatePillRect, pillR, pillR, host.mediaGatePillBgPaint);

        float iconCx = host.mediaGatePillRect.left + padH + iconSize / 2f;
        float iconCy = host.mediaGatePillRect.centerY();

        if (host.mediaDownloading) {
            // Determinate progress (>=0) is always drawn here directly —
            // it only changes on real progress events, not every frame, so
            // there's no repeated-redraw problem for the cache to solve.
            // Indeterminate (<0) is skipped here ONLY when the caller is
            // recording this into a cached Picture (spinnerHandledSeparately);
            // it will draw the ring itself, live, every frame, via
            // drawIndeterminateSpinnerOnly() on top of that cached Picture.
            if (host.mediaDownloadProgress >= 0 || !spinnerHandledSeparately) {
                host.drawProgressRing(canvas, iconCx, iconCy, iconSize, host.mediaGatePillIconPaint, host.mediaDownloadProgress);
            }
        } else {
            host.drawGateIcon(canvas, iconCx, iconCy, iconSize, host.mediaGatePillIconPaint);
        }

        if (!label.isEmpty()) {
            float textBaselineY = host.mediaGatePillRect.centerY()
                    - (host.mediaGatePillTextPaint.ascent() + host.mediaGatePillTextPaint.descent()) / 2f;
            canvas.drawText(label, iconCx + iconSize / 2f + iconGap, textBaselineY, host.mediaGatePillTextPaint);
        }
    }

    /**
     * Draws ONLY the live indeterminate spinner ring, at the exact position
     * the last drawMediaDownloadGate(canvas, true) call computed and stored
     * in host.mediaGatePillRect. Meant to be called every frame on top of a
     * cached Picture that has everything else already baked in — see
     * MessageBubbleCanvasView#drawMediaWithOptionalCache().
     *
     * No-ops (draws nothing) unless still actually in the indeterminate-
     * gated-downloading state — if that state has changed since the cached
     * Picture was recorded, the caller's own state check already routes
     * around this method entirely, but the guard here costs nothing and
     * means this method can never draw a stray ring over content it
     * doesn't belong on.
     */
    void drawIndeterminateSpinnerOnly(Canvas canvas) {
        if (!host.mediaGated || !host.mediaDownloading || host.mediaDownloadProgress >= 0) return;
        float iconSize = MessageBubbleCanvasView.GROUP_GATE_PILL_ICON_DP * host.density;
        float padH = MessageBubbleCanvasView.GROUP_GATE_PILL_PAD_H_DP * host.density;
        float iconCx = host.mediaGatePillRect.left + padH + iconSize / 2f;
        float iconCy = host.mediaGatePillRect.centerY();
        host.drawProgressRing(canvas, iconCx, iconCy, iconSize, host.mediaGatePillIconPaint, host.mediaDownloadProgress);
    }
}
