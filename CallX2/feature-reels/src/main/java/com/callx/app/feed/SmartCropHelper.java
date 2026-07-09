package com.callx.app.feed;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.media.FaceDetector;
import android.widget.ImageView;

/**
 * SmartCropHelper ── face-aware focal-point crop.
 *
 * Instead of always centre-cropping a photo (which regularly slices off
 * subjects' faces when the photo's aspect ratio doesn't match the 9:16
 * slide), this runs Android's on-device {@link FaceDetector} against a
 * small downsampled copy of the bitmap to estimate a focal point, then
 * shifts a MATRIX-scaletype ImageView so that point stays centred instead
 * of the geometric centre of the frame.
 *
 * No extra Gradle dependency: uses the platform's built-in (deprecated but
 * functional) android.media.FaceDetector, which is more than adequate for a
 * coarse "don't crop the face" focal point — this is not a beauty filter.
 */
public final class SmartCropHelper {

    private SmartCropHelper() {}

    /** Max size for the face-scan bitmap; keeps detection fast (runs on a bg thread). */
    private static final int SCAN_MAX_DIM = 400;
    private static final int MAX_FACES = 4;

    public static class FocalPoint {
        /** 0f..1f fraction across the bitmap width/height. Defaults to centre. */
        public float xFrac = 0.5f;
        public float yFrac = 0.42f; // slightly above centre — flattering default even w/o a face
        public boolean faceFound = false;
    }

    /**
     * Runs face detection synchronously — call from a background thread only.
     */
    public static FocalPoint detectFocalPoint(Bitmap source) {
        FocalPoint out = new FocalPoint();
        if (source == null || source.getWidth() < 2 || source.getHeight() < 2) return out;

        try {
            int w = source.getWidth();
            int h = source.getHeight();
            float scale = Math.min(1f, SCAN_MAX_DIM / (float) Math.max(w, h));
            int sw = Math.max(2, Math.round(w * scale));
            int sh = Math.max(2, Math.round(h * scale));
            // FaceDetector requires even width.
            if (sw % 2 != 0) sw--;

            Bitmap scan = Bitmap.createScaledBitmap(source, sw, sh, true)
                    .copy(Bitmap.Config.RGB_565, false);
            try {
                FaceDetector detector = new FaceDetector(sw, sh, MAX_FACES);
                FaceDetector.Face[] faces = new FaceDetector.Face[MAX_FACES];
                int found = detector.findFaces(scan, faces);

                if (found > 0) {
                    float sumX = 0f, sumY = 0f;
                    int counted = 0;
                    for (int i = 0; i < found; i++) {
                        FaceDetector.Face f = faces[i];
                        if (f == null) continue;
                        PointF mid = new PointF();
                        f.getMidPoint(mid);
                        sumX += mid.x;
                        sumY += mid.y;
                        counted++;
                    }
                    if (counted > 0) {
                        out.xFrac = clamp01(sumX / counted / sw);
                        out.yFrac = clamp01(sumY / counted / sh);
                        out.faceFound = true;
                    }
                }
            } finally {
                scan.recycle();
            }
        } catch (Throwable ignored) {
            // FaceDetector can throw on odd devices/bitmap configs — fall back to centre-ish default.
        }
        return out;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * Applies a focal-point-aware centerCrop to {@code iv} using MATRIX
     * scale type: the bitmap is scaled to fully cover the view, then
     * translated so the focal point lands as close to the view's centre as
     * the crop bounds allow (instead of always using the bitmap's
     * geometric centre, like a fixed centerCrop would).
     */
    public static void applyFocalCrop(ImageView iv, Bitmap bmp, FocalPoint focal) {
        int vw = iv.getWidth();
        int vh = iv.getHeight();
        if (vw == 0 || vh == 0 || bmp == null) return;

        int bw = bmp.getWidth();
        int bh = bmp.getHeight();

        float scale = Math.max(vw / (float) bw, vh / (float) bh);
        float scaledW = bw * scale;
        float scaledH = bh * scale;

        float focalPxX = focal.xFrac * scaledW;
        float focalPxY = focal.yFrac * scaledH;

        // Desired top-left so the focal point sits at the view centre.
        float dx = vw / 2f - focalPxX;
        float dy = vh / 2f - focalPxY;

        // Clamp so we never reveal empty space beyond the scaled bitmap edges.
        dx = Math.min(0f, Math.max(dx, vw - scaledW));
        dy = Math.min(0f, Math.max(dy, vh - scaledH));

        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(dx, dy);

        iv.setScaleType(ImageView.ScaleType.MATRIX);
        iv.setImageMatrix(m);
    }
}
