package com.callx.app.chat.gesture;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.performance.SwipeOptimizer;
import com.callx.app.models.Message;

import java.util.List;

/**
 * SwipeReplyHandler — WhatsApp-style swipe-to-reply (1:1 + group).
 *
 * v815 rewrite — same proven approach as the Reels-comments swipe
 * (ReelCommentFragment#attachSwipeToReply), adapted for the chat list:
 *
 *  1. The target message is captured ONCE when the drag starts (first active frame), not
 *     looked up again on release. The old code re-resolved it via
 *     getBindingAdapterPosition() inside the release frame; on a live Paging list that can be
 *     NO_POSITION / a different row mid-recover, and the early `m == null -> return` silently
 *     dropped the reply.
 *  2. The reply is NOT started from inside onChildDraw (we are in RecyclerView's draw pass:
 *     ViewStub inflate, setVisibility, requestFocus and showSoftInput are all illegal/janky
 *     there). It is posted to the next frame (~16ms).
 *  3. The reveal icon is drawn in the strip the row uncovers (received -> left edge, sent ->
 *     right edge). The old code positioned it on the wrong side / off-screen.
 *  4. Quick flick arms the reply with a short drag; parents can't steal a horizontal drag;
 *     rows still recovering from an earlier swipe are ignored; spring-back is 120ms.
 *
 * The row only ever moves via translationX (no layout pass) and always springs back —
 * ItemTouchHelper's own swipe-dismiss is disabled (threshold/velocity unreachable).
 *
 * Reply decision = finger RELEASE: armed (|dX| >= trigger at lift) or a fast flick -> reply.
 * Swipe out, drag back under the trigger, lift = no reply.
 */
public class SwipeReplyHandler extends ItemTouchHelper.Callback {

    // ── Feature flags ──────────────────────────────────────────────────────
    public static boolean ENABLE_SWIPE_REPLY = true;
    public static boolean ENABLE_HAPTICS     = true;

    // ── Geometry (dp) ──────────────────────────────────────────────────────
    private static final float TRIGGER_DP     = 60f;   // finger travel that arms the reply
    private static final float MAX_VISUAL_DP  = 84f;   // row never visually goes past this
    private static final float FLING_MIN_DP   = 28f;   // flick arms from this travel...
    private static final float FLING_VEL_DP_S = 600f;  // ...if at least this fast (dp/s)
    private static final float LOCK_DP        = 8f;    // lock parents once clearly horizontal
    private static final float ICON_MAX_CX_DP = 28f;   // icon centre distance from row edge
    private static final float CHIP_BASE_DP   = 14f;
    private static final float CHIP_GROW_DP   = 5f;
    private static final float ICON_SMALL_DP  = 17f;
    private static final float ICON_BIG_DP    = 21f;
    private static final long  RECOVER_MS     = 120L;
    private static final long  DEBOUNCE_MS    = 350L;
    private static final long  POP_MS         = 180L;  // icon/chip "pop" when the reply arms
    private static final float POP_CHIP_DP    = 3f;    // extra chip radius at full pop
    private static final Interpolator POP_IN  = new OvershootInterpolator(2.2f);
    private static final Interpolator POP_OUT = new DecelerateInterpolator();

    private static final int COLOR_FALLBACK = 0xFF4CAF50; // brand_primary

    // ── Collaborators ──────────────────────────────────────────────────────
    private final List<Message>        messages;
    private final String               currentUid;
    private final Context              ctx;
    private final OnSwipeReplyListener listener;

    // ── Cached px values ───────────────────────────────────────────────────
    private final float triggerPx, maxVisualPx, flingMinPx, flingVelPxS, lockPx;
    private final float iconMaxCxPx, chipBasePx, chipGrowPx, iconSmallPx, iconBigPx;

    // ── Draw objects (built once) ──────────────────────────────────────────
    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Drawable    replyIcon;
    // PERF: Drawable.setColorFilter(int, Mode) allocates a new PorterDuffColorFilter on EVERY
    // call — i.e. on every drag frame. Build it once per accent colour instead.
    private PorterDuffColorFilter iconFilter;
    private int                   iconFilterColor;

