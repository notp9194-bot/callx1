package com.callx.app.models;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YouTubeChapter {
    public String title;
    public long   startMs;   // milliseconds

    public YouTubeChapter(String title, long startMs) {
        this.title   = title;
        this.startMs = startMs;
    }

    /** Parse "0:00 Intro\n2:30 Main topic\n5:45 Outro" style descriptions. */
    public static List<YouTubeChapter> parseFromDescription(String desc) {
        List<YouTubeChapter> list = new ArrayList<>();
        if (desc == null || desc.isEmpty()) return list;
        Pattern p = Pattern.compile("(?m)^(\\d{1,2}:\\d{2}(?::\\d{2})?)\\s+(.+)$");
        Matcher m = p.matcher(desc);
        while (m.find()) {
            String ts   = m.group(1);
            String name = m.group(2).trim();
            list.add(new YouTubeChapter(name, parseTimestamp(ts)));
        }
        return list;
    }

    private static long parseTimestamp(String ts) {
        String[] parts = ts.split(":");
        long result = 0;
        for (String part : parts) result = result * 60 + Long.parseLong(part);
        return result * 1000L;
    }

    public String getFormattedTime() {
        long secs = startMs / 1000;
        long m    = secs / 60;
        long s    = secs % 60;
        if (m >= 60) return (m / 60) + ":" + pad(m % 60) + ":" + pad(s);
        return m + ":" + pad(s);
    }
    private String pad(long n) { return n < 10 ? "0" + n : String.valueOf(n); }
}
