package com.callx.app.viewer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;
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
 *   2. Sensor tracking  -> TYPE_GAME_ROTATION_VECTOR (gyro+accelerometer
 *                         fusion, no magnetometer — see constructor) roll
 *                         component, low-pass filtered + dead-zoned in
 *                         onSensorChanged().
 *   3. Visual transform -> applied once per vsync via a Choreographer frame
 *                         callback, decoupled from sensor event rate.
 *
 * No allocations in onSensorChanged()/the per-frame callback.
 */
public final class StorySpinViewController implements SensorEventListener {

    /** Scale-up so the media still fully covers its own frame's corners at
     *  ANY counter-rotation angle. A rotated rectangle's own corners sweep
     *  outside its unrotated bounds by up to sqrt(2)x at a 45° rotation —
     *  the worst case for a full 360° range (previously this only needed to
     *  cover a small +/-20° tilt, hence the old, much smaller 1.08 value).
     *  Purely a View transform, no extra draw cost. */
    private static final float OVERSCAN_SCALE = 1.42f;
    /** Below this roll delta (degrees) from level, sensor noise is ignored. */
    private static final float DEAD_ZONE_DEG = 0.5f;
    /** Exponential low-pass factor for the raw sensor angle. */
    private static final float SENSOR_LOWPASS_ALPHA = 0.15f;
    /** Per-frame follow factor toward the filtered target (render-side ease)
     *  — this is now the SLOW-rotation end of a dynamic range (most
     *  responsive). See {@link #RENDER_LERP_FACTOR_FAST} and
     *  computeVelocityAwareLerp(). */
    private static final float RENDER_LERP_FACTOR = 0.22f;
    /** Follow factor used when the phone is rolling quickly — lower value =
     *  more damping/lag, which is what smooths out the jerk of a fast flick
     *  instead of snapping the media through a big angle in one frame. */
    private static final float RENDER_LERP_FACTOR_FAST = 0.08f;
    /** Angular velocity (deg/sec) at/above which the FAST (most-damped)
     *  factor is fully applied. Below this, lerp scales smoothly between
     *  the fast and slow factors. */
    private static final float FAST_ROTATION_DEG_PER_SEC = 90f;
    /** Skip re-applying rotation for imperceptible sub-degree deltas. */
    private static final float MIN_DEGREE_DELTA = 0.05f;
    /** Once currentRotationDeg has settled within this many degrees of a
     *  resting target for STILL_FRAMES_BEFORE_PAUSE consecutive frames, the
     *  per-vsync Choreographer loop is suspended instead of ticking forever
     *  at zero — phone-is-flat-on-a-table is the common case, and there is
     *  no reason to keep waking up 60x/sec to reapply an unchanged angle. */
    private static final float SETTLED_DEG = 0.05f;
    /** Consecutive settled frames required before pausing the frame loop. */
    private static final int STILL_FRAMES_BEFORE_PAUSE = 6;

    private final SensorManager sensorManager;
    private final Sensor rotationSensor;
    private final Context appContext;

    private View[] targets;
    private boolean active = false;

    // Reused scratch — never allocated in a hot path.
    private final float[] rotationMatrix = new float[9];

