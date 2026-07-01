package com.callx.app.starred;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.paging.PagingData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.conversation.MessagePagingAdapter;
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
 * Uses MessagePagingAdapter (PagingData.from list) for consistent rendering.
 */
public class StarredMessagesActivity extends AppCompatActivity
        implements MessagePagingAdapter.ActionListener {

    private RecyclerView rv;
    private TextView     tvEmpty;
    private MessagePagingAdapter adapter;

    private String  chatId;
    private boolean isGroup;
    private String  currentUid;

    // Local snapshot of the current list — PagingDataAdapter has no getCurrentList()
    private final List<Message> currentList = new ArrayList<>();

    // Last submitted list — guarded so Room doesn't clobber a fresh Firebase result
    private volatile boolean firebaseLoaded = false;

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
        adapter = new MessagePagingAdapter(currentUid, isGroup);
        adapter.setActionListener(this);
        rv.setAdapter(adapter);

        // OFFLINE FIX: Step 1 — Room se turant load karo (zero latency)
        loadFromRoom();

        // Step 2 — Firebase se sync karo agar online hain
        if (isOnline()) {
            loadFromFirebase();
        }
    }

    // ── Submit helpers ────────────────────────────────────────────────────────
    private void submitList(List<Message> list) {
        currentList.clear();
        currentList.addAll(list);
        adapter.submitData(getLifecycle(), PagingData.from(new ArrayList<>(list)));
    }

    // ── OFFLINE FIX: Room se starred messages load karo ──────────────────────
    private void loadFromRoom() {
        AppDatabase db = AppDatabase.getInstance(getApplicationContext());
        Executors.newSingleThreadExecutor().execute(() -> {
            List<MessageEntity> all = db.messageDao().getStarredMessagesSync();
            if (all == null || all.isEmpty()) return;

            List<Message> roomStarred = new ArrayList<>();
            for (MessageEntity e : all) {
                if (!chatId.equals(e.chatId)) continue;
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

            roomStarred.sort((a, b) -> {
                long ta = a.timestamp != null ? a.timestamp : 0L;
                long tb = b.timestamp != null ? b.timestamp : 0L;
                return Long.compare(ta, tb);
            });

            runOnUiThread(() -> {
                if (!firebaseLoaded) {
                    submitList(roomStarred);
                    updateEmptyState(roomStarred.isEmpty());
                }
            });
        });
    }

    // ── Firebase se load karo (online only) ──────────────────────────────────
    private void loadFromFirebase() {
        DatabaseReference ref = isGroup
                ? FirebaseUtils.getGroupMessagesRef(chatId)
                : FirebaseUtils.getMessagesRef(chatId);

        ref.orderByChild("starred").equalTo(true)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        List<Message> fresh = new ArrayList<>();
                        for (DataSnapshot c : snap.getChildren()) {
                            Message m = c.getValue(Message.class);
                            if (m == null) continue;
                            if (m.id == null) m.id = c.getKey();
                            if (Boolean.TRUE.equals(m.starred)) fresh.add(m);
                        }
                        fresh.sort((a, b) -> {
                            long ta = a.timestamp != null ? a.timestamp : 0L;
                            long tb2 = b.timestamp != null ? b.timestamp : 0L;
                            return Long.compare(ta, tb2);
                        });
                        firebaseLoaded = true;
                        submitList(fresh);
                        updateEmptyState(fresh.isEmpty());
                    }
                    @Override public void onCancelled(DatabaseError e) {
                        if (currentList.isEmpty()) {
                            Toast.makeText(StarredMessagesActivity.this,
                                    "Offline — cached starred messages dikh rahe hain",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void updateEmptyState(boolean isEmpty) {
        tvEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rv.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network active = cm.getActiveNetwork();
        if (active == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    // ── ActionListener stubs (read-only starred list) ─────────────────────────
    @Override public void onReply(Message m)                  {}
    @Override public void onNavigateToOriginal(String msgId)  {}
    @Override public void onForward(Message m)                {}
    @Override public void onReact(Message m, String emoji)    {}
    @Override public void onCopy(Message m)                   {}

    /** Tap delete → unstar (removes from this list via DiffUtil on next submit). */
    @Override public void onDelete(Message m) { onStar(m); }

    @Override public void onStar(Message m) {
        if (isOnline()) {
            DatabaseReference ref = isGroup
                    ? FirebaseUtils.getGroupMessagesRef(chatId)
                    : FirebaseUtils.getMessagesRef(chatId);
            ref.child(m.id).child("starred").setValue(false);
        }
        Executors.newSingleThreadExecutor().execute(() ->
            AppDatabase.getInstance(getApplicationContext())
                .messageDao().updateStarred(m.id, false));

        // Remove from local snapshot and re-submit — DiffUtil handles the animation
        currentList.removeIf(msg -> m.id != null && m.id.equals(msg.id));
        adapter.submitData(getLifecycle(), PagingData.from(new ArrayList<>(currentList)));
        updateEmptyState(currentList.isEmpty());

        Toast.makeText(this, "Message unstarred", Toast.LENGTH_SHORT).show();
    }
}
