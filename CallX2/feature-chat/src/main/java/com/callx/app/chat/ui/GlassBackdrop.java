package com.callx.app.chat.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.RenderNode;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.FrameMetrics;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.chat.R;

import java.util.ArrayList;
import java.util.WeakHashMap;

/**
 * Live-blur engine for the chat screen's glass surfaces (header icons, input
 * bar). One instance per glass host view.
 *
 * STRIP RECORDING (API 31+): all hosts pointing at the same source view share ONE
 * {@link Shared} (list watchers, change counter, fling freeze, jank guard). Each host
 * records ONLY the thin strip of the source behind itself (its own rect + the blur
 * reach, clamped to the source) into its own small RenderNode with blur + saturation.
 * Header strip + input strip never overlap, so together they cost a fraction of the
 * old full-screen recording, and the blur only runs over those two strips.
 *
 * The recording refreshes only when something behind the glass changed: scroll, child
 * attach/detach, list layout, item animation, or a descendant that overlaps the strip
 * invalidated itself (animated / async-loading item, download spinner, audio waveform,
 * wallpaper finishing to load; reported by {@link GlassSourceLayout}, throttled to
 * {@link #CONTENT_MIN_INTERVAL_MS}). Never while the list is flinging (refreshed once
 * when it settles). There is NO periodic refresh: an idle chat (caret blinking, nothing
 * animating behind the glass) records nothing. The 600ms safety timer only remains for a
 * source that is not a {@link GlassSourceLayout}, where invalidations can't be observed.
 * All of it runs in a pre-draw listener WITHOUT invalidate() (that would
 * re-schedule frames forever).
 *
 * BUSY = NO RECORDING: while the window is not focused (dialog / bottom sheet / popup /
 * notification shade / paused or still transitioning in; split-screen and PiP excepted
 * because they stay visible and live), or while the glass geometry is animating (keyboard
 * spring, growing input bar: the host moved on 2+ consecutive frames), no strip is
 * re-recorded. The last strip keeps being drawn, attached to the host. One refresh follows
 * as soon as focus returns / the motion has settled. (Safety valve: if the window stays
 * unfocused while the backdrop keeps changing, it still refreshes every 2s at most.)
 *
 * EMPTY-BACKDROP SHORTCUT: if no message row of the list overlaps a host's rect
 * (empty chat / nothing scrolled under the header or input bar, only wallpaper),
 * that host skips the blur entirely and paints the same cheap tinted fill as the
 * low-end path. A host with nothing behind it does not record its strip at all.
 * It flips back to real blur the moment a message slides behind the
 * glass. Switch: {@link #SKIP_BLUR_WHEN_EMPTY}.
 *
 * LOW-RES: each strip node is rendered at a fraction of its real size (1/4 width x
 * 1/4 height at the top tier, see {@link GlassRenderNodeBackdrop}) and up-scaled when
 * drawn, so the GPU rasterises/blurs 16x fewer pixels for the same look.
 *
 * ADAPTIVE QUALITY (process-wide, 3 tiers):
 *   HIGH   full radius, 1/4 resolution
 *   MEDIUM half radius, 1/8 resolution
 *   TINT   no blur, the cheap tinted frosted fill
 * The starting tier comes from the device ({@code MEDIA_PERFORMANCE_CLASS}). While the
 * list scrolls, FrameMetrics are sampled: heavy jank steps ONE tier down; a cooldown
 * (30s, doubling on each repeat up to 8 min) must pass before probing one tier back up,
 * and MEDIUM->HIGH additionally needs clean scroll windows. Nothing is disabled for the
 * whole process any more.
 *
 * Low-end (API < 31 or low-RAM): no blur at all, draw() returns false and the
 * host paints a tinted frosted fill. (The old CPU bitmap-blur fallback for API 23-30
 * was removed — it was permanently disabled and unreachable in practice.)
 *
 * {@code source} must NOT contain any glass host (fl_chat_backdrop =
 * wallpaper + skeleton + message list only).
 */
final class GlassBackdrop {

