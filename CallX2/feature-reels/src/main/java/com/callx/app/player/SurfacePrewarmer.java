package com.callx.app.player;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SurfacePrewarmer — PERF advance: "Surface pre-warm", the companion to
 * {@link GpuDecodeWarmup}.
 *
 * GpuDecodeWarmup deliberately warms the MediaCodec/EGL driver paths
 * WITHOUT ever touching a real Surface/SurfaceView/TextureView (see its
 * class doc). That leaves one more per-process cold-start cost on the
 * table: the very first real SurfaceView any feed row in this process ever
 * attaches — Home's item_home_feed_post PlayerView (surface_type=
 * "surface_view"), or the Reels tab's own — pays for:
 *   - class loading + verification of android.view.SurfaceView and the
 *     SurfaceControl/BLASTBufferQueue machinery behind it,
 *   - this process's first binder handshake with SurfaceFlinger to
 *     register a new layer.
 * Every SurfaceView created afterwards in the same process skips both —
 * same principle as GpuDecodeWarmup's codec/EGL warm-up, just for the
 * Surface side of first-frame latency instead of the decode side.
 *
 * Important scope note: this does NOT — and cannot — pre-create the exact
 * Surface a specific feed card will render into. RecyclerView rows come
 * and go, and every PlayerView owns its own independent SurfaceView
 * instance; there is no single Surface to hand around between them. What
 * this removes is the one-time per-process setup cost described above, so
 * that first real row's own surfaceCreated() fires sooner and more
 * consistently — which is exactly what
 * {@code revealCardThumbnailAfterFirstFrame()}'s crossfade timing is
 * sensitive to on a cold app start (an unpredictable extra 1-2 frames of
 * delay before the first real Surface exists reads as a jump instead of a
 * clean crossfade).
 *
 * Implementation: a 1x1px SurfaceView, fully attached and VISIBLE (not
 * merely inflated — visibility gates whether the platform actually
 * allocates the Surface), parked permanently in the host Activity's window
 * decor view. Small enough to be a visual no-op, real enough that
 * surfaceCreated() genuinely fires and the above cost is genuinely paid.
 * Runs at most once per process (mirrors GpuDecodeWarmup's `started`
 * guard) and is intentionally never torn down — same lifetime as the
 * warmed-up codec/EGL state it complements, so a later tab switch or
 * fragment recreation never re-pays this cost.
 */
public final class SurfacePrewarmer {

    private static final String TAG = "SurfacePrewarmer";
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private SurfacePrewarmer() {}

    /**
     * Fire-and-forget — call from HomeFragment.onCreateView() (and safe to
     * also call from ReelsFragment.onCreateView(); whichever tab the user
     * opens first pays the one-time cost, exactly like
     * {@link GpuDecodeWarmup#warmUpOnce}). Later calls in the same process
     * are free no-ops.
     */
    public static void warmUpOnce(Context context) {
        if (!started.compareAndSet(false, true)) return; // already warmed/warming this process

        Activity activity = unwrapActivity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            // Not a real chance to attach a view right now (e.g. an
            // Application/Service context, or the activity is on its way
            // out) — don't burn the one-shot guard on a no-op attempt; let
            // a later, real call retry.
            started.set(false);
            return;
        }

        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            SurfaceView probe = new SurfaceView(activity);
            probe.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                    Log.d(TAG, "warmUpOnce: probe surfaceCreated — Surface pipeline is warm");
                }
                @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
                @Override public void surfaceDestroyed(SurfaceHolder holder) {}
            });
            decor.addView(probe, new FrameLayout.LayoutParams(1, 1));
        } catch (Exception e) {
            // Never let a warm-up probe crash the app — worst case the
            // first real reel/post just pays the normal cold-start cost,
            // same as before this class existed.
            Log.w(TAG, "warmUpOnce: failed, first real card pays normal cold-start cost: " + e.getMessage());
        }
    }

    private static Activity unwrapActivity(Context context) {
        Context c = context;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return (c instanceof Activity) ? (Activity) c : null;
    }
}
