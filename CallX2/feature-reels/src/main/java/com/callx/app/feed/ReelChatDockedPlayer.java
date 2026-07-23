package com.callx.app.feed;

import android.app.Activity;
import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.callx.app.reels.R;

/**
 * ReelChatDockedPlayer — Activity-level mini floating reel player.
 *
 * When the user switches from Reels tab to Chat tab (or any tab), the currently
 * playing ExoPlayer's surface is transferred to a compact mini overlay pinned
 * to the bottom-right corner of the screen. Playback continues uninterrupted.
 *
 * Surface transfer works by calling {@code miniPlayerView.setPlayer(player)},
 * which internally detaches the renderer from the old PlayerView and reattaches
 * it to the new one — no new ExoPlayer is created.
 *
 * Behaviour:
 *  • Appears bottom-right corner, 9:16 aspect ratio (120 × 213 dp)
 *  • Same 16dp corner-radius as comments-sheet docked video
 *  • Tap          → toggle mute on the live ExoPlayer
 *  • Swipe down   → dismiss (player released via callback)
 *  • Close (×)    → dismiss
 *  • Returning to Reels → surface transferred back to fragment's PlayerView
 *
 * Lifecycle contract:
 *  - show()         called when Reels → Chat switch detected
 *  - collapseBack() called BEFORE ReelsFragment.onTabResumed() so the surface
 *                   is back on the fragment's PlayerView before startPlayback().
 *  - dismiss()      called by user closing the overlay; triggers callback so
 *                   the host can release the player properly.
 */
@OptIn(markerClass = UnstableApi.class)
public class ReelChatDockedPlayer {

    // ── Constants ──────────────────────────────────────────────────────────
    private static final float MINI_WIDTH_DP    = 120f;
    private static final float ASPECT_RATIO     = 16f / 9f;   // portrait 9:16 → height/width
    private static final float CORNER_RADIUS_DP = 16f;
    private static final float SWIPE_DISMISS_DP = 80f;
    private static final float TAP_SLOP_DP      = 8f;
    private static final long  MAX_TAP_MS       = 250L;

    // ── Callback ───────────────────────────────────────────────────────────

    /** Events dispatched back to MainActivity. */
    public interface Callback {
        /** User dismissed the overlay (close button or swipe). Host should release player. */
        void onDockedPlayerDismissed();
    }

    // ── State ──────────────────────────────────────────────────────────────

    private final Activity  activity;
    private       Callback  callback;

    /** Root FrameLayout added to android.R.id.content. */
    private ViewGroup  container;
    /** The small PlayerView inside the overlay. */
    private PlayerView miniPlayerView;

    /**
     * The ExoPlayer borrowed from ReelPlayerFragment.
     * NOT released here unless the overlay is dismissed permanently.
     * collapseBack() just moves the surface — release is done by the fragment.
     */
    private ExoPlayer  livePlayer;

    /**
     * The fragment's original PlayerView — restored when collapsing back.
     */
    private PlayerView originalFragmentPlayerView;

    /** Whether we are in the middle of a swipe-to-dismiss gesture. */
    private boolean    isDismissGesture;

    // ── Constructor ────────────────────────────────────────────────────────

    public ReelChatDockedPlayer(Activity activity) {
        this.activity = activity;
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /** True when the overlay is attached to the window and visible. */
    public boolean isShowing() {
        return container != null && container.getParent() != null;
    }

    /**
     * Show the mini-player overlay and transfer the ExoPlayer surface to it.
     *
     * @param player         ExoPlayer currently playing in ReelPlayerFragment — NOT released here.
     * @param fragmentPlayer The PlayerView in the fragment we'll restore the surface to.
     * @param cb             Event callback.
     */
    public void show(@NonNull ExoPlayer player,
                     @NonNull PlayerView fragmentPlayer,
                     @NonNull Callback cb) {
        if (isShowing()) return;

        this.livePlayer               = player;
        this.originalFragmentPlayerView = fragmentPlayer;
        this.callback                 = cb;

        // ── Inflate overlay layout ───────────────────────────────────────
        LayoutInflater inflater = LayoutInflater.from(activity);
        container = (ViewGroup) inflater.inflate(
                R.layout.layout_reel_chat_docked, null, false);
        miniPlayerView = container.findViewById(R.id.mini_reel_player_view);

        // ── Transfer ExoPlayer surface → mini view ────────────────────────
        // ExoPlayer internally clears the old surface and renders to the new
        // one. Playback continues at the exact same position without a hiccup.
        miniPlayerView.setPlayer(player);
        miniPlayerView.setUseController(false);
        miniPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);

        // ── Corner radius ─────────────────────────────────────────────────
        float cornerPx = dpToPx(CORNER_RADIUS_DP);
        ViewOutlineProvider roundedOutline = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                if (view.getWidth() > 0 && view.getHeight() > 0)
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        };
        miniPlayerView.setOutlineProvider(roundedOutline);
        miniPlayerView.setClipToOutline(true);
        container.setOutlineProvider(roundedOutline);
        container.setClipToOutline(true);

        // ── Add to activity content root ──────────────────────────────────
        ViewGroup root = activity.findViewById(android.R.id.content);
        int widthPx   = (int) dpToPx(MINI_WIDTH_DP);
        int heightPx  = (int) (widthPx * ASPECT_RATIO);
        int marginPx  = (int) dpToPx(16f);
        int navPx     = getNavBarHeightPx();

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(widthPx, heightPx);
        lp.gravity     = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        lp.bottomMargin = marginPx + navPx;
        lp.rightMargin  = marginPx;
        root.addView(container, lp);

