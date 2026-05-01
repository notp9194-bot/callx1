package com.callx.app.activities;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
public class GroupChatActivity extends AppCompatActivity {
    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private String groupId, groupName, currentUid, currentName;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();
    private boolean isRecording = false;
    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker;
    // ---- Real-time header (online + typing) state ----
    private DatabaseReference typingRef;     // groups/{gid}/typing
    private DatabaseReference membersRef;    // groups/{gid}/members
    private ValueEventListener typingListener, membersListener;
    // memberUid -> ValueEventListener on users/{uid}/lastSeen
    private final Map<String, ValueEventListener> presenceListeners = new HashMap<>();
    private final Map<String, Long>    memberLastSeen = new HashMap<>();
    private final Map<String, String>  memberNames    = new HashMap<>();
    private final Map<String, String>  typingNames    = new HashMap<>(); // typing uid -> name
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
            // Online/offline boundary slide karte rehta hai → har 30s recompute
            subtitleHandler.postDelayed(this, 30_000L);
        }
    };
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
        binding.rvMessages.setAdapter(adapter);
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(has ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(has ? View.GONE : View.VISIBLE);
                // Typing indicator — set true on input, auto-clear after 4s idle
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
                String name = FileUtils.fileName(this, uri);
                uploadAndSend(uri, "file", "raw", name);
            });
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
    }
    private void sendText() {
        String txt = binding.etMessage.getText().toString().trim();
        if (txt.isEmpty()) return;
        Message m = new Message();
        m.senderId = currentUid;
        m.senderName = currentName;
        m.text = txt;
        m.type = "text";
        m.timestamp = System.currentTimeMillis();
        push(m, txt);
        binding.etMessage.setText("");
    }
    private void uploadAndSend(Uri uri, String msgType, String rt, String fileName) {
        binding.uploadProgress.setVisibility(View.VISIBLE);
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri, "callx/groups/" + msgType, rt,
            new CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    binding.uploadProgress.setVisibility(View.GONE);
                    Message m = new Message();
                    m.senderId = currentUid;
                    m.senderName = currentName;
                    m.type = msgType;
                    m.mediaUrl = r.secureUrl;
                    m.imageUrl = "image".equals(msgType) ? r.secureUrl : null;
                    m.fileName = fileName;
                    m.fileSize = r.bytes != null ? r.bytes : size;
                    m.duration = r.durationMs;
                    m.timestamp = System.currentTimeMillis();
                    String preview;
                    switch (msgType) {
                        case "image": preview = "📷 Photo"; break;
                        case "video": preview = "🎬 Video"; break;
                        case "audio": preview = "🎤 Voice"; break;
                        case "file":  preview = "📎 " +
                            (fileName == null ? "File" : fileName); break;
                        default: preview = "Media";
                    }
                    push(m, preview);
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
        meta.put("lastMessage",     preview);
        meta.put("lastSenderName",  currentName);
        meta.put("lastMessageAt",   System.currentTimeMillis());
        FirebaseUtils.getGroupsRef().child(groupId).updateChildren(meta);
        // Server fanout — sender ka apna photo cache se nikal ke bhejo,
        // taaki receiver side notification me sender ka avatar dikhe.
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
                    if (m != null) {
                        if (m.id == null) m.id = snap.getKey();
                        messages.add(m);
                        adapter.notifyItemInserted(messages.size() - 1);
                        binding.rvMessages.scrollToPosition(messages.size() - 1);
                    }
                }
                @Override public void onChildChanged(DataSnapshot s, String p) {}
                @Override public void onChildRemoved(DataSnapshot s) {}
                @Override public void onChildMoved(DataSnapshot s, String p) {}
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    // ===================================================================
    // Real-time toolbar header — typing + online + member count (WhatsApp)
    // ===================================================================
    private void setupRealtimeHeader() {
        typingRef  = FirebaseUtils.getGroupTypingRef(groupId);
        membersRef = FirebaseUtils.getGroupMembersRef(groupId);

        // Typing watcher — kaun-kaun type kar raha hai (mere alawa)
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                typingNames.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getKey();
                    if (uid == null || uid.equals(currentUid)) continue;
                    Object v = c.getValue();
                    String name = v == null ? "" : String.valueOf(v);
                    if (name.isEmpty() || "true".equalsIgnoreCase(name)) {
                        name = memberNames.containsKey(uid)
                            ? memberNames.get(uid) : "Someone";
                    }
                    typingNames.put(uid, name);
                }
                refreshSubtitle();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        typingRef.addValueEventListener(typingListener);

        // Members watcher — list change pe presence subscriptions sync karo
        membersListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                java.util.Set<String> latest = new java.util.HashSet<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid = c.getKey();
                    if (uid != null) latest.add(uid);
                }
                totalMembers = latest.size();
                // Stale subscriptions hatao
                java.util.List<String> toRemove = new ArrayList<>();
                for (String uid : presenceListeners.keySet())
                    if (!latest.contains(uid)) toRemove.add(uid);
                for (String uid : toRemove) {
                    ValueEventListener l = presenceListeners.remove(uid);
                    if (l != null) FirebaseUtils.getUserRef(uid).removeEventListener(l);
                    memberLastSeen.remove(uid);
                    memberNames.remove(uid);
                }
                // Naye members ke liye presence sub karo
                for (String uid : latest) {
                    if (presenceListeners.containsKey(uid)) continue;
                    if (uid.equals(currentUid)) continue; // self ignore
                    subscribeMemberPresence(uid);
                }
                refreshSubtitle();
            }
            @Override public void onCancelled(DatabaseError e) {}
        };
        membersRef.addValueEventListener(membersListener);

        // 30s pe subtitle re-render (online window slide)
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
        DatabaseReference me = FirebaseUtils
            .getGroupTypingRef(groupId).child(currentUid);
        if (typing) {
            me.setValue(currentName == null ? "Someone" : currentName);
            me.onDisconnect().removeValue();
        } else {
            me.removeValue();
        }
    }
    private void refreshSubtitle() {
        if (getSupportActionBar() == null) return;
        // Priority 1: typing
        if (!typingNames.isEmpty()) {
            String sub;
            if (typingNames.size() == 1) {
                sub = typingNames.values().iterator().next() + " typing…";
            } else if (typingNames.size() == 2) {
                java.util.Iterator<String> it = typingNames.values().iterator();
                sub = it.next() + " & " + it.next() + " typing…";
            } else {
                sub = typingNames.size() + " people typing…";
            }
            getSupportActionBar().setSubtitle(sub);
            return;
        }
        // Priority 2: online count + members
        long now = System.currentTimeMillis();
        int online = 0;
        for (Long ls : memberLastSeen.values()) {
            if (ls != null && (now - ls) < com.callx.app.utils.Constants.ONLINE_WINDOW_MS)
                online++;
        }
        int members = totalMembers > 0 ? totalMembers : (memberLastSeen.size() + 1);
        String sub = members + (members == 1 ? " member" : " members");
        if (online > 0) sub = online + " online • " + sub;
        getSupportActionBar().setSubtitle(sub);
    }
    @Override
    protected void onPause() {
        // App background ya activity left → typing flag turant clear
        typingStopHandler.removeCallbacks(typingStopRunnable);
        setMyTyping(false);
        super.onPause();
    }
    @Override
    protected void onDestroy() {
        // Saare realtime listeners detach karo (memory leak / wasted reads se bachao)
        subtitleHandler.removeCallbacks(subtitleRefresh);
        typingStopHandler.removeCallbacks(typingStopRunnable);
        setMyTyping(false);
        if (typingRef  != null && typingListener  != null)
            typingRef.removeEventListener(typingListener);
        if (membersRef != null && membersListener != null)
            membersRef.removeEventListener(membersListener);
        for (Map.Entry<String, ValueEventListener> e : presenceListeners.entrySet()) {
            FirebaseUtils.getUserRef(e.getKey()).removeEventListener(e.getValue());
        }
        presenceListeners.clear();
        super.onDestroy();
    }
}
