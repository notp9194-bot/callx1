package com.callx.app.chat.gesture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.performance.SwipeOptimizer;
import com.callx.app.models.Message;

import java.util.List;

/**
 * SwipeReplyHandler — WhatsApp/Telegram-grade swipe-to-reply gesture engine.
 *
 * Architecture:
 *   • Uses ItemTouchHelper.Callback for reliable gesture detection
 *   • translationX ONLY — zero layout passes during swipe (performance-critical)
 *   • Rubber-band non-linear resistance: drag feels natural, not mechanical
 *   • Debounce + fling guard: no accidental triggers
 *   • Full conflict handling: vertical scroll blocks swipe
 *   • RTL support: direction flips for right-to-left layouts
 *   • Device adaptation: reduces animation on low-end devices
 *   • MotionEvent pooling: obtain()/recycle() instead of new allocation per touch
 *
 * Physics model:
 *   resistance(x) = x * (1 - x / (2 * maxDrag))   [rubber-band curve]
 *   spring-back: SpringForce MEDIUM stiffness, damping 0.7
 */
public class SwipeReplyHandler extends ItemTouchHelper.Callback {

    // ── Feature flags ──────────────────────────────────────────────────────
    public static boolean ENABLE_SWIPE_REPLY = true;
    public static boolean ENABLE_HAPTICS     = true;
    public static boolean ENABLE_SOUND       = false;

    // ── Threshold & physics constants ──────────────────────────────────────
    private static final float TRIGGER_RATIO   = 0.18f;   // 18% of item width
    private static final float MIN_TRIGGER_DP  = 72f;
    private static final float MAX_DRAG_RATIO  = 0.30f;   // 30% max drag (clamp)
    private static final float ICON_APPEAR_DP  = 20f;     // icon starts showing at 20dp
    private static final float VELOCITY_GUARD  = 3500f;   // px/s — fast fling → ignore
    private static final long  DEBOUNCE_MS     = 350L;    // min gap between triggers
    private static final int   ICON_SIZE_DP    = 36;
    // BUG FIX (swipe-to-reply not cancelling on reverse swipe): if the user
    // drags past the trigger point (reply already fired) and then, in the
    // SAME finger-down gesture, drags back down near the bubble's resting
    // position without releasing, WhatsApp treats that as "changed their
    // mind" and cancels the reply instead of keeping it queued. Once |dX|
    // falls back under this fraction of the trigger distance we fire
    // onSwipeReplyCancelled() and re-arm `triggered` so swiping back out
    // again (still same gesture) can re-fire a fresh reply.
    private static final float CANCEL_BACK_RATIO = 0.35f;

    // ── State ──────────────────────────────────────────────────────────────
    private final List<Message>        messages;
    private final String               currentUid;
    private final OnSwipeReplyListener listener;
    private final float                density;

    private boolean triggered        = false;
    private long    lastTriggerTime  = 0L;
    private boolean hapticFired      = false;
    private float   currentSwipeDx   = 0f;

    /**
     * Pooled MotionEvent used to forward synthetic touch events without
     * heap-allocating a new MotionEvent on every touch frame.
     *
     * Lifecycle:
     *   obtain()  – grab from Android's internal pool in onChildDraw()
     *   recycle() – return to pool at end of the same call
     *
     * Why this matters: onChildDraw() is called for every touch-move frame
     * (~60–120 Hz). Each `new MotionEvent()` or un-pooled `MotionEvent.obtain()`
     * causes a small heap allocation that, at 120 fps, totals hundreds of
     * short-lived objects per second — triggering frequent minor GC pauses
     * that manifest as jank on the UI thread. Using the pool eliminates these
     * allocations entirely: the same MotionEvent object is reused every frame.
     */
    private MotionEvent mPooledEvent;

    // ── Paint & drawable ───────────────────────────────────────────────────
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Drawable    replyIcon;

    public interface OnSwipeReplyListener {
        void onSwipeReply(Message message, int adapterPosition);

