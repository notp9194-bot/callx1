package com.callx.app.social;

import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.ReturnCode;
import com.arthenica.ffmpegkit.FFmpegSession;

/**
 * DuetVideoCompositor v2 — FFmpeg-based compositing.
 *
 * Replaces the broken MediaCodec frame-by-frame compositor which caused
 * infinite hangs (lockHardwareCanvas on bg thread, HTTP MediaExtractor).
 *
 * Supported layout modes:
 *   0 = SIDE_BY_SIDE  — [original | camera] left/right  (1080×1920)
 *   1 = TOP_BOTTOM    — [original] top / [camera] bottom (1080×1920)
 *   2 = REACT_PIP     — camera fills frame, original 30% pip bottom-left
 *
 * Audio: camera mic track kept at 1.0, original reel at originalVolume (0..1).
 *
 * Thread: SYNCHRONOUS — must be called from a background thread.
 * Returns true on success, false on failure (caller uses camera file alone).
 */
public class DuetVideoCompositor {

    private static final String TAG = "DuetVideoCompositor";

    // Output resolution
    private static final int W = 1080;
    private static final int H = 1920;
    private static final int HALF_W = W / 2;
    private static final int HALF_H = H / 2;

    /**
     * @param cameraPath     Absolute path to CameraX recording (.mp4)
     * @param originalUrl    URL or local path of original reel
     * @param outputPath     Absolute path for composited output (.mp4)
     * @param layoutMode     0=side-by-side, 1=top-bottom, 2=pip
     * @param originalVolume 0.0..1.0 — mix level for original reel audio
     * @return true on success
     */
    public boolean composite(String cameraPath, String originalUrl,
                             String outputPath, int layoutMode, float originalVolume) {
        try {
            String cmd = buildCommand(cameraPath, originalUrl, outputPath,
                                      layoutMode, originalVolume);
            Log.d(TAG, "FFmpeg cmd: " + cmd);
            FFmpegSession session = FFmpegKit.execute(cmd);
            if (ReturnCode.isSuccess(session.getReturnCode())) {
                Log.i(TAG, "composite() success → " + outputPath);
                return true;
            } else {
                Log.e(TAG, "FFmpeg failed rc=" + session.getReturnCode()
                      + " logs=" + session.getAllLogsAsString());
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "composite() exception: " + e.getMessage(), e);
            return false;
        }
    }

    private String buildCommand(String cam, String orig, String out,
                                int mode, float origVol) {
        // Escape paths for shell (spaces etc.)
        String camQ  = "\"" + cam  + "\"";
        String origQ = "\"" + orig + "\"";
        String outQ  = "\"" + out  + "\"";

        // Camera volume always 1.0 (mic audio)
        String camVol  = "1.0";
        String origVolStr = String.format("%.2f", origVol);

        switch (mode) {

            case DuetReelActivity.LAYOUT_TOP_BOTTOM: {
                // Stack vertically: original on top, camera on bottom
                // Each scaled to W × H/2
                String vf = "[0:v]scale=" + W + ":" + HALF_H + ",setsar=1[top];"
                          + "[1:v]scale=" + W + ":" + HALF_H + ",setsar=1[bot];"
                          + "[top][bot]vstack,scale=" + W + ":" + H + "[vout]";
                String af = "[0:a]volume=" + origVolStr + "[a0];"
                          + "[1:a]volume=" + camVol  + "[a1];"
                          + "[a0][a1]amix=inputs=2:duration=shortest[aout]";
                return "-y -i " + origQ + " -i " + camQ
                     + " -filter_complex \"" + vf + ";" + af + "\""
                     + " -map \"[vout]\" -map \"[aout]\""
                     + " -c:v libx264 -preset veryfast -crf 23"
                     + " -c:a aac -b:a 128k -shortest"
                     + " " + outQ;
            }

            case DuetReelActivity.LAYOUT_REACT_PIP: {
                // Camera full frame, original as 30% PiP bottom-left corner
                int pipW   = (int)(W * 0.30f);
                int pipH   = (int)(H * 0.30f);
                int margin = 24;
                int pipX   = margin;
                int pipY   = H - pipH - margin;

                String vf = "[1:v]scale=" + W + ":" + H + ",setsar=1[bg];"
                          + "[0:v]scale=" + pipW + ":" + pipH + ",setsar=1[pip];"
                          + "[bg][pip]overlay=" + pipX + ":" + pipY + "[vout]";
                String af = "[0:a]volume=" + origVolStr + "[a0];"
                          + "[1:a]volume=" + camVol  + "[a1];"
                          + "[a0][a1]amix=inputs=2:duration=shortest[aout]";
                return "-y -i " + origQ + " -i " + camQ
                     + " -filter_complex \"" + vf + ";" + af + "\""
                     + " -map \"[vout]\" -map \"[aout]\""
                     + " -c:v libx264 -preset veryfast -crf 23"
                     + " -c:a aac -b:a 128k -shortest"
                     + " " + outQ;
            }

            case DuetReelActivity.LAYOUT_SIDE_BY_SIDE:
            default: {
                // Left = original, Right = camera — each W/2 × H
                String vf = "[0:v]scale=" + HALF_W + ":" + H + ",setsar=1[left];"
                          + "[1:v]scale=" + HALF_W + ":" + H + ",setsar=1[right];"
                          + "[left][right]hstack,scale=" + W + ":" + H + "[vout]";
                String af = "[0:a]volume=" + origVolStr + "[a0];"
                          + "[1:a]volume=" + camVol  + "[a1];"
                          + "[a0][a1]amix=inputs=2:duration=shortest[aout]";
                return "-y -i " + origQ + " -i " + camQ
                     + " -filter_complex \"" + vf + ";" + af + "\""
                     + " -map \"[vout]\" -map \"[aout]\""
                     + " -c:v libx264 -preset veryfast -crf 23"
                     + " -c:a aac -b:a 128k -shortest"
                     + " " + outQ;
            }
        }
    }
}
