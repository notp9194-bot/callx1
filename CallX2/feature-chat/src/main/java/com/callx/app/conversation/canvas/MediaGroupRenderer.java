package com.callx.app.conversation.canvas;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;

/**
 * Draws the media-group (album grid) slot: per-cell centerCrop bitmap (or
 * placeholder), per-video play-glyph + duration badge, per-item caption
 * strip, "+N" overflow overlay, per-cell/master download gates, group
 * caption or captionless timestamp/tick pill.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file
 * split, no behavior change) — bind/measure/touch logic for the media
 * group stays on the host view; this class only owns the draw() call.
 *
 * PERF: this instance is created once per MessageBubbleCanvasView and
 * reused across every rebind while the view is recycled, so per-cell
 * BitmapShaders/gradients and scratch RectF/Path objects below are cached
 * as fields and only rebuilt when the underlying bitmap or geometry they
 * depend on actually changes — not on every draw() during scroll.
 */
final class MediaGroupRenderer {

    private final MessageBubbleCanvasView host;

    MediaGroupRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    // ── Per-cell BitmapShader cache ─────────────────────────────────────────
    // One slot per possible grid cell (GROUP_MAX_VISIBLE). A shader is only
    // rebuilt when the cell's bitmap reference or its scale/translate
    // (derived from the cell rect) actually changed since the last draw —
    // otherwise the previously-built shader is reused as-is.
    private final BitmapShader[] cellShaders = new BitmapShader[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final Bitmap[] cellShaderBitmaps = new Bitmap[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final float[] cellShaderScale = new float[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final float[] cellShaderDx = new float[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final float[] cellShaderDy = new float[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];

    // ── Other pooled scratch objects (avoid per-draw() `new RectF()`/`new
    // LinearGradient()` for the duration badge + caption-strip scrims) ─────
    private final RectF durBgRect = new RectF();

    // Per-cell caption-strip gradient cache (mirrors the BitmapShader cache
    // above): only rebuilt when the strip's top/bottom Y actually changes.
    private final LinearGradient[] itemCaptionGradients = new LinearGradient[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final float[] itemCaptionGradientTop = new float[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final float[] itemCaptionGradientBottom = new float[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];

    // Group-level caption scrim gradient — single slot, same rebuild-on-change rule.
    private LinearGradient groupScrimGradient;
    private float groupScrimGradientTop = Float.NaN;
    private float groupScrimGradientBottom = Float.NaN;

    // ── Ellipsize cache — per-cell file/audio label and per-item caption
    // only actually change on rebind, but the old code ran
    // TextUtils.ellipsize() on every single draw() during scroll, for every
    // visible cell. Cache the result per cell index and only recompute when
    // that cell's raw text or available width changes.
    private final String[] cellLabelRaw = new String[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final float[] cellLabelMaxW = new float[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final CharSequence[] cellLabelDisplay = new CharSequence[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final String[] cellCaptionRaw = new String[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final float[] cellCaptionMaxW = new float[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];
    private final CharSequence[] cellCaptionDisplay = new CharSequence[MessageBubbleCanvasView.GROUP_MAX_VISIBLE];

    void draw(Canvas canvas) {
        float cellR = MessageBubbleCanvasView.GROUP_CORNER_R * host.density;
        for (int i = 0; i < host.groupVisibleCount; i++) {
            RectF rect = host.groupRects[i];
            boolean isLastOverlay = (i == host.groupVisibleCount - 1) && host.groupRemaining > 0;
            GridItem item = i < host.groupItems.size() ? host.groupItems.get(i) : null;
            Bitmap bmp = host.groupBitmaps[i];
            boolean isAudioOrFileCell = item != null && (item.isAudio || item.isFile);

            if (isAudioOrFileCell) {
                // Audio/file cell — no thumbnail bitmap is ever requested for
                // these (see MessagePagingAdapter's per-cell Glide-load loop),
                // so just draw the dark placeholder + glyph + filename/duration
                // label, mirroring MediaGroupLayoutHelper.buildCell()'s
                // isAudio||isFile branch (icon ImageView + centered TextView).
                canvas.drawRoundRect(rect, cellR, cellR, host.groupFileCellBgPaint);
                drawFileOrAudioGlyph(canvas, rect, item.isAudio);

                String label = (item.label != null && !item.label.isEmpty())
                        ? item.label : (item.isAudio ? "Audio" : "File");
                float maxTextW = rect.width() - 4 * host.density;
                CharSequence ellipsized;
                if (cellLabelDisplay[i] != null && label.equals(cellLabelRaw[i]) && maxTextW == cellLabelMaxW[i]) {
                    ellipsized = cellLabelDisplay[i];
                } else {
                    ellipsized = TextUtils.ellipsize(
                            label, host.groupFileLabelPaint, maxTextW, TextUtils.TruncateAt.MIDDLE);
                    cellLabelRaw[i] = label;
                    cellLabelMaxW[i] = maxTextW;
                    cellLabelDisplay[i] = ellipsized;
                }
                float baseline = rect.bottom - 4 * host.density - host.groupFileLabelPaint.descent();
                canvas.drawText(ellipsized, 0, ellipsized.length(), rect.centerX(), baseline, host.groupFileLabelPaint);
            } else if (bmp != null) {
                float scale = Math.max(rect.width() / bmp.getWidth(), rect.height() / bmp.getHeight());
                float dx = rect.left - (bmp.getWidth() * scale - rect.width()) / 2f;
                float dy = rect.top - (bmp.getHeight() * scale - rect.height()) / 2f;

                BitmapShader shader = cellShaders[i];
                boolean needsRebuild = shader == null
                        || cellShaderBitmaps[i] != bmp
                        || cellShaderScale[i] != scale
                        || cellShaderDx[i] != dx
                        || cellShaderDy[i] != dy;
                if (needsRebuild) {
                    shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    host.groupShaderMatrix.reset();
                    host.groupShaderMatrix.setScale(scale, scale);
                    host.groupShaderMatrix.postTranslate(dx, dy);
                    shader.setLocalMatrix(host.groupShaderMatrix);
                    cellShaders[i] = shader;
                    cellShaderBitmaps[i] = bmp;
                    cellShaderScale[i] = scale;
                    cellShaderDx[i] = dx;
                    cellShaderDy[i] = dy;
                }
                host.groupBitmapPaint.setShader(shader);
                canvas.drawRoundRect(rect, cellR, cellR, host.groupBitmapPaint);
            } else {
                canvas.drawRoundRect(rect, cellR, cellR, host.groupCellBgPaint);
                // Bitmap for this cell was cleared (e.g. recycled bind) —
                // drop the stale shader cache so a later bitmap always
                // triggers a fresh build rather than comparing against a
                // now-irrelevant previous reference.
                cellShaders[i] = null;
                cellShaderBitmaps[i] = null;
            }

            if (item != null && item.isVideo && !isLastOverlay) {
                float cx = rect.centerX(), cy = rect.centerY();
                float circleR = (MessageBubbleCanvasView.GROUP_PLAY_CIRCLE_DP * host.density) / 2f;
                canvas.drawCircle(cx, cy, circleR, host.groupPlayCirclePaint);

                float triR = (MessageBubbleCanvasView.GROUP_PLAY_TRIANGLE_DP * host.density) / 2f;
                host.groupPlayTrianglePath.reset();
                host.groupPlayTrianglePath.moveTo(cx - triR * 0.5f, cy - triR * 0.8f);
                host.groupPlayTrianglePath.lineTo(cx - triR * 0.5f, cy + triR * 0.8f);
                host.groupPlayTrianglePath.lineTo(cx + triR * 0.9f, cy);
                host.groupPlayTrianglePath.close();
                canvas.drawPath(host.groupPlayTrianglePath, host.groupPlayTrianglePaint);

                boolean hasItemCaption = item.caption != null && !item.caption.isEmpty();
                if (item.duration != null && !item.duration.isEmpty()) {
                    float durPadH = 3 * host.density, durPadV = 1 * host.density;
                    float textW = host.groupDurationTextPaint.measureText(item.duration);
                    float textH = host.groupDurationTextPaint.descent() - host.groupDurationTextPaint.ascent();
                    if (hasItemCaption) {
                        // Duration moves to the top-end corner so it doesn't
                        // collide with the caption strip pinned to the bottom
                        // (same conflict-avoidance MediaGroupLayoutHelper uses).
                        float right = rect.right - 4 * host.density;
                        float top = rect.top + 4 * host.density;
                        durBgRect.set(right - textW - durPadH * 2, top, right, top + textH + durPadV * 2);
                    } else {
                        float left = rect.left + 4 * host.density;
                        float bottom = rect.bottom - 4 * host.density;
                        durBgRect.set(left, bottom - textH - durPadV * 2, left + textW + durPadH * 2, bottom);
                    }
                    canvas.drawRoundRect(durBgRect, 3 * host.density, 3 * host.density, host.groupDurationBgPaint);
                    float textBaseline = hasItemCaption
                            ? durBgRect.top + durPadV - host.groupDurationTextPaint.ascent()
                            : durBgRect.bottom - durPadV - host.groupDurationTextPaint.descent();
                    canvas.drawText(item.duration, durBgRect.left + durPadH, textBaseline, host.groupDurationTextPaint);
                }
            }

            // Per-item caption: small gradient strip + single-line ellipsized
            // text pinned to this cell's bottom edge — mirrors
            // MediaGroupLayoutHelper's per-item caption exactly, just Canvas-
            // drawn. Skipped on the "+N" overflow cell (nothing legible fits
            // under the dark overlay+count already covering it).
            if (item != null && !isLastOverlay && item.caption != null && !item.caption.isEmpty()) {
                float stripH = MessageBubbleCanvasView.GROUP_ITEM_CAPTION_STRIP_H_DP * host.density;
                float stripTop = rect.bottom - stripH;

                LinearGradient grad = itemCaptionGradients[i];
                if (grad == null || itemCaptionGradientTop[i] != stripTop || itemCaptionGradientBottom[i] != rect.bottom) {
                    grad = new LinearGradient(
                            0, stripTop, 0, rect.bottom, 0x00000000, 0x99000000, Shader.TileMode.CLAMP);
                    itemCaptionGradients[i] = grad;
                    itemCaptionGradientTop[i] = stripTop;
                    itemCaptionGradientBottom[i] = rect.bottom;
                }
                host.groupItemCaptionScrimPaint.setShader(grad);
                canvas.drawRect(rect.left, stripTop, rect.right, rect.bottom, host.groupItemCaptionScrimPaint);

                float margin = MessageBubbleCanvasView.GROUP_ITEM_CAPTION_MARGIN_DP * host.density;
                float maxTextW = rect.width() - margin * 2;
                CharSequence ellipsized;
                if (cellCaptionDisplay[i] != null && item.caption.equals(cellCaptionRaw[i]) && maxTextW == cellCaptionMaxW[i]) {
                    ellipsized = cellCaptionDisplay[i];
                } else {
                    ellipsized = TextUtils.ellipsize(item.caption, host.groupItemCaptionPaint, maxTextW, TextUtils.TruncateAt.END);
                    cellCaptionRaw[i] = item.caption;
                    cellCaptionMaxW[i] = maxTextW;
                    cellCaptionDisplay[i] = ellipsized;
                }
                float baseline = rect.bottom - MessageBubbleCanvasView.GROUP_ITEM_CAPTION_BOTTOM_DP * host.density - host.groupItemCaptionPaint.descent();
                canvas.drawText(ellipsized, 0, ellipsized.length(), rect.left + margin, baseline, host.groupItemCaptionPaint);
            }

            if (isLastOverlay) {
                canvas.drawRoundRect(rect, cellR, cellR, host.groupMoreOverlayPaint);
                canvas.drawText("+" + host.groupRemaining, rect.centerX(),
                        rect.centerY() - (host.groupMoreTextPaint.ascent() + host.groupMoreTextPaint.descent()) / 2f,
                        host.groupMoreTextPaint);
            }

            // Per-cell download badge — only drawn once the master gate
            // pill (below) has been dismissed; while the gate is up it
            // alone covers the whole grid, same as the old View-based
            // master overlay sitting on top of every per-cell overlay.
            if (!host.groupGateActive && !isLastOverlay
                    && i < host.groupCellPending.length && host.groupCellPending[i]) {
                canvas.drawRoundRect(rect, cellR, cellR, host.groupCellGateDimPaint);
                float cx = rect.centerX(), cy = rect.centerY();
                float badgeR = (MessageBubbleCanvasView.GROUP_CELL_GATE_BADGE_DP * host.density) / 2f;
                canvas.drawCircle(cx, cy, badgeR, host.groupCellGateBadgeBgPaint);
                boolean downloading = i < host.groupCellDownloading.length && host.groupCellDownloading[i];
                if (downloading) {
                    int prog = i < host.groupCellProgress.length ? host.groupCellProgress[i] : -1;
                    host.drawProgressRing(canvas, cx, cy, MessageBubbleCanvasView.GROUP_CELL_GATE_ICON_DP * host.density, host.groupCellGateIconPaint, prog);
                } else {
                    host.drawGateIcon(canvas, cx, cy, MessageBubbleCanvasView.GROUP_CELL_GATE_ICON_DP * host.density, host.groupCellGateIconPaint);
                }
            }
        }

        if (host.groupGateActive) {
            // Master "Download N photos" pill — mirrors
            // MediaGroupLayoutHelper.addMasterDownloadOverlay(): a single
            // dim scrim over the whole grid with a centered pill, tap
            // anywhere in the grid to dismiss + start every pending cell.
            canvas.drawRect(host.groupContentRect, host.groupGateScrimPaint);

            String label = "Download " + host.groupGatePendingCount
                    + (host.groupGatePendingCount == 1 ? " photo" : " photos");
            float iconSize = MessageBubbleCanvasView.GROUP_GATE_PILL_ICON_DP * host.density;
            float iconGap = MessageBubbleCanvasView.GROUP_GATE_PILL_ICON_GAP_DP * host.density;
            float padH = MessageBubbleCanvasView.GROUP_GATE_PILL_PAD_H_DP * host.density;
            float padV = MessageBubbleCanvasView.GROUP_GATE_PILL_PAD_V_DP * host.density;
            float textW = host.groupGatePillTextPaint.measureText(label);
            float contentH = Math.max(iconSize, host.groupGatePillTextPaint.descent() - host.groupGatePillTextPaint.ascent());
            float pillW = padH * 2 + iconSize + iconGap + textW;
            float pillH = padV * 2 + contentH;
            float cx = host.groupContentRect.centerX(), cy = host.groupContentRect.centerY();
            host.groupGatePillRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f);

            float pillR = MessageBubbleCanvasView.GROUP_GATE_PILL_CORNER_DP * host.density;
            canvas.drawRoundRect(host.groupGatePillRect, pillR, pillR, host.groupGatePillBgPaint);

            float iconCx = host.groupGatePillRect.left + padH + iconSize / 2f;
            float iconCy = host.groupGatePillRect.centerY();
            host.drawGateIcon(canvas, iconCx, iconCy, iconSize, host.groupGatePillIconPaint);

            float textBaselineY = host.groupGatePillRect.centerY()
                    - (host.groupGatePillTextPaint.ascent() + host.groupGatePillTextPaint.descent()) / 2f;
            canvas.drawText(label, iconCx + iconSize / 2f + iconGap, textBaselineY, host.groupGatePillTextPaint);
        }

        if (host.groupHasCaption && host.groupCaptionLayout != null) {
            float scrimTop = host.groupContentRect.bottom - MessageBubbleCanvasView.GROUP_CAPTION_SCRIM_H_DP * host.density;
            float scrimBottom = host.groupContentRect.bottom;
            if (groupScrimGradient == null || groupScrimGradientTop != scrimTop || groupScrimGradientBottom != scrimBottom) {
                groupScrimGradient = new LinearGradient(
                        0, scrimTop, 0, scrimBottom, 0x00000000, 0xAA000000, Shader.TileMode.CLAMP);
                groupScrimGradientTop = scrimTop;
                groupScrimGradientBottom = scrimBottom;
            }
            host.groupScrimPaint.setShader(groupScrimGradient);
            canvas.drawRect(host.groupContentRect.left, scrimTop, host.groupContentRect.right, host.groupContentRect.bottom, host.groupScrimPaint);

            canvas.save();
            canvas.translate(host.groupContentRect.left + 4 * host.density,
                    host.groupContentRect.bottom - host.groupCaptionLayout.getHeight() - 4 * host.density);
            host.groupCaptionLayout.draw(canvas);
            canvas.restore();

            // TICK ADVANCE (media-group bubbles): the caption scrim used to
            // stop at the caption text itself — a captioned group never drew
            // its timestamp/tick at all (the captionless branch below
            // already did, and so does MediaRenderer's captioned branch for
            // a single image/video). Reuses the same mediaPillTextPaint/
            // tickPaint styling as the captionless pill, just without a
            // separate pill background since the caption scrim already
            // darkens this corner.
            float pillPadH = MessageBubbleCanvasView.MEDIA_PILL_PADDING_H_DP * host.density;
            float textBaselineY = host.groupContentRect.bottom - pillPadH
                    - host.mediaPillTextPaint.descent();
            float tickReserve = host.sent ? (MessageBubbleCanvasView.TICK_SIZE_DP + MessageBubbleCanvasView.TICK_GAP_DP) * host.density : 0;
            float timeX = host.groupContentRect.right - pillPadH - tickReserve - host.mediaPillTextPaint.measureText(host.footerTimeText);
            canvas.drawText(host.footerTimeText, timeX, textBaselineY, host.mediaPillTextPaint);
            if (host.hasExpiry) {
                canvas.drawText(host.expiryText, timeX - host.expiryReserveWidth(), textBaselineY, host.expiryPaint);
            }
            if (host.sent) {
                host.drawTick(canvas, host.groupContentRect.right - pillPadH - MessageBubbleCanvasView.TICK_SIZE_DP * host.density, textBaselineY);
            }
        } else {
            // Captionless group: translucent timestamp/tick pill overlaid
            // on the grid's bottom-right corner — same treatment as the
            // single-image bubble's captionless pill.
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
                host.drawTick(canvas, host.mediaPillRect.right - pillPadH - MessageBubbleCanvasView.TICK_SIZE_DP * host.density, textBaselineY);
            }
        }
    }

    /**
     * Small centered glyph for a mixed-group audio/file cell — a simple
     * speaker/waveform shape for audio, a dog-eared page shape (same idea
     * as FileBubbleRenderer's file glyph) for file. Sits above the
     * filename/duration label, same vertical arrangement as the legacy
     * ImageView(icon)+TextView(label) pair in MediaGroupLayoutHelper.
     */
    private final Path fileOrAudioGlyphPath = new Path();

    private void drawFileOrAudioGlyph(Canvas canvas, RectF rect, boolean isAudio) {
        float cx = rect.centerX();
        // Icon center sits slightly above the cell's middle to leave room
        // for the label pinned to the bottom edge, mirroring the legacy
        // icon's bottomMargin=14dp inside a bottom-gravity TextView row.
        float cy = rect.centerY() - MessageBubbleCanvasView.GROUP_ITEM_CAPTION_MARGIN_DP * host.density;
        float glyphR = Math.min(rect.width(), rect.height()) * 0.16f;

        if (isAudio) {
            // Waveform-style glyph: three rounded bars of varying height.
            float barW = glyphR * 0.4f;
            float gap = glyphR * 0.3f;
            float[] heights = {glyphR * 0.9f, glyphR * 1.6f, glyphR * 1.1f};
            float totalW = barW * 3 + gap * 2;
            float x = cx - totalW / 2f;
            for (float h : heights) {
                canvas.drawRoundRect(x, cy - h / 2f, x + barW, cy + h / 2f,
                        barW / 2f, barW / 2f, host.groupFileGlyphPaint);
                x += barW + gap;
            }
        } else {
            // Dog-eared page glyph — same construction as FileBubbleRenderer's.
            fileOrAudioGlyphPath.reset();
            float fw = glyphR * 1.1f, fh = glyphR * 1.45f;
            float fx = cx - fw / 2f, fy = cy - fh / 2f;
            float fold = fw * 0.30f;
            fileOrAudioGlyphPath.moveTo(fx, fy + fold);
            fileOrAudioGlyphPath.lineTo(fx, fy + fh);
            fileOrAudioGlyphPath.lineTo(fx + fw, fy + fh);
            fileOrAudioGlyphPath.lineTo(fx + fw, fy);
            fileOrAudioGlyphPath.lineTo(fx + fw - fold, fy);
            fileOrAudioGlyphPath.close();
            canvas.drawPath(fileOrAudioGlyphPath, host.groupFileGlyphPaint);
        }
    }
}
