package com.callx.app.social;

import android.content.Context;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DuetVideoCompositor — Pure Android MediaCodec + OpenGL ES 2.0 implementation.
 * No FFmpeg, no native libraries. Zero APK size increase.
 *
 * Renders two video streams side-by-side (or top-bottom / PiP) into a single MP4.
 *
 * Layout modes:
 *   LAYOUT_SIDE_BY_SIDE (0) — left: camera, right: original
 *   LAYOUT_TOP_BOTTOM   (1) — top: camera, bottom: original
 *   LAYOUT_REACT_PIP    (2) — camera full-screen, original small PiP (bottom-right)
 *
 * Audio: uses camera recording's audio track (mic audio).
 * Original reel audio is NOT mixed in (separate AudioMixHelper handles that if needed).
 */
public class DuetVideoCompositor {

    private static final String TAG = "DuetVideoCompositor";

    // Output video settings
    private static final int OUTPUT_WIDTH    = 1080;
    private static final int OUTPUT_HEIGHT   = 1920;
    private static final int OUTPUT_BITRATE  = 4_000_000; // 4 Mbps
    private static final int OUTPUT_FPS      = 30;
    private static final int IFRAME_INTERVAL = 1;

    // EGL / GL objects
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext  = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface  = EGL14.EGL_NO_SURFACE;

    // GL texture IDs for the two video streams
    private int texCam      = -1;
    private int texOriginal = -1;

    // SurfaceTextures fed by decoder output surfaces
    private SurfaceTexture stCam;
    private SurfaceTexture stOriginal;
    private final AtomicBoolean camFrameReady      = new AtomicBoolean(false);
    private final AtomicBoolean originalFrameReady = new AtomicBoolean(false);

    // GL program
    private int glProgram = -1;
    private int aPositionLoc, aTexCoordLoc, uMvpMatrixLoc, uTexMatrixLoc, uTextureLoc;

    // Vertex data — full-screen quad
    private static final float[] FULL_QUAD_COORDS = {
        -1f, -1f,  0f, 0f,
         1f, -1f,  1f, 0f,
        -1f,  1f,  0f, 1f,
         1f,  1f,  1f, 1f,
    };

    private FloatBuffer fullQuadBuf;

