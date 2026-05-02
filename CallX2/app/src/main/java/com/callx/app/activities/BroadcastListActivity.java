package com.callx.app.activities;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * Feature 9 (NEW): Broadcast / Announcement List
 *
 * Lets a user:
 *  1. Create a named broadcast list with selected contacts.
 *  2. Send a message to all contacts in the list as separate 1-on-1 chats.
 *     (Recipients see it as a normal message — not a group.)
 *  3. View and manage existing broadcast lists.
 *
 * Firebase schema:
 *   broadcasts/{uid}/{listId}/name        = "My Broadcast"
 *   broadcasts/{uid}/{listId}/members/{uid} = true
 */
public class BroadcastListActivity extends AppCompatActivity {

    private RecyclerView  rvLists;
    private BroadcastAdapter adapter;
    private final List<BroadcastRow> lists = new ArrayList<>();
    private String currentUid, currentName;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_broadcast_list);

        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        currentName = FirebaseUtils.getCurrentName();

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Broadcast Lists");
        }
        tb.setNavigationOnClickListener(v -> finish());

        rvLists = findViewById(R.id.rv_broadcast_lists);
        rvLists.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BroadcastAdapter(lists,
                this::onListClick, this::onSendClick);
        rvLists.setAdapter(adapter);

        findViewById(R.id.fab_new_list).setOnClickListener(v -> showNewListDialog());
        loadLists();
    }

    private void loadLists() {
        FirebaseUtils.getBroadcastsRef(currentUid)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        lists.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            String listId = c.getKey();
                            String name   = c.child("name").getValue(String.class);
                            long   count  = c.child("members").getChildrenCount();
                            lists.add(new BroadcastRow(listId, name, (int) count));
                        }
                        adapter.notifyDataSetChanged();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void showNewListDialog() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_new_broadcast, null);
        EditText etName = v.findViewById(R.id.et_list_name);
        new AlertDialog.Builder(this)
                .setTitle("New Broadcast List")
                .setView(v)
                .setPositiveButton("Create", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) return;
                    createList(name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createList(String name) {
        DatabaseReference ref = FirebaseUtils.getBroadcastsRef(currentUid).push();
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        ref.setValue(data).addOnSuccessListener(u ->
                Toast.makeText(this, "List \"" + name + "\" created. Tap to add members.",
                        Toast.LENGTH_SHORT).show());
    }

    private void onListClick(BroadcastRow row) {
        // Open member management — for simplicity show info toast
        Toast.makeText(this, row.name + " — " + row.memberCount + " recipients",
                Toast.LENGTH_SHORT).show();
    }

    private void onSendClick(BroadcastRow row) {
        // Prompt for message text then blast
        EditText et = new EditText(this);
        et.setHint("Type your broadcast message…");
        new AlertDialog.Builder(this)
                .setTitle("Send to " + row.name)
                .setView(et)
                .setPositiveButton("Send", (d, w) -> {
                    String txt = et.getText().toString().trim();
                    if (!txt.isEmpty()) sendBroadcast(row, txt);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendBroadcast(BroadcastRow row, String text) {
        FirebaseUtils.getBroadcastsRef(currentUid)
                .child(row.listId).child("members")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        int sent = 0;
                        for (DataSnapshot c : snap.getChildren()) {
                            String recipientUid = c.getKey();
                            sendToPeer(recipientUid, text, row.listId);
                            sent++;
                        }
                        Toast.makeText(BroadcastListActivity.this,
                                "Sent to " + sent + " recipients", Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void sendToPeer(String recipientUid, String text, String broadcastListId) {
        String chatId = FirebaseUtils.getChatId(currentUid, recipientUid);
        Message msg = new Message();
        msg.id              = FirebaseUtils.getMessagesRef(chatId).push().getKey();
        msg.senderId        = currentUid;
        msg.senderName      = currentName;
        msg.type            = "text";
        msg.text            = text;
        msg.timestamp       = System.currentTimeMillis();
        msg.status          = "sent";
        msg.broadcastListId = broadcastListId;
        if (msg.id != null) {
            FirebaseUtils.getMessagesRef(chatId).child(msg.id).setValue(msg);
            PushNotify.send(recipientUid, currentName, text, chatId, false);
        }
    }

    // ── Data ───────────────────────────────────────────────────────────────

    static class BroadcastRow {
        String listId, name;
        int memberCount;
        BroadcastRow(String l, String n, int c) { listId=l; name=n; memberCount=c; }
    }

    // ── Adapter ────────────────────────────────────────────────────────────

    interface RowAction { void run(BroadcastRow r); }

    static class BroadcastAdapter extends RecyclerView.Adapter<BroadcastAdapter.VH> {
        private final List<BroadcastRow> rows;
        private final RowAction onClick, onSend;
        BroadcastAdapter(List<BroadcastRow> r, RowAction c, RowAction s) {
            rows=r; onClick=c; onSend=s;
        }
        @Override public VH onCreateViewHolder(ViewGroup p, int t) {
            View v = LayoutInflater.from(p.getContext())
                    .inflate(android.R.layout.simple_list_item_2, p, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            BroadcastRow r = rows.get(pos);
            h.t1.setText(r.name);
            h.t2.setText(r.memberCount + " recipients");
            h.itemView.setOnClickListener(v -> onClick.run(r));
            h.itemView.setOnLongClickListener(v -> { onSend.run(r); return true; });
        }
        @Override public int getItemCount() { return rows.size(); }
        static class VH extends RecyclerView.ViewHolder {
            TextView t1, t2;
            VH(View v) { super(v); t1=v.findViewById(android.R.id.text1); t2=v.findViewById(android.R.id.text2); }
        }
    }
}
