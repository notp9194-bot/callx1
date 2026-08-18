package com.callx.app.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import java.io.File;

/**
 * VideoOverlayBaker — burns a single pre-rendered transparent overlay bitmap
 * (stickers / text / freehand drawing — already composited by the caller at
 * the video's own resolution) into every frame of a video, producing a new
 * .mp4 file with the overlay baked directly into the pixels.
 *
 * This is the generic, non-Reels counterpart of
 * {@code com.callx.app.editor.ReelVideoExportEngine}'s overlay step, moved
 * to :core so any feature module can re-encode "preview-only" overlay edits
 * into an actual video file before it's sent/uploaded. First consumer is
 * feature-chat's MediaEditActivity: stickers/text/draw strokes placed on a
 * VIDEO in the chat media editor were only ever shown in the live editor
 * preview (an on-screen overlay layer above a static video thumbnail) and
 * were never baked into the video itself, so they silently disappeared once
 * the original video file was sent. This class fixes that by producing a
 * real re-encoded file that contains them.
 *
 * MUST be called from a thread with a Looper (the main/UI thread) — Media3
 * Transformer requires it, same constraint as ReelVideoExportEngine.
 */
@UnstableApi
public final class VideoOverlayBaker {

    private static final String TAG = "VideoOverlayBaker";

    public interface Callback {
        /** Called periodically on the main thread, percent is 0-100 (may be -1 if unknown). */
        void onProgress(int percent);
        /** Called on the main thread once the new file is ready. */
        void onSuccess(@NonNull Uri outputUri);
        /** Called on the main thread if baking fails — caller should fall back to the original video. */
        void onError(@NonNull Exception e);
    }

    private VideoOverlayBaker() {}

    /**
     * @param inputUri      source video (content:// or file:// both work — Media3 handles both)
     * @param overlayBitmap transparent ARGB_8888 bitmap sized to match the video's own
     *                      (rotation-corrected) display dimensions — see {@link #readDisplaySize}.
     *                      Everything drawn on it is burned into every frame as-is.
     */
    public static void bakeOverlay(@NonNull Context context, @NonNull Uri inputUri,
                                    @NonNull Bitmap overlayBitmap, @NonNull Callback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());

        try {
            File outDir = new File(context.getCacheDir(), "media_edit_video_out");
            if (!outDir.exists()) outDir.mkdirs();
            File output = new File(outDir, "edit_video_" + System.currentTimeMillis() + ".mp4");

            BitmapOverlay overlay = new BitmapOverlay() {
                @Override
                public Bitmap getBitmap(long presentationTimeUs) {
                    return overlayBitmap;
                }

                @Override
                public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                    return new OverlaySettings.Builder().build();
                }
            };
            OverlayEffect overlayEffect = new OverlayEffect(ImmutableList.of(overlay));

            MediaItem mediaItem = MediaItem.fromUri(inputUri);
            EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem)
                    .setEffects(new Effects(ImmutableList.of(), ImmutableList.of(overlayEffect)))
                    .build();

            Transformer transformer = new Transformer.Builder(context)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(@NonNull Composition composition, @NonNull ExportResult exportResult) {
                        mainHandler.post(() -> callback.onSuccess(Uri.fromFile(output)));
                    }

                    @Override
                    public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult,
                                         @NonNull ExportException exception) {
                        Log.e(TAG, "Overlay bake failed", exception);
                        mainHandler.post(() -> callback.onError(exception));
                    }
                })
                .build();

            transformer.start(editedMediaItem, output.getAbsolutePath());

            // Poll progress every 300ms until export finishes or fails.
            ProgressHolder progressHolder = new ProgressHolder();
            Runnable progressPoller = new Runnable() {
                @Override
                public void run() {
                    int state = transformer.getProgress(progressHolder);
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        callback.onProgress(progressHolder.progress);
                        mainHandler.postDelayed(this, 300);
                    } else if (state == Transformer.PROGRESS_STATE_NOT_STARTED) {
                        mainHandler.postDelayed(this, 300);
                    }
                    // PROGRESS_STATE_UNAVAILABLE / no longer running → stop polling,
                    // onCompleted/onError will fire the final callback.
                }
            };
            mainHandler.postDelayed(progressPoller, 300);

        } catch (Exception e) {
            Log.e(TAG, "Overlay bake setup failed", e);
            mainHandler.post(() -> callback.onError(e));
        }
    }

    /**
     * Reads a video's {width, height}, already swapped if rotation metadata is 90/270,
     * so the returned size matches what's actually displayed (and therefore what an
     * overlay bitmap needs to match). Returns {0, 0} on failure — callers should fall
     * back to a reasonable default aspect ratio.
     */
    @NonNull
    public static int[] readDisplaySize(@NonNull Context context, @NonNull Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            int w = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation = 0;
            try {
                if (rotationStr != null) rotation = Integer.parseInt(rotationStr);
            } catch (Exception ignored) {}
            if (rotation == 90 || rotation == 270) {
                int tmp = w; w = h; h = tmp;
            }
            return new int[]{w, h};
        } catch (Exception e) {
            return new int[]{0, 0};
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }
}
