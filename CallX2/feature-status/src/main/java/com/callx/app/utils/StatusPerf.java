package com.callx.app.utils;

import android.os.Trace;

/**
 * Thin wrapper over {@link android.os.Trace} for Status hot paths. Sections show up in Perfetto /
 * Android Studio profiler and can be asserted by macrobenchmarks (TraceSectionMetric). Cost when no
 * trace is recording is a cheap native check. Always pair begin/end in try/finally.
 *
 * Sections: StatusTab#create, StatusTab#start, StatusTab#rebuild, StatusCompose#renderCanvas,
 * StatusCompose#prepareUpload, StatusCompose#addPhotoLayer (+ the existing StatusViewer#open).
 */
public final class StatusPerf {
    private StatusPerf() {}
    public static void begin(String section) { try { Trace.beginSection(section); } catch (Throwable ignored) {} }
    public static void end() { try { Trace.endSection(); } catch (Throwable ignored) {} }
}
