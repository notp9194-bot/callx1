package com.callx.app.media;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Crop;
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
 * VideoCropTransformer — re-encodes a video so it is permanently cropped to
 * a rectangular region, using Media3 Transformer's {@link Crop} effect.
 *
 * Lives in :core (same home as {@link VideoOverlayBaker}, which this class
 * mirrors structurally) so every feature module that offers "crop" on a
 * video — currently feature-reels' ReelEditorActivity (Step 1 · Trim and
 * Crop) and feature-chat's MediaEditActivity — re-encodes through this one
 * helper instead of each hand-rolling its own Transformer setup.
 *
 * MUST be called from a thread with a Looper (the main/UI thread) — same
 * Media3 Transformer constraint as VideoOverlayBaker.
 */
@UnstableApi
public final class VideoCropTransformer {

    private static final String TAG = "VideoCropTransformer";

    public interface Callback {
        /** Called periodically on the main thread, percent is 0-100 (may be -1 if unknown). */
        void onProgress(int percent);
        /** Called on the main thread once the cropped file is ready. */
        void onSuccess(@NonNull Uri outputUri);
        /** Called on the main thread if the crop/export fails. */
        void onError(@NonNull Exception e);
    }

    private VideoCropTransformer() {}

    /**
     * @param inputUri     source video (content:// or file:// both work — Media3 handles both)
     * @param cropFraction crop box as fractions (0f–1f) of the video's own displayed
     *                     width/height — e.g. from {@link com.callx.app.media.crop.CropOverlayView#getCropRectNormalized()}
     */
    public static void cropVideo(@NonNull Context context, @NonNull Uri inputUri,
                                  @NonNull android.graphics.RectF cropFraction, @NonNull Callback callback) {
        Handler mainHandler = new Handler(Looper.getMainLooper());

        try {
            File outDir = new File(context.getCacheDir(), "media_crop_video_out");
            if (!outDir.exists()) outDir.mkdirs();
            File output = new File(outDir, "cropped_video_" + System.currentTimeMillis() + ".mp4");

            // Fractions (0..1, origin top-left) → Media3 NDC (-1..1, origin bottom-left,
            // y flipped) expected by the Crop effect.
            float leftNdc   = -1f + 2f * cropFraction.left;
            float rightNdc  = -1f + 2f * cropFraction.right;
            float topNdc    =  1f - 2f * cropFraction.top;
            float bottomNdc =  1f - 2f * cropFraction.bottom;

            // Crop(left, top, right, bottom) — all in NDC, left<right and bottom<top.
            Crop cropEffect = new Crop(leftNdc, topNdc, rightNdc, bottomNdc);

            MediaItem mediaItem = MediaItem.fromUri(inputUri);
            EditedMediaItem editedMediaItem = new EditedMediaItem.Builder(mediaItem)
                    .setEffects(new Effects(ImmutableList.of(), ImmutableList.of(cropEffect)))
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
                        Log.e(TAG, "Video crop export failed", exception);
                        mainHandler.post(() -> callback.onError(exception));
                    }
                })
                .build();

            transformer.start(editedMediaItem, output.getAbsolutePath());

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
                }
            };
            mainHandler.postDelayed(progressPoller, 300);

        } catch (Exception e) {
            Log.e(TAG, "Video crop setup failed", e);
            mainHandler.post(() -> callback.onError(e));
        }
    }
}