    static final float SATURATION = 1.5f;
    /** Only for sources that can't report invalidations (not a GlassSourceLayout). */
    private static final long SAFETY_REFRESH_MS = 600L;
    /** Min gap between re-recordings caused purely by content invalidating behind the glass (~20fps). */
    static final long CONTENT_MIN_INTERVAL_MS = 50L;
    /**
     * PERF: while the list is actively scrolling/dragging (not idle), content-driven
     * re-recordings are throttled harder (~11fps instead of ~20fps) — a strip that's a frame
     * or two stale during motion is imperceptible, but the extra tree-walk + blur cost per
     * skipped recording is real, and scrolling is exactly when frames are most contested.
     */
    static final long CONTENT_MIN_INTERVAL_SCROLLING_MS = 90L;
    /** PERF: no message behind a glass host (only wallpaper) -> skip blur, show tint directly. */
    static final boolean SKIP_BLUR_WHEN_EMPTY = true;
    /** While a host has nothing behind it, wait this long before freeing its GPU strip/layer —
     *  avoids alloc/free churn if content flickers in and out right at the boundary. */
    private static final long NO_CONTENT_RELEASE_DELAY_MS = 300L;

    /** Two host moves closer than this are one continuous animation (>= 2 in a row = animating). */
    private static final long MOTION_GAP_MS = 50L;
    /** After the last animated move, wait this long before recording again. */
    private static final long BUSY_SETTLE_MS = 80L;
    private static final int BUSY_NONE = 0, BUSY_TIMED = 1, BUSY_WINDOW = 2;
    /** While unfocused, a changed backdrop is still refreshed at most this often. */
    private static final long UNFOCUSED_MAX_STALE_MS = 2000L;

    // ── Adaptive quality (process-wide) ──────────────────────────────────
    static final int TIER_HIGH = 0, TIER_MEDIUM = 1, TIER_TINT = 2;
    private static final float[] TIER_SCALE = { 0.25f, 0.125f, 0.125f };
    private static final float[] TIER_RADIUS_MUL = { 1f, 0.5f, 0.5f };

    private static final int SAMPLE_FRAMES = 90;            // scroll frames per judgement window
    private static final int JANK_PERCENT = 40;             // >= this % janky frames -> step down
    private static final int CLEAN_JANK_PERCENT = 10;      // < this % -> a "clean" window
    private static final int CLEAN_WINDOWS_TO_PROMOTE = 2; // clean windows needed for MEDIUM -> HIGH
    private static final int CLEAN_WINDOWS_TO_FORGIVE = 4; // clean windows that reset the backoff
    private static final long COOLDOWN_BASE_MS = 30_000L;  // doubles per repeated step-down
    private static final int MAX_BACKOFF_SHIFT = 4;        // 30s .. 480s

    private static volatile int sTier = TIER_HIGH;
    private static boolean sTierInit;
    private static long sCooldownUntil;
    private static int sStepDowns;
    private static int sCleanWindows;

    /** Per-app heap class (MB) below which a device is treated as budget hardware at startup. */
    private static final int LOW_MEMORY_CLASS_MB = 192;

    /**
     * Picks the starting tier once per process from the device's media performance class and,
     * for devices that don't declare one, its heap class (memoryClassMb from
     * ActivityManager#getMemoryClass()). PERF: budget devices (common in the actual install
     * base — no declared MEDIA_PERFORMANCE_CLASS and a small per-app heap) previously started
     * straight at MEDIUM blur, which could jank on the very first scroll before the jank
     * watcher ever got a chance to step it down. They now start at TINT (zero blur cost) and
     * earn MEDIUM/HIGH the same way TINT already promotes elsewhere in this class, only once
     * real scrolling shows it's safe.
     */
    private static void initDeviceTier(int memoryClassMb) {
        if (sTierInit) return;
        sTierInit = true;
        final int pc = Build.VERSION.SDK_INT >= 31 ? Build.VERSION.MEDIA_PERFORMANCE_CLASS : 0;
        if (pc >= 31) {
            sTier = TIER_HIGH;
        } else if (memoryClassMb > 0 && memoryClassMb < LOW_MEMORY_CLASS_MB) {
            sTier = TIER_TINT;
        } else {
            sTier = TIER_MEDIUM;
        }
    }

