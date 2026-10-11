package com.callx.app.utils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * StatusMentionHelper — @mention parsing and highlighting for status text.
 * Detects @username patterns, resolves UIDs, and highlights in text.
 */
public final class StatusMentionHelper {
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([\\w.]+)");
    private static final int MENTION_COLOR = Color.parseColor("#1DA1F2");
    private StatusMentionHelper() {}
    public static class MentionResult {
        public List<String> mentionedNames = new ArrayList<>();
        public Map<String, String> mentionNames = new HashMap<>(); // uid → name (filled after resolution)
        public String cleanText;
    }
    /** Extract @mention names from text. */
    public static MentionResult extract(String text) {
        MentionResult r = new MentionResult();
        r.cleanText = text;
        if (text == null) return r;
        Matcher m = MENTION_PATTERN.matcher(text);
        while (m.find()) {
            String name = m.group(1);
            if (name != null && !r.mentionedNames.contains(name)) {
                r.mentionedNames.add(name);
            }
        }
        return r;
    }
    public static boolean hasMention(String text) {
        if (text == null) return false;
        return MENTION_PATTERN.matcher(text).find();
    }
    /** Highlight @mentions in a SpannableString with blue color. */
    public static SpannableString highlight(String text) {
        if (text == null) return new SpannableString("");
        SpannableString ss = new SpannableString(text);
        if (text.indexOf('@') < 0) return ss; // v509: no '@' → no regex pass at all
        final int[] ranges = mentionRanges(text);
        for (int i = 0; i < ranges.length; i += 2) {
            ss.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    ranges[i], ranges[i + 1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }

    /**
     * v509 — display-path variant for the status viewer (runs on every text status / caption).
     * No '@' (the vast majority) → returns the String itself: no SpannableString allocation and
     * TextView skips span handling. With mentions → a fresh SpannableString built from cached
     * match ranges, so the regex runs once per distinct text. A new Spannable per call on purpose
     * (a Spannable shared between two TextViews gets its change-watchers stolen by the other).
     */
    public static CharSequence highlightFast(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.indexOf('@') < 0) return text;
        return highlight(text);
    }

    // [start0,end0,start1,end1,...] per distinct text; tiny, bounded.
    private static final android.util.LruCache<String, int[]> RANGE_CACHE =
            new android.util.LruCache<>(48);
    private static final int[] NO_RANGES = new int[0];
    private static int[] mentionRanges(String text) {
        int[] hit = RANGE_CACHE.get(text);
        if (hit != null) return hit;
        List<Integer> pos = new ArrayList<>();
        Matcher m = MENTION_PATTERN.matcher(text);
        while (m.find()) { pos.add(m.start()); pos.add(m.end()); }
        int[] out = NO_RANGES;
        if (!pos.isEmpty()) {
            out = new int[pos.size()];
            for (int i = 0; i < out.length; i++) out[i] = pos.get(i);
        }
        RANGE_CACHE.put(text, out);
        return out;
    }
    /** Notify mentioned users via Firebase (fire-and-forget). */
    public static void notifyMentions(String ownerUid, String ownerName,
                                       String statusId, List<String> mentionedUids) {
        if (mentionedUids == null || mentionedUids.isEmpty()) return;
        for (String uid : mentionedUids) {
            if (uid == null || uid.equals(ownerUid)) continue;
            java.util.Map<String, Object> notif = new java.util.HashMap<>();
            notif.put("type",      "status_mention");
            notif.put("fromUid",   ownerUid);
            notif.put("fromName",  ownerName);
            notif.put("statusId",  statusId);
            notif.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
            FirebaseUtils.db()
                .getReference("notifications")
                .child(uid)
                .push()
                .setValue(notif);
        }
    }
}