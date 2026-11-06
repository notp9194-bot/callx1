package com.callx.app.reaction;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * FloatingReactionOverlayView — Instagram-style "story/reel got reactions"
 * replay animation: a burst of emoji rise from the bottom of the screen,
 * drifting sideways and fading out near the top, spawning continuously for
 * a short window then stopping on their own (see screenshots 2-8: opening
 * a reel/story that received emoji reactions replays this burst once).
 *
 * Self-contained, reusable — used by both StatusViewerActivity
 * (feature-status) and ReelPlayerFragment/ReelSocialController
 * (feature-reels) so it lives in `core`. Plain FrameLayout host for
 * lightweight TextView particles (no custom Canvas particle system needed —
 * particle counts are small and short-lived, so View-based animation is
 * simpler and cheap enough here).
 *
 * Usage:
 *   overlay.playBurst("🔥");                 // single emoji, repeated
 *   overlay.playBurst(Arrays.asList("👏","🔥")); // mixed emoji, randomized
 *   overlay.stop();                          // cancel early (e.g. onPause)
 */
public class FloatingReactionOverlayView extends FrameLayout {

    /** Total wall-clock time the burst spawns new particles for. */
    private static final long SPAWN_WINDOW_MS = 3200L;
    /** Gap between two consecutive particle spawns — cut to a third (was
     *  260L) to roughly triple how many emoji appear per burst. */
    private static final long SPAWN_INTERVAL_MS = 87L;
    /** How long one particle takes to rise + fade from bottom to top. */
    private static final long PARTICLE_RISE_MS = 2600L;
    /** Fraction of PARTICLE_RISE_MS spent ramping alpha 0→1 at the start —
     *  kept short so particles reach full opacity almost immediately
     *  instead of looking dim while they rise (see spawnParticle()). */
    private static final float FADE_IN_FRACTION = 0.12f;
    /** Fraction of PARTICLE_RISE_MS spent ramping alpha 1→0 at the end. */
    private static final float FADE_OUT_FRACTION = 0.30f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<String> pendingEmojis = new ArrayList<>();

    private boolean running = false;
    private long spawnStartedAt = 0L;
    private int emojiCursor = 0;

    private final Runnable spawnTick = this::spawnTick;

    // Optional launch point (overlay-local px): the first particles leave from here instead of the bottom edge
    // (used so a tapped emoji "flies out" of the grid cell the user pressed).
    private float originX = -1f, originY = -1f;
    private int originSpawns = 0;
    private static final int ORIGIN_SPAWN_COUNT = 9;

