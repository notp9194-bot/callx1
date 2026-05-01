package com.callx.app.activities;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.adapters.MessageAdapter;
import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Feature 7: Starred Messages screen.
 * Shows all messages where starred == true from the given chat or group.
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
    @Override public void onCopy(Message m) {
        if (m.text == null || m.text.isEmpty()) return;
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", m.text));
            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show();
        }
    }

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
