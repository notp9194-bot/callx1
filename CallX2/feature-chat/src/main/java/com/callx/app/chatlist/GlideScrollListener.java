package com.callx.app.chatlist;

import android.content.Context;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;

/**
 * GlideScrollListener — v86 Ultra Pro Max: hardware layer toggle + Glide pause.
 *
 * v85: Glide pause during SETTLING, resume on IDLE/DRAGGING.
 *
 * v86 ADDITIONS:
 *  Hardware layer during fling (same technique as Telegram's LegacyListView):
 *    • SCROLL_STATE_SETTLING → setLayerType(HARDWARE) on all visible children.
 *      The GPU composites the pre-rendered row textures without involving the CPU
 *      or the RenderThread per frame — this is why Telegram feels buttery at 60fps
 *      even on mid-range phones. Rows that don't change during scroll are perfect
 *      candidates for hardware layer caching.
 *    • SCROLL_STATE_IDLE     → setLayerType(NONE) to free GPU texture memory and
 *      allow normal software draws again (needed for typing indicator / tick updates).
 *    • SCROLL_STATE_DRAGGING → setLayerType(NONE); user may pause and the content
 *      needs to update normally (new Firebase messages, typing).
 *
 * setLayerType(HARDWARE) cost:
 *    First call uploads each view's bitmap to a GPU texture (one-time cost).
 *    Subsequent frames during the fling just composite — O(1) GPU work regardless
 *    of view complexity. Combined with our pre-baked canvas paths and zero-alloc
 *    onDraw(), the upload is trivially fast.
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
                // Fast fling: pause Glide decoding + hand off to GPU compositing
                glide.pauseRequests();
                setChildLayerType(recyclerView, View.LAYER_TYPE_HARDWARE);
                break;

            case RecyclerView.SCROLL_STATE_DRAGGING:
                // User in control: resume loading, drop hardware layer (content can update)
                glide.resumeRequests();
                setChildLayerType(recyclerView, View.LAYER_TYPE_NONE);
                break;

            case RecyclerView.SCROLL_STATE_IDLE:
                // Scroll settled: resume loading remaining avatars, drop GPU textures
                glide.resumeRequests();
                setChildLayerType(recyclerView, View.LAYER_TYPE_NONE);
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
