package com.callx.app.activities;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.LinearLayout;
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
public class ChatActivity extends AppCompatActivity {
    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private String partnerUid, partnerName, chatId, currentUid, currentName;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();
    private boolean isRecording = false;
    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker;
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
        // WhatsApp style: chat khulte hi mera unread counter reset
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
        binding.rvMessages.setAdapter(adapter);
        setupTextWatcher();
        setupPickers();
        binding.btnAttach.setOnClickListener(v -> showAttachSheet());
        binding.btnCamera.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnMic.setOnClickListener(v -> toggleRecording());
        loadMessages();
        // Feature 13/14 — agar partner ne mujhe perma-block kiya hai
        // to chat input disable karke special-request CTA dikhao.
        watchPartnerPermaBlock();
    }
    private boolean partnerPermaBlockedMe = false;
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
            binding.etMessage.setHint("Type a message");
            return;
        }
        binding.etMessage.setEnabled(false);
        binding.etMessage.setHint(
            partnerName + " ne aapko block kiya — Send special request");
        binding.btnSend.setVisibility(View.GONE);
        binding.btnMic.setVisibility(View.GONE);
        // Show CTA Snackbar with action
        com.google.android.material.snackbar.Snackbar.make(binding.getRoot(),
                partnerName + " ne aapko permanently block kiya hai",
                com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            .setAction("Special request", v -> openSpecialRequestDialog())
            .show();
    }
    private void openSpecialRequestDialog() {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Apna message likho (e.g. Sorry, please unblock me)");
        et.setMinLines(2);
        et.setMaxLines(4);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Send special request")
            .setMessage(partnerName + " ko ek baar request bhejo. " +
                "Wo accept kare to aap message kar sakte ho.")
            .setView(et)
            .setPositiveButton("Send", (d, w) -> {
                String txt = et.getText().toString().trim();
                if (txt.isEmpty()) txt = "Please unblock me";
                // Save in RTDB so receiver ka chat list highlight ho jaaye (Feature 18/19)
                java.util.Map<String, Object> entry = new java.util.HashMap<>();
                entry.put("text",     txt);
                entry.put("ts",       System.currentTimeMillis());
                entry.put("fromName", currentName);
                entry.put("fromUid",  currentUid);
                FirebaseUtils.db().getReference("specialRequests")
                    .child(partnerUid).child(currentUid).setValue(entry);
                // FCM push (force=true server side perma-block bypass)
                com.callx.app.utils.PushNotify.notifySpecialRequest(
                    partnerUid, currentUid, currentName, txt);
                Toast.makeText(this, "Special request bhej diya",
                    Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
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
                String name = FileUtils.fileName(this, uri);
                uploadAndSend(uri, "file", "raw", name);
            });
    }
    private void showAttachSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);
        View v = LayoutInflater.from(this)
            .inflate(R.layout.bottom_sheet_attach, null);
        v.findViewById(R.id.opt_gallery).setOnClickListener(x -> {
            sheet.dismiss(); imagePicker.launch("image/*");
        });
        v.findViewById(R.id.opt_video).setOnClickListener(x -> {
            sheet.dismiss(); videoPicker.launch("video/*");
        });
        v.findViewById(R.id.opt_audio).setOnClickListener(x -> {
            sheet.dismiss(); audioPicker.launch("audio/*");
        });
        v.findViewById(R.id.opt_file).setOnClickListener(x -> {
            sheet.dismiss(); filePicker.launch("application/pdf");
        });
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
                Toast.makeText(this, "Recording... mic dabake rok do",
                    Toast.LENGTH_SHORT).show();
                binding.btnMic.setBackgroundResource(R.drawable.circle_reject);
            } else {
                Toast.makeText(this, "Recording start nahi hui",
                    Toast.LENGTH_SHORT).show();
            }
        } else {
            isRecording = false;
            binding.btnMic.setBackgroundResource(R.drawable.circle_primary);
            Uri uri = recorder.stop(this);
            if (uri != null) {
                uploadAndSend(uri, "audio", "video", null);
            } else {
                Toast.makeText(this, "Recording empty thi",
                    Toast.LENGTH_SHORT).show();
            }
        }
    }
    private void sendText() {
        String txt = binding.etMessage.getText().toString().trim();
        if (txt.isEmpty()) return;
        Message m = new Message();
        m.senderId  = currentUid;
        m.senderName= currentName;
        m.text      = txt;
        m.type      = "text";
        m.timestamp = System.currentTimeMillis();
        pushMessage(m, txt);
        binding.etMessage.setText("");
    }
    private void uploadAndSend(Uri uri, String msgType, String resourceType,
                               String fileName) {
        binding.uploadProgress.setVisibility(View.VISIBLE);
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri, "callx/" + msgType,
            resourceType, new CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    binding.uploadProgress.setVisibility(View.GONE);
                    Message m = new Message();
                    m.senderId  = currentUid;
                    m.senderName= currentName;
                    m.type      = msgType;
                    m.mediaUrl  = r.secureUrl;
                    m.imageUrl  = "image".equals(msgType) ? r.secureUrl : null;
                    m.fileName  = fileName;
                    m.fileSize  = r.bytes != null ? r.bytes : size;
                    m.duration  = r.durationMs;
                    m.timestamp = System.currentTimeMillis();
                    String preview;
                    switch (msgType) {
                        case "image": preview = "📷 Photo"; break;
                        case "video": preview = "🎬 Video"; break;
                        case "audio": preview = "🎤 Voice message"; break;
                        case "file":  preview = "📎 " +
                            (fileName != null ? fileName : "File"); break;
                        default: preview = "Media";
                    }
                    pushMessage(m, preview);
                }
                @Override public void onError(String err) {
                    binding.uploadProgress.setVisibility(View.GONE);
                    Toast.makeText(ChatActivity.this,
                        err == null ? "Upload fail" : err,
                        Toast.LENGTH_LONG).show();
                }
            });
    }
    private void pushMessage(Message m, String preview) {
        DatabaseReference ref = FirebaseUtils.getMessagesRef(chatId).push();
        m.id = ref.getKey();
        ref.setValue(m);
        // Update lastMessage on contacts (sender side: zero unread, recipient side: bump)
        Map<String, Object> meSide = new HashMap<>();
        meSide.put("lastMessage", preview);
        meSide.put("lastMessageAt", System.currentTimeMillis());
        meSide.put("unread", 0);
        FirebaseUtils.getContactsRef(currentUid).child(partnerUid)
            .updateChildren(meSide);

        Map<String, Object> partnerSide = new HashMap<>();
        partnerSide.put("lastMessage", preview);
        partnerSide.put("lastMessageAt", System.currentTimeMillis());
        partnerSide.put("unread", ServerValue.increment(1));
        FirebaseUtils.getContactsRef(partnerUid).child(currentUid)
            .updateChildren(partnerSide);

        String mediaUrl = m.mediaUrl == null ? "" : m.mediaUrl;
        PushNotify.notifyMessage(partnerUid, currentUid, currentName,
            chatId, m.id, preview, "message", mediaUrl);
    }
    private long lastTypingPingAt = 0L;
    private void maybeSendTypingPing() {
        long now = System.currentTimeMillis();
        if (now - lastTypingPingAt < 4000L) return;
        lastTypingPingAt = now;
        if (partnerUid == null || currentUid == null || chatId == null) return;
        PushNotify.notifyTyping(partnerUid, currentUid, currentName, chatId);
    }
    @Override protected void onResume() {
        super.onResume();
        // Chat screen visible hai to unread hamesha 0 rahe
        if (currentUid != null && partnerUid != null) {
            FirebaseUtils.getContactsRef(currentUid).child(partnerUid)
                .child("unread").setValue(0);
        }
    }
    private void loadMessages() {
        FirebaseUtils.getMessagesRef(chatId).orderByChild("timestamp")
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
        return super.onOptionsItemSelected(item);
    }
}
