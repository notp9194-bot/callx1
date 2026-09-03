package com.callx.app.viewer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Choreographer;
import android.view.View;

/**
 * Instagram-style Story "level stabilizer".
 *
 * As the phone physically rolls left/right in the viewer's hand, the Story
 * media's own on-screen rotation stays fixed (level) — the PHONE's angle
 * changes, the MEDIA's angle does not. This is a counter-rotation: we read
 * the phone's roll and apply the exact opposite rotation to the media View,
 * canceling it out.
 *
 * Architecture (kept separate on purpose):
 *   1. Video playback  -> untouched. Only View.setRotation()/setScaleX/Y are
 *                         touched on the PlayerView/ImageView; the ExoPlayer
 *                         instance itself is never referenced here.
 *   2. Sensor tracking  -> TYPE_ROTATION_VECTOR roll component, low-pass
 *                         filtered + dead-zoned in onSensorChanged().
 *   3. Visual transform -> applied once per vsync via a Choreographer frame
 *                         callback, decoupled from sensor event rate.
 *
 * No allocations in onSensorChanged()/the per-frame callback.
 */
public final class StorySpinViewController implements SensorEventListener {

    /** Small scale-up so the media still fully covers its own frame's
     *  corners while counter-rotated (a rotated rectangle's corners would
     *  otherwise poke outside the original bounds). Purely a View transform. */
    private static final float OVERSCAN_SCALE = 1.08f;
    /** Max roll (degrees) the phone can be tilted before the stabilizer caps
     *  out and lets the media start rotating along with it a little — mirrors
     *  a physical gimbal reaching its own limit rather than fighting forever. */
    private static final float MAX_COUNTER_ROTATION_DEG = 20f;
    /** Below this roll delta (degrees) from level, sensor noise is ignored. */
    private static final float DEAD_ZONE_DEG = 0.5f;
    /** Exponential low-pass factor for the raw sensor angle. */
    private static final float SENSOR_LOWPASS_ALPHA = 0.15f;
    /** Per-frame follow factor toward the filtered target (render-side ease). */
    private static final float RENDER_LERP_FACTOR = 0.22f;
    /** Skip re-applying rotation for imperceptible sub-degree deltas. */
    private static final float MIN_DEGREE_DELTA = 0.05f;

    private final SensorManager sensorManager;
    private final Sensor rotationSensor;

    private View[] targets;
    private boolean active = false;

    // Reused scratch — never allocated in a hot path.
    private final float[] rotationMatrix = new float[9];
    private final float[] orientation = new float[3];

    private float smoothedRollDeg = 0f;
    private boolean referenceCaptured = false;
    // Written on the sensor callback, read once per frame on the render side.
    private volatile float targetCounterRotationDeg = 0f;
    private float currentRotationDeg = 0f;
    private float lastAppliedRotationDeg = Float.NaN;

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

    /** Call once (e.g. from onCreate) with the exact Views that must stay
     *  level — the Story's image/player, never a shared background/blur
     *  layer or anything else on screen. */
    public void attachTargets(View... views) {
        this.targets = views;
        for (View v : views) {
            if (v == null) continue;
            v.setScaleX(OVERSCAN_SCALE);
            v.setScaleY(OVERSCAN_SCALE);
        }
    }

    /** Registers the sensor and starts the per-frame stabilization loop.
     *  Safe to call from onResume(); no-op if already active or unsupported. */
    public void start() {
        if (rotationSensor == null || sensorManager == null || targets == null || active) return;
        active = true;
        referenceCaptured = false;
        currentRotationDeg = 0f;
        targetCounterRotationDeg = 0f;
        lastAppliedRotationDeg = Float.NaN;
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        choreographer.postFrameCallback(frameCallback);
    }

    /** Unregisters the sensor, stops the frame loop, and snaps the target
     *  Views back to level/identity. Safe to call from onPause(), and safe
     *  to call twice. */
    public void stop() {
        if (!active) return;
        active = false;
        sensorManager.unregisterListener(this);
        choreographer.removeFrameCallback(frameCallback);
        if (targets != null) {
            for (View v : targets) {
                if (v == null) continue;
                v.setRotation(0f);
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
        // orientation[2] = roll: how far the phone is tilted sideways
        // (left/right) around its own long axis — exactly the rotation a
        // held phone picks up when you casually turn your wrist.
        float rollDeg = (float) Math.toDegrees(orientation[2]);

        if (!referenceCaptured) {
            smoothedRollDeg = rollDeg;
            referenceCaptured = true;
            return;
        }

        float delta = shortestAngleDelta(smoothedRollDeg, rollDeg);
        smoothedRollDeg += delta * SENSOR_LOWPASS_ALPHA;

        float magnitude = Math.abs(smoothedRollDeg);
        float sign = Math.signum(smoothedRollDeg);
        float effective = magnitude <= DEAD_ZONE_DEG ? 0f : (magnitude - DEAD_ZONE_DEG) * sign;
        float clampedRoll = Math.max(-MAX_COUNTER_ROTATION_DEG, Math.min(MAX_COUNTER_ROTATION_DEG, effective));

        // The counter-rotation is the exact negative of the phone's own
        // roll — this is what keeps the media's angle constant while the
        // phone's angle changes.
        targetCounterRotationDeg = -clampedRoll;
    }

    /** Runs once per vsync while active — decoupled from sensor event rate,
     *  which is what keeps this smooth regardless of how fast
     *  SENSOR_DELAY_GAME events arrive, and never touches playback/layout. */
    private void applyFrame() {
        float target = targetCounterRotationDeg;
        currentRotationDeg += (target - currentRotationDeg) * RENDER_LERP_FACTOR;

        if (Float.isNaN(lastAppliedRotationDeg)
                || Math.abs(currentRotationDeg - lastAppliedRotationDeg) > MIN_DEGREE_DELTA) {
            for (View v : targets) {
                if (v != null) v.setRotation(currentRotationDeg);
            }
            lastAppliedRotationDeg = currentRotationDeg;
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
