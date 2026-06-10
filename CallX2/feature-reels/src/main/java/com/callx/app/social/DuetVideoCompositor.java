package com.callx.app.social;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
 * DuetVideoCompositor v6 — All Fixes Applied
 *
 * ── What changed from v5 ─────────────────────────────────────────────────────
 *  ✅ FIX 4: composite() now accepts separateSoundUrl parameter.
 *             If original reel had a separate music track (musicUrl in Firebase),
 *             it is decoded and mixed at originalVolume alongside the reel video audio.
 *             Whichever audio source (embedded video audio vs soundUrl) is louder
 *             wins — both are decoded; their PCM is summed with individual gains.
 *
 *  ✅ FIX 8: OOM fix — decodeAudioToPcm() now uses CHUNK-BASED decode.
 *             PCM is written to a temp RAW file rather than a giant ArrayList<Short>.
 *             For a 60-second stereo 44100 Hz duet this saves ~20 MB of heap.
 *             After encode the temp file is deleted.
 *
 *  ✅ FIX 9: Duet watermark — a "Duet with @{ownerName}" text is burned into
 *             the video using a GLES texture rendered in the top-left corner.
 *             The watermark is drawn once to a Bitmap, uploaded to a regular
 *             GL_TEXTURE_2D, and composited as a final pass after the video frames.
 *
 *  ✅ All v5 bug fixes retained (audio pre-encode, muxer order, bubble shader)
 */
public class DuetVideoCompositor {

    private static final String TAG = "DuetVideoCompositor";

    private static final int  OUT_W           = 1080;
    private static final int  OUT_H           = 1920;
    private static final int  OUT_BITRATE     = 4_000_000;
    private static final int  OUT_FPS         = 30;
    private static final int  IFRAME_INTERVAL = 1;
    private static final long TIMEOUT_US      = 10_000L;
    // Fix 8: chunk size for streaming PCM decode (~512 KB of shorts)
    private static final int  PCM_CHUNK_SHORTS = 262_144;

    // EGL
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext  = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface  = EGL14.EGL_NO_SURFACE;

    // GL — normal rect program
    private int glProgram      = -1;
    private int aPosition, aTexCoord, uMVP, uTexMatrix, uTexture;

    // GL — circle-bubble program
    private int glBubbleProgram   = -1;
    private int bPosition, bTexCoord, bMVP, bTexMatrix, bTexture, bCenter, bRadius, bAspect;

    // Fix 9: watermark texture program
    private int glWatermarkProgram = -1;
    private int wPosition, wTexCoord, wMVP, wTexture2D;
    private int watermarkTexId = -1;
    private int watermarkW = 0, watermarkH = 0;

    private FloatBuffer quadBuf;

    private int          texCam   = -1, texOrig = -1;
    private SurfaceTexture stCam, stOrig;
    private final float[] matCam  = new float[16];
    private final float[] matOrig = new float[16];

