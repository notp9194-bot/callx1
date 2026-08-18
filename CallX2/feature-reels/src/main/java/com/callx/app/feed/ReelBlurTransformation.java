package com.callx.app.feed;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * ReelBlurTransformation — Instagram-style blurred background for photo reels.
 *
 * Applies a two-pass box blur (horizontal then vertical) on a scaled-down copy of
 * the bitmap, then scales back up. Pure Java — no RenderScript, works on all API
 * levels. Designed for the background "letterbox fill" layer in photo slideshow
 * reels: the foreground photo uses fitCenter (never cropped); this blurred layer
 * fills the full 9:16 frame behind it, exactly like Instagram's approach.
 *
 * Performance: the bitmap is scaled to 1/4 before blurring, so even with
 * radius=20 the inner loops operate on ~120×213 pixels — very fast on the main
 * thread (but Glide runs transforms on a background thread anyway).
 *
 * Usage:
 *   Glide.with(ctx)
 *       .load(url)
 *       .apply(new RequestOptions()
 *           .transform(new CenterCrop(), new ReelBlurTransformation(20)))
 *       .into(ivBgBlur);
 */
public class ReelBlurTransformation extends BitmapTransformation {

    private static final String ID =
            "com.callx.app.feed.ReelBlurTransformation.v2";
    private static final byte[] ID_BYTES =
            ID.getBytes(Charset.forName("UTF-8"));

    private final int radius;

    public ReelBlurTransformation(int radius) {
        this.radius = Math.max(1, Math.min(radius, 25));
    }

    // ── Glide BitmapTransformation ────────────────────────────────────────────

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool,
                               @NonNull Bitmap toTransform,
                               int outWidth, int outHeight) {
        // Scale down to 1/4 for speed.
        int scaledW = Math.max(1, toTransform.getWidth()  / 4);
        int scaledH = Math.max(1, toTransform.getHeight() / 4);

        Bitmap scaled  = Bitmap.createScaledBitmap(toTransform, scaledW, scaledH, true);
        Bitmap mutable = scaled.copy(Bitmap.Config.ARGB_8888, true);
        if (scaled != toTransform && !scaled.isRecycled()) scaled.recycle();

        // Two-pass box blur on the small bitmap.
        boxBlur(mutable, radius);

        // Scale back up.
        Bitmap result = Bitmap.createScaledBitmap(
                mutable, toTransform.getWidth(), toTransform.getHeight(), true);
        if (!mutable.isRecycled()) mutable.recycle();

        // Darken so the foreground photo pops over the blurred background.
        Canvas canvas = new Canvas(result);
        Paint darkPaint = new Paint();
        darkPaint.setColor(0x66000000);   // ~40 % translucent black
        canvas.drawRect(0, 0, result.getWidth(), result.getHeight(), darkPaint);

        return result;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof ReelBlurTransformation)
                && ((ReelBlurTransformation) o).radius == radius;
    }

    @Override
    public int hashCode() {
        return ID.hashCode() * 31 + radius;
    }

    // ── Two-pass box blur (pure Java, no RenderScript) ────────────────────────

    /**
     * Applies a separable box blur in-place: one horizontal pass, one vertical pass.
     * Equivalent to a Gaussian blur at large radii. Simple, correct, and fast on
     * small bitmaps (this is called after a 1/4 scale-down).
     */
    private static void boxBlur(@NonNull Bitmap bmp, int radius) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] pix = new int[w * h];
        bmp.getPixels(pix, 0, w, 0, 0, w, h);

        int[] tmp = new int[w * h];

        // ── Horizontal pass: pix[] → tmp[] ────────────────────────────────────
        for (int y = 0; y < h; y++) {
            int rowOff = y * w;
            for (int x = 0; x < w; x++) {
                long rsum = 0, gsum = 0, bsum = 0;
                int count = 0;
                int xStart = Math.max(0, x - radius);
                int xEnd   = Math.min(w - 1, x + radius);
                for (int nx = xStart; nx <= xEnd; nx++) {
                    int c = pix[rowOff + nx];
                    rsum += (c >> 16) & 0xff;
                    gsum += (c >>  8) & 0xff;
                    bsum +=  c        & 0xff;
                    count++;
                }
                tmp[rowOff + x] = (pix[rowOff + x] & 0xff000000)
                        | ((int)(rsum / count) << 16)
                        | ((int)(gsum / count) <<  8)
                        |  (int)(bsum / count);
            }
        }

        // ── Vertical pass: tmp[] → pix[] ──────────────────────────────────────
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                long rsum = 0, gsum = 0, bsum = 0;
                int count = 0;
                int yStart = Math.max(0, y - radius);
                int yEnd   = Math.min(h - 1, y + radius);
                for (int ny = yStart; ny <= yEnd; ny++) {
                    int c = tmp[ny * w + x];
                    rsum += (c >> 16) & 0xff;
                    gsum += (c >>  8) & 0xff;
                    bsum +=  c        & 0xff;
                    count++;
                }
                pix[y * w + x] = (tmp[y * w + x] & 0xff000000)
                        | ((int)(rsum / count) << 16)
                        | ((int)(gsum / count) <<  8)
                        |  (int)(bsum / count);
            }
        }

        bmp.setPixels(pix, 0, w, 0, 0, w, h);
    }
}
