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
        // v425 PERF: voice-badge Paints/Paths are NOT built here anymore —
        // see ensureVoiceRes(). The overwhelming majority of bubbles never
        // carry a voice caption, so paying 3 Paints + 2 Paths + 1 RectF per
        // MessageBubbleCanvasView up front was pure waste.
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
    // v425 PERF: all voice-badge draw resources are lazily created on the
    // first voice-caption draw (ensureVoiceRes()) instead of eagerly per view.
    private Paint voiceBadgeBgPaint;
    private Paint voiceBadgeIconPaint;
    private android.text.TextPaint voiceBadgeDurPaint;
    private RectF voiceBadgePillRectF;
    private android.graphics.Path voiceBadgeGlyphPath;
    // Feature: Save-audio button glyph (down-arrow-into-tray, reused for
    // both the arrow-head and the tray strokes within one drawVoiceBadge()
    // call — same reuse-not-realloc precedent as voiceBadgeGlyphPath above).
    private android.graphics.Path voiceBadgeDownloadArrowPath;

    /** Builds the voice-badge Paints/Paths/RectF once, on first use. */
    private void ensureVoiceRes() {
        if (voiceBadgeBgPaint != null) return;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xCC000000);
        Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        icon.setColor(0xFFFFFFFF);
        icon.setStyle(Paint.Style.FILL);
        android.text.TextPaint dur = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        dur.setColor(0xFFFFFFFF);
        dur.setTextSize(host.spToPx(11f));
        voiceBadgePillRectF = new RectF();
        voiceBadgeGlyphPath = new android.graphics.Path();
        voiceBadgeDownloadArrowPath = new android.graphics.Path();
        voiceBadgeIconPaint = icon;
        voiceBadgeDurPaint = dur;
        voiceBadgeBgPaint = bg; // assigned last — doubles as the "ready" flag
    }

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
                // Adaptive blur (only for the 32x32 ThumbHash placeholder,
                // never a real decoded photo/video-frame): the bigger the
                // bubble, the more this tiny bitmap has to be upscaled to
                // fill mediaRect, and a fixed bilinear upscale alone starts
                // looking soft-blocky rather than genuinely blurred once
                // the bubble grows toward MEDIA_MAX_*_DP. Blurring a bit
                // more at the source size before the upscale keeps small
                // bubbles crisp-ish and makes big bubbles read as a proper
                // soft preview instead. See blurPlaceholderForBubble() —
                // this only runs on a genuine cache miss (bitmap or rect
                // changed), same as the shader build itself, so it costs
                // nothing on the 30-60fps redraw path.
                android.graphics.Bitmap shaderSourceBitmap = host.mediaBitmapIsPlaceholder
                        ? blurPlaceholderForBubble(host.mediaBitmap, host.mediaRect.width(), host.mediaRect.height(), host.density)
                        : host.mediaBitmap;

                float scale = Math.max(host.mediaRect.width() / shaderSourceBitmap.getWidth(),
                        host.mediaRect.height() / shaderSourceBitmap.getHeight());
                float dx = host.mediaRect.left - (shaderSourceBitmap.getWidth() * scale - host.mediaRect.width()) / 2f;
                float dy = host.mediaRect.top - (shaderSourceBitmap.getHeight() * scale - host.mediaRect.height()) / 2f;
                host.mediaShaderMatrix.reset();
                host.mediaShaderMatrix.setScale(scale, scale);
                host.mediaShaderMatrix.postTranslate(dx, dy);

                android.graphics.BitmapShader shader = new android.graphics.BitmapShader(
                        shaderSourceBitmap, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
                shader.setLocalMatrix(host.mediaShaderMatrix);
                host.mediaBitmapPaint.setShader(shader);

                // Identity check stays on the ORIGINAL bitmap (not the
                // blurred copy) — that's the object ThumbHashPlaceholder's
                // LruCache hands back for a given hash, so this is still
                // the correct thing to compare against on the next draw()
                // to decide whether a rebuild is needed at all.
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
                host.voiceSpeedRect.setEmpty();
                host.voiceDownloadRect.setEmpty();
            }
        } else {
            host.voiceBadgeRect.setEmpty();
            host.voiceDownloadRect.setEmpty();
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
    private String cachedVoiceSpeedLabel;
    private boolean cachedVoiceDownloading;
    private float cachedVoiceDurTextWidth;
    private float cachedVoiceSpeedTextWidth;
    private float cachedVoicePillW, cachedVoicePillH;
    /** Where the play/pause segment ends and the separator/speed segment
     *  begins, relative to the pill's left edge — lets drawVoiceBadge()
     *  split host.voiceBadgeRect / host.voiceSpeedRect without recomputing
     *  layout math twice. */
    private float cachedVoiceBadgeSegmentW;
    /** Where the speed segment ends and the download-button segment
     *  begins, relative to the pill's left edge — mirrors
     *  cachedVoiceBadgeSegmentW's role one segment further right. Only
     *  meaningful while !downloading (no download button shown yet for a
     *  clip that hasn't finished fetching for playback). */
    private float cachedVoiceSpeedSegmentEndW;
    // PERF ULTRA: "elapsed / total" concat cache. While a voice caption is
    // playing, drawVoiceBadge() runs on every playback tick (several times
    // a second) — without this, `host.voiceElapsedText + " / " + totalText`
    // allocated a brand-new String on every single one of those draws even
    // though the elapsed label itself typically only changes once a second.
    // Distinct from ensureVoiceBadgeGeometry()'s cache above: that one
    // guards the (expensive) measureText/layout math, this one guards the
    // (cheap but constant) String concat that feeds into it.
    private String cachedVoiceElapsedIn;
    private String cachedVoiceTotalIn;
    private String cachedVoiceCombinedDur;

    private void ensureVoiceBadgeGeometry(String durText, String speedLabel, boolean downloading, float density) {
        ensureVoiceRes();
        // Cache hit — nothing that affects geometry changed since last draw.
        if (durText.equals(cachedVoiceDurText) && speedLabel.equals(cachedVoiceSpeedLabel)
                && downloading == cachedVoiceDownloading) {
            return;
        }
        cachedVoiceDurText = durText;
        cachedVoiceSpeedLabel = speedLabel;
        cachedVoiceDownloading = downloading;
        cachedVoiceDurTextWidth = voiceBadgeDurPaint.measureText(
                downloading ? DOWNLOADING_LABEL : durText);
        float iconD = 22f * density;
        float padH = 8f * density;
        float padV = 5f * density;
        float gap = 6f * density;
        float textH = voiceBadgeDurPaint.descent() - voiceBadgeDurPaint.ascent();
        cachedVoicePillH = Math.max(iconD, textH) + padV * 2;
        // Play/pause segment: icon + gap + duration text, padded both ends.
        cachedVoiceBadgeSegmentW = padH + iconD + gap + cachedVoiceDurTextWidth + padH;
        if (downloading) {
            // No speed chip (or download button — nothing decrypted/fetched
            // yet to save) while still fetching for playback.
            cachedVoiceSpeedTextWidth = 0f;
            cachedVoicePillW = cachedVoiceBadgeSegmentW;
            cachedVoiceSpeedSegmentEndW = cachedVoiceBadgeSegmentW;
        } else {
            cachedVoiceSpeedTextWidth = voiceBadgeDurPaint.measureText(speedLabel);
            // Separator (thin divider) + speed text + trailing pad.
            float sepGap = 8f * density;
            cachedVoiceSpeedSegmentEndW = cachedVoiceBadgeSegmentW + sepGap + cachedVoiceSpeedTextWidth + padH;
            // Feature: Save-audio button — a 3rd segment appended after the
            // speed chip: another thin divider + a small download glyph,
            // same icon language as ic_download_reel.xml (down-arrow into a
            // tray), sized like a compact square button rather than a
            // text+icon segment (nothing to measureText here).
            float downloadIconD = 16f * density;
            float downloadPadH = 8f * density;
            cachedVoicePillW = cachedVoiceSpeedSegmentEndW + sepGap + downloadIconD + downloadPadH;
        }
    }

    private static final String DOWNLOADING_LABEL = "\u2026"; // "…" — fetching, nothing to show yet

    /**
     * Feature: Voice Caption on Photo (Canvas). Draws a play/pause +
     * duration pill (bg_voice_duration_pill's look, drawn manually here
     * instead of a Drawable) whose bottom-start corner anchors at (x, y).
     * Mirrors the legacy fl_voice_on_image treatment: dark translucent
     * pill, white glyph, white "m:ss" duration label. Records the pill's
     * final bounds into host.voiceBadgeRect so onTouchEvent can hit-test
     * taps on it (see MessageBubbleCanvasView#onTouchEvent's voiceBadgeRect
     * block, checked BEFORE the general mediaRect tap).
     *
     * While host.voiceDownloading is true (clip still being fetched/
     * decrypted — see MessagePagingAdapter#toggleAudio), the icon/duration
     * segment is replaced with a plain "…" and no speed chip is drawn —
     * same idea as the photo's own mediaDownloading gate, just a lighter
     * weight treatment sized for this small pill instead of a full scrim.
     * Otherwise a second segment shows the current speed (host.
     * voiceSpeedLabel, e.g. "1×") separated by a thin divider, hit-tested
     * against host.voiceSpeedRect independently of the play/pause segment.
     */
    private void drawVoiceBadge(Canvas canvas, float x, float y) {
        ensureVoiceRes();
        float density = host.density;
        float iconD = 22f * density;
        float padH = 8f * density;
        float gap = 6f * density;
        String totalText = host.voiceDuration != null ? host.voiceDuration : "0:00";
        // Feature: elapsed/total while playing — "0:03 / 0:12" instead of
        // just the static total, so the user can see where playback is.
        // Falls back to just the total the instant isVoicePlaying/
        // voiceElapsedText clears (pause keeps the last elapsed value —
        // see voiceElapsedText's javadoc; a genuine stop clears it).
        boolean showElapsed = host.isVoicePlaying && host.voiceElapsedText != null && !host.voiceElapsedText.isEmpty();
        String durText;
        if (!showElapsed) {
            durText = totalText;
        } else if (host.voiceElapsedText.equals(cachedVoiceElapsedIn) && totalText.equals(cachedVoiceTotalIn)
                && cachedVoiceCombinedDur != null) {
            // PERF ULTRA: same elapsed+total as last tick's draw — reuse the
            // already-built "0:03 / 0:12" String instead of concatenating again.
            durText = cachedVoiceCombinedDur;
        } else {
            cachedVoiceElapsedIn = host.voiceElapsedText;
            cachedVoiceTotalIn = totalText;
            cachedVoiceCombinedDur = host.voiceElapsedText + " / " + totalText;
            durText = cachedVoiceCombinedDur;
        }
        String speedLabel = host.voiceSpeedLabel != null ? host.voiceSpeedLabel : "1×";
        boolean downloading = host.voiceDownloading;
        ensureVoiceBadgeGeometry(durText, speedLabel, downloading, density); // PERF: no-op (field reads only) unless something changed
        float pillH = cachedVoicePillH;
        float pillW = cachedVoicePillW;

        // (x, y) is the bottom-start anchor point of the pill.
        voiceBadgePillRectF.set(x, y - pillH, x + pillW, y);
        float r = pillH / 2f;
        canvas.drawRoundRect(voiceBadgePillRectF, r, r, voiceBadgeBgPaint);

        float iconCx = voiceBadgePillRectF.left + padH + iconD / 2f;
        float iconCy = voiceBadgePillRectF.centerY();
        if (downloading) {
            // Fetching — a plain static ellipsis instead of a play/pause
            // glyph, so the badge never claims to be "playing" before
            // there's actually anything to play.
            float textBaseline = voiceBadgePillRectF.centerY()
                    - (voiceBadgeDurPaint.ascent() + voiceBadgeDurPaint.descent()) / 2f;
            canvas.drawText(DOWNLOADING_LABEL, voiceBadgePillRectF.left + padH, textBaseline, voiceBadgeDurPaint);
        } else if (host.isVoicePlaying) {
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

        if (!downloading) {
            float textBaseline = voiceBadgePillRectF.centerY()
                    - (voiceBadgeDurPaint.ascent() + voiceBadgeDurPaint.descent()) / 2f;
            canvas.drawText(durText, iconCx + iconD / 2f + gap, textBaseline, voiceBadgeDurPaint);
        }

        // Play/pause hit-rect: everything up to the end of the duration
        // segment (or the whole pill while downloading — no speed chip
        // to carve out yet).
        float badgeSegRight = voiceBadgePillRectF.left + cachedVoiceBadgeSegmentW;
        host.voiceBadgeRect.set(voiceBadgePillRectF.left, voiceBadgePillRectF.top,
                badgeSegRight, voiceBadgePillRectF.bottom);

        if (downloading) {
            host.voiceSpeedRect.setEmpty();
            host.voiceDownloadRect.setEmpty();
            return;
        }

        // Speed segment: thin divider, then the speed label, right-aligned
        // to the pill's own right edge.
        float dividerX = badgeSegRight + (4f * density);
        float dividerPad = 5f * density;
        canvas.drawLine(dividerX, voiceBadgePillRectF.top + dividerPad,
                dividerX, voiceBadgePillRectF.bottom - dividerPad, voiceBadgeIconPaint);
        float speedTextX = dividerX + (4f * density);
        float textBaseline = voiceBadgePillRectF.centerY()
                - (voiceBadgeDurPaint.ascent() + voiceBadgeDurPaint.descent()) / 2f;
        canvas.drawText(speedLabel, speedTextX, textBaseline, voiceBadgeDurPaint);
        float speedSegRight = voiceBadgePillRectF.left + cachedVoiceSpeedSegmentEndW;
        host.voiceSpeedRect.set(dividerX, voiceBadgePillRectF.top,
                speedSegRight, voiceBadgePillRectF.bottom);

        // ── Feature: Save-audio button — 3rd segment ─────────────────────
        // Own thin divider (same treatment as the speed chip's), then a
        // compact down-arrow-into-tray glyph (ic_download_reel.xml's shape,
        // hand-drawn since this whole badge is Canvas-drawn, no Drawable
        // inflation anywhere else in it) centered in the remaining pill
        // width out to its right edge.
        float downloadDividerX = speedSegRight + (4f * density);
        canvas.drawLine(downloadDividerX, voiceBadgePillRectF.top + dividerPad,
                downloadDividerX, voiceBadgePillRectF.bottom - dividerPad, voiceBadgeIconPaint);
        host.voiceDownloadRect.set(downloadDividerX, voiceBadgePillRectF.top,
                voiceBadgePillRectF.right, voiceBadgePillRectF.bottom);
        float dlIconD = 16f * density;
        float dlCx = (downloadDividerX + voiceBadgePillRectF.right) / 2f;
        float dlCy = voiceBadgePillRectF.centerY();
        float strokeW = 1.6f * density;
        Paint.Style prevStyle = voiceBadgeIconPaint.getStyle();
        float prevStrokeW = voiceBadgeIconPaint.getStrokeWidth();
        voiceBadgeIconPaint.setStyle(Paint.Style.STROKE);
        voiceBadgeIconPaint.setStrokeWidth(strokeW);
        voiceBadgeIconPaint.setStrokeCap(Paint.Cap.ROUND);
        voiceBadgeIconPaint.setStrokeJoin(Paint.Join.ROUND);
        // Down arrow (shaft + chevron head), mirrors the vector's first path.
        float shaftTop = dlCy - dlIconD * 0.42f;
        float shaftBottom = dlCy + dlIconD * 0.12f;
        canvas.drawLine(dlCx, shaftTop, dlCx, shaftBottom, voiceBadgeIconPaint);
        voiceBadgeDownloadArrowPath.reset();
        voiceBadgeDownloadArrowPath.moveTo(dlCx - dlIconD * 0.28f, dlCy - dlIconD * 0.14f);
        voiceBadgeDownloadArrowPath.lineTo(dlCx, dlCy + dlIconD * 0.12f);
        voiceBadgeDownloadArrowPath.lineTo(dlCx + dlIconD * 0.28f, dlCy - dlIconD * 0.14f);
        canvas.drawPath(voiceBadgeDownloadArrowPath, voiceBadgeIconPaint);
        // Tray (open-top rounded rect), mirrors the vector's second path.
        float trayY = dlCy + dlIconD * 0.30f;
        float trayHalfW = dlIconD * 0.38f;
        voiceBadgeDownloadArrowPath.reset();
        voiceBadgeDownloadArrowPath.moveTo(dlCx - trayHalfW, trayY);
        voiceBadgeDownloadArrowPath.lineTo(dlCx - trayHalfW, trayY + dlIconD * 0.22f);
        voiceBadgeDownloadArrowPath.lineTo(dlCx + trayHalfW, trayY + dlIconD * 0.22f);
        voiceBadgeDownloadArrowPath.lineTo(dlCx + trayHalfW, trayY);
        canvas.drawPath(voiceBadgeDownloadArrowPath, voiceBadgeIconPaint);
        // Restore the shared icon paint to its normal filled style — every
        // other glyph on this badge (play triangle, pause bars) draws FILL.
        voiceBadgeIconPaint.setStyle(prevStyle);
        voiceBadgeIconPaint.setStrokeWidth(prevStrokeW);
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
    // ── Adaptive blur for the ThumbHash placeholder ──────────────────────
    // Radius is interpolated by how much mediaRect upscales the 32x32
    // placeholder: below MIN_UPSCALE (a small bubble, near
    // MessageBubbleCanvasView.MEDIA_MIN_WIDTH_DP/MEDIA_MIN_HEIGHT_DP) no
    // extra blur is added — plain bilinear upscale already looks fine at
    // that scale. At/above MAX_UPSCALE (a big bubble, near
    // MEDIA_MAX_WIDTH_DP/MEDIA_MAX_HEIGHT_DP) the radius caps at
    // PLACEHOLDER_MAX_BLUR_RADIUS. Values tuned by feel, not measurement —
    // same as TinyThumbBlurTransformation's radius=3 "correct" constant.
    private static final int PLACEHOLDER_MIN_BLUR_RADIUS = 1;
    private static final int PLACEHOLDER_MAX_BLUR_RADIUS = 5;
    private static final float PLACEHOLDER_MIN_UPSCALE = 6f;
    private static final float PLACEHOLDER_MAX_UPSCALE = 20f;

    // PERF (advance #3): bubbles at/above this size use the GPU-accelerated
    // RenderEffect blur (NativeBlur) instead of the Java box blur — the
    // RenderNode/ImageReader setup has real fixed overhead per call, so
    // it's only worth paying once the bubble is big enough that fast-scroll
    // jank risk is real. Smaller/typical bubbles stay on the Java path.
    private static final float NATIVE_BLUR_MIN_BUBBLE_DP = 300f;

    private static int adaptiveBlurRadiusFor(float bubbleWidthPx, float bubbleHeightPx, int sourceSize) {
        if (sourceSize <= 0) return PLACEHOLDER_MIN_BLUR_RADIUS;
        float upscale = Math.max(bubbleWidthPx, bubbleHeightPx) / (float) sourceSize;
        float t = (upscale - PLACEHOLDER_MIN_UPSCALE) / (PLACEHOLDER_MAX_UPSCALE - PLACEHOLDER_MIN_UPSCALE);
        t = Math.max(0f, Math.min(1f, t));
        return Math.round(PLACEHOLDER_MIN_BLUR_RADIUS + t * (PLACEHOLDER_MAX_BLUR_RADIUS - PLACEHOLDER_MIN_BLUR_RADIUS));
    }

    /**
     * Returns a blurred COPY of the small ThumbHash placeholder bitmap,
     * radius scaled to how far mediaRect upscales it — never mutates `src`,
     * since ThumbHashPlaceholder's LruCache hands out the very same decoded
     * Bitmap instance to every bubble sharing that hash string; blurring in
     * place would corrupt every other bubble currently showing it. Returns
     * `src` unchanged when the bubble is small enough that the computed
     * radius wouldn't do anything (the common case for a compact bubble).
     *
     * Big bubbles (≥300dp) try NativeBlur (RenderEffect) first and fall
     * back to the Java box blur below on any failure (old API level,
     * OEM GPU quirk, etc.) — same null-means-fall-back contract used
     * throughout this pipeline (ThumbHash.decode(), BlurHash.decode()).
     */
    private static android.graphics.Bitmap blurPlaceholderForBubble(android.graphics.Bitmap src,
            float bubbleWidthPx, float bubbleHeightPx, float density) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        int radius = adaptiveBlurRadiusFor(bubbleWidthPx, bubbleHeightPx, Math.max(w, h));
        if (radius <= PLACEHOLDER_MIN_BLUR_RADIUS) return src;

        boolean isBigBubble = Math.max(bubbleWidthPx, bubbleHeightPx) >= NATIVE_BLUR_MIN_BUBBLE_DP * density;
        if (isBigBubble && com.callx.app.utils.NativeBlur.isSupported()) {
            android.graphics.Bitmap natively = com.callx.app.utils.NativeBlur.blur(src, radius);
            if (natively != null) return natively;
            // fall through to the Java path below
        }

        android.graphics.Bitmap copy = src.copy(
                src.getConfig() != null ? src.getConfig() : android.graphics.Bitmap.Config.ARGB_8888, true);
        int[] pixels = new int[w * h];
        copy.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] tmp = new int[Math.max(w, h)];
        // 3-pass box blur ≈ Gaussian — same shape as TinyThumbBlurTransformation's,
        // just inlined here since it operates directly on the already-32x32
        // placeholder (no separate downscale step needed).
        for (int pass = 0; pass < 3; pass++) {
            placeholderBoxBlurHorizontal(pixels, tmp, w, h, radius);
            placeholderBoxBlurVertical(pixels, tmp, w, h, radius);
        }
        copy.setPixels(pixels, 0, w, 0, 0, w, h);
        return copy;
    }

    private static void placeholderBoxBlurHorizontal(int[] pixels, int[] tmp, int w, int h, int radius) {
        for (int y = 0; y < h; y++) {
            int rowStart = y * w;
            long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            for (int x = -radius; x <= radius; x++) {
                int px = clampPx(x, 0, w - 1);
                int c = pixels[rowStart + px];
                sumA += (c >>> 24) & 0xFF;
                sumR += (c >>> 16) & 0xFF;
                sumG += (c >>> 8) & 0xFF;
                sumB += c & 0xFF;
            }
            int count = radius * 2 + 1;
            for (int x = 0; x < w; x++) {
                tmp[x] = ((int) (sumA / count) << 24) | ((int) (sumR / count) << 16)
                        | ((int) (sumG / count) << 8) | (int) (sumB / count);
                int addX = clampPx(x + radius + 1, 0, w - 1);
                int subX = clampPx(x - radius, 0, w - 1);
                if (x + radius + 1 < w && x - radius >= 0) {
                    int add = pixels[rowStart + addX];
                    int sub = pixels[rowStart + subX];
                    sumA += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                    sumR += ((add >>> 16) & 0xFF) - ((sub >>> 16) & 0xFF);
                    sumG += ((add >>> 8) & 0xFF) - ((sub >>> 8) & 0xFF);
                    sumB += (add & 0xFF) - (sub & 0xFF);
                }
            }
            System.arraycopy(tmp, 0, pixels, rowStart, w);
        }
    }

    private static void placeholderBoxBlurVertical(int[] pixels, int[] tmp, int w, int h, int radius) {
        for (int x = 0; x < w; x++) {
            long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            for (int y = -radius; y <= radius; y++) {
                int py = clampPx(y, 0, h - 1);
                int c = pixels[py * w + x];
                sumA += (c >>> 24) & 0xFF;
                sumR += (c >>> 16) & 0xFF;
                sumG += (c >>> 8) & 0xFF;
                sumB += c & 0xFF;
            }
            int count = radius * 2 + 1;
            for (int y = 0; y < h; y++) {
                tmp[y] = ((int) (sumA / count) << 24) | ((int) (sumR / count) << 16)
                        | ((int) (sumG / count) << 8) | (int) (sumB / count);
                if (y + radius + 1 < h && y - radius >= 0) {
                    int add = pixels[clampPx(y + radius + 1, 0, h - 1) * w + x];
                    int sub = pixels[clampPx(y - radius, 0, h - 1) * w + x];
                    sumA += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                    sumR += ((add >>> 16) & 0xFF) - ((sub >>> 16) & 0xFF);
                    sumG += ((add >>> 8) & 0xFF) - ((sub >>> 8) & 0xFF);
                    sumB += (add & 0xFF) - (sub & 0xFF);
                }
            }
            for (int y = 0; y < h; y++) pixels[y * w + x] = tmp[y];
        }
    }

    private static int clampPx(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    void drawIndeterminateSpinnerOnly(Canvas canvas) {
        if (!host.mediaGated || !host.mediaDownloading || host.mediaDownloadProgress >= 0) return;
        float iconSize = MessageBubbleCanvasView.GROUP_GATE_PILL_ICON_DP * host.density;
        float padH = MessageBubbleCanvasView.GROUP_GATE_PILL_PAD_H_DP * host.density;
        float iconCx = host.mediaGatePillRect.left + padH + iconSize / 2f;
        float iconCy = host.mediaGatePillRect.centerY();
        host.drawProgressRing(canvas, iconCx, iconCy, iconSize, host.mediaGatePillIconPaint, host.mediaDownloadProgress);
    }
}
