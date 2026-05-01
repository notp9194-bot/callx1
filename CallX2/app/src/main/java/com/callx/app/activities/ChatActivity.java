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
import android.widget.EditText;
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
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1-on-1 chat screen.
 * Features implemented:
 *   1. Read Receipts    — sent / delivered / read (✓ / ✓✓ / ✓✓ blue)
 *   2. Reply / Quote    — reply bar in input area with cancel button
 *   3. Emoji Reactions  — bottom sheet emoji row; aggregate pill on bubble
 *   4. Message Edit     — AlertDialog; "edited" marker + editedAt timestamp
 *   5. Message Delete   — "for me" removes locally; "for everyone" sets deleted=true
 *   6. Forward          — contact picker → cloned message with forwardedFrom
 *   7. Starred Messages — toggle star; open StarredMessagesActivity from menu
 *   8. Pin Message      — banner above RecyclerView; clicking banner scrolls to message
 *   9. (Group-only)
 *  10. (Group-only)
 */
public class ChatActivity extends AppCompatActivity
        implements MessageAdapter.ActionListener {

    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();

    private String partnerUid, partnerName, chatId, currentUid, currentName;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();
    private boolean isRecording = false;

    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker;

    // Feature 2: active reply state
    private Message replyingTo = null;

    // Feature 8: active pinned message
    private String  pinnedMsgId    = null;
    private String  pinnedMsgText  = null;

    // Perma-block watcher
    private boolean partnerPermaBlockedMe = false;

    // Typing throttle
    private long lastTypingPingMs = 0L;

    // Feature 9: In-chat search
    private final List<Integer> searchResults = new ArrayList<>();
    private int searchCursor = -1;
    private boolean searchVisible = false;

    // Feature 10: Disappearing messages
    private long disappearDurationMs = 0L; // 0 = off
    private final Handler tickHandler = new Handler();
    private final Runnable seekTick = new Runnable() {
        @Override public void run() {
            adapter.notifyDataSetChanged();
            tickHandler.postDelayed(this, 500);
        }
    };

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        partnerUid  = getIntent().getStringExtra("partnerUid");
        partnerName = getIntent().getStringExtra("partnerName");
        if (partnerUid == null
                || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        currentName = FirebaseUtils.getCurrentName();
        chatId      = FirebaseUtils.getChatId(currentUid, partnerUid);

        setupToolbar();
        setupRecyclerView();
        setupInputBar();
        setupPickers();
        setupReplyBarButtons();       // Feature 2
        setupPinnedBanner();          // Feature 8
        setupSearchBar();             // Feature 9
        setupDisappearingBar();       // Feature 10
        tickHandler.postDelayed(seekTick, 500); // audio seekbar live tick

        loadMessages();
        listenForReadReceipts();      // Feature 1
        watchPinnedMessage();         // Feature 8
        watchPartnerPermaBlock();

        // Reset unread on open
        FirebaseUtils.getContactsRef(currentUid)
                .child(partnerUid).child("unread").setValue(0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (currentUid != null && partnerUid != null) {
            FirebaseUtils.getContactsRef(currentUid)
                    .child(partnerUid).child("unread").setValue(0);
            markAllReceivedAsRead(); // Feature 1
        }
    }

    @Override protected void onDestroy() {
        tickHandler.removeCallbacks(seekTick);
        if (adapter != null) adapter.releasePlayer();
        super.onDestroy();
    }

    // ── Setup helpers ──────────────────────────────────────────────────────
    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(
                    partnerName != null ? partnerName : "Chat");
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(lm);
        adapter = new MessageAdapter(messages, currentUid, false);
        adapter.setActionListener(this);
        binding.rvMessages.setAdapter(adapter);
    }

    private void setupInputBar() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(has ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(has ? View.GONE   : View.VISIBLE);
                if (has) throttleTypingPing();
            }
        });
        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnMic.setOnClickListener(v -> toggleRecording());
    }

    // Feature 2: Reply bar cancel button
    private void setupReplyBarButtons() {
        if (binding.btnCancelReply != null) {
            binding.btnCancelReply.setOnClickListener(v -> clearReply());
        }
    }

    // Feature 8: Pinned banner button
    private void setupPinnedBanner() {
        if (binding.llPinnedBanner == null) return;
        binding.llPinnedBanner.setOnClickListener(v -> scrollToPinned());
        if (binding.btnUnpin != null) {
            binding.btnUnpin.setOnClickListener(v -> {
                if (pinnedMsgId != null) unpinMessage(pinnedMsgId);
            });
        }
    }

    // ── Feature 9: In-chat Message Search ────────────────────────────────────
    private void setupSearchBar() {
        if (binding.llSearchBar == null) return;
        binding.etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                runSearch(s.toString().trim());
            }
        });
        if (binding.btnSearchPrev != null)
            binding.btnSearchPrev.setOnClickListener(v -> navigateSearch(-1));
        if (binding.btnSearchNext != null)
            binding.btnSearchNext.setOnClickListener(v -> navigateSearch(+1));
        if (binding.btnSearchClose != null)
            binding.btnSearchClose.setOnClickListener(v -> closeSearch());
    }

    private void toggleSearch() {
        searchVisible = !searchVisible;
        if (binding.llSearchBar == null) return;
        binding.llSearchBar.setVisibility(searchVisible ? View.VISIBLE : View.GONE);
        if (searchVisible) {
            binding.etSearch.requestFocus();
        } else {
            closeSearch();
        }
    }

    private void closeSearch() {
        searchVisible = false;
        if (binding.llSearchBar != null)
            binding.llSearchBar.setVisibility(View.GONE);
        searchResults.clear();
        searchCursor = -1;
        adapter.setSearchHighlight(null, -1);
        if (binding.tvSearchNav != null) binding.tvSearchNav.setText("");
    }

    private void runSearch(String query) {
        searchResults.clear();
        searchCursor = -1;
        if (query.isEmpty()) {
            adapter.setSearchHighlight(null, -1);
            if (binding.tvSearchNav != null) binding.tvSearchNav.setText("0/0");
            return;
        }
        String lq = query.toLowerCase(java.util.Locale.getDefault());
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m.text != null && m.text.toLowerCase(java.util.Locale.getDefault()).contains(lq)) {
                searchResults.add(i);
            }
        }
        if (!searchResults.isEmpty()) {
            searchCursor = searchResults.size() - 1; // start at last (newest)
            jumpToSearchResult(query);
        } else {
            adapter.setSearchHighlight(null, -1);
            if (binding.tvSearchNav != null) binding.tvSearchNav.setText("0/0");
        }
    }

    private void navigateSearch(int dir) {
        if (searchResults.isEmpty()) return;
        searchCursor = (searchCursor + dir + searchResults.size()) % searchResults.size();
        jumpToSearchResult(binding.etSearch.getText().toString().trim());
    }

    private void jumpToSearchResult(String query) {
        int pos = searchResults.get(searchCursor);
        adapter.setSearchHighlight(query, pos);
        binding.rvMessages.scrollToPosition(pos);
        if (binding.tvSearchNav != null)
            binding.tvSearchNav.setText((searchCursor + 1) + "/" + searchResults.size());
    }

    // ── Feature 10: Disappearing Messages ────────────────────────────────────
    private void setupDisappearingBar() {
        watchDisappearingSetting();
        if (binding.btnDisappearingChange != null)
            binding.btnDisappearingChange.setOnClickListener(v -> showDisappearingPicker());
    }

    private void watchDisappearingSetting() {
        FirebaseUtils.db().getReference("chats").child(chatId)
                .child("disappearDurationMs")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        Long val = s.getValue(Long.class);
                        disappearDurationMs = val != null ? val : 0L;
                        updateDisappearingBar();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void updateDisappearingBar() {
        if (binding.llDisappearingBar == null) return;
        if (disappearDurationMs <= 0) {
            binding.llDisappearingBar.setVisibility(View.GONE);
        } else {
            binding.llDisappearingBar.setVisibility(View.VISIBLE);
            String label = formatDuration(disappearDurationMs);
            if (binding.tvDisappearingInfo != null)
                binding.tvDisappearingInfo.setText("⏳ Disappearing: " + label);
        }
    }

    private void showDisappearingPicker() {
        String[] labels = {"Off", "5 minutes", "1 hour", "24 hours", "7 days"};
        long[]   values = {0L, 5*60*1000L, 60*60*1000L, 24*60*60*1000L, 7*24*60*60*1000L};
        new AlertDialog.Builder(this)
                .setTitle("Disappearing Messages")
                .setItems(labels, (d, which) -> setDisappearing(values[which]))
                .show();
    }

    private void setDisappearing(long ms) {
        disappearDurationMs = ms;
        FirebaseUtils.db().getReference("chats").child(chatId)
                .child("disappearDurationMs").setValue(ms == 0 ? null : ms);
        updateDisappearingBar();
        String msg = ms == 0 ? "Disappearing messages turned off"
                : "New messages will disappear after " + formatDuration(ms);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String formatDuration(long ms) {
        if (ms >= 7 * 24 * 3600_000L) return "7 days";
        if (ms >= 24 * 3600_000L) return "24 hours";
        if (ms >= 3600_000L) return "1 hour";
        if (ms >= 60_000L) return (ms / 60_000L) + " minutes";
        return (ms / 1000L) + " seconds";
    }

    // ── Feature 1: Read Receipts ───────────────────────────────────────────
    /**
     * Mark all received (partner's) messages in this chat as "read".
     * Called when chat is opened or resumed.
     */
    private void markAllReceivedAsRead() {
        FirebaseUtils.getMessagesRef(chatId)
                .orderByChild("senderId").equalTo(partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        for (DataSnapshot c : snap.getChildren()) {
                            String st = c.child("status").getValue(String.class);
                            if (!"read".equals(st)) {
                                c.getRef().child("status").setValue("read");
                            }
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    /**
     * Real-time listener: when partner opens this chat, they mark our messages
     * as "read". We observe those changes and update the local list in real-time
     * so ticks animate immediately.
     */
    private void listenForReadReceipts() {
        FirebaseUtils.getMessagesRef(chatId)
                .orderByChild("senderId").equalTo(currentUid)
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildChanged(DataSnapshot s, String p) {
                        String status = s.child("status").getValue(String.class);
                        String msgId  = s.getKey();
                        if (status == null || msgId == null) return;
                        for (int i = 0; i < messages.size(); i++) {
                            if (msgId.equals(messages.get(i).id)) {
                                messages.get(i).status = status;
                                adapter.notifyItemChanged(i);
                                break;
                            }
                        }
                    }
                    @Override public void onChildAdded(DataSnapshot s, String p) {}
                    @Override public void onChildRemoved(DataSnapshot s) {}
                    @Override public void onChildMoved(DataSnapshot s, String p) {}
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // ── Feature 8: Pinned Message ──────────────────────────────────────────
    private void watchPinnedMessage() {
        FirebaseUtils.db().getReference("chats")
                .child(chatId).child("pinnedMessageId")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        pinnedMsgId = s.getValue(String.class);
                        if (pinnedMsgId == null || pinnedMsgId.isEmpty()) {
                            hidePinnedBanner();
                        } else {
                            fetchAndShowPinnedBanner(pinnedMsgId);
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void fetchAndShowPinnedBanner(String msgId) {
        FirebaseUtils.getMessagesRef(chatId).child(msgId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        Message m = s.getValue(Message.class);
                        if (m == null) { hidePinnedBanner(); return; }
                        pinnedMsgText = m.text != null ? m.text
                                : ("[" + (m.type != null ? m.type : "media") + "]");
                        showPinnedBanner(pinnedMsgText);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void showPinnedBanner(String text) {
        if (binding.llPinnedBanner == null) return;
        if (binding.tvPinnedPreview != null)
            binding.tvPinnedPreview.setText("📌  " + text);
        binding.llPinnedBanner.setVisibility(View.VISIBLE);
    }

    private void hidePinnedBanner() {
        if (binding.llPinnedBanner != null)
            binding.llPinnedBanner.setVisibility(View.GONE);
        pinnedMsgId   = null;
        pinnedMsgText = null;
    }

    private void scrollToPinned() {
        if (pinnedMsgId == null) return;
        for (int i = 0; i < messages.size(); i++) {
            if (pinnedMsgId.equals(messages.get(i).id)) {
                binding.rvMessages.smoothScrollToPosition(i);
                return;
            }
        }
    }

    private void unpinMessage(String msgId) {
        FirebaseUtils.getMessagesRef(chatId).child(msgId)
                .child("pinned").setValue(false);
        FirebaseUtils.db().getReference("chats")
                .child(chatId).child("pinnedMessageId").removeValue();
        hidePinnedBanner();
        Toast.makeText(this, "Message unpinned", Toast.LENGTH_SHORT).show();
    }

    // ── MessageAdapter.ActionListener implementations ──────────────────────

    // Feature 2: Reply
    @Override public void onReply(Message m) {
        replyingTo = m;
        if (binding.llReplyBar != null) {
            String who  = m.senderName != null ? m.senderName : "Message";
            String body = m.text != null ? m.text : "[" + m.type + "]";
            if (binding.tvReplyBarName != null) binding.tvReplyBarName.setText(who);
            if (binding.tvReplyBarText != null) binding.tvReplyBarText.setText(body);
            binding.llReplyBar.setVisibility(View.VISIBLE);
        }
        binding.etMessage.requestFocus();
    }

    private void clearReply() {
        replyingTo = null;
        if (binding.llReplyBar != null)
            binding.llReplyBar.setVisibility(View.GONE);
    }

    // Feature 4: Edit
    @Override public void onEdit(Message m) {
        if (!currentUid.equals(m.senderId)) return;
        EditText et = new EditText(this);
        et.setText(m.text); et.setSelection(et.getText().length());
        int p = dp(16); et.setPadding(p, p, p, p);
        new AlertDialog.Builder(this)
                .setTitle("Edit message")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = et.getText().toString().trim();
                    if (newText.isEmpty() || newText.equals(m.text)) return;
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("text",     newText);
                    upd.put("edited",   true);
                    upd.put("editedAt", System.currentTimeMillis());
                    FirebaseUtils.getMessagesRef(chatId)
                            .child(m.id).updateChildren(upd);
                })
                .setNegativeButton("Cancel", null).show();
    }

    // Feature 5: Delete
    @Override public void onDelete(Message m) {
        boolean isSender = currentUid.equals(m.senderId);
        String[] opts = isSender
                ? new String[]{"Delete for me", "Delete for everyone"}
                : new String[]{"Delete for me"};
        new AlertDialog.Builder(this)
                .setTitle("Delete message?")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        // Delete for me: remove locally only
                        int idx = findMessage(m.id);
                        if (idx >= 0) {
                            messages.remove(idx);
                            adapter.notifyItemRemoved(idx);
                        }
                    } else {
                        // Delete for everyone: mark in Firebase
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("deleted",  true);
                        upd.put("text",     "");
                        upd.put("mediaUrl", "");
                        FirebaseUtils.getMessagesRef(chatId)
                                .child(m.id).updateChildren(upd);
                    }
                }).show();
    }

    // Feature 3: React
    @Override public void onReact(Message m, String emoji) {
        DatabaseReference reactRef = FirebaseUtils
                .getMessagesRef(chatId).child(m.id)
                .child("reactions").child(currentUid);
        // Toggle: if same emoji already set, remove it
        if (m.reactions != null && emoji.equals(m.reactions.get(currentUid))) {
            reactRef.removeValue();
        } else {
            reactRef.setValue(emoji);
        }
    }

    // Feature 3: Reaction tap → who reacted
    @Override public void onReactionTap(Message m) {
        if (m.reactions == null || m.reactions.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.reactions.entrySet()) {
            sb.append(e.getValue()).append("  ").append(
                    currentUid.equals(e.getKey()) ? "You" : e.getKey()).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Reactions")
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null).show();
    }

    // Feature 6: Forward
    @Override public void onForward(Message m) {
        FirebaseUtils.getContactsRef(currentUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        List<String> names = new ArrayList<>();
                        List<String> uids  = new ArrayList<>();
                        for (DataSnapshot c : snap.getChildren()) {
                            String uid  = c.getKey();
                            String name = c.child("name").getValue(String.class);
                            if (uid != null && name != null
                                    && !uid.equals(currentUid)) {
                                uids.add(uid); names.add(name);
                            }
                        }
                        if (names.isEmpty()) {
                            Toast.makeText(ChatActivity.this,
                                    "No contacts to forward to",
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new AlertDialog.Builder(ChatActivity.this)
                                .setTitle("Forward to…")
                                .setItems(names.toArray(new String[0]),
                                        (d, which) -> doForward(m,
                                                uids.get(which),
                                                names.get(which)))
                                .show();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void doForward(Message orig, String toUid, String toName) {
        String fwdChatId = FirebaseUtils.getChatId(currentUid, toUid);
        Message fwd = new Message();
        fwd.senderId     = currentUid;
        fwd.senderName   = currentName;
        fwd.type         = orig.type;
        fwd.text         = orig.text;
        fwd.mediaUrl     = orig.mediaUrl;
        fwd.imageUrl     = orig.imageUrl;
        fwd.fileName     = orig.fileName;
        fwd.fileSize     = orig.fileSize;
        fwd.duration     = orig.duration;
        fwd.timestamp    = System.currentTimeMillis();
        fwd.status       = "sent";
        fwd.forwardedFrom = orig.senderName != null ? orig.senderName : "Unknown";
        DatabaseReference ref = FirebaseUtils.getMessagesRef(fwdChatId).push();
        fwd.id = ref.getKey();
        ref.setValue(fwd);
        // Update contact metadata
        String preview = orig.text != null ? orig.text : "📎 " + orig.type;
        Map<String, Object> meta = new HashMap<>();
        meta.put("lastMessage", "↪ " + preview);
        meta.put("lastMessageAt", System.currentTimeMillis());
        meta.put("unread", ServerValue.increment(1));
        FirebaseUtils.getContactsRef(toUid).child(currentUid).updateChildren(meta);
        Toast.makeText(this, "Forwarded to " + toName, Toast.LENGTH_SHORT).show();
    }

    // Feature 7: Star
    @Override public void onStar(Message m) {
        boolean nowStarred = !Boolean.TRUE.equals(m.starred);
        FirebaseUtils.getMessagesRef(chatId).child(m.id)
                .child("starred").setValue(nowStarred);
        Toast.makeText(this,
                nowStarred ? "Message starred ⭐" : "Message unstarred",
                Toast.LENGTH_SHORT).show();
    }

    // Feature 8: Pin
    @Override public void onPin(Message m) {
        boolean isPinned = Boolean.TRUE.equals(m.pinned);
        if (isPinned) {
            unpinMessage(m.id);
        } else {
            // First unpin any existing pinned message
            if (pinnedMsgId != null && !pinnedMsgId.equals(m.id)) {
                FirebaseUtils.getMessagesRef(chatId)
                        .child(pinnedMsgId).child("pinned").setValue(false);
            }
            FirebaseUtils.getMessagesRef(chatId)
                    .child(m.id).child("pinned").setValue(true);
            FirebaseUtils.db().getReference("chats")
                    .child(chatId).child("pinnedMessageId").setValue(m.id);
            Toast.makeText(this, "Message pinned 📌", Toast.LENGTH_SHORT).show();
        }
    }

    // Feature 9 (copy): ActionListener.onCopy
    @Override public void onCopy(Message m) {
        if (m.text == null || m.text.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("message", m.text));
            Toast.makeText(this, "Text copied", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Load messages ──────────────────────────────────────────────────────
    private void loadMessages() {
        FirebaseUtils.getMessagesRef(chatId)
                .orderByChild("timestamp")
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildAdded(DataSnapshot snap, String prev) {
                        Message m = snap.getValue(Message.class);
                        if (m == null) return;
                        if (m.id == null) m.id = snap.getKey();
                        // Feature 1: mark as delivered when we first see partner's message
                        if (partnerUid.equals(m.senderId)
                                && "sent".equals(m.status)) {
                            snap.getRef().child("status").setValue("delivered");
                            m.status = "delivered";
                        }
                        messages.add(m);
                        adapter.notifyItemInserted(messages.size() - 1);
                        binding.rvMessages.scrollToPosition(messages.size() - 1);
                        // Feature 10: schedule local removal if expiresAt set
                        if (m.expiresAt != null && m.expiresAt > 0) {
                            long delay = m.expiresAt - System.currentTimeMillis();
                            if (delay > 0) {
                                final String expId = m.id;
                                tickHandler.postDelayed(() -> {
                                    int idx2 = findMessage(expId);
                                    if (idx2 >= 0) {
                                        messages.remove(idx2);
                                        adapter.notifyItemRemoved(idx2);
                                    }
                                    // Also remove from Firebase
                                    FirebaseUtils.getMessagesRef(chatId)
                                            .child(expId).removeValue();
                                }, delay);
                            } else {
                                // already expired
                                FirebaseUtils.getMessagesRef(chatId)
                                        .child(m.id).removeValue();
                            }
                        }
                    }
                    // Features 3, 4, 5, 7, 8 — live updates
                    @Override public void onChildChanged(DataSnapshot s, String p) {
                        Message updated = s.getValue(Message.class);
                        if (updated == null) return;
                        if (updated.id == null) updated.id = s.getKey();
                        int idx = findMessage(updated.id);
                        if (idx >= 0) {
                            messages.set(idx, updated);
                            adapter.notifyItemChanged(idx);
                        }
                    }
                    @Override public void onChildRemoved(DataSnapshot s) {}
                    @Override public void onChildMoved(DataSnapshot s, String p) {}
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private int findMessage(String id) {
        if (id == null) return -1;
        for (int i = 0; i < messages.size(); i++)
            if (id.equals(messages.get(i).id)) return i;
        return -1;
    }

    // ── Send ───────────────────────────────────────────────────────────────
    private void sendText() {
        String txt = binding.etMessage.getText().toString().trim();
        if (txt.isEmpty()) return;
        Message m = buildOutgoing();
        m.text    = txt;
        m.type    = "text";
        pushMessage(m, txt);
        binding.etMessage.setText("");
        clearReply();
    }

    /** Build a Message with all common outgoing fields prefilled */
    private Message buildOutgoing() {
        Message m = new Message();
        m.senderId   = currentUid;
        m.senderName = currentName;
        m.timestamp  = System.currentTimeMillis();
        m.status     = "sent"; // Feature 1
        // Feature 10: attach expiresAt if disappearing enabled
        if (disappearDurationMs > 0) {
            m.expiresAt = m.timestamp + disappearDurationMs;
        }
        // Feature 2: attach reply info
        if (replyingTo != null) {
            m.replyToId         = replyingTo.id;
            m.replyToSenderName = replyingTo.senderName;
            m.replyToText       = replyingTo.text != null
                    ? replyingTo.text : ("[" + replyingTo.type + "]");
        }
        return m;
    }

    private void pushMessage(Message m, String preview) {
        DatabaseReference ref = FirebaseUtils.getMessagesRef(chatId).push();
        m.id = ref.getKey();
        ref.setValue(m);
        // Update contact metadata
        Map<String, Object> meSide = new HashMap<>();
        meSide.put("lastMessage",   preview);
        meSide.put("lastMessageAt", m.timestamp);
        meSide.put("unread", 0);
        FirebaseUtils.getContactsRef(currentUid)
                .child(partnerUid).updateChildren(meSide);
        Map<String, Object> partnerSide = new HashMap<>();
        partnerSide.put("lastMessage",   preview);
        partnerSide.put("lastMessageAt", m.timestamp);
        partnerSide.put("unread", ServerValue.increment(1));
        FirebaseUtils.getContactsRef(partnerUid)
                .child(currentUid).updateChildren(partnerSide);
        PushNotify.notifyMessage(partnerUid, currentUid, currentName,
                chatId, m.id, preview, "message",
                m.mediaUrl != null ? m.mediaUrl : "");
    }

    // ── Upload & send media ────────────────────────────────────────────────
    private void uploadAndSend(Uri uri, String msgType,
                                String resourceType, String fileName) {
        binding.uploadProgress.setVisibility(View.VISIBLE);
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri,
                "callx/" + msgType, resourceType,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result r) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m = buildOutgoing();
                        m.type     = msgType;
                        m.mediaUrl = r.secureUrl;
                        m.imageUrl = "image".equals(msgType) ? r.secureUrl : null;
                        m.fileName = fileName;
                        m.fileSize = r.bytes != null ? r.bytes : size;
                        m.duration = r.durationMs;
                        String preview = mediaPreview(msgType, fileName);
                        pushMessage(m, preview);
                        clearReply();
                    }
                    @Override public void onError(String err) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(ChatActivity.this,
                                err != null ? err : "Upload failed",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private static String mediaPreview(String type, String fileName) {
        switch (type) {
            case "image": return "📷 Photo";
            case "video": return "🎬 Video";
            case "audio": return "🎤 Voice message";
            case "file":  return "📎 " + (fileName != null ? fileName : "File");
            default:      return "Media";
        }
    }

    // ── Pickers ────────────────────────────────────────────────────────────
    private void setupPickers() {
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri,"image","image",null); });
        videoPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri,"video","video",null); });
        audioPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> { if (uri != null) uploadAndSend(uri,"audio","video",null); });
        filePicker  = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    uploadAndSend(uri,"file","raw", FileUtils.fileName(this, uri));
                });
    }

    private void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this)
                .inflate(R.layout.bottom_sheet_attach, null);
        v.findViewById(R.id.opt_gallery)
                .setOnClickListener(x -> { sheet.dismiss(); imagePicker.launch("image/*"); });
        v.findViewById(R.id.opt_video)
                .setOnClickListener(x -> { sheet.dismiss(); videoPicker.launch("video/*"); });
        v.findViewById(R.id.opt_audio)
                .setOnClickListener(x -> { sheet.dismiss(); audioPicker.launch("audio/*"); });
        v.findViewById(R.id.opt_file)
                .setOnClickListener(x -> { sheet.dismiss(); filePicker.launch("application/pdf"); });
        sheet.setContentView(v);
        sheet.show();
    }

    // ── Voice recording ────────────────────────────────────────────────────
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
                Toast.makeText(this, "Recording… tap again to stop",
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            isRecording = false;
            binding.btnMic.setBackgroundResource(R.drawable.circle_primary);
            Uri uri = recorder.stop(this);
            if (uri != null) uploadAndSend(uri, "audio", "video", null);
            else Toast.makeText(this, "Recording was empty", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Typing ping ────────────────────────────────────────────────────────
    private void throttleTypingPing() {
        long now = System.currentTimeMillis();
        if (now - lastTypingPingMs < 4_000L) return;
        lastTypingPingMs = now;
        PushNotify.notifyTyping(partnerUid, currentUid, currentName, chatId);
    }

    // ── Perma-block ────────────────────────────────────────────────────────
    private void watchPartnerPermaBlock() {
        FirebaseUtils.db().getReference("permaBlocked")
                .child(partnerUid).child(currentUid)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        partnerPermaBlockedMe =
                                Boolean.TRUE.equals(s.getValue(Boolean.class));
                        applyPermaBlockUi();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void applyPermaBlockUi() {
        if (!partnerPermaBlockedMe) {
            binding.etMessage.setEnabled(true);
            binding.etMessage.setHint(getString(R.string.hint_message));
            return;
        }
        binding.etMessage.setEnabled(false);
        binding.etMessage.setHint(partnerName + " has blocked you");
        binding.btnSend.setVisibility(View.GONE);
        binding.btnMic.setVisibility(View.GONE);
        Snackbar.make(binding.getRoot(),
                        partnerName + " has permanently blocked you",
                        Snackbar.LENGTH_INDEFINITE)
                .setAction("Send request", v -> openSpecialRequestDialog())
                .show();
    }

    private void openSpecialRequestDialog() {
        EditText et = new EditText(this);
        et.setHint("Write your message (e.g. Sorry, please unblock me)");
        et.setMinLines(2); et.setMaxLines(4);
        int p = dp(16); et.setPadding(p, p, p, p);
        new AlertDialog.Builder(this)
                .setTitle("Send special request")
                .setMessage("Send a one-time unblock request to " + partnerName + ".")
                .setView(et)
                .setPositiveButton("Send", (d, w) -> {
                    String txt = et.getText().toString().trim();
                    if (txt.isEmpty()) txt = "Please unblock me";
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("text", txt);
                    entry.put("ts",   System.currentTimeMillis());
                    entry.put("fromName", currentName);
                    entry.put("fromUid",  currentUid);
                    FirebaseUtils.db().getReference("specialRequests")
                            .child(partnerUid).child(currentUid).setValue(entry);
                    PushNotify.notifySpecialRequest(
                            partnerUid, currentUid, currentName, txt);
                    Toast.makeText(this, "Request sent", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ── Menu ───────────────────────────────────────────────────────────────
    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_voice_call || id == R.id.action_video_call) {
            Intent i = new Intent(this, CallActivity.class);
            i.putExtra("partnerUid",  partnerUid);
            i.putExtra("partnerName", partnerName);
            i.putExtra("isCaller",    true);
            i.putExtra("video",       id == R.id.action_video_call);
            startActivity(i);
            return true;
        }
        // Feature 9: In-chat search
        if (id == R.id.action_search_chat) {
            toggleSearch();
            return true;
        }
        // Feature 10: Disappearing messages
        if (id == R.id.action_disappearing) {
            showDisappearingPicker();
            return true;
        }
        // Feature 7: Starred messages
        if (id == R.id.action_starred) {
            Intent i = new Intent(this, StarredMessagesActivity.class);
            i.putExtra("chatId",  chatId);
            i.putExtra("isGroup", false);
            startActivity(i);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Utility ────────────────────────────────────────────────────────────
    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
