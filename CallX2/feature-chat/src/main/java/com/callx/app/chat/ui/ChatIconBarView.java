package com.callx.app.chat.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.callx.app.chat.R;

/**
 * PERF (icon-bar merge pass): combines what used to be four separate
 * ImageButtons — btn_attach, btn_camera, btn_mic, btn_send, each with its
 * own background drawable / ripple / property animator — into a SINGLE
 * Canvas-drawn view. Two wins:
 *
 *  1. Four measured+laid-out views collapse to one. Every capsule resize
 *     (keyboard show/hide, line-count change) used to re-measure all four.
 *
 *  2. The old "attach/camera shrink away as you type" animation
 *     (see git history of ChatActivity#animateIconTo) called
 *     icon.setLayoutParams(lp) on EVERY animation frame for two views —
 *     i.e. up to ~40 full ConstraintLayout re-measure/re-layout passes of
 *     ll_input_row per single expand/collapse cycle. Here the shrink/fade
 *     is pure Canvas paint (invalidate-only, like the capsule's own
 *     clipBounds reveal trick), and the view's *measured* width changes
 *     exactly once per cycle — snapped to full width right before an
 *     expand-fade-in (so icons have room to appear) or snapped to
 *     collapsed width right after a collapse-fade-out finishes (so nothing
 *     visibly jumps).
 *
 * Mic press/drag-to-record semantics (see ChatMediaController) are
 * preserved by forwarding raw touch events for the mic/send slot to a
 * {@link MicTouchListener}, and by exposing a standard bean property
 * (get/setMicIconScale) so the existing ObjectAnimator-based scale-bounce
 * code keeps working unchanged, just retargeted at this view.
 */
public class ChatIconBarView extends View {

    public interface MicTouchListener {
        boolean onMicTouch(View v, MotionEvent event);
    }

    private static final float SLOT_DP = 34f;
    private static final float GAP_DP = 4f;
    private static final float ICON_INSET_DP = 6f;
    private static final OvershootInterpolator EXPAND_OVERSHOOT = new OvershootInterpolator(2.2f);

    private final float density = getResources().getDisplayMetrics().density;
    private final int slotSizePx = Math.round(SLOT_DP * density);
    private final int gapPx = Math.round(GAP_DP * density);
    private final int iconInsetPx = Math.round(ICON_INSET_DP * density);
    private final int touchSlopPx;

    private final Rect attachRect = new Rect();
    private final Rect cameraRect = new Rect();
    private final Rect micSendRect = new Rect();

    private Drawable attachIcon, cameraIcon, micIcon, sendIcon;
    private Paint circlePaint;
    private Paint pressPaint;

    // Expand/collapse (attach+camera) state
    private boolean iconsExpanded = true;
    private boolean expandedLayout = true; // drives onMeasure width
    private float attachCameraFraction = 1f; // 0..1(+overshoot), paint-only
    private ValueAnimator expandAnimator;

    // Mic <-> send crossfade state
    private boolean showingSend = false;
    private float sendFraction = 0f; // 0 = mic fully shown, 1 = send fully shown
    private ValueAnimator sendAnimator;

    private float micIconScale = 1f;
    private boolean micEnabled = true;
    private boolean sendEnabled = true;
    private boolean micSendVisible = true;

    private Runnable onAttachClick;
    private Runnable onCameraClick;
    private Runnable onSendClick;
    private Runnable onSendLongClick;
    private MicTouchListener micTouchListener;

    private int downSlot = 0; // 1=attach 2=camera 3=send
    private float downX, downY;
    private boolean longPressFired;
    private boolean micPointerActive;
    private final Runnable longPressRunnable = this::handleLongPress;

    public ChatIconBarView(Context context) {
        this(context, null);
    }