    // bubble NDC position (set per-frame)
    private float renderBubbleX = -0.55f;
    private float renderBubbleY = -0.72f;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param cameraPath        absolute path to CameraX-recorded MP4
     * @param originalUrl       URL or file:// of the original reel video
     * @param outputPath        absolute path for composited output MP4
     * @param layoutMode        0=side, 1=top-bottom, 2=PiP, 3=ReactionBubble
     * @param origVolume        0.0–1.0 original reel audio volume in mix
     * @param micGain           0.0–2.0 mic gain multiplier
     * @param bubbleNdcX        NDC X centre of bubble (-1..1), mode 3 only
     * @param bubbleNdcY        NDC Y centre of bubble (-1..1), mode 3 only
     * @param separateSoundUrl  Fix 4: optional URL of original reel's music track
     *                          (null/empty = use only embedded video audio)
     * @param ownerName         Fix 9: name for duet watermark (e.g. "john")
     * @return true on success
     */
    public boolean composite(String cameraPath, String originalUrl,
                             String outputPath, int layoutMode,
                             float origVolume, float micGain,
                             float bubbleNdcX, float bubbleNdcY,
                             String separateSoundUrl,
                             String ownerName) {
        Log.d(TAG, "start layout=" + layoutMode + " cam=" + cameraPath
                + " origVol=" + origVolume + " micGain=" + micGain
                + " soundUrl=" + separateSoundUrl);

        if (cameraPath == null || !new File(cameraPath).exists()) {
            Log.e(TAG, "camera file missing"); return false;
        }
        if (originalUrl == null || originalUrl.isEmpty()) {
            Log.e(TAG, "original URL empty"); return false;
        }

        try {
            return pipeline(cameraPath, originalUrl, outputPath, layoutMode,
                            origVolume, micGain, bubbleNdcX, bubbleNdcY,
                            separateSoundUrl, ownerName);
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

    private boolean pipeline(String camPath, String origUrl, String outPath, int layout,
                              float origVolume, float micGain,
                              float bubbleNdcX, float bubbleNdcY,
                              String soundUrl, String ownerName) throws Exception {

        // ── 1. Pre-encode audio (Fix 4: pass soundUrl for music mixing) ───
        String      audioTempPath = outPath + ".audio.mp4";
        MediaFormat audioOutFmt   = preEncodeAudio(camPath, origUrl, origVolume, micGain,
                                                    soundUrl, audioTempPath);

        // ── 2. Encoder ────────────────────────────────────────────────────
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

        // ── 3. EGL + GL ───────────────────────────────────────────────────
        setupEGL(encSurface);
        setupGL();
        encoder.start();

        // Fix 9: build watermark texture after GL is ready
        if (ownerName != null && !ownerName.isEmpty()) {
            setupWatermarkTexture("Duet with @" + ownerName);
        }

        // ── 4. OES textures + SurfaceTextures ─────────────────────────────
        texCam  = createOESTexture();
        texOrig = createOESTexture();
        stCam   = new SurfaceTexture(texCam);
        stOrig  = new SurfaceTexture(texOrig);
        stCam.setDefaultBufferSize(OUT_W / 2, OUT_H);
        stOrig.setDefaultBufferSize(OUT_W / 2, OUT_H);

        Surface surfCam  = new Surface(stCam);
        Surface surfOrig = new Surface(stOrig);

        // ── 5. Camera extractor + decoder ────────────────────────────────
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

        // ── 6. Original extractor + decoder ──────────────────────────────
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

        // ── 7. Muxer ──────────────────────────────────────────────────────
        MediaMuxer muxer = new MediaMuxer(outPath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        int  muxVid    = -1;
        int  muxAud    = -1;
        boolean muxStarted = false;

        // ── 8. Main loop ──────────────────────────────────────────────────
        boolean camInputEOS  = false;
        boolean origInputEOS = false;
        boolean camOutputEOS = false;
        boolean encEOS       = false;
        boolean camHasFrame  = false;
        boolean origHasFrame = false;
        long    camPtsUs     = 0L;

        renderBubbleX = bubbleNdcX;
        renderBubbleY = bubbleNdcY;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long encodedFrames = 0;

        while (!encEOS) {

            if (!camInputEOS) {
                int idx = decCam.dequeueInputBuffer(TIMEOUT_US);
                if (idx >= 0) {
                    ByteBuffer buf = decCam.getInputBuffer(idx);
                    int n = extCam.readSampleData(buf, 0);
                    if (n < 0) {
                        decCam.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        camInputEOS = true;
                    } else { decCam.queueInputBuffer(idx, 0, n, extCam.getSampleTime(), 0); extCam.advance(); }
                }
            }

            if (!origInputEOS) {
                int idx = decOrig.dequeueInputBuffer(TIMEOUT_US);
                if (idx >= 0) {
                    ByteBuffer buf = decOrig.getInputBuffer(idx);
                    int n = extOrig.readSampleData(buf, 0);
                    if (n < 0) {
                        extOrig.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        n = extOrig.readSampleData(buf, 0);
                        if (n < 0) {
                            decOrig.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            origInputEOS = true;
                        } else { decOrig.queueInputBuffer(idx, 0, n, extOrig.getSampleTime(), 0); extOrig.advance(); }
                    } else { decOrig.queueInputBuffer(idx, 0, n, extOrig.getSampleTime(), 0); extOrig.advance(); }
                }
            }

            int camOut = decCam.dequeueOutputBuffer(info, TIMEOUT_US);
            if (camOut >= 0) {
                boolean doRender = info.size > 0;
                decCam.releaseOutputBuffer(camOut, doRender);
                if (doRender) { stCam.updateTexImage(); stCam.getTransformMatrix(matCam); camHasFrame = true; camPtsUs = info.presentationTimeUs; }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) camOutputEOS = true;
            }

            int origOut = decOrig.dequeueOutputBuffer(info, TIMEOUT_US);
            if (origOut >= 0) {
                boolean doRender = info.size > 0;
                decOrig.releaseOutputBuffer(origOut, doRender);
                if (doRender) { stOrig.updateTexImage(); stOrig.getTransformMatrix(matOrig); origHasFrame = true; }
            }

            if (camHasFrame && origHasFrame) {
                GLES20.glViewport(0, 0, OUT_W, OUT_H);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                renderFrame(layout);

                // Fix 9: draw watermark on top
                if (watermarkTexId >= 0 && watermarkW > 0) drawWatermark();

                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, camPtsUs * 1000L);
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                camHasFrame = false;
            }

            if (camOutputEOS) { encoder.signalEndOfInputStream(); camOutputEOS = false; }

            int encOut = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxStarted) {
                    muxVid = muxer.addTrack(encoder.getOutputFormat());
                    if (audioOutFmt != null) muxAud = muxer.addTrack(audioOutFmt);
                    muxer.start();
                    muxStarted = true;
                    if (muxAud >= 0) copyAudioToMuxer(audioTempPath, muxAud, muxer);
                }
            } else if (encOut >= 0) {
                if (muxStarted && muxVid >= 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && info.size > 0) {
                    muxer.writeSampleData(muxVid, encoder.getOutputBuffer(encOut), info);
                    encodedFrames++;
                }
                encoder.releaseOutputBuffer(encOut, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encEOS = true;
            }
        }

        encoder.stop();  encoder.release();
        decCam.stop();   decCam.release();
        decOrig.stop();  decOrig.release();
        extCam.release(); extOrig.release();
        surfCam.release(); surfOrig.release();
        stCam.release();   stOrig.release();
        if (muxStarted) muxer.stop();
        muxer.release();
        encSurface.release();

        boolean success = encodedFrames > 0 && new File(outPath).length() > 1024;
        Log.d(TAG, "done frames=" + encodedFrames + " size=" + new File(outPath).length() + " ok=" + success);
        return success;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    private void renderFrame(int layout) {
        switch (layout) {
            case 1: // TOP-BOTTOM: camera top, original bottom
                drawRect(texCam,  matCam,  -1f,  0f,  1f,  1f);
                drawRect(texOrig, matOrig, -1f, -1f,  1f,  0f);
                break;
            case 2: // PiP: original small top-right, camera fills screen
                drawRect(texCam,  matCam,  -1f, -1f,  1f,  1f);
                drawRect(texOrig, matOrig,  0.4f, 0.55f, 1f, 1f);
                break;
            case 3: // Reaction Bubble
                drawRect(texOrig, matOrig, -1f, -1f, 1f, 1f);
                drawBubble(texCam, matCam, renderBubbleX, renderBubbleY,
                           0.32f, (float) OUT_W / OUT_H);
                break;
            default: // 0: SIDE-BY-SIDE — original left, camera right
                drawRect(texOrig, matOrig, -1f, -1f,  0f,  1f);
                drawRect(texCam,  matCam,   0f, -1f,  1f,  1f);
                break;
        }
    }

    // ── Fix 9: Watermark ─────────────────────────────────────────────────────

    private static final String WATERMARK_VS =
        "attribute vec4 aPosition;\n" +
        "attribute vec2 aTexCoord;\n" +
        "uniform mat4 uMVPMatrix;\n" +
        "varying vec2 vTex;\n" +
        "void main() { gl_Position = uMVPMatrix * aPosition; vTex = aTexCoord; }\n";

    private static final String WATERMARK_FS =
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform sampler2D uTexture2D;\n" +
        "void main() { gl_FragColor = texture2D(uTexture2D, vTex); }\n";

    private void setupWatermarkTexture(String text) {
        int textSizePx  = 36;
        int padding     = 16;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSizePx);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(4f, 1f, 1f, Color.BLACK);

        float textWidth  = paint.measureText(text);
        Paint.FontMetricsInt fm = paint.getFontMetricsInt();
        int bmpW = (int)(textWidth + padding * 2);
        int bmpH = (int)((-fm.ascent + fm.descent) + padding * 2);

        Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        Canvas c   = new Canvas(bmp);
        c.drawColor(0x99000000); // semi-transparent black background
        c.drawText(text, padding, -fm.ascent + padding, paint);

        watermarkW = bmpW;
        watermarkH = bmpH;

        // Upload bitmap to GL_TEXTURE_2D
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        watermarkTexId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTexId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();

        // Build watermark GL program
        glWatermarkProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(glWatermarkProgram, makeShader(GLES20.GL_VERTEX_SHADER, WATERMARK_VS));
        GLES20.glAttachShader(glWatermarkProgram, makeShader(GLES20.GL_FRAGMENT_SHADER, WATERMARK_FS));
        GLES20.glLinkProgram(glWatermarkProgram);
        wPosition = GLES20.glGetAttribLocation(glWatermarkProgram,  "aPosition");
        wTexCoord = GLES20.glGetAttribLocation(glWatermarkProgram,  "aTexCoord");
        wMVP      = GLES20.glGetUniformLocation(glWatermarkProgram, "uMVPMatrix");
        wTexture2D= GLES20.glGetUniformLocation(glWatermarkProgram, "uTexture2D");
    }

