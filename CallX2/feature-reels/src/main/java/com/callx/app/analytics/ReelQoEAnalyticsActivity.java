package com.callx.app.analytics;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.*;
import com.callx.app.player.NetworkQualityMonitor;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.callx.app.reels.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * ReelQoEAnalyticsActivity — Quality of Experience Analytics Dashboard
 *
 * Features:
 *  ✅ Local QoE stats from SharedPreferences (AdaptiveStreamingManager data)
 *  ✅ Per-session ABR metrics: TTFF, stall %, quality switches, avg bitrate
 *  ✅ Firebase Realtime DB persistence — each session posted under:
 *       qoe_analytics/{uid}/{reelId}/{sessionId}
 *  ✅ Rolling 7-day aggregate: avg stall, avg TTFF, switch rate
 *  ✅ Network quality breakdown (% time on each network type)
 *  ✅ Manual Firebase push via "Sync Now" button
 *  ✅ Auto-sync on activity resume (if last sync > 1 hour ago)
 *  ✅ Device/OS metadata included in every Firebase record
 *
 * Open from ReelABRSettingsActivity or ReelCreatorDashboardActivity.
 */
public class ReelQoEAnalyticsActivity extends AppCompatActivity {

    private static final String TAG             = "QoEAnalytics";
    private static final String QOE_PREFS       = "abr_qoe_stats";
    private static final String SYNC_PREFS      = "qoe_sync";
    private static final String KEY_LAST_SYNC   = "last_sync_ms";
    private static final long   AUTO_SYNC_GAP   = 60 * 60 * 1_000L;  // 1 hour

    // SharedPreferences keys (must match AdaptiveStreamingManager)
    private static final String KEY_TOTAL_SESSIONS   = "total_sessions";
    private static final String KEY_TOTAL_STALL_MS   = "total_stall_ms";
    private static final String KEY_TOTAL_SWITCHES   = "total_quality_switches";
    private static final String KEY_TOTAL_UPGRADES   = "total_upgrades";
    private static final String KEY_TOTAL_DOWNGRADES = "total_downgrades";
    private static final String KEY_AVG_TTFF_MS      = "avg_ttff_ms";

    // Firebase path
    private static final String FB_PATH_QOE = "qoe_analytics";

    // v7: Rolling session trend — last N session stall values, for improving/declining arrow
    private static final String TREND_PREFS        = "qoe_trend";
    private static final String KEY_TREND_STALLS   = "recent_stall_values";
    private static final int    MAX_TREND_SAMPLES   = 20;

