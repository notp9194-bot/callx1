package com.callx.app.chatv2;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.OverScroller;

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
 */
public class FastChatSurfaceView extends GLSurfaceView {

    private final FastChatGLRenderer renderer;
    private final GestureDetector gestureDetector;
    private final OverScroller scroller;
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
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                scrollBy(dy);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                float maxScroll = renderer.getEngine().nativeGetContentHeight();
                scroller.fling(0, (int) renderer.getEngine().nativeGetScrollY(),
                        0, (int) -vy,
                        0, 0, 0, (int) maxScroll);
                Choreographer.getInstance().postFrameCallback(flingCallback);
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float y = renderer.getEngine().nativeGetScrollY() + e.getY();
                renderer.getEngine().nativeHitTest(e.getX(), y);
                return true;
            }
        });
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
