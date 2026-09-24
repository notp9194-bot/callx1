package com.callx.app.chat.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

/**
 * The glass backdrop source (wallpaper + skeleton + message list). Behaves exactly
 * like a plain FrameLayout, plus it reports every descendant invalidation to
 * {@link GlassBackdrop}.
 *
 * Why: anything that changes pixels behind the glass without scrolling or relayout
 * (animated / async-loading items, download spinners, audio waveform, a wallpaper that
 * finishes loading) has to invalidate itself. Listening for that lets the glass re-record
 * exactly when something actually changed, instead of polling on a timer.
 */
public final class GlassSourceLayout extends FrameLayout {

    interface Listener {
        /** {@code target} is the view that actually invalidated (may be deep inside a row). */
        void onDescendantChanged(View target);
    }

    private Listener listener;

    public GlassSourceLayout(Context context) {
        super(context);
    }

    public GlassSourceLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GlassSourceLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    void setListener(Listener l) {
        listener = l;
    }

    // API 28+: every descendant invalidation is routed through here. (Glass blur itself is
    // API 31+, so older devices never need this callback.)
    @RequiresApi(28)
    @Override
    public void onDescendantInvalidated(@NonNull View child, @NonNull View target) {
        super.onDescendantInvalidated(child, target);
        final Listener l = listener;
        if (l != null) l.onDescendantChanged(target);
    }
}
