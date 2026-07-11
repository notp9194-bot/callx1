package com.callx.app.conversation.emptystate;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Gap #4 fix: Telegram never keeps every visible lottie animation running
 * at full tilt during a fast fling — it pauses playback while the list is
 * moving and resumes once it settles, so scroll perf never competes with
 * decode/render work.
 *
 * This app currently only has ONE RLottieViewWrapper on screen at a time
 * (the empty-chat wave), so this pool's practical effect today is small.
 * It exists so that when animated stickers / animated emoji reactions land
 * INSIDE message bubbles (RecyclerView ViewHolders), the wiring is already
 * there — a ViewHolder just needs to:
 *
 *   onBindViewHolder:  pool.register(lottieWrapper)
 *   onViewRecycled:    pool.unregister(lottieWrapper); lottieWrapper.release()
 *
 * and scrolling will automatically pause/resume it — no extra ViewHolder
 * bookkeeping required.
 *
 * WeakHashMap so a ViewHolder recycled without calling unregister() (a bug
 * elsewhere) can't leak the view or crash this pool.
 */
public class RLottiePlaybackPool {

    private final Set<RLottieViewWrapper> activeViews =
            Collections.newSetFromMap(new WeakHashMap<>());

    public void register(RLottieViewWrapper view) {
        if (view != null) activeViews.add(view);
    }

    public void unregister(RLottieViewWrapper view) {
        if (view != null) activeViews.remove(view);
    }

    public void pauseAll() {
        for (RLottieViewWrapper v : activeViews) v.pause();
    }

    public void resumeAll() {
        for (RLottieViewWrapper v : activeViews) v.resume();
    }

    /**
     * Convenience: attach directly to a RecyclerView. Pauses on drag/fling,
     * resumes once the list is idle again.
     */
    public RecyclerView.OnScrollListener attachTo(RecyclerView recyclerView) {
        RecyclerView.OnScrollListener listener = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(androidx.annotation.NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    resumeAll();
                } else {
                    // DRAGGING or SETTLING (fling) — both are "list is moving"
                    pauseAll();
                }
            }
        };
        recyclerView.addOnScrollListener(listener);
        return listener;
    }
}
