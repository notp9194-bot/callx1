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

    // Localized kind labels, resolved once per renderer (never inside the draw loop).
    private String gifBadgeText, photoLabelText;

    private String gifBadgeLabel() {
        if (gifBadgeText == null) gifBadgeText = host.getContext().getString(com.callx.app.chat.R.string.media_kind_gif);
        return gifBadgeText;
    }

    private String photoLabel() {
        if (photoLabelText == null) photoLabelText = host.getContext().getString(com.callx.app.chat.R.string.media_kind_photo);
        return photoLabelText;
    }

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
    private Paint voiceBadgeIconPaint;
    private android.text.TextPaint voiceBadgeDurPaint;
    private android.graphics.Path voiceBadgeGlyphPath;

    /** Builds the voice-badge Paints/Paths/RectF once, on first use. */
    private void ensureVoiceRes() {
        if (voiceBadgeIconPaint != null) return;
        Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        icon.setColor(0xFFFFFFFF);
        icon.setStyle(Paint.Style.FILL);
        android.text.TextPaint dur = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        dur.setColor(0xFFFFFFFF);
        dur.setTextSize(host.spToPx(11f));
        voiceBadgeGlyphPath = new android.graphics.Path();
        voiceBadgeDurPaint = dur;
        voiceBadgeIconPaint = icon; // assigned last — doubles as the "ready" flag
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
    // WhatsApp: on the bubble's tail side the media's top corner is square (the tail grows
    // out of it); otherwise a round image corner inside a square bubble corner leaves a
    // small notch. drawRoundRect can't do per-corner radii, so after the normal rounded
    // draw we paint ONLY the corner filler (square minus quarter-circle) with the same
    // paint/shader — a few vertices, no extra overdraw, no alpha doubling for scrims.
    private final android.graphics.Path tailCornerPath = new android.graphics.Path();
    private final android.graphics.RectF tailCornerOval = new android.graphics.RectF();
    // PERF: the filler path is rebuilt only when its geometry changes (media draw can run
    // every frame during download/upload progress; a mutated Path = a re-rasterised texture).
    private float tcL = Float.NaN, tcT, tcR;
    private boolean tcSent;

    private void squareTailCorner(Canvas canvas, android.graphics.Paint paint, float r) {
        if (!host.isBubbleTailShown() || r <= 0f) return;
        final android.graphics.RectF m = host.mediaRect;
        final android.graphics.Path p = tailCornerPath;
        final boolean sent = host.sent;
        final float x = sent ? m.right : m.left;
        if (x != tcL || m.top != tcT || r != tcR || sent != tcSent) {
            tcL = x; tcT = m.top; tcR = r; tcSent = sent;
            p.reset();
            if (sent) {                                    // tail at the top-RIGHT
                p.moveTo(m.right, m.top);
                p.lineTo(m.right - r, m.top);
                tailCornerOval.set(m.right - 2f * r, m.top, m.right, m.top + 2f * r);
                p.arcTo(tailCornerOval, 270f, 90f, false);
            } else {                                       // tail at the top-LEFT
                p.moveTo(m.left, m.top);
                p.lineTo(m.left, m.top + r);
                tailCornerOval.set(m.left, m.top, m.left + 2f * r, m.top + 2f * r);
                p.arcTo(tailCornerOval, 180f, 90f, false);
            }
            p.close();
        }
        canvas.drawPath(p, paint);
    }

    void draw(Canvas canvas, int hPad, int vPad, boolean spinnerHandledSeparately) {
        float r = MessageBubbleCanvasView.MEDIA_CORNER_RADIUS_DP * host.density;
        // BUG FIX: defense-in-depth alongside MessagePagingAdapter's
        // safeCacheCopy()/putBitmapSafely() — this is the last line of
        // defense right at the point BitmapShader construction previously
        // crashed with "Cannot create BitmapShader for recycled bitmap".
        // Treat a recycled bitmap exactly like no bitmap at all: skip
        // drawing it (the placeholder/blank bubble background still shows)
        // instead of crashing the whole draw pass. Also drop the stale
        // shader cache so a later, valid bitmap for this same view isn't
        // compared against a recycled one by reference.
        if (host.mediaBitmap != null && host.mediaBitmap.isRecycled()) {
            cachedShader = null;
            cachedShaderBitmap = null;
            host.mediaBitmap = null;
        }
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
                // soft preview instead. See resolvePlaceholderShaderSource() —
                // this only runs on a genuine cache miss (bitmap or rect
                // changed), same as the shader build itself, so it costs
                // nothing on the 30-60fps redraw path.
                // PERF (v611): the blur is now served from a process-wide cache and,
                // on a miss, computed on a background thread — see
                // resolvePlaceholderShaderSource(). A miss returns the raw (already
                // soft) 32x32 placeholder for this frame and re-draws when the blur lands.
                android.graphics.Bitmap shaderSourceBitmap = host.mediaBitmapIsPlaceholder
                        ? resolvePlaceholderShaderSource(host.mediaBitmap, host.mediaRect.width(), host.mediaRect.height(), host.density)
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
            squareTailCorner(canvas, host.mediaBitmapPaint, r);
        } else {
            // Not decoded yet — plain placeholder box, same rounded shape.
            // Also drop any stale cached shader so a bitmap arriving later
            // is guaranteed to take the fresh-build path above (cachedShaderBitmap
            // would otherwise still reference the old bitmap only by luck of
            // object identity never colliding — clearing is the safe explicit choice).
            cachedShader = null;
            cachedShaderBitmap = null;
            canvas.drawRoundRect(host.mediaRect, r, r, host.mediaPlaceholderPaint);
            squareTailCorner(canvas, host.mediaPlaceholderPaint, r);
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
            float tw = host.textPaint.measureText(gifBadgeLabel());
            float bw = tw + badgePad * 2f;
            float bh = badgeTsz + badgePad * 2f;
            float bx = host.mediaRect.left + 6f * host.density;
            float by = host.mediaRect.top  + 6f * host.density;
            gifBadgeRectF.set(bx, by, bx + bw, by + bh);
            canvas.drawRoundRect(gifBadgeRectF, badgeR, badgeR, gifBadgeBgPaint);
            host.textPaint.setColor(0xFFFFFFFF);
            canvas.drawText(gifBadgeLabel(), bx + badgePad, by + badgePad + badgeTsz * 0.85f, host.textPaint);
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
        // v849: ONLY the photo + static-waveform strip layout exists now. The old dark
        // pill (play | m:ss | 1x | download) drawn over the photo was removed.
        if (!host.voiceStripRect.isEmpty()) {
            if (!host.voiceStripLive()) drawVoiceStrip(canvas); // live: drawn in the overlay layer
        } else {
            host.voiceBadgeRect.setEmpty();
            host.voiceSpeedRect.setEmpty();
            host.voiceDownloadRect.setEmpty();
        }

        if (host.mediaGated) {
            // Manual-download/upload gate covers the whole slot (idle pill or
            // live spinner/percentage). The captionless timestamp pill below
            // stays visible on top of it (same as the group bubble) so time
            // — and, for a sent message still uploading/failed, the pending
            // clock — never disappears. The caption row + footer stay hidden
            // while gated, exactly as before.
            // v877: the scrim is live too (drawMediaGateScrimLive from the overlay layer) so gate
            // transitions on a captionless bubble never dirty the cached node.
            if (host.mediaHasCaption && host.textLayout != null) return;
        }

        if (host.mediaHasCaption && host.textLayout != null) {
            float captionTop = host.mediaRect.bottom + host.voiceStripRect.height()
                    + MessageBubbleCanvasView.MEDIA_CAPTION_GAP_DP * host.density;
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
        } else if (!host.voiceStripRect.isEmpty()) {
            host.readMoreRect.setEmpty(); // v845: time/ticks already drawn inside the voice strip
        } else {
            host.readMoreRect.setEmpty();
            // v852: the footer strip (time + tick) is NOT part of the cached bubble node any more —
            // it is drawn live from drawDynamicOverlayLayer() via drawFooterStrip().
        }
    }

    /**
     * v852: captionless image/video/gif — time + tick in a slim strip BELOW the photo, drawn in the
     * live overlay layer so a tick/expiry/clock change only redraws this rect (the cached bubble node
     * with the photo is never re-recorded). Plain text + tick, no background/border.
     */
    void drawFooterStrip(Canvas canvas) {
        if (!host.hasFooterStrip()) return;
        final float d = host.density;
        final RectF f = host.mediaFooterRect;
        f.set(host.mediaRect.left, host.mediaRect.bottom, host.mediaRect.right,
                host.mediaRect.bottom + MessageBubbleCanvasView.MEDIA_FOOTER_STRIP_DP * d);
        final float pad = 4f * d;
        final float cy = f.centerY();
        final float tickReserve = host.sent ? (MessageBubbleCanvasView.TICK_SIZE_DP + MessageBubbleCanvasView.TICK_GAP_DP) * d : 0f;
        final float rightX = f.right - pad;
        host.footerPaint.getFontMetrics(host.footerFmScratch);
        final float base = cy - (host.footerFmScratch.ascent + host.footerFmScratch.descent) / 2f;
        final float timeX = rightX - host.footerTimeTextWidth() - tickReserve;
        canvas.drawText(host.footerTimeText, timeX, base, host.footerPaint);
        if (host.hasExpiry) canvas.drawText(host.expiryText, timeX - host.expiryReserveWidth(), base, host.expiryPaint);
        if (host.sent) {
            // Sent + gated = upload queued/running/failed -> pending clock instead of the tick.
            host.drawTick(canvas, rightX - MessageBubbleCanvasView.TICK_SIZE_DP * d, base, host.mediaGated, true);
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

    /** v877: scrim over the media slot while gated — live layer (was baked into the cached node). */
    void drawMediaGateScrimLive(Canvas canvas) {
        drawMediaGateScrim(canvas);
    }

    /** v877: play glyph is live (hidden while gated) so a gate transition needs no re-record. */
    void drawVideoPlayGlyphLive(Canvas canvas) {
        if (host.mediaGated || host.mediaRect.isEmpty()) return;
        drawVideoPlayGlyph(canvas);
    }

    private void drawVideoPlayGlyph(Canvas canvas) {
        {
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
        }
    }

    private void drawVideoPlayOverlay(Canvas canvas) {
        // v877: the play glyph is drawn live (drawVideoPlayGlyphLive); only the duration badge is
        // part of the cached node. (v874: glyph hidden while gated — still true, now live.)
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

    // ── v845: voice-on-photo strip (static waveform) ────────────────────────
    // Perf notes: zero allocations per draw; waveform is ONE cached Path in local coords
    // (rebuilt only when bar count / seed changes); played part = clipRect over the same
    // Path, so playback costs one extra drawPath, and nothing at all while idle.
    private Paint stripCirclePaint, stripWaveIdlePaint, stripWavePlayedPaint, stripLinePaint;
    private android.text.TextPaint stripDurPaint;
    // v846: waveform = shared ALPHA_8 mask bitmap (1 byte/px), tinted at draw time. One drawBitmap
    // for idle + one clipped drawBitmap for the played part; built once per (seed, size).
    private static final android.util.LruCache<String, android.graphics.Bitmap> sWaveMasks =
            new android.util.LruCache<String, android.graphics.Bitmap>(2560 * 1024) {
                @Override protected int sizeOf(String k, android.graphics.Bitmap b) { return b.getByteCount(); }
            };
    private String stripDurKeyTotal, stripDurKeySpeed;
    private String stripCurIn, stripCurSpeedIn, stripCurOut;
    private float stripDurW;

    private void ensureStripRes() {
        if (stripCirclePaint != null) return;
        ensureVoiceRes();
        stripCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripCirclePaint.setColor(MessageBubbleCanvasView.AUDIO_BTN_BG_COLOR);
        stripWaveIdlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripWavePlayedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripWavePlayedPaint.setColor(MessageBubbleCanvasView.AUDIO_BTN_BG_COLOR);
        stripLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripLinePaint.setStrokeWidth(Math.max(1f, host.density));
        stripDurPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        stripDurPaint.setTextSize(host.spToPx(12f));
    }

    // v851: per-draw zero-alloc lookup. The last mask used by THIS view is remembered by
    // (seed, w, h) ints, so a redraw (progress tick, tick-color change) never builds the String
    // key nor touches the LRU. Seeds are bucketed into 64 shared patterns: a fast scroll over
    // many voice photos reuses the same few ALPHA_8 masks instead of building one per message.
    private android.graphics.Bitmap lastMask;
    private int lastMaskSeed, lastMaskW, lastMaskH;

    private android.graphics.Bitmap waveMask(int wPx, int hPx, float barW, float step, int seedIn) {
        final int seed = (seedIn ^ (seedIn >>> 16)) & 63;
        if (lastMask != null && seed == lastMaskSeed && wPx == lastMaskW && hPx == lastMaskH
                && !lastMask.isRecycled()) return lastMask;
        final String key = seed + "_" + wPx + "_" + hPx;
        android.graphics.Bitmap m = sWaveMasks.get(key);
        if (m != null) { lastMask = m; lastMaskSeed = seed; lastMaskW = wPx; lastMaskH = hPx; return m; }
        m = android.graphics.Bitmap.createBitmap(wPx, hPx, android.graphics.Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        int bars = Math.max(1, (int) (wPx / step));
        float realStep = wPx / (float) bars;
        float r = barW / 2f, cy = hPx / 2f;
        int s = seed;
        for (int i = 0; i < bars; i++) {
            s = s * 1103515245 + 12345;
            float f = 0.25f + 0.75f * (((s >>> 16) & 0x7FFF) / 32767f);
            float h = Math.max(barW, hPx * f);
            float x = i * realStep;
            c.drawRoundRect(x, cy - h / 2f, x + barW, cy + h / 2f, r, r, p);
        }
        sWaveMasks.put(key, m);
        lastMask = m; lastMaskSeed = seed; lastMaskW = wPx; lastMaskH = hPx;
        return m;
    }

    void drawVoiceStrip(Canvas canvas) {
        ensureStripRes();
        final float d = host.density;
        final RectF s = host.voiceStripRect;
        final boolean downloading = host.voiceDownloading;
        final float pad = 8f * d, gap = 8f * d, circleD = 34f * d;
        final float cy = s.centerY();
        final int textColor = host.textPaint.getColor();

        // 1. play/pause circle
        final float cx = s.left + pad + circleD / 2f;
        canvas.drawCircle(cx, cy, circleD / 2f, stripCirclePaint);
        if (downloading) {
            float base = cy - (voiceBadgeDurPaint.ascent() + voiceBadgeDurPaint.descent()) / 2f;
            canvas.drawText(DOWNLOADING_LABEL, cx - voiceBadgeDurPaint.measureText(DOWNLOADING_LABEL) / 2f,
                    base, voiceBadgeDurPaint);
        } else if (host.isVoicePlaying) {
            float barW = circleD * 0.11f, barH = circleD * 0.38f, barGap = circleD * 0.13f, br = 1.5f * d;
            canvas.drawRoundRect(cx - barGap / 2f - barW, cy - barH / 2f, cx - barGap / 2f, cy + barH / 2f, br, br, voiceBadgeIconPaint);
            canvas.drawRoundRect(cx + barGap / 2f, cy - barH / 2f, cx + barGap / 2f + barW, cy + barH / 2f, br, br, voiceBadgeIconPaint);
        } else {
            float t = circleD * 0.22f;
            voiceBadgeGlyphPath.reset();
            voiceBadgeGlyphPath.moveTo(cx - t * 0.55f, cy - t * 0.85f);
            voiceBadgeGlyphPath.lineTo(cx - t * 0.55f, cy + t * 0.85f);
            voiceBadgeGlyphPath.lineTo(cx + t * 0.95f, cy);
            voiceBadgeGlyphPath.close();
            canvas.drawPath(voiceBadgeGlyphPath, voiceBadgeIconPaint);
        }

        // 2. right side: time + ticks (what used to be the pill on the photo)
        final float tickReserve = host.sent ? (MessageBubbleCanvasView.TICK_SIZE_DP + MessageBubbleCanvasView.TICK_GAP_DP) * d : 0f;
        final float timeW = host.footerTimeTextWidth() + tickReserve + host.expiryReserveWidth();
        final float rightX = s.right - pad;
        final float timeLeft = rightX - timeW;
        host.footerPaint.getFontMetrics(host.footerFmScratch);
        final float timeBase = cy - (host.footerFmScratch.ascent + host.footerFmScratch.descent) / 2f;
        final float timeX = rightX - host.footerTimeTextWidth() - tickReserve;
        canvas.drawText(host.footerTimeText, timeX, timeBase, host.footerPaint);
        if (host.hasExpiry) canvas.drawText(host.expiryText, timeX - host.expiryReserveWidth(), timeBase, host.expiryPaint);
        if (host.sent) host.drawTick(canvas, rightX - MessageBubbleCanvasView.TICK_SIZE_DP * d, timeBase, host.mediaGated, true);

        // 3. divider + m:ss (elapsed while playing, else total; speed only when != 1x)
        final float dividerX = timeLeft - gap;
        stripLinePaint.setColor(textColor);
        stripLinePaint.setAlpha(60);
        canvas.drawLine(dividerX, s.top + 12f * d, dividerX, s.bottom - 12f * d, stripLinePaint);
        final String total = host.voiceDuration != null ? host.voiceDuration : "0:00";
        final String speed = host.voiceSpeedLabel != null ? host.voiceSpeedLabel : "1×";
        final boolean showSpeed = !"1×".equals(speed);
        if (!total.equals(stripDurKeyTotal) || !speed.equals(stripDurKeySpeed)) {
            stripDurKeyTotal = total;
            stripDurKeySpeed = speed;
            stripDurW = stripDurPaint.measureText(showSpeed ? total + " " + speed : total);
        }
        String cur = (host.isVoicePlaying && host.voiceElapsedText != null && !host.voiceElapsedText.isEmpty())
                ? host.voiceElapsedText : total;
        if (showSpeed) {
            // v851: elapsed text changes ~1/s but we draw ~4/s while playing — reuse the concat.
            if (!cur.equals(stripCurIn) || !speed.equals(stripCurSpeedIn)) {
                stripCurIn = cur; stripCurSpeedIn = speed; stripCurOut = cur + " " + speed;
            }
            cur = stripCurOut;
        }
        stripDurPaint.setColor(textColor);
        stripDurPaint.setAlpha(200);
        final float durRight = dividerX - gap;
        final float durLeft = durRight - stripDurW;
        final float durBase = cy - (stripDurPaint.ascent() + stripDurPaint.descent()) / 2f;
        canvas.drawText(cur, durLeft, durBase, stripDurPaint);

        // 4. static waveform between the circle and the duration
        final float waveLeft = cx + circleD / 2f + gap;
        final float waveRight = durLeft - gap;
        final float waveW = waveRight - waveLeft;
        if (waveW > 8f * d) {
            final int wPx = Math.round(waveW);
            final int hPx = Math.round(24f * d);
            final int seed = host.voiceUrl != null ? host.voiceUrl.hashCode() : 1;
            final android.graphics.Bitmap mask = waveMask(wPx, hPx, 2.5f * d, 4.5f * d, seed);
            final float top = cy - hPx / 2f;
            stripWaveIdlePaint.setColor(textColor);
            stripWaveIdlePaint.setAlpha(90);
            canvas.drawBitmap(mask, waveLeft, top, stripWaveIdlePaint);
            if (host.voiceProgress > 0f) {
                canvas.save();
                canvas.clipRect(waveLeft, top, waveLeft + wPx * host.voiceProgress, top + hPx);
                canvas.drawBitmap(mask, waveLeft, top, stripWavePlayedPaint);
                canvas.restore();
            }
        }

        // 5. hit rects: circle + waveform = play/pause; duration = speed. No download button here.
        host.voiceBadgeRect.set(s.left, s.top, Math.max(waveRight, cx + circleD / 2f + pad), s.bottom);
        if (downloading) {
            host.voiceSpeedRect.setEmpty();
        } else {
            host.voiceSpeedRect.set(durLeft - 6f * d, s.top, durRight + 6f * d, s.bottom);
        }
        host.voiceDownloadRect.setEmpty();
    }

    private static final String DOWNLOADING_LABEL = "\u2026"; // "…" — fetching, nothing to show yet


    /**
     * Draws the single-media download gate: a dim scrim over mediaRect plus
     * a centered pill — idle "⬇ <label>" (tap to start) when !mediaDownloading,
     * or a live spinner/percentage ring while mediaDownloading is true. See
     * setMediaDownloadGate()/setMediaDownloadProgress().
     */
    private void drawMediaGateScrim(Canvas canvas) {
        float r = MessageBubbleCanvasView.MEDIA_CORNER_RADIUS_DP * host.density;
        canvas.drawRoundRect(host.mediaRect, r, r, host.mediaGateScrimPaint);
        squareTailCorner(canvas, host.mediaGateScrimPaint, r);
    }

    /** v853: gate pill, drawn from the host's overlay layer on every progress/spinner tick. */
    void drawMediaGatePillLive(Canvas canvas) {
        final boolean spinnerHandledSeparately = false;
        final int drawnPercent = host.mediaDownloadProgress >= 0
                ? Math.max(0, Math.min(100, Math.round(host.mediaDisplayProgress))) : -1;
        String label = host.mediaDownloading
                ? (drawnPercent >= 0 ? drawnPercent + "%" : "")
                : (host.mediaDownloadLabel.isEmpty() ? photoLabel() : host.mediaDownloadLabel);

        float iconSize = MessageBubbleCanvasView.GROUP_GATE_PILL_ICON_DP * host.density;
        float iconGap = MessageBubbleCanvasView.GROUP_GATE_PILL_ICON_GAP_DP * host.density;
        float padH = MessageBubbleCanvasView.GROUP_GATE_PILL_PAD_H_DP * host.density;
        float padV = MessageBubbleCanvasView.GROUP_GATE_PILL_PAD_V_DP * host.density;
        // upload extras ("Compressing 34%", "45% · 5.4 MB / 12 MB"); plain "NN%" when it doesn't fit
        label = host.uploadProgressLabel(label, padH * 2 + iconSize + iconGap);
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
                host.drawProgressRing(canvas, iconCx, iconCy, iconSize, host.mediaGatePillIconPaint,
                        host.mediaDownloadProgress >= 0 ? drawnPercent : host.mediaDownloadProgress);
            }
            if (host.mediaUploadCancelable) {
                // X inside the ring = "tap to cancel". Static, so it lives in the cached Picture
                // while only the ring itself animates (drawIndeterminateSpinnerOnly).
                float d = iconSize * 0.2f;
                canvas.drawLine(iconCx - d, iconCy - d, iconCx + d, iconCy + d, host.mediaGatePillIconPaint);
                canvas.drawLine(iconCx - d, iconCy + d, iconCx + d, iconCy - d, host.mediaGatePillIconPaint);
            }
        } else if (host.mediaGateRetry) {
            drawRetryIcon(canvas, iconCx, iconCy, iconSize, host.mediaGatePillIconPaint);
        } else if (host.mediaGateWaiting) {
            drawWaitingIcon(canvas, iconCx, iconCy, iconSize, host.mediaGatePillIconPaint);
        } else {
            host.drawGateIcon(canvas, iconCx, iconCy, iconSize, host.mediaGatePillIconPaint);
        }

        if (!label.isEmpty()) {
            float textBaselineY = host.mediaGatePillRect.centerY()
                    - (host.mediaGatePillTextPaint.ascent() + host.mediaGatePillTextPaint.descent()) / 2f;
            canvas.drawText(label, iconCx + iconSize / 2f + iconGap, textBaselineY, host.mediaGatePillTextPaint);
        }
    }

    /** Static clock (circle + two hands): upload queued, waiting for the network. */
    void drawWaitingIcon(Canvas canvas, float cx, float cy, float size, Paint paint) {
        float r = size * 0.36f;
        canvas.drawCircle(cx, cy, r, paint);
        canvas.drawLine(cx, cy, cx, cy - r * 0.65f, paint);
        canvas.drawLine(cx, cy, cx + r * 0.5f, cy, paint);
    }

    private final RectF retryArcRect = new RectF();
    private final android.graphics.Path retryHeadPath = new android.graphics.Path();

    /** Circular "retry" arrow (open ring with an arrowhead) — failed upload/download, not a download arrow. */
    void drawRetryIcon(Canvas canvas, float cx, float cy, float size, Paint paint) {
        float r = size * 0.36f;
        retryArcRect.set(cx - r, cy - r, cx + r, cy + r);
        final float start = -60f, sweep = 300f;
        canvas.drawArc(retryArcRect, start, sweep, false, paint);
        double end = Math.toRadians(start + sweep);
        float ex = cx + (float) Math.cos(end) * r, ey = cy + (float) Math.sin(end) * r;
        // clockwise travel direction at the arc end, arrowhead points back along it ±40°
        double tx = -Math.sin(end), ty = Math.cos(end);
        float h = size * 0.3f;
        retryHeadPath.reset();
        for (int s = -1; s <= 1; s += 2) {
            double a = Math.toRadians(180 + 40 * s);
            float bx = (float) (tx * Math.cos(a) - ty * Math.sin(a));
            float by = (float) (tx * Math.sin(a) + ty * Math.cos(a));
            retryHeadPath.moveTo(ex, ey);
            retryHeadPath.lineTo(ex + bx * h, ey + by * h);
        }
        canvas.drawPath(retryHeadPath, paint);
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

    // ── PERF (v611): process-wide placeholder-blur cache + background compute ──
    // Before: every recycled bubble view re-blurred the SAME ThumbHash placeholder on
    // the main thread (big bubbles even did a synchronous HardwareRenderer round trip
    // with setWaitForPresent(true)). The blurred result only depends on
    // (placeholder bitmap identity, blur radius, native-vs-java), and ThumbHashPlaceholder
    // hands the identical Bitmap instance to every bubble sharing a hash, so one static
    // cache serves every view. Radius is already quantized by adaptiveBlurRadiusFor(),
    // which is the "bucketed size" part of the key. A miss is computed off the main thread.
    private static final class PlaceholderBlurKey {
        final android.graphics.Bitmap src;
        final int radius;
        final boolean nativeBlur;
        PlaceholderBlurKey(android.graphics.Bitmap src, int radius, boolean nativeBlur) {
            this.src = src; this.radius = radius; this.nativeBlur = nativeBlur;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof PlaceholderBlurKey)) return false;
            PlaceholderBlurKey k = (PlaceholderBlurKey) o;
            return k.src == src && k.radius == radius && k.nativeBlur == nativeBlur;
        }
        @Override public int hashCode() {
            return (System.identityHashCode(src) * 31 + radius) * 2 + (nativeBlur ? 1 : 0);
        }
    }

    // 32x32 placeholders (~4 KB each): 48 entries is noise next to one decoded photo.
    private static final android.util.LruCache<PlaceholderBlurKey, android.graphics.Bitmap> PLACEHOLDER_BLUR_CACHE =
            new android.util.LruCache<>(48);
    // Main-thread only: key -> views waiting for that blur to finish.
    private static final java.util.HashMap<PlaceholderBlurKey, java.util.ArrayList<Runnable>> PLACEHOLDER_BLUR_WAITERS =
            new java.util.HashMap<>();
    private static final java.util.concurrent.ExecutorService PLACEHOLDER_BLUR_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "bubble-placeholder-blur");
                    t.setDaemon(true);
                    return t;
                }
            });
    private static final android.os.Handler PLACEHOLDER_BLUR_MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * v878: warm PLACEHOLDER_BLUR_CACHE for a placeholder that is about to be shown in a slot of the given
     * size, from ANY thread. Same key / compute path as {@link #resolvePlaceholderShaderSource}, so the first
     * draw hits the cache and there is no second record when a blur lands. A size mismatch just misses.
     */
    static void prewarmPlaceholderBlur(final android.graphics.Bitmap src, float slotWpx, float slotHpx, float density) {
        if (src == null || src.isRecycled()) return;
        final int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return;
        final int radius = adaptiveBlurRadiusFor(slotWpx, slotHpx, Math.max(w, h));
        if (radius <= PLACEHOLDER_MIN_BLUR_RADIUS) return;
        final boolean useNative = Math.max(slotWpx, slotHpx) >= NATIVE_BLUR_MIN_BUBBLE_DP * density
                && com.callx.app.utils.NativeBlur.isSupported();
        final PlaceholderBlurKey key = new PlaceholderBlurKey(src, radius, useNative);
        if (PLACEHOLDER_BLUR_CACHE.get(key) != null) return;
        PLACEHOLDER_BLUR_MAIN.post(new Runnable() {
            @Override public void run() {
                // WAITERS is main-thread-only
                if (PLACEHOLDER_BLUR_CACHE.get(key) != null || PLACEHOLDER_BLUR_WAITERS.containsKey(key)) return;
                PLACEHOLDER_BLUR_WAITERS.put(key, new java.util.ArrayList<Runnable>(1));
                PLACEHOLDER_BLUR_EXEC.execute(new Runnable() {
                    @Override public void run() {
                        android.graphics.Bitmap result = null;
                        try { result = computePlaceholderBlur(src, radius, useNative); } catch (Throwable ignored) {}
                        final android.graphics.Bitmap toCache = result != null ? result : src;
                        PLACEHOLDER_BLUR_MAIN.post(new Runnable() {
                            @Override public void run() {
                                PLACEHOLDER_BLUR_CACHE.put(key, toCache);
                                java.util.ArrayList<Runnable> done = PLACEHOLDER_BLUR_WAITERS.remove(key);
                                if (done != null) for (Runnable r : done) r.run();
                            }
                        });
                    }
                });
            }
        });
    }

    /**
     * Main thread (called from draw()). Returns the blurred placeholder if it is cached;
     * otherwise returns {@code src} for this frame, schedules the blur on a background
     * thread and invalidates this bubble when it is ready.
     */
    private android.graphics.Bitmap resolvePlaceholderShaderSource(final android.graphics.Bitmap src,
            float bubbleWidthPx, float bubbleHeightPx, float density) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        final int radius = adaptiveBlurRadiusFor(bubbleWidthPx, bubbleHeightPx, Math.max(w, h));
        if (radius <= PLACEHOLDER_MIN_BLUR_RADIUS) return src;

        final boolean useNative = Math.max(bubbleWidthPx, bubbleHeightPx) >= NATIVE_BLUR_MIN_BUBBLE_DP * density
                && com.callx.app.utils.NativeBlur.isSupported();
        final PlaceholderBlurKey key = new PlaceholderBlurKey(src, radius, useNative);

        android.graphics.Bitmap hit = PLACEHOLDER_BLUR_CACHE.get(key);
        if (hit != null && !hit.isRecycled()) return hit;

        // This view re-draws once the blur lands: drop the shader built from the raw
        // placeholder (cachedShaderBitmap still points at `src`) and invalidate.
        Runnable onReady = new Runnable() {
            @Override public void run() {
                if (host.mediaBitmap == src) {
                    cachedShader = null;
                    cachedShaderBitmap = null;
                    host.invalidate();
                }
            }
        };
        java.util.ArrayList<Runnable> waiters = PLACEHOLDER_BLUR_WAITERS.get(key);
        if (waiters != null) { waiters.add(onReady); return src; } // already computing
        waiters = new java.util.ArrayList<>(2);
        waiters.add(onReady);
        PLACEHOLDER_BLUR_WAITERS.put(key, waiters);

        PLACEHOLDER_BLUR_EXEC.execute(new Runnable() {
            @Override public void run() {
                android.graphics.Bitmap result = null;
                android.os.Trace.beginSection("BubbleBlur#compute");
                try { result = computePlaceholderBlur(src, radius, useNative); } catch (Throwable ignored) {}
                finally { android.os.Trace.endSection(); }
                // Negative-cache a failure as `src` itself so it is never retried per draw.
                final android.graphics.Bitmap toCache = result != null ? result : src;
                PLACEHOLDER_BLUR_MAIN.post(new Runnable() {
                    @Override public void run() {
                        PLACEHOLDER_BLUR_CACHE.put(key, toCache);
                        java.util.ArrayList<Runnable> done = PLACEHOLDER_BLUR_WAITERS.remove(key);
                        if (done != null) for (Runnable r : done) r.run();
                    }
                });
            }
        });
        return src;
    }

    /**
     * Background-thread blur of the small ThumbHash placeholder bitmap, radius scaled to
     * how far mediaRect upscales it — never mutates `src`, since ThumbHashPlaceholder's
     * LruCache hands out the very same decoded Bitmap instance to every bubble sharing
     * that hash string; blurring in place would corrupt every other bubble currently
     * showing it.
     *
     * Big bubbles (>=300dp) try NativeBlur (RenderEffect) first and fall back to the
     * Java box blur below on any failure (old API level, OEM GPU quirk, etc.) — same
     * null-means-fall-back contract used throughout this pipeline.
     */
    private static android.graphics.Bitmap computePlaceholderBlur(android.graphics.Bitmap src, int radius,
            boolean useNative) {
        int w = src.getWidth(), h = src.getHeight();
        if (useNative) {
            android.graphics.Bitmap natively = com.callx.app.utils.NativeBlur.blur(src, radius);
            if (natively != null) return natively;
            // fall through to the Java path below
        }

        android.graphics.Bitmap copy = src.copy(
                src.getConfig() != null ? src.getConfig() : android.graphics.Bitmap.Config.ARGB_8888, true);
        if (copy == null) return null;
        int[] pixels = new int[w * h];
        copy.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] tmp = new int[Math.max(w, h)];
        // 3-pass box blur ~ Gaussian — same shape as TinyThumbBlurTransformation's,
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
