package com.callx.app.chat.gesture;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.performance.SwipeOptimizer;
import com.callx.app.models.Message;

import java.util.List;
import java.util.Locale;

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
 * v905 perf pass:
 *  - Hardware layer ONLY on heavy rows (image/video/gif/sticker/multi_media/file). Text rows move by
 *    translationX alone (RenderNode property, no re-record), so a layer there was pure cost.
 *  - Reveal chip + icon are painted on a tiny overlay view instead of RecyclerView's canvas.
 *  - The reply (reply-bar show + keyboard) is dispatched AFTER the spring-back finished
 *    (see DEFER_REPLY_UNTIL_RECOVER), not in the release frame.
 *  - Accent colour cached (no per-drag theme lookup); debug-only "SwipePerf" frame log.
 *
 * Reply decision = finger RELEASE: armed (|dX| >= trigger at lift) or a fast flick -> reply.
 * Swipe out, drag back under the trigger, lift = no reply.
 */
public class SwipeReplyHandler extends ItemTouchHelper.Callback {

    // ── Feature flags ──────────────────────────────────────────────────────
    public static boolean ENABLE_SWIPE_REPLY = true;
    public static boolean ENABLE_HAPTICS     = true;
    /** true: reply bar / keyboard start after the row finished springing back (smoother, ~120ms later). */
    public static boolean DEFER_REPLY_UNTIL_RECOVER = true;
    /** Debug builds only: logcat tag SwipePerf, one line per swipe. */
    public static boolean ENABLE_SWIPE_PERF_LOG = true;
    /**
     * EXPERIMENTAL (default off): attachTo() uses an own touch engine instead of ItemTouchHelper.
     * ItemTouchHelper invalidates the whole RecyclerView on every ACTION_MOVE; the light engine moves
     * the row + overlay only. Flip to true, compare the SwipePerf log, and re-test long-press, taps,
     * vertical scroll and horizontal-scrolling children before keeping it on.
     */
    public static boolean USE_LIGHT_ENGINE = false;

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
    private static final long FIRE_SAFETY_MS = RECOVER_MS + 80L;   // fallback if clearView never comes
    private static final long SLOW_FRAME_NS  = 20_000_000L;        // > ~1 dropped frame at 60Hz
    private static final long IDLE_GAP_NS    = 100_000_000L;       // finger still: not a real frame gap
    private static final String PERF_TAG     = "SwipePerf";

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
    private int                   accent = COLOR_FALLBACK;   // chat theme colour (cached; refreshTheme())
    private boolean               dragHeavy;                 // heavy row -> hardware layer used

    // Reveal overlay (chip + icon painted outside RecyclerView's canvas)
    private RevealOverlay overlay;
    private RecyclerView  overlayRv;
    private View          revealItem;
    private float         revealProgress, revealSigned;
    private boolean       revealIsSent;
    private final int[]   locA = new int[2], locB = new int[2];

    // Deferred reply dispatch
    private FireTask pendingFire;

    // Debug perf counters
    private final boolean debuggable;
    private long  perfLastNs, perfWorstNs;
    private int   perfFrames, perfSlow;

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
        debuggable = (ctx.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        accent = readAccent();
    }

    private int readAccent() {
        try { return com.callx.app.utils.ChatThemeManager.get(ctx).getPrimaryColor(); }
        catch (Exception e) { return COLOR_FALLBACK; }
    }

    /** Call when the chat theme colour changes (e.g. onResume); drags never look it up themselves. */
    public void refreshTheme() { accent = readAccent(); }

    /** Rows whose bubble is expensive to re-rasterise: a hardware layer pays off only for these. */
    private static boolean isHeavy(@Nullable Message m) {
        if (m == null || m.type == null) return false;
        switch (m.type) {
            case "image": case "video": case "gif": case "sticker":
            case "multi_media": case "file":
                return true;
            default:
                return false;
        }
    }

    // ── ItemTouchHelper.Callback ───────────────────────────────────────────

