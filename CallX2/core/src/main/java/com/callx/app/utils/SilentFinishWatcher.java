package com.callx.app.utils;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;

/**
 * App-wide version of ActivityGuard: instead of only catching finish()
 * calls that were manually wired one-by-one, this watches EVERY activity
 * in the app and auto-reports any that finish abnormally fast (flash-open-
 * then-close), the same way CallxApp's uncaught exception handler covers
 * every crash app-wide without each Activity opting in.
 *
 * Register once, in CallxApp.onCreate():
 *   registerActivityLifecycleCallbacks(new SilentFinishWatcher());
 *
 * Heuristic (best-effort, not a perfect signal — finish() is also a normal,
 * legitimate call e.g. back button, successful completion, redirects):
 *   - isFinishing() == true (not a config-change recreation)
 *   - onResume() was never reached (user never actually saw/used the
 *     screen — if they had, they'd have to press back themselves, which
 *     is a user-initiated close, not the app closing itself)
 * Only when BOTH hold do we treat it as "the app closed this on its own"
 * and route it through the same on-device crash dialog as a real exception.
 * No time window: a guard clause that fails after a slow async check (e.g.
 * a Firebase lookup) should still be caught even if it takes a few seconds.
 */
public class SilentFinishWatcher implements Application.ActivityLifecycleCallbacks {

    private final Map<String, Boolean> resumed = new HashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private String key(Activity a) {
        return a.getClass().getName() + "@" + System.identityHashCode(a);
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        resumed.put(key(activity), false);
    }

    @Override public void onActivityResumed(Activity activity) {
        resumed.put(key(activity), true);
    }

    @Override public void onActivityDestroyed(Activity activity) {
        String k = key(activity);
        Boolean wasResumed = resumed.remove(k);
        boolean neverResumed = wasResumed == null || !wasResumed;

        if (activity.isFinishing() && !activity.isChangingConfigurations() && neverResumed) {
            String reason = activity.getClass().getSimpleName()
                    + " closed itself before the user ever saw it (finished without"
                    + " reaching onResume()) — this was NOT a user-initiated back/close."
                    + " Likely a silent guard-clause finish() with no error shown.";
            // Post to main looper's next iteration so this activity's own
            // destroy() call finishes cleanly before we route into the
            // crash handler (which kills the process).
            main.post(() -> {
                Thread current = Thread.currentThread();
                Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
                if (handler != null) {
                    handler.uncaughtException(current, new IllegalStateException(reason));
                }
            });
        }
    }

    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
}
