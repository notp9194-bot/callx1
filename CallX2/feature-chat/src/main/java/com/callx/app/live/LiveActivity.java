package com.callx.app.live;

import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.chat.R;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LiveActivity — Host ka screen
 * Host yahan se live shuru karta hai. Selected contacts ko notification jaati hai.
 * Viewers real-time messages bhejte hain jo yahan show hote hain.
 */
public class LiveActivity extends AppCompatActivity {

    private String liveId;
    private String myUid;
    private String myName;
    private List<String> invitedUids;

    private LiveChatAdapter chatAdapter;
    private final List<LiveChatAdapter.LiveMessage> messages = new ArrayList<>();
    private RecyclerView rvChat;
    private TextView tvViewerCount;
    private EditText etMessage;

    private DatabaseReference liveRef;
    private ValueEventListener messagesListener;
    private ValueEventListener viewersListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_live);

        liveId      = getIntent().getStringExtra("liveId");
        myUid       = getIntent().getStringExtra("myUid");
        invitedUids = getIntent().getStringArrayListExtra("invitedUids");
        if (invitedUids == null) invitedUids = new ArrayList<>();

        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            myName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
            if (myName == null || myName.isEmpty()) myName = "Host";
        } else {
            myName = "Host";
        }

        setupUI();
        createLiveSession();
        notifyInvitedContacts();
    }

    private void setupUI() {
        rvChat        = findViewById(R.id.rv_live_chat);
        tvViewerCount = findViewById(R.id.tv_live_viewer_count);
        etMessage     = findViewById(R.id.et_live_message);
        ImageButton btnSend   = findViewById(R.id.btn_live_send);
        ImageButton btnEndLive = findViewById(R.id.btn_end_live);
        View btnSwitchCamera  = findViewById(R.id.btn_switch_camera);

        chatAdapter = new LiveChatAdapter(messages);
        rvChat.setLayoutManager(new LinearLayoutManager(this));
        rvChat.setAdapter(chatAdapter);

        btnSend.setOnClickListener(v -> sendMessage());

        btnEndLive.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Live End Karo?")
                .setMessage("Kya aap live band karna chahte hain?")
                .setPositiveButton("Haan, End Karo", (d, w) -> endLive())
                .setNegativeButton("Cancel", null)
                .show()
        );

        if (btnSwitchCamera != null) {
            btnSwitchCamera.setOnClickListener(v ->
                Toast.makeText(this, "Camera switch", Toast.LENGTH_SHORT).show());
        }

        etMessage.setOnEditorActionListener((textView, i, event) -> {
            sendMessage(); return true;
        });
    }

    private void createLiveSession() {
        if (liveId == null || myUid == null) return;
        liveRef = LiveManager.getLiveRef(liveId);

        Map<String, Object> liveData = new HashMap<>();
        liveData.put("hostUid",   myUid);
        liveData.put("hostName",  myName);
        liveData.put("status",    "active");
        liveData.put("startedAt", ServerValue.TIMESTAMP);
        liveData.put("viewerCount", 0);

        Map<String, Object> invitedMap = new HashMap<>();
        for (String uid : invitedUids) invitedMap.put(uid, true);
        liveData.put("invitedUids", invitedMap);

        liveRef.setValue(liveData);

        // Track host's active live
        LiveManager.getUserActiveLiveRef(myUid).setValue(liveId);

        startListeners();
    }

    private void startListeners() {
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

        // Viewers count listener
        viewersListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                long count = snap.getChildrenCount();
                if (tvViewerCount != null)
                    tvViewerCount.setText(count + " viewers");
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        };
        LiveManager.getLiveViewersRef(liveId).addValueEventListener(viewersListener);
    }

    private void sendMessage() {
        if (etMessage == null) return;
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) return;

        Map<String, Object> msg = new HashMap<>();
        msg.put("senderUid",  myUid);
        msg.put("senderName", myName + " (Host)");
        msg.put("text",       text);
        msg.put("timestamp",  ServerValue.TIMESTAMP);

        LiveManager.getLiveMessagesRef(liveId).push().setValue(msg);
        etMessage.setText("");
    }

    private void notifyInvitedContacts() {
        if (invitedUids.isEmpty() || myUid == null) return;

        FirebaseUtils.getUserRef(myUid).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                String photo = snap.child("photoUrl").getValue(String.class);
                String name  = snap.child("name").getValue(String.class);
                if (name == null || name.isEmpty()) name = myName;
                final String hostName  = name;
                final String hostPhoto = photo != null ? photo : "";

                for (String uid : invitedUids) {
                    PushNotify.notifyUser(uid, myUid, hostName, "live_invite", liveId);
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {}
        });
    }

    private void endLive() {
        if (liveRef != null) liveRef.child("status").setValue("ended");
        if (myUid != null) LiveManager.getUserActiveLiveRef(myUid).removeValue();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (liveRef != null && messagesListener != null)
            LiveManager.getLiveMessagesRef(liveId).removeEventListener(messagesListener);
        if (liveRef != null && viewersListener != null)
            LiveManager.getLiveViewersRef(liveId).removeEventListener(viewersListener);
    }

    @Override public void onBackPressed() {
        new AlertDialog.Builder(this)
            .setTitle("Live End Karo?")
            .setMessage("Wapas jaane par live band ho jayega.")
            .setPositiveButton("Haan", (d, w) -> endLive())
            .setNegativeButton("Cancel", null)
            .show();
    }
}