    /** Judges one window of scroll frames. Main thread only. */
    private static boolean onJankWindow(int jankPercent) {
        final long now = SystemClock.uptimeMillis();
        final int tier = sTier;
        if (tier == TIER_TINT) {
            // No blur cost to measure here: after the cooldown just probe MEDIUM again.
            if (now >= sCooldownUntil) return setTier(TIER_MEDIUM);
            return false;
        }
        if (jankPercent >= JANK_PERCENT) {
            sCleanWindows = 0;
            sStepDowns++;
            sCooldownUntil = now + (COOLDOWN_BASE_MS << Math.min(sStepDowns - 1, MAX_BACKOFF_SHIFT));
            return setTier(tier + 1);
        }
        if (jankPercent < CLEAN_JANK_PERCENT) {
            sCleanWindows++;
            if (sCleanWindows >= CLEAN_WINDOWS_TO_FORGIVE) sStepDowns = 0;
        } else {
            sCleanWindows = 0;
        }
        if (tier == TIER_MEDIUM && now >= sCooldownUntil && sCleanWindows >= CLEAN_WINDOWS_TO_PROMOTE) {
            sCleanWindows = 0;
            return setTier(TIER_HIGH);
        }
        return false;
    }

    private static boolean setTier(int t) {
        if (t == sTier) return false;
        sTier = t;
        return true;
    }

    // ── Shared per-source recording ──────────────────────────────────────

    private static final WeakHashMap<View, Shared> SHARED_BY_SOURCE = new WeakHashMap<>();

    private static final class Shared {
        final View source;
        final ArrayList<GlassBackdrop> users = new ArrayList<>(2);

        RecyclerView list;
        boolean frozen;
        volatile boolean scrolling;
        boolean lightBlur;           // PERF: half-radius blur while the finger is dragging
        Window metricsWindow;
        Window.OnFrameMetricsAvailableListener metricsListener;
        Context jankCtx;             // captured once; used to (re)start the watcher lazily
        int sampleFrames, jankFrames;
        int seq = 1;                 // bumped whenever the content behind the glass changed
        boolean eventDriven;         // source reports descendant invalidations (no polling needed)
        long busyUntil;              // uptimeMillis: a host is animating, don't record before this
        final int[] tmpRect = new int[4];

        // PERF: ONE low-res capture of the whole source, shared by every glass host that reads
        // from it (header + input bar). Without this each host independently re-walked the
        // entire source view tree (source.draw()) on every dirty frame -> 2x the traversal cost
        // for 2 hosts, Nx for N. Now the tree is walked once per dirty cycle; each host just
        // crops/composites a cheap GPU texture region out of this node (see
        // GlassRenderNodeBackdrop#recordFromBase).
        RenderNode baseCapture;
        int baseSeq = -1;
        int baseTier = -1;
        int baseSrcW = -1, baseSrcH = -1;
        float baseSx = 1f, baseSy = 1f;

        Shared(View source) { this.source = source; }

        void markDirty() { seq++; }

        /** (Re)records the shared low-res capture if the source or dirty count changed. API 31+. */
        @RequiresApi(31)
        boolean ensureBaseCapture(int tier) {
            final int sw = source.getWidth(), sh = source.getHeight();
            if (sw <= 0 || sh <= 0) return false;
            if (baseCapture != null && baseSeq == seq && baseTier == tier
                    && baseSrcW == sw && baseSrcH == sh) {
                return true;   // already up to date for this dirty cycle, nothing to redo
            }
            final float scale = TIER_SCALE[tier];
            final int lw = Math.max(1, Math.round(sw * scale));
            final int lh = Math.max(1, Math.round(sh * scale));
            if (baseCapture == null) baseCapture = new RenderNode("callx.glass.base");
            baseCapture.setPosition(0, 0, lw, lh);
            boolean ok;
            Canvas c = baseCapture.beginRecording(lw, lh);
            try {
                c.scale(lw / (float) sw, lh / (float) sh);
                source.draw(c);
                ok = true;
            } catch (RuntimeException e) {
                ok = false;
            } finally {
                baseCapture.endRecording();
            }
            if (ok) {
                baseSx = lw / (float) sw;
                baseSy = lh / (float) sh;
                baseSeq = seq;
                baseTier = tier;
                baseSrcW = sw;
                baseSrcH = sh;
            }
            return ok;
        }

