package com.callx.app.utils;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

/**
 * Bridge so feature modules can route a HANDLED exception through the exact
 * same "App Crashed" screen (CrashReportActivity, in the app module) that
 * CallxApp's uncaught-exception handler uses for real fatal crashes.
 *
 * WHY THIS EXISTS: several spots in the status/repost/reshare and sticker
 * code catch exceptions locally with `catch (Exception ignored)` so a bug
 * there doesn't hard-crash the whole viewer — but that also means the bug
 * leaves zero trace. From the user's side that looks exactly like a silent
 * crash (screen freezes/misbehaves/closes, nothing to copy-paste, nothing
 * in logcat they can get to). report() below writes the SAME
 * files/last_crash.txt CallxApp's handler writes, then opens the crash
 * screen with the trace pre-filled — copy-paste-able like a real crash —
 * but does NOT kill the process, since the caller already recovered.
 *
 * Feature modules (feature-status, feature-reels, ...) depend on core, but
 * core/feature modules do NOT depend on the app module, so
 * CrashReportActivity can't be referenced directly here — same reflection
 * pattern already used elsewhere in this codebase for cross-module launches
 * (see StatusViewerActivity#openOriginalContent,
 * StatusStickerPickerSheet#openMusicCreator).
 */
public final class CrashReporter {
    private static final String TAG = "CrashReporter";
    // Mirrors CrashReportActivity.CRASH_FILE_NAME / EXTRA_TRACE exactly —
    // keep in sync if either changes.
    private static final String CRASH_FILE_NAME = "last_crash.txt";
    private static final String EXTRA_TRACE = "extra_crash_trace";
    private static final String CRASH_ACTIVITY_CLASS = "com.callx.app.activities.CrashReportActivity";

    private CrashReporter() { /* no instances */ }

    /**
     * Report a handled exception. Shows the same crash screen a real fatal
     * crash would (with "Copy to Clipboard" / "Restart App"), but the app
     * process keeps running — the caller already handled/recovered from
     * the underlying failure, this just makes it visible instead of silent.
     *
     * @param ctx any Context
     * @param tag short label for where this happened, e.g.
     *            "StatusViewer.renderStickers", "StatusReshare.share" —
     *            shows at the top of the trace so it's obvious at a glance
     *            which code path failed.
     * @param t   the caught exception
     */
    public static void report(Context ctx, String tag, Throwable t) {
        if (ctx == null || t == null) return;
        try {
            String trace = buildTrace(tag, t);
            Log.e(TAG, trace);
            saveTraceToFile(ctx, trace);
            launchCrashScreen(ctx, trace);
        } catch (Throwable ignored) {
            // The reporter itself must never be the thing that crashes the app.
        }
    }

    /**
     * Like {@link #report}, but only logs + saves to file — does not open
     * the crash screen. Use for issues that are handled well enough that
     * interrupting the user isn't warranted, but still worth having a trace
     * for if they report a problem later.
     */
    public static void reportSilently(Context ctx, String tag, Throwable t) {
        if (ctx == null || t == null) return;
        try {
            String trace = buildTrace(tag, t);
            Log.e(TAG, trace);
            saveTraceToFile(ctx, trace);
        } catch (Throwable ignored) {
        }
    }

    private static String buildTrace(String tag, Throwable t) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("Tag: " + tag + " (handled — app kept running)");
        pw.println("Time: " + new Date());
        t.printStackTrace(pw);
        return sw.toString();
    }

    private static void saveTraceToFile(Context ctx, String trace) {
        try (FileOutputStream fos = new FileOutputStream(new File(ctx.getFilesDir(), CRASH_FILE_NAME))) {
            fos.write(trace.getBytes());
        } catch (Exception ignored) {
            // Best-effort only — the crash screen intent extra is the primary path.
        }
    }

    private static void launchCrashScreen(Context ctx, String trace) {
        try {
            Class<?> cls = Class.forName(CRASH_ACTIVITY_CLASS);
            Intent i = new Intent(ctx, cls);
            i.putExtra(EXTRA_TRACE, trace);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (ClassNotFoundException ignored) {
            // app module not on the classpath for whatever's calling this
            // (shouldn't happen in the real app) — trace is still on disk.
        }
    }
}
