package com.callx.app.conversation.models;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * v169 — PresentationMessage
 *
 * Data model for a "presentation card" message type.
 *
 * A presentation message is a rich-content message that renders as a styled
 * card bubble in the chat list, visually similar to a single slide in a
 * presentation. It supports:
 *
 *   - Multiple text blocks (title, body, caption) each with independent styles
 *   - A background: solid colour OR a remote image URL
 *   - An optional overlay gradient (for legibility over dark/bright photos)
 *   - Aspect ratio: LANDSCAPE (16:9), PORTRAIT (9:16), or SQUARE (1:1)
 *   - Per-slide theme: LIGHT (white text), DARK (black text)
 *
 * ── Firebase storage structure ───────────────────────────────────────────────
 *
 * This model is stored as a sub-field of the existing MessageEntity under the
 * key "presentationData" (JSON-serialised). The message type field is set to
 * "presentation" so MessagePagingAdapter routes it to PresentationCanvasView.
 *
 * {
 *   "type": "presentation",
 *   "presentationData": {
 *     "aspectRatio": "LANDSCAPE",
 *     "theme": "LIGHT",
 *     "bgColor": -1,               // ARGB int; 0 if using bgImageUrl
 *     "bgImageUrl": "https://…",   // null if using bgColor
 *     "bgImageThumbUrl": "https://…",
 *     "overlayGradient": true,
 *     "textBlocks": [
 *       {
 *         "text": "Hello, World!",
 *         "role": "TITLE",
 *         "textColor": -1,
 *         "textSizeSp": 28,
 *         "fontFamily": "sans-serif-medium",
 *         "alignment": "CENTER",
 *         "bold": true,
 *         "italic": false,
 *         "underline": false,
 *         "strikethrough": false,
 *         "letterSpacing": 0.05,
 *         "lineHeightMult": 1.2
 *       }
 *     ]
 *   }
 * }
 */
public class PresentationMessage {

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum AspectRatio { LANDSCAPE, PORTRAIT, SQUARE }

    public enum Theme { LIGHT, DARK }

    public enum TextRole { TITLE, BODY, CAPTION }

    public enum TextAlignment { LEFT, CENTER, RIGHT }

    // ── Fields ────────────────────────────────────────────────────────────────

    @NonNull  public AspectRatio aspectRatio     = AspectRatio.LANDSCAPE;
    @NonNull  public Theme       theme           = Theme.DARK;
    @ColorInt public int         bgColor         = 0xFF1A1A2E;   // default dark navy
    @Nullable public String      bgImageUrl      = null;
    @Nullable public String      bgImageThumbUrl = null;
    public boolean               overlayGradient = true;
    @NonNull  public List<TextBlock> textBlocks  = new ArrayList<>();

    // ── TextBlock inner class ─────────────────────────────────────────────────

    public static class TextBlock {
        @NonNull  public String        text          = "";
        @NonNull  public TextRole      role          = TextRole.BODY;
        @ColorInt public int           textColor     = 0xFFFFFFFF;
        public int                     textSizeSp    = 14;
        @NonNull  public String        fontFamily    = "sans-serif";
        @NonNull  public TextAlignment alignment     = TextAlignment.CENTER;
        public boolean                 bold          = false;
        public boolean                 italic        = false;
        public boolean                 underline     = false;
        public boolean                 strikethrough = false;
        @ColorInt public int           highlight     = 0;  // 0 = none
        public float                   letterSpacing = 0f;
        public float                   lineHeightMult= 1.2f;

        // ── Constructor helpers ────────────────────────────────────────────────

        @NonNull
        public static TextBlock title(String text) {
            TextBlock b = new TextBlock();
            b.text       = text;
            b.role       = TextRole.TITLE;
            b.textSizeSp = 28;
            b.bold       = true;
            b.alignment  = TextAlignment.CENTER;
            b.fontFamily = "sans-serif-medium";
            b.textColor  = 0xFFFFFFFF;
            return b;
        }

        @NonNull
        public static TextBlock body(String text) {
            TextBlock b = new TextBlock();
            b.text       = text;
            b.role       = TextRole.BODY;
            b.textSizeSp = 15;
            b.alignment  = TextAlignment.LEFT;
            b.fontFamily = "sans-serif";
            b.textColor  = 0xFFEEEEEE;
            return b;
        }

