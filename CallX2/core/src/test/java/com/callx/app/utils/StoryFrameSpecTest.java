package com.callx.app.utils;

import static org.junit.Assert.assertEquals;
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
}
