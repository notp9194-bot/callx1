package com.callx.app.feed;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;

/**
 * SmartCropImageView ── face-aware focal-point crop
 * ──────────────────────────────────────────────────
 * Drop-in replacement for a plain {@code android:scaleType="centerCrop"}
 * ImageView. Behaves exactly like centerCrop by default (so it's a safe
 * swap in any existing layout), but once ML Kit's on-device face detector
 * finds a face in the bitmap, the crop window is re-centered on the face
 * instead of the geometric middle of the image — so a portrait thumbnail
 * (feed cards, pinned-reel hero image, etc.) doesn't chop off foreheads/chins
 * the way a naive centerCrop can on a fixed-height card.
 *
 * Usage:
 *   SmartCropImageView iv = findViewById(R.id.iv_pinned_thumb);
 *   SmartCropImageView.loadWithFaceCrop(iv, url); // Glide load + detect + crop
 *
 * Detection runs once per bitmap, off the main thread (ML Kit's own executor),
 * and only ever *shifts the crop window* — it never changes the view's
 * measured size, so it's safe to use anywhere centerCrop is used today.
 */
public class SmartCropImageView extends AppCompatImageView {

    // Focal point as a fraction of the bitmap's width/height (0.5, 0.5 = plain
    // center — identical to default centerCrop behaviour).
    private float focalX = 0.5f;
    private float focalY = 0.5f;
    @Nullable private Bitmap currentBitmap;

    @Nullable private static FaceDetector sDetector;

    public SmartCropImageView(Context context) {
        super(context);
        init();
    }

    public SmartCropImageView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SmartCropImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setScaleType(ScaleType.MATRIX);
    }

    private static FaceDetector detector() {
        if (sDetector == null) {
            FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .build();
            sDetector = FaceDetection.getClient(options);
        }
        return sDetector;
    }

    /**
     * Bind a bitmap with face-aware cropping. Draws with a plain center focal
     * point immediately (zero perceived delay), then re-centers on the
     * detected face(s) once detection finishes, with a quick matrix
     * transition so the shift doesn't look like a jump-cut.
     */
    public void setImageBitmapSmartCrop(@Nullable Bitmap bitmap) {
        currentBitmap = bitmap;
        focalX = 0.5f;
        focalY = 0.5f;
        setImageBitmap(bitmap);
        applyCropMatrix();

        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;

        try {
            InputImage input = InputImage.fromBitmap(bitmap, 0);
            Task<List<Face>> task = detector().process(input);
            task.addOnSuccessListener(faces -> {
                if (faces == null || faces.isEmpty() || currentBitmap != bitmap) return;

                // Union of all detected face boxes — union rather than "biggest
                // face" so a couple/group photo keeps everyone in frame instead
                // of zeroing in on just one person.
                Rect union = null;
                for (Face f : faces) {
                    Rect box = f.getBoundingBox();
                    if (union == null) union = new Rect(box);
                    else union.union(box);
                }
                if (union == null) return;

                float cx = union.centerX() / (float) bitmap.getWidth();
                // Bias slightly above the face box's vertical center — faces
                // read better a little above the exact middle of the crop,
                // leaving room for "headroom" like a real portrait composition.
                float cy = (union.centerY() - union.height() * 0.15f) / (float) bitmap.getHeight();

                focalX = clamp01(cx);
                focalY = clamp01(cy);
                applyCropMatrix();
            });
        } catch (Exception ignored) {
            // Detection is a nice-to-have; any failure just keeps the
            // default center-crop framing set above.
        }
    }

    /**
     * Convenience loader: Glide-decodes {@code url} as a Bitmap (not just a
     * Drawable, since ML Kit needs pixel access) and binds it with face-aware
     * cropping. Safe to call on a recycled/rebound view — each call replaces
     * whatever bitmap/detection was in flight for that view.
     */
    public static void loadWithFaceCrop(SmartCropImageView view, @Nullable String url) {
        if (url == null || url.isEmpty()) {
            view.setImageBitmapSmartCrop(null);
            return;
        }
        com.bumptech.glide.Glide.with(view.getContext())
                .asBitmap()
                .load(url)
                .apply(new com.bumptech.glide.request.RequestOptions()
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL))
                .into(new com.bumptech.glide.request.target.CustomTarget<Bitmap>() {
                    @Override public void onResourceReady(@androidx.annotation.NonNull Bitmap resource,
                            @Nullable com.bumptech.glide.request.transition.Transition<? super Bitmap> transition) {
                        view.setImageBitmapSmartCrop(resource);
                    }
                    @Override public void onLoadCleared(@Nullable android.graphics.drawable.Drawable placeholder) {
                        view.setImageBitmapSmartCrop(null);
                    }
                });
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyCropMatrix();
    }

    /**
     * centerCrop-equivalent Matrix, except the crop window is translated so
     * that (focalX, focalY) — a fraction of the bitmap's own dimensions —
     * lands at the center of the view instead of the bitmap's geometric
     * center. Falls back to identical behaviour to android:scaleType=
     * "centerCrop" when focalX/focalY == 0.5/0.5 (the default).
     */
    private void applyCropMatrix() {
        Bitmap bmp = currentBitmap;
        int viewW = getWidth();
        int viewH = getHeight();
        if (bmp == null || viewW == 0 || viewH == 0) return;

        float scale = Math.max(viewW / (float) bmp.getWidth(), viewH / (float) bmp.getHeight());
        float scaledW = bmp.getWidth() * scale;
        float scaledH = bmp.getHeight() * scale;

        // Where the focal point lands in the scaled (but not yet translated) bitmap
        float focalScaledX = focalX * scaledW;
        float focalScaledY = focalY * scaledH;

        // Translate so the focal point sits at the view's center, then clamp
        // so we never reveal empty space beyond the bitmap's edges.
        float dx = viewW / 2f - focalScaledX;
        float dy = viewH / 2f - focalScaledY;
        dx = Math.min(0f, Math.max(dx, viewW - scaledW));
        dy = Math.min(0f, Math.max(dy, viewH - scaledH));

        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        setImageMatrix(matrix);
    }
}
