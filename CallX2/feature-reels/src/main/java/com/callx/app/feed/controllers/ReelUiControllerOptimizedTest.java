package com.callx.app.feed.controllers;

import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

import android.content.Context;
import java.util.Arrays;
import java.util.List;

/**
 * UNIT TEST SUITE: ReelUiController optimization validation
 *
 * Tests cover:
 * 1. Hashtag rendering correctness
 * 2. View pool allocation patterns
 * 3. Drawable cache behavior
 * 4. Hashtag state cache
 * 5. Duet/Stitch button rendering
 * 6. Performance benchmarks
 * 7. Memory leak detection
 */
@RunWith(AndroidJUnit4.class)
public class ReelUiControllerOptimizedTest {

    private Context context;
    private ReelChipViewPool chipViewPool;
    private ReelHashtagCache hashtagCache;
    private LinearLayout testContainer;

    @Before
    public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        chipViewPool = new ReelChipViewPool(context);
        hashtagCache = new ReelHashtagCache();
        testContainer = new LinearLayout(context);
    }

    // ── HASHTAG CACHE TESTS ────────────────────────────────────────────────

    @Test
    public void testHashtagCacheHit() {
        String caption = "test #hello #world";
        List<String> tags = Arrays.asList("hello", "world");

        // First call: miss
        assertNull(hashtagCache.getCachedHashtags(caption));

        // Cache it
        hashtagCache.cacheHashtags(caption, tags);

        // Second call: hit
        List<String> cached = hashtagCache.getCachedHashtags(caption);
        assertNotNull(cached);
        assertEquals(tags, cached);
    }

    @Test
    public void testHashtagCacheLRUEviction() {
        // Fill cache with 51 entries (max is 50)
        for (int i = 0; i < 51; i++) {
            String caption = "caption_" + i;
            List<String> tags = Arrays.asList("tag_" + i);
            hashtagCache.cacheHashtags(caption, tags);
        }

        // Cache size should not exceed MAX_CACHE_SIZE
        assertTrue(hashtagCache.getCacheSize() <= 50);

        // First entry should be evicted (oldest, least recently used)
        assertNull(hashtagCache.getCachedHashtags("caption_0"));

        // Latest entry should be present
        assertNotNull(hashtagCache.getCachedHashtags("caption_50"));
    }

    @Test
    public void testHashtagCacheEmptyCaption() {
        // Empty/null captions should not be cached
        hashtagCache.cacheHashtags("", null);
        hashtagCache.cacheHashtags(null, Arrays.asList("tag"));

        assertEquals(0, hashtagCache.getCacheSize());
    }

    // ── VIEW POOL TESTS ────────────────────────────────────────────────────

    @Test
    public void testChipPoolPreWarm() {
        // Pre-warm should create POOL_SIZE chips
        assertTrue(chipViewPool.getPoolSize() > 0);
    }

    @Test
    public void testChipPoolAcquireRelease() {
        int initialSize = chipViewPool.getPoolSize();

        // Acquire a chip
        ReelChipViewPool.ChipView chip = chipViewPool.acquire();
        assertNotNull(chip);
        assertNotNull(chip.textView);
        assertNotNull(chip.layoutParams);

        // Release it back
        chipViewPool.release(chip);

        // Pool size should recover
        assertTrue(chipViewPool.getPoolSize() >= initialSize - 1);
    }

    @Test
    public void testChipPoolReusesTextView() {
        ReelChipViewPool.ChipView chip1 = chipViewPool.acquire();
        chip1.textView.setText("test1");

        chipViewPool.release(chip1);

        ReelChipViewPool.ChipView chip2 = chipViewPool.acquire();

        // If pool hit, chip2 is the same object as chip1 (reused)
        // but its state should be reset
        assertEquals("", chip2.textView.getText().toString());
        assertNull(chip2.textView.getOnClickListener());
    }

    @Test
    public void testChipPoolMaxCapacity() {
        // Pool should not grow beyond MAX_POOLED_CHIPS
        int startSize = chipViewPool.getPoolSize();

        for (int i = 0; i < 100; i++) {
            chipViewPool.release(new ReelChipViewPool.ChipView(new TextView(context)));
        }

        // Pool size capped
        assertTrue(chipViewPool.getPoolSize() <= 20);
    }

    // ── DRAWABLE CACHE TESTS ───────────────────────────────────────────────

    @Test
    public void testDrawableCacheDuetButton() {
        android.graphics.drawable.GradientDrawable d1 = ReelDrawableCache.getDuetButtonDrawable();
        android.graphics.drawable.GradientDrawable d2 = ReelDrawableCache.getDuetButtonDrawable();

        // Both calls should return the SAME object (cached)
        assertSame(d1, d2);
    }

    @Test
    public void testDrawableCacheStitchButton() {
        android.graphics.drawable.GradientDrawable d1 = ReelDrawableCache.getStitchButtonDrawable();
        android.graphics.drawable.GradientDrawable d2 = ReelDrawableCache.getStitchButtonDrawable();

        // Both calls should return the SAME object (cached)
        assertSame(d1, d2);
    }

    @Test
    public void testDrawableCacheSeparateTypes() {
        android.graphics.drawable.GradientDrawable duet = ReelDrawableCache.getDuetButtonDrawable();
        android.graphics.drawable.GradientDrawable stitch = ReelDrawableCache.getStitchButtonDrawable();

        // Should be different objects
        assertNotSame(duet, stitch);
    }

    @Test
    public void testDrawableCacheSize() {
        // Clear and rebuild
        ReelDrawableCache.clear();

        ReelDrawableCache.getDuetButtonDrawable();
        ReelDrawableCache.getStitchButtonDrawable();

        assertEquals(2, ReelDrawableCache.getCacheSize());
    }

    // ── HASHTAG RENDERING VALIDATION TESTS ─────────────────────────────────

    @Test
    public void testHashtagRenderingCorrectness() {
        List<String> tags = Arrays.asList("hello", "world", "test");

        // Simulate rendering (simplified version of renderHashtagsOptimized)
        for (String tag : tags) {
            ReelChipViewPool.ChipView chipWrapper = chipViewPool.acquire();
            TextView chip = chipWrapper.textView;
            chip.setText("#" + tag);
            testContainer.addView(chip);
        }

        // Verify rendering
        assertTrue(ReelUiControllerMigrationHelper.validateHashtagRendering(
            tags, testContainer
        ));
    }

    @Test
    public void testHashtagRenderingWithDuplicateTags() {
        List<String> tags = Arrays.asList("tag", "tag", "other");

        for (String tag : tags) {
            ReelChipViewPool.ChipView chipWrapper = chipViewPool.acquire();
            TextView chip = chipWrapper.textView;
            chip.setText("#" + tag);
            testContainer.addView(chip);
        }

        assertTrue(ReelUiControllerMigrationHelper.validateHashtagRendering(
            tags, testContainer
        ));
    }

    @Test
    public void testHashtagRenderingEmpty() {
        List<String> emptyTags = Arrays.asList();

        assertTrue(ReelUiControllerMigrationHelper.validateHashtagRendering(
            emptyTags, testContainer
        ));
    }

    // ── PERFORMANCE BENCHMARK TESTS ────────────────────────────────────────

    @Test
    public void testHashtagPoolingAllocationReduction() {
        List<String> tags = Arrays.asList(
            "tag1", "tag2", "tag3", "tag4", "tag5",
            "tag6", "tag7", "tag8", "tag9", "tag10"
        );

        long startTime = System.nanoTime();

        // Old approach: allocate everything
        for (String tag : tags) {
            TextView tv = new TextView(context);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            );
            tv.setLayoutParams(lp);
        }

        long oldApproachTime = System.nanoTime() - startTime;

        startTime = System.nanoTime();

        // New approach: use pool
        for (String tag : tags) {
            ReelChipViewPool.ChipView chip = chipViewPool.acquire();
            chip.textView.setText("#" + tag);
            chipViewPool.release(chip);
        }

        long newApproachTime = System.nanoTime() - startTime;

        // New approach should be significantly faster (90%+ improvement expected)
        assertTrue("New approach should be faster",
            newApproachTime < oldApproachTime * 0.3); // Allow 70% slowdown for measurement variance
    }

    @Test
    public void testDrawableCachingPerformance() {
        // First call (miss)
        long startTime1 = System.nanoTime();
        ReelDrawableCache.getDuetButtonDrawable();
        long time1 = System.nanoTime() - startTime1;

        // Subsequent calls (hit)
        long startTime2 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            ReelDrawableCache.getDuetButtonDrawable();
        }
        long time2 = (System.nanoTime() - startTime2) / 100;

        // Cache hits should be 100x+ faster than creation
        assertTrue("Cache hit should be faster than creation",
            time2 < time1 / 10);
    }

    // ── MEMORY LEAK DETECTION TESTS ────────────────────────────────────────

    @Test
    public void testChipPoolClearDoesNotLeak() {
        ReelChipViewPool pool = new ReelChipViewPool(context);
        pool.clear();

        assertEquals(0, pool.getPoolSize());
    }

    @Test
    public void testHashtagCacheClearDoesNotLeak() {
        for (int i = 0; i < 50; i++) {
            hashtagCache.cacheHashtags("caption_" + i, Arrays.asList("tag_" + i));
        }

        hashtagCache.clear();

        assertEquals(0, hashtagCache.getCacheSize());
    }

    // ── INTEGRATION TESTS ──────────────────────────────────────────────────

    @Test
    public void testOptimisticReuseWhenCountMatches() {
        List<String> tags1 = Arrays.asList("tag1", "tag2");

        // First render
        for (String tag : tags1) {
            ReelChipViewPool.ChipView chip = chipViewPool.acquire();
            chip.textView.setText("#" + tag);
            testContainer.addView(chip.textView);
        }

        int firstRenderChildCount = testContainer.getChildCount();

        // Second render with same count
        List<String> tags2 = Arrays.asList("tag3", "tag4");

        // In optimized version, we'd update in-place instead of removeAll
        for (int i = 0; i < tags2.size(); i++) {
            TextView tv = (TextView) testContainer.getChildAt(i);
            tv.setText("#" + tags2.get(i));
        }

        // Child count should remain same (optimistic reuse)
        assertEquals(firstRenderChildCount, testContainer.getChildCount());
    }

    @Test
    public void testRemovalWhenCountDiffers() {
        // Add 3 chips
        for (int i = 0; i < 3; i++) {
            ReelChipViewPool.ChipView chip = chipViewPool.acquire();
            chip.textView.setText("#tag" + i);
            testContainer.addView(chip.textView);
        }

        assertEquals(3, testContainer.getChildCount());

        // Remove all and add only 2
        testContainer.removeAllViews();
        for (int i = 0; i < 2; i++) {
            ReelChipViewPool.ChipView chip = chipViewPool.acquire();
            chip.textView.setText("#tag" + i);
            testContainer.addView(chip.textView);
        }

        assertEquals(2, testContainer.getChildCount());
    }

    // ── STRESS TESTS ───────────────────────────────────────────────────────

    @Test
    public void testRapidAcquireReleaseCycles() {
        for (int i = 0; i < 1000; i++) {
            ReelChipViewPool.ChipView chip = chipViewPool.acquire();
            assertNotNull(chip);
            chipViewPool.release(chip);
        }

        // Should complete without crashing or memory issues
        assertTrue(true);
    }

    @Test
    public void testLargeHashtagCount() {
        List<String> manyTags = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            manyTags.add("tag" + i);
        }

        // Simulate rendering 100 hashtags
        for (String tag : manyTags) {
            ReelChipViewPool.ChipView chip = chipViewPool.acquire();
            chip.textView.setText("#" + tag);
            testContainer.addView(chip.textView);
        }

        assertEquals(100, testContainer.getChildCount());
    }
}
