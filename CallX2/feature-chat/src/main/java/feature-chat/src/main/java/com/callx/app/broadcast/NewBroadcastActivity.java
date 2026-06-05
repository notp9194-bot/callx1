package com.callx.app.broadcast;

  import android.os.Bundle;
  import android.widget.Button;
  import android.widget.EditText;
  import android.widget.Toast;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;
  import com.callx.app.chat.R;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.database.DataSnapshot;
  import com.google.firebase.database.DatabaseError;
  import com.google.firebase.database.FirebaseDatabase;
  import com.google.firebase.database.ValueEventListener;
  import java.util.ArrayList;
  import java.util.HashMap;
  import java.util.List;
  import java.util.Map;
  import java.util.UUID;

  /**
   * NewBroadcastActivity — Create a new broadcast list.
   * Select contacts → name the list → save.
   */
  public class NewBroadcastActivity extends AppCompatActivity {

      private EditText etListName;
      private BroadcastMemberSelectAdapter memberAdapter;
      private List<com.callx.app.models.User> contacts = new ArrayList<>();
      private String myUid;

      @Override protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          setContentView(R.layout.activity_new_broadcast);
          myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
          etListName = findViewById(R.id.et_list_name);

          RecyclerView rv = findViewById(R.id.rv_contacts);
          rv.setLayoutManager(new LinearLayoutManager(this));
          memberAdapter = new BroadcastMemberSelectAdapter(contacts);
          rv.setAdapter(memberAdapter);

          loadContacts();

          Button btnCreate = findViewById(R.id.btn_create);
          btnCreate.setOnClickListener(v -> createBroadcast());
      }

      private void loadContacts() {
          FirebaseDatabase.getInstance()
              .getReference("users").child(myUid).child("contacts")
              .addListenerForSingleValueEvent(new ValueEventListener() {
                  @Override public void onDataChange(DataSnapshot s) {
                      contacts.clear();
                      for (DataSnapshot c : s.getChildren()) {
                          com.callx.app.models.User u = c.getValue(com.callx.app.models.User.class);
                          if (u != null) contacts.add(u);
                      }
                      memberAdapter.notifyDataSetChanged();
                  }
                  @Override public void onCancelled(DatabaseError e) {}
              });
      }

      private void createBroadcast() {
          String name = etListName.getText().toString().trim();
          if (name.isEmpty()) { Toast.makeText(this,"List ka naam dalo",Toast.LENGTH_SHORT).show(); return; }
          List<com.callx.app.models.User> selected = memberAdapter.getSelected();
          if (selected.isEmpty()) { Toast.makeText(this,"Koi contact select nahi hua",Toast.LENGTH_SHORT).show(); return; }

          String broadcastId = UUID.randomUUID().toString().replace("-","");
          Map<String,Object> data = new HashMap<>();
          data.put("name", name);
          data.put("createdAt", System.currentTimeMillis());
          Map<String,Boolean> recipients = new HashMap<>();
          for (com.callx.app.models.User u : selected) recipients.put(u.uid, true);
          data.put("recipients", recipients);

          FirebaseDatabase.getInstance()
              .getReference("broadcasts").child(myUid).child(broadcastId)
              .setValue(data)
              .addOnSuccessListener(v -> {
                  Toast.makeText(this,"Broadcast list banayi gayi!",Toast.LENGTH_SHORT).show();
                  finish();
              });
      }
  }