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
 * DuetVideoCompositor v5 — Pure MediaCodec + OpenGL ES 2.0.
 * No FFmpeg. Zero APK size increase.
 *
 * Added in v5:
 *  - NEW: LAYOUT_REACTION_BUBBLE (3) — original reel fills full screen,
 *    camera is composited as a circular bubble (bottom-left corner).
 *    Circle clipping done in a dedicated GL program with a circle-mask fragment shader.
 *    bubbleX / bubbleY (NDC -1..1) let the caller position the bubble from Activity.
 *
 * Fixed vs v3:
 *  - FIX: Audio track was silently dropped in v3 because mixAudio() called
 *    muxer.addTrack() AFTER muxer.start() — illegal per MediaMuxer contract.
 *  - NEW: preEncodeAudio() encodes mixed PCM to a temp MP4 *before* the main
 *    muxer is started; returns the audio MediaFormat so both video + audio
 *    tracks are added before muxer.start().
 *  - NEW: copyAudioToMuxer() copies frames from the temp audio file into the
 *    main muxer immediately after muxer.start() — slider value now works end-to-end.
 *  - pipeline() no longer calls the old mixAudio(); uses preEncodeAudio() +
 *    copyAudioToMuxer() instead.
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

    // GL — normal rect program
    private int glProgram      = -1;
    private int aPosition, aTexCoord, uMVP, uTexMatrix, uTexture;

    // GL — circle-bubble program (used only for LAYOUT_REACTION_BUBBLE)
    private int glBubbleProgram   = -1;
    private int bPosition, bTexCoord, bMVP, bTexMatrix, bTexture, bCenter, bRadius, bAspect;
    private FloatBuffer quadBuf;

    // Textures + SurfaceTextures
    private int          texCam   = -1, texOrig = -1;
    private SurfaceTexture stCam,  stOrig;

    // Transform matrices updated per-frame via updateTexImage()
    private final float[] matCam  = new float[16];
    private final float[] matOrig = new float[16];
    // ✅ FIX (elongated/"lambi" video): source clip aspect ratios, used to
    // center-crop (cover-fit) into each panel instead of stretching the
    // texture to fill a differently-shaped rect — same idea as ExoPlayer's
    // resize_mode="zoom" used in the live preview.
    private float camAspect  = 9f / 16f;
    private float origAspect = 9f / 16f;

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param cameraPath   absolute path to the CameraX-recorded MP4
     * @param originalUrl  URL or file:// path of the original reel
     * @param outputPath   absolute path for the composited output MP4
     * @param layoutMode   0=side-by-side, 1=top-bottom, 2=PiP, 3=ReactionBubble
     * @param origVolume   0.0–1.0 volume of original reel audio in the mix
     * @param micGain      0.0–2.0 gain multiplier for camera mic audio (1.0 = no change)
     * @param bubbleNdcX   NDC X centre of reaction bubble (-1..1), used only for mode 3
     * @param bubbleNdcY   NDC Y centre of reaction bubble (-1..1), used only for mode 3
     * @return true on success
     */
      // ─────────────────────────────────────────────────────────────────────────
      // Progress listener — FIX v9
      // ─────────────────────────────────────────────────────────────────────────

      /**
       * ✅ FIX (PROGRESS): Callback fired on the compositor thread as frames are encoded.
       * pct = 0–100 inclusive. Caller must post to UI thread if updating Views.
       */
      public interface ProgressListener {
          void onProgress(int pct);
      }

      /** Set once per composite() call; read from pipeline(). */
      private ProgressListener progressListener = null;
      /** Deduplicates callbacks so UI is not flooded on every single frame. */
      private int lastReportedPct = -1;

  
    public boolean composite(String cameraPath, String originalUrl,
                             String outputPath, int layoutMode,
                             float origVolume, float micGain,
                             float bubbleNdcX, float bubbleNdcY) {
        Log.d(TAG, "start layout=" + layoutMode + " cam=" + cameraPath
                + " origVol=" + origVolume + " micGain=" + micGain);

        if (cameraPath == null || !new File(cameraPath).exists()) {
            Log.e(TAG, "camera file missing"); return false;
        }
        if (originalUrl == null || originalUrl.isEmpty()) {
            Log.e(TAG, "original URL empty"); return false;
        }

        try {
            return pipeline(cameraPath, originalUrl, outputPath, layoutMode,
                            origVolume, micGain, bubbleNdcX, bubbleNdcY);
        } catch (Exception e) {
            Log.e(TAG, "composite failed: " + e.getMessage(), e);
            return false;
        } finally {
            releaseGL();
        }
    }
      /**
       * ✅ FIX (PROGRESS): Full overload with optional ProgressListener.
       * @param listener callback receiving 0–100% updates; pass null to disable.
       */
      public boolean composite(String cameraPath, String originalUrl,
                               String outputPath, int layoutMode,
                               float origVolume, float micGain,
                               float bubbleNdcX, float bubbleNdcY,
                               ProgressListener listener) {
          this.progressListener = listener;
          this.lastReportedPct  = -1;
          return composite(cameraPath, originalUrl, outputPath, layoutMode,
                           origVolume, micGain, bubbleNdcX, bubbleNdcY);
      }

      /**
       * ✅ NEW (v6): Full-featured overload.
       * layoutMode 4 = LAYOUT_GREEN_SCREEN (chroma-key camera composited over
       * the full-screen original reel — camAspect cover-fit, spill suppressed).
       *
       * @param beatTimesMs         optional beat timestamps (ms) from BeatSyncAnalyzer.
       *                            When non-null/non-empty, a short punch-zoom +
       *                            white-flash "auto-cut" effect is baked into the
       *                            output within ~150ms after every beat.
       * @param bubbleTrackTimesMs  optional face-tracking timeline (ms, monotonic) —
       *                            paired with bubbleTrackX/Y (NDC -1..1). When
       *                            provided, the reaction bubble (mode 3) follows
       *                            this interpolated path frame-by-frame instead of
       *                            staying at the static bubbleNdcX/bubbleNdcY.
       */
      public boolean composite(String cameraPath, String originalUrl,
                               String outputPath, int layoutMode,
                               float origVolume, float micGain,
                               float bubbleNdcX, float bubbleNdcY,
                               long[] beatTimesMs,
                               long[] bubbleTrackTimesMs, float[] bubbleTrackX, float[] bubbleTrackY,
                               ProgressListener listener) {
          this.progressListener   = listener;
          this.lastReportedPct    = -1;
          this.beatTimesMs        = beatTimesMs;
          this.bubbleTrackTimesMs = bubbleTrackTimesMs;
          this.bubbleTrackX       = bubbleTrackX;
          this.bubbleTrackY       = bubbleTrackY;
          this.beatCursor         = 0;
          this.trackCursor        = 0;

          Log.d(TAG, "start(v6) layout=" + layoutMode
                  + " beats=" + (beatTimesMs != null ? beatTimesMs.length : 0)
                  + " bubbleTrack=" + (bubbleTrackTimesMs != null ? bubbleTrackTimesMs.length : 0));

          if (cameraPath == null || !new File(cameraPath).exists()) {
              Log.e(TAG, "camera file missing"); return false;
          }
          if (originalUrl == null || originalUrl.isEmpty()) {
              Log.e(TAG, "original URL empty"); return false;
          }
          try {
              return pipeline(cameraPath, originalUrl, outputPath, layoutMode,
                              origVolume, micGain, bubbleNdcX, bubbleNdcY);
          } catch (Exception e) {
              Log.e(TAG, "composite failed: " + e.getMessage(), e);
              return false;
          } finally {
              releaseGL();
              this.beatTimesMs = null;
              this.bubbleTrackTimesMs = null;
              this.bubbleTrackX = null;
              this.bubbleTrackY = null;
          }
      }

      // ── v6: beat-cut + face-track-follow state ────────────────────────────
      private long[]  beatTimesMs;
      private long[]  bubbleTrackTimesMs;
      private float[] bubbleTrackX;
      private float[] bubbleTrackY;
      private int     beatCursor = 0;
      private int     trackCursor = 0;

      /** LAYOUT_GREEN_SCREEN — camera is chroma-keyed and composited full-frame
       *  over the original reel. Pass this as layoutMode. */
      public static final int LAYOUT_GREEN_SCREEN = 4;

  

    // ─────────────────────────────────────────────────────────────────────────
    // Pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private boolean pipeline(String camPath, String origUrl,
                              String outPath, int layout,
                              float origVolume, float micGain,
                              float bubbleNdcX, float bubbleNdcY) throws Exception {

        // ── 1. Pre-encode audio FIRST (before muxer is created) ───────────
        // This gives us the audio MediaFormat so we can add BOTH tracks to the
        // muxer before calling muxer.start() — the only legal MediaMuxer order.
        String      audioTempPath = outPath + ".audio.mp4";
        MediaFormat audioOutFmt   = preEncodeAudio(camPath, origUrl, origVolume, micGain, audioTempPath);

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

        // ── 3. EGL + GL (must happen after createInputSurface) ────────────
        setupEGL(encSurface);
        setupGL();
        encoder.start();

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
        camAspect = clipAspect(camFmt);

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
        origAspect = clipAspect(origFmt);

        MediaCodec decOrig = MediaCodec.createDecoderByType(
                origFmt.getString(MediaFormat.KEY_MIME));
        decOrig.configure(origFmt, surfOrig, null, 0);
        decOrig.start();

        // ── 7. Muxer ──────────────────────────────────────────────────────
        MediaMuxer muxer = new MediaMuxer(outPath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        int  muxVid       = -1;
        int  muxAud       = -1;
        boolean muxStarted = false;

        // ── 8. Main loop ──────────────────────────────────────────────────
        boolean camInputEOS  = false;
        boolean origInputEOS = false;
        boolean camOutputEOS = false;
        boolean encEOS       = false;

        boolean camHasFrame  = false;
        boolean origHasFrame = false;
        long    camPtsUs     = 0L;

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long encodedFrames = 0;
          // ✅ FIX (PROGRESS): estimate total frames from camera file duration
          long totalFrames = (long) OUT_FPS * 30; // safe 30s fallback
          try {
              MediaExtractor probe = new MediaExtractor();
              probe.setDataSource(camPath);
              int pt = pickTrack(probe, "video/");
              if (pt >= 0) {
                  MediaFormat pf = probe.getTrackFormat(pt);
                  if (pf.containsKey(MediaFormat.KEY_DURATION)) {
                      long durUs = pf.getLong(MediaFormat.KEY_DURATION);
                      totalFrames = Math.max(1L, (durUs / 1_000_000L) * OUT_FPS);
                  }
              }
              probe.release();
          } catch (Exception ignored) {}
          if (progressListener != null) progressListener.onProgress(0);
  

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
            }

            // ── Render + encode when both streams have a frame ────────────
            if (camHasFrame && origHasFrame) {
                GLES20.glViewport(0, 0, OUT_W, OUT_H);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                // Pass bubble position for reaction-bubble mode — use the
                // interpolated face-tracking path when available, else static.
                float[] bubblePos = interpolateBubbleTrack(camPtsUs);
                renderBubbleX = bubblePos != null ? bubblePos[0] : bubbleNdcX;
                renderBubbleY = bubblePos != null ? bubblePos[1] : bubbleNdcY;

                // Beat-synced auto-cut: punch-zoom + flash within ~150ms of a beat
                float beatFactor   = computeBeatFactor(camPtsUs);
                beatPunchScale      = 1f + 0.06f * beatFactor;
                beatFlashAlpha      = 0.30f * beatFactor;

                renderFrame(layout);
                if (beatFlashAlpha > 0.01f) drawFlashOverlay(beatFlashAlpha);

                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface,
                        camPtsUs * 1000L);
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);

                camHasFrame = false;
            }

            if (camOutputEOS) {
                encoder.signalEndOfInputStream();
                camOutputEOS = false;
            }

            // ── Drain encoder ─────────────────────────────────────────────
            int encOut = encoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxStarted) {
                    // ✅ Add BOTH tracks before muxer.start() — MediaMuxer rule
                    muxVid = muxer.addTrack(encoder.getOutputFormat());
                    if (audioOutFmt != null) {
                        muxAud = muxer.addTrack(audioOutFmt);
                    }
                    muxer.start();
                    muxStarted = true;

                    // ✅ Copy pre-encoded audio frames into muxer right after start
                    if (muxAud >= 0) {
                        copyAudioToMuxer(audioTempPath, muxAud, muxer);
                    }
                }
            } else if (encOut >= 0) {
                if (muxStarted && muxVid >= 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && info.size > 0) {
                    muxer.writeSampleData(muxVid,
                            encoder.getOutputBuffer(encOut), info);
                    encodedFrames++;
                      // ✅ FIX (PROGRESS): emit percentage without flooding
                      if (progressListener != null) {
                          int pct = (int) Math.min(99L, (encodedFrames * 99L) / totalFrames);
                          if (pct != lastReportedPct) {
                              lastReportedPct = pct;
                              progressListener.onProgress(pct);
                          }
                      }
                }
                encoder.releaseOutputBuffer(encOut, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    encEOS = true;
                }
            }
        }

        // ── 9. Release everything ─────────────────────────────────────────
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
        Log.d(TAG, "done frames=" + encodedFrames + " size=" + new File(outPath).length()
                + " success=" + success);
        if (success && progressListener != null) progressListener.onProgress(100);
        return success;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    // bubbleNdcX/Y stored per-frame for renderFrame to access
    private float renderBubbleX = -0.55f;
    private float renderBubbleY = -0.72f;

    // ── v6: beat-cut punch/flash, applied per-frame in renderFrame/drawRect* ──
    private float beatPunchScale = 1f;
    private float beatFlashAlpha = 0f;

    // Gap between the two panels, matched to the 10dp preview gap
    // (10dp @ ~3x density ≈ 30px on the 1080-wide/1920-tall output canvas).
    private static final float PANEL_GAP_PX    = 30f;
    private static final float PANEL_RADIUS_PX  = 54f; // ≈18dp @3x, matches bg_duet_panel_rounded

    private void renderFrame(int layout) {
        switch (layout) {
            case 1: { // TOP-BOTTOM
                float gapNdc = (PANEL_GAP_PX / 2f) / (OUT_H / 2f);
                drawRectRounded(texCam,  matCam,  -1f, gapNdc,  1f, 1f, PANEL_RADIUS_PX, camAspect);
                drawRectRounded(texOrig, matOrig, -1f, -1f, 1f, -gapNdc, PANEL_RADIUS_PX, origAspect);
                break;
            }
            case 2: // PiP
                drawRect(texCam,  matCam,  -1f, -1f,  1f,  1f);
                drawRect(texOrig, matOrig,  0.45f, -1f, 1f, -0.35f);
                break;
            case 3: // REACTION BUBBLE
                // Original reel fills full screen
                drawRect(texOrig, matOrig, -1f, -1f, 1f, 1f);
                // Camera face in a circular bubble (radius ~0.28 NDC = ~150px on 1080px wide)
                // Face-tracking (when available) already drives renderBubbleX/Y above.
                drawCircleBubble(texCam, matCam, renderBubbleX, renderBubbleY, 0.28f);
                break;
            case LAYOUT_GREEN_SCREEN: // 4 — chroma-key camera over full-screen original
                drawRect(texOrig, matOrig, -1f, -1f, 1f, 1f);
                drawGreenScreenFull(texCam, matCam, camAspect);
                break;
            default: { // SIDE-BY-SIDE
                float gapNdc = (PANEL_GAP_PX / 2f) / (OUT_W / 2f);
                drawRectRounded(texCam,  matCam,  -1f, -1f, -gapNdc, 1f, PANEL_RADIUS_PX, camAspect);
                drawRectRounded(texOrig, matOrig,  gapNdc, -1f,  1f, 1f, PANEL_RADIUS_PX, origAspect);
                break;
            }
        }
    }

    /**
     * Draw texId as a circle centred at (cx,cy) NDC with given NDC radius.
     * Aspect ratio correction applied so the circle isn't squished.
     */
    private void drawCircleBubble(int texId, float[] texMatrix,
                                   float cx, float cy, float radius) {
        if (glBubbleProgram < 0) return;
        GLES20.glUseProgram(glBubbleProgram);

        // Build MVP: translate to (cx,cy), scale by radius
        float[] mvp = new float[16];
        Matrix.setIdentityM(mvp, 0);
        Matrix.translateM(mvp, 0, cx, cy, 0f);
        Matrix.scaleM(mvp, 0, radius, radius, 1f);

        GLES20.glUniformMatrix4fv(bMVP,       1, false, mvp,       0);
        GLES20.glUniformMatrix4fv(bTexMatrix, 1, false, texMatrix, 0);
        // Centre of the quad in NDC space (after MVP, the quad centre = 0,0 in local space)
        GLES20.glUniform2f(bCenter, 0f, 0f);
        GLES20.glUniform1f(bRadius, 1.0f); // 1.0 in local space = radius in NDC
        // Aspect ratio: width/height of output so circle isn't oval
        float aspect = (float) OUT_W / OUT_H;
        GLES20.glUniform1f(bAspect, aspect);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(bTexture, 0);

        quadBuf.position(0);
        GLES20.glEnableVertexAttribArray(bPosition);
        GLES20.glVertexAttribPointer(bPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuf);
        quadBuf.position(2);
        GLES20.glEnableVertexAttribArray(bTexCoord);
        GLES20.glVertexAttribPointer(bTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * Returns width/height for a video track, swapping for 90°/270° rotation
     * (portrait clips recorded in landscape sensor orientation report their
     * raw un-rotated width/height in the format, with "rotation-degrees"
     * telling the renderer how to display them).
     */
    private float clipAspect(MediaFormat fmt) {
        int w = fmt.containsKey(MediaFormat.KEY_WIDTH)  ? fmt.getInteger(MediaFormat.KEY_WIDTH)  : 1080;
        int h = fmt.containsKey(MediaFormat.KEY_HEIGHT) ? fmt.getInteger(MediaFormat.KEY_HEIGHT) : 1920;
        int rotation = fmt.containsKey("rotation-degrees") ? fmt.getInteger("rotation-degrees") : 0;
        if (rotation == 90 || rotation == 270) {
            int tmp = w; w = h; h = tmp;
        }
        if (w <= 0 || h <= 0) return 9f / 16f;
        return (float) w / (float) h;
    }

    private void drawRectRounded(int texId, float[] texMatrix,
                                  float x0, float y0, float x1, float y1,
                                  float cornerRadiusPx, float srcAspect) {
        if (glRoundedProgram < 0) return;
        GLES20.glUseProgram(glRoundedProgram);

        float scaleX = (x1 - x0) * 0.5f * beatPunchScale;
        float scaleY = (y1 - y0) * 0.5f * beatPunchScale;
        float transX = (x0 + x1) * 0.5f;
        float transY = (y0 + y1) * 0.5f;

        float[] mvp = new float[16];
        Matrix.setIdentityM(mvp, 0);
        Matrix.translateM(mvp, 0, transX, transY, 0f);
        Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f);

        GLES20.glUniformMatrix4fv(rMVP,       1, false, mvp,       0);
        GLES20.glUniformMatrix4fv(rTexMatrix, 1, false, texMatrix, 0);

        float halfWidthPx  = scaleX * OUT_W;
        float halfHeightPx = scaleY * OUT_H;
        GLES20.glUniform2f(rHalfSizePx, halfWidthPx, halfHeightPx);
        GLES20.glUniform1f(rRadiusPx, cornerRadiusPx);

        // Center-crop (cover-fit): shrink texcoords on whichever axis the
        // source is "too wide/tall" for, so nothing gets stretched.
        float dstAspect = halfWidthPx / halfHeightPx;
        float cropScaleX = 1f, cropScaleY = 1f;
        if (srcAspect > dstAspect) {
            cropScaleX = dstAspect / srcAspect; // crop the sides
        } else {
            cropScaleY = srcAspect / dstAspect; // crop top/bottom
        }
        GLES20.glUniform2f(rCropScale, cropScaleX, cropScaleY);
        GLES20.glUniform2f(rCropOffset, (1f - cropScaleX) * 0.5f, (1f - cropScaleY) * 0.5f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(rTexture, 0);

        quadBuf.position(0);
        GLES20.glEnableVertexAttribArray(rPosition);
        GLES20.glVertexAttribPointer(rPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuf);
        quadBuf.position(2);
        GLES20.glEnableVertexAttribArray(rTexCoord);
        GLES20.glVertexAttribPointer(rTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void drawRect(int texId, float[] texMatrix,
                           float x0, float y0, float x1, float y1) {
        GLES20.glUseProgram(glProgram);

        float scaleX = (x1 - x0) * 0.5f * beatPunchScale;
        float scaleY = (y1 - y0) * 0.5f * beatPunchScale;
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

    /**
     * ✅ NEW (v6): Draws texId full-frame with a chroma-key (green-screen) alpha
     * cut applied in the fragment shader, cover-fit cropped to srcAspect so the
     * camera clip fills the 1080×1920 canvas without stretching.
     */
    private void drawGreenScreenFull(int texId, float[] texMatrix, float srcAspect) {
        if (glGreenScreenProgram < 0) return;
        GLES20.glUseProgram(glGreenScreenProgram);

        float scaleX = 1f * beatPunchScale;
        float scaleY = 1f * beatPunchScale;

        float[] mvp = new float[16];
        Matrix.setIdentityM(mvp, 0);
        Matrix.scaleM(mvp, 0, scaleX, scaleY, 1f);

        GLES20.glUniformMatrix4fv(gMVP,       1, false, mvp,       0);
        GLES20.glUniformMatrix4fv(gTexMatrix, 1, false, texMatrix, 0);

        float dstAspect = (float) OUT_W / OUT_H;
        float cropScaleX = 1f, cropScaleY = 1f;
        if (srcAspect > dstAspect) {
            cropScaleX = dstAspect / srcAspect;
        } else {
            cropScaleY = srcAspect / dstAspect;
        }
        GLES20.glUniform2f(gCropScale,  cropScaleX, cropScaleY);
        GLES20.glUniform2f(gCropOffset, (1f - cropScaleX) * 0.5f, (1f - cropScaleY) * 0.5f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glUniform1i(gTexture, 0);

        quadBuf.position(0);
        GLES20.glEnableVertexAttribArray(gPosition);
        GLES20.glVertexAttribPointer(gPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuf);
        quadBuf.position(2);
        GLES20.glEnableVertexAttribArray(gTexCoord);
        GLES20.glVertexAttribPointer(gTexCoord, 2, GLES20.GL_FLOAT, false, 16, quadBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * ✅ NEW (v6): Full-screen flat white flash, used as the "cut" pop on beats.
     */
    private void drawFlashOverlay(float alpha) {
        if (glFlashProgram < 0) return;
        GLES20.glUseProgram(glFlashProgram);
        GLES20.glUniform4f(fColor, 1f, 1f, 1f, Math.min(1f, alpha));

        quadBuf.position(0);
        GLES20.glEnableVertexAttribArray(fPosition);
        GLES20.glVertexAttribPointer(fPosition, 2, GLES20.GL_FLOAT, false, 16, quadBuf);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * ✅ NEW (v6): Beat-cut envelope — returns 1.0 right at a beat, decaying
     * linearly to 0.0 over BEAT_CUT_WINDOW_US. Uses a monotonic cursor since
     * camPtsUs only increases across the pipeline loop.
     */
    private static final long BEAT_CUT_WINDOW_US = 150_000L;

    private float computeBeatFactor(long camPtsUs) {
        if (beatTimesMs == null || beatTimesMs.length == 0) return 0f;
        while (beatCursor < beatTimesMs.length - 1
                && beatTimesMs[beatCursor + 1] * 1000L <= camPtsUs) {
            beatCursor++;
        }
        long beatUs = beatTimesMs[beatCursor] * 1000L;
        long elapsed = camPtsUs - beatUs;
        if (elapsed < 0 || elapsed > BEAT_CUT_WINDOW_US) return 0f;
        return 1f - ((float) elapsed / BEAT_CUT_WINDOW_US);
    }

    /**
     * ✅ NEW (v6): Linear interpolation over the face-tracking timeline for the
     * given camera presentation time. Returns null if no track was supplied
     * (falls back to the static bubble position).
     */
    private float[] interpolateBubbleTrack(long camPtsUs) {
        if (bubbleTrackTimesMs == null || bubbleTrackTimesMs.length == 0) return null;
        long tMs = camPtsUs / 1000L;

        if (tMs <= bubbleTrackTimesMs[0]) {
            return new float[]{bubbleTrackX[0], bubbleTrackY[0]};
        }
        int last = bubbleTrackTimesMs.length - 1;
        if (tMs >= bubbleTrackTimesMs[last]) {
            return new float[]{bubbleTrackX[last], bubbleTrackY[last]};
        }
        while (trackCursor < last && bubbleTrackTimesMs[trackCursor + 1] < tMs) {
            trackCursor++;
        }
        int i0 = trackCursor, i1 = Math.min(trackCursor + 1, last);
        long t0 = bubbleTrackTimesMs[i0], t1 = bubbleTrackTimesMs[i1];
        float frac = (t1 > t0) ? (float) (tMs - t0) / (t1 - t0) : 0f;
        float x = bubbleTrackX[i0] + (bubbleTrackX[i1] - bubbleTrackX[i0]) * frac;
        float y = bubbleTrackY[i0] + (bubbleTrackY[i1] - bubbleTrackY[i0]) * frac;
        return new float[]{x, y};
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

    // ── Normal rect shader ────────────────────────────────────────────────────
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

    // ── Circle-bubble shader ──────────────────────────────────────────────────
    // Same vertex shader as above but fragment clips to a circle.
    // uCenter: centre of circle in local quad space (0,0 after MVP = centre of bubble)
    // uRadius: radius in local space (1.0 = bubble edge)
    // uAspect: OUT_W / OUT_H — corrects oval distortion from non-square NDC space
    private static final String BUBBLE_FS =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform samplerExternalOES uTexture;\n" +
        "uniform vec2  uCenter;\n" +
        "uniform float uRadius;\n" +
        "uniform float uAspect;\n" +
        "void main() {\n" +
        // vTex is 0..1; convert to -1..1 local space
        "  vec2 p = (vTex - 0.5) * 2.0;\n" +
        // Correct for non-square viewport (portrait: height > width → squish x)
        "  p.x *= uAspect;\n" +
        "  float dist = length(p - uCenter);\n" +
        // Smooth edge: anti-alias over 2px in NDC
        "  float alpha = 1.0 - smoothstep(uRadius - 0.04, uRadius, dist);\n" +
        "  if (alpha < 0.01) discard;\n" +
        "  gl_FragColor = texture2D(uTexture, vTex) * alpha;\n" +
        "}\n";

    // ── Crop-aware vertex shader (rounded panels only) ────────────────────────
    // Same as VS, but additionally center-crops the texture coords so a
    // source clip of one aspect ratio "covers" a differently-shaped
    // destination rect without stretching — exactly like ExoPlayer's
    // resize_mode="zoom" in the live recording preview.
    private static final String CROP_VS =
        "uniform mat4 uMVPMatrix;\n" +
        "uniform mat4 uTexMatrix;\n" +
        "uniform vec2 uCropScale;\n" +
        "uniform vec2 uCropOffset;\n" +
        "attribute vec4 aPosition;\n" +
        "attribute vec4 aTexCoord;\n" +
        "varying vec2 vTex;\n" +
        "void main() {\n" +
        "  gl_Position = uMVPMatrix * aPosition;\n" +
        "  vec2 raw = (uTexMatrix * aTexCoord).xy;\n" +
        "  vTex = raw * uCropScale + uCropOffset;\n" +
        "}\n";

    // ── Rounded-rect shader ───────────────────────────────────────────────────
    // Same vertex shader; fragment clips to a rounded rectangle using a
    // signed-distance-field test in PIXEL space (uHalfSizePx / uRadiusPx),
    // so the corner radius stays a fixed size regardless of the rect's
    // aspect ratio — used to give the Original / Camera panels the rounded
    // "card" look + transparent-style gap shown in the design mock.
    private static final String ROUNDED_FS =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform samplerExternalOES uTexture;\n" +
        "uniform vec2  uHalfSizePx;\n" +
        "uniform float uRadiusPx;\n" +
        "void main() {\n" +
        "  vec2 p = (vTex - 0.5) * 2.0 * uHalfSizePx;\n" +
        "  vec2 q = abs(p) - uHalfSizePx + uRadiusPx;\n" +
        "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uRadiusPx;\n" +
        "  float alpha = 1.0 - smoothstep(-1.0, 1.0, d);\n" +
        "  if (alpha < 0.01) discard;\n" +
        "  gl_FragColor = texture2D(uTexture, vTex) * alpha;\n" +
        "}\n";

    private int glRoundedProgram = -1;
    private int rPosition, rTexCoord, rMVP, rTexMatrix, rTexture, rHalfSizePx, rRadiusPx;
    private int rCropScale, rCropOffset;

    // ── v6: Green-screen (chroma-key) shader ──────────────────────────────────
    // Keys out pixels close to pure green, with mild spill suppression on the
    // edge so a bright-green ring doesn't linger around the subject.
    private static final String GREEN_SCREEN_FS =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float;\n" +
        "varying vec2 vTex;\n" +
        "uniform samplerExternalOES uTexture;\n" +
        "void main() {\n" +
        "  vec4 c = texture2D(uTexture, vTex);\n" +
        "  float chromaDist = length(c.rgb - vec3(0.0, 1.0, 0.0));\n" +
        "  float alpha = smoothstep(0.25, 0.55, chromaDist);\n" +
        "  vec3 rgb = c.rgb;\n" +
        "  float greenExcess = max(rgb.g - max(rgb.r, rgb.b), 0.0);\n" +
        "  rgb.g -= greenExcess * (1.0 - alpha) * 0.5;\n" +
        "  if (alpha < 0.03) discard;\n" +
        "  gl_FragColor = vec4(rgb, alpha);\n" +
        "}\n";

    private int glGreenScreenProgram = -1;
    private int gPosition, gTexCoord, gMVP, gTexMatrix, gTexture, gCropScale, gCropOffset;

    // ── v6: Flat-color flash overlay shader (beat-cut pop) ────────────────────
    private static final String FLASH_VS =
        "attribute vec4 aPosition;\n" +
        "void main() { gl_Position = aPosition; }\n";

    private static final String FLASH_FS =
        "precision mediump float;\n" +
        "uniform vec4 uColor;\n" +
        "void main() { gl_FragColor = uColor; }\n";

    private int glFlashProgram = -1;
    private int fPosition, fColor;

    private void setupGL() {
        // Normal rect program
        glProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(glProgram, makeShader(GLES20.GL_VERTEX_SHADER,   VS));
        GLES20.glAttachShader(glProgram, makeShader(GLES20.GL_FRAGMENT_SHADER, FS));
        GLES20.glLinkProgram(glProgram);

        aPosition  = GLES20.glGetAttribLocation(glProgram,  "aPosition");
        aTexCoord  = GLES20.glGetAttribLocation(glProgram,  "aTexCoord");
        uMVP       = GLES20.glGetUniformLocation(glProgram, "uMVPMatrix");
        uTexMatrix = GLES20.glGetUniformLocation(glProgram, "uTexMatrix");
        uTexture   = GLES20.glGetUniformLocation(glProgram, "uTexture");

        // Rounded-rect program (crop-aware VS, rounded-rect-clip FS)
        glRoundedProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(glRoundedProgram, makeShader(GLES20.GL_VERTEX_SHADER,   CROP_VS));
        GLES20.glAttachShader(glRoundedProgram, makeShader(GLES20.GL_FRAGMENT_SHADER, ROUNDED_FS));
        GLES20.glLinkProgram(glRoundedProgram);

        rPosition   = GLES20.glGetAttribLocation(glRoundedProgram,  "aPosition");
        rTexCoord   = GLES20.glGetAttribLocation(glRoundedProgram,  "aTexCoord");
        rMVP        = GLES20.glGetUniformLocation(glRoundedProgram, "uMVPMatrix");
        rTexMatrix  = GLES20.glGetUniformLocation(glRoundedProgram, "uTexMatrix");
        rTexture    = GLES20.glGetUniformLocation(glRoundedProgram, "uTexture");
        rHalfSizePx = GLES20.glGetUniformLocation(glRoundedProgram, "uHalfSizePx");
        rRadiusPx   = GLES20.glGetUniformLocation(glRoundedProgram, "uRadiusPx");
        rCropScale  = GLES20.glGetUniformLocation(glRoundedProgram, "uCropScale");
        rCropOffset = GLES20.glGetUniformLocation(glRoundedProgram, "uCropOffset");

        // Circle-bubble program (same VS, circle-clip FS)
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

        // Green-screen program (crop-aware VS, chroma-key clip FS)
        glGreenScreenProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(glGreenScreenProgram, makeShader(GLES20.GL_VERTEX_SHADER,   CROP_VS));
        GLES20.glAttachShader(glGreenScreenProgram, makeShader(GLES20.GL_FRAGMENT_SHADER, GREEN_SCREEN_FS));
        GLES20.glLinkProgram(glGreenScreenProgram);

        gPosition   = GLES20.glGetAttribLocation(glGreenScreenProgram,  "aPosition");
        gTexCoord   = GLES20.glGetAttribLocation(glGreenScreenProgram,  "aTexCoord");
        gMVP        = GLES20.glGetUniformLocation(glGreenScreenProgram, "uMVPMatrix");
        gTexMatrix  = GLES20.glGetUniformLocation(glGreenScreenProgram, "uTexMatrix");
        gTexture    = GLES20.glGetUniformLocation(glGreenScreenProgram, "uTexture");
        gCropScale  = GLES20.glGetUniformLocation(glGreenScreenProgram, "uCropScale");
        gCropOffset = GLES20.glGetUniformLocation(glGreenScreenProgram, "uCropOffset");

        // Flash-overlay program (solid color, beat-cut pop)
        glFlashProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(glFlashProgram, makeShader(GLES20.GL_VERTEX_SHADER,   FLASH_VS));
        GLES20.glAttachShader(glFlashProgram, makeShader(GLES20.GL_FRAGMENT_SHADER, FLASH_FS));
        GLES20.glLinkProgram(glFlashProgram);

        fPosition = GLES20.glGetAttribLocation(glFlashProgram,  "aPosition");
        fColor    = GLES20.glGetUniformLocation(glFlashProgram, "uColor");

        // Enable blending so bubble/green-screen/flash alpha edges work
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

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
    // Audio — pre-encode BEFORE muxer.start()
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Decodes audio from camPath and origUrl, mixes with origVolume weighting,
     * encodes to AAC, and writes to a standalone temp MP4 file.
     *
     * Returns the audio MediaFormat so the caller can call muxer.addTrack()
     * BEFORE muxer.start(). Returns null if no camera audio exists.
     *
     * Key fix: this method does NOT touch the main muxer at all — it creates
     * its own temporary MediaMuxer. The main muxer stays in the "tracks not
     * yet added" state until the caller invokes muxer.addTrack() with this
     * format and then muxer.start().
     */
    private MediaFormat preEncodeAudio(String camPath, String origUrl,
                                        float origVolume, float micGain, String tempPath) {
        try {
            short[] camPcm  = decodeAudioToPcm(camPath);
            short[] origPcm = decodeAudioToPcm(origUrl);

            if (camPcm == null || camPcm.length == 0) {
                Log.w(TAG, "preEncodeAudio: no camera audio, skipping audio track");
                return null;
            }

            // Detect actual sample rate & channel count from camera track
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
                        channels   = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                }
            } finally {
                probe.release();
            }

            // Mix PCM: camera (scaled by micGain) + original (scaled by origVolume)
            int outLen = camPcm.length;
            short[] mixed = new short[outLen];
            for (int i = 0; i < outLen; i++) {
                float cam  = (camPcm[i] / 32768f) * micGain;   // mic gain applied here
                float orig = (origPcm != null && origPcm.length > 0)
                             ? (origPcm[i % origPcm.length] / 32768f) * origVolume : 0f;
                float sum  = cam + orig;
                // Hard clamp to prevent clipping
                sum = Math.max(-1f, Math.min(1f, sum));
                mixed[i] = (short)(sum * 32767f);
            }

            // Configure AAC encoder
            MediaFormat encFmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE,  128_000);
            encFmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            encFmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            // Temp muxer — only audio; completely independent from the main muxer
            MediaMuxer tempMuxer = new MediaMuxer(tempPath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            ByteBuffer pcmBuf = ByteBuffer.allocateDirect(mixed.length * 2)
                    .order(ByteOrder.nativeOrder());
            for (short s : mixed) pcmBuf.putShort(s);
            pcmBuf.flip();

            int     tempAudTrack   = -1;
            boolean trackAdded     = false;
            boolean tempMuxStarted = false;
            boolean inputDone      = false;
            boolean outputDone     = false;
            MediaFormat finalAudioFormat = null;
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
                            byte[] tmp = new byte[remaining];
                            pcmBuf.get(tmp);
                            inBuf.put(tmp);
                            long consumed = pcmBuf.capacity() - pcmBuf.remaining() - remaining;
                            long ptsUs = (consumed / (2L * channels)) * 1_000_000L / sampleRate;
                            enc.queueInputBuffer(idx, 0, remaining, ptsUs, 0);
                        } else {
                            enc.queueInputBuffer(idx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                    }
                }

                // Drain encoder output into temp muxer
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
                        tempMuxer.writeSampleData(tempAudTrack,
                                enc.getOutputBuffer(out), bi);
                    }
                    enc.releaseOutputBuffer(out, false);
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        outputDone = true;
                }
            }

            enc.stop(); enc.release();
            if (tempMuxStarted) tempMuxer.stop();
            tempMuxer.release();

            Log.d(TAG, "preEncodeAudio: done path=" + tempPath
                    + " fmt=" + finalAudioFormat);
            return finalAudioFormat;

        } catch (Exception e) {
            Log.e(TAG, "preEncodeAudio failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * Copies all audio sample frames from audioTempPath into the (already
     * started) main muxer on the given track index.
     * Call this immediately after muxer.start().
     */
    private void copyAudioToMuxer(String audioTempPath, int muxAudTrack, MediaMuxer muxer) {
        MediaExtractor ext = new MediaExtractor();
        try {
            ext.setDataSource(audioTempPath);
            int track = pickTrack(ext, "audio/");
            if (track < 0) {
                Log.w(TAG, "copyAudioToMuxer: no audio track in temp file");
                return;
            }
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
            Log.d(TAG, "copyAudioToMuxer: audio frames copied to main muxer");
        } catch (Exception e) {
            Log.e(TAG, "copyAudioToMuxer failed: " + e.getMessage(), e);
        } finally {
            ext.release();
            new File(audioTempPath).delete();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PCM decode helper
    // ─────────────────────────────────────────────────────────────────────────

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
