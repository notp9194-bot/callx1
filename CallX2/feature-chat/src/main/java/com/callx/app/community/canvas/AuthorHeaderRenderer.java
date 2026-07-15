package com.callx.app.community.canvas;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextUtils;

/**
 * Draws the post header: pinned label, announcement badge, circular author
 * avatar (photo or placeholder), author name, timestamp, and the 3-dot
 * options button (admin/author only).
 *
 * measure() computes every rect/baseline this section needs (including
 * host.avatarRect / host.optionsButtonRect, which touch-hit-testing in the
 * host relies on) and caches them; draw() only paints using that cached
 * state — it never recomputes geometry, so it's safe to call during
 * onMeasure (via measure()) without a real backing Canvas.
 */
final class AuthorHeaderRenderer {

    private final CommunityPostCanvasView host;

    AuthorHeaderRenderer(CommunityPostCanvasView host) {
        this.host = host;
    }

    // Ellipsize cache — name only changes on rebind, so don't re-run
    // TextUtils.ellipsize() (a full remeasure) on every measure() pass.
    private String lastNameRaw;
    private float lastNameMaxW = -1f;
    private String cachedNameDisplay;

    // Cached geometry/text from the last measure() call, consumed by draw().
    private float pinnedBaselineY = -1f, pinnedX;
    private float announcementBaselineY = -1f, announcementX;
    private String nameToDraw = "";
    private float nameX, nameBaselineY;
    private boolean hasTs;
    private float tsBaselineY;
    private boolean showOptions;
    private float optionsCx, optionsCy;

    /** Computes all header geometry starting at `top`. Returns the bottom y of the header block. */
    float measure(float top) {
        float y = top;
        pinnedBaselineY = -1f;
        announcementBaselineY = -1f;

        if (host.hasPinned) {
            Paint.FontMetrics fm = host.pinnedBadgePaint.getFontMetrics();
            pinnedX = host.cardPadding;
            pinnedBaselineY = y - fm.ascent;
            y += (fm.descent - fm.ascent) + host.badgeMarginBottom;
        }

        if (host.hasAnnouncement) {
            Paint.FontMetrics fm = host.announcementBadgePaint.getFontMetrics();
            announcementX = host.cardPadding;
            announcementBaselineY = y - fm.ascent;
            y += (fm.descent - fm.ascent) + host.badgeMarginBottom;
        }

        float headerTop = y;
        float avatarSize = host.avatarSize;
        host.avatarRect.set(host.cardPadding, headerTop, host.cardPadding + avatarSize, headerTop + avatarSize);

        float textX = host.avatarRect.right + host.avatarTextGap;
        showOptions = host.canModify;
        float optionsW = showOptions ? host.optionsButtonSize : 0f;
        float textMaxW = Math.max(1f, host.cardRight() - host.cardPadding - textX - optionsW
                - (showOptions ? host.avatarTextGap : 0f));

        String rawName = host.authorName != null ? host.authorName : "Member";
        if (cachedNameDisplay != null && rawName.equals(lastNameRaw) && textMaxW == lastNameMaxW) {
            nameToDraw = cachedNameDisplay;
        } else {
            nameToDraw = TextUtils.ellipsize(rawName, host.authorNamePaint, textMaxW, TextUtils.TruncateAt.END).toString();
            lastNameRaw = rawName;
            lastNameMaxW = textMaxW;
            cachedNameDisplay = nameToDraw;
        }

        Paint.FontMetrics nfm = host.authorNamePaint.getFontMetrics();
        Paint.FontMetrics tfm = host.timestampPaint.getFontMetrics();
        float nameH = nfm.descent - nfm.ascent;
        float tsH = tfm.descent - tfm.ascent;
        hasTs = host.timestampText != null && !host.timestampText.isEmpty();
        float rowGap = hasTs ? host.nameTimestampGap : 0f;
        float blockH = nameH + (hasTs ? rowGap + tsH : 0f);
        float blockTop = host.avatarRect.centerY() - blockH / 2f;

        nameX = textX;
        nameBaselineY = blockTop - nfm.ascent;
        if (hasTs) {
            tsBaselineY = blockTop + nameH + rowGap - tfm.ascent;
        }

        if (showOptions) {
            optionsCx = host.cardRight() - host.cardPadding - host.optionsButtonSize / 2f;
            optionsCy = host.avatarRect.centerY();
            host.optionsButtonRect.set(optionsCx - host.optionsButtonSize / 2f, optionsCy - host.optionsButtonSize / 2f,
                    optionsCx + host.optionsButtonSize / 2f, optionsCy + host.optionsButtonSize / 2f);
        } else {
            host.optionsButtonRect.setEmpty();
        }

        return headerTop + avatarSize;
    }

