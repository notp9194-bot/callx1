package com.callx.app.group;

import android.os.Bundle;
import androidx.annotation.NonNull;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.MessageEntity;
import com.callx.app.models.Message;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GroupTopicChatActivity — Dedicated chat screen for a single Group Topic / Thread.
 *
 * Messages are filtered from the group's messages node by topicId.
 * Sending a message attaches topicId + topicName to the Message object.
 *
 * Features:
 *  - Real-time message stream for this topic only
 *  - Closed topic banner (read-only if non-admin)
 *  - Reply-to support
 *  - Anonymous posting toggle (if group has it enabled)
 *  - Send text messages
 *  - Simple RecyclerView with sent/received bubbles
 *  - Auto-scroll to bottom on new message
 */
public class GroupTopicChatActivity extends AppCompatActivity {

    public static final String EXTRA_GROUP_ID    = "groupId";
    public static final String EXTRA_TOPIC_ID    = "topicId";
    public static final String EXTRA_TOPIC_NAME  = "topicName";
    public static final String EXTRA_TOPIC_EMOJI = "topicEmoji";
    public static final String EXTRA_TOPIC_CLOSED = "topicClosed";

    private String groupId, topicId, topicName, topicEmoji, currentUid, currentName;
    private boolean topicClosed = false;
    private boolean isAdmin     = false;
    private boolean anonymousPostingEnabled = false;
    private boolean postAnonymously = false;

    private RecyclerView rv;
    private EditText etMessage;
    private ImageButton btnSend, btnAnon;
    private TextView tvTopicClosed, tvAnonStatus;
    private View closedBanner;

