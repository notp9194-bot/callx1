package com.callx.app.channel.canvas;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextUtils;

/**
 * Draws the post card header:
 *   • Optional pinned badge ("📌 Pinned") above the avatar row
 *   • Circular author avatar (photo or placeholder glyph)
 *   • Author name (bold, ellipsized)
 *   • Relative timestamp
 *   • Three-dot options button (admin/owner only)
 *
 * measure() computes all geometry and caches rects consumed by the host's
 * touch hit-testing; draw() only paints from that cached state — never
 * allocates during a draw pass.
 */
final class ChannelPostHeaderRenderer {

    private final ChannelPostCanvasView host;

    ChannelPostHeaderRenderer(ChannelPostCanvasView host) {
        this.host = host;
    }

    // Ellipsize cache — avoid re-shaping on every measure() when nothing changed.
    private String lastNameRaw;
    private float  lastNameMaxW = -1f;
    private String cachedNameDisplay;

    // Geometry cached by measure(), consumed by draw()
    private float pinnedBaselineY  = -1f;
    private float pinnedX;
    private boolean showOptions;
    private float optionsCx, optionsCy;
    private String nameToDraw = "";
    private float nameX, nameBaselineY;
    private boolean hasTs;
    private float tsBaselineY;

    // PERF: FontMetrics reused across measure() calls — getFontMetrics(fm) writes
    // into the existing object in-place, zero allocation per measure pass.
    private final Paint.FontMetrics pinnedFm = new Paint.FontMetrics();
    private final Paint.FontMetrics nameFm   = new Paint.FontMetrics();
    private final Paint.FontMetrics tsFm     = new Paint.FontMetrics();
    private final Paint.FontMetrics glyphFm  = new Paint.FontMetrics();

    float measure(float top) {
        float y = top;
        pinnedBaselineY = -1f;

        if (host.isPinned) {
            host.pinnedBadgePaint.getFontMetrics(pinnedFm); // in-place, zero alloc
            pinnedX = host.cardPadding;
            pinnedBaselineY = y - pinnedFm.ascent;
            y += (pinnedFm.descent - pinnedFm.ascent) + host.badgeMarginBottom;
        }

        float headerTop = y;
        float avatarSize = host.avatarSize;
        host.avatarRect.set(host.cardPadding, headerTop,
                host.cardPadding + avatarSize, headerTop + avatarSize);

        float textX   = host.avatarRect.right + host.avatarTextGap;
        showOptions   = host.canModify;
        float optionsW = showOptions ? host.optionsButtonSize : 0f;
        float textMaxW = Math.max(1f, host.getWidth() - host.cardPadding - textX
                - optionsW - (showOptions ? host.avatarTextGap : 0f));

        String rawName = host.authorName != null ? host.authorName : "Channel";
        if (cachedNameDisplay != null && rawName.equals(lastNameRaw) && textMaxW == lastNameMaxW) {
            nameToDraw = cachedNameDisplay;
        } else {
            nameToDraw = TextUtils.ellipsize(
                    rawName, host.authorNamePaint, textMaxW, TextUtils.TruncateAt.END).toString();
            lastNameRaw   = rawName;
            lastNameMaxW  = textMaxW;
            cachedNameDisplay = nameToDraw;
        }

        host.authorNamePaint.getFontMetrics(nameFm); // in-place, zero alloc
        host.timestampPaint.getFontMetrics(tsFm);   // in-place, zero alloc
        float nameH = nameFm.descent - nameFm.ascent;
        float tsH   = tsFm.descent  - tsFm.ascent;
        hasTs = host.timestampText != null && !host.timestampText.isEmpty();
        float rowGap  = hasTs ? host.nameTimestampGap : 0f;
        float blockH  = nameH + (hasTs ? rowGap + tsH : 0f);
        float blockTop = host.avatarRect.centerY() - blockH / 2f;

        nameX         = textX;
        nameBaselineY = blockTop - nameFm.ascent;
        if (hasTs) {
            tsBaselineY = blockTop + nameH + rowGap - tsFm.ascent;
        }

        if (showOptions) {
            optionsCx = host.getWidth() - host.cardPadding - host.optionsButtonSize / 2f;
            optionsCy = host.avatarRect.centerY();
            host.optionsButtonRect.set(
                    optionsCx - host.optionsButtonSize / 2f,
                    optionsCy - host.optionsButtonSize / 2f,
                    optionsCx + host.optionsButtonSize / 2f,
                    optionsCy + host.optionsButtonSize / 2f);
        } else {
            host.optionsButtonRect.setEmpty();
        }

        return headerTop + avatarSize;
    }

    void draw(Canvas canvas) {
        if (pinnedBaselineY >= 0) {
            canvas.drawText(ChannelPostCanvasView.PINNED_LABEL,
                    pinnedX, pinnedBaselineY, host.pinnedBadgePaint);
        }

        // Circular avatar
        if (host.authorAvatarBitmap != null) {
            Bitmap bmp = host.authorAvatarBitmap;
            float w = host.avatarRect.width(), h = host.avatarRect.height();
            if (host.avatarShaderCache == null || host.avatarShaderBitmap != bmp
                    || host.avatarShaderRectW != w || host.avatarShaderRectH != h) {
                float scale = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                float dx = host.avatarRect.left  - (bmp.getWidth()  * scale - w) / 2f;
                float dy = host.avatarRect.top   - (bmp.getHeight() * scale - h) / 2f;
                host.avatarShaderMatrix.reset();
                host.avatarShaderMatrix.setScale(scale, scale);
                host.avatarShaderMatrix.postTranslate(dx, dy);
                BitmapShader shader = new BitmapShader(bmp,
                        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shader.setLocalMatrix(host.avatarShaderMatrix);
                host.avatarShaderCache  = shader;
                host.avatarShaderBitmap = bmp;
                host.avatarShaderRectW  = w;
                host.avatarShaderRectH  = h;
            }
            host.avatarPaint.setShader(host.avatarShaderCache);
            canvas.drawOval(host.avatarRect, host.avatarPaint);
        } else {
            canvas.drawOval(host.avatarRect, host.avatarPlaceholderBgPaint);
            host.avatarGlyphPaint.getFontMetrics(glyphFm); // in-place, zero alloc
            Paint.FontMetrics gfm = glyphFm;
            canvas.drawText(ChannelPostCanvasView.PERSON_GLYPH,
                    host.avatarRect.centerX(),
                    host.avatarRect.centerY() - (gfm.ascent + gfm.descent) / 2f,
                    host.avatarGlyphPaint);
        }

        canvas.drawText(nameToDraw, nameX, nameBaselineY, host.authorNamePaint);
        if (hasTs) {
            canvas.drawText(host.timestampText, nameX, tsBaselineY, host.timestampPaint);
        }

        if (showOptions) {
            float dotR    = host.optionsButtonSize * 0.07f;
            float spacing = host.optionsButtonSize * 0.28f;
            canvas.drawCircle(optionsCx, optionsCy - spacing, dotR, host.optionsDotPaint);
            canvas.drawCircle(optionsCx, optionsCy,           dotR, host.optionsDotPaint);
            canvas.drawCircle(optionsCx, optionsCy + spacing, dotR, host.optionsDotPaint);
        }
    }
}
