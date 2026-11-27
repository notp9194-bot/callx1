package com.callx.app.activities;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

/**
 * PERF (perceived speed): the chat bubble's thumbnail stays visible inside the
 * player until the first video frame is rendered, instead of a black shutter.
 *
 * Mechanism: the PlayerView's own shutter is made transparent and the thumbnail is
 * drawn as the PlayerView BACKGROUND (fit-center on black). Because it is the view's
 * own background it scales/moves with the open/close animation for free (single mode
 * animates binding.player itself; gallery mode animates the whole pager). The
 * thumbnail comes from Glide's caches only (onlyRetrieveFromCache) — no network.
 * {@link #onFirstFrame} restores a plain black background and drops the bitmap.
 */
final class VideoShutter {

    private VideoShutter() {}

    /** Call before the player is attached. No-op image-wise if thumbUrl is empty/uncached. */
    static void show(@NonNull PlayerView pv, @Nullable String thumbUrl) { show(pv, thumbUrl, null); }

    /** @param immediate the bubble's own decoded bitmap (zero decode, frame 1); skips the Glide lookup when present. */
    static void show(@NonNull PlayerView pv, @Nullable String thumbUrl, @Nullable Bitmap immediate) {
        pv.setShutterBackgroundColor(Color.TRANSPARENT);
        pv.setBackgroundColor(Color.BLACK);
        pv.setTag(null);
        if (immediate != null && !immediate.isRecycled()) {
            pv.setTag("immediate");
            pv.setBackground(new FitCenterBitmapDrawable(immediate));
            return;
        }
        if (thumbUrl == null || thumbUrl.isEmpty()) return;
        pv.setTag(thumbUrl);
        try {
            Glide.with(pv.getContext())
                    .asBitmap()
                    .load(thumbUrl)
                    .onlyRetrieveFromCache(true)
                    .into(new CustomTarget<Bitmap>(512, 512) {
                        @Override public void onResourceReady(@NonNull Bitmap bmp, @Nullable Transition<? super Bitmap> t) {
                            // Stale guard: the holder may have been recycled / first frame may already be out.
                            if (!thumbUrl.equals(pv.getTag())) return;
                            pv.setBackground(new FitCenterBitmapDrawable(bmp));
                        }
                        @Override public void onLoadCleared(@Nullable Drawable placeholder) {}
                    });
        } catch (Throwable ignored) {
            // Context torn down — the black background is a fine fallback.
        }
    }

    /** First real frame is on screen (or playback started): drop the thumbnail. */
    static void onFirstFrame(@NonNull PlayerView pv) {
        if (pv.getTag() == null) return;
        pv.setTag(null);
        pv.setBackgroundColor(Color.BLACK);
    }

    /** Black, then the bitmap fit-centered — same letterboxing the video itself gets. */
    private static final class FitCenterBitmapDrawable extends Drawable {
        private final Bitmap bmp;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect dst = new Rect();

        FitCenterBitmapDrawable(Bitmap bmp) { this.bmp = bmp; }

        @Override public void draw(@NonNull Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty() || bmp.getWidth() <= 0 || bmp.getHeight() <= 0) return;
            c.drawColor(Color.BLACK);
            float s = Math.min(b.width() / (float) bmp.getWidth(), b.height() / (float) bmp.getHeight());
            int w = Math.round(bmp.getWidth() * s);
            int h = Math.round(bmp.getHeight() * s);
            int l = b.left + (b.width() - w) / 2;
            int t = b.top + (b.height() - h) / 2;
            dst.set(l, t, l + w, t + h);
            c.drawBitmap(bmp, null, dst, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { paint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }
}