        // ── Entrance: slide up + fade in ──────────────────────────────────
        container.setTranslationY(heightPx + marginPx);
        container.setAlpha(0f);
        container.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(320)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                .start();

        // ── Close button ──────────────────────────────────────────────────
        ImageButton btnClose = container.findViewById(R.id.btn_mini_reel_close);
        if (btnClose != null) {
            btnClose.setOnClickListener(v -> dismiss(true));
        }

        // ── Touch handler: tap = mute toggle, swipe-down = dismiss ────────
        container.setOnTouchListener(buildTouchListener());
    }

    /**
     * Transfer the ExoPlayer surface BACK to the fragment's original PlayerView.
     * Call this BEFORE calling ReelsFragment.onTabResumed() so startPlayback()
     * finds the player already attached to the correct view.
     */
    public void collapseBack() {
        if (!isShowing()) return;

        // Move surface back to original PlayerView — playback continues
        if (originalFragmentPlayerView != null && livePlayer != null) {
            originalFragmentPlayerView.setPlayer(livePlayer);
        }
        if (miniPlayerView != null) {
            miniPlayerView.setPlayer(null);
        }

        // Exit animation → remove overlay
        if (container != null) {
            int h = container.getHeight();
            container.animate()
                    .translationY(h > 0 ? h : (int) dpToPx(220f))
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(this::removeOverlay)
                    .start();
        }

        // Clear refs — ownership returns to the fragment
        livePlayer                = null;
        originalFragmentPlayerView = null;
    }

    /**
     * Permanently dismiss the overlay. The ExoPlayer surface is detached; the
     * host is responsible for releasing it via the callback.
     *
     * @param animated Whether to animate the exit.
     */
    public void dismiss(boolean animated) {
        if (miniPlayerView != null) {
            miniPlayerView.setPlayer(null);
        }

        ExoPlayer playerRef = livePlayer;   // capture before clearing
        livePlayer                = null;
        originalFragmentPlayerView = null;

        if (container != null && container.getParent() != null) {
            if (animated) {
                int h = container.getHeight();
                container.animate()
                        .translationY(h > 0 ? h : (int) dpToPx(220f))
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

    // ── Touch listener ─────────────────────────────────────────────────────

    private View.OnTouchListener buildTouchListener() {
        return new View.OnTouchListener() {
            private long  touchDownMs;
            private float startRawY;
            private boolean movedBeyondSlop;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchDownMs     = System.currentTimeMillis();
                        startRawY       = event.getRawY();
                        movedBeyondSlop = false;
                        isDismissGesture = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dy = event.getRawY() - startRawY;
                        if (!movedBeyondSlop && Math.abs(dy) > dpToPx(TAP_SLOP_DP)) {
                            movedBeyondSlop = true;
                        }
                        if (movedBeyondSlop && dy > 0) {
                            isDismissGesture = true;
                            // Follow finger (damped)
                            container.setTranslationY(dy * 0.75f);
                            float alpha = 1f - Math.min(dy / dpToPx(120f), 1f);
                            container.setAlpha(Math.max(alpha, 0f));
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float finalDy   = event.getRawY() - startRawY;
                        long  elapsed   = System.currentTimeMillis() - touchDownMs;
                        boolean wasTap  = !movedBeyondSlop && elapsed < MAX_TAP_MS;

                        if (wasTap) {
                            // Short tap → toggle mute
                            if (livePlayer != null) {
                                boolean muted = livePlayer.getVolume() <= 0f;
                                livePlayer.setVolume(muted ? 1f : 0f);
                                // Brief visual feedback
                                showMuteFeedback(muted);
                            }
                        } else if (isDismissGesture && finalDy > dpToPx(SWIPE_DISMISS_DP)) {
                            dismiss(true);
                        } else {
                            // Snap back
                            container.animate()
                                    .translationY(0f)
                                    .alpha(1f)
                                    .setDuration(200)
                                    .start();
                            isDismissGesture = false;
                        }
                        return true;
                    }
                }
                return false;
            }
        };
    }

    // ── Mute icon feedback ─────────────────────────────────────────────────

    private void showMuteFeedback(boolean wasMuted) {
        // Show a brief mute/unmute icon overlay on the mini-player
        View icon = container.findViewById(R.id.iv_mini_mute_indicator);
        if (icon == null) return;

        // Set icon based on NEW state (opposite of wasMuted):
        //   wasMuted=true  → player was muted, now unmuted → show volume_on
        //   wasMuted=false → player was playing, now muted  → show volume_off
        if (icon instanceof ImageView) {
            ((ImageView) icon).setImageResource(
                    wasMuted
                            ? R.drawable.ic_volume_on
                            : R.drawable.ic_volume_off);
        }

        icon.animate().cancel();
        icon.setAlpha(0f);
        icon.setScaleX(0.6f);
        icon.setScaleY(0.6f);
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

    private void removeOverlay() {
        if (container == null) return;
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root != null) root.removeView(container);
        container      = null;
        miniPlayerView = null;
    }

    private float dpToPx(float dp) {
        return dp * activity.getResources().getDisplayMetrics().density;
    }

    private int getNavBarHeightPx() {
        android.view.View decorView = activity.getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsets insets = decorView.getRootWindowInsets();
            if (insets != null) {
                android.graphics.Insets nav = insets.getInsets(
                        android.view.WindowInsets.Type.navigationBars());
                return nav.bottom;
            }
        }
        // Fallback: read from resources
        int resId = activity.getResources().getIdentifier(
                "navigation_bar_height", "dimen", "android");
        return resId > 0 ? activity.getResources().getDimensionPixelSize(resId)
                         : (int) dpToPx(48f);
    }
}
