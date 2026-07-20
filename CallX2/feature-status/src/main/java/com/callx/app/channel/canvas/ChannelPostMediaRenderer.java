package com.callx.app.channel.canvas;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * Draws the single image/video media card for channel posts.
 *
 * Mirrors PostMediaRenderer from the community canvas module:
 *   • Rounded-corner clip using a cached Path (rebuilt only on rect-size change)
 *   • CenterCrop-equivalent fill via BitmapShader + cached Matrix
 *   • Translucent scrim + play circle + triangle for video posts
 *   • All objects reused across frames — zero allocation per onDraw()
 */
final class ChannelPostMediaRenderer {

    private final ChannelPostCanvasView host;

    ChannelPostMediaRenderer(ChannelPostCanvasView host) {
        this.host = host;
    }

    void draw(Canvas canvas) {
        float r = host.mediaCornerRadius;
        float w = host.mediaRect.width();
        float h = host.mediaRect.height();

        // Rebuild clip path only when rect dimensions change.
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
            // Rebuild BitmapShader only when bitmap reference or rect changes.
            if (host.mediaShaderCache == null
                    || host.mediaShaderBitmap != bmp
                    || host.mediaShaderRectW != w
                    || host.mediaShaderRectH != h) {
                float scale = Math.max(w / bmp.getWidth(), h / bmp.getHeight());
                float dx = host.mediaRect.left - (bmp.getWidth()  * scale - w) / 2f;
                float dy = host.mediaRect.top  - (bmp.getHeight() * scale - h) / 2f;
                host.mediaShaderMatrix.reset();
                host.mediaShaderMatrix.setScale(scale, scale);
                host.mediaShaderMatrix.postTranslate(dx, dy);
                BitmapShader shader = new BitmapShader(
                        bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                shader.setLocalMatrix(host.mediaShaderMatrix);
                host.mediaShaderCache  = shader;
                host.mediaShaderBitmap = bmp;
                host.mediaShaderRectW  = w;
                host.mediaShaderRectH  = h;
            }
            host.mediaBitmapPaint.setShader(host.mediaShaderCache);
            canvas.drawRect(host.mediaRect, host.mediaBitmapPaint);
        } else {
            host.mediaBitmapPaint.setShader(null);
            canvas.drawRect(host.mediaRect, host.mediaPlaceholderPaint);
        }

        if (host.mediaIsVideo) {
            // Dark scrim
            canvas.drawRect(host.mediaRect, host.mediaVideoScrimPaint);
            // Play circle
            float cx    = host.mediaRect.centerX();
            float cy    = host.mediaRect.centerY();
            float playR = host.playIconRadius;
            canvas.drawCircle(cx, cy, playR, host.playCirclePaint);
            // Play triangle — rebuild Path only when radius changes.
            if (host.playTriangleForRadius != playR) {
                float tri = playR * 0.7f;
                host.playTrianglePath.reset();
                host.playTrianglePath.moveTo(-tri * 0.4f, -tri);
                host.playTrianglePath.lineTo(-tri * 0.4f,  tri);
                host.playTrianglePath.lineTo( tri * 0.8f,  0f);
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
