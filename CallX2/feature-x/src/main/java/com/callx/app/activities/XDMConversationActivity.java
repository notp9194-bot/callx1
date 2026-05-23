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
import com.callx.app.utils.ImageCompressor;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * XDMConversationActivity — Direct message thread in the X module.
 *
 * v24 changes:
 *   ✅ Image DMs now compressed via ImageCompressor before upload
 *   ✅ Fallback to direct upload if compression fails
 */
public class XDMConversationActivity extends AppCompatActivity {

    private String myUid, otherUid, convId, otherName, otherHandle, otherPhoto, otherThumb;
    private RecyclerView rvMessages;
    private com.callx.app.adapters.XDMAdapter adapter;
    private EditText etMessage;
    private ValueEventListener msgListener;

    private final ActivityResultLauncher<Intent> mediaPicker =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Uri uri = result.getData().getData();
                if (uri != null) compressAndSendImage(uri);
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
        otherThumb  = getIntent().getStringExtra("other_thumb");

        if (convId == null && otherUid != null)
            convId = XFirebaseUtils.dmConversationId(myUid, otherUid);

        // Header
        TextView tvTitle  = findViewById(R.id.tv_dm_conv_title);
        TextView tvHandle = findViewById(R.id.tv_dm_conv_handle);
        ImageView ivAvatar= findViewById(R.id.iv_dm_conv_avatar);
        if (tvTitle  != null && otherName   != null) tvTitle.setText(otherName);
        if (tvHandle != null && otherHandle != null) tvHandle.setText("@" + otherHandle);
        if (ivAvatar != null) {
            String displayUrl = (otherThumb != null && !otherThumb.isEmpty()) ? otherThumb : otherPhoto;
            if (displayUrl != null) Glide.with(this).load(displayUrl).circleCrop().into(ivAvatar);
        }

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
            @Override public void onCancelled(DatabaseError e) {}
        };
        XFirebaseUtils.xDmMessagesRef(convId).limitToLast(100).addValueEventListener(msgListener);
    }

    private void sendTextMessage() {
        String text = etMessage != null ? etMessage.getText().toString().trim() : "";
        if (text.isEmpty()) return;
        etMessage.setText("");
        pushMessage(text, null, null);
    }

    // ── Image: compress → upload ──────────────────────────────────────────

    /**
     * Compress picked image via ImageCompressor, then upload to Cloudinary.
     * Falls back to direct upload if compression fails.
     */
    private void compressAndSendImage(Uri uri) {
        ImageCompressor.compress(this, uri, new ImageCompressor.Callback() {
            @Override
            public void onSuccess(ImageCompressor.Result result) {
                Uri compressedUri = Uri.fromFile(result.fullFile);
                uploadImageAndSend(compressedUri, result.thumbFile);
            }

            @Override
            public void onError(Exception e) {
                android.util.Log.w("XDM", "Image compress failed, fallback to original", e);
                uploadImageAndSend(uri, null);
            }
        });
    }

    private void uploadImageAndSend(Uri uri, java.io.File thumbFile) {
        XCloudinaryUtils.uploadTweetImage(this, uri, new XCloudinaryUtils.XUploadListener() {
            @Override public void onSuccess(String pid, String url) {
                // Clean up temp file after successful upload
                if (thumbFile != null) {
                    try { thumbFile.delete(); } catch (Exception ignored) {}
                }
                runOnUiThread(() -> pushMessage(null, url, "image"));
            }
            @Override public void onError(String msg) {
                if (thumbFile != null) {
                    try { thumbFile.delete(); } catch (Exception ignored) {}
                }
                runOnUiThread(() -> Toast.makeText(XDMConversationActivity.this,
                    "Upload failed", Toast.LENGTH_SHORT).show());
            }
            @Override public void onProgress(int pct) {}
        });
    }

    // ── Push message to Firebase ──────────────────────────────────────────

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
        preview.put("otherThumb", otherThumb != null ? otherThumb : "");
        preview.put("myUid", myUid);
        XFirebaseUtils.xDmConversationsRef(myUid).child(convId).updateChildren(preview);

        // Mirror for the recipient
        java.util.Map<String, Object> recipientPreview = new java.util.HashMap<>(preview);
        com.callx.app.utils.FirebaseUtils.getUserRef(myUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    String myName  = snap.child("name").getValue(String.class);
                    String myPhoto = snap.child("photoUrl").getValue(String.class);
                    String myThumb = snap.child("thumbUrl").getValue(String.class);
                    recipientPreview.put("otherUid",    myUid);
                    recipientPreview.put("otherName",   myName  != null ? myName  : "User");
                    recipientPreview.put("otherPhoto",  myPhoto != null ? myPhoto : "");
                    recipientPreview.put("otherThumb",  myThumb != null ? myThumb : "");
                    recipientPreview.put("otherHandle", myUid);
                    recipientPreview.put("myUid",       otherUid);
                    recipientPreview.put("unread",      true);
                    XFirebaseUtils.xDmConversationsRef(otherUid).child(convId)
                        .updateChildren(recipientPreview);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    private void markSeen() {
        if (convId == null || myUid.isEmpty()) return;
        XFirebaseUtils.xDmConversationsRef(myUid).child(convId).child("unread").setValue(false);
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
                @Override public void onCancelled(DatabaseError e) {}
            });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (msgListener != null)
            XFirebaseUtils.xDmMessagesRef(convId).removeEventListener(msgListener);
    }
}
