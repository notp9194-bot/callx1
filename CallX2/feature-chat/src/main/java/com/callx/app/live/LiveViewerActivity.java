package com.callx.app.live;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LiveViewerActivity — Viewer ka screen
 * Viewer yahan host ka live dekhta hai aur messages bhejta hai.
 */
public class LiveViewerActivity extends AppCompatActivity {

    private String liveId;
    private String hostName;
    private String myUid;
    private String myName;

    private LiveChatAdapter chatAdapter;
    private final List<LiveChatAdapter.LiveMessage> messages = new ArrayList<>();
    private RecyclerView rvChat;
    private TextView tvHostName;
    private TextView tvLiveStatus;
    private EditText etMessage;

    private ValueEventListener messagesListener;
    private ValueEventListener statusListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_live_viewer);

        liveId   = getIntent().getStringExtra("liveId");
        hostName = getIntent().getStringExtra("hostName");
        if (hostName == null || hostName.isEmpty()) hostName = "Host";

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            myUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
            myName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
            if (myName == null || myName.isEmpty()) myName = "Viewer";
        }

        setupUI();
        joinLive();
        startListeners();
    }

    private void setupUI() {
        rvChat      = findViewById(R.id.rv_live_viewer_chat);
        tvHostName  = findViewById(R.id.tv_live_host_name);
        tvLiveStatus = findViewById(R.id.tv_live_badge);
        etMessage   = findViewById(R.id.et_live_viewer_message);
        ImageButton btnSend  = findViewById(R.id.btn_live_viewer_send);
        ImageButton btnLeave = findViewById(R.id.btn_leave_live);

        if (tvHostName != null) tvHostName.setText(hostName + " LIVE hai 🔴");

        chatAdapter = new LiveChatAdapter(messages);
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(chatAdapter);

        btnSend.setOnClickListener(v -> sendMessage());
        btnLeave.setOnClickListener(v -> leaveLive());

        etMessage.setOnEditorActionListener((textView, i, event) -> {
            sendMessage(); return true;
        });
    }

    private void joinLive() {
        if (liveId == null || myUid == null) return;
        Map<String, Object> viewerData = new HashMap<>();
        viewerData.put("name",     myName);
        viewerData.put("joinedAt", ServerValue.TIMESTAMP);
        LiveManager.getLiveViewersRef(liveId).child(myUid).setValue(viewerData);
    }

    private void startListeners() {
        if (liveId == null) return;

        // Messages listener
        messagesListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                messages.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    String sender = child.child("senderName").getValue(String.class);
                    String text   = child.child("text").getValue(String.class);
                    Long ts       = child.child("timestamp").getValue(Long.class);
                    if (text != null && !text.isEmpty()) {
                        messages.add(new LiveChatAdapter.LiveMessage(
                            sender != null ? sender : "Viewer",
                            text,
                            ts != null ? ts : 0));
                    }
                }
                chatAdapter.notifyDataSetChanged();
                if (!messages.isEmpty())
                    rvChat.smoothScrollToPosition(messages.size() - 1);
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        LiveManager.getLiveMessagesRef(liveId).addValueEventListener(messagesListener);

        // Live status listener — agar host end kare toh viewer ko bhi pata chale
        statusListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String status = snap.getValue(String.class);
                if ("ended".equals(status)) {
                    Toast.makeText(LiveViewerActivity.this,
                        "Live khatam ho gaya", Toast.LENGTH_LONG).show();
                    finish();
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        LiveManager.getLiveRef(liveId).child("status").addValueEventListener(statusListener);
    }

    private void sendMessage() {
        if (etMessage == null) return;
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        if (myUid == null) return;

        Map<String, Object> msg = new HashMap<>();
        msg.put("senderUid",  myUid);
        msg.put("senderName", myName);
        msg.put("text",       text);
        msg.put("timestamp",  ServerValue.TIMESTAMP);

        LiveManager.getLiveMessagesRef(liveId).push().setValue(msg);
        etMessage.setText("");
    }

    private void leaveLive() {
        if (myUid != null && liveId != null)
            LiveManager.getLiveViewersRef(liveId).child(myUid).removeValue();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (messagesListener != null && liveId != null)
            LiveManager.getLiveMessagesRef(liveId).removeEventListener(messagesListener);
        if (statusListener != null && liveId != null)
            LiveManager.getLiveRef(liveId).child("status").removeEventListener(statusListener);
        if (myUid != null && liveId != null)
            LiveManager.getLiveViewersRef(liveId).child(myUid).removeValue();
    }
}
