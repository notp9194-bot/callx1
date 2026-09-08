package com.callx.app.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;

import androidx.core.content.ContextCompat;

/**
 * Shared semantic styling for follow actions.
 *
 * Follow buttons intentionally do not use brand_primary: brand_primary is
 * still used by app chrome and unrelated actions. Keeping the pressed state
 * here also makes programmatically-created buttons behave like XML buttons.
 */
public final class FollowButtonStyler {

    private FollowButtonStyler() {}

    public static int primaryColor(Context context) {
        return ContextCompat.getColor(context, com.callx.app.core.R.color.follow_button_primary);
    }

    public static int textColor(Context context) {
        return ContextCompat.getColor(context, com.callx.app.core.R.color.follow_button_text);
    }

    /** Returns the light/dark-aware primary + pressed tint list. */
    public static ColorStateList primaryStateList(Context context) {
        int pressed = ContextCompat.getColor(
                context, com.callx.app.core.R.color.follow_button_pressed);
        int primary = primaryColor(context);
        return new ColorStateList(
                new int[][] {
                        new int[] { android.R.attr.state_pressed },
                        new int[] {}
                },
                new int[] { pressed, primary });
    }

    /** Applies the follow action tint without replacing the button shape. */
    public static void applyPrimaryTint(View view) {
        if (view == null) return;
        view.setBackgroundTintList(primaryStateList(view.getContext()));
    }
}