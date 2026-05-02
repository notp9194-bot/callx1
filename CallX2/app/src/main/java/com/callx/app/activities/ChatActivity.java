package com.callx.app.activities;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1-on-1 Chat Screen — Production Level
 *
 * Original features (1–8):
 *  1. Read Receipts     — sent / delivered / read (✓ / ✓✓ / ✓✓ blue)
 *  2. Reply / Quote     — reply bar with cancel button
 *  3. Emoji Reactions   — quick-react row + aggregate pill below bubble
 *  4. Message Edit      — AlertDialog; "edited" label + editedAt timestamp
 *  5. Message Delete    — "for me" / "for everyone"
 *  6. Forward           — contact picker → cloned message with forwardedFrom
 *  7. Starred Messages  — toggle star; open StarredMessagesActivity from menu
 *  8. Pin Message       — banner above RecyclerView; clicking banner scrolls to it
 *
 * NEW features (N1–N8 — previously missing):
 *  N1. Typing Indicator — toolbar subtitle shows "Typing…" in real-time via Firebase
 *  N2. Online/Last Seen — toolbar subtitle shows partner's online status or last-seen time
 *  N3. View Profile     — menu item opens ProfileActivity with partner's profile
 *  N4. Copy Message     — bottom-sheet "Copy" copies text to system clipboard
 *  N5. Camera Capture   — btn_camera triggers real camera capture (not just gallery)
 *  N6. Search in Chat   — collapsible search bar filters messages by keyword
 *  N7. Message Info     — bottom-sheet "Info" shows sent time + delivery/read status
 *  N8. Mute/Block/Clear — menu options: mute notifications, block user, clear local chat
 */
