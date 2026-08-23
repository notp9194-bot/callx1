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
import androidx.media3.effect.RgbMatrix;
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

    // Luminosity-based grayscale colour matrix (column-major 4x4, as used by GL).
    private static final float[] GRAYSCALE_MATRIX = {
        0.213f, 0.213f, 0.213f, 0f,
        0.715f, 0.715f, 0.715f, 0f,
        0.072f, 0.072f, 0.072f, 0f,
        0f,     0f,     0f,     1f
    };

    private static RgbMatrix grayscaleEffect() {
        return (presentationTimeUs, useHdr) -> GRAYSCALE_MATRIX;
    }

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
        // ── Advanced text-overlay styling (Step 2 wizard) ──────────────────
        public final String  fontKey;   // classic|serif|mono|condensed
        public final boolean bold;
        public final boolean italic;
        public final String  bgStyle;   // none|pill|solid|highlight
        public final String  align;     // left|center|right
        public final float   rotationDeg;
        public final float   scale;

        public OverlayItem(String text, int color, float x, float y, float textSizeSp) {
            this(text, color, x, y, textSizeSp, "classic", false, false, "pill", "center", 0f, 1f);
        }

        public OverlayItem(String text, int color, float x, float y, float textSizeSp,
                            String fontKey, boolean bold, boolean italic, String bgStyle,
                            String align, float rotationDeg, float scale) {
            this.text = text;
            this.color = color;
            this.x = x;
            this.y = y;
            this.textSizeSp = textSizeSp;
            this.fontKey = fontKey != null ? fontKey : "classic";
            this.bold = bold;
            this.italic = italic;
            this.bgStyle = bgStyle != null ? bgStyle : "pill";
            this.align = align != null ? align : "center";
            this.rotationDeg = rotationDeg;
            this.scale = scale <= 0f ? 1f : scale;
        }
    }

    /**
     * Parses the JSON array produced by ReelCameraActivity / ReelEditorActivity, e.g.
     * [{"type":"text","value":"Hello|#FF0000","x":0.5,"y":0.5}, {"type":"emoji","value":"🔥","x":0.3,"y":0.2}]
     * or the richer Step-2 "advanced text overlay" schema:
     * [{"type":"text","value":"Hello","x":0.5,"y":0.5,"color":"#FF0000","font":"serif",
     *   "bold":true,"italic":false,"bg":"solid","align":"center","size":30,"rotation":12,"scale":1.4}]
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
            // Legacy inline format: "text|#RRGGBB"
            if (value.contains("|#")) {
                int sep = value.lastIndexOf("|#");
                String colorHex = value.substring(sep + 1);
                value = value.substring(0, sep);
                try { color = Color.parseColor(colorHex); } catch (Exception ignored) {}
            }
            // Advanced schema: explicit "color" key wins over the legacy inline one.
            String colorStr = extractJsonString(obj, "color");
            if (colorStr != null) {
                try { color = Color.parseColor(colorStr); } catch (Exception ignored) {}
            }

            float size = extractJsonFloat(obj, "size", 28f);
            String font = extractJsonString(obj, "font");
            String bg = extractJsonString(obj, "bg");
            String align = extractJsonString(obj, "align");
            boolean bold = obj.contains("\"bold\":true");
            boolean italic = obj.contains("\"italic\":true");
            float rotation = extractJsonFloat(obj, "rotation", 0f);
            float scale = extractJsonFloat(obj, "scale", 1f);

            return new OverlayItem(value, color, x, y, size, font, bold, italic, bg, align, rotation, scale);
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
        export(context, inputPath, filterName, brightness, contrast, saturation,
            overlays, 0L, 0L, callback);
    }

    /**
     * Overload that also bakes a trim range into the export so the uploaded file
     * always matches the range the user picked on the trim filmstrip. Pass
     * {@code trimEndMs <= trimStartMs} (e.g. 0, 0) to skip clipping entirely.
     */
    public static void export(Context context,
                               String inputPath,
                               @Nullable String filterName,
                               float brightness, float contrast, float saturation,
                               @Nullable List<OverlayItem> overlays,
                               long trimStartMs, long trimEndMs,
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

            MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(Uri.fromFile(input));
            // ✅ Bake the selected trim range into the exported file so the preview
            // loop range and the actually-uploaded video always match.
            if (trimEndMs > trimStartMs) {
                itemBuilder.setClippingConfiguration(
                    new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(trimStartMs)
                        .setEndPositionMs(trimEndMs)
                        .build());
            }
            MediaItem mediaItem = itemBuilder.build();
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
                effects.add(grayscaleEffect());
                break;
            case "Noir":
                effects.add(grayscaleEffect());
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
            effects.add(new RgbAdjustment.Builder().setRedScale(scale).setGreenScale(scale).setBlueScale(scale).build());
        }
        if (saturation <= 0.15f) {
            // Fully desaturated → grayscale
            effects.add(grayscaleEffect());
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
            drawStyledOverlay(canvas, item, width, height, density);
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

    /**
     * Draws one advanced text overlay — font family, bold/italic, background
     * style (none/pill/solid/highlight), multi-line alignment, rotation and
     * scale — matching what the Step 2 editor preview (draggable/pinchable
     * TextView) showed, so the exported/baked video looks identical to what
     * the user styled on screen instead of the old fixed white-bold-pill text.
     */
    private static void drawStyledOverlay(Canvas canvas, OverlayItem item, int videoWidth, int videoHeight, float density) {
        float sizePx = item.textSizeSp * density * ((float) videoWidth / 1080f) * item.scale;

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(sizePx);
        paint.setTypeface(resolveTypeface(item.fontKey, item.bold, item.italic));

        boolean highlight = "highlight".equals(item.bgStyle);
        int textColor = item.color;
        if (highlight) {
            // Light backgrounds get dark text and vice versa, same contrast rule as the editor preview.
            double luminance = (0.299 * Color.red(item.color) + 0.587 * Color.green(item.color) + 0.114 * Color.blue(item.color));
            textColor = luminance > 150 ? Color.BLACK : Color.WHITE;
        }
        paint.setColor(textColor);
        if (!"none".equals(item.bgStyle) && !highlight) {
            paint.setShadowLayer(4f * density, 0f, 2f * density, 0x99000000);
        }

        Paint.Align paintAlign = "left".equals(item.align) ? Paint.Align.LEFT
            : "right".equals(item.align) ? Paint.Align.RIGHT : Paint.Align.CENTER;
        paint.setTextAlign(paintAlign);

        String[] lines = item.text.split("\\n", -1);
        float lineHeight = sizePx * 1.25f;
        float pad = 8f * density * item.scale;

        // Widest line drives the background box width.
        float maxLineWidth = 0f;
        for (String line : lines) maxLineWidth = Math.max(maxLineWidth, paint.measureText(line));
        float blockHeight = lineHeight * lines.length;

        canvas.save();
        canvas.translate(item.x * videoWidth, item.y * videoHeight);
        canvas.rotate(item.rotationDeg);

        if (!"none".equals(item.bgStyle)) {
            Paint bg = new Paint();
            bg.setAntiAlias(true);
            if (highlight) {
                bg.setColor(item.color);
            } else if ("solid".equals(item.bgStyle)) {
                bg.setColor(0xEE000000);
            } else { // pill
                bg.setColor(0x66000000);
            }
            android.graphics.RectF rect = new android.graphics.RectF(
                -maxLineWidth / 2f - pad, -blockHeight / 2f - pad,
                maxLineWidth / 2f + pad, blockHeight / 2f + pad);
            float radius = "solid".equals(item.bgStyle) || highlight ? 6f * density : (blockHeight / 2f + pad);
            canvas.drawRoundRect(rect, radius, radius, bg);
        }

        float baselineY = -blockHeight / 2f + lineHeight * 0.8f;
        for (String line : lines) {
            float lineX;
            if (paintAlign == Paint.Align.LEFT) lineX = -maxLineWidth / 2f;
            else if (paintAlign == Paint.Align.RIGHT) lineX = maxLineWidth / 2f;
            else lineX = 0f;
            canvas.drawText(line, lineX, baselineY, paint);
            baselineY += lineHeight;
        }
        canvas.restore();
    }

    private static Typeface resolveTypeface(String fontKey, boolean bold, boolean italic) {
        Typeface base;
        if ("serif".equals(fontKey)) base = Typeface.SERIF;
        else if ("mono".equals(fontKey)) base = Typeface.MONOSPACE;
        else if ("condensed".equals(fontKey)) base = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
        else base = Typeface.SANS_SERIF;

        int style = Typeface.NORMAL;
        if (bold && italic) style = Typeface.BOLD_ITALIC;
        else if (bold) style = Typeface.BOLD;
        else if (italic) style = Typeface.ITALIC;
        return Typeface.create(base, style);
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
