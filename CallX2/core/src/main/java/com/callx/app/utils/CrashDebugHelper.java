package com.callx.app.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * DEBUG-only diagnostics for tracking down the reaction-picker crash.
 *
 * Two different failure classes need two different capture strategies:
 *
 * 1. Plain Java exceptions/errors — catchable with try/catch in the exact
 *    place they happen. See {@link #uncaughtHandlerTrace} for the ones that
 *    somehow still escape to the top (shouldn't happen if call sites are
 *    wrapped properly, but this is the safety net).
 *
 * 2. Native crashes (SIGSEGV/SIGABRT from the RLottie C++ decoder) — these
 *    are NOT Java exceptions. No try/catch anywhere in Java code can ever
 *    catch them; the OS kills the whole process immediately, so there is no
 *    way to show an AlertDialog "at the moment" it happens. The only way to
 *    get any information out is a breadcrumb: write to disk exactly what
 *    we're about to do *right before* the risky native call, using a
 *    synchronous commit() (not apply()) so it's guaranteed durable even if
 *    the process dies a few milliseconds later, then erase it right after
 *    the call returns successfully. If the app restarts and finds a
 *    breadcrumb that was never erased, that's proof positive of exactly
 *    which native call killed the process last time.
 */
public final class CrashDebugHelper {

    private static final String PREFS = "crash_debug_prefs";
    private static final String KEY_LOTTIE_BREADCRUMB = "pending_lottie_native_call";
    private static final String KEY_UNCAUGHT_TRACE = "last_uncaught_java_trace";

    private CrashDebugHelper() {}

    // ── Native-call breadcrumb (for the reaction-picker RLottie crash) ────

    /** Call immediately BEFORE any RLottieViewWrapper.loadFromFile(). */
    public static void markLottieLoadStarting(Context ctx, String reactionId, java.io.File file) {
        try {
            String detail = "reactionId=" + reactionId
                    + "\nfile=" + (file != null ? file.getAbsolutePath() : "null")
                    + "\nsizeBytes=" + (file != null ? file.length() : -1)
                    + "\nthread=" + Thread.currentThread().getName()
                    + "\ntimestamp=" + System.currentTimeMillis();
            prefs(ctx).edit().putString(KEY_LOTTIE_BREADCRUMB, detail).commit(); // sync — must land before native call
        } catch (Throwable ignored) {}
    }

    /** Call immediately AFTER loadFromFile() returns (success OR handled failure). */
    public static void clearLottieLoadMarker(Context ctx) {
        try {
            prefs(ctx).edit().remove(KEY_LOTTIE_BREADCRUMB).commit();
        } catch (Throwable ignored) {}
    }

    // ── Global uncaught-exception safety net ───────────────────────────

    public static void installUncaughtHandler(Context appCtx) {
        final Context ctx = appCtx.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, t) -> {
            try {
                String trace = "Thread: " + thread.getName() + "\n\n" + Log.getStackTraceString(t);
                prefs(ctx).edit().putString(KEY_UNCAUGHT_TRACE, trace).commit();
            } catch (Throwable ignored) {}
            if (prev != null) prev.uncaughtException(thread, t); // preserve normal crash/kill behavior
        });
    }

    /**
     * Call once at app startup (after {@link #installUncaughtHandler}).
     * Returns a combined debug report if either capture mechanism found
     * something left over from last run, or null if the app exited clean.
     * Clears whatever it finds — shown once only.
     */
    public static String consumePendingCrashReport(Context ctx) {
        SharedPreferences p = prefs(ctx);
        StringBuilder sb = new StringBuilder();

        String lottieCrumb = p.getString(KEY_LOTTIE_BREADCRUMB, null);
        if (lottieCrumb != null) {
            sb.append("⚠️ NATIVE CRASH DETECTED (RLottie)\n")
              .append("App was killed mid-way through loading an animated\n")
              .append("reaction and never returned — this is a native\n")
              .append("SIGSEGV, not a Java exception, so no stack trace is\n")
              .append("possible. Below is exactly what was being loaded when\n")
              .append("it happened:\n\n")
              .append(lottieCrumb)
              .append("\n\n");
        }

        String javaTrace = p.getString(KEY_UNCAUGHT_TRACE, null);
        if (javaTrace != null) {
            sb.append("⚠️ UNCAUGHT JAVA EXCEPTION\n\n").append(javaTrace);
        }

        if (lottieCrumb == null && javaTrace == null) return null;

        p.edit().remove(KEY_LOTTIE_BREADCRUMB).remove(KEY_UNCAUGHT_TRACE).apply();
        return sb.toString();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
