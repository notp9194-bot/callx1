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
    private String lastError = null;

    /** Debug-only: the exception from the most recent failed load(), so it
     *  can be shown on-screen (Toast) when the developer has no logcat
     *  access, e.g. building from a mobile CI. Null if the last load
     *  succeeded or none was attempted yet. */
    @Nullable
    public String getLastError() {
        return lastError;
    }

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

    /** Load + play a lottie JSON straight from a cached file (server-downloaded pack).
     *  @return true if the drawable loaded and started playing, false otherwise —
     *  caller must NOT hide the fallback emoji on a false return, or nothing shows. */
    public boolean loadFromFile(File jsonFile) {
        if (released || jsonFile == null || !jsonFile.exists()) return false;
        try {
            AXrLottieDrawable drawable = AXrLottieDrawable.fromFile(jsonFile).build();
            lottieView.setLottieDrawable(drawable);
            lottieView.playAnimation();
            lastError = null;
            return true;
        } catch (Throwable e) {
            // Caller (EmptyChatLottieController) already has the static emoji
            // TextView showing underneath, so a bad file just means no
            // animation instead of a crash.
            lastError = Log.getStackTraceString(e);
            Log.w(TAG, "loadFromFile failed: " + e.getMessage());
            return false;
        }
    }

    /** Load + play a lottie JSON bundled in assets/ (used for the default emoji).
     *  @return true if the drawable loaded and started playing, false otherwise —
     *  caller must NOT hide the fallback emoji on a false return, or nothing shows. */
    public boolean loadFromAsset(String assetPath) {
        if (released) return false;
        try {
            AXrLottieDrawable drawable = AXrLottieDrawable.fromAssets(getContext(), assetPath).build();
            lottieView.setLottieDrawable(drawable);
            lottieView.playAnimation();
            lastError = null;
            return true;
        } catch (Throwable e) {
            lastError = Log.getStackTraceString(e);
            Log.w(TAG, "loadFromAsset failed: " + e.getMessage());
            return false;
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
