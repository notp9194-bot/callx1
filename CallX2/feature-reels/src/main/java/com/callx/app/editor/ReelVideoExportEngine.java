package com.callx.app.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.effect.RgbAdjustment;
import androidx.media3.effect.RgbFilter;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * ReelVideoExportEngine — burns the live-selected colour filter and any text/sticker
 * overlays directly into the recorded reel's pixels using Media3 Transformer.
 *
 * Output is a brand-new .mp4 file (re-encoded). This is the "hard-bake" step that runs
 * right before upload, so the uploaded file already contains the filter + overlays —
 * no extra rendering is needed by viewers.
 *
 * Uses only androidx.media3:media3-transformer + media3-effect, which are part of the
 * same Media3 family already bundled for ExoPlayer playback (small incremental size).
 */
@UnstableApi
public class ReelVideoExportEngine {

    private static final String TAG = "ReelVideoExport";

    public interface ExportCallback {
        /** Called periodically on the main thread, percent is 0-100 (may be -1 if unknown). */
        void onProgress(int percent);
        /** Called on the main thread once the new file is ready. */
        void onSuccess(String outputPath);
        /** Called on the main thread if export fails — caller should fall back to the original file. */
        void onError(Exception e);
    }

    /** A single text / emoji / sticker overlay, in NORMALIZED screen coordinates (0..1). */
    public static class OverlayItem {
        public final String text;
        public final int    color;
        public final float  x; // 0..1, left edge anchor
        public final float  y; // 0..1, top edge anchor
        public final float  textSizeSp;

        public OverlayItem(String text, int color, float x, float y, float textSizeSp) {
            this.text = text;
            this.color = color;
            this.x = x;
            this.y = y;
            this.textSizeSp = textSizeSp;
        }
    }

