package com.callx.app.messages;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.models.XProfile;
import com.callx.app.utils.XFirebaseUtils;
import com.callx.app.x.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
import de.hdodenhof.circleimageview.CircleImageView;

/**
 * XCreateGroupDMActivity — Create a new X Group DM.
 *
 * Flow:
 *   1. Enter a group name.
 *   2. Search / select from users you follow (at least 2 required beyond yourself).
 *   3. Tap "Create" → writes to x/dm_groups/{groupId} and opens the conversation.
 *
 * Firebase schema for a group:
 *   x/dm_groups/{groupId}/
 *     name          : String
 *     createdBy     : uid
 *     createdAt     : timestamp
 *     members/{uid}/
 *       name        : String
 *       thumbUrl    : String
 *       joinedAt    : timestamp
 *     lastMessage   : String
 *     lastMessageTs : long
 *     lastSenderUid : String
 *     lastSenderName: String
 *     unread/{uid}  : Boolean
 *     seen/{uid}    : timestamp   (last-seen timestamp per member)
 */
public class XCreateGroupDMActivity extends AppCompatActivity {

    private String myUid, myName, myThumbUrl;
    private EditText etGroupName, etSearch;
    private RecyclerView rvFollowing;
    private TextView tvSelectedCount;
    private Button btnCreate;

    private final List<XProfile>    allFollowing = new ArrayList<>();
    private final List<XProfile>    filteredList = new ArrayList<>();
    private final Set<String>       selectedUids = new LinkedHashSet<>();
    private final Map<String, XProfile> selectedProfiles = new LinkedHashMap<>();

