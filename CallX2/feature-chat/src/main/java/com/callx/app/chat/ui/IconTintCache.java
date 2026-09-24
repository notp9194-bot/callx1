package com.callx.app.chat.ui;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;

import java.util.concurrent.ConcurrentHashMap;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

/**
 * ULTRA-OPT: process-wide static cache for tinted icon bitmaps, shared by
 * every chat screen (ChatIconBarView's attach/camera/mic/send icons,
 * MessageBubbleCanvasView's call-entry icon, and any other
 * statically-tinted icon like btn_view_once).
 *
 * Before this, each screen either re-tinted its own Drawable on every
 * Activity creation (ContextCompat.getDrawable + mutate + setTint + draw —
 * real work: allocation, drawable state mutation, a Canvas draw pass) or,
 * for XML android:tint icons, leaned on AppCompat's internal tint cache
 * (present, but not guaranteed across OEM/AppCompat versions and not
 * inspectable/controllable by us).
 *
 * Here one explicit Bitmap per (resId, color, size, night-mode) is drawn
 * ONCE per process and reused by every chat screen for the rest of the
 * app's life — 2nd, 3rd, ... Nth chat open pays zero icon-tint cost.
 * Keyed on night-mode too so a day/night switch regenerates instead of
 * reusing a stale-colored bitmap (see ChatIconBarView's same fix).
 *
 * LOCK-FREE READS: backed by ConcurrentHashMap instead of a
 * synchronized-SparseArray. get() on a hit is a single lock-free map read
 * (ConcurrentHashMap.get() never blocks, even during a concurrent write)
 * — call sites like MessageBubbleCanvasView.bindCallEntry() run this on
 * every RecyclerView rebind, so removing the synchronized block removes a
 * (rarely-contended but non-zero) monitor acquisition from that hot path.
 * Insert uses putIfAbsent, which is also lock-free on the read side; on a
 * cold miss two threads can in theory both build a bitmap for the same key
 * and one loses the race — cheap, one-time-per-key, and never observed in
 * practice since binds happen on the UI thread — so no dedicated lock is
 * introduced to prevent it.
 *
 * CACHED NIGHT FLAG: the night-mode bit used to be re-read from
 * getResources().getConfiguration() on EVERY get() — including every
 * cache hit on the RecyclerView rebind path. It is now resolved once and
 * held in a volatile int; a hit is just a volatile read + one map read.
 * The flag is dropped (invalidateNightMode()) by (a) a process-level
 * ComponentCallbacks2 registered once on the application context for
 * system-driven changes, and (b) ChatActivity / GroupChatActivity on
 * onCreate + onConfigurationChanged (covers AppCompat per-activity night
 * overrides, where the Activity's uiMode can differ from the
 * Application's). It is deliberately invalidated, not set from the
 * callback's Configuration, so the next get() re-resolves from the
 * caller's own Context — always the authoritative source for that screen.
 *
 * HARDWARE BITMAPS: on API 26+ the cached bitmaps are Bitmap.Config.HARDWARE
 * (GPU-resident, zero heap, no per-frame upload). They MUST only be drawn on
 * hardware-accelerated canvases — never into a software Canvas(Bitmap) or a
 * LAYER_TYPE_SOFTWARE view, which throws. Current chat call sites
 * (ChatIconBarView, MessageBubbleCanvasView, btn_view_once ImageView) are
 * all hardware-drawn. minSdk 23 devices keep ARGB_8888.
 */
public final class IconTintCache {

    private IconTintCache() {}

    private static final ConcurrentHashMap<Integer, Bitmap> CACHE = new ConcurrentHashMap<>(16);

    private static final int NIGHT_UNKNOWN = -1;
    /** -1 = unresolved, 0 = day, 1 = night. Benign race: worst case two threads resolve the same value. */
    private static volatile int nightState = NIGHT_UNKNOWN;
    private static volatile boolean callbacksRegistered = false;

    /** Drop the cached night flag; next get() re-reads it from its own Context. */
    public static void invalidateNightMode() {
        nightState = NIGHT_UNKNOWN;
    }

    private static void ensureCallbacks(Context context) {
        if (callbacksRegistered) return;
        synchronized (IconTintCache.class) {
            if (callbacksRegistered) return;
            Context app = context.getApplicationContext();
            if (app == null) return;
            app.registerComponentCallbacks(new ComponentCallbacks2() {
                @Override public void onConfigurationChanged(Configuration newConfig) {
                    invalidateNightMode();
                }
                @Override public void onLowMemory() { }
                @Override public void onTrimMemory(int level) { }
            });
            callbacksRegistered = true;
        }
    }

    private static boolean isNight(Context context) {
        int st = nightState;
        if (st == NIGHT_UNKNOWN) {
            ensureCallbacks(context);
            st = ((context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES) ? 1 : 0;
            nightState = st;
        }
        return st == 1;
    }

    /** Returns a cached tinted bitmap, drawing + caching it on first request. */
    public static Bitmap get(Context context, int resId, int colorResId, int sizePx) {
        boolean isNight = isNight(context);
        // Composite key: resId, colorResId and night-bit all folded together.
        // resId/colorResId are stable resource ints for the app's lifetime,
        // so a simple mix is enough to avoid collisions between icons.
        int key = (resId * 31 + colorResId) * 2 + (isNight ? 1 : 0);

        Bitmap cached = CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;

        Drawable d = ContextCompat.getDrawable(context, resId);
        if (d == null) return null;
        d = d.mutate();
        DrawableCompat.setTint(d, ContextCompat.getColor(context, colorResId));

        int size = Math.max(1, sizePx);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, size, size);
        d.draw(new Canvas(bmp));

        // HARDWARE CONFIG (API 26+): a HARDWARE bitmap lives in GPU memory
        // only — no Java-heap/native pixel copy, no CPU->GPU texture upload
        // on first draw, and RenderThread reuses the same texture across
        // every bind/frame. It can't be drawn into via Canvas, so we draw
        // into the ARGB_8888 bitmap above and then copy() it once. Every
        // consumer just drawBitmap()s / setImageBitmap()s it on a
        // hardware-accelerated canvas (chat views, GlassBackdrop's
        // RenderNode capture), which HARDWARE bitmaps fully support. Any
        // failure (copy() returns null on some drivers / low GPU memory)
        // silently keeps the plain ARGB_8888 bitmap.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Bitmap hw = bmp.copy(Bitmap.Config.HARDWARE, false);
                if (hw != null) {
                    bmp.recycle();
                    bmp = hw;
                }
            } catch (RuntimeException ignored) {
                // keep software bitmap
            }
        }

        // putIfAbsent, not put: if another thread raced us and already
        // inserted a (recycled-safe, identical) bitmap for this key, keep
        // that one and let ours get GC'd rather than overwriting.
        Bitmap existing = CACHE.putIfAbsent(key, bmp);
        return existing != null ? existing : bmp;
    }
}
