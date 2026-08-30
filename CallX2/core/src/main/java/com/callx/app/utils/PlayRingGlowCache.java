package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Build;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PlayRingGlowCache — same rasterize-once-per-size approach as
 * {@link StoryRingBitmapCache}, applied to the sound-detail play button's
 * colour-bloom glow (see fragment_sound_detail.xml's layout_play_ring_container).
 *
 * WHY THIS EXISTS: the glow used to be a plain XML {@code <shape>} radial
 * GradientDrawable (bg_play_ring_glow.xml). That's a *procedural* shader —
 * Android rebuilds the RadialGradient in onBoundsChange() every time a new
 * Drawable instance is inflated and bound to a View, and re-evaluates it
 * per pixel on every draw. Sound Detail isn't a RecyclerView row, but it IS
 * reopened constantly in normal use (tapping a sound from any reel, hopping
 * through related sounds — which replaces the whole fragment instance, see
 * SoundDetailCache's class doc) — so that shader was being rebuilt from
 * scratch on every single open, for a glow that's pixel-identical every
 * time (same fixed 76dp size, same fixed colors).
 *
 * FIX: pre-rasterize the glow exactly once into a small shared bitmap (GPU-
 * resident HARDWARE config on API 26+, same as the story ring), keyed by
 * size. Every subsequent open — of this sound or any other — just blits the
 * cached texture via PlayRingGlowDrawable, zero shader math.
 */
public final class PlayRingGlowCache {

    private PlayRingGlowCache() {}

    // Same pink→orange→yellow bloom bg_play_ring_glow.xml used, baked in
    // directly now that rasterization happens here instead of in XML.
    private static final int COLOR_START  = 0x80FF1493; // pink/magenta, center
    private static final int COLOR_MID    = 0x40FF8A00; // orange, mid
    private static final int COLOR_END    = 0x00FFD600; // yellow, fully transparent at edge

    private static final int MAX_CACHED_BITMAPS = 4; // glow only ever needs 1-2 distinct sizes in practice

    private static final Map<String, Bitmap> CACHE =
        new LinkedHashMap<String, Bitmap>(MAX_CACHED_BITMAPS, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                if (size() > MAX_CACHED_BITMAPS) {
                    Bitmap old = eldest.getValue();
                    if (old != null && !old.isRecycled()) old.recycle();
                    return true;
                }
                return false;
            }
        };

    private static String keyFor(int w, int h) { return w + "x" + h; }

    /**
     * Returns a shared, pre-rasterized bitmap of the glow for the given
     * local width/height. Built once per distinct size, reused by every
     * play-ring glow after that for the lifetime of the process.
     */
    public static synchronized Bitmap get(int width, int height) {
        if (width <= 0 || height <= 0) return null;

        String key = keyFor(width, height);
        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Bitmap software;
        try {
            software = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError oom) {
            return null; // glow bitmap is tiny, but never OOM-crash over it
        }

        Canvas canvas = new Canvas(software);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float cx = width / 2f;
        float cy = height / 2f;
        float radius = Math.min(cx, cy);
        RadialGradient rg = new RadialGradient(cx, cy, radius,
            new int[]{COLOR_START, COLOR_MID, COLOR_END},
            new float[]{0f, 0.55f, 1f},
            Shader.TileMode.CLAMP);
        paint.setShader(rg);
        canvas.drawCircle(cx, cy, radius, paint);

        Bitmap finalBitmap = software;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Bitmap hw = software.copy(Bitmap.Config.HARDWARE, false);
                if (hw != null) {
                    software.recycle();
                    finalBitmap = hw;
                }
            } catch (Throwable ignored) {
                // Keep software bitmap as-is.
            }
        }

        CACHE.put(key, finalBitmap);
        return finalBitmap;
    }
}
