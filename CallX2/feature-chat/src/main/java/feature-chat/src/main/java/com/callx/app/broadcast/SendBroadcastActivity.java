package com.callx.app.broadcast;

  import android.os.Bundle;
  import android.widget.EditText;
  import android.widget.Toast;
  import androidx.appcompat.app.AppCompatActivity;
  import com.callx.app.chat.R;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.FirebaseDatabase;
  import com.google.firebase.database.ValueEventListener;
  import java.util.ArrayList;
  import java.util.List;
  import java.util.UUID;
  import java.util.HashMap;
  import java.util.Map;

  /**
   * SendBroadcastActivity — Compose and send a broadcast message.
   * Message is sent as individual 1:1 chat to each recipient.
   */
  public class SendBroadcastActivity extends AppCompatActivity {

      private String broadcastId, broadcastName, myUid;
      private EditText etMessage;
      private List<String> recipientUids = new ArrayList<>();

      @Override protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_send_broadcast);
          broadcastId   = getIntent().getStringExtra("broadcastId");
          broadcastName = getIntent().getStringExtra("broadcastName");
          myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
          etMessage = findViewById(R.id.et_message);
          loadRecipients();

          findViewById(R.id.btn_send).setOnClickListener(v -> sendBroadcast());
      }

      private void loadRecipients() {
          FirebaseDatabase.getInstance()
              .getReference("broadcasts").child(myUid).child(broadcastId).child("recipients")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(DataSnapshot s) {
                      recipientUids.clear();
                      for (DataSnapshot c : s.getChildren()) recipientUids.add(c.getKey());
                  }
                  @Override public void onCancelled(DatabaseError e) {}
              });
      }

      private void sendBroadcast() {
          String text = etMessage.getText().toString().trim();
          if (text.isEmpty()) { Toast.makeText(this, "Message khaali hai", Toast.LENGTH_SHORT).show(); return; }
          if (recipientUids.isEmpty()) { Toast.makeText(this, "Koi recipient nahi", Toast.LENGTH_SHORT).show(); return; }

          for (String recipientUid : recipientUids) {
              String chatId = myUid.compareTo(recipientUid) < 0
                      ? myUid + "_" + recipientUid
                      : recipientUid + "_" + myUid;
              String msgId = UUID.randomUUID().toString().replace("-","");
              Map<String,Object> msg = new HashMap<>();
              msg.put("id", msgId); msg.put("senderId", myUid);
              msg.put("text", text); msg.put("type", "text");
              msg.put("timestamp", System.currentTimeMillis()); msg.put("status","sent");
              FirebaseDatabase.getInstance()
                  .getReference("chats").child(chatId).child("messages").child(msgId).setValue(msg);
          }
          Toast.makeText(this, "Broadcast bheja gaya " + recipientUids.size() + " logon ko", Toast.LENGTH_SHORT).show();
          finish();
      }
  }