    private final List<Message> messages = new ArrayList<>();
    private TopicMessageAdapter adapter;
    private DatabaseReference groupMsgRef;
    private ChildEventListener msgListener;
    private AppDatabase db;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_topic_chat);

        groupId   = getIntent().getStringExtra(EXTRA_GROUP_ID);
        topicId   = getIntent().getStringExtra(EXTRA_TOPIC_ID);
        topicName = getIntent().getStringExtra(EXTRA_TOPIC_NAME);
        topicEmoji = getIntent().getStringExtra(EXTRA_TOPIC_EMOJI);
        topicClosed = getIntent().getBooleanExtra(EXTRA_TOPIC_CLOSED, false);

        if (groupId == null || topicId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid  = FirebaseUtils.getCurrentUid();
        currentName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
        if (currentName == null) currentName = "User";

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String emoji = TextUtils.isEmpty(topicEmoji) ? "💬" : topicEmoji;
            getSupportActionBar().setTitle(emoji + " " + topicName);
        }

        rv          = findViewById(R.id.rv_topic_messages);
        etMessage   = findViewById(R.id.et_message);
        btnSend     = findViewById(R.id.btn_send);
        btnAnon     = findViewById(R.id.btn_anon);
        closedBanner = findViewById(R.id.banner_topic_closed);
        tvAnonStatus = findViewById(R.id.tv_anon_status);

        adapter = new TopicMessageAdapter(currentUid);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        groupMsgRef = FirebaseUtils.getGroupMessagesRef(groupId);
        db = AppDatabase.getInstance(getApplicationContext());

        checkAdminStatus();
        loadAnonymousSetting();
        setupSendButton();
        restoreTopicDraft();
        listenMessages();
        updateClosedBanner();
    }

    private void checkAdminStatus() {
        FirebaseUtils.getGroupsRef().child(groupId).child("admins").child(currentUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        isAdmin = Boolean.TRUE.equals(snap.getValue(Boolean.class));
                        updateClosedBanner();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void loadAnonymousSetting() {
        FirebaseUtils.getGroupsRef().child(groupId).child("groupSettings")
                .child("anonymousPostingEnabled")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        Boolean val = snap.getValue(Boolean.class);
                        anonymousPostingEnabled = Boolean.TRUE.equals(val);
                        btnAnon.setVisibility(anonymousPostingEnabled ? View.VISIBLE : View.GONE);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void updateClosedBanner() {
        boolean canPost = !topicClosed || isAdmin;
        if (closedBanner != null)
            closedBanner.setVisibility(topicClosed ? View.VISIBLE : View.GONE);
        etMessage.setEnabled(canPost);
        btnSend.setEnabled(canPost);
        if (!canPost) {
            etMessage.setHint("This topic is closed");
        }
    }

    private void setupSendButton() {
        btnSend.setOnClickListener(v -> sendMessage());
        etMessage.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                sendMessage(); return true;
            }
            return false;
        });
        btnAnon.setOnClickListener(v -> {
            postAnonymously = !postAnonymously;
            btnAnon.setAlpha(postAnonymously ? 1.0f : 0.4f);
            tvAnonStatus.setVisibility(postAnonymously ? View.VISIBLE : View.GONE);
            Toast.makeText(this, postAnonymously ? "Posting anonymously" : "Posting as yourself", Toast.LENGTH_SHORT).show();
        });
    }

    private void listenMessages() {
        loadCachedMessages();
        msgListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot snap, String prev) {
                Message m = snap.getValue(Message.class);
                if (m == null) return;
                if (m.id == null) m.id = snap.getKey();
                if (topicId.equals(m.topicId)) {
                    m.isGroup = true;
                    persistMessage(m);
                    upsertMessage(m, true);
                }
            }
            @Override public void onChildChanged(DataSnapshot snap, String prev) {
                Message m = snap.getValue(Message.class);
                if (m == null) return;
                if (m.id == null) m.id = snap.getKey();
                if (!topicId.equals(m.topicId)) return;
                m.isGroup = true;
                persistMessage(m);
                upsertMessage(m, false);
            }
            @Override public void onChildRemoved(DataSnapshot snap) {
                String messageId = snap.getKey();
                if (messageId == null) return;
                dbExecutor.execute(() -> db.messageDao().softDelete(messageId));
                int index = findMessage(messageId);
                if (index >= 0) {
                    messages.get(index).deleted = true;
                    messages.get(index).text = "";
                    adapter.notifyItemChanged(index);
                }
            }
            @Override public void onChildMoved(DataSnapshot snap, String prev) {}
            @Override public void onCancelled(DatabaseError e) {}
        };
        groupMsgRef.orderByChild("topicId").equalTo(topicId)
                .limitToLast(100)
                .addChildEventListener(msgListener);
    }

    private void loadCachedMessages() {
        dbExecutor.execute(() -> {
            List<MessageEntity> cached = db.messageDao()
                    .getTopicMessages(groupId, topicId, 100);
            List<Message> models = new ArrayList<>(cached.size());
            for (MessageEntity entity : cached) {
                Message model = com.callx.app.utils.MessageEntityMapper.toModel(entity);
                if (model != null) {
                    model.isGroup = true;
                    models.add(model);
                }
            }
            runOnUiThread(() -> {
                for (Message model : models) {
                    if (findMessage(model.id) < 0) {
                        messages.add(insertionIndex(model), model);
                    }
                }
                if (!models.isEmpty()) {
                    adapter.notifyDataSetChanged();
                    rv.scrollToPosition(messages.size() - 1);
                }
            });
        });
    }

    private void persistMessage(Message message) {
        if (message == null || message.id == null) return;
        dbExecutor.execute(() -> db.messageDao().insertMessage(
                com.callx.app.utils.MessageEntityMapper.fromModel(message, groupId)));
    }

    private int findMessage(String messageId) {
        if (messageId == null) return -1;
        for (int i = 0; i < messages.size(); i++) {
            Message item = messages.get(i);
            if (messageId.equals(item.id) || messageId.equals(item.messageId)) return i;
        }
        return -1;
    }

    private void upsertMessage(Message message, boolean scrollToEnd) {
        int existing = findMessage(message.id != null ? message.id : message.messageId);
        if (existing >= 0) {
            messages.set(existing, message);
            adapter.notifyItemChanged(existing);
        } else {
            int insertAt = insertionIndex(message);
            messages.add(insertAt, message);
            adapter.notifyItemInserted(insertAt);
        }
        if (scrollToEnd && !messages.isEmpty()) {
            rv.scrollToPosition(messages.size() - 1);
        }
    }

    private int insertionIndex(Message message) {
        long timestamp = message.timestamp != null ? message.timestamp : 0L;
        for (int i = 0; i < messages.size(); i++) {
            Message existing = messages.get(i);
            long existingTimestamp = existing.timestamp != null ? existing.timestamp : 0L;
            if (existingTimestamp > timestamp) return i;
        }
        return messages.size();
    }

    private void sendMessage() {
        String text = etMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        if (topicClosed && !isAdmin) {
            Toast.makeText(this, "This topic is closed", Toast.LENGTH_SHORT).show();
            return;
        }
        etMessage.setText("");
        com.callx.app.utils.DraftStore.clear(this, topicDraftKey());
        Message m     = new Message();
        m.senderId    = currentUid;
        m.senderName  = postAnonymously ? "Anonymous" : currentName;
        m.senderPhoto = postAnonymously ? null : (FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getPhotoUrl().toString() : null);
        m.isAnonymous = postAnonymously;
        m.text        = text;
        m.type        = "text";
        m.topicId     = topicId;
        m.topicName   = topicName;
        m.isGroup     = true;
        m.timestamp   = System.currentTimeMillis();
        m.status      = "sent";

        DatabaseReference ref = groupMsgRef.push();
        m.id = m.messageId = ref.getKey();
        persistMessage(m);
        upsertMessage(m, true);
        ref.setValue(m);

        // Bump topic's lastMessage + messageCount
        Map<String, Object> topicUpdates = new HashMap<>();
        topicUpdates.put("lastMessage", text.length() > 50 ? text.substring(0, 50) + "…" : text);
        topicUpdates.put("lastMessageAt", m.timestamp);
        topicUpdates.put("lastSenderName", m.senderName);
        FirebaseUtils.getGroupsRef().child(groupId).child("topics")
                .child(topicId).updateChildren(topicUpdates);
        FirebaseUtils.getGroupsRef().child(groupId).child("topics")
                .child(topicId).child("messageCount")
                .runTransaction(new Transaction.Handler() {
                    @NonNull @Override public Transaction.Result doTransaction(@NonNull MutableData d) {
                        Long count = d.getValue(Long.class);
                        d.setValue(count == null ? 1 : count + 1);
                        return Transaction.success(d);
                    }
                    @Override public void onComplete(DatabaseError e, boolean committed, DataSnapshot s) {}
                });
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveTopicDraft();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveTopicDraft();
        if (msgListener != null) groupMsgRef.removeEventListener(msgListener);
        dbExecutor.shutdown();
    }

    // ── Draft persistence (SharedPreferences-backed — see DraftStore) ──────
    private String topicDraftKey() {
        return "topic_" + groupId + "_" + topicId;
    }

    private void saveTopicDraft() {
        if (etMessage == null) return;
        com.callx.app.utils.DraftStore.save(this, topicDraftKey(), etMessage.getText().toString());
    }

    private void restoreTopicDraft() {
        String draft = com.callx.app.utils.DraftStore.get(this, topicDraftKey());
        if (draft != null && !draft.isEmpty()) {
            etMessage.setText(draft);
            etMessage.setSelection(draft.length());
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { onBackPressed(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // ── Simple inline adapter ─────────────────────────────────────────────
    private class TopicMessageAdapter extends RecyclerView.Adapter<TopicMessageAdapter.VH> {
        private static final int VIEW_SENT = 0, VIEW_RECV = 1;
        private final String myUid;
        TopicMessageAdapter(String uid) { myUid = uid; }

        @Override public int getItemViewType(int pos) {
            String sid = messages.get(pos).senderId;
            return myUid.equals(sid) && !(Boolean.TRUE.equals(messages.get(pos).isAnonymous)) ? VIEW_SENT : VIEW_RECV;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int layout = viewType == VIEW_SENT
                    ? R.layout.item_message_sent : R.layout.item_message_received;
            View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) { h.bind(messages.get(pos)); }
        @Override public int getItemCount() { return messages.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvText, tvSender, tvTime;
            VH(View v) {
                super(v);
                tvText   = v.findViewById(R.id.tv_message_text);
                tvSender = v.findViewById(R.id.tv_sender_name);
                tvTime   = v.findViewById(R.id.tv_message_time);
            }
            void bind(Message m) {
                if (tvText   != null) tvText.setText(m.text);
                if (tvSender != null) {
                    tvSender.setText(m.isAnonymous ? "Anonymous" : m.senderName);
                    tvSender.setVisibility(View.VISIBLE);
                }
                if (tvTime != null && m.timestamp != null && m.timestamp > 0) {
                    tvTime.setText(new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                            .format(new java.util.Date(m.timestamp)));
                }
            }
        }
    }
}
