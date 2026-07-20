package com.callx.app.community.canvas;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Draws the bottom action row: like (heart) + count, comment (reply glyph)
 * + count, share glyph. Mirrors the legacy btn_like/tv_like_count/
 * btn_comment/tv_comment_count row, plus a share action the XML version
 * didn't have (CommunityPostAdapter.Listener#onShare is a new default
 * no-op method so existing implementers keep compiling).
 */
final class EngagementBarRenderer {

    static final int REGION_NONE     = 0;
    static final int REGION_LIKE     = 1;
    static final int REGION_COMMENT  = 2;
    static final int REGION_SHARE    = 3;
    static final int REGION_BOOKMARK = 4;

    private final CommunityPostCanvasView host;

    EngagementBarRenderer(CommunityPostCanvasView host) {
        this.host = host;
    }

    /** Lays out icon/count rects within [left,right] starting at top. Returns bottom y. */
    float layout(float left, float top, float right) {
        float iconSize = host.engagementIconSize;
        float y = top;
        float x = left;

        host.likeIconRect.set(x, y, x + iconSize, y + iconSize);
        x += iconSize + host.engagementIconGap;
        float likeCountW = host.engagementCountPaint.measureText(host.likeCountText);
        host.likeCountTextX = x;
        x += likeCountW + host.engagementGroupGap;

        host.commentIconRect.set(x, y, x + iconSize, y + iconSize);
        x += iconSize + host.engagementIconGap;
        float commentCountW = host.engagementCountPaint.measureText(host.commentCountText);
        host.commentCountTextX = x;
        x += commentCountW + host.engagementGroupGap;

        // Bookmark at the far right, share just to its left with a small gap.
        float gap = iconSize * 0.6f;
        float bookmarkX = right - iconSize;
        host.bookmarkIconRect.set(bookmarkX, y, bookmarkX + iconSize, y + iconSize);
        float shareX = bookmarkX - gap - iconSize;
        host.shareIconRect.set(shareX, y, shareX + iconSize, y + iconSize);

        return y + iconSize;
    }

    void draw(Canvas canvas) {
        drawLikeGlyph(canvas, host.likeIconRect, host.myReacted);
        if (!host.likeCountText.isEmpty()) {
            Paint.FontMetrics fm = host.engagementCountPaint.getFontMetrics();
            canvas.drawText(host.likeCountText, host.likeCountTextX,
                    host.likeIconRect.centerY() - (fm.ascent + fm.descent) / 2f, host.engagementCountPaint);
        }

        drawCommentGlyph(canvas, host.commentIconRect);
        if (!host.commentCountText.isEmpty()) {
            Paint.FontMetrics fm = host.engagementCountPaint.getFontMetrics();
            canvas.drawText(host.commentCountText, host.commentCountTextX,
                    host.commentIconRect.centerY() - (fm.ascent + fm.descent) / 2f, host.engagementCountPaint);
        }

        drawShareGlyph(canvas, host.shareIconRect);
        drawBookmarkGlyph(canvas, host.bookmarkIconRect, host.isBookmarked);
    }

    private final Path heartPath = new Path();
    private float heartForSize = -1f;
    private final Path arrowPath = new Path();
    private float arrowForSize = -1f;

    private void drawLikeGlyph(Canvas canvas, RectF rect, boolean filled) {
        Paint paint = filled ? host.likeFilledPaint : host.likeOutlinePaint;
        float cx = rect.centerX();
        float cy = rect.centerY();
        float s = rect.width() * 0.42f;
        // PERF: this used to build a new Path on every draw() call for every
        // visible post row — the heart shape only depends on icon size,
        // which is fixed per row, so cache it and just translate to (cx,cy).
        if (heartForSize != s) {
            heartPath.reset();
            heartPath.moveTo(0, s * 0.75f);
            heartPath.cubicTo(-s * 1.4f, -s * 0.3f, -s * 0.5f, -s * 1.3f, 0, -s * 0.4f);
            heartPath.cubicTo(s * 0.5f, -s * 1.3f, s * 1.4f, -s * 0.3f, 0, s * 0.75f);
            heartPath.close();
            heartForSize = s;
        }
        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawPath(heartPath, paint);
        canvas.restore();
    }

    private void drawCommentGlyph(Canvas canvas, RectF rect) {
        float r = rect.width() * 0.4f;
        canvas.drawCircle(rect.centerX(), rect.centerY(), r, host.commentGlyphPaint);
    }

    private void drawShareGlyph(Canvas canvas, RectF rect) {
        float cx = rect.centerX();
        float cy = rect.centerY();
        float s = rect.width() * 0.35f;
        if (arrowForSize != s) {
            arrowPath.reset();
            arrowPath.moveTo(-s, s * 0.6f);
            arrowPath.lineTo(s, 0);
            arrowPath.lineTo(-s, -s * 0.6f);
            arrowForSize = s;
        }
        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawPath(arrowPath, host.shareGlyphPaint);
        canvas.restore();
    }

    private final Path bookmarkPath = new Path();
    private float bookmarkForSize = -1f;

    private void drawBookmarkGlyph(Canvas canvas, RectF rect, boolean filled) {
        float cx = rect.centerX();
        float top = rect.top + rect.height() * 0.1f;
        float bottom = rect.bottom - rect.height() * 0.05f;
        float halfW = rect.width() * 0.28f;
        float notchDepth = rect.height() * 0.22f;
        float s = rect.width();  // cache key

        if (bookmarkForSize != s) {
            bookmarkPath.reset();
            // Rectangle with a V-notch cut at the bottom
            bookmarkPath.moveTo(-halfW, top - rect.top - rect.height() * 0.5f);
            bookmarkPath.lineTo( halfW, top - rect.top - rect.height() * 0.5f);
            bookmarkPath.lineTo( halfW, bottom - rect.centerY() + notchDepth * 0.5f);
            bookmarkPath.lineTo(0,      bottom - rect.centerY() - notchDepth * 0.5f);
            bookmarkPath.lineTo(-halfW, bottom - rect.centerY() + notchDepth * 0.5f);
            bookmarkPath.close();
            bookmarkForSize = s;
        }

        Paint p = filled ? host.likeFilledPaint : host.bookmarkGlyphPaint;
        canvas.save();
        canvas.translate(rect.centerX(), rect.centerY());
        canvas.drawPath(bookmarkPath, p);
        canvas.restore();
    }

    /** Returns which region (REGION_*) contains (x,y). */
    int hitTest(float x, float y) {
        if (host.likeIconRect.contains(x, y))     return REGION_LIKE;
        if (host.commentIconRect.contains(x, y))  return REGION_COMMENT;
        if (host.shareIconRect.contains(x, y))    return REGION_SHARE;
        if (host.bookmarkIconRect.contains(x, y)) return REGION_BOOKMARK;
        return REGION_NONE;
    }
}
