package com.callx.app.utils;
/**
 * StatusCustomExpiryHelper — Custom expiry durations for statuses.
 * Options: 1h / 3h / 6h / 12h / 24h / 48h / 72h
 */
public final class StatusCustomExpiryHelper {
    public static final int[] EXPIRY_HOURS   = {1, 3, 6, 12, 24, 48, 72};
    public static final String[] EXPIRY_LABELS = {"1 hour", "3 hours", "6 hours", "12 hours", "24 hours (Default)", "2 days", "3 days"};
    public static final int DEFAULT_INDEX = 4; // 24 hours
    private StatusCustomExpiryHelper() {}
    public static long computeExpiresAt(int hours) {
        return System.currentTimeMillis() + (long) hours * 3_600_000L;
    }
    public static String labelFor(int hours) {
        for (int i = 0; i < EXPIRY_HOURS.length; i++) {
            if (EXPIRY_HOURS[i] == hours) return EXPIRY_LABELS[i];
        }
        return hours + " hours";
    }
    public static int[] getHoursOptions() { return EXPIRY_HOURS; }
    public static String[] getLabelOptions() { return EXPIRY_LABELS; }
}