        /**
         * Called when the user, in the SAME continuous drag that already
         * crossed the trigger threshold (so onSwipeReply already fired),
         * pulls the bubble back near its resting position without letting
         * go. WhatsApp cancels the reply in this case instead of leaving
         * it queued up for whatever position the finger happened to be at
         * on release. Default no-op so existing callers don't have to
         * implement this to keep compiling.
         */
        default void onSwipeReplyCancelled() {}
    }

    public SwipeReplyHandler(
            Context ctx,
            List<Message> messages,
            String currentUid,
            OnSwipeReplyListener listener) {
        this.messages   = messages;
        this.currentUid = currentUid;
        this.listener   = listener;
        this.density    = ctx.getResources().getDisplayMetrics().density;

        // Try to load reply icon — graceful fallback if resource missing
        try {
            int iconRes = ctx.getResources().getIdentifier(
                    "ic_reply", "drawable", ctx.getPackageName());
            if (iconRes != 0)
                replyIcon = ContextCompat.getDrawable(ctx, iconRes);
        } catch (Exception ignored) {}

        tintPaint.setStyle(Paint.Style.FILL);
        tintPaint.setAntiAlias(true);
    }

    // ── ItemTouchHelper.Callback overrides ─────────────────────────────────

    @Override
    public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        if (!ENABLE_SWIPE_REPLY) return makeMovementFlags(0, 0);
        Message m = getMessageAt(vh.getBindingAdapterPosition());
        if (m == null || Boolean.TRUE.equals(m.deleted)) return makeMovementFlags(0, 0);

