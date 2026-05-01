package com.callx.app.activities;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.callx.app.adapters.MemberSelectAdapter;
import com.callx.app.databinding.ActivityNewGroupBinding;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;
public class NewGroupActivity extends AppCompatActivity {
    private ActivityNewGroupBinding binding;
    private final List<User> contacts = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private MemberSelectAdapter adapter;
    private String currentUid;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityNewGroupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        currentUid = FirebaseUtils.getCurrentUid();
        binding.rvMembers.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MemberSelectAdapter(contacts, selected);
        binding.rvMembers.setAdapter(adapter);
        loadContacts();
        binding.btnCreate.setOnClickListener(v -> create());
    }
    private void loadContacts() {
        FirebaseUtils.getContactsRef(currentUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    contacts.clear();
                    for (DataSnapshot c : snap.getChildren()) {
                        User u = c.getValue(User.class);
                        if (u != null) {
                            if (u.uid == null) u.uid = c.getKey();
                            contacts.add(u);
                        }
                    }
                    adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private void create() {
        String name = binding.etGroupName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Group name daalo", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Kam se kam ek member select karo",
                Toast.LENGTH_SHORT).show();
            return;
        }
        DatabaseReference ref = FirebaseUtils.getGroupsRef().push();
        String groupId = ref.getKey();
        Map<String, Object> g = new HashMap<>();
        g.put("id",             groupId);
        g.put("name",           name);
        g.put("createdBy",      currentUid);
        g.put("adminUid",       currentUid);   // creator = primary admin
        g.put("createdAt",      System.currentTimeMillis());
        g.put("lastMessage",    "Group bana");
        g.put("lastSenderName", "");
        g.put("lastMessageAt",  System.currentTimeMillis());
        Map<String, Boolean> members = new HashMap<>();
        members.put(currentUid, true);
        for (String uid : selected) members.put(uid, true);
        g.put("members", members);
        Map<String, Boolean> admins = new HashMap<>();
        admins.put(currentUid, true);
        g.put("admins", admins);
        // unread counts initialized at 0 for all members
        Map<String, Object> unread = new HashMap<>();
        for (String uid : members.keySet()) unread.put(uid, 0L);
        g.put("unread", unread);
        ref.setValue(g).addOnSuccessListener(x -> {
            // Index for each member
            for (String uid : members.keySet()) {
                FirebaseUtils.getUserGroupsRef(uid).child(groupId).setValue(true);
            }
            Intent i = new Intent(this, GroupChatActivity.class);
            i.putExtra("groupId", groupId);
            i.putExtra("groupName", name);
            startActivity(i);
            finish();
        });
    }
}
