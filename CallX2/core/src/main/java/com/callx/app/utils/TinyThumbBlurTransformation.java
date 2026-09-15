package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;

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
 * This transformation blurs the bubble so it reads as a soft color/mood
 * preview instead — matching the blurred-preview look apps like WhatsApp
 * show before a media download completes.
 *
 * PERF (ultra, 15-Sep-2026): originally this blurred the FULL upscaled
 * bubble bitmap (e.g. 200x200 = 40,000px, 3 box-blur passes each). Now it:
 *   1. Downscales to a small working size (DOWNSCALE_PX, default 32) using
 *      a hardware-accelerated bilinear Canvas draw — ~40x fewer pixels to
 *      touch in the box-blur loops for a 200px bubble.
 *   2. Blurs at that small size (radius scaled proportionally by callers).
 *   3. Upscales back to bubble size, again via filtered bilinear draw —
 *      which itself adds extra smoothing for free, so the result is if
 *      anything SOFTER/closer to a real blurhash look than full-res blur.
 *   4. Reuses BitmapPool for both intermediate bitmaps (small + result)
 *      instead of raw allocations, and a per-thread reusable int[] pixel
 *      buffer (Glide's transformation pool is a small fixed set of
 *      background threads, so ThreadLocal buffers are cheap and avoid
 *      GC churn from a new int[] on every bind).
 *   5. Result is still disk+memory cached by Glide per-thumbUrl (see
 *      THUMB_RGB565's DiskCacheStrategy.ALL + updateDiskCacheKey below),
 *      so all of this only runs on a genuine cache miss — first time a
 *      given thumbUrl is bound, not on every rebind/rescroll.
 *
 * Deliberately NOT using RenderScript (deprecated, removed in newer APIs)
 * or a third-party blur lib — dependency-free box blur, works identically
 * pre- and post-hardware-bitmap devices (Glide auto-falls-back to a
 * software bitmap for any request with a custom Transformation attached).
 */
public class TinyThumbBlurTransformation extends BitmapTransformation {

    // v3: source thumb is now aspect-preserving (not square), so a working
    // bitmap can come in very thin (e.g. 24x8) — radius is now clamped per-
    // call to the smaller working dimension (see transform()) instead of
    // being used raw, which could otherwise over-blur/flatten a thin edge.
    // Bumping the ID busts any v2 disk-cache entries from the old
    // square-thumb + unclamped-radius behavior.
    private static final String ID = "com.callx.app.utils.TinyThumbBlurTransformation.v3";
    private static final byte[] ID_BYTES = ID.getBytes(Charset.forName("UTF-8"));

    // PERF: work size for the blur pass. Small enough to be ~free, large
    // enough that the box-blur radius still has real pixel neighborhoods
    // to average (avoids banding). 32px is close to the source 24px thumb's
    // own real detail level — we're not throwing away information the
    // 24px thumb had.
    private static final int DOWNSCALE_PX = 32;

    // PERF: bilinear-filtered Paint reused for both the downscale and
    // upscale Canvas draws (avoids allocating a new Paint per call).
    private static final ThreadLocal<Paint> FILTER_PAINT = new ThreadLocal<Paint>() {
        @Override protected Paint initialValue() {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setFilterBitmap(true);
            return p;
        }
    };

    // PERF: reusable int[] pixel scratch buffer per background thread.
    // Grown (not shrunk) as needed; at DOWNSCALE_PX=32 this settles at
    // 32*32=1024 ints (4KB) per thread almost immediately and is then
    // never reallocated again for the life of the app.
    private static final ThreadLocal<int[]> PIXEL_BUF = new ThreadLocal<>();

    private final int radius;

    /** @param radius blur radius in px, applied at DOWNSCALE_PX working size. Typical: 4-8 (scaled down from the old 12-20 full-res values). */
    public TinyThumbBlurTransformation(int radius) {
        this.radius = Math.max(1, radius);
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform,
                                int outWidth, int outHeight) {
        int srcW = toTransform.getWidth();
        int srcH = toTransform.getHeight();
        if (srcW == 0 || srcH == 0) return toTransform;

        Bitmap.Config config = toTransform.getConfig() != null
                ? toTransform.getConfig() : Bitmap.Config.ARGB_8888;

        // Step 1: downscale to a small working bitmap (pooled, not a raw
        // alloc) using a filtered Canvas draw — cheap, hardware-accelerated
        // bilinear resample.
        int smallW = Math.max(1, Math.min(DOWNSCALE_PX, srcW));
        int smallH = Math.max(1, Math.min(DOWNSCALE_PX, srcH));
        Bitmap small = pool.get(smallW, smallH, config);
        Canvas smallCanvas = new Canvas(small);
        smallCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
        smallCanvas.drawBitmap(toTransform, new Rect(0, 0, srcW, srcH),
                new Rect(0, 0, smallW, smallH), FILTER_PAINT.get());

        // Step 2: blur at the small size. FIX: clamp radius to the working
        // bitmap's own smaller dimension — a non-square (aspect-preserved)
        // source can produce a thin small bitmap (e.g. 24x8), where a fixed
        // radius tuned for 32x32 would exceed the short side and flatten it
        // into a uniform strip instead of a soft blur.
        int effRadius = Math.max(1, Math.min(radius, Math.max(1, Math.min(smallW, smallH) / 2)));
        boxBlur(small, effRadius, smallW, smallH);

        // Step 3: upscale back to the bubble's actual size, again via a
        // filtered draw — this adds a second layer of free smoothing on
        // top of the box blur, so it reads softer than full-res blur did.
        Bitmap result = pool.get(srcW, srcH, config);
        Canvas resultCanvas = new Canvas(result);
        resultCanvas.drawColor(0, PorterDuff.Mode.CLEAR);
        resultCanvas.drawBitmap(small, new Rect(0, 0, smallW, smallH),
                new Rect(0, 0, srcW, srcH), FILTER_PAINT.get());

        pool.put(small); // return the intermediate to the pool immediately
        return result;
    }

    // 3-pass box blur ≈ Gaussian, operates in place on `bmp` at (w,h).
    // O(w*h) per pass regardless of radius (sliding window sum). At
    // DOWNSCALE_PX=32 that's 1,024px vs. ~40,000px for a 200px bubble —
    // roughly 40x less work per bind than blurring at full bubble size.
    private void boxBlur(Bitmap bmp, int radius, int w, int h) {
        int need = w * h;
        int[] pixels = PIXEL_BUF.get();
        if (pixels == null || pixels.length < need) {
            pixels = new int[need];
            PIXEL_BUF.set(pixels);
        }
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] tmpRow = new int[Math.max(w, h)]; // small (<=32), negligible
        for (int pass = 0; pass < 3; pass++) {
            boxBlurHorizontal(pixels, tmpRow, w, h, radius);
            boxBlurVertical(pixels, tmpRow, w, h, radius);
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
    }

    private static void boxBlurHorizontal(int[] pixels, int[] tmp, int w, int h, int radius) {
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

    private static void boxBlurVertical(int[] pixels, int[] tmp, int w, int h, int radius) {
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
