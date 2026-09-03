package com.callx.app.viewer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Choreographer;
import android.view.View;

/**
 * Instagram-style "Spin View" for Story media.
 *
 * Pans the Story's video/image content horizontally as the phone physically
 * rotates left/right, WITHOUT touching video playback, layout, or anything
 * else on screen — see StatusViewerActivity#onResume()/onPause() for wiring.
 *
 * Architecture (kept deliberately separate, per the three systems this
 * feature spans):
 *   1. Video playback   -> untouched. This class never references the
 *                          ExoPlayer instance, only the PlayerView's own
 *                          View properties (translationX/scaleX/scaleY),
 *                          which the GPU composites independently of decode.
 *   2. Sensor tracking   -> TYPE_ROTATION_VECTOR, SENSOR_DELAY_GAME, with a
 *                          low-pass filter + dead-zone (onSensorChanged).
 *   3. Visual transform  -> a Choreographer frame callback reads the latest
 *                          sensor-derived target once per vsync and eases
 *                          the View's translationX toward it. Sensor events
 *                          arrive on a separate cadence from vsync, so
 *                          decoupling them here is what keeps this at 60fps
 *                          regardless of sensor jitter/rate.
 *
 * No allocations happen in onSensorChanged() or the per-frame callback —
 * all scratch arrays/fields are pre-allocated.
 */
public final class StorySpinViewController implements SensorEventListener {

    /** View scale applied to the targets so there's overscan headroom to pan
     *  into without ever revealing an edge. Purely a View transform — no
     *  extra decode, no bigger bitmap/video source needed. */
    private static final float OVERSCAN_SCALE = 1.14f;
    /** Of the overscan headroom on one side, how much of it panning may use. */
    private static final float MAX_PAN_FRACTION = 0.9f;
    /** Relative device yaw (degrees, since the Story opened) that maps to
     *  the maximum pan in that direction. */
    private static final float MAX_YAW_DEG = 18f;
    /** Sensor noise smaller than this (degrees, from center) is ignored. */
    private static final float DEAD_ZONE_DEG = 0.6f;
    /** Exponential low-pass factor for the raw sensor angle. Lower = smoother
     *  but slightly more lag; this is what removes gyroscope hand jitter. */
    private static final float SENSOR_LOWPASS_ALPHA = 0.12f;
    /** Per-frame critically-damped follow factor toward the filtered target,
     *  applied at render time — the second half of the smoothing. */
    private static final float RENDER_LERP_FACTOR = 0.18f;
    /** Skip re-applying translationX for sub-pixel, imperceptible deltas —
     *  keeps a perfectly still phone doing zero work per frame. */
    private static final float MIN_PIXEL_DELTA = 0.4f;

    private final SensorManager sensorManager;
    private final Sensor rotationSensor;

    private View[] targets;
    private boolean active = false;
    private boolean referenceCaptured = false;

    // Reused scratch — never allocated in a hot path.
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    private float referenceYawDeg = 0f;
    private float smoothedYawDeg = 0f;
    // Written on the sensor callback, read once per frame on the UI/render
    // thread. A single float write/read is effectively atomic in practice
    // here (bounded -1..1, worst case one stale frame — not worth a lock).
    private volatile float targetPanFraction = 0f;
    private float currentPanFraction = 0f;
    private float maxPanPx = 0f;
    private float lastAppliedTranslationX = Float.NaN;

    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!active) return;
            applyFrame();
            choreographer.postFrameCallback(this);
        }
    };

    public StorySpinViewController(Context ctx) {
        sensorManager = (SensorManager) ctx.getApplicationContext()
                .getSystemService(Context.SENSOR_SERVICE);
        rotationSensor = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null;
    }

    /** Call once (e.g. from onCreate, after the views exist) with the exact
     *  Views that should visually pan — for a Story that's the image/player,
     *  never a background/blur layer or any container the rest of the UI
     *  shares. Applies the overscan scale immediately; actual pixel range is
     *  computed lazily in start() once the views have a real width. */
    public void attachTargets(View... views) {
        this.targets = views;
        for (View v : views) {
            if (v == null) continue;
            v.setScaleX(OVERSCAN_SCALE);
            v.setScaleY(OVERSCAN_SCALE);
        }
    }

    /** Registers the sensor and starts the per-frame pan loop. No-op if
     *  already active, if the device has no rotation sensor, or before
     *  attachTargets() has been called. Safe to call from onResume(). */
    public void start() {
        if (rotationSensor == null || sensorManager == null || targets == null || active) return;
        active = true;
        referenceCaptured = false;
        currentPanFraction = 0f;
        targetPanFraction = 0f;
        lastAppliedTranslationX = Float.NaN;
        for (View v : targets) {
            if (v != null && v.getWidth() > 0) {
                maxPanPx = (v.getWidth() * (OVERSCAN_SCALE - 1f) / 2f) * MAX_PAN_FRACTION;
                break;
            }
        }
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        choreographer.postFrameCallback(frameCallback);
    }

    /** Unregisters the sensor, stops the frame loop, and snaps the target
     *  Views back to identity (centered, unscaled) so the Story looks normal
     *  the instant this stops — e.g. the moment it's no longer visible.
     *  Safe to call from onPause()/onStop(), and safe to call twice. */
    public void stop() {
        if (!active) return;
        active = false;
        sensorManager.unregisterListener(this);
        choreographer.removeFrameCallback(frameCallback);
        if (targets != null) {
            for (View v : targets) {
                if (v == null) continue;
                v.setTranslationX(0f);
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!active) return;
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        float yawDeg = (float) Math.toDegrees(orientation[0]);

        if (!referenceCaptured) {
            // First reading becomes "center" — the Story pans relative to
            // however the phone happened to be held when it opened, not to
            // absolute compass heading.
            referenceYawDeg = yawDeg;
            smoothedYawDeg = yawDeg;
            referenceCaptured = true;
            return;
        }

        // Low-pass filter first — this is the primary jitter removal.
        float delta = shortestAngleDelta(smoothedYawDeg, yawDeg);
        smoothedYawDeg += delta * SENSOR_LOWPASS_ALPHA;

        float relativeYaw = shortestAngleDelta(referenceYawDeg, smoothedYawDeg);

        // Dead-zone — tiny hand tremor near center produces zero pan.
        float magnitude = Math.abs(relativeYaw);
        float sign = Math.signum(relativeYaw);
        float effective = magnitude <= DEAD_ZONE_DEG ? 0f : (magnitude - DEAD_ZONE_DEG) * sign;

        float clamped = Math.max(-MAX_YAW_DEG, Math.min(MAX_YAW_DEG, effective));
        targetPanFraction = clamped / MAX_YAW_DEG;
    }

    /** Runs once per vsync while active. This — not the sensor rate — is
     *  what determines the render cadence, so playback/scrolling elsewhere
     *  is never affected by how fast SENSOR_DELAY_GAME events arrive. */
    private void applyFrame() {
        float target = targetPanFraction;
        currentPanFraction += (target - currentPanFraction) * RENDER_LERP_FACTOR;
        float translationX = currentPanFraction * maxPanPx;

        if (Float.isNaN(lastAppliedTranslationX)
                || Math.abs(translationX - lastAppliedTranslationX) > MIN_PIXEL_DELTA) {
            for (View v : targets) {
                if (v != null) v.setTranslationX(translationX);
            }
            lastAppliedTranslationX = translationX;
        }
    }

    private static float shortestAngleDelta(float fromDeg, float toDeg) {
        float d = toDeg - fromDeg;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }
}
