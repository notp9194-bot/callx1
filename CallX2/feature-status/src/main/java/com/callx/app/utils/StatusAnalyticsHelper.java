package com.callx.app.utils;

import com.callx.app.models.StatusItem;
import com.google.firebase.database.*;
import java.util.*;

/** StatusAnalyticsHelper v26 — FIX: unique viewers properly counted. */
public final class StatusAnalyticsHelper {
    private StatusAnalyticsHelper() {}

    public static long recordViewStart() { return System.currentTimeMillis(); }

    public static void recordViewEnd(String ownerUid, String statusId, String viewerUid, long startTime) {
        if (ownerUid == null || statusId == null || viewerUid == null) return;
        if (ownerUid.equals(viewerUid)) return;
        long duration = System.currentTimeMillis() - startTime;
        if (duration < 200) return;
        Map<String, Object> data = new HashMap<>();
        data.put("viewerUid", viewerUid); data.put("duration", duration);
        data.put("timestamp", ServerValue.TIMESTAMP);
        FirebaseUtils.db().getReference("statusAnalytics").child(ownerUid).child(statusId)
            .child("viewSessions").push().setValue(data);
        FirebaseUtils.getStatusRef().child(ownerUid).child(statusId)
            .child("viewDurations").child(viewerUid).setValue(duration);
    }

    public static class Analytics {
        public int    totalViews;        // total view events (includes repeats)
        public int    uniqueViewers;     // FIX: seenBy.size() = real unique viewers
        public double avgViewDurationSec;
        public int    totalReactions;
        public Map<String, Integer> reactionBreakdown = new LinkedHashMap<>();
        public double reachPercent;

        public String getSummary() {
            return uniqueViewers + " unique viewers  ·  " + totalViews + " total views\n"
                 + totalReactions + " reactions  ·  "
                 + String.format("%.1f", avgViewDurationSec) + "s avg view time";
        }
    }

    /** FIX: uniqueViewers = seenBy.size() (actual unique), totalViews may count viewSessions */
    public static Analytics compute(StatusItem item, int totalContacts) {
        Analytics a = new Analytics();
        // FIX: seenBy holds one entry per viewer — true unique count
        a.uniqueViewers     = item.seenBy != null ? item.seenBy.size() : 0;
        // viewDurations also unique per viewer
        a.totalViews        = item.viewDurations != null ? item.viewDurations.size() : a.uniqueViewers;
        a.avgViewDurationSec = item.getAvgViewDurationSec();
        a.totalReactions    = 0;
        if (item.reactions != null) {
            for (String e : item.reactions.values()) {
                a.reactionBreakdown.merge(e, 1, Integer::sum);
                a.totalReactions++;
            }
        }
        a.reachPercent = totalContacts > 0 ? (a.uniqueViewers * 100.0 / totalContacts) : 0;
        return a;
    }
}
