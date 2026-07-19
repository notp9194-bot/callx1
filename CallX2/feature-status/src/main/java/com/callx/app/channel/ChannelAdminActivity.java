package com.callx.app.channel;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.status.R;
import com.callx.app.viewmodel.ChannelViewModel;
import com.callx.app.utils.FirebaseUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.*;
import de.hdodenhof.circleimageview.CircleImageView;
import java.util.*;

/**
 * ChannelAdminActivity — full WhatsApp-level admin management panel.
 *
 * Owner can:
 *   - View current admins with roles (Owner / Admin)
 *   - Search for users by username/name (not just UID)
 *   - Add an admin from search results or by UID
 *   - Remove an existing admin
 *   - Transfer ownership to any admin
 *   - Navigate to ChannelFollowersActivity to view/manage followers
 *   - Navigate to ChannelInviteLinkActivity for invite link management
 *   - Navigate to ChannelScheduledPostsActivity for scheduled posts
 */
public class ChannelAdminActivity extends AppCompatActivity {

    public static final String EXTRA_CHANNEL_ID   = "channelId";
    public static final String EXTRA_CHANNEL_NAME = "channelName";
    public static final String EXTRA_OWNER_UID    = "ownerUid";

    private ChannelViewModel viewModel;
    private String channelId, channelName, ownerUid;
    private AdminAdapter adapter;
    private final List<AdminEntry> admins = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_channel_admin);

        channelId   = getIntent().getStringExtra(EXTRA_CHANNEL_ID);
        channelName = getIntent().getStringExtra(EXTRA_CHANNEL_NAME);
        ownerUid    = getIntent().getStringExtra(EXTRA_OWNER_UID);
        if (channelId == null) { finish(); return; }

        viewModel = new ViewModelProvider(this).get(ChannelViewModel.class);

        Toolbar toolbar = findViewById(R.id.toolbar_admin);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Admins — " + channelName);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView rv = findViewById(R.id.rv_admins);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AdminAdapter();
        rv.setAdapter(adapter);

        FloatingActionButton fab = findViewById(R.id.fab_add_admin);
        if (fab != null) fab.setOnClickListener(v -> showAddAdminDialog());

        // Quick-access buttons
        View btnFollowers   = findViewById(R.id.btn_view_followers);
        View btnInviteLink  = findViewById(R.id.btn_manage_invite_link);
        View btnScheduled   = findViewById(R.id.btn_scheduled_posts);

        if (btnFollowers != null) btnFollowers.setOnClickListener(v -> {
            Intent i = new Intent(this, ChannelFollowersActivity.class);
            i.putExtra(ChannelFollowersActivity.EXTRA_CHANNEL_ID,   channelId);
            i.putExtra(ChannelFollowersActivity.EXTRA_CHANNEL_NAME, channelName);
            i.putExtra(ChannelFollowersActivity.EXTRA_OWNER_UID,    ownerUid);
            startActivity(i);
        });

        if (btnInviteLink != null) btnInviteLink.setOnClickListener(v -> {
            Intent i = new Intent(this, ChannelInviteLinkActivity.class);
            i.putExtra(ChannelInviteLinkActivity.EXTRA_CHANNEL_ID,   channelId);
            i.putExtra(ChannelInviteLinkActivity.EXTRA_CHANNEL_NAME, channelName);
            startActivity(i);
        });

        if (btnScheduled != null) btnScheduled.setOnClickListener(v -> {
            Intent i = new Intent(this, ChannelScheduledPostsActivity.class);
            i.putExtra(ChannelScheduledPostsActivity.EXTRA_CHANNEL_ID,   channelId);
            i.putExtra(ChannelScheduledPostsActivity.EXTRA_CHANNEL_NAME, channelName);
            startActivity(i);
        });

        viewModel.toastMessage.observe(this, msg -> {
            if (msg != null && !msg.isEmpty()) {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                if (msg.contains("added") || msg.contains("removed") || msg.contains("transferred")) loadAdmins();
            }
        });

        loadAdmins();
    }

    private void loadAdmins() {
        viewModel.loadAdmins(channelId, uidToRole -> {
            admins.clear();
            // Owner always first
            String[] ownerEntry = {ownerUid};
            if (uidToRole.containsKey(ownerUid)) {
                admins.add(new AdminEntry(ownerUid, "owner"));
            }
            for (Map.Entry<String, String> e : uidToRole.entrySet()) {
                if (!e.getKey().equals(ownerUid))
                    admins.add(new AdminEntry(e.getKey(), e.getValue()));
            }
            resolveAdminProfiles();
        });
    }

    private void resolveAdminProfiles() {
        if (admins.isEmpty()) { adapter.setAdmins(admins); return; }
        final int[] done = {0};
        for (AdminEntry entry : admins) {
            FirebaseUtils.getUserRef(entry.uid)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot snap) {
                        String name = snap.child("name").getValue(String.class);
                        String icon = snap.child("photoUrl").getValue(String.class);
                        entry.name    = name != null ? name : entry.uid.substring(0, 8) + "…";
                        entry.iconUrl = icon != null ? icon : "";
                        done[0]++;
                        if (done[0] >= admins.size()) adapter.setAdmins(new ArrayList<>(admins));
                    }
                    @Override public void onCancelled(@NonNull DatabaseError e) {
                        entry.name = entry.uid.substring(0, 8) + "…";
                        done[0]++;
                        if (done[0] >= admins.size()) adapter.setAdmins(new ArrayList<>(admins));
                    }
                });
        }
    }

    // ── Add Admin Dialog with user search ─────────────────────────────────

    private void showAddAdminDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_search_user, null);
        TextInputEditText etSearch = dialogView != null ? dialogView.findViewById(R.id.et_search_user) : null;
        RecyclerView rvResults     = dialogView != null ? dialogView.findViewById(R.id.rv_user_results) : null;
        final List<UserSearchEntry> results = new ArrayList<>();

        UserSearchAdapter searchAdapter = new UserSearchAdapter(results, entry -> {
            // User selected → add as admin
            viewModel.addAdmin(channelId, entry.uid);
        });
        if (rvResults != null) {
            rvResults.setLayoutManager(new LinearLayoutManager(this));
            rvResults.setAdapter(searchAdapter);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("Add admin")
            .setView(dialogView)
            .setNegativeButton("Cancel", null)
            .create();

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    String query = s.toString().trim().toLowerCase();
                    if (query.length() >= 2) searchUsers(query, results, searchAdapter);
                    else { results.clear(); searchAdapter.notifyDataSetChanged(); }
                }
            });
        }

        // Also allow direct UID entry
        View btnAddByUid = dialogView != null ? dialogView.findViewById(R.id.btn_add_by_uid) : null;
        if (btnAddByUid != null) {
            btnAddByUid.setOnClickListener(v -> {
                dialog.dismiss();
                showAddByUidDialog();
            });
        }

        dialog.show();
    }

    private void searchUsers(String query, List<UserSearchEntry> results, UserSearchAdapter adapter) {
        FirebaseUtils.db().getReference("users")
            .orderByChild("nameLower").startAt(query).endAt(query + "\uf8ff")
            .limitToFirst(20)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snap) {
                    results.clear();
                    for (DataSnapshot ds : snap.getChildren()) {
                        String uid  = ds.getKey();
                        String name = ds.child("name").getValue(String.class);
                        String icon = ds.child("photoUrl").getValue(String.class);
                        if (uid == null || uid.equals(ownerUid)) continue;
                        // Skip existing admins
                        boolean alreadyAdmin = false;
                        for (AdminEntry ae : admins) if (ae.uid.equals(uid)) { alreadyAdmin = true; break; }
                        if (alreadyAdmin) continue;
                        results.add(new UserSearchEntry(uid, name != null ? name : uid, icon));
                    }
                    adapter.notifyDataSetChanged();
                }
                @Override public void onCancelled(@NonNull DatabaseError e) {}
            });
    }

    private void showAddByUidDialog() {
        EditText et = new EditText(this);
        et.setHint("User UID");
        et.setPadding(40, 20, 40, 20);
        new AlertDialog.Builder(this)
            .setTitle("Add admin by UID")
            .setView(et)
            .setPositiveButton("Add", (d, w) -> {
                String uid = et.getText().toString().trim();
                if (!uid.isEmpty()) viewModel.addAdmin(channelId, uid);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Transfer Ownership ────────────────────────────────────────────────

    private void showTransferOwnershipDialog(AdminEntry target) {
        if (!viewModel.getMyUid().equals(ownerUid)) {
            Toast.makeText(this, "Only the owner can transfer ownership.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Transfer ownership")
            .setMessage("Transfer channel ownership to " + target.name + "?\n\n"
                + "You will become an admin. This action cannot be undone.")
            .setPositiveButton("Transfer", (d, w) -> {
                viewModel.transferOwnership(channelId, ownerUid, target.uid);
                ownerUid = target.uid; // update local state
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── Adapter ───────────────────────────────────────────────────────────

    class AdminAdapter extends RecyclerView.Adapter<AdminAdapter.VH> {
        private final List<AdminEntry> list = new ArrayList<>();

        void setAdmins(List<AdminEntry> entries) {
            list.clear();
            list.addAll(entries);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_channel_admin, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            AdminEntry e = list.get(pos);
            h.tvName.setText(e.name != null ? e.name : e.uid);
            boolean isOwner = "owner".equals(e.role);
            h.tvRole.setText(isOwner ? "Owner" : "Admin");
            h.tvRole.setTextColor(isOwner ? 0xFF0075C9 : 0xFF25D366);

            if (h.ivIcon != null && e.iconUrl != null && !e.iconUrl.isEmpty())
                Glide.with(ChannelAdminActivity.this).load(e.iconUrl).circleCrop().into(h.ivIcon);

            // Remove button: only visible to owner, only for non-owner admins
            boolean canRemove = !isOwner && viewModel.getMyUid().equals(ownerUid);
            h.btnRemove.setVisibility(canRemove ? View.VISIBLE : View.GONE);
            h.btnRemove.setOnClickListener(v ->
                new AlertDialog.Builder(ChannelAdminActivity.this)
                    .setTitle("Remove admin?")
                    .setMessage(e.name + " will lose admin access.")
                    .setPositiveButton("Remove", (d, w) -> viewModel.removeAdmin(channelId, e.uid))
                    .setNegativeButton("Cancel", null)
                    .show());

            // Long press → transfer ownership (only for admin rows, by owner)
            h.itemView.setOnLongClickListener(v -> {
                if (!isOwner && viewModel.getMyUid().equals(ownerUid)) {
                    showTransferOwnershipDialog(e);
                    return true;
                }
                return false;
            });
        }

        @Override public int getItemCount() { return list.size(); }

        class VH extends RecyclerView.ViewHolder {
            CircleImageView ivIcon;
            TextView        tvName, tvRole;
            ImageButton     btnRemove;
            VH(View v) {
                super(v);
                ivIcon    = v.findViewById(R.id.iv_admin_icon);
                tvName    = v.findViewById(R.id.tv_admin_name);
                tvRole    = v.findViewById(R.id.tv_admin_role);
                btnRemove = v.findViewById(R.id.btn_remove_admin);
            }
        }
    }

    // ── User search adapter ────────────────────────────────────────────────

    static class UserSearchAdapter extends RecyclerView.Adapter<UserSearchAdapter.VH> {
        interface OnUserSelected { void onSelected(UserSearchEntry entry); }
        private final List<UserSearchEntry> list;
        private final OnUserSelected cb;
        UserSearchAdapter(List<UserSearchEntry> list, OnUserSelected cb) {
            this.list = list; this.cb = cb;
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false));
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            UserSearchEntry e = list.get(pos);
            h.text1.setText(e.name);
            h.text2.setText(e.uid.substring(0, Math.min(e.uid.length(), 12)) + "…");
            h.itemView.setOnClickListener(v -> { if (cb != null) cb.onSelected(e); });
        }
        @Override public int getItemCount() { return list.size(); }
        static class VH extends RecyclerView.ViewHolder {
            TextView text1, text2;
            VH(View v) {
                super(v);
                text1 = v.findViewById(android.R.id.text1);
                text2 = v.findViewById(android.R.id.text2);
            }
        }
    }

    // ── Data classes ──────────────────────────────────────────────────────

    static class AdminEntry {
        String uid, role, name, iconUrl;
        AdminEntry(String uid, String role) { this.uid = uid; this.role = role; }
    }

    static class UserSearchEntry {
        String uid, name, iconUrl;
        UserSearchEntry(String uid, String name, String iconUrl) {
            this.uid = uid; this.name = name; this.iconUrl = iconUrl;
        }
    }
}
