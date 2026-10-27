package com.callx.app.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StoryFrameSpecTest {
    @Test public void tallCard_letterboxAbsorbsPartOfChrome() {
        // 1080x1920 canvas shown on a 1080x2400 screen (d=3): card = 2400-192 = 2208, image fits width -> 1920 tall, extra = 144
        float[] o = new float[2];
        StoryFrameSpec.safeInsetsCanvasPx(1080, 1920, 1080, 2400, 3f, o);
        assertEquals((88f * 3f - 144f) / 1f, o[0], 0.5f);   // 264-144 = 120
        assertEquals((96f * 3f - 144f) / 1f, o[1], 0.5f);   // 288-144 = 144
    }

    @Test public void veryTallCard_noOverlap() {
        float[] o = new float[2];
        StoryFrameSpec.safeInsetsCanvasPx(1080, 1920, 1080, 3200, 3f, o);
        assertEquals(0f, o[0], 0.01f);
        assertEquals(0f, o[1], 0.01f);
    }

    @Test public void shortCard_fitByHeight_insetsScaledDown() {
        float[] o = new float[2];
        StoryFrameSpec.safeInsetsCanvasPx(540, 960, 1080, 1700, 3f, o); // card 1508 -> scale 1.571
        assertTrue(o[0] > 0 && o[1] > 0);
        assertEquals(88f * 3f / (1508f / 960f), o[0], 0.5f);
    }

    @Test public void zeroSizes_safe() {
        float[] o = {9, 9};
        StoryFrameSpec.safeInsetsCanvasPx(0, 0, 0, 0, 3f, o);
        assertEquals(0f, o[0], 0f);
    }

    @Test public void v639_fullWidthCanvas_sidesGetMinEdgeMargin() {
        float[] o = new float[4];
        // 1080x1920 canvas on a 1080x2400 screen, d=3, status bar 72px
        StoryFrameSpec.safeInsets(1080, 1920, 1080, 2400, 3f, 72f, 0f, 0f, 0f, 0f, 0f, o);
        assertTrue(o[2] > 0 && o[3] > 0);            // vertical guide exists
        assertEquals(o[2], o[3], 0.01f);
    }

    @Test public void v639_narrowCanvas_sideLetterboxAbsorbsMargin() {
        float[] o = new float[4];
        // very narrow canvas: image fit by height, big side letterbox -> no side overlap
        StoryFrameSpec.safeInsets(540, 1920, 1080, 2400, 3f, 72f, 0f, 0f, 0f, 0f, 0f, o);
        assertEquals(0f, o[2], 0.01f);
        assertEquals(0f, o[3], 0.01f);
    }

    @Test public void v639_cutoutSideInsetWins_andMeasuredChromeIsUsed() {
        float[] a = new float[4], b = new float[4];
        StoryFrameSpec.safeInsets(1080, 1920, 1080, 2400, 3f, 72f, 0f, 0f, 0f, 0f, 0f, a);
        StoryFrameSpec.safeInsets(1080, 1920, 1080, 2400, 3f, 72f, 0f, 120f, 0f, 300f, 0f, b);
        assertTrue(b[2] > a[2]);                      // cutout wider than default margin
        assertTrue(b[0] > a[0]);                      // measured header taller than default
    }

    @Test public void v639_zeroSizes_safe() {
        float[] o = {9, 9, 9, 9};
        StoryFrameSpec.safeInsets(0, 0, 0, 0, 3f, 0, 0, 0, 0, 0, 0, o);
        for (float v : o) assertEquals(0f, v, 0f);
    }

    @Test public void v640_tallScreen_topBottomAbsorbed_sidesStillGuarded() {
        float[] o = new float[4];
        // screen much taller than 9:16 -> big top/bottom letterbox, chrome no longer overlaps the canvas
        StoryFrameSpec.safeInsets(1080, 1920, 1080, 3600, 3f, 72f, 0f, 0f, 0f, 0f, 0f, o);
        assertEquals(0f, o[0], 0.01f);
        assertEquals(0f, o[1], 0.01f);
        assertTrue(o[2] > 0 && o[3] > 0);
    }

    @Test public void v640_wideScreen_fitByHeight_noSideInset() {
        float[] o = new float[4];
        // tablet-like wide viewer: image fit by height, wide side letterbox
        StoryFrameSpec.safeInsets(1080, 1920, 1600, 2400, 3f, 72f, 0f, 0f, 0f, 0f, 0f, o);
        assertEquals(0f, o[2], 0.01f);
        assertEquals(0f, o[3], 0.01f);
        assertTrue(o[0] > 0);
    }

    @Test public void v640_asymmetricCutout_rightOnly() {
        float[] o = new float[4];
        StoryFrameSpec.safeInsets(1080, 1920, 1080, 2400, 3f, 72f, 0f, 0f, 150f, 0f, 0f, o);
        assertTrue(o[3] > o[2]);
    }

    @Test public void v640_isInsideSafe_centerInside_edgeOutside() {
        float[] ins = {100f, 100f, 40f, 40f};
        assertTrue(StoryFrameSpec.isInsideSafe(540, 960, 300, 100, 0f, 1080, 1920, ins));
        assertFalse(StoryFrameSpec.isInsideSafe(540, 120, 300, 100, 0f, 1080, 1920, ins));   // pokes into header strip
        assertFalse(StoryFrameSpec.isInsideSafe(60, 960, 300, 100, 0f, 1080, 1920, ins));    // pokes past left edge
    }

    @Test public void v640_isInsideSafe_rotationGrowsBounds() {
        float[] ins = {100f, 100f, 40f, 40f};
        // 600x40 bar fits horizontally but rotated 90° it is 600 tall and hits the bottom strip near the edge
        assertTrue(StoryFrameSpec.isInsideSafe(540, 1600, 600, 40, 0f, 1080, 1920, ins));
        assertFalse(StoryFrameSpec.isInsideSafe(540, 1600, 600, 40, 90f, 1080, 1920, ins));
    }

    @Test public void v689_bottomChrome_growsWithCaption() {
        float none = StoryFrameSpec.bottomChromeDp(0, false);
        assertEquals(StoryFrameSpec.BOTTOM_MIN_DP, none, 0.01f);
        assertEquals(StoryFrameSpec.BOTTOM_MIN_DP, StoryFrameSpec.bottomChromeDp(1, false), 0.01f); // 62 < min
        assertTrue(StoryFrameSpec.bottomChromeDp(3, false) > StoryFrameSpec.bottomChromeDp(2, false));
        assertTrue(StoryFrameSpec.bottomChromeDp(2, true) > StoryFrameSpec.bottomChromeDp(2, false));
    }
}
