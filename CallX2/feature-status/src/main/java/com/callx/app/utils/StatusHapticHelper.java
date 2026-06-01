package com.callx.app.utils;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** StatusHapticHelper v26 — Custom haptic feedback patterns for status interactions. */
public final class StatusHapticHelper {
    private StatusHapticHelper() {}

    /** Light click — reaction tap, story advance */
    public static void light(Context ctx) { vibrate(ctx, 30, 100); }
    /** Medium click — story dismiss, archive */
    public static void medium(Context ctx) { vibrate(ctx, 50, 180); }
    /** Heavy — delete, error */
    public static void heavy(Context ctx) { vibrate(ctx, 80, 255); }
    /** Success pattern — status posted */
    public static void success(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            long[] pattern = {0, 30, 50, 30}; int[] amp = {0, 80, 0, 120};
            getVibrator(ctx).vibrate(VibrationEffect.createWaveform(pattern, amp, -1));
        } else {
            getVibrator(ctx).vibrate(new long[]{0, 30, 50, 30}, -1);
        }
    }
    /** Reaction selected */
    public static void reaction(View v) {
        if (v != null) v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }
    private static void vibrate(Context ctx, long ms, int amp) {
        Vibrator vib = getVibrator(ctx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(ms, amp));
        } else { vib.vibrate(ms); }
    }
    private static Vibrator getVibrator(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return vm.getDefaultVibrator();
        }
        return (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
