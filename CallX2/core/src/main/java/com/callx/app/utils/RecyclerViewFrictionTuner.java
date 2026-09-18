package com.callx.app.utils;

import android.app.ActivityManager;
import android.content.Context;

import androidx.recyclerview.widget.RecyclerView;

import java.lang.reflect.Field;

/**
 * Shared, reusable version of the reflection trick that {@code
 * FastFlingRecyclerView} (feature-chat) uses to lower a RecyclerView's
 * internal OverScroller friction. The default is deliberately device-aware:
 * long-glide tuning is useful on a capable device, but a fixed low friction
 * value can keep work, decodes, and battery usage high on low-RAM phones.
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

    /**
     * Legacy explicit value kept for callers that intentionally request the
     * old profile. Default callers use {@link #recommendedFriction(Context)}.
     */
    public static final float TELEGRAM_FRICTION = 0.007f;

    // Conservative profile values. Unknown devices use the middle profile,
    // never the most aggressive long-glide setting.
    private static final float LOW_RAM_FRICTION = 0.013f;
    private static final float MID_RAM_FRICTION = 0.011f;
    private static final float HIGH_RAM_FRICTION = 0.010f;

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

    /** Convenience overload using the conservative device-aware profile. */
    public static boolean applyReducedFriction(RecyclerView rv) {
        return applyReducedFriction(rv, recommendedFriction(rv != null ? rv.getContext() : null));
    }

    /**
     * Returns a conservative fling profile based only on stable platform
     * memory signals. This is intentionally closer to stock than the old
     * fixed 0.007f setting when the device cannot afford a long glide.
     */
    public static float recommendedFriction(Context context) {
        if (context == null) return MID_RAM_FRICTION;
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null && manager.isLowRamDevice()) return LOW_RAM_FRICTION;
            int memoryClassMb = manager != null ? manager.getMemoryClass() : 0;
            if (memoryClassMb > 0 && memoryClassMb <= 192) return LOW_RAM_FRICTION;
            if (memoryClassMb >= 384) return HIGH_RAM_FRICTION;
        } catch (Exception ignored) {
            // Unknown profile: keep the conservative middle value.
        }
        return MID_RAM_FRICTION;
    }

    /** Diminishing velocity boost paired with {@link #recommendedFriction}. */
    public static float recommendedBoostGain(Context context) {
        if (context == null) return 0.25f;
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null && manager.isLowRamDevice()) return 0.12f;
            int memoryClassMb = manager != null ? manager.getMemoryClass() : 0;
            if (memoryClassMb > 0 && memoryClassMb <= 192) return 0.16f;
            if (memoryClassMb >= 384) return 0.30f;
        } catch (Exception ignored) {
            // Unknown profile: use the conservative middle boost.
        }
        return 0.22f;
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