public class ChatActivity extends AppCompatActivity
        implements MessageAdapter.ActionListener {

    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages    = new ArrayList<>();
    private final List<Message> allMessages = new ArrayList<>(); // full list for search

    private String partnerUid, partnerName, chatId, currentUid, currentName, partnerPhotoUrl;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();
    private boolean isRecording = false;

    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker;
    private ActivityResultLauncher<Uri>    cameraCapturer; // N5
    private Uri cameraOutputUri;                          // N5

    // Feature 2
    private Message replyingTo = null;

    // Feature 8
    private String pinnedMsgId   = null;
    private String pinnedMsgText = null;

    // Perma-block
    private boolean partnerPermaBlockedMe = false;

    // N1/N2 — presence
    private ValueEventListener typingListener   = null;
    private ValueEventListener presenceListener = null;
    private boolean partnerOnline   = false;
    private Long    partnerLastSeen = null;
    private boolean isTypingShown   = false;
    private long    lastTypingPingMs = 0L;

    // N6 — search
    private boolean searchActive = false;
    private String  searchQuery  = "";

    // N8 — mute
    private boolean isMuted = false;
    private ValueEventListener muteListener = null;

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());

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
        setupReplyBarButtons();
        setupPinnedBanner();
        setupSearchBar();           // N6

        loadMessages();
        listenForReadReceipts();    // Feature 1
        watchPinnedMessage();       // Feature 8
        watchPartnerPermaBlock();
        watchPartnerTyping();       // N1
        watchPartnerPresence();     // N2
        watchMuteStatus();          // N8

        FirebaseUtils.getContactsRef(currentUid)
                .child(partnerUid).child("unread").setValue(0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (currentUid != null && partnerUid != null) {
            FirebaseUtils.getContactsRef(currentUid)
                    .child(partnerUid).child("unread").setValue(0);
            markAllReceivedAsRead();
        }
        // Publish own online presence
        FirebaseUtils.getUserRef(currentUid).child("online").setValue(true);
    }

    @Override protected void onPause() {
        super.onPause();
        // Clear typing and set offline
        clearOurTypingStatus();
        Map<String, Object> upd = new HashMap<>();
        upd.put("online",   false);
        upd.put("lastSeen", System.currentTimeMillis());
        FirebaseUtils.getUserRef(currentUid).updateChildren(upd);
    }

    @Override protected void onDestroy() {
        if (adapter != null) adapter.releasePlayer();
        if (typingListener   != null)
            FirebaseUtils.db().getReference("typing")
                    .child(chatId).child(partnerUid)
                    .removeEventListener(typingListener);
        if (presenceListener != null)
            FirebaseUtils.getUserRef(partnerUid)
                    .removeEventListener(presenceListener);
        if (muteListener     != null)
            FirebaseUtils.db().getReference("mutedChats")
                    .child(currentUid).child(chatId)
                    .removeEventListener(muteListener);
        super.onDestroy();
    }

    // ── Setup ──────────────────────────────────────────────────────────────
    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        // Set name in custom title view
        if (binding.tvPartnerName != null)
            binding.tvPartnerName.setText(partnerName != null ? partnerName : "Chat");

        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // Toolbar title area click → zoom avatar
        if (binding.llToolbarTitle != null)
            binding.llToolbarTitle.setOnClickListener(v -> showAvatarZoom());

        // Avatar click → zoom avatar
        if (binding.ivPartnerAvatar != null)
            binding.ivPartnerAvatar.setOnClickListener(v -> showAvatarZoom());

        loadPartnerAvatar();

        // Gradient toolbar voice call button
        if (binding.btnToolbarVoiceCall != null) {
            binding.btnToolbarVoiceCall.setOnClickListener(v -> {
                Intent ci = new Intent(ChatActivity.this, CallActivity.class);
                ci.putExtra("partnerUid",  partnerUid);
                ci.putExtra("partnerName", partnerName);
                ci.putExtra("isCaller",    true);
                ci.putExtra("video",       false);
                startActivity(ci);
            });
        }

        // Gradient toolbar video call button
        if (binding.btnToolbarVideoCall != null) {
            binding.btnToolbarVideoCall.setOnClickListener(v -> {
                Intent ci = new Intent(ChatActivity.this, CallActivity.class);
                ci.putExtra("partnerUid",  partnerUid);
                ci.putExtra("partnerName", partnerName);
                ci.putExtra("isCaller",    true);
                ci.putExtra("video",       true);
                startActivity(ci);
            });
        }
    }

    private void loadPartnerAvatar() {
        if (partnerUid == null) return;
        FirebaseUtils.getUserRef(partnerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String photo = snap.child("photoUrl").getValue(String.class);
                    if (photo != null && !photo.isEmpty()) {
                        partnerPhotoUrl = photo;
                        com.bumptech.glide.Glide.with(ChatActivity.this)
                            .load(photo)
                            .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
                            .placeholder(R.drawable.ic_person)
                            .error(R.drawable.ic_person)
                            .into(binding.ivPartnerAvatar);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void showAvatarZoom() {
        if (partnerPhotoUrl == null || partnerPhotoUrl.isEmpty()) return;
        android.app.Dialog dialog = new android.app.Dialog(this,
            android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.view.View dv = android.view.LayoutInflater.from(this)
            .inflate(R.layout.dialog_avatar_zoom, null);
        dialog.setContentView(dv);
        android.widget.ImageView iv = dv.findViewById(R.id.iv_avatar_zoom);
        com.bumptech.glide.Glide.with(this)
            .load(partnerPhotoUrl)
            .apply(com.bumptech.glide.request.RequestOptions.circleCropTransform())
            .placeholder(R.drawable.ic_person)
            .into(iv);
        dv.setOnClickListener(v -> dialog.dismiss());
        dv.findViewById(R.id.btn_close_zoom).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
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
                else     clearOurTypingStatus();
            }
        });
        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> launchCamera()); // N5
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnMic.setOnClickListener(v -> toggleRecording());
    }

    private void setupReplyBarButtons() {
        if (binding.btnCancelReply != null)
            binding.btnCancelReply.setOnClickListener(v -> clearReply());
    }

    private void setupPinnedBanner() {
        if (binding.llPinnedBanner == null) return;
        binding.llPinnedBanner.setOnClickListener(v -> scrollToPinned());
        if (binding.btnUnpin != null)
            binding.btnUnpin.setOnClickListener(v -> {
                if (pinnedMsgId != null) unpinMessage(pinnedMsgId);
            });
    }

    // N6: Search bar
    private void setupSearchBar() {
        if (binding.llSearchBar == null) return;
        binding.llSearchBar.setVisibility(View.GONE);
        if (binding.etSearch != null) {
            binding.etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    searchQuery = s.toString().trim().toLowerCase(Locale.getDefault());
                    applySearch();
                }
            });
        }
        if (binding.btnCloseSearch != null)
            binding.btnCloseSearch.setOnClickListener(v -> closeSearch());
    }

    private void openSearch() {
        searchActive = true;
        if (binding.llSearchBar != null)
            binding.llSearchBar.setVisibility(View.VISIBLE);
        if (binding.etSearch != null) {
            binding.etSearch.setText("");
            binding.etSearch.requestFocus();
        }
    }

    private void closeSearch() {
        searchActive = false;
        searchQuery  = "";
        if (binding.llSearchBar != null)
            binding.llSearchBar.setVisibility(View.GONE);
        messages.clear();
        messages.addAll(allMessages);
        adapter.notifyDataSetChanged();
        if (!messages.isEmpty())
            binding.rvMessages.scrollToPosition(messages.size() - 1);
    }

    private void applySearch() {
        messages.clear();
        if (searchQuery.isEmpty()) {
            messages.addAll(allMessages);
        } else {
            for (Message m : allMessages) {
                if (m.text != null &&
                        m.text.toLowerCase(Locale.getDefault()).contains(searchQuery))
                    messages.add(m);
            }
        }
        adapter.notifyDataSetChanged();
        if (!messages.isEmpty())
            binding.rvMessages.scrollToPosition(messages.size() - 1);
    }

    // ── N1: Typing Indicator ───────────────────────────────────────────────
    private void watchPartnerTyping() {
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                Long ts = s.getValue(Long.class);
                boolean typing = ts != null &&
                        (System.currentTimeMillis() - ts < 6_000L);
                updateToolbarSubtitle(typing);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("typing")
                .child(chatId).child(partnerUid)
                .addValueEventListener(typingListener);
    }

    private void throttleTypingPing() {
        long now = System.currentTimeMillis();
        if (now - lastTypingPingMs < 4_000L) return;
        lastTypingPingMs = now;
        // Write our typing timestamp to Firebase so partner sees it
        FirebaseUtils.db().getReference("typing")
                .child(chatId).child(currentUid)
                .setValue(System.currentTimeMillis());
        PushNotify.notifyTyping(partnerUid, currentUid, currentName, chatId);
    }

    private void clearOurTypingStatus() {
        FirebaseUtils.db().getReference("typing")
                .child(chatId).child(currentUid).removeValue();
    }

    // ── N2: Online / Last Seen ─────────────────────────────────────────────
    private void watchPartnerPresence() {
        presenceListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                partnerOnline   = Boolean.TRUE.equals(
                        s.child("online").getValue(Boolean.class));
                partnerLastSeen = s.child("lastSeen").getValue(Long.class);
                if (!isTypingShown) updateToolbarSubtitle(false);
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getUserRef(partnerUid).addValueEventListener(presenceListener);
    }

    private void updateToolbarSubtitle(boolean typing) {
        isTypingShown = typing;
        String sub;
        if (typing) {
            sub = "Typing\u2026";
        } else if (partnerOnline) {
            sub = "Online";
        } else if (partnerLastSeen != null) {
            sub = "Last seen " + DATE_FMT.format(new Date(partnerLastSeen));
        } else {
            sub = null;
        }
        if (getSupportActionBar() != null)
            getSupportActionBar().setSubtitle(sub);
        if (binding.tvPartnerStatus != null) {
            if (sub != null && !sub.isEmpty()) {
                binding.tvPartnerStatus.setText(sub);
                binding.tvPartnerStatus.setVisibility(android.view.View.VISIBLE);
            } else {
                binding.tvPartnerStatus.setVisibility(android.view.View.GONE);
            }
        }
    }

    // ── Feature 1: Read Receipts ───────────────────────────────────────────
    private void markAllReceivedAsRead() {
        FirebaseUtils.getMessagesRef(chatId)
                .orderByChild("senderId").equalTo(partnerUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        for (DataSnapshot c : snap.getChildren()) {
                            String st = c.child("status").getValue(String.class);
                            if (!"read".equals(st))
                                c.getRef().child("status").setValue("read");
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

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
                        if (pinnedMsgId == null || pinnedMsgId.isEmpty()) hidePinnedBanner();
                        else fetchAndShowPinnedBanner(pinnedMsgId);
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
            binding.tvPinnedPreview.setText("\uD83D\uDCCC  " + text);
        binding.llPinnedBanner.setVisibility(View.VISIBLE);
    }

    private void hidePinnedBanner() {
        if (binding.llPinnedBanner != null)
            binding.llPinnedBanner.setVisibility(View.GONE);
        pinnedMsgId = null; pinnedMsgText = null;
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
        FirebaseUtils.getMessagesRef(chatId).child(msgId).child("pinned").setValue(false);
        FirebaseUtils.db().getReference("chats")
                .child(chatId).child("pinnedMessageId").removeValue();
        hidePinnedBanner();
        Toast.makeText(this, "Message unpinned", Toast.LENGTH_SHORT).show();
    }

    // ── N8: Mute ──────────────────────────────────────────────────────────
    private void watchMuteStatus() {
        muteListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot s) {
                isMuted = Boolean.TRUE.equals(s.getValue(Boolean.class));
                invalidateOptionsMenu();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.db().getReference("mutedChats")
                .child(currentUid).child(chatId)
                .addValueEventListener(muteListener);
    }

    private void toggleMute() {
        boolean newState = !isMuted;
        FirebaseUtils.db().getReference("mutedChats")
                .child(currentUid).child(chatId).setValue(newState ? true : null);
        Toast.makeText(this,
                newState ? "Chat muted" : "Chat unmuted", Toast.LENGTH_SHORT).show();
    }

    // ── N8: Block ─────────────────────────────────────────────────────────
    private void confirmBlockUser() {
        new AlertDialog.Builder(this)
                .setTitle("Block " + partnerName + "?")
                .setMessage(partnerName + " will no longer be able to"
                        + " send you messages or call you.")
                .setPositiveButton("Block", (d, w) -> {
                    FirebaseUtils.db().getReference("permaBlocked")
                            .child(currentUid).child(partnerUid).setValue(true);
                    Toast.makeText(this, partnerName + " blocked",
                            Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ── N8: Clear Chat ────────────────────────────────────────────────────
    private void confirmClearChat() {
        new AlertDialog.Builder(this)
                .setTitle("Clear chat?")
                .setMessage("All messages will be deleted from this device."
                        + " This cannot be undone.")
                .setPositiveButton("Clear", (d, w) -> {
                    messages.clear();
                    allMessages.clear();
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, "Chat cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ── ActionListener implementations ─────────────────────────────────────

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

    @Override public void onDelete(Message m) {
        boolean isSender = currentUid.equals(m.senderId);
        String[] opts = isSender
                ? new String[]{"Delete for me", "Delete for everyone"}
                : new String[]{"Delete for me"};
        new AlertDialog.Builder(this)
                .setTitle("Delete message?")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        int idx = findMessage(m.id);
                        if (idx >= 0) {
                            messages.remove(idx);
                            allMessages.remove(m);
                            adapter.notifyItemRemoved(idx);
                        }
                    } else {
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("deleted",  true);
                        upd.put("text",     "");
                        upd.put("mediaUrl", "");
                        FirebaseUtils.getMessagesRef(chatId)
                                .child(m.id).updateChildren(upd);
                    }
                }).show();
    }

    @Override public void onReact(Message m, String emoji) {
        DatabaseReference reactRef = FirebaseUtils
                .getMessagesRef(chatId).child(m.id)
                .child("reactions").child(currentUid);
        if (m.reactions != null && emoji.equals(m.reactions.get(currentUid)))
            reactRef.removeValue();
        else
            reactRef.setValue(emoji);
    }

    @Override public void onReactionTap(Message m) {
        if (m.reactions == null || m.reactions.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.reactions.entrySet())
            sb.append(e.getValue()).append("  ").append(e.getKey()).append("\n");
        new AlertDialog.Builder(this)
                .setTitle("Reactions")
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null).show();
    }

    @Override public void onForward(Message m) {
        FirebaseUtils.getContactsRef(currentUid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        List<String> names = new ArrayList<>();
                        List<String> uids  = new ArrayList<>();
                        for (DataSnapshot c : s.getChildren()) {
                            String uid  = c.getKey();
                            String name = c.child("name").getValue(String.class);
                            if (uid != null && name != null && !uid.equals(partnerUid)) {
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
                                .setTitle("Forward to\u2026")
                                .setItems(names.toArray(new String[0]), (d, idx) -> {
                                    String toUid    = uids.get(idx);
                                    String toChatId = FirebaseUtils
                                            .getChatId(currentUid, toUid);
                                    Message fwd        = new Message();
                                    fwd.senderId       = currentUid;
                                    fwd.senderName     = currentName;
                                    fwd.type           = m.type;
                                    fwd.text           = m.text;
                                    fwd.mediaUrl       = m.mediaUrl;
                                    fwd.imageUrl       = m.imageUrl;
                                    fwd.fileName       = m.fileName;
                                    fwd.fileSize       = m.fileSize;
                                    fwd.duration       = m.duration;
                                    fwd.forwardedFrom  = m.senderName != null
                                            ? m.senderName : "Unknown";
                                    fwd.timestamp      = System.currentTimeMillis();
                                    fwd.status         = "sent";
                                    String key = FirebaseUtils
                                            .getMessagesRef(toChatId).push().getKey();
                                    if (key == null) return;
                                    fwd.id = key;
                                    FirebaseUtils.getMessagesRef(toChatId)
                                            .child(key).setValue(fwd);
                                    Toast.makeText(ChatActivity.this,
                                            "Forwarded to " + names.get(idx),
                                            Toast.LENGTH_SHORT).show();
                                }).show();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    @Override public void onStar(Message m) {
        boolean nowStarred = !Boolean.TRUE.equals(m.starred);
        FirebaseUtils.getMessagesRef(chatId)
                .child(m.id).child("starred").setValue(nowStarred);
        Toast.makeText(this,
                nowStarred ? "Message starred" : "Message unstarred",
                Toast.LENGTH_SHORT).show();
    }

    @Override public void onPin(Message m) {
        boolean nowPinned = !Boolean.TRUE.equals(m.pinned);
        FirebaseUtils.getMessagesRef(chatId)
                .child(m.id).child("pinned").setValue(nowPinned);
        if (nowPinned) {
            FirebaseUtils.db().getReference("chats")
                    .child(chatId).child("pinnedMessageId").setValue(m.id);
            Toast.makeText(this, "Message pinned", Toast.LENGTH_SHORT).show();
        } else {
            FirebaseUtils.db().getReference("chats")
                    .child(chatId).child("pinnedMessageId").removeValue();
            hidePinnedBanner();
        }
    }

    // N4: Copy message text to clipboard
    @Override public void onCopy(Message m) {
        if (m.text == null || m.text.isEmpty()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("message", m.text));
        Toast.makeText(this, "Message copied", Toast.LENGTH_SHORT).show();
    }

    // N7: Show message info dialog
    @Override public void onInfo(Message m) {
        String sent = m.timestamp != null
                ? DATE_FMT.format(new Date(m.timestamp)) : "Unknown";
        String statusLine;
        switch (m.status == null ? "sent" : m.status) {
            case "read":
                statusLine = "Read \u2713\u2713 (blue)"; break;
            case "delivered":
                statusLine = "Delivered \u2713\u2713";   break;
            default:
                statusLine = "Sent \u2713";              break;
        }
        String info = "Sent: " + sent
                + "\nStatus: " + statusLine
                + (Boolean.TRUE.equals(m.edited)   ? "\n(Edited)"    : "")
                + (Boolean.TRUE.equals(m.starred)  ? "\n\u2B50 Starred" : "")
                + (Boolean.TRUE.equals(m.pinned)   ? "\n\uD83D\uDCCC Pinned" : "")
                + (m.forwardedFrom != null && !m.forwardedFrom.isEmpty()
                        ? "\n\u21AA Forwarded from " + m.forwardedFrom : "");
        new AlertDialog.Builder(this)
                .setTitle("Message Info")
                .setMessage(info)
                .setPositiveButton("OK", null).show();
    }

    // ── Load messages ──────────────────────────────────────────────────────
    private void loadMessages() {
        FirebaseUtils.getMessagesRef(chatId)
                .orderByChild("timestamp")
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildAdded(DataSnapshot s, String p) {
                        Message m = s.getValue(Message.class);
                        if (m == null) return;
                        if (m.id == null) m.id = s.getKey();
                        allMessages.add(m);
                        if (!searchActive || matchesSearch(m)) {
                            messages.add(m);
                            adapter.notifyItemInserted(messages.size() - 1);
                            binding.rvMessages.scrollToPosition(messages.size() - 1);
                        }
                        if (!currentUid.equals(m.senderId) && !"read".equals(m.status))
                            s.getRef().child("status").setValue("delivered");
                    }
                    @Override public void onChildChanged(DataSnapshot s, String p) {
                        Message updated = s.getValue(Message.class);
                        if (updated == null || s.getKey() == null) return;
                        if (updated.id == null) updated.id = s.getKey();
                        for (int i = 0; i < allMessages.size(); i++) {
                            if (s.getKey().equals(allMessages.get(i).id)) {
                                allMessages.set(i, updated); break;
                            }
                        }
                        for (int i = 0; i < messages.size(); i++) {
                            if (s.getKey().equals(messages.get(i).id)) {
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

    private boolean matchesSearch(Message m) {
        return searchQuery.isEmpty() || (m.text != null &&
                m.text.toLowerCase(Locale.getDefault()).contains(searchQuery));
    }

    private int findMessage(String id) {
        for (int i = 0; i < messages.size(); i++)
            if (id.equals(messages.get(i).id)) return i;
        return -1;
    }

    // ── Send text ──────────────────────────────────────────────────────────
    private void sendText() {
        if (partnerPermaBlockedMe) return;
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        binding.etMessage.setText("");
        clearOurTypingStatus();
        Message m = buildOutgoing();
        m.type = "text";
        m.text = text;
        pushMessage(m, text);
        clearReply();
    }

    private Message buildOutgoing() {
        Message m     = new Message();
        m.senderId    = currentUid;
        m.senderName  = currentName;
        m.timestamp   = System.currentTimeMillis();
        m.status      = "sent";
        if (replyingTo != null) {
            m.replyToId         = replyingTo.id;
            m.replyToText       = replyingTo.text != null
                    ? replyingTo.text : "[" + replyingTo.type + "]";
            m.replyToSenderName = replyingTo.senderName;
        }
        return m;
    }

    private void pushMessage(Message m, String previewText) {
        String key = FirebaseUtils.getMessagesRef(chatId).push().getKey();
        if (key == null) return;
        m.id = key;
        FirebaseUtils.getMessagesRef(chatId).child(key).setValue(m);
        long ts = m.timestamp;
        Map<String, Object> myUpd = new HashMap<>();
        myUpd.put("lastMessage", previewText); myUpd.put("lastTs", ts);
        FirebaseUtils.getContactsRef(currentUid).child(partnerUid).updateChildren(myUpd);
        Map<String, Object> theirUpd = new HashMap<>();
        theirUpd.put("lastMessage", previewText); theirUpd.put("lastTs", ts);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).updateChildren(theirUpd);
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid).child("unread")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        Long cur = s.getValue(Long.class);
                        s.getRef().setValue((cur != null ? cur : 0) + 1);
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        if (!isMuted)
            PushNotify.notifyMessage(partnerUid, currentUid, currentName,
                    chatId, m.id != null ? m.id : "", previewText,
                    m.type != null ? m.type : "text",
                    m.mediaUrl != null ? m.mediaUrl : "");
    }

    // ── N5: Real Camera Capture ────────────────────────────────────────────
    private void launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, 300);
            return;
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME,
                "callx_" + System.currentTimeMillis() + ".jpg");
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cameraOutputUri = getContentResolver()
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
        if (cameraOutputUri != null)
            cameraCapturer.launch(cameraOutputUri);
    }

    // ── Upload & send media ────────────────────────────────────────────────
    private void uploadAndSend(Uri uri, String msgType,
                                String resourceType, String fileName) {
        binding.uploadProgress.setVisibility(View.VISIBLE);
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri, "callx/" + msgType, resourceType,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result r) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m  = buildOutgoing();
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
            case "image": return "\uD83D\uDCF7 Photo";
            case "video": return "\uD83C\uDFAC Video";
            case "audio": return "\uD83C\uDFA4 Voice message";
            case "file":  return "\uD83D\uDCCE " + (fileName != null ? fileName : "File");
            default:      return "Media";
        }
    }

    // ── Pickers ────────────────────────────────────────────────────────────
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
        // N5
        cameraCapturer = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success && cameraOutputUri != null)
                        uploadAndSend(cameraOutputUri, "image", "image", null);
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
                .setOnClickListener(x -> { sheet.dismiss(); filePicker.launch("*/*"); });
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
                Toast.makeText(this, "Recording\u2026 tap again to stop",
                        Toast.LENGTH_SHORT).show();
            }
        } else {
            isRecording = false;
            binding.btnMic.setBackgroundResource(R.drawable.circle_primary);
            Uri uri = recorder.stop(this);
            if (uri != null) uploadAndSend(uri, "audio", "video", null);
            else Toast.makeText(this, "Recording was empty",
                    Toast.LENGTH_SHORT).show();
        }
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
                    entry.put("text",     txt);
                    entry.put("ts",       System.currentTimeMillis());
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

    @Override public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        MenuItem muteItem = menu.findItem(R.id.action_mute);
        if (muteItem != null)
            muteItem.setTitle(isMuted ? "\uD83D\uDD14 Unmute" : "\uD83D\uDD15 Mute");
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_view_profile) { // N3 — show avatar fullscreen
            showAvatarZoom(); return true;
        }
        if (id == R.id.action_starred) {
            Intent i = new Intent(this, StarredMessagesActivity.class);
            i.putExtra("chatId",  chatId);
            i.putExtra("isGroup", false);
            startActivity(i); return true;
        }
        if (id == R.id.action_search)     { openSearch();        return true; } // N6
        if (id == R.id.action_mute)       { toggleMute();        return true; } // N8
        if (id == R.id.action_block)      { confirmBlockUser();   return true; } // N8
        if (id == R.id.action_clear_chat) { confirmClearChat();   return true; } // N8
        return super.onOptionsItemSelected(item);
    }

    @Override public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 300 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            launchCamera();
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
