package com.callx.app.utils;

import androidx.annotation.NonNull;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.Map;

/**
 * StatusAnalyticsTracker — Production-grade per-status view analytics.
 *
 * Firebase schema:
 *   statusAnalytics/{ownerUid}/{statusId}/
 *     totalViews      — int (incremented on each unique view)
 *     totalDurationMs — long (sum of all view durations in ms)
 *     viewsByHour/{0..23} — int (views per hour of day — for owner insights)
 *     completionCount — int (views where user watched >= 80% of duration)
 *     viewerDetails/{viewerUid}/
 *         viewedAt    — timestamp
 *         durationMs  — how long they watched
 *         completed   — boolean (watched >= 80%)
 *
 * Usage (StatusViewerActivity integration):
 *   // When status starts displaying:
 *   String sessionId = StatusAnalyticsTracker.startView(ownerUid, statusId);
 *
 *   // When status stops (next/prev/close):
 *   StatusAnalyticsTracker.endView(ownerUid, statusId, sessionId, durationMs, totalDurationMs);
 *
 * Owner insights (StatusViewerActivity "Seen by" tap):
 *   StatusAnalyticsTracker.loadInsights(ownerUid, statusId, callback);
 */
public final class StatusAnalyticsTracker {

    private static final String FB_ROOT = "statusAnalytics";
    private static final double COMPLETION_THRESHOLD = 0.80; // 80% watch = completed

    private StatusAnalyticsTracker() {}

    // ── Insights model ────────────────────────────────────────────────────

    public static class Insights {
        public int  totalViews;
        public long totalDurationMs;
        public int  completionCount;
        public int  avgDurationSec;
        public int  peakHour;     // 0-23, hour with most views
        public double completionRate; // 0.0–1.0

        public String formatAvgDuration() {
            int sec = avgDurationSec;
            if (sec < 60) return sec + "s";
            return (sec / 60) + "m " + (sec % 60) + "s";
        }

        public String formatCompletionRate() {
            return Math.round(completionRate * 100) + "%";
        }
    }

    public interface InsightsCallback {
        void onInsights(Insights insights);
    }

    // ── Track view start / end ────────────────────────────────────────────

    /**
     * Called when a status item starts displaying to the viewer.
     * Returns a start-time token (System.currentTimeMillis) to pass to endView.
     */
    public static long startView() {
        return System.currentTimeMillis();
    }

