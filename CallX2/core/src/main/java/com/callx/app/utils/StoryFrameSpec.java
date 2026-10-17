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

    // ── v639: viewer-accurate model (4 sides, real window size + insets) ───────────────────
    /** Gap between the viewer's top inset (status bar / notch) and the story card (see StatusViewerActivity#setupEdgeToEdge). */
    public static final float CARD_TOP_GAP_DP = 12f;
    /** Header chrome INSIDE the card (progress segments + owner row). Default; the viewer publishes its measured value via StoryChromePrefs. */
    public static final float TOP_CHROME_IN_CARD_DP = 64f;
    /** Minimum breathing room kept from the left/right screen edge (rounded card corners / edge-swipe gestures). */
    public static final float SIDE_EDGE_DP = 12f;

    /**
     * Viewer-accurate safe insets, in canvas pixels. out[0]=top, out[1]=bottom, out[2]=left, out[3]=right.
     *
     * @param viewerW/viewerH  REAL viewer window size (full screen incl. system bars, the viewer is immersive)
     * @param sysTop           top inset ignoring visibility (status bar + cutout) — the card starts below it
     * @param sysBottom        bottom inset the viewer actually reserves (cutout / ime), added to the reply bar
     * @param sysLeft/sysRight cutout insets on the sides (landscape notch etc.)
     * @param topChromePx      measured header chrome inside the card, or <=0 for the default
     * @param bottomChromePx   measured caption chrome inside the card, or <=0 for the default
     */
    public static void safeInsets(int canvasW, int canvasH, int viewerW, int viewerH, float density,
                                  float sysTop, float sysBottom, float sysLeft, float sysRight,
                                  float topChromePx, float bottomChromePx, float[] out) {
        out[0] = out[1] = out[2] = out[3] = 0f;
        if (canvasW <= 0 || canvasH <= 0 || viewerW <= 0 || viewerH <= 0) return;
        float cardTop = sysTop + CARD_TOP_GAP_DP * density;
        float cardH   = Math.max(1f, viewerH - cardTop - (REPLY_BAR_DP * density + sysBottom));
        float cardW   = viewerW;
        float scale   = Math.min(cardW / (float) canvasW, cardH / (float) canvasH);
        float extraX  = Math.max(0f, (cardW - canvasW * scale) / 2f);   // side letterbox
        float extraY  = Math.max(0f, (cardH - canvasH * scale) / 2f);   // top/bottom letterbox
        float topC = topChromePx    > 0f ? topChromePx    : TOP_CHROME_IN_CARD_DP * density;
        float botC = bottomChromePx > 0f ? bottomChromePx : BOTTOM_CHROME_DP * density;
        float sideMin = SIDE_EDGE_DP * density;
        out[0] = Math.max(0f, topC - extraY) / scale;
        out[1] = Math.max(0f, botC - extraY) / scale;
        out[2] = Math.max(0f, Math.max(sideMin, sysLeft)  - extraX) / scale;
        out[3] = Math.max(0f, Math.max(sideMin, sysRight) - extraX) / scale;
    }

    /**
     * True when an overlay (text / sticker) lies fully inside the safe rect described by
     * {@code ins} (top, bottom, left, right — as returned by {@link #safeInsets}).
     * cx/cy = centre, w/h = already-scaled size, rotation in degrees; all in canvas px.
     */
    public static boolean isInsideSafe(float cx, float cy, float w, float h, float rotationDeg,
                                       float canvasW, float canvasH, float[] ins) {
        double r = Math.toRadians(rotationDeg);
        double c = Math.abs(Math.cos(r)), s = Math.abs(Math.sin(r));
        float hw = (float) ((w * c + h * s) / 2.0), hh = (float) ((w * s + h * c) / 2.0);
        return cx - hw >= ins[2] && cx + hw <= canvasW - ins[3]
            && cy - hh >= ins[0] && cy + hh <= canvasH - ins[1];
    }
}