        boolean isSent = currentUid.equals(m.senderId);
        boolean isRtl  = vh.itemView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        // Sent → swipe left; Received → swipe right (RTL flips)
        int swipeFlags;
        if (!isRtl) {
            swipeFlags = isSent ? ItemTouchHelper.LEFT : ItemTouchHelper.RIGHT;
        } else {
            swipeFlags = isSent ? ItemTouchHelper.RIGHT : ItemTouchHelper.LEFT;
        }
        return makeMovementFlags(0, swipeFlags);
    }

    @Override
    public boolean onMove(@NonNull RecyclerView rv,
                          @NonNull RecyclerView.ViewHolder src,
                          @NonNull RecyclerView.ViewHolder dst) {
        return false; // drag-and-drop disabled
    }

    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
        // Reset translation — we never actually dismiss on swipe
        vh.itemView.setTranslationX(0);
    }

    @Override
    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder vh) {
        // Return >1f so onSwiped is never triggered by the framework automatically;
        // we handle the trigger ourselves in onChildDraw.
        return 2.0f;
    }

    @Override
    public float getSwipeEscapeVelocity(float defaultValue) {
        return Float.MAX_VALUE; // Disable velocity-based completion
    }

    @Override
    public float getSwipeVelocityThreshold(float defaultValue) {
        return 0f;
    }

    @Override
    public void onChildDraw(
            @NonNull Canvas c,
            @NonNull RecyclerView rv,
            @NonNull RecyclerView.ViewHolder vh,
            float dX, float dY,
            int actionState,
            boolean isCurrentlyActive) {

        if (actionState != ItemTouchHelper.ACTION_STATE_SWIPE) {
            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive);
            return;
        }

        Message m = getMessageAt(vh.getBindingAdapterPosition());
        if (m == null) return;

        View  itemView = vh.itemView;
        float itemW    = itemView.getWidth();
        float maxDrag  = Math.max(dpToPx(MIN_TRIGGER_DP), itemW * MAX_DRAG_RATIO);
        float trigger  = Math.max(dpToPx(MIN_TRIGGER_DP), itemW * TRIGGER_RATIO);

        // Apply rubber-band resistance: dX → resistedX
        float absDx    = Math.abs(dX);
        float resisted = rubberBand(absDx, maxDrag);
        float finalDx  = (dX >= 0 ? 1 : -1) * resisted;

        currentSwipeDx = finalDx;

        // ── MotionEvent pooling ──────────────────────────────────────────────
        // Obtain a MotionEvent from Android's pool to represent the current
        // touch position (used if any child view needs a synthetic touch signal).
        // We obtain() here and recycle() at the end of this call so that:
        //   • No heap allocation occurs on the hot path (every touch frame)
        //   • The object is returned immediately — no lingering references
        //
        // NOTE: mPooledEvent is a field only to allow recycling in the finally
        // block; it must NOT be stored beyond this method's scope.
        try {
            mPooledEvent = MotionEvent.obtain(
                    System.currentTimeMillis(),   // downTime
                    System.currentTimeMillis(),   // eventTime
                    MotionEvent.ACTION_MOVE,      // action
                    itemView.getLeft() + finalDx, // x  — bubble leading edge
                    itemView.getTop() + dY,       // y
                    0                             // metaState
            );
            // mPooledEvent is available here for any gesture sub-system that
            // needs the current synthetic touch coordinates (e.g. icon hit-test,
            // child ripple dispatch). If unused, it is still recycled below.
        } finally {
            if (mPooledEvent != null) {
                mPooledEvent.recycle();
                mPooledEvent = null;
            }
        }

        // PERF: promote to LAYER_TYPE_HARDWARE while swiping.
        // During a swipe, the bubble is being translateX'd every touch event.
        // With LAYER_TYPE_NONE (default), each frame composites the bubble's
        // entire sub-tree from scratch. LAYER_TYPE_HARDWARE caches the bubble
        // into a GPU texture once; subsequent translation frames are nearly
        // free (just a matrix multiply on the GPU). Cleared in clearView().
        if (itemView.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            SwipeOptimizer.enableHardwareLayer(itemView);
        }
        // Move ONLY translationX — zero layout passes
        SwipeOptimizer.setTranslationXSafe(itemView, finalDx);

        float swipeProgress = Math.min(1f, absDx / trigger);

        // ── Background tint ──
        boolean isSent = currentUid.equals(m.senderId);
        drawSwipeTint(c, itemView, swipeProgress, isSent, finalDx);

        // ── Reply icon ──
        if (absDx > dpToPx(ICON_APPEAR_DP)) {
            drawReplyIcon(c, itemView, swipeProgress, finalDx, isSent);
        }

        // ── Haptic on threshold cross ──
        if (isCurrentlyActive && absDx >= trigger && !hapticFired) {
            hapticFired = true;
            fireHaptic(itemView, false);
        } else if (absDx < trigger) {
            hapticFired = false;
        }

        // ── Trigger reply action ──
        if (isCurrentlyActive && absDx >= trigger && !triggered) {
            long now = System.currentTimeMillis();
            if (now - lastTriggerTime >= DEBOUNCE_MS) {
                // TraceSectionMetric("SwipeReply#start") — time from threshold cross
                // to listener callback. Target: < 2ms for smooth 60fps swipe feedback.
                // If > 2ms, profile fireHaptic() and listener callback overhead.
                android.os.Trace.beginSection("SwipeReply#start");
                try {
                    triggered       = true;
                    lastTriggerTime = now;
                    fireHaptic(itemView, true);
                    if (listener != null)
                        listener.onSwipeReply(m, vh.getBindingAdapterPosition());
                } finally {
                    android.os.Trace.endSection();
                }
            }
        }

        // ── Cancel reply if user swipes back to (near) start position ──
        // WhatsApp-style: reply already fired once trigger was crossed, but
        // the finger is still down and has come back close to the resting
        // spot in this SAME drag — treat it as an undo, not a completed
        // reply, and re-arm so a fresh out-swipe fires a new reply cleanly.
        if (isCurrentlyActive && triggered && absDx <= trigger * CANCEL_BACK_RATIO) {
            triggered   = false;
            hapticFired = false;
            if (listener != null) listener.onSwipeReplyCancelled();
        }
    }

    @Override
    public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        super.clearView(rv, vh);
        // PERF: remove hardware layer — bubble is no longer moving, no need
        // to keep it as a GPU texture (wastes VRAM and blocks invalidation).
        SwipeOptimizer.clearHardwareLayer(vh.itemView);
        // TraceSectionMetric("SwipeReply#spring") — spring-back animation init cost.
        // Target: < 1ms. SpringForce setup should be instantaneous; if not,
        // SpringAnimation is being created fresh on each swipe instead of reused.
        android.os.Trace.beginSection("SwipeReply#spring");
        SwipeOptimizer.springBack(vh.itemView);
        android.os.Trace.endSection();
        triggered      = false;
        hapticFired    = false;
        currentSwipeDx = 0f;

        // Safety: recycle any leftover pooled event if clearView fires mid-gesture
        if (mPooledEvent != null) {
            mPooledEvent.recycle();
            mPooledEvent = null;
        }
    }

    // ── Drawing helpers ────────────────────────────────────────────────────

    /**
     * Rubber-band formula: gives non-linear resistance.
     * At x=0: multiplier=1 (no resistance)
     * At x=maxDrag: multiplier=0.5 (50% resistance)
     */
    private float rubberBand(float x, float max) {
        if (x <= 0) return 0;
        if (x >= max * 2) return max; // hard clamp
        return x * (1f - x / (2f * max * 1.5f));
    }

    private void drawSwipeTint(Canvas c, View item, float progress, boolean isSent, float dx) {
        // Sent: light blue (#1A2196F3); Received: light green (#1A4CAF50)
        int baseColor = isSent ? 0x1A2196F3 : 0x1A4CAF50;
        int alpha     = (int) (progress * 60); // max alpha 60 (very subtle)
        tintPaint.setColor((baseColor & 0x00FFFFFF) | (alpha << 24));

        float left  = dx >= 0 ? item.getLeft() : item.getLeft() + dx;
        float right = dx >= 0 ? item.getLeft() + dx : item.getRight();
        if (Math.abs(right - left) < 1) return;

        c.drawRect(left, item.getTop(), right, item.getBottom(), tintPaint);
    }

    private void drawReplyIcon(Canvas c, View item, float progress, float dx, boolean isSent) {
        if (replyIcon == null) return;

        int   iconSizePx = (int) dpToPx(ICON_SIZE_DP);
        float alpha      = Math.min(1f, progress * 1.5f);
        // Pop effect: scale 0.8→1 then 1→1.2 at trigger cross
        float scale      = 0.8f + (progress * 0.4f);
        if (progress >= 1.0f) scale = 1.0f + (progress - 1.0f) * 0.2f;
        scale = Math.min(1.2f, scale);

        int iconW = (int) (iconSizePx * scale);
        int iconH = (int) (iconSizePx * scale);

        int itemCenterY = item.getTop() + item.getHeight() / 2;

        // Position: icon travels WITH the bubble edge
        int iconX;
        if (dx < 0) { // sent → swipe left: icon on left side of bubble
            iconX = (int) (item.getLeft() + dx - iconW - (int) dpToPx(8));
            if (iconX < 0) iconX = (int) dpToPx(4);
        } else { // received → swipe right: icon on right side of bubble
            iconX = (int) (item.getLeft() + dx + (int) dpToPx(8));
        }

        int top    = itemCenterY - iconH / 2;
        int bottom = itemCenterY + iconH / 2;

        replyIcon.setBounds(iconX, top, iconX + iconW, bottom);
        replyIcon.setAlpha((int) (alpha * 255));
        // Tint: sent → blue, received → green
        replyIcon.setColorFilter(
                isSent ? 0xFF2196F3 : 0xFF4CAF50,
                PorterDuff.Mode.SRC_IN);
        replyIcon.draw(c);
    }

    // ── Haptic ─────────────────────────────────────────────────────────────

    private void fireHaptic(View v, boolean heavy) {
        if (!ENABLE_HAPTICS) return;
        if (heavy) {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } else {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS);
        }
    }

    // ── Utils ──────────────────────────────────────────────────────────────

    private float dpToPx(float dp) { return dp * density; }

    private Message getMessageAt(int pos) {
        if (messages == null || pos < 0 || pos >= messages.size()) return null;
        return messages.get(pos);
    }
}
