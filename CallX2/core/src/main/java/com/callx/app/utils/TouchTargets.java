package com.callx.app.utils;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;

import java.util.ArrayList;

/**
 * Grows a small view's TOUCH area to at least 48dp without changing its size,
 * position or the surrounding layout (Material / Android accessibility minimum).
 *
 * Use this for dense stacks (reel action column, feed-card action rows, camera
 * tool rails) where making the view itself 48dp would push neighbours around.
 * For isolated buttons just make the view 48dp in XML instead.
 *
 * One composite TouchDelegate is kept per parent (Android allows only one
 * delegate per view), and bounds are recomputed on every layout pass, so it is
 * safe in RecyclerView holders: call once when the holder is created.
 */
public final class TouchTargets {

    private static final int MIN_DP = 48;

    private TouchTargets() {}

    /** Expand the given child ids (looked up under root) to a 48dp touch area. */
    public static void expand(@NonNull View root, @IdRes int... ids) {
        for (int id : ids) {
            View v = root.findViewById(id);
            if (v != null) expand(v);
        }
    }

    /** Expand one view to a 48dp touch area. */
    public static void expand(@NonNull final View v) {
        v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                update(view);
            }
        });
        if (v.isLaidOut()) update(v);
    }

    private static void update(View v) {
        if (!(v.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) v.getParent();
        int w = v.getWidth(), h = v.getHeight();
        if (w <= 0 || h <= 0) return;
        float d = v.getResources().getDisplayMetrics().density;
        int min = Math.round(MIN_DP * d);
        int extraW = Math.max(0, (min - w + 1) / 2);
        int extraH = Math.max(0, (min - h + 1) / 2);
        if (extraW == 0 && extraH == 0) return;
        Rect r = new Rect(v.getLeft() - extraW, v.getTop() - extraH,
                v.getRight() + extraW, v.getBottom() + extraH);
        // a delegate can only receive touches that land inside its parent
        r.left = Math.max(r.left, 0);
        r.top = Math.max(r.top, 0);
        r.right = Math.min(r.right, parent.getWidth());
        r.bottom = Math.min(r.bottom, parent.getHeight());
        Group g = (Group) parent.getTag(com.callx.app.core.R.id.touch_targets_group);
        if (g == null) {
            g = new Group(parent);
            parent.setTag(com.callx.app.core.R.id.touch_targets_group, g);
            parent.setTouchDelegate(g);
        }
        g.set(v, r);
    }

    private static final class Group extends TouchDelegate {
        private final ArrayList<View> targets = new ArrayList<>();
        private final ArrayList<Rect> rects = new ArrayList<>();
        private View active;

        Group(View parent) {
            super(new Rect(), parent);
        }

        void set(View v, Rect r) {
            int i = targets.indexOf(v);
            if (i < 0) {
                targets.add(v);
                rects.add(new Rect(r));
            } else {
                rects.get(i).set(r);
            }
        }

        @Override
        public boolean onTouchEvent(@NonNull MotionEvent e) {
            int x = (int) e.getX(), y = (int) e.getY();
            View t;
            boolean inside;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    active = null;
                    for (int i = 0; i < targets.size(); i++) {
                        View c = targets.get(i);
                        if (c.getVisibility() == View.VISIBLE && c.isEnabled()
                                && rects.get(i).contains(x, y)) {
                            // a touch directly on a different clickable child wins
                            active = c;
                            break;
                        }
                    }
                    if (active == null) return false;
                    t = active;
                    inside = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    t = active;
                    if (t == null) return false;
                    inside = rects.get(targets.indexOf(t)).contains(x, y);
                    break;
                default:
                    return false;
            }
            boolean ending = e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL;
            MotionEvent ev = MotionEvent.obtain(e);
            if (inside) {
                ev.setLocation(t.getWidth() / 2f, t.getHeight() / 2f);
            } else {
                ev.setLocation(-10000f, -10000f);
            }
            boolean handled = t.dispatchTouchEvent(ev);
            ev.recycle();
            if (ending) active = null;
            return handled;
        }
    }
}
