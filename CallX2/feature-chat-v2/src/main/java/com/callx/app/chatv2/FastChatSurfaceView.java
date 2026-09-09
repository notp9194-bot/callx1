package com.callx.app.chatv2;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.OverScroller;

import androidx.annotation.Nullable;

/**
 * GLSurfaceView subclass that owns touch → scroll translation. Scroll
 * offset lives in native memory (NativeChatEngine.nativeSetScrollY) so
 * the GL thread never blocks on the UI thread for it.
 *
 * IMPORTANT: fling animation is driven via Choreographer.FrameCallback,
 * NOT View.computeScroll(). GLSurfaceView/SurfaceView render to their
 * own dedicated Surface and the framework frequently skips their normal
 * View.draw() dispatch as an optimization — which means computeScroll()
 * (only invoked from within that dispatch) never reliably fires, and an
 * OverScroller-based fling silently never animates. Choreographer sits
 * outside that path entirely, so it's not affected.
 *
 * Phase 4: also owns the touch → message-interaction translation
 * (long-press for reactions, horizontal swipe for reply) on top of the
 * existing scroll/fling handling — hit testing itself stays native
 * (NativeChatEngine.nativeHitTestId), this class just decides which
 * gesture a touch sequence turned into.
 */
public class FastChatSurfaceView extends GLSurfaceView {

    /** messageId + view-local touch coords, for anchoring a reaction popup. */
    public interface OnMessageLongPressListener {
        void onMessageLongPress(String messageId, float viewX, float viewY);
    }

    /** Horizontal swipe-to-reply on a bubble. */
    public interface OnMessageSwipeListener {
        void onMessageSwipe(String messageId);
    }

    // A deliberate horizontal drag has to clearly dominate over vertical
    // motion before it's treated as "swipe to reply" instead of scroll —
    // this is what lets a slightly-diagonal scroll gesture stay a scroll.
    private static final float SWIPE_TRIGGER_PX = 80f;
    private static final float SWIPE_DOMINANCE_RATIO = 1.5f;

    private final FastChatGLRenderer renderer;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;

    private OnMessageLongPressListener longPressListener;
    private OnMessageSwipeListener swipeListener;

    // Per-gesture swipe tracking, reset on ACTION_DOWN.
    private String downMessageId;
    private float accumDx = 0f, accumDy = 0f;
    private boolean swipeTriggered = false;

    private final Choreographer.FrameCallback flingCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (scroller.computeScrollOffset()) {
                renderer.getEngine().nativeSetScrollY(scroller.getCurrY());
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    public FastChatSurfaceView(Context context, FastChatGLRenderer renderer) {
        super(context);
        this.renderer = renderer;
        this.scroller = new OverScroller(context);

        setEGLContextClientVersion(2);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                downMessageId = hitTestId(e.getX(), e.getY());
                accumDx = 0f;
                accumDy = 0f;
                swipeTriggered = false;
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                accumDx += dx;
                accumDy += dy;

                if (swipeTriggered) {
                    // Gesture already resolved to a swipe this touch
                    // sequence — don't also scroll the list underneath it.
                    return true;
                }

                if (downMessageId != null
                        && Math.abs(accumDx) > SWIPE_TRIGGER_PX
                        && Math.abs(accumDx) > Math.abs(accumDy) * SWIPE_DOMINANCE_RATIO) {
                    swipeTriggered = true;
                    if (swipeListener != null) swipeListener.onMessageSwipe(downMessageId);
                    return true;
                }

                scrollBy(dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (swipeTriggered) return true;
                float maxScroll = renderer.getEngine().nativeGetContentHeight();
                scroller.fling(0, (int) renderer.getEngine().nativeGetScrollY(),
                        0, (int) -vy,
                        0, 0, 0, (int) maxScroll);
                Choreographer.getInstance().postFrameCallback(flingCallback);
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                hitTestId(e.getX(), e.getY());
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                String id = hitTestId(e.getX(), e.getY());
                if (id != null && longPressListener != null) {
                    longPressListener.onMessageLongPress(id, e.getX(), e.getY());
                }
            }
        });
    }

    public void setOnMessageLongPressListener(OnMessageLongPressListener l) {
        this.longPressListener = l;
    }

    public void setOnMessageSwipeListener(OnMessageSwipeListener l) {
        this.swipeListener = l;
    }

    @Nullable
    private String hitTestId(float viewX, float viewY) {
        float contentY = renderer.getEngine().nativeGetScrollY() + viewY;
        return renderer.getEngine().nativeHitTestId(viewX, contentY);
    }

    private void scrollBy(float dy) {
        float current = renderer.getEngine().nativeGetScrollY();
        renderer.getEngine().nativeSetScrollY(current + dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        getParent().requestDisallowInterceptTouchEvent(true);
        if (event.getAction() == MotionEvent.ACTION_DOWN && !scroller.isFinished()) {
            scroller.abortAnimation();
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }
}
