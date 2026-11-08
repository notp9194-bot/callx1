package com.callx.app.compose;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * v766: bakes a template with VIDEO boxes into ONE mp4.
 *
 * Layers, bottom to top: {@code under} (opaque background + photo boxes + anything below the clips) → every video slot
 * (centre-cropped to its box, masked to the box shape, rotated like the poster placed it) → the slot's frame (shadow + border)
 * → {@code over} (transparent: text, stickers, credit). Output is H.264 + (optionally) the AAC track of one source file copied
 * as-is, so the finished file goes through the normal video-status upload path.
 *
 * Timing is deterministic: frame N of the output is built from source time {@code trimStart + (N / fps) mod loopLength}, each clip
 * decoded forward and restarted from its trim point when the slot is longer than the clip (loop). No wall-clock dependence.
 *
 * v772: a slot may also be a PHOTO box ({@link Slot#image}), and every slot has a colour filter + an animation (zoom / pan / fade-in).
 *
 * Self-contained: touches no editor state. Call from a background thread. Not tested on device yet (v766).
 */
public final class TemplateVideoCompositor {

    private static final String TAG = "TplVideoComp";

    public static final int DEFAULT_W = 720, DEFAULT_H = 1280, DEFAULT_FPS = 30, DEFAULT_BITRATE = 4_000_000;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private static final long FRAME_TOL_US = 20_000L;      // pick the first decoded frame within ~½ source frame of the wanted time
    private static final long ADVANCE_TIMEOUT_MS = 5000L;  // give one clip this long to produce one frame
    private static final long READ_AHEAD_US = 500_000L;    // stop feeding the decoder this far past the slot's end

    /** One video box. Geometry = fractions of the canvas (same as templateJson slots), rot in degrees (clockwise). */
    public static final class Slot {
        public String path;                       // local file (copy content:// uris to cache first)
        public float cx = 0.5f, cy = 0.5f, w = 0.5f, h = 0.3f, rot;
        @Nullable public TemplateBoxStyle style;  // null = plain rounded box
        public long trimStartMs;                  // where in the clip this slot starts
        public long durationMs = 3000L;           // how long this box shows (clip loops if shorter)
        public float zoom = 1f;                   // ≥ 1: user zoom inside the box
        public float panX, panY;                  // -1..1 inside the free area left by zoom / cover-crop
        /** v772: photo box — drawn from this bitmap (already cropped to the box) instead of a decoded clip; {@link #path} unused. */
        @Nullable public Bitmap image;
        /** v772: {@link TemplateSpec#FILTERS} key, colour-graded in the shader. */
        @NonNull public String filter = TemplateSpec.DEF_FILTER;
        /** v772: {@link TemplateSpec#ANIMS} key, played over the box's own duration (zoom / pan / fade-in). */
        @NonNull public String anim = TemplateSpec.DEF_ANIM;
    }

    public interface Progress { void onProgress(int pct); }

    public static final class Result {
        public boolean ok, cancelled, audioKept;
        @Nullable public String error;
        public int frames;
    }

    private TemplateVideoCompositor() { }

    // ── entry point ──────────────────────────────────────────────────────────

    /**
     * @param under      opaque background, drawn first (scaled to the output size)
     * @param over       transparent top layer (text / stickers), may be null
     * @param audioPath  file whose AAC track is copied (null = silent); non-AAC audio is skipped, see {@link Result#audioKept}
     * @param totalMs    output length
     */
    @NonNull
    public static Result compose(@NonNull Bitmap under, @Nullable Bitmap over, @NonNull List<Slot> slots,
                                 @Nullable String audioPath, long audioStartMs, long totalMs,
                                 int outW, int outH, @NonNull File out,
                                 @Nullable Progress progress, @Nullable AtomicBoolean cancel) {
        final Result res = new Result();
        if (slots.isEmpty() || totalMs < 500) { res.error = "no slots / too short"; return res; }
        final Engine e = new Engine(under, over, slots, audioPath, audioStartMs, totalMs,
                even(outW), even(outH), out, progress, cancel, res);
        try {
            e.run();
        } catch (Throwable t) {
            Log.e(TAG, "compose failed", t);
            res.ok = false;
            res.error = String.valueOf(t);
        } finally {
            e.release();
        }
        if (!res.ok && !res.cancelled && out.exists()) //noinspection ResultOfMethodCallIgnored
            out.delete();
        if (res.cancelled && out.exists()) //noinspection ResultOfMethodCallIgnored
            out.delete();
        return res;
    }

    private static int even(int v) { return Math.max(2, v & ~1); }

    /** One compiled box shader and its uniform locations. */
    private static final class VProg {
        int prog, aPos, mvp, texMat, crop, vid, mask, cm, alpha;
    }

    // ── engine ───────────────────────────────────────────────────────────────

    private static final class Engine {
        final Bitmap under, over;
        final List<Slot> slots;
        final String audioPath;
        final long audioStartUs, totalUs;
        final int W, H, fps = DEFAULT_FPS;
        final File outFile;
        final Progress progress;
        final AtomicBoolean cancel;
        final Result res;

        HandlerThread thread;
        Handler handler;
        MediaCodec encoder;
        Surface encSurface;
        EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        MediaMuxer muxer;
        boolean muxStarted;
        int muxVideo = -1, muxAudio = -1;
        MediaFormat audioFmt;

        VProg progVideo, progImage;     // v772: external-OES (clips) and plain 2D (photo boxes) variants of the box shader
        int progTex;
        int tAPos, tMvp, tTex, tAlpha;
        FloatBuffer quad;
        final float[] proj = new float[16];
        final List<Clip> clips = new ArrayList<>();
        int underTex, overTex;

        Engine(Bitmap under, Bitmap over, List<Slot> slots, String audioPath, long audioStartMs, long totalMs,
               int w, int h, File out, Progress p, AtomicBoolean cancel, Result res) {
            this.under = under; this.over = over; this.slots = slots; this.audioPath = audioPath;
            this.audioStartUs = Math.max(0, audioStartMs) * 1000L;
            this.totalUs = totalMs * 1000L;
            this.W = w; this.H = h; this.outFile = out; this.progress = p; this.cancel = cancel; this.res = res;
        }

        void run() throws Exception {
            thread = new HandlerThread("TplVideoComp-frames");
            thread.start();
            handler = new Handler(thread.getLooper());

            // encoder (surface input)
            final MediaFormat ef = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encSurface = encoder.createInputSurface();

            initEgl();
            initGl();
            Matrix.orthoM(proj, 0, 0, W, H, 0, -1, 1);   // pixel space, y down

            for (Slot s : slots) clips.add(new Clip(s));
            for (Clip c : clips) c.prepare();

            audioFmt = audioPath != null ? findAacFormat(audioPath) : null;
            res.audioKept = audioFmt != null;

            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            encoder.start();

            final int totalFrames = (int) Math.max(1, totalUs * fps / 1_000_000L);
            final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            for (int f = 0; f < totalFrames; f++) {
                if (cancel != null && cancel.get()) { res.cancelled = true; return; }
                final long tUs = f * 1_000_000L / fps;
                for (Clip c : clips) c.advance(tUs);
                drawFrame(tUs);
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, tUs * 1000L);
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                drain(info, false);
                res.frames = f + 1;
                if (progress != null && (f % 5 == 0)) progress.onProgress(Math.min(99, (int) (f * 100L / totalFrames)));
            }
            encoder.signalEndOfInputStream();
            drain(info, true);
            if (muxStarted) { muxer.stop(); muxStarted = false; }
            res.ok = res.frames > 0 && outFile.length() > 1024;
            if (progress != null && res.ok) progress.onProgress(100);
        }

        // ── encoder drain / mux ──────────────────────────────────────────────

        void drain(MediaCodec.BufferInfo info, boolean untilEos) throws Exception {
            int idleSpins = 0;
            while (true) {
                final int idx = encoder.dequeueOutputBuffer(info, untilEos ? 10_000 : 0);
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!untilEos) return;
                    if (++idleSpins > 3000) throw new IllegalStateException("encoder never finished");
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxStarted) throw new IllegalStateException("format changed twice");
                    muxVideo = muxer.addTrack(encoder.getOutputFormat());
                    if (audioFmt != null) muxAudio = muxer.addTrack(audioFmt);
                    muxer.start();
                    muxStarted = true;
                    if (muxAudio >= 0) copyAudio();
                } else if (idx >= 0) {
                    idleSpins = 0;
                    final ByteBuffer eb = encoder.getOutputBuffer(idx);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxStarted && eb != null) {
                        eb.position(info.offset);
                        eb.limit(info.offset + info.size);
                        muxer.writeSampleData(muxVideo, eb, info);
                    }
                    encoder.releaseOutputBuffer(idx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
                }
            }
        }

        /** Copies [audioStart, audioStart + total) of the AAC track as-is (timestamps shifted to 0). */
        void copyAudio() {
            MediaExtractor ex = null;
            try {
                ex = new MediaExtractor();
                ex.setDataSource(audioPath);
                int track = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    final String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if ("audio/mp4a-latm".equals(mime)) { track = i; break; }
                }
                if (track < 0) return;
                ex.selectTrack(track);
                ex.seekTo(audioStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                final MediaFormat af = ex.getTrackFormat(track);
                final int cap = af.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) ? Math.max(af.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 4096) : 262_144;
                final ByteBuffer buf = ByteBuffer.allocate(cap);
                final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                while (true) {
                    final long t = ex.getSampleTime();
                    if (t < 0) break;
                    if (t - audioStartUs > totalUs) break;
                    buf.clear();
                    final int sz = ex.readSampleData(buf, 0);
                    if (sz < 0) break;
                    if (t >= audioStartUs) {
                        bi.set(0, sz, t - audioStartUs, MediaCodec.BUFFER_FLAG_KEY_FRAME);
                        muxer.writeSampleData(muxAudio, buf, bi);
                    }
                    ex.advance();
                }
            } catch (Exception e) {
                Log.w(TAG, "audio copy failed (video kept, audio may be cut)", e);
            } finally {
                if (ex != null) ex.release();
            }
        }

        @Nullable
        MediaFormat findAacFormat(String path) {
            MediaExtractor ex = null;
            try {
                ex = new MediaExtractor();
                ex.setDataSource(path);
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    final MediaFormat f = ex.getTrackFormat(i);
                    if ("audio/mp4a-latm".equals(f.getString(MediaFormat.KEY_MIME))) return f;
                }
            } catch (Exception ignored) {
            } finally {
                if (ex != null) ex.release();
            }
            return null;
        }

        // ── EGL / GL ─────────────────────────────────────────────────────────

        void initEgl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("no EGL display");
            final int[] ver = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) throw new IllegalStateException("eglInitialize failed");
            final int[] attrs = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE};
            final EGLConfig[] cfg = new EGLConfig[1];
            final int[] n = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, cfg, 0, 1, n, 0) || n[0] < 1)
                throw new IllegalStateException("no EGL config");
            eglContext = EGL14.eglCreateContext(eglDisplay, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg[0], encSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
                throw new IllegalStateException("eglMakeCurrent failed");
        }

        void initGl() {
            final String vsVideo =
                    "attribute vec2 aPos;\n"
                  + "uniform mat4 uMvp;\n"
                  + "uniform mat4 uTexMat;\n"
                  + "uniform vec4 uCrop;\n"            // u0, v0 (bottom), u1, v1 (top)
                  + "varying vec2 vVid;\n"
                  + "varying vec2 vMask;\n"
                  + "void main(){\n"
                  + "  gl_Position = uMvp * vec4(aPos, 0.0, 1.0);\n"
                  + "  vec2 t = (aPos + 1.0) * 0.5;\n"  // y down: t.y = 0 is the box top
                  + "  vMask = t;\n"
                  + "  vec2 uv = vec2(mix(uCrop.x, uCrop.z, t.x), mix(uCrop.w, uCrop.y, t.y));\n"
                  + "  vVid = (uTexMat * vec4(uv, 0.0, 1.0)).xy;\n"
                  + "}\n";
            final String vsTex =
                    "attribute vec2 aPos;\n"
                  + "uniform mat4 uMvp;\n"
                  + "varying vec2 vUV;\n"
                  + "void main(){\n"
                  + "  gl_Position = uMvp * vec4(aPos, 0.0, 1.0);\n"
                  + "  vUV = (aPos + 1.0) * 0.5;\n"
                  + "}\n";
            final String fsTex =
                    "precision mediump float;\n"
                  + "uniform sampler2D uTex;\n"
                  + "uniform float uA;\n"
                  + "varying vec2 vUV;\n"
                  + "void main(){ gl_FragColor = texture2D(uTex, vUV) * uA; }\n";
            progVideo = videoProgram(vsVideo, true);
            progImage = videoProgram(vsVideo, false);
            progTex = program(vsTex, fsTex);
            tAPos = GLES20.glGetAttribLocation(progTex, "aPos");
            tMvp = GLES20.glGetUniformLocation(progTex, "uMvp");
            tTex = GLES20.glGetUniformLocation(progTex, "uTex");
            tAlpha = GLES20.glGetUniformLocation(progTex, "uA");

            final float[] q = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
            quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quad.put(q).position(0);

            GLES20.glViewport(0, 0, W, H);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

            underTex = bitmapTexture(under);
            overTex = over != null ? bitmapTexture(over) : 0;
        }

        void drawFrame(long tUs) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            final float[] full = new float[16];
            screenMvp(full);
            drawTex(underTex, full);
            for (Clip c : clips) {
                final long slotEndUs = c.slot.durationMs * 1000L;
                // box only shows for its own duration; after that the background shows through (a shorter slot ends early)
                if (tUs < slotEndUs && c.hasFrame) c.draw(tUs);
                if (c.frameTex != 0 && tUs < slotEndUs) drawTex(c.frameTex, c.mvp, c.frameAlpha);
            }
            if (overTex != 0) drawTex(overTex, full);
        }

        /** Box shader: clip/photo → colour matrix (filter) → box-shape mask → fade alpha, premultiplied. */
        VProg videoProgram(String vs, boolean oes) {
            final String fs =
                    (oes ? "#extension GL_OES_EGL_image_external : require\n" : "")
                  + "precision mediump float;\n"
                  + (oes ? "uniform samplerExternalOES uVid;\n" : "uniform sampler2D uVid;\n")
                  + "uniform sampler2D uMask;\n"
                  + "uniform mat4 uCM;\n"
                  + "uniform float uAlpha;\n"
                  + "varying vec2 vVid;\n"
                  + "varying vec2 vMask;\n"
                  + "void main(){\n"
                  + "  vec3 c = texture2D(uVid, vVid).rgb;\n"
                  + "  c = clamp((uCM * vec4(c, 1.0)).rgb, 0.0, 1.0);\n"
                  + "  float a = texture2D(uMask, vMask).a * uAlpha;\n"
                  + "  gl_FragColor = vec4(c * a, a);\n"   // premultiplied
                  + "}\n";
            final VProg v = new VProg();
            v.prog = program(vs, fs);
            v.aPos = GLES20.glGetAttribLocation(v.prog, "aPos");
            v.mvp = GLES20.glGetUniformLocation(v.prog, "uMvp");
            v.texMat = GLES20.glGetUniformLocation(v.prog, "uTexMat");
            v.crop = GLES20.glGetUniformLocation(v.prog, "uCrop");
            v.vid = GLES20.glGetUniformLocation(v.prog, "uVid");
            v.mask = GLES20.glGetUniformLocation(v.prog, "uMask");
            v.cm = GLES20.glGetUniformLocation(v.prog, "uCM");
            v.alpha = GLES20.glGetUniformLocation(v.prog, "uAlpha");
            return v;
        }

        void screenMvp(float[] out) {
            final float[] m = new float[16];
            Matrix.setIdentityM(m, 0);
            Matrix.translateM(m, 0, W / 2f, H / 2f, 0f);
            Matrix.scaleM(m, 0, W / 2f, H / 2f, 1f);
            Matrix.multiplyMM(out, 0, proj, 0, m, 0);
        }

        void drawTex(int tex, float[] mvp) { drawTex(tex, mvp, 1f); }

        void drawTex(int tex, float[] mvp, float alpha) {
            GLES20.glUseProgram(progTex);
            GLES20.glUniform1f(tAlpha, alpha);
            GLES20.glUniformMatrix4fv(tMvp, 1, false, mvp, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glUniform1i(tTex, 0);
            GLES20.glEnableVertexAttribArray(tAPos);
            GLES20.glVertexAttribPointer(tAPos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        int bitmapTexture(Bitmap b) {
            final int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
            return t[0];
        }

        int program(String vs, String fs) {
            final int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
            final int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, v);
            GLES20.glAttachShader(p, f);
            GLES20.glLinkProgram(p);
            final int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("link: " + GLES20.glGetProgramInfoLog(p));
            return p;
        }

        int shader(int type, String src) {
            final int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, src);
            GLES20.glCompileShader(s);
            final int[] ok = new int[1];
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
            return s;
        }

        void release() {
            for (Clip c : clips) c.release();
            try { if (encoder != null) { encoder.stop(); } } catch (Throwable ignored) { }
            try { if (encoder != null) encoder.release(); } catch (Throwable ignored) { }
            try { if (muxer != null) { if (muxStarted) muxer.stop(); muxer.release(); } } catch (Throwable ignored) { }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                    EGL14.eglTerminate(eglDisplay);
                } catch (Throwable ignored) { }
            }
            if (encSurface != null) encSurface.release();
            if (thread != null) thread.quitSafely();
        }

        // ── one decoded clip ─────────────────────────────────────────────────

        final class Clip {
            final Slot slot;
            MediaExtractor ex;
            MediaCodec dec;
            Surface surface;
            SurfaceTexture st;
            int oesTex, maskTex, frameTex;
            final float[] texMat = new float[16];
            final float[] mvp = new float[16];
            final float[] crop = new float[4];
            int dispW, dispH;
            float boxAspect = 1f, frameAlpha = 1f;
            final float[] colorMat;          // v772: filter as a mat4
            final float[] anim = new float[4];
            long trimUs, loopLenUs, curLoop = -1;
            boolean inEos, outEos, hasFrame;
            int held = -1;
            long heldPts;
            final Object lock = new Object();
            boolean frameAvail;
            final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

            Clip(Slot s) { slot = s; colorMat = TemplateEffects.glMatrix(s.filter); }

            /** v772: photo box — one texture, no decoder; the flip matrix turns bitmap rows (top-down) into the clip convention. */
            void prepareImage() {
                final Bitmap b = slot.image;
                dispW = Math.max(1, b.getWidth());
                dispH = Math.max(1, b.getHeight());
                oesTex = bitmapTexture(b);
                Matrix.setIdentityM(texMat, 0);
                texMat[5] = -1f;
                texMat[13] = 1f;
                setupBox();
                hasFrame = true;
            }

            /** Box pixels: mask + frame textures and the placement matrix. */
            void setupBox() {
                final float bw = Math.max(2f, slot.w * W), bh = Math.max(2f, slot.h * H);
                final TemplateBoxStyle style = slot.style != null ? slot.style : new TemplateBoxStyle();
                final Bitmap mask = style.maskBitmap(Math.round(bw), Math.round(bh));
                maskTex = bitmapTexture(mask);
                mask.recycle();
                final Bitmap frame = style.frameBitmap(Math.round(bw), Math.round(bh));
                if (frame != null) { frameTex = bitmapTexture(frame); frame.recycle(); }

                final float[] m = new float[16];
                Matrix.setIdentityM(m, 0);
                Matrix.translateM(m, 0, slot.cx * W, slot.cy * H, 0f);
                Matrix.rotateM(m, 0, slot.rot, 0f, 0f, 1f);
                Matrix.scaleM(m, 0, bw / 2f, bh / 2f, 1f);
                Matrix.multiplyMM(mvp, 0, proj, 0, m, 0);
                boxAspect = bw / bh;
                cropFor(0f);
            }

            void prepare() throws Exception {
                if (slot.image != null) { prepareImage(); return; }
                ex = new MediaExtractor();
                ex.setDataSource(slot.path);
                int track = -1;
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    final String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) { track = i; break; }
                }
                if (track < 0) throw new IllegalStateException("no video track in " + slot.path);
                ex.selectTrack(track);
                final MediaFormat fmt = ex.getTrackFormat(track);
                int w = fmt.getInteger(MediaFormat.KEY_WIDTH), h = fmt.getInteger(MediaFormat.KEY_HEIGHT);
                final int rot = fmt.containsKey(MediaFormat.KEY_ROTATION) ? fmt.getInteger(MediaFormat.KEY_ROTATION) : 0;
                if (rot % 180 != 0) { final int t = w; w = h; h = t; }
                dispW = w; dispH = h;
                final long clipDur = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0L;
                final long slotUs = Math.max(100_000L, slot.durationMs * 1000L);
                trimUs = Math.max(0, slot.trimStartMs * 1000L);
                if (clipDur > 0 && trimUs > clipDur - 200_000L) trimUs = Math.max(0, clipDur - 200_000L);
                final long remaining = clipDur > 0 ? clipDur - trimUs : slotUs;
                loopLenUs = Math.max(100_000L, Math.min(slotUs, remaining));

                // GL objects for this clip
                final int[] t = new int[1];
                GLES20.glGenTextures(1, t, 0);
                oesTex = t[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                st = new SurfaceTexture(oesTex);
                st.setOnFrameAvailableListener(sf -> {
                    synchronized (lock) { frameAvail = true; lock.notifyAll(); }
                }, handler);
                surface = new Surface(st);
                Matrix.setIdentityM(texMat, 0);

                setupBox();

                dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                dec.configure(fmt, surface, null, 0);
                dec.start();
                startLoop(0);
            }

            /** Centre-crop to fill the box, then user zoom / pan + the slot animation (at progress {@code p}) inside the free area. */
            void cropFor(float p) {
                TemplateEffects.animState(slot.anim, p, anim);
                frameAlpha = anim[3];
                final float va = dispW / (float) dispH;
                float fu = 1f, fv = 1f;                       // visible fraction of the video
                if (va > boxAspect) fu = boxAspect / va; else fv = va / boxAspect;
                final float z = Math.max(1f, slot.zoom) * anim[0];
                fu = Math.max(0.05f, fu / z);
                fv = Math.max(0.05f, fv / z);
                final float px = Math.max(-1f, Math.min(1f, slot.panX + anim[1])), py = Math.max(-1f, Math.min(1f, slot.panY + anim[2]));
                float u0 = (1f - fu) / 2f + px * (1f - fu) / 2f;
                float v0 = (1f - fv) / 2f - py * (1f - fv) / 2f;   // +panY shows the lower part (texture v grows upward)
                u0 = Math.max(0f, Math.min(1f - fu, u0));
                v0 = Math.max(0f, Math.min(1f - fv, v0));
                crop[0] = u0; crop[1] = v0; crop[2] = u0 + fu; crop[3] = v0 + fv;
            }

            void startLoop(long loop) {
                curLoop = loop;
                ex.seekTo(trimUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                if (loop > 0 || hasFrame || held >= 0) dec.flush();
                inEos = false; outEos = false; held = -1;
            }

            void feed() {
                while (!inEos) {
                    final int ib = dec.dequeueInputBuffer(0);
                    if (ib < 0) return;
                    final ByteBuffer buf = dec.getInputBuffer(ib);
                    final int sz = buf == null ? -1 : ex.readSampleData(buf, 0);
                    final long t = ex.getSampleTime();
                    if (sz < 0 || t < 0 || t > trimUs + loopLenUs + READ_AHEAD_US) {
                        dec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inEos = true;
                    } else {
                        dec.queueInputBuffer(ib, 0, sz, t, 0);
                        ex.advance();
                    }
                }
            }

            /** Makes the GL texture hold the source frame for output time {@code tUs}. */
            void advance(long tUs) {
                if (slot.image != null) return;                 // photo box: nothing to decode
                if (tUs >= slot.durationMs * 1000L) return;     // box already finished, nothing more to decode
                final long loop = tUs / loopLenUs;
                if (loop != curLoop) startLoop(loop);
                final long needed = trimUs + (tUs % loopLenUs);
                final long deadline = SystemClock.uptimeMillis() + ADVANCE_TIMEOUT_MS;
                while (SystemClock.uptimeMillis() < deadline) {
                    if (held >= 0) {
                        if (heldPts < needed - FRAME_TOL_US && !outEos) {
                            dec.releaseOutputBuffer(held, false);
                            held = -1;
                        } else {
                            renderHeld();
                            return;
                        }
                    }
                    if (outEos) return;                          // clip ended early: keep the last frame on screen
                    feed();
                    final int idx = dec.dequeueOutputBuffer(bi, 5_000);
                    if (idx >= 0) {
                        final boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (eos) outEos = true;
                        if (bi.size == 0) { dec.releaseOutputBuffer(idx, false); continue; }
                        held = idx;
                        heldPts = bi.presentationTimeUs;
                    }
                }
                Log.w(TAG, "clip timed out waiting for a frame: " + slot.path);
            }

            void renderHeld() {
                synchronized (lock) { frameAvail = false; }
                dec.releaseOutputBuffer(held, true);
                held = -1;
                synchronized (lock) {
                    final long until = SystemClock.uptimeMillis() + 1500;
                    while (!frameAvail) {
                        final long left = until - SystemClock.uptimeMillis();
                        if (left <= 0) return;                   // no new image arrived; keep whatever the texture has
                        try { lock.wait(left); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }
                }
                st.updateTexImage();
                st.getTransformMatrix(texMat);
                hasFrame = true;
            }

            void draw(long tUs) {
                final long durUs = Math.max(1L, slot.durationMs * 1000L);
                cropFor(Math.min(1f, tUs / (float) durUs));    // v772: zoom / pan / fade follow the box's own timeline
                final VProg v = slot.image != null ? progImage : progVideo;
                final int target = slot.image != null ? GLES20.GL_TEXTURE_2D : GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
                GLES20.glUseProgram(v.prog);
                GLES20.glUniformMatrix4fv(v.mvp, 1, false, mvp, 0);
                GLES20.glUniformMatrix4fv(v.texMat, 1, false, texMat, 0);
                GLES20.glUniformMatrix4fv(v.cm, 1, false, colorMat, 0);
                GLES20.glUniform1f(v.alpha, anim[3]);
                GLES20.glUniform4f(v.crop, crop[0], crop[1], crop[2], crop[3]);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(target, oesTex);
                GLES20.glUniform1i(v.vid, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskTex);
                GLES20.glUniform1i(v.mask, 1);
                GLES20.glEnableVertexAttribArray(v.aPos);
                GLES20.glVertexAttribPointer(v.aPos, 2, GLES20.GL_FLOAT, false, 0, quad);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            }

            void release() {
                try { if (dec != null) { dec.stop(); } } catch (Throwable ignored) { }
                try { if (dec != null) dec.release(); } catch (Throwable ignored) { }
                try { if (ex != null) ex.release(); } catch (Throwable ignored) { }
                try { if (st != null) st.release(); } catch (Throwable ignored) { }
                try { if (surface != null) surface.release(); } catch (Throwable ignored) { }
            }
        }
    }
}
