package com.callx.app.broadcast;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.callx.app.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BroadcastChatActivity — Message composer for a broadcast list.
 *
 * When a message is sent here:
 *   1. Saved to broadcast_messages/{listId}/{msgId}
 *   2. Delivered to each recipient's personal chat (chats/{chatId}/messages)
 *   3. Recipient's contact node updated (lastMessage, unread++)
 *   4. FCM push notification sent to each recipient via PushNotify
 *
 * Recipients receive the message as a normal 1-on-1 message from the sender —
 * they cannot see who else got it (same as WhatsApp Broadcast).
 */
public class BroadcastChatActivity extends AppCompatActivity {

    private RecyclerView         rvMessages;
    private EditText             etMessage;
    private ImageButton          btnSend;
    private ImageButton          btnAttach;
    private TextView             tvSubtitle;

    private BroadcastMsgAdapter  adapter;
    private final List<BroadcastMessage> messages = new ArrayList<>();

    private String listId;
    private String listName;
    private String myUid;
    private String myName;
    private String myPhoto;

    // Recipient cache
    private final Map<String, RecipientInfo> recipients = new HashMap<>();

    private DatabaseReference msgRef;
    private DatabaseReference listRef;
    private ValueEventListener msgListener;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_broadcast_chat);

        listId   = getIntent().getStringExtra(BroadcastListsActivity.EXTRA_LIST_ID);
        listName = getIntent().getStringExtra(BroadcastListsActivity.EXTRA_LIST_NAME);

        if (listId == null) { finish(); return; }

        myUid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (myUid == null) { finish(); return; }

        // Toolbar
        androidx.appcompat.widget.Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(listName != null ? listName : "Broadcast");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        tb.setNavigationOnClickListener(v -> finish());

        tvSubtitle = findViewById(R.id.tv_broadcast_subtitle);

        rvMessages = findViewById(R.id.rv_broadcast_messages);
        etMessage  = findViewById(R.id.et_broadcast_message);
        btnSend    = findViewById(R.id.btn_broadcast_send);
        btnAttach  = findViewById(R.id.btn_broadcast_attach);

        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BroadcastMsgAdapter(messages);
        rvMessages.setAdapter(adapter);

        msgRef  = FirebaseDatabase.getInstance()
                .getReference("broadcast_messages").child(listId);
        listRef = FirebaseDatabase.getInstance()
                .getReference("broadcast_lists").child(myUid).child(listId);

        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                btnSend.setEnabled(!s.toString().trim().isEmpty());
            }
        });
        btnSend.setEnabled(false);
        btnSend.setOnClickListener(v -> sendTextMessage());
        btnAttach.setOnClickListener(v -> showAttachOptions());

        loadMyProfile();
        loadRecipients();
        attachMessageListener();
    }

    // ── Load sender profile ───────────────────────────────────────────────────
    private void loadMyProfile() {
        FirebaseUtils.getUserRef(myUid).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        myName  = snap.child("name").getValue(String.class);
                        myPhoto = snap.child("photoUrl").getValue(String.class);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    // ── Load recipients list + count subtitle ─────────────────────────────────
    private void loadRecipients() {
        listRef.child("recipients").addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        recipients.clear();
                        long count = snap.getChildrenCount();
                        tvSubtitle.setText("📢 " + count + " recipients");

                        // Fetch each recipient's name & FCM token for delivery
                        for (DataSnapshot r : snap.getChildren()) {
                            String uid = r.getKey();
                            if (uid == null) continue;
                            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(
                                    new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot us) {
                                            String name  = us.child("name").getValue(String.class);
                                            String photo = us.child("photoUrl").getValue(String.class);
                                            String token = us.child("fcmToken").getValue(String.class);
                                            recipients.put(uid, new RecipientInfo(uid,
                                                    name  != null ? name  : "User",
                                                    photo != null ? photo : "",
                                                    token != null ? token : ""));
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                                    });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    // ── Listen to broadcast message history ───────────────────────────────────
    private void attachMessageListener() {
        msgListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                messages.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    BroadcastMessage m = c.getValue(BroadcastMessage.class);
                    if (m != null) {
                        if (m.id == null) m.id = c.getKey();
                        messages.add(m);
                    }
                }
                adapter.notifyDataSetChanged();
                if (!messages.isEmpty())
                    rvMessages.scrollToPosition(messages.size() - 1);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        msgRef.orderByChild("timestamp").addValueEventListener(msgListener);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Send message
    // ─────────────────────────────────────────────────────────────────────────

    private void sendTextMessage() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        etMessage.setText("");
        dispatchBroadcast(text, "text", null, null, null);
    }

    private void showAttachOptions() {
        new AlertDialog.Builder(this)
                .setTitle("Attachment bhejo")
                .setItems(new CharSequence[]{"📷 Image", "🎥 Video", "📄 Document"},
                        (dialog, which) -> {
                            // In a full build, each opens a picker.
                            // Here we show a placeholder toast — ChatActivity ke
                            // actual pickers yahan wire karo apni codebase se.
                            Toast.makeText(this,
                                    "ChatActivity ke media picker se integrate karo",
                                    Toast.LENGTH_SHORT).show();
                        })
                .show();
    }

    /**
     * Core broadcast dispatch:
     *   1. Save to broadcast_messages/{listId}
     *   2. For every recipient → write to their personal chat + update unread
     *   3. Send FCM push to each recipient
     *   4. Update broadcast list metadata (lastMessage, sentCount)
     */
    private void dispatchBroadcast(String text, String type,
                                   String mediaUrl, String fileName, String caption) {
        if (recipients.isEmpty()) {
            Toast.makeText(this,
                    "Recipients load ho rahe hain, thoda intezaar karo",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        long now    = System.currentTimeMillis();
        String msgId = msgRef.push().getKey();
        if (msgId == null) return;

        BroadcastMessage bm = new BroadcastMessage(
                msgId, text, type, mediaUrl, fileName, caption,
                myUid, now, recipients.size());

        // 1. Save broadcast message record
        msgRef.child(msgId).setValue(bm);

        // 2. Deliver to each recipient's personal chat
        executor.execute(() -> {
            int delivered = 0;
            for (RecipientInfo r : recipients.values()) {
                try {
                    deliverToRecipient(r, text, type, mediaUrl, fileName, caption, now, msgId);
                    delivered++;
                } catch (Exception e) {
                    android.util.Log.w("BroadcastChat",
                            "Delivery failed to " + r.uid, e);
                }
            }
            final int finalDelivered = delivered;

            // 3. Update delivered count in broadcast message
            msgRef.child(msgId).child("deliveredCount").setValue(finalDelivered);

            // 4. Update list metadata
            Map<String, Object> listUpdate = new HashMap<>();
            listUpdate.put("lastMessage",     "text".equals(type) ? text : getTypeLabel(type));
            listUpdate.put("lastMessageType", type);
            listUpdate.put("lastMessageTime", now);
            listRef.updateChildren(listUpdate);
            listRef.child("sentCount").setValue(ServerValue.increment(1));

            runOnUiThread(() ->
                    Toast.makeText(this,
                            "✓ " + finalDelivered + "/" + recipients.size() + " recipients ko deliver hua",
                            Toast.LENGTH_SHORT).show());
        });
    }

    /**
     * Deliver one message to one recipient's personal chat.
     *
     * Uses the same Firebase schema as ChatActivity:
     *   chats/{chatId}/messages/{msgId}
     *   contacts/{recipientUid}/{myUid}/lastMessage …
     *   contacts/{myUid}/{recipientUid}/lastMessage …
     */
    private void deliverToRecipient(RecipientInfo r, String text, String type,
                                    String mediaUrl, String fileName, String caption,
                                    long timestamp, String broadcastMsgId) {

        // chatId = sorted concat of the two UIDs (same as ChatActivity)
        String chatId = myUid.compareTo(r.uid) < 0
                ? myUid + "_" + r.uid
                : r.uid + "_" + myUid;

        DatabaseReference db = FirebaseDatabase.getInstance().getReference();

        // ── Build message payload (matches ChatActivity schema) ──────────────
        Map<String, Object> msg = new HashMap<>();
        msg.put("messageId",        broadcastMsgId);
        msg.put("senderId",         myUid);
        msg.put("text",             text   != null ? text   : "");
        msg.put("type",             type   != null ? type   : "text");
        msg.put("mediaUrl",         mediaUrl != null ? mediaUrl : "");
        msg.put("fileName",         fileName != null ? fileName : "");
        msg.put("caption",          caption != null ? caption : "");
        msg.put("timestamp",        timestamp);
        msg.put("seen",             false);
        msg.put("broadcast",        true);   // flag so recipient can see 📢 icon

        // ── Write message into chat ──────────────────────────────────────────
        db.child("chats").child(chatId).child("messages")
                .child(broadcastMsgId).setValue(msg);

        // ── Update sender's contact node for recipient ───────────────────────
        String lastPreview = "text".equals(type) ? text : getTypeLabel(type);
        db.child("contacts").child(myUid).child(r.uid).updateChildren(
                buildLastMsgUpdate(r.name, r.photoUrl, lastPreview, type, timestamp));

        // ── Update recipient's contact node for sender ───────────────────────
        Map<String, Object> recipientNode = buildLastMsgUpdate(
                myName  != null ? myName  : "User",
                myPhoto != null ? myPhoto : "",
                lastPreview, type, timestamp);
        recipientNode.put("unread", ServerValue.increment(1));
        db.child("contacts").child(r.uid).child(myUid).updateChildren(recipientNode);

        // ── Send FCM push to recipient ───────────────────────────────────────
        if (!r.fcmToken.isEmpty()) {
            sendFcmPush(r, text, type, chatId);
        }
    }

    private Map<String, Object> buildLastMsgUpdate(String name, String photo,
                                                   String lastMsg, String type, long ts) {
        Map<String, Object> m = new HashMap<>();
        m.put("name",           name);
        m.put("photoUrl",       photo);
        m.put("lastMessage",    lastMsg);
        m.put("lastMessageType", type);
        m.put("lastMessageTime", ts);
        return m;
    }

    /**
     * Send FCM notification via PushNotify (same as ChatActivity uses).
     * We call the static helper via reflection so the broadcast module
     * doesn't create a hard compile-time dependency on the chat module.
     */
    private void sendFcmPush(RecipientInfo r, String text, String type, String chatId) {
        try {
            Class<?> cls = Class.forName("com.callx.app.utils.PushNotify");
            java.lang.reflect.Method method = cls.getMethod(
                    "notifyMessage",
                    Context.class, String.class, String.class,
                    String.class, String.class, String.class);
            String preview = "text".equals(type) ? text : "📢 " + getTypeLabel(type);
            method.invoke(null, getApplicationContext(),
                    r.fcmToken,
                    myName  != null ? myName  : "User",
                    preview,
                    myUid,
                    chatId);
        } catch (Exception ex) {
            // PushNotify signature differs — log and continue; message is already delivered
            android.util.Log.w("BroadcastChat",
                    "FCM push skipped for " + r.uid + ": " + ex.getMessage());
        }
    }

    private String getTypeLabel(String type) {
        if (type == null) return "Message";
        switch (type) {
            case "image":  return "📷 Photo";
            case "video":  return "🎥 Video";
            case "audio":  return "🎤 Voice Message";
            case "file":   return "📄 Document";
            default:       return "Message";
        }
    }

    // ── Options menu ──────────────────────────────────────────────────────────
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_broadcast_chat, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_broadcast_edit) {
            Intent i = new Intent(this, CreateBroadcastActivity.class);
            i.putExtra(BroadcastListsActivity.EXTRA_LIST_ID,   listId);
            i.putExtra(BroadcastListsActivity.EXTRA_LIST_NAME, listName);
            startActivity(i);
            return true;
        }
        if (id == R.id.action_broadcast_info) {
            showRecipientsInfo();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showRecipientsInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(recipients.size()).append(" recipients:\n\n");
        for (RecipientInfo r : recipients.values()) sb.append("• ").append(r.name).append("\n");
        new AlertDialog.Builder(this)
                .setTitle("📢 " + listName)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (msgRef != null && msgListener != null)
            msgRef.removeEventListener(msgListener);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecipientInfo helper
    // ─────────────────────────────────────────────────────────────────────────
    static class RecipientInfo {
        String uid, name, photoUrl, fcmToken;
        RecipientInfo(String uid, String name, String photoUrl, String fcmToken) {
            this.uid      = uid;
            this.name     = name;
            this.photoUrl = photoUrl;
            this.fcmToken = fcmToken;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message Adapter (sent messages log)
    // ─────────────────────────────────────────────────────────────────────────
    static class BroadcastMsgAdapter
            extends RecyclerView.Adapter<BroadcastMsgAdapter.VH> {

        private final List<BroadcastMessage> data;
        BroadcastMsgAdapter(List<BroadcastMessage> data) { this.data = data; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_broadcast_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BroadcastMessage m = data.get(pos);

            // Message preview text
            String preview;
            if ("text".equals(m.type) || m.type == null) {
                preview = m.text != null ? m.text : "";
            } else {
                preview = typeIcon(m.type) + (m.caption != null && !m.caption.isEmpty()
                        ? " " + m.caption : " " + typeName(m.type));
            }
            h.tvText.setText(preview);

            // Delivery status
            h.tvDelivery.setText("📢 " + m.deliveredCount + "/" + m.totalRecipients);

            // Time
            h.tvTime.setText(new SimpleDateFormat("h:mm a", Locale.getDefault())
                    .format(new Date(m.timestamp)));
        }

        private String typeIcon(String type) {
            switch (type) {
                case "image": return "📷";
                case "video": return "🎥";
                case "audio": return "🎤";
                case "file":  return "📄";
                default:      return "💬";
            }
        }

        private String typeName(String type) {
            switch (type) {
                case "image": return "Photo";
                case "video": return "Video";
                case "audio": return "Voice";
                case "file":  return "Document";
                default:      return "Message";
            }
        }

        @Override public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvText, tvDelivery, tvTime;
            VH(@NonNull View v) {
                super(v);
                tvText     = v.findViewById(R.id.tv_broadcast_msg_text);
                tvDelivery = v.findViewById(R.id.tv_broadcast_delivery);
                tvTime     = v.findViewById(R.id.tv_broadcast_msg_time);
            }
        }
    }
}