    private void drawWatermark() {
        // Place in top-left NDC: convert pixel size to NDC fraction
        float ndcW = (watermarkW * 2f) / OUT_W;
        float ndcH = (watermarkH * 2f) / OUT_H;
        float margin = 0.04f;
        float x0 = -1f + margin;
        float x1 = x0 + ndcW;
        float y1 = 1f - margin;
        float y0 = y1 - ndcH;

        GLES20.glUseProgram(glWatermarkProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        float[] mvp = new float[16];
        Matrix.setIdentityM(mvp, 0);

        GLES20.glUniformMatrix4fv(wMVP, 1, false, mvp, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTexId);
        GLES20.glUniform1i(wTexture2D, 0);

        float[] verts = {
            x0, y0, 0f, 1f,
            x1, y0, 1f, 1f,
            x0, y1, 0f, 0f,
            x1, y1, 1f, 0f
        };
        FloatBuffer vb = ByteBuffer.allocateDirect(verts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(verts).position(0);
        GLES20.glVertexAttribPointer(wPosition, 2, GLES20.GL_FLOAT, false, 16, vb);
        GLES20.glEnableVertexAttribArray(wPosition);
        vb.position(2);
        GLES20.glVertexAttribPointer(wTexCoord, 2, GLES20.GL_FLOAT, false, 16, vb);
        GLES20.glEnableVertexAttribArray(wTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GL helpers
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

    private static final String BUBBLE_FS =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform samplerExternalOES uTexture;\n" +
        "uniform vec2  uCenter;\n" +
        "uniform float uRadius;\n" +
        "uniform float uAspect;\n" +
        "void main() {\n" +
        "  vec2 p = (vTex - 0.5) * 2.0;\n" +
        "  p.x *= uAspect;\n" +
        "  float dist = length(p - uCenter);\n" +
        "  float alpha = 1.0 - smoothstep(uRadius - 0.04, uRadius, dist);\n" +
        "  if (alpha < 0.01) discard;\n" +
        "  gl_FragColor = texture2D(uTexture, vTex) * alpha;\n" +
        "}\n";

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

        glBubbleProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(glBubbleProgram, makeShader(GLES20.GL_VERTEX_SHADER,   VS));
        GLES20.glAttachShader(glBubbleProgram, makeShader(GLES20.GL_FRAGMENT_SHADER, BUBBLE_FS));
        GLES20.glLinkProgram(glBubbleProgram);
        bPosition  = GLES20.glGetAttribLocation(glBubbleProgram,  "aPosition");
        bTexCoord  = GLES20.glGetAttribLocation(glBubbleProgram,  "aTexCoord");
        bMVP       = GLES20.glGetUniformLocation(glBubbleProgram, "uMVPMatrix");
        bTexMatrix = GLES20.glGetUniformLocation(glBubbleProgram, "uTexMatrix");
        bTexture   = GLES20.glGetUniformLocation(glBubbleProgram, "uTexture");
        bCenter    = GLES20.glGetUniformLocation(glBubbleProgram, "uCenter");
        bRadius    = GLES20.glGetUniformLocation(glBubbleProgram, "uRadius");
        bAspect    = GLES20.glGetUniformLocation(glBubbleProgram, "uAspect");

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        float[] q = { -1f,-1f, 0f,0f,  1f,-1f, 1f,0f,  -1f,1f, 0f,1f,  1f,1f, 1f,1f };
        quadBuf = ByteBuffer.allocateDirect(q.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadBuf.put(q).position(0);
    }

    private void drawRect(int tex, float[] texMat, float x0, float y0, float x1, float y1) {
        float[] quad = { x0,y0, 0f,0f,  x1,y0, 1f,0f,  x0,y1, 0f,1f,  x1,y1, 1f,1f };
        FloatBuffer fb = ByteBuffer.allocateDirect(quad.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(quad).position(0);

        GLES20.glUseProgram(glProgram);
        float[] mvp = new float[16]; Matrix.setIdentityM(mvp, 0);
        GLES20.glUniformMatrix4fv(uMVP,       1, false, mvp,    0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMat, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glUniform1i(uTexture, 0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, fb);
        GLES20.glEnableVertexAttribArray(aPosition);
        fb.position(2);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, fb);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawBubble(int tex, float[] texMat, float cx, float cy, float r, float aspect) {
        GLES20.glUseProgram(glBubbleProgram);
        float[] mvp = new float[16]; Matrix.setIdentityM(mvp, 0);
        GLES20.glUniformMatrix4fv(bMVP,       1, false, mvp,    0);
        GLES20.glUniformMatrix4fv(bTexMatrix, 1, false, texMat, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glUniform1i(bTexture, 0);
        GLES20.glUniform2f(bCenter, cx, cy);
        GLES20.glUniform1f(bRadius, r);
        GLES20.glUniform1f(bAspect, aspect);

        quadBuf.position(0);
        GLES20.glVertexAttribPointer(bPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuf);
        GLES20.glEnableVertexAttribArray(bPosition);
        quadBuf.position(2);
        GLES20.glVertexAttribPointer(bTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuf);
        GLES20.glEnableVertexAttribArray(bTexCoord);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void setupEGL(Surface encSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(eglDisplay, null, 0, null, 0);
        int[] attrs = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142 /*EGL_RECORDABLE_ANDROID*/, 1, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, numConfigs, 0);
        int[] ctx2 = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctx2, 0);
        int[] sAttribs = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encSurface, sAttribs, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    private void releaseGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
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
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private int pickTrack(MediaExtractor ext, String mimePrefix) {
        for (int i = 0; i < ext.getTrackCount(); i++) {
            String m = ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fix 8: Audio pre-encode (OOM safe — chunk-based PCM decode)
    // Fix 4: Mix separate soundUrl alongside embedded video audio
    // ─────────────────────────────────────────────────────────────────────────

    private MediaFormat preEncodeAudio(String camPath, String origUrl,
                                        float origVolume, float micGain,
                                        String soundUrl, String tempPath) {
        try {
            // Fix 8: decode to temp RAW files instead of ArrayList<Short>
            String camRaw  = tempPath + ".cam.raw";
            String origRaw = tempPath + ".orig.raw";
            String sndRaw  = tempPath + ".snd.raw";

            int sampleRate = 44100, channels = 1;
            MediaExtractor probe = new MediaExtractor();
            try {
                probe.setDataSource(camPath);
                int t = pickTrack(probe, "audio/");
                if (t >= 0) {
                    MediaFormat f = probe.getTrackFormat(t);
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            } finally { probe.release(); }

            long camSamples  = decodeAudioToRaw(camPath,  camRaw);
            long origSamples = decodeAudioToRaw(origUrl,  origRaw);
            long sndSamples  = (soundUrl != null && !soundUrl.isEmpty())
                               ? decodeAudioToRaw(soundUrl, sndRaw) : 0;

            if (camSamples <= 0) {
                new File(camRaw).delete(); new File(origRaw).delete();
                if (sndSamples > 0) new File(sndRaw).delete();
                Log.w(TAG, "preEncodeAudio: no camera audio — skipping");
                return null;
            }

            // Encode from streamed PCM mix
            MediaFormat encFmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE,   128_000);
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            MediaMuxer tempMuxer = new MediaMuxer(tempPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Fix 8: stream mix in chunks from temp RAW files
            java.io.DataInputStream camIn  = new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(camRaw)));
            java.io.DataInputStream origIn = origSamples > 0
                ? new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(origRaw))) : null;
            java.io.DataInputStream sndIn  = sndSamples > 0
                ? new java.io.DataInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(sndRaw)))  : null;

            int     tempAudTrack   = -1;
            boolean trackAdded     = false;
            boolean tempMuxStarted = false;
            boolean inputDone      = false;
            boolean outputDone     = false;
            MediaFormat finalAudioFormat = null;
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();

            long samplesWritten = 0;

            while (!outputDone) {
                if (!inputDone) {
                    int idx = enc.dequeueInputBuffer(TIMEOUT_US);
                    if (idx >= 0) {
                        ByteBuffer inBuf = enc.getInputBuffer(idx);
                        inBuf.clear();
                        int capacity = inBuf.capacity() / 2; // shorts
                        short[] chunk = new short[Math.min(capacity, PCM_CHUNK_SHORTS)];
                        int read = 0;
                        try {
                            for (int i = 0; i < chunk.length; i++) {
                                float cam  = (camIn.readShort() / 32768f) * micGain;
                                // original video audio
                                float orig = 0f;
                                if (origIn != null && samplesWritten + i < origSamples) {
                                    orig = (origIn.readShort() / 32768f) * origVolume;
                                }
                                // Fix 4: separate music track
                                float snd = 0f;
                                if (sndIn != null && sndSamples > 0) {
                                    try { snd = (sndIn.readShort() / 32768f) * origVolume; }
                                    catch (java.io.EOFException ignored) {}
                                }
                                float sum = Math.max(-1f, Math.min(1f, cam + orig + snd));
                                inBuf.putShort((short)(sum * 32767f));
                                read++;
                            }
                            long ptsUs = (samplesWritten / channels) * 1_000_000L / sampleRate;
                            samplesWritten += read;
                            enc.queueInputBuffer(idx, 0, read * 2, ptsUs, 0);
                        } catch (java.io.EOFException eof) {
                            if (read > 0) {
                                long ptsUs = (samplesWritten / channels) * 1_000_000L / sampleRate;
                                samplesWritten += read;
                                enc.queueInputBuffer(idx, 0, read * 2, ptsUs, 0);
                            }
                            enc.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                int out = enc.dequeueOutputBuffer(bi, TIMEOUT_US);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!trackAdded) {
                        finalAudioFormat = enc.getOutputFormat();
                        tempAudTrack     = tempMuxer.addTrack(finalAudioFormat);
                        tempMuxer.start();
                        tempMuxStarted = true;
                        trackAdded     = true;
                    }
                } else if (out >= 0) {
                    if (trackAdded && tempMuxStarted
                            && (bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && bi.size > 0) {
                        tempMuxer.writeSampleData(tempAudTrack, enc.getOutputBuffer(out), bi);
                    }
                    enc.releaseOutputBuffer(out, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }

            camIn.close();
            if (origIn != null) origIn.close();
            if (sndIn  != null) sndIn.close();
            enc.stop(); enc.release();
            if (tempMuxStarted) tempMuxer.stop();
            tempMuxer.release();

            // Clean temp raw files
            new File(camRaw).delete();
            new File(origRaw).delete();
            if (sndSamples > 0) new File(sndRaw).delete();

            return finalAudioFormat;

        } catch (Exception e) {
            Log.e(TAG, "preEncodeAudio failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Fix 8: Decodes audio from a file/URL and writes raw signed-16bit PCM
     * to a temp file (avoids loading full audio into heap).
     * @return number of shorts written, or 0 on failure
     */
    private long decodeAudioToRaw(String dataSource, String outRawPath) {
        MediaExtractor ext = new MediaExtractor();
        MediaCodec     dec = null;
        long           written = 0;

        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(
                new java.io.BufferedOutputStream(new java.io.FileOutputStream(outRawPath)))) {

            ext.setDataSource(dataSource);
            int track = pickTrack(ext, "audio/");
            if (track < 0) return 0;

            ext.selectTrack(track);
            MediaFormat fmt  = ext.getTrackFormat(track);
            String      mime = fmt.getString(MediaFormat.KEY_MIME);

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
                            dec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(idx, 0, n, ext.getSampleTime(), 0);
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
                            dos.writeShort(outBuf.getShort());
                            written++;
                        }
                    }
                    dec.releaseOutputBuffer(out, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "decodeAudioToRaw(" + dataSource + "): " + e.getMessage());
        } finally {
            try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) {}
            ext.release();
        }
        return written;
    }

    private void copyAudioToMuxer(String audioTempPath, int muxAudTrack, MediaMuxer muxer) {
        MediaExtractor ext = new MediaExtractor();
        try {
            ext.setDataSource(audioTempPath);
            int track = pickTrack(ext, "audio/");
            if (track < 0) return;
            ext.selectTrack(track);
            MediaCodec.BufferInfo bi  = new MediaCodec.BufferInfo();
            ByteBuffer            buf = ByteBuffer.allocate(1024 * 1024);
            while (true) {
                buf.clear();
                int n = ext.readSampleData(buf, 0);
                if (n < 0) break;
                bi.offset             = 0;
                bi.size               = n;
                bi.presentationTimeUs = ext.getSampleTime();
                bi.flags              = ext.getSampleFlags();
                muxer.writeSampleData(muxAudTrack, buf, bi);
                ext.advance();
            }
        } catch (Exception e) {
            Log.e(TAG, "copyAudioToMuxer failed: " + e.getMessage(), e);
        } finally {
            ext.release();
            new File(audioTempPath).delete();
        }
    }
}