    /** ItemTouchHelper.LEFT / RIGHT for this row, or 0 when it must not swipe (shared by both engines). */
    private int swipeFlagsFor(@NonNull RecyclerView.ViewHolder vh) {
        if (!ENABLE_SWIPE_REPLY) return 0;
        if (gate != null && !gate.canSwipe()) return 0;
        Message m = getMessageAt(vh.getBindingAdapterPosition());
        if (!isReplyable(m)) return 0; // deleted / system rows

        boolean isSent = currentUid != null && currentUid.equals(m.senderId);
        boolean isRtl  = vh.itemView.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;

        // Sent -> swipe left; Received -> swipe right (RTL flips)
        if (!isRtl) {
            return isSent ? ItemTouchHelper.LEFT : ItemTouchHelper.RIGHT;
        } else {
            return isSent ? ItemTouchHelper.RIGHT : ItemTouchHelper.LEFT;
        }
    }

    @Override
    public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        final int swipeFlags = swipeFlagsFor(vh);
        if (swipeFlags == 0) return makeMovementFlags(0, 0);
        ensureOverlay(rv);   // touch-down path (not a draw pass) — safe place to add the view
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
        processFrame(c, rv, vh, dX, isCurrentlyActive);
    }

    /** One gesture frame (from ItemTouchHelper's draw pass OR the light engine). c may be null. */
    private void processFrame(@Nullable Canvas c, @NonNull RecyclerView rv,
                              @NonNull RecyclerView.ViewHolder vh, float dX,
                              boolean isCurrentlyActive) {
        final View  item   = vh.itemView;
        final float absDx  = Math.abs(dX);
        perfTick();
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
            if (nowArmed && !armed) {
                fireHaptic(item, false);                     // tick once, as it crosses
                // Arm-time prefetch: decode the reply-bar thumbnail now, while the finger is still
                // down, so it is already in Glide's memory cache when the reply bar shows.
                if (dragHeavy && dragMsg != null) {
                    try { com.callx.app.chat.ui.ReplyPreviewBinder.prefetchThumb(ctx, dragMsg); }
                    catch (Throwable ignored) {}
                }
            }
            armed = nowArmed;                                // dragging back under = disarmed
        } else if (dragging && vh == dragVh) {
            // First non-active frame after the finger lifted = exact release position.
            onRelease(rv, item);
        }

        // Row moves by translationX only — zero layout passes. (Layer: see beginDrag, heavy rows only.)
        if (item.getTranslationX() != signed) SwipeOptimizer.setTranslationXSafe(item, signed);

        // Reveal chip + icon in the strip the row uncovers.
        final long now = SystemClock.uptimeMillis();
        float progress = Math.min(1f, absDx / triggerPx);
        final boolean isDragRow = (vh == dragVh);
        final boolean triggered = isDragRow && (isCurrentlyActive ? armed : fired);
        if (isDragRow) setPopTarget(triggered, now);      // arm -> pop up, disarm -> settle back
        final boolean show = progress > 0.05f;
        if (isDragRow && overlay != null && overlayRv == rv) {
            // Drag row: chip lives on the overlay view, RecyclerView's canvas stays untouched.
            revealItem = item; revealProgress = progress; revealSigned = signed; revealIsSent = dragIsSent;
            updateOverlay(show);
            if (popRunning(now)) overlay.postInvalidateOnAnimation();
        } else {
            // Older row still recovering (or no overlay available): classic in-canvas draw.
            if (show && c != null) {
                boolean isSent = isDragRow ? dragIsSent : isSentRow(vh);
                drawReveal(c, item, progress, signed, isSent, isDragRow ? popAt(now) : 0f);
            }
            if (isDragRow && popRunning(now)) rv.postInvalidateOnAnimation();
        }
    }

    private void beginDrag(RecyclerView.ViewHolder vh, float dX) {
        dragging     = true;
        armed        = false;
        fired        = false;
        parentLocked = false;
        dragVh       = vh;
        dragPos      = vh.getBindingAdapterPosition();
        dragMsg      = getMessageAt(dragPos);   // captured once, used at release
        dragIsSent   = dragMsg != null && currentUid != null && currentUid.equals(dragMsg.senderId);
        // Hardware layer only where re-rasterising the bubble is costly; set once, before the first move.
        dragHeavy = isHeavy(dragMsg);
        if (dragHeavy && vh.itemView.getLayerType() != View.LAYER_TYPE_HARDWARE) {
            SwipeOptimizer.enableHardwareLayer(vh.itemView);
        }
        perfLastNs = 0L; perfWorstNs = 0L; perfFrames = 0; perfSlow = 0;
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
                final FireTask task = new FireTask(rv, dragMsg, dragPos);
                pendingFire = task;
                // Reply bar + keyboard change layout: let the spring-back finish first
                // (clearView posts the task); the delayed post is only a safety net.
                if (DEFER_REPLY_UNTIL_RECOVER) rv.postDelayed(task, FIRE_SAFETY_MS);
                else rv.post(task);
            }
        }
    }

    @Override
    public void clearView(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
        super.clearView(rv, vh);
        if (vh.itemView.getLayerType() == View.LAYER_TYPE_HARDWARE) {
            SwipeOptimizer.clearHardwareLayer(vh.itemView);
        }
        SwipeOptimizer.springBack(vh.itemView);

        // Fallback: view cleared before any non-active frame was drawn.
        if (dragging && vh == dragVh) onRelease(rv, vh.itemView);

        // Another (older) row finished recovering — keep the current drag's state.
        if (dragVh != null && vh != dragVh) return;

        // Row is back at rest: now (next frame) start the reply bar / keyboard.
        final FireTask task = pendingFire;
        if (task != null) rv.post(task);
        updateOverlay(false);
        revealItem = null;
        perfFlush();

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

    // ── Attach (classic ItemTouchHelper or light engine) ───────────────────

    /** Hooks this handler to the list. Replaces `new ItemTouchHelper(handler).attachToRecyclerView(rv)`. */
    public void attachTo(@NonNull RecyclerView rv) {
        if (USE_LIGHT_ENGINE) {
            rv.addOnItemTouchListener(new LightEngine(rv));
        } else {
            new ItemTouchHelper(this).attachToRecyclerView(rv);
        }
    }

    /**
     * Own touch tracking: decides horizontal-vs-vertical by slop, owns the gesture once horizontal,
     * moves the row (translationX) and the overlay chip, and springs back with a ValueAnimator.
     * Nothing here invalidates the RecyclerView. All reply logic (arm / release / defer / perf)
     * is the same {@link #processFrame} the ItemTouchHelper path uses.
     */
    private final class LightEngine implements RecyclerView.OnItemTouchListener {
        private final RecyclerView rv;
        private final int slop;
        private RecyclerView.ViewHolder cand;
        private int   candFlags;
        private float downX, downY, rawDx;
        private int   pointerId = -1;
        private boolean engaged;
        private ValueAnimator recover;

        LightEngine(RecyclerView rv) {
            this.rv = rv;
            this.slop = ViewConfiguration.get(rv.getContext()).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(@NonNull RecyclerView r, @NonNull MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    if (recover != null) recover.end();      // previous row snaps home (reply still fires)
                    cand = null;
                    engaged = false;
                    final View child = r.findChildViewUnder(e.getX(), e.getY());
                    if (child != null) {
                        final RecyclerView.ViewHolder vh = r.getChildViewHolder(child);
                        final int f = swipeFlagsFor(vh);
                        if (f != 0) {
                            cand = vh;
                            candFlags = f;
                            downX = e.getX();
                            downY = e.getY();
                            pointerId = e.getPointerId(0);
                            rawDx = 0f;
                            ensureOverlay(r);
                        }
                    }
                    return false;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (engaged) return true;
                    if (cand == null) return false;
                    final int idx = e.findPointerIndex(pointerId);
                    if (idx < 0) { cand = null; return false; }
                    final float dx = e.getX(idx) - downX, dy = e.getY(idx) - downY;
                    final float adx = Math.abs(dx), ady = Math.abs(dy);
                    if (ady > slop && ady >= adx) { cand = null; return false; }   // vertical: RV scrolls
                    if (adx > slop && adx > ady * 1.2f) {
                        final boolean right = dx > 0f;
                        final int need = right ? ItemTouchHelper.RIGHT : ItemTouchHelper.LEFT;
                        if ((candFlags & need) == 0) { cand = null; return false; }  // wrong direction
                        engaged = true;
                        dragMove(e);          // this event is consumed here, so apply it now
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    cand = null;
                    engaged = false;
                    return false;
                default:
                    return false;
            }
        }

        @Override
        public void onTouchEvent(@NonNull RecyclerView r, @NonNull MotionEvent e) {
            if (!engaged || cand == null) return;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:   dragMove(e); break;
                case MotionEvent.ACTION_UP:     finishGesture(false); break;
                case MotionEvent.ACTION_CANCEL: finishGesture(true);  break;
                default: break;
            }
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(boolean disallow) {
            if (disallow && !engaged) cand = null;           // a child (seekbar, carousel) wants the gesture
        }

        private void dragMove(@NonNull MotionEvent e) {
            final int idx = e.findPointerIndex(pointerId);
            if (idx < 0 || cand == null) return;
            final float dx = e.getX(idx) - downX;
            final boolean right = (candFlags & ItemTouchHelper.RIGHT) != 0;
            rawDx = right ? Math.max(0f, dx) : Math.min(0f, dx);   // one-sided, like ItemTouchHelper
            processFrame(null, rv, cand, rawDx, true);
        }

        private void finishGesture(boolean cancelled) {
            final RecyclerView.ViewHolder vh = cand;
            cand = null;
            engaged = false;
            if (vh == null) return;
            if (cancelled) { armed = false; velocity = 0f; }  // a cancelled gesture never replies
            processFrame(null, rv, vh, rawDx, false);        // release decision happens here
            startRecover(vh, rawDx);
        }

        private void startRecover(@NonNull final RecyclerView.ViewHolder vh, final float from) {
            final ValueAnimator a = ValueAnimator.ofFloat(1f, 0f);
            a.setDuration(RECOVER_MS);
            a.setInterpolator(new DecelerateInterpolator());
            a.addUpdateListener(anim ->
                    processFrame(null, rv, vh, from * (Float) anim.getAnimatedValue(), false));
            a.addListener(new AnimatorListenerAdapter() {
                private boolean done;
                @Override public void onAnimationEnd(Animator animation) {
                    if (done) return;
                    done = true;
                    if (recover == a) recover = null;
                    clearView(rv, vh);                       // layer off, row home, fire reply, hide chip
                }
            });
            recover = a;
            a.start();
        }
    }

    // ── Reveal overlay ─────────────────────────────────────────────────────

    /** Transparent, touch-transparent view in the activity's content frame; paints chip + icon only. */
    private static final class RevealOverlay extends View {
        interface Painter { void paint(Canvas c, RevealOverlay self); }
        Painter painter;
        boolean active;

        RevealOverlay(Context c) {
            super(c);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onDraw(Canvas c) {
            if (active && painter != null) painter.paint(c, this);
        }
    }

    /** Adds the overlay once per RecyclerView. Never called from a draw pass. */
    private void ensureOverlay(@NonNull RecyclerView rv) {
        if (overlay != null && overlayRv == rv && overlay.getParent() != null) return;
        try {
            View root = rv.getRootView();
            View content = root != null ? root.findViewById(android.R.id.content) : null;
            if (!(content instanceof FrameLayout)) { overlay = null; return; }
            if (overlay != null && overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
            final RevealOverlay ov = new RevealOverlay(ctx);
            ov.painter = this::paintOverlay;
            ((FrameLayout) content).addView(ov, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay = ov;
            overlayRv = rv;
            rv.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(@NonNull View v) {}
                @Override public void onViewDetachedFromWindow(@NonNull View v) {
                    v.removeOnAttachStateChangeListener(this);
                    // CRASH FIX (NPE in ViewGroup.dispatchDetachedFromWindow, seen on activity destroy):
                    // this callback runs INSIDE the parent's detach loop (the RecyclerView is a
                    // descendant of android.R.id.content, which is also the overlay's parent).
                    // ViewGroup.dispatchDetachedFromWindow() iterates mChildren with a cached
                    // child COUNT; removing the overlay from `content` right here shrinks
                    // mChildren mid-iteration, so a later index reads null ->
                    // "Attempt to invoke dispatchDetachedFromWindow() on a null object reference".
                    // Rule: never add/remove views of an ancestor from a detach callback.
                    //
                    // So: (1) neutralise the overlay immediately — no hierarchy change, so it is
                    // safe mid-detach: inactive + no painter means onDraw paints nothing and the
                    // activity reference is dropped; (2) remove it from its parent only AFTER the
                    // current detach pass is finished, via a main-looper post (a plain
                    // ov.post() is NOT usable: a detached view only runs posts on re-attach).
                    ov.active = false;
                    ov.painter = null;                      // drop the handler -> activity reference
                    if (overlay == ov) { overlay = null; overlayRv = null; revealItem = null; }
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (ov.getParent() instanceof ViewGroup) ((ViewGroup) ov.getParent()).removeView(ov);
                    });
                }
            });
        } catch (Throwable t) {
            overlay = null;                                  // fall back to in-canvas drawing
            overlayRv = null;
        }
    }

    private void updateOverlay(boolean show) {
        final RevealOverlay ov = overlay;
        if (ov == null) return;
        final boolean was = ov.active;
        ov.active = show;
        if (show || was) ov.invalidate();
    }

    private void paintOverlay(Canvas c, RevealOverlay ov) {
        final RecyclerView rv = overlayRv;
        final View item = revealItem;
        if (rv == null || item == null) return;
        rv.getLocationInWindow(locA);
        ov.getLocationInWindow(locB);
        c.save();
        c.translate(locA[0] - locB[0], locA[1] - locB[1]);
        c.clipRect(0, 0, rv.getWidth(), rv.getHeight());
        drawReveal(c, item, revealProgress, revealSigned, revealIsSent,
                popAt(SystemClock.uptimeMillis()));
        c.restore();
    }

    // ── Deferred reply dispatch ────────────────────────────────────────────

    private final class FireTask implements Runnable {
        private final RecyclerView rv;
        private final Message msg;
        private final int pos;
        private boolean done;

        FireTask(RecyclerView rv, Message msg, int pos) { this.rv = rv; this.msg = msg; this.pos = pos; }

        @Override public void run() {
            if (done) return;
            done = true;
            rv.removeCallbacks(this);
            if (pendingFire == this) pendingFire = null;
            if (!rv.isAttachedToWindow()) return;           // screen gone while the row was recovering
            if (listener != null) listener.onSwipeReply(msg, pos);
        }
    }

    // ── Debug perf log (debuggable builds only) ────────────────────────────

    private void perfTick() {
        if (!debuggable || !ENABLE_SWIPE_PERF_LOG) return;
        final long n = System.nanoTime();
        if (perfLastNs != 0L) {
            final long d = n - perfLastNs;
            if (d < IDLE_GAP_NS) {                           // longer gaps = finger resting, not a frame
                perfFrames++;
                if (d > SLOW_FRAME_NS) perfSlow++;
                if (d > perfWorstNs) perfWorstNs = d;
            }
        }
        perfLastNs = n;
    }

    private void perfFlush() {
        if (!debuggable || !ENABLE_SWIPE_PERF_LOG || perfFrames == 0) return;
        Log.d(PERF_TAG, String.format(Locale.US,
                "swipe: frames=%d slow(>20ms)=%d worstGap=%.1fms heavyRow=%b overlay=%b",
                perfFrames, perfSlow, perfWorstNs / 1_000_000f, dragHeavy, overlay != null));
        perfFrames = 0; perfSlow = 0; perfWorstNs = 0L; perfLastNs = 0L;
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