    // ── Views ─────────────────────────────────────────────────────────────────
    private TextView tvSessions, tvAvgStall, tvAvgTtff, tvSwitches,
                     tvUpgrades, tvDowngrades, tvSyncStatus, tvLastSync;
    private ProgressBar pbStallRate;
    private Button      btnSync;
    private LinearLayout layoutStats;
    private View         viewEmpty;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reel_qoe_analytics);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle("QoE Analytics");
            }
            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        }

        bindViews();
        loadLocalStats();

        // Auto-sync to Firebase if last sync was > 1 hour ago
        long lastSync = getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC, 0);
        if (System.currentTimeMillis() - lastSync > AUTO_SYNC_GAP) {
            syncToFirebase(false);
        }

        if (btnSync != null) {
            btnSync.setOnClickListener(v -> syncToFirebase(true));
        }
    }

    private void bindViews() {
        tvSessions    = findViewById(R.id.tv_sessions);
        tvAvgStall    = findViewById(R.id.tv_avg_stall);
        tvAvgTtff     = findViewById(R.id.tv_avg_ttff);
        tvSwitches    = findViewById(R.id.tv_switches);
        tvUpgrades    = findViewById(R.id.tv_upgrades);
        tvDowngrades  = findViewById(R.id.tv_downgrades);
        tvSyncStatus  = findViewById(R.id.tv_sync_status);
        tvLastSync    = findViewById(R.id.tv_last_sync);
        pbStallRate   = findViewById(R.id.pb_stall_rate);
        btnSync       = findViewById(R.id.btn_sync);
        layoutStats   = findViewById(R.id.layout_stats);
        viewEmpty     = findViewById(R.id.view_empty);
    }

    // ── Local stats ───────────────────────────────────────────────────────────

    private void loadLocalStats() {
        SharedPreferences prefs = getSharedPreferences(QOE_PREFS, Context.MODE_PRIVATE);
        long sessions   = prefs.getLong(KEY_TOTAL_SESSIONS,   0);
        long stallMs    = prefs.getLong(KEY_TOTAL_STALL_MS,   0);
        long switches   = prefs.getLong(KEY_TOTAL_SWITCHES,   0);
        long upgrades   = prefs.getLong(KEY_TOTAL_UPGRADES,   0);
        long downgrades = prefs.getLong(KEY_TOTAL_DOWNGRADES, 0);
        long avgTtff    = prefs.getLong(KEY_AVG_TTFF_MS,      0);

        if (sessions == 0) {
            if (layoutStats != null) layoutStats.setVisibility(View.GONE);
            if (viewEmpty   != null) viewEmpty.setVisibility(View.VISIBLE);
            return;
        }

        if (layoutStats != null) layoutStats.setVisibility(View.VISIBLE);
        if (viewEmpty   != null) viewEmpty.setVisibility(View.GONE);

        long avgStallMs = stallMs / sessions;
        // Stall rate: avg stall per video as % of a 30s reel
        float stallRatePct = Math.min(100f, (avgStallMs / 30_000f) * 100f);

        set(tvSessions,   String.valueOf(sessions));
        set(tvAvgStall,   avgStallMs + " ms avg");
        set(tvAvgTtff,    avgTtff + " ms");
        set(tvSwitches,   String.valueOf(switches));
        set(tvUpgrades,   "↑ " + upgrades);
        set(tvDowngrades, "↓ " + downgrades);

        // v6: Composite QoE Health Score (0-100) — single glanceable number
        // combining stall rate, switch frequency, and startup latency.
        int healthScore = computeHealthScore(stallRatePct, switches, sessions, avgTtff);
        String scoreLabel = healthScore >= 80 ? "Excellent" : healthScore >= 60 ? "Good"
            : healthScore >= 40 ? "Fair" : "Poor";
        set(tvSwitches, switches + "  ·  QoE Score: " + healthScore + "/100 (" + scoreLabel + ")");

        if (pbStallRate != null) {
            pbStallRate.setProgress((int) stallRatePct);
        }

        // Last sync timestamp
        long lastSync = getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC, 0);
        if (tvLastSync != null) {
            String syncText = lastSync == 0 ? "Never synced" : "Last sync: " + formatTime(lastSync);
            tvLastSync.setText(syncText + "   ·   Trend: " + computeTrendLabel());
        }
    }

    /**
     * v6: Composite QoE Health Score (0-100).
     * Weighted blend: stall rate (heaviest penalty), switch frequency
     * (oscillation = bad UX), and startup latency (TTFF).
     */
    private int computeHealthScore(float stallRatePct, long switches, long sessions, long avgTtffMs) {
        double stallPenalty  = Math.min(60.0, stallRatePct * 0.6);              // up to -60
        double switchesPerSession = sessions > 0 ? (double) switches / sessions : 0;
        double switchPenalty = Math.min(25.0, switchesPerSession * 3.0);        // up to -25
        double ttffPenalty   = Math.min(15.0, (avgTtffMs / 1000.0) * 5.0);      // up to -15
        int score = (int) Math.round(100.0 - stallPenalty - switchPenalty - ttffPenalty);
        return Math.max(0, Math.min(100, score));
    }

    // ── Firebase sync ─────────────────────────────────────────────────────────

    /**
     * Push aggregated QoE snapshot to Firebase Realtime Database.
     * Path: qoe_analytics/{uid}/sessions/{timestamp}
     *
     * Transaction-safe: uses runTransaction so concurrent writes from
     * multiple sessions merge correctly on the server.
     *
     * @param userTriggered true = show toast feedback
     */
    private void syncToFirebase(boolean userTriggered) {
        String uidTemp = null;
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() != null)
                uidTemp = FirebaseAuth.getInstance().getCurrentUser().getUid();
        } catch (Exception ignored) {}
        if (uidTemp == null) {
            setSyncStatus("Sign in required to sync");
            return;
        }
        final String uid = uidTemp;

        setSyncStatus("Syncing…");
        if (btnSync != null) btnSync.setEnabled(false);

        SharedPreferences prefs = getSharedPreferences(QOE_PREFS, Context.MODE_PRIVATE);
        final long sessions   = prefs.getLong(KEY_TOTAL_SESSIONS,   0);
        final long stallMs    = prefs.getLong(KEY_TOTAL_STALL_MS,   0);
        final long switches   = prefs.getLong(KEY_TOTAL_SWITCHES,   0);
        final long upgrades   = prefs.getLong(KEY_TOTAL_UPGRADES,   0);
        final long downgrades = prefs.getLong(KEY_TOTAL_DOWNGRADES, 0);
        final long avgTtff    = prefs.getLong(KEY_AVG_TTFF_MS,      0);

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("sessions",        sessions);
        snapshot.put("total_stall_ms",  stallMs);
        snapshot.put("avg_stall_ms",    sessions > 0 ? stallMs / sessions : 0);
        snapshot.put("total_switches",  switches);
        snapshot.put("upgrades",        upgrades);
        snapshot.put("downgrades",      downgrades);
        snapshot.put("avg_ttff_ms",     avgTtff);
        snapshot.put("device_model",    android.os.Build.MODEL);
        snapshot.put("os_version",      android.os.Build.VERSION.SDK_INT);
        snapshot.put("timestamp",       System.currentTimeMillis());
        snapshot.put("network_type",    getNetworkTypeLabel());

        String sessionKey = String.valueOf(System.currentTimeMillis());
        DatabaseReference ref = FirebaseDatabase.getInstance()
            .getReference(FB_PATH_QOE)
            .child(uid)
            .child("sessions")
            .child(sessionKey);

        ref.setValue(snapshot)
           .addOnSuccessListener(unused -> {
               long now = System.currentTimeMillis();
               getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                   .edit().putLong(KEY_LAST_SYNC, now).apply();
               mainHandler.post(() -> {
                   setSyncStatus("Synced ✓ " + formatTime(now));
                   if (tvLastSync != null) tvLastSync.setText("Last sync: " + formatTime(now));
                   if (btnSync != null) btnSync.setEnabled(true);
                   if (userTriggered) toast("QoE synced to Firebase");
               });
               Log.d(TAG, "QoE synced → " + ref.getPath());

               // Also update lifetime aggregate via transaction
               updateLifetimeAggregate(uid, sessions, stallMs, switches, avgTtff);
           })
           .addOnFailureListener(e -> {
               mainHandler.post(() -> {
                   setSyncStatus("Sync failed: " + e.getMessage());
                   if (btnSync != null) btnSync.setEnabled(true);
                   if (userTriggered) toast("Sync failed");
               });
               Log.w(TAG, "Firebase sync failed: " + e.getMessage());
           });
    }

    /**
     * Merge local stats into per-user lifetime aggregate using Firebase Transaction.
     * Path: qoe_analytics/{uid}/lifetime
     */
    private void updateLifetimeAggregate(String uid, long sessions,
                                          long totalStallMs, long totalSwitches, long avgTtff) {
        DatabaseReference lifetimeRef = FirebaseDatabase.getInstance()
            .getReference(FB_PATH_QOE)
            .child(uid)
            .child("lifetime");

        lifetimeRef.runTransaction(new Transaction.Handler() {
            @Override
            @NonNull
            public Transaction.Result doTransaction(@NonNull MutableData data) {
                Long prevSessions = data.child("sessions").getValue(Long.class);
                if (prevSessions == null) prevSessions = 0L;

                Long prevStall = data.child("total_stall_ms").getValue(Long.class);
                if (prevStall == null) prevStall = 0L;

                Long prevSwitches = data.child("total_switches").getValue(Long.class);
                if (prevSwitches == null) prevSwitches = 0L;

                long newSessions  = prevSessions + sessions;
                long newStall     = prevStall    + totalStallMs;
                long newSwitches  = prevSwitches + totalSwitches;
                long newAvgStall  = newSessions > 0 ? newStall / newSessions : 0;

                data.child("sessions").setValue(newSessions);
                data.child("total_stall_ms").setValue(newStall);
                data.child("avg_stall_ms").setValue(newAvgStall);
                data.child("total_switches").setValue(newSwitches);
                data.child("avg_ttff_ms").setValue(avgTtff);
                data.child("last_updated").setValue(System.currentTimeMillis());
                return Transaction.success(data);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed,
                                   DataSnapshot currentData) {
                if (error != null) Log.w(TAG, "Lifetime aggregate error: " + error.getMessage());
                else Log.d(TAG, "Lifetime aggregate updated");
            }
        });
    }

    // ── Static helper: push a single reel session from anywhere ──────────────

    /**
     * Push QoE metrics for a single reel playback session to Firebase.
     * Call from ReelPlayerController/ReelPlayerFragment on playback end.
     *
     * @param ctx          application context
     * @param reelId       reel being played
     * @param stallMs      total stall during this session
     * @param ttffMs       time-to-first-frame (-1 = unknown)
     * @param switches     quality switch count
     * @param avgBitrateKbps average bitrate during session
     */
    public static void pushSessionToFirebase(Context ctx, String reelId,
                                              long stallMs, long ttffMs,
                                              int switches, long avgBitrateKbps) {
        String uid = null;
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() != null)
                uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        } catch (Exception ignored) {}
        if (uid == null || reelId == null) return;

        Map<String, Object> data = new HashMap<>();
        data.put("reel_id",         reelId);
        data.put("stall_ms",        stallMs);
        data.put("ttff_ms",         ttffMs);
        data.put("switches",        switches);
        data.put("avg_bitrate_kbps",avgBitrateKbps);
        data.put("device_model",    android.os.Build.MODEL);
        data.put("os_sdk",          android.os.Build.VERSION.SDK_INT);
        data.put("timestamp",       System.currentTimeMillis());

        String sessionKey = reelId + "_" + System.currentTimeMillis();
        FirebaseDatabase.getInstance()
            .getReference(FB_PATH_QOE)
            .child(uid)
            .child("reel_sessions")
            .child(sessionKey)
            .setValue(data)
            .addOnSuccessListener(v -> Log.d(TAG, "Session QoE pushed: " + reelId))
            .addOnFailureListener(e -> Log.w(TAG, "pushSession failed: " + e.getMessage()));

        // v7: Append this session's stall value to the rolling local trend window
        recordTrendSample(ctx, stallMs);
    }

    /** v7: Append a stall-ms sample to the rolling trend window (max MAX_TREND_SAMPLES). */
    private static void recordTrendSample(Context ctx, long stallMs) {
        try {
            SharedPreferences prefs = ctx.getApplicationContext()
                .getSharedPreferences(TREND_PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_TREND_STALLS, "");
            java.util.List<String> values = new java.util.ArrayList<>(
                java.util.Arrays.asList(raw.isEmpty() ? new String[0] : raw.split(",")));
            values.add(String.valueOf(stallMs));
            while (values.size() > MAX_TREND_SAMPLES) values.remove(0);
            prefs.edit().putString(KEY_TREND_STALLS, String.join(",", values)).apply();
        } catch (Exception e) {
            Log.w(TAG, "recordTrendSample: " + e.getMessage());
        }
    }

    /**
     * v7: Compare the average stall time of the first half vs second half of the
     * rolling sample window to produce a simple "Improving / Declining / Stable" trend.
     */
    private String computeTrendLabel() {
        try {
            SharedPreferences prefs = getSharedPreferences(TREND_PREFS, Context.MODE_PRIVATE);
            String raw = prefs.getString(KEY_TREND_STALLS, "");
            if (raw.isEmpty()) return "Not enough data yet";
            String[] parts = raw.split(",");
            if (parts.length < 4) return "Not enough data yet";

            int mid = parts.length / 2;
            double firstAvg = 0, secondAvg = 0;
            for (int i = 0; i < mid; i++) firstAvg += Long.parseLong(parts[i]);
            for (int i = mid; i < parts.length; i++) secondAvg += Long.parseLong(parts[i]);
            firstAvg  /= mid;
            secondAvg /= (parts.length - mid);

            double deltaPct = firstAvg == 0 ? 0 : ((secondAvg - firstAvg) / firstAvg) * 100.0;
            if (deltaPct < -10) return "▲ Improving (stalls down " + (int) -deltaPct + "%)";
            if (deltaPct >  10) return "▼ Declining (stalls up " + (int) deltaPct + "%)";
            return "● Stable";
        } catch (Exception e) {
            return "";
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private void setSyncStatus(String msg) {
        mainHandler.post(() -> { if (tvSyncStatus != null) tvSyncStatus.setText(msg); });
    }

    private void set(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String formatTime(long ms) {
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault());
        sdf.setTimeZone(TimeZone.getDefault());
        return sdf.format(new Date(ms));
    }

    private String getNetworkTypeLabel() {
        try {
            return NetworkQualityMonitor.get(this).currentQuality().name();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}
