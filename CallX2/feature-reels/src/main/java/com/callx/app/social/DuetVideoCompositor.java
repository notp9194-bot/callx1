package com.callx.app.social;

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
import android.opengl.Matrix;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * DuetVideoCompositor v2 — Pure MediaCodec + OpenGL ES 2.0.
 * No FFmpeg. Zero APK size increase.
 *
 * Fixed vs v1:
 *  - Dedicated compositor thread (not cameraExecutor) → no camera conflict
 *  - updateTexImage() always called on GL thread (no race condition)
 *  - Frame sync via decoder output render flag only (no AtomicBoolean listener)
 *  - Original reel loops if shorter than camera recording
 *  - Robust EOS handling for both decoders
 */
public class DuetVideoCompositor {

    private static final String TAG = "DuetVideoCompositor";

    private static final int OUT_W           = 1080;
    private static final int OUT_H           = 1920;
    private static final int OUT_BITRATE     = 4_000_000;
    private static final int OUT_FPS         = 30;
    private static final int IFRAME_INTERVAL = 1;
    private static final long TIMEOUT_US     = 10_000L;

    // EGL
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext  = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface  = EGL14.EGL_NO_SURFACE;

    // GL
    private int glProgram      = -1;
    private int aPosition, aTexCoord, uMVP, uTexMatrix, uTexture;
    private FloatBuffer quadBuf;

    // Textures + SurfaceTextures
    private int          texCam   = -1, texOrig = -1;
    private SurfaceTexture stCam,  stOrig;

