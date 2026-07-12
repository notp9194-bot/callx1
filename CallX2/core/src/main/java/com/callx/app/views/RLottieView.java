package com.callx.app.views;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.aghajari.rlottie.AXrLottieDrawable;
import com.aghajari.rlottie.AXrLottieImageView;

/**
 * Reusable RLottie playback widget, living in :core so any feature module
 * (not just :feature-chat) can play a bundled Lottie animation. core/build.gradle
 * exposes rlottie.aar as an `api` dependency for exactly this reason, and
 * CallxApp already calls AXrLottie.init() once at app startup, so this
 * widget just needs to load + play — no extra init required.
 *
 * Twin of feature-chat's RLottieViewWrapper (same com.aghajari.rlottie API,
 * verified against the aar's bytecode there) — kept as a separate class
 * instead of promoting that one to :core so :feature-chat's empty-chat
 * animation code doesn't need to change.
 */
public class RLottieView extends FrameLayout {

    private static final String TAG = "RLottieView";

    private final AXrLottieImageView lottieView;
    private boolean released = false;

    public RLottieView(Context ctx) {
        this(ctx, null);
    }

    public RLottieView(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        lottieView = new AXrLottieImageView(ctx);
        addView(lottieView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Load a lottie JSON bundled in assets/ and play it once (autoRepeat off —
     *  callers that want a burst/one-shot effect, like a like-button heart,
     *  want exactly one play per trigger, not a loop).
     *  @return true if the drawable loaded and started playing. */
    public boolean playFromAsset(String assetPath) {
        if (released) return false;
        try {
            AXrLottieDrawable drawable = AXrLottieDrawable.fromAssets(getContext(), assetPath).build();
            lottieView.setAutoRepeat(false);
            lottieView.setLottieDrawable(drawable);
            lottieView.playAnimation();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "playFromAsset failed: " + e.getMessage());
            return false;
        }
    }

    /** Load a lottie JSON bundled in assets/ and loop it continuously. */
    public boolean loadFromAssetLooping(String assetPath) {
        if (released) return false;
        try {
            AXrLottieDrawable drawable = AXrLottieDrawable.fromAssets(getContext(), assetPath).build();
            lottieView.setAutoRepeat(true);
            lottieView.setLottieDrawable(drawable);
            lottieView.playAnimation();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "loadFromAssetLooping failed: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (!released && lottieView.isPlaying()) lottieView.stopAnimation();
    }

    /** Must be called when the host view is torn down — releases native memory. */
    public void release() {
        if (released) return;
        released = true;
        lottieView.release();
    }
}
