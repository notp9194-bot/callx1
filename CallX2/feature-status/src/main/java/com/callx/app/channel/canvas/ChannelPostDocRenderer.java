package com.callx.app.channel.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Draws a document post card:
 *   • Rounded icon box with a file-type label (PDF, DOC, XLS, etc.)
 *   • Document file name (ellipsized, bold)
 *   • File size
 *
 * All geometry allocated in layout(), nothing allocated in draw().
 */
final class ChannelPostDocRenderer {

    private final ChannelPostCanvasView host;

    ChannelPostDocRenderer(ChannelPostCanvasView host) {
        this.host = host;
    }

    // Geometry
    private final RectF iconRect = new RectF();
    private float nameBaselineY;
    private float sizeBaselineY;
    private float rowBottom;
    private String nameEllipsized;
    private String lastNameRaw;
    private float lastNameMaxW = -1f;

    float layout(float left, float top, float right) {
        float iconSize = host.docIconSize;
        float gap      = host.docIconGap;

        iconRect.set(left, top, left + iconSize, top + iconSize);

        float textX  = iconRect.right + gap;
        float textW  = Math.max(1f, right - textX);

        // Ellipsize doc name
        String raw = host.docName != null ? host.docName : "Document";
        if (!raw.equals(lastNameRaw) || textW != lastNameMaxW) {
            nameEllipsized = android.text.TextUtils.ellipsize(
                    raw, host.docNamePaint, textW,
                    android.text.TextUtils.TruncateAt.MIDDLE).toString();
            lastNameRaw   = raw;
            lastNameMaxW  = textW;
        }

        Paint.FontMetrics nfm = host.docNamePaint.getFontMetrics();
        Paint.FontMetrics sfm = host.docSizePaint.getFontMetrics();
        float nameH  = nfm.descent - nfm.ascent;
        float sizeH  = sfm.descent - sfm.ascent;
        float gap2   = host.docTextLineGap;
        float blockH = nameH + gap2 + sizeH;
        float blockTop = top + (iconSize - blockH) / 2f;

        nameBaselineY = blockTop - nfm.ascent;
        sizeBaselineY = blockTop + nameH + gap2 - sfm.ascent;

        rowBottom = top + iconSize;
        return rowBottom;
    }

    void draw(Canvas canvas) {
        // Icon box
        canvas.drawRoundRect(iconRect, host.docIconCornerRadius,
                host.docIconCornerRadius, host.docIconBgPaint);

        // File type label inside icon box (centered)
        String typeLabel = host.docTypeLabel != null ? host.docTypeLabel : "FILE";
        float typeLabelX = iconRect.centerX();
        float typeLabelY = iconRect.centerY()
                - (host.docTypeLabelPaint.ascent() + host.docTypeLabelPaint.descent()) / 2f;
        canvas.drawText(typeLabel, typeLabelX, typeLabelY, host.docTypeLabelPaint);

        float textX = iconRect.right + host.docIconGap;

        // Name
        canvas.drawText(nameEllipsized != null ? nameEllipsized : "",
                textX, nameBaselineY, host.docNamePaint);

        // Size
        if (host.docSizeText != null && !host.docSizeText.isEmpty()) {
            canvas.drawText(host.docSizeText, textX, sizeBaselineY, host.docSizePaint);
        }
    }
}
