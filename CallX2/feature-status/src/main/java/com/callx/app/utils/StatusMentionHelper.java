package com.callx.app.utils;

import android.graphics.Color;
import android.text.*;
import android.text.style.ForegroundColorSpan;
import com.google.firebase.database.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/** StatusMentionHelper v26 — FIX: UID resolution from username lookup. */
public final class StatusMentionHelper {
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([\\w.]+)");
    private static final int MENTION_COLOR = Color.parseColor("#1DA1F2");
    private StatusMentionHelper() {}

    public interface MentionResolutionCallback {
        void onResolved(Map<String, String> usernameToUid); // username → uid
    }

    public static List<String> extractNames(String text) {
        List<String> names = new ArrayList<>();
        if (text == null) return names;
        Matcher m = MENTION_PATTERN.matcher(text);
        while (m.find()) { String n = m.group(1); if (n != null && !names.contains(n)) names.add(n); }
        return names;
    }

    public static boolean hasMention(String text) {
        return text != null && MENTION_PATTERN.matcher(text).find();
    }

    public static SpannableString highlight(String text) {
        if (text == null) return new SpannableString("");
        SpannableString ss = new SpannableString(text);
        Matcher m = MENTION_PATTERN.matcher(text);
        while (m.find()) ss.setSpan(new ForegroundColorSpan(MENTION_COLOR),
                m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    /** FIX: Resolve @username → UID via Firebase users node (username field lookup) */
    public static void resolveUids(List<String> usernames, MentionResolutionCallback cb) {
        if (usernames == null || usernames.isEmpty()) { if (cb != null) cb.onResolved(new HashMap<>()); return; }
        Map<String, String> result = new HashMap<>();
        AtomicInteger remaining = new AtomicInteger(usernames.size());
        for (String username : usernames) {
            FirebaseUtils.db().getReference("users")
                .orderByChild("username").equalTo(username).limitToFirst(1)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        for (DataSnapshot c : snap.getChildren()) {
                            if (c.getKey() != null) result.put(username, c.getKey());
                        }
                        if (remaining.decrementAndGet() == 0 && cb != null) cb.onResolved(result);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        if (remaining.decrementAndGet() == 0 && cb != null) cb.onResolved(result);
                    }
                });
        }
    }

    public static void notifyMentions(String ownerUid, String ownerName,
                                       String statusId, Map<String, String> usernameToUid) {
        if (usernameToUid == null || usernameToUid.isEmpty()) return;
        for (String uid : usernameToUid.values()) {
            if (uid == null || uid.equals(ownerUid)) continue;
            Map<String, Object> n = new HashMap<>();
            n.put("type","status_mention"); n.put("fromUid",ownerUid);
            n.put("fromName",ownerName); n.put("statusId",statusId);
            n.put("timestamp", ServerValue.TIMESTAMP);
            FirebaseUtils.db().getReference("notifications").child(uid).push().setValue(n);
        }
    }
}
