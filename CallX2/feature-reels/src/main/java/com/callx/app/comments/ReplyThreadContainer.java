package com.callx.app.comments;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.callx.app.reels.R;

/**
 * The replies container of a comment row. It mirrors its own visibility onto
 * the parent-row trunk (R.id.view_thread_trunk, the short line under the
 * parent avatar), so the trunk appears/disappears with the replies from every
 * code path (fragment expand/collapse, adapter rebind, reply posted) without
 * each caller having to remember it.
 */
public class ReplyThreadContainer extends LinearLayout {

    @Nullable private View trunk;

    public ReplyThreadContainer(Context c) { super(c); }
    public ReplyThreadContainer(Context c, @Nullable AttributeSet a) { super(c, a); }

    /** Parent-row trunk that sits beside the main comment (null before attach). */
    @Nullable
    public View getTrunk() {
        if (trunk == null) {
            ViewParent p = getParent();
            if (p instanceof View) trunk = ((View) p).findViewById(R.id.view_thread_trunk);
        }
        return trunk;
    }

    /** Stops any fade running on the trunk and restores full alpha (row recycle). */
    public void resetTrunkAnim() {
        View t = getTrunk();
        if (t != null) { t.animate().cancel(); t.setAlpha(1f); }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView != this) return;
        View t = getTrunk();
        if (t != null) t.setVisibility(visibility == VISIBLE ? VISIBLE : GONE);
    }
}
