package com.callx.app.utils;

/**
 * Cross-module lock so the Reels feature (feature-reels) can tell the host
 * Activity (app module) to disable the outer tab ViewPager2's swipe gesture.
 *
 * Why this exists: CallX2's main screen swipes between tabs (Chats / Status /
 * Groups / Reels / Calls) using a horizontal ViewPager2. The Reels feed is a
 * VERTICAL ViewPager2 nested inside that tab, and a photo-slideshow reel adds
 * a third, HORIZONTAL ViewPager2 nested inside that for left/right photo
 * navigation. requestDisallowInterceptTouchEvent() on the photo pager is the
 * first line of defense, but three levels of nested ViewPager2 make that
 * fragile in practice — this lock is the deterministic backstop: while a
 * multi-photo reel is the one on screen, the outer tab pager's swipe is
 * simply turned off, exactly like Instagram (whose top-level tabs don't
 * respond to swipe at all while you're inside Reels/a photo carousel).
 *
 * feature-reels cannot depend on :app directly (wrong dependency direction),
 * so this tiny static bridge lives in :core, which both modules already
 * depend on. MainActivity registers a Controller once; Reels-side code calls
 * lock()/unlock() as photo reels come in/out of view.
 */
public final class ReelTabSwipeLock {

    public interface Controller {
        void setTabSwipeEnabled(boolean enabled);
    }

    private static Controller controller;
    private static boolean locked = false;

    private ReelTabSwipeLock() {}

    /** Called once by MainActivity (e.g. in onCreate) to wire itself up. */
    public static void setController(Controller c) {
        controller = c;
        // Re-apply whatever state is already in effect to a freshly
        // (re)attached controller, e.g. after an Activity recreation.
        if (controller != null) controller.setTabSwipeEnabled(!locked);
    }

    /** Disables the outer tab pager's swipe. Safe to call repeatedly. */
    public static void lock() {
        if (locked) return;
        locked = true;
        if (controller != null) controller.setTabSwipeEnabled(false);
    }

    /** Re-enables the outer tab pager's swipe. Safe to call repeatedly. */
    public static void unlock() {
        if (!locked) return;
        locked = false;
        if (controller != null) controller.setTabSwipeEnabled(true);
    }
}
