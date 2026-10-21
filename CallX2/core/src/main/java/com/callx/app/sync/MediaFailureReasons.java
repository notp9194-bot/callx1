package com.callx.app.sync;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Why an outgoing media upload failed, so the bubble's retry pill can say more than "Tap to retry".
 * In-memory only (a process restart falls back to the generic label) — no schema change needed.
 */
public final class MediaFailureReasons {
    public static final int UNKNOWN = 0, NETWORK = 1, TOO_LARGE = 2, FILE_MISSING = 3;
    private static final ConcurrentHashMap<String, Integer> REASONS = new ConcurrentHashMap<>();

    private MediaFailureReasons() {}

    public static void set(String messageId, int reason) {
        if (messageId == null) return;
        if (reason == UNKNOWN) REASONS.remove(messageId); else REASONS.put(messageId, reason);
    }

    public static int get(String messageId) {
        if (messageId == null) return UNKNOWN;
        Integer r = REASONS.get(messageId);
        return r != null ? r : UNKNOWN;
    }

    public static void clear(String messageId) { if (messageId != null) REASONS.remove(messageId); }

    /** Best-effort mapping of an uploader/exception message to a reason. */
    public static int classify(String error) {
        if (error == null) return UNKNOWN;
        String e = error.toLowerCase(Locale.ROOT);
        if (e.contains("too large") || e.contains("413") || e.contains("exceeds") || e.contains("file size"))
            return TOO_LARGE;
        if (e.contains("no such file") || e.contains("not found") || e.contains("enoent") || e.contains("file missing"))
            return FILE_MISSING;
        if (e.contains("unable to resolve") || e.contains("timeout") || e.contains("timed out")
                || e.contains("network") || e.contains("connect") || e.contains("socket")
                || e.contains("offline") || e.contains("no internet") || e.contains("did not complete"))
            return NETWORK;
        return UNKNOWN;
    }
}
