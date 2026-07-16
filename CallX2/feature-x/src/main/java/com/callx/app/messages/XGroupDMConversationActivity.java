package com.callx.app.messages;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.XDMMessage;
import com.callx.app.utils.ImageCompressor;
import com.callx.app.utils.PushNotify;
import com.callx.app.utils.XCloudinaryUtils;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;

/**
 * XGroupDMConversationActivity — Production-grade Group DM conversation.
 *
 * Features:
 *   ✅ Real-time messages via Firebase RTDB (x/dm_group_messages/{groupId})
 *   ✅ Typing indicator — {groupId}/{myUid} → isTyping node
 *   ✅ Seen state — per-member last-seen message ID
 *   ✅ Emoji reactions with long-press picker
 *   ✅ Reply-to message (swipe or long-press)
 *   ✅ Image & video sending via Cloudinary
 *   ✅ System messages (join/leave/rename)
 *   ✅ Group info navigation
 *   ✅ Proper listener cleanup (no leaks)
 *
 * Intent extras:
 *   "group_id"    — Firebase key for the group (required)
 *   "group_name"  — display name in toolbar
 *   "group_photo" — icon URL for toolbar avatar
 */
public class XGroupDMConversationActivity extends AppCompatActivity {

    private static final String[] REACTION_EMOJIS = {"❤️", "😂", "😮", "😢", "😠", "👍"};
    private static final long TYPING_DEBOUNCE_MS  = 2000;

    // State
    private String myUid, myName, myThumbUrl;
    private String groupId, groupName, groupPhoto;

    // UI
    private RecyclerView rvMessages;
    private XGroupDMAdapter adapter;
    private EditText etMessage;
    private TextView tvTypingIndicator;
    private View vReplyPreview;
    private TextView tvReplyPreviewText;
    private ImageView ivToolbarAvatar;
    private TextView tvToolbarTitle, tvToolbarSubtitle;

    // Firebase listeners
    private ValueEventListener msgListener;
    private ValueEventListener typingListener;
    private ValueEventListener membersListener;

    // Typing debounce
    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private Runnable stopTypingRunnable;
    private boolean amTyping = false;

    // Reply-to
    private XGroupDMMessage replyToMsg = null;

    // Member count for "N members" subtitle
    private int memberCount = 0;
    private final Map<String, String> memberNames = new HashMap<>();

