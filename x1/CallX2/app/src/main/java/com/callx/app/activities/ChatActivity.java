package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.callx.app.R;
import com.callx.app.adapters.MessageAdapter;
import com.callx.app.databinding.ActivityChatBinding;
import com.callx.app.models.Message;
import com.callx.app.utils.AudioRecorderHelper;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChatActivity extends AppCompatActivity implements MessageAdapter.MessageActionListener {

    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private String partnerUid, partnerName, chatId, currentUid, currentName;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();
    private boolean isRecording = false;
    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker;

    // Feature 2: Reply state
    private Message replyingTo = null;
    private View replyBar = null;

    // Feature 8: Pinned message banner
    private String pinnedMessageId = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        partnerUid  = getIntent().getStringExtra("partnerUid");
        partnerName = getIntent().getStringExtra("partnerName");
        if (partnerUid == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        currentName = FirebaseUtils.getCurrentName();
        chatId = FirebaseUtils.getChatId(currentUid, partnerUid);

        // Reset unread counter on open
        FirebaseUtils.getContactsRef(currentUid).child(partnerUid)
                .child("unread").setValue(0);

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(partnerName != null ? partnerName : "Chat");
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.rvMessages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MessageAdapter(messages, currentUid, false);
        adapter.setMessageActionListener(this);
        binding.rvMessages.setAdapter(adapter);

        setupTextWatcher();
        setupPickers();
        setupReplyBar();

        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnMic.setOnClickListener(v -> toggleRecording());

        loadMessages();
        // Feature 8: Watch pinned message
        watchPinnedMessage();
        // Feature 1: Mark messages as read
        markMessagesRead();
        watchPartnerPermaBlock();
    }

    // ========== Feature 1: Read Receipts ==========
    private void markMessagesRead() {
        // When this chat is opened, mark all received messages as "read"
        FirebaseUtils.getMessagesRef(chatId).orderByChild("timestamp")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        for (DataSnapshot child : snap.getChildren()) {
                            String sid = child.child("senderId").getValue(String.class);
                            String st  = child.child("status").getValue(String.class);
                            if (partnerUid.equals(sid) && !"read".equals(st)) {
                                child.getRef().child("status").setValue("read");
                            }
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // ========== Feature 2: Reply bar setup ==========
    private void setupReplyBar() {
        // Dynamically create reply bar above the input area
        replyBar = LayoutInflater.from(this).inflate(
                android.R.layout.simple_list_item_2, null);
        // We'll show an inline TextView instead
    }

    private void showReplyBar(Message m) {
        replyingTo = m;
        String who = m.senderName != null ? m.senderName : "Message";
        String preview = m.text != null ? m.text : ("[" + m.type + "]");
        binding.etMessage.setHint("↩ Replying to " + who + ": " + preview);
    }

    private void clearReplyBar() {
        replyingTo = null;
        binding.etMessage.setHint("Type a message");
    }

    // ========== Feature 8: Pinned message banner ==========
    private void watchPinnedMessage() {
        FirebaseUtils.db().getReference("chats")
                .child(chatId).child("pinnedMessageId")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        pinnedMessageId = s.getValue(String.class);
                        updatePinnedBanner();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void updatePinnedBanner() {
        if (pinnedMessageId == null || pinnedMessageId.isEmpty()) return;
        FirebaseUtils.getMessagesRef(chatId).child(pinnedMessageId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        Message m = s.getValue(Message.class);
                        if (m == null) return;
                        String txt = m.text != null ? m.text : "[" + m.type + "]";
                        Toast.makeText(ChatActivity.this,
                                "📌 Pinned: " + txt, Toast.LENGTH_SHORT).show();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // ========== Feature 1 continued: update local status when message delivered ==========
    private void sendText() {
        String txt = binding.etMessage.getText().toString().trim();
        if (txt.isEmpty()) return;
        Message m = new Message();
        m.senderId   = currentUid;
        m.senderName = currentName;
        m.text       = txt;
        m.type       = "text";
        m.timestamp  = System.currentTimeMillis();
        m.status     = "sent"; // Feature 1
        applyReplyInfo(m);     // Feature 2
        pushMessage(m, txt);
        binding.etMessage.setText("");
        clearReplyBar();
    }

    // Feature 2: attach reply info to outgoing message
    private void applyReplyInfo(Message m) {
        if (replyingTo != null) {
            m.replyToId         = replyingTo.id;
            m.replyToSenderName = replyingTo.senderName;
            m.replyToText = replyingTo.text != null
                    ? replyingTo.text : ("[" + replyingTo.type + "]");
        }
    }

    // ========== MessageActionListener (long-press actions) ==========

    // Feature 2: Reply
    @Override public void onReply(Message m) { showReplyBar(m); }

    // Feature 4: Edit
    @Override public void onEdit(Message m) {
        if (!currentUid.equals(m.senderId)) return;
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(m.text);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Edit message")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = et.getText().toString().trim();
                    if (newText.isEmpty() || newText.equals(m.text)) return;
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("text", newText);
                    upd.put("edited", true);
                    FirebaseUtils.getMessagesRef(chatId).child(m.id).updateChildren(upd);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // Feature 5: Delete
    @Override public void onDelete(Message m) {
        String[] opts = currentUid.equals(m.senderId)
                ? new String[]{"Delete for me", "Delete for everyone"}
                : new String[]{"Delete for me"};
        new AlertDialog.Builder(this)
                .setTitle("Delete message")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        // Delete for me: just hide locally
                        int idx = messages.indexOf(m);
                        if (idx >= 0) {
                            messages.remove(idx);
                            adapter.notifyItemRemoved(idx);
                        }
                    } else {
                        // Delete for everyone: mark in Firebase
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("deleted", true);
                        upd.put("text", "");
                        upd.put("mediaUrl", "");
                        FirebaseUtils.getMessagesRef(chatId).child(m.id).updateChildren(upd);
                    }
                }).show();
    }

    // Feature 3: React
    @Override public void onReact(Message m) {
        String[] emojis = {"❤️", "👍", "😂", "😮", "😢", "🙏"};
        new AlertDialog.Builder(this)
                .setTitle("React")
                .setItems(emojis, (d, which) -> {
                    String emoji = emojis[which];
                    FirebaseUtils.getMessagesRef(chatId)
                            .child(m.id).child("reactions")
                            .child(currentUid).setValue(emoji);
                }).show();
    }

    // Feature 6: Forward
    @Override public void onForward(Message m) {
        // Load contacts and let user pick a chat to forward to
        FirebaseUtils.getContactsRef(currentUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        List<String> names = new ArrayList<>();
                        List<String> uids  = new ArrayList<>();
                        for (DataSnapshot c : snap.getChildren()) {
                            String uid  = c.getKey();
                            String name = c.child("name").getValue(String.class);
                            if (uid != null && name != null) {
                                uids.add(uid); names.add(name);
                            }
                        }
                        if (names.isEmpty()) {
                            Toast.makeText(ChatActivity.this,
                                    "No contacts to forward to", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new AlertDialog.Builder(ChatActivity.this)
                                .setTitle("Forward to")
                                .setItems(names.toArray(new String[0]), (d, which) -> {
                                    String toUid = uids.get(which);
                                    String toName = names.get(which);
                                    String fwdChatId = FirebaseUtils.getChatId(currentUid, toUid);
                                    Message fwd = new Message();
                                    fwd.senderId      = currentUid;
                                    fwd.senderName    = currentName;
                                    fwd.type          = m.type;
                                    fwd.text          = m.text;
                                    fwd.mediaUrl      = m.mediaUrl;
                                    fwd.imageUrl      = m.imageUrl;
                                    fwd.fileName      = m.fileName;
                                    fwd.fileSize      = m.fileSize;
                                    fwd.duration      = m.duration;
                                    fwd.timestamp     = System.currentTimeMillis();
                                    fwd.forwardedFrom = currentName;  // Feature 6
                                    fwd.status        = "sent";
                                    DatabaseReference ref =
                                            FirebaseUtils.getMessagesRef(fwdChatId).push();
                                    fwd.id = ref.getKey();
                                    ref.setValue(fwd);
                                    Toast.makeText(ChatActivity.this,
                                            "Forwarded to " + toName, Toast.LENGTH_SHORT).show();
                                }).show();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // Feature 7: Star
    @Override public void onStar(Message m) {
        boolean isStarred = Boolean.TRUE.equals(m.starred);
        FirebaseUtils.getMessagesRef(chatId).child(m.id)
                .child("starred").setValue(!isStarred);
        Toast.makeText(this,
                isStarred ? "Message unstarred" : "Message starred ⭐",
                Toast.LENGTH_SHORT).show();
    }

    // Feature 8: Pin
    @Override public void onPin(Message m) {
        boolean isPinned = Boolean.TRUE.equals(m.pinned);
        if (!isPinned) {
            // Unpin previous
            if (pinnedMessageId != null) {
                FirebaseUtils.getMessagesRef(chatId)
                        .child(pinnedMessageId).child("pinned").setValue(false);
            }
            // Pin new
            FirebaseUtils.getMessagesRef(chatId).child(m.id).child("pinned").setValue(true);
            FirebaseUtils.db().getReference("chats")
                    .child(chatId).child("pinnedMessageId").setValue(m.id);
            Toast.makeText(this, "Message pinned 📌", Toast.LENGTH_SHORT).show();
        } else {
            FirebaseUtils.getMessagesRef(chatId).child(m.id).child("pinned").setValue(false);
            FirebaseUtils.db().getReference("chats")
                    .child(chatId).child("pinnedMessageId").removeValue();
            Toast.makeText(this, "Message unpinned", Toast.LENGTH_SHORT).show();
        }
    }

    // ========== Load messages (with change listener for live edits/deletes/reactions) ==========
    private void loadMessages() {
        FirebaseUtils.getMessagesRef(chatId).orderByChild("timestamp")
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildAdded(DataSnapshot snap, String prev) {
                        Message m = snap.getValue(Message.class);
                        if (m == null) return;
                        if (m.id == null) m.id = snap.getKey();
                        // Feature 1: mark delivered when we load a partner's message
                        if (partnerUid.equals(m.senderId) && "sent".equals(m.status)) {
                            snap.getRef().child("status").setValue("delivered");
                        }
                        messages.add(m);
                        adapter.notifyItemInserted(messages.size() - 1);
                        binding.rvMessages.scrollToPosition(messages.size() - 1);
                    }
                    // Feature 4, 5, 3, 7, 8 — live updates
                    @Override public void onChildChanged(DataSnapshot s, String p) {
                        Message updated = s.getValue(Message.class);
                        if (updated == null) return;
                        if (updated.id == null) updated.id = s.getKey();
                        for (int i = 0; i < messages.size(); i++) {
                            if (updated.id.equals(messages.get(i).id)) {
                                messages.set(i, updated);
                                adapter.notifyItemChanged(i);
                                break;
                            }
                        }
                    }
                    @Override public void onChildRemoved(DataSnapshot s) {}
                    @Override public void onChildMoved(DataSnapshot s, String p) {}
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // ========== Existing helpers ==========
    private boolean partnerPermaBlockedMe = false;
    private void watchPartnerPermaBlock() {
        FirebaseUtils.db().getReference("permaBlocked")
                .child(partnerUid).child(currentUid)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        partnerPermaBlockedMe = Boolean.TRUE.equals(s.getValue(Boolean.class));
                        applyPermaBlockUi();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void applyPermaBlockUi() {
        if (!partnerPermaBlockedMe) {
            binding.etMessage.setEnabled(true);
            binding.etMessage.setHint("Type a message");
            return;
        }
        binding.etMessage.setEnabled(false);
        binding.etMessage.setHint(partnerName + " ne aapko block kiya");
        binding.btnSend.setVisibility(View.GONE);
        binding.btnMic.setVisibility(View.GONE);
        com.google.android.material.snackbar.Snackbar.make(binding.getRoot(),
                        partnerName + " ne aapko permanently block kiya hai",
                        com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
                .setAction("Special request", v -> openSpecialRequestDialog()).show();
    }

    private void openSpecialRequestDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Apna message likho");
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new AlertDialog.Builder(this)
                .setTitle("Send special request")
                .setView(et)
                .setPositiveButton("Send", (d, w) -> {
                    String txt = et.getText().toString().trim();
                    if (txt.isEmpty()) txt = "Please unblock me";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("text", txt); entry.put("ts", System.currentTimeMillis());
                    entry.put("fromName", currentName); entry.put("fromUid", currentUid);
                    FirebaseUtils.db().getReference("specialRequests")
                            .child(partnerUid).child(currentUid).setValue(entry);
                    PushNotify.notifySpecialRequest(partnerUid, currentUid, currentName, txt);
                    Toast.makeText(this, "Request bhej diya", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void setupTextWatcher() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(has ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(has ? View.GONE : View.VISIBLE);
                if (has) maybeSendTypingPing();
            }
        });
    }

    private void setupPickers() {
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "image", "image", null); });
        videoPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "video", "video", null); });
        audioPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri, "audio", "video", null); });
        filePicker  = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    uploadAndSend(uri, "file", "raw", FileUtils.fileName(this, uri));
                });
    }

    private void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_attach, null);
        v.findViewById(R.id.opt_gallery).setOnClickListener(x -> {
            sheet.dismiss(); imagePicker.launch("image/*"); });
        v.findViewById(R.id.opt_video).setOnClickListener(x -> {
            sheet.dismiss(); videoPicker.launch("video/*"); });
        v.findViewById(R.id.opt_audio).setOnClickListener(x -> {
            sheet.dismiss(); audioPicker.launch("audio/*"); });
        v.findViewById(R.id.opt_file).setOnClickListener(x -> {
            sheet.dismiss(); filePicker.launch("application/pdf"); });
        sheet.setContentView(v);
        sheet.show();
    }

    private void toggleRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 200);
            return;
        }
        if (!isRecording) {
            if (recorder.start(this)) {
                isRecording = true;
                binding.btnMic.setBackgroundResource(R.drawable.circle_reject);
            }
        } else {
            isRecording = false;
            binding.btnMic.setBackgroundResource(R.drawable.circle_primary);
            Uri uri = recorder.stop(this);
            if (uri != null) uploadAndSend(uri, "audio", "video", null);
        }
    }

    private void uploadAndSend(Uri uri, String msgType, String resourceType, String fileName) {
        binding.uploadProgress.setVisibility(View.VISIBLE);
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri, "callx/" + msgType, resourceType,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result r) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m = new Message();
                        m.senderId  = currentUid; m.senderName = currentName;
                        m.type      = msgType;    m.mediaUrl   = r.secureUrl;
                        m.imageUrl  = "image".equals(msgType) ? r.secureUrl : null;
                        m.fileName  = fileName;   m.fileSize   = r.bytes != null ? r.bytes : size;
                        m.duration  = r.durationMs;
                        m.timestamp = System.currentTimeMillis();
                        m.status    = "sent"; // Feature 1
                        applyReplyInfo(m);    // Feature 2
                        String preview;
                        switch (msgType) {
                            case "image": preview = "📷 Photo"; break;
                            case "video": preview = "🎬 Video"; break;
                            case "audio": preview = "🎤 Voice message"; break;
                            case "file":  preview = "📎 " + (fileName != null ? fileName : "File"); break;
                            default: preview = "Media";
                        }
                        pushMessage(m, preview);
                        clearReplyBar();
                    }
                    @Override public void onError(String err) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(ChatActivity.this,
                                err == null ? "Upload fail" : err, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void pushMessage(Message m, String preview) {
        DatabaseReference ref = FirebaseUtils.getMessagesRef(chatId).push();
        m.id = ref.getKey();
        ref.setValue(m);
        Map<String, Object> meSide = new HashMap<>();
        meSide.put("lastMessage", preview);
        meSide.put("lastMessageAt", System.currentTimeMillis());
        meSide.put("unread", 0);
        FirebaseUtils.getContactsRef(currentUid).child(partnerUid).updateChildren(meSide);
        Map<String, Object> partnerSide = new HashMap<>();
        partnerSide.put("lastMessage", preview);
        partnerSide.put("lastMessageAt", System.currentTimeMillis());
        partnerSide.put("unread", ServerValue.increment(1));
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).updateChildren(partnerSide);
        PushNotify.notifyMessage(partnerUid, currentUid, currentName,
                chatId, m.id, preview, "message", m.mediaUrl == null ? "" : m.mediaUrl);
    }

    private long lastTypingPingAt = 0L;
    private void maybeSendTypingPing() {
        long now = System.currentTimeMillis();
        if (now - lastTypingPingAt < 4000L) return;
        lastTypingPingAt = now;
        PushNotify.notifyTyping(partnerUid, currentUid, currentName, chatId);
    }

    @Override protected void onResume() {
        super.onResume();
        if (currentUid != null && partnerUid != null) {
            FirebaseUtils.getContactsRef(currentUid).child(partnerUid)
                    .child("unread").setValue(0);
            markMessagesRead(); // Feature 1
        }
    }

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_voice_call || id == R.id.action_video_call) {
            Intent i = new Intent(this, CallActivity.class);
            i.putExtra("partnerUid", partnerUid);
            i.putExtra("partnerName", partnerName);
            i.putExtra("isCaller", true);
            i.putExtra("video", id == R.id.action_video_call);
            startActivity(i); return true;
        }
        // Feature 7: Starred messages from menu
        if (id == R.id.action_starred) {
            Intent i = new Intent(this, StarredMessagesActivity.class);
            i.putExtra("chatId", chatId);
            i.putExtra("isGroup", false);
            startActivity(i); return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
