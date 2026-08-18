package com.callx.app.player;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GpuDecodeWarmup — pays the cold-start decoder/GPU init cost BEFORE the
 * user opens their first reel (PERF advance #8 — "GPU decode warm-up").
 *
 * On a cold app start, the FIRST ExoPlayer ever built in the process eats
 * two one-time costs that every later reel skips entirely:
 *   1. MediaCodec HAL/OMX plugin loading for the video codec (H.264/HEVC)
 *      — creating the very first decoder of a given mime type on a device
 *      can take 50–150ms while the codec service process spins up.
 *   2. EGL/GL driver + context creation the first time ANY GPU surface is
 *      touched in the process — vendor GL driver library loading, shader
 *      compiler warm-up, etc.
 *
 * Both costs are paid once per process, not once per player instance — so
 * instead of the FIRST real reel silently absorbing them (visible as a
 * slower "instant" playback on cold start vs every reel after), we pay
 * them speculatively, off the main thread, the moment the Reels tab is
 * first opened. By the time preparePlayerSilently() builds the first real
 * pooled ExoPlayer, the codec service and GL driver are already resident.
 *
 * Deliberately does NOT touch ExoPlayer, a Surface, or any reel content —
 * it only needs to trigger the underlying platform init, which doesn't
 * require an actual video bitstream or a visible surface:
 *   - MediaCodec.createDecoderByType(mime) + release() warms the codec
 *     without configuring or feeding it any data.
 *   - A throwaway EGL pbuffer context (never attached to any View/Surface)
 *     warms the GL driver without any visible side effect.
 *
 * Runs at most once per process (guarded by {@link #done}); safe to call
 * from every ReelsFragment.onCreateView() — later calls are free no-ops.
 */
public final class GpuDecodeWarmup {

    private static final String TAG = "GpuDecodeWarmup";

    private static final AtomicBoolean started = new AtomicBoolean(false);

    private GpuDecodeWarmup() { }

    /** Fire-and-forget — call from ReelsFragment.onCreateView(). Runs on a
     *  short-lived background thread so it never blocks the UI. */
    public static void warmUpOnce(Context context) {
        if (!started.compareAndSet(false, true)) return; // already warmed/warming this process
        final Context appCtx = context.getApplicationContext();
        new Thread(() -> {
            warmUpCodec();
            warmUpEgl();
        }, "GpuDecodeWarmup").start();
        // appCtx currently unused inside the warm-up itself (both steps are
        // process/driver-level, not Context-dependent) — kept for parity
        // with the rest of this codebase's *Manager.get(Context) pattern
        // and in case a future warm-up step needs it (e.g. reading a
        // device-tier flag).
        if (appCtx == null) Log.w(TAG, "warmUpOnce: null application context");
    }

    /**
     * Creates and immediately releases a decoder for the codecs reels
     * actually use, forcing the codec HAL/plugin to load now instead of on
     * the first real reel. No configure()/start() — just instantiation,
     * which is where the one-time service-spin-up cost lives.
     */
    private static void warmUpCodec() {
        long t0 = System.currentTimeMillis();
        warmUpMime("video/avc");   // H.264 — every device, every reel can fall back to this
        warmUpMime("video/hevc");  // HEVC — used when CodecSupport picks vc_h265
        Log.d(TAG, "warmUpCodec: done in " + (System.currentTimeMillis() - t0) + "ms");
    }

    private static void warmUpMime(String mime) {
        // Skip mimes with no decoder on this device — createDecoderByType()
        // throws for those, which is expected and not a failure.
        if (!hasDecoderFor(mime)) return;
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createDecoderByType(mime);
        } catch (Exception e) {
            Log.w(TAG, "warmUpMime(" + mime + ") failed: " + e.getMessage());
        } finally {
            if (codec != null) {
                try { codec.release(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean hasDecoderFor(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mime)) return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "hasDecoderFor(" + mime + ") probe failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Creates a throwaway 1x1 EGL pbuffer context — never attached to any
     * View, PlayerView, or Surface — purely to force the GL driver library
     * to load and its context-creation path to run once, off the main
     * thread, ahead of the first real reel's SurfaceView/TextureView
     * attach.
     */
    private static void warmUpEgl() {
        long t0 = System.currentTimeMillis();
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) {
                Log.w(TAG, "warmUpEgl: no display");
                return;
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Log.w(TAG, "warmUpEgl: eglInitialize failed");
                return;
            }

            int[] configAttribs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0) {
                Log.w(TAG, "warmUpEgl: eglChooseConfig failed");
                return;
            }
            EGLConfig config = configs[0];

            int[] contextAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            if (context == EGL14.EGL_NO_CONTEXT) {
                Log.w(TAG, "warmUpEgl: eglCreateContext failed");
                return;
            }

            int[] pbufferAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0);
            if (surface == EGL14.EGL_NO_SURFACE) {
                Log.w(TAG, "warmUpEgl: eglCreatePbufferSurface failed");
                return;
            }

            EGL14.eglMakeCurrent(display, surface, surface, context);
            // Context is now current on this background thread — driver is
            // fully loaded and warm. Immediately tear down; nothing is ever
            // rendered or shown.
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            Log.d(TAG, "warmUpEgl: done in " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            // Never let a warm-up probe crash the app — worst case we
            // simply didn't warm anything and the first reel pays the
            // normal cold-start cost, same as before this feature existed.
            Log.w(TAG, "warmUpEgl: failed: " + t.getMessage());
        } finally {
            try {
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display);
            } catch (Exception ignored) {}
        }
    }
}
