package com.callx.app.chat.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.core.content.ContextCompat;

import com.callx.app.chat.R;

/**
 * Shared live-blur engine for every glass surface in the chat screen
 * (header icons, input bar). One instance per glass host view.
 *
 * It blurs whatever {@code source} draws behind the host and exposes
 * {@link #draw(Canvas)} which paints that blurred backdrop in the HOST's own
 * coordinate space (caller clips it to the glass shape first).
 *
 *  - API 31+: RenderNode + RenderEffect (blur + saturation) on the GPU.
 *  - API 23-30: tiny bitmap capture + box blur, throttled; silently degrades
 *    (draw() returns false) if capture is impossible.
 *
 * Refreshed in a pre-draw listener WITHOUT invalidate() — invalidating there
 * would re-schedule a frame forever. It therefore only runs in frames that
 * something else already invalidated; an idle chat costs nothing.
 *
 * IMPORTANT: {@code source} must NOT contain any glass host (that would make
 * the RenderNodes reference each other). In activity_chat.xml the source is
 * fl_chat_backdrop = wallpaper + skeleton + message list only.
 */
final class GlassBackdrop {

    static final float SATURATION = 1.5f;
    private static final float SOFT_SCALE = 1f / 8f;
    private static final long SOFT_MIN_INTERVAL_MS = 48L;

    private final View host;
    private final int sourceId;
    private final float blurPx;
    private View source;

    private boolean enabled = true;
    private boolean dark;
    private int baseColor;
    private boolean ready;

    private final int[] locHost = new int[2];
    private final int[] locSrc = new int[2];

    // API 31+
    private GlassRenderNodeBackdrop node;
    // API < 31
    private Bitmap softBitmap;
    private Canvas softCanvas;
    private int[] softPx, softTmp;
    private long lastSoftAt;
    private boolean softFailed;
    private boolean trailingPosted;
    private final RectF softDst = new RectF();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final Runnable trailingInvalidate = this::onTrailingInvalidate;

    private void onTrailingInvalidate() {
        trailingPosted = false;
        host.invalidate();
    }

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        update();
        return true;
    };

    GlassBackdrop(View host, int sourceId, float blurDp) {
        this.host = host;
        this.sourceId = sourceId;
        this.blurPx = blurDp * host.getResources().getDisplayMetrics().density;
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(SATURATION);
        bitmapPaint.setColorFilter(new ColorMatrixColorFilter(cm));
        refreshTheme();
    }

    boolean isDark() { return dark; }
    int baseColor() { return baseColor; }
    void setEnabled(boolean e) { enabled = e; }

    void refreshTheme() {
        Context c = host.getContext();
        dark = (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        baseColor = ContextCompat.getColor(c, R.color.chat_unified_bg) | 0xFF000000;
    }

    void attach() {
        refreshTheme();
        host.getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    void detach() {
        host.getViewTreeObserver().removeOnPreDrawListener(preDraw);
        host.removeCallbacks(trailingInvalidate);
        trailingPosted = false;
        source = null;
        ready = false;
    }

    private View resolveSource() {
        if (source == null && sourceId != View.NO_ID) {
            source = host.getRootView().findViewById(sourceId);
        }
        return source;
    }

    private void update() {
        if (!enabled || !host.isShown()) return;
        final int w = host.getWidth(), h = host.getHeight();
        if (w <= 0 || h <= 0) return;
        View src = resolveSource();
        if (src == null || src.getWidth() <= 0) return;

        host.getLocationInWindow(locHost);
        src.getLocationInWindow(locSrc);
        float offX = locHost[0] - locSrc[0];
        float offY = locHost[1] - locSrc[1];

        if (Build.VERSION.SDK_INT >= 31 && host.isHardwareAccelerated()) {
            if (node == null) node = new GlassRenderNodeBackdrop(blurPx, SATURATION);
            ready = node.record(src, w, h, offX, offY, baseColor);
        } else {
            captureSoftware(src, w, h, offX, offY);
        }
    }

    private void captureSoftware(View src, int w, int h, float offX, float offY) {
        if (softFailed) return;
        long now = SystemClock.uptimeMillis();
        if (softBitmap != null && now - lastSoftAt < SOFT_MIN_INTERVAL_MS) {
            if (!trailingPosted) {
                trailingPosted = true;
                host.postDelayed(trailingInvalidate, SOFT_MIN_INTERVAL_MS);
            }
            return;
        }
        try {
            int sw = Math.max(2, Math.round(w * SOFT_SCALE));
            int sh = Math.max(2, Math.round(h * SOFT_SCALE));
            if (softBitmap == null || softBitmap.getWidth() != sw || softBitmap.getHeight() != sh) {
                softBitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                softCanvas = new Canvas(softBitmap);
                softPx = new int[sw * sh];
                softTmp = new int[sw * sh];
            }
            softBitmap.eraseColor(baseColor);
            int save = softCanvas.save();
            softCanvas.scale(sw / (float) w, sh / (float) h);
            softCanvas.translate(-offX, -offY);
            src.draw(softCanvas);
            softCanvas.restoreToCount(save);

            softBitmap.getPixels(softPx, 0, sw, 0, 0, sw, sh);
            int r = Math.max(1, Math.round(blurPx * SOFT_SCALE / 1.6f));
            for (int i = 0; i < 2; i++) {
                boxBlur(softPx, softTmp, sw, sh, r, true);
                boxBlur(softTmp, softPx, sw, sh, r, false);
            }
            softBitmap.setPixels(softPx, 0, sw, 0, 0, sw, sh);
            lastSoftAt = now;
            ready = true;
        } catch (Throwable t) {
            // e.g. hardware bitmaps cannot be drawn onto a software canvas
            softFailed = true;
            ready = false;
            softBitmap = null;
        }
    }

    /** Draws the blurred backdrop covering the whole host. Returns false if unavailable. */
    boolean draw(Canvas canvas) {
        if (!ready) return false;
        if (Build.VERSION.SDK_INT >= 31 && node != null && canvas.isHardwareAccelerated()) {
            node.draw(canvas);
            return true;
        }
        if (softBitmap != null && !softFailed) {
            softDst.set(0, 0, host.getWidth(), host.getHeight());
            canvas.drawBitmap(softBitmap, null, softDst, bitmapPaint);
            return true;
        }
        return false;
    }

    private static int clamp(int v, int max) {
        return v < 0 ? 0 : (v > max ? max : v);
    }

    /** Edge-clamped sliding-window box blur, horizontal or vertical. */
    private static void boxBlur(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        final int div = 2 * r + 1;
        final int lines = horizontal ? h : w;
        final int len = horizontal ? w : h;
        final int lineStride = horizontal ? w : 1;
        final int step = horizontal ? 1 : w;
        for (int l = 0; l < lines; l++) {
            final int base = l * lineStride;
            int sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int p = src[base + clamp(i, len - 1) * step];
                sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[base + i * step] = 0xFF000000 | ((sr / div) << 16) | ((sg / div) << 8) | (sb / div);
                int add = src[base + clamp(i + r + 1, len - 1) * step];
                int rem = src[base + clamp(i - r, len - 1) * step];
                sr += ((add >> 16) & 255) - ((rem >> 16) & 255);
                sg += ((add >> 8) & 255) - ((rem >> 8) & 255);
                sb += (add & 255) - (rem & 255);
            }
        }
    }
}
