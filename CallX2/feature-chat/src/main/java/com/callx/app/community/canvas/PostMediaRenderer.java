package com.callx.app.community.canvas;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * Draws the single image/video media card (bindMedia). Mirrors the legacy
 * container_media/iv_post_media/iv_play_icon FrameLayout: a fixed-height
 * rounded card, centerCrop-style bitmap fill, translucent scrim + play glyph
 * for video posts.
 *
 * PERF: clip Path, BitmapShader and the play-triangle Path are all cached on
 * the host and only rebuilt when their inputs (rect size / bitmap identity)
 * actually change, instead of being allocated fresh on every draw() call —
 * this runs once per visible post per frame during scroll, so per-frame
 * allocation here was a direct source of scroll jank/GC pauses.
 */
final class PostMediaRenderer {

    private final CommunityPostCanvasView host;

    PostMediaRenderer(CommunityPostCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
        float r = host.mediaCornerRadius;
        float w = host.mediaRect.width(), h = host.mediaRect.height();

        if (host.mediaClipW != w || host.mediaClipH != h) {
            host.mediaClipPath.reset();
            host.mediaClipPath.addRoundRect(host.mediaRect, r, r, Path.Direction.CW);
            host.mediaClipW = w;
            host.mediaClipH = h;
        }

        canvas.save();
        canvas.clipPath(host.mediaClipPath);

        if (host.mediaBitmap != null) {
            Bitmap bmp = host.mediaBitmap;
            if (host.mediaShaderCache == null || host.mediaShaderBitmap != bmp
                    || host.mediaShaderRectW != w || host.mediaShaderRectH != h) {
                float scale = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                float dx = host.mediaRect.left - (bmp.getWidth() * scale - w) / 2f;
                float dy = host.mediaRect.top - (bmp.getHeight() * scale - h) / 2f;
                host.mediaShaderMatrix.reset();
                host.mediaShaderMatrix.setScale(scale, scale);
                host.mediaShaderMatrix.postTranslate(dx, dy);
                BitmapShader shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shader.setLocalMatrix(host.mediaShaderMatrix);
                host.mediaShaderCache = shader;
                host.mediaShaderBitmap = bmp;
                host.mediaShaderRectW = w;
                host.mediaShaderRectH = h;
            }
            host.mediaBitmapPaint.setShader(host.mediaShaderCache);
            canvas.drawRect(host.mediaRect, host.mediaBitmapPaint);
        } else {
            host.mediaBitmapPaint.setShader(null);
            canvas.drawRect(host.mediaRect, host.mediaPlaceholderPaint);
        }

        if (host.mediaIsVideo) {
            canvas.drawRect(host.mediaRect, host.mediaVideoScrimPaint);
            float cx = host.mediaRect.centerX();
            float cy = host.mediaRect.centerY();
            float playR = host.playIconRadius;
            canvas.drawCircle(cx, cy, playR, host.playCirclePaint);

            if (host.playTriangleForRadius != playR) {
                float triSize = playR * 0.7f;
                host.playTrianglePath.reset();
                host.playTrianglePath.moveTo(-triSize * 0.4f, -triSize);
                host.playTrianglePath.lineTo(-triSize * 0.4f, triSize);
                host.playTrianglePath.lineTo(triSize * 0.8f, 0);
                host.playTrianglePath.close();
                host.playTriangleForRadius = playR;
            }
            canvas.save();
            canvas.translate(cx, cy);
            canvas.drawPath(host.playTrianglePath, host.playTrianglePaint);
            canvas.restore();
        }

        canvas.restore();
    }
}
