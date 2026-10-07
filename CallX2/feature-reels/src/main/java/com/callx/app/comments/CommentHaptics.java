package com.callx.app.comments;

import android.os.Build;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Tiny haptic vocabulary for the reel comment UI, so every call site says
 * WHAT happened (confirm / tick / reject) and the API-level fallbacks live in
 * exactly one place.
 *
 *   confirm → a like landed, a reaction was picked, a comment/reply was accepted
 *   tick    → light feedback: un-like, reaction removed, retry tapped
 *   reject  → something failed or was refused (send failed, like rolled back,
 *             "commenting too fast", paging/replies load failed)
 *
 * Uses View#performHapticFeedback, so the user's system "touch feedback"
 * setting is respected automatically (no VIBRATE permission needed). Never
 * throws - a haptic must never be able to break the interaction it decorates.
 */
final class CommentHaptics {

    private CommentHaptics() {}

    static void confirm(@Nullable View v) {
        fire(v, Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ? HapticFeedbackConstants.CONFIRM
            : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    static void tick(@Nullable View v) {
        fire(v, HapticFeedbackConstants.CLOCK_TICK);
    }

    static void reject(@Nullable View v) {
        fire(v, Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ? HapticFeedbackConstants.REJECT
            : HapticFeedbackConstants.LONG_PRESS);
    }

    /** Like toggled: confirm when it became liked, light tick when un-liked. */
    static void like(@Nullable View v, boolean nowLiked) {
        if (nowLiked) confirm(v); else tick(v);
    }

    private static void fire(@Nullable View v, int constant) {
        if (v == null) return;
        try { v.performHapticFeedback(constant); } catch (Exception ignored) {}
    }
}
