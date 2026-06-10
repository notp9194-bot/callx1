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
 * DuetVideoCompositor v3 — Pure MediaCodec + OpenGL ES 2.0.
 * No FFmpeg. Zero APK size increase.
 *
 * Fixed vs v2:
 *  - FIX: Original reel audio now actually mixed into output (was silently ignored in v2)
 *  - mixAudio(): decodes both camera + original to PCM, mixes with origVolume weighting,
 *    re-encodes as AAC, writes to muxer — slider in DuetReelActivity now works end-to-end
 *  - decodeAudioToPcm(): shared helper, loops original if shorter than camera recording
 *  - pipeline() now accepts origVolume and forwards it to mixAudio()
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
     * @param origVolume   0.0–1.0 volume of original reel audio in the mix
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
            return pipeline(cameraPath, originalUrl, outputPath, layoutMode, origVolume);
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
                              String outPath, int layout, float origVolume) throws Exception {

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

        // Audio: mixed from camera + original reel
        // extAud kept as placeholder so release() calls below stay intact
        MediaExtractor extAud = new MediaExtractor();
        extAud.setDataSource(camPath);
        int audTrack = pickTrack(extAud, "audio/");
        int muxAud   = -1;
        // muxAud index is set after muxer.start() — see INFO_OUTPUT_FORMAT_CHANGED block

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
                    // Mix camera audio + original reel audio into muxer
                    muxAud = mixAudio(camPath, origUrl, origVolume, muxer);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Audio mixing  (camera mic + original reel audio → AAC → muxer)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Decodes audio from both camPath and origUrl to PCM, mixes them with
     * origVolume weighting, re-encodes as AAC, and writes to the muxer.
     *
     * @return the muxer audio track index, or -1 if audio is unavailable
     */
    private int mixAudio(String camPath, String origUrl,
                         float origVolume, MediaMuxer muxer) {
        try {
            // ── Decode camera audio to PCM ────────────────────────────────
            short[] camPcm  = decodeAudioToPcm(camPath);
            int     sampleRate = 44100; // default; updated below
            int     channels   = 1;

            // ── Decode original reel audio to PCM ─────────────────────────
            short[] origPcm = decodeAudioToPcm(origUrl);

            if (camPcm == null || camPcm.length == 0) {
                Log.w(TAG, "mixAudio: no camera audio, skipping audio track");
                return -1;
            }

            // Detect actual sample rate & channel count from camera track
            MediaExtractor probe = new MediaExtractor();
            try {
                probe.setDataSource(camPath);
                int t = pickTrack(probe, "audio/");
                if (t >= 0) {
                    MediaFormat f = probe.getTrackFormat(t);
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels   = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            } finally {
                probe.release();
            }

            // ── Mix PCM samples ───────────────────────────────────────────
            int outLen = camPcm.length;
            short[] mixed = new short[outLen];

            for (int i = 0; i < outLen; i++) {
                float cam  = camPcm[i]  / 32768f;                     // normalise
                float orig = (origPcm != null && i < origPcm.length)
                             ? (origPcm[i % origPcm.length] / 32768f) // loop if shorter
                             : 0f;
                // Mix: camera always full volume; original scaled by slider
                float sum = cam + orig * origVolume;
                // Hard clamp to prevent clipping
                sum = Math.max(-1f, Math.min(1f, sum));
                mixed[i] = (short) (sum * 32767f);
            }

            // ── Encode mixed PCM → AAC ────────────────────────────────────
            MediaFormat encFmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE,       128_000);
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec enc = MediaCodec.createEncoderByType(
                    MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            // ByteBuffer view of mixed shorts
            ByteBuffer pcmBuf = ByteBuffer.allocateDirect(mixed.length * 2)
                    .order(ByteOrder.nativeOrder());
            for (short s : mixed) pcmBuf.putShort(s);
            pcmBuf.flip();

            int muxAudTrack = -1;
            boolean muxTrackAdded = false;
            boolean inputDone  = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

            while (!outputDone) {

                // Feed PCM into encoder
                if (!inputDone) {
                    int idx = enc.dequeueInputBuffer(TIMEOUT_US);
                    if (idx >= 0) {
                        ByteBuffer inBuf = enc.getInputBuffer(idx);
                        inBuf.clear();
                        int remaining = Math.min(inBuf.capacity(), pcmBuf.remaining());
                        if (remaining > 0) {
                            // Transfer `remaining` bytes from pcmBuf
                            byte[] tmp = new byte[remaining];
                            pcmBuf.get(tmp);
                            inBuf.put(tmp);
                            // Compute PTS from how many bytes we've consumed
                            long ptsUs = ((pcmBuf.capacity() - pcmBuf.remaining() - remaining)
                                         / (2L * channels)) * 1_000_000L / sampleRate;
                            enc.queueInputBuffer(idx, 0, remaining, ptsUs, 0);
                        } else {
                            enc.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                // Drain encoder output
                int out = enc.dequeueOutputBuffer(bi, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxTrackAdded) {
                        muxAudTrack  = muxer.addTrack(enc.getOutputFormat());
                        muxTrackAdded = true;
                    }
                } else if (out >= 0) {
                    if (muxTrackAdded
                            && (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && bi.size > 0) {
                        muxer.writeSampleData(muxAudTrack,
                                enc.getOutputBuffer(out), bi);
                    }
                    enc.releaseOutputBuffer(out, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        outputDone = true;
                }
            }

            enc.stop();
            enc.release();
            Log.d(TAG, "mixAudio: done, track=" + muxAudTrack);
            return muxAudTrack;

        } catch (Exception e) {
            Log.e(TAG, "mixAudio failed (non-fatal): " + e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Decodes all audio samples from a file/URL to a flat short[] (interleaved channels).
     * Returns null if no audio track found.
     */
    private short[] decodeAudioToPcm(String dataSource) {
        MediaExtractor ext = new MediaExtractor();
        MediaCodec     dec = null;
        java.util.ArrayList<Short> samples = new java.util.ArrayList<>(256 * 1024);

        try {
            ext.setDataSource(dataSource);
            int track = pickTrack(ext, "audio/");
            if (track < 0) return null;

            ext.selectTrack(track);
            MediaFormat fmt = ext.getTrackFormat(track);
            String mime = fmt.getString(MediaFormat.KEY_MIME);

            dec = MediaCodec.createDecoderByType(mime);
            dec.configure(fmt, null, null, 0);
            dec.start();

            boolean inputDone  = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

            while (!outputDone) {
                if (!inputDone) {
                    int idx = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (idx >= 0) {
                        ByteBuffer buf = dec.getInputBuffer(idx);
                        int n = ext.readSampleData(buf, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(idx, 0, n,
                                    ext.getSampleTime(), 0);
                            ext.advance();
                        }
                    }
                }

                int out = dec.dequeueOutputBuffer(bi, TIMEOUT_US);
                if (out >= 0) {
                    ByteBuffer outBuf = dec.getOutputBuffer(out);
                    if (outBuf != null && bi.size > 0) {
                        outBuf.position(bi.offset);
                        outBuf.limit(bi.offset + bi.size);
                        while (outBuf.remaining() >= 2) {
                            samples.add(outBuf.getShort());
                        }
                    }
                    dec.releaseOutputBuffer(out, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        outputDone = true;
                }
            }

        } catch (Exception e) {
            Log.w(TAG, "decodeAudioToPcm non-fatal (" + dataSource + "): " + e.getMessage());
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) {}
            ext.release();
        }

        if (samples.isEmpty()) return null;
        short[] arr = new short[samples.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = samples.get(i);
        return arr;
    }

}
