package com.callx.app.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.XDMMessage;
import com.callx.app.utils.XCloudinaryUtils;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

public class XDMConversationActivity extends AppCompatActivity {

    private String myUid, otherUid, convId, otherName, otherHandle, otherPhoto;
    private RecyclerView rvMessages;
    private com.callx.app.adapters.XDMAdapter adapter;
    private EditText etMessage;
    private ValueEventListener msgListener;
    private long lastSeenTs = 0;

    private final ActivityResultLauncher<Intent> mediaPicker =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) sendMediaMessage(uri);
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_dm_conversation);

        myUid       = FirebaseAuth.getInstance().getCurrentUser() != null
            ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";
        otherUid    = getIntent().getStringExtra("other_uid");
        convId      = getIntent().getStringExtra("conversation_id");
        otherName   = getIntent().getStringExtra("other_name");
        otherHandle = getIntent().getStringExtra("other_handle");
        otherPhoto  = getIntent().getStringExtra("other_photo");

        if (convId == null && otherUid != null)
            convId = XFirebaseUtils.dmConversationId(myUid, otherUid);

        // Header
        TextView tvTitle  = findViewById(R.id.tv_dm_conv_title);
        TextView tvHandle = findViewById(R.id.tv_dm_conv_handle);
        ImageView ivAvatar= findViewById(R.id.iv_dm_conv_avatar);
        if (tvTitle  != null && otherName   != null) tvTitle.setText(otherName);
        if (tvHandle != null && otherHandle != null) tvHandle.setText("@" + otherHandle);
        if (ivAvatar != null && otherPhoto  != null)
            Glide.with(this).load(otherPhoto).circleCrop().into(ivAvatar);

        // Profile tap
        if (ivAvatar != null && otherUid != null) {
            ivAvatar.setOnClickListener(v ->
                startActivity(new Intent(this, XProfileActivity.class).putExtra("uid", otherUid)));
        }

        // Back
        View btnBack = findViewById(R.id.btn_dm_conv_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Messages
        rvMessages = findViewById(R.id.rv_dm_messages);
        adapter = new com.callx.app.adapters.XDMAdapter(this, myUid);
        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        rvMessages.setAdapter(adapter);

        // Input
        etMessage     = findViewById(R.id.et_dm_message);
        View btnSend  = findViewById(R.id.btn_dm_send);
        View btnMedia = findViewById(R.id.btn_dm_media);
        if (btnSend  != null) btnSend.setOnClickListener(v -> sendTextMessage());
        if (btnMedia != null) btnMedia.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            mediaPicker.launch(i);
        });

        // Mark conversation as seen
        markSeen();
        loadMessages();
    }

    private void loadMessages() {
        msgListener = new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<XDMMessage> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XDMMessage m = ds.getValue(XDMMessage.class);
                    if (m != null) { m.id = ds.getKey(); list.add(m); }
                }
                adapter.setMessages(list);
                if (!list.isEmpty())
                    rvMessages.scrollToPosition(list.size() - 1);
                markSeen();
            }
            @Override public void onCancelled(DataSnapshot e) {}
        };
        XFirebaseUtils.xDmMessagesRef(convId).limitToLast(100).addValueEventListener(msgListener);
    }

    private void sendTextMessage() {
        String text = etMessage != null ? etMessage.getText().toString().trim() : "";
        if (text.isEmpty()) return;
        etMessage.setText("");
        pushMessage(text, null, null);
    }

    private void sendMediaMessage(Uri uri) {
        XCloudinaryUtils.uploadTweetImage(this, uri, new XCloudinaryUtils.XUploadListener() {
            @Override public void onSuccess(String pid, String url) {
                runOnUiThread(() -> pushMessage(null, url, "image"));
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> Toast.makeText(XDMConversationActivity.this,
                    "Upload failed", Toast.LENGTH_SHORT).show());
            }
            @Override public void onProgress(int pct) {}
        });
    }

    private void pushMessage(String text, String mediaUrl, String mediaType) {
        XDMMessage msg = new XDMMessage();
        msg.senderUid  = myUid;
        msg.text       = text;
        msg.mediaUrl   = mediaUrl;
        msg.mediaType  = mediaType;
        msg.timestamp  = System.currentTimeMillis();
        msg.seen       = false;

        String key = XFirebaseUtils.xDmMessagesRef(convId).push().getKey();
        if (key == null) return;
        msg.id = key;
        XFirebaseUtils.xDmMessagesRef(convId).child(key).setValue(msg);

        // Update conversation preview for BOTH participants
        java.util.Map<String, Object> preview = new java.util.HashMap<>();
        preview.put("lastMessage", text != null ? text : (mediaType != null ? "[" + mediaType + "]" : ""));
        preview.put("lastMessageTs", msg.timestamp);
        preview.put("lastSenderUid", myUid);
        preview.put("otherUid", otherUid);
        preview.put("otherName", otherName);
        preview.put("otherHandle", otherHandle);
        preview.put("otherPhoto", otherPhoto);
        preview.put("myUid", myUid);
        XFirebaseUtils.xDmConversationsRef(myUid).child(convId).updateChildren(preview);

        // Mirror for the recipient with correct otherUid = myUid
        java.util.Map<String, Object> recipientPreview = new java.util.HashMap<>(preview);
        // Get my own name/photo to set as "otherName" from recipient's perspective
        com.callx.app.utils.FirebaseUtils.getUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String myName  = snap.child("name").getValue(String.class);
                    String myPhoto = snap.child("photoUrl").getValue(String.class);
                    recipientPreview.put("otherUid", myUid);
                    recipientPreview.put("otherName", myName != null ? myName : "User");
                    recipientPreview.put("otherPhoto", myPhoto != null ? myPhoto : "");
                    recipientPreview.put("otherHandle", myUid);  // fallback
                    recipientPreview.put("myUid", otherUid);
                    recipientPreview.put("unread", true);
                    XFirebaseUtils.xDmConversationsRef(otherUid).child(convId)
                        .updateChildren(recipientPreview);
                }
                @Override public void onCancelled(DataSnapshot e) {}
            });
    }

    private void markSeen() {
        if (convId == null || myUid.isEmpty()) return;
        XFirebaseUtils.xDmConversationsRef(myUid).child(convId).child("unread").setValue(false);
        // Mark individual messages as seen
        XFirebaseUtils.xDmMessagesRef(convId)
            .orderByChild("senderUid").equalTo(otherUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    for (DataSnapshot ds : snap.getChildren()) {
                        Boolean seen = ds.child("seen").getValue(Boolean.class);
                        if (!Boolean.TRUE.equals(seen))
                            ds.getRef().child("seen").setValue(true);
                    }
                }
                @Override public void onCancelled(DataSnapshot e) {}
            });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (msgListener != null)
            XFirebaseUtils.xDmMessagesRef(convId).removeEventListener(msgListener);
    }
}
