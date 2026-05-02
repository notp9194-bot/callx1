package com.callx.app.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Feature 10 (NEW): Seen By List (Group)
 * Shows which group members have seen a message and at what time.
 */
public class SeenByActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView     tvEmpty;
    private SeenAdapter  adapter;

    private final List<SeenRow> rows = new ArrayList<>();
    private String groupId, msgId;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_seen_by);

        groupId = getIntent().getStringExtra("groupId");
        msgId   = getIntent().getStringExtra("msgId");
        if (groupId == null || msgId == null) { finish(); return; }

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Seen By");
        }
        tb.setNavigationOnClickListener(v -> finish());

        rv      = findViewById(R.id.rv_seen_by);
        tvEmpty = findViewById(R.id.tv_empty);
        adapter = new SeenAdapter(rows);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        loadSeenBy();
    }

    private void loadSeenBy() {
        FirebaseUtils.getGroupMessagesRef(groupId).child(msgId).child("seenBy")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        rows.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            String uid = c.getKey();
                            Long   ts  = c.getValue(Long.class);
                            // Fetch display name
                            String name = uid;
                            rows.add(new SeenRow(name, uid, ts));
                        }
                        // Load names from Firebase
                        loadNames();
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        Toast.makeText(SeenByActivity.this, "Failed", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void loadNames() {
        if (rows.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
            return;
        }
        final int[] pending = {rows.size()};
        for (SeenRow row : rows) {
            FirebaseUtils.getUserRef(row.uid).child("name")
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override public void onDataChange(DataSnapshot s) {
                            String n = s.getValue(String.class);
                            if (n != null) row.name = n;
                            pending[0]--;
                            if (pending[0] == 0) finishLoad();
                        }
                        @Override public void onCancelled(DatabaseError e) {
                            pending[0]--;
                            if (pending[0] == 0) finishLoad();
                        }
                    });
        }
    }

    private void finishLoad() {
        rows.sort((a, b) -> Long.compare(
                a.seenAt != null ? a.seenAt : 0,
                b.seenAt != null ? b.seenAt : 0));
        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    static class SeenRow {
        String name, uid;
        Long   seenAt;
        SeenRow(String n, String u, Long t) { name = n; uid = u; seenAt = t; }
    }

    static class SeenAdapter extends RecyclerView.Adapter<SeenAdapter.VH> {
        private final List<SeenRow> rows;
        private final SimpleDateFormat fmt =
                new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
        SeenAdapter(List<SeenRow> rows) { this.rows = rows; }
        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(android.R.layout.two_line_list_item, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            SeenRow r = rows.get(pos);
            h.line1.setText(r.name);
            h.line2.setText(r.seenAt != null
                    ? "Seen at " + fmt.format(new Date(r.seenAt))
                    : "Seen");
        }
        @Override public int getItemCount() { return rows.size(); }
        static class VH extends RecyclerView.ViewHolder {
            TextView line1, line2;
            VH(View v) {
                super(v);
                line1 = v.findViewById(android.R.id.text1);
                line2 = v.findViewById(android.R.id.text2);
            }
        }
    }
}
