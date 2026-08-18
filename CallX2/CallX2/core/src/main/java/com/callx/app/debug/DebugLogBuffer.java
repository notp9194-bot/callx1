package com.callx.app.debug;

import androidx.annotation.NonNull;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;

/**
 * TEMPORARY DEBUG UTILITY — in-memory ring buffer for the "ChatPagingDebug"
 * investigation (flicker/rebuild on send).
 *
 * Every {@link #d(String, String)} call still goes to Logcat exactly like a
 * normal android.util.Log.d() call, so `adb logcat -s ChatPagingDebug` keeps
 * working unchanged. On top of that it stores each line (timestamped) in a
 * small fixed-size buffer so the same lines can be viewed straight from the
 * phone — no computer/adb needed — via Chat ▸ ⋮ ▸ "🐞 Paging Debug Log".
 *
 * Safe to call from any thread. Remove this whole file (and its call sites
 * in ChatActivity / MessagePagingAdapter / MessageKeysetPagingSource) once
 * the flicker-on-send root cause is confirmed and fixed for good.
 */
public final class DebugLogBuffer {

    private static final int MAX_LINES = 2000;
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private static final ArrayDeque<String> lines = new ArrayDeque<>(MAX_LINES);

    private DebugLogBuffer() {}

    /** Logs to Logcat (same as android.util.Log.d) AND appends to the in-memory buffer. */
    public static void d(@NonNull String tag, @NonNull String msg) {
        android.util.Log.d(tag, msg);
        append(tag, msg);
    }

    private static synchronized void append(String tag, String msg) {
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
