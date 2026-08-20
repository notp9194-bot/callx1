package com.callx.app.social;

import android.annotation.SuppressLint;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.view.PreviewView;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * FaceTrackingHelper — Real-time face-tracking auto-crop for the duet
 * reaction-bubble layout (LAYOUT_REACTION_BUBBLE).
 *
 * Wraps ML Kit's on-device FaceDetector behind a CameraX ImageAnalysis use
 * case (FAST performance mode, no classification/landmarks needed — just the
 * bounding box). Reports a smoothed face-center in NDC space (-1..1) on every
 * frame so DuetReelActivity can move the draggable bubble to "auto-follow"
 * the subject's face, instead of requiring the user to hold still.
 *
 * Also records a (timestamp, ndcX, ndcY) track while recording is active —
 * this track is later handed to DuetVideoCompositor so the baked output
 * follows the same face path frame-by-frame, not just a single static point.
 *
 * Usage:
 *   faceTracker = new FaceTrackingHelper(analysisExecutor);
 *   ImageAnalysis analysis = faceTracker.buildAnalysisUseCase();
 *   // bind `analysis` into the CameraX use-case group alongside preview/videoCapture
 *   faceTracker.setListener(ndc -> runOnUiThread(() -> moveBubbleTo(ndc[0], ndc[1])));
 *   faceTracker.setRecording(true);  // start logging the track when recording begins
 *   ...
 *   long[] t = faceTracker.getTrackTimesMs();
 *   float[] x = faceTracker.getTrackX();
 *   float[] y = faceTracker.getTrackY();
 */
public class FaceTrackingHelper {

    private static final String TAG = "FaceTrackingHelper";

    /** Smoothing factor — higher = snappier, lower = smoother (0..1). */
    private static final float SMOOTHING = 0.35f;

    public interface Listener {
        /** ndc = {x, y} in -1..1 NDC space, already smoothed. */
        void onFaceMoved(float[] ndc);
    }

    private final FaceDetector detector;
    private final Executor     analysisExecutor;
    private Listener listener;

    private float smoothedX = 0f, smoothedY = 0f;
    private boolean hasSmoothed = false;

    // ── Recording-time track capture ────────────────────────────────────────
    private volatile boolean recording = false;
    private long recordStartMs = 0L;
    private final java.util.ArrayList<Long>  trackT = new java.util.ArrayList<>();
    private final java.util.ArrayList<Float> trackX = new java.util.ArrayList<>();
    private final java.util.ArrayList<Float> trackY = new java.util.ArrayList<>();

    public FaceTrackingHelper(Executor analysisExecutor) {
        this.analysisExecutor = analysisExecutor;
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.15f)
                .enableTracking()
                .build();
        this.detector = FaceDetection.getClient(options);
    }

    public void setListener(Listener l) { this.listener = l; }

    /** Call when the user toggles "Auto-follow face" on/off. */
    public void setRecording(boolean isRecording) {
        this.recording = isRecording;
        if (isRecording) {
            recordStartMs = System.currentTimeMillis();
            trackT.clear(); trackX.clear(); trackY.clear();
        }
    }

    public boolean isRecording() { return recording; }

    public ImageAnalysis buildAnalysisUseCase() {
        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyzeFrame);
        return analysis;
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeFrame(@NonNull ImageProxy proxy) {
        if (proxy.getImage() == null) { proxy.close(); return; }

        InputImage image = InputImage.fromMediaImage(
                proxy.getImage(), proxy.getImageInfo().getRotationDegrees());

        Task<List<Face>> task = detector.process(image);
        task.addOnSuccessListener(faces -> {
            if (!faces.isEmpty()) {
                Face largest = pickLargest(faces);
                float ndcX = boxCenterNdcX(largest, image.getWidth());
                float ndcY = boxCenterNdcY(largest, image.getHeight());
                emit(ndcX, ndcY);
            }
        }).addOnFailureListener(e -> Log.w(TAG, "face detect failed: " + e.getMessage()))
          .addOnCompleteListener(t -> proxy.close());
    }

    private Face pickLargest(List<Face> faces) {
        Face best = faces.get(0);
        int bestArea = best.getBoundingBox().width() * best.getBoundingBox().height();
        for (Face f : faces) {
            int area = f.getBoundingBox().width() * f.getBoundingBox().height();
            if (area > bestArea) { best = f; bestArea = area; }
        }
        return best;
    }

    private float boxCenterNdcX(Face f, int imgWidth) {
        float cx = f.getBoundingBox().centerX();
        return (cx / imgWidth) * 2f - 1f;
    }

    private float boxCenterNdcY(Face f, int imgHeight) {
        float cy = f.getBoundingBox().centerY();
        // Flip so NDC +Y is up, matching DuetVideoCompositor's convention.
        return -((cy / imgHeight) * 2f - 1f);
    }

    private void emit(float rawX, float rawY) {
        if (!hasSmoothed) {
            smoothedX = rawX; smoothedY = rawY; hasSmoothed = true;
        } else {
            smoothedX += (rawX - smoothedX) * SMOOTHING;
            smoothedY += (rawY - smoothedY) * SMOOTHING;
        }

        if (recording) {
            long elapsed = System.currentTimeMillis() - recordStartMs;
            trackT.add(elapsed);
            trackX.add(smoothedX);
            trackY.add(smoothedY);
        }

        if (listener != null) listener.onFaceMoved(new float[]{smoothedX, smoothedY});
    }

    // ── Track accessors (call after stopRecording, before compositing) ──────
    public long[] getTrackTimesMs() {
        long[] a = new long[trackT.size()];
        for (int i = 0; i < a.length; i++) a[i] = trackT.get(i);
        return a;
    }

    public float[] getTrackX() {
        float[] a = new float[trackX.size()];
        for (int i = 0; i < a.length; i++) a[i] = trackX.get(i);
        return a;
    }

    public float[] getTrackY() {
        float[] a = new float[trackY.size()];
        for (int i = 0; i < a.length; i++) a[i] = trackY.get(i);
        return a;
    }

    public boolean hasTrack() { return !trackT.isEmpty(); }

    public void release() {
        try { detector.close(); } catch (Exception ignored) {}
    }
}
