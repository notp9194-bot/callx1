package com.callx.app.feed;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.callx.app.models.ReelModel;

import java.util.ArrayList;
import java.util.List;

/**
 * ReelPhotoBeatSyncController ── Beat-Sync Auto-Advance v6
 * ════════════════════════════════════════════════════════════
 *
 * Controls music beat–driven auto-advance for photo slideshows.
 *
 * Two modes:
 *   1. TAP TEMPO  — user taps a button N times; controller computes average BPM,
 *                   stores it in the ReelModel, and drives the slideshow.
 *   2. STORED BPM — if ReelModel.beatIntervalMs > 0 the stored value is used directly.
 *
 * Integration:
 *   • Call startSync(reel, viewPager2) in ReelPlayerFragment when beat-sync is on.
 *   • Call stopSync() in onPause / when slideshow ends.
 *   • For tap-tempo during editing, call onBeatTap() on each user tap,
 *     then commit() to finalise the BPM.
 *
 * Visual metronome:
 *   • Attach a View via setMetronomeIndicator() — it will flash on each beat.
 *
 * BPM constraints:
 *   • Min: 60 BPM (1000 ms interval) — slower than this is not useful
 *   • Max: 200 BPM (300 ms interval) — faster than this is jarring
 *   • Default: 120 BPM (500 ms interval)
 */
public class ReelPhotoBeatSyncController {

    // ── Listener ──────────────────────────────────────────────────────────────

    public interface BeatListener {
        /** Called on every beat tick — advance to next photo here. */
        void onBeat(int beatNumber);
        /** Called when tap-tempo finishes and BPM is committed. */
        void onBpmCommitted(float bpm, int intervalMs);
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final float  MIN_BPM          = 60f;
    private static final float  MAX_BPM          = 200f;
    private static final float  DEFAULT_BPM      = 120f;
    private static final int    TAP_TIMEOUT_MS   = 2500;  // tap sequence resets after this idle
    private static final int    MIN_TAP_COUNT    = 3;     // need at least 3 taps for reliable BPM

    // ── State ─────────────────────────────────────────────────────────────────

    private float            currentBpm       = DEFAULT_BPM;
    private int              currentIntervalMs;
    private boolean          isSyncing        = false;
    private int              beatCount        = 0;

    // Tap tempo
    private final List<Long> tapTimestamps    = new ArrayList<>();
    private final Handler    tapTimeoutHandler = new Handler(Looper.getMainLooper());
    private Runnable         tapTimeoutRunnable;

    // Beat timer
    private final Handler    beatHandler      = new Handler(Looper.getMainLooper());
    private Runnable         beatRunnable;

    // Visual metronome
    @Nullable private View   metronomeView;
    @Nullable private ValueAnimator metronomeAnim;

