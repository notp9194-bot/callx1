package com.callx.app.activities;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.*;

import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.adapters.GroupMemberAdapter;
import com.callx.app.adapters.MediaThumbAdapter;
import com.callx.app.db.AppDatabase;
import com.callx.app.db.entity.GroupEntity;
import com.callx.app.models.Group;
import com.callx.app.models.Message;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FirebaseUtils;
import java.util.concurrent.Executors;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.tabs.TabLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * GroupInfoActivity — Ultra-advanced comprehensive group information screen.
 *
 * Features:
 *  1. Collapsing header with group icon (admin-editable), name & description
 *  2. Quick-action bar: Video call, Audio call, Search in chat, Group settings
 *  3. Shared media gallery (Images / Videos / Files tabs) with 3-column grid
 *  4. Full member list with online status, admin badges, per-member options
 *  5. Admin controls: rename, change icon, edit description
 *  6. Add members (admin only)
 *  7. Invite link management: copy, share, reset (admin only)
 *  8. Pinned messages viewer
 *  9. Report group, Leave group, Delete group (admin only)
 * 10. Group creation metadata
 */
public class GroupInfoActivity extends AppCompatActivity {

    // Intent extras
    public static final String EXTRA_GROUP_ID   = "groupId";
    public static final String EXTRA_GROUP_NAME = "groupName";

    // Views
    private de.hdodenhof.circleimageview.CircleImageView ivGroupIcon;
    private ImageButton btnChangeIcon, btnEditName;
    private TextView tvGroupName, tvGroupDesc, tvCreatedAt;
    private TextView tvInviteLink, tvMemberCount, tvDNDStatus;
    private EditText etDescEdit;
    private View cardDescEdit;
    private View btnSaveDesc;
    private RecyclerView rvMembers, rvMedia;
    private TabLayout tabMedia;

    // Admin-only views
    private View btnAddMember, btnResetLink, btnDeleteGroup;

    // State
    private String groupId, groupName;
    private String currentUid;
    private boolean isAdmin = false;
    private String currentIconUrl = null;

    // Adapters
    private GroupMemberAdapter memberAdapter;
    private MediaThumbAdapter  mediaAdapter;

    private final List<GroupMemberAdapter.MemberItem> members  = new ArrayList<>();
    private final List<String>                         mediaUrls = new ArrayList<>();

    // Firebase listeners (for cleanup)
    private ValueEventListener groupListener, membersListener, mediaListener;

    // Image picker for group icon
    private ActivityResultLauncher<String> iconPicker;

    // ── Lifecycle ─────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_info);

        groupId   = getIntent().getStringExtra(EXTRA_GROUP_ID);
        groupName = getIntent().getStringExtra(EXTRA_GROUP_NAME);

        if (groupId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish();
            return;
        }
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();

        bindViews();
        setupToolbar();
        setupMediaTabs();
        setupMembersRecycler();
        setupMediaRecycler();
        setupIconPicker();
        setupClickListeners();

