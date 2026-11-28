package com.callx.app.chatlist.canvas;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import java.util.HashMap;

/**
 * v948 — process-wide pre-rasterized icon bitmaps for the Chats row canvas views.
 *
 * Before: every ViewHolder created its own VectorDrawable copies (mutate() = native tree copy + its own raster
 * cache) for the avatar placeholders and the call icons, and its own verified-badge Bitmap. Now each icon is
 * rasterized ONCE per (res, size, tint) and shared by every row: one GPU texture, zero per-VH vector/bitmap
 * allocation, and a plain drawBitmap() on the draw path.
 *
 * Bitmaps are immutable after creation (never recycled, never drawn into again) so sharing across views is safe.
 */
final class ChatRowIconCache {

    private static final HashMap<String, Bitmap> CACHE = new HashMap<>();

    private ChatRowIconCache() {}

    /**
     * @param tint 0 = draw the drawable with its own colours, otherwise a solid tint (ARGB).
     * @return square bitmap of {@code sizePx}, or null if the resource could not be loaded.
     */
    @Nullable
    static Bitmap get(Context ctx, int res, int sizePx, int tint) {
        if (sizePx <= 0) return null;
        final String key = res + ":" + sizePx + ":" + tint;
        synchronized (CACHE) {
            Bitmap b = CACHE.get(key);
            if (b != null) return b;
            Drawable d = ContextCompat.getDrawable(ctx, res);
            if (d == null) return null;
            d = d.mutate();
            if (tint != 0) {
                d = DrawableCompat.wrap(d);
                DrawableCompat.setTint(d, tint);
            }
            b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, sizePx, sizePx);
            d.draw(new Canvas(b));
            CACHE.put(key, b);
            return b;
        }
    }
}
