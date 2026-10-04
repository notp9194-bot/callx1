package com.callx.app.utils;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * Crops the CENTER SQUARE out of a bitmap — no scaling, so no upscale/quality loss.
 *
 * Why: new avatars are uploaded as an already-cropped square (see MediaCropActivity
 * EXTRA_SQUARE_LOCKED), so this is a no-op for them (returns the same bitmap).
 * Old avatars uploaded before that change can be portrait/landscape; the small
 * profile avatar shows them via circleCrop() (= center square). Applying the same
 * center-square here makes the avatar-zoom dialog frame them identically, instead
 * of showing a different (fit-center) region of the photo.
 */
public final class SquareCenterCrop extends BitmapTransformation {

    private static final String ID = "com.callx.app.utils.SquareCenterCrop.v1";
    private static final byte[] ID_BYTES = ID.getBytes(Charset.forName("UTF-8"));

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap src,
                               int outWidth, int outHeight) {
        int w = src.getWidth(), h = src.getHeight();
        if (w == h) return src;                       // already square (all new avatars)
        int side = Math.min(w, h);
        return Bitmap.createBitmap(src, (w - side) / 2, (h - side) / 2, side, side);
    }

    @Override public boolean equals(Object o) { return o instanceof SquareCenterCrop; }
    @Override public int hashCode() { return ID.hashCode(); }
    @Override public void updateDiskCacheKey(@NonNull MessageDigest md) { md.update(ID_BYTES); }
}
