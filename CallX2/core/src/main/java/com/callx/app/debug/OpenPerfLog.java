package com.callx.app.debug;

/**
 * v835: always-on (debug AND release) tiny in-memory timing log for the
 * chat-open path. Shown at the top of the 3-dot menu → "Paging Debug Log".
 * Cost per mark: one nanoTime read + one short string append.
 */
public final class OpenPerfLog {
    private static final int MAX_CHARS = 8000;
    private static final StringBuilder sb = new StringBuilder();

    private OpenPerfLog() {}

    public static synchronized void add(String line) {
        android.util.Log.d("OpenPerf", line);
        sb.append(line).append('\n');
        if (sb.length() > MAX_CHARS) sb.delete(0, sb.length() - MAX_CHARS);
    }

    public static synchronized String getAll() { return sb.toString(); }

    public static synchronized void clear() { sb.setLength(0); }
}
