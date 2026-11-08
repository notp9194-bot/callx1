package com.callx.app.compose;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.media.FaceDetector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * v751: smart photo fit for template boxes. Instead of always centre-cropping, the crop window is moved so the faces in
 * the photo stay inside the box (a face near the top / edge no longer gets cut).
 *
 * Uses the framework {@link FaceDetector} (no new Gradle dependency, works offline). It only finds fairly frontal faces,
 * so a photo with no detectable face simply returns null and the caller keeps the normal centre crop — it can never make
 * the result worse than before. Blocking (≈10–40 ms on a 360 px copy): call it OFF the main thread.
 */
public final class TemplateSmartCrop {

    private static final int MAX_SIDE = 360;       // detect on a small copy — fast, and plenty for faces
    private static final int MAX_FACES = 6;
    private static final float MIN_CONFIDENCE = 0.4f;

    private TemplateSmartCrop() { }

    /**
     * @return {cx, cy} (each 0..1 of the photo) = centre of everything that should stay visible (all detected faces with a
     *         bit of forehead / chin / shoulders around them), or null when no face was found.
     */
    @Nullable
    public static float[] faceFocus(@NonNull Bitmap src) {
        Bitmap scaled = null, rgb = null;
        try {
            final int bw = src.getWidth(), bh = src.getHeight();
            if (bw < 32 || bh < 32) return null;
            final float k = Math.min(1f, MAX_SIDE / (float) Math.max(bw, bh));
            int w = Math.max(16, Math.round(bw * k));
            final int h = Math.max(16, Math.round(bh * k));
            w &= ~1;                                  // FaceDetector needs an even width

            scaled = Bitmap.createScaledBitmap(src, w, h, true);
            rgb = scaled.copy(Bitmap.Config.RGB_565, false); // FaceDetector only reads RGB_565
            if (rgb == null) return null;

            final FaceDetector.Face[] faces = new FaceDetector.Face[MAX_FACES];
            final int n = new FaceDetector(w, h, MAX_FACES).findFaces(rgb, faces);
            if (n <= 0) return null;

            float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
            boolean any = false;
            final PointF mid = new PointF();
            for (int i = 0; i < n && i < MAX_FACES; i++) {
                final FaceDetector.Face f = faces[i];
                if (f == null || f.confidence() < MIN_CONFIDENCE) continue;
                final float ed = f.eyesDistance();    // distance between the eyes ≈ the scale of the face
                if (ed <= 0f) continue;
                f.getMidPoint(mid);                   // midpoint between the eyes
                l = Math.min(l, mid.x - 1.9f * ed);   // face + hair / ears
                r = Math.max(r, mid.x + 1.9f * ed);
                t = Math.min(t, mid.y - 1.8f * ed);   // forehead + hair
                b = Math.max(b, mid.y + 2.6f * ed);   // chin + neck
                any = true;
            }
            if (!any) return null;
            final float cx = Math.max(0f, Math.min(1f, ((l + r) / 2f) / w));
            final float cy = Math.max(0f, Math.min(1f, ((t + b) / 2f) / h));
            return new float[]{cx, cy};
        } catch (Throwable ignored) {                 // OOM / odd bitmap config / device quirk → plain centre crop
            return null;
        } finally {
            try {
                if (rgb != null) rgb.recycle();
                if (scaled != null && scaled != src) scaled.recycle();
            } catch (Throwable ignored) { }
        }
    }
}
