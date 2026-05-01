package com.callx.app.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GroupChatActivity extends AppCompatActivity
        implements MessageAdapter.MessageActionListener {

    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private String groupId, groupName, currentUid, currentName;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();
    private boolean isRecording = false;
    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker;

    // Feature 9: Admin tracking
    private boolean isAdmin = false;
    private final Map<String, String> memberNames = new HashMap<>();   // uid -> name
    private final Map<String, Boolean> memberAdmins = new HashMap<>(); // uid -> isAdmin

    // Real-time header state
    private DatabaseReference typingRef, membersRef;
    private ValueEventListener typingListener, membersListener;
    private final Map<String, ValueEventListener> presenceListeners = new HashMap<>();
    private final Map<String, Long>   memberLastSeen = new HashMap<>();
    private final Map<String, String> typingNames    = new HashMap<>();
    private int totalMembers = 0;
    private boolean amTyping = false;
    private final android.os.Handler typingStopHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable typingStopRunnable = () -> setMyTyping(false);
    private final android.os.Handler subtitleHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable subtitleRefresh = new Runnable() {
        @Override public void run() {
            refreshSubtitle();
            subtitleHandler.postDelayed(this, 30_000L);
        }
    };

    // Reply state (Feature 2 for groups)
    private Message replyingTo = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        groupId   = getIntent().getStringExtra("groupId");
        groupName = getIntent().getStringExtra("groupName");
        if (groupId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        currentName = FirebaseUtils.getCurrentName();

        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(groupName != null ? groupName : "Group");
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.rvMessages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MessageAdapter(messages, currentUid, true);
        adapter.setMessageActionListener(this);
        binding.rvMessages.setAdapter(adapter);

        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(has ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(has ? View.GONE : View.VISIBLE);
                if (has) {
                    setMyTyping(true);
                    typingStopHandler.removeCallbacks(typingStopRunnable);
                    typingStopHandler.postDelayed(typingStopRunnable, 4000L);
                } else {
                    typingStopHandler.removeCallbacks(typingStopRunnable);
                    setMyTyping(false);
                }
            }
        });

        setupRealtimeHeader();
        setupPickers();

        binding.btnAttach.setOnClickListener(v -> {
            BottomSheetDialog sheet = new BottomSheetDialog(this);
            View vw = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_attach, null);
            vw.findViewById(R.id.opt_gallery).setOnClickListener(x -> {
                sheet.dismiss(); imagePicker.launch("image/*"); });
            vw.findViewById(R.id.opt_video).setOnClickListener(x -> {
                sheet.dismiss(); videoPicker.launch("video/*"); });
            vw.findViewById(R.id.opt_audio).setOnClickListener(x -> {
                sheet.dismiss(); audioPicker.launch("audio/*"); });
            vw.findViewById(R.id.opt_file).setOnClickListener(x -> {
                sheet.dismiss(); filePicker.launch("application/pdf"); });
            sheet.setContentView(vw); sheet.show();
        });
        binding.btnCamera.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnMic.setOnClickListener(v -> {
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
        });

        loadMessages();

        // Feature 9: Check if current user is admin
        checkAdminStatus();
    }

    // =========================================================
    // Feature 9: Admin controls
    // =========================================================
    private void checkAdminStatus() {
        FirebaseUtils.getGroupMembersRef(groupId).child(currentUid).child("role")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot s) {
                        String role = s.getValue(String.class);
                        isAdmin = "admin".equals(role);
                        invalidateOptionsMenu();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
        // Also cache all member names & roles
        FirebaseUtils.getGroupMembersRef(groupId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(DataSnapshot snap) {
                        memberNames.clear(); memberAdmins.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            String uid  = c.getKey();
                            String name = c.child("name").getValue(String.class);
                            String role = c.child("role").getValue(String.class);
                            if (uid != null) {
                                memberNames.put(uid, name != null ? name : uid);
                                memberAdmins.put(uid, "admin".equals(role));
                            }
                        }
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    private void showAdminPanel() {
        List<String> displayNames = new ArrayList<>();
        List<String> uids = new ArrayList<>();
        for (Map.Entry<String, String> e : memberNames.entrySet()) {
            if (e.getKey().equals(currentUid)) continue;
            uids.add(e.getKey());
            Boolean admin = memberAdmins.get(e.getKey());
            displayNames.add(e.getValue() + (Boolean.TRUE.equals(admin) ? " [Admin]" : ""));
        }
        if (displayNames.isEmpty()) {
            Toast.makeText(this, "No other members", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Group Members")
                .setItems(displayNames.toArray(new String[0]), (d, which) -> {
                    String uid = uids.get(which);
                    showMemberActions(uid, memberNames.get(uid));
                }).show();
    }

    private void showMemberActions(String uid, String name) {
        Boolean isAdm = memberAdmins.get(uid);
        List<String> actions = new ArrayList<>();
        actions.add("Remove from group");
        actions.add(Boolean.TRUE.equals(isAdm) ? "Revoke admin" : "Make admin");

        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(actions.toArray(new String[0]), (d, which) -> {
                    if (which == 0) removeMember(uid, name);
                    else toggleAdmin(uid, name, Boolean.TRUE.equals(isAdm));
                }).show();
    }

    private void removeMember(String uid, String name) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage("This will remove " + name + " from the group.")
                .setPositiveButton("Remove", (d, w) -> {
                    FirebaseUtils.getGroupMembersRef(groupId).child(uid).removeValue();
                    // Also remove from user's groups list
                    FirebaseUtils.db().getReference("users").child(uid)
                            .child("groups").child(groupId).removeValue();
                    Toast.makeText(this, name + " removed from group", Toast.LENGTH_SHORT).show();
                    memberNames.remove(uid);
                    memberAdmins.remove(uid);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void toggleAdmin(String uid, String name, boolean wasAdmin) {
        String newRole = wasAdmin ? "member" : "admin";
        FirebaseUtils.getGroupMembersRef(groupId).child(uid).child("role").setValue(newRole);
        memberAdmins.put(uid, !wasAdmin);
        Toast.makeText(this,
                name + (wasAdmin ? " revoked admin" : " made admin"), Toast.LENGTH_SHORT).show();
    }

    private void renameGroup() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(groupName);
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this)
                .setTitle("Rename Group")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newName = et.getText().toString().trim();
                    if (newName.isEmpty()) return;
                    groupName = newName;
                    FirebaseUtils.getGroupsRef().child(groupId).child("name").setValue(newName);
                    if (getSupportActionBar() != null)
                        getSupportActionBar().setTitle(newName);
                    Toast.makeText(this, "Group renamed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // =========================================================
    // Feature 10: Group Invite Link
    // =========================================================
    private void shareInviteLink() {
        String link = "callx://join/" + groupId;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,
                "Join '" + groupName + "' on CallX!\n" + link);
        share.putExtra(Intent.EXTRA_SUBJECT, "Join my CallX group");
        startActivity(Intent.createChooser(share, "Share invite link via"));
    }

    // =========================================================
    // MessageActionListener — Feature 2-8 for groups
    // =========================================================
    @Override public void onReply(Message m) {
        replyingTo = m;
        String who = m.senderName != null ? m.senderName : "Message";
        String preview = m.text != null ? m.text : "[" + m.type + "]";
        binding.etMessage.setHint("↩ Replying to " + who + ": " + preview);
    }

    @Override public void onEdit(Message m) {
        if (!currentUid.equals(m.senderId)) return;
        android.widget.EditText et = new android.widget.EditText(this);
        et.setText(m.text);
        new AlertDialog.Builder(this)
                .setTitle("Edit message").setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = et.getText().toString().trim();
                    if (newText.isEmpty() || newText.equals(m.text)) return;
                    Map<String, Object> upd = new HashMap<>();
                    upd.put("text", newText); upd.put("edited", true);
                    FirebaseUtils.getGroupMessagesRef(groupId).child(m.id).updateChildren(upd);
                })
                .setNegativeButton("Cancel", null).show();
    }

    @Override public void onDelete(Message m) {
        String[] opts = currentUid.equals(m.senderId) || isAdmin
                ? new String[]{"Delete for me", "Delete for everyone"}
                : new String[]{"Delete for me"};
        new AlertDialog.Builder(this)
                .setTitle("Delete message")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        int idx = messages.indexOf(m);
                        if (idx >= 0) { messages.remove(idx); adapter.notifyItemRemoved(idx); }
                    } else {
                        Map<String, Object> upd = new HashMap<>();
                        upd.put("deleted", true); upd.put("text", ""); upd.put("mediaUrl", "");
                        FirebaseUtils.getGroupMessagesRef(groupId).child(m.id).updateChildren(upd);
                    }
                }).show();
    }

    @Override public void onReact(Message m) {
        String[] emojis = {"❤️", "👍", "😂", "😮", "😢", "🙏"};
        new AlertDialog.Builder(this)
                .setTitle("React")
                .setItems(emojis, (d, which) ->
                        FirebaseUtils.getGroupMessagesRef(groupId)
                                .child(m.id).child("reactions")
                                .child(currentUid).setValue(emojis[which]))
                .show();
    }

    @Override public void onForward(Message m) {
        Toast.makeText(this, "Forward not available in groups", Toast.LENGTH_SHORT).show();
    }

    @Override public void onStar(Message m) {
        boolean isStarred = Boolean.TRUE.equals(m.starred);
        FirebaseUtils.getGroupMessagesRef(groupId).child(m.id)
                .child("starred").setValue(!isStarred);
        Toast.makeText(this, isStarred ? "Unstarred" : "Starred ⭐", Toast.LENGTH_SHORT).show();
    }

    @Override public void onPin(Message m) {
        boolean isPinned = Boolean.TRUE.equals(m.pinned);
        FirebaseUtils.getGroupMessagesRef(groupId).child(m.id)
                .child("pinned").setValue(!isPinned);
        FirebaseUtils.getGroupsRef().child(groupId).child("pinnedMessageId")
                .setValue(isPinned ? null : m.id);
        Toast.makeText(this, isPinned ? "Unpinned" : "Pinned 📌", Toast.LENGTH_SHORT).show();
    }

    // =========================================================
    // Menu
    // =========================================================
    @Override public boolean onCreateOptionsMenu(Menu menu) {
        // Invite link always visible; admin actions only for admins
        menu.add(0, 1001, 0, "🔗 Invite Link");
        menu.add(0, 1002, 1, "⭐ Starred Messages");
        if (isAdmin) {
            menu.add(0, 1003, 2, "👑 Admin Panel");
            menu.add(0, 1004, 3, "✏ Rename Group");
        }
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == 1001) { shareInviteLink(); return true; }
        if (id == 1002) {
            Intent i = new Intent(this, StarredMessagesActivity.class);
            i.putExtra("chatId", groupId); i.putExtra("isGroup", true);
            startActivity(i); return true;
        }
        if (id == 1003 && isAdmin) { showAdminPanel(); return true; }
        if (id == 1004 && isAdmin) { renameGroup(); return true; }
        return super.onOptionsItemSelected(item);
    }

    // =========================================================
    // Messaging
    // =========================================================
    private void sendText() {
        String txt = binding.etMessage.getText().toString().trim();
        if (txt.isEmpty()) return;
        Message m = new Message();
        m.senderId = currentUid; m.senderName = currentName;
        m.text = txt; m.type = "text";
        m.timestamp = System.currentTimeMillis();
        m.status = "sent";
        applyReplyInfo(m);
        push(m, txt);
        binding.etMessage.setText("");
        replyingTo = null;
        binding.etMessage.setHint("Type a message");
    }

    private void applyReplyInfo(Message m) {
        if (replyingTo != null) {
            m.replyToId = replyingTo.id;
            m.replyToSenderName = replyingTo.senderName;
            m.replyToText = replyingTo.text != null ? replyingTo.text : ("[" + replyingTo.type + "]");
        }
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

    private void uploadAndSend(Uri uri, String msgType, String rt, String fileName) {
        binding.uploadProgress.setVisibility(View.VISIBLE);
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri, "callx/groups/" + msgType, rt,
                new CloudinaryUploader.UploadCallback() {
                    @Override public void onSuccess(CloudinaryUploader.Result r) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Message m = new Message();
                        m.senderId = currentUid; m.senderName = currentName;
                        m.type = msgType; m.mediaUrl = r.secureUrl;
                        m.imageUrl = "image".equals(msgType) ? r.secureUrl : null;
                        m.fileName = fileName;
                        m.fileSize = r.bytes != null ? r.bytes : size;
                        m.duration = r.durationMs;
                        m.timestamp = System.currentTimeMillis();
                        m.status = "sent";
                        applyReplyInfo(m);
                        String preview;
                        switch (msgType) {
                            case "image": preview = "📷 Photo"; break;
                            case "video": preview = "🎬 Video"; break;
                            case "audio": preview = "🎤 Voice"; break;
                            case "file":  preview = "📎 " + (fileName == null ? "File" : fileName); break;
                            default: preview = "Media";
                        }
                        push(m, preview);
                        replyingTo = null;
                    }
                    @Override public void onError(String err) {
                        binding.uploadProgress.setVisibility(View.GONE);
                        Toast.makeText(GroupChatActivity.this,
                                err == null ? "Upload fail" : err, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void push(Message m, String preview) {
        DatabaseReference ref = FirebaseUtils.getGroupMessagesRef(groupId).push();
        m.id = ref.getKey();
        ref.setValue(m);
        Map<String, Object> meta = new HashMap<>();
        meta.put("lastMessage", preview);
        meta.put("lastSenderName", currentName);
        meta.put("lastMessageAt", System.currentTimeMillis());
        FirebaseUtils.getGroupsRef().child(groupId).updateChildren(meta);
        String myPhoto = com.callx.app.CallxApp.getMyPhotoUrlCached();
        String pushType = "text".equals(m.type) ? "group_message" : m.type;
        PushNotify.notifyGroupRich(groupId, currentUid, currentName,
                myPhoto == null ? "" : myPhoto,
                m.id, pushType, preview,
                m.mediaUrl == null ? "" : m.mediaUrl);
    }

    private void loadMessages() {
        FirebaseUtils.getGroupMessagesRef(groupId).orderByChild("timestamp")
                .addChildEventListener(new ChildEventListener() {
                    @Override public void onChildAdded(DataSnapshot snap, String prev) {
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

    // =========================================================
    // Real-time header
    // =========================================================
    private void setupRealtimeHeader() {
        typingRef  = FirebaseUtils.getGroupTypingRef(groupId);
        membersRef = FirebaseUtils.getGroupMembersRef(groupId);

        typingListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                typingNames.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getKey();
                    if (uid == null || uid.equals(currentUid)) continue;
                    Object v = c.getValue();
                    String name = v == null ? "" : String.valueOf(v);
                    if (name.isEmpty() || "true".equalsIgnoreCase(name))
                        name = memberNames.containsKey(uid) ? memberNames.get(uid) : "Someone";
                    typingNames.put(uid, name);
                }
                refreshSubtitle();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        typingRef.addValueEventListener(typingListener);

        membersListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                java.util.Set<String> latest = new java.util.HashSet<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getKey();
                    if (uid != null) latest.add(uid);
                }
                totalMembers = latest.size();
                List<String> toRemove = new ArrayList<>();
                for (String uid : presenceListeners.keySet())
                    if (!latest.contains(uid)) toRemove.add(uid);
                for (String uid : toRemove) {
                    ValueEventListener l = presenceListeners.remove(uid);
                    if (l != null) FirebaseUtils.getUserRef(uid).removeEventListener(l);
                    memberLastSeen.remove(uid);
                    memberNames.remove(uid);
                }
                for (String uid : latest) {
                    if (presenceListeners.containsKey(uid)) continue;
                    if (uid.equals(currentUid)) continue;
                    subscribeMemberPresence(uid);
                }
                refreshSubtitle();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        membersRef.addValueEventListener(membersListener);
        subtitleHandler.post(subtitleRefresh);
    }

    private void subscribeMemberPresence(final String uid) {
        ValueEventListener l = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                Long ls = snap.child("lastSeen").getValue(Long.class);
                String nm = snap.child("name").getValue(String.class);
                if (nm == null) nm = snap.child("displayName").getValue(String.class);
                memberLastSeen.put(uid, ls == null ? 0L : ls);
                memberNames.put(uid, nm == null ? "Member" : nm);
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
        DatabaseReference me = FirebaseUtils.getGroupTypingRef(groupId).child(currentUid);
        if (typing) { me.setValue(currentName == null ? "Someone" : currentName); me.onDisconnect().removeValue(); }
        else me.removeValue();
    }

    private void refreshSubtitle() {
        if (getSupportActionBar() == null) return;
        if (!typingNames.isEmpty()) {
            String sub;
            if (typingNames.size() == 1)
                sub = typingNames.values().iterator().next() + " typing…";
            else if (typingNames.size() == 2) {
                java.util.Iterator<String> it = typingNames.values().iterator();
                sub = it.next() + " & " + it.next() + " typing…";
            } else sub = typingNames.size() + " people typing…";
            getSupportActionBar().setSubtitle(sub); return;
        }
        long now = System.currentTimeMillis();
        int online = 0;
        for (Long ls : memberLastSeen.values())
            if (ls != null && (now - ls) < com.callx.app.utils.Constants.ONLINE_WINDOW_MS) online++;
        int members = totalMembers > 0 ? totalMembers : (memberLastSeen.size() + 1);
        String sub = members + (members == 1 ? " member" : " members");
        if (online > 0) sub = online + " online • " + sub;
        getSupportActionBar().setSubtitle(sub);
    }

    @Override protected void onPause() {
        typingStopHandler.removeCallbacks(typingStopRunnable);
        setMyTyping(false);
        super.onPause();
    }

    @Override protected void onDestroy() {
        subtitleHandler.removeCallbacks(subtitleRefresh);
        typingStopHandler.removeCallbacks(typingStopRunnable);
        setMyTyping(false);
        if (typingRef != null && typingListener != null)
            typingRef.removeEventListener(typingListener);
        if (membersRef != null && membersListener != null)
            membersRef.removeEventListener(membersListener);
        for (Map.Entry<String, ValueEventListener> e : presenceListeners.entrySet())
            FirebaseUtils.getUserRef(e.getKey()).removeEventListener(e.getValue());
        presenceListeners.clear();
        super.onDestroy();
    }
}
