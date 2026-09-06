package com.callx.app.utils;

import androidx.recyclerview.widget.RecyclerView;

import java.lang.reflect.Field;

/**
 * Shared, reusable version of the reflection trick that {@code
 * FastFlingRecyclerView} (feature-chat) uses to lower a RecyclerView's
 * internal OverScroller friction — the real "Telegram-glide" fix (friction,
 * not launch velocity): same launch speed decelerates over a longer
 * distance because the deceleration curve itself is slower.
 *
 * Pulled out here so screens that use a *plain* {@code RecyclerView} (no
 * custom subclass — e.g. comment lists bound straight from XML) can opt in
 * with one call, instead of duplicating FastFlingRecyclerView's reflection
 * code or having to swap their XML tag to a custom subclass.
 *
 * Same safety contract as the original: field found by TYPE, not a
 * hardcoded name (tolerates AndroidX internal renames), and every failure
 * mode is swallowed — a device/AndroidX version where this doesn't work
 * silently keeps stock friction, never crashes.
 */
public final class RecyclerViewFrictionTuner {

    /** android.widget.OverScroller's own default (SCROLL_FRICTION). */
    public static final float STOCK_FRICTION = 0.015f;

    /** Same value FastFlingRecyclerView uses for chat — long, Telegram-like coast. */
    public static final float TELEGRAM_FRICTION = 0.007f;

    private RecyclerViewFrictionTuner() {}

    /**
     * Attempts to lower {@code rv}'s internal OverScroller friction.
     *
     * @return true if the friction was actually reduced; false if reflection
     *         didn't find the expected fields on this AndroidX/OEM build
     *         (RecyclerView is left completely untouched in that case).
     */
    public static boolean applyReducedFriction(RecyclerView rv, float friction) {
        if (rv == null) return false;
        try {
            Field flingerField = findFieldByTypeName(RecyclerView.class, "ViewFlinger");
            if (flingerField == null) return false;
            flingerField.setAccessible(true);
            Object flinger = flingerField.get(rv);
            if (flinger == null) return false;

            Field scrollerField = findFieldByAssignableType(flinger.getClass(), android.widget.OverScroller.class);
            if (scrollerField == null) return false;
            scrollerField.setAccessible(true);
            Object scroller = scrollerField.get(flinger);
            if (!(scroller instanceof android.widget.OverScroller)) return false;

            ((android.widget.OverScroller) scroller).setFriction(friction);
            return true;
        } catch (Exception e) {
            // ReflectiveOperationException family + any unexpected cast issue —
            // never let a reflection hiccup take down the host screen.
            return false;
        }
    }

    /** Convenience overload using the standard {@link #TELEGRAM_FRICTION} value. */
    public static boolean applyReducedFriction(RecyclerView rv) {
        return applyReducedFriction(rv, TELEGRAM_FRICTION);
    }

    private static Field findFieldByTypeName(Class<?> start, String simpleNameContains) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getSimpleName().contains(simpleNameContains)) return f;
            }
        }
        return null;
    }

    private static Field findFieldByAssignableType(Class<?> start, Class<?> assignableTo) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (assignableTo.isAssignableFrom(f.getType())) return f;
            }
        }
        return null;
    }
}