        @NonNull
        public static TextBlock caption(String text) {
            TextBlock b = new TextBlock();
            b.text       = text;
            b.role       = TextRole.CAPTION;
            b.textSizeSp = 11;
            b.italic     = true;
            b.alignment  = TextAlignment.CENTER;
            b.fontFamily = "sans-serif";
            b.textColor  = 0xFFCCCCCC;
            return b;
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    @NonNull
    public static PresentationMessage fromPlainText(@NonNull String text) {
        PresentationMessage pm = new PresentationMessage();
        pm.textBlocks.add(TextBlock.body(text));
        return pm;
    }

    // ── Aspect-ratio helpers ──────────────────────────────────────────────────

    /** Returns the width : height ratio as a float. */
    public float aspectRatioFloat() {
        switch (aspectRatio) {
            case PORTRAIT:  return 9f / 16f;
            case SQUARE:    return 1f;
            case LANDSCAPE: default: return 16f / 9f;
        }
    }

    // ── JSON serialisation (simple, avoids Gson dependency) ──────────────────

    @NonNull
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendStr(sb, "aspectRatio", aspectRatio.name()); sb.append(",");
        appendStr(sb, "theme",       theme.name());       sb.append(",");
        sb.append("\"bgColor\":").append(bgColor).append(",");
        appendStr(sb, "bgImageUrl",      bgImageUrl);      sb.append(",");
        appendStr(sb, "bgImageThumbUrl", bgImageThumbUrl); sb.append(",");
        sb.append("\"overlayGradient\":").append(overlayGradient).append(",");
        sb.append("\"textBlocks\":[");
        for (int i = 0; i < textBlocks.size(); i++) {
            if (i > 0) sb.append(",");
            appendTextBlock(sb, textBlocks.get(i));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendTextBlock(StringBuilder sb, TextBlock b) {
        sb.append("{");
        appendStr(sb, "text",       b.text);            sb.append(",");
        appendStr(sb, "role",       b.role.name());     sb.append(",");
        sb.append("\"textColor\":").append(b.textColor).append(",");
        sb.append("\"textSizeSp\":").append(b.textSizeSp).append(",");
        appendStr(sb, "fontFamily", b.fontFamily);      sb.append(",");
        appendStr(sb, "alignment",  b.alignment.name());sb.append(",");
        sb.append("\"bold\":").append(b.bold).append(",");
        sb.append("\"italic\":").append(b.italic).append(",");
        sb.append("\"underline\":").append(b.underline).append(",");
        sb.append("\"strikethrough\":").append(b.strikethrough).append(",");
        sb.append("\"highlight\":").append(b.highlight).append(",");
        sb.append("\"letterSpacing\":").append(b.letterSpacing).append(",");
        sb.append("\"lineHeightMult\":").append(b.lineHeightMult);
        sb.append("}");
    }

    private static void appendStr(StringBuilder sb, String key, @Nullable String val) {
        sb.append("\"").append(key).append("\":");
        if (val == null) sb.append("null");
        else sb.append("\"").append(val.replace("\"", "\\\"")).append("\"");
    }

    @NonNull
    public static PresentationMessage fromJson(@NonNull String json) {
        // Minimal parser — in production, replace with Gson/Moshi.
        PresentationMessage pm = new PresentationMessage();
        try {
            pm.aspectRatio     = AspectRatio.valueOf(extractStr(json, "aspectRatio", "LANDSCAPE"));
            pm.theme           = Theme.valueOf(extractStr(json, "theme", "DARK"));
            pm.bgColor         = (int) extractLong(json, "bgColor", 0xFF1A1A2E);
            pm.bgImageUrl      = extractStrNullable(json, "bgImageUrl");
            pm.bgImageThumbUrl = extractStrNullable(json, "bgImageThumbUrl");
            pm.overlayGradient = extractBool(json, "overlayGradient", true);
            // textBlocks parsing — left minimal; use Gson in production.
        } catch (Exception e) {
            // Return default on any parse error.
        }
        return pm;
    }

    // ── Minimal JSON extractors ───────────────────────────────────────────────

    private static String extractStr(String json, String key, String def) {
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) return def;
        int start = i + pat.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : def;
    }

    private static @Nullable String extractStrNullable(String json, String key) {
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int start = i + pat.length();
        int end = json.indexOf("\"", start);
        return end > start ? json.substring(start, end) : null;
    }

    private static long extractLong(String json, String key, long def) {
        String pat = "\"" + key + "\":";
        int i = json.indexOf(pat);
        if (i < 0) return def;
        int start = i + pat.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        try { return Long.parseLong(json.substring(start, end)); } catch (Exception e) { return def; }
    }

    private static boolean extractBool(String json, String key, boolean def) {
        String pat = "\"" + key + "\":";
        int i = json.indexOf(pat);
        if (i < 0) return def;
        String rest = json.substring(i + pat.length()).trim();
        if (rest.startsWith("true"))  return true;
        if (rest.startsWith("false")) return false;
        return def;
    }
}
