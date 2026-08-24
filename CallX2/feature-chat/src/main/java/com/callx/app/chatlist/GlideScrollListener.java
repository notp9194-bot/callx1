package com.callx.app.chatlist;

import android.content.Context;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;

/**
 * GlideScrollListener — v87 Ultra Pro Max: hardware layer toggle + Glide pause.
 *
 * v85: Glide pause during SETTLING, resume on IDLE/DRAGGING.
 * v86: hardware layer promoted on SETTLING (WRONG — see v87 fix below).
 *
 * v87 FIX — root cause of the fling "jhatka":
 *    setLayerType(HARDWARE) is not instant. The *first* draw after the call
 *    is a synchronous full software draw pass of the view, uploaded as a GPU
 *    texture — only frames *after* that are cheap composites. v86 triggered
 *    this upload exactly on SCROLL_STATE_SETTLING, which is the frame fling
 *    velocity peaks at (RecyclerView enters SETTLING right as the finger
 *    leaves the screen with full fling velocity). Promoting every visible
 *    row to a hardware layer at that exact moment meant N synchronous texture
 *    uploads landing on the single fastest-moving frame — the stutter felt
 *    right at the start of the fling.
 *
 *    Fix (same idea as Reels' beginFeedScrollLayer(), which promotes on the
 *    first scrolled pixel instead of on fling-settle): promote to HARDWARE
 *    on the very first onScrolled() delta, i.e. during the low-velocity drag
 *    phase, well before SETTLING/fling ever begins. By the time RecyclerView
 *    transitions to SETTLING and velocity peaks, every row's GPU texture is
 *    already uploaded and warm — SETTLING then costs only cheap composites,
 *    zero uploads.
 *
 *    • onScrolled() (first delta after IDLE) → setLayerType(HARDWARE) on all
 *      visible children, while velocity is still low (drag phase).
 *    • SCROLL_STATE_SETTLING  → layer already warm; just pause Glide decode
 *      so decode work doesn't compete with the GPU during fling.
 *    • SCROLL_STATE_IDLE      → setLayerType(NONE) to free GPU texture memory
 *      and resume normal software draws (typing indicator / tick updates)
 *      and resume Glide loading.
 */
public class GlideScrollListener extends RecyclerView.OnScrollListener {

    private final RequestManager glide;
    private boolean hwLayerOn = false;

    public GlideScrollListener(Context context) {
        this.glide = Glide.with(context.getApplicationContext());
    }

    @Override
    public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
        // Promote at the very first pixel of movement — still low velocity
        // (drag phase) — so the one-time GPU texture upload per row happens
        // here instead of at fling-peak (SETTLING).
        if (!hwLayerOn) {
            hwLayerOn = true;
            setChildLayerType(recyclerView, View.LAYER_TYPE_HARDWARE);
        }
    }

    @Override
    public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
        switch (newState) {
            case RecyclerView.SCROLL_STATE_SETTLING:
                // Fling in progress: textures already warm from onScrolled()
                // above — just pause Glide decoding so it doesn't compete
                // with GPU compositing on this frame.
                glide.pauseRequests();
                break;

            case RecyclerView.SCROLL_STATE_DRAGGING:
                // User back in control after a fling-interrupt: keep the
                // hardware layer (still cheap/warm), just resume loading.
                glide.resumeRequests();
                break;

            case RecyclerView.SCROLL_STATE_IDLE:
                // Scroll settled: resume loading remaining avatars, drop GPU
                // textures, and reset so the next scroll re-promotes early.
                glide.resumeRequests();
                setChildLayerType(recyclerView, View.LAYER_TYPE_NONE);
                hwLayerOn = false;
                break;
        }
    }

    private static void setChildLayerType(RecyclerView rv, int layerType) {
        int count = rv.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = rv.getChildAt(i);
            if (child != null) child.setLayerType(layerType, null);
        }
    }
}
