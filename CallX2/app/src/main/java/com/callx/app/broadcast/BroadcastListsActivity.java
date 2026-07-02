package com.callx.app.broadcast;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * BroadcastListsActivity — Shows all broadcast lists created by the current user.
 *
 * Entry points:
 *   - MainActivity Chats FAB → "New Broadcast" menu item
 *   - Deep link: intent extra "open_tab" = "broadcast"
 *
 * Firebase path: broadcast_lists/{myUid}/{listId}
 */
public class BroadcastListsActivity extends AppCompatActivity {

    public static final String EXTRA_LIST_ID   = "broadcastListId";
    public static final String EXTRA_LIST_NAME = "broadcastListName";

    private RecyclerView          rvLists;
    private View                  tvEmpty;
    private FloatingActionButton  fabNew;

    private BroadcastListAdapter  adapter;
    private final List<BroadcastList> lists = new ArrayList<>();

    private String           myUid;
    private DatabaseReference listsRef;
    private ValueEventListener listsListener;

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_broadcast_lists);

        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Broadcast Lists");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;

        if (myUid == null) { finish(); return; }

        listsRef = FirebaseUtils.db()
                .getReference("broadcast_lists").child(myUid);

        rvLists = findViewById(R.id.rv_broadcast_lists);
        tvEmpty = findViewById(R.id.tv_empty_broadcast);
        fabNew  = findViewById(R.id.fab_new_broadcast);

        rvLists.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BroadcastListAdapter(lists, this::onListClicked, this::onListLongClick);
        rvLists.setAdapter(adapter);

        fabNew.setOnClickListener(v -> openCreateBroadcast(null));

        attachListener();
    }

    // ── Open create screen (null = new, non-null = edit existing) ────────────
    private void openCreateBroadcast(BroadcastList existing) {
        Intent i = new Intent(this, CreateBroadcastActivity.class);
        if (existing != null) {
            i.putExtra(EXTRA_LIST_ID,   existing.id);
            i.putExtra(EXTRA_LIST_NAME, existing.name);
        }
        startActivity(i);
    }

    // ── Open chat screen for this broadcast list ──────────────────────────────
    private void onListClicked(BroadcastList bl) {
        if (bl.recipientCount() == 0) {
            Toast.makeText(this,
                    "Pehle recipients add karo (list ko hold karo → Edit)",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, BroadcastChatActivity.class);
        i.putExtra(EXTRA_LIST_ID,   bl.id);
        i.putExtra(EXTRA_LIST_NAME, bl.name);
        startActivity(i);
    }

    // ── Long-press: edit / delete options ────────────────────────────────────
    private void onListLongClick(BroadcastList bl) {
        new AlertDialog.Builder(this)
                .setTitle(bl.name)
                .setItems(new CharSequence[]{"✏️  Edit List", "🗑️  Delete List"},
                        (dialog, which) -> {
                            if (which == 0) openCreateBroadcast(bl);
                            else            confirmDelete(bl);
                        })
                .show();
    }

    private void confirmDelete(BroadcastList bl) {
        new AlertDialog.Builder(this)
                .setTitle("Delete \"" + bl.name + "\"?")
                .setMessage("Yeh broadcast list delete ho jayegi. Messages delete nahi honge.")
                .setPositiveButton("Delete", (d, w) -> deleteBroadcastList(bl))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteBroadcastList(BroadcastList bl) {
        listsRef.child(bl.id).removeValue()
                .addOnSuccessListener(a ->
                        Toast.makeText(this, "List delete ho gayi", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Delete failed: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    // ── Firebase listener ─────────────────────────────────────────────────────
    private void attachListener() {
        listsListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                lists.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    BroadcastList bl = child.getValue(BroadcastList.class);
                    if (bl != null) {
                        if (bl.id == null) bl.id = child.getKey();
                        lists.add(0, bl); // newest first
                    }
                }
                // Sort by lastMessageTime desc, then createdAt desc
                lists.sort((a, b) -> {
                    long ta = a.lastMessageTime > 0 ? a.lastMessageTime : a.createdAt;
                    long tb2 = b.lastMessageTime > 0 ? b.lastMessageTime : b.createdAt;
                    return Long.compare(tb2, ta);
                });
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(lists.isEmpty() ? View.VISIBLE : View.GONE);
                rvLists.setVisibility(lists.isEmpty() ? View.GONE   : View.VISIBLE);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        listsRef.addValueEventListener(listsListener);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (listsRef != null && listsListener != null)
            listsRef.removeEventListener(listsListener);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inline Adapter
    // ─────────────────────────────────────────────────────────────────────────
    interface OnListClick     { void on(BroadcastList bl); }
    interface OnListLongClick { void on(BroadcastList bl); }

    static class BroadcastListAdapter
            extends RecyclerView.Adapter<BroadcastListAdapter.VH> {

        private final List<BroadcastList> data;
        private final OnListClick         click;
        private final OnListLongClick     longClick;

        BroadcastListAdapter(List<BroadcastList> data,
                             OnListClick click,
                             OnListLongClick longClick) {
            this.data      = data;
            this.click     = click;
            this.longClick = longClick;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_broadcast_list, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BroadcastList bl = data.get(pos);
            h.tvName.setText(bl.name != null ? bl.name : "Broadcast List");
            h.tvCount.setText(bl.recipientCount() + " recipients");

            String lastMsg = bl.lastMessage;
            if (lastMsg == null || lastMsg.isEmpty()) {
                lastMsg = "Abhi tak koi message nahi bheja";
            } else {
                // Prefix icon for media types
                if ("image".equals(bl.lastMessageType))       lastMsg = "📷 " + lastMsg;
                else if ("video".equals(bl.lastMessageType))  lastMsg = "🎥 " + lastMsg;
                else if ("audio".equals(bl.lastMessageType))  lastMsg = "🎤 " + lastMsg;
                else if ("file".equals(bl.lastMessageType))   lastMsg = "📄 " + lastMsg;
            }
            h.tvLastMsg.setText(lastMsg);

            if (bl.lastMessageTime > 0) {
                h.tvTime.setVisibility(View.VISIBLE);
                h.tvTime.setText(formatTime(bl.lastMessageTime));
            } else {
                h.tvTime.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(v -> click.on(bl));
            h.itemView.setOnLongClickListener(v -> { longClick.on(bl); return true; });
        }

        private String formatTime(long ts) {
            long now = System.currentTimeMillis();
            long diff = now - ts;
            if (diff < 60_000)           return "Abhi";
            if (diff < 3_600_000)        return (diff / 60_000) + "m";
            if (diff < 86_400_000)       return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(ts));
            return new SimpleDateFormat("dd MMM", Locale.getDefault()).format(new Date(ts));
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvCount, tvLastMsg, tvTime;
            VH(@NonNull View v) {
                super(v);
                tvName    = v.findViewById(R.id.tv_broadcast_name);
                tvCount   = v.findViewById(R.id.tv_recipient_count);
                tvLastMsg = v.findViewById(R.id.tv_last_message);
                tvTime    = v.findViewById(R.id.tv_last_time);
            }
        }
    }
}