        boolean isAnimating() { return list != null && list.isAnimating(); }

        /**
         * True if any message row overlaps the rect (source coordinates). No list found
         * (unknown source) counts as "content" so we keep blurring exactly as before.
         * Cheap: a handful of visible rows, plain float compares, no allocations.
         */
        boolean hasContentBehind(float l, float t, float r, float b) {
            final RecyclerView rv = list;
            if (rv == null) return true;
            if (rv.getVisibility() != View.VISIBLE) return false;
            final float ox = rv.getLeft() + rv.getTranslationX();
            final float oy = rv.getTop() + rv.getTranslationY();
            for (int i = 0, n = rv.getChildCount(); i < n; i++) {
                final View c = rv.getChildAt(i);
                if (c.getVisibility() != View.VISIBLE) continue;
                final float cl = ox + c.getLeft() + c.getTranslationX();
                final float ct = oy + c.getTop() + c.getTranslationY();
                if (cl < r && cl + c.getWidth() > l && ct < b && ct + c.getHeight() > t) return true;
            }
            return false;
        }

        final RecyclerView.OnChildAttachStateChangeListener childWatcher =
                new RecyclerView.OnChildAttachStateChangeListener() {
                    @Override public void onChildViewAttachedToWindow(View view) { markDirty(); }
                    @Override public void onChildViewDetachedFromWindow(View view) { markDirty(); }
                };

        final View.OnLayoutChangeListener layoutWatcher =
                (v, l, t, r, b, ol, ot, or, ob) -> markDirty();

