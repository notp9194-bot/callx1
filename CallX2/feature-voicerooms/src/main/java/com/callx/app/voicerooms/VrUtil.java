package com.callx.app.voicerooms;

import android.content.Context;

import java.util.Locale;

/** Chhoti helpers — dp conversion + "1.2K" style counts. */
final class VrUtil {
    private VrUtil() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 2400 -> "2.4K", 1200 -> "1.2K", 12000 -> "12K", 950 -> "950". */
    static String compact(int n) {
        if (n >= 1_000_000) {
            return String.format(Locale.US, "%.1fM", n / 1_000_000f);
        }
        if (n >= 1000) {
            float k = n / 1000f;
            boolean whole = k >= 10f || Math.abs(k - Math.round(k)) < 0.05f;
            String s = whole ? String.valueOf(Math.round(k))
                             : String.format(Locale.US, "%.1f", k);
            return s + "K";
        }
        return String.valueOf(n);
    }
}
