package com.callx.app.activities;

  import android.os.Bundle;
  import android.view.View;
  import android.widget.EditText;
  import android.widget.ImageView;
  import android.widget.TextView;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.bumptech.glide.Glide;
  import com.callx.app.models.XMessage;
  import com.callx.app.utils.XFirebaseUtils;
  import com.callx.app.x.R;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.*;
  import java.util.ArrayList;
  import java.util.List;

  public class XDMConversationActivity extends AppCompatActivity {

      private String conversationId, otherUid, otherName, otherHandle, otherPhoto;
      private String myUid;
      private RecyclerView recyclerView;
      private com.callx.app.adapters.XDMAdapter adapter;
      private EditText etMessage;
      private ValueEventListener msgListener;

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_x_dm_conversation);

          conversationId = getIntent().getStringExtra("conversation_id");
          otherUid       = getIntent().getStringExtra("other_uid");
          otherName      = getIntent().getStringExtra("other_name");
          otherHandle    = getIntent().getStringExtra("other_handle");
          otherPhoto     = getIntent().getStringExtra("other_photo");
          myUid          = FirebaseAuth.getInstance().getCurrentUser() != null
              ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "";

          if (conversationId == null)
              conversationId = XFirebaseUtils.dmConversationId(myUid, otherUid);

          // Header
          findViewById(R.id.btn_xdm_back).setOnClickListener(v -> finish());
          ImageView ivAvatar = findViewById(R.id.iv_xdm_header_avatar);
          TextView tvName    = findViewById(R.id.tv_xdm_header_name);
          TextView tvHandle  = findViewById(R.id.tv_xdm_header_handle);
          Glide.with(this).load(otherPhoto).circleCrop().into(ivAvatar);
          tvName.setText(otherName);
          tvHandle.setText("@" + otherHandle);

          recyclerView = findViewById(R.id.rv_xdm_messages);
          adapter = new com.callx.app.adapters.XDMAdapter(this, myUid);
          LinearLayoutManager lm = new LinearLayoutManager(this);
          lm.setStackFromEnd(true);
          recyclerView.setLayoutManager(lm);
          recyclerView.setAdapter(adapter);

          etMessage = findViewById(R.id.et_xdm_message);
          findViewById(R.id.btn_xdm_send).setOnClickListener(v -> sendMessage());
          loadMessages();
      }

      private void loadMessages() {
          final String convId = conversationId;
          msgListener = new ValueEventListener() {
              @Override public void onDataChange(DataSnapshot snap) {
                  List<XMessage> list = new ArrayList<>();
                  for (DataSnapshot ds : snap.getChildren()) {
                      XMessage m = ds.getValue(XMessage.class);
                      if (m != null) { m.id = ds.getKey(); list.add(m); }
                  }
                  adapter.setMessages(list);
                  if (!list.isEmpty()) recyclerView.scrollToPosition(list.size() - 1);
                  // Mark conversation as seen
                  XFirebaseUtils.xDmConversationsRef(myUid).child(convId).child("seen").setValue(true);
              }
              @Override public void onCancelled(DatabaseError e) {}
          };
          XFirebaseUtils.xDmMessagesRef(conversationId).orderByChild("timestamp")
              .limitToLast(50).addValueEventListener(msgListener);
      }

      private void sendMessage() {
          String text = etMessage.getText().toString().trim();
          if (text.isEmpty() || myUid.isEmpty()) return;

          XMessage msg  = new XMessage();
          msg.senderId  = myUid;
          msg.receiverId= otherUid;
          msg.text      = text;
          msg.timestamp = System.currentTimeMillis();
          msg.seen      = false;

          String key = XFirebaseUtils.xDmMessagesRef(conversationId).push().getKey();
          if (key == null) return;
          msg.id = key;

          XFirebaseUtils.xDmMessagesRef(conversationId).child(key).setValue(msg);

          // Update conversation preview for both sides
          java.util.Map<String, Object> preview = new java.util.HashMap<>();
          preview.put("lastMessage", text);
          preview.put("lastMessageTs", msg.timestamp);
          preview.put("otherUid", otherUid);
          preview.put("otherName", otherName);
          preview.put("otherHandle", otherHandle);
          preview.put("otherPhoto", otherPhoto);
          preview.put("seen", false);
          XFirebaseUtils.xDmConversationsRef(otherUid).child(conversationId).updateChildren(preview);
          XFirebaseUtils.xDmConversationsRef(myUid).child(conversationId).child("seen").setValue(true);

          etMessage.setText("");
      }

      @Override protected void onDestroy() {
          super.onDestroy();
          if (msgListener != null)
              XFirebaseUtils.xDmMessagesRef(conversationId).removeEventListener(msgListener);
      }
  }