        final RecyclerView.OnScrollListener scrollWatcher = new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                markDirty();
            }

            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                scrolling = newState != RecyclerView.SCROLL_STATE_IDLE;
                // PERF: the OS delivers an OnFrameMetricsAvailableListener callback for every
                // single frame the window draws, for as long as it's registered — not just
                // during scrolling. It used to stay registered for the whole time a chat screen
                // was open (idle included), which is most of the time. Only jank we can actually
                // act on happens during a scroll, so the watcher now runs only for that window.
                if (scrolling) {
                    if (metricsWindow == null && jankCtx != null) startJankWatch(jankCtx);
                } else {
                    stopJankWatch();
                    sampleFrames = 0;
                    jankFrames = 0;
                }
                boolean light = newState == RecyclerView.SCROLL_STATE_DRAGGING;
                if (light != lightBlur) {
                    lightBlur = light;
                    markDirty();   // re-record with the new radius
                    if (!light) for (int i = 0; i < users.size(); i++) users.get(i).host.invalidate();
                }
                // PERF: don't re-record during a fling; one refresh when it settles.
                boolean f = newState == RecyclerView.SCROLL_STATE_SETTLING;
                if (f == frozen) return;
                frozen = f;
                if (!f) {
                    markDirty();
                    for (int i = 0; i < users.size(); i++) users.get(i).host.invalidate();
                }
            }
        };

        void acquire(GlassBackdrop user) {
            if (users.contains(user)) return;
            users.add(user);
            if (users.size() == 1 && source instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) source;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (g.getChildAt(i) instanceof RecyclerView) {
                        list = (RecyclerView) g.getChildAt(i);
                        list.addOnScrollListener(scrollWatcher);
                        list.addOnChildAttachStateChangeListener(childWatcher);
                        list.addOnLayoutChangeListener(layoutWatcher);
                        break;
                    }
                }
                if (source instanceof GlassSourceLayout) {
                    ((GlassSourceLayout) source).setListener(this::onContentInvalidated);
                    eventDriven = Build.VERSION.SDK_INT >= 28;
                }
                jankCtx = user.host.getContext();   // watcher starts lazily on first scroll
            }
        }

        /**
         * A descendant of the source invalidated itself. Flag every host whose strip it
         * overlaps so that host re-records (throttled). Rows far from the glass (a spinner
         * in the middle of the screen) are ignored. No allocations.
         */
        void onContentInvalidated(View target) {
            if (!rectInSource(target, tmpRect)) {
                for (int i = 0; i < users.size(); i++) users.get(i).contentDirty = true;
                return;
            }
            for (int i = 0; i < users.size(); i++) {
                final GlassBackdrop u = users.get(i);
                if (u.recR > u.recL && tmpRect[0] < u.recR && tmpRect[2] > u.recL
                        && tmpRect[1] < u.recB && tmpRect[3] > u.recT) {
                    u.contentDirty = true;
                }
            }
        }

        /** Target's bounds in source coordinates -> out = {l, t, r, b}. False if not a descendant. */
        private boolean rectInSource(View target, int[] out) {
            float x = 0f, y = 0f;
            View v = target;
            while (v != source) {
                x += v.getLeft() + v.getTranslationX();
                y += v.getTop() + v.getTranslationY();
                final android.view.ViewParent p = v.getParent();
                if (!(p instanceof View)) return false;
                v = (View) p;
                if (v != source) { x -= v.getScrollX(); y -= v.getScrollY(); }
            }
            out[0] = (int) x;
            out[1] = (int) y;
            out[2] = (int) x + target.getWidth();
            out[3] = (int) y + target.getHeight();
            return true;
        }

        private void startJankWatch(Context ctx) {
            if (Build.VERSION.SDK_INT < 31) return;
            Activity a = activityOf(ctx);
            if (a == null) return;
            float hz = 60f;
            if (a.getDisplay() != null) hz = Math.max(30f, a.getDisplay().getRefreshRate());
            final long budgetNs = (long) (1_000_000_000d / hz);
            metricsWindow = a.getWindow();
            metricsListener = (window, m, dropped) -> {
                if (!scrolling) return;
                if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1) return;
                sampleFrames++;
                if (m.getMetric(FrameMetrics.TOTAL_DURATION) > budgetNs) jankFrames++;
                if (sampleFrames >= SAMPLE_FRAMES) {
                    final int pct = jankFrames * 100 / sampleFrames;
                    sampleFrames = 0;
                    jankFrames = 0;
                    if (onJankWindow(pct)) onTierChanged();
                }
            };
            metricsWindow.addOnFrameMetricsAvailableListener(
                    metricsListener, new Handler(Looper.getMainLooper()));
        }

        private void stopJankWatch() {
            if (metricsWindow != null && metricsListener != null) {
                metricsWindow.removeOnFrameMetricsAvailableListener(metricsListener);
            }
            metricsWindow = null;
            metricsListener = null;
        }

        /** Quality tier changed: make every host repaint (each re-records at the new quality itself). */
        private void onTierChanged() {
            for (int i = 0; i < users.size(); i++) users.get(i).host.invalidate();
        }

        void release(GlassBackdrop user) {
            users.remove(user);
            if (users.isEmpty()) {
                stopJankWatch();
                if (list != null) {
                    list.removeOnScrollListener(scrollWatcher);
                    list.removeOnChildAttachStateChangeListener(childWatcher);
                    list.removeOnLayoutChangeListener(layoutWatcher);
                    list = null;
                }
                frozen = false;
                if (source instanceof GlassSourceLayout) ((GlassSourceLayout) source).setListener(null);
                eventDriven = false;
                baseCapture = null;
                baseSeq = -1;
                baseTier = -1;
                SHARED_BY_SOURCE.remove(source);
            }
        }
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    // ── Per-host state ───────────────────────────────────────────────────

    private final View host;
    private final int sourceId;
    private final float blurPx;
    /** false = no capture/blur at all; glass falls back to a tinted frosted fill (as cheap as solid). */
    private final boolean blurAllowed;

    private Shared shared;
    private boolean enabled = true;
    private boolean dark;
    private int baseColor;
    private boolean ready;
    /** No message behind this host right now -> tinted fill instead of blur. */
    private boolean noContent;
    private long noContentSince = -1L;
    /** draw() painted the fallback since the last time the blur was ready (needs a repaint to swap). */
    private boolean fallbackDrawn;

    private final int[] locHost = new int[2];
    private final int[] locSrc = new int[2];
    private int lastW = -1, lastH = -1;
    private float lastOffX = Float.NaN, lastOffY = Float.NaN;

    // API 31+: this host's own strip recording (host rect + blur reach only)
    private GlassRenderNodeBackdrop strip;
    private boolean stripReady;
    private final int stripPad;                 // blur reach in px (1.5 sigma) around the host rect
    private int recSeq = -1;
    private int recTier = -1;                   // quality tier this strip was recorded at
    private int recL = -1, recT = -1, recR = -1, recB = -1;
    private float stripDx, stripDy;             // strip origin relative to the host's top-left
    private long lastRecordAt;
    private int recHostW = -1, recHostH = -1;
    // host motion tracking (keyboard / layout animations)
    private int prevW = -1, prevH = -1;
    private float prevOffX = Float.NaN, prevOffY = Float.NaN;
    private long lastMoveAt;
    private int moveStreak;
    private final ViewTreeObserver.OnWindowFocusChangeListener focusListener;
    /** Something overlapping this host's strip invalidated itself since the last recording. */
    private boolean contentDirty;
    private boolean trailingPosted;

    private final Runnable trailingInvalidate = this::onTrailingInvalidate;

    private void onTrailingInvalidate() {
        trailingPosted = false;
        host.invalidate();
    }

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        update();
        return true;
    };

    GlassBackdrop(View host, int sourceId, float blurDp) {
        this.host = host;
        this.sourceId = sourceId;
        this.focusListener = hasFocus -> {
            if (hasFocus) host.invalidate();   // refresh whatever changed while we were skipping
        };
        this.blurPx = blurDp * host.getResources().getDisplayMetrics().density;
        this.stripPad = Math.round(this.blurPx * 1.5f);
        ActivityManager am = (ActivityManager) host.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        boolean lowRam = am != null && am.isLowRamDevice();
        this.blurAllowed = !lowRam && Build.VERSION.SDK_INT >= 31;
        initDeviceTier(am != null ? am.getMemoryClass() : 0);
        refreshTheme();
    }

    boolean isDark() { return dark; }
    int baseColor() { return baseColor; }
    void setEnabled(boolean e) { enabled = e; }

    void refreshTheme() {
        Context c = host.getContext();
        dark = (c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        baseColor = ContextCompat.getColor(c, R.color.chat_unified_bg) | 0xFF000000;
        if (shared != null) shared.markDirty();
    }

    void attach() {
        refreshTheme();
        if (!blurAllowed) return;   // frosted-fill only: no pre-draw work at all
        host.getViewTreeObserver().addOnPreDrawListener(preDraw);
        host.getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
    }

    void detach() {
        host.getViewTreeObserver().removeOnPreDrawListener(preDraw);
        host.getViewTreeObserver().removeOnWindowFocusChangeListener(focusListener);
        host.removeCallbacks(trailingInvalidate);
        trailingPosted = false;
        if (shared != null) {
            shared.release(this);
            shared = null;
        }
        ready = false;
        noContent = false;
        noContentSince = -1L;
        fallbackDrawn = false;
        stripReady = false;
        contentDirty = false;
        moveStreak = 0;
        prevW = prevH = -1;
        prevOffX = prevOffY = Float.NaN;
        strip = null;
    }

    private Shared resolveShared() {
        if (shared == null && sourceId != View.NO_ID) {
            View src = host.getRootView().findViewById(sourceId);
            if (src != null) {
                Shared s = SHARED_BY_SOURCE.get(src);
                if (s == null) {
                    s = new Shared(src);
                    SHARED_BY_SOURCE.put(src, s);
                }
                s.acquire(this);
                shared = s;
            }
        }
        return shared;
    }

    private void update() {
        if (!enabled || !host.isShown()) return;
        final int w = host.getWidth(), h = host.getHeight();
        if (w <= 0 || h <= 0) return;
        final Shared s = resolveShared();
        if (s == null || s.source.getWidth() <= 0) return;

        // Quality governor says TINT: no blur right now, cheap tint only. (resolveShared() above
        // still ran, so the jank watcher keeps sampling and can promote us back up after cooldown.)
        final int tier = sTier;
        if (tier >= TIER_TINT) {
            if (ready) {
                ready = false;
                host.invalidate();
            }
            if (stripReady) { stripReady = false; strip = null; }
            return;
        }

        host.getLocationInWindow(locHost);
        s.source.getLocationInWindow(locSrc);
        final float offX = locHost[0] - locSrc[0];
        final float offY = locHost[1] - locSrc[1];

        // Motion tracking: a host that moves/resizes on 2+ consecutive frames is animating
        // (keyboard spring, growing input bar). Tell every host of this source to hold off.
        if (w != prevW || h != prevH || offX != prevOffX || offY != prevOffY) {
            final long tNow = SystemClock.uptimeMillis();
            moveStreak = (tNow - lastMoveAt <= MOTION_GAP_MS) ? moveStreak + 1 : 1;
            lastMoveAt = tNow;
            if (moveStreak >= 2) s.busyUntil = tNow + BUSY_SETTLE_MS;
            prevW = w; prevH = h; prevOffX = offX; prevOffY = offY;
        }

        // PERF: only wallpaper behind this host -> no recording, no blur, just the tint.
        if (SKIP_BLUR_WHEN_EMPTY && !s.hasContentBehind(offX, offY, offX + w, offY + h)) {
            if (!noContent) {
                noContent = true;
                noContentSince = SystemClock.uptimeMillis();
            }
            if (ready) {           // was blurred a moment ago: repaint once with the tint
                ready = false;
                host.invalidate();
            }
            // PERF: after a short grace period, also free this host's GPU-backed strip/layer
            // instead of just skipping its use — an empty chat (or scrolled to a stretch with
            // nothing behind the header/input bar) otherwise holds onto a compositing-layer
            // texture indefinitely for no reason.
            if (stripReady && SystemClock.uptimeMillis() - noContentSince > NO_CONTENT_RELEASE_DELAY_MS) {
                stripReady = false;
                strip = null;
            }
            return;
        }
        if (noContent) {           // a message just slid behind the glass: force a fresh recording
            noContent = false;
            noContentSince = -1L;
            stripReady = false;
        }

        // blurAllowed guarantees SDK_INT >= 31 whenever update() runs; only isHardwareAccelerated()
        // can still say no (e.g. a software layer), in which case we just show the tinted fallback.
        if (!host.isHardwareAccelerated()) { ready = false; return; }
        if (!updateStrip(s, w, h, offX, offY, tier)) { ready = false; return; }
        lastW = w; lastH = h; lastOffX = offX; lastOffY = offY;
        ready = true;
        if (fallbackDrawn) {   // tint was painted earlier: repaint once with the real blur
            fallbackDrawn = false;
            host.invalidate();
        }
    }

    /**
     * API 31+: (re)records ONLY the strip of the source behind this host (host rect + blur
     * reach, clamped to the source) when something behind it changed. Returns true if a
     * valid recording exists.
     */
    private boolean updateStrip(Shared s, int w, int h, float offX, float offY, int tier) {
        final int sw = s.source.getWidth(), sh = s.source.getHeight();
        final int hl = Math.round(offX), ht = Math.round(offY);
        final int l = Math.max(0, hl - stripPad);
        final int t = Math.max(0, ht - stripPad);
        final int r = Math.min(sw, hl + w + stripPad);
        final int b = Math.min(sh, ht + h + stripPad);
        if (r <= l || b <= t) return false;

        final long now = SystemClock.uptimeMillis();

        // BUSY: window unfocused / geometry animating -> keep drawing the last strip, record nothing.
        if (stripReady && recTier == tier && w == recHostW && h == recHostH) {
            final int busy = busyReason(s, now);
            // Safety valve: a window that stays unfocused for long while its content keeps changing
            // (never-focused embedding, long-lived dialog) still gets a slow refresh.
            final boolean staleWhileUnfocused = busy == BUSY_WINDOW
                    && (recSeq != s.seq || contentDirty)
                    && now - lastRecordAt > UNFOCUSED_MAX_STALE_MS;
            if (busy != BUSY_NONE && !staleWhileUnfocused) {
                if (busy == BUSY_TIMED && !trailingPosted) {
                    // make sure one frame comes after the motion settled (nothing else may draw)
                    trailingPosted = true;
                    host.postDelayed(trailingInvalidate, Math.max(1L, s.busyUntil - now + 1));
                }
                return true;
            }
        }

        final long since = now - lastRecordAt;
        final long contentInterval = s.scrolling ? CONTENT_MIN_INTERVAL_SCROLLING_MS : CONTENT_MIN_INTERVAL_MS;
        final boolean rectChanged = l != recL || t != recT || r != recR || b != recB;
        final boolean contentDue = contentDirty && since >= contentInterval;
        final boolean safetyDue = !s.eventDriven && since > SAFETY_REFRESH_MS;   // only if we can't observe changes
        final boolean need = !stripReady || rectChanged || recSeq != s.seq || recTier != tier
                || s.isAnimating() || contentDue || safetyDue;
        // PERF: no re-recording while flinging, unless there is nothing valid to draw yet
        // or the host moved (strip position would be wrong).
        if (!need || (s.frozen && stripReady && !rectChanged && recTier == tier)) {
            if (contentDirty && !contentDue && !s.frozen && !trailingPosted) {
                // Content changed but we're inside the throttle window and no other frame may
                // follow (e.g. the spinner just stopped): make sure one comes after the gap.
                trailingPosted = true;
                host.postDelayed(trailingInvalidate, contentInterval - since + 1);
            }
            return stripReady;
        }

        if (strip == null) strip = new GlassRenderNodeBackdrop(SATURATION);
        strip.setQuality(blurPx * TIER_RADIUS_MUL[tier] * (s.lightBlur ? 0.5f : 1f), TIER_SCALE[tier]);
        // PERF: crop this host's strip out of ONE shared capture of the source instead of each
        // host re-walking the whole view tree. Falls back to a direct draw only if the shared
        // capture couldn't be produced this frame.
        if (s.ensureBaseCapture(tier)) {
            stripReady = strip.recordFromBase(s.baseCapture, s.baseSx, s.baseSy, r - l, b - t, l, t, baseColor);
        } else {
            stripReady = strip.recordDirect(s.source, r - l, b - t, l, t, baseColor);
        }
        stripDx = l - offX;
        stripDy = t - offY;
        recSeq = s.seq;
        recTier = tier;
        recHostW = w; recHostH = h;
        recL = l; recT = t; recR = r; recB = b;
        lastRecordAt = now;
        contentDirty = false;
        return stripReady;
    }

    private int busyReason(Shared s, long now) {
        if (now < s.busyUntil) return BUSY_TIMED;
        if (host.getWindowVisibility() != View.VISIBLE) return BUSY_WINDOW;
        if (!host.hasWindowFocus()) {
            // Split-screen / PiP: window is visible and live but never focused -> keep recording.
            final Activity a = activityOf(host.getContext());
            if (a == null || Build.VERSION.SDK_INT < 24 || !a.isInMultiWindowMode()) return BUSY_WINDOW;
        }
        return BUSY_NONE;
    }

    /** Draws the blurred backdrop covering the whole host. Returns false if unavailable. */
    boolean draw(Canvas canvas) {
        if (!ready) {
            fallbackDrawn = true;
            return false;
        }
        if (strip != null && stripReady && canvas.isHardwareAccelerated()) {
            strip.draw(canvas, stripDx, stripDy);
            return true;
        }
        return false;
    }
}