    // "Pop" animation state (0 = resting size, 1 = armed size, overshoots a little on arm).
    private float   popFrom, popTo;
    private long    popT0;
    private boolean popTarget;
    private final float popChipPx;

    // Optional gate: returns false -> swipe is disabled entirely (no movement, no haptic).
    private SwipeGate gate;

    // ── Gesture state ──────────────────────────────────────────────────────
    private boolean               dragging, armed, fired, parentLocked, dragIsSent;
    private RecyclerView.ViewHolder dragVh;      // the row being dragged (others = still recovering)
    private Message               dragMsg;       // captured at drag start
    private int                   dragPos = RecyclerView.NO_POSITION;
    private float                 lastAbsDx, velocity;   // velocity in px/s
    private long                  lastT;
    private long                  lastTriggerTime = 0L;
    private int                   accent = COLOR_FALLBACK;   // chat theme colour, refreshed per drag

    public interface OnSwipeReplyListener {
        void onSwipeReply(Message message, int adapterPosition);
    }

    /** Lets the host veto swiping (blocked chat, admins-only group, recording, multi-select...). */
    public interface SwipeGate {
        boolean canSwipe();
    }

    /** Replyable = a real, non-deleted message that has a sender (not a system / date row). */
    public static boolean isReplyable(@Nullable Message m) {
        return m != null
                && !Boolean.TRUE.equals(m.deleted)
                && m.senderId != null && !m.senderId.isEmpty();
    }

    public SwipeReplyHandler setGate(@Nullable SwipeGate gate) {
        this.gate = gate;
        return this;
    }

    public SwipeReplyHandler(
            Context ctx,
            List<Message> messages,
            String currentUid,
            OnSwipeReplyListener listener) {
        this.ctx        = ctx;
        this.messages   = messages;
        this.currentUid = currentUid;
        this.listener   = listener;

        final float d = ctx.getResources().getDisplayMetrics().density;
        triggerPx   = TRIGGER_DP * d;
        maxVisualPx = MAX_VISUAL_DP * d;
        flingMinPx  = FLING_MIN_DP * d;
        flingVelPxS = FLING_VEL_DP_S * d;
        lockPx      = LOCK_DP * d;
        iconMaxCxPx = ICON_MAX_CX_DP * d;
        chipBasePx  = CHIP_BASE_DP * d;
        chipGrowPx  = CHIP_GROW_DP * d;
        iconSmallPx = ICON_SMALL_DP * d;
        iconBigPx   = ICON_BIG_DP * d;
        popChipPx   = POP_CHIP_DP * d;

        try {
            Drawable dr = ContextCompat.getDrawable(ctx, com.callx.app.chat.R.drawable.ic_reply);
            if (dr != null) replyIcon = dr.mutate();
        } catch (Exception ignored) {}

        chipPaint.setStyle(Paint.Style.FILL);
    }

    // ── ItemTouchHelper.Callback ───────────────────────────────────────────

    @Override
    public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        if (!ENABLE_SWIPE_REPLY) return makeMovementFlags(0, 0);
        if (gate != null && !gate.canSwipe()) return makeMovementFlags(0, 0);
        Message m = getMessageAt(vh.getBindingAdapterPosition());
        if (!isReplyable(m)) return makeMovementFlags(0, 0); // deleted / system rows

