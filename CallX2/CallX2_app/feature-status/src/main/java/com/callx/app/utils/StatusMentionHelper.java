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
        Matcher m = MENTION_PATTERN.matcher(text);
        while (m.find()) {
            ss.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                    m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
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