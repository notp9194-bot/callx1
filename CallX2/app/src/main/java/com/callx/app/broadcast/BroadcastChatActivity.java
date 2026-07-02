package com.callx.app.broadcast;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
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
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BroadcastChatActivity — Message composer for a broadcast list.
 *
 * When a message is sent here:
 *   1. Saved to broadcast_messages/{listId}/{msgId} with status="sending"
 *   2. BroadcastDeliveryWorker (WorkManager) does the actual fan-out —
 *      survives app kill, retries with backoff, delivers atomically, and
 *      skips recipients who have blocked the sender.
 *   3. This screen watches each recipient's copy of the message for the
 *      "seen" flag and rolls that up into seenCount on the broadcast record.
 *
 * Recipients receive the message as a normal 1-on-1 message from the sender —
 * they cannot see who else got it (same as WhatsApp Broadcast).
 */
public class BroadcastChatActivity extends AppCompatActivity {

    private static final int MAX_SEEN_LISTENERS = 20; // bound resource usage

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

    // Recipient cache (uid → RecipientInfo)
    private final Map<String, RecipientInfo> recipients = new HashMap<>();

    private DatabaseReference msgRef;
    private DatabaseReference listRef;
    private ValueEventListener msgListener;

    // Seen-tracking: msgId+recipientUid → listener, so we can detach cleanly
    private final Map<String, ValueEventListener> seenListeners = new HashMap<>();

    // ── Media pickers ────────────────────────────────────────────────────────
    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<String> videoPicker;
    private ActivityResultLauncher<String> docPicker;
    private ActivityResultLauncher<String> audioPicker;
    private AlertDialog uploadDialog;

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

        registerPickers();

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
        adapter = new BroadcastMsgAdapter(messages, this::onMessageTapped);
        rvMessages.setAdapter(adapter);

        // Path: broadcast_messages/{ownerUid}/{listId} — owner-scoped so Firebase
        // security rules can enforce auth.uid === ownerUid without ambiguity.
        msgRef  = FirebaseDatabase.getInstance()
                .getReference("broadcast_messages").child(myUid).child(listId);
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