    public ChatIconBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        touchSlopPx = ViewConfiguration.get(context).getScaledTouchSlop();
        initPaints();
        initIcons();
    }

    private void initPaints() {
        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(ContextCompat.getColor(getContext(), R.color.brand_primary));
        pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pressPaint.setColor(Color.WHITE);
        pressPaint.setAlpha(40);
    }

    private void initIcons() {
        attachIcon = tinted(R.drawable.ic_attach);
        cameraIcon = tinted(R.drawable.ic_camera);
        micIcon = tinted(R.drawable.ic_mic);
        sendIcon = tinted(R.drawable.ic_send);
    }

    private Drawable tinted(int resId) {
        Drawable d = ContextCompat.getDrawable(getContext(), resId);
        if (d == null) return null;
        d = d.mutate();
        DrawableCompat.setTint(d, ContextCompat.getColor(getContext(), R.color.white));
        return d;
    }

    // ── Measure / layout ─────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = expandedLayout
                ? slotSizePx * 3 + gapPx * 2
                : slotSizePx;
        setMeasuredDimension(
                resolveSizeAndState(desiredWidth, widthMeasureSpec, 0),
                resolveSizeAndState(slotSizePx, heightMeasureSpec, 0));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int top = (h - slotSizePx) / 2;
        micSendRect.set(w - slotSizePx, top, w, top + slotSizePx);
        cameraRect.set(micSendRect.left - gapPx - slotSizePx, top,
                micSendRect.left - gapPx, top + slotSizePx);
        attachRect.set(cameraRect.left - gapPx - slotSizePx, top,
                cameraRect.left - gapPx, top + slotSizePx);
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float f = Math.max(0f, Math.min(1.3f, attachCameraFraction));
        if (f > 0.01f) {
            drawIcon(canvas, attachIcon, attachRect, Math.min(1f, f), 0.6f + 0.4f * f);
            drawIcon(canvas, cameraIcon, cameraRect, Math.min(1f, f), 0.6f + 0.4f * f);
        }

        // Mic/send shared slot: green circle background always drawn
        // (matches original circle_primary background on both buttons).
        if (!micSendVisible) return;
        int cx = micSendRect.centerX();
        int cy = micSendRect.centerY();
        canvas.drawCircle(cx, cy, slotSizePx / 2f, circlePaint);

        if (sendFraction < 0.995f) {
            int alpha = Math.round((1f - sendFraction) * 255);
            canvas.save();
            canvas.scale(micIconScale, micIconScale, cx, cy);
            drawIcon(canvas, micIcon, micSendRect, alpha / 255f, 1f, micEnabled ? 255 : 100);
            canvas.restore();
        }
        if (sendFraction > 0.005f) {
            drawIcon(canvas, sendIcon, micSendRect, sendFraction, 1f, sendEnabled ? 255 : 100);
        }
    }

    private void drawIcon(Canvas canvas, Drawable icon, Rect slot, float alpha, float scale) {
        drawIcon(canvas, icon, slot, alpha, scale, 255);
    }

    private void drawIcon(Canvas canvas, Drawable icon, Rect slot, float alpha, float scale, int baseAlpha) {
        if (icon == null || alpha <= 0f) return;
        int cx = slot.centerX();
        int cy = slot.centerY();
        canvas.save();
        canvas.scale(scale, scale, cx, cy);
        icon.setBounds(slot.left + iconInsetPx, slot.top + iconInsetPx,
                slot.right - iconInsetPx, slot.bottom - iconInsetPx);
        icon.setAlpha(Math.round(alpha * baseAlpha));
        icon.draw(canvas);
        canvas.restore();
    }

    // ── Touch handling ───────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();

        boolean inMicSlot = micSendVisible && !showingSend && micSendRect.contains((int) x, (int) y);

        if (micPointerActive || (action == MotionEvent.ACTION_DOWN && inMicSlot)) {
            if (!micEnabled) return false;
            if (action == MotionEvent.ACTION_DOWN) micPointerActive = true;
            boolean handled = micTouchListener != null && micTouchListener.onMicTouch(this, event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                micPointerActive = false;
            }
            return handled;
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                longPressFired = false;
                downSlot = slotAt(x, y);
                if (downSlot != 0) {
                    postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                    invalidate();
                }
                return downSlot != 0;
            case MotionEvent.ACTION_MOVE:
                if (downSlot != 0 && (Math.abs(x - downX) > touchSlopPx || Math.abs(y - downY) > touchSlopPx)) {
                    cancelPress();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (downSlot != 0 && !longPressFired) {
                    fireClick(downSlot);
                }
                cancelPress();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelPress();
                return true;
            default:
                return false;
        }
    }

    private int slotAt(float x, float y) {
        if (attachCameraFraction > 0.3f && attachRect.contains((int) x, (int) y)) return 1;
        if (attachCameraFraction > 0.3f && cameraRect.contains((int) x, (int) y)) return 2;
        if (micSendVisible && micSendRect.contains((int) x, (int) y)) return 3;
        return 0;
    }

    private void handleLongPress() {
        if (downSlot == 3 && showingSend) {
            longPressFired = true;
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            if (onSendLongClick != null) onSendLongClick.run();
        }
    }

    private void fireClick(int slot) {
        performClick();
        switch (slot) {
            case 1:
                if (onAttachClick != null) onAttachClick.run();
                break;
            case 2:
                if (onCameraClick != null) onCameraClick.run();
                break;
            case 3:
                if (showingSend && sendEnabled && onSendClick != null) onSendClick.run();
                break;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void cancelPress() {
        removeCallbacks(longPressRunnable);
        downSlot = 0;
        invalidate();
    }

    // ── Public API ───────────────────────────────────────────────────────

    public void setOnAttachClickListener(Runnable r) { this.onAttachClick = r; }
    public void setOnCameraClickListener(Runnable r) { this.onCameraClick = r; }
    public void setOnSendClickListener(Runnable r) { this.onSendClick = r; }
    public void setOnSendLongClickListener(Runnable r) { this.onSendLongClick = r; }
    public void setMicTouchListener(MicTouchListener l) { this.micTouchListener = l; }

    public void setMicEnabled(boolean enabled) {
        this.micEnabled = enabled;
        invalidate();
    }

    public void setSendEnabled(boolean enabled) {
        this.sendEnabled = enabled;
        invalidate();
    }

    /** Re-tints the mic/send circle background (see ChatThemeController /
     *  ChatThemeManager — theme apply used to swap a GradientDrawable
     *  background on btnSend/btnMic directly; now it just recolors this
     *  view's Paint). */
    public void setAccentColor(int color) {
        circlePaint.setColor(color);
        invalidate();
    }

    /** Hides/shows the mic+send slot entirely (blocked-chat states, which
     *  used to set VISIBLE/GONE on the separate btnSend/btnMic). Attach
     *  and camera are unaffected — matches the original controller, which
     *  never touched them for block state either. */
    public void setMicSendVisible(boolean visible) {
        this.micSendVisible = visible;
        invalidate();
    }

    /** Bean property so ChatMediaController can keep driving the mic
     *  press/lock/cancel scale-bounce via ObjectAnimator, unchanged. */
    public float getMicIconScale() { return micIconScale; }
    public void setMicIconScale(float scale) {
        this.micIconScale = scale;
        invalidate();
    }

    /** Mic icon's bounds in this view's local coordinate space — combine
     *  with getLocationInWindow() to replicate the old
     *  excludeDragZoneFromSystemGestures() math that used to read
     *  btnMic.getLocationInWindow() directly. */
    public Rect getMicBoundsInView() {
        return new Rect(micSendRect);
    }

    /** Crossfades mic -> send (or back), replacing animateSendMicSwap(). */
    public void setHasText(boolean hasText) {
        if (showingSend == hasText) return;
        showingSend = hasText;
        if (sendAnimator != null) sendAnimator.cancel();
        sendAnimator = ValueAnimator.ofFloat(sendFraction, hasText ? 1f : 0f);
        sendAnimator.setDuration(150);
        sendAnimator.addUpdateListener(a -> {
            sendFraction = (float) a.getAnimatedValue();
            invalidate();
        });
        sendAnimator.start();
    }

    /** Telegram/Instagram-style attach+camera collapse, replacing
     *  animateAttachCameraIcons(). See class doc for the single-layout-pass
     *  perf rationale. */
    public void setIconsExpanded(boolean expand) {
        if (iconsExpanded == expand) return;
        iconsExpanded = expand;

        if (expandAnimator != null) expandAnimator.cancel();

        if (expand) {
            // Snap measured width to full BEFORE the fade-in starts, so the
            // icons have somewhere to appear -- one requestLayout, not one
            // per frame.
            if (!expandedLayout) {
                expandedLayout = true;
                requestLayout();
            }
            expandAnimator = ValueAnimator.ofFloat(attachCameraFraction, 1f);
            expandAnimator.setDuration(320);
            expandAnimator.setInterpolator(EXPAND_OVERSHOOT);
            expandAnimator.addUpdateListener(a -> {
                attachCameraFraction = (float) a.getAnimatedValue();
                invalidate();
            });
            expandAnimator.start();
        } else {
            expandAnimator = ValueAnimator.ofFloat(attachCameraFraction, 0f);
            expandAnimator.setDuration(200);
            expandAnimator.setInterpolator(new DecelerateInterpolator());
            expandAnimator.addUpdateListener(a -> {
                attachCameraFraction = (float) a.getAnimatedValue();
                invalidate();
            });
            expandAnimator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    // Now that attach/camera are fully invisible, collapse
                    // the measured width -- exactly one requestLayout for
                    // the whole cycle, not one per frame.
                    if (expandedLayout) {
                        expandedLayout = false;
                        requestLayout();
                    }
                }
            });
            expandAnimator.start();
        }
    }
}
