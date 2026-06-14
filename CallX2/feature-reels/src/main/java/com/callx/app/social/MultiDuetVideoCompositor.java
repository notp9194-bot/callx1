package com.callx.app.social;

import android.media.*;
import android.opengl.*;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * MultiDuetVideoCompositor
 *
 * 3-4 person duet grid compositor.
 * Supports 2, 3, or 4 input video files → one output MP4 with grid layout.
 *
 * Layout logic:
 *   2 videos  → side by side (L|R)
 *   3 videos  → top row: 2 equal, bottom row: 1 centred
 *   4 videos  → 2×2 grid
 *
 * Usage:
 *   List<String> paths = Arrays.asList(hostPath, p1Path, p2Path, p3Path);
 *   boolean ok = new MultiDuetVideoCompositor().composite(paths, outputPath, listener);
 *
 * Audio: mixes all input tracks with equal weight using PCM mixing (like DuetVideoCompositor).
 *
 * NOTE: This class is intentionally self-contained (no EGL shared with DuetVideoCompositor)
 * so it can run in its own thread without GL state conflicts.
 */
public class MultiDuetVideoCompositor {

    private static final String TAG         = "MultiDuetCompositor";
    private static final int    OUT_W       = 1080;
    private static final int    OUT_H       = 1920;
    private static final int    OUT_FPS     = 30;
    private static final int    OUT_BITRATE = 6_000_000;
    private static final int    IFRAME_INT  = 2;
    private static final int    TIMEOUT_US  = 10_000;

    public interface ProgressListener {
        void onProgress(int pct); // 0–100
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * @param videoPaths  2–4 local MP4 file paths (host first, then participants in slot order)
     * @param outputPath  destination MP4 path
     * @param listener    optional progress callback (null OK)
     * @return true on success
     */
    public boolean composite(List<String> videoPaths, String outputPath,
                             ProgressListener listener) {
        if (videoPaths == null || videoPaths.size() < 2 || videoPaths.size() > 4) {
            Log.e(TAG, "Need 2–4 video paths, got " +
                    (videoPaths == null ? "null" : videoPaths.size()));
            return false;
        }
        for (String p : videoPaths) {
            if (p == null || !new File(p).exists()) {
                Log.e(TAG, "Missing file: " + p); return false;
            }
        }
        try {
            return pipeline(videoPaths, outputPath, listener);
        } catch (Exception e) {
            Log.e(TAG, "composite failed: " + e.getMessage(), e);
            return false;
        }
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private boolean pipeline(List<String> paths, String outPath,
                             ProgressListener listener) throws Exception {
        int n = paths.size();

        // ── 1. Audio: mix all PCM tracks ─────────────────────────────────────
        String audioTempPath = outPath + ".mix.m4a";
        MediaFormat audioFmt = mixAllAudio(paths, audioTempPath);

        // ── 2. Video encoder ──────────────────────────────────────────────────
        MediaFormat encFmt = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, OUT_W, OUT_H);
        encFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        encFmt.setInteger(MediaFormat.KEY_BIT_RATE,         OUT_BITRATE);
        encFmt.setInteger(MediaFormat.KEY_FRAME_RATE,       OUT_FPS);
        encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INT);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface encSurface = encoder.createInputSurface();

