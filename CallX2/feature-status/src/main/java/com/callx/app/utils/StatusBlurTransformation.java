package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * StatusBlurTransformation — WhatsApp-style blurred backdrop for the status
 * viewer.
 *
 * BUG FIX (image status getting cropped): iv_status used to be scaleType
 * "centerCrop", which fills the whole screen but chops off the top/bottom (or
 * sides) of any photo whose aspect ratio doesn't exactly match the device
 * screen — a portrait 4:3 photo, a wide landscape shot, a layout collage,
 * etc. all lost content. WhatsApp instead always shows the FULL photo
 * (fitCenter, never cropped) and fills the leftover space behind it with a
 * blurred, darkened, cropped copy of the same photo instead of plain black
 * bars. That's exactly what this transformation renders for the iv_status_bg
 * layer sitting behind the fitCenter iv_status foreground.
 *
 * Applies a two-pass box blur (horizontal then vertical) on a scaled-down
 * copy of the bitmap, then scales back up. Pure Java — no RenderScript,
 * works on all API levels.
 *
 * Usage:
 *   Glide.with(ctx)
 *       .load(url)
 *       .apply(new RequestOptions()
 *           .transform(new CenterCrop(), new StatusBlurTransformation(20)))
 *       .into(ivStatusBg);
 */
public class StatusBlurTransformation extends BitmapTransformation {

    private static final String ID =
            "com.callx.app.utils.StatusBlurTransformation.v3";
    private static final byte[] ID_BYTES =
            ID.getBytes(Charset.forName("UTF-8"));

    private final int radius;

    public StatusBlurTransformation(int radius) {
        this.radius = Math.max(1, Math.min(radius, 25));
    }

    // ── Glide BitmapTransformation ────────────────────────────────────────

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool,
                               @NonNull Bitmap toTransform,
                               int outWidth, int outHeight) {
        final int w = toTransform.getWidth(), h = toTransform.getHeight();
        // Blur a 1/4-size copy (cheap), then stretch it back up.
        final int sw = Math.max(1, w / 4), sh = Math.max(1, h / 4);

        Bitmap small = Bitmap.createScaledBitmap(toTransform, sw, sh, true);
        int[] pix = new int[sw * sh];
        small.getPixels(pix, 0, sw, 0, 0, sw, sh);
        if (small != toTransform && !small.isRecycled()) small.recycle(); // never recycle Glide's source

        // Running-sum box blur, O(1) per pixel regardless of radius (was O(radius)).
        blur(pix, new int[sw * sh], sw, sh, radius);

        Bitmap blurred = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        blurred.setPixels(pix, 0, sw, 0, 0, sw, sh);

        // Result comes from Glide's BitmapPool (recycled memory) instead of a fresh allocation.
        Bitmap result = pool.get(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawBitmap(blurred, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        blurred.recycle();

        // Darken so the sharp foreground photo pops over the blurred backdrop.
        // One pass of 0x8C (~55% black) = the earlier 40% + 25% scrims combined.
        canvas.drawColor(0x8C000000);
        return result;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
        messageDigest.update(java.nio.ByteBuffer.allocate(4).putInt(radius).array()); // radius is part of the cache key
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof StatusBlurTransformation)
                && ((StatusBlurTransformation) o).radius == radius;
    }

    @Override
    public int hashCode() {
        return ID.hashCode() * 31 + radius;
    }

    // ── Two-pass running-sum box blur (pure Java, no RenderScript) ─────────
    // Same window ([i-r, i+r] clamped to the edges, averaged by the real pixel count)
    // as before, so the look is unchanged — only the cost per pixel drops.

    /** Blurs `pix` in place (horizontal pass into `tmp`, vertical pass back into `pix`). */
    private static void blur(int[] pix, int[] tmp, int w, int h, int r) {
        for (int y = 0; y < h; y++) blurLine(pix, tmp, y * w, 1, w, r);   // rows
        for (int x = 0; x < w; x++) blurLine(tmp, pix, x, w, h, r);       // columns
    }

    private static void blurLine(int[] src, int[] dst, int start, int stride, int len, int r) {
        int rs = 0, gs = 0, bs = 0;
        final int hi = Math.min(r, len - 1);
        for (int i = 0; i <= hi; i++) {
            int c = src[start + i * stride];
            rs += (c >> 16) & 0xff; gs += (c >> 8) & 0xff; bs += c & 0xff;
        }
        for (int x = 0; x < len; x++) {
            final int cnt = Math.min(len - 1, x + r) - Math.max(0, x - r) + 1;
            final int idx = start + x * stride;
            dst[idx] = (src[idx] & 0xff000000) | ((rs / cnt) << 16) | ((gs / cnt) << 8) | (bs / cnt);
            final int add = x + r + 1;          // pixel entering the window for x+1
            if (add < len) {
                int c = src[start + add * stride];
                rs += (c >> 16) & 0xff; gs += (c >> 8) & 0xff; bs += c & 0xff;
            }
            final int rem = x - r;              // pixel leaving the window for x+1
            if (rem >= 0) {
                int c = src[start + rem * stride];
                rs -= (c >> 16) & 0xff; gs -= (c >> 8) & 0xff; bs -= c & 0xff;
            }
        }
    }
}
