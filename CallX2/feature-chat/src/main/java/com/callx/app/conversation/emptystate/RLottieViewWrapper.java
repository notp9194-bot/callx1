package com.callx.app.conversation.emptystate;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.aghajari.rlottie.AXrLottieDrawable;
import com.aghajari.rlottie.AXrLottieImageView;

import java.io.File;

/**
 * Adapter over com.aghajari.rlottie (core/libs/rlottie.aar). API below was
 * verified directly against that aar's bytecode (AXrLottieImageView /
 * AXrLottieDrawable, com.aghajari.rlottie package) - not guessed.
 *
 * If you ever swap in a different rlottie aar with different class/method
 * names, this is the ONLY file in the empty-chat feature that needs editing.
 *
 * Note: AXrLottieImageView has no pauseAnimation()/resumeAnimation() - only
 * playAnimation()/stopAnimation()/isPlaying(). stopAnimation() halts the
 * animator without resetting the drawable's current frame, so
 * playAnimation() again after that resumes from where it left off - that's
 * what pause()/resume() below use.
 */
public class RLottieViewWrapper extends FrameLayout {

    private static final String TAG = "RLottieViewWrapper";

    private final AXrLottieImageView lottieView;
    private boolean released = false;

    public RLottieViewWrapper(Context ctx) {
        this(ctx, null);
    }

    public RLottieViewWrapper(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        lottieView = new AXrLottieImageView(ctx);
        lottieView.setAutoRepeat(true);
        addView(lottieView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Load + play a lottie JSON straight from a cached file (server-downloaded pack). */
    public void loadFromFile(File jsonFile) {
        if (released || jsonFile == null || !jsonFile.exists()) return;
        try {
            AXrLottieDrawable drawable = AXrLottieDrawable.fromFile(jsonFile).build();
            lottieView.setLottieDrawable(drawable);
            lottieView.playAnimation();
        } catch (Throwable e) {
            // Caller (EmptyChatLottieController) already has the static emoji
            // TextView showing underneath, so a bad file just means no
            // animation instead of a crash.
            Log.w(TAG, "loadFromFile failed: " + e.getMessage());
        }
    }

    /** Load + play a lottie JSON bundled in assets/ (used for the default emoji). */
    public void loadFromAsset(String assetPath) {
        if (released) return;
        try {
            AXrLottieDrawable drawable = AXrLottieDrawable.fromAssets(getContext(), assetPath).build();
            lottieView.setLottieDrawable(drawable);
            lottieView.playAnimation();
        } catch (Throwable e) {
            Log.w(TAG, "loadFromAsset failed: " + e.getMessage());
        }
    }

    public void pause() {
        if (!released && lottieView.isPlaying()) lottieView.stopAnimation();
    }

    public void resume() {
        if (!released && !lottieView.isPlaying() && lottieView.getLottieDrawable() != null) {
            lottieView.playAnimation();
        }
    }

    /** Must be called from the host Activity's onDestroy() - releases native memory. */
    public void release() {
        if (released) return;
        released = true;
        lottieView.release();
    }
}
