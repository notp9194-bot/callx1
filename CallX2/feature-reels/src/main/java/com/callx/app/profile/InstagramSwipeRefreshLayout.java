package com.callx.app.profile;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.AppBarLayout;

/**
 * SwipeRefreshLayout wrapping a header+tabs+grid CoordinatorLayout (see
 * activity_user_reels.xml) needs to gate pull-to-refresh on whether the
 * REAL scrollable content — the header (via AppBarLayout) and the active
 * grid RecyclerView, both several levels down — is scrolled to the very
 * top. Not this widget's direct child.
 *
 * BUG this fixes: stock SwipeRefreshLayout.canChildScrollUp() only calls
 * ViewCompat.canScrollVertically(-1) on its immediate child. Here that
 * child is a plain CoordinatorLayout, which is never itself scrollable —
 * so the stock check always reports "can't scroll up" (i.e. "already at
 * the top"), and pull-to-refresh fires on ANY downward drag, no matter how
 * far down the grid actually is. Overriding canChildScrollUp() to look at
 * the AppBarLayout's real offset and the active RecyclerView's real scroll
 * position is what Instagram effectively does: refresh only triggers when
 * the header is fully expanded AND the grid is at position 0.
 */
public class InstagramSwipeRefreshLayout extends SwipeRefreshLayout {

    /** Supplies whichever RecyclerView (Reels grid or Series grid) is the currently visible/active one. */
    public interface ActiveScrollTargetProvider {
        View getActiveScrollTarget();
    }

    private AppBarLayout appBarLayout;
    private ActiveScrollTargetProvider activeScrollTargetProvider;

    public InstagramSwipeRefreshLayout(Context context) {
        super(context);
    }

    public InstagramSwipeRefreshLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    /** Must be called once (e.g. in onCreate, right after both views are bound). */
    public void setAppBarLayout(AppBarLayout appBarLayout) {
        this.appBarLayout = appBarLayout;
    }

    /** Must be called once. The lambda should always return whichever grid is currently showing. */
    public void setActiveScrollTargetProvider(ActiveScrollTargetProvider provider) {
        this.activeScrollTargetProvider = provider;
    }

    @Override
    public boolean canChildScrollUp() {
        // Header not fully expanded (mid-collapse or fully collapsed) —
        // there is definitely more content above the fold, so this is NOT
        // "the top" of the screen yet. getTop() is 0 only when fully
        // expanded; it goes negative as the header collapses.
        if (appBarLayout != null && appBarLayout.getTop() < 0) {
            return true;
        }
        // Header is fully expanded — now check whether the grid itself
        // still has content above its current scroll position.
        View target = (activeScrollTargetProvider != null) ? activeScrollTargetProvider.getActiveScrollTarget() : null;
        if (target != null && target.canScrollVertically(-1)) {
            return true;
        }
        // Header fully expanded AND grid at position 0 — this really is
        // the top of the whole screen. Safe to let a pull trigger refresh.
        return false;
    }
}
