package com.callx.app.conversation.controllers;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;

import androidx.asynclayoutinflater.appcompat.AsyncLayoutInflater;

import com.bumptech.glide.Glide;
import com.callx.app.chat.R;

/**
 * PERF (advanced optimization #7): pre-inflates MediaEditActivity's root
 * layout (activity_media_edit.xml) on a background thread the moment a
 * camera capture finishes, instead of leaving that inflate to happen
 * synchronously on the main thread inside MediaEditActivity#onCreate() —
 * exactly when the capture→edit transition animation is trying to run.
 *
 * activity_media_edit.xml is a big screen (top toolbar, media preview
 * surface, filter strip, draw tools, emoji row, sticker layer, bottom bar,
 * thumb strip...) so its first inflate is real, one-shot, main-thread work.
 * The tree itself doesn't depend on which photo/video was captured — that's
 * bound in afterwards from Intent extras in onCreate() — so it's safe to
 * build ahead of time and just hand the finished View to the real Activity.
 *
 * Uses androidx.asynclayoutinflater's AppCompat-aware inflater (NOT plain
 * android.view.AsyncLayoutInflater / androidx.asynclayoutinflater.view). The
 * plain inflater skips AppCompat's view-inflation factory, so widgets like
 * <Button>/<ImageView> would come back as their raw framework versions
 * instead of the tinted AppCompat/Material equivalents setContentView()
 * normally swaps them for — this variant installs that factory itself, so
 * the pre-built tree looks identical to a normal inflate.
 *
 * ChatCameraActivity and MediaEditActivity already share the exact same
 * manifest theme (Theme.AppCompat.NoActionBar), so pre-inflating against
 * that theme via an application-Context ContextThemeWrapper — before a real
 * MediaEditActivity Context even exists — resolves attrs/styles identically
 * to how MediaEditActivity's own setContentView() would.
 */
public final class MediaEditPreloadCache {

    private static final String TAG = "MediaEditPreloadCache";

    private static volatile View cachedRoot;
    private static volatile boolean warming;

    private MediaEditPreloadCache() {}

    /** Call as soon as a capture (photo or video) has finished — see
     *  ChatCameraActivity#finishWithResult() — so the inflate gets the
     *  longest possible head start before MediaEditActivity actually
     *  starts. Safe to call more than once; only one warm-up is ever in
     *  flight, and an already-cached, not-yet-consumed tree is left alone. */
    public static void warmUp(Context context) {
        if (cachedRoot != null || warming) return;
        synchronized (MediaEditPreloadCache.class) {
            if (cachedRoot != null || warming) return;
            warming = true;
            try {
                Context themed = new ContextThemeWrapper(
                        context.getApplicationContext(), R.style.Theme_AppCompat_NoActionBar);
                new AsyncLayoutInflater(themed).inflate(
                        R.layout.activity_media_edit, null,
                        (view, resid, parent) -> {
                            cachedRoot = view;
                            warming = false;
                            Log.d(TAG, "activity_media_edit pre-inflated off-thread");
                        });
            } catch (Exception e) {
                // A warm-up failure should never affect the real flow —
                // onCreate() just falls back to its normal synchronous
                // inflate via take() returning null.
                Log.w(TAG, "pre-inflate failed, MediaEditActivity will inflate normally", e);
                warming = false;
            }
        }
    }

    /** MediaEditActivity#onCreate() calls this instead of going straight to
     *  setContentView(R.layout.activity_media_edit). Returns the pre-built
     *  tree if the warm-up finished in time, else null (caller falls back
     *  to a normal synchronous inflate — e.g. MediaEditActivity opened from
     *  the gallery-attach flow instead of a fresh camera capture, or the
     *  async inflate just hadn't finished yet). Consumes the cached tree —
     *  a given pre-inflated View is never handed out twice. */
    public static View take() {
        View root = cachedRoot;
        cachedRoot = null;
        return root;
    }

    /**
     * Companion to warmUp() — call alongside it right after a capture
     * finishes (see ChatCameraActivity#finishWithResult()). Speculatively
     * populates Glide's memory cache with the captured photo/video at the
     * exact two sizes MediaEditActivity will actually request it at once
     * it opens:
     *
     *  - MediaEditActivity#filterThumbSizePx() — the filter-strip thumbs
     *    (refreshFilterThumbs(), one Glide load per filter, all sharing
     *    this one decode once cached) — only paid the first time the user
     *    opens the filter panel, but that first open is exactly when a
     *    cold multi-thumbnail decode would otherwise show up as a stutter.
     *  - MediaEditActivity.THUMB_STRIP_SIZE_PX — the bottom multi-item
     *    thumb strip (rebuildThumbStrip()), built once on onCreate itself.
     *
     * Both call sites use the identical .centerCrop().override(size, size)
     * combo used here — the whole Uri+transformation+size triple has to
     * match exactly for Glide's cache to actually hit, not just the Uri.
     * Fire-and-forget: preload() only populates the cache, never touches
     * an ImageView, and a failed/slow preload simply means those loads
     * fall back to a normal fresh decode later — never blocks anything.
     */
    public static void preloadThumbnails(Context context, Uri uri) {
        try {
            Context app = context.getApplicationContext();
            int filterSizePx = MediaEditActivity.filterThumbSizePx(app);
            Glide.with(app).load(uri).centerCrop()
                    .override(filterSizePx, filterSizePx).preload();
            Glide.with(app).load(uri).centerCrop()
                    .override(MediaEditActivity.THUMB_STRIP_SIZE_PX, MediaEditActivity.THUMB_STRIP_SIZE_PX)
                    .preload();
        } catch (Exception e) {
            Log.w(TAG, "thumbnail preload failed, will decode normally on demand", e);
        }
    }
}