        boolean isSent = currentUid != null && currentUid.equals(m.senderId);
        boolean isRtl  = vh.itemView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        // Sent -> swipe left; Received -> swipe right (RTL flips)
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
        // Unreachable (threshold/velocity disabled below) — never dismiss a row.
        vh.itemView.setTranslationX(0f);
    }

    /** >1 => ItemTouchHelper can never complete a swipe by distance; we only use dX as a signal. */
    @Override
    public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder vh) { return 2.0f; }

    /** Disable the velocity path too: a fast fling would otherwise "dismiss" the row. */
    @Override
    public float getSwipeEscapeVelocity(float defaultValue) { return Float.MAX_VALUE; }

    @Override
    public float getSwipeVelocityThreshold(float defaultValue) { return 0f; }

    /** Fast spring-back (default ~250ms, scaled by distance). */
    @Override
    public long getAnimationDuration(@NonNull RecyclerView rv, int animationType,
                                     float animateDx, float animateDy) {
        return RECOVER_MS;
    }

    /** Row translation for a raw finger distance: 1:1 to the trigger, then heavy resistance. */
    private float visualAbs(float absDx) {
        if (absDx <= triggerPx) return absDx;
        return Math.min(maxVisualPx, triggerPx + (absDx - triggerPx) * 0.25f);
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

        final View  item   = vh.itemView;
        final float absDx  = Math.abs(dX);
        final float signed = (dX >= 0f ? 1f : -1f) * visualAbs(absDx);

        if (isCurrentlyActive) {
            if (!dragging || vh != dragVh) beginDrag(vh, dX);

            // finger velocity (px/s), lightly smoothed — used for flick-to-arm
            long now = SystemClock.uptimeMillis();
            if (lastT != 0L && now > lastT) {
                float v = (absDx - lastAbsDx) * 1000f / (now - lastT);
                velocity = 0.5f * velocity + 0.5f * v;
            }
            lastAbsDx = absDx;
            lastT     = now;

            // Clearly horizontal -> stop parents from stealing the gesture.
            if (!parentLocked && absDx > lockPx && rv.getParent() != null) {
                rv.getParent().requestDisallowInterceptTouchEvent(true);
                parentLocked = true;
            }

            boolean nowArmed = absDx >= triggerPx;
            if (nowArmed && !armed) fireHaptic(item, false); // tick once, as it crosses
            armed = nowArmed;                                // dragging back under = disarmed
        } else if (dragging && vh == dragVh) {
            // First non-active frame after the finger lifted = exact release position.
            onRelease(rv, item);
        }

        // Row moves by translationX only — zero layout passes.
        if (item.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            SwipeOptimizer.enableHardwareLayer(item);
        }
        SwipeOptimizer.setTranslationXSafe(item, signed);

        // Reveal chip + icon in the strip the row uncovers.
        final long now = SystemClock.uptimeMillis();
        float progress = Math.min(1f, absDx / triggerPx);
        final boolean isDragRow = (vh == dragVh);
        final boolean triggered = isDragRow && (isCurrentlyActive ? armed : fired);
        if (isDragRow) setPopTarget(triggered, now);      // arm -> pop up, disarm -> settle back
        if (progress > 0.05f) {
            boolean isSent = isDragRow ? dragIsSent : isSentRow(vh);
            drawReveal(c, item, progress, signed, isSent, isDragRow ? popAt(now) : 0f);
        }
        // The pop runs on its own clock; keep drawing until it settles (finger may be still).
        if (isDragRow && popRunning(now)) rv.postInvalidateOnAnimation();
    }

    private void beginDrag(RecyclerView.ViewHolder vh, float dX) {
        dragging     = true;
        armed        = false;
        fired        = false;
        parentLocked = false;
        dragVh       = vh;
        dragPos      = vh.getBindingAdapterPosition();
        dragMsg      = getMessageAt(dragPos);   // captured once, used at release
        try { accent = com.callx.app.utils.ChatThemeManager.get(ctx).getPrimaryColor(); }
        catch (Exception e) { accent = COLOR_FALLBACK; }
        dragIsSent   = dragMsg != null && currentUid != null && currentUid.equals(dragMsg.senderId);
        velocity     = 0f;
        lastAbsDx    = 0f;
        lastT        = 0L;
        resetPop();
    }

    /** Finger lifted: decide now. Reply is posted to the next frame (we are inside a draw pass). */
    private void onRelease(@NonNull RecyclerView rv, @NonNull View item) {
        dragging = false;
        unlockParent(rv);

        boolean flick = !armed && lastAbsDx >= flingMinPx && velocity >= flingVelPxS;
        if ((armed || flick) && !fired && dragMsg != null) {
            long now = SystemClock.uptimeMillis();
            if (now - lastTriggerTime >= DEBOUNCE_MS) {
                fired           = true;
                lastTriggerTime = now;
                fireHaptic(item, true);
                final Message fm = dragMsg;
                final int     fp = dragPos;
                rv.post(() -> {
                    if (listener != null) listener.onSwipeReply(fm, fp);
                });
            }
        }
    }

    @Override
    public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        super.clearView(rv, vh);
        SwipeOptimizer.clearHardwareLayer(vh.itemView);
        SwipeOptimizer.springBack(vh.itemView);

        // Fallback: view cleared before any non-active frame was drawn.
        if (dragging && vh == dragVh) onRelease(rv, vh.itemView);

        // Another (older) row finished recovering — keep the current drag's state.
        if (dragVh != null && vh != dragVh) return;

        unlockParent(rv);
        dragVh = null;
        dragMsg = null;
        dragPos = RecyclerView.NO_POSITION;
        dragging = armed = fired = false;
        velocity = lastAbsDx = 0f;
        lastT = 0L;
        resetPop();
    }

    private void unlockParent(RecyclerView rv) {
        if (parentLocked && rv.getParent() != null) {
            rv.getParent().requestDisallowInterceptTouchEvent(false);
        }
        parentLocked = false;
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    /**
     * signedVisual > 0: row moved right (received) -> strip is at the LEFT edge.
     * signedVisual < 0: row moved left  (sent)     -> strip is at the RIGHT edge.
     */
    private void drawReveal(Canvas c, View item, float progress, float signedVisual,
                            boolean isSent, float pop) {
        final float strip = Math.abs(signedVisual);
        final float off   = Math.min(strip * 0.5f, iconMaxCxPx);
        final float cx    = signedVisual >= 0f ? item.getLeft() + off : item.getRight() - off;
        final float cy    = item.getTop() + item.getHeight() / 2f;
        final int   color = accent;

        // pop is 0..1 (+ a little overshoot on arm): chip grows/brightens, icon scales small->big.
        final float popC = Math.max(0f, Math.min(1f, pop));
        float chipProgress = Math.min(1f, progress * 1.3f);
        int chipAlpha = (int) (0x33 * chipProgress + 0x22 * popC);
        chipPaint.setColor((color & 0x00FFFFFF) | (chipAlpha << 24));
        c.drawCircle(cx, cy, chipBasePx + chipGrowPx * chipProgress + popChipPx * pop, chipPaint);

        if (replyIcon != null) {
            float sz = iconSmallPx + (iconBigPx - iconSmallPx) * pop;
            int   h  = (int) (sz / 2f);
            replyIcon.setBounds((int) cx - h, (int) cy - h, (int) cx + h, (int) cy + h);
            replyIcon.setAlpha((int) (255 * Math.min(1f, progress * 1.6f)));
            if (iconFilter == null || iconFilterColor != color) {   // PERF: no per-frame alloc
                iconFilter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
                iconFilterColor = color;
            }
            replyIcon.setColorFilter(iconFilter);
            replyIcon.draw(c);
        }
    }

    // ── Pop animation (own clock, driven from onChildDraw) ─────────────────

    private float popAt(long now) {
        if (popT0 == 0L) return popTo;
        float t = Math.min(1f, (now - popT0) / (float) POP_MS);
        Interpolator ip = popTo > popFrom ? POP_IN : POP_OUT;
        return popFrom + (popTo - popFrom) * ip.getInterpolation(t);
    }

    private boolean popRunning(long now) {
        return popT0 != 0L && now - popT0 < POP_MS;
    }

    private void setPopTarget(boolean on, long now) {
        if (on == popTarget) return;
        popFrom   = popAt(now);
        popTo     = on ? 1f : 0f;
        popT0     = now;
        popTarget = on;
    }

    private void resetPop() {
        popFrom = popTo = 0f;
        popT0 = 0L;
        popTarget = false;
    }

    // ── Haptic ─────────────────────────────────────────────────────────────

    private void fireHaptic(View v, boolean heavy) {
        if (!ENABLE_HAPTICS) return;
        v.performHapticFeedback(heavy
                ? HapticFeedbackConstants.LONG_PRESS
                : HapticFeedbackConstants.KEYBOARD_PRESS);
    }

    // ── Utils ──────────────────────────────────────────────────────────────

    private boolean isSentRow(RecyclerView.ViewHolder vh) {
        Message m = getMessageAt(vh.getBindingAdapterPosition());
        return m != null && currentUid != null && currentUid.equals(m.senderId);
    }

    private Message getMessageAt(int pos) {
        if (messages == null || pos < 0 || pos >= messages.size()) return null;
        return messages.get(pos);
    }
}
