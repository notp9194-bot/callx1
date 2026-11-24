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

    // --- v835c: per-key accumulators (only while collecting == true) ---
    public static volatile boolean collecting = false;
    private static final java.util.LinkedHashMap<String, long[]> accs = new java.util.LinkedHashMap<>();

    public static synchronized void begin() { accs.clear(); collecting = true; }

    public static synchronized void acc(String key, long nanos) {
        if (!collecting) return;
        long[] a = accs.get(key);
        if (a == null) { a = new long[2]; accs.put(key, a); }
        a[0] += nanos; a[1]++;
    }

    public static synchronized void flushAccs() {
        collecting = false;
        for (java.util.Map.Entry<String, long[]> e : accs.entrySet()) {
            add("   acc " + e.getKey() + ": " + (e.getValue()[0] / 1000L) + " µs / "
                    + e.getValue()[1] + " calls");
        }
        accs.clear();
    }

    public static synchronized String getAll() { return sb.toString(); }

    public static synchronized void clear() { sb.setLength(0); }
}
