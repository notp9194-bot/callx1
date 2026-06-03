package com.callx.app.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.view.TextureView;
import androidx.annotation.NonNull;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import java.nio.ByteBuffer;

/**
 * Feature: Background Blur
 *
 * Ek custom VideoSink jo camera frames ko process karta hai:
 *  1. VideoFrame (I420 ya TextureBuffer) → Bitmap
 *  2. ML Kit Selfie Segmenter → person mask
 *  3. Background pixels pe Gaussian blur
 *  4. Processed frame → TextureView (local video preview)
 *
 * Usage:
 *   BackgroundBlurProcessor blurProc = new BackgroundBlurProcessor(context, textureView);
 *   localVideoTrack.addSink(blurProc);
 *   blurProc.setEnabled(true);   // blur on
 *   blurProc.setEnabled(false);  // bypass
 *   blurProc.release();          // cleanup
 */
public class BackgroundBlurProcessor implements VideoSink {

    private final TextureView outputView;
    private volatile boolean enabled = false;
    private volatile boolean released = false;
    private Segmenter segmenter;
    private RenderScript rs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean processingFrame = false;

    public BackgroundBlurProcessor(@NonNull android.content.Context ctx,
                                   @NonNull TextureView outputView) {
        this.outputView = outputView;
        try {
            rs = RenderScript.create(ctx);
            SelfieSegmenterOptions options = new SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
                .build();
            segmenter = Segmentation.getClient(options);
        } catch (Exception ignored) {}
    }

    public void setEnabled(boolean on) {
        this.enabled = on;
    }

    @Override
    public void onFrame(VideoFrame frame) {
        if (released) return;
        if (!enabled || processingFrame) {
            // Bypass: render directly (caller should also keep the real sink)
            return;
        }
        processingFrame = true;

        // Retain frame buffer for async use
        frame.retain();
        Bitmap input = videoFrameToBitmap(frame);
        frame.release();

        if (input == null) { processingFrame = false; return; }

        try {
            InputImage image = InputImage.fromBitmap(input, 0);
            segmenter.process(image)
                .addOnSuccessListener(mask -> {
                    try {
                        Bitmap result = applyBlur(input, mask);
                        renderToView(result);
                    } catch (Exception ignored) {}
                    finally { processingFrame = false; }
                })
                .addOnFailureListener(e -> {
                    renderToView(input);
                    processingFrame = false;
                });
        } catch (Exception e) {
            processingFrame = false;
        }
    }

    // ── VideoFrame → Bitmap ───────────────────────────────────────────────
    private Bitmap videoFrameToBitmap(VideoFrame frame) {
        try {
            VideoFrame.I420Buffer i420 = frame.getBuffer().toI420();
            int w = i420.getWidth(), h = i420.getHeight();
            int[] argb = new int[w * h];
            i420ToArgb(i420, argb, w, h);
            Bitmap bm = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
            i420.release();
            // Scale down for performance
            return Bitmap.createScaledBitmap(bm, w / 2, h / 2, false);
        } catch (Exception e) { return null; }
    }

    // Basic YUV I420 → ARGB conversion (pure Java)
    private void i420ToArgb(VideoFrame.I420Buffer i420, int[] out, int w, int h) {
        ByteBuffer y = i420.getDataY(), u = i420.getDataU(), v = i420.getDataV();
        int yStride = i420.getStrideY(), uStride = i420.getStrideU();
        int vStride = i420.getStrideV();
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int yVal = (y.get(row * yStride + col) & 0xFF);
                int uVal = (u.get((row / 2) * uStride + col / 2) & 0xFF) - 128;
                int vVal = (v.get((row / 2) * vStride + col / 2) & 0xFF) - 128;
                int r = clamp(yVal + (int)(1.370705f * vVal));
                int g = clamp(yVal - (int)(0.698001f * vVal) - (int)(0.337633f * uVal));
                int b = clamp(yVal + (int)(1.732446f * uVal));
                out[row * w + col] = Color.rgb(r, g, b);
            }
        }
    }

    private int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    // ── Apply blur to background using segmentation mask ─────────────────
    private Bitmap applyBlur(Bitmap original, SegmentationMask mask) {
        int w = original.getWidth(), h = original.getHeight();

        // Create blurred version of the whole image
        Bitmap blurred = applyGaussianBlur(original);
        if (blurred == null) return original;

        // Scale mask to match (possibly scaled-down) bitmap
        Bitmap maskBm = maskToBitmap(mask, w, h);

        // Composite: background = blurred, foreground = original (where mask is opaque)
        Bitmap result = blurred.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(result);

        // Draw original only where mask says "person"
        Paint maskPaint = new Paint();
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OVER));

        // Create masked original (person only)
        Bitmap personOnly = original.copy(Bitmap.Config.ARGB_8888, true);
        applyMask(personOnly, maskBm);
        canvas.drawBitmap(personOnly, 0, 0, null);

        return result;
    }

    private Bitmap applyGaussianBlur(Bitmap src) {
        if (rs == null) return src;
        try {
            Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
            Allocation inAlloc  = Allocation.createFromBitmap(rs, src);
            Allocation outAlloc = Allocation.createFromBitmap(rs, out);
            ScriptIntrinsicBlur script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            script.setRadius(18f);
            script.setInput(inAlloc);
            script.forEach(outAlloc);
            outAlloc.copyTo(out);
            return out;
        } catch (Exception e) { return src; }
    }

    private Bitmap maskToBitmap(SegmentationMask mask, int targetW, int targetH) {
        int mw = mask.getWidth(), mh = mask.getHeight();
        int[] pixels = new int[mw * mh];
        ByteBuffer buf = mask.getBuffer();
        buf.rewind();
        for (int i = 0; i < mw * mh; i++) {
            float confidence = buf.getFloat();
            int alpha = (int)(confidence * 255);
            pixels[i] = Color.argb(alpha, 0, 0, 0);
        }
        Bitmap bm = Bitmap.createBitmap(pixels, mw, mh, Bitmap.Config.ARGB_8888);
        return Bitmap.createScaledBitmap(bm, targetW, targetH, true);
    }

    private void applyMask(Bitmap target, Bitmap mask) {
        Canvas canvas = new Canvas(target);
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(mask, 0, 0, p);
    }

    // ── Render to TextureView ─────────────────────────────────────────────
    private void renderToView(Bitmap bm) {
        if (bm == null || !outputView.isAvailable()) return;
        try {
            Canvas canvas = outputView.lockCanvas();
            if (canvas == null) return;
            canvas.drawColor(Color.BLACK);
            // Fit bitmap to view
            float sw = (float) outputView.getWidth() / bm.getWidth();
            float sh = (float) outputView.getHeight() / bm.getHeight();
            float scale = Math.max(sw, sh);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate(
                (outputView.getWidth()  - bm.getWidth()  * scale) / 2f,
                (outputView.getHeight() - bm.getHeight() * scale) / 2f);
            // Mirror for front camera
            m.preScale(-1, 1, bm.getWidth() / 2f, 0);
            canvas.drawBitmap(bm, m, null);
            outputView.unlockCanvasAndPost(canvas);
        } catch (Exception ignored) {}
    }

    public void release() {
        released = true;
        try { if (segmenter != null) segmenter.close(); } catch (Exception ignored) {}
        try { if (rs != null) rs.destroy(); } catch (Exception ignored) {}
    }
}