    /**
     * Parses the JSON array produced by ReelCameraActivity / ReelEditorActivity, e.g.
     * [{"type":"text","value":"Hello|#FF0000","x":0.5,"y":0.5}, {"type":"emoji","value":"🔥","x":0.3,"y":0.2}]
     */
    public static List<OverlayItem> parseOverlayJsonArray(@Nullable String json) {
        List<OverlayItem> result = new ArrayList<>();
        if (json == null || json.length() < 2) return result;
        String inner = json.trim();
        if (inner.startsWith("[")) inner = inner.substring(1, inner.length() - 1);

        int depth = 0, start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    String obj = inner.substring(start, i + 1);
                    OverlayItem item = parseOverlayObject(obj);
                    if (item != null) result.add(item);
                    start = i + 1;
                    while (start < inner.length() && inner.charAt(start) == ',') start++;
                }
            }
        }
        return result;
    }

    private static OverlayItem parseOverlayObject(String obj) {
        try {
            String value = extractJsonString(obj, "value");
            float x = extractJsonFloat(obj, "x", 0.5f);
            float y = extractJsonFloat(obj, "y", 0.5f);
            if (value == null) return null;

            int color = Color.WHITE;
            if (value.contains("|#")) {
                int sep = value.lastIndexOf("|#");
                String colorHex = value.substring(sep + 1);
                value = value.substring(0, sep);
                try { color = Color.parseColor(colorHex); } catch (Exception ignored) {}
            }
            return new OverlayItem(value, color, x, y, 28f);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJsonString(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int s = json.indexOf(marker);
        if (s < 0) return null;
        s += marker.length();
        int e = json.indexOf("\"", s);
        if (e < 0) return null;
        return json.substring(s, e).replace("\\\"", "\"");
    }

    private static float extractJsonFloat(String json, String key, float fallback) {
        String marker = "\"" + key + "\":";
        int s = json.indexOf(marker);
        if (s < 0) return fallback;
        s += marker.length();
        int e = s;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '.' || json.charAt(e) == '-')) e++;
        try { return Float.parseFloat(json.substring(s, e)); } catch (Exception ex) { return fallback; }
    }

    /**
     * Re-encode {@code inputPath} with the given filter + overlays baked into the pixels.
     * Safe to call even if filterName is empty/"Normal" and overlays is empty — in that
     * case it still re-encodes (so callers can keep the pipeline simple), but you should
     * usually skip calling this entirely when there's nothing to bake.
     */
    public static void export(Context context,
                               String inputPath,
                               @Nullable String filterName,
                               float brightness, float contrast, float saturation,
                               @Nullable List<OverlayItem> overlays,
                               ExportCallback callback) {

        Handler mainHandler = new Handler(Looper.getMainLooper());

        try {
            File input = new File(inputPath);
            File outDir = new File(context.getCacheDir(), "reel_export");
            if (!outDir.exists()) outDir.mkdirs();
            File output = new File(outDir, "reel_export_" + System.currentTimeMillis() + ".mp4");

            List<Effect> videoEffects = new ArrayList<>();
            addFilterEffects(videoEffects, filterName, brightness, contrast, saturation);
            addOverlayEffect(context, videoEffects, input.getAbsolutePath(), overlays);

            MediaItem mediaItem = MediaItem.fromUri(Uri.fromFile(input));
            EditedMediaItem.Builder editedBuilder = new EditedMediaItem.Builder(mediaItem);
            if (!videoEffects.isEmpty()) {
                editedBuilder.setEffects(new Effects(ImmutableList.of(), ImmutableList.copyOf(videoEffects)));
            }
            EditedMediaItem editedMediaItem = editedBuilder.build();

            Transformer transformer = new Transformer.Builder(context)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(@NonNull Composition composition, @NonNull ExportResult exportResult) {
                        mainHandler.post(() -> callback.onSuccess(output.getAbsolutePath()));
                    }

                    @Override
                    public void onError(@NonNull Composition composition, @NonNull ExportResult exportResult,
                                         @NonNull ExportException exception) {
                        Log.e(TAG, "Export failed", exception);
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
            Log.e(TAG, "Export setup failed", e);
            mainHandler.post(() -> callback.onError(e));
        }
    }

    /** Maps the live-preview filter presets (see ReelCameraActivity / ReelEditorActivity) to real pixel effects. */
    private static void addFilterEffects(List<Effect> effects, @Nullable String filterName,
                                          float brightness, float contrast, float saturation) {
        if (filterName == null) filterName = "";

        switch (filterName) {
            case "Mono":
                effects.add(RgbFilter.createGrayscaleEffect());
                break;
            case "Noir":
                effects.add(RgbFilter.createGrayscaleEffect());
                effects.add(new Contrast(0.35f));
                break;
            case "Warm":
            case "Juno":
                effects.add(new RgbAdjustment.Builder().setRedScale(1.15f).setBlueScale(0.9f).build());
                break;
            case "Cool":
            case "Clarendon":
                effects.add(new RgbAdjustment.Builder().setBlueScale(1.18f).setRedScale(0.92f).build());
                break;
            case "Vivid":
                effects.add(new RgbAdjustment.Builder().setRedScale(1.08f).setGreenScale(1.05f).setBlueScale(1.08f).build());
                effects.add(new Contrast(0.15f));
                break;
            case "Fade":
                effects.add(new Contrast(-0.2f));
                effects.add(new RgbAdjustment.Builder().setRedScale(1.05f).setGreenScale(1.05f).setBlueScale(1.05f).build());
                break;
            case "Drama":
                effects.add(new Contrast(0.3f));
                break;
            case "Vintage":
                effects.add(new RgbAdjustment.Builder().setRedScale(1.1f).setBlueScale(0.8f).build());
                effects.add(new Contrast(-0.1f));
                break;
            case "Lark":
                effects.add(new RgbAdjustment.Builder().setBlueScale(1.08f).build());
                effects.add(new Contrast(0.08f));
                break;
            default:
                break; // "Normal" / unknown — no preset colour effect
        }

        // User-adjusted sliders (from ReelFiltersActivity), 1f = no change.
        if (contrast != 1f) {
            float c = Math.max(-1f, Math.min(1f, contrast - 1f));
            if (c != 0f) effects.add(new Contrast(c));
        }
        if (brightness != 0f) {
            // brightness already in -80..80 range from ReelFiltersActivity; approximate via RGB scale.
            float scale = 1f + (brightness / 255f);
            scale = Math.max(0.5f, Math.min(1.5f, scale));
            effects.add(new RgbAdjustment.Builder().setScale(scale).build());
        }
        if (saturation <= 0.15f) {
            // Fully desaturated → grayscale
            effects.add(RgbFilter.createGrayscaleEffect());
        }
    }

    /** Draws all text/sticker overlays onto a single transparent bitmap and overlays it on every frame. */
    private static void addOverlayEffect(Context context, List<Effect> effects,
                                          String inputPath, @Nullable List<OverlayItem> overlays) {
        if (overlays == null || overlays.isEmpty()) return;

        int[] size = readVideoSize(inputPath);
        int width = size[0] > 0 ? size[0] : 720;
        int height = size[1] > 0 ? size[1] : 1280;

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        float density = context.getResources().getDisplayMetrics().density;

        for (OverlayItem item : overlays) {
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setColor(item.color);
            paint.setTextSize(item.textSizeSp * density * ((float) width / 1080f));
            paint.setTypeface(Typeface.DEFAULT_BOLD);

            Paint bg = new Paint();
            bg.setColor(0x55000000);

            float px = item.x * width;
            float py = item.y * height;
            float pad = 8f * density;
            android.graphics.Rect bounds = new android.graphics.Rect();
            paint.getTextBounds(item.text, 0, item.text.length(), bounds);

            canvas.drawRect(px - pad, py - pad, px + bounds.width() + pad, py + bounds.height() + pad, bg);
            canvas.drawText(item.text, px, py - bounds.top, paint);
        }

        BitmapOverlay overlay = new BitmapOverlay() {
            @Override
            public Bitmap getBitmap(long presentationTimeUs) {
                return bitmap;
            }

            @Override
            public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                return new OverlaySettings.Builder().build();
            }
        };

        effects.add(new OverlayEffect(ImmutableList.of(overlay)));
    }

    private static int[] readVideoSize(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            int w = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation = rotationStr != null ? Integer.parseInt(rotationStr) : 0;
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
