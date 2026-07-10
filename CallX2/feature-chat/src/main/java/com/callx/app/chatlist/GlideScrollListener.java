package com.callx.app.chatlist;

import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;

/**
 * GlideScrollListener — v83 Telegram-level scroll performance.
 *
 * WHY THIS EXISTS
 * ───────────────
 * Glide decodes bitmaps on a background thread pool. During a fast fling on
 * a chat list, dozens of avatar load requests are fired sequentially as rows
 * come into view. Those decode jobs compete for CPU with the RenderThread and
 * the UI thread, causing jank on mid-range devices.
 *
 * Telegram solves this by pausing image loading during a fling and resuming
 * once scrolling settles. This listener does the same:
 *
 *   SCROLL_STATE_IDLE       → resumeRequests()   (fill in any missing avatars)
 *   SCROLL_STATE_DRAGGING   → resumeRequests()   (user is in control, load normally)
 *   SCROLL_STATE_SETTLING   → pauseRequests()    (fast fling — don't decode now)
 *
 * Attach once to your RecyclerView:
 *   rv.addOnScrollListener(new GlideScrollListener(context));
 */
public class GlideScrollListener extends RecyclerView.OnScrollListener {

    private final RequestManager glide;

    public GlideScrollListener(Context context) {
        this.glide = Glide.with(context.getApplicationContext());
    }

    @Override
    public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
        switch (newState) {
            case RecyclerView.SCROLL_STATE_SETTLING:
                // Fast fling — pause bitmap decoding to reduce CPU contention
                glide.pauseRequests();
                break;
            case RecyclerView.SCROLL_STATE_DRAGGING:
            case RecyclerView.SCROLL_STATE_IDLE:
                // User is in control or scroll stopped — resume loading
                glide.resumeRequests();
                break;
        }
    }
}