    /**
     * Public entry point. Called from background thread.
     *
     * @param cameraPath    absolute path of the CameraX-recorded MP4
     * @param originalUrl   URL of the original reel (HTTP/HTTPS or file://)
     * @param outputPath    absolute path for the composited output MP4
     * @param layoutMode    0=side-by-side, 1=top-bottom, 2=PiP
     * @param originalVolume ignored here — audio mixing handled by AudioMixHelper
     * @return true on success, false on any error
     */
    public boolean composite(String cameraPath, String originalUrl,
                             String outputPath, int layoutMode, float originalVolume) {
        Log.d(TAG, "composite() start — layout=" + layoutMode);

        if (cameraPath == null || !new File(cameraPath).exists()) {
            Log.e(TAG, "Camera file missing: " + cameraPath);
            return false;
        }
        if (originalUrl == null || originalUrl.isEmpty()) {
            Log.e(TAG, "Original URL empty");
            return false;
        }

        try {
            return runComposite(cameraPath, originalUrl, outputPath, layoutMode);
        } catch (Exception e) {
            Log.e(TAG, "composite() failed: " + e.getMessage(), e);
            return false;
        } finally {
            releaseGL();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private boolean runComposite(String camPath, String origUrl,
                                  String outputPath, int layoutMode) throws Exception {

        // 1. Setup encoder
        MediaFormat encFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, OUTPUT_WIDTH, OUTPUT_HEIGHT);
        encFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encFormat.setInteger(MediaFormat.KEY_BIT_RATE,      OUTPUT_BITRATE);
        encFormat.setInteger(MediaFormat.KEY_FRAME_RATE,    OUTPUT_FPS);
        encFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        // 2. EGL setup (must happen after encoder.configure so we get the input Surface)
        setupEGL(encoder.createInputSurface());
        encoder.start();

        // 3. Setup GL program + textures
        setupGLProgram();
        texCam      = createOESTexture();
        texOriginal = createOESTexture();

        stCam      = new SurfaceTexture(texCam);
        stOriginal = new SurfaceTexture(texOriginal);
        stCam.setOnFrameAvailableListener(st -> camFrameReady.set(true));
        stOriginal.setOnFrameAvailableListener(st -> originalFrameReady.set(true));

        // 4. Create decoder surfaces
        Surface surfCam      = new Surface(stCam);
        Surface surfOriginal = new Surface(stOriginal);

        // 5. Setup extractors + decoders
        MediaExtractor extCam = new MediaExtractor();
        extCam.setDataSource(camPath);
        int camVideoTrack = selectVideoTrack(extCam);
        if (camVideoTrack < 0) throw new IOException("No video track in camera file");
        extCam.selectTrack(camVideoTrack);
        MediaFormat camFmt = extCam.getTrackFormat(camVideoTrack);

        MediaExtractor extOrig = new MediaExtractor();
        extOrig.setDataSource(origUrl);
        int origVideoTrack = selectVideoTrack(extOrig);
        if (origVideoTrack < 0) throw new IOException("No video track in original URL");
        extOrig.selectTrack(origVideoTrack);
        MediaFormat origFmt = extOrig.getTrackFormat(origVideoTrack);

        MediaCodec decCam  = MediaCodec.createDecoderByType(
            camFmt.getString(MediaFormat.KEY_MIME));
        MediaCodec decOrig = MediaCodec.createDecoderByType(
            origFmt.getString(MediaFormat.KEY_MIME));

        decCam.configure(camFmt,   surfCam,      null, 0);
        decOrig.configure(origFmt, surfOriginal,  null, 0);
        decCam.start();
        decOrig.start();

        // 6. Setup muxer — one video track + one audio track (from camera)
        MediaMuxer muxer = new MediaMuxer(outputPath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxVideoTrack = -1;
        int muxAudioTrack = -1;
        boolean muxerStarted = false;

        // Copy audio from camera file
        MediaExtractor extAudio = new MediaExtractor();
        extAudio.setDataSource(camPath);
        int camAudioTrack = selectAudioTrack(extAudio);
        if (camAudioTrack >= 0) {
            extAudio.selectTrack(camAudioTrack);
            muxAudioTrack = muxer.addTrack(extAudio.getTrackFormat(camAudioTrack));
        } else {
            extAudio.release();
            extAudio = null;
        }

        // 7. Decode + composite loop
        boolean camEOS   = false;
        boolean origEOS  = false;
        boolean encEOS   = false;

        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        long encodedFrameCount = 0;
        long camPresentationUs = 0;

        final long TIMEOUT_US = 10_000; // 10ms

        while (!encEOS) {

            // ── Feed camera decoder ───────────────────────────────────────
            if (!camEOS) {
                int inIdx = decCam.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer buf = decCam.getInputBuffer(inIdx);
                    int sampleSize = extCam.readSampleData(buf, 0);
                    if (sampleSize < 0) {
                        decCam.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        camEOS = true;
                    } else {
                        long pts = extCam.getSampleTime();
                        decCam.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                        extCam.advance();
                    }
                }
            }

            // ── Feed original decoder ─────────────────────────────────────
            if (!origEOS) {
                int inIdx = decOrig.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer buf = decOrig.getInputBuffer(inIdx);
                    int sampleSize = extOrig.readSampleData(buf, 0);
                    if (sampleSize < 0) {
                        decOrig.queueInputBuffer(inIdx, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        origEOS = true;
                    } else {
                        decOrig.queueInputBuffer(inIdx, 0, sampleSize,
                            extOrig.getSampleTime(), 0);
                        extOrig.advance();
                    }
                }
            }

            // ── Drain camera decoder output ───────────────────────────────
            MediaCodec.BufferInfo camInfo = new MediaCodec.BufferInfo();
            int camOutIdx = decCam.dequeueOutputBuffer(camInfo, TIMEOUT_US);
            if (camOutIdx >= 0) {
                boolean render = (camInfo.size > 0);
                decCam.releaseOutputBuffer(camOutIdx, render);
                if (render) {
                    // Wait for frame to arrive on SurfaceTexture
                    waitForFrame(camFrameReady, 100);
                    stCam.updateTexImage();
                    camPresentationUs = camInfo.presentationTimeUs;
                }
                if ((camInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Camera decoder EOS");
                }
            }

            // ── Drain original decoder output ─────────────────────────────
            MediaCodec.BufferInfo origInfo = new MediaCodec.BufferInfo();
            int origOutIdx = decOrig.dequeueOutputBuffer(origInfo, TIMEOUT_US);
            if (origOutIdx >= 0) {
                boolean render = (origInfo.size > 0);
                decOrig.releaseOutputBuffer(origOutIdx, render);
                if (render) {
                    waitForFrame(originalFrameReady, 100);
                    stOriginal.updateTexImage();
                }
                if ((origInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "Original decoder EOS");
                    // Loop original reel from start
                    extOrig.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    origEOS = false;
                    decOrig.flush();
                }
            }

            // ── Render composite frame into encoder surface ───────────────
            GLES20.glViewport(0, 0, OUTPUT_WIDTH, OUTPUT_HEIGHT);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            renderComposite(layoutMode);

            // Present frame to encoder
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface,
                camPresentationUs * 1000L);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);

            // ── Drain encoder output ──────────────────────────────────────
            int encOutIdx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
            if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted) {
                    muxVideoTrack = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;

                    // Copy audio BEFORE main encode loop output so we start together
                    if (extAudio != null && muxAudioTrack >= 0) {
                        copyAudioTrack(extAudio, muxer, muxAudioTrack, camPath);
                    }
                }
            } else if (encOutIdx >= 0) {
                ByteBuffer encBuf = encoder.getOutputBuffer(encOutIdx);
                if (encBuf != null && muxerStarted && muxVideoTrack >= 0) {
                    if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        muxer.writeSampleData(muxVideoTrack, encBuf, encInfo);
                        encodedFrameCount++;
                    }
                }
                encoder.releaseOutputBuffer(encOutIdx, false);
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encEOS = true;
                }
            }

            // Stop when camera is exhausted + we've encoded enough frames
            if (camEOS && encodedFrameCount > 0) {
                encoder.signalEndOfInputStream();
            }
        }

        // 8. Cleanup
        encoder.stop();    encoder.release();
        decCam.stop();     decCam.release();
        decOrig.stop();    decOrig.release();
        extCam.release();  extOrig.release();
        if (extAudio != null) extAudio.release();
        if (muxerStarted) muxer.stop();
        muxer.release();
        surfCam.release();
        surfOriginal.release();
        stCam.release();
        stOriginal.release();

        Log.d(TAG, "composite() done — frames=" + encodedFrameCount + " output=" + outputPath);
        return encodedFrameCount > 0 && new File(outputPath).length() > 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OpenGL rendering
    // ─────────────────────────────────────────────────────────────────────────

    private void renderComposite(int layoutMode) {
        float[] texMatrix = new float[16];

        switch (layoutMode) {
            case 1: // TOP-BOTTOM — camera top half, original bottom half
                // Camera: top half  (y: 0 → +1  in NDC)
                stCam.getTransformMatrix(texMatrix);
                drawQuad(texCam, texMatrix, -1f, 0f, 1f, 1f);   // left=-1, bottom=0, right=1, top=1
                // Original: bottom half (y: -1 → 0)
                stOriginal.getTransformMatrix(texMatrix);
                drawQuad(texOriginal, texMatrix, -1f, -1f, 1f, 0f);
                break;

            case 2: // PiP — camera fills screen, original small bottom-right
                stCam.getTransformMatrix(texMatrix);
                drawQuad(texCam, texMatrix, -1f, -1f, 1f, 1f);  // full screen
                stOriginal.getTransformMatrix(texMatrix);
                drawQuad(texOriginal, texMatrix, 0.4f, -1f, 1f, -0.4f); // bottom-right pip
                break;

            default: // SIDE-BY-SIDE — camera left, original right
                stCam.getTransformMatrix(texMatrix);
                drawQuad(texCam, texMatrix, -1f, -1f, 0f, 1f);  // left half
                stOriginal.getTransformMatrix(texMatrix);
                drawQuad(texOriginal, texMatrix, 0f, -1f, 1f, 1f); // right half
                break;
        }
    }

    /** Draw a textured quad into NDC rect [x0,y0] → [x1,y1]. */
    private void drawQuad(int texId, float[] texMatrix,
                           float x0, float y0, float x1, float y1) {
        GLES20.glUseProgram(glProgram);

        // MVP: identity (NDC coords directly)
        float[] mvp = new float[16];
        Matrix.setIdentityM(mvp, 0);

        // Scale + translate to target rect
        float scaleX = (x1 - x0) / 2f;
        float scaleY = (y1 - y0) / 2f;
        float transX = (x0 + x1) / 2f;
        float transY = (y0 + y1) / 2f;
        Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f);
        float[] trans = new float[16];
        Matrix.setIdentityM(trans, 0);
        Matrix.translateM(trans, 0, transX / scaleX, transY / scaleY, 0f);
        float[] combined = new float[16];
        Matrix.multiplyMM(combined, 0, trans, 0, mvp, 0);

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(uTextureLoc, 0);

        // Matrices
        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, combined, 0);
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0);

        // Vertex data (position + texcoord interleaved, stride=4 floats)
        fullQuadBuf.position(0);
        GLES20.glEnableVertexAttribArray(aPositionLoc);
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 16, fullQuadBuf);

        fullQuadBuf.position(2);
        GLES20.glEnableVertexAttribArray(aTexCoordLoc);
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 16, fullQuadBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EGL setup
    // ─────────────────────────────────────────────────────────────────────────

    private void setupEGL(Surface encoderSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] version = new int[2];
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

        int[] attribList = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0);

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0],
            EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);

        int[] surfAttribs = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0],
            encoderSurface, surfAttribs, 0);

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GL program
    // ─────────────────────────────────────────────────────────────────────────

    private static final String VERTEX_SHADER =
        "uniform mat4 uMVPMatrix;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTexCoord;\n" +
        "varying vec2 vTexCoord;\n" +
        "void main() {\n" +
        "  gl_Position = uMVPMatrix * aPosition;\n" +
        "  vTexCoord = (uTexMatrix * aTexCoord).xy;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTexCoord;\n" +
        "uniform samplerExternalOES uTexture;\n" +
        "void main() {\n" +
        "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
        "}\n";

    private void setupGLProgram() {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER,   VERTEX_SHADER);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        glProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(glProgram, vs);
        GLES20.glAttachShader(glProgram, fs);
        GLES20.glLinkProgram(glProgram);

        aPositionLoc  = GLES20.glGetAttribLocation(glProgram,  "aPosition");
        aTexCoordLoc  = GLES20.glGetAttribLocation(glProgram,  "aTexCoord");
        uMvpMatrixLoc = GLES20.glGetUniformLocation(glProgram, "uMVPMatrix");
        uTexMatrixLoc = GLES20.glGetUniformLocation(glProgram, "uTexMatrix");
        uTextureLoc   = GLES20.glGetUniformLocation(glProgram, "uTexture");

        // Build vertex buffer
        fullQuadBuf = ByteBuffer.allocateDirect(FULL_QUAD_COORDS.length * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fullQuadBuf.put(FULL_QUAD_COORDS).position(0);
    }

    private int compileShader(int type, String src) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, src);
        GLES20.glCompileShader(id);
        return id;
    }

    private int createOESTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void releaseGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }

    private int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return -1;
    }

    /**
     * Copy entire audio track from camera file into muxer.
     * Runs synchronously — called once after muxer.start().
     */
    private void copyAudioTrack(MediaExtractor extAudio, MediaMuxer muxer,
                                  int muxAudioTrack, String camPath) {
        ByteBuffer buf = ByteBuffer.allocate(1024 * 256);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int n = extAudio.readSampleData(buf, 0);
                if (n < 0) break;
                info.offset         = 0;
                info.size           = n;
                info.presentationTimeUs = extAudio.getSampleTime();
                info.flags          = extAudio.getSampleFlags();
                muxer.writeSampleData(muxAudioTrack, buf, info);
                extAudio.advance();
            }
        } catch (Exception e) {
            Log.w(TAG, "copyAudioTrack error (non-fatal): " + e.getMessage());
        }
    }

    /** Busy-wait until a SurfaceTexture frame is available, up to maxMs. */
    private void waitForFrame(AtomicBoolean flag, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (!flag.getAndSet(false)) {
            if (System.currentTimeMillis() > deadline) return;
            try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        }
    }
}
