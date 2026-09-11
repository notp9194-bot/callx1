package com.callx.app.group;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
    private String groupIconUrl = null; // uploaded avatar URL
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

        // Image picker for group avatar
        imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    // Show preview immediately
                    Glide.with(this).load(uri).circleCrop().override(96, 96).into(binding.ivGroupIcon);
                    binding.ivGroupIcon.setPadding(0, 0, 0, 0);
                    // Upload to Cloudinary
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

        // Avatar picker click
        binding.flAvatarPicker.setOnClickListener(v ->
            imagePicker.launch("image/*"));
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
        g.put("adminUid",       currentUid);
        g.put("createdAt",      System.currentTimeMillis());
        g.put("lastMessage",    "Group bana");
        g.put("lastSenderName", "");
        g.put("lastMessageAt",  System.currentTimeMillis());
        if (groupIconUrl != null && !groupIconUrl.isEmpty()) {
            g.put("iconUrl", groupIconUrl);
        }
        // WHATSAPP-LEVEL FIX: this used to write members as a bare
        // Map<String, Boolean> (uid -> true). GroupInfoActivity /
        // GroupChatActivity read member.child("name")/child("role") off
        // this same "members" node — on a plain boolean node those reads
        // return null, so every initial member showed the generic
        // "Member" fallback forever (only members added later via
        // AddGroupMembersBottomSheet got a real name, since that flow
        // already wrote a full object). Mirror AddGroupMembersBottomSheet's
        // memberData shape ({name, role, addedAt}) here so creation-time
        // members are indistinguishable from later-added ones.
        long now = System.currentTimeMillis();
        Map<String, String> nameByUid = new HashMap<>();
        for (User u : contacts) {
            if (u.uid != null && u.name != null) nameByUid.put(u.uid, u.name);
        }
        String myName = FirebaseUtils.getCurrentName();

        Map<String, Object> members = new HashMap<>();
        Map<String, Object> creatorData = new HashMap<>();
        creatorData.put("name", myName != null ? myName : "");
        creatorData.put("role", "creator");
        creatorData.put("addedAt", now);
        members.put(currentUid, creatorData);
        for (String uid : selected) {
            Map<String, Object> memberData = new HashMap<>();
            memberData.put("name", nameByUid.containsKey(uid) ? nameByUid.get(uid) : "");
            memberData.put("role", "member");
            memberData.put("addedAt", now);
            members.put(uid, memberData);
        }
        g.put("members", members);
        Map<String, Boolean> admins = new HashMap<>();
        admins.put(currentUid, true);
        g.put("admins", admins);
        Map<String, Object> unread = new HashMap<>();
        for (String uid : members.keySet()) unread.put(uid, 0L);
        g.put("unread", unread);
        ref.setValue(g).addOnSuccessListener(x -> {
            // WhatsApp-level fix: track each member's userGroups write so a
            // rules/permission failure for any invited member surfaces
            // instead of silently leaving their Groups tab out of sync.
            for (String uid : members.keySet()) {
                FirebaseUtils.getUserGroupsRef(uid).child(groupId).setValue(true)
                    .addOnFailureListener(e -> runOnUiThread(() ->
                        Toast.makeText(NewGroupActivity.this,
                            "Group sync failed for a member: " + e.getMessage(),
                            Toast.LENGTH_LONG).show()));
            }

            // WHATSAPP-LEVEL FIX: GroupInfoActivity already posts a
            // "X added Y" system message when members are added AFTER
            // creation (see showAddMemberDialog there) — but a brand new
            // group's own initial members never got any such entry, so the
            // chat opened completely blank with no history of who was in
            // it from the start. Post the same two system-message lines
            // WhatsApp shows right when a group is created.
            postGroupCreatedSystemMessages(groupId, name);

            Intent i = new Intent(this, GroupChatActivity.class);
            i.putExtra("groupId", groupId);
            i.putExtra("groupName", name);
            startActivity(i);
            finish();
        }).addOnFailureListener(e -> runOnUiThread(() ->
            Toast.makeText(NewGroupActivity.this,
                "Group banane me error: " + e.getMessage(), Toast.LENGTH_LONG).show()));
    }

    /** "{You} created this group" + "{You} added {names}" — same combined-
     *  name formatting GroupInfoActivity uses for later add-member events,
     *  duplicated here since these two activities don't share a base class. */
    private void postGroupCreatedSystemMessages(String groupId, String groupName) {
        String myName = FirebaseUtils.getCurrentName();
        List<String> addedNames = new ArrayList<>();
        for (User u : contacts) {
            if (selected.contains(u.uid)) {
                addedNames.add(u.name != null && !u.name.isEmpty() ? u.name : "Member");
            }
        }

        DatabaseReference createdRef = FirebaseUtils.getGroupMessagesRef(groupId).push();
        Map<String, Object> created = new HashMap<>();
        created.put("id",        createdRef.getKey());
        created.put("senderId",  "system");
        created.put("senderName","System");
        created.put("text",      myName + " created this group");
        created.put("type",      "system");
        created.put("timestamp", System.currentTimeMillis());
        createdRef.setValue(created);

        if (addedNames.isEmpty()) return;
        String who;
        if (addedNames.size() == 1) {
            who = addedNames.get(0);
        } else if (addedNames.size() == 2) {
            who = addedNames.get(0) + " and " + addedNames.get(1);
        } else {
            who = addedNames.get(0) + ", " + addedNames.get(1)
                    + " and " + (addedNames.size() - 2) + " other"
                    + (addedNames.size() - 2 == 1 ? "" : "s");
        }
        String addedText = myName + " added " + who;
        long addedAt = System.currentTimeMillis() + 1; // keep strictly after the "created" line
        DatabaseReference addedRef = FirebaseUtils.getGroupMessagesRef(groupId).push();
        Map<String, Object> added = new HashMap<>();
        added.put("id",        addedRef.getKey());
        added.put("senderId",  "system");
        added.put("senderName","System");
        added.put("text",      addedText);
        added.put("type",      "system");
        added.put("timestamp", addedAt);
        addedRef.setValue(added).addOnSuccessListener(v -> {
            // Reflect the latest system line in the chat-list preview too,
            // same as a normal message would.
            Map<String, Object> lastUpd = new HashMap<>();
            lastUpd.put("lastMessage",     addedText);
            lastUpd.put("lastMessageType", "system");
            lastUpd.put("lastMessageAt",   addedAt);
            lastUpd.put("lastSenderName",  "");
            FirebaseUtils.getGroupsRef().child(groupId).updateChildren(lastUpd);
        });
    }
}
