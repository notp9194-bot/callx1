package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.*;

/**
 * Group chat screen.
 * In addition to features 2–8 (same as 1-on-1):
 *   9. Group Admin Controls — member list, kick, promote/demote, rename group
 *  10. Group Invite Link    — Android share sheet; deep-link URL
 */
public class GroupChatActivity extends AppCompatActivity
        implements MessageAdapter.ActionListener {

    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();

    private String groupId, groupName, currentUid, currentName;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();
    private boolean isRecording = false;
    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker;

    // Feature 9: admin state
    private boolean isAdmin = false;
    // uid → {name, role}
    private final Map<String, String>  memberNames  = new HashMap<>();
    private final Map<String, String>  memberRoles  = new HashMap<>();

    // Real-time header (typing + online count)
    private DatabaseReference typingRef, membersRef;
    private ValueEventListener typingListener, membersListener;
    private final Map<String, ValueEventListener> presenceListeners = new HashMap<>();
    private final Map<String, Long>   memberLastSeen = new HashMap<>();
    private final Map<String, String> typingNames    = new HashMap<>();
    private int totalMembers = 0;
    private boolean amTyping = false;
    private final android.os.Handler typingHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private final Runnable stopTyping = () -> setMyTyping(false);
    private final android.os.Handler subtitleHandler = new android.os.Handler(
            android.os.Looper.getMainLooper());
    private final Runnable subtitleTick = new Runnable() {
        @Override public void run() {
            refreshSubtitle();
            subtitleHandler.postDelayed(this, 30_000L);
        }
    };

    // Feature 2: active reply state
    private Message replyingTo = null;

    // Feature 8: active pinned message
    private String pinnedMsgId = null;

    // ── Lifecycle ──────────────────────────────────────────────────────────
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        groupId   = getIntent().getStringExtra("groupId");
        groupName = getIntent().getStringExtra("groupName");
        if (groupId == null
                || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        currentName = FirebaseUtils.getCurrentName();

        setupToolbar();
        setupRecyclerView();
        setupInputBar();
        setupPickers();
        setupPinnedBanner();          // Feature 8
        setupReplyCancel();           // Feature 2

        loadMessages();
        setupRealtimeHeader();
        checkAdminStatus();           // Feature 9
        watchPinnedMessage();         // Feature 8
    }

    @Override protected void onDestroy() {
        subtitleHandler.removeCallbacks(subtitleTick);
        typingHandler.removeCallbacks(stopTyping);
        setMyTyping(false);
        if (typingRef  != null && typingListener  != null)
            typingRef.removeEventListener(typingListener);
        if (membersRef != null && membersListener != null)
            membersRef.removeEventListener(membersListener);
        for (Map.Entry<String, ValueEventListener> e : presenceListeners.entrySet())
            FirebaseUtils.getUserRef(e.getKey()).removeEventListener(e.getValue());
        presenceListeners.clear();
        if (adapter != null) adapter.releasePlayer();
        super.onDestroy();
    }

    @Override protected void onPause() {
        typingHandler.removeCallbacks(stopTyping);
        setMyTyping(false);
        super.onPause();
    }

    // ── Setup ──────────────────────────────────────────────────────────────
    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(
                    groupName != null ? groupName : "Group");
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupRecyclerView() {
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        binding.rvMessages.setLayoutManager(lm);
        adapter = new MessageAdapter(messages, currentUid, true);
        adapter.setActionListener(this);
        binding.rvMessages.setAdapter(adapter);
    }

    private void setupInputBar() {
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c){}
            @Override public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(has ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(has ? View.GONE   : View.VISIBLE);
                if (has) {
                    setMyTyping(true);
                    typingHandler.removeCallbacks(stopTyping);
                    typingHandler.postDelayed(stopTyping, 4_000L);
                } else {
                    typingHandler.removeCallbacks(stopTyping);
                    setMyTyping(false);
                }
            }
        });
        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnMic.setOnClickListener(v -> toggleRecording());
    }

    private void setupReplyCancel() {
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

    // ── Feature 8: Pinned Message ──────────────────────────────────────────
    private void watchPinnedMessage() {
        FirebaseUtils.getGroupsRef().child(groupId).child("pinnedMessageId")
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
        FirebaseUtils.getGroupMessagesRef(groupId).child(msgId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        Message m = s.getValue(Message.class);
                        if (m == null) { hidePinnedBanner(); return; }
                        String txt = m.text != null ? m.text
                                : ("[" + (m.type != null ? m.type : "media") + "]");
                        if (binding.llPinnedBanner != null) {
                            if (binding.tvPinnedPreview != null)
                                binding.tvPinnedPreview.setText("📌  " + txt);
                            binding.llPinnedBanner.setVisibility(View.VISIBLE);
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void hidePinnedBanner() {
        if (binding.llPinnedBanner != null)
            binding.llPinnedBanner.setVisibility(View.GONE);
        pinnedMsgId = null;
    }

    private void scrollToPinned() {
        if (pinnedMsgId == null) return;
        for (int i = 0; i < messages.size(); i++)
            if (pinnedMsgId.equals(messages.get(i).id)) {
                binding.rvMessages.smoothScrollToPosition(i); return;
            }
    }

    private void unpinMessage(String msgId) {
        FirebaseUtils.getGroupMessagesRef(groupId)
                .child(msgId).child("pinned").setValue(false);
        FirebaseUtils.getGroupsRef().child(groupId)
                .child("pinnedMessageId").removeValue();
        hidePinnedBanner();
        Toast.makeText(this, "Unpinned", Toast.LENGTH_SHORT).show();
    }

    // ── Feature 9: Admin Controls ──────────────────────────────────────────
    private void checkAdminStatus() {
        FirebaseUtils.getGroupMembersRef(groupId).child(currentUid).child("role")
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        isAdmin = "admin".equals(s.getValue(String.class));
                        invalidateOptionsMenu();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        // Cache all members
        FirebaseUtils.getGroupMembersRef(groupId)
                .addValueEventListener(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        memberNames.clear(); memberRoles.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            String uid  = c.getKey();
                            String name = c.child("name").getValue(String.class);
                            String role = c.child("role").getValue(String.class);
                            if (uid != null) {
                                memberNames.put(uid, name != null ? name : "Member");
                                memberRoles.put(uid, role != null ? role : "member");
                            }
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void showAdminPanel() {
        List<String> display = new ArrayList<>();
        List<String> uids    = new ArrayList<>();
        for (Map.Entry<String, String> e : memberNames.entrySet()) {
            if (e.getKey().equals(currentUid)) continue;
            uids.add(e.getKey());
            String role = memberRoles.get(e.getKey());
            display.add(e.getValue()
                    + ("admin".equals(role) ? "  👑" : ""));
        }
        if (display.isEmpty()) {
            Toast.makeText(this, "No other members", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Group Members")
                .setItems(display.toArray(new String[0]),
                        (d, i) -> showMemberOptions(uids.get(i)))
                .show();
    }

    private void showMemberOptions(String uid) {
        String name    = memberNames.getOrDefault(uid, "Member");
        String role    = memberRoles.getOrDefault(uid, "member");
        boolean isAdm  = "admin".equals(role);

        String[] opts = {
            "Remove from group",
            isAdm ? "Revoke admin" : "Make admin"
        };
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(opts, (d, which) -> {
                    if (which == 0) confirmRemoveMember(uid, name);
                    else toggleMemberAdmin(uid, name, isAdm);
                }).show();
    }

    private void confirmRemoveMember(String uid, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage(name + " will be removed from this group.")
                .setPositiveButton("Remove", (d, w) -> {
                    FirebaseUtils.getGroupMembersRef(groupId).child(uid).removeValue();
                    // Remove group from user's profile
                    FirebaseUtils.db().getReference("users")
                            .child(uid).child("groups").child(groupId).removeValue();
                    memberNames.remove(uid);
                    memberRoles.remove(uid);
                    Toast.makeText(this, name + " removed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void toggleMemberAdmin(String uid, String name, boolean wasAdmin) {
        String newRole = wasAdmin ? "member" : "admin";
        FirebaseUtils.getGroupMembersRef(groupId)
                .child(uid).child("role").setValue(newRole);
        memberRoles.put(uid, newRole);
        Toast.makeText(this,
                name + (wasAdmin ? ": admin revoked" : ": now admin 👑"),
                Toast.LENGTH_SHORT).show();
    }

    private void renameGroup() {
        EditText et = new EditText(this);
        et.setText(groupName);
        et.setSelection(et.getText().length());
        int p = dp(16); et.setPadding(p, p, p, p);
        new AlertDialog.Builder(this)
                .setTitle("Rename Group")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newName = et.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(groupName)) return;
                    groupName = newName;
                    FirebaseUtils.getGroupsRef()
                            .child(groupId).child("name").setValue(newName);
                    if (getSupportActionBar() != null)
                        getSupportActionBar().setTitle(newName);
                    Toast.makeText(this, "Group renamed to "" + newName + """,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ── Feature 10: Group Invite Link ──────────────────────────────────────
    private void shareInviteLink() {
        // Deep-link format: callx://join/{groupId}
        // The receiving app can parse this in AuthActivity/MainActivity
        String link = "callx://join/" + groupId;
        String body = "Join *" + groupName + "* on CallX!\n\n" + link
                + "\n\n(Open link with CallX app to join.)";
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,    body);
        share.putExtra(Intent.EXTRA_SUBJECT, "Join my CallX group — " + groupName);
        startActivity(Intent.createChooser(share, "Share invite link via…"));
    }

    // ── MessageAdapter.ActionListener ──────────────────────────────────────
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
        et.setText(m.text);
        int p = dp(16); et.setPadding(p, p, p, p);
        new AlertDialog.Builder(this)
                .setTitle("Edit message").setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = et.getText().toString().trim();
                    if (newText.isEmpty() || newText.equals(m.text)) return;
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("text", newText); upd.put("edited", true);
                    upd.put("editedAt", System.currentTimeMillis());
                    FirebaseUtils.getGroupMessagesRef(groupId)
                            .child(m.id).updateChildren(upd);
                })
                .setNegativeButton("Cancel", null).show();
    }

    @Override public void onDelete(Message m) {
        boolean canDeleteAll = currentUid.equals(m.senderId) || isAdmin;
        String[] opts = canDeleteAll
                ? new String[]{"Delete for me", "Delete for everyone"}
                : new String[]{"Delete for me"};
        new AlertDialog.Builder(this)
                .setTitle("Delete message?")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        int idx = findMessage(m.id);
                        if (idx >= 0) { messages.remove(idx); adapter.notifyItemRemoved(idx); }
                    } else {
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("deleted", true); upd.put("text", ""); upd.put("mediaUrl", "");
                        FirebaseUtils.getGroupMessagesRef(groupId)
                                .child(m.id).updateChildren(upd);
                    }
                }).show();
    }

    @Override public void onReact(Message m, String emoji) {
        DatabaseReference reactRef = FirebaseUtils
                .getGroupMessagesRef(groupId)
                .child(m.id).child("reactions").child(currentUid);
        if (m.reactions != null && emoji.equals(m.reactions.get(currentUid)))
            reactRef.removeValue();
        else
            reactRef.setValue(emoji);
    }

    @Override public void onReactionTap(Message m) {
        if (m.reactions == null || m.reactions.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.reactions.entrySet()) {
            String who = memberNames.containsKey(e.getKey())
                    ? memberNames.get(e.getKey())
                    : (currentUid.equals(e.getKey()) ? "You" : e.getKey());
            sb.append(e.getValue()).append("  ").append(who).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Reactions")
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null).show();
    }

    @Override public void onForward(Message m) {
        // Forward within group is uncommon; for production we just toast
        Toast.makeText(this,
                "Forwarding from groups — go to a 1-on-1 chat to forward",
                Toast.LENGTH_SHORT).show();
    }

    @Override public void onStar(Message m) {
        boolean nowStarred = !Boolean.TRUE.equals(m.starred);
        FirebaseUtils.getGroupMessagesRef(groupId)
                .child(m.id).child("starred").setValue(nowStarred);
        Toast.makeText(this,
                nowStarred ? "Starred ⭐" : "Unstarred",
                Toast.LENGTH_SHORT).show();
    }

    @Override public void onPin(Message m) {
        boolean isPinned = Boolean.TRUE.equals(m.pinned);
        if (isPinned) {
            unpinMessage(m.id);
        } else {
            if (pinnedMsgId != null && !pinnedMsgId.equals(m.id)) {
                FirebaseUtils.getGroupMessagesRef(groupId)
                        .child(pinnedMsgId).child("pinned").setValue(false);
            }
            FirebaseUtils.getGroupMessagesRef(groupId)
                    .child(m.id).child("pinned").setValue(true);
            FirebaseUtils.getGroupsRef().child(groupId)
                    .child("pinnedMessageId").setValue(m.id);
            Toast.makeText(this, "Message pinned 📌", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Messages ───────────────────────────────────────────────────────────
    private void sendText() {
        String txt = binding.etMessage.getText().toString().trim();
        if (txt.isEmpty()) return;
        Message m = buildOutgoing();
        m.text = txt; m.type = "text";
        push(m, txt);
        binding.etMessage.setText("");
        clearReply();
    }

    private Message buildOutgoing() {
        Message m = new Message();
        m.senderId   = currentUid;
        m.senderName = currentName;
        m.timestamp  = System.currentTimeMillis();
        m.status     = "sent";
        if (replyingTo != null) {
            m.replyToId         = replyingTo.id;
            m.replyToSenderName = replyingTo.senderName;
            m.replyToText       = replyingTo.text != null
                    ? replyingTo.text : ("[" + replyingTo.type + "]");
        }
        return m;
    }

    private void push(Message m, String preview) {
        DatabaseReference ref = FirebaseUtils.getGroupMessagesRef(groupId).push();
        m.id = ref.getKey();
        ref.setValue(m);
        Map<String, Object> meta = new HashMap<>();
        meta.put("lastMessage",   preview);
        meta.put("lastSenderName", currentName);
        meta.put("lastMessageAt", m.timestamp);
        FirebaseUtils.getGroupsRef().child(groupId).updateChildren(meta);
        String photo   = com.callx.app.CallxApp.getMyPhotoUrlCached();
        String pushType = "text".equals(m.type) ? "group_message" : m.type;
        PushNotify.notifyGroupRich(groupId, currentUid, currentName,
                photo != null ? photo : "",
                m.id, pushType, preview,
                m.mediaUrl != null ? m.mediaUrl : "");
    }

    private void loadMessages() {
        FirebaseUtils.getGroupMessagesRef(groupId)
                .orderByChild("timestamp")
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildAdded(DataSnapshot snap, String p) {
                        Message m = snap.getValue(Message.class);
                        if (m == null) return;
                        if (m.id == null) m.id = snap.getKey();
                        messages.add(m);
                        adapter.notifyItemInserted(messages.size() - 1);
                        binding.rvMessages.scrollToPosition(messages.size() - 1);
                    }
                    @Override public void onChildChanged(DataSnapshot s, String p) {
                        Message updated = s.getValue(Message.class);
                        if (updated == null) return;
                        if (updated.id == null) updated.id = s.getKey();
                        int idx = findMessage(updated.id);
                        if (idx >= 0) { messages.set(idx, updated); adapter.notifyItemChanged(idx); }
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

    // ── Media upload ───────────────────────────────────────────────────────
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
                uri -> { if (uri == null) return;
                    uploadAndSend(uri,"file","raw", FileUtils.fileName(this, uri)); });
    }

    private void uploadAndSend(Uri uri, String msgType, String rt, String fileName) {
        binding.uploadProgress.setVisibility(View.VISIBLE);
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri,
                "callx/groups/" + msgType, rt,
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
                        push(m, preview);
                        clearReply();
                    }
                    @Override public void onError(String err) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(GroupChatActivity.this,
                                err != null ? err : "Upload failed",
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private static String mediaPreview(String type, String fileName) {
        switch (type) {
            case "image": return "📷 Photo";
            case "video": return "🎬 Video";
            case "audio": return "🎤 Voice";
            case "file":  return "📎 " + (fileName != null ? fileName : "File");
            default:      return "Media";
        }
    }

    private void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View vw = LayoutInflater.from(this)
                .inflate(R.layout.bottom_sheet_attach, null);
        vw.findViewById(R.id.opt_gallery).setOnClickListener(x -> {
            sheet.dismiss(); imagePicker.launch("image/*"); });
        vw.findViewById(R.id.opt_video).setOnClickListener(x -> {
            sheet.dismiss(); videoPicker.launch("video/*"); });
        vw.findViewById(R.id.opt_audio).setOnClickListener(x -> {
            sheet.dismiss(); audioPicker.launch("audio/*"); });
        vw.findViewById(R.id.opt_file).setOnClickListener(x -> {
            sheet.dismiss(); filePicker.launch("application/pdf"); });
        sheet.setContentView(vw); sheet.show();
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
            Uri u = recorder.stop(this);
            if (u != null) uploadAndSend(u, "audio", "video", null);
        }
    }

    // ── Real-time header ───────────────────────────────────────────────────
    private void setupRealtimeHeader() {
        typingRef  = FirebaseUtils.getGroupTypingRef(groupId);
        membersRef = FirebaseUtils.getGroupMembersRef(groupId);

        typingListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                typingNames.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getKey();
                    if (uid == null || uid.equals(currentUid)) continue;
                    Object val = c.getValue();
                    String name = val != null ? String.valueOf(val) : "";
                    if (name.isEmpty() || "true".equalsIgnoreCase(name))
                        name = memberNames.getOrDefault(uid, "Someone");
                    typingNames.put(uid, name);
                }
                refreshSubtitle();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        typingRef.addValueEventListener(typingListener);

        membersListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Set<String> latest = new HashSet<>();
                for (DataSnapshot c : snap.getChildren())
                    if (c.getKey() != null) latest.add(c.getKey());
                totalMembers = latest.size();
                // Remove stale presence listeners
                List<String> toRemove = new ArrayList<>();
                for (String uid : presenceListeners.keySet())
                    if (!latest.contains(uid)) toRemove.add(uid);
                for (String uid : toRemove) {
                    ValueEventListener l = presenceListeners.remove(uid);
                    if (l != null) FirebaseUtils.getUserRef(uid).removeEventListener(l);
                    memberLastSeen.remove(uid);
                }
                for (String uid : latest) {
                    if (presenceListeners.containsKey(uid)
                            || uid.equals(currentUid)) continue;
                    subscribePresence(uid);
                }
                refreshSubtitle();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        membersRef.addValueEventListener(membersListener);
        subtitleHandler.post(subtitleTick);
    }

    private void subscribePresence(String uid) {
        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Long ls = snap.child("lastSeen").getValue(Long.class);
                memberLastSeen.put(uid, ls != null ? ls : 0L);
                refreshSubtitle();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        presenceListeners.put(uid, l);
        FirebaseUtils.getUserRef(uid).addValueEventListener(l);
    }

    private void setMyTyping(boolean typing) {
        if (typing == amTyping) return;
        amTyping = typing;
        DatabaseReference me =
                FirebaseUtils.getGroupTypingRef(groupId).child(currentUid);
        if (typing) {
            me.setValue(currentName != null ? currentName : "Someone");
            me.onDisconnect().removeValue();
        } else {
            me.removeValue();
        }
    }

    private void refreshSubtitle() {
        if (getSupportActionBar() == null) return;
        if (!typingNames.isEmpty()) {
            String sub;
            int n = typingNames.size();
            if (n == 1)
                sub = typingNames.values().iterator().next() + " is typing…";
            else if (n == 2) {
                Iterator<String> it = typingNames.values().iterator();
                sub = it.next() + " & " + it.next() + " are typing…";
            } else {
                sub = n + " people are typing…";
            }
            getSupportActionBar().setSubtitle(sub);
            return;
        }
        long now = System.currentTimeMillis();
        int online = 0;
        for (Long ls : memberLastSeen.values())
            if (ls != null && (now - ls) < com.callx.app.utils.Constants.ONLINE_WINDOW_MS) online++;
        int total = totalMembers > 0 ? totalMembers : (memberLastSeen.size() + 1);
        String sub = total + (total == 1 ? " member" : " members");
        if (online > 0) sub = online + " online, " + sub;
        getSupportActionBar().setSubtitle(sub);
    }

    // ── Menu ───────────────────────────────────────────────────────────────
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, R.id.menu_invite, 0, "🔗 Invite Link")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, R.id.menu_starred, 1, "⭐ Starred Messages")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        if (isAdmin) {
            menu.add(0, R.id.menu_admin_panel, 2, "👑 Admin Panel")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            menu.add(0, R.id.menu_rename, 3, "✏ Rename Group")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_invite)      { shareInviteLink(); return true; }
        if (id == R.id.menu_starred) {
            Intent i = new Intent(this, StarredMessagesActivity.class);
            i.putExtra("chatId",  groupId);
            i.putExtra("isGroup", true);
            startActivity(i); return true;
        }
        if (id == R.id.menu_admin_panel) { if (isAdmin) showAdminPanel(); return true; }
        if (id == R.id.menu_rename)      { if (isAdmin) renameGroup(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // ── Utility ────────────────────────────────────────────────────────────
    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }
}
