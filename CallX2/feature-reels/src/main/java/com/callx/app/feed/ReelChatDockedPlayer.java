package com.callx.app.feed;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.RequiresApi;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.bumptech.glide.Glide;
import com.callx.app.reels.R;

/**
 * ReelChatDockedPlayer — Activity-level mini floating reel player.
 *
 * Advanced features (v3):
 *  1. Drag to reposition      — free-follow drag with rubber-band edge resistance,
 *                                released → SpringAnimation-based bouncy corner snap
 *                                (any of the 4 corners, not a fixed duration tween)
 *  2. Double-tap to expand    — double-tap → scale-pulse + haptic, then surface back
 *                                and switch to Reels tab
 *  3. Long-press preview      — hold → dimmed scrim + 200×350dp enlarged preview,
 *                                release → scrim fades and preview restores
 *  4. Swipe-up next reel      — swipe up ≥ 70dp → haptic + callback triggers next
 *                                reel; guarded against overlapping triggers while a
 *                                switch is already in flight, with a safety-net timeout
 *  5. Picture-in-Picture      — app goes background → Android native PiP wrapper
 *  6. Animated thumbnail      — blurred thumb fades out on first video frame render
 *  7. Mute state persistence  — SharedPreferences remembers mute across sessions
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelChatDockedPlayer {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final float MINI_WIDTH_DP      = 120f;
    private static final float ASPECT_RATIO       = 16f / 9f;   // portrait → height = width * ratio
    private static final float CORNER_RADIUS_DP   = 16f;
    private static final float SWIPE_DOWN_DP      = 80f;
    private static final float SWIPE_UP_DP        = 70f;
    private static final float TAP_SLOP_DP        = 8f;
    private static final float EDGE_MARGIN_DP     = 16f;
    private static final long  MAX_TAP_MS         = 250L;
    private static final long  DOUBLE_TAP_MS      = 350L;
    private static final long  LONG_PRESS_MS      = 480L;
    private static final float LARGE_PREVIEW_W_DP = 200f;
    private static final float LARGE_PREVIEW_H_DP = 350f;

    // SharedPrefs for mute state persistence (Feature 7)
    private static final String PREF_NAME   = "callx_docked_prefs";
    private static final String PREF_MUTED  = "mini_player_muted";

    // ── Callback ───────────────────────────────────────────────────────────

    /** Events dispatched back to the host (MainActivity). */
    public interface Callback {
        /** User dismissed overlay (close / swipe-down). Host should release player. */
        void onDockedPlayerDismissed();
        /** User double-tapped — host should collapse back and switch to Reels tab. */
        void onDockedPlayerExpandRequested();
        /** User swiped up — host should advance to next reel in mini player. */
        void onDockedPlayerNextReel();
    }

    // ── State ──────────────────────────────────────────────────────────────

    private final Activity  activity;
    private       Callback  callback;

    private ViewGroup  container;
    private PlayerView miniPlayerView;
    private ExoPlayer  livePlayer;
    private PlayerView originalFragmentPlayerView;

    private boolean isDismissGesture;
    private boolean isLongPressed;

    // Position tracking for drag-to-reposition (Feature 1)
    private float snapX, snapY;             // last corner-snapped position
    private float startContainerX;          // container.getX() at ACTION_DOWN
    private float startContainerY;          // container.getY() at ACTION_DOWN
    private float touchDownRawX;
    private float touchDownRawY;

    // Double-tap state (Feature 2)
    private long lastTapMs = 0;
    private final Handler doubleTapHandler  = new Handler(Looper.getMainLooper());
    private Runnable singleTapRunnable;

    // Long-press state (Feature 3)
    private final Handler longPressHandler  = new Handler(Looper.getMainLooper());

    // First-frame listener ref so we can remove it (Feature 6)
    private Player.Listener firstFrameListener;

    // Guard against overlapping swipe-up triggers while a reel switch is in flight (Feature 4 v3)
    private boolean isAdvancingReel = false;

    // Dim scrim shown behind the enlarged long-press preview (Feature 3 v3)
    private View previewScrim;

    // Active spring animations for corner snap, so a new drag can cancel them (Feature 1 v3)
    private SpringAnimation springX, springY;

    // ── Constructor ────────────────────────────────────────────────────────

    public ReelChatDockedPlayer(Activity activity) {
        this.activity = activity;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    public boolean isShowing() {
        return container != null && container.getParent() != null;
    }

    /**
     * Show the mini-player overlay and transfer ExoPlayer surface to it.
     *
     * @param player         ExoPlayer currently playing in ReelPlayerFragment.
     * @param fragmentPlayer The PlayerView in the fragment (surface restored here on collapseBack).
     * @param thumbUrl       Thumbnail URL for blurred fallback animation (nullable).
     * @param cb             Event callback.
     */
    public void show(@NonNull ExoPlayer player,
                     @NonNull PlayerView fragmentPlayer,
                     @Nullable String thumbUrl,
                     @NonNull Callback cb) {
        if (isShowing()) return;

        this.livePlayer                = player;
        this.originalFragmentPlayerView = fragmentPlayer;
        this.callback                  = cb;

        // ── Inflate layout ───────────────────────────────────────────────
        LayoutInflater inflater = LayoutInflater.from(activity);
        container = (ViewGroup) inflater.inflate(
                R.layout.layout_reel_chat_docked, null, false);
        miniPlayerView = container.findViewById(R.id.mini_reel_player_view);

        // ── Feature 7: Restore mute state from SharedPrefs ─────────────
        SharedPreferences prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean savedMuted = prefs.getBoolean(PREF_MUTED, false);
        player.setVolume(savedMuted ? 0f : 1f);

        // ── Transfer ExoPlayer surface → mini view ───────────────────────
        miniPlayerView.setPlayer(player);
        miniPlayerView.setUseController(false);
        miniPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

        // ── Corner radius ────────────────────────────────────────────────
        applyCornerRadius(CORNER_RADIUS_DP);

        // ── Add to activity content root (bottom-right default) ──────────
        ViewGroup root = activity.findViewById(android.R.id.content);
        int widthPx   = (int) dpToPx(MINI_WIDTH_DP);
        int heightPx  = (int) (widthPx * ASPECT_RATIO);
        int marginPx  = (int) dpToPx(EDGE_MARGIN_DP);
        int navPx     = getNavBarHeightPx();

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(widthPx, heightPx);
        lp.gravity      = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lp.bottomMargin = marginPx + navPx;
        lp.rightMargin  = marginPx;
        root.addView(container, lp);

        // After layout, capture initial position for drag tracking
        container.post(() -> {
            snapX = container.getX();
            snapY = container.getY();
        });

        // ── Entrance animation: slide up + fade in ───────────────────────
        container.setTranslationY(heightPx + marginPx);
        container.setAlpha(0f);
        container.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();

        // ── Feature 6: Animated thumbnail fallback ───────────────────────
        showThumbnailFallback(thumbUrl);
        listenForFirstFrame();

        // ── Close button ─────────────────────────────────────────────────
        ImageButton btnClose = container.findViewById(R.id.btn_mini_reel_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss(true));
        }

        // ── Touch handler (all gesture features) ─────────────────────────
        container.setOnTouchListener(buildTouchListener());
    }

    // ── Feature 6: Thumbnail fallback helpers ──────────────────────────────

    private void showThumbnailFallback(@Nullable String thumbUrl) {
        ImageView thumbView = container.findViewById(R.id.iv_mini_thumb);
        if (thumbView == null || thumbUrl == null || thumbUrl.isEmpty()) {
            if (thumbView != null) thumbView.setVisibility(View.GONE);
            return;
        }
        thumbView.setAlpha(1f);
        thumbView.setVisibility(View.VISIBLE);
        // Load at tiny resolution → bilinear scale-up creates natural blur (Instagram trick)
        Glide.with(activity)
             .load(thumbUrl)
             .override(12, 21)
             .centerCrop()
             .into(thumbView);
    }

    private void hideThumbnailFallback() {
        if (container == null) return;
        ImageView thumbView = container.findViewById(R.id.iv_mini_thumb);
        if (thumbView == null || thumbView.getVisibility() != View.VISIBLE) return;
        thumbView.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> {
                    if (thumbView != null) thumbView.setVisibility(View.GONE);
                })
                .start();
    }

    private void listenForFirstFrame() {
        if (livePlayer == null) return;
        // Remove any stale listener first
        if (firstFrameListener != null) {
            livePlayer.removeListener(firstFrameListener);
        }
        firstFrameListener = new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                hideThumbnailFallback();
                if (livePlayer != null) livePlayer.removeListener(this);
            }
        };
        livePlayer.addListener(firstFrameListener);
    }

    // ── collapseBack / dismiss ──────────────────────────────────────────────

    /**
     * Transfer ExoPlayer surface BACK to the fragment's PlayerView.
     * Called BEFORE ReelsFragment.onTabResumed() so startPlayback() sees the view.
     */
    public void collapseBack() {
        if (!isShowing()) return;
        cancelAllHandlers();

        if (originalFragmentPlayerView != null && livePlayer != null) {
            originalFragmentPlayerView.setPlayer(livePlayer);
        }
        if (miniPlayerView != null) {
            miniPlayerView.setPlayer(null);
        }
        if (firstFrameListener != null && livePlayer != null) {
            livePlayer.removeListener(firstFrameListener);
            firstFrameListener = null;
        }

        if (container != null) {
            float h = container.getHeight() > 0 ? container.getHeight() : dpToPx(220f);
            container.animate()
                    .translationY(h)
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(this::removeOverlay)
                    .start();
        }
        livePlayer                 = null;
        originalFragmentPlayerView = null;
    }

    /** Permanently dismiss the overlay. */
    public void dismiss(boolean animated) {
        cancelAllHandlers();

        if (firstFrameListener != null && livePlayer != null) {
            livePlayer.removeListener(firstFrameListener);
            firstFrameListener = null;
        }
        if (miniPlayerView != null) {
            miniPlayerView.setPlayer(null);
        }
        livePlayer                 = null;
        originalFragmentPlayerView = null;

        if (container != null && container.getParent() != null) {
            if (animated) {
                float h = container.getHeight() > 0 ? container.getHeight() : dpToPx(220f);
                container.animate()
                        .translationY(h)
                        .alpha(0f)
                        .setDuration(220)
                        .withEndAction(() -> {
                            removeOverlay();
                            if (callback != null) callback.onDockedPlayerDismissed();
                        })
                        .start();
            } else {
                removeOverlay();
                if (callback != null) callback.onDockedPlayerDismissed();
            }
        } else {
            if (callback != null) callback.onDockedPlayerDismissed();
        }
    }

    // ── Feature 4: Update player for next reel ─────────────────────────────

    /**
     * Swap the ExoPlayer surface when the user swipes to the next reel in mini player.
     * Called by ReelsFragment after it advances the ViewPager2 and the new fragment is ready.
     */
    public void updatePlayer(@NonNull ExoPlayer newPlayer,
                             @NonNull PlayerView newFragmentView,
                             @Nullable String thumbUrl) {
        if (!isShowing()) return;

        // Remove old first-frame listener
        if (firstFrameListener != null && livePlayer != null) {
            livePlayer.removeListener(firstFrameListener);
            firstFrameListener = null;
        }

        // Detach old surface
        if (miniPlayerView != null) {
            miniPlayerView.setPlayer(null);
        }

        // Feature 7: persist mute state to new player
        SharedPreferences prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        boolean muted = prefs.getBoolean(PREF_MUTED, false);
        newPlayer.setVolume(muted ? 0f : 1f);

        livePlayer                 = newPlayer;
        originalFragmentPlayerView = newFragmentView;

        if (miniPlayerView != null) {
            miniPlayerView.setPlayer(newPlayer);
        }

        // Feature 4 v3: reel switch has landed — allow the next swipe-up.
        isAdvancingReel = false;

        // Thumbnail + first-frame for new reel
        showThumbnailFallback(thumbUrl);
        listenForFirstFrame();

        // Brief pop animation to indicate reel change
        container.animate()
                .scaleX(0.92f).scaleY(0.92f)
                .setDuration(100)
                .withEndAction(() -> container.animate()
                        .scaleX(1f).scaleY(1f)
                        .setDuration(160)
                        .setInterpolator(new OvershootInterpolator(2f))
                        .start())
                .start();
    }

    // ── Feature 5: Picture-in-Picture ─────────────────────────────────────

    // ── PiP state ─────────────────────────────────────────────────────────
    private boolean inPipMode = false;

    /**
     * Build current PiP params — 9:16 ratio + source rect of the mini player view.
     * Always returns a valid params object; the builder handles missing source rect
     * gracefully on older APIs.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private android.app.PictureInPictureParams buildPipParams() {
        android.app.PictureInPictureParams.Builder b =
                new android.app.PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(9, 16));

        // Source rect hint — drives the entry/exit animation so PiP appears to
        // "fly out of" the mini player widget, not the top-left corner.
        if (container != null && container.getWidth() > 0) {
            int[] loc = new int[2];
            container.getLocationOnScreen(loc);
            b.setSourceRectHint(new Rect(
                    loc[0], loc[1],
                    loc[0] + container.getWidth(),
                    loc[1] + container.getHeight()));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            b.setSeamlessResizeEnabled(true);
            // API 31+: system handles auto-enter PiP on gesture-home nav;
            // setAutoEnterEnabled is set via a separate setPictureInPictureParams()
            // call in enableAutoEnterPip() below.
        }
        return b.build();
    }

    /**
     * Register auto-enter PiP params with the Activity as soon as the mini player
     * is shown. This is the KEY fix for intermittent PiP:
     *
     *   • API 26-30 — params pre-registered so onUserLeaveHint() works reliably.
     *   • API 31+   — setAutoEnterEnabled(true) lets the system enter PiP on
     *                  gesture-home WITHOUT needing onUserLeaveHint() at all.
     *
     * Call this right after show() succeeds.
     */
    public void enableAutoEnterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (container == null || livePlayer == null) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Build auto-enter params (API 31+)
                android.app.PictureInPictureParams.Builder b =
                        new android.app.PictureInPictureParams.Builder()
                                .setAspectRatio(new Rational(9, 16))
                                .setAutoEnterEnabled(true)   // ← handles gesture-nav
                                .setSeamlessResizeEnabled(true);

                if (container.getWidth() > 0) {
                    int[] loc = new int[2];
                    container.getLocationOnScreen(loc);
                    b.setSourceRectHint(new Rect(
                            loc[0], loc[1],
                            loc[0] + container.getWidth(),
                            loc[1] + container.getHeight()));
                }
                activity.setPictureInPictureParams(b.build());
            } else {
                // API 26-30: pre-register params so onUserLeaveHint fires correctly
                activity.setPictureInPictureParams(buildPipParams());
            }
        } catch (Exception ignored) {}
    }

    /**
     * Clear auto-enter PiP when the mini player is dismissed.
     * Prevents PiP from activating after the user closes the mini player.
     */
    public void disableAutoEnterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            android.app.PictureInPictureParams params =
                    new android.app.PictureInPictureParams.Builder()
                            .setAutoEnterEnabled(false)
                            .build();
            activity.setPictureInPictureParams(params);
        } catch (Exception ignored) {}
    }

    /**
     * Imperatively enter PiP — called from onUserLeaveHint() (API 26-30 fallback)
     * and from onStop() as a last-resort fallback on any API.
     *
     * IMPORTANT: expand the mini player BEFORE calling enterPictureInPictureMode()
     * so the system already sees a full-window video surface. Calling expandForPip()
     * AFTER (in onPictureInPictureModeChanged) is too late for a clean animation.
     */
    public void enterPipIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (container == null || livePlayer == null) return;
        if (inPipMode) return; // already in PiP — don't double-enter

        // Expand first so the video surface fills the window before PiP clips it
        expandForPip();

        try {
            activity.enterPictureInPictureMode(buildPipParams());
        } catch (Exception e) {
            // enterPictureInPictureMode failed (e.g. activity not in foreground)
            // Roll back the expansion so mini player stays visible
            restoreFromPip();
        }
    }

    /**
     * Maximize mini player to fill the Activity window.
     * Called BEFORE enterPictureInPictureMode() and again from
     * onPictureInPictureModeChanged(true) as a safety net.
     */
    public void expandForPip() {
        if (container == null) return;
        inPipMode = true;

        container.setLayoutParams(
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        container.setX(0); container.setY(0);
        container.setTranslationX(0); container.setTranslationY(0);
        container.bringToFront();
        container.setClipToOutline(false);

        if (miniPlayerView != null) miniPlayerView.setClipToOutline(false);

        // Hide UI chrome — only raw video visible in PiP overlay
        View btnClose = container.findViewById(R.id.btn_mini_reel_close);
        if (btnClose != null) btnClose.setVisibility(View.GONE);

        View swipeHint = container.findViewById(R.id.ll_swipe_up_hint);
        if (swipeHint != null) swipeHint.setVisibility(View.GONE);
    }

    /**
     * Restore mini player to its corner position after exiting PiP.
     * Safe to call even if PiP was never fully entered.
     */
    public void restoreFromPip() {
        if (container == null) return;
        inPipMode = false;

        int widthPx  = (int) dpToPx(MINI_WIDTH_DP);
        int heightPx = (int) (widthPx * ASPECT_RATIO);
        int marginPx = (int) dpToPx(EDGE_MARGIN_DP);
        int navPx    = getNavBarHeightPx();

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(widthPx, heightPx);
        lp.gravity      = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lp.bottomMargin = marginPx + navPx;
        lp.rightMargin  = marginPx;
        container.setLayoutParams(lp);
        container.setX(0); container.setY(0);
        container.setTranslationX(0); container.setTranslationY(0);

        View btnClose = container.findViewById(R.id.btn_mini_reel_close);
        if (btnClose != null) btnClose.setVisibility(View.VISIBLE);

        View swipeHint = container.findViewById(R.id.ll_swipe_up_hint);
        if (swipeHint != null) swipeHint.setVisibility(View.VISIBLE);

        applyCornerRadius(CORNER_RADIUS_DP);

        // Re-enable auto-enter for the restored mini player position
        enableAutoEnterPip();

        // Re-capture snap position after next layout pass
        container.post(() -> {
            if (container != null) {
                snapX = container.getX();
                snapY = container.getY();
            }
        });
    }

    /** Returns true while the activity is currently in PiP mode. */
    public boolean isInPipMode() { return inPipMode; }

    // ── Corner radius helper ───────────────────────────────────────────────

    private void applyCornerRadius(float radiusDp) {
        if (container == null) return;
        float cornerPx = dpToPx(radiusDp);
        ViewOutlineProvider outline = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                if (view.getWidth() > 0 && view.getHeight() > 0)
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        };
        miniPlayerView.setOutlineProvider(outline);
        miniPlayerView.setClipToOutline(true);
        container.setOutlineProvider(outline);
        container.setClipToOutline(true);
    }

    // ── Touch listener (Features 1, 2, 3, 4) ──────────────────────────────

    private View.OnTouchListener buildTouchListener() {
        return new View.OnTouchListener() {

            private long    touchDownMs;
            private boolean movedBeyondSlop;
            private float   deltaX, deltaY;   // cumulative finger delta

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {

                    case MotionEvent.ACTION_DOWN:
                        // Feature 1 v3: cancel any in-flight spring snap so the new
                        // drag isn't fought by the previous release animation.
                        if (springX != null) springX.cancel();
                        if (springY != null) springY.cancel();
                        container.animate().cancel();

                        touchDownMs      = System.currentTimeMillis();
                        touchDownRawX    = event.getRawX();
                        touchDownRawY    = event.getRawY();
                        startContainerX  = container.getX();
                        startContainerY  = container.getY();
                        movedBeyondSlop  = false;
                        isDismissGesture = false;
                        isLongPressed    = false;
                        deltaX           = 0;
                        deltaY           = 0;

                        // Feature 3: Schedule long-press expand
                        longPressHandler.postDelayed(() -> {
                            if (!movedBeyondSlop) {
                                isLongPressed = true;
                                expandToLargePreview();
                            }
                        }, LONG_PRESS_MS);
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        deltaX = event.getRawX() - touchDownRawX;
                        deltaY = event.getRawY() - touchDownRawY;

                        if (!movedBeyondSlop
                                && (Math.abs(deltaX) > dpToPx(TAP_SLOP_DP)
                                    || Math.abs(deltaY) > dpToPx(TAP_SLOP_DP))) {
                            movedBeyondSlop = true;
                            // Cancel long-press if finger moved
                            longPressHandler.removeCallbacksAndMessages(null);
                        }

                        if (isLongPressed) {
                            // Don't drag while in large-preview mode
                            return true;
                        }

                        if (movedBeyondSlop) {
                            // Feature 1: Drag to reposition — follow finger, with
                            // rubber-band resistance once the container starts
                            // going past the screen edges (v3: feels physical
                            // instead of allowing it to fly fully off-screen).
                            ViewGroup dragRoot = activity.findViewById(android.R.id.content);
                            float rawTargetX = startContainerX + deltaX;
                            float rawTargetY = startContainerY + deltaY;
                            float w = container.getWidth();
                            float h = container.getHeight();
                            float maxX = dragRoot.getWidth()  - w;
                            float maxY = dragRoot.getHeight() - h;

                            container.setX(applyEdgeResistance(rawTargetX, 0f, maxX));
                            container.setY(applyEdgeResistance(rawTargetY, 0f, maxY));

                            if (deltaY > 0) {
                                // Downward drag → dismiss gesture
                                isDismissGesture = true;
                                float alpha = 1f - Math.min(deltaY / dpToPx(120f), 1f);
                                container.setAlpha(Math.max(alpha, 0f));
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        longPressHandler.removeCallbacksAndMessages(null);

                        // Feature 3: Collapse long-press preview on release
                        if (isLongPressed) {
                            isLongPressed = false;
                            collapseFromLargePreview();
                            return true;
                        }

                        long elapsed = System.currentTimeMillis() - touchDownMs;
                        boolean wasTap = !movedBeyondSlop && elapsed < MAX_TAP_MS;

                        if (wasTap) {
                            // Feature 2: Double-tap detection
                            long now = System.currentTimeMillis();
                            if (now - lastTapMs < DOUBLE_TAP_MS) {
                                // Double-tap → expand/jump to Reels
                                doubleTapHandler.removeCallbacksAndMessages(null);
                                lastTapMs = 0;

                                // Feature 2 v3: quick pulse so the tap registers
                                // visually even though the tab switch is instant.
                                container.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                                container.animate().cancel();
                                container.setScaleX(0.9f);
                                container.setScaleY(0.9f);
                                container.animate()
                                        .scaleX(1f).scaleY(1f)
                                        .setDuration(140)
                                        .setInterpolator(new OvershootInterpolator(2f))
                                        .start();

                                if (callback != null) callback.onDockedPlayerExpandRequested();
                            } else {
                                lastTapMs = now;
                                // Delay single-tap action to wait for possible double-tap
                                singleTapRunnable = () -> {
                                    // Single tap → toggle mute
                                    if (livePlayer != null) {
                                        boolean muted = livePlayer.getVolume() <= 0f;
                                        livePlayer.setVolume(muted ? 1f : 0f);
                                        // Feature 7: Persist mute state
                                        activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                                                .edit()
                                                .putBoolean(PREF_MUTED, !muted)
                                                .apply();
                                        showMuteFeedback(muted);
                                    }
                                };
                                doubleTapHandler.postDelayed(singleTapRunnable, DOUBLE_TAP_MS);
                            }

                        } else if (movedBeyondSlop) {
                            // Feature 4: Swipe-up → next reel
                            if (deltaY < -dpToPx(SWIPE_UP_DP)
                                    && Math.abs(deltaX) < dpToPx(50f)
                                    && !isAdvancingReel) {
                                // Feature 4 v3: guard against a second swipe firing
                                // before the in-flight reel switch finishes — the
                                // guard is cleared in updatePlayer()/dismiss()/collapseBack().
                                isAdvancingReel = true;
                                container.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);

                                container.animate()
                                        .translationY(container.getTranslationY() - dpToPx(22f))
                                        .setDuration(80)
                                        .withEndAction(() -> container.animate()
                                                .translationY(0f)
                                                .setDuration(180)
                                                .start())
                                        .start();
                                container.setX(snapX);
                                container.setY(snapY);
                                container.setAlpha(1f);
                                if (callback != null) callback.onDockedPlayerNextReel();

                                // Safety-net: if the host never reaches updatePlayer()
                                // (e.g. this was already the last reel), don't leave
                                // swipe-up permanently blocked.
                                doubleTapHandler.postDelayed(
                                        () -> isAdvancingReel = false, 900L);

                            } else if (isDismissGesture && deltaY > dpToPx(SWIPE_DOWN_DP)) {
                                // Swipe down → dismiss
                                dismiss(true);

                            } else {
                                // Feature 1: Released after drag — snap to nearest corner
                                snapToNearestCorner();
                                container.animate()
                                        .alpha(1f)
                                        .setDuration(150)
                                        .start();
                            }
                        }
                        return true;
                    }
                }
                return false;
            }
        };
    }

    // ── Feature 1: Corner snap ─────────────────────────────────────────────

    private void snapToNearestCorner() {
        ViewGroup root = activity.findViewById(android.R.id.content);
        int rootW = root.getWidth();
        int rootH = root.getHeight();
        int w  = container.getWidth();
        int h  = container.getHeight();
        float m   = dpToPx(EDGE_MARGIN_DP);
        float nav = getNavBarHeightPx();

        float cx = container.getX() + w / 2f;
        float cy = container.getY() + h / 2f;

        // 4 corners: TL, TR, BL, BR
        float[][] corners = {
            {m,              m},
            {rootW - w - m,  m},
            {m,              rootH - h - m - nav},
            {rootW - w - m,  rootH - h - m - nav}
        };

        float minDist = Float.MAX_VALUE;
        float[] best  = corners[3]; // default bottom-right
        for (float[] c : corners) {
            float dx = cx - (c[0] + w / 2f);
            float dy = cy - (c[1] + h / 2f);
            float d  = dx * dx + dy * dy;
            if (d < minDist) { minDist = d; best = c; }
        }

        snapX = best[0];
        snapY = best[1];

        // Feature 1 v3: spring physics instead of a fixed-duration overshoot
        // tween — the snap now reacts to how the view is currently moving and
        // settles with a natural bounce regardless of drag speed.
        container.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);

        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();

        springX = new SpringAnimation(container, DynamicAnimation.X, snapX);
        springX.getSpring()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY);
        springX.start();

        springY = new SpringAnimation(container, DynamicAnimation.Y, snapY);
        springY.getSpring()
                .setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY);
        springY.start();
    }

    // ── Feature 3: Long-press large preview ────────────────────────────────

    private void expandToLargePreview() {
        if (container == null) return;
        container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);

        ViewGroup root = activity.findViewById(android.R.id.content);
        int rootW = root.getWidth();
        int rootH = root.getHeight();

        // Feature 3 v3: dim scrim behind the enlarged preview so it stands
        // out clearly over whatever chat content is behind it. Inserted just
        // below the container so it doesn't intercept the ongoing gesture.
        if (previewScrim == null) {
            previewScrim = new View(activity);
            previewScrim.setBackgroundColor(0x99000000);
            previewScrim.setAlpha(0f);
        }
        if (previewScrim.getParent() == null) {
            int containerIndex = root.indexOfChild(container);
            root.addView(previewScrim, Math.max(containerIndex, 0),
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
        }
        previewScrim.animate().alpha(1f).setDuration(180).start();

        // Scale up to ~200×350dp from current 120×213dp
        float scaleFactor = dpToPx(LARGE_PREVIEW_W_DP) / container.getWidth();
        float targetX = (rootW - container.getWidth()) / 2f;
        float targetY = (rootH - container.getHeight()) / 2f;

        container.animate()
                .scaleX(scaleFactor).scaleY(scaleFactor)
                .x(targetX).y(targetY)
                .alpha(1f)
                .setDuration(220)
                .setInterpolator(new OvershootInterpolator(1.0f))
                .start();
    }

    private void collapseFromLargePreview() {
        if (container == null) return;
        container.animate()
                .scaleX(1f).scaleY(1f)
                .x(snapX).y(snapY)
                .setDuration(220)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();

        if (previewScrim != null && previewScrim.getParent() != null) {
            previewScrim.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction(() -> {
                        ViewGroup root = activity.findViewById(android.R.id.content);
                        if (root != null && previewScrim != null) root.removeView(previewScrim);
                    })
                    .start();
        }
    }

    // ── Mute feedback ──────────────────────────────────────────────────────

    private void showMuteFeedback(boolean wasMuted) {
        ImageView icon = container.findViewById(R.id.iv_mini_mute_indicator);
        if (icon == null) return;
        icon.setImageResource(wasMuted ? R.drawable.ic_volume_on : R.drawable.ic_volume_off);
        icon.animate().cancel();
        icon.setAlpha(0f);
        icon.setScaleX(0.6f); icon.setScaleY(0.6f);
        icon.setVisibility(View.VISIBLE);
        icon.animate()
                .alpha(0.9f).scaleX(1f).scaleY(1f)
                .setDuration(120)
                .withEndAction(() -> icon.animate()
                        .alpha(0f).scaleX(0.85f).scaleY(0.85f)
                        .setStartDelay(500).setDuration(200)
                        .withEndAction(() -> icon.setVisibility(View.GONE))
                        .start())
                .start();
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void cancelAllHandlers() {
        longPressHandler.removeCallbacksAndMessages(null);
        doubleTapHandler.removeCallbacksAndMessages(null);
        if (springX != null) springX.cancel();
        if (springY != null) springY.cancel();
        isAdvancingReel = false;
    }

    private void removeOverlay() {
        if (container == null) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root != null) {
            root.removeView(container);
            if (previewScrim != null && previewScrim.getParent() == root) {
                root.removeView(previewScrim);
            }
        }
        previewScrim   = null;
        container      = null;
        miniPlayerView = null;
    }

    /**
     * Rubber-band resistance (v3): inside [min, max] the value passes through
     * unchanged; beyond either bound it's damped by 1/3 so dragging the mini
     * player toward/past a screen edge feels elastic instead of hard-clipped
     * or freely flying off-screen.
     */
    private static float applyEdgeResistance(float target, float min, float max) {
        if (target < min) {
            return min - (min - target) * 0.33f;
        }
        if (target > max) {
            return max + (target - max) * 0.33f;
        }
        return target;
    }

    private float dpToPx(float dp) {
        return dp * activity.getResources().getDisplayMetrics().density;
    }

    private int getNavBarHeightPx() {
        View decorView = activity.getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.view.WindowInsets insets = decorView.getRootWindowInsets();
            if (insets != null) {
                android.graphics.Insets nav = insets.getInsets(
                        android.view.WindowInsets.Type.navigationBars());
                return nav.bottom;
            }
        }
        int resId = activity.getResources().getIdentifier(
                "navigation_bar_height", "dimen", "android");
        return resId > 0
                ? activity.getResources().getDimensionPixelSize(resId)
                : (int) dpToPx(48f);
    }
}
