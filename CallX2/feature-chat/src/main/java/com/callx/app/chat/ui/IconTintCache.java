package com.callx.app.chat.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

/**
 * ULTRA-OPT: process-wide static cache for tinted icon bitmaps, shared by
 * every chat screen (ChatIconBarView's attach/camera/mic/send icons, and
 * any other statically-tinted icon like btn_view_once).
 *
 * Before this, each screen either re-tinted its own Drawable on every
 * Activity creation (ContextCompat.getDrawable + mutate + setTint + draw —
 * real work: allocation, drawable state mutation, a Canvas draw pass) or,
 * for XML android:tint icons, leaned on AppCompat's internal tint cache
 * (present, but not guaranteed across OEM/AppCompat versions and not
 * inspectable/controllable by us).
 *
 * Here one explicit Bitmap per (resId, color, size, night-mode) is drawn
 * ONCE per process and reused by every chat screen for the rest of the
 * app's life — 2nd, 3rd, ... Nth chat open pays zero icon-tint cost.
 * Keyed on night-mode too so a day/night switch regenerates instead of
 * reusing a stale-colored bitmap (see ChatIconBarView's same fix).
 */
public final class IconTintCache {

    private IconTintCache() {}

    private static final SparseArray<Bitmap> CACHE = new SparseArray<>(16);

    /** Returns a cached tinted bitmap, drawing + caching it on first request. */
    public static Bitmap get(Context context, int resId, int colorResId, int sizePx) {
        boolean isNight = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        // Composite key: resId, colorResId and night-bit all folded together.
        // resId/colorResId are stable resource ints for the app's lifetime,
        // so a simple mix is enough to avoid collisions between icons.
        int key = (resId * 31 + colorResId) * 2 + (isNight ? 1 : 0);

        synchronized (CACHE) {
            Bitmap cached = CACHE.get(key);
            if (cached != null && !cached.isRecycled()) return cached;
        }

        Drawable d = ContextCompat.getDrawable(context, resId);
        if (d == null) return null;
        d = d.mutate();
        DrawableCompat.setTint(d, ContextCompat.getColor(context, colorResId));

        int size = Math.max(1, sizePx);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(bmp));

        synchronized (CACHE) {
            CACHE.put(key, bmp);
        }
        return bmp;
    }
}
