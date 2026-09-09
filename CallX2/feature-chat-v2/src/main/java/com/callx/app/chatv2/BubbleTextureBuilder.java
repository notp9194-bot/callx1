package com.callx.app.chatv2;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

import android.opengl.GLES20;
import android.opengl.GLUtils;

/**
 * Rasterizes message text into a bitmap and uploads it as a GL texture.
 *
 * NOTE on scope: full glyph-atlas text shaping (FreeType + HarfBuzz,
 * like Telegram's own C++ renderer) is a large vendoring effort on its
 * own — not something to bolt on blind in one pass. This builder uses
 * Android's own text stack (StaticLayout, handles Hindi/emoji/complex
 * scripts correctly out of the box) to rasterize once per message, then
 * hands the result to the native renderer as an opaque texture — the
 * actual per-frame compositing, scrolling and draw batching (the part
 * that dominates chat-scroll cost) still happens fully in native GL.
 * Swapping this for a real glyph atlas later is a self-contained
 * follow-up that doesn't touch the native renderer's API.
 */
public class BubbleTextureBuilder {

    public static class BubbleTexture {
        public final int textureId;
        public final float width, height;
        BubbleTexture(int textureId, float width, float height) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
        }
    }

    private static final int MAX_BUBBLE_WIDTH_PX = 720;
    private static final int MIN_CONTENT_WIDTH_PX = 60;
    private static final int PADDING_PX = 28;
    private static final float TEXT_SIZE_SP = 34f;

    // Cache keyed by messageId — text bubbles never change content after
    // send, so we upload each one exactly once per process lifetime.
    private final Map<String, BubbleTexture> cache = new LinkedHashMap<>();

    /** Must be called on the GL thread (GLSurfaceView.queueEvent). */
    @NonNull
    public BubbleTexture buildOrGet(String messageId, String text, boolean isMine) {
        BubbleTexture cached = cache.get(messageId);
        if (cached != null) return cached;

        TextPaint paint = new TextPaint();
        paint.setAntiAlias(true);
        paint.setColor(Color.WHITE);
        paint.setTextSize(TEXT_SIZE_SP);

        int maxTextWidth = MAX_BUBBLE_WIDTH_PX - (PADDING_PX * 2);
        StaticLayout layout = StaticLayout.Builder
                .obtain(text == null ? "" : text, 0,
                        text == null ? 0 : text.length(), paint, maxTextWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .build();

        // Shrink-to-content width (WhatsApp/Telegram-style): StaticLayout
        // was built at maxTextWidth so wrapping is already correct, but a
        // short message shouldn't render a bubble as wide as a paragraph —
        // measure the longest actual line and size the bubble to that.
        float maxLineWidth = 0f;
        int lineCount = layout.getLineCount();
        for (int i = 0; i < lineCount; i++) {
            maxLineWidth = Math.max(maxLineWidth, layout.getLineWidth(i));
        }
        int textW = (int) Math.min(maxTextWidth, Math.max(MIN_CONTENT_WIDTH_PX, Math.ceil(maxLineWidth)));
        int textH = layout.getHeight();
        int bmpW = textW + PADDING_PX * 2;
        int bmpH = textH + PADDING_PX * 2;

        Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.translate(PADDING_PX, PADDING_PX);
        layout.draw(canvas);

        int[] texIds = new int[1];
        GLES20.glGenTextures(1, texIds, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();

        BubbleTexture tex = new BubbleTexture(texIds[0], bmpW, bmpH);
        cache.put(messageId, tex);
        return tex;
    }

    /** Must be called on the GL thread. */
    public void clear() {
        for (BubbleTexture t : cache.values()) {
            GLES20.glDeleteTextures(1, new int[]{t.textureId}, 0);
        }
        cache.clear();
    }
}
