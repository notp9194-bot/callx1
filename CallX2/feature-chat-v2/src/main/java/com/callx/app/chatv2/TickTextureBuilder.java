package com.callx.app.chatv2;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Read-receipt tick icon, built once per status value and reused across
 * every "mine" bubble in that state (same idea as BubbleTextureBuilder,
 * but keyed by status string instead of messageId — there are only ever
 * 3 distinct icons needed, not one per message).
 *
 * WhatsApp/Telegram convention: single tick = sent, double tick =
 * delivered, double tick (accent color) = read. Drawn as an actual Path
 * (not the ✓ text glyph) so it renders identically regardless of the
 * device's default font / emoji support.
 */
public class TickTextureBuilder {

    public static class TickTexture {
        public final int textureId;
        public final float width, height;
        TickTexture(int textureId, float width, float height) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
        }
    }

    private static final int ICON_H = 20;
    private static final int SINGLE_W = 22;
    private static final int DOUBLE_W = 34;
    // Bubble background is already the outgoing blue — ticks need their
    // own baked-in color to read against it (see ChatRenderer's tick
    // draw call: transparent tick pixels fall back to the same bubble
    // color, so only these colored strokes are visible).
    private static final int COLOR_SENT_DELIVERED = Color.rgb(214, 224, 236); // soft white-gray
    private static final int COLOR_READ = Color.rgb(120, 220, 255);           // bright accent

    private final Map<String, TickTexture> cache = new HashMap<>();

    /** Must be called on the GL thread. Returns null for statuses that
     *  shouldn't show a tick at all (still pending, failed, or unknown). */
    @Nullable
    public TickTexture buildOrGet(@Nullable String status) {
        if (status == null) return null;
        boolean single = "sent".equals(status);
        boolean doubleGray = "delivered".equals(status);
        boolean doubleAccent = "read".equals(status);
        if (!single && !doubleGray && !doubleAccent) return null;

        TickTexture cached = cache.get(status);
        if (cached != null) return cached;

        int w = single ? SINGLE_W : DOUBLE_W;
        int color = doubleAccent ? COLOR_READ : COLOR_SENT_DELIVERED;

        Bitmap bmp = Bitmap.createBitmap(w, ICON_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);

        drawCheck(canvas, paint, 0f);
        if (!single) drawCheck(canvas, paint, 9f);

        int[] texIds = new int[1];
        GLES20.glGenTextures(1, texIds, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();

        TickTexture tex = new TickTexture(texIds[0], w, ICON_H);
        cache.put(status, tex);
        return tex;
    }

    /** Single check mark, short-stroke-then-long-stroke, offset by dx. */
    private void drawCheck(Canvas canvas, Paint paint, float dx) {
        Path path = new Path();
        path.moveTo(dx + 2f, ICON_H * 0.55f);
        path.lineTo(dx + 7f, ICON_H * 0.8f);
        path.lineTo(dx + 15f, ICON_H * 0.25f);
        canvas.drawPath(path, paint);
    }

    /** Must be called on the GL thread. */
    public void clear() {
        for (TickTexture t : cache.values()) {
            GLES20.glDeleteTextures(1, new int[]{t.textureId}, 0);
        }
        cache.clear();
    }
}