        loadGroupData();
        listenMembers();
        listenMediaMessages();
    }

    @Override
    protected void onDestroy() {
        if (groupListener  != null) FirebaseUtils.getGroupsRef().child(groupId).removeEventListener(groupListener);
        if (membersListener != null) FirebaseUtils.getGroupMembersRef(groupId).removeEventListener(membersListener);
        if (mediaListener  != null) FirebaseUtils.getGroupMessagesRef(groupId).removeEventListener(mediaListener);
        super.onDestroy();
    }

    // ── View Binding ──────────────────────────────────────────────────────
    private void bindViews() {
        ivGroupIcon     = findViewById(R.id.iv_group_icon);
        btnChangeIcon   = findViewById(R.id.btn_change_icon);
        btnEditName     = findViewById(R.id.btn_edit_name);
        tvGroupName     = findViewById(R.id.tv_group_name);
        tvGroupDesc     = findViewById(R.id.tv_group_desc);
        tvCreatedAt     = findViewById(R.id.tv_created_at);
        tvInviteLink    = findViewById(R.id.tv_invite_link);
        tvMemberCount   = findViewById(R.id.tv_member_count);
        etDescEdit      = findViewById(R.id.et_desc_edit);
        cardDescEdit    = findViewById(R.id.card_desc_edit);
        btnSaveDesc     = findViewById(R.id.btn_save_desc);
        rvMembers       = findViewById(R.id.rv_members);
        rvMedia         = findViewById(R.id.rv_media);
        tabMedia        = findViewById(R.id.tab_media);
        btnAddMember    = findViewById(R.id.btn_add_member);
        btnResetLink    = findViewById(R.id.btn_reset_link);
        btnDeleteGroup  = findViewById(R.id.btn_delete_group);
    }

    // ── Toolbar ───────────────────────────────────────────────────────────
    private void setupToolbar() {
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        // Collapse behaviour — show/hide title
        AppBarLayout appBar = findViewById(R.id.appbar);
        appBar.addOnOffsetChangedListener((abl, offset) -> {
            float ratio = Math.abs(offset) / (float) abl.getTotalScrollRange();
            boolean collapsed = ratio > 0.85f;
            if (getSupportActionBar() != null)
                getSupportActionBar().setTitle(collapsed ? groupName : "");
        });
    }

    // ── Media Tabs ────────────────────────────────────────────────────────
    private void setupMediaTabs() {
        tabMedia.addTab(tabMedia.newTab().setText("Photos"));
        tabMedia.addTab(tabMedia.newTab().setText("Videos"));
        tabMedia.addTab(tabMedia.newTab().setText("Files"));
        tabMedia.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) { refreshMediaGrid(tab.getPosition()); }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    // ── RecyclerViews ─────────────────────────────────────────────────────
    private void setupMembersRecycler() {
        memberAdapter = new GroupMemberAdapter(members, currentUid, (uid, action) -> {
            handleMemberAction(uid, action);
        });
        rvMembers.setLayoutManager(new LinearLayoutManager(this));
        rvMembers.setNestedScrollingEnabled(false);
        rvMembers.setAdapter(memberAdapter);
    }

    private void setupMediaRecycler() {
        mediaAdapter = new MediaThumbAdapter(mediaUrls, url -> {
            Intent i = new Intent(this, MediaViewerActivity.class);
            i.putExtra("mediaUrl", url);
            i.putExtra("mediaType", "image");
            startActivity(i);
        });
        GridLayoutManager glm = new GridLayoutManager(this, 3);
        rvMedia.setLayoutManager(glm);
        rvMedia.setNestedScrollingEnabled(false);
        rvMedia.setAdapter(mediaAdapter);
    }

    // ── Icon Picker ───────────────────────────────────────────────────────
    private void setupIconPicker() {
        iconPicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) uploadGroupIcon(uri);
                });
    }

    // ── Click Listeners ───────────────────────────────────────────────────
    private void setupClickListeners() {
        // Change icon (admin)
        btnChangeIcon.setOnClickListener(v -> iconPicker.launch("image/*"));

        // Edit name (admin)
        btnEditName.setOnClickListener(v -> showRenameDialog());

        // Save description
        btnSaveDesc.setOnClickListener(v -> saveDescription());

        // Quick actions
        findViewById(R.id.btn_quick_video).setOnClickListener(v ->
                Toast.makeText(this, "Group video call — coming soon", Toast.LENGTH_SHORT).show());

        findViewById(R.id.btn_quick_audio).setOnClickListener(v ->
                Toast.makeText(this, "Group audio call — coming soon", Toast.LENGTH_SHORT).show());

        findViewById(R.id.btn_quick_search).setOnClickListener(v -> {
            Intent i = new Intent(this, SearchActivity.class);
            i.putExtra("groupId", groupId);
            startActivity(i);
        });

        findViewById(R.id.btn_quick_settings).setOnClickListener(v -> {
            Intent i = new Intent(this, GroupSettingsActivity.class);
            i.putExtra(GroupSettingsActivity.EXTRA_GROUP_ID,   groupId);
            i.putExtra(GroupSettingsActivity.EXTRA_GROUP_NAME, groupName);
            startActivity(i);
        });

        // Add member
        btnAddMember.setOnClickListener(v -> showAddMemberDialog());

        // Invite link — copy
        findViewById(R.id.btn_copy_link).setOnClickListener(v -> {
            String link = "callx://join/" + groupId;
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Invite Link", link));
            Toast.makeText(this, "Link copied!", Toast.LENGTH_SHORT).show();
        });

        // Share invite link
        findViewById(R.id.btn_share_link).setOnClickListener(v -> shareInviteLink());

        // Reset invite link (admin)
        btnResetLink.setOnClickListener(v -> showResetLinkConfirm());

        // View all media
        findViewById(R.id.tv_view_all_media).setOnClickListener(v ->
                Toast.makeText(this, "Media viewer — coming soon", Toast.LENGTH_SHORT).show());

        // Report group
        findViewById(R.id.btn_report_group).setOnClickListener(v -> showReportDialog());

        // Leave group
        findViewById(R.id.btn_leave_group).setOnClickListener(v -> confirmLeaveGroup());

        // Delete group (admin only)
        btnDeleteGroup.setOnClickListener(v -> confirmDeleteGroup());
    }

    // ── Firebase: Load group data ─────────────────────────────────────────
    private void loadGroupData() {
        // v18 IMPROVEMENT 3: Room se pehle load karo — offline blank screen fix
        Executors.newSingleThreadExecutor().execute(() -> {
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            GroupEntity cached = db.groupDao().getGroup(groupId);
            if (cached != null) {
                runOnUiThread(() -> {
                    groupName = cached.name != null ? cached.name : "Group";
                    tvGroupName.setText(groupName);
                    if (cached.description != null && !cached.description.isEmpty()) {
                        tvGroupDesc.setText(cached.description);
                        tvGroupDesc.setVisibility(View.VISIBLE);
                        etDescEdit.setText(cached.description);
                    } else {
                        tvGroupDesc.setText("Tap to add description");
                        tvGroupDesc.setVisibility(View.VISIBLE);
                    }
                    currentIconUrl = cached.iconUrl;
                    if (currentIconUrl != null && !currentIconUrl.isEmpty()) {
                        Glide.with(GroupInfoActivity.this)
                                .load(currentIconUrl)
                                .placeholder(R.drawable.ic_group)
                                .into(ivGroupIcon);
                    }
                    tvInviteLink.setText("callx://join/" + groupId);
                });
            }
        });

        groupListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                Group g = snap.getValue(Group.class);
                if (g == null) return;

                groupName = g.name != null ? g.name : "Group";
                tvGroupName.setText(groupName);

                String desc = g.description;
                if (desc != null && !desc.isEmpty()) {
                    tvGroupDesc.setText(desc);
                    tvGroupDesc.setVisibility(View.VISIBLE);
                    etDescEdit.setText(desc);
                } else {
                    tvGroupDesc.setText("Tap to add description");
                    tvGroupDesc.setVisibility(View.VISIBLE);
                    etDescEdit.setText("");
                }

                if (g.createdAt != null) {
                    SimpleDateFormat sdf = new SimpleDateFormat("dd MMM yyyy", Locale.getDefault());
                    tvCreatedAt.setText("Created " + sdf.format(new Date(g.createdAt)));
                }

                // Load group icon
                currentIconUrl = g.iconUrl;
                if (currentIconUrl != null && !currentIconUrl.isEmpty()) {
                    Glide.with(GroupInfoActivity.this)
                            .load(currentIconUrl)
                            .placeholder(R.drawable.ic_group)
                            .into(ivGroupIcon);
                }

                // Invite link
                tvInviteLink.setText("callx://join/" + groupId);

                // Check if current user is admin
                boolean adminByMap  = g.admins   != null && g.admins.containsKey(currentUid);
                boolean adminByField = currentUid.equals(g.adminUid);
                setAdminMode(adminByMap || adminByField);

                // v18 IMPROVEMENT 3: Room mein update karo for next offline visit
                Executors.newSingleThreadExecutor().execute(() -> {
                    AppDatabase db = AppDatabase.getInstance(getApplicationContext());
                    GroupEntity entity = db.groupDao().getGroup(groupId);
                    if (entity == null) entity = new GroupEntity();
                    entity.id          = groupId;
                    entity.name        = g.name;
                    entity.description = g.description;
                    entity.iconUrl     = g.iconUrl;
                    entity.syncedAt    = System.currentTimeMillis();
                    db.groupDao().insertGroup(entity);
                });
            }

            @Override
            public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getGroupsRef().child(groupId).addValueEventListener(groupListener);
    }

    private void setAdminMode(boolean admin) {
        isAdmin = admin;
        btnChangeIcon.setVisibility(admin ? View.VISIBLE : View.GONE);
        btnEditName.setVisibility(admin ? View.VISIBLE : View.GONE);
        cardDescEdit.setVisibility(admin ? View.VISIBLE : View.GONE);
        btnAddMember.setVisibility(admin ? View.VISIBLE : View.GONE);
        btnResetLink.setVisibility(admin ? View.VISIBLE : View.GONE);
        btnDeleteGroup.setVisibility(admin ? View.VISIBLE : View.GONE);
        if (memberAdapter != null) memberAdapter.setIsAdmin(admin);
    }

    // ── Firebase: Members listener ────────────────────────────────────────
    private void listenMembers() {
        membersListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                members.clear();
                long onlineMs = com.callx.app.utils.Constants.ONLINE_WINDOW_MS;
                long now = System.currentTimeMillis();
                for (DataSnapshot c : snap.getChildren()) {
                    String uid      = c.getKey();
                    String name     = c.child("name").getValue(String.class);
                    String role     = c.child("role").getValue(String.class);
                    Long   lastSeen = c.child("lastSeen").getValue(Long.class);
                    String photo    = c.child("photoUrl").getValue(String.class);
                    boolean online  = lastSeen != null && (now - lastSeen) < onlineMs;
                    members.add(new GroupMemberAdapter.MemberItem(
                            uid, name != null ? name : "Member",
                            role != null ? role : "member",
                            photo, online, lastSeen));
                }
                // Sort: creator/admin first, then by name
                members.sort((a, b) -> {
                    int ra = "admin".equals(a.role) ? 0 : 1;
                    int rb = "admin".equals(b.role) ? 0 : 1;
                    if (ra != rb) return ra - rb;
                    return a.name.compareToIgnoreCase(b.name);
                });
                tvMemberCount.setText(members.size() + " member" + (members.size() == 1 ? "" : "s"));
                memberAdapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(DatabaseError e) {}
        };
        FirebaseUtils.getGroupMembersRef(groupId).addValueEventListener(membersListener);
    }

    // ── Firebase: Media messages listener ─────────────────────────────────
    // Current tab: 0=images, 1=videos, 2=files
    private int currentMediaTab = 0;

    private void listenMediaMessages() {
        Query q = FirebaseUtils.getGroupMessagesRef(groupId)
                .orderByChild("type")
                .limitToLast(60);
        mediaListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snap) {
                refreshMediaGrid(currentMediaTab);
                // Cache all media messages for tab switching
                mediaUrls.clear();
                for (DataSnapshot c : snap.getChildren()) {
                    String type = c.child("type").getValue(String.class);
                    String url  = c.child("mediaUrl").getValue(String.class);
                    if ("image".equals(type) && url != null && !url.isEmpty())
                        mediaUrls.add(url);
                }
                mediaAdapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(DatabaseError e) {}
        };
        // Listen to image messages
        FirebaseUtils.getGroupMessagesRef(groupId)
                .orderByChild("type")
                .equalTo("image")
                .limitToLast(30)
                .addValueEventListener(mediaListener);
    }

    private void refreshMediaGrid(int tab) {
        currentMediaTab = tab;
        mediaUrls.clear();
        // Load different media based on tab — re-query Firebase
        String mediaType;
        switch (tab) {
            case 1: mediaType = "video"; break;
            case 2: mediaType = "file";  break;
            default: mediaType = "image"; break;
        }
        FirebaseUtils.getGroupMessagesRef(groupId)
                .orderByChild("type")
                .equalTo(mediaType)
                .limitToLast(30)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snap) {
                        mediaUrls.clear();
                        for (DataSnapshot c : snap.getChildren()) {
                            String url = c.child("mediaUrl").getValue(String.class);
                            if (url != null && !url.isEmpty()) mediaUrls.add(url);
                        }
                        if (mediaAdapter != null) mediaAdapter.notifyDataSetChanged();
                    }
                    @Override public void onCancelled(DatabaseError e) {}
                });
    }

    // ── Member action handler ─────────────────────────────────────────────
    private void handleMemberAction(String uid, String action) {
        if (!isAdmin && !"view_profile".equals(action)) {
            Toast.makeText(this, "Admin permissions required", Toast.LENGTH_SHORT).show();
            return;
        }
        switch (action) {
            case "view_profile":
                openMemberProfile(uid);
                break;
            case "make_admin":
                setMemberRole(uid, "admin");
                break;
            case "revoke_admin":
                setMemberRole(uid, "member");
                break;
            case "remove":
                confirmRemoveMember(uid);
                break;
            case "message":
                openDirectChat(uid);
                break;
        }
    }

    private void openMemberProfile(String uid) {
        // Find member's name
        for (GroupMemberAdapter.MemberItem m : members) {
            if (uid.equals(m.uid)) {
                Intent i = new Intent(this, ChatActivity.class);
                i.putExtra("partnerUid",  m.uid);
                i.putExtra("partnerName", m.name);
                startActivity(i);
                return;
            }
        }
    }

    private void openDirectChat(String uid) {
        for (GroupMemberAdapter.MemberItem m : members) {
            if (uid.equals(m.uid)) {
                Intent i = new Intent(this, ChatActivity.class);
                i.putExtra("partnerUid",  m.uid);
                i.putExtra("partnerName", m.name);
                startActivity(i);
                return;
            }
        }
    }

    private void setMemberRole(String uid, String role) {
        FirebaseUtils.getGroupMembersRef(groupId).child(uid).child("role").setValue(role);
        if ("admin".equals(role)) {
            FirebaseUtils.getGroupsRef().child(groupId).child("admins").child(uid).setValue(true);
            Toast.makeText(this, "Made admin 👑", Toast.LENGTH_SHORT).show();
        } else {
            FirebaseUtils.getGroupsRef().child(groupId).child("admins").child(uid).removeValue();
            Toast.makeText(this, "Admin revoked", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmRemoveMember(String uid) {
        String name = "Member";
        for (GroupMemberAdapter.MemberItem m : members)
            if (uid.equals(m.uid)) { name = m.name; break; }
        final String finalName = name;
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage(name + " will be removed from this group.")
                .setPositiveButton("Remove", (d, w) -> {
                    FirebaseUtils.getGroupMembersRef(groupId).child(uid).removeValue();
                    FirebaseUtils.getGroupsRef().child(groupId).child("admins").child(uid).removeValue();
                    FirebaseUtils.db().getReference("userGroups").child(uid).child(groupId).removeValue();
                    // System message
                    DatabaseReference sysRef = FirebaseUtils.getGroupMessagesRef(groupId).push();
                    Map<String, Object> sys = new HashMap<>();
                    sys.put("id",        sysRef.getKey());
                    sys.put("senderId",  "system");
                    sys.put("text",      finalName + " was removed from the group");
                    sys.put("type",      "system");
                    sys.put("timestamp", System.currentTimeMillis());
                    sysRef.setValue(sys);
                    Toast.makeText(this, finalName + " removed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Admin: Rename group ───────────────────────────────────────────────
    private void showRenameDialog() {
        EditText et = new EditText(this);
        et.setText(groupName);
        et.setSelection(et.getText().length());
        int p = dp(16); et.setPadding(p, p, p, p);
        new AlertDialog.Builder(this)
                .setTitle("Rename Group")
                .setView(et)
                .setPositiveButton("Save", (d, w) -> {
                    String newName = et.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(groupName)) return;
                    groupName = newName;
                    tvGroupName.setText(newName);
                    FirebaseUtils.getGroupsRef().child(groupId).child("name").setValue(newName);
                    postSystemMessage(FirebaseUtils.getCurrentName() + " changed the group name to \"" + newName + "\"");
                    Toast.makeText(this, "Group renamed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Admin: Save description ───────────────────────────────────────────
    private void saveDescription() {
        String desc = etDescEdit.getText().toString().trim();
        FirebaseUtils.getGroupsRef().child(groupId).child("description").setValue(desc);
        tvGroupDesc.setText(desc.isEmpty() ? "Tap to add description" : desc);
        Toast.makeText(this, "Description saved", Toast.LENGTH_SHORT).show();
    }

    // ── Admin: Upload group icon ──────────────────────────────────────────
    private void uploadGroupIcon(Uri uri) {
        ProgressDialog pd = new ProgressDialog(this);
        pd.setMessage("Uploading icon…");
        pd.setCancelable(false);
        pd.show();
        CloudinaryUploader.upload(this, uri, "image", "callx_groups",
                new CloudinaryUploader.UploadCallback() {
                    @Override
                    public void onSuccess(CloudinaryUploader.Result r) {
                        pd.dismiss();
                        currentIconUrl = r.secureUrl;
                        Glide.with(GroupInfoActivity.this).load(r.secureUrl).into(ivGroupIcon);
                        FirebaseUtils.getGroupsRef().child(groupId).child("iconUrl").setValue(r.secureUrl);
                        Toast.makeText(GroupInfoActivity.this, "Icon updated", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(String err) {
                        pd.dismiss();
                        Toast.makeText(GroupInfoActivity.this,
                                "Upload failed: " + err, Toast.LENGTH_LONG).show();
                    }
                });
    }

    // ── Add member ────────────────────────────────────────────────────────
    private void showAddMemberDialog() {
        EditText et = new EditText(this);
        et.setHint("Enter CallX ID or UID");
        int p = dp(16); et.setPadding(p, p, p, p);
        new AlertDialog.Builder(this)
                .setTitle("Add Member")
                .setView(et)
                .setPositiveButton("Add", (d, w) -> {
                    String uid = et.getText().toString().trim();
                    if (uid.isEmpty()) return;
                    addMemberByUid(uid);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addMemberByUid(String uid) {
        // Look up user
        FirebaseUtils.getUserRef(uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snap) {
                        if (!snap.exists()) {
                            Toast.makeText(GroupInfoActivity.this,
                                    "User not found", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        String name = snap.child("name").getValue(String.class);
                        if (name == null) name = snap.child("displayName").getValue(String.class);
                        if (name == null) name = "Member";

                        final String memberName = name;
                        Map<String, Object> memberData = new HashMap<>();
                        memberData.put("name",    memberName);
                        memberData.put("role",    "member");
                        memberData.put("addedAt", System.currentTimeMillis());

                        FirebaseUtils.getGroupMembersRef(groupId).child(uid)
                                .setValue(memberData);
                        FirebaseUtils.db().getReference("userGroups")
                                .child(uid).child(groupId).setValue(true);

                        postSystemMessage(memberName + " was added to the group");
                        Toast.makeText(GroupInfoActivity.this,
                                memberName + " added", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onCancelled(DatabaseError e) {}
                });
    }

    // ── Invite link ───────────────────────────────────────────────────────
    private void shareInviteLink() {
        String link = "callx://join/" + groupId;
        String body = "Join *" + groupName + "* on CallX!\n\n" + link;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(i, "Share invite link"));
    }

    private void showResetLinkConfirm() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Invite Link?")
                .setMessage("The old link will stop working. Anyone with the old link won't be able to join.")
                .setPositiveButton("Reset", (d, w) -> {
                    // Generate a new token-based sub-path (server-side ideally, here we use timestamp)
                    String newToken = Long.toHexString(System.currentTimeMillis());
                    FirebaseUtils.getGroupsRef().child(groupId)
                            .child("inviteToken").setValue(newToken);
                    tvInviteLink.setText("callx://join/" + groupId + "?t=" + newToken);
                    Toast.makeText(this, "Invite link reset", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Report group ──────────────────────────────────────────────────────
    private void showReportDialog() {
        String[] reasons = {"Spam or scam", "Inappropriate content",
                "Harassment", "Misinformation", "Other"};
        new AlertDialog.Builder(this)
                .setTitle("Report Group")
                .setItems(reasons, (d, which) -> {
                    FirebaseUtils.db().getReference("reports").push().setValue(
                            new HashMap<String, Object>() {{
                                put("groupId",   groupId);
                                put("groupName", groupName);
                                put("reportedBy", currentUid);
                                put("reason",    reasons[which]);
                                put("timestamp", System.currentTimeMillis());
                            }});
                    Toast.makeText(this, "Group reported. Thank you.", Toast.LENGTH_LONG).show();
                })
                .show();
    }

    // ── Leave group ───────────────────────────────────────────────────────
    private void confirmLeaveGroup() {
        new AlertDialog.Builder(this)
                .setTitle("Leave Group?")
                .setMessage("You will no longer receive messages from this group.")
                .setPositiveButton("Leave", (d, w) -> doLeaveGroup())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doLeaveGroup() {
        String myName = FirebaseUtils.getCurrentName();
        Map<String, Object> upd = new HashMap<>();
        upd.put("groups/"    + groupId + "/members/" + currentUid, null);
        upd.put("groups/"    + groupId + "/admins/"  + currentUid, null);
        upd.put("groups/"    + groupId + "/mutedBy/" + currentUid, null);
        upd.put("groups/"    + groupId + "/unread/"  + currentUid, null);
        upd.put("userGroups/" + currentUid + "/" + groupId, null);
        FirebaseUtils.db().getReference().updateChildren(upd);
        postSystemMessage(myName + " left the group");
        finish();
    }

    // ── Delete group (admin only) ─────────────────────────────────────────
    private void confirmDeleteGroup() {
        new AlertDialog.Builder(this)
                .setTitle("Delete Group?")
                .setMessage("All messages and members will be permanently removed. This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> doDeleteGroup())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doDeleteGroup() {
        // Remove group for all members
        for (GroupMemberAdapter.MemberItem m : members) {
            FirebaseUtils.db().getReference("userGroups")
                    .child(m.uid).child(groupId).removeValue();
        }
        // Remove group data
        FirebaseUtils.getGroupsRef().child(groupId).removeValue();
        FirebaseUtils.getGroupMessagesRef(groupId).removeValue();
        Toast.makeText(this, "Group deleted", Toast.LENGTH_LONG).show();
        // Navigate back to main
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    // ── System message helper ─────────────────────────────────────────────
    private void postSystemMessage(String text) {
        DatabaseReference sysRef = FirebaseUtils.getGroupMessagesRef(groupId).push();
        Map<String, Object> sys = new HashMap<>();
        sys.put("id",        sysRef.getKey());
        sys.put("senderId",  "system");
        sys.put("senderName","System");
        sys.put("text",      text);
        sys.put("type",      "system");
        sys.put("timestamp", System.currentTimeMillis());
        sysRef.setValue(sys);
    }

    // ── Utility ───────────────────────────────────────────────────────────
    private int dp(int dp) {
        return (int)(dp * getResources().getDisplayMetrics().density);
    }
}
