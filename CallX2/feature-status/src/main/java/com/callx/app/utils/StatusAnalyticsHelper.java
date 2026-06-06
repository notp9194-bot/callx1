package com.callx.app.utils;
import com.callx.app.models.StatusItem;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
/**
 * StatusAnalyticsHelper — Track and compute status reach analytics.
 * Tracks: view count, unique viewers, average view duration, reaction breakdown.
 * Firebase path: statusAnalytics/{ownerUid}/{statusId}
 */
public final class StatusAnalyticsHelper {
    private StatusAnalyticsHelper() {}
    /** Record view start — call when status becomes visible. Returns session start time. */
    public static long recordViewStart() {
        return System.currentTimeMillis();
    }
    /** Record view end — call when viewer moves to next or exits. */
    public static void recordViewEnd(String ownerUid, String statusId,
                                     String viewerUid, long startTime) {
        if (ownerUid == null || statusId == null || viewerUid == null) return;
        if (ownerUid.equals(viewerUid)) return; // Don't count own views
        long duration = System.currentTimeMillis() - startTime;
        if (duration < 200) return; // Ignore accidental taps < 200ms
        Map<String, Object> data = new HashMap<>();
        data.put("viewerUid", viewerUid);
        data.put("duration",  duration);
        data.put("timestamp", ServerValue.TIMESTAMP);
        getRef(ownerUid, statusId).child("viewSessions").push().setValue(data);
        // Update viewDurations on status node
        FirebaseUtils.getStatusRef()
            .child(ownerUid)
            .child(statusId)
            .child("viewDurations")
            .child(viewerUid)
            .setValue(duration);
    }
    /** Compute analytics summary from a StatusItem. */
    public static class Analytics {
        public int     totalViews;
        public int     uniqueViewers;
        public double  avgViewDurationSec;
        public int     totalReactions;
        public Map<String, Integer> reactionBreakdown;
        public double  reachPercent;   // viewers / contacts * 100
        public String getSummary() {
            return totalViews + " views · " + totalReactions + " reactions · "
                    + String.format("%.1f", avgViewDurationSec) + "s avg view";
        }
    }
    public static Analytics compute(StatusItem item, int totalContacts) {
        Analytics a = new Analytics();
        a.totalViews           = item.getViewCount();
        a.uniqueViewers        = a.totalViews;
        a.avgViewDurationSec   = item.getAvgViewDurationSec();
        a.reactionBreakdown    = new HashMap<>();
        a.totalReactions       = 0;
        if (item.reactions != null) {
            for (String emoji : item.reactions.values()) {
                a.reactionBreakdown.merge(emoji, 1, Integer::sum);
                a.totalReactions++;
            }
        }
        a.reachPercent = totalContacts > 0 ? (a.uniqueViewers * 100.0 / totalContacts) : 0;
        return a;
    }
    private static DatabaseReference getRef(String ownerUid, String statusId) {
        return FirebaseUtils.db()
            .getReference("statusAnalytics")
            .child(ownerUid)
            .child(statusId);
    }
}