        // ── 3. EGL ─────────────────────────────────────────────────────────────
        EGLDisplay eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(eglDisplay, new int[2], 0, new int[2], 1);
        int[] attrib = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] nConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attrib, 0, configs, 0, 1, nConfigs, 0);
        int[] ctxAttrib = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        EGLContext eglContext = EGL14.eglCreateContext(eglDisplay,
                configs[0], EGL14.EGL_NO_CONTEXT, ctxAttrib, 0);
        int[] surfAttrib = {EGL14.EGL_NONE};
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, configs[0], encSurface, surfAttrib, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        // ── 4. GL program (simple OES blit) ──────────────────────────────────
        String vsSrc =
            "attribute vec4 aPos;\n" +
            "attribute vec2 aUV;\n" +
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uTex;\n" +
            "varying vec2 vUV;\n" +
            "void main(){\n" +
            "  gl_Position = uMVP * aPos;\n" +
            "  vUV = (uTex * vec4(aUV,0,1)).xy;\n" +
            "}\n";
        String fsSrc =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "varying vec2 vUV;\n" +
            "void main(){ gl_FragColor = texture2D(uTex,vUV); }\n";

        int prog = buildGlProgram(vsSrc, fsSrc);
        int aPos = GLES20.glGetAttribLocation(prog, "aPos");
        int aUV  = GLES20.glGetAttribLocation(prog, "aUV");
        int uMVP = GLES20.glGetUniformLocation(prog, "uMVP");
        int uTex = GLES20.glGetUniformLocation(prog, "uTex");

        GLES20.glViewport(0, 0, OUT_W, OUT_H);
        GLES20.glClearColor(0f, 0f, 0f, 1f);

        // ── 5. OES textures + SurfaceTextures per video ──────────────────────
        int[]          texIds = new int[n];
        SurfaceTexture[] sts  = new SurfaceTexture[n];
        Surface[]      surfs  = new Surface[n];

        for (int i = 0; i < n; i++) {
            texIds[i] = createOESTex();
            sts[i]    = new SurfaceTexture(texIds[i]);
            sts[i].setDefaultBufferSize(OUT_W / 2, OUT_H / 2);
            surfs[i]  = new Surface(sts[i]);
        }

        // ── 6. Extractors + decoders ──────────────────────────────────────────
        MediaExtractor[] exts = new MediaExtractor[n];
        MediaCodec[]     decs = new MediaCodec[n];
        for (int i = 0; i < n; i++) {
            exts[i] = new MediaExtractor();
            exts[i].setDataSource(paths.get(i));
            int vidTrack = pickVideoTrack(exts[i]);
            if (vidTrack < 0) throw new IOException("No video in " + paths.get(i));
            exts[i].selectTrack(vidTrack);
            MediaFormat fmt = exts[i].getTrackFormat(vidTrack);
            decs[i] = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            decs[i].configure(fmt, surfs[i], null, 0);
            decs[i].start();
        }

        // ── 7. Muxer ──────────────────────────────────────────────────────────
        MediaMuxer muxer    = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int muxVid          = -1;
        int muxAud          = -1;
        boolean muxStarted  = false;

        // Add audio track to muxer up front so we can start() after video format is ready
        if (audioFmt != null) {
            muxAud = muxer.addTrack(audioFmt);
        }

        encoder.start();

        // ── 8. Main encode loop ───────────────────────────────────────────────
        boolean[] inEOS  = new boolean[n];
        boolean[] outEOS = new boolean[n];
        boolean encEOS   = false;
        long encodedFrames = 0;
        long totalFrames   = OUT_FPS * 60L; // safe 60s cap
        float[] mvpId = identityMVP();

        MediaCodec.BufferInfo encInfo = new MediaCodec.BufferInfo();
        MediaCodec.BufferInfo[] decInfo = new MediaCodec.BufferInfo[n];
        for (int i = 0; i < n; i++) decInfo[i] = new MediaCodec.BufferInfo();

        float[][] texMat = new float[n][16];
        for (int i = 0; i < n; i++) android.opengl.Matrix.setIdentityM(texMat[i], 0);
        boolean[] newFrame = new boolean[n];

        while (!encEOS) {
            // Feed decoders
            for (int i = 0; i < n; i++) {
                if (inEOS[i]) continue;
                int ibIdx = decs[i].dequeueInputBuffer(TIMEOUT_US);
                if (ibIdx >= 0) {
                    java.nio.ByteBuffer buf = decs[i].getInputBuffer(ibIdx);
                    int sz = exts[i].readSampleData(buf, 0);
                    if (sz < 0) {
                        decs[i].queueInputBuffer(ibIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inEOS[i] = true;
                    } else {
                        long pts = exts[i].getSampleTime();
                        decs[i].queueInputBuffer(ibIdx, 0, sz, pts, 0);
                        exts[i].advance();
                    }
                }
            }

            // Drain decoders → SurfaceTexture
            for (int i = 0; i < n; i++) {
                if (outEOS[i]) continue;
                int obIdx = decs[i].dequeueOutputBuffer(decInfo[i], TIMEOUT_US);
                if (obIdx >= 0) {
                    boolean eos = (decInfo[i].flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    decs[i].releaseOutputBuffer(obIdx, true); // render to surface
                    sts[i].updateTexImage();
                    sts[i].getTransformMatrix(texMat[i]);
                    newFrame[i] = true;
                    if (eos) outEOS[i] = true;
                } else if (obIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // ignore
                }
            }

            // Render grid if any new frame arrived
            boolean anyNew = false;
            for (boolean nf : newFrame) anyNew |= nf;
            if (anyNew) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glUseProgram(prog);
                drawGrid(n, texIds, texMat, aPos, aUV, uMVP, uTex, mvpId);
                EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                for (int i = 0; i < n; i++) newFrame[i] = false;
            }

            // Drain encoder
            int eIdx = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US);
            if (eIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxStarted) {
                    muxVid = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                    // Write audio track
                    if (muxAud >= 0 && new File(audioTempPath).exists()) {
                        copyAudioTrack(audioTempPath, muxer, muxAud);
                    }
                }
            } else if (eIdx >= 0) {
                if (!muxStarted) {
                    muxVid = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxStarted = true;
                    if (muxAud >= 0 && new File(audioTempPath).exists()) {
                        copyAudioTrack(audioTempPath, muxer, muxAud);
                    }
                }
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        && encInfo.size > 0 && muxStarted) {
                    java.nio.ByteBuffer eb = encoder.getOutputBuffer(eIdx);
                    if (eb != null) muxer.writeSampleData(muxVid, eb, encInfo);
                    encodedFrames++;
                    if (listener != null) {
                        int pct = (int) Math.min(99, encodedFrames * 99L / totalFrames);
                        listener.onProgress(pct);
                    }
                }
                if ((encInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encEOS = true;
                encoder.releaseOutputBuffer(eIdx, false);
            }

            // Signal encoder EOS once all decoders are done
            boolean allDecDone = true;
            for (boolean eos : outEOS) allDecDone &= eos;
            if (allDecDone && !encEOS) {
                encoder.signalEndOfInputStream();
            }
        }

        // ── 9. Cleanup ────────────────────────────────────────────────────────
        if (muxStarted) muxer.stop();
        muxer.release();
        for (int i = 0; i < n; i++) {
            decs[i].stop(); decs[i].release();
            exts[i].release();
            surfs[i].release();
        }
        encoder.stop(); encoder.release();
        EGL14.eglDestroySurface(eglDisplay, eglSurface);
        EGL14.eglDestroyContext(eglDisplay, eglContext);
        EGL14.eglTerminate(eglDisplay);

        new File(audioTempPath).delete();
        if (listener != null) listener.onProgress(100);

        boolean ok = encodedFrames > 0 && new File(outPath).length() > 1024;
        Log.d(TAG, "done: " + ok + " frames=" + encodedFrames + " size=" + new File(outPath).length());
        return ok;
    }

    // ── Grid drawing ──────────────────────────────────────────────────────────

    /**
     * Draws n videos in a grid:
     *   n=2 → [L][R]
     *   n=3 → [TL][TR] / [B centred]
     *   n=4 → [TL][TR] / [BL][BR]
     */
    private void drawGrid(int n, int[] texIds, float[][] texMats,
                          int aPos, int aUV, int uMVP, int uTex, float[] identity) {
        switch (n) {
            case 2:
                // Left half | Right half
                drawCell(texIds[0], texMats[0], -1f, 0f, 0f, 1f,  aPos, aUV, uMVP, uTex, identity);
                drawCell(texIds[1], texMats[1],  0f, 0f, 1f, 1f,  aPos, aUV, uMVP, uTex, identity);
                break;
            case 3:
                // Top-left | Top-right
                drawCell(texIds[0], texMats[0], -1f, 0f, 0f, 1f,  aPos, aUV, uMVP, uTex, identity);
                drawCell(texIds[1], texMats[1],  0f, 0f, 1f, 1f,  aPos, aUV, uMVP, uTex, identity);
                // Bottom centre (half-width, centred)
                drawCell(texIds[2], texMats[2], -0.5f, -1f, 0.5f, 0f, aPos, aUV, uMVP, uTex, identity);
                break;
            case 4:
            default:
                // 2×2 grid
                drawCell(texIds[0], texMats[0], -1f, 0f, 0f, 1f,  aPos, aUV, uMVP, uTex, identity); // TL
                drawCell(texIds[1], texMats[1],  0f, 0f, 1f, 1f,  aPos, aUV, uMVP, uTex, identity); // TR
                drawCell(texIds[2], texMats[2], -1f,-1f, 0f, 0f,  aPos, aUV, uMVP, uTex, identity); // BL
                drawCell(texIds[3], texMats[3],  0f,-1f, 1f, 0f,  aPos, aUV, uMVP, uTex, identity); // BR
                break;
        }
    }

    /** Draw one video cell into NDC rect [x0,y0]→[x1,y1] */
    private void drawCell(int texId, float[] texMat,
                          float x0, float y0, float x1, float y1,
                          int aPos, int aUV, int uMVP, int uTex, float[] identity) {
        float[] verts = {
            x0, y0,   x0, y1,   x1, y0,   x1, y1
        };
        float[] uvs = {
            0f, 0f,   0f, 1f,   1f, 0f,   1f, 1f
        };

        java.nio.FloatBuffer vBuf = java.nio.ByteBuffer.allocateDirect(verts.length * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        vBuf.put(verts).position(0);
        java.nio.FloatBuffer uBuf = java.nio.ByteBuffer.allocateDirect(uvs.length * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        uBuf.put(uvs).position(0);

        GLES20.glUniformMatrix4fv(uMVP, 1, false, identity, 0);
        GLES20.glUniformMatrix4fv(uTex, 1, false, texMat,   0);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, vBuf);
        GLES20.glEnableVertexAttribArray(aUV);
        GLES20.glVertexAttribPointer(aUV,  2, GLES20.GL_FLOAT, false, 0, uBuf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // ── Audio mixing ──────────────────────────────────────────────────────────

    /**
     * Decodes audio from all input files, mixes PCM with equal weight,
     * encodes to AAC and writes to tempPath.
     * @return MediaFormat of the encoded audio, or null if all inputs lack audio.
     */
    private MediaFormat mixAllAudio(List<String> paths, String tempPath) {
        int n = paths.size();
        // Decode each to 44100 Hz mono short[]
        short[][] pcmTracks = new short[n][];
        int maxLen = 0;
        for (int i = 0; i < n; i++) {
            pcmTracks[i] = decodePCM(paths.get(i));
            if (pcmTracks[i] != null && pcmTracks[i].length > maxLen) {
                maxLen = pcmTracks[i].length;
            }
        }
        if (maxLen == 0) return null;

        // Mix: sum and normalise
        short[] mixed = new short[maxLen];
        float weight = 1.0f / n;
        for (int s = 0; s < maxLen; s++) {
            float sum = 0f;
            for (int i = 0; i < n; i++) {
                if (pcmTracks[i] != null && s < pcmTracks[i].length) {
                    sum += pcmTracks[i][s] * weight;
                }
            }
            mixed[s] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) sum));
        }

        return encodePCMtoAAC(mixed, 44100, tempPath);
    }

    private short[] decodePCM(String path) {
        try {
            MediaExtractor ext = new MediaExtractor();
            ext.setDataSource(path);
            int audioTrack = -1;
            for (int i = 0; i < ext.getTrackCount(); i++) {
                if (ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                        .startsWith("audio/")) { audioTrack = i; break; }
            }
            if (audioTrack < 0) { ext.release(); return null; }
            ext.selectTrack(audioTrack);
            MediaFormat fmt = ext.getTrackFormat(audioTrack);

            MediaCodec dec = MediaCodec.createDecoderByType(
                    fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();

            java.util.ArrayList<Short> samples = new java.util.ArrayList<>(128 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;

            while (true) {
                if (!inputDone) {
                    int ibIdx = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (ibIdx >= 0) {
                        java.nio.ByteBuffer ib = dec.getInputBuffer(ibIdx);
                        int sz = ext.readSampleData(ib, 0);
                        if (sz < 0) {
                            dec.queueInputBuffer(ibIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            dec.queueInputBuffer(ibIdx, 0, sz, ext.getSampleTime(), 0);
                            ext.advance();
                        }
                    }
                }
                int obIdx = dec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (obIdx >= 0) {
                    java.nio.ByteBuffer ob = dec.getOutputBuffer(obIdx);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        java.nio.ShortBuffer sb = ob.order(
                                java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                        while (sb.hasRemaining()) samples.add(sb.get());
                    }
                    dec.releaseOutputBuffer(obIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (obIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // ignore
                }
            }
            dec.stop(); dec.release(); ext.release();
            short[] arr = new short[samples.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = samples.get(i);
            return arr;
        } catch (Exception e) {
            Log.w(TAG, "decodePCM err: " + e.getMessage()); return null;
        }
    }

    private MediaFormat encodePCMtoAAC(short[] pcm, int sampleRate, String outPath) {
        try {
            MediaFormat fmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE,     128_000);
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();

            MediaMuxer muxer = new MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxTrack = -1;
            boolean muxStarted = false;

            int pcmPos = 0;
            int samplesPerFrame = 1024;
            boolean inputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat outFmt = null;

            while (true) {
                if (!inputDone) {
                    int ibIdx = enc.dequeueInputBuffer(TIMEOUT_US);
                    if (ibIdx >= 0) {
                        java.nio.ByteBuffer ib = enc.getInputBuffer(ibIdx);
                        ib.clear();
                        int remaining = pcm.length - pcmPos;
                        if (remaining <= 0) {
                            enc.queueInputBuffer(ibIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            int count = Math.min(samplesPerFrame, remaining);
                            java.nio.ShortBuffer sb = ib.asShortBuffer();
                            sb.put(pcm, pcmPos, count);
                            long pts = (long) pcmPos * 1_000_000L / sampleRate;
                            enc.queueInputBuffer(ibIdx, 0, count * 2, pts, 0);
                            pcmPos += count;
                        }
                    }
                }
                int obIdx = enc.dequeueOutputBuffer(info, TIMEOUT_US);
                if (obIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = enc.getOutputFormat();
                    muxTrack = muxer.addTrack(outFmt);
                    muxer.start(); muxStarted = true;
                } else if (obIdx >= 0) {
                    if (!muxStarted) {
                        outFmt = enc.getOutputFormat();
                        muxTrack = muxer.addTrack(outFmt);
                        muxer.start(); muxStarted = true;
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && info.size > 0) {
                        muxer.writeSampleData(muxTrack, enc.getOutputBuffer(obIdx), info);
                    }
                    enc.releaseOutputBuffer(obIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            if (muxStarted) muxer.stop();
            muxer.release(); enc.stop(); enc.release();
            return outFmt;
        } catch (Exception e) {
            Log.e(TAG, "encodePCM err: " + e.getMessage()); return null;
        }
    }

    private void copyAudioTrack(String audioPath, MediaMuxer muxer, int muxTrack) {
        try {
            MediaExtractor ext = new MediaExtractor();
            ext.setDataSource(audioPath);
            for (int i = 0; i < ext.getTrackCount(); i++) {
                MediaFormat f = ext.getTrackFormat(i);
                if (f.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                    ext.selectTrack(i); break;
                }
            }
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(256 * 1024);
            while (true) {
                int sz = ext.readSampleData(buf, 0);
                if (sz < 0) break;
                info.offset = 0; info.size = sz;
                info.presentationTimeUs = ext.getSampleTime();
                info.flags = ext.getSampleFlags();
                muxer.writeSampleData(muxTrack, buf, info);
                ext.advance();
            }
            ext.release();
        } catch (Exception e) {
            Log.w(TAG, "copyAudioTrack err: " + e.getMessage());
        }
    }

    // ── GL helpers ────────────────────────────────────────────────────────────

    private int createOESTex() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        return t[0];
    }

    private int buildGlProgram(String vs, String fs) {
        int v = compileShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private int compileShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private float[] identityMVP() {
        float[] m = new float[16];
        android.opengl.Matrix.setIdentityM(m, 0);
        return m;
    }

    private int pickVideoTrack(MediaExtractor ext) {
        for (int i = 0; i < ext.getTrackCount(); i++) {
            if (ext.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("video/"))
                return i;
        }
        return -1;
    }
}