    // Media launchers
    private final ActivityResultLauncher<Intent> imagePicker =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                Uri uri = r.getData().getData();
                if (uri != null) compressAndSendImage(uri);
            }
        });

    private final ActivityResultLauncher<Intent> videoPicker =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), r -> {
            if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                Uri uri = r.getData().getData();
                if (uri != null) uploadAndSendVideo(uri);
            }
        });

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_gdm_conversation);

        com.google.firebase.auth.FirebaseUser me =
                FirebaseAuth.getInstance().getCurrentUser();
        myUid = me != null ? me.getUid() : "";

        groupId    = getIntent().getStringExtra("group_id");
        groupName  = getIntent().getStringExtra("group_name");
        groupPhoto = getIntent().getStringExtra("group_photo");

        if (groupId == null || myUid.isEmpty()) {
            finish();
            return;
        }

        // Load my profile snapshot for sender fields
        loadMyProfile();

        setupViews();
        listenMembers();
        listenMessages();
        listenTyping();
        markSeen();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────

    private void setupViews() {
        // Toolbar
        ivToolbarAvatar  = findViewById(R.id.iv_gdm_toolbar_avatar);
        tvToolbarTitle   = findViewById(R.id.tv_gdm_toolbar_title);
        tvToolbarSubtitle= findViewById(R.id.tv_gdm_toolbar_subtitle);

        if (tvToolbarTitle != null)
            tvToolbarTitle.setText(groupName != null ? groupName : "Group");
        if (groupPhoto != null && ivToolbarAvatar != null)
            Glide.with(this).load(groupPhoto).circleCrop()
                    .placeholder(R.drawable.ic_group).into(ivToolbarAvatar);

        // Back
        View btnBack = findViewById(R.id.btn_gdm_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        // Group info tap → placeholder (future: XGroupDMInfoActivity)
        if (tvToolbarTitle  != null) tvToolbarTitle.setOnClickListener(v -> showGroupInfo());
        if (ivToolbarAvatar != null) ivToolbarAvatar.setOnClickListener(v -> showGroupInfo());

        // RecyclerView
        rvMessages = findViewById(R.id.rv_gdm_messages);
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        rvMessages.setLayoutManager(llm);
        rvMessages.setItemAnimator(null);

        adapter = new XGroupDMAdapter(this, myUid, groupId);
        adapter.setReactionListener(new XGroupDMAdapter.OnReactionListener() {
            @Override public void onLongPress(XGroupDMMessage msg, View anchor) {
                showReactionPicker(msg, anchor);
            }
            @Override public void onReact(XGroupDMMessage msg, String emoji) {
                toggleReaction(msg, emoji);
            }
        });
        rvMessages.setAdapter(adapter);

        // Typing indicator
        tvTypingIndicator = findViewById(R.id.tv_gdm_typing_indicator);

        // Reply bar
        vReplyPreview     = findViewById(R.id.layout_gdm_reply_preview);
        tvReplyPreviewText= findViewById(R.id.tv_gdm_reply_preview_text);
        View btnCancelReply = findViewById(R.id.btn_gdm_cancel_reply);
        if (btnCancelReply != null) btnCancelReply.setOnClickListener(v -> clearReply());

        // Message input
        etMessage = findViewById(R.id.et_gdm_message);
        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                handleTyping(s.length() > 0);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        // Send button
        View btnSend = findViewById(R.id.btn_gdm_send);
        if (btnSend != null) btnSend.setOnClickListener(v -> sendTextMessage());

        // Attach button
        View btnAttach = findViewById(R.id.btn_gdm_attach);
        if (btnAttach != null) btnAttach.setOnClickListener(v -> showAttachMenu());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Firebase — messages
    // ─────────────────────────────────────────────────────────────────────

    private void listenMessages() {
        DatabaseReference ref = XFirebaseUtils.xDmGroupMessagesRef(groupId);
        msgListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<XGroupDMMessage> list = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    XGroupDMMessage m = parseMessage(ds);
                    if (m != null) list.add(m);
                }
                // Sort by timestamp (Firebase should already be ordered but be defensive)
                list.sort(Comparator.comparingLong(x -> x.timestamp));
                adapter.setMessages(list);
                // Scroll to bottom
                if (!list.isEmpty())
                    rvMessages.smoothScrollToPosition(list.size() - 1);
                markSeen();
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        ref.orderByChild("timestamp").limitToLast(100)
           .addValueEventListener(msgListener);
    }

    private XGroupDMMessage parseMessage(DataSnapshot ds) {
        try {
            XGroupDMMessage m = new XGroupDMMessage();
            m.id              = ds.getKey();
            m.senderUid       = ds.child("senderUid").getValue(String.class);
            m.senderName      = ds.child("senderName").getValue(String.class);
            m.senderThumbUrl  = ds.child("senderThumbUrl").getValue(String.class);
            m.text            = ds.child("text").getValue(String.class);
            m.mediaUrl        = ds.child("mediaUrl").getValue(String.class);
            m.mediaType       = ds.child("mediaType").getValue(String.class);
            Long ts           = ds.child("timestamp").getValue(Long.class);
            m.timestamp       = ts != null ? ts : 0;
            m.replyToMsgId    = ds.child("replyToMsgId").getValue(String.class);
            m.replyToText     = ds.child("replyToText").getValue(String.class);
            m.replyToSenderName = ds.child("replyToSenderName").getValue(String.class);
            Boolean sys       = ds.child("isSystemMessage").getValue(Boolean.class);
            m.isSystemMessage = Boolean.TRUE.equals(sys);
            m.systemText      = ds.child("systemText").getValue(String.class);

            // Reactions: {emoji: {uid: true}}
            if (ds.child("reactions").exists()) {
                m.reactions = new HashMap<>();
                for (DataSnapshot emojiSnap : ds.child("reactions").getChildren()) {
                    Map<String, Boolean> users = new HashMap<>();
                    for (DataSnapshot uidSnap : emojiSnap.getChildren()) {
                        users.put(uidSnap.getKey(), Boolean.TRUE.equals(uidSnap.getValue(Boolean.class)));
                    }
                    m.reactions.put(emojiSnap.getKey(), users);
                }
            }
            return m;
        } catch (Exception e) {
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Firebase — members
    // ─────────────────────────────────────────────────────────────────────

    private void listenMembers() {
        DatabaseReference ref = XFirebaseUtils.xDmGroupRef(groupId).child("members");
        membersListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                memberNames.clear();
                for (DataSnapshot ds : snap.getChildren()) {
                    String uid  = ds.getKey();
                    String name = ds.child("name").getValue(String.class);
                    if (uid != null && name != null) memberNames.put(uid, name);
                }
                memberCount = memberNames.size();
                if (tvToolbarSubtitle != null)
                    tvToolbarSubtitle.setText(memberCount + " members");
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        ref.addValueEventListener(membersListener);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Firebase — typing
    // ─────────────────────────────────────────────────────────────────────

    private void listenTyping() {
        DatabaseReference ref = XFirebaseUtils.xDmGroupRef(groupId).child("typing");
        typingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                List<String> names = new ArrayList<>();
                for (DataSnapshot ds : snap.getChildren()) {
                    String uid = ds.getKey();
                    if (!myUid.equals(uid) && Boolean.TRUE.equals(ds.getValue(Boolean.class))) {
                        String name = memberNames.get(uid);
                        if (name != null) names.add(name.split(" ")[0]); // first name only
                    }
                }
                if (tvTypingIndicator == null) return;
                if (names.isEmpty()) {
                    tvTypingIndicator.setVisibility(View.GONE);
                } else if (names.size() == 1) {
                    tvTypingIndicator.setText(names.get(0) + " is typing…");
                    tvTypingIndicator.setVisibility(View.VISIBLE);
                } else {
                    tvTypingIndicator.setText(names.size() + " people are typing…");
                    tvTypingIndicator.setVisibility(View.VISIBLE);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        ref.addValueEventListener(typingListener);
    }

    private void handleTyping(boolean isTyping) {
        if (groupId == null) return;
        DatabaseReference typingRef = XFirebaseUtils.xDmGroupRef(groupId)
                .child("typing").child(myUid);
        if (isTyping && !amTyping) {
            amTyping = true;
            typingRef.setValue(true);
        }
        if (stopTypingRunnable != null) typingHandler.removeCallbacks(stopTypingRunnable);
        stopTypingRunnable = () -> {
            amTyping = false;
            typingRef.removeValue();
        };
        typingHandler.postDelayed(stopTypingRunnable, TYPING_DEBOUNCE_MS);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Firebase — seen
    // ─────────────────────────────────────────────────────────────────────

    private void markSeen() {
        if (groupId == null || myUid.isEmpty()) return;
        XFirebaseUtils.xDmGroupRef(groupId)
                .child("seen").child(myUid).setValue(System.currentTimeMillis());
        // Also clear unread badge in the group conversation list entry
        XFirebaseUtils.xDmGroupsRef().child(groupId)
                .child("unread").child(myUid).setValue(false);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sending messages
    // ─────────────────────────────────────────────────────────────────────

    private void sendTextMessage() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        etMessage.setText("");
        sendMessage(text, null, null);
    }

    private void sendMessage(String text, String mediaUrl, String mediaType) {
        if (groupId == null || myUid.isEmpty()) return;

        DatabaseReference ref = XFirebaseUtils.xDmGroupMessagesRef(groupId);
        String key = ref.push().getKey();
        if (key == null) return;

        Map<String, Object> msg = new HashMap<>();
        msg.put("senderUid",      myUid);
        msg.put("senderName",     myName != null ? myName : "");
        msg.put("senderThumbUrl", myThumbUrl != null ? myThumbUrl : "");
        msg.put("timestamp",      System.currentTimeMillis());
        if (text     != null) msg.put("text",      text);
        if (mediaUrl != null) msg.put("mediaUrl",  mediaUrl);
        if (mediaType!= null) msg.put("mediaType", mediaType);

        // Reply-to
        if (replyToMsg != null) {
            msg.put("replyToMsgId",     replyToMsg.id);
            msg.put("replyToText",      replyToMsg.text != null
                    ? replyToMsg.text : (replyToMsg.mediaUrl != null ? "📷 Media" : ""));
            msg.put("replyToSenderName",replyToMsg.senderName != null
                    ? replyToMsg.senderName : "");
        }
        clearReply();

        // Write message
        ref.child(key).setValue(msg);

        // Update conversation preview for all members
        String preview = text != null ? text : (mediaType != null ? "📷 Media" : "");
        updateGroupPreview(preview);
    }

    private void updateGroupPreview(String lastMsg) {
        if (groupId == null) return;
        Map<String, Object> update = new HashMap<>();
        update.put("lastMessage",    lastMsg);
        update.put("lastMessageTs",  System.currentTimeMillis());
        update.put("lastSenderUid",  myUid);
        update.put("lastSenderName", myName != null ? myName : "");

        XFirebaseUtils.xDmGroupsRef().child(groupId).updateChildren(update);

        // Set unread=true for all other members
        for (String uid : memberNames.keySet()) {
            if (!myUid.equals(uid)) {
                XFirebaseUtils.xDmGroupsRef().child(groupId)
                        .child("unread").child(uid).setValue(true);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Media
    // ─────────────────────────────────────────────────────────────────────

    private void showAttachMenu() {
        String[] options = {"Image", "Video"};
        new AlertDialog.Builder(this)
                .setTitle("Attach")
                .setItems(options, (d, which) -> {
                    if (which == 0) pickImage();
                    else            pickVideo();
                })
                .show();
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setType("image/*");
        imagePicker.launch(i);
    }

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_PICK);
        i.setType("video/*");
        videoPicker.launch(i);
    }

    private void compressAndSendImage(Uri uri) {
        new Thread(() -> {
            Uri compressed = ImageCompressor.compress(this, uri);
            Uri toUpload   = compressed != null ? compressed : uri;
            XCloudinaryUtils.uploadImageDirect(this, toUpload, new XCloudinaryUtils.UploadCallback() {
                @Override public void onSuccess(String url, String thumbUrl) {
                    runOnUiThread(() -> sendMessage(null, url, "image"));
                }
                @Override public void onFailure(String error) {
                    runOnUiThread(() -> Toast.makeText(XGroupDMConversationActivity.this,
                            "Image upload failed", Toast.LENGTH_SHORT).show());
                }
            });
        }).start();
    }

    private void uploadAndSendVideo(Uri uri) {
        new Thread(() -> {
            XCloudinaryUtils.uploadVideoDirect(this, uri, new XCloudinaryUtils.UploadCallback() {
                @Override public void onSuccess(String url, String thumbUrl) {
                    runOnUiThread(() -> sendMessage(null, url, "video"));
                }
                @Override public void onFailure(String error) {
                    runOnUiThread(() -> Toast.makeText(XGroupDMConversationActivity.this,
                            "Video upload failed", Toast.LENGTH_SHORT).show());
                }
            });
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Reactions
    // ─────────────────────────────────────────────────────────────────────

    private void showReactionPicker(XGroupDMMessage msg, View anchor) {
        new AlertDialog.Builder(this)
                .setTitle("React")
                .setItems(REACTION_EMOJIS, (d, which) -> toggleReaction(msg, REACTION_EMOJIS[which]))
                .show();
    }

    private void toggleReaction(XGroupDMMessage msg, String emoji) {
        if (msg.id == null || groupId == null) return;
        DatabaseReference ref = XFirebaseUtils.xDmGroupMessagesRef(groupId)
                .child(msg.id).child("reactions").child(emoji).child(myUid);
        boolean alreadyReacted = msg.hasReacted(myUid, emoji);
        if (alreadyReacted) {
            ref.removeValue();
        } else {
            ref.setValue(true);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Reply
    // ─────────────────────────────────────────────────────────────────────

    public void setReplyTo(XGroupDMMessage msg) {
        replyToMsg = msg;
        if (vReplyPreview  != null) vReplyPreview.setVisibility(View.VISIBLE);
        if (tvReplyPreviewText != null) {
            String preview = (msg.senderName != null ? msg.senderName + ": " : "")
                    + (msg.text != null ? msg.text : "📷 Media");
            tvReplyPreviewText.setText(preview);
        }
    }

    private void clearReply() {
        replyToMsg = null;
        if (vReplyPreview != null) vReplyPreview.setVisibility(View.GONE);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Group info
    // ─────────────────────────────────────────────────────────────────────

    private void showGroupInfo() {
        // Build a simple info dialog showing group name + member list
        StringBuilder sb = new StringBuilder();
        sb.append(groupName != null ? groupName : "Group").append("\n");
        sb.append(memberCount).append(" members\n\n");
        for (Map.Entry<String, String> e : memberNames.entrySet()) {
            sb.append("• ").append(e.getValue()).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("Group Info")
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .setNeutralButton("Leave Group", (d, w) -> leaveGroup())
                .show();
    }

    private void leaveGroup() {
        if (groupId == null || myUid.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("Leave Group")
                .setMessage("Are you sure you want to leave this group?")
                .setPositiveButton("Leave", (d, w) -> {
                    // Remove from members
                    XFirebaseUtils.xDmGroupRef(groupId)
                            .child("members").child(myUid).removeValue();
                    // Remove from my group list
                    XFirebaseUtils.xDmGroupsRef().child(groupId)
                            .child("members").child(myUid).removeValue();
                    // Send system message
                    sendSystemMessage((myName != null ? myName : "A member") + " left the group");
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendSystemMessage(String text) {
        if (groupId == null) return;
        DatabaseReference ref = XFirebaseUtils.xDmGroupMessagesRef(groupId);
        String key = ref.push().getKey();
        if (key == null) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("isSystemMessage", true);
        msg.put("systemText",      text);
        msg.put("timestamp",       System.currentTimeMillis());
        ref.child(key).setValue(msg);
    }

    // ─────────────────────────────────────────────────────────────────────
    // My profile
    // ─────────────────────────────────────────────────────────────────────

    private void loadMyProfile() {
        if (myUid.isEmpty()) return;
        XFirebaseUtils.xUserRef(myUid).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) {
                        myName     = s.child("name").getValue(String.class);
                        myThumbUrl = s.child("thumbUrl").getValue(String.class);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Lifecycle — cleanup
    // ─────────────────────────────────────────────────────────────────────

    @Override protected void onPause() {
        super.onPause();
        if (amTyping && groupId != null) {
            amTyping = false;
            XFirebaseUtils.xDmGroupRef(groupId).child("typing").child(myUid).removeValue();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        typingHandler.removeCallbacksAndMessages(null);
        if (groupId != null) {
            if (msgListener     != null)
                XFirebaseUtils.xDmGroupMessagesRef(groupId).removeEventListener(msgListener);
            if (typingListener  != null)
                XFirebaseUtils.xDmGroupRef(groupId).child("typing").removeEventListener(typingListener);
            if (membersListener != null)
                XFirebaseUtils.xDmGroupRef(groupId).child("members").removeEventListener(membersListener);
            XFirebaseUtils.xDmGroupRef(groupId).child("typing").child(myUid).removeValue();
        }
    }
}
