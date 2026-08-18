package com.callx.app.community.canvas;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws a multi-image gallery-style post — mirrors the chat module's
 * MediaGroupRenderer grid rules (1/2/3/4/5+ cell layouts) but for a
 * community post's media list instead of a chat bubble's grid.
 *
 * Cell rects are computed in layout() (called from the host's onMeasure so
 * touch hit-testing and draw() share the exact same geometry) and cached
 * until the item count or the available width changes.
 *
 * PERF: per-cell clip Path, BitmapShader and play-triangle Path (up to 4 of
 * each, previously allocated fresh every draw() call for every visible grid
 * post) are now cached per cell slot on the host and only rebuilt when that
 * cell's rect size or bitmap identity changes.
 */
final class PostMediaGroupRenderer {

    private final CommunityPostCanvasView host;
    final List<RectF> cellRects = new ArrayList<>();
    private int lastCount = -1;
    private float lastWidth = -1f;

    PostMediaGroupRenderer(CommunityPostCanvasView host) {
        this.host = host;
    }

    /** Lays out cellRects for `count` items within [left, right] starting at `top`. Returns bottom y. */
    float layout(float left, float top, float right, int count) {
        float width = right - left;
        if (count == lastCount && width == lastWidth && !cellRects.isEmpty()) {
            float bottom = 0f;
            for (RectF r : cellRects) bottom = Math.max(bottom, r.bottom);
            return bottom;
        }
        cellRects.clear();
        float gap = host.mediaGroupGap;
        float height = host.mediaGroupHeight;

        if (count <= 1) {
            cellRects.add(new RectF(left, top, right, top + height));
        } else if (count == 2) {
            float w = (width - gap) / 2f;
            cellRects.add(new RectF(left, top, left + w, top + height));
            cellRects.add(new RectF(left + w + gap, top, right, top + height));
        } else if (count == 3) {
            float leftW = (width - gap) * 0.6f;
            float rightW = width - gap - leftW;
            float halfH = (height - gap) / 2f;
            cellRects.add(new RectF(left, top, left + leftW, top + height));
            cellRects.add(new RectF(left + leftW + gap, top, right, top + halfH));
            cellRects.add(new RectF(left + leftW + gap, top + halfH + gap, right, top + height));
        } else {
            // 4 or more: 2x2 grid, 4th cell gets "+N" overflow badge if count > 4
            float w = (width - gap) / 2f;
            float h = (height - gap) / 2f;
            cellRects.add(new RectF(left, top, left + w, top + h));
            cellRects.add(new RectF(left + w + gap, top, right, top + h));
            cellRects.add(new RectF(left, top + h + gap, left + w, top + height));
            cellRects.add(new RectF(left + w + gap, top + h + gap, right, top + height));
        }
        lastCount = count;
        lastWidth = width;
        float bottom = 0f;
        for (RectF r : cellRects) bottom = Math.max(bottom, r.bottom);
        return bottom;
    }

    void draw(Canvas canvas) {
        List<Bitmap> bitmaps = host.mediaGroupBitmaps;
        List<Boolean> isVideo = host.mediaGroupIsVideo;
        int overflow = host.mediaGroupOverflowCount;
        float r = host.mediaCornerRadius;

        for (int i = 0; i < cellRects.size(); i++) {
            RectF cell = cellRects.get(i);
            float w = cell.width(), h = cell.height();

            Path clip = host.mediaGroupClipPath[i];
            if (host.mediaGroupClipW[i] != w || host.mediaGroupClipH[i] != h) {
                clip.reset();
                clip.addRoundRect(cell, r, r, Path.Direction.CW);
                host.mediaGroupClipW[i] = w;
                host.mediaGroupClipH[i] = h;
            }

            canvas.save();
            canvas.clipPath(clip);

            Bitmap bmp = bitmaps != null && i < bitmaps.size() ? bitmaps.get(i) : null;
            if (bmp != null) {
                if (host.mediaGroupShaderCache[i] == null || host.mediaGroupShaderBitmap[i] != bmp
                        || host.mediaGroupShaderW[i] != w || host.mediaGroupShaderH[i] != h) {
                    float scale = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                    float dx = cell.left - (bmp.getWidth() * scale - w) / 2f;
                    float dy = cell.top - (bmp.getHeight() * scale - h) / 2f;
                    host.mediaShaderMatrix.reset();
                    host.mediaShaderMatrix.setScale(scale, scale);
                    host.mediaShaderMatrix.postTranslate(dx, dy);
                    BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    shader.setLocalMatrix(host.mediaShaderMatrix);
                    host.mediaGroupShaderCache[i] = shader;
                    host.mediaGroupShaderBitmap[i] = bmp;
                    host.mediaGroupShaderW[i] = w;
                    host.mediaGroupShaderH[i] = h;
                }
                host.mediaBitmapPaint.setShader(host.mediaGroupShaderCache[i]);
                canvas.drawRect(cell, host.mediaBitmapPaint);
            } else {
                host.mediaBitmapPaint.setShader(null);
                canvas.drawRect(cell, host.mediaPlaceholderPaint);
            }

            boolean video = isVideo != null && i < isVideo.size() && Boolean.TRUE.equals(isVideo.get(i));
            if (video) {
                canvas.drawRect(cell, host.mediaVideoScrimPaint);
                float playR = Math.min(w, h) * 0.18f;
                canvas.drawCircle(cell.centerX(), cell.centerY(), playR, host.playCirclePaint);

                Path tri = host.mediaGroupTrianglePath[i];
                if (host.mediaGroupTriangleForRadius[i] != playR) {
                    float triSize = playR * 0.7f;
                    tri.reset();
                    tri.moveTo(-triSize * 0.4f, -triSize);
                    tri.lineTo(-triSize * 0.4f, triSize);
                    tri.lineTo(triSize * 0.8f, 0);
                    tri.close();
                    host.mediaGroupTriangleForRadius[i] = playR;
                }
                canvas.save();
                canvas.translate(cell.centerX(), cell.centerY());
                canvas.drawPath(tri, host.playTrianglePaint);
                canvas.restore();
            }

            // "+N" overflow badge on the last cell
            boolean isLastCell = i == cellRects.size() - 1;
            if (isLastCell && overflow > 0) {
                canvas.drawRect(cell, host.mediaOverflowScrimPaint);
                String label = "+" + overflow;
                android.graphics.Paint.FontMetrics fm = host.mediaOverflowTextPaint.getFontMetrics();
                canvas.drawText(label, cell.centerX(), cell.centerY() - (fm.ascent + fm.descent) / 2f,
                        host.mediaOverflowTextPaint);
            }

            canvas.restore();
        }
    }

    /** Returns the tapped cell index, or -1. Index == last cell with overflow>0 means "open gallery". */
    int hitTestCell(float x, float y) {
        for (int i = 0; i < cellRects.size(); i++) {
            if (cellRects.get(i).contains(x, y)) return i;
        }
        return -1;
    }
}
