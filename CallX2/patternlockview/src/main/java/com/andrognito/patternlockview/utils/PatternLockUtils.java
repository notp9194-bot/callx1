package com.andrognito.patternlockview.utils;

import com.andrognito.patternlockview.PatternLockView;
import java.util.List;

public class PatternLockUtils {

    private PatternLockUtils() {}

    public static String patternToString(PatternLockView patternLockView, List<PatternLockView.Dot> pattern) {
        if (pattern == null) return "";
        int patternSize = patternLockView.getPatternSize();
        StringBuilder sb = new StringBuilder();
        for (PatternLockView.Dot dot : pattern) {
            sb.append(dot.getRow() * patternSize + dot.getColumn());
        }
        return sb.toString();
    }

    public static String patternToSha1String(PatternLockView patternLockView, List<PatternLockView.Dot> pattern) {
        try {
            String patternStr = patternToString(patternLockView, pattern);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(patternStr.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
