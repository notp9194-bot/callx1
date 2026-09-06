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
        // ✅ NEW: text-in animation baked into the exported pixels (see drawStyledOverlay).
        public final String  animKey;       // none|typewriter|word
        public final long    animDurationMs; // derived from text length — 0 when animKey is "none"
        // ✅ NEW: gradient/multi-colour fill + outline/stroke — mirrors the editor's
        // StyledOverlayTextView so the exported pixels match the live preview.
        public final boolean gradientEnabled;
        public final int     gradientStart;
        public final int     gradientEnd;
        public final boolean outlineEnabled;
        public final int     outlineColor;

        public OverlayItem(String text, int color, float x, float y, float textSizeSp) {
            this(text, color, x, y, textSizeSp, "classic", false, false, "pill", "center", 0f, 1f, "none",
                false, Color.WHITE, Color.WHITE, false, Color.BLACK);
        }

        public OverlayItem(String text, int color, float x, float y, float textSizeSp,
                            String fontKey, boolean bold, boolean italic, String bgStyle,
                            String align, float rotationDeg, float scale) {
            this(text, color, x, y, textSizeSp, fontKey, bold, italic, bgStyle, align, rotationDeg, scale, "none",
                false, Color.WHITE, Color.WHITE, false, Color.BLACK);
        }

        public OverlayItem(String text, int color, float x, float y, float textSizeSp,
                            String fontKey, boolean bold, boolean italic, String bgStyle,
                            String align, float rotationDeg, float scale, String animKey) {
            this(text, color, x, y, textSizeSp, fontKey, bold, italic, bgStyle, align, rotationDeg, scale, animKey,
                false, Color.WHITE, Color.WHITE, false, Color.BLACK);
        }

        public OverlayItem(String text, int color, float x, float y, float textSizeSp,
                            String fontKey, boolean bold, boolean italic, String bgStyle,
                            String align, float rotationDeg, float scale, String animKey,
                            boolean gradientEnabled, int gradientStart, int gradientEnd,
                            boolean outlineEnabled, int outlineColor) {
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
            this.animKey = animKey != null ? animKey : "none";
            this.animDurationMs = computeAnimDurationMs(text, this.animKey);
            this.gradientEnabled = gradientEnabled;
            this.gradientStart = gradientStart;
            this.gradientEnd = gradientEnd;
            this.outlineEnabled = outlineEnabled;
            this.outlineColor = outlineColor;
        }
    }

    /** Same reveal-speed formula the editor's live preview uses (playTextAnimationPreview
     *  in ReelEditorActivity), so the baked export matches what the user saw while editing. */
    private static long computeAnimDurationMs(@Nullable String text, String animKey) {
        if (text == null || text.isEmpty() || "none".equals(animKey)) return 0L;
        if ("word".equals(animKey)) {
            int words = text.trim().isEmpty() ? 1 : text.trim().split("\\s+").length;
            return Math.max(500L, Math.min(2500L, words * 220L));
        }
        int chars = text.length();
        return Math.max(600L, Math.min(3000L, chars * 45L));
    }

    /**
     * Parses the JSON array produced by ReelCameraActivity / ReelEditorActivity, e.g.
     * [{"type":"text","value":"Hello|#FF0000","x":0.5,"y":0.5}, {"type":"emoji","value":"🔥","x":0.3,"y":0.2}]
     * or the richer Step-2 "advanced text overlay" schema:
     * [{"type":"text","value":"Hello","x":0.5,"y":0.5,"color":"#FF0000","font":"serif",
     *   "bold":true,"italic":false,"bg":"solid","align":"center","size":30,"rotation":12,
     *   "scale":1.4,"anim":"typewriter"}]
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

    /**
     * Same as {@link #parseOverlayJsonArray} but keeps only "type":"text" entries —
     * used by ReelShareController's download/share-out path. A reel's sticker_json
     * can hold a mix of text (never baked into the stored video's pixels — see
     * ReelPlayerFragment/ReelTextOverlayRenderer for why) and non-text stickers
     * (which, if they exist, already got baked in at upload time — see
     * ReelEditorActivity#stripTextOverlayEntries). Re-baking the whole array here
     * would draw those non-text ones a second time; only the text needs baking
     * for a file leaving the app (WhatsApp, gallery, etc. can't render the live
     * in-app overlay layer).
     */
    public static List<OverlayItem> parseTextOnlyOverlays(@Nullable String json) {
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
                    if (obj.contains("\"type\":\"text\"")) {
                        OverlayItem item = parseOverlayObject(obj);
                        if (item != null) result.add(item);
                    }
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
            String anim = extractJsonString(obj, "anim");

            // ✅ NEW: gradient fill + outline — absent in older/legacy sticker JSON,
            // so all of these default to "off" when the keys aren't present.
            boolean gradientOn = obj.contains("\"gradientOn\":true");
            int gradientStart = Color.WHITE, gradientEnd = Color.WHITE;
            String gStartStr = extractJsonString(obj, "gradientStart");
            String gEndStr = extractJsonString(obj, "gradientEnd");
            if (gStartStr != null) { try { gradientStart = Color.parseColor(gStartStr); } catch (Exception ignored) {} }
            if (gEndStr != null) { try { gradientEnd = Color.parseColor(gEndStr); } catch (Exception ignored) {} }
            boolean outlineOn = obj.contains("\"outlineOn\":true");
            int outlineColor = Color.BLACK;
            String outlineColorStr = extractJsonString(obj, "outlineColor");
            if (outlineColorStr != null) { try { outlineColor = Color.parseColor(outlineColorStr); } catch (Exception ignored) {} }

            return new OverlayItem(value, color, x, y, size, font, bold, italic, bg, align, rotation, scale, anim,
                gradientOn, gradientStart, gradientEnd, outlineOn, outlineColor);
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
            overlays, null, 0L, 0L, callback);
    }

    /**
     * Overload that also bakes a creator watermark (see {@link WatermarkSpec}) into the
     * export. Used by ReelShareController's download/share-out path so the file that
     * leaves the app (Gallery, WhatsApp, etc.) always carries the reel owner's watermark
     * — the live in-app overlay (ReelPlayerFragment) can't be seen once the video is
     * outside CallX, same reasoning as parseTextOnlyOverlays' doc above.
     */
    public static void export(Context context,
                               String inputPath,
                               @Nullable String filterName,
                               float brightness, float contrast, float saturation,
                               @Nullable List<OverlayItem> overlays,
                               @Nullable WatermarkSpec watermark,
                               ExportCallback callback) {
        export(context, inputPath, filterName, brightness, contrast, saturation,
            overlays, watermark, 0L, 0L, callback);
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
        export(context, inputPath, filterName, brightness, contrast, saturation,
            overlays, null, trimStartMs, trimEndMs, callback);
    }

    /**
     * ✅ NEW: which UI surface a watermark is being baked for. Feed/Reels and
     * Stories can render the SAME brand (same type/text/logo/color — identity
     * doesn't change per surface) at a DIFFERENT size/position — Instagram
     * does this too (its Stories re-share stamp is smaller than the feed
     * watermark). Only affects which position/opacity/fontSize triple
     * {@link #resolveWatermarkSpec(Context, String, String, Boolean, boolean, WatermarkSurface)}
     * reads: FEED reads the top-level fields (unchanged, backward compatible);
     * STORY reads users/{uid}/watermarkSettings/story/* IF that sub-object's
     * "customized" flag is true, else falls back to the same top-level fields
     * as FEED — see ReelWatermarkSettingsActivity's "Customize for Stories"
     * switch, which is what writes/clears that sub-object.
     */
    public enum WatermarkSurface { FEED, STORY }

    /**
     * A resolved (already-fetched) creator watermark, ready to bake into an export.
     * Callers resolve this from users/{ownerUid}/watermarkSettings — see
     * ReelShareController#fetchWatermarkSpec — *before* calling export(), since
     * Transformer setup here is synchronous and logo images need a network fetch.
     */
    public static class WatermarkSpec {
        @Nullable public final String text;       // null when logo (bitmap) is used instead
        @Nullable public final Bitmap logoBitmap;  // null when text is used instead
        public final int    color;
        public final float  opacity;    // 0f..1f
        public final float  textSizeSp;
        public final String position;   // "Top Left"|"Top Right"|"Bottom Left"|"Bottom Right"|"Center"

        public WatermarkSpec(@Nullable String text, @Nullable Bitmap logoBitmap, int color,
                              float opacity, float textSizeSp, @Nullable String position) {
            this.text = text;
            this.logoBitmap = logoBitmap;
            this.color = color;
            this.opacity = Math.max(0f, Math.min(1f, opacity));
            this.textSizeSp = textSizeSp;
            this.position = position != null ? position : "Bottom Right";
        }
    }

    /**
     * Synchronously resolves {@code ownerUid}'s watermark (users/{ownerUid}/watermarkSettings,
     * saved by ReelWatermarkSettingsActivity) into a ready-to-bake WatermarkSpec.
     *
     * MUST be called off the main thread — it blocks on a one-shot Firebase read and, for a
     * logo watermark, a synchronous Glide image fetch, both of which need to finish before
     * {@link #export} can be started (Transformer setup itself is synchronous). Used by both
     * ReelShareController (bakes the OWNER's watermark into a download/share-out file) and
     * ReelUploadActivity (bakes the poster's OWN watermark into the master uploaded file, so
     * every viewer — in-app or downloaded later — always sees it, not just this one export).
     *
     * Returns null (no watermark baked) if disabled, unset, or anything fails, so a slow or
     * broken watermark never blocks the upload/download itself.
     */
    @Nullable
    public static WatermarkSpec resolveWatermarkSpec(Context context, @Nullable String ownerUid,
                                                       @Nullable String ownerName) {
        return resolveWatermarkSpec(context, ownerUid, ownerName, null, false);
    }

    /**
     * Same as {@link #resolveWatermarkSpec(Context, String, String)}, but also takes a
     * per-reel override (ReelModel#watermarkEnabled — the "Show Watermark on This Reel"
     * switch in ReelPostDetailsActivity, Instagram-style per-share control):
     *
     *  • {@code Boolean.FALSE} — creator turned the watermark off for THIS reel; skip the
     *    Firebase read entirely and never bake one, even if their global toggle is on.
     *  • {@code Boolean.TRUE}  — creator turned it on for THIS reel; bake one even if their
     *    global toggle is off, or they never configured watermarkSettings at all (falls back
     *    to a plain "@name" text watermark, bottom-right, in that case).
     *  • {@code null}          — no per-reel override; use the global toggle exactly like the
     *    2-arg overload always has (old reels, and uploads that never passed through Post
     *    Details, keep this behavior).
     */
    @Nullable
    public static WatermarkSpec resolveWatermarkSpec(Context context, @Nullable String ownerUid,
                                                       @Nullable String ownerName,
                                                       @Nullable Boolean perReelOverride) {
        return resolveWatermarkSpec(context, ownerUid, ownerName, perReelOverride, false);
    }

    /**
     * Same as the 4-arg overload, plus {@code creditGiven} —
     * {@code com.callx.app.models.ReelModel#repostCreditGiven()} for the reel being exported. True skips
     * the watermark for a repost that already credits the original creator in its
     * own caption (Instagram sometimes waives its own repost watermark the same
     * way, rather than applying it unconditionally on every repost). A {@code TRUE}
     * {@code perReelOverride} still wins over this — an explicit per-reel "show it
     * anyway" choice is a stronger signal than an inferred caption credit.
     */
    @Nullable
    public static WatermarkSpec resolveWatermarkSpec(Context context, @Nullable String ownerUid,
                                                       @Nullable String ownerName,
                                                       @Nullable Boolean perReelOverride,
                                                       boolean creditGiven) {
        return resolveWatermarkSpec(context, ownerUid, ownerName, perReelOverride, creditGiven, WatermarkSurface.FEED);
    }

    /**
     * Same as the 5-arg overload, plus {@code surface} — see {@link WatermarkSurface}'s
     * class doc. Pass {@link WatermarkSurface#STORY} when baking a clip that's headed
     * to a Story/Status surface (e.g. ReelShareToStoryActivity) so a creator's
     * "Customize for Stories" size/position, if they set one, is honored instead of
     * always reusing the Feed/Reels one.
     */
    @Nullable
    public static WatermarkSpec resolveWatermarkSpec(Context context, @Nullable String ownerUid,
                                                       @Nullable String ownerName,
                                                       @Nullable Boolean perReelOverride,
                                                       boolean creditGiven,
                                                       WatermarkSurface surface) {
        if (ownerUid == null || ownerUid.isEmpty()) return null;
        if (Boolean.FALSE.equals(perReelOverride)) return null;
        if (creditGiven && !Boolean.TRUE.equals(perReelOverride)) return null;
        try {
            final com.google.firebase.database.DataSnapshot[] result = new com.google.firebase.database.DataSnapshot[1];
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            com.callx.app.utils.FirebaseUtils.getUserRef(ownerUid).child("watermarkSettings")
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot snap) {
                        result[0] = snap;
                        latch.countDown();
                    }
                    @Override public void onCancelled(@NonNull com.google.firebase.database.DatabaseError e) {
                        latch.countDown();
                    }
                });
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            com.google.firebase.database.DataSnapshot snap = result[0];
            boolean hasSettings = (snap != null && snap.exists());
            // No global settings ever saved: only proceed if THIS reel force-enables it
            // (falls back to defaults below); otherwise nothing to bake, same as before.
            if (!hasSettings && !Boolean.TRUE.equals(perReelOverride)) return null;

            Boolean globalEnabled = hasSettings ? snap.child("enabled").getValue(Boolean.class) : null;
            boolean effectiveEnabled = Boolean.TRUE.equals(perReelOverride)
                || (globalEnabled != null && globalEnabled);
            if (!effectiveEnabled) return null;

            String type = hasSettings ? snap.child("type").getValue(String.class) : null;
            String position = hasSettings ? snap.child("position").getValue(String.class) : null;
            Long opacityL = hasSettings ? snap.child("opacity").getValue(Long.class) : null;
            Long fontSizeL = hasSettings ? snap.child("fontSize").getValue(Long.class) : null;
            String colorStr = hasSettings ? snap.child("color").getValue(String.class) : null;
            float opacity = (opacityL != null ? opacityL : 80L) / 100f;
            float fontSize = fontSizeL != null ? fontSizeL : 16L;
            int color;
            try { color = Color.parseColor(colorStr != null ? colorStr : "#FFFFFF"); }
            catch (Exception e) { color = Color.WHITE; }

            // ✅ NEW: Stories-specific size/position override — identity (type/
            // text/logo/color) always stays the Feed/Reels one; only WHERE and
            // HOW BIG changes, matching how the settings screen edits it.
            if (surface == WatermarkSurface.STORY && hasSettings) {
                com.google.firebase.database.DataSnapshot storySnap = snap.child("story");
                Boolean customized = storySnap.child("customized").getValue(Boolean.class);
                if (Boolean.TRUE.equals(customized)) {
                    String sPos = storySnap.child("position").getValue(String.class);
                    Long sOpacityL = storySnap.child("opacity").getValue(Long.class);
                    Long sFontSizeL = storySnap.child("fontSize").getValue(Long.class);
                    if (sPos != null) position = sPos;
                    if (sOpacityL != null) opacity = sOpacityL / 100f;
                    if (sFontSizeL != null) fontSize = sFontSizeL;
                }
            }

            if ("logo".equals(type)) {
                String logoUrl = snap.child("logoUrl").getValue(String.class);
                if (logoUrl == null || logoUrl.isEmpty()) return null;
                try {
                    Bitmap logo = com.bumptech.glide.Glide.with(context)
                        .asBitmap().load(logoUrl).submit(256, 256)
                        .get(8, java.util.concurrent.TimeUnit.SECONDS);
                    return new WatermarkSpec(null, logo, color, opacity, fontSize, position);
                } catch (Exception e) {
                    return null; // logo fetch failed — skip the watermark rather than fail the caller
                }
            }

            String text;
            if ("custom_text".equals(type)) {
                text = snap.child("customText").getValue(String.class);
                if (text == null || text.isEmpty()) text = "@" + (ownerName != null ? ownerName : "");
            } else {
                text = "@" + (ownerName != null ? ownerName : "");
            }
            return new WatermarkSpec(text, null, color, opacity, fontSize, position);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Core export — re-encodes {@code inputPath} with the given filter, overlays,
     * watermark and trim range all baked into the pixels.
     */
    public static void export(Context context,
                               String inputPath,
                               @Nullable String filterName,
                               float brightness, float contrast, float saturation,
                               @Nullable List<OverlayItem> overlays,
                               @Nullable WatermarkSpec watermark,
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
            addOverlayEffect(context, videoEffects, input.getAbsolutePath(), overlays, watermark);

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

    /**
     * How often the baked-in watermark jumps to a new spot. Mirrors the Instagram/TikTok
     * repost-watermark behavior: instead of sitting in one fixed pixel for the whole clip,
     * it periodically shifts corner (and nudges a few dp within that corner) so a single
     * crop can't reliably cut it out of every frame.
     */
    private static final long WATERMARK_JITTER_CYCLE_US = 3_500_000L; // ~3.5s per slot

    /** Corners the watermark cycles through. Center is excluded from the rotation — jitter
     *  only matters for corner/edge crops, so a "Center" setting is just used as a normal
     *  fixed anchor (nothing to crop around in the middle of the frame anyway). */
    private static final String[] WATERMARK_JITTER_CORNERS = {
        "Bottom Right", "Top Left", "Top Right", "Bottom Left"
    };

    /** Extra per-lap pixel nudge (dp) on top of the corner anchor, so the watermark doesn't
     *  land in the exact same spot every time it revisits a given corner either. */
    private static final float[][] WATERMARK_JITTER_OFFSETS_DP = {
        {0f, 0f}, {10f, -6f}, {-8f, 8f}, {6f, 10f}
    };

    private static final int WATERMARK_JITTER_TOTAL_SLOTS =
        WATERMARK_JITTER_CORNERS.length * WATERMARK_JITTER_OFFSETS_DP.length;

    /**
     * Reference phone size used only to convert fragment_reel_player.xml's dp-based UI chrome
     * (caption box, action-button rail, top badges) into fractions of the video frame. The
     * export has no idea what device/screen the reel will actually play back on, so this is
     * an approximation — but it's enough to keep the baked watermark from landing under that
     * chrome most of the time, the same way Instagram auto-adjusts its own watermark spot.
     */
    private static final float UI_REF_WIDTH_DP  = 412f;
    private static final float UI_REF_HEIGHT_DP = 915f;

    /**
     * UI safe zones to steer the watermark away from, each as {x, y, w, h} in dp against the
     * reference size above — sourced from fragment_reel_player.xml:
     *  • top-left    — layout_suggested_label (owner row + follow button) + stub_quality_badge
     *  • top-right   — top_controls (mute button)
     *  • bottom-left — bottom_info (owner row, caption @ marginBottom 136dp, song/BPM badges
     *                  up to marginBottom 228dp) — width stops at the same 64dp the caption's
     *                  own marginEnd already reserves for the action rail
     *  • bottom-right— right_actions (like/comment/share/save/repost/more/audio rail, 68dp wide)
     */
    private static float[][] uiSafeZonesDp() {
        return new float[][]{
            {0f, 0f, 200f, 62f},
            {UI_REF_WIDTH_DP - 54f, 0f, 54f, 54f},
            {0f, UI_REF_HEIGHT_DP - 270f, UI_REF_WIDTH_DP - 64f, 270f},
            {UI_REF_WIDTH_DP - 76f, UI_REF_HEIGHT_DP - 450f, 76f, 450f},
        };
    }

    /** {@link #uiSafeZonesDp()} scaled from reference dp into this export's actual pixel size. */
    private static List<float[]> scaledUiSafeZones(int videoW, int videoH) {
        float sx = videoW / UI_REF_WIDTH_DP;
        float sy = videoH / UI_REF_HEIGHT_DP;
        List<float[]> zones = new ArrayList<>();
        for (float[] z : uiSafeZonesDp()) {
            zones.add(new float[]{z[0] * sx, z[1] * sy, z[2] * sx, z[3] * sy});
        }
        return zones;
    }

    /**
     * If the candidate watermark box overlaps a UI safe zone, nudges it just clear of that
     * zone — toward whichever edge needs the smallest shift — instead of letting it sit under
     * chrome the caption/buttons would cover it with. Mirrors Instagram's own auto-adjust.
     */
    private static float[] avoidUiSafeZones(float x, float y, int boxW, int boxH,
                                             int videoW, int videoH, int margin) {
        for (float[] zone : scaledUiSafeZones(videoW, videoH)) {
            float zx = zone[0], zy = zone[1], zw = zone[2], zh = zone[3];
            boolean overlaps = x < zx + zw && x + boxW > zx && y < zy + zh && y + boxH > zy;
            if (!overlaps) continue;

            float pushLeft  = zx - boxW - x;   // shift so box ends up fully left of the zone
            float pushRight = zx + zw - x;      // ...fully right of it
            float pushUp    = zy - boxH - y;    // ...fully above it
            float pushDown  = zy + zh - y;       // ...fully below it

            float best = pushRight;
            boolean horizontal = true;
            if (Math.abs(pushLeft) < Math.abs(best)) { best = pushLeft; horizontal = true; }
            if (Math.abs(pushUp) < Math.abs(best))   { best = pushUp;   horizontal = false; }
            if (Math.abs(pushDown) < Math.abs(best)) { best = pushDown; horizontal = false; }

            if (horizontal) x += best; else y += best;
            x = clampToFrame(x, margin, videoW - boxW - margin);
            y = clampToFrame(y, margin, videoH - boxH - margin);
        }
        return new float[]{x, y};
    }

    /**
     * Draws all text/sticker overlays onto a transparent bitmap and overlays it on every frame.
     *
     * ✅ NEW: when at least one overlay has a text-in animation (typewriter / word reveal), the
     * bitmap can no longer be built once and reused for the whole video — the revealed text
     * changes with presentationTimeUs. To keep the common case (no animated overlays) exactly
     * as cheap as before, this only switches to per-frame rendering when something actually
     * animates.
     *
     * ✅ NEW: when a watermark is baked in, its position is no longer frozen for the whole
     * export either — see {@link #jitteredWatermarkAnchor}. Rather than re-rendering a full
     * frame on every single video frame (expensive), a small fixed set of position "slots" is
     * pre-rendered once up front and the export just picks the slot for the current timestamp —
     * same crop-resistance as a true per-frame jitter, without the per-frame cost.
     */
    private static void addOverlayEffect(Context context, List<Effect> effects,
                                          String inputPath, @Nullable List<OverlayItem> overlays,
                                          @Nullable WatermarkSpec watermark) {
        if ((overlays == null || overlays.isEmpty()) && watermark == null) return;
        List<OverlayItem> safeOverlays = overlays != null ? overlays : new ArrayList<>();

        int[] size = readVideoSize(inputPath);
        int width = size[0] > 0 ? size[0] : 720;
        int height = size[1] > 0 ? size[1] : 1280;
        float density = context.getResources().getDisplayMetrics().density;

        long maxAnimUs = 0L;
        for (OverlayItem item : safeOverlays) {
            if (!"none".equals(item.animKey)) {
                maxAnimUs = Math.max(maxAnimUs, item.animDurationMs * 1000L);
            }
        }
        final long settleAtUs = maxAnimUs;
        final boolean watermarkMoves = watermark != null && !"Center".equals(watermark.position);

        if (settleAtUs == 0L) {
            if (!watermarkMoves) {
                // Fast path — nothing animates and nothing jitters, so render exactly once
                // (unchanged behavior/perf for overlay-only or Center-watermark exports).
                final Bitmap bitmap = renderOverlayFrame(safeOverlays, watermark, width, height, density, 0L);
                BitmapOverlay overlay = new BitmapOverlay() {
                    @Override public Bitmap getBitmap(long presentationTimeUs) { return bitmap; }
                    @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                        return new OverlaySettings.Builder().build();
                    }
                };
                effects.add(new OverlayEffect(ImmutableList.of(overlay)));
                return;
            }
            // Overlays are static but the watermark jitters — pre-render one bitmap per
            // jitter slot (cheap, one-time cost) and pick between them by timestamp.
            final Bitmap[] slots = new Bitmap[WATERMARK_JITTER_TOTAL_SLOTS];
            BitmapOverlay overlay = new BitmapOverlay() {
                @Override public Bitmap getBitmap(long presentationTimeUs) {
                    int slot = jitterSlotIndex(presentationTimeUs);
                    if (slots[slot] == null) {
                        slots[slot] = renderOverlayFrame(safeOverlays, watermark, width, height, density,
                            slotTimeUs(slot));
                    }
                    return slots[slot];
                }
                @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                    return new OverlaySettings.Builder().build();
                }
            };
            effects.add(new OverlayEffect(ImmutableList.of(overlay)));
            return;
        }

        // Slow path — re-render every frame while an overlay animation is still revealing.
        // Once settled: if the watermark jitters, cache one bitmap per jitter slot (overlay
        // text fully revealed + watermark at that slot's position); otherwise cache a single
        // frame like before.
        final Bitmap[] settledSlots = watermarkMoves ? new Bitmap[WATERMARK_JITTER_TOTAL_SLOTS] : null;
        final Bitmap[] settledFrame = new Bitmap[1];
        BitmapOverlay overlay = new BitmapOverlay() {
            @Override public Bitmap getBitmap(long presentationTimeUs) {
                if (presentationTimeUs >= settleAtUs) {
                    if (watermarkMoves) {
                        int slot = jitterSlotIndex(presentationTimeUs);
                        if (settledSlots[slot] == null) {
                            settledSlots[slot] = renderOverlayFrameAt(safeOverlays, watermark, width, height,
                                density, /*overlayTimeUs=*/settleAtUs, /*watermarkTimeUs=*/slotTimeUs(slot));
                        }
                        return settledSlots[slot];
                    }
                    if (settledFrame[0] == null) {
                        settledFrame[0] = renderOverlayFrame(safeOverlays, watermark, width, height, density, presentationTimeUs);
                    }
                    return settledFrame[0];
                }
                return renderOverlayFrame(safeOverlays, watermark, width, height, density, presentationTimeUs);
            }
            @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
                return new OverlaySettings.Builder().build();
            }
        };
        effects.add(new OverlayEffect(ImmutableList.of(overlay)));
    }

    /** Which pre-rendered jitter slot is "current" for this timestamp. */
    private static int jitterSlotIndex(long presentationTimeUs) {
        long lap = presentationTimeUs / WATERMARK_JITTER_CYCLE_US;
        return (int) (lap % WATERMARK_JITTER_TOTAL_SLOTS);
    }

    /** A representative timestamp that maps back to {@code slot} via {@link #jitterSlotIndex}. */
    private static long slotTimeUs(int slot) {
        return (long) slot * WATERMARK_JITTER_CYCLE_US;
    }

    private static Bitmap renderOverlayFrame(List<OverlayItem> overlays, @Nullable WatermarkSpec watermark,
                                              int width, int height, float density, long presentationTimeUs) {
        return renderOverlayFrameAt(overlays, watermark, width, height, density, presentationTimeUs, presentationTimeUs);
    }

    /**
     * Same as {@link #renderOverlayFrame}, but lets overlay text-reveal timing and the
     * watermark's jitter timing be driven by two different clocks — needed once overlay text
     * has settled but the watermark should keep moving for the rest of the reel.
     */
    private static Bitmap renderOverlayFrameAt(List<OverlayItem> overlays, @Nullable WatermarkSpec watermark,
                                                int width, int height, float density,
                                                long overlayTimeUs, long watermarkTimeUs) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        for (OverlayItem item : overlays) {
            drawStyledOverlay(canvas, item, width, height, density, overlayTimeUs);
        }
        // Watermark is drawn last so it always sits on top of any other overlay/sticker.
        drawWatermark(canvas, watermark, width, height, density, watermarkTimeUs);
        return bitmap;
    }

    /**
     * Draws the resolved creator watermark (text or logo) at its time-jittered corner/center.
     *
     * ✅ Locked invariant (no auto-scale by video resolution): unlike
     * {@link #drawStyledOverlay} below — which scales caption/sticker text by
     * {@code videoWidth / 1080f} so it matches the editor preview at any export
     * resolution — the watermark intentionally does NOT multiply by video width/
     * height anywhere in this method. {@code wm.textSizeSp * density} and the
     * fixed {@code 44 * density} logo box are both resolution-independent, so a
     * 480p export and a 4K export of the same reel get the identical physical
     * watermark size — the on-frame ratio simply shrinks/grows with the frame,
     * it is never renormalized back to a constant ratio.
     *
     * ✅ NEW (anti-tamper/robustness): a bare bitmap/text drawn straight onto the
     * frame disappears almost completely if that patch of video gets cropped out
     * or heavily blurred — there's no trace left once the pixels are gone. Real
     * platforms don't solve this cryptographically either; the practical mitigation
     * is a semi-transparent BLENDED backing plate — {@link #drawWatermarkChip} below —
     * behind the mark, so:
     *   • blurring the frame still leaves a visible soft dark smudge in that corner
     *     (the chip), instead of the mark blending away into whatever was under it;
     *   • combined with the existing per-corner jitter (crop resistance) and the
     *     text drop-shadow (contrast on any background colour), all three together
     *     approximate the Instagram/TikTok repost-watermark's actual behavior —
     *     no single trick is bulletproof, but stacking them raises the bar past a
     *     single static crop or a single blur pass.
     * This is a deterrent, not a guarantee — a determined re-encode/inpaint can
     * still remove any burned-in watermark. Don't oversell it as tamper-proof.
     */
    private static void drawWatermark(Canvas canvas, @Nullable WatermarkSpec wm,
                                       int videoWidth, int videoHeight, float density, long presentationTimeUs) {
        if (wm == null) return;
        int margin = (int) (16 * density);
        // Chip alpha scales WITH the configured opacity (a 20%-opacity watermark
        // gets a faint chip, a 100%-opacity one gets a fuller chip) rather than
        // being a fixed value that ignores the creator's own setting.
        int chipAlpha = Math.round(0.4f * wm.opacity * 255);
        float chipRadius = 8f * density;
        float padH = 10f * density, padV = 6f * density;

        if (wm.logoBitmap != null) {
            // ✅ Logo size now follows the same textSizeSp knob as the text
            // watermark (previously hardcoded to 44dp regardless of the Font
            // Size slider) — this is what lets a Stories-specific variant
            // render a visibly smaller/bigger logo than Feed/Reels, not just
            // reposition it. 16sp (the default) maps to the old 44dp so
            // existing saved settings render identically to before.
            int logoSize = Math.round(wm.textSizeSp * 2.75f * density);
            Bitmap scaled = Bitmap.createScaledBitmap(wm.logoBitmap, logoSize, logoSize, true);
            int boxW = logoSize + (int) (padH * 2);
            int boxH = logoSize + (int) (padV * 2);
            float[] xy = jitteredWatermarkAnchor(wm.position, presentationTimeUs, videoWidth, videoHeight,
                boxW, boxH, margin, density);
            drawWatermarkChip(canvas, xy[0], xy[1], boxW, boxH, chipRadius, chipAlpha);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setAlpha(Math.round(wm.opacity * 255));
            canvas.drawBitmap(scaled, xy[0] + padH, xy[1] + padV, paint);
            return;
        }
        if (wm.text == null || wm.text.isEmpty()) return;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(wm.color);
        paint.setAlpha(Math.round(wm.opacity * 255));
        paint.setTextSize(wm.textSizeSp * density); // fixed sp→px — deliberately no video-resolution multiplier
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setShadowLayer(4f * density, 0f, density, Color.argb(Math.round(wm.opacity * 160), 0, 0, 0));

        float textWidth = paint.measureText(wm.text);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float textHeight = fm.descent - fm.ascent;
        int boxW = Math.round(textWidth + padH * 2);
        int boxH = Math.round(textHeight + padV * 2);
        float[] xy = jitteredWatermarkAnchor(wm.position, presentationTimeUs, videoWidth, videoHeight,
            boxW, boxH, margin, density);
        drawWatermarkChip(canvas, xy[0], xy[1], boxW, boxH, chipRadius, chipAlpha);
        canvas.drawText(wm.text, xy[0] + padH, xy[1] + padV - fm.ascent, paint);
    }

    /**
     * Semi-transparent blended backing plate behind the watermark — see the
     * "anti-tamper/robustness" doc on {@link #drawWatermark}. Always a translucent
     * BLACK rounded rect regardless of the creator's chosen text colour (white,
     * pink, gold, cyan…) so it reads as a consistent soft chip against any video
     * background rather than clashing with — or vanishing into — the text colour.
     */
    private static void drawWatermarkChip(Canvas canvas, float left, float top,
                                           int boxW, int boxH, float radius, int alpha) {
        if (alpha <= 0) return;
        Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chipPaint.setColor(Color.BLACK);
        chipPaint.setAlpha(alpha);
        canvas.drawRoundRect(left, top, left + boxW, top + boxH, radius, radius, chipPaint);
    }

    /** Top-left (x, y) for a {@code boxW}×{@code boxH} box at the given named corner/center. */
    private static float[] watermarkAnchor(@Nullable String position, int videoW, int videoH,
                                            int boxW, int boxH, int margin) {
        String pos = position != null ? position : "Bottom Right";
        float x, y;
        switch (pos) {
            case "Top Left":    x = margin;                     y = margin;                     break;
            case "Top Right":   x = videoW - boxW - margin;      y = margin;                     break;
            case "Bottom Left": x = margin;                     y = videoH - boxH - margin;      break;
            case "Center":      x = (videoW - boxW) / 2f;        y = (videoH - boxH) / 2f;        break;
            case "Bottom Right":
            default:            x = videoW - boxW - margin;      y = videoH - boxH - margin;      break;
        }
        return new float[]{x, y};
    }

    /**
     * Instagram-style moving anchor: starts from the creator's configured corner (falls back
     * to Bottom Right), then every {@link #WATERMARK_JITTER_CYCLE_US} rotates to the next
     * corner, with a small extra per-lap dp nudge — so the watermark keeps landing somewhere
     * new instead of sitting in one crop-able spot for the whole reel. "Center" is left static,
     * since a centered watermark isn't something a corner crop could remove anyway.
     */
    private static float[] jitteredWatermarkAnchor(@Nullable String basePosition, long presentationTimeUs,
                                                     int videoW, int videoH, int boxW, int boxH,
                                                     int margin, float density) {
        if ("Center".equals(basePosition)) {
            return watermarkAnchor(basePosition, videoW, videoH, boxW, boxH, margin);
        }

        int startIndex = 0;
        if (basePosition != null) {
            for (int i = 0; i < WATERMARK_JITTER_CORNERS.length; i++) {
                if (WATERMARK_JITTER_CORNERS[i].equals(basePosition)) { startIndex = i; break; }
            }
        }

        long lap = presentationTimeUs / WATERMARK_JITTER_CYCLE_US;
        String corner = WATERMARK_JITTER_CORNERS[(int) ((lap + startIndex) % WATERMARK_JITTER_CORNERS.length)];
        float[][] offsets = WATERMARK_JITTER_OFFSETS_DP;
        float[] offsetDp = offsets[(int) ((lap / WATERMARK_JITTER_CORNERS.length) % offsets.length)];

        float[] base = watermarkAnchor(corner, videoW, videoH, boxW, boxH, margin);
        float x = clampToFrame(base[0] + offsetDp[0] * density, margin, videoW - boxW - margin);
        float y = clampToFrame(base[1] + offsetDp[1] * density, margin, videoH - boxH - margin);
        return avoidUiSafeZones(x, y, boxW, boxH, videoW, videoH, margin);
    }

    /** Keeps a jitter-nudged coordinate from pushing the watermark off-screen or past its margin. */
    private static float clampToFrame(float value, float min, float max) {
        if (max < min) return min; // watermark box bigger than the safe area — degrade gracefully
        return Math.max(min, Math.min(max, value));
    }

    /** Returns how much of item.text should be visible at presentationTimeUs given its animKey.
     *  Package-private (not private) so ReelTextOverlayRenderer can reuse the exact same
     *  reveal curve for the live playback replay (see playAnimation there) — keeping the
     *  baked-export timing and the live-overlay timing identical. */
    static String revealedText(OverlayItem item, long presentationTimeUs) {
        if ("none".equals(item.animKey) || item.animDurationMs <= 0L) return item.text;
        float progress = Math.min(1f, (presentationTimeUs / 1000f) / item.animDurationMs);
        if ("word".equals(item.animKey)) {
            String[] words = item.text.trim().split("\\s+");
            int shown = Math.max(1, Math.min(words.length, Math.round(progress * words.length)));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(' ');
                sb.append(words[i]);
            }
            return sb.toString();
        }
        int totalChars = item.text.length();
        int shown = Math.max(1, Math.min(totalChars, Math.round(progress * totalChars)));
        return item.text.substring(0, shown);
    }

    /**
     * Draws one advanced text overlay — font family, bold/italic, background
     * style (none/pill/solid/highlight), multi-line alignment, rotation and
     * scale — matching what the Step 2 editor preview (draggable/pinchable
     * TextView) showed, so the exported/baked video looks identical to what
     * the user styled on screen instead of the old fixed white-bold-pill text.
     */
    private static void drawStyledOverlay(Canvas canvas, OverlayItem item, int videoWidth, int videoHeight,
                                           float density, long presentationTimeUs) {
        float sizePx = item.textSizeSp * density * ((float) videoWidth / 1080f) * item.scale;

        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextSize(sizePx);
        paint.setTypeface(resolveTypeface(item.fontKey, item.bold, item.italic));

        // ✅ NEW: Strong/Neon/Typewriter tracking, matching the editor preview's applyFontFlourish().
        if ("neon".equals(item.fontKey)) paint.setLetterSpacing(0.05f);
        else if ("typewriter".equals(item.fontKey)) paint.setLetterSpacing(0.08f);
        else if ("strong".equals(item.fontKey)) paint.setLetterSpacing(0.01f);

        boolean highlight = "highlight".equals(item.bgStyle);
        int textColor = item.color;
        if (highlight) {
            // Light backgrounds get dark text and vice versa, same contrast rule as the editor preview.
            double luminance = (0.299 * Color.red(item.color) + 0.587 * Color.green(item.color) + 0.114 * Color.blue(item.color));
            textColor = luminance > 150 ? Color.BLACK : Color.WHITE;
        }
        paint.setColor(textColor);
        boolean neon = "neon".equals(item.fontKey);
        if (neon) {
            // Bloom pass drawn first (see below), then a crisp un-shadowed pass on top.
            paint.setShadowLayer(0f, 0f, 0f, 0);
        } else if (!"none".equals(item.bgStyle)) {
            paint.setShadowLayer(4f * density, 0f, 2f * density, 0x99000000);
        }

        Paint.Align paintAlign = "left".equals(item.align) ? Paint.Align.LEFT
            : "right".equals(item.align) ? Paint.Align.RIGHT : Paint.Align.CENTER;
        paint.setTextAlign(paintAlign);

        // ✅ NEW: text-in animation — only the revealed prefix is drawn/measured, so the
        // background box (pill/solid/highlight) grows with the text exactly like the preview.
        String displayText = revealedText(item, presentationTimeUs);
        String[] lines = displayText.split("\\n", -1);
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

        // ✅ NEW: Neon glow — two soft blurred bloom passes in the text's own colour, then a
        // crisp pass on top. A single TextView shadowLayer can't build this look on the preview
        // side, but a manually-drawn Canvas has no such limit, so export can go a bit further.
        if (neon) {
            Paint glow = new Paint(paint);
            glow.setShadowLayer(22f * density, 0f, 0f, textColor);
            float gy = -blockHeight / 2f + lineHeight * 0.8f;
            for (String line : lines) {
                float lineX = paintAlign == Paint.Align.LEFT ? -maxLineWidth / 2f
                    : paintAlign == Paint.Align.RIGHT ? maxLineWidth / 2f : 0f;
                canvas.drawText(line, lineX, gy, glow);
                gy += lineHeight;
            }
        }

        // ✅ NEW: outline/stroke pass, drawn behind the fill — same two-pass trick as
        // the editor's StyledOverlayTextView (a single Paint has one colour, so the
        // stroke-coloured pass has to be a separate drawText call before the fill one).
        if (item.outlineEnabled) {
            Paint outline = new Paint(paint);
            outline.setShader(null);
            outline.setStyle(Paint.Style.STROKE);
            outline.setStrokeJoin(Paint.Join.ROUND);
            outline.setStrokeMiter(2f);
            outline.setStrokeWidth(2.5f * density * item.scale);
            outline.setColor(item.outlineColor);
            outline.setShadowLayer(0f, 0f, 0f, 0);
            float oy = -blockHeight / 2f + lineHeight * 0.8f;
            for (String line : lines) {
                float lineX = paintAlign == Paint.Align.LEFT ? -maxLineWidth / 2f
                    : paintAlign == Paint.Align.RIGHT ? maxLineWidth / 2f : 0f;
                canvas.drawText(line, lineX, oy, outline);
                oy += lineHeight;
            }
        }

        // ✅ NEW: gradient/multi-colour fill — a LinearGradient shader spanning the
        // widest line's width, matching StyledOverlayTextView's onSizeChanged shader.
        if (item.gradientEnabled) {
            paint.setShader(new android.graphics.LinearGradient(
                -maxLineWidth / 2f, 0f, maxLineWidth / 2f, 0f,
                item.gradientStart, item.gradientEnd, android.graphics.Shader.TileMode.CLAMP));
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
        // ✅ NEW — mirrors ReelEditorActivity.resolvePreviewTypeface() so export matches preview.
        else if ("strong".equals(fontKey)) base = Typeface.create("sans-serif-black", Typeface.NORMAL);
        else if ("neon".equals(fontKey)) base = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
        else if ("typewriter".equals(fontKey)) base = Typeface.MONOSPACE;
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
