package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;

/**
 * FILE BUBBLE DRAW — card-style: icon circle | name+meta | action button.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file split,
 * no behavior change) — bind/measure/touch logic for the file bubble
 * stays on the host view; this class only owns the draw() call.
 *
 * PERF: this instance is created once per MessageBubbleCanvasView (see the
 * `final FileBubbleRenderer fileBubbleRenderer = ...` field on the host)
 * and reused across every rebind while the view is recycled by the
 * RecyclerView, so all Paint/Path objects below are allocated once in the
 * constructor and only mutated (color/style/geometry) inside draw() —
 * no per-frame `new Paint()`/`new Path()` churn during scroll.
 */
final class FileBubbleRenderer {

    private final MessageBubbleCanvasView host;

    // ── Pooled drawing objects — allocated once, reused every draw() ───────
    private final Paint iconBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path glyphPath = new Path();
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint actionBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint actionIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Shared by the "open" (right-arrow) and "download" (down-arrow) glyphs —
    // only one of the two is ever drawn per frame, so one reusable Path
    // covers both; reset() before each shape.
    private final Path actionArrowPath = new Path();

    // ── Ellipsize/measure cache — fileNameText/fileSizeMimeText only
    // change on rebind (bindFile()), but the old code ran measureText() +
    // TextUtils.ellipsize() on every single draw() during scroll. Cache the
    // displayed string and only recompute when either the raw text or the
    // available column width (textColW, tied to the card's layout) changes.
    private String lastNameRaw;
    private float lastNameColW = -1f;
    private String cachedNameDisplay;
    private String lastMetaRaw;
    private float lastMetaColW = -1f;
    private String cachedMetaDisplay;

    // ── Action-button geometry, cached for drawIndeterminateSpinnerOnly() ──
    // Set at the top of every draw() call (cheap — a few float ops derived
    // from host.bubbleRect + fixed dp constants), read back by
    // drawIndeterminateSpinnerOnly() so it draws the live ring at the exact
    // same spot without recomputing the whole layout.
    private float lastActionCx, lastActionCy, lastActionR;

    FileBubbleRenderer(MessageBubbleCanvasView host) {
        this.host = host;
        // Colors/styles that never change frame-to-frame are set once here.
        glyphPaint.setColor(0xFFFFFFFF);
        glyphPaint.setStyle(Paint.Style.FILL);
        actionBgPaint.setColor(0x22000000);
    }

    void draw(Canvas canvas) {
        draw(canvas, false);
    }

