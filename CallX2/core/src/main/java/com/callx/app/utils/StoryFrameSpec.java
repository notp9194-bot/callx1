package com.callx.app.utils;

/**
 * StoryFrameSpec — the ONE place that describes how a story is framed in the viewer, so the
 * editor can show exactly what will be covered. Keep in sync with activity_status_viewer.xml:
 *  - fl_story_card = screen height minus the solid reply bar under it (REPLY_BAR_DP)
 *  - ll_top_chrome (progress segments + owner header) overlays the card's top (TOP_CHROME_DP)
 *  - ll_bottom_chrome (caption / seen-by / live comments, with gradient) overlays the bottom (BOTTOM_CHROME_DP)
 * The baked story image is 9:16 and shown fitCenter in that card.
 */
public final class StoryFrameSpec {
    private StoryFrameSpec() {}

    public static final float REPLY_BAR_DP    = 64f;
    public static final float TOP_CHROME_DP   = 88f;   // status bar + progress bars + header row
    public static final float BOTTOM_CHROME_DP = 96f;  // caption / seen-by / gradient

    /** Insets (in canvas pixels) of the area the viewer will NOT cover with its own chrome. out[0]=top, out[1]=bottom. */
    public static void safeInsetsCanvasPx(int canvasW, int canvasH, int screenW, int screenH,
                                          float density, float[] out) {
        out[0] = out[1] = 0f;
        if (canvasW <= 0 || canvasH <= 0 || screenW <= 0 || screenH <= 0) return;
        float cardH = Math.max(1f, screenH - REPLY_BAR_DP * density);
        // viewer: fitCenter inside (screenW x cardH)
        float scale = Math.min(screenW / (float) canvasW, cardH / (float) canvasH);
        float imgH  = canvasH * scale;
        float extra = Math.max(0f, (cardH - imgH) / 2f);   // letterbox above/below the image
        float topOverlap    = Math.max(0f, TOP_CHROME_DP * density - extra);
        float bottomOverlap = Math.max(0f, BOTTOM_CHROME_DP * density - extra);
        out[0] = topOverlap / scale;
        out[1] = bottomOverlap / scale;
    }
}
