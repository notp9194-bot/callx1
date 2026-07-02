package com.callx.app.broadcast;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BroadcastAnalyticsActivity — per-message delivery + seen report.
 *
 * Shows:
 *  - Delivery status per recipient (delivered / skipped / pending)
 *  - Seen timestamp per recipient (when they read it)
 *  - Total stats: delivered / seen / skipped / replied
 *  - Export to CSV button
 *
 * Extras:
 *   EXTRA_MSG_ID   — broadcast message ID
 *   EXTRA_LIST_ID  — broadcast list ID
 *   EXTRA_MSG_TEXT — preview of message text (for title)
 */
public class BroadcastAnalyticsActivity extends AppCompatActivity {

    public static final String EXTRA_MSG_ID   = "analyticsMessageId";
    public static final String EXTRA_LIST_ID  = "analyticsListId";
    public static final String EXTRA_MSG_TEXT = "analyticsMsgText";

    private RecyclerView    rvRecipients;
    private TextView        tvStats;
    private TextView        tvExport;

    private AnalyticsAdapter adapter;
    private final List<RecipientRow> rows = new ArrayList<>();

    private String myUid;
    private String msgId;
    private String listId;

    private final SimpleDateFormat dtFmt =
            new SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault());

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_broadcast_analytics);

        msgId  = getIntent().getStringExtra(EXTRA_MSG_ID);
        listId = getIntent().getStringExtra(EXTRA_LIST_ID);
        String msgText = getIntent().getStringExtra(EXTRA_MSG_TEXT);

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        if (myUid == null || msgId == null || listId == null) { finish(); return; }

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Message Analytics");
            if (msgText != null && !msgText.isEmpty()) {
                getSupportActionBar().setSubtitle(
                        msgText.length() > 40 ? msgText.substring(0, 40) + "…" : msgText);
            }
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        rvRecipients = findViewById(R.id.rv_analytics_recipients);
        tvStats      = findViewById(R.id.tv_analytics_stats);
        tvExport     = findViewById(R.id.tv_export_csv);

        rvRecipients.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AnalyticsAdapter(rows);
        rvRecipients.setAdapter(adapter);

        tvExport.setOnClickListener(v -> exportCsv());

        loadAnalytics();
    }

    // ── Load message + recipients from Firebase ───────────────────────────────
    private void loadAnalytics() {
        // 1. Fetch message metadata (seenByTs, deliveredCount, skippedCount, replyCount)
        FirebaseUtils.db()
                .getReference("broadcast_messages").child(myUid).child(listId).child(msgId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot msgSnap) {
                        // seenByTs: uid → epoch ms
                        Map<String, Long> seenTs = new HashMap<>();
                        DataSnapshot seenByTsSnap = msgSnap.child("seenByTs");
                        for (DataSnapshot s : seenByTsSnap.getChildren()) {
                            Long ts = s.getValue(Long.class);
                            if (s.getKey() != null && ts != null) seenTs.put(s.getKey(), ts);
                        }
                        // seenBy (bool fallback for older messages)
                        for (DataSnapshot s : msgSnap.child("seenBy").getChildren()) {
                            if (s.getKey() != null && !seenTs.containsKey(s.getKey()))
                                seenTs.put(s.getKey(), 0L); // seen but no timestamp
                        }

                        long sentTs = msgSnap.child("timestamp").getValue(Long.class) != null
                                ? msgSnap.child("timestamp").getValue(Long.class) : 0L;
                        int replyCount = msgSnap.child("replyCount").getValue(Integer.class) != null
                                ? msgSnap.child("replyCount").getValue(Integer.class) : 0;

                        // 2. Fetch recipients list
                        FirebaseUtils.db()
                                .getReference("broadcast_lists").child(myUid).child(listId)
                                .child("recipients")
                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override public void onDataChange(@NonNull DataSnapshot rSnap) {
                                        loadRecipientNames(rSnap, seenTs, sentTs, replyCount);
                                    }
                                    @Override public void onCancelled(@NonNull DatabaseError e) {
                                        Toast.makeText(BroadcastAnalyticsActivity.this,
                                                "Load failed", Toast.LENGTH_SHORT).show();
                                    }
                                });
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    private void loadRecipientNames(DataSnapshot rSnap,
                                    Map<String, Long> seenTs,
                                    long sentTs, int replyCount) {
        List<String> uids = new ArrayList<>();
        for (DataSnapshot r : rSnap.getChildren()) {
            if (r.getKey() != null) uids.add(r.getKey());
        }
        if (uids.isEmpty()) {
            updateUi(replyCount);
            return;
        }

        final int[] remaining = {uids.size()};
        for (String uid : uids) {
            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(
                    new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot us) {
                            String name = us.child("name").getValue(String.class);
                            if (name == null) name = "User";

                            Long seen = seenTs.get(uid);
                            String seenLabel;
                            if (seen == null) {
                                seenLabel = "Not seen";
                            } else if (seen == 0) {
                                seenLabel = "Seen (time unknown)";
                            } else {
                                seenLabel = "Seen " + dtFmt.format(new Date(seen));
                            }

                            // Delivery gap (how long after send was it seen?)
                            long gapMs = (seen != null && seen > 0 && sentTs > 0)
                                    ? seen - sentTs : -1;
                            String gapLabel = gapMs > 0
                                    ? "(" + formatDuration(gapMs) + " baad)" : "";

                            rows.add(new RecipientRow(name, uid, seenLabel, gapLabel,
                                    seen != null));
                            remaining[0]--;
                            if (remaining[0] == 0) {
                                // Sort: seen first (by timestamp asc), then not-seen
                                Collections.sort(rows, (a, b) -> {
                                    if (a.seen && !b.seen) return -1;
                                    if (!a.seen && b.seen) return 1;
                                    return a.name.compareToIgnoreCase(b.name);
                                });
                                updateUi(replyCount);
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {
                            remaining[0]--;
                            if (remaining[0] == 0) updateUi(replyCount);
                        }
                    });
        }
    }

    private void updateUi(int replyCount) {
        adapter.notifyDataSetChanged();
        int total   = rows.size();
        int seen    = (int) rows.stream().filter(r -> r.seen).count();
        int notSeen = total - seen;
        tvStats.setText(
                "📢 " + total + " recipients  •  "
                + "👁 " + seen + " seen  •  "
                + "⏳ " + notSeen + " not seen"
                + (replyCount > 0 ? "  •  💬 " + replyCount + " replied" : ""));
    }

    // ── CSV Export ────────────────────────────────────────────────────────────
    private void exportCsv() {
        if (rows.isEmpty()) {
            Toast.makeText(this, "Koi data nahi export karne ke liye", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            File dir  = getExternalFilesDir("exports");
            if (dir != null) dir.mkdirs();
            File csv  = new File(dir, "broadcast_analytics_" + msgId + ".csv");
            FileWriter fw = new FileWriter(csv);
            fw.write("Name,UID,Seen,Seen Time,Delay After Send\n");
            for (RecipientRow r : rows) {
                fw.write(escapeCsv(r.name) + "," + escapeCsv(r.uid) + ","
                        + (r.seen ? "Yes" : "No") + ","
                        + escapeCsv(r.seenLabel) + ","
                        + escapeCsv(r.gapLabel) + "\n");
            }
            fw.close();

            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", csv);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/csv");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Broadcast Analytics Report");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Share Analytics CSV"));
        } catch (IOException e) {
            Toast.makeText(this, "CSV export failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private String escapeCsv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }

    private String formatDuration(long ms) {
        if (ms < 60_000) return (ms / 1000) + "s";
        if (ms < 3_600_000) return (ms / 60_000) + "m";
        return (ms / 3_600_000) + "h " + ((ms % 3_600_000) / 60_000) + "m";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Adapter
    // ─────────────────────────────────────────────────────────────────────────

    static class RecipientRow {
        String name, uid, seenLabel, gapLabel;
        boolean seen;
        RecipientRow(String name, String uid, String seenLabel, String gapLabel, boolean seen) {
            this.name = name; this.uid = uid;
            this.seenLabel = seenLabel; this.gapLabel = gapLabel; this.seen = seen;
        }
    }

    static class AnalyticsAdapter extends RecyclerView.Adapter<AnalyticsAdapter.VH> {
        private final List<RecipientRow> data;
        AnalyticsAdapter(List<RecipientRow> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_broadcast_analytics_recipient, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            RecipientRow r = data.get(pos);
            h.tvName.setText(r.name);
            h.tvSeen.setText(r.seenLabel);
            h.tvGap.setText(r.gapLabel);
            h.tvGap.setVisibility(r.gapLabel.isEmpty() ? View.GONE : View.VISIBLE);
            // Green if seen, grey if not
            int color = r.seen ? 0xFF4CAF50 : 0xFF9E9E9E;
            h.tvSeen.setTextColor(color);
            h.tvDot.getBackground().setTint(color);
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvSeen, tvGap;
            View tvDot;
            VH(@NonNull View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_analytics_name);
                tvSeen = v.findViewById(R.id.tv_analytics_seen);
                tvGap  = v.findViewById(R.id.tv_analytics_gap);
                tvDot  = v.findViewById(R.id.view_analytics_dot);
            }
        }
    }
}
