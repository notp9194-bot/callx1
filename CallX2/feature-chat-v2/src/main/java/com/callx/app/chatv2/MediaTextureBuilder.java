package com.callx.app.chatv2;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import androidx.annotation.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uploads already-decoded media bitmaps (images / video thumbnails) as
 * GL textures. Decoding itself (Glide fetch + bitmap decode) happens on
 * the main thread in FastChatGLRenderer — this class only does the
 * texImage2D upload, which must run on the GL thread.
 *
 * Reuses the exact same textured-quad draw path as text bubbles
 * (ChatRenderer's fragment shader) — a media bubble is just a quad
 * whose texture is an opaque photo instead of rasterized text, so no
 * native-side changes were needed for this.
 */
public class MediaTextureBuilder {

    public static class MediaTexture {
        public final int textureId;
        public final float width, height;
        MediaTexture(int textureId, float width, float height) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
        }
    }

    private static final int MAX_MEDIA_WIDTH_PX = 720;
    private static final int MAX_MEDIA_HEIGHT_PX = 900;

    private final Map<String, MediaTexture> cache = new LinkedHashMap<>();

    public boolean has(String messageId) {
        return cache.containsKey(messageId);
    }

    public MediaTexture get(String messageId) {
        return cache.get(messageId);
    }

    /** Must be called on the GL thread. Bitmap ownership transfers here (recycled after upload). */
    @NonNull
    public MediaTexture upload(String messageId, @NonNull Bitmap bitmap) {
        MediaTexture cached = cache.get(messageId);
        if (cached != null) return cached;

        // Fit within max bubble bounds, preserving aspect ratio — same
        // convention WhatsApp/Telegram-style media bubbles use.
        float scale = Math.min(
                (float) MAX_MEDIA_WIDTH_PX / bitmap.getWidth(),
                (float) MAX_MEDIA_HEIGHT_PX / bitmap.getHeight());
        scale = Math.min(scale, 1f);
        float drawW = bitmap.getWidth() * scale;
        float drawH = bitmap.getHeight() * scale;

        int[] texIds = new int[1];
        GLES20.glGenTextures(1, texIds, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        bitmap.recycle();

        MediaTexture tex = new MediaTexture(texIds[0], drawW, drawH);
        cache.put(messageId, tex);
        return tex;
    }

    /** Must be called on the GL thread. */
    public void clear() {
        for (MediaTexture t : cache.values()) {
            GLES20.glDeleteTextures(1, new int[]{t.textureId}, 0);
        }
        cache.clear();
    }
}