        loadRecipients();
        attachMessageListener();
    }

    // ── Register media pickers ──────────────────────────────────────────────
    private void registerPickers() {
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image", "image"); });
        videoPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "video", "video"); });
        docPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "file", "raw"); });
        audioPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "audio", "raw"); });
    }

    // ── Load recipients list + count subtitle ─────────────────────────────────
    private void loadRecipients() {
        listRef.child("recipients").addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        recipients.clear();
                        long count = snap.getChildrenCount();
                        tvSubtitle.setText("📢 " + count + " recipients");

                        for (DataSnapshot r : snap.getChildren()) {
                            String uid = r.getKey();
                            if (uid == null) continue;
                            FirebaseUtils.getUserRef(uid).addListenerForSingleValueEvent(
                                    new ValueEventListener() {
                                        @Override public void onDataChange(@NonNull DataSnapshot us) {
                                            String name = us.child("name").getValue(String.class);
                                            recipients.put(uid, new RecipientInfo(uid,
                                                    name != null ? name : "User"));
                                        }
                                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                                    });
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    // ── Listen to broadcast message history + attach seen-tracking ────────────
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

                attachSeenTracking();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        msgRef.orderByChild("timestamp").addValueEventListener(msgListener);
    }

    /**
     * For the most recent messages, watch each recipient's personal chat copy
     * for the "seen" flag flipping true, and roll it up into seenCount on the
     * broadcast_messages record. Bounded to MAX_SEEN_LISTENERS most recent
     * messages so this stays cheap even for large broadcast lists / history.
     */
    private void attachSeenTracking() {
        int from = Math.max(0, messages.size() - MAX_SEEN_LISTENERS);
        for (int i = from; i < messages.size(); i++) {
            BroadcastMessage m = messages.get(i);
            if (m.id == null || !"sent".equals(m.status)) continue;
            if (m.seenCount >= m.deliveredCount) continue; // already fully seen

            for (RecipientInfo r : recipients.values()) {
                String key = m.id + ":" + r.uid;
                if (seenListeners.containsKey(key)) continue; // already watching

                String chatId = myUid.compareTo(r.uid) < 0
                        ? myUid + "_" + r.uid : r.uid + "_" + myUid;
                // FIX: personal-chat messages live at "messages/{chatId}/{msgId}",
                // not "chats/{chatId}/messages/{msgId}" (see BroadcastDeliveryWorker
                // / FirebaseUtils.getMessagesRef). Watching the wrong path meant
                // this listener never fired.
                DatabaseReference seenRef = FirebaseDatabase.getInstance()
                        .getReference("messages").child(chatId)
                        .child(m.id).child("seen");

                ValueEventListener l = new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        if (Boolean.TRUE.equals(snap.getValue(Boolean.class))) {
                            markSeen(m.id, r.uid);
                            seenRef.removeEventListener(this);
                            seenListeners.remove(key);
                        }
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                };
                seenRef.addValueEventListener(l);
                seenListeners.put(key, l);
            }
        }
    }

    /**
     * Atomically marks a recipient as having seen a broadcast message and
     * increments seenCount. Uses a Firebase Transaction to guarantee:
     *  - No double-counting if the same recipient triggers the listener twice.
     *  - No race between parallel seen events from different recipients.
     */
    private void markSeen(String msgId, String recipientUid) {
        DatabaseReference msgEntry = msgRef.child(msgId);
        msgEntry.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData data) {
                // Guard: message node must exist before we mutate it.
                // getValue() == null means the node was deleted or never written.
                if (data.getValue() == null) {
                    return Transaction.abort();
                }
                // Already recorded — abort to avoid double-counting
                if (Boolean.TRUE.equals(
                        data.child("seenBy").child(recipientUid).getValue(Boolean.class))) {
                    return Transaction.abort();
                }
                // Mark recipient seen + increment counter atomically
                data.child("seenBy").child(recipientUid).setValue(true);
                Integer current = data.child("seenCount").getValue(Integer.class);
                data.child("seenCount").setValue(current != null ? current + 1 : 1);
                return Transaction.success(data);
            }

            @Override
            public void onComplete(DatabaseError error, boolean committed,
                                   DataSnapshot currentData) {
                if (error != null) {
                    android.util.Log.w("BroadcastChat",
                            "markSeen transaction failed: " + error.getMessage());
                }
            }
        });
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
                .setItems(new CharSequence[]{"📷 Image", "🎥 Video", "🎤 Audio/Voice", "📄 Document"},
                        (dialog, which) -> {
                            switch (which) {
                                case 0: imagePicker.launch("image/*");  break;
                                case 1: videoPicker.launch("video/*");  break;
                                case 2: audioPicker.launch("audio/*");  break;
                                case 3: docPicker.launch("*/*");        break;
                            }
                        })
                .show();
    }

    private void uploadAndSend(Uri uri, String type, String cloudinaryResourceType) {
        if (recipients.isEmpty()) {
            Toast.makeText(this, "Recipients load ho rahe hain, thoda intezaar karo",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        showUploadDialog();
        String fileName = queryFileName(uri, type);

        CloudinaryUploader.upload(this, uri, "broadcast", cloudinaryResourceType,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result result) {
                        runOnUiThread(() -> {
                            dismissUploadDialog();
                            dispatchBroadcast(null, type, result.secureUrl, fileName, null);
                        });
                    }
                    @Override public void onError(String message) {
                        runOnUiThread(() -> {
                            dismissUploadDialog();
                            Toast.makeText(BroadcastChatActivity.this,
                                    "Upload fail: " + message, Toast.LENGTH_SHORT).show();
                        });
                    }
                });
    }

    private String queryFileName(Uri uri, String type) {
        String name = null;
        try (android.database.Cursor cursor = getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception ignored) {}
        if (name == null || name.isEmpty()) {
            String mime = getContentResolver().getType(uri);
            String ext  = mime != null
                    ? MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) : null;
            name = type + "." + (ext != null ? ext : "bin");
        }
        return name;
    }

    private void showUploadDialog() {
        ProgressBar pb = new ProgressBar(this);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        pb.setPadding(pad, pad, pad, pad);
        uploadDialog = new AlertDialog.Builder(this)
                .setTitle("Upload ho raha hai…")
                .setView(pb)
                .setCancelable(false)
                .show();
    }

    private void dismissUploadDialog() {
        if (uploadDialog != null && uploadDialog.isShowing()) uploadDialog.dismiss();
    }

    /**
     * Core broadcast dispatch:
     *   1. Save placeholder record to broadcast_messages/{listId} (status="sending")
     *   2. Hand off actual fan-out to BroadcastDeliveryWorker (WorkManager) —
     *      atomic, retryable, blocked-user-aware, survives process death.
     */
    private void dispatchBroadcast(String text, String type,
                                   String mediaUrl, String fileName, String caption) {
        if (recipients.isEmpty()) {
            Toast.makeText(this, "Recipients load ho rahe hain, thoda intezaar karo",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        long now = System.currentTimeMillis();
        String msgId = msgRef.push().getKey();
        if (msgId == null) return;

        BroadcastMessage bm = new BroadcastMessage(
                msgId, text, type, mediaUrl, fileName, caption,
                myUid, now, recipients.size());

        msgRef.child(msgId).setValue(bm);

        BroadcastDeliveryWorker.enqueue(this, myUid, listId, msgId,
                text, type, mediaUrl, fileName, caption, now);
    }

    /** Retry a failed broadcast message. */
    private void retryMessage(BroadcastMessage m) {
        if (m.id == null) return;
        msgRef.child(m.id).child("status").setValue("sending");
        BroadcastDeliveryWorker.enqueue(this, myUid, listId, m.id,
                m.text, m.type, m.mediaUrl, m.fileName, m.caption, m.timestamp);
        Toast.makeText(this, "Retry ho raha hai…", Toast.LENGTH_SHORT).show();
    }

    private void onMessageTapped(BroadcastMessage m) {
        if ("failed".equals(m.status)) retryMessage(m);
    }

    // ── Options menu ──────────────────────────────────────────────────────────
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_broadcast_chat, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_broadcast_edit) {
            android.content.Intent i = new android.content.Intent(this, CreateBroadcastActivity.class);
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
        if (msgRef != null && msgListener != null)
            msgRef.removeEventListener(msgListener);
        for (Map.Entry<String, ValueEventListener> e : seenListeners.entrySet()) {
            String[] parts = e.getKey().split(":");
            if (parts.length != 2) continue;
            String chatId = myUid.compareTo(parts[1]) < 0
                    ? myUid + "_" + parts[1] : parts[1] + "_" + myUid;
            // Must match the ref used in attachSeenTracking() above, or this
            // removeEventListener() is a silent no-op and the listener leaks.
            FirebaseDatabase.getInstance().getReference("messages").child(chatId)
                    .child(parts[0]).child("seen")
                    .removeEventListener(e.getValue());
        }
        seenListeners.clear();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RecipientInfo helper
    // ─────────────────────────────────────────────────────────────────────────
    static class RecipientInfo {
        String uid, name;
        RecipientInfo(String uid, String name) {
            this.uid  = uid;
            this.name = name;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Message Adapter (sent messages log)
    // ─────────────────────────────────────────────────────────────────────────
    interface OnMsgTap { void tap(BroadcastMessage m); }

    static class BroadcastMsgAdapter
            extends RecyclerView.Adapter<BroadcastMsgAdapter.VH> {

        private final List<BroadcastMessage> data;
        private final OnMsgTap onTap;
        BroadcastMsgAdapter(List<BroadcastMessage> data, OnMsgTap onTap) {
            this.data = data;
            this.onTap = onTap;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_broadcast_message, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            BroadcastMessage m = data.get(pos);

            String preview;
            if ("text".equals(m.type) || m.type == null) {
                preview = m.text != null ? m.text : "";
            } else {
                preview = typeIcon(m.type) + (m.caption != null && !m.caption.isEmpty()
                        ? " " + m.caption : " " + typeName(m.type));
            }
            h.tvText.setText(preview);

            if ("failed".equals(m.status)) {
                h.tvDelivery.setText("⚠️ Failed — tap to retry");
            } else if ("sending".equals(m.status)) {
                h.tvDelivery.setText("⏳ Sending…");
            } else {
                String d = "📢 " + m.deliveredCount + "/" + m.totalRecipients;
                if (m.seenCount > 0) d += " • 👁 " + m.seenCount + " seen";
                if (m.skippedCount > 0) d += " • " + m.skippedCount + " skipped";
                h.tvDelivery.setText(d);
            }

            h.tvTime.setText(new SimpleDateFormat("h:mm a", Locale.getDefault())
                    .format(new Date(m.timestamp)));

            h.itemView.setOnClickListener(v -> onTap.tap(m));
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
