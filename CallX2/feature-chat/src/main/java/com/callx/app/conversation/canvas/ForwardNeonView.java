package com.callx.app.conversation.canvas;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.ImageButton;

import com.callx.app.utils.ChatThemeManager;

/**
 * Legacy (View-based bubble) quick-forward button. Draws the exact same neon
 * animation as the canvas bubble by reusing {@link ForwardNeonButton}.
 * Extends ImageButton so it stays a drop-in replacement for the old layout tag.
 *
 * Usage: setOnClickListener(v -> view.play(() -> doForward()));
 * The action fires at ForwardNeonButton.ACTION_AT_MS; extra taps while playing
 * are ignored; animation + pending action are cancelled on detach / cancel().
 */
public class ForwardNeonView extends ImageButton {

    private final ForwardNeonButton neon = new ForwardNeonButton();
    private long animStart = -1L;
    private Runnable pendingAction;
    private final Runnable fire = new Runnable() {
        @Override public void run() {
            Runnable a = pendingAction;
            pendingAction = null;
            if (a != null) a.run();
        }
    };

    public ForwardNeonView(Context c) { super(c); init(); }
    public ForwardNeonView(Context c, AttributeSet a) { super(c, a); init(); }
    public ForwardNeonView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        setBackground(null);
        setContentDescription("Forward");
    }

    /** Play the animation, then run {@code action} at ACTION_AT_MS. */
    public void play(Runnable action) {
        if (animStart >= 0) return;                       // already running
        if (ForwardNeonButton.motionReduced(getContext())) {
            if (action != null) action.run();             // animations off: act now
            return;
        }
        pendingAction = action;
        animStart = SystemClock.uptimeMillis();
        removeCallbacks(fire);
        postDelayed(fire, ForwardNeonButton.ACTION_AT_MS);
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        postInvalidateOnAnimation();
    }

    /** Stop animation and drop the pending action (rebind / recycle). */
    public void cancel() {
        removeCallbacks(fire);
        pendingAction = null;
        if (animStart >= 0) { animStart = -1L; invalidate(); }
    }

    @Override
    public void setPressed(boolean pressed) {
        boolean changed = pressed != isPressed();
        super.setPressed(pressed);
        if (changed) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom());
        if (size <= 0f) return;
        final float left = getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - size) / 2f;
        final float top  = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - size) / 2f;
        final boolean dark = ChatThemeManager.isDarkMode(getContext());

        long el = -1L;
        if (animStart >= 0) {
            el = SystemClock.uptimeMillis() - animStart;
            if (el >= ForwardNeonButton.DURATION_MS) { animStart = -1L; el = -1L; }
        }
        final boolean pressedLook = isPressed() && el < 0;
        if (pressedLook) {
            canvas.save();
            canvas.scale(0.88f, 0.88f, getWidth() / 2f, getHeight() / 2f);
        }
        neon.draw(canvas, left, top, size, el, dark);
        if (pressedLook) canvas.restore();
        if (el >= 0) postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancel();
        super.onDetachedFromWindow();
    }
}