    public FloatingReactionOverlayView(Context ctx) { super(ctx); init(); }
    public FloatingReactionOverlayView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }
    public FloatingReactionOverlayView(Context ctx, AttributeSet attrs, int defStyle) { super(ctx, attrs, defStyle); init(); }

    private void init() {
        setClipChildren(false);
        setClipToPadding(false);
        // Never intercepts touches — it's a pure visual overlay sitting on
        // top of the status/reel content; taps must still reach whatever's
        // beneath it (play/pause, swipe, etc).
        setClickable(false);
        setFocusable(false);
    }

    @Override
    public boolean onInterceptTouchEvent(android.view.MotionEvent ev) { return false; }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent ev) { return false; }

    /** Convenience overload for a single reaction emoji. */
    public void playBurst(String emoji) {
        if (emoji == null || emoji.isEmpty()) return;
        List<String> single = new ArrayList<>(1);
        single.add(emoji);
        playBurst(single);
    }

    /**
     * Starts (or restarts) a timed burst that spawns particles drawn from
     * `emojis` (cycled/randomized) for SPAWN_WINDOW_MS, then stops on its
     * own — matching Instagram's one-shot replay rather than an endless
     * loop. Safe to call again while already running (restarts cleanly).
     */
    public void playBurst(List<String> emojis) {
        if (emojis == null || emojis.isEmpty()) return;
        stop();
        pendingEmojis.clear();
        pendingEmojis.addAll(emojis);
        emojiCursor = 0;
        running = true;
        spawnStartedAt = System.currentTimeMillis();
        setVisibility(View.VISIBLE);
        handler.post(spawnTick);
    }

    /** Burst whose first particles rise from (cx, cy) in this view's own coordinates, then continues as usual. */
    public void playBurstFrom(String emoji, float cx, float cy) {
        if (emoji == null || emoji.isEmpty()) return;
        playBurst(emoji);                 // resets origin via stop()
        originX = cx; originY = cy; originSpawns = 0;
    }

    /** Stops spawning new particles and clears any in-flight ones immediately. */
    public void stop() {
        originX = -1f; originY = -1f; originSpawns = 0;
        running = false;
        handler.removeCallbacks(spawnTick);
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View child = getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof android.animation.ValueAnimator) {
                ((android.animation.ValueAnimator) tag).cancel();
            }
        }
        removeAllViews();
        setVisibility(View.GONE);
    }

    private void spawnTick() {
        if (!running) return;
        long elapsed = System.currentTimeMillis() - spawnStartedAt;
        if (elapsed >= SPAWN_WINDOW_MS || getWidth() <= 0) {
            running = false;
            // Let already-rising particles finish their own animation and
            // self-remove; just stop spawning new ones.
            return;
        }
        spawnParticle();
        handler.postDelayed(spawnTick, SPAWN_INTERVAL_MS);
    }

    private void spawnParticle() {
        String emoji = pendingEmojis.get(emojiCursor % pendingEmojis.size());
        emojiCursor++;

        TextView particle = new TextView(getContext());
        particle.setText(emoji);
        float sizeSp = 26f + random.nextInt(14); // 26sp–39sp, Instagram-like size jitter
        particle.setTextSize(sizeSp);
        int size = dp(48);
        LayoutParams lp = new LayoutParams(size, size);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        int width = getWidth();
        int startX = width > size ? random.nextInt(width - size) : 0;
        final boolean fromOrigin = originX >= 0f && originSpawns < ORIGIN_SPAWN_COUNT && getHeight() > 0;
        float riseFromOrigin = 0f;
        if (fromOrigin) {
            originSpawns++;
            startX = Math.max(0, Math.min(width - size, Math.round(originX - size / 2f) + random.nextInt(dp(40)) - dp(20)));
            lp.bottomMargin = Math.round(getHeight() - originY - size / 2f);
            riseFromOrigin = originY + size;
        } else {
            lp.bottomMargin = -size; // start just below the visible bottom edge
        }
        lp.leftMargin = startX;
        particle.setLayoutParams(lp);
        particle.setAlpha(0f);
        float startRotation = random.nextInt(21) - 10; // -10°..+10°
        particle.setRotation(startRotation);
        addView(particle);

        // Horizontal drift: a gentle sideways sway, randomized left or right.
        float driftX = (random.nextBoolean() ? 1f : -1f) * (dp(18) + random.nextInt(dp(28)));
        float riseDistance = fromOrigin ? riseFromOrigin : (getHeight() > 0 ? getHeight() + size : dp(600));
        float endRotation = startRotation + (random.nextBoolean() ? 18f : -18f);

        // A single ValueAnimator drives translation/rotation/alpha together
        // for the particle's whole lifetime. Previously this was two
        // separate ViewPropertyAnimator calls (one for rise+fade-in, a
        // second queued via postDelayed for fade-out) — starting the
        // second animate() call mid-flight silently cancelled/froze the
        // first one's translationY/rotation, so particles would stop
        // rising partway up the screen (looked like they vanished around
        // the middle of the screen) while also spending most of their
        // visible life at a low, linearly-ramping alpha (looked dim).
        // Driving everything from one animator's onUpdate fixes both.
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(PARTICLE_RISE_MS);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            particle.setTranslationY(-riseDistance * t);
            particle.setTranslationX(driftX * t);
            particle.setRotation(startRotation + (endRotation - startRotation) * t);

            float alpha;
            if (t < FADE_IN_FRACTION) {
                alpha = t / FADE_IN_FRACTION; // quick ramp to full opacity
            } else if (t > 1f - FADE_OUT_FRACTION) {
                alpha = (1f - t) / FADE_OUT_FRACTION; // ease out near the top
            } else {
                alpha = 1f; // fully opaque for the bulk of the rise
            }
            particle.setAlpha(Math.max(0f, Math.min(1f, alpha)));
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                removeViewSafely(particle);
            }
        });
        particle.setTag(anim);
        anim.start();
    }

    private void removeViewSafely(View v) {
        if (v.getParent() == this) removeView(v);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }
}
