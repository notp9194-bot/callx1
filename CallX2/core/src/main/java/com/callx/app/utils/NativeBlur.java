package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.nio.ByteBuffer;

/**
 * NativeBlur — GPU-accelerated bitmap blur via RenderEffect/RenderNode,
 * for API 31+ (RenderScript's modern replacement; RenderScript itself is
 * deprecated since API 31 and shouldn't be added to new code).
 *
 * Used only for the "big bubble" chat-media placeholder case (see
 * MediaRenderer#blurPlaceholderForBubble) — RenderNode/HardwareRenderer/
 * ImageReader setup has real fixed overhead per call, so it's only worth
 * it once the adaptive blur radius is already at the high end (a large,
 * heavily-upscaled bubble). Smaller/typical bubbles keep using the
 * existing pure-Java 3-pass box blur, which is already O(w*h) via a
 * sliding-window sum and cheap enough at the tiny 32x32 source size.
 *
 * A single ImageReader + HardwareRenderer pair is lazily created once and
 * reused across every call (resized only if a request needs more room
 * than the current one holds), so repeated calls during fast-scroll don't
 * each pay full GPU surface setup cost.
 *
 * Returns null on anything unsupported/unexpected — callers must fall
 * back to the Java box blur in that case, same contract as
 * ThumbHash.decode()/BlurHash.decode() returning null on failure.
 */
public final class NativeBlur {

    private NativeBlur() {}

    private static final String TAG = "NativeBlur";

    @androidx.annotation.ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    private static ImageReader sReader;
    private static HardwareRenderer sRenderer;
    private static int sReaderW = -1, sReaderH = -1;

    /**
     * Returns a new blurred bitmap (ARGB_8888, same size as src), or null
     * if unsupported or anything goes wrong — caller should fall back to
     * the Java box blur path in that case. Never mutates src.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public static synchronized Bitmap blur(Bitmap src, float radiusPx) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        try {
            ensureSurface(w, h);

            RenderNode node = new RenderNode("thumbhash-native-blur");
            node.setPosition(0, 0, w, h);
            node.setRenderEffect(RenderEffect.createBlurEffect(
                    Math.max(0.01f, radiusPx), Math.max(0.01f, radiusPx), Shader.TileMode.CLAMP));
            Canvas canvas = node.beginRecording();
            canvas.drawBitmap(src, 0, 0, null);
            node.endRecording();

            sRenderer.setContentRoot(node);
            sRenderer.createRenderRequest()
                    .setWaitForPresent(true)
                    .syncAndDraw();

            Image image = sReader.acquireLatestImage();
            if (image == null) return null;
            try {
                Image.Plane plane = image.getPlanes()[0];
                ByteBuffer buf = plane.getBuffer();
                int pixelStride = plane.getPixelStride();
                int rowStride = plane.getRowStride();
                int rowPaddingPx = (rowStride - pixelStride * sReaderW) / pixelStride;

                Bitmap padded = Bitmap.createBitmap(sReaderW + rowPaddingPx, sReaderH, Bitmap.Config.ARGB_8888);
                padded.copyPixelsFromBuffer(buf);
                Bitmap cropped = (rowPaddingPx == 0 && sReaderW == w && sReaderH == h)
                        ? padded
                        : Bitmap.createBitmap(padded, 0, 0, w, h);
                if (cropped != padded) padded.recycle();

                // Preserve the source's config (advance #2's RGB_565 for
                // opaque hashes) — the ImageReader path only speaks
                // ARGB_8888, so convert back down rather than silently
                // doubling memory for an opaque placeholder.
                Bitmap.Config srcCfg = src.getConfig();
                if (srcCfg == Bitmap.Config.RGB_565 && cropped.getConfig() != Bitmap.Config.RGB_565) {
                    Bitmap downsampled = cropped.copy(Bitmap.Config.RGB_565, false);
                    cropped.recycle();
                    return downsampled;
                }
                return cropped;
            } finally {
                image.close();
            }
        } catch (Exception e) {
            // GPU/driver quirk on some OEM — non-fatal, caller falls back
            // to the Java box blur for this bubble.
            Log.w(TAG, "native blur failed, caller will fall back to Java box blur", e);
            return null;
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static void ensureSurface(int w, int h) {
        if (sReader != null && sReaderW >= w && sReaderH >= h) return;
        releaseSurface();
        sReaderW = Math.max(w, 32);
        sReaderH = Math.max(h, 32);
        sReader = ImageReader.newInstance(sReaderW, sReaderH, PixelFormat.RGBA_8888, 2);
        sRenderer = new HardwareRenderer();
        sRenderer.setSurface(sReader.getSurface());
    }

    private static void releaseSurface() {
        if (sRenderer != null) {
            try { sRenderer.destroy(); } catch (Exception ignored) {}
            sRenderer = null;
        }
        if (sReader != null) {
            try { sReader.close(); } catch (Exception ignored) {}
            sReader = null;
        }
    }
}
