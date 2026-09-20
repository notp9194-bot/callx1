package com.callx.app.debug;

import androidx.annotation.NonNull;
import com.callx.app.core.BuildConfig;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Debug-only ring buffer for the chat paging investigation.
 *
 * Release call sites are compile-time gated and this class also short-circuits
 * defensively, so release builds do not format timestamps, write Logcat, or
 * retain a 2,000-line buffer.
 */
public final class DebugLogBuffer {

    private static final int MAX_LINES = 2000;
    private static final SimpleDateFormat TIME_FMT = BuildConfig.DEBUG
            ? new SimpleDateFormat("HH:mm:ss.SSS", Locale.US) : null;

    private static final ArrayDeque<String> lines =
            new ArrayDeque<>(BuildConfig.DEBUG ? MAX_LINES : 1);

    private DebugLogBuffer() {}

    /** Logs to Logcat (same as android.util.Log.d) AND appends to the in-memory buffer. */
    public static void d(@NonNull String tag, @NonNull String msg) {
        if (!BuildConfig.DEBUG) return;
        android.util.Log.d(tag, msg);
        append(tag, msg);
    }

    private static synchronized void append(String tag, String msg) {
        if (!BuildConfig.DEBUG) return;
        String line;
        synchronized (TIME_FMT) {
            line = TIME_FMT.format(new java.util.Date()) + "  " + tag + "  " + msg;
        }
        if (lines.size() >= MAX_LINES) {
            lines.pollFirst();
        }
        lines.addLast(line);
    }

    /** Returns a snapshot of every buffered line, oldest first. */
    @NonNull
    public static synchronized String getAll() {
        if (lines.isEmpty()) return "(no ChatPagingDebug logs captured yet — send a message)";
        return String.join("\n", new ArrayList<>(lines));
    }

    /** Number of lines currently buffered. */
    public static synchronized int size() {
        return lines.size();
    }

    public static synchronized void clear() {
        lines.clear();
    }
}