    /**
     * @param spinnerHandledSeparately when true, skips drawing the LIVE
     *        indeterminate progress ring (fileDownloadPercent < 0) inline —
     *        the action-button circle behind it is still drawn (static).
     *        Caller must then call drawIndeterminateSpinnerOnly() every
     *        frame on top of a cached Picture/RenderNode — see
     *        MessageBubbleCanvasView#drawFileBubbleWithOptionalCache().
     *        Mirrors MediaRenderer's spinnerHandledSeparately split.
     */
    void draw(Canvas canvas, boolean spinnerHandledSeparately) {
        int hPad = Math.round(MessageBubbleCanvasView.H_PADDING_DP * host.density);
        int vPad = Math.round(MessageBubbleCanvasView.V_PADDING_DP * host.density);
        int replyGap = host.hasReply ? Math.round(MessageBubbleCanvasView.REPLY_GAP_TO_MESSAGE_DP * host.density) : 0;
        float top  = host.bubbleTop + host.replyBoxHeight + replyGap + vPad;
        float left = host.bubbleRect.left + hPad;
        float rowPad  = MessageBubbleCanvasView.FILE_ROW_PAD_DP * host.density;
        float iconCol = MessageBubbleCanvasView.FILE_ICON_COL_DP * host.density;
        float actCol  = MessageBubbleCanvasView.FILE_ACTION_COL_DP * host.density;
        float contentW = host.bubbleRect.width() - hPad * 2f;

        // ── Icon circle ────────────────────────────────────────────────────────
        float cx = left + iconCol / 2f;
        float cy = top  + host.fileCardHeight / 2f;
        float cr = iconCol * 0.38f;
        iconBgPaint.setColor(host.fileIconColor);
        canvas.drawCircle(cx, cy, cr, iconBgPaint);
        // File glyph — simple dog-ear rectangle
        glyphPath.reset();
        float fw = cr * 0.55f, fh = cr * 0.72f;
        float fx = cx - fw / 2f, fy = cy - fh / 2f;
        float fold = fw * 0.30f;
        glyphPath.moveTo(fx, fy + fold);
        glyphPath.lineTo(fx, fy + fh);
        glyphPath.lineTo(fx + fw, fy + fh);
        glyphPath.lineTo(fx + fw, fy);
        glyphPath.lineTo(fx + fw - fold, fy);
        glyphPath.lineTo(fx, fy + fold);
        glyphPath.close();
        glyphPath.moveTo(fx, fy + fold);
        glyphPath.lineTo(fx + fw - fold, fy + fold);
        glyphPath.lineTo(fx + fw - fold, fy);
        canvas.drawPath(glyphPath, glyphPaint);

        // ── Action button (right column) ───────────────────────────────────────
        float aRight = host.bubbleRect.right - hPad;
        float aLeft  = aRight - actCol;
        float actCx  = aLeft + actCol / 2f;
        float actCy  = cy;
        float actR   = actCol * 0.38f;
        canvas.drawCircle(actCx, actCy, actR, actionBgPaint);
        // Store tap rect
        host.fileActionRect.set(actCx - actR, actCy - actR, actCx + actR, actCy + actR);
        // Cache geometry for drawIndeterminateSpinnerOnly()
        lastActionCx = actCx;
        lastActionCy = actCy;
        lastActionR  = actR;

        // Draw icon glyph: ⬇ (download arrow) or ⬗ (open/share square)
        actionIconPaint.setColor(host.sent ? 0xFF555555 : 0xFF008069);
        actionIconPaint.setStyle(Paint.Style.FILL);
        if (host.fileIsDownloading) {
            // Progress ring. Indeterminate (<0) is skipped here ONLY when the
            // caller is recording this into a cached Picture/RenderNode
            // (spinnerHandledSeparately) — it will draw the ring itself,
            // live, every frame, via drawIndeterminateSpinnerOnly() on top
            // of that cache.
            if (host.fileDownloadPercent >= 0 || !spinnerHandledSeparately) {
                host.drawProgressRing(canvas, actCx, actCy, actR * 1.4f, actionIconPaint, host.fileDownloadPercent);
            }
        } else if (host.fileIsCached) {
            // Open icon — simple right-pointing arrow
            actionArrowPath.reset();
            float as = actR * 0.45f;
            actionArrowPath.moveTo(actCx - as, actCy - as * 0.7f);
            actionArrowPath.lineTo(actCx + as, actCy);
            actionArrowPath.lineTo(actCx - as, actCy + as * 0.7f);
            actionIconPaint.setStyle(Paint.Style.STROKE);
            actionIconPaint.setStrokeWidth(host.density * 2f);
            canvas.drawPath(actionArrowPath, actionIconPaint);
        } else {
            // Download arrow
            actionArrowPath.reset();
            float as = actR * 0.42f;
            actionArrowPath.moveTo(actCx, actCy - as);
            actionArrowPath.lineTo(actCx, actCy + as * 0.5f);
            actionArrowPath.moveTo(actCx - as * 0.7f, actCy + as * 0.2f);
            actionArrowPath.lineTo(actCx, actCy + as * 0.9f);
            actionArrowPath.lineTo(actCx + as * 0.7f, actCy + as * 0.2f);
            actionIconPaint.setStyle(Paint.Style.STROKE);
            actionIconPaint.setStrokeWidth(host.density * 2f);
            canvas.drawPath(actionArrowPath, actionIconPaint);
        }

        // ── Name + meta text (centre column) ──────────────────────────────────
        float textLeft = left + iconCol + rowPad;
        float textRight = aLeft - rowPad;
        float textColW = Math.max(1f, textRight - textLeft);
        // File name
        host.textPaint.setTextSize(host.spToPx(13f));
        host.textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        host.textPaint.setColor(host.sent ? 0xFF1A1A1A : 0xFF1A1A1A);
        String displayName;
        if (cachedNameDisplay != null && host.fileNameText.equals(lastNameRaw) && textColW == lastNameColW) {
            displayName = cachedNameDisplay;
        } else {
            displayName = host.fileNameText;
            if (host.textPaint.measureText(host.fileNameText) > textColW) {
                displayName = android.text.TextUtils.ellipsize(
                        host.fileNameText, host.textPaint, textColW, android.text.TextUtils.TruncateAt.MIDDLE).toString();
            }
            lastNameRaw = host.fileNameText;
            lastNameColW = textColW;
            cachedNameDisplay = displayName;
        }
        float nameLineH = host.textPaint.getFontSpacing();
        float metaLineH;
        {
            host.textPaint.setTextSize(host.spToPx(10f));
            host.textPaint.setTypeface(Typeface.DEFAULT);
            metaLineH = host.textPaint.getFontSpacing();
            host.textPaint.setTextSize(host.spToPx(13f));
            host.textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        }
        float totalTxtH = nameLineH + 2f * host.density + metaLineH;
        float nameY = top + (host.fileCardHeight - totalTxtH) / 2f + nameLineH * 0.85f;
        canvas.drawText(displayName, textLeft, nameY, host.textPaint);
        // Meta (size · type)
        host.textPaint.setTextSize(host.spToPx(10f));
        host.textPaint.setTypeface(Typeface.DEFAULT);
        host.textPaint.setColor(0xFF888888);
        float metaY = nameY + 2f * host.density + metaLineH * 0.85f;
        String displayMeta;
        if (cachedMetaDisplay != null && host.fileSizeMimeText.equals(lastMetaRaw) && textColW == lastMetaColW) {
            displayMeta = cachedMetaDisplay;
        } else {
            displayMeta = host.fileSizeMimeText;
            if (host.textPaint.measureText(displayMeta) > textColW) {
                displayMeta = android.text.TextUtils.ellipsize(
                        displayMeta, host.textPaint, textColW, android.text.TextUtils.TruncateAt.END).toString();
            }
            lastMetaRaw = host.fileSizeMimeText;
            lastMetaColW = textColW;
            cachedMetaDisplay = displayMeta;
        }
        canvas.drawText(displayMeta, textLeft, metaY, host.textPaint);

        // ── Footer ─────────────────────────────────────────────────────────────
        int footerH = Math.round(host.spToPx(MessageBubbleCanvasView.FOOTER_TEXT_SP) + MessageBubbleCanvasView.FOOTER_GAP_DP * host.density);
        host.drawFooter(canvas, host.bubbleRect.bottom - vPad * 0.4f, host.bubbleRect.right - hPad);
    }

    /**
     * Draws ONLY the live indeterminate spinner ring, at the exact position
     * the last draw(canvas, true) call computed and cached in
     * lastActionCx/lastActionCy/lastActionR. Meant to be called every frame
     * on top of a cached Picture/RenderNode that has everything else
     * (icon circle, action-button circle, name/meta text, footer) already
     * baked in — see MessageBubbleCanvasView#drawFileBubbleWithOptionalCache().
     * No-ops unless still actually in the indeterminate-downloading state.
     */
    void drawIndeterminateSpinnerOnly(Canvas canvas) {
        if (!host.fileIsDownloading || host.fileDownloadPercent >= 0) return;
        actionIconPaint.setColor(host.sent ? 0xFF555555 : 0xFF008069);
        actionIconPaint.setStyle(Paint.Style.FILL);
        host.drawProgressRing(canvas, lastActionCx, lastActionCy, lastActionR * 1.4f, actionIconPaint, host.fileDownloadPercent);
    }
}