    @Nullable private BeatListener listener;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ReelPhotoBeatSyncController() {
        currentIntervalMs = bpmToIntervalMs(DEFAULT_BPM);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setBeatListener(@Nullable BeatListener l) { this.listener = l; }

    /** Attach a view that will flash on each beat (e.g. a dot or progress segment). */
    public void setMetronomeIndicator(@Nullable View v) { this.metronomeView = v; }

    /**
     * Start beat-sync using the interval stored in the reel model (or default 120 BPM).
     */
    public void startSync(@NonNull ReelModel reel) {
        stopSync();
        int interval = reel.beatIntervalMs > 0 ? reel.beatIntervalMs
                     : bpmToIntervalMs(DEFAULT_BPM);
        currentIntervalMs = clampInterval(interval);
        currentBpm        = intervalMsToBpm(currentIntervalMs);
        isSyncing         = true;
        beatCount         = 0;
        scheduleNextBeat();
    }

    /**
     * Start beat-sync with an explicit BPM (overrides model value).
     */
    public void startSync(float bpm) {
        stopSync();
        currentBpm        = clampBpm(bpm);
        currentIntervalMs = bpmToIntervalMs(currentBpm);
        isSyncing         = true;
        beatCount         = 0;
        scheduleNextBeat();
    }

    /** Pause beat delivery (e.g. slideshow long-press pause). */
    public void pauseSync() {
        isSyncing = false;
        beatHandler.removeCallbacks(beatRunnable);
    }

    /** Resume after pauseSync(). */
    public void resumeSync() {
        if (!isSyncing) {
            isSyncing = true;
            scheduleNextBeat();
        }
    }

    /** Stop beat-sync entirely and release resources. */
    public void stopSync() {
        isSyncing = false;
        beatHandler.removeCallbacks(beatRunnable);
        if (metronomeAnim != null) { metronomeAnim.cancel(); metronomeAnim = null; }
    }

    public boolean isSyncing() { return isSyncing; }
    public float   getBpm()    { return currentBpm; }
    public int     getIntervalMs() { return currentIntervalMs; }

    // ── Tap Tempo ─────────────────────────────────────────────────────────────

    /**
     * Record a tap for tap-tempo BPM detection.
     * Call this each time the user taps the beat button.
     * After MIN_TAP_COUNT taps the running average BPM is computed live.
     */
    public void onBeatTap() {
        long now = System.currentTimeMillis();
        // Reset if too long since last tap
        if (!tapTimestamps.isEmpty()
                && now - tapTimestamps.get(tapTimestamps.size() - 1) > TAP_TIMEOUT_MS) {
            tapTimestamps.clear();
        }
        tapTimestamps.add(now);

        // Schedule auto-commit timeout
        tapTimeoutHandler.removeCallbacks(tapTimeoutRunnable);
        tapTimeoutRunnable = this::commitTapTempo;
        tapTimeoutHandler.postDelayed(tapTimeoutRunnable, TAP_TIMEOUT_MS);

        // Live BPM preview (at least 2 taps needed for an interval)
        if (tapTimestamps.size() >= 2) {
            float liveBpm = computeBpmFromTaps();
            currentBpm        = liveBpm;
            currentIntervalMs = bpmToIntervalMs(liveBpm);
        }
    }

    /**
     * Commit the current tap-tempo result into the given ReelModel and notify listener.
     * Call explicitly, or it auto-fires after TAP_TIMEOUT_MS idle.
     */
    public void commit(@NonNull ReelModel reel) {
        tapTimeoutHandler.removeCallbacks(tapTimeoutRunnable);
        if (tapTimestamps.size() < MIN_TAP_COUNT) return;
        float bpm = computeBpmFromTaps();
        bpm = clampBpm(bpm);
        int interval = bpmToIntervalMs(bpm);
        currentBpm        = bpm;
        currentIntervalMs = interval;
        reel.beatIntervalMs = interval;
        reel.musicBpm       = bpm;
        reel.photoBeatSync  = true;
        tapTimestamps.clear();
        if (listener != null) listener.onBpmCommitted(bpm, interval);
    }

    /** Resets tap-tempo without committing. */
    public void resetTaps() {
        tapTimestamps.clear();
        tapTimeoutHandler.removeCallbacks(tapTimeoutRunnable);
    }

    /** Number of taps recorded so far. */
    public int getTapCount() { return tapTimestamps.size(); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void scheduleNextBeat() {
        if (!isSyncing) return;
        beatRunnable = () -> {
            if (!isSyncing) return;
            beatCount++;
            if (listener != null) listener.onBeat(beatCount);
            flashMetronome();
            scheduleNextBeat();
        };
        beatHandler.postDelayed(beatRunnable, currentIntervalMs);
    }

    private void commitTapTempo() {
        if (tapTimestamps.size() < MIN_TAP_COUNT) return;
        float bpm = clampBpm(computeBpmFromTaps());
        currentBpm        = bpm;
        currentIntervalMs = bpmToIntervalMs(bpm);
        tapTimestamps.clear();
        if (listener != null) listener.onBpmCommitted(bpm, currentIntervalMs);
    }

    private float computeBpmFromTaps() {
        if (tapTimestamps.size() < 2) return DEFAULT_BPM;
        int n = tapTimestamps.size();
        long totalInterval = tapTimestamps.get(n - 1) - tapTimestamps.get(0);
        float avgIntervalMs = totalInterval / (float)(n - 1);
        return 60_000f / avgIntervalMs;
    }

    private void flashMetronome() {
        if (metronomeView == null) return;
        if (metronomeAnim != null) metronomeAnim.cancel();
        metronomeAnim = ValueAnimator.ofFloat(1f, 0.3f, 1f);
        metronomeAnim.setDuration(200);
        metronomeAnim.setInterpolator(new AccelerateDecelerateInterpolator());
        metronomeAnim.addUpdateListener(a -> {
            if (metronomeView != null) metronomeView.setAlpha((float) a.getAnimatedValue());
        });
        metronomeAnim.start();
    }

    // ── Static helpers ────────────────────────────────────────────────────────

    public static int  bpmToIntervalMs(float bpm)  { return Math.round(60_000f / clampBpm(bpm)); }
    public static float intervalMsToBpm(int ms)    { return 60_000f / Math.max(1, ms); }
    public static float clampBpm(float bpm)        { return Math.max(MIN_BPM, Math.min(MAX_BPM, bpm)); }
    public static int  clampInterval(int ms)       { return bpmToIntervalMs(intervalMsToBpm(ms)); }

    /** User-facing display string: "120 BPM" */
    public static String formatBpm(float bpm) { return Math.round(bpm) + " BPM"; }
}