    private float smoothedRollDeg = 0f;
    private boolean referenceCaptured = false;
    // Written on the sensor callback, read once per frame on the render side.
    private volatile float targetCounterRotationDeg = 0f;
    // Current angular speed of the phone's roll (deg/sec), also written by
    // the sensor callback and read once per frame — drives how much damping
    // applyFrame() uses this frame.
    private volatile float currentAngularVelocityDegPerSec = 0f;
    private long lastEventTimestampNanos = 0L;
    private float currentRotationDeg = 0f;
    private float lastAppliedRotationDeg = Float.NaN;
    // How many consecutive applyFrame() calls have landed within SETTLED_DEG
    // of their target. Reset to 0 the instant a new sensor sample moves the
    // target; once it reaches STILL_FRAMES_BEFORE_PAUSE the frame loop stops
    // scheduling itself and waits for onSensorChanged() to wake it back up.
    private int settledFrameCount = 0;
    // True while the Choreographer loop is intentionally parked because the
    // phone has been level for a while. onSensorChanged() flips this back
    // off and re-posts a frame the moment real motion resumes.
    private boolean frameLoopParked = false;

    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!active) return;
            applyFrame();
            if (!frameLoopParked) {
                choreographer.postFrameCallback(this);
            }
        }
    };

    public StorySpinViewController(Context ctx) {
        appContext = ctx.getApplicationContext();
        sensorManager = (SensorManager) appContext.getSystemService(Context.SENSOR_SERVICE);
        // TYPE_GAME_ROTATION_VECTOR fuses gyroscope + accelerometer only —
        // no magnetometer. We only need roll relative to the device itself,
        // never true compass heading, so dropping the magnetometer removes
        // its biggest real-world drift source (magnetic interference from
        // metal, speakers, phone cases with magnets) without losing anything
        // this feature uses. Falls back to TYPE_ROTATION_VECTOR (which adds
        // the magnetometer back in) on the rare device that lacks it.
        Sensor gameRotation = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) : null;
        rotationSensor = gameRotation != null ? gameRotation
                : (sensorManager != null
                    ? sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) : null);
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
            // Rotation/scale here is a pure compositor transform (no content
            // redraw needed frame to frame). Promoting to a hardware layer
            // means each setRotation() just re-composites an already-cached
            // GPU layer instead of re-recording/redrawing the whole view
            // (and, for a PlayerView, potentially its child SurfaceTexture
            // path) on every vsync tick.
            v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
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
        currentAngularVelocityDegPerSec = 0f;
        lastEventTimestampNanos = 0L;
        lastAppliedRotationDeg = Float.NaN;
        settledFrameCount = 0;
        frameLoopParked = false;
        sensorManager.registerListener(this, rotationSensor, batteryAwareSensorDelay());
        choreographer.postFrameCallback(frameCallback);
    }

    /** SENSOR_DELAY_GAME normally (smooth ~20ms cadence); drops to the much
     *  lighter SENSOR_DELAY_NORMAL (~200ms) when the system's Battery Saver
     *  is on, trading stabilizer smoothness for meaningfully less sensor
     *  wakeups/CPU while the user has explicitly asked to save power. */
    private int batteryAwareSensorDelay() {
        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        boolean lowBattery = pm != null && pm.isPowerSaveMode();
        return lowBattery ? SensorManager.SENSOR_DELAY_NORMAL : SensorManager.SENSOR_DELAY_GAME;
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
                // Drop the hardware layer once we're done animating it —
                // holding one costs a GPU-backed buffer per target for as
                // long as it's set, so only keep it around while the
                // stabilizer is actually active.
                v.setLayerType(View.LAYER_TYPE_NONE, null);
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!active) return;
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        // We only ever need roll (rotation around the device's own long
        // axis) — not azimuth or pitch. SensorManager.getOrientation()
        // computes all three (atan2 + asin + atan2) and writes them into a
        // scratch array we'd only read index [2] from. Inlining just the
        // roll term — atan2(-R[6], R[8]), the same formula getOrientation()
        // uses internally for orientation[2] — gives the identical angle
        // for two fewer trig calls and no array write, on every single
        // sensor sample (this fires at up to ~50Hz under SENSOR_DELAY_GAME).
        float rollRad = (float) Math.atan2(-rotationMatrix[6], rotationMatrix[8]);
        float rollDeg = (float) Math.toDegrees(rollRad);

        if (!referenceCaptured) {
            smoothedRollDeg = rollDeg;
            referenceCaptured = true;
            lastEventTimestampNanos = event.timestamp;
            return;
        }

        // Angular velocity from consecutive sensor timestamps (nanos, as
        // SensorEvent already provides — no System.nanoTime() call needed).
        // Used only to pick how much damping applyFrame() should use this
        // frame; never feeds back into the angle itself.
        if (lastEventTimestampNanos != 0L) {
            float dtSec = (event.timestamp - lastEventTimestampNanos) / 1_000_000_000f;
            if (dtSec > 0f) {
                float rawDelta = shortestAngleDelta(smoothedRollDeg, rollDeg);
                currentAngularVelocityDegPerSec = Math.abs(rawDelta) / dtSec;
            }
        }
        lastEventTimestampNanos = event.timestamp;

        float delta = shortestAngleDelta(smoothedRollDeg, rollDeg);
        smoothedRollDeg += delta * SENSOR_LOWPASS_ALPHA;

        float magnitude = Math.abs(smoothedRollDeg);
        float sign = Math.signum(smoothedRollDeg);
        // No upper clamp — the stabilizer now follows the phone's roll all
        // the way through a full 360°, not just a small +/-20° window. The
        // physical roll angle itself is naturally bounded to (-180, 180]
        // by atan2 in the sensor read above, so "effective" already can't
        // exceed that on its own; shortestAngleDelta()/applyFrame() below
        // handle the wrap at +/-180 so a continuous phone spin produces a
        // continuous counter-rotation instead of a snap back to 0.
        float effective = magnitude <= DEAD_ZONE_DEG ? 0f : (magnitude - DEAD_ZONE_DEG) * sign;

        // The counter-rotation is the exact negative of the phone's own
        // roll — this is what keeps the media's angle constant while the
        // phone's angle changes.
        targetCounterRotationDeg = -effective;

        // Only wake a parked frame loop for a target that actually moved
        // meaningfully away from where the view currently sits — otherwise
        // ordinary sensor noise while the phone is resting flat would keep
        // re-arming the loop and defeat the whole point of parking it.
        // shortestAngleDelta() so a wake check near the +/-180 seam doesn't
        // see a false ~360° "jump".
        if (frameLoopParked
                && Math.abs(shortestAngleDelta(currentRotationDeg, targetCounterRotationDeg)) > MIN_DEGREE_DELTA) {
            frameLoopParked = false;
            settledFrameCount = 0;
            choreographer.postFrameCallback(frameCallback);
        }
    }

    /** Runs once per vsync while active — decoupled from sensor event rate,
     *  which is what keeps this smooth regardless of how fast
     *  SENSOR_DELAY_GAME events arrive, and never touches playback/layout. */
    private void applyFrame() {
        float target = targetCounterRotationDeg;
        float lerp = velocityAwareLerp(currentAngularVelocityDegPerSec);
        // Shortest-path delta, not a plain subtraction: with the +/-20° cap
        // removed, target and currentRotationDeg can now sit on opposite
        // sides of the +/-180° seam (e.g. target=179°, current=-179° is
        // really only a 2° gap). A naive `target - currentRotationDeg`
        // would lerp the long way around (358°) instead of the short way,
        // which is exactly the kind of jump a continuous 360° spin must
        // never produce.
        float step = shortestAngleDelta(currentRotationDeg, target);
        currentRotationDeg += step * lerp;
        // Keep the running angle normalized to (-180, 180] so it doesn't
        // grow without bound over a long viewing session of repeated spins
        // — setRotation() treats e.g. 720° and 0° identically on screen,
        // but an ever-growing float here would eventually lose precision.
        currentRotationDeg = normalizeDeg(currentRotationDeg);

        if (Float.isNaN(lastAppliedRotationDeg)
                || Math.abs(shortestAngleDelta(lastAppliedRotationDeg, currentRotationDeg)) > MIN_DEGREE_DELTA) {
            for (View v : targets) {
                if (v != null) v.setRotation(currentRotationDeg);
            }
            lastAppliedRotationDeg = currentRotationDeg;
        }

        // Track how long we've been sitting essentially on-target. Once
        // that holds for STILL_FRAMES_BEFORE_PAUSE frames in a row, park the
        // Choreographer loop — the doFrame() callback checks frameLoopParked
        // right after this call and skips re-posting itself.
        if (Math.abs(shortestAngleDelta(currentRotationDeg, target)) <= SETTLED_DEG) {
            settledFrameCount++;
            if (settledFrameCount >= STILL_FRAMES_BEFORE_PAUSE) {
                frameLoopParked = true;
            }
        } else {
            settledFrameCount = 0;
        }
    }

    /** Maps the phone's current roll speed to a follow-factor: fast rotation
     *  → more damping (RENDER_LERP_FACTOR_FAST, less jerk); slow/still →
     *  full responsiveness (RENDER_LERP_FACTOR). Linear ramp in between, no
     *  branching table, no allocation. */
    private static float velocityAwareLerp(float angularVelocityDegPerSec) {
        float t = Math.min(1f, angularVelocityDegPerSec / FAST_ROTATION_DEG_PER_SEC);
        return RENDER_LERP_FACTOR + (RENDER_LERP_FACTOR_FAST - RENDER_LERP_FACTOR) * t;
    }

    private static float shortestAngleDelta(float fromDeg, float toDeg) {
        float d = toDeg - fromDeg;
        while (d > 180f) d -= 360f;
        while (d < -180f) d += 360f;
        return d;
    }

    /** Wraps any angle into (-180, 180]. Used to keep currentRotationDeg
     *  bounded during a continuous multi-turn spin instead of accumulating
     *  without limit — the on-screen result is identical either way, since
     *  View.setRotation(deg) and View.setRotation(deg +/- 360) render the
     *  same frame. */
    private static float normalizeDeg(float deg) {
        float d = deg % 360f;
        if (d > 180f) d -= 360f;
        else if (d <= -180f) d += 360f;
        return d;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { /* no-op */ }
}
