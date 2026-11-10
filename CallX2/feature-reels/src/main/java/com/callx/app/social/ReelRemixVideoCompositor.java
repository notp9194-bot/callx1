package com.callx.app.social;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;

/**
 * ReelRemixVideoCompositor — merges the original reel + the user's recording into one file for Remix / Collab.
 *
 * v791 FIX — "stuck on Compositing… after recording" (both Remix and Collab):
 * the old implementation created a MediaCodec encoder with an input Surface but never rendered a single frame into
 * it (no decoder, no GL) and never signalled end-of-stream, so its encode loop waited forever. The user recording was
 * never even opened. This class now delegates to {@link DuetVideoCompositor} — the same decode → OpenGL → encode
 * pipeline the Duet screen already uses — and keeps the same public API, so ReelRemixActivity did not need to change
 * how it calls it. Run {@link #composite()} on a background thread; it blocks until done and always ends with exactly
 * one of onComplete / onError.
 *
 * Layout mapping (what the compositor can really render):
 *   side_by_side → side by side
 *   react_cam    → original full screen + camera in a round bubble (top-right)
 *   green_screen → camera full screen + original as a small corner picture   (nearest available look)
 *   overlay      → camera full screen + original as a small corner picture   (nearest available look)
 */
public class ReelRemixVideoCompositor {

    private static final String TAG = "RemixCompositor";

    // DuetVideoCompositor layout ids
    private static final int DUET_SIDE_BY_SIDE = 0;
    private static final int DUET_PIP          = 2;
    private static final int DUET_BUBBLE       = 3;

    public interface CompositionListener {
        /** Progress 0–100 (called from the compositor thread — post to the UI thread to touch Views). */
        void onProgress(int percent);
        /** Composition finished; {@code outputFile} is a playable MP4. */
        void onComplete(File outputFile);
        /** Composition failed. */
        void onError(String message, Exception cause);
    }

    private final String              originalPath;
    private final String              userPath;
    private final String              outputPath;
    private final String              layoutMode;
    private final float               originalVolume;
    private final CompositionListener listener;

    public ReelRemixVideoCompositor(Context ctx, String originalPath, String userPath, String outputPath,
                                    String layoutMode, CompositionListener listener) {
        this(ctx, originalPath, userPath, outputPath, layoutMode, 1f, listener);
    }

    /** @param originalVolume 0..1 — volume of the original reel's audio in the mix (0 = user muted the original). */
    public ReelRemixVideoCompositor(Context ctx, String originalPath, String userPath, String outputPath,
                                    String layoutMode, float originalVolume, CompositionListener listener) {
        this.originalPath   = originalPath;
        this.userPath       = userPath;
        this.outputPath     = outputPath;
        this.layoutMode     = layoutMode == null ? ReelRemixActivity.LAYOUT_SIDE_BY_SIDE : layoutMode;
        this.originalVolume = Math.max(0f, Math.min(1f, originalVolume));
        this.listener       = listener;
    }

    /** Blocks until done. Call from a background thread. */
    public void composite() {
        try {
            if (userPath == null || userPath.isEmpty() || !new File(userPath).exists()) {
                fail("Your recording file is missing", null);
                return;
            }
            if (originalPath == null || originalPath.isEmpty()) {
                fail("Original video is not available", null);
                return;
            }
            final int layout;
            float bx = 0.55f, by = 0.68f;   // react_cam bubble: top-right
            switch (layoutMode) {
                case ReelRemixActivity.LAYOUT_REACT_CAM:    layout = DUET_BUBBLE;       break;
                case ReelRemixActivity.LAYOUT_GREEN_SCREEN:
                case ReelRemixActivity.LAYOUT_OVERLAY:      layout = DUET_PIP;          break;
                default:                                    layout = DUET_SIDE_BY_SIDE; break;
            }

            final File out = new File(outputPath);
            boolean ok = new DuetVideoCompositor().composite(
                    userPath, originalPath, outputPath, layout,
                    originalVolume, 1f, bx, by,
                    pct -> { if (listener != null) listener.onProgress(Math.max(0, Math.min(100, pct))); });

            if (ok && out.exists() && out.length() > 0) {
                if (listener != null) { listener.onProgress(100); listener.onComplete(out); }
            } else {
                fail("Could not merge the videos on this device", null);
            }
        } catch (Throwable t) {   // incl. OutOfMemoryError / codec errors — never leave the UI waiting
            Log.e(TAG, "composite crashed", t);
            fail("Composition failed: " + t.getMessage(), t instanceof Exception ? (Exception) t : new Exception(t));
        }
    }

    private void fail(String msg, Exception cause) {
        Log.e(TAG, msg, cause);
        if (listener != null) listener.onError(msg, cause);
    }

    public static long getDurationMs(String path) {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(path);
            String d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d != null ? Long.parseLong(d) : 0L;
        } catch (Exception e) {
            return 0L;
        } finally {
            try { mmr.release(); } catch (Exception ignored) {}
        }
    }
}