    // Transform matrices updated per-frame via updateTexImage()
    private final float[] matCam  = new float[16];
    private final float[] matOrig = new float[16];

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param cameraPath   absolute path to the CameraX-recorded MP4
     * @param originalUrl  URL or file:// path of the original reel
     * @param outputPath   absolute path for the composited output MP4
     * @param layoutMode   0=side-by-side, 1=top-bottom, 2=PiP
     * @param origVolume   unused here (audio mix handled separately)
     * @return true on success
     */
    public boolean composite(String cameraPath, String originalUrl,
                             String outputPath, int layoutMode, float origVolume) {
        Log.d(TAG, "start layout=" + layoutMode + " cam=" + cameraPath);

        if (cameraPath == null || !new File(cameraPath).exists()) {
            Log.e(TAG, "camera file missing"); return false;
        }
        if (originalUrl == null || originalUrl.isEmpty()) {
            Log.e(TAG, "original URL empty"); return false;
        }

        try {
            return pipeline(cameraPath, originalUrl, outputPath, layoutMode);
        } catch (Exception e) {
            Log.e(TAG, "composite failed: " + e.getMessage(), e);
            return false;
        } finally {
            releaseGL();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private boolean pipeline(String camPath, String origUrl,
                              String outPath, int layout) throws Exception {

        // ── 1. Encoder ────────────────────────────────────────────────────
        MediaFormat encFmt = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, OUT_W, OUT_H);
        encFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encFmt.setInteger(MediaFormat.KEY_BIT_RATE,           OUT_BITRATE);
        encFmt.setInteger(MediaFormat.KEY_FRAME_RATE,         OUT_FPS);
        encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,   IFRAME_INTERVAL);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encSurface = encoder.createInputSurface();

        // ── 2. EGL + GL (must happen after createInputSurface) ────────────
        setupEGL(encSurface);
        setupGL();
        encoder.start();

        // ── 3. OES textures + SurfaceTextures ─────────────────────────────
        texCam  = createOESTexture();
        texOrig = createOESTexture();
        stCam   = new SurfaceTexture(texCam);
        stOrig  = new SurfaceTexture(texOrig);
        // No listeners — we call updateTexImage() manually on GL thread
        stCam.setDefaultBufferSize(OUT_W / 2, OUT_H);
        stOrig.setDefaultBufferSize(OUT_W / 2, OUT_H);

        Surface surfCam  = new Surface(stCam);
        Surface surfOrig = new Surface(stOrig);

        // ── 4. Camera extractor + decoder ────────────────────────────────
        MediaExtractor extCam = new MediaExtractor();
        extCam.setDataSource(camPath);
        int camVidTrack = pickTrack(extCam, "video/");
        if (camVidTrack < 0) throw new IOException("no video track in camera file");
        extCam.selectTrack(camVidTrack);
        MediaFormat camFmt = extCam.getTrackFormat(camVidTrack);

        MediaCodec decCam = MediaCodec.createDecoderByType(
                camFmt.getString(MediaFormat.KEY_MIME));
        decCam.configure(camFmt, surfCam, null, 0);
        decCam.start();

        // ── 5. Original extractor + decoder ──────────────────────────────
        MediaExtractor extOrig = new MediaExtractor();
        extOrig.setDataSource(origUrl);
        int origVidTrack = pickTrack(extOrig, "video/");
        if (origVidTrack < 0) throw new IOException("no video track in original");
        extOrig.selectTrack(origVidTrack);
        MediaFormat origFmt = extOrig.getTrackFormat(origVidTrack);

        MediaCodec decOrig = MediaCodec.createDecoderByType(
                origFmt.getString(MediaFormat.KEY_MIME));
        decOrig.configure(origFmt, surfOrig, null, 0);
        decOrig.start();

        // ── 6. Muxer ──────────────────────────────────────────────────────
        MediaMuxer muxer = new MediaMuxer(outPath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Audio track from camera
        MediaExtractor extAud = new MediaExtractor();
        extAud.setDataSource(camPath);
        int audTrack = pickTrack(extAud, "audio/");
        int muxAud   = -1;
        if (audTrack >= 0) {
            extAud.selectTrack(audTrack);
            muxAud = muxer.addTrack(extAud.getTrackFormat(audTrack));
        }

        int  muxVid       = -1;
        boolean muxStarted = false;

        // ── 7. Main loop ──────────────────────────────────────────────────
        boolean camInputEOS  = false;
        boolean origInputEOS = false;
        boolean camOutputEOS = false;
        boolean encEOS       = false;

        // State for both decoders
        boolean camHasFrame  = false;
        boolean origHasFrame = false;
        long    camPtsUs     = 0L;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long encodedFrames = 0;

        while (!encEOS) {

            // ── Feed camera input ─────────────────────────────────────────
            if (!camInputEOS) {
                int idx = decCam.dequeueInputBuffer(TIMEOUT_US);
                if (idx >= 0) {
                    ByteBuffer buf = decCam.getInputBuffer(idx);
                    int n = extCam.readSampleData(buf, 0);
                    if (n < 0) {
                        decCam.queueInputBuffer(idx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        camInputEOS = true;
                    } else {
                        decCam.queueInputBuffer(idx, 0, n, extCam.getSampleTime(), 0);
                        extCam.advance();
                    }
                }
            }

            // ── Feed original input ───────────────────────────────────────
            if (!origInputEOS) {
                int idx = decOrig.dequeueInputBuffer(TIMEOUT_US);
                if (idx >= 0) {
                    ByteBuffer buf = decOrig.getInputBuffer(idx);
                    int n = extOrig.readSampleData(buf, 0);
                    if (n < 0) {
                        // Loop: seek back to start
                        extOrig.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        n = extOrig.readSampleData(buf, 0);
                        if (n < 0) {
                            decOrig.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            origInputEOS = true;
                        } else {
                            decOrig.queueInputBuffer(idx, 0, n,
                                    extOrig.getSampleTime(), 0);
                            extOrig.advance();
                        }
                    } else {
                        decOrig.queueInputBuffer(idx, 0, n,
                                extOrig.getSampleTime(), 0);
                        extOrig.advance();
                    }
                }
            }

            // ── Drain camera output ───────────────────────────────────────
            int camOut = decCam.dequeueOutputBuffer(info, TIMEOUT_US);
            if (camOut >= 0) {
                boolean doRender = info.size > 0;
                decCam.releaseOutputBuffer(camOut, doRender);
                if (doRender) {
                    // Called on GL thread — safe
                    stCam.updateTexImage();
                    stCam.getTransformMatrix(matCam);
                    camHasFrame = true;
                    camPtsUs    = info.presentationTimeUs;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    camOutputEOS = true;
                }
            }

            // ── Drain original output ─────────────────────────────────────
            int origOut = decOrig.dequeueOutputBuffer(info, TIMEOUT_US);
            if (origOut >= 0) {
                boolean doRender = info.size > 0;
                decOrig.releaseOutputBuffer(origOut, doRender);
                if (doRender) {
                    stOrig.updateTexImage();
                    stOrig.getTransformMatrix(matOrig);
                    origHasFrame = true;
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        && origInputEOS) {
                    // Already looping, this shouldn't happen unless truly out of data
                }
            }

            // ── Render + encode when both streams have a frame ────────────
            if (camHasFrame && origHasFrame) {
                GLES20.glViewport(0, 0, OUT_W, OUT_H);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                renderFrame(layout);

                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface,
                        camPtsUs * 1000L);
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);

                camHasFrame = false; // wait for next camera frame
            }

            // Signal EOS to encoder once camera output is done
            if (camOutputEOS) {
                encoder.signalEndOfInputStream();
                camOutputEOS = false; // signal only once
            }

            // ── Drain encoder ─────────────────────────────────────────────
            int encOut = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxStarted) {
                    muxVid = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                    // Copy audio now that muxer is started
                    if (muxAud >= 0) copyAudio(extAud, muxer, muxAud);
                }
            } else if (encOut >= 0) {
                if (muxStarted && muxVid >= 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && info.size > 0) {
                    muxer.writeSampleData(muxVid,
                            encoder.getOutputBuffer(encOut), info);
                    encodedFrames++;
                }
                encoder.releaseOutputBuffer(encOut, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encEOS = true;
                }
            }
        }

        // ── 8. Release everything ─────────────────────────────────────────
        encoder.stop();  encoder.release();
        decCam.stop();   decCam.release();
        decOrig.stop();  decOrig.release();
        extCam.release(); extOrig.release(); extAud.release();
        surfCam.release(); surfOrig.release();
        stCam.release();   stOrig.release();
        if (muxStarted) muxer.stop();
        muxer.release();
        encSurface.release();

        boolean success = encodedFrames > 0 && new File(outPath).length() > 1024;
        Log.d(TAG, "done frames=" + encodedFrames + " size=" + new File(outPath).length()
                + " success=" + success);
        return success;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    private void renderFrame(int layout) {
        switch (layout) {
            case 1: // TOP-BOTTOM
                drawRect(texCam,  matCam,  -1f,  0f,  1f,  1f);  // top half
                drawRect(texOrig, matOrig, -1f, -1f,  1f,  0f);  // bottom half
                break;
            case 2: // PiP
                drawRect(texCam,  matCam,  -1f, -1f,  1f,  1f);        // full screen
                drawRect(texOrig, matOrig,  0.45f, -1f, 1f, -0.35f);   // pip bottom-right
                break;
            default: // SIDE-BY-SIDE
                drawRect(texCam,  matCam,  -1f, -1f,  0f,  1f);  // left
                drawRect(texOrig, matOrig,  0f, -1f,  1f,  1f);  // right
                break;
        }
    }

    /**
     * Draw texId into NDC rect [x0,y0]->[x1,y1].
     * texMatrix comes from SurfaceTexture.getTransformMatrix().
     */
    private void drawRect(int texId, float[] texMatrix,
                           float x0, float y0, float x1, float y1) {
        GLES20.glUseProgram(glProgram);

        // Build MVP that maps unit quad to [x0,y0]->[x1,y1]
        float scaleX = (x1 - x0) * 0.5f;
        float scaleY = (y1 - y0) * 0.5f;
        float transX = (x0 + x1) * 0.5f;
        float transY = (y0 + y1) * 0.5f;

        float[] mvp = new float[16];
        Matrix.setIdentityM(mvp, 0);
        Matrix.translateM(mvp, 0, transX, transY, 0f);
        Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f);

        GLES20.glUniformMatrix4fv(uMVP,       1, false, mvp,       0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(uTexture, 0);

        quadBuf.position(0);
        GLES20.glEnableVertexAttribArray(aPosition);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuf);
        quadBuf.position(2);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EGL
    // ─────────────────────────────────────────────────────────────────────────

    private void setupEGL(Surface encoderSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(eglDisplay, new int[2], 0, new int[2], 1);

        int[] attr = {
            EGL14.EGL_RED_SIZE,         8,
            EGL14.EGL_GREEN_SIZE,       8,
            EGL14.EGL_BLUE_SIZE,        8,
            EGL14.EGL_ALPHA_SIZE,       8,
            EGL14.EGL_RENDERABLE_TYPE,  EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        };
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attr, 0, cfgs, 0, 1, n, 0);

        eglContext = EGL14.eglCreateContext(eglDisplay, cfgs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfgs[0], encoderSurface,
                new int[]{EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private void releaseGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE)
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GL setup
    // ─────────────────────────────────────────────────────────────────────────

    private static final String VS =
        "uniform mat4 uMVPMatrix;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTexCoord;\n" +
        "varying vec2 vTex;\n" +
        "void main() {\n" +
        "  gl_Position = uMVPMatrix * aPosition;\n" +
        "  vTex = (uTexMatrix * aTexCoord).xy;\n" +
        "}\n";

    private static final String FS =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform samplerExternalOES uTexture;\n" +
        "void main() { gl_FragColor = texture2D(uTexture, vTex); }\n";

    private void setupGL() {
        glProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(glProgram, makeShader(GLES20.GL_VERTEX_SHADER,   VS));
        GLES20.glAttachShader(glProgram, makeShader(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(glProgram);

        aPosition  = GLES20.glGetAttribLocation(glProgram,  "aPosition");
        aTexCoord  = GLES20.glGetAttribLocation(glProgram,  "aTexCoord");
        uMVP       = GLES20.glGetUniformLocation(glProgram, "uMVPMatrix");
        uTexMatrix = GLES20.glGetUniformLocation(glProgram, "uTexMatrix");
        uTexture   = GLES20.glGetUniformLocation(glProgram, "uTexture");

        // Unit quad: pos(-1,-1 to 1,1), tex(0,0 to 1,1) interleaved
        float[] q = { -1f,-1f, 0f,0f,  1f,-1f, 1f,0f,  -1f,1f, 0f,1f,  1f,1f, 1f,1f };
        quadBuf = ByteBuffer.allocateDirect(q.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadBuf.put(q).position(0);
    }

    private int makeShader(int type, String src) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, src);
        GLES20.glCompileShader(id);
        return id;
    }

    private int createOESTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private int pickTrack(MediaExtractor ext, String mimePrefix) {
        for (int i = 0; i < ext.getTrackCount(); i++) {
            String m = ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private void copyAudio(MediaExtractor extAud, MediaMuxer muxer, int muxAudTrack) {
        ByteBuffer buf = ByteBuffer.allocate(256 * 1024);
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int n = extAud.readSampleData(buf, 0);
                if (n < 0) break;
                bi.offset = 0; bi.size = n;
                bi.presentationTimeUs = extAud.getSampleTime();
                bi.flags = extAud.getSampleFlags();
                muxer.writeSampleData(muxAudTrack, buf, bi);
                extAud.advance();
            }
        } catch (Exception e) {
            Log.w(TAG, "copyAudio non-fatal: " + e.getMessage());
        }
    }
}
