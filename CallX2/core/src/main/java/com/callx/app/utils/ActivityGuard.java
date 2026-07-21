package com.callx.app.utils;

import android.app.Activity;

/**
 * Surfaces "silent finish()" bugs — an Activity that flashes open and
 * immediately closes because a guard-clause check failed (missing intent
 * extra, null user, etc). Normally these show NO error at all: finish()
 * just runs and the activity is gone, leaving no trace in logcat or on
 * screen, which makes them very hard to diagnose from a user's device.
 *
 * This routes the failure through the SAME on-device crash reporter that
 * CallxApp.onCreate() registers via Thread.setDefaultUncaughtExceptionHandler
 * (see CrashReportActivity) — so instead of a silent finish(), the person
 * sees the full "reason" string on the crash screen, copyable straight off
 * the device with no adb/logcat needed.
 *
 * Usage — replace a silent guard clause:
 *     if (communityId == null || currentUid == null) { finish(); return; }
 * with:
 *     if (communityId == null || currentUid == null) {
 *         ActivityGuard.reportAndFinish(this, "communityId=" + communityId
 *                 + ", currentUid=" + currentUid);
 *         return;
 *     }
 *
 * NOTE: this is a debugging aid, not user-facing UX — it kills the process
 * after showing the trace (same as any other uncaught crash), so only wire
 * it into guard clauses you're actively trying to diagnose, not into normal
 * finish() calls (back button, successful completion, etc).
 */
public class ActivityGuard {

    private ActivityGuard() {}

    public static void reportAndFinish(Activity activity, String reason) {
        String activityName = activity != null ? activity.getClass().getSimpleName() : "UnknownActivity";
        RuntimeException e = new IllegalStateException(
                "[" + activityName + "] guard clause failed — activity was about to finish() "
                        + "silently with no error shown. Reason: " + reason);

        Thread current = Thread.currentThread();
        Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
        if (handler != null) {
            // Routes into CallxApp's handler -> saves trace + launches
            // CrashReportActivity, same as any real uncaught crash.
            handler.uncaughtException(current, e);
        } else if (activity != null) {
            // Fallback: no handler registered (shouldn't happen since
            // CallxApp always sets one) — just finish so we don't leave a
            // half-initialized activity on screen.
            activity.finish();
        }
    }
}