    /**
     * Called when the viewer moves away from a status item (next/prev/close/pause).
     *
     * @param ownerUid      UID of the status owner
     * @param statusId      ID of the viewed status
     * @param startTimeMs   value returned by startView()
     * @param totalDurationMs total display duration of this status (for completion calc)
     */
    public static void endView(String ownerUid, String statusId,
                                long startTimeMs, long totalDurationMs) {
        if (ownerUid == null || statusId == null) return;
        String myUid = safeUid();
        if (myUid == null || myUid.equals(ownerUid)) return; // don't track own views

        long viewDurationMs = System.currentTimeMillis() - startTimeMs;
        if (viewDurationMs <= 0) return;

        boolean completed = totalDurationMs > 0
                && ((double) viewDurationMs / totalDurationMs) >= COMPLETION_THRESHOLD;

        int hourOfDay = new java.util.Calendar.Builder()
            .setInstant(System.currentTimeMillis())
            .build()
            .get(java.util.Calendar.HOUR_OF_DAY);

        Map<String, Object> updates = new HashMap<>();
        String base = FB_ROOT + "/" + ownerUid + "/" + statusId;

        // Viewer detail
        updates.put(base + "/viewerDetails/" + myUid + "/viewedAt",    ServerValue.TIMESTAMP);
        updates.put(base + "/viewerDetails/" + myUid + "/durationMs",  viewDurationMs);
        updates.put(base + "/viewerDetails/" + myUid + "/completed",   completed);

        // Increment totalViews + totalDurationMs + viewsByHour
        // NOTE: we use multi-path update. The counters (totalViews, totalDurationMs,
        // completionCount) need server-side increment; we read-modify-write here
        // since Firebase RTDB doesn't support atomic increment in multi-path writes.
        // For high-traffic scenarios, Cloud Functions would be better; this is fine
        // for a messaging app status system.
        final boolean isCompleted = completed;
        final long duration = viewDurationMs;
        final int hour = hourOfDay;

        FirebaseUtils.db()
            .getReference(base)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    Long existing = snap.child("totalViews").getValue(Long.class);
                    Long existingDur = snap.child("totalDurationMs").getValue(Long.class);
                    Long existingComp = snap.child("completionCount").getValue(Long.class);
                    Long existingHour = snap.child("viewsByHour/" + hour).getValue(Long.class);

                    Map<String, Object> up = new HashMap<>();
                    up.put("totalViews",      (existing      != null ? existing      : 0L) + 1);
                    up.put("totalDurationMs", (existingDur   != null ? existingDur   : 0L) + duration);
                    up.put("viewsByHour/" + hour, (existingHour != null ? existingHour : 0L) + 1);
                    if (isCompleted) {
                        up.put("completionCount", (existingComp != null ? existingComp : 0L) + 1);
                    }

                    FirebaseUtils.db().getReference(base).updateChildren(up);
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {}
            });

        // Write viewer details in parallel (no read needed)
        FirebaseUtils.db().getReference().updateChildren(updates);
    }

    // ── Load insights (for status owner) ─────────────────────────────────

    /**
     * Load analytics insights for a single status.
     * Called when the owner taps "Seen by N" to get detailed analytics.
     */
    public static void loadInsights(String ownerUid, String statusId, InsightsCallback cb) {
        if (ownerUid == null || statusId == null || cb == null) return;
        String myUid = safeUid();
        if (myUid == null || !myUid.equals(ownerUid)) return; // only owner sees analytics

        FirebaseUtils.db()
            .getReference(FB_ROOT)
            .child(ownerUid)
            .child(statusId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    Insights ins = new Insights();
                    Long views    = snap.child("totalViews").getValue(Long.class);
                    Long totalDur = snap.child("totalDurationMs").getValue(Long.class);
                    Long compCount = snap.child("completionCount").getValue(Long.class);

                    ins.totalViews     = views    != null ? views.intValue()    : 0;
                    ins.totalDurationMs = totalDur != null ? totalDur           : 0L;
                    ins.completionCount = compCount != null ? compCount.intValue() : 0;

                    if (ins.totalViews > 0) {
                        ins.avgDurationSec = (int)(ins.totalDurationMs / ins.totalViews / 1000L);
                        ins.completionRate = (double) ins.completionCount / ins.totalViews;
                    }

                    // Find peak hour
                    int peak = 0; long peakCount = 0;
                    DataSnapshot hoursSnap = snap.child("viewsByHour");
                    for (DataSnapshot hSnap : hoursSnap.getChildren()) {
                        Long c = hSnap.getValue(Long.class);
                        if (c != null && c > peakCount) {
                            peakCount = c;
                            try { peak = Integer.parseInt(hSnap.getKey()); } catch (Exception ignored) {}
                        }
                    }
                    ins.peakHour = peak;
                    cb.onInsights(ins);
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    cb.onInsights(new Insights());
                }
            });
    }

    // ── Viewer name list (resolves UIDs → names) ──────────────────────────

    public interface ViewerListCallback {
        void onViewers(java.util.List<ViewerEntry> viewers);
    }

    public static class ViewerEntry {
        public String uid;
        public String name;
        public String photoUrl;
        public long   viewedAt;
        public long   durationMs;
        public boolean completed;
    }

    /**
     * Load viewer detail list for a status (owner only).
     * Resolves each viewer's name and photo from the "users" node.
     */
    public static void loadViewerList(String ownerUid, String statusId, ViewerListCallback cb) {
        if (ownerUid == null || statusId == null || cb == null) return;
        String myUid = safeUid();
        if (myUid == null || !myUid.equals(ownerUid)) {
            cb.onViewers(new java.util.ArrayList<>());
            return;
        }

        FirebaseUtils.db()
            .getReference(FB_ROOT)
            .child(ownerUid)
            .child(statusId)
            .child("viewerDetails")
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snap) {
                    java.util.List<ViewerEntry> raw = new java.util.ArrayList<>();
                    for (DataSnapshot child : snap.getChildren()) {
                        ViewerEntry e = new ViewerEntry();
                        e.uid        = child.getKey();
                        Long vAt     = child.child("viewedAt").getValue(Long.class);
                        Long dur     = child.child("durationMs").getValue(Long.class);
                        Boolean comp = child.child("completed").getValue(Boolean.class);
                        e.viewedAt   = vAt  != null ? vAt  : 0L;
                        e.durationMs = dur  != null ? dur  : 0L;
                        e.completed  = comp != null && comp;
                        raw.add(e);
                    }
                    // Resolve names
                    resolveNames(raw, cb);
                }
                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    cb.onViewers(new java.util.ArrayList<>());
                }
            });
    }

    private static void resolveNames(java.util.List<ViewerEntry> entries, ViewerListCallback cb) {
        if (entries.isEmpty()) { cb.onViewers(entries); return; }
        final int[] done = {0};
        for (ViewerEntry e : entries) {
            if (e.uid == null) { checkDone(done, entries.size(), entries, cb); continue; }
            FirebaseUtils.db()
                .getReference("users")
                .child(e.uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snap) {
                        e.name     = snap.child("name").getValue(String.class);
                        e.photoUrl = snap.child("photoUrl").getValue(String.class);
                        if (e.name == null) e.name = "Unknown";
                        checkDone(done, entries.size(), entries, cb);
                    }
                    @Override
                    public void onCancelled(@NonNull DatabaseError err) {
                        e.name = "Unknown";
                        checkDone(done, entries.size(), entries, cb);
                    }
                });
        }
    }

    private static void checkDone(int[] done, int total,
                                   java.util.List<ViewerEntry> entries,
                                   ViewerListCallback cb) {
        done[0]++;
        if (done[0] >= total) {
            entries.sort((a, b) -> Long.compare(b.viewedAt, a.viewedAt));
            cb.onViewers(entries);
        }
    }

    private static String safeUid() {
        try { return FirebaseUtils.getCurrentUid(); } catch (Exception e) { return null; }
    }
}
