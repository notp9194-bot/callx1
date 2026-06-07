package com.callx.app.group;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.bumptech.glide.Glide;
import com.callx.app.group.MemberSelectAdapter;
import com.callx.app.chat.databinding.ActivityNewGroupBinding;
import com.callx.app.models.User;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.util.*;

public class NewGroupActivity extends AppCompatActivity {
    private ActivityNewGroupBinding binding;
    private final List<User> contacts = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private MemberSelectAdapter adapter;
    private String currentUid;
    private String groupIconUrl = null;
    private ActivityResultLauncher<String> imagePicker;

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

        imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    Glide.with(this).load(uri).circleCrop().into(binding.ivGroupIcon);
                    binding.ivGroupIcon.setPadding(0, 0, 0, 0);
                    binding.btnCreate.setEnabled(false);
                    binding.btnCreate.setText("Photo upload ho rahi hai...");
                    CloudinaryUploader.upload(this, uri, "group_avatars", "image",
                        new CloudinaryUploader.UploadCallback() {
                            @Override public void onSuccess(CloudinaryUploader.Result result) {
                                runOnUiThread(() -> {
                                    groupIconUrl = result.secureUrl;
                                    binding.btnCreate.setEnabled(true);
                                    binding.btnCreate.setText("Group banao");
                                    Toast.makeText(NewGroupActivity.this,
                                        "Photo upload ho gayi!", Toast.LENGTH_SHORT).show();
                                });
                            }
                            @Override public void onError(String message) {
                                runOnUiThread(() -> {
                                    groupIconUrl = null;
                                    binding.btnCreate.setEnabled(true);
                                    binding.btnCreate.setText("Group banao");
                                    Toast.makeText(NewGroupActivity.this,
                                        "Photo upload fail: " + message, Toast.LENGTH_SHORT).show();
                                });
                            }
                        });
                }
            });

        binding.flAvatarPicker.setOnClickListener(v -> imagePicker.launch("image/*"));
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
        String groupName = binding.etGroupName.getText().toString().trim();
        if (groupName.isEmpty()) {
            Toast.makeText(this, "Group name daalo", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Kam se kam ek member select karo", Toast.LENGTH_SHORT).show();
            return;
        }

        // Disable button to prevent double-tap
        binding.btnCreate.setEnabled(false);
        binding.btnCreate.setText("Bana raha hai...");

        DatabaseReference ref = FirebaseUtils.getGroupsRef().push();
        String groupId = ref.getKey();

        Map<String, Object> g = new HashMap<>();
        g.put("id",             groupId);
        g.put("name",           groupName);
        g.put("createdBy",      currentUid);
        g.put("adminUid",       currentUid);
        g.put("createdAt",      System.currentTimeMillis());
        g.put("lastMessage",    "Group bana");
        g.put("lastSenderName", "");
        g.put("lastMessageAt",  System.currentTimeMillis());
        if (groupIconUrl != null && !groupIconUrl.isEmpty()) {
            g.put("iconUrl", groupIconUrl);
        }

        // admins map (backwards compat)
        Map<String, Boolean> admins = new HashMap<>();
        admins.put(currentUid, true);
        g.put("admins", admins);

        // unread map
        Set<String> allUids = new HashSet<>();
        allUids.add(currentUid);
        allUids.addAll(selected);
        Map<String, Object> unread = new HashMap<>();
        for (String uid : allUids) unread.put(uid, 0L);
        g.put("unread", unread);

        // Fetch member names from Firebase, then save group
        // members/{uid} = {name, role}  ← GroupChatActivity reads 'role' from here
        final int[] remaining = {allUids.size()};
        final Map<String, String> nameMap = new HashMap<>();

        for (String uid : allUids) {
            FirebaseUtils.db().getReference("users").child(uid).child("name")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String n = snap.getValue(String.class);
                        nameMap.put(uid, n != null ? n : "Member");
                        remaining[0]--;
                        if (remaining[0] == 0)
                            saveGroupWithMembers(ref, groupId, groupName, g, allUids, nameMap);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        nameMap.put(uid, "Member");
                        remaining[0]--;
                        if (remaining[0] == 0)
                            saveGroupWithMembers(ref, groupId, groupName, g, allUids, nameMap);
                    }
                });
        }
    }

    /**
     * Called after all member names are fetched.
     * Writes members/{uid} = {name, role} so GroupChatActivity
     * can correctly read the creator's role as "admin".
     */
    private void saveGroupWithMembers(
            DatabaseReference ref,
            String groupId,
            String groupName,
            Map<String, Object> g,
            Set<String> allUids,
            Map<String, String> nameMap) {

        Map<String, Object> membersMap = new HashMap<>();
        for (String uid : allUids) {
            Map<String, Object> memberData = new HashMap<>();
            memberData.put("name", nameMap.getOrDefault(uid, "Member"));
            // Creator gets "admin", everyone else gets "member"
            memberData.put("role", uid.equals(currentUid) ? "admin" : "member");
            membersMap.put(uid, memberData);
        }
        g.put("members", membersMap);

        ref.setValue(g)
            .addOnSuccessListener(x -> {
                for (String uid : allUids) {
                    FirebaseUtils.getUserGroupsRef(uid).child(groupId).setValue(true);
                    // Write join log entry
                    java.util.Map<String, Object> log = new java.util.HashMap<>();
                    log.put("name", nameMap.getOrDefault(uid, "Member"));
                    log.put("uid", uid);
                    log.put("action", uid.equals(currentUid) ? "created" : "joined");
                    log.put("at", System.currentTimeMillis());
                    FirebaseUtils.getGroupsRef().child(groupId).child("joinLog").push().setValue(log);
                }
                Intent i = new Intent(this, GroupChatActivity.class);
                i.putExtra("groupId",    groupId);
                i.putExtra("groupName",  groupName);
                startActivity(i);
                finish();
            })
            .addOnFailureListener(e -> runOnUiThread(() -> {
                binding.btnCreate.setEnabled(true);
                binding.btnCreate.setText("Group banao");
                Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }));
    }
}
