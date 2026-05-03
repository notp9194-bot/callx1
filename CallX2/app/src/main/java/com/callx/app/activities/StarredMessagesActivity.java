package com.callx.app.activities;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.adapters.MessageAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Feature 7: Starred Messages screen.
 * Shows all messages where starred == true from the given chat or group.
 *
 * OFFLINE FIX: Room se pehle load karo, Firebase secondary source hai.
 */
public class StarredMessagesActivity extends AppCompatActivity
        implements MessageAdapter.ActionListener {

    private RecyclerView rv;
    private TextView     tvEmpty;
    private MessageAdapter adapter;
    private final List<Message> starred = new ArrayList<>();

    private String  chatId;
    private boolean isGroup;
    private String  currentUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_starred_messages);

        chatId   = getIntent().getStringExtra("chatId");
        isGroup  = getIntent().getBooleanExtra("isGroup", false);
        if (chatId == null
                || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // Toolbar
        if (findViewById(R.id.toolbar) != null) {
            androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
            setSupportActionBar(tb);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle("Starred Messages");
            }
            tb.setNavigationOnClickListener(v -> finish());
        }

        rv      = findViewById(R.id.rv_starred);
        tvEmpty = findViewById(R.id.tv_empty);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        rv.setLayoutManager(lm);
        adapter = new MessageAdapter(starred, currentUid, isGroup);
        adapter.setActionListener(this);
        rv.setAdapter(adapter);

        // OFFLINE FIX: Step 1 — Room se turant load karo (zero latency)
        loadFromRoom();

        // Step 2 — Firebase se sync karo agar online hain
        if (isOnline()) {
            loadFromFirebase();
        }
    }

    // ── OFFLINE FIX: Room se starred messages load karo ──────────────────
    private void loadFromRoom() {
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            // MessageDao.getStarredMessages() — chatId filter nahi (sab starred)
            // Isliye chatId se manually filter karte hain
            List<MessageEntity> all = db.messageDao().getStarredMessagesSync();
            if (all == null || all.isEmpty()) return;

            List<Message> roomStarred = new ArrayList<>();
            for (MessageEntity e : all) {
                if (!chatId.equals(e.chatId)) continue; // sirf is chat ke
                if (!Boolean.TRUE.equals(e.starred))    continue;
                Message m = new Message();
                m.id         = e.id;
                m.senderId   = e.senderId;
                m.senderName = e.senderName;
                m.text       = e.text;
                m.type       = e.type;
                m.mediaUrl   = e.mediaUrl;
                m.thumbnailUrl = e.thumbnailUrl;
                m.fileName   = e.fileName;
                m.fileSize   = e.fileSize;
                m.duration   = e.duration;
                m.timestamp  = e.timestamp;
                m.starred    = true;
                m.isGroup    = Boolean.TRUE.equals(e.isGroup);
                roomStarred.add(m);
            }
            if (roomStarred.isEmpty()) return;

            // Sort: oldest → newest
            roomStarred.sort((a, b) -> {
                long ta = a.timestamp != null ? a.timestamp : 0L;
                long tb = b.timestamp != null ? b.timestamp : 0L;
                return Long.compare(ta, tb);
            });

            runOnUiThread(() -> {
                if (starred.isEmpty()) { // sirf tab dikhao agar Firebase ne abhi kuch nahi diya
                    starred.addAll(roomStarred);
                    adapter.notifyDataSetChanged();
                    tvEmpty.setVisibility(starred.isEmpty() ? View.VISIBLE : View.GONE);
                    rv.setVisibility(starred.isEmpty() ? View.GONE : View.VISIBLE);
                }
            });
        });
    }

    // ── Firebase se load karo (online only) ──────────────────────────────
    private void loadFromFirebase() {
        DatabaseReference ref = isGroup
                ? FirebaseUtils.getGroupMessagesRef(chatId)
                : FirebaseUtils.getMessagesRef(chatId);

        ref.orderByChild("starred").equalTo(true)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        starred.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            Message m = c.getValue(Message.class);
                            if (m == null) continue;
                            if (m.id == null) m.id = c.getKey();
                            if (Boolean.TRUE.equals(m.starred))
                                starred.add(m);
                        }
                        // Sort by timestamp ascending
                        starred.sort((a, b) -> {
                            long ta = a.timestamp != null ? a.timestamp : 0L;
                            long tb2 = b.timestamp != null ? b.timestamp : 0L;
                            return Long.compare(ta, tb2);
                        });
                        adapter.notifyDataSetChanged();
                        tvEmpty.setVisibility(starred.isEmpty() ? View.VISIBLE : View.GONE);
                        rv.setVisibility(starred.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        // Firebase fail — Room data already showing, no action needed
                        if (starred.isEmpty()) {
                            Toast.makeText(StarredMessagesActivity.this,
                                    "Offline — cached starred messages dikh rahe hain",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ── ActionListener stubs (no actions needed in a read-only starred list) ──
    @Override public void onReply(Message m)                  {}
    @Override public void onEdit(Message m)                   {}
    @Override public void onForward(Message m)                {}
    @Override public void onReact(Message m, String emoji)    {}
    @Override public void onReactionTap(Message m)            {}
    @Override public void onPin(Message m)                    {}
    @Override public void onCopy(Message m)                   {}
    @Override public void onInfo(Message m)                   {}

    /**
     * Tap delete → unstar (removes from this list).
     */
    @Override public void onDelete(Message m)                 { onStar(m); }

    @Override public void onStar(Message m) {
        // Update Firebase (if online)
        if (isOnline()) {
            DatabaseReference ref = isGroup
                    ? FirebaseUtils.getGroupMessagesRef(chatId)
                    : FirebaseUtils.getMessagesRef(chatId);
            ref.child(m.id).child("starred").setValue(false);
        }
        // Update Room (always — works offline too)
        Executors.newSingleThreadExecutor().execute(() ->
            AppDatabase.getInstance(getApplicationContext())
                .messageDao().updateStarred(m.id, false));

        Toast.makeText(this, "Message unstarred", Toast.LENGTH_SHORT).show();
    }
}

    private RecyclerView rv;
    private TextView     tvEmpty;
    private MessageAdapter adapter;
    private final List<Message> starred = new ArrayList<>();

    private String  chatId;
    private boolean isGroup;
    private String  currentUid;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_starred_messages);

        chatId   = getIntent().getStringExtra("chatId");
        isGroup  = getIntent().getBooleanExtra("isGroup", false);
        if (chatId == null
                || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        // Toolbar
        if (findViewById(R.id.toolbar) != null) {
            androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
            setSupportActionBar(tb);
            if (getSupportActionBar() != null) {
                getSupportActionBar().setDisplayHomeAsUpEnabled(true);
                getSupportActionBar().setTitle("Starred Messages");
            }
            tb.setNavigationOnClickListener(v -> finish());
        }

        rv      = findViewById(R.id.rv_starred);
        tvEmpty = findViewById(R.id.tv_empty);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        rv.setLayoutManager(lm);
        adapter = new MessageAdapter(starred, currentUid, isGroup);
        adapter.setActionListener(this);
        rv.setAdapter(adapter);

        loadStarred();
    }

    private void loadStarred() {
        DatabaseReference ref = isGroup
                ? FirebaseUtils.getGroupMessagesRef(chatId)
                : FirebaseUtils.getMessagesRef(chatId);

        ref.orderByChild("starred").equalTo(true)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        starred.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            Message m = c.getValue(Message.class);
                            if (m == null) continue;
                            if (m.id == null) m.id = c.getKey();
                            if (Boolean.TRUE.equals(m.starred))
                                starred.add(m);
                        }
                        // Sort by timestamp descending (most recently starred last)
                        starred.sort((a, b) -> {
                            long ta = a.timestamp != null ? a.timestamp : 0L;
                            long tb2 = b.timestamp != null ? b.timestamp : 0L;
                            return Long.compare(ta, tb2);
                        });
                        adapter.notifyDataSetChanged();
                        tvEmpty.setVisibility(starred.isEmpty() ? View.VISIBLE : View.GONE);
                        rv.setVisibility(starred.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        Toast.makeText(StarredMessagesActivity.this,
                                "Failed to load starred messages", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    // ── ActionListener stubs (no actions needed in a read-only starred list) ──
    @Override public void onReply(Message m)                  {}
    @Override public void onEdit(Message m)                   {}
    @Override public void onForward(Message m)                {}
    @Override public void onReact(Message m, String emoji)    {}
    @Override public void onReactionTap(Message m)            {}
    @Override public void onPin(Message m)                    {}
    @Override public void onCopy(Message m)                   {}
    @Override public void onInfo(Message m)                   {}

    /**
     * Tap delete → unstar (removes from this list).
     */
    @Override public void onDelete(Message m)                 { onStar(m); }

    @Override public void onStar(Message m) {
        DatabaseReference ref = isGroup
                ? FirebaseUtils.getGroupMessagesRef(chatId)
                : FirebaseUtils.getMessagesRef(chatId);
        ref.child(m.id).child("starred").setValue(false);
        Toast.makeText(this, "Message unstarred", Toast.LENGTH_SHORT).show();
    }
}
