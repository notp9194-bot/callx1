package com.callx.app.debug;

/**
 * v843: always-on timing log for the Chats-TAB cold open (process start -> first frame).
 * Same idea as OpenPerfLog (chat screen). Shown in the Chats tab 3-dot menu -> "Chats Open Log".
 * Times are ms since process start (falls back to first mark on API < 24).
 * Cost per mark: one clock read + one short string append.
 */
public final class TabOpenPerfLog {
    private static final int MAX_CHARS = 8000;
    private static final StringBuilder sb = new StringBuilder();
    private static volatile long t0 = 0L;
    private static final java.util.HashSet<String> once = new java.util.HashSet<>();

    private TabOpenPerfLog() {}

    private static long base() {
        long b = t0;
        if (b == 0L) {
            b = android.os.SystemClock.elapsedRealtime();
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                b = android.os.Process.getStartElapsedRealtime();
            }
            t0 = b;
        }
        return b;
    }

    public static synchronized void mark(String label) {
        long ms = android.os.SystemClock.elapsedRealtime() - base();
        String line = label + " = " + ms + " ms";
        android.util.Log.d("TabOpenPerf", line);
        sb.append(line).append('\n');
        if (sb.length() > MAX_CHARS) sb.delete(0, sb.length() - MAX_CHARS);
    }

    /** Marks only the first time per process (e.g. "first data"). */
    public static synchronized void markOnce(String label) {
        if (once.add(label)) mark(label);
    }

    public static synchronized String getAll() { return sb.toString(); }

    public static synchronized void clear() { sb.setLength(0); once.clear(); }
}
