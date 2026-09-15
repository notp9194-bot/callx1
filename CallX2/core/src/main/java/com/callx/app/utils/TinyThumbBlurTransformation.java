package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * TinyThumbBlurTransformation — smooths the pre-download chat thumbnail.
 *
 * Since 24-Sep-2026 ImageCompressor emits a 24×24px thumbnail (was 200×200)
 * to cut thumbUrl size ~99% (see ImageCompressor THUMB_SIZE/THUMB_QUALITY/
 * THUMB_TARGET_BYTES). Displayed at bubble size (~200dp) that source is
 * blown up ~8x, so raw nearest/bilinear upscaling shows hard, blocky pixels.
 * This transformation runs a box blur (3 passes ≈ Gaussian) on the ALREADY
 * upscaled bitmap so the bubble reads as a soft color/mood preview instead —
 * matching the blurred-preview look apps like WhatsApp show before a media
 * download completes.
 *
 * Deliberately NOT using RenderScript (deprecated, removed in newer APIs)
 * or a third-party blur lib — this is a small, dependency-free stack/box
 * blur that works identically pre- and post-hardware-bitmap devices (Glide
 * automatically falls back to a software bitmap for any request that has a
 * custom Transformation attached, so no extra wiring needed at call sites).
 */
public class TinyThumbBlurTransformation extends BitmapTransformation {

    private static final String ID = "com.callx.app.utils.TinyThumbBlurTransformation";
    private static final byte[] ID_BYTES = ID.getBytes(Charset.forName("UTF-8"));

    private final int radius;

    /** @param radius blur radius in px, applied at the transformation's output size (post-scale). Typical: 12-20. */
    public TinyThumbBlurTransformation(int radius) {
        this.radius = Math.max(1, radius);
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform,
                                int outWidth, int outHeight) {
        // Glide already scaled toTransform to outWidth/outHeight per the
        // request's .override()/centerCrop() before handing it to us, so we
        // blur at display size — cheap (bubble-sized bitmap, not the 24px
        // source) and avoids a second resize pass.
        Bitmap.Config config = toTransform.getConfig() != null
                ? toTransform.getConfig() : Bitmap.Config.ARGB_8888;
        Bitmap result = pool.get(toTransform.getWidth(), toTransform.getHeight(), config);
        new Canvas(result).drawBitmap(toTransform, 0, 0, null);
        boxBlur(result, radius);
        return result;
    }

    // Simple 3-pass box blur ≈ Gaussian, operates in place on `bmp`.
    // O(w*h) per pass regardless of radius (sliding window sum), so a
    // 200x200 bubble blurs in well under a millisecond.
    private static void boxBlur(Bitmap bmp, int radius) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w == 0 || h == 0) return;
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlurHorizontal(pixels, w, h, radius);
            boxBlurVertical(pixels, w, h, radius);
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static void boxBlurHorizontal(int[] pixels, int w, int h, int radius) {
        int[] tmp = new int[w];
        for (int y = 0; y < h; y++) {
            int rowStart = y * w;
            long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            for (int x = -radius; x <= radius; x++) {
                int px = clamp(x, 0, w - 1);
                int c = pixels[rowStart + px];
                sumA += (c >>> 24) & 0xFF;
                sumR += (c >>> 16) & 0xFF;
                sumG += (c >>> 8) & 0xFF;
                sumB += c & 0xFF;
                count++;
            }
            for (int x = 0; x < w; x++) {
                tmp[x] = ((int) (sumA / count) << 24) | ((int) (sumR / count) << 16)
                        | ((int) (sumG / count) << 8) | (int) (sumB / count);
                int addX = clamp(x + radius + 1, 0, w - 1);
                int subX = clamp(x - radius, 0, w - 1);
                if (x + radius + 1 < w && x - radius >= 0) {
                    int add = pixels[rowStart + addX];
                    int sub = pixels[rowStart + subX];
                    sumA += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                    sumR += ((add >>> 16) & 0xFF) - ((sub >>> 16) & 0xFF);
                    sumG += ((add >>> 8) & 0xFF) - ((sub >>> 8) & 0xFF);
                    sumB += (add & 0xFF) - (sub & 0xFF);
                }
            }
            System.arraycopy(tmp, 0, pixels, rowStart, w);
        }
    }

    private static void boxBlurVertical(int[] pixels, int w, int h, int radius) {
        int[] tmp = new int[h];
        for (int x = 0; x < w; x++) {
            long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
            int count = 0;
            for (int y = -radius; y <= radius; y++) {
                int py = clamp(y, 0, h - 1);
                int c = pixels[py * w + x];
                sumA += (c >>> 24) & 0xFF;
                sumR += (c >>> 16) & 0xFF;
                sumG += (c >>> 8) & 0xFF;
                sumB += c & 0xFF;
                count++;
            }
            for (int y = 0; y < h; y++) {
                tmp[y] = ((int) (sumA / count) << 24) | ((int) (sumR / count) << 16)
                        | ((int) (sumG / count) << 8) | (int) (sumB / count);
                if (y + radius + 1 < h && y - radius >= 0) {
                    int add = pixels[clamp(y + radius + 1, 0, h - 1) * w + x];
                    int sub = pixels[clamp(y - radius, 0, h - 1) * w + x];
                    sumA += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                    sumR += ((add >>> 16) & 0xFF) - ((sub >>> 16) & 0xFF);
                    sumG += ((add >>> 8) & 0xFF) - ((sub >>> 8) & 0xFF);
                    sumB += (add & 0xFF) - (sub & 0xFF);
                }
            }
            for (int y = 0; y < h; y++) pixels[y * w + x] = tmp[y];
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest messageDigest) {
        messageDigest.update(ID_BYTES);
        messageDigest.update(String.valueOf(radius).getBytes(Charset.forName("UTF-8")));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TinyThumbBlurTransformation
                && ((TinyThumbBlurTransformation) o).radius == radius;
    }

    @Override
    public int hashCode() {
        return ID.hashCode() * 31 + radius;
    }
}