    /** Paints using the geometry computed by the last measure() call. */
    void draw(Canvas canvas) {
        if (pinnedBaselineY >= 0) {
            canvas.drawText(CommunityPostCanvasView.PINNED_LABEL_TEXT, pinnedX, pinnedBaselineY, host.pinnedBadgePaint);
        }
        if (announcementBaselineY >= 0) {
            canvas.drawText(CommunityPostCanvasView.ANNOUNCEMENT_LABEL_TEXT, announcementX, announcementBaselineY, host.announcementBadgePaint);
        }

        if (host.authorAvatarBitmap != null) {
            Bitmap bmp = host.authorAvatarBitmap;
            float w = host.avatarRect.width(), h = host.avatarRect.height();
            // PERF: BitmapShader construction (and the matrix math around it)
            // used to run on every onDraw() call for every visible post —
            // with the avatar bitmap and target rect unchanged between
            // frames during a scroll, that's pure waste. Rebuild only when
            // the bitmap reference or rect size actually changes.
            if (host.avatarShaderCache == null || host.avatarShaderBitmap != bmp
                    || host.avatarShaderRectW != w || host.avatarShaderRectH != h) {
                float scale = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                float dx = host.avatarRect.left - (bmp.getWidth() * scale - w) / 2f;
                float dy = host.avatarRect.top - (bmp.getHeight() * scale - h) / 2f;
                host.avatarShaderMatrix.reset();
                host.avatarShaderMatrix.setScale(scale, scale);
                host.avatarShaderMatrix.postTranslate(dx, dy);
                BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shader.setLocalMatrix(host.avatarShaderMatrix);
                host.avatarShaderCache = shader;
                host.avatarShaderBitmap = bmp;
                host.avatarShaderRectW = w;
                host.avatarShaderRectH = h;
            }
            host.avatarPaint.setShader(host.avatarShaderCache);
            canvas.drawOval(host.avatarRect, host.avatarPaint);
        } else {
            canvas.drawOval(host.avatarRect, host.avatarPlaceholderBgPaint);
            Paint.FontMetrics gfm = host.avatarGlyphPaint.getFontMetrics();
            canvas.drawText(CommunityPostCanvasView.PERSON_GLYPH,
                    host.avatarRect.centerX(), host.avatarRect.centerY() - (gfm.ascent + gfm.descent) / 2f,
                    host.avatarGlyphPaint);
        }

        canvas.drawText(nameToDraw, nameX, nameBaselineY, host.authorNamePaint);
        if (hasTs) {
            canvas.drawText(host.timestampText, nameX, tsBaselineY, host.timestampPaint);
        }

        if (showOptions) {
            float dotR = host.optionsButtonSize * 0.07f;
            float spacing = host.optionsButtonSize * 0.28f;
            canvas.drawCircle(optionsCx, optionsCy - spacing, dotR, host.optionsDotPaint);
            canvas.drawCircle(optionsCx, optionsCy, dotR, host.optionsDotPaint);
            canvas.drawCircle(optionsCx, optionsCy + spacing, dotR, host.optionsDotPaint);
        }
    }
}
