package com.callx.app.conversation.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * FILE BUBBLE DRAW — card-style: icon circle | name+meta | action button.
 *
 * Moved verbatim out of MessageBubbleCanvasView (feature-based file split,
 * no behavior change) — bind/measure/touch logic for the file bubble
 * stays on the host view; this class only owns the draw() call.
 */
final class FileBubbleRenderer {

    private final MessageBubbleCanvasView host;

    FileBubbleRenderer(MessageBubbleCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
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
        // Paints/paths below are pre-allocated fields on the host view
        // (fileIconBgPaint/fileGlyphPaint/fileActionBgPaint/fileActionIconPaint/
        // fileGlyphPath/fileActionIconPath) and reset+reused every draw()
        // instead of `new`'d per frame — avoids GC churn while fast-scrolling.
        float cx = left + iconCol / 2f;
        float cy = top  + host.fileCardHeight / 2f;
        float cr = iconCol * 0.38f;
        Paint iconBg = host.fileIconBgPaint;
        iconBg.setColor(host.fileIconColor);
        canvas.drawCircle(cx, cy, cr, iconBg);
        // File glyph — simple dog-ear rectangle
        android.graphics.Path fp = host.fileGlyphPath;
        fp.reset();
        float fw = cr * 0.55f, fh = cr * 0.72f;
        float fx = cx - fw / 2f, fy = cy - fh / 2f;
        float fold = fw * 0.30f;
        fp.moveTo(fx, fy + fold);
        fp.lineTo(fx, fy + fh);
        fp.lineTo(fx + fw, fy + fh);
        fp.lineTo(fx + fw, fy);
        fp.lineTo(fx + fw - fold, fy);
        fp.lineTo(fx, fy + fold);
        fp.close();
        fp.moveTo(fx, fy + fold);
        fp.lineTo(fx + fw - fold, fy + fold);
        fp.lineTo(fx + fw - fold, fy);
        Paint glyphPaint = host.fileGlyphPaint;
        glyphPaint.setColor(0xFFFFFFFF);
        glyphPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(fp, glyphPaint);

        // ── Action button (right column) ───────────────────────────────────────
        float aRight = host.bubbleRect.right - hPad;
        float aLeft  = aRight - actCol;
        float actCx  = aLeft + actCol / 2f;
        float actCy  = cy;
        float actR   = actCol * 0.38f;
        Paint actBg = host.fileActionBgPaint;
        actBg.setColor(0x22000000);
        canvas.drawCircle(actCx, actCy, actR, actBg);
        // Store tap rect
        host.fileActionRect.set(actCx - actR, actCy - actR, actCx + actR, actCy + actR);

        // Draw icon glyph: ⬇ (download arrow) or ⬗ (open/share square)
        Paint actIcon = host.fileActionIconPaint;
        actIcon.setColor(host.sent ? 0xFF555555 : 0xFF008069);
        actIcon.setStyle(Paint.Style.FILL);
        android.graphics.Path actPath = host.fileActionIconPath;
        actPath.reset();
        if (host.fileIsDownloading) {
            // Progress ring
            host.drawProgressRing(canvas, actCx, actCy, actR * 1.4f, actIcon, host.fileDownloadPercent);
        } else if (host.fileIsCached) {
            // Open icon — simple right-pointing arrow
            float as = actR * 0.45f;
            actPath.moveTo(actCx - as, actCy - as * 0.7f);
            actPath.lineTo(actCx + as, actCy);
            actPath.lineTo(actCx - as, actCy + as * 0.7f);
            actIcon.setStyle(Paint.Style.STROKE);
            actIcon.setStrokeWidth(host.density * 2f);
            canvas.drawPath(actPath, actIcon);
        } else {
            // Download arrow
            float as = actR * 0.42f;
            actPath.moveTo(actCx, actCy - as);
            actPath.lineTo(actCx, actCy + as * 0.5f);
            actPath.moveTo(actCx - as * 0.7f, actCy + as * 0.2f);
            actPath.lineTo(actCx, actCy + as * 0.9f);
            actPath.lineTo(actCx + as * 0.7f, actCy + as * 0.2f);
            actIcon.setStyle(Paint.Style.STROKE);
            actIcon.setStrokeWidth(host.density * 2f);
            canvas.drawPath(actPath, actIcon);
        }

        // ── Name + meta text (centre column) ──────────────────────────────────
        float textLeft = left + iconCol + rowPad;
        float textRight = aLeft - rowPad;
        float textColW = Math.max(1f, textRight - textLeft);
        // File name — fileNamePaint/fileMetaPaint are pre-configured once
        // (size/typeface/color never change for this bubble type) instead
        // of toggling the shared host.textPaint back and forth every
        // draw() call.
        String displayName = host.fileNameEllipsizeCache.get(
                host.fileNameText, host.fileNamePaint, textColW, android.text.TextUtils.TruncateAt.MIDDLE);
        float nameLineH = host.fileNamePaint.getFontSpacing();
        float metaLineH = host.fileMetaPaint.getFontSpacing();
        float totalTxtH = nameLineH + 2f * host.density + metaLineH;
        float nameY = top + (host.fileCardHeight - totalTxtH) / 2f + nameLineH * 0.85f;
        canvas.drawText(displayName, textLeft, nameY, host.fileNamePaint);
        // Meta (size · type)
        float metaY = nameY + 2f * host.density + metaLineH * 0.85f;
        String displayMeta = host.fileMetaEllipsizeCache.get(
                host.fileSizeMimeText, host.fileMetaPaint, textColW, android.text.TextUtils.TruncateAt.END);
        canvas.drawText(displayMeta, textLeft, metaY, host.fileMetaPaint);

        // ── Footer ─────────────────────────────────────────────────────────────
        int footerH = Math.round(host.spToPx(MessageBubbleCanvasView.FOOTER_TEXT_SP) + MessageBubbleCanvasView.FOOTER_GAP_DP * host.density);
        host.drawFooter(canvas, host.bubbleRect.bottom - vPad * 0.4f, host.bubbleRect.right - hPad);
    }
}
