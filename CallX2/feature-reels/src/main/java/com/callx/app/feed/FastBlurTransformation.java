package com.callx.app.feed;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * FastBlurTransformation ── pure-Java stack blur for Glide.
 *
 * Used to generate the low-res blurred placeholder for photo slides
 * (Instagram-style fade-in) without pulling in RenderScript (deprecated) or
 * a third-party blur library. Operates on a heavily downscaled bitmap so the
 * blur pass stays fast even on the main image path.
 */
public class FastBlurTransformation extends BitmapTransformation {

    private static final String ID = "com.callx.app.feed.FastBlurTransformation";
    private static final byte[] ID_BYTES = ID.getBytes(Charset.forName("UTF-8"));

    private final int radius;
    private final int sampling;

    public FastBlurTransformation() {
        this(20, 4);
    }

    public FastBlurTransformation(int radius, int sampling) {
        this.radius = radius;
        this.sampling = Math.max(1, sampling);
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform, int outWidth, int outHeight) {
        int scaledW = Math.max(1, toTransform.getWidth() / sampling);
        int scaledH = Math.max(1, toTransform.getHeight() / sampling);
        Bitmap scaled = Bitmap.createScaledBitmap(toTransform, scaledW, scaledH, true);
        Bitmap blurred = stackBlur(scaled, radius);
        if (blurred != scaled) scaled.recycle();
        return blurred;
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
        byte[] params = (radius + "_" + sampling).getBytes(Charset.forName("UTF-8"));
        messageDigest.update(params);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FastBlurTransformation
                && ((FastBlurTransformation) o).radius == radius
                && ((FastBlurTransformation) o).sampling == sampling;
    }

    @Override
    public int hashCode() {
        return ID.hashCode() * 31 * radius + sampling;
    }

    /**
     * Classic Jaroslaw/Enrique Stack Blur algorithm — O(n) per pixel, no
     * native dependency required. Mutates and returns a fresh ARGB_8888
     * bitmap so the original decoded bitmap stays untouched.
     */
    private static Bitmap stackBlur(Bitmap src, int radius) {
        if (radius < 1) return src.copy(Bitmap.Config.ARGB_8888, true);
        Bitmap bitmap = src.copy(Bitmap.Config.ARGB_8888, true);

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1, hm = h - 1, wh = w * h, div = radius + radius + 1;
        int[] r = new int[wh], g = new int[wh], b = new int[wh], a = new int[wh];
        int rSum, gSum, bSum, aSum, x, y, i, p, yp, yi, yw;
        int[] vmin = new int[Math.max(w, h)];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) dv[i] = (i / divsum);

        yw = yi = 0;
        int[][] stack = new int[div][4];
        int stackpointer, stackstart, sir[], rbs;
        int routsum, goutsum, boutsum, aoutsum, rinsum, ginsum, binsum, ainsum;

        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = ainsum = routsum = goutsum = boutsum = aoutsum = rSum = gSum = bSum = aSum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p >> 16) & 0xff;
                sir[1] = (p >> 8) & 0xff;
                sir[2] = p & 0xff;
                sir[3] = (p >> 24) & 0xff;
                rbs = radius + 1 - Math.abs(i);
                rSum += sir[0] * rbs; gSum += sir[1] * rbs; bSum += sir[2] * rbs; aSum += sir[3] * rbs;
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3];
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3];
                }
            }
            stackpointer = radius;

            for (x = 0; x < w; x++) {
                r[yi] = dv[rSum]; g[yi] = dv[gSum]; b[yi] = dv[bSum]; a[yi] = dv[aSum];

                rSum -= routsum; gSum -= goutsum; bSum -= boutsum; aSum -= aoutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3];

                if (y == 0) vmin[x] = Math.min(x + radius + 1, wm);
                p = pix[yw + vmin[x]];

                sir[0] = (p >> 16) & 0xff; sir[1] = (p >> 8) & 0xff; sir[2] = p & 0xff; sir[3] = (p >> 24) & 0xff;

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3];
                rSum += rinsum; gSum += ginsum; bSum += binsum; aSum += ainsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3];
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]; ainsum -= sir[3];

                yi++;
            }
            yw += w;
        }

        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = ainsum = routsum = goutsum = boutsum = aoutsum = rSum = gSum = bSum = aSum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;
                sir = stack[i + radius];
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]; sir[3] = a[yi];
                rbs = radius + 1 - Math.abs(i);
                rSum += r[yi] * rbs; gSum += g[yi] * rbs; bSum += b[yi] * rbs; aSum += a[yi] * rbs;
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3];
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3];
                }
                if (i < hm) yp += w;
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = (dv[aSum] << 24) | (dv[rSum] << 16) | (dv[gSum] << 8) | dv[bSum];

                rSum -= routsum; gSum -= goutsum; bSum -= boutsum; aSum -= aoutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]; aoutsum -= sir[3];

                if (x == 0) vmin[y] = Math.min(y + radius + 1, hm) * w;
                p = x + vmin[y];

                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]; sir[3] = a[p];

                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]; ainsum += sir[3];
                rSum += rinsum; gSum += ginsum; bSum += binsum; aSum += ainsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]; aoutsum += sir[3];
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]; ainsum -= sir[3];

                yi += w;
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
        return bitmap;
    }

    /** Draws {@code src} scaled to fill a canvas of the given size (helper, currently unused directly). */
    private static Bitmap scaleToFill(Bitmap src, int w, int h) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(src, null, new android.graphics.Rect(0, 0, w, h), null);
        return out;
    }
}