    private FollowingAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_x_create_group_dm);

        com.google.firebase.auth.FirebaseUser me = FirebaseAuth.getInstance().getCurrentUser();
        myUid = me != null ? me.getUid() : "";

        // Load my profile
        if (!myUid.isEmpty()) {
            XFirebaseUtils.xUserRef(myUid).addListenerForSingleValueEvent(
                    new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            myName     = s.child("name").getValue(String.class);
                            myThumbUrl = s.child("thumbUrl").getValue(String.class);
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
        }

        // Toolbar
        View btnBack = findViewById(R.id.btn_create_gdm_back);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());
        TextView tvTitle = findViewById(R.id.tv_create_gdm_title);
        if (tvTitle != null) tvTitle.setText("New Group");

        etGroupName    = findViewById(R.id.et_group_name);
        etSearch       = findViewById(R.id.et_search_following);
        rvFollowing    = findViewById(R.id.rv_create_gdm_following);
        tvSelectedCount= findViewById(R.id.tv_selected_count);
        btnCreate      = findViewById(R.id.btn_create_gdm);

        adapter = new FollowingAdapter();
        rvFollowing.setLayoutManager(new LinearLayoutManager(this));
        rvFollowing.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                filterList(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnCreate.setOnClickListener(v -> createGroup());
        updateCreateButton();

        loadFollowing();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Load following list
    // ─────────────────────────────────────────────────────────────────────

    private void loadFollowing() {
        if (myUid.isEmpty()) return;
        XFirebaseUtils.userFollowingRef(myUid).addListenerForSingleValueEvent(
                new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        List<String> uids = new ArrayList<>();
                        for (DataSnapshot ds : snap.getChildren()) {
                            if (ds.getKey() != null) uids.add(ds.getKey());
                        }
                        loadProfiles(uids);
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {}
                });
    }

    private void loadProfiles(List<String> uids) {
        if (uids.isEmpty()) return;
        for (String uid : uids) {
            XFirebaseUtils.xUserRef(uid).addListenerForSingleValueEvent(
                    new ValueEventListener() {
                        @Override public void onDataChange(@NonNull DataSnapshot s) {
                            XProfile p = new XProfile();
                            p.uid      = uid;
                            p.name     = s.child("name").getValue(String.class);
                            p.handle   = s.child("handle").getValue(String.class);
                            p.thumbUrl = s.child("thumbUrl").getValue(String.class);
                            p.photoUrl = s.child("photoUrl").getValue(String.class);
                            if (p.name != null) {
                                allFollowing.add(p);
                                filterList(etSearch.getText().toString());
                            }
                        }
                        @Override public void onCancelled(@NonNull DatabaseError e) {}
                    });
        }
    }

    private void filterList(String query) {
        filteredList.clear();
        String q = query.toLowerCase().trim();
        for (XProfile p : allFollowing) {
            if (q.isEmpty() || (p.name != null && p.name.toLowerCase().contains(q))
                    || (p.handle != null && p.handle.toLowerCase().contains(q))) {
                filteredList.add(p);
            }
        }
        adapter.notifyDataSetChanged();
    }

    private void onProfileToggled(XProfile p) {
        if (selectedUids.contains(p.uid)) {
            selectedUids.remove(p.uid);
            selectedProfiles.remove(p.uid);
        } else {
            selectedUids.add(p.uid);
            selectedProfiles.put(p.uid, p);
        }
        updateCreateButton();
        adapter.notifyDataSetChanged();
    }

    private void updateCreateButton() {
        int count = selectedUids.size();
        if (tvSelectedCount != null) {
            tvSelectedCount.setText(count == 0 ? "Select people to add"
                    : count + " selected");
        }
        if (btnCreate != null) btnCreate.setEnabled(count >= 1);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Create group
    // ─────────────────────────────────────────────────────────────────────

    private void createGroup() {
        String groupName = etGroupName != null
                ? etGroupName.getText().toString().trim() : "";
        if (groupName.isEmpty()) {
            Toast.makeText(this, "Enter a group name", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedUids.isEmpty()) {
            Toast.makeText(this, "Select at least 1 person", Toast.LENGTH_SHORT).show();
            return;
        }

        DatabaseReference groupRef = XFirebaseUtils.xDmGroupsRef().push();
        String groupId = groupRef.getKey();
        if (groupId == null) return;

        long now = System.currentTimeMillis();

        // Build members map (includes me)
        Map<String, Object> members = new HashMap<>();
        // Add myself
        Map<String, Object> myMemberData = new HashMap<>();
        myMemberData.put("name",     myName != null ? myName : "");
        myMemberData.put("thumbUrl", myThumbUrl != null ? myThumbUrl : "");
        myMemberData.put("joinedAt", now);
        members.put(myUid, myMemberData);

        for (Map.Entry<String, XProfile> entry : selectedProfiles.entrySet()) {
            XProfile p = entry.getValue();
            Map<String, Object> memberData = new HashMap<>();
            memberData.put("name",     p.name != null ? p.name : "");
            memberData.put("thumbUrl", p.thumbUrl != null ? p.thumbUrl : "");
            memberData.put("joinedAt", now);
            members.put(entry.getKey(), memberData);
        }

        // Group root data
        Map<String, Object> groupData = new HashMap<>();
        groupData.put("name",         groupName);
        groupData.put("createdBy",    myUid);
        groupData.put("createdAt",    now);
        groupData.put("lastMessage",  myName + " created this group");
        groupData.put("lastMessageTs",now);
        groupData.put("lastSenderUid",myUid);
        groupData.put("members",      members);

        btnCreate.setEnabled(false);
        groupRef.setValue(groupData)
                .addOnSuccessListener(unused -> {
                    // Send system message
                    DatabaseReference msgRef = XFirebaseUtils.xDmGroupMessagesRef(groupId);
                    String key = msgRef.push().getKey();
                    if (key != null) {
                        Map<String, Object> sysMsg = new HashMap<>();
                        sysMsg.put("isSystemMessage", true);
                        sysMsg.put("systemText",      (myName != null ? myName : "Someone") + " created this group");
                        sysMsg.put("timestamp",       now);
                        msgRef.child(key).setValue(sysMsg);
                    }
                    // Open conversation
                    Intent i = new Intent(this, XGroupDMConversationActivity.class);
                    i.putExtra("group_id",   groupId);
                    i.putExtra("group_name", groupName);
                    startActivity(i);
                    finish();
                })
                .addOnFailureListener(e -> {
                    btnCreate.setEnabled(true);
                    Toast.makeText(this, "Failed to create group: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Adapter
    // ─────────────────────────────────────────────────────────────────────

    private class FollowingAdapter extends RecyclerView.Adapter<FollowingAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_x_create_gdm_user, parent, false);
            return new VH(v);
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            XProfile p = filteredList.get(pos);
            h.tvName.setText(p.name != null ? p.name : "");
            h.tvHandle.setText(p.handle != null ? "@" + p.handle : "");
            boolean selected = selectedUids.contains(p.uid);
            h.cbSelect.setChecked(selected);
            String avatarUrl = p.thumbUrl != null ? p.thumbUrl : p.photoUrl;
            if (avatarUrl != null)
                Glide.with(h.ivAvatar.getContext()).load(avatarUrl).circleCrop()
                        .placeholder(R.drawable.ic_person).into(h.ivAvatar);
            h.itemView.setOnClickListener(v -> onProfileToggled(p));
            h.cbSelect.setOnClickListener(v -> onProfileToggled(p));
        }

        @Override public int getItemCount() { return filteredList.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivAvatar;
            TextView tvName, tvHandle;
            CheckBox cbSelect;
            VH(View v) {
                super(v);
                ivAvatar = v.findViewById(R.id.iv_gdm_user_avatar);
                tvName   = v.findViewById(R.id.tv_gdm_user_name);
                tvHandle = v.findViewById(R.id.tv_gdm_user_handle);
                cbSelect = v.findViewById(R.id.cb_gdm_user_select);
            }
        }
    